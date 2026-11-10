package de.documan.domain;

public enum MailAuth implements Choice {
    PASSWORD("password", "Passwort"),
    MICROSOFT("microsoft", "Microsoft-Anmeldung");

    private final String value;
    private final String label;

    MailAuth(String value, String label) {
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
