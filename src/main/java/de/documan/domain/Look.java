package de.documan.domain;

/** Aussehen der Oberfläche. Die Farben und Formen stehen in {@code looks.css}. */
public enum Look implements Choice {
    STANDARD("standard", "Standard"),
    WIN11("win11", "Windows 11"),
    WIN10("win10", "Windows 10"),
    WIN7("win7", "Windows 7"),
    VISTA("vista", "Windows Vista"),
    WINXP("winxp", "Windows XP"),
    MACOS("macos", "macOS"),
    UBUNTU("ubuntu", "Ubuntu"),
    GNOME("gnome", "GNOME"),
    KDE("kde", "KDE Plasma");

    private final String value;
    private final String label;

    Look(String value, String label) {
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
