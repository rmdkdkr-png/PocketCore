#!/usr/bin/env python3
"""베기 색 표 생성기 — slash_tab.h (롬의 색 번호만 — 그림 없음, look_tab.h 와 같은 성격)
사용: python3 slash_gen.py <롬> <앱>/app/src/main/cpp
  SLASH_SET[64][4][3]  라운드 로더(0x223D8E)가 쓰는 효과 팔레트 +1~+4 — 키 = (캐릭터<<2 | 검질<<1 | 2P) = 0x5B51 >> 2,
                       목록 u32 0x223E5D + 키×4 → u16 오프셋(끝 0xFFFF, 첫 칸 = 몸) → 은행 [0x250038](= 0x259190) 의 색 1~3. 없는 칸 = 0xFFFF
  SLASH_BA             공용 «베인 섬광»(ACT 04/08, 은행 팔레트 0xBA) — 맞은 쪽 공용 칸(P1 이 벤 섬광 = 팔레트 11)
  SLASH_6B             한조가 기술 중 +1 에 쓰는 은행 팔레트 0x6B(갈포드 포피도 쓰지만 짝꿍이라 안 씀)
  SLASH_DYN1_UKYO      우쿄가 기술 중 +1 에 쓰는 세 색(기술 대본 0x228CFF → 0x222F15, u162 census)
롬 6판(한글 동봉·KO101·H1·H1+KO101·H1+동봉·순정 JUE) 이 영역 바이트가 같다(u162 census · 이 생성기로 다시 확인)."""
import sys, os
ROM = sys.argv[1]; OUT = sys.argv[2]
r = open(ROM, 'rb').read()
def b(a): return r[a - 0x200000]
def w(a): return b(a) | (b(a + 1) << 8)
def d(a): return w(a) | (w(a + 2) << 16)
bank = d(0x250038)
def tri(off): return tuple(w(bank + off + 2 * j) & 0xFFF for j in range(1, 4))
def tri_n(n): return tri(n * 8)
NM = ["카즈키", "소게츠", "하오마루", "겐쥬로", "나코루루", "리무루루", "한조", "갈포드", "아수라", "샬럿", "모로즈미", "우쿄", "주베이", "시키", "유가", "쿠로코"]
rows = []
for key in range(64):
    ptr = d(0x223E5D + key * 4); lst = []; a = ptr
    while w(a) != 0xFFFF and len(lst) < 8: lst.append(w(a)); a += 2
    eff = [tri(o) for o in lst[1:5]]
    while len(eff) < 4: eff.append(None)
    rows.append((key, ptr, eff))
L = ['/* slash_gen.py 로 만든 베기 색 표 — 고치지 말고 생성기를 고친다. 롬 색 번호(12비트 0x0BGR)만, 은행 0x%06X */' % bank,
     '#pragma once', '#include <stdint.h>', '#define SLASH_NONE 0xFFFF',
     '/* [키 = 0x5B51 >> 2 = 캐릭터<<2 | 검질<<1 | 2P][자리 +1~+4 = 스프라이트 팔레트 1~4 (P2 는 6~9)][색 1~3] */',
     'static const uint16_t SLASH_SET[64][4][3] = {']
for key, ptr, eff in rows:
    L.append('   /* %02X %-4s %s %s */ {%s},' % (key, NM[key >> 2], 'SR'[(key >> 1) & 1], '2P' if key & 1 else '1P',
             ', '.join('{0x%03X,0x%03X,0x%03X}' % e if e else '{SLASH_NONE,SLASH_NONE,SLASH_NONE}' for e in eff)))
L.append('};')
L.append('static const uint16_t SLASH_BA[3] = { 0x%03X, 0x%03X, 0x%03X };          /* 베인 섬광(은행 팔레트 0xBA) */' % tri_n(0xBA))
L.append('static const uint16_t SLASH_6B[3] = { 0x%03X, 0x%03X, 0x%03X };          /* 한조 기술 중 +1(은행 팔레트 0x6B) */' % tri_n(0x6B))
L.append('static const uint16_t SLASH_DYN1_UKYO[3] = { 0x7FF, 0x25F, 0x000 };   /* 우쿄 기술 중 +1 */')
open(os.path.join(OUT, 'slash_tab.h'), 'w').write('\n'.join(L) + '\n')
print('ok bank %06X BA %s 6B %s' % (bank, '/'.join('%03X' % c for c in tri_n(0xBA)), '/'.join('%03X' % c for c in tri_n(0x6B))))
