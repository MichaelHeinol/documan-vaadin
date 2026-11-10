package de.documan.domain;

import java.time.Instant;

/** Auftrag an den Worker und zugleich Verlauf. */
public record BackupRun(
        long id,
        RunKind kind,
        String origin,
        RunStatus status,
        String archive,
        boolean safetyBackup,
        Long size,
        Integer documents,
        Integer files,
        String message,
        String error,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt) {

    public boolean isActive() {
        return status == RunStatus.QUEUED || status == RunStatus.RUNNING;
    }

    public boolean isQueued() {
        return status == RunStatus.QUEUED;
    }

    public boolean isMigration() {
        return kind == RunKind.EXPORT || kind == RunKind.IMPORT;
    }

    public boolean isFailed() {
        return status == RunStatus.FAILED;
    }

    public Instant shownAt() {
        return finishedAt != null ? finishedAt : queuedAt;
    }

    public String detail() {
        if (!error.isEmpty()) {
            return error;
        }
        return message.isEmpty() ? archive : archive + " · " + message;
    }
}
