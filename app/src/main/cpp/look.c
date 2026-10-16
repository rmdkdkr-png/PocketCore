/* 모든 모드 컬러 — look.h 참고. 리그 허브(league.c)에 있던 컬러 코드(PAL1·look_apply·look_preview·코어 바꿔 쓰기 표)를 떼어 와
   허브와 상관없이 매 프레임 돈다. 표는 look_gen.py 가 만든 look_tab.h(프리셋 16 × 캐릭터 14 + 쿠로코 × 검질 2), 세로 글자는
   hub_glyphs.py 가 만든 hub_glyphs.h 의 LOOK_VLAB.

   고르기(캐릭터 SCR 2 · 검질 SCR 4, MODE 240, 오른쪽 세로 칸이 있을 때만 — 이어하기 화면·인트로도 SCR 2/4 라서 칸으로 가린다, 실측)
     OPTION    짧게(뗄 때, 40프레임 미만): 커서 캐릭터의 컬러를 다음 것으로(그 캐릭터 기억 칸을 바꾼다 — 다음에도 그 색). 그 캐릭터에서
               앞 것과 같은 색·안 만든 내 색·쿠로코의 2P색은 건너뛴다. 길게(40프레임째): 만들기 요청(Java 가 «컬러 만들기» 창을 연다).
               «뗄 때» 바꾸는 것은 길게 누르기와 가르려고 — 짧게 누르면 차이를 못 느낀다.
     B         (캐릭터 고르기만) 2P색으로 결정 — 보이는 색을 2P색으로 하고 A 를 대신 4프레임. 기억은 안 바꾼다(그 판만 — 실수로 B 를 눌러도
               고른 색을 안 잃는다). B 는 뗄 때까지 게임에 안 준다(검질 고르기 B = 뒤로).
     A         게임 그대로 — 보이는 색으로 결정.
     미리보기   가운데 그림(스프라이트 팔레트 0)을 보이는 색으로 — 코어 바꿔 쓰기 두 자리(두 검질) + 프레임 뒤 고쳐 쓰기.
     세로 칸    컬러 이름(LOOK_VLAB).
   경기 컬러(sel)는 고르기 화면에서 보이는 색을 따라가다 고르기를 떠나면 그대로 남는다 → 1인 대전 내내(이야기·라운드 사이·이어하기 화면 포함).
   타이틀 메뉴가 뜨면 잊는다(데모·인트로가 팔레트 0 에 캐릭터를 그려서 — 새지 않게).
   경기 표: P1 캐릭터 = 고를 때 커서 캐릭터(무작위 칸이면 0x57D5 — 검질 확정 뒤 ~160프레임에 게임이 씀). 두 검질 모두 «기본 색 → 고른 색»,
     그 판 검질(전투 중 P1 블록 0x5B51 이 그 캐릭터면 블록, 아니면 0x574C — 블록은 이야기 화면 동안 옛 값)을 자리 0 에. 고른 색이 2P색이고
     진짜 미러전(P2 블록 0x5D51 이 같은 캐릭터·미러)이면 팔레트 5(P2 몸)의 «2P색 → 기본 색»도(게임이 P2 를 2P색으로 칠한 것을 바꿈 —
     같은 캐릭터·같은 검질일 때만 그 색이 나온다).
   팔레트 0 은 P1 몸만 쓴다(필살기·잔상·짐승 짝꿍·이펙트는 다른 팔레트, 실측 4명 × 기술 6개). 짝꿍 짐승(3·4)은 원래 색 — 게임 2P색과 같은 규칙.

   잠금: 자기 잠금 K_mu 만. league_hub_status(L_mu) 가 look_cur(K_mu) 를 부르므로 순서는 L_mu → K_mu 하나뿐 — 여기서는 L_mu 를 안 잡는다.
   팔레트·화면 램을 직접 쓰는 일은 look_tick·look_before_save(코어 잠금 안)에서만. Java(UI 스레드)의 nativeLook* 는 K_mu 만 잡고 값만 바꾼다. */
#include <jni.h>
#include <pthread.h>
#include <string.h>
#include "look.h"
#include "look_tab.h"
#define HUB_WANT_LOOK
#include "hub_glyphs.h"
#include "vpanel.h"

#define RAM_BASE 0x4000
#define A(x) ((x) - RAM_BASE)
#define R_MODE  A(0x40A7)   /* 240 메뉴·연출 / 241 전투측 */
#define R_SCR   A(0x41C0)   /* 메뉴일 때 0 타이틀 · 2 캐릭터 · 4 검질 · 6 카드 (이어하기 화면도 SCR 2) */
#define R_HOVER A(0x4E3C)   /* 캐릭터 고르기 커서 = 캐릭터 × 4 (가운데 무작위 칸 = 60) — 무작위로 결정하면 검질 고르기 동안 뽑기처럼 돌다 실제 캐릭터에서 멈춤 */
#define R_P1CH  A(0x57D5)   /* P1 캐릭터 — 검질 확정 뒤 약 160프레임에 씀(그 전 255). 다음 판에서 다시 쓸 때까지 옛 값이 남는다 */
#define R_STYC  A(0x574C)   /* 검질 고르기 커서 0 수라 · 1 나찰(확정 뒤에도 남음) — 실측: 이 바이트만 미리보기 팔레트의 검질과 늘 같다 */
#define R_P1BLK A(0x5B51)   /* (캐릭터<<4)|(검질<<3)|(미러 0x04) — 전투 로드 때 선다 */
#define R_P2BLK A(0x5D51)
#define IN_A   1
#define IN_OPT (1 << 3)
#define IN_DPAD (0xF << 4)
#define IN_B   (1 << 8)
#define IN_ABFOLD ((1 << 1) | (1 << 9) | (1 << 10) | (1 << 11))   /* 코어가 A+B·원버튼으로 접는 패드 비트(Y·X·L·R) — A·B 를 막을 땐 같이 막는다 */
#define IN_ALL 0xFFF
#define LONG_F 40           /* OPTION 길게 = 컬러 만들기(0.7초) */

volatile int look_in_block = 0, look_in_or = 0;
static pthread_mutex_t K_mu = PTHREAD_MUTEX_INITIALIZER;
static uint8_t *g_pal;                /* 코어 팔레트 램(0x8200~, 스프라이트 팔레트 16 × 4색 × 2바이트) */
/* 코어의 팔레트 바꿔 쓰기(retro_ngp_palremap). 코어 판이 둘 — 0966bed 판(150 부터 동봉)은 한 팔레트의 색 1~3 이 모두 한 자리의 from 이 되는
   순간 셋 다 to 로, 옛 판(1249a9e — 149 까지 동봉·바깥 코어)은 칸마다 따로(자리 0 부터, 맞는 자리에서 멈춤). 어느 판이든 맞게: 보일 검질을
   자리 0 에(같은 색이어도 적는다), 미리보기는 옛 표로 섞인 세 색도 알아본다(preview_fix). 게임은 몸 팔레트를 롬에서
   자주 다시 쓴다(대전 중 1초에 한 번꼴, 실측) — 프레임 뒤에 고쳐 쓰기만 하면 그때마다 원래 색이 1~3 프레임 보인다. 표는 상태 저장에 안 들어가서
   매 프레임 정하고 바뀐 칸만 넣는다. 칸: 스프라이트 팔레트 0(P1 몸·미리보기)·5(P2 몸)의 색 1~3 × 자리 2(검질 둘을 한 번에) */
static void (*g_remap)(int idx, int slot, int from, int to);
static const int RM_IDX[6] = { 1, 2, 3, 21, 22, 23 };
static int rm_have[6][2][2];          /* [칸][자리] = {from, to} — from −1 = 비움 */
static int rm_init;                   /* 0 이면 다음에 코어 표를 통째로 비우고 시작(코어를 새로 올림) */

static struct {
   int on;                            /* Java 가 켬 */
   signed char mem[LOOK_NSLOT];       /* 기억 칸: 0~13 캐릭터 · 14 안 씀 · 15 가운데 무작위 칸 — 값 0~17 */
   uint16_t cus[14][2][3];            /* 내 색 1·2 — 캐릭터마다, 두 검질 같이(세 칸 역할이 두 검질에서 같고 검질을 바꿔도 같은 모습이어야 덜 헷갈림) */
   uint8_t cus_ok[14][2];
   int cur;                           /* 고르기 화면에 보이는 컬러 0~17 */
   int sel, sel_ch;                   /* 경기 컬러(−1 없음) · 그때 캐릭터(−1 = 0x57D5 로) */
   int b2p;                           /* B 로 2P색 결정 중(그 판만 — 기억은 안 바꿈) */
   int prev_in, last_scr, owned, hold_b, inj, inj_f;
   int opt_f, opt_own;                /* OPTION 누른 프레임 · 그 누름이 고르기 화면을 쥔 채 시작했나(밖에서 누른 채 들어온 것은 안 센다) */
   int edit; uint16_t edit_c[3];      /* 컬러 만들기 창이 열려 있음 · 그 색 */
   uint16_t pv_last[3]; int pv_has, pv_sty;   /* 미리보기에 마지막으로 쓴(보인) 세 색·그 검질 — 만드는 색을 바꾸거나 창을 닫으면 그 색은 표에 없어서, 이것으로 «우리 색»을 알아본다 */
   int prow, psty, pslot, sty_seen;   /* 미리보기 캐릭터 줄·검질·기억 칸(상태 저장 직전 되돌리기·만들기 창) · 검질 고르기에서 본 검질(−1) */
   int rand_pick;                     /* 가운데 무작위 칸에서 결정했다 — 검질 고르기 동안 게임이 커서를 뽑기처럼 돌려 실제 캐릭터에서 멈춘다(실측) */
   const uint8_t *ram;
   int mem_gen, sel_gen, edit_req;    /* Java 가 보는 바뀜 셈 */
   unsigned f;
} K = { .sel = -1, .sel_ch = -1, .sty_seen = -1, .prow = -1, .last_scr = -1 };

void look_vram(void *chr, void *scr, void *pal) { vpanel_vram(chr, scr); g_pal = (uint8_t *)pal; }
void look_palremap_fn(void *fn) { if ((void *)g_remap != fn) rm_init = 0; *(void **)&g_remap = fn; }

/* 표의 세 색 — 16·17 은 그 캐릭터의 내 색(없으면 기본). 쿠로코 줄(14)엔 내 색이 없다 */
static void tri(int row, int sty, int idx, uint16_t o[3])
{
   const uint16_t *t = LOOK_TAB[row][sty][0];
   if (idx >= 0 && idx < LOOK_NPRE) t = LOOK_TAB[row][sty][idx];
   else if (idx >= LOOK_NPRE && idx < LOOK_NIDX && row < 14 && K.cus_ok[row][idx - LOOK_NPRE]) t = K.cus[row][idx - LOOK_NPRE];
   memcpy(o, t, 6);
}
/* 그 캐릭터에서 쓸 수 있는 번호인가 — sty −1 = 캐릭터 고르기(두 검질 모두 앞 프리셋과 같을 때만 건너뜀) */
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

static void rm_push(int want[6][2][2])
{
   if (!g_remap) return;
   if (!rm_init) {
      g_remap(-1, 0, 0, 0);
      for (int i = 0; i < 6; i++) for (int k = 0; k < 2; k++) { rm_have[i][k][0] = -1; rm_have[i][k][1] = 0; }
      rm_init = 1;
   }
   for (int i = 0; i < 6; i++)
      for (int k = 0; k < 2; k++)
         if (want[i][k][0] != rm_have[i][k][0] || (want[i][k][0] >= 0 && want[i][k][1] != rm_have[i][k][1])) {
            g_remap(RM_IDX[i], k, want[i][k][0], want[i][k][1]);
            rm_have[i][k][0] = want[i][k][0]; rm_have[i][k][1] = want[i][k][1];
         }
}
/* 표 한 줄(세 칸)을 «from → to» 로 — 0966bed 코어는 한 팔레트의 세 칸이 모두 같은 자리에 있어야 바꾼다(같은 색 칸도 적는다 · 옛 코어는 칸마다) */
static void rm_want(int want[6][2][2], int base, int slot, const uint16_t from[3], const uint16_t to[3])
{
   for (int j = 0; j < 3; j++) { want[base + j][slot][0] = from[j]; want[base + j][slot][1] = to[j]; }
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
      for (int k = 0; k < 2 && !ok; k++) ok = rm_have[j][k][0] == c[j] && rm_have[j][k][1] == v;
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

void look_tick(uint8_t *ram, size_t n, int input, int ctx)
{
   int want[6][2][2], block = 0, orr = 0;
   uint16_t t[3], b[3];
   if (!ram || n < 0x4000) return;
   pthread_mutex_lock(&K_mu);
   K.ram = ram;
   for (int i = 0; i < 6; i++) for (int k = 0; k < 2; k++) { want[i][k][0] = -1; want[i][k][1] = 0; }
   if (!K.on) {                                                       /* 꺼짐 — 표를 비우고, 쥐고 있던 세로 칸이 있으면 되돌린다 */
      if (K.owned && vpanel_on()) vpanel_restore();
      K.owned = 0;
      rm_push(want); look_in_block = 0; look_in_or = 0;
      pthread_mutex_unlock(&K_mu); return;
   }
   K.f++;
   int b_edge = (input & IN_B) && !(K.prev_in & IN_B);
   int o_dn = (input & IN_OPT) && !(K.prev_in & IN_OPT), o_up = !(input & IN_OPT) && (K.prev_in & IN_OPT);
   K.prev_in = input;
   if (K.hold_b && !(input & IN_B)) K.hold_b = 0;
   int md = ram[R_MODE], scr = ram[R_SCR];
   if (md == 240 && scr == 0 && vpanel_menu_kind()) {                 /* 타이틀 메뉴 — 경기 컬러를 잊는다(SCR 0 만으론 안 됨: 이어하기 뒤 8프레임 SCR 0) */
      if (K.sel >= 0) K.sel_gen++;
      K.sel = -1; K.sel_ch = -1; K.b2p = 0;
   }
   int sel_scr = md == 240 && (scr == 2 || scr == 4) && vpanel_layout();
   int owned = sel_scr && ctx == LOOK_CTX_FREE;
   if (o_dn) { K.opt_f = (int)K.f; K.opt_own = owned && !K.edit; }
   if (owned) {
      int h = ram[R_HOVER] >> 2;
      if (scr == 2) K.rand_pick = h == 15;
      int slot = (scr == 4 && K.rand_pick) || h == 15 ? 15 : h < 14 ? h : -1;   /* 무작위로 골랐으면 돌아가는 캐릭터와 상관없이 무작위 칸의 기억 */
      int row = h < 14 ? h : h == 15 ? LOOK_ROW_KUROKO : -1;
      if (slot >= 0 && row >= 0) {
         int was2 = K.owned && K.last_scr == 2;                       /* 지난 프레임에도 캐릭터 고르기를 쥐고 있었나 — 검질 고르기의 «뒤로» B 가
                                                                         같은 프레임에 캐릭터 고르기로 넘어와 2P색 결정으로 또 잡히지 않게(실측) */
         int sty_now = scr == 4 ? (ram[R_STYC] & 1) : -1;
         if (scr == 2 && K.last_scr == 4) K.b2p = 0;                 /* 검질 고르기에서 뒤로 — 2P색 결정 풀림 */
         K.last_scr = scr;
         if (K.edit) block = IN_ALL;                                  /* 컬러 만들기 창 — 게임에 아무것도 안 준다(고르기 화면엔 시간 제한이 없다, 실측) */
         else {
            block |= IN_OPT;                                          /* 게임은 고르기 화면에서 OPTION 을 안 쓴다(실측) */
            if (o_up && K.opt_own && (int)K.f - K.opt_f < LONG_F) {  /* 짧게: 다음 컬러 */
               int m = K.mem[slot] >= 0 && K.mem[slot] < LOOK_NIDX ? K.mem[slot] : 0;
               K.mem[slot] = (signed char)next_idx(row, K.b2p ? 1 : m, sty_now);
               K.b2p = 0; K.mem_gen++;
            }
            if ((input & IN_OPT) && K.opt_own && (int)K.f - K.opt_f == LONG_F && slot < 14) K.edit_req++;   /* 길게: 컬러 만들기(Java 가 창을 연다 ·
                                                                         쿠로코 칸·무작위로 고른 검질 고르기 제외 — 내 색은 캐릭터마다라 기억 칸이 그 캐릭터일 때만) */
            if (scr == 2) {                                           /* 게임은 캐릭터 고르기에서 B 를 안 쓴다(실측) */
               block |= IN_B;
               if (b_edge && was2 && !K.hold_b) { K.b2p = 1; K.inj = 1; K.inj_f = (int)K.f; K.hold_b = 1; }
            }
         }
         int m = K.mem[slot] >= 0 && K.mem[slot] < LOOK_NIDX && idx_ok(row, K.mem[slot], -1) ? K.mem[slot] : 0;
         K.cur = K.b2p ? 1 : m;
         int sch = h < 14 ? h : -1;                                   /* 무작위 뽑기가 멈춘 커서도 실제 캐릭터라 그대로 맞는다 */
         if (K.sel != K.cur || K.sel_ch != sch) K.sel_gen++;
         K.sel = K.cur; K.sel_ch = sch;
         K.sty_seen = sty_now;
         K.prow = row; K.psty = scr == 4 ? (ram[R_STYC] & 1) : 0; K.pslot = slot;
         if (g_pal) K.psty = preview_fix(row, K.psty, K.cur, scr == 4 && K.rand_pick);
         vpanel_put(LOOK_VLAB + (K.edit ? LOOK_NPRE : K.cur) * 18 * 16);   /* 만드는 중이면 «내 색 1» */
         if (K.cur > 0 || K.edit)
            for (int k = 0; k < 2; k++) {                             /* 두 자리 — 검질 고르기에서 ←→ 로 검질을 바꿔도 바로 그 검질의 그 컬러 · 보이는 검질이 자리 0 */
               int sty = k ^ K.psty;
               tri(row, sty, 0, b);
               if (K.edit) memcpy(t, K.edit_c, 6); else tri(row, sty, K.cur, t);
               if (!k || memcmp(b, t, 6)) rm_want(want, 0, k, b, t);
            }
      }
   } else {
      if (K.owned && vpanel_on()) vpanel_restore();                   /* 방금까지 쥐었다 — 세로 칸을 원래대로 */
      if (K.sel > 0 && !sel_scr && g_pal) {                           /* 경기 쪽(고르기 화면 밖) */
         int ch = K.sel_ch >= 0 ? K.sel_ch : ram[R_P1CH] < 14 ? ram[R_P1CH] : -1;
         if (ch >= 0) {
            int s0 = p1_sty(ram, ch), b2 = ram[R_P2BLK];
            for (int k = 0; k < 2; k++) {                             /* 그 판 검질이 자리 0 — 옛 코어가 두 검질 기본 색이 같은 칸에서 다른 검질 색을 넣지 않게 */
               int sty = k ^ s0;
               tri(ch, sty, 0, b); tri(ch, sty, K.sel, t);
               if (!k || memcmp(b, t, 6)) {
                  rm_want(want, 0, k, b, t);
                  if (pal_is(0, b)) pal_wr(0, t);                     /* 불러온 상태·켠 순간 — 게임이 다시 쓰기 전에도 */
               }
            }
            if (K.sel == 1 && (b2 >> 4) == ch && (b2 & 0x04)) {      /* 미러전 P2(게임이 2P색으로 칠함) → 기본 색 — 진짜 미러전만(아니면 옛 코어가 다른 상대 칸을 바꾼다) */
               int s2 = (b2 >> 3) & 1;
               const uint16_t *alt = LOOK_TAB[ch][s2][1];
               tri(ch, s2, 0, b);
               if (memcmp(alt, b, 6)) {
                  rm_want(want, 3, 0, alt, b);
                  if (pal_is(5, alt)) pal_wr(5, b);
               }
            }
         }
      }
   }
   K.owned = owned;
   if (K.inj) {                                                       /* B 2P색 결정 — A 를 2프레임 뒤부터 4프레임. 그동안 십자는 막는다(커서가 옮겨 다른 캐릭터로 결정되지 않게) */
      int d = (int)K.f + 1 - K.inj_f;
      if (d >= 2 && d < 6) orr |= IN_A;
      if (d < 8) block |= IN_DPAD;
      if (d >= 8) K.inj = 0;
   }
   if (K.hold_b) block |= IN_B;
   if (block & (IN_A | IN_B)) block |= IN_ABFOLD;
   rm_push(want);
   look_in_block = block; look_in_or = orr;
   pthread_mutex_unlock(&K_mu);
}

void look_enable(int on)
{
   pthread_mutex_lock(&K_mu);
   K.on = on ? 1 : 0; rm_init = 0;
   if (!on) { K.sel = -1; K.sel_ch = -1; K.cur = 0; K.b2p = 0; K.edit = 0; K.inj = 0; look_in_block = 0; look_in_or = 0; }   /* cur 0 — 꺼진 판의 리그 기록(허브 v[10])에 옛 컬러가 안 남게 */
   pthread_mutex_unlock(&K_mu);
}
void look_set_mem(const signed char *mem, int n)
{
   pthread_mutex_lock(&K_mu);
   for (int i = 0; i < LOOK_NSLOT; i++) K.mem[i] = (mem && i < n && mem[i] >= 0 && mem[i] < LOOK_NIDX) ? mem[i] : 0;
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
void look_set_match(int idx)
{
   pthread_mutex_lock(&K_mu);
   K.sel = (idx > 0 && idx < LOOK_NIDX) ? idx : -1; K.sel_ch = -1; K.sel_gen++;
   pthread_mutex_unlock(&K_mu);
}
void look_edit(int on, int c1, int c2, int c3)
{
   pthread_mutex_lock(&K_mu);
   K.edit = on ? 1 : 0;
   K.edit_c[0] = (uint16_t)(c1 & 0xFFF); K.edit_c[1] = (uint16_t)(c2 & 0xFFF); K.edit_c[2] = (uint16_t)(c3 & 0xFFF);
   pthread_mutex_unlock(&K_mu);
}
int look_cur(void) { pthread_mutex_lock(&K_mu); int c = K.cur; pthread_mutex_unlock(&K_mu); return c; }
void look_status(int v[LOOK_NSTAT])
{
   pthread_mutex_lock(&K_mu);
   v[0] = K.on; v[1] = K.owned; v[2] = K.mem_gen; v[3] = K.cur; v[4] = K.sel; v[5] = K.sel_ch; v[6] = K.b2p; v[7] = K.edit_req;
   v[8] = K.sty_seen; v[9] = K.sel_gen; v[10] = K.edit; v[11] = (int)K.f; v[12] = K.owned && K.pslot < 14 ? K.prow : -1; v[13] = K.psty;
   pthread_mutex_unlock(&K_mu);
}
void look_triples(int ch, int sty, int out[LOOK_NIDX * 3])
{
   uint16_t t[3];
   int row = ch >= 0 && ch < 15 ? ch : 0;
   pthread_mutex_lock(&K_mu);
   for (int i = 0; i < LOOK_NIDX; i++) { tri(row, sty & 1, i, t); for (int j = 0; j < 3; j++) out[i * 3 + j] = t[j]; }
   pthread_mutex_unlock(&K_mu);
}
/* 상태 저장 직전 — 세로 칸을 원래대로, 팔레트 0·5 도 우리가 바꾼 색이면 원래 색으로(저장된 상태는 원래 게임 그대로 · 다음 프레임에 다시 바꾼다).
   .look 없이 그 상태를 불러도(옛 앱·다른 판) 원래 색이고, 게임이 팔레트를 다시 쓸 때까지(대전 중 1초에 한 번꼴) 엉뚱한 색이 남지 않는다.
   캐릭터·검질은 알고 되돌린다 — 황금·은빛·그림자처럼 캐릭터가 달라도 같은 세 색인 프리셋(밝기 순위로)이 있어 색만으론 못 가린다.
   검질: 전투 중이고 P1 블록이 그 캐릭터면 블록의 검질, 아니면 검질 고르기 커서(0x574C — 확정 뒤에도 남음). 그 검질의 색이 아니면 다른 검질도 본다 */
void look_before_save(void)
{
   uint16_t b[3], t[3];
   pthread_mutex_lock(&K_mu);
   if (K.owned) vpanel_restore();
   if (g_pal && K.on && K.ram) {
      const uint8_t *ram = K.ram;
      int row = -1, s0 = 0, idx = 0;
      if (K.owned) { row = K.prow; s0 = K.psty; idx = K.edit ? -1 : K.cur; }
      else if (K.sel > 0) {
         row = K.sel_ch >= 0 ? K.sel_ch : ram[R_P1CH] < 14 ? ram[R_P1CH] : -1;
         s0 = p1_sty(ram, row);
         idx = K.sel;
      }
      if (row >= 0 && idx != 0)
         for (int k = 0; k < 2; k++) {
            int s = k ? !s0 : s0;
            if (idx < 0) memcpy(t, K.edit_c, 6); else tri(row, s, idx, t);
            if (pal_is(0, t)) { tri(row, s, 0, b); pal_wr(0, b); break; }
         }
      if (row >= 0 && row < 14 && !K.owned && K.sel == 1) {           /* 미러전 P2 — 우리가 2P색 → 기본 색으로 바꾼 것을 2P색으로 */
         int b2 = ram[R_P2BLK];
         if ((b2 >> 4) == row && (b2 & 0x04)) { int s2 = (b2 >> 3) & 1; tri(row, s2, 0, b); if (pal_is(5, b)) pal_wr(5, LOOK_TAB[row][s2][1]); }
      }
   }
   pthread_mutex_unlock(&K_mu);
}

/* 상태 저장 옆(.look, native.c) — 경기 컬러가 있을 때만 16바이트: 'L' 'K' 판 1 · 경기 컬러 · 경기 캐릭터(부호, −1 = 0x57D5) · 나머지 0 */
size_t look_blob(void *buf, size_t cap)
{
   pthread_mutex_lock(&K_mu);
   size_t n = 0;
   if (K.on && K.sel > 0 && cap >= 16) {
      uint8_t *p = (uint8_t *)buf; memset(p, 0, 16);
      p[0] = 'L'; p[1] = 'K'; p[2] = 1; p[3] = (uint8_t)K.sel; p[4] = (uint8_t)(signed char)K.sel_ch;
      n = 16;
   }
   pthread_mutex_unlock(&K_mu);
   return n;
}
/* 불러온 바로 뒤(코어 잠금 안) — .look 이 없거나 판이 안 맞으면 경기 컬러를 버린다(불러온 판 램에 맞지 않는 컬러가 남지 않게) */
void look_resume(const void *buf, size_t n)
{
   pthread_mutex_lock(&K_mu);
   const uint8_t *p = (const uint8_t *)buf;
   if (p && n >= 16 && p[0] == 'L' && p[1] == 'K' && p[2] == 1 && p[3] > 0 && p[3] < LOOK_NIDX && (signed char)p[4] >= -1 && (signed char)p[4] < 14) {
      K.sel = p[3]; K.sel_ch = (signed char)p[4];
   } else { K.sel = -1; K.sel_ch = -1; }
   K.b2p = 0; K.sel_gen++;
   pthread_mutex_unlock(&K_mu);
}
void look_forget(void)
{
   pthread_mutex_lock(&K_mu);
   K.sel = -1; K.sel_ch = -1; K.cur = 0; K.b2p = 0; K.edit = 0; K.owned = 0; K.hold_b = 0; K.inj = 0; K.last_scr = -1; K.prow = -1; K.opt_own = 0; K.pv_has = 0;
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
JNI(void, nativeLookSetMatch)(JNIEnv *env, jclass cls, jint idx) { (void)env; (void)cls; look_set_match(idx); }
JNI(void, nativeLookEdit)(JNIEnv *env, jclass cls, jboolean on, jint c1, jint c2, jint c3) { (void)env; (void)cls; look_edit(on ? 1 : 0, c1, c2, c3); }
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
