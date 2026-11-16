#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Genre-Bilder für Lukas Kino (Wunsch Lukas 16.11.2026): statt einfarbiger Kacheln mit „SAMMLUNG“ und Strich
bekommt jede Kategorie ein gezeichnetes Motiv – alle im selben Stil (flache Scherenschnitt-Plakate in der
Farbe der Kategorie), Name unten auf der Kachel.

Erzeugt je Kategorie zwei Bilder:
  kachel/<datei>.jpg   1000 x 1500  – Kachel (Jellyfin: Primary der Sammlung), mit Name unten
  breit/<datei>.jpg    1920 x 1080  – Hintergrund (Jellyfin: Backdrop der Sammlung), Motiv oben rechts, ohne Schrift

Aufruf:  python3 genre_kunst.py [zielordner] [Name …]
Braucht: rsvg-convert, Pillow, die Schrift ../../lg/web/fonts/InterTight.ttf
Einspielen in Jellyfin: genre_bilder_hochladen.py (gleicher Ordner).

Alle Motive sind selbst gezeichnet (keine fremden Bilder, keine Figuren aus Filmen).
"""
import math
import os
import random
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

HIER = os.path.dirname(os.path.abspath(__file__))
SCHRIFT = os.path.join(HIER, "..", "..", "lg", "web", "fonts", "InterTight.ttf")

# Zeichenfläche: „Kern“ 1000 x 1000, Horizont bei y = 800. Bänder reichen weit nach links/rechts/unten,
# damit dieselbe Szene als Hochformat (Kachel) und als Breitbild (Hintergrund) taugt.
XL, XR, YU, YO = -2400, 2600, 2300, -900


class Szene:
    def __init__(self):
        self.defs = []
        self.teile = []
        self.n = 0

    def nid(self, p="g"):
        self.n += 1
        return "%s%d" % (p, self.n)

    def roh(self, s):
        self.teile.append(s)

    # ------------------------------------------------------------ Grundformen
    def himmel(self, stufen):
        """stufen: [(y, farbe), …] von oben nach unten (Kern-Koordinaten)."""
        i = self.nid("h")
        y0, y1 = stufen[0][0], stufen[-1][0]
        st = "".join('<stop offset="%.4f" stop-color="%s"/>' % ((y - y0) / float(y1 - y0), f) for y, f in stufen)
        self.defs.append('<linearGradient id="%s" gradientUnits="userSpaceOnUse" x1="0" y1="%d" x2="0" y2="%d">%s</linearGradient>'
                         % (i, y0, y1, st))
        self.roh('<rect x="%d" y="%d" width="%d" height="%d" fill="url(#%s)"/>' % (XL, YO, XR - XL, YU - YO, i))

    def schein(self, cx, cy, r, farbe, kraft=0.6):
        i = self.nid("s")
        self.defs.append('<radialGradient id="%s"><stop offset="0" stop-color="%s" stop-opacity="%.2f"/>'
                         '<stop offset="1" stop-color="%s" stop-opacity="0"/></radialGradient>' % (i, farbe, kraft, farbe))
        self.roh('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="url(#%s)"/>' % (cx, cy, r, i))

    def scheibe(self, cx, cy, r, farbe, glanz=None, kraft=0.55):
        if glanz:
            self.schein(cx, cy, r * 2.6, glanz, kraft)
        self.roh('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/>' % (cx, cy, r, farbe))

    def verlauf(self, f1, f2, y1, y2):
        i = self.nid("v")
        self.defs.append('<linearGradient id="%s" gradientUnits="userSpaceOnUse" x1="0" y1="%d" x2="0" y2="%d">'
                         '<stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>' % (i, y1, y2, f1, f2))
        return "url(#%s)" % i

    def band(self, punkte, farbe, weich=True, op=1.0):
        """Gelände-Band durch die Punkte, nach unten und zu den Seiten gefüllt."""
        p = [(XL, punkte[0][1])] + list(punkte) + [(XR, punkte[-1][1])]
        d = "M%d,%d L%d,%.1f " % (XL, YU, XL, p[0][1])
        if weich:
            d += spline(p)
        else:
            d += " ".join("L%.1f,%.1f" % q for q in p)
        d += " L%d,%d Z" % (XR, YU)
        self.roh('<path d="%s" fill="%s"%s/>' % (d, farbe, (' opacity="%.2f"' % op) if op < 1 else ""))

    def form(self, punkte, farbe, op=1.0, extra=""):
        d = "M" + " L".join("%.1f,%.1f" % q for q in punkte) + " Z"
        self.roh('<path d="%s" fill="%s"%s %s/>' % (d, farbe, (' opacity="%.2f"' % op) if op < 1 else "", extra))

    def pfad(self, d, farbe, op=1.0, extra=""):
        self.roh('<path d="%s" fill="%s"%s %s/>' % (d, farbe, (' opacity="%.2f"' % op) if op < 1 else "", extra))

    def linie(self, d, farbe, breite, op=1.0, extra=""):
        self.roh('<path d="%s" fill="none" stroke="%s" stroke-width="%.1f" stroke-linecap="round" stroke-linejoin="round"%s %s/>'
                 % (d, farbe, breite, (' opacity="%.2f"' % op) if op < 1 else "", extra))

    def rechteck(self, x, y, w, h, farbe, r=0, op=1.0):
        self.roh('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="%.1f" fill="%s"%s/>'
                 % (x, y, w, h, r, farbe, (' opacity="%.2f"' % op) if op < 1 else ""))

    def kreis(self, cx, cy, r, farbe, op=1.0):
        self.roh('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"%s/>' % (cx, cy, r, farbe, (' opacity="%.2f"' % op) if op < 1 else ""))

    def ellipse(self, cx, cy, rx, ry, farbe, op=1.0, extra=""):
        self.roh('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.1f" fill="%s"%s %s/>'
                 % (cx, cy, rx, ry, farbe, (' opacity="%.2f"' % op) if op < 1 else "", extra))

    # ------------------------------------------------------------ Bausteine
    def sterne(self, n, saat, farbe="#FFFFFF", y0=-850, y1=560, gross=2.6):
        z = random.Random(saat)
        for _ in range(n):
            x, y = z.uniform(XL, XR), z.uniform(y0, y1)
            r = z.uniform(0.9, gross)
            self.kreis(x, y, r, farbe, z.uniform(0.35, 0.95))

    def wolke(self, x, y, g, farbe, op=1.0):
        for dx, dy, rx, ry in ((0, 0, 70, 30), (-52, 8, 44, 22), (56, 8, 50, 24), (-14, -22, 40, 26), (30, -18, 34, 22)):
            self.ellipse(x + dx * g, y + dy * g, rx * g, ry * g, farbe, op)

    def vogel(self, x, y, g, farbe, breite=3.2):
        self.linie("M%.1f,%.1f q%.1f,%.1f %.1f,%.1f q%.1f,%.1f %.1f,%.1f" % (x - g, y, g * .5, -g * .55, g, 0, g * .5, -g * .55, g, 0), farbe, breite)

    def tanne(self, x, basis, h, farbe, breite=None):
        b = breite or h * 0.42
        for k, (o, u, f) in enumerate(((0.0, 0.42, 0.50), (0.22, 0.68, 0.76), (0.46, 0.94, 1.0))):
            self.form([(x, basis - h + h * o), (x - b * f / 2, basis - h + h * u), (x + b * f / 2, basis - h + h * u)], farbe)
        self.rechteck(x - h * 0.025, basis - h * 0.08, h * 0.05, h * 0.09, farbe)

    def haeuser(self, x0, x1, basis, hmin, hmax, wmin, wmax, saat, farbe, fenster=None, anteil=0.25, luecke=0):
        z = random.Random(saat)
        x = x0
        while x < x1:
            w = z.uniform(wmin, wmax)
            hh = z.uniform(hmin, hmax)
            self.rechteck(x, basis - hh, w, hh + 40, farbe)
            if z.random() < 0.3:
                self.rechteck(x + w * 0.4, basis - hh - 26, 5, 28, farbe)       # Antenne
            if fenster:
                fx = x + 9
                while fx < x + w - 12:
                    fy = basis - hh + 12
                    while fy < basis - 12:
                        if z.random() < anteil:
                            self.rechteck(fx, fy, 7, 10, fenster, 0, z.uniform(0.55, 1.0))
                        fy += 22
                    fx += 16
            x += w + luecke

    def mensch(self, x, basis, h, farbe, art="mann", hut=False):
        """Einfache Figur von vorn/hinten. h = Körpergröße."""
        k = h * 0.075                                   # Kopf-Radius
        kopf_y = basis - h + k
        self.kreis(x, kopf_y, k, farbe)
        sch = h * 0.15                                   # halbe Schulterbreite
        hals = kopf_y + k * 0.85
        if art == "frau":
            # Kleid: schmale Schultern, ausgestellter Rock
            self.pfad("M%.1f,%.1f Q%.1f,%.1f %.1f,%.1f L%.1f,%.1f L%.1f,%.1f L%.1f,%.1f Q%.1f,%.1f %.1f,%.1f Z" % (
                x - sch * .8, hals + h * .05, x, hals - h * .02, x + sch * .8, hals + h * .05,
                x + sch * .55, basis - h * .52, x + sch * 1.5, basis - h * .2, x - sch * 1.5, basis - h * .2,
                x - sch * .55, basis - h * .52, x - sch * .8, hals + h * .05), farbe)
            self.rechteck(x - sch * .55, basis - h * .22, sch * .38, h * .22, farbe, 3)
            self.rechteck(x + sch * .17, basis - h * .22, sch * .38, h * .22, farbe, 3)
            # Haare
            self.pfad("M%.1f,%.1f q%.1f,%.1f %.1f,%.1f l%.1f,%.1f l%.1f,0 Z" % (
                x - k * 1.05, kopf_y - k * .1, k * 1.05, -k * 1.5, k * 2.1, 0, k * .25, k * 2.3, -k * 2.6), farbe)
        elif art == "kind":
            self.rechteck(x - sch * .8, hals, sch * 1.6, h * .40, farbe, sch * .5)
            self.rechteck(x - sch * .7, basis - h * .42, sch * .58, h * .42, farbe, 3)
            self.rechteck(x + sch * .12, basis - h * .42, sch * .58, h * .42, farbe, 3)
        else:
            self.pfad("M%.1f,%.1f Q%.1f,%.1f %.1f,%.1f L%.1f,%.1f L%.1f,%.1f Z" % (
                x - sch, hals + h * .07, x, hals - h * .03, x + sch, hals + h * .07,
                x + sch * .82, basis - h * .46, x - sch * .82, basis - h * .46), farbe)
            self.rechteck(x - sch * .82, basis - h * .48, sch * .74, h * .48, farbe, 3)
            self.rechteck(x + sch * .08, basis - h * .48, sch * .74, h * .48, farbe, 3)
        if hut:
            self.ellipse(x, kopf_y - k * .55, k * 2.0, k * .36, farbe)
            self.pfad("M%.1f,%.1f q%.1f,%.1f %.1f,%.1f l%.1f,%.1f l%.1f,0 Z" % (
                x - k * 1.02, kopf_y - k * .55, k * 1.02, -k * 1.75, k * 2.04, 0, 0, 0, -k * 2.04), farbe)

    def herz(self, x, y, g, farbe, op=1.0, dreh=0):
        d = ("M0,%.1f C%.1f,%.1f %.1f,%.1f 0,%.1f C%.1f,%.1f %.1f,%.1f 0,%.1f Z"
             % (g * .9, -g * 1.5, -g * .2, -g * .6, -g * 1.25, -g * .2, g * .6, -g * 1.25, g * 1.5, -g * .2, g * .9))
        self.roh('<path d="%s" transform="translate(%.1f %.1f) rotate(%d)" fill="%s"%s/>'
                 % (d, x, y, dreh, farbe, (' opacity="%.2f"' % op) if op < 1 else ""))

    def fledermaus(self, x, y, g, farbe, dreh=0):
        d = ("M0,0 C%.1f,%.1f %.1f,%.1f %.1f,%.1f C%.1f,%.1f %.1f,%.1f %.1f,%.1f C%.1f,%.1f %.1f,%.1f 0,%.1f "
             "C%.1f,%.1f %.1f,%.1f %.1f,%.1f C%.1f,%.1f %.1f,%.1f %.1f,%.1f C%.1f,%.1f %.1f,%.1f 0,0 Z"
             % (g * .5, -g * .9, g * 1.5, -g * .9, g * 2.2, -g * .3,
                g * 1.8, -g * .25, g * 1.5, g * .1, g * 1.4, g * .45,
                g * 1.0, g * .15, g * .5, g * .2, g * .55,
                -g * .5, g * .2, -g * 1.0, g * .15, -g * 1.4, g * .45,
                -g * 1.5, g * .1, -g * 1.8, -g * .25, -g * 2.2, -g * .3,
                -g * 1.5, -g * .9, -g * .5, -g * .9))
        self.roh('<path d="%s" transform="translate(%.1f %.1f) rotate(%d)" fill="%s"/>' % (d, x, y, dreh, farbe))

    def nebel(self, y, farbe, op=0.18, saat=1, n=9):
        z = random.Random(saat)
        i = self.nid("n")
        self.defs.append('<filter id="%s" x="-20%%" y="-80%%" width="140%%" height="260%%"><feGaussianBlur stdDeviation="22"/></filter>' % i)
        s = '<g filter="url(#%s)" opacity="%.2f">' % (i, op)
        for _ in range(n):
            s += '<ellipse cx="%.0f" cy="%.0f" rx="%.0f" ry="%.0f" fill="%s"/>' % (
                z.uniform(-1800, 1500), y + z.uniform(-28, 28), z.uniform(220, 520), z.uniform(22, 46), farbe)
        self.roh(s + "</g>")

    def rauch(self, kreise, farbe, op=0.6, weich=14):
        """Weiche Rauchwolke aus Kreisen [(cx, cy, r), …]."""
        i = self.nid("r")
        self.defs.append('<filter id="%s" x="-30%%" y="-30%%" width="160%%" height="160%%"><feGaussianBlur stdDeviation="%d"/></filter>' % (i, weich))
        self.roh('<g filter="url(#%s)" opacity="%.2f">' % (i, op)
                 + "".join('<circle cx="%.0f" cy="%.0f" r="%.0f" fill="%s"/>' % (cx, cy, r, farbe) for cx, cy, r in kreise) + "</g>")

    def svg(self, breite, hoehe, skala, dx, dy):
        return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d"><defs>%s</defs>'
                '<g transform="translate(%.1f %.1f) scale(%.4f)">%s</g></svg>'
                % (breite, hoehe, breite, hoehe, "".join(self.defs), dx, dy, skala, "".join(self.teile)))


def spline(p):
    """Catmull-Rom durch alle Punkte als kubische Bézier-Stücke."""
    d = "L%.1f,%.1f " % p[0]
    for i in range(len(p) - 1):
        p0 = p[i - 1] if i > 0 else p[i]
        p1, p2 = p[i], p[i + 1]
        p3 = p[i + 2] if i + 2 < len(p) else p2
        c1 = (p1[0] + (p2[0] - p0[0]) / 6.0, p1[1] + (p2[1] - p0[1]) / 6.0)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6.0, p2[1] - (p3[1] - p1[1]) / 6.0)
        d += "C%.1f,%.1f %.1f,%.1f %.1f,%.1f " % (c1[0], c1[1], c2[0], c2[1], p2[0], p2[1])
    return d


# ====================================================================== Die 15 Szenen

def western(s):
    s.himmel([(-600, "#4A1420"), (120, "#A8351F"), (480, "#E9772B"), (800, "#F9CC5C")])
    s.scheibe(630, 590, 150, "#FFE9A6", "#FFD36B", 0.7)
    for x, y, g in ((300, 250, 16), (360, 300, 12), (780, 210, 14), (-420, 230, 15), (-800, 320, 12)):
        s.vogel(x, y, g, "#5A1A16")
    # ferne Tafelberge
    s.band([(-1900, 760), (-1500, 760), (-1480, 700), (-1250, 700), (-1230, 760), (-900, 760), (-880, 690), (-700, 690),
            (-680, 730), (-560, 730), (-540, 765), (-150, 765), (-130, 715), (20, 715), (40, 765), (520, 765), (540, 728),
            (700, 728), (720, 765), (960, 765), (975, 705), (1120, 705), (1135, 765), (1500, 765)], "#C4592C", weich=False)
    # großer Felsturm links (wie ein Fausthandschuh) und Tafelberg rechts
    s.form([(60, 810), (150, 700), (205, 676), (214, 470), (232, 452), (240, 418), (300, 418), (306, 440), (340, 446),
            (352, 668), (404, 694), (480, 810)], "#8C3520")
    s.form([(214, 470), (232, 452), (240, 418), (262, 418), (250, 470), (246, 676), (205, 676)], "#A94426")
    s.form([(700, 810), (748, 720), (774, 704), (780, 560), (800, 548), (940, 548), (952, 566), (958, 700), (990, 722), (1050, 810)], "#8C3520")
    s.form([(780, 560), (800, 548), (836, 548), (822, 566), (818, 704), (774, 704)], "#A94426")
    s.form([(-560, 810), (-520, 730), (-500, 716), (-492, 610), (-420, 610), (-410, 716), (-380, 735), (-330, 810)], "#8C3520")
    # Boden
    s.band([(-1900, 800), (-900, 790), (-200, 806), (300, 796), (700, 808), (1200, 798)], "#5A1D14")
    s.band([(-1900, 880), (-800, 860), (0, 884), (500, 866), (1000, 884)], "#2E0D0B")
    # Kakteen
    for x, b, g in ((560, 846, 1.0), (118, 862, 0.72), (905, 868, 0.6), (-260, 872, 0.8), (-1050, 868, 0.9)):
        f = "#230908"
        s.linie("M%.1f,%.1f V%.1f" % (x, b, b - 230 * g), f, 36 * g)
        s.linie("M%.1f,%.1f h%.1f v%.1f" % (x, b - 96 * g, -58 * g, -70 * g), f, 24 * g)
        s.linie("M%.1f,%.1f h%.1f v%.1f" % (x, b - 140 * g, 56 * g, -78 * g), f, 24 * g)
    # Wagenrad und Schädel-Fels als Kleinigkeit im Vordergrund
    s.roh('<g fill="none" stroke="#230908" stroke-width="7"><circle cx="330" cy="884" r="46"/>'
          + "".join('<path d="M330,884 l%.1f,%.1f"/>' % (46 * math.cos(a), 46 * math.sin(a)) for a in [k * math.pi / 4 for k in range(8)])
          + '</g><circle cx="330" cy="884" r="9" fill="#230908"/>')


def horror(s):
    s.himmel([(-600, "#08020F"), (150, "#22083A"), (560, "#4A0F5E"), (800, "#8C2B7E")])
    s.sterne(110, 7, "#E9D6FF", y1=420, gross=2.2)
    s.scheibe(610, 380, 172, "#F3EBFF", "#C9A6FF", 0.5)
    for cx, cy, r in ((560, 330, 30), (668, 420, 22), (630, 300, 12), (540, 440, 16)):
        s.kreis(cx, cy, r, "#DCCBF5")
    s.wolke(430, 430, 1.5, "#2A0C40", 0.9)
    s.wolke(830, 300, 1.2, "#2A0C40", 0.85)
    for x, y, g, d in ((330, 250, 15, -12), (420, 330, 10, 8), (800, 470, 13, 10), (250, 420, 9, -6), (-300, 300, 14, 5), (-700, 380, 11, -8)):
        s.fledermaus(x, y, g, "#0B0312", d)
    s.band([(-1900, 770), (-1200, 740), (-600, 772), (-100, 750), (150, 742), (380, 660), (520, 612), (680, 618), (840, 700), (1000, 748), (1400, 760)], "#200A30")
    # Spukhaus auf dem Hügel
    f = "#0A0310"
    s.rechteck(440, 470, 170, 160, f)
    s.form([(424, 474), (525, 380), (626, 474)], f)
    s.rechteck(590, 400, 74, 230, f)
    s.form([(578, 404), (627, 300), (676, 404)], f)
    s.rechteck(624, 268, 6, 40, f)
    s.rechteck(394, 520, 60, 110, f)
    s.form([(384, 524), (424, 470), (464, 524)], f)
    s.rechteck(470, 392, 16, 50, f)                                  # Schornstein
    for x, y, w, h in ((470, 500, 26, 38), (540, 500, 26, 38), (612, 440, 26, 40), (612, 520, 26, 40), (410, 548, 24, 34)):
        s.rechteck(x, y, w, h, "#FFC94A", 3)
        s.rechteck(x + w / 2 - 1.5, y, 3, h, f)
        s.rechteck(x, y + h / 2 - 1.5, w, 3, f)
    s.pfad("M512,632 v-44 a18,18 0 0 1 36,0 v44 Z", "#3A0F14")
    s.schein(525, 520, 150, "#FFB347", 0.22)
    # toter Baum
    b = "#06020A"
    s.linie("M190,800 C196,700 176,640 200,560 C212,520 204,480 222,440", b, 26)
    s.linie("M200,585 C150,560 130,520 96,500 M132,530 C120,500 124,480 108,460", b, 11)
    s.linie("M204,540 C250,510 270,470 308,452 M262,486 C276,462 270,440 284,420", b, 11)
    s.linie("M218,462 C200,430 206,400 186,372 M222,440 C240,410 236,384 256,360", b, 9)
    # Vordergrund: Friedhof
    s.band([(-1900, 850), (-900, 828), (-200, 846), (300, 832), (700, 850), (1200, 836)], b)
    for x, h in ((330, 70), (760, 62), (-180, 66), (-640, 70), (-1200, 64)):
        s.pfad("M%d,846 v-%d a24,24 0 0 1 48,0 v%d Z" % (x, h - 24, h - 24), b)
    for x in (430, 690, 880, -420, -900):
        s.rechteck(x, 760, 12, 90, b)
        s.rechteck(x - 20, 782, 52, 12, b)
    s.nebel(812, "#C9A6FF", 0.20, 3)


def thriller(s):
    s.himmel([(-600, "#06080B"), (200, "#161A20"), (600, "#3B414B"), (800, "#5B6470")])
    s.scheibe(560, 420, 200, "#B81422", "#E0202E", 0.45)
    s.kreis(560, 420, 200, "#D21F2B", 0.55)
    # Regenstriche
    z = random.Random(5)
    for _ in range(170):
        x, y, l = z.uniform(XL, XR), z.uniform(-850, 780), z.uniform(26, 60)
        s.linie("M%.0f,%.0f l%.0f,%.0f" % (x, y, -l * .28, l), "#AEB8C6", 1.6, z.uniform(.12, .32))
    s.haeuser(-1900, 1500, 800, 60, 210, 60, 130, 11, "#12151A", "#E8C36A", 0.10)
    s.band([(-1900, 800), (1500, 800)], "#0B0D11", weich=False)
    # Messer, mit der Spitze im Boden, leicht schräg
    s.roh('<g transform="rotate(14 560 800)">')
    kl = s.verlauf("#F4F7FA", "#9AA5B3", 330, 800)
    s.pfad("M522,330 L598,330 L598,640 C598,720 580,770 560,812 C540,770 522,720 522,640 Z", kl)
    s.pfad("M560,330 L598,330 L598,640 C598,720 580,770 560,812 Z", "#6F7A88", 0.55)
    s.rechteck(496, 312, 128, 22, "#1B1E24", 6)
    s.rechteck(530, 150, 60, 166, "#0E1014", 16)
    for y in (186, 232, 278):
        s.kreis(560, y, 7, "#4A515C")
    # Blut auf der Klinge
    s.pfad("M522,560 C540,548 566,566 598,552 L598,640 C598,720 580,770 560,812 C540,770 522,720 522,640 Z", "#C4131F")
    s.pfad("M536,560 q6,40 0,76 q-5,14 -10,0 q-2,-40 0,-76 Z M574,556 q5,26 0,52 q-4,10 -8,0 q-2,-26 0,-52 Z", "#C4131F")
    s.roh('</g>')
    s.ellipse(552, 822, 120, 20, "#A60F1A")
    s.ellipse(470, 842, 34, 8, "#A60F1A")
    s.ellipse(668, 838, 22, 6, "#A60F1A")
    s.band([(-1900, 870), (1500, 870)], "#050608", weich=False)


def krimi(s):
    s.himmel([(-600, "#030818"), (150, "#0B1C44"), (560, "#1D438F"), (800, "#3C6BC0")])
    s.sterne(60, 4, "#CFE0FF", y1=300, gross=2.0)
    s.scheibe(820, 250, 54, "#E8F0FF", "#9DBEFF", 0.4)
    s.haeuser(-1900, 1500, 800, 150, 420, 90, 170, 21, "#132C63", "#F2D27A", 0.10)
    s.haeuser(-1900, 1500, 800, 60, 250, 70, 150, 8, "#0B1B40", "#F2D27A", 0.16)
    s.band([(-1900, 800), (1500, 800)], "#060D22", weich=False)
    # Straßenlaterne mit Lichtkegel
    f = "#030712"
    s.rechteck(268, 400, 12, 420, f)
    s.linie("M274,404 q0,-44 44,-44 h34", f, 10)
    s.pfad("M336,360 h44 l10,26 h-64 Z", f)
    i = s.nid("k")
    s.defs.append('<linearGradient id="%s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFE9A8" stop-opacity=".62"/>'
                  '<stop offset="1" stop-color="#FFE9A8" stop-opacity="0.04"/></linearGradient>' % i)
    s.roh('<path d="M330,386 L386,386 L640,830 L120,830 Z" fill="url(#%s)"/>' % i)
    s.ellipse(380, 832, 262, 26, "#FFE9A8", 0.22)
    s.kreis(358, 388, 9, "#FFF3C8")
    # Detektiv mit Hut und Mantel
    x, b = 560, 836
    s.pfad("M%d,%d L%d,%d L%d,%d L%d,%d Z" % (x - 16, b - 150, x - 30, b, x - 4, b, x + 2, b - 150), f)       # Beine
    s.pfad("M%d,%d L%d,%d L%d,%d L%d,%d Z" % (x + 2, b - 150, x + 6, b, x + 32, b, x + 20, b - 150), f)
    s.pfad("M%d,%d C%d,%d %d,%d %d,%d L%d,%d L%d,%d L%d,%d C%d,%d %d,%d %d,%d Z" % (
        x - 20, b - 318, x - 70, b - 306, x - 74, b - 250, x - 70, b - 200, x - 62, b - 96, x + 66, b - 96, x + 72, b - 200,
        x + 76, b - 250, x + 72, b - 306, x + 22, b - 318), f)                                                    # Mantel
    s.form([(x - 30, b - 330), (x, b - 292), (x - 46, b - 286)], f)                                             # Kragen
    s.form([(x + 30, b - 330), (x, b - 292), (x + 46, b - 286)], f)
    s.kreis(x, b - 346, 27, f)
    s.ellipse(x, b - 364, 62, 11, f)                                                                              # Hutkrempe
    s.pfad("M%d,%d C%d,%d %d,%d %d,%d L%d,%d Z" % (x - 34, b - 366, x - 32, b - 414, x + 32, b - 414, x + 34, b - 366, x, b - 362), f)
    s.rechteck(x - 34, b - 380, 68, 9, "#1D438F")
    s.pfad("M%d,%d l28,4 l-2,8 l-26,-4 Z" % (x + 20, b - 340), f)                                                # Zigarette/Pfeife
    s.kreis(x + 50, b - 332, 3.5, "#FF9A3C")
    s.linie("M%d,%d c10,-22 -8,-34 4,-56 c8,-16 -4,-26 2,-44" % (x + 52, b - 340), "#DCE8FF", 3, 0.35)
    s.band([(-1900, 880), (1500, 880)], "#02050E", weich=False)
    s.nebel(806, "#9DBEFF", 0.16, 9)


def fantasy(s):
    s.himmel([(-600, "#12083A"), (120, "#3B1FA8"), (480, "#6A3DE8"), (700, "#B985FF"), (800, "#F2B9EA")])
    s.sterne(150, 12, "#FFFFFF", y1=430, gross=2.6)
    s.scheibe(700, 300, 120, "#FFF4D6", "#FFD9F5", 0.5)
    s.scheibe(880, 190, 40, "#D9C6FF", "#D9C6FF", 0.4)
    # Funkelsterne
    for x, y, g in ((330, 200, 16), (520, 120, 11), (860, 420, 13), (180, 380, 10), (-300, 180, 14), (-800, 300, 12)):
        s.pfad("M%d,%d l%.1f,%.1f l%.1f,%.1f l%.1f,%.1f l%.1f,%.1f l%.1f,%.1f l%.1f,%.1f l%.1f,%.1f Z" % (
            x, y - g, g * .22, g * .78, g * .78, g * .22, -g * .78, g * .22, -g * .22, g * .78, -g * .22, -g * .78, -g * .78, -g * .22, g * .78, -g * .22), "#FFFFFF", 0.9)
    s.band([(-1900, 760), (-1500, 640), (-1300, 730), (-1000, 600), (-760, 740), (-500, 660), (-250, 760), (0, 700), (160, 640),
            (330, 740), (560, 690), (760, 620), (900, 700), (1060, 640), (1300, 760)], "#5630C4", weich=False)
    s.band([(-1900, 790), (-1300, 740), (-900, 780), (-500, 730), (-100, 790), (200, 760), (500, 800), (900, 760), (1300, 790)], "#3A1D96")
    # Felsnadel mit Schloss
    f = "#170B3C"
    s.form([(330, 830), (392, 700), (410, 560), (440, 520), (640, 520), (668, 560), (690, 700), (760, 830)], f)
    s.rechteck(440, 420, 200, 110, f)
    for x in range(440, 640, 30):
        s.rechteck(x, 404, 18, 20, f)
    for x, y, w, h in ((420, 330, 54, 200), (606, 300, 58, 230), (512, 260, 60, 180)):
        s.rechteck(x, y, w, h, f)
        s.form([(x - 10, y + 4), (x + w / 2, y - 86), (x + w + 10, y + 4)], f)
        s.rechteck(x + w / 2 - 2, y - 118, 4, 36, f)
        s.form([(x + w / 2 + 2, y - 118), (x + w / 2 + 34, y - 108), (x + w / 2 + 2, y - 98)], "#FFD24A")
    for x, y in ((440, 372), (628, 350), (535, 312), (535, 372), (480, 462), (590, 462)):
        s.pfad("M%d,%d v-16 a7,7 0 0 1 14,0 v16 Z" % (x, y), "#FFD98A")
    s.pfad("M520,530 v-40 a20,20 0 0 1 40,0 v40 Z", "#0B0522")
    # Drache
    d = "#120830"
    s.roh('<g transform="translate(250 330) scale(1.15) rotate(-8)">')
    s.pfad("M-90,20 C-50,-6 -10,-4 30,8 C60,16 84,6 104,-14 L120,-30 L114,-6 L136,-4 L112,10 C92,34 50,40 10,34 C-30,28 -62,36 -100,66 "
           "C-76,40 -84,30 -120,34 C-104,22 -98,22 -90,20 Z", d)
    s.pfad("M10,10 C-4,-50 -50,-84 -110,-92 C-84,-70 -78,-56 -84,-36 C-60,-44 -44,-36 -40,-16 C-24,-22 -10,-14 0,8 Z", d)
    s.pfad("M30,10 C40,-44 80,-78 140,-96 C122,-70 122,-54 132,-38 C108,-42 94,-32 92,-14 C76,-18 64,-10 56,10 Z", d)
    s.roh('</g>')
    s.band([(-1900, 860), (-900, 836), (-200, 858), (300, 842), (700, 862), (1200, 846)], "#0C0526")
    for x, h in ((120, 150), (60, 110), (880, 140), (950, 100), (-360, 130), (-420, 96), (-1100, 140)):
        s.tanne(x, 852, h, "#0C0526")


def scifi(s):
    s.himmel([(-600, "#01060F"), (100, "#062743"), (480, "#0E6A9C"), (720, "#1592C4"), (800, "#86E4F5")])
    s.sterne(200, 21, "#DFF6FF", y1=470, gross=2.6)
    # Ringplanet
    s.schein(660, 320, 330, "#7FE0F2", 0.35)
    i = s.nid("p")
    s.defs.append('<linearGradient id="%s" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#E6FBFF"/>'
                  '<stop offset="1" stop-color="#3FA9D6"/></linearGradient><clipPath id="%sc"><circle cx="660" cy="320" r="150"/></clipPath>' % (i, i))
    s.roh('<g transform="rotate(-18 660 320)"><ellipse cx="660" cy="320" rx="270" ry="50" fill="none" stroke="#BDEFFF" stroke-width="16" opacity=".55"/></g>')
    s.roh('<circle cx="660" cy="320" r="150" fill="url(#%s)"/>' % i)
    s.roh('<g clip-path="url(#%sc)" opacity=".35" transform="rotate(-18 660 320)">' % i
          + "".join('<rect x="480" y="%d" width="360" height="%d" fill="#0E6A9C"/>' % (y, h) for y, h in ((236, 16), (276, 26), (330, 12), (366, 30), (420, 14)))
          + '</g>')
    s.roh('<g transform="rotate(-18 660 320)"><path d="M392,326 A270,50 0 0 0 928,326" fill="none" stroke="#DFF8FF" stroke-width="16"/></g>')
    s.scheibe(240, 190, 34, "#CFF4FF", "#7FE0F2", 0.4)
    # Felsen und Stadt der Zukunft
    s.band([(-1900, 770), (-1500, 700), (-1320, 760), (-1000, 680), (-800, 770), (-420, 720), (-200, 770), (60, 730), (200, 770),
            (640, 770), (780, 700), (900, 760), (1040, 720), (1300, 770)], "#0A5580", weich=False)
    f = "#052A44"
    for x, w, h in ((640, 44, 250), (700, 30, 330), (744, 54, 200), (820, 26, 280), (860, 46, 170)):
        s.pfad("M%d,800 v-%d q%d,-%d %d,0 v%d Z" % (x, h, w / 2, w, w, h), f)
        for k in range(4):
            s.rechteck(x + w / 2 - 3, 800 - h + 20 + k * 34, 6, 14, "#9FF0FF", 2, 0.9)
    s.kreis(715, 446, 6, "#FF5A6E")
    s.band([(-1900, 800), (-900, 790), (0, 806), (600, 796), (1200, 806)], "#041E33")
    # Rakete beim Start
    s.roh('<g transform="translate(330 0)">')
    fl = s.verlauf("#FFF3B0", "#FF7A2A", 620, 800)
    s.pfad("M-24,610 C-30,690 -16,750 0,806 C16,750 30,690 24,610 Z", fl)
    s.pfad("M-11,612 C-14,670 -8,710 0,750 C8,710 14,670 11,612 Z", "#FFFFFF", 0.85)
    s.ellipse(0, 806, 150, 26, "#DFF6FF", 0.5)
    s.ellipse(-90, 792, 70, 30, "#DFF6FF", 0.4)
    s.ellipse(96, 794, 76, 28, "#DFF6FF", 0.4)
    k = "#021220"
    s.pfad("M0,330 C44,400 48,500 40,612 L-40,612 C-48,500 -44,400 0,330 Z", k)
    s.pfad("M-40,520 L-84,620 L-40,606 Z M40,520 L84,620 L40,606 Z", k)
    s.pfad("M0,330 C20,362 32,400 38,440 L-38,440 C-32,400 -20,362 0,330 Z", "#FF5A6E")
    s.kreis(0, 492, 20, "#9FF0FF")
    s.kreis(0, 492, 20, "none")
    s.roh('<circle cx="0" cy="492" r="20" fill="none" stroke="#E6FBFF" stroke-width="5"/>')
    s.roh('</g>')
    s.band([(-1900, 868), (-900, 850), (0, 872), (600, 856), (1200, 872)], "#020F1C")
    for x, r in ((560, 26), (120, 20), (900, 30), (-300, 24), (-900, 28)):
        s.ellipse(x, 868, r * 1.8, r, "#020F1C")


def action(s):
    s.himmel([(-600, "#1A0303"), (150, "#6E0B08"), (500, "#E3261B"), (720, "#FF7A22"), (800, "#FFD25A")])
    # Explosion
    s.schein(600, 640, 520, "#FFD25A", 0.75)
    z = random.Random(3)

    def stern(cx, cy, r1, r2, n, farbe, saat):
        zz = random.Random(saat)
        p = []
        for k in range(n * 2):
            a = math.pi * k / n + zz.uniform(-0.06, 0.06)
            r = (r1 if k % 2 == 0 else r2) * zz.uniform(0.85, 1.12)
            p.append((cx + r * math.cos(a), cy + r * math.sin(a)))
        s.form(p, farbe)

    stern(600, 640, 330, 190, 15, "#FF8A1E", 1)
    stern(600, 640, 250, 140, 13, "#FFC233", 2)
    stern(600, 640, 160, 96, 11, "#FFF1A8", 4)
    # Rauch
    s.rauch(((420, 430, 74), (500, 356, 90), (610, 300, 104), (720, 350, 86), (800, 436, 66), (560, 250, 70), (680, 214, 64), (620, 150, 52)), "#2A0504", 0.72, 12)
    # Trümmer
    for _ in range(26):
        a, r = z.uniform(math.pi, 2 * math.pi), z.uniform(300, 470)
        x, y, g = 600 + r * math.cos(a), 640 + r * math.sin(a) * .9, z.uniform(5, 14)
        s.roh('<rect x="%.0f" y="%.0f" width="%.0f" height="%.0f" fill="#1A0303" transform="rotate(%d %.0f %.0f)"/>' % (x, y, g * 1.6, g, z.randint(0, 90), x, y))
    # Hubschrauber
    h = "#120202"
    s.roh('<g transform="translate(250 330) rotate(-6)">')
    s.pfad("M-70,0 C-70,-34 -30,-46 20,-44 C60,-42 86,-24 92,2 L210,-10 L214,4 L96,22 C84,40 50,48 10,48 C-34,48 -70,34 -70,0 Z", h)
    s.pfad("M196,-44 l26,0 l-2,56 l-18,2 Z", h)
    s.rechteck(-150, -66, 330, 7, h, 3)
    s.rechteck(10, -64, 8, 22, h)
    s.linie("M-50,66 h130 M-28,46 v20 M56,46 v20", h, 7)
    s.pfad("M-58,-6 C-56,-26 -36,-34 -8,-34 L-8,6 L-58,8 Z", "#FF8A1E")
    s.roh('</g>')
    # Skyline davor
    s.haeuser(-1900, 1500, 810, 90, 330, 70, 150, 31, "#1A0303", "#FFC233", 0.07)
    s.band([(-1900, 810), (1500, 810)], "#120202", weich=False)
    # Auto, das davonfliegt
    s.roh('<g transform="translate(830 560) rotate(-24)">')
    s.pfad("M-60,0 C-58,-14 -40,-18 -30,-20 L-14,-40 L26,-40 L44,-20 C56,-18 62,-12 62,0 L62,10 L-60,10 Z", h)
    s.kreis(-34, 12, 13, h)
    s.kreis(38, 12, 13, h)
    s.roh('</g>')
    s.band([(-1900, 876), (1500, 876)], "#0A0101", weich=False)


def komoedien(s):
    s.himmel([(-600, "#C2560A"), (100, "#F08A12"), (500, "#FFC61A"), (800, "#FFE680")])
    # Scheinwerfer-Strahlen
    for x1, x2, x3 in ((-200, 330, 520), (1200, 480, 690), (500, 420, 640)):
        i = s.nid("l")
        s.defs.append('<linearGradient id="%s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFBE0" stop-opacity=".6"/>'
                      '<stop offset="1" stop-color="#FFFBE0" stop-opacity=".05"/></linearGradient>' % i)
        s.roh('<path d="M%d,-700 L%d,820 L%d,820 Z" fill="url(#%s)"/>' % (x1, x2, x3, i))
    # Konfetti
    z = random.Random(8)
    farben = ("#E3261B", "#1592C4", "#2E8B3F", "#FFFFFF", "#E8508D", "#6A3DE8")
    for _ in range(150):
        x, y = z.uniform(XL, XR), z.uniform(-850, 760)
        if 300 < x < 720 and 300 < y < 720:
            continue
        w, h = z.uniform(10, 20), z.uniform(6, 11)
        s.roh('<rect x="%.0f" y="%.0f" width="%.0f" height="%.0f" rx="2" fill="%s" transform="rotate(%d %.0f %.0f)"/>' % (x, y, w, h, z.choice(farben), z.randint(0, 180), x, y))
    for x, y, r in ((200, 300, 34), (820, 240, 28), (-300, 360, 30)):
        s.linie("M%d,%d c%d,%d %d,%d %d,%d c%d,%d %d,%d %d,%d" % (x, y, r, -r, r, r, 2 * r, 0, r, -r, r, r, 2 * r, 0), "#E3261B", 7)
    # lachende Maske
    f = "#3B2200"
    s.roh('<g transform="rotate(-9 520 520)">')
    s.schein(520, 520, 330, "#FFFFFF", 0.35)
    s.pfad("M330,330 C330,300 420,290 520,290 C620,290 710,300 710,330 C720,480 690,640 520,740 C350,640 320,480 330,330 Z", f)
    s.pfad("M330,330 C330,300 420,290 520,290 C620,290 710,300 710,330 C712,360 712,390 708,420 C640,380 400,380 332,420 C328,390 328,360 330,330 Z", "#5A3600")
    hell = "#FFD84A"
    s.pfad("M392,450 Q436,390 480,450 Q436,426 392,450 Z", hell)
    s.pfad("M560,450 Q604,390 648,450 Q604,426 560,450 Z", hell)
    s.pfad("M400,530 Q520,590 640,530 Q624,670 520,680 Q416,670 400,530 Z", hell)
    s.pfad("M418,548 Q520,590 622,548 L616,574 Q520,612 424,574 Z", "#FFFFFF")
    s.linie("M380,432 q14,-34 52,-44 M660,432 q-14,-34 -52,-44", f, 9)
    s.roh('</g>')
    # Bühnenboden und Bananenschale
    s.band([(-1900, 800), (1500, 800)], "#8A4A06", weich=False)
    s.band([(-1900, 850), (1500, 850)], "#5A2E02", weich=False)
    b = "#FFE14A"
    s.roh('<g transform="translate(790 838)">')
    s.pfad("M0,-36 C-8,-14 -40,-4 -66,6 C-40,12 -14,6 0,-10 C14,6 40,12 66,6 C40,-4 8,-14 0,-36 Z", b)
    s.pfad("M-6,-36 C-12,-8 -18,10 -30,22 C-8,16 2,0 0,-12 Z", "#F2C61A")
    s.rechteck(-5, -48, 10, 14, "#6B4A0A", 3)
    s.roh('</g>')
    # Mikrofon auf Ständer
    m = "#2B1800"
    s.rechteck(196, 600, 8, 240, m)
    s.ellipse(200, 842, 46, 9, m)
    s.roh('<g transform="rotate(28 200 600)"><rect x="188" y="540" width="24" height="70" rx="8" fill="%s"/><circle cx="200" cy="528" r="24" fill="%s"/></g>' % (m, m))


def drama(s):
    s.himmel([(-600, "#16040C"), (200, "#3F0C22"), (600, "#8E2345"), (800, "#B43A5E")])
    # Lichtkegel von oben
    i = s.nid("l")
    s.defs.append('<linearGradient id="%s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFE6C8" stop-opacity=".10"/>'
                  '<stop offset="1" stop-color="#FFE6C8" stop-opacity=".55"/></linearGradient>' % i)
    s.roh('<path d="M470,-700 L560,-700 L760,832 L300,832 Z" fill="url(#%s)"/>' % i)
    # Staub im Licht
    z = random.Random(6)
    for _ in range(46):
        y = z.uniform(-400, 780)
        halb = 46 + (y + 700) / 1532.0 * 180
        s.kreis(530 + z.uniform(-halb, halb), y, z.uniform(1.6, 3.6), "#FFF1DC", z.uniform(.3, .8))
    s.band([(-1900, 800), (1500, 800)], "#2A0714", weich=False)
    s.ellipse(530, 836, 250, 34, "#FFE6C8", 0.55)
    s.ellipse(530, 836, 170, 22, "#FFF4E2", 0.5)
    # einsame Gestalt im Licht, Kopf gesenkt, langer Schatten
    f = "#12030A"
    s.ellipse(590, 846, 130, 12, f, 0.55)
    s.mensch(530, 838, 300, f)
    s.pfad("M486,596 C470,650 468,700 478,744 L496,744 C492,700 496,650 502,612 Z", f)
    s.pfad("M574,596 C590,650 592,700 582,744 L564,744 C568,700 564,650 558,612 Z", f)
    # Rose auf dem Boden
    s.linie("M330,850 q40,-14 86,-6", "#0E3A1A", 5)
    s.pfad("M356,846 q10,-14 24,-8 q-8,12 -24,8 Z", "#0E3A1A")
    s.kreis(322, 850, 15, "#E3264A")
    s.pfad("M312,846 q10,-12 20,0 q-4,10 -10,10 q-6,0 -10,-10 Z", "#A8122E")
    # Vorhänge links und rechts, Schabracke oben
    for seite in (-1, 1):
        x0 = 500 + seite * 330
        aussen = XL if seite < 0 else XR
        v = s.verlauf("#6E1230", "#2E0714", -700, 900)
        d = "M%d,-900 L%d,-900 " % (aussen, x0)
        y, k = -900, 0
        while y < 880:
            d += "Q%d,%d %d,%d " % (x0 + seite * (-36 if k % 2 == 0 else 22), y + 110, x0 + seite * (-6 if k % 2 == 0 else 6), y + 220)
            y += 220
            k += 1
        d += "L%d,%d L%d,%d Z" % (x0, 880, aussen, 880)
        s.pfad(d, v)
        for k in range(1, 30):
            x = x0 + seite * (30 + k * 74)
            if x < XL or x > XR:
                break
            s.linie("M%d,-900 C%d,-300 %d,300 %d,880" % (x, x - seite * 26, x + seite * 20, x - seite * 8), "#1E040D", 12, 0.5)
        s.rechteck(x0 - 24 if seite > 0 else x0 - 20, 560, 44, 18, "#F2B705", 6)
    s.band([(-1900, 880), (1500, 880)], "#0A0206", weich=False)


def abenteuer(s):
    s.himmel([(-600, "#06301F"), (120, "#17663A"), (480, "#4FAE4A"), (700, "#B9E07E"), (800, "#F6F2A6")])
    s.scheibe(700, 470, 120, "#FFFBD6", "#FFF3A0", 0.7)
    for x, y, g in ((300, 300, 15), (370, 350, 11), (240, 380, 12), (-500, 300, 14), (860, 260, 12)):
        s.vogel(x, y, g, "#0B3A22")
    s.band([(-1900, 700), (-1600, 560), (-1400, 660), (-1150, 500), (-900, 680), (-700, 590), (-450, 700), (-200, 610), (-20, 500),
            (140, 640), (330, 560), (480, 690), (760, 600), (900, 520), (1060, 660), (1300, 700)], "#3B9450", weich=False)
    s.form([(-20, 500), (20, 560), (-4, 580), (36, 600), (60, 640), (-120, 640)], "#EAF7D0", 0.75)
    s.form([(900, 520), (930, 566), (908, 584), (950, 610), (850, 610)], "#EAF7D0", 0.75)
    s.band([(-1900, 770), (-1300, 720), (-900, 760), (-500, 716), (-100, 760), (300, 730), (700, 764), (1100, 730), (1400, 760)], "#226B38")
    # Stufenpyramide im Dschungel
    f = "#114A27"
    for k in range(6):
        w = 430 - k * 62
        s.rechteck(560 - w / 2, 760 - (k + 1) * 44, w, 46, f)
    s.rechteck(520, 456, 80, 44, f)
    s.rechteck(508, 444, 104, 14, f)
    s.rechteck(545, 468, 30, 32, "#F6F2A6", 0, 0.85)
    s.form([(532, 760), (588, 760), (574, 500), (546, 500)], "#1B6A38")
    for k in range(12):
        s.rechteck(534 + k * 1.1, 742 - k * 21.5, 52 - k * 2.2, 5, f)
    # Hängebrücke
    s.linie("M-140,700 Q60,790 250,712", "#06200F", 5)
    s.linie("M-140,676 Q60,766 250,688", "#06200F", 3)
    for k in range(1, 13):
        t = k / 13.0
        x = -140 + 390 * t
        y = (1 - t) ** 2 * 700 + 2 * (1 - t) * t * 790 + t * t * 712
        s.linie("M%.0f,%.0f v-24" % (x, y), "#06200F", 2.5)
    # Vordergrund mit Palmen und Entdecker
    v = "#06200F"
    s.band([(-1900, 850), (-1200, 820), (-600, 846), (-140, 700), (-100, 700), (-60, 840), (200, 852), (250, 712), (300, 712), (380, 820),
            (700, 850), (1200, 830)], v)

    def palme(x, b, h, neig):
        tx, ty = x + neig, b - h
        s.linie("M%d,%d Q%d,%d %d,%d" % (x, b, x + neig * .2, b - h * .55, tx, ty), v, 16)
        for a, l in ((-160, 120), (-128, 130), (-92, 110), (-52, 130), (-20, 120), (18, 96), (-198, 96)):
            ex, ey = tx + l * math.cos(math.radians(a)), ty + l * math.sin(math.radians(a))
            mx, my = (tx + ex) / 2, (ty + ey) / 2 - 46
            s.pfad("M%.0f,%.0f Q%.0f,%.0f %.0f,%.0f Q%.0f,%.0f %.0f,%.0f Z" % (tx, ty, mx, my, ex, ey + 34, mx, my + 26, tx, ty), v)
        s.kreis(tx - 6, ty + 12, 9, v)
        s.kreis(tx + 8, ty + 14, 9, v)

    palme(90, 850, 330, 50)
    palme(900, 846, 380, -60)
    palme(-420, 850, 300, -40)
    palme(-1000, 850, 340, 50)
    s.mensch(275, 716, 96, v, hut=True)
    s.rechteck(254, 644, 16, 30, v, 5)                         # Rucksack
    s.linie("M296,716 L310,634", v, 4)                          # Wanderstab


def mystery(s):
    s.himmel([(-600, "#031012"), (150, "#0E3438"), (560, "#2F6E73"), (800, "#7DB9B2")])
    s.sterne(70, 15, "#D8FFF6", y1=260, gross=2.0)
    # Mondsichel
    s.schein(740, 240, 190, "#D8FFF6", 0.4)
    s.roh('<path d="M770,160 A92,92 0 1 0 800,318 A72,72 0 1 1 770,160 Z" fill="#EFFFFA"/>')
    # Tannenwald in Schichten
    z = random.Random(2)
    for farbe, basis, hmin, hmax, schritt, saat in (("#3F8780", 770, 150, 250, 74, 1), ("#235C5A", 790, 200, 330, 96, 2), ("#123E3F", 812, 250, 420, 128, 3)):
        zz = random.Random(saat)
        x = -1950
        while x < 1500:
            h = zz.uniform(hmin, hmax)
            if not (380 < x < 700 and farbe != "#3F8780"):
                s.tanne(x, basis, h, farbe)
            x += schritt * zz.uniform(0.7, 1.25)
        s.band([(-1900, basis - 6), (1500, basis - 6)], farbe, weich=False)
        s.nebel(basis - 40, "#D8FFF6", 0.20, saat + 20)
    # leuchtendes Tor zwischen den Bäumen
    s.schein(540, 640, 330, "#D8FFF6", 0.55)
    s.pfad("M462,820 V600 A78,78 0 0 1 618,600 V820 Z", "#0A2A2C")
    s.pfad("M478,820 V604 A62,62 0 0 1 602,604 V820 Z", "#EFFFFA")
    i = s.nid("t")
    s.defs.append('<linearGradient id="%s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#EFFFFA" stop-opacity=".7"/>'
                  '<stop offset="1" stop-color="#EFFFFA" stop-opacity="0"/></linearGradient>' % i)
    s.roh('<path d="M478,820 L602,820 L720,900 L360,900 Z" fill="url(#%s)"/>' % i)
    # Gestalt mit Laterne vor dem Tor
    s.mensch(540, 824, 150, "#06181B")
    s.pfad("M512,716 L568,716 L582,812 L498,812 Z", "#06181B")       # Umhang
    s.form([(540, 668), (518, 706), (562, 706)], "#06181B")            # Kapuze
    # Raben auf einem Ast
    v = "#041012"
    s.band([(-1900, 850), (-900, 836), (0, 852), (700, 838), (1200, 852)], v)
    s.linie("M1000,560 C900,540 820,560 760,520 M860,548 C840,520 846,500 830,480", v, 12)
    for x, y in ((880, 528), (930, 536)):
        s.pfad("M%d,%d c-4,-26 20,-34 30,-18 l14,-2 l-12,10 c2,16 -10,26 -32,10 Z" % (x, y), v)
    for x, h in ((200, 300), (880, 360), (-300, 320), (-900, 380), (-1500, 320)):
        s.tanne(x, 850, h, v)


def liebesfilme(s):
    s.himmel([(-600, "#3E0A30"), (100, "#96235F"), (460, "#E8508D"), (680, "#FF9E9E"), (800, "#FFE0B8")])
    s.scheibe(560, 560, 190, "#FFF0D2", "#FFD0B0", 0.7)
    s.wolke(250, 330, 1.5, "#FFB3C6", 0.55)
    s.wolke(840, 260, 1.2, "#FFB3C6", 0.5)
    s.wolke(-400, 300, 1.6, "#FFB3C6", 0.45)
    for x, y, g, o, d in ((420, 300, 20, .95, -12), (700, 240, 15, .9, 10), (600, 150, 11, .8, 0), (330, 180, 13, .8, 14), (820, 420, 12, .85, -8),
                          (200, 470, 10, .7, 8), (-250, 250, 16, .8, -10), (-700, 380, 13, .7, 10)):
        s.herz(x, y, g, "#FFFFFF", o, d)
    s.band([(-1900, 780), (-1300, 740), (-800, 770), (-300, 730), (100, 760), (400, 720), (800, 760), (1300, 740)], "#B5306E")
    s.band([(-1900, 800), (-900, 790), (-300, 800), (200, 760), (420, 716), (560, 700), (700, 716), (900, 770), (1300, 800)], "#6B1447")
    # Baum mit Herzkrone
    f = "#2A0620"
    s.linie("M250,790 C254,720 240,680 252,620 M252,690 C232,670 222,650 214,636 M250,660 C270,640 280,630 290,612", f, 14)
    s.herz(252, 560, 96, f)
    for x, y, g in ((214, 520, 12), (290, 510, 10), (252, 580, 11)):
        s.herz(x, y, g, "#E8508D")
    # Paar, Hand in Hand, vor der Sonne
    s.mensch(520, 706, 210, f)
    s.mensch(612, 708, 188, f, art="frau")
    s.linie("M548,596 Q568,640 590,604", f, 9)
    # Bank und Laterne
    s.rechteck(760, 742, 130, 9, f, 3)
    s.rechteck(760, 712, 130, 8, f, 3)
    s.linie("M772,750 v28 M878,750 v28 M772,716 v28 M878,716 v28", f, 6)
    s.band([(-1900, 860), (-900, 840), (-200, 858), (400, 842), (900, 860), (1300, 846)], "#3E0A2C")
    z = random.Random(14)
    for _ in range(60):
        x = z.uniform(-1900, 1500)
        s.linie("M%.0f,856 q%.0f,-22 %.0f,-%.0f" % (x, z.uniform(-6, 6), z.uniform(-10, 10), z.uniform(24, 44)), "#3E0A2C", 4)
    for x, y in ((120, 838), (430, 836), (700, 842), (940, 836), (-300, 840), (-800, 836)):
        s.herz(x, y, 9, "#FF8FB6")


def familie(s):
    s.himmel([(-600, "#1557C0"), (150, "#2C82E6"), (520, "#5FB2F7"), (800, "#CDEBFF")])
    s.scheibe(180, 240, 76, "#FFE680", "#FFF3B0", 0.7)
    # Regenbogen
    for k, f in enumerate(("#F2545B", "#FF9F43", "#FFD93B", "#4CC96B", "#3E9BF0", "#8E6BE8")):
        s.roh('<path d="M%d,800 A%d,%d 0 0 1 %d,800" fill="none" stroke="%s" stroke-width="20" opacity=".9"/>' % (150 + k * 20, 400 - k * 20, 400 - k * 20, 950 - k * 20, f))
    for x, y, g in ((330, 330, 1.2), (800, 200, 1.5), (560, 110, 1.0), (-300, 260, 1.6), (-800, 380, 1.3), (60, 480, 0.9), (940, 470, 1.0)):
        s.wolke(x, y, g, "#FFFFFF", 0.96)
    # Heißluftballon
    s.roh('<g transform="translate(690 400)">')
    s.pfad("M0,-150 C96,-150 124,-70 110,-10 C98,40 50,76 28,100 L-28,100 C-50,76 -98,40 -110,-10 C-124,-70 -96,-150 0,-150 Z", "#F2545B")
    s.pfad("M0,-150 C40,-150 56,-70 50,-10 C44,40 22,76 14,100 L-14,100 C-22,76 -44,40 -50,-10 C-56,-70 -40,-150 0,-150 Z", "#FFD93B")
    s.pfad("M0,-150 C10,-150 12,-70 10,-10 C8,40 4,76 2,100 L-2,100 C-4,76 -8,40 -10,-10 C-12,-70 -10,-150 0,-150 Z", "#F2545B")
    s.linie("M-26,102 L-16,140 M26,102 L16,140", "#0C2A60", 3)
    s.rechteck(-20, 138, 40, 30, "#8A5A2B", 6)
    s.roh('</g>')
    # Drachen (Papierdrachen)
    s.form([(330, 160), (372, 214), (330, 290), (288, 214)], "#FFD93B")
    s.form([(330, 160), (372, 214), (330, 214)], "#FF9F43")
    s.form([(330, 214), (330, 290), (288, 214)], "#FF9F43")
    s.linie("M330,290 C340,340 300,380 330,430 C352,470 410,560 452,640", "#0C2A60", 2.5)
    for x, y in ((328, 330), (318, 372), (334, 414)):
        s.form([(x - 12, y - 6), (x + 12, y + 6), (x + 12, y - 6), (x - 12, y + 6)], "#F2545B")
    # Hügel
    s.band([(-1900, 760), (-1400, 700), (-900, 750), (-400, 690), (0, 740), (300, 700), (700, 750), (1100, 700), (1400, 750)], "#3B8DE8")
    s.band([(-1900, 800), (-1000, 770), (-400, 800), (100, 760), (420, 700), (600, 690), (800, 730), (1100, 790), (1400, 780)], "#2166C8")
    # Häuschen
    s.rechteck(820, 700, 84, 62, "#FFFFFF")
    s.form([(808, 704), (862, 654), (916, 704)], "#F2545B")
    s.rechteck(852, 726, 20, 36, "#F2545B")
    s.rechteck(828, 716, 16, 16, "#5FB2F7")
    # Familie Hand in Hand auf dem Hügel, Hund
    f = "#0C2A60"
    s.mensch(430, 706, 150, f)
    s.mensch(500, 696, 96, f, art="kind")
    s.mensch(566, 694, 84, f, art="kind")
    s.mensch(640, 700, 140, f, art="frau")
    s.linie("M452,630 L484,640 M516,640 L552,646 M580,646 L618,632", f, 6)
    s.pfad("M690,712 c2,-16 16,-18 44,-18 l10,-14 l8,10 l12,2 l-8,10 l0,26 l-9,0 l0,-16 l-36,0 l0,16 l-9,0 l-2,-20 l-12,-10 Z", f)
    # Luftballon in Kinderhand
    s.linie("M586,640 C600,600 580,570 596,530", f, 2.5)
    s.ellipse(598, 500, 24, 30, "#F2545B")
    s.band([(-1900, 860), (-900, 838), (-200, 858), (400, 842), (900, 860), (1300, 846)], "#123E8C")
    z = random.Random(4)
    for _ in range(40):
        x = z.uniform(-1900, 1500)
        f2 = z.choice(("#FFD93B", "#FFFFFF", "#F2545B"))
        s.linie("M%.0f,852 v-18" % x, "#0C2A60", 3)
        s.kreis(x, 830, 7, f2)


def krieg(s):
    s.himmel([(-600, "#14170A"), (150, "#3A4219"), (520, "#6E7A34"), (720, "#B3AE66"), (800, "#E3D59B")])
    s.scheibe(650, 520, 110, "#F5ECC0", "#EFE2A0", 0.55)
    # Rauchsäulen
    for x0, br in ((300, 1.0), (820, 0.8), (-500, 1.1), (-1200, 0.9)):
        z = random.Random(x0)
        s.rauch([(x0 + k * 16 * br + z.uniform(-14, 14), 760 - k * 52, (30 + k * 9) * br) for k in range(12)], "#1F230F", 0.62, 12)
    # Flugzeuge
    for x, y, g in ((330, 200, 1.0), (450, 250, 0.8), (230, 270, 0.8), (-400, 220, 0.9)):
        s.roh('<g transform="translate(%d %d) scale(%.2f) rotate(-8)">' % (x, y, g))
        s.pfad("M-52,0 C-52,-7 -30,-9 30,-8 C46,-8 58,-4 60,0 C58,4 46,8 30,8 C-30,9 -52,7 -52,0 Z", "#14170A")
        s.pfad("M-6,-6 L10,-50 L26,-50 L22,-6 Z M-6,6 L10,50 L26,50 L22,6 Z M-50,-4 L-58,-24 L-46,-24 L-38,-4 Z", "#14170A")
        s.roh('</g>')
    # zerstörte Stadt in der Ferne
    s.band([(-1900, 760), (-1700, 700), (-1680, 640), (-1640, 660), (-1600, 610), (-1560, 760), (-1300, 760), (-1280, 680), (-1220, 700),
            (-1200, 650), (-1150, 760), (-800, 760), (-780, 690), (-740, 660), (-720, 720), (-680, 700), (-660, 760), (-300, 760), (-280, 700),
            (-240, 720), (-220, 640), (-180, 660), (-160, 760), (80, 760), (100, 690), (130, 670), (150, 720), (190, 700), (200, 760),
            (420, 760), (440, 700), (470, 720), (500, 650), (540, 680), (550, 760), (880, 760), (900, 690), (930, 660), (960, 720), (990, 700),
            (1000, 760), (1400, 760)], "#4C5722", weich=False)
    s.band([(-1900, 800), (-900, 780), (-200, 800), (300, 786), (800, 802), (1300, 790)], "#2A300F")
    # Panzer
    f = "#12140A"
    s.roh('<g transform="translate(610 790)">')
    s.pfad("M-170,-20 L-150,-70 L130,-70 L170,-20 Z", f)
    s.pfad("M-90,-70 L-70,-118 L50,-118 L74,-70 Z", f)
    s.rechteck(50, -106, 200, 13, f, 4)
    s.rechteck(238, -110, 26, 21, f, 4)
    s.rechteck(-30, -136, 46, 20, f, 6)
    s.linie("M-60,-118 L-84,-190", f, 4)
    s.roh('<rect x="-186" y="-24" width="372" height="62" rx="31" fill="%s"/>' % f)
    for k in range(6):
        s.kreis(-150 + k * 60, 8, 21, "#3A4219")
        s.kreis(-150 + k * 60, 8, 8, f)
    s.roh('</g>')
    # Soldaten mit Helm und Gewehr
    for x, b, h in ((150, 820, 150), (250, 826, 160), (340, 822, 146), (-200, 824, 150), (-320, 820, 156)):
        s.mensch(x, b, h, f)
        k = h * 0.075
        s.pfad("M%.1f,%.1f a%.1f,%.1f 0 0 1 %.1f,0 l%.1f,%.1f l%.1f,0 Z" % (x - k * 1.25, b - h + k * .9, k * 1.25, k * 1.35, k * 2.5, k * .35, k * .4, -k * 3.2), f)
        s.linie("M%.1f,%.1f L%.1f,%.1f" % (x - h * .16, b - h * .42, x + h * .22, b - h * 1.02), f, 5)
        s.rechteck(x - h * .2, b - h * .84, h * .13, h * .24, f, 4)
    # Stacheldraht
    s.band([(-1900, 860), (-900, 842), (-200, 858), (400, 846), (900, 860), (1300, 848)], "#0A0C05")
    v = "#0A0C05"
    for x in range(-1900, 1500, 190):
        s.linie("M%d,880 L%d,760" % (x, x + 12), v, 10)
        s.linie("M%d,772 L%d,846" % (x - 24, x + 44), v, 6)
    for y in (782, 812):
        d = "M-1900,%d " % y
        for x in range(-1900, 1500, 38):
            d += "q9,-16 19,0 q9,16 19,0 "
        s.linie(d, v, 2.6)
        for x in range(-1880, 1500, 76):
            s.linie("M%d,%d l8,8 M%d,%d l-8,8" % (x - 4, y - 5, x + 4, y - 5), v, 2.6)


def historie(s):
    s.himmel([(-600, "#3A220C"), (120, "#7A5226"), (480, "#B98F52"), (700, "#E8CC92"), (800, "#F9EBC6")])
    s.scheibe(620, 500, 130, "#FFF7DC", "#FFE9B0", 0.7)
    for x, y, g in ((300, 280, 14), (360, 320, 11), (-300, 300, 13), (860, 240, 12)):
        s.vogel(x, y, g, "#4A2E10")
    s.band([(-1900, 740), (-1500, 680), (-1100, 730), (-700, 660), (-300, 730), (100, 690), (500, 740), (900, 690), (1300, 740)], "#A27C46")
    # Arena mit Bögen (rechts, in der Ferne)
    f2 = "#7A5630"
    s.pfad("M700,770 L700,560 L760,548 L1040,548 L1040,770 Z", f2)
    s.pfad("M700,560 L740,470 L800,460 L800,548 L760,548 Z", f2)
    for reihe, y, h in ((0, 690, 62), (1, 590, 56)):
        for k in range(6):
            x = 716 + k * 54
            s.pfad("M%d,%d v-%d a17,17 0 0 1 34,0 v%d Z" % (x, y + h, h - 17, h - 17), "#E8CC92", 0.85)
    for k in range(2):
        x = 748 + k * 0
    s.band([(-1900, 780), (-900, 760), (-300, 780), (200, 740), (420, 716), (640, 730), (900, 776), (1300, 770)], "#5E3F1C")
    # Tempel auf dem Hügel
    f = "#2B1908"
    s.form([(170, 492), (330, 410), (490, 492)], f)
    s.form([(214, 484), (330, 426), (446, 484)], "#7A5630")
    s.rechteck(160, 490, 340, 22, f)
    for k in range(6):
        x = 178 + k * 57
        s.rechteck(x, 512, 22, 196, f)
        s.rechteck(x - 5, 512, 32, 10, f)
        s.rechteck(x - 5, 700, 32, 10, f)
    s.rechteck(150, 708, 360, 16, f)
    s.rechteck(134, 724, 392, 16, f)
    s.rechteck(118, 740, 424, 16, f)
    # Reiterstandbild / Ritter mit Banner und Schild
    v = "#1E1005"
    s.band([(-1900, 850), (-900, 832), (-200, 850), (300, 836), (700, 852), (1200, 838)], v)
    s.mensch(640, 842, 170, v)
    s.pfad("M622,684 a18,20 0 0 1 36,0 l4,10 l-44,0 Z", v)                               # Helm
    s.pfad("M640,662 q16,-30 34,-22 q-14,6 -20,26 Z", "#B4262A")                          # Helmbusch
    s.pfad("M588,726 q26,-10 52,0 q0,54 -26,74 q-26,-20 -26,-74 Z", v)                     # Schild
    s.pfad("M600,734 q14,-5 28,0 q0,36 -14,50 q-14,-14 -14,-50 Z", "#B98F52")
    s.linie("M690,842 L690,560", v, 6)                                                     # Lanze mit Banner
    s.pfad("M693,566 L780,580 L750,606 L780,634 L693,640 Z", "#B4262A")
    s.form([(690, 540), (682, 566), (698, 566)], v)
    # umgestürzte Säule
    s.roh('<g transform="rotate(-8 300 850)"><rect x="200" y="826" width="210" height="30" rx="4" fill="%s"/><rect x="190" y="820" width="16" height="42" fill="%s"/></g>' % (v, v))
    s.rechteck(880, 760, 30, 90, v)
    s.rechteck(872, 752, 46, 12, v)
    for x, h in ((-400, 110), (-460, 80), (-1000, 120)):
        s.rechteck(x, 850 - h, 28, h, v)
        s.rechteck(x - 7, 850 - h - 10, 42, 12, v)


SZENEN = [
    # (Name der Sammlung in Jellyfin, Dateiname, Zeichner, Zeilen für die Kachel)
    ("Action", "action", action, ["Action"]),
    ("Komödien", "komoedien", komoedien, ["Komödien"]),
    ("Horror", "horror", horror, ["Horror"]),
    ("Thriller", "thriller", thriller, ["Thriller"]),
    ("Science-Fiction", "science-fiction", scifi, ["Science-Fiction"]),
    ("Krimi", "krimi", krimi, ["Krimi"]),
    ("Drama", "drama", drama, ["Drama"]),
    ("Abenteuer", "abenteuer", abenteuer, ["Abenteuer"]),
    ("Fantasy", "fantasy", fantasy, ["Fantasy"]),
    ("Mystery", "mystery", mystery, ["Mystery"]),
    ("Liebesfilme", "liebesfilme", liebesfilme, ["Liebesfilme"]),
    ("Familie & Animation", "familie-animation", familie, ["Familie &", "Animation"]),
    ("Kriegsfilme", "kriegsfilme", krieg, ["Kriegsfilme"]),
    ("Historienfilme", "historienfilme", historie, ["Historienfilme"]),
    ("Western", "western", western, ["Western"]),
]


def rendern(svg, ziel_png):
    p = subprocess.run(["rsvg-convert", "-o", ziel_png], input=svg.encode("utf-8"), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace")[:800])


def schrift(groesse, gewicht=b"ExtraBold"):
    f = ImageFont.truetype(SCHRIFT, groesse)
    try:
        f.set_variation_by_name(gewicht)
    except Exception:
        pass
    return f


def kachel(name, datei, zeichner, zeilen, ordner):
    s = Szene()
    zeichner(s)
    # Kern 1,25-fach, unten bündig, waagerecht mittig: sichtbar ist Kern-x 100..900
    svg = s.svg(1000, 1500, 1.25, -125, 250)
    png = os.path.join(ordner, "kachel", datei + ".png")
    rendern(svg, png)
    b = Image.open(png).convert("RGB")
    # dunkler Verlauf unten, darauf der Name (weiß, fett, mittig) – auf dem Fernseher ist die Kachel nur ca. 200 px breit
    v = Image.new("L", (1, 1500), 0)
    for y in range(1500):
        t = max(0.0, min(1.0, (y - 1040) / 300.0))
        v.putpixel((0, y), int(225 * (t * t * (3 - 2 * t))))
    b.paste(Image.new("RGB", b.size, (6, 6, 9)), (0, 0), v.resize(b.size))
    d = ImageDraw.Draw(b)
    gr = 128
    while gr > 60:
        f = schrift(gr)
        if max(d.textlength(z, font=f) for z in zeilen) <= 900:
            break
        gr -= 2
    zh = int(gr * 1.06)
    y = 1500 - 96 - zh * len(zeilen)
    for z in zeilen:
        w = d.textlength(z, font=f)
        d.text(((1000 - w) / 2 + 3, y + 4), z, font=f, fill=(0, 0, 0))
        d.text(((1000 - w) / 2, y), z, font=f, fill=(255, 255, 255))
        y += zh
    ziel = os.path.join(ordner, "kachel", datei + ".jpg")
    b.save(ziel, "JPEG", quality=90, optimize=True)
    os.remove(png)
    return ziel


def breit(name, datei, zeichner, ordner):
    s = Szene()
    zeichner(s)
    # Motiv oben rechts (dort ist auf dem Fernseher Platz): Kern 0,75-fach, Horizont bei etwa y = 480
    svg = s.svg(1920, 1080, 0.75, 1160, -120)
    png = os.path.join(ordner, "breit", datei + ".png")
    rendern(svg, png)
    b = Image.open(png).convert("RGB")
    ziel = os.path.join(ordner, "breit", datei + ".jpg")
    b.save(ziel, "JPEG", quality=88, optimize=True)
    os.remove(png)
    return ziel


def main():
    ordner = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HIER, "bilder")
    nur = [a.lower() for a in sys.argv[2:]]
    for u in ("kachel", "breit"):
        os.makedirs(os.path.join(ordner, u), exist_ok=True)
    for name, datei, zeichner, zeilen in SZENEN:
        if nur and datei not in nur:
            continue
        kachel(name, datei, zeichner, zeilen, ordner)
        breit(name, datei, zeichner, ordner)
        print("ok", name)


if __name__ == "__main__":
    main()
