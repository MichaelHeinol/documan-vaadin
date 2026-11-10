-- Wann die tägliche Übersicht zuletzt verschickt wurde. Es gibt höchstens einen Datensatz.
CREATE TABLE daily_report (
    id      smallint PRIMARY KEY,
    sent_on date     NOT NULL
);
