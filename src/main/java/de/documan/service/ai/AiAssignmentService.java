package de.documan.service.ai;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import de.documan.config.DocuManProperties;
import de.documan.domain.Document;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.AiRepository;
import de.documan.repo.DocumentRepository;
import de.documan.repo.MasterRepository;
import de.documan.service.CategoryPaths;
import de.documan.service.Reindexer;

/**
 * KI-Zuordnung: Nach der Verarbeitung füllt sie leere Felder eines Dokuments.
 *
 * <p>Zuerst die lernende Zuordnung aus den eigenen Dokumenten, schnell und ohne zusätzlichen Dienst. Ist
 * Ollama eingerichtet, sieht sich danach ein Sprachmodell an, was noch fehlt, auch neue Absendernamen und das
 * Dokumentdatum. Die Regeln der Stammdaten gehen immer vor, Gesetztes wird nie überschrieben. Alles, was die KI
 * setzt, bleibt markiert, bis jemand das Dokument speichert.
 *
 * <p>Läuft nur im Worker.
 */
@Service
public class AiAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AiAssignmentService.class);
    /** Neu gelernt wird höchstens so oft, solange sich etwas geändert hat. */
    private static final long RELEARN_NANOS = 10L * 60 * 1_000_000_000L;
    /** Nach einem Fehler des Sprachmodells wird so lange gewartet. */
    private static final long RETRY_NANOS = 5L * 60 * 1_000_000_000L;
    private static final int PROMPT_TEXT = 6000;
    private static final int PROMPT_NAMES = 300;
    private static final int MAX_TAGS = 5;
    private static final int MAX_NEW_NAME = 100;

    private final DocuManProperties properties;
    private final AiRepository ai;
    private final DocumentRepository documents;
    private final MasterRepository master;
    private final Reindexer reindexer;
    private final OllamaClient ollama;
    private LearnedModel model;
    private String modelStamp = "";
    private long learnedAt;
    // null: kein Fehler, sonst der Zeitpunkt (System.nanoTime) des nächsten Versuchs.
    private Long retryAfter;

    public AiAssignmentService(DocuManProperties properties, AiRepository ai, DocumentRepository documents,
            MasterRepository master, Reindexer reindexer, OllamaClient ollama) {
        this.properties = properties;
        this.ai = ai;
        this.documents = documents;
        this.master = master;
        this.reindexer = reindexer;
        this.ollama = ollama;
    }

    /** Lernt neu, wenn noch kein Modell da ist oder sich die Beispiele geändert haben, höchstens alle 10 Minuten. */
    synchronized LearnedModel model() {
        long now = System.nanoTime();
        if (model != null && now - learnedAt < RELEARN_NANOS) {
            return model;
        }
        String stamp = ai.trainingStamp();
        if (model == null || !stamp.equals(modelStamp)) {
            model = LearnedModel.train(ai.trainingRows());
            modelStamp = stamp;
            ai.learned(model.size());
            log.info("KI-Zuordnung hat aus {} Dokumenten gelernt", model.size());
        }
        learnedAt = now;
        return model;
    }

    /** Verwirft das gelernte Modell, etwa in Tests nach neuen Beispielen. */
    public synchronized void forget() {
        model = null;
        modelStamp = "";
        retryAfter = null;
    }

    /** Direkt nach der Verarbeitung: lernende Zuordnung, danach bei Bedarf das Sprachmodell vormerken. */
    public void afterProcessing(long id) {
        Optional<Document> found = documents.find(id);
        if (found.isEmpty()) {
            return;
        }
        if (properties.isAiLearning()) {
            try {
                applyLearned(found.get());
            } catch (RuntimeException error) {
                log.warn("KI-Zuordnung für #{} fehlgeschlagen: {}", id, error.toString());
            }
        }
        if (ollama.configured() && stillMissing(documents.find(id).orElse(found.get()))) {
            ai.requestLanguageModel(id);
        }
    }

    private static boolean stillMissing(Document document) {
        return document.correspondentId() == null || document.documentTypeId() == null || document.categoryId() == null
                || document.documentDate() == null || document.tags().isEmpty();
    }

    private void applyLearned(Document document) {
        LearnedModel.Prediction prediction = model().predict(ai.content(document.id()), document.id());
        int assigned = 0;
        if (prediction.correspondentId() != null && ai.assignCorrespondent(document.id(), prediction.correspondentId())) {
            assigned++;
        }
        if (prediction.typeId() != null && ai.assignType(document.id(), prediction.typeId())) {
            assigned++;
        }
        if (prediction.categoryId() != null && ai.assignCategory(document.id(), prediction.categoryId())) {
            assigned++;
        }
        if (document.tags().isEmpty()) {
            assigned += ai.assignTags(document.id(), prediction.tagIds());
        }
        changed(document.id(), assigned);
    }

    private void changed(long id, int assigned) {
        if (assigned > 0) {
            ai.countAssigned(assigned);
            documents.requestFileSync(new Long[] {id});
            documents.composeTitles(List.of(id), properties.getTimeZone());
            reindexer.reindex(id);
        }
    }

    /**
     * Gibt das nächste vorgemerkte Dokument an das Sprachmodell. Gibt false zurück, wenn nichts ansteht,
     * Ollama fehlt oder nach einem Fehler noch gewartet wird.
     */
    public boolean languageModelNext() {
        if (!ollama.configured() || retryAfter != null && System.nanoTime() - retryAfter < 0) {
            return false;
        }
        Optional<Long> next = ai.nextForLanguageModel();
        if (next.isEmpty()) {
            return false;
        }
        long id = next.get();
        try {
            Optional<Document> document = documents.find(id);
            if (document.isPresent()) {
                askLanguageModel(document.get());
            }
            ai.modelReachable();
            ai.languageModelDone(id);
        } catch (OllamaClient.UnavailableException unavailable) {
            ai.modelFailed(unavailable.getMessage());
            log.warn("Sprachmodell nicht verfügbar, nächster Versuch in 5 Minuten: {}", unavailable.getMessage());
            retryAfter = System.nanoTime() + RETRY_NANOS;
            return false;
        } catch (IOException | RuntimeException error) {
            // Eine unbrauchbare Antwort wird nicht wiederholt, sonst hinge die Warteschlange an diesem Dokument.
            ai.modelFailed("#" + id + ": " + error.getMessage());
            log.warn("Sprachmodell konnte #{} nicht zuordnen: {}", id, error.toString());
            ai.languageModelDone(id);
        }
        return true;
    }

    private void askLanguageModel(Document document) throws IOException {
        if (!stillMissing(document)) {
            return;
        }
        List<MasterItem> correspondents = master.all(MasterKind.CORRESPONDENT);
        List<MasterItem> types = master.all(MasterKind.DOCUMENT_TYPE);
        List<MasterItem> tags = master.all(MasterKind.TAG);
        CategoryPaths paths = new CategoryPaths(master.categories());
        List<String> categoryLabels = paths.ordered().stream().map(Map.Entry::getValue).toList();

        String system = """
                Du ordnest Dokumente eines Privathaushalts für ein Dokumentenarchiv. Antworte ausschließlich mit dem
                verlangten JSON. Lass ein Feld leer, wenn du dir nicht sicher bist.
                absender: Firma, Behörde oder Person, die das Dokument ausgestellt hat. Nimm genau einen Namen aus
                der Liste der bekannten Absender, wenn er passt, sonst den Namen, wie er im Dokument steht.
                dokumenttyp: genau einer aus der Liste der Dokumenttypen oder leer.
                kategorie: genau einer der Pfade aus der Liste der Kategorien oder leer.
                tags: höchstens %d aus der Liste der Tags, keine neuen.
                datum: das Belegdatum des Dokuments als JJJJ-MM-TT, kein Fälligkeits- oder Druckdatum, sonst leer.
                """.formatted(MAX_TAGS);
        String user = "Bekannte Absender: " + names(correspondents) + "\n"
                + "Dokumenttypen: " + names(types) + "\n"
                + "Kategorien: " + String.join("; ", categoryLabels.stream().limit(PROMPT_NAMES).toList()) + "\n"
                + "Tags: " + names(tags) + "\n\n"
                + "Dateiname: " + document.originalFilename() + "\n"
                + "Text des Dokuments:\n" + cut(ai.content(document.id()), PROMPT_TEXT);
        JsonNode answer = ollama.ask(system, user, schema());

        int assigned = 0;
        String sender = answer.path("absender").asString("").strip();
        if (document.correspondentId() == null && !sender.isEmpty() && sender.length() <= MAX_NEW_NAME) {
            Long id = find(correspondents, sender)
                    .orElseGet(() -> master.getOrCreate(MasterKind.CORRESPONDENT, sender).map(MasterItem::id).orElse(null));
            if (id != null && ai.assignCorrespondent(document.id(), id)) {
                assigned++;
            }
        }
        Optional<Long> type = find(types, answer.path("dokumenttyp").asString(""));
        if (document.documentTypeId() == null && type.isPresent() && ai.assignType(document.id(), type.get())) {
            assigned++;
        }
        String category = answer.path("kategorie").asString("").strip();
        if (document.categoryId() == null && !category.isEmpty()) {
            for (Map.Entry<Long, String> entry : paths.ordered()) {
                if (normal(entry.getValue()).equals(normal(category)) && ai.assignCategory(document.id(), entry.getKey())) {
                    assigned++;
                    break;
                }
            }
        }
        if (document.tags().isEmpty()) {
            Set<Long> chosen = new LinkedHashSet<>();
            for (JsonNode tag : answer.path("tags")) {
                find(tags, tag.asString("")).ifPresent(chosen::add);
            }
            assigned += ai.assignTags(document.id(), chosen.stream().limit(MAX_TAGS).toList());
        }
        LocalDate date = date(answer.path("datum").asString(""));
        if (document.documentDate() == null && date != null && ai.assignDate(document.id(), date)) {
            assigned++;
        }
        changed(document.id(), assigned);
    }

    private static Map<String, Object> schema() {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("absender", text);
        properties.put("dokumenttyp", text);
        properties.put("kategorie", text);
        properties.put("tags", Map.of("type", "array", "items", text));
        properties.put("datum", text);
        return Map.of("type", "object", "properties", properties, "required", new ArrayList<>(properties.keySet()));
    }

    private static String names(List<MasterItem> items) {
        return String.join("; ", items.stream().limit(PROMPT_NAMES).map(MasterItem::name).toList());
    }

    private static String normal(String text) {
        return text.toLowerCase(Locale.GERMAN).replaceAll("\\s*/\\s*", "/").replaceAll("\\s+", " ").strip();
    }

    private static Optional<Long> find(List<MasterItem> items, String name) {
        String wanted = normal(name);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return items.stream().filter(item -> normal(item.name()).equals(wanted)).map(MasterItem::id).findFirst();
    }

    /** Ein plausibles Belegdatum, nicht vor 1900 und höchstens ein Jahr in der Zukunft. */
    static LocalDate date(String value) {
        try {
            LocalDate date = LocalDate.parse(value.strip());
            return date.getYear() >= 1900 && date.isBefore(LocalDate.now().plusYears(1)) ? date : null;
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    private static String cut(String text, int length) {
        return text.length() > length ? text.substring(0, length) : text;
    }
}
