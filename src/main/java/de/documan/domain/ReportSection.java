package de.documan.domain;

/** Ein Abschnitt der täglichen Übersicht, in der Reihenfolge der Mail. */
public enum ReportSection implements Choice {
    DOCUMENTS("documents", "Dokumente"),
    PER_DAY("per_day", "Neue Dokumente der letzten 14 Tage"),
    SOURCES("sources", "Herkunft der Dokumente"),
    TYPES("types", "Größte Dokumenttypen"),
    CATEGORIES("categories", "Dokumente nach Kategorie"),
    ACTIVITIES("activities", "Aktivitäten der Benutzer"),
    MAILBOXES("mailboxes", "Postfächer"),
    FOLDERS("folders", "Verzeichnisse"),
    BACKUP("backup", "Sicherung"),
    STORAGE("storage", "Speicher");

    private final String value;
    private final String label;

    ReportSection(String value, String label) {
        this.value = value;
        this.label = label;
    }

    @Override
    public String value() {
        return value;
    }

    @Override
    public String label() {
        return label;
    }
}
