package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import de.documan.domain.Document;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.Selection;
import de.documan.service.DocumentService;
import de.documan.service.SearchService;

class SearchTests extends IntegrationTest {

    @Autowired
    SearchService search;
    @Autowired
    DocumentService service;

    private Document strom;
    private Document auto;
    private Document miete;

    @BeforeEach
    void documents() {
        strom = document("Stromrechnung Januar").content("Stadtwerke Köln. Ihre Abrechnung für Strom 2025.").create();
        auto = document("Kfz-Versicherung").content("Die Versicherungen für Ihr Auto. Beitrag für 2024.").create();
        miete = document("Mietvertrag").content("Wohnung in der Hauptstraße, Vermieter Müller.").create();
    }

    private List<Document> ranked(String text) {
        var outcome = search.search(Selection.ALL, text);
        String order = outcome.order() != null ? outcome.order() : "d.id";
        return documents.page(outcome.selection(), order, outcome.orderParams(), 100, 0);
    }

    private Set<Long> found(String text) {
        return ranked(text).stream().map(Document::id).collect(Collectors.toSet());
    }

    private static Set<Long> ids(Document... wanted) {
        Set<Long> ids = new HashSet<>();
        for (Document document : wanted) {
            ids.add(document.id());
        }
        return ids;
    }

    @Test
    void germanStemming() {
        assertThat(found("Versicherung")).isEqualTo(ids(auto));
    }

    @Test
    void prefixWhileTyping() {
        assertThat(found("Hauptstr")).isEqualTo(ids(miete));
    }

    @Test
    void umlautPrefix() {
        assertThat(found("Müll")).isEqualTo(ids(miete));
    }

    @Test
    void allWordsAreRequired() {
        assertThat(found("Strom Köln")).isEqualTo(ids(strom));
        assertThat(found("Strom Müller")).isEmpty();
    }

    @Test
    void compoundWordPartIsFound() {
        assertThat(found("rechnung")).isEqualTo(ids(strom));
    }

    @Test
    void compoundWordPartWithUmlautIgnoresCase() {
        Document document = document("Bescheid").content("Gebühren für die Müllabfuhr im Jahr").create();
        assertThat(found("MÜLLABF")).isEqualTo(ids(document));
        assertThat(found("abfuhr")).isEqualTo(ids(document));
    }

    @Test
    void underscoreIsLiteralInSubstringSearch() {
        Document document = document("Export").content("Datei kunden_liste_final").create();
        document("Anderes").content("kundenXliste").create();
        assertThat(found("n_liste")).isEqualTo(ids(document));
    }

    @Test
    void shortWordsStillWorkWithoutSubstringIndex() {
        Document document = document("Kurz").content("Er zahlt 17 EUR an XY").create();
        assertThat(found("XY")).isEqualTo(ids(document));
        assertThat(found("17")).isEqualTo(ids(document));
    }

    @Test
    void exactPhrase() {
        assertThat(found("\"Beitrag für 2024\"")).isEqualTo(ids(auto));
        assertThat(found("\"für Beitrag 2024\"")).isEmpty();
    }

    @Test
    void exclusion() {
        assertThat(found("Abrechnung -Mannheim")).isEqualTo(ids(strom));
        assertThat(found("Abrechnung -Köln")).isEmpty();
    }

    @Test
    void germanOr() {
        assertThat(found("Müller oder Kfz")).isEqualTo(ids(miete, auto));
    }

    @Test
    void symbolsDoNotBreakTheQuery() {
        for (String text : List.of("a & b", "(", "'", "\\", "a:*", "!x", "<>", "\"", "100%")) {
            found(text);
        }
    }

    @Test
    void onlySymbolsReturnsEverything() {
        assertThat(found("&&&")).hasSize(3);
    }

    @Test
    void titleRanksAboveBody() {
        Document body = document("Sonstiges").content("Hier steht nebenbei Mietvertrag im Text.").create();
        assertThat(ranked("Mietvertrag")).extracting(Document::id).containsExactly(miete.id(), body.id());
    }

    @Test
    void correspondentAndTagNamesAreSearchable() {
        MasterItem allianz = correspondent("Allianz");
        service.update(auto.id(), auto.title(), allianz.name(), null, null, "", "", List.of(), "");
        assertThat(found("Allianz")).isEqualTo(ids(auto));
        service.addTag(List.of(auto.id()), "Fahrzeug");
        assertThat(found("Fahrzeug")).isEqualTo(ids(auto));
    }

    @Test
    void renamingAndDeletingMasterDataUpdatesTheIndex() {
        MasterItem tag = service.addTag(List.of(auto.id()), "Altname");
        master.update(MasterKind.TAG, tag.id(), "Neuname", tag.algorithm(), "", true, tag.color());
        service.reindexMaster(MasterKind.TAG, tag.id());
        assertThat(found("Neuname")).isEqualTo(ids(auto));
        assertThat(found("Altname")).isEmpty();
        service.deleteMaster(MasterKind.TAG, tag.id());
        assertThat(found("Neuname")).isEmpty();
    }

    @Test
    void snippetHighlightsAndEscapes() {
        Document document = document("Test")
                .content("Vorher <script>alert(1)</script> Preise & Mehr Zauberwort nachher").create();
        String snippet = search.addSnippets(List.of(document), "Zauberwort").getFirst().snippet();
        assertThat(snippet).contains("<mark>Zauberwort</mark>").doesNotContain("<script>").contains("Preise &amp; Mehr");
    }

    @Test
    void veryLongTextCanBeIndexed() {
        Random random = new Random(1);
        List<String> words = IntStream.range(0, 120_000)
                .mapToObj(index -> random.ints(9, 'a', 'z' + 1)
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString())
                .toList();
        Document document = document("Riesig").content(String.join(" ", words)).create();
        assertThat(jdbc.sql("SELECT search_vector IS NOT NULL FROM document WHERE id = ?")
                .param(document.id()).query(Boolean.class).single()).isTrue();
        assertThat(found(words.get(10))).isEqualTo(ids(document));
    }
}
