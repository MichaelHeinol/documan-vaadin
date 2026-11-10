package de.documan.ui;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.shared.Registration;

/**
 * Ruft eine Aktion regelmäßig auf der Seite auf und schickt das Ergebnis per Push.
 *
 * <p>Anders als das Abfragen durch den Browser zählt das nicht als Bedienung. Eine offene Seite hält die
 * Sitzung so nicht über die Stunde hinaus am Leben.
 */
final class Ticker {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "page-ticker");
        thread.setDaemon(true);
        return thread;
    });

    private Ticker() {
    }

    static Registration every(UI ui, long millis, Runnable action) {
        ScheduledFuture<?> future = TIMER.scheduleWithFixedDelay(() -> {
            try {
                ui.access(action::run);
            } catch (RuntimeException closed) {
                // Die Seite ist geschlossen, die Abmeldung entfernt den Ticker.
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }
}
