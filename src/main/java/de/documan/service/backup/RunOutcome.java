package de.documan.service.backup;

/** Ergebnis eines Laufs für den Verlauf. */
record RunOutcome(String archive, Long size, Integer documents, Integer files, String message) {
}
