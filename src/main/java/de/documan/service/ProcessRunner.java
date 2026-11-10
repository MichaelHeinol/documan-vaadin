package de.documan.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/** Startet die Kommandozeilenwerkzeuge. Ausgaben gehen in Dateien, damit nichts blockieren kann. */
@Component
public class ProcessRunner {

    public record Result(int exitCode, String stdout, String stderr) {

        /** Letzte Zeile der Fehlerausgabe, dort steht bei allen Werkzeugen die eigentliche Ursache. */
        public String lastErrorLine() {
            List<String> lines = stderr.strip().lines().toList();
            return lines.isEmpty() ? "" : lines.getLast();
        }
    }

    /** Das Werkzeug wurde nach Ablauf der Frist beendet. */
    public static class TimedOut extends IOException {

        public TimedOut(String message) {
            super(message);
        }
    }

    public Result run(List<String> command, Duration timeout) throws IOException {
        return run(command, timeout, Map.of(), null);
    }

    public Result run(List<String> command, Duration timeout, Map<String, String> environment, byte[] input)
            throws IOException {
        Path stdout = Files.createTempFile("documan-out-", ".txt");
        Path stderr = Files.createTempFile("documan-err-", ".txt");
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile());
            builder.environment().putAll(environment);
            Process process = builder.start();
            writeInput(process, input);
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                kill(process);
                throw new TimedOut(command.getFirst() + " nach " + timeout.toSeconds()
                        + " Sekunden abgebrochen.");
            }
            return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                    Files.readString(stderr, StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(command.getFirst() + " wurde unterbrochen.", interrupted);
        } finally {
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }

    private static void writeInput(Process process, byte[] input) {
        try (OutputStream stream = process.getOutputStream()) {
            if (input != null) {
                stream.write(input);
            }
        } catch (IOException endedEarly) {
            // Werkzeuge dürfen die Eingabe unvollständig lesen.
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
