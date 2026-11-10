package de.documan;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import de.documan.service.ImageToPdf;

/** Testdateien: PDFs mit Textebene und simulierte Scans. */
public final class Samples {

    private Samples() {
    }

    /** Minimales PDF mit echter Textebene. */
    public static byte[] textPdf(String text) {
        byte[] stream = ("BT /F1 14 Tf 72 720 Td (" + text + ") Tj ET").getBytes(StandardCharsets.ISO_8859_1);
        List<byte[]> objects = List.of(
                latin("<< /Type /Catalog /Pages 2 0 R >>"),
                latin("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                latin("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
                        + "/Resources << /Font << /F1 5 0 R >> >> >>"),
                concat(latin("<< /Length " + stream.length + " >>\nstream\n"), stream, latin("\nendstream")),
                latin("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write(output, latin("%PDF-1.4\n"));
        List<Integer> offsets = new ArrayList<>();
        for (int index = 0; index < objects.size(); index++) {
            offsets.add(output.size());
            write(output, latin((index + 1) + " 0 obj\n"));
            write(output, objects.get(index));
            write(output, latin("\nendobj\n"));
        }
        int table = output.size();
        write(output, latin("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n"));
        for (int offset : offsets) {
            write(output, latin("%010d 00000 n \n".formatted(offset)));
        }
        write(output, latin("trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + table
                + "\n%%EOF\n"));
        return output.toByteArray();
    }

    private static byte[] latin(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            write(output, part);
        }
        return output.toByteArray();
    }

    private static void write(ByteArrayOutputStream output, byte[] bytes) {
        output.write(bytes, 0, bytes.length);
    }

    /** Simuliert eine eingescannte Seite. */
    public static BufferedImage scanImage(int imageType, String... lines) {
        BufferedImage image = new BufferedImage(1654, 2339, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = image.createGraphics();
        canvas.setColor(Color.WHITE);
        canvas.fillRect(0, 0, image.getWidth(), image.getHeight());
        canvas.setColor(Color.BLACK);
        canvas.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 52));
        for (int index = 0; index < lines.length; index++) {
            canvas.drawString(lines[index], 140, 260 + index * 90);
        }
        canvas.dispose();
        if (imageType == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), imageType);
        Graphics2D target = converted.createGraphics();
        target.drawImage(image, 0, 0, null);
        target.dispose();
        return converted;
    }

    public static BufferedImage scanImage(String... lines) {
        return scanImage(BufferedImage.TYPE_INT_RGB, lines);
    }

    public static byte[] imageBytes(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) {
            throw new IOException("Kein Schreiber für " + format);
        }
        return output.toByteArray();
    }

    public static byte[] png(String... lines) throws IOException {
        return imageBytes(scanImage(lines), "png");
    }

    public static byte[] multipageTiff(BufferedImage... pages) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (BufferedImage page : pages) {
                writer.writeToSequence(new IIOImage(page, null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    /** PDF, das nur aus einem Bild besteht, wie es Scanner liefern. */
    public static byte[] imageOnlyPdf(Path workspace, String... lines) throws IOException {
        Path source = workspace.resolve("quelle.png");
        Path target = workspace.resolve("quelle.pdf");
        Files.write(source, png(lines));
        ImageToPdf.convert(source, target);
        return Files.readAllBytes(target);
    }

    public static byte[] encryptedPdf(String text) throws IOException {
        try (PDDocument document = Loader.loadPDF(textPdf(text));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.protect(new StandardProtectionPolicy("def", "abc", new AccessPermission()));
            document.save(output);
            return output.toByteArray();
        }
    }

    public static int pageCount(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return document.getNumberOfPages();
        }
    }
}
