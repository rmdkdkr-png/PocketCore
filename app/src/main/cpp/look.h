/* 모든 모드 컬러(look.c) — 1인 대전·무한 대전·연습 대전·리그 대전 어디서나 캐릭터·검질 고르기에서 OPTION = 다음 컬러(길게 = 컬러 만들기),
   캐릭터 고르기에서 B = 2P색으로 결정, 오른쪽 세로 이름 칸 = 컬러 이름. 경기 중엔 코어 팔레트 바꿔 쓰기로 P1 몸(스프라이트 팔레트 0)을 고른 색으로.
   허브(league.c)와 상관없이 매 진짜 프레임 돈다 — 허브가 고르기 화면 입력을 쓰는 동안(상대 선택·대신 누르기)만 ctx = BUSY 로 손을 뗀다. */
#pragma once
#include <stddef.h>
#include <stdint.h>
#define LOOK_NIDX   18      /* 0 기본 · 1 2P색 · 2~15 프리셋 · 16·17 내 색 1·2(캐릭터마다 — 두 검질 같이) */
#define LOOK_NSLOT  16      /* 기억 칸: 0~13 캐릭터 · 14 안 씀(유가) · 15 가운데 무작위 칸 */
#define LOOK_NSTAT  14
/* look_tick 의 ctx — 허브가 고르기 화면 입력을 쓰는 중(league_hub_owns_select)이면 BUSY: 컬러는 손대지 않는다 */
#define LOOK_CTX_FREE 0
#define LOOK_CTX_BUSY 1
void look_enable(int on);                               /* Java: 사쇼2 확인한 롬 판(일반 판 practiceRomOk · 리그 판) + 설정 «컬러 고르기» 켬 */
void look_vram(void *chr, void *scr, void *pal);        /* league_vram 이 넘겨준다(세로 칸은 vpanel 로) */
void look_palremap_fn(void *fn);                        /* 코어의 retro_ngp_palremap(옛 코어면 NULL — 프레임 뒤 고쳐 쓰기만) */
/* 진짜 프레임 한 번(retro_run) 뒤, 코어 잠금 안에서 league_tick_hub 바로 다음에. input = 이번 프레임 사용자 입력(RETRO_DEVICE_ID 비트) */
void look_tick(uint8_t *ram, size_t n, int input, int ctx);
void look_set_mem(const signed char *mem, int n);       /* 캐릭터마다 기억한 컬러(Java 가 저장한 값) */
int  look_get_mem(signed char *mem, int n);             /* 돌려줌 = 기억 바뀐 횟수 */
void look_set_custom(int ch, int k, int c1, int c2, int c3);   /* 내 색 k(0·1) — 세 색 12비트 0x0BGR, c1 < 0 이면 지움 */
void look_set_match(int idx);                           /* 이 경기 컬러를 정해 둔다(리그 체크포인트 이어 하기) — −1·0 = 정하지 않음 */
void look_edit(int on, int c1, int c2, int c3);         /* 컬러 만들기 창: 고르기 화면 미리보기를 이 색으로(on 0 = 끝) — 그동안 게임 입력은 모두 막는다 */
/* [켜짐, 고르기 화면을 쥠, 기억 바뀐 횟수, 보이는 컬러, 경기 컬러(−1), 경기 캐릭터(−1 = 0x57D5 로), 2P색 결정 중, 만들기 요청 횟수,
    검질 고르기 검질(−1), 경기 컬러 바뀐 횟수, 만드는 중, 프레임, 만들 수 있는 캐릭터(쥔 고르기 화면의 캐릭터 0~13 — 쿠로코·무작위면 −1), 미리보기 검질] */
void look_status(int v[LOOK_NSTAT]);
int  look_cur(void);                                    /* 고르기 화면에 보이는 컬러(허브 상태 v[10] · 리그 기록) */
void look_triples(int ch, int sty, int out[LOOK_NIDX * 3]);   /* 그 캐릭터·검질의 18색 × 세 색(없는 내 색 = 기본) — 만들기 창의 시작 색 */
void look_before_save(void);                            /* 상태 저장 직전 — 세로 칸·팔레트를 원래대로(다음 프레임에 다시 바꾼다) */
size_t look_blob(void *buf, size_t cap);                /* 상태 옆 .look(16바이트) — 경기 컬러 없으면 0 */
void look_resume(const void *buf, size_t n);            /* 불러온 뒤 — NULL = 없음(경기 컬러를 잊는다) */
void look_forget(void);                                 /* 코어를 새로 올림·리셋·상태 불러오기 — 경기 컬러·화면 칸을 잊는다(기억·내 색은 그대로) */
/* 입력 콜백: (사용자 입력 & ~(… | look_in_block)) | … | look_in_or  (다음 프레임) */
extern volatile int look_in_block, look_in_or;
