package de.documan.domain;

public enum DocumentSource implements Choice {
    UPLOAD("upload", "Hochgeladen"),
    CONSUME("consume", "Eingangsordner"),
    MAIL("mail", "E-Mail");

    private final String value;
    private final String label;

    DocumentSource(String value, String label) {
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
