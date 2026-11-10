package de.documan.service.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import de.documan.config.DocuManProperties;
import de.documan.repo.BackupRepository;
import de.documan.service.InboxFolder;

/** Sicherungsordner und Zielordner: wohin Archive kommen und wo sie gefunden werden. */
@Component
public class BackupFolders {

    // Ein ganzes NAS zu durchsuchen wäre zu langsam, die Übersicht schaut nur begrenzt tief.
    private static final int SEARCH_DEPTH = 3;

    /** Archiv mit seinem Pfad relativ zum Sicherungsordner. */
    public record Located(Path path, String relative) {
    }

    private final DocuManProperties properties;
    private final BackupRepository runs;
    private final InboxFolder inbox;

    public BackupFolders(DocuManProperties properties, BackupRepository runs, InboxFolder inbox) {
        this.properties = properties;
        this.runs = runs;
        this.inbox = inbox;
    }

    public Path target(String folder) {
        return properties.getBackupDir().resolve(folder);
    }

    /** Der aktuell eingestellte Zielordner. */
    public Path target() {
        return target(runs.loadSettings().folder());
    }

    static boolean isHidden(String name) {
        return name.startsWith(".") || name.startsWith("#") || name.startsWith("@");
    }

    /** Im Dokumentenordner sichert sich das Archiv selbst, aus dem Eingang wird es importiert. */
    public void checkOutsideData(Path target) {
        List<Path> guarded = new ArrayList<>(List.of(properties.getMediaRoot(), properties.getConsumeDir()));
        guarded.addAll(inbox.watchedPaths());
        for (Path folder : guarded) {
            if (!Files.exists(folder)) {
                continue;
            }
            for (Path current = target; current != null; current = current.getParent()) {
                if (isSameFolder(current, folder)) {
                    throw new BackupException(target + " liegt im Dokument- oder Eingangsordner.");
                }
            }
        }
    }

    private static boolean isSameFolder(Path first, Path second) {
        try {
            return Files.exists(first) && Files.isSameFile(first, second);
        } catch (IOException notComparable) {
            return false;
        }
    }

    private static boolean isMountPoint(Path folder) {
        try {
            return !Files.getAttribute(folder, "unix:dev").equals(Files.getAttribute(folder.getParent(), "unix:dev"));
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException unknown) {
            return false;
        }
    }

    /** Sucht Archive, ohne in Systemordner oder eingebundene Laufwerke abzusteigen. */
    private static void collect(Path folder, int depth, Pattern name, Map<Object, Path> found) {
        List<Path> entries;
        try (Stream<Path> stream = Files.list(folder)) {
            entries = stream.toList();
        } catch (IOException unreadable) {
            return;
        }
        for (Path entry : entries) {
            String fileName = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                if (depth > 0 && !isHidden(fileName) && !isMountPoint(entry)) {
                    collect(entry, depth - 1, name, found);
                }
            } else if (name.matcher(fileName).matches()) {
                // Über die NAS-Einbindung kann dieselbe Datei auf zwei Wegen erreichbar sein.
                found.putIfAbsent(fileKey(entry), entry);
            }
        }
    }

    private static Object fileKey(Path path) {
        try {
            Object key = Files.readAttributes(path, BasicFileAttributes.class).fileKey();
            return key != null ? key : path;
        } catch (IOException unknown) {
            return path;
        }
    }

    /** Archive im Sicherungsordner und im gewählten Zielordner, das neueste zuerst. */
    public List<Located> find(Pattern name) {
        Path root = properties.getBackupDir();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        Map<Object, Path> found = new LinkedHashMap<>();
        collect(root, SEARCH_DEPTH, name, found);
        collect(target(), SEARCH_DEPTH, name, found);
        List<Path> paths = new ArrayList<>(found.values());
        paths.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
        return paths.stream().map(path -> new Located(path, relative(root, path))).toList();
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /** Löst einen relativen Pfad aus einer Anfrage auf, ohne den Sicherungsordner zu verlassen. */
    public Located locate(String relative, Pattern name) {
        try {
            Path root = properties.getBackupDir().toRealPath();
            Path path = root.resolve(relative).toRealPath();
            if (!path.startsWith(root) || !name.matcher(path.getFileName().toString()).matches()
                    || !Files.isRegularFile(path)) {
                return null;
            }
            return new Located(path, relative(root, path));
        } catch (InvalidPathException | IOException notUsable) {
            return null;
        }
    }
}
