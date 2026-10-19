/* 모든 모드 컬러(look.c) — 1인 대전·무한 대전·연습 대전·리그 대전 어디서나 캐릭터·검질 고르기에서 OPTION = 다음 몸 컬러(길게 = 컬러 만들기),
   A+B = 내 베기 색(칼 궤적·필살 이펙트·내가 벤 섬광) 다음 것, A·B = 결정(보통 색 — 165 부터 플레이어 2P색은 없다), 오른쪽 세로 이름 칸 = 컬러 이름,
   그 위 문양 칸 = 베기 색 아이콘. 경기 중엔 코어 팔레트 바꿔 쓰기로 P1 몸(스프라이트 팔레트 0)·P1 효과(1~4)·내가 벤 섬광(11)을 고른 색으로.
   허브(league.c)와 상관없이 매 진짜 프레임 돈다 — 허브가 고르기 화면 입력을 쓰는 동안(상대 선택·대신 누르기)만 ctx = BUSY 로 손을 뗀다. */
#pragma once
#include <stddef.h>
#include <stdint.h>
#define LOOK_NIDX   18      /* 0 기본 · 1 (옛 2P색 — 안 고름, 옛 기록 이름용) · 2~15 프리셋 · 16·17 내 색 1·2(캐릭터마다 — 두 검질 같이) */
#define LOOK_NSLOT  16      /* 기억 칸: 0~13 캐릭터 · 14 안 씀(유가) · 15 가운데 무작위 칸 */
#define LOOK_NSTAT  20      /* 177: [18]·[19] 상대 컬러 — 아래 look_status */
/* A·B·A+B 가르기(look.c gate) — A 나 B 를 누른 프레임부터 LOOK_AB_WIN 프레임 안에 다른 쪽이 눌리면 A+B(베기 색), 아니면 창 끝에 결정.
   결정은 그다음 프레임부터 LOOK_INJ_N 프레임 대신 누른다(게임 결정 = 누르고 4프레임 ≈ 67 ms 뒤). 실기에서 두 손가락 A+B 가 자주 빗나가면 5로(한 줄) */
#define LOOK_AB_WIN   4
#define LOOK_INJ_N    4
#define LOOK_INJ_DPAD 6     /* 대신 누르기 몇 프레임째까지 십자를 막나(결정 전에 커서가 옮겨 가지 않게) */
#define LOOK_TOAST_F  72    /* A+B 뒤 세로 칸에 «베기 + 이름»을 보이는 프레임(1.2초) */
/* look_tick 의 ctx — 허브가 고르기 화면 입력을 쓰는 중(league_hub_owns_select)이면 BUSY: 컬러는 손대지 않는다 */
#define LOOK_CTX_FREE 0
#define LOOK_CTX_BUSY 1
/* 허브가 «결정»만 쥔다(연습 «내 캐릭터 먼저») — 컬러·베기 색·가르기는 look 이 하되 결정을 대신 누르지 않고 look_take_confirm 으로 허브에 넘긴다 */
#define LOOK_CTX_HOLD 2
/* 153 랭크 자동 진행 — 허브가 화면을 대신 밟는 동안: look 은 FREE 처럼 보이는 색·경기 컬러·경기 베기 색을 정하되 사용자 입력은 안 본다 */
#define LOOK_CTX_AUTO 3
void look_enable(int on);                               /* Java: 사쇼2 확인한 롬 판(일반 판 practiceRomOk · 리그 판) + 설정 «컬러 고르기» 켬 */
void look_vram(void *chr, void *scr, void *pal);        /* league_vram 이 넘겨준다(세로 칸·아이콘은 vpanel 로) */
void look_palremap_fn(void *fn);                        /* 코어의 retro_ngp_palremap(옛 코어면 NULL — 프레임 뒤 고쳐 쓰기만) */
/* 진짜 프레임 한 번(retro_run) 뒤, 코어 잠금 안에서 league_tick_hub 바로 다음에. input = 이번 프레임 사용자 입력(RETRO_DEVICE_ID 비트) */
void look_tick(uint8_t *ram, size_t n, int input, int ctx);
void look_set_mem(const signed char *mem, int n);       /* 캐릭터마다 기억한 컬러(Java 가 저장한 값 — 옛 1(2P색)은 0 으로) */
int  look_get_mem(signed char *mem, int n);             /* 돌려줌 = 기억 바뀐 횟수 */
void look_set_custom(int ch, int k, int c1, int c2, int c3);   /* 내 색 k(0·1) — 세 색 12비트 0x0BGR, c1 < 0 이면 지움 */
void look_set_fx(int fx);                               /* 내 베기 색(0~6 — 하나로 기억, Java 설정 pocketcore_ss2_slash). 경기 베기 색은 고르기 화면에서 이 값을 따라간다 */
void look_set_pick(int rnd_ch);                         /* 허브가 내 캐릭터 결정을 되살린다(연습 «상대 고르기» — 리셋이 look_forget 으로 지움):
                                                           무작위 칸에서 뽑혔던 캐릭터(0~13, −1 없음 — 그 칸이면 무작위 칸 컬러 기억) */
void look_set_match(int idx);                           /* 이 경기 컬러를 정해 둔다(리그 체크포인트 이어 하기) — 2~17 만, 그 밖(0·1·−1) = 정하지 않음 */
void look_edit(int on, int c1, int c2, int c3);         /* 컬러 만들기 창: 고르기 화면 미리보기를 이 색으로(on 0 = 끝) — 그동안 게임 입력은 모두 막는다 */
int  look_take_confirm(void);                           /* HOLD(연습 «내 캐릭터 먼저»·검질): 가르기가 낸 결정을 가져가며 비운다 — 0 없음 · 1 A · 2 B(177 — 부르는 곳은 != 0 이면 결정) */
/* [0 켜짐, 1 고르기 화면을 쥠, 2 기억 바뀐 횟수, 3 보이는 컬러, 4 경기 컬러(−1), 5 경기 캐릭터(−1 = 0x57D5 로), 6 늘 0(옛 2P색 자리), 7 만들기 요청 횟수,
    8 검질 고르기 검질(−1), 9 경기 컬러 바뀐 횟수, 10 만드는 중, 11 프레임, 12 만들 수 있는 캐릭터(쥔 고르기 화면의 캐릭터 0~13 — 쿠로코·무작위면 −1), 13 미리보기 검질,
    14 베기 색(0~6), 15 A+B 로 바뀐 횟수, 16 결정 횟수(가르기), 17 경기 베기 색,
    18 상대 컬러 상태(177 — 0 칠하지 않음 · 1 주 색 · 2 거울 겹침이라 대신 색), 19 대신 색 번호(−1 · 0~15 = look_tab 프리셋 번호)] */
void look_status(int v[LOOK_NSTAT]);
int  look_cur(void);                                    /* 고르기 화면에 보이는 컬러(허브 상태 v[10] · 리그 기록) */
void look_triples(int ch, int sty, int out[LOOK_NIDX * 3]);   /* 그 캐릭터·검질의 18색 × 세 색(없는 내 색 = 기본) — 만들기 창의 시작 색 */
void look_before_save(void);                            /* 상태 저장 직전 — 세로 칸·아이콘·팔레트를 원래대로(저장된 상태엔 게임 원래 색만) */
void look_after_save(void);                             /* 상태 저장(c_serialize) 바로 뒤 — 우리 색·칸을 다시(다음 화면 한 프레임이 원래 색으로 안 나오게) */
size_t look_blob(void *buf, size_t cap);                /* 상태 옆 .look(16바이트) — 경기 컬러·경기 베기 색이 없으면 0 */
void look_resume(const void *buf, size_t n);            /* 불러온 뒤 — NULL = 없음(경기 컬러·베기 색을 잊는다) */
void look_after_load(const uint8_t *ram, size_t n);     /* look_resume 바로 뒤 — 불러온 램으로 바꿔 쓰기 표를 새로 넣고 지금 팔레트도 고친다(첫 프레임부터 우리 색) */
void look_forget(void);                                 /* 코어를 새로 올림·리셋·상태 불러오기 — 경기 컬러·화면 칸을 잊는다(기억·내 색·베기 색은 그대로) */
/* 177 상대 컬러(리그 랭크 경기만) — 스프라이트 팔레트 5(P2 몸) 자리 0 = 그 검질 기본 → 상대 색 · 자리 1 = 2P색(게임이 거울전 CPU 에 칠하는 색) → 상대 색.
   ch 0~14(14 = 유가 — look_tab 줄 15) · sty · 주 색 세 색(12비트, c1 < 0 = 끔) · 상대 베기 색 0~6(P2 효과 팔레트 6~9·P2 가 벤 섬광 10).
   같은 캐릭터 거울전에서 P1 이 보이는 세 색과 채널 차 합 ≤ OPP_CLASH 면 대신 색(OPP_FB 차례로 내 색·주 색 둘 다와 ≥ OPP_FAR 인 첫 것)을
   native 가 표에서 고른다 — 게임 짝 예외: 같은 검질 {기본, 2P색} 은 겹침으로 안 보고, 내 색이 그 검질 기본이면 2P색 후보는 FAR 와 상관없이 받는다.
   칠하는 때: 켜짐 · P2 블록(0x5D51) 캐릭터 == ch · P1·P2 블록 둘 중 하나는 0 아님(리셋 직후 부팅 프레임) · 타이틀·고르기·검질·카드(MODE 240 SCR 0·2·4·6) 아님 ·
   live(native.c 가 look_tick 바로 앞에 league_opp_live 로 — 랭크 경기 무장·승리 대사·걸쇠, 연습 제외) 또는 불러온 뒤 OPP_GRACE 프레임.
   내 «컬러 고르기»가 꺼져 있어도 칠한다(상대 정체성 — 코어를 올리고 내릴 때 끈다). look_forget 은 끄지 않는다(−4 돌려보내기·리셋·불러오기마다 불림) */
#define OPP_CLASH 9
#define OPP_FAR   13
#define OPP_GRACE 120
void look_set_opp(int ch, int sty, int c1, int c2, int c3, int fx);
void look_set_opp_live(int live);                       /* native.c — 코어 잠금 안, look_tick 바로 앞(L_mu 를 풀고 난 뒤 K_mu) */
/* 입력 콜백: (사용자 입력 & ~(… | look_in_block)) | … | look_in_or  (다음 프레임) */
extern volatile int look_in_block, look_in_or;
