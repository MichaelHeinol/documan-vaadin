package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;

import de.documan.domain.AppUser;
import de.documan.domain.Document;
import de.documan.domain.MailAccount;
import de.documan.repo.MailRepository;
import de.documan.repo.UserRepository;
import jakarta.mail.internet.MimeMessage;

class RegistrationTests extends IntegrationTest {

    private static final String PASSWORD = "sicheres-passwort";
    private static final Pattern TOKEN = Pattern.compile("token=(\\S+)");

    @Autowired
    UserRepository users;
    @Autowired
    MailRepository mailAccounts;
    @Autowired
    PasswordEncoder encoder;

    private GreenMail server;

    @BeforeEach
    void startServer() {
        server = new GreenMail(ServerSetupTest.SMTP);
        server.start();
        properties.setEmailHost("127.0.0.1");
        properties.setEmailPort(server.getSmtp().getPort());
        properties.setEmailSecurity("none");
        properties.setEmailFrom("documan@example.de");
        properties.setSiteUrl("https://dms.example.de");
        properties.setRegistrationEnabled(true);
    }

    @AfterEach
    void stopServer() {
        server.stop();
        properties.setEmailHost("");
        properties.setSiteUrl("");
        properties.setRegistrationEnabled(false);
    }

    private static MockHttpServletRequestBuilder form(String url, String... fields) {
        MockHttpServletRequestBuilder request = post(url).with(csrf());
        for (int index = 0; index < fields.length; index += 2) {
            request = request.param(fields[index], fields[index + 1]);
        }
        return request;
    }

    private MvcResult register(String username, String email) throws Exception {
        return mvc.perform(form("/registrieren", "username", username, "firstName", "Lena", "lastName", "Berg", "email",
                email)).andReturn();
    }

    private String lastToken() throws Exception {
        MimeMessage[] mails = server.getReceivedMessages();
        Matcher match = TOKEN.matcher(plainText(mails[mails.length - 1]));
        assertThat(match.find()).isTrue();
        return URLDecoder.decode(match.group(1), StandardCharsets.UTF_8);
    }

    private MvcResult confirm(String path, String token) throws Exception {
        return confirm(path, token, PASSWORD, PASSWORD);
    }

    private MvcResult confirm(String path, String token, String password, String repeat) throws Exception {
        return mvc.perform(form(path, "token", token, "password", password, "repeat", repeat)).andReturn();
    }

    private MvcResult login(String username) throws Exception {
        return mvc.perform(form("/login", "username", username, "password", PASSWORD)).andReturn();
    }

    private AppUser saved(String username) {
        return users.findByUsername(username).orElseThrow();
    }

    private void anna() {
        users.insert("anna", encoder.encode(PASSWORD), false, true, "anna@example.de");
    }












    // Passwort vergessen

    private static final String NEW = "noch-sichereres-passwort";

    private MvcResult forgotten(String email) throws Exception {
        return mvc.perform(form("/passwort/vergessen", "email", email)).andReturn();
    }












    @Test
    void cleanupLeavesBlockedAccountsAlone() throws Exception {
        users.insert("gesperrt", encoder.encode(PASSWORD), false, false, "g@example.de");
        register("lena", "lena@example.de");
        assertThat(users.findByUsername("gesperrt")).isPresent();
    }













}
