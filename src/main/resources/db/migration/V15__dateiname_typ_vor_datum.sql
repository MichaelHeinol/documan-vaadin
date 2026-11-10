-- Im Dateinamen steht der Dokumenttyp jetzt vor dem Belegdatum.
UPDATE document SET file_sync_pending = true WHERE status IN ('done', 'failed');
