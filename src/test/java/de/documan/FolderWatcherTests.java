package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.documan.domain.Document;
import de.documan.domain.DocumentSource;
import de.documan.domain.MasterKind;
import de.documan.service.CategoryPaths;
import de.documan.service.FolderWatcher;

class FolderWatcherTests extends IntegrationTest {

    private Path root;
    private double now;
    private FolderWatcher watcher;

    @BeforeEach
    void watch() throws IOException {
        root = workspace.resolve("eingang");
        Files.createDirectory(root);
        now = 0;
        watcher = new FolderWatcher(root, 10, () -> now, ingest);
    }

    private int scanAfter(double seconds) {
        now += seconds;
        return watcher.scan();
    }

    private Path write(String name, byte[] data) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        return Files.write(path, data);
    }

    private Path write(String name) throws IOException {
        return write(name, Samples.textPdf(name));
    }

    private long documentCount() {
        return documents.total();
    }

    private Document onlyDocument() {
        long id = jdbc.sql("SELECT id FROM document").query(Long.class).single();
        return reload(id);
    }

    @Test
    void fileIsImportedOnlyOnceStable() throws Exception {
        Path path = write("scan.pdf");
        assertThat(scanAfter(0)).isZero();
        assertThat(scanAfter(5)).isZero();
        assertThat(scanAfter(6)).isEqualTo(1);
        assertThat(path).doesNotExist();
        assertThat(onlyDocument().source()).isEqualTo(DocumentSource.CONSUME);
    }

    @Test
    void subfoldersBecomeTheCategoryHierarchy() throws Exception {
        write("Finanzen/Bank/Girokonto/auszug.pdf");
        scanAfter(0);
        assertThat(scanAfter(11)).isEqualTo(1);
        Document document = onlyDocument();
        assertThat(document.categoryName()).isEqualTo("Girokonto");
        assertThat(new CategoryPaths(master.categories()).label(document.ownCategoryId()))
                .isEqualTo("Finanzen / Bank / Girokonto");
        assertThat(document.sourceDetail()).isEqualTo("Finanzen/Bank/Girokonto/auszug.pdf");

        // Vorhandene Stufen werden wiederverwendet, auch in anderer Schreibweise.
        write("finanzen/Bank/zweiter.pdf");
        scanAfter(0);
        scanAfter(11);
        assertThat(master.count(MasterKind.CATEGORY)).isEqualTo(3);
    }

    @Test
    void onlyTenFolderLevelsCount() throws Exception {
        write("A/B/C/D/E/F/G/H/I/J/K/L/tief.pdf");
        scanAfter(0);
        assertThat(scanAfter(11)).isEqualTo(1);
        assertThat(new CategoryPaths(master.categories()).label(onlyDocument().ownCategoryId()))
                .isEqualTo("A / B / C / D / E / F / G / H / I / J");
    }

    @Test
    void filesInTheInboxRootGetNoCategory() throws Exception {
        write("scan.pdf");
        scanAfter(0);
        scanAfter(11);
        assertThat(onlyDocument().categoryId()).isNull();
    }

    @Test
    void growingFileIsNotTouched() throws Exception {
        Path path = write("scan.pdf", "%PDF-1.4 teil 1".getBytes());
        scanAfter(0);
        Files.write(path, Samples.textPdf("fertig geschrieben"));
        assertThat(scanAfter(11)).isZero();
        assertThat(path).exists();
        assertThat(scanAfter(11)).isEqualTo(1);
    }

    @Test
    void emptyFileWaitsForContent() throws Exception {
        Path path = write("scan.pdf", new byte[0]);
        scanAfter(0);
        assertThat(scanAfter(30)).isZero();
        assertThat(path).exists();
    }

    @Test
    void subfoldersAreScanned() throws Exception {
        write("Scanner/2026/scan.pdf");
        scanAfter(0);
        assertThat(scanAfter(11)).isEqualTo(1);
        assertThat(onlyDocument().sourceDetail()).isEqualTo("Scanner/2026/scan.pdf");
        assertThat(onlyDocument().addedBy()).isEqualTo("Eingangsordner");
    }

    @Test
    void systemFilesOfNasAndClientsAreIgnored() throws Exception {
        for (String name : List.of("@eaDir/scan.pdf/SYNOFILE_THUMB.pdf", "#recycle/alt.pdf", ".stfolder/x.pdf",
                ".versteckt.pdf", "Thumbs.db", "desktop.ini", "~$offen.pdf", "upload.pdf.part", "upload.tmp")) {
            write(name);
        }
        scanAfter(0);
        assertThat(scanAfter(11)).isZero();
        assertThat(documentCount()).isZero();
        assertThat(root.resolve(FolderWatcher.REJECTED_DIR)).doesNotExist();
    }

    @Test
    void everyFormatIsImported() throws Exception {
        write("tabelle.html", "<html><p>Liste</p></html>".getBytes());
        byte[] noise = new byte[256];
        for (int index = 0; index < noise.length; index++) {
            noise[index] = (byte) index;
        }
        write("messung.xyz", noise);
        scanAfter(0);
        assertThat(scanAfter(11)).isEqualTo(2);
        assertThat(root.resolve("tabelle.html")).doesNotExist();
        assertThat(root.resolve(FolderWatcher.REJECTED_DIR)).doesNotExist();
    }

    @Test
    void duplicatesMoveToRejectedWithoutOverwriting() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            write("scan.pdf", Samples.textPdf("identisch"));
            scanAfter(0);
            scanAfter(11);
        }
        assertThat(documentCount()).isEqualTo(1);
        Path rejected = root.resolve(FolderWatcher.REJECTED_DIR);
        try (Stream<Path> files = Files.list(rejected)) {
            assertThat(files.map(path -> path.getFileName().toString()).sorted())
                    .containsExactly("scan-1.pdf", "scan.pdf");
        }
        scanAfter(11);
        try (Stream<Path> files = Files.list(rejected)) {
            assertThat(files.count()).isEqualTo(2);
        }
    }

    @Test
    void undeletableFileIsNotImportedAgainAndAgain() throws Exception {
        watcher = new FolderWatcher(root, 10, () -> now, ingest) {
            @Override
            protected void remove(Path path) throws IOException {
                throw new AccessDeniedException("nur lesen");
            }
        };
        Path path = write("scan.pdf");
        scanAfter(0);
        assertThat(scanAfter(11)).isEqualTo(1);
        scanAfter(11);
        scanAfter(11);
        assertThat(path).exists();
        assertThat(root.resolve(FolderWatcher.REJECTED_DIR)).doesNotExist();
    }

    @Test
    void missingFolderDoesNotRaise() {
        assertThat(new FolderWatcher(root.resolve("fehlt"), 10, () -> now, ingest).scan()).isZero();
    }

    @Test
    void emptiedSubfoldersGoOnceTheyStayEmpty() throws Exception {
        write("Ablage/2025/scan.pdf");
        scanAfter(0);
        assertThat(scanAfter(11)).isEqualTo(1);
        assertThat(root.resolve("Ablage/2025")).isDirectory();
        scanAfter(5);
        assertThat(root.resolve("Ablage/2025")).isDirectory();
        scanAfter(6);
        assertThat(root.resolve("Ablage/2025")).doesNotExist();
        scanAfter(11);
        scanAfter(11);
        assertThat(root.resolve("Ablage")).doesNotExist();
        assertThat(root).isDirectory();
    }

    @Test
    void aNewFileKeepsTheFolder() throws Exception {
        write("Ablage/eins.pdf");
        scanAfter(0);
        scanAfter(11);
        write("Ablage/zwei.pdf");
        scanAfter(11);
        assertThat(root.resolve("Ablage/zwei.pdf")).exists();
    }

    @Test
    void leftoversOfNasAndClientsDoNotKeepAFolder() throws Exception {
        write("Ablage/scan.pdf");
        write("Ablage/Thumbs.db", new byte[] {1});
        write("Ablage/@eaDir/scan.pdf/SYNOFILE_THUMB_M.jpg", new byte[] {1});
        scanAfter(0);
        scanAfter(11);
        scanAfter(11);
        assertThat(root.resolve("Ablage")).doesNotExist();
    }

    @Test
    void filesStillBeingWrittenKeepTheFolder() throws Exception {
        write("Ablage/scan.pdf");
        write("Ablage/upload.pdf.part", new byte[] {1});
        scanAfter(0);
        scanAfter(11);
        scanAfter(11);
        assertThat(root.resolve("Ablage/upload.pdf.part")).exists();
    }

    @Test
    void rejectedFilesAlsoEmptyTheirFolder() throws Exception {
        write("erst.pdf", Samples.textPdf("identisch"));
        scanAfter(0);
        scanAfter(11);
        write("Ablage/doppelt.pdf", Samples.textPdf("identisch"));
        scanAfter(0);
        scanAfter(11);
        scanAfter(11);
        assertThat(root.resolve("Ablage")).doesNotExist();
        assertThat(root.resolve(FolderWatcher.REJECTED_DIR).resolve("doppelt.pdf")).exists();
    }

    @Test
    void otherEmptyFoldersGoAfterTenMinutes() throws Exception {
        Files.createDirectory(root.resolve("Scanner"));
        Files.createDirectories(root.resolve("Alt/2024/Q1"));
        write("Alt/Thumbs.db", new byte[] {1});
        write("scan.pdf");
        scanAfter(0);
        scanAfter(11);
        scanAfter(11);
        assertThat(root.resolve("Scanner")).isDirectory();
        scanAfter(FolderWatcher.EMPTY_FOLDER_SECONDS - 23);
        assertThat(root.resolve("Alt/2024/Q1")).isDirectory();
        scanAfter(1);
        assertThat(root.resolve("Scanner")).doesNotExist();
        assertThat(root.resolve("Alt")).doesNotExist();
        assertThat(root).isDirectory();
    }

    @Test
    void aFileInASubfolderKeepsTheFoldersAbove() throws Exception {
        write("Alt/2024/upload.pdf.part", new byte[] {1});
        Files.createDirectories(root.resolve("Alt/Leer"));
        scanAfter(0);
        scanAfter(FolderWatcher.EMPTY_FOLDER_SECONDS + 1);
        assertThat(root.resolve("Alt/Leer")).doesNotExist();
        assertThat(root.resolve("Alt/2024/upload.pdf.part")).exists();
    }

    @Test
    void systemFoldersAndRejectedFilesStay() throws Exception {
        Files.createDirectories(root.resolve(FolderWatcher.REJECTED_DIR));
        Files.createDirectories(root.resolve("#recycle"));
        Files.createDirectories(root.resolve("Ablage/.stfolder"));
        scanAfter(0);
        scanAfter(FolderWatcher.EMPTY_FOLDER_SECONDS + 1);
        assertThat(root.resolve(FolderWatcher.REJECTED_DIR)).isDirectory();
        assertThat(root.resolve("#recycle")).isDirectory();
        assertThat(root.resolve("Ablage/.stfolder")).isDirectory();
    }

    @Test
    void aFileThatArrivesMeanwhileIsKept() throws Exception {
        Files.createDirectories(root.resolve("Ablage/neu"));
        watcher = new FolderWatcher(root, 10, () -> now, ingest) {
            @Override
            protected List<Path> emptyFolders() {
                List<Path> found = super.emptyFolders();
                if (now > FolderWatcher.EMPTY_FOLDER_SECONDS) {
                    try {
                        write("Ablage/neu/scan.pdf.part", new byte[] {1});
                    } catch (IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                }
                return found;
            }
        };
        scanAfter(0);
        scanAfter(FolderWatcher.EMPTY_FOLDER_SECONDS + 1);
        assertThat(root.resolve("Ablage/neu/scan.pdf.part")).exists();
    }
}
