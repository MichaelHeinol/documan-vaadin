package de.documan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import de.documan.service.PipelineService;

/** Der gemeinsame Textleser, so wie die Java-Fassung ihn aufruft. */
class TextExtractTests extends IntegrationTest {

    @Autowired
    PipelineService pipeline;

    private String extract(String name, byte[] data, String mimeType) throws Exception {
        Path path = Files.write(workspace.resolve(name), data);
        return pipeline.extractText(path, mimeType);
    }

    @Test
    void wordWithHeader() throws Exception {
        String text = extract("brief.docx", OfficeSamples.docx(), OfficeSamples.DOCX_TYPE);
        assertThat(text).contains("Rechnung Nr. 4711\nBetrag:\t 99,50 € für Müller").contains("Stadtwerke Kopfzeile");
    }

    @Test
    void excelWithSharedAndInlineText() throws Exception {
        assertThat(extract("liste.xlsx", OfficeSamples.xlsx(), "application/zip"))
                .contains("Artikel\nSchrauben").contains("Lagerort Halle 3");
    }

    @Test
    void powerpointKeepsTheSlideOrder() throws Exception {
        String text = extract("folien.pptx", OfficeSamples.pptx(), "application/zip");
        assertThat(text.indexOf("Folie zwei")).isLessThan(text.indexOf("Folie zehn"));
    }

    @Test
    void openDocumentKeepsTheReadingOrder() throws Exception {
        assertThat(extract("vertrag.odt", OfficeSamples.odt(), "application/zip"))
                .isEqualTo("Vertrag\nHallo schöne Welt und\tGruß\n");
    }

    @Test
    void rtfWithoutFormatting() throws Exception {
        assertThat(extract("notiz.rtf", OfficeSamples.RTF, "text/rtf")).isEqualTo("Grüße aus Köln\nZeile zwei\tEnde");
    }

    @Test
    void htmlWithoutScriptsAndStyles() throws Exception {
        assertThat(extract("seite.html", OfficeSamples.HTML, "text/html"))
                .contains("Angebot", "Preis & Leistung").doesNotContain("alert", "Kopf");
    }

    @Test
    void mailWithHeadersAndAttachmentNames() throws Exception {
        assertThat(extract("post.eml", OfficeSamples.MAIL, "message/rfc822")).contains("Von: Anna <anna@example.de>",
                "Betreff: Grüße", "Hallo, anbei die Unterlagen.", "Anhänge: rechnung.pdf");
    }

    @Test
    void otherTextFormatsAreReadAsTheyAre() throws Exception {
        byte[] json = "{\"kunde\": \"Weber\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(extract("daten.json", json, "application/json")).isEqualTo("{\"kunde\": \"Weber\"}");
    }

    @Test
    void unknownAndBrokenFilesGiveEmptyText() throws Exception {
        byte[] noise = new byte[256];
        for (int index = 0; index < noise.length; index++) {
            noise[index] = (byte) index;
        }
        assertThat(extract("x.bin", noise, "image/x-tga")).isEmpty();
        assertThat(extract("kaputt.docx", OfficeSamples.zipped(Map.of("word/document.xml", "<w:document")),
                OfficeSamples.DOCX_TYPE)).isEmpty();
    }
}
