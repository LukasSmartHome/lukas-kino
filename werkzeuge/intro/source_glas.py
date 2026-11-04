#!/usr/bin/env python3
"""SOURCE – kurzes Intro, Version 2: tiefe 3D-Buchstaben aus mattem Glas.
Die Bilder laufen auf den Seitenwänden der Buchstaben, vorne mattes Glas.
Kamera fährt aus dem S raus und dreht nach vorne, Knall bei 5,0 s.
Aufruf:  python3 kurz2.py test 0.1 1.0 ...  -> test2.jpg + test2_full.jpg
         python3 kurz2.py                   -> SOURCE_Intro_Glas.mp4 (Musik: musik_kurz.wav)
"""
import sys, os, math, glob, subprocess
import numpy as np, cv2
from fontTools.ttLib import TTFont
from fontTools.pens.basePen import BasePen
from PIL import Image, ImageDraw, ImageFont

S = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(S, 'src', 'SOURCE_Intro')
W, H, FPS = 1920, 1080, 30
T_B, T_END = 5.0, 10.0
NF = int(round(T_END * FPS))
DEPTH, NS = 0.30, 6
f32 = np.float32


def smooth(a, b, x):
    if x <= a: return 0.0
    if x >= b: return 1.0
    u = (x - a) / (b - a)
    return u * u * (3 - 2 * u)


class FlatPen(BasePen):
    def __init__(self, gs, steps=10):
        super().__init__(gs); self.contours = []; self.cur = []; self.steps = steps
    def _moveTo(self, p): self.cur = [p]
    def _lineTo(self, p): self.cur.append(p)
    def _curveToOne(self, p1, p2, p3):
        p0 = self.cur[-1]
        for i in range(1, self.steps + 1):
            t = i / self.steps; m = 1 - t
            self.cur.append((m**3*p0[0] + 3*m*m*t*p1[0] + 3*m*t*t*p2[0] + t**3*p3[0],
                             m**3*p0[1] + 3*m*m*t*p1[1] + 3*m*t*t*p2[1] + t**3*p3[1]))
    def _qCurveToOne(self, p1, p2):
        p0 = self.cur[-1]
        for i in range(1, self.steps + 1):
            t = i / self.steps; m = 1 - t
            self.cur.append((m*m*p0[0] + 2*m*t*p1[0] + t*t*p2[0], m*m*p0[1] + 2*m*t*p1[1] + t*t*p2[1]))
    def _closePath(self):
        c = self.cur
        if len(c) > 1 and abs(c[0][0] - c[-1][0]) < 1e-6 and abs(c[0][1] - c[-1][1]) < 1e-6: c = c[:-1]
        if len(c) > 2: self.contours.append(np.array(c, float))
        self.cur = []
    _endPath = _closePath


def load_letters():
    font = TTFont(os.path.join(SRC, 'fonts', 'oswald700.ttf'))
    gs = font.getGlyphSet(); cmap = font.getBestCmap(); upm = font['head'].unitsPerEm
    x = 0.0; letters = []
    for ch in 'SOURCE':
        g = gs[cmap[ord(ch)]]; pen = FlatPen(gs); g.draw(pen)
        letters.append([c + np.array([x, 0.0]) for c in pen.contours])
        x += g.width + 0.035 * upm
    allp = np.concatenate([c for l in letters for c in l])
    x0, y0 = allp.min(0); x1, y1 = allp.max(0)
    s = 1.0 / (x1 - x0); cx = (x0 + x1) / 2; cy = (y0 + y1) / 2
    return [[np.stack([(c[:, 0] - cx) * s, -(c[:, 1] - cy) * s], 1) for c in l] for l in letters]


def inside(letter, p):
    n = 0
    for c in letter:
        if cv2.pointPolygonTest(c.astype(f32).reshape(-1, 1, 2), (float(p[0]), float(p[1])), False) > 0: n += 1
    return n % 2 == 1


LET = load_letters()
LD = []
for l in LET:
    pts, nrm, nxt, off = [], [], [], 0
    for c in l:
        n = len(c); b = np.roll(c, -1, 0); d = b - c; ln = np.linalg.norm(d, axis=1) + 1e-12
        nv = np.stack([d[:, 1], -d[:, 0]], 1) / ln[:, None]
        i = int(np.argmax(ln))
        if inside(l, (c[i] + b[i]) / 2 + nv[i] * 1e-4): nv = -nv      # Normale nach außen
        pts.append(c); nrm.append(nv); nxt.append(off + (np.arange(n) + 1) % n); off += n
    LD.append(dict(P=np.concatenate(pts), N=np.concatenate(nrm), nxt=np.concatenate(nxt), sizes=[len(c) for c in l]))
ALLP = np.concatenate([d['P'] for d in LD])


def thick_point(letter):
    pts = np.concatenate(letter); x0, y0 = pts.min(0); x1, y1 = pts.max(0)
    sc = 1500 / (y1 - y0); w = int((x1 - x0) * sc) + 20; h = int((y1 - y0) * sc) + 20
    m = np.zeros((h, w), np.uint8)
    cv2.fillPoly(m, [np.round(((c - [x0, y0]) * sc + 10) * 16).astype(np.int32) for c in letter], 255, cv2.LINE_8, 4)
    dt = cv2.distanceTransform(m, cv2.DIST_L2, 5)
    yy, xx = np.unravel_index(np.argmax(dt), dt.shape)
    return np.array([(xx - 10) / sc + x0, (yy - 10) / sc + y0])


def rot(yaw, pitch, roll):
    cy, sy = math.cos(yaw), math.sin(yaw); cp, sp = math.cos(pitch), math.sin(pitch); cr, sr = math.cos(roll), math.sin(roll)
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rx = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]])
    Rz = np.array([[cr, -sr, 0], [sr, cr, 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


# ---------------- Kamera ----------------
F0, F1 = 1100.0, 3000.0            # Weitwinkel am Anfang, am Ende fast flach (wie Tele)
MAG1, MAG0_T = 1160.0, 4200.0      # Bildschirm-Pixel pro Schriftbreite
YAW0, PITCH0, ROLL0 = math.radians(-62), math.radians(18), math.radians(-8)
T_ROT = T_B - 0.1
P0 = thick_point(LET[0]); PIV0 = np.array([P0[0], P0[1], DEPTH * 0.5])
PIV1 = np.array([0.0, 34 / MAG1, 0.0])
_p3 = np.concatenate([np.c_[ALLP, np.full(len(ALLP), z)] for z in (0.0, DEPTH)])
_cz = ((_p3 - PIV0) @ rot(YAW0, PITCH0, ROLL0).T)[:, 2]
D0 = max(F0 / MAG0_T, 0.06 - _cz.min()); MAG0 = F0 / D0


def cam(t):
    u = min(max(t / T_END, 0), 1); g = 1 - (1 - u) ** 4
    mag = math.exp(math.log(MAG0) + (math.log(MAG1) - math.log(MAG0)) * g)
    r = smooth(0, T_ROT, t)
    Fc = math.exp(math.log(F0) + (math.log(F1) - math.log(F0)) * smooth(0, T_B + 1.5, t))
    ox = oy = 0.0
    if t >= T_B:
        k = math.exp(-(t - T_B) / 0.12); ox = k * 10 * math.sin(t * 90); oy = k * 8 * math.cos(t * 77)
    return dict(F=Fc, D=Fc / mag, R=rot(YAW0 * (1 - r), PITCH0 * (1 - r), ROLL0 * (1 - r)),
                piv=PIV0 * (1 - r) + PIV1 * r, ox=ox, oy=oy, dep=DEPTH * (1 - 0.8 * smooth(T_B, T_B + 0.12, t)))


def project(c, P2, z):
    X = np.empty((len(P2), 3)); X[:, :2] = P2; X[:, 2] = z; X -= c['piv']
    q = X @ c['R'].T; q[:, 2] += c['D']
    return np.stack([W / 2 + c['ox'] + c['F'] * q[:, 0] / q[:, 2], H / 2 + c['oy'] + c['F'] * q[:, 1] / q[:, 2]], 1), q


LIGHT = np.array([-0.45, -0.75, -0.5]); LIGHT /= np.linalg.norm(LIGHT)


def draw(c):
    """Malt Seitenwände (Kanal 0 Deckung, Kanal 2 Helligkeit) und Vorderseiten (Kanal 1), von hinten nach vorne."""
    buf = np.zeros((H, W, 3), np.uint8)
    zs = np.linspace(0, c['dep'], NS + 1)
    items = []
    for d in LD:
        lv = [project(c, d['P'], z) for z in zs]
        Sx = np.stack([s for s, q in lv]); Q = np.stack([q for s, q in lv])
        items.append((Q[0, :, 2].mean(), d, Sx, Q))
    items.sort(key=lambda x: -x[0])
    zmin = min(it[3][..., 2].min() for it in items)
    ys = []
    for _, d, Sx, Q in items:
        nx = d['nxt']
        ncam = np.c_[d['N'], np.zeros(len(d['N']))] @ c['R'].T
        lam = np.clip(ncam @ LIGHT, 0, 1)
        Vs, Ds, Ss = [], [], []
        for j in range(NS):
            v = np.stack([Sx[j], Sx[j][nx], Sx[j + 1][nx], Sx[j + 1]], 1)
            qm = (Q[j] + Q[j][nx] + Q[j + 1] + Q[j + 1][nx]) / 4
            fac = np.einsum('ij,ij->i', ncam, -qm) > 0
            sh = (0.55 + 0.45 * lam) * (1 - 0.35 * (j + 0.5) / NS)
            Vs.append(v[fac]); Ds.append(qm[fac, 2]); Ss.append(sh[fac])
        V = np.concatenate(Vs); Dp = np.concatenate(Ds); Sh = np.concatenate(Ss)
        mn = V.min(1); mx = V.max(1)
        ok = (mx[:, 0] > -2) & (mn[:, 0] < W + 2) & (mx[:, 1] > -2) & (mn[:, 1] < H + 2)
        V, Dp, Sh = V[ok], Dp[ok], Sh[ok]
        o = np.argsort(-Dp); V, Sh = V[o], Sh[o]
        cen = V.mean(1, keepdims=True); dv = V - cen
        V = V + dv / np.maximum(np.linalg.norm(dv, axis=2, keepdims=True), 1e-6) * 0.6
        Vi = np.round(V * 16).astype(np.int32); Si = np.clip(Sh * 255, 0, 255).astype(int)
        for k in range(len(Vi)):
            cv2.fillConvexPoly(buf, Vi[k], (255, 0, int(Si[k])), cv2.LINE_AA, 4)
        fp = [np.round(s * 16).astype(np.int32) for s in np.split(Sx[0], np.cumsum(d['sizes'])[:-1])]
        cv2.fillPoly(buf, fp, (0, 255, 0), cv2.LINE_AA, 4)
        ys.append(Sx[0][:, 1])
    ys = np.concatenate(ys)
    return buf, zmin, ys.min(), ys.max()


# ---------------- Bilder ----------------
FILES = sorted(glob.glob(os.path.join(SRC, 'epic', 'bilder', '*.png')))
IM = [cv2.imread(f) for f in FILES]
SM = [cv2.GaussianBlur(cv2.resize(im, (240, 135), interpolation=cv2.INTER_AREA), (0, 0), 3) for im in IM]
rng = np.random.default_rng(7)
order = list(rng.permutation(len(IM))) + list(rng.permutation(len(IM)))
SCHED = []
k = 0
while len(SCHED) < NF:
    t = len(SCHED) / FPS
    dur = 4 if t < 1.6 else (3 if t < 3.2 else 2)
    idx = order[k % len(order)]; k += 1
    for j in range(dur): SCHED.append((idx, j / dur, k))


def content(idx, prog, ks):
    s = 1.03 + 0.05 * prog
    dx, dy = np.random.default_rng(ks).uniform(-25, 25, 2)
    M = np.array([[s, 0, (1 - s) * W / 2 + dx], [0, s, (1 - s) * H / 2 + dy]], f32)
    return cv2.warpAffine(IM[idx], M, (W, H), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT).astype(f32) / 255


yy, xx = np.mgrid[0:H, 0:W].astype(f32)
RR = np.sqrt(((xx - W / 2) / (W * 0.62)) ** 2 + ((yy - H / 2) / (H * 0.62)) ** 2)
BASE = (np.clip(1 - RR, 0, 1) ** 1.6)[..., None] * np.array([0.03, 0.04, 0.055], f32)   # BGR
VIG = (1 - 0.35 * np.clip(RR, 0, 1.3) ** 2)[..., None].astype(f32)
DIAG = xx + 0.35 * yy
REFL = (0.07 * np.exp(-((DIAG - 0.62 * W) / 300) ** 2) + 0.08 * np.exp(-((DIAG - 0.36 * W) / 70) ** 2))[..., None].astype(f32)
NOISE = cv2.GaussianBlur(np.random.default_rng(3).normal(0, 0.02, (H, W)).astype(f32), (0, 0), 0.8)[..., None]
GLOWC = np.array([0.35, 0.66, 1.0], f32)
EDGEC = np.array([0.80, 0.92, 1.0], f32)
GT, GB = np.array([0.66, 0.69, 0.71], f32), np.array([0.25, 0.26, 0.28], f32)
WARM = np.array([0.45, 0.78, 1.0], f32)
SMOKE = np.array([0.20, 0.20, 0.21], f32)
K3 = np.ones((3, 3), np.uint8)


def make_sub():
    fnt = ImageFont.truetype(os.path.join(SRC, 'fonts', 'oswald500.ttf'), 40)
    text = 'A LUKAS WERNER SOLUTION'; tr = 0.42 * 40
    ws = [fnt.getlength(ch) for ch in text]; total = sum(ws) + tr * (len(text) - 1)
    im = Image.new('RGBA', (int(total) + 40, 80), (0, 0, 0, 0)); d = ImageDraw.Draw(im); x = 20
    for ch, wd in zip(text, ws):
        d.text((x, 10), ch, font=fnt, fill=(236, 230, 218, 255)); x += wd + tr
    a = np.array(im).astype(f32) / 255
    return a[..., 2::-1].copy(), a[..., 3:4].copy()


SUB_RGB, SUB_A = make_sub()
_, _, _, _ybot = draw(cam(T_END))
SUB_Y = int(_ybot + 40); SUB_X = int(W / 2 - SUB_RGB.shape[1] / 2)


def render(fi):
    t = fi / FPS; c = cam(t); pre = t < T_B
    fl = math.exp(-(t - T_B) / 0.14) if t >= T_B else 0.0
    buf, zmin, ytop, ybot = draw(c)
    wa = buf[..., 0].astype(f32) / 255; fa = buf[..., 1].astype(f32) / 255
    sh = np.clip((buf[..., 2].astype(f32) / 255) / np.maximum(wa, 1 / 255), 0, 1)
    idx, prog, ks = SCHED[fi]
    if pre:
        cont = content(idx, prog, ks)
        blur = cv2.resize(SM[idx], (W, H), interpolation=cv2.INTER_LINEAR).astype(f32) / 255
        bg = blur * (0.22 - 0.16 * smooth(0.5, T_B, t)) + BASE
        wallc = cont * (sh[..., None] * 1.08)
        facec = blur * 0.55 + 0.07 + NOISE
    else:
        bg = BASE.copy()
        wallc = sh[..., None] * SMOKE
        v = np.clip((np.arange(H, dtype=f32) - ytop) / max(ybot - ytop, 1), 0, 1)[:, None, None]
        facec = (GT + (GB - GT) * v) + WARM * (0.07 * (1 - 0.5 * v)) + NOISE
    cov = np.clip(wa + fa, 0, 1)
    gk = 0.15 * smooth(1.5, T_B, t) if pre else 0.30 + 1.0 * fl
    if gk > 0:
        small = cv2.resize(cov, (W // 4, H // 4), interpolation=cv2.INTER_AREA)
        glow = cv2.resize(0.5 * cv2.GaussianBlur(small, (0, 0), 6) + 0.8 * cv2.GaussianBlur(small, (0, 0), 20), (W, H))
        bg = bg + glow[..., None] * GLOWC * gk
    dist = cv2.distanceTransform((fa > 0.5).astype(np.uint8), cv2.DIST_L2, 3)
    fres = np.exp(-dist / 4) * 0.30 + np.exp(-dist / 20) * 0.10
    hi = np.clip(fa - np.roll(fa, 3, 0), 0, 1)
    facec = facec + fres[..., None] * EDGEC + hi[..., None] * 0.45 + REFL
    if not pre:
        facec = facec * 0.9 + bg * 0.1
        facec = facec * (1 - fl) + fl
    img = bg * np.clip(1 - wa - fa, 0, 1)[..., None] + wallc * wa[..., None] + facec * fa[..., None]
    if not pre:
        gs = smooth(T_B + 1.2, T_B + 2.1, t)
        if 0 < gs < 1:
            pos = 200 + gs * (W + 200)
            img += (np.exp(-((DIAG - pos) / 45) ** 2) * fa * 0.35)[..., None]
        img += fl * 0.5
        sa = smooth(T_B + 0.45, T_B + 1.05, t)
        if sa > 0:
            y0 = SUB_Y - 10 + int(round(12 * (1 - sa))); h, w = SUB_A.shape[:2]
            reg = img[y0:y0 + h, SUB_X:SUB_X + w]
            img[y0:y0 + h, SUB_X:SUB_X + w] = reg * (1 - SUB_A * sa) + SUB_RGB * SUB_A * sa
    img *= VIG
    img *= smooth(0, 0.25, t) * (1 - smooth(T_END - 0.8, T_END, t))
    return np.clip(img * 255, 0, 255).astype(np.uint8), zmin


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == 'test':
        ts = [float(x) for x in sys.argv[2:]]; tiles = []
        for t in ts:
            fr, zmin = render(min(int(round(t * FPS)), NF - 1)); print(t, 'zmin', round(zmin, 3))
            tiles.append(cv2.resize(fr, (640, 360), interpolation=cv2.INTER_AREA))
            if t == ts[-1]: cv2.imwrite(os.path.join(S, 'test2_full.jpg'), fr, [cv2.IMWRITE_JPEG_QUALITY, 90])
        while len(tiles) % 3: tiles.append(np.zeros_like(tiles[0]))
        cv2.imwrite(os.path.join(S, 'test2.jpg'), np.vstack([np.hstack(tiles[i:i + 3]) for i in range(0, len(tiles), 3)]),
                    [cv2.IMWRITE_JPEG_QUALITY, 88])
        sys.exit()
    tmp = os.path.join(S, 'video2_tmp.mp4'); vid = os.path.join(S, 'video2.mp4')
    p = subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'bgr24', '-s', f'{W}x{H}', '-r', str(FPS),
                          '-i', '-', '-c:v', 'libx264', '-crf', '17', '-preset', 'veryfast', '-pix_fmt', 'yuv420p', tmp], stdin=subprocess.PIPE)
    zm = 9
    for fi in range(NF):
        fr, zmin = render(fi); zm = min(zm, zmin); p.stdin.write(fr.tobytes())
    p.stdin.close(); assert p.wait() == 0
    os.replace(tmp, vid); print('min depth', round(zm, 3))
    out = os.path.join(S, 'SOURCE_Intro_Glas.mp4')
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', vid, '-i', os.path.join(S, 'musik_kurz.wav'), '-map', '0:v', '-map', '1:a',
                    '-c:v', 'copy', '-c:a', 'aac', '-b:a', '256k', '-shortest', out + '.tmp.mp4'], check=True)
    os.replace(out + '.tmp.mp4', out); print('fertig', out)
