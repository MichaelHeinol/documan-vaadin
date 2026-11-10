package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import de.documan.domain.BackupSettings;
import de.documan.repo.BackupRepository;
import de.documan.service.InboxWatchers;
import de.documan.service.InboxFolder;
import de.documan.service.InboxFolderException;
import de.documan.service.IngestService;
import de.documan.service.backup.BackupException;
import de.documan.service.backup.BackupService;

class InboxFolderTests extends IntegrationTest {

    private static final String PAGE = "/eingangsordner";

    @Autowired
    InboxFolder inbox;
    @Autowired
    BackupRepository runs;
    @Autowired
    BackupService backup;
    @Autowired
    IngestService ingestion;
    @Autowired
    PlatformTransactionManager transactions;

    private Path consume;
    private Path nas;
    private Path before;

    @BeforeEach
    void folders() throws Exception {
        before = properties.getConsumeDir();
        consume = workspace.resolve("consume");
        nas = backupRoot.resolve("nas");
        Files.createDirectories(consume.resolve("scanner"));
        Files.createDirectories(nas.resolve("scans"));
        properties.setConsumeDir(consume);
        properties.setConsumeHostDir("/volume1/docker/consume");
        properties.setBrowseHostDir("/volume1");
    }

    @AfterEach
    void restore() {
        properties.setConsumeDir(before);
        properties.setConsumeHostDir("");
        properties.setBrowseHostDir("");
    }

    private void refused(String folder, String message) {
        assertThatThrownBy(() -> inbox.check(folder)).isInstanceOf(InboxFolderException.class).hasMessageContaining(message);
    }

    private void file(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[] {1});
    }

    // Auswahl

    @Test
    void theDefaultFolderIsWatched() {
        assertThat(inbox.watchedPaths()).containsExactly(consume);
    }

    @Test
    void choicesStartWithBothRoots() {
        assertThat(inbox.listFolders("")).containsExactly("eingang", "nas");
    }

    @Test
    void withoutNasOnlyTheDefaultRemains() throws Exception {
        Files.delete(nas.resolve("scans"));
        Files.delete(nas);
        assertThat(inbox.listFolders("")).containsExactly("eingang");
    }

    @Test
    void subfoldersAreListed() {
        assertThat(inbox.listFolders("nas")).containsExactly("scans");
        assertThat(inbox.listFolders("eingang")).containsExactly("scanner");
    }

    @Test
    void systemFoldersStayHidden() throws Exception {
        for (String name : List.of("#recycle", "@eaDir", ".geheim", "_abgelehnt")) {
            Files.createDirectories(nas.resolve(name));
        }
        assertThat(inbox.listFolders("nas")).containsExactly("scans");
    }

    @Test
    void outsidePathsAreRefused() {
        for (String value : List.of("/etc", "../etc", "nas/../..", "woanders/ordner")) {
            assertThatThrownBy(() -> inbox.listFolders(value)).as(value).isInstanceOf(InboxFolderException.class);
        }
    }

    @Test
    void displayShowsThePathOnTheNas() {
        assertThat(inbox.display("eingang")).isEqualTo("/volume1/docker/consume");
        assertThat(inbox.display("nas/scans/neu")).isEqualTo("/volume1/scans/neu");
    }

    @Test
    void withoutComposeHintTheContainerPathShows() {
        properties.setConsumeHostDir("");
        assertThat(inbox.display("eingang")).isEqualTo(consume.toString());
    }

    @Test
    void newFolderIsCreated() {
        assertThat(inbox.createFolder("nas/scans", "neu")).isEqualTo("nas/scans/neu");
        assertThat(nas.resolve("scans/neu")).isDirectory();
    }

    @Test
    void newFolderNeedsAParentAndAPlainName() {
        assertThatThrownBy(() -> inbox.createFolder("", "neu")).isInstanceOf(InboxFolderException.class);
        assertThatThrownBy(() -> inbox.createFolder("nas", "a/b")).isInstanceOf(InboxFolderException.class);
        assertThatThrownBy(() -> inbox.createFolder("nas", " ")).isInstanceOf(InboxFolderException.class);
    }

    @Test
    void anEntryOutOfReachIsSkipped() {
        jdbc.sql("INSERT INTO consume_folder (folder) VALUES ('woanders')").update();
        assertThat(inbox.watchedPaths()).containsExactly(consume);
    }

    // Prüfung

    @Test
    void emptyFolderCountsZero() {
        assertThat(inbox.check("nas/scans")).isZero();
    }

    @Test
    void filesCountAlsoInSubfolders() throws Exception {
        file(nas.resolve("scans/a.pdf"));
        file(nas.resolve("scans/tief/b.pdf"));
        file(nas.resolve("scans/Thumbs.db"));
        assertThat(inbox.check("nas/scans")).isEqualTo(2);
    }

    @Test
    void wholeNasIsRefused() {
        refused("nas", "nicht den ganzen NAS-Ordner");
    }

    @Test
    void nothingChosenIsRefused() {
        refused("", "Bitte einen Ordner wählen");
    }

    @Test
    void missingFolderIsRefused() {
        refused("nas/fehlt", "ist kein Ordner");
    }

    @Test
    void readOnlyFolderIsRefused() {
        InboxFolder locked = new InboxFolder(properties, jdbc, runs, transactions) {
            @Override
            protected boolean accessible(Path path) {
                return false;
            }
        };
        assertThatThrownBy(() -> locked.check("nas/scans")).hasMessageContaining("nicht schreiben");
    }

    @Test
    void folderInsideTheDocumentsIsRefused() throws Exception {
        Files.createDirectories(nas.resolve("docman/originals"));
        properties.setMediaRoot(nas.resolve("docman"));
        refused("nas/docman/originals", "Dokument- oder Sicherungsordner");
    }

    @Test
    void folderAroundTheDocumentsIsRefused() throws Exception {
        Path media = Files.createDirectories(nas.resolve("docker/data/media"));
        properties.setMediaRoot(media);
        refused("nas/docker", "Daten von DocuMan");
    }

    @Test
    void folderAroundTheBackupTargetIsRefused() throws Exception {
        Files.createDirectories(nas.resolve("alles/sicherung"));
        BackupSettings settings = runs.loadSettings();
        runs.saveSettings(new BackupSettings(settings.enabled(), settings.frequency(), settings.weekday(),
                settings.time(), "nas/alles/sicherung", settings.keep(), settings.lastScheduled()));
        refused("nas/alles", "Daten von DocuMan");
    }

    @Test
    void hugeFoldersAreRefused() throws Exception {
        file(nas.resolve("scans/a.pdf"));
        file(nas.resolve("scans/b.pdf"));
        InboxFolder small = new InboxFolder(properties, jdbc, runs, transactions) {
            @Override
            protected int maxFiles() {
                return 1;
            }
        };
        assertThatThrownBy(() -> small.check("nas/scans")).hasMessageContaining("mehr als 1 Dateien");
    }

    @Test
    void backupsRefuseTheChosenInbox() {
        inbox.add("nas/scans");
        assertThatThrownBy(() -> backup.checkWritable("nas/scans")).isInstanceOf(BackupException.class)
                .hasMessageContaining("Eingangsordner");
    }

    @Test
    void backupsRefuseEveryInbox() throws Exception {
        Files.createDirectories(nas.resolve("post"));
        inbox.add("nas/scans");
        inbox.add("nas/post");
        assertThatThrownBy(() -> backup.checkWritable("nas/post")).isInstanceOf(BackupException.class)
                .hasMessageContaining("Eingangsordner");
    }

    @Test
    void aWatchedFolderIsRefusedAgain() {
        refused("eingang", "überschneidet sich");
    }

    @Test
    void aFolderInsideAWatchedOneIsRefused() {
        refused("eingang/scanner", "/volume1/docker/consume");
    }

    @Test
    void aFolderAroundAWatchedOneIsRefused() throws Exception {
        Files.createDirectories(nas.resolve("scans/tief"));
        inbox.add("nas/scans/tief");
        refused("nas/scans", "überschneidet sich");
    }

    @Test
    void theNumberOfFoldersIsLimited() {
        InboxFolder few = new InboxFolder(properties, jdbc, runs, transactions) {
            @Override
            protected int maxFolders() {
                return 1;
            }
        };
        assertThatThrownBy(() -> few.check("nas/scans")).hasMessageContaining("höchstens 1 Eingangsordner");
    }

    // Worker

    @Test
    void everyFolderIsImported() throws Exception {
        double[] now = {0};
        InboxWatchers watchers = new InboxWatchers(10, () -> now[0], ingestion);
        inbox.add("nas/scans");
        Files.write(consume.resolve("eins.pdf"), Samples.textPdf("Erster Beleg mit genug Text"));
        Files.write(nas.resolve("scans/zwei.pdf"), Samples.textPdf("Zweiter Beleg mit genug Text"));
        watchers.scan(inbox.watchedPaths());
        now[0] = 20;
        assertThat(watchers.scan(inbox.watchedPaths())).isEqualTo(2);
        assertThat(nas.resolve("scans/zwei.pdf")).doesNotExist();
        assertThat(documents.total()).isEqualTo(2);
    }

    @Test
    void aRemovedFolderForgetsWhatWasSeen() throws Exception {
        double[] now = {0};
        InboxWatchers watchers = new InboxWatchers(10, () -> now[0], ingestion);
        Files.write(consume.resolve("beleg.pdf"), Samples.textPdf("Beleg mit genug Text"));
        watchers.scan(List.of(consume));
        watchers.scan(List.of(nas.resolve("scans")));
        now[0] = 20;
        assertThat(watchers.scan(List.of(consume))).isZero();
    }

    @Test
    void aKeptFolderKeepsWhatWasSeen() throws Exception {
        double[] now = {0};
        InboxWatchers watchers = new InboxWatchers(10, () -> now[0], ingestion);
        Files.write(consume.resolve("beleg.pdf"), Samples.textPdf("Beleg mit genug Text"));
        watchers.scan(List.of(consume));
        now[0] = 20;
        assertThat(watchers.scan(List.of(consume, nas.resolve("scans")))).isEqualTo(1);
    }

    // Seite

    private List<String> watched() {
        return inbox.folders().stream().map(InboxFolder.Entry::folder).toList();
    }














    @Test
    void removingAnUnknownFolderIsReported() {
        assertThatThrownBy(() -> inbox.remove(999)).hasMessageContaining("Dieser Ordner wird nicht mehr überwacht.");
    }
}
