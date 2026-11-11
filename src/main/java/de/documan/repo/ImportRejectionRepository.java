package de.documan.repo;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import de.documan.domain.Choice;
import de.documan.domain.DocumentSource;

/** Dateien, die beim Import abgelehnt wurden, mit Grund und Herkunft. */
@Repository
public class ImportRejectionRepository {

    /** So viele Einträge bleiben höchstens erhalten, ältere fallen beim nächsten neuen weg. */
    public static final int KEEP = 500;

    /**
     * Ein abgelehnter Import.
     *
     * @param existingDocumentId bei Duplikaten das vorhandene Dokument, sonst null
     */
    public record ImportRejection(long id, Instant created, DocumentSource source, String filename, String reason,
            String addedBy, String sourceDetail, Long existingDocumentId) {

        /** Herkunft mit Benutzer oder Postfach, ohne die Art doppelt zu nennen, etwa „Hochgeladen · anna“. */
        public String origin() {
            String label = source.label();
            if (addedBy.isEmpty()) {
                return label;
            }
            return addedBy.startsWith(label) ? addedBy : label + " · " + addedBy;
        }
    }

    private final JdbcClient jdbc;

    public ImportRejectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(DocumentSource source, String filename, String reason, String addedBy, String sourceDetail,
            Long existingDocumentId) {
        jdbc.sql("""
                INSERT INTO import_rejection (source, filename, reason, added_by, source_detail, existing_document_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .params(source.value(), cut(filename, 500), cut(reason, 1000), cut(addedBy, 150),
                        cut(sourceDetail, 500), existingDocumentId)
                .update();
        jdbc.sql("""
                DELETE FROM import_rejection
                WHERE id NOT IN (SELECT id FROM import_rejection ORDER BY created DESC, id DESC LIMIT ?)
                """).param(KEEP).update();
    }

    private static String cut(String text, int length) {
        String value = text == null ? "" : text;
        return value.length() > length ? value.substring(0, length) : value;
    }

    /** Die neuesten zuerst. */
    public List<ImportRejection> all() {
        return jdbc.sql("""
                SELECT id, created, source, filename, reason, added_by, source_detail, existing_document_id
                FROM import_rejection ORDER BY created DESC, id DESC
                """)
                .query((row, index) -> new ImportRejection(row.getLong("id"),
                        row.getTimestamp("created").toInstant(),
                        Choice.find(DocumentSource.class, row.getString("source")).orElse(DocumentSource.UPLOAD),
                        row.getString("filename"), row.getString("reason"), row.getString("added_by"),
                        row.getString("source_detail"), Rows.nullableLong(row, "existing_document_id")))
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM import_rejection").query(Long.class).single();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM import_rejection WHERE id = ?").param(id).update() > 0;
    }

    public int deleteAll() {
        return jdbc.sql("DELETE FROM import_rejection").update();
    }
}
