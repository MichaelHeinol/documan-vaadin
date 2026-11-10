package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import de.documan.domain.Document;
import de.documan.domain.DocumentStatus;
import de.documan.repo.DocumentRepository.Processed;
import de.documan.repo.MasterRepository;
import de.documan.service.DuplicateDocumentException;
import de.documan.service.ImageToPdf;
import de.documan.service.IngestException;
import de.documan.service.MediaStore;
import de.documan.service.PipelineService;
import de.documan.service.ProcessRunner;

class PipelineTests extends IntegrationTest {

    @Autowired
    PipelineService pipeline;
    @Autowired
    MasterRepository masterRepository;
    @Autowired
    MediaStore media;
    @Autowired
    ProcessRunner processes;

    private Document process(Document document) {
        assertThat(pipeline.processNext()).isTrue();
        return reload(document);
    }

    private void requeue(Document document) {
        assertThat(documents.requeue(new Long[] {document.id()})).isEqualTo(1);
    }

    @Test
    void storesOriginalAndQueues() throws Exception {
        Document document = add(Samples.textPdf("Hallo"), "Meine_Rechnung 2025.pdf");
        assertThat(document.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(document.title()).isEqualTo("Meine Rechnung 2025");
        assertThat(document.mimeType()).isEqualTo("application/pdf");
        assertThat(document.original()).matches("^originals/\\d{4}/\\d{2}/[0-9a-f]{8}-.+\\.pdf$");
        assertThat(media.resolve(document.original())).hasBinaryContent(Samples.textPdf("Hallo"));
    }

    @Test
    void duplicateIsRejectedRegardlessOfName() throws Exception {
        Document first = add(Samples.textPdf("Hallo"), "a.pdf");
        assertThatThrownBy(() -> add(Samples.textPdf("Hallo"), "b.pdf"))
                .isInstanceOfSatisfying(DuplicateDocumentException.class,
                        duplicate -> assertThat(duplicate.existing().id()).isEqualTo(first.id()));
    }

    @Test
    void typeComesFromContentNotFromExtension() throws Exception {
        Document disguised = add("<html><script>alert(1)</script></html>".getBytes(), "rechnung.pdf");
        assertThat(disguised.mimeType()).isEqualTo("text/html");
        assertThat(disguised.original()).endsWith(".bin");
        Document document = add(Samples.png("x"), "foto.pdf");
        assertThat(document.mimeType()).isEqualTo("image/png");
        assertThat(document.original()).endsWith(".png");
    }

    @Test
    void rejectsEmptyAndOversized() {
        assertThatThrownBy(() -> add(new byte[0])).isInstanceOf(IngestException.class);
        properties.setMaxFileMb(0);
        assertThatThrownBy(() -> add(Samples.textPdf("Hallo"))).isInstanceOf(IngestException.class);
    }

    @Test
    void hostileFilenameStaysInsideMediaRoot() throws Exception {
        Document document = add(Samples.textPdf("Hallo"), "../../etc/pass wd.pdf");
        assertThat(media.resolve(document.original()).normalize()).startsWith(mediaRoot);
        assertThat(document.title()).isEqualTo("pass wd");
    }

    @Test
    void assignedTagsAreSearchableImmediately() throws Exception {
        var tag = tag("Steuer");
        var filename = "datei.pdf";
        var options = new de.documan.service.IngestService.Options(null, "", "",
                de.documan.domain.MailHeaders.NONE, null, null, List.of(tag.id()), List.of());
        Document document = ingest.ingest(new org.springframework.core.io.ByteArrayResource(Samples.textPdf("Hallo")),
                filename, de.documan.domain.DocumentSource.UPLOAD, options);
        assertThat(document.tags()).extracting(item -> item.name()).containsExactly("Steuer");
        String vector = jdbc.sql("SELECT search_vector::text FROM document WHERE id = ?")
                .param(document.id()).query(String.class).single();
        assertThat(vector).contains("steu");
    }

    @Test
    void bornDigitalPdfSkipsOcr() throws Exception {
        String text = "Stadtwerke Rechnung vom 14.03.2025 mit vorhandener Textebene";
        Document document = process(add(Samples.textPdf(text)));
        assertThat(document.status()).as(document.error()).isEqualTo(DocumentStatus.DONE);
        assertThat(document.content()).contains("vorhandener Textebene");
        assertThat(document.hasArchive()).isFalse();
        assertThat(document.pageCount()).isEqualTo(1);
        assertThat(document.documentDate()).isEqualTo("2025-03-14");
        assertThat(media.resolve(document.thumbnail())).exists();
    }

    @Test
    void scannedPngGetsOcrAndSearchableArchive() throws Exception {
        byte[] image = Samples.png("Stadtwerke Musterstadt", "Stromrechnung", "Datum: 02.04.2025");
        Document document = process(add(image, "scan.png"));
        assertThat(document.status()).as(document.error()).isEqualTo(DocumentStatus.DONE);
        assertThat(document.content()).contains("Stromrechnung");
        assertThat(document.documentDate()).isEqualTo("2025-04-02");
        assertThat(pipeline.extractPdfPages(media.resolve(document.archive())).getFirst()).contains("Stromrechnung");
        assertThat(document.archive()).startsWith("archive/");
    }

    @Test
    void imageOnlyPdfGetsOcrAndKeepsOriginal() throws Exception {
        byte[] pdf = Samples.imageOnlyPdf(workspace, "Versicherungsschein");
        Document document = process(add(pdf, "scan.pdf"));
        assertThat(document.status()).as(document.error()).isEqualTo(DocumentStatus.DONE);
        assertThat(document.content()).contains("Versicherungsschein");
        assertThat(media.resolve(document.original())).hasBinaryContent(pdf);
        assertThat(document.hasArchive()).isTrue();
    }

    @Test
    void corruptPdfFailsButKeepsOriginal() throws Exception {
        byte[] data = ("%PDF-1.7\nkaputt" + "\u0001\u0002".repeat(32)).getBytes();
        Document document = process(add(data));
        assertThat(document.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.error()).isNotEmpty();
        assertThat(media.resolve(document.original())).exists();
    }

    @Test
    void passwordProtectedPdfReportsReason() throws Exception {
        Document document = process(add(Samples.encryptedPdf("Geheim")));
        assertThat(document.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.error()).contains("passwortgeschützt");
    }

    @Test
    void officeFileStaysUnchangedAndBecomesSearchable() throws Exception {
        byte[] data = OfficeSamples.docx();
        Document document = add(data, "Brief an Müller.docx");
        assertThat(document.original()).endsWith(".docx");
        document = process(document);
        assertThat(document.status()).as(document.error()).isEqualTo(DocumentStatus.DONE);
        assertThat(document.content()).contains("Rechnung Nr. 4711");
        assertThat(document.hasArchive()).isFalse();
        assertThat(document.hasThumbnail()).isFalse();
        assertThat(document.previewKind()).isEqualTo("text");
        assertThat(media.resolve(document.original())).hasBinaryContent(data);
    }

    @Test
    void unknownFormatIsKeptWithoutPreview() throws Exception {
        byte[] data = new byte[1024];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) index;
        }
        Document document = process(add(data, "messung.XYZ"));
        assertThat(document.status()).as(document.error()).isEqualTo(DocumentStatus.DONE);
        assertThat(document.original()).endsWith(".xyz");
        assertThat(document.content()).isEmpty();
        assertThat(document.previewKind()).isEqualTo("none");
        assertThat(media.resolve(document.original())).hasBinaryContent(data);
    }

    @Test
    void textFileInWindowsEncoding() throws Exception {
        byte[] data = "Grüße aus Köln\n".repeat(4).getBytes(Charset.forName("windows-1252"));
        Document document = process(add(data, "notiz.txt"));
        assertThat(document.status()).as(document.error()).isEqualTo(DocumentStatus.DONE);
        assertThat(document.content()).contains("Grüße aus Köln");
        assertThat(document.previewKind()).isEqualTo("text");
    }

    @Test
    void nulCharactersAreRemoved() {
        assertThat(PipelineService.cleanText(" a\u0000b ".replace('\u0000', (char) 0))).isEqualTo("ab");
    }

    @Test
    void reprocessingKeepsUserEdits() throws Exception {
        Document document = process(add(Samples.textPdf("Rechnung vom 14.03.2025 mit genug Text")));
        documents.updateMetadata(document.id(), "Eigener Titel", null, null, java.time.LocalDate.of(2020, 1, 1), "", "Notiz");
        requeue(document);
        document = process(document);
        assertThat(document.title()).isEqualTo("Eigener Titel");
        assertThat(document.notes()).isEqualTo("Notiz");
        assertThat(document.documentDate()).isEqualTo("2020-01-01");
    }

    @Test
    void reprocessingReplacesGeneratedFiles() throws Exception {
        Document document = process(add(Samples.png("Erster Lauf"), "scan.png"));
        String archive = document.archive();
        String thumbnail = document.thumbnail();
        requeue(document);
        document = process(document);
        assertThat(document.archive()).isEqualTo(archive);
        assertThat(document.thumbnail()).isEqualTo(thumbnail);
    }

    @Test
    void queueIsProcessedOldestFirstAndEmptyQueueIsFalse() throws Exception {
        Document first = add(Samples.textPdf("Erstes Dokument mit etwas mehr Text"), "a.pdf");
        Document second = add(Samples.textPdf("Zweites Dokument mit etwas mehr Text"), "b.pdf");
        pipeline.processNext();
        assertThat(reload(first).status()).isEqualTo(DocumentStatus.DONE);
        assertThat(reload(second).status()).isEqualTo(DocumentStatus.PENDING);
        pipeline.processNext();
        assertThat(pipeline.processNext()).isFalse();
    }

    @Test
    void interruptedDocumentsReturnToQueueWithALimit() {
        Document retry = document("Dokument").status(DocumentStatus.PROCESSING).attempts(1).create();
        Document giveUp = document("Dokument").status(DocumentStatus.PROCESSING)
                .attempts(PipelineService.MAX_ATTEMPTS).create();
        assertThat(pipeline.recoverInterrupted()).isEqualTo(1);
        assertThat(reload(retry).status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(reload(giveUp).status()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    void editDuringProcessingSurvives() throws Exception {
        Document document = add(Samples.textPdf("Rechnung mit ausreichend viel Inhalt"));
        PipelineService editing = new PipelineService(properties, documents, masterRepository, reindexer, media,
                processes) {
            @Override
            protected Processed processDocument(Document claimed) {
                documents.updateMetadata(claimed.id(), "Während OCR geändert", null, null, null, "", "");
                return new Processed("", null, "", "", null, null, null);
            }
        };
        editing.processNext();
        assertThat(reload(document).title()).isEqualTo("Während OCR geändert");
        assertThat(reload(document).status()).isEqualTo(DocumentStatus.DONE);
    }

    private int convert(byte[] data, String suffix) throws IOException {
        Path source = workspace.resolve("input" + suffix);
        Path target = workspace.resolve("output.pdf");
        Files.write(source, data);
        ImageToPdf.convert(source, target);
        return Samples.pageCount(target);
    }

    @Test
    void transparentPng() throws Exception {
        BufferedImage image = Samples.scanImage(BufferedImage.TYPE_INT_ARGB, "x");
        assertThat(convert(Samples.imageBytes(image, "png"), ".png")).isEqualTo(1);
    }

    @Test
    void grayscaleJpeg() throws Exception {
        BufferedImage image = Samples.scanImage(BufferedImage.TYPE_BYTE_GRAY, "x");
        assertThat(convert(Samples.imageBytes(image, "jpeg"), ".jpg")).isEqualTo(1);
    }

    @Test
    void multipageTiffKeepsPages() throws Exception {
        byte[] data = Samples.multipageTiff(
                Samples.scanImage(BufferedImage.TYPE_BYTE_BINARY, "eins"),
                Samples.scanImage(BufferedImage.TYPE_BYTE_BINARY, "zwei"),
                Samples.scanImage(BufferedImage.TYPE_BYTE_BINARY, "drei"));
        assertThat(convert(data, ".tif")).isEqualTo(3);
    }

    @Test
    void webpIsRead() throws Exception {
        // 1x1 Pixel, verlustbehaftet, von cwebp erzeugt.
        byte[] webp = java.util.Base64.getDecoder()
                .decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        assertThat(convert(webp, ".webp")).isEqualTo(1);
    }

    @Test
    void unreadableImageIsReportedAsFailure() throws Exception {
        // Beginnt wie ein PNG, bricht aber mitten in den Bilddaten ab.
        byte[] data = java.util.Arrays.copyOf(Samples.png("Text"), 200);
        Document document = process(add(data, "kaputt.png"));
        assertThat(document.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.error()).contains("Bild konnte nicht gelesen werden");
    }
}
