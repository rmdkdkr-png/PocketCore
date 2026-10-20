/* 타격 알림 — 설명은 callout.h. 램 주소·칸 규칙은 practice.c(연습 대전 프레임 미터)와 같은 것만 쓴다. */
#include "callout.h"
#include "callout_atlas.h"
#include "hitfx_cels.h"
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#define RAM_BASE 0x4000
#define A(x)     ((x) - RAM_BASE)
#define R_MODE   A(0x40A7)   /* 241 전투 */
#define R_FIGHT  A(0x411A)   /* 1 = 라운드 진행 */
#define R_RES2   A(0x5823)   /* 판 끝남 표시(HUD 이름표와 같은 «라운드 조작 중» 조건) */
#define R_RES    A(0x5824)   /* 라운드 결과(0 = 진행 중) */
#define R_HSTOP  A(0x5DC7)   /* 히트스톱(두 사람 공통) */
#define R_PAUSE  A(0x61D4)   /* 일시정지 1 */
#define R_OBJN   A(0x41C2)   /* 활성 물체 수 */
#define R_OBJLIST A(0x4180)  /* 활성 물체 목록 — (칸 번호, 정렬 값) */
#define PB(p)    (A(0x5980) + (p) * 0x200)
#define O_STANCE 0x49        /* 0x20 서기 · 0x40 앉기 · 0x80 공중 | 0x10 상단 가드 · 0x08 하단 가드 · 0x03 튕겨내기 */
#define O_CTRL   0x4E        /* 0 움직일 수 있음 · 04/06 대시 · 08 점프 · 0A 맞음 · 0C 공중 맞음 · 10 다운 · 12 가드 · 02 보통 공격 · FF 필살·잡기·반동 */
#define O_Y      0x5A        /* 땅 = 128 */
#define O_HP     0xC6
#define O_COMBO  0x117
#define OBJ(k)   (A(0x4E30) + (k) * 0x40)   /* 물체 — 싸움꾼 0·1: +0x0E ACT · +0x1C 공격 상자(FF = 없음) · 장풍 +0x06 살아 있음 · +0x1E/+0x1C 상자 · +0x27 주인 */
#define R_CAMX   A(0x576D)   /* 화면 왼쪽 끝 무대 X(배경 카메라 8.8 의 정수 — 코어 패치 30 몸 자리와 같은 값) */
#define RAM_NEED (A(0x61D4) + 1)

enum { K_IDLE = 0, K_MOVE, K_STARTUP, K_ACTIVE, K_RECOVERY, K_HITSTUN, K_BLOCKSTUN, K_DOWN };
enum { C_NONE = 0, C_HIT, C_BLOCK, C_THROW, C_OTHER, C_MULTI };

static atomic_int co_on = 0;
static atomic_int fx_mode = 0;                 /* 타격 이펙트 FXM_* (0 = 끔) */
static const uint8_t *fx_pal;                  /* 코어 스프라이트 팔레트 램(0x8200~) — 칼 궤적 색(GL 스레드, tick 앞에 받음) */
static atomic_int co_reset_req = 1;            /* 지우기는 GL 스레드에서만(tick·get 이 먼저 본다) — 다른 스레드는 부탁만 */
static struct {
   int have_prev, live_prev, first_done;
   uint32_t f;
   uint8_t p_hs, p_ctrl[2], p_combo[2], p_cap[32];
   uint16_t p_act[2];
   int8_t armed[32];
   uint8_t mode[2], stun[2], downseen[2], boxq[2][2], armq[2][2];
   int act_live[2], act_first[2], act_n[2];
   uint8_t cls[2];                              /* 마지막으로 센 칸(히트스톱 프레임은 안 셈) — 닿은 프레임에서 보면 «바로 앞 프레임»의 칸 */
   uint8_t rv_stun[2]; int64_t rv_free[2];     /* 경직을 봤나 · 처음 움직일 수 있게 된 프레임(−1 = 아직) */
   uint8_t p_hp[2];                             /* 앞 프레임 체력 — 닿은 프레임에 준 피해(그 프레임에 바로 준다, 봇 47번 중 45) */
   int cam;                                     /* 화면 왼쪽 끝의 무대 X(0x576D — 코어 몸 자리와 같은 값) */
   int stats[8];
} S;
static callout_t L[2][CO_ROWS];                 /* 쪽마다 줄 — t0 < 0 = 빈 칸 */
/* 타격 이펙트(도트) — 게임 프레임으로 센다(히트스톱 동안 첫 컷 멈춤 · 그다음 2프레임마다 한 컷 · 게임이 멈추면 같이 멈춤) */
typedef struct {
   int on, kind, ring;          /* kind FXK_* · ring 0 없음 / 1 COUNTER / 2 PUNISHER */
   int wx, y, dir;              /* 무대 X(화면 x = wx − 카메라) · 게임 y · 밀리는 쪽 ±1(강 섬광은 그쪽으로 뻗음) */
   int cel, sub, hold;          /* 지금 컷 · 컷 안 프레임 · 히트스톱 동안 첫 컷 유지 */
   uint16_t col[3];             /* 12비트 0x0BGR — 1 흰 심 · 2 가운데 · 3 테두리(때린 쪽 칼 궤적 색) */
   uint32_t seq;                /* 낸 차례(다 차면 가장 오래된 것) */
} dfx_t;
enum { FXK_L = 0, FXK_H, FXK_T, FXK_G, FXK_C, FXK_D, FXK_K, FXK_S };   /* 섬광 약·강·작은 것 · 185: 가드 반달 · 칼 부딪힘 · 넘어짐 먼지 · 막타 일섬 · 187: 강베기 일섬(칼 궤적 색) */
static dfx_t FX[FX_MAX];
static uint32_t fx_seq;
static struct { uint8_t on, ctrl[2]; uint16_t y[2]; uint32_t f, last[2]; } DU;   /* 185 넘어짐 먼지 — 라운드 조작 밖(KO 뒤 쓰러짐)에서도 보려고 따로 센다 */
static int flash_req;                           /* 186 COUNTER 몸 번쩍 — 맞은 쪽 + 1(native.c 가 callout_tick 바로 뒤 가져가 hitflash_start) */
/* 186 화면 흔들림 — 게임 그림을 몇 진짜 프레임 좌우로(막타는 위아래도) 흔든다(native.c gl_draw 가 뷰포트를 옮김 — 라벨·터치 자리는 그대로).
   세기 1 = 칼 부딪힘·약한 COUNTER · 2 = 센 맞음(피해 12 이상) · 3 = 막타. 값은 ¼ 게임 px, 첫 칸은 맞은 쪽이 밀리는 쪽으로. 더 센 것이 도는 중이면 약한 것은 무시 */
static const int8_t SK_X1[] = { 4, -4, 2, -2 };
static const int8_t SK_X2[] = { 6, -6, 5, -5, 3, -3, 2, -2, 1 };
static const int8_t SK_X3[] = { 10, -10, 8, -8, 6, -6, 4, -4, 3, -3, 2, -1 };
static const int8_t SK_Y3[] = { -4, 4, -3, 3, -2, 2, -1, 1, 0, 0, 0, 0 };
static struct { int lv, t, dir; } SK;
static atomic_int fl_on = 0;                    /* 191 몸 번쩍 — 이펙트와 따로 켬/끔(코어 옵션 ngp_hit_flash · 앱 «몸 번쩍») */
static atomic_int sk_on = 0;                    /* 189 화면 흔들림 기본 끔 — 유저 10-21 00:01 「약간 멀미나고 토할거같은데」(186~188 의 화면 전체 흔들림이 유력).
                                                   코드는 남겨 두고 callout_shake_enable 로만 켠다(지금 앱 설정엔 없음 — 코어 이전 때 «맞은 몸만 떨림»으로 바꿀 것) */

static int any_on(void) { return atomic_load(&co_on) || atomic_load(&fx_mode) != FXM_OFF || atomic_load(&fl_on); }
void callout_enable(int on)
{
   if (!atomic_exchange(&co_on, on ? 1 : 0) && on) atomic_store(&co_reset_req, 1);   /* 꺼져 있던 동안의 앞 프레임 값으로 판정하지 않게 */
}
void callout_flash_enable(int on)
{
   int was = any_on();
   atomic_store(&fl_on, on ? 1 : 0);
   if (!was && on) atomic_store(&co_reset_req, 1);
}
int  callout_enabled(void) { return atomic_load(&co_on); }

void callout_reset(void) { atomic_store(&co_reset_req, 1); }
/* 187 보는 램이 바뀜(런어헤드 앞선 프레임 수가 바뀜·코어가 이번엔 보여 준 프레임 사본을 못 줌) — 앞 프레임 값으로 닿음을 판정하지 않게.
   이펙트·흔들림·라벨은 그대로 둔다 */
void callout_resync(void) { S.have_prev = 0; DU.on = 0; }

void callout_fx_pal(const uint8_t *pal) { fx_pal = pal; }

void callout_fx_mode(int mode)
{
   if (mode < 0 || mode >= FXM_N) mode = FXM_OFF;
   if (atomic_exchange(&fx_mode, mode) == FXM_OFF && mode != FXM_OFF && !atomic_load(&co_on) && !atomic_load(&fl_on)) atomic_store(&co_reset_req, 1);
}
int callout_fx_mode_get(void) { return atomic_load(&fx_mode); }

static void reset_now(void)
{
   int st[8];
   memcpy(st, S.stats, sizeof st);
   memset(&S, 0, sizeof S);
   memcpy(S.stats, st, sizeof st);
   memset(S.armed, -1, sizeof S.armed);
   for (int s = 0; s < 2; s++) for (int r = 0; r < CO_ROWS; r++) { L[s][r].t0 = -1e9; L[s][r].kind = -1; L[s][r].side = s; L[s][r].row = r; }
   memset(FX, 0, sizeof FX);
   memset(&DU, 0, sizeof DU);
   flash_req = 0;
   memset(&SK, 0, sizeof SK);
}

static int rd16(const uint8_t *ram, int o) { return ram[o] | (ram[o + 1] << 8); }

static const hfx_cel *fx_cels(int kind, int *n)
{
   switch (kind) {
   case FXK_H: *n = HFX_N_H; return HFX_H;
   case FXK_T: *n = HFX_N_T; return HFX_T;
   case FXK_G: *n = HFX_N_G; return HFX_G;
   case FXK_C: *n = HFX_N_C; return HFX_C;
   case FXK_D: *n = HFX_N_D; return HFX_D;
   case FXK_K: *n = HFX_N_K; return HFX_K;
   case FXK_S: *n = HFX_N_S; return HFX_S;          /* 192 앞 강베기 일섬 — 따로 그린 짧은 초승달(31×23, 움직임). 187~191 은 막타 일섬 그림을 썼다 */
   default:    *n = HFX_N_L; return HFX_L;
   }
}
/* 185 고정 색(12비트 0x0BGR — 흰 심·가운데·테두리): 가드 푸른 · 칼 부딪힘 노랑 · 먼지 흙(밝음·가운데·어두움) · 막타 빨강 */
static const uint16_t COL_G[3] = { 0xFFF, 0xFD8, 0xC62 }, COL_C[3] = { 0xFFF, 0x9FF, 0x2AF },
                      COL_D[3] = { 0xBDE, 0x7AC, 0x468 }, COL_K[3] = { 0xFFF, 0x23F, 0x007 };

static int luma12(uint16_t c) { return 299 * (c & 15) + 587 * ((c >> 4) & 15) + 114 * ((c >> 8) & 15); }

/* 때린 쪽 a 의 칼 궤적 팔레트(스프라이트 팔레트 P1 1~4 · P2 6~9 중 궤적 칸 — slash.c 와 같은 규칙: 아수라 +1, 나머지 +2) 세 색을 밝기 순으로 →
   이펙트 색 = 흰 심 · 둘째로 밝은 색 · 가장 어두운 색. 베기 색을 바꿨으면 그 색(팔레트 램엔 바꾼 색이 들어 있다). 못 읽으면 금빛 */
static void trail_colors(const uint8_t *ram, int a, uint16_t col[3])
{
   uint16_t c[3] = { 0xBFF, 0x0EF, 0x0AF };
   int ch = (ram[a ? A(0x5D51) : A(0x5B51)] >> 2) >> 2, slot = (a ? 6 : 1) + (ch == 8 ? 0 : 1);
   if (fx_pal) {
      uint16_t t[3]; int ok = 0;
      for (int j = 0; j < 3; j++) { const uint8_t *e = fx_pal + slot * 8 + (j + 1) * 2; t[j] = (uint16_t)((e[0] | (e[1] << 8)) & 0xFFF); ok |= t[j] != 0; }
      if (ok) memcpy(c, t, sizeof c);
   }
   for (int i = 0; i < 2; i++) for (int j = 0; j < 2 - i; j++) if (luma12(c[j]) < luma12(c[j + 1])) { uint16_t x = c[j]; c[j] = c[j + 1]; c[j + 1] = x; }
   if (luma12(c[1]) < 1500) { c[1] = 0x0EF; c[2] = 0x0AF; }               /* 너무 어두우면(아수라 +2 남색 같은) 금빛 */
   col[0] = 0xFFF; col[1] = c[1]; col[2] = c[2];
}

static dfx_t *fx_new(void)
{
   int i, use = -1;
   if (atomic_load(&fx_mode) == FXM_OFF) return NULL;
   for (i = 0; i < FX_MAX; i++) if (!FX[i].on) { use = i; break; }
   if (use < 0) { use = 0; for (i = 1; i < FX_MAX; i++) if (FX[i].seq < FX[use].seq) use = i; }
   memset(&FX[use], 0, sizeof FX[use]);
   FX[use].on = 1; FX[use].seq = ++fx_seq;
   return &FX[use];
}

/* 맞은 쪽 d 에 하나 — 자리 = 맞은 몸(물체 +8 X · +0xA Y, 땅 128)에서 때린 쪽으로 7px(가드 9px), 몸 가운데 높이(서기 25 · 앉음 15 · 공중 22px 위).
   막타 일섬은 몸 가운데(X 그대로, 24px 위). 색: 섬광 = 흰 + 때린 쪽 칼 궤적 · 가드·막타 = 고정 */
static void fx_spawn(const uint8_t *ram, int d, int kind, int ring)
{
   int a = 1 - d;
   dfx_t *e = fx_new();
   if (!e) return;
   int dx = rd16(ram, OBJ(d) + 8), ax = rd16(ram, OBJ(a) + 8), fy = rd16(ram, OBJ(d) + 0xA), st = ram[PB(d) + O_STANCE];
   int dir = ax <= dx ? 1 : -1, h = (st & 0x40) ? 15 : fy < 128 ? 22 : 25;
   e->kind = kind; e->ring = ring; e->dir = dir;
   e->wx = kind == FXK_K || kind == FXK_S ? dx : dx - dir * (kind == FXK_G ? 9 : 7);
   e->y = kind == FXK_K || kind == FXK_S ? fy - 24 : fy - h;
   e->hold = kind != FXK_T && kind != FXK_H && kind != FXK_S;   /* 192 혜성·앞 강베기 일섬은 히트스톱 동안에도 움직임(유저 「정지화면 뚝이라 애니메이션으로」) */
   if (kind == FXK_G) memcpy(e->col, COL_G, sizeof e->col);
   else if (kind == FXK_K) memcpy(e->col, COL_K, sizeof e->col);
   else trail_colors(ram, a, e->col);
}

/* 185 칼 부딪힘 — 맞음·가드 없이 히트스톱만 선 닿음(봇 판 555 맞음 · 28 가드 · 11 그 밖). 두 몸 사이 가운데, 가슴 높이 */
static void fx_clash(const uint8_t *ram)
{
   dfx_t *e = fx_new();
   if (!e) return;
   int x0 = rd16(ram, OBJ(0) + 8), x1 = rd16(ram, OBJ(1) + 8), y0 = rd16(ram, OBJ(0) + 0xA), y1 = rd16(ram, OBJ(1) + 0xA);
   e->kind = FXK_C; e->dir = 1; e->hold = 1;
   e->wx = (x0 + x1) / 2; e->y = (y0 < y1 ? y0 : y1) - 26;
   memcpy(e->col, COL_C, sizeof e->col);
}

/* 185 넘어짐 먼지 — 다운(조작 0x10)이 시작될 때(봇 판 111번 모두 땅 위) · 공중에서 맞은 몸(0x0C)이 땅(128)에 닿을 때(96번). 몸마다 12 프레임 안엔 한 번 */
static void dust_tick(const uint8_t *ram)
{
   int fight = ram[R_MODE] == 241 && ram[R_PAUSE] != 1;
   DU.f++;
   for (int p = 0; p < 2; p++) {
      int c = ram[PB(p) + O_CTRL], y = rd16(ram, PB(p) + O_Y);
      if (fight && DU.on && ((c == 0x10 && DU.ctrl[p] != 0x10) || (c == 0x0C && DU.y[p] < 128 && y >= 128)) && (!DU.last[p] || DU.f - DU.last[p] > 12)) {
         dfx_t *e = fx_new();
         if (e) {
            e->kind = FXK_D; e->dir = 1; e->hold = 0;
            e->wx = rd16(ram, OBJ(p) + 8); e->y = 128;
            memcpy(e->col, COL_D, sizeof e->col);
         }
         DU.last[p] = DU.f;
      }
      DU.ctrl[p] = (uint8_t)c; DU.y[p] = (uint16_t)y;
   }
   DU.on = (uint8_t)fight;
}

static int sk_len(int lv) { return lv == 3 ? (int)sizeof SK_X3 : lv == 2 ? (int)sizeof SK_X2 : lv == 1 ? (int)sizeof SK_X1 : 0; }
static void shake(int lv, int dir)
{
   if (!atomic_load(&sk_on) || atomic_load(&fx_mode) == FXM_OFF || (SK.lv > lv && SK.t < sk_len(SK.lv))) return;
   SK.lv = lv; SK.t = 0; SK.dir = dir < 0 ? -1 : 1;
}
static void shake_advance(void) { if (SK.lv && ++SK.t >= sk_len(SK.lv)) SK.lv = 0; }

/* 한 진짜 프레임 — 멈춤(hold) 이펙트는 히트스톱 중 첫 컷 그대로, 아니면 컷 길이(dur, 0 = 2 프레임)마다 다음 컷 */
static void fx_advance(int hs)
{
   for (int i = 0; i < FX_MAX; i++) {
      dfx_t *e = &FX[i]; int n, dur;
      if (!e->on) continue;
      const hfx_cel *cs = fx_cels(e->kind, &n);
      if (e->cel >= n) { e->on = 0; continue; }
      if (e->hold && e->cel == 0 && hs) continue;
      e->hold = 0;
      dur = cs[e->cel].dur ? cs[e->cel].dur : 2;
      if (++e->sub < dur) continue;
      e->sub = 0;
      if (++e->cel >= n) e->on = 0;
   }
}

static void emit(int kind, int side, double now)
{
   int r, use = -1;
   S.stats[kind]++;
   if (!atomic_load(&co_on)) return;                  /* 라벨 끔 — 이펙트만 켠 경우 */
   for (r = 0; r < CO_ROWS; r++) if (L[side][r].kind == kind && now - L[side][r].t0 < CO_LIFE_F / 60.0) { use = r; break; }   /* 같은 라벨이 떠 있으면 다시 시작 */
   if (use < 0) for (r = 0; r < CO_ROWS; r++) if (now - L[side][r].t0 >= CO_LIFE_F / 60.0) { use = r; break; }
   if (use < 0) { use = 0; for (r = 1; r < CO_ROWS; r++) if (L[side][r].t0 < L[side][use].t0) use = r; }   /* 다 차면 가장 오래된 줄 */
   L[side][use].kind = kind; L[side][use].side = side; L[side][use].row = use; L[side][use].t0 = now;
}


void callout_tick(const uint8_t *ram, size_t ramsz, double now)
{
   uint8_t ctrl[2], combo[2], st[2], box[2], proj[2], cap[32];
   uint16_t act[2], y[2];
   int hs, live, p;
   if (atomic_exchange(&co_reset_req, 0)) reset_now();
   if (!any_on() || !ram || ramsz < (size_t)RAM_NEED) return;
   fx_advance(ram[R_HSTOP]);                             /* 이펙트는 판이 끝나는 중(KO·대사)에도 끝까지 */
   shake_advance();                                      /* 186 흔들림도 판 끝(KO) 중에 끝까지 */
   if (atomic_load(&fx_mode) != FXM_OFF) dust_tick(ram);
   S.cam = ram[R_CAMX];
   live = ram[R_MODE] == 241 && ram[R_FIGHT] == 1 && ram[R_RES] == 0 && ram[R_RES2] == 0 && ram[R_PAUSE] != 1;
   if (!live) { S.live_prev = 0; S.have_prev = 0; return; }
   if (!S.live_prev) {                                   /* 라운드 조작이 (다시) 시작 — 둘 다 만피면 새 라운드 */
      S.live_prev = 1;
      if (ram[PB(0) + O_HP] == 128 && ram[PB(1) + O_HP] == 128) { S.first_done = 0; S.stats[5]++; }
   }
   hs = ram[R_HSTOP];
   for (p = 0; p < 2; p++) {
      int b = PB(p), o = OBJ(p);
      ctrl[p] = ram[b + O_CTRL]; combo[p] = ram[b + O_COMBO]; st[p] = ram[b + O_STANCE];
      y[p] = (uint16_t)rd16(ram, b + O_Y); act[p] = (uint16_t)rd16(ram, o + 0x0E); box[p] = ram[o + 0x1C] != 0xFF;
   }
   memset(cap, 0, sizeof cap);
   {
      int n = ram[R_OBJN] > 30 ? 30 : ram[R_OBJN];
      for (int i = 0; i < n; i++) {
         int k = ram[R_OBJLIST + 2 * i];
         if (k < 4 || k > 31) continue;
         int o = OBJ(k);
         if (ram[o + 0x06] != 0xFF && (ram[o + 0x1E] != 0xFF || ram[o + 0x1C] != 0xFF)) cap[k] = (uint8_t)(1 | (ram[o + 0x27] & 1) << 1 | (ram[o + 0x27] > 1 ? 4 : 0));
      }
   }
   S.f++;
   if (!S.have_prev) goto keep;
   {
      int frozen = hs && S.p_hs;
      /* 닿음 — fd_tick 과 같은 차례 */
      int kind = C_NONE, d = -1, q;
      if (hs && !S.p_hs) {
         for (q = 0; q < 2 && d < 0; q++) if (combo[q] > S.p_combo[q]) { d = q; kind = C_HIT; }
         for (q = 0; q < 2 && d < 0; q++) if (st[q] & 0x18) { d = q; kind = C_BLOCK; }
         for (q = 0; q < 2 && d < 0; q++) if (st[q] & 0x03) { d = q; kind = C_OTHER; }
         if (d < 0) kind = C_OTHER;
      } else if (!hs) {
         for (q = 0; q < 2 && d < 0; q++) if (combo[q] > S.p_combo[q]) { d = q; kind = S.p_combo[q] == 0 ? C_THROW : C_MULTI; }
      }
      if (d >= 0 && (kind == C_HIT || kind == C_THROW)) {
         int a = 1 - d;
         S.stats[4] += kind == C_HIT; S.stats[6] += kind == C_THROW;
         if (!S.first_done) { S.first_done = 1; emit(CO_FIRST, a, now); }
         if (kind == C_HIT) {
            int ctr = S.cls[d] == K_STARTUP || S.cls[d] == K_ACTIVE, pun = S.cls[d] == K_RECOVERY;
            if (ctr) emit(CO_COUNTER, a, now);   /* 189: COUNTER 몸 번쩍 뺌(너무 잦음) — 번쩍은 서서 친 강베기만 */
            else if (pun) emit(CO_PUNISH, a, now);
            {  /* 강(혜성) = 보통 기술로 피해 12 이상(약베기 6~11 · 강베기 14~21 실측 — 닿은 프레임에 바로 줄어듦).
                  필살(ACT ≥ 0x180, 177 실측)은 원작이 불·감전 해골·큰 초승달을 이미 그려서 작은 섬광만(절제 — 봇 판 그림에서 겹쳐 지저분했음) */
               int dmg = S.p_hp[d] > ram[PB(d) + O_HP] ? S.p_hp[d] - ram[PB(d) + O_HP] : 0;
               int ko = ram[PB(d) + O_HP] == 0, heavy = dmg >= 12 && act[a] < 0x180;   /* 187 강베기(보통 기술 센 맞음) — 유저 「앞강베기 타격효과가 가장 강렬해야」 */
               /* 189 유저 「너무 잦고 강함」·「강베기에만 넣어라고 했을텐데? 앞 강베기」 — 일섬·몸 번쩍은 앞 강베기만(COUNTER·막타·다른 강베기는 뺌).
                  앞 강베기 = 때린 쪽 ACT 0x64(내딛기)·0x68(베기) — 봇 대본 실측(아수라: 중립·뒤+A 꾹 = 0x60, 앞+A 꾹 = 0x64 → 0x68, 아래+A 꾹 = 0x6C ·
                  CPU 9: 0x68 맞음이 히트스톱 9~11 로 가장 김 — 보통 기술 ACT 칸은 캐릭터마다 같은 자리) */
               int front = !ko && (act[a] == 0x64 || act[a] == 0x68);
               /* 185: 체력 0 = 막타 일섬 · 192: 앞 강베기는 작은 일섬 하나(혜성 없이 — 191 까지 혜성 40×15 + 일섬 49×37 이 히트스톱 내내 서 있어
                  유저 「너무 커서 … 정지화면 뚝」) */
               fx_spawn(ram, d, ko ? FXK_K : front ? FXK_S : heavy ? FXK_H : FXK_L, ctr ? 1 : pun ? 2 : 0);
               {  /* 186 흔들림 — 필살도 센 맞음이면(그림은 절제해도 손맛은). 187: 강베기는 막타와 같은 가장 큰 흔들림 */
                  int sd = rd16(ram, OBJ(a) + 8) <= rd16(ram, OBJ(d) + 8) ? 1 : -1;
                  if (ko || heavy) shake(3, sd); else if (dmg >= 12) shake(2, sd); else if (ctr) shake(1, sd);
               }
               if (front && atomic_load(&fl_on)) flash_req = d + 1;   /* 189 서서 친 강베기만 맞은 몸 번쩍 · 191 이펙트와 따로 켬 */
            }
         }
      }
      if (d >= 0 && kind == C_MULTI) {
         fx_spawn(ram, d, ram[PB(d) + O_HP] == 0 ? FXK_K : FXK_T, 0);
         if (ram[PB(d) + O_HP] == 0) shake(3, rd16(ram, OBJ(1 - d) + 8) <= rd16(ram, OBJ(d) + 8) ? 1 : -1);   /* 186 연타 막타도 */
      }
      if (d >= 0 && kind == C_BLOCK) fx_spawn(ram, d, FXK_G, 0);          /* 185 가드 반달 */
      if (kind == C_OTHER && d < 0) { fx_clash(ram); shake(1, 1); }      /* 185 칼 부딪힘(튕겨내기 비트가 선 것은 빼고) · 186 작은 흔들림 */   /* 히트스톱 없는 여러 번 맞음(장풍·연타 필살) — 큰 섬광은 첫 타만, 나머지는 작은 것 */
      if (kind != C_NONE && d >= 0) { S.mode[d] = 2; S.stun[d] = kind == C_BLOCK ? C_BLOCK : C_HIT; }
      /* 장풍 arm — 주인이 동작 중에 켠 공격 상자만 */
      for (int k = 4; k < 32; k++) {
         if (!(cap[k] & 1)) { S.armed[k] = -1; continue; }
         int ow = (cap[k] & 4) ? -1 : (cap[k] >> 1) & 1;
         if (!(S.p_cap[k] & 1) && ow >= 0 && S.mode[ow] == 1) S.armed[k] = (int8_t)ow;
      }
      proj[0] = proj[1] = 0;
      for (int k = 4; k < 32; k++) if (S.armed[k] >= 0) proj[S.armed[k]] = 1;
      if (frozen) goto keep;                            /* 히트스톱 동안은 칸을 안 센다(앞 칸 그대로) */
      for (p = 0; p < 2; p++) {
         int act_now = S.boxq[p][1], proj_now = S.armq[p][1], c;
         S.boxq[p][1] = S.boxq[p][0]; S.boxq[p][0] = box[p];
         S.armq[p][1] = S.armq[p][0]; S.armq[p][0] = proj[p];
         if (ctrl[p] == 0) {
            S.act_live[p] = 0; S.mode[p] = 0; S.downseen[p] = 0;
            c = y[p] < 128 ? K_MOVE : K_IDLE;
         } else if (S.mode[p] == 2 || ctrl[p] == 0x0A || ctrl[p] == 0x0C || ctrl[p] == 0x10 || ctrl[p] == 0x12) {
            if (S.mode[p] != 2) { S.stun[p] = ctrl[p] == 0x12 ? C_BLOCK : C_HIT; S.downseen[p] = 0; }
            S.act_live[p] = 0; S.mode[p] = 2;
            if (ctrl[p] == 0x10) S.downseen[p] = 1;
            c = S.downseen[p] ? K_DOWN : (S.stun[p] == C_BLOCK || ctrl[p] == 0x12) ? K_BLOCKSTUN : K_HITSTUN;
         } else if (ctrl[p] == 0x04 || ctrl[p] == 0x06 || ctrl[p] == 0x08) {
            S.act_live[p] = 0; S.mode[p] = 0;
            c = K_MOVE;
         } else {
            int cancel = S.mode[p] == 1 && S.p_ctrl[p] == 0x02 && act[p] >= 0x180 && act[p] != S.p_act[p];
            if (S.mode[p] != 1 || cancel) {                /* 새 동작 */
               S.act_live[p] = 1; S.act_first[p] = -1; S.act_n[p] = 0;
               act_now = 0; S.boxq[p][0] = box[p]; S.boxq[p][1] = 0;
               proj_now = 0; S.armq[p][0] = proj[p]; S.armq[p][1] = 0;
               /* REVERSAL — 경직이 풀린 뒤 첫 틱(2 프레임 안)에 공격 동작 */
               if (S.rv_stun[p] && (ctrl[p] == 0x02 || ctrl[p] == 0xFF)) {
                  int64_t gap = S.rv_free[p] < 0 ? 0 : (int64_t)S.f - S.rv_free[p];
                  S.stats[7]++;
                  if (gap <= 2) emit(CO_REVERSAL, p, now);
               }
               S.rv_stun[p] = 0;
            }
            S.mode[p] = 1;
            if (act_now) { if (S.act_first[p] < 0) S.act_first[p] = S.act_n[p]; c = K_ACTIVE; }
            else if (proj_now && S.act_first[p] < 0) { S.act_first[p] = S.act_n[p]; c = K_ACTIVE; }
            else c = S.act_first[p] >= 0 ? K_RECOVERY : K_STARTUP;
            S.act_n[p]++;
         }
         /* 리버설 셈 — 경직 칸이면 «봤음», 움직일 수 있게 된 첫 프레임을 적고, 8 프레임 넘게 그냥 서 있으면 잊는다 */
         if (c == K_HITSTUN || c == K_BLOCKSTUN || c == K_DOWN) { S.rv_stun[p] = 1; S.rv_free[p] = -1; }
         else if (S.rv_stun[p] && (c == K_IDLE || c == K_MOVE)) {
            if (S.rv_free[p] < 0) S.rv_free[p] = (int64_t)S.f;
            else if ((int64_t)S.f - S.rv_free[p] > 8) S.rv_stun[p] = 0;
         }
         S.cls[p] = (uint8_t)c;                         /* 다음 프레임의 닿음은 이 칸을 본다 */
      }
   }
keep:
   S.p_hs = (uint8_t)hs;
   for (p = 0; p < 2; p++) { S.p_ctrl[p] = ctrl[p]; S.p_combo[p] = combo[p]; S.p_act[p] = act[p]; S.p_hp[p] = ram[PB(p) + O_HP]; }
   memcpy(S.p_cap, cap, sizeof cap);
   S.have_prev = 1;
}

int callout_get(callout_t *out, int max, double now)
{
   int n = 0;
   if (atomic_exchange(&co_reset_req, 0)) reset_now();
   for (int s = 0; s < 2; s++) for (int r = 0; r < CO_ROWS; r++) {
      double age = now - L[s][r].t0;
      if (L[s][r].kind < 0 || age < 0 || age >= CO_LIFE_F / 60.0 || n >= max) continue;
      out[n++] = L[s][r];
   }
   for (int i = 1; i < n; i++) for (int j = i; j > 0 && out[j].t0 < out[j - 1].t0; j--) { callout_t t = out[j]; out[j] = out[j - 1]; out[j - 1] = t; }
   return n;
}

int callout_anim(double age, float *slide, float *alpha, float *flash)
{
   double f = age * 60.0;
   if (f < 0 || f >= CO_LIFE_F) return 0;
   if (f < 6) { double u = f / 6.0; u = 1 - (1 - u) * (1 - u) * (1 - u); *slide = (float)(1 - u); }   /* 6 프레임 동안 밀려 들어옴(감속) */
   else *slide = 0.f;
   *flash = f < 4 ? (float)(0.7 * (1 - f / 4)) : 0.f;                                                   /* 들어올 때 잠깐 하얗게 */
   *alpha = f < 46 ? 1.f : (float)(1 - (f - 46) / (CO_LIFE_F - 46.0));                                  /* 끝 14 프레임 사라짐 */
   if (*alpha < 0) *alpha = 0;
   return 1;
}

/* 라벨 색 — 위·아래 그러데이션 · 테두리 (0xRRGGBB) */
static const uint32_t CO_TOP[CO_KINDS] = { 0xFFF6C8, 0xFFF27A, 0xFF9A8A, 0xC8F6FF };
static const uint32_t CO_BOT[CO_KINDS] = { 0xFFB000, 0xFF8A00, 0xE0141E, 0x1E8CFF };
static const uint32_t CO_OUT[CO_KINDS] = { 0x3A1A00, 0x2A1200, 0x2A0006, 0x001A3A };
#define CO_SHADOW 2                     /* 그림자 어긋남(텍스처 px) · 세기 0.55 */

static uint8_t *g_atl;
static int g_atl_w, g_atl_h, g_atl_rect[CO_KINDS][4];

int callout_atlas(const uint8_t **rgba, int *w, int *h, int rect[CO_KINDS][4], int *scale)
{
   if (!g_atl) {
      int W = 0, H = 0, y = 1;
      for (int k = 0; k < CO_KINDS; k++) { if (CALLOUT_ATLAS[k].w + CO_SHADOW + 2 > W) W = CALLOUT_ATLAS[k].w + CO_SHADOW + 2; H += CALLOUT_ATLAS[k].h + CO_SHADOW + 2; }
      H += 1;
      uint8_t *img = (uint8_t *)calloc((size_t)W * H, 4), *nib = NULL;
      if (!img) return 0;
      for (int k = 0; k < CO_KINDS; k++) {
         int lw = CALLOUT_ATLAS[k].w, lh = CALLOUT_ATLAS[k].h, fy0 = CALLOUT_ATLAS[k].fy0, fy1 = CALLOUT_ATLAS[k].fy1;
         nib = (uint8_t *)calloc((size_t)lw * lh, 1);
         if (!nib) { free(img); return 0; }
         {  /* RLE 풀기 — 0, n = 0 이 n 개 */
            const uint8_t *r = CALLOUT_ATLAS[k].rle; int n = CALLOUT_ATLAS[k].n, i = 0, o = 0;
            while (i < n && o < lw * lh) {
               if (r[i] == 0) { int c = i + 1 < n ? r[i + 1] : 0; o += c; i += 2; }
               else nib[o++] = r[i++];
            }
         }
         for (int yy = 0; yy < lh + CO_SHADOW; yy++) for (int xx = 0; xx < lw + CO_SHADOW; xx++) {
            float fa = 0, oa = 0, sa = 0;
            if (yy < lh && xx < lw) { uint8_t v = nib[yy * lw + xx]; fa = (v >> 4) / 15.f; oa = (v & 15) / 15.f; }
            if (yy >= CO_SHADOW && xx >= CO_SHADOW) { uint8_t v = nib[(yy - CO_SHADOW) * lw + (xx - CO_SHADOW)]; sa = (v & 15) / 15.f * 0.55f; }
            float t = fy1 > fy0 ? (float)(yy - fy0) / (float)(fy1 - fy0) : 0.f;
            if (t < 0) t = 0;
            if (t > 1) t = 1;
            float cr = 0, cg = 0, cb = 0, ca = 0, col[3], oc[3];
            for (int c = 0; c < 3; c++) {
               int sh = 16 - 8 * c;
               col[c] = (((CO_TOP[k] >> sh) & 0xFF) * (1 - t) + ((CO_BOT[k] >> sh) & 0xFF) * t) / 255.f;
               oc[c] = ((CO_OUT[k] >> sh) & 0xFF) / 255.f;
            }
            /* 그림자 → 테두리 → 글자 순서로 덮기(곧은 알파) */
            ca = sa; cr = cg = cb = 0;
            {  float a2 = oa, na = a2 + ca * (1 - a2);
               if (na > 0) { cr = (oc[0] * a2 + cr * ca * (1 - a2)) / na; cg = (oc[1] * a2 + cg * ca * (1 - a2)) / na; cb = (oc[2] * a2 + cb * ca * (1 - a2)) / na; }
               ca = na; }
            {  float a2 = fa, na = a2 + ca * (1 - a2);
               if (na > 0) { cr = (col[0] * a2 + cr * ca * (1 - a2)) / na; cg = (col[1] * a2 + cg * ca * (1 - a2)) / na; cb = (col[2] * a2 + cb * ca * (1 - a2)) / na; }
               ca = na; }
            uint8_t *d = img + ((size_t)(y + yy) * W + (size_t)(1 + xx)) * 4;
            d[0] = (uint8_t)(cr * 255 + 0.5f); d[1] = (uint8_t)(cg * 255 + 0.5f); d[2] = (uint8_t)(cb * 255 + 0.5f); d[3] = (uint8_t)(ca * 255 + 0.5f);
         }
         free(nib);
         g_atl_rect[k][0] = 1; g_atl_rect[k][1] = y; g_atl_rect[k][2] = lw + CO_SHADOW; g_atl_rect[k][3] = lh + CO_SHADOW;
         y += lh + CO_SHADOW + 2;
      }
      g_atl = img; g_atl_w = W; g_atl_h = H;
   }
   if (rgba) *rgba = g_atl;
   if (w) *w = g_atl_w;
   if (h) *h = g_atl_h;
   if (rect) memcpy(rect, g_atl_rect, sizeof g_atl_rect);
   if (scale) *scale = CALLOUT_ATLAS_SCALE;
   return 1;
}

int callout_layout(const callout_t *c, double now, float q[4], int tr[4], float *alpha, float *flash)
{
   int rect[CO_KINDS][4], sc = 1;
   float slide = 0, w, h;
   if (!c || c->kind < 0 || c->kind >= CO_KINDS) return 0;
   if (!callout_anim(now - c->t0, &slide, alpha, flash)) return 0;
   if (!callout_atlas(NULL, NULL, NULL, rect, &sc) || sc < 1) return 0;
   w = (float)rect[c->kind][2] / sc; h = (float)rect[c->kind][3] / sc;
   q[0] = c->side == 0 ? CO_MARGIN - slide * (w + CO_MARGIN) : 160.f - CO_MARGIN - w + slide * (w + CO_MARGIN);
   q[1] = CO_Y0 + c->row * (h + 1.f);
   q[2] = w; q[3] = h;
   memcpy(tr, rect[c->kind], sizeof rect[c->kind]);
   return 1;
}

void callout_stats(int v[8]) { memcpy(v, S.stats, sizeof S.stats); }

/* 컷 하나를 게임 좌표 (cx, cy) 기준으로 훑는다 — flip = 좌우 뒤집기(강 섬광은 밀리는 쪽으로) */
#define PLAY_Y0 32
#define PLAY_Y1 135
static int fx_layers(const dfx_t *e, const hfx_cel **cel, uint16_t pal[2][3], int *flip, int *nl)
{
   int n, k = 0;
   const hfx_cel *cs = fx_cels(e->kind, &n);
   if (e->cel >= n) return 0;
   if (e->ring && e->cel < HFX_N_R) {                              /* 반격 고리가 밑 — COUNTER 노랑·주황 · PUNISHER 빨강(라벨 색과 같게) */
      cel[k] = &HFX_R[e->cel]; flip[k] = 0;
      pal[k][0] = 0xFFF; pal[k][1] = e->ring == 1 ? 0x4EF : 0x46F; pal[k][2] = e->ring == 1 ? 0x08F : 0x10B; k++;
   }
   cel[k] = &cs[e->cel]; flip[k] = (e->kind == FXK_H || e->kind == FXK_G || e->kind == FXK_K || e->kind == FXK_S) && e->dir < 0; memcpy(pal[k], e->col, sizeof pal[k]); k++;
   *nl = k;
   return 1;
}

int callout_fx_span(int *gy0, int *gy1)
{
   int lo = 1 << 30, hi = -1;
   if (atomic_exchange(&co_reset_req, 0)) reset_now();
   if (atomic_load(&fx_mode) == FXM_OFF) return 0;
   for (int i = 0; i < FX_MAX; i++) {
      const hfx_cel *cel[2]; uint16_t pal[2][3]; int flip[2], nl;
      if (!FX[i].on || !fx_layers(&FX[i], cel, pal, flip, &nl)) continue;
      for (int l = 0; l < nl; l++) {
         int y0 = FX[i].y - cel[l]->ay, y1 = y0 + cel[l]->h - 1;
         if (y0 < PLAY_Y0) y0 = PLAY_Y0;
         if (y1 > PLAY_Y1) y1 = PLAY_Y1;
         if (y1 < y0) continue;
         if (y0 < lo) lo = y0;
         if (y1 > hi) hi = y1;
      }
   }
   if (hi < lo) return 0;
   *gy0 = lo; *gy1 = hi;
   return 1;
}

void callout_fx_draw(uint8_t *rows, uint8_t *ph, int row0, int nrows, int fbw, int ox, int oy, int sub, uint32_t (*rgb)(int c12))
{
   if ((!rows && !ph) || nrows <= 0 || sub < 1 || !rgb || atomic_load(&fx_mode) == FXM_OFF) return;
   int idx[FX_MAX], n = 0;
   for (int j = 0; j < FX_MAX; j++) if (FX[j].on) idx[n++] = j;
   for (int a = 1; a < n; a++) for (int b = a; b > 0 && FX[idx[b]].seq < FX[idx[b - 1]].seq; b--) { int t = idx[b]; idx[b] = idx[b - 1]; idx[b - 1] = t; }
   for (int k = 0; k < n; k++) {                                    /* 먼저 낸 것부터(나중 것이 위) */
      const dfx_t *e = &FX[idx[k]];
      const hfx_cel *cel[2]; uint16_t pal[2][3]; int flip[2], nl;
      if (!fx_layers(e, cel, pal, flip, &nl)) continue;
      int sx = e->wx - S.cam;
      for (int l = 0; l < nl; l++) {
         uint32_t cc[3] = { rgb(pal[l][0]), rgb(pal[l][1]), rgb(pal[l][2]) };
         const hfx_cel *c = cel[l];
         for (int yy = 0; yy < c->h; yy++) {
            int gy = e->y - c->ay + yy;
            if (gy < PLAY_Y0 || gy > PLAY_Y1) continue;
            for (int xx = 0; xx < c->w; xx++) {
               int v = c->px[yy * c->w + xx];
               if (!v) continue;
               int gx = flip[l] ? sx + c->ax - xx : sx - c->ax + xx;
               if (gx < 0 || gx > 159) continue;
               for (int ty = 0; ty < sub; ty++) {
                  int r = oy + gy * sub + ty - row0;
                  if (r < 0 || r >= nrows) continue;
                  for (int tx = 0; tx < sub; tx++) {
                     int x = ox + gx * sub + tx;
                     if (x < 0 || x >= fbw) continue;
                     if (rows) memcpy(rows + ((size_t)r * fbw + x) * 4, &cc[v - 1], 4);
                     if (ph) ph[(size_t)r * fbw + x] = 0;   /* 이펙트 화소는 4배 모눈에 맞춰 그렸다(쪽지 0) */
                  }
               }
            }
         }
      }
   }
}

int callout_fx_count(void) { int n = 0; for (int i = 0; i < FX_MAX; i++) n += FX[i].on; return n; }

/* 191 코어용(같은 파일을 코어도 쓴다) — 지금 이펙트의 화소마다 put(ctx, gx, gy, c12): 게임 좌표(놀이 칸 안·화면 안만) · 12비트 0x0BGR.
   먼저 낸 것부터(나중 것이 위) — callout_fx_draw 와 같은 차례·같은 자리 */
void callout_fx_each(void (*put)(void *ctx, int gx, int gy, uint16_t c12), void *ctx)
{
   if (!put || atomic_load(&fx_mode) == FXM_OFF) return;
   int idx[FX_MAX], n = 0;
   for (int j = 0; j < FX_MAX; j++) if (FX[j].on) idx[n++] = j;
   for (int a = 1; a < n; a++) for (int b = a; b > 0 && FX[idx[b]].seq < FX[idx[b - 1]].seq; b--) { int t = idx[b]; idx[b] = idx[b - 1]; idx[b - 1] = t; }
   for (int k = 0; k < n; k++) {
      const dfx_t *e = &FX[idx[k]];
      const hfx_cel *cel[2]; uint16_t pal[2][3]; int flip[2], nl;
      if (!fx_layers(e, cel, pal, flip, &nl)) continue;
      int sx = e->wx - S.cam;
      for (int l = 0; l < nl; l++) {
         const hfx_cel *c = cel[l];
         for (int yy = 0; yy < c->h; yy++) {
            int gy = e->y - c->ay + yy;
            if (gy < PLAY_Y0 || gy > PLAY_Y1) continue;
            for (int xx = 0; xx < c->w; xx++) {
               int v = c->px[yy * c->w + xx];
               if (!v) continue;
               int gx = flip[l] ? sx + c->ax - xx : sx - c->ax + xx;
               if (gx < 0 || gx > 159) continue;
               put(ctx, gx, gy, pal[l][v - 1]);
            }
         }
      }
   }
}

int callout_flash_take(void) { int r = flash_req; flash_req = 0; return r; }

void callout_shake_enable(int on) { atomic_store(&sk_on, on ? 1 : 0); if (!on) SK.lv = 0; }

int callout_shake(float *gx, float *gy)
{
   int lv = SK.lv, t = SK.t;
   if (!lv || t < 0 || t >= sk_len(lv) || !atomic_load(&sk_on) || atomic_load(&fx_mode) == FXM_OFF) return 0;
   const int8_t *X = lv == 3 ? SK_X3 : lv == 2 ? SK_X2 : SK_X1;
   *gx = SK.dir * X[t] / 4.f;
   *gy = lv == 3 ? SK_Y3[t] / 4.f : 0.f;
   return 1;
}
