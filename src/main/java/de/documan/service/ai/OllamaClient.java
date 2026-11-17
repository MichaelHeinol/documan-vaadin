package de.documan.service.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import de.documan.config.DocuManProperties;

/**
 * Spricht mit einem Ollama-Server im eigenen Netz. Die Dokumente verlassen das Heimnetz nicht.
 *
 * <p>Fehlt das Modell, lädt Ollama es einmal selbst herunter. Das kann bei mehreren Gigabyte dauern.
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);
    private static final Duration CONNECT = Duration.ofSeconds(10);
    private static final Duration PULL = Duration.ofHours(2);
    private static final JsonMapper JSON = JsonMapper.shared();

    /** Der Server war nicht erreichbar oder hat mit einem Fehler geantwortet. Später erneut versuchen. */
    public static class UnavailableException extends IOException {
        public UnavailableException(String message) {
            super(message);
        }
    }

    private final DocuManProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT).build();

    public OllamaClient(DocuManProperties properties) {
        this.properties = properties;
    }

    public boolean configured() {
        return !properties.getOllamaUrl().isBlank();
    }

    private URI uri(String path) {
        String base = properties.getOllamaUrl().replaceAll("/+$", "");
        return URI.create(base + path);
    }

    private HttpResponse<String> post(String path, Object body, Duration timeout) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("Unterbrochen");
        } catch (IOException error) {
            throw new UnavailableException("Ollama unter " + properties.getOllamaUrl() + " nicht erreichbar: "
                    + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    /**
     * Fragt das Modell und gibt seine Antwort als JSON zurück. Das Schema legt fest, welche Felder sie hat.
     */
    public JsonNode ask(String system, String user, Map<String, Object> schema) throws IOException {
        // think=false: Modelle wie qwen3 antworten sonst erst nach langem Nachdenken. num_ctx: Ohne Angabe nimmt
        // Ollama ein kleines Fenster und schneidet längere Anfragen vorn ab, dann fehlen dem Modell die Anweisungen.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getOllamaModel());
        body.put("stream", false);
        body.put("think", false);
        body.put("format", schema);
        body.put("options", Map.of("temperature", 0, "num_ctx", properties.getOllamaContext()));
        body.put("messages", List.of(Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        Duration timeout = Duration.ofSeconds(properties.getOllamaTimeoutSeconds());
        HttpResponse<String> response = post("/api/chat", body, timeout);
        if (response.statusCode() == 404 && response.body().contains("not found")) {
            pull();
            response = post("/api/chat", body, timeout);
        }
        if (response.statusCode() == 400 && response.body().contains("thinking")) {
            // Manche Ollama-Fassungen lehnen think bei Modellen ohne Denkmodus ab.
            body.remove("think");
            response = post("/api/chat", body, timeout);
        }
        if (response.statusCode() != 200) {
            throw new UnavailableException("Ollama antwortet mit " + response.statusCode() + ": "
                    + abbreviate(response.body()));
        }
        String content;
        try {
            content = JSON.readTree(response.body()).path("message").path("content").asString("");
        } catch (JacksonException invalid) {
            throw new UnavailableException("Ollama antwortet unverständlich: " + abbreviate(response.body()));
        }
        try {
            return JSON.readTree(json(content));
        } catch (JacksonException invalid) {
            throw new IOException("Antwort des Modells ist kein JSON: " + abbreviate(content));
        }
    }

    /**
     * Das JSON-Objekt aus der Antwort. Manche Modelle stellen trotz Schema Überlegungen ({@code <think>…</think>})
     * oder einen Markdown-Rahmen davor.
     */
    static String json(String content) {
        String text = content.replaceAll("(?s)<think>.*?</think>", "").strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    /** Lädt das Modell auf den Ollama-Server. */
    private void pull() throws IOException {
        log.info("Ollama lädt das Modell {} herunter, das kann dauern", properties.getOllamaModel());
        HttpResponse<String> response = post("/api/pull",
                Map.of("model", properties.getOllamaModel(), "stream", false), PULL);
        if (response.statusCode() != 200) {
            throw new UnavailableException("Modell " + properties.getOllamaModel() + " ließ sich nicht laden: "
                    + abbreviate(response.body()));
        }
    }

    private static String abbreviate(String text) {
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() > 300 ? single.substring(0, 300) + " …" : single;
    }
}
