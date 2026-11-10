-- Was Benutzer getan haben. Der Name bleibt als Text, gelöschte Konten zählen weiter.
CREATE TABLE activity (
    id       bigserial PRIMARY KEY,
    username varchar(150) NOT NULL,
    kind     varchar(10)  NOT NULL,
    count    integer      NOT NULL DEFAULT 1,
    at       timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX activity_kind_idx ON activity (kind);
CREATE INDEX activity_username_idx ON activity (username);
