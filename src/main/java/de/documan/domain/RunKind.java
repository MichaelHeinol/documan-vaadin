package de.documan.domain;

public enum RunKind implements Choice {
    BACKUP("backup", "Sicherung"),
    RESTORE("restore", "Wiederherstellung"),
    EXPORT("export", "Umzug erstellen"),
    IMPORT("import", "Umzug einspielen");

    private final String value;
    private final String label;

    RunKind(String value, String label) {
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
