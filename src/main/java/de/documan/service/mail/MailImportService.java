package de.documan.service.mail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import de.documan.config.DocuManProperties;
import de.documan.domain.AfterImport;
import de.documan.domain.AttachmentFilter;
import de.documan.domain.Document;
import de.documan.domain.DocumentSource;
import de.documan.domain.MailAccount;
import de.documan.domain.MailHeaders;
import de.documan.domain.MailRule;
import de.documan.repo.MailRepository;
import de.documan.service.DuplicateDocumentException;
import de.documan.service.FileNames;
import de.documan.service.IngestException;
import de.documan.service.IngestService;
import de.documan.service.mail.MailboxClient.Attachment;
import de.documan.service.mail.MailboxClient.Criteria;
import de.documan.service.mail.MailboxClient.Header;
import de.documan.service.mail.MailboxClient.MailMessage;

/** Importiert PDF- und Bildanhänge aus IMAP-Postfächern. */
@Service
public class MailImportService {

    private static final Logger log = LoggerFactory.getLogger(MailImportService.class);
    private static final int MAX_HEADERS_PER_RUN = 1000;
    private static final int MAX_DOWNLOADS_PER_RUN = 100;
    private static final int SNIFF_BYTES = 8192;
    private static final int MAX_KEY_LENGTH = 500;

    /** Stellt die Verbindung her. Tests ersetzen sie durch eine Attrappe. */
    public interface Connector {
        MailboxClient connect(MailAccount account) throws Exception;
    }

    private final DocuManProperties properties;
    private final MailRepository accounts;
    private final IngestService ingest;
    private Connector connector;

    public MailImportService(DocuManProperties properties, MailRepository accounts, IngestService ingest,
            ImapConnector imap) {
        this.properties = properties;
        this.accounts = accounts;
        this.ingest = ingest;
        this.connector = imap::connect;
    }

    /** Nur für Tests. */
    public void useConnector(Connector replacement) {
        this.connector = replacement;
    }

    /** Prüft die Zugangsdaten und gibt die Ordner des Postfachs zurück. */
    public List<String> testConnection(MailAccount account) throws Exception {
        try (MailboxClient mailbox = connector.connect(account)) {
            return mailbox.folders();
        }
    }

    private static String messageKey(Header header) {
        String messageId = header.messageId().strip();
        if (!messageId.isEmpty()) {
            return messageId.length() > MAX_KEY_LENGTH ? messageId.substring(0, MAX_KEY_LENGTH) : messageId;
        }
        String fingerprint = header.date() + "|" + header.from() + "|" + header.subject();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Criteria criteria(MailAccount account) {
        LocalDate since = account.maximumAgeDays() == 0 ? null
                : LocalDate.now(properties.getTimeZone()).minusDays(account.maximumAgeDays());
        return new Criteria(account.onlyUnseen(), since);
    }

    private static boolean passesFilters(MailAccount account, Header header) {
        return contains(header.from(), account.filterFrom()) && contains(header.subject(), account.filterSubject())
                && !(account.skipSpam() && header.spam())
                && account.exclusionRules().stream().noneMatch(rule -> matches(rule, header));
    }

    private static boolean matches(MailRule rule, Header header) {
        String text = switch (rule.field()) {
            case FROM -> header.from();
            case TO -> header.to();
            case SUBJECT -> header.subject();
        };
        return contains(text, rule.value());
    }

    private static boolean contains(String text, String part) {
        return part.isEmpty() || text.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    /** Liefert importierbare Anhänge. Inline-Bilder sind meist Logos und Signaturen. */
    private List<Attachment> wantedAttachments(MailAccount account, MailMessage message) throws Exception {
        List<Attachment> wanted = new ArrayList<>();
        for (Attachment attachment : message.attachments()) {
            byte[] payload = attachment.payload();
            String mimeType = ingest.detectMimeType(Arrays.copyOf(payload, Math.min(payload.length, SNIFF_BYTES)));
            if (mimeType.equals("application/pdf")) {
                wanted.add(attachment);
            } else if (!attachment.disposition().equals("attachment")) {
                continue;
            } else if (account.attachments() == AttachmentFilter.FILES
                    || account.attachments() == AttachmentFilter.ALL && Document.OCR_IMAGE_TYPES.contains(mimeType)) {
                wanted.add(attachment);
            }
        }
        return wanted;
    }

    private static String title(Header header, Attachment attachment, int attachmentCount) {
        String subject = header.subject().strip();
        String stem = FileNames.stem(attachment.filename().isEmpty() ? "Anhang" : attachment.filename());
        if (subject.isEmpty()) {
            return stem;
        }
        return attachmentCount == 1 ? subject : subject + " – " + stem;
    }

    private record ImportResult(int imported, boolean hadAttachments) {
    }

    private ImportResult importMessage(MailAccount account, MailMessage message) throws Exception {
        List<Attachment> attachments = wantedAttachments(account, message);
        int imported = 0;
        for (Attachment attachment : attachments) {
            Header header = message.header();
            try {
                String filename = attachment.filename().isEmpty() ? "anhang.pdf" : attachment.filename();
                ingest.ingest(new ByteArrayResource(attachment.payload()), filename, DocumentSource.MAIL,
                        new IngestService.Options(title(header, attachment, attachments.size()),
                                header.from() + ": " + header.subject(), IngestService.MAIL_LABEL + ": " + account.name(),
                                new MailHeaders(header.from(), header.to(), header.subject(), header.sent(),
                                        header.messageId().strip()),
                                account.assignCorrespondentId(), account.assignDocumentTypeId(),
                                account.assignTagIds(), List.of()));
                imported++;
            } catch (DuplicateDocumentException duplicate) {
                log.info("Anhang {} ist bereits vorhanden", attachment.filename());
            } catch (IngestException error) {
                log.warn("Anhang {} abgelehnt: {}", attachment.filename(), error.getMessage());
            }
        }
        return new ImportResult(imported, !attachments.isEmpty());
    }

    private static void afterImport(MailAccount account, MailboxClient mailbox, long uid) throws Exception {
        if (account.afterImport() == AfterImport.MARK_SEEN) {
            mailbox.markSeen(uid);
        } else if (account.afterImport() == AfterImport.MOVE && !account.targetFolder().isEmpty()) {
            mailbox.move(uid, account.targetFolder());
        } else if (account.afterImport() == AfterImport.DELETE) {
            mailbox.delete(uid);
        }
    }

    /** Holt neue Nachrichten ab. Zuerst nur die Kopfzeilen, um nichts doppelt zu laden. */
    public int pollAccount(MailAccount account) throws Exception {
        if (account.skipSpam() && SpamMarks.isSpamFolder(account.folder())) {
            throw new IllegalStateException("Spam-Ordner werden nicht abgerufen: " + account.folder());
        }
        int imported = 0;
        try (MailboxClient mailbox = connector.connect(account)) {
            List<Header> headers = new ArrayList<>(mailbox.headers(criteria(account), MAX_HEADERS_PER_RUN));
            Set<String> known = accounts.knownMessages(account.id(),
                    headers.stream().map(MailImportService::messageKey).toList());
            // Älteste zuerst: Bei Weiterleitungen bestimmt so die ursprüngliche Mail den Titel.
            Collections.reverse(headers);
            List<Header> wanted = headers.stream()
                    .filter(header -> !known.contains(messageKey(header)) && passesFilters(account, header))
                    .limit(MAX_DOWNLOADS_PER_RUN).toList();
            accounts.setPollTotal(account.id(), wanted.size());
            int done = 0;
            for (Header header : wanted) {
                imported += processMessage(account, mailbox, header);
                accounts.setPollDone(account.id(), ++done);
            }
        }
        return imported;
    }

    /** Importiert eine Nachricht und gibt die Zahl der neuen Dokumente zurück. */
    private int processMessage(MailAccount account, MailboxClient mailbox, Header header) throws Exception {
        var message = mailbox.fetch(header.uid());
        if (message.isEmpty()) {
            return 0;
        }
        ImportResult result = importMessage(account, message.get());
        // Erst merken, dann im Postfach ändern: Stürzt etwas dazwischen ab,
        // bleibt die Nachricht liegen, wird aber nicht doppelt importiert.
        String subject = header.subject();
        boolean remembered = accounts.rememberMessage(account.id(), messageKey(header),
                subject.length() > MAX_KEY_LENGTH ? subject.substring(0, MAX_KEY_LENGTH) : subject);
        if (remembered && result.hadAttachments()) {
            afterImport(account, mailbox, header.uid());
        }
        return result.imported();
    }

    /** Ein beendeter Worker hinterlässt sonst einen Abruf, der nie fertig wird. */
    public void recoverPolls() {
        accounts.clearPolls();
    }

    /** Ruft alle fälligen Postfächer ab. Ein Fehler betrifft immer nur ein Postfach. */
    public void pollDueAccounts() {
        Instant now = Instant.now();
        for (MailAccount account : accounts.enabled()) {
            Instant threshold = now.minus(Duration.ofMinutes(account.pollMinutes()));
            if (account.lastCheckAt() != null && account.lastCheckAt().isAfter(threshold)) {
                continue;
            }
            String error = "";
            int imported = 0;
            accounts.startPoll(account.id());
            try {
                imported = pollAccount(account);
                if (imported > 0) {
                    log.info("Postfach {}: {} Dokumente importiert", account.name(), imported);
                }
            } catch (Exception problem) {
                error = problem.getMessage() != null ? problem.getMessage() : problem.getClass().getSimpleName();
                log.warn("Postfach {}: {}", account.name(), error);
            }
            accounts.recordCheck(account.id(), Instant.now(), error, imported);
        }
    }
}
