package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import de.documan.domain.Document;
import de.documan.repo.AiRepository;
import de.documan.repo.WorkerRequestRepository;
import de.documan.service.FolderWatcher;

/** Eingangsordner von Hand einlesen und die KI-Analyse als Status. */
class ManualImportAndAnalysisTests extends IntegrationTest {

    @Autowired
    WorkerRequestRepository requests;
    @Autowired
    AiRepository ai;

    private Path folderWith(String name, Instant modified) throws IOException {
        Path root = workspace.resolve("eingang");
        Files.createDirectories(root);
        Path file = Files.write(root.resolve(name), Samples.textPdf(name));
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return root;
    }

    @Test
    void aManualScanTakesFilesThatHaveLongBeenUnchanged() throws Exception {
        Path root = folderWith("alt.pdf", Instant.now().minusSeconds(60));
        assertThat(new FolderWatcher(root, 10, () -> 0, ingest).scan()).isZero();
        assertThat(new FolderWatcher(root, 10, () -> 0, ingest).scan(true)).isEqualTo(1);
        assertThat(documents.total()).isEqualTo(1);
    }

    @Test
    void aManualScanStillWaitsForFilesBeingWritten() throws Exception {
        Path root = folderWith("frisch.pdf", Instant.now());
        assertThat(new FolderWatcher(root, 10, () -> 0, ingest).scan(true)).isZero();
        assertThat(documents.total()).isZero();
    }

    @Test
    void aRequestStaysPendingUntilTheWorkerHandledIt() {
        assertThat(requests.pending(WorkerRequestRepository.CONSUME)).isFalse();
        requests.request(WorkerRequestRepository.CONSUME, "anna");
        assertThat(requests.pending(WorkerRequestRepository.CONSUME)).isTrue();
        assertThat(requests.find(WorkerRequestRepository.CONSUME).requestedBy()).isEqualTo("anna");
        String before = documents.changeStamp();
        requests.handled(WorkerRequestRepository.CONSUME, "2 Dateien übernommen");
        assertThat(requests.pending(WorkerRequestRepository.CONSUME)).isFalse();
        assertThat(requests.find(WorkerRequestRepository.CONSUME).result()).isEqualTo("2 Dateien übernommen");
        assertThat(documents.changeStamp()).isNotEqualTo(before);
    }

    @Test
    void waitingAndRunningAnalysesAreShown() throws Exception {
        Document waiting = document("Wartet").create();
        Document running = document("Läuft").create();
        Document plain = document("Fertig").create();
        ai.requestLanguageModel(waiting.id());
        ai.requestLanguageModel(running.id());
        ai.analysisStarted(running.id());

        assertThat(ai.analysis(waiting.id())).isEqualTo(AiRepository.Analysis.WAITING);
        assertThat(ai.analysis(running.id())).isEqualTo(AiRepository.Analysis.RUNNING);
        assertThat(ai.analysis(plain.id())).isEqualTo(AiRepository.Analysis.NONE);
        assertThat(ai.analysing(java.util.List.of(waiting.id(), running.id(), plain.id())))
                .containsExactlyInAnyOrder(waiting.id(), running.id());

        ai.analysisFinished();
        ai.languageModelDone(running.id());
        assertThat(ai.analysis(running.id())).isEqualTo(AiRepository.Analysis.NONE);
    }
}
