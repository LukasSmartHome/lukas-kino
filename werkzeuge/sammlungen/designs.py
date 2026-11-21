# Je Filmreihe ein eigenes Motiv mit passender Farbe und Schrift (Claude, Konto B, 21.11.2026).
# Nur allgemeine Stimmungen (Weltall, Wüste, Nacht-Stadt …) – keine Figuren, Logos oder Szenen aus den Filmen.
from motive import W, H

D = {}


def d(name, motiv, zeilen, **kw):
    D[name] = dict(motiv=motiv, zeilen=zeilen, **kw)


# --- Science-Fiction
d("Star Wars", "weltall", ["STAR", "WARS"], font="Orbitron", var="Black", sperren=0.06, pos="oben", max=110, leuchten="#5FA8FF",
  p=dict(planet=(-40, H * 0.84, 210), planet_hell="#E0A060", planet_dunkel="#1A0E08", rand="#FFD9A0",
         nebelfarben=["#24308F", "#5B2A8F", "#13627A"]), seed=11)
d("Star Trek", "warp", ["STAR", "TREK"], font="Audiowide", pos="unten", max=96, leuchten="#3FA0FF", dy=-10, seed=3)
d("Attraction", "weltall", ["ATTRACTION"], font="Audiowide", pos="oben", max=70, rand=18, leuchten="#3FFFB0",
  p=dict(planet=(W + 30, H * 0.72, 170), planet_hell="#4FA88A", planet_dunkel="#03120D", rand="#9FFFD9",
         nebelfarben=["#0F5A4A", "#1C3F8F", "#3A8F6A"]), seed=21)
d("Riddick", "finsternis", ["RIDDICK"], font="Orbitron", var="Black", sperren=0.04, pos="unten", max=62, leuchten="#FF9F3A", seed=5,
  p=dict(korona="#FFB547", nebelfarben=["#3A1C0C", "#2A1A3A"]))
d("Avatar", "dschungel", ["AVATAR"], font="Orbitron", var="Bold", sperren=0.05, pos="oben", max=90, leuchten="#33E0FF",
  p=dict(oben="#071B33", unten="#020A12", schichten=["#06243A", "#041A2A", "#020E18"],
         leuchten=["#3FF2FF", "#C04DFF", "#4DFFB0", "#3F8CFF"]), seed=8)
d("Pacific Rim", "sturm", ["PACIFIC", "RIM"], font="Orbitron", var="Black", sperren=0.04, pos="oben", max=70, leuchten="#2FD0FF",
  p=dict(neon="#1FB8FF"), seed=4)
d("Man of Steel", "strahlen", ["MAN OF", "STEEL"], font="Oswald", var="Bold", sperren=0.06, pos="unten", max=96, kontur="#0B2347", kontur_breite=7, schatten_stark=1.0,
  p=dict(oben="#3C7FCB", unten="#0B2347", strahl="#DDEEFF", kern="#FFF6DA", wolke="#6F8DAD"), seed=2)

# --- Marvel / Comic
d("Marvel Cinematic Universe", "strahlen", ["MARVEL"], font="Anton", sperren=0.05, pos="mitte", max=104,
  unter="CINEMATIC UNIVERSE", unter_font="Oswald", unter_gr=26, unter_var="SemiBold", unter_farbe="#FFD36B", leuchten="#FF4A3A",
  p=dict(oben="#3A0508", unten="#0A0103", strahl="#FF6A3A", kern="#FFD36B", wolke="#2A0A10", quelle=(W / 2, H * 0.42)), seed=9)
d("Spider-Man", "stadt", ["SPIDER-MAN"], font="Bangers", sperren=0.03, pos="oben", max=78, kontur="#0A1B4A", kontur_breite=7, farbe="#FFFFFF",
  leuchten="#FF3B3B", p=dict(oben="#0B1640", unten="#3A1A5C", glow="#FF2E4A", fenster=["#FFD27A", "#7FB8FF"],
                             neon=[("#2E6BFF", W * 0.2), ("#FF2E4A", W * 0.8)]), seed=12)
d("Deadpool", "comic", ["DEADPOOL"], font="Bangers", sperren=0.03, pos="mitte", max=92, kontur="#000000", kontur_breite=9,
  p=dict(a="#B3121B", b="#5C0910", kern="#FF5A3A", punkte="#0B0204", punktstark=0.6), seed=1)
d("Venom", "tropfen", ["VENOM"], font="Anton", sperren=0.12, pos="unten", max=110, leuchten="#5FD0E0", leucht_stark=0.5, seed=13)
d("Die Unglaublichen", "comic", ["DIE", "UNGLAUB-", "LICHEN"], font="LuckiestGuy", pos="mitte", max=64, kontur="#3A0A00", kontur_breite=7,
  p=dict(a="#F2711C", b="#C1440E", kern="#FFD36B", punkte="#5A1400", punktstark=0.45, mitte=(W / 2, H * 0.55)), seed=6)

# --- Fantasy / Abenteuer
d("Der Hobbit", "berge", ["DER", "HOBBIT"], font="Cinzel", var="Black", pos="oben", max=90, farbe="#3A2A12", kontur="#FFF1CC", kontur_breite=5, schatten=False,
  p=dict(oben="#F7D59A", unten="#7FAF6A", sonne=(W * 0.72, H * 0.3, 34), sonnenfarbe="#FFF4D6", sonnenlicht="#FFE3A0",
         schichten=["#6E9A5A", "#4F7D45", "#335E33", "#1C3D22"], hub=60, dunst=True, dunstfarbe="#F7E3B0"), seed=14)
d("Die Tribute von Panem", "glut", ["DIE", "TRIBUTE", "VON PANEM"], font="Cinzel", var="Black", pos="unten", max=80, farbe="#FFD9A0",
  leuchten="#FF6A1A", seed=15)
d("Die Twilight Saga", "wald", ["TWILIGHT"], font="PlayfairDisplay", var="Bold", sperren=0.06, pos="oben", max=80,
  unter="DIE SAGA", unter_font="Oswald", unter_gr=24, unter_var="Medium", unter_farbe="#E04A4A",
  p=dict(oben="#1A222C", unten="#4A5560", mond=(W * 0.25, H * 0.16), schichten=["#2F3A44", "#202930", "#141A20", "#080B0E"],
         nebel="#B9C4CE", nebelstark=0.4), seed=16)
d("The Huntsman", "wald", ["THE", "HUNTSMAN"], font="Cinzel", var="Black", pos="unten", max=58, farbe="#E6F2FF", leuchten="#7FC4FF",
  p=dict(oben="#0E2236", unten="#7FA6C4", mond=(W * 0.7, H * 0.18), mondfarbe="#F2F8FF",
         schichten=["#3E5C78", "#2A4560", "#1A2F45", "#0B1826"], nebel="#DDEBFA", nebelstark=0.45), seed=17)
d("Asterix & Obelix", "wald", ["ASTERIX", "& OBELIX"], font="LuckiestGuy", pos="oben", max=64, farbe="#FFD23F", kontur="#3A2A00",
  kontur_breite=7, p=dict(oben="#7FC8F0", unten="#F8E7A8", spitz=False, schichten=["#7DB04A", "#5A9A38", "#3F7A2A", "#24521A"],
                          nebel="#FFF3C0", nebelstark=0.25), seed=18)
d("Drachenzähmen leicht gemacht", "sturm", ["DRACHEN-", "ZÄHMEN", "LEICHT", "GEMACHT"], font="Fredoka", var="Bold", pos="oben", max=78,
  kontur="#0A2233", kontur_breite=7, p=dict(oben="#16384F", unten="#3E6F88", wellen=["#2A5A72", "#1C4258", "#0E2A3A"], blitzfarbe="#DDF4FF"), seed=19)
d("Indiana Jones", "duenen", ["INDIANA", "JONES"], font="Rye", pos="unten", max=80, farbe="#FFE9C0", kontur="#3A1A08", kontur_breite=5, seed=20)
d("Jurassic Park", "dschungel", ["JURASSIC", "PARK"], font="Anton", sperren=0.04, pos="oben", max=80, farbe="#F2C14E",
  p=dict(vulkan=True), seed=22)
d("Aquaman", "meer", ["AQUAMAN"], font="Cinzel", var="Black", pos="oben", max=60, farbe="#FFE39A", leuchten="#2FD0C8", leucht_stark=0.6,
  p=dict(grund="#04121F"), seed=23)
d("Maze Runner", "labyrinth", ["MAZE", "RUNNER"], font="BlackOpsOne", pos="mitte", max=90, kontur="#0A1206", kontur_breite=7, farbe="#F0F8D8", leuchten="#9FDF5A",
  leucht_stark=0.6, seed=24)
d("Die Bestimmung", "stadt", ["DIE", "BESTIMMUNG"], font="Oswald", var="Bold", sperren=0.08, pos="oben", max=60,
  p=dict(oben="#5E6E7E", unten="#B8C2CA", hinten="#4A5662", vorne="#1E252C", fensteranteil=0.02, glow="#E8E2D0"), seed=25)

# --- Action / Thriller
d("Mission: Impossible", "laser", ["MISSION:", "IMPOSSIBLE"], font="BebasNeue", sperren=0.06, pos="mitte", max=88, leuchten="#FF3A3A",
  leucht_stark=0.5, seed=26)
d("James Bond", "streifen", ["JAMES", "BOND"], font="PlayfairDisplay", var="Black", sperren=0.08, pos="mitte", max=96, farbe="#E8C878",
  p=dict(oben="#0A0A0C", unten="#000000", linie="#C9A44C", rahmen=True, licht=((W * 0.5, H * 0.45), "#6A5420")), seed=27)
d("Kingsman", "streifen", ["KINGS", "MAN"], font="Cinzel", var="Black", pos="mitte", max=96, farbe="#E8C878",
  p=dict(oben="#10234A", unten="#06102A", linie="#C9A44C", rahmen=True, abstand=14), seed=28)
d("John Wick", "stadt", ["JOHN WICK"], font="BebasNeue", sperren=0.1, pos="oben", max=78, leuchten="#B04DFF",
  p=dict(oben="#0A0618", unten="#24104A", hinten="#1E1238", vorne="#06040E", fenster=["#B04DFF", "#4DD2FF", "#FF4DC4"],
         regen=True, neon=[("#7A2DFF", W * 0.3), ("#2D8CFF", W * 0.75)]), seed=29)
d("The Equalizer", "stadt", ["THE", "EQUALIZER"], font="Oswald", var="Bold", sperren=0.06, pos="oben", max=66, leuchten="#FF9A3A",
  leucht_stark=0.6, p=dict(oben="#0C0E12", unten="#2A2016", hinten="#1A1812", vorne="#060505", fenster=["#FFB347", "#FFD27A"],
                           regen=True, glow="#FF8A2A"), seed=30)
d("Jack Reacher", "strasse", ["JACK", "REACHER"], font="Oswald", var="Bold", sperren=0.08, pos="oben", max=78,
  p=dict(oben="#0B1626", unten="#2A4060", sonne="#6A8CB8", boden="#0A0F18", huegel="#101826", strasse="#05070C", linie="#E8E2C8",
         lichter="#FFE6A0", horizont=0.52), seed=31)
d("Rambo", "dschungel", ["RAMBO"], font="BlackOpsOne", sperren=0.06, pos="oben", max=88, farbe="#E2D2A0",
  p=dict(oben="#3C4A30", unten="#0E140A", schichten=["#2A3A22", "#1A2614", "#0C140A"]), seed=32)
d("Bad Boys", "stadt", ["BAD BOYS"], font="Anton", sperren=0.06, pos="oben", max=84, leuchten="#FF4FA3",
  p=dict(oben="#FF7E5F", unten="#FEB47B", hinten="#A24A6A", vorne="#2B0F2E", fenster=["#FFE08A", "#FF9AD5"], glow="#FFD08A"), seed=33)
d("R.E.D.", "glut", ["R.E.D."], font="Anton", sperren=0.08, pos="mitte", max=120, leuchten="#FF2A2A",
  p=dict(oben="#120202", unten="#4A0606", glut="#FF2A1A", funken=["#FF6A3A", "#FFB347", "#FF2A2A"]), seed=34)
d("Nobody", "stadt", ["NOBODY"], font="Anton", sperren=0.12, pos="oben", max=86, leuchten="#E04A3A", leucht_stark=0.5,
  p=dict(oben="#14161C", unten="#3A3430", hinten="#24252A", vorne="#0A0B0D", fenster=["#FFC870"], glow="#C8783A"), seed=35)
d("One Shot", "stadt", ["ONE SHOT"], font="BlackOpsOne", sperren=0.04, pos="oben", max=72,
  p=dict(oben="#1C2430", unten="#4A5868", hinten="#2E3846", vorne="#0C1016", fenster=["#E8EEF5"], fensteranteil=0.08, regen=True), seed=36)
d("Furioza", "stadt", ["FURIOZA"], font="Anton", sperren=0.1, pos="oben", max=90, leuchten="#FF3A2A",
  p=dict(oben="#1A0606", unten="#5A1A10", hinten="#2A0E0C", vorne="#080202", fenster=["#FF6A3A"], fensteranteil=0.08,
         neon=[("#FF3A1A", W * 0.5)], glow="#FF4A1A"), seed=37)
d("Sniper", "fadenkreuz", ["SNIPER"], font="BlackOpsOne", sperren=0.08, pos="unten", max=80,
  p=dict(oben="#C8A27A", unten="#4A3A3A", schichten=["#6A5048", "#4A3830", "#2A201C", "#120E0C"], hub=40,
         sonne=(W * 0.5, H * 0.42, 22), sonnenfarbe="#FFE0B0"), seed=38)
d("Escape Plan", "gitter", ["ESCAPE", "PLAN"], font="BlackOpsOne", pos="mitte", max=80, leuchten="#9FC4FF", leucht_stark=0.4, seed=39)
d("Sherlock Holmes", "nebel_grusel", ["SHERLOCK", "HOLMES"], font="PlayfairDisplay", var="Black", pos="oben", max=70, farbe="#F2E2C0",
  p=dict(oben="#2A2418", unten="#0A0806", fenster=(W * 0.72, H * 0.5), fensterlicht="#E8B060", nebel="#8A7A60"), seed=40)
d("Sisu", "berge", ["SISU"], font="Anton", sperren=0.2, pos="oben", max=120, farbe="#FFF2DA",
  p=dict(oben="#E8A060", unten="#5A4A50", sonne=(W * 0.3, H * 0.4, 30), schichten=["#7A6060", "#5A4448", "#3A2C30", "#1E1618"],
         hub=40, dunst=True, dunstfarbe="#F0B880"), seed=41)

# --- Horror / dunkel
d("Conjuring", "nebel_grusel", ["CONJURING"], font="Cinzel", var="Black", sperren=0.0, pos="oben", max=90, rand=18, farbe="#E8E2D8",
  p=dict(haus=(W * 0.5, H * 0.66), fenster=(W * 0.5, H * 0.6), fensterlicht="#C89A4A"), seed=42)
d("Es", "nebel_grusel", ["ES"], font="Creepster", pos="oben", max=260, farbe="#D7261E", schatten_stark=0.9,
  p=dict(oben="#0E1214", unten="#020303", regen=True, akzent=((W * 0.5, H * 0.95), "#7A0A0A", 140), nebel="#4A5458"), seed=43)
d("The Purge", "stadt", ["THE", "PURGE"], font="BlackOpsOne", pos="oben", max=90, leuchten="#FF2A2A",
  p=dict(oben="#05040A", unten="#1A0A1A", hinten="#120A16", vorne="#030205", fenster=["#FF2A2A", "#2A6AFF"], fensteranteil=0.1,
         neon=[("#FF1A1A", W * 0.25), ("#1A4AFF", W * 0.75)]), seed=44)
d("Resident Evil", "nebel_grusel", ["RESIDENT", "EVIL"], font="BebasNeue", sperren=0.1, pos="oben", max=86, leuchten="#4AFF8A",
  leucht_stark=0.45, p=dict(oben="#06140C", unten="#010402", nebel="#2E5A3E", akzent=((W * 0.5, H * 0.75), "#1A6A3A", 160)), seed=45)
d("Underworld", "nebel_grusel", ["Under", "world"], font="UnifrakturMaguntia", pos="oben", max=110, farbe="#D8E6F5", leuchten="#3A7AC8",
  leucht_stark=0.6, p=dict(oben="#0A1424", unten="#02050A", nebel="#3A5A7A", regen=True, akzent=((W * 0.75, H * 0.2), "#5A8AC8", 70)), seed=46)

# --- Familie / Komödie / Gefühl
d("Ich – Einfach unverbesserlich", "comic", ["ICH –", "EINFACH", "UNVER-", "BESSERLICH"], font="Fredoka", var="Bold", pos="mitte",
  max=72, farbe="#1E3F8F", schatten=False, kontur="#FFFFFF", kontur_breite=5,
  p=dict(a="#FFD84A", b="#FFC928", kern="#FFF4B0", punkte="#3A6FDB", punktstark=0.35, mitte=(W / 2, H * 0.45)), seed=47)
d("Cars", "strasse", ["CARS"], font="LuckiestGuy", pos="oben", max=120, farbe="#FFFFFF", kontur="#7A1A0A", kontur_breite=8,
  leuchten="#FF7A2A", p=dict(oben="#FF9A4A", unten="#C24A6A", sonne="#FFE0A0", boden="#5A2A2E", huegel="#7A3A3E", strasse="#1A1418"), seed=48)
d("Ice Age", "schnee", ["ICE AGE"], font="LuckiestGuy", pos="oben", max=92, farbe="#FFFFFF", kontur="#0E4A7A", kontur_breite=8,
  p=dict(risse=True, huegel=["#E8F6FF", "#C8E6F8"]), seed=49)
d("Zoomania", "stadt", ["ZOOMANIA"], font="LuckiestGuy", pos="oben", max=80, farbe="#FFFFFF", kontur="#1A3A6A", kontur_breite=7,
  p=dict(oben="#6FC8F0", unten="#FFD9A0", hinten="#7AA0C8", vorne="#3A5A88", fenster=["#FFE08A", "#FFFFFF", "#9FE8FF"],
         fensteranteil=0.3, alle_fenster=True, glow="#FFF0C0"), seed=50)
d("Santa Clause", "schnee", ["SANTA", "CLAUSE"], font="Lobster", pos="oben", max=88, farbe="#FFFFFF", leuchten="#FF3A3A", leucht_stark=0.6,
  p=dict(oben="#0B1A3A", unten="#24487A", huegel=["#DDEBF8", "#F4F9FF"], warm=((W * 0.5, H * 0.72), "#FFB060"), flocken=220), seed=51)
d("Mamma Mia!", "griechisch", ["Mamma", "Mia!"], font="Pacifico", pos="oben", max=80, dy=-14, farbe="#FFFFFF", leuchten="#0E6BA8", leucht_stark=0.6,
  seed=52)
d("Pitch Perfect", "klang", ["PITCH", "PERFECT"], font="Righteous", pos="oben", max=90, farbe="#FFFFFF", leuchten="#FF4FA3", seed=53)
d("Mama gegen Papa", "geteilt", ["MAMA", "gegen", "PAPA"], font="LuckiestGuy", pos="mitte", max=96, kontur="#1A1A2A", kontur_breite=7,
  zeilenhoehe=0.95, seed=54)
d("Ostwind", "wiese", ["OSTWIND"], font="PlayfairDisplay", var="Black", sperren=0.06, pos="unten", max=84, farbe="#FFF2DE", dy=-20, seed=55)
d("Eberhofer", "bayern", ["EBERHOFER"], font="AbrilFatface", pos="mitte", max=110, rand=16, farbe="#0E3A66", kontur="#FFFFFF", kontur_breite=6,
  schatten=False, dy=-40, seed=56)
d("Die Unfassbaren", "funkeln", ["DIE", "UNFASSBAREN"], font="AbrilFatface", pos="mitte", max=96, leuchten="#B07AFF", seed=57)

# --- Western / Skandinavien
d("Manitu", "berge", ["MANITU"], font="Rye", pos="unten", max=96, farbe="#FFE2A8",
  p=dict(oben="#F2B25A", unten="#C8603A", sonne=(W * 0.5, H * 0.46, 40), schichten=["#B86A3A", "#8E4A2A", "#5E2E1A", "#2E160C"],
         hub=26, glatt=0.4), seed=58)
d("Winnetou – Der Mythos lebt", "berge", ["WINNETOU"], font="Rye", pos="unten", max=100, rand=16, farbe="#FFE2A8",
  unter="DER MYTHOS LEBT", unter_font="Oswald", unter_gr=24, unter_var="SemiBold", unter_farbe="#FFE2A8",
  p=dict(oben="#5A3A7A", unten="#F08A4A", sonne=(W * 0.65, H * 0.5, 30), schichten=["#7A4A5A", "#5A3046", "#3A1E30", "#1E0E18"], hub=50),
  seed=59)
d("Millennium", "schnee", ["MILLENNIUM"], font="Oswald", var="Light", sperren=0.05, pos="oben", max=96, rand=16,
  unter="DIE TRILOGIE", unter_font="Oswald", unter_gr=22, unter_var="Medium", unter_farbe="#C8D6E2",
  p=dict(oben="#1A2430", unten="#05080C", huegel=["#2A3440", "#151C24"], flocken=120), seed=60)
d("Millennium – Neuverfilmung", "schnee", ["MILLENNIUM"], font="Oswald", var="Light", sperren=0.05, pos="oben", max=96, rand=16,
  unter="NEUVERFILMUNG", unter_font="Oswald", unter_gr=22, unter_var="Medium", unter_farbe="#E05A4A",
  p=dict(oben="#24181A", unten="#060404", huegel=["#33262A", "#1A1214"], flocken=120, warm=((W * 0.2, H * 0.7), "#8A2A1A")), seed=61)

DESIGNS = D
