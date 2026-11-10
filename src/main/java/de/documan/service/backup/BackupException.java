package de.documan.service.backup;

/** Sicherung oder Wiederherstellung fehlgeschlagen. Der Text erscheint in der Oberfläche. */
public class BackupException extends RuntimeException {

    public BackupException(String message) {
        super(message);
    }

    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
