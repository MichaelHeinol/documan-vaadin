package de.documan.service;

import java.util.regex.Pattern;

/** Macht Dateinamen aus fremder Hand für das Dateisystem und für Downloads unbedenklich. */
public final class FileNames {

    private static final Pattern UNWANTED = Pattern.compile("[^-\\w.]", Pattern.UNICODE_CHARACTER_CLASS);

    private FileNames() {
    }

    /** Leerzeichen werden zu Unterstrichen, alles außer Buchstaben, Ziffern, Punkt und Bindestrich entfällt. */
    public static String valid(String name) {
        String cleaned = UNWANTED.matcher(name.strip().replace(' ', '_')).replaceAll("");
        return cleaned.equals(".") || cleaned.equals("..") ? "" : cleaned;
    }

    /** Dateiname ohne Pfad. Auch Windows-Pfade fremder Geräte werden abgeschnitten. */
    public static String baseName(String name) {
        String normalized = name.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    /** Dateiname ohne Pfad und ohne letzte Endung. */
    public static String stem(String name) {
        String base = baseName(name);
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    /** Letzte Endung samt Punkt oder leer. */
    public static String suffix(String name) {
        String base = baseName(name);
        int dot = base.lastIndexOf('.');
        return dot > 0 && dot < base.length() - 1 ? base.substring(dot) : "";
    }
}
