package de.documan.ui;

import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import de.documan.config.AccountConfig;
import de.documan.domain.RunKind;
import de.documan.repo.BackupRepository;
import de.documan.repo.DocumentRepository;
import de.documan.service.backup.MigrationArchive;
import de.documan.service.backup.MigrationService;
import de.documan.web.Fmt;
import jakarta.annotation.security.RolesAllowed;

/** Ein Umzugsarchiv in ein leeres System einspielen. */
@Route(value = "sicherung/umzug/einspielen", layout = MainLayout.class)
@PageTitle("Umzug einspielen · DocuMan")
@RolesAllowed(AccountConfig.ADMIN_ROLE)
public class MigrationImportView extends Div implements BeforeEnterObserver {

    public static final String CONFIRMATION = "UMZIEHEN";

    private final BackupRepository runs;
    private final DocumentRepository documents;
    private final MigrationService migration;
    private final Fmt fmt;

    public MigrationImportView(BackupRepository runs, DocumentRepository documents, MigrationService migration,
            Fmt fmt) {
        this.runs = runs;
        this.documents = documents;
        this.migration = migration;
        this.fmt = fmt;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String relative = event.getLocation().getQueryParameters().getSingleParameter("archiv").orElse("");
        MigrationArchive archive = migration.findArchive(relative);
        String problem = archive == null ? "Archiv nicht gefunden." : !archive.readable() ? archive.problem()
                : documents.total() > 0 ? "Dieses System enthält bereits Dokumente. Ein Umzug geht nur in ein leeres System."
                        : null;
        if (problem != null) {
            Ui.error(problem);
            event.forwardTo(MigrationView.class);
            return;
        }
        render(archive);
    }

    private void render(MigrationArchive archive) {
        removeAll();
        Anchor back = Ui.link("sicherung/umzug", Ui.icon("arrow-left"), new Span("Umzug"));
        back.setClassName("back-link");
        Header head = new Header(new Div(new H1("Umzug einspielen"), new Paragraph(archive.sourceLabel()
                + "-Version, Stand vom " + fmt.dateTime(archive.created()) + ", " + archive.documents()
                + " Dokument(e), " + fmt.size(archive.size()))));
        head.setClassName("page-head");
        TextField confirmation = new TextField("Zur Bestätigung " + CONFIRMATION + " eingeben");
        confirmation.setMaxLength(40);
        confirmation.setRequired(true);
        Button load = new Button("Einspielen", click -> {
            if (!Forms.show(confirmation, confirmation.getValue().strip().equals(CONFIRMATION) ? null
                    : "Bitte genau " + CONFIRMATION + " eingeben.")) {
                return;
            }
            if (runs.hasActive()) {
                Ui.warning("Es läuft bereits ein Auftrag.");
            } else {
                runs.enqueue(RunKind.IMPORT, "", archive.relative(), false);
                Ui.info("Der Umzug beginnt in wenigen Sekunden.");
            }
            UI.getCurrent().navigate(MigrationView.class);
        });
        load.addThemeVariants(ButtonVariant.PRIMARY, ButtonVariant.ERROR);
        Anchor cancel = Ui.link("sicherung/umzug", "Abbrechen");
        cancel.setClassName("button");
        add(back, head, Ui.div("panel form-stack narrow", new Html("<ul class=\"facts\"><li>Dokumente, Stammdaten, "
                + "Benutzer und Postfächer kommen in dieses System. Vorhandene Stammdaten und Benutzer bleiben "
                + "unverändert.</li><li>Neue Benutzer bekommen kein nutzbares Passwort. Vergeben Sie es danach unter "
                + "„Benutzer“.</li><li>Postfächer werden deaktiviert und brauchen ihr Passwort neu.</li><li>Das Archiv "
                + "wird vorab vollständig geprüft. Scheitert etwas, bleibt das System, wie es war.</li><li>Es werden "
                + "keine E-Mails verschickt.</li></ul>"), DocumentView.field(confirmation),
                Ui.div("form-actions", load, cancel)));
    }
}
