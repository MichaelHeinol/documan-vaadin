package de.documan.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import de.documan.domain.Document;
import de.documan.repo.DocumentRepository;
import de.documan.service.FileNames;
import de.documan.service.FilingService;
import de.documan.service.MediaStore;

/** Liefert Originale, Archivfassungen und Vorschaubilder aus. */
@Controller
public class DocumentFileController {

    private static final MediaType PDF = MediaType.APPLICATION_PDF;

    private final DocumentRepository documents;
    private final MediaStore media;
    private final FilingService filing;

    public DocumentFileController(DocumentRepository documents, MediaStore media, FilingService filing) {
        this.documents = documents;
        this.media = media;
        this.filing = filing;
    }

    private Document require(long id) {
        return documents.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Resource open(String relative) {
        Path path = media.resolve(relative);
        if (relative.isEmpty() || !Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Datei fehlt auf dem Datenträger.");
        }
        return new FileSystemResource(path);
    }

    @GetMapping("/dokumente/{id}/vorschau")
    public ResponseEntity<Resource> preview(@PathVariable long id) {
        Document document = require(id);
        // Nur PDF und Bilder öffnet der Browser direkt. Eine HTML- oder SVG-Datei könnte sonst
        // Skripte im Namen des angemeldeten Benutzers ausführen.
        if (!List.of("pdf", "image").contains(document.previewKind())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String file = document.hasArchive() ? document.archive() : document.original();
        MediaType type = document.hasArchive() ? PDF : MediaType.parseMediaType(document.mimeType());
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(open(file));
    }

    @GetMapping("/dokumente/{id}/bild")
    public ResponseEntity<Resource> thumbnail(@PathVariable long id) {
        Document document = require(id);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(open(document.thumbnail()));
    }

    @GetMapping("/dokumente/{id}/download/{kind}")
    public ResponseEntity<Resource> download(@PathVariable long id, @PathVariable String kind) {
        Document document = require(id);
        boolean archive = kind.equals("archiv") && document.hasArchive();
        String file = archive ? document.archive() : document.original();
        String extension = archive ? ".pdf" : FileNames.suffix(document.original());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filing.downloadName(document, extension), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(archive ? PDF : MediaType.parseMediaType(document.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(open(file));
    }
}
