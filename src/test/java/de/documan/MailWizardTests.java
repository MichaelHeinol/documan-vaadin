package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.naming.NameNotFoundException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.sun.net.httpserver.HttpServer;

import de.documan.domain.MailAccount;
import de.documan.domain.MailAuth;
import de.documan.domain.MailRule;
import de.documan.domain.MailRuleField;
import de.documan.domain.MailSecurity;
import de.documan.repo.MailRepository;
import de.documan.service.SecretBox;
import de.documan.service.mail.ImapConnector;
import de.documan.service.mail.MailImportService;
import de.documan.service.mail.MailProviders;
import de.documan.service.mail.MailProviders.Provider;
import de.documan.service.mail.MailboxClient;
import de.documan.service.mail.MicrosoftLogin;
import tools.jackson.databind.json.JsonMapper;

/** Assistent, Vorlagen der Hoster und Anmeldung bei Microsoft. */
class MailWizardTests extends IntegrationTest {

    private static final String ADDRESS = "post@example.de";

    @Autowired
    MailProviders providers;
    @Autowired
    MicrosoftLogin microsoft;
    @Autowired
    MailRepository accounts;
    @Autowired
    SecretBox secrets;
    @Autowired
    MailImportService imports;
    @Autowired
    ImapConnector imap;

    /** Postfächer verwaltet der Superuser. */
    @Override
    protected MvcResult page(String url) throws Exception {
        return page(url, CHEF);
    }

    @Override
    protected MvcResult submit(String url, String... fields) throws Exception {
        return submit(url, CHEF, fields);
    }

    private HttpServer tokenServer;
    private final Deque<String> tokenReplies = new ArrayDeque<>();
    private final List<String> tokenRequests = new ArrayList<>();

    @BeforeEach
    void noRealDns() {
        providers.useMxLookup(domain -> {
            throw new NameNotFoundException(domain);
        });
    }

    @AfterEach
    void restore() {
        properties.setMicrosoftClientId("");
        properties.setMicrosoftLoginUrl("https://login.microsoftonline.com");
        imports.useConnector(imap::connect);
        if (tokenServer != null) {
            tokenServer.stop(0);
        }
    }

    /** Antwortet wie der Token-Server von Microsoft mit den vorbereiteten Texten. */
    private void microsoftAnswers(String... replies) throws IOException {
        tokenReplies.addAll(List.of(replies));
        tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokenServer.createContext("/", exchange -> {
            tokenRequests.add(exchange.getRequestURI().getPath() + "?"
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = tokenReplies.remove().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        tokenServer.start();
        properties.setMicrosoftClientId("client-1");
        properties.setMicrosoftLoginUrl("http://127.0.0.1:" + tokenServer.getAddress().getPort());
    }

    private static Map<String, Object> json(MvcResult result) throws Exception {
        return JsonMapper.shared().readerForMapOf(Object.class).readValue(body(result));
    }

    private Map<String, Object> postJson(String url, MockHttpSession session, String... fields) throws Exception {
        MockHttpServletRequestBuilder request = post(url).with(CHEF).with(csrf()).session(session);
        for (int index = 0; index < fields.length; index += 2) {
            request = request.param(fields[index], fields[index + 1]);
        }
        return json(mvc.perform(request).andReturn());
    }

    private static String[] connection(String... extra) {
        List<String> fields = new ArrayList<>(List.of("host", "imap.example.de", "port", "993", "security", "ssl",
                "username", ADDRESS));
        fields.addAll(List.of(extra));
        return fields.toArray(String[]::new);
    }

    private String[] complete(String... extra) {
        List<String> fields = new ArrayList<>(List.of(connection()));
        fields.addAll(List.of("name", "Büro", "folder", "INBOX", "maximumAgeDays", "30", "attachments", "pdf",
                "afterImport", "seen", "enabled", "on"));
        fields.addAll(List.of(extra));
        return fields.toArray(String[]::new);
    }

    private MailAccount savedAccount(MailAuth auth) {
        MailAccount blank = MailAccount.blank();
        long id = accounts.insert(new MailAccount(0, "Büro", true, "imap.example.de", 993, MailSecurity.SSL, ADDRESS,
                secrets.encrypt("geheim"), "INBOX", true, 30, "", "", blank.attachments(), blank.afterImport(), "",
                null, null, List.of(), null, "", 0, auth));
        return accounts.find(id).orElseThrow();
    }

    private static final class FakeMailbox implements MailboxClient {
        @Override
        public List<Header> headers(Criteria criteria, int limit) {
            return List.of();
        }

        @Override
        public java.util.Optional<MailMessage> fetch(long uid) {
            return java.util.Optional.empty();
        }

        @Override
        public void markSeen(long uid) {
        }

        @Override
        public void move(long uid, String folder) {
        }

        @Override
        public void delete(long uid) {
        }

        @Override
        public List<String> folders() {
            return List.of("INBOX", "Archiv");
        }

        @Override
        public void close() {
        }
    }

    // Vorlagen und Erkennung

    @Test
    void templatesCarryTheHosterSettings() {
        Map<String, String> servers = Map.of("ionos", "imap.ionos.de", "strato", "imap.strato.de", "webde",
                "imap.web.de", "gmail", "imap.gmail.com", "microsoft", "outlook.office365.com");
        servers.forEach((key, host) -> {
            Provider provider = providers.byKey(key).orElseThrow();
            assertThat(provider.host()).isEqualTo(host);
            assertThat(provider.port()).isEqualTo(993);
            assertThat(provider.security()).isEqualTo(MailSecurity.SSL);
        });
        assertThat(providers.byKey("microsoft").orElseThrow().auth()).isEqualTo(MailAuth.MICROSOFT);
        assertThat(providers.byKey("gmail").orElseThrow().auth()).isEqualTo(MailAuth.PASSWORD);
    }

    @Test
    void knownDomainsNeedNoDns() {
        providers.useMxLookup(domain -> {
            throw new AssertionError("keine DNS-Abfrage erwartet");
        });
        assertThat(providers.detect("a@gmail.com").orElseThrow().key()).isEqualTo("gmail");
        assertThat(providers.detect(" A@GoogleMail.com ").orElseThrow().key()).isEqualTo("gmail");
        assertThat(providers.detect("a@web.de").orElseThrow().key()).isEqualTo("webde");
        assertThat(providers.detect("a@hotmail.de").orElseThrow().key()).isEqualTo("microsoft");
        assertThat(providers.detect("a@outlook.com").orElseThrow().key()).isEqualTo("microsoft");
    }

    @Test
    void ownDomainsAreRecognizedByTheirMailServer() {
        Map<String, String> servers = Map.of("mx00.ionos.de", "ionos", "mx01.kundenserver.de", "ionos",
                "smtpin.rzone.de", "strato", "aspmx.l.google.com", "gmail",
                "firma-de.mail.protection.outlook.com", "microsoft");
        servers.forEach((server, key) -> {
            providers.useMxLookup(domain -> List.of(server));
            assertThat(providers.detect("a@firma.de").orElseThrow().key()).as(server).isEqualTo(key);
        });
    }

    @Test
    void theMostImportantMailServerDecides() {
        providers.useMxLookup(domain -> List.of("mail.eigener-server.de", "mx00.ionos.de"));
        assertThat(providers.detect("a@firma.de")).isEmpty();
    }

    @Test
    void unknownOrBrokenAddressesAreNotRecognized() {
        assertThat(providers.detect("a@firma.de")).isEmpty();
        assertThat(providers.detect("keine-adresse")).isEmpty();
        assertThat(providers.detect("a@firma de")).isEmpty();
        assertThat(providers.detect(null)).isEmpty();
    }


    // Assistent







    // Verbindung testen





    // Microsoft



    private MockHttpServletRequestBuilder form(String... fields) {
        MockHttpServletRequestBuilder request = post("/postfaecher/neu").with(CHEF).with(csrf());
        for (int index = 0; index < fields.length; index += 2) {
            request = request.param(fields[index], fields[index + 1]);
        }
        return request;
    }











    @Test
    void accessTokensReplaceTheRefreshToken() throws Exception {
        microsoftAnswers("{\"access_token\":\"zugang\",\"refresh_token\":\"neu\"}");
        MailAccount account = savedAccount(MailAuth.MICROSOFT);

        assertThat(microsoft.accessToken(account)).isEqualTo("zugang");

        assertThat(tokenRequests.getFirst()).contains("grant_type=refresh_token").contains("refresh_token=geheim");
        assertThat(secrets.decrypt(accounts.find(account.id()).orElseThrow().passwordEncrypted())).isEqualTo("neu");
    }

    @Test
    void expiredRefreshTokenAsksToReconnect() throws Exception {
        microsoftAnswers("{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS70008: abgelaufen\"}");
        MailAccount account = savedAccount(MailAuth.MICROSOFT);
        assertThatThrownBy(() -> microsoft.accessToken(account)).hasMessageContaining("neu verbinden");
    }

    @Test
    void imapLoginUsesXoauth2WithTheAccessToken() throws Exception {
        microsoftAnswers("{\"access_token\":\"zugang\"}");
        AtomicReference<String> login = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread imapServer = Thread.ofVirtual().start(() -> recordLogin(server, login));
            MailAccount blank = MailAccount.blank();
            long id = accounts.insert(new MailAccount(0, "Microsoft", true, "127.0.0.1", server.getLocalPort(),
                    MailSecurity.NONE, ADDRESS, secrets.encrypt("erneuern"), "INBOX", true, 30, "", "",
                    blank.attachments(), blank.afterImport(), "", null, null, List.of(), null, "", 0,
                    MailAuth.MICROSOFT));

            // Der Testserver endet nach der Anmeldung, das Öffnen des Ordners scheitert also.
            assertThatThrownBy(() -> imap.connect(accounts.find(id).orElseThrow()).close())
                    .isInstanceOf(Exception.class);
            imapServer.join(5000);
        }
        assertThat(login.get()).isEqualTo("user=" + ADDRESS + "\u0001auth=Bearer zugang\u0001\u0001");
    }

    /** Spielt einen IMAP-Server, der nur XOAUTH2 anbietet, und merkt sich die Anmeldedaten. */
    private static void recordLogin(ServerSocket server, AtomicReference<String> login) {
        try (Socket client = server.accept();
                var input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                var output = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
            output.print("* OK bereit\r\n");
            output.flush();
            for (String line = input.readLine(); line != null; line = input.readLine()) {
                String[] parts = line.split(" ");
                if (parts[1].equals("CAPABILITY")) {
                    output.print("* CAPABILITY IMAP4rev1 AUTH=XOAUTH2 LOGINDISABLED\r\n" + parts[0] + " OK\r\n");
                    output.flush();
                } else if (parts[1].equals("AUTHENTICATE")) {
                    String payload = parts.length > 3 ? parts[3] : null;
                    if (payload == null) {
                        output.print("+ \r\n");
                        output.flush();
                        payload = input.readLine();
                    }
                    login.set(new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8));
                    output.print(parts[0] + " NO fertig\r\n");
                    output.flush();
                    return;
                } else {
                    output.print(parts[0] + " BAD\r\n");
                    output.flush();
                }
            }
        } catch (IOException ended) {
            // Der Client hat die Verbindung beendet.
        }
    }
}
