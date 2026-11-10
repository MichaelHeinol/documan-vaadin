package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;

import de.documan.domain.Document;
import de.documan.domain.MailAccount;
import de.documan.domain.MailSecurity;
import de.documan.repo.MailRepository;
import de.documan.service.SecretBox;
import de.documan.service.mail.ImapConnector;
import de.documan.service.mail.MailImportService;
import de.documan.service.mail.MailboxClient;
import de.documan.service.mail.MailboxClient.Criteria;
import de.documan.service.mail.MailboxClient.Header;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.activation.DataHandler;

/** Spricht echtes IMAP mit einem eingebetteten Mailserver. */
class ImapMailboxTests extends IntegrationTest {

    private static final String ADDRESS = "rechnungen@example.org";
    private static final String PASSWORD = "imap-passwort";

    @Autowired
    ImapConnector connector;
    @Autowired
    MailImportService imports;
    @Autowired
    MailRepository accounts;
    @Autowired
    SecretBox secrets;

    private GreenMail server;
    private GreenMailUser user;
    private String spamFlag;

    @BeforeEach
    void startServer() {
        server = new GreenMail(new ServerSetup[] {ServerSetupTest.IMAP, ServerSetupTest.IMAPS});
        server.start();
        user = server.setUser(ADDRESS, ADDRESS, PASSWORD);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private MailAccount account(MailSecurity security, int port) {
        MailAccount blank = MailAccount.blank();
        long id = accounts.insert(new MailAccount(0, "Echt", true, "127.0.0.1", port, security, ADDRESS,
                secrets.encrypt(PASSWORD), "INBOX", true, 30, "", "", blank.attachments(), blank.afterImport(), "",
                null, null, List.of(), null, "", 0));
        return accounts.find(id).orElseThrow();
    }

    private MailAccount plainAccount() {
        return account(MailSecurity.NONE, server.getImap().getPort());
    }

    private void deliver(String subject, String filename, byte[] attachment) throws Exception {
        MimeMessage message = new MimeMessage((Session) null);
        message.setFrom(new InternetAddress("Stadtwerke <info@stadtwerke.de>"));
        message.setRecipients(Message.RecipientType.TO, ADDRESS);
        message.setSubject(subject, "UTF-8");
        message.setSentDate(new java.util.Date());
        MimeMultipart parts = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Im Anhang finden Sie Ihr Dokument.", "UTF-8");
        parts.addBodyPart(text);
        if (attachment != null) {
            MimeBodyPart file = new MimeBodyPart();
            file.setDataHandler(new DataHandler(new ByteArrayDataSource(attachment, "application/pdf")));
            file.setFileName(jakarta.mail.internet.MimeUtility.encodeText(filename, "UTF-8", null));
            file.setDisposition(MimeBodyPart.ATTACHMENT);
            file.setHeader("Content-Transfer-Encoding", "base64");
            parts.addBodyPart(file);
        }
        message.setContent(parts);
        if (spamFlag != null) {
            message.setHeader("X-Spam-Flag", spamFlag);
        }
        message.saveChanges();
        user.deliver(message);
    }

    private static Criteria unread() {
        return new Criteria(true, LocalDate.now().minusDays(30));
    }

    @Test
    void importsAnAttachmentAndMarksTheMailAsRead() throws Exception {
        deliver("Ihre Stromrechnung für März", "Stromrechnung März.pdf", Samples.textPdf("Abschlag"));

        assertThat(imports.pollAccount(plainAccount())).isEqualTo(1);

        Document document = reload(jdbc.sql("SELECT id FROM document").query(Long.class).single());
        assertThat(document.title()).isEqualTo("Ihre Stromrechnung für März");
        assertThat(document.originalFilename()).isEqualTo("Stromrechnung März.pdf");
        assertThat(document.sourceDetail()).startsWith("info@stadtwerke.de: ");
        assertThat(document.addedBy()).isEqualTo("E-Mail: Echt");
        assertThat(document.mail().from()).isEqualTo("info@stadtwerke.de");
        assertThat(document.mail().to()).isEqualTo(ADDRESS);
        assertThat(document.mail().subject()).isEqualTo("Ihre Stromrechnung für März");
        assertThat(document.mail().date()).isCloseTo(java.time.Instant.now(),
                org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.MINUTES));
        assertThat(document.mail().messageId()).startsWith("<").endsWith(">");
        MimeMessage stored = server.getReceivedMessages()[0];
        assertThat(stored.isSet(Flags.Flag.SEEN)).isTrue();
    }

    @Test
    void readingHeadersAndAttachmentsLeavesTheMailUnread() throws Exception {
        deliver("Vertrag", "vertrag.pdf", Samples.textPdf("Text"));
        try (MailboxClient mailbox = connector.connect(plainAccount())) {
            Header header = mailbox.headers(unread(), 10).getFirst();
            assertThat(header.from()).isEqualTo("info@stadtwerke.de");
            assertThat(header.subject()).isEqualTo("Vertrag");
            assertThat(header.messageId()).isNotEmpty();
            var message = mailbox.fetch(header.uid()).orElseThrow();
            assertThat(message.attachments()).hasSize(1);
            assertThat(message.attachments().getFirst().disposition()).isEqualTo("attachment");
            assertThat(message.attachments().getFirst().payload()).isEqualTo(Samples.textPdf("Text"));
        }
        assertThat(server.getReceivedMessages()[0].isSet(Flags.Flag.SEEN)).isFalse();
    }

    @Test
    void spamHeaderKeepsTheMailOutAndUnread() throws Exception {
        spamFlag = "YES";
        deliver("Gewinn", "gewinn.pdf", Samples.textPdf("Gewinn"));
        spamFlag = "NO";
        deliver("Rechnung", "rechnung.pdf", Samples.textPdf("Rechnung"));

        assertThat(imports.pollAccount(plainAccount())).isEqualTo(1);

        assertThat(documents.total()).isEqualTo(1);
        assertThat(server.getReceivedMessages()[0].isSet(Flags.Flag.SEEN)).isFalse();
        assertThat(server.getReceivedMessages()[1].isSet(Flags.Flag.SEEN)).isTrue();
    }

    @Test
    void junkFlagKeepsTheMailOut() throws Exception {
        deliver("Gewinn", "gewinn.pdf", Samples.textPdf("Gewinn"));
        try (Store store = Session.getInstance(new java.util.Properties()).getStore("imap")) {
            store.connect("127.0.0.1", server.getImap().getPort(), ADDRESS, PASSWORD);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            inbox.getMessage(1).setFlags(new Flags("$Junk"), true);
            inbox.close(false);
        }

        assertThat(imports.pollAccount(plainAccount())).isZero();
    }

    @Test
    void searchFindsOnlyUnreadMail() throws Exception {
        deliver("Erste", "a.pdf", Samples.textPdf("A"));
        deliver("Zweite", "b.pdf", Samples.textPdf("B"));
        try (MailboxClient mailbox = connector.connect(plainAccount())) {
            List<Header> headers = mailbox.headers(unread(), 10);
            assertThat(headers).extracting(Header::subject).containsExactly("Zweite", "Erste");
            mailbox.markSeen(headers.getFirst().uid());
            assertThat(mailbox.headers(unread(), 10)).extracting(Header::subject).containsExactly("Erste");
            assertThat(mailbox.headers(new Criteria(false, null), 10)).hasSize(2);
            assertThat(mailbox.headers(new Criteria(false, null), 1)).extracting(Header::subject)
                    .containsExactly("Zweite");
        }
    }

    @Test
    void movesAndDeletesMessages() throws Exception {
        deliver("Weg", "a.pdf", Samples.textPdf("A"));
        deliver("Archiv", "b.pdf", Samples.textPdf("B"));
        try (MailboxClient mailbox = connector.connect(plainAccount())) {
            server.getManagers().getImapHostManager().createMailbox(user, "Archiv");
            List<Header> headers = mailbox.headers(unread(), 10);
            mailbox.move(headers.get(0).uid(), "Archiv");
            mailbox.delete(headers.get(1).uid());
            assertThat(mailbox.headers(new Criteria(false, null), 10)).isEmpty();
            assertThat(mailbox.folders()).contains("INBOX", "Archiv");
        }
    }

    @Test
    void wrongPasswordIsReported() {
        MailAccount account = plainAccount();
        MailAccount wrong = new MailAccount(account.id(), account.name(), true, account.host(), account.port(),
                account.security(), account.username(), secrets.encrypt("falsch"), account.folder(), true, 30, "", "",
                account.attachments(), account.afterImport(), "", null, null, List.of(), null, "", 0);
        assertThatThrownBy(() -> imports.testConnection(wrong)).isInstanceOf(Exception.class);
    }

    @Test
    void selfSignedCertificateIsRefused() {
        MailAccount account = account(MailSecurity.SSL, server.getImaps().getPort());
        assertThatThrownBy(() -> imports.testConnection(account))
                .hasCauseInstanceOf(javax.net.ssl.SSLHandshakeException.class);
    }

    @Test
    void headerEncodingIsDecoded() throws Exception {
        deliver("Grüße aus Köln", "x.pdf", null);
        try (MailboxClient mailbox = connector.connect(plainAccount())) {
            assertThat(mailbox.headers(unread(), 10).getFirst().subject()).isEqualTo("Grüße aus Köln");
        }
        assertThat(GreenMailUtil.getBody(server.getReceivedMessages()[0]).getBytes(StandardCharsets.UTF_8)).isNotEmpty();
    }
}
