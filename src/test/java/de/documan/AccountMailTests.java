package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;

import de.documan.config.AdminBootstrap;
import de.documan.domain.AppUser;
import de.documan.repo.UserRepository;
import de.documan.service.Notifications;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;

/** Pflicht-E-Mail und Benachrichtigungen, der Versand läuft über einen eingebetteten SMTP-Server. */
class AccountMailTests extends IntegrationTest {

    private static final String PASSWORD = "ein-gutes-passwort";

    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    Notifications notifications;
    @Autowired
    AdminBootstrap bootstrap;

    private GreenMail server;

    @BeforeEach
    void startServer() {
        server = new GreenMail(ServerSetupTest.SMTP);
        server.start();
        properties.setEmailHost("127.0.0.1");
        properties.setEmailPort(server.getSmtp().getPort());
        properties.setEmailSecurity("none");
        properties.setEmailHostUser("");
        properties.setEmailHostPassword("");
        properties.setEmailFrom("documan@example.de");
        properties.setSiteUrl("");
    }

    @AfterEach
    void stopServer() {
        server.stop();
        properties.setEmailHost("");
        properties.setSiteUrl("");
        properties.setAdminEmail("");
        properties.setAdminPassword("");
    }

    private long insertUser(String username, String email) {
        return users.insert(username, encoder.encode(PASSWORD), false, true, email);
    }

    private MvcResult createUser(String username, String email) throws Exception {
        return submit("/benutzer/neu", CHEF, "username", username, "email", email, "active", "on");
    }

    private static String textOf(MimeMessage message) throws Exception {
        return plainText(message).strip() + "\n";
    }

    private static String withoutToken(String text) {
        return text.replaceAll("token=\\S+", "token=X");
    }



    @Test
    void htmlEscapesWhatUsersTyped() {
        String markup = Notifications.asHtml("Ihr Benutzername: <b>x</b>\n\nHallo & tschüss", "<b>x</b>");
        assertThat(markup).contains("Ihr Benutzername: <strong>&lt;b&gt;x&lt;/b&gt;</strong>")
                .contains("<p>Hallo &amp; tschüss</p>");
    }










    @Test
    void generalNotificationsUseTheSameWay() throws Exception {
        assertThat(notifications.send("lena@example.de", "Betreff mit ä", "Text mit ü\n").sent()).isTrue();
        assertThat(server.getReceivedMessages()[0].getSubject()).isEqualTo("Betreff mit ä");
    }



    private static RequestPostProcessor as(String username) {
        return user(username).roles("USER");
    }






    @Test
    void adminEmailIsTakenOnTheVeryFirstStart() {
        properties.setAdminUser("chef");
        properties.setAdminPassword(PASSWORD);
        properties.setAdminEmail("chef@example.de");
        ApplicationArguments none = new DefaultApplicationArguments();
        bootstrap.run(none);
        AppUser admin = users.findByUsername("chef").orElseThrow();
        assertThat(admin.email()).isEqualTo("chef@example.de");
        assertThat(admin.superuser()).isTrue();

        properties.setAdminEmail("spaeter@example.de");
        bootstrap.run(none);
        assertThat(users.findByUsername("chef").orElseThrow().email()).isEqualTo("chef@example.de");
    }


}
