package de.documan.ui;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

import com.vaadin.flow.shared.Registration;

import de.documan.repo.DocumentRepository;

/**
 * Meldet geöffneten Seiten, dass sich an Dokumenten oder Stammdaten etwas getan hat.
 *
 * <p>Verarbeitung, Eingangsordner und Postfächer laufen im Worker, einem eigenen Prozess. Der Webserver fragt
 * deshalb alle paar Sekunden einen Änderungsstempel aus der Datenbank ab und schickt die Nachricht per Push
 * an die Seiten. Gefragt wird nur, solange jemand eine Seite offen hat.
 */
@Component
@ConditionalOnWebApplication
public class ChangeWatcher implements DisposableBean {

    static final long INTERVAL_SECONDS = 4;

    private static final Logger log = LoggerFactory.getLogger(ChangeWatcher.class);

    private final DocumentRepository documents;
    private final Set<Consumer<Boolean>> listeners = new CopyOnWriteArraySet<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "change-watcher");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String stamp;

    public ChangeWatcher(DocumentRepository documents) {
        this.documents = documents;
        timer.scheduleWithFixedDelay(this::check, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Ruft listener bei jeder Prüfung auf, im Thread des Wächters: mit true, wenn sich seit der letzten
     * Prüfung etwas geändert hat. So kann eine Seite eine verschobene Aktualisierung nachholen, sobald
     * niemand mehr etwas bearbeitet.
     */
    public Registration subscribe(Consumer<Boolean> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    void check() {
        if (listeners.isEmpty()) {
            stamp = null;
            return;
        }
        try {
            String current = documents.changeStamp();
            String previous = stamp;
            stamp = current;
            boolean changed = previous != null && !Objects.equals(previous, current);
            listeners.forEach(listener -> listener.accept(changed));
        } catch (RuntimeException error) {
            // Etwa während einer Wiederherstellung. Beim nächsten Mal wieder.
            log.debug("Änderungsstempel nicht lesbar: {}", error.toString());
        }
    }

    @Override
    public void destroy() {
        timer.shutdownNow();
    }
}
