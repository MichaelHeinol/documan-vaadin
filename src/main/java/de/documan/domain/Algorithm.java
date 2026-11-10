package de.documan.domain;

public enum Algorithm implements Choice {
    NONE("none", "Keine automatische Zuordnung"),
    ANY("any", "Eines der Wörter"),
    ALL("all", "Alle Wörter"),
    LITERAL("literal", "Exakter Text"),
    REGEX("regex", "Regulärer Ausdruck");

    private final String value;
    private final String label;

    Algorithm(String value, String label) {
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
