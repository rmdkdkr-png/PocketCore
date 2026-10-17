/* 153 VS 띠 — 무대 인트로 동안 판 0 위 검은 띠(0~3줄 × 0~19칸)에 Java 가 그린 글자 타일을 얹는다(vsband.c).
   부르는 쪽은 모두 코어 잠금 안(GL 스레드 retro_run 뒤 · 상태 저장 직전). */
#pragma once
#include <stdint.h>
#define VB_COLS 20
#define VB_ROWS 4
#define VB_CELLS (VB_COLS * VB_ROWS)
#define VB_MAXT 80                     /* 우리 타일 자리: 0x0D0~0x105(54) 다음 0x0B6~0x0CF(26) — vsband_slot */
int  vsband_slot(int i);               /* 띠 타일 번호 i(0~79) → 문자 램 타일 번호 */
void vsband_vram(void *chr, void *scr);
void vsband_spr(void *spr);            /* 스프라이트 램(64 × 4바이트) — 우리 자리 타일을 가리키는 스프라이트가 있으면 포기(NULL = 안 봄) */
int  vsband_set(const uint8_t *tiles, int n, const uint16_t *map);   /* 1 = 받음 — map 80칸(줄 먼저): 0xFFFF 그대로 · (팔레트<<9)|번호 */
void vsband_clear(void);
int  vsband_layout(void);              /* 띠 80칸이 게임 빈칸(0x001)·우리 칸뿐인가 */
int  vsband_put(void);                 /* 얹기·다시 얹기(매 프레임) — 1 = 얹혀 있음 */
void vsband_restore(void);             /* 거두기(우리 칸·우리 타일만 원래대로) */
void vsband_forget(void);              /* 화면이 통째로 바뀜 — 되돌리지 않고 잊는다 */
int  vsband_on(void);
int  vsband_ready(void);               /* 띠가 있고 포기하지 않았나 */
int  vsband_clash(void);               /* 우리 타일 자리를 게임이 쓰거나 스프라이트가 가리켜 포기했나 */
