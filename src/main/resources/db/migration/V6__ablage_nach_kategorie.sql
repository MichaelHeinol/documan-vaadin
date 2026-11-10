-- Vorhandene Dateien ziehen beim nächsten Lauf des Workers in die neuen Ordner.
UPDATE document SET file_sync_pending = true WHERE status IN ('done', 'failed');
