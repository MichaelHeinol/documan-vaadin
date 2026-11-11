package de.documan.service;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.DocumentRepository;
import de.documan.repo.DocumentRepository.TreeRow;
import de.documan.repo.MasterRepository;
import de.documan.repo.Selection;
import de.documan.repo.UserRepository;

/** Filter, aktive Filter und Trefferzahlen der Dokumentliste. */
@Service
public class Filters {

    // Filterwert für Dokumente, bei denen das Feld leer ist.
    public static final String NONE = "none";
    public static final String UNKNOWN_ADDER = "Unbekannt";
    private static final String ADDED_BY = "added_by";
    private static final int MAX_ID_DIGITS = 18;
    private static final int MAX_YEAR_DIGITS = 4;

    private record View(String label, String condition) {
    }

    private static final Map<String, View> VIEWS = Map.of(
            // Im Eingang erscheint ein Dokument erst, wenn seine Verarbeitung fertig ist.
            "inbox", new View("Eingang", "(d.inbox AND d.status IN ('done', 'failed'))"),
            "active", new View("In Verarbeitung", "d.status IN ('pending', 'processing')"),
            "failed", new View("Fehlgeschlagen", "d.status = 'failed'"));

    /** Die eigene Kategorie des Dokuments, sonst die seines Typs. */
    private static final String CATEGORY_OF_DOCUMENT =
            "COALESCE(d.category_id, (SELECT t.category_id FROM document_type t WHERE t.id = d.document_type_id))";
    private static final String NO_CATEGORY = "Ohne Kategorie";
    /** Filtert genau eine Kategoriestufe ohne ihre Unterkategorien. */
    public static final String CATEGORY_ONLY = "categoryOnly";

    public record ActiveFilter(String label, String color, String remove) {
    }

    public record Applied(Selection selection, List<ActiveFilter> active) {
    }

    public record Option(String label, long count, String color, boolean selected, String url) {
    }

    public record Facet(String label, List<Option> options) {
    }

    /** Wonach die Ordneransicht gliedert. Der Wert steht in der Adresse, etwa /ordner?nach=typ. */
    public enum TreeOrder {
        CATEGORY("kategorie", "Kategorie", "Kategorie"),
        TYPE("typ", "Dokumenttyp / Absendername", "Typ / Absender"),
        CORRESPONDENT("absender", "Absendername / Dokumenttyp", "Absender / Typ");

        private final String value;
        private final String label;
        private final String shortLabel;

        TreeOrder(String value, String label, String shortLabel) {
            this.value = value;
            this.label = label;
            this.shortLabel = shortLabel;
        }

        /** Für die Umschalter, auch auf dem Handy. */
        public String shortLabel() {
            return shortLabel;
        }

        public String value() {
            return value;
        }

        public String label() {
            return label;
        }

        /** Unbekannte oder fehlende Werte ergeben die Ansicht nach Kategorie. */
        public static TreeOrder of(String value) {
            for (TreeOrder order : values()) {
                if (order.value.equals(value)) {
                    return order;
                }
            }
            return CATEGORY;
        }
    }

    /**
     * Ein Ordner der Ordneransicht. Hat er eigene Dokumente, lädt die Oberfläche sie beim Aufklappen über
     * query nach, sonst ist query null.
     *
     * @param category ob der Ordner eine Kategoriestufe ist, die Oberfläche zeigt dann ein anderes Symbol
     */
    public record TreeNode(String name, long count, String key, boolean category, List<TreeNode> children,
            String query) {
    }

    private final DocumentRepository documents;
    private final MasterRepository master;
    private final UserRepository users;

    public Filters(DocumentRepository documents, MasterRepository master, UserRepository users) {
        this.documents = documents;
        this.master = master;
        this.users = users;
    }

    /** Vorschläge für „Eingespielt von“: alle Benutzer und bereits vergebene Werte. */
    public List<String> knownAdders() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        users.all().forEach(user -> names.add(user.username()));
        names.addAll(documents.addedByCounts(Selection.ALL).keySet());
        names.remove("");
        return List.copyOf(names);
    }

    private static List<Long> ids(QueryParams params, String name) {
        return params.values(name).stream()
                .filter(value -> value.matches("\\d{1," + MAX_ID_DIGITS + "}"))
                .map(Long::valueOf)
                .toList();
    }

    private static List<Integer> years(QueryParams params) {
        return params.values("year").stream()
                .filter(value -> value.matches("\\d{1," + MAX_YEAR_DIGITS + "}"))
                .map(Integer::valueOf)
                .toList();
    }

    /** Gibt die Auswahl der Dokumente und die Liste der aktiven Filter zurück. */
    public Applied apply(QueryParams params) {
        Selection selection = Selection.ALL;
        List<ActiveFilter> active = new ArrayList<>();

        View view = VIEWS.get(params.first("view"));
        if (view != null) {
            selection = selection.and(view.condition());
            active.add(new ActiveFilter(view.label(), null, params.without("view")));
        }

        selection = single(params, MasterKind.CORRESPONDENT, "d.correspondent_id", "Ohne Absendername", selection,
                active);
        selection = single(params, MasterKind.DOCUMENT_TYPE, "d.document_type_id", "Ohne Dokumenttyp", selection,
                active);
        selection = categories(params, selection, active);

        for (MasterItem tag : master.withIds(MasterKind.TAG, ids(params, "tag").toArray(Long[]::new))) {
            selection = selection.and(
                    "EXISTS (SELECT 1 FROM document_tag link WHERE link.document_id = d.id AND link.tag_id = ?)",
                    tag.id());
            active.add(new ActiveFilter(tag.name(), tag.color(), params.without("tag", String.valueOf(tag.id()))));
        }

        List<Integer> years = years(params);
        if (!years.isEmpty()) {
            selection = selection.and("EXTRACT(YEAR FROM d.document_date)::int = ANY(?)",
                    (Object) years.toArray(Integer[]::new));
        }
        for (int year : years) {
            active.add(new ActiveFilter(String.valueOf(year), null, params.without("year", String.valueOf(year))));
        }

        String addedBy = params.first(ADDED_BY);
        if (!addedBy.isEmpty()) {
            boolean unknown = addedBy.equals(NONE);
            selection = selection.and("d.added_by = ?", unknown ? "" : addedBy);
            active.add(new ActiveFilter(unknown ? UNKNOWN_ADDER : addedBy, null, params.without(ADDED_BY)));
        }
        return new Applied(selection, active);
    }

    private Selection single(QueryParams params, MasterKind kind, String column, String noneLabel, Selection current,
            List<ActiveFilter> active) {
        Selection selection = current;
        String name = kind.filterName();
        List<MasterItem> items = master.withIds(kind, ids(params, name).toArray(Long[]::new));
        if (!items.isEmpty()) {
            selection = selection.and(column + " = ANY(?)",
                    (Object) items.stream().map(MasterItem::id).toArray(Long[]::new));
        }
        for (MasterItem item : items) {
            active.add(new ActiveFilter(item.name(), null, params.without(name, String.valueOf(item.id()))));
        }
        if (params.values(name).contains(NONE)) {
            selection = selection.and(column + " IS NULL");
            active.add(new ActiveFilter(noneLabel, null, params.without(name, NONE)));
        }
        return selection;
    }

    /** Kategorie samt allen Unterkategorien. Mehrere gewählte Kategorien gelten zusammen. */
    private Selection categories(QueryParams params, Selection current, List<ActiveFilter> active) {
        Selection selection = current;
        String name = MasterKind.CATEGORY.filterName();
        CategoryPaths paths = new CategoryPaths(master.categories());
        List<Long> chosen = ids(params, name).stream().filter(paths::contains).toList();
        if (!chosen.isEmpty()) {
            Long[] all = chosen.stream().flatMap(id -> paths.withDescendants(id).stream()).distinct()
                    .toArray(Long[]::new);
            selection = selection.and(CATEGORY_OF_DOCUMENT + " = ANY(?)", (Object) all);
        }
        for (long id : chosen) {
            active.add(new ActiveFilter(paths.label(id), null, params.without(name, String.valueOf(id))));
        }
        if (params.values(name).contains(NONE)) {
            selection = selection.and(CATEGORY_OF_DOCUMENT + " IS NULL");
            active.add(new ActiveFilter(NO_CATEGORY, null, params.without(name, NONE)));
        }
        // Nur genau diese Stufe, für die Ordneransicht.
        String exact = params.first(CATEGORY_ONLY);
        if (exact.equals(NONE)) {
            selection = selection.and(CATEGORY_OF_DOCUMENT + " IS NULL");
            active.add(new ActiveFilter(NO_CATEGORY, null, params.without(CATEGORY_ONLY)));
        } else if (exact.matches("\\d{1," + MAX_ID_DIGITS + "}") && paths.contains(Long.valueOf(exact))) {
            selection = selection.and(CATEGORY_OF_DOCUMENT + " = ?", Long.valueOf(exact));
            active.add(new ActiveFilter(paths.label(Long.valueOf(exact)), null, params.without(CATEGORY_ONLY)));
        }
        return selection;
    }

    /** Jede Kategorie zählt auch die Dokumente ihrer Unterkategorien. */
    private List<Option> categoryOptions(QueryParams params, Selection selection) {
        String name = MasterKind.CATEGORY.filterName();
        CategoryPaths paths = new CategoryPaths(master.categories());
        Map<Long, Long> totals = paths.totals(documents.countsBy(CATEGORY_OF_DOCUMENT, selection));
        Set<Long> selected = new HashSet<>(ids(params, name));
        List<Option> options = new ArrayList<>();
        for (Map.Entry<Long, String> entry : paths.ordered()) {
            long count = totals.getOrDefault(entry.getKey(), 0L);
            if (count > 0 || selected.contains(entry.getKey())) {
                options.add(option(params, name, String.valueOf(entry.getKey()), entry.getValue(), count, null));
            }
        }
        return options;
    }

    /** Ordnerbaum nach Kategorie, wie die Ablage auf dem NAS. Eine Abfrage für alle Ordner. */
    public List<TreeNode> tree() {
        return tree(TreeOrder.CATEGORY);
    }

    /**
     * Ordnerbaum in der gewählten Gliederung. Nach Kategorie folgen die Stufen der Kategorie, jede mit ihren
     * eigenen Dokumenten. Die beiden anderen Gliederungen fassen Dokumenttyp und Absendername über alle
     * Kategorien zusammen.
     */
    public List<TreeNode> tree(TreeOrder order) {
        List<TreeRow> rows = documents.treeRows();
        return switch (order) {
            case CATEGORY -> categoryTree(rows);
            case TYPE -> nameTree(rows, order, TreeRow::typeId, TreeRow::typeName, "Ohne Dokumenttyp", "type",
                    TreeRow::correspondentId, TreeRow::correspondentName, "Ohne Absendername", "correspondent");
            case CORRESPONDENT -> nameTree(rows, order, TreeRow::correspondentId, TreeRow::correspondentName,
                    "Ohne Absendername", "correspondent", TreeRow::typeId, TreeRow::typeName, "Ohne Dokumenttyp",
                    "type");
        };
    }

    private List<TreeNode> categoryTree(List<TreeRow> rows) {
        CategoryPaths paths = new CategoryPaths(master.categories());
        Map<Long, Long> own = new HashMap<>();
        long loose = 0;
        for (TreeRow row : rows) {
            if (paths.contains(row.categoryId())) {
                own.merge(row.categoryId(), row.count(), Long::sum);
            } else {
                loose += row.count();
            }
        }
        List<TreeNode> tree = new ArrayList<>();
        for (long root : paths.roots()) {
            TreeNode node = categoryNode(root, paths, own);
            if (node.count() > 0) {
                tree.add(node);
            }
        }
        if (loose > 0) {
            tree.add(new TreeNode(NO_CATEGORY, loose, prefix(TreeOrder.CATEGORY) + NONE, true, List.of(),
                    CATEGORY_ONLY + "=" + NONE));
        }
        return tree;
    }

    private TreeNode categoryNode(long id, CategoryPaths paths, Map<Long, Long> own) {
        List<TreeNode> children = new ArrayList<>();
        for (long child : paths.children(id)) {
            TreeNode node = categoryNode(child, paths, own);
            if (node.count() > 0) {
                children.add(node);
            }
        }
        long documentsHere = own.getOrDefault(id, 0L);
        long count = documentsHere + children.stream().mapToLong(TreeNode::count).sum();
        return new TreeNode(paths.name(id), count, prefix(TreeOrder.CATEGORY) + id, true, children,
                documentsHere > 0 ? CATEGORY_ONLY + "=" + id : null);
    }

    private static String prefix(TreeOrder order) {
        return order.value() + "/";
    }

    /** Zwei Ebenen aus Stammdaten, etwa Dokumenttyp und darunter Absendername. Fehlende Namen stehen zuletzt. */
    private static List<TreeNode> nameTree(List<TreeRow> rows, TreeOrder order,
            Function<TreeRow, Long> outerId, Function<TreeRow, String> outerName, String outerMissing, String outerFilter,
            Function<TreeRow, Long> innerId, Function<TreeRow, String> innerName, String innerMissing,
            String innerFilter) {
        Map<String, String> outerNames = new HashMap<>();
        Map<String, Map<String, Long>> counts = new HashMap<>();
        Map<String, String> innerNames = new HashMap<>();
        for (TreeRow row : rows) {
            String outer = key(outerId.apply(row));
            String inner = key(innerId.apply(row));
            outerNames.putIfAbsent(outer, name(outerName.apply(row), outerMissing));
            innerNames.putIfAbsent(inner, name(innerName.apply(row), innerMissing));
            counts.computeIfAbsent(outer, ignored -> new HashMap<>()).merge(inner, row.count(), Long::sum);
        }
        List<TreeNode> tree = new ArrayList<>();
        for (String outer : sorted(outerNames)) {
            List<TreeNode> children = new ArrayList<>();
            Map<String, Long> inner = counts.get(outer);
            for (String key : sorted(innerNames.entrySet().stream().filter(entry -> inner.containsKey(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))) {
                children.add(new TreeNode(innerNames.get(key), inner.get(key), prefix(order) + outer + "/" + key, false,
                        List.of(), outerFilter + "=" + outer + "&" + innerFilter + "=" + key));
            }
            tree.add(new TreeNode(outerNames.get(outer), children.stream().mapToLong(TreeNode::count).sum(),
                    prefix(order) + outer, false, children, null));
        }
        return tree;
    }

    private static String key(Long id) {
        return id == null ? NONE : id.toString();
    }

    private static String name(String name, String missing) {
        return name == null ? missing : name;
    }

    /** Nach Namen in deutscher Sortierung, Ä bei A, ohne Groß- und Kleinschreibung. „Ohne …“ steht zuletzt. */
    private static List<String> sorted(Map<String, String> names) {
        Collator german = Collator.getInstance(Locale.GERMAN);
        german.setStrength(Collator.SECONDARY);
        return names.keySet().stream()
                .sorted(Comparator.comparing((String key) -> key.equals(NONE)).thenComparing(names::get, german))
                .toList();
    }

    private static Option option(QueryParams params, String name, String value, String label, long count,
            String color) {
        boolean selected = params.values(name).contains(value);
        return new Option(label, count, color, selected,
                selected ? params.without(name, value) : params.with(name, value));
    }

    /** Aktive Werte bleiben auch ohne Treffer sichtbar, damit man sie abwählen kann. */
    private List<Option> modelOptions(QueryParams params, MasterKind kind, Map<Long, Long> counts) {
        Set<Long> visible = new LinkedHashSet<>(counts.keySet());
        visible.addAll(ids(params, kind.filterName()));
        return master.withIds(kind, visible.toArray(Long[]::new)).stream()
                .map(item -> option(params, kind.filterName(), String.valueOf(item.id()), item.name(),
                        counts.getOrDefault(item.id(), 0L), item.color()))
                .toList();
    }

    /** Filtergruppen mit Trefferzahlen innerhalb der aktuellen Auswahl. */
    public List<Facet> facets(QueryParams params, Selection selection) {
        List<Option> yearOptions = new ArrayList<>();
        documents.yearCounts(selection).forEach((year, count) -> yearOptions
                .add(option(params, "year", year.toString(), year.toString(), count, null)));
        List<Option> adderOptions = new ArrayList<>();
        documents.addedByCounts(selection).forEach((name, count) -> adderOptions.add(option(params, ADDED_BY,
                name.isEmpty() ? NONE : name, name.isEmpty() ? UNKNOWN_ADDER : name, count, null)));
        return List.of(
                new Facet("Absendername", modelOptions(params, MasterKind.CORRESPONDENT,
                        documents.countsBy("d.correspondent_id", selection))),
                new Facet("Dokumenttyp", modelOptions(params, MasterKind.DOCUMENT_TYPE,
                        documents.countsBy("d.document_type_id", selection))),
                new Facet("Tags", modelOptions(params, MasterKind.TAG, documents.tagCounts(selection))),
                new Facet("Jahr", yearOptions),
                new Facet("Eingespielt von", adderOptions),
                new Facet("Kategorie", categoryOptions(params, selection)));
    }

    /** Baut einen Listenlink für die Übersicht. */
    public static String listLink(String name, String value) {
        return "/dokumente?" + name + "=" + UriUtils.encode(value, StandardCharsets.UTF_8);
    }

    public static String addedByLink(String value) {
        return listLink(ADDED_BY, value);
    }
}
