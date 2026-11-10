package de.documan.service.backup;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.zaxxer.hikari.HikariDataSource;

import de.documan.config.DocuManProperties;
import de.documan.domain.BackupFrequency;
import de.documan.domain.BackupRun;
import de.documan.domain.BackupSettings;
import de.documan.domain.MailAccount;
import de.documan.domain.RunKind;
import de.documan.domain.RunStatus;
import de.documan.repo.BackupRepository;
import de.documan.repo.DocumentRepository;
import de.documan.repo.MailRepository;
import de.documan.service.SecretBox;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sichert Datenbank und Dokumente in ein Archiv und spielt es wieder ein.
 *
 * <p>Ein Archiv ist ein ZIP und lässt sich damit auch ohne DocuMan öffnen. Bereits komprimierte
 * Dateien werden nur gespeichert, das spart auf dem NAS Rechenzeit. Es beginnt mit manifest.json
 * und endet mit checksums.json. Fehlt die letzte Datei, ist es unvollständig.
 *
 * <p>Beides läuft im Worker und damit nie gleichzeitig mit der Dokumentverarbeitung.
 */
@Service
public class BackupService {

    public static final int FORMAT_VERSION = 1;
    public static final String APPLICATION = "documan-java";
    public static final String MANIFEST = "manifest.json";
    public static final String DATABASE = "database.dump";
    public static final String CHECKSUMS = "checksums.json";
    public static final String MEDIA_PREFIX = "media/";

    public static final String MANUAL = "manuell";
    public static final String AUTOMATIC = "auto";
    public static final String SAFETY = "vor-wiederherstellung";

    // Liegt im Dokumentenordner, weil Worker und Weboberfläche ihn teilen und Umbenennen
    // dort nicht über Dateisystemgrenzen geht. Solange er existiert, ist die Seite gesperrt.
    public static final String RESTORE_FOLDER = ".wiederherstellung";
    public static final String DB_RESTORED_MARKER = "datenbank-fertig";
    public static final String PART_SUFFIX = ".part";
    // Ändert sich mit jeder Wiederherstellung. Die Weboberfläche beendet damit alte Sitzungen.
    public static final String RESTORE_STAMP = ".wiederherstellung-stand";

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final Pattern ARCHIVE_NAME =
            Pattern.compile("documan-\\d{8}-\\d{6}-(manuell|auto|vor-wiederherstellung)\\.zip");
    // Punkt, # und @ kennzeichnen Systemordner, auf Synology etwa #recycle und @eaDir.
    private static final Pattern FOLDER_PART = Pattern.compile("[^\\x00-\\x1f/\\\\.#@][^\\x00-\\x1f/\\\\]*");
    private static final Set<String> STORED_SUFFIXES = Set.of(".dump", ".gif", ".jpeg", ".jpg", ".png", ".webp");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MANIFEST_LIMIT = 1_000_000;
    private static final JsonMapper JSON = JsonMapper.shared();

    private final DocuManProperties properties;
    private final BackupRepository runs;
    private final DocumentRepository documents;
    private final MailRepository mail;
    private final SecretBox secrets;
    private final DatabaseTools database;
    private final DataSource dataSource;
    private final Flyway flyway;
    private final BackupFolders folders;
    private final MigrationService migration;

    public BackupService(DocuManProperties properties, BackupRepository runs, DocumentRepository documents,
            MailRepository mail, SecretBox secrets, DatabaseTools database, DataSource dataSource, Flyway flyway,
            BackupFolders folders, MigrationService migration) {
        this.properties = properties;
        this.runs = runs;
        this.documents = documents;
        this.mail = mail;
        this.secrets = secrets;
        this.database = database;
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.folders = folders;
        this.migration = migration;
    }

    public Path restoreFolder() {
        return properties.getMediaRoot().resolve(RESTORE_FOLDER);
    }

    public boolean restoreInProgress() {
        return Files.exists(restoreFolder());
    }

    /** Prüft einen Unterordner des Sicherungsordners und gibt ihn normalisiert zurück. */
    public static String cleanFolder(String input) {
        String value = input.strip();
        List<String> parts = Stream.of(value.split("/")).map(String::strip).filter(part -> !part.isEmpty()).toList();
        if (value.startsWith("/") || parts.stream().anyMatch(part -> !FOLDER_PART.matcher(part).matches())) {
            throw new BackupException("Bitte einen Unterordner angeben, zum Beispiel „taeglich“ oder „nas/taeglich“. "
                    + "Andere Pfade sind im Container nicht erreichbar.");
        }
        return String.join("/", parts);
    }

    public void checkWritable(String folder) {
        Path target = folders.target(folder);
        folders.checkOutsideData(target);
        try {
            Files.createDirectories(target);
            Files.delete(Files.createTempFile(target, ".documan-", ".test"));
        } catch (IOException error) {
            throw new BackupException("In " + target + " kann nicht geschrieben werden: " + error, error);
        }
    }

    /** Sichtbare Unterordner für die Auswahl des Zielordners. */
    public List<String> listFolders(String folder) {
        Path target = folders.target(cleanFolder(folder));
        try (Stream<Path> entries = Files.list(target)) {
            return entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !BackupFolders.isHidden(name))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException error) {
            throw new BackupException(target + " kann nicht gelesen werden: " + reason(error), error);
        }
    }

    /** Legt einen Unterordner an und gibt seinen Pfad relativ zum Sicherungsordner zurück. */
    public String createFolder(String parent, String name) {
        String cleanName = name.strip();
        if (cleanName.isEmpty() || cleanName.contains("/")) {
            throw new BackupException("Bitte einen Ordnernamen ohne Schrägstrich angeben.");
        }
        String cleanParent = cleanFolder(parent);
        String folder = cleanFolder(cleanParent.isEmpty() ? cleanName : cleanParent + "/" + cleanName);
        try {
            Files.createDirectories(folders.target(folder));
        } catch (IOException error) {
            throw new BackupException("Der Ordner kann nicht angelegt werden: " + reason(error), error);
        }
        return folder;
    }

    private static String reason(IOException error) {
        if (error instanceof AccessDeniedException) {
            return "keine Berechtigung";
        }
        return error instanceof FileSystemException system && system.getReason() != null
                ? system.getReason() : error.getClass().getSimpleName();
    }

    /** Erster geplanter Zeitpunkt nach {@code after}. */
    public ZonedDateTime nextRun(BackupSettings config, ZonedDateTime after) {
        ZonedDateTime local = after.withZoneSameInstant(properties.getTimeZone());
        ZonedDateTime planned = local.truncatedTo(ChronoUnit.DAYS).with(config.time());
        int step = 1;
        if (config.frequency() == BackupFrequency.WEEKLY) {
            int weekday = planned.getDayOfWeek().getValue() - 1;
            planned = planned.plusDays(Math.floorMod(config.weekday() - weekday, 7));
            step = 7;
        }
        return planned.isAfter(local) ? planned : planned.plusDays(step);
    }

    public ZonedDateTime nextRun(BackupSettings config) {
        Instant from = config.lastScheduled() != null ? config.lastScheduled() : Instant.now();
        return nextRun(config, from.atZone(properties.getTimeZone()));
    }

    /** Reiht die geplante Sicherung ein, auch wenn der Termin wegen Stillstand verpasst wurde. */
    public boolean enqueueDue() {
        BackupSettings config = runs.loadSettings();
        if (!config.enabled() || runs.hasActive()) {
            return false;
        }
        Instant now = Instant.now();
        if (nextRun(config).toInstant().isAfter(now)) {
            return false;
        }
        runs.setLastScheduled(now);
        runs.enqueue(RunKind.BACKUP, AUTOMATIC, "", false);
        return true;
    }

    private record MediaFile(Path path, String name) {
    }

    private List<MediaFile> mediaFiles() throws IOException {
        Path root = properties.getMediaRoot();
        List<MediaFile> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attributes) {
                boolean hidden = folder.getParent() != null && folder.getParent().equals(root)
                        && folder.getFileName().toString().startsWith(".");
                return hidden ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                boolean hidden = file.getParent().equals(root) && file.getFileName().toString().startsWith(".");
                if (!hidden) {
                    files.add(new MediaFile(file, MEDIA_PREFIX + root.relativize(file).toString().replace('\\', '/')));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    static void stopRequested(BooleanSupplier stop) {
        if (stop != null && stop.getAsBoolean()) {
            throw new BackupException("Abgebrochen, weil der Dienst beendet wird.");
        }
    }

    private static int writeArchive(Path partial, Path dump, Map<String, Object> manifest, List<MediaFile> files,
            BooleanSupplier stop) throws IOException {
        Map<String, String> checksums = new LinkedHashMap<>();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(partial)))) {
            ArchiveFiles.addBytes(zip, MANIFEST, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            checksums.put(DATABASE, ArchiveFiles.addFile(zip, dump, DATABASE, STORED_SUFFIXES));
            for (MediaFile file : files) {
                stopRequested(stop);
                try {
                    checksums.put(file.name(), ArchiveFiles.addFile(zip, file.path(), file.name(), STORED_SUFFIXES));
                } catch (NoSuchFileException deleted) {
                    // Das Dokument wurde während der Sicherung gelöscht.
                    continue;
                }
            }
            ArchiveFiles.addBytes(zip, CHECKSUMS, JSON.writeValueAsBytes(checksums));
        }
        try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        return checksums.size() - 1;
    }

    private int schemaVersion() {
        var current = flyway.info().current();
        return current == null ? 0 : Integer.parseInt(current.getVersion().getVersion());
    }

    private int knownSchemaVersion() {
        return Stream.of(flyway.info().all())
                .mapToInt(migration -> Integer.parseInt(migration.getVersion().getVersion()))
                .max().orElse(0);
    }

    /** Fertige Sicherung. */
    public record Created(Path path, int documents, int files) {
    }

    /**
     * Schreibt ein neues Archiv.
     *
     * <p>Die Datenbank kommt zuerst: Dateien, die danach dazukommen, stören eine Wiederherstellung
     * nicht. Umgekehrt könnte der Export auf Dateien zeigen, die im Archiv fehlen.
     * Erst das fertige Archiv bekommt seinen endgültigen Namen.
     */
    public Created createBackup(String origin, BooleanSupplier stop) {
        Path target = folders.target();
        folders.checkOutsideData(target);
        try {
            Files.createDirectories(target);
        } catch (IOException error) {
            throw new BackupException(target + " kann nicht angelegt werden: " + error, error);
        }

        ZonedDateTime now = ZonedDateTime.now(properties.getTimeZone()).truncatedTo(ChronoUnit.SECONDS);
        String stem = "documan-" + now.format(STAMP) + "-" + origin;
        Path path = target.resolve(stem + ".zip");
        Path partial = target.resolve(stem + ".zip" + PART_SUFFIX);
        Path dump = target.resolve(stem + ".dump" + PART_SUFFIX);
        try {
            int documentCount = Math.toIntExact(documents.total());
            database.dump(dump);
            List<MediaFile> files = mediaFiles();
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("app", APPLICATION);
            manifest.put("format", FORMAT_VERSION);
            manifest.put("created", now.toOffsetDateTime().toString());
            manifest.put("documents", documentCount);
            manifest.put("files", files.size());
            manifest.put("schema", schemaVersion());
            int fileCount = writeArchive(partial, dump, manifest, files, stop);
            Files.move(partial, path, StandardCopyOption.ATOMIC_MOVE);
            return new Created(path, documentCount, fileCount);
        } catch (IOException error) {
            throw new BackupException("Sicherung fehlgeschlagen: " + error.getMessage(), error);
        } finally {
            deleteQuietly(partial);
            deleteQuietly(dump);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            log.warn("{} konnte nicht entfernt werden: {}", path, error.getMessage());
        }
    }

    /** Entfernt alte Archive des Zeitplans. Andere Archive löscht DocuMan nie selbst. */
    public int pruneAutomatic(Path folder, int keep) throws IOException {
        if (keep == 0) {
            return 0;
        }
        List<Path> archives;
        try (Stream<Path> entries = Files.list(folder)) {
            archives = entries
                    .filter(path -> {
                        Matcher name = ARCHIVE_NAME.matcher(path.getFileName().toString());
                        return name.matches() && name.group(1).equals(AUTOMATIC);
                    })
                    .sorted()
                    .toList();
        }
        List<Path> outdated = archives.subList(0, Math.max(0, archives.size() - keep));
        for (Path path : outdated) {
            Files.delete(path);
        }
        return outdated.size();
    }

    /** Liest nur die Angaben zum Archiv und bleibt deshalb auch bei großen Dateien schnell. */
    public Map<String, Object> readManifest(Path path) {
        Map<String, Object> manifest;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry(MANIFEST);
            if (entry == null || entry.getSize() > MANIFEST_LIMIT) {
                throw new BackupException("Die Datei ist kein DocuMan-Archiv.");
            }
            try (InputStream stream = zip.getInputStream(entry)) {
                manifest = readJson(stream.readAllBytes());
            }
        } catch (IOException | JacksonException error) {
            throw new BackupException("Das Archiv ist nicht lesbar: " + error.getMessage(), error);
        }
        if (!Integer.valueOf(FORMAT_VERSION).equals(manifest.get("format"))) {
            throw new BackupException("Das Archivformat wird von dieser Version nicht unterstützt.");
        }
        if (!APPLICATION.equals(manifest.get("app"))) {
            throw new BackupException("Das Archiv stammt nicht aus der Java-Version von DocuMan und passt nicht zu "
                    + "ihrer Datenbank.");
        }
        if (!manifest.keySet().containsAll(List.of("created", "documents", "schema"))) {
            throw new BackupException("Die Angaben im Archiv sind unvollständig.");
        }
        return manifest;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(byte[] data) {
        return JSON.readValue(data, Map.class);
    }

    private Archive archiveAt(BackupFolders.Located located) throws IOException {
        Matcher name = ARCHIVE_NAME.matcher(located.path().getFileName().toString());
        name.matches();
        Map<String, Object> manifest = null;
        String problem = "";
        try {
            manifest = readManifest(located.path());
        } catch (BackupException error) {
            problem = error.getMessage();
        }
        return new Archive(located.path(), located.relative(), name.group(1), Files.size(located.path()), manifest,
                problem);
    }

    /**
     * Archive im Sicherungsordner und im gewählten Zielordner, das neueste zuerst.
     *
     * <p>Der Ordner gilt als Quelle, nicht der Verlauf: Nach einer Neuinstallation ist die Datenbank
     * leer, und auch von Hand zurückkopierte Archive sollen erscheinen.
     */
    public List<Archive> listArchives() {
        List<Archive> archives = new ArrayList<>();
        try {
            for (BackupFolders.Located located : folders.find(ARCHIVE_NAME)) {
                archives.add(archiveAt(located));
            }
        } catch (IOException error) {
            throw new BackupException("Der Sicherungsordner ist nicht lesbar: " + error.getMessage(), error);
        }
        return archives;
    }

    /** Größe aller Archive. Im Sicherungsordner ist auch der NAS-Ordner mit dem ganzen Laufwerk eingebunden. */
    public long archiveBytes() {
        long total = 0;
        for (BackupFolders.Located located : folders.find(ARCHIVE_NAME)) {
            try {
                total += Files.size(located.path());
            } catch (IOException vanished) {
                continue;
            }
        }
        return total;
    }

    /** Löst einen relativen Pfad aus einer Anfrage auf, ohne den Sicherungsordner zu verlassen. */
    public Archive findArchive(String relative) {
        try {
            BackupFolders.Located located = folders.locate(relative, ARCHIVE_NAME);
            return located == null ? null : archiveAt(located);
        } catch (IOException notUsable) {
            return null;
        }
    }

    /** Neuere Migrationen als der Code kennt, ließen sich nicht mehr hochstufen. */
    public void checkCompatible(Map<String, Object> manifest) {
        if (((Number) manifest.get("schema")).intValue() > knownSchemaVersion()) {
            throw new BackupException("Das Archiv stammt aus einer neueren DocuMan-Version. "
                    + "Bitte zuerst DocuMan aktualisieren.");
        }
    }

    /** Lässt nur die erwarteten Dateien zu, damit ein Archiv nichts außerhalb schreiben kann. */
    public static Path memberTarget(String name, boolean regularFile, Path destination) {
        Path relative;
        try {
            relative = Path.of(name);
        } catch (InvalidPathException invalid) {
            throw new BackupException("Das Archiv enthält einen unzulässigen Eintrag: " + name);
        }
        boolean allowed = name.equals(DATABASE) || name.startsWith(MEDIA_PREFIX);
        boolean escapes = relative.isAbsolute() || Stream.of(name.split("/")).anyMatch(".."::equals);
        boolean hidden = relative.getNameCount() > 1 && relative.getName(1).toString().startsWith(".");
        if (!allowed || !regularFile || escapes || hidden) {
            throw new BackupException("Das Archiv enthält einen unzulässigen Eintrag: " + name);
        }
        return destination.resolve(relative);
    }

    /** Packt das Archiv aus und prüft dabei jede Prüfsumme. */
    public void unpack(Path path, Path destination, BooleanSupplier stop) {
        Map<String, Object> expected = null;
        Map<String, Object> actual = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                stopRequested(stop);
                if (entry.getName().equals(MANIFEST)) {
                    continue;
                }
                try (InputStream stream = zip.getInputStream(entry)) {
                    if (entry.getName().equals(CHECKSUMS)) {
                        expected = readJson(stream.readAllBytes());
                    } else {
                        Path target = memberTarget(entry.getName(), !entry.isDirectory(), destination);
                        actual.put(entry.getName(), ArchiveFiles.extract(stream, target));
                    }
                }
            }
        } catch (IOException | JacksonException error) {
            throw new BackupException("Das Archiv ist beschädigt: " + error.getMessage(), error);
        }
        if (expected == null) {
            throw new BackupException("Das Archiv ist unvollständig.");
        }
        if (!expected.equals(actual)) {
            throw new BackupException("Das Archiv ist beschädigt: Prüfsummen stimmen nicht überein.");
        }
        if (!actual.containsKey(DATABASE)) {
            throw new BackupException("Im Archiv fehlt die Datenbank.");
        }
    }

    /** Ordner, die im Archiv fehlen, müssen beim Tausch trotzdem geleert werden. */
    private void alignTopFolders(Path fresh) throws IOException {
        Files.createDirectories(fresh);
        try (Stream<Path> entries = Files.list(properties.getMediaRoot())) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry) && !name.startsWith(".")) {
                    Files.createDirectories(fresh.resolve(name));
                }
            }
        }
    }

    /** Tauscht die Dokumentordner durch Umbenennen. Darf nach einem Absturz erneut laufen. */
    private void swapMedia(Path staging) throws IOException {
        Path root = properties.getMediaRoot();
        Path fresh = staging.resolve("neu").resolve("media");
        Path previous = staging.resolve("alt");
        Files.createDirectories(previous);
        List<Path> entries;
        try (Stream<Path> stream = Files.list(fresh)) {
            entries = stream.sorted().toList();
        }
        for (Path entry : entries) {
            Path live = root.resolve(entry.getFileName().toString());
            if (Files.exists(live)) {
                Files.move(live, previous.resolve(entry.getFileName().toString()));
            }
            Files.move(entry, live);
        }
    }

    /** Postfach-Passwörter hängen am SECRET_KEY und überleben einen neuen Schlüssel nicht. */
    private int resetUndecryptableMail() {
        Long[] locked = mail.all().stream()
                .filter(account -> !account.passwordEncrypted().isEmpty())
                .filter(account -> secrets.decrypt(account.passwordEncrypted()).isEmpty())
                .map(MailAccount::id)
                .toArray(Long[]::new);
        if (locked.length > 0) {
            mail.disable(locked, "Nach der Wiederherstellung bitte das Passwort neu eingeben und aktivieren.");
        }
        return locked.length;
    }

    private void closeConnections() {
        if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
            hikari.getHikariPoolMXBean().softEvictConnections();
        }
    }

    private void writeRestoreStamp() {
        try {
            Files.writeString(properties.getMediaRoot().resolve(RESTORE_STAMP), Instant.now().toString());
        } catch (IOException error) {
            log.warn("Stand der Wiederherstellung nicht gespeichert: {}", error.getMessage());
        }
    }

    /** Macht den eingespielten Stand betriebsbereit und gibt eine Meldung zurück. */
    private String settleRestoredDatabase() {
        closeConnections();
        flyway.migrate();
        // Der Export entstand während einer laufenden Sicherung, ihr Eintrag ist also veraltet.
        runs.deleteActive();
        // Sonst würde der Zeitplan sofort nachholen und dabei womöglich alte Archive entfernen.
        runs.setLastScheduled(Instant.now());
        writeRestoreStamp();
        int locked = resetUndecryptableMail();
        if (locked > 0) {
            return "Bei " + locked + " Postfach/Postfächern muss das Passwort neu eingegeben werden, "
                    + "weil der SECRET_KEY nicht zur Sicherung passt.";
        }
        return "";
    }

    /** Spielt ein Archiv ein. Bis die Datenbank ersetzt ist, bleibt alles unverändert. */
    private RunOutcome restoreBackup(BackupRun run, BooleanSupplier stop) throws IOException {
        Archive archive = findArchive(run.archive());
        if (archive == null) {
            throw new BackupException("Das Archiv " + run.archive() + " wurde nicht gefunden.");
        }
        if (archive.manifest() == null) {
            throw new BackupException(archive.problem());
        }
        checkCompatible(archive.manifest());

        Path staging = restoreFolder();
        Path marker = staging.resolve(DB_RESTORED_MARKER);
        Files.createDirectory(staging);
        log.info("Wiederherstellung aus {} beginnt", run.archive());
        String note;
        try {
            Path fresh = staging.resolve("neu");
            unpack(archive.path(), fresh, stop);
            alignTopFolders(fresh.resolve("media"));
            if (run.safetyBackup() && documents.total() > 0) {
                Created safety = createBackup(SAFETY, stop);
                log.info("Sicherung vor der Wiederherstellung: {}", safety.path());
            }
            stopRequested(stop);
            Path script = staging.resolve("database.sql");
            database.renderScript(fresh.resolve(DATABASE), script);
            closeConnections();
            database.replaceDatabase(script);
            Files.createFile(marker);
            // Ab hier gibt es kein Zurück mehr. Mit der Datenbank ist auch der Eintrag des Laufs verschwunden.
            swapMedia(staging);
            note = settleRestoredDatabase();
        } catch (IOException | RuntimeException error) {
            // Scheitert der Tausch selbst, bleibt der Ordner samt Sperre für den Neustart liegen.
            if (Files.exists(marker)) {
                swapMedia(staging);
            }
            deleteTree(staging);
            throw error;
        }
        deleteTree(staging);
        log.info("Wiederherstellung aus {} abgeschlossen", run.archive());
        return new RunOutcome(run.archive(), null, archive.documents(), null, note);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Räumt nach einem Abbruch auf und gibt die Seite wieder frei.
     *
     * <p>War die Datenbank schon ersetzt, wird der Rest nachgeholt. Sonst gilt der alte Stand.
     */
    private void recoverRestore() throws IOException {
        Path staging = restoreFolder();
        if (!Files.exists(staging)) {
            return;
        }
        if (Files.exists(staging.resolve(DB_RESTORED_MARKER))) {
            log.warn("Unterbrochene Wiederherstellung wird abgeschlossen");
            swapMedia(staging);
            settleRestoredDatabase();
            runs.recordRestored("Nach einer Unterbrechung abgeschlossen.");
        }
        deleteTree(staging);
    }

    /** Beim Start des Workers: Was noch als laufend gilt, wurde unterbrochen. */
    public int recoverRuns() throws IOException {
        recoverRestore();
        int interrupted = runs.failRunning("Durch einen Neustart unterbrochen.");
        migration.recover();
        Path target = folders.target();
        try (var leftovers = Files.newDirectoryStream(target, "documan-*" + PART_SUFFIX)) {
            for (Path leftover : leftovers) {
                Files.delete(leftover);
            }
        } catch (IOException error) {
            // Ein nicht erreichbarer Sicherungsordner darf den Worker nicht am Start hindern.
            log.warn("Reste in {} nicht entfernt: {}", target, error.getMessage());
        }
        return interrupted;
    }

    private RunOutcome runBackup(BackupRun run, BooleanSupplier stop) throws IOException {
        Created created = createBackup(run.origin(), stop);
        String message = "";
        if (run.origin().equals(AUTOMATIC)) {
            try {
                int removed = pruneAutomatic(created.path().getParent(), runs.loadSettings().keep());
                if (removed > 0) {
                    message = removed + " ältere(s) Archiv(e) des Zeitplans entfernt.";
                }
            } catch (IOException error) {
                message = "Alte Archive konnten nicht entfernt werden: " + error.getMessage();
            }
        }
        String relative = properties.getBackupDir().relativize(created.path()).toString().replace('\\', '/');
        return new RunOutcome(relative, Files.size(created.path()), created.documents(), created.files(), message);
    }

    /** Führt den nächsten Auftrag aus. Gibt false zurück, wenn nichts ansteht. */
    public boolean runNext(BooleanSupplier stop) {
        var claimed = runs.claimNext();
        if (claimed.isEmpty()) {
            return false;
        }
        BackupRun run = claimed.get();
        try {
            RunOutcome outcome = switch (run.kind()) {
                case BACKUP -> runBackup(run, stop);
                case RESTORE -> restoreBackup(run, stop);
                case EXPORT -> migration.export(stop);
                case IMPORT -> migration.importArchive(run.archive(), stop);
            };
            runs.finish(run, RunStatus.DONE, outcome.archive(), outcome.size(), outcome.documents(), outcome.files(),
                    outcome.message(), "");
            log.info("Lauf #{} fertig: {}", run.id(), outcome.archive());
        } catch (IOException | RuntimeException error) {
            log.error("Lauf #{} fehlgeschlagen", run.id(), error);
            runs.finish(run, RunStatus.FAILED, run.archive(), null, null, null, "", String.valueOf(error.getMessage()));
        }
        return true;
    }

}
