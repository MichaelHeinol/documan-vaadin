package de.documan.service.backup;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import de.documan.config.DocuManProperties;
import de.documan.domain.AfterImport;
import de.documan.domain.Algorithm;
import de.documan.domain.AppUser;
import de.documan.domain.AttachmentFilter;
import de.documan.domain.Choice;
import de.documan.domain.Document;
import de.documan.domain.DocumentSource;
import de.documan.domain.DocumentStatus;
import de.documan.domain.MailAccount;
import de.documan.domain.MailAuth;
import de.documan.domain.MailRule;
import de.documan.domain.MailRuleField;
import de.documan.domain.MailHeaders;
import de.documan.domain.MailSecurity;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.DocumentRepository;
import de.documan.repo.DocumentRepository.ImportedDocument;
import de.documan.repo.MailRepository;
import de.documan.repo.MasterRepository;
import de.documan.repo.UserRepository;
import de.documan.service.CategoryPaths;
import de.documan.service.Reindexer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bringt Dokumente und Stammdaten von der Django- in die Java-Version und zurück.
 *
 * <p>Das Format beschreibt docs/umzugsformat.md. Datensicherungen bleiben davon getrennt, denn sie
 * enthalten einen Datenbank-Dump, der nur zur eigenen Version passt.
 *
 * <p>Beides läuft im Worker und damit nie gleichzeitig mit der Dokumentverarbeitung.
 */
@Service
public class MigrationService {

    public static final String FORMAT = "documan-umzug";
    public static final int VERSION = 1;
    public static final String SOURCE = "java";
    public static final String MANIFEST = "manifest.json";
    public static final String MASTER_DATA = "stammdaten.json";
    public static final String DOCUMENTS = "dokumente.jsonl";
    public static final String CHECKSUMS = "checksums.json";
    public static final String MEDIA_PREFIX = "media/";
    // Liegt im Dokumentenordner, damit Dateien durch Umbenennen an ihren Platz kommen.
    public static final String STAGING_FOLDER = ".umzug";
    public static final String MAIL_NOTE = "Nach dem Umzug bitte das Passwort neu eingeben und aktivieren.";

    static final Pattern ARCHIVE_NAME = Pattern.compile("documan-umzug-\\d{8}-\\d{6}-(django|java)\\.zip");

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);
    private static final Set<String> STORED_SUFFIXES = Set.of(".gif", ".jpeg", ".jpg", ".png", ".webp");
    private static final Set<String> SOURCES = Set.of("django", "java");
    private static final List<String> MEDIA_FOLDERS = List.of("originals/", "archive/", "thumbnails/");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String JOURNAL = "verschoben.txt";
    private static final String PART_SUFFIX = ".part";
    private static final int MANIFEST_LIMIT = 1_000_000;
    private static final int MAX_NAMED_MISSING = 10;
    private static final int UNUSABLE_PASSWORD_BYTES = 32;
    private static final JsonMapper JSON = JsonMapper.shared();

    private final DocuManProperties properties;
    private final BackupFolders folders;
    private final DocumentRepository documents;
    private final MasterRepository master;
    private final UserRepository users;
    private final MailRepository mail;
    private final Reindexer reindexer;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    public MigrationService(DocuManProperties properties, BackupFolders folders, DocumentRepository documents,
            MasterRepository master, UserRepository users, MailRepository mail, Reindexer reindexer,
            PasswordEncoder encoder, PlatformTransactionManager transactions) {
        this.properties = properties;
        this.folders = folders;
        this.documents = documents;
        this.master = master;
        this.users = users;
        this.mail = mail;
        this.reindexer = reindexer;
        this.encoder = encoder;
        this.transaction = new TransactionTemplate(transactions);
    }

    private Path stagingFolder() {
        return properties.getMediaRoot().resolve(STAGING_FOLDER);
    }

    // Erstellen

    /** Dateien eines Dokuments, die wirklich auf dem Datenträger liegen. */
    private record Planned(long id, String title, String archive, String thumbnail) {
    }

    private String existing(String relative) {
        return !relative.isEmpty() && Files.isRegularFile(properties.getMediaRoot().resolve(relative)) ? relative : "";
    }

    /** Das Manifest steht vorn im ZIP, die Zahlen müssen also vor dem Schreiben feststehen. */
    private List<Planned> plan(List<String> missing) {
        List<Planned> planned = new ArrayList<>();
        for (DocumentRepository.StoredFiles files : documents.storedFiles()) {
            if (existing(files.original()).isEmpty()) {
                missing.add(files.title());
            } else {
                planned.add(new Planned(files.id(), files.title(), existing(files.archive()),
                        existing(files.thumbnail())));
            }
        }
        return planned;
    }

    private static List<String> mediaOf(Document document, Planned plan) {
        return Stream.of(document.original(), plan.archive(), plan.thumbnail()).filter(file -> !file.isEmpty()).toList();
    }

    private static int countFiles(List<Planned> planned) {
        return planned.stream()
                .mapToInt(plan -> 1 + (plan.archive().isEmpty() ? 0 : 1) + (plan.thumbnail().isEmpty() ? 0 : 1))
                .sum();
    }

    private static Map<String, Object> masterEntry(MasterItem item, boolean withColor) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", item.name());
        entry.put("algorithm", item.algorithm().value());
        entry.put("match", item.match());
        entry.put("ignore_case", item.ignoreCase());
        if (withColor) {
            entry.put("color", item.color());
        }
        return entry;
    }

    private static <T> String nameOf(Map<Long, String> names, Long id) {
        return id == null ? null : names.get(id);
    }

    private static Map<Long, String> namesById(List<MasterItem> items) {
        Map<Long, String> names = new LinkedHashMap<>();
        items.forEach(item -> names.put(item.id(), item.name()));
        return names;
    }

    private Map<String, Object> masterData() {
        List<MasterItem> correspondents = master.all(MasterKind.CORRESPONDENT);
        List<MasterItem> types = master.all(MasterKind.DOCUMENT_TYPE);
        List<MasterItem> tags = master.all(MasterKind.TAG);
        Map<Long, String> correspondentNames = namesById(correspondents);
        Map<Long, String> typeNames = namesById(types);
        Map<Long, String> tagNames = namesById(tags);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("correspondents", correspondents.stream().map(item -> masterEntry(item, false)).toList());
        // Die Kategorie jedes Dokumenttyps als Liste der Stufen, leer ohne.
        CategoryPaths paths = new CategoryPaths(master.categories());
        Map<Long, Long> typeCategories = master.typeCategories();
        data.put("document_types", types.stream().map(item -> {
            Map<String, Object> entry = masterEntry(item, false);
            entry.put("category", paths.path(typeCategories.get(item.id())));
            return entry;
        }).toList());
        data.put("tags", tags.stream().map(item -> masterEntry(item, true)).toList());
        data.put("users", users.all().stream().map(MigrationService::userEntry).toList());
        data.put("mail_accounts", mail.all().stream()
                .map(account -> mailEntry(account, correspondentNames, typeNames, tagNames)).toList());
        return data;
    }

    private static Map<String, Object> userEntry(AppUser user) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("username", user.username());
        entry.put("email", user.email());
        entry.put("first_name", user.firstName());
        entry.put("last_name", user.lastName());
        entry.put("is_admin", user.superuser());
        entry.put("active", user.active());
        return entry;
    }

    private Map<String, Object> mailEntry(MailAccount account, Map<Long, String> correspondents,
            Map<Long, String> types, Map<Long, String> tags) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", account.name());
        entry.put("host", account.host());
        entry.put("port", account.port());
        entry.put("security", account.security().value());
        entry.put("username", account.username());
        entry.put("folder", account.folder());
        entry.put("only_unseen", account.onlyUnseen());
        entry.put("poll_minutes", account.pollMinutes());
        entry.put("maximum_age_days", account.maximumAgeDays());
        entry.put("filter_from", account.filterFrom());
        entry.put("filter_subject", account.filterSubject());
        entry.put("attachments", account.attachments().value());
        entry.put("after_import", account.afterImport().value());
        entry.put("target_folder", account.targetFolder());
        entry.put("auth_method", account.auth().value());
        entry.put("skip_spam", account.skipSpam());
        entry.put("exclusion_rules", account.exclusionRules().stream()
                .map(rule -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("field", rule.field().value());
                    row.put("value", rule.value());
                    return row;
                }).toList());
        entry.put("assign_correspondent", nameOf(correspondents, account.assignCorrespondentId()));
        entry.put("assign_document_type", nameOf(types, account.assignDocumentTypeId()));
        entry.put("assign_tags", account.assignTagIds().stream().map(tags::get).toList());
        entry.put("processed", mail.processed(account.id()).stream()
                .map(item -> {
                    Map<String, Object> processed = new LinkedHashMap<>();
                    processed.put("message_id", item.messageId());
                    processed.put("subject", item.subject());
                    return processed;
                }).toList());
        return entry;
    }

    private String timestamp(java.time.Instant moment) {
        return moment.atZone(properties.getTimeZone()).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private Map<String, Object> documentEntry(Document document, Planned plan, CategoryPaths paths) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("title", document.title());
        entry.put("correspondent", document.correspondentName());
        entry.put("document_type", document.documentTypeName());
        // Nur die eigene Kategorie, die des Typs steht beim Dokumenttyp.
        entry.put("category", paths.path(document.ownCategoryId()));
        entry.put("tags", document.tags().stream().map(MasterItem::name).toList());
        entry.put("document_date", document.documentDate() == null ? null : document.documentDate().toString());
        entry.put("notes", document.notes());
        entry.put("inbox", document.inbox());
        entry.put("content", document.content());
        entry.put("page_count", document.pageCount());
        entry.put("original", document.original());
        entry.put("archive", plan.archive());
        entry.put("thumbnail", plan.thumbnail());
        entry.put("original_filename", document.originalFilename());
        entry.put("mime_type", document.mimeType());
        entry.put("size", document.size());
        entry.put("checksum", document.checksum());
        entry.put("source", document.source().value());
        entry.put("source_detail", document.sourceDetail());
        entry.put("added_by", document.addedBy());
        entry.put("mail_from", document.mail().from());
        entry.put("mail_to", document.mail().to());
        entry.put("mail_subject", document.mail().subject());
        entry.put("mail_date", document.mail().date() == null ? null : timestamp(document.mail().date()));
        entry.put("mail_message_id", document.mail().messageId());
        entry.put("status", document.status().value());
        entry.put("error", document.error());
        entry.put("added", timestamp(document.added()));
        entry.put("modified", timestamp(document.modified()));
        return entry;
    }

    /** Hält das ZIP offen, wenn der Schreiber dieses Eintrags geschlossen wird. */
    private static OutputStream entryOf(ZipOutputStream zip, MessageDigest digest) {
        return new DigestOutputStream(new FilterOutputStream(new BufferedOutputStream(zip)) {
            @Override
            public void write(byte[] data, int offset, int length) throws IOException {
                out.write(data, offset, length);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        }, digest);
    }

    private void writeDocuments(ZipOutputStream zip, List<Planned> planned, Map<String, String> checksums,
            List<String> mediaFiles, BooleanSupplier stop) throws IOException {
        MessageDigest digest = ArchiveFiles.sha256();
        zip.putNextEntry(new ZipEntry(DOCUMENTS));
        CategoryPaths paths = new CategoryPaths(master.categories());
        try (OutputStream output = entryOf(zip, digest)) {
            for (Planned plan : planned) {
                BackupService.stopRequested(stop);
                Document document = documents.find(plan.id())
                        .orElseThrow(() -> new BackupException("„" + plan.title() + "“ wurde währenddessen gelöscht."));
                output.write(JSON.writeValueAsBytes(documentEntry(document, plan, paths)));
                output.write('\n');
                mediaFiles.addAll(mediaOf(document, plan));
            }
        }
        zip.closeEntry();
        checksums.put(DOCUMENTS, ArchiveFiles.hex(digest));
    }

    private void writeArchive(Path partial, ZonedDateTime now, List<Planned> planned, BooleanSupplier stop)
            throws IOException {
        Map<String, String> checksums = new LinkedHashMap<>();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", FORMAT);
        manifest.put("version", VERSION);
        manifest.put("source", SOURCE);
        manifest.put("created", now.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        manifest.put("documents", planned.size());
        manifest.put("files", countFiles(planned));

        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(partial)))) {
            ArchiveFiles.addBytes(zip, MANIFEST, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            byte[] data = JSON.writeValueAsBytes(masterData());
            ArchiveFiles.addBytes(zip, MASTER_DATA, data);
            MessageDigest digest = ArchiveFiles.sha256();
            digest.update(data);
            checksums.put(MASTER_DATA, ArchiveFiles.hex(digest));
            List<String> mediaFiles = new ArrayList<>();
            writeDocuments(zip, planned, checksums, mediaFiles, stop);
            for (String file : mediaFiles) {
                BackupService.stopRequested(stop);
                // Verschwindet eine Datei jetzt noch, stimmt das Manifest nicht mehr: Der Lauf scheitert.
                String name = MEDIA_PREFIX + file;
                checksums.put(name, ArchiveFiles.addFile(zip, properties.getMediaRoot().resolve(file), name,
                        STORED_SUFFIXES));
            }
            ArchiveFiles.addBytes(zip, CHECKSUMS, JSON.writeValueAsBytes(checksums));
        }
    }

    private static String missingNote(List<String> missing) {
        if (missing.isEmpty()) {
            return "";
        }
        String named = String.join(", ", missing.subList(0, Math.min(missing.size(), MAX_NAMED_MISSING)));
        String more = missing.size() > MAX_NAMED_MISSING ? " und weitere" : "";
        return missing.size() + " Dokument(e) ohne Originaldatei ausgelassen: " + named + more;
    }

    /** Schreibt ein neues Umzugsarchiv in den aktuellen Zielordner. */
    RunOutcome export(BooleanSupplier stop) throws IOException {
        Path target = folders.target();
        folders.checkOutsideData(target);
        try {
            Files.createDirectories(target);
        } catch (IOException error) {
            throw new BackupException(target + " kann nicht angelegt werden: " + error, error);
        }
        ZonedDateTime now = ZonedDateTime.now(properties.getTimeZone()).truncatedTo(ChronoUnit.SECONDS);
        String name = "documan-umzug-" + now.format(STAMP) + "-" + SOURCE + ".zip";
        Path path = target.resolve(name);
        Path partial = target.resolve(name + PART_SUFFIX);
        List<String> missing = new ArrayList<>();
        try {
            List<Planned> planned = plan(missing);
            writeArchive(partial, now, planned, stop);
            Files.move(partial, path, StandardCopyOption.ATOMIC_MOVE);
            String relative = properties.getBackupDir().relativize(path).toString().replace('\\', '/');
            return new RunOutcome(relative, Files.size(path), planned.size(), countFiles(planned), missingNote(missing));
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    // Lesen

    /** Liest nur die Angaben zum Archiv und bleibt deshalb auch bei großen Dateien schnell. */
    public Map<String, Object> readManifest(Path path) {
        Map<String, Object> manifest;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry(MANIFEST);
            if (entry == null || entry.getSize() > MANIFEST_LIMIT) {
                throw new BackupException("Die Datei ist kein Umzugsarchiv.");
            }
            try (InputStream stream = zip.getInputStream(entry)) {
                manifest = map(stream.readAllBytes());
            }
        } catch (IOException | JacksonException error) {
            throw new BackupException("Das Archiv ist nicht lesbar: " + error.getMessage(), error);
        }
        if (!FORMAT.equals(manifest.get("format")) || !Integer.valueOf(VERSION).equals(manifest.get("version"))) {
            throw new BackupException("Dieses Umzugsformat wird von dieser Version nicht unterstützt.");
        }
        if (!SOURCES.contains(manifest.get("source"))
                || !manifest.keySet().containsAll(List.of("created", "documents", "files"))) {
            throw new BackupException("Die Angaben im Archiv sind unvollständig.");
        }
        try {
            OffsetDateTime.parse(String.valueOf(manifest.get("created")));
            ((Number) manifest.get("documents")).intValue();
        } catch (DateTimeParseException | ClassCastException | NullPointerException invalid) {
            throw new BackupException("Die Angaben im Archiv sind unvollständig.", invalid);
        }
        return manifest;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(byte[] data) {
        return JSON.readValue(data, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String line) {
        return JSON.readValue(line, Map.class);
    }

    private MigrationArchive archiveAt(BackupFolders.Located located) throws IOException {
        Map<String, Object> manifest = null;
        String problem = "";
        try {
            manifest = readManifest(located.path());
        } catch (BackupException error) {
            problem = error.getMessage();
        }
        return new MigrationArchive(located.path(), located.relative(), Files.size(located.path()), manifest, problem);
    }

    /** Umzugsarchive im Sicherungsordner und im gewählten Zielordner, das neueste zuerst. */
    public List<MigrationArchive> listArchives() {
        List<MigrationArchive> archives = new ArrayList<>();
        try {
            for (BackupFolders.Located located : folders.find(ARCHIVE_NAME)) {
                archives.add(archiveAt(located));
            }
        } catch (IOException error) {
            throw new BackupException("Der Sicherungsordner ist nicht lesbar: " + error.getMessage(), error);
        }
        return archives;
    }

    public MigrationArchive findArchive(String relative) {
        try {
            BackupFolders.Located located = folders.locate(relative, ARCHIVE_NAME);
            return located == null ? null : archiveAt(located);
        } catch (IOException notUsable) {
            return null;
        }
    }

    // Einspielen

    private static boolean allowedMediaPath(String relative) {
        if (relative.contains("\\") || relative.startsWith("/")
                || MEDIA_FOLDERS.stream().noneMatch(relative::startsWith)) {
            return false;
        }
        return Stream.of(relative.split("/")).noneMatch(part -> part.isEmpty() || part.startsWith("."));
    }

    /** Lässt nur die erwarteten Dateien zu, damit ein Archiv nichts außerhalb schreiben kann. */
    public static Path memberTarget(String name, Path destination) {
        boolean known = name.equals(MASTER_DATA) || name.equals(DOCUMENTS);
        boolean media = name.startsWith(MEDIA_PREFIX) && allowedMediaPath(name.substring(MEDIA_PREFIX.length()));
        if (!known && !media) {
            throw new BackupException("Das Archiv enthält einen unzulässigen Eintrag: " + name);
        }
        return destination.resolve(name);
    }

    /** Packt das Archiv aus und prüft dabei jede Prüfsumme. Gibt die Dateien unter media/ zurück. */
    private Set<String> unpack(Path path, Path destination, BooleanSupplier stop) {
        Map<String, Object> expected = null;
        Map<String, Object> actual = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                BackupService.stopRequested(stop);
                if (entry.getName().equals(MANIFEST)) {
                    continue;
                }
                try (InputStream stream = zip.getInputStream(entry)) {
                    if (entry.getName().equals(CHECKSUMS)) {
                        expected = map(stream.readAllBytes());
                    } else {
                        if (entry.isDirectory()) {
                            throw new BackupException("Das Archiv enthält einen unzulässigen Eintrag: " + entry.getName());
                        }
                        actual.put(entry.getName(), ArchiveFiles.extract(stream, memberTarget(entry.getName(), destination)));
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
        if (!actual.containsKey(MASTER_DATA) || !actual.containsKey(DOCUMENTS)) {
            throw new BackupException("Das Archiv ist unvollständig.");
        }
        Set<String> media = new HashSet<>();
        for (String name : actual.keySet()) {
            if (name.startsWith(MEDIA_PREFIX)) {
                media.add(name.substring(MEDIA_PREFIX.length()));
            }
        }
        return media;
    }

    private static <E extends Enum<E> & Choice> E choice(Class<E> type, Object value, String field) {
        return Choice.find(type, String.valueOf(value))
                .orElseThrow(() -> new BackupException("Unbekannter Wert für " + field + ": " + value));
    }

    private static String text(Map<String, Object> entry, String field) {
        Object value = entry.get(field);
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullableText(Map<String, Object> entry, String field) {
        Object value = entry.get(field);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> data, String field) {
        Object value = data.get(field);
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(Map<String, Object> entry, String field) {
        Object value = entry.get(field);
        return value == null ? List.of() : (List<String>) value;
    }

    private static void validateMaster(Map<String, Object> data) {
        for (String list : List.of("correspondents", "document_types", "tags")) {
            for (Map<String, Object> entry : entries(data, list)) {
                choice(Algorithm.class, entry.get("algorithm"), "algorithm");
            }
        }
        for (Map<String, Object> entry : entries(data, "mail_accounts")) {
            choice(MailSecurity.class, entry.get("security"), "security");
            choice(AttachmentFilter.class, entry.get("attachments"), "attachments");
            choice(AfterImport.class, entry.get("after_import"), "after_import");
        }
    }

    private static void requireFile(Set<String> media, String relative, String folder) {
        if (!relative.startsWith(folder) || !media.contains(relative)) {
            throw new BackupException("Eine genannte Datei liegt nicht im Archiv: " + relative);
        }
    }

    private static void validateDocument(Map<String, Object> entry, Set<String> media) {
        choice(DocumentStatus.class, entry.get("status"), "status");
        choice(DocumentSource.class, entry.get("source"), "source");
        requireFile(media, text(entry, "original"), "originals/");
        if (!text(entry, "archive").isEmpty()) {
            requireFile(media, text(entry, "archive"), "archive/");
        }
        if (!text(entry, "thumbnail").isEmpty()) {
            requireFile(media, text(entry, "thumbnail"), "thumbnails/");
        }
        if (!text(entry, "checksum").matches("[0-9a-f]{64}")) {
            throw new BackupException("Ungültige Prüfsumme bei „" + text(entry, "title") + "“.");
        }
    }

    private static void forEachDocument(Path file, Consumer<Map<String, Object>> action) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    action.accept(map(line));
                }
            }
        }
    }

    private Map<String, Long> importMaster(MasterKind kind, List<Map<String, Object>> list) {
        Map<String, Long> ids = new LinkedHashMap<>();
        master.all(kind).forEach(item -> ids.put(item.name(), item.id()));
        for (Map<String, Object> entry : list) {
            String name = text(entry, "name");
            if (!ids.containsKey(name)) {
                String color = kind.hasColor() ? text(entry, "color") : null;
                ids.put(name, master.insert(kind, name, choice(Algorithm.class, entry.get("algorithm"), "algorithm"),
                        text(entry, "match"), !Boolean.FALSE.equals(entry.get("ignore_case")), color));
            }
        }
        return ids;
    }

    /** Der Kategoriepfad eines Eintrags, oberste Stufe zuerst. Ältere Archive haben keinen. */
    private static List<String> categoryPath(Map<String, Object> entry) {
        Object value = entry.get("category");
        if (value instanceof List<?> names) {
            return names.stream().map(String::valueOf).limit(CategoryPaths.MAX_LEVELS).toList();
        }
        return value instanceof String name && !name.isBlank() ? List.of(name) : List.of();
    }

    /** Ordnet Dokumenttypen ihrer Kategorie zu und legt fehlende Stufen an. */
    private void importCategories(List<Map<String, Object>> list, Map<String, Long> types) {
        for (Map<String, Object> entry : list) {
            Long type = types.get(text(entry, "name"));
            if (type != null) {
                master.categoryChain(categoryPath(entry)).ifPresent(category -> master.setCategory(type, category));
            }
        }
    }

    /** Neue Benutzer bekommen einen Hash aus Zufall: Damit lässt sich niemand anmelden. */
    private String unusablePassword() {
        byte[] bytes = new byte[UNUSABLE_PASSWORD_BYTES];
        random.nextBytes(bytes);
        return encoder.encode(Base64.getEncoder().encodeToString(bytes));
    }

    /** Gibt die Zahl der neuen Benutzer zurück. Vorhandene behalten Passwort und Rechte. */
    private int importUsers(List<Map<String, Object>> list) {
        int created = 0;
        for (Map<String, Object> entry : list) {
            String username = text(entry, "username");
            String email = text(entry, "email");
            var existing = users.findByUsername(username);
            if (existing.isPresent()) {
                if (existing.get().email().isEmpty() && !email.isEmpty()) {
                    users.setEmail(existing.get().id(), email);
                }
            } else {
                long id = users.insert(username, unusablePassword(), Boolean.TRUE.equals(entry.get("is_admin")),
                        !Boolean.FALSE.equals(entry.get("active")), email);
                users.setNames(id, text(entry, "first_name"), text(entry, "last_name"));
                created++;
            }
        }
        return created;
    }

    private static Long lookup(Map<String, Long> ids, String name, String label) {
        if (name == null) {
            return null;
        }
        Long id = ids.get(name);
        if (id == null) {
            throw new BackupException(label + " „" + name + "“ fehlt in den Stammdaten.");
        }
        return id;
    }

    /** Gibt die Zahl der neu angelegten und damit deaktivierten Postfächer zurück. */
    private int importMail(List<Map<String, Object>> list, Map<String, Long> correspondents, Map<String, Long> types,
            Map<String, Long> tags) {
        Set<String> known = new HashSet<>(mail.all().stream().map(MailAccount::name).toList());
        int created = 0;
        for (Map<String, Object> entry : list) {
            String name = text(entry, "name");
            if (known.contains(name)) {
                continue;
            }
            long id = mail.insert(new MailAccount(0, name, false, text(entry, "host"),
                    ((Number) entry.get("port")).intValue(), choice(MailSecurity.class, entry.get("security"), "security"),
                    text(entry, "username"), "", text(entry, "folder"), Boolean.TRUE.equals(entry.get("only_unseen")),
                    ((Number) entry.get("maximum_age_days")).intValue(), text(entry, "filter_from"),
                    text(entry, "filter_subject"), choice(AttachmentFilter.class, entry.get("attachments"), "attachments"),
                    choice(AfterImport.class, entry.get("after_import"), "after_import"), text(entry, "target_folder"),
                    lookup(correspondents, nullableText(entry, "assign_correspondent"), "Absendername"),
                    lookup(types, nullableText(entry, "assign_document_type"), "Dokumenttyp"),
                    names(entry, "assign_tags").stream().map(tag -> lookup(tags, tag, "Tag")).toList(), null, "", 0,
                    entry.get("auth_method") == null ? MailAuth.PASSWORD
                            : choice(MailAuth.class, entry.get("auth_method"), "auth_method"),
                    !Boolean.FALSE.equals(entry.get("skip_spam")),
                    entries(entry, "exclusion_rules").stream()
                            .filter(rule -> !text(rule, "value").isBlank())
                            .map(rule -> new MailRule(choice(MailRuleField.class, rule.get("field"), "field"),
                                    text(rule, "value")))
                            .toList(),
                    entry.get("poll_minutes") == null ? MailAccount.DEFAULT_POLL_MINUTES
                            : ((Number) entry.get("poll_minutes")).intValue()));
            mail.disable(new Long[] {id}, MAIL_NOTE);
            for (Map<String, Object> processed : entries(entry, "processed")) {
                mail.rememberMessage(id, text(processed, "message_id"), text(processed, "subject"));
            }
            created++;
        }
        return created;
    }

    private void importDocument(Map<String, Object> entry, Map<String, Long> correspondents, Map<String, Long> types,
            Map<String, Long> tags) {
        DocumentStatus status = choice(DocumentStatus.class, entry.get("status"), "status");
        Object date = entry.get("document_date");
        Object pages = entry.get("page_count");
        // Die Absenderfelder fehlen in älteren Archiven derselben Formatversion.
        String sent = text(entry, "mail_date");
        MailHeaders mail = new MailHeaders(text(entry, "mail_from"), text(entry, "mail_to"),
                text(entry, "mail_subject"), sent.isEmpty() ? null : OffsetDateTime.parse(sent).toInstant(),
                text(entry, "mail_message_id"));
        long id = documents.insertImported(new ImportedDocument(text(entry, "title"),
                lookup(correspondents, nullableText(entry, "correspondent"), "Absendername"),
                lookup(types, nullableText(entry, "document_type"), "Dokumenttyp"),
                date == null ? null : java.time.LocalDate.parse(String.valueOf(date)), text(entry, "notes"),
                Boolean.TRUE.equals(entry.get("inbox")), text(entry, "content"),
                pages == null ? null : ((Number) pages).intValue(), text(entry, "original"), text(entry, "archive"),
                text(entry, "thumbnail"), text(entry, "original_filename"), text(entry, "mime_type"),
                ((Number) entry.get("size")).longValue(), text(entry, "checksum"),
                choice(DocumentSource.class, entry.get("source"), "source"), text(entry, "source_detail"),
                text(entry, "added_by"), mail, status == DocumentStatus.PROCESSING ? DocumentStatus.PENDING : status, text(entry, "error"),
                OffsetDateTime.parse(text(entry, "added")).toInstant(),
                OffsetDateTime.parse(text(entry, "modified")).toInstant()));
        documents.addTags(id, names(entry, "tags").stream().map(tag -> lookup(tags, tag, "Tag")).toList());
        Long category = master.categoryChain(categoryPath(entry)).orElse(null);
        if (category != null) {
            documents.setCategory(new Long[] {id}, category);
        }
        reindexer.reindex(id);
    }

    private record Imported(int documents, int newUsers, int disabledMail) {
    }

    private void requireEmptyTarget() {
        if (documents.total() > 0) {
            throw new BackupException("Das Ziel enthält bereits Dokumente. Ein Umzug geht nur in ein leeres System.");
        }
    }

    private Imported importDatabase(Map<String, Object> data, Path documentFile) {
        return transaction.execute(status -> {
            requireEmptyTarget();
            Map<String, Long> correspondents = importMaster(MasterKind.CORRESPONDENT, entries(data, "correspondents"));
            Map<String, Long> types = importMaster(MasterKind.DOCUMENT_TYPE, entries(data, "document_types"));
            importCategories(entries(data, "document_types"), types);
            Map<String, Long> tags = importMaster(MasterKind.TAG, entries(data, "tags"));
            int newUsers = importUsers(entries(data, "users"));
            int disabledMail = importMail(entries(data, "mail_accounts"), correspondents, types, tags);
            int[] count = {0};
            try {
                forEachDocument(documentFile, entry -> {
                    importDocument(entry, correspondents, types, tags);
                    count[0]++;
                });
            } catch (IOException error) {
                throw new BackupException("Das Archiv ist nicht lesbar: " + error.getMessage(), error);
            }
            return new Imported(count[0], newUsers, disabledMail);
        });
    }

    private void moveIn(Path staged, Set<String> media, List<String> moved) throws IOException {
        Path root = properties.getMediaRoot();
        for (String file : media) {
            Path target = root.resolve(file);
            if (Files.exists(target)) {
                throw new BackupException("Am Ziel liegt bereits eine Datei: " + file);
            }
            Files.createDirectories(target.getParent());
            Files.move(staged.resolve(file), target);
            moved.add(file);
        }
    }

    /** Legt verschobene Dateien in die Zwischenablage zurück und räumt leere Ordner weg. */
    private void moveBack(Path staged, List<String> moved) throws IOException {
        Path root = properties.getMediaRoot();
        for (String file : moved) {
            Path target = root.resolve(file);
            if (Files.exists(target) && !Files.exists(staged.resolve(file))) {
                Files.createDirectories(staged.resolve(file).getParent());
                Files.move(target, staged.resolve(file));
            }
            for (Path folder = target.getParent(); !folder.equals(root); folder = folder.getParent()) {
                if (!isEmpty(folder)) {
                    break;
                }
                Files.delete(folder);
            }
        }
    }

    private static boolean isEmpty(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.findAny().isEmpty();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path folder, IOException error) throws IOException {
                Files.delete(folder);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String importNote(Imported imported) {
        return "Benutzer ohne Passwort: " + imported.newUsers() + ". Deaktivierte Postfächer: "
                + imported.disabledMail() + ".";
    }

    /** Spielt ein Umzugsarchiv ein. Scheitert etwas, bleibt das Ziel, wie es war. */
    RunOutcome importArchive(String relative, BooleanSupplier stop) throws IOException {
        requireEmptyTarget();
        MigrationArchive archive = findArchive(relative);
        if (archive == null) {
            throw new BackupException("Das Archiv " + relative + " wurde nicht gefunden.");
        }
        if (!archive.readable()) {
            throw new BackupException(archive.problem());
        }

        Path staging = stagingFolder();
        Files.createDirectory(staging);
        Path fresh = staging.resolve("neu");
        Path staged = fresh.resolve("media");
        List<String> moved = new ArrayList<>();
        log.info("Umzug aus {} beginnt", relative);
        try {
            Set<String> media = unpack(archive.path(), fresh, stop);
            Map<String, Object> data = map(Files.readAllBytes(fresh.resolve(MASTER_DATA)));
            validateMaster(data);
            forEachDocument(fresh.resolve(DOCUMENTS), entry -> validateDocument(entry, media));
            BackupService.stopRequested(stop);

            // Das Protokoll steht vor dem ersten Verschieben: Es trägt die Aufräumarbeit nach einem Absturz.
            Files.write(staging.resolve(JOURNAL), media, StandardCharsets.UTF_8);
            moveIn(staged, media, moved);
            Imported imported = importDatabase(data, fresh.resolve(DOCUMENTS));
            deleteTree(staging);
            log.info("Umzug aus {} abgeschlossen", relative);
            return new RunOutcome(relative, archive.size(), imported.documents(), media.size(), importNote(imported));
        } catch (IOException | RuntimeException error) {
            moveBack(staged, moved);
            deleteTree(staging);
            if (error instanceof JacksonException || error instanceof ClassCastException
                    || error instanceof NullPointerException || error instanceof DateTimeParseException) {
                throw new BackupException("Das Archiv enthält ungültige Daten: " + error.getMessage(), error);
            }
            throw error;
        }
    }

    /** Beim Start des Workers: Stehen Dokumente in der Datenbank, war der Umzug fertig. */
    public void recover() throws IOException {
        Path staging = stagingFolder();
        if (!Files.exists(staging)) {
            return;
        }
        if (documents.total() == 0 && Files.exists(staging.resolve(JOURNAL))) {
            log.warn("Unterbrochener Umzug wird zurückgenommen");
            moveBack(staging.resolve("neu").resolve("media"), Files.readAllLines(staging.resolve(JOURNAL)));
        }
        deleteTree(staging);
    }

    /** Für Tests und die Oberfläche: sortierte Einträge eines Archivs. */
    public static List<String> entryNames(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            List<String> names = new ArrayList<>(Collections.list(zip.entries()).stream().map(ZipEntry::getName).toList());
            names.sort(Comparator.naturalOrder());
            return names;
        }
    }

    /** Für Tests: liest einen Eintrag als Text. */
    public static String entryText(Path path, String name) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile());
                InputStream stream = new BufferedInputStream(zip.getInputStream(zip.getEntry(name)));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            stream.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
