package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import de.documan.domain.AppUser;
import de.documan.domain.BackupRun;
import de.documan.domain.Document;
import de.documan.domain.DocumentStatus;
import de.documan.domain.MailAccount;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.domain.RunKind;
import de.documan.domain.RunStatus;
import de.documan.repo.BackupRepository;
import de.documan.repo.MailRepository;
import de.documan.repo.Selection;
import de.documan.repo.UserRepository;
import de.documan.service.SearchService;
import de.documan.service.SecretBox;
import de.documan.service.backup.BackupException;
import de.documan.service.backup.BackupService;
import de.documan.service.backup.MigrationArchive;
import de.documan.service.backup.MigrationService;
import tools.jackson.databind.json.JsonMapper;

class MigrationTests extends IntegrationTest {

    private static final String ARCHIVE = "documan-umzug-20261101-100000-django.zip";
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String ORIGINAL = "originals/2026/03/Stadtwerke - Strom.pdf";

    @Autowired
    MigrationService migration;
    @Autowired
    BackupService backup;
    @Autowired
    BackupRepository runs;
    @Autowired
    UserRepository users;
    @Autowired
    MailRepository mail;
    @Autowired
    SecretBox secrets;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    SearchService search;

    private Path consume;

    @BeforeEach
    void folders() throws IOException {
        consume = Files.createDirectory(workspace.resolve("eingang"));
        properties.setConsumeDir(consume);
    }

    @AfterEach
    void resetMail() {
        properties.setEmailHost("");
    }

    private BackupRun run(RunKind kind, String archive) {
        long id = runs.enqueue(kind, "", archive, false);
        assertThat(backup.runNext(null)).isTrue();
        return runs.history(50).stream().filter(item -> item.id() == id).findFirst().orElseThrow();
    }

    private BackupRun exportNow() {
        BackupRun result = run(RunKind.EXPORT, "");
        assertThat(result.status()).as(result.error()).isEqualTo(RunStatus.DONE);
        return result;
    }

    private BackupRun importNow(String archive) {
        return run(RunKind.IMPORT, archive);
    }

    /** Dokument mit allen drei Dateien, Tag und Absendername. */
    private Document fullDocument() throws IOException {
        Document document = document("Strom").content("Abschlag für Oktober").date("2026-03-14")
                .correspondent(correspondent("Stadtwerke")).type(documentType("Rechnung")).tag(tag("Steuer", "#123456"))
                .original(ORIGINAL).pages(2).filed().stored();
        Path archive = mediaRoot.resolve("archive/2026/03/Stadtwerke - Strom.pdf");
        Path thumbnail = mediaRoot.resolve("thumbnails/2026/03/Stadtwerke - Strom.jpg");
        Files.createDirectories(archive.getParent());
        Files.createDirectories(thumbnail.getParent());
        Files.writeString(archive, "archiv");
        Files.writeString(thumbnail, "bild");
        jdbc.sql("UPDATE document SET archive = ?, thumbnail = ? WHERE id = ?")
                .params("archive/2026/03/Stadtwerke - Strom.pdf", "thumbnails/2026/03/Stadtwerke - Strom.jpg", document.id())
                .update();
        return reload(document);
    }

    private long mailAccount() {
        MailAccount blank = MailAccount.blank();
        long id = mail.insert(new MailAccount(0, "Rechnungen", true, "imap.example.de", 993, blank.security(), "post",
                secrets.encrypt("geheim"), "INBOX", true, 30, "", "", blank.attachments(), blank.afterImport(), "", null,
                null, List.of(), null, "", 0));
        mail.rememberMessage(id, "<1@example.de>", "Rechnung");
        return id;
    }

    /** Leert das System wie eine frische Installation. */
    private void wipe() throws IOException {
        jdbc.sql("""
                TRUNCATE app_user, correspondent, category, document_type, tag, document, document_tag,
                         mail_account, mail_account_tag, processed_mail RESTART IDENTITY CASCADE
                """).update();
        deleteTree(mediaRoot);
        Files.createDirectories(mediaRoot);
    }

    private List<String> mediaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(mediaRoot)) {
            return files.filter(Files::isRegularFile).map(path -> mediaRoot.relativize(path).toString()).toList();
        }
    }

    private static Map<String, Object> jsonOf(String text) {
        @SuppressWarnings("unchecked")
        Map<String, Object> value = JSON.readValue(text, Map.class);
        return value;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    // Beispieldaten, wie sie docs/umzugsformat.md beschreibt

    private static Map<String, Object> manifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "documan-umzug");
        manifest.put("version", 1);
        manifest.put("source", "django");
        manifest.put("created", "2026-11-01T10:00:00+01:00");
        manifest.put("documents", 1);
        manifest.put("files", 3);
        return manifest;
    }

    private static Map<String, Object> masterData() {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("name", "Rechnungen");
        account.put("host", "imap.example.de");
        account.put("port", 993);
        account.put("security", "ssl");
        account.put("username", "rechnung@example.de");
        account.put("folder", "INBOX");
        account.put("only_unseen", true);
        account.put("maximum_age_days", 30);
        account.put("filter_from", "");
        account.put("filter_subject", "");
        account.put("attachments", "pdf");
        account.put("after_import", "seen");
        account.put("target_folder", "");
        account.put("assign_correspondent", "Stadtwerke");
        account.put("assign_document_type", null);
        account.put("assign_tags", List.of("Steuer"));
        account.put("processed", List.of(Map.of("message_id", "<1@example.de>", "subject", "Rechnung")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("correspondents", List.of(Map.of("name", "Stadtwerke", "algorithm", "any", "match", "Strom",
                "ignore_case", true)));
        data.put("document_types", List.of(Map.of("name", "Rechnung", "algorithm", "none", "match", "",
                "ignore_case", true)));
        data.put("tags", List.of(Map.of("name", "Steuer", "algorithm", "none", "match", "", "ignore_case", true,
                "color", "#3f7d6e")));
        data.put("users", List.of(Map.of("username", "anna", "email", "anna@example.de", "is_admin", false,
                "active", true)));
        data.put("mail_accounts", List.of(account));
        return data;
    }

    private static Map<String, Object> documentLine() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("title", "Stromrechnung");
        line.put("correspondent", "Stadtwerke");
        line.put("document_type", "Rechnung");
        line.put("tags", List.of("Steuer"));
        line.put("document_date", "2026-03-14");
        line.put("notes", "Notiz");
        line.put("inbox", false);
        line.put("content", "Erkannter Text\nüber zwei Zeilen");
        line.put("page_count", 2);
        line.put("original", ORIGINAL);
        line.put("archive", "archive/2026/03/Stadtwerke - Strom.pdf");
        line.put("thumbnail", "thumbnails/2026/03/Stadtwerke - Strom.jpg");
        line.put("original_filename", "scan.pdf");
        line.put("mime_type", "application/pdf");
        line.put("size", 12345);
        line.put("checksum", "a".repeat(64));
        line.put("source", "upload");
        line.put("source_detail", "");
        line.put("status", "done");
        line.put("error", "");
        line.put("added", "2026-03-15T09:30:00.123456+01:00");
        line.put("modified", "2026-03-16T10:00:00+01:00");
        return line;
    }

    private static Map<String, byte[]> members(Map<String, Object> data, List<Map<String, Object>> lines) {
        Map<String, byte[]> members = new LinkedHashMap<>();
        members.put(MigrationService.MASTER_DATA, JSON.writeValueAsBytes(data));
        StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(JSON.writeValueAsString(line)).append('\n'));
        members.put(MigrationService.DOCUMENTS, text.toString().getBytes(StandardCharsets.UTF_8));
        members.put("media/" + ORIGINAL, "original".getBytes());
        members.put("media/archive/2026/03/Stadtwerke - Strom.pdf", "archiv".getBytes());
        members.put("media/thumbnails/2026/03/Stadtwerke - Strom.jpg", "bild".getBytes());
        return members;
    }

    private Path writeArchive(Map<String, Object> manifest, Map<String, byte[]> members, boolean withChecksums)
            throws Exception {
        Path path = backupRoot.resolve(ARCHIVE);
        Map<String, String> checksums = new LinkedHashMap<>();
        for (var member : members.entrySet()) {
            checksums.put(member.getKey(), sha256(member.getValue()));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(MigrationService.MANIFEST));
            zip.write(JSON.writeValueAsBytes(manifest));
            zip.closeEntry();
            for (var member : members.entrySet()) {
                zip.putNextEntry(new ZipEntry(member.getKey()));
                zip.write(member.getValue());
                zip.closeEntry();
            }
            if (withChecksums) {
                zip.putNextEntry(new ZipEntry(MigrationService.CHECKSUMS));
                zip.write(JSON.writeValueAsBytes(checksums));
                zip.closeEntry();
            }
        }
        return Files.write(path, bytes.toByteArray());
    }

    private Path validArchive() throws Exception {
        return writeArchive(manifest(), members(masterData(), List.of(documentLine())), true);
    }

    private void assertUntouched(BackupRun result, String message) throws IOException {
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).contains(message);
        assertThat(documents.total()).isZero();
        assertThat(master.count(MasterKind.CORRESPONDENT)).isZero();
        assertThat(users.findByUsername("anna")).isEmpty();
        assertThat(mediaFiles()).isEmpty();
        assertThat(mediaRoot.resolve(MigrationService.STAGING_FOLDER)).doesNotExist();
    }

    // Erstellen

    @Test
    void exportFollowsTheFormat() throws Exception {
        Document document = fullDocument();
        users.insert("anna", encoder.encode("sicheres-passwort"), true, true, "anna@example.de");
        mailAccount();

        BackupRun run = exportNow();

        assertThat(run.archive()).matches("documan-umzug-\\d{8}-\\d{6}-java\\.zip");
        assertThat(run.documents()).isEqualTo(1);
        assertThat(run.files()).isEqualTo(3);
        Path path = backupRoot.resolve(run.archive());
        try (ZipFile zip = new ZipFile(path.toFile())) {
            List<String> names = java.util.Collections.list(zip.entries()).stream().map(ZipEntry::getName).toList();
            assertThat(names.subList(0, 3)).containsExactly("manifest.json", "stammdaten.json", "dokumente.jsonl");
            assertThat(names.getLast()).isEqualTo("checksums.json");
            assertThat(names).contains("media/" + ORIGINAL);
            assertThat(zip.getEntry("media/thumbnails/2026/03/Stadtwerke - Strom.jpg").getMethod())
                    .isEqualTo(ZipEntry.STORED);
            assertThat(zip.getEntry("media/" + ORIGINAL).getMethod()).isEqualTo(ZipEntry.DEFLATED);
        }

        Map<String, Object> manifest = jsonOf(MigrationService.entryText(path, "manifest.json"));
        assertThat(manifest).containsEntry("format", "documan-umzug").containsEntry("version", 1)
                .containsEntry("source", "java").containsEntry("documents", 1).containsEntry("files", 3);
        Map<String, Object> checksums = jsonOf(MigrationService.entryText(path, "checksums.json"));
        assertThat(checksums).containsKeys("stammdaten.json", "dokumente.jsonl", "media/" + ORIGINAL)
                .doesNotContainKeys("manifest.json", "checksums.json").hasSize(5);
        assertThat(checksums.get("media/" + ORIGINAL)).isEqualTo(sha256(Samples.textPdf("Strom")));

        String lines = MigrationService.entryText(path, "dokumente.jsonl");
        assertThat(lines.strip().lines()).hasSize(1);
        Map<String, Object> line = jsonOf(lines);
        assertThat(line).containsEntry("title", "Strom").containsEntry("correspondent", "Stadtwerke")
                .containsEntry("document_type", "Rechnung").containsEntry("document_date", "2026-03-14")
                .containsEntry("original", ORIGINAL).containsEntry("page_count", 2).containsEntry("inbox", false)
                .containsEntry("status", "done").containsEntry("checksum", document.checksum());
        assertThat(line.get("tags")).isEqualTo(List.of("Steuer"));
        assertThat((String) line.get("added")).matches(".*T\\d\\d:\\d\\d:\\d\\d.*[+-]\\d\\d:\\d\\d");

        String masterData = MigrationService.entryText(path, "stammdaten.json");
        assertThat(masterData).contains("\"is_admin\":true").contains("anna@example.de").contains("\"color\":\"#123456\"")
                .contains("<1@example.de>").contains("\"auth_method\":\"password\"").doesNotContain("password\":")
                .doesNotContain("password_encrypted").doesNotContain("geheim");
    }

    @Test
    void passwordsNeverLeaveTheSystem() throws Exception {
        document("Strom").stored();
        String hash = encoder.encode("sicheres-passwort");
        users.insert("anna", hash, false, true, "anna@example.de");
        long account = mailAccount();
        Path path = backupRoot.resolve(exportNow().archive());
        for (String name : MigrationService.entryNames(path)) {
            if (name.endsWith(".json") || name.endsWith(".jsonl")) {
                assertThat(MigrationService.entryText(path, name)).doesNotContain(hash)
                        .doesNotContain(mail.find(account).orElseThrow().passwordEncrypted());
            }
        }
    }

    @Test
    void documentsWithoutOriginalAreLeftOutAndNamed() throws Exception {
        document("Vorhanden").stored();
        document("Verschwunden").original("originals/2026/01/weg.pdf").create();
        BackupRun run = exportNow();
        assertThat(run.documents()).isEqualTo(1);
        assertThat(run.message()).contains("1 Dokument(e)").contains("Verschwunden");
        assertThat(MigrationService.entryText(backupRoot.resolve(run.archive()), "dokumente.jsonl"))
                .contains("Vorhanden").doesNotContain("Verschwunden");
    }

    @Test
    void missingArchiveOrThumbnailLeavesTheFieldEmpty() throws Exception {
        Document document = document("Strom").stored();
        jdbc.sql("UPDATE document SET archive = 'archive/x.pdf', thumbnail = 'thumbnails/x.jpg' WHERE id = ?")
                .param(document.id()).update();
        Map<String, Object> line = jsonOf(MigrationService.entryText(backupRoot.resolve(exportNow().archive()),
                "dokumente.jsonl"));
        assertThat(line).containsEntry("archive", "").containsEntry("thumbnail", "");
    }

    @Test
    void fileVanishingWhileWritingFailsTheRun() throws Exception {
        Document document = document("Strom").stored();
        runs.enqueue(RunKind.EXPORT, "", "", false);
        boolean[] deleted = {false};
        backup.runNext(() -> {
            if (!deleted[0]) {
                deleted[0] = true;
                mediaRoot.resolve(document.original()).toFile().delete();
            }
            return false;
        });
        assertThat(runs.history(1).getFirst().status()).isEqualTo(RunStatus.FAILED);
        assertThat(backupRoot.toFile().list()).isEmpty();
    }

    @Test
    void stoppingAbandonsTheExportWithoutLeftovers() throws Exception {
        document("Strom").stored();
        runs.enqueue(RunKind.EXPORT, "", "", false);
        backup.runNext(() -> true);
        assertThat(runs.history(1).getFirst().status()).isEqualTo(RunStatus.FAILED);
        assertThat(backupRoot.toFile().list()).isEmpty();
    }

    @Test
    void exportLandsInTheChosenFolder() throws Exception {
        document("Strom").stored();
        runs.saveSettings(new de.documan.domain.BackupSettings(false, de.documan.domain.BackupFrequency.DAILY, 6,
                java.time.LocalTime.of(2, 0), "ziel", 7, null));
        assertThat(exportNow().archive()).startsWith("ziel/");
        assertThat(migration.listArchives()).hasSize(1);
    }

    @Test
    void refusesTheDocumentFolderAsTarget() throws Exception {
        Files.createSymbolicLink(backupRoot.resolve("nas"), mediaRoot);
        runs.saveSettings(new de.documan.domain.BackupSettings(false, de.documan.domain.BackupFrequency.DAILY, 6,
                java.time.LocalTime.of(2, 0), "nas", 7, null));
        assertThat(run(RunKind.EXPORT, "").error()).contains("Dokument- oder Eingangsordner");
    }

    // Abgrenzung zur Sicherung

    @Test
    void migrationArchivesAreNoBackups() throws Exception {
        document("Strom").stored();
        exportNow();
        assertThat(backup.listArchives()).isEmpty();
        assertThat(migration.listArchives()).hasSize(1);
    }

    @Test
    void retentionLeavesMigrationArchivesAlone() throws Exception {
        document("Strom").stored();
        String archive = exportNow().archive();
        Files.createFile(backupRoot.resolve("documan-20200101-020000-auto.zip"));
        Files.createFile(backupRoot.resolve("documan-20200102-020000-auto.zip"));
        assertThat(backup.pruneAutomatic(backupRoot, 1)).isEqualTo(1);
        assertThat(backupRoot.resolve(archive)).exists();
    }

    @Test
    void backupsAreNoMigrationArchives() throws Exception {
        Files.createFile(backupRoot.resolve("documan-20261030-020000-manuell.zip"));
        assertThat(migration.listArchives()).isEmpty();
        assertThat(migration.findArchive("documan-20261030-020000-manuell.zip")).isNull();
    }

    // Einspielen

    @Test
    void importsAnArchiveOfTheOtherVersion() throws Exception {
        validArchive();
        users.insert("admin", encoder.encode("sicheres-passwort"), true, true, "admin@example.de");

        BackupRun result = importNow(ARCHIVE);

        assertThat(result.status()).as(result.error()).isEqualTo(RunStatus.DONE);
        assertThat(result.documents()).isEqualTo(1);
        assertThat(result.files()).isEqualTo(3);
        assertThat(result.message()).contains("Benutzer ohne Passwort: 1").contains("Deaktivierte Postfächer: 1");

        Document document = reload(jdbc.sql("SELECT id FROM document").query(Long.class).single());
        assertThat(document.title()).isEqualTo("Stromrechnung");
        assertThat(document.correspondentName()).isEqualTo("Stadtwerke");
        assertThat(document.documentTypeName()).isEqualTo("Rechnung");
        assertThat(document.tags()).extracting(MasterItem::name).containsExactly("Steuer");
        assertThat(document.documentDate()).isEqualTo("2026-03-14");
        assertThat(document.notes()).isEqualTo("Notiz");
        assertThat(document.inbox()).isFalse();
        assertThat(document.content()).isEqualTo("Erkannter Text\nüber zwei Zeilen");
        assertThat(document.pageCount()).isEqualTo(2);
        assertThat(document.original()).isEqualTo(ORIGINAL);
        assertThat(document.status()).isEqualTo(DocumentStatus.DONE);
        assertThat(document.added()).isEqualTo(Instant.parse("2026-03-15T08:30:00.123456Z"));
        assertThat(document.modified()).isEqualTo(Instant.parse("2026-03-16T09:00:00Z"));
        assertThat(document.attempts()).isZero();
        assertThat(jdbc.sql("SELECT file_sync_pending FROM document").query(Boolean.class).single()).isTrue();
        assertThat(document.addedBy()).isEmpty();
        assertThat(document.mail()).isEqualTo(de.documan.domain.MailHeaders.NONE);

        assertThat(mediaRoot.resolve(ORIGINAL)).hasContent("original");
        assertThat(mediaRoot.resolve(MigrationService.STAGING_FOLDER)).doesNotExist();
        assertThat(documents.count(search.search(Selection.ALL, "Erkannter").selection())).isEqualTo(1);
        assertThat(documents.count(search.search(Selection.ALL, "Stadtwerke").selection())).isEqualTo(1);
    }


    @Test
    void existingUsersKeepPasswordAndRights() throws Exception {
        validArchive();
        String hash = encoder.encode("sicheres-passwort");
        users.insert("anna", hash, true, true, "");
        importNow(ARCHIVE);
        AppUser anna = users.findByUsername("anna").orElseThrow();
        assertThat(anna.passwordHash()).isEqualTo(hash);
        assertThat(anna.superuser()).isTrue();
        assertThat(anna.email()).isEqualTo("anna@example.de");
    }

    @Test
    void existingEmailStays() throws Exception {
        validArchive();
        users.insert("anna", encoder.encode("sicheres-passwort"), false, true, "alt@example.de");
        importNow(ARCHIVE);
        assertThat(users.findByUsername("anna").orElseThrow().email()).isEqualTo("alt@example.de");
    }

    @Test
    void existingMasterDataStaysUnchanged() throws Exception {
        validArchive();
        long kept = master.insert(MasterKind.TAG, "Steuer", de.documan.domain.Algorithm.NONE, "", true, "#ffffff");
        importNow(ARCHIVE);
        assertThat(master.count(MasterKind.TAG)).isEqualTo(1);
        assertThat(master.find(MasterKind.TAG, kept).orElseThrow().color()).isEqualTo("#ffffff");
    }

    @Test
    void mailAccountsAreDisabledWithoutPassword() throws Exception {
        validArchive();
        importNow(ARCHIVE);
        MailAccount account = mail.all().getFirst();
        assertThat(account.enabled()).isFalse();
        assertThat(account.passwordEncrypted()).isEmpty();
        assertThat(account.lastError()).isEqualTo("Nach dem Umzug bitte das Passwort neu eingeben und aktivieren.");
        assertThat(account.assignCorrespondentId()).isNotNull();
        assertThat(account.assignTagIds()).hasSize(1);
        assertThat(mail.processed(account.id())).extracting(item -> item.messageId()).containsExactly("<1@example.de>");
        assertThat(account.auth()).isEqualTo(de.documan.domain.MailAuth.PASSWORD);
        assertThat(account.skipSpam()).isTrue();
        assertThat(account.pollMinutes()).isEqualTo(60);
        assertThat(account.exclusionRules()).isEmpty();
    }

    @Test
    void exclusionRulesAndSpamSettingMoveAlong() throws Exception {
        fullDocument();
        long account = mailAccount();
        jdbc.sql("UPDATE mail_account SET skip_spam = false, poll_minutes = 15 WHERE id = ?").param(account).update();
        jdbc.sql("INSERT INTO mail_exclusion_rule (account_id, field, value) VALUES (?, 'from', '@werbung.de')")
                .param(account).update();
        BackupRun exported = exportNow();
        String masterData = MigrationService.entryText(backupRoot.resolve(exported.archive()), "stammdaten.json");
        assertThat(masterData).contains("\"skip_spam\":false").contains("\"poll_minutes\":15").contains("\"field\":\"from\"")
                .contains("\"value\":\"@werbung.de\"");

        wipe();
        assertThat(importNow(exported.archive()).status()).isEqualTo(RunStatus.DONE);

        MailAccount copy = mail.all().getFirst();
        assertThat(copy.skipSpam()).isFalse();
        assertThat(copy.pollMinutes()).isEqualTo(15);
        assertThat(copy.exclusionRules())
                .containsExactly(new de.documan.domain.MailRule(de.documan.domain.MailRuleField.FROM, "@werbung.de"));
    }

    @Test
    void microsoftAccountsKeepTheirLoginTypeButNotTheToken() throws Exception {
        fullDocument();
        long account = mailAccount();
        jdbc.sql("UPDATE mail_account SET auth_method = 'microsoft' WHERE id = ?").param(account).update();
        BackupRun exported = exportNow();
        String masterData = MigrationService.entryText(backupRoot.resolve(exported.archive()), "stammdaten.json");
        assertThat(masterData).contains("\"auth_method\":\"microsoft\"").doesNotContain("geheim");

        wipe();
        assertThat(importNow(exported.archive()).status()).isEqualTo(RunStatus.DONE);

        MailAccount copy = mail.all().getFirst();
        assertThat(copy.auth()).isEqualTo(de.documan.domain.MailAuth.MICROSOFT);
        assertThat(copy.passwordEncrypted()).isEmpty();
        assertThat(copy.enabled()).isFalse();
    }

    @Test
    void processingBecomesPendingAndNullsAreAllowed() throws Exception {
        Map<String, Object> line = documentLine();
        line.put("status", "processing");
        line.put("correspondent", null);
        line.put("document_type", null);
        line.put("document_date", null);
        line.put("page_count", null);
        line.put("archive", "");
        line.put("thumbnail", "");
        Map<String, byte[]> members = members(masterData(), List.of(line));
        writeArchive(manifest(), members, true);
        BackupRun result = importNow(ARCHIVE);
        assertThat(result.status()).as(result.error()).isEqualTo(RunStatus.DONE);
        Document document = reload(jdbc.sql("SELECT id FROM document").query(Long.class).single());
        assertThat(document.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(document.correspondentId()).isNull();
        assertThat(document.documentDate()).isNull();
        assertThat(document.pageCount()).isNull();
        assertThat(document.hasArchive()).isFalse();
    }

    @Test
    void categoriesTravelWithTypesAndDocuments() throws Exception {
        Document original = fullDocument();
        MasterItem finances = category("Finanzen", master.find(MasterKind.DOCUMENT_TYPE, original.documentTypeId())
                .orElseThrow());
        documents.setCategory(new Long[] {original.id()}, subcategory(finances, "Energie").id());
        BackupRun exported = exportNow();
        Path archive = backupRoot.resolve(exported.archive());
        assertThat(MigrationService.entryText(archive, "stammdaten.json")).contains("\"category\":[\"Finanzen\"]");
        assertThat(MigrationService.entryText(archive, "dokumente.jsonl"))
                .contains("\"category\":[\"Finanzen\",\"Energie\"]");
        wipe();

        assertThat(importNow(exported.archive()).status()).isEqualTo(RunStatus.DONE);

        Document copy = reload(jdbc.sql("SELECT id FROM document").query(Long.class).single());
        assertThat(copy.categoryName()).isEqualTo("Energie");
        assertThat(master.categoryOf(copy.documentTypeId()).flatMap(id -> master.find(MasterKind.CATEGORY, id))
                .map(MasterItem::name)).contains("Finanzen");
    }

    @Test
    void roundTripBetweenTwoInstallations() throws Exception {
        Document original = fullDocument();
        users.insert("anna", encoder.encode("sicheres-passwort"), false, true, "anna@example.de");
        mailAccount();
        BackupRun exported = exportNow();
        wipe();

        BackupRun result = importNow(exported.archive());

        assertThat(result.status()).as(result.error()).isEqualTo(RunStatus.DONE);
        Document copy = reload(jdbc.sql("SELECT id FROM document").query(Long.class).single());
        assertThat(copy.title()).isEqualTo(original.title());
        assertThat(copy.checksum()).isEqualTo(original.checksum());
        assertThat(copy.added()).isEqualTo(original.added());
        assertThat(copy.tags().getFirst().color()).isEqualTo("#123456");
        assertThat(mediaRoot.resolve(copy.original())).hasBinaryContent(Samples.textPdf("Strom"));
        assertThat(mediaRoot.resolve(copy.archive())).hasContent("archiv");
    }

    @Test
    void originAndMailHeadersTravelInBothDirections() throws Exception {
        Document original = fullDocument();
        jdbc.sql("""
                UPDATE document SET source = 'mail', added_by = 'E-Mail: Büro', mail_from = 'info@stadtwerke.de',
                    mail_to = 'konto@example.de', mail_subject = 'Abschlag', mail_date = '2026-10-31T09:00:00Z',
                    mail_message_id = '<7@stadtwerke.de>' WHERE id = ?
                """).param(original.id()).update();

        BackupRun exported = exportNow();

        Map<String, Object> line = jsonOf(MigrationService.entryText(backupRoot.resolve(exported.archive()),
                "dokumente.jsonl"));
        assertThat(line).containsEntry("added_by", "E-Mail: Büro").containsEntry("mail_from", "info@stadtwerke.de")
                .containsEntry("mail_to", "konto@example.de").containsEntry("mail_subject", "Abschlag")
                .containsEntry("mail_date", "2026-10-31T10:00:00+01:00")
                .containsEntry("mail_message_id", "<7@stadtwerke.de>");

        wipe();
        assertThat(importNow(exported.archive()).status()).isEqualTo(RunStatus.DONE);

        Document copy = reload(jdbc.sql("SELECT id FROM document").query(Long.class).single());
        assertThat(copy.addedBy()).isEqualTo("E-Mail: Büro");
        assertThat(copy.mail()).isEqualTo(new de.documan.domain.MailHeaders("info@stadtwerke.de", "konto@example.de",
                "Abschlag", Instant.parse("2026-10-31T09:00:00Z"), "<7@stadtwerke.de>"));
        assertThat(documents.count(search.search(Selection.ALL, "Abschlag").selection())).isEqualTo(1);
    }

    @Test
    void documentsWithoutMailExportAnEmptyDate() throws Exception {
        fullDocument();
        Map<String, Object> line = jsonOf(MigrationService.entryText(backupRoot.resolve(exportNow().archive()),
                "dokumente.jsonl"));
        assertThat(line).containsEntry("added_by", "").containsEntry("mail_from", "").containsEntry("mail_date", null);
    }

    @Test
    void importSendsNoMail() throws Exception {
        validArchive();
        // Ein nicht erreichbarer Server würde jeden Versandversuch als Fehler sichtbar machen.
        properties.setEmailHost("127.0.0.1");
        properties.setEmailPort(1);
        BackupRun result = importNow(ARCHIVE);
        assertThat(result.status()).as(result.error()).isEqualTo(RunStatus.DONE);
        assertThat(result.message()).doesNotContain("Zugangsmail");
    }

    @Test
    void targetWithDocumentsIsRefusedBeforeAnythingChanges() throws Exception {
        validArchive();
        document("Schon da").create();
        BackupRun result = importNow(ARCHIVE);
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).contains("bereits Dokumente");
        assertThat(master.count(MasterKind.CORRESPONDENT)).isZero();
        assertThat(mediaRoot.resolve(MigrationService.STAGING_FOLDER)).doesNotExist();
    }

    @Test
    void damagedFileChangesNothing() throws Exception {
        Map<String, byte[]> members = members(masterData(), List.of(documentLine()));
        writeArchive(manifest(), members, true);
        Path path = backupRoot.resolve(ARCHIVE);
        Map<String, byte[]> tampered = new LinkedHashMap<>(members);
        tampered.put("media/" + ORIGINAL, "verfaelscht".getBytes());
        Map<String, Object> checksums = jsonOf(MigrationService.entryText(path, "checksums.json"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(MigrationService.MANIFEST));
            zip.write(JSON.writeValueAsBytes(manifest()));
            zip.closeEntry();
            for (var member : tampered.entrySet()) {
                zip.putNextEntry(new ZipEntry(member.getKey()));
                zip.write(member.getValue());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(MigrationService.CHECKSUMS));
            zip.write(JSON.writeValueAsBytes(checksums));
            zip.closeEntry();
        }
        Files.write(path, bytes.toByteArray());
        assertUntouched(importNow(ARCHIVE), "Prüfsummen");
    }

    @Test
    void missingChecksumsMeanIncomplete() throws Exception {
        writeArchive(manifest(), members(masterData(), List.of(documentLine())), false);
        assertUntouched(importNow(ARCHIVE), "unvollständig");
    }

    @Test
    void cutOffArchiveIsUnreadable() throws Exception {
        Path path = validArchive();
        Files.write(path, java.util.Arrays.copyOf(Files.readAllBytes(path), 100));
        assertUntouched(importNow(ARCHIVE), "nicht lesbar");
    }

    @Test
    void otherFormatsAreRefused() throws Exception {
        Map<String, Object> manifest = manifest();
        manifest.put("version", 2);
        Path path = writeArchive(manifest, members(masterData(), List.of(documentLine())), true);
        assertThatThrownBy(() -> migration.readManifest(path)).hasMessageContaining("nicht unterstützt");
        manifest.put("version", 1);
        manifest.put("format", "documan-backup");
        Path other = writeArchive(manifest, members(masterData(), List.of(documentLine())), true);
        assertThatThrownBy(() -> migration.readManifest(other)).hasMessageContaining("nicht unterstützt");
    }

    @Test
    void unsafePathsAreRefused() {
        Path target = backupRoot.resolve("ziel");
        for (String name : List.of("media/../boese", "media/originals/../../boese", "/etc/boese", "media//etc/boese",
                "manage.py", "media/.x/y", "media/originals/.x", "media/originals\\x.pdf", "media/andere/x.pdf",
                "database.dump")) {
            assertThatThrownBy(() -> MigrationService.memberTarget(name, target)).as(name)
                    .isInstanceOf(BackupException.class);
        }
        assertThat(MigrationService.memberTarget("media/originals/2026/03/Ä b.pdf", target)).isNotNull();
    }

    @Test
    void unsafeEntryIsRefusedInAnArchive() throws Exception {
        Map<String, byte[]> members = members(masterData(), List.of(documentLine()));
        members.put("media/../../boese", new byte[] {'x'});
        writeArchive(manifest(), members, true);
        assertUntouched(importNow(ARCHIVE), "unzulässigen Eintrag");
    }

    @Test
    void namedFileMustBeInTheArchive() throws Exception {
        Map<String, Object> line = documentLine();
        line.put("thumbnail", "thumbnails/2026/03/fehlt.jpg");
        writeArchive(manifest(), members(masterData(), List.of(line)), true);
        assertUntouched(importNow(ARCHIVE), "nicht im Archiv");
    }

    @Test
    void unknownValuesAreRefused() throws Exception {
        for (String field : List.of("status", "source")) {
            Map<String, Object> line = documentLine();
            line.put(field, "quatsch");
            writeArchive(manifest(), members(masterData(), List.of(line)), true);
            assertUntouched(importNow(ARCHIVE), field);
        }
        for (String field : List.of("security", "attachments", "after_import")) {
            Map<String, Object> data = masterData();
            Map<String, Object> account = new LinkedHashMap<>(accountsOf(data).getFirst());
            account.put(field, "quatsch");
            data.put("mail_accounts", List.of(account));
            writeArchive(manifest(), members(data, List.of(documentLine())), true);
            assertUntouched(importNow(ARCHIVE), field);
        }
        Map<String, Object> data = masterData();
        data.put("correspondents", List.of(Map.of("name", "X", "algorithm", "quatsch", "match", "", "ignore_case", true)));
        writeArchive(manifest(), members(data, List.of(documentLine())), true);
        assertUntouched(importNow(ARCHIVE), "algorithm");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> accountsOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("mail_accounts");
    }

    @Test
    void failingTransactionPutsTheFilesBack() throws Exception {
        // Zwei Dokumente mit derselben Prüfsumme verletzen die Eindeutigkeit erst beim Speichern.
        Map<String, Object> second = documentLine();
        second.put("title", "Zweites");
        writeArchive(manifest(), members(masterData(), List.of(documentLine(), second)), true);
        BackupRun result = importNow(ARCHIVE);
        assertUntouched(result, "");
        assertThat(mail.all()).isEmpty();
        try (Stream<Path> folders = Files.list(mediaRoot)) {
            assertThat(folders).isEmpty();
        }
    }

    @Test
    void reportsAMissingReference() throws Exception {
        Map<String, Object> line = documentLine();
        line.put("correspondent", "Unbekannt");
        writeArchive(manifest(), members(masterData(), List.of(line)), true);
        assertUntouched(importNow(ARCHIVE), "Unbekannt");
    }

    @Test
    void occupiedTargetFileIsNotOverwritten() throws Exception {
        validArchive();
        Path foreign = mediaRoot.resolve(ORIGINAL);
        Files.createDirectories(foreign.getParent());
        Files.writeString(foreign, "fremd");
        BackupRun result = importNow(ARCHIVE);
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(foreign).hasContent("fremd");
        assertThat(documents.total()).isZero();
    }

    @Test
    void restartRemovesAnInterruptedImport() throws Exception {
        Path staging = mediaRoot.resolve(MigrationService.STAGING_FOLDER);
        Files.createDirectories(staging.resolve("neu/media"));
        Files.write(staging.resolve("verschoben.txt"), List.of(ORIGINAL));
        Files.createDirectories(mediaRoot.resolve(ORIGINAL).getParent());
        Files.writeString(mediaRoot.resolve(ORIGINAL), "halb");

        backup.recoverRuns();

        assertThat(mediaRoot.resolve(ORIGINAL)).doesNotExist();
        assertThat(staging).doesNotExist();
    }

    @Test
    void restartKeepsAFinishedImport() throws Exception {
        document("Fertig").original(ORIGINAL).stored();
        Path staging = mediaRoot.resolve(MigrationService.STAGING_FOLDER);
        Files.createDirectories(staging.resolve("neu/media"));
        Files.write(staging.resolve("verschoben.txt"), List.of(ORIGINAL));

        backup.recoverRuns();

        assertThat(mediaRoot.resolve(ORIGINAL)).exists();
        assertThat(staging).doesNotExist();
    }

    // Oberfläche und Rechte

    private List<String> adminUrls() {
        return List.of("/sicherung/umzug", "/sicherung/umzug/einspielen?archiv=" + ARCHIVE);
    }











}
