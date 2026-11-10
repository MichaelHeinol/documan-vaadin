package de.documan.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import de.documan.domain.Algorithm;
import de.documan.domain.Choice;
import de.documan.domain.Document;
import de.documan.domain.DocumentSource;
import de.documan.domain.DocumentStatus;
import de.documan.domain.MailHeaders;
import de.documan.domain.MasterItem;

@Repository
public class DocumentRepository {

    private static final String LIST_COLUMNS = """
            d.id, d.title, d.correspondent_id, c.name AS correspondent_name, d.document_type_id,
            dt.name AS document_type_name, COALESCE(d.category_id, dt.category_id) AS category_id,
            cat.name AS category_name, d.category_id AS own_category_id, d.document_date, d.notes,
            d.inbox, d.page_count, d.original, d.archive, d.thumbnail, d.original_filename, d.mime_type, d.size,
            d.checksum, d.source, d.source_detail, d.added_by,
            d.mail_from, d.mail_to, d.mail_subject, d.mail_date, d.mail_message_id, d.status, d.error, d.attempts,
            d.added, d.modified
            """;
    private static final String FROM = """
             FROM document d
            LEFT JOIN correspondent c ON c.id = d.correspondent_id
            LEFT JOIN document_type dt ON dt.id = d.document_type_id
            LEFT JOIN category cat ON cat.id = COALESCE(d.category_id, dt.category_id)
            """;

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Felder einer neuen Datei. */
    public record NewDocument(String title, String original, String originalFilename, String mimeType, long size,
            String checksum, DocumentSource source, String sourceDetail, String addedBy, MailHeaders mail,
            Long correspondentId, Long documentTypeId) {
    }

    /** Ergebnis der Verarbeitung. Dokumentdatum und Zuordnungen füllen nur leere Felder. */
    public record Processed(String content, Integer pageCount, String archive, String thumbnail, LocalDate documentDate,
            Long correspondentId, Long documentTypeId) {
    }

    /** Dokument aus einem Umzugsarchiv. Seine Dateien ziehen danach an ihren Platz. */
    public record ImportedDocument(String title, Long correspondentId, Long documentTypeId, LocalDate documentDate,
            String notes, boolean inbox, String content, Integer pageCount, String original, String archive,
            String thumbnail, String originalFilename, String mimeType, long size, String checksum,
            DocumentSource source, String sourceDetail, String addedBy, MailHeaders mail, DocumentStatus status,
            String error, Instant added, Instant modified) {
    }

    /** Pfade eines Dokuments, ohne den Text zu laden. */
    public record StoredFiles(long id, String title, String original, String archive, String thumbnail) {
    }

    private static Document map(ResultSet row, String content) throws SQLException {
        return new Document(row.getLong("id"), row.getString("title"), Rows.nullableLong(row, "correspondent_id"),
                row.getString("correspondent_name"), Rows.nullableLong(row, "document_type_id"),
                row.getString("document_type_name"), Rows.nullableLong(row, "category_id"),
                row.getString("category_name"), Rows.nullableLong(row, "own_category_id"),
                Rows.date(row, "document_date"), row.getString("notes"),
                row.getBoolean("inbox"), content, Rows.nullableInt(row, "page_count"), row.getString("original"),
                row.getString("archive"), row.getString("thumbnail"), row.getString("original_filename"),
                row.getString("mime_type"), row.getLong("size"), row.getString("checksum"),
                Choice.of(DocumentSource.class, row.getString("source")), row.getString("source_detail"),
                row.getString("added_by"), new MailHeaders(row.getString("mail_from"), row.getString("mail_to"),
                        row.getString("mail_subject"), Rows.instant(row, "mail_date"),
                        row.getString("mail_message_id")),
                Choice.of(DocumentStatus.class, row.getString("status")), row.getString("error"),
                row.getInt("attempts"), Rows.instant(row, "added"), Rows.instant(row, "modified"), List.of(), null);
    }

    public Optional<Document> find(long id) {
        return jdbc.sql("SELECT " + LIST_COLUMNS + ", d.content" + FROM + "WHERE d.id = ?")
                .param(id)
                .query((row, index) -> map(row, row.getString("content")))
                .optional()
                .map(document -> withTags(List.of(document)).getFirst());
    }

    public Optional<Document> findByChecksum(String checksum) {
        return jdbc.sql("SELECT " + LIST_COLUMNS + FROM + "WHERE d.checksum = ?")
                .param(checksum)
                .query((row, index) -> map(row, ""))
                .optional();
    }

    public List<Document> page(Selection selection, String order, List<Object> orderParams, int limit, int offset) {
        List<Object> params = new ArrayList<>(selection.params());
        params.addAll(orderParams);
        params.add(limit);
        params.add(offset);
        List<Document> documents = jdbc
                .sql("SELECT " + LIST_COLUMNS + FROM + "WHERE " + selection.where() + " ORDER BY " + order
                        + " LIMIT ? OFFSET ?")
                .params(params)
                .query((row, index) -> map(row, ""))
                .list();
        return withTags(documents);
    }

    public long count(Selection selection) {
        return jdbc.sql("SELECT count(*) FROM document d WHERE " + selection.where())
                .params(selection.params())
                .query(Long.class).single();
    }

    public List<Long> ids(Selection selection) {
        return jdbc.sql("SELECT d.id FROM document d WHERE " + selection.where())
                .params(selection.params())
                .query(Long.class).list();
    }

    private List<Document> withTags(List<Document> documents) {
        if (documents.isEmpty()) {
            return documents;
        }
        Long[] ids = documents.stream().map(Document::id).toArray(Long[]::new);
        Map<Long, List<MasterItem>> tags = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT link.document_id, t.id, t.name, t.algorithm, t.match_text, t.ignore_case, t.color
                FROM document_tag link JOIN tag t ON t.id = link.tag_id
                WHERE link.document_id = ANY(?) ORDER BY t.name
                """)
                .param(ids)
                .query(row -> {
                    tags.computeIfAbsent(row.getLong("document_id"), key -> new ArrayList<>())
                            .add(new MasterItem(row.getLong("id"), row.getString("name"),
                                    Choice.of(Algorithm.class, row.getString("algorithm")),
                                    row.getString("match_text"), row.getBoolean("ignore_case"),
                                    row.getString("color"), 0));
                });
        return documents.stream()
                .map(document -> document.withTags(tags.getOrDefault(document.id(), List.of())))
                .toList();
    }

    public long insert(NewDocument document) {
        return jdbc.sql("""
                INSERT INTO document (title, original, original_filename, mime_type, size, checksum, source,
                                      source_detail, added_by, mail_from, mail_to, mail_subject, mail_date,
                                      mail_message_id, correspondent_id, document_type_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """)
                .params(document.title(), document.original(), document.originalFilename(), document.mimeType(),
                        document.size(), document.checksum(), document.source().value(), document.sourceDetail(),
                        document.addedBy(), document.mail().from(), document.mail().to(), document.mail().subject(),
                        Rows.timestamp(document.mail().date()), document.mail().messageId(),
                        document.correspondentId(), document.documentTypeId())
                .query(Long.class).single();
    }

    public long insertImported(ImportedDocument document) {
        return jdbc.sql("""
                INSERT INTO document (title, correspondent_id, document_type_id, document_date, notes, inbox, content,
                    page_count, original, archive, thumbnail, original_filename, mime_type, size, checksum, source,
                    source_detail, added_by, mail_from, mail_to, mail_subject, mail_date, mail_message_id, status,
                    error, attempts, file_sync_pending, added, modified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, true, ?, ?)
                RETURNING id
                """)
                .params(document.title(), document.correspondentId(), document.documentTypeId(), document.documentDate(),
                        document.notes(), document.inbox(), document.content(), document.pageCount(),
                        document.original(), document.archive(), document.thumbnail(), document.originalFilename(),
                        document.mimeType(), document.size(), document.checksum(), document.source().value(),
                        document.sourceDetail(), document.addedBy(), document.mail().from(), document.mail().to(),
                        document.mail().subject(), Rows.timestamp(document.mail().date()),
                        document.mail().messageId(), document.status().value(), document.error(),
                        Timestamp.from(document.added()), Timestamp.from(document.modified()))
                .query(Long.class).single();
    }

    public List<StoredFiles> storedFiles() {
        return jdbc.sql("SELECT id, title, original, archive, thumbnail FROM document ORDER BY id")
                .query((row, index) -> new StoredFiles(row.getLong("id"), row.getString("title"),
                        row.getString("original"), row.getString("archive"), row.getString("thumbnail")))
                .list();
    }

    public void updateMetadata(long id, String title, Long correspondentId, Long documentTypeId, LocalDate date,
            String addedBy, String notes) {
        jdbc.sql("""
                UPDATE document
                SET title = ?, correspondent_id = ?, document_type_id = ?, document_date = ?, added_by = ?, notes = ?,
                    modified = now()
                WHERE id = ?
                """)
                .params(title, correspondentId, documentTypeId, date, addedBy, notes, id)
                .update();
    }

    /** Die eigene Kategorie der Dokumente. null heißt: Es gilt die des Dokumenttyps. */
    public void setCategory(Long[] ids, Long categoryId) {
        jdbc.sql("UPDATE document SET category_id = ?, modified = now() WHERE id = ANY(?)").params(categoryId, ids)
                .update();
    }

    public void setTags(long id, Collection<Long> tagIds) {
        jdbc.sql("DELETE FROM document_tag WHERE document_id = ?").param(id).update();
        addTags(id, tagIds);
    }

    public void addTags(long id, Collection<Long> tagIds) {
        for (long tagId : tagIds) {
            jdbc.sql("INSERT INTO document_tag (document_id, tag_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
                    .params(id, tagId).update();
        }
    }

    public void removeTag(long id, long tagId) {
        jdbc.sql("DELETE FROM document_tag WHERE document_id = ? AND tag_id = ?").params(id, tagId).update();
    }

    /** Gibt zurück, wie viele Dokumente tatsächlich im Eingang lagen. */
    public int setFiled(Long[] ids) {
        return jdbc.sql("UPDATE document SET inbox = false, modified = now() WHERE inbox AND id = ANY(?)")
                .param(ids).update();
    }

    public void setCorrespondent(Long[] ids, Long correspondentId) {
        jdbc.sql("UPDATE document SET correspondent_id = ?, modified = now() WHERE id = ANY(?)")
                .params(correspondentId, ids).update();
    }

    public void setDocumentType(Long[] ids, Long documentTypeId) {
        jdbc.sql("UPDATE document SET document_type_id = ?, modified = now() WHERE id = ANY(?)")
                .params(documentTypeId, ids).update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM document WHERE id = ?").param(id).update();
    }

    /** Nächstes Dokument der Warteschlange, das älteste zuerst. */
    public Optional<Document> claimNext() {
        return jdbc.sql("""
                UPDATE document SET status = 'processing', attempts = attempts + 1, modified = now()
                WHERE id = (SELECT id FROM document WHERE status = 'pending' ORDER BY added, id
                            FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id
                """)
                .query(Long.class).optional()
                .flatMap(this::find);
    }

    /** Nur eigene Felder schreiben: Titel und Notizen könnten gerade bearbeitet worden sein. */
    public void saveProcessed(long id, Processed result) {
        jdbc.sql("""
                UPDATE document
                SET status = 'done', error = '', attempts = 0, modified = now(), file_sync_pending = true,
                    content = ?, page_count = ?, archive = ?, thumbnail = ?,
                    document_date = COALESCE(document_date, ?),
                    correspondent_id = COALESCE(correspondent_id, ?), document_type_id = COALESCE(document_type_id, ?)
                WHERE id = ?
                """)
                .params(result.content(), result.pageCount(), result.archive(), result.thumbnail(),
                        result.documentDate(), result.correspondentId(), result.documentTypeId(), id)
                .update();
    }

    public void markFailed(long id, String error) {
        jdbc.sql("UPDATE document SET status = 'failed', error = ?, modified = now(), file_sync_pending = true "
                + "WHERE id = ?")
                .params(error, id).update();
    }

    /** Kategorie, Titel oder Datum haben sich geändert: Der Worker verschiebt die Dateien. */
    public void requestFileSync(Long[] ids) {
        jdbc.sql("UPDATE document SET file_sync_pending = true WHERE id = ANY(?)").param(ids).update();
    }

    /** Sperrt das nächste Dokument, dessen Dateien umziehen müssen. Laufende warten noch. */
    public Optional<Document> claimFileSync() {
        return jdbc.sql("""
                SELECT id FROM document WHERE file_sync_pending AND status IN ('done', 'failed')
                ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
                """)
                .query(Long.class).optional()
                .flatMap(this::find);
    }

    public boolean originalTaken(String original, long exceptId) {
        return jdbc.sql("SELECT count(*) FROM document WHERE original = ? AND id <> ?")
                .params(original, exceptId).query(Long.class).single() > 0;
    }

    public void saveFiled(long id, String original, String archive, String thumbnail) {
        jdbc.sql("UPDATE document SET original = ?, archive = ?, thumbnail = ?, file_sync_pending = false WHERE id = ?")
                .params(original, archive, thumbnail, id).update();
    }

    public void clearFileSync(long id) {
        jdbc.sql("UPDATE document SET file_sync_pending = false WHERE id = ?").param(id).update();
    }

    public int failExhausted(int maxAttempts, String error) {
        return jdbc.sql("UPDATE document SET status = 'failed', error = ? WHERE status = 'processing' AND attempts >= ?")
                .params(error, maxAttempts).update();
    }

    public int requeueInterrupted() {
        return jdbc.sql("UPDATE document SET status = 'pending' WHERE status = 'processing'").update();
    }

    /**
     * Übernimmt fehlgeschlagene Dokumente nach manueller Bearbeitung als fertig: kein Fehler mehr, nicht mehr
     * im Eingang. Text und Vorschau bleiben, wie sie sind. Gibt die Zahl der übernommenen zurück.
     */
    public int acceptFailed(Long[] ids) {
        return jdbc.sql("""
                UPDATE document SET status = 'done', error = '', attempts = 0, inbox = false, modified = now(),
                    file_sync_pending = true
                WHERE id = ANY(?) AND status = 'failed'
                """)
                .param(ids).update();
    }

    public int requeue(Long[] ids) {
        return jdbc.sql("""
                UPDATE document SET status = 'pending', error = '', attempts = 0
                WHERE id = ANY(?) AND status IN ('done', 'failed')
                """)
                .param(ids).update();
    }

    public int activeCount() {
        return jdbc.sql("SELECT count(*) FROM document WHERE status IN ('pending', 'processing')")
                .query(Integer.class).single();
    }

    public long total() {
        return jdbc.sql("SELECT count(*) FROM document").query(Long.class).single();
    }

    /**
     * Ändert sich, sobald sich an Dokumenten oder Stammdaten etwas getan hat. Danach aktualisiert die
     * Oberfläche offene Ansichten.
     *
     * <p>Neue, gelöschte und verarbeitete Dokumente zählen über Anzahl, Status und Änderungszeit. Was
     * Benutzer gesammelt ändern, hinterlässt eine Aktivität.
     */
    public String changeStamp() {
        return jdbc.sql("""
                SELECT concat_ws('|',
                    (SELECT concat_ws('-', count(*), max(modified), count(*) FILTER (WHERE inbox),
                        count(*) FILTER (WHERE status = 'pending'), count(*) FILTER (WHERE status = 'processing'),
                        count(*) FILTER (WHERE status = 'done'), count(*) FILTER (WHERE status = 'failed'))
                     FROM document),
                    (SELECT coalesce(max(id), 0) FROM activity),
                    (SELECT concat_ws('-', count(*), max(id)) FROM correspondent),
                    (SELECT concat_ws('-', count(*), max(id)) FROM document_type),
                    (SELECT concat_ws('-', count(*), max(id)) FROM tag),
                    (SELECT concat_ws('-', count(*), max(id)) FROM category))
                """).query(String.class).single();
    }

    /** Für die Seitenleiste: alle Zähler mit einer Abfrage. */
    public record NavigationCounts(long total, long inbox, long active, long failed) {
    }

    public NavigationCounts navigationCounts() {
        return jdbc.sql("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE inbox AND status IN ('done', 'failed')) AS inbox,
                       count(*) FILTER (WHERE status IN ('pending', 'processing')) AS active,
                       count(*) FILTER (WHERE status = 'failed') AS failed
                FROM document
                """)
                .query((row, index) -> new NavigationCounts(row.getLong("total"), row.getLong("inbox"),
                        row.getLong("active"), row.getLong("failed")))
                .single();
    }

    /** Kennzahlen der Übersicht. */
    public record Summary(long total, long inbox, long pages, long thisMonth, long withoutType,
            long withoutCorrespondent, long failed, long withoutTag, long withoutCategory) {
    }

    public Summary summary(Instant monthStart) {
        return jdbc.sql("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE inbox AND status IN ('done', 'failed')) AS inbox,
                       COALESCE(sum(page_count), 0) AS pages,
                       count(*) FILTER (WHERE added >= ?) AS this_month,
                       count(*) FILTER (WHERE document_type_id IS NULL) AS without_type,
                       count(*) FILTER (WHERE correspondent_id IS NULL) AS without_correspondent,
                       count(*) FILTER (WHERE status = 'failed') AS failed,
                       count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM document_tag link
                                                          WHERE link.document_id = document.id)) AS without_tag,
                       count(*) FILTER (WHERE category_id IS NULL AND NOT EXISTS (SELECT 1 FROM document_type t
                                                          WHERE t.id = document.document_type_id
                                                          AND t.category_id IS NOT NULL)) AS without_category
                FROM document
                """)
                .param(Timestamp.from(monthStart))
                .query((row, index) -> new Summary(row.getLong("total"), row.getLong("inbox"), row.getLong("pages"),
                        row.getLong("this_month"), row.getLong("without_type"), row.getLong("without_correspondent"),
                        row.getLong("failed"), row.getLong("without_tag"), row.getLong("without_category")))
                .single();
    }

    /** Dokumente je Jahr und Monat nach Dokumentdatum, Schlüssel: Jahr * 100 + Monat. */
    public Map<Integer, Integer> countsByMonth(LocalDate from, LocalDate to) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT (EXTRACT(YEAR FROM document_date) * 100 + EXTRACT(MONTH FROM document_date))::int AS month,
                       count(*)::int AS documents
                FROM document WHERE document_date BETWEEN ? AND ? GROUP BY 1
                """)
                .params(from, to)
                .query(row -> {
                    counts.put(row.getInt("month"), row.getInt("documents"));
                });
        return counts;
    }

    /** Gruppenzahlen der Filterleiste innerhalb der aktuellen Auswahl. column ist ein SQL-Ausdruck über d. */
    public Map<Long, Long> countsBy(String column, Selection selection) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT " + column + " AS key, count(*) AS documents FROM document d WHERE " + selection.where()
                + " GROUP BY 1")
                .params(selection.params())
                .query(row -> {
                    Long key = Rows.nullableLong(row, "key");
                    if (key != null) {
                        counts.put(key, row.getLong("documents"));
                    }
                });
        return counts;
    }

    /** Dokumente je „Eingespielt von“ innerhalb der Auswahl. Der leere Wert steht für unbekannt. */
    public Map<String, Long> addedByCounts(Selection selection) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT d.added_by, count(*) AS documents FROM document d WHERE " + selection.where()
                + " GROUP BY 1 ORDER BY 1")
                .params(selection.params())
                .query(row -> {
                    counts.put(row.getString("added_by"), row.getLong("documents"));
                });
        return counts;
    }

    /** Über die Zwischentabelle zählen, damit auch nicht gewählte Tags der Treffer erscheinen. */
    public Map<Long, Long> tagCounts(Selection selection) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT link.tag_id, count(*) AS documents FROM document_tag link WHERE link.document_id IN "
                + "(SELECT d.id FROM document d WHERE " + selection.where() + ") GROUP BY 1")
                .params(selection.params())
                .query(row -> {
                    counts.put(row.getLong("tag_id"), row.getLong("documents"));
                });
        return counts;
    }

    public Map<Integer, Long> yearCounts(Selection selection) {
        Map<Integer, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT EXTRACT(YEAR FROM d.document_date)::int AS year, count(*) AS documents FROM document d WHERE "
                + selection.where() + " AND d.document_date IS NOT NULL GROUP BY 1 ORDER BY 1 DESC")
                .params(selection.params())
                .query(row -> {
                    counts.put(row.getInt("year"), row.getLong("documents"));
                });
        return counts;
    }

    /** Zeile des Ordnerbaums. categoryId ist die eigene Kategorie des Dokuments oder die seines Typs. */
    public record TreeRow(Long categoryId, Long typeId, String typeName, Long correspondentId, String correspondentName,
            long count) {
    }

    public List<TreeRow> treeRows() {
        return jdbc.sql("""
                SELECT COALESCE(d.category_id, dt.category_id) AS category_id, d.document_type_id,
                       dt.name AS type_name, d.correspondent_id, c.name AS correspondent_name, count(*) AS documents
                FROM document d
                LEFT JOIN correspondent c ON c.id = d.correspondent_id
                LEFT JOIN document_type dt ON dt.id = d.document_type_id
                GROUP BY 1, 2, 3, 4, 5
                ORDER BY dt.name ASC NULLS LAST, c.name ASC NULLS LAST
                """)
                .query((row, index) -> new TreeRow(Rows.nullableLong(row, "category_id"),
                        Rows.nullableLong(row, "document_type_id"),
                        row.getString("type_name"), Rows.nullableLong(row, "correspondent_id"),
                        row.getString("correspondent_name"), row.getLong("documents")))
                .list();
    }
}
