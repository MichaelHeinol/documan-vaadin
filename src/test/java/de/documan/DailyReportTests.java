package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;

import de.documan.domain.ActivityKind;
import de.documan.domain.AppUser;
import de.documan.domain.BackupFrequency;
import de.documan.domain.BackupSettings;
import de.documan.domain.Document;
import de.documan.domain.DocumentStatus;
import de.documan.repo.ActivityRepository;
import de.documan.repo.BackupRepository;
import de.documan.repo.UserRepository;
import de.documan.service.DailyReportService;
import jakarta.mail.internet.MimeMessage;

class DailyReportTests extends IntegrationTest {

    @Autowired
    DailyReportService report;
    @Autowired
    UserRepository users;
    @Autowired
    ActivityRepository activities;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    BackupRepository backups;

    private GreenMail server;
    private AppUser chef;

    @BeforeEach
    void startServer() {
        server = new GreenMail(ServerSetupTest.SMTP);
        server.start();
        properties.setEmailHost("127.0.0.1");
        properties.setEmailPort(server.getSmtp().getPort());
        properties.setEmailSecurity("none");
        properties.setEmailFrom("documan@example.de");
        properties.setSiteUrl("https://dms.example.de");
        properties.setDailyReportTime("07:00");
        long id = users.insert("chef", encoder.encode("x"), true, true, "chef@example.de");
        users.insert("ohne", encoder.encode("x"), true, true, "");
        users.insert("anna", encoder.encode("x"), false, true, "anna@example.de");
        chef = users.find(id).orElseThrow();
    }

    @AfterEach
    void stopServer() {
        server.stop();
        properties.setEmailHost("");
        properties.setSiteUrl("");
        properties.setDailyReportTime("07:00");
    }

    private Instant at(int hour, int minute, int day) {
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, properties.getTimeZone()).toInstant();
    }

    private Instant at(int hour) {
        return at(hour, 0, 6);
    }

    private Document document(String title, String source) {
        Document document = document(title).create();
        jdbc.sql("UPDATE document SET source = ? WHERE id = ?").params(source, document.id()).update();
        return document;
    }

    private String[] recipients() {
        return Arrays.stream(server.getReceivedMessages()).map(message -> {
            try {
                return message.getAllRecipients()[0].toString();
            } catch (Exception broken) {
                throw new IllegalStateException(broken);
            }
        }).toArray(String[]::new);
    }

    @Test
    void numbersAndSizesAreGerman() {
        assertThat(DailyReportService.number(1234567)).isEqualTo("1.234.567");
        assertThat(DailyReportService.size(512)).isEqualTo("512 Bytes");
        assertThat(DailyReportService.size(5L * 1024 * 1024 * 1024 / 2)).isEqualTo("2,5 GB");
    }

    @Test
    void reportCoversTheLast24Hours() {
        Instant now = Instant.now();
        Document old = document("Alt", "upload");
        jdbc.sql("UPDATE document SET added = ? WHERE id = ?")
                .params(Timestamp.from(now.minus(Duration.ofDays(2))), old.id()).update();
        document("Scan", "consume");
        Document mailed = document("Mail", "mail");
        jdbc.sql("UPDATE document SET inbox = false WHERE id = ?").param(mailed.id()).update();
        Document broken = document("Kaputt").status(DocumentStatus.FAILED).create();
        jdbc.sql("UPDATE document SET error = 'OCR fehlgeschlagen', inbox = true WHERE id = ?").param(broken.id())
                .update();
        activities.record("chef", ActivityKind.FILED, 3);
        activities.record("anna", ActivityKind.EDITED, 9);
        jdbc.sql("UPDATE activity SET at = ? WHERE username = 'anna'")
                .param(Timestamp.from(now.minus(Duration.ofDays(2)))).update();
        jdbc.sql("""
                INSERT INTO mail_account (name, enabled, host, port, security, username, password_encrypted, folder,
                    only_unseen, maximum_age_days, filter_from, filter_subject, attachments, after_import,
                    target_folder, last_error, imported_count, auth_method, skip_spam, poll_done, poll_total)
                VALUES ('Büro', true, 'imap.example.de', 993, 'ssl', 'u', 'x', 'INBOX', true, 30, '', '', 'pdf',
                    'seen', '', '', 0, 'password', true, 0, 0)
                """).update();

        DailyReportService.Report result = report.build(chef, now);

        assertThat(result.subject()).isEqualTo("DocuMan: Übersicht vom "
                + DateTimeFormatter.ofPattern("dd.MM.yyyy").format(now.atZone(properties.getTimeZone())));
        assertThat(result.text())
                .contains("– Neu: 3 (hochgeladen 1, Eingangsordner 1, E-Mail 1)")
                .contains("– Fehlgeschlagen: 1")
                .contains("– Insgesamt: 4")
                .contains("Fehlgeschlagene Dokumente\n– Kaputt: OCR fehlgeschlagen")
                .contains("– chef: 0 hochgeladen, 3 Eingang bearbeitet, 0 bearbeitet, 0 gelöscht")
                .doesNotContain("anna")
                .contains("– Büro, Ordner INBOX: noch nie abgerufen")
                .contains("– Letzte Sicherung: noch keine")
                .contains("Zur Übersicht: https://dms.example.de/");
    }

    @Test
    void quietDaySaysSo() {
        String text = report.build(chef, Instant.now()).text();
        assertThat(text).contains("Aktivitäten der Benutzer\n– keine").contains("Postfächer\n– keine eingerichtet")
                .doesNotContain("Fehlgeschlagene Dokumente");
    }

    @Test
    void lastBackupIsReported() {
        jdbc.sql("INSERT INTO backup_run (kind, status, size, finished_at) VALUES ('backup', 'done', ?, ?)")
                .params(3L * 1024 * 1024, Timestamp.from(at(3))).update();
        assertThat(report.build(chef, at(7)).text())
                .contains("– Letzte Sicherung: 06.11.2026 03:00, erfolgreich, 3,0 MB");
    }

    @Test
    void goesOnceADayToEveryActiveAdminWithAddress() {
        assertThat(report.sendDue(at(6, 59, 6))).isFalse();
        assertThat(report.sendDue(at(7))).isTrue();
        assertThat(report.sendDue(at(9))).isFalse();
        assertThat(recipients()).containsExactly("chef@example.de");
        assertThat(report.sendDue(at(7, 0, 7))).isTrue();
        assertThat(server.getReceivedMessages()).hasSize(2);
    }

    @Test
    void aMissedTimeIsCaughtUp() {
        jdbc.sql("INSERT INTO daily_report (id, sent_on) VALUES (1, ?)").param(LocalDate.of(2026, 11, 4)).update();
        assertThat(report.sendDue(at(15))).isTrue();
        assertThat(server.getReceivedMessages()).hasSize(1);
    }

    @Test
    void failedDeliveryIsNotRepeatedEveryMinute() {
        server.stop();
        assertThat(report.sendDue(at(7))).isTrue();
        assertThat(report.sendDue(at(7, 1, 6))).isFalse();
    }

    @Test
    void emptyTimeSwitchesItOff() {
        properties.setDailyReportTime("");
        assertThat(report.reportTime()).isNull();
        assertThat(report.sendDue(at(12))).isFalse();
        assertThat(server.getReceivedMessages()).isEmpty();
    }

    @Test
    void invalidTimeSwitchesItOff() {
        properties.setDailyReportTime("25:99");
        assertThat(report.reportTime()).isNull();
    }

    @Test
    void withoutMailServerNothingHappens() {
        properties.setEmailHost("");
        assertThat(report.sendDue(at(12))).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM daily_report").query(Long.class).single()).isZero();
    }

    private void addedOn(Document document, Instant moment) {
        jdbc.sql("UPDATE document SET added = ? WHERE id = ?").params(Timestamp.from(moment), document.id()).update();
    }

    @Test
    void newDocumentsPerDayForTwoWeeks() {
        int[][] days = {{20, 2}, {14, 1}, {6, 5}};
        for (int[] day : days) {
            for (int index = 0; index < day[1]; index++) {
                addedOn(document("Tag " + day[0] + " " + index).create(), at(8, 0, day[0]));
            }
        }
        String text = report.build(chef, at(9, 0, 20)).text();
        String section = text.split("Neue Dokumente der letzten 14 Tage\n")[1].split("\n\n")[0];
        List<String> lines = List.of(section.split("\n"));
        assertThat(lines).hasSize(14).contains("– 14.11.: 1");
        assertThat(lines.getFirst()).isEqualTo("– 07.11.: 0");
        assertThat(lines.getLast()).isEqualTo("– 20.11.: 2");
    }

    @Test
    void sourcesOfTheLast30Days() {
        document("Hoch", "upload");
        document("Mail", "mail");
        document("Mail 2", "mail");
        assertThat(report.build(chef, Instant.now()).text()).contains(
                "Herkunft der Dokumente, letzte 30 Tage\n– Hochgeladen: 1\n– Eingangsordner: 0\n– E-Mail: 2");
    }

    @Test
    void largestDocumentTypes() {
        String names = "ABCDEF";
        int[] counts = {1, 6, 2, 4, 3, 5};
        for (int position = 0; position < names.length(); position++) {
            String name = String.valueOf(names.charAt(position));
            var type = documentType(name);
            for (int index = 0; index < counts[position]; index++) {
                document(name + index).type(type).create();
            }
        }
        assertThat(report.build(chef, Instant.now()).text())
                .contains("Die 5 größten Dokumenttypen\n– B: 6\n– F: 5\n– D: 4\n– E: 3\n– C: 2\n\n");
    }

    @Test
    void foldersNameInboxesAndBackupTarget() throws Exception {
        Path before = properties.getConsumeDir();
        Path consume = Files.createDirectories(workspace.resolve("eingang"));
        Files.write(consume.resolve("wartet.pdf"), new byte[] {'x'});
        properties.setConsumeDir(consume);
        properties.setConsumeHostDir("/volume1/scan");
        properties.setBrowseHostDir("/volume1");
        backups.saveSettings(new BackupSettings(true, BackupFrequency.DAILY, 6, LocalTime.of(3, 0),
                "nas/backup/documan", 7, null));
        try {
            assertThat(report.build(chef, Instant.now()).text())
                    .contains("– Eingangsordner /volume1/scan: erreichbar, 1 wartend, 0 abgelehnt")
                    .contains("– Sicherungsziel /volume1/backup/documan: täglich um 03:00, die letzten 7 bleiben");
        } finally {
            properties.setConsumeDir(before);
            properties.setConsumeHostDir("");
            properties.setBrowseHostDir("");
        }
    }

    @Test
    void weeklyBackupWithoutScheduleText() {
        assertThat(report.build(chef, Instant.now()).text())
                .contains("Sicherungsziel Sicherungsordner: keine zeitgesteuerte Sicherung");
        backups.saveSettings(new BackupSettings(true, BackupFrequency.WEEKLY, 6, LocalTime.of(2, 30), "woche", 0,
                null));
        assertThat(report.build(chef, Instant.now()).text())
                .contains("Sicherungsziel Sicherungsordner/woche: wöchentlich am Sonntag um 02:30, alle bleiben");
    }

    @Test
    void barsGrowWithTheirValue() {
        var section = new DailyReportService.Section("Test", List.of(), List.of(
                new DailyReportService.Bar("null", 0, "0"), new DailyReportService.Bar("halb", 5, "5"),
                new DailyReportService.Bar("voll", 10, "10")), 0);
        String markup = DailyReportService.barsHtml(section);
        assertThat(markup.split("width=\"50%\"", -1)).hasSize(2);
        assertThat(markup.split("width=\"100%\" style=\"background:#2f7d6d", -1)).hasSize(2);
        assertThat(markup.split("background:#2f7d6d", -1)).hasSize(3);
    }

    @Test
    void htmlPartShowsChartsAndEscapes() throws Exception {
        Document broken = document("<b>Kaputt</b>").status(DocumentStatus.FAILED).create();
        jdbc.sql("UPDATE document SET error = 'x' WHERE id = ?").param(broken.id()).update();
        report.send(chef, Instant.now());
        String markup = mailPart(server.getReceivedMessages()[0], "text/html");
        assertThat(markup)
                .contains(">Neue Dokumente der letzten 14 Tage</h2>")
                .contains(">Verzeichnisse</h2>")
                .contains("background:#2f7d6d")
                .contains("&lt;b&gt;Kaputt&lt;/b&gt;")
                .doesNotContain("<b>Kaputt")
                .contains("<a href=\"https://dms.example.de/\">");
    }

}
