"""195 타격 알림 라벨 넷(시안 A·B·C·D) 공통 — 게임 화소(160×152) ×4 출력 위에 그린다.
글꼴(모두 OFL): KaiseiTokumin-ExtraBold · Silkscreen-Bold · DelaGothicOne · YujiBoku(구글 글꼴) · Galmuri11-Bold · Galmuri7(앱 assets/fonts 의 pixel_b·galmuri7).
SS2LABEL_FONTS = 글꼴 폴더(기본 /tmp/claude-0/hx/fonts). 그림만 헤더에 들어가고 글꼴 파일은 안 들어간다."""
import math, os
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops
F = os.environ.get('SS2LABEL_FONTS', '/tmp/claude-0/hx/fonts').rstrip('/') + '/'
CLIP = os.environ.get('SS2LABEL_CLIP', '/tmp/claude-0/hx/clip').rstrip('/') + '/'   # 시안 영상용 게임 그림(PPM)
S = 4
KINDS = ['first', 'counter', 'punish', 'reversal']
EN = {'first': 'FIRST ATTACK', 'counter': 'COUNTER', 'punish': 'PUNISHER', 'reversal': 'REVERSAL'}
KANJI = {'first': '先制', 'counter': '反撃', 'punish': '確反', 'reversal': '切返'}
KO = {'first': '선제', 'counter': '반격', 'punish': '응징', 'reversal': '역습'}
SEAL = {'first': '先', 'counter': '反', 'punish': '確', 'reversal': '返'}

def frame(call):
    return Image.open('%sf%06d.ppm' % (CLIP, call)).convert('RGBA')

def font(name, size):
    return ImageFont.truetype(F + name, size)

def mask_text(text, fnt, binary=True, pad=2):
    """글자 가림(L) — binary 면 1비트(도트)."""
    l, t, r, b = fnt.getbbox(text)
    w, h = r - l + pad * 2, b - t + pad * 2
    im = Image.new('L', (w, h), 0)
    d = ImageDraw.Draw(im)
    if binary: d.fontmode = '1'
    d.text((pad - l, pad - t), text, font=fnt, fill=255)
    return im

def dilate(m, r=1, diag=True):
    """L 가림 넓히기(화소 r)."""
    a = np.asarray(m) > 127
    out = a.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            if not diag and abs(dx) + abs(dy) > r: continue
            out |= np.roll(np.roll(a, dy, 0), dx, 1)
    return Image.fromarray((out * 255).astype(np.uint8))

def pad_img(m, p):
    w, h = m.size
    o = Image.new(m.mode, (w + 2 * p, h + 2 * p), 0)
    o.paste(m, (p, p))
    return o

def up(im, s=S):
    return im.resize((im.width * s, im.height * s), Image.NEAREST)

BAYER4 = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16.0

def dither_alpha(rgba, keep):
    """keep 0~1 — 4×4 바이어 무늬로 화소를 지움(원작 바둑판 사라짐 결)."""
    a = np.array(rgba)
    h, w = a.shape[:2]
    th = np.tile(BAYER4, (h // 4 + 1, w // 4 + 1))[:h, :w]
    a[..., 3] = np.where(th < keep, a[..., 3], 0)
    return Image.fromarray(a)

def ease_out(x):
    x = min(max(x, 0.0), 1.0)
    return 1 - (1 - x) ** 3

def ease_out_back(x, k=1.70158):
    x = min(max(x, 0.0), 1.0)
    return 1 + (k + 1) * (x - 1) ** 3 + k * (x - 1) ** 2

def paste(base, over, x, y):
    """over(RGBA)를 base(RGBA) 위 (x, y) 출력 화소에."""
    base.alpha_composite(over, (int(x), int(y))) if 0 <= x and 0 <= y and x + over.width <= base.width and y + over.height <= base.height else _paste_clip(base, over, int(x), int(y))

def _paste_clip(base, over, x, y):
    l, t = max(0, -x), max(0, -y)
    r, b = min(over.width, base.width - x), min(over.height, base.height - y)
    if r <= l or b <= t: return
    base.alpha_composite(over.crop((l, t, r, b)), (x + l, y + t))
