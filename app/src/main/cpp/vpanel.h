/* 캐릭터·검질 고르기 오른쪽 세로 이름 칸(둘째 판 3~11줄 × 17·18칸) — league.c(상대 선택의 배경 이름)·look.c(내 캐릭터의 컬러 이름)가
   같이 쓰는 칸의 주인 하나. 부르는 쪽은 모두 코어 잠금 안(GL 스레드 retro_run 뒤 · 상태 저장 직전)에서만 부른다(따로 안 잠근다). */
#pragma once
#include <stdint.h>
void vpanel_vram(void *chr, void *scr);
int  vpanel_layout(void);                       /* 지금 화면에 그 칸이 있나(위아래 테 + 18칸이 이름 타일이거나 우리 타일) */
void vpanel_put(const uint8_t *tiles);          /* 글자 그림 18타일(9줄 × 2칸, 줄 먼저) → 빈 타일 0x130~0x141 + 칸이 그 타일을 가리키게 */
void vpanel_restore(void);                      /* 우리 타일을 가리키는 칸을 원래 값으로 */
void vpanel_forget(void);                       /* 화면이 통째로 바뀌었다(상태 불러오기·코어 올림) — 되돌리지 않고 잊는다 */
int  vpanel_on(void);
int  vpanel_menu_kind(void);                    /* 타이틀 메뉴가 떠 있나 — 1 한글판 · 2 순정판 · 0 아님 */
