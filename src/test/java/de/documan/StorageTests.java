package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import de.documan.service.StorageService;
import de.documan.service.StorageService.Drive;
import de.documan.service.StorageService.Usage;
import de.documan.service.backup.BackupService;

class StorageTests extends IntegrationTest {

    private static final String ARCHIVE = "documan-20261102-030000-auto.zip";

    @Autowired
    StorageService storage;
    @Autowired
    BackupService backup;

    @BeforeEach
    void files() throws Exception {
        Path folder = Files.createDirectories(mediaRoot.resolve("originals/Telekom/Rechnung"));
        Files.write(folder.resolve("eins.pdf"), new byte[1000]);
        Files.write(folder.resolve("zwei.pdf"), new byte[500]);
        Files.write(backupRoot.resolve(ARCHIVE), new byte[200]);
    }

    @Test
    void filesAddUpWithSubfolders() {
        storage.measure();
        Usage usage = storage.usage();
        assertThat(usage.media()).isEqualTo(1500);
        assertThat(usage.backups()).isEqualTo(200);
    }

    @Test
    void onlyArchivesCountAsBackups() throws Exception {
        Files.write(backupRoot.resolve("notizen.txt"), new byte[900]);
        storage.measure();
        assertThat(storage.usage().backups()).isEqualTo(200);
    }

    @Test
    void theNasFolderIsNotWalked() throws Exception {
        Path scans = Files.createDirectories(backupRoot.resolve("nas/scans"));
        Files.write(scans.resolve("fremd.pdf"), new byte[900]);
        storage.measure();
        assertThat(storage.usage().backups()).isEqualTo(200);
    }

    @Test
    void totalIncludesTheDatabase() {
        storage.measure();
        Usage usage = storage.usage();
        assertThat(usage.database()).isPositive();
        assertThat(usage.total()).isEqualTo(1700 + usage.database());
    }

    @Test
    void driveIsMeasured() {
        Drive drive = storage.usage().drive();
        assertThat(drive.total()).isPositive();
        assertThat(drive.used()).isLessThanOrEqualTo(drive.total());
    }

    @Test
    void missingDriveLeavesOutTheDrive() {
        StorageService blind = new StorageService(properties, jdbc, backup) {
            @Override
            protected Drive drive(Path path) {
                return null;
            }
        };
        assertThat(blind.usage().drive()).isNull();
        assertThat(blind.usage().share()).isZero();
    }




}
