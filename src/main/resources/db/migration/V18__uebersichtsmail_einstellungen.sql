-- Einstellungen der täglichen Übersicht. time leer heißt: beim ersten Laden gilt DAILY_REPORT_TIME.
ALTER TABLE daily_report
    ADD COLUMN enabled          boolean      NOT NULL DEFAULT true,
    ADD COLUMN time             time,
    ADD COLUMN weekdays         varchar(13)  NOT NULL DEFAULT '0,1,2,3,4,5,6',
    ADD COLUMN sections         varchar(200) NOT NULL
        DEFAULT 'documents,per_day,sources,types,activities,mailboxes,folders,backup,storage',
    ADD COLUMN sources_chart    varchar(4)   NOT NULL DEFAULT 'bars',
    ADD COLUMN types_chart      varchar(4)   NOT NULL DEFAULT 'bars',
    ADD COLUMN storage_chart    varchar(4)   NOT NULL DEFAULT 'bars',
    ADD COLUMN extra_recipients text         NOT NULL DEFAULT '';

-- Superuser, die die Übersicht nicht bekommen.
CREATE TABLE daily_report_excluded (
    user_id bigint PRIMARY KEY REFERENCES app_user ON DELETE CASCADE
);
