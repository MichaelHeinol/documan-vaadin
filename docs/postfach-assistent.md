# Postfach-Assistent mit Hoster-Vorlagen

Wunsch des Users im Wortlaut: „baue noch moderne assistenten für die EMail-Verbindung ein sowie entspr. vorlagen für die
Hoster ionos, stato, web.de gmail, microsoft“ und „versuche die Verbindungsparameter von den jeweiligen Hostern
vorzublenden“. Der User hat entschieden: Für Microsoft wird OAuth eingebaut. Beide Versionen gleich, Java baut parallel.
Kein Commit, kein Push, nichts veröffentlichen, NAS nicht anfassen.

## Vorlagen

| Schlüssel | Anzeige | IMAP-Server | Port | Verschlüsselung | Anmeldung |
|---|---|---|---|---|---|
| ionos | IONOS | imap.ionos.de | 993 | SSL/TLS | Passwort |
| strato | STRATO | imap.strato.de | 993 | SSL/TLS | Passwort |
| webde | WEB.DE | imap.web.de | 993 | SSL/TLS | Passwort |
| gmail | Gmail | imap.gmail.com | 993 | SSL/TLS | App-Passwort |
| microsoft | Microsoft | outlook.office365.com | 993 | SSL/TLS | OAuth |
| other | Anderer Anbieter | leer | 993 | SSL/TLS | Passwort |

Benutzername ist immer die vollständige Adresse. Texte, wörtlich in beiden Versionen:

- ionos, Hinweis: „Benutzername ist Ihre E-Mail-Adresse. Verwenden Sie das Passwort des Postfachs, nicht das Ihres IONOS-Kundenkontos.“
- strato, Hinweis: „Benutzername ist Ihre E-Mail-Adresse. Verwenden Sie das Passwort, das Sie beim Einrichten des Postfachs vergeben haben.“
- webde, Hinweis: „Schalten Sie zuerst bei WEB.DE unter E-Mail-Einstellungen, POP3/IMAP-Abruf den Zugriff ein. Mit Zwei-Faktor-Schutz brauchen Sie ein anwendungsspezifisches Passwort.“
- gmail, Hinweis: „Google akzeptiert hier nicht Ihr normales Passwort. Schalten Sie die Bestätigung in zwei Schritten ein und erstellen Sie ein App-Passwort.“ Link „App-Passwort erstellen“ auf https://myaccount.google.com/apppasswords
- microsoft, Hinweis: „Microsoft erlaubt keine Anmeldung mit Passwort mehr. Sie melden sich stattdessen im Browser bei Microsoft an, DocuMan speichert nur den erlaubten Zugriff.“
- other, Hinweis: „Die Angaben finden Sie in der Hilfe Ihres Anbieters unter IMAP.“

Hinweis bei abgelehnter Anmeldung im Verbindungstest (loginHint):
- ionos, strato: „Prüfen Sie das Postfach-Passwort.“
- webde: „Ist der POP3/IMAP-Abruf bei WEB.DE eingeschaltet?“
- gmail: „Gmail braucht ein App-Passwort, nicht Ihr normales Passwort.“
- microsoft: „Bitte melden Sie sich erneut bei Microsoft an.“
- other: „Prüfen Sie Benutzername und Passwort.“

## Erkennung

Domain der Adresse, kleingeschrieben:
- gmail: gmail.com, googlemail.com
- webde: web.de
- microsoft: outlook.com, outlook.de, hotmail.com, hotmail.de, live.com, live.de, msn.com

Sonst MX-Abfrage, 2 Sekunden Grenze, jeder Fehler heißt „nicht erkannt“. Endet ein MX-Host (kleingeschrieben, ohne Punkt am Ende) auf:
- ionos: .ionos.de, .ionos.com, .kundenserver.de, .1and1.com
- strato: .rzone.de, .strato.de
- gmail: .google.com, .googlemail.com
- webde: .web.de
- microsoft: .protection.outlook.com

Bestimmt wird nach dem MX mit der kleinsten Priorität. Die Domain wird vorher geprüft (`^[a-z0-9.-]{1,253}$`, sonst nicht erkannt).

## Ablauf

Adressen, Java zuerst, Django entsprechend mit seinen Schrägstrichen:

1. `GET /postfaecher/neu`: Schritt 1. Feld „E-Mail-Adresse“, Kacheln der sechs Vorlagen. GET-Formular auf Schritt 2, jede Kachel ist ein Submit-Knopf `vorlage=<schlüssel>`, dazu „Weiter“ ohne Vorlage.
2. `GET /postfaecher/erkennen?adresse=...`: JSON `{provider, label, host, port, security}` oder `{provider: null}`. Beim Tippen (abgeprallt 400 ms) markiert die Seite die Kachel und zeigt „Erkannt: IONOS · imap.ionos.de · Port 993 · SSL/TLS“.
3. `GET /postfaecher/neu/einrichten?vorlage=&adresse=`: das bestehende Formular, vorausgefüllt: Server, Port, Verschlüsselung, Benutzername = Adresse, Bezeichnung = Adresse. Ohne gültige Vorlage wird erkannt, sonst `other`. Der Hinweis des Anbieters steht oben im Abschnitt „Zugang“. Alle Felder bleiben änderbar und sichtbar.
4. Im Formular: Knopf „Verbindung testen“, POST `/postfaecher/testen`, JSON `{ok, folders}` oder `{ok: false, error, hint}`. Nutzt die Eingaben, speichert nichts. Bei Erfolg kommen die Ordner in eine datalist für „Ordner“ und „Zielordner“. Beim Bearbeiten darf das gespeicherte Passwort nur verwendet werden, wenn Server und Benutzername unverändert sind, sonst ginge es an einen fremden Server.
5. Stepper oben: 1 Anbieter, 2 Anmeldung, 3 Abruf. Mit JavaScript zeigt Schritt 2 nur „Zugang“ und Schritt 3 die übrigen Abschnitte, ohne JavaScript oder nach Formularfehlern ist alles sichtbar. Beim Bearbeiten gibt es keinen Stepper.

## Microsoft

Einstellungen: `MICROSOFT_CLIENT_ID` (leer = nicht eingerichtet), `MICROSOFT_TENANT` (Vorgabe `common`). In `.env.example` und im Compose durchreichen, im NAS-Compose ebenso.

Gerätecode-Verfahren gegen `https://login.microsoftonline.com/<tenant>/oauth2/v2.0/devicecode` und `/token`, ohne Client-Secret, Scope `offline_access https://outlook.office.com/IMAP.AccessAsUser.All`.

- Neue Spalte `mail_account.auth_method`, varchar(10), Vorgabe `password`, Werte `password` und `microsoft` (Anzeige „Passwort“, „Microsoft-Anmeldung“).
- Der Refresh-Token liegt verschlüsselt in der vorhandenen Spalte für das Passwort. So gilt alles weiter: Umzug ohne Passwort, „Postfach deaktiviert“, Pflichtprüfung.
- POST `/postfaecher/microsoft/start`: holt den Code, `device_code` bleibt in der Sitzung, JSON `{userCode, verificationUri, interval, expiresIn}`.
- POST `/postfaecher/microsoft/pruefen`: fragt einmal `/token` ab, JSON `{status: "pending"|"done"|"error", error}`. Bei `slow_down` bleibt es pending. Bei Erfolg liegt der verschlüsselte Refresh-Token in der Sitzung, bis das Formular gespeichert oder getestet wird.
- Die Oberfläche zeigt im Abschnitt „Zugang“ statt des Passwortfelds den Knopf „Bei Microsoft anmelden“, danach Code und Link „Code eingeben“ (neuer Tab) und fragt im Intervall nach. Erfolg: „Mit Microsoft verbunden.“ Beim Bearbeiten heißt der Knopf „Neu verbinden“.
- Ohne Client-ID erscheint statt des Knopfs: „Für die Microsoft-Anmeldung fehlt noch die Client-ID. Die Einrichtung steht in der Anleitung unter E-Mail.“
- Beim Abruf: Refresh-Token gegen Access-Token tauschen, einen neuen Refresh-Token sofort speichern, dann XOAUTH2. `invalid_grant` wird zur Meldung „Die Microsoft-Anmeldung ist abgelaufen. Bitte im Postfach neu verbinden.“

## Umzug

`mail_accounts[].auth_method` ist optional, fehlt es, gilt `password`. Die Formatversion bleibt 1. Java ändert `docs/umzugsformat.md`, bitte danach von dort kopieren, die Dateien sollen gleich bleiben.

## Abschluss

Tests für Erkennung (Domain, MX, Fehler), Vorausfüllen, Test ohne Speichern, Passwortschutz bei geändertem Server, Gerätecode mit Attrappe des Token-Servers, Token-Wechsel, Umzug mit und ohne `auth_method`. Assistent hell, dunkel und am Handy im Browser prüfen. README und PDF nachziehen, inklusive Einrichtung der Microsoft-App.

## Stand Java, zum Abgleich

Fertig, 393 Tests grün. Ohne Commit. Quellen zum Nachschlagen in `D:\Programmierung\Claude\DocuMan-Java`:

- Vorlagen und Erkennung: `src/main/java/de/documan/service/mail/MailProviders.java`
- Microsoft: `src/main/java/de/documan/service/mail/MicrosoftLogin.java`, `ImapConnector.java`
- Abläufe: `src/main/java/de/documan/web/MailController.java`
- Oberfläche: `src/main/resources/templates/mail/wizard.html`, `mail/form.html`, Abschnitte „Postfach-Assistent“ in `static/js/app.js`, `.stepper` bis `.device-code` in `static/css/app.css`
- Tests als Vorlage: `src/test/java/de/documan/MailWizardTests.java`
- Anleitung: README, Abschnitte „Arbeiten mit DocuMan“ und „Postfächer bei Microsoft“

Ergänzungen gegenüber dem Text oben:

- Fehlertext bei einer Absage von Microsoft: „Die Anmeldung bei Microsoft ist fehlgeschlagen: <Text>“, der Text endet vor „ Trace ID“.
- Der Stepper steht auch auf Schritt 1. Die Adresse steht mit „Weiter“ in einer Zeile vor den Kacheln, damit die Eingabetaste nicht die erste Kachel auslöst.
- Der Zeitgeber der Sitzung bleibt davon unberührt.
- `docs/umzugsformat.md` aus Java ist aktuell, bitte nach Django kopieren.
- Nach dem Zugriff: Das Abrufen tauscht den Refresh-Token jedes Mal und speichert einen neuen sofort.

Für XOAUTH2 prüft Java mit einem kleinen Test-IMAP-Server die genaue Zeichenfolge `user=<adresse>\x01auth=Bearer <token>\x01\x01`, denn GreenMail kann still auf LOGIN ausweichen.

## Antworten auf die Rückfragen aus Django

- Vorlagen: Host, Port, Sicherheit, Hinweise und App-Passwort stehen oben unter „Vorlagen“.
- Erkennung: Zuerst die festen Domains unter „Erkennung“, sonst MX-Abfrage der Domain. Zeitlimit rund 2 Sekunden insgesamt. Ohne Treffer, bei Fehler oder Zeitüberschreitung gibt `/postfaecher/erkennen` `{provider: null}` zurück, Schritt 2 nimmt dann „Anderer Anbieter“ mit leerem Server.
- Microsoft: Gerätecode, keine Weiterleitung. Client-ID und Mandant kommen nur aus `.env` (`MICROSOFT_CLIENT_ID`, `MICROSOFT_TENANT`, Vorgabe `common`), nie aus dem Formular. Kein Client-Secret. Scope `offline_access https://outlook.office.com/IMAP.AccessAsUser.All`.
- Tokens: Gespeichert wird nur der Refresh-Token, verschlüsselt mit demselben Verfahren und Schlüssel wie die Postfach-Passwörter (aus `SECRET_KEY`), in derselben Spalte. Access-Tokens werden nie gespeichert, jede Verbindung holt einen neuen. Stellt Microsoft dabei einen neuen Refresh-Token aus, wird er sofort gespeichert. Im Assistenten liegt der Token bis zum Speichern verschlüsselt in der Sitzung, ein ungespeichertes Postfach schreibt keinen neuen Token zurück.
- Umzug: Tokens ziehen wie Passwörter nicht mit, nur `auth_method`. Das Postfach kommt deaktiviert an und wird unter „Bearbeiten“ neu verbunden.
- Neue `.env`-Variablen: nur `MICROSOFT_CLIENT_ID` und `MICROSOFT_TENANT`.
