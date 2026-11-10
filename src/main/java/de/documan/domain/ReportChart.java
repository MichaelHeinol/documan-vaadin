package de.documan.domain;

/** Darstellung einer Auswertung in der täglichen Übersicht. */
public enum ReportChart implements Choice {
    BARS("bars", "Balken"),
    PIE("pie", "Torte");

    private final String value;
    private final String label;

    ReportChart(String value, String label) {
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
