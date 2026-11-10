package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import de.documan.config.DocuManProperties;
import de.documan.domain.Algorithm;
import de.documan.domain.Document;
import de.documan.domain.DocumentSource;
import de.documan.domain.DocumentStatus;
import de.documan.domain.MasterItem;
import de.documan.domain.MasterKind;
import de.documan.repo.DocumentRepository;
import de.documan.repo.MasterRepository;
import de.documan.service.IngestException;
import de.documan.service.IngestService;
import de.documan.service.Reindexer;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

/** Grundlage aller Tests: echtes PostgreSQL, eigene Ordner je Testfall. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    protected static final RequestPostProcessor ANNA = user("anna").roles("USER");
    protected static final RequestPostProcessor CHEF = user("chef").roles("USER", "ADMIN");

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected DocuManProperties properties;
    @Autowired
    protected DocumentRepository documents;
    @Autowired
    protected MasterRepository master;
    @Autowired
    protected Reindexer reindexer;
    @Autowired
    protected IngestService ingest;

    protected Path mediaRoot;
    protected Path backupRoot;
    protected Path workspace;
    private int created;

    @BeforeEach
    void prepare() throws IOException {
        mediaRoot = Files.createTempDirectory("documan-test-");
        backupRoot = Files.createTempDirectory("documan-backup-");
        workspace = Files.createTempDirectory("documan-work-");
        properties.setMediaRoot(mediaRoot);
        properties.setBackupDir(backupRoot);
        properties.setMaxFileMb(500);
        properties.setSecretKey("nur-fuer-tests");
        created = 0;
        jdbc.sql("""
                TRUNCATE app_user, correspondent, category, document_type, tag, document, document_tag,
                         mail_account, mail_account_tag, processed_mail, backup_settings, backup_run, activity,
                         consume_folder, storage_usage, daily_report RESTART IDENTITY CASCADE
                """).update();
        // So steht es nach der Einrichtung in der Datenbank.
        jdbc.sql("INSERT INTO consume_folder (folder) VALUES ('eingang')").update();
    }

    @AfterEach
    void cleanUp() throws IOException {
        deleteTree(mediaRoot);
        deleteTree(backupRoot);
        deleteTree(workspace);
    }

    /** Ein Teil einer Mail, die als Text und HTML verschickt wurde, mit Zeilenenden wie im Quelltext. */
    protected static String mailPart(MimeMessage message, String type) throws Exception {
        String found = findPart((MimeMultipart) message.getContent(), type);
        if (found == null) {
            throw new AssertionError("Kein Teil vom Typ " + type);
        }
        return found;
    }

    /** Sucht auch in verschachtelten Teilen, etwa in multipart/related mit eingebetteten Bildern. */
    private static String findPart(MimeMultipart parts, String type) throws Exception {
        for (int index = 0; index < parts.getCount(); index++) {
            var part = parts.getBodyPart(index);
            if (part.isMimeType(type)) {
                return part.getContent().toString().replace("\r\n", "\n");
            }
            if (part.getContent() instanceof MimeMultipart nested) {
                String found = findPart(nested, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    protected static String plainText(MimeMessage message) throws Exception {
        return mailPart(message, "text/plain");
    }

    protected static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Bequemes Anlegen von Dokumenten, wie sie der Worker hinterlassen würde. */
    protected final class DocumentBuilder {
        private final String title;
        private String content = "";
        private String checksum;
        private DocumentStatus status = DocumentStatus.DONE;
        private String original = "originals/2026/01/x.pdf";
        private String originalFilename = "x.pdf";
        private String mimeType = "application/pdf";
        private Long correspondentId;
        private Long documentTypeId;
        private LocalDate documentDate;
        private Integer pageCount;
        private boolean inbox = true;
        private int attempts;
        private final List<Long> tagIds = new ArrayList<>();

        private DocumentBuilder(String title) {
            this.title = title;
        }

        /** Eine Datei in einem anderen Format als PDF. */
        public DocumentBuilder file(String name, String type) {
            original = "originals/2026/01/" + name;
            originalFilename = name;
            mimeType = type;
            return this;
        }

        public DocumentBuilder content(String value) {
            content = value;
            return this;
        }

        public DocumentBuilder status(DocumentStatus value) {
            status = value;
            return this;
        }

        public DocumentBuilder original(String value) {
            original = value;
            return this;
        }

        public DocumentBuilder correspondent(MasterItem item) {
            correspondentId = item.id();
            return this;
        }

        public DocumentBuilder type(MasterItem item) {
            documentTypeId = item.id();
            return this;
        }

        public DocumentBuilder date(String value) {
            documentDate = LocalDate.parse(value);
            return this;
        }

        public DocumentBuilder date(LocalDate value) {
            documentDate = value;
            return this;
        }

        public DocumentBuilder pages(int value) {
            pageCount = value;
            return this;
        }

        public DocumentBuilder filed() {
            inbox = false;
            return this;
        }

        public DocumentBuilder attempts(int value) {
            attempts = value;
            return this;
        }

        public DocumentBuilder tag(MasterItem item) {
            tagIds.add(item.id());
            return this;
        }

        public Document create() {
            created++;
            String sum = checksum != null ? checksum : "%064d".formatted(created);
            long id = jdbc.sql("""
                    INSERT INTO document (title, content, checksum, status, original, original_filename, mime_type,
                        correspondent_id, document_type_id, document_date, page_count, inbox, attempts, added)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """)
                    .params(title, content, sum, status.value(), original, originalFilename, mimeType, correspondentId,
                            documentTypeId, documentDate, pageCount, inbox, attempts,
                            java.sql.Timestamp.from(Instant.now().plusMillis(created)))
                    .query(Long.class).single();
            documents.addTags(id, tagIds);
            reindexer.reindex(id);
            return reload(id);
        }

        /** Legt zusätzlich die Datei an, damit Vorschau und Sicherung sie finden. */
        public Document stored() throws IOException {
            Document document = create();
            Path path = mediaRoot.resolve(document.original());
            Files.createDirectories(path.getParent());
            Files.write(path, Samples.textPdf(title));
            return document;
        }
    }

    protected DocumentBuilder document(String title) {
        return new DocumentBuilder(title);
    }

    protected Document reload(Document document) {
        return reload(document.id());
    }

    protected Document reload(long id) {
        return documents.find(id).orElseThrow();
    }

    protected MasterItem correspondent(String name) {
        return master.find(MasterKind.CORRESPONDENT,
                master.insert(MasterKind.CORRESPONDENT, name, Algorithm.NONE, "", true, null)).orElseThrow();
    }

    protected MasterItem documentType(String name) {
        return master.find(MasterKind.DOCUMENT_TYPE,
                master.insert(MasterKind.DOCUMENT_TYPE, name, Algorithm.NONE, "", true, null)).orElseThrow();
    }

    /** Legt eine Kategorie der obersten Stufe an und ordnet ihr die Dokumenttypen zu. */
    protected MasterItem category(String name, MasterItem... types) {
        return subcategory(null, name, types);
    }

    /** Legt eine Kategorie unter parent an (null: oberste Stufe) und ordnet ihr die Dokumenttypen zu. */
    protected MasterItem subcategory(MasterItem parent, String name, MasterItem... types) {
        long id = master.saveCategory(null, name, parent == null ? null : parent.id());
        for (MasterItem type : types) {
            master.setCategory(type.id(), id);
        }
        return master.find(MasterKind.CATEGORY, id).orElseThrow();
    }

    protected MasterItem tag(String name) {
        return tag(name, MasterItem.DEFAULT_COLOR);
    }

    protected MasterItem tag(String name, String color) {
        return master.find(MasterKind.TAG, master.insert(MasterKind.TAG, name, Algorithm.NONE, "", true, color))
                .orElseThrow();
    }

    protected Document add(byte[] data, String filename) throws IngestException {
        return ingest.ingest(named(data, filename), filename, DocumentSource.UPLOAD, IngestService.Options.NONE);
    }

    protected Document add(byte[] data) throws IngestException {
        return add(data, "datei.pdf");
    }

    private static ByteArrayResource named(byte[] data, String filename) {
        return new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    protected MvcResult page(String url) throws Exception {
        return mvc.perform(get(url).with(ANNA)).andReturn();
    }

    protected MvcResult page(String url, RequestPostProcessor person) throws Exception {
        return mvc.perform(get(url).with(person)).andReturn();
    }

    protected MvcResult submit(String url, String... fields) throws Exception {
        return submit(url, ANNA, fields);
    }

    protected MvcResult submit(String url, RequestPostProcessor person, String... fields) throws Exception {
        MockHttpServletRequestBuilder request = post(url).with(person).with(csrf());
        for (int index = 0; index < fields.length; index += 2) {
            request = request.param(fields[index], fields[index + 1]);
        }
        return mvc.perform(request).andReturn();
    }

    protected static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    protected static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    @SuppressWarnings("unchecked")
    protected static <T> T model(MvcResult result, String name) {
        return (T) result.getModelAndView().getModel().get(name);
    }

    protected static String redirect(MvcResult result) {
        return result.getResponse().getRedirectedUrl();
    }
}
