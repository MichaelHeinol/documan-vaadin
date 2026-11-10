package de.documan.service.backup;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;

/** Eine Archivdatei im Sicherungsordner. */
public record Archive(Path path, String relative, String origin, long size, Map<String, Object> manifest,
        String problem) {

    private static final Map<String, String> ORIGIN_LABELS = Map.of(
            BackupService.MANUAL, "Von Hand",
            BackupService.AUTOMATIC, "Zeitplan",
            BackupService.SAFETY, "Vor Wiederherstellung");

    public boolean readable() {
        return manifest != null;
    }

    public String originLabel() {
        return ORIGIN_LABELS.get(origin);
    }

    public OffsetDateTime created() {
        return manifest == null ? null : OffsetDateTime.parse((String) manifest.get("created"));
    }

    public Integer documents() {
        return manifest == null ? null : ((Number) manifest.get("documents")).intValue();
    }

    public String folder() {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? "" : relative.substring(0, slash);
    }
}
