package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import de.documan.domain.Algorithm;
import de.documan.domain.Document;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.service.DateFinder;
import de.documan.service.Matching;
import de.documan.service.PipelineService;

class RulesTests extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

    @Autowired
    PipelineService pipeline;

    private static LocalDate date(String text) {
        return DateFinder.find(text, TODAY);
    }

    @Test
    void germanNumericDate() {
        assertThat(date("Köln, 14.03.2025")).isEqualTo("2025-03-14");
    }

    @Test
    void twoDigitYear() {
        assertThat(date("Beleg 14.03.25")).isEqualTo("2025-03-14");
    }

    @Test
    void writtenMonth() {
        assertThat(date("Berlin, den 3. März 2024")).isEqualTo("2024-03-03");
    }

    @Test
    void isoDate() {
        assertThat(date("Export 2024-02-29")).isEqualTo("2024-02-29");
    }

    @Test
    void prefersLabelledDateOverEarlierOne() {
        assertThat(date("Lieferung 01.02.2025\nRechnungsdatum: 15.02.2025")).isEqualTo("2025-02-15");
    }

    @Test
    void ignoresBirthDateAndDueDate() {
        assertThat(date("geb. 01.01.1980\nZahlbar bis 01.06.2026\nStand 12.05.2026")).isEqualTo("2026-05-12");
    }

    @Test
    void ignoresFutureAndImpossibleDates() {
        assertThat(date("31.02.2025 und 01.01.2030")).isNull();
    }

    @Test
    void noDate() {
        assertThat(date("Kein Datum enthalten, nur 12345.")).isNull();
    }

    private static MasterItem rule(Algorithm algorithm, String match, boolean ignoreCase) {
        return new MasterItem(0, "x", algorithm, match, ignoreCase, null, 0);
    }

    private static MasterItem rule(Algorithm algorithm, String match) {
        return rule(algorithm, match, true);
    }

    private static OptionalInt position(MasterItem rule, String text) {
        return Matching.matchPosition(rule, text);
    }

    @Test
    void anyWordUsesWordBoundaries() {
        MasterItem rule = rule(Algorithm.ANY, "Bank Sparkasse");
        assertThat(position(rule, "Ihre bank informiert")).isPresent();
        assertThat(position(rule, "Das Bankett beginnt")).isEmpty();
    }

    @Test
    void allWordsNeedEveryTerm() {
        MasterItem rule = rule(Algorithm.ALL, "Strom Rechnung");
        assertThat(position(rule, "Rechnung für Strom")).isPresent();
        assertThat(position(rule, "Rechnung für Gas")).isEmpty();
    }

    @Test
    void quotedPhraseIsOneTerm() {
        MasterItem rule = rule(Algorithm.ANY, "\"Deutsche Bank\"");
        assertThat(position(rule, "Deutsche Bank AG")).isPresent();
        assertThat(position(rule, "Bank in Deutschland")).isEmpty();
    }

    @Test
    void literal() {
        assertThat(position(rule(Algorithm.LITERAL, "Kd.-Nr. 123"), "Ihre Kd.-Nr. 123 bitte")).isPresent();
    }

    @Test
    void regexAndInvalidRegex() {
        assertThat(position(rule(Algorithm.REGEX, "R-\\d+"), "R-42")).isPresent();
        assertThat(position(rule(Algorithm.REGEX, "("), "R-42")).isEmpty();
    }

    @Test
    void caseSensitive() {
        assertThat(position(rule(Algorithm.ANY, "Telekom", false), "TELEKOM")).isEmpty();
    }

    @Test
    void umlautsCountAsWordCharacters() {
        assertThat(position(rule(Algorithm.ANY, "Bank"), "Die Bänke und die Bankä")).isEmpty();
        assertThat(position(rule(Algorithm.ANY, "MÜLLER"), "Herr müller")).isPresent();
    }

    @Test
    void noneAndEmptyNeverMatch() {
        assertThat(position(rule(Algorithm.NONE, "x"), "x")).isEmpty();
        assertThat(position(rule(Algorithm.ANY, "  "), "x")).isEmpty();
    }

    private long ruleFor(MasterKind kind, String name, String match) {
        return master.insert(kind, name, Algorithm.ANY, match, true, MasterItem.DEFAULT_COLOR);
    }

    @Test
    void earliestCorrespondentWins() {
        ruleFor(MasterKind.CORRESPONDENT, "Bank", "Sparkasse");
        long sender = ruleFor(MasterKind.CORRESPONDENT, "Stadtwerke", "Stadtwerke");
        Document document = document("Dokument").create();
        var assignment = pipeline.assign(document, "Stadtwerke Köln\n\nBitte an die Sparkasse zahlen.");
        assertThat(assignment.correspondentId()).isEqualTo(sender);
    }

    @Test
    void keepsManualValuesAndAddsTags() {
        MasterItem manual = correspondent("Manuell");
        ruleFor(MasterKind.CORRESPONDENT, "Regel", "Rechnung");
        long invoice = ruleFor(MasterKind.DOCUMENT_TYPE, "Rechnung", "Rechnung");
        long tag = ruleFor(MasterKind.TAG, "Finanzen", "Rechnung");
        Document document = document("Dokument").correspondent(manual).create();
        var assignment = pipeline.assign(document, "Rechnung Nr. 1");
        assertThat(assignment.correspondentId()).isNull();
        assertThat(assignment.documentTypeId()).isEqualTo(invoice);
        assertThat(assignment.tagIds()).isEqualTo(List.of(tag));
    }
}
