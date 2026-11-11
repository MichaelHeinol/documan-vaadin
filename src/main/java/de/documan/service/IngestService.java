package de.documan.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import de.documan.config.DocuManProperties;
import de.documan.domain.Document;
import de.documan.domain.DocumentSource;
import de.documan.domain.MailHeaders;
import de.documan.repo.DocumentRepository;
import de.documan.repo.ImportRejectionRepository;
import de.documan.repo.MasterRepository;
import de.documan.repo.DocumentRepository.NewDocument;

/** Nimmt Dateien aus allen Quellen entgegen und legt sie in die Warteschlange. */
@Service
public class IngestService {

    /**
     * Zusatzangaben einer Quelle. categoryPath sind die Kategoriestufen von oben nach unten, etwa aus den
     * Unterordnern im Eingangsordner. Fehlende Stufen werden angelegt.
     */
    public record Options(String title, String sourceDetail, String addedBy, MailHeaders mail, Long correspondentId,
            Long documentTypeId, Collection<Long> tagIds, List<String> categoryPath) {

        public static final Options NONE =
                new Options(null, "", "", MailHeaders.NONE, null, null, List.of(), List.of());

        public static Options of(String addedBy, String sourceDetail) {
            return of(addedBy, sourceDetail, List.of());
        }

        public static Options of(String addedBy, String sourceDetail, List<String> categoryPath) {
            return new Options(null, sourceDetail, addedBy, MailHeaders.NONE, null, null, List.of(), categoryPath);
        }
    }

    public static final String CONSUME_LABEL = "Eingangsordner";
    public static final String MAIL_LABEL = "E-Mail";

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    // Diese Typen bekommen eine feste Endung, alle anderen behalten die ihres Dateinamens.
    private static final Map<String, String> KNOWN_EXTENSIONS = Map.of(
            "application/pdf", ".pdf",
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/tiff", ".tif",
            "image/webp", ".webp",
            "text/plain", ".txt",
            "text/csv", ".csv",
            "text/markdown", ".md");
    private static final int SNIFF_BYTES = 8192;
    private static final Pattern EXTENSION = Pattern.compile("\\.[a-z0-9]{1,10}");
    // Diese Endungen versprechen einen bekannten Typ. Stimmt der Inhalt nicht, gelten sie nicht.
    private static final Set<String> CLAIMED_EXTENSIONS =
            Set.of(".pdf", ".jpg", ".jpeg", ".png", ".tif", ".tiff", ".webp");
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_TITLE_LENGTH = 300;
    private static final int MAX_ADDED_BY_LENGTH = 150;
    private static final int MAX_MAIL_LENGTH = 500;
    private static final int MAX_RECIPIENTS_LENGTH = 1000;
    private static final Duration DETECT_TIMEOUT = Duration.ofSeconds(30);

    private final DocuManProperties properties;
    private final DocumentRepository documents;
    private final Reindexer reindexer;
    private final MediaStore media;
    private final ProcessRunner processes;
    private final MasterRepository master;
    private final ImportRejectionRepository rejections;

    public IngestService(DocuManProperties properties, DocumentRepository documents, Reindexer reindexer,
            MediaStore media, ProcessRunner processes, MasterRepository master, ImportRejectionRepository rejections) {
        this.rejections = rejections;
        this.master = master;
        this.properties = properties;
        this.documents = documents;
        this.reindexer = reindexer;
        this.media = media;
        this.processes = processes;
    }

    /** Erkennt den Dateityp am Inhalt, nie an der Endung. */
    public String detectMimeType(byte[] head) throws IOException {
        var result = processes.run(List.of("file", "--brief", "--mime-type", "-"), DETECT_TIMEOUT, Map.of(), head);
        if (result.exitCode() != 0) {
            throw new IOException("Dateityp konnte nicht bestimmt werden: " + result.lastErrorLine());
        }
        return result.stdout().strip();
    }

    private static String titleFromFilename(String filename) {
        String title = FileNames.stem(filename).replace('_', ' ').strip();
        return title.isEmpty() ? "Unbenanntes Dokument" : shorten(title, MAX_TITLE_LENGTH);
    }

    private static String shorten(String text, int length) {
        return text.length() > length ? text.substring(0, length) : text;
    }

    static String extension(String filename, String mimeType) {
        String known = KNOWN_EXTENSIONS.get(mimeType);
        if (known != null) {
            return known;
        }
        String suffix = FileNames.suffix(filename.replace('\\', '/')).toLowerCase(Locale.ROOT);
        return EXTENSION.matcher(suffix).matches() && !CLAIMED_EXTENSIONS.contains(suffix) ? suffix : ".bin";
    }

    private static String storageName(String filename, String extension) {
        String name = FileNames.valid(shorten(FileNames.stem(filename), MAX_NAME_LENGTH));
        return (name.isEmpty() ? "dokument" : name) + extension;
    }

    private static MailHeaders shortened(MailHeaders mail) {
        return new MailHeaders(shorten(mail.from(), MAX_MAIL_LENGTH), shorten(mail.to(), MAX_RECIPIENTS_LENGTH),
                shorten(mail.subject(), MAX_MAIL_LENGTH), mail.date(), shorten(mail.messageId(), MAX_MAIL_LENGTH));
    }

    private record Fingerprint(String checksum, long size, byte[] head) {
    }

    private static Fingerprint fingerprint(InputStreamSource source) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (InputStream stream = new DigestInputStream(source.getInputStream(), digest)) {
            byte[] head = stream.readNBytes(SNIFF_BYTES);
            long size = head.length + stream.transferTo(java.io.OutputStream.nullOutputStream());
            return new Fingerprint(HexFormat.of().formatHex(digest.digest()), size, head);
        }
    }

    /**
     * Speichert die Datei als Original und reiht sie zur Verarbeitung ein.
     *
     * <p>Der Originalinhalt wird nie verändert. OCR und Vorschau entstehen als eigene Dateien,
     * das Original bleibt immer im Klartext auf dem Datenträger.
     *
     * <p>Abgelehnte Dateien merkt sich DocuMan mit Grund unter „Fehlerhafte Importe“, egal woher sie kamen.
     */
    public Document ingest(InputStreamSource source, String filename, DocumentSource origin, Options options)
            throws IngestException {
        try {
            return store(source, filename, origin, options);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } catch (IngestException rejected) {
            Long existing = rejected instanceof DuplicateDocumentException duplicate ? duplicate.existing().id() : null;
            rejections.record(origin, filename, rejected.getMessage(), options.addedBy(), options.sourceDetail(),
                    existing);
            throw rejected;
        }
    }

    private Document store(InputStreamSource source, String filename, DocumentSource origin, Options options)
            throws IOException, IngestException {
        Fingerprint file = fingerprint(source);
        if (file.size() == 0) {
            throw new IngestException("Die Datei ist leer.");
        }
        if (file.size() > properties.getMaxFileMb() * 1024L * 1024L) {
            throw new IngestException("Die Datei ist größer als " + properties.getMaxFileMb() + " MB.");
        }
        var existing = documents.findByChecksum(file.checksum());
        if (existing.isPresent()) {
            throw new DuplicateDocumentException(existing.get());
        }

        String mimeType = detectMimeType(file.head());
        String extension = extension(filename, mimeType);

        String original;
        try (InputStream stream = source.getInputStream()) {
            original = media.storeOriginal(stream, file.checksum(), storageName(filename, extension));
        }
        String title = options.title() == null ? titleFromFilename(filename) : shorten(options.title(), MAX_TITLE_LENGTH);
        long id;
        try {
            id = documents.insert(new NewDocument(title, original, shorten(filename, 500), mimeType, file.size(),
                    file.checksum(), origin, shorten(options.sourceDetail(), 500),
                    shorten(options.addedBy(), MAX_ADDED_BY_LENGTH), shortened(options.mail()),
                    options.correspondentId(), options.documentTypeId()));
        } catch (DuplicateKeyException parallel) {
            // Dieselbe Datei kam parallel über einen anderen Weg herein.
            media.deleteQuietly(original);
            throw new DuplicateDocumentException(documents.findByChecksum(file.checksum()).orElseThrow());
        }
        documents.setTags(id, options.tagIds());
        master.categoryChain(options.categoryPath())
                .ifPresent(category -> documents.setCategory(new Long[] {id}, category));
        reindexer.reindex(id);
        log.info("Angenommen: {} ({}, {})", filename, mimeType, origin.value());
        return documents.find(id).orElseThrow();
    }
}
