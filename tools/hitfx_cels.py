"""183+ 타격 이펙트 도트 컷 → app/src/main/cpp/hitfx_cels.h
  python3 tools/hitfx_cels.py > app/src/main/cpp/hitfx_cels.h
그림은 tools/hitfx_cels_art.py(게임 화소 1배, 값 0 투명 · 1 흰 심 · 2 가운데 · 3 테두리 — 사무쇼2 스프라이트처럼 3색 + 투명).
섬광(약) 4컷 · 섬광(강, 오른쪽 = 맞은 쪽으로 뻗는 혜성) 5컷 · 반격 고리 4컷 · 작은 섬광(다단히트) 2컷
+ 185: 가드 반달 3컷 · 칼 부딪힘 4컷 · 넘어짐 먼지 4컷 · 막타 일섬 5컷. 컷마다 2프레임(1/30초), 첫 컷은 히트스톱 내내.
+ 192: 혜성을 작게(26×11) · 앞 강베기 일섬 따로(31×23) — 둘은 컷마다 길이(dur, 프레임)를 적고 히트스톱 동안에도 넘어간다. dur 0 = 2 프레임.
+ 195: «크게» 묶음 LB(약 섬광 23×23) · HB(혜성 40×15) · SB(일섬 47×35) — 작게와 같은 컷·길이.
+ 196: GPT 외주 그림(tools/hitfx_gpt/v1)을 고르기로 실었다가 197 에서 뺌(유저 「195가 좋다」) — 표는 195 그대로."""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hitfx_cels_art as A

# 196: GPT 외주 그림(014 의뢰 → from_gpt/ss2_hitfx_v1.zip, 015 실제 대전 비교)을 «그림 고르기»로 실었다가(test90) 유저 10-21 20:50 「195가 좋다」 →
#      197 에서 뺐다. 원본은 tools/hitfx_gpt/v1(manifest·PNG)에 남김 — 다시 쓰려면 아래 gpt() 로 읽어 SETS 에 더하면 된다.
GPT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'hitfx_gpt', 'v1')
_KEY = {(255, 255, 255): 1, (255, 192, 64): 2, (192, 64, 0): 3}
def gpt(kind, size):
    from PIL import Image
    m = json.load(open(os.path.join(GPT_DIR, 'manifest.json'), encoding='utf-8'))
    e = [x for x in m['effects'] if x['kind'] == kind and x['size'] == size][0]
    cels = []
    for c in e['cels']:
        im = Image.open(os.path.join(GPT_DIR, c['file'])).convert('RGBA'); w, h = im.size; px = im.load(); g = []
        for y in range(h):
            row = []
            for x in range(w):
                r, gg, b, a = px[x, y]
                if a == 0: row.append(0); continue
                if a != 255 or (r, gg, b) not in _KEY: raise SystemExit('열쇠색 아님: %s (%d,%d)' % (c['file'], x, y))
                row.append(_KEY[(r, gg, b)])
            g.append(row)
        cels.append(g)
    return cels, tuple(e['anchor']), [c['dur'] for c in e['cels']]

def tiny():
    g = A.blank(7, 7); A.put(g, 3, 3, 1)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)): A.put(g, 3 + dx, 3 + dy, 2)
    g = A.outline(g, 3)
    h = A.blank(7, 7)
    for (x, y) in ((1, 1), (5, 1), (1, 5), (5, 5)): A.put(h, x, y, 2)
    return [g, h], (3, 3)

SETS = [('L', A.spark_light), ('H', A.spark_heavy), ('R', A.counter_ring), ('T', tiny),
        ('G', A.guard_arc), ('C', A.clash), ('D', A.dust), ('K', A.ko_slash),   # 185: 가드·칼 부딪힘·넘어짐 먼지·막타 일섬
        ('S', A.front_slash),                                                     # 192: 앞 강베기 일섬
        ('LB', A.spark_light_big), ('HB', A.spark_heavy_big), ('SB', A.front_slash_big)]   # 195: «크게» — 약 섬광·혜성·일섬

out = ['/* 자동 생성(tools/hitfx_cels.py) — 타격 이펙트 도트 컷(게임 화소). 값 0 투명 · 1 흰 심 · 2 가운데 · 3 테두리 */',
       '#ifndef HITFX_CELS_H', '#define HITFX_CELS_H', '#include <stdint.h>',
       'typedef struct { uint8_t w, h, ax, ay; const uint8_t *px; uint8_t dur; } hfx_cel;   /* dur = 이 컷 프레임 수(0 = 2, 히트스톱 동안 첫 컷 멈춤) */']
def cname(nm): return ('HFXG_' + nm[2:]) if nm.startswith('G_') else ('HFX_' + nm)
def pname(nm): return ('HFXG_PX_' + nm[2:]) if nm.startswith('G_') else ('HFX_PX_' + nm)
def nname(nm): return ('HFXG_N_' + nm[2:]) if nm.startswith('G_') else ('HFX_N_' + nm)
for nm, fn in SETS:
    r = fn()
    cels, (ax, ay) = r[0], r[1]
    durs = r[2] if len(r) > 2 else [0] * len(cels)
    for i, g in enumerate(cels):
        flat = [v for row in g for v in row]
        out.append('static const uint8_t %s%d[%d] = {%s};' % (pname(nm), i, len(flat), ','.join(map(str, flat))))
    out.append('#define %s %d' % (nname(nm), len(cels)))
    out.append('static const hfx_cel %s[%s] = { %s };' % (cname(nm), nname(nm), ', '.join('{%d,%d,%d,%d,%s%d,%d}' % (len(g[0]), len(g), ax, ay, pname(nm), i, durs[i]) for i, g in enumerate(cels))))
out.append('#endif')
print('\n'.join(out))
