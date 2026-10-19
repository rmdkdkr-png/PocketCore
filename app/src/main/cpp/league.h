/* 가상 리그 — 사무쇼2 랭크전의 대진 덮어쓰기·최종 결과 감지 (league.c). */
#pragma once
#include <stddef.h>
#include <stdint.h>

/* 진짜 프레임 한 번(retro_run) 뒤, 코어 잠금 안에서 부른다. ram = SYSTEM_RAM(16KB, CPU 0x4000~). */
void league_tick(uint8_t *ram, size_t n);
/* 게임 속 리그(허브)까지 — input = 이번 프레임 사용자 입력(RETRO_DEVICE_ID 비트), 화면 글자를 바꾸려면 먼저 league_vram */
void league_tick_hub(uint8_t *ram, size_t n, int input);
/* 코어 문자 램·스크롤 램(글자 바꾸기)·팔레트 램(컬러 — look_vram 에 넘김) */
void league_vram(void *chr, void *scr, void *pal);
/* 코어의 retro_ngp_palremap(없으면 NULL) — 컬러를 게임이 팔레트를 쓰는 순간 바꿔 넣어 깜빡임 없이(look_palremap_fn 에 넘김) */
void league_palremap_fn(void *fn);
#define LEAGUE_NHUB 15   /* 늘리지 않는다 — 고정 크기로 받는 하네스가 있다(153 자동 진행·VS 띠는 따로: league_hub_autostatus · league_hub_vs_status) */
void league_hub(int on, const signed char *sty, const signed char *dif);
void league_hub_return(int rematch);
void league_hub_cancel(void);
void league_hub_status(int v[LEAGUE_NHUB]);
/* 게임 속 리그 배경(home = 캐릭터마다 상대 기본 배경 0~7·없으면 −1, stage_sel −1 상대 기본·−2 무작위·0~7). color 는 안 씀(look.c) */
void league_hub_look(const signed char *home, int stage_sel, int color);
/* 둘 다(실행 창 «리그·연습») — 셋째 줄 ◀▶ 로 연습·리그. 다른 판의 모드를 고르면 갈아타기 요청(league_hub_dual_status v[2]) */
#define LEAGUE_NDUAL 6
void league_hub_dual(int on);
void league_hub_dual_status(int v[LEAGUE_NDUAL]);
int  league_hub_menu(void);     /* 168 고침(K5): 지난 프레임 타이틀 메뉴 글자 — 0 아님 · 1 한글판 · 2 순정판 */
void league_hub_handoff_clear(void);
/* 갈아탄 리그 판 — 로고·메뉴를 넘겨 바로 상대 선택까지 */
void league_hub_go(void);
/* 랭크 매치(끝없는 매칭) — 셋째 줄 A = 상대 찾기(H_SEARCH 6), 정해진 상대로 «상대 선택» 없이 내 캐릭터 고르기 */
void league_hub_ranked(int on);
void league_hub_search(int on);
int  league_hub_match(int ch, int sty, int diff, int stage, int lv, int cap, int atk, int dfn, int floor, int go);
int  league_hub_take_ev(void);
/* 153 VS 띠 — 다음 경기 무대 인트로 위 검은 띠에 Java 가 그린 타일(league.c 설명). tiles NULL = 끄기 */
#define LEAGUE_VS_THRU_BANNER 1
int  league_hub_vs(const uint8_t *tiles, int n, const uint16_t *map, int hold, int flags);
#define LEAGUE_NVS 3
void league_spr(void *spr);   /* 153 — 스프라이트 램(VS 띠 겹침 검사). native.c 가 league_vram 옆에서 */
void league_hub_vs_status(int v[LEAGUE_NVS]);   /* [단계|0x10 얹힘|0x20 겹침 포기, 대사 단계 프레임, hold] */
/* 다음 league_arm 의 CPU 손잡이(시작 단계 0x5814 · 상한 0x5815 · 공격 배율 0x57E2 · 받는 피해 배율 0x57E4 · 경기 중 실시간 단계 바닥 0x5CF5, −1 그대로) — 한 번 쓰면 비움 */
void league_cpu(int lv, int cap, int atk, int dfn, int floor);
/* 153 랭크 자동 진행 — 내 캐릭터(칸 0~13 · 15 무작위)·검질·카드 칸 두 개(−1 없음)로 go 부터 무대 인트로까지 입력 없이(league.c 설명).
   flags 2 = tail(on 0: 직접 고른 판도 이야기부터 자동 · 내 고르기 기록) · 4 = 단계 1~5 동안 4배속·소리 끔. 다음 go 부터. 1 = 받음.
   b2p 는 안 씀(165 — 플레이어 2P색 없음, 서명은 그대로) */
int  league_hub_autopick(int on, int cell, int sty, int b2p, int k1, int k2, int flags);
#define LEAGUE_NMYPICK 8
void league_hub_mypick(int v[LEAGUE_NMYPICK]);     /* [있음, 칸, 캐릭터, 검질, 늘 0(옛 2P색), 카드 1, 2, 경기 컬러] */
/* 168 랭크 «내 캐릭터 먼저» — 상대 없이(무장 안 함) 1인 대전 고르기(허브 상태 9). 이야기가 시작되면 고른 것은 league_hub_mypick 에(그 뒤 입력을 막고 멈춰 둠).
   1 = 받음(허브 종류 0 · 타이틀·찾기). 상태 [요청 대기, 고르는 판, 고른 것을 남김, 그 판 프레임(−1)] */
#define LEAGUE_NPO 4
int  league_hub_pick_only(void);
void league_hub_pick_only_status(int v[LEAGUE_NPO]);
#define LEAGUE_NAUTO 5
void league_hub_autostatus(int v[LEAGUE_NAUTO]);   /* [단계 0~8(6 무대 인트로 · 8 막힘), go 뒤 프레임, go→장면, go→첫 라운드, 막힌 단계] */
/* 153: native.c 가 프레임마다(잠금 없이) — 1 = 코어 4배속(pace 배속) · 1 = 게임 소리를 버린다(자동 진행 단계 1~5 · −4 리셋부터, autopick flags 4) */
extern volatile int league_ff, league_mute;
/* 허브가 게임 대신 받는 입력 비트 — 입력 콜백은 사용자 입력에서 이 비트를 지운다 */
extern volatile int league_in_block;
/* 사용자 입력에 더할 비트(연습 대전: 이야기·대사 넘기기 A) — 입력 콜백 = (입력 & ~막는 비트) | 더할 비트 */
extern volatile int league_in_or;
/* 연습 대전 허브(종류 1) — 일반 세션에서 확인한 롬 판일 때만 Java 가 켠다. autostart = 메뉴 «연습 대전» → 상대 선택까지 대신 누름 */
void league_practice_hub(int on, int autostart);
/* 연습 경기 중인가(허브 H_MATCH) — 더미 캐릭터(0~14)·검질(0 수라 · 1 나찰) */
int  league_practice_match(int *dummy, int *sty);
/* 치트가 묻는다 — 1 리그가 쥔 세션(게임 속 리그·랭크전 한 경기 — 치트 아무것도 안 씀) · 2 연습 대전 흐름(경기 치트 쉼) · 0 그 밖 */
int  league_session_owner(void);
/* 연습 메뉴 «다시 고르기»(1)·«타이틀로»(0) — 코어 리셋 바로 뒤에 */
void league_practice_return(int repick);
/* 상태 불러오기·앱 리셋 뒤 — 연습 허브를 타이틀 상태로 */
void league_practice_title(void);
/* 상태 저장 직전 — 허브가 바꿔 그린 머리말·세로 칸을 원래대로(다음 프레임에 다시 그린다) */
void league_hub_before_save(void);
/* 연습 경기 이어가기 — 연습 경기 중이면 허브를 buf 에 담고 크기(아니면 0). 불러온 뒤 league_practice_resume(담은 것, 크기 · 없으면 NULL, 0) */
size_t league_practice_blob(void *buf, size_t cap);
void league_practice_resume(const void *buf, size_t n);

#define LEAGUE_NSTAT 26
#define LEAGUE_CARDS_OFF 1   /* 사용자 카드 없음(0xFF) */
#define LEAGUE_ALLCARDS  2   /* 올카드 해금(소유 개수 8) */
#define LEAGUE_AUTONAV   4   /* 새로 켠 판 — 1인 대전 캐릭터 고르기까지 메뉴 자동 진입 */
#define LEAGUE_FROM_CKPT 8   /* 체크포인트에서 재개 — 이미 서 있는 결판 bit7 도 이 경기 결과로 잡는다 */
void league_arm(int opp, int sty, int diff, int points, int timev, int flags);
/* 다음 league_arm 에 쓸 배경(0~7, −1 게임 그대로)·경기 컬러(0~17 — look_set_match, −1 = 고르기 화면에서 정함) */
void league_look(int stage, int color);
/* 메뉴 자동 진입 중이면 on=1, 입력 콜백은 사용자 입력 대신 mask 를 돌려준다 */
extern volatile int league_ovr_on, league_ovr_mask;
void league_disarm(void);
/* 결판 뒤 승리 대사(149) — 결판을 잡은 뒤에도 게임은 이긴 쪽 이름판·대사(0x5825 가 서기 전)까지 돈다(그동안 게임 입력은 모두 막는다).
   0x5825 가 서는 프레임(·새로 누른 B·건너뛰기·장면 이탈·시간 끝)에 «멈춤 걸쇠»가 선다 — native.c 는 걸쇠가 서 있으면 다음 프레임을 돌리지 않는다.
   걸쇠는 무장 해제(disarm)로는 안 풀리고 리셋·허브 돌려보내기·새 무장·새 허브·연습 허브·코어 올림/내림으로만 풀린다.
   post_skip = 뒤로 가기(다음 진짜 프레임에 걸쇠) · post_clear = 리셋(nativeReset) */
int  league_post_hold(void);
void league_post_skip(void);
void league_post_clear(void);
void league_status(int v[LEAGUE_NSTAT]);
/* 허브가 고르기 화면 입력을 쓰는 중(상대 선택·대신 누르기)이면 1 — look_tick 의 ctx */
int league_hub_owns_select(void);
/* look_tick 의 ctx — BUSY(위와 같음) · HOLD(연습 «내 캐릭터 먼저»: 컬러는 look, 결정은 허브) · FREE */
int league_hub_look_ctx(void);
