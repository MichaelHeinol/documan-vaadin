-- Der Ordner „Ohne Korrespondent“ heißt jetzt „Ohne Absendername“.
UPDATE document SET file_sync_pending = true
WHERE correspondent_id IS NULL AND status IN ('done', 'failed');
