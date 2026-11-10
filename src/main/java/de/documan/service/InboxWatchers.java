package de.documan.service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ein Beobachter je Eingangsordner. Ein entfernter vergisst, was dort beobachtet wurde. */
public class InboxWatchers {

    private static final Logger log = LoggerFactory.getLogger(InboxWatchers.class);

    private final double stableSeconds;
    private final DoubleSupplier clock;
    private final IngestService ingest;
    private Map<Path, FolderWatcher> watchers = new LinkedHashMap<>();

    public InboxWatchers(double stableSeconds, DoubleSupplier clock, IngestService ingest) {
        this.stableSeconds = stableSeconds;
        this.clock = clock;
        this.ingest = ingest;
    }

    public int scan(List<Path> roots) {
        Map<Path, FolderWatcher> current = new LinkedHashMap<>();
        for (Path root : roots) {
            FolderWatcher watcher = watchers.get(root);
            if (watcher == null) {
                log.info("Überwache Eingangsordner {}", root);
                watcher = new FolderWatcher(root, stableSeconds, clock, ingest);
            }
            current.put(root, watcher);
        }
        watchers = current;
        int imported = 0;
        for (FolderWatcher watcher : current.values()) {
            imported += watcher.scan();
        }
        return imported;
    }
}
