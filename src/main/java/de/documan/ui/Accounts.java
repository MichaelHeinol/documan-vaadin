package de.documan.ui;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.vaadin.flow.server.VaadinServletRequest;

import de.documan.config.DocuManProperties;

/** Regeln für Konten, Passwörter und die Links in den Mails zu Konten. */
@Component
public class Accounts {

    public static final Pattern USERNAME = Pattern.compile("[\\w.@+-]{1,150}", Pattern.UNICODE_CHARACTER_CLASS);
    public static final String INVALID_LINK = "Dieser Link ist ungültig oder abgelaufen.";

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@([^@\\s.]+\\.)+[^@\\s.]+");
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final Set<String> COMMON_PASSWORDS = Set.of("password", "passwort", "12345678", "123456789",
            "1234567890", "qwertz123", "qwerty123", "qwertzuiop", "qwertyuiop", "password1", "passwort1", "hallo123",
            "admin123", "administrator", "letmein123", "iloveyou", "sommer2024", "sommer2025", "sommer2026",
            "willkommen", "geheim123", "abcd1234", "11111111", "00000000");

    private final SessionRegistry sessions;
    private final DocuManProperties properties;
    private final PasswordEncoder encoder;

    public Accounts(SessionRegistry sessions, DocuManProperties properties, PasswordEncoder encoder) {
        this.sessions = sessions;
        this.properties = properties;
        this.encoder = encoder;
    }

    /** Sperren, Rechte und Passwörter gelten sofort, nicht erst beim nächsten Anmelden. */
    public void endSessions(String username, String exceptSessionId) {
        sessions.getAllPrincipals().stream()
                .filter(principal -> principal instanceof UserDetails user && user.getUsername().equals(username))
                .flatMap(principal -> sessions.getAllSessions(principal, false).stream())
                .filter(session -> !session.getSessionId().equals(exceptSessionId))
                .forEach(SessionInformation::expireNow);
    }

    /** Die eigene Sitzung, die beim Ändern des eigenen Passworts offen bleiben darf. */
    public static String currentSessionId() {
        VaadinServletRequest request = VaadinServletRequest.getCurrent();
        return request == null || request.getHttpServletRequest().getSession(false) == null ? null
                : request.getHttpServletRequest().getSession(false).getId();
    }

    public static String passwordProblem(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return "Das Passwort muss mindestens " + MIN_PASSWORD_LENGTH + " Zeichen lang sein.";
        }
        if (COMMON_PASSWORDS.contains(password.toLowerCase())) {
            return "Dieses Passwort ist zu verbreitet.";
        }
        return null;
    }

    public static String emailProblem(String email) {
        String cleaned = email == null ? "" : email.strip();
        if (cleaned.isEmpty()) {
            return "Bitte eine E-Mail-Adresse angeben.";
        }
        if (cleaned.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(cleaned).matches()) {
            return "Bitte eine gültige E-Mail-Adresse angeben.";
        }
        return null;
    }

    /** SITE_URL, sonst die Adresse, unter der die Seite gerade aufgerufen wurde. */
    public String siteUrl() {
        String configured = properties.getSiteUrl().strip();
        VaadinServletRequest request = VaadinServletRequest.getCurrent();
        String base = !configured.isEmpty() || request == null ? configured
                : ServletUriComponentsBuilder.fromContextPath(request.getHttpServletRequest()).build().toUriString();
        return base.replaceAll("/+$", "") + "/";
    }

    public String link(String path, String token) {
        return siteUrl() + path + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    /** Ein Passwort, das niemand kennt. Das eigene legt der Benutzer über den Link fest. */
    public String unknownPassword() {
        return encoder.encode(UUID.randomUUID().toString());
    }
}
