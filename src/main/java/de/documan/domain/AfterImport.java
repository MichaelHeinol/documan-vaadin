package de.documan.domain;

public enum AfterImport implements Choice {
    MARK_SEEN("seen", "Als gelesen markieren"),
    MOVE("move", "In Ordner verschieben"),
    DELETE("delete", "Löschen"),
    NOTHING("nothing", "Unverändert lassen");

    private final String value;
    private final String label;

    AfterImport(String value, String label) {
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
