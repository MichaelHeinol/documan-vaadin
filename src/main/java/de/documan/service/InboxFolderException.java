package de.documan.service;

/** Ein Ordner lässt sich nicht als Eingangsordner verwenden. Die Meldung richtet sich an den Benutzer. */
public class InboxFolderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InboxFolderException(String message) {
        super(message);
    }
}
