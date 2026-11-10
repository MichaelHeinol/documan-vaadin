package de.documan.domain;

public enum MailRuleField implements Choice {
    FROM("from", "Absender"),
    TO("to", "Empfänger"),
    SUBJECT("subject", "Betreff");

    private final String value;
    private final String label;

    MailRuleField(String value, String label) {
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
