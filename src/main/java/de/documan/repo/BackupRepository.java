package de.documan.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import de.documan.domain.BackupFrequency;
import de.documan.domain.BackupRun;
import de.documan.domain.BackupSettings;
import de.documan.domain.Choice;
import de.documan.domain.RunKind;
import de.documan.domain.RunStatus;

@Repository
public class BackupRepository {

    private static final int SINGLETON_ID = 1;

    private final JdbcClient jdbc;

    public BackupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static BackupRun map(ResultSet row) throws SQLException {
        return new BackupRun(row.getLong("id"), Choice.of(RunKind.class, row.getString("kind")),
                row.getString("origin"), Choice.of(RunStatus.class, row.getString("status")),
                row.getString("archive"), row.getBoolean("safety_backup"), Rows.nullableLong(row, "size"),
                Rows.nullableInt(row, "documents"), Rows.nullableInt(row, "files"), row.getString("message"),
                row.getString("error"), Rows.instant(row, "queued_at"), Rows.instant(row, "started_at"),
                Rows.instant(row, "finished_at"));
    }

    public BackupSettings loadSettings() {
        jdbc.sql("INSERT INTO backup_settings (id) VALUES (?) ON CONFLICT DO NOTHING").param(SINGLETON_ID).update();
        return jdbc.sql("SELECT * FROM backup_settings WHERE id = ?")
                .param(SINGLETON_ID)
                .query((row, index) -> new BackupSettings(row.getBoolean("enabled"),
                        Choice.of(BackupFrequency.class, row.getString("frequency")), row.getInt("weekday"),
                        row.getObject("time", LocalTime.class), row.getString("folder"), row.getInt("keep"),
                        Rows.instant(row, "last_scheduled")))
                .single();
    }

    public void saveSettings(BackupSettings settings) {
        loadSettings();
        jdbc.sql("""
                UPDATE backup_settings
                SET enabled = ?, frequency = ?, weekday = ?, time = ?, folder = ?, keep = ?, last_scheduled = ?
                WHERE id = ?
                """)
                .params(settings.enabled(), settings.frequency().value(), settings.weekday(), settings.time(),
                        settings.folder(), settings.keep(), Rows.timestamp(settings.lastScheduled()), SINGLETON_ID)
                .update();
    }

    public void setLastScheduled(Instant at) {
        jdbc.sql("UPDATE backup_settings SET last_scheduled = ? WHERE id = ?")
                .params(Timestamp.from(at), SINGLETON_ID).update();
    }

    public long enqueue(RunKind kind, String origin, String archive, boolean safetyBackup) {
        return jdbc.sql("INSERT INTO backup_run (kind, origin, archive, safety_backup) VALUES (?, ?, ?, ?) RETURNING id")
                .params(kind.value(), origin, archive, safetyBackup)
                .query(Long.class).single();
    }

    public Optional<BackupRun> claimNext() {
        return jdbc.sql("""
                UPDATE backup_run SET status = 'running', started_at = now()
                WHERE id = (SELECT id FROM backup_run WHERE status = 'queued' ORDER BY queued_at, id
                            FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING *
                """)
                .query((row, index) -> map(row)).optional();
    }

    /** Ergebnis eines Laufs. Nach einer Wiederherstellung gibt es den ursprünglichen Eintrag nicht mehr. */
    public void finish(BackupRun run, RunStatus status, String archive, Long size, Integer documents, Integer files,
            String message, String error) {
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbc.sql("""
                UPDATE backup_run
                SET status = ?, archive = ?, size = ?, documents = ?, files = ?, message = ?, error = ?, finished_at = ?
                WHERE id = ? AND status = 'running'
                """)
                .params(status.value(), archive, size, documents, files, message, error, now, run.id())
                .update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO backup_run (kind, origin, status, archive, safety_backup, size, documents, files,
                                            message, error, queued_at, started_at, finished_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(run.kind().value(), run.origin(), status.value(), archive, run.safetyBackup(), size,
                            documents, files, message, error, Rows.timestamp(run.queuedAt()),
                            Rows.timestamp(run.startedAt()), now)
                    .update();
        }
    }

    public void recordRestored(String message) {
        jdbc.sql("INSERT INTO backup_run (kind, status, message, finished_at) VALUES ('restore', 'done', ?, now())")
                .param(message).update();
    }

    public int failRunning(String error) {
        return jdbc.sql("UPDATE backup_run SET status = 'failed', error = ?, finished_at = now() WHERE status = 'running'")
                .param(error).update();
    }

    public int deleteActive() {
        return jdbc.sql("DELETE FROM backup_run WHERE status IN ('queued', 'running')").update();
    }

    public boolean hasActive() {
        return activeRun().isPresent();
    }

    public Optional<BackupRun> activeRun() {
        return jdbc.sql("""
                SELECT * FROM backup_run WHERE status IN ('queued', 'running') ORDER BY queued_at DESC, id DESC LIMIT 1
                """)
                .query((row, index) -> map(row)).optional();
    }

    public List<BackupRun> history(int limit) {
        return jdbc.sql("SELECT * FROM backup_run ORDER BY queued_at DESC, id DESC LIMIT ?")
                .param(limit)
                .query((row, index) -> map(row)).list();
    }

    public Optional<BackupRun> lastSuccessfulBackup() {
        return jdbc.sql("""
                SELECT * FROM backup_run WHERE kind = 'backup' AND status = 'done'
                ORDER BY finished_at DESC NULLS LAST LIMIT 1
                """)
                .query((row, index) -> map(row)).optional();
    }

    /** Eine stillschweigend scheiternde Sicherung fällt sonst erst im Ernstfall auf. */
    public boolean lastBackupFailed() {
        return jdbc.sql("SELECT status FROM backup_run WHERE kind = 'backup' ORDER BY queued_at DESC, id DESC LIMIT 1")
                .query(String.class).optional()
                .filter(RunStatus.FAILED.value()::equals)
                .isPresent();
    }
}
