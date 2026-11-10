package de.documan.service.backup;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import de.documan.config.DocuManProperties;
import de.documan.service.ProcessRunner;

/** Ruft pg_dump, pg_restore und psql mit den Zugangsdaten der Anwendung auf. */
@Component
public class DatabaseTools {

    private static final Duration TIMEOUT = Duration.ofHours(6);

    private final DocuManProperties properties;
    private final ProcessRunner processes;

    public DatabaseTools(DocuManProperties properties, ProcessRunner processes) {
        this.properties = properties;
        this.processes = processes;
    }

    public void run(List<String> command) {
        Map<String, String> environment = Map.of(
                "PGHOST", properties.getDbHost(),
                "PGPORT", String.valueOf(properties.getDbPort()),
                "PGUSER", properties.getDbUser(),
                "PGPASSWORD", properties.getDbPassword(),
                "PGDATABASE", properties.getDbName());
        try {
            var result = processes.run(command, TIMEOUT, environment, null);
            if (result.exitCode() != 0) {
                String reason = result.lastErrorLine();
                throw new BackupException(command.getFirst() + " fehlgeschlagen: "
                        + (reason.isEmpty() ? "Exit-Code " + result.exitCode() : reason));
            }
        } catch (IOException error) {
            throw new BackupException(command.getFirst() + " fehlgeschlagen: " + error.getMessage(), error);
        }
    }

    public void dump(Path target) {
        run(List.of("pg_dump", "--format=custom", "--file=" + target));
    }

    /** Das SQL entsteht vollständig vorab: Ein abgebrochener Datenstrom bliebe sonst unbemerkt. */
    public void renderScript(Path dump, Path script) {
        run(List.of("pg_restore", "--no-owner", "--no-privileges", "--file=" + script, dump.toString()));
    }

    /** Ersetzt den gesamten Inhalt der Datenbank in einer einzigen Transaktion. */
    public void replaceDatabase(Path script) {
        run(List.of("psql", "--no-psqlrc", "--quiet", "--set=ON_ERROR_STOP=1", "--single-transaction",
                "--command=DROP SCHEMA public CASCADE; CREATE SCHEMA public;", "--file=" + script));
    }
}
