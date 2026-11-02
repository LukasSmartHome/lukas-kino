# Werkzeuge

## filmsortierer.py – Filme erkennen, Doppelte finden, sauber benennen

Läuft im File editor auf dem Pi (dort sind `/media` und `/share` eingehängt). Holen und starten:

```
wget -q -O /tmp/fs.py https://raw.githubusercontent.com/LukasSmartHome/lukas-kino/filme/werkzeuge/filmsortierer.py
python3 /tmp/fs.py offen
```

Der Jellyfin-Schlüssel wird aus `jellyfin_schluessel.txt` im HA-Konfigordner gelesen und nie ausgegeben.
Für Standbilder braucht es ffmpeg (`apk add --no-cache ffmpeg` im File editor, verschwindet beim Neustart der App).
Alles ohne `--los` ist nur Vorschau.

| Befehl | Was |
|---|---|
| `inventar` | alle Filme mit Größe, Laufzeit, Ton, TMDB-ID → `/share/kino/pruefung/inventar.json` |
| `offen` | Filme ohne Zuordnung, mit Namensvorschlag aus dem Dateinamen |
| `suche Name_mit_Unterstrich:Jahr …` | TMDB-Kandidaten über Jellyfin (kein eigener TMDB-Schlüssel) |
| `bilder ID …` | Kontaktbogen mit 12 Standbildern → `http://192.168.178.44:8098/pruefung/ID.jpg` (nur Heimnetz) |
| `doppelte` | gleiche TMDB-ID, gleicher Titel und Jahr oder exakt gleiche Datei |
| `umbenennen ID TMDB-Nr\|ttIMDb [--titel=..] [--jahr=..] [--los]` | verschiebt nach `Titel (Jahr) [tmdbid-N]/Titel (Jahr) [tmdbid-N].ext` |
| `stapel ID,ZIEL ID,ZIEL … [--los]` | mehrere auf einmal |
| `scan` | nur die Film-Bibliothek neu einlesen |
| `titel ID Neuer_Titel` | Anzeigenamen in Jellyfin setzen und sperren |
| `zurueck [--los]` | letzte Verschiebung rückgängig (Protokoll `/media/Filme/.filmsortierer_log.jsonl`) |

Längere Läufe (mehr als 15 Sekunden) im File editor mit `nohup … > /tmp/x.log 2>&1 &` starten und das Log lesen.

## Plan für große Mengen (Terabytes)

1. **Platz zuerst:** Die Platte am Pi hat 939 GB, davon sind nur noch etwa 64 GB frei. Vor dem großen Schwung braucht es eine größere Platte.
2. **Eingang statt direkt in die Bibliothek:** Neue Filme kommen erst in einen Eingangsordner, den Jellyfin nicht sieht.
3. **Prüfen (automatisch):** Laufzeit, Auflösung, Tonsprachen und eingebetteten Titel auslesen (ffprobe), dann den Namen aus dem Dateinamen raten und bei TMDB über Jellyfin suchen. Passen Name und Jahr genau zu genau einem Treffer, gilt der Film als sicher.
4. **Unsichere (Claude):** Kontaktbogen anschauen, Dateiinfos vergleichen, im Netz recherchieren, so wie am 02.11. bei 10 Filmen geübt.
5. **Doppelte:** Gleiche TMDB-ID wie schon in der Bibliothek, dann bleibt die bessere Fassung (deutscher Ton vor Auflösung vor HDR und Tonformat vor Größe). Die andere kommt in einen Ordner `_doppelt`, wird aber nie automatisch gelöscht.
6. **Einsortieren:** Schema `Titel (Jahr) [tmdbid-N]` – damit erkennen Jellyfin und Plex jeden Film sicher, auch bei gleichen Titeln.
7. **Trailer:** Dafür braucht es keine Dateien. TMDB liefert die Trailer-Links, die Kino-Oberfläche holt deutsche Trailer (KinoCheck). Lokale Trailerdateien nur auf Wunsch, weil sie Platz kosten.
8. **Wo es läuft:** Bei Terabytes am besten am PC, bevor kopiert wird. Der PC ist schneller, und Doppelte oder Müll landen gar nicht erst auf dem Pi. Dasselbe Skript lässt sich dafür um einen Eingangsmodus mit ffprobe erweitern (läuft mit Python und ffmpeg auch unter Windows), Claude steuert es dann per Agent am PC.
9. **Bestehende Filme:** 123 erkannte Filme liegen noch flach in `/media/Filme`. Ins Schema ordnen geht mit `stapel`. Jellyfin legt verschobene Dateien aber als neue Einträge an, dabei kann „gesehen“ und die Fortsetzen-Stelle verloren gehen. Deshalb erst mit einem Film testen.
