package de.documan.service;

/** Verarbeitung fehlgeschlagen. Der Text erscheint beim Dokument. */
public class ProcessingException extends Exception {

    public ProcessingException(String message) {
        super(message);
    }
}
