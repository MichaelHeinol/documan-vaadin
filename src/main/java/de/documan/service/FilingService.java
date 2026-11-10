package de.documan.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import de.documan.config.DocuManProperties;
import de.documan.domain.Document;
import de.documan.repo.DocumentRepository;
import de.documan.repo.MasterRepository;

/**
 * Legt Dateien nach ihrer Kategorisierung ab und benennt sie danach.
 *
 * <p>Beispiel: originals/Finanzen/Energie/Stadtwerke Musterstadt_Rechnung_20260314.pdf. Die Ordner bildet nur die
 * Kategorie ab, ohne Kategorie liegt die Datei unter „Ohne Kategorie“.
 *
 * <p>Verschoben wird nur im Worker. So läuft es nie gleichzeitig mit einer Sicherung, und die
 * Weboberfläche muss bei einem umbenannten Absendernamen nicht auf tausende Dateien warten.
 */
@Service
public class FilingService {

    private static final Logger log = LoggerFactory.getLogger(FilingService.class);
    // Diese Zeichen verbieten Windows und SMB, auch wenn das Dateisystem des NAS sie erlaubt.
    private static final Pattern FORBIDDEN_CHARACTERS = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1f]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern EDGE_DOTS_AND_SPACES = Pattern.compile("^[ .]+|[ .]+$");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    // Verschlüsselte Synology-Ordner erlauben nur 143 Bytes.
    private static final int MAX_NAME_BYTES = 130;
    // Ein sehr langer Dokumenttyp soll den Absender nicht verdrängen.
    private static final int MAX_TYPE_BYTES = 40;
    private static final String FALLBACK_NAME = "Dokument";
    private static final String NO_CATEGORY = "Ohne Kategorie";
    // Bis zu zehn Kategoriestufen. Früher kamen Absendername und Dokumenttyp als Ordner dazu, beim Aufräumen
    // alter Ablagen zählen sie mit.
    private static final int FOLDER_LEVELS = CategoryPaths.MAX_LEVELS + 2;

    private final DocuManProperties properties;
    private final DocumentRepository documents;
    private final MediaStore media;
    private final MasterRepository master;
    private final TransactionTemplate transaction;
    private final Reindexer reindexer;

    public FilingService(DocuManProperties properties, DocumentRepository documents, MediaStore media,
            MasterRepository master, PlatformTransactionManager transactions, Reindexer reindexer) {
        this.reindexer = reindexer;
        this.properties = properties;
        this.documents = documents;
        this.media = media;
        this.master = master;
        this.transaction = new TransactionTemplate(transactions);
    }

    private record Moved(Path source, Path destination) {
    }

    public static String cleanName(String text) {
        return cleanName(text, MAX_NAME_BYTES);
    }

    private static String cleanName(String text, int limit) {
        String spaced = FORBIDDEN_CHARACTERS.matcher(text).replaceAll(" ");
        String name = WHITESPACE.matcher(spaced.strip()).replaceAll(" ");
        return EDGE_DOTS_AND_SPACES.matcher(limitBytes(name, limit)).replaceAll("");
    }

    private static String limitBytes(String name, int limit) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) {
            return name;
        }
        try {
            // Mitten im Zeichen abgeschnittene Bytes entfallen.
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.IGNORE)
                    .decode(ByteBuffer.wrap(Arrays.copyOf(bytes, limit)))
                    .toString();
        } catch (CharacterCodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Absender, Typ und Belegdatum wie der Titel. Fehlt eines davon, entfällt es. Ohne Absender und Typ steht
     * der Titel an ihrer Stelle.
     */
    public String fileStem(Document document) {
        LocalDate day = document.documentDate() != null ? document.documentDate()
                : document.added().atZone(properties.getTimeZone()).toLocalDate();
        String kind = document.documentTypeName() == null ? "" : cleanName(document.documentTypeName(), MAX_TYPE_BYTES);
        String tail = kind.isEmpty() ? day.format(FILE_DATE) : kind + "_" + day.format(FILE_DATE);
        String sender = document.correspondentName() != null ? document.correspondentName()
                : kind.isEmpty() ? document.title() : "";
        // Ein zu langer Absender wird gekürzt, Typ und Datum bleiben vollständig.
        String name = cleanName(sender, MAX_NAME_BYTES - tail.getBytes(StandardCharsets.UTF_8).length - 1);
        if (name.isEmpty()) {
            return kind.isEmpty() ? FALLBACK_NAME + "_" + tail : tail;
        }
        return name + "_" + tail;
    }

    /** Wie die abgelegte Datei mit ihrem Index. Noch nicht abgelegt zählt der künftige Name. */
    public String downloadName(Document document, String extension) {
        String expected = fileStem(document);
        String stored = FileNames.stem(Path.of(document.original()).getFileName().toString());
        boolean filed = stored.matches(Pattern.quote(expected) + "(_\\d+)?");
        return (filed ? stored : expected) + extension;
    }

    /** Die Kategoriestufen von oben nach unten, ohne Kategorie „Ohne Kategorie“. */
    public String folderOf(Document document) {
        return categoryFolder(new CategoryPaths(master.categories()).path(document.categoryId()), document);
    }

    /** Wie folderOf, mit den Namen der Kategoriestufen von oben nach unten. */
    public static String categoryFolder(List<String> categories, Document document) {
        List<String> parts = categories.stream().limit(CategoryPaths.MAX_LEVELS)
                .map(name -> name == null ? "" : cleanName(name))
                .filter(name -> !name.isEmpty())
                .toList();
        return parts.isEmpty() ? NO_CATEGORY : String.join("/", parts);
    }

    /** Nach einem Absturz liegt die eigene Datei schon am Ziel, der Datensatz aber noch nicht. */
    private boolean isFree(Document document, String folder, String stem) {
        String current = document.original();
        String name = "originals/" + folder + "/" + stem + FileNames.suffix(current);
        if (name.equals(current)) {
            return true;
        }
        if (documents.originalTaken(name, document.id())) {
            return false;
        }
        return !Files.exists(media.resolve(name)) || !Files.exists(media.resolve(current));
    }

    private String freeStem(Document document, String folder, String stem) {
        String candidate = stem;
        for (int number = 2; !isFree(document, folder, candidate); number++) {
            candidate = stem + "_" + number;
        }
        return candidate;
    }

    /** Eigene Methode, damit Tests einen scheiternden Umzug nachstellen können. */
    protected void rename(Path source, Path destination) throws IOException {
        Files.move(source, destination);
    }

    /** Verschiebt eine Datei und gibt ihren neuen Namen zurück. */
    private String move(String current, String topFolder, String folder, String stem, List<Moved> moved)
            throws IOException {
        if (current.isEmpty()) {
            return "";
        }
        String target = topFolder + "/" + folder + "/" + stem + FileNames.suffix(current);
        Path source = media.resolve(current);
        Path destination = media.resolve(target);
        if (target.equals(current)) {
            return current;
        }
        if (!Files.exists(source)) {
            return Files.exists(destination) ? target : current;
        }
        Files.createDirectories(destination.getParent());
        rename(source, destination);
        moved.add(new Moved(source, destination));
        return target;
    }

    private void undo(List<Moved> moved) {
        for (Moved step : moved.reversed()) {
            try {
                Files.move(step.destination(), step.source());
            } catch (IOException error) {
                log.error("{} konnte nicht zurückgeholt werden", step.destination(), error);
            }
        }
    }

    /**
     * Entfernt die geleerten Ordner über einer verschobenen Datei, höchstens bis unter originals, archive
     * oder thumbnails. Vor den Kategorien lagen Dateien weniger tief.
     */
    private void removeEmptyFolders(Path path) {
        Path root = media.resolve("").toAbsolutePath().normalize();
        Path folder = path.toAbsolutePath().normalize().getParent();
        try {
            for (int level = 0; level < FOLDER_LEVELS && folder != null && folder.startsWith(root)
                    && root.relativize(folder).getNameCount() > 1; level++) {
                Files.delete(folder);
                folder = folder.getParent();
            }
        } catch (DirectoryNotEmptyException stillInUse) {
            return;
        } catch (IOException notRemovable) {
            log.debug("{} bleibt bestehen", folder);
        }
    }

    /**
     * Bringt die Dateien eines Dokuments an den richtigen Ort. Vorher bekommt es seinen Titel aus Absendername,
     * Typ und Datum, das betrifft vor allem Dokumente aus der Zeit vor dieser Regel.
     */
    private void refile(Document found) {
        Document document = found;
        if (!documents.composeTitles(List.of(found.id()), properties.getTimeZone()).isEmpty()) {
            reindexer.reindex(found.id());
            document = documents.find(found.id()).orElse(found);
        }
        String folder = folderOf(document);
        String stem = freeStem(document, folder, fileStem(document));
        List<Moved> moved = new ArrayList<>();
        try {
            String original = move(document.original(), "originals", folder, stem, moved);
            String archive = move(document.archive(), "archive", folder, stem, moved);
            String thumbnail = move(document.thumbnail(), "thumbnails", folder, stem, moved);
            documents.saveFiled(document.id(), original, archive, thumbnail);
            moved.forEach(step -> removeEmptyFolders(step.source()));
        } catch (IOException error) {
            undo(moved);
            // Der alte Name bleibt gültig, ein erneuter Versuch würde nur die Warteschlange blockieren.
            log.warn("#{} konnte nicht umbenannt werden: {}", document.id(), error.toString());
            documents.clearFileSync(document.id());
        }
    }

    /** Verschiebt die Dateien des nächsten Dokuments. Gibt false zurück, wenn nichts ansteht. */
    public boolean refileNext() {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var document = documents.claimFileSync();
            document.ifPresent(this::refile);
            return document.isPresent();
        }));
    }
}
