/* 가상 리그 — 사무쇼2 랭크전 한 판의 «대진 덮어쓰기»와 «최종 결과 감지».

   기획서(NGP 사무라이 쇼다운 2 가상 리그 v1) 4장의 두 검증 항목을 실행부에서 한다.
   주소는 CustumApKS handoff/ss2_league/0단계_주소탐색_결과.md 에서 확정한 것만 쓴다
   (순정 ss2.ngc·ss2_h1.ngc 와 한글패치 v1.01·동봉판 사본에서 같게 동작함을 실측).
   지원하지 않는 롬에서는 Java 가 아예 무장(arm)하지 않는다 — 여기서는 롬을 가리지 않는다.

   매 «진짜» 프레임(retro_run 한 번 뒤, 코어 잠금 안)에 league_tick 이 불린다.
   런어헤드·프레임 생성의 숨은 프레임은 코어 안에서 되돌려지므로 여기 오지 않는다.

     대진: 결판 전까지 매 프레임 0x57DF(상대 id)·0x57E0(유파)를 쓴다. 게임은 상대를 뽑는 프레임에
           한 번 써 넣으므로 그 뒤 첫 프레임에 다시 덮이고, 전투 로드(0x5D51) 때는 이미 우리 값이다.
     결과: 0x5824 의 bit7(경기 결판)이 0 인 것을 한 번 본 뒤 1 로 서는 첫 프레임 = 경기 최종 결과.
           승자는 같은 바이트의 0x40(P1)/0x20(P2), 둘 다 없으면 무승부. 승수는 0x5821/0x5822.
           «승수 == 2» 는 쓰지 않는다 — 무승부 라운드(양쪽 +1)·3:3 무승부·포인트 3·1 라운드 경기에서 틀린다.
     한 번만: 잡은 뒤에는 done 이 서고 다시 잡지 않는다(다음 경기는 Java 가 새로 무장한다).
     경기 시작: (0x40A7==241 && 0x411A==1 && 0x5824==0) 이 거짓→참이 되는 첫 프레임이 라운드 시작이고,
           그때 승수 0:0 이고 이어하기 횟수(0x5804)가 0 이면 경기 시작이다(입력은 그 2프레임 뒤부터 받는다).
           리셋 직후 남은 램으로는 서지 않는다 — 옛 «P2 블록이 목표와 같다» 조건은 남은 램에 헛발했다.
     올카드: 소유 개수(0x63BA + 8×캐릭 + 4×유파 + k, 120칸)를 8로 채운다. 게임은 부팅과 «모드를 고르는 순간»
           세이브에서 다시 읽어 덮으므로, 캐릭터·유파 고르기 화면(MODE 240 · SCR 2/4) 동안 쓴다(마감은 유파 확정 뒤 SCR 4→0).
           게임오버·설정 저장 때 이 값이 .flash 에 남으므로 랭크전은 원작과 다른 세이브 폴더를 쓴다(Java).
     메뉴 자동 진입: 새로 켠 판이면 «A → 뗐다가 → A» 로 1인 대전 캐릭터 고르기까지 대신 밟는다(그동안 사용자 입력은 막는다).
           캐릭터·유파·카드는 사용자가 고른다. 정해진 때까지 못 닿으면 손을 뗀다. */

#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <stddef.h>
#include "league.h"
#include "look.h"
#include "vpanel.h"
#include "hub_glyphs.h"
#define PG_WANT_MENU
#include "practice_glyphs.h"

#define RAM_BASE 0x4000
#define A(x) ((x) - RAM_BASE)

#define R_OPP    A(0x57DF)   /* 다음/지금 상대 캐릭터 id (0~14) */
#define R_STY    A(0x57E0)   /* 상대 유파 0 修羅 · 1 羅刹 */
#define R_CARD1  A(0x57DC)   /* P1 능력 카드 1 · 2 — 0xFF 없음 */
#define R_CARD2  A(0x57DD)
#define R_W1     A(0x5821)   /* 라운드 승수 P1 · P2 */
#define R_W2     A(0x5822)
#define R_KO     A(0x5823)
#define R_RES    A(0x5824)   /* 라운드 결과 0x40 P1 · 0x20 P2 · 0x10 무승부 라운드, +0x80 경기 결판 */
#define R_NEED   A(0x5813)   /* 이번 경기 실효 포인트(필요 승수) */
#define R_TIME   A(0x5812)   /* 이번 경기 실효 시간 */
#define R_HP1    A(0x5A46)
#define R_HP2    A(0x5C46)
#define R_P1BLK  A(0x5B51)   /* (캐릭터<<4)|(유파<<3)|(카드 미러 0x04) */
#define R_P2BLK  A(0x5D51)
#define R_MODE   A(0x40A7)   /* 240 메뉴·연출 / 241 전투측 */
#define R_SCR    A(0x41C0)   /* 메뉴 화면: 0 타이틀 · 2 캐릭터 · 4 유파 · 6 카드 */
#define R_FIGHT  A(0x411A)   /* 1 = 라운드 진행(조작 가능 직전) */
#define R_CONT   A(0x5804)   /* 이어하기 횟수 — 새 1인 대전 캐릭터 고르기에서 0 */
#define R_OPT    A(0x636A)   /* GAME OPTION: 난도·포인트·시간·대전 포인트·대전 시간 */
#define R_OWN    A(0x63BA)   /* 카드 소유 개수 120칸 */
#define R_CPUMUL A(0x57E2)   /* CPU 공격 배율 · 받는 피해 배율(16비트씩, 0x100=1.0) — 난도·장 따라 */
#define R_CPULV  A(0x5814)   /* CPU 단계 — 1인 대전 첫 경기에서 1 + 3×난도(이어하기마다 줄어듦) */
#define R_CPUCAP A(0x5815)   /* CPU 단계 상한 — 1인 대전 첫 경기 3 + 3×난도. 라운드 중 실시간 단계(P2 0x5CF5)가 약 600프레임마다 0~상한에서 다시 정해짐 */
#define R_P2LV   A(0x5CF5)   /* CPU(P2) 실시간 단계 — 전투 로드 때 0x5814 에서, 라운드 시작마다 max(0, 0x5814 + 상대 승수 − 자기 승수), 약 600프레임마다 0~상한에서 다시 */
#define R_CCARD1 A(0x57E6)   /* CPU(P2) 카드 1 · 2 — 0xFF 없음 */
#define R_CCARD2 A(0x57E7)
#define R_MCUR   A(0x624B)   /* 타이틀 메뉴 커서: 0 1인 대전 · 1 무한 대전 · 2 2인 대전(VS) · 3 카드 모음집 · 4 게임 설정 */
#define R_HOVER  A(0x4E3C)   /* 캐릭터 고르기 커서 = 캐릭터 id × 4 (가운데 무작위 칸 = 15 × 4) */
#define R_RSCR   A(0x5825)   /* «결과 화면» 코드 — 이긴 쪽 이름판·승리 대사가 사라진 바로 뒤 프레임에 C0(P1)/A0(P2)/90(무승부)로 선다(149 실측: 결판 + 320~650).
                                그 2프레임 뒤 0x57C8 = 27(이김 → 다음 장 이야기)/24(짐·무승부 → 이어하기 화면) — 여기서 멈추면 게임은 다음 장으로 안 간다 */
#define R_SEQ    A(0x57CB)   /* 결판 뒤 연출 단계(149 실측) — 63 = 이긴 쪽 이름판·대사가 뜬 동안 · 66 = 대사 끝(0x5825 2프레임 전). 표시용으로만 */
#define POST_MAX 1800        /* 0x5825 가 끝내 안 서면 결판 + 30초에 멈춘다(안전판) */
#define POST_BGRACE 60       /* 결판 + 1초 안에 새로 누른 B 는 싸우던 손(KO 뒤로 이어진 연타) — 건너뛰기로 안 센다 */
#define R_BOUT   A(0x5827)   /* 1인 대전 몇 번째 판(0~) — 배경(스테이지)은 상대가 아니라 이 값을 따른다. 0~7 = 서로 다른 배경 8개
                                (0 후지산 마을 · 1 숲속 호숫가 · 2 산 위 신사 · 3 화염 사원(불타는 절, 밤) · 4 폐허 사원(불타는 절, 낮) ·
                                5 불꽃 동굴 · 6 마계 제단 · 7 수정 신전). 판이 뜨기 전부터 매 프레임 쓰면 그 배경으로 뜬다 — CPU 단계·배율은 그대로(실측) */
#define IN_A     1           /* NGPC A = libretro JOYPAD_B(0) */
#define IN_OPT   (1 << 3)    /* NGPC OPTION = libretro START(3) */
#define IN_DOWN  (1 << 5)
#define IN_LEFT  (1 << 6)
#define IN_RIGHT (1 << 7)
#define IN_UP_DOWN ((1 << 4) | (1 << 5))
#define IN_B     (1 << 8)    /* NGPC B = libretro JOYPAD_A(8) */
#define IN_ABFOLD ((1 << 1) | (1 << 9) | (1 << 10) | (1 << 11))   /* 코어가 A+B·원버튼으로 접는 패드 비트(Y·X·L·R) — A·B 를 막을 땐 같이 막는다 */

/* 메뉴 자동 진입 — native.c 의 입력 콜백이 이 둘을 본다(다음 프레임 입력). NGPC A = libretro JOYPAD_B(0). */
volatile int league_ovr_on = 0, league_ovr_mask = 0;
/* 사용자 입력에 «더할» 비트(다음 프레임) — 연습 대전에서 카드 화면 뒤 이야기·대사를 넘기는 A. 입력 콜백 = (입력 & ~막는 비트) | 더할 비트 */
volatile int league_in_or = 0;

static pthread_mutex_t L_mu = PTHREAD_MUTEX_INITIALIZER;
static struct {
   int armed;
   int opp, sty;            /* 대진 목표 */
   int diff, points, timev; /* 옵션 — 음수면 안 건드린다 */
   int cards_off;           /* 1 = 사용자 카드 없음(0xFF) */
   int allcards;            /* 1 = 올카드 해금 */
   int autonav;             /* 1 = 메뉴 자동 진입 중 */
   unsigned nav_ok;         /* 캐릭터 고르기 화면이 이어진 프레임 수 */
   int seen_open;           /* 무장 뒤 bit7 이 0 인 걸 봤나 */
   int rs_prev;             /* 지난 프레임의 라운드 시작 조건 */
   int fight_seen;          /* 경기 시작(첫 라운드, 0:0)을 봤나 */
   int done;                /* 최종 결과를 잡았나 */
   int bits, w1, w2, ko, need, timev_eff, p1blk, p2blk, p2blk_first, card1, card2, cpu_lv, cpu_atk, cpu_def;
   unsigned frames, fight_frame, done_frame;
   int stage;               /* 배경 0~7(R_BOUT 에 쓴다), −1 = 게임 그대로(1판 배경) */
   int color;               /* 안 씀(늘 0) — 컬러는 look.c. 연습 이어하기 덩어리(.prac)가 sizeof L 그대로라 칸은 남긴다 */
   int k_lv, k_cap, k_atk, k_dfn, k_floor;   /* 랭크 매치 CPU 세기 손잡이 — k_floor = 경기 중 실시간 단계 0x5CF5 바닥(맞을수록 내려가는 게임 적응을 이 아래로는 안 둠) — 시작 단계 0x5814 · 상한 0x5815 · 공격 배율 0x57E2 · 받는 피해 배율 0x57E4 (−1 = 게임 값 그대로) */
   int cap_seen;            /* 결판 때 0x5815 */
   int from_ckpt;           /* 체크포인트에서 이어 하는 경기(경기 시작은 이미 지남 — 바닥 손잡이를 바로 쓴다) */
} L;
#define NKNOB 5
static int knob_next[NKNOB] = { -1, -1, -1, -1, -1 };   /* 다음 league_arm 에 쓸 손잡이(league_cpu) — 한 번 쓰면 비운다 */
/* 결판 뒤 승리 대사(149) — L 과 따로(Java 가 정산 뒤 disarm 해도 이어 돈다). .prac 덩어리(H·L)엔 안 들어간다 */
enum { PQ_NONE = 0, PQ_QUOTE, PQ_SKIP, PQ_SCENE, PQ_TIMEOUT, PQ_API };
static struct { int on, hold, reason, prev_in, seen_quote, skip; unsigned f, quote_f; } PQ;
#define IN_GAME_ALL (IN_A | IN_B | IN_OPT | IN_UP_DOWN | IN_LEFT | IN_RIGHT | IN_ABFOLD | (1 << 2))   /* 게임 입력 전부(SELECT 까지) */
static void post_clear_locked(void)
{
   if (PQ.on || PQ.hold) league_in_block &= ~IN_GAME_ALL;   /* 대사 동안 막던 입력을 푼다(허브가 켜져 있으면 다음 틱에 어차피 새로 쓴다) */
   memset(&PQ, 0, sizeof PQ);
}
static int look_stage = -1, look_color = 0;   /* 다음 무장에 쓸 배경(league_look) — 무장이 L 을 비워도 남게 따로. look_color 는 안 씀(.prac 칸 자리) */

/* ── 컬러 — look.c(모든 모드: 1인·무한·연습·리그)로 옮겼다. 캐릭터·검질 고르기의 OPTION(다음 컬러)·B(2P색으로 결정)·미리보기 색·
   오른쪽 세로 칸 컬러 이름·경기 중 코어 팔레트 바꿔 쓰기는 모두 look 이 한다. 허브는 고르기 화면 입력을 쓰는 동안(상대 선택·대신 누르기)만
   league_hub_owns_select() 로 look 에 «바쁨»을 알린다(native.c 가 look_tick 의 ctx 로 넘김). 세로 칸 주인은 vpanel.c 하나. */

/* ── 게임 속 리그(허브) — 타이틀 메뉴 «2인 대전»(통신 대전) 자리를 «리그 대전»으로 쓴다 ─────────────────────
   H_TITLE    타이틀 메뉴 셋째 줄 글자를 «리그 대전»으로 바꿔 그리고(한글판 배치일 때만), 그 칸에서 누른 A 는 게임 대신 받는다
              → 커서를 0(1인 대전)으로 바꾸고 A 를 대신 눌러 1인 대전 캐릭터 고르기로.
   H_PICK_OPP 캐릭터 고르기 = 상대 고르기. 머리말 «캐릭터 선택» → «상대 선택». A 는 게임 대신 받아, 커서 캐릭터에
              리그 상대가 있으면 그 상대로 대진을 무장하고(카드 없음·올카드·난도·2선승·60초) H_PICK_ME 로.
   H_PICK_ME  같은 화면에서 내 캐릭터·검질·카드를 고른다(원래 게임 그대로). 경기가 시작되면 H_MATCH.
              그 전에 타이틀로 나가면 무장을 풀고 H_TITLE(경기는 Java 가 무효로 닫는다).
   H_MATCH    결판까지 1단계 그대로(league_tick 본문). 결판 뒤 Java 가 정산·결과 카드를 띄우고 league_hub_return.
   H_RETURN   리셋 뒤 타이틀 메뉴까지 A 를 대신 누르고, 메뉴가 뜨면 커서를 «리그 대전»으로 내린다 → H_TITLE.
              rematch 가 있으면 메뉴에서 바로 리그 대전 → 같은 상대로 H_PICK_ME.
   화면 글자는 코어의 문자/스크롤 램을 직접 고친다(롬·세이브는 안 건드림). 배치가 다르면(순정 롬 등) 글자는 그대로 두고 흐름만 같다.

   종류(kind) 1 = 연습 대전(일반 세션, practice.c) — 같은 상태 기계를 쓰되
   H_TITLE    셋째 줄 글자 «연습 대전» / «PRACTICE».
   H_PICK_OPP 머리말 «상대 선택», 15명 모두(가운데 무작위 칸 = 유가). A = 수라 더미 · B = 나찰 더미 · 옵션 = 스테이지(오른쪽 세로 칸).
              고르면 대진만 무장(카드·난도·규칙·올카드는 안 건드림 — 사용자 세이브 그대로).
   H_PICK_ME  리그와 같다(옵션 컬러 · B 2P색). 검질 고르기를 본 뒤 카드 화면이 끝나면 첫 라운드까지 이야기·대사를 A 로 넘긴다.
   H_MATCH    연습 경기(practice.c). 메뉴 «상대 고르기»/«타이틀로» → native.c 가 리셋 → league_practice_return → H_RETURN
              (상대 고르기면 메뉴에서 바로 «연습 대전» → 상대 선택).

   둘 다(dual, league_hub_dual) — 실행 창 옵션 «리그·연습 대전» 하나로 두 모드를 다 쓴다. 셋째 줄에 커서가 오면 글자 양옆에 ◀ ▶ 를 그리고
   ←→ 로 «연습 대전» ↔ «리그 대전»을 바꾼다(sel). 이 판의 종류(kind)와 같은 쪽에서 A 면 위 흐름 그대로, 다른 쪽이면 A 를 받아 두고
   handoff 를 세운다 — 리그는 따로 세이브·고정 롬·동봉 코어로 돌아야 해서(랭크전 판) Java 가 판을 갈아 끼우고(일반 세션 ↔ 리그 세션)
   새 판은 league_hub_go 로 로고·메뉴를 넘겨 바로 상대 선택까지 간다(전원부터 1초 남짓 — 실측 61프레임). */
enum { H_OFF = 0, H_TITLE, H_PICK_OPP, H_PICK_ME, H_MATCH, H_RETURN, H_SEARCH };
/* H_SEARCH (랭크 매치) 타이틀 메뉴 그대로 두고 앱이 «상대 찾는 중…»·상대 카드를 게임 위에 띄운다. 허브는 게임 입력을 모두 막고 누름만 모은다(ev).
              앱이 상대를 정하면(league_hub_match) 재대결과 같은 길로 «상대 선택» 없이 바로 내 캐릭터 고르기(H_PICK_ME, 대진·손잡이 무장).
              취소(league_hub_search(0))면 H_TITLE. */
static struct {
   int st;
   signed char sty[15], dif[15];     /* 캐릭터 id → 리그 상대의 검질(없으면 −1)·난도 */
   int prev_in;                      /* 지난 프레임 사용자 입력(A 누름의 시작을 본다) */
   int hover, picked, rematch;       /* 커서 캐릭터 · 고른 상대 캐릭터 · 결과 뒤 바로 다시 붙을 상대(−1 없음) */
   unsigned f, nav_f;                /* 허브 프레임 · 대신 누르기 시작한 프레임 */
   int nav;                          /* 0 없음 · 1 메뉴에서 A(1인 대전으로) · 2 리셋 뒤 타이틀까지 · 3 메뉴 커서 내리기 */
   int downs;                        /* nav 3: 남은 아래 누르기 */
   int hdr_saved;                    /* 머리말 원래 타일을 몇 개 보관했나(0 = 안 바꿈) */
   uint8_t hdr_orig[20 * 16];
   int label_menu, label_hdr;        /* 이번 판에서 글자를 바꿔 그렸나(Java 표시용) */
   int seen_csel;                    /* 리그 대전으로 들어온 뒤 캐릭터 고르기를 봤나(그 뒤에 메뉴가 보이면 «뒤로 나감») */
   unsigned csel_f;                  /* 캐릭터 고르기가 이어진 프레임 수(머리말은 화면이 자리 잡은 뒤에 바꾼다) */
   int hold_a;                       /* 허브가 받은 A 를 사용자가 뗄 때까지 게임에도 안 준다(누른 채로 넘어가 게임이 받지 않게) */
   signed char home[15];             /* 캐릭터 id → 그 리그 상대의 기본 배경(0~7, 없으면 −1) */
   int stage_sel;                    /* 배경 고르기: −1 상대 기본 · −2 무작위 · 0~7 그 배경 (상대 고르기에서 B) */
   int color;                        /* 안 씀 — 컬러는 look.c(캐릭터마다 기억). .prac 덩어리가 sizeof H 그대로라 칸은 남긴다 */
   int hold_b;                       /* B 로 캐릭터를 확정한 뒤 사용자가 B 를 뗄 때까지 게임에 안 준다(검질 고르기에서 B = 뒤로) */
   int vp_on;                        /* 오른쪽 세로 이름 칸을 배경 글자로 바꿔 놓았나(표시만 — 칸의 주인·원래 값은 vpanel.c) */
   uint16_t vp_orig[18];             /* 안 씀 — 원래 값은 vpanel.c 가 하나만 둔다(.prac 크기를 지키려고 칸은 남김) */
   int kind;                         /* 0 리그 · 1 연습 대전 */
   int pick_sty;                     /* 연습: 고른 더미 검질(A 수라 0 · B 나찰 1) */
   int repick;                       /* 연습: 리셋 뒤 메뉴에서 바로 «연습 대전» → 상대 선택까지(연습 «상대 고르기» · 리그 «다른 상대» · 갈아탄 새 판) */
   int style_seen;                   /* 연습: 더미를 고른 뒤 검질 고르기 화면을 봤나(그 뒤의 이야기·대사만 넘긴다) */
   int dual;                         /* 1 = 셋째 줄에서 ←→ 로 연습·리그 둘 다 고른다 */
   int sel;                          /* 둘 다: 셋째 줄에 보이는 종류(0 리그 · 1 연습) */
   int handoff;                      /* 둘 다: 다른 종류를 A 로 골랐다 — 1 리그 · 2 연습(Java 가 판을 갈아 끼운다) */
   int focus;                        /* 셋째 줄에 커서가 있나(화살표를 그렸나) */
   int menu_saved;                   /* 셋째 줄 원래 글자(타일 그림·칸)를 보관했나 — 상태 저장 직전에 되돌린다 */
   uint8_t menu_orig[32];            /* 한글판 «2 인» 두 타일 그림 */
   uint16_t row_orig[10];            /* 14줄 5~14칸 원래 값 */
   int menu_orig_ok;                 /* menu_orig 에 원래 «2 인» 그림이 들어 있나(타이틀을 떠났다 와도 남긴다 — 게임이 그 타일을 다시 안 읽고 돌아올 수 있어서) */
   /* ── 랭크 매치(끝없는 매칭) — 여기부터 새 칸(연습 .prac 덩어리 호환: PR_H_137) ── */
   int ranked;                       /* 1 = 셋째 줄 «리그 대전» A → 상대 찾기(H_SEARCH), 0 = 예전 상대 선택(H_PICK_OPP) */
   int ev;                           /* H_SEARCH 동안 모은 버튼 누름(1 A · 2 B · 4 OPTION · 8 ← · 16 → · 32 ↑ · 64 ↓) — Java 가 가져가며 비운다 */
   int go;                           /* Java 가 상대를 정했다(league_hub_match) — 다음 프레임에 1인 대전 → 내 캐릭터 고르기로 */
   int fx_on, fx_ch, fx_sty, fx_diff, fx_stage;   /* 정해진 상대(매칭·재대결) */
   int fx_k[NKNOB];                  /* 그 상대의 CPU 손잡이(단계·상한·공격·받는 피해·바닥) */
   int prev_in2;                     /* 지난 프레임 입력(H_SEARCH 의 방향 누름 시작) */
} H = { .st = H_OFF, .stage_sel = -1 };
static int pr_stage_keep = -2;        /* 연습 대전 스테이지 고르기(−2 무작위 · 0~7) — 허브를 다시 켜도 지난번 값 */
static int pr_pend_on;                /* 불러온 상태의 연습 경기 허브를 다음 league_practice_hub(1, 0) 때 되돌릴 것이 있나(pr_pend) */
volatile int league_in_block = 0;     /* 입력 콜백이 사용자 입력에서 지울 비트(허브가 대신 받는 A) */
static int hub_scr = -1;              /* 지난 프레임 메뉴 화면(MODE 240 일 때 R_SCR: 0 타이틀 · 2 캐릭터 · 4 검질 · 6 카드 · 8 대사, 아니면 −1) — 띠 안내를 화면마다 */
static unsigned hub_gen;              /* 타이틀로 돌려보낸 횟수 — Java 가 «돌려보냄이 코어에 닿았나»를 본다(닿기 전의 옛 상태로 경기 기록을 만들지 않게) */

static uint8_t *g_chr, *g_scr;        /* 코어 문자 램(타일 512 × 16바이트) · 스크롤 램(두 판 × 32 × 32 × 2바이트) */
/* 팔레트 램·세로 칸은 look·vpanel 이 쓴다 — native.c(·호스트 하네스)는 그대로 league_vram 하나만 부른다 */
void league_vram(void *chr, void *scr, void *pal) { g_chr = (uint8_t *)chr; g_scr = (uint8_t *)scr; look_vram(chr, scr, pal); }

/* 코어의 팔레트 바꿔 쓰기(retro_ngp_palremap) — 표의 주인은 look.c(경기 컬러·미리보기). 넘겨주기만 한다 */
void league_palremap_fn(void *fn) { look_palremap_fn(fn); }

static int scr_tile(int plane, int row, int col)
{
   const uint8_t *e = g_scr + plane * 0x800 + (row * 32 + col) * 2;
   return (e[0] | (e[1] << 8)) & 0x1FF;
}
/* 타이틀 메뉴가 떠 있나 — 메뉴 글자 타일이 제자리에 있는지로 본다(램 표시는 리셋 뒤에 안 서는 게 있어서). 둘째 판 12~16줄.
   1 = 한글판(7칸부터 «1 인 대전» 0x1B1… · «2 인 대전» 0x1BB~0x1BF 전용 타일) · 2 = 순정판(5칸부터 «1P PLAY» 0x1B1… · 글자마다 타일) */
static int menu_kind(void)
{
   if (!g_scr) return 0;
   if (scr_tile(1, 12, 7) == 0x1B1 && scr_tile(1, 13, 7) == 0x1B6 && scr_tile(1, 16, 7) == 0x1C6) return 1;
   if (scr_tile(1, 12, 5) == 0x1B1 && scr_tile(1, 13, 5) == 0x1B7 && scr_tile(1, 15, 5) == 0x1C5) return 2;
   return 0;
}
/* 셋째 줄을 «리그 대전» / «LEAGUE» 로. 한글판은 «2 인» 두 타일의 그림을 바꾸고, 순정판은 14줄 5~11칸이 가리키는 타일을
   메뉴의 다른 낱말 글자로 바꾼다(L·A=PLAY, E=COLLECTION·GAME, G=GAME, U=SURVIVAL, 끝은 빈칸 0x1B0) */
/* 연습 대전: 한글판은 «2 인» 두 타일을 «연습»(practice_glyphs.h), 순정판은 «PRACTICE» 여덟 글자 — 메뉴 글자 P = «VS PLAY» 의 P,
   R = SURVIVAL, A = PLAY, C·T·I·C·E = COLLECTION. «VS PLAY» 는 5~11칸 일곱 칸이지만 같은 메뉴 창의 «COLLECTION»(5~14칸)·
   «GAME OPTION»(5~15칸)이 더 길어 12칸도 창 바탕(0x17F)이다 — 여덟째 글자를 12칸에 쓴다(바탕 칸의 칠 번호 비트는 그대로). */
/* 둘 다(dual): 커서가 셋째 줄에 있으면 글자 양옆에 ◀ ▶(빈 타일 0x12E·0x12F — 롬 다섯 판의 부팅·타이틀 내내 안 쓰임, 실측).
   한글판은 글자 앞뒤 빈칸(6·12칸, 0x1B0)에, 순정판은 게임 커서(4칸 스프라이트)와 안 겹치게 글자를 6칸부터 쓰고 ◀ 5칸 · ▶ 글자 뒤.
   글자 칠은 두 판 모두 잉크 1 · 바탕 3. 타이틀을 떠나면 두 타일을 다시 비운다(다른 화면이 빈 타일로 쓸 수 있어서). */
#define AR_L 0x12E
#define AR_R 0x12F
static const uint8_t ARROW_R[8] = { 0x00, 0x10, 0x18, 0x1C, 0x18, 0x10, 0x00, 0x00 };   /* 줄마다 잉크 칸(왼쪽 = 높은 비트) — 1~5줄 세모 */
static void arrow_tile(int t, int left)
{
   uint8_t *d = g_chr + t * 16;
   for (int y = 0; y < 8; y++) {
      uint16_t v = 0;
      for (int x = 0; x < 8; x++) {
         int on = (ARROW_R[y] >> (7 - (left ? 7 - x : x))) & 1;
         v |= (uint16_t)((on ? 1 : 3) << (14 - 2 * x));
      }
      d[y * 2] = (uint8_t)v; d[y * 2 + 1] = (uint8_t)(v >> 8);
   }
}
static int arrow_is(int t, int left)
{
   uint8_t tmp[16];
   memcpy(tmp, g_chr + t * 16, 16); arrow_tile(t, left);
   int same = !memcmp(tmp, g_chr + t * 16, 16);
   memcpy(g_chr + t * 16, tmp, 16);
   return same;
}
static void arrows_clear(void)                   /* 화살표 타일을 다시 빈 타일로(우리 그림일 때만) */
{
   if (!g_chr) return;
   if (arrow_is(AR_L, 1)) memset(g_chr + AR_L * 16, 0, 16);
   if (arrow_is(AR_R, 0)) memset(g_chr + AR_R * 16, 0, 16);
}
static uint8_t *row14(int c) { return g_scr + 0x800 + (14 * 32 + c) * 2; }
static void ent_set(int c, int tile)               /* 칠 번호 비트는 그대로, 뒤집기는 끔 */
{
   uint8_t *e = row14(c);
   uint16_t v = (uint16_t)(((e[0] | (e[1] << 8)) & 0x1E00) | (tile & 0x1FF));
   e[0] = (uint8_t)v; e[1] = (uint8_t)(v >> 8);
}
static void menu_backup(int kind)                  /* 처음 바꿔 그리기 전에 원래 그림·칸을 보관(상태 저장 직전에 되돌리려고) */
{
   static const uint16_t VS[10] = { 0x1BF, 0x1C0, 0x1B0, 0x1C1, 0x1C2, 0x1C3, 0x1C4, 0x17F, 0x17F, 0x17F };   /* 순정판 셋째 줄(«VS PLAY» + 창 바탕) — 늘 같다 */
   if (H.menu_saved) return;
   if (kind == 1) {
      int ours = !memcmp(g_chr + 0x1BB * 16, PG_MENU_YEONSEUP, 32) || !memcmp(g_chr + 0x1BB * 16, HUB_MENU_RIGEU, 32);
      if (ours && !H.menu_orig_ok) return;           /* 이미 우리 글자이고 원래 그림을 본 적이 없다(앞 판이 저장한 타이틀) */
      if (!ours) { memcpy(H.menu_orig, g_chr + 0x1BB * 16, 32); H.menu_orig_ok = 1; }
   }
   for (int c = 0; c < 10; c++) {
      uint8_t *e = row14(5 + c);
      uint16_t v = (uint16_t)(e[0] | (e[1] << 8));
      if (kind == 2) v = (uint16_t)((v & 0xFE00) | VS[c]);            /* 이미 바뀐 칸이라도 원래 글자로 */
      else if ((v & 0x1FF) == AR_L || (v & 0x1FF) == AR_R) v = (uint16_t)((v & 0xFE00) | 0x1B0);
      H.row_orig[c] = v;
   }
   H.menu_saved = kind;
}
static void menu_restore(void)
{
   if (!g_chr || !g_scr) { H.menu_saved = 0; return; }
   int mk = menu_kind();
   if (H.menu_saved && mk == H.menu_saved) {
      if (H.menu_saved == 1) memcpy(g_chr + 0x1BB * 16, H.menu_orig, 32);
      for (int c = 0; c < 10; c++) { uint8_t *e = row14(5 + c); e[0] = (uint8_t)H.row_orig[c]; e[1] = (uint8_t)(H.row_orig[c] >> 8); }
   } else if (mk == 1) {                            /* 보관 못 함(앞 판이 저장한 타이틀 — 이미 우리 글자) — 화살표 칸만이라도 빈칸으로 */
      if (scr_tile(1, 14, 6) == AR_L) ent_set(6, 0x1B0);
      if (scr_tile(1, 14, 12) == AR_R) ent_set(12, 0x1B0);
   }
   arrows_clear();
   H.menu_saved = 0;
}
static void menu_relabel(int kind, int focus)
{
   int lk = H.dual ? H.sel : H.kind;              /* 보일 종류 */
   int arrows = H.dual && focus;
   if (!g_chr || !g_scr) return;
   menu_backup(kind);
   if (arrows) { arrow_tile(AR_L, 1); arrow_tile(AR_R, 0); }
   if (kind == 1) {
      memcpy(g_chr + 0x1BB * 16, lk == 1 ? PG_MENU_YEONSEUP : HUB_MENU_RIGEU, 32);
      if (H.dual) { ent_set(6, arrows ? AR_L : 0x1B0); ent_set(12, arrows ? AR_R : 0x1B0); }
   } else if (kind == 2) {
      static const uint16_t LEAGUE[7] = { 0x1C2, 0x1C9, 0x1C3, 0x1CF, 0x1B8, 0x1D2, 0x1B0 };
      static const uint16_t PRACTICE[8] = { 0x1C1, 0x1B9, 0x1B5, 0x1C5, 0x1CB, 0x1CC, 0x1CA, 0x1C9 };
      const uint16_t *w = lk == 1 ? PRACTICE : LEAGUE;
      int n = lk == 1 ? 8 : 6;                     /* LEAGUE 여섯 글자(일곱째 0x1B0 은 «VS PLAY» 일곱 칸을 채우던 빈칸) */
      int c0 = H.dual ? 6 : 5;
      uint16_t want[10];
      for (int c = 0; c < 10; c++) want[c] = c < 7 ? 0x1B0 : 0x17F;   /* 5~11칸 글자 바탕 0x1B0 · 12~14칸 창 바탕 */
      if (!H.dual && lk != 1) n = 7;               /* 예전 그대로(LEAGUE + 빈칸) */
      for (int i = 0; i < n; i++) want[c0 - 5 + i] = w[i];
      if (arrows) { want[c0 - 6] = AR_L; want[c0 - 5 + n] = AR_R; }
      for (int c = 0; c < 10; c++) {
         int cur = scr_tile(1, 14, 5 + c);
         if (c >= 7 && cur != 0x17F && cur != 0x1B0 && cur != AR_L && cur != AR_R && cur != PRACTICE[7] && cur != PRACTICE[6]
             && cur != PRACTICE[5] && cur != LEAGUE[5]) continue;   /* 12칸부터는 창 바탕이거나 우리가 쓴 것만 */
         if (cur != want[c]) ent_set(5 + c, want[c]);
      }
   }
}
/* 캐릭터 고르기 머리말: 둘째 판 0줄 5~14칸 0x150~0x159, 1줄 5~13칸 0x15A~0x162 (두 판 같은 자리).
   1 = 한글판(«캐릭터 선택» — 앞 6칸 «캐릭터» 만 «상대» 로) · 2 = 순정판(«十五士選択», 1줄 14칸 0x163 — 뒤 4칸 «選択» 도 «선택» 으로) */
static int hdr_layout(void)
{
   if (!g_chr || !g_scr) return 0;
   for (int c = 0; c < 10; c++) if (scr_tile(1, 0, 5 + c) != 0x150 + c) return 0;
   for (int c = 0; c < 9; c++) if (scr_tile(1, 1, 5 + c) != 0x15A + c) return 0;
   return scr_tile(1, 1, 14) == 0x163 ? 2 : 1;
}
static void hdr_put(int kind, int sangdae)
{
   int n = sangdae ? (kind == 2 ? 20 : 12) : H.hdr_saved;
   for (int i = 0; i < n; i++) {
      int t = i < 6 ? 0x150 + i : i < 12 ? 0x15A + (i - 6) : i < 16 ? 0x156 + (i - 12) : 0x160 + (i - 16);
      uint8_t *dst = g_chr + t * 16;
      if (sangdae) {
         if (!H.hdr_saved) memcpy(H.hdr_orig + i * 16, dst, 16);
         memcpy(dst, i < 12 ? HUB_HDR_SANGDAE + i * 16 : HUB_HDR_SEONTAEK + (i - 12) * 16, 16);
      } else memcpy(dst, H.hdr_orig + i * 16, 16);
   }
   H.hdr_saved = sangdae ? n : 0;
}

/* 캐릭터 고르기 오른쪽 세로 이름 칸 — 둘째 판 3~11줄 × 17·18칸(위아래 테: 2줄 0x16C · 12줄 0x16E, 검질 고르기는 0x19C · 0x19E).
   원래는 왼쪽 칸과 같은 커서 캐릭터 이름(전용 타일 0x1E8 빈칸 ~ 0x1F9). 상대 고르기에서는 배경 이름(여기), 내 캐릭터 고르기에서는 컬러 이름(look.c)을
   게임 글꼴 그대로 쓴다. 칸의 주인(원래 값 보관·되돌리기)은 vpanel.c 하나 — 허브가 배경 이름으로 바꿔 둔 칸을 look 이 넘겨받아도 원래 값을 안 잃게. */
static void vp_put(int lab) { if (lab >= 0 && lab < HUB_VLAB_N) { vpanel_put(HUB_VLAB + lab * 18 * 16); H.vp_on = vpanel_on(); } }   /* lab: 0~7 배경 · 8 무작위 */
static void vp_restore(void) { vpanel_restore(); H.vp_on = 0; }

static void arm_locked(int opp, int sty, int diff, int points, int timev, int flags, const int *knob);
static void hub_go_fixed(uint8_t *ram)       /* 정해진 상대로 1인 대전 → 내 캐릭터 고르기(메뉴·커서 2 에서만 부른다) */
{
   ram[R_MCUR] = 0; H.nav = 1; H.nav_f = H.f; H.st = H_PICK_ME; H.picked = H.fx_ch; H.seen_csel = 0; H.go = 0; H.ev = 0;
   look_stage = (H.fx_stage >= 0 && H.fx_stage <= 7) ? H.fx_stage : -1; look_color = H.color;
   arm_locked(H.fx_ch, H.fx_sty, H.fx_diff, 2, 60, LEAGUE_ALLCARDS, H.fx_k);
}

/* 고른 상대로 무장하기 직전 — 배경(−1 상대 기본 · −2 무작위 · 0~7)과 컬러를 다음 무장에 넘긴다 */
static void hub_set_look(int picked)
{
   int st = H.stage_sel;
   if (st == -1) st = (picked >= 0 && picked < 15) ? H.home[picked] : -1;
   else if (st == -2) st = (int)((H.f * 5u + 3u) % 8u);
   look_stage = (st >= 0 && st <= 7) ? st : -1;
   look_color = H.color;
}

static void hub_tick(uint8_t *ram, int input)
{
   H.f++;
   int a_edge = (input & IN_A) && !(H.prev_in & IN_A);
   int b_edge = (input & IN_B) && !(H.prev_in & IN_B);
   int o_edge = (input & IN_OPT) && !(H.prev_in & IN_OPT);
   int lr_edge = (input & (IN_LEFT | IN_RIGHT)) && !(H.prev_in & (IN_LEFT | IN_RIGHT));
   H.prev_in2 = H.prev_in;
   H.prev_in = input;
   int mk = (ram[R_MODE] == 240 && ram[R_SCR] == 0) ? menu_kind() : 0;
   hub_scr = ram[R_MODE] == 240 ? ram[R_SCR] : -1;
   int menu = mk != 0;
   int csel = ram[R_MODE] == 240 && ram[R_SCR] == 2;
   int block = 0;
   if (H.hold_a && !(input & IN_A)) H.hold_a = 0;
   if (H.hold_b && !(input & IN_B)) H.hold_b = 0;

   /* 대신 누르기(다음 프레임 입력) */
   if (H.nav == 1) {                                  /* 메뉴에서 1인 대전 A — 2프레임 뒤부터 4프레임 */
      unsigned d = H.f + 1 - H.nav_f;
      league_ovr_on = 1; league_ovr_mask = (d >= 2 && d < 6) ? IN_A : 0;
      if (d >= 8) { H.nav = 0; league_ovr_on = 0; league_ovr_mask = 0; }
   } else if (H.nav == 2) {                           /* 리셋 뒤: A 로 로고·인트로를 넘겨 메뉴까지(메뉴가 뜬 뒤엔 A 를 안 준다) */
      unsigned d = H.f + 1 - H.nav_f;
      league_ovr_on = 1;
      league_ovr_mask = (!menu && ((d >= 20 && d < 24) || (d >= 200 && d < 204) || (d >= 380 && d < 384))) ? IN_A : 0;
      if (menu) { H.nav = 3; H.nav_f = H.f; H.downs = 2; league_ovr_mask = 0; }
      else if (d > 900) { H.nav = 0; league_ovr_on = 0; league_ovr_mask = 0; H.st = H_TITLE; }
   } else if (H.nav == 3) {                           /* 메뉴 커서를 «리그 대전»(2)까지 아래로 */
      unsigned d = H.f + 1 - H.nav_f;
      league_ovr_on = 1; league_ovr_mask = ((d % 12) >= 4 && (d % 12) < 8 && H.downs > 0) ? IN_DOWN : 0;
      if ((d % 12) == 8 && H.downs > 0) H.downs--;
      if (H.downs == 0 && (d % 12) >= 10) {
         H.nav = 0; league_ovr_on = 0; league_ovr_mask = 0; H.st = H_TITLE;
         if (H.rematch >= 0 && menu && ram[R_MCUR] == 2) {   /* 재대결: 바로 리그 대전 → 같은 상대 */
            ram[R_MCUR] = 0; H.nav = 1; H.nav_f = H.f; H.st = H_PICK_ME; H.picked = H.rematch; H.seen_csel = 0;
            hub_set_look(H.picked);
            arm_locked(H.picked, H.sty[H.picked], H.dif[H.picked], 2, 60, LEAGUE_ALLCARDS, NULL);
            H.rematch = -1;
         } else if (H.rematch == -4 && H.fx_on && menu && ram[R_MCUR] == 2) {   /* 랭크 재대결: 같은 상대·같은 손잡이로 바로 내 캐릭터 고르기 */
            hub_go_fixed(ram); H.rematch = -1;
         } else if (H.repick == 2 && menu && ram[R_MCUR] == 2) {   /* 랭크 «다음 상대»·갈아탄 리그 판: 메뉴에서 바로 상대 찾기 */
            H.st = H_SEARCH; H.ev = 0; H.go = 0;
         } else if (H.repick && menu && ram[R_MCUR] == 2) {   /* 연습 «상대 고르기»·리그 «다른 상대»·갈아탄 새 판: 바로 상대 선택 */
            ram[R_MCUR] = 0; H.nav = 1; H.nav_f = H.f; H.st = H_PICK_OPP; H.picked = -1; H.seen_csel = 0; H.csel_f = 0;
         }
         H.repick = 0;
      }
   }

   switch (H.st) {
   case H_TITLE:
      if (menu) {
         H.focus = ram[R_MCUR] == 2 && !H.nav;
         if (!H.focus && !H.handoff) H.sel = H.kind;    /* 커서가 셋째 줄을 떠나면 이 판의 종류로(다른 줄에서 본 글자·안내가 어긋나지 않게) */
         if (H.focus && H.dual && !H.handoff) {        /* 둘 다: ←→ 로 연습 ↔ 리그(게임은 이 메뉴에서 좌우를 안 쓴다) */
            block = IN_LEFT | IN_RIGHT;
            if (lr_edge) H.sel ^= 1;
         }
         menu_relabel(mk, H.focus); H.label_menu = mk;
         if (H.handoff) block = IN_A | IN_B | IN_UP_DOWN;   /* 판을 갈아 끼우는 중 — 메뉴를 그대로 */
         else if (H.focus) {
            block |= IN_A;
            if (a_edge && H.dual && H.sel != H.kind) H.handoff = 1 + H.sel;   /* 다른 판의 모드 — Java 가 판을 바꾼다 */
            else if (a_edge && H.kind == 0 && H.ranked) {   /* 랭크 매치: 상대 찾기 — 메뉴는 그대로, 앱이 «상대 찾는 중…» */
               H.st = H_SEARCH; H.ev = 0; H.go = 0; H.hold_a = 1;
            }
            else if (a_edge) {                        /* «리그 대전»/«연습 대전» 선택 → 1인 대전 캐릭터 고르기로 */
               ram[R_MCUR] = 0; H.nav = 1; H.nav_f = H.f; H.st = H_PICK_OPP; H.picked = -1; H.seen_csel = 0; H.csel_f = 0;
               H.focus = 0;
            }
         }
      }
      break;
   case H_SEARCH:                                    /* 랭크 매치 상대 찾기·상대 카드 — 게임 입력은 모두 막고 누름만 모은다 */
      block = IN_A | IN_B | IN_OPT | IN_UP_DOWN | IN_LEFT | IN_RIGHT | IN_ABFOLD;   /* A+B·원버튼 접힘 비트도(게임 메뉴로 새지 않게) */
      if (!H.hold_a && a_edge) H.ev |= 1;
      if (b_edge) H.ev |= 2;
      if (o_edge) H.ev |= 4;
      if ((input & IN_LEFT) && !(H.prev_in2 & IN_LEFT)) H.ev |= 8;
      if ((input & IN_RIGHT) && !(H.prev_in2 & IN_RIGHT)) H.ev |= 16;
      if ((input & (1 << 4)) && !(H.prev_in2 & (1 << 4))) H.ev |= 32;   /* ↑ = libretro UP(4) */
      if ((input & IN_DOWN) && !(H.prev_in2 & IN_DOWN)) H.ev |= 64;
      if (menu) {
         H.focus = 0; menu_relabel(mk, 0); H.label_menu = mk;   /* 찾는 동안은 ◀▶ 없이(바꿀 수 없음) */
         if (H.go && H.fx_on && ram[R_MCUR] == 2) hub_go_fixed(ram);
      } else if (!H.nav) { H.st = H_TITLE; H.go = 0; }   /* 메뉴가 사라졌다(있을 수 없는 일 — 안전하게 타이틀 상태로) */
      break;
   case H_RETURN:                                    /* 돌아가는 중에도 메뉴가 뜨면 셋째 줄 글자를 바로(원래 «2 인 대전»이 잠깐 보이지 않게) */
      if (menu) { H.focus = 0; menu_relabel(mk, 0); H.label_menu = mk; }
      break;
   case H_PICK_OPP:
      if (csel) { H.seen_csel = 1; H.csel_f++; } else H.csel_f = 0;
      if (csel && !H.nav && H.kind == 1) {          /* 연습: A 수라 · B 나찰 더미 · 옵션 스테이지(게임은 이 화면에서 B·옵션을 안 쓴다) */
         block = IN_A | IN_B | IN_OPT;
         if (o_edge) H.stage_sel = H.stage_sel == -2 ? 0 : H.stage_sel >= 7 || H.stage_sel < -2 ? -2 : H.stage_sel + 1;
         if (H.stage_sel < -2 || H.stage_sel == -1 || H.stage_sel > 7) H.stage_sel = -2;
         pr_stage_keep = H.stage_sel;
         int h = ram[R_HOVER] >> 2;                 /* 0~13 캐릭터 · 15 가운데 무작위 칸 = 유가(14) — 더미는 AI 가 필요 없어 15명 모두 */
         H.hover = (h >= 0 && h < 14) ? h : h == 15 ? 14 : -1;
         int hk = H.csel_f >= 20 ? hdr_layout() : 0;
         if (hk) { hdr_put(hk, 1); H.label_hdr = hk; }
         if (H.csel_f >= 20) vp_put(H.stage_sel == -2 ? 8 : H.stage_sel);
         if ((a_edge || b_edge) && H.csel_f >= 20 && H.hover >= 0) {   /* 머리말이 «상대 선택» 으로 바뀐 뒤에만 */
            H.picked = H.hover; H.pick_sty = a_edge ? 0 : 1;
            if (H.hdr_saved && hdr_layout()) hdr_put(0, 0);
            look_stage = H.stage_sel >= 0 ? H.stage_sel : (int)((H.f * 5u + 3u) % 8u);   /* 무작위는 고르는 순간 뽑는다 */
            look_color = H.color;
            arm_locked(H.picked, H.pick_sty, -1, 0, 0, 0, NULL);   /* 대진·배경·컬러만 — 카드·난도·규칙·올카드는 사용자 세이브 그대로 */
            H.st = H_PICK_ME; H.style_seen = 0;
            if (a_edge) H.hold_a = 1; else H.hold_b = 1;
         }
      } else if (csel && !H.nav) {
         block = IN_A | IN_B | IN_OPT;
         if (o_edge || b_edge)                       /* OPTION(연습과 같게 · 예전 B 도): 배경 — 상대 기본 → 무작위 → 0 … 7 → 상대 기본 (게임은 이 화면에서 B·OPTION 을 안 쓴다) */
            H.stage_sel = H.stage_sel == -1 ? -2 : H.stage_sel == -2 ? 0 : H.stage_sel >= 7 ? -1 : H.stage_sel + 1;
         int h = ram[R_HOVER] >> 2;                 /* 0~13 캐릭터 · 15 가운데 무작위 칸 — 고르기 판에 없는 유가(14)를 그 칸에 숨은 상대로 */
         H.hover = (h >= 0 && h < 14) ? h : (h == 15 && H.sty[14] >= 0) ? 14 : -1;
         int hk = H.csel_f >= 20 ? hdr_layout() : 0;
         if (hk) { hdr_put(hk, 1); H.label_hdr = hk; }
         if (H.csel_f >= 20) {                       /* 오른쪽 세로 칸 = 배경 이름(상대 기본이면 커서 상대의 배경, 리그 상대가 아니면 원래 이름) */
            int lab = H.stage_sel == -2 ? 8 : H.stage_sel >= 0 ? H.stage_sel
                    : (H.hover >= 0 && H.sty[H.hover] >= 0 && H.home[H.hover] >= 0) ? H.home[H.hover] : -1;
            if (lab >= 0) vp_put(lab); else vp_restore();
         }
         if (a_edge && H.csel_f >= 20 && H.hover >= 0 && H.sty[H.hover] >= 0) {   /* 머리말이 «상대 선택» 으로 바뀐 뒤에만(두 번 눌러 안 보고 골라지지 않게) */
            H.picked = H.hover;
            if (H.hdr_saved && hdr_layout()) hdr_put(0, 0);
            hub_set_look(H.picked);
            arm_locked(H.picked, H.sty[H.picked], H.dif[H.picked], 2, 60, LEAGUE_ALLCARDS, NULL);
            H.st = H_PICK_ME;
            H.hold_a = 1;
         }
      } else if (menu && !H.nav && H.seen_csel) { H.st = H_TITLE; H.hdr_saved = 0; H.seen_csel = 0; H.vp_on = 0; }   /* 캐릭터 고르기에서 뒤로 나감 */
      else if (menu && !H.nav && !H.seen_csel && H.f - H.nav_f > 90) {   /* 대신 누른 A 를 게임이 안 받았다(메뉴 그대로) — 다시 «리그 대전» 칸에서 */
         H.st = H_TITLE; ram[R_MCUR] = 2;
      }
      break;
   case H_PICK_ME:
      if (csel) H.seen_csel = 1;
      /* 컬러(옵션·B·미리보기·세로 칸)는 look.c — 허브가 바쁘지 않으면(league_hub_owns_select 0) look 이 쥔다 */
      if (H.kind == 1) {                            /* 연습: 검질 고르기를 본 뒤 카드 화면이 끝나면 이야기(MODE 241 · SCR 0)·대사(MODE 240 · SCR 8)를 A 로 넘긴다 */
         if (ram[R_MODE] == 240 && ram[R_SCR] == 4) H.style_seen = 1;
         int story = H.style_seen && ((ram[R_MODE] == 241 && ram[R_SCR] == 0 && ram[R_FIGHT] == 0) || (ram[R_MODE] == 240 && ram[R_SCR] == 8));
         league_in_or = (story && (H.f % 30u) < 4) ? IN_A : 0;
      }
      if (L.fight_seen) { H.st = H_MATCH; league_in_or = 0; }
      else if (menu && !H.nav && H.seen_csel) { L.armed = 0; H.st = H_TITLE; H.picked = -1; H.seen_csel = 0; H.vp_on = 0; league_in_or = 0; }   /* 대진 전에 타이틀로 — 무효 */
      break;
   case H_MATCH:
      if (H.kind == 1 && menu && !H.nav) { L.armed = 0; H.st = H_TITLE; H.picked = -1; H.seen_csel = 0; H.vp_on = 0; }   /* 연습 경기가 게임 쪽에서 끝나 타이틀로 왔다 */
      break;
   default: break;
   }
   if (!menu && (H.menu_saved || H.focus)) { H.focus = 0; H.menu_saved = 0; arrows_clear(); }   /* 타이틀을 떠났다 — 화살표 타일을 비운다(게임이 메뉴를 새로 그린다) */
   if (H.hold_a) block |= IN_A;
   if (H.hold_b) block |= IN_B;
   if (block & (IN_A | IN_B)) block |= IN_ABFOLD;   /* 패드 «A+B»·원버튼 비트로 게임이 A 를 받아 허브를 건너뛰지 않게 */
   league_in_block = block;
}

void league_hub(int on, const signed char *sty, const signed char *dif)
{
   pthread_mutex_lock(&L_mu);
   pr_pend_on = 0;
   memset(&H, 0, sizeof H);
   post_clear_locked();
   hub_gen = 0;                                      /* 새 화면(Java)의 셈과 같이 0 부터 */
   H.st = on ? H_TITLE : H_OFF; H.picked = -1; H.hover = -1; H.rematch = -1; H.stage_sel = -1; H.sel = 0;
   for (int i = 0; i < 15; i++) { H.sty[i] = sty ? sty[i] : -1; H.dif[i] = dif ? dif[i] : 1; H.home[i] = -1; }
   look_stage = -1; look_color = 0;
   league_in_block = 0; league_ovr_on = 0; league_ovr_mask = 0; league_in_or = 0;
   pthread_mutex_unlock(&L_mu);
}

/* 연습 대전 허브(일반 세션, 확인한 롬 판만 — Java 가 가린다). on = 켜기, autostart = 로고·인트로를 넘겨 메뉴 «연습 대전» → 상대 선택까지
   대신 누른다(실행 창의 «연습 대전으로» 링크 — 이어하기로 연 판에서는 Java 가 0 을 준다). 리그와 같은 허브를 종류 1 로 쓴다. */
/* 연습 경기 이어가기 — 상태 저장 옆(.prac, native.c)에 남긴 허브(H·L·고른 배경·컬러)를 불러온 뒤 그대로 되돌린다. 옵션을 바꾸면 앱이
   «저장 → 코어 내림 → 다시 올림 → 불러오기»를 하고(applyLive), 이어하기·슬롯 불러오기도 같은 길이라, 안 그러면 연습이 꺼진 채
   끝없는 경기만 남았다. 연습 경기(H_MATCH) 중에 저장한 것만 남긴다. */
#define PR_BLOB_N (sizeof H + sizeof L + 2 * sizeof(int))
#define PR_H_OLD offsetof(__typeof__(H), dual)      /* 둘 다(dual…) 앞까지 = 앞 판(내부 134)의 허브 크기 — 그 판이 남긴 .prac 도 받는다(새 칸은 0) */
#define PR_H_137 offsetof(__typeof__(H), ranked)    /* 랭크 매치 칸 앞까지 = 내부 136·137 허브 크기 */
#define PR_L_137 offsetof(__typeof__(L), k_lv)      /* L 도 손잡이 칸 앞까지(내부 134~137 의 L 크기) */
#define PR_BLOB_OLD (PR_H_OLD + PR_L_137 + 2 * sizeof(int))
#define PR_BLOB_137 (PR_H_137 + PR_L_137 + 2 * sizeof(int))
static uint8_t pr_pend[PR_BLOB_N];
static void pr_apply(const uint8_t *b)
{
   int dual = H.dual;                               /* 둘 다 켜짐은 지금 판(Java)이 정한 것 — 저장 때 값이 아니라 */
   memcpy(&H, b, sizeof H); memcpy(&L, b + sizeof H, sizeof L);
   H.dual = dual; H.sel = H.kind; H.handoff = 0; H.focus = 0; H.menu_saved = 0;
   memcpy(&look_stage, b + sizeof H + sizeof L, sizeof(int));
   memcpy(&look_color, b + sizeof H + sizeof L + sizeof(int), sizeof(int));
   H.hdr_saved = 0; H.vp_on = 0; H.hold_a = 0; H.hold_b = 0; H.nav = 0;   /* 저장된 화면 램은 원래 그림(저장 직전에 되돌림) */
   league_in_block = 0; league_ovr_on = 0; league_ovr_mask = 0; league_in_or = 0;
}
size_t league_practice_blob(void *buf, size_t cap)
{
   pthread_mutex_lock(&L_mu);
   size_t n = 0;
   if (H.kind == 1 && H.st == H_MATCH && cap >= PR_BLOB_N) {
      uint8_t *b = (uint8_t *)buf;
      memcpy(b, &H, sizeof H); memcpy(b + sizeof H, &L, sizeof L);
      memcpy(b + sizeof H + sizeof L, &look_stage, sizeof(int));
      memcpy(b + sizeof H + sizeof L + sizeof(int), &look_color, sizeof(int));
      n = PR_BLOB_N;
   }
   pthread_mutex_unlock(&L_mu);
   return n;
}
/* 상태를 불러온 바로 뒤(코어 잠금 안). 허브가 이미 연습으로 켜져 있으면 바로 되돌리고, 코어를 막 다시 올린 판(이어하기·옵션 바꾸기)은
   곧이어 Java 가 league_practice_hub(1, 0) 로 허브를 새로 켜므로 그때 한 번 더 되돌리게 남겨 둔다(코어를 올릴 때 native.c 가 비운다).
   buf NULL = 남긴 것 없음(기다리던 것도 버린다) */
void league_practice_resume(const void *buf, size_t n)
{
   pthread_mutex_lock(&L_mu);
   pr_pend_on = 0;
   static uint8_t up[PR_BLOB_N];
   if (buf && (n == PR_BLOB_OLD || n == PR_BLOB_137)) {   /* 앞 판 크기 — 허브·L 뒤에 새 칸(0)을 끼워 지금 크기로 */
      size_t ho = n == PR_BLOB_OLD ? PR_H_OLD : PR_H_137, lo = n == PR_BLOB_OLD ? PR_L_137 : PR_L_137;
      memset(up, 0, sizeof up);
      memcpy(up, buf, ho);
      memcpy(up + sizeof H, (const uint8_t *)buf + ho, lo);
      memcpy(up + sizeof H + sizeof L, (const uint8_t *)buf + ho + lo, 2 * sizeof(int));
      buf = up; n = PR_BLOB_N;
      { __typeof__(L) *lp = (__typeof__(L) *)(up + sizeof H); lp->k_lv = lp->k_cap = lp->k_atk = lp->k_dfn = lp->k_floor = -1; }
   }
   if (buf && n == PR_BLOB_N) {
      __typeof__(H) t;
      memcpy(&t, buf, sizeof t);
      if (t.kind == 1 && t.st == H_MATCH && t.picked >= 0 && t.picked <= 14) {
         if (H.kind == 1 && H.st != H_OFF) pr_apply((const uint8_t *)buf);
         memcpy(pr_pend, buf, PR_BLOB_N); pr_pend_on = 1;
      }
   }
   pthread_mutex_unlock(&L_mu);
}

void league_practice_hub(int on, int autostart)
{
   pthread_mutex_lock(&L_mu);
   memset(&H, 0, sizeof H);
   memset(&L, 0, sizeof L);
   post_clear_locked();
   hub_gen = 0;
   H.kind = 1;
   H.st = !on ? H_OFF : autostart ? H_RETURN : H_TITLE;
   H.sel = 1;
   if (on && autostart) { H.nav = 2; H.nav_f = 0; H.repick = 1; }
   H.picked = -1; H.hover = -1; H.rematch = -1; H.stage_sel = pr_stage_keep;
   for (int i = 0; i < 15; i++) { H.sty[i] = -1; H.dif[i] = 1; H.home[i] = -1; }
   look_stage = -1; look_color = 0;
   league_in_block = 0; league_ovr_on = 0; league_ovr_mask = 0; league_in_or = 0;
   if (on && !autostart && pr_pend_on) pr_apply(pr_pend);   /* 이어하기·옵션 바꾸기로 다시 연 판 — 연습 경기 그대로 */
   pr_pend_on = 0;
   pthread_mutex_unlock(&L_mu);
}

/* 연습 경기 중인가(허브 종류 1 · H_MATCH) — practice.c 가 매 프레임 묻는다. 더미 캐릭터·검질을 함께 */
int league_practice_match(int *dummy, int *sty)
{
   pthread_mutex_lock(&L_mu);
   int on = H.kind == 1 && H.st == H_MATCH;
   if (dummy) *dummy = H.picked;
   if (sty) *sty = H.pick_sty;
   pthread_mutex_unlock(&L_mu);
   return on;
}

/* 치트(cheat.c)가 매 진짜 프레임 묻는다 — 1 = 리그가 쥔 세션(게임 속 리그 허브 종류 0 이 켜짐, 또는 허브 없이 무장된 랭크전 한 경기):
   기록이 깨끗해야 해서 치트는 아무것도 안 쓴다. 2 = 연습 대전 흐름(허브 종류 1 의 상대 고르기 → 내 캐릭터 → 연습 경기 → 돌아가는 중):
   연습이 시간·체력·분노·더미·배율을 맡으므로 경기 치트를 쉰다(경기 전에 쓴 공격 배율이 연습 경기까지 새지 않게 경기 전부터). 0 = 그 밖.
   연습 허브는 H_PICK_ME 에서도 L.armed 를 세우므로 «armed» 하나로는 못 가른다 — 허브 종류를 같이 본다 */
int league_session_owner(void)
{
   pthread_mutex_lock(&L_mu);
   int r = (H.st != H_OFF && H.kind == 0) || (H.st == H_OFF && L.armed) ? 1
         : H.st != H_OFF && H.kind == 1 && H.st != H_TITLE ? 2 : 0;
   pthread_mutex_unlock(&L_mu);
   return r;
}

/* 연습 메뉴 «상대 고르기»(repick 1)·«타이틀로»(0) — native.c 가 코어 잠금 안에서 리셋한 바로 뒤에 부른다.
   리그 결판 뒤 돌아오기와 같은 길: 로고·인트로를 넘겨 메뉴 셋째 줄로, 상대 고르기면 그대로 상대 선택까지 */
void league_practice_return(int repick)
{
   pthread_mutex_lock(&L_mu);
   L.armed = 0;
   if (H.st != H_OFF && H.kind == 1) {
      H.st = H_RETURN; H.nav = 2; H.nav_f = H.f; H.picked = -1; H.hdr_saved = 0; H.vp_on = 0; H.hold_a = 0; H.hold_b = 0;
      H.repick = repick ? 1 : 0; H.rematch = -1; H.style_seen = 0; H.sel = H.kind; H.handoff = 0;
      hub_gen++;
   }
   league_in_block = 0; league_ovr_on = 0; league_ovr_mask = 0; league_in_or = 0;
   pthread_mutex_unlock(&L_mu);
}

/* 상태 불러오기·앱 리셋 뒤(연습 허브만) — 램이 통째로 바뀌었으니 고르던 것·경기를 잊고 타이틀 상태로(메뉴 글자는 다시 바꿔 그린다) */
void league_practice_title(void)
{
   pthread_mutex_lock(&L_mu);
   if (H.st != H_OFF && H.kind == 1) {
      L.armed = 0;
      H.st = H_TITLE; H.nav = 0; H.picked = -1; H.hdr_saved = 0; H.vp_on = 0; H.hold_a = 0; H.hold_b = 0; H.repick = 0; H.style_seen = 0;
      H.menu_saved = 0; H.focus = 0; H.handoff = 0;
      league_in_block = 0; league_ovr_on = 0; league_ovr_mask = 0; league_in_or = 0;
   }
   pthread_mutex_unlock(&L_mu);
}

/* 상태 저장 직전(코어 잠금 안) — 허브가 바꿔 그린 머리말·오른쪽 세로 칸을 원래대로(저장된 상태에 우리 글자가 안 섞이게).
   다음 프레임에 허브가 다시 바꿔 그린다 */
void league_hub_before_save(void)
{
   pthread_mutex_lock(&L_mu);
   if (H.hdr_saved && hdr_layout()) hdr_put(0, 0);
   vp_restore();
   menu_restore();                                  /* 타이틀 셋째 줄 글자·화살표도 원래대로 */
   pthread_mutex_unlock(&L_mu);
}

/* 게임 속 리그의 배경: 캐릭터마다 상대 기본 배경, 지금 고른 배경(−1 상대 기본 · −2 무작위 · 0~7) — Java 가 저장한 값을 넣는다.
   color 는 안 쓴다(컬러는 look.c 가 캐릭터마다 기억 — Java Look). 인자는 옛 JNI 모양 그대로 둔다 */
void league_hub_look(const signed char *home, int stage_sel, int color)
{
   pthread_mutex_lock(&L_mu);
   for (int i = 0; i < 15; i++) H.home[i] = home ? home[i] : -1;
   H.stage_sel = (stage_sel >= -2 && stage_sel <= 7) ? stage_sel : -1;
   (void)color;
   pthread_mutex_unlock(&L_mu);
}

/* 다음 무장(league_arm)에 쓸 배경(0~7, −1 = 게임 그대로)·경기 컬러 — 2단계 리그 화면에서 시작하는 경기·체크포인트 재개용.
   컬러(0~17, −1 = 고르기 화면에서 정함)는 look 에 넘긴다(look_set_match — 캐릭터는 0x57D5 로) */
void league_look(int stage, int color)
{
   pthread_mutex_lock(&L_mu);
   look_stage = (stage >= 0 && stage <= 7) ? stage : -1; look_color = 0;
   pthread_mutex_unlock(&L_mu);
   look_set_match(color);
}

/* 결판 뒤(Java 가 정산·결과 카드를 닫은 뒤): 무장을 풀고 리셋 뒤 타이틀 «리그 대전»까지. rematch = 다시 붙을 상대 캐릭터(−1 없음 ·
   −2 = «다른 상대»: 타이틀에서 멈추지 않고 바로 상대 선택까지). 리셋 자체는 native.c 가 코어 잠금 안에서 한다(nativeLeagueHubReturn). */
void league_hub_return(int rematch)
{
   pthread_mutex_lock(&L_mu);
   L.armed = 0;
   post_clear_locked();                               /* 리셋 뒤 — 멈춤 걸쇠를 푼다 */
   if (H.st != H_OFF) {
      H.st = H_RETURN; H.nav = 2; H.nav_f = H.f; H.picked = -1; H.hdr_saved = 0; H.vp_on = 0; H.hold_b = 0;
      H.sel = H.kind; H.handoff = 0; H.menu_saved = 0; H.focus = 0;
      hub_gen++;
      H.rematch = (rematch >= 0 && rematch < 15 && H.sty[rematch] >= 0) ? rematch : (rematch == -4 && H.fx_on) ? -4 : -1;
      H.repick = rematch == -2 ? 1 : rematch == -3 ? 2 : 0;    /* −2 상대 선택 · −3 랭크 «다음 상대»(바로 상대 찾기) · −4 랭크 재대결(정해진 상대) */
      H.go = 0; H.ev = 0;
   }
   league_in_block = 0; league_ovr_on = 0; league_ovr_mask = 0; league_in_or = 0;
   pthread_mutex_unlock(&L_mu);
}

/* 고른 상대를 취소하고 타이틀 상태로(Java 가 경기 기록을 못 만들었을 때) */
void league_hub_cancel(void)
{
   pthread_mutex_lock(&L_mu);
   L.armed = 0;
   if (H.st != H_OFF) { vp_restore(); H.st = H_TITLE; H.picked = -1; H.hold_b = 0; H.go = 0; H.ev = 0; }
   league_in_block = 0; league_ovr_on = 0; league_ovr_mask = 0;
   pthread_mutex_unlock(&L_mu);
}

/* [허브 상태(0 꺼짐·1 타이틀·2 상대 고르기·3 내 캐릭터·4 경기·5 타이틀로 돌아가는 중), 커서 캐릭터, 고른 상대 캐릭터, 허브 프레임,
    메뉴 글자 바꿈, 머리말 글자 바꿈, 재대결 상대, 대신 누르기 단계, 타이틀로 돌려보낸 횟수, 고른 배경(−1 상대 기본·−2 무작위·0~7),
    고르기 화면에 보이는 컬러(0~17 — look_cur), 무장한 경기의 배경(−1 없음)] */
void league_hub_status(int v[LEAGUE_NHUB])
{
   pthread_mutex_lock(&L_mu);
   v[0] = H.st; v[1] = H.hover; v[2] = H.picked; v[3] = (int)H.f;
   v[4] = H.label_menu; v[5] = H.label_hdr; v[6] = H.rematch; v[7] = H.nav; v[8] = (int)hub_gen;
   v[9] = H.stage_sel; v[10] = look_cur(); v[11] = L.armed ? L.stage : -1;
   v[12] = H.ev; v[13] = H.fx_on ? H.fx_ch : -1; v[14] = H.ranked;
   pthread_mutex_unlock(&L_mu);
}

/* 둘 다(실행 창 «리그·연습») — 허브를 켠 뒤(league_hub · league_practice_hub 는 끈 채로 시작) 켠다. 셋째 줄은 이 판의 종류부터 */
void league_hub_dual(int on)
{
   pthread_mutex_lock(&L_mu);
   H.dual = on && H.st != H_OFF; H.sel = H.kind; H.handoff = 0;
   pthread_mutex_unlock(&L_mu);
}
/* 갈아탄 새 판(리그 세션) — 로고·메뉴를 넘겨(4배속) 바로 상대 선택까지. 연습 판은 league_practice_hub(1, 1) 이 같은 일을 한다 */
void league_hub_go(void)
{
   pthread_mutex_lock(&L_mu);
   if (H.st != H_OFF) { H.st = H_RETURN; H.nav = 2; H.nav_f = H.f; H.repick = (H.kind == 0 && H.ranked) ? 2 : 1; H.rematch = -1; H.sel = H.kind; H.handoff = 0; }
   pthread_mutex_unlock(&L_mu);
}
/* [둘 다 켜짐, 셋째 줄에 보이는 종류(0 리그 · 1 연습), 갈아타기 요청(0 없음 · 1 리그 · 2 연습), 셋째 줄에 커서, 이 판의 종류,
    메뉴 화면(0 타이틀 · 2 캐릭터 · 4 검질 · 6 카드 · 8 대사 · −1 메뉴 밖)] */
void league_hub_dual_status(int v[LEAGUE_NDUAL])
{
   pthread_mutex_lock(&L_mu);
   v[0] = H.dual; v[1] = H.sel; v[2] = H.handoff; v[3] = H.focus && H.st == H_TITLE; v[4] = H.kind; v[5] = H.st != H_OFF ? hub_scr : -1;
   pthread_mutex_unlock(&L_mu);
}
/* 갈아타기를 못 했을 때(Java) — 요청을 지우고 셋째 줄을 이 판의 종류로 */
void league_hub_handoff_clear(void)
{
   pthread_mutex_lock(&L_mu);
   H.handoff = 0; H.sel = H.kind;
   pthread_mutex_unlock(&L_mu);
}

/* ── 랭크 매치 API ── */
/* 랭크 매치 켬/끔(리그 판 허브를 켠 뒤 Java 가 — league_hub 는 끈 채로 시작). 켬이면 셋째 줄 A = 상대 찾기 */
void league_hub_ranked(int on)
{
   pthread_mutex_lock(&L_mu);
   H.ranked = on && H.kind == 0 && H.st != H_OFF;
   pthread_mutex_unlock(&L_mu);
}
/* 상대 찾기 켬(1 — 타이틀 상태에서만)·취소(0 — 찾기·카드 중이면 타이틀 상태로, 커서는 셋째 줄 그대로) */
void league_hub_search(int on)
{
   pthread_mutex_lock(&L_mu);
   if (on && H.st == H_TITLE && H.ranked) { H.st = H_SEARCH; H.ev = 0; H.go = 0; }
   else if (!on && H.st == H_SEARCH) { H.st = H_TITLE; H.ev = 0; H.go = 0; }
   pthread_mutex_unlock(&L_mu);
}
/* 정해진 상대 — 상대 캐릭터·검질·난도(R_OPT, 0~2)·배경(0~7, −1 게임 그대로)·손잡이 4개(−1 그대로).
   go = 1 이면 찾기 중일 때 다음 프레임에 바로 내 캐릭터 고르기로(재대결 −4 는 go 0 으로 상대만 바꿔 둔다). 1 = 받음 */
int league_hub_match(int ch, int sty, int diff, int stage, int lv, int cap, int atk, int dfn, int floor, int go)
{
   int ok = 0;
   pthread_mutex_lock(&L_mu);
   if (ch >= 0 && ch <= 14 && (sty == 0 || sty == 1) && H.st != H_OFF && H.kind == 0) {
      H.fx_on = 1; H.fx_ch = ch; H.fx_sty = sty; H.fx_diff = (diff >= 0 && diff <= 2) ? diff : 1; H.fx_stage = stage;
      H.fx_k[0] = lv; H.fx_k[1] = cap; H.fx_k[2] = atk; H.fx_k[3] = dfn; H.fx_k[4] = floor;
      if (go && H.st == H_SEARCH) H.go = 1;
      ok = 1;
   }
   pthread_mutex_unlock(&L_mu);
   return ok;
}
/* 찾기·카드 중 모은 누름을 가져가며 비운다 */
int league_hub_take_ev(void)
{
   pthread_mutex_lock(&L_mu);
   int e = H.ev; H.ev = 0;
   pthread_mutex_unlock(&L_mu);
   return e;
}
/* 다음 league_arm 의 CPU 손잡이(체크포인트에서 이어 하는 랭크 경기) — −1 그대로 */
void league_cpu(int lv, int cap, int atk, int dfn, int floor)
{
   pthread_mutex_lock(&L_mu);
   knob_next[0] = lv; knob_next[1] = cap; knob_next[2] = atk; knob_next[3] = dfn; knob_next[4] = floor;
   pthread_mutex_unlock(&L_mu);
}

static void post_start(const uint8_t *ram, int input)
{
   memset(&PQ, 0, sizeof PQ);
   PQ.on = 1; PQ.prev_in = input;                     /* 결판 때 쥐고 있던 B 는 «건너뛰기»로 안 센다(뗐다 다시 눌러야) */
   league_in_block |= IN_GAME_ALL;
   if (ram[R_RSCR]) { PQ.on = 0; PQ.hold = 1; PQ.reason = PQ_QUOTE; }   /* 체크포인트가 이미 대사 뒤(멈춘 프레임) — 바로 */
}
static void post_tick(const uint8_t *ram, int input)
{
   PQ.f++;
   league_in_block |= IN_GAME_ALL;                   /* 대사 동안 게임 입력은 모두 막는다 — A 는 대사를 건너뛰고, 진 판에선 이어하기 화면의 A 가 «이어하기»가 된다(실측) */
   int b_edge = (input & IN_B) && !(PQ.prev_in & IN_B) && PQ.f > POST_BGRACE;   /* 쥔 채 넘어온 B·KO 직후 연타는 빼고 */
   PQ.prev_in = input;
   if (ram[R_SEQ] == 63 && !PQ.seen_quote) { PQ.seen_quote = 1; PQ.quote_f = PQ.f; }
   int why = ram[R_RSCR] ? PQ_QUOTE
           : b_edge ? PQ_SKIP
           : PQ.skip ? PQ_API                                                       /* 뒤로 가기(Java) — 이 진짜 프레임에서 */
           : (ram[R_MODE] != 241 || ram[R_SCR] == 0 || ram[R_SCR] == 2) ? PQ_SCENE   /* 대사 장면을 떠났다(0x5825 를 못 봤어도) */
           : PQ.f >= POST_MAX ? PQ_TIMEOUT : PQ_NONE;
   if (why) { PQ.on = 0; PQ.hold = 1; PQ.reason = why; }
}
int league_post_hold(void) { return PQ.hold; }      /* native.c 가 프레임마다 — 잠금 없이(int 하나) */
/* 건너뛰기 요청 — 걸쇠는 바로 세우지 않고 다음 진짜 프레임(post_tick)에서 세운다: 코어 2배 출력(120Hz) 중이면 합성 호출 뒤에
   멈춰 «사이 그림»이 남을 수 있어서. 그래서 멈춤(g_paused) 중이면 풀린 뒤에 선다(Java 는 메뉴 바를 닫고 부른다) */
void league_post_skip(void)
{
   pthread_mutex_lock(&L_mu);
   if (PQ.on) PQ.skip = 1;
   pthread_mutex_unlock(&L_mu);
}
void league_post_clear(void) { pthread_mutex_lock(&L_mu); post_clear_locked(); pthread_mutex_unlock(&L_mu); }

void league_tick(uint8_t *ram, size_t n) { league_tick_hub(ram, n, 0); }

void league_tick_hub(uint8_t *ram, size_t n, int input)
{
   if (!ram || n < 0x4000) return;
   pthread_mutex_lock(&L_mu);
   if (H.st != H_OFF) hub_tick(ram, input);
   if (PQ.on) post_tick(ram, input);                 /* 허브 다음 — 허브가 league_in_block 을 새로 쓴 뒤에 더한다 */
   if (!L.armed || L.done) { pthread_mutex_unlock(&L_mu); return; }
   L.frames++;

   ram[R_OPP] = (uint8_t)L.opp;
   ram[R_STY] = (uint8_t)L.sty;
   ram[R_CCARD1] = 0xFF; ram[R_CCARD2] = 0xFF;          /* CPU 카드 없음 — 리그 공통 규칙(1장 상대는 원래도 없다) */
   if (L.cards_off) { ram[R_CARD1] = 0xFF; ram[R_CARD2] = 0xFF; }
   if (L.diff   >= 0) ram[R_OPT + 0] = (uint8_t)L.diff;
   if (L.points >  0) ram[R_OPT + 1] = (uint8_t)L.points;
   if (L.timev  >  0) ram[R_OPT + 2] = (uint8_t)L.timev;
   if (L.allcards && ram[R_MODE] == 240 && (ram[R_SCR] == 2 || ram[R_SCR] == 4))
      memset(ram + R_OWN, 8, 120);
   if (L.stage >= 0) ram[R_BOUT] = (uint8_t)L.stage;     /* 배경 — 판이 뜨기 전부터 끝까지 */
   /* CPU 세기 손잡이 — 게임은 상대를 뽑는 프레임에 난도(R_OPT)로 이 값들을 정하고, 단계는 라운드마다·약 600프레임마다, 배율은 맞을 때마다 읽는다.
      무장부터 결판까지 매 프레임 쓰면 그 값으로 싸운다(실측 E2: 무장부터 쓰기 = 첫 라운드 상태에서 바로 쓰기, 피해 기록이 프레임까지 같음) */
   if (L.k_lv  >= 0) ram[R_CPULV] = (uint8_t)L.k_lv;
   if (L.k_cap >= 0) ram[R_CPUCAP] = (uint8_t)L.k_cap;
   if (L.k_atk >= 0) { ram[R_CPUMUL] = (uint8_t)L.k_atk; ram[R_CPUMUL + 1] = (uint8_t)(L.k_atk >> 8); }
   if (L.k_dfn >= 0) { ram[R_CPUMUL + 2] = (uint8_t)L.k_dfn; ram[R_CPUMUL + 3] = (uint8_t)(L.k_dfn >> 8); }
   if (L.k_floor > 0 && (L.fight_seen || L.from_ckpt) && ram[R_MODE] == 241 && ram[R_P2LV] < L.k_floor) ram[R_P2LV] = (uint8_t)L.k_floor;   /* 실측 E6 */

   /* 메뉴 자동 진입: 무장 뒤 20·60 프레임에 A(4프레임씩, 사이는 뗀다). 캐릭터 고르기(MODE 240 · SCR 2)가
      30프레임 이어지면 끝(데모·이어하기 화면도 잠깐 SCR 2 라서 이어짐까지 본다). 400프레임 안에 못 닿으면 손을 뗀다. */
   if (L.autonav) {
      unsigned f = L.frames + 1;                        /* 다음 프레임에 들어갈 입력 */
      league_ovr_mask = ((f >= 20 && f < 24) || (f >= 60 && f < 64)) ? 1 : 0;
      league_ovr_on = 1;
      if (L.frames > 64 && ram[R_MODE] == 240 && ram[R_SCR] == 2) L.nav_ok++; else L.nav_ok = 0;
      if (L.nav_ok >= 30 || L.frames > 400) { L.autonav = 0; league_ovr_on = 0; league_ovr_mask = 0; }
   }

   /* 경기 시작 — 첫 라운드(0:0, 이어하기 아님)가 조작 가능해지는 순간. 대진이 맞는지 이때 P2 블록으로도 남긴다 */
   int rs = ram[R_MODE] == 241 && ram[R_FIGHT] == 1 && ram[R_RES] == 0;
   if (rs && !L.rs_prev && !L.fight_seen && ram[R_W1] == 0 && ram[R_W2] == 0 && ram[R_CONT] == 0) {
      L.fight_seen = 1; L.fight_frame = L.frames; L.p2blk_first = ram[R_P2BLK];
   }
   L.rs_prev = rs;

   uint8_t r = ram[R_RES];
   if (!(r & 0x80)) L.seen_open = 1;
   else if (L.seen_open) {
      L.done = 1; L.done_frame = L.frames;
      L.autonav = 0; league_ovr_on = 0; league_ovr_mask = 0;
      L.bits = r; L.w1 = ram[R_W1]; L.w2 = ram[R_W2]; L.ko = ram[R_KO];
      L.need = ram[R_NEED]; L.p1blk = ram[R_P1BLK]; L.p2blk = ram[R_P2BLK];
      L.card1 = ram[R_CARD1]; L.card2 = ram[R_CARD2]; L.timev_eff = ram[R_TIME];
      L.cpu_lv = ram[R_CPULV];                        /* 난도가 실제로 먹었는지의 증거 — 정산 기록에 남긴다 */
      L.cpu_atk = ram[R_CPUMUL] | (ram[R_CPUMUL + 1] << 8); L.cpu_def = ram[R_CPUMUL + 2] | (ram[R_CPUMUL + 3] << 8);
      L.cap_seen = ram[R_CPUCAP];
      if (!(H.st != H_OFF && H.kind == 1)) post_start(ram, input);   /* 승리 대사까지 게임을 더 돌린다(149) — 리그·랭크만(연습 대전 허브도 무장하지만 거기선 멈추지 않는다) */
   }
   pthread_mutex_unlock(&L_mu);
}

/* JNI 없이도 부를 수 있게(호스트 시험 하네스가 ctypes 로 같은 코드를 돌린다) 몸통은 평범한 C 함수로 둔다. */
static int clampi(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
static void arm_locked(int opp, int sty, int diff, int points, int timev, int flags, const int *knob)
{
   memset(&L, 0, sizeof L);
   post_clear_locked();
   /* 손잡이: 단계 0~15 · 상한 단계~15 · 배율 0x0080~0x0180(게임이 쓰는 값은 0x00B5~0x0140 — 앱은 그 안에서 고른다) */
   L.k_lv  = knob && knob[0] >= 0 ? clampi(knob[0], 0, 15) : -1;
   L.k_cap = knob && knob[1] >= 0 ? clampi(knob[1], L.k_lv > 0 ? L.k_lv : 0, 15) : -1;
   L.k_atk = knob && knob[2] >= 0 ? clampi(knob[2], 0x80, 0x180) : -1;
   L.k_dfn = knob && knob[3] >= 0 ? clampi(knob[3], 0x80, 0x180) : -1;
   L.k_floor = knob && knob[4] > 0 ? clampi(knob[4], 0, L.k_cap >= 0 ? L.k_cap : 15) : -1;
   L.opp = opp & 0x0F; L.sty = sty & 1;
   L.diff = diff; L.points = points; L.timev = timev;
   L.cards_off = (flags & LEAGUE_CARDS_OFF) ? 1 : 0;
   L.allcards  = (flags & LEAGUE_ALLCARDS) ? 1 : 0;
   L.autonav   = (flags & LEAGUE_AUTONAV) ? 1 : 0;
   L.rs_prev   = 1;                                   /* 무장 순간 이미 라운드 중이면(체크포인트) 그 라운드는 «시작»으로 안 센다 */
   /* 체크포인트에서 재개: 랭크전 판은 램이 0 인 새 부팅에서 시작하므로, 체크포인트 안에서 bit7 이 이미 서 있다면
      그건 이 경기의 결판뿐이다(Java 가 정산하기 전에 앱이 내려간 경우). 첫 틱에 그대로 결과로 잡는다. */
   L.seen_open = (flags & LEAGUE_FROM_CKPT) ? 1 : 0;
   L.from_ckpt = (flags & LEAGUE_FROM_CKPT) ? 1 : 0;
   L.stage = look_stage; L.color = look_color;
   if (H.nav == 0) { league_ovr_on = 0; league_ovr_mask = 0; }   /* 허브가 대신 누르는 중이면 그대로 */
   L.armed = 1;
}

void league_arm(int opp, int sty, int diff, int points, int timev, int flags)
{
   pthread_mutex_lock(&L_mu);
   arm_locked(opp, sty, diff, points, timev, flags, knob_next);
   for (int i = 0; i < NKNOB; i++) knob_next[i] = -1;    /* 한 번만 — 다음 무장(예전 리그 경기 등)으로 새지 않게 */
   pthread_mutex_unlock(&L_mu);
}

void league_disarm(void)
{
   pthread_mutex_lock(&L_mu); L.armed = 0; league_ovr_on = 0; league_ovr_mask = 0; pthread_mutex_unlock(&L_mu);
}

/* [무장, 프레임, bit7 0 봄, 경기 시작 봄, 결과 잡음, 결과 비트, 승수1, 승수2, KO, 실효 포인트,
    P1 블록, P2 블록(결판), P2 블록(경기 시작), 경기 시작 프레임, 결판 프레임, 사용자 카드 1, 2, 실효 시간,
    CPU 단계, CPU 공격 배율, CPU 받는 피해 배율] */
void league_status(int v[LEAGUE_NSTAT])
{
   pthread_mutex_lock(&L_mu);
   v[0] = L.armed; v[1] = (int)L.frames; v[2] = L.seen_open; v[3] = L.fight_seen; v[4] = L.done;
   v[5] = L.bits; v[6] = L.w1; v[7] = L.w2; v[8] = L.ko; v[9] = L.need;
   v[10] = L.p1blk; v[11] = L.p2blk; v[12] = L.p2blk_first;
   v[13] = (int)L.fight_frame; v[14] = (int)L.done_frame;
   v[15] = L.done ? L.card1 : 0xFF; v[16] = L.done ? L.card2 : 0xFF;
   v[17] = L.timev_eff;
   v[18] = L.cpu_lv; v[19] = L.cpu_atk; v[20] = L.cpu_def;
   v[21] = L.cap_seen;
   v[22] = PQ.on ? 1 : PQ.hold ? 2 : 0;              /* 결판 뒤: 0 없음 · 1 대사 도는 중 · 2 멈춤 걸쇠(결과 카드 차례) */
   v[23] = PQ.reason; v[24] = (int)PQ.f; v[25] = PQ.seen_quote ? (int)PQ.quote_f : -1;
   pthread_mutex_unlock(&L_mu);
}

#define JNI(ret, name) JNIEXPORT ret JNICALL Java_com_dudu_pocketcore_Emu_##name

JNI(void, nativeLeagueArm)(JNIEnv *env, jclass cls, jint opp, jint sty, jint diff, jint points,
                           jint timev, jint flags)
{ (void)env; (void)cls; league_arm(opp, sty, diff, points, timev, flags); }

JNI(void, nativeLeagueDisarm)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; league_disarm(); }

/* 149: 승리 대사 건너뛰기(뒤로 가기) — 다음 진짜 프레임에 걸쇠(B 는 post_tick 이 입력에서 직접 본다) */
JNI(void, nativeLeaguePostSkip)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; league_post_skip(); }

JNI(void, nativeLeagueHub)(JNIEnv *env, jclass cls, jboolean on, jbyteArray sty, jbyteArray dif)
{
   (void)cls;
   signed char s[15], d[15];
   for (int i = 0; i < 15; i++) { s[i] = -1; d[i] = 1; }
   if (sty && (*env)->GetArrayLength(env, sty) >= 15) (*env)->GetByteArrayRegion(env, sty, 0, 15, (jbyte *)s);
   if (dif && (*env)->GetArrayLength(env, dif) >= 15) (*env)->GetByteArrayRegion(env, dif, 0, 15, (jbyte *)d);
   league_hub(on ? 1 : 0, s, d);
}

JNI(void, nativeLeagueHubCancel)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; league_hub_cancel(); }

JNI(void, nativeHubDual)(JNIEnv *env, jclass cls, jboolean on)
{ (void)env; (void)cls; league_hub_dual(on ? 1 : 0); }

JNI(void, nativeHubGo)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; league_hub_go(); }

JNI(void, nativeHubHandoffClear)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; league_hub_handoff_clear(); }

JNI(jintArray, nativeHubDualStatus)(JNIEnv *env, jclass cls)
{
   (void)cls;
   int v[LEAGUE_NDUAL];
   jint jv[LEAGUE_NDUAL];
   league_hub_dual_status(v);
   for (int i = 0; i < LEAGUE_NDUAL; i++) jv[i] = v[i];
   jintArray a = (*env)->NewIntArray(env, LEAGUE_NDUAL);
   if (a) (*env)->SetIntArrayRegion(env, a, 0, LEAGUE_NDUAL, jv);
   return a;
}

JNI(void, nativeHubRanked)(JNIEnv *env, jclass cls, jboolean on)
{ (void)env; (void)cls; league_hub_ranked(on ? 1 : 0); }
JNI(void, nativeHubSearch)(JNIEnv *env, jclass cls, jboolean on)
{ (void)env; (void)cls; league_hub_search(on ? 1 : 0); }
JNI(jboolean, nativeHubMatch)(JNIEnv *env, jclass cls, jint ch, jint sty, jint diff, jint stage, jint lv, jint cap, jint atk, jint dfn, jint floor, jboolean go)
{ (void)env; (void)cls; return league_hub_match(ch, sty, diff, stage, lv, cap, atk, dfn, floor, go ? 1 : 0) ? JNI_TRUE : JNI_FALSE; }
JNI(jint, nativeHubTakeEv)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return league_hub_take_ev(); }
JNI(void, nativeLeagueCpu)(JNIEnv *env, jclass cls, jint lv, jint cap, jint atk, jint dfn, jint floor)
{ (void)env; (void)cls; league_cpu(lv, cap, atk, dfn, floor); }

JNI(void, nativeLeagueHubLook)(JNIEnv *env, jclass cls, jbyteArray home, jint stageSel, jint color)
{
   (void)cls;
   signed char h[15];
   for (int i = 0; i < 15; i++) h[i] = -1;
   if (home && (*env)->GetArrayLength(env, home) >= 15) (*env)->GetByteArrayRegion(env, home, 0, 15, (jbyte *)h);
   league_hub_look(h, stageSel, color);
}

JNI(void, nativeLeagueLook)(JNIEnv *env, jclass cls, jint stage, jint color)
{ (void)env; (void)cls; league_look(stage, color); }

JNI(jintArray, nativeLeagueHubStatus)(JNIEnv *env, jclass cls)
{
   (void)cls;
   int v[LEAGUE_NHUB];
   jint jv[LEAGUE_NHUB];
   league_hub_status(v);
   for (int i = 0; i < LEAGUE_NHUB; i++) jv[i] = v[i];
   jintArray a = (*env)->NewIntArray(env, LEAGUE_NHUB);
   if (a) (*env)->SetIntArrayRegion(env, a, 0, LEAGUE_NHUB, jv);
   return a;
}

JNI(jintArray, nativeLeagueStatus)(JNIEnv *env, jclass cls)
{
   (void)cls;
   int v[LEAGUE_NSTAT];
   jint jv[LEAGUE_NSTAT];
   league_status(v);
   for (int i = 0; i < LEAGUE_NSTAT; i++) jv[i] = v[i];
   jintArray a = (*env)->NewIntArray(env, LEAGUE_NSTAT);
   if (a) (*env)->SetIntArrayRegion(env, a, 0, LEAGUE_NSTAT, jv);
   return a;
}

/* 허브가 고르기 화면 입력을 쓰는 중인가 — 상대 선택(OPTION 스테이지·A/B 고르기)·대신 누르기 동안 1(look 은 컬러를 안 만진다) */
int league_hub_owns_select(void)
{
   pthread_mutex_lock(&L_mu);
   int v = H.st == H_PICK_OPP || H.nav != 0 || league_ovr_on;
   pthread_mutex_unlock(&L_mu);
   return v;
}
