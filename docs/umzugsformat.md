# Umzugsformat

Das Umzugsarchiv bringt Dokumente und Stammdaten von der Django- in die Java-Version und zurück.
Beide Versionen schreiben und lesen genau dieses Format. Datensicherungen bleiben davon getrennt, denn
sie enthalten einen Datenbank-Dump, der nur zur eigenen Version passt.

## Datei

Name: `documan-umzug-JJJJMMTT-HHMMSS-<quelle>.zip`, wobei `<quelle>` `django` oder `java` ist.

Die Einträge stehen in dieser Reihenfolge im ZIP:

| Eintrag | Inhalt |
| --- | --- |
| `manifest.json` | Angaben zum Archiv |
| `stammdaten.json` | Absendernamen, Dokumenttypen, Tags, Benutzer, Postfächer |
| `dokumente.jsonl` | Ein JSON-Objekt je Zeile und Dokument, UTF-8 |
| `media/...` | Die Dateien mit ihrem Pfad relativ zum Dokumentenordner |
| `checksums.json` | SHA-256 aller Einträge außer `manifest.json` und `checksums.json` |

Fehlt `checksums.json`, ist das Archiv unvollständig. Bereits komprimierte Dateien (`.gif`, `.jpeg`,
`.jpg`, `.png`, `.webp`) werden nur gespeichert. Das Archiv nutzt ZIP64. Bis zum fertigen Schreiben
trägt die Datei die Endung `.part`.

### manifest.json

```json
{
  "format": "documan-umzug",
  "version": 1,
  "source": "django",
  "created": "2026-11-01T10:00:00+01:00",
  "documents": 2,
  "files": 6
}
```

Andere Werte für `format` oder `version` werden abgelehnt. `files` zählt die Einträge unter `media/`.

### stammdaten.json

```json
{
  "correspondents": [
    {"name": "Stadtwerke", "algorithm": "any", "match": "Strom", "ignore_case": true}
  ],
  "document_types": [
    {"name": "Rechnung", "algorithm": "none", "match": "", "ignore_case": true}
  ],
  "tags": [
    {"name": "Steuer", "algorithm": "none", "match": "", "ignore_case": true, "color": "#3f7d6e"}
  ],
  "users": [
    {"username": "anna", "email": "anna@example.de", "is_admin": false, "active": true}
  ],
  "mail_accounts": [
    {
      "name": "Rechnungen",
      "host": "imap.example.de",
      "port": 993,
      "security": "ssl",
      "username": "rechnung@example.de",
      "folder": "INBOX",
      "only_unseen": true,
      "poll_minutes": 60,
      "maximum_age_days": 30,
      "filter_from": "",
      "filter_subject": "",
      "attachments": "pdf",
      "after_import": "seen",
      "target_folder": "",
      "auth_method": "password",
      "skip_spam": true,
      "exclusion_rules": [{"field": "from", "value": "@werbung.example"}],
      "assign_correspondent": "Stadtwerke",
      "assign_document_type": null,
      "assign_tags": ["Steuer"],
      "processed": [{"message_id": "<1@example.de>", "subject": "Rechnung"}]
    }
  ]
}
```

Verweise stehen als Name, nicht als ID. Passwörter sind bewusst nicht enthalten, weder die der
Benutzer noch die der Postfächer.

Zu jedem Dokumenttyp steht zusätzlich `"category"` mit seiner Kategorie als Liste der Stufen, oberste
zuerst, etwa `["Finanzen", "Bank"]`, leer ohne. Dasselbe Feld steht in `dokumente.jsonl` für eine eigene
Kategorie des Dokuments, die der des Typs vorgeht. Beim Einspielen werden fehlende Stufen angelegt,
höchstens zehn. Die Werte sind optional: Ältere Archive ohne Kategorien lassen sich weiter einspielen.

### dokumente.jsonl

Jede Zeile ist ein Objekt mit diesen Feldern:

```json
{
  "title": "Stromrechnung",
  "correspondent": "Stadtwerke",
  "document_type": "Rechnung",
  "tags": ["Steuer"],
  "document_date": "2026-03-14",
  "notes": "",
  "inbox": false,
  "content": "Erkannter Text",
  "page_count": 2,
  "original": "originals/Stadtwerke/Rechnung/Stadtwerke_Rechnung_20260314.pdf",
  "archive": "archive/Stadtwerke/Rechnung/Stadtwerke_Rechnung_20260314.pdf",
  "thumbnail": "thumbnails/Stadtwerke/Rechnung/Stadtwerke_Rechnung_20260314.jpg",
  "original_filename": "scan.pdf",
  "mime_type": "application/pdf",
  "size": 12345,
  "checksum": "64 Hexzeichen, SHA-256 des Originals",
  "source": "upload",
  "source_detail": "",
  "added_by": "anna",
  "mail_from": "",
  "mail_to": "",
  "mail_subject": "",
  "mail_date": null,
  "mail_message_id": "",
  "status": "done",
  "error": "",
  "added": "2026-03-15T09:30:00+01:00",
  "modified": "2026-03-16T10:00:00+01:00"
}
```

`correspondent`, `document_type`, `document_date`, `page_count` und `mail_date` dürfen `null` sein.
`archive` und `thumbnail` sind leer, wenn es die Datei nicht gibt. Zeitpunkte sind ISO-8601 mit Zeitzone.

`added_by` und die `mail_`-Felder sind optional, damit ältere Archive der Version 1 lesbar bleiben.
Fehlen sie, gelten eine leere Zeichenkette und `null`.

`auth_method` im Postfach ist `password` oder `microsoft` und ebenfalls optional, ohne Angabe gilt `password`.
Der Refresh-Token der Microsoft-Anmeldung steht wie jedes Passwort nicht im Archiv.

`poll_minutes`, `skip_spam` und `exclusion_rules` im Postfach sind ebenfalls optional. Ohne Angabe gelten 60 Minuten,
`true` und keine Regeln.
`field` einer Regel ist `from`, `to` oder `subject`, Regeln mit leerem `value` werden übergangen.

`first_name` und `last_name` im Benutzer sind optional, ohne Angabe bleiben sie leer.
Eine noch nicht bestätigte Registrierung zieht als inaktives Konto mit.

## Erstellen

- Das Archiv entsteht im Worker im aktuellen Zielordner der Datensicherung.
- Dokumente, deren Originaldatei fehlt, werden ausgelassen und im Lauf genannt.
- Fehlen Archiv- oder Vorschaudatei, bleibt das Feld leer.
- Verschwindet eine Datei während des Schreibens, schlägt der Lauf fehl.

## Einspielen

1. Das Ziel darf keine Dokumente enthalten. Sonst bricht der Lauf ab, bevor etwas verändert wird.
2. Das Archiv wird vollständig ausgepackt und jede Prüfsumme geprüft.
3. Erlaubt sind unter `media/` nur `originals/`, `archive/` und `thumbnails/`, keine absoluten
   Pfade, kein `..`, kein Backslash und keine Pfadteile mit führendem Punkt. Jede im Dokument genannte
   Datei muss im Archiv liegen, unbekannte Werte für `algorithm`, `status`, `source`, `security`,
   `attachments` und `after_import` lehnen den Import ab.
4. Alle Datenbankänderungen laufen in einer Transaktion. Scheitert sie, werden die verschobenen Dateien
   zurückgelegt. Der Ordner `.umzug` im Dokumentenordner dient dabei als Zwischenablage.
5. Stammdaten werden über den Namen zugeordnet, Vorhandenes bleibt unverändert.
6. Benutzer werden über den Benutzernamen zugeordnet. Vorhandene behalten Passwort und Rechte, nur eine
   leere E-Mail-Adresse wird ergänzt. Neue Benutzer bekommen kein nutzbares Passwort. Die Administration
   vergibt es neu.
7. Postfächer werden deaktiviert und ohne Passwort angelegt, mit dem Hinweis „Nach dem Umzug bitte das
   Passwort neu eingeben und aktivieren.“
8. Dokumente behalten `added`, `modified`, `status` und `error`. `processing` wird zu `pending`,
   `attempts` beginnt bei 0, `file_sync_pending` ist `true`, und der Suchindex wird neu aufgebaut.
9. Beim Einspielen werden keine E-Mails verschickt.
10. Der Lauf meldet, wie viele Benutzer ein Passwort brauchen und wie viele Postfächer deaktiviert sind.

Das Einstellen des Zeitplans und die Sicherungsläufe gehören nicht zum Umzug.

## Rechte

Erstellen und Einspielen darf nur ein Admin, genau wie die Datensicherung.
