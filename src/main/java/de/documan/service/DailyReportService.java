package de.documan.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

import de.documan.config.DocuManProperties;
import de.documan.domain.AppUser;
import de.documan.domain.BackupFrequency;
import de.documan.domain.BackupSettings;
import de.documan.domain.DocumentSource;
import de.documan.domain.ReportChart;
import de.documan.domain.ReportSection;
import de.documan.domain.ReportSettings;
import de.documan.repo.ActivityRepository;
import de.documan.repo.ActivityRepository.UserActivity;
import de.documan.repo.BackupRepository;
import de.documan.repo.MasterRepository;
import de.documan.repo.ReportRepository;
import de.documan.repo.UserRepository;

/**
 * Die tägliche Übersicht per E-Mail an die Superuser.
 *
 * <p>Der Worker fragt regelmäßig, ob sie fällig ist. Ein verpasster Termin, etwa weil das NAS aus war, wird am
 * selben Tag nachgeholt, aber an einem Tag nie zweimal verschickt. Was die Mail enthält, wann sie geht und an
 * wen, steht in daily_report und wird auf der Seite „Übersichtsmail“ eingestellt.
 */
@Service
public class DailyReportService {

    /** Betreff, Text, HTML und Bilder einer Übersicht. images: Content-ID → PNG der Torten. */
    public record Report(String subject, String text, String html, Map<String, byte[]> images) {
    }

    /**
     * Ein Balken oder Tortenstück. shown ist der Wert, wie er daneben steht.
     *
     * @param color feste Farbe in der Torte, etwa grau für „Weitere“. Leer: die nächste Farbe der Palette.
     */
    public record Bar(String label, long value, String shown, String color) {

        public Bar(String label, long value, String shown) {
            this(label, value, shown, "");
        }
    }

    /**
     * Ein Abschnitt der Übersicht. Im Text werden Balken und Tortenstücke zu Aufzählungen.
     *
     * @param scale Wert, der die ganze Breite füllt. 0 heißt: der größte Balken.
     */
    public record Section(String title, List<String> lines, List<Bar> bars, long scale, ReportChart chart, String key) {

        public Section(String title, List<String> lines) {
            this(title, lines, List.of(), 0, ReportChart.BARS, "");
        }

        public Section(String title, List<String> lines, List<Bar> bars, long scale) {
            this(title, lines, bars, scale, ReportChart.BARS, "");
        }

        public long total() {
            return bars.stream().mapToLong(Bar::value).sum();
        }

        public boolean isPie() {
            return chart == ReportChart.PIE && total() > 0;
        }

        public List<String> colors() {
            Iterator<String> palette = PALETTE.iterator();
            return bars.stream().map(bar -> bar.color().isEmpty() ? palette.next() : bar.color()).toList();
        }

        public List<String> items() {
            long total = total();
            List<String> items = new ArrayList<>(lines);
            for (Bar bar : bars) {
                String share = isPie() ? " (" + percent(bar.value(), total) + " %)" : "";
                items.add(bar.label() + ": " + bar.shown() + share);
            }
            return items;
        }

        public String text() {
            List<String> result = new ArrayList<>(List.of(title));
            items().forEach(item -> result.add("– " + item));
            return String.join("\n", result);
        }

        String contentId() {
            return "documan-" + key;
        }
    }

    /** Ein Empfänger. user fehlt bei weiteren Adressen, dann lautet die Anrede nur „Hallo,“. */
    public record Recipient(String address, AppUser user) {

        public String name() {
            return user == null ? "" : user.username();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(DailyReportService.class);
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final int FAILED_LISTED = 10;
    private static final int CHART_DAYS = 14;
    private static final int SOURCE_DAYS = 30;
    private static final int TYPES_LISTED = 5;
    private static final List<String> SIZE_UNITS = List.of("Bytes", "KB", "MB", "GB", "TB");
    private static final List<String> WEEKDAY_SHORT = List.of("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So");
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter CHART_DAY = DateTimeFormatter.ofPattern("dd.MM.");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    /** Farben der Tortenstücke, wie die Torten der Startseite im hellen Modus. */
    static final List<String> PALETTE = List.of("#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300",
            "#6250d6", "#e34948");
    private static final String OTHER = "#9aa5a1";
    private static final String FREE = "#d5ddda";
    // Inline-Stile, weil viele Mailprogramme Stylesheets verwerfen. Werte bleiben immer als Zahl lesbar.
    private static final String PAGE = "padding:24px;font-family:Segoe UI,Arial,sans-serif;font-size:14px;"
            + "line-height:1.5;color:#1d2321";
    private static final String HEADING = "font-size:15px;margin:22px 0 6px;color:#1d2321";
    private static final String LABEL = "padding:3px 10px 3px 0;white-space:nowrap;color:#1d2321";
    private static final String VALUE = "padding:3px 0 3px 10px;text-align:right;white-space:nowrap;color:#1d2321";
    private static final String FILL = "background:#2f7d6d;height:12px;line-height:12px;font-size:0";
    private static final String TRACK = "background:#e3e9e7;height:12px;line-height:12px;font-size:0";
    private static final String LEGEND = "padding:3px 10px 3px 0;color:#1d2321";
    private static final int PIE_SIZE = 160;

    private final DocuManProperties properties;
    private final JdbcClient jdbc;
    private final UserRepository users;
    private final ActivityRepository activities;
    private final StorageService storage;
    private final InboxFolder inboxFolder;
    private final BackupRepository backups;
    private final ReportRepository reports;
    private final Notifications notifications;
    private final TransactionTemplate transaction;

    public DailyReportService(DocuManProperties properties, JdbcClient jdbc, UserRepository users,
            ActivityRepository activities, StorageService storage, InboxFolder inboxFolder, BackupRepository backups,
            ReportRepository reports, Notifications notifications, PlatformTransactionManager transactions) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.users = users;
        this.activities = activities;
        this.storage = storage;
        this.inboxFolder = inboxFolder;
        this.backups = backups;
        this.reports = reports;
        this.notifications = notifications;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Die Uhrzeit der Übersicht, null wenn sie ausgeschaltet ist. */
    public LocalTime reportTime() {
        ReportSettings config = reports.load();
        return config.enabled() ? config.time() : null;
    }

    public String scheduleText() {
        return scheduleText(reports.load());
    }

    /** Der Zeitplan in Worten, etwa „täglich um 07:00“ oder „Mo, Mi, Fr um 07:00“. */
    public static String scheduleText(ReportSettings config) {
        List<Integer> days = config.weekdays().stream().sorted().toList();
        if (!config.enabled() || days.isEmpty()) {
            return "ausgeschaltet";
        }
        String at = CLOCK.format(config.time());
        if (days.size() == WEEKDAY_SHORT.size()) {
            return "täglich um " + at;
        }
        return days.stream().map(WEEKDAY_SHORT::get).collect(Collectors.joining(", ")) + " um " + at;
    }

    /** Wann die Übersicht als Nächstes geht. Ist der heutige Termin verpasst, ist das gleich. */
    public Instant nextSend(ReportSettings config, Instant now) {
        if (!config.enabled() || config.weekdays().isEmpty()) {
            return null;
        }
        LocalDate today = local(now).toLocalDate();
        for (int offset = 0; offset < 8; offset++) {
            LocalDate day = today.plusDays(offset);
            if (!config.weekdays().contains(day.getDayOfWeek().getValue() - 1) || !day.isAfter(config.sentOn())) {
                continue;
            }
            Instant moment = day.atTime(config.time()).atZone(properties.getTimeZone()).toInstant();
            return moment.isAfter(now) ? moment : now;
        }
        return null;
    }

    /** Der nächste Termin für die Einstellungsseite, etwa „Montag, 09.11.2026 um 07:00 Uhr“. */
    public String nextSendText(ReportSettings config, Instant now) {
        Instant moment = nextSend(config, now);
        if (moment == null) {
            return "";
        }
        ZonedDateTime local = local(moment);
        return BackupSettings.WEEKDAYS.get(local.getDayOfWeek().getValue() - 1) + ", " + DAY.format(local) + " um "
                + CLOCK.format(local) + " Uhr";
    }

    public static String number(long value) {
        return String.format(Locale.GERMANY, "%,d", value);
    }

    public static String size(long value) {
        double amount = value;
        int unit = 0;
        while (amount >= 1024 && unit < SIZE_UNITS.size() - 1) {
            amount /= 1024;
            unit++;
        }
        if (unit == 0) {
            return value + " " + SIZE_UNITS.getFirst();
        }
        return String.format(Locale.GERMANY, "%.1f", amount) + " " + SIZE_UNITS.get(unit);
    }

    /** Anteil mit einer Nachkommastelle. Ganzzahlig gerundet, damit Python genauso rundet. */
    public static String percent(long value, long total) {
        long tenths = (value * 2000 + total) / (2 * total);
        return tenths / 10 + "," + tenths % 10;
    }

    private ZonedDateTime local(Instant moment) {
        return moment.atZone(properties.getTimeZone());
    }

    private String moment(Instant moment) {
        return MOMENT.format(local(moment));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private List<Section> documents(Instant since) {
        Timestamp from = Timestamp.from(since);
        long[] counts = jdbc.sql("""
                SELECT count(*) AS total,
                    count(*) FILTER (WHERE added >= ?) AS new,
                    count(*) FILTER (WHERE added >= ? AND source = 'upload') AS uploaded,
                    count(*) FILTER (WHERE added >= ? AND source = 'consume') AS consumed,
                    count(*) FILTER (WHERE added >= ? AND source = 'mail') AS mailed,
                    count(*) FILTER (WHERE inbox AND status = 'done') AS inbox,
                    count(*) FILTER (WHERE status IN ('pending', 'processing')) AS busy,
                    count(*) FILTER (WHERE status = 'failed') AS failed
                FROM document
                """)
                .params(from, from, from, from)
                .query((row, index) -> new long[] {row.getLong("total"), row.getLong("new"), row.getLong("uploaded"),
                        row.getLong("consumed"), row.getLong("mailed"), row.getLong("inbox"), row.getLong("busy"),
                        row.getLong("failed")})
                .single();
        List<Section> sections = new ArrayList<>();
        sections.add(new Section("Dokumente", List.of(
                "Neu: " + number(counts[1]) + " (hochgeladen " + number(counts[2]) + ", Eingangsordner "
                        + number(counts[3]) + ", E-Mail " + number(counts[4]) + ")",
                "Warten im Eingang: " + number(counts[5]),
                "In Verarbeitung: " + number(counts[6]),
                "Fehlgeschlagen: " + number(counts[7]),
                "Insgesamt: " + number(counts[0]))));
        long failed = counts[7];
        if (failed > 0) {
            List<String> lines = new ArrayList<>(jdbc
                    .sql("SELECT title, error FROM document WHERE status = 'failed' ORDER BY modified DESC LIMIT ?")
                    .param(FAILED_LISTED)
                    .query((row, index) -> row.getString("title") + ": " + row.getString("error"))
                    .list());
            if (failed > FAILED_LISTED) {
                lines.add("und " + number(failed - FAILED_LISTED) + " weitere");
            }
            sections.add(new Section("Fehlgeschlagene Dokumente", lines));
        }
        return sections;
    }

    /** Neue Dokumente je Kalendertag, der heutige zuletzt. */
    private Section perDay(Instant now) {
        LocalDate first = local(now).toLocalDate().minusDays(CHART_DAYS - 1);
        Instant start = first.atStartOfDay(properties.getTimeZone()).toInstant();
        Map<LocalDate, Long> counts = new HashMap<>();
        jdbc.sql("""
                SELECT (added AT TIME ZONE ?)::date AS day, count(*) AS count FROM document
                WHERE added >= ? GROUP BY day
                """)
                .params(properties.getTimeZone().getId(), Timestamp.from(start))
                .query((ResultSet row) -> {
                    counts.put(row.getObject("day", LocalDate.class), row.getLong("count"));
                });
        List<Bar> bars = new ArrayList<>();
        for (int offset = 0; offset < CHART_DAYS; offset++) {
            LocalDate day = first.plusDays(offset);
            long count = counts.getOrDefault(day, 0L);
            bars.add(new Bar(CHART_DAY.format(day), count, number(count)));
        }
        return new Section("Neue Dokumente der letzten " + CHART_DAYS + " Tage", List.of(), bars, 0);
    }

    private Section sources(Instant now, ReportChart chart) {
        Map<String, Long> counts = jdbc.sql("SELECT source, count(*) AS count FROM document WHERE added >= ? GROUP BY source")
                .param(Timestamp.from(now.minus(Duration.ofDays(SOURCE_DAYS))))
                .query((row, index) -> Map.entry(row.getString("source"), row.getLong("count")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        List<Bar> bars = new ArrayList<>();
        for (DocumentSource source : DocumentSource.values()) {
            long count = counts.getOrDefault(source.value(), 0L);
            bars.add(new Bar(source.label(), count, number(count)));
        }
        return new Section("Herkunft der Dokumente, letzte " + SOURCE_DAYS + " Tage", List.of(), bars, 0, chart,
                "sources");
    }

    private Section types(ReportChart chart) {
        List<Bar> bars = new ArrayList<>(jdbc.sql("""
                SELECT t.name, count(*) AS count FROM document d JOIN document_type t ON t.id = d.document_type_id
                GROUP BY t.id, t.name ORDER BY count DESC, t.name LIMIT ?
                """)
                .param(TYPES_LISTED)
                .query((row, index) -> new Bar(row.getString("name"), row.getLong("count"), number(row.getLong("count"))))
                .list());
        List<String> lines = bars.isEmpty() ? List.of("noch keine Dokumenttypen vergeben") : List.of();
        if (!bars.isEmpty() && chart == ReportChart.PIE) {
            // Eine Torte zeigt das Ganze, also bekommen die kleineren Typen ein gemeinsames Stück.
            long typed = jdbc.sql("SELECT count(*) FROM document WHERE document_type_id IS NOT NULL")
                    .query(Long.class).single();
            long others = typed - bars.stream().mapToLong(Bar::value).sum();
            if (others > 0) {
                bars.add(new Bar("Weitere Dokumenttypen", others, number(others), OTHER));
            }
        }
        return new Section("Die " + TYPES_LISTED + " größten Dokumenttypen", lines, bars, 0, chart, "types");
    }

    /** Die oberste Kategoriestufe samt allen Unterkategorien. Ohne Kategorie steht zuletzt. */
    private Section categories(ReportChart chart) {
        String title = "Dokumente nach Kategorie";
        CategoryPaths paths = new CategoryPaths(jdbc.sql("SELECT id, name, parent_id FROM category")
                .query((row, index) -> new MasterRepository.CategoryNode(row.getLong("id"), row.getString("name"),
                        (Long) row.getObject("parent_id", Long.class)))
                .list());
        if (paths.roots().isEmpty()) {
            return new Section(title, List.of("noch keine Kategorien vergeben"), List.of(), 0, chart, "categories");
        }
        Map<Long, Long> direct = new HashMap<>();
        long[] without = {0};
        jdbc.sql("""
                SELECT COALESCE(d.category_id, t.category_id) AS category, count(*) AS count FROM document d
                LEFT JOIN document_type t ON t.id = d.document_type_id GROUP BY 1
                """)
                .query(row -> {
                    Long category = (Long) row.getObject("category", Long.class);
                    if (paths.contains(category)) {
                        direct.put(category, row.getLong("count"));
                    } else {
                        without[0] += row.getLong("count");
                    }
                });
        Map<Long, Long> totals = paths.totals(direct);
        List<Bar> bars = new ArrayList<>(paths.roots().stream().filter(id -> totals.getOrDefault(id, 0L) > 0)
                .sorted(Comparator.comparing((Long id) -> -totals.get(id)).thenComparing(paths::name))
                .map(id -> new Bar(paths.name(id), totals.get(id), number(totals.get(id))))
                .toList());
        if (without[0] > 0) {
            bars.add(new Bar("Ohne Kategorie", without[0], number(without[0]), OTHER));
        }
        return new Section(title, List.of(), bars, 0, chart, "categories");
    }

    private Section activities(Instant since) {
        List<String> lines = new ArrayList<>();
        for (UserActivity row : activities.overview(since)) {
            lines.add(row.username() + ": " + number(row.uploaded()) + " hochgeladen, " + number(row.filed())
                    + " Eingang bearbeitet, " + number(row.edited()) + " bearbeitet, " + number(row.deleted())
                    + " gelöscht");
        }
        return new Section("Aktivitäten der Benutzer", lines.isEmpty() ? List.of("keine") : lines);
    }

    private Section mailboxes() {
        List<String> lines = jdbc
                .sql("SELECT name, folder, enabled, last_check_at, last_error FROM mail_account ORDER BY name")
                .query((row, index) -> {
                    Instant checked = instant(row, "last_check_at");
                    String state = !row.getBoolean("enabled") ? "ausgeschaltet"
                            : checked != null ? "zuletzt abgerufen " + moment(checked) : "noch nie abgerufen";
                    String error = row.getString("last_error");
                    String problem = error == null || error.isEmpty() ? "" : ", Fehler: " + error;
                    return row.getString("name") + ", Ordner " + row.getString("folder") + ": " + state + problem;
                })
                .list();
        return new Section("Postfächer", lines.isEmpty() ? List.of("keine eingerichtet") : lines);
    }

    /** Der Zielordner der Sicherung, unter nas/ als Pfad auf dem NAS. */
    private String backupTarget(String folder) {
        int slash = folder.indexOf('/');
        String first = slash < 0 ? folder : folder.substring(0, slash);
        String rest = slash < 0 ? "" : folder.substring(slash + 1);
        String host = properties.getBrowseHostDir();
        if (InboxFolder.NAS.equals(first) && host != null && !host.isEmpty()) {
            String base = host.replaceAll("/+$", "");
            return rest.isEmpty() ? base : base.isEmpty() ? rest : base + "/" + rest;
        }
        return "Sicherungsordner" + (folder.isEmpty() ? "" : "/" + folder);
    }

    private static String schedule(BackupSettings config) {
        if (!config.enabled()) {
            return "keine zeitgesteuerte Sicherung";
        }
        String at = CLOCK.format(config.time());
        String when = config.frequency() == BackupFrequency.WEEKLY
                ? "wöchentlich am " + BackupSettings.WEEKDAYS.get(config.weekday()) + " um " + at
                : "täglich um " + at;
        String kept = config.keep() > 0 ? "die letzten " + number(config.keep()) + " bleiben" : "alle bleiben";
        return when + ", " + kept;
    }

    private Section folders() {
        List<String> lines = new ArrayList<>();
        for (InboxFolder.Status entry : inboxFolder.status()) {
            String state = !entry.reachable() ? "nicht gefunden" : entry.writable() ? "erreichbar" : "nur lesbar";
            lines.add("Eingangsordner " + entry.path() + ": " + state + ", " + number(entry.waiting()) + " wartend, "
                    + number(entry.rejected()) + " abgelehnt");
        }
        BackupSettings config = backups.loadSettings();
        lines.add("Sicherungsziel " + backupTarget(config.folder()) + ": " + schedule(config));
        return new Section("Verzeichnisse", lines);
    }

    private Section backup() {
        String state = jdbc.sql("""
                SELECT status, size, error, finished_at FROM backup_run
                WHERE kind = 'backup' AND status IN ('done', 'failed')
                ORDER BY finished_at DESC, id DESC LIMIT 1
                """)
                .query((row, index) -> {
                    String finished = moment(instant(row, "finished_at"));
                    return "done".equals(row.getString("status"))
                            ? finished + ", erfolgreich, " + size(row.getLong("size"))
                            : finished + ", fehlgeschlagen: " + row.getString("error");
                })
                .optional().orElse("noch keine");
        return new Section("Sicherung", List.of("Letzte Sicherung: " + state));
    }

    private Section storage(ReportChart chart) {
        StorageService.Usage usage = storage.usage();
        StorageService.Drive drive = usage.drive();
        boolean pie = chart == ReportChart.PIE;
        List<Bar> bars = new ArrayList<>(List.of(
                new Bar("Dokumente", usage.media(), size(usage.media())),
                new Bar("Datenbank", usage.database(), size(usage.database())),
                new Bar("Sicherungen", usage.backups(), size(usage.backups()))));
        if (drive == null) {
            return new Section("Speicher", List.of(), bars, 0, chart, "storage");
        }
        if (pie) {
            // Die Torte zeigt das ganze Laufwerk, dazu gehört auch, was andere Programme belegen.
            long rest = Math.max(drive.used() - usage.media() - usage.database() - usage.backups(), 0);
            bars.add(new Bar("Übriges belegt", rest, size(rest), OTHER));
        }
        bars.add(new Bar("Frei auf dem Laufwerk", drive.free(), size(drive.free()), pie ? FREE : ""));
        String line = "Laufwerk: " + size(drive.used()) + " von " + size(drive.total()) + " belegt ("
                + String.format(Locale.GERMANY, "%.1f", drive.percent()) + " %)";
        return new Section("Speicher", List.of(line), bars, drive.total(), chart, "storage");
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }

    public static String barsHtml(Section section) {
        long top = section.scale() > 0 ? section.scale()
                : section.bars().stream().mapToLong(Bar::value).max().orElse(0);
        StringBuilder rows = new StringBuilder();
        for (Bar bar : section.bars()) {
            // Wie Pythons round(): bei genau ,5 zur geraden Zahl, damit beide Versionen gleich zeichnen.
            int width = top > 0 ? (int) Math.rint(bar.value() * 100.0 / top) : 0;
            width = bar.value() > 0 ? Math.max(width, 1) : 0;
            String cells = (width > 0 ? "<td width=\"" + width + "%\" style=\"" + FILL + "\">&nbsp;</td>" : "")
                    + (width < 100 ? "<td style=\"" + TRACK + "\">&nbsp;</td>" : "");
            rows.append("<tr><td width=\"30%\" style=\"").append(LABEL).append("\">").append(escape(bar.label()))
                    .append("</td>")
                    .append("<td width=\"55%\" style=\"padding:3px 0\"><table role=\"presentation\" width=\"100%\" ")
                    .append("cellpadding=\"0\" cellspacing=\"0\"><tr>").append(cells).append("</tr></table></td>")
                    .append("<td style=\"").append(VALUE).append("\">").append(escape(bar.shown())).append("</td></tr>");
        }
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"border-collapse:collapse;font-size:13px\">" + rows + "</table>";
    }

    private static String pieHtml(Section section) {
        long total = section.total();
        List<String> colors = section.colors();
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < section.bars().size(); index++) {
            Bar bar = section.bars().get(index);
            String swatch = "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                    + "<td width=\"10\" height=\"10\" style=\"width:10px;height:10px;background:" + colors.get(index)
                    + ";font-size:0;line-height:0\">&nbsp;</td></tr></table>";
            rows.append("<tr><td style=\"padding:3px 8px 3px 0\">").append(swatch).append("</td>")
                    .append("<td style=\"").append(LEGEND).append("\">").append(escape(bar.label())).append("</td>")
                    .append("<td style=\"").append(VALUE).append("\">").append(escape(bar.shown())).append("</td>")
                    .append("<td style=\"").append(VALUE).append("\">").append(percent(bar.value(), total))
                    .append(" %</td></tr>");
        }
        String alt = escape("Tortengrafik: " + section.title() + ". Die Werte stehen daneben.");
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse\">"
                + "<tr><td valign=\"middle\" style=\"padding:4px 18px 4px 0\">"
                + "<img src=\"cid:" + section.contentId() + "\" width=\"" + PIE_SIZE + "\" height=\"" + PIE_SIZE
                + "\" alt=\"" + alt + "\" style=\"display:block;border:0\"></td>"
                + "<td valign=\"middle\"><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"border-collapse:collapse;font-size:13px\">" + rows + "</table></td></tr></table>";
    }

    private static String sectionHtml(Section section) {
        List<String> parts = new ArrayList<>();
        parts.add("<h2 style=\"" + HEADING + "\">" + escape(section.title()) + "</h2>");
        if (!section.lines().isEmpty()) {
            String items = section.lines().stream().map(line -> "<li>" + escape(line) + "</li>")
                    .collect(Collectors.joining());
            parts.add("<ul style=\"margin:0 0 6px;padding-left:18px\">" + items + "</ul>");
        }
        if (section.isPie()) {
            parts.add(pieHtml(section));
        } else if (!section.bars().isEmpty()) {
            parts.add(barsHtml(section));
        }
        return String.join("\n", parts);
    }

    private static String html(List<String> intro, List<Section> sections, String footer) {
        String paragraphs = intro.stream().map(line -> "<p style=\"margin:0 0 10px\">" + escape(line) + "</p>")
                .collect(Collectors.joining());
        String body = sections.stream().map(DailyReportService::sectionHtml).collect(Collectors.joining("\n"));
        if (!footer.isEmpty()) {
            String link = escape(footer);
            body += "\n<p style=\"margin:22px 0 0\">Zur Übersicht: <a href=\"" + link + "\">" + link + "</a></p>";
        }
        return "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"></head>"
                + "<body style=\"margin:0;padding:16px;background:#f2f4f3\">\n"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                + "<td align=\"center\">\n"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"max-width:640px;background:#ffffff;border-radius:8px\"><tr>"
                + "<td style=\"" + PAGE + "\">\n" + paragraphs + "\n" + body + "\n</td></tr></table>\n"
                + "</td></tr></table>\n</body></html>";
    }

    private List<Section> sections(ReportSettings config, Instant now, Instant since) {
        Map<ReportSection, Supplier<List<Section>>> builders = new LinkedHashMap<>();
        builders.put(ReportSection.DOCUMENTS, () -> documents(since));
        builders.put(ReportSection.PER_DAY, () -> List.of(perDay(now)));
        builders.put(ReportSection.SOURCES, () -> List.of(sources(now, config.sourcesChart())));
        builders.put(ReportSection.TYPES, () -> List.of(types(config.typesChart())));
        builders.put(ReportSection.CATEGORIES, () -> List.of(categories(config.categoriesChart())));
        builders.put(ReportSection.ACTIVITIES, () -> List.of(activities(since)));
        builders.put(ReportSection.MAILBOXES, () -> List.of(mailboxes()));
        builders.put(ReportSection.FOLDERS, () -> List.of(folders()));
        builders.put(ReportSection.BACKUP, () -> List.of(backup()));
        builders.put(ReportSection.STORAGE, () -> List.of(storage(config.storageChart())));
        List<Section> sections = new ArrayList<>();
        builders.forEach((key, builder) -> {
            if (config.shows(key)) {
                sections.addAll(builder.get());
            }
        });
        return sections;
    }

    /** Betreff, Text, HTML und Bilder der Übersicht für die letzten 24 Stunden. */
    public Report build(String name, Instant now, ReportSettings config) {
        Instant since = now.minus(WINDOW);
        List<String> intro = List.of(name.isEmpty() ? "Hallo," : "Hallo " + name + ",",
                "hier ist die Übersicht von DocuMan für die 24 Stunden bis " + moment(now) + ".");
        List<Section> sections = sections(config, now, since);
        String site = properties.getSiteUrl().strip();
        String footer = site.isEmpty() ? "" : site.replaceAll("/+$", "") + "/";
        List<String> blocks = new ArrayList<>(intro);
        sections.forEach(section -> blocks.add(section.text()));
        if (!footer.isEmpty()) {
            blocks.add("Zur Übersicht: " + footer);
        }
        Map<String, byte[]> images = new LinkedHashMap<>();
        for (Section section : sections) {
            if (section.isPie()) {
                images.put(section.contentId(),
                        MailCharts.piePng(section.bars().stream().map(Bar::value).toList(), section.colors()));
            }
        }
        return new Report("DocuMan: Übersicht vom " + DAY.format(local(now)), String.join("\n\n", blocks) + "\n",
                html(intro, sections, footer), images);
    }

    public Report build(AppUser user, Instant now) {
        return build(user.username(), now, reports.load());
    }

    private Notifications.Result deliver(Recipient recipient, Instant now, ReportSettings config) {
        Report report = build(recipient.name(), now, config);
        return notifications.sendHtml(recipient.address(), report.subject(), report.text(), report.html(),
                report.images());
    }

    /** Schickt die Übersicht an einen Benutzer. */
    public Notifications.Result send(AppUser user, Instant now) {
        if (user.email().isEmpty()) {
            return new Notifications.Result(Notifications.Delivery.FAILED,
                    "Für " + user.username() + " ist keine E-Mail-Adresse eingetragen.");
        }
        return deliver(new Recipient(user.email(), user), now, reports.load());
    }

    /** Aktive Superuser mit Adresse, die nicht abgewählt sind, dann die weiteren Empfänger. */
    public List<Recipient> recipients(ReportSettings config) {
        List<Recipient> found = new ArrayList<>();
        users.all().stream()
                .filter(user -> user.superuser() && user.active() && !user.pending() && !user.email().isEmpty())
                .filter(user -> !config.excluded().contains(user.id()))
                .sorted((left, right) -> left.username().compareTo(right.username()))
                .forEach(user -> found.add(new Recipient(user.email(), user)));
        config.extraRecipients().forEach(address -> found.add(new Recipient(address, null)));
        Set<String> seen = new HashSet<>();
        return found.stream().filter(recipient -> seen.add(recipient.address().toLowerCase(Locale.ROOT))).toList();
    }

    public List<Recipient> recipients() {
        return recipients(reports.load());
    }

    /** Verschickt die Übersicht, sobald ihr Termin erreicht ist. True, wenn sie jetzt ging. */
    public boolean sendDue(Instant now) {
        if (!notifications.available()) {
            return false;
        }
        ZonedDateTime local = local(now);
        LocalDate today = local.toLocalDate();
        ReportSettings config = reports.load(today);
        if (!config.enabled() || !config.weekdays().contains(local.getDayOfWeek().getValue() - 1)
                || local.toLocalTime().isBefore(config.time())) {
            return false;
        }
        boolean due = Boolean.TRUE.equals(transaction.execute(status -> {
            LocalDate sentOn = jdbc.sql("SELECT sent_on FROM daily_report WHERE id = 1 FOR UPDATE")
                    .query(LocalDate.class).single();
            if (!sentOn.isBefore(today)) {
                return false;
            }
            // Erst vermerken, dann senden: Scheitert der Versand, folgt nicht alle 30 s ein neuer.
            jdbc.sql("UPDATE daily_report SET sent_on = ? WHERE id = 1").param(today).update();
            return true;
        }));
        if (!due) {
            return false;
        }
        for (Recipient recipient : recipients(config)) {
            Notifications.Result result = deliver(recipient, now, config);
            if (!result.sent()) {
                log.warn("Tägliche Übersicht an {} nicht verschickt: {}", recipient.address(), result.problem());
            }
        }
        return true;
    }
}
