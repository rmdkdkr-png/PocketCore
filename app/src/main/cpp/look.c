/* 모든 모드 컬러 — look.h 참고. 리그 허브(league.c)에 있던 컬러 코드(PAL1·look_apply·look_preview·코어 바꿔 쓰기 표)를 떼어 와
   허브와 상관없이 매 프레임 돈다. 표는 tools/look_gen.py 가 만든 look_tab.h(프리셋 16 × 캐릭터 14 + 쿠로코 + 유가 × 검질 2 — 177: 프리셋 2~15 는 검질 무관), 세로 글자·아이콘은
   hub_glyphs.py 가 만든 hub_glyphs.h 의 LOOK_VLAB·SLASH_VLAB·SLASH_ICON, 베기 색은 slash.c(표 slash_tab.h).

   고르기(캐릭터 SCR 2 · 검질 SCR 4, MODE 240, 오른쪽 세로 칸이 있을 때만 — 이어하기 화면·인트로도 SCR 2/4 라서 칸으로 가린다, 실측)
     OPTION    짧게(뗄 때, 40프레임 미만): 커서 캐릭터의 컬러를 다음 것으로(그 캐릭터 기억 칸을 바꾼다 — 다음에도 그 색). 그 캐릭터에서
               앞 것과 같은 색·안 만든 내 색·옛 2P색(번호 1 — 165 부터 안 고름)은 건너뛴다. 길게(40프레임째): 만들기 요청(Java 가 «컬러 만들기» 창을 연다).
               «뗄 때» 바꾸는 것은 길게 누르기와 가르려고 — 짧게 누르면 차이를 못 느낀다.
     A·B·A+B   165: 쥔 동안 플레이어의 A·B·A+B(·SP 접기) 는 게임에 안 준다. 누른 프레임부터 LOOK_AB_WIN(4) 프레임 창 안에 다른 쪽이 눌리기 시작하면
               (창 밖부터 쥐고 있던 단추 — 앞 화면 결정에 쓴 것·오래 쥔 것 — 는 A+B 의 한쪽으로 안 셈)
               A+B = 내 베기 색 다음 것(결정 아님 — 아이콘·세로 칸 이름 1.2초), 아니면 창 끝에 결정: 다음 프레임부터 4프레임 A 를 대신 누른다
               (B 도 A 와 같은 보통 색 결정 — 게임은 플레이어의 B 결정을 못 받는다. 검질 고르기의 B 만 게임의 «뒤로»라 B 를 대신 누름).
               패드 «A+B»(비트 1 Y)·물리 X(비트 10 L)는 기다리지 않고 바로 A+B. 결정·A+B 뒤엔 셋 다 뗄 때까지 먹는다(쥐어도 되풀이 안 함 ·
               화면이 바뀌어 쥠이 풀려도 쥔 단추는 뗄 때까지 막는다 — 게임이 다시 누른 것으로 보지 않게). 창 동안·대신 누르기 6프레임째까지 십자를 막는다.
               HOLD(연습 «내 캐릭터 먼저»): 대신 누르지 않고 결정을 허브에 넘긴다(look_take_confirm).
     미리보기   가운데 그림(스프라이트 팔레트 0)을 보이는 색으로 — 코어 바꿔 쓰기 두 자리(두 검질) + 프레임 뒤 고쳐 쓰기.
     세로 칸    컬러 이름(LOOK_VLAB) — A+B 뒤 1.2초는 «베기 + 이름»(SLASH_VLAB), OPTION 짧게면 바로 컬러 이름.
     아이콘     그 위 문양 칸에 베기 색 아이콘(SLASH_ICON, 색 = 프리셋 세 색 · 원래 색이면 커서 캐릭터의 원래 +2 궤적) — vpanel 이 빌리고 되돌린다.
   경기 컬러(sel)·경기 베기 색(fx_sel)은 고르기 화면에서 보이는 것을 따라가다 고르기를 떠나면 그대로 남는다 → 1인 대전 내내(이야기·라운드 사이·이어하기 화면 포함).
   타이틀 메뉴가 뜨면 잊는다(데모·인트로가 팔레트 0~4 에 캐릭터를 그려서 — 새지 않게).
   경기 표: P1 캐릭터 = 고를 때 커서 캐릭터(무작위 칸이면 0x57D5 — 검질 확정 뒤 ~160프레임에 게임이 씀). 두 검질 모두 «기본 색 → 고른 색»,
     그 판 검질(전투 중 P1 블록 0x5B51 이 그 캐릭터면 블록, 아니면 0x574C — 블록은 이야기 화면 동안 옛 값)을 자리 0 에.
     165: 플레이어 2P색이 없어져 미러전 P2 바꾸기도 없다 — 게임이 같은 캐릭터 거울전에서 CPU 에게 주는 2P 팔레트는 그대로(팔레트 5 는 P2 커스텀 컬러 몫으로 비워 둠).
   팔레트 0 은 P1 몸만 쓴다(필살기·잔상·짐승 짝꿍·이펙트는 다른 팔레트, 실측 4명 × 기술 6개). 짝꿍 짐승(3·4)은 원래 색 — 게임 2P색과 같은 규칙.
   베기 색(slash.c): 경기 쪽 창(slash_window — 전투 로드·인트로·라운드)에서 P1 효과 팔레트 1~4(목록 +1~+4 · 기술 중 +1 둘)와 내가 벤 섬광(팔레트 11 의 BA)을
     같은 바꿔 쓰기 표로 + 프레임 뒤 보정(slash_fix). 창을 닫으면 우리 색인 팔레트를 게임 색으로 되돌린다. 상대(6~10)·짝꿍·B7 은 원래 색.

   코어 바꿔 쓰기 표는 이 파일이 유일한 주인 — 스프라이트 팔레트 0~11 × 자리 2 × 색 1~3(idx = 팔레트×4 + 1 + 색). 0 몸 · 1~4 P1 효과 · 11 내가 벤 섬광 ·
   5(P2 몸)·6~9(P2 효과)·10(P2 가 벤 섬광)은 상대 커스텀 컬러 때. 동봉 코어(0966bed) 자리 2개 안에 다 든다.

   잠금: 자기 잠금 K_mu 만. league_hub_status(L_mu) 가 look_cur(K_mu) 를 부르므로 순서는 L_mu → K_mu 하나뿐 — 여기서는 L_mu 를 안 잡는다.
   팔레트·화면 램을 직접 쓰는 일은 look_tick·look_before_save·look_after_save·look_after_load(코어 잠금 안)에서만. Java(UI 스레드)의 nativeLook* 는 K_mu 만 잡고 값만 바꾼다. */
#include <jni.h>
#include <pthread.h>
#include <string.h>
#include "look.h"
#include "look_tab.h"
#define HUB_WANT_LOOK
#include "hub_glyphs.h"
#include "vpanel.h"
#include "slash.h"

#define RAM_BASE 0x4000
#define A(x) ((x) - RAM_BASE)
#define R_MODE  A(0x40A7)   /* 240 메뉴·연출 / 241 전투측 */
#define R_SCR   A(0x41C0)   /* 메뉴일 때 0 타이틀 · 2 캐릭터 · 4 검질 · 6 카드 (이어하기 화면도 SCR 2) */
#define R_HOVER A(0x4E3C)   /* 캐릭터 고르기 커서 = 캐릭터 × 4 (가운데 무작위 칸 = 60) — 무작위로 결정하면 검질 고르기 동안 뽑기처럼 돌다 실제 캐릭터에서 멈춤 */
#define R_P1CH  A(0x57D5)   /* P1 캐릭터 — 검질 확정 뒤 약 160프레임에 씀(그 전 255). 다음 판에서 다시 쓸 때까지 옛 값이 남는다 */
#define R_STYC  A(0x574C)   /* 검질 고르기 커서 0 수라 · 1 나찰(확정 뒤에도 남음) — 실측: 이 바이트만 미리보기 팔레트의 검질과 늘 같다 */
#define R_P1BLK A(0x5B51)   /* (캐릭터<<4)|(검질<<3)|(미러 0x04) — 전투 로드 때 선다 */
#define R_P2BLK A(0x5D51)   /* 177: P2 블록(같은 꼴) — 인트로 첫 프레임에 선다. 리셋 직후 첫 대전 전까지는 P1·P2 둘 다 0(실측) */
#define R_P1STY A(0x57D6)   /* 177: P1 검질 — 0x57D5 와 같이 검질 확정 때 씀(무작위 칸이면 실제 검질 · 유가 치트면 14/검질) */
#define IN_A   1
#define IN_OPT (1 << 3)
#define IN_DPAD (0xF << 4)
#define IN_B   (1 << 8)
#define IN_ABK ((1 << 1) | (1 << 10))   /* 코어가 A+B 로 접는 단추(패드 «A+B» = Y · 물리 X = L) — 165: 고르기 화면에선 바로 A+B(베기 색) */
#define IN_ABFOLD ((1 << 1) | (1 << 9) | (1 << 10) | (1 << 11))   /* 코어가 A+B·원버튼으로 접는 패드 비트(Y·X·L·R) — A·B 를 막을 땐 같이 막는다 */
#define IN_ALL 0xFFF
#define LONG_F 40           /* OPTION 길게 = 컬러 만들기(0.7초) */
#define RM_NP  SLASH_NPAL   /* 바꿔 쓰기 표 — 스프라이트 팔레트 0~11 */

volatile int look_in_block = 0, look_in_or = 0;
static pthread_mutex_t K_mu = PTHREAD_MUTEX_INITIALIZER;
static uint8_t *g_pal;                /* 코어 팔레트 램(0x8200~, 스프라이트 팔레트 16 × 4색 × 2바이트) */
/* 코어의 팔레트 바꿔 쓰기(retro_ngp_palremap). 코어 판이 둘 — 0966bed 판(151 부터 동봉)은 한 팔레트의 색 1~3 이 모두 한 자리의 from 이 되는
   순간 셋 다 to 로, 옛 판(1249a9e — 150 까지 동봉·바깥 코어)은 칸마다 따로(자리 0 부터, 맞는 자리에서 멈춤). 어느 판이든 맞게: 보일 검질을
   자리 0 에(같은 색이어도 적는다), 미리보기는 옛 표로 섞인 세 색도 알아본다(preview_fix). 게임은 몸 팔레트를 롬에서
   자주 다시 쓴다(대전 중 1초에 한 번꼴, 실측) — 프레임 뒤에 고쳐 쓰기만 하면 그때마다 원래 색이 1~3 프레임 보인다. 표는 상태 저장에 안 들어가서
   매 프레임 정하고 바뀐 칸만 넣는다. 칸: 스프라이트 팔레트 0~11 의 색 1~3 × 자리 2 */
static void (*g_remap)(int idx, int slot, int from, int to);
typedef int rm_t[RM_NP][2][3][2];     /* [팔레트][자리][색 1~3] = {from, to} — from −1 = 비움 */
static rm_t rm_have;
static int rm_init;                   /* 0 이면 다음에 코어 표를 통째로 비우고 시작(코어를 새로 올림) */
static slash_want SW;                 /* 지금 넣은 베기 색 표(P1 효과·내가 벤 섬광) — 창을 닫거나 바뀌면 우리 색을 게임 색으로 되돌린다 */
enum { G_IDLE = 0, G_WAIT, G_SWALLOW };

static struct {
   int on;                            /* Java 가 켬 */
   signed char mem[LOOK_NSLOT];       /* 기억 칸: 0~13 캐릭터 · 14 안 씀 · 15 가운데 무작위 칸 — 값 0·2~17 */
   uint16_t cus[14][2][3];            /* 내 색 1·2 — 캐릭터마다, 두 검질 같이(세 칸 역할이 두 검질에서 같고 검질을 바꿔도 같은 모습이어야 덜 헷갈림) */
   uint8_t cus_ok[14][2];
   int cur;                           /* 고르기 화면에 보이는 컬러 0·2~17 */
   int sel, sel_ch;                   /* 경기 컬러(−1 없음) · 그때 캐릭터(−1 = 0x57D5 로) */
   int fx, fx_sel;                    /* 내 베기 색(0~6, 하나로 기억) · 경기 베기 색(고르기 화면에서 fx 를 따라감) — 상대 베기 색은 OP.fx(177) */
   int prev_in, owned, inj, inj_f, inj_bit, last_scr;
   int g_st, g_first, g_scr0; unsigned g_f0;   /* A·B·A+B 가르기 — 상태 · 먼저 누른 단추 · 그때 화면 · 그때 프레임 */
   int hold;                          /* 결정·A+B 뒤 뗄 때까지 막는 단추(A·B·A+B) — 쥠이 풀려도 */
   int a_age, b_age;                  /* A·B 를 누르기 시작한 뒤 지난 프레임(255 에서 멈춤 — 쥠 밖의 누름도 센다). A+B 의 «다른 쪽»은 창 안에 눌린 것만 */
   int conf_pend, conf_n;             /* HOLD: 허브가 가져갈 결정 · 결정 횟수 */
   int toast_f;                       /* 이 프레임까지 세로 칸에 «베기 + 이름» */
   int opt_f, opt_own;                /* OPTION 누른 프레임 · 그 누름이 고르기 화면을 쥔 채 시작했나(밖에서 누른 채 들어온 것은 안 센다) */
   int edit; uint16_t edit_c[3];      /* 컬러 만들기 창이 열려 있음 · 그 색 */
   uint16_t pv_last[3]; int pv_has, pv_sty;   /* 미리보기에 마지막으로 쓴(보인) 세 색·그 검질 — 만드는 색을 바꾸거나 창을 닫으면 그 색은 표에 없어서, 이것으로 «우리 색»을 알아본다 */
   int prow, psty, pslot, sty_seen;   /* 미리보기 캐릭터 줄·검질·기억 칸(상태 저장 직전 되돌리기·만들기 창) · 검질 고르기에서 본 검질(−1) */
   int rand_pick;                     /* 가운데 무작위 칸에서 결정했다 — 검질 고르기 동안 게임이 커서를 뽑기처럼 돌려 실제 캐릭터에서 멈춘다(실측) */
   int rand_pin;                      /* 무작위 칸에서 뽑혔던 캐릭터 + 1 — 허브가 그 칸으로 다시 고를 때 무작위 칸의 컬러 기억(look_set_pick · 타이틀에서 잊음) */
   const uint8_t *vp_lab;             /* 마지막에 세로 칸에 쓴 글자(저장 직후 다시 쓰기) */
   uint16_t ic_c[3];                  /* 마지막 아이콘 세 색 */
   const uint8_t *ram;
   int mem_gen, sel_gen, edit_req, fx_gen;    /* Java 가 보는 바뀜 셈 */
   unsigned f;
} K = { .sel = -1, .sel_ch = -1, .sty_seen = -1, .prow = -1, .last_scr = -1, .a_age = 255, .b_age = 255 };

/* ── 177 상대 컬러(리그 랭크 경기만 — look.h OPP_*) ──
   on·ch·sty·c = Java 가 넣은 주 색(nativeLookOpp) · fx = 상대 베기 색 · live = 리그가 칠해도 된다고 함(native.c 가 프레임마다) · grace = 불러온 뒤 남은 프레임 ·
   state = 이 프레임에 칠했나(0 아님 · 1 주 색 · 2 대신 색) · fb = 대신 색 번호(−1) · show = 지금 칠하는 세 색(저장 앞뒤·바뀔 때 «우리 것»을 알아봄) */
static const signed char OPP_FB[8] = { 1, 8, 7, 9, 2, 5, 3, 0 };   /* 대신 색 차례: 2P색(게임 자신의 거울 구분) · 흰옷 · 먹빛 · 황금 · 쪽빛 · 다홍 · 비취 · 기본 */
static struct { int on, ch, sty, fx, live, grace, state, fb; uint16_t c[3], show[3]; } OP = { .fb = -1 };
static int tdist(const uint16_t a[3], const uint16_t b[3])      /* 세 색 채널 차 합(0~135) — 생성기 colorgen.dist · Java OppLook.dist 와 같은 식 */
{
   int d = 0;
   for (int j = 0; j < 3; j++) for (int s = 0; s < 12; s += 4) { int x = (a[j] >> s) & 15, y = (b[j] >> s) & 15; d += x > y ? x - y : y - x; }
   return d;
}
static int opp_row(int ch) { return ch == 14 ? LOOK_ROW_YUGA : ch; }   /* 상대 ch 0~14 → 표 줄(유가 15 — 쿠로코 줄 14 는 고르기 화면 미리보기 전용) */

void look_vram(void *chr, void *scr, void *pal) { vpanel_vram(chr, scr); vpanel_pal(pal); g_pal = (uint8_t *)pal; }
void look_palremap_fn(void *fn) { if ((void *)g_remap != fn) rm_init = 0; *(void **)&g_remap = fn; }

/* 표의 세 색 — 16·17 은 그 캐릭터의 내 색(없으면 기본). 쿠로코 줄(14)·유가 줄(15)엔 내 색이 없다.
   177(166-5): 프리셋 2~15 는 검질과 무관 — 표도 두 검질 같은 값이지만(look_gen.py) 옛 표가 와도 수라 판 값으로(런타임 보증) */
static void tri(int row, int sty, int idx, uint16_t o[3])
{
   if (idx >= 2 && idx < LOOK_NPRE) sty = 0;
   const uint16_t *t = LOOK_TAB[row][sty][0];
   if (idx >= 0 && idx < LOOK_NPRE) t = LOOK_TAB[row][sty][idx];
   else if (idx >= LOOK_NPRE && idx < LOOK_NIDX && row < 14 && K.cus_ok[row][idx - LOOK_NPRE]) t = K.cus[row][idx - LOOK_NPRE];
   memcpy(o, t, 6);
}
/* 그 캐릭터에서 쓸 수 있는 번호인가 — sty −1 = 캐릭터 고르기(두 검질 모두 앞 프리셋과 같을 때만 건너뜀). 번호 1(옛 2P색)은 LOOK_DUP 비트 1 로 늘 안 됨 */
static int idx_ok(int row, int idx, int sty)
{
   if (idx < 0 || idx >= LOOK_NIDX) return 0;
   if (idx < LOOK_NPRE) {
      int d0 = (LOOK_DUP[row][0] >> idx) & 1, d1 = (LOOK_DUP[row][1] >> idx) & 1;
      return sty == 0 ? !d0 : sty == 1 ? !d1 : !(d0 && d1);
   }
   return row < 14 && K.cus_ok[row][idx - LOOK_NPRE];
}
static uint16_t pal_rd(int p, int j) { const uint8_t *e = g_pal + p * 8 + (j + 1) * 2; return (uint16_t)((e[0] | (e[1] << 8)) & 0xFFF); }
static void pal_wr(int p, const uint16_t c[3]) { for (int j = 0; j < 3; j++) { uint8_t *e = g_pal + p * 8 + (j + 1) * 2; e[0] = (uint8_t)c[j]; e[1] = (uint8_t)(c[j] >> 8); } }
/* 세 색이 모두 같을 때만 «그 색» — 맞을 때 번쩍임·분노처럼 게임이 잠깐 쓴 다른 세 색은 그대로 둔다(0966bed 코어도 세 칸을 함께 본다) */
static int pal_is(int p, const uint16_t c[3]) { return pal_rd(p, 0) == c[0] && pal_rd(p, 1) == c[1] && pal_rd(p, 2) == c[2]; }

static void rm_clear(rm_t w) { for (int p = 0; p < RM_NP; p++) for (int k = 0; k < 2; k++) for (int j = 0; j < 3; j++) { w[p][k][j][0] = -1; w[p][k][j][1] = 0; } }
static void rm_push(rm_t want)
{
   if (!g_remap) return;
   if (!rm_init) {
      g_remap(-1, 0, 0, 0);
      rm_clear(rm_have);
      rm_init = 1;
   }
   for (int p = 0; p < RM_NP; p++)
      for (int k = 0; k < 2; k++)
         for (int j = 0; j < 3; j++) {
            int *w = want[p][k][j], *h = rm_have[p][k][j];
            if (w[0] != h[0] || (w[0] >= 0 && w[1] != h[1])) {
               g_remap(p * 4 + 1 + j, k, w[0], w[1]);
               h[0] = w[0]; h[1] = w[1];
            }
         }
}
/* 표 한 줄(세 칸)을 «from → to» 로 — 0966bed 코어는 한 팔레트의 세 칸이 모두 같은 자리에 있어야 바꾼다(같은 색 칸도 적는다 · 옛 코어는 칸마다) */
static void rm_want(rm_t want, int p, int slot, const uint16_t from[3], const uint16_t to[3])
{
   for (int j = 0; j < 3; j++) { want[p][slot][j][0] = from[j]; want[p][slot][j][1] = to[j]; }
}
static int next_idx(int row, int cur, int sty)
{
   for (int s = 1; s <= LOOK_NIDX; s++) { int i = (cur + s) % LOOK_NIDX; if (idx_ok(row, i, sty)) return i; }
   return 0;
}
/* 경기 P1 검질 — 전투 중이고 P1 블록이 그 캐릭터면 블록의 검질, 아니면 검질 고르기 커서(0x574C — 확정 뒤에도 남음) */
static int p1_sty(const uint8_t *ram, int row) { return (ram[R_MODE] == 241 && (ram[R_P1BLK] >> 4) == row) ? (ram[R_P1BLK] >> 3) & 1 : ram[R_STYC] & 1; }
/* 팔레트 0 이 이 프레임 동안 코어 표(rm_have — rm_push 는 프레임 뒤라 아직 지난 프레임에 넣은 표)로 바뀌기 전엔 c 였을 수 있나 — 칸마다
   c 그대로이거나, 그 칸 자리의 from 이 c 이고 to 가 지금 색. 커서를 옮긴 프레임에 게임이 새 캐릭터 기본 색을 쓸 때 표는 아직 옛 캐릭터 것이라
   옛 코어(칸마다)는 옛 기본 색과 같은 칸만 옛 캐릭터의 고른 색으로 바꿔 섞인 세 색을 만든다(게임은 가만히 있는 고르기 화면에서 다시 안 쓴다, 실측) */
static int pal_was(const uint16_t c[3])
{
   for (int j = 0; j < 3; j++) {
      uint16_t v = pal_rd(0, j); int ok = v == c[j];
      for (int k = 0; k < 2 && !ok; k++) ok = rm_have[0][k][j][0] == c[j] && rm_have[0][k][j][1] == v;
      if (!ok) return 0;
   }
   return 1;
}
/* 지금 팔레트 0 이 그 캐릭터·검질의 어느 컬러인가 — 그대로, 맞는 게 없으면 코어 표를 되돌려 한 번 더(rm_init — 표가 이 코어에 넣은 것일 때만) */
static int pv_known(int row, int sty)
{
   uint16_t t[3];
   for (int i = 0; i < LOOK_NIDX; i++) { if (i >= LOOK_NPRE && !idx_ok(row, i, sty)) continue; tri(row, sty, i, t); if (pal_is(0, t)) return 1; }
   for (int i = 0; i < LOOK_NIDX && rm_init; i++) { if (i >= LOOK_NPRE && !idx_ok(row, i, sty)) continue; tri(row, sty, i, t); if (pal_was(t)) return 1; }
   return 0;
}
/* 고르기 화면 미리보기 — 캐릭터 고르기 그림은 늘 수라(실측, 14명 모두), 검질 고르기는 커서(0x574C) 검질. 지금 팔레트 0 이 그 캐릭터·검질의
   어느 컬러든(또는 만드는 색) 보일 색으로 고쳐 쓴다(OPTION 으로 바꿀 땐 게임이 팔레트를 다시 안 쓴다). 다른 색이면 그대로(맞을 때 번쩍임 같은 게임 색).
   any: 검질을 모른다 — 무작위 칸으로 결정하면 뽑기가 멈춘 캐릭터를 게임이 0x574C 와 상관없이 아무 검질로나 그린다(실측) → 다른 검질도 보고
   알아본 검질을 pv_sty 에 둔다. 돌려줌 = 그림의 검질 */
static int preview_fix(int row, int sty, int idx, int any)
{
   uint16_t want[3];
   if ((K.edit && pal_is(0, K.edit_c)) || (K.pv_has && pal_is(0, K.pv_last))) { if (any) sty = K.pv_sty; }
   else if (pv_known(row, sty)) ;
   else if (any && pv_known(row, !sty)) sty = !sty;
   else return sty;
   if (K.edit) memcpy(want, K.edit_c, 6); else tri(row, sty, idx, want);
   if (!pal_is(0, want)) pal_wr(0, want);
   memcpy(K.pv_last, want, 6); K.pv_has = 1; K.pv_sty = sty;
   return sty;
}

/* ── A·B·A+B 가르기 (look.h LOOK_AB_WIN) ── */
static void gate_reset(void) { K.g_st = G_IDLE; K.conf_pend = 0; }
static void gate_chord(int input)
{
   K.fx = (K.fx + 1) % SLASH_N; K.fx_gen++;
   K.toast_f = (int)K.f + LOOK_TOAST_F;
   K.hold = input & (IN_A | IN_B | IN_ABK);
   K.g_st = G_SWALLOW;
}
static void gate_confirm(int first, int scr, int ctx, int input)
{
   if (ctx == LOOK_CTX_HOLD) K.conf_pend = first == IN_B ? 2 : 1;   /* 허브가 다음 프레임에 가져간다(대신 누르지 않음) — 177: 1 A · 2 B(연습 검질 화면 B = 캐릭터 다시) */
   else { K.inj_bit = (scr == 4 && first == IN_B) ? IN_B : IN_A; K.inj = 1; K.inj_f = (int)K.f; }
   K.conf_n++;
   K.hold = input & (IN_A | IN_B | IN_ABK);
   K.g_st = G_SWALLOW;
}
/* 쥔 고르기 화면에서 한 프레임 — a_dn·b_dn·ab_dn = 이번 프레임에 누르기 시작함.
   a·b = A+B 의 «다른 쪽»으로 칠 쥔 단추 — 창(LOOK_AB_WIN) 안에 누르기 시작했고 앞 결정·A+B 에 쓴 단추(K.hold)가 아닐 때만.
   (165 리뷰: 결정에 쓴 A 를 쥔 채 화면이 바뀌면 gate_reset 이 G_IDLE 로 돌려, 다음 화면에서 누른 B 가 그 A 와 A+B 가 되어 «뒤로»·결정이 먹혔다) */
static void gate_step(int input, int a_dn, int b_dn, int ab_dn, int scr, int ctx)
{
   int a = (input & IN_A) && !(K.hold & IN_A) && K.a_age < LOOK_AB_WIN;
   int b = (input & IN_B) && !(K.hold & IN_B) && K.b_age < LOOK_AB_WIN;
   switch (K.g_st) {
   case G_IDLE:
      if (ab_dn || (a_dn && b) || (b_dn && a)) gate_chord(input);
      else if (a_dn || b_dn) { K.g_st = G_WAIT; K.g_f0 = K.f; K.g_first = a_dn ? IN_A : IN_B; K.g_scr0 = scr; }
      break;
   case G_WAIT:
      if (scr != K.g_scr0) { K.g_st = G_SWALLOW; K.hold = input & (IN_A | IN_B | IN_ABK); break; }   /* 화면이 바뀜 — 아무 일 없음 */
      if (ab_dn || (K.g_first == IN_A ? b_dn : a_dn) || (a && b)) { gate_chord(input); break; }   /* 먼저 것을 이미 뗐어도 창 안이면 A+B */
      if ((int)(K.f - K.g_f0) >= LOOK_AB_WIN - 1) gate_confirm(K.g_first, scr, ctx, input);
      break;
   default:
      if (!(input & (IN_A | IN_B | IN_ABK))) K.g_st = G_IDLE;
      break;
   }
}

/* 경기 쪽(고르기 화면 밖) 몸 표 — write: 팔레트 0 이 기본 색이면 지금 고른 색으로도(불러온 상태·켠 순간 — 게임이 다시 쓰기 전에도) */
static void match_want(const uint8_t *ram, rm_t want, int write)
{
   uint16_t b[3], t[3];
   if (K.sel <= 0 || !g_pal) return;
   int ch = K.sel_ch >= 0 ? K.sel_ch : ram[R_P1CH] < 14 ? ram[R_P1CH] : -1;
   if (ch < 0) return;
   int s0 = p1_sty(ram, ch);
   for (int k = 0; k < 2; k++) {                       /* 그 판 검질이 자리 0 — 옛 코어가 두 검질 기본 색이 같은 칸에서 다른 검질 색을 넣지 않게 */
      int sty = k ^ s0;
      tri(ch, sty, 0, b); tri(ch, sty, K.sel, t);
      if (!k || memcmp(b, t, 6)) {
         rm_want(want, 0, k, b, t);
         if (write && pal_is(0, b)) pal_wr(0, t);
      }
   }
}
/* 177: P1 이 지금 보이는 세 색(같은 캐릭터 거울 겹침 판정 — 경기 컬러를 칠하기 전 원래 표 값으로). 돌려줌 = P1 캐릭터(−1 모름).
   캐릭터·검질은 0x57D5/0x57D6(검질 확정 때 씀 — 무작위 칸이면 뽑힌 캐릭터·실제 검질, 유가 치트면 14) — P1 블록·0x574C 는 이야기 화면 동안
   앞 판 값이고 무작위 칸이면 커서와 실제 검질이 다르다(K6). 0x57D5 를 아직 안 썼으면(255) 고르기에서 본 캐릭터·검질 커서.
   색: 경기 컬러가 그 캐릭터 것이면 그 색, 아니면 그 검질 기본(165 부터 플레이어 2P색은 없음) · 유가는 유가 줄 기본 · *sty_out = 그 검질 */
static int p1_visible(const uint8_t *ram, uint16_t o[3], int *sty_out)
{
   int ch, sty;
   if (ram[R_P1CH] <= 14) { ch = ram[R_P1CH]; sty = ram[R_P1STY] & 1; }
   else if (K.sel_ch >= 0 && K.sel_ch < 14) { ch = K.sel_ch; sty = ram[R_STYC] & 1; }
   else return -1;
   int idx = (ch < 14 && K.on && K.sel > 0 && (K.sel_ch < 0 || K.sel_ch == ch)) ? K.sel : 0;
   if (ch == 14) memcpy(o, LOOK_TAB[LOOK_ROW_YUGA][sty][0], 6); else tri(ch, sty, idx, o);
   *sty_out = sty;
   return ch;
}
/* 177: 상대 컬러 표(팔레트 5) — write: 팔레트 5 가 게임 색(기본·2P색)이거나 지난 프레임 우리 색이면 지금 바로도(불러온 직후·켠 순간·대신 색이 바뀐 순간) */
static void opp_want(const uint8_t *ram, rm_t want, int write, int title)
{
   OP.state = 0; OP.fb = -1;
   if (!OP.on || !g_pal || title || !(OP.live || OP.grace > 0)) return;
   int md = ram[R_MODE], scr = ram[R_SCR], b2 = ram[R_P2BLK];
   if (!(ram[R_P1BLK] | b2)) return;                                  /* 리셋 직후 첫 대전 전(로고~카드) — P2 블록 0 이 카즈키(ch 0)로 읽히지 않게(K5) */
   if (md == 240 && (scr == 0 || scr == 2 || scr == 4 || scr == 6)) return;   /* 타이틀·고르기·검질·카드 — VS 인트로·대사(SCR 8)는 남는다 */
   if ((b2 >> 4) != OP.ch) return;                                   /* 다른 캐릭터 — 앞 판 값·다른 모드는 안 건드림 */
   int sty = (b2 >> 3) & 1, row = opp_row(OP.ch), msty = -1;
   const uint16_t *b0 = LOOK_TAB[row][sty][0], *b1 = LOOK_TAB[row][sty][1];
   uint16_t mine[3], show[3], prev[3];
   memcpy(show, OP.c, 6);
   if (p1_visible(ram, mine, &msty) == OP.ch && tdist(mine, OP.c) <= OPP_CLASH
       && !(msty == sty && ((!memcmp(mine, b0, 6) && !memcmp(OP.c, b1, 6)) || (!memcmp(mine, b1, 6) && !memcmp(OP.c, b0, 6))))) {
      /* 겹침(같은 캐릭터면 검질과 상관없이 — 두 검질이 같은 그림) — 단 같은 검질의 게임 {기본, 2P색} 짝은 겹침 아님(K3 ①: 기본↔2P 거리 7~11 인 캐릭터) */
      int best = 0, bd = -1;
      for (int i = 0; i < 8; i++) {
         const uint16_t *t = LOOK_TAB[row][sty][OPP_FB[i]];
         int d = tdist(t, mine);
         if (OPP_FB[i] == 1 && !memcmp(mine, b0, 6)) { best = i; break; }   /* 내 색이 그 검질 기본 — 2P색은 FAR 와 상관없이(게임과 같은 거울 구분, K3 ②) */
         if (d >= OPP_FAR && tdist(t, OP.c) >= OPP_FAR) { best = i; break; }
         if (d > bd) { bd = d; best = i; }
      }
      memcpy(show, LOOK_TAB[row][sty][OPP_FB[best]], 6); OP.fb = OPP_FB[best];
   }
   memcpy(prev, OP.show, 6);
   OP.state = OP.fb >= 0 ? 2 : 1; memcpy(OP.show, show, 6);
   rm_want(want, 5, 0, b0, show);
   if (memcmp(b1, b0, 6)) rm_want(want, 5, 1, b1, show);
   if (write && !pal_is(5, show) && (pal_is(5, b0) || pal_is(5, b1) || pal_is(5, prev))) pal_wr(5, show);
}
/* 177: 이 프레임의 상대 베기 색 — 상대 컬러가 칠해진 판만 · 내 경기 베기 색과 같으면 0(누가 벤 칼인지 안 보이므로 그 판은 원래 색, M2) */
static int opp_fx_now(void) { return OP.state && OP.fx > 0 && OP.fx != (K.on ? K.fx_sel : 0) ? OP.fx : 0; }
/* 베기 색 표 — 이번 프레임 것을 정하고(바뀌었으면 옛 표의 우리 색을 게임 색으로 되돌린 뒤) 바꿔 쓰기 표에 얹는다.
   P1(내 베기 색)은 컬러 고르기를 켰을 때만 · P2(상대 베기 색, 177)는 상대 컬러가 칠해진 판(opp_want 뒤)이면 컬러 고르기와 상관없이 */
static void slash_want_step(const uint8_t *ram, rm_t want, int title)
{
   slash_want nw;
   slash_clear(&nw);
   if (!title && slash_window(ram)) {
      if (K.on && K.fx_sel > 0) slash_build(ram, 0, K.fx_sel, &nw);
      int f2 = opp_fx_now();
      if (f2 > 0) slash_build(ram, 1, f2, &nw);
   }
   if (memcmp(&nw, &SW, sizeof SW)) { if (g_pal) slash_restore(g_pal, &SW); SW = nw; }
   for (int p = 1; p < RM_NP; p++)
      for (int k = 0; k < SLASH_NSLOT; k++) {
         const slash_pair *q = &SW.s[p][k];
         if (q->from[0] < 0) continue;
         for (int j = 0; j < 3; j++) { want[p][k][j][0] = q->from[j]; want[p][k][j][1] = q->to[j]; }
      }
}

void look_tick(uint8_t *ram, size_t n, int input, int ctx)
{
   rm_t want;
   int block = 0, orr = 0;
   uint16_t t[3], b[3];
   if (!ram || n < 0x4000) return;
   if (ctx == LOOK_CTX_AUTO) { input = 0; ctx = LOOK_CTX_FREE; }   /* 153: 자동 진행 — 쥐되 사용자 입력은 없는 것으로 */
   pthread_mutex_lock(&K_mu);
   K.ram = ram;
   rm_clear(want);
   if (!K.on) {                                                       /* 꺼짐 — 내 컬러 표는 비우고, 쥐고 있던 세로 칸이 있으면 되돌린다 */
      if (K.owned && vpanel_on()) vpanel_restore();
      K.owned = 0; gate_reset(); K.hold = 0; K.inj = 0;
      int md0 = ram[R_MODE], scr0 = ram[R_SCR];                      /* 177: 상대 컬러·상대 베기 색은 컬러 고르기와 상관없이(K2) */
      int title0 = md0 == 240 && scr0 == 0 && vpanel_menu_kind();
      if (!(md0 == 240 && (scr0 == 2 || scr0 == 4) && vpanel_layout())) opp_want(ram, want, 1, title0); else { OP.state = 0; OP.fb = -1; }
      if (OP.grace > 0) OP.grace--;
      slash_want_step(ram, want, title0);
      rm_push(want);
      if (g_pal) slash_fix(g_pal, &SW);
      look_in_block = 0; look_in_or = 0;
      pthread_mutex_unlock(&K_mu); return;
   }
   K.f++;
   int a_dn = (input & IN_A) && !(K.prev_in & IN_A), b_dn = (input & IN_B) && !(K.prev_in & IN_B);
   int ab_dn = (input & IN_ABK) && !(K.prev_in & IN_ABK);
   int o_dn = (input & IN_OPT) && !(K.prev_in & IN_OPT), o_up = !(input & IN_OPT) && (K.prev_in & IN_OPT);
   K.prev_in = input;
   if (a_dn) K.a_age = 0; else if (K.a_age < 255) K.a_age++;          /* 누른 뒤 지난 프레임(쥔 첫 프레임에 a_dn 을 지우기 전 — 그 누름도 창 안이면 A+B 의 한쪽) */
   if (b_dn) K.b_age = 0; else if (K.b_age < 255) K.b_age++;
   K.hold &= input;                                                   /* 뗀 단추는 놓는다 */
   int md = ram[R_MODE], scr = ram[R_SCR];
   int title = md == 240 && scr == 0 && vpanel_menu_kind();
   if (title) {                                                       /* 타이틀 메뉴 — 경기 컬러·베기 색을 잊는다(SCR 0 만으론 안 됨: 이어하기 뒤 8프레임 SCR 0) */
      if (K.sel >= 0) K.sel_gen++;
      K.sel = -1; K.sel_ch = -1; K.fx_sel = 0; K.rand_pin = 0;
   }
   int sel_scr = md == 240 && (scr == 2 || scr == 4) && vpanel_layout();
   int owned = sel_scr && ctx != LOOK_CTX_BUSY;
   if (o_dn) { K.opt_f = (int)K.f; K.opt_own = owned && !K.edit; }
   if (owned) {
      int h = ram[R_HOVER] >> 2;
      if (scr == 2) K.rand_pick = h == 15;
      int slot = (scr == 4 && K.rand_pick) || h == 15 || (K.rand_pin && h == K.rand_pin - 1) ? 15 : h < 14 ? h : -1;   /* 무작위로 골랐으면 돌아가는 캐릭터와 상관없이 무작위 칸의 기억 */
      int row = h < 14 ? h : h == 15 ? LOOK_ROW_KUROKO : -1;
      if (ctx != LOOK_CTX_HOLD) K.conf_pend = 0;
      if (slot >= 0 && row >= 0) {
         int sty_now = scr == 4 ? (ram[R_STYC] & 1) : -1;
         if (scr == 2 && K.last_scr == 4) K.rand_pin = 0;            /* 검질 고르기에서 뒤로 — 무작위 칸에서 뽑혔던 캐릭터 고정 풀림(다시 고르면 직접 고른 것) */
         K.last_scr = scr;
         if (K.edit) { block = IN_ALL; gate_reset(); }                /* 컬러 만들기 창 — 게임에 아무것도 안 준다(고르기 화면엔 시간 제한이 없다, 실측) */
         else {
            block |= IN_OPT | IN_A | IN_B | IN_ABFOLD;                /* 게임은 고르기 화면에서 OPTION 을 안 쓴다(실측) · A·B·A+B 는 가르기가 받는다 */
            if (o_up && K.opt_own && (int)K.f - K.opt_f < LONG_F) {  /* 짧게: 다음 컬러 */
               int m = K.mem[slot] >= 0 && K.mem[slot] < LOOK_NIDX ? K.mem[slot] : 0;
               K.mem[slot] = (signed char)next_idx(row, m, sty_now);
               K.mem_gen++;
               K.toast_f = (int)K.f;                                  /* 베기 이름을 보이던 중이면 바로 컬러 이름으로 */
            }
            if ((input & IN_OPT) && K.opt_own && (int)K.f - K.opt_f == LONG_F && slot < 14) K.edit_req++;   /* 길게: 컬러 만들기(Java 가 창을 연다 ·
                                                                         쿠로코 칸·무작위로 고른 검질 고르기 제외 — 내 색은 캐릭터마다라 기억 칸이 그 캐릭터일 때만) */
            if (!K.owned) a_dn = b_dn = ab_dn = 0;                    /* 쥔 첫 프레임의 누름은 앞 주인(허브의 상대 선택 A 등 — 같은 프레임에 BUSY → FREE)의 것 —
                                                                         새 누름으로 안 본다(쥔 단추는 가장자리가 없어 그 뒤에도 안 잡힘 · 게임엔 막음) */
            gate_step(input, a_dn, b_dn, ab_dn, scr, ctx);
            if (K.g_st == G_WAIT) block |= IN_DPAD;                   /* 결정 전에 커서가 옮겨 가지 않게 */
         }
         int m = K.mem[slot] >= 0 && K.mem[slot] < LOOK_NIDX && idx_ok(row, K.mem[slot], -1) ? K.mem[slot] : 0;
         K.cur = m;
         int sch = h < 14 ? h : -1;                                   /* 무작위 뽑기가 멈춘 커서도 실제 캐릭터라 그대로 맞는다 */
         if (K.sel != K.cur || K.sel_ch != sch) K.sel_gen++;
         K.sel = K.cur; K.sel_ch = sch;
         K.fx_sel = K.fx;                                             /* 경기 베기 색 — 몸 경기 컬러와 같은 길 */
         K.sty_seen = sty_now;
         K.prow = row; K.psty = scr == 4 ? (ram[R_STYC] & 1) : 0; K.pslot = slot;
         if (g_pal) K.psty = preview_fix(row, K.psty, K.cur, scr == 4 && K.rand_pick);
         int toast = !K.edit && (int)(K.toast_f - (int)K.f) > 0;
         K.vp_lab = toast ? SLASH_VLAB + K.fx * 18 * 16 : LOOK_VLAB + (K.edit ? LOOK_NPRE : K.cur) * 18 * 16;   /* 만드는 중이면 «내 색 1» */
         vpanel_put(K.vp_lab);
         slash_icon(K.fx, row, scr == 4 ? K.psty : 0, K.ic_c);       /* 원래 색 아이콘 = 캐릭터 고르기는 수라(그림도 늘 수라) · 검질 고르기는 커서 검질 */
         vpanel_icon_put(SLASH_ICON, K.ic_c);
         if (K.cur > 0 || K.edit)
            for (int k = 0; k < 2; k++) {                             /* 두 자리 — 검질 고르기에서 ←→ 로 검질을 바꿔도 바로 그 검질의 그 컬러 · 보이는 검질이 자리 0 */
               int sty = k ^ K.psty;
               if (k && scr == 4 && K.rand_pick && !K.edit) {             /* 무작위 칸 뽑기에서 두 검질 컬러가 같으면 자리 1 을 비운다 — 다른 검질로 다시 그린 기본 색이
                                                                             그대로 와야 preview_fix 가 그린 검질을 알아본다(저장 때 맞는 검질 기본 색으로 되돌리게) */
                  uint16_t c0[3], c1[3];
                  tri(row, 0, K.cur, c0); tri(row, 1, K.cur, c1);
                  if (!memcmp(c0, c1, 6)) continue;
               }
               tri(row, sty, 0, b);
               if (K.edit) memcpy(t, K.edit_c, 6); else tri(row, sty, K.cur, t);
               if (!k || memcmp(b, t, 6)) rm_want(want, 0, k, b, t);
            }
      } else gate_reset();
   } else {
      gate_reset();
      if (K.owned && vpanel_on()) vpanel_restore();                   /* 방금까지 쥐었다 — 세로 칸·아이콘을 원래대로 */
      if (!sel_scr) match_want(ram, want, 1);                         /* 경기 쪽(고르기 화면 밖) */
   }
   K.owned = owned;
   if (!sel_scr) opp_want(ram, want, 1, title); else { OP.state = 0; OP.fb = -1; }   /* 177 상대 컬러 — 고르기 화면 밖 */
   if (OP.grace > 0) OP.grace--;                                      /* 불러온 뒤 남은 프레임(무장 전 프레임 대비) */
   slash_want_step(ram, want, title);
   if (K.inj) {                                                       /* 결정 — 다음 프레임부터 LOOK_INJ_N 프레임 대신 누름 · 십자는 LOOK_INJ_DPAD 프레임째까지 막는다 */
      int d = (int)K.f + 1 - K.inj_f;
      if (d >= 1 && d <= LOOK_INJ_N) orr |= K.inj_bit;
      if (d <= LOOK_INJ_DPAD) block |= IN_DPAD;
      if (d >= LOOK_INJ_DPAD) K.inj = 0;
   }
   block |= K.hold;                                                   /* 결정·A+B 를 낸 단추는 뗄 때까지(쥠이 풀려도 — 게임이 새로 누른 것으로 보지 않게) */
   if (block & (IN_A | IN_B)) block |= IN_ABFOLD;
   rm_push(want);
   if (g_pal) slash_fix(g_pal, &SW);                                  /* 프레임 뒤 보정(동봉 코어가 반쯤 바꾼 세 색 · 표보다 먼저 온 쓰기) */
   look_in_block = block; look_in_or = orr;
   pthread_mutex_unlock(&K_mu);
}

void look_enable(int on)
{
   pthread_mutex_lock(&K_mu);
   K.on = on ? 1 : 0; rm_init = 0;
   if (!on) { K.sel = -1; K.sel_ch = -1; K.cur = 0; K.fx_sel = 0; K.edit = 0; K.inj = 0; K.hold = 0; gate_reset(); look_in_block = 0; look_in_or = 0; }   /* cur 0 — 꺼진 판의 리그 기록(허브 v[10])에 옛 컬러가 안 남게 */
   pthread_mutex_unlock(&K_mu);
}
void look_set_mem(const signed char *mem, int n)
{
   pthread_mutex_lock(&K_mu);
   for (int i = 0; i < LOOK_NSLOT; i++) K.mem[i] = (mem && i < n && mem[i] >= 0 && mem[i] < LOOK_NIDX && mem[i] != 1) ? mem[i] : 0;   /* 옛 1(2P색) → 기본 */
   pthread_mutex_unlock(&K_mu);
}
int look_get_mem(signed char *mem, int n)
{
   pthread_mutex_lock(&K_mu);
   for (int i = 0; i < LOOK_NSLOT && i < n; i++) mem[i] = K.mem[i];
   int g = K.mem_gen;
   pthread_mutex_unlock(&K_mu);
   return g;
}
void look_set_custom(int ch, int k, int c1, int c2, int c3)
{
   if (ch < 0 || ch >= 14 || k < 0 || k > 1) return;
   pthread_mutex_lock(&K_mu);
   K.cus_ok[ch][k] = c1 >= 0;
   if (c1 >= 0) { K.cus[ch][k][0] = (uint16_t)(c1 & 0xFFF); K.cus[ch][k][1] = (uint16_t)(c2 & 0xFFF); K.cus[ch][k][2] = (uint16_t)(c3 & 0xFFF); }
   pthread_mutex_unlock(&K_mu);
}
void look_set_fx(int fx)
{
   pthread_mutex_lock(&K_mu);
   K.fx = fx > 0 && fx < SLASH_N ? fx : 0;
   pthread_mutex_unlock(&K_mu);
}
void look_set_pick(int rnd_ch)
{
   pthread_mutex_lock(&K_mu);
   K.rand_pin = rnd_ch >= 0 && rnd_ch < 14 ? rnd_ch + 1 : 0; K.sel_gen++;
   pthread_mutex_unlock(&K_mu);
}
void look_set_match(int idx)
{
   pthread_mutex_lock(&K_mu);
   K.sel = (idx >= 2 && idx < LOOK_NIDX) ? idx : -1; K.sel_ch = -1; K.sel_gen++;   /* 1(옛 2P색)도 «정하지 않음» */
   pthread_mutex_unlock(&K_mu);
}
void look_edit(int on, int c1, int c2, int c3)
{
   pthread_mutex_lock(&K_mu);
   K.edit = on ? 1 : 0;
   K.edit_c[0] = (uint16_t)(c1 & 0xFFF); K.edit_c[1] = (uint16_t)(c2 & 0xFFF); K.edit_c[2] = (uint16_t)(c3 & 0xFFF);
   pthread_mutex_unlock(&K_mu);
}
int look_take_confirm(void)
{
   pthread_mutex_lock(&K_mu);
   int c = K.conf_pend; K.conf_pend = 0;
   pthread_mutex_unlock(&K_mu);
   return c;
}
void look_set_opp(int ch, int sty, int c1, int c2, int c3, int fx)
{
   pthread_mutex_lock(&K_mu);
   OP.on = c1 >= 0 && ch >= 0 && ch <= 14; OP.ch = ch; OP.sty = sty & 1; OP.state = 0; OP.fb = -1; OP.grace = 0;
   OP.c[0] = (uint16_t)(c1 & 0xFFF); OP.c[1] = (uint16_t)(c2 & 0xFFF); OP.c[2] = (uint16_t)(c3 & 0xFFF);
   memcpy(OP.show, OP.c, 6);
   OP.fx = OP.on && fx > 0 && fx < SLASH_N ? fx : 0;
   pthread_mutex_unlock(&K_mu);
}
void look_set_opp_live(int live) { pthread_mutex_lock(&K_mu); OP.live = live ? 1 : 0; pthread_mutex_unlock(&K_mu); }
int look_cur(void) { pthread_mutex_lock(&K_mu); int c = K.cur; pthread_mutex_unlock(&K_mu); return c; }
void look_status(int v[LOOK_NSTAT])
{
   pthread_mutex_lock(&K_mu);
   v[0] = K.on; v[1] = K.owned; v[2] = K.mem_gen; v[3] = K.cur; v[4] = K.sel; v[5] = K.sel_ch; v[6] = 0; v[7] = K.edit_req;
   v[8] = K.sty_seen; v[9] = K.sel_gen; v[10] = K.edit; v[11] = (int)K.f; v[12] = K.owned && K.pslot < 14 ? K.prow : -1; v[13] = K.psty;
   v[14] = K.fx; v[15] = K.fx_gen; v[16] = K.conf_n; v[17] = K.fx_sel;
   v[18] = OP.state; v[19] = OP.state == 2 ? OP.fb : -1;
   pthread_mutex_unlock(&K_mu);
}
void look_triples(int ch, int sty, int out[LOOK_NIDX * 3])
{
   uint16_t t[3];
   int row = ch == 14 ? LOOK_ROW_YUGA : ch >= 0 && ch < 14 ? ch : 0;   /* 177: 14 = 유가 줄(쿠로코 줄은 고르기 화면 미리보기 전용 — Java LookEditor 는 0~13 만) */
   pthread_mutex_lock(&K_mu);
   for (int i = 0; i < LOOK_NIDX; i++) { tri(row, sty & 1, i, t); for (int j = 0; j < 3; j++) out[i * 3 + j] = t[j]; }
   pthread_mutex_unlock(&K_mu);
}
/* 지금 몸 팔레트 0 에 쓸 «기본 → 보일 색» — 고르기 화면을 쥔 동안은 미리보기, 아니면 경기 컬러. 돌려줌 = 줄(−1 없음), *s0 = 먼저 볼 검질, *idx (−1 = 만드는 색) */
static int body_now(const uint8_t *ram, int *s0, int *idx)
{
   int row = -1;
   *s0 = 0; *idx = 0;
   if (K.owned) { row = K.prow; *s0 = K.psty; *idx = K.edit ? -1 : K.cur; }
   else if (K.sel > 0) {
      row = K.sel_ch >= 0 ? K.sel_ch : ram[R_P1CH] < 14 ? ram[R_P1CH] : -1;
      if (row >= 0) *s0 = p1_sty(ram, row);
      *idx = K.sel;
   }
   return *idx != 0 ? row : -1;
}
/* 상태 저장 직전 — 세로 칸·아이콘을 원래대로, 팔레트 0 도 우리가 바꾼 색이면 원래 색으로, 효과 팔레트도 우리 색이면 게임 색으로
   (저장된 상태는 원래 게임 그대로 · 저장 직후 look_after_save 가 다시 바꾼다). .look 없이 그 상태를 불러도(옛 앱·다른 판) 원래 색이고,
   게임이 팔레트를 다시 쓸 때까지(대전 중 1초에 한 번꼴) 엉뚱한 색이 남지 않는다.
   캐릭터·검질은 알고 되돌린다 — 황금·은빛·그림자처럼 캐릭터가 달라도 같은 세 색인 프리셋(밝기 순위로)이 있어 색만으론 못 가린다.
   검질: 전투 중이고 P1 블록이 그 캐릭터면 블록의 검질, 아니면 검질 고르기 커서(0x574C — 확정 뒤에도 남음). 그 검질의 색이 아니면 다른 검질도 본다 */
void look_before_save(void)
{
   uint16_t b[3], t[3];
   pthread_mutex_lock(&K_mu);
   if (K.owned) vpanel_restore();
   if (g_pal && K.on && K.ram) {
      int s0, idx, row = body_now(K.ram, &s0, &idx);
      if (row >= 0)
         for (int k = 0; k < 2; k++) {
            int s = k ? !s0 : s0;
            if (idx < 0) memcpy(t, K.edit_c, 6); else tri(row, s, idx, t);
            if (pal_is(0, t)) { tri(row, s, 0, b); pal_wr(0, b); break; }
         }
   }
   if (g_pal && K.ram && OP.state && pal_is(5, OP.show)) {           /* 177: 상대 색 → 게임 색(P2 블록 거울 비트면 2P색) — 저장된 상태엔 게임 색만 */
      int b2 = K.ram[R_P2BLK];
      pal_wr(5, LOOK_TAB[opp_row(OP.ch)][(b2 >> 3) & 1][(b2 & 0x04) ? 1 : 0]);
   }
   if (g_pal) slash_restore(g_pal, &SW);
   pthread_mutex_unlock(&K_mu);
}
/* 상태 저장(c_serialize) 바로 뒤 — 되돌려 둔 것을 다시 우리 것으로(다음 화면이 한 프레임 원래 색·원래 칸으로 나오지 않게) */
void look_after_save(void)
{
   uint16_t b[3], t[3];
   pthread_mutex_lock(&K_mu);
   if (g_pal) slash_reapply(g_pal, &SW);
   if (g_pal && K.on && K.ram) {
      int s0, idx, row = body_now(K.ram, &s0, &idx);
      if (row >= 0)
         for (int k = 0; k < 2; k++) {
            int s = k ? !s0 : s0;
            tri(row, s, 0, b);
            if (idx < 0) memcpy(t, K.edit_c, 6); else tri(row, s, idx, t);
            if (pal_is(0, b)) { pal_wr(0, t); break; }
         }
   }
   if (K.on && K.owned && K.vp_lab) { vpanel_put(K.vp_lab); vpanel_icon_put(SLASH_ICON, K.ic_c); }
   if (g_pal && K.ram && OP.state) {                                  /* 177: 되돌린 상대 색을 다시 */
      int b2 = K.ram[R_P2BLK], row = opp_row(OP.ch), sty = (b2 >> 3) & 1;
      if (pal_is(5, LOOK_TAB[row][sty][0]) || pal_is(5, LOOK_TAB[row][sty][1])) pal_wr(5, OP.show);
   }
   pthread_mutex_unlock(&K_mu);
}

/* 상태 저장 옆(.look, native.c) — 경기 컬러나 경기 베기 색이 있을 때만 16바이트: 'L' 'K' 판 1 · [3] 경기 컬러(2~17, 없으면 0) ·
   [4] 경기 캐릭터(부호, −1 = 0x57D5) · [5] 경기 베기 색(1~6, 없으면 0) · 나머지 0. 옛 앱은 [5] 를 모르고 [3] 0 은 «없음»으로 버린다(원래 색) */
size_t look_blob(void *buf, size_t cap)
{
   pthread_mutex_lock(&K_mu);
   size_t n = 0;
   if (K.on && (K.sel > 0 || K.fx_sel > 0) && cap >= 16) {
      uint8_t *p = (uint8_t *)buf; memset(p, 0, 16);
      p[0] = 'L'; p[1] = 'K'; p[2] = 1; p[3] = (uint8_t)(K.sel > 0 ? K.sel : 0); p[4] = (uint8_t)(signed char)K.sel_ch; p[5] = (uint8_t)K.fx_sel;
      n = 16;
   }
   pthread_mutex_unlock(&K_mu);
   return n;
}
/* 불러온 바로 뒤(코어 잠금 안) — .look 이 없거나 판이 안 맞으면 경기 컬러·베기 색을 버린다(불러온 판 램에 맞지 않는 컬러가 남지 않게).
   [3] 은 2~17 만(1 = 옛 2P색 → 없음), [5] 는 1~6 만 */
void look_resume(const void *buf, size_t n)
{
   pthread_mutex_lock(&K_mu);
   const uint8_t *p = (const uint8_t *)buf;
   K.sel = -1; K.sel_ch = -1; K.fx_sel = 0;
   if (p && n >= 16 && p[0] == 'L' && p[1] == 'K' && p[2] == 1) {
      if (p[3] >= 2 && p[3] < LOOK_NIDX && (signed char)p[4] >= -1 && (signed char)p[4] < 14) { K.sel = p[3]; K.sel_ch = (signed char)p[4]; }
      if (p[5] >= 1 && p[5] < SLASH_N) K.fx_sel = p[5];
   }
   K.sel_gen++;
   pthread_mutex_unlock(&K_mu);
}
/* look_resume 바로 뒤(코어 잠금 안) — 불러온 램으로 표를 만들어 코어에 새로 넣고(look_forget 이 rm_init 0 — 통째로 비우고 다시) 지금 팔레트도 고친다.
   컬러 고르기를 끈 판에서 불러오면(또는 .look 이 없으면) 표가 비어 원래 색 그대로 */
void look_after_load(const uint8_t *ram, size_t n)
{
   rm_t want;
   if (!ram || n < 0x4000) return;
   pthread_mutex_lock(&K_mu);
   K.ram = ram;
   rm_clear(want);
   int md = ram[R_MODE], scr = ram[R_SCR];
   int title = md == 240 && scr == 0 && vpanel_menu_kind();
   int sel_scr = md == 240 && (scr == 2 || scr == 4) && vpanel_layout();
   if (K.on && !sel_scr && !title) match_want(ram, want, 1);
   if (OP.on) OP.grace = OPP_GRACE;                                   /* 177: 불러온 뒤 무장 전 프레임도 상대 색(리그가 곧 무장 — UI 스레드라 live 는 지난 프레임 값) */
   if (!sel_scr) opp_want(ram, want, 1, title);                       /* 177: 컬러 고르기를 꺼도 첫 프레임부터(K2) */
   slash_clear(&SW);                                                  /* 불러온 팔레트 램엔 옛 표의 우리 색이 없다(저장 전에 되돌림) — 되돌리기 없이 새로 */
   slash_want_step(ram, want, title);
   rm_push(want);
   if (g_pal) slash_fix(g_pal, &SW);
   pthread_mutex_unlock(&K_mu);
}
void look_forget(void)
{
   pthread_mutex_lock(&K_mu);
   K.sel = -1; K.sel_ch = -1; K.cur = 0; K.fx_sel = 0; K.edit = 0; K.owned = 0; K.inj = 0; K.hold = 0; K.prow = -1; K.opt_own = 0; K.pv_has = 0;
   K.rand_pin = 0; K.toast_f = (int)K.f; K.vp_lab = NULL; K.last_scr = -1; gate_reset();
   OP.state = 0; OP.fb = -1;          /* 177: 상대 컬러는 끄지 않는다(−4 돌려보내기·리셋·불러오기마다 불림 — 끄는 것은 Java·코어 올림/내림) · 칠한 기록만 */
   K.ram = NULL;                      /* 165 고침: 코어를 내리면(load/unload_locked) 그 램도 없다 — 저장 직전 되돌리기가 옛 램을 안 읽게(다음 look_tick·look_after_load 가 다시) */
   slash_clear(&SW);
   rm_init = 0; vpanel_forget();
   look_in_block = 0; look_in_or = 0;
   pthread_mutex_unlock(&K_mu);
}

#define JNI(ret, name) JNIEXPORT ret JNICALL Java_com_dudu_pocketcore_Emu_##name
JNI(void, nativeLookEnable)(JNIEnv *env, jclass cls, jboolean on) { (void)env; (void)cls; look_enable(on ? 1 : 0); }
JNI(void, nativeLookSetMem)(JNIEnv *env, jclass cls, jbyteArray a)
{
   (void)cls; signed char m[LOOK_NSLOT]; memset(m, 0, sizeof m);
   if (a && (*env)->GetArrayLength(env, a) >= LOOK_NSLOT) (*env)->GetByteArrayRegion(env, a, 0, LOOK_NSLOT, (jbyte *)m);
   look_set_mem(m, LOOK_NSLOT);
}
JNI(jbyteArray, nativeLookGetMem)(JNIEnv *env, jclass cls)
{
   (void)cls; signed char m[LOOK_NSLOT]; look_get_mem(m, LOOK_NSLOT);
   jbyteArray a = (*env)->NewByteArray(env, LOOK_NSLOT);
   if (a) (*env)->SetByteArrayRegion(env, a, 0, LOOK_NSLOT, (const jbyte *)m);
   return a;
}
JNI(void, nativeLookSetCustom)(JNIEnv *env, jclass cls, jint ch, jint k, jint c1, jint c2, jint c3) { (void)env; (void)cls; look_set_custom(ch, k, c1, c2, c3); }
JNI(void, nativeLookSetFx)(JNIEnv *env, jclass cls, jint fx) { (void)env; (void)cls; look_set_fx(fx); }
JNI(void, nativeLookSetMatch)(JNIEnv *env, jclass cls, jint idx) { (void)env; (void)cls; look_set_match(idx); }
JNI(void, nativeLookEdit)(JNIEnv *env, jclass cls, jboolean on, jint c1, jint c2, jint c3) { (void)env; (void)cls; look_edit(on ? 1 : 0, c1, c2, c3); }
JNI(void, nativeLookOpp)(JNIEnv *env, jclass cls, jint ch, jint sty, jint c1, jint c2, jint c3, jint fx) { (void)env; (void)cls; look_set_opp(ch, sty, c1, c2, c3, fx); }   /* 177 상대 컬러(c1 < 0 = 끔) */
JNI(jintArray, nativeLookStatus)(JNIEnv *env, jclass cls)
{
   (void)cls; int v[LOOK_NSTAT]; jint jv[LOOK_NSTAT]; look_status(v);
   for (int i = 0; i < LOOK_NSTAT; i++) jv[i] = v[i];
   jintArray a = (*env)->NewIntArray(env, LOOK_NSTAT);
   if (a) (*env)->SetIntArrayRegion(env, a, 0, LOOK_NSTAT, jv);
   return a;
}
JNI(jintArray, nativeLookTriples)(JNIEnv *env, jclass cls, jint ch, jint sty)   /* 컬러 만들기 창: 그 캐릭터·검질의 18색(없는 내 색 = 기본) */
{
   (void)cls; int v[LOOK_NIDX * 3]; jint jv[LOOK_NIDX * 3];
   look_triples(ch, sty, v);
   for (int i = 0; i < LOOK_NIDX * 3; i++) jv[i] = v[i];
   jintArray a = (*env)->NewIntArray(env, LOOK_NIDX * 3);
   if (a) (*env)->SetIntArrayRegion(env, a, 0, LOOK_NIDX * 3, jv);
   return a;
}
