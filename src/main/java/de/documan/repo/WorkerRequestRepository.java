package de.documan.repo;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Aufträge der Oberfläche an den Worker. Die Oberfläche läuft in einem anderen Container, deshalb gehen sie über
 * die Datenbank: Sie trägt den Zeitpunkt ein, der Worker erledigt den Auftrag und schreibt das Ergebnis zurück.
 */
@Repository
public class WorkerRequestRepository {

    public static final String CONSUME = "consume";

    public record Request(Instant requestedAt, String requestedBy, Instant handledAt, String result) {

        /** Angefordert, aber noch nicht erledigt. */
        public boolean pending() {
            return requestedAt != null && (handledAt == null || handledAt.isBefore(requestedAt));
        }
    }

    private final JdbcClient jdbc;

    public WorkerRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void request(String kind, String user) {
        jdbc.sql("UPDATE worker_request SET requested_at = clock_timestamp(), requested_by = ? WHERE kind = ?")
                .params(user, kind).update();
    }

    public Request find(String kind) {
        return jdbc.sql("SELECT requested_at, requested_by, handled_at, result FROM worker_request WHERE kind = ?")
                .param(kind)
                .query((row, index) -> new Request(instant(row.getTimestamp("requested_at")),
                        row.getString("requested_by"), instant(row.getTimestamp("handled_at")),
                        row.getString("result")))
                .optional().orElse(new Request(null, "", null, ""));
    }

    /** Für den Worker: Steht der Auftrag an? */
    public boolean pending(String kind) {
        return find(kind).pending();
    }

    public void handled(String kind, String result) {
        jdbc.sql("UPDATE worker_request SET handled_at = clock_timestamp(), result = ? WHERE kind = ?")
                .params(result.length() > 500 ? result.substring(0, 500) : result, kind).update();
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
