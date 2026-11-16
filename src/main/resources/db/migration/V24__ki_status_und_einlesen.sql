-- Welches Dokument das Sprachmodell gerade analysiert, damit die Oberfläche es anzeigen kann.
ALTER TABLE ai_status ADD COLUMN current_document_id bigint;
ALTER TABLE ai_status ADD COLUMN current_since timestamptz;

-- Aufträge der Oberfläche an den Worker, etwa die Eingangsordner sofort einzulesen. Eine Zeile je Art.
CREATE TABLE worker_request (
    kind         varchar(20) PRIMARY KEY,
    requested_at timestamptz,
    requested_by varchar(150) NOT NULL DEFAULT '',
    handled_at   timestamptz,
    result       varchar(500) NOT NULL DEFAULT ''
);
INSERT INTO worker_request (kind) VALUES ('consume');
