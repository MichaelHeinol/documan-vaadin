package de.documan.ui;

import static de.documan.ui.Markup.esc;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.IntStream;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.timepicker.TimePicker;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;

import de.documan.config.AccountConfig;
import de.documan.config.DocuManProperties;
import de.documan.domain.BackupFrequency;
import de.documan.domain.BackupRun;
import de.documan.domain.BackupSettings;
import de.documan.domain.RunKind;
import de.documan.repo.BackupRepository;
import de.documan.service.backup.Archive;
import de.documan.service.backup.BackupException;
import de.documan.service.backup.BackupService;
import de.documan.web.Fmt;
import jakarta.annotation.security.RolesAllowed;

/** Datensicherung: Stand, vorhandene Archive, Zeitplan und Verlauf. */
@Route(value = "sicherung", layout = MainLayout.class)
@PageTitle("Datensicherung · DocuMan")
@RolesAllowed(AccountConfig.ADMIN_ROLE)
public class BackupView extends Div {

    private static final int HISTORY = 15;
    private static final int MAX_KEEP = 1000;
    private static final int REFRESH_MILLIS = 4000;

    private final BackupRepository runs;
    private final BackupService backup;
    private final DocuManProperties properties;
    private final Fmt fmt;
    private final Div status = new Div();
    private Registration ticking;
    private boolean wasActive;

    public BackupView(BackupRepository runs, BackupService backup, DocuManProperties properties, Fmt fmt) {
        this.runs = runs;
        this.backup = backup;
        this.properties = properties;
        this.fmt = fmt;
        add(head(), status, settingsForm());
        showStatus();
    }

    private Header head() {
        Anchor migration = Ui.link("sicherung/umzug", "Umzug");
        migration.setClassName("button");
        Button now = Ui.primary("Jetzt sichern", () -> {
            if (runs.hasActive()) {
                Ui.warning("Es läuft bereits ein Auftrag.");
            } else {
                runs.enqueue(RunKind.BACKUP, BackupService.MANUAL, "", false);
                Ui.info("Die Sicherung beginnt in wenigen Sekunden.");
            }
            showStatus();
        });
        Header head = new Header(new Div(new H1("Datensicherung"),
                new Paragraph("Eine Sicherung enthält die Datenbank und alle Dokumente in einer Archivdatei.")),
                Ui.div("form-actions", migration, now));
        head.setClassName("page-head");
        return head;
    }

    /** Stand, Archive und Verlauf. Läuft ein Auftrag, aktualisiert sich das von selbst. */
    private void showStatus() {
        status.removeAll();
        BackupSettings config = runs.loadSettings();
        BackupRun active = runs.activeRun().orElse(null);
        if (active != null) {
            Div note = Ui.div("status-note", new com.vaadin.flow.component.html.Span(active.kind().label()
                    + (active.isQueued() ? ": wartet auf den Worker. Ein Dokument in Verarbeitung wird zuerst fertig."
                            : ": läuft. Diese Seite aktualisiert sich von selbst.")));
            note.getElement().setAttribute("role", "status");
            status.add(note);
        }
        BackupRun last = runs.lastSuccessfulBackup().orElse(null);
        ZonedDateTime next = config.enabled() ? backup.nextRun(config) : null;
        List<Archive> archives = backup.listArchives();
        status.add(new Html("<div class=\"stats three\">"
                + stat("Letzte Sicherung", last != null ? fmt.shortDateTime(last.finishedAt()) : "Keine",
                        last != null ? last.documents() + " Dokument(e), " + fmt.size(last.size()) : "Noch nichts gesichert", "")
                + stat("Nächste Sicherung", next != null ? fmt.shortDateTime(next) : "Aus",
                        next != null ? config.frequency().label() : "Kein Zeitplan aktiv", "")
                + stat("Archive", String.valueOf(archives.size()), properties.getBackupDir().toString(), " mono")
                + "</div>"));

        Section existing = new Section();
        existing.setClassName("page-section");
        existing.add(Ui.div("section-head", new H2("Vorhandene Sicherungen")));
        if (archives.isEmpty()) {
            existing.add(Ui.div("card card-empty-box", new Paragraph("Im Sicherungsordner liegt noch kein Archiv. Nach "
                    + "einer Neuinstallation kopieren Sie Ihre Archive dorthin, sie erscheinen dann hier.")));
        } else {
            Grid<Archive> grid = new Grid<>();
            grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_WRAP_CELL_CONTENT);
            grid.setAllRowsVisible(true);
            grid.addComponentColumn(archive -> archive.readable() ? new com.vaadin.flow.component.html.Span(
                    fmt.dateTime(archive.created())) : Ui.span("state-bad", archive.problem())).setHeader("Erstellt");
            grid.addColumn(Archive::originLabel).setHeader("Auslöser");
            grid.addColumn(archive -> archive.folder().isEmpty() ? "–" : archive.folder()).setHeader("Ordner");
            grid.addColumn(archive -> archive.documents() != null ? String.valueOf(archive.documents()) : "–")
                    .setHeader("Dokumente").setAutoWidth(true).setFlexGrow(0);
            grid.addColumn(archive -> fmt.size(archive.size())).setHeader("Größe").setAutoWidth(true).setFlexGrow(0);
            grid.addComponentColumn(archive -> {
                if (!archive.readable()) {
                    return new Div();
                }
                Button restore = new Button("Wiederherstellen", click -> RestoreView.open(archive.relative()));
                restore.addThemeVariants(ButtonVariant.SMALL);
                return restore;
            }).setAutoWidth(true).setFlexGrow(0);
            grid.setItems(archives);
            existing.add(grid);
        }
        status.add(existing);

        List<BackupRun> history = runs.history(HISTORY);
        if (!history.isEmpty()) {
            Section section = new Section();
            section.setClassName("page-section");
            Grid<BackupRun> grid = new Grid<>();
            grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_WRAP_CELL_CONTENT);
            grid.setAllRowsVisible(true);
            grid.addColumn(run -> fmt.dateTime(run.shownAt())).setHeader("Zeitpunkt").setAutoWidth(true);
            grid.addColumn(run -> run.kind().label()).setHeader("Art").setAutoWidth(true);
            grid.addComponentColumn(run -> run.isFailed() ? Ui.span("state-bad", run.status().label())
                    : new com.vaadin.flow.component.html.Span(run.status().label())).setHeader("Status").setAutoWidth(true);
            grid.addColumn(BackupRun::detail).setHeader("Details").setFlexGrow(3);
            grid.setItems(history);
            section.add(Ui.div("section-head", new H2("Verlauf")), grid);
            status.add(section);
        }
        boolean nowActive = active != null;
        if (wasActive && !nowActive) {
            MainLayout.current().ifPresent(MainLayout::refreshNow);
        }
        wasActive = nowActive;
    }

    private static String stat(String label, String value, String note, String noteClass) {
        return "<div class=\"card stat\"><span class=\"stat-label\">" + esc(label) + "</span><span class=\"stat-value\">"
                + esc(value) + "</span><span class=\"stat-note" + noteClass + "\">" + esc(note) + "</span></div>";
    }

    private Section settingsForm() {
        BackupSettings config = runs.loadSettings();
        Checkbox enabled = new Checkbox("Zeitgesteuert sichern", config.enabled());
        Select<BackupFrequency> frequency = new Select<>();
        frequency.setLabel("Häufigkeit");
        frequency.setItems(BackupFrequency.values());
        frequency.setItemLabelGenerator(BackupFrequency::label);
        frequency.setValue(config.frequency());
        Select<Integer> weekday = new Select<>();
        weekday.setLabel("Wochentag");
        weekday.setItems(IntStream.range(0, BackupSettings.WEEKDAYS.size()).boxed().toList());
        weekday.setItemLabelGenerator(BackupSettings.WEEKDAYS::get);
        weekday.setValue(Math.max(0, Math.min(config.weekday(), BackupSettings.WEEKDAYS.size() - 1)));
        TimePicker time = new TimePicker("Uhrzeit");
        time.setLocale(java.util.Locale.GERMANY);
        time.setValue(config.time());
        TextField folder = new TextField("Unterordner");
        folder.setMaxLength(255);
        folder.setValue(config.folder());
        folder.setHelperText("Leer = direkt im Sicherungsordner. Fehlende Ordner werden angelegt.");
        Button choose = Ui.secondary("Ordner wählen", () -> new FolderDialog("Zielordner wählen", "Sicherungsordner",
                folder.getValue(), value -> {
                    String cleaned = BackupService.cleanFolder(value);
                    return new FolderDialog.Listing(cleaned, backup.listFolders(cleaned));
                }, backup::createFolder, folder::setValue).open());
        IntegerField keep = new IntegerField("Aufbewahren");
        keep.setMin(0);
        keep.setMax(MAX_KEEP);
        keep.setValue(config.keep());
        keep.setHelperText("So viele Sicherungen des Zeitplans bleiben erhalten. 0 = alle.");
        Div weekdayField = DocumentView.field(weekday);
        weekdayField.setVisible(frequency.getValue() == BackupFrequency.WEEKLY);
        frequency.addValueChangeListener(change -> weekdayField.setVisible(change.getValue() == BackupFrequency.WEEKLY));

        Section section = new Section();
        section.setClassName("panel form-stack page-section");
        section.add(new H2("Zeitplan und Zielordner"), enabled,
                Ui.div("form-row", DocumentView.field(frequency), weekdayField, DocumentView.field(time)),
                Ui.div("form-row", Ui.div("field grow", Ui.div("input-with-button", folder, choose)),
                        DocumentView.field(keep)),
                new Html("<p class=\"help\">Im Unterordner <span class=\"mono\">nas</span> sehen Sie die Ordner Ihres NAS, "
                        + "soweit der DocuMan-Benutzer darauf zugreifen darf. Andere Laufwerke legt <span class=\"mono\">"
                        + "BACKUP_BROWSE_DIR</span> in der <span class=\"mono\">.env</span> fest.</p>"));
        section.add(Ui.div("form-actions", Ui.primary("Speichern", () -> {
            LocalTime chosenTime = time.getValue();
            boolean ok = Forms.show(time, chosenTime == null ? "Bitte eine Uhrzeit wie 02:30 angeben." : null);
            Integer kept = keep.getValue();
            ok &= Forms.show(keep, kept == null || kept < 0 || kept > MAX_KEEP
                    ? "Bitte eine Zahl zwischen 0 und " + MAX_KEEP + " angeben." : null);
            String target = "";
            try {
                target = BackupService.cleanFolder(folder.getValue() == null ? "" : folder.getValue());
                backup.checkWritable(target);
                Forms.show(folder, null);
            } catch (BackupException error) {
                ok &= Forms.show(folder, error.getMessage());
            }
            if (!ok) {
                return;
            }
            // Der Zeitplan beginnt jetzt: Ein Termin von heute früh wird nicht nachgeholt.
            runs.saveSettings(new BackupSettings(enabled.getValue(), frequency.getValue(), weekday.getValue(),
                    chosenTime, target, kept, Instant.now()));
            Ui.success("Einstellungen gespeichert.");
            showStatus();
        })));
        return section;
    }

    @Override
    protected void onAttach(AttachEvent event) {
        ticking = Ticker.every(event.getUI(), REFRESH_MILLIS, () -> {
            if (runs.hasActive() || wasActive) {
                showStatus();
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
