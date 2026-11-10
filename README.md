# DocuMan (Vaadin)

Selbst gehostetes Dokumentenmanagement für zu Hause, angelehnt an paperless:
Scans, E-Mail-Anhänge und Uploads werden automatisch per OCR durchsuchbar gemacht
und mit Kategorien, Absendernamen, Dokumenttypen und Tags abgelegt.

Dies ist die Vaadin-Version von DocuMan. Sie bietet denselben Funktionsumfang wie die Django- und die
Java-Version. Hintergrunddienst, Datenbank und Sicherungen teilt sie mit der Java-Version, die Oberfläche ist
mit Vaadin gebaut: Seiten aktualisieren sich per Push, ohne neu zu laden.

- **OCR** mit OCRmyPDF/Tesseract (Deutsch und Englisch), Scans werden gedreht und begradigt
- **Volltextsuche** in PostgreSQL mit deutscher Stammformbildung und Teilstrings, Treffer erscheinen beim Tippen
- **Mehrere Eingangsordner** für Netzwerkscanner, funktionieren auch auf SMB- und NFS-Freigaben
- **Postfach-Assistent** mit Vorlagen für IONOS, STRATO, WEB.DE, Gmail und Microsoft
- **Ausschlussregeln und Spam-Schutz** je Postfach
- **IMAP-Import** von PDF- und Bildanhängen, Absender, Empfänger, Betreff und Datum bleiben am Dokument
- **Eingespielt von:** Jedes Dokument merkt sich, woher es kam, die Übersicht zeigt das als 3D-Tortengrafik
- **Automatische Zuordnung** von Absendername, Typ, Tags und Dokumentdatum
- **Datensicherung** als ZIP per Knopfdruck oder Zeitplan, Wiederherstellung auch nach einer Neuinstallation
- **Umzug** von und zur Django-Version, nur für Admins. Mit der Java-Version teilt diese Fassung Datenbank und
  Sicherungen
- **E-Mail-Benachrichtigungen:** Neue Benutzer erhalten ihre Zugangsdaten mit der Adresse des Systems
- **Moderne Oberfläche** auf Deutsch, für Bildschirm und Handy, mit zehn Aussehen und Dunkelmodus
- **Kein Datengefängnis:** Originale bleiben inhaltlich unverändert und liegen als normale Dateien auf dem NAS, sortiert nach Absendername und Dokumenttyp, benannt nach Absender, Dokumenttyp und Belegdatum

## Installation auf dem NAS

Voraussetzung ist Docker mit Compose (Synology: Container Manager, QNAP: Container Station).
Die Vaadin-Version läuft neben der Django- und der Java-Version: Sie hat einen eigenen Projektnamen, eigene
Daten und standardmäßig den Port 8002.

1. Projektordner auf das NAS kopieren, z. B. nach `/volume1/docker/documan-vaadin`.

2. Konfiguration anlegen und die drei Pflichtwerte setzen:

   ```bash
   cp .env.example .env
   openssl rand -hex 32
   ```

   `SECRET_KEY`, `POSTGRES_PASSWORD` und `ADMIN_PASSWORD` in `.env` eintragen.
   Für die ersten beiden je einen eigenen Zufallswert verwenden. Mit `ADMIN_EMAIL` bekommt das
   Admin-Konto beim ersten Start gleich seine E-Mail-Adresse, sonst fragt DocuMan nach der Anmeldung danach.

3. `PUID` und `PGID` in `.env` auf den Benutzer setzen, dem die Dateien gehören sollen:

   ```bash
   id
   ```

4. Datenordner anlegen. Synology erstellt fehlende Ordner nicht selbst und bricht sonst mit
   `Bind mount failed` ab:

   ```bash
   mkdir -p data/media data/postgres consume backup
   ```

   Der Benutzer aus `PUID` muss diese Ordner beschreiben dürfen. DocuMan setzt keine Ordnerrechte:
   Ein Einmal-Container dafür würde auf Synology bei jedem Start als „unerwartet beendet“ gemeldet.

5. Starten. Der erste Bau dauert auf einem NAS einige Minuten:

   ```bash
   docker compose up -d --build
   ```

   In der DSM geht das ohne Kommandozeile: **Container Manager → Projekt → Erstellen**, als Pfad den
   Projektordner wählen und die vorhandene `docker-compose.yml` verwenden.

DocuMan ist danach unter `http://<nas-adresse>:8002` erreichbar.

Das Image wird auf dem NAS selbst gebaut, die Basis-Images gibt es für Intel und ARM (64 Bit).
Im Leerlauf belegen die drei Container zusammen rund 400 MB RAM, das Image rund 580 MB Speicherplatz.
Die Web-Oberfläche startet auf einem aktuellen PC in unter 2 Sekunden. OCR braucht zusätzlich Speicher
und CPU-Zeit, gemessen wurden rund 2 Sekunden je Seite, auf schwachen Geräten kann es deutlich länger dauern.

### Mit fertigem Image

`nas-paket/compose.yaml` erspart das Bauen auf dem NAS. Im Projektordner genügen diese Datei als
`compose.yaml`, die `.env` und die Ordner aus Schritt 4. Dafür muss das Image zuerst veröffentlicht werden,
das geschieht nicht automatisch:

```bash
docker build --platform linux/amd64 --provenance=false --sbom=false --target app -t mikeheinoldock/documan-vaadin:latest .
```

```bash
docker push mikeheinoldock/documan-vaadin:latest
```

Nach einem Neustart des NAS startet DocuMan von selbst. Die Synology startet die Container dabei einzeln
und lehnt jeden ab, dessen Abhängigkeit noch nicht läuft. `nas-paket/compose.yaml` kommt deshalb ohne
`depends_on` aus, stattdessen wartet DocuMan beim Start selbst bis zu fünf Minuten auf die Datenbank.
Bleiben nach einem Neustart `web` und `worker` aus, stammt die `compose.yaml` im Projektordner noch aus
der Zeit davor: Ersetzen Sie sie durch die aktuelle Fassung und erstellen Sie das Projekt neu.

Diese Fassung bindet `/volume1` ein, damit Sie den Zielordner der Datensicherung in der Oberfläche
wählen können. DocuMan erreicht dort alles, worauf der Benutzer aus `PUID` Zugriff hat. Wer das
einschränken möchte, setzt in `.env` zum Beispiel `BACKUP_BROWSE_DIR=/volume1/backup`. Für eine
USB-Festplatte eignet sich `/volumeUSB1/usbshare`, sie muss beim Start angeschlossen sein.

## Dokumente hineinbekommen

| Weg | So geht's |
| --- | --- |
| Hochladen | „Hochladen“ wählen und Dateien auswählen oder ins Fenster ziehen |
| Eingangsordner | Dateien in den überwachten Ordner legen, auch in Unterordner. Für den Scanner als Netzwerkfreigabe einrichten |
| E-Mail | Unter „Postfächer“ ein IMAP-Konto verbinden |

Beim Hochladen zeigt DocuMan zu jeder Datei, ob sie angenommen wurde. Abgelehnte Dateien, etwa Duplikate
oder nicht unterstützte Formate, bleiben mit ihrem Grund im Fenster stehen, bis Sie es schließen. Ein Link
führt zum schon vorhandenen Dokument. Angenommene erscheinen sofort in Listen und Übersicht.

DocuMan nimmt jeden Dateityp an und legt ihn unverändert ab. Nur PDFs und Bilder (PNG, JPEG, TIFF auch
mehrseitig, WebP) bekommen zusätzlich eine durchsuchbare Archivfassung als PDF, das Original bleibt daneben
erhalten. Andere Formate wandelt DocuMan nicht um. Aus Word, Excel, PowerPoint (auch die alten Formate .doc, .xls, .ppt),
OpenDocument, RTF, HTML, E-Mails (.eml) und Textdateien liest DocuMan den Text für die Suche. Andere Formate
sind über Titel, Absendername, Dokumenttyp und Tags auffindbar. PDFs und Bilder zeigt die Detailseite direkt
an, ausgelesene Texte als Text. Alle anderen Formate öffnet DocuMan nie im Browser, sie stehen zum Herunterladen
bereit. Eine Datei, die sich `.pdf` oder `.jpg` nennt, aber etwas anderes enthält, wird als `.bin` abgelegt.

Die Eingangsordner werden alle 10 Sekunden geprüft. Eine Datei wird erst übernommen, wenn sie sich
10 Sekunden nicht mehr verändert hat, damit Scanner in Ruhe schreiben können. Danach wird sie aus
dem Ordner entfernt. Duplikate landen dort in `_abgelehnt/`, leere Dateien bleiben liegen.
Leere Unterordner räumt DocuMan ebenfalls weg. Hat DocuMan den Ordner selbst geleert, verschwindet er,
sobald er 10 Sekunden leer geblieben ist. Andere leere Ordner, etwa frisch angelegte oder ältere Reste,
bleiben 10 Minuten stehen, damit noch Dateien hineinkopiert werden können. Reste wie `@eaDir`, `Thumbs.db`
oder `.DS_Store` zählen dabei nicht. Der Eingangsordner selbst, `_abgelehnt/` und Systemordner wie
`#recycle` bleiben immer stehen.

Unterordner im Eingangsordner werden zu Kategorien: Eine Datei aus `Finanzen/Bank/Girokonto/` bekommt die
Kategorie Finanzen / Bank / Girokonto. Fehlende Stufen legt DocuMan an, vorhandene findet es unabhängig von
Groß- und Kleinschreibung. Es zählen höchstens zehn Ordnerebenen, tiefere bleiben unberücksichtigt. Dateien
direkt im Eingangsordner bekommen keine eigene Kategorie.

Unter **Eingangsordner** sehen Administratoren alle überwachten Ordner mit Zustand, wartenden und
abgelehnten Dateien. Mit „Ordner hinzufügen“ kommt ein weiterer dazu, zum Beispiel einer je Scanner oder
je Person. DocuMan überwacht bis zu 20 Ordner und beginnt innerhalb weniger Sekunden damit. „Nicht mehr
überwachen“ beendet die Überwachung, die Dateien im Ordner bleiben liegen. Ein Ordner bleibt immer
bestehen. Nach dem Update steht der bisher eingestellte Ordner als erster in der Liste. Zur Auswahl
stehen der Standardordner `consume/` und die Ordner des NAS unter `nas`, soweit der DocuMan-Benutzer
darauf zugreifen darf. Weil importierte Dateien aus dem Ordner verschwinden, fragt DocuMan bei einem
gefüllten Ordner nach. Den ganzen NAS-Ordner, Ordner mit Daten von DocuMan, Ordner mit mehr als
5.000 Dateien und Ordner, die in einem überwachten Ordner liegen oder einen enthalten, lehnt es ab.

Unter **Postfächer** führt ein Assistent durch die Einrichtung: Adresse eingeben oder den Anbieter wählen,
anmelden, Verbindung testen und festlegen, was abgerufen wird. Für IONOS, STRATO, WEB.DE, Gmail und
Microsoft trägt er Server, Port und Verschlüsselung selbst ein. Eigene Domains erkennt er am Mailserver
der Domain. Alle Werte lassen sich ändern, unter „Anderer Anbieter“ tragen Sie sie selbst ein.

| Anbieter | Server | Anmeldung |
| --- | --- | --- |
| IONOS | `imap.ionos.de` | Adresse und Passwort des Postfachs, nicht des Kundenkontos |
| STRATO | `imap.strato.de` | Adresse und Passwort des Postfachs |
| WEB.DE | `imap.web.de` | Zuerst bei WEB.DE den POP3/IMAP-Abruf einschalten, mit Zwei-Faktor-Schutz ein anwendungsspezifisches Passwort |
| Gmail | `imap.gmail.com` | App-Passwort, dafür muss die Bestätigung in zwei Schritten aktiv sein |
| Microsoft | `outlook.office365.com` | Anmeldung im Browser, siehe unter [E-Mail](#e-mail) |

**Ausschlussregeln:** Je Postfach legen Sie beliebig viele Regeln an, zum Beispiel „Absender enthält
@werbung.example“ oder „Betreff enthält Newsletter“. Trifft eine Regel zu, lädt DocuMan die Nachricht nicht
herunter und lässt sie unverändert im Postfach. Entfernen Sie die Regel, zählt die Nachricht beim nächsten
Abruf wieder mit.

**Abruf:** Jedes Postfach wird einmal pro Stunde abgerufen. Den Abstand stellen Sie je Postfach unter
„Abruf alle (Minuten)“ ein, von 5 Minuten bis zu einer Woche. Die frühere Einstellung
`MAIL_POLL_SECONDS` in der `.env` wird nicht mehr beachtet.

**Fortschritt:** Läuft ein Abruf, zeigt die Karte des Postfachs einen Balken mit dem Stand, zum Beispiel
„3 von 12 Nachrichten verarbeitet“. Das gilt auch für den automatischen Abruf, die Seite lädt sich danach
selbst neu. „Jetzt abrufen“ startet spätestens innerhalb einer Minute, bis dahin steht dort „Wartet auf
den Abruf“.

**Spam:** „Spam überspringen“ ist in jedem Postfach eingeschaltet. DocuMan bewertet den Inhalt nicht selbst,
sondern beachtet, was Mailanbieter und Mailprogramme kennzeichnen: die Kopfzeilen `X-Spam-Flag`,
`X-Spam-Status`, `X-Spam` und die Spam-Stufe von Microsoft ab 5, Betreffzeilen, die mit `[SPAM]` oder
`***SPAM***` beginnen, und die IMAP-Markierungen `$Junk` und `Junk`. Markieren Sie eine Nachricht im
Mailprogramm als „Kein Spam“, gilt das vor jeder Kennzeichnung. Spam-Ordner wie `Spam` oder `Junk-E-Mail`
ruft DocuMan nicht ab. Was niemand gekennzeichnet hat, kommt durch, dafür helfen die Regeln oben.

Beim E-Mail-Import kommen standardmäßig nur PDFs mit, weil Signaturen und Logos sonst mitkämen. Unter
„Anhänge“ wählen Sie „Nur PDF“, „PDF und Bilder“ oder „Alle Dateianhänge“. In die Mail eingebettete Bilder
kommen nie mit.
Für Gmail, GMX, Web.de und andere Anbieter ist meist ein App-Passwort nötig.
Das Zertifikat des Mailservers wird geprüft, selbstsignierte Zertifikate werden abgelehnt.

## Arbeiten mit DocuMan

Die **Übersicht** zeigt Kennzahlen, die größten Dokumenttypen, Absendernamen, Tags und Kategorien sowie den
Eingang auf einen Blick. Die Tortengrafik „Eingespielt aus Quelle“ zeigt, wie viele Dokumente von welchem
Benutzer, aus welchem Postfach und aus dem Eingangsordner stammen. Ein Klick auf ein Stück öffnet diese
Dokumente.

Die Tortengrafik „Eingang bearbeitet von“ summiert je Benutzer, wie viele Dokumente aus dem Eingang
abgelegt wurden. Administratoren sehen darunter die Tabelle „Aktivitäten der Benutzer“: hochgeladene,
abgelegte, bearbeitete und gelöschte Dokumente je Benutzer und wann die Person zuletzt aktiv war.
Gezählt wird ab dem Update auf diese Fassung. Die Zahlen stecken in der Datensicherung, beim Umzug in die
andere Version ziehen sie nicht mit. Die Karte „Speicherverbrauch“ zeigt Administratoren, wie voll das
Laufwerk ist und wie viel DocuMan davon belegt, aufgeteilt in Dokumente, Datenbank und Sicherungen. Sie
zählt der Hintergrunddienst alle fünf Minuten, die Übersicht wartet nicht darauf. Neben dem Namen DocuMan
steht die laufende Version.

Übersicht, Dokumentlisten, Eingang, Ordner, die Listen der Stammdaten und die Seitenleiste halten sich
selbst auf dem neuesten Stand. Der Server prüft alle 4 Sekunden, ob neue Dokumente angekommen sind oder sich
etwas geändert hat, und schickt die Änderung per Push an die offenen Seiten. Sie tauschen nur ihren Inhalt
aus, ohne neu zu laden, Scrollposition und Eingaben bleiben. Solange Dokumente angehakt sind, ein Fenster
offen ist oder ein Dokument geändert, aber noch nicht gespeichert ist, wartet die Aktualisierung. Eine
Dokumentseite zeigt Text und Vorschau, sobald die Verarbeitung fertig ist. Die Aktualisierung zählt nicht
als Aktivität, die Abmeldung nach einer Stunde greift trotzdem.

Unter **Mein Konto → Darstellung** wählt jeder Benutzer sein Aussehen: Standard, Windows 11, Windows 10,
Windows 7, Windows Vista, Windows XP, macOS, Ubuntu, GNOME oder KDE Plasma, jeweils hell, dunkel oder wie
im System. Die Wahl gilt für das Konto auf jedem Gerät. Es sind Anmutungen in Farben und Formen, passende
Schriften erscheinen nur, wenn sie auf dem Gerät installiert sind. Beim Umzug zieht die Wahl nicht mit.

**Eingespielt von:** Das Attribut steht bei jedem Dokument im Bearbeitungsformular und lässt sich dort
korrigieren. In der Dokumentliste gibt es dazu einen eigenen Filter. Ältere Uploads zeigen „Unbekannt“,
denn wer sie eingespielt hat, wurde bisher nicht festgehalten.

**E-Mail-Metadaten:** Dokumente aus einem Postfach behalten Absender, Empfänger, Betreff, Sendedatum und
Nachrichten-ID. Sie stehen auf der Dokumentseite unter „Datei“, Absender und Betreff sind durchsuchbar.

Neue Dokumente erscheinen im **Eingang**, sobald ihre Verarbeitung fertig ist. Solange Texterkennung und
Vorschau noch laufen, stehen sie nur unter „In Verarbeitung“ und zählen nicht zum Eingang. Dort prüfen Sie
die Vorschläge und wählen „Ablegen und weiter“, bis der Eingang leer ist. Fehlgeschlagene Dokumente bleiben
im Eingang, damit sie nicht übersehen werden.

**Fehlgeschlagene Dokumente:** Scheitert die Verarbeitung, etwa weil die Texterkennung eine Datei nicht
lesen kann, zeigt die Detailseite den Grund. Sie können das Dokument erneut verarbeiten lassen oder die Angaben
selbst ergänzen und mit „Speichern und als Dokument übernehmen“ abschließen. Es zählt dann nicht mehr als
Fehler, verlässt den Eingang und wird wie jedes andere Dokument abgelegt. In der Dokumentliste übernimmt
„Fehlgeschlagene als Dokument übernehmen“ mehrere auf einmal, andere ausgewählte Dokumente bleiben dabei unverändert.

**Kategorien:** Unter **Kategorien** legen Sie eine Ordnung über den Dokumenttypen an, bis zu zehn Stufen tief,
etwa Finanzen / Bank / Girokonto. Jede Kategorie bekommt dazu eine übergeordnete, auf derselben Stufe ist
jeder Name nur einmal vergeben. Ein Dokument erhält seine Kategorie auf drei Wegen: über seinen Dokumenttyp,
der beim Bearbeiten eine Kategorie bekommt, über den Unterordner im Eingangsordner oder direkt auf der
Detailseite. Dort tippen Sie die Kategorie wie den Dokumenttyp ein, die Stufen getrennt durch /, etwa
„Finanzen / Bank“. Vorhandene Kategorien werden vorgeschlagen, neue Stufen legt DocuMan an. Eine eigene
Kategorie des Dokuments geht der seines Typs vor, ein leeres Feld nimmt sie wieder weg. Wird eine Kategorie gelöscht, rücken ihre Unterkategorien, Dokumenttypen und Dokumente eine Stufe
nach oben. Die Dokumentliste filtert nach Kategorie, eine Kategorie umfasst dabei alle Unterkategorien. Die
Suche findet Dokumente über jede Stufe ihrer Kategorie.

**Ordner:** Die Ordneransicht zeigt alle Dokumente als Baum nach Kategorie mit ihren Unterkategorien, darin
nach Dokumenttyp und Absendername.

**Stapelbearbeitung:** In der Dokumentliste wählen Sie mehrere Dokumente aus und legen sie ab, setzen
Absendername, Typ, Kategorie oder Tags, übernehmen fehlgeschlagene, verarbeiten sie erneut oder löschen sie.
Bei „Kategorie setzen“ nimmt ein leeres Feld die eigene Kategorie weg, dann gilt die des Dokumenttyps.

**Automatische Zuordnung:** Bei Absendernamen, Dokumenttypen und Tags lassen sich Suchbegriffe
hinterlegen. Passen mehrere Absendernamen, gewinnt der Begriff, der am weitesten oben im Text
steht: Der Absender steht im Briefkopf, eine erwähnte Bank erst im Zahlungshinweis.
Absendernamen und Typen werden nur vorgeschlagen, wenn das Feld noch leer ist.

**Suche:**

| Eingabe | Bedeutung |
| --- | --- |
| `strom 2025` | beide Begriffe, auch Wortanfänge und Wortteile („rechnung“ findet „Stromrechnung“) |
| `"Beitrag für 2024"` | exakte Wortfolge |
| `rechnung -telekom` | ohne „telekom“ |
| `strom oder gas` | einer der Begriffe |

Die Taste `/` springt in die Suche. In der Dokumentliste aktualisieren sich die Treffer beim Tippen.

## Dateien auf dem NAS

```
data/
  media/
    originals/           Originale, ihr Inhalt wird nie verändert
    archive/             durchsuchbare PDF/A-Fassungen
    thumbnails/          Vorschaubilder
  postgres/              Datenbank
consume/                 Eingangsordner
backup/                  Datensicherungen
```

Alle drei Ordner sind gleich aufgebaut und folgen der Kategorisierung: erst die Stufen der Kategorie, darin
der Dokumenttyp. Die Dateien heißen nach Absender, Dokumenttyp und Belegdatum, zum Beispiel
`originals/Finanzen/Energie/Rechnung/Stadtwerke Musterstadt_Rechnung_20260314.pdf`. Der Absender steht damit im
Dateinamen und bekommt keinen eigenen Ordner. Gibt es im selben
Ordner mehrere Dateien mit diesem Namen, folgt ein Index wie `Stadtwerke Musterstadt_Rechnung_20260314_2.pdf`.
Heruntergeladene Dateien heißen genauso.

Ohne Kategorie liegt die Datei unter `Ohne Kategorie`, dort zusätzlich im Ordner des Absendernamens, etwa
`originals/Ohne Kategorie/Stadtwerke Musterstadt/Rechnung/`. Fehlt der Absendername, heißt dieser Ordner
`Ohne Absendername`, und im Dateinamen steht der Titel anstelle des Absenders. Ohne Dokumenttyp liegt sie unter `Ohne Dokumenttyp`, und der Typ entfällt im Namen. Ohne Belegdatum zählt das Importdatum.
Tags bestimmen den Ordner nicht, weil ein Dokument mehrere haben kann.

Ändern Sie Kategorie, Absendername, Dokumenttyp, Titel oder Datum oder benennen Sie eine Kategorie, einen
Absendernamen oder Dokumenttyp um, zieht DocuMan die Dateien innerhalb weniger Sekunden nach. Leer gewordene
Ordner verschwinden dabei. Nach dem Update auf diese
Ablage ziehen vorhandene Dateien einmalig in die neuen Ordner. Während der Verarbeitung behalten neue
Dateien vorübergehend ihren ursprünglichen Namen. Benennen Sie Dateien nicht von Hand um, die Datenbank
merkt sich ihren Pfad.

## Datensicherung

Superuser finden in der Seitenleiste den Punkt **Datensicherung**. „Jetzt sichern“ schreibt Datenbank
und Dokumente in eine einzige ZIP-Datei, zum Beispiel `documan-20261031-020000-auto.zip`. Sie lässt
sich auch ohne DocuMan öffnen. Der Betrieb läuft währenddessen weiter, nur die Dokumentverarbeitung
wartet.

**Zeitplan:** Sicherungen laufen täglich oder wöchentlich zur eingestellten Uhrzeit. War das NAS zu
diesem Zeitpunkt aus, wird die Sicherung beim nächsten Start nachgeholt. „Aufbewahren“ begrenzt nur
die Archive des Zeitplans, von Hand erstellte Archive löscht DocuMan nie. Schlägt die letzte Sicherung
fehl, zeigt die Seitenleiste einen Hinweis.

**Zielordner:** Mit „Ordner wählen“ suchen Sie das Ziel aus oder legen einen neuen Ordner an. Zur
Auswahl stehen der Sicherungsordner aus `BACKUP_DIR` und mit `nas-paket/compose.yaml` auch die Ordner
des NAS unter `nas`. DocuMan läuft im Container und sieht nur eingebundene Ordner, für andere Laufwerke
ändern Sie `BACKUP_BROWSE_DIR` und erstellen das Projekt neu. Der Dokument- und der Eingangsordner sind
als Ziel gesperrt. Eine Sicherung auf derselben Festplatte schützt nicht vor deren Ausfall.

Das Archiv enthält nicht die `.env` und nicht den Eingangsordner. Es ist unverschlüsselt und enthält
alle Dokumente im Klartext, behandeln Sie es also wie die Dokumente selbst. Sichern Sie die `.env`
zusätzlich an einem sicheren Ort.

### Wiederherstellen und Neuinstallation

1. DocuMan wie oben installieren und die alte `.env` verwenden. Ohne den ursprünglichen `SECRET_KEY`
   funktioniert alles außer den Postfach-Passwörtern: Betroffene Postfächer werden deaktiviert und
   brauchen ein neu eingegebenes Passwort.
2. Das Archiv in den Sicherungsordner kopieren. Unterordner werden bis zu drei Ebenen tief gefunden,
   tiefere Ablagen nach Auswahl als Zielordner.
3. Mit dem Admin-Konto aus der `.env` anmelden, unter **Datensicherung** das Archiv wählen und
   „Wiederherstellen“ bestätigen.

Danach gelten die Benutzer und Passwörter aus der Sicherung, alle bisherigen Anmeldungen enden. Das
Archiv wird vorab vollständig geprüft, und die Datenbank wird in einer einzigen Transaktion ersetzt:
Scheitert etwas, bleibt der bisherige Stand erhalten. Auf Wunsch sichert DocuMan vorher den aktuellen
Stand. Solche Archive enden auf `-vor-wiederherstellung.zip`. Archive älterer Versionen werden
automatisch hochgestuft, Archive neuerer Versionen werden abgelehnt.

Sicherungen der Java-Version lassen sich hier einspielen und umgekehrt, beide Fassungen haben dieselbe
Datenbank. So wechseln Sie zwischen Java und Vaadin: Sicherung erstellen, im anderen System
wiederherstellen. Sicherungen der Django-Version lassen sich hier nicht einspielen und umgekehrt, DocuMan
lehnt solche Archive mit einem Hinweis ab. Für diesen Wechsel gibt es den Umzug.

## Umzug zwischen Vaadin- und Django-Version

Der Umzug bringt Dokumente, Stammdaten, Benutzer und Postfächer in die jeweils andere Version. Er
steht Superusern unter **Datensicherung → Umzug** zur Verfügung, in der Django-Version genauso.

1. Im alten System „Umzugsarchiv erstellen“ wählen. Die Datei heißt zum Beispiel
   `documan-umzug-20261101-100000-java.zip` und liegt im aktuellen Zielordner der Datensicherung.
2. Die Datei in den Sicherungsordner des neuen Systems kopieren. Sie erscheint dort unter **Umzug**.
3. Im neuen, noch leeren System „Einspielen“ wählen und mit `UMZIEHEN` bestätigen.

Kategorien ziehen in beide Richtungen mit um, auch die eigenen Kategorien einzelner Dokumente.

Eingespielt wird nur in ein System ohne Dokumente, sonst ist die Schaltfläche gesperrt. Das Archiv wird
vorab vollständig geprüft. Scheitert etwas, bleibt das System, wie es war.

Passwörter werden nicht übertragen, weder die der Benutzer noch die der Postfächer:

- Neue Benutzer haben danach kein nutzbares Passwort. Schicken Sie ihnen unter „Benutzer“ mit „Zugangsmail
  senden“ einen Link oder vergeben Sie es dort selbst. Der Lauf nennt ihre Anzahl. Vorhandene Benutzer behalten Passwort und Rechte.
- Postfächer sind deaktiviert. Tragen Sie das Passwort neu ein und aktivieren Sie sie wieder.
- Beim Einspielen werden keine E-Mails verschickt. Die Zugangsmail können Sie danach in der
  Benutzerliste auslösen.

Dokumente behalten Datum, Status und Dateien, der Suchindex entsteht neu. Dokumente, deren Originaldatei
fehlt, lässt das Archiv aus und der Lauf nennt sie. Umzugsarchive erscheinen nicht unter den Sicherungen,
die Aufbewahrung des Zeitplans lässt sie in Ruhe, und sie enthalten alle Dokumente im Klartext.
Das Format beschreibt `docs/umzugsformat.md`.

## E-Mail

### Postfächer bei Microsoft

Outlook.com, Hotmail und Microsoft 365 nehmen über IMAP keine Passwörter mehr an. DocuMan meldet sich
deshalb per Gerätecode an: Sie öffnen die Seite von Microsoft, geben den angezeigten Code ein und
bestätigen den Zugriff. Gespeichert wird nur ein verschlüsselter Zugriffsschlüssel, nie Ihr Passwort. Das
NAS muss dafür nicht aus dem Internet erreichbar sein.

Einmalig braucht DocuMan dafür eine eigene App-Registrierung bei Microsoft:

1. Unter <https://entra.microsoft.com> bei **App-Registrierungen** eine neue Registrierung anlegen.
2. Als Kontotyp „Konten in einem beliebigen Organisationsverzeichnis und persönliche Microsoft-Konten“
   wählen, eine Umleitungs-URI ist nicht nötig.
3. Unter **Authentifizierung** „Öffentliche Clientflows zulassen“ einschalten.
4. Unter **API-Berechtigungen** die delegierte Berechtigung `IMAP.AccessAsUser.All` von Office 365
   Exchange Online hinzufügen.
5. Die **Anwendungs-ID (Client-ID)** als `MICROSOFT_CLIENT_ID` in `.env` eintragen und die Container neu
   starten.

Wer nur ein privates Microsoft-Konto hat, braucht für die Registrierung unter Umständen zuerst ein
kostenloses Verzeichnis (Mandant) bei Microsoft Entra. Bei Microsoft 365 muss IMAP für das Postfach
erlaubt sein, das kann die Verwaltung gesperrt haben. Läuft die Anmeldung ab, zeigt das Postfach einen
Fehler, unter „Bearbeiten“ verbinden Sie es neu.

### Benachrichtigungen

Jeder Benutzer braucht eine E-Mail-Adresse. Superuser tragen sie beim Anlegen ein. Bestehende Benutzer
ohne Adresse werden nach der Anmeldung danach gefragt und können erst dann weiterarbeiten.

Beim Anlegen eines Benutzers vergeben Sie kein Passwort. DocuMan verschickt eine Zugangsmail mit dem
Benutzernamen und einem Link, über den der Benutzer sein Passwort selbst festlegt. Der Link gilt 2 Stunden und
wirkt nur einmal. Scheitert der Versand, bleibt der Benutzer bestehen und die Seite zeigt eine Warnung. In
der Benutzerliste verschickt „Zugangsmail senden“ einen neuen Link. Ohne Mailversand vergeben Sie das
Passwort beim Bearbeiten des Benutzers.

Den Versand richten Sie in `.env` ein:

| Variable | Bedeutung |
| --- | --- |
| `SITE_URL` | Adresse des Systems in den Nachrichten, ohne Angabe die Adresse der aktuellen Anfrage |
| `EMAIL_HOST` | SMTP-Server, leer bedeutet: kein Versand |
| `EMAIL_PORT` | Port, Standard 587 |
| `EMAIL_SECURITY` | `starttls`, `ssl` oder `none` |
| `EMAIL_HOST_USER` | Benutzername am SMTP-Server |
| `EMAIL_HOST_PASSWORD` | Passwort am SMTP-Server |
| `EMAIL_FROM` | Absender, ohne Angabe der Benutzername am SMTP-Server |
| `DAILY_REPORT_TIME` | Uhrzeit der täglichen Übersicht beim ersten Start, Standard 07:00, leer: zunächst aus. Danach gilt die Seite „Übersichtsmail“ |

Hinter einem Reverse Proxy setzen Sie `SITE_URL`, damit die Mail die öffentliche Adresse nennt. Das
Zertifikat des Mailservers wird geprüft, ein Versuch dauert höchstens 15 Sekunden.

**Tägliche Übersicht:** Standardmäßig jeden Tag um 07:00 Uhr bekommen alle aktiven Superuser mit E-Mail-Adresse
eine Übersicht über die letzten 24 Stunden: neue Dokumente nach Quelle, wartende und fehlgeschlagene Dokumente
mit Grund, Aktivitäten der Benutzer, Zustand der Postfächer mit ihrem IMAP-Ordner, letzte Sicherung und
Speicherverbrauch. Diagramme zeigen die neuen Dokumente der letzten 14 Tage, ihre Herkunft in den letzten
30 Tagen, die fünf größten Dokumenttypen, die Dokumente je Kategorie der obersten Stufe samt
Unterkategorien und die Belegung des Speichers. Unter „Verzeichnisse“ stehen die
eingestellten Eingangsordner mit ihrem Pfad auf dem NAS, ob DocuMan sie erreicht und wie viele Dateien dort
warten oder abgelehnt wurden, dazu Zielordner und Zeitplan der Sicherung. Die Balken sind reines HTML und
erscheinen in jedem Mailprogramm sofort, die Textfassung der Mail nennt dieselben Werte als Liste.

**Übersichtsmail einstellen:** Auf der Seite „Übersichtsmail“ (nur für Superuser) legen Sie fest, ob und wann
die Übersicht geht: Uhrzeit und Wochentage, etwa nur Montag bis Freitag. Jeder Abschnitt lässt sich einzeln
abwählen. Herkunft der Dokumente, Dokumenttypen, Kategorien und Speicher zeigt die Mail wahlweise als Balken
oder als Torte. Torten kommen als eingebettete Bilder mit der Mail, ohne Verbindung nach außen, Werte und Anteile stehen
in einer Legende daneben. Manche Mailprogramme zeigen eingebettete Bilder erst nach einem Klick. Bei den
Dokumenttypen fasst die Torte alle kleineren Typen als „Weitere Dokumenttypen“ zusammen, beim Speicher zeigt sie
das ganze Laufwerk einschließlich dessen, was andere Programme belegen. Empfänger sind alle aktiven Superuser
mit E-Mail-Adresse, wer die Übersicht nicht bekommen soll, wird abgewählt. Weitere Adressen, etwa ein
Sammelpostfach, tragen Sie eine je Zeile ein. „Jetzt an mich senden“ schickt die Übersicht sofort an Sie, zum
Ausprobieren, ebenso „Übersicht jetzt an mich senden“ unter „Mein Konto“. `DAILY_REPORT_TIME=HH:MM` in der
`.env` gibt nur beim ersten Start die Uhrzeit vor, `DAILY_REPORT_TIME=` ohne Wert lässt die Übersicht zunächst
ausgeschaltet. Danach gilt allein die Seite. War DocuMan zum Termin nicht in Betrieb, kommt die Übersicht am
selben Tag später nach, an einem Tag aber nie zweimal.

## Benutzer und Registrierung

Es gibt zwei Rollen. **Standardbenutzer** suchen, bearbeiten und ergänzen Dokumente und Stammdaten und pflegen
unter „Mein Konto“ Namen, E-Mail-Adresse und Passwort. **Superuser** dürfen zusätzlich löschen und verwalten
Postfächer, Sicherungen und Benutzer.

Mit `REGISTRATION_ENABLED=true` in der `.env` und eingerichtetem Mailversand erscheint auf der Anmeldeseite
„Konto anlegen“. Dort gibt man Benutzername, Namen und E-Mail-Adresse an, aber kein Passwort. Über den
Link aus der Aktivierungsmail legt man innerhalb von 48 Stunden sein Passwort fest. Erst dann ist das Konto
aktiv, als Standardbenutzer. Eine geänderte E-Mail-Adresse gilt ebenfalls erst nach der Bestätigung. Die Registrierung ist ausgeschaltet, weil jedes bestätigte Konto das ganze Archiv lesen
kann. Schalten Sie sie nur ein, wenn die Seite nicht offen im Internet steht.

Mit eingerichtetem Mailversand steht auf der Anmeldeseite „Passwort vergessen?“. Nach Eingabe der
E-Mail-Adresse kommt ein Link, über den man ein neues Passwort vergibt. Er gilt 2 Stunden und wirkt nur
einmal, bis dahin bleibt das bisherige Passwort gültig. Die Seite verrät nicht, ob es zu einer Adresse ein
Konto gibt.

## Betrieb

Aktualisieren, nachdem neue Projektdateien kopiert wurden:

```bash
docker compose up -d --build
```

Protokolle ansehen:

```bash
docker compose logs -f worker
```

Weitere Benutzer legen Superuser in der Seitenleiste unter „Benutzer“ an, siehe Abschnitt „E-Mail“. Alle
Benutzer sehen alle Dokumente. Das eigene Passwort ändert jeder Benutzer unter „Passwort ändern“.

Weitere OCR-Sprachen brauchen zwei Änderungen: `TESSERACT_LANGS` im `Dockerfile` und
`OCR_LANGUAGES` in `.env`. Danach neu bauen.

Die Java-Prozesse begrenzen `WEB_HEAP_MB` und `WORKER_HEAP_MB` in `.env`. Die OCR läuft außerhalb
davon. Der Worker liest Bilder beim Umwandeln in PDF in seinen Speicher: Scheitern sehr große Scans,
erhöhen Sie `WORKER_HEAP_MB`.

### Sicherheit

DocuMan ist für das Heimnetz gedacht und spricht selbst nur HTTP. Stellen Sie den Port nicht direkt ins
Internet. Für den Zugriff von unterwegs eignet sich ein VPN (z. B. WireGuard oder Tailscale).
Hinter einem Reverse Proxy mit HTTPS setzen Sie `HTTPS=true` in `.env`. Mit `ALLOWED_HOSTS` schränken Sie
die zulässigen Hostnamen ein.

Nach einer Stunde ohne Aktivität meldet DocuMan ab, die Seite wechselt spätestens eine Minute später selbst
zur Anmeldung. Als Aktivität zählt jede Seite und jede Aktion, nicht aber die Aktualisierung per Push. Eine
Anmeldung endet außerdem beim Abmelden, nach einer Passwortänderung, nach einer Wiederherstellung und
wenn der Web-Container neu startet.

## Entwicklung

Die Anwendung ist in Java 25 und Spring Boot 4 geschrieben und läuft in zwei Containern aus demselben
Image: `web` liefert die Oberfläche, `worker` verarbeitet Warteschlange, Eingänge und Sicherungen.

- **Oberfläche:** Vaadin 25 (Flow) mit den freien Kernkomponenten. Die Seiten sind Java-Klassen unter
  `de.documan.ui`, der Server hält ihren Zustand und schickt Änderungen per Push. Das Stylesheet und die
  zehn Aussehen sind dieselben wie in der Java-Version, `vaadin.css` gibt sie an die Vaadin-Komponenten
  weiter. Ohne eigenen Frontend-Code nimmt der Build das fertige Bundle von Vaadin, ein Node-Build ist nicht
  nötig.
- **Datenbank:** `JdbcClient` statt JPA, weil Suche und Warteschlange auf `tsvector`, Trigrammen und
  `SKIP LOCKED` aufbauen. Das Schema verwaltet Flyway.
- **Warteschlange:** ein eigener Worker auf der Datenbank statt Redis, genau eine Instanz. Sicherungen
  laufen dadurch nie gleichzeitig mit der Dokumentverarbeitung.
- **Werkzeuge:** OCRmyPDF, Poppler, `file` und die PostgreSQL-Clients laufen als eigene Prozesse.

Tests laufen im Container gegen echtes PostgreSQL und echtes OCRmyPDF, IMAP-Abruf und Mailversand gegen
einen eingebetteten Mailserver:

```bash
docker compose -f docker-compose.test.yml run --rm --build tests
```

```bash
docker compose -f docker-compose.test.yml down -v
```

Die Version in der Oberfläche kommt beim Bauen des Images hinein, zum Beispiel mit
`--build-arg DOCUMAN_VERSION=2026.11.02-1`. Ohne Angabe steht dort `dev`.

`DocuMan-Anleitung.pdf` entsteht aus dieser Datei. Nach jeder Änderung neu erzeugen:

```bash
docker build -t documan-anleitung tools/anleitung
```

```bash
docker run --rm -v .:/app documan-anleitung
```

## Lizenz

DocuMan steht unter der MIT-Lizenz, siehe Datei `LICENSE`. Die Schrift Inter steht unter der SIL Open
Font License, siehe `src/main/resources/static/fonts/Inter-LICENSE.txt`.
