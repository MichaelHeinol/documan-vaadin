package de.documan.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import de.documan.config.DocuManProperties;

/**
 * Kurzanleitung für Benutzer als PDF. Sie hängt an der Begrüßungsmail und nennt hervorgehoben den
 * Eingangsordner dieser Installation, über den die meisten Dokumente hereinkommen.
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

    public byte[] pdf() {
        List<String> folders = inboxFolders();
        try (PDDocument document = new PDDocument()) {
            document.getDocumentInformation().setTitle(TITLE);
            document.getDocumentInformation().setAuthor("DocuMan");
            try (Writer page = new Writer(document)) {
                page.title(TITLE);
                page.paragraph("Für Benutzer: Dokumente einreichen, prüfen und wiederfinden.", Writer.MUTED);
                page.inbox(folders);

                page.heading("Anmelden");
                String site = properties.getSiteUrl().replaceAll("/+$", "");
                page.paragraph("Öffnen Sie DocuMan im Browser" + (site.isEmpty() ? "" : " unter " + site)
                        + " und melden Sie sich mit Ihrem Benutzernamen und Ihrem Passwort an. Ihr Passwort legen "
                        + "Sie über den Link in der Begrüßungsmail selbst fest. Haben Sie es vergessen, fordern Sie "
                        + "auf der Anmeldeseite unter „Passwort vergessen?“ einen neuen Link an.", Writer.INK);

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
        private static final Color ACCENT = new Color(0x1D4ED8);
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
            PDPage page = new PDPage(PAGE);
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
            y -= 22;
            show(text, BOLD, 22, ACCENT, MARGIN, y);
            y -= 10;
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

        /** Der Kasten mit dem Eingangsordner, farbig hinterlegt und in großer Schrift. */
        void inbox(List<String> folders) throws IOException {
            float inner = WIDTH - 2 * PADDING - 6;
            float left = MARGIN + PADDING + 6;
            String title = folders.size() > 1 ? "Ihre Eingangsordner" : "Ihr Eingangsordner";
            List<String> paths = new ArrayList<>();
            for (String folder : folders) {
                paths.addAll(wrap(folder, MONO, 13, inner));
            }
            List<String> text = new ArrayList<>();
            if (folders.isEmpty()) {
                text.addAll(wrap("Welcher Ordner das ist, sagt Ihnen Ihr Administrator.", REGULAR, SIZE, inner));
            }
            text.addAll(wrap("Legen Sie hier Scans, PDFs und Fotos von Dokumenten ab, am besten richten Sie Ihren "
                    + "Scanner gleich auf " + (folders.size() > 1 ? "einen dieser Ordner" : "diesen Ordner")
                    + " ein. DocuMan holt jede Datei nach wenigen Sekunden ab, erkennt den Text, auch in Scans, und "
                    + "entfernt sie danach aus dem Ordner. Das Dokument finden Sie dann im Eingang.",
                    REGULAR, SIZE, inner));
            text.add("");
            text.addAll(wrap("Geeignet sind PDF, JPG, PNG, TIFF und WebP sowie Textdateien. Was DocuMan nicht "
                    + "übernehmen kann, legt es im Unterordner " + FolderWatcher.REJECTED_DIR + " ab, den Grund "
                    + "zeigt die Seite „Fehlerhafte Importe“.", REGULAR, SIZE, inner));
            float height = PADDING + 22 + paths.size() * 18 + 8 + text.size() * LEADING + PADDING;
            y -= 18;
            ensure(height);
            float top = y;
            content.setNonStrokingColor(BOX_FILL);
            content.setStrokingColor(BOX_BORDER);
            content.setLineWidth(1.2f);
            content.addRect(MARGIN, top - height, WIDTH, height);
            content.fillAndStroke();
            content.setNonStrokingColor(BOX_BORDER);
            content.addRect(MARGIN, top - height, 6, height);
            content.fill();
            y = top - PADDING - 14;
            show(title, BOLD, 14, FOLDER, left, y);
            y -= 8;
            for (String path : paths) {
                y -= 18;
                show(path, MONO, 13, FOLDER, left, y);
            }
            y -= 8;
            for (String line : text) {
                y -= LEADING;
                if (!line.isEmpty()) {
                    show(line, REGULAR, SIZE, INK, left, y);
                }
            }
            y = top - height - 4;
        }

        @Override
        public void close() throws IOException {
            content.close();
        }
    }
}
