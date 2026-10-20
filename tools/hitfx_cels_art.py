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

# ── 베기 섬광(강): 맞은 쪽으로 뻗는 짧은 혜성 — 192: 작게(40×15 → 26×11) + 히트스톱 동안에도 뻗어 감(컷 길이 1·1·2·2·2·2) ──
#    유저 10-21 「강베기 이펙트가 너무 커서 … 정지화면 뚝이라 애니메이션으로 · 크기가 작게」
def spark_heavy():
    """센 맞음(강베기 등): 맞은 쪽으로 뻗는 짧은 혜성 — 24×11, 닿은 자리 7,5. 히트스톱 동안에도 뻗어 감"""
    W, H, X0, Y = 26, 11, 7, 5
    cels, durs = [], []
    def comet(g, x0, length, th):
        for i in range(length):
            t = i / max(length - 1, 1)
            half = th * (1 - t) ** 0.6
            for dy in range(-int(math.ceil(half)), int(math.ceil(half)) + 1):
                if abs(dy) <= half: put(g, x0 + i, Y + dy, 2)
            if t < 0.75: put(g, x0 + i, Y, 1, over=True)
    g = blank(W, H); disc(g, X0, Y, 1.6, 1); comet(g, X0 - 2, 6, 1.6); cels.append(outline(g, 3)); durs.append(1)
    g = blank(W, H); disc(g, X0 + 1, Y, 1.4, 1); comet(g, X0 - 2, 12, 2.0); cels.append(outline(g, 3)); durs.append(1)
    g = blank(W, H); disc(g, X0 + 2, Y, 1.0, 1); comet(g, X0 - 2, 17, 2.0)
    for (x, y) in ((X0 + 5, Y - 4), (X0 + 7, Y + 4)): put(g, x, y, 1)
    cels.append(outline(g, 3)); durs.append(2)
    g = blank(W, H)
    for i in range(16):
        t = i / 15; off = 1 + t * 2.2
        put(g, X0 + 1 + i, int(round(Y - off)), 2 if t < 0.6 else 3); put(g, X0 + 1 + i, int(round(Y + off)), 2 if t < 0.6 else 3)
    for (x, y) in ((X0 + 9, Y - 5), (X0 + 12, Y + 5)): put(g, x, y, 1); put(g, x - 1, y, 2)
    cels.append(g); durs.append(2)
    g = blank(W, H)
    for (x, y) in ((X0 + 12, Y - 5), (X0 + 16, Y + 5), (X0 + 18, Y - 1)): put(g, x, y, 2)
    cels.append(g); durs.append(2)
    g = blank(W, H)
    for (x, y) in ((X0 + 15, Y - 5), (X0 + 19, Y + 5)): put(g, x, y, 3)
    cels.append(g); durs.append(2)
    return cels, (X0, Y), durs

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

# ── 2단계(185) ──────────────────────────────────────────────────────────────
# 가드 섬광: 때린 쪽(왼쪽 = 기본, 반대면 좌우 뒤집음)으로 볼록한 푸른 반달 방패 (17×21, 닿은 자리 8,10)
def guard_arc():
    W, H, C, Cy = 17, 21, 12, 10; cels = []
    for k, (r, th, br) in enumerate(((6, 2, 0), (8, 2, 1), (9, 1, 2))):
        g = blank(W, H)
        for i in range(-60, 61, 3):
            a = math.radians(180 + i)
            for t in range(th):
                rr = r - t
                x = int(round(C + math.cos(a) * rr)); y = int(round(Cy - math.sin(a) * rr))
                if br == 2 and (i // 9) % 2: continue
                if br == 1 and abs(i) > 50 and (i // 6) % 2: continue
                put(g, x, y, 1 if (t == 0 and k == 0 and abs(i) < 35) else 2)
        cels.append(outline(g, 3) if k < 2 else g)
    return cels, (C - 4, Cy)

# 막타 일섬: 맞은 몸을 위에서 아래로 가로지르는 붓 사선(때린 쪽 위 → 맞은 쪽 아래), 흰 심 + 빨강 + 검붉은 테 (49×37, 가운데 24,18)
def ko_slash():
    W, H, C, Cy = 49, 37, 24, 18; cels = []
    def stroke(g, L, th, x0=-1.0, gap=None):
        ang = math.atan2(-14, 20)                      # 오른쪽 아래로
        for i in range(int(L * 2) + 1):
            t = i / (L * 2)
            if gap and any(a <= t <= b for a, b in gap): continue
            half = th * math.sin(math.pi * min(max(t, 0.02), 0.98)) ** 0.7
            cx = C + (t - 0.5) * L * math.cos(ang) * 2 * 0.5 * 2 * 0.5 * 2
            cy = Cy - (t - 0.5) * L * math.sin(ang) * 2 * 0.5 * 2 * 0.5 * 2
            for d in range(-int(math.ceil(half)), int(math.ceil(half)) + 1):
                if abs(d) > half: continue
                nx, ny = -math.sin(ang) * d, -math.cos(ang) * d
                put(g, int(round(cx + nx)), int(round(cy + ny)), 1 if abs(d) <= 0.6 else 2)
    g = blank(W, H); stroke(g, 26, 2.6); cels.append(outline(g, 3))
    g = blank(W, H); stroke(g, 30, 2.2); cels.append(outline(g, 3))
    g = blank(W, H); stroke(g, 30, 1.6, gap=[(0.30, 0.38), (0.62, 0.70)]); cels.append(outline(g, 3))
    g = blank(W, H); stroke(g, 30, 1.0, gap=[(0.18, 0.34), (0.46, 0.56), (0.70, 0.86)])
    for (x, y) in ((C + 14, Cy - 12), (C - 16, Cy + 11)): put(g, x, y, 2)
    cels.append(g)
    g = blank(W, H)
    for (x, y) in ((C + 17, Cy - 14), (C - 19, Cy + 13), (C + 3, Cy - 3)): put(g, x, y, 3)
    cels.append(g)
    return cels, (C, Cy)

# ── 192 앞 강베기 일섬: 맞은 몸 가운데를 가르는 짧은 초승달 — 그어지고(컷 1·1) 굵어졌다 가늘어지며 두 줄로 벌어지고 불티로 사라짐(31×23, 닿은 자리 15,11) ──
#    191 까지는 막타 일섬(49×37)을 히트스톱 내내 첫 컷으로 세워 둬서 크고 멈춰 보였다(유저 「정지화면 뚝」)
def stroke(g, P0, P1, bulge, th, t0, t1, C, Cy, gap=None, shift=0.0, core_th=0.6):
    """P0→P1 사이 볼록한 붓 사선의 t0..t1 구간. 두께 th·sin(πt)^0.75, 법선 쪽으로 shift"""
    (x0, y0), (x1, y1) = P0, P1
    L = math.hypot(x1 - x0, y1 - y0)
    ux, uy = (x1 - x0) / L, (y1 - y0) / L
    nx, ny = uy, -ux                                   # 법선(오른쪽 위로)
    n = int(L * 3) + 1
    for i in range(n + 1):
        t = i / n
        if t < t0 or t > t1: continue
        if gap and any(a <= t <= b for a, b in gap): continue
        half = th * math.sin(math.pi * min(max(t, 0.03), 0.97)) ** 0.75
        b = bulge * math.sin(math.pi * t) + shift
        cx = C + x0 + (x1 - x0) * t + nx * b
        cy = Cy + y0 + (y1 - y0) * t + ny * b
        r = max(half, 0.35)
        for k in range(-int(math.ceil(r)) - 1, int(math.ceil(r)) + 2):
            if abs(k) > r: continue
            put(g, int(round(cx + nx * k)), int(round(cy + ny * k)), 1 if abs(k) <= core_th and half > 0.9 else 2)

def front_slash():
    """앞 강베기: 맞은 몸 가운데를 가르는 짧은 초승달 베기 — 그어지고(1·1·1) 벌어지며 사라짐 + 불티. 31×23, 닿은 자리 15,11"""
    W, H, C, Cy = 31, 23, 15, 11
    P0, P1 = (-11, -8), (11, 8)
    cels, durs = [], []
    def sparks(g, k, col):
        # 닿은 자리에서 법선 양쪽·앞으로 튀는 불티 6
        base = [(-0.62, -0.78, 1.0), (0.55, 0.83, 1.0), (0.95, -0.3, 1.3), (-0.9, 0.42, 0.8), (0.3, -0.95, 0.7), (-0.25, 0.97, 0.9)]
        for (vx, vy, sp) in base:
            d = 3 + k * 2.6 * sp
            x, y = C + vx * d, Cy + vy * d
            put(g, int(round(x)), int(round(y)), col)
            if col == 1: put(g, int(round(x - vx)), int(round(y - vy)), 2)
    # 0: 닿음 — 가운데 섬광 + 선이 35% 그어짐
    g = blank(W, H); disc(g, C, Cy, 1.2, 1)
    for a in (0, 90, 180, 270): ray(g, C, Cy, math.radians(a + 35), 1, 3, 2)
    stroke(g, P0, P1, 2.2, 1.0, 0.0, 0.40, C, Cy); cels.append(outline(g, 3)); durs.append(1)
    # 1: 75% · 섬광 커짐
    g = blank(W, H); disc(g, C, Cy, 1.6, 1)
    for a in (0, 90, 180, 270): ray(g, C, Cy, math.radians(a + 35), 2, 4, 2)
    stroke(g, P0, P1, 2.2, 1.8, 0.0, 0.78, C, Cy); cels.append(outline(g, 3)); durs.append(1)
    # 2: 다 그어짐(가장 굵게)
    g = blank(W, H); stroke(g, P0, P1, 2.2, 2.0, 0.0, 1.0, C, Cy); sparks(g, 0, 1); cels.append(outline(g, 3)); durs.append(2)
    # 3: 가늘어짐 · 불티 퍼짐
    g = blank(W, H); stroke(g, P0, P1, 2.2, 1.3, 0.0, 1.0, C, Cy); sparks(g, 1, 1); cels.append(outline(g, 3)); durs.append(2)
    # 4: 두 가는 줄로 벌어짐(베인 자리) — 테두리 없이 1px
    g = blank(W, H)
    stroke(g, P0, P1, 2.2, 0.3, 0.06, 0.94, C, Cy, shift=-1.5, gap=[(0.46, 0.54)])
    stroke(g, P0, P1, 2.2, 0.3, 0.06, 0.94, C, Cy, shift=1.5, gap=[(0.30, 0.37), (0.63, 0.70)])
    sparks(g, 2, 1); cels.append(g); durs.append(2)
    # 5: 끊어진 조각(어두운 색)
    g = blank(W, H)
    stroke(g, P0, P1, 2.2, 0.3, 0.12, 0.88, C, Cy, shift=-2.4, gap=[(0.22, 0.42), (0.52, 0.76)])
    stroke(g, P0, P1, 2.2, 0.3, 0.12, 0.88, C, Cy, shift=2.4, gap=[(0.30, 0.52), (0.62, 0.82)])
    for y in range(H):
        for x in range(W):
            if g[y][x]: g[y][x] = 3
    sparks(g, 3, 2)
    cels.append(g); durs.append(2)
    # 6: 남은 점
    g = blank(W, H); sparks(g, 4, 3)
    for (x, y) in ((C - 9, Cy - 9), (C + 10, Cy + 6)): put(g, x, y, 3)
    cels.append(g); durs.append(2)
    return cels, (C, Cy), durs
