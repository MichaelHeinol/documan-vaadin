package de.documan.domain;

/** Absendername, Dokumenttyp oder Tag samt Regel für die automatische Zuordnung. */
public record MasterItem(long id, String name, Algorithm algorithm, String match, boolean ignoreCase, String color,
        long documentCount) {

    public static final String DEFAULT_COLOR = "#3f7d6e";

    public boolean hasRule() {
        return algorithm != Algorithm.NONE && !match.isEmpty();
    }

    public String ruleLabel() {
        return hasRule() ? algorithm.label() + ": " + match : "Keine";
    }
}
