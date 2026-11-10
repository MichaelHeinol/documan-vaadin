package de.documan.repo;

import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import de.documan.config.DocuManProperties;
import de.documan.domain.Choice;
import de.documan.domain.ReportChart;
import de.documan.domain.ReportSection;
import de.documan.domain.ReportSettings;

/** Die Einstellungen der täglichen Übersicht. Es gibt höchstens einen Datensatz. */
@Repository
public class ReportRepository {

    private static final Pattern TIME = Pattern.compile("([01]?\\d|2[0-3]):([0-5]\\d)");

    private final JdbcClient jdbc;
    private final DocuManProperties properties;

    public ReportRepository(JdbcClient jdbc, DocuManProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    private static List<String> split(String value, String separator) {
        return Arrays.stream(value.split(separator)).map(String::strip).filter(part -> !part.isEmpty()).toList();
    }

    public ReportSettings load() {
        return load(LocalDate.now(properties.getTimeZone()));
    }

    /** Der Datensatz. Fehlt die Uhrzeit, kommt sie einmalig aus DAILY_REPORT_TIME. */
    @Transactional
    public ReportSettings load(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        jdbc.sql("INSERT INTO daily_report (id, sent_on) VALUES (1, ?) ON CONFLICT (id) DO NOTHING")
                .param(yesterday).update();
        boolean unset = jdbc.sql("SELECT time IS NULL FROM daily_report WHERE id = 1").query(Boolean.class).single();
        if (unset) {
            Matcher match = TIME.matcher(properties.getDailyReportTime().strip());
            boolean valid = match.matches();
            LocalTime time = valid ? LocalTime.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)))
                    : ReportSettings.DEFAULT_TIME;
            jdbc.sql("UPDATE daily_report SET enabled = ?, time = ? WHERE id = 1").params(valid, Time.valueOf(time))
                    .update();
        }
        Set<Long> excluded = new HashSet<>(jdbc.sql("SELECT user_id FROM daily_report_excluded").query(Long.class).list());
        return jdbc.sql("SELECT * FROM daily_report WHERE id = 1")
                .query((row, index) -> new ReportSettings(
                        row.getBoolean("enabled"),
                        row.getObject("time", LocalTime.class),
                        split(row.getString("weekdays"), ",").stream().map(Integer::valueOf).toList(),
                        split(row.getString("sections"), ",").stream()
                                .flatMap(key -> Choice.find(ReportSection.class, key).stream())
                                .sorted().toList(),
                        Choice.find(ReportChart.class, row.getString("sources_chart")).orElse(ReportChart.BARS),
                        Choice.find(ReportChart.class, row.getString("types_chart")).orElse(ReportChart.BARS),
                        Choice.find(ReportChart.class, row.getString("storage_chart")).orElse(ReportChart.BARS),
                        Choice.find(ReportChart.class, row.getString("categories_chart")).orElse(ReportChart.BARS),
                        split(row.getString("extra_recipients"), "\n"),
                        excluded,
                        row.getObject("sent_on", LocalDate.class)))
                .single();
    }

    /** Speichert alles außer dem Versanddatum. */
    @Transactional
    public void save(ReportSettings settings) {
        load();
        jdbc.sql("""
                UPDATE daily_report SET enabled = ?, time = ?, weekdays = ?, sections = ?, sources_chart = ?,
                    types_chart = ?, storage_chart = ?, categories_chart = ?, extra_recipients = ?
                WHERE id = 1
                """)
                .params(settings.enabled(), Time.valueOf(settings.time()),
                        settings.weekdays().stream().sorted().map(String::valueOf).collect(Collectors.joining(",")),
                        settings.sections().stream().sorted().map(ReportSection::value).collect(Collectors.joining(",")),
                        settings.sourcesChart().value(), settings.typesChart().value(), settings.storageChart().value(),
                        settings.categoriesChart().value(), String.join("\n", settings.extraRecipients()))
                .update();
        jdbc.sql("DELETE FROM daily_report_excluded").update();
        for (long id : settings.excluded()) {
            jdbc.sql("INSERT INTO daily_report_excluded (user_id) VALUES (?) ON CONFLICT DO NOTHING").param(id).update();
        }
    }
}
