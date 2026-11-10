package de.documan.domain;

/** Was ein Benutzer getan hat. */
public enum ActivityKind {
    UPLOADED("uploaded"),
    FILED("filed"),
    EDITED("edited"),
    DELETED("deleted");

    private final String value;

    ActivityKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
