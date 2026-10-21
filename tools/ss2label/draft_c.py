"""시안 C — 먹 베기 띠(모션 그래픽, 4배 그림 그대로): 때린 쪽 가장자리에서 붓 띠가 쓸려 나오고 흰 기울임 영어가 미끄러져 들어온 뒤
붉은 낙관(先·反·確·返)이 찍힘 · 퇴장: 흰 칼선이 띠를 가로로 베고 위아래로 갈라지며 사라짐."""
from common import *
import random
BAND_H = 17                      # 게임 화소
INK = (16, 12, 12)
TEXT_H = 40                      # 출력 화소(4배)
SEAL_PX = 52

def text_img(kind):
    f = font('DelaGothicOne-Regular.ttf', int(TEXT_H * 1.05))
    txt = EN[kind]
    l, t, r, b = f.getbbox(txt)
    w, h = r - l + 30, b - t + 12
    m = Image.new('L', (w, h), 0)
    ImageDraw.Draw(m).text((6 - l, 6 - t), txt, font=f, fill=255)
    m = m.transform(m.size, Image.AFFINE, (1, 0.22, -0.22 * h * 0.5, 0, 1, 0), resample=Image.BICUBIC)
    m = m.crop(m.getbbox())
    maxw = 66 * S
    if m.width > maxw:
        sc = maxw / m.width
        m = m.resize((int(m.width * sc), int(m.height * sc)), Image.LANCZOS)
    out = Image.new('RGBA', (m.width + 5, m.height + 5), (0, 0, 0, 0))
    sh = Image.new('RGBA', m.size, (210, 24, 24, 0)); sh.putalpha(m)
    tx = Image.new('RGBA', m.size, (255, 255, 255, 0)); tx.putalpha(m)
    out.alpha_composite(sh, (4, 4)); out.alpha_composite(tx, (0, 0))
    return out

def seal_img(kind, px=SEAL_PX, rot=-6):
    im = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((2, 2, px - 3, px - 3), radius=max(2, px // 9), fill=(204, 20, 26, 255))
    d.rounded_rectangle((px // 8, px // 8, px - px // 8 - 1, px - px // 8 - 1), radius=max(1, px // 18), outline=(255, 236, 220, 255), width=max(2, px // 20))
    f = font('YujiBoku-Regular.ttf', int(px * 0.6))
    l, t, r, b = f.getbbox(SEAL[kind])
    d.text(((px - (r - l)) / 2 - l, (px - (b - t)) / 2 - t), SEAL[kind], font=f, fill=(255, 240, 230, 255))
    a = np.array(im); rnd = np.random.default_rng(7)
    holes = rnd.random(a.shape[:2]) < 0.05
    a[holes & (a[..., 3] > 0), 3] = 110
    return Image.fromarray(a).rotate(rot, resample=Image.BICUBIC, expand=True)

def band_mask(L, H, seed):
    """붓 띠(출력 화소) — 털 줄 30개를 그어 끝이 갈라지고 위아래가 가늘어짐."""
    rnd = random.Random(seed)
    pad = 10
    m = Image.new('L', (L + pad * 2, H + pad * 2), 0)
    d = ImageDraw.Draw(m)
    n = 34
    for i in range(n):
        u = (i + 0.5) / n                        # 위 0 → 아래 1
        edge = 1 - abs(u - 0.5) * 2              # 가운데 1, 가장자리 0
        end = L * (0.80 + 0.2 * edge ** 0.5) * rnd.uniform(0.93, 1.0)
        y0 = pad + u * H + rnd.uniform(-0.8, 0.8)
        wobble = rnd.uniform(-2.5, 2.5)
        th = max(2, int(H / n * 2.2 * (0.5 + 0.5 * edge ** 0.3)))
        pts = [(pad + x, y0 + wobble * math.sin(x / L * math.pi) - x / L * 6) for x in np.linspace(0, end, 12)]
        d.line(pts, fill=255, width=th)
        if rnd.random() < 0.35:                  # 마른 붓 끊김
            gx = pad + end * rnd.uniform(0.55, 0.85)
            d.line([(gx, y0 - 1 - gx / L * 6), (gx + rnd.uniform(8, 24), y0 - 1 - gx / L * 6)], fill=0, width=th)
    return m.filter(ImageFilter.GaussianBlur(0.7)), pad

def layers(kind, t, seed):
    """왼쪽 기준 층들: (띠 RGBA, 글자 (그림, x, y) | None, 낙관 (그림, cx, cy) | None, 칼선 y, 전체 폭·높이)."""
    ti = text_img(kind)
    L = ti.width + 22 + SEAL_PX + 26
    H = BAND_H * S
    bm, pad = band_mask(L, H, seed)
    p = ease_out((t + 1) / 4)
    reveal = Image.new('L', bm.size, 0)
    xe = int(p * (bm.width + 24))
    ImageDraw.Draw(reveal).polygon([(0, 0), (xe, 0), (xe - 22, bm.height), (0, bm.height)], fill=255)
    m = ImageChops.multiply(bm, reveal)
    band = Image.new('RGBA', bm.size, INK + (0,)); band.putalpha(m.point(lambda v: int(v * 0.92)))
    if 0 <= t < 4:
        d = ImageDraw.Draw(band)
        for k, yy in enumerate((0.22, 0.5, 0.8)):
            y = pad + H * yy
            d.line([(xe - 70 - k * 24, y), (xe - 8, y - 2)], fill=(255, 255, 255, 170), width=2)
    txt = None
    if t >= 2:
        q = ease_out_back((t - 1) / 5, 1.5)
        tx = pad + 14 - (1 - q) * 150
        ty = pad + (H - ti.height) / 2 - 3
        txt = (ti, tx, ty, t < 6)
    seal = None
    if t >= 5:
        s = 1.0 + 0.8 * max(0.0, 1 - (t - 5) / 3)
        rot = -16 + 10 * min(1.0, (t - 5) / 3)
        se = seal_img(kind, int(SEAL_PX * s), rot)
        cx = pad + 14 + ti.width + 14 + SEAL_PX / 2
        cy = pad + H / 2 - 4
        seal = (se, cx, cy)
    return band, txt, seal, pad + H / 2 - 4, pad

def render(kind, side, t, seed):
    band, txt, seal, cut_y, pad = layers(kind, min(t, 47), seed)
    W, H = band.width, band.height
    img = Image.new('RGBA', (W + 30, H + 30), (0, 0, 0, 0))
    oy = 15
    if side == 'L': img.alpha_composite(band, (0, oy))
    else: img.alpha_composite(band.transpose(Image.FLIP_LEFT_RIGHT), (img.width - W, oy))
    def put(im, x, y):
        if side == 'R': x = img.width - x - im.width
        paste(img, im, x, y)
    if txt:
        ti, tx, ty, blur = txt
        if blur:
            for g in (3, 2, 1):
                gh = ti.copy(); gh.putalpha(gh.getchannel('A').point(lambda v, g=g: int(v * 0.16 * (4 - g))))
                put(gh, int(tx - g * 28), int(ty + oy))
        put(ti, int(tx), int(ty + oy))
    if seal:
        se, cx, cy = seal
        put(se, int(cx - se.width / 2), int(cy + oy - se.height / 2))
    return img, oy + cut_y

def draw(base, kind, t, side, y, seed=3):
    if t < 0 or t > 58: return 0
    img, cut = render(kind, side, t, seed)
    if t >= 48:
        out = Image.new('RGBA', img.size, (0, 0, 0, 0))
        if t >= 50:
            v = ease_out((t - 49) / 8)
            fa = max(0.0, 1 - v * 1.2)
            top = img.crop((0, 0, img.width, int(cut))); bot = img.crop((0, int(cut), img.width, img.height))
            for pc, py, sgn in ((top, 0, -1), (bot, int(cut), 1)):
                pc = pc.copy(); pc.putalpha(pc.getchannel('A').point(lambda a: int(a * fa)))
                dx = int(v * 30) * (1 if sgn > 0 else -1) * (1 if side == 'L' else -1)
                paste(out, pc, dx, int(py + sgn * v * 24))
        else:
            out = img.copy()
        if t <= 51:
            d = ImageDraw.Draw(out)
            pr = min(1.0, (t - 47) / 3)
            w = img.width
            if side == 'L': d.line([(w * (1 - pr), cut + 2), (w, cut - 2)], fill=(255, 255, 255, 255), width=4)
            else: d.line([(0, cut - 2), (w * pr, cut + 2)], fill=(255, 255, 255, 255), width=4)
        img = out
    gx = 0 if side == 'L' else 160 * S - img.width
    paste(base, img, gx, y * S - 15 - 10)
    return BAND_H + 2

if __name__ == '__main__':
    for side, ks, nm in (('L', ('first', 'counter'), 'c_static_l.png'), ('R', ('punish', 'reversal'), 'c_static_r.png')):
        base = frame(2732); y = 34
        for k in ks: y += draw(base, k, 20, side, y)
        base.convert('RGB').save('/tmp/claude-0/hx/lbl/' + nm)
    from PIL import Image
    a = Image.open('/tmp/claude-0/hx/lbl/c_static_l.png'); b = Image.open('/tmp/claude-0/hx/lbl/c_static_r.png')
    s = Image.new('RGB', (1280, 608)); s.paste(a, (0, 0)); s.paste(b, (640, 0)); s.save('/tmp/claude-0/hx/lbl/c_static.png')
