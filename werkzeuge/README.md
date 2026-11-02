# Werkzeuge

## filmsortierer.py – Platten durchsuchen, Filme erkennen, Doppelte finden, sauber einsortieren

**Grundregel (Lukas, 02.11.2026): Es wird nie etwas gelöscht oder überschrieben.** Doppeltes und Zusatzkram wandern
nach `Kino/_Pruefen/…` – mit dem alten Pfad dahinter, damit Lukas sieht, wo es lag. Löschen macht nur Lukas, nach dem Bericht.
Alles ohne `--los` ist nur Vorschau. Jeder Lauf hat ein Protokoll und lässt sich komplett zurücknehmen.

Läuft im File editor auf dem Pi (dort sind `/media` und `/share` eingehängt) und genauso am Windows-PC (Python 3.8+).
Braucht nur Python. Der Jellyfin-Schlüssel kommt aus `jellyfin_schluessel.txt` im HA-Konfigordner (wird nie ausgegeben;
am PC: `--schluessel=\\192.168.178.44\config\jellyfin_schluessel.txt`). Filme werden über Jellyfin bei TMDB erkannt –
es braucht keinen eigenen TMDB-Schlüssel. ffprobe/ffmpeg sind optional (genauere Qualität, Kontaktbögen).

Holen und starten (File editor, `/api/exec_command` mit `sh -c "…"`):

```
wget -q -O /tmp/fs.py https://raw.githubusercontent.com/LukasSmartHome/lukas-kino/filme/werkzeuge/filmsortierer.py
python3 /tmp/fs.py
```

Längere Läufe (mehr als ca. 15 s) mit `nohup python3 /tmp/fs.py pruefen /media/Filme4 > /tmp/fs.log 2>&1 &` starten,
dann nur `cat /media/Filme4/Kino/_sortierer/fortschritt.txt` und am Ende `tail -n 8 /tmp/fs.log` lesen.

### A) Eingang – eine ganze Platte sortieren (die 14-TB-Platte Filme4)

| Schritt | Befehl | Was passiert |
|---|---|---|
| 1 | `bestand /media/Filme4` | Liste aller Dateien mit Größe und Datum → `Kino/_sortierer/bestand_*.tsv` (vorhandene Ordnung festhalten, Vorgabe Lukas) |
| 2 | `pruefen /media/Filme4 --ohne-bibliothek` | alles einordnen, Filme bei TMDB erkennen, Doppelte suchen → `bericht.md`, `unsicher.tsv`, `plan.json`. `--ohne-bibliothek` beim großen Umzug (sonst gelten die SSD-Filme als „schon in der Bibliothek“); später bei neuen Filmen weglassen |
| 3 | `vorschau /media/Filme4 12 47` | nur wenn der Name nichts hergibt: Kontaktbogen (12 Standbilder) → `http://192.168.178.44:8098/pruefung/e12.jpg` (braucht ffmpeg) |
| 4 | `entscheiden /media/Filme4 12=tmdb:603 47=beiseite 50=serie:Dark:2` | Unsichere festlegen (viele auf einmal) |
| 5 | `ausfuehren /media/Filme4` | Vorschau: was wohin geht, Konflikte |
| 6 | `ausfuehren /media/Filme4 --los` | verschieben (gleiche Platte = sofort, nichts wird kopiert) |
| 7 | Lukas liest `Kino/_sortierer/bericht.md` | und entscheidet über `_Pruefen/Doppelt` und `_Pruefen/Beiseite` |
| zurück | `zurueck-lauf /media/Filme4 --los` | letzten Lauf komplett rückgängig |
| kopieren | `kopieren /media/Filme /media/Filme4/Von_SSD --los` | die SSD-Filme als weiteren Eingang auf die große Platte (vor Schritt 2): jede Datei mit Prüfsumme geprüft, die SSD bleibt unberührt |

Ergebnis auf der Platte:

```
Kino/Filme/Titel (Jahr) [tmdbid-N]/Titel (Jahr) [tmdbid-N].mkv   (+ Untertitel mit gleichem Namen, z. B. .de.srt)
Kino/Serien/Serienname/Staffel 01/<Originalname mit S01E01>
Kino/Musik/<alter Pfad>                                           (Ordnung bleibt – ob sie bleibt, entscheidet Lukas)
Kino/_Pruefen/Doppelt/<alter Pfad>                                (schlechtere Fassungen und exakte Kopien)
Kino/_Pruefen/Beiseite/<alter Pfad>                               (Samples, Trailer, Extras, .nfo, Thumbs.db, Untertitel ohne Film)
Kino/_sortierer/                                                  (Bestand, Plan, Bericht, Protokolle, Caches)
```

Was liegen bleibt: unsichere Filme (bis entschieden), DVD-/Blu-ray-Abbilder (VIDEO_TS, BDMV, ISO), Ordner ohne Film/Musik
(z. B. Fotos). Ein zweiter `pruefen`-Lauf sieht nur noch den Rest (der Ordner `Kino` wird übersprungen).

Wie entschieden wird:
- **Sicher erkannt** = Name und Jahr passen genau zu einem TMDB-Treffer, oder es gibt im Jahr nur genau einen Treffer
  (z. B. „The Matrix 1999“ → „Matrix“), oder die ID steht schon im Namen (`[tmdbid-603]`, `imdbid-tt…`).
  Name und Jahr kommen aus dem Dateinamen oder – nur bei Ordnern mit genau einem Film – aus dem Ordnernamen.
- **Doppelt** = a) exakt gleiche Datei (Größe + Prüfsumme der ersten/letzten 4 MiB), b) gleicher Film (TMDB-ID) bzw.
  gleiche Folge – die bessere Fassung bleibt: **deutscher Ton vor Auflösung vor HDR vor Tonkanälen vor Größe**,
  c) schon in der Jellyfin-Bibliothek in mindestens gleicher Qualität. Ist die neue Fassung besser als die in der Bibliothek,
  steht das im Bericht („Bessere Fassung als in der Bibliothek“) – die alte nimmt niemand automatisch raus.
- **Serien** erkennt es an `S01E02` oder `1x02`, den Serienamen aus dem Dateinamen oder dem Ordner (Staffel-Ordner zählen nicht).
- Entscheidungen werden nach Pfad gespeichert (`entscheidungen.json`) und gelten auch nach einem neuen `pruefen`.
- TMDB-Antworten, ffprobe-Werte und Prüfsummen werden gecacht – ein zweiter Lauf ist schnell.

Entscheidungen (`entscheiden QUELLE NR=AKTION`): `tmdb:603`, `imdb:tt0133093`, `titel:Eigener_Titel:1999` (ohne ID),
`serie:Name_der_Serie[:Staffel]`, `musik`, `beiseite`, `doppelt`, `lassen`.

#### Tokensparend arbeiten (wichtig für Konto B mit Pro-Plan)

- Das Skript macht die Masse. Claude liest **nie** `plan.json` oder `bestand_*.tsv`, sondern nur die Zusammenfassung,
  die `pruefen`/`ausfuehren` ausgeben, und `unsicher.tsv` – bei vielen Fällen stückweise (`sed -n 1,40p`).
- Unsichere gesammelt klären: mehrere Namen auf einmal mit `suche Name_mit_Unterstrich:Jahr Anderer:2011 …`,
  dann **ein** `entscheiden` mit allen Nummern. Kontaktbogen (`vorschau`) nur, wenn Name und Datei gar nichts hergeben.
- Lange Läufe mit nohup starten und nur `fortschritt.txt` lesen, nicht das ganze Log.
- `bericht.md` ist für Lukas (lang). Claude fasst daraus nur die Zahlen und Auffälligkeiten zusammen.

### B) Bibliothek – Filme, die schon in Jellyfin sind

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
| `zurueck [--los]` | letzte umbenennen-Verschiebung rückgängig (Protokoll `/media/Filme/.filmsortierer_log.jsonl`) |

Für Standbilder braucht es ffmpeg (`apk add --no-cache ffmpeg` im File editor, verschwindet beim Neustart der App).

## Plan für die große Platte (Stand 02.11.2026)

0. **Vorgaben Lukas:** Nichts darf verloren gehen. Vorher die vorhandene Ordnung festhalten (`bestand`). Die Musik ist schon
   geordnet – ob die Ordnung bleibt, entscheidet Lukas. Viele Doppelte, auch in verschiedenen Ordnern. Zusatzdateien neben
   Filmen erst beiseitelegen, löschen nur nach OK. Möglichst tokensparend. Ziel: Filme in die Jellyfin-Filmbibliothek,
   Serien getrennt, Musik als eigener Reiter in Jellyfin.
1. **Platz:** 14-TB-Platte Filme4 (real 12,7 TB, NTFS). Alle Filme kommen dorthin, auch die jetzigen von der 1-TB-SSD
   am Pi (`/media/Filme`, auf die kommen keine weiteren) – mit `kopieren`, die SSD-Fassung bleibt, bis alles läuft.
2. **Wo es läuft:** Am Pi direkt auf der Platte (`/media/Filme4`, gleiche Platte = Verschieben ohne Kopieren). Am PC geht
   es genauso, falls die Platte dort hängt (z. B. `E:\`).
3. **Danach in Jellyfin:** `/media/Filme4/Kino/Filme` als weiteren Ordner der Bibliothek „Filme“, `/media/Filme4/Kino/Serien`
   zur Bibliothek „Serien“, neue Bibliothek „Musik“ mit `/media/Filme4/Kino/Musik`. Achtung: Merkliste, Gesehen und
   Bewertungen in HA hängen an den HA-IDs (alte Plex-IDs bzw. `jf:<Jellyfin-ID>`) – Filme, die Jellyfin neu anlegt,
   bekommen neue IDs. Die Apps finden sie über den Titel wieder (FilmIndex), trotzdem erst mit wenigen Filmen testen.
4. **Trailer:** Dafür braucht es keine Dateien (TMDB/KinoCheck in der Kino-Oberfläche).
