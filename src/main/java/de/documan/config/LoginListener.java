package de.documan.config;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import de.documan.repo.UserRepository;

/** Hält die letzte Anmeldung für die Benutzerliste fest. */
@Component
public class LoginListener {

    private final UserRepository users;

    public LoginListener(UserRepository users) {
        this.users = users;
    }

    @EventListener
    public void onLogin(AuthenticationSuccessEvent event) {
        users.touchLogin(event.getAuthentication().getName());
    }
}
