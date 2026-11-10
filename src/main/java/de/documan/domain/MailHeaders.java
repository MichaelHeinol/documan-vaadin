package de.documan.domain;

import java.time.Instant;

/** Kopfzeilen der E-Mail, aus der ein Dokument stammt. Andere Dokumente haben leere Felder. */
public record MailHeaders(String from, String to, String subject, Instant date, String messageId) {

    public static final MailHeaders NONE = new MailHeaders("", "", "", null, "");
}
