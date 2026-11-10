package de.documan.service.mail;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import de.documan.config.DocuManProperties;
import de.documan.domain.MailAccount;
import de.documan.repo.MailRepository;
import de.documan.service.SecretBox;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anmeldung bei Microsoft per Gerätecode. Microsoft lehnt IMAP mit Passwort ab.
 *
 * <p>Das Verfahren braucht keine Rückleitung zu DocuMan und damit keine öffentliche Adresse des NAS.
 * Gespeichert wird nur der Refresh-Token, verschlüsselt an der Stelle des Passworts.
 */
@Component
public class MicrosoftLogin {

    /** Vom Benutzer einzugebender Code. {@code deviceCode} bleibt auf dem Server. */
    public record DeviceCode(String deviceCode, String userCode, String verificationUri, int interval, int expiresIn) {
    }

    /** Stand der Anmeldung: {@code refreshToken} ist gesetzt, sobald sie gelungen ist. */
    public record Poll(boolean pending, String refreshToken, String error) {
    }

    private static final String SCOPE = "offline_access https://outlook.office.com/IMAP.AccessAsUser.All";
    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int DEFAULT_INTERVAL_SECONDS = 5;
    private static final int DEFAULT_LIFETIME_SECONDS = 900;
    private static final Map<String, String> POLL_ERRORS = Map.of(
            "authorization_declined", "Die Anmeldung wurde abgelehnt.",
            "expired_token", "Der Code ist abgelaufen. Bitte starten Sie die Anmeldung neu.",
            "bad_verification_code", "Der Code ist abgelaufen. Bitte starten Sie die Anmeldung neu.");

    private final DocuManProperties properties;
    private final MailRepository accounts;
    private final SecretBox secrets;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public MicrosoftLogin(DocuManProperties properties, MailRepository accounts, SecretBox secrets) {
        this.properties = properties;
        this.accounts = accounts;
        this.secrets = secrets;
    }

    public boolean configured() {
        return !properties.getMicrosoftClientId().isBlank();
    }

    private static String encoded(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private JsonNode post(String path, Map<String, String> fields) throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>(fields);
        form.put("client_id", properties.getMicrosoftClientId().strip());
        URI address = URI.create(properties.getMicrosoftLoginUrl() + "/" + properties.getMicrosoftTenant()
                + "/oauth2/v2.0/" + path);
        HttpRequest request = HttpRequest.newBuilder(address).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encoded(form)))
                .build();
        String body = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        try {
            return JSON.readTree(body);
        } catch (JacksonException unreadable) {
            throw new IOException("Microsoft hat unverständlich geantwortet.", unreadable);
        }
    }

    private static String description(JsonNode reply) {
        String text = reply.path("error_description").asString("");
        String firstLine = text.lines().findFirst().orElse("").split(" Trace ID")[0].strip();
        return firstLine.isEmpty() ? reply.path("error").asString("Unbekannter Fehler") : firstLine;
    }

    /** Fordert einen Code an, den der Benutzer auf der Seite von Microsoft eingibt. */
    public DeviceCode start() throws IOException, InterruptedException {
        JsonNode reply = post("devicecode", Map.of("scope", SCOPE));
        if (reply.path("device_code").asString("").isEmpty()) {
            throw new IOException(description(reply));
        }
        return new DeviceCode(reply.path("device_code").asString(), reply.path("user_code").asString(),
                reply.path("verification_uri").asString(), reply.path("interval").asInt(DEFAULT_INTERVAL_SECONDS),
                reply.path("expires_in").asInt(DEFAULT_LIFETIME_SECONDS));
    }

    /** Fragt einmal nach, ob der Benutzer den Code bestätigt hat. */
    public Poll poll(String deviceCode) throws IOException, InterruptedException {
        JsonNode reply = post("token", Map.of("grant_type", DEVICE_GRANT, "device_code", deviceCode));
        String refreshToken = reply.path("refresh_token").asString("");
        if (!refreshToken.isEmpty()) {
            return new Poll(false, refreshToken, "");
        }
        String error = reply.path("error").asString("");
        if (error.equals("authorization_pending") || error.equals("slow_down")) {
            return new Poll(true, "", "");
        }
        return new Poll(false, "", POLL_ERRORS.getOrDefault(error, description(reply)));
    }

    /**
     * Tauscht den Refresh-Token gegen einen Zugang für diese Verbindung.
     *
     * <p>Microsoft stellt dabei meist einen neuen Refresh-Token aus. Er wird sofort gespeichert, sonst
     * liefe die Anmeldung irgendwann ab. Im Assistenten gibt es noch kein Postfach, der bisherige Token
     * bleibt dann gültig, bis gespeichert wird.
     */
    public String accessToken(MailAccount account) throws IOException, InterruptedException {
        if (!configured()) {
            throw new IOException("Für die Microsoft-Anmeldung fehlt die Client-ID.");
        }
        JsonNode reply = post("token", Map.of("grant_type", "refresh_token", "scope", SCOPE, "refresh_token",
                secrets.decrypt(account.passwordEncrypted())));
        String accessToken = reply.path("access_token").asString("");
        if (accessToken.isEmpty()) {
            throw new IOException(reply.path("error").asString("").equals("invalid_grant")
                    ? "Die Microsoft-Anmeldung ist abgelaufen. Bitte im Postfach neu verbinden."
                    : description(reply));
        }
        String refreshToken = reply.path("refresh_token").asString("");
        if (!refreshToken.isEmpty() && account.id() != 0) {
            accounts.setCredential(account.id(), secrets.encrypt(refreshToken));
        }
        return accessToken;
    }
}
