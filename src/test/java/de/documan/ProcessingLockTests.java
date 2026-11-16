package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.documan.domain.Document;
import de.documan.domain.DocumentStatus;

/** Während der (erneuten) Verarbeitung lassen sich die Angaben eines Dokuments nicht ändern. */
class ProcessingLockTests extends IntegrationTest {

    @Test
    void onlyFinishedDocumentsCanBeEdited() {
        Document done = document("Fertig").create();
        Document failed = document("Fehler").status(DocumentStatus.FAILED).create();
        Document pending = document("Wartet").status(DocumentStatus.PENDING).create();
        Document processing = document("Läuft").status(DocumentStatus.PROCESSING).create();
        assertThat(documents.finishedIds(List.of(done.id(), failed.id(), pending.id(), processing.id())))
                .containsExactly(done.id(), failed.id());
        assertThat(documents.finishedIds(List.of())).isEmpty();
        assertThat(reload(processing).isFinished()).isFalse();
    }
}
