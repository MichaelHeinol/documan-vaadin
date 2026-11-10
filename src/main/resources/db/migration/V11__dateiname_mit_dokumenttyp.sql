-- Der Dateiname enthält jetzt den Dokumenttyp und trennt seine Teile mit Unterstrichen.
UPDATE document SET file_sync_pending = true WHERE status IN ('done', 'failed');
