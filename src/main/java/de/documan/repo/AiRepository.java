package de.documan.repo;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Daten der KI-Zuordnung: Trainingsdaten, die Markierung gesetzter Felder und der Zustand für die Übersicht.
 *
 * <p>Was die KI setzt, steht in {@code document.ai_fields} (correspondent, type, category, date) und
 * {@code document_tag.by_ai}. Speichert jemand das Dokument, gilt alles als bestätigt und zählt danach als
 * Lernbeispiel.
 */
@Repository
public class AiRepository {

    public static final String CORRESPONDENT = "correspondent";
    public static final String TYPE = "type";
    public static final String CATEGORY = "category";
    public static final String DATE = "date";
    /** So viel Text je Dokument fließt ins Lernen ein, der Anfang trägt Absender und Betreff. */
    public static final int TRAINING_TEXT = 8000;

    /**
     * Ein Lernbeispiel. Von der KI gesetzte, unbestätigte Werte fehlen darin, damit sich Fehler nicht
     * selbst bestätigen.
     */
    public record TrainingRow(long id, String content, Long correspondentId, Long typeId, Long categoryId,
            Set<Long> tagIds) {
    }

    public record Status(Instant learnedAt, int learnedDocuments, Instant modelOkAt, String modelError,
            Instant modelErrorAt, int assigned) {
    }

    private final JdbcClient jdbc;

    public AiRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<TrainingRow> trainingRows() {
        return jdbc.sql("""
                SELECT d.id, left(d.content, ?) AS content,
                       CASE WHEN position('correspondent' IN d.ai_fields) = 0 THEN d.correspondent_id END AS correspondent_id,
                       CASE WHEN position('type' IN d.ai_fields) = 0 THEN d.document_type_id END AS document_type_id,
                       CASE WHEN position('category' IN d.ai_fields) = 0 THEN d.category_id END AS category_id,
                       ARRAY(SELECT link.tag_id FROM document_tag link
                             WHERE link.document_id = d.id AND NOT link.by_ai) AS tag_ids
                FROM document d
                WHERE d.status = 'done' AND d.content <> ''
                ORDER BY d.id
                """)
                .param(TRAINING_TEXT)
                .query((row, index) -> new TrainingRow(row.getLong("id"), row.getString("content"),
                        Rows.nullableLong(row, "correspondent_id"), Rows.nullableLong(row, "document_type_id"),
                        Rows.nullableLong(row, "category_id"), ids(row.getArray("tag_ids"))))
                .list();
    }

    private static Set<Long> ids(Array array) throws SQLException {
        Set<Long> ids = new LinkedHashSet<>();
        if (array != null) {
            Arrays.stream((Object[]) array.getArray()).map(value -> ((Number) value).longValue()).forEach(ids::add);
        }
        return ids;
    }

    /** Ändert sich, sobald sich an den Lernbeispielen etwas getan hat. */
    public String trainingStamp() {
        return jdbc.sql("""
                SELECT concat_ws('-',
                    (SELECT concat_ws('/', count(*), max(modified)) FROM document WHERE status = 'done'),
                    (SELECT count(*) FROM document_tag WHERE NOT by_ai))
                """).query(String.class).single();
    }

    public String content(long id) {
        return jdbc.sql("SELECT left(content, ?) FROM document WHERE id = ?").params(TRAINING_TEXT, id)
                .query(String.class).optional().orElse("");
    }

    /** Ein zugeordnetes Dokument als Beispiel für das Sprachmodell, nur mit bestätigten Werten. */
    public record Example(long id, String filename, String excerpt, Long correspondentId, Long typeId,
            Long categoryId, Set<Long> tagIds) {
    }

    /** Die Beispiele in der Reihenfolge der ids, mit dem Textanfang in der gegebenen Länge. */
    public List<Example> examples(List<Long> ids, int excerpt) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Example> found = jdbc.sql("""
                SELECT d.id, d.original_filename, left(d.content, ?) AS excerpt,
                       CASE WHEN position('correspondent' IN d.ai_fields) = 0 THEN d.correspondent_id END AS correspondent_id,
                       CASE WHEN position('type' IN d.ai_fields) = 0 THEN d.document_type_id END AS document_type_id,
                       CASE WHEN position('category' IN d.ai_fields) = 0 THEN d.category_id END AS category_id,
                       ARRAY(SELECT link.tag_id FROM document_tag link
                             WHERE link.document_id = d.id AND NOT link.by_ai) AS tag_ids
                FROM document d WHERE d.id = ANY(?)
                """)
                .params(excerpt, ids.toArray(Long[]::new))
                .query((row, index) -> new Example(row.getLong("id"), row.getString("original_filename"),
                        row.getString("excerpt"), Rows.nullableLong(row, "correspondent_id"),
                        Rows.nullableLong(row, "document_type_id"), Rows.nullableLong(row, "category_id"),
                        ids(row.getArray("tag_ids"))))
                .list();
        return ids.stream().flatMap(id -> found.stream().filter(example -> example.id() == id)).toList();
    }

    private void mark(long id, String field) {
        jdbc.sql("""
                UPDATE document SET ai_fields = CASE WHEN ai_fields = '' THEN ? ELSE ai_fields || ',' || ? END
                WHERE id = ? AND position(? IN ai_fields) = 0
                """).params(field, field, id, field).update();
    }

    /** Setzt den Absendernamen nur, wenn er noch fehlt, und markiert ihn. Gibt zurück, ob gesetzt wurde. */
    public boolean assignCorrespondent(long id, long correspondentId) {
        return assign(id, "correspondent_id", CORRESPONDENT, correspondentId);
    }

    public boolean assignType(long id, long typeId) {
        return assign(id, "document_type_id", TYPE, typeId);
    }

    /** Eine eigene Kategorie nur, wenn weder das Dokument noch sein Dokumenttyp eine hat. */
    public boolean assignCategory(long id, long categoryId) {
        boolean set = jdbc.sql("""
                UPDATE document d SET category_id = ? WHERE d.id = ? AND d.category_id IS NULL
                  AND NOT EXISTS (SELECT 1 FROM document_type t WHERE t.id = d.document_type_id
                                  AND t.category_id IS NOT NULL)
                """).params(categoryId, id).update() > 0;
        if (set) {
            mark(id, CATEGORY);
        }
        return set;
    }

    public boolean assignDate(long id, LocalDate date) {
        boolean set = jdbc.sql("UPDATE document SET document_date = ? WHERE id = ? AND document_date IS NULL")
                .params(date, id).update() > 0;
        if (set) {
            mark(id, DATE);
        }
        return set;
    }

    private boolean assign(long id, String column, String field, long value) {
        boolean set = jdbc.sql("UPDATE document SET " + column + " = ? WHERE id = ? AND " + column + " IS NULL")
                .params(value, id).update() > 0;
        if (set) {
            mark(id, field);
        }
        return set;
    }

    /** Ergänzt Tags als KI-Vorschlag. Vorhandene bleiben, wie sie sind. Gibt die Zahl neuer Tags zurück. */
    public int assignTags(long id, Collection<Long> tagIds) {
        int added = 0;
        for (long tag : tagIds) {
            added += jdbc.sql("""
                    INSERT INTO document_tag (document_id, tag_id, by_ai) VALUES (?, ?, true) ON CONFLICT DO NOTHING
                    """).params(id, tag).update();
        }
        return added;
    }

    /** Der Benutzer hat das Dokument gespeichert: Alles gilt als bestätigt, das Sprachmodell kommt nicht mehr. */
    public void confirm(Collection<Long> ids) {
        Long[] selected = ids.toArray(Long[]::new);
        jdbc.sql("UPDATE document SET ai_fields = '', ai_pending = false WHERE id = ANY(?)").param(selected).update();
        jdbc.sql("UPDATE document_tag SET by_ai = false WHERE by_ai AND document_id = ANY(?)").param(selected)
                .update();
    }

    /** Ein einzelnes Feld wurde bestätigt, etwa durch die Stapelbearbeitung. */
    public void confirmField(Collection<Long> ids, String field) {
        jdbc.sql("""
                UPDATE document SET ai_fields = array_to_string(array_remove(string_to_array(ai_fields, ','), ?), ',')
                WHERE id = ANY(?) AND position(? IN ai_fields) > 0
                """).params(field, ids.toArray(Long[]::new), field).update();
    }

    public void confirmTag(Collection<Long> ids, long tagId) {
        jdbc.sql("UPDATE document_tag SET by_ai = false WHERE tag_id = ? AND document_id = ANY(?)")
                .params(tagId, ids.toArray(Long[]::new)).update();
    }

    public Set<String> aiFields(long id) {
        String fields = jdbc.sql("SELECT ai_fields FROM document WHERE id = ?").param(id).query(String.class)
                .optional().orElse("");
        return fields.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(fields.split(",")));
    }

    public Set<Long> aiTagIds(long id) {
        return Set.copyOf(jdbc.sql("SELECT tag_id FROM document_tag WHERE document_id = ? AND by_ai").param(id)
                .query(Long.class).list());
    }

    public void requestLanguageModel(long id) {
        jdbc.sql("UPDATE document SET ai_pending = true WHERE id = ?").param(id).update();
    }

    /** Das nächste fertige Dokument, das sich das Sprachmodell noch ansehen soll. */
    public Optional<Long> nextForLanguageModel() {
        return jdbc.sql("SELECT id FROM document WHERE ai_pending AND status = 'done' ORDER BY id LIMIT 1")
                .query(Long.class).optional();
    }

    public void languageModelDone(long id) {
        jdbc.sql("UPDATE document SET ai_pending = false WHERE id = ?").param(id).update();
    }

    /** Ist kein Sprachmodell mehr eingerichtet, wartet kein Dokument mehr darauf. Gibt die Anzahl zurück. */
    public int forgetLanguageModel() {
        return jdbc.sql("UPDATE document SET ai_pending = false WHERE ai_pending").update();
    }

    public long waitingForLanguageModel() {
        return jdbc.sql("SELECT count(*) FROM document WHERE ai_pending AND status = 'done'").query(Long.class)
                .single();
    }

    public Status status() {
        return jdbc.sql("SELECT * FROM ai_status WHERE id = 1")
                .query((row, index) -> new Status(instant(row.getTimestamp("learned_at")),
                        row.getInt("learned_documents"), instant(row.getTimestamp("model_ok_at")),
                        row.getString("model_error"), instant(row.getTimestamp("model_error_at")),
                        row.getInt("assigned")))
                .single();
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** Stand der KI-Analyse eines Dokuments für die Oberfläche. */
    public enum Analysis {
        /** Keine Analyse durch das Sprachmodell vorgemerkt. */
        NONE,
        /** Vorgemerkt, das Sprachmodell ist noch nicht dran. */
        WAITING,
        /** Das Sprachmodell analysiert das Dokument gerade. */
        RUNNING
    }

    public Analysis analysis(long id) {
        return jdbc.sql("""
                SELECT CASE WHEN (SELECT current_document_id FROM ai_status WHERE id = 1) = d.id THEN 'RUNNING'
                            WHEN d.ai_pending AND d.status = 'done' THEN 'WAITING' ELSE 'NONE' END
                FROM document d WHERE d.id = ?
                """).param(id).query(String.class).optional().map(Analysis::valueOf).orElse(Analysis.NONE);
    }

    /** Die Dokumente aus der Auswahl, die auf das Sprachmodell warten oder gerade analysiert werden. */
    public Set<Long> analysing(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("""
                SELECT id FROM document WHERE id = ANY(?) AND (ai_pending AND status = 'done'
                    OR id = (SELECT current_document_id FROM ai_status WHERE id = 1))
                """).param(ids.toArray(Long[]::new)).query(Long.class).list());
    }

    /** Ist das Dokument noch vorgemerkt? Wer es inzwischen gespeichert hat, will keine Vorschläge mehr. */
    public boolean stillWaiting(long id) {
        return jdbc.sql("SELECT ai_pending FROM document WHERE id = ?").param(id).query(Boolean.class).optional()
                .orElse(false);
    }

    public void analysisStarted(long id) {
        jdbc.sql("UPDATE ai_status SET current_document_id = ?, current_since = now() WHERE id = 1").param(id).update();
    }

    public void analysisFinished() {
        jdbc.sql("UPDATE ai_status SET current_document_id = NULL, current_since = NULL WHERE id = 1").update();
    }

    public void learned(int documents) {
        jdbc.sql("UPDATE ai_status SET learned_at = now(), learned_documents = ? WHERE id = 1").param(documents)
                .update();
    }

    public void modelReachable() {
        jdbc.sql("UPDATE ai_status SET model_ok_at = now(), model_error = '' WHERE id = 1").update();
    }

    public void modelFailed(String error) {
        jdbc.sql("UPDATE ai_status SET model_error = ?, model_error_at = now() WHERE id = 1")
                .param(error.length() > 1000 ? error.substring(0, 1000) : error).update();
    }

    public void countAssigned(int fields) {
        if (fields > 0) {
            jdbc.sql("UPDATE ai_status SET assigned = assigned + ? WHERE id = 1").param(fields).update();
        }
    }
}
