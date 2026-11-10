package de.documan.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import de.documan.config.DocuManProperties;
import de.documan.domain.AppUser;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

/** Verschickt Nachrichten an Benutzer. Weitere Anlässe bekommen hier ihre eigene Methode. */
@Service
public class Notifications {

    /** Ausgang eines Versands. Ein Fehler ist kein Ausnahmefall, die Oberfläche zeigt ihn an. */
    public enum Delivery {
        SENT, NOT_CONFIGURED, FAILED
    }

    public record Result(Delivery delivery, String problem) {

        public boolean sent() {
            return delivery == Delivery.SENT;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(Notifications.class);
    private static final String TIMEOUT_MILLISECONDS = String.valueOf(Duration.ofSeconds(15).toMillis());
    private static final String SECURITY_SSL = "ssl";
    private static final String SECURITY_NONE = "none";
    public static final String USERNAME_LABEL = "Ihr Benutzername:";
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<]+");

    private final DocuManProperties properties;

    public Notifications(DocuManProperties properties) {
        this.properties = properties;
    }

    private Properties transportProperties() {
        Properties settings = new Properties();
        settings.put("mail.smtp.host", properties.getEmailHost());
        settings.put("mail.smtp.port", String.valueOf(properties.getEmailPort()));
        settings.put("mail.smtp.connectiontimeout", TIMEOUT_MILLISECONDS);
        settings.put("mail.smtp.timeout", TIMEOUT_MILLISECONDS);
        settings.put("mail.smtp.writetimeout", TIMEOUT_MILLISECONDS);
        settings.put("mail.smtp.auth", String.valueOf(!properties.getEmailHostUser().isEmpty()));
        String security = properties.getEmailSecurity().strip().toLowerCase(Locale.ROOT);
        if (SECURITY_SSL.equals(security)) {
            settings.put("mail.smtp.ssl.enable", "true");
        } else if (!SECURITY_NONE.equals(security)) {
            settings.put("mail.smtp.starttls.enable", "true");
            settings.put("mail.smtp.starttls.required", "true");
        }
        if (!SECURITY_NONE.equals(security)) {
            // Sonst ginge das Passwort auch an einen vorgeschalteten Angreifer.
            settings.put("mail.smtp.ssl.checkserveridentity", "true");
        }
        return settings;
    }

    private String sender() {
        if (!properties.getEmailFrom().isEmpty()) {
            return properties.getEmailFrom();
        }
        return properties.getEmailHostUser().isEmpty() ? "documan@localhost" : properties.getEmailHostUser();
    }

    private static String anchor(MatchResult found) {
        return Matcher.quoteReplacement("<a href=\"" + found.group() + "\">" + found.group() + "</a>");
    }

    /** Dieselbe Nachricht als HTML. Links sind anklickbar, der Benutzername steht fett. */
    public static String asHtml(String text, String username) {
        List<String> paragraphs = new ArrayList<>();
        for (String block : text.strip().split("\n\n")) {
            List<String> lines = List.of(block.split("\n", -1)).stream()
                    .map(line -> LINK.matcher(HtmlUtils.htmlEscape(line, "UTF-8")).replaceAll(Notifications::anchor))
                    .toList();
            paragraphs.add("<p>" + String.join("<br>", lines) + "</p>");
        }
        String body = String.join("\n", paragraphs);
        if (username != null) {
            String name = HtmlUtils.htmlEscape(username, "UTF-8");
            String bold = USERNAME_LABEL + " <strong>" + name + "</strong>";
            body = body.replaceFirst(Pattern.quote(USERNAME_LABEL + " " + name), Matcher.quoteReplacement(bold));
        }
        return "<!doctype html><html lang=\"de\"><body style=\"font-family: sans-serif; line-height: 1.5\">\n"
                + body + "\n</body></html>";
    }

    /** Verschickt eine Nachricht als Text und HTML. Ohne EMAIL_HOST passiert nichts. */
    public Result send(String recipient, String subject, String text) {
        return send(recipient, subject, text, null);
    }

    private Result send(String recipient, String subject, String text, String username) {
        return sendHtml(recipient, subject, text, asHtml(text, username));
    }

    /** Verschickt eine Nachricht mit eigenem HTML-Teil, etwa mit Tabellen und Balken. */
    public Result sendHtml(String recipient, String subject, String text, String html) {
        return sendHtml(recipient, subject, text, html, Map.of());
    }

    /**
     * Wie sendHtml, dazu Bilder, die der HTML-Teil über cid: einbindet. Sie hängen inline an der Nachricht.
     *
     * @param images Content-ID → PNG
     */
    public Result sendHtml(String recipient, String subject, String text, String html, Map<String, byte[]> images) {
        if (properties.getEmailHost().isEmpty()) {
            return new Result(Delivery.NOT_CONFIGURED, "Der E-Mail-Versand ist nicht eingerichtet (EMAIL_HOST in der .env).");
        }
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(transportProperties()));
            message.setFrom(new InternetAddress(sender()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient, true));
            message.setSubject(subject, "UTF-8");
            MimeBodyPart plain = new MimeBodyPart();
            plain.setText(text, "UTF-8");
            MimeBodyPart markup = new MimeBodyPart();
            markup.setText(html, "UTF-8", "html");
            MimeMultipart alternatives = new MimeMultipart("alternative");
            alternatives.addBodyPart(plain);
            alternatives.addBodyPart(markup);
            if (images.isEmpty()) {
                message.setContent(alternatives);
            } else {
                MimeBodyPart body = new MimeBodyPart();
                body.setContent(alternatives);
                MimeMultipart related = new MimeMultipart("related");
                related.addBodyPart(body);
                for (Map.Entry<String, byte[]> image : images.entrySet()) {
                    MimeBodyPart part = new MimeBodyPart();
                    part.setDataHandler(new DataHandler(new ByteArrayDataSource(image.getValue(), "image/png")));
                    part.setContentID("<" + image.getKey() + ">");
                    part.setDisposition(Part.INLINE);
                    part.setFileName(image.getKey() + ".png");
                    related.addBodyPart(part);
                }
                message.setContent(related);
            }
            if (properties.getEmailHostUser().isEmpty()) {
                Transport.send(message);
            } else {
                Transport.send(message, properties.getEmailHostUser(), properties.getEmailHostPassword());
            }
            return new Result(Delivery.SENT, "");
        } catch (MessagingException error) {
            log.warn("Nachricht an {} nicht zugestellt: {}", recipient, error.getMessage());
            return new Result(Delivery.FAILED, error.getMessage());
        }
    }

    public boolean available() {
        return !properties.getEmailHost().isEmpty();
    }

    public Result confirmRegistration(String username, String email, String link) {
        return send(email, "Ihr Konto bei DocuMan aktivieren", """
                Hallo %s,

                bitte aktivieren Sie Ihr neues Konto bei DocuMan.

                %s %s

                Über diesen Link legen Sie Ihr Passwort fest, damit ist das Konto aktiv:

                %s

                Der Link gilt 48 Stunden. Haben Sie kein Konto angelegt, ignorieren Sie diese Nachricht.
                """.formatted(username, USERNAME_LABEL, username, link), username);
    }

    public Result confirmEmail(String username, String email, String link) {
        return send(email, "Ihre neue E-Mail-Adresse bei DocuMan", """
                Hallo %s,

                bitte bestätigen Sie Ihre neue E-Mail-Adresse über diesen Link:

                %s

                Der Link gilt 48 Stunden. Haben Sie die Änderung nicht veranlasst, ignorieren Sie diese Nachricht.
                """.formatted(username, link));
    }

    /** Meldet einem Benutzer seinen Zugang. Sein Passwort legt er über den Link selbst fest. */
    public Result accessCreated(AppUser user, String link) {
        return send(user.email(), "Ihr Zugang zu DocuMan", """
                Hallo %s,

                für Sie wurde ein Zugang zu DocuMan eingerichtet.

                %s %s

                Legen Sie über diesen Link Ihr Passwort fest:

                %s

                Danach melden Sie sich mit Ihrem Benutzernamen und diesem Passwort an. Der Link gilt 2 Stunden \
                und wirkt nur einmal. Ist er abgelaufen, fordern Sie auf der Anmeldeseite unter „Passwort vergessen?“ \
                einen neuen an.
                """.formatted(user.username(), USERNAME_LABEL, user.username(), link), user.username());
    }

    public Result passwordReset(AppUser user, String link) {
        return send(user.email(), "Neues Passwort für DocuMan", """
                Hallo %s,

                für Ihr Konto bei DocuMan wurde ein neues Passwort angefordert.

                %s %s

                Über diesen Link vergeben Sie das neue Passwort:

                %s

                Der Link gilt 2 Stunden und wirkt nur einmal. Haben Sie kein neues Passwort angefordert, \
                ignorieren Sie diese Nachricht. Ihr bisheriges Passwort bleibt dann gültig.
                """.formatted(user.username(), USERNAME_LABEL, user.username(), link), user.username());
    }
}
