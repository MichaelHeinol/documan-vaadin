package de.documan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import de.documan.domain.Look;
import de.documan.domain.LookMode;
import de.documan.repo.UserRepository;
import de.documan.repo.UserRepository.Appearance;

class AppearanceTests extends IntegrationTest {

    private static final String SAVE = "/konto/darstellung";
    private static final RequestPostProcessor BEN = user("ben").roles("USER");

    @Autowired
    UserRepository users;

    @BeforeEach
    void accounts() {
        jdbc.sql("""
                INSERT INTO app_user (username, email, password_hash, superuser, active)
                VALUES ('anna', 'anna@example.de', 'x', false, true), ('ben', 'ben@example.de', 'x', false, true)
                """).update();
    }

    private String dashboard(RequestPostProcessor person) throws Exception {
        return body(page("/", person));
    }









    @Test
    void everyLookHasAStyle() throws Exception {
        String styles;
        try (var stream = getClass().getResourceAsStream("/static/css/looks.css")) {
            styles = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (Look look : Look.values()) {
            if (look != Look.STANDARD) {
                assertThat(styles).contains("[data-look=\"" + look.value() + "\"] {");
            }
        }
    }

}
