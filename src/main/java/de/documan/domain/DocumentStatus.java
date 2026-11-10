package de.documan.domain;

public enum DocumentStatus implements Choice {
    PENDING("pending", "Wartet"),
    PROCESSING("processing", "Wird verarbeitet"),
    DONE("done", "Fertig"),
    FAILED("failed", "Fehlgeschlagen");

    private final String value;
    private final String label;

    DocumentStatus(String value, String label) {
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
