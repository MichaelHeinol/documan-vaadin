package de.documan.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * Einstellungen der täglichen Übersicht.
 *
 * @param weekdays 0 = Montag bis 6 = Sonntag
 * @param excluded Benutzer-IDs der Superuser, die die Übersicht nicht bekommen
 * @param sentOn   wann die Übersicht zuletzt ging
 */
public record ReportSettings(
        boolean enabled,
        LocalTime time,
        List<Integer> weekdays,
        List<ReportSection> sections,
        ReportChart sourcesChart,
        ReportChart typesChart,
        ReportChart storageChart,
        ReportChart categoriesChart,
        List<String> extraRecipients,
        Set<Long> excluded,
        LocalDate sentOn) {

    public static final LocalTime DEFAULT_TIME = LocalTime.of(7, 0);

    public boolean shows(ReportSection section) {
        return sections.contains(section);
    }
}
