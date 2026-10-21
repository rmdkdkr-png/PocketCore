"""시안 B — 이름 명패(판 시작 이름판 결): 흰 판 + 빨강 테두리 + 양끝 술 장식, 빨강 한글(갈무리11 굵게, 게임 화소 1배).
등장: 때린 쪽 가장자리에서 두루마리처럼 펼쳐짐(6 프레임) → 글자 한 번 반전 번쩍 · 퇴장: 가장자리로 말려 들어감."""
from common import *
RED, DRED, WHITE, CREAM = (224, 16, 16, 255), (112, 0, 0, 255), (255, 255, 255, 255), (248, 240, 224, 255)

def text_mask(kind):
    return mask_text(KO[kind], font('Galmuri11-Bold.ttf', 12), binary=True, pad=0)

def plaque(kind, inverse=False):
    m = np.asarray(text_mask(kind)) > 127
    th, tw = m.shape
    W, H = tw + 12, th + 8                           # 판(테두리 포함) — 글자 좌우 6 · 위아래 4
    img = np.zeros((H + 4, W + 6, 4), np.uint8)       # 양끝 술 자리 3씩, 위아래 2씩
    ox, oy = 3, 2
    # 판: 바깥 검붉은 1 · 빨강 1 · 흰 1 · 크림 바탕
    img[oy:oy + H, ox:ox + W] = DRED
    img[oy + 1:oy + H - 1, ox + 1:ox + W - 1] = RED
    img[oy + 2:oy + H - 2, ox + 2:ox + W - 2] = WHITE
    img[oy + 3:oy + H - 3, ox + 3:ox + W - 3] = CREAM if not inverse else RED
    # 글자
    tx, ty = ox + 6, oy + 4
    sub = img[ty:ty + th, tx:tx + tw]
    sub[m] = RED if not inverse else WHITE
    # 양끝 술 장식(세로 막대 + 위아래 구슬)
    for x in (ox - 2, ox + W + 1):
        img[oy + 1:oy + H - 1, x] = DRED
        img[oy - 1:oy + 1, x - 1:x + 2] = RED
        img[oy + H - 1:oy + H + 1, x - 1:x + 2] = RED
    return Image.fromarray(img)

def draw(base, kind, t, side, y):
    if t < 0 or t > 58: return 0
    full = plaque(kind)
    W, H = full.size
    if t < 6: vis = ease_out((t + 1) / 6)
    elif t >= 50: vis = 1 - ease_out((t - 49) / 7)
    else: vis = 1.0
    if vis <= 0: return H
    spr = plaque(kind, inverse=(t in (6, 7)))
    w = max(4, int(round(W * vis)))
    if side == 'L':
        crop = spr.crop((W - w, 0, W, H)) if t < 6 else spr.crop((0, 0, w, H))
        gx = 3
    else:
        crop = spr.crop((0, 0, w, H)) if t < 6 else spr.crop((W - w, 0, W, H))
        gx = 160 - 3 - w
    paste(base, up(crop), gx * S, y * S)
    return H

if __name__ == '__main__':
    base = frame(2732)
    y = 34
    for k in ('first', 'counter'):
        y += draw(base, k, 20, 'L', y) + 1
    y = 34
    for k in ('punish', 'reversal'):
        y += draw(base, k, 20, 'R', y) + 1
    base.convert('RGB').save('/tmp/claude-0/hx/lbl/b_static.png')
