#!/usr/bin/env python3
"""177: 컬러 표 대조 — native look_tab.h 와 Java LookTab.java(둘 다 tools/look_gen.py 산출)가 같은 값인가.
상대 컬러 쉼 견본(MenuModel — 대신 색 세 색)이 native 판정(look.c OPP_FB)과 어긋나지 않게. 사용: python3 tools/check_looktab.py (같으면 0)"""
import os, sys
def look_tables_equal():
    import re
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main')
    h = open(os.path.join(root, 'cpp', 'look_tab.h'), encoding='utf-8').read()
    j = open(os.path.join(root, 'java', 'com', 'dudu', 'pocketcore', 'LookTab.java'), encoding='utf-8').read()
    def nums(txt, start, end):
        a = txt.index(start); b = txt.index(end, a)
        return [int(x, 16) for x in re.findall(r'0x([0-9A-Fa-f]+)', txt[a:b])]
    th = nums(h, 'LOOK_TAB[', '};'); tj = nums(j, 'TAB = {', '};')
    dh = nums(h, 'LOOK_DUP[', '};'); dj = nums(j, 'DUP = {', '};')
    nrow = int(re.search(r'#define LOOK_NROW (\d+)', h).group(1))
    return th == tj and dh == dj and len(th) == nrow * 2 * 16 * 3 and nrow == int(re.search(r'NROW = (\d+)', j).group(1))

if __name__ == '__main__':
    ok = look_tables_equal()
    print('look_tab.h == LookTab.java: %s' % ('같음' if ok else '다름 — tools/look_gen.py 로 둘 다 다시 만들 것'))
    sys.exit(0 if ok else 1)
