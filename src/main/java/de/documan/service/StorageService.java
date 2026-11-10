package de.documan.service;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import de.documan.config.DocuManProperties;
import de.documan.service.backup.BackupService;

/**
 * Speicherverbrauch für die Übersicht.
 *
 * <p>Dateien zählt der Worker, die Übersicht liest nur sein Ergebnis: Auf einem NAS dauert das
 * Aufsummieren vieler Dateien spürbar, und die Seite darf nie darauf warten.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    public record Drive(long total, long used, long free, double percent) {
    }

    /** {@code drive} fehlt, wenn sich das Laufwerk nicht abfragen lässt, {@code measuredAt} vor der ersten Zählung. */
    public record Usage(Drive drive, Instant measuredAt, long media, long database, long backups, long total,
            double share) {
    }

    private record Counted(long media, long backups, Instant measuredAt) {
    }

    private final DocuManProperties properties;
    private final JdbcClient jdbc;
    private final BackupService backup;

    public StorageService(DocuManProperties properties, JdbcClient jdbc, BackupService backup) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.backup = backup;
    }

    public void measure() {
        jdbc.sql("""
                INSERT INTO storage_usage (id, media, backups, measured_at) VALUES (1, ?, ?, now())
                ON CONFLICT (id) DO UPDATE SET media = EXCLUDED.media, backups = EXCLUDED.backups,
                    measured_at = EXCLUDED.measured_at
                """)
                .params(folderSize(properties.getMediaRoot()), backup.archiveBytes()).update();
    }

    public Usage usage() {
        Drive drive = drive(properties.getMediaRoot());
        long database = jdbc.sql("SELECT pg_database_size(current_database())").query(Long.class).single();
        Optional<Counted> counted = jdbc.sql("SELECT media, backups, measured_at FROM storage_usage")
                .query((row, index) -> new Counted(row.getLong("media"), row.getLong("backups"),
                        row.getTimestamp("measured_at").toInstant()))
                .optional();
        long media = counted.map(Counted::media).orElse(0L);
        long backups = counted.map(Counted::backups).orElse(0L);
        long total = media + database + backups;
        return new Usage(drive, counted.map(Counted::measuredAt).orElse(null), media, database, backups, total,
                drive == null ? 0 : percent(total, drive.total()));
    }

    /** Protected, damit Tests ein fehlendes Laufwerk nachstellen können. */
    protected Drive drive(Path path) {
        try {
            FileStore store = Files.getFileStore(path);
            long total = store.getTotalSpace();
            long used = total - store.getUnallocatedSpace();
            return new Drive(total, used, store.getUsableSpace(), percent(used, total));
        } catch (IOException unavailable) {
            log.warn("Laufwerk von {} lässt sich nicht abfragen: {}", path, unavailable.toString());
            return null;
        }
    }

    private static double percent(long part, long whole) {
        return whole == 0 ? 0 : Math.round(part * 1000.0 / whole) / 10.0;
    }

    private static long folderSize(Path root) {
        long[] total = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    total[0] += attributes.size();
                    return FileVisitResult.CONTINUE;
                }

                /** Der Worker hat die Datei gerade verschoben, oder der Ordner fehlt. */
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException unreadable) {
            log.warn("{} lässt sich nicht vollständig lesen: {}", root, unreadable.toString());
        }
        return total[0];
    }
}
