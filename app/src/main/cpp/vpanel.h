/* 캐릭터·검질 고르기 오른쪽 세로 이름 칸(둘째 판 3~11줄 × 17·18칸)과 그 위 문양 칸(0~1줄 × 17·18칸 — 165 베기 색 아이콘) — league.c(상대 선택의
   배경 이름)·look.c(내 캐릭터의 컬러 이름·베기 색)가 같이 쓰는 칸의 주인 하나. 부르는 쪽은 모두 코어 잠금 안(GL 스레드 retro_run 뒤 · 상태 저장 직전)에서만
   부른다(따로 안 잠근다). */
#pragma once
#include <stdint.h>
void vpanel_vram(void *chr, void *scr);
void vpanel_pal(void *pal);                     /* 팔레트 램(0x8200~ — 둘째 판 팔레트는 +0x100) — look_vram 이 넘긴다 */
int  vpanel_layout(void);                       /* 지금 화면에 그 칸이 있나(위아래 테 + 18칸이 이름 타일이거나 우리 타일) */
void vpanel_put(const uint8_t *tiles);          /* 글자 그림 18타일(9줄 × 2칸, 줄 먼저) → 빈 타일 0x130~0x141 + 칸이 그 타일을 가리키게 */
/* 베기 색 아이콘 — vpanel_layout() 이고 0~1줄 × 17·18칸이 문양(캐릭터 고르기 0x168~0x16B · 검질 고르기 0x198~0x19B)이거나 우리 칸이면 1 */
int  vpanel_icon_layout(void);
/* 아이콘 4타일(64바이트) + 세 색(12비트 0x0BGR, 색 1~3) → 빈 타일 0x142~0x145 · 둘째 판 팔레트 하나(7·6·5·4·3·2·13 중 둘째 판 칸이 안 쓰는 첫 번째)
   · 칸 4개. 매 프레임 불러도 된다(바뀐 것만 쓴다). 돌려줌 = 그렸나 */
int  vpanel_icon_put(const uint8_t *tiles4, const uint16_t c[3]);
void vpanel_restore(void);                      /* 이름 칸·아이콘 — 칸·팔레트·타일 그림을 원래 값으로(아직 우리 것일 때만) */
void vpanel_forget(void);                       /* 화면이 통째로 바뀌었다(상태 불러오기·코어 올림) — 되돌리지 않고 잊는다 */
int  vpanel_on(void);                           /* 이름 칸이나 아이콘을 빌리고 있나 */
int  vpanel_icon_pal(void);                     /* 아이콘이 빌린 둘째 판 팔레트(−1 없음) — 시험·상태 */
int  vpanel_menu_kind(void);                    /* 타이틀 메뉴가 떠 있나 — 1 한글판 · 2 순정판 · 0 아님 */
