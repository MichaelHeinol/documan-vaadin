-- Bei „microsoft“ liegt in password_encrypted der Refresh-Token statt eines Passworts.
ALTER TABLE mail_account ADD COLUMN auth_method varchar(10) NOT NULL DEFAULT 'password';
