package de.documan.domain;

/** Helligkeit der Oberfläche. */
public enum LookMode implements Choice {
    AUTO("auto", "Wie im System"),
    LIGHT("light", "Hell"),
    DARK("dark", "Dunkel");

    private final String value;
    private final String label;

    LookMode(String value, String label) {
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
