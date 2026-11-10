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
import com.vaadin.flow.component.html.NativeDetails;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;

import de.documan.domain.Document;
import de.documan.domain.MasterItem;
import de.documan.repo.DocumentRepository;
import de.documan.service.Filters;
import de.documan.service.Filters.CategoryNode;
import de.documan.service.Filters.Folder;
import de.documan.service.Filters.TreeNode;
import de.documan.service.QueryParams;
import de.documan.web.Fmt;
import jakarta.annotation.security.PermitAll;

/** Alle Dokumente als Baum: Kategorie, Unterkategorien, Dokumenttyp, Absendername. */
@Route(value = "ordner", layout = MainLayout.class)
@PageTitle("Ordner · DocuMan")
@PermitAll
public class TreeView extends Div implements Refreshable {

    private static final int FOLDER_LIMIT = 200;
    private static final String DATE_ORDER = "d.document_date DESC NULLS LAST, d.added DESC, d.id DESC";
    // Aufgeklappte Ordner bleiben offen, auch nach dem Aktualisieren und beim nächsten Besuch.
    private static final String OPEN = "documan.treeOpen";

    private final Filters filters;
    private final DocumentRepository documents;
    private final Fmt fmt;
    private final Div tree = Ui.div("tree");

    public TreeView(Filters filters, DocumentRepository documents, Fmt fmt) {
        this.filters = filters;
        this.documents = documents;
        this.fmt = fmt;
        Header head = new Header(new Div(new H1("Ordner"), new Paragraph("Kategorie / Dokumenttyp / Absendername")));
        head.setClassName("page-head");
        add(head, tree);
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
        List<CategoryNode> nodes = filters.tree();
        if (nodes.isEmpty()) {
            tree.add(DocumentsView.emptyState("folder", "Noch keine Dokumente",
                    "Sobald Dokumente da sind, erscheinen sie hier nach Kategorie, Typ und Absendername sortiert.", null));
            return;
        }
        nodes.forEach(node -> tree.add(category(node)));
    }

    private NativeDetails category(CategoryNode node) {
        Div children = Ui.div("tree-children");
        node.children().forEach(child -> children.add(category(child)));
        node.types().forEach(type -> children.add(type(type)));
        return details("tree-category", node.key(), summary("category", node.name(), node.count()), children, null);
    }

    private NativeDetails type(TreeNode node) {
        Div children = Ui.div("tree-children");
        node.folders().forEach(folder -> children.add(folder(folder)));
        return details("tree-type", node.key(), summary("folder", node.name(), node.count()), children, null);
    }

    private NativeDetails folder(Folder folder) {
        Div content = Ui.div("");
        content.add(Ui.paragraph("tree-note", "Wird geladen …"));
        return details("tree-folder", folder.key(), summary(null, folder.name(), folder.count()), content,
                () -> fill(content, folder.query()));
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
