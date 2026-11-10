package de.documan.domain;

public enum MailSecurity implements Choice {
    SSL("ssl", "SSL/TLS"),
    STARTTLS("starttls", "STARTTLS"),
    NONE("none", "Keine Verschlüsselung");

    private final String value;
    private final String label;

    MailSecurity(String value, String label) {
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
