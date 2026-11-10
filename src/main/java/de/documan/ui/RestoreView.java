package de.documan.ui;

import java.util.Map;

import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
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
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;

import de.documan.config.AccountConfig;
import de.documan.domain.RunKind;
import de.documan.repo.BackupRepository;
import de.documan.repo.DocumentRepository;
import de.documan.service.backup.Archive;
import de.documan.service.backup.BackupException;
import de.documan.service.backup.BackupService;
import de.documan.web.Fmt;
import jakarta.annotation.security.RolesAllowed;

/** Eine Sicherung wiederherstellen. Der aktuelle Stand wird dabei vollständig ersetzt. */
@Route(value = "sicherung/wiederherstellen", layout = MainLayout.class)
@PageTitle("Sicherung wiederherstellen · DocuMan")
@RolesAllowed(AccountConfig.ADMIN_ROLE)
public class RestoreView extends Div implements BeforeEnterObserver {

    public static final String CONFIRMATION = "WIEDERHERSTELLEN";

    private final BackupRepository runs;
    private final BackupService backup;
    private final DocumentRepository documents;
    private final Fmt fmt;

    public RestoreView(BackupRepository runs, BackupService backup, DocumentRepository documents, Fmt fmt) {
        this.runs = runs;
        this.backup = backup;
        this.documents = documents;
        this.fmt = fmt;
    }

    static void open(String relative) {
        UI.getCurrent().navigate("sicherung/wiederherstellen", QueryParameters.simple(Map.of("archiv", relative)));
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String relative = event.getLocation().getQueryParameters().getSingleParameter("archiv").orElse("");
        Archive archive = backup.findArchive(relative);
        String problem = archive == null ? "Archiv nicht gefunden." : !archive.readable() ? archive.problem() : null;
        if (problem == null) {
            try {
                backup.checkCompatible(archive.manifest());
            } catch (BackupException error) {
                problem = error.getMessage();
            }
        }
        if (problem != null) {
            Ui.error(problem);
            event.forwardTo(BackupView.class);
            return;
        }
        render(archive);
    }

    private void render(Archive archive) {
        removeAll();
        Anchor back = Ui.link("sicherung", Ui.icon("arrow-left"), new Span("Datensicherung"));
        back.setClassName("back-link");
        Header head = new Header(new Div(new H1("Sicherung wiederherstellen"), new Paragraph("Stand vom "
                + fmt.dateTime(archive.created()) + ", " + archive.documents() + " Dokument(e), "
                + fmt.size(archive.size()))));
        head.setClassName("page-head");

        Checkbox safety = new Checkbox("Vorher den aktuellen Stand sichern", true);
        TextField confirmation = new TextField("Zur Bestätigung " + CONFIRMATION + " eingeben");
        confirmation.setMaxLength(40);
        confirmation.setRequired(true);
        Button restore = new Button("Wiederherstellen", click -> {
            if (!Forms.show(confirmation, confirmation.getValue().strip().equals(CONFIRMATION) ? null
                    : "Bitte genau " + CONFIRMATION + " eingeben.")) {
                return;
            }
            if (runs.hasActive()) {
                Ui.warning("Es läuft bereits ein Auftrag.");
            } else {
                runs.enqueue(RunKind.RESTORE, "", archive.relative(), safety.getValue());
                Ui.info("Die Wiederherstellung beginnt in wenigen Sekunden.");
            }
            UI.getCurrent().navigate(BackupView.class);
        });
        restore.addThemeVariants(ButtonVariant.PRIMARY, ButtonVariant.ERROR);
        Anchor cancel = Ui.link("sicherung", "Abbrechen");
        cancel.setClassName("button");

        Div form = Ui.div("panel form-stack narrow",
                new Html("<div class=\"status-note failed\"><strong>Der aktuelle Stand wird vollständig ersetzt.</strong> "
                        + "Alle " + documents.total() + " vorhandenen Dokumente, Stammdaten und Benutzer weichen dem "
                        + "Inhalt des Archivs.</div>"),
                new Html("<ul class=\"facts\"><li>Alles, was seit dieser Sicherung hinzukam, geht verloren, sofern es "
                        + "nicht vorher gesichert wird.</li><li>DocuMan ist währenddessen gesperrt. Danach melden Sie sich "
                        + "mit den Zugangsdaten aus der Sicherung an.</li><li>Das Archiv wird vorab vollständig geprüft. "
                        + "Ist es beschädigt, bleibt alles unverändert.</li><li>Passt der <span class=\"mono\">SECRET_KEY"
                        + "</span> nicht zur Sicherung, müssen Postfach-Passwörter neu eingegeben werden.</li></ul>"),
                safety, DocumentView.field(confirmation), Ui.div("form-actions", restore, cancel));
        add(back, head, form);
    }
}
