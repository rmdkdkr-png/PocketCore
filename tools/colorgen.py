"""color preset prototypes — 12-bit 0x0BGR triples (c1 skin/light, c2 outfit, c3 outline)"""
import colorsys
PAL1 = [
 (((0xBDF,0x00D,0x005),(0xBDF,0x05F,0x014)),((0x9BF,0x309,0x005),(0x9BF,0x26C,0x015))),
 (((0xBDF,0xF90,0x420),(0xCDF,0x980,0x230)),((0xFDE,0xD57,0x420),(0xFDE,0xA46,0x330))),
 (((0xBDF,0x00D,0x023),(0xBDF,0x080,0x023)),((0x9BF,0xA40,0x023),(0x9BF,0x827,0x023))),
 (((0xBCF,0x60C,0x005),(0xBCF,0x406,0x002)),((0xBDF,0xC66,0x500),(0xBDF,0x07D,0x033))),
 (((0xCDF,0x43F,0x224),(0xBDF,0x07F,0x224)),((0xBCF,0xA0A,0x224),(0xBDF,0xA6F,0x224))),
 (((0xCDF,0xF50,0x410),(0xCDF,0xA90,0x230)),((0xCDF,0xF67,0x312),(0xCDF,0xA6F,0x223))),
 (((0x9DF,0x653,0x210),(0xDDD,0x623,0x302)),((0xAFF,0x628,0x005),(0xAFF,0x07C,0x123))),
 (((0xBDF,0xF81,0x410),(0xBDF,0x09F,0x023)),((0xCDF,0xA00,0x500),(0xCDF,0xE33,0x510))),
 (((0xFFF,0x444,0x222),(0xFFF,0x988,0x222)),((0xFF9,0x00D,0x004),(0xFF9,0xD0A,0x203))),
 (((0xBEF,0xF55,0x401),(0xFFF,0x40F,0x104)),((0xCEF,0x00F,0x014),(0xCEF,0xB4B,0x203))),
 (((0xBEF,0xC72,0x500),(0x9EF,0x02D,0x114)),((0xBDF,0x074,0x032),(0x9CD,0x679,0x213))),
 (((0xBDF,0xE60,0x500),(0xBBF,0x40A,0x005)),((0xBDF,0x380,0x032),(0xBCF,0xA67,0x303))),
 (((0xADF,0x36A,0x123),(0xBDF,0x03B,0x123)),((0xADF,0x363,0x123),(0xFFF,0xBBB,0x123))),
 (((0xEFF,0xB40,0x500),(0xCDF,0xA0F,0x303)),((0xFFF,0xB99,0x222),(0xBBF,0xA48,0x203))),
]
def rgb(c): return (c & 15, (c >> 4) & 15, (c >> 8) & 15)
def pack(r, g, b):
    q = lambda v: max(0, min(15, int(round(v))))
    return q(r) | (q(g) << 4) | (q(b) << 8)
def lum(c): r, g, b = rgb(c); return (0.299 * r + 0.587 * g + 0.114 * b) / 15
def hsv(c): r, g, b = rgb(c); return colorsys.rgb_to_hsv(r / 15, g / 15, b / 15)
def from_hsv(h, s, v): r, g, b = colorsys.hsv_to_rgb(h % 1.0, max(0, min(1, s)), max(0, min(1, v))); return pack(r * 15, g * 15, b * 15)
def hexc(c): r, g, b = rgb(c); return '#%x%x%x' % (r, g, b)
def dist(a, b):
    return sum(abs(x - y) for p, q in zip(a, b) for x, y in zip(rgb(p), rgb(q)))
# ---- transforms ----
def gen_old(c, k):          # league.c gen_color (컬러 3·4)
    h, s, v = hsv(c); h *= 360
    if s < 0.25:
        h = 350 if k == 2 else 160; s = 0.65
        if v < 0.5: v = 0.3 + v * 0.8
    else: h += 120 if k == 2 else 240
    return from_hsv(h / 360, s, v)
def t_rot(B, A, k): return (B[0], gen_old(B[1], k), gen_old(B[2], k))
def t_tint(B, A, hue, s2=0.8, s3=0.85, v2=(0.55, 1.0), v3=(0.2, 0.45)):
    h2, S2, V2 = hsv(B[1]); h3, S3, V3 = hsv(B[2])
    hh = hue / 360
    near = S2 >= 0.3 and min(abs(h2 - hh), 1 - abs(h2 - hh)) < 35 / 360
    if near:  # base outfit already this hue — go deep/dark variant so it is still a different look
        return (B[0], from_hsv(hh, 1.0, max(v2[0], V2) * 0.6), from_hsv(hh, 1.0, 0.2))
    return (B[0], from_hsv(hh, s2, min(max(V2, v2[0]), v2[1])), from_hsv(hh, s3, min(max(V3, v3[0]), v3[1])))
def t_rank(B, A, theme):    # theme = (light, mid, dark) — map by luminance rank
    order = sorted(range(3), key=lambda j: -lum(B[j]))
    out = [0, 0, 0]
    for rank, j in enumerate(order): out[j] = theme[rank]
    return tuple(out)
def t_lummap(B, A, stops):   # stops: list of (lum, color) — interpolate per color by its luminance
    out = []
    for c in B:
        L = lum(c)
        for (l0, c0), (l1, c1) in zip(stops, stops[1:]):
            if L <= l1 or (l1 == stops[-1][0]):
                t = 0 if l1 == l0 else max(0, min(1, (L - l0) / (l1 - l0)))
                a, b = rgb(c0), rgb(c1)
                out.append(pack(*[a[i] + (b[i] - a[i]) * t for i in range(3)])); break
    return tuple(out)
def t_mono(B, A): return tuple(pack(*(lum(c) * 15,) * 3) for c in B)
def t_neg(B, A): return tuple(0xFFF - c for c in B)
def t_keepskin(B, A, c2, c3): return (B[0], c2, c3)

PRESETS = [
 ('기본',   lambda B, A: B),
 ('2P색',   lambda B, A: A),
 ('컬러3',  lambda B, A: t_rot(B, A, 2)),
 ('컬러4',  lambda B, A: t_rot(B, A, 3)),
 ('쪽빛',   lambda B, A: t_tint(B, A, 225)),
 ('하늘',   lambda B, A: t_tint(B, A, 195, s2=0.6, v2=(0.85, 1.0))),
 ('비취',   lambda B, A: t_tint(B, A, 150)),
 ('풀빛',   lambda B, A: t_tint(B, A, 100)),
 ('보라',   lambda B, A: t_tint(B, A, 280)),
 ('다홍',   lambda B, A: t_tint(B, A, 355)),
 ('주황',   lambda B, A: t_tint(B, A, 28)),
 ('노랑',   lambda B, A: t_tint(B, A, 52, v2=(0.8, 1.0))),
 ('분홍',   lambda B, A: t_tint(B, A, 330, s2=0.45, v2=(0.85, 1.0))),
 ('먹빛',   lambda B, A: t_keepskin(B, A, 0x333, 0x111)),
 ('흰옷',   lambda B, A: t_keepskin(B, A, 0xEEE, 0x777)),
 ('황금',   lambda B, A: t_rank(B, A, (0x9EF, 0x0AE, 0x035))),
 ('은빛',   lambda B, A: t_rank(B, A, (0xFFF, 0xBAA, 0x544))),
 ('청동',   lambda B, A: t_rank(B, A, (0x8BE, 0x269, 0x123))),
 ('옥돌',   lambda B, A: t_rank(B, A, (0xCFC, 0x6B4, 0x242))),
 ('흑백',   t_mono),
 ('세피아', lambda B, A: t_lummap(B, A, [(0, 0x012), (0.5, 0x58A), (1, 0xCEF)])),
 ('그림자', lambda B, A: t_rank(B, A, (0x645, 0x323, 0x101))),
 ('유령',   lambda B, A: t_rank(B, A, (0xFFE, 0xEC9, 0x965))),
 ('반전',   t_neg),
 ('핏빛',   lambda B, A: t_rank(B, A, (0x9AF, 0x00C, 0x003))),
 ('불꽃',   lambda B, A: t_rank(B, A, (0x8FF, 0x09F, 0x017))),
]
def preset(name_or_idx, ch, sty):
    B, A = PAL1[ch][sty]
    if isinstance(name_or_idx, int): return PRESETS[name_or_idx][1](B, A)
    for n, f in PRESETS:
        if n == name_or_idx: return f(B, A)

# ---- refined (v2) ----
def t_tint2(B, A, hue, s2=0.8, s3=0.85, v2=(0.55, 1.0), v3=(0.2, 0.45)):
    h2, S2, V2 = hsv(B[1]); hh = hue / 360
    near = S2 >= 0.3 and min(abs(h2 - hh), 1 - abs(h2 - hh)) < 35 / 360
    if near:   # 이미 그 색 계열 — 어두운 옷이면 밝게, 밝은 옷이면 짙게
        if V2 < 0.75: return (B[0], from_hsv(hh, 0.45, 1.0), from_hsv(hh, 0.9, 0.55))
        return (B[0], from_hsv(hh, 1.0, 0.55), from_hsv(hh, 1.0, 0.2))
    _, S3, V3 = hsv(B[2])
    return (B[0], from_hsv(hh, s2, min(max(V2, v2[0]), v2[1])), from_hsv(hh, s3, min(max(V3, v3[0]), v3[1])))
def t_mono2(B, A):
    m = t_mono(B, A)
    if dist(m, B) <= 6:   # 이미 무채색(아수라 수라) — 대비를 늘림
        return t_rank(B, A, (0xFFF, 0x999, 0x000))
    return m
FINAL = [
 ('기본',   lambda B, A: B),
 ('2P색',   lambda B, A: A),
 ('쪽빛',   lambda B, A: t_tint2(B, A, 225)),
 ('비취',   lambda B, A: t_tint2(B, A, 150)),
 ('보라',   lambda B, A: t_tint2(B, A, 280)),
 ('다홍',   lambda B, A: t_tint2(B, A, 355)),
 ('분홍',   lambda B, A: t_tint2(B, A, 330, s2=0.45, v2=(0.85, 1.0))),
 ('먹빛',   lambda B, A: t_keepskin(B, A, 0x333, 0x111)),
 ('흰옷',   lambda B, A: t_keepskin(B, A, 0xEEE, 0x444)),
 ('황금',   lambda B, A: t_rank(B, A, (0x9EF, 0x0AE, 0x035))),
 ('은빛',   lambda B, A: t_rank(B, A, (0xFFF, 0xBAA, 0x544))),
 ('청동',   lambda B, A: t_rank(B, A, (0x8BE, 0x269, 0x123))),
 ('흑백',   t_mono2),
 ('그림자', lambda B, A: t_rank(B, A, (0x645, 0x323, 0x101))),
 ('핏빛',   lambda B, A: t_rank(B, A, (0x9AF, 0x00C, 0x003))),
 ('유령',   lambda B, A: t_rank(B, A, (0xFFE, 0xEC9, 0x965))),
]
PRESETS = PRESETS + [(n + '2', f) for n, f in FINAL if n in ('쪽빛', '비취', '보라', '다홍', '분홍', '흰옷', '흑백')]
def final_triples(ch, sty):
    B, A = PAL1[ch][sty]; return [f(B, A) for n, f in FINAL]
def avail(ch, sty, thr=6):
    t = final_triples(ch, sty); ok = []
    for i, x in enumerate(t):
        ok.append(all(dist(x, t[j]) > thr for j in range(i) if ok[j]))
    return ok
def _main():
    for ch in range(14):
        for sty in range(2):
            a = avail(ch, sty)
            dup = [FINAL[i][0] for i in range(16) if not a[i]]
            mind = min(dist(x, y) for i, x in enumerate(final_triples(ch, sty)) for j, y in enumerate(final_triples(ch, sty)) if i < j)
            print(ch, sty, 'dups', dup, 'min pair dist', mind)
def t_fixed2(B, A, h, s2, v2, s3, v3): return (B[0], from_hsv(h / 360, s2, v2), from_hsv(h / 360, s3, v3))
FINAL[6] = ('분홍', lambda B, A: t_fixed2(B, A, 330, 0.4, 1.0, 0.75, 0.6))
if __name__ == "__main__": _main()
_old_preset = preset
def preset(name_or_idx, ch, sty):
    B, A = PAL1[ch][sty]
    if isinstance(name_or_idx, int): return FINAL[name_or_idx][1](B, A)
    for n, f in FINAL:
        if n == name_or_idx: return f(B, A)
    return _old_preset(name_or_idx, ch, sty)
