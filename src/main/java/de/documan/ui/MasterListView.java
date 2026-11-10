package de.documan.ui;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;

import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.MasterRepository;
import de.documan.service.CategoryPaths;
import de.documan.service.DocumentService;
import jakarta.annotation.security.PermitAll;

/** Kategorien, Dokumenttypen, Absendernamen oder Tags als Tabelle. */
@Route(value = "stammdaten/:slug([a-z]+)", layout = MainLayout.class)
@PermitAll
public class MasterListView extends Div implements BeforeEnterObserver, HasDynamicTitle, Refreshable {

    private final MasterRepository master;
    private final DocumentService documents;
    private final boolean admin;
    private MasterKind kind;

    public MasterListView(MasterRepository master, DocumentService documents) {
        this.master = master;
        this.documents = documents;
        this.admin = Ui.isAdmin();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var found = MasterKind.bySlug(event.getRouteParameters().get("slug").orElse(""));
        if (found.isEmpty()) {
            event.forwardTo(DashboardView.class);
            return;
        }
        kind = found.get();
        refresh();
    }

    @Override
    public String getPageTitle() {
        return (kind == null ? "Stammdaten" : kind.plural()) + " · DocuMan";
    }

    @Override
    public void refresh() {
        removeAll();
        Anchor create = Ui.link("stammdaten/" + kind.slug() + "/neu", "Neu anlegen");
        create.setClassName("button primary");
        Header head = new Header(new Div(new H1(kind.plural()), new Paragraph(kind.hasRules()
                ? "Mit Suchbegriffen werden neue Dokumente automatisch zugeordnet."
                : "Kategorien ordnen Dokumente in bis zu zehn Stufen und bilden die obersten Ordnerebenen der Ablage. "
                        + "Ein Dokument bekommt sie über seinen Dokumenttyp, über den Unterordner im Eingangsordner oder "
                        + "direkt beim Dokument.")), create);
        head.setClassName("page-head");
        add(head);

        List<MasterItem> items = master.all(kind);
        if (items.isEmpty()) {
            add(DocumentsView.emptyState("layers", "Noch keine " + kind.plural(), kind.hasRules()
                    ? "Legen Sie Einträge hier an oder tippen Sie sie direkt beim Dokument ein."
                    : "Legen Sie Kategorien hier an. Unterordner im Eingangsordner werden beim Einlesen automatisch "
                            + "zu Kategorien.", null));
            return;
        }

        CategoryPaths paths = new CategoryPaths(master.categories());
        Map<Long, String> groups = new LinkedHashMap<>();
        Map<Long, String> labels = new LinkedHashMap<>();
        if (kind == MasterKind.DOCUMENT_TYPE) {
            // Dokumenttypen zeigen ihre Kategorie mit allen Stufen.
            master.typeCategories().forEach((type, category) -> groups.put(type, paths.label(category)));
        } else if (kind == MasterKind.CATEGORY) {
            // Kategorien stehen in Baumreihenfolge mit ihrem Pfad und zeigen ihre Dokumenttypen.
            master.categoryTypes().forEach((id, names) -> groups.put(id, String.join(", ", names)));
            List<Long> order = paths.ordered().stream().map(Map.Entry::getKey).toList();
            items = items.stream().sorted(Comparator.comparingInt(item -> order.indexOf(item.id()))).toList();
            items.forEach(item -> labels.put(item.id(), paths.label(item.id())));
        }

        Grid<MasterItem> grid = new Grid<>();
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_WRAP_CELL_CONTENT);
        grid.setAllRowsVisible(true);
        grid.addClassName("master-grid");
        grid.addComponentColumn(item -> {
            Anchor link = Ui.link("stammdaten/" + kind.slug() + "/" + item.id(),
                    new Span(labels.getOrDefault(item.id(), item.name())));
            link.setClassName("name-link");
            if (kind.hasColor()) {
                Span dot = new Span();
                dot.setClassName("dot");
                dot.getStyle().set("--c", item.color());
                link.addComponentAsFirst(dot);
            }
            return link;
        }).setHeader("Name").setFlexGrow(3);
        if (kind == MasterKind.DOCUMENT_TYPE) {
            grid.addColumn(item -> groups.getOrDefault(item.id(), "Keine")).setHeader("Kategorie").setFlexGrow(2);
        } else if (kind == MasterKind.CATEGORY) {
            grid.addColumn(item -> groups.getOrDefault(item.id(), "Keine")).setHeader("Dokumenttypen").setFlexGrow(2);
        }
        if (kind.hasRules()) {
            grid.addColumn(MasterItem::ruleLabel).setHeader("Automatische Zuordnung").setFlexGrow(2);
        }
        grid.addComponentColumn(item -> Ui.link("dokumente?" + kind.filterName() + "=" + item.id(),
                String.valueOf(item.documentCount()))).setHeader("Dokumente").setAutoWidth(true).setFlexGrow(0);
        if (admin) {
            grid.addComponentColumn(item -> {
                Button delete = new Button("Löschen", click -> Ui.confirm("„" + item.name()
                        + "“ löschen? Die Dokumente bleiben erhalten.", "Löschen", () -> delete(item)));
                delete.addThemeVariants(ButtonVariant.ERROR, ButtonVariant.SMALL, ButtonVariant.TERTIARY);
                return delete;
            }).setAutoWidth(true).setFlexGrow(0);
        }
        grid.setItems(items);
        add(grid);
    }

    private void delete(MasterItem item) {
        if (!admin) {
            return;
        }
        documents.deleteMaster(kind, item.id());
        String moved = kind == MasterKind.CATEGORY ? " Unterkategorien und Dokumente rücken eine Stufe nach oben." : "";
        Ui.success("„" + item.name() + "“ wurde gelöscht. Die Dokumente bleiben erhalten." + moved);
        refresh();
        MainLayout.current().ifPresent(MainLayout::refreshNow);
    }

    static void open(MasterKind kind) {
        UI.getCurrent().navigate("stammdaten/" + kind.slug());
    }
}
