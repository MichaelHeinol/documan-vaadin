package de.documan.domain;

public enum BackupFrequency implements Choice {
    DAILY("daily", "Täglich"),
    WEEKLY("weekly", "Wöchentlich");

    private final String value;
    private final String label;

    BackupFrequency(String value, String label) {
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
