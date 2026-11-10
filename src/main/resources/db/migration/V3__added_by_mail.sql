-- Wer ein Dokument eingespielt hat und die Kopfzeilen der E-Mail, aus der es stammt.
ALTER TABLE document
    ADD COLUMN added_by        varchar(150)  NOT NULL DEFAULT '',
    ADD COLUMN mail_from       varchar(500)  NOT NULL DEFAULT '',
    ADD COLUMN mail_to         varchar(1000) NOT NULL DEFAULT '',
    ADD COLUMN mail_subject    varchar(500)  NOT NULL DEFAULT '',
    ADD COLUMN mail_date       timestamptz,
    ADD COLUMN mail_message_id varchar(500)  NOT NULL DEFAULT '';

-- Uploads bleiben leer, bisher wurde nicht festgehalten, wer sie eingespielt hat.
UPDATE document SET added_by = 'Eingangsordner' WHERE source = 'consume';
UPDATE document SET added_by = 'E-Mail' WHERE source = 'mail';

-- Bisher stand „Absender: Betreff“ in source_detail.
UPDATE document
SET mail_from = split_part(source_detail, ': ', 1),
    mail_subject = CASE WHEN position(': ' IN source_detail) > 0
                        THEN substr(source_detail, position(': ' IN source_detail) + 2) ELSE '' END
WHERE source = 'mail' AND source_detail <> '';

-- Absender und Betreff gehören in den Suchindex, siehe Reindexer.
UPDATE document
SET search_vector = COALESCE(search_vector, ''::tsvector) || setweight(to_tsvector('german', concat_ws(' ',
        mail_from, translate(mail_from, '<>@.', '    '), mail_subject)), 'B')
WHERE source = 'mail' AND source_detail <> '';
