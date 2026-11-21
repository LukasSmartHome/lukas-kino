# Motive für die Sammlungs-Kacheln von Lukas Kino (Claude, Konto B, 21.11.2026).
# Alles selbst gezeichnet: Verläufe, Formen, Licht. Keine Figuren, keine Logos, keine Filmbilder.
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageChops

W, H = 392, 556


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def verlauf(oben, unten, w=W, h=H, kurve=1.0):
    a, b = np.array(rgb(oben), float), np.array(rgb(unten), float)
    t = (np.linspace(0, 1, h) ** kurve)[:, None, None]
    arr = a * (1 - t) + b * t
    arr = np.repeat(arr, w, axis=1)
    return Image.fromarray(arr.clip(0, 255).astype("uint8"), "RGB")


def licht(img, xy, r, farbe, staerke=1.0, blur=None):
    """Weicher Lichtfleck (addiert)."""
    l = Image.new("RGB", img.size, 0)
    d = ImageDraw.Draw(l)
    c = tuple(int(v * staerke) for v in rgb(farbe))
    x, y = xy
    d.ellipse([x - r, y - r, x + r, y + r], fill=c)
    l = l.filter(ImageFilter.GaussianBlur(blur or r * 0.6))
    return ImageChops.add(img, l)


def ueber(img, ebene, alpha):
    return Image.blend(img, ebene, alpha)


def maske_auf(img, farbe, maske):
    """Farbe dort auftragen, wo die Maske (L) hell ist."""
    f = Image.new("RGB", img.size, rgb(farbe))
    return Image.composite(f, img, maske)


def koernung(img, rng, stark=5):
    a = np.array(img, np.int16)
    a += rng.integers(-stark, stark + 1, a.shape[:2])[:, :, None]
    return Image.fromarray(a.clip(0, 255).astype("uint8"), "RGB")


def vignette(img, stark=0.55):
    yy, xx = np.mgrid[0:H, 0:W]
    d = np.sqrt(((xx - W / 2) / (W * 0.62)) ** 2 + ((yy - H * 0.45) / (H * 0.62)) ** 2)
    f = 1 - stark * np.clip(d - 0.55, 0, 1) ** 1.4
    a = np.array(img, float) * f[:, :, None]
    return Image.fromarray(a.clip(0, 255).astype("uint8"), "RGB")


def kamm(rng, n, y0, hub, glatt=0.55, w=W):
    """Bergkamm (Mittelpunkt-Verschiebung) als Liste von y-Werten über die Breite."""
    pkt = [y0 + rng.uniform(-hub, hub), y0 + rng.uniform(-hub, hub)]
    while len(pkt) < n:
        neu = []
        for i in range(len(pkt) - 1):
            neu += [pkt[i], (pkt[i] + pkt[i + 1]) / 2 + rng.uniform(-hub, hub)]
        neu.append(pkt[-1])
        pkt = neu
        hub *= glatt
    xs = np.linspace(0, w, len(pkt))
    return list(zip(xs, pkt))


def flaeche_unter(img, punkte, farbe):
    d = ImageDraw.Draw(img)
    d.polygon(punkte + [(W, H), (0, H)], fill=rgb(farbe))


# ------------------------------------------------------------------ Motive

def m_weltall(rng, p):
    img = verlauf(p.get("oben", "#0B0F2A"), p.get("unten", "#02030A"))
    for _ in range(p.get("nebel", 7)):
        c = rng.choice(p.get("nebelfarben", ["#3B2A8F", "#1E6FA8", "#A8325E"]))
        img = licht(img, (rng.uniform(-40, W + 40), rng.uniform(0, H * 0.8)), rng.uniform(60, 150), c, rng.uniform(0.25, 0.5))
    d = ImageDraw.Draw(img)
    for _ in range(420):
        x, y, s = rng.uniform(0, W), rng.uniform(0, H), rng.uniform(0.3, 1.0)
        v = int(120 + 135 * s)
        d.point((x, y), fill=(v, v, min(255, v + 10)))
    for _ in range(14):
        img = licht(img, (rng.uniform(0, W), rng.uniform(0, H * 0.85)), rng.uniform(2, 5), "#FFFFFF", 0.9, 4)
    if p.get("planet"):
        cx, cy, r = p["planet"]
        pl = Image.new("L", img.size, 0)
        ImageDraw.Draw(pl).ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
        körper = verlauf(p.get("planet_hell", "#3A6EA5"), p.get("planet_dunkel", "#06101F"))
        körper = körper.rotate(35, resample=Image.BICUBIC, expand=False, fillcolor=rgb(p.get("planet_dunkel", "#06101F")))
        img = Image.composite(körper, img, pl)
        rand = Image.new("L", img.size, 0)
        ImageDraw.Draw(rand).ellipse([cx - r, cy - r, cx + r, cy + r], outline=255, width=3)
        rand = rand.filter(ImageFilter.GaussianBlur(4))
        img = maske_auf(img, p.get("rand", "#9FD3FF"), rand)
    return img


def m_warp(rng, p):
    img = verlauf(p.get("oben", "#030714"), p.get("unten", "#000000"))
    cx, cy = W / 2, H * 0.36
    l = Image.new("RGB", img.size, 0)
    d = ImageDraw.Draw(l)
    for _ in range(260):
        a = rng.uniform(0, 2 * math.pi)
        r1 = rng.uniform(20, 140)
        r2 = r1 + rng.uniform(40, 260)
        c = rgb(rng.choice(p.get("farben", ["#FFFFFF", "#9CC8FF", "#5B8CFF"])))
        d.line([(cx + r1 * math.cos(a), cy + r1 * math.sin(a)), (cx + r2 * math.cos(a), cy + r2 * math.sin(a))],
               fill=c, width=int(rng.choice([1, 1, 2])))
    img = ImageChops.add(img, l.filter(ImageFilter.GaussianBlur(1.2)))
    img = ImageChops.add(img, l.filter(ImageFilter.GaussianBlur(6)))
    return licht(img, (cx, cy), 60, p.get("kern", "#7FB2FF"), 0.8)


def m_glut(rng, p):
    img = verlauf(p.get("oben", "#0A0302"), p.get("unten", "#3A0A02"))
    img = licht(img, (W / 2, H + 40), 260, p.get("glut", "#FF5A1F"), 0.85)
    l = Image.new("RGB", img.size, 0)
    d = ImageDraw.Draw(l)
    for _ in range(170):
        x, y = rng.uniform(0, W), rng.uniform(0, H)
        s = rng.uniform(0.8, 3.2) * (0.4 + y / H)
        c = rgb(rng.choice(p.get("funken", ["#FFB347", "#FF7A1A", "#FFD27A"])))
        d.ellipse([x - s, y - s * 1.6, x + s, y + s * 1.6], fill=c)
    img = ImageChops.add(img, l.filter(ImageFilter.GaussianBlur(0.8)))
    return ImageChops.add(img, l.filter(ImageFilter.GaussianBlur(5)))


def bäume(img, rng, y_basis, hoehe, farbe, dicht=26, spitz=True):
    d = ImageDraw.Draw(img)
    x = -20
    while x < W + 20:
        h = hoehe * rng.uniform(0.55, 1.0)
        b = h * rng.uniform(0.28, 0.42)
        if spitz:
            for k in range(3):
                t = y_basis - h * (0.35 + 0.3 * k)
                d.polygon([(x, t - h * 0.4), (x - b * (1 - 0.25 * k), t + h * 0.35), (x + b * (1 - 0.25 * k), t + h * 0.35)], fill=rgb(farbe))
            d.rectangle([x - 3, y_basis - h * 0.3, x + 3, H], fill=rgb(farbe))
        else:
            d.rectangle([x - 3, y_basis - h * 0.45, x + 3, y_basis], fill=rgb(farbe))
            for _ in range(5):
                rx, ry, rr = x + rng.uniform(-b * 0.7, b * 0.7), y_basis - h * rng.uniform(0.55, 0.95), b * rng.uniform(0.45, 0.8)
                d.ellipse([rx - rr, ry - rr, rx + rr, ry + rr], fill=rgb(farbe))
        x += rng.uniform(dicht * 0.6, dicht * 1.3) * (1.0 if spitz else 1.6)
    d.rectangle([0, y_basis, W, H], fill=rgb(farbe))


def nebelband(img, y, h, farbe, alpha):
    l = Image.new("L", img.size, 0)
    ImageDraw.Draw(l).rectangle([-50, y, W + 50, y + h], fill=int(255 * alpha))
    l = l.filter(ImageFilter.GaussianBlur(h * 0.6))
    return maske_auf(img, farbe, l)


def m_wald(rng, p):
    img = verlauf(p.get("oben", "#1B2433"), p.get("unten", "#3D4A55"))
    if p.get("mond"):
        mx, my = p["mond"]
        img = licht(img, (mx, my), 70, p.get("mondlicht", "#C9D6E8"), 0.6)
        ImageDraw.Draw(img).ellipse([mx - 26, my - 26, mx + 26, my + 26], fill=rgb(p.get("mondfarbe", "#E8EEF5")))
    schichten = p.get("schichten", ["#2C3742", "#1E262E", "#121820", "#080B10"])
    for i, f in enumerate(schichten):
        bäume(img, rng, H * (0.52 + 0.1 * i), 150 + 45 * i, f, dicht=22 + 6 * i, spitz=p.get("spitz", True))
        if i < len(schichten) - 1:
            img = nebelband(img, H * (0.5 + 0.1 * i), 40, p.get("nebel", "#AAB6C2"), p.get("nebelstark", 0.35))
    return img


def m_berge(rng, p):
    img = verlauf(p.get("oben", "#F2A65A"), p.get("unten", "#6B3A5A"), kurve=0.8)
    if p.get("sonne"):
        sx, sy, sr = p["sonne"]
        img = licht(img, (sx, sy), sr * 3, p.get("sonnenlicht", "#FFD38A"), 0.7)
        ImageDraw.Draw(img).ellipse([sx - sr, sy - sr, sx + sr, sy + sr], fill=rgb(p.get("sonnenfarbe", "#FFE9B8")))
    for i, f in enumerate(p.get("schichten", ["#8A4B63", "#5E3350", "#3A2140", "#1E1228"])):
        y0 = H * (0.42 + 0.12 * i)
        flaeche_unter(img, kamm(rng, 65, y0, p.get("hub", 70) * (1 - 0.15 * i)), f)
        if p.get("dunst") and i < 3:
            img = nebelband(img, y0 + 20, 30, p.get("dunstfarbe", "#F5C99A"), 0.18)
    return img


def m_duenen(rng, p):
    img = verlauf(p.get("oben", "#F6B15A"), p.get("unten", "#C4622D"), kurve=0.9)
    sx, sy = p.get("sonne", (W * 0.68, H * 0.3))
    img = licht(img, (sx, sy), 140, p.get("sonnenlicht", "#FFE0A0"), 0.8)
    ImageDraw.Draw(img).ellipse([sx - 36, sy - 36, sx + 36, sy + 36], fill=rgb(p.get("sonnenfarbe", "#FFF1CF")))
    for i, f in enumerate(p.get("schichten", ["#D9853F", "#B8652B", "#8C461C", "#5A2A10"])):
        y0 = H * (0.5 + 0.11 * i)
        ph = rng.uniform(0, 6)
        pkt = [(x, y0 + 26 * math.sin(x / (70 + 25 * i) + ph) + 12 * math.sin(x / 31 + ph * 2)) for x in range(0, W + 8, 8)]
        flaeche_unter(img, pkt, f)
    return img


def m_stadt(rng, p):
    img = verlauf(p.get("oben", "#140C2E"), p.get("unten", "#3A1C5C"))
    if p.get("glow"):
        img = licht(img, (W / 2, H * 0.62), 220, p["glow"], 0.55)
    d = ImageDraw.Draw(img)
    fenster = p.get("fenster", ["#FFD27A", "#FF8AD8", "#7FE0FF"])
    for schicht, (f, y0, hmax) in enumerate(((p.get("hinten", "#2A1F4A"), H * 0.66, 230), (p.get("vorne", "#0D0A1A"), H * 0.76, 300))):
        x = -10
        while x < W:
            bw = rng.uniform(26, 64)
            bh = rng.uniform(hmax * 0.35, hmax)
            d.rectangle([x, y0 - bh, x + bw, H], fill=rgb(f))
            if rng.uniform() < 0.3:
                d.rectangle([x + bw * 0.4, y0 - bh - 22, x + bw * 0.5, y0 - bh], fill=rgb(f))
            if schicht == 1 or p.get("alle_fenster"):
                for fy in np.arange(y0 - bh + 10, H - 10, 11):
                    for fx in np.arange(x + 5, x + bw - 6, 9):
                        if rng.uniform() < p.get("fensteranteil", 0.22):
                            d.rectangle([fx, fy, fx + 4, fy + 5], fill=rgb(rng.choice(fenster)))
            x += bw + rng.uniform(1, 6)
        d.rectangle([0, y0, W, H], fill=rgb(f))
    if p.get("regen"):
        l = Image.new("RGB", img.size, 0)
        dl = ImageDraw.Draw(l)
        for _ in range(420):
            x, y = rng.uniform(-40, W), rng.uniform(-20, H)
            dl.line([(x, y), (x + 6, y + 22)], fill=(110, 120, 150), width=1)
        img = ImageChops.add(img, l)
    if p.get("neon"):
        for c, x in p["neon"]:
            img = licht(img, (x, H * 0.95), 120, c, 0.7)
    return img


def m_meer(rng, p):
    img = verlauf(p.get("oben", "#1C8FB8"), p.get("unten", "#031A33"), kurve=0.8)
    l = Image.new("RGB", img.size, 0)
    d = ImageDraw.Draw(l)
    for _ in range(9):
        x = rng.uniform(-60, W + 60)
        b = rng.uniform(14, 40)
        d.polygon([(x - b, -10), (x + b, -10), (x + b * 4 + rng.uniform(-80, 80), H), (x - b * 2 + rng.uniform(-80, 80), H)], fill=(90, 160, 190))
    img = ImageChops.add(img, l.filter(ImageFilter.GaussianBlur(14)))
    d = ImageDraw.Draw(img)
    for _ in range(40):
        x, y, r = rng.uniform(0, W), rng.uniform(H * 0.3, H), rng.uniform(2, 7)
        d.ellipse([x - r, y - r, x + r, y + r], outline=(170, 225, 240), width=1)
    if p.get("grund"):
        flaeche_unter(img, kamm(rng, 33, H * 0.86, 20), p["grund"])
    return img


def m_sturm(rng, p):
    img = verlauf(p.get("oben", "#0B1420"), p.get("unten", "#1F3B4D"))
    for _ in range(8):
        img = licht(img, (rng.uniform(0, W), rng.uniform(0, H * 0.4)), rng.uniform(60, 120), "#3A4C5E", 0.5)
    if p.get("blitz", True):
        l = Image.new("RGB", img.size, 0)
        d = ImageDraw.Draw(l)
        x, y = W * 0.62, 0
        pts = [(x, y)]
        while y < H * 0.62:
            x += rng.uniform(-28, 28)
            y += rng.uniform(18, 40)
            pts.append((x, y))
        d.line(pts, fill=rgb(p.get("blitzfarbe", "#BFE6FF")), width=3)
        img = ImageChops.add(img, l)
        img = ImageChops.add(img, l.filter(ImageFilter.GaussianBlur(10)))
    for i, f in enumerate(p.get("wellen", ["#1B3A4F", "#122A3A", "#0A1A26"])):
        y0 = H * (0.66 + 0.1 * i)
        ph = rng.uniform(0, 6)
        flaeche_unter(img, [(x, y0 + 18 * math.sin(x / 38 + ph) + 8 * math.sin(x / 13)) for x in range(0, W + 6, 6)], f)
    if p.get("neon"):
        img = licht(img, (W * 0.3, H * 0.62), 90, p["neon"], 0.55)
    return img


def m_strasse(rng, p):
    img = verlauf(p.get("oben", "#FF8A3D"), p.get("unten", "#7A2E5C"), kurve=0.8)
    hy = H * p.get("horizont", 0.5)
    img = licht(img, (W / 2, hy), 150, p.get("sonne", "#FFD08A"), 0.8)
    d = ImageDraw.Draw(img)
    d.rectangle([0, hy, W, H], fill=rgb(p.get("boden", "#2A1A2E")))
    flaeche_unter(img, kamm(rng, 33, hy - 6, 14), p.get("huegel", "#3A2340"))
    d.rectangle([0, hy, W, H], fill=rgb(p.get("boden", "#2A1A2E")))
    d.polygon([(W / 2 - 6, hy), (W / 2 + 6, hy), (W * 0.95, H), (W * 0.05, H)], fill=rgb(p.get("strasse", "#141018")))
    for i in range(9):
        t0, t1 = (i / 9) ** 1.8, ((i + 0.5) / 9) ** 1.8
        y0, y1 = hy + (H - hy) * t0, hy + (H - hy) * t1
        w0, w1 = 1 + 6 * t0, 1 + 6 * t1
        d.polygon([(W / 2 - w0, y0), (W / 2 + w0, y0), (W / 2 + w1, y1), (W / 2 - w1, y1)], fill=rgb(p.get("linie", "#FFD54A")))
    if p.get("lichter"):
        img = licht(img, (W * 0.38, H * 0.9), 50, p["lichter"], 0.8)
        img = licht(img, (W * 0.62, H * 0.9), 50, p["lichter"], 0.8)
    return img


def m_schnee(rng, p):
    img = verlauf(p.get("oben", "#BFE3F5"), p.get("unten", "#2B6A92"), kurve=1.1)
    if p.get("risse"):
        l = Image.new("RGB", img.size, 0)
        d = ImageDraw.Draw(l)
        for _ in range(7):
            x, y = rng.uniform(0, W), rng.uniform(H * 0.4, H)
            a = rng.uniform(0, 2 * math.pi)
            pts = [(x, y)]
            for _ in range(14):
                a += rng.uniform(-0.6, 0.6)
                x += 16 * math.cos(a)
                y += 16 * math.sin(a)
                pts.append((x, y))
            d.line(pts, fill=(200, 240, 255), width=2)
        img = ImageChops.add(img, l.filter(ImageFilter.GaussianBlur(0.6)))
        img = ImageChops.add(img, l.filter(ImageFilter.GaussianBlur(4)))
    if p.get("huegel"):
        for i, f in enumerate(p["huegel"]):
            flaeche_unter(img, kamm(rng, 33, H * (0.66 + 0.1 * i), 30), f)
    if p.get("warm"):
        img = licht(img, p["warm"][0], 90, p["warm"][1], 0.7)
    d = ImageDraw.Draw(img)
    for _ in range(p.get("flocken", 160)):
        x, y, r = rng.uniform(0, W), rng.uniform(0, H), rng.uniform(0.8, 3.0)
        d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255))
    return img


def m_comic(rng, p):
    cx, cy = p.get("mitte", (W / 2, H * 0.4))
    img = Image.new("RGB", (W, H), rgb(p.get("a", "#B3121B")))
    d = ImageDraw.Draw(img)
    n = 28
    for i in range(n):
        if i % 2:
            continue
        a0, a1 = 2 * math.pi * i / n, 2 * math.pi * (i + 1) / n
        d.polygon([(cx, cy), (cx + 900 * math.cos(a0), cy + 900 * math.sin(a0)), (cx + 900 * math.cos(a1), cy + 900 * math.sin(a1))],
                  fill=rgb(p.get("b", "#7A0A12")))
    img = licht(img, (cx, cy), 120, p.get("kern", "#FFB04A"), 0.6)
    pkt = Image.new("L", img.size, 0)
    dp = ImageDraw.Draw(pkt)
    for y in range(0, H + 14, 14):
        for x in range(0, W + 14, 14):
            r = 1 + 5 * (y / H) ** 1.3
            ox = 7 if (y // 14) % 2 else 0
            dp.ellipse([x + ox - r, y - r, x + ox + r, y + r], fill=255)
    return Image.composite(Image.new("RGB", img.size, rgb(p.get("punkte", "#1A0508"))), img, pkt.point(lambda v: int(v * p.get("punktstark", 0.55))))


def m_strahlen(rng, p):
    img = verlauf(p.get("oben", "#2E6FB8"), p.get("unten", "#0D2747"))
    sx, sy = p.get("quelle", (W / 2, -40))
    l = Image.new("RGB", img.size, 0)
    d = ImageDraw.Draw(l)
    for i in range(16):
        a = math.pi * (0.15 + 0.7 * i / 15) + rng.uniform(-0.03, 0.03)
        b = rng.uniform(0.025, 0.06)
        d.polygon([(sx, sy), (sx + 1200 * math.cos(a - b), sy + 1200 * math.sin(a - b)), (sx + 1200 * math.cos(a + b), sy + 1200 * math.sin(a + b))],
                  fill=tuple(int(v * 0.45) for v in rgb(p.get("strahl", "#BFE0FF"))))
    img = ImageChops.add(img, l.filter(ImageFilter.GaussianBlur(8)))
    img = licht(img, (sx, sy + 40), 120, p.get("kern", "#FFF4D6"), 0.9)
    for _ in range(p.get("wolken", 10)):
        x, y = rng.uniform(-40, W + 40), rng.uniform(H * 0.55, H)
        img = licht(img, (x, y), rng.uniform(50, 90), p.get("wolke", "#55708F"), 0.55, 30)
    return img


def m_nebel_grusel(rng, p):
    img = verlauf(p.get("oben", "#0E1214"), p.get("unten", "#020303"))
    if p.get("fenster"):
        fx, fy = p["fenster"]
        img = licht(img, (fx, fy), 90, p.get("fensterlicht", "#C9A15A"), 0.55)
        d = ImageDraw.Draw(img)
        d.rectangle([fx - 18, fy - 28, fx + 18, fy + 28], fill=rgb(p.get("fensterfarbe", "#E8C27A")))
        d.line([(fx, fy - 28), (fx, fy + 28)], fill=(10, 10, 10), width=3)
        d.line([(fx - 18, fy), (fx + 18, fy)], fill=(10, 10, 10), width=3)
    if p.get("haus"):
        d = ImageDraw.Draw(img)
        hx, hy = p["haus"]
        d.polygon([(hx - 110, hy), (hx, hy - 90), (hx + 110, hy), (hx + 100, H), (hx - 100, H)], fill=(6, 7, 8))
    for i in range(5):
        img = nebelband(img, H * (0.55 + 0.09 * i), 46, p.get("nebel", "#5E6A70"), 0.22)
    if p.get("akzent"):
        img = licht(img, p["akzent"][0], p["akzent"][2], p["akzent"][1], 0.6)
    if p.get("regen"):
        l = Image.new("RGB", img.size, 0)
        dl = ImageDraw.Draw(l)
        for _ in range(300):
            x, y = rng.uniform(-30, W), rng.uniform(-20, H)
            dl.line([(x, y), (x + 4, y + 20)], fill=(70, 80, 90), width=1)
        img = ImageChops.add(img, l)
    l = Image.new("RGB", img.size, 0)
    d = ImageDraw.Draw(l)
    for _ in range(26):
        x = rng.uniform(0, W)
        d.line([(x, 0), (x + rng.uniform(-6, 6), H)], fill=(30, 30, 30), width=1)
    return ImageChops.add(img, l)


def m_labyrinth(rng, p):
    img = verlauf(p.get("oben", "#1E2A12"), p.get("unten", "#0A0F06"))
    cw, ch = 14, 20
    zelle = 28
    besucht = np.zeros((ch, cw), bool)
    waende = set()
    stapel = [(0, 0)]
    besucht[0, 0] = True
    while stapel:
        x, y = stapel[-1]
        nb = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if 0 <= x + dx < cw and 0 <= y + dy < ch and not besucht[y + dy, x + dx]]
        if not nb:
            stapel.pop()
            continue
        nx, ny = nb[int(rng.integers(len(nb)))]
        waende.add(((x, y), (nx, ny)))
        waende.add(((nx, ny), (x, y)))
        besucht[ny, nx] = True
        stapel.append((nx, ny))
    m = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(m)
    for y in range(ch):
        for x in range(cw):
            px, py = x * zelle - 0, y * zelle
            if ((x, y), (x + 1, y)) not in waende:
                d.line([(px + zelle, py), (px + zelle, py + zelle)], fill=255, width=7)
            if ((x, y), (x, y + 1)) not in waende:
                d.line([(px, py + zelle), (px + zelle, py + zelle)], fill=255, width=7)
    img = maske_auf(img, p.get("hecke", "#3F6B22"), m.filter(ImageFilter.GaussianBlur(0.8)))
    img = licht(img, (W / 2, H * 0.4), 110, p.get("licht", "#E8F5B0"), 0.5)
    return img


def m_laser(rng, p):
    img = verlauf(p.get("oben", "#05070D"), p.get("unten", "#0D1220"))
    d = ImageDraw.Draw(img)
    for x in range(0, W, 28):
        d.line([(x, 0), (x, H)], fill=rgb(p.get("raster", "#121A2C")), width=1)
    for y in range(0, H, 28):
        d.line([(0, y), (W, y)], fill=rgb(p.get("raster", "#121A2C")), width=1)
    l = Image.new("RGB", img.size, 0)
    dl = ImageDraw.Draw(l)
    for _ in range(p.get("anzahl", 7)):
        y0, y1 = rng.uniform(-50, H + 50), rng.uniform(-50, H + 50)
        dl.line([(-10, y0), (W + 10, y1)], fill=rgb(p.get("laser", "#FF2A2A")), width=2)
    img = ImageChops.add(img, l)
    return ImageChops.add(img, l.filter(ImageFilter.GaussianBlur(7)))


def m_streifen(rng, p):
    img = verlauf(p.get("oben", "#0C1A33"), p.get("unten", "#050B18"))
    l = Image.new("RGB", (W * 2, H * 2), 0)
    d = ImageDraw.Draw(l)
    for x in range(-H * 2, W * 2, p.get("abstand", 22)):
        d.line([(x, 0), (x + H * 2, H * 2)], fill=tuple(int(v * 0.35) for v in rgb(p.get("linie", "#C9A44C"))), width=1)
    img = ImageChops.add(img, l.crop((W // 2, H // 2, W // 2 + W, H // 2 + H)))
    d = ImageDraw.Draw(img)
    if p.get("rahmen"):
        d.rectangle([14, 14, W - 15, H - 15], outline=rgb(p.get("linie", "#C9A44C")), width=2)
        d.rectangle([22, 22, W - 23, H - 23], outline=rgb(p.get("linie", "#C9A44C")), width=1)
    if p.get("licht"):
        img = licht(img, p["licht"][0], 150, p["licht"][1], 0.5)
    return img


def m_funkeln(rng, p):
    img = verlauf(p.get("oben", "#1A0B33"), p.get("unten", "#05020D"))
    for _ in range(22):
        x, y, r = rng.uniform(0, W), rng.uniform(0, H), rng.uniform(8, 30)
        img = licht(img, (x, y), r, rng.choice(p.get("farben", ["#7A4DFF", "#FF4DC4", "#4DD2FF"])), 0.45, r * 0.3)
    l = Image.new("RGB", img.size, 0)
    d = ImageDraw.Draw(l)
    for _ in range(26):
        x, y, s = rng.uniform(0, W), rng.uniform(0, H), rng.uniform(4, 13)
        d.polygon([(x, y - s), (x + s * 0.22, y - s * 0.22), (x + s, y), (x + s * 0.22, y + s * 0.22), (x, y + s), (x - s * 0.22, y + s * 0.22), (x - s, y), (x - s * 0.22, y - s * 0.22)], fill=(255, 245, 220))
    img = ImageChops.add(img, l)
    return ImageChops.add(img, l.filter(ImageFilter.GaussianBlur(5)))


def m_klang(rng, p):
    img = verlauf(p.get("oben", "#FF4FA3"), p.get("unten", "#3A1A7A"))
    d = ImageDraw.Draw(img)
    n = 26
    bw = W / n
    for i in range(n):
        h = (0.15 + 0.85 * abs(math.sin(i * 0.55 + rng.uniform(0, 0.6)))) * H * 0.2
        x = i * bw
        d.rounded_rectangle([x + 3, H * 0.68 - h, x + bw - 3, H * 0.68 + h], radius=5, fill=rgb(p.get("balken", "#FFE3F2")))
    return licht(img, (W / 2, H * 0.58), 160, p.get("glanz", "#FFFFFF"), 0.25)


def m_bayern(rng, p):
    img = verlauf(p.get("oben", "#7FC6F2"), p.get("unten", "#D9F0FF"))
    for i, f in enumerate(p.get("huegel", ["#7DBE4A", "#5DA338", "#3F8226"])):
        flaeche_unter(img, kamm(rng, 33, H * (0.6 + 0.1 * i), 26, 0.5), f)
    d = ImageDraw.Draw(img)
    rh, rw = 26, 30
    for reihe in range(3):
        for k in range(-1, W // rw + 2):
            x = k * rw + (rw / 2 if reihe % 2 else 0)
            y = reihe * rh / 2 * 1.0
            farbe = rgb("#1F78C8") if (k + reihe) % 2 else rgb("#FFFFFF")
            d.polygon([(x, y - rh / 2), (x + rw / 2, y), (x, y + rh / 2), (x - rw / 2, y)], fill=farbe)
    return img


def m_wiese(rng, p):
    img = verlauf(p.get("oben", "#F7B267"), p.get("unten", "#F25C54"), kurve=0.8)
    img = licht(img, (W * 0.7, H * 0.52), 120, p.get("sonne", "#FFE6A8"), 0.8)
    flaeche_unter(img, kamm(rng, 33, H * 0.64, 24), p.get("huegel", "#6B3A2E"))
    d = ImageDraw.Draw(img)
    for _ in range(520):
        x = rng.uniform(0, W)
        h = rng.uniform(30, 120)
        k = rng.uniform(-14, 14)
        d.line([(x, H), (x + k, H - h)], fill=rgb(p.get("gras", "#2A1712")), width=2)
    d.rectangle([0, H - 30, W, H], fill=rgb(p.get("gras", "#2A1712")))
    l = Image.new("RGB", img.size, 0)
    dl = ImageDraw.Draw(l)
    for _ in range(6):
        y = rng.uniform(H * 0.15, H * 0.5)
        ph = rng.uniform(0, 6)
        dl.line([(x, y + 10 * math.sin(x / 40 + ph)) for x in range(-10, W + 10, 6)], fill=(255, 240, 220), width=2)
    return ImageChops.add(img, l.filter(ImageFilter.GaussianBlur(1.5)))


def m_geteilt(rng, p):
    img = Image.new("RGB", (W, H), rgb(p.get("a", "#2F80ED")))
    d = ImageDraw.Draw(img)
    pts = [(W, 0), (W, H)]
    y = H
    x = W * 0.38
    zick = []
    for i in range(14):
        zick.append((x + (18 if i % 2 else -18), H - i * H / 13))
    d.polygon([(W, 0)] + list(reversed(zick)) + [(W, H)], fill=rgb(p.get("b", "#EB5757")))
    img = licht(img, (W * 0.2, H * 0.3), 90, "#FFFFFF", 0.2)
    return licht(img, (W * 0.8, H * 0.3), 90, "#FFFFFF", 0.2)


def m_dschungel(rng, p):
    img = verlauf(p.get("oben", "#2E5A3A"), p.get("unten", "#0B1A10"))
    if p.get("vulkan"):
        d = ImageDraw.Draw(img)
        d.polygon([(W * 0.15, H * 0.62), (W * 0.42, H * 0.3), (W * 0.58, H * 0.3), (W * 0.9, H * 0.62)], fill=rgb("#1C2B22"))
        for _ in range(8):
            img = licht(img, (W * 0.5 + rng.uniform(-30, 60), H * 0.25 - rng.uniform(0, 120)), rng.uniform(25, 45), "#3A4A44", 0.5, 18)
        img = licht(img, (W * 0.5, H * 0.3), 30, "#FF6A2A", 0.7)
    for i, f in enumerate(p.get("schichten", ["#1F3D27", "#13291A", "#08140C"])):
        d = ImageDraw.Draw(img)
        for _ in range(26):
            x, y = rng.uniform(-30, W + 30), H * (0.6 + 0.13 * i) + rng.uniform(-40, 40)
            a = rng.uniform(-1.2, 1.2)
            lb, lh = rng.uniform(40, 90), rng.uniform(12, 24)
            blatt = Image.new("L", (int(lb * 2), int(lb * 2)), 0)
            ImageDraw.Draw(blatt).ellipse([lb - lb, lb - lh, lb + lb, lb + lh], fill=255)
            blatt = blatt.rotate(math.degrees(a), resample=Image.BICUBIC)
            img.paste(Image.new("RGB", blatt.size, rgb(f)), (int(x - lb), int(y - lb)), blatt)
        d.rectangle([0, H * (0.68 + 0.13 * i), W, H], fill=rgb(f))
    if p.get("leuchten"):
        for _ in range(60):
            img = licht(img, (rng.uniform(0, W), rng.uniform(H * 0.45, H)), rng.uniform(2, 5), rng.choice(p["leuchten"]), 0.9, 4)
    return img


def m_fadenkreuz(rng, p):
    img = m_berge(rng, p)
    l = Image.new("RGB", img.size, 0)
    d = ImageDraw.Draw(l)
    cx, cy = W / 2, H * 0.4
    c = rgb(p.get("kreuz", "#E8E8E8"))
    for r in (110, 70):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=c, width=2)
    d.line([(cx - 140, cy), (cx - 14, cy)], fill=c, width=2)
    d.line([(cx + 14, cy), (cx + 140, cy)], fill=c, width=2)
    d.line([(cx, cy - 140), (cx, cy - 14)], fill=c, width=2)
    d.line([(cx, cy + 14), (cx, cy + 140)], fill=c, width=2)
    d.ellipse([cx - 3, cy - 3, cx + 3, cy + 3], fill=rgb(p.get("punkt", "#FF3B30")))
    return ImageChops.add(img, l)


def m_gitter(rng, p):
    img = verlauf(p.get("oben", "#1A1F26"), p.get("unten", "#050608"))
    img = licht(img, (W * 0.5, -20), 230, p.get("licht", "#DCE6F0"), 0.55)
    d = ImageDraw.Draw(img)
    for x in np.arange(18, W, 38):
        d.rectangle([x, 0, x + 9, H], fill=rgb(p.get("stab", "#2A2F36")))
        d.line([(x + 2, 0), (x + 2, H)], fill=rgb(p.get("glanz", "#5A6470")), width=1)
    for y in (H * 0.18, H * 0.82):
        d.rectangle([0, y, W, y + 10], fill=rgb(p.get("stab", "#2A2F36")))
    return img


def m_finsternis(rng, p):
    img = m_weltall(rng, dict(p, nebel=3, planet=None))
    cx, cy, r = W * 0.5, H * 0.38, 120
    img = licht(img, (cx, cy), r * 1.25, p.get("korona", "#FFB547"), 0.95, 22)
    ImageDraw.Draw(img).ellipse([cx - r, cy - r, cx + r, cy + r], fill=(2, 2, 4))
    return img


def m_fluessig(rng, p):
    img = verlauf(p.get("oben", "#05080A"), p.get("unten", "#000000"))
    m = Image.new("L", (W // 4, H // 4), 0)
    d = ImageDraw.Draw(m)
    for _ in range(18):
        x, y, r = rng.uniform(0, W / 4), rng.uniform(H / 8, H / 4), rng.uniform(6, 22)
        d.ellipse([x - r, y - r, x + r, y + r], fill=255)
    for _ in range(10):
        x, y = rng.uniform(0, W / 4), H / 4
        pts = [(x, y)]
        for _ in range(12):
            x += rng.uniform(-6, 6)
            y -= rng.uniform(4, 9)
            pts.append((x, y))
        d.line(pts, fill=255, width=4)
    m = m.resize((W, H), Image.BICUBIC).filter(ImageFilter.GaussianBlur(10)).point(lambda v: 255 if v > 110 else 0).filter(ImageFilter.GaussianBlur(2))
    koerper = verlauf(p.get("hell", "#1E2B33"), p.get("dunkel", "#030506"))
    img = Image.composite(koerper, img, m)
    kante = m.filter(ImageFilter.FIND_EDGES).filter(ImageFilter.GaussianBlur(1.5))
    return maske_auf(img, p.get("glanz", "#9FC4D6"), kante)


def m_griechisch(rng, p):
    img = verlauf("#5EC8F2", "#1B7FC4")
    img = licht(img, (W * 0.75, H * 0.18), 70, "#FFF3C4", 0.9)
    d = ImageDraw.Draw(img)
    d.rectangle([0, H * 0.5, W, H], fill=rgb("#0E6BA8"))
    for y in np.arange(H * 0.52, H, 10):
        d.line([(0, y), (W, y)], fill=rgb("#2A86C0"), width=1)
    flaeche_unter(img, kamm(rng, 17, H * 0.66, 30), "#F4EFE6")
    x = 0
    while x < W:
        bw, bh = rng.uniform(34, 60), rng.uniform(40, 80)
        d.rectangle([x, H * 0.7 - bh, x + bw, H], fill=rgb("#FFFFFF"))
        d.rectangle([x + bw * 0.4, H * 0.7 - bh * 0.6, x + bw * 0.6, H * 0.7 - bh * 0.3], fill=rgb("#1F78C8"))
        if rng.uniform() < 0.35:
            d.pieslice([x + bw * 0.2, H * 0.7 - bh - bw * 0.3, x + bw * 0.8, H * 0.7 - bh + bw * 0.3], 180, 360, fill=rgb("#1F78C8"))
        x += bw + rng.uniform(4, 16)
    return img


def m_tropfen(rng, p):
    img = verlauf(p.get("oben", "#1A2026"), p.get("unten", "#05070A"))
    img = licht(img, (W * 0.5, H * 0.55), 200, p.get("licht", "#1E4A55"), 0.6)
    m = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(m)
    d.rectangle([0, 0, W, 34], fill=255)
    x = -6
    while x < W + 10:
        b = rng.uniform(10, 26)
        l = rng.uniform(40, 330) if rng.uniform() < 0.55 else rng.uniform(10, 60)
        d.rounded_rectangle([x, 0, x + b, l], radius=b / 2, fill=255)
        d.ellipse([x - 3, l - b * 0.9, x + b + 3, l + b * 0.5], fill=255)
        x += b + rng.uniform(-4, 14)
    m = m.filter(ImageFilter.GaussianBlur(2))
    koerper = verlauf(p.get("hell", "#20282E"), p.get("dunkel", "#000000"))
    img = Image.composite(koerper, img, m)
    glanz = m.filter(ImageFilter.FIND_EDGES).filter(ImageFilter.GaussianBlur(1.2))
    glanz = ImageChops.offset(glanz, -2, -2)
    return maske_auf(img, p.get("glanz", "#8FC8D8"), glanz.point(lambda v: min(255, v * 2)))


MOTIVE = {k[2:]: v for k, v in globals().items() if k.startswith("m_")}
