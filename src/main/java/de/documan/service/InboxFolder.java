package de.documan.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import de.documan.config.DocuManProperties;
import de.documan.repo.BackupRepository;

/**
 * Die überwachten Eingangsordner: wo sie liegen und wie sie sich ändern lassen.
 *
 * <p>Zur Auswahl steht, was der Container sieht: der eingerichtete Eingangsordner und, wenn
 * eingebunden, der NAS-Ordner. Der Worker entfernt importierte Dateien, deshalb sind der ganze
 * NAS-Ordner und alles mit Daten von DocuMan gesperrt, und ein gefüllter Ordner muss ausdrücklich
 * bestätigt werden.
 */
@Service
public class InboxFolder {

    public static final String DEFAULT = "eingang";
    public static final String NAS = "nas";
    private static final Logger log = LoggerFactory.getLogger(InboxFolder.class);
    private static final Pattern FOLDER_PART = Pattern.compile("[^\\x00-\\x1f/\\\\.#@][^\\x00-\\x1f/\\\\]*");

    public record Entry(long id, String folder) {
    }

    public record Status(long id, String folder, String path, boolean reachable, boolean writable, int waiting,
            int rejected) {
    }

    private final DocuManProperties properties;
    private final JdbcClient jdbc;
    private final BackupRepository runs;
    private final TransactionTemplate transaction;

    public InboxFolder(DocuManProperties properties, JdbcClient jdbc, BackupRepository runs,
            PlatformTransactionManager transactions) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.runs = runs;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Mehr Dateien sprechen für ein Archiv, nicht für einen Ordner, in den ein Scanner schreibt. */
    protected int maxFiles() {
        return 5000;
    }

    /** Der Worker durchsucht jeden Ordner alle paar Sekunden. */
    protected int maxFolders() {
        return 20;
    }

    /** Eigene Methode, damit Tests eine schreibgeschützte Freigabe nachstellen können. */
    protected boolean accessible(Path path) {
        return Files.isReadable(path) && Files.isWritable(path) && Files.isExecutable(path);
    }

    private Path root(String name) {
        return switch (name) {
            case DEFAULT -> properties.getConsumeDir();
            case NAS -> properties.getBackupDir().resolve(NAS);
            default -> null;
        };
    }

    private List<String> parts(String value) {
        String text = value.strip();
        List<String> parts = Stream.of(text.split("/")).map(String::strip).filter(part -> !part.isEmpty()).toList();
        boolean valid = parts.stream().allMatch(part -> FOLDER_PART.matcher(part).matches());
        if (text.startsWith("/") || !valid || (!parts.isEmpty() && root(parts.getFirst()) == null)) {
            throw new InboxFolderException("Dieser Ordner steht nicht zur Auswahl.");
        }
        return parts;
    }

    public String clean(String value) {
        return String.join("/", parts(value));
    }

    public Path pathOf(String value) {
        List<String> parts = parts(value);
        if (parts.isEmpty()) {
            throw new InboxFolderException("Bitte einen Ordner wählen.");
        }
        Path path = root(parts.getFirst());
        for (String part : parts.subList(1, parts.size())) {
            path = path.resolve(part);
        }
        return path;
    }

    public List<Entry> folders() {
        return jdbc.sql("SELECT id, folder FROM consume_folder ORDER BY id").query(Entry.class).list();
    }

    /** Die Pfade aller überwachten Ordner. Ein nicht mehr gültiger Eintrag wird übersprungen. */
    public List<Path> watchedPaths() {
        List<Path> paths = new ArrayList<>();
        for (Entry entry : folders()) {
            try {
                paths.add(pathOf(entry.folder()));
            } catch (InboxFolderException | InvalidPathException vanished) {
                log.warn("Eingangsordner {} steht nicht mehr zur Auswahl", entry.folder());
            }
        }
        return paths;
    }

    /** Der Pfad auf dem NAS, soweit die Compose-Datei ihn mitteilt, sonst der im Container. */
    public String display(String value) {
        List<String> parts = parts(value);
        String first = parts.getFirst();
        String host = first.equals(DEFAULT) ? properties.getConsumeHostDir() : properties.getBrowseHostDir();
        String base = (host.isEmpty() ? root(first).toString() : host).replaceAll("/+$", "");
        return Stream.concat(Stream.of(base), parts.stream().skip(1)).collect(Collectors.joining("/"));
    }

    public List<String> listFolders(String value) {
        if (clean(value).isEmpty()) {
            return Stream.of(DEFAULT, NAS).filter(name -> Files.isDirectory(root(name))).toList();
        }
        Path path = pathOf(value);
        try (Stream<Path> entries = Files.list(path)) {
            return entries.filter(entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                    .filter(entry -> !FolderWatcher.isIgnoredFolder(entry))
                    .map(entry -> entry.getFileName().toString())
                    .filter(name -> !name.startsWith("#") && !name.startsWith("@"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException error) {
            throw new InboxFolderException(path + " kann nicht gelesen werden: " + error.getMessage());
        }
    }

    public String createFolder(String parent, String name) {
        String cleanName = name.strip();
        if (cleanName.isEmpty() || cleanName.contains("/")) {
            throw new InboxFolderException("Bitte einen Ordnernamen ohne Schrägstrich angeben.");
        }
        String cleanParent = clean(parent);
        if (cleanParent.isEmpty()) {
            throw new InboxFolderException("Bitte zuerst einen Ordner öffnen.");
        }
        String folder = clean(cleanParent + "/" + cleanName);
        try {
            Files.createDirectories(pathOf(folder));
        } catch (IOException error) {
            throw new InboxFolderException("Der Ordner kann nicht angelegt werden: " + error.getMessage());
        }
        return folder;
    }

    private static Object identity(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).fileKey();
        } catch (IOException missing) {
            return null;
        }
    }

    private Path backupTarget() {
        return properties.getBackupDir().resolve(runs.loadSettings().folder());
    }

    /** Der Ordner und alle darüber, erkannt am Dateisystem statt am Namen. */
    private static Set<Object> chain(Path path) {
        Set<Object> identities = new HashSet<>();
        for (Path folder = path; folder != null; folder = folder.getParent()) {
            identities.add(identity(folder));
        }
        identities.remove(null);
        return identities;
    }

    private void checkOutsideData(Path path) {
        Set<Object> guarded = new HashSet<>();
        guarded.add(identity(properties.getMediaRoot()));
        // Der Sicherungsordner selbst liegt immer darüber, weil der NAS-Ordner in ihm eingebunden ist.
        if (!backupTarget().equals(properties.getBackupDir())) {
            guarded.add(identity(backupTarget()));
        }
        guarded.retainAll(chain(path));
        if (!guarded.isEmpty()) {
            throw new InboxFolderException(path + " liegt im Dokument- oder Sicherungsordner von DocuMan.");
        }
    }

    /** Ineinander liegende Ordner würden dieselben Dateien zweimal durchsuchen. */
    private void checkNoOverlap(Path path) {
        Object own = identity(path);
        for (Entry entry : folders()) {
            Path other;
            try {
                other = pathOf(entry.folder());
            } catch (InboxFolderException | InvalidPathException vanished) {
                continue;
            }
            if (chain(path).contains(identity(other)) || chain(other).contains(own)) {
                throw new InboxFolderException(path + " überschneidet sich mit dem überwachten Ordner "
                        + display(entry.folder()) + ".");
            }
        }
    }

    /** Zählt, was der Worker importieren und danach entfernen würde. */
    private int countFiles(Path root) throws IOException {
        Set<Object> guarded = Stream.of(properties.getMediaRoot(), properties.getBackupDir(), backupTarget())
                .map(InboxFolder::identity).filter(Objects::nonNull).collect(Collectors.toSet());
        int files = 0;
        Deque<Path> folders = new ArrayDeque<>(List.of(root));
        while (!folders.isEmpty()) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folders.pop())) {
                for (Path entry : entries) {
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        if (guarded.contains(identity(entry))) {
                            throw new InboxFolderException(root + " enthält Daten von DocuMan.");
                        }
                        if (!FolderWatcher.isIgnoredFolder(entry)) {
                            folders.push(entry);
                        }
                    } else if (!FolderWatcher.isIgnored(entry) && ++files > maxFiles()) {
                        throw new InboxFolderException(root + " enthält mehr als " + maxFiles()
                                + " Dateien. Bitte einen eigenen Ordner für den Eingang wählen.");
                    }
                }
            }
        }
        return files;
    }

    /** Prüft einen neuen Ordner. Gibt zurück, wie viele Dateien dort zum Import bereitliegen. */
    public int check(String value) {
        if (folders().size() >= maxFolders()) {
            throw new InboxFolderException("DocuMan überwacht höchstens " + maxFolders() + " Eingangsordner.");
        }
        if (clean(value).equals(NAS)) {
            throw new InboxFolderException("Bitte einen Unterordner wählen, nicht den ganzen NAS-Ordner.");
        }
        Path path = pathOf(value);
        if (!Files.isDirectory(path)) {
            throw new InboxFolderException(path + " ist kein Ordner.");
        }
        if (!accessible(path)) {
            throw new InboxFolderException("DocuMan darf in " + path
                    + " nicht schreiben, importierte Dateien blieben dort liegen.");
        }
        checkOutsideData(path);
        checkNoOverlap(path);
        try {
            return countFiles(path);
        } catch (IOException error) {
            throw new InboxFolderException(path + " kann nicht gelesen werden: " + error.getMessage());
        }
    }

    public void add(String value) {
        jdbc.sql("INSERT INTO consume_folder (folder) VALUES (?) ON CONFLICT (folder) DO NOTHING")
                .param(clean(value)).update();
    }

    /** Beendet die Überwachung eines Ordners und gibt ihn zurück. Seine Dateien bleiben liegen. */
    public String remove(long id) {
        return transaction.execute(state -> {
            List<Entry> entries = jdbc.sql("SELECT id, folder FROM consume_folder ORDER BY id FOR UPDATE")
                    .query(Entry.class).list();
            Entry entry = entries.stream().filter(candidate -> candidate.id() == id).findFirst()
                    .orElseThrow(() -> new InboxFolderException("Dieser Ordner wird nicht mehr überwacht."));
            if (entries.size() == 1) {
                throw new InboxFolderException("Ein Eingangsordner muss bleiben.");
            }
            jdbc.sql("DELETE FROM consume_folder WHERE id = ?").param(id).update();
            return entry.folder();
        });
    }

    public List<Status> status() {
        return folders().stream().map(this::status).toList();
    }

    private Status status(Entry entry) {
        String folder = entry.folder();
        Path path;
        String shown;
        try {
            path = pathOf(folder);
            shown = display(folder);
        } catch (InboxFolderException | InvalidPathException vanished) {
            path = null;
            shown = folder;
        }
        boolean reachable = path != null && Files.isDirectory(path);
        int waiting = 0;
        int rejected = 0;
        if (reachable) {
            try {
                List<Path> files = new ArrayList<>();
                FolderWatcher.walk(path, files);
                waiting = files.size();
                Path refused = path.resolve(FolderWatcher.REJECTED_DIR);
                if (Files.isDirectory(refused)) {
                    try (Stream<Path> entries = Files.list(refused)) {
                        rejected = (int) entries.filter(Files::isRegularFile).count();
                    }
                }
            } catch (IOException unreadable) {
                waiting = 0;
            }
        }
        return new Status(entry.id(), folder, shown, reachable, reachable && accessible(path), waiting, rejected);
    }
}
