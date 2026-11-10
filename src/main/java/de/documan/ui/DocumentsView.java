package de.documan.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Article;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Nav;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;

import de.documan.domain.ActivityKind;
import de.documan.domain.Document;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.ActivityRepository;
import de.documan.repo.DocumentRepository;
import de.documan.repo.MasterRepository;
import de.documan.repo.Selection;
import de.documan.service.CategoryPaths;
import de.documan.service.DocumentService;
import de.documan.service.Filters;
import de.documan.service.Filters.Applied;
import de.documan.service.Filters.Facet;
import de.documan.service.QueryParams;
import de.documan.service.SearchService;
import de.documan.web.Fmt;
import jakarta.annotation.security.PermitAll;

/** Alle Dokumente, der Eingang und die Suche: Filter, Sortierung, Raster oder Liste, Stapelbearbeitung. */
@Route(value = "dokumente", layout = MainLayout.class)
@PermitAll
public class DocumentsView extends Div implements BeforeEnterObserver, HasDynamicTitle, Refreshable {

    private static final int PAGE_SIZE = 48;
    private static final String LAYOUT = "documan.layout";

    private record Sort(String label, String order) {
    }

    private static final Map<String, Sort> SORTS = sorts();

    private static Map<String, Sort> sorts() {
        Map<String, Sort> sorts = new LinkedHashMap<>();
        sorts.put("date", new Sort("Dokumentdatum", "d.document_date DESC NULLS LAST, d.added DESC, d.id DESC"));
        sorts.put("added", new Sort("Zuletzt hinzugefügt", "d.added DESC, d.id DESC"));
        sorts.put("title", new Sort("Titel", "d.title, d.id"));
        sorts.put("correspondent", new Sort("Absendername", "c.name ASC NULLS LAST, d.title, d.id"));
        return sorts;
    }

    private record Action(String key, String label, String placeholder, boolean needsValue, boolean required) {
    }

    private final DocumentRepository documents;
    private final DocumentService service;
    private final MasterRepository master;
    private final Filters filters;
    private final SearchService search;
    private final ActivityRepository activities;
    private final Fmt fmt;
    private final boolean admin;
    private final String username;

    private QueryParams params = new QueryParams(Map.of());
    private String query = "";
    private final Set<Long> selected = new LinkedHashSet<>();
    private final List<Checkbox> boxes = new ArrayList<>();
    private Section docs;
    private Div bulk;
    private Span bulkCount;
    private ComboBox<String> bulkValue;
    private Select<Action> bulkAction;

    public DocumentsView(DocumentRepository documents, DocumentService service, MasterRepository master,
            Filters filters, SearchService search, ActivityRepository activities, Fmt fmt) {
        this.documents = documents;
        this.service = service;
        this.master = master;
        this.filters = filters;
        this.search = search;
        this.activities = activities;
        this.fmt = fmt;
        this.admin = Ui.isAdmin();
        this.username = Ui.username();
        setId("results");
    }

    @Override
    public String getPageTitle() {
        return (query.isEmpty() ? "Dokumente" : query) + " · DocuMan";
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        params = toParams(event.getLocation().getQueryParameters());
        selected.clear();
        render();
    }

    private static QueryParams toParams(QueryParameters parameters) {
        Map<String, String[]> map = new LinkedHashMap<>();
        parameters.getParameters().forEach((name, values) -> map.put(name, values.toArray(String[]::new)));
        return new QueryParams(map);
    }

    /** Suche beim Tippen: Die Adresse ändert sich mit, ohne neuen Eintrag im Verlauf. */
    void search(String text) {
        Map<String, String[]> map = new LinkedHashMap<>();
        params.except("q", "page").forEach((name, values) -> map.put(name, values.toArray(String[]::new)));
        if (!text.isBlank()) {
            map.put("q", new String[] {text.strip()});
        }
        params = new QueryParams(map);
        selected.clear();
        render();
        String encoded = params.encoded();
        UI.getCurrent().getPage().getHistory().replaceState(null,
                new Location("dokumente" + (encoded.isEmpty() ? "" : "?" + encoded)));
        UI.getCurrent().getPage().setTitle(getPageTitle());
    }

    @Override
    public void refresh() {
        render();
    }

    @Override
    public boolean busy() {
        return !selected.isEmpty();
    }

    private void render() {
        removeAll();
        boxes.clear();
        Applied applied = filters.apply(params);
        query = params.first("q").strip();
        MainLayout.current().ifPresent(layout -> layout.showQuery(query));

        Selection selection = applied.selection();
        String order = null;
        List<Object> orderParams = List.of();
        if (!query.isEmpty()) {
            var outcome = search.search(selection, query);
            selection = outcome.selection();
            order = outcome.order();
            orderParams = outcome.orderParams();
        }
        Sort sort = SORTS.get(params.first("sort"));
        // Suchergebnisse bleiben nach Relevanz sortiert, solange niemand anders wählt.
        if (sort != null || order == null) {
            order = (sort != null ? sort : SORTS.get("date")).order();
            orderParams = List.of();
        }

        long count = documents.count(selection);
        int pages = Math.max(1, (int) Math.ceilDiv(count, (long) PAGE_SIZE));
        String pageValue = params.first("page");
        int page = Math.max(1, Math.min(pageValue.matches("\\d{1,9}") ? Integer.parseInt(pageValue) : 1, pages));
        List<Document> list = documents.page(selection, order, orderParams, PAGE_SIZE, (page - 1) * PAGE_SIZE);
        if (!query.isEmpty()) {
            list = search.addSnippets(list, query);
        }
        String sortLabel = sort != null ? sort.label() : (query.isEmpty() ? SORTS.get("date").label() : "Relevanz");

        add(filterBar(filters.facets(params, selection), sortLabel, sort));
        if (!applied.active().isEmpty()) {
            Div chips = Ui.div("active-filters");
            applied.active().forEach(filter -> {
                Anchor chip = Ui.link("dokumente" + filter.remove(), dot(filter.color()), new Span(filter.label()),
                        Ui.icon("close"));
                chip.setClassName("chip");
                chip.getElement().setAttribute("aria-label", "Filter " + filter.label() + " entfernen");
                chips.add(chip);
            });
            add(chips);
        }
        if (!query.isEmpty() || !applied.active().isEmpty()) {
            add(Ui.paragraph("result-line", count + " Treffer"));
        }

        String view = params.first("view");
        if (list.isEmpty()) {
            add(empty(view, applied));
            return;
        }
        docs = new Section();
        docs.setClassName("docs");
        docs.setId("docs");
        docs.getElement().setAttribute("aria-label", "Dokumente");
        if ("list".equals(VaadinSession.getCurrent().getAttribute(LAYOUT))) {
            docs.addClassName("as-list");
        }
        list.forEach(document -> docs.add(card(document)));
        add(docs);

        if (pages > 1) {
            Nav pagination = new Nav();
            pagination.setClassName("pagination");
            pagination.getElement().setAttribute("aria-label", "Seiten");
            String base = params.withoutPage();
            String prefix = "dokumente?" + (base.isEmpty() ? "" : base + "&") + "page=";
            if (page > 1) {
                pagination.add(buttonLink(prefix + (page - 1), "Zurück"));
            }
            pagination.add(new Span("Seite " + page + " von " + pages));
            if (page < pages) {
                pagination.add(buttonLink(prefix + (page + 1), "Weiter"));
            }
            add(pagination);
        }
        add(bulkBar());
    }

    private static Anchor buttonLink(String href, String text) {
        Anchor anchor = Ui.link(href, text);
        anchor.setClassName("button");
        return anchor;
    }

    private static Component dot(String color) {
        if (color == null) {
            return new Span();
        }
        Span dot = new Span();
        dot.setClassName("dot");
        dot.getStyle().set("--c", color);
        return dot;
    }

    private Div filterBar(List<Facet> facets, String sortLabel, Sort sort) {
        Div bar = Ui.div("filters");
        for (Facet facet : facets) {
            Div menu = Ui.div("dropdown-menu");
            menu.add(new H3(facet.label()));
            facet.options().forEach(option -> {
                Anchor link = Ui.link("dokumente" + option.url(), dot(option.color()), Ui.span("name", option.label()),
                        Ui.span("count", String.valueOf(option.count())));
                if (option.selected()) {
                    link.getElement().setAttribute("aria-current", "true");
                }
                menu.add(link);
            });
            if (facet.options().isEmpty()) {
                menu.add(Ui.div("empty", new Span("Keine Einträge")));
            }
            bar.add(dropdown(facet.label(), menu, PopoverPosition.BOTTOM_START));
        }

        Div sortMenu = Ui.div("dropdown-menu");
        sortMenu.add(new H3("Sortieren nach"));
        SORTS.forEach((key, option) -> {
            Anchor link = Ui.link("dokumente" + params.with("sort", key), Ui.span("name", option.label()));
            if (option == sort) {
                link.getElement().setAttribute("aria-current", "true");
            }
            sortMenu.add(link);
        });
        Div end = Ui.div("filters-end", dropdown(sortLabel, sortMenu, PopoverPosition.BOTTOM_END), layoutSwitch());
        bar.add(end);
        return bar;
    }

    /** Ein Filtermenü: Knopf mit Pfeil, darunter die Auswahl. */
    private static Div dropdown(String label, Div menu, PopoverPosition position) {
        Button summary = new Button(label, Ui.icon("chevron"));
        summary.setIconAfterText(true);
        summary.addClassName("dropdown-button");
        Popover popover = new Popover(menu);
        popover.setTarget(summary);
        popover.setPosition(position);
        popover.addClassName("dropdown-popover");
        menu.getChildren().filter(Anchor.class::isInstance)
                .forEach(link -> ((Anchor) link).getElement().addEventListener("click", click -> popover.close()));
        return Ui.div("dropdown", summary, popover);
    }

    private Div layoutSwitch() {
        boolean list = "list".equals(VaadinSession.getCurrent().getAttribute(LAYOUT));
        Button grid = new Button(Ui.icon("grid"));
        Button rows = new Button(Ui.icon("rows"));
        grid.setAriaLabel("Rasteransicht");
        rows.setAriaLabel("Listenansicht");
        grid.getElement().setAttribute("aria-pressed", String.valueOf(!list));
        rows.getElement().setAttribute("aria-pressed", String.valueOf(list));
        grid.addClickListener(click -> setLayout("grid", grid, rows));
        rows.addClickListener(click -> setLayout("list", grid, rows));
        Div segmented = Ui.div("segmented", grid, rows);
        segmented.getElement().setAttribute("role", "group");
        segmented.getElement().setAttribute("aria-label", "Darstellung");
        return segmented;
    }

    private void setLayout(String layout, Button grid, Button rows) {
        VaadinSession.getCurrent().setAttribute(LAYOUT, layout);
        grid.getElement().setAttribute("aria-pressed", String.valueOf(layout.equals("grid")));
        rows.getElement().setAttribute("aria-pressed", String.valueOf(layout.equals("list")));
        if (docs != null) {
            docs.setClassName("as-list", layout.equals("list"));
        }
    }

    private Article card(Document document) {
        Article card = new Article();
        card.setClassName("doc");

        Checkbox box = new Checkbox();
        box.setAriaLabel(document.title() + " auswählen");
        box.setValue(selected.contains(document.id()));
        box.addValueChangeListener(change -> {
            if (change.getValue()) {
                selected.add(document.id());
            } else {
                selected.remove(document.id());
            }
            updateBulk();
        });
        boxes.add(box);
        Div check = Ui.div("doc-check", box);
        card.add(check, cardLink(document, fmt));
        return card;
    }

    /** Vorschaubild, Titel, Absender, Datum und Tags eines Dokuments, verlinkt auf seine Seite. */
    static Anchor cardLink(Document document, Fmt fmt) {
        Div sheet = Ui.div("doc-sheet");
        if (document.hasThumbnail()) {
            Image thumbnail = new Image("dokumente/" + document.id() + "/bild?v=" + document.version(), "");
            thumbnail.getElement().setAttribute("loading", "lazy");
            sheet.add(thumbnail);
        } else {
            sheet.add(Ui.span("doc-kind", document.fileLabel()));
        }
        if (document.isFailed()) {
            sheet.add(Ui.span("doc-badge failed", "Fehler"));
        }
        if (!document.isFinished()) {
            sheet.add(Ui.span("doc-badge waiting", document.status().label()));
        }
        if (document.isDone() && document.inbox()) {
            Span fresh = Ui.span("doc-new", "");
            fresh.getElement().setAttribute("title", "Im Eingang");
            sheet.add(fresh);
        }

        H3 title = new H3(document.title());
        title.setClassName("doc-title");
        Paragraph meta = new Paragraph(Ui.span("doc-who", document.subtitle()), Ui.span("doc-date", fmt.date(document)));
        meta.setClassName("doc-meta");
        Div body = Ui.div("doc-body", title, meta);
        if (document.snippet() != null && !document.snippet().isEmpty()) {
            // Der Ausschnitt ist schon maskiert, nur die Treffer sind markiert.
            body.add(new Html("<p class=\"doc-snippet\">" + document.snippet() + "</p>"));
        }
        if (!document.tags().isEmpty()) {
            Div tags = Ui.div("doc-tags");
            for (MasterItem tag : document.tags()) {
                Span dot = new Span();
                dot.setClassName("dot");
                dot.getStyle().set("--c", tag.color());
                tags.add(new Span(dot, new Span(tag.name())));
            }
            body.add(tags);
        }
        Anchor link = Ui.link("dokumente/" + document.id(), sheet, body);
        link.setClassName("doc-link");
        return link;
    }

    private Div empty(String view, Applied applied) {
        boolean filtered = !query.isEmpty() || !applied.active().isEmpty();
        boolean emptyInbox = "inbox".equals(view) && query.isEmpty() && applied.active().size() == 1;
        if (emptyInbox) {
            return emptyState("inbox", "Der Eingang ist leer", "Alle Dokumente sind abgelegt.", null);
        }
        if (filtered) {
            return emptyState("search", "Nichts gefunden", "Versuchen Sie einen anderen Begriff oder entfernen Sie Filter.",
                    null);
        }
        Button upload = new Button("Dateien auswählen", click -> MainLayout.current().ifPresent(MainLayout::openUpload));
        upload.addThemeVariants(ButtonVariant.PRIMARY);
        return emptyState("file", "Noch keine Dokumente",
                "Laden Sie Dateien hoch, legen Sie sie in den Eingangsordner oder verbinden Sie ein Postfach.", upload);
    }

    static Div emptyState(String icon, String title, String text, Component action) {
        Div state = Ui.div("empty-state", Ui.icon(icon), new H2(title), new Paragraph(text));
        if (action != null) {
            state.add(action);
        }
        return state;
    }

    // Stapelbearbeitung

    private List<Action> actions() {
        List<Action> actions = new ArrayList<>(List.of(
                new Action("done", "Ablegen", "", false, false),
                new Action("correspondent", "Absendername setzen", "Absendername (leer = entfernen)", true, false),
                new Action("type", "Dokumenttyp setzen", "Dokumenttyp (leer = entfernen)", true, false),
                new Action("category", "Kategorie setzen", "Kategorie, etwa Finanzen / Bank (leer = wie Dokumenttyp)", true,
                        false),
                new Action("add_tag", "Tag hinzufügen", "Tag", true, true),
                new Action("remove_tag", "Tag entfernen", "Tag", true, true),
                new Action("reprocess", "Erneut verarbeiten", "", false, false),
                new Action("accept", "Fehlgeschlagene als Dokument übernehmen", "", false, false)));
        if (admin) {
            actions.add(new Action("delete", "Löschen", "", false, false));
        }
        return actions;
    }

    private Div bulkBar() {
        bulkCount = Ui.span("bulk-count", "");
        bulkCount.getElement().setAttribute("aria-live", "polite");
        Button clear = Ui.button("Aufheben", () -> {
            selected.clear();
            boxes.forEach(box -> box.setValue(false));
            updateBulk();
        });
        bulkAction = new Select<>();
        bulkAction.setAriaLabel("Aktion");
        bulkAction.setItems(actions());
        bulkAction.setItemLabelGenerator(Action::label);
        bulkAction.setValue(bulkAction.getListDataView().getItem(0));
        bulkValue = new ComboBox<>();
        bulkValue.setAriaLabel("Wert");
        bulkValue.setAllowCustomValue(true);
        bulkValue.addCustomValueSetListener(custom -> bulkValue.setValue(custom.getDetail()));
        bulkAction.addValueChangeListener(change -> updateValueField());
        Button apply = Ui.primary("Anwenden", this::applyBulk);
        bulk = Ui.div("bulk", bulkCount, clear, bulkAction, bulkValue, apply);
        updateValueField();
        updateBulk();
        return bulk;
    }

    private void updateValueField() {
        Action action = bulkAction.getValue();
        bulkValue.setVisible(action != null && action.needsValue());
        if (action == null || !action.needsValue()) {
            return;
        }
        bulkValue.clear();
        bulkValue.setPlaceholder(action.placeholder());
        bulkValue.setItems(switch (action.key()) {
            case "correspondent" -> names(MasterKind.CORRESPONDENT);
            case "type" -> names(MasterKind.DOCUMENT_TYPE);
            case "category" -> new CategoryPaths(master.categories()).ordered().stream().map(Map.Entry::getValue).toList();
            default -> names(MasterKind.TAG);
        });
    }

    private List<String> names(MasterKind kind) {
        return master.all(kind).stream().map(MasterItem::name).toList();
    }

    private void updateBulk() {
        if (bulk == null) {
            return;
        }
        bulk.setClassName("visible", !selected.isEmpty());
        if (docs != null) {
            docs.setClassName("selecting", !selected.isEmpty());
        }
        bulkCount.setText(selected.size() + " ausgewählt");
    }

    private void applyBulk() {
        Action action = bulkAction.getValue();
        if (action == null || selected.isEmpty()) {
            return;
        }
        String value = bulkValue.getValue() == null ? "" : bulkValue.getValue().strip();
        if (action.required() && value.isEmpty()) {
            Ui.warning("Bitte einen Tag angeben.");
            return;
        }
        if (action.key().equals("delete")) {
            Ui.confirm(selected.size() + " Dokument(e) endgültig löschen? Auch die Originale werden entfernt.",
                    "Löschen", () -> runBulk(action.key(), value));
            return;
        }
        runBulk(action.key(), value);
    }

    private void runBulk(String action, String value) {
        List<Long> ids = List.copyOf(selected);
        Long[] array = ids.toArray(Long[]::new);
        int count = ids.size();
        switch (action) {
            case "delete" -> {
                if (!admin) {
                    return;
                }
                int deleted = service.deleteAll(ids);
                activities.record(username, ActivityKind.DELETED, deleted);
                Ui.success(deleted + " Dokument(e) gelöscht.");
            }
            case "done" -> {
                int filed = documents.setFiled(array);
                activities.record(username, ActivityKind.FILED, filed);
                Ui.success(filed + " Dokument(e) abgelegt.");
            }
            case "reprocess" -> Ui.info(documents.requeue(array) + " Dokument(e) werden erneut verarbeitet.");
            case "correspondent" -> {
                service.setCorrespondent(ids, value);
                activities.record(username, ActivityKind.EDITED, count);
                Ui.success("Absendername für " + count + " Dokument(e) gesetzt.");
            }
            case "type" -> {
                service.setDocumentType(ids, value);
                activities.record(username, ActivityKind.EDITED, count);
                Ui.success("Dokumenttyp für " + count + " Dokument(e) gesetzt.");
            }
            case "category" -> {
                List<String> path = CategoryPaths.parse(value);
                if (path.size() > CategoryPaths.MAX_LEVELS) {
                    Ui.error("Höchstens " + CategoryPaths.MAX_LEVELS + " Stufen, getrennt durch /.");
                    return;
                }
                service.setCategory(array, master.categoryChain(path).orElse(null));
                activities.record(username, ActivityKind.EDITED, count);
                Ui.success(path.isEmpty()
                        ? "Eigene Kategorie bei " + count + " Dokument(en) entfernt, es gilt die des Dokumenttyps."
                        : "Kategorie „" + String.join(CategoryPaths.SEPARATOR, path) + "“ für " + count
                                + " Dokument(e) gesetzt.");
            }
            case "accept" -> {
                int accepted = documents.acceptFailed(array);
                activities.record(username, ActivityKind.FILED, accepted);
                Ui.success(accepted + " fehlgeschlagene(s) Dokument(e) übernommen.");
            }
            case "add_tag", "remove_tag" -> {
                MasterItem tag = action.equals("add_tag") ? service.addTag(ids, value) : service.removeTag(ids, value);
                activities.record(username, ActivityKind.EDITED, count);
                Ui.success("Tag „" + tag.name() + "“ bei " + count + " Dokument(e) geändert.");
            }
            default -> {
                return;
            }
        }
        selected.clear();
        MainLayout.current().ifPresentOrElse(MainLayout::refreshNow, this::render);
    }
}
