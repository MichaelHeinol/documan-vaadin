-- Über diese Adresse erhalten Benutzer Nachrichten wie die Zugangsmail.
ALTER TABLE app_user ADD COLUMN email varchar(254) NOT NULL DEFAULT '';
