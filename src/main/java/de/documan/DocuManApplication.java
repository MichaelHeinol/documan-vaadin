package de.documan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Rolle web: Oberfläche. Rolle worker (Profil "worker"): Warteschlange, Eingänge, Sicherungen. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DocuManApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocuManApplication.class, args);
    }
}
