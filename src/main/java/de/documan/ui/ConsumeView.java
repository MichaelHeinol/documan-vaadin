package de.documan.ui;

import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.html.DescriptionList;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import de.documan.config.AccountConfig;
import de.documan.service.InboxFolder;
import de.documan.service.InboxFolder.Status;
import de.documan.service.InboxFolderException;
import jakarta.annotation.security.RolesAllowed;

/** Die überwachten Eingangsordner: Zustand, wartende und abgelehnte Dateien, hinzufügen und entfernen. */
@Route(value = "eingangsordner", layout = MainLayout.class)
@PageTitle("Eingangsordner · DocuMan")
@RolesAllowed(AccountConfig.ADMIN_ROLE)
public class ConsumeView extends Div implements Refreshable {

    private final InboxFolder inbox;
    private final Div confirm = new Div();
    private final UnorderedList list = new UnorderedList();

    public ConsumeView(InboxFolder inbox) {
        this.inbox = inbox;
        Header head = new Header(new Div(new H1("Eingangsordner"), new Paragraph("Was hier landet, importiert DocuMan "
                + "automatisch und entfernt es danach aus dem Ordner.")));
        head.setClassName("page-head");
        list.setClassName("consume-list");
        Section section = new Section();
        section.setClassName("panel form-stack");
        section.add(new H2("Überwachte Ordner"), list,
                Ui.div("form-actions", Ui.primary("Ordner hinzufügen", this::choose)),
                new Html("<p class=\"help\">Unter <span class=\"mono\">" + Markup.esc(InboxFolder.DEFAULT)
                        + "</span> steht der Standardordner, unter <span class=\"mono\">nas</span> stehen die Ordner Ihres "
                        + "NAS, soweit der DocuMan-Benutzer darauf zugreifen darf. Jeder Ordner muss bereits als Freigabe "
                        + "erreichbar sein, zum Beispiel für Ihren Scanner. Ordner dürfen nicht ineinander liegen. Geprüft "
                        + "wird alle 10 Sekunden. Wer einen Ordner nicht mehr überwacht, findet die Dateien darin "
                        + "unverändert vor.</p>"));
        add(head, confirm, section);
        refresh();
    }

    @Override
    public void refresh() {
        list.removeAll();
        var folders = inbox.status();
        for (Status folder : folders) {
            DescriptionList facts = new DescriptionList();
            facts.setClassName("consume-facts");
            facts.add(new DescriptionList.Term("Ordner"), mono(folder.path()),
                    new DescriptionList.Term("Zustand"), new DescriptionList.Description(!folder.reachable()
                            ? "Nicht gefunden" : !folder.writable() ? "Nur lesbar" : "Erreichbar"),
                    new DescriptionList.Term("Wartende Dateien"),
                    new DescriptionList.Description(String.valueOf(folder.waiting())),
                    new DescriptionList.Term("Abgelehnt"),
                    new DescriptionList.Description(String.valueOf(folder.rejected())));
            ListItem item = new ListItem(facts);
            item.setClassName("consume-folder");
            if (!folder.reachable()) {
                item.add(Ui.paragraph("status-note failed", "DocuMan findet diesen Ordner nicht. Bitte prüfen Sie die "
                        + "Freigabe oder überwachen Sie einen anderen Ordner."));
            } else if (!folder.writable()) {
                item.add(Ui.paragraph("status-note failed", "DocuMan darf hier nicht schreiben. Importierte Dateien "
                        + "lassen sich nicht entfernen, bitte prüfen Sie die Rechte."));
            }
            if (folders.size() > 1) {
                item.add(Ui.secondary("Nicht mehr überwachen", () -> remove(folder)));
            }
            list.add(item);
        }
    }

    private static DescriptionList.Description mono(String text) {
        DescriptionList.Description description = new DescriptionList.Description(text);
        description.setClassName("mono");
        return description;
    }

    private void choose() {
        new FolderDialog("Eingangsordner hinzufügen", "Auswahl", "", folder -> {
            String cleaned = inbox.clean(folder);
            return new FolderDialog.Listing(cleaned, inbox.listFolders(cleaned));
        }, inbox::createFolder, this::addFolder).open();
    }

    private void addFolder(String folder) {
        try {
            int files = inbox.check(folder);
            if (files > 0) {
                ask(folder, files);
                return;
            }
            confirmAdd(folder);
        } catch (InboxFolderException error) {
            Ui.error(error.getMessage());
        }
    }

    /** Ein gefüllter Ordner: Erst nach Rückfrage, denn DocuMan importiert und entfernt alles darin. */
    private void ask(String folder, int files) {
        confirm.removeAll();
        Div box = Ui.div("panel form-stack consume-confirm", new H2("Diesen Ordner wirklich überwachen?"),
                new Html("<p class=\"status-note failed\"><strong class=\"mono\">" + Markup.esc(inbox.display(folder))
                        + "</strong> enthält " + files + (files == 1 ? " Datei" : " Dateien") + ". DocuMan importiert "
                        + "alles, auch aus Unterordnern, und entfernt es danach dort. Duplikate und nicht unterstützte "
                        + "Dateien landen in <span class=\"mono\">_abgelehnt</span>.</p>"));
        box.add(Ui.div("form-actions", Ui.danger("Ja, importieren und entfernen", () -> {
            confirm.removeAll();
            confirmAdd(folder);
        }), Ui.secondary("Abbrechen", confirm::removeAll)));
        confirm.add(box);
    }

    private void confirmAdd(String folder) {
        try {
            inbox.add(folder);
            Ui.success("DocuMan überwacht jetzt auch " + inbox.display(folder) + ".");
            refresh();
        } catch (InboxFolderException error) {
            Ui.error(error.getMessage());
        }
    }

    private void remove(Status folder) {
        try {
            String removed = inbox.remove(folder.id());
            Ui.success("DocuMan überwacht " + inbox.display(removed) + " nicht mehr. Die Dateien dort bleiben liegen.");
            refresh();
        } catch (InboxFolderException error) {
            Ui.error(error.getMessage());
        }
    }
}
