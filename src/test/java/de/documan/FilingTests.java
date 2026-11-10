package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import de.documan.domain.Algorithm;
import de.documan.domain.Document;
import de.documan.domain.DocumentStatus;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.service.FilingService;
import de.documan.service.MediaStore;
import de.documan.service.PipelineService;

class FilingTests extends IntegrationTest {

    @Autowired
    FilingService filing;
    @Autowired
    PipelineService pipeline;
    @Autowired
    MediaStore media;
    @Autowired
    PlatformTransactionManager transactions;

    private static final String MARCH = "2026-03-14";
    private static final String UNSORTED = "Ohne Kategorie/Ohne Absendername/Ohne Dokumenttyp";

    /** Dokument mit drei echten Dateien im alten Ordner, das auf den Umzug wartet. */
    private Document stored(String title, String name, boolean extra, boolean pending, DocumentStatus status,
            MasterItem correspondent) throws IOException {
        DocumentBuilder builder = document(title).date(MARCH).status(status).original("originals/2026/10/" + name + ".pdf");
        if (correspondent != null) {
            builder.correspondent(correspondent);
        }
        Document document = builder.create();
        String archive = extra ? "archive/2026/10/" + name + ".pdf" : "";
        String thumbnail = extra ? "thumbnails/2026/10/" + name + ".jpg" : "";
        jdbc.sql("UPDATE document SET archive = ?, thumbnail = ?, file_sync_pending = ? WHERE id = ?")
                .params(archive, thumbnail, pending, document.id()).update();
        for (String file : List.of(document.original(), archive, thumbnail)) {
            if (!file.isEmpty()) {
                Path path = mediaRoot.resolve(file);
                Files.createDirectories(path.getParent());
                Files.writeString(path, file);
            }
        }
        return reload(document);
    }

    private Document stored(String title, String name) throws IOException {
        return stored(title, name, true, true, DocumentStatus.DONE, null);
    }

    private Document typed(Document document, MasterItem type) {
        documents.setDocumentType(new Long[] {document.id()}, type.id());
        return reload(document);
    }

    private void refileAll() {
        while (filing.refileNext()) {
            // Alle Dokumente der Reihe nach umziehen.
        }
    }

    private boolean pending(Document document) {
        return jdbc.sql("SELECT file_sync_pending FROM document WHERE id = ?").param(document.id())
                .query(Boolean.class).single();
    }

    @Test
    void usesSenderAndDocumentDate() {
        Document document = document("Stromrechnung").date(MARCH)
                .correspondent(correspondent("Stadtwerke Musterstadt")).create();
        assertThat(filing.fileStem(document)).isEqualTo("Stadtwerke Musterstadt_20260314");
    }

    @Test
    void withoutCorrespondentTheTitleTakesItsPlace() {
        assertThat(filing.fileStem(document("Stromrechnung").date(MARCH).create()))
                .isEqualTo("Stromrechnung_20260314");
    }

    @Test
    void removesCharactersWindowsRefuses() {
        assertThat(FilingService.cleanName("A/B: \"Müller\" <GmbH>?*|\\")).isEqualTo("A B Müller GmbH");
    }

    @Test
    void keepsUmlautsAndAmpersands() {
        assertThat(FilingService.cleanName("Müller & Söhne (Köln)")).isEqualTo("Müller & Söhne (Köln)");
    }

    @Test
    void trailingDotsAreRemoved() {
        assertThat(FilingService.cleanName("Muster GmbH. ...")).isEqualTo("Muster GmbH");
    }

    @Test
    void longNamesStayWithinTheByteLimit() {
        String name = FilingService.cleanName("ä".repeat(300));
        assertThat(name.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(130);
        assertThat(name).endsWith("ä");
    }

    @Test
    void aLongSenderIsShortenedAndTheDateStays() {
        String stem = filing.fileStem(document("ä".repeat(300)).date(MARCH).create());
        assertThat(stem.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(130);
        assertThat(stem).endsWith("ä_20260314");
    }

    @Test
    void emptyNameGetsAFallback() {
        assertThat(filing.fileStem(document("???").date(MARCH).create())).isEqualTo("Dokument_20260314");
    }

    @Test
    void documentTypePrecedesTheDate() {
        Document document = document("Strom").date(MARCH).correspondent(correspondent("Telekom"))
                .type(documentType("Rechnung")).create();
        assertThat(filing.fileStem(document)).isEqualTo("Telekom_Rechnung_20260314");
    }

    @Test
    void aLongSenderGivesWayToTypeAndDate() {
        String stem = filing.fileStem(document("ä".repeat(300)).date(MARCH).type(documentType("Rechnung")).create());
        assertThat(stem.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(130);
        assertThat(stem).endsWith("ä_Rechnung_20260314");
    }

    @Test
    void aLongTypeIsShortened() {
        Document document = document("Strom").date(MARCH).type(documentType("x".repeat(200))).create();
        assertThat(filing.fileStem(document)).isEqualTo("Strom_" + "x".repeat(40) + "_20260314");
    }

    @Test
    void aTypeWithoutValidCharactersAddsNothing() {
        Document document = document("Strom").date(MARCH).type(documentType("..")).create();
        assertThat(filing.fileStem(document)).isEqualTo("Strom_20260314");
    }

    @Test
    void withoutDateTheImportDateCounts() {
        LocalDate today = LocalDate.now(properties.getTimeZone());
        assertThat(filing.fileStem(document("Strom").create()))
                .isEqualTo("Strom_" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
    }

    @Test
    void folderFollowsTheCategorization() {
        Document document = document("Dokument").correspondent(correspondent("Telekom"))
                .type(documentType("Rechnung")).create();
        assertThat(filing.folderOf(document)).isEqualTo("Ohne Kategorie/Telekom/Rechnung");
    }

    @Test
    void missingCategoriesGetTheirOwnFolder() {
        assertThat(filing.folderOf(document("Dokument").create())).isEqualTo(UNSORTED);
    }

    @Test
    void folderNamesCannotAddLevels() {
        Document document = document("Dokument").correspondent(correspondent("A/B: Müller"))
                .type(documentType("..")).create();
        assertThat(filing.folderOf(document)).isEqualTo("Ohne Kategorie/A B Müller/Ohne Dokumenttyp");
    }

    @Test
    void movesAllFilesTogether() throws Exception {
        Document document = typed(stored("Strom", "abcd", true, true, DocumentStatus.DONE, correspondent("Stadtwerke")),
                documentType("Rechnung"));
        assertThat(filing.refileNext()).isTrue();
        Document moved = reload(document);
        assertThat(moved.original()).isEqualTo("originals/Ohne Kategorie/Stadtwerke/Rechnung/Stadtwerke_Rechnung_20260314.pdf");
        assertThat(moved.archive()).isEqualTo("archive/Ohne Kategorie/Stadtwerke/Rechnung/Stadtwerke_Rechnung_20260314.pdf");
        assertThat(moved.thumbnail()).isEqualTo("thumbnails/Ohne Kategorie/Stadtwerke/Rechnung/Stadtwerke_Rechnung_20260314.jpg");
        assertThat(pending(moved)).isFalse();
        for (String file : List.of(moved.original(), moved.archive(), moved.thumbnail())) {
            assertThat(mediaRoot.resolve(file)).exists();
        }
    }

    @Test
    void contentStaysTheSame() throws Exception {
        Document document = stored("Strom", "abcd");
        byte[] before = Files.readAllBytes(mediaRoot.resolve(document.original()));
        filing.refileNext();
        assertThat(mediaRoot.resolve(reload(document).original())).hasBinaryContent(before);
    }

    @Test
    void oldEmptyFoldersDisappear() throws Exception {
        stored("Strom", "abcd");
        filing.refileNext();
        assertThat(mediaRoot.resolve("originals/Ohne Kategorie/2026")).doesNotExist();
        assertThat(mediaRoot.resolve("originals/" + UNSORTED)).exists();
    }

    @Test
    void foldersWithOtherFilesStay() throws Exception {
        stored("Strom", "eins");
        stored("Gas", "zwei", true, false, DocumentStatus.DONE, null);
        filing.refileNext();
        assertThat(mediaRoot.resolve("originals/2026/10/zwei.pdf")).exists();
    }

    @Test
    void nothingToDo() throws Exception {
        stored("Strom", "abcd", true, false, DocumentStatus.DONE, null);
        assertThat(filing.refileNext()).isFalse();
    }

    @Test
    void runningDocumentsWait() throws Exception {
        Document document = stored("Strom", "abcd", true, true, DocumentStatus.PROCESSING, null);
        assertThat(filing.refileNext()).isFalse();
        assertThat(pending(document)).isTrue();
    }

    @Test
    void failedDocumentsAreFiledToo() throws Exception {
        Document document = stored("Strom", "abcd", true, true, DocumentStatus.FAILED, null);
        assertThat(filing.refileNext()).isTrue();
        assertThat(reload(document).original()).startsWith("originals/" + UNSORTED + "/");
    }

    @Test
    void sameSenderAndDateGetAnIndex() throws Exception {
        stored("Rechnung", "eins");
        stored("Rechnung", "zwei");
        stored("Rechnung", "drei");
        refileAll();
        assertThat(jdbc.sql("SELECT original FROM document").query(String.class).list())
                .containsExactlyInAnyOrder("originals/" + UNSORTED + "/Rechnung_20260314.pdf",
                        "originals/" + UNSORTED + "/Rechnung_20260314_2.pdf",
                        "originals/" + UNSORTED + "/Rechnung_20260314_3.pdf");
    }

    @Test
    void indexComesAfterTheDocumentType() throws Exception {
        MasterItem type = documentType("Mahnung");
        typed(stored("Rechnung", "eins"), type);
        Document second = typed(stored("Rechnung", "zwei"), type);
        refileAll();
        assertThat(reload(second).original())
                .isEqualTo("originals/Ohne Kategorie/Ohne Absendername/Mahnung/Rechnung_Mahnung_20260314_2.pdf");
    }

    @Test
    void downloadKeepsTheIndexOfTheFile() throws Exception {
        stored("Rechnung", "eins");
        Document second = stored("Rechnung", "zwei");
        refileAll();
        assertThat(filing.downloadName(reload(second), ".pdf")).isEqualTo("Rechnung_20260314_2.pdf");
    }

    @Test
    void downloadBeforeFilingUsesTheComingName() throws Exception {
        Document waiting = stored("Rechnung", "abcd1234-scan");
        assertThat(filing.downloadName(waiting, ".pdf")).isEqualTo("Rechnung_20260314.pdf");
    }

    @Test
    void downloadAfterAChangeUsesTheNewName() throws Exception {
        Document document = stored("Rechnung", "eins");
        refileAll();
        documents.setCorrespondent(new Long[] {document.id()}, correspondent("Telekom").id());
        assertThat(filing.downloadName(reload(document), ".pdf")).isEqualTo("Telekom_20260314.pdf");
    }

    @Test
    void anotherFolderNeedsNoIndex() throws Exception {
        stored("Rechnung", "eins");
        typed(stored("Rechnung", "zwei"), documentType("Mahnung"));
        refileAll();
        assertThat(jdbc.sql("SELECT original FROM document").query(String.class).list())
                .containsExactlyInAnyOrder("originals/" + UNSORTED + "/Rechnung_20260314.pdf",
                        "originals/Ohne Kategorie/Ohne Absendername/Mahnung/Rechnung_Mahnung_20260314.pdf");
    }

    @Test
    void indexStaysOnRepeatedFiling() throws Exception {
        stored("Rechnung", "eins");
        Document second = stored("Rechnung", "zwei");
        refileAll();
        documents.requestFileSync(new Long[] {second.id()});
        filing.refileNext();
        assertThat(reload(second).original()).isEqualTo("originals/" + UNSORTED + "/Rechnung_20260314_2.pdf");
    }

    @Test
    void alreadyCorrectFileStays() throws Exception {
        String name = "originals/" + UNSORTED + "/Rechnung_20260314.pdf";
        Document document = document("Rechnung").date(MARCH).original(name).stored();
        documents.requestFileSync(new Long[] {document.id()});
        filing.refileNext();
        assertThat(reload(document).original()).isEqualTo(name);
        assertThat(pending(document)).isFalse();
    }

    @Test
    void foreignFileAtTheTargetIsNotOverwritten() throws Exception {
        Path foreign = mediaRoot.resolve("originals/" + UNSORTED + "/Rechnung_20260314.pdf");
        Files.createDirectories(foreign.getParent());
        Files.writeString(foreign, "fremd");
        Document document = stored("Rechnung", "abcd", false, true, DocumentStatus.DONE, null);
        filing.refileNext();
        assertThat(reload(document).original()).isEqualTo("originals/" + UNSORTED + "/Rechnung_20260314_2.pdf");
        assertThat(foreign).hasContent("fremd");
    }

    @Test
    void repeatsAfterACrashBetweenMoveAndDatabase() throws Exception {
        Document document = stored("Rechnung", "abcd", false, true, DocumentStatus.DONE, null);
        Path target = mediaRoot.resolve("originals/" + UNSORTED + "/Rechnung_20260314.pdf");
        Files.createDirectories(target.getParent());
        Files.move(mediaRoot.resolve(document.original()), target);
        filing.refileNext();
        assertThat(reload(document).original()).isEqualTo("originals/" + UNSORTED + "/Rechnung_20260314.pdf");
    }

    @Test
    void missingFileKeepsItsName() {
        Document document = document("Weg").original("originals/2026/10/weg.pdf").create();
        documents.requestFileSync(new Long[] {document.id()});
        filing.refileNext();
        assertThat(reload(document).original()).isEqualTo("originals/2026/10/weg.pdf");
        assertThat(pending(document)).isFalse();
    }

    @Test
    void failedMoveRestoresEveryFile() throws Exception {
        Document document = stored("Rechnung", "abcd");
        int[] calls = {0};
        FilingService failing = new FilingService(properties, documents, media, master, transactions) {
            @Override
            protected void rename(Path source, Path destination) throws IOException {
                if (++calls[0] == 2) {
                    throw new AccessDeniedException("Keine Rechte");
                }
                super.rename(source, destination);
            }
        };
        assertThat(failing.refileNext()).isTrue();
        Document unchanged = reload(document);
        assertThat(unchanged.original()).isEqualTo("originals/2026/10/abcd.pdf");
        assertThat(mediaRoot.resolve(unchanged.original())).exists();
        assertThat(mediaRoot.resolve(unchanged.archive())).exists();
        assertThat(pending(document)).isFalse();
    }










    @Test
    void newCorrespondentAloneChangesNothing() {
        correspondent("Neu");
        assertThat(filing.refileNext()).isFalse();
    }

    @Test
    void newDocumentTypeAloneChangesNothing() {
        documentType("Neu");
        assertThat(filing.refileNext()).isFalse();
    }

    @Test
    void importEndsWithTheFinalName() throws Exception {
        master.insert(MasterKind.CORRESPONDENT, "Stadtwerke", Algorithm.ANY, "Stromrechnung", true, null);
        byte[] pdf = Samples.textPdf("Stromrechnung vom 14.03.2026 mit genug Text");
        Document document = add(pdf, "scan.pdf");
        assertThat(pipeline.processNext()).isTrue();
        assertThat(filing.refileNext()).isTrue();
        Document filed = reload(document);
        assertThat(filed.original()).isEqualTo("originals/Ohne Kategorie/Stadtwerke/Ohne Dokumenttyp/Stadtwerke_20260314.pdf");
        assertThat(filed.thumbnail()).isEqualTo("thumbnails/Ohne Kategorie/Stadtwerke/Ohne Dokumenttyp/Stadtwerke_20260314.jpg");
        assertThat(mediaRoot.resolve(filed.original())).hasBinaryContent(pdf);
    }
}
