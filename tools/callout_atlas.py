"""182 타격 알림 라벨 그림 → app/src/main/cpp/callout_atlas.h
   글꼴: Inter Display Black Italic(SIL OFL 1.1 — 글꼴 파일은 안 넣고 그린 그림만 넣는다), 가로 0.86 배로 좁힘.
   라벨마다 4배(게임 화소 1 = 그림 4) 그림, 바이트 하나 = 위 니블 글자 덮임(0..15) · 아래 니블 테두리 덮임(0..15).
   0 이 이어지면 0, n(1..255) 으로 줄여 적는다(RLE). 색·그러데이션·그림자는 callout.c 가 텍스처를 만들 때 입힌다.
     python3 tools/callout_atlas.py [글꼴] > app/src/main/cpp/callout_atlas.h"""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
FONT = sys.argv[1] if len(sys.argv) > 1 else '/usr/share/fonts/opentype/inter/InterDisplay-BlackItalic.otf'
S = 4            # 게임 화소 1 = 그림 4
CAP = 28         # 대문자 높이(그림 px) = 게임 7px
SX = 0.86        # 가로 좁힘
OUT = 3          # 테두리(그림 px)
LABELS = ['FIRST ATTACK', 'COUNTER', 'PUNISHER', 'REVERSAL']

def render(text):
    font = ImageFont.truetype(FONT, int(CAP * 1.38))
    l, t, r, b = font.getbbox(text)
    pad = OUT + 2
    W, H = r - l + pad * 2 + 6, b - t + pad * 2
    m = Image.new('L', (W, H), 0)
    ImageDraw.Draw(m).text((pad + 3 - l, pad - t), text, font=font, fill=255)
    m = m.resize((max(1, int(round(W * SX))), H), Image.LANCZOS)
    o = m.filter(ImageFilter.MaxFilter(OUT * 2 + 1))
    f = np.asarray(m).astype(np.float32) / 255.0
    g = np.asarray(o).astype(np.float32) / 255.0
    # 빈 줄·칸 깎기(그림자 자리 1 게임 화소 남김)
    a = np.maximum(f, g)
    ys = np.where(a.max(1) > 0.02)[0]; xs = np.where(a.max(0) > 0.02)[0]
    y0, y1, x0, x1 = ys[0], ys[-1] + 1, xs[0], xs[-1] + 1
    f = f[y0:y1, x0:x1]; g = g[y0:y1, x0:x1]
    fy = np.where(f.max(1) > 0.5)[0]
    return f, g, int(fy[0]), int(fy[-1])

def q4(a): return np.clip(np.rint(a * 15), 0, 15).astype(np.uint8)

out = []
out.append('/* 자동 생성 — tools/callout_atlas.py (글꼴 %s, 4배, 대문자 %dpx, 가로 %.2f, 테두리 %dpx). */' % (FONT.split('/')[-1], CAP, SX, OUT))
out.append('#define CALLOUT_ATLAS_SCALE %d' % S)
out.append('#define CALLOUT_ATLAS_N %d' % len(LABELS))
rows = []
for i, text in enumerate(LABELS):
    f, g, fy0, fy1 = render(text)
    h, w = f.shape
    byte = (q4(f) << 4) | q4(g)
    flat = byte.reshape(-1).tolist()
    rle = []; k = 0
    while k < len(flat):
        if flat[k] == 0:
            n = 0
            while k < len(flat) and flat[k] == 0 and n < 255: n += 1; k += 1
            rle += [0, n]
        else:
            rle.append(flat[k]); k += 1
    name = 'CALLOUT_RLE_%d' % i
    out.append('static const uint8_t %s[%d] = {   /* %s %dx%d, 글자 줄 %d..%d */' % (name, len(rle), text, w, h, fy0, fy1))
    for j in range(0, len(rle), 32): out.append('   ' + ','.join(str(v) for v in rle[j:j + 32]) + ',')
    out.append('};')
    rows.append((w, h, fy0, fy1, name, len(rle), text))
out.append('static const struct { int w, h, fy0, fy1; const uint8_t *rle; int n; const char *text; } CALLOUT_ATLAS[CALLOUT_ATLAS_N] = {')
for w, h, fy0, fy1, name, n, text in rows: out.append('   { %d, %d, %d, %d, %s, %d, "%s" },' % (w, h, fy0, fy1, name, n, text))
out.append('};')
print('\n'.join(out))
