-- Stand des laufenden Abrufs: Der Worker schreibt ihn, die Oberfläche liest ihn.
ALTER TABLE mail_account
    ADD COLUMN poll_started_at timestamptz,
    ADD COLUMN poll_done integer NOT NULL DEFAULT 0,
    ADD COLUMN poll_total integer NOT NULL DEFAULT 0;
