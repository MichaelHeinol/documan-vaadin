package de.documan.ui;

/**
 * HTML für Teile ohne Bedienung, etwa Diagramme und Tabellen. Jeder Text läuft durch esc().
 *
 * <p>Links tragen router-link: Der Router von Vaadin wechselt die Seite dann, ohne sie neu zu laden.
 */
final class Markup {

    private Markup() {
    }

    static String esc(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Ein interner Link. inner ist schon fertiges HTML. */
    static String link(String href, String classes, String inner) {
        String target = href.startsWith("/") ? href.substring(1) : href;
        return "<a href=\"" + esc(target) + "\" router-link" + (classes.isEmpty() ? "" : " class=\"" + classes + "\"")
                + ">" + inner + "</a>";
    }

    static String icon(String name) {
        return "<svg class=\"icon\" aria-hidden=\"true\"><use href=\"#i-" + name + "\"/></svg>";
    }

    static String dot(String color) {
        return color == null ? "" : "<i class=\"dot\" style=\"--c: " + esc(color) + "\"></i>";
    }

    static String tag(String name, String classes, String text) {
        return "<" + name + (classes.isEmpty() ? "" : " class=\"" + classes + "\"") + ">" + esc(text) + "</" + name + ">";
    }
}
