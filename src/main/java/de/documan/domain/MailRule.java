package de.documan.domain;

/** Schließt Nachrichten aus, deren Feld den Text enthält. */
public record MailRule(MailRuleField field, String value) {
}
