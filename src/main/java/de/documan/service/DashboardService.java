package de.documan.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import de.documan.config.DocuManProperties;
import de.documan.domain.Document;
import de.documan.domain.DocumentStatus;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.ActivityRepository;
import de.documan.repo.DocumentRepository;
import de.documan.repo.DocumentRepository.Summary;
import de.documan.repo.MasterRepository;
import de.documan.repo.Selection;

/** Kennzahlen und Übersichten der Startseite. */
@Service
public class DashboardService {

    private static final int CATEGORY_ROWS = 6;
    private static final int CHART_MONTHS = 12;
    private static final int INBOX_ROWS = 5;
    private static final int RECENT_DOCUMENTS = 6;
    private static final List<String> MONTH_NAMES = List.of("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli",
            "August", "September", "Oktober", "November", "Dezember");
    private static final Map<String, String> SHORT_NAMES = Map.of("Juni", "Jun", "Juli", "Jul");
    private static final int PIE_SLICES = 8;
    private static final int PIE_CENTER_X = 160;
    private static final int PIE_CENTER_Y = 80;
    private static final int PIE_RADIUS_X = 140;
    private static final int PIE_RADIUS_Y = 70;
    private static final int PIE_DEPTH = 30;
    private static final int PIE_START = -90;
    private static final String PIE_ARC = "A " + PIE_RADIUS_X + " " + PIE_RADIUS_Y + " 0 ";

    public record Stat(String label, String value, String url, String note) {
    }

    public record Row(String label, long count, long share, String color, String url) {
    }

    public record Category(String title, String icon, long total, String manageUrl, long without, String withoutUrl,
            List<Row> rows) {
    }

    public record Column(String label, String name, int count, double height, boolean peak) {
    }

    public record Tick(int value, double position) {
    }

    public record Chart(List<Column> columns, List<Tick> ticks, int total) {
    }

    /** Ein Tortenstück. {@code url} fehlt bei „Weitere“, {@code wall} ist bei Stücken hinten leer. */
    public record Slice(int number, String label, long count, long percent, String url, String top, String wall) {
    }

    public record Pie(List<Slice> slices, long total) {
    }

    public record Dashboard(Summary summary, List<Stat> stats, List<Category> categories, Chart chart, Pie pie,
            Pie inboxPie, List<Document> inboxDocuments, List<Document> recentDocuments) {
    }

    private final DocuManProperties properties;
    private final DocumentRepository documents;
    private final MasterRepository master;
    private final ActivityRepository activities;

    public DashboardService(DocuManProperties properties, DocumentRepository documents, MasterRepository master,
            ActivityRepository activities) {
        this.properties = properties;
        this.documents = documents;
        this.master = master;
        this.activities = activities;
    }

    private static String germanNumber(long value) {
        return new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.GERMANY)).format(value);
    }

    /** Runde Achsenobergrenze, zum Beispiel 37 -> 50. */
    public static int niceCeiling(int value) {
        if (value <= 1) {
            return 1;
        }
        int magnitude = (int) Math.pow(10, Math.floor(Math.log10(value)));
        for (int factor : new int[] {1, 2, 5, 10}) {
            if (value <= factor * magnitude) {
                return factor * magnitude;
            }
        }
        return 10 * magnitude;
    }

    private Category category(MasterKind kind, String icon, long without) {
        List<MasterItem> items = master.ranked(kind, CATEGORY_ROWS);
        long largest = items.isEmpty() ? 1 : items.getFirst().documentCount();
        String filter = kind.filterName();
        List<Row> rows = items.stream()
                .map(item -> new Row(item.name(), item.documentCount(),
                        Math.round(item.documentCount() * 100.0 / largest), item.color(),
                        Filters.listLink(filter, String.valueOf(item.id()))))
                .toList();
        return new Category(kind.plural(), icon, master.count(kind), "/stammdaten/" + kind.slug(), without,
                kind == MasterKind.TAG ? null : Filters.listLink(filter, Filters.NONE), rows);
    }

    /** Die oberste Kategoriestufe, jede mit den Dokumenten aller Unterkategorien. */
    private Category topCategories(long without) {
        MasterKind kind = MasterKind.CATEGORY;
        CategoryPaths paths = new CategoryPaths(master.categories());
        Map<Long, Long> totals = paths.totals(documents.countsBy(
                "COALESCE(d.category_id, (SELECT t.category_id FROM document_type t WHERE t.id = d.document_type_id))",
                Selection.ALL));
        List<Long> roots = paths.roots().stream().filter(id -> totals.getOrDefault(id, 0L) > 0)
                .sorted(Comparator.comparing((Long id) -> -totals.get(id)).thenComparing(paths::name))
                .limit(CATEGORY_ROWS).toList();
        long largest = roots.isEmpty() ? 1 : totals.get(roots.getFirst());
        List<Row> rows = roots.stream()
                .map(id -> new Row(paths.name(id), totals.get(id), Math.round(totals.get(id) * 100.0 / largest), null,
                        Filters.listLink(kind.filterName(), String.valueOf(id))))
                .toList();
        return new Category(kind.plural(), "i-category", master.count(kind), "/stammdaten/" + kind.slug(), without,
                Filters.listLink(kind.filterName(), Filters.NONE), rows);
    }

    /** Die letzten Monate inklusive des aktuellen, älteste zuerst. */
    private static List<YearMonth> monthSequence(LocalDate today) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth month = YearMonth.from(today);
        for (int index = 0; index < CHART_MONTHS; index++) {
            months.add(month);
            month = month.minusMonths(1);
        }
        Collections.reverse(months);
        return months;
    }

    /** Monatsverteilung nach Dokumentdatum als Säulen mit einer runden Skala. */
    public Chart buildChart(LocalDate today) {
        List<YearMonth> months = monthSequence(today);
        Map<Integer, Integer> counts = documents.countsByMonth(months.getFirst().atDay(1), today);
        List<Integer> values = months.stream()
                .map(month -> counts.getOrDefault(month.getYear() * 100 + month.getMonthValue(), 0))
                .toList();
        int largest = Collections.max(values);
        int top = niceCeiling(largest);
        List<Integer> ticks = top % 2 == 0 ? List.of(top, top / 2, 0) : List.of(top, 0);

        List<Column> columns = new ArrayList<>();
        boolean peakMarked = false;
        for (int index = 0; index < months.size(); index++) {
            YearMonth month = months.get(index);
            int count = values.get(index);
            String name = MONTH_NAMES.get(month.getMonthValue() - 1);
            boolean peak = count == largest && count > 0 && !peakMarked;
            peakMarked = peakMarked || peak;
            columns.add(new Column(SHORT_NAMES.getOrDefault(name, name.substring(0, 3)), name + " " + month.getYear(),
                    count, rounded(count * 100.0 / top), peak));
        }
        return new Chart(columns, ticks.stream().map(tick -> new Tick(tick, rounded(tick * 100.0 / top))).toList(),
                values.stream().mapToInt(Integer::intValue).sum());
    }

    private static double rounded(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static String decimal(double value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String piePoint(double angle, int depth) {
        double turn = Math.toRadians(angle);
        return decimal(PIE_CENTER_X + PIE_RADIUS_X * Math.cos(turn)) + " "
                + decimal(PIE_CENTER_Y + PIE_RADIUS_Y * Math.sin(turn) + depth);
    }

    private static String pieTop(double start, double end) {
        if (end - start >= 360) {
            return "M " + piePoint(180, 0) + " " + PIE_ARC + "1 1 " + piePoint(0, 0) + " " + PIE_ARC + "1 1 "
                    + piePoint(180, 0) + " Z";
        }
        int large = end - start > 180 ? 1 : 0;
        return "M " + PIE_CENTER_X + " " + PIE_CENTER_Y + " L " + piePoint(start, 0) + " " + PIE_ARC + large + " 1 "
                + piePoint(end, 0) + " Z";
    }

    /** Sichtbar ist nur die Vorderseite, also die Winkel von 0 bis 180 Grad. */
    private static String pieWall(double start, double end) {
        double first = Math.max(start, 0);
        double last = Math.min(end, 180);
        if (last <= first) {
            return "";
        }
        return "M " + piePoint(first, 0) + " " + PIE_ARC + "0 1 " + piePoint(last, 0) + " L "
                + piePoint(last, PIE_DEPTH) + " " + PIE_ARC + "0 0 " + piePoint(first, PIE_DEPTH) + " Z";
    }

    private record PieEntry(String value, String label, long count) {
    }

    /** Stücke der 3D-Torte. {@code counts} ordnet dem Wert von „Eingespielt von“ eine Anzahl zu. */
    public static Pie buildPie(Map<String, Long> counts) {
        return buildPie(counts, true);
    }

    public static Pie buildPie(Map<String, Long> counts, boolean linked) {
        List<PieEntry> entries = counts.entrySet().stream()
                .map(entry -> entry.getKey().isEmpty()
                        ? new PieEntry(Filters.NONE, Filters.UNKNOWN_ADDER, entry.getValue())
                        : new PieEntry(entry.getKey(), entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(PieEntry::count).reversed().thenComparing(PieEntry::label))
                .collect(Collectors.toCollection(ArrayList::new));
        if (entries.size() > PIE_SLICES) {
            long rest = entries.subList(PIE_SLICES - 1, entries.size()).stream().mapToLong(PieEntry::count).sum();
            entries = new ArrayList<>(entries.subList(0, PIE_SLICES - 1));
            entries.add(new PieEntry(null, "Weitere", rest));
        }
        long total = entries.stream().mapToLong(PieEntry::count).sum();

        List<Slice> slices = new ArrayList<>();
        double angle = PIE_START;
        for (PieEntry entry : entries) {
            double share = (double) entry.count() / total;
            double end = angle + share * 360;
            slices.add(new Slice(slices.size() + 1, entry.label(), entry.count(), (long) Math.rint(share * 100),
                    linked && entry.value() != null ? Filters.addedByLink(entry.value()) : null, pieTop(angle, end),
                    pieWall(angle, end)));
            angle = end;
        }
        return new Pie(slices, total);
    }

    public Dashboard build() {
        ZonedDateTime now = ZonedDateTime.now(properties.getTimeZone());
        Instant monthStart = YearMonth.from(now).atDay(1).atStartOfDay(properties.getTimeZone()).toInstant();
        Summary summary = documents.summary(monthStart);
        String list = "/dokumente";
        List<Stat> stats = List.of(
                new Stat("Dokumente", germanNumber(summary.total()), list, "im Archiv"),
                new Stat("Im Eingang", germanNumber(summary.inbox()), list + "?view=inbox",
                        summary.inbox() > 0 ? "warten aufs Ablegen" : "alles abgelegt"),
                new Stat("Diesen Monat", germanNumber(summary.thisMonth()), list + "?sort=added", "neu hinzugekommen"),
                new Stat("Seiten", germanNumber(summary.pages()), list, "erfasst und durchsuchbar"));
        List<Category> categories = List.of(
                category(MasterKind.DOCUMENT_TYPE, "i-layers", summary.withoutType()),
                category(MasterKind.CORRESPONDENT, "i-user", summary.withoutCorrespondent()),
                category(MasterKind.TAG, "i-tag", summary.withoutTag()),
                topCategories(summary.withoutCategory()));
        Selection inbox = Selection.ALL.and("d.inbox").and("d.status = ?", DocumentStatus.DONE.value());
        return new Dashboard(summary, stats, categories, buildChart(now.toLocalDate()),
                buildPie(documents.addedByCounts(Selection.ALL)), buildPie(activities.inboxCounts(), false),
                documents.page(inbox, "d.added, d.id", List.of(), INBOX_ROWS, 0),
                documents.page(Selection.ALL, "d.added DESC, d.id DESC", List.of(), RECENT_DOCUMENTS, 0));
    }
}
