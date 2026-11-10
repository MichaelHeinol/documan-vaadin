package de.documan.ui;

import java.util.List;
import java.util.Map;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;

import de.documan.config.AccountConfig;
import de.documan.domain.BackupRun;
import de.documan.domain.RunKind;
import de.documan.repo.BackupRepository;
import de.documan.repo.DocumentRepository;
import de.documan.service.backup.MigrationArchive;
import de.documan.service.backup.MigrationService;
import de.documan.web.Fmt;
import jakarta.annotation.security.RolesAllowed;

/** Umzug von und zur Django-Version. */
@Route(value = "sicherung/umzug", layout = MainLayout.class)
@PageTitle("Umzug · DocuMan")
@RolesAllowed(AccountConfig.ADMIN_ROLE)
public class MigrationView extends Div {

    private static final int HISTORY = 30;
    private static final int REFRESH_MILLIS = 4000;

    private final BackupRepository runs;
    private final DocumentRepository documents;
    private final MigrationService migration;
    private final Fmt fmt;
    private final Div content = new Div();
    private Button create;
    private Registration ticking;
    private boolean wasActive;

    public MigrationView(BackupRepository runs, DocumentRepository documents, MigrationService migration, Fmt fmt) {
        this.runs = runs;
        this.documents = documents;
        this.migration = migration;
        this.fmt = fmt;
        Anchor back = Ui.link("sicherung", Ui.icon("arrow-left"), new Span("Datensicherung"));
        back.setClassName("back-link");
        create = Ui.primary("Umzugsarchiv erstellen", () -> {
            if (runs.hasActive()) {
                Ui.warning("Es läuft bereits ein Auftrag.");
            } else {
                runs.enqueue(RunKind.EXPORT, "", "", false);
                Ui.info("Das Umzugsarchiv entsteht in wenigen Sekunden.");
            }
            render();
        });
        Header head = new Header(new Div(new H1("Umzug"), new Paragraph("Bringt Dokumente, Stammdaten, Benutzer und "
                + "Postfächer zur Django-Version und zurück. Passwörter werden nicht übertragen. Mit der Java-Version "
                + "teilt diese Fassung Datenbank und Sicherungen, dafür braucht es keinen Umzug.")), create);
        head.setClassName("page-head");
        add(back, head, content);
        render();
    }

    private void render() {
        content.removeAll();
        BackupRun active = runs.activeRun().orElse(null);
        create.setEnabled(active == null);
        if (active != null) {
            content.add(Ui.div("status-note", new Span(active.kind().label() + (active.isQueued()
                    ? ": wartet auf den Worker. Ein Dokument in Verarbeitung wird zuerst fertig."
                    : ": läuft. Diese Seite aktualisiert sich von selbst."))));
        }
        long current = documents.total();
        if (current > 0) {
            content.add(new Html("<div class=\"status-note failed\"><strong>Einspielen ist gesperrt.</strong> Dieses "
                    + "System enthält " + current + " Dokument(e). Ein Umzug geht nur in ein leeres System. Erstellen geht "
                    + "jederzeit.</div>"));
        }

        Section found = new Section();
        found.setClassName("page-section");
        found.add(Ui.div("section-head", new H2("Gefundene Umzugsarchive")));
        List<MigrationArchive> archives = migration.listArchives();
        if (archives.isEmpty()) {
            found.add(Ui.div("card card-empty-box", new Paragraph("Noch kein Umzugsarchiv gefunden. Kopieren Sie ein "
                    + "Archiv der anderen Version in den Sicherungsordner, es erscheint dann hier.")));
        } else {
            Grid<MigrationArchive> grid = new Grid<>();
            grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_WRAP_CELL_CONTENT);
            grid.setAllRowsVisible(true);
            grid.addComponentColumn(archive -> archive.readable() ? new Span(fmt.dateTime(archive.created()))
                    : Ui.span("state-bad", archive.problem())).setHeader("Erstellt");
            grid.addColumn(archive -> archive.sourceLabel().isEmpty() ? "–" : archive.sourceLabel()).setHeader("Quelle");
            grid.addColumn(archive -> archive.folder().isEmpty() ? "–" : archive.folder()).setHeader("Ordner");
            grid.addColumn(archive -> archive.documents() != null ? String.valueOf(archive.documents()) : "–")
                    .setHeader("Dokumente").setAutoWidth(true).setFlexGrow(0);
            grid.addColumn(archive -> fmt.size(archive.size())).setHeader("Größe").setAutoWidth(true).setFlexGrow(0);
            grid.addComponentColumn(archive -> {
                if (!archive.readable() || current > 0) {
                    return new Div();
                }
                Button load = new Button("Einspielen", click -> UI.getCurrent().navigate("sicherung/umzug/einspielen",
                        QueryParameters.simple(Map.of("archiv", archive.relative()))));
                load.addThemeVariants(ButtonVariant.SMALL);
                return load;
            }).setAutoWidth(true).setFlexGrow(0);
            grid.setItems(archives);
            found.add(grid);
        }
        content.add(found);

        List<BackupRun> history = runs.history(HISTORY).stream().filter(BackupRun::isMigration).toList();
        if (!history.isEmpty()) {
            Grid<BackupRun> grid = new Grid<>();
            grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_WRAP_CELL_CONTENT);
            grid.setAllRowsVisible(true);
            grid.addColumn(run -> fmt.dateTime(run.shownAt())).setHeader("Zeitpunkt").setAutoWidth(true);
            grid.addColumn(run -> run.kind().label()).setHeader("Art").setAutoWidth(true);
            grid.addComponentColumn(run -> run.isFailed() ? Ui.span("state-bad", run.status().label())
                    : new Span(run.status().label())).setHeader("Status").setAutoWidth(true);
            grid.addColumn(BackupRun::detail).setHeader("Details").setFlexGrow(3);
            grid.setItems(history);
            Section section = new Section();
            section.setClassName("page-section");
            section.add(Ui.div("section-head", new H2("Verlauf")), grid);
            content.add(section);
        }
        boolean nowActive = active != null;
        if (wasActive && !nowActive) {
            MainLayout.current().ifPresent(MainLayout::refreshNow);
        }
        wasActive = nowActive;
    }

    @Override
    protected void onAttach(AttachEvent event) {
        ticking = Ticker.every(event.getUI(), REFRESH_MILLIS, () -> {
            if (runs.hasActive() || wasActive) {
                render();
            }
        });
    }

    @Override
    protected void onDetach(DetachEvent event) {
        if (ticking != null) {
            ticking.remove();
            ticking = null;
        }
    }
}
