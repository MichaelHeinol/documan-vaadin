package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.documan.service.mail.SpamMarks;

class SpamMarksTests {

    private static boolean header(String name, String value) {
        return SpamMarks.marked(Map.of(name, List.of(value)), "Ihre Rechnung", List.of());
    }

    private static boolean subject(String text) {
        return SpamMarks.marked(Map.of(), text, List.of());
    }

    private static boolean flags(String... flags) {
        return SpamMarks.marked(Map.of(), "Ihre Rechnung", List.of(flags));
    }

    @Test
    void headersOfSpamFilters() {
        assertThat(header("x-spam-flag", "YES")).isTrue();
        assertThat(header("x-spam-flag", "NO")).isFalse();
        assertThat(header("x-spam-status", "Yes, score=7.2 required=5.0")).isTrue();
        assertThat(header("x-spam-status", "No, score=0.1 required=5.0")).isFalse();
        assertThat(header("x-spam", "yes")).isTrue();
        assertThat(header("x-ms-exchange-organization-scl", "5")).isTrue();
        assertThat(header("x-ms-exchange-organization-scl", "1")).isFalse();
        assertThat(header("x-ms-exchange-organization-scl", "-1")).isFalse();
        assertThat(header("x-forefront-antispam-report", "CIP:1.2.3.4;SCL:9;SRV:;")).isTrue();
        assertThat(header("x-forefront-antispam-report", "CIP:1.2.3.4;SCL:1;SRV:;")).isFalse();
        assertThat(header("x-forefront-antispam-report", "CIP:1.2.3.4;SCL:-1;SRV:;")).isFalse();
    }

    @Test
    void anyOfSeveralHeadersCounts() {
        assertThat(SpamMarks.marked(Map.of("x-spam-flag", List.of("NO", "YES")), "Rechnung", List.of())).isTrue();
    }

    @Test
    void markedSubjects() {
        assertThat(subject("[SPAM] Gewinn")).isTrue();
        assertThat(subject("***SPAM*** Gewinn")).isTrue();
        assertThat(subject("*** SPAM *** Gewinn")).isTrue();
        assertThat(subject("{Spam} Gewinn")).isTrue();
        assertThat(subject("(spam) Gewinn")).isTrue();
    }

    @Test
    void ordinarySubjectsAreNoSpam() {
        assertThat(subject("Spam-Filter: Ihre Rechnung")).isFalse();
        assertThat(subject("Rechnung [SPAM]")).isFalse();
        assertThat(subject("")).isFalse();
    }

    @Test
    void flagsOfMailPrograms() {
        assertThat(flags("$Junk")).isTrue();
        assertThat(flags("Junk")).isTrue();
        assertThat(flags("$Label1")).isFalse();
    }

    @Test
    void notSpamInTheMailProgramWins() {
        assertThat(flags("$Junk", "$NotJunk")).isFalse();
        assertThat(SpamMarks.marked(Map.of("x-spam-flag", List.of("YES")), "[SPAM] Rechnung", List.of("NonJunk")))
                .isFalse();
    }

    @Test
    void spamFolders() {
        assertThat(List.of("Spam", "SPAM", "Junk", "Junk-E-Mail", "Junk E-Mail", "Junk Email", "[Gmail]/Spam",
                "INBOX.Junk", "INBOX/Spam", "Unerwünscht"))
                .allMatch(SpamMarks::isSpamFolder);
        assertThat(List.of("INBOX", "Spam/Rechnungen", "Rechnungen", "")).noneMatch(SpamMarks::isSpamFolder);
    }
}
