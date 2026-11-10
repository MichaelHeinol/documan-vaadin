package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import de.documan.domain.BackupRun;
import de.documan.domain.Document;
import de.documan.domain.DocumentStatus;
import de.documan.domain.MailAccount;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.domain.RunKind;
import de.documan.domain.RunStatus;
import de.documan.repo.BackupRepository;
import de.documan.repo.MailRepository;
import de.documan.repo.Selection;
import de.documan.repo.UserRepository;
import de.documan.service.FilingService;
import de.documan.service.SearchService;
import de.documan.service.backup.BackupService;
import de.documan.service.backup.MigrationService;
import tools.jackson.databind.json.JsonMapper;

/** Spielt ein Archiv ein, das die Django-Version wirklich geschrieben hat. */
class DjangoArchiveTests extends IntegrationTest {

    private static final String ARCHIVE = "documan-umzug-20261101-014907-django.zip";
    private static final String UMLAUT_ORIGINAL = "originals/2026/03/Stadtwerke Müller & Söhne - scan-strom.pdf";

    @Autowired
    BackupService backup;
    @Autowired
    BackupRepository runs;
    @Autowired
    UserRepository users;
    @Autowired
    MailRepository mail;
    @Autowired
    FilingService filing;
    @Autowired
    SearchService search;
    @Autowired
    MigrationService migration;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void placeArchive() throws Exception {
        try (InputStream source = getClass().getResourceAsStream("/umzug/" + ARCHIVE)) {
            Files.copy(source, backupRoot.resolve(ARCHIVE));
        }
    }

    private BackupRun importIt() {
        runs.enqueue(RunKind.IMPORT, "", ARCHIVE, false);
        assertThat(backup.runNext(null)).isTrue();
        BackupRun result = runs.history(1).getFirst();
        assertThat(result.status()).as(result.error()).isEqualTo(RunStatus.DONE);
        return result;
    }

    private List<Document> allDocuments() {
        return documents.page(Selection.ALL, "d.id", List.of(), 10, 0).stream().map(item -> reload(item.id())).toList();
    }

    @Test
    void isListedAsDjangoArchive() {
        var archive = migration.listArchives().getFirst();
        assertThat(archive.sourceLabel()).isEqualTo("Django");
        assertThat(archive.documents()).isEqualTo(3);
        assertThat(archive.problem()).isEmpty();
    }

    @Test
    void documentsArriveWithAllTheirFields() {
        BackupRun result = importIt();
        assertThat(result.documents()).isEqualTo(3);
        assertThat(result.files()).isEqualTo(7);

        List<Document> imported = allDocuments();
        assertThat(imported).extracting(Document::title).containsExactly("scan-strom", "Mobilfunk Mai", "quittung");

        Document strom = imported.get(0);
        assertThat(strom.correspondentName()).isEqualTo("Stadtwerke Müller & Söhne");
        assertThat(strom.tags()).extracting(MasterItem::name).containsExactly("Steuer", "Wichtig");
        assertThat(strom.tags().getFirst().color()).isEqualTo("#aa3355");
        assertThat(strom.documentDate()).isEqualTo("2026-03-14");
        assertThat(strom.original()).isEqualTo(UMLAUT_ORIGINAL);
        assertThat(strom.hasArchive()).isFalse();
        assertThat(strom.inbox()).isTrue();

        Document mobile = imported.get(1);
        assertThat(mobile.documentTypeName()).isEqualTo("Rechnung");
        assertThat(mobile.notes()).isEqualTo("Vertrag läuft bis 2027");
        assertThat(mobile.inbox()).isFalse();

        Document receipt = imported.get(2);
        assertThat(receipt.correspondentId()).isNull();
        assertThat(receipt.documentDate()).isNull();
        assertThat(receipt.mimeType()).isEqualTo("image/png");
        assertThat(receipt.hasArchive()).isTrue();
        assertThat(receipt.content()).contains("Handwerkerquittung\nBetrag 120 Euro");
        assertThat(imported).allMatch(document -> document.status() == DocumentStatus.DONE);
    }

    @Test
    void originalsMatchTheirChecksumsByteForByte() throws Exception {
        importIt();
        for (Document document : allDocuments()) {
            byte[] data = Files.readAllBytes(mediaRoot.resolve(document.original()));
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)))
                    .as(document.title()).isEqualTo(document.checksum());
            assertThat((long) data.length).isEqualTo(document.size());
            if (document.hasThumbnail()) {
                assertThat(mediaRoot.resolve(document.thumbnail())).exists();
            }
        }
    }

    @Test
    void filesFromAnOlderArchiveMoveIntoTheCategoryFolders() throws Exception {
        importIt();
        while (filing.refileNext()) {
            // Alle Dokumente einmal durch die Ablage schicken.
        }
        String umlauts = "originals/Ohne Kategorie/Stadtwerke Müller & Söhne_20260314.pdf";
        assertThat(allDocuments()).extracting(Document::original).containsExactly(umlauts,
                "originals/Ohne Kategorie/Telekom_Rechnung_20260502.pdf",
                "originals/Ohne Kategorie/quittung_20261101.png");
        assertThat(mediaRoot.resolve(umlauts)).exists();
    }

    @Test
    void textAndMasterDataAreSearchable() {
        importIt();
        for (String word : List.of("Zaehlerstand", "Handwerkerquittung", "Müller", "Telekom", "Wichtig")) {
            assertThat(documents.count(search.search(Selection.ALL, word).selection())).as(word).isPositive();
        }
    }

    @Test
    void usersComeWithoutPasswords() {
        BackupRun result = importIt();
        assertThat(result.message()).contains("Benutzer ohne Passwort: 5").contains("Deaktivierte Postfächer: 1");
        assertThat(users.all()).extracting(user -> user.username())
                .containsExactlyInAnyOrder("admin", "anna", "bernd", "chefin", "ohne.mail");
        assertThat(users.findByUsername("chefin").orElseThrow().superuser()).isTrue();
        assertThat(users.findByUsername("anna").orElseThrow().superuser()).isFalse();
        users.all().forEach(user -> assertThat(encoder.matches("", user.passwordHash())).isFalse());
    }

    @Test
    void existingAdminKeepsPassword() {
        String hash = encoder.encode("sicheres-passwort");
        users.insert("admin", hash, true, true, "");
        BackupRun result = importIt();
        assertThat(result.message()).contains("Benutzer ohne Passwort: 4");
        var admin = users.findByUsername("admin").orElseThrow();
        assertThat(admin.passwordHash()).isEqualTo(hash);
        assertThat(admin.email()).isEqualTo("admin@example.de");
    }


    @Test
    void mailAccountArrivesDisabled() {
        importIt();
        MailAccount account = mail.all().getFirst();
        assertThat(account.name()).isEqualTo("Rechnungen");
        assertThat(account.enabled()).isFalse();
        assertThat(account.passwordEncrypted()).isEmpty();
        assertThat(account.host()).isEqualTo("imap.example.de");
        assertThat(account.assignCorrespondentId()).isEqualTo(master.getOrCreate(MasterKind.CORRESPONDENT, "Telekom")
                .orElseThrow().id());
        assertThat(account.assignTagIds()).hasSize(1);
        assertThat(mail.processed(account.id())).isNotEmpty();
    }

    @Test
    void importedDataCanBeExportedAgain() throws Exception {
        importIt();
        Files.delete(backupRoot.resolve(ARCHIVE));
        runs.enqueue(RunKind.EXPORT, "", "", false);
        backup.runNext(null);
        BackupRun result = runs.history(1).getFirst();
        assertThat(result.status()).as(result.error()).isEqualTo(RunStatus.DONE);
        assertThat(result.documents()).isEqualTo(3);
        assertThat(result.files()).isEqualTo(7);
        Path path = backupRoot.resolve(result.archive());
        assertThat(MigrationService.entryNames(path)).contains("media/" + UMLAUT_ORIGINAL);
        String lines = MigrationService.entryText(path, MigrationService.DOCUMENTS);
        assertThat(lines.getBytes(StandardCharsets.UTF_8)).isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> first = JsonMapper.shared().readValue(lines.lines().findFirst().orElseThrow(), Map.class);
        assertThat(first).containsEntry("correspondent", "Stadtwerke Müller & Söhne")
                .containsEntry("original", UMLAUT_ORIGINAL);
    }
}
