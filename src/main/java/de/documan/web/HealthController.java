package de.documan.web;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Für den Healthcheck des Containers: antwortet, sobald die Datenbank erreichbar ist. */
@RestController
public class HealthController {

    private final JdbcClient jdbc;

    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping(value = "/healthz", produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
        return "ok";
    }
}
