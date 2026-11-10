package de.documan.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Tortengrafiken als PNG für die Übersichtsmail.
 *
 * <p>Die Bilder enthalten keine Schrift, Werte und Anteile stehen in der Legende daneben. So sieht das Bild
 * in beiden Versionen gleich aus und braucht keine Schriftarten im Container.
 */
public final class MailCharts {

    private static final int SIZE = 320;
    private static final double GAP = 2;
    private static final double MARGIN = 2;

    private MailCharts() {
    }

    /** Eine Torte, die oben beginnt und im Uhrzeigersinn läuft. Weiße Linien trennen die Stücke. */
    public static byte[] piePng(List<Long> values, List<String> colors) {
        double total = values.stream().mapToLong(Long::longValue).sum();
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            double diameter = SIZE - 2 * MARGIN;
            // Java zählt Winkel gegen den Uhrzeigersinn ab drei Uhr, also beginnt die Torte bei 90 Grad.
            double start = 90;
            List<Double> borders = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                long value = values.get(index);
                if (value <= 0) {
                    continue;
                }
                double sweep = value * 360 / total;
                graphics.setColor(Color.decode(colors.get(index)));
                graphics.fill(sweep >= 360 ? new Ellipse2D.Double(MARGIN, MARGIN, diameter, diameter)
                        : new Arc2D.Double(MARGIN, MARGIN, diameter, diameter, start, -sweep, Arc2D.PIE));
                borders.add(start);
                start -= sweep;
            }
            if (borders.size() > 1) {
                double center = SIZE / 2.0;
                graphics.setColor(Color.WHITE);
                graphics.setStroke(new BasicStroke((float) GAP));
                for (double angle : borders) {
                    double radians = Math.toRadians(angle);
                    graphics.draw(new Line2D.Double(center, center, center + center * Math.cos(radians),
                            center - center * Math.sin(radians)));
                }
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", buffer);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return buffer.toByteArray();
    }
}
