package de.documan.service.backup;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;

/** Ein Umzugsarchiv im Sicherungsordner. */
public record MigrationArchive(Path path, String relative, long size, Map<String, Object> manifest, String problem) {

    public boolean readable() {
        return manifest != null;
    }

    public String sourceLabel() {
        if (manifest == null) {
            return "";
        }
        return "django".equals(manifest.get("source")) ? "Django" : "Java";
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
