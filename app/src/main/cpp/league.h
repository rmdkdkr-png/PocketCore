/* 가상 리그 — 사무쇼2 랭크전의 대진 덮어쓰기·최종 결과 감지 (league.c). */
#pragma once
#include <stddef.h>
#include <stdint.h>

/* 진짜 프레임 한 번(retro_run) 뒤, 코어 잠금 안에서 부른다. ram = SYSTEM_RAM(16KB, CPU 0x4000~). */
void league_tick(uint8_t *ram, size_t n);
/* 게임 속 리그(허브)까지 — input = 이번 프레임 사용자 입력(RETRO_DEVICE_ID 비트), 화면 글자를 바꾸려면 먼저 league_vram */
void league_tick_hub(uint8_t *ram, size_t n, int input);
/* 코어 문자 램·스크롤 램(글자 바꾸기)·팔레트 램(컬러) */
void league_vram(void *chr, void *scr, void *pal);
/* 코어의 retro_ngp_palremap(없으면 NULL) — 컬러를 게임이 팔레트를 쓰는 순간 바꿔 넣어 깜빡임 없이 */
void league_palremap_fn(void *fn);
#define LEAGUE_NHUB 12
void league_hub(int on, const signed char *sty, const signed char *dif);
void league_hub_return(int rematch);
void league_hub_cancel(void);
void league_hub_status(int v[LEAGUE_NHUB]);
/* 게임 속 리그 배경·컬러(home = 캐릭터마다 상대 기본 배경 0~7·없으면 −1, stage_sel −1 상대 기본·−2 무작위·0~7, color 0~3) */
void league_hub_look(const signed char *home, int stage_sel, int color);
/* 둘 다(실행 창 «리그·연습») — 셋째 줄 ◀▶ 로 연습·리그. 다른 판의 모드를 고르면 갈아타기 요청(league_hub_dual_status v[2]) */
#define LEAGUE_NDUAL 6
void league_hub_dual(int on);
void league_hub_dual_status(int v[LEAGUE_NDUAL]);
void league_hub_handoff_clear(void);
/* 갈아탄 리그 판 — 로고·메뉴를 넘겨 바로 상대 선택까지 */
void league_hub_go(void);
/* 허브가 게임 대신 받는 입력 비트 — 입력 콜백은 사용자 입력에서 이 비트를 지운다 */
extern volatile int league_in_block;
/* 사용자 입력에 더할 비트(연습 대전: 이야기·대사 넘기기 A) — 입력 콜백 = (입력 & ~막는 비트) | 더할 비트 */
extern volatile int league_in_or;
/* 연습 대전 허브(종류 1) — 일반 세션에서 확인한 롬 판일 때만 Java 가 켠다. autostart = 메뉴 «연습 대전» → 상대 선택까지 대신 누름 */
void league_practice_hub(int on, int autostart);
/* 연습 경기 중인가(허브 H_MATCH) — 더미 캐릭터(0~14)·검질(0 수라 · 1 나찰) */
int  league_practice_match(int *dummy, int *sty);
/* 연습 메뉴 «다시 고르기»(1)·«타이틀로»(0) — 코어 리셋 바로 뒤에 */
void league_practice_return(int repick);
/* 상태 불러오기·앱 리셋 뒤 — 연습 허브를 타이틀 상태로 */
void league_practice_title(void);
/* 상태 저장 직전 — 허브가 바꿔 그린 머리말·세로 칸을 원래대로(다음 프레임에 다시 그린다) */
void league_hub_before_save(void);
/* 연습 경기 이어가기 — 연습 경기 중이면 허브를 buf 에 담고 크기(아니면 0). 불러온 뒤 league_practice_resume(담은 것, 크기 · 없으면 NULL, 0) */
size_t league_practice_blob(void *buf, size_t cap);
void league_practice_resume(const void *buf, size_t n);

#define LEAGUE_NSTAT 21
#define LEAGUE_CARDS_OFF 1   /* 사용자 카드 없음(0xFF) */
#define LEAGUE_ALLCARDS  2   /* 올카드 해금(소유 개수 8) */
#define LEAGUE_AUTONAV   4   /* 새로 켠 판 — 1인 대전 캐릭터 고르기까지 메뉴 자동 진입 */
#define LEAGUE_FROM_CKPT 8   /* 체크포인트에서 재개 — 이미 서 있는 결판 bit7 도 이 경기 결과로 잡는다 */
void league_arm(int opp, int sty, int diff, int points, int timev, int flags);
/* 다음 league_arm 에 쓸 배경(0~7, −1 게임 그대로)·내 컬러(0 기본 · 1 2P색 · 2·3 만든 색) */
void league_look(int stage, int color);
/* 메뉴 자동 진입 중이면 on=1, 입력 콜백은 사용자 입력 대신 mask 를 돌려준다 */
extern volatile int league_ovr_on, league_ovr_mask;
void league_disarm(void);
void league_status(int v[LEAGUE_NSTAT]);
