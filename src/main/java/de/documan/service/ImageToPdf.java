package de.documan.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;

/** Wandelt ein Bild in ein PDF. Mehrseitige TIFFs bleiben mehrseitig. */
public final class ImageToPdf {

    private static final float DEFAULT_DPI = 300;
    private static final float MINIMUM_DPI = 72;
    private static final float JPEG_QUALITY = 0.92f;
    private static final float POINTS_PER_INCH = 72;
    private static final float MILLIMETERS_PER_INCH = 25.4f;
    // Schützt den Arbeitsspeicher vor manipulierten Bildern, entspricht rund 1200 dpi auf A4.
    private static final long MAX_PIXELS = 200_000_000L;

    private ImageToPdf() {
    }

    public static void convert(Path image, Path pdf) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(image.toFile())) {
            if (input == null) {
                throw new IOException("Datei nicht lesbar");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("unbekanntes Bildformat");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                write(reader, orientation(image), pdf);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void write(ImageReader reader, int orientation, Path pdf) throws IOException {
        try (PDDocument document = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            int frames = reader.getNumImages(true);
            for (int index = 0; index < frames; index++) {
                if ((long) reader.getWidth(index) * reader.getHeight(index) > MAX_PIXELS) {
                    throw new IOException("Bild ist zu groß");
                }
                BufferedImage page = upright(onWhite(reader.read(index)), orientation);
                addPage(document, page, resolution(reader, index));
            }
            document.save(pdf.toFile());
        }
    }

    private static void addPage(PDDocument document, BufferedImage image, float dpi) throws IOException {
        float scale = POINTS_PER_INCH / dpi;
        float width = image.getWidth() * scale;
        float height = image.getHeight() * scale;
        PDPage page = new PDPage(new PDRectangle(width, height));
        document.addPage(page);
        PDImageXObject embedded = JPEGFactory.createFromImage(document, image, JPEG_QUALITY);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(embedded, 0, 0, width, height);
        }
    }

    /** Transparenz gibt es im PDF-Scan nicht, sie wird zu weißem Papier. */
    private static BufferedImage onWhite(BufferedImage source) {
        BufferedImage page = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = page.createGraphics();
        canvas.setColor(Color.WHITE);
        canvas.fillRect(0, 0, page.getWidth(), page.getHeight());
        canvas.drawImage(source, 0, 0, null);
        canvas.dispose();
        return page;
    }

    private static float resolution(ImageReader reader, int index) {
        try {
            IIOMetadata metadata = reader.getImageMetadata(index);
            if (metadata == null || !metadata.isStandardMetadataFormatSupported()) {
                return DEFAULT_DPI;
            }
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_1.0");
            var sizes = root.getElementsByTagName("HorizontalPixelSize");
            if (sizes.getLength() == 0) {
                return DEFAULT_DPI;
            }
            float millimeters = Float.parseFloat(((IIOMetadataNode) sizes.item(0)).getAttribute("value"));
            float dpi = MILLIMETERS_PER_INCH / millimeters;
            return dpi >= MINIMUM_DPI && !Float.isInfinite(dpi) ? dpi : DEFAULT_DPI;
        } catch (IOException | RuntimeException noResolution) {
            return DEFAULT_DPI;
        }
    }

    /** Handyfotos liegen oft gedreht in der Datei, die Lage steht in den EXIF-Daten. */
    private static int orientation(Path image) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(image.toFile());
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (ImageProcessingException | MetadataException | IOException | RuntimeException noExif) {
            return 1;
        }
        return 1;
    }

    private static BufferedImage upright(BufferedImage image, int orientation) {
        int width = image.getWidth();
        int height = image.getHeight();
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> null;
        };
        if (transform == null) {
            return image;
        }
        boolean swapped = orientation >= 5;
        BufferedImage turned = new BufferedImage(swapped ? height : width, swapped ? width : height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = turned.createGraphics();
        canvas.drawImage(image, transform, null);
        canvas.dispose();
        return turned;
    }
}
