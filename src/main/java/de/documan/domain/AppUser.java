package de.documan.domain;

import java.time.Instant;

public record AppUser(long id, String username, String email, String passwordHash, boolean superuser,
        boolean active, Instant created, Instant lastLogin, String firstName, String lastName, Instant pendingSince) {

    /** Wartet auf die Bestätigung seiner Registrierung. */
    public boolean pending() {
        return pendingSince != null;
    }
}
