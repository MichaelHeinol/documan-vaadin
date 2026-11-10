-- Jeder Benutzer pflegt seinen Namen selbst.
ALTER TABLE app_user ADD COLUMN first_name varchar(150) NOT NULL DEFAULT '';
ALTER TABLE app_user ADD COLUMN last_name varchar(150) NOT NULL DEFAULT '';

-- Gesetzt, solange eine Registrierung auf die Bestätigung per E-Mail wartet.
-- Das unterscheidet sie von Konten, die ein Superuser gesperrt hat.
ALTER TABLE app_user ADD COLUMN pending_since timestamptz;
