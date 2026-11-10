package de.documan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Kleine Dateien in Office- und anderen Formaten, dieselben wie in den Tests der Django-Fassung. */
final class OfficeSamples {

    static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
    private static final String A = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"";
    private static final String S = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
    private static final String O = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
            + "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\"";

    static final byte[] RTF = ("{\\rtf1\\ansi\\ansicpg1252{\\fonttbl{\\f0 Arial;}}{\\*\\generator Word;}"
            + "Gr\\'fc\\'dfe aus K\\u246?ln\\par Zeile zwei\\tab Ende}").getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] HTML = ("<html><head><title>Kopf</title><style>p{}</style></head><body><h1>Angebot</h1>"
            + "<p>Preis &amp; Leistung</p><script>alert(1)</script></body></html>").getBytes(StandardCharsets.UTF_8);
    static final byte[] MAIL = ("From: Anna <anna@example.de>\r\nTo: ablage@example.de\r\n"
            + "Subject: =?utf-8?q?Gr=C3=BC=C3=9Fe?=\r\nMIME-Version: 1.0\r\n"
            + "Content-Type: multipart/mixed; boundary=X\r\n\r\n"
            + "--X\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nHallo, anbei die Unterlagen.\r\n"
            + "--X\r\nContent-Type: application/pdf\r\n"
            + "Content-Disposition: attachment; filename=\"rechnung.pdf\"\r\n\r\n%PDF\r\n--X--\r\n")
            .getBytes(StandardCharsets.UTF_8);

    private OfficeSamples() {
    }

    static byte[] zipped(Map<String, String> parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static byte[] docx() throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types/>");
        parts.put("word/document.xml", "<w:document " + W + "><w:body><w:p><w:r><w:t>Rechnung Nr. 4711</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>Betrag:</w:t></w:r><w:r><w:tab/><w:t xml:space=\"preserve\"> 99,50 € für Müller</w:t>"
                + "</w:r></w:p></w:body></w:document>");
        parts.put("word/header1.xml", "<w:hdr " + W + "><w:p><w:r><w:t>Stadtwerke Kopfzeile</w:t></w:r></w:p></w:hdr>");
        return zipped(parts);
    }

    static byte[] xlsx() throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types/>");
        parts.put("xl/workbook.xml", "<workbook " + S + "/>");
        parts.put("xl/sharedStrings.xml",
                "<sst " + S + "><si><t>Artikel</t></si><si><r><t>Schrau</t></r><r><t>ben</t></r></si></sst>");
        parts.put("xl/worksheets/sheet1.xml", "<worksheet " + S + "><sheetData><row><c t=\"inlineStr\"><is><t>"
                + "Lagerort Halle 3</t></is></c></row></sheetData></worksheet>");
        return zipped(parts);
    }

    private static String slide(String text) {
        return "<p:sld xmlns:p=\"p\" " + A + "><a:p><a:r><a:t>" + text + "</a:t></a:r></a:p></p:sld>";
    }

    static byte[] pptx() throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types/>");
        parts.put("ppt/presentation.xml", "<p/>");
        parts.put("ppt/slides/slide10.xml", slide("Folie zehn"));
        parts.put("ppt/slides/slide2.xml", slide("Folie zwei"));
        return zipped(parts);
    }

    static byte[] odt() throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("mimetype", "application/vnd.oasis.opendocument.text");
        parts.put("content.xml", "<office:document-content " + O + "><office:body><office:text>"
                + "<text:h>Vertrag</text:h><text:p>Hallo <text:span>schöne</text:span> Welt<text:s/>und<text:tab/>"
                + "Gruß</text:p></office:text></office:body></office:document-content>");
        return zipped(parts);
    }
}
