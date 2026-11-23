package de.documan.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.springframework.stereotype.Service;

import de.documan.config.DocuManProperties;

/**
 * Persönliche Kurzanleitung für Benutzer als PDF. Sie hängt an der Begrüßungsmail, spricht den Benutzer mit
 * Namen an, nennt seine Anmeldung mit anklickbarem Link und hebt den Eingangsordner dieser Installation hervor,
 * über den die meisten Dokumente hereinkommen.
 */
@Service
public class UserGuide {

    public static final String FILENAME = "DocuMan-Kurzanleitung.pdf";
    public static final String TITLE = "DocuMan – Kurzanleitung";

    private final DocuManProperties properties;
    private final InboxFolder inbox;

    public UserGuide(DocuManProperties properties, InboxFolder inbox) {
        this.properties = properties;
        this.inbox = inbox;
    }

    /**
     * Die überwachten Eingangsordner, wie Benutzer sie finden: für den Standardordner der Netzwerkpfad aus
     * CONSUME_SHARE, sonst der Pfad auf dem NAS. Nicht erreichbare Ordner fehlen.
     */
    public List<String> inboxFolders() {
        List<String> folders = new ArrayList<>();
        for (InboxFolder.Entry entry : inbox.folders()) {
            try {
                Path path = inbox.pathOf(entry.folder());
                if (!Files.isDirectory(path)) {
                    continue;
                }
                boolean standard = entry.folder().equals(InboxFolder.DEFAULT);
                folders.add(standard && !properties.getConsumeShare().isEmpty() ? properties.getConsumeShare()
                        : inbox.display(entry.folder()));
            } catch (InboxFolderException | InvalidPathException vanished) {
                // Ein Eintrag, der nicht mehr zur Auswahl steht, gehört nicht in die Anleitung.
            }
        }
        return folders;
    }

    /** Die Anmeldeseite, leer ohne SITE_URL. */
    public String loginUrl() {
        String site = properties.getSiteUrl().replaceAll("/+$", "");
        return site.isEmpty() ? "" : site + "/login";
    }

    /**
     * Die Anleitung für einen bestimmten Benutzer: Sie spricht ihn mit Namen an und nennt seine Anmeldung mit
     * anklickbarem Link.
     *
     * @param username der Benutzername für die Anmeldung
     * @param name     so wird der Benutzer angesprochen, etwa sein Vorname; leer heißt Benutzername
     */
    public byte[] pdf(String username, String name) {
        List<String> folders = inboxFolders();
        String greeting = name == null || name.isBlank() ? username : name.strip();
        String today = LocalDate.now(properties.getTimeZone()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        try (PDDocument document = new PDDocument()) {
            document.getDocumentInformation().setTitle(TITLE + " für " + greeting);
            document.getDocumentInformation().setAuthor("DocuMan");
            try (Writer page = new Writer(document)) {
                page.title("Willkommen bei DocuMan, " + greeting + "!");
                page.paragraph("Ihre persönliche Kurzanleitung, erstellt am " + today, Writer.MUTED);
                page.paragraph("Schön, dass Sie dabei sind! Hier steht alles, was Sie für den Anfang brauchen: wie "
                        + "Sie sich anmelden, wohin Ihre Dokumente gehören und wie Sie sie wiederfinden.", Writer.INK);
                page.login(username, loginUrl());
                page.inbox(folders);

                page.heading("Weitere Wege für neue Dokumente");
                page.bullet("Hochladen: Über „Hochladen“ oben rechts wählen Sie Dateien von Ihrem Rechner oder "
                        + "Handy aus.");
                page.bullet("E-Mail: Sind Postfächer eingerichtet, übernimmt DocuMan auch die Anhänge "
                        + "eingehender Mails. Die Adresse nennt Ihnen Ihr Administrator.");

                page.heading("Neue Dokumente prüfen: der Eingang");
                page.paragraph("Jedes neue Dokument landet zuerst im „Eingang“. Öffnen Sie es und prüfen Sie "
                        + "Absendername, Dokumenttyp, Kategorie, Dokumentdatum und Tags. Den Titel bildet DocuMan "
                        + "selbst aus Absendername, Dokumenttyp und Datum.", Writer.INK);
                page.bullet("„Ablegen und weiter“ nimmt das Dokument aus dem Eingang und öffnet das nächste.");
                page.bullet("„Speichern“ übernimmt Ihre Änderungen, das Dokument bleibt im Eingang.");
                page.bullet("Angaben mit der Markierung KI hat die automatische Zuordnung gesetzt. Mit dem "
                        + "Speichern bestätigen Sie sie, daraus lernt DocuMan für die nächsten Dokumente.");
                page.bullet("Solange ein Dokument verarbeitet wird oder die KI-Analyse läuft, lassen sich seine "
                        + "Angaben nicht ändern. Die Seite aktualisiert sich von selbst.");

                page.heading("Dokumente wiederfinden");
                page.bullet("Die Suche oben durchsucht auch den erkannten Text der Dokumente.");
                page.bullet("Unter „Alle Dokumente“ grenzen Sie nach Absendername, Dokumenttyp, Kategorie, Tags "
                        + "und Jahr ein.");
                page.bullet("„Ordner“ zeigt das Archiv als Baum, wahlweise nach Kategorie, Dokumenttyp oder "
                        + "Absender.");

                page.heading("Wenn etwas nicht klappt");
                page.paragraph("Fehlt eine Datei, sehen Sie unter „Fehlerhafte Importe“ nach. Dort steht, warum "
                        + "DocuMan sie nicht übernommen hat, etwa weil das Dokument schon im Archiv ist oder die "
                        + "Datei nicht gelesen werden konnte.", Writer.INK);

                page.heading("Ihr Konto");
                page.paragraph("Unter „Mein Konto“ ändern Sie Ihr Passwort, Ihre E-Mail-Adresse und die "
                        + "Darstellung.", Writer.INK);

                page.paragraph("", Writer.INK);
                page.paragraph("Viel Freude mit Ihrem Archiv, " + greeting + "! Bei Fragen hilft Ihnen Ihr "
                        + "Administrator gern weiter.", Writer.ACCENT);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Schreibt Text von oben nach unten und beginnt bei Bedarf eine neue Seite. */
    private static final class Writer implements AutoCloseable {

        static final Color INK = new Color(0x1F2937);
        static final Color MUTED = new Color(0x4B5563);
        static final Color ACCENT = new Color(0x1D4ED8);
        private static final Color LOGIN_FILL = new Color(0xDBEAFE);
        private static final Color LOGIN_TEXT = new Color(0x1E3A8A);
        private static final Color BOX_FILL = new Color(0xFEF3C7);
        private static final Color BOX_BORDER = new Color(0xD97706);
        private static final Color FOLDER = new Color(0x7C2D12);
        private static final PDRectangle PAGE = PDRectangle.A4;
        private static final float MARGIN = 56;
        private static final float WIDTH = PAGE.getWidth() - 2 * MARGIN;
        private static final float SIZE = 10.5f;
        private static final float LEADING = 14.5f;
        private static final float BULLET_INDENT = 14;
        private static final float PADDING = 14;
        private static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private static final PDFont MONO = new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);

        private final PDDocument document;
        private PDPage page;
        private PDPageContentStream content;
        private float y;

        Writer(PDDocument document) throws IOException {
            this.document = document;
            newPage();
        }

        private void newPage() throws IOException {
            if (content != null) {
                content.close();
            }
            page = new PDPage(PAGE);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = PAGE.getHeight() - MARGIN;
        }

        private void ensure(float height) throws IOException {
            if (y - height < MARGIN) {
                newPage();
            }
        }

        /** Zeichen, die die Standardschrift nicht kennt, werden zu einem Fragezeichen. */
        private static String printable(String text, PDFont font) {
            StringBuilder result = new StringBuilder();
            text.codePoints().forEach(point -> {
                String character = new String(Character.toChars(point));
                try {
                    font.encode(character);
                    result.append(character);
                } catch (IOException | IllegalArgumentException unknown) {
                    result.append('?');
                }
            });
            return result.toString();
        }

        private static float width(String text, PDFont font, float size) throws IOException {
            return font.getStringWidth(printable(text, font)) / 1000 * size;
        }

        /** Bricht an Leerzeichen um, ein zu langes Wort wie ein Pfad wird zeichenweise getrennt. */
        private static List<String> wrap(String text, PDFont font, float size, float available) throws IOException {
            List<String> lines = new ArrayList<>();
            String line = "";
            for (String word : text.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(candidate, font, size) <= available) {
                    line = candidate;
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line);
                }
                line = word;
                while (width(line, font, size) > available && line.length() > 1) {
                    int cut = line.length() - 1;
                    while (cut > 1 && width(line.substring(0, cut), font, size) > available) {
                        cut--;
                    }
                    lines.add(line.substring(0, cut));
                    line = line.substring(cut);
                }
            }
            if (!line.isEmpty()) {
                lines.add(line);
            }
            return lines;
        }

        private void show(String text, PDFont font, float size, Color color, float x, float baseline)
                throws IOException {
            content.beginText();
            content.setFont(font, size);
            content.setNonStrokingColor(color);
            content.newLineAtOffset(x, baseline);
            content.showText(printable(text, font));
            content.endText();
        }

        void title(String text) throws IOException {
            for (String line : wrap(text, BOLD, 20, WIDTH)) {
                y -= 24;
                show(line, BOLD, 20, ACCENT, MARGIN, y);
            }
            y -= 8;
        }

        void heading(String text) throws IOException {
            ensure(60);
            y -= 26;
            show(text, BOLD, 13.5f, ACCENT, MARGIN, y);
            y -= 6;
        }

        void paragraph(String text, Color color) throws IOException {
            for (String line : wrap(text, REGULAR, SIZE, WIDTH)) {
                ensure(LEADING);
                y -= LEADING;
                show(line, REGULAR, SIZE, color, MARGIN, y);
            }
            y -= 6;
        }

        void bullet(String text) throws IOException {
            List<String> lines = wrap(text, REGULAR, SIZE, WIDTH - BULLET_INDENT);
            for (int index = 0; index < lines.size(); index++) {
                ensure(LEADING);
                y -= LEADING;
                if (index == 0) {
                    show("•", REGULAR, SIZE, ACCENT, MARGIN + 2, y);
                }
                show(lines.get(index), REGULAR, SIZE, INK, MARGIN + BULLET_INDENT, y);
            }
            y -= 4;
        }

        /** Eine Zeile in einem Kasten; mit Adresse wird sie anklickbar. */
        private record Row(String text, PDFont font, float size, Color color, float advance, String url) {

            static Row gap(float advance) {
                return new Row("", REGULAR, SIZE, INK, advance, "");
            }
        }

        private static float inner() {
            return WIDTH - 2 * PADDING - 6;
        }

        private static void add(List<Row> rows, String text, PDFont font, float size, Color color, float advance,
                String url) throws IOException {
            for (String line : wrap(text, font, size, inner())) {
                rows.add(new Row(line, font, size, color, advance, url));
            }
        }

        private static void text(List<Row> rows, String text) throws IOException {
            add(rows, text, REGULAR, SIZE, INK, LEADING, "");
        }

        /** Ein farbig hinterlegter Kasten mit Randleiste. */
        private void box(List<Row> rows, Color fill, Color border) throws IOException {
            float height = 2 * PADDING + 4;
            for (Row row : rows) {
                height += row.advance();
            }
            y -= 18;
            ensure(height);
            float top = y;
            content.setNonStrokingColor(fill);
            content.setStrokingColor(border);
            content.setLineWidth(1.2f);
            content.addRect(MARGIN, top - height, WIDTH, height);
            content.fillAndStroke();
            content.setNonStrokingColor(border);
            content.addRect(MARGIN, top - height, 6, height);
            content.fill();
            float left = MARGIN + PADDING + 6;
            y = top - PADDING + 2;
            for (Row row : rows) {
                y -= row.advance();
                if (row.text().isEmpty()) {
                    continue;
                }
                show(row.text(), row.font(), row.size(), row.color(), left, y);
                if (!row.url().isEmpty()) {
                    link(row, left);
                }
            }
            y = top - height - 4;
        }

        /** Unterstreicht die Zeile und macht sie anklickbar. */
        private void link(Row row, float left) throws IOException {
            float lineWidth = width(row.text(), row.font(), row.size());
            content.setStrokingColor(row.color());
            content.setLineWidth(0.8f);
            content.moveTo(left, y - 2);
            content.lineTo(left + lineWidth, y - 2);
            content.stroke();
            PDActionURI action = new PDActionURI();
            action.setURI(row.url());
            PDBorderStyleDictionary border = new PDBorderStyleDictionary();
            border.setWidth(0);
            PDAnnotationLink annotation = new PDAnnotationLink();
            annotation.setRectangle(new PDRectangle(left, y - 4, lineWidth, row.size() + 6));
            annotation.setBorderStyle(border);
            annotation.setAction(action);
            page.getAnnotations().add(annotation);
        }

        /** Der Kasten mit der Anmeldung: Adresse als Link und Benutzername. */
        void login(String username, String url) throws IOException {
            List<Row> rows = new ArrayList<>();
            add(rows, "Ihre Anmeldung", BOLD, 14, LOGIN_TEXT, 16, "");
            rows.add(Row.gap(6));
            if (url.isEmpty()) {
                text(rows, "Die Adresse von DocuMan nennt Ihnen Ihr Administrator.");
            } else {
                add(rows, "Hier geht es zur Anmeldung:", REGULAR, SIZE, INK, LEADING, "");
                add(rows, url, BOLD, 13, ACCENT, 18, url);
            }
            rows.add(Row.gap(4));
            add(rows, "Ihr Benutzername:", REGULAR, SIZE, INK, LEADING, "");
            add(rows, username, MONO, 13, LOGIN_TEXT, 18, "");
            rows.add(Row.gap(6));
            text(rows, "Ihr Passwort legen Sie zuerst über den Link in der Begrüßungsmail fest, danach melden Sie "
                    + "sich hier an. Haben Sie es vergessen, fordern Sie auf der Anmeldeseite unter „Passwort "
                    + "vergessen?“ einen neuen Link an.");
            box(rows, LOGIN_FILL, ACCENT);
        }

        /** Der Kasten mit dem Eingangsordner, farbig hinterlegt und in großer Schrift. */
        void inbox(List<String> folders) throws IOException {
            List<Row> rows = new ArrayList<>();
            add(rows, folders.size() > 1 ? "Ihre Eingangsordner" : "Ihr Eingangsordner", BOLD, 14, FOLDER, 16, "");
            rows.add(Row.gap(8));
            for (String folder : folders) {
                add(rows, folder, MONO, 13, FOLDER, 18, "");
            }
            rows.add(Row.gap(8));
            if (folders.isEmpty()) {
                text(rows, "Welcher Ordner das ist, sagt Ihnen Ihr Administrator.");
            }
            text(rows, "Legen Sie hier Scans, PDFs und Fotos von Dokumenten ab, am besten richten Sie Ihren "
                    + "Scanner gleich auf " + (folders.size() > 1 ? "einen dieser Ordner" : "diesen Ordner")
                    + " ein. DocuMan holt jede Datei nach wenigen Sekunden ab, erkennt den Text, auch in Scans, und "
                    + "entfernt sie danach aus dem Ordner. Das Dokument finden Sie dann im Eingang.");
            rows.add(Row.gap(LEADING));
            text(rows, "Geeignet sind PDF, JPG, PNG, TIFF und WebP sowie Textdateien. Was DocuMan nicht "
                    + "übernehmen kann, legt es im Unterordner " + FolderWatcher.REJECTED_DIR + " ab, den Grund "
                    + "zeigt die Seite „Fehlerhafte Importe“.");
            box(rows, BOX_FILL, BOX_BORDER);
        }

        @Override
        public void close() throws IOException {
            content.close();
        }
    }
}
