package de.documan.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import de.documan.config.DocuManProperties;
import de.documan.domain.Document;

/** Ablage der Dateien: Originale, durchsuchbare Archivfassungen und Vorschaubilder. */
@Component
public class MediaStore {

    private static final DateTimeFormatter MONTH_FOLDER = DateTimeFormatter.ofPattern("yyyy/MM");

    private final DocuManProperties properties;

    public MediaStore(DocuManProperties properties) {
        this.properties = properties;
    }

    public Path resolve(String relative) {
        return properties.getMediaRoot().resolve(relative);
    }

    /** Speichert ein neues Original und gibt den relativen Pfad zurück. */
    public String storeOriginal(InputStream content, String checksum, String storageName) throws IOException {
        String month = ZonedDateTime.now(properties.getTimeZone()).format(MONTH_FOLDER);
        String stem = "originals/" + month + "/" + checksum.substring(0, 8) + "-" + FileNames.stem(storageName);
        String suffix = FileNames.suffix(storageName);
        String relative = stem + suffix;
        Path target = resolve(relative);
        Files.createDirectories(target.getParent());
        for (int counter = 2; Files.exists(target); counter++) {
            relative = stem + "_" + counter + suffix;
            target = resolve(relative);
        }
        Files.copy(content, target);
        return relative;
    }

    /** Legt eine erzeugte Datei ab und ersetzt eine vorhandene Fassung im selben Schritt. */
    public String storeGenerated(Path source, String relative) throws IOException {
        Path target = resolve(relative);
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".neu");
        Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return relative;
    }

    public void deleteQuietly(String relative) {
        try {
            if (!relative.isEmpty()) {
                Files.deleteIfExists(resolve(relative));
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public void deleteFiles(Document document) {
        deleteQuietly(document.original());
        deleteQuietly(document.archive());
        deleteQuietly(document.thumbnail());
    }
}
