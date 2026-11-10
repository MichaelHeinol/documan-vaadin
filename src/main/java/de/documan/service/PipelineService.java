package de.documan.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.documan.config.DocuManProperties;
import de.documan.domain.Document;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.DocumentRepository;
import de.documan.repo.DocumentRepository.Processed;
import de.documan.repo.MasterRepository;

/** Macht aus einem Original ein durchsuchbares Dokument. */
@Service
public class PipelineService {

    public static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);
    // Weniger Zeichen auf einer Seite gelten als Scan ohne brauchbare Textebene.
    private static final int MIN_PAGE_CHARACTERS = 20;
    private static final int THUMBNAIL_SIZE = 640;
    // Kurze Schreiben erreichen nur eine Konfidenz um 16, knapp über OCRmyPDFs Standard von 14.
    private static final String ROTATE_PAGES_THRESHOLD = "10";
    // OCRmyPDF schreibt bei Exit-Code 10 trotzdem ein gültiges PDF, nur nicht PDF/A-konform.
    private static final int OCR_EXIT_PDFA_FAILED = 10;
    private static final Duration EXTRACT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration THUMBNAIL_TIMEOUT = Duration.ofMinutes(2);
    // Liest Office- und andere Formate. Dasselbe Programm nutzt die Django-Fassung.
    private static final String TEXT_EXTRACTOR = "documan-textextract";

    private final DocuManProperties properties;
    private final DocumentRepository documents;
    private final MasterRepository master;
    private final Reindexer reindexer;
    private final MediaStore media;
    private final ProcessRunner processes;

    public PipelineService(DocuManProperties properties, DocumentRepository documents, MasterRepository master,
            Reindexer reindexer, MediaStore media, ProcessRunner processes) {
        this.properties = properties;
        this.documents = documents;
        this.master = master;
        this.reindexer = reindexer;
        this.media = media;
        this.processes = processes;
    }

    public static String cleanText(String text) {
        // PostgreSQL kann keine NUL-Zeichen speichern, kaputte PDFs liefern sie aber.
        return text.replace("\u0000", "").strip();
    }

    /** Liefert den Text je Seite, oder null, wenn das PDF nicht lesbar ist. */
    public List<String> extractPdfPages(Path pdf) throws IOException {
        var result = processes.run(List.of("pdftotext", "-enc", "UTF-8", pdf.toString(), "-"), EXTRACT_TIMEOUT);
        if (result.exitCode() != 0) {
            return null;
        }
        // pdftotext beendet jede Seite mit einem Seitenvorschub.
        List<String> pages = new ArrayList<>(Arrays.asList(result.stdout().split("\f", -1)));
        pages.removeLast();
        return pages;
    }

    private static boolean needsOcr(List<String> pages) {
        return pages.stream().anyMatch(page -> visibleCharacters(page) < MIN_PAGE_CHARACTERS);
    }

    private static long visibleCharacters(String page) {
        return page.codePoints()
                .filter(character -> !Character.isWhitespace(character) && !Character.isSpaceChar(character))
                .count();
    }

    /** Erzeugt eine Archivfassung mit Textebene. Gibt die Fehlermeldung zurück oder null. */
    private String runOcr(Path input, Path output) throws IOException {
        var result = processes.run(List.of(
                "ocrmypdf",
                "--skip-text",
                "--rotate-pages",
                "--rotate-pages-threshold", ROTATE_PAGES_THRESHOLD,
                "--deskew",
                "--language", properties.getOcrLanguages(),
                "--jobs", String.valueOf(properties.getOcrJobs()),
                "--output-type", properties.getOcrOutputType(),
                "--optimize", "1",
                "--quiet",
                input.toString(),
                output.toString()), Duration.ofSeconds(properties.getOcrTimeoutSeconds()));
        if (result.exitCode() == 0 || (result.exitCode() == OCR_EXIT_PDFA_FAILED && Files.exists(output))) {
            return null;
        }
        String reason = result.lastErrorLine();
        return reason.isEmpty() ? "OCRmyPDF endete mit Code " + result.exitCode() + "." : reason;
    }

    private Path renderThumbnail(Path pdf, Path outputStem) throws IOException {
        var result = processes.run(List.of(
                "pdftoppm",
                "-jpeg",
                "-jpegopt", "quality=80",
                "-singlefile",
                "-f", "1",
                "-l", "1",
                "-scale-to", String.valueOf(THUMBNAIL_SIZE),
                pdf.toString(),
                outputStem.toString()), THUMBNAIL_TIMEOUT);
        Path thumbnail = outputStem.resolveSibling(outputStem.getFileName() + ".jpg");
        return result.exitCode() == 0 && Files.exists(thumbnail) ? thumbnail : null;
    }

    private Path preparePdf(Document document, Path source, Path workspace) throws ProcessingException {
        if (document.isPdf()) {
            return source;
        }
        Path pdf = workspace.resolve("input.pdf");
        try {
            ImageToPdf.convert(source, pdf);
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            throw new ProcessingException("Bild konnte nicht gelesen werden: " + error.getMessage());
        }
        return pdf;
    }

    private record PdfResult(String text, int pages, String archive, String thumbnail) {
    }

    private PdfResult processPdf(Document document, Path source, Path workspace)
            throws ProcessingException, IOException {
        Path input = preparePdf(document, source, workspace);
        List<String> pages = extractPdfPages(input);
        if (pages == null) {
            throw new ProcessingException(
                    "Das PDF ist beschädigt oder passwortgeschützt. Das Original bleibt erhalten.");
        }

        Path archivePdf = null;
        if (needsOcr(pages) || pages.isEmpty()) {
            Path output = workspace.resolve("archive.pdf");
            String problem = runOcr(input, output);
            if (problem == null) {
                archivePdf = output;
                List<String> recognized = extractPdfPages(output);
                pages = recognized != null ? recognized : pages;
            } else if (pages.stream().allMatch(String::isBlank)) {
                throw new ProcessingException("OCR fehlgeschlagen: " + problem);
            } else {
                // Z. B. digital signierte PDFs: Vorhandener Text reicht, Original bleibt maßgeblich.
                log.warn("OCR für {} übersprungen: {}", document.originalFilename(), problem);
            }
        }

        // Erzeugte Dateien heißen wie das Original und unterscheiden sich nur im Ordner.
        String stem = FileNames.stem(document.original());
        String folder = document.originalFolder();
        String archive = document.archive();
        if (archivePdf != null) {
            archive = media.storeGenerated(archivePdf, "archive/" + folder + "/" + stem + ".pdf");
        }
        String thumbnail = document.thumbnail();
        Path image = renderThumbnail(archivePdf != null ? archivePdf : input, workspace.resolve("thumbnail"));
        if (image != null) {
            thumbnail = media.storeGenerated(image, "thumbnails/" + folder + "/" + stem + ".jpg");
        }
        return new PdfResult(String.join("\n\n", pages), pages.size(), archive, thumbnail);
    }

    static String readTextFile(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        int bom = raw.length >= 3 && raw[0] == (byte) 0xEF && raw[1] == (byte) 0xBB && raw[2] == (byte) 0xBF ? 3 : 0;
        for (Charset charset : List.of(StandardCharsets.UTF_8, Charset.forName("windows-1252"))) {
            try {
                return charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw, bom, raw.length - bom))
                        .toString();
            } catch (CharacterCodingException tryNext) {
                continue;
            }
        }
        return new String(raw, StandardCharsets.ISO_8859_1);
    }

    /** Text aus Formaten, die DocuMan unverändert ablegt. Unlesbares ergibt leeren Text. */
    public String extractText(Path source, String mimeType) throws IOException {
        var result = processes.run(List.of(TEXT_EXTRACTOR, source.toString(), mimeType), EXTRACT_TIMEOUT);
        if (result.exitCode() != 0) {
            log.warn("Text aus {} nicht lesbar: {}", source.getFileName(), result.lastErrorLine());
            return "";
        }
        return result.stdout();
    }

    /**
     * Führt die Verarbeitung aus. Metadaten des Benutzers bleiben unangetastet.
     *
     * <p>Nur PDFs und Bilder bekommen eine Archivfassung. Alle anderen Formate bleiben, wie sie sind, ihr
     * Text kommt aus textextract.
     */
    protected Processed processDocument(Document document) throws ProcessingException, IOException {
        Path source = media.resolve(document.original());
        Path workspace = Files.createTempDirectory("documan-");
        try {
            String text;
            Integer pageCount = null;
            String archive = document.archive();
            String thumbnail = document.thumbnail();
            if (document.isPdf() || document.isOcrImage()) {
                PdfResult pdf = processPdf(document, source, workspace);
                text = pdf.text();
                pageCount = pdf.pages();
                archive = pdf.archive();
                thumbnail = pdf.thumbnail();
            } else if (document.isText()) {
                text = readTextFile(source);
            } else {
                text = extractText(source, document.mimeType());
            }
            String content = cleanText(text);
            LocalDate date = document.documentDate() != null ? null
                    : DateFinder.find(content, LocalDate.now(properties.getTimeZone()));
            Assignment assignment = assign(document, content);
            documents.addTags(document.id(), assignment.tagIds());
            return new Processed(content, pageCount, archive, thumbnail, date, assignment.correspondentId(),
                    assignment.documentTypeId());
        } finally {
            deleteTree(workspace);
        }
    }

    /** Vorschläge der Regeln. Nur leere Felder werden gefüllt, manuell Gesetztes bleibt. */
    public record Assignment(Long correspondentId, Long documentTypeId, List<Long> tagIds) {
    }

    public Assignment assign(Document document, String content) {
        String text = document.title() + "\n" + content;
        Long correspondent = document.correspondentId() != null ? null
                : idOf(Matching.firstMatch(master.withRules(MasterKind.CORRESPONDENT), text));
        Long documentType = document.documentTypeId() != null ? null
                : idOf(Matching.firstMatch(master.withRules(MasterKind.DOCUMENT_TYPE), text));
        List<Long> tags = master.withRules(MasterKind.TAG).stream()
                .filter(rule -> Matching.matchPosition(rule, text).isPresent())
                .map(MasterItem::id)
                .toList();
        return new Assignment(correspondent, documentType, tags);
    }

    private static Long idOf(MasterItem item) {
        return item == null ? null : item.id();
    }

    /** Verarbeitet das nächste Dokument. Gibt false zurück, wenn nichts ansteht. */
    public boolean processNext() {
        var claimed = documents.claimNext();
        if (claimed.isEmpty()) {
            return false;
        }
        Document document = claimed.get();
        long started = System.nanoTime();
        log.info("Verarbeite #{}: {}", document.id(), document.originalFilename());
        try {
            documents.saveProcessed(document.id(), processDocument(document));
            log.info("#{} fertig in {} s", document.id(),
                    String.format("%.1f", (System.nanoTime() - started) / 1e9));
        } catch (ProcessingException error) {
            documents.markFailed(document.id(), error.getMessage());
            log.warn("#{} fehlgeschlagen: {}", document.id(), error.getMessage());
        } catch (IOException | RuntimeException error) {
            documents.markFailed(document.id(), "Unerwarteter Fehler: " + error.getMessage());
            log.error("#{} fehlgeschlagen", document.id(), error);
        }
        reindexer.reindex(document.id());
        return true;
    }

    /**
     * Holt Dokumente zurück, die beim letzten Beenden mitten in der Verarbeitung waren.
     *
     * <p>Ein Dokument, das den Worker wiederholt abstürzen lässt (z. B. Speicher voll),
     * wird nach {@link #MAX_ATTEMPTS} als fehlgeschlagen markiert statt endlos neu zu starten.
     */
    public int recoverInterrupted() {
        documents.failExhausted(MAX_ATTEMPTS,
                "Verarbeitung wurde mehrfach unterbrochen, möglicherweise zu wenig Arbeitsspeicher.");
        return documents.requeueInterrupted();
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
