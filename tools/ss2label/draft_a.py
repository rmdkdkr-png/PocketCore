"""시안 A — 원작 붓 한자 도장(一本·勝負 결). 게임 화소 1배 도트: 빨강 두 톤 + 검붉은 테두리 + 그림자, 아래 작은 영어.
등장: 흰 실루엣 1.5배 → 흰 1배 → 빨강(원작 一本目 이 흰색 → 빨강) · 머묾 · 퇴장: 4×4 바둑판으로 사라짐."""
from common import *
KFONT = 'KaiseiTokumin-ExtraBold.ttf'
RED_HI, RED_LO, EDGE, SHADOW = (255, 40, 32, 255), (196, 0, 0, 255), (48, 0, 0, 255), (0, 0, 0, 160)
WHITE = (255, 255, 255, 255)
EN_FONT = 'Silkscreen-Bold.ttf'

def kanji_sprite(kind, size, white=False):
    m = mask_text(KANJI[kind], font(KFONT, size), binary=True, pad=3)
    a = np.asarray(m) > 127
    h, w = a.shape
    o = dilate(m, 1)
    ob = np.asarray(o) > 127
    sh = np.zeros_like(a); sh[1:, 1:] = ob[:-1, :-1]          # 그림자 = 테두리를 오른쪽 아래로 1
    img = np.zeros((h, w, 4), np.uint8)
    img[sh & ~ob] = SHADOW
    img[ob & ~a] = EDGE
    if white:
        img[a] = WHITE
    else:
        ys = np.nonzero(a.any(1))[0]
        mid = (ys.min() + ys.max()) / 2 if len(ys) else h / 2
        top = np.zeros_like(a); top[:int(mid) + 1] = True
        img[a & top] = RED_HI
        img[a & ~top] = RED_LO
    return Image.fromarray(img)

def en_sprite(kind):
    m = mask_text(EN[kind], font(EN_FONT, 8), binary=True, pad=2)
    a = np.asarray(m) > 127
    ob = np.asarray(dilate(m, 1)) > 127
    img = np.zeros(a.shape + (4,), np.uint8)
    img[ob & ~a] = (24, 0, 0, 255)
    img[a] = (255, 236, 200, 255)
    return Image.fromarray(img)

def label_sprite(kind, size, white=False):
    k = kanji_sprite(kind, size, white)
    e = en_sprite(kind)
    w = max(k.width, e.width); h = k.height + e.height - 2
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    im.alpha_composite(k, ((w - k.width) // 2, 0))
    im.alpha_composite(e, ((w - e.width) // 2, k.height - 2))
    return im

def size_of(kind):
    return 14 if kind == 'first' else 19

def draw(base, kind, t, side, y):
    """base = 640×608 RGBA, t = 등장 뒤 프레임, side 'L'/'R', y = 게임 y(위). 돌려줌: 차지한 높이(게임 화소)."""
    if t < 0 or t > 58: return 0
    sz = size_of(kind)
    spr = label_sprite(kind, sz, white=t < 4)
    sc = 1.5 if t < 2 else 1.0
    if sc != 1.0:
        spr = spr.resize((int(spr.width * sc), int(spr.height * sc)), Image.NEAREST)
    if t >= 50:
        spr = dither_alpha(spr, 1 - (t - 49) / 9)
    full = label_sprite(kind, sz)
    gx = 3 if side == 'L' else 160 - 3 - full.width
    cx, cy = gx + full.width / 2, y + full.height / 2
    x0, y0 = math.floor(cx - spr.width / 2 + 0.5), math.floor(cy - spr.height / 2 + 0.5)   # 195 반올림은 늘 위로(줄 y 홀짝과 상관없이 같은 자리 — 코어 표가 줄마다 같게)
    paste(base, up(spr), x0 * S, y0 * S)
    return full.height

if __name__ == '__main__':
    import sys
    base = frame(2732)
    y = 34
    for k in ('first', 'counter'):
        y += draw(base, k, 20, 'L', y) + 1
    y = 34
    for k in ('punish', 'reversal'):
        y += draw(base, k, 20, 'R', y) + 1
    base.convert('RGB').save('/tmp/claude-0/hx/lbl/a_static.png')
