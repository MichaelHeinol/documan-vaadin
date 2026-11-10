-- Der überwachte Eingangsordner. Es gibt höchstens einen Datensatz, ohne ihn gilt der Standardordner.
CREATE TABLE consume_settings (
    id     smallint PRIMARY KEY,
    folder varchar(500) NOT NULL
);
