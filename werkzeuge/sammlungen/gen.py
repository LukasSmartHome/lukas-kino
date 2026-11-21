# Erzeugt die Sammlungs-Kacheln (392 x 556) für Lukas Kino – eigene Motive und Schrift, keine Filmbilder/Logos.
# Aufruf: python3 gen.py AUSGABE_ORDNER   -> <slug>.jpg + index.json
import json, os, re, sys, unicodedata
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops
from motive import MOTIVE, W, H, rgb, koernung, vignette

FONTS = os.environ.get("FONTS", "/tmp/fonts")


def schrift(name, groesse, var=None):
    f = ImageFont.truetype(os.path.join(FONTS, name + ".ttf"), int(groesse))
    if var:
        try:
            if isinstance(var, str):
                f.set_variation_by_name(var)
            else:
                f.set_variation_by_axes(var)
        except Exception:
            pass
    return f


def slug(t):
    t = t.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss").replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
    t = unicodedata.normalize("NFKD", t).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", t.lower()).strip("-")


def breite(text, f, sperr):
    if not text:
        return 0
    if not sperr:
        return f.getlength(text)
    return sum(f.getlength(ch) for ch in text) + sperr * (len(text) - 1)


def zeile_malen(d, x, y, text, f, sperr, **kw):
    if not sperr:
        d.text((x, y), text, font=f, **kw)
        return
    for ch in text:
        d.text((x, y), ch, font=f, **kw)
        x += f.getlength(ch) + sperr


def block(e, f, sp, zeilen):
    """Zeilen-Positionen: Oberkante der Großbuchstaben bündig, gleichmäßiger Abstand."""
    ref = f.getbbox("HÄÖ")
    oben, kap = ref[1], ref[3] - ref[1]
    klein = any(ch.islower() for z in zeilen for ch in z)
    unterlaenge = (f.getbbox("Hgjy")[3] - ref[3]) if klein else 0
    schritt = (kap + unterlaenge) * 1.22 * e.get("zeilenhoehe", 1.0)
    return oben, kap, schritt, unterlaenge


def titel(img, e):
    zeilen = e["zeilen"]
    font, var = e.get("font", "BebasNeue"), e.get("var")
    rand = e.get("rand", 28)
    sperr_f = e.get("sperren", 0.0)
    gr = e.get("max", 120)
    unter = e.get("unter")
    while gr > 14:
        f = schrift(font, gr, var)
        sp = sperr_f * gr
        if all(breite(z, f, sp) <= W - 2 * rand - 0.06 * gr for z in zeilen):
            break
        gr -= 2
    f = schrift(font, gr, var)
    sp = sperr_f * gr
    oben, kap, schritt, ul = block(e, f, sp, zeilen)
    ug = e.get("unter_gr", 22)
    uf = schrift(e.get("unter_font", "Oswald"), ug, e.get("unter_var", "Medium")) if unter else None
    usp = 0.14 * ug
    block_h = schritt * (len(zeilen) - 1) + kap + ul + (ug * 1.5 if unter else 0)
    pos = e.get("pos", "oben")
    y0 = {"oben": 58, "mitte": (H - 100 - block_h) / 2 + 14, "unten": H - 104 - block_h}[pos] + e.get("dy", 0)
    mitte = e.get("ausrichtung", "mitte") == "mitte"
    ebene = Image.new("L", img.size, 0)
    dl = ImageDraw.Draw(ebene)
    for i, z in enumerate(zeilen):
        x = (W - breite(z, f, sp)) / 2 if mitte else rand
        zeile_malen(dl, x, y0 + i * schritt - oben, z, f, sp, fill=255)
    um = None
    if unter:
        um = Image.new("L", img.size, 0)
        ux = (W - breite(unter, uf, usp)) / 2 if mitte else rand
        uy = y0 + schritt * (len(zeilen) - 1) + kap + ul + ug * 0.55 - uf.getbbox("H")[1]
        zeile_malen(ImageDraw.Draw(um), ux, uy, unter, uf, usp, fill=255)
        ebene = ImageChops.lighter(ebene, um)
    if e.get("schatten", True):
        s = ebene.filter(ImageFilter.GaussianBlur(e.get("schatten_blur", 10)))
        s = ImageChops.offset(s, 0, 4)
        img = Image.composite(Image.new("RGB", img.size, (0, 0, 0)), img, s.point(lambda v: int(v * e.get("schatten_stark", 0.8))))
    if e.get("leuchten"):
        g = ebene.filter(ImageFilter.GaussianBlur(e.get("leucht_blur", 14)))
        lf = Image.new("RGB", img.size, rgb(e["leuchten"]))
        img = ImageChops.add(img, Image.composite(lf, Image.new("RGB", img.size, 0), g.point(lambda v: int(v * e.get("leucht_stark", 0.9)))))
    if e.get("kontur"):
        k = ebene.filter(ImageFilter.MaxFilter(int(e.get("kontur_breite", 5)) | 1))
        img = Image.composite(Image.new("RGB", img.size, rgb(e["kontur"])), img, k)
    img = Image.composite(Image.new("RGB", img.size, rgb(e.get("farbe", "#FFFFFF"))), img, ebene)
    if um is not None and e.get("unter_farbe"):
        img = Image.composite(Image.new("RGB", img.size, rgb(e["unter_farbe"])), img, um)
    return img


def unten_abdunkeln(img):
    a = np.array(img, float)
    t = np.clip((np.arange(H) - (H - 110)) / 110, 0, 1) ** 1.3 * 0.72
    a *= (1 - t)[:, None, None]
    return Image.fromarray(a.clip(0, 255).astype("uint8"), "RGB")


def kachel(e):
    rng = np.random.default_rng(e.get("seed", 7))
    img = MOTIVE[e["motiv"]](rng, e.get("p", {}))
    img = vignette(img, e.get("vignette", 0.5))
    img = titel(img, e)
    img = unten_abdunkeln(img)
    return koernung(img, rng, 3)


from designs import DESIGNS

if __name__ == "__main__":
    aus = sys.argv[1]
    os.makedirs(aus, exist_ok=True)
    index = {}
    nur = sys.argv[2:] or None
    for name, e in DESIGNS.items():
        if nur and name not in nur:
            continue
        s = slug(name)
        kachel(e).save(os.path.join(aus, s + ".jpg"), quality=90, optimize=True, progressive=True)
        index[name] = s + ".jpg"
    if not nur:
        json.dump(index, open(os.path.join(aus, "index.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(len(index), "Kacheln")
