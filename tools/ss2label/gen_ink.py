"""195 타격 알림 라벨 시안 C(먹 베기 띠) 조각 → app/src/main/cpp/ss2label_ink.h
  python3 tools/ss2label/gen_ink.py > app/src/main/cpp/ss2label_ink.h
4배 그림이라 프레임을 다 담지 않고 조각만: 종류마다 붓 띠 가림(A8, 바깥 10 여백) · 기울인 영어 가림(A8 — 흰 글자, 빨강 그림자는 코어가 (4,4) 밀어 그림) ·
낙관(RGBA, 곧은 알파, −6°). 움직임(띠 쓸려 나옴·빛줄·글자 미끄러짐과 잔상·낙관 찍힘·칼선·갈라짐)은 코어(ss2hit.c ink_*)가 draft_c 와 같은 셈으로."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from PIL import Image
import common, draft_c
from gen_art import pack, unpack

KINDS = ['first', 'counter', 'punish', 'reversal']

def text_mask(kind):
    """draft_c.text_img 의 글자 가림(그림자 전)"""
    f = common.font('DelaGothicOne-Regular.ttf', int(draft_c.TEXT_H * 1.05))
    from PIL import ImageDraw
    txt = common.EN[kind]
    l, t, r, b = f.getbbox(txt)
    w, h = r - l + 30, b - t + 12
    m = Image.new('L', (w, h), 0)
    ImageDraw.Draw(m).text((6 - l, 6 - t), txt, font=f, fill=255)
    m = m.transform(m.size, Image.AFFINE, (1, 0.22, -0.22 * h * 0.5, 0, 1, 0), resample=Image.BICUBIC)
    m = m.crop(m.getbbox())
    maxw = 66 * common.S
    if m.width > maxw:
        sc = maxw / m.width
        m = m.resize((int(m.width * sc), int(m.height * sc)), Image.LANCZOS)
    return m

def main():
    o = ['/* 자동 생성(tools/ss2label/gen_ink.py) — 195 타격 알림 라벨 시안 C(먹 베기 띠) 조각(4배 화소). 움직임은 ss2hit.c */',
         '#ifndef SS2LABEL_INK_H', '#define SS2LABEL_INK_H', '#include <stdint.h>',
         '#define SLI_BAND_H %d   /* 띠 높이(4배 화소) */' % (draft_c.BAND_H * common.S),
         '#define SLI_SEAL_PX %d' % draft_c.SEAL_PX,
         'typedef struct { uint16_t w, h; uint32_t off; } sli_img;']
    data = []; recs = []
    for kind in KINDS:
        tm = text_mask(kind)
        # 확인: text_img 와 같은 그림이 되는지(가림 → 그림자·흰 글자)
        ti = draft_c.text_img(kind)
        assert ti.size == (tm.width + 5, tm.height + 5), (ti.size, tm.size)
        L = ti.width + 22 + draft_c.SEAL_PX + 26
        bm, pad = draft_c.band_mask(L, draft_c.BAND_H * common.S, 3)
        se = draft_c.seal_img(kind, draft_c.SEAL_PX, -6)
        for im, mode in ((bm, 'L'), (tm, 'L'), (se, 'RGBA')):
            a = np.asarray(im.convert(mode))
            flat = a.reshape(-1).tolist()
            p = pack(flat)
            assert unpack(p, len(flat)) == flat
            recs.append('{%d,%d,%d}' % (im.width, im.height, len(data)))
            data += p
    o.append('static const uint8_t SLI_DATA[%d] = {' % len(data))
    for i in range(0, len(data), 40): o.append('   ' + ','.join(map(str, data[i:i + 40])) + ',')
    o.append('};')
    o.append('/* 종류마다 [띠 가림 A8 · 글자 가림 A8 · 낙관 RGBA] — SLI_IMG[종류 × 3 + 0..2] */')
    o.append('static const sli_img SLI_IMG[%d] = { %s };' % (len(recs), ', '.join(recs)))
    o.append('#endif')
    print('\n'.join(o))
    sys.stderr.write('ink data %d B\n' % len(data))

if __name__ == '__main__':
    main()
