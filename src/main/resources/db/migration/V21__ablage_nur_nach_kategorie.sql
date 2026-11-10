-- Die Ordner bildet nur noch die Kategorie ab, Absendername und Dokumenttyp stehen allein im Dateinamen.
-- Alle Dateien ziehen einmal um. Dabei bekommen die Dokumente ihren Titel aus Absendername, Dokumenttyp
-- und Datum, siehe FilingService.
UPDATE document SET file_sync_pending = true WHERE status IN ('done', 'failed');
