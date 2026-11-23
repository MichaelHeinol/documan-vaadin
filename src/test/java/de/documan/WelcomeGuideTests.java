package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;

import de.documan.domain.AppUser;
import de.documan.repo.UserRepository;
import de.documan.service.Notifications;
import de.documan.service.UserGuide;
import jakarta.mail.BodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

/** Die Begrüßungsmail nennt den Eingangsordner und bringt eine persönliche Kurzanleitung als PDF mit. */
class WelcomeGuideTests extends IntegrationTest {

    private static final String HOST_DIR = "/volume1/docker/documan/consume";

    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    Notifications notifications;
    @Autowired
    UserGuide guide;

    private GreenMail server;
    private Path consumeDir;
    private String consumeHostDir;
    private String browseHostDir;

    @BeforeEach
    void startServer() throws IOException {
        server = new GreenMail(ServerSetupTest.SMTP);
        server.start();
        properties.setEmailHost("127.0.0.1");
        properties.setEmailPort(server.getSmtp().getPort());
        properties.setEmailSecurity("none");
        properties.setEmailHostUser("");
        properties.setEmailHostPassword("");
        properties.setEmailFrom("documan@example.de");
        properties.setSiteUrl("https://dms.example.de/");
        consumeDir = properties.getConsumeDir();
        consumeHostDir = properties.getConsumeHostDir();
        browseHostDir = properties.getBrowseHostDir();
        properties.setConsumeDir(Files.createDirectories(workspace.resolve("eingang")));
        properties.setConsumeHostDir(HOST_DIR);
        properties.setConsumeShare("");
    }

    @AfterEach
    void stopServer() {
        server.stop();
        properties.setEmailHost("");
        properties.setSiteUrl("");
        properties.setConsumeDir(consumeDir);
        properties.setConsumeHostDir(consumeHostDir);
        properties.setBrowseHostDir(browseHostDir);
        properties.setConsumeShare("");
    }

    private AppUser lena() {
        users.insert("lena", encoder.encode("ein-gutes-passwort"), false, true, "lena@example.de");
        return users.findByUsername("lena").orElseThrow();
    }

    private MimeMessage welcome() {
        assertThat(notifications.accessCreated(lena(), "https://dms.example.de/zugang?token=x").sent()).isTrue();
        return server.getReceivedMessages()[0];
    }

    private static byte[] guideOf(MimeMessage message) throws Exception {
        MimeMultipart parts = (MimeMultipart) message.getContent();
        for (int index = 0; index < parts.getCount(); index++) {
            BodyPart part = parts.getBodyPart(index);
            if (UserGuide.FILENAME.equals(part.getFileName())) {
                assertThat(part.isMimeType("application/pdf")).isTrue();
                return part.getInputStream().readAllBytes();
            }
        }
        throw new AssertionError("Keine Kurzanleitung im Anhang");
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** Die Ziele aller anklickbaren Links im PDF. */
    private static List<String> linksOf(byte[] pdf) throws IOException {
        List<String> links = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (PDPage page : document.getPages()) {
                for (PDAnnotation annotation : page.getAnnotations()) {
                    if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri) {
                        links.add(uri.getURI());
                    }
                }
            }
        }
        return links;
    }

    @Test
    void theWelcomeMailNamesTheInboxAndCarriesTheGuide() throws Exception {
        MimeMessage mail = welcome();

        assertThat(plainText(mail)).contains(Notifications.INBOX_LABEL + "\n" + HOST_DIR + "\n")
                .contains(Notifications.GUIDE_NOTE);
        assertThat(mailPart(mail, "text/html")).contains("<strong>" + HOST_DIR + "</strong>")
                .contains("Ihr Benutzername: <strong>lena</strong>");
        byte[] pdf = guideOf(mail);
        // Zum Ansehen nach dem Testlauf, target ist Build-Ausgabe.
        Files.write(Files.createDirectories(Path.of("target")).resolve("DocuMan-Kurzanleitung-Beispiel.pdf"), pdf);
        String guideText = textOf(pdf);
        assertThat(guideText).contains("Willkommen bei DocuMan, lena!").contains("Ihre persönliche Kurzanleitung")
                .contains("Ihre Anmeldung").contains("https://dms.example.de/login").contains("Ihr Benutzername:")
                .contains("Ihr Eingangsordner").contains(HOST_DIR).contains("Ablegen und weiter")
                .contains("Fehlerhafte Importe").contains("Viel Freude mit Ihrem Archiv, lena!");
        // Die Anmeldung ist anklickbar.
        assertThat(linksOf(pdf)).containsExactly("https://dms.example.de/login");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo(UserGuide.TITLE + " für lena");
        }
    }

    @Test
    void theGuideUsesTheFirstNameWhenThereIsOne() throws Exception {
        AppUser lena = lena();
        users.setNames(lena.id(), "Lena", "Berger");
        assertThat(notifications.accessCreated(users.findByUsername("lena").orElseThrow(),
                "https://dms.example.de/zugang?token=x").sent()).isTrue();
        String guideText = textOf(guideOf(server.getReceivedMessages()[0]));
        assertThat(guideText).contains("Willkommen bei DocuMan, Lena!").contains("Viel Freude mit Ihrem Archiv, Lena!");
        // Anmelden muss sie sich trotzdem mit dem Benutzernamen.
        assertThat(guideText).containsPattern("Ihr Benutzername:\\s+lena");
    }

    @Test
    void withoutSiteUrlTheGuideHasNoLink() throws Exception {
        properties.setSiteUrl("");
        byte[] pdf = guide.pdf("lena", "");
        assertThat(textOf(pdf)).contains("Willkommen bei DocuMan, lena!")
                .contains("Die Adresse von DocuMan nennt Ihnen Ihr Administrator.");
        assertThat(linksOf(pdf)).isEmpty();
        assertThat(guide.loginUrl()).isEmpty();
    }

    @Test
    void aSiteUrlWithTrailingSlashesGivesOneLoginLink() {
        properties.setSiteUrl("https://dms.example.de//");
        assertThat(guide.loginUrl()).isEqualTo("https://dms.example.de/login");
    }

    @Test
    void theNetworkShareIsWhatUsersSee() throws Exception {
        properties.setConsumeShare("\\\\NAS\\scan");
        MimeMessage mail = welcome();
        assertThat(plainText(mail)).contains("\\\\NAS\\scan").doesNotContain(HOST_DIR);
        assertThat(textOf(guideOf(mail))).contains("\\\\NAS\\scan");
    }

    @Test
    void everyWatchedFolderIsListed() throws Exception {
        Files.createDirectories(backupRoot.resolve("nas").resolve("scanner"));
        properties.setBrowseHostDir("/volume1");
        jdbc.sql("INSERT INTO consume_folder (folder) VALUES ('nas/scanner')").update();
        MimeMessage mail = welcome();
        assertThat(plainText(mail)).contains(Notifications.INBOXES_LABEL + "\n" + HOST_DIR + "\n/volume1/scanner\n");
        assertThat(textOf(guideOf(mail))).contains("Ihre Eingangsordner").contains("/volume1/scanner");
    }

    @Test
    void withoutReachableFolderTheAdministratorIsNamed() throws Exception {
        properties.setConsumeDir(workspace.resolve("fehlt"));
        MimeMessage mail = welcome();
        assertThat(plainText(mail)).doesNotContain(Notifications.INBOX_LABEL).contains(Notifications.GUIDE_NOTE);
        assertThat(textOf(guideOf(mail))).contains("sagt Ihnen Ihr Administrator");
    }

    @Test
    void theRegistrationMailAlsoCarriesTheGuide() throws Exception {
        assertThat(notifications.confirmRegistration("lena", "lena@example.de", "https://dms.example.de/x").sent())
                .isTrue();
        MimeMessage mail = server.getReceivedMessages()[0];
        assertThat(textOf(guideOf(mail))).contains(HOST_DIR);
    }

    @Test
    void otherMailsCarryNoGuide() throws Exception {
        assertThat(notifications.passwordReset(lena(), "https://dms.example.de/y").sent()).isTrue();
        assertThat(GreenMailUtil.getWholeMessage(server.getReceivedMessages()[0])).doesNotContain(UserGuide.FILENAME);
    }

    @Test
    void unusualCharactersAndLongPathsDoNotBreakTheGuide() throws Exception {
        properties.setConsumeShare("\\\\NAS\\Ordner ✓ " + "sehr-langer-ordnername-".repeat(6));
        String guideText = textOf(guide.pdf("lena", "Lena"));
        // Der lange Name passt nicht mehr in die Zeile und wird umgebrochen.
        assertThat(guideText).contains("\\\\NAS\\Ordner ?").contains("sehr-langer-ordnername-sehr-langer");
    }
}
