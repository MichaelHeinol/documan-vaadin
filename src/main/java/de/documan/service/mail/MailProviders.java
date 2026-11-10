package de.documan.service.mail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import org.springframework.stereotype.Component;

import de.documan.domain.MailAuth;
import de.documan.domain.MailSecurity;

/** Vorlagen der Hoster für den Postfach-Assistenten und ihre Erkennung an der Adresse. */
@Component
public class MailProviders {

    /** Eine Vorlage. {@code loginHint} erscheint, wenn der Server die Anmeldung ablehnt. */
    public record Provider(String key, String label, String host, int port, MailSecurity security, MailAuth auth,
            String hint, String loginHint, String linkLabel, String linkUrl) {
    }

    /** Liefert die Mailserver einer Domain, den wichtigsten zuerst. Tests ersetzen die DNS-Abfrage. */
    public interface MxLookup {
        List<String> hosts(String domain) throws NamingException;
    }

    public static final String OTHER = "other";

    private static final Provider OTHER_PROVIDER = new Provider(OTHER, "Anderer Anbieter", "", 993, MailSecurity.SSL,
            MailAuth.PASSWORD, "Die Angaben finden Sie in der Hilfe Ihres Anbieters unter IMAP.",
            "Prüfen Sie Benutzername und Passwort.", null, null);
    private static final List<Provider> ALL = List.of(
            new Provider("ionos", "IONOS", "imap.ionos.de", 993, MailSecurity.SSL, MailAuth.PASSWORD,
                    "Benutzername ist Ihre E-Mail-Adresse. Verwenden Sie das Passwort des Postfachs, nicht das Ihres "
                            + "IONOS-Kundenkontos.",
                    "Prüfen Sie das Postfach-Passwort.", null, null),
            new Provider("strato", "STRATO", "imap.strato.de", 993, MailSecurity.SSL, MailAuth.PASSWORD,
                    "Benutzername ist Ihre E-Mail-Adresse. Verwenden Sie das Passwort, das Sie beim Einrichten des "
                            + "Postfachs vergeben haben.",
                    "Prüfen Sie das Postfach-Passwort.", null, null),
            new Provider("webde", "WEB.DE", "imap.web.de", 993, MailSecurity.SSL, MailAuth.PASSWORD,
                    "Schalten Sie zuerst bei WEB.DE unter E-Mail-Einstellungen, POP3/IMAP-Abruf den Zugriff ein. Mit "
                            + "Zwei-Faktor-Schutz brauchen Sie ein anwendungsspezifisches Passwort.",
                    "Ist der POP3/IMAP-Abruf bei WEB.DE eingeschaltet?", null, null),
            new Provider("gmail", "Gmail", "imap.gmail.com", 993, MailSecurity.SSL, MailAuth.PASSWORD,
                    "Google akzeptiert hier nicht Ihr normales Passwort. Schalten Sie die Bestätigung in zwei Schritten "
                            + "ein und erstellen Sie ein App-Passwort.",
                    "Gmail braucht ein App-Passwort, nicht Ihr normales Passwort.", "App-Passwort erstellen",
                    "https://myaccount.google.com/apppasswords"),
            new Provider("microsoft", "Microsoft", "outlook.office365.com", 993, MailSecurity.SSL, MailAuth.MICROSOFT,
                    "Microsoft erlaubt keine Anmeldung mit Passwort mehr. Sie melden sich stattdessen im Browser bei "
                            + "Microsoft an, DocuMan speichert nur den erlaubten Zugriff.",
                    "Bitte melden Sie sich erneut bei Microsoft an.", null, null),
            OTHER_PROVIDER);

    private static final Map<String, String> DOMAINS = Map.ofEntries(
            Map.entry("gmail.com", "gmail"), Map.entry("googlemail.com", "gmail"), Map.entry("web.de", "webde"),
            Map.entry("outlook.com", "microsoft"), Map.entry("outlook.de", "microsoft"),
            Map.entry("hotmail.com", "microsoft"), Map.entry("hotmail.de", "microsoft"),
            Map.entry("live.com", "microsoft"), Map.entry("live.de", "microsoft"), Map.entry("msn.com", "microsoft"));
    private static final Map<String, String> MAIL_SERVERS = Map.of(
            ".ionos.de", "ionos", ".ionos.com", "ionos", ".kundenserver.de", "ionos", ".1and1.com", "ionos",
            ".rzone.de", "strato", ".strato.de", "strato", ".google.com", "gmail", ".googlemail.com", "gmail",
            ".web.de", "webde", ".protection.outlook.com", "microsoft");
    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9.-]{1,253}");
    // Zwei Versuche von 0,6 und 1,2 Sekunden: Die Abfrage läuft während der Eingabe.
    private static final String DNS_TIMEOUT_MILLISECONDS = "600";
    private static final String DNS_RETRIES = "2";

    private MxLookup mx = MailProviders::lookUp;

    /** Nur für Tests. */
    public void useMxLookup(MxLookup replacement) {
        this.mx = replacement;
    }

    public List<Provider> all() {
        return ALL;
    }

    public Provider other() {
        return OTHER_PROVIDER;
    }

    public Optional<Provider> byKey(String key) {
        return ALL.stream().filter(provider -> provider.key().equals(key)).findFirst();
    }

    /** Zu einem Server gehört höchstens eine Vorlage. Unbekannte Server sind „Anderer Anbieter“. */
    public Provider byHost(String host) {
        return ALL.stream().filter(provider -> provider.host().equalsIgnoreCase(host) && !host.isEmpty()).findFirst()
                .orElse(OTHER_PROVIDER);
    }

    /** Erkennt den Hoster an der Domain, eigene Domains an ihrem Mailserver. */
    public Optional<Provider> detect(String address) {
        String text = address == null ? "" : address.strip().toLowerCase(Locale.ROOT);
        String domain = text.substring(text.lastIndexOf('@') + 1);
        if (!text.contains("@") || !DOMAIN.matcher(domain).matches()) {
            return Optional.empty();
        }
        String known = DOMAINS.get(domain);
        return known != null ? byKey(known) : detectByMailServer(domain);
    }

    private Optional<Provider> detectByMailServer(String domain) {
        try {
            String host = mx.hosts(domain).stream().findFirst().orElse("").toLowerCase(Locale.ROOT);
            return MAIL_SERVERS.entrySet().stream().filter(entry -> host.endsWith(entry.getKey())).findFirst()
                    .flatMap(entry -> byKey(entry.getValue()));
        } catch (NamingException unknown) {
            return Optional.empty();
        }
    }

    private record MailServer(int priority, String host) {
    }

    private static List<String> lookUp(String domain) throws NamingException {
        Hashtable<String, String> settings = new Hashtable<>();
        settings.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        settings.put("com.sun.jndi.dns.timeout.initial", DNS_TIMEOUT_MILLISECONDS);
        settings.put("com.sun.jndi.dns.timeout.retries", DNS_RETRIES);
        DirContext dns = new InitialDirContext(settings);
        try {
            Attribute records = dns.getAttributes(domain, new String[] {"MX"}).get("MX");
            if (records == null) {
                return List.of();
            }
            List<MailServer> servers = new ArrayList<>();
            NamingEnumeration<?> values = records.getAll();
            while (values.hasMore()) {
                String[] parts = values.next().toString().strip().split("\\s+");
                if (parts.length == 2 && parts[0].matches("\\d{1,5}")) {
                    servers.add(new MailServer(Integer.parseInt(parts[0]), parts[1].replaceAll("\\.$", "")));
                }
            }
            return servers.stream().sorted(Comparator.comparingInt(MailServer::priority)).map(MailServer::host).toList();
        } finally {
            dns.close();
        }
    }
}
