package de.documan.ui;

import java.util.List;

import org.springframework.core.io.FileSystemResource;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.UploadI18N;
import com.vaadin.flow.server.streams.UploadHandler;

import de.documan.domain.ActivityKind;
import de.documan.domain.Document;
import de.documan.domain.DocumentSource;
import de.documan.repo.ActivityRepository;
import de.documan.service.DuplicateDocumentException;
import de.documan.service.IngestException;
import de.documan.service.IngestService;

/**
 * Dateien hochladen, per Auswahl oder durch Ablegen im Fenster.
 *
 * <p>Zu jeder Datei steht, ob sie angenommen wurde. Abgelehnte bleiben mit ihrem Grund stehen, bis man das
 * Fenster schließt. Angenommene erscheinen sofort in den Ansichten.
 */
class UploadDialog extends Dialog {

    private final IngestService ingest;
    private final ActivityRepository activities;
    private final String username;
    private final Runnable afterUpload;
    private final UnorderedList results = new UnorderedList();
    private int accepted;
    private int rejected;

    UploadDialog(IngestService ingest, ActivityRepository activities, String username, Runnable afterUpload) {
        this.ingest = ingest;
        this.activities = activities;
        this.username = username;
        this.afterUpload = afterUpload;
        setHeaderTitle("Hochladen");
        addClassName("upload-dialog");
        setWidth("min(560px, 100vw)");

        Upload upload = new Upload(UploadHandler.toTempFile((metadata, file) -> {
            String name = metadata.fileName() == null || metadata.fileName().isBlank() ? "dokument" : metadata.fileName();
            try {
                Document document = ingest.ingest(new FileSystemResource(file), name, DocumentSource.UPLOAD,
                        IngestService.Options.of(username, ""));
                report(name, "angenommen", "ok", "dokumente/" + document.id());
            } catch (DuplicateDocumentException duplicate) {
                report(name, duplicate.getMessage(), "warning", "dokumente/" + duplicate.existing().id());
            } catch (IngestException error) {
                report(name, error.getMessage(), "warning", null);
            } finally {
                file.delete();
            }
        }));
        upload.setI18n(german());
        upload.setMaxFiles(200);
        upload.setDropAllowed(true);
        upload.addFileRejectedListener(event -> report(event.getFileName(), event.getErrorMessage(), "error", null));
        upload.addFailedListener(event -> report(event.getFileName(), "Nicht hochgeladen: "
                + (event.getReason() == null ? "Verbindung unterbrochen" : event.getReason().getMessage()), "error", null));
        upload.addAllFinishedListener(event -> upload.clearFileList());

        results.setClassName("upload-results");
        add(upload, results);
        Button close = new Button("Schließen", click -> close());
        getFooter().add(close);
        // Solange das Fenster offen ist, warten die Ansichten. Danach zeigen sie die neuen Dokumente.
        addOpenedChangeListener(change -> {
            if (!change.isOpened()) {
                afterUpload.run();
            }
        });
    }

    private void report(String name, String note, String level, String href) {
        UI ui = UI.getCurrent();
        Runnable show = () -> {
            Span label = Ui.span("upload-name", name);
            Span text = Ui.span("upload-note", note);
            ListItem entry = new ListItem(label, text);
            entry.setClassName(level);
            if (href != null) {
                entry.add(Ui.link(href, level.equals("ok") ? "Öffnen" : "Vorhandenes öffnen"));
            }
            results.add(entry);
            if (level.equals("ok")) {
                accepted++;
                activities.record(username, ActivityKind.UPLOADED, 1);
                afterUpload.run();
            } else {
                rejected++;
            }
            setHeaderTitle(rejected == 0 ? accepted + " angenommen"
                    : accepted + " angenommen, " + rejected + " abgelehnt");
        };
        if (ui == null) {
            show.run();
        } else {
            ui.access(show::run);
        }
    }

    private static UploadI18N german() {
        UploadI18N i18n = new UploadI18N();
        i18n.setDropFiles(new UploadI18N.DropFiles().setOne("Datei hier ablegen").setMany("Dateien hier ablegen"));
        i18n.setAddFiles(new UploadI18N.AddFiles().setOne("Datei auswählen …").setMany("Dateien auswählen …"));
        i18n.setError(new UploadI18N.Error().setTooManyFiles("Zu viele Dateien auf einmal.")
                .setFileIsTooBig("Die Datei ist zu groß.").setIncorrectFileType("Dieses Format wird nicht angenommen."));
        i18n.setUploading(new UploadI18N.Uploading()
                .setStatus(new UploadI18N.Uploading.Status().setConnecting("Verbinde …").setStalled("Angehalten")
                        .setProcessing("Wird übernommen …").setHeld("Wartet"))
                .setRemainingTime(new UploadI18N.Uploading.RemainingTime().setPrefix("noch ")
                        .setUnknown("Restzeit unbekannt"))
                .setError(new UploadI18N.Uploading.Error().setServerUnavailable("Server nicht erreichbar")
                        .setUnexpectedServerError("Fehler auf dem Server").setForbidden("Nicht erlaubt")));
        i18n.setUnits(List.of("B", "KB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB"));
        return i18n;
    }
}
