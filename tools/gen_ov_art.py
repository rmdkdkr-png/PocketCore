# -*- coding: utf-8 -*-
"""157 덧판 그림(우리가 찍은 픽셀) → app/src/main/java/com/dudu/pocketcore/OvArt.java

  python3 -I tools/gen_ov_art.py <저장소 루트> [--out <파일>]

출처는 모두 우리 것: 기획 목업 handoff/ss2_next/157_인게임메뉴_기획/시안/mock_final.py 의 그림 상수·그리기 함수를 그대로 옮겼고
(단도·사슬·왕관·알·삼각·화살·문장·별·낙관·붓 획·깃발·칼집 마개·테), 낙관 붓글자는 앱 assets/fonts/ink_b.ttf(고운바탕 Bold, SIL OFL 1.1)를,
꼬리표 도장 글자는 assets/fonts/galmuri7.ttf(갈무리7, SIL OFL 1.1)를 이 생성기가 1비트로 굽는다(목업 brush_glyph·mask 와 같은 방법).
롬·게임 화면·상태 파일은 쓰지 않는다. 난수는 목업과 같은 고정 씨앗(random.Random) — 두 번 돌려도 같은 파일(결정적).
그림마다 칠 0..3(0 = 투명). 계약서 docs/157_contract.md §6 의 이름·크기·칠의 뜻을 지킨다(24개) + 덧붙인 그림(꼬리표 도장 5 · 두 글자 낙관 2 · 막대 9 · 사선 1).
"""
import os
import random
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont


# ---------------------------------------------------------------- 목업 그림 상수(mock_final.py 그대로)
KATANA = ["........", "2.......", "22......", "22111111", "22......", "2.......", "........"]
CHAINB = ["........", ".11..11.", "1..11..1", "1..11..1", ".11..11.", "........"]
CROWN = ["1..1..1", "1.121.1", "1122211", "2222222", "2121212"]
PIPW = [".1111.", "111111", "111111", "111111", "111111", ".1111."]
PIPL = [".2222.", "2....2", "2....2", "2....2", "2....2", ".2222."]
TRI_UP = ["..1..", ".111.", "11111"]
TRI_DN = ["11111", ".111.", "..1.."]
ARROW_L = ["...1", "..11", ".111", "1111", ".111", "..11", "...1"]
ARROW_R = [r[::-1] for r in ARROW_L]
CREST = ["...33...", "..3113..", ".311113.", "31122113", "31122113", ".311113.", "..3113..", "...33..."]
STAR5 = ["..#..", ".###.", "#####", ".###.", ".#.#."]


class Canvas:
    """목업 Frame 의 칠 판만(idx) — 같은 px/fill 규칙(밖은 자름)"""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.idx = [[0] * w for _ in range(h)]

    def px(self, x, y, i):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.idx[y][x] = i

    def fill(self, x0, y0, x1, y1, i):
        for y in range(max(0, y0), min(self.h, y1 + 1)):
            for x in range(max(0, x0), min(self.w, x1 + 1)):
                self.idx[y][x] = i

    def rows(self):
        return [''.join(str(v) for v in r) for r in self.idx]


def from_rows(rows, mp=None):
    out = []
    for r in rows:
        s = ''
        for ch in r:
            if mp is not None:
                s += str(mp.get(ch, 0))
            else:
                s += ch if ch in '123' else '0'
        out.append(s)
    return out


# ---------------------------------------------------------------- 글꼴 굽기(목업 brush_glyph · mask 와 같은 방법)
class Fonts:
    def __init__(self, repo):
        fd = os.path.join(repo, 'app/src/main/assets/fonts')
        self.ink = ImageFont.truetype(os.path.join(fd, 'ink_b.ttf'), 120)
        self.g7 = ImageFont.truetype(os.path.join(fd, 'galmuri7.ttf'), 8)

    def brush_glyph(self, ch, size, grow=5, thr=100):
        im = Image.new('L', (170, 170), 0)
        d = ImageDraw.Draw(im)
        bb = d.textbbox((0, 0), ch, font=self.ink)
        d.text(((170 - (bb[2] - bb[0])) // 2 - bb[0], (170 - (bb[3] - bb[1])) // 2 - bb[1]), ch, font=self.ink, fill=255)
        if grow >= 3:
            im = im.filter(ImageFilter.MaxFilter(grow))
        bb = im.getbbox()
        side = max(bb[2] - bb[0], bb[3] - bb[1])
        sq = Image.new('L', (side, side), 0)
        sq.paste(im.crop(bb), ((side - (bb[2] - bb[0])) // 2, (side - (bb[3] - bb[1])) // 2))
        sm = sq.resize((size, size), Image.LANCZOS)
        return [[1 if sm.getpixel((x, y)) > thr else 0 for x in range(size)] for y in range(size)]

    def mask7(self, s):
        im = Image.new('L', (len(s) * 14 + 8, 16), 0)
        d = ImageDraw.Draw(im)
        d.fontmode = '1'
        d.text((0, -1), s, font=self.g7, fill=255)
        bb = im.getbbox()
        wdt = bb[2] if bb else 0
        return [[1 if im.getpixel((x, y)) > 127 else 0 for x in range(wdt)] for y in range(14)]


# ---------------------------------------------------------------- 그림 함수(목업 seal·seal16·seal8·brush_stroke·banner·sheath 를 칸 0,0 기준으로)
def seal_frame(cv, x, y, nw, nh, seed):
    """목업 seal() 의 틀: 인주(2) · 아래·오른 테(3) · 테 빠짐(0, 고정 씨앗) · 네 귀(0). nw == nh 이면 목업과 같은 난수 순서"""
    rnd = random.Random(seed)
    cv.fill(x, y, x + nw - 1, y + nh - 1, 2)
    if nw == nh:
        n = nw
        for k in range(n):
            for (xx, yy) in ((x + k, y + n - 1), (x + n - 1, y + k)):
                cv.px(xx, yy, 3)
        for _ in range(n // 2):
            side = rnd.randint(0, 3)
            k = rnd.randint(1, n - 2)
            cv.px(*((x + k, y), (x + k, y + n - 1), (x, y + k), (x + n - 1, y + k))[side], 0)
    else:
        for k in range(nw):
            cv.px(x + k, y + nh - 1, 3)
        for k in range(nh):
            cv.px(x + nw - 1, y + k, 3)
        for _ in range((nw + nh) // 4):
            side = rnd.randint(0, 3)
            if side < 2:
                k = rnd.randint(1, nw - 2)
                cv.px(x + k, (y, y + nh - 1)[side], 0)
            else:
                k = rnd.randint(1, nh - 2)
                cv.px((x, x + nw - 1)[side - 2], y + k, 0)
    for (cx, cy) in ((x, y), (x + nw - 1, y), (x, y + nh - 1), (x + nw - 1, y + nh - 1)):
        cv.px(cx, cy, 0)


def seal24(fonts, ch):
    n = 24
    cv = Canvas(n, n)
    seal_frame(cv, 0, 0, n, n, ord(ch) * 7 + n)
    g = fonts.brush_glyph(ch, n - 6, grow=5 if ch in '승' else 3)
    for yy, row in enumerate(g):
        for xx, v in enumerate(row):
            if v:
                cv.px(3 + xx, 3 + yy, 1)
    return cv.rows()


def seal_two(fonts, text):
    """두 글자 낙관 32×24(«기권»·«무효») — 우리 그림: seal 틀을 가로로 늘리고 붓글자 둘(13px)을 나란히"""
    nw, nh, gs = 32, 24, 13
    cv = Canvas(nw, nh)
    seal_frame(cv, 0, 0, nw, nh, sum(ord(c) for c in text) * 7 + nw)
    for k, ch in enumerate(text):
        g = fonts.brush_glyph(ch, gs, grow=3)
        ox, oy = 3 + k * gs, (nh - gs) // 2
        for yy, row in enumerate(g):
            for xx, v in enumerate(row):
                if v:
                    cv.px(ox + xx, oy + yy, 1)
    return cv.rows()


def seal16_frame():
    cv = Canvas(16, 16)
    x = y = 0
    cv.fill(x, y, x + 15, y + 15, 2)
    cv.fill(x + 1, y + 15, x + 15, y + 15, 3)
    cv.fill(x + 15, y + 1, x + 15, y + 15, 3)
    for (cx, cy) in ((x, y), (x + 15, y), (x, y + 15), (x + 15, y + 15), (x + 6, y), (x, y + 9), (x + 15, y + 5), (x + 11, y + 15)):
        cv.px(cx, cy, 0)
    return cv.rows()


def seal8(fonts, ch):
    cv = Canvas(8, 8)
    cv.fill(0, 0, 7, 7, 2)
    for (cx, cy) in ((0, 0), (7, 0), (0, 7), (7, 7)):
        cv.px(cx, cy, 0)
    if ch:
        m = fonts.mask7(ch)
        for yy, row in enumerate(m[:7]):
            for xx, v in enumerate(row[:7]):
                if v:
                    cv.px(xx, yy, 1)
    return cv.rows()


def brush(length, seed=11, head=3):
    cv = Canvas(length, 3)
    rnd = random.Random(seed)
    x0, y = 0, 1
    n = length - 1
    for i in range(n + 1):
        t = i / max(1, n)
        th = max(1, round(head * (1 - t) ** 0.7 + 0.4))
        for k in range(th):
            if t > 0.6 and rnd.random() < (t - 0.55) * 1.1:
                continue
            cv.px(x0 + i, y - th // 2 + k, 2)
        if th >= 2:
            cv.px(x0 + i, y - th // 2, 1)
    return cv.rows()


def banner():
    """목업 banner(fr, c=0, r0=0, r1=4, …) 의 깃대(칸 0)와 천(칸 1–4) — 글자는 뺌"""
    cv = Canvas(40, 40)
    c, r0, r1 = 0, 0, 4
    cv.fill(c * 8 + 5, r0 * 8 + 2, c * 8 + 6, r1 * 8 + 7, 2)
    cv.fill(c * 8 + 5, r0 * 8 + 2, c * 8 + 5, r1 * 8 + 7, 1)
    cv.fill(c * 8 + 4, r0 * 8, c * 8 + 7, r0 * 8 + 1, 1)
    cv.fill(c * 8 + 7, r0 * 8 + 3, c * 8 + 7, r0 * 8 + 3, 1)
    x0 = (c + 1) * 8
    x1 = x0 + 31
    yb = r1 * 8 + 7
    cv.fill(x0, r0 * 8 + 1, x1, yb, 3)
    cv.fill(x0, r0 * 8 + 1, x1, r0 * 8 + 2, 2)
    for x in range(x0, x1 + 1):
        d = abs((x - x0) - 15.5)
        cut = int(6 - d / 2.6)
        for y in range(yb - max(0, cut), yb + 1):
            cv.px(x, y, 0)
    rows = cv.rows()
    return [r[8:40] for r in rows], [r[0:8] for r in rows]


def sheath_cap_top():
    cv = Canvas(7, 3)
    cv.fill(0, 0, 6, 2, 2)
    cv.fill(1, 0, 5, 0, 1)
    return cv.rows()


def sheath_cap_bot():
    cv = Canvas(7, 3)
    cv.fill(0, 0, 6, 2, 2)
    return cv.rows()


def sheath_edge():
    return ['2' + ('1' if (y // 4) % 4 else '2') for y in range(16)]


def crest32():
    """승급 문장 32×32 — 작은 CREST(8×8 마름모: 3 테 · 1 몸 · 2 속)를 32로 다시 그림(우리 그림):
    1px 바깥 테(3) · 몸(1)을 십자 홈(3)으로 네 면 · 속 테(3) · 속(2) · 속 왼위 빛(1)"""
    cv = Canvas(32, 32)
    for y in range(32):
        for x in range(32):
            dx, dy = x - 15.5, y - 15.5
            d = abs(dx) + abs(dy)
            if d > 16:
                continue
            if d > 15:
                v = 3
            elif d > 9:
                v = 3 if (abs(dx) < 1 or abs(dy) < 1) else 1
            elif d > 8:
                v = 3
            else:
                v = 1 if dx + dy < -4 else 2
            cv.px(x, y, v)
    return cv.rows()


def gauge_pieces():
    """LP 막대 조각 8×6 — 목업 gauge() 의 테(2)·찬 몫(윗줄 1 + 2)·잃은 꼬리(1)·빈 몫(3 = 옻칠 몸통)을 칸 단위로(왼끝·가운데·오른끝 × 빔·참·꼬리)"""
    out = []
    for state in ('EMPTY', 'FULL', 'TAIL'):
        for pos in ('L', 'M', 'R'):
            cv = Canvas(8, 6)
            for x in range(8):
                cv.px(x, 0, 2)
                cv.px(x, 5, 2)
                for yy in range(1, 5):
                    if state == 'EMPTY':
                        v = 3
                    elif state == 'FULL':
                        v = 1 if yy == 1 else 2
                    else:
                        v = 1
                    cv.px(x, yy, v)
            if pos == 'L':
                for yy in range(6):
                    cv.px(0, yy, 2)
            if pos == 'R':
                for yy in range(6):
                    cv.px(7, yy, 2)
            out.append(('GAUGE_%s_%s' % (pos, state), cv.rows()))
    return out


def slash():
    """사선(45°) 한 칸 — 칼 휘두름·호명 띠 끝·일섬 장식용, 뒤집기로 네 방향: 오른아래 삼각 몸(1) · 사선 테(2)"""
    cv = Canvas(8, 8)
    for y in range(8):
        for x in range(8):
            s = x + y
            cv.px(x, y, 2 if s == 7 else (1 if s > 7 else 0))
    return cv.rows()


# ---------------------------------------------------------------- 목록(번호 = 이 순서. 앞 24개는 계약서 §6 표 순서)
def build(repo):
    f = Fonts(repo)
    cloth, pole = banner()
    arts = [
        ('KATANA', from_rows(KATANA), '단도 커서: 1 칼날 · 2 손잡이'),
        ('CHAIN', from_rows(CHAINB), '라이벌 사슬: 1'),
        ('CROWN', from_rows(CROWN), '1위 왕관: 1 · 2'),
        ('PIP_WIN', from_rows(PIPW), '최근 판 찬 알: 1'),
        ('PIP_LOSS', from_rows(PIPL), '빈 알: 2'),
        ('TRI_UP', from_rows(TRI_UP), '▲: 1'),
        ('TRI_DN', from_rows(TRI_DN), '▼: 1'),
        ('ARROW_L', from_rows(ARROW_L), '◀: 1'),
        ('ARROW_R', from_rows(ARROW_R), '▶: 1'),
        ('CREST', from_rows(CREST), '티어 문장 작은 것: 1 · 2 · 3'),
        ('STAR5', from_rows(STAR5, {'#': 1}), '별: 1'),
        ('SEAL24_SEUNG', seal24(f, '승'), '낙관 «승»: 1 글자 · 2 인주 · 3 테 · 0 빠진 곳'),
        ('SEAL24_PAE', seal24(f, '패'), '낙관 «패»'),
        ('SEAL24_MU', seal24(f, '무'), '낙관 «무»(무승부 · 무효 — 팔레트로 가름)'),
        ('SEAL16_FRAME', seal16_frame(), '서랍 제목 낙관 틀: 2 인주 · 3 테 · 0 — 글자는 g11b 칠 1'),
        ('SEAL8_FRAME', seal8(f, ''), '꼬리표 도장 틀: 2 인주(네 귀 0) — 글자는 g7 칠 1'),
        ('CREST32', crest32(), '승급 문장: 1 · 2 · 3'),
        ('BRUSH152', brush(152), '붓 한 획(머리 굵고 꼬리 마름): 2 먹 · 1 윗빛'),
        ('BRUSH72', brush(72), '붓 한 획 72'),
        ('BANNER_CLOTH', cloth, '노보리 천(꼬리 잘림 0): 3 바탕 · 2 윗띠'),
        ('BANNER_POLE', pole, '깃대: 1 빛 · 2 나무'),
        ('SHEATH_CAP_TOP', sheath_cap_top(), '칼집 위 금 마개: 1 · 2 (목업은 그 위에 금 윗줄 2 를 그어 윗줄 1 을 덮음)'),
        ('SHEATH_CAP_BOT', sheath_cap_bot(), '아래 마개: 2'),
        ('SHEATH_EDGE', sheath_edge(), '칼집 왼쪽 금 테(16줄마다 되풀이, y % 16 기준): 1 · 2'),
        ('SEAL8_YEON', seal8(f, '연'), '꼬리표 도장 «연»(연승): 1 글자 · 2 인주 · 0 귀'),
        ('SEAL8_SEOL', seal8(f, '설'), '꼬리표 도장 «설»(설욕)'),
        ('SEAL8_CHEOT', seal8(f, '첫'), '꼬리표 도장 «첫»(오늘 첫 승)'),
        ('SEAL8_WI', seal8(f, '위'), '꼬리표 도장 «위»(강등 위기)'),
        ('SEAL8_RA', seal8(f, '라'), '꼬리표 도장 «라»(라이벌)'),
        ('SEAL32_GIGWON', seal_two(f, '기권'), '두 글자 낙관 «기권» 32×24: 1 글자 · 2 인주 · 3 테 · 0 빠진 곳'),
        ('SEAL32_MUHYO', seal_two(f, '무효'), '두 글자 낙관 «무효» 32×24'),
    ]
    for name, rows in gauge_pieces():
        arts.append((name, rows, 'LP 막대 조각 8×6: 2 테 · 찬 몫 1 윗빛 + 2 · 꼬리 1 · 빈 몫 3'))
    arts.append(('SLASH', slash(), '사선 한 칸: 1 몸 · 2 테(뒤집기로 네 방향)'))
    for name, rows, _ in arts:
        w = len(rows[0])
        assert all(len(r) == w for r in rows), name
        assert all(c in '0123' for r in rows for c in r), name
    return arts


def java(arts):
    L = []
    L.append('package com.dudu.pocketcore;')
    L.append('')
    L.append('/**')
    L.append(' * 157 덧판 그림(우리가 찍은 픽셀) — tools/gen_ov_art.py 가 만든 파일이다. 손으로 고치지 않는다(생성기를 고치고 다시 돌림).')
    L.append(' * 계약서 docs/157_contract.md §6. 출처: handoff/ss2_next/157_인게임메뉴_기획/시안/mock_final.py 의 그림 상수·그리기 함수(우리 그림).')
    L.append(' * 낙관 붓글자 = 앱 assets/fonts/ink_b.ttf(고운바탕 Bold, SIL OFL 1.1) · 꼬리표 도장 글자 = assets/fonts/galmuri7.ttf(갈무리7, SIL OFL 1.1) 를')
    L.append(' * 생성기가 1비트로 구운 것. 롬 그림·게임 화면은 쓰지 않는다.')
    L.append(' * 그림마다 칠 0..3(0 = 투명). 쓰는 쪽은 TileCanvas.art(id, x, y, ci1, ci2, ci3, hflip) 로 칠 1·2·3 을 원하는 칠로 옮겨 그린다.')
    L.append(' * 앞 24개(KATANA..SHEATH_EDGE)의 이름·크기·칠의 뜻은 계약서 표대로, 그 뒤는 덧붙인 그림.')
    L.append(' */')
    L.append('final class OvArt {')
    L.append('    private OvArt() { }')
    L.append('')
    for i, (name, rows, note) in enumerate(arts):
        L.append('    static final int %s = %d;%s// %d×%d %s' % (name, i, ' ' * max(1, 22 - len(name) - len(str(i))), len(rows[0]), len(rows), note))
    L.append('    static final int COUNT = %d;' % len(arts))
    L.append('')
    L.append('    private static final String[] NAME = {')
    names = ['"%s"' % a[0] for a in arts]
    for k in range(0, len(names), 8):
        L.append('        ' + ', '.join(names[k:k + 8]) + ',')
    L.append('    };')
    L.append('    private static final int[] W = { %s };' % ', '.join(str(len(a[1][0])) for a in arts))
    L.append('    private static final int[] H = { %s };' % ', '.join(str(len(a[1])) for a in arts))
    L.append('    /** 칠 문자열 — 그림마다 w×h 자(줄 먼저), \'0\'..\'3\' */')
    L.append('    private static final String[] D = {')
    for name, rows, _ in arts:
        data = ''.join(rows)
        w = len(rows[0])
        per = max(1, 96 // w) * w if w <= 96 else w
        chunks = [data[k:k + per] for k in range(0, len(data), per)]
        L.append('        /* %s */' % name)
        for j, ch in enumerate(chunks):
            L.append('        "%s"%s' % (ch, ' +' if j < len(chunks) - 1 else ','))
    L.append('    };')
    L.append('')
    L.append('    static int w(int id) { return W[id]; }')
    L.append('    static int h(int id) { return H[id]; }')
    L.append('    /** 칠 0..3 — 범위 밖 0 */')
    L.append('    static int px(int id, int x, int y) {')
    L.append('        if (id < 0 || id >= COUNT || x < 0 || y < 0 || x >= W[id] || y >= H[id]) return 0;')
    L.append("        return D[id].charAt(y * W[id] + x) - '0';")
    L.append('    }')
    L.append('    static String name(int id) { return NAME[id]; }')
    L.append('}')
    return '\n'.join(L) + '\n'


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    repo = sys.argv[1]
    out = os.path.join(repo, 'app/src/main/java/com/dudu/pocketcore/OvArt.java')
    if '--out' in sys.argv:
        out = sys.argv[sys.argv.index('--out') + 1]
    arts = build(repo)
    src = java(arts)
    with open(out, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(src)
    print('OvArt.java: %d 그림, %d 바이트 → %s' % (len(arts), len(src.encode('utf-8')), out))


if __name__ == '__main__':
    main()
