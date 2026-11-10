package de.documan.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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

    public record Folder(String name, long count, String key, String query) {
    }

    /** Eine Kategoriestufe der Ordneransicht mit Unterkategorien und den Dokumenttypen dieser Stufe. */
    public record CategoryNode(String name, long count, String key, List<CategoryNode> children,
            List<TreeNode> types) {
    }

    public record TreeNode(String name, long count, String key, List<Folder> folders) {
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

    /** Ordnerbaum Kategorie → Unterkategorien → Dokumenttyp → Absendername. Eine Abfrage für alle Ordner. */
    public List<CategoryNode> tree() {
        CategoryPaths paths = new CategoryPaths(master.categories());
        Map<Long, List<TreeRow>> byCategory = new LinkedHashMap<>();
        for (TreeRow row : documents.treeRows()) {
            Long category = paths.contains(row.categoryId()) ? row.categoryId() : null;
            byCategory.computeIfAbsent(category, key -> new ArrayList<>()).add(row);
        }
        List<CategoryNode> tree = new ArrayList<>();
        for (long root : paths.roots()) {
            CategoryNode node = categoryNode(root, paths, byCategory);
            if (node.count() > 0) {
                tree.add(node);
            }
        }
        List<TreeRow> loose = byCategory.getOrDefault(null, List.of());
        if (!loose.isEmpty()) {
            String key = "category-" + NONE;
            List<TreeNode> types = typeNodes(loose, key, NONE);
            tree.add(new CategoryNode(NO_CATEGORY, types.stream().mapToLong(TreeNode::count).sum(), key, List.of(),
                    types));
        }
        return tree;
    }

    private CategoryNode categoryNode(long id, CategoryPaths paths, Map<Long, List<TreeRow>> byCategory) {
        String key = "category-" + id;
        List<CategoryNode> children = new ArrayList<>();
        for (long child : paths.children(id)) {
            CategoryNode node = categoryNode(child, paths, byCategory);
            if (node.count() > 0) {
                children.add(node);
            }
        }
        List<TreeNode> types = typeNodes(byCategory.getOrDefault(id, List.of()), key, String.valueOf(id));
        long count = children.stream().mapToLong(CategoryNode::count).sum()
                + types.stream().mapToLong(TreeNode::count).sum();
        return new CategoryNode(paths.name(id), count, key, children, types);
    }

    /** Die Zeilen einer Kategoriestufe, nach Typ und Absendername sortiert. */
    private static List<TreeNode> typeNodes(List<TreeRow> rows, String categoryKey, String categoryValue) {
        Map<String, List<TreeRow>> byType = new LinkedHashMap<>();
        for (TreeRow row : rows) {
            byType.computeIfAbsent(row.typeId() == null ? NONE : row.typeId().toString(), key -> new ArrayList<>())
                    .add(row);
        }
        List<TreeNode> types = new ArrayList<>();
        byType.forEach((typeKey, typeRows) -> {
            List<Folder> folders = new ArrayList<>();
            for (TreeRow row : typeRows) {
                String correspondent = row.correspondentId() == null ? NONE : row.correspondentId().toString();
                String name = row.correspondentName() == null ? "Ohne Absendername" : row.correspondentName();
                folders.add(new Folder(name, row.count(), categoryKey + "/" + typeKey + "/" + correspondent,
                        CATEGORY_ONLY + "=" + categoryValue + "&type=" + typeKey + "&correspondent=" + correspondent));
            }
            String typeName = typeRows.getFirst().typeName();
            types.add(new TreeNode(typeName == null ? "Ohne Dokumenttyp" : typeName,
                    folders.stream().mapToLong(Folder::count).sum(), categoryKey + "/" + typeKey, folders));
        });
        return types;
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
