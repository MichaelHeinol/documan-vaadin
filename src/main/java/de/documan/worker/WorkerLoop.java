package de.documan.worker;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import de.documan.config.DocuManProperties;
import de.documan.service.DailyReportService;
import de.documan.service.FilingService;
import de.documan.service.InboxFolder;
import de.documan.service.InboxWatchers;
import de.documan.service.IngestService;
import de.documan.service.PipelineService;
import de.documan.service.StorageService;
import de.documan.service.backup.BackupService;
import de.documan.service.mail.MailImportService;

/**
 * Verarbeitet die Warteschlange, den Eingangsordner und die Postfächer.
 *
 * <p>Genau eine Instanz: Sicherungen laufen dadurch nie gleichzeitig mit der Dokumentverarbeitung.
 */
@Component
@Profile("worker")
public class WorkerLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);
    private static final long IDLE_NANOS = 2_000_000_000L;
    private static final long ERROR_RETRY_NANOS = 10_000_000_000L;
    private static final long MAIL_CHECK_NANOS = 30_000_000_000L;
    private static final long BACKUP_CHECK_NANOS = 30_000_000_000L;
    private static final long STORAGE_CHECK_NANOS = 300_000_000_000L;
    private static final long SHUTDOWN_WAIT_MILLIS = 20_000;

    private final DocuManProperties properties;
    private final PipelineService pipeline;
    private final MailImportService mail;
    private final BackupService backup;
    private final IngestService ingest;
    private final FilingService filing;
    private final InboxFolder inbox;
    private final StorageService storage;
    private final DailyReportService dailyReport;
    private volatile boolean stopping;
    private Thread thread;

    public WorkerLoop(DocuManProperties properties, PipelineService pipeline, MailImportService mail,
            BackupService backup, IngestService ingest, FilingService filing, InboxFolder inbox,
            StorageService storage, DailyReportService dailyReport) {
        this.properties = properties;
        this.pipeline = pipeline;
        this.mail = mail;
        this.backup = backup;
        this.ingest = ingest;
        this.filing = filing;
        this.inbox = inbox;
        this.storage = storage;
        this.dailyReport = dailyReport;
    }

    @Override
    public void start() {
        thread = new Thread(this::run, "worker");
        thread.start();
    }

    @Override
    public void stop() {
        stopping = true;
        LockSupport.unpark(thread);
        try {
            thread.join(SHUTDOWN_WAIT_MILLIS);
            if (thread.isAlive()) {
                // Ein laufendes Werkzeug wird beendet, das Dokument kommt beim Neustart zurück in die Warteschlange.
                thread.interrupt();
                thread.join(SHUTDOWN_WAIT_MILLIS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    private void recover() {
        try {
            int interrupted = backup.recoverRuns();
            if (interrupted > 0) {
                log.warn("{} Sicherungslauf/-läufe wurden unterbrochen", interrupted);
            }
        } catch (IOException error) {
            log.error("Aufräumen nach einer Unterbrechung fehlgeschlagen", error);
        }
        int recovered = pipeline.recoverInterrupted();
        if (recovered > 0) {
            log.info("{} unterbrochene Dokumente wieder eingereiht", recovered);
        }
        mail.recoverPolls();
    }

    private void pause(long nanos) {
        long deadline = System.nanoTime() + nanos;
        while (!stopping && System.nanoTime() < deadline) {
            LockSupport.parkNanos(deadline - System.nanoTime());
        }
    }

    private void run() {
        recover();
        InboxWatchers watchers = new InboxWatchers(properties.getConsumeStableSeconds(),
                () -> System.nanoTime() / 1e9, ingest);
        long nextScan = System.nanoTime();
        long nextMail = nextScan;
        long nextBackup = nextScan;
        long nextStorage = nextScan;
        long scanInterval = properties.getConsumePollSeconds() * 1_000_000_000L;
        log.info("Worker gestartet");

        while (!stopping) {
            boolean busy;
            try {
                long now = System.nanoTime();
                if (now - nextScan >= 0) {
                    watchers.scan(inbox.watchedPaths());
                    nextScan = now + scanInterval;
                }
                if (now - nextMail >= 0) {
                    mail.pollDueAccounts();
                    nextMail = now + MAIL_CHECK_NANOS;
                }
                if (now - nextBackup >= 0) {
                    backup.enqueueDue();
                    dailyReport.sendDue(Instant.now());
                    nextBackup = now + BACKUP_CHECK_NANOS;
                }
                if (now - nextStorage >= 0) {
                    storage.measure();
                    nextStorage = now + STORAGE_CHECK_NANOS;
                }
                busy = backup.runNext(() -> stopping) || pipeline.processNext() || filing.refileNext();
            } catch (RuntimeException error) {
                log.error("Fehler in der Worker-Schleife", error);
                pause(ERROR_RETRY_NANOS);
                continue;
            }
            if (!busy) {
                pause(IDLE_NANOS);
            }
        }
        log.info("Worker beendet");
    }
}
