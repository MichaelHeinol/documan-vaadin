package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sun.net.httpserver.HttpServer;

import de.documan.domain.Document;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.AiRepository;
import de.documan.service.DocumentService;

/** KI-Zuordnung: lernt aus den eigenen Dokumenten, ergänzt optional per Sprachmodell, überschreibt nichts. */
class AiAssignmentTests extends IntegrationTest {

    private static final String POWER = "Stadtwerke Musterstadt Stromrechnung Kundennummer 4711815 Verbrauch "
            + "Kilowattstunden Abschlag Zählerstand Netzentgelt Arbeitspreis Grundpreis";
    private static final String XRAY = "Radiologische Praxis Befund Röntgen Thorax Patientin Aufnahme Befundbericht "
            + "Überweisung Strahlenschutz Radiologie Diagnose";

    @Autowired
    AiRepository ai;
    @Autowired
    DocumentService service;

    private HttpServer server;
    private final AtomicReference<String> answer = new AtomicReference<>();
    private final AtomicReference<String> request = new AtomicReference<>();
    private volatile boolean rejectThinking;

    @BeforeEach
    void enableLearning() {
        properties.setAiLearning(true);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private MasterItem[] examples() {
        MasterItem town = correspondent("Stadtwerke Musterstadt");
        MasterItem invoice = documentType("Rechnung");
        MasterItem power = tag("Strom");
        MasterItem practice = correspondent("Radiologie Brühl");
        MasterItem finding = documentType("Befund");
        for (int month = 1; month <= 3; month++) {
            document("Strom " + month).content(POWER + " Monat " + month).correspondent(town).type(invoice).tag(power)
                    .create();
            document("Befund " + month).content(XRAY + " Termin " + month).correspondent(practice).type(finding)
                    .create();
        }
        return new MasterItem[] {town, invoice, power, practice, finding};
    }

    @Test
    void similarDocumentsTeachSenderTypeAndTags() {
        MasterItem[] known = examples();
        Document fresh = document("scan").content(POWER + " Monat 4 Nachzahlung").create();

        aiAssignment.afterProcessing(fresh.id());

        Document assigned = reload(fresh);
        assertThat(assigned.correspondentId()).isEqualTo(known[0].id());
        assertThat(assigned.documentTypeId()).isEqualTo(known[1].id());
        assertThat(assigned.tags()).extracting(MasterItem::name).containsExactly("Strom");
        assertThat(ai.aiFields(fresh.id())).containsExactlyInAnyOrder(AiRepository.CORRESPONDENT, AiRepository.TYPE);
        assertThat(ai.aiTagIds(fresh.id())).containsExactly(known[2].id());
        assertThat(assigned.title()).startsWith("Stadtwerke Musterstadt_Rechnung_");
        assertThat(ai.status().assigned()).isEqualTo(3);
        assertThat(ai.status().learnedDocuments()).isEqualTo(6);
    }

    @Test
    void unknownTextStaysEmpty() {
        examples();
        Document fresh = document("scan").content("Einladung zum Sommerfest des Kleingartenvereins mit Grillen")
                .create();
        aiAssignment.afterProcessing(fresh.id());
        Document untouched = reload(fresh);
        assertThat(untouched.correspondentId()).isNull();
        assertThat(untouched.documentTypeId()).isNull();
        assertThat(untouched.tags()).isEmpty();
        assertThat(ai.aiFields(fresh.id())).isEmpty();
    }

    @Test
    void setValuesAreNeverReplaced() {
        examples();
        MasterItem other = correspondent("Andere Stadtwerke");
        Document fresh = document("scan").content(POWER).correspondent(other).create();
        aiAssignment.afterProcessing(fresh.id());
        assertThat(reload(fresh).correspondentId()).isEqualTo(other.id());
        assertThat(ai.aiFields(fresh.id())).doesNotContain(AiRepository.CORRESPONDENT);
    }

    @Test
    void savingConfirmsAndOnlyConfirmedValuesTeach() {
        examples();
        Document fresh = document("scan").content(POWER + " Monat 4").create();
        aiAssignment.afterProcessing(fresh.id());
        assertThat(ai.trainingRows()).filteredOn(row -> row.id() == fresh.id())
                .allSatisfy(row -> assertThat(row.correspondentId()).isNull());

        Document assigned = reload(fresh);
        service.update(assigned.id(), assigned.title(), assigned.correspondentName(), assigned.documentTypeName(),
                assigned.documentDate(), "", "", assigned.tags().stream().map(MasterItem::id).toList(), "");
        assertThat(ai.aiFields(fresh.id())).isEmpty();
        assertThat(ai.aiTagIds(fresh.id())).isEmpty();
        assertThat(ai.trainingRows()).filteredOn(row -> row.id() == fresh.id())
                .allSatisfy(row -> assertThat(row.correspondentId()).isEqualTo(assigned.correspondentId()));
    }

    @Test
    void withLearningOffNothingHappens() {
        examples();
        properties.setAiLearning(false);
        Document fresh = document("scan").content(POWER).create();
        aiAssignment.afterProcessing(fresh.id());
        assertThat(reload(fresh).correspondentId()).isNull();
    }

    private void ollama(String json) throws IOException {
        answer.set(json);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (rejectThinking && request.get().contains("\"think\"")) {
                byte[] refusal = "{\"error\":\"\\\"qwen2.5:3b\\\" does not support thinking\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, refusal.length);
                exchange.getResponseBody().write(refusal);
                exchange.close();
                return;
            }
            String content = answer.get().replace("\\", "\\\\").replace("\"", "\\\"");
            byte[] body = ("{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},\"done\":true}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        properties.setOllamaUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void theLanguageModelFillsWhatIsStillMissing() throws IOException {
        properties.setAiLearning(false);
        documentType("Rechnung");
        tag("Strom");
        MasterItem bank = subcategory(category("Finanzen"), "Bank");
        ollama("{\"absender\":\"Neue Firma GmbH\",\"dokumenttyp\":\"rechnung\",\"kategorie\":\"Finanzen / Bank\","
                + "\"tags\":[\"Strom\",\"Erfunden\"],\"datum\":\"2026-03-14\"}");
        Document fresh = document("scan").content("Rechnung der Neue Firma GmbH vom 14.03.2026").create();

        aiAssignment.afterProcessing(fresh.id());
        assertThat(aiAssignment.languageModelNext()).isTrue();

        Document assigned = reload(fresh);
        assertThat(assigned.correspondentName()).isEqualTo("Neue Firma GmbH");
        assertThat(assigned.documentTypeName()).isEqualTo("Rechnung");
        assertThat(assigned.ownCategoryId()).isEqualTo(bank.id());
        assertThat(assigned.tags()).extracting(MasterItem::name).containsExactly("Strom");
        assertThat(assigned.documentDate()).isEqualTo(LocalDate.of(2026, 3, 14));
        assertThat(ai.aiFields(fresh.id())).containsExactlyInAnyOrder(AiRepository.CORRESPONDENT, AiRepository.TYPE,
                AiRepository.CATEGORY, AiRepository.DATE);
        assertThat(master.all(MasterKind.TAG)).extracting(MasterItem::name).doesNotContain("Erfunden");
        assertThat(request.get()).contains("Rechnung der Neue Firma GmbH").contains("Finanzen / Bank");
        assertThat(ai.status().modelOkAt()).isNotNull();
        assertThat(aiAssignment.languageModelNext()).isFalse();
    }

    @Test
    void theLanguageModelSeesHowSimilarDocumentsWereFiled() throws IOException {
        examples();
        ollama("{\"absender\":\"\",\"dokumenttyp\":\"\",\"kategorie\":\"\",\"tags\":[],\"datum\":\"2026-04-01\"}");
        Document fresh = document("scan").content(POWER + " Monat 4 Nachzahlung").create();

        aiAssignment.afterProcessing(fresh.id());
        assertThat(aiAssignment.languageModelNext()).isTrue();

        assertThat(request.get()).contains("Beispiele, so sind ähnliche Dokumente in diesem Archiv abgelegt")
                .contains("absender=Stadtwerke Musterstadt; dokumenttyp=Rechnung").contains("tags=Strom")
                .doesNotContain("absender=Radiologie Brühl")
                .contains("\"num_ctx\":8192").contains("\"think\":false");
        assertThat(reload(fresh).documentDate()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void withoutLearningThereAreNoExamples() throws IOException {
        examples();
        properties.setAiLearning(false);
        ollama("{\"absender\":\"\",\"dokumenttyp\":\"\",\"kategorie\":\"\",\"tags\":[],\"datum\":\"\"}");
        Document fresh = document("scan").content(POWER).create();
        aiAssignment.afterProcessing(fresh.id());
        assertThat(aiAssignment.languageModelNext()).isTrue();
        assertThat(request.get()).doesNotContain("Beispiele, so sind");
    }

    @Test
    void reasoningBeforeTheAnswerIsIgnored() throws IOException {
        properties.setAiLearning(false);
        documentType("Rechnung");
        ollama("<think>Das ist wohl eine Rechnung.</think> ```json {\"absender\":\"\",\"dokumenttyp\":\"Rechnung\","
                + "\"kategorie\":\"\",\"tags\":[],\"datum\":\"\"} ```");
        Document fresh = document("scan").content("Rechnung Nummer 17").create();
        aiAssignment.afterProcessing(fresh.id());
        assertThat(aiAssignment.languageModelNext()).isTrue();
        assertThat(reload(fresh).documentTypeName()).isEqualTo("Rechnung");
    }

    @Test
    void anOllamaThatRefusesThinkIsAskedAgainWithoutIt() throws IOException {
        properties.setAiLearning(false);
        documentType("Rechnung");
        rejectThinking = true;
        ollama("{\"absender\":\"\",\"dokumenttyp\":\"Rechnung\",\"kategorie\":\"\",\"tags\":[],\"datum\":\"\"}");
        Document fresh = document("scan").content("Rechnung Nummer 18").create();
        aiAssignment.afterProcessing(fresh.id());
        assertThat(aiAssignment.languageModelNext()).isTrue();
        assertThat(reload(fresh).documentTypeName()).isEqualTo("Rechnung");
        assertThat(request.get()).doesNotContain("\"think\"");
        assertThat(ai.status().modelError()).isNullOrEmpty();
    }

    @Test
    void withoutOllamaNothingWaitsForTheLanguageModel() {
        Document waiting = document("Wartet").create();
        ai.requestLanguageModel(waiting.id());
        assertThat(ai.analysis(waiting.id())).isEqualTo(AiRepository.Analysis.WAITING);

        assertThat(aiAssignment.languageModelNext()).isFalse();
        assertThat(ai.analysis(waiting.id())).isEqualTo(AiRepository.Analysis.NONE);
        assertThat(ai.waitingForLanguageModel()).isZero();
    }

    @Test
    void anUnreachableModelIsRetriedLater() {
        properties.setAiLearning(false);
        properties.setOllamaUrl("http://127.0.0.1:9");
        Document fresh = document("scan").content("Irgendein Brief").create();
        aiAssignment.afterProcessing(fresh.id());

        assertThat(aiAssignment.languageModelNext()).isFalse();
        assertThat(ai.status().modelError()).contains("nicht erreichbar");
        assertThat(ai.waitingForLanguageModel()).isEqualTo(1);
    }

    @Test
    void invalidAnswersDoNotBlockTheQueue() throws IOException {
        properties.setAiLearning(false);
        ollama("kein json");
        Document fresh = document("scan").content("Irgendein Brief").create();
        aiAssignment.afterProcessing(fresh.id());
        assertThat(aiAssignment.languageModelNext()).isTrue();
        assertThat(ai.waitingForLanguageModel()).isZero();
        assertThat(reload(fresh).correspondentId()).isNull();
    }
}
