package de.documan.service.mail;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Erkennt Spam an den Merkmalen, die Mailserver und Mailprogramme hinterlassen.
 *
 * <p>Eine eigene Bewertung des Inhalts gibt es nicht: Was niemand als Spam gekennzeichnet hat, gilt
 * nicht als Spam.
 */
public final class SpamMarks {

    private static final Pattern YES = Pattern.compile("^\\s*yes\\b", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Pattern> VERDICTS = Map.of(
            "x-spam-flag", YES,
            "x-spam-status", YES,
            "x-spam", YES,
            "x-ms-exchange-organization-scl", Pattern.compile("^\\s*[5-9]\\b"),
            "x-forefront-antispam-report", Pattern.compile("\\bSCL:[5-9]\\b", Pattern.CASE_INSENSITIVE));
    private static final Pattern SUBJECT = Pattern.compile("^\\s*[\\[{(*]+\\s*spam\\s*[\\]})*]+",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> JUNK_FLAGS = Set.of("$junk", "junk");
    private static final Set<String> NOT_JUNK_FLAGS = Set.of("$notjunk", "notjunk", "nonjunk");
    private static final Set<String> FOLDERS = Set.of("spam", "junk", "junk-e-mail", "junk e-mail", "junk email",
            "junk-mail", "junk mail", "bulk mail", "unerwünscht");

    private SpamMarks() {
    }

    /** Kopfzeilen mit kleingeschriebenen Namen. „Kein Spam“ im Mailprogramm geht jeder Kennzeichnung vor. */
    public static boolean marked(Map<String, List<String>> headers, String subject, Collection<String> flags) {
        List<String> lowerFlags = flags.stream().map(flag -> flag.toLowerCase(Locale.ROOT)).toList();
        if (lowerFlags.stream().anyMatch(NOT_JUNK_FLAGS::contains)) {
            return false;
        }
        return lowerFlags.stream().anyMatch(JUNK_FLAGS::contains) || SUBJECT.matcher(subject).find()
                || VERDICTS.entrySet().stream().anyMatch(verdict -> headers
                        .getOrDefault(verdict.getKey(), List.of()).stream()
                        .anyMatch(value -> verdict.getValue().matcher(value).find()));
    }

    /** Vergleicht den letzten Teil des Namens: Unterordner heißen „[Gmail]/Spam“ oder „INBOX.Junk“. */
    public static boolean isSpamFolder(String folder) {
        return FOLDERS.contains(folder.replaceAll(".*[/.]", "").strip().toLowerCase(Locale.ROOT));
    }
}
