-- Jedes Postfach hat seinen eigenen Abrufabstand.
ALTER TABLE mail_account ADD COLUMN poll_minutes integer NOT NULL DEFAULT 60;
