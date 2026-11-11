package de.documan.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Nav;
import com.vaadin.flow.component.html.NativeDetails;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;

import de.documan.domain.Document;
import de.documan.domain.MasterItem;
import de.documan.repo.DocumentRepository;
import de.documan.service.Filters;
import de.documan.service.Filters.TreeNode;
import de.documan.service.Filters.TreeOrder;
import de.documan.service.QueryParams;
import de.documan.web.Fmt;
import jakarta.annotation.security.PermitAll;

/** Alle Dokumente als Baum: nach Kategorie wie die Ablage, nach Dokumenttyp oder nach Absendername. */
@Route(value = "ordner", layout = MainLayout.class)
@PageTitle("Ordner · DocuMan")
@PermitAll
public class TreeView extends Div implements BeforeEnterObserver, Refreshable {

    private static final int FOLDER_LIMIT = 200;
    private static final String DATE_ORDER = "d.document_date DESC NULLS LAST, d.added DESC, d.id DESC";
    // Aufgeklappte Ordner bleiben offen, auch nach dem Aktualisieren und beim nächsten Besuch.
    private static final String OPEN = "documan.treeOpen";

    private final Filters filters;
    private final DocumentRepository documents;
    private final Fmt fmt;
    private final Div tree = Ui.div("tree");
    private final Paragraph label = new Paragraph();
    private final Nav orders = new Nav();
    private TreeOrder order = TreeOrder.CATEGORY;

    public TreeView(Filters filters, DocumentRepository documents, Fmt fmt) {
        this.filters = filters;
        this.documents = documents;
        this.fmt = fmt;
        orders.setClassName("segmented tree-orders");
        orders.getElement().setAttribute("aria-label", "Gliederung");
        Header head = new Header(new Div(new H1("Ordner"), label), orders);
        head.setClassName("page-head");
        add(head, tree);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        order = TreeOrder.of(event.getLocation().getQueryParameters().getSingleParameter("nach").orElse(""));
        label.setText(order.label());
        orders.removeAll();
        for (TreeOrder choice : TreeOrder.values()) {
            Anchor link = Ui.link("ordner?nach=" + choice.value(), choice.shortLabel());
            if (choice == order) {
                link.getElement().setAttribute("aria-current", "page");
            }
            orders.add(link);
        }
        refresh();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> openKeys() {
        VaadinSession session = VaadinSession.getCurrent();
        Set<String> keys = (Set<String>) session.getAttribute(OPEN);
        if (keys == null) {
            keys = new HashSet<>();
            session.setAttribute(OPEN, keys);
        }
        return keys;
    }

    @Override
    public void refresh() {
        tree.removeAll();
        List<TreeNode> nodes = filters.tree(order);
        if (nodes.isEmpty()) {
            tree.add(DocumentsView.emptyState("folder", "Noch keine Dokumente",
                    "Sobald Dokumente da sind, erscheinen sie hier nach Kategorie, Dokumenttyp oder Absendername sortiert.", null));
            return;
        }
        nodes.forEach(node -> tree.add(node(node)));
    }

    /** Ein Ordner mit seinen Unterordnern. Eigene Dokumente lädt er erst beim Aufklappen. */
    private NativeDetails node(TreeNode node) {
        Div children = Ui.div("tree-children");
        node.children().forEach(child -> children.add(node(child)));
        Runnable load = null;
        if (node.query() != null) {
            Div content = Ui.div("");
            content.add(Ui.paragraph("tree-note", "Wird geladen …"));
            children.add(content);
            load = () -> fill(content, node.query());
        }
        return details(node.category() ? "tree-category" : "tree-type", node.key(),
                summary(node.category() ? "category" : "folder", node.name(), node.count()), children, load);
    }

    private static Component[] summary(String icon, String name, long count) {
        return icon == null
                ? new Component[] {Ui.icon("chevron", "icon chevron"), Ui.span("tree-name", name),
                        Ui.span("tree-count", String.valueOf(count))}
                : new Component[] {Ui.icon("chevron", "icon chevron"), Ui.icon(icon), Ui.span("tree-name", name),
                        Ui.span("tree-count", String.valueOf(count))};
    }

    /** Aufklappbarer Eintrag. load füllt den Inhalt erst, wenn jemand ihn öffnet. */
    private NativeDetails details(String classes, String key, Component[] summary, Div content, Runnable load) {
        NativeDetails details = new NativeDetails();
        details.setClassName(classes);
        details.setSummary(summary);
        details.setContent(content);
        boolean open = openKeys().contains(key);
        details.setOpen(open);
        if (open && load != null) {
            load.run();
        }
        details.addToggleListener(toggle -> {
            if (toggle.isOpened()) {
                openKeys().add(key);
                if (load != null) {
                    load.run();
                }
            } else {
                openKeys().remove(key);
            }
        });
        return details;
    }

    /** Inhalt eines Ordners, höchstens 200 Dokumente, der Rest über die Dokumentliste. */
    private void fill(Div content, String query) {
        Map<String, String[]> map = new java.util.LinkedHashMap<>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                String name = java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8);
                String value = java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8);
                String[] previous = map.getOrDefault(name, new String[0]);
                String[] values = java.util.Arrays.copyOf(previous, previous.length + 1);
                values[previous.length] = value;
                map.put(name, values);
            }
        }
        QueryParams params = new QueryParams(map);
        var selection = filters.apply(params).selection();
        List<Document> list = documents.page(selection, DATE_ORDER, List.of(), FOLDER_LIMIT, 0);
        long count = documents.count(selection);
        Div items = Ui.div("tree-list");
        for (Document document : list) {
            Span dots = Ui.span("tree-doc-tags", "");
            for (MasterItem tag : document.tags()) {
                Span dot = new Span();
                dot.setClassName("dot");
                dot.getStyle().set("--c", tag.color());
                dot.getElement().setAttribute("title", tag.name());
                dots.add(dot);
            }
            Anchor link = Ui.link("dokumente/" + document.id(), Ui.span("tree-doc-title", document.title()), dots,
                    new Span(fmt.date(document)));
            link.setClassName("tree-doc");
            items.add(link);
        }
        if (count > FOLDER_LIMIT) {
            Paragraph more = new Paragraph(Ui.link("dokumente?" + params.encoded(), "Alle " + count + " Dokumente anzeigen"));
            more.setClassName("tree-note");
            items.add(more);
        }
        content.removeAll();
        content.add(items);
    }
}
