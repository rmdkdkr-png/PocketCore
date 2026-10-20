"""183+ 타격 이펙트 도트 컷 → app/src/main/cpp/hitfx_cels.h
  python3 tools/hitfx_cels.py > app/src/main/cpp/hitfx_cels.h
그림은 tools/hitfx_cels_art.py(게임 화소 1배, 값 0 투명 · 1 흰 심 · 2 가운데 · 3 테두리 — 사무쇼2 스프라이트처럼 3색 + 투명).
섬광(약) 4컷 · 섬광(강, 오른쪽 = 맞은 쪽으로 뻗는 혜성) 5컷 · 반격 고리 4컷 · 작은 섬광(다단히트) 2컷
+ 185: 가드 반달 3컷 · 칼 부딪힘 4컷 · 넘어짐 먼지 4컷 · 막타 일섬 5컷. 컷마다 2프레임(1/30초), 첫 컷은 히트스톱 내내.
+ 192: 혜성을 작게(26×11) · 앞 강베기 일섬 따로(31×23) — 둘은 컷마다 길이(dur, 프레임)를 적고 히트스톱 동안에도 넘어간다. dur 0 = 2 프레임."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hitfx_cels_art as A

def tiny():
    g = A.blank(7, 7); A.put(g, 3, 3, 1)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)): A.put(g, 3 + dx, 3 + dy, 2)
    g = A.outline(g, 3)
    h = A.blank(7, 7)
    for (x, y) in ((1, 1), (5, 1), (1, 5), (5, 5)): A.put(h, x, y, 2)
    return [g, h], (3, 3)

SETS = [('L', A.spark_light), ('H', A.spark_heavy), ('R', A.counter_ring), ('T', tiny),
        ('G', A.guard_arc), ('C', A.clash), ('D', A.dust), ('K', A.ko_slash),   # 185: 가드·칼 부딪힘·넘어짐 먼지·막타 일섬
        ('S', A.front_slash)]                                                     # 192: 앞 강베기 일섬
out = ['/* 자동 생성(tools/hitfx_cels.py) — 타격 이펙트 도트 컷(게임 화소). 값 0 투명 · 1 흰 심 · 2 가운데 · 3 테두리 */',
       '#ifndef HITFX_CELS_H', '#define HITFX_CELS_H', '#include <stdint.h>',
       'typedef struct { uint8_t w, h, ax, ay; const uint8_t *px; uint8_t dur; } hfx_cel;   /* dur = 이 컷 프레임 수(0 = 2, 히트스톱 동안 첫 컷 멈춤) */']
for nm, fn in SETS:
    r = fn()
    cels, (ax, ay) = r[0], r[1]
    durs = r[2] if len(r) > 2 else [0] * len(cels)
    for i, g in enumerate(cels):
        flat = [v for row in g for v in row]
        out.append('static const uint8_t HFX_PX_%s%d[%d] = {%s};' % (nm, i, len(flat), ','.join(map(str, flat))))
    out.append('#define HFX_N_%s %d' % (nm, len(cels)))
    out.append('static const hfx_cel HFX_%s[HFX_N_%s] = { %s };' % (nm, nm, ', '.join('{%d,%d,%d,%d,HFX_PX_%s%d,%d}' % (len(g[0]), len(g), ax, ay, nm, i, durs[i]) for i, g in enumerate(cels))))
out.append('#endif')
print('\n'.join(out))
