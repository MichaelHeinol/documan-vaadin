package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;

import de.documan.domain.AppUser;
import de.documan.domain.ReportChart;
import de.documan.domain.ReportSection;
import de.documan.domain.ReportSettings;
import de.documan.repo.ReportRepository;
import de.documan.repo.UserRepository;
import de.documan.service.DailyReportService;
import jakarta.mail.BodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

class ReportSettingsTests extends IntegrationTest {

    @Autowired
    DailyReportService report;
    @Autowired
    ReportRepository reports;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    private GreenMail server;
    private AppUser chef;
    private AppUser zweit;

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
        chef = users.find(users.insert("chef", encoder.encode("x"), true, true, "chef@example.de")).orElseThrow();
        zweit = users.find(users.insert("zweit", encoder.encode("x"), true, true, "zweit@example.de")).orElseThrow();
        users.insert("ohne", encoder.encode("x"), true, true, "");
        users.insert("anna", encoder.encode("x"), false, true, "anna@example.de");
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

    /** Vor allen Testtagen angelegt, sonst hinge das Ergebnis vom heutigen Datum ab. */
    private ReportSettings config() {
        return reports.load(LocalDate.of(2026, 11, 1));
    }

    private void save(ReportSettings settings) {
        reports.save(settings);
    }

    private static ReportSettings with(ReportSettings base, List<Integer> weekdays, List<ReportSection> sections,
            ReportChart sources, ReportChart types, List<String> extra, Set<Long> excluded) {
        return new ReportSettings(base.enabled(), base.time(), weekdays, sections, sources, types, base.storageChart(),
                base.categoriesChart(), extra, excluded, base.sentOn());
    }

    private static List<ReportSection> all() {
        return List.of(ReportSection.values());
    }

    private static final List<Integer> EVERY_DAY = List.of(0, 1, 2, 3, 4, 5, 6);

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
    void firstLoadTakesTheTimeFromEnv() {
        properties.setDailyReportTime("06:30");
        ReportSettings config = reports.load();
        assertThat(config.enabled()).isTrue();
        assertThat(config.time()).isEqualTo(LocalTime.of(6, 30));
        properties.setDailyReportTime("");
        assertThat(reports.load().enabled()).isTrue();
    }

    @Test
    void emptyEnvTimeStartsSwitchedOff() {
        properties.setDailyReportTime("");
        ReportSettings config = reports.load();
        assertThat(config.enabled()).isFalse();
        assertThat(config.time()).isEqualTo(LocalTime.of(7, 0));
        assertThat(DailyReportService.scheduleText(config)).isEqualTo("ausgeschaltet");
    }

    @Test
    void sectionsCanBeLeftOut() {
        save(with(config(), EVERY_DAY, List.of(ReportSection.DOCUMENTS, ReportSection.STORAGE), ReportChart.BARS,
                ReportChart.BARS, List.of(), Set.of()));
        String text = report.build(chef, Instant.now()).text();
        assertThat(text).contains("Dokumente\n– Neu:").contains("Speicher\n");
        for (String title : List.of("Postfächer", "Verzeichnisse", "Neue Dokumente der letzten", "Sicherung\n")) {
            assertThat(text).doesNotContain(title);
        }
    }

    @Test
    void onlyOnChosenWeekdays() {
        save(with(config(), List.of(0, 2, 4), all(), ReportChart.BARS, ReportChart.BARS, List.of(), Set.of()));
        ReportSettings config = reports.load();
        assertThat(DailyReportService.scheduleText(config)).isEqualTo("Mo, Mi, Fr um 07:00");
        assertThat(report.nextSendText(config, at(9, 0, 7))).isEqualTo("Montag, 09.11.2026 um 07:00 Uhr");
        assertThat(report.sendDue(at(9, 0, 7))).isFalse();
        assertThat(report.sendDue(at(7, 0, 9))).isTrue();
        assertThat(server.getReceivedMessages()).hasSize(2);
    }

    @Test
    void excludedUsersAndExtraAddresses() throws Exception {
        save(with(config(), EVERY_DAY, all(), ReportChart.BARS, ReportChart.BARS,
                List.of("buero@example.de", "ZWEIT@example.de"), Set.of(chef.id())));
        assertThat(report.sendDue(at(7, 0, 6))).isTrue();
        assertThat(recipients()).containsExactly("zweit@example.de", "buero@example.de");
        assertThat(plainText(server.getReceivedMessages()[0])).startsWith("Hallo zweit,\n");
        assertThat(plainText(server.getReceivedMessages()[1])).startsWith("Hallo,\n");
    }

    @Test
    void percentRoundsHalfUp() {
        long[][] values = {{1, 3}, {2, 3}, {1, 8}, {1, 16}, {0, 5}, {5, 5}};
        assertThat(Arrays.stream(values).map(pair -> DailyReportService.percent(pair[0], pair[1])).toList())
                .containsExactly("33,3", "66,7", "12,5", "6,3", "0,0", "100,0");
    }

    @Test
    void sourcesAsPieWithEmbeddedImage() throws Exception {
        save(with(config(), EVERY_DAY, all(), ReportChart.PIE, ReportChart.BARS, List.of(), Set.of()));
        for (String[] item : new String[][] {{"Hoch", "upload"}, {"Mail", "mail"}, {"Mail 2", "mail"}}) {
            var document = document(item[0]).create();
            jdbc.sql("UPDATE document SET source = ? WHERE id = ?").params(item[1], document.id()).update();
        }
        DailyReportService.Report result = report.build(chef, Instant.now());
        assertThat(result.text())
                .contains("– Hochgeladen: 1 (33,3 %)\n– Eingangsordner: 0 (0,0 %)\n– E-Mail: 2 (66,7 %)");
        assertThat(result.images()).containsOnlyKeys("documan-sources");
        assertThat(result.images().get("documan-sources")).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(result.html()).contains("src=\"cid:documan-sources\"").contains("background:#2a78d6");

        report.send(chef, Instant.now());
        MimeMessage message = server.getReceivedMessages()[0];
        MimeMultipart related = (MimeMultipart) message.getContent();
        assertThat(related.getContentType()).startsWith("multipart/related");
        BodyPart image = related.getBodyPart(1);
        assertThat(image.isMimeType("image/png")).isTrue();
        assertThat(image.getHeader("Content-ID")[0]).isEqualTo("<documan-sources>");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        image.getInputStream().transferTo(bytes);
        assertThat(bytes.toByteArray()).isEqualTo(result.images().get("documan-sources"));
        assertThat(mailPart(message, "text/html")).contains("cid:documan-sources");
    }

    @Test
    void typesPieCollectsTheRest() {
        save(with(config(), EVERY_DAY, all(), ReportChart.BARS, ReportChart.PIE, List.of(), Set.of()));
        String names = "ABCDEF";
        int[] counts = {1, 6, 2, 4, 3, 5};
        for (int position = 0; position < names.length(); position++) {
            String name = String.valueOf(names.charAt(position));
            var type = documentType(name);
            for (int index = 0; index < counts[position]; index++) {
                document(name + index).type(type).create();
            }
        }
        assertThat(report.build(chef, Instant.now()).text()).contains("– B: 6 (28,6 %)")
                .contains("– Weitere Dokumenttypen: 1 (4,8 %)");
    }

    @Test
    void emptyPieStaysWithoutImage() {
        save(with(config(), EVERY_DAY, all(), ReportChart.PIE, ReportChart.BARS, List.of(), Set.of()));
        DailyReportService.Report result = report.build(chef, Instant.now());
        assertThat(result.text()).contains("– Hochgeladen: 0\n");
        assertThat(result.images()).isEmpty();
        assertThat(result.html()).doesNotContain("cid:");
    }




}
