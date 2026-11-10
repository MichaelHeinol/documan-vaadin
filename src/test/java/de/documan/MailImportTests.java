package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import de.documan.domain.AfterImport;
import de.documan.domain.AttachmentFilter;
import de.documan.domain.Document;
import de.documan.domain.DocumentSource;
import de.documan.domain.MailAccount;
import de.documan.domain.MailRule;
import de.documan.domain.MailRuleField;
import de.documan.domain.MasterItem;
import de.documan.repo.MailRepository;
import de.documan.service.SecretBox;
import de.documan.service.mail.ImapConnector;
import de.documan.service.mail.MailImportService;
import de.documan.service.mail.MailboxClient;
import de.documan.service.mail.MailboxClient.Attachment;
import de.documan.service.mail.MailboxClient.Criteria;
import de.documan.service.mail.MailboxClient.Header;
import de.documan.service.mail.MailboxClient.MailMessage;

class MailImportTests extends IntegrationTest {

    /** Postfach aus dem Speicher. Die echte IMAP-Anbindung prüft {@link ImapMailboxTests}. */
    private static class FakeMailbox implements MailboxClient {
        final Map<Long, MailMessage> messages = new LinkedHashMap<>();
        final List<Long> seen = new ArrayList<>();
        final List<String> moved = new ArrayList<>();
        final List<Long> deleted = new ArrayList<>();
        int fullFetches;
        Criteria criteria;

        FakeMailbox(MailMessage... mails) {
            for (MailMessage mail : mails) {
                messages.put(mail.header().uid(), mail);
            }
        }

        @Override
        public List<Header> headers(Criteria given, int limit) {
            criteria = given;
            return messages.values().stream().map(MailMessage::header).toList();
        }

        @Override
        public Optional<MailMessage> fetch(long uid) {
            fullFetches++;
            return Optional.ofNullable(messages.get(uid));
        }

        @Override
        public void markSeen(long uid) {
            seen.add(uid);
        }

        @Override
        public void move(long uid, String folder) {
            moved.add(uid + ">" + folder);
        }

        @Override
        public void delete(long uid) {
            deleted.add(uid);
        }

        @Override
        public List<String> folders() {
            return List.of("INBOX");
        }

        @Override
        public void close() {
            // Nichts zu schließen.
        }
    }

    private static Attachment attachment(byte[] data, String filename, String disposition) {
        return new Attachment(filename, disposition, data);
    }

    private static Attachment attachment(byte[] data, String filename) {
        return attachment(data, filename, "attachment");
    }

    private static Attachment attachment(byte[] data) {
        return attachment(data, "rechnung.pdf");
    }

    private static MailMessage message(long uid, String subject, String sender, Attachment... attachments) {
        return new MailMessage(new Header(uid, "<" + uid + "@example.org>", "Sat, 31 Oct 2026 10:00:00 +0100", sender,
                "konto@example.de, zweite@example.de", subject, java.time.Instant.parse("2026-10-31T09:00:00Z")),
                Arrays.asList(attachments));
    }

    private static MailMessage message(long uid, Attachment... attachments) {
        return message(uid, "Ihre Rechnung", "kunde@example.org", attachments);
    }

    @Autowired
    MailImportService imports;
    @Autowired
    MailRepository accounts;
    @Autowired
    SecretBox secrets;
    @Autowired
    ImapConnector imap;

    private MailAccount account;
    private FakeMailbox mailbox;

    private MailAccount changed(UnaryOperator<MailAccount> change) {
        accounts.update(change.apply(account));
        account = accounts.find(account.id()).orElseThrow();
        return account;
    }

    private static MailAccount with(MailAccount source, AfterImport after, String target) {
        return new MailAccount(source.id(), source.name(), source.enabled(), source.host(), source.port(),
                source.security(), source.username(), source.passwordEncrypted(), source.folder(),
                source.onlyUnseen(), source.maximumAgeDays(), source.filterFrom(), source.filterSubject(),
                source.attachments(), after, target, source.assignCorrespondentId(), source.assignDocumentTypeId(),
                source.assignTagIds(), source.lastCheckAt(), source.lastError(), source.importedCount());
    }

    private long accountNamed(String name) {
        MailAccount blank = MailAccount.blank();
        return accounts.insert(new MailAccount(0, name, true, "imap.example.org", 993, blank.security(), "u",
                secrets.encrypt("geheim"), "INBOX", true, 30, "", "", blank.attachments(), blank.afterImport(), "",
                null, null, List.of(), null, "", 0));
    }

    @BeforeEach
    void account() {
        account = accounts.find(accountNamed("Test")).orElseThrow();
    }

    @AfterEach
    void realConnector() {
        imports.useConnector(imap::connect);
    }

    private int poll(MailMessage... messages) throws Exception {
        mailbox = new FakeMailbox(messages);
        imports.useConnector(given -> mailbox);
        return imports.pollAccount(account);
    }

    private List<String> titles() {
        return jdbc.sql("SELECT title FROM document ORDER BY title").query(String.class).list();
    }

    private long processedMails() {
        return jdbc.sql("SELECT count(*) FROM processed_mail").query(Long.class).single();
    }

    private Document onlyDocument() {
        return reload(jdbc.sql("SELECT id FROM document").query(Long.class).single());
    }

    @Test
    void pdfAttachmentBecomesDocumentWithSubjectAsTitle() throws Exception {
        MasterItem tag = tag("Mail");
        MasterItem sender = correspondent("Kunde");
        changed(source -> new MailAccount(source.id(), source.name(), source.enabled(), source.host(), source.port(),
                source.security(), source.username(), source.passwordEncrypted(), source.folder(),
                source.onlyUnseen(), source.maximumAgeDays(), source.filterFrom(), source.filterSubject(),
                source.attachments(), source.afterImport(), source.targetFolder(), sender.id(), null,
                List.of(tag.id()), null, "", 0));

        assertThat(poll(message(1, attachment(Samples.textPdf("Hallo"))))).isEqualTo(1);

        Document document = onlyDocument();
        assertThat(document.title()).isEqualTo("Ihre Rechnung");
        assertThat(document.source()).isEqualTo(DocumentSource.MAIL);
        assertThat(document.correspondentId()).isEqualTo(sender.id());
        assertThat(document.tags()).extracting(MasterItem::id).containsExactly(tag.id());
        assertThat(document.sourceDetail()).contains("kunde@example.org");
        assertThat(mailbox.seen).containsExactly(1L);
    }

    @Test
    void everyAttachmentKeepsTheMailHeaders() throws Exception {
        poll(message(1, attachment(Samples.textPdf("A"), "a.pdf"), attachment(Samples.textPdf("B"), "b.pdf")));

        List<Long> ids = jdbc.sql("SELECT id FROM document ORDER BY id").query(Long.class).list();
        assertThat(ids).hasSize(2);
        for (long id : ids) {
            Document document = reload(id);
            assertThat(document.addedBy()).isEqualTo("E-Mail: " + account.name());
            assertThat(document.mail().from()).isEqualTo("kunde@example.org");
            assertThat(document.mail().to()).isEqualTo("konto@example.de, zweite@example.de");
            assertThat(document.mail().subject()).isEqualTo("Ihre Rechnung");
            assertThat(document.mail().date()).isEqualTo("2026-10-31T09:00:00Z");
            assertThat(document.mail().messageId()).isEqualTo("<1@example.org>");
        }
    }

    @Test
    void severalAttachmentsGetDistinctTitles() throws Exception {
        poll(message(1, attachment(Samples.textPdf("A"), "a.pdf"), attachment(Samples.textPdf("B"), "b.pdf")));
        assertThat(titles()).containsExactly("Ihre Rechnung – a", "Ihre Rechnung – b");
    }

    @Test
    void messageIsProcessedOnlyOnce() throws Exception {
        MailMessage mail = message(1, attachment(Samples.textPdf("Hallo")));
        poll(mail);
        assertThat(poll(mail)).isZero();
        assertThat(mailbox.fullFetches).isZero();
        assertThat(documents.total()).isEqualTo(1);
    }

    @Test
    void sameAttachmentInTwoMailsIsOneDocument() throws Exception {
        byte[] data = Samples.textPdf("Hallo");
        poll(message(1, attachment(data)), message(2, attachment(data)));
        assertThat(documents.total()).isEqualTo(1);
        assertThat(processedMails()).isEqualTo(2);
    }

    @Test
    void messageWithoutAttachmentIsLeftUnread() throws Exception {
        poll(message(1));
        assertThat(mailbox.seen).isEmpty();
        assertThat(processedMails()).isEqualTo(1);
    }

    @Test
    void onlyPdfByDefaultInlineImagesNever() throws Exception {
        byte[] png = Samples.png("x");
        Attachment[] files = {attachment(png, "foto.png"), attachment(png, "logo.png", "inline")};
        poll(message(1, files));
        assertThat(documents.total()).isZero();

        changed(source -> new MailAccount(source.id(), source.name(), source.enabled(), source.host(), source.port(),
                source.security(), source.username(), source.passwordEncrypted(), source.folder(),
                source.onlyUnseen(), source.maximumAgeDays(), source.filterFrom(), source.filterSubject(),
                AttachmentFilter.ALL, source.afterImport(), source.targetFolder(), null, null, List.of(), null, "", 0));
        poll(message(2, files));
        assertThat(onlyDocument().originalFilename()).isEqualTo("foto.png");
    }

    @Test
    void allAttachmentsAlsoBringOtherFormats() throws Exception {
        Attachment table = attachment("Datum;Betrag\n14.03.2026;99,50\n".getBytes(), "umsatz.csv");
        Attachment logo = attachment(Samples.png("y"), "logo.png", "inline");
        poll(message(1, table, logo));
        assertThat(documents.total()).isZero();

        changed(source -> new MailAccount(source.id(), source.name(), source.enabled(), source.host(), source.port(),
                source.security(), source.username(), source.passwordEncrypted(), source.folder(),
                source.onlyUnseen(), source.maximumAgeDays(), source.filterFrom(), source.filterSubject(),
                AttachmentFilter.FILES, source.afterImport(), source.targetFolder(), null, null, List.of(), null, "",
                0));
        poll(message(2, table, logo));
        assertThat(onlyDocument().originalFilename()).isEqualTo("umsatz.csv");
    }

    @Test
    void mislabelledAttachmentIsJudgedByContent() throws Exception {
        poll(message(1, attachment(new byte[] {'M', 'Z', (byte) 0x90, 0, 1, 2}, "rechnung.pdf")));
        assertThat(documents.total()).isZero();
    }

    @Test
    void filters() throws Exception {
        changed(source -> new MailAccount(source.id(), source.name(), source.enabled(), source.host(), source.port(),
                source.security(), source.username(), source.passwordEncrypted(), source.folder(),
                source.onlyUnseen(), source.maximumAgeDays(), "@stadtwerke", "rechnung", source.attachments(),
                source.afterImport(), source.targetFolder(), null, null, List.of(), null, "", 0));
        MailMessage hit = message(1, "Ihre RECHNUNG", "info@stadtwerke.de", attachment(Samples.textPdf("Treffer")));
        MailMessage wrongSender = message(2, "Rechnung", "x@example.org", attachment(Samples.textPdf("A")));
        MailMessage wrongSubject = message(3, "Newsletter", "a@stadtwerke.de", attachment(Samples.textPdf("B")));
        assertThat(poll(hit, wrongSender, wrongSubject)).isEqualTo(1);
        assertThat(mailbox.seen).containsExactly(1L);
    }

    private static MailAccount ruled(MailAccount source, boolean skipSpam, MailRule... rules) {
        return new MailAccount(source.id(), source.name(), source.enabled(), source.host(), source.port(),
                source.security(), source.username(), source.passwordEncrypted(), source.folder(),
                source.onlyUnseen(), source.maximumAgeDays(), source.filterFrom(), source.filterSubject(),
                source.attachments(), source.afterImport(), source.targetFolder(), null, null, List.of(), null, "", 0,
                source.auth(), skipSpam, List.of(rules));
    }

    private static MailMessage spam(long uid, Attachment attachment) {
        return new MailMessage(new Header(uid, "<" + uid + "@example.org>", "Sat, 31 Oct 2026 10:00:00 +0100",
                "kunde@example.org", "konto@example.de", "Gewinn", java.time.Instant.parse("2026-10-31T09:00:00Z"),
                true), List.of(attachment));
    }

    @Test
    void exclusionRulesLeaveTheMailUntouched() throws Exception {
        changed(source -> ruled(source, true, new MailRule(MailRuleField.FROM, "@WERBUNG.de"),
                new MailRule(MailRuleField.SUBJECT, "newsletter")));
        MailMessage wanted = message(1, attachment(Samples.textPdf("Treffer")));
        MailMessage sender = message(2, "Rechnung", "post@werbung.de", attachment(Samples.textPdf("A")));
        MailMessage subject = message(3, "Unser Newsletter im Mai", "x@example.org", attachment(Samples.textPdf("B")));

        assertThat(poll(wanted, sender, subject)).isEqualTo(1);

        assertThat(mailbox.seen).containsExactly(1L);
        assertThat(mailbox.fullFetches).isEqualTo(1);
        assertThat(accounts.processed(account.id())).hasSize(1);
    }

    @Test
    void exclusionRuleOnRecipient() throws Exception {
        changed(source -> ruled(source, true, new MailRule(MailRuleField.TO, "ZWEITE@")));
        assertThat(poll(message(1, attachment(Samples.textPdf("A"))))).isZero();
    }

    @Test
    void removingARuleBringsTheMailBack() throws Exception {
        changed(source -> ruled(source, true, new MailRule(MailRuleField.SUBJECT, "rechnung")));
        MailMessage mail = message(1, attachment(Samples.textPdf("A")));
        assertThat(poll(mail)).isZero();
        changed(source -> ruled(source, true));
        assertThat(poll(mail)).isEqualTo(1);
    }

    @Test
    void spamIsSkippedUnlessSwitchedOff() throws Exception {
        MailMessage marked = spam(1, attachment(Samples.textPdf("A")));
        MailMessage wanted = message(2, attachment(Samples.textPdf("B")));

        assertThat(poll(marked, wanted)).isEqualTo(1);
        assertThat(mailbox.seen).containsExactly(2L);
        assertThat(mailbox.fullFetches).isEqualTo(1);

        changed(source -> ruled(source, false));
        assertThat(poll(marked)).isEqualTo(1);
    }

    @Test
    void spamFolderIsRefused() throws Exception {
        changed(source -> new MailAccount(source.id(), source.name(), source.enabled(), source.host(), source.port(),
                source.security(), source.username(), source.passwordEncrypted(), "[Gmail]/Spam", source.onlyUnseen(),
                source.maximumAgeDays(), source.filterFrom(), source.filterSubject(), source.attachments(),
                source.afterImport(), source.targetFolder(), null, null, List.of(), null, "", 0));
        assertThatThrownBy(() -> poll()).hasMessageContaining("Spam-Ordner werden nicht abgerufen");

        changed(source -> ruled(source, false));
        assertThat(poll()).isZero();
    }

    @Test
    void afterImportActions() throws Exception {
        changed(source -> with(source, AfterImport.MOVE, "Archiv"));
        poll(message(1, attachment(Samples.textPdf("Eins"))));
        assertThat(mailbox.moved).containsExactly("1>Archiv");

        changed(source -> with(source, AfterImport.DELETE, ""));
        poll(message(2, attachment(Samples.textPdf("Zwei"))));
        assertThat(mailbox.deleted).containsExactly(2L);

        changed(source -> with(source, AfterImport.NOTHING, ""));
        poll(message(3, attachment(Samples.textPdf("Drei"))));
        assertThat(mailbox.seen).isEmpty();
    }

    @Test
    void failedImportLeavesMailUntouchedAndRetries() throws Exception {
        MailMessage mail = message(1, attachment(Samples.textPdf("Hallo")));
        Path blocked = workspace.resolve("keinOrdner");
        Files.writeString(blocked, "Datenträger voll");
        properties.setMediaRoot(blocked);
        assertThatThrownBy(() -> poll(mail)).isInstanceOf(UncheckedIOException.class);
        assertThat(mailbox.seen).isEmpty();
        assertThat(processedMails()).isZero();

        properties.setMediaRoot(mediaRoot);
        assertThat(poll(mail)).isEqualTo(1);
    }

    @Test
    void criteriaRespectUnseenAndAge() throws Exception {
        poll();
        assertThat(mailbox.criteria.unseenOnly()).isTrue();
        assertThat(mailbox.criteria.since()).isNotNull();
        changed(source -> new MailAccount(source.id(), source.name(), source.enabled(), source.host(), source.port(),
                source.security(), source.username(), source.passwordEncrypted(), source.folder(), false, 0,
                source.filterFrom(), source.filterSubject(), source.attachments(), source.afterImport(),
                source.targetFolder(), null, null, List.of(), null, "", 0));
        poll();
        assertThat(mailbox.criteria.unseenOnly()).isFalse();
        assertThat(mailbox.criteria.since()).isNull();
    }

    @Test
    void errorInOneAccountIsRecordedAndDoesNotStopOthers() throws IOException {
        long second = accountNamed("Zweites");
        imports.useConnector(given -> {
            if (given.id() == account.id()) {
                throw new IOException("Server nicht erreichbar");
            }
            return new FakeMailbox(message(1, attachment(Samples.textPdf("Hallo"))));
        });
        imports.pollDueAccounts();
        assertThat(accounts.find(account.id()).orElseThrow().lastError()).contains("nicht erreichbar");
        MailAccount other = accounts.find(second).orElseThrow();
        assertThat(other.importedCount()).isEqualTo(1);
        assertThat(other.lastError()).isEmpty();
    }

    @Test
    void accountsArePolledOnlyWhenDue() {
        int[] connections = {0};
        imports.useConnector(given -> {
            connections[0]++;
            return new FakeMailbox();
        });
        accounts.recordCheck(account.id(), Instant.now(), "", 0);
        imports.pollDueAccounts();
        assertThat(connections[0]).isZero();

        accounts.recordCheck(account.id(), Instant.now().minusSeconds(3600), "", 0);
        imports.pollDueAccounts();
        assertThat(connections[0]).isEqualTo(1);
    }

    private MailRepository.PollProgress progress() {
        return accounts.pollProgress().getFirst();
    }

    @Test
    void runningPollReportsItsProgress() throws Exception {
        List<String> reported = new ArrayList<>();
        FakeMailbox watched = new FakeMailbox(message(1, attachment(Samples.textPdf("Beleg eins"))),
                message(2, attachment(Samples.textPdf("Beleg zwei"))),
                message(3, attachment(Samples.textPdf("Beleg drei")))) {
            @Override
            public Optional<MailMessage> fetch(long uid) {
                var now = progress();
                reported.add(now.state() + " " + now.done() + "/" + now.total());
                return super.fetch(uid);
            }
        };
        imports.useConnector(given -> watched);
        imports.pollDueAccounts();
        assertThat(reported).containsExactly("running 0/3", "running 1/3", "running 2/3");
        assertThat(progress()).isEqualTo(new MailRepository.PollProgress(account.id(), "idle", 0, 0));
    }

    @Test
    void totalCountsOnlyNewAndWantedMessages() throws Exception {
        changed(source -> ruled(source, true, new MailRule(MailRuleField.SUBJECT, "Werbung")));
        accounts.rememberMessage(account.id(), "<1@example.org>", "Alt");
        poll(message(1, attachment(Samples.textPdf("Alt"))),
                message(2, "Werbung", "kunde@example.org", attachment(Samples.textPdf("Werbung"))),
                message(3, attachment(Samples.textPdf("Neu"))));
        assertThat(progress().total()).isEqualTo(1);
        assertThat(progress().done()).isEqualTo(1);
    }

    @Test
    void aVanishedMessageStillCounts() throws Exception {
        FakeMailbox emptying = new FakeMailbox(message(1, attachment(Samples.textPdf("Weg")))) {
            @Override
            public Optional<MailMessage> fetch(long uid) {
                return Optional.empty();
            }
        };
        imports.useConnector(given -> emptying);
        imports.pollAccount(account);
        assertThat(progress().done()).isEqualTo(1);
        assertThat(progress().total()).isEqualTo(1);
    }

    @Test
    void failedPollEndsItsProgress() {
        imports.useConnector(given -> {
            throw new IOException("Server weg");
        });
        imports.pollDueAccounts();
        assertThat(progress().state()).isEqualTo("idle");
        assertThat(accounts.find(account.id()).orElseThrow().lastError()).contains("Server weg");
    }

    @Test
    void restartedWorkerClearsAnInterruptedPoll() {
        accounts.startPoll(account.id());
        accounts.setPollTotal(account.id(), 5);
        accounts.setPollDone(account.id(), 2);
        assertThat(progress()).isEqualTo(new MailRepository.PollProgress(account.id(), "running", 2, 5));
        accounts.recordCheck(account.id(), Instant.now(), "", 0);
        accounts.startPoll(account.id());
        imports.recoverPolls();
        assertThat(progress().state()).isEqualTo("idle");
    }

    @Test
    void requestedFetchIsWaiting() {
        accounts.recordCheck(account.id(), Instant.now(), "", 0);
        assertThat(progress().state()).isEqualTo("idle");
        accounts.markDue(account.id());
        assertThat(progress().state()).isEqualTo("waiting");
    }

    @Test
    void disabledAccountNeverWaits() {
        jdbc.sql("UPDATE mail_account SET enabled = false, last_check_at = NULL").update();
        assertThat(progress().state()).isEqualTo("idle");
    }



    private boolean polledAfter(int minutes, int pollMinutes) {
        jdbc.sql("UPDATE mail_account SET poll_minutes = ?, last_check_at = now() - make_interval(mins => ?)")
                .params(pollMinutes, minutes).update();
        boolean[] connected = {false};
        imports.useConnector(given -> {
            connected[0] = true;
            return new FakeMailbox();
        });
        imports.pollDueAccounts();
        return connected[0];
    }

    @Test
    void newAccountsPollHourly() {
        assertThat(account.pollMinutes()).isEqualTo(60);
    }

    @Test
    void hourlyAccountWaitsForTheHour() {
        assertThat(polledAfter(59, 60)).isFalse();
        assertThat(polledAfter(61, 60)).isTrue();
    }

    @Test
    void everyAccountKeepsItsOwnInterval() {
        assertThat(polledAfter(20, 15)).isTrue();
        assertThat(polledAfter(61, 120)).isFalse();
    }

    private String[] form(String... changes) {
        List<String> fields = new ArrayList<>(List.of("name", "Test", "enabled", "on", "host", "imap.example.org", "port",
                "993", "security", "ssl", "username", "u", "folder", "INBOX", "maximumAgeDays", "30", "attachments",
                "pdf", "afterImport", "seen"));
        fields.addAll(List.of(changes));
        return fields.toArray(String[]::new);
    }






    @Test
    void passwordIsEncryptedAtRest() {
        assertThat(secrets.decrypt(account.passwordEncrypted())).isEqualTo("geheim");
        assertThat(account.passwordEncrypted()).doesNotContain("geheim");
    }
}
