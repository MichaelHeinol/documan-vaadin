package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import de.documan.domain.Document;
import de.documan.domain.MailHeaders;
import de.documan.repo.Selection;
import de.documan.repo.UserRepository;
import de.documan.service.DashboardService;
import de.documan.service.DashboardService.Dashboard;
import de.documan.service.DashboardService.Pie;
import de.documan.service.DashboardService.Slice;
import de.documan.service.Filters;
import de.documan.service.Filters.Facet;
import de.documan.service.Filters.Option;
import de.documan.service.SearchService;

/** „Eingespielt von“, E-Mail-Metadaten und die Tortengrafik der Übersicht. */
class OriginTests extends IntegrationTest {

    @Autowired
    UserRepository users;
    @Autowired
    SearchService search;
    @Autowired
    DataSource dataSource;

    private Document addedBy(String title, String value) {
        Document document = document(title).create();
        jdbc.sql("UPDATE document SET added_by = ? WHERE id = ?").params(value, document.id()).update();
        return reload(document);
    }

    private Document mail(String title, String from, String subject) {
        Document document = document(title).create();
        jdbc.sql("""
                UPDATE document SET mail_from = ?, mail_to = 'konto@example.de', mail_subject = ?,
                    mail_date = '2026-10-31T09:00:00Z', mail_message_id = '<1@example.org>' WHERE id = ?
                """).params(from, subject, document.id()).update();
        reindexer.reindex(document.id());
        return reload(document);
    }

    private static Map<String, Long> counts(Object... pairs) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            counts.put((String) pairs[index], ((Number) pairs[index + 1]).longValue());
        }
        return counts;
    }

    private static List<String> titles(MvcResult result) {
        return OriginTests.<List<Document>>model(result, "documents").stream().map(Document::title).toList();
    }

    private static Facet adderFacet(MvcResult result) {
        return OriginTests.<List<Facet>>model(result, "facets").stream()
                .filter(facet -> facet.label().equals("Eingespielt von")).findFirst().orElseThrow();
    }

    // Eingespielt von








    @Test
    void valueIsNotSearchable() {
        addedBy("Datei", "Zauberwort");
        reindexer.reindex(jdbc.sql("SELECT id FROM document").query(Long.class).single());
        assertThat(search.search(Selection.ALL, "Zauberwort").selection().params().getFirst())
                .isEqualTo(new Long[0]);
    }

    // E-Mail

    @Test
    void searchFindsSenderAndSubject() {
        Document document = mail("Datei", "rechnung@stadtwerke-musterstadt.de", "Abschlag Oktober");
        document("Andere").create();

        for (String text : List.of("Abschlag", "rechnung@stadtwerke-musterstadt.de", "musterstadt")) {
            assertThat(search.search(Selection.ALL, text).selection().params().getFirst()).as(text)
                    .isEqualTo(new Long[] {document.id()});
        }
        String vector = jdbc.sql("SELECT search_vector::text FROM document WHERE id = ?")
                .param(document.id()).query(String.class).single();
        assertThat(vector).containsPattern("'abschlag':\\d+B");
    }

    @Test
    void searchFindsAddressesInAngleBrackets() {
        Document document = mail("Datei", "Versorger <info@altwerke.example>", "Abschlag");
        assertThat(search.search(Selection.ALL, "altwerke").selection().params().getFirst())
                .isEqualTo(new Long[] {document.id()});
    }


    @Test
    void headerLengthsAreLimited() throws Exception {
        var options = new de.documan.service.IngestService.Options(null, "", "x".repeat(200),
                new MailHeaders("f".repeat(600), "t".repeat(1200), "s".repeat(600), Instant.EPOCH, "m".repeat(600)),
                null, null, List.of(), List.of());
        Document document = ingest.ingest(new org.springframework.core.io.ByteArrayResource(Samples.textPdf("Lang")),
                "lang.pdf", de.documan.domain.DocumentSource.MAIL, options);
        assertThat(document.addedBy()).hasSize(150);
        assertThat(document.mail().from()).hasSize(500);
        assertThat(document.mail().to()).hasSize(1000);
        assertThat(document.mail().subject()).hasSize(500);
        assertThat(document.mail().messageId()).hasSize(500);
    }

    // Altbestand

    @Test
    void migrationFillsExistingDocuments() {
        var flyway = Flyway.configure().dataSource(dataSource).schemas("altbestand").locations("classpath:db/migration");
        try {
            flyway.target("2").load().migrate();
            String insert = """
                    INSERT INTO altbestand.document (title, checksum, original, original_filename, mime_type, source,
                                                     source_detail, search_vector)
                    VALUES (?, ?, 'o.pdf', 'o.pdf', 'application/pdf', ?, ?, NULL)
                    """;
            jdbc.sql(insert).params("Scan", "1".repeat(64), "consume", "sub/scan.pdf").update();
            jdbc.sql(insert).params("Upload", "2".repeat(64), "upload", "").update();
            jdbc.sql(insert).params("Mail", "3".repeat(64), "mail", "anna@example.org: Re: Rechnung").update();

            flyway.target("latest").load().migrate();

            var rows = jdbc.sql("""
                    SELECT added_by, mail_from, mail_subject, search_vector::text AS vector
                    FROM altbestand.document ORDER BY title
                    """).query().listOfRows();
            assertThat(rows.get(0)).containsEntry("added_by", "E-Mail").containsEntry("mail_from", "anna@example.org")
                    .containsEntry("mail_subject", "Re: Rechnung");
            assertThat((String) rows.get(0).get("vector")).contains("'rechnung'").contains("'exampl'");
            assertThat(rows.get(1)).containsEntry("added_by", "Eingangsordner").containsEntry("mail_from", "");
            assertThat(rows.get(2)).containsEntry("added_by", "").containsEntry("mail_subject", "");
        } finally {
            jdbc.sql("DROP SCHEMA altbestand CASCADE").update();
        }
    }

    // Tortengrafik

    @Test
    void wallsCoverExactlyTheFrontOfThePie() {
        Pie pie = DashboardService.buildPie(counts("a", 2, "b", 1, "c", 1));

        assertThat(pie.total()).isEqualTo(4);
        assertThat(pie.slices()).extracting(Slice::percent).containsExactly(50L, 25L, 25L);
        Slice half = pie.slices().get(0);
        Slice second = pie.slices().get(1);
        Slice third = pie.slices().get(2);
        assertThat(half.top()).isEqualTo("M 160 80 L 160.00 10.00 A 140 70 0 0 1 160.00 150.00 Z");
        assertThat(half.wall())
                .isEqualTo("M 300.00 80.00 A 140 70 0 0 1 160.00 150.00 L 160.00 180.00 A 140 70 0 0 0 300.00 110.00 Z");
        assertThat(second.top()).isEqualTo("M 160 80 L 160.00 150.00 A 140 70 0 0 1 20.00 80.00 Z");
        assertThat(second.wall())
                .isEqualTo("M 160.00 150.00 A 140 70 0 0 1 20.00 80.00 L 20.00 110.00 A 140 70 0 0 0 160.00 180.00 Z");
        assertThat(third.top()).isEqualTo("M 160 80 L 20.00 80.00 A 140 70 0 0 1 160.00 10.00 Z");
        assertThat(third.wall()).isEmpty();
    }

    @Test
    void largeSliceUsesTheLargeArc() {
        Pie pie = DashboardService.buildPie(counts("a", 3, "b", 1));
        assertThat(pie.slices().getFirst().top()).contains("A 140 70 0 1 1");
        assertThat(pie.slices().get(1).top()).contains("A 140 70 0 0 1");
    }

    @Test
    void singleSliceIsTwoHalfArcs() {
        Slice slice = DashboardService.buildPie(counts("anna", 7)).slices().getFirst();
        assertThat(slice.top())
                .isEqualTo("M 20.00 80.00 A 140 70 0 1 1 300.00 80.00 A 140 70 0 1 1 20.00 80.00 Z");
        assertThat(slice.wall())
                .isEqualTo("M 300.00 80.00 A 140 70 0 0 1 20.00 80.00 L 20.00 110.00 A 140 70 0 0 0 300.00 110.00 Z");
        assertThat(slice.percent()).isEqualTo(100);
    }

    @Test
    void slicesAreRankedAndEmptyMeansUnknown() {
        Pie pie = DashboardService.buildPie(counts("zora", 3, "", 3, "anna", 3, "Max", 5));

        assertThat(pie.slices()).extracting(Slice::label).containsExactly("Max", "Unbekannt", "anna", "zora");
        assertThat(pie.slices()).extracting(Slice::number).containsExactly(1, 2, 3, 4);
        assertThat(pie.slices().get(1).url()).isEqualTo("/dokumente?added_by=none");
    }

    @Test
    void linksEncodeTheValue() {
        Slice slice = DashboardService.buildPie(counts("E-Mail: Büro & Co+", 1)).slices().getFirst();
        assertThat(slice.url()).isEqualTo("/dokumente?added_by=E-Mail%3A%20B%C3%BCro%20%26%20Co%2B");
    }

    @Test
    void moreThanEightValuesFoldIntoOthers() {
        Map<String, Long> nine = new LinkedHashMap<>();
        IntStream.rangeClosed(1, 9).forEach(number -> nine.put("wert" + number, 100L - number));

        Pie pie = DashboardService.buildPie(nine);

        assertThat(pie.slices()).hasSize(8);
        Slice others = pie.slices().getLast();
        assertThat(others.label()).isEqualTo("Weitere");
        assertThat(others.count()).isEqualTo(92 + 91);
        assertThat(others.url()).isNull();
        assertThat(pie.total()).isEqualTo(nine.values().stream().mapToLong(Long::longValue).sum());

        nine.remove("wert9");
        assertThat(DashboardService.buildPie(nine).slices()).extracting(Slice::label).doesNotContain("Weitere");
    }



}
