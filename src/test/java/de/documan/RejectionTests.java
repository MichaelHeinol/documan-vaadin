package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;

import de.documan.domain.DocumentSource;
import de.documan.repo.ImportRejectionRepository;
import de.documan.repo.ImportRejectionRepository.ImportRejection;
import de.documan.service.IngestException;
import de.documan.service.IngestService;

/** Abgelehnte Dateien bleiben unter „Fehlerhafte Importe“ sichtbar, egal woher sie kamen. */
class RejectionTests extends IntegrationTest {

    @Autowired
    ImportRejectionRepository rejections;
    @Autowired
    IngestService ingest;

    private void offer(byte[] content, String name, DocumentSource source, IngestService.Options options) {
        try {
            ingest.ingest(new ByteArrayResource(content), name, source, options);
        } catch (IngestException expected) {
            // Die Ablehnung zählt, nicht die Ausnahme.
        }
    }

    @Test
    void rejectedFilesAreKeptWithReasonAndOrigin() throws Exception {
        byte[] pdf = Samples.textPdf("Rechnung mit genug Text");
        offer(pdf, "rechnung.pdf", DocumentSource.UPLOAD, IngestService.Options.of("anna", ""));
        offer(pdf, "noch-einmal.pdf", DocumentSource.UPLOAD, IngestService.Options.of("anna", ""));
        offer(new byte[0], "scan.pdf", DocumentSource.CONSUME,
                IngestService.Options.of(IngestService.CONSUME_LABEL, "Steuer/scan.pdf"));

        List<ImportRejection> all = rejections.all();
        assertThat(all).extracting(ImportRejection::filename).containsExactly("scan.pdf", "noch-einmal.pdf");
        ImportRejection empty = all.getFirst();
        assertThat(empty.reason()).isEqualTo("Die Datei ist leer.");
        assertThat(empty.source()).isEqualTo(DocumentSource.CONSUME);
        assertThat(empty.sourceDetail()).isEqualTo("Steuer/scan.pdf");
        assertThat(empty.origin()).isEqualTo("Eingangsordner");
        ImportRejection duplicate = all.get(1);
        assertThat(duplicate.reason()).startsWith("Bereits vorhanden als");
        assertThat(duplicate.existingDocumentId()).isNotNull();
        assertThat(duplicate.addedBy()).isEqualTo("anna");
        assertThat(duplicate.origin()).isEqualTo("Hochgeladen · anna");
        assertThat(documents.navigationCounts().rejected()).isEqualTo(2);
    }

    @Test
    void entriesCanBeRemovedAndOnlyTheNewestAreKept() {
        for (int number = 0; number < ImportRejectionRepository.KEEP + 3; number++) {
            rejections.record(DocumentSource.MAIL, "anhang-" + number + ".pdf", "Die Datei ist leer.", "", "", null);
        }
        assertThat(rejections.count()).isEqualTo(ImportRejectionRepository.KEEP);
        assertThat(rejections.all()).extracting(ImportRejection::filename).doesNotContain("anhang-0.pdf");
        String before = documents.changeStamp();
        assertThat(rejections.delete(rejections.all().getFirst().id())).isTrue();
        assertThat(documents.changeStamp()).isNotEqualTo(before);
        assertThat(rejections.deleteAll()).isEqualTo(ImportRejectionRepository.KEEP - 1);
        assertThat(rejections.count()).isZero();
    }
}
