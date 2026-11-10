package de.documan.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import de.documan.domain.ActivityKind;

/** Hält fest, was Benutzer tun, und fasst es für die Übersicht zusammen. */
@Repository
public class ActivityRepository {

    /** Eine Zeile der Übersicht. */
    public record UserActivity(String username, long uploaded, long filed, long edited, long deleted, Instant last) {
    }

    private final JdbcClient jdbc;

    public ActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String username, ActivityKind kind, int count) {
        if (count > 0) {
            jdbc.sql("INSERT INTO activity (username, kind, count) VALUES (?, ?, ?)")
                    .params(username, kind.value(), count).update();
        }
    }

    /** Abgelegte Dokumente je Benutzer. */
    public Map<String, Long> inboxCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT username, sum(count) AS total FROM activity WHERE kind = ? GROUP BY username")
                .param(ActivityKind.FILED.value())
                .query((row, index) -> counts.put(row.getString("username"), row.getLong("total")))
                .list();
        return counts;
    }

    /** Eine Zeile je Benutzer, zuletzt Aktive zuerst. */
    public List<UserActivity> overview() {
        return overview(Instant.EPOCH);
    }

    /** Wie overview, aber nur Aktionen ab diesem Zeitpunkt. */
    public List<UserActivity> overview(Instant since) {
        return jdbc.sql("""
                SELECT username,
                    coalesce(sum(count) FILTER (WHERE kind = 'uploaded'), 0) AS uploaded,
                    coalesce(sum(count) FILTER (WHERE kind = 'filed'), 0) AS filed,
                    coalesce(sum(count) FILTER (WHERE kind = 'edited'), 0) AS edited,
                    coalesce(sum(count) FILTER (WHERE kind = 'deleted'), 0) AS deleted,
                    max(at) AS last_at
                FROM activity WHERE at >= ? GROUP BY username ORDER BY last_at DESC, username
                """)
                .param(Timestamp.from(since))
                .query((row, index) -> new UserActivity(row.getString("username"), row.getLong("uploaded"),
                        row.getLong("filed"), row.getLong("edited"), row.getLong("deleted"),
                        Rows.instant(row, "last_at")))
                .list();
    }
}
