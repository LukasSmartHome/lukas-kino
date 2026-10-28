# Lukas Kino

Eigene, werbefreie Startseite für Lukas' TCL-Fernseher (Google TV, Android 14).

- Großes Hintergrundbild, Uhrzeit links oben, Bildname bzw. Land rechts oben.
- Unten die App-Kacheln mit den echten Bannern der Apps; gedrückt halten = verschieben (OK legt ab), nochmal halten = „Deinstallieren“ darüber (hoch, dann OK).
- Hoch: nur das Bild. Runter: Kacheln zurück. Nochmal runter: Suche nach Apps und Spielen. Home oder Zurück: an den Anfang.
- PlayStation-Kachel wechselt auf HDMI 2.

## Aufbau

- `app/src/main/assets/index.html` – die ganze Oberfläche.
- `app/src/main/java/de/lukas/kino/MainActivity.java` – liefert Apps, Bilder, Hintergründe und die Tasten der Fernbedienung.
- Hintergrundbilder liegen nur auf dem Fernseher in `Android/data/de.lukas.kino/files/hintergruende/` (nicht im Repository). Der Dateiname ohne Endung wird rechts oben angezeigt; beginnt er mit `_`, wird nichts angezeigt.

## Bauen

GitHub Actions baut bei jedem Push die APK und hängt sie als `lukas-kino.apk` an ein Release.
Der Schlüssel in `signing/` ist absichtlich öffentlich: Er sorgt nur dafür, dass neue Versionen über die alte installiert werden können.

## Startseite

Lukas Kino ist die einzige Startseite: Die Google-TV-Startseite (`com.google.android.apps.tv.launcherx`) und `com.google.android.tungsten.setupwraith` sind per `pm disable-user --user 0` abgeschaltet, dadurch landet die Home-Taste direkt hier.
Rückgängig: `pm enable com.google.android.apps.tv.launcherx` und `pm enable com.google.android.tungsten.setupwraith`.

Home und Zurück springen immer an den Anfang (erste Kachel).

Reserve: Der Bedienungshilfe-Dienst `StartseitenWaechter` holt Lukas Kino nach vorn, falls die Google-Startseite doch wieder aufgeht (z. B. nach einem Update), und einmal nach dem Hochfahren. Auf TCL braucht er `appops set de.lukas.kino AUTO_START allow`, sonst wird er nicht gestartet.
