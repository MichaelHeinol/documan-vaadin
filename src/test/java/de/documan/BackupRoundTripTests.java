package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import de.documan.domain.BackupFrequency;
import de.documan.domain.BackupRun;
import de.documan.domain.BackupSettings;
import de.documan.domain.Document;
import de.documan.domain.MailAccount;
import de.documan.domain.RunKind;
import de.documan.domain.RunStatus;
import de.documan.repo.BackupRepository;
import de.documan.repo.MailRepository;
import de.documan.repo.Selection;
import de.documan.service.SearchService;
import de.documan.service.SecretBox;
import de.documan.service.backup.BackupException;
import de.documan.service.backup.BackupService;
import de.documan.service.backup.DatabaseTools;

/** Echter Durchlauf mit pg_dump und psql gegen die Testdatenbank. */
class BackupRoundTripTests extends IntegrationTest {

    @Autowired
    BackupService backup;
    @Autowired
    BackupRepository runs;
    @Autowired
    MailRepository mail;
    @Autowired
    SecretBox secrets;
    @Autowired
    SearchService search;
    @MockitoSpyBean
    DatabaseTools database;

    private BackupRun backupNow(String origin) {
        long id = runs.enqueue(RunKind.BACKUP, origin, "", false);
        assertThat(backup.runNext(null)).isTrue();
        BackupRun run = runs.history(50).stream().filter(item -> item.id() == id).findFirst().orElseThrow();
        assertThat(run.status()).as(run.error()).isEqualTo(RunStatus.DONE);
        return run;
    }

    private BackupRun backupNow() {
        return backupNow(BackupService.MANUAL);
    }

    private BackupRun restore(String archive, boolean safety) {
        runs.enqueue(RunKind.RESTORE, "", archive, safety);
        assertThat(backup.runNext(null)).isTrue();
        return runs.history(1).getFirst();
    }

    private List<String> titles() {
        return jdbc.sql("SELECT title FROM document ORDER BY id").query(String.class).list();
    }

    private List<Path> backupFiles(String glob) throws IOException {
        try (var files = Files.newDirectoryStream(backupRoot, glob)) {
            List<Path> paths = new java.util.ArrayList<>();
            files.forEach(paths::add);
            return paths;
        }
    }

    private long mailAccount() {
        MailAccount blank = MailAccount.blank();
        return mail.insert(new MailAccount(0, "Post", true, "h", 993, blank.security(), "u", secrets.encrypt("geheim"),
                "INBOX", true, 30, "", "", blank.attachments(), blank.afterImport(), "", null, null, List.of(), null,
                "", 0));
    }

    @Test
    void restoreBringsBackDocumentsAndFiles() throws Exception {
        Document kept = document("Stromrechnung").content("Abschlag für Oktober").stored();
        BackupRun run = backupNow();
        assertThat(run.documents()).isEqualTo(1);
        assertThat(run.files()).isEqualTo(1);
        assertThat(run.size()).isPositive();
        assertThat(backupFiles("*.part")).isEmpty();

        Path keptPath = mediaRoot.resolve(kept.original());
        documents.updateMetadata(kept.id(), "Geändert", null, null, null, "", "");
        Files.delete(keptPath);
        Document newer = document("Neuer als die Sicherung").original("originals/2026/02/n.pdf").stored();
        Path newerPath = mediaRoot.resolve(newer.original());

        BackupRun result = restore(run.archive(), false);

        assertThat(result.status()).as(result.error()).isEqualTo(RunStatus.DONE);
        assertThat(result.documents()).isEqualTo(1);
        assertThat(result.kind()).isEqualTo(RunKind.RESTORE);
        assertThat(titles()).containsExactly("Stromrechnung");
        assertThat(keptPath).hasBinaryContent(Samples.textPdf("Stromrechnung"));
        assertThat(newerPath).doesNotExist();
        assertThat(backup.restoreInProgress()).isFalse();
        var outcome = search.search(Selection.ALL, "abschlag");
        assertThat(documents.count(outcome.selection())).isEqualTo(1);
        assertThat(runs.hasActive()).isFalse();
    }

    @Test
    void oldMailPasswordsSurviveWithTheSameKey() {
        long account = mailAccount();
        BackupRun run = backupNow();

        BackupRun result = restore(run.archive(), false);

        assertThat(result.message()).isEmpty();
        MailAccount restored = mail.find(account).orElseThrow();
        assertThat(secrets.decrypt(restored.passwordEncrypted())).isEqualTo("geheim");
        assertThat(restored.enabled()).isTrue();
    }

    @Test
    void newSecretKeyDisablesTheMailboxWithANote() {
        long account = mailAccount();
        BackupRun run = backupNow();

        properties.setSecretKey("ein ganz anderer Schluessel");
        BackupRun result = restore(run.archive(), false);

        assertThat(result.status()).as(result.error()).isEqualTo(RunStatus.DONE);
        assertThat(result.message()).contains("neu eingegeben");
        MailAccount restored = mail.find(account).orElseThrow();
        assertThat(restored.enabled()).isFalse();
        assertThat(restored.lastError()).contains("Passwort");
    }

    @Test
    void damagedArchiveChangesNothing() throws Exception {
        document("Unversehrt").stored();
        BackupRun run = backupNow();
        jdbc.sql("UPDATE document SET title = 'Aktuell'").update();
        Path path = backupRoot.resolve(run.archive());
        Map<String, byte[]> members = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                members.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        String media = members.keySet().stream().filter(name -> name.startsWith("media/")).findFirst().orElseThrow();
        members.put(media, "verfaelscht".getBytes());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var member : members.entrySet()) {
                zip.putNextEntry(new ZipEntry(member.getKey()));
                zip.write(member.getValue());
                zip.closeEntry();
            }
        }

        BackupRun result = restore(run.archive(), false);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).contains("Prüfsummen");
        assertThat(titles()).containsExactly("Aktuell");
        assertThat(backup.restoreInProgress()).isFalse();
    }

    @Test
    void flippedBytesChangeNothing() throws Exception {
        document("Unversehrt").stored();
        BackupRun run = backupNow();
        jdbc.sql("UPDATE document SET title = 'Aktuell'").update();
        Path path = backupRoot.resolve(run.archive());
        byte[] data = Files.readAllBytes(path);
        int position = new String(data, StandardCharsets.ISO_8859_1).indexOf(BackupService.DATABASE);
        data[position + 200] ^= (byte) 0xFF;
        Files.write(path, data);

        BackupRun result = restore(run.archive(), false);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).contains("beschädigt");
        assertThat(titles()).containsExactly("Aktuell");
        assertThat(backup.restoreInProgress()).isFalse();
    }

    @Test
    void archiveIsARegularZipWithChecksums() throws Exception {
        Document document = document("Rechnung").stored();
        BackupRun run = backupNow();
        assertThat(run.archive()).endsWith(".zip");
        try (ZipFile zip = new ZipFile(backupRoot.resolve(run.archive()).toFile())) {
            List<String> names = java.util.Collections.list(zip.entries()).stream().map(ZipEntry::getName).toList();
            assertThat(names.getFirst()).isEqualTo(BackupService.MANIFEST);
            assertThat(names.getLast()).isEqualTo(BackupService.CHECKSUMS);
            assertThat(zip.getInputStream(zip.getEntry("media/" + document.original())).readAllBytes())
                    .isEqualTo(Samples.textPdf("Rechnung"));
            assertThat(zip.getEntry(BackupService.DATABASE).getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(zip.getEntry("media/" + document.original()).getMethod()).isEqualTo(ZipEntry.DEFLATED);
        }
    }

    @Test
    void refusesTheDocumentFolderAsTarget() throws Exception {
        document("Dokument").stored();
        Files.createSymbolicLink(backupRoot.resolve("nas"), mediaRoot);
        runs.saveSettings(new BackupSettings(false, BackupFrequency.DAILY, 6, LocalTime.of(2, 0), "nas", 7, null));
        runs.enqueue(RunKind.BACKUP, BackupService.MANUAL, "", false);
        backup.runNext(null);
        assertThat(runs.history(1).getFirst().error()).contains("Dokument- oder Eingangsordner");
    }

    @Test
    void truncatedArchiveChangesNothing() throws Exception {
        document("Unversehrt").stored();
        BackupRun run = backupNow();
        Path path = backupRoot.resolve(run.archive());
        byte[] data = Files.readAllBytes(path);
        Files.write(path, java.util.Arrays.copyOf(data, data.length / 2));

        BackupRun result = restore(run.archive(), false);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(titles()).containsExactly("Unversehrt");
    }

    @Test
    void failingImportRollsTheDatabaseBack() throws Exception {
        Document document = document("Bleibt").stored();
        BackupRun run = backupNow();
        jdbc.sql("UPDATE document SET title = 'Aktuell'").update();
        doAnswer(call -> {
            Path script = call.getArgument(1);
            Files.writeString(script, "CREATE TABLE halbfertig (id int);\nSELECT 1/0;\n");
            return null;
        }).when(database).renderScript(any(), any());

        BackupRun result = restore(run.archive(), false);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).contains("psql");
        assertThat(titles()).containsExactly("Aktuell");
        assertThat(mediaRoot.resolve(document.original())).exists();
        assertThat(backup.restoreInProgress()).isFalse();
    }

    @Test
    void currentStateIsSavedFirst() throws Exception {
        document("Dokument").stored();
        BackupRun run = backupNow();
        restore(run.archive(), true);
        List<Path> safety = backupFiles("*-vor-wiederherstellung.zip");
        assertThat(safety).hasSize(1);
        assertThat(backup.readManifest(safety.getFirst()).get("documents")).isEqualTo(1);
    }

    @Test
    void emptyInstallationNeedsNoSafetyBackup() throws Exception {
        Document document = document("Dokument").stored();
        BackupRun run = backupNow();
        documents.delete(document.id());
        restore(run.archive(), true);
        assertThat(backupFiles("*-vor-wiederherstellung.zip")).isEmpty();
        assertThat(documents.total()).isEqualTo(1);
    }

    @Test
    void scheduledArchivesArePrunedAfterSuccess() throws Exception {
        runs.saveSettings(new BackupSettings(false, BackupFrequency.DAILY, 6, LocalTime.of(2, 0), "", 1, null));
        Path old = Files.createFile(backupRoot.resolve("documan-20200101-020000-auto.zip"));
        BackupRun run = backupNow(BackupService.AUTOMATIC);
        assertThat(old).doesNotExist();
        assertThat(run.message()).contains("entfernt");
    }

    @Test
    void scheduleDoesNotFireRightAfterARestore() {
        runs.saveSettings(new BackupSettings(true, BackupFrequency.DAILY, 6, LocalTime.of(2, 0), "", 7,
                Instant.now().minus(30, ChronoUnit.DAYS)));
        // Der Lauf von Hand ist nötig, damit die Sicherung nicht selbst schon den Zeitplan weiterschiebt.
        BackupRun run = backupNow();
        restore(run.archive(), false);
        assertThat(backup.enqueueDue()).isFalse();
    }

    @Test
    void stoppingAbandonsTheBackupWithoutLeftovers() throws Exception {
        document("Dokument").stored();
        runs.enqueue(RunKind.BACKUP, BackupService.MANUAL, "", false);
        backup.runNext(() -> true);
        assertThat(runs.history(1).getFirst().status()).isEqualTo(RunStatus.FAILED);
        try (Stream<Path> files = Files.list(backupRoot)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void missingTargetFolderIsReported() throws Exception {
        Files.writeString(backupRoot.resolve("datei"), "kein Ordner");
        runs.saveSettings(new BackupSettings(false, BackupFrequency.DAILY, 6, LocalTime.of(2, 0), "datei", 7, null));
        runs.enqueue(RunKind.BACKUP, BackupService.MANUAL, "", false);
        backup.runNext(null);
        BackupRun run = runs.history(1).getFirst();
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.error()).contains("kann nicht angelegt werden");
    }

    @Test
    void failingDumpIsReported() {
        doThrow(new BackupException("pg_dump fehlgeschlagen: Verbindung verloren")).when(database).dump(any());
        runs.enqueue(RunKind.BACKUP, BackupService.MANUAL, "", false);
        backup.runNext(null);
        assertThat(runs.history(1).getFirst().error()).contains("pg_dump");
    }

    @Test
    void restartFinishesARestoreAfterTheDatabaseWasReplaced() throws Exception {
        Files.createDirectory(mediaRoot.resolve("originals"));
        Files.writeString(mediaRoot.resolve("originals/alt.pdf"), "alt");
        Path staging = backup.restoreFolder();
        Path fresh = staging.resolve("neu/media/originals");
        Files.createDirectories(fresh);
        Files.writeString(fresh.resolve("neu.pdf"), "neu");
        Files.createFile(staging.resolve(BackupService.DB_RESTORED_MARKER));

        backup.recoverRuns();

        assertThat(mediaRoot.resolve("originals/neu.pdf")).hasContent("neu");
        assertThat(mediaRoot.resolve("originals/alt.pdf")).doesNotExist();
        assertThat(backup.restoreInProgress()).isFalse();
        assertThat(runs.history(1).getFirst().status()).isEqualTo(RunStatus.DONE);
    }

    @Test
    void restartDiscardsARestoreBeforeTheDatabaseWasReplaced() throws Exception {
        Files.createDirectory(mediaRoot.resolve("originals"));
        Files.writeString(mediaRoot.resolve("originals/alt.pdf"), "alt");
        Path fresh = backup.restoreFolder().resolve("neu/media/originals");
        Files.createDirectories(fresh);
        Files.writeString(fresh.resolve("neu.pdf"), "neu");
        runs.enqueue(RunKind.RESTORE, "", "", false);
        runs.claimNext();
        Path leftover = Files.createFile(backupRoot.resolve("documan-20261031-020000-auto.zip.part"));

        assertThat(backup.recoverRuns()).isEqualTo(1);

        assertThat(mediaRoot.resolve("originals/alt.pdf")).exists();
        assertThat(backup.restoreInProgress()).isFalse();
        assertThat(leftover).doesNotExist();
        assertThat(runs.history(1).getFirst().status()).isEqualTo(RunStatus.FAILED);
    }


    @Test
    void longAndUmlautFileNamesSurvive() throws Exception {
        String name = "originals/2026/03/" + "ä".repeat(80) + "-Stromrechnung für März.pdf";
        Document document = document("Lang").original(name).stored();
        BackupRun run = backupNow();
        Files.delete(mediaRoot.resolve(name));
        BackupRun result = restore(run.archive(), false);
        assertThat(result.status()).as(result.error()).isEqualTo(RunStatus.DONE);
        assertThat(mediaRoot.resolve(document.original())).exists();
    }
}
