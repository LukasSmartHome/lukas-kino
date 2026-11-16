# Lukas Kino für die LG-Fernseher

Web-Version der Kino-Oberfläche (gleicher Look wie die Android-App) für die offizielle Jellyfin-App aus dem LG Store.

- `web/` – die Oberfläche: `index.html` (kleiner Lader, ändert sich nie), `app.js`, `app.css`, `manifest.json`, Schrift.
- Auf dem Pi liegt alles in `/share/kino/` (`web/` plus `System/Info/Public` = Kopie von Jellyfins `/System/Info/Public`).
- Ausgeliefert vom kleinen App-Webserver `kino_web/` (Home-Assistant-App „Lukas Kino Web“, Port 8098). Home Assistant selbst
  geht nicht: Es schickt `X-Frame-Options: SAMEORIGIN`, die Jellyfin-App lädt die Seite aber in ein iframe (grauer Bildschirm).
- In der LG-Jellyfin-App als Server eintragen: `192.168.178.44:8098`.
- Die App lädt `System/Info/Public`, dann `web/manifest.json` und zeigt `web/index.html` im Vollbild. Zurück-Taste = keyCode 461,
  Beenden per `postMessage({type: 'AppHost.exit'})`.
- Anmeldung nur per Quick Connect, der Schlüssel liegt im localStorage am Fernseher.

## Wer schaut wo (seit 02.11.2026)

Die Server-Adresse in der LG-App sagt der Oberfläche, wer davor sitzt (in `/share/kino/` sind `eltern`, `jonas`, `lukas`
Verweise auf denselben Ordner):

| Fernseher | Adresse in der LG-App | Person | Besonderheit |
|---|---|---|---|
| Wohnzimmer (LG C3) | `192.168.178.44:8098` | Eltern (Moni + Uli gemeinsam) | – |
| Jonas (LG B5) | `192.168.178.44:8098/jonas` | Jonas | leichter Modus (ohne Glas-Unschärfe), DTS/TrueHD über den Pi |

- **Merkliste** = dieselbe wie im HA-Profil (`sensor.plex_merkliste`). Lesen über `bewertungen.json` (Paket `kino_export.yaml`),
  schreiben per Webhook an Automation 84 (Adresse nur in `/share/kino/kino_geheim.json`, nicht im Repo).
  Plex-IDs werden über Titel + Jahr zugeordnet (`plexfilme.json`), Filme nur in Jellyfin als `jf:<ID>`.
- **Empfohlen** = Filme, die die anderen mit 7–10 bewertet haben, gruppiert nach bester Note.
- **Ton**: Kann der Fernseher die Tonspur nicht (Jonas: DTS, TrueHD) oder ist die gewünschte (deutsche) Spur nicht die
  Standardspur der Datei, spielt der Film als HLS über Jellyfin auf dem Pi: Bild nur umverpackt, Ton → AC3 5.1.
  Geht direkt etwas schief, wird automatisch auf den Pi gewechselt (und umgekehrt).
- `?person=`, `?leicht=1/0`, `?ha=` überschreiben das zum Testen.

## Poster-Wand und Sammlungen (seit 16.11.2026)

Wie am TCL: eine geöffnete Kategorie, Filmreihe oder „Gesehen“ ist eine Poster-Wand (`posterWandFuellen`, 9 Poster nebeneinander, `#wand.poster`), keine Reihen nach Jahren mehr. Genre-Kategorie = Sammlung mit Farbe in `FARBEN`, alles andere ist eine Filmreihe (`istFilmreihe`). Kachel „Sammlungen“ vorn in den Kategorien; im Genre steht eine Filmreihe als eine Kachel, wenn dort mindestens 2 ihrer Filme liegen. Einspielen: `werkzeuge/kino_web_patch_wand_1611.py` (Stand davor auf dem Pi: `app.js.bak_vor_wand2_16-11-2026`).

## Schneller Start, kein Intro (seit 16.11.2026)

- Das Start-Intro ist entfernt (Wunsch Lukas: nervt auf Dauer, lauter Ton). Die Datei `/share/kino/intro.mp4` wird nicht mehr geladen.
- Beim Start gehen alle Anfragen der Startseite sofort gleichzeitig los (`vorabStarten` in `app.js`: Bibliotheken, Weiterschauen,
  Neu, Kategorien, Film-Index, `bewertungen.json`, `plexfilme.json`) statt nacheinander. Der Ablauf danach ist unverändert –
  er bekommt nur Antworten, die schon unterwegs sind (einmalig, höchstens 15 s alt; ging eine schief, fragt er normal neu).
  „Empfohlen“ wird im Hintergrund berechnet, der Start wartet nicht mehr darauf.
- Gemessen im Browser mit nachgestelltem Jellyfin (150 ms je Antwort): Startseite nach 1,11 s → 0,44 s. Aussehen unverändert.
- Eingespielt mit `werkzeuge/kino_web_patch_1611.py ORDNER` (läuft gleich im Repo und auf dem Pi, vergleicht sich über Prüfsummen).
