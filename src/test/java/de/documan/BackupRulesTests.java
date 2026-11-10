package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import de.documan.domain.BackupFrequency;
import de.documan.domain.BackupRun;
import de.documan.domain.BackupSettings;
import de.documan.domain.RunKind;
import de.documan.domain.RunStatus;
import de.documan.repo.BackupRepository;
import de.documan.service.backup.Archive;
import de.documan.service.backup.BackupException;
import de.documan.service.backup.BackupService;

class BackupRulesTests extends IntegrationTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String ARCHIVE = "documan-20261030-020000-manuell.zip";

    @Autowired
    BackupService backup;
    @Autowired
    BackupRepository runs;

    private BackupSettings settings(boolean enabled, BackupFrequency frequency, int weekday, LocalTime time,
            Instant lastScheduled) {
        BackupSettings settings = new BackupSettings(enabled, frequency, weekday, time, "", 7, lastScheduled);
        runs.saveSettings(settings);
        return settings;
    }

    private static ZonedDateTime berlin(int month, int day, int hour) {
        return ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, BERLIN);
    }

    private static byte[] manifest(Map<String, Object> changes) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("app", BackupService.APPLICATION);
        manifest.put("format", BackupService.FORMAT_VERSION);
        manifest.put("created", "2026-10-31T02:00:00+01:00");
        manifest.put("documents", 1);
        manifest.put("files", 0);
        manifest.put("schema", 1);
        manifest.putAll(changes);
        return tools.jackson.databind.json.JsonMapper.shared().writeValueAsBytes(manifest);
    }

    private static byte[] manifest() {
        return manifest(Map.of());
    }

    private Path writeZip(String name, Map<String, byte[]> members) throws Exception {
        Path path = backupRoot.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var member : members.entrySet()) {
                zip.putNextEntry(new ZipEntry(member.getKey()));
                zip.write(member.getValue());
                zip.closeEntry();
            }
        }
        return path;
    }

    private Path validArchive() throws Exception {
        return writeZip(ARCHIVE, Map.of(BackupService.MANIFEST, manifest()));
    }

    private void touch(String... names) throws Exception {
        for (String name : names) {
            Files.createFile(backupRoot.resolve(name));
        }
    }

    private List<String> filesInBackupFolder() throws Exception {
        try (Stream<Path> files = Files.list(backupRoot)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void acceptsSubfolders() {
        assertThat(BackupService.cleanFolder("")).isEmpty();
        assertThat(BackupService.cleanFolder(" taeglich/ ")).isEqualTo("taeglich");
        assertThat(BackupService.cleanFolder("nas/Täglich 1")).isEqualTo("nas/Täglich 1");
        assertThat(BackupService.cleanFolder("nas/Fotos (2026) & mehr")).isEqualTo("nas/Fotos (2026) & mehr");
    }

    @Test
    void rejectsPathsOutsideTheBackupFolder() {
        for (String value : List.of("/volume1/backup", "..", "a/../b", "..\\b", ".versteckt", "a\\b")) {
            assertThatThrownBy(() -> BackupService.cleanFolder(value)).as(value).isInstanceOf(BackupException.class);
        }
    }

    private MvcResult saveSettings(String... changes) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>(Map.of("frequency", "daily", "weekday", "6", "time", "03:30",
                "folder", "", "keep", "5"));
        for (int index = 0; index < changes.length; index += 2) {
            fields.put(changes[index], changes[index + 1]);
        }
        var request = post("/sicherung").with(CHEF).with(csrf());
        for (var field : fields.entrySet()) {
            request = request.param(field.getKey(), field.getValue());
        }
        return mvc.perform(request).andReturn();
    }



    @Test
    void dailyRunsTodayOrTomorrow() {
        BackupSettings config = settings(false, BackupFrequency.DAILY, 6, LocalTime.of(2, 0), null);
        assertThat(backup.nextRun(config, berlin(11, 3, 1))).isEqualTo(berlin(11, 3, 2));
        assertThat(backup.nextRun(config, berlin(11, 3, 2))).isEqualTo(berlin(11, 4, 2));
    }

    @Test
    void weeklyFindsTheWeekday() {
        BackupSettings config = settings(false, BackupFrequency.WEEKLY, 6, LocalTime.of(2, 0), null);
        assertThat(backup.nextRun(config, berlin(11, 3, 12))).isEqualTo(berlin(11, 8, 2));
        assertThat(backup.nextRun(config, berlin(11, 8, 3))).isEqualTo(berlin(11, 15, 2));
    }

    @Test
    void keepsTheLocalTimeAcrossDaylightSaving() {
        BackupSettings config = settings(false, BackupFrequency.DAILY, 6, LocalTime.of(2, 30), null);
        ZonedDateTime planned = backup.nextRun(config, berlin(10, 24, 12));
        assertThat(planned.toLocalTime()).isEqualTo(LocalTime.of(2, 30));
    }

    @Test
    void nothingIsDueWhenDisabled() {
        settings(false, BackupFrequency.DAILY, 6, LocalTime.of(2, 0), Instant.now().minus(3, ChronoUnit.DAYS));
        assertThat(backup.enqueueDue()).isFalse();
    }

    @Test
    void missedDateIsCaughtUpOnce() {
        settings(true, BackupFrequency.DAILY, 6, LocalTime.of(2, 0), Instant.now().minus(3, ChronoUnit.DAYS));
        assertThat(backup.enqueueDue()).isTrue();
        assertThat(backup.enqueueDue()).isFalse();
        BackupRun run = runs.history(5).getFirst();
        assertThat(run.origin()).isEqualTo(BackupService.AUTOMATIC);
        assertThat(runs.history(5)).hasSize(1);
    }

    @Test
    void savingDoesNotTriggerTodaysPastDate() throws Exception {
        String earlier = ZonedDateTime.now(BERLIN).minusMinutes(5).toLocalTime().withSecond(0).withNano(0).toString();
        saveSettings("enabled", "on", "time", earlier, "keep", "7");
        assertThat(backup.enqueueDue()).isFalse();
    }

    @Test
    void onlyOldScheduledArchivesGo() throws Exception {
        touch("documan-20261001-020000-auto.zip", "documan-20261002-020000-auto.zip",
                "documan-20261003-020000-auto.zip", "documan-20261001-120000-manuell.zip",
                "documan-20261001-130000-vor-wiederherstellung.zip", "fremde-datei-auto.zip");
        assertThat(backup.pruneAutomatic(backupRoot, 2)).isEqualTo(1);
        assertThat(filesInBackupFolder()).containsExactly("documan-20261001-120000-manuell.zip",
                "documan-20261001-130000-vor-wiederherstellung.zip", "documan-20261002-020000-auto.zip",
                "documan-20261003-020000-auto.zip", "fremde-datei-auto.zip");
    }

    @Test
    void zeroKeepsEverything() throws Exception {
        touch("documan-20261001-020000-auto.zip");
        assertThat(backup.pruneAutomatic(backupRoot, 0)).isZero();
    }

    @Test
    void listsNestedArchivesAndFlagsBrokenOnes() throws Exception {
        Files.createDirectory(backupRoot.resolve("nas"));
        Files.writeString(backupRoot.resolve("nas/documan-20261031-020000-auto.zip"), "kaputt");
        validArchive();
        writeZip("anderes.zip", Map.of(BackupService.MANIFEST, manifest()));

        List<Archive> archives = backup.listArchives();

        assertThat(archives).extracting(Archive::relative)
                .containsExactly("nas/documan-20261031-020000-auto.zip", ARCHIVE);
        assertThat(archives.get(0).problem()).isNotEmpty();
        assertThat(archives.get(0).manifest()).isNull();
        assertThat(archives.get(1).documents()).isEqualTo(1);
        assertThat(archives.get(0).folder()).isEqualTo("nas");
    }

    @Test
    void foreignArchiveIsRejected() throws Exception {
        Path path = writeZip(ARCHIVE, Map.of("irgendwas", new byte[] {1}));
        assertThatThrownBy(() -> backup.readManifest(path)).hasMessageContaining("kein DocuMan-Archiv");
    }

    @Test
    void unknownFormatIsRejected() throws Exception {
        Path path = writeZip(ARCHIVE, Map.of(BackupService.MANIFEST, manifest(Map.of("format", 99))));
        assertThatThrownBy(() -> backup.readManifest(path)).hasMessageContaining("nicht unterstützt");
    }

    @Test
    void archiveOfTheDjangoVersionIsRejected() throws Exception {
        Path path = writeZip(ARCHIVE, Map.of(BackupService.MANIFEST, manifest(Map.of("app", "django"))));
        assertThatThrownBy(() -> backup.readManifest(path)).hasMessageContaining("Java-Version");
    }

    @Test
    void findStaysInsideTheBackupFolder() throws Exception {
        Path outside = backupRoot.getParent().resolve(ARCHIVE);
        Files.createFile(outside);
        try {
            for (String relative : List.of("../" + ARCHIVE, "", "..", "a\u0000b")) {
                assertThat(backup.findArchive(relative)).as(relative).isNull();
            }
        } finally {
            Files.delete(outside);
        }
    }

    @Test
    void newerVersionIsRefused() {
        assertThatThrownBy(() -> backup.checkCompatible(Map.of("schema", 9999)))
                .hasMessageContaining("neueren DocuMan-Version");
        backup.checkCompatible(Map.of("schema", 1));
    }

    @Test
    void archiveCannotWriteOutsideTheWorkspace() {
        Path workspaceFolder = backupRoot.resolve("ziel");
        for (String name : List.of("../boese", "media/../../boese", "/etc/boese", "manage.py", "media/.x/y")) {
            assertThatThrownBy(() -> BackupService.memberTarget(name, true, workspaceFolder)).as(name)
                    .isInstanceOf(BackupException.class);
        }
    }

    @Test
    void linksAreRefused() {
        assertThatThrownBy(() -> BackupService.memberTarget("media/link", false, backupRoot))
                .isInstanceOf(BackupException.class);
    }

    @Test
    void truncatedArchiveIsRefused() throws Exception {
        Path path = writeZip(ARCHIVE, new LinkedHashMap<>(Map.of(BackupService.MANIFEST, manifest(),
                BackupService.DATABASE, new byte[] {'x'})));
        assertThatThrownBy(() -> backup.unpack(path, backupRoot.resolve("ziel"), null))
                .hasMessageContaining("unvollständig");
    }












    private BackupRun run() {
        runs.enqueue(RunKind.BACKUP, BackupService.MANUAL, "", false);
        return runs.claimNext().orElseThrow();
    }

    @Test
    void listsOnlyVisibleFolders() throws Exception {
        for (String name : List.of("Zeta", "alpha", "#recycle", "@eaDir", ".geheim")) {
            Files.createDirectory(backupRoot.resolve(name));
        }
        Files.writeString(backupRoot.resolve("datei.txt"), "x");
        assertThat(backup.listFolders("")).containsExactly("alpha", "Zeta");
    }

    @Test
    void listingAMissingFolderIsReported() {
        assertThatThrownBy(() -> backup.listFolders("fehlt")).hasMessageContaining("kann nicht gelesen werden");
    }

    @Test
    void createsAFolderAtAnyLevel() {
        assertThat(backup.createFolder("", "neu")).isEqualTo("neu");
        assertThat(backup.createFolder("neu", "tiefer")).isEqualTo("neu/tiefer");
        assertThat(backup.createFolder("neu", "tiefer")).isEqualTo("neu/tiefer");
        assertThat(backupRoot.resolve("neu/tiefer")).isDirectory();
    }

    @Test
    void rejectsBadFolderNames() {
        for (String name : List.of("", "   ", "a/b", "..", ".x", "#x")) {
            assertThatThrownBy(() -> backup.createFolder("", name)).as(name).isInstanceOf(BackupException.class);
        }
    }

    @Test
    void documentsCannotBeTheTarget() throws Exception {
        Files.createDirectories(mediaRoot.resolve("originals"));
        Files.createSymbolicLink(backupRoot.resolve("nas"), mediaRoot);
        for (String folder : List.of("nas", "nas/originals")) {
            assertThatThrownBy(() -> backup.checkWritable(folder)).as(folder)
                    .hasMessageContaining("Dokument- oder Eingangsordner");
        }
    }

    @Test
    void inboxCannotBeTheTarget() throws Exception {
        Path inbox = Files.createDirectory(backupRoot.resolve("eingang"));
        Path before = properties.getConsumeDir();
        properties.setConsumeDir(inbox);
        try {
            assertThatThrownBy(() -> backup.checkWritable("eingang")).isInstanceOf(BackupException.class);
        } finally {
            properties.setConsumeDir(before);
        }
    }

    @Test
    void searchSkipsSystemFoldersAndDeepTrees() throws Exception {
        Files.createDirectory(backupRoot.resolve("#recycle"));
        writeZip("#recycle/" + ARCHIVE, Map.of(BackupService.MANIFEST, manifest()));
        Files.createDirectories(backupRoot.resolve("a/b/c/d"));
        writeZip("a/b/c/d/documan-20261029-020000-manuell.zip", Map.of(BackupService.MANIFEST, manifest()));
        assertThat(backup.listArchives()).isEmpty();
    }

    @Test
    void chosenFolderIsSearchedHoweverDeepItIs() throws Exception {
        Files.createDirectories(backupRoot.resolve("a/b/c/d"));
        writeZip("a/b/c/d/documan-20261029-020000-manuell.zip", Map.of(BackupService.MANIFEST, manifest()));
        runs.saveSettings(new BackupSettings(false, BackupFrequency.DAILY, 6, LocalTime.of(2, 0), "a/b/c/d", 7, null));
        assertThat(backup.listArchives()).hasSize(1);
    }

    @Test
    void cutOffZipIsUnreadable() throws Exception {
        Path path = validArchive();
        Files.write(path, java.util.Arrays.copyOf(Files.readAllBytes(path), 40));
        assertThatThrownBy(() -> backup.readManifest(path)).hasMessageContaining("nicht lesbar");
    }

    @Test
    void zipCannotEscapeTheWorkspace() throws Exception {
        Path path = writeZip(ARCHIVE, new LinkedHashMap<>(Map.of(BackupService.MANIFEST, manifest(), "../../boese",
                new byte[] {'x'})));
        assertThatThrownBy(() -> backup.unpack(path, backupRoot.resolve("ziel"), null))
                .hasMessageContaining("unzulässigen Eintrag");
    }



}
