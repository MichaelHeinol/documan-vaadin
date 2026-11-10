package de.documan.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * Links zum Bestätigen und zum Festlegen eines Passworts per E-Mail.
 *
 * <p>Der Inhalt ist mit SECRET_KEY verschlüsselt und damit fälschungssicher. Datenbankzeilen braucht es
 * nicht. Dass ein Link nur einmal wirkt, stellt der Aufrufer am Zustand des Kontos sicher.
 */
@Component
public class AccountTokens {

    public static final String REGISTRATION = "registrierung";
    public static final String EMAIL_CHANGE = "email";
    public static final String ACCESS = "zugang";
    public static final String PASSWORD_RESET = "passwort";
    public static final Duration LIFETIME = Duration.ofHours(48);
    public static final Duration ACCESS_LIFETIME = Duration.ofHours(2);
    public static final Duration RESET_LIFETIME = Duration.ofHours(2);

    /** Inhalt eines gültigen Links. */
    public record Claim(long userId, List<String> values) {
    }

    private static final String SEPARATOR = "\n";
    private static final int HEAD_FIELDS = 3;
    private static final int STAMP_LENGTH = 16;

    private final SecretBox secrets;

    public AccountTokens(SecretBox secrets) {
        this.secrets = secrets;
    }

    public String issue(String purpose, long userId, String... values) {
        return issue(purpose, LIFETIME, userId, values);
    }

    public String issue(String purpose, Duration lifetime, long userId, String... values) {
        String expires = String.valueOf(Instant.now().plus(lifetime).getEpochSecond());
        return secrets.encrypt(String.join(SEPARATOR,
                Stream.concat(Stream.of(purpose, expires, String.valueOf(userId)), Stream.of(values)).toList()));
    }

    /** Leer, wenn der Link gefälscht, abgelaufen oder für einen anderen Zweck ausgestellt ist. */
    public Optional<Claim> read(String purpose, String token) {
        List<String> parts = List.of(secrets.decrypt(token == null ? "" : token).split(SEPARATOR, -1));
        if (parts.size() < HEAD_FIELDS || !parts.get(0).equals(purpose)
                || Instant.ofEpochSecond(Long.parseLong(parts.get(1))).isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(new Claim(Long.parseLong(parts.get(2)), parts.subList(HEAD_FIELDS, parts.size())));
    }

    /** Ändert sich mit dem Passwort. Dadurch wirkt ein Link zum Festlegen nur einmal. */
    public static String passwordStamp(String passwordHash) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(passwordHash.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, STAMP_LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public String issuePasswordLink(String purpose, long userId, String passwordHash) {
        Duration lifetime = ACCESS.equals(purpose) ? ACCESS_LIFETIME : RESET_LIFETIME;
        return issue(purpose, lifetime, userId, passwordStamp(passwordHash));
    }
}
