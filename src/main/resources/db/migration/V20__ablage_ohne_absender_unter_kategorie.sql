-- Mit Kategorie entfällt der Ordner des Absendernamens. Diese Dateien ziehen einmal um.
UPDATE document d SET file_sync_pending = true
WHERE d.status IN ('done', 'failed')
  AND (d.category_id IS NOT NULL OR EXISTS (
      SELECT 1 FROM document_type t WHERE t.id = d.document_type_id AND t.category_id IS NOT NULL));
