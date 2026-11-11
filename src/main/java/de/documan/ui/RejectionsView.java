package de.documan.ui;

import java.util.List;

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
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import de.documan.domain.DocumentSource;
import de.documan.repo.DocumentRepository;
import de.documan.repo.ImportRejectionRepository;
import de.documan.repo.ImportRejectionRepository.ImportRejection;
import de.documan.web.Fmt;
import jakarta.annotation.security.PermitAll;

/** Fehlerhafte Importe: Dateien, die nicht als Dokument angenommen wurden, mit Grund und Herkunft. */
@Route(value = "importe/fehlerhaft", layout = MainLayout.class)
@PageTitle("Fehlerhafte Importe · DocuMan")
@PermitAll
public class RejectionsView extends Div implements Refreshable {

    private final ImportRejectionRepository rejections;
    private final DocumentRepository documents;
    private final Fmt fmt;

    public RejectionsView(ImportRejectionRepository rejections, DocumentRepository documents, Fmt fmt) {
        this.rejections = rejections;
        this.documents = documents;
        this.fmt = fmt;
        refresh();
    }

    @Override
    public void refresh() {
        removeAll();
        List<ImportRejection> all = rejections.all();
        Header head = new Header(new Div(new H1("Fehlerhafte Importe"),
                new Paragraph("Dateien, die nicht als Dokument angenommen wurden, mit Grund und Herkunft.")));
        head.setClassName("page-head");
        if (!all.isEmpty()) {
            head.add(Ui.secondary("Alle entfernen", () -> Ui.confirm(
                    "Alle Einträge entfernen? Die Dateien selbst sind davon nicht betroffen.", "Entfernen",
                    this::removeEveryEntry)));
        }
        add(head);

        long failed = documents.navigationCounts().failed();
        if (failed > 0) {
            Anchor note = Ui.link("dokumente?view=failed", (failed == 1 ? "1 Dokument wurde"
                    : failed + " Dokumente wurden") + " angenommen, aber die Verarbeitung ist fehlgeschlagen. "
                    + "Sie stehen unter „Fehlgeschlagen“.");
            note.setClassName("status-note failed");
            add(note);
        }

        if (all.isEmpty()) {
            add(DocumentsView.emptyState("alert", "Keine fehlerhaften Importe", "Wird eine Datei beim Hochladen, aus "
                    + "dem Eingangsordner oder aus einem Postfach abgelehnt, etwa als Duplikat oder weil sie leer ist, "
                    + "steht sie hier mit dem Grund.", null));
            return;
        }

        Grid<ImportRejection> grid = new Grid<>();
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_WRAP_CELL_CONTENT);
        grid.setAllRowsVisible(true);
        grid.addColumn(rejection -> fmt.dateTime(rejection.created())).setHeader("Zeitpunkt").setAutoWidth(true)
                .setFlexGrow(0);
        grid.addComponentColumn(rejection -> Ui.span("strong", rejection.filename())).setHeader("Datei")
                .setFlexGrow(2);
        grid.addComponentColumn(this::origin).setHeader("Herkunft").setFlexGrow(2);
        grid.addComponentColumn(RejectionsView::reason).setHeader("Grund").setFlexGrow(3);
        grid.addComponentColumn(rejection -> {
            Button remove = new Button("Entfernen", click -> remove(rejection));
            remove.addThemeVariants(ButtonVariant.SMALL, ButtonVariant.TERTIARY);
            return remove;
        }).setAutoWidth(true).setFlexGrow(0);
        grid.setItems(all);
        add(grid);
        if (all.size() >= ImportRejectionRepository.KEEP) {
            add(Ui.paragraph("help", "Es bleiben die neuesten " + ImportRejectionRepository.KEEP + " Einträge erhalten."));
        }
    }

    private Div origin(ImportRejection rejection) {
        Div cell = new Div(new Span(rejection.origin()));
        if (!rejection.sourceDetail().isEmpty()) {
            cell.add(Ui.span("rejection-detail", rejection.sourceDetail()));
        }
        if (rejection.source() == DocumentSource.CONSUME) {
            cell.add(Ui.span("rejection-detail", "Die Datei liegt im Eingangsordner unter _abgelehnt."));
        }
        return cell;
    }

    private static Div reason(ImportRejection rejection) {
        Div cell = new Div(new Span(rejection.reason()));
        if (rejection.existingDocumentId() != null) {
            Anchor existing = Ui.link("dokumente/" + rejection.existingDocumentId(), "Vorhandenes Dokument öffnen");
            existing.setClassName("rejection-detail");
            cell.add(existing);
        }
        return cell;
    }

    private void remove(ImportRejection rejection) {
        if (rejections.delete(rejection.id())) {
            Ui.success("Der Eintrag wurde entfernt.");
        }
        refresh();
        MainLayout.current().ifPresent(MainLayout::refreshNow);
    }

    private void removeEveryEntry() {
        int removed = rejections.deleteAll();
        Ui.success(removed == 1 ? "1 Eintrag wurde entfernt." : removed + " Einträge wurden entfernt.");
        refresh();
        MainLayout.current().ifPresent(MainLayout::refreshNow);
    }
}
