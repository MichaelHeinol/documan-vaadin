-- KI-Zuordnung: Was die KI gesetzt hat, bleibt markiert, bis jemand das Dokument speichert.
-- ai_fields nennt die Felder (correspondent, type, category, date), document_tag.by_ai die Tags.
-- ai_pending: Das Sprachmodell soll sich das Dokument noch ansehen.
ALTER TABLE document ADD COLUMN ai_fields varchar(100) NOT NULL DEFAULT '';
ALTER TABLE document ADD COLUMN ai_pending boolean NOT NULL DEFAULT false;
ALTER TABLE document_tag ADD COLUMN by_ai boolean NOT NULL DEFAULT false;

CREATE INDEX document_ai_pending_idx ON document (id) WHERE ai_pending;

-- Zustand der KI für die Übersicht, genau eine Zeile.
CREATE TABLE ai_status (
    id                smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    learned_at        timestamptz,
    learned_documents integer     NOT NULL DEFAULT 0,
    model_ok_at       timestamptz,
    model_error       text        NOT NULL DEFAULT '',
    model_error_at    timestamptz,
    assigned          integer     NOT NULL DEFAULT 0
);
INSERT INTO ai_status (id) VALUES (1);
