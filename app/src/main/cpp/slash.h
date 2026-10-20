/* 베기 색(slash.c) — 칼 궤적·내가 벤 섬광의 색을 프리셋으로(177 부터 필살 효과는 원래 색 — slash.c slash_build 앞 주석). look.c 가 코어 팔레트 바꿔 쓰기 표의 유일한 주인이고,
   여기는 순수 함수(잠금 없음 — look 의 K_mu 안에서만 불린다): 표 만들기 · 프레임 뒤 보정 · 저장 앞뒤 되돌림/다시 씀 · 아이콘 색.
   스프라이트 팔레트(u162 census 실측): 0 P1 몸 · 1~4 P1 효과(+1~+4) · 5 P2 몸 · 6~9 P2 효과 · 10 P1 이 맞은 쪽 공용(P2 가 벤 섬광) ·
   11 P2 가 맞은 쪽 공용(P1 이 벤 섬광) · 12 그림자. +2~+4 는 라운드 로더(0x223D8E)가 라운드마다 목록(slash_tab.h SLASH_SET)에서, +1 은 기술 대본도 쓴다. */
#pragma once
#include <stddef.h>
#include <stdint.h>
#define SLASH_N    7        /* 0 원래 색 · 1 청염 · 2 핏빛 · 3 비취 · 4 벚꽃 · 5 흑염 · 6 서리 (저장 값 = A+B 로 돌리는 순서) */
#define SLASH_NPAL 12       /* 스프라이트 팔레트 0~11 — look 의 바꿔 쓰기 표와 같은 모양(0 몸 · 5 P2 몸은 look 몫, 여기선 안 씀) */
#define SLASH_NSLOT 2       /* 동봉 코어(0966bed)의 바꿔 쓰기 자리 수 — 색 칸마다 2 */
typedef struct { int16_t from[3], to[3]; } slash_pair;   /* from[0] < 0 = 빈 자리 */
typedef struct { slash_pair s[SLASH_NPAL][SLASH_NSLOT]; } slash_want;
extern const char *const SLASH_NAME[SLASH_N];
void slash_clear(slash_want *w);
/* 경기 쪽 창 — (MODE 241 이고 이야기 序章(SCR 0 · FIGHT 0)이 아님) 또는 (MODE 240 이고 SCR ≥ 8 = 전투 로드·인트로·라운드 사이).
   고르기·카드(SCR 2/4/6)는 미리보기가 팔레트 1~4 에 커서 캐릭터 효과 색을 실어서, 序章은 P1 블록(0x5B51)이 앞 판 값이라 뺀다(실측) */
int  slash_window(const uint8_t *ram);
/* side 0 = P1(키 0x5B51 → 팔레트 1~4 · 내가 벤 섬광 11) · 1 = P2(키 0x5D51 → 6~9 · 10 — 상대 커스텀 컬러 때. 이번엔 안 부름).
   preset 0 이나 범위 밖이면 그 쪽은 비움. w 의 다른 쪽 칸은 건드리지 않는다(두 쪽을 한 표에 — 부르는 쪽이 먼저 slash_clear) */
void slash_build(const uint8_t *ram, int side, int preset, slash_want *w);
/* 고르기 화면 아이콘 세 색(core · mid · edge) — 프리셋 1~6 = 그 T, 0 = 그 캐릭터·검질의 원래 +2 궤적을 밝기 순으로(+2 가 어두우면 +1·+3 —
   아수라 · look_row 14·범위 밖·모두 어두움 = 보통 금빛) */
void slash_icon(int preset, int look_row, int sty, uint16_t out[3]);
/* 프레임 뒤 보정 — 자리마다 지금 세 색이 그 자리의 from/to 로만 되어 있고 from 이 하나라도 있으면 to 를 쓴다(동봉 코어의 «윗바이트마다 판정»이
   표가 늦게 오거나 같은 값으로 다시 쓰일 때 to1/from2/from3 으로 굳는 것 · 불러온 상태 · 켠 순간). 돌려줌 = 고친 팔레트 수 */
int  slash_fix(uint8_t *pal, const slash_want *w);
void slash_restore(uint8_t *pal, const slash_want *w);   /* 세 색이 to 인 팔레트 → from (상태 저장 직전 · 창을 닫을 때) */
void slash_reapply(uint8_t *pal, const slash_want *w);   /* 세 색이 from 인 팔레트 → to (저장 직후) */
int  slash_any(const slash_want *w);
