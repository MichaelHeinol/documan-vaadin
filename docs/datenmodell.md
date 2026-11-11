# Datenmodell

Die Datenbank ist in der Java- und der Vaadin-Version identisch, beide spielen dieselben Flyway-Migrationen ein
(Stand: V22). Die Grafik gibt es als [SVG](datenmodell.svg) und [PDF](datenmodell.pdf). Das Diagramm unten zeigt
GitHub direkt an.

| Bereich | Tabelle | Inhalt |
|---|---|---|
| Dokumente | `document` | Ein Dokument mit Original, Archivfassung, Vorschau, Text, Zuordnung und E-Mail-Angaben |
| Dokumente | `document_tag` | Tags eines Dokuments (n:m) |
| Dokumente | `import_rejection` | Abgelehnte Importe mit Grund, bei Duplikaten mit Verweis auf das vorhandene Dokument |
| Stammdaten | `category` | Kategorien in bis zu zehn Stufen, `parent_id` zeigt auf die Stufe darüber |
| Stammdaten | `correspondent` | Absendernamen mit Regel für die automatische Zuordnung |
| Stammdaten | `document_type` | Dokumenttypen mit Regel und optionaler Kategorie |
| Stammdaten | `tag` | Tags mit Farbe und Regel |
| E-Mail | `mail_account` | Postfächer mit Zugang, Abrufregeln, Fortschritt und Zuordnung neuer Dokumente |
| E-Mail | `mail_account_tag` | Tags, die ein Postfach neuen Dokumenten gibt (n:m) |
| E-Mail | `mail_exclusion_rule` | Ausschlussregeln je Postfach |
| E-Mail | `processed_mail` | Bereits verarbeitete Nachrichten je Postfach, damit nichts doppelt kommt |
| Betrieb | `app_user` | Benutzer mit Rolle, Darstellung und Kontostatus |
| Betrieb | `activity` | Aktivitäten der Benutzer für die Übersicht |
| Betrieb | `daily_report` | Einstellungen der täglichen Übersichtsmail (eine Zeile) |
| Betrieb | `daily_report_excluded` | Superuser, die die Übersichtsmail nicht bekommen |
| Betrieb | `backup_settings` | Zeitplan und Ziel der Datensicherung (eine Zeile) |
| Betrieb | `backup_run` | Aufträge für Sicherung, Wiederherstellung und Umzug mit Fortschritt |
| Betrieb | `consume_folder` | Weitere Eingangsordner |
| Betrieb | `storage_usage` | Zuletzt gemessener Speicherbedarf (eine Zeile) |

Löschregeln: Gelöschte Stammdaten lassen die Dokumente stehen, der Verweis wird leer (`SET NULL`). Mit einem
Dokument oder Postfach verschwinden seine Tag-Zuordnungen, Ausschlussregeln und verarbeiteten Nachrichten
(`CASCADE`). `activity.username` und `document.added_by` sind Texte, keine Fremdschlüssel: Sie bleiben, auch
wenn ein Benutzer gelöscht wird.

```mermaid
erDiagram
    activity {
        bigint id PK
        varchar_150 username
        varchar_10 kind
        int count
        timestamptz at
    }
    app_user {
        bigint id PK
        varchar_150 username UK
        varchar_200 password_hash
        boolean superuser
        boolean active
        timestamptz created
        timestamptz last_login
        varchar_254 email
        varchar_150 first_name
        varchar_150 last_name
        timestamptz pending_since
        varchar_10 look
        varchar_5 mode
    }
    backup_run {
        bigint id PK
        varchar_10 kind
        varchar_30 origin
        varchar_10 status
        varchar_500 archive
        boolean safety_backup
        bigint size
        int documents
        int files
        text message
        text error
        timestamptz queued_at
        timestamptz started_at
        timestamptz finished_at
    }
    backup_settings {
        int id PK
        boolean enabled
        varchar_10 frequency
        smallint weekday
        time time
        varchar_255 folder
        smallint keep
        timestamptz last_scheduled
    }
    category {
        bigint id PK
        varchar_200 name
        varchar_10 algorithm
        varchar_500 match_text
        boolean ignore_case
        bigint parent_id FK
    }
    consume_folder {
        bigint id PK
        varchar_500 folder UK
    }
    correspondent {
        bigint id PK
        varchar_200 name UK
        varchar_10 algorithm
        varchar_500 match_text
        boolean ignore_case
    }
    daily_report {
        smallint id PK
        date sent_on
        boolean enabled
        time time
        varchar_13 weekdays
        varchar_200 sections
        varchar_4 sources_chart
        varchar_4 types_chart
        varchar_4 storage_chart
        text extra_recipients
        varchar_4 categories_chart
    }
    daily_report_excluded {
        bigint user_id PK,FK
    }
    document {
        bigint id PK
        varchar_300 title
        bigint correspondent_id FK
        bigint document_type_id FK
        date document_date
        text notes
        boolean inbox
        text content
        int page_count
        varchar_500 original
        varchar_500 archive
        varchar_500 thumbnail
        varchar_500 original_filename
        varchar_100 mime_type
        bigint size
        varchar_64 checksum UK
        varchar_10 source
        varchar_500 source_detail
        varchar_10 status
        text error
        smallint attempts
        boolean file_sync_pending
        timestamptz added
        timestamptz modified
        tsvector search_vector
        varchar_150 added_by
        varchar_500 mail_from
        varchar_1000 mail_to
        varchar_500 mail_subject
        timestamptz mail_date
        varchar_500 mail_message_id
        bigint category_id FK
    }
    document_tag {
        bigint document_id PK,FK
        bigint tag_id PK,FK
    }
    document_type {
        bigint id PK
        varchar_200 name UK
        varchar_10 algorithm
        varchar_500 match_text
        boolean ignore_case
        bigint category_id FK
    }
    import_rejection {
        bigint id PK
        timestamptz created
        varchar_20 source
        varchar_500 filename
        varchar_1000 reason
        varchar_150 added_by
        varchar_500 source_detail
        bigint existing_document_id FK
    }
    mail_account {
        bigint id PK
        varchar_100 name UK
        boolean enabled
        varchar_255 host
        int port
        varchar_10 security
        varchar_255 username
        text password_encrypted
        varchar_255 folder
        boolean only_unseen
        int maximum_age_days
        varchar_255 filter_from
        varchar_255 filter_subject
        varchar_5 attachments
        varchar_10 after_import
        varchar_255 target_folder
        bigint assign_correspondent_id FK
        bigint assign_document_type_id FK
        timestamptz last_check_at
        text last_error
        int imported_count
        varchar_10 auth_method
        boolean skip_spam
        timestamptz poll_started_at
        int poll_done
        int poll_total
        int poll_minutes
    }
    mail_account_tag {
        bigint mail_account_id PK,FK
        bigint tag_id PK,FK
    }
    mail_exclusion_rule {
        bigint id PK
        bigint account_id FK
        varchar_10 field
        varchar_255 value
    }
    processed_mail {
        bigint id PK
        bigint account_id FK
        varchar_500 message_id
        varchar_500 subject
        timestamptz processed_at
    }
    storage_usage {
        smallint id PK
        bigint media
        bigint backups
        timestamptz measured_at
    }
    tag {
        bigint id PK
        varchar_200 name UK
        varchar_10 algorithm
        varchar_500 match_text
        boolean ignore_case
        varchar_7 color
    }
    category |o--o{ category : "parent_id"
    app_user ||--o{ daily_report_excluded : "user_id"
    category |o--o{ document : "category_id"
    correspondent |o--o{ document : "correspondent_id"
    document_type |o--o{ document : "document_type_id"
    document ||--o{ document_tag : "document_id"
    tag ||--o{ document_tag : "tag_id"
    category |o--o{ document_type : "category_id"
    document |o--o{ import_rejection : "existing_document_id"
    correspondent |o--o{ mail_account : "assign_correspondent_id"
    document_type |o--o{ mail_account : "assign_document_type_id"
    mail_account ||--o{ mail_account_tag : "mail_account_id"
    tag ||--o{ mail_account_tag : "tag_id"
    mail_account ||--o{ mail_exclusion_rule : "account_id"
    mail_account ||--o{ processed_mail : "account_id"
```

## Neu erzeugen

Nach einer neuen Migration: eine leere Datenbank starten, die App einmal dagegen hochfahren, damit Flyway alle
Migrationen einspielt, und das Schema auslesen. `tools/datenmodell/datenmodell.py` erwartet in einem Ordner die
Dateien `columns.txt`, `fks.txt` und `uniques.txt` (Ausgabe von `psql -tA -F'|'` auf `information_schema`, die
Abfragen stehen im Skript-Kopf) und schreibt daraus `datenmodell.mmd` und `datenmodell.dot`. Graphviz macht
aus der `.dot`-Datei SVG und PDF:

```bash
dot -Tsvg datenmodell.dot -o docs/datenmodell.svg
```

```bash
dot -Tpdf datenmodell.dot -o docs/datenmodell.pdf
```
