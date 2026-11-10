package de.documan.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Aside;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.html.Nav;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.spring.security.AuthenticationContext;

import de.documan.config.DocuManProperties;
import de.documan.repo.BackupRepository;
import de.documan.repo.DocumentRepository;
import de.documan.repo.DocumentRepository.NavigationCounts;
import de.documan.service.IngestService;
import de.documan.repo.ActivityRepository;
import jakarta.annotation.security.PermitAll;

/** Rahmen aller angemeldeten Seiten: Seitenleiste, Kopf mit Suche und Hochladen, Leiste für das Handy. */
@PermitAll
public class MainLayout extends Div implements RouterLayout, AfterNavigationObserver {

    private record NavItem(Anchor anchor, String path, String view, Span count) {
    }

    private final DocumentRepository documents;
    private final BackupRepository backups;
    private final ChangeWatcher changes;
    private final AuthenticationContext authentication;
    private final IngestService ingest;
    private final ActivityRepository activities;
    private final boolean admin;
    private final String username;

    private final Main main = new Main();
    private final List<NavItem> items = new ArrayList<>();
    private final TextField search = new TextField();
    private Span totalCount;
    private Span inboxCount;
    private Span tabInboxCount;
    private Anchor activeItem;
    private Anchor failedItem;
    private Span activeCount;
    private Span failedCount;
    private Span backupFailed;
    private Component content;
    private Registration watching;
    private boolean pendingRefresh;
    private String path = "";
    private String view = "";

    public MainLayout(DocumentRepository documents, BackupRepository backups, DocuManProperties properties,
            ChangeWatcher changes, AuthenticationContext authentication, IngestService ingest,
            ActivityRepository activities) {
        this.documents = documents;
        this.backups = backups;
        this.changes = changes;
        this.authentication = authentication;
        this.ingest = ingest;
        this.activities = activities;
        this.admin = Ui.isAdmin();
        this.username = Ui.username();
        setClassName("shell");

        main.setClassName("main");
        main.setId("main");
        Div backdrop = Ui.div("backdrop");
        backdrop.addClickListener(click -> setNavigationOpen(false));
        Div workspace = Ui.div("workspace", topbar(properties.getVersion()), main);
        add(Ui.sprite(), sidebar(properties.getVersion()), backdrop, workspace, tabbar());
        updateCounts();
    }

    private Aside sidebar(String version) {
        Aside sidebar = new Aside();
        sidebar.setClassName("sidebar");
        sidebar.getElement().setAttribute("aria-label", "Navigation");

        Image logo = new Image("favicon.svg", "");
        logo.setWidth("28px");
        logo.setHeight("28px");
        Anchor brand = Ui.link("", logo, new Span("DocuMan"), versionLabel(version));
        brand.setClassName("brand");

        totalCount = Ui.span("nav-count", "0");
        inboxCount = Ui.span("nav-count", "0");
        activeCount = Ui.span("nav-count", "0");
        failedCount = Ui.span("nav-count danger", "0");
        Nav archive = group("Archiv", null,
                item("home", "Übersicht", "", "", null),
                item("file", "Alle Dokumente", "dokumente", "", totalCount),
                item("folder", "Ordner", "ordner", "", null),
                item("inbox", "Eingang", "dokumente", "inbox", inboxCount));
        activeItem = item("clock", "In Verarbeitung", "dokumente", "active", activeCount);
        failedItem = item("alert", "Fehlgeschlagen", "dokumente", "failed", failedCount);
        archive.add(activeItem, failedItem);

        Nav master = group("Stammdaten", "Stammdaten",
                item("category", "Kategorien", "stammdaten/kategorien", "", null),
                item("layers", "Dokumenttypen", "stammdaten/dokumenttypen", "", null),
                item("user", "Absendernamen", "stammdaten/korrespondenten", "", null),
                item("tag", "Tags", "stammdaten/tags", "", null));
        sidebar.add(brand, archive, master);

        if (admin) {
            sidebar.add(group("Eingänge", "Eingänge",
                    item("mail", "Postfächer", "postfaecher", "", null),
                    item("folder", "Eingangsordner", "eingangsordner", "", null)));
        }

        Nav foot = group("Konto", null);
        foot.addClassName("nav-foot");
        if (admin) {
            backupFailed = Ui.span("nav-count danger", "Fehler");
            foot.add(item("archive", "Datensicherung", "sicherung", "", backupFailed),
                    item("chart", "Übersichtsmail", "uebersichtsmail", "", null),
                    item("users", "Benutzer", "benutzer", "", null));
        }
        foot.add(item("user", "Mein Konto", "konto", "", null));
        NativeButton logout = new NativeButton();
        logout.setClassName("nav-item");
        logout.add(Ui.icon("logout"), new Span("Abmelden"), Ui.span("nav-count", username));
        logout.addClickListener(click -> authentication.logout());
        foot.add(logout);
        sidebar.add(foot);
        return sidebar;
    }

    private static Span versionLabel(String version) {
        Span label = Ui.span("brand-version", version);
        label.getElement().setAttribute("title", "Version");
        return label;
    }

    private static Nav group(String label, String title, Component... content) {
        Nav nav = new Nav();
        nav.setClassName("nav-group");
        nav.getElement().setAttribute("aria-label", label);
        if (title != null) {
            H2 heading = new H2(title);
            heading.setClassName("nav-title");
            nav.add(heading);
        }
        nav.add(content);
        return nav;
    }

    private Anchor item(String icon, String label, String target, String targetView, Span count) {
        String href = targetView.isEmpty() ? target : target + "?view=" + targetView;
        Anchor anchor = Ui.link(href, Ui.icon(icon), new Span(label));
        anchor.setClassName("nav-item");
        if (count != null) {
            anchor.add(count);
        }
        items.add(new NavItem(anchor, target, targetView, count));
        return anchor;
    }

    private Header topbar(String version) {
        Image logo = new Image("favicon.svg", "");
        logo.setWidth("28px");
        logo.setHeight("28px");
        Anchor brand = Ui.link("", logo, versionLabel(version));
        brand.setClassName("topbar-brand");
        brand.getElement().setAttribute("aria-label", "DocuMan, Übersicht");

        search.setClassName("search-field");
        search.setPlaceholder("Dokumente suchen");
        search.setAriaLabel("Dokumente durchsuchen");
        search.setClearButtonVisible(true);
        search.setPrefixComponent(Ui.icon("search"));
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.setValueChangeTimeout(300);
        // In der Dokumentliste sucht DocuMan beim Tippen, sonst nach der Eingabetaste.
        search.addValueChangeListener(change -> {
            if (change.isFromClient() && content instanceof DocumentsView list) {
                list.search(change.getValue());
            }
        });
        search.addKeyDownListener(Key.ENTER, enter -> {
            if (content instanceof DocumentsView list) {
                list.search(search.getValue());
            } else {
                UI.getCurrent().navigate("dokumente", QueryParameters.simple(Map.of("q", search.getValue().strip())));
            }
        });

        Button upload = new Button("Hochladen", Ui.icon("upload"), click -> openUpload());
        upload.addThemeVariants(ButtonVariant.PRIMARY);
        upload.addClassName("upload-button");

        Header header = new Header(brand, search, upload);
        header.setClassName("topbar");
        return header;
    }

    private Nav tabbar() {
        Nav tabbar = new Nav();
        tabbar.setClassName("tabbar");
        tabbar.getElement().setAttribute("aria-label", "Hauptnavigation");
        tabInboxCount = new Span();
        tabInboxCount.setClassName("tab-badge");
        NativeButton more = new NativeButton();
        more.add(Ui.icon("menu"), new Span("Mehr"));
        more.addClickListener(click -> setNavigationOpen(true));
        tabbar.add(tab("home", "Übersicht", "", "", null), tab("file", "Dokumente", "dokumente", "", null),
                tab("inbox", "Eingang", "dokumente", "inbox", tabInboxCount), tab("folder", "Ordner", "ordner", "", null),
                more);
        return tabbar;
    }

    private Anchor tab(String icon, String label, String target, String targetView, Span badge) {
        Anchor anchor = item(icon, label, target, targetView, null);
        anchor.removeClassName("nav-item");
        if (badge != null) {
            anchor.add(badge);
        }
        return anchor;
    }

    /** Öffnet das Fenster zum Hochladen. Angenommene Dateien erscheinen über die Aktualisierung. */
    public void openUpload() {
        new UploadDialog(ingest, activities, username, this::refreshNow).open();
    }

    private void setNavigationOpen(boolean open) {
        getElement().executeJs("document.body.classList.toggle('nav-open', $0)", open);
    }

    @Override
    public void showRouterLayoutContent(HasElement newContent) {
        content = newContent instanceof Component component ? component : null;
        main.removeAll();
        if (content != null) {
            main.add(content);
        }
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        path = event.getLocation().getPath();
        List<String> views = event.getLocation().getQueryParameters().getParameters().getOrDefault("view", List.of());
        view = views.isEmpty() ? "" : views.getFirst();
        for (NavItem item : items) {
            boolean current = item.path().isEmpty() ? path.isEmpty()
                    : (path.equals(item.path()) || path.startsWith(item.path() + "/")) && (!path.equals("dokumente")
                            || view.equals(item.view()));
            if (current) {
                item.anchor().getElement().setAttribute("aria-current", "page");
            } else {
                item.anchor().getElement().removeAttribute("aria-current");
            }
        }
        if (!(content instanceof DocumentsView)) {
            search.clear();
        }
        setNavigationOpen(false);
        updateCounts();
    }

    /** Die Dokumentliste trägt ihren Suchbegriff ins Suchfeld ein. */
    void showQuery(String query) {
        if (!search.getValue().equals(query)) {
            search.setValue(query);
        }
    }

    private void updateCounts() {
        NavigationCounts counts = documents.navigationCounts();
        totalCount.setText(String.valueOf(counts.total()));
        inboxCount.setText(String.valueOf(counts.inbox()));
        inboxCount.setClassName("nav-count" + (counts.inbox() > 0 ? " accent" : ""));
        tabInboxCount.setText(counts.inbox() > 0 ? String.valueOf(counts.inbox()) : "");
        tabInboxCount.setVisible(counts.inbox() > 0);
        activeCount.setText(String.valueOf(counts.active()));
        activeItem.setVisible(counts.active() > 0);
        failedCount.setText(String.valueOf(counts.failed()));
        failedItem.setVisible(counts.failed() > 0);
        if (backupFailed != null) {
            backupFailed.setVisible(backups.lastBackupFailed());
        }
    }

    @Override
    protected void onAttach(AttachEvent event) {
        UI ui = event.getUI();
        watching = changes.subscribe(changed -> ui.access(() -> onTick(changed)));
        // Die Taste / springt in die Suche, außer man tippt gerade in ein Feld.
        ui.getPage().executeJs("if (!window.documanSlash) { window.documanSlash = true;"
                + " document.addEventListener('keydown', event => { const target = event.composedPath()[0];"
                + " if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey || target.isContentEditable"
                + " || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) { return; }"
                + " const field = document.querySelector('.search-field'); if (field) { event.preventDefault(); field.focus(); } }); }");
    }

    @Override
    protected void onDetach(DetachEvent event) {
        if (watching != null) {
            watching.remove();
            watching = null;
        }
    }

    private void onTick(boolean changed) {
        if (changed) {
            updateCounts();
            pendingRefresh = true;
        }
        if (pendingRefresh && !busy()) {
            pendingRefresh = false;
            if (content instanceof Refreshable page) {
                page.refresh();
            }
        }
    }

    /** Nach einer eigenen Änderung, etwa dem Hochladen: sofort, ohne auf den Wächter zu warten. */
    public void refreshNow() {
        updateCounts();
        pendingRefresh = true;
        onTick(false);
    }

    private boolean busy() {
        boolean dialogOpen = getUI().map(ui -> ui.getChildren()
                .anyMatch(child -> child instanceof Dialog dialog && dialog.isOpened())).orElse(false);
        return dialogOpen || content instanceof Refreshable page && page.busy();
    }

    /** Der Rahmen der aktuellen Seite, etwa um die Zähler nach einer Änderung neu zu holen. */
    public static java.util.Optional<MainLayout> current() {
        return UI.getCurrent() == null ? java.util.Optional.empty()
                : UI.getCurrent().getChildren().filter(MainLayout.class::isInstance).map(MainLayout.class::cast)
                        .findFirst();
    }
}
