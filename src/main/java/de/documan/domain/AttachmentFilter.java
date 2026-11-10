package de.documan.domain;

public enum AttachmentFilter implements Choice {
    PDF("pdf", "Nur PDF"),
    ALL("all", "PDF und Bilder"),
    FILES("files", "Alle Dateianhänge");

    private final String value;
    private final String label;

    AttachmentFilter(String value, String label) {
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
