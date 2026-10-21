"""시안 D — 큰 한글 도트(타이머 숫자 결): 갈무리11 굵게를 2배 도트로(글자 22 게임 화소), 노랑 → 주황 → 빨강 띠 + 검갈색 테두리·그림자,
아래 작은 영어(갈무리7). 등장: 위에서 떨어져 튀고(찌그러짐) 한 번 하얗게 번쩍 + 좌우 1px 떨림 · 퇴장: 위로 쏙 줄며 사라짐."""
from common import *
GRAD = [(255, 248, 120), (255, 224, 64), (255, 176, 32), (255, 128, 16), (232, 72, 8), (200, 32, 0)]
EDGE, SHADOW = (56, 16, 0, 255), (0, 0, 0, 170)

def big_sprite(kind, white=False):
    m = mask_text(KO[kind] + '!', font('Galmuri11-Bold.ttf', 12), binary=True, pad=0)
    m = m.crop(m.getbbox())
    a = np.asarray(m) > 127
    k = 1 if kind == 'first' else 2                      # 선제는 작게(반격·응징·역습과 같이 뜨면 위에 작은 꼬리표)
    a2 = np.kron(a, np.ones((k, k), bool))               # 2배 도트
    a2 = np.pad(a2, 3)
    h, w = a2.shape
    ob = np.asarray(dilate(Image.fromarray((a2 * 255).astype(np.uint8)), 1)) > 127
    sh = np.zeros_like(a2); sh[2:, 2:] = ob[:-2, :-2]
    img = np.zeros((h, w, 4), np.uint8)
    img[sh & ~ob] = SHADOW
    img[ob & ~a2] = EDGE
    ys = np.nonzero(a2.any(1))[0]; y0, y1 = ys.min(), ys.max()
    for y in range(y0, y1 + 1):
        if white: c = (255, 255, 255)
        else:
            u = (y - y0) / max(1, y1 - y0)
            c = GRAD[min(len(GRAD) - 1, int(u * len(GRAD)))]
        row = a2[y]
        img[y][row] = c + (255,)
    # 윗줄 반짝(글자 위 가장자리 1px 흰)
    if not white:
        top = a2 & ~np.roll(a2, 1, 0)
        img[top] = (255, 255, 230, 255)
    return Image.fromarray(img)

def en_sprite(kind):
    m = mask_text(EN[kind], font('Galmuri7.ttf', 8), binary=True, pad=2)
    a = np.asarray(m) > 127
    ob = np.asarray(dilate(m, 1)) > 127
    img = np.zeros(a.shape + (4,), np.uint8)
    img[ob & ~a] = (40, 12, 0, 255)
    img[a] = (255, 240, 200, 255)
    return Image.fromarray(img)

def draw(base, kind, t, side, y):
    if t < 0 or t > 58: return 0
    big = big_sprite(kind, white=(t in (5, 6)))
    en = en_sprite(kind)
    W = max(big.width, en.width); Hh = big.height + en.height - 3
    # 등장: 떨어짐(0~4) · 찌그러짐(5~7) · 떨림(6~11)
    if t < 5: dy = -int(round((1 - ease_out((t + 1) / 5)) * 26)); sy = 1.0
    elif t == 5: dy = 0; sy = 0.82
    elif t == 6: dy = -1; sy = 1.08
    else: dy = 0; sy = 1.0
    dx = (1 if t % 2 else -1) if 6 <= t <= 11 else 0
    if t >= 50:
        v = ease_out((t - 49) / 8)
        sy = max(0.05, 1 - v); dy = -int(v * 10)
    spr = big if sy == 1.0 else big.resize((big.width, max(1, int(round(big.height * sy)))), Image.NEAREST)
    gx = (3 if side == 'L' else 160 - 3 - W) + (W - big.width) // 2 + dx
    gy = y + dy + (big.height - spr.height)
    paste(base, up(spr), gx * S, gy * S)
    if 4 <= t < 50:
        ex = (3 if side == 'L' else 160 - 3 - W) + (W - en.width) // 2
        e = en if t >= 6 else dither_alpha(en, (t - 3) / 3)
        paste(base, up(e), ex * S, (y + big.height - 3) * S)
    return Hh + 1

if __name__ == '__main__':
    for side, ks, nm in (('L', ('first', 'counter'), 'd_static_l.png'), ('R', ('punish', 'reversal'), 'd_static_r.png')):
        base = frame(2732); y = 34
        for k in ks: y += draw(base, k, 20, side, y)
        base.convert('RGB').save('/tmp/claude-0/hx/lbl/' + nm)
    a = Image.open('/tmp/claude-0/hx/lbl/d_static_l.png'); b = Image.open('/tmp/claude-0/hx/lbl/d_static_r.png')
    s = Image.new('RGB', (1280, 608)); s.paste(a, (0, 0)); s.paste(b, (640, 0)); s.save('/tmp/claude-0/hx/lbl/d_static.png')
