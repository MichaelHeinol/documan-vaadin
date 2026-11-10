package de.documan.ui;

/**
 * Eine Seite, die sich selbst auf den neuesten Stand bringt, wenn im Hintergrund etwas passiert ist.
 *
 * <p>Der Rahmen ruft refresh() erst auf, wenn busy() nichts dagegen hat und kein Dialog offen ist. Bis dahin
 * wartet die Aktualisierung, so geht keine Eingabe verloren.
 */
public interface Refreshable {

    void refresh();

    /** Wahr, solange jemand hier etwas eingibt, auswählt oder bearbeitet. */
    default boolean busy() {
        return false;
    }
}
