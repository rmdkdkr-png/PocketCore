"""1단계 도트 시안 — 게임 화소(1배)에 3색 + 투명으로 그린 이펙트 컷. 색 번호 1 = 밝음(흰 심) · 2 = 가운데 · 3 = 테두리."""
import math

def blank(w, h): return [[0] * w for _ in range(h)]

def put(g, x, y, c, over=False):
    h = len(g); w = len(g[0])
    if 0 <= x < w and 0 <= y < h and (over or g[y][x] == 0 or c < g[y][x]): g[y][x] = c

def line(g, x0, y0, x1, y1, c, over=False):
    x0, y0, x1, y1 = int(round(x0)), int(round(y0)), int(round(x1)), int(round(y1))
    dx, dy = abs(x1 - x0), -abs(y1 - y0); sx = 1 if x0 < x1 else -1; sy = 1 if y0 < y1 else -1; e = dx + dy
    while True:
        put(g, x0, y0, c, over)
        if x0 == x1 and y0 == y1: break
        e2 = 2 * e
        if e2 >= dy: e += dy; x0 += sx
        if e2 <= dx: e += dx; y0 += sy

def ray(g, cx, cy, ang, r0, r1, c):
    line(g, cx + math.cos(ang) * r0, cy - math.sin(ang) * r0, cx + math.cos(ang) * r1, cy - math.sin(ang) * r1, c)

def disc(g, cx, cy, r, c):
    for y in range(int(cy - r - 1), int(cy + r + 2)):
        for x in range(int(cx - r - 1), int(cx + r + 2)):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r + 0.5: put(g, x, y, c)

def ring(g, cx, cy, r, c, gaps=0, seed=0):
    n = max(8, int(2 * math.pi * r * 1.5))
    for i in range(n):
        a = 2 * math.pi * i / n
        if gaps and int((a / (2 * math.pi)) * gaps * 2 + seed) % 2 == 1: continue
        put(g, int(round(cx + math.cos(a) * r)), int(round(cy - math.sin(a) * r)), c)

def outline(g, edge):
    """밝은 색(1·2) 둘레에 테두리 색 한 겹 — 원작 섬광처럼 흰 심 + 색 테두리"""
    h = len(g); w = len(g[0]); out = [row[:] for row in g]
    for y in range(h):
        for x in range(w):
            if g[y][x] != 0: continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                xx, yy = x + dx, y + dy
                if 0 <= xx < w and 0 <= yy < h and g[yy][xx] in (1, 2): out[y][x] = edge; break
    return out

# ── 베기 섬광(약): 십자 → 8갈래 별 → 깨진 고리 + 점 → 점 넷  (17×17, 가운데 8,8) ──
def spark_light():
    C = 8; cels = []
    g = blank(17, 17); disc(g, C, C, 1, 1)
    for a in (0, 90, 180, 270): ray(g, C, C, math.radians(a), 1, 4, 2)
    cels.append(outline(g, 3))
    g = blank(17, 17); disc(g, C, C, 1.5, 1)
    for a in range(0, 360, 45): ray(g, C, C, math.radians(a), 1, 6 if a % 90 == 0 else 4, 2 if a % 90 == 0 else 1)
    cels.append(outline(g, 3))
    g = blank(17, 17)
    for a in range(0, 360, 45): ray(g, C, C, math.radians(a + 22), 5, 7 if a % 90 == 0 else 6, 2)
    ring(g, C, C, 4, 3, gaps=4)
    cels.append(g)
    g = blank(17, 17)
    for a in (30, 120, 210, 300): put(g, int(C + math.cos(math.radians(a)) * 7), int(C - math.sin(math.radians(a)) * 7), 2)
    for a in (75, 255): put(g, int(C + math.cos(math.radians(a)) * 6), int(C - math.sin(math.radians(a)) * 6), 3)
    cels.append(g)
    return cels, (C, C)

# ── 베기 섬광(강): 혜성 줄기(맞은 쪽 = 오른쪽으로 뻗음) + 불티  (40×15, 닿은 자리 12,7) ──
def spark_heavy():
    W, H, X0, Y = 40, 15, 12, 7; cels = []
    def comet(g, x0, length, th, core=True):
        for i in range(length):
            t = i / max(length - 1, 1)
            half = th * (1 - t) ** 0.6
            for dy in range(-int(math.ceil(half)), int(math.ceil(half)) + 1):
                if abs(dy) <= half: put(g, x0 + i, Y + dy, 2)
            if core and t < 0.8: put(g, x0 + i, Y, 1, over=True)
    g = blank(W, H); disc(g, X0, Y, 2.5, 1); comet(g, X0 - 3, 12, 2.5); cels.append(outline(g, 3))          # 닿음 — 짧고 굵게(히트스톱 동안)
    g = blank(W, H); disc(g, X0 + 2, Y, 1.5, 1); comet(g, X0 - 4, 26, 3.0)
    for (x, y) in ((X0 + 6, Y - 5), (X0 + 9, Y + 5), (X0 + 3, Y - 6)): put(g, x, y, 1)
    cels.append(outline(g, 3))                                                                            # 뻗음
    g = blank(W, H)
    for i in range(24):                                                                                   # 두 갈래로 갈라짐
        t = i / 23; off = 1 + t * 3
        put(g, X0 + 2 + i, int(round(Y - off)), 2 if t < 0.7 else 3); put(g, X0 + 2 + i, int(round(Y + off)), 2 if t < 0.7 else 3)
    for (x, y) in ((X0 + 12, Y - 7), (X0 + 17, Y + 6), (X0 + 8, Y + 7)): put(g, x, y, 1); put(g, x - 1, y, 2)
    cels.append(g)
    g = blank(W, H)
    for (x, y) in ((X0 + 16, Y - 7), (X0 + 22, Y + 6), (X0 + 11, Y + 7), (X0 + 26, Y - 2)): put(g, x, y, 2)
    cels.append(g)
    g = blank(W, H)
    for (x, y) in ((X0 + 19, Y - 7), (X0 + 25, Y + 7)): put(g, x, y, 3)
    cels.append(g)
    return cels, (X0, Y)

# ── 반격 고리: 각진(마름모) 고리가 커지며 끊김 — 섬광 위에 겹침 (25×25, 가운데 12,12) ──
def counter_ring():
    C = 12; cels = []
    for r, th, gap in ((5, 2, 0), (8, 2, 0), (11, 1, 1), (12, 1, 2)):
        g = blank(25, 25)
        for k in range(th):
            rr = r - k
            for i in range(-rr, rr + 1):
                pts = ((C + i, C - (rr - abs(i))), (C + i, C + (rr - abs(i))))
                for (x, y) in pts:
                    if gap and (abs(i) + gap) % 3 == 0: continue
                    put(g, x, y, 2 if k == 0 else 3)
        cels.append(g)
    return cels, (C, C)

# ── 칼 부딪힘: 큰 «깡» 별(긴 가시 넷) + 포물선 불티 (33×25, 가운데 16,12) ──
def clash():
    C, Cy = 16, 12; cels = []
    g = blank(33, 25); disc(g, C, Cy, 2, 1)
    for a in (45, 135, 225, 315): ray(g, C, Cy, math.radians(a), 2, 9, 1)
    for a in (0, 90, 180, 270): ray(g, C, Cy, math.radians(a), 2, 5, 2)
    cels.append(outline(g, 3))
    for k in (1, 2, 3):
        g = blank(33, 25)
        if k == 1:
            disc(g, C, Cy, 1, 1)
            for a in (45, 135, 225, 315): ray(g, C, Cy, math.radians(a), 3, 11, 2)
        for s in range(6):
            side = -1 if s < 3 else 1; vx = side * (3 + (s % 3) * 2.2); vy = -4.5 + (s % 3) * 1.5
            t = k * 1.2; x = C + vx * t; y = Cy + vy * t + 1.2 * t * t
            put(g, int(round(x)), int(round(y)), 1 if k < 3 else 2); put(g, int(round(x - side)), int(round(y - vy / 4)), 2 if k < 3 else 3)
        cels.append(g if k > 1 else outline(g, 3))
    return cels, (C, Cy)

# ── 넘어짐 먼지: 발밑 좌우 흙먼지 셋, 바둑판으로 사라짐 (41×13, 발 20,12) ──
def dust():
    W, H, C, B = 41, 13, 20, 12; cels = []
    for k in range(4):
        g = blank(W, H)
        for side in (-1, 1):
            for j, (dx, r) in enumerate(((5, 3.0), (10, 2.4), (14, 1.6))):
                x = C + side * (dx + k * (2 + j)); y = B - r - k * 0.6 + 0.5; rr = r + k * 0.5
                for yy in range(int(y - rr - 1), int(y + rr + 2)):
                    for xx in range(int(x - rr - 1), int(x + rr + 2)):
                        d = (xx - x) ** 2 + (yy - y) ** 2
                        if d > rr * rr: continue
                        if k >= 2 and (xx + yy + k) % 2: continue          # 바둑판으로 흩어짐
                        put(g, xx, yy, 1 if (d < rr * rr * 0.35 and k < 2) else 2 if d < rr * rr * 0.75 else 3)
        cels.append(g)
    return cels, (C, B)
