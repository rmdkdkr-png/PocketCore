#!/usr/bin/env python3
"""176c 컬러 표 회귀(175 사용자 보고 «수라나찰 변경시 컬러고정은 안되네 — 하오마루»): 커스텀 프리셋 2~15 는 검질과 무관해야 한다.
look_tab.h(tools/look_gen.py 산출)에서 캐릭터 0~13 × 프리셋 2~15 = 196 칸의 나찰 값이 수라 값과 같은가 — 0 기본·1 2P색만 검질마다 게임 색.
런타임에도 look.c tri() 가 «idx 2~15 → 수라 판»으로 보증하지만, 표 자체가 어긋나면 여기서 잡는다(175 표면 102 칸).
사용: python3 tools/check_lookfree.py (같으면 0) · 다른 검사에서: from check_lookfree import presets_style_free"""
import os, re, sys

def presets_style_free(path=None):
    """다른 칸 목록 [(캐릭터, 프리셋)] — 비어야 한다"""
    path = path or os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'cpp', 'look_tab.h')
    h = open(path, encoding='utf-8').read()
    a = h.index('LOOK_TAB['); t = [int(x, 16) for x in re.findall(r'0x([0-9A-Fa-f]+)', h[a:h.index('};', a)])]
    tri = lambda r, s, i: t[((r * 2 + s) * 16 + i) * 3:((r * 2 + s) * 16 + i) * 3 + 3]
    return [(r, i) for r in range(14) for i in range(2, 16) if tri(r, 1, i) != tri(r, 0, i)]

if __name__ == '__main__':
    bad = presets_style_free(sys.argv[1] if len(sys.argv) > 1 else None)
    print('프리셋 2~15 검질 무관(캐릭터 0~13 · 196 칸): %s' % ('그렇다' if not bad else '다른 칸 %d — %s — tools/look_gen.py 로 다시 만들 것' % (len(bad), bad[:8])))
    sys.exit(0 if not bad else 1)
