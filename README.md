# Lukas Kino

Eigene, werbefreie Startseite für Lukas' TCL-Fernseher (Google TV, Android 14).

- Großes Hintergrundbild, Uhrzeit links oben, Bildname bzw. Land rechts oben.
- Unten die App-Kacheln mit den echten Bannern der Apps; gedrückt halten = verschieben, OK = ablegen, nochmal OK = fertig.
- Hoch: nur das Bild. Runter: Kacheln zurück. Nochmal runter: Suche nach Apps und Spielen.
- PlayStation-Kachel wechselt auf HDMI 2.

## Aufbau

- `app/src/main/assets/index.html` – die ganze Oberfläche.
- `app/src/main/java/de/lukas/kino/MainActivity.java` – liefert Apps, Bilder, Hintergründe und die Tasten der Fernbedienung.
- Hintergrundbilder liegen nur auf dem Fernseher in `Android/data/de.lukas.kino/files/hintergruende/` (nicht im Repository). Der Dateiname ohne Endung wird rechts oben angezeigt; beginnt er mit `_`, wird nichts angezeigt.

## Bauen

GitHub Actions baut bei jedem Push die APK und hängt sie als `lukas-kino.apk` an ein Release.
Der Schlüssel in `signing/` ist absichtlich öffentlich: Er sorgt nur dafür, dass neue Versionen über die alte installiert werden können.
