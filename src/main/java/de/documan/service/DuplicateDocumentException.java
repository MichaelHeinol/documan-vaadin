package de.documan.service;

import de.documan.domain.Document;

public class DuplicateDocumentException extends IngestException {

    private final transient Document existing;

    public DuplicateDocumentException(Document existing) {
        super("Bereits vorhanden als „" + existing.title() + "“.");
        this.existing = existing;
    }

    public Document existing() {
        return existing;
    }
}
