#!/usr/bin/env python3
"""컬러 표 생성기 — look_tab.h(프리셋 16개 × 캐릭터 14명 + 쿠로코 + 유가 × 검질 2 · 겹침 표 · 이름) + LookTab.java(같은 값)
사용: python3 tools/look_gen.py <앱>/app/src/main/cpp [<앱>/app/src/main/java/com/dudu/pocketcore]
표는 여기서 한 번 계산해 C·Java 에 박는다(기기마다 float 결과가 갈리지 않게 · 리뷰·시험이 표 하나를 본다).
프리셋 정의는 colorgen.py 의 FINAL(같은 폴더). 세로 이름 칸 글자(LOOK_VLAB)는 hub_glyphs.py 가 hub_glyphs.h 에 만든다.
177(166-5): 프리셋 2~15 는 검질과 무관 — 수라 판 값(그 캐릭터 수라 기본·2P색으로 계산)을 두 검질에 같이 쓴다. 0 기본·1 2P색만 검질마다 게임 색.
   겹침(LOOK_DUP)은 합친 값으로 다시 센다(그 검질 기본과 같은 프리셋은 그 검질에서만 건너뜀).
177: 줄 15 = 유가(상대 컬러 전용 — 리그 상대가 유가일 때. P1 코드는 row < 14 그대로). 롬 0x259310 + 14*0x40 의 [0]·[3]·[5]·[6](실측 경기 팔레트 5).
177: 둘째 인자를 주면 LookTab.java 도 — 자바가 상대 컬러를 native 없이 정하고(OppLook) 쉼 견본(MenuModel)을 그린다. tools/check_golden.py 가 두 표가 같은지 본다."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import colorgen as G
KUROKO = ((0xEEE, 0x666, 0x000), (0xEEE, 0x666, 0x000))   # 가운데 무작위 칸 미리보기(쿠로코) — 실측 eee/666/000, 2P색 없음
YUGA = (((0xFBB, 0xC61, 0x402), (0xFFB, 0x570, 0x330)), ((0xFCC, 0xA38, 0x303), (0xFEB, 0xBA0, 0x420)))   # 롬 0x259310+14*0x40 [0][3] · [5][6] — 177 실측 경기 P2 팔레트 5
#   (수라 기본 FBB/C61/402 · 수라 거울 2P FFB/570/330 · 나찰 기본 FCC/A38/303 · 나찰 거울 2P FEB/BA0/420 — 설계의 수라 2P «[4] FFF/FF0/F70» 은 게임이 안 씀)
out = sys.argv[1]; jout = sys.argv[2] if len(sys.argv) > 2 else None
rows = [G.PAL1[c] for c in range(14)] + [(KUROKO, KUROKO), YUGA]
NROW = len(rows)
tab = []; dup = []
for r, row in enumerate(rows):
    B0, A0 = row[0]
    tr = []; dr = []
    for sty in range(2):
        B, A = row[sty]
        t = [B, A] + [f(B0, A0) for n, f in G.FINAL[2:]]           # 2~15 = 수라 판 값 — 두 검질 같음
        ok = []
        for i, x in enumerate(t):                                   # 165: 2P색(번호 1)은 플레이어가 고르는 목록에서 뺐다 — 늘 건너뛰고(LOOK_DUP 비트 1) 겹침 비교에도 안 넣는다
            ok.append(i == 0 or (i >= 2 and all(G.dist(x, t[j]) > 6 for j in range(i) if ok[j])))
        tr.append(t); dr.append(sum(1 << i for i in range(16) if not ok[i]))
    tab.append(tr); dup.append(dr)
names = [n for n, f in G.FINAL] + ['내 색 1', '내 색 2']
s = ['/* look_gen.py(tools/) 로 만든 컬러 표 — 고치지 말고 생성기를 고친다. 12비트 0x0BGR, [캐릭터 0~13 · 14 = 쿠로코(무작위 칸 미리보기) · 15 = 유가(상대 컬러 전용)][검질][프리셋][색 1~3].',
     '   177: 프리셋 2~15 는 검질과 무관(수라 판 값 — 수라·나찰을 골라도 같은 커스텀 컬러) · 0 기본·1 2P색만 검질마다 게임 색. Java LookTab 이 같은 값 */',
     '#define LOOK_NPRE 16', '#define LOOK_ROW_KUROKO 14', '#define LOOK_ROW_YUGA 15', '#define LOOK_NROW %d' % NROW,
     'static const uint16_t LOOK_TAB[LOOK_NROW][2][LOOK_NPRE][3] = {']
for r in range(NROW):
    s.append('   { /* %d */' % r)
    for sty in range(2):
        s.append('      { ' + ', '.join('{0x%03X,0x%03X,0x%03X}' % t for t in tab[r][sty]) + ' },')
    s.append('   },')
s.append('};')
s.append('/* 같은 캐릭터·검질에서 앞 프리셋과 거의 같은 색(채널 차 합 ≤ 6) — 옵션으로 돌릴 때 건너뛴다(캐릭터 고르기에선 두 검질 모두 같을 때만). 비트 1(2P색)은 늘 — 165 부터 안 고름(번호는 옛 기록 이름용) */')
s.append('static const uint16_t LOOK_DUP[LOOK_NROW][2] = { ' + ', '.join('{0x%04X,0x%04X}' % tuple(d) for d in dup) + ' };')
s.append('/* 이름(앱 띠·기록과 같은 순서 — Java Look.NAMES) */')
s.append('static const char *const LOOK_NAME[LOOK_NPRE + 2] = { ' + ', '.join('"%s"' % n for n in names) + ' };')
open(os.path.join(out, 'look_tab.h'), 'w').write('\n'.join(s) + '\n')
if jout:
    j = ['package com.dudu.pocketcore;', '',
         '/** tools/look_gen.py 로 만든 컬러 표(look_tab.h 와 같은 값 — tools/check_golden.py 가 본다). 고치지 말고 생성기를 고친다.',
         ' *  12비트 0x0BGR, [줄 0~13 캐릭터 · 14 쿠로코 · 15 유가][검질][프리셋 0~15][색 1~3] · 프리셋 2~15 는 두 검질 같음.',
         ' *  쓰는 곳: 쉼 견본(MenuModel — 거울전 대신 색). 상대 컬러 배정(OppLook)은 앞으로 표가 바뀌어도 색이 안 바뀌게 자기 v1 사본을 쓴다. */',
         'final class LookTab {', '    private LookTab() {}',
         '    static final int NPRE = 16, ROW_KUROKO = 14, ROW_YUGA = 15, NROW = %d;' % NROW,
         '    /** 캐릭터 번호 → 줄(유가 14 → 15 · 쿠로코 줄은 고르기 화면 무작위 칸 미리보기 전용이라 캐릭터로는 안 나온다). 범위 밖은 −1 */',
         '    static int row(int ch) { return ch == 14 ? ROW_YUGA : ch >= 0 && ch < 14 ? ch : -1; }',
         '    static final short[][][][] TAB = {']
    for r in range(NROW):
        j.append('        { ' + ', '.join('{ ' + ', '.join('{0x%03X,0x%03X,0x%03X}' % t for t in tab[r][sty]) + ' }' for sty in range(2)) + ' },')
    j.append('    };')
    j.append('    static final int[][] DUP = { ' + ', '.join('{0x%04X,0x%04X}' % tuple(d) for d in dup) + ' };')
    j.append('}')
    open(os.path.join(jout, 'LookTab.java'), 'w').write('\n'.join(j) + '\n')
print('rows', NROW, 'names', len(names))
for r in range(NROW): print(r, ['%04x' % d for d in dup[r]])
