package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import de.documan.service.backup.BackupService;

/**
 * Liegt der Sicherungsordner selbst auf dem eingebundenen NAS, ist er über {@code nas} ein zweites Mal
 * erreichbar. Wie auf dem NAS, wo {@code /data/backup} und {@code /data/backup/nas/DocuMan} derselbe Ordner sind,
 * zeigt hier ein Link {@code nas/DocuMan} auf den Sicherungsordner.
 */
class BackupFolderShortcutTests extends IntegrationTest {

    @Autowired
    BackupService backup;

    @BeforeEach
    void mirrorTheBackupFolder() throws IOException {
        Files.createDirectories(backupRoot.resolve("nas/andere"));
        Files.createSymbolicLink(backupRoot.resolve("nas/DocuMan"), backupRoot);
        Files.createDirectories(backupRoot.resolve("monatlich"));
    }

    @Test
    void theBackupFolderSeenThroughTheNasIsTheBackupFolder() {
        assertThat(backup.normalizeFolder("nas/DocuMan")).isEmpty();
        assertThat(backup.normalizeFolder(" nas/DocuMan/ ")).isEmpty();
        assertThat(backup.normalizeFolder("nas/DocuMan/monatlich")).isEqualTo("monatlich");
        // Der leere Einhängepunkt, den Docker im Sicherungsordner anlegt, ist in Wahrheit die NAS-Einbindung.
        assertThat(backup.normalizeFolder("nas/DocuMan/nas")).isEqualTo("nas");
        assertThat(backup.normalizeFolder("nas/DocuMan/nas/DocuMan/monatlich")).isEqualTo("monatlich");
    }

    @Test
    void otherFoldersStayAsTheyAre() {
        assertThat(backup.normalizeFolder("")).isEmpty();
        assertThat(backup.normalizeFolder("nas/andere")).isEqualTo("nas/andere");
        assertThat(backup.normalizeFolder("monatlich")).isEqualTo("monatlich");
        // Fehlende Ordner legt die Sicherung später an, sie bleiben unverändert.
        assertThat(backup.normalizeFolder("nas/neu/tief")).isEqualTo("nas/neu/tief");
    }

    @Test
    void browsingIntoTheMirrorShowsTheBackupFolder() {
        assertThat(backup.listFolders("nas/DocuMan")).isEqualTo(backup.listFolders(""))
                .contains("monatlich", "nas");
    }

    @Test
    void aFolderCreatedInTheMirrorIsNamedWithoutDetour() {
        assertThat(backup.createFolder("nas/DocuMan", "woechentlich")).isEqualTo("woechentlich");
        assertThat(backupRoot.resolve("woechentlich")).isDirectory();
    }

    @Test
    void theShortenedFolderIsWritable() {
        backup.checkWritable(backup.normalizeFolder("nas/DocuMan/monatlich"));
    }
}
