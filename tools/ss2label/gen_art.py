"""195 타격 알림 라벨 도트 셋(시안 A·B·D) → app/src/main/cpp/ss2label_art.h
  python3 tools/ss2label/gen_art.py > app/src/main/cpp/ss2label_art.h
시안 그림 코드(draft_a·b·d.draw)를 그대로 돌리면서 paste(…, up(그림), x, y) 를 가로채 «프레임마다 어느 그림을 어디에»만 적는다
(늘리기·찌그러뜨리기·바둑판 지우기·잘라 펴기는 이미 된 1배 그림으로 — 코어는 붙이기만). 같은 그림은 한 번만.
그림은 팔레트(스타일마다 ≤ 255색, 0 = 투명) 번호를 PackBits 로 줄여 담는다.
C(먹 베기 띠)는 4배 그림이라 따로(gen_ink.py)."""
import sys, os, hashlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from PIL import Image
import common, draft_a, draft_b, draft_d

STYLES = [('PIXEL', draft_d), ('BRUSH', draft_a), ('PLAQUE', draft_b)]   # 코어 스타일 번호 0·1·2 (3 = 먹 띠)
KINDS = ['first', 'counter', 'punish', 'reversal']                     # CO_FIRST … CO_REVERSAL 차례
T_MAX = 59                                                             # t 0..58
Y0 = 34

class Marker:
    def __init__(self, im): self.im = im

def trace(mod, kind, side, t):
    ops = []
    def up(im, s=common.S): return Marker(im.copy())
    def paste(base, over, x, y):
        assert isinstance(over, Marker), 'pixel styles only'
        assert x % common.S == 0 and y % common.S == 0
        ops.append((over.im, x // common.S, y // common.S))
    mod.up, mod.paste = up, paste
    h = mod.draw(None, kind, t, side, Y0)
    return ops, h

def pack(idx):
    """PackBits: b < 128 = (b + 1) 번 다음 바이트 · b ≥ 128 = (b − 127) 바이트 그대로"""
    out = []; i = 0; n = len(idx)
    while i < n:
        j = i
        while j + 1 < n and idx[j + 1] == idx[i] and j - i < 127: j += 1
        if j > i:
            out += [j - i, idx[i]]; i = j + 1
        else:
            k = i; lit = []
            while k < n and len(lit) < 128 and not (k + 1 < n and idx[k + 1] == idx[k]):
                lit.append(idx[k]); k += 1
            if not lit: lit = [idx[k]]; k += 1
            out += [127 + len(lit)] + lit; i = k
    return out

def unpack(b, n):
    out = []; i = 0
    while len(out) < n:
        c = b[i]
        if c < 128: out += [b[i + 1]] * (c + 1); i += 2
        else: out += list(b[i + 1:i + 1 + c - 127]); i += 1 + c - 127
    return out[:n]

def main():
    sprites, sprite_key = [], {}            # (스타일, rgba bytes) → id
    pal = [[(0, 0, 0, 0)] for _ in STYLES]
    frames = []                             # [style][kind][side][t] = [(spr, gx, dy)]
    rowh = []; kh = []
    for si, (nm, mod) in enumerate(STYLES):
        fs_k = []; hmax = 0; kh.append([])
        for kind in KINDS:
            fs_s = []
            for side in ('L', 'R'):
                fs_t = []
                for t in range(T_MAX):
                    ops, h = trace(mod, kind, side, t)
                    if t == 20:
                        hmax = max(hmax, h)
                        if side == 'L': kh[si].append(h)
                    rec = []
                    for im, gx, gy in ops:
                        a = np.asarray(im.convert('RGBA'))
                        key = (si, a.shape, a.tobytes())
                        if key not in sprite_key:
                            idx = []
                            for px in a.reshape(-1, 4):
                                c = tuple(int(v) for v in px)
                                if c[3] == 0: idx.append(0); continue
                                if c not in pal[si]: pal[si].append(c)
                                idx.append(pal[si].index(c))
                            sprite_key[key] = len(sprites)
                            sprites.append((si, a.shape[1], a.shape[0], idx))
                        rec.append((sprite_key[key], gx, gy - Y0))
                    fs_t.append(rec)
                fs_s.append(fs_t)
            fs_k.append(fs_s)
        frames.append(fs_k)
        rowh.append(hmax + 1)
    for si in range(len(STYLES)): assert len(pal[si]) <= 256, (si, len(pal[si]))
    # ── C 머리 ──
    o = ['/* 자동 생성(tools/ss2label/gen_art.py) — 195 타격 알림 라벨 도트 셋: 0 큰 한글 도트(시안 D) · 1 붓 한자 도장(A) · 2 이름 명패(B).',
         '   프레임(t 0..58, 60Hz)마다 붙일 1배 그림·자리(게임 화소 — gx 는 화면 x, dy 는 줄 위에서). 그림 = 스타일 팔레트 번호(0 투명)를 PackBits 로. */',
         '#ifndef SS2LABEL_ART_H', '#define SS2LABEL_ART_H', '#include <stdint.h>',
         '#define SL_PIX_STYLES %d' % len(STYLES), '#define SL_T %d' % T_MAX,
         'typedef struct { uint8_t style; uint8_t w, h; uint32_t off; } sl_sprite;',
         'typedef struct { uint16_t spr; int16_t gx, dy; } sl_op;',
         'typedef struct { uint16_t first; uint8_t n; } sl_frame;']
    o.append('static const uint8_t SL_ROWH[%d] = { %s };' % (len(STYLES), ', '.join(map(str, rowh))))
    o.append('/* 줄 0 에 이 종류가 있으면 줄 1 은 y0 + SL_KH + 1(시안 영상과 같게) */')
    o.append('static const uint8_t SL_KH[%d][4] = { %s };' % (len(STYLES), ', '.join('{%s}' % ','.join(map(str, k)) for k in kh)))
    o.append('static const uint32_t SL_PAL[%d][256] = {' % len(STYLES))
    for si in range(len(STYLES)):
        vals = ['0x%08X' % (c[0] | c[1] << 8 | c[2] << 16 | c[3] << 24) for c in pal[si]]
        o.append('   { %s },' % ', '.join(vals))
    o.append('};   /* 0xAABBGGRR(메모리 R,G,B,A) */')
    data = []; table = []
    for (si, w, h, idx) in sprites:
        p = pack(idx)
        assert unpack(p, w * h) == idx
        table.append('{%d,%d,%d,%d}' % (si, w, h, len(data)))
        data += p
    o.append('static const uint8_t SL_DATA[%d] = {' % len(data))
    for i in range(0, len(data), 40): o.append('   ' + ','.join(map(str, data[i:i + 40])) + ',')
    o.append('};')
    o.append('static const sl_sprite SL_SPR[%d] = { %s };' % (len(table), ', '.join(table)))
    ops = []; fr = []
    for si in range(len(STYLES)):
        for k in range(len(KINDS)):
            for s in range(2):
                for t in range(T_MAX):
                    rec = frames[si][k][s][t]
                    fr.append('{%d,%d}' % (len(ops), len(rec)))
                    ops += rec
    assert len(ops) < 65536
    o.append('static const sl_op SL_OPS[%d] = { %s };' % (len(ops), ', '.join('{%d,%d,%d}' % r for r in ops)))
    o.append('/* SL_FRAMES[((스타일 × 4 + 종류) × 2 + 쪽) × SL_T + t] — 쪽 0 = 왼쪽(P1) · 1 = 오른쪽(P2) */')
    o.append('static const sl_frame SL_FRAMES[%d] = { %s };' % (len(fr), ', '.join(fr)))
    o.append('#endif')
    print('\n'.join(o))
    sys.stderr.write('sprites %d · data %d B · ops %d · pal %s · rowh %s\n' % (len(sprites), len(data), len(ops), [len(p) for p in pal], rowh))

if __name__ == '__main__':
    main()
