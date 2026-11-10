package de.documan.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import de.documan.repo.UserRepository;

/** Legt beim ersten Start das Admin-Konto aus ADMIN_USER und ADMIN_PASSWORD an. */
@Component
@ConditionalOnWebApplication
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final DocuManProperties properties;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public AdminBootstrap(DocuManProperties properties, UserRepository users, PasswordEncoder encoder) {
        this.properties = properties;
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!users.isEmpty()) {
            return;
        }
        if (properties.getAdminPassword().isEmpty()) {
            log.error("ADMIN_PASSWORD fehlt in .env, es wurde kein Konto angelegt.");
            return;
        }
        String username = properties.getAdminUser().isEmpty() ? "admin" : properties.getAdminUser();
        users.insert(username, encoder.encode(properties.getAdminPassword()), true, true, properties.getAdminEmail());
        log.info("Admin-Konto „{}“ angelegt.", username);
    }
}
