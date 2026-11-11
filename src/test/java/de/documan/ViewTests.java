package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import de.documan.domain.Algorithm;
import de.documan.domain.Document;
import de.documan.domain.DocumentStatus;
import de.documan.domain.MailAccount;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.MailRepository;
import de.documan.repo.UserRepository;
import de.documan.service.DashboardService;
import de.documan.service.DashboardService.Chart;
import de.documan.service.DashboardService.Dashboard;
import de.documan.service.DashboardService.Stat;
import de.documan.service.Filters;
import de.documan.service.SecretBox;

class ViewTests extends IntegrationTest {

    @Autowired
    DashboardService dashboard;
    @Autowired
    de.documan.service.DocumentService service;
    @Autowired
    Filters filters;
    @Autowired
    MailRepository mail;
    @Autowired
    SecretBox secrets;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    private static List<String> titles(MvcResult result) {
        return ViewTests.<List<Document>>model(result, "documents").stream().map(Document::title).toList();
    }

    // Zugriff







    // Übersicht

    private Dashboard board() {
        return dashboard.build();
    }

    private Map<String, String> stats() {
        return board().stats().stream().collect(Collectors.toMap(Stat::label, Stat::value));
    }


    @Test
    void keyFigures() {
        document("A").pages(3).create();
        Document second = document("B").pages(1200).filed().status(DocumentStatus.FAILED).create();
        assertThat(second.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(stats()).containsEntry("Dokumente", "2").containsEntry("Im Eingang", "1")
                .containsEntry("Seiten", "1.203");
        assertThat(board().summary().failed()).isEqualTo(1);
    }

    @Test
    void severalTagsDoNotInflateTheFigures() {
        document("Mehrfach").pages(10).tag(tag("Eins")).tag(tag("Zwei")).create();
        document("Ohne Tag").pages(1).create();
        assertThat(stats()).containsEntry("Dokumente", "2").containsEntry("Im Eingang", "2")
                .containsEntry("Seiten", "11");
        assertThat(board().categories().get(2).without()).isEqualTo(1);
    }

    @Test
    void categoriesAreRankedAndCountUnassigned() {
        MasterItem big = correspondent("Viel");
        MasterItem small = correspondent("Wenig");
        correspondent("Nichts");
        document("1").correspondent(big).create();
        document("2").correspondent(big).create();
        document("3").correspondent(small).create();
        document("4").create();
        var category = board().categories().get(1);
        assertThat(category.title()).isEqualTo("Absendernamen");
        assertThat(category.rows()).extracting(row -> row.label() + row.count() + "/" + row.share())
                .containsExactly("Viel2/100", "Wenig1/50");
        assertThat(category.total()).isEqualTo(3);
        assertThat(category.without()).isEqualTo(1);
        assertThat(category.rows().getFirst().url()).isEqualTo("/dokumente?correspondent=" + big.id());
    }


    @Test
    void chartCoversTwelveMonthsWithRoundScale() {
        for (String date : List.of("2026-10-02", "2026-10-03", "2026-10-04", "2025-11-30", "2025-10-31", "2026-11-01")) {
            document("Dokument").date(date).create();
        }
        Chart chart = dashboard.buildChart(LocalDate.of(2026, 10, 15));
        assertThat(chart.columns()).hasSize(12);
        assertThat(chart.columns().getFirst().name()).isEqualTo("November 2025");
        assertThat(chart.columns().getFirst().count()).isEqualTo(1);
        assertThat(chart.columns().getLast().count()).isEqualTo(3);
        assertThat(chart.total()).isEqualTo(4);
        assertThat(chart.ticks()).extracting(tick -> tick.value()).containsExactly(5, 0);
        assertThat(chart.columns().getLast().height()).isEqualTo(60.0);
        assertThat(chart.columns().stream().filter(column -> column.peak())).hasSize(1);
    }

    @Test
    void niceCeiling() {
        Map<Integer, Integer> values = Map.of(0, 1, 1, 1, 2, 2, 3, 5, 7, 10, 37, 50, 120, 200);
        values.forEach((value, expected) -> assertThat(DashboardService.niceCeiling(value)).isEqualTo(expected));
    }

    @Test
    void inboxPreviewStartsWithOldestFinishedDocument() {
        document("Älter").create();
        document("Neuer").create();
        document("Wartet").status(DocumentStatus.PENDING).create();
        assertThat(board().inboxDocuments()).extracting(Document::title).containsExactly("Älter", "Neuer");
    }



    // Liste










    // Ordner

    private List<Filters.TreeNode> treeOfSixDocuments() {
        MasterItem invoice = documentType("Rechnung");
        MasterItem contract = documentType("Bescheid");
        MasterItem town = correspondent("Stadtwerke");
        MasterItem chamber = correspondent("Ärztekammer");
        document("Strom").type(invoice).correspondent(town).create();
        document("Gas").type(invoice).correspondent(town).create();
        document("Beitrag").type(invoice).correspondent(chamber).create();
        document("Ohne Absender").type(contract).create();
        document("Lose").correspondent(town).create();
        document("Ganz lose").create();
        return filters.tree(Filters.TreeOrder.TYPE);
    }

    private MvcResult folder(Filters.TreeNode node, int position) throws Exception {
        return page("/ordner/inhalt?" + node.children().get(position).query());
    }

    @Test
    void typesContainCorrespondentsWithCounts() {
        List<String> summary = treeOfSixDocuments().stream()
                .map(node -> node.name() + node.count() + node.children().stream()
                        .map(folder -> folder.name() + folder.count()).toList())
                .toList();
        assertThat(summary).containsExactly("Bescheid1[Ohne Absendername1]",
                "Rechnung3[Ärztekammer1, Stadtwerke2]", "Ohne Dokumenttyp2[Stadtwerke1, Ohne Absendername1]");
    }

    @Test
    void correspondentsContainTypesWithCounts() {
        treeOfSixDocuments();
        List<String> summary = filters.tree(Filters.TreeOrder.CORRESPONDENT).stream()
                .map(node -> node.name() + node.count() + node.children().stream()
                        .map(folder -> folder.name() + folder.count()).toList())
                .toList();
        assertThat(summary).containsExactly("Ärztekammer1[Rechnung1]", "Stadtwerke3[Rechnung2, Ohne Dokumenttyp1]",
                "Ohne Absendername2[Bescheid1, Ohne Dokumenttyp1]");
    }

    @Test
    void withoutCategoriesEverythingLiesInOneFolder() {
        treeOfSixDocuments();
        assertThat(filters.tree()).extracting(node -> node.name() + node.count() + node.children().size())
                .containsExactly("Ohne Kategorie60");
    }







    // Hochladen




    // Detailseite

    private String detail(Document document) {
        return "/dokumente/" + document.id();
    }

    /** Setzt den Absendernamen wie die Detailseite, die übrigen Angaben bleiben. */
    private void rename(Document document, String correspondentName) {
        service.update(document.id(), document.title(), correspondentName, document.documentTypeName(),
                document.documentDate(), document.addedBy(), document.notes(),
                document.tags().stream().map(MasterItem::id).toList(), "");
    }

    private MvcResult save(Document document, String... fields) throws Exception {
        String[] all = new String[fields.length + 2];
        all[0] = "title";
        all[1] = document.title();
        System.arraycopy(fields, 0, all, 2, fields.length);
        return submit(detail(document), all);
    }


    @Test
    void masterDataNamesAreCaseInsensitive() throws Exception {
        MasterItem existing = correspondent("Telekom");
        Document document = document("Datei").stored();
        rename(document, "TELEKOM");
        assertThat(reload(document).correspondentId()).isEqualTo(existing.id());
        assertThat(master.count(MasterKind.CORRESPONDENT)).isEqualTo(1);
    }

    @Test
    void emptyNameClearsCorrespondent() throws Exception {
        Document document = document("Datei").correspondent(correspondent("Alt")).stored();
        rename(document, "");
        assertThat(reload(document).correspondentId()).isNull();
    }













    // Stapel

    private MvcResult bulk(List<Document> selected, String action, String value, String... extra) throws Exception {
        var request = post("/stapel").with(action.equals("delete") ? CHEF : ANNA).with(csrf()).param("action", action).param("value", value);
        for (Document document : selected) {
            request = request.param("ids", String.valueOf(document.id()));
        }
        for (int index = 0; index < extra.length; index += 2) {
            request = request.param(extra[index], extra[index + 1]);
        }
        return mvc.perform(request).andReturn();
    }





    // Stammdaten








    // Postfächer

    private static final String[] MAIL_FIELDS = {"name", "Rechnungen", "enabled", "on", "host", "imap.example.org",
        "port", "993", "security", "ssl", "username", "u", "folder", "INBOX", "maximumAgeDays", "30", "attachments",
        "pdf", "afterImport", "seen"};

    private static String[] mailFields(String... extra) {
        String[] fields = java.util.Arrays.copyOf(MAIL_FIELDS, MAIL_FIELDS.length + extra.length);
        System.arraycopy(extra, 0, fields, MAIL_FIELDS.length, extra.length);
        return fields;
    }

    private MailAccount onlyAccount() {
        return mail.all().getFirst();
    }






    // Benutzer






    private org.springframework.mock.web.MockHttpSession loggedIn(String username, String password) throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        mvc.perform(post("/login").session(session).with(csrf()).param("username", username)
                .param("password", password)).andReturn();
        assertThat(status(mvc.perform(get("/status").session(session)).andReturn())).isEqualTo(200);
        return session;
    }






    private String stamp() {
        return documents.changeStamp();
    }

    @Test
    void stampStaysWhileNothingHappens() throws Exception {
        document("Rechnung").create();
        assertThat(stamp()).isEqualTo(stamp());
    }

    @Test
    void stampChangesWithDocumentsAndMasterData() throws Exception {
        String stamp = stamp();
        Document document = document("Rechnung").create();
        assertThat(stamp()).isNotEqualTo(stamp);
        stamp = stamp();
        jdbc.sql("UPDATE document SET status = 'failed' WHERE id = ?").param(document.id()).update();
        assertThat(stamp()).isNotEqualTo(stamp);
        stamp = stamp();
        tag("Steuer");
        assertThat(stamp()).isNotEqualTo(stamp);
        stamp = stamp();
        jdbc.sql("DELETE FROM document WHERE id = ?").param(document.id()).update();
        assertThat(stamp()).isNotEqualTo(stamp);
    }



    @Test
    void generatedFileNamesStayInsideTheMediaFolder() throws Exception {
        Document document = add(Samples.textPdf("Name"), "..\\..\\böse datei.pdf");
        assertThat(Path.of(document.original()).getNameCount()).isEqualTo(4);
        assertThat(Files.exists(mediaRoot.resolve(document.original()))).isTrue();
    }
}
