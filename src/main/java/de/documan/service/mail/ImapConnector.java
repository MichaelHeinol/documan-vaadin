package de.documan.service.mail;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.springframework.stereotype.Component;

import de.documan.domain.MailAccount;
import de.documan.domain.MailAuth;
import de.documan.domain.MailSecurity;
import de.documan.service.SecretBox;
import jakarta.mail.Address;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;

/** Verbindet sich per IMAP. Das Zertifikat des Servers wird immer geprüft. */
@Component
public class ImapConnector {

    private static final String TIMEOUT_MILLISECONDS = "60000";

    private final SecretBox secrets;
    private final MicrosoftLogin microsoft;

    public ImapConnector(SecretBox secrets, MicrosoftLogin microsoft) {
        this.secrets = secrets;
        this.microsoft = microsoft;
    }

    private static Properties properties(MailSecurity security, MailAuth auth) {
        String protocol = security == MailSecurity.SSL ? "imaps" : "imap";
        String prefix = "mail." + protocol + ".";
        Properties properties = new Properties();
        if (auth == MailAuth.MICROSOFT) {
            properties.put(prefix + "auth.mechanisms", "XOAUTH2");
        }
        properties.put(prefix + "connectiontimeout", TIMEOUT_MILLISECONDS);
        properties.put(prefix + "timeout", TIMEOUT_MILLISECONDS);
        properties.put(prefix + "writetimeout", TIMEOUT_MILLISECONDS);
        // Lesen darf die Nachricht nicht als gelesen markieren.
        properties.put(prefix + "peek", "true");
        if (security != MailSecurity.NONE) {
            // Sonst ginge das Passwort auch an einen vorgeschalteten Angreifer.
            properties.put(prefix + "ssl.checkserveridentity", "true");
        }
        if (security == MailSecurity.STARTTLS) {
            properties.put(prefix + "starttls.enable", "true");
            properties.put(prefix + "starttls.required", "true");
        }
        return properties;
    }

    public MailboxClient connect(MailAccount account) throws MessagingException, IOException, InterruptedException {
        Session session = Session.getInstance(properties(account.security(), account.auth()));
        Store store = session.getStore(account.security() == MailSecurity.SSL ? "imaps" : "imap");
        String secret = account.auth() == MailAuth.MICROSOFT ? microsoft.accessToken(account)
                : secrets.decrypt(account.passwordEncrypted());
        store.connect(account.host(), account.port(), account.username(), secret);
        try {
            Folder folder = store.getFolder(account.folder());
            folder.open(Folder.READ_WRITE);
            return new ImapMailbox((IMAPStore) store, (IMAPFolder) folder);
        } catch (MessagingException | RuntimeException error) {
            store.close();
            throw error;
        }
    }

    private record ImapMailbox(IMAPStore store, IMAPFolder folder) implements MailboxClient {

        @Override
        public List<Header> headers(Criteria criteria, int limit) throws MessagingException {
            Message[] found = search(criteria);
            Message[] newest = Arrays.copyOfRange(found, Math.max(0, found.length - limit), found.length);
            FetchProfile profile = new FetchProfile();
            profile.add(FetchProfile.Item.ENVELOPE);
            profile.add(FetchProfile.Item.FLAGS);
            profile.add(IMAPFolder.FetchProfileItem.HEADERS);
            profile.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(newest, profile);
            List<Header> headers = new ArrayList<>();
            for (int index = newest.length - 1; index >= 0; index--) {
                headers.add(header(newest[index]));
            }
            return headers;
        }

        private Message[] search(Criteria criteria) throws MessagingException {
            List<SearchTerm> terms = new ArrayList<>();
            if (criteria.unseenOnly()) {
                terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            }
            if (criteria.since() != null) {
                Date since = Date.from(criteria.since().atStartOfDay(ZoneId.systemDefault()).toInstant());
                terms.add(new ReceivedDateTerm(ComparisonTerm.GE, since));
            }
            return switch (terms.size()) {
                case 0 -> folder.getMessages();
                case 1 -> folder.search(terms.getFirst());
                default -> folder.search(new AndTerm(terms.toArray(SearchTerm[]::new)));
            };
        }

        private Header header(Message message) throws MessagingException {
            String[] messageId = message.getHeader("Message-ID");
            String[] date = message.getHeader("Date");
            Date sent = message.getSentDate();
            String subject = message.getSubject() == null ? "" : message.getSubject();
            return new Header(folder.getUID(message), messageId == null ? "" : messageId[0],
                    date == null ? "" : date[0], sender(message), recipients(message), subject,
                    sent == null ? null : sent.toInstant(),
                    SpamMarks.marked(allHeaders(message), subject, List.of(message.getFlags().getUserFlags())));
        }

        private static Map<String, List<String>> allHeaders(Message message) throws MessagingException {
            Map<String, List<String>> headers = new HashMap<>();
            for (var lines = message.getAllHeaders(); lines.hasMoreElements();) {
                var line = lines.nextElement();
                headers.computeIfAbsent(line.getName().toLowerCase(Locale.ROOT), name -> new ArrayList<>())
                        .add(line.getValue());
            }
            return headers;
        }

        private static String sender(Message message) throws MessagingException {
            Address[] from = message.getFrom();
            return from == null || from.length == 0 ? "" : address(from[0]);
        }

        private static String recipients(Message message) throws MessagingException {
            Address[] to = message.getRecipients(Message.RecipientType.TO);
            return to == null ? "" : Arrays.stream(to).map(ImapMailbox::address).collect(Collectors.joining(", "));
        }

        private static String address(Address entry) {
            return entry instanceof InternetAddress address ? address.getAddress() : entry.toString();
        }

        @Override
        public Optional<MailMessage> fetch(long uid) throws MessagingException, IOException {
            Message message = folder.getMessageByUID(uid);
            if (message == null) {
                return Optional.empty();
            }
            List<Attachment> attachments = new ArrayList<>();
            collect(message, attachments);
            return Optional.of(new MailMessage(header(message), attachments));
        }

        /** Teile mit Dateiname oder Content-ID gelten als Anhang, Bilder aus Signaturen also auch. */
        private static void collect(Part part, List<Attachment> attachments) throws MessagingException, IOException {
            if (part.isMimeType("multipart/*")) {
                Multipart multipart = (Multipart) part.getContent();
                for (int index = 0; index < multipart.getCount(); index++) {
                    collect(multipart.getBodyPart(index), attachments);
                }
            } else if (part.isMimeType("message/rfc822")) {
                collect((Part) part.getContent(), attachments);
            } else if (part.getFileName() != null || part.getHeader("Content-ID") != null) {
                String filename = part.getFileName() == null ? "" : MimeUtility.decodeText(part.getFileName());
                String disposition = part.getDisposition() == null ? "" : part.getDisposition().toLowerCase(Locale.ROOT);
                try (var stream = part.getInputStream()) {
                    attachments.add(new Attachment(filename, disposition, stream.readAllBytes()));
                }
            }
        }

        private Message require(long uid) throws MessagingException {
            Message message = folder.getMessageByUID(uid);
            if (message == null) {
                throw new MessagingException("Nachricht " + uid + " ist nicht mehr vorhanden.");
            }
            return message;
        }

        @Override
        public void markSeen(long uid) throws MessagingException {
            require(uid).setFlag(Flags.Flag.SEEN, true);
        }

        @Override
        public void move(long uid, String target) throws MessagingException {
            Message[] messages = {require(uid)};
            Folder destination = store.getFolder(target);
            if (store.hasCapability("MOVE")) {
                folder.moveMessages(messages, destination);
            } else {
                folder.copyMessages(messages, destination);
                delete(uid);
            }
        }

        @Override
        public void delete(long uid) throws MessagingException {
            require(uid).setFlag(Flags.Flag.DELETED, true);
            folder.expunge();
        }

        @Override
        public List<String> folders() throws MessagingException {
            List<String> names = new ArrayList<>();
            for (Folder entry : store.getDefaultFolder().list("*")) {
                names.add(entry.getFullName());
            }
            return names;
        }

        @Override
        public void close() {
            try {
                folder.close(false);
            } catch (MessagingException | IllegalStateException alreadyClosed) {
                // Die Verbindung ist ohnehin zu Ende.
            }
            try {
                store.close();
            } catch (MessagingException alreadyClosed) {
                // Die Verbindung ist ohnehin zu Ende.
            }
        }
    }
}
