package de.documan.domain;

public enum RunStatus implements Choice {
    QUEUED("queued", "Wartet"),
    RUNNING("running", "Läuft"),
    DONE("done", "Fertig"),
    FAILED("failed", "Fehlgeschlagen");

    private final String value;
    private final String label;

    RunStatus(String value, String label) {
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
