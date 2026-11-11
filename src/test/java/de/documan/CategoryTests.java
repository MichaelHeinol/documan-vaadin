package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import de.documan.domain.AppUser;
import de.documan.domain.Document;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.domain.ReportChart;
import de.documan.domain.ReportSettings;
import de.documan.repo.ReportRepository;
import de.documan.repo.UserRepository;
import de.documan.service.CategoryPaths;
import de.documan.service.DailyReportService;
import de.documan.service.DashboardService;
import de.documan.service.DocumentService;
import de.documan.service.FilingService;
import de.documan.service.Filters;

/** Kategorien in bis zu zehn Stufen über den Dokumenttypen, am Typ oder direkt am Dokument. */
class CategoryTests extends IntegrationTest {

    @Autowired
    Filters filters;
    @Autowired
    FilingService filing;
    @Autowired
    DashboardService dashboard;
    @Autowired
    DailyReportService report;
    @Autowired
    ReportRepository reports;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    DocumentService service;

    private static List<String> titles(MvcResult result) {
        return CategoryTests.<List<Document>>model(result, "documents").stream().map(Document::title).toList();
    }

    private CategoryPaths paths() {
        return new CategoryPaths(master.categories());
    }

    private Document titled(String title) {
        return documents.find(jdbc.sql("SELECT id FROM document WHERE title = ?").param(title)
                .query(Long.class).single()).orElseThrow();
    }

    /**
     * Finanzen mit Rechnung, darunter Bank mit Kontoauszug. Bescheid hat keine Kategorie, „Strom“ steht
     * selbst unter Finanzen / Energie, „Lose“ hat gar keinen Typ.
     */
    private MasterItem finances() {
        MasterItem invoice = documentType("Rechnung");
        MasterItem statement = documentType("Kontoauszug");
        MasterItem notice = documentType("Bescheid");
        MasterItem town = correspondent("Stadtwerke");
        MasterItem finances = category("Finanzen", invoice);
        subcategory(finances, "Bank", statement);
        MasterItem energy = subcategory(finances, "Energie");
        Document strom = document("Strom").type(notice).correspondent(town).create();
        service.setCategory(new Long[] {strom.id()}, energy.id());
        document("Gas").type(invoice).correspondent(town).create();
        document("Oktober").type(statement).create();
        document("Grundsteuer").type(notice).create();
        document("Lose").create();
        return finances;
    }

    private void refileAll() {
        while (filing.refileNext()) {
            // Alle Dokumente der Reihe nach umziehen.
        }
    }

    private void fileAll() {
        jdbc.sql("UPDATE document SET file_sync_pending = true").update();
        refileAll();
    }

    // Stammdaten








    // Am Dokument

    @Test
    void theDocumentsOwnCategoryWinsOverItsType() {
        finances();
        assertThat(titled("Strom").categoryName()).isEqualTo("Energie");
        assertThat(titled("Grundsteuer").categoryId()).isNull();
        assertThat(titled("Oktober").categoryName()).isEqualTo("Bank");
        assertThat(titled("Oktober").ownCategoryId()).isNull();
    }




    // Ablage

    @Test
    void theCategoryLevelsAreTheTopFolders() {
        finances();
        assertThat(filing.folderOf(titled("Strom"))).isEqualTo("Finanzen/Energie");
        assertThat(filing.folderOf(titled("Oktober"))).isEqualTo("Finanzen/Bank");
        assertThat(filing.folderOf(titled("Lose"))).isEqualTo("Ohne Kategorie");
    }



    // Filter, Suche und Ordner



    @Test
    void theTreeNestsTheLevels() {
        finances();
        List<Filters.TreeNode> tree = filters.tree();
        assertThat(tree).extracting(node -> node.name() + node.count())
                .containsExactly("Finanzen3", "Ohne Kategorie2");
        Filters.TreeNode top = tree.getFirst();
        assertThat(top.children()).extracting(node -> node.name() + node.count())
                .containsExactly("Bank1", "Energie1");
        // Wie die Ablage: nur Kategoriestufen, Dokumenttyp und Absendername bekommen keine eigenen Ordner.
        assertThat(top.query()).isNotNull();
        assertThat(top.children()).allSatisfy(child -> {
            assertThat(child.children()).isEmpty();
            assertThat(child.category()).isTrue();
        });
    }


    // Übersicht und Mail


    @Test
    void theMailCountsTheTopLevel() {
        finances();
        AppUser chef = users.find(users.insert("chef", encoder.encode("x"), true, true, "chef@example.de"))
                .orElseThrow();
        String text = report.build(chef, Instant.now()).text();
        assertThat(text).contains("Dokumente nach Kategorie\n– Finanzen: 3\n– Ohne Kategorie: 2");

        ReportSettings base = reports.load();
        reports.save(new ReportSettings(base.enabled(), base.time(), base.weekdays(), base.sections(),
                base.sourcesChart(), base.typesChart(), base.storageChart(), ReportChart.PIE, base.extraRecipients(),
                base.excluded(), base.sentOn()));
        DailyReportService.Report pie = report.build(chef, Instant.now());
        assertThat(pie.text()).contains("– Finanzen: 3 (60,0 %)").contains("– Ohne Kategorie: 2 (40,0 %)");
        assertThat(pie.images()).containsKey("documan-categories");
    }

    @Test
    void withoutCategoriesTheMailSaysSo() {
        AppUser chef = users.find(users.insert("chef", encoder.encode("x"), true, true, "chef@example.de"))
                .orElseThrow();
        assertThat(report.build(chef, Instant.now()).text())
                .contains("Dokumente nach Kategorie\n– noch keine Kategorien vergeben");
    }
}
