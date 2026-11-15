package de.documan.service.ai;

import java.time.Instant;

import org.springframework.stereotype.Service;

import de.documan.config.DocuManProperties;
import de.documan.repo.AiRepository;

/** Zustand der KI-Zuordnung für die Übersicht der Administratoren. */
@Service
public class AiStatus {

    /**
     * @param waiting Dokumente, die noch auf das Sprachmodell warten
     */
    public record Overview(boolean learning, Instant learnedAt, int learnedDocuments, int assigned,
            boolean modelConfigured, String model, String modelUrl, Instant modelOkAt, String modelError,
            Instant modelErrorAt, long waiting) {

        /** Der letzte Versuch mit dem Sprachmodell ist gescheitert. */
        public boolean modelFailing() {
            return !modelError.isEmpty() && modelErrorAt != null
                    && (modelOkAt == null || modelErrorAt.isAfter(modelOkAt));
        }
    }

    private final DocuManProperties properties;
    private final AiRepository ai;

    public AiStatus(DocuManProperties properties, AiRepository ai) {
        this.properties = properties;
        this.ai = ai;
    }

    public Overview overview() {
        AiRepository.Status status = ai.status();
        boolean configured = !properties.getOllamaUrl().isBlank();
        return new Overview(properties.isAiLearning(), status.learnedAt(), status.learnedDocuments(),
                status.assigned(), configured, properties.getOllamaModel(), properties.getOllamaUrl(),
                status.modelOkAt(), status.modelError(), status.modelErrorAt(),
                configured ? ai.waitingForLanguageModel() : 0);
    }
}
