package de.documan.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;

import de.documan.domain.DocumentSource;

/**
 * Beobachtet den Eingangsordner.
 *
 * <p>Der Ordner wird bewusst abgefragt statt per WatchService überwacht: Auf SMB- und
 * NFS-Freigaben, wie sie Scanner am NAS nutzen, kommen Dateiereignisse nicht an.
 */
public class FolderWatcher {

    public static final String REJECTED_DIR = "_abgelehnt";

    private static final Logger log = LoggerFactory.getLogger(FolderWatcher.class);
    // Systemordner und Dateien von NAS, Windows, macOS und Synchronisationsdiensten.
    private static final Set<String> IGNORED_DIRS =
            Set.of("@eadir", "#recycle", "#snapshot", ".stfolder", ".stversions", "$recycle.bin");
    private static final Set<String> IGNORED_FILES = Set.of("thumbs.db", "desktop.ini");
    private static final Set<String> IGNORED_SUFFIXES = Set.of(".tmp", ".part", ".crdownload", ".filepart");
    // Was NAS und Clients von selbst in Ordnern ablegen. Hält einen leeren Unterordner nicht am Leben.
    private static final Set<String> LEFTOVERS = Set.of("@eadir", "thumbs.db", "desktop.ini", ".ds_store");
    /**
     * So lange bleibt ein leerer Unterordner stehen, den DocuMan nicht selbst geleert hat. Wer gerade
     * einen Ordner anlegt und erst danach Dateien hineinkopiert, verliert ihn nicht.
     */
    public static final double EMPTY_FOLDER_SECONDS = 600;

    private record Signature(long size, long modifiedNanos) {
    }

    private record Sighting(Signature signature, double since) {
    }

    private record Found(Path path, Signature signature) {
    }

    private final Path root;
    private final double stableSeconds;
    private final DoubleSupplier clock;
    private final IngestService ingest;
    private Map<Path, Sighting> seen = new HashMap<>();
    // Dateien, die nicht entfernt werden konnten. Sonst würden sie endlos wiederkommen.
    private final Map<Path, Signature> stuck = new HashMap<>();
    // Oberste leere Unterordner -> seit wann sie leer sind
    private Map<Path, Double> empty = new LinkedHashMap<>();
    // Unterordner, aus denen DocuMan Dateien geholt hat, samt den Ordnern darüber
    private Set<Path> emptied = new HashSet<>();

    public FolderWatcher(Path root, double stableSeconds, DoubleSupplier clock, IngestService ingest) {
        this.root = root;
        this.stableSeconds = stableSeconds;
        this.clock = clock;
        this.ingest = ingest;
    }

    public Path root() {
        return root;
    }

    static boolean isIgnored(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.startsWith(".") || name.startsWith("~$") || IGNORED_FILES.contains(name)
                || IGNORED_SUFFIXES.contains(FileNames.suffix(name));
    }

    static boolean isIgnoredFolder(Path path) {
        String name = path.getFileName().toString();
        return IGNORED_DIRS.contains(name.toLowerCase(Locale.ROOT)) || name.startsWith(".")
                || name.equals(REJECTED_DIR);
    }

    static void walk(Path folder, List<Path> files) throws IOException {
        List<Path> entries;
        try (Stream<Path> stream = Files.list(folder)) {
            entries = stream.toList();
        }
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                if (!isIgnoredFolder(entry)) {
                    walk(entry, files);
                }
            } else if (!isIgnored(entry)) {
                files.add(entry);
            }
        }
    }

    private List<Found> files() {
        // Als Liste einlesen: Die Schleife verschiebt Dateien aus dem Ordner heraus.
        // Eine zwischenzeitlich getrennte Freigabe darf den Worker nicht beenden.
        List<Path> paths = new ArrayList<>();
        try {
            walk(root, paths);
        } catch (IOException error) {
            log.error("Eingangsordner {} nicht lesbar", root, error);
            return List.of();
        }
        List<Found> files = new ArrayList<>();
        for (Path path : paths) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                files.add(new Found(path, new Signature(attributes.size(),
                        attributes.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS))));
            } catch (IOException vanished) {
                continue;
            }
        }
        return files;
    }

    /**
     * Nimmt Dateien an, die sich lange genug nicht mehr verändert haben.
     *
     * <p>Scanner schreiben über das Netz oft minutenlang in dieselbe Datei.
     */
    public int scan() {
        if (!Files.isDirectory(root)) {
            log.error("Eingangsordner {} fehlt", root);
            return 0;
        }
        double now = clock.getAsDouble();
        Map<Path, Sighting> current = new HashMap<>();
        int imported = 0;
        for (Found file : files()) {
            if (file.signature().equals(stuck.get(file.path()))) {
                continue;
            }
            Sighting previous = seen.get(file.path());
            double since = previous != null && previous.signature().equals(file.signature()) ? previous.since() : now;
            if (file.signature().size() > 0 && now - since >= stableSeconds) {
                imported += consume(file.path(), file.signature());
            } else {
                current.put(file.path(), new Sighting(file.signature(), since));
            }
        }
        seen = current;
        removeEmptyFolders(now);
        return imported;
    }

    private static boolean isLeftover(Path entry) {
        return LEFTOVERS.contains(entry.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private static boolean isFolder(Path entry) {
        return Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Sammelt die obersten leeren Unterordner und sagt, ob der Ordner selbst leer ist.
     *
     * <p>Leer heißt: nur Reste von NAS und Clients und selbst leere Unterordner. Systemordner wie
     * _abgelehnt oder #recycle gelten nie als leer und halten ihren Ordner am Leben.
     */
    private static boolean collectEmpty(Path folder, List<Path> found) {
        List<Path> entries;
        try (Stream<Path> stream = Files.list(folder)) {
            entries = stream.toList();
        } catch (IOException unreadable) {
            return false;
        }
        boolean isEmpty = true;
        for (Path entry : entries) {
            if (isLeftover(entry)) {
                continue;
            }
            if (isFolder(entry) && !isIgnoredFolder(entry)) {
                List<Path> below = new ArrayList<>();
                if (collectEmpty(entry, below)) {
                    found.add(entry);
                    continue;
                }
                found.addAll(below);
            }
            isEmpty = false;
        }
        return isEmpty;
    }

    /** Die obersten leeren Unterordner. Eigene Methode, damit Tests eine Datei dazwischenschieben können. */
    protected List<Path> emptyFolders() {
        List<Path> found = new ArrayList<>();
        collectEmpty(root, found);
        return found;
    }

    /** Löscht einen leeren Ordner samt leeren Unterordnern und Resten. Neu Hinzugekommenes bleibt. */
    private static void clear(Path folder) throws IOException {
        List<Path> entries;
        try (Stream<Path> stream = Files.list(folder)) {
            entries = stream.toList();
        }
        for (Path entry : entries) {
            if (isLeftover(entry)) {
                if (isFolder(entry)) {
                    deleteTree(entry);
                } else {
                    Files.delete(entry);
                }
            } else if (isFolder(entry) && !isIgnoredFolder(entry)) {
                clear(entry);
            } else {
                throw new IOException(entry.getFileName() + " ist inzwischen hinzugekommen");
            }
        }
        Files.delete(folder);
    }

    /** Entfernt einen leeren Unterordner samt Resten von NAS und Clients. */
    private static boolean removeFolder(Path folder) {
        try {
            clear(folder);
        } catch (IOException error) {
            log.warn("Leerer Ordner {} bleibt bestehen: {}", folder, error.toString());
            return false;
        }
        log.info("Leeren Ordner {} entfernt", folder);
        return true;
    }

    private static void deleteTree(Path folder) throws IOException {
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(folder)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }

    /**
     * Entfernt leere Unterordner, sobald sie lange genug leer bleiben.
     *
     * <p>Hat DocuMan den Ordner selbst geleert, reicht die kurze Wartezeit für Dateien. So läuft ein
     * Kopiervorgang, der gleich die nächste Datei anlegt, nicht ins Leere. Andere leere Ordner bleiben
     * {@link #EMPTY_FOLDER_SECONDS} stehen. Der Eingangsordner selbst bleibt immer.
     */
    private void removeEmptyFolders(double now) {
        Map<Path, Double> waiting = new LinkedHashMap<>();
        for (Path folder : emptyFolders()) {
            double since = empty.getOrDefault(folder, now);
            double wait = emptied.contains(folder) ? stableSeconds : Math.max(stableSeconds, EMPTY_FOLDER_SECONDS);
            if (now - since < wait) {
                waiting.put(folder, since);
            } else {
                removeFolder(folder);
            }
        }
        empty = waiting;
        emptied.removeIf(folder -> !Files.isDirectory(folder));
    }

    private void markEmptied(Path path) {
        for (Path folder = path.getParent(); folder != null && !folder.equals(root) && folder.startsWith(root);
                folder = folder.getParent()) {
            emptied.add(folder);
        }
    }

    /** Die Unterordner über einer Datei werden zu Kategoriestufen, höchstens zehn, tiefere zählen nicht. */
    private List<String> categoryPath(Path path) {
        List<String> names = new ArrayList<>();
        Path folder = root.relativize(path).getParent();
        if (folder != null) {
            folder.forEach(part -> names.add(part.toString().strip()));
        }
        return names.stream().filter(name -> !name.isEmpty()).limit(CategoryPaths.MAX_LEVELS).toList();
    }

    private int consume(Path path, Signature signature) {
        try {
            ingest.ingest(new FileSystemResource(path), path.getFileName().toString(), DocumentSource.CONSUME,
                    IngestService.Options.of(IngestService.CONSUME_LABEL,
                            root.relativize(path).toString().replace('\\', '/'), categoryPath(path)));
        } catch (DuplicateDocumentException duplicate) {
            log.info("Duplikat {}: {}", path.getFileName(), duplicate.getMessage());
            reject(path, signature);
            return 0;
        } catch (IngestException error) {
            log.warn("Abgelehnt {}: {}", path.getFileName(), error.getMessage());
            reject(path, signature);
            return 0;
        } catch (UncheckedIOException error) {
            log.error("Lesefehler bei {}", path, error);
            stuck.put(path, signature);
            return 0;
        }

        try {
            remove(path);
            markEmptied(path);
        } catch (IOException error) {
            log.error("{} wurde importiert, konnte aber nicht entfernt werden", path, error);
            stuck.put(path, signature);
        }
        return 1;
    }

    /** Eigene Methode, damit Tests eine schreibgeschützte Freigabe nachstellen können. */
    protected void remove(Path path) throws IOException {
        Files.delete(path);
    }

    private void reject(Path path, Signature signature) {
        Path targetFolder = root.resolve(REJECTED_DIR);
        try {
            Files.createDirectories(targetFolder);
            String name = path.getFileName().toString();
            Path target = targetFolder.resolve(name);
            for (int counter = 1; Files.exists(target); counter++) {
                target = targetFolder.resolve(FileNames.stem(name) + "-" + counter + FileNames.suffix(name));
            }
            Files.move(path, target);
            markEmptied(path);
        } catch (IOException error) {
            log.error("{} konnte nicht verschoben werden", path, error);
            stuck.put(path, signature);
        }
    }
}
