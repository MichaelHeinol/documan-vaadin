-- Was der Worker zuletzt an Dateien gezählt hat. Es gibt höchstens einen Datensatz.
CREATE TABLE storage_usage (
    id          smallint PRIMARY KEY,
    media       bigint NOT NULL,
    backups     bigint NOT NULL,
    measured_at timestamptz NOT NULL
);

-- Wie DocuMan für einen Benutzer aussieht.
ALTER TABLE app_user
    ADD COLUMN look varchar(10) NOT NULL DEFAULT 'standard',
    ADD COLUMN mode varchar(5) NOT NULL DEFAULT 'auto';
