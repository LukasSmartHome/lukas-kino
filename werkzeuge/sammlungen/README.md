# Sammlungs-Kacheln (Lukas Kino)

Eigene Bilder für die Filmreihen (Wunsch Lukas 21.11.2026): je Reihe ein selbst gezeichnetes Motiv (Weltall, Wüste,
Nacht-Stadt, Nebel …) mit passender Schrift – keine Filmbilder, Figuren oder Logos.

- `designs.py` – je Reihe (Name wie in Jellyfin nach `kino_pflege.py reihen`) Motiv, Farben, Schrift, Zeilen.
- `motive.py` – die Motive, `gen.py` – rendert 392 x 556 (2x der Kachel ohne Stapel) nach `bilder/` plus `index.json`.
- Schriften: Google Fonts (OFL/Apache), z. B. Orbitron, Cinzel, Bebas Neue, Anton, Rye, Creepster, Bangers, Luckiest Guy,
  Fredoka, Playfair Display, Oswald, Audiowide, Righteous, Lobster, Pacifico, Abril Fatface, Black Ops One,
  UnifrakturMaguntia (Ordner per `FONTS=` angeben).
- Auf dem Pi liegen die Bilder in `/share/kino/reihen/` (Port 8098), `kino_pflege.py reihen` trägt sie in
  `/share/kino/reihen.json` ein (`kachel`). Neue Reihe ohne eigenes Bild: Kachel aus dem Poster des ersten Films.
