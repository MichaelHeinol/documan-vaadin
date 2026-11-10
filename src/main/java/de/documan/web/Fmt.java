package de.documan.web;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import de.documan.config.DocuManProperties;
import de.documan.domain.Document;

/** Deutsche Darstellung von Datum, Zeit und Größen in den Vorlagen. */
@Component("fmt")
public class Fmt {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd.MM. HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> UNITS = List.of("Bytes", "KB", "MB", "GB", "TB");
    private static final int STEP = 1024;

    private final DocuManProperties properties;

    public Fmt(DocuManProperties properties) {
        this.properties = properties;
    }

    private ZonedDateTime local(Instant moment) {
        return moment.atZone(properties.getTimeZone());
    }

    public String date(LocalDate date) {
        return date == null ? "" : DATE.format(date);
    }

    public String date(Instant moment) {
        return moment == null ? "" : DATE.format(local(moment));
    }

    public String date(Document document) {
        return document.documentDate() != null ? date(document.documentDate()) : date(document.added());
    }

    public String dateTime(Instant moment) {
        return moment == null ? "" : DATE_TIME.format(local(moment));
    }

    public String dateTime(OffsetDateTime moment) {
        return moment == null ? "" : dateTime(moment.toInstant());
    }

    public String shortDateTime(Instant moment) {
        return moment == null ? "" : SHORT.format(local(moment));
    }

    public String shortDateTime(TemporalAccessor moment) {
        return moment == null ? "" : SHORT.format(moment);
    }

    public String time(TemporalAccessor moment) {
        return moment == null ? "" : TIME.format(moment);
    }

    public String size(Long bytes) {
        if (bytes == null) {
            return "";
        }
        double value = bytes;
        int unit = 0;
        while (value >= STEP && unit < UNITS.size() - 1) {
            value /= STEP;
            unit++;
        }
        if (unit == 0) {
            return bytes + " " + UNITS.getFirst();
        }
        return new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.GERMANY)).format(value) + " "
                + UNITS.get(unit);
    }

    public String checksum(String value) {
        return value.length() > 16 ? value.substring(0, 16) + "…" : value;
    }
}
