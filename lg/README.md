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
