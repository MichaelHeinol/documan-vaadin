package de.documan.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/** Zeitplan der Datensicherung. Wochentag: 0 = Montag bis 6 = Sonntag. */
public record BackupSettings(
        boolean enabled,
        BackupFrequency frequency,
        int weekday,
        LocalTime time,
        String folder,
        int keep,
        Instant lastScheduled) {

    public static final List<String> WEEKDAYS =
            List.of("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag");
}
