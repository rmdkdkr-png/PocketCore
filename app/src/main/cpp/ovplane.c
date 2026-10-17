/* 157 덧판(가상 오버레이 판) — 리그 인게임 메뉴(결판·쉼·서랍)를 게임 화면 위에 그린다. 계약서 docs/157_contract.md §3·§4.
 *
 * 코어 램·VRAM 은 한 바이트도 안 건드린다. Java(TileCanvas)가 NGPC 규칙(8×8·2bpp·칸당 팔레트 하나·색 0 투명)으로 만든 타일·맵·팔레트를
 * 받아 두었다가, native.c gl_draw 가 잔상 뒤 그림(src)을 넘기면 ov_compose 가 같은 크기의 따로 버퍼에 합성해 돌려준다.
 * g_fb·nativeFrameBuffer 는 날것 그대로(스크린샷·썸네일에 메뉴가 안 들어감).
 *
 * 상태 = «바탕 상태»(Java setter 로 정한 것) + «대본»(60Hz 시각 t 의 값). 그릴 때마다 바탕에서 다시 계산한다(상태 없는 평가, §4.3).
 *  - 앞 단계: op 를 뒤에서부터 훑어 아직 시작 안 한 «오래 남는 op» 의 시작 전 값(가장 먼저 시작할 op 가 마지막에 남음)
 *  - 진행·끝: 앞에서부터 시작한 오래 남는 op 의 값(나중에 시작한 것이 이김)
 *  - 잠깐 op(STAMP·PALCYCLE·FLASH·BOB): 도는 동안만 위에 덧씌움
 *  - setter 는 먼저 끝난 유한 op 를 바탕에 굽고 뺀다 · 새 대본은 옛 대본의 유한 op 를 다 끝 상태로 굽고(늘 도는 op 는 버림) 단다.
 * 시계: ov_tick 에 들어온 단조 시각 → t = floor((now − start)·60 + ε) — 화면 120Hz 여도 같은 박자(같은 t 가 두 vsync).
 * 합성 순서(게임 좌표 160×152, 그림은 sub×sub 덩어리): 바탕 줄 밀기 → 바탕 칸 0→3(DIM·FILL·FLASH) → L 층 → W 층 → 일섬 칼선 → 흔들림.
 *
 * 스레드: ov_status 만 아무 스레드. 나머지는 GL 스레드(LeagueMenu)지만 그래도 ov 자물쇠 하나로 감싼다(합성·tick·상태와 겹침 방지).
 *         ov_hold()·ov_in_block() 은 잠금 없이 int 하나(pace·입력 콜백 — 코어 잠금 안에서 불려도 자물쇠 순서가 안 꼬인다).
 * 자물쇠 순서: 코어 잠금(native.c) → ov 잠금. ovplane 은 native.c 를 부르지 않는다.
 */
#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include "ovplane.h"

/* ── 계약 셈 ─────────────────────────────────────────────────────────── */

static int64_t floor_div16(int64_t v) { return v >= 0 ? (v >> 16) : -((-v + 65535) >> 16); }
static int64_t floor_divi(int64_t a, int64_t b) { int64_t q = a / b; if ((a % b) != 0 && ((a < 0) != (b < 0))) q--; return q; }

/* 감속 보간 — t < t0 → from · t ≥ t0+dur(또는 dur ≤ 0) → to · 그 사이 e(u) 0..65536 */
int ov_ease(int ease, int t, int t0, int dur, int from, int to)
{
   if (dur <= 0 || t >= t0 + dur) return to;
   if (t <= t0) return from;
   int64_t u = ((int64_t)(t - t0) << 16) / dur;          /* 0..65535 */
   int64_t e;
   switch (ease) {
   case OV_EASE_OUT:  e = 65536 - (((65536 - u) * (65536 - u)) >> 16); break;
   case OV_EASE_IN:   e = (u * u) >> 16; break;
   case OV_EASE_STEP: e = 0; break;
   default:           e = u; break;
   }
   return from + (int)floor_div16((int64_t)(to - from) * e);
}

/* 12비트 0x0BGR → 코어와 같은 색(니블×17 → 565 → 8888). 돌려줌 0xAABBGGRR(리틀 엔디언 메모리 R,G,B,A) */
uint32_t ov_rgb(int c12)
{
   unsigned r8 = (unsigned)(c12 & 15) * 17, g8 = (unsigned)((c12 >> 4) & 15) * 17, b8 = (unsigned)((c12 >> 8) & 15) * 17;
   unsigned r5 = r8 >> 3, g6 = g8 >> 2, b5 = b8 >> 3;
   unsigned r = (r5 << 3) | (r5 >> 2), g = (g6 << 2) | (g6 >> 4), b = (b5 << 3) | (b5 >> 2);
   return 0xFF000000u | (b << 16) | (g << 8) | r;
}

/* ── 안쪽 표현 ─────────────────────────────────────────────────────────── */

#define BLACK 0xFF000000u
#define TEPS  1e-6                  /* 시계 ε — 계약 1e-9 보다 넉넉히(가동 몇 주 된 단조 시계의 double 오차가 1e-9 프레임을 넘는다). 16.7ns 앞당김일 뿐 */
#define IM_EMPTY 0xFFFFFFFFu        /* 안쪽 맵 칸: bit0-10 절대 타일 · bit11-14 팔레트 · bit15 V · bit16 H */
#define IM_TILE  0x7FFu
#define IM_PSH   11
#define IM_V     (1u << 15)
#define IM_H     (1u << 16)

typedef struct { int c0, r0, c1, r1, dx, dy, vis; } ovpane_t;          /* c1 < c0 = 안 씀 */
typedef struct { int x, y, w, h, pal, col; } ovfill_t;                 /* w ≤ 0 = 없음 */
typedef struct { int x, y, tile, pal, flags; } ovquad_t;
typedef struct { int on, c0, r0, c1, r1, dir, slope, front, epal, ecol; } ovwipe_t;
typedef struct { int mode, y0, y1, arg, color; } ovbslot_t;

typedef struct {
   ovpane_t   pane[OV_PANES];
   ovfill_t   fill[OV_FILLS];
   ovquad_t   quad[OV_QUADS];
   uint16_t pal[OV_PALS][4];
   int      sx, sy, clip0, clip1;
   int16_t  rowx[OV_GAME_H];
   ovwipe_t   wipe;
} ovlstate_t;

typedef struct {
   ovlstate_t l[OV_NLAYERS];
   ovbslot_t  base[OV_BASE_SLOTS];
   int16_t  bgx[OV_GAME_H];        /* 바탕 줄 밀기(ROWSHIFT 층 2) */
} ovstate_t;

typedef struct { int layer, c0, r0, w, h, sc, sr; } ovcnt_t;          /* COUNT 한 번: dst (c0,r0) w×h ← src (sc,sr) */
typedef struct {
   ovstate_t s;
   int ncnt; ovcnt_t cnt[OV_MAX_OPS];
   int shake;                      /* 흔들림 dy(게임 px) */
   int ff, fy, fc, fd;             /* 일섬: 프레임(−1 없음) · 줄 · 가운데 색 · 테 색 */
   int qdx[OV_NLAYERS][OV_QUADS], qdy[OV_NLAYERS][OV_QUADS];   /* BOB */
} oveff_t;

typedef struct { int op, t0, dur, a, b, c, d; } ovop_t;

static uint8_t  tpx_w[OV_W_TILES][64], tpx_l[OV_L_TILES][64];        /* 타일 픽셀(칠 0..3) — 올릴 때 풀어 둠 */
static uint32_t map_w[OV_W_COLS * OV_W_ROWS], map_l[OV_L_COLS * OV_L_ROWS];
static uint32_t emap_w[OV_W_COLS * OV_W_ROWS], emap_l[OV_L_COLS * OV_L_ROWS];   /* COUNT 적용한 맵(그릴 때만) */
typedef struct { int ntiles, cols, rows; uint8_t (*px)[64]; uint32_t *map, *emap; } ovbank_t;
static const ovbank_t BK[OV_NLAYERS] = {
   { OV_W_TILES, OV_W_COLS, OV_W_ROWS, tpx_w, map_w, emap_w },
   { OV_L_TILES, OV_L_COLS, OV_L_ROWS, tpx_l, map_l, emap_l },
};

static pthread_mutex_t g_mx = PTHREAD_MUTEX_INITIALIZER;
#define LOCK()   pthread_mutex_lock(&g_mx)
#define UNLOCK() pthread_mutex_unlock(&g_mx)

static int       g_init;
static ovstate_t g_base;                       /* 바탕 상태 */
static ovop_t      g_ops[OV_MAX_OPS];
static int       g_nops;
static int       g_cue_on, g_armed, g_pending, g_started, g_skip_start, g_skip_pending;
static double    g_t0s;                        /* 대본 시작 시각(초) */
static int       g_toff, g_t;                  /* t = floor((now − g_t0s)·60) + g_toff */
static int       g_cue_end = -1, g_cue_id;
static int       g_visible, g_shown, g_dirty = 1;
static volatile int g_hold, g_block, g_inblk;
static int       g_starts, g_composites, g_latch;
static double    g_last_now = -1.0;            /* 지난 ov_tick 시각 — 긴 틈(앱이 내려감)이면 대본 시계를 멈춘다 */
#define OV_GAP_S 0.25                          /* pace_steps 의 «긴 틈»과 같은 문턱 */
static int       g_rect_ok, g_rx, g_ry, g_sub = 1, g_fbw, g_fbh, g_vx, g_vy, g_vw, g_vh;
static const uint8_t *g_ram;
static size_t    g_ramn;
static uint32_t *g_out;                         /* 합성 결과(src 와 같은 크기) */
static size_t    g_outcap;
static unsigned  g_outw, g_outh;
static int       g_out_valid;
static const uint8_t *g_last_src;
static uint32_t  g_ovl[OV_GAME_H][OV_GAME_W];   /* 층 그림(게임 좌표, 0 = 그림 없음) */
static int8_t    g_pidx[OV_L_COLS * OV_L_ROWS]; /* 칸 → 창유리(가장 작은 번호), −1 없음 */
static uint8_t   g_dim5[5][256], g_dim6[5][256];
static const int SIN16[16] = { 0, 98, 181, 236, 256, 236, 181, 98, 0, -98, -181, -236, -256, -236, -181, -98 };

static void lstate_reset(ovlstate_t *L)
{
   memset(L, 0, sizeof *L);
   for (int i = 0; i < OV_PANES; i++) { L->pane[i].c0 = 0; L->pane[i].c1 = -1; }
   L->clip0 = 0; L->clip1 = OV_GAME_H - 1;
}
static void layer_reset(int ly)
{
   const ovbank_t *B = &BK[ly];
   memset(B->px, 0, (size_t)B->ntiles * 64);
   for (int i = 0; i < B->cols * B->rows; i++) B->map[i] = IM_EMPTY;
   lstate_reset(&g_base.l[ly]);
}
static void base_reset(void)
{
   memset(g_base.base, 0, sizeof g_base.base);
   memset(g_base.bgx, 0, sizeof g_base.bgx);
}
static void update_inblk(void) { g_inblk = g_block | ((g_visible || g_hold) ? 0xFFF : 0); }
static void ensure_init(void)
{
   if (g_init) return;
   g_init = 1;
   layer_reset(OV_W); layer_reset(OV_L); base_reset();
   for (int k = 0; k <= 4; k++)
      for (int c = 0; c < 256; c++) {
         int n = (c + 8) / 17; if (n > 15) n = 15;
         unsigned v8 = (unsigned)(((n * k) >> 2) * 17), r5 = v8 >> 3, g6 = v8 >> 2;
         g_dim5[k][c] = (uint8_t)((r5 << 3) | (r5 >> 2));
         g_dim6[k][c] = (uint8_t)((g6 << 2) | (g6 >> 4));
      }
}

/* ── op 분류·검사 ─────────────────────────────────────────────────────── */

static int op_transient(int op) { return op == OV_OP_STAMP || op == OV_OP_PALCYCLE || op == OV_OP_FLASH || op == OV_OP_BOB; }
/* 대상: 0 W · 1 L · 2 바탕 */
static int op_target(const ovop_t *o)
{
   switch (o->op) {
   case OV_OP_DIM: case OV_OP_BASE: case OV_OP_FLASH: return 2;
   case OV_OP_ROWSHIFT: return (o->a & 15) == OV_BASE_LAYER ? 2 : (o->a & 15);
   default: return o->a & 15;
   }
}
/* 효과가 있는 마지막 프레임 다음(유한 op) — STAMP 번쩍이 흔들림보다 길 수 있다 */
static int op_stop(const ovop_t *o)
{
   int d = o->dur;
   if (o->op == OV_OP_STAMP && o->b > d) d = o->b;
   return o->t0 + d;
}
static int count_ok(const ovop_t *o, int ly)
{
   const ovbank_t *B = &BK[ly];
   int n = (o->a >> 16) & 0xFF, dc0 = o->b & 0xFF, dr0 = (o->b >> 8) & 0xFF, w = (o->b >> 16) & 0xFF, h = (o->b >> 24) & 0xFF;
   int sc = o->c & 0xFF, sr = (o->c >> 8) & 0xFF, stc = (int8_t)(o->d & 0xFF), str = (int8_t)((o->d >> 8) & 0xFF);
   if (n < 1 || w < 1 || h < 1 || dc0 + w > B->cols || dr0 + h > B->rows) return 0;
   for (int k = 0; k < n; k += (n > 1 ? n - 1 : 1)) {                /* 처음·끝(곧은 줄이라 둘이면 됨) */
      int c = sc + k * stc, r = sr + k * str;
      if (c < 0 || r < 0 || c + w > B->cols || r + h > B->rows) return 0;
   }
   return 1;
}
static int op_valid(const ovop_t *o)
{
   int a = o->a, ly = a & 15;
   int lay01 = ly == OV_W || ly == OV_L;
   switch (o->op) {
   case OV_OP_DIM:      return ((a >> 4) & 15) < OV_BASE_SLOTS && o->c >= 0 && o->c <= 4 && o->d >= 0 && o->d <= 4;
   case OV_OP_SLIDE:    return lay01;
   case OV_OP_CLIP:     return lay01 && ((a >> 4) & 15) <= 1;
   case OV_OP_STAMP:    return lay01 && o->b >= 0;
   case OV_OP_COUNT:    return lay01 && count_ok(o, ly);
   case OV_OP_GAUGE:    return lay01 && ((a >> 4) & 15) < OV_FILLS && ((a >> 10) & 3) >= 1;
   case OV_OP_PALCYCLE: return lay01 && ((a >> 10) & 3) >= 1 && o->d >= 1;
   case OV_OP_WIPE: {
      const ovbank_t *B;
      int c0 = o->b & 0xFF, r0 = (o->b >> 8) & 0xFF, c1 = (o->b >> 16) & 0xFF, r1 = (o->b >> 24) & 0xFF;
      if (!lay01) return 0;
      B = &BK[ly];
      return c0 <= c1 && r0 <= r1 && c1 < B->cols && r1 < B->rows && o->c >= 0 && o->c <= 4;
   }
   case OV_OP_SCROLLY:  return lay01;
   case OV_OP_ROWSHIFT: return ly <= OV_BASE_LAYER && ((a >> 10) & 3) <= 2;
   case OV_OP_FLASH:    return 1;
   case OV_OP_SHOW:     return lay01 && (o->b == 0 || o->b == 1);
   case OV_OP_PAL:      return lay01 && o->b >= -1 && o->c >= -1 && o->d >= -1;
   case OV_OP_BASE:     return ((a >> 4) & 15) < OV_BASE_SLOTS && ((a >> 10) & 15) <= OV_BASE_SHIFT;
   case OV_OP_BOB:      return lay01;
   default:             return 0;
   }
}

/* ── 평가 ──────────────────────────────────────────────────────────────── */

/* 오래 남는 op 하나를 상태 s 에 — pre = 시작 전 값 · te = 평가 시각 · tw = 물결 위상 시각.
   cnt 가 NULL 이면(굽기) COUNT 는 바탕 맵에 바로 쓴다 */
static void apply_persist(ovstate_t *s, oveff_t *E, const ovop_t *o, int te, int tw, int pre)
{
   int a = o->a, ly = a & 15, ez = (a >> 8) & 3;
#define EV(f, t) (pre ? (f) : ov_ease(ez, te, o->t0, o->dur, (f), (t)))
   switch (o->op) {
   case OV_OP_DIM: {
      ovbslot_t *b = &s->base[(a >> 4) & 15];
      b->mode = OV_BASE_DIM; b->y0 = o->b & 0xFF; b->y1 = (o->b >> 8) & 0xFF;
      b->arg = pre ? o->c : ov_ease(OV_EASE_LINEAR, te, o->t0, o->dur, o->c, o->d); b->color = 0;
      break;
   }
   case OV_OP_BASE: {
      ovbslot_t *b = &s->base[(a >> 4) & 15];
      b->mode = (a >> 10) & 15; b->y0 = o->b & 0xFF; b->y1 = (o->b >> 8) & 0xFF;
      b->arg = EV(o->c, o->d); b->color = (a >> 16) & 0xFFF;
      break;
   }
   case OV_OP_SLIDE: {
      ovpane_t *p = &s->l[ly].pane[(a >> 4) & 15];
      int v = EV(o->b, o->c);
      if ((a >> 10) & 1) p->dy = v; else p->dx = v;
      break;
   }
   case OV_OP_CLIP: {
      int v = EV(o->b, o->c);
      if ((a >> 4) & 15) s->l[ly].clip1 = v; else s->l[ly].clip0 = v;
      break;
   }
   case OV_OP_COUNT: {
      const ovbank_t *B = &BK[ly];
      int n = (a >> 16) & 0xFF, u = EV(0, 65536);
      int k = (int)(((int64_t)u * n) >> 16); if (k > n - 1) k = n - 1; if (k < 0) k = 0;
      ovcnt_t c;
      c.layer = ly; c.c0 = o->b & 0xFF; c.r0 = (o->b >> 8) & 0xFF; c.w = (o->b >> 16) & 0xFF; c.h = (o->b >> 24) & 0xFF;
      c.sc = (o->c & 0xFF) + k * (int8_t)(o->d & 0xFF); c.sr = ((o->c >> 8) & 0xFF) + k * (int8_t)((o->d >> 8) & 0xFF);
      if (E) { if (E->ncnt < OV_MAX_OPS) E->cnt[E->ncnt++] = c; }
      else {                                   /* 굽기 — src 를 먼저 떠 둔다(겹쳐도 바르게) */
         static uint32_t tmp[OV_L_COLS * OV_L_ROWS];
         for (int r = 0; r < c.h; r++) for (int q = 0; q < c.w; q++) tmp[r * c.w + q] = B->map[(c.sr + r) * B->cols + c.sc + q];
         for (int r = 0; r < c.h; r++) for (int q = 0; q < c.w; q++) B->map[(c.r0 + r) * B->cols + c.c0 + q] = tmp[r * c.w + q];
      }
      break;
   }
   case OV_OP_GAUGE: {
      ovfill_t *f = &s->l[ly].fill[(a >> 4) & 15];
      f->x = o->b & 0xFFF; f->y = (o->b >> 12) & 0xFFF; f->h = (o->b >> 24) & 0xFF;
      f->w = EV(o->c, o->d); f->pal = (a >> 12) & 15; f->col = (a >> 10) & 3;
      break;
   }
   case OV_OP_WIPE: {
      ovwipe_t *w = &s->l[ly].wipe;
      int ended = !pre && (o->dur <= 0 || te >= o->t0 + o->dur);
      if (ended) { w->on = 0; break; }
      w->on = 1; w->c0 = o->b & 0xFF; w->r0 = (o->b >> 8) & 0xFF; w->c1 = (o->b >> 16) & 0xFF; w->r1 = (o->b >> 24) & 0xFF;
      w->dir = (a >> 4) & 1; w->slope = o->c; w->epal = (a >> 12) & 15; w->ecol = (a >> 10) & 3;
      {
         int W = (w->c1 - w->c0 + 1) * 8, H = (w->r1 - w->r0 + 1) * 8;
         w->front = EV(-1, W + H * w->slope + 2);
      }
      break;
   }
   case OV_OP_SCROLLY: s->l[ly].sy = EV(o->b, o->c); break;
   case OV_OP_ROWSHIFT: {
      int16_t *tab = ly == OV_BASE_LAYER ? s->bgx : s->l[ly].rowx;
      int y0 = o->b & 0xFF, y1 = (o->b >> 8) & 0xFF, shape = (a >> 10) & 3, v = EV(o->c, o->d);
      if (y1 > OV_GAME_H - 1) y1 = OV_GAME_H - 1;
      for (int i = y0; i <= y1; i++) {
         int x = v;
         if (shape == 1) x = (((i - y0) >> 1) & 1) ? -v : v;
         else if (shape == 2) x = (int)floor_divi((int64_t)v * SIN16[(i - y0 + tw) & 15], 256);
         tab[i] = (int16_t)x;
      }
      break;
   }
   case OV_OP_SHOW: s->l[ly].pane[(a >> 4) & 15].vis = pre ? !o->b : o->b; break;
   case OV_OP_PAL:
      if (!pre) {
         uint16_t *p = s->l[ly].pal[(a >> 4) & 15];
         if (o->b >= 0) p[1] = (uint16_t)(o->b & 0xFFF);
         if (o->c >= 0) p[2] = (uint16_t)(o->c & 0xFFF);
         if (o->d >= 0) p[3] = (uint16_t)(o->d & 0xFFF);
      }
      break;
   default: break;
   }
#undef EV
}

static int nib_mix(int A, int B, int64_t w)
{
   int out = 0;
   for (int sh = 0; sh < 12; sh += 4) {
      int na = (A >> sh) & 15, nb = (B >> sh) & 15;
      int n = na + (int)floor_div16((int64_t)(nb - na) * w);
      if (n < 0) n = 0; else if (n > 15) n = 15;
      out |= n << sh;
   }
   return out;
}

/* 잠깐 op 의 값(f = t − t0 ≥ 0) — 합성(apply_transient)과 «다시 합성할까»(cue_changes)가 같은 셈을 쓴다 */
static int palcycle_col(const ovop_t *o, int f)
{
   int P = o->d, A = o->b & 0xFFF, B = o->c & 0xFFF;
   if ((o->a >> 12) & 1) return ((f / (P / 2 > 1 ? P / 2 : 1)) % 2 == 0) ? A : B;
   int p = f % P, tri = p < P / 2 ? p : P - p;
   return nib_mix(A, B, (int64_t)tri * 2 * 65536 / P);
}
static int bob_v(const ovop_t *o, int f)
{
   int half = o->c / 2 > 1 ? o->c / 2 : 1;
   return ((f / half) % 2) ? o->b : 0;
}
/* 늘 도는(dur < 0) 잠깐 op 가 f 에 그리는 것의 열쇠 — 열쇠가 같으면 그림도 같다(쉬는 메뉴가 vsync 마다 다시 합성·1.5MB 올림을 안 하게) */
static int ambient_key(const ovop_t *o, int f)
{
   switch (o->op) {
   case OV_OP_PALCYCLE: return palcycle_col(o, f);
   case OV_OP_BOB:      return bob_v(o, f);
   case OV_OP_STAMP:    return (f < o->b ? 1 : 0) | ((o->c && (f & 1)) ? 2 : 0);   /* 번쩍 · 흔들림 방향 */
   case OV_OP_FLASH:    return f < 10 ? f : 10;                                       /* f ≥ 10 은 아무것도 안 그림 */
   default:             return 0;
   }
}

static void apply_transient(oveff_t *E, const ovop_t *o, int t)
{
   int a = o->a, ly = a & 15, f = t - o->t0;
   switch (o->op) {
   case OV_OP_STAMP:
      if (f < o->b) { uint16_t *p = E->s.l[ly].pal[(a >> 4) & 15]; p[1] = p[2] = p[3] = (uint16_t)(o->d & 0xFFF); }
      if (o->dur < 0 || f < o->dur) E->shake = (f & 1) ? -o->c : o->c;
      break;
   case OV_OP_PALCYCLE:
      E->s.l[ly].pal[(a >> 4) & 15][(a >> 10) & 3] = (uint16_t)palcycle_col(o, f);
      break;
   case OV_OP_FLASH: E->ff = f; E->fy = o->b; E->fc = o->c & 0xFFF; E->fd = o->d & 0xFFF; break;
   case OV_OP_BOB: {
      int v = bob_v(o, f), q = (a >> 4) & 15;
      if ((a >> 10) & 1) E->qdy[ly][q] = v; else E->qdx[ly][q] = v;
      break;
   }
   default: break;
   }
}

/* 지금 시각의 유효 상태 */
static void eval_locked(oveff_t *E)
{
   memcpy(&E->s, &g_base, sizeof g_base);
   E->ncnt = 0; E->shake = 0; E->ff = -1; E->fy = E->fc = E->fd = 0;
   memset(E->qdx, 0, sizeof E->qdx); memset(E->qdy, 0, sizeof E->qdy);
   if (!g_cue_on) return;
   int st = g_started, t = g_t, tw = st ? t : 0;
   for (int i = g_nops - 1; i >= 0; i--) {
      const ovop_t *o = &g_ops[i];
      if (!op_transient(o->op) && (!st || t < o->t0)) apply_persist(&E->s, E, o, o->t0 - 1, tw, 1);
   }
   if (!st) return;
   for (int i = 0; i < g_nops; i++) {
      const ovop_t *o = &g_ops[i];
      if (!op_transient(o->op) && t >= o->t0) apply_persist(&E->s, E, o, t, t, 0);
   }
   for (int i = 0; i < g_nops; i++) {
      const ovop_t *o = &g_ops[i];
      if (op_transient(o->op) && t >= o->t0 && (o->dur < 0 || t < op_stop(o))) apply_transient(E, o, t);
   }
}

/* 끝난 유한 op 를 바탕에 굽고 뺀다(setter 앞) */
static void bake_ended_locked(void)
{
   if (!g_cue_on || !g_started) return;
   int w = 0;
   for (int i = 0; i < g_nops; i++) {
      ovop_t o = g_ops[i];
      if (o.dur >= 0 && g_t >= op_stop(&o)) {
         if (!op_transient(o.op)) apply_persist(&g_base, NULL, &o, o.t0 + o.dur, o.t0 + o.dur, 0);
         continue;
      }
      g_ops[w++] = o;
   }
   g_nops = w;
}
/* 대본 전부를 끝 상태로 굽고 뗀다(새 대본 앞 — 건너뛴 것처럼) */
static void bake_all_locked(void)
{
   for (int i = 0; i < g_nops; i++) {
      const ovop_t *o = &g_ops[i];
      if (o->dur >= 0 && !op_transient(o->op)) apply_persist(&g_base, NULL, o, o->t0 + o->dur, o->t0 + o->dur, 0);
   }
   g_nops = 0;
}
static void cue_drop_locked(void)
{
   g_nops = 0; g_cue_on = g_armed = g_pending = g_started = g_skip_start = g_skip_pending = 0;
   g_cue_end = -1; g_cue_id = 0; g_toff = g_t = 0;
}
/* 대상(0 W · 1 L · 2 바탕)을 쥔 op 를 굽지 않고 뺀다(ov_clear 의 층 지우기) */
static void drop_target_locked(int tg)
{
   int w = 0;
   for (int i = 0; i < g_nops; i++) if (op_target(&g_ops[i]) != tg) g_ops[w++] = g_ops[i];
   g_nops = w;
}
/* 맵 칸 사각형을 새로 쓰기 전 — 이미 시작한 COUNT·GAUGE 가 그 칸을 쥐고 있으면 뺀다(새 대본이 와서 끝 상태를 굽으면 새 칸을 덮어씀).
   바탕 채움(GAUGE 를 구운 것)도 왼위 칸이 그 사각형 안이면 지운다 — 장면을 갈아 끼우면 옛 막대가 남지 않게. 아직 시작 안 한 op 는 그대로 */
static void map_write_guard_locked(int ly, int c0, int r0, int c1, int r1)
{
   int w = 0;
   for (int i = 0; i < g_nops; i++) {
      const ovop_t *o = &g_ops[i];
      int drop = 0;
      if (g_cue_on && g_started && g_t >= o->t0 && (o->a & 15) == ly) {
         if (o->op == OV_OP_COUNT) {
            int dc0 = o->b & 0xFF, dr0 = (o->b >> 8) & 0xFF, dc1 = dc0 + ((o->b >> 16) & 0xFF) - 1, dr1 = dr0 + ((o->b >> 24) & 0xFF) - 1;
            drop = !(dc1 < c0 || dc0 > c1 || dr1 < r0 || dr0 > r1);
         } else if (o->op == OV_OP_GAUGE) {
            int cx = (o->b & 0xFFF) >> 3, cy = ((o->b >> 12) & 0xFFF) >> 3;
            drop = cx >= c0 && cx <= c1 && cy >= r0 && cy <= r1;
         }
      }
      if (!drop) g_ops[w++] = *o;
   }
   g_nops = w;
   for (int i = 0; i < OV_FILLS; i++) {
      ovfill_t *f = &g_base.l[ly].fill[i];
      int cx = f->x >> 3, cy = f->y >> 3;
      if (f->w > 0 && cx >= c0 && cx <= c1 && cy >= r0 && cy <= r1) f->w = 0;
   }
}
/* 시각 tp → t 사이에 유효 상태가 바뀌었나(다시 합성할지). 늘 도는 잠깐 op(고른 줄 일렁임·단도 까딱)는 값이 실제로 바뀔 때만 —
   예전엔 늘 도는 op 가 있으면 t 가 바뀔 때마다 «바뀜»이라 쉬는 메뉴가 60Hz 로 4배 그림 전체를 다시 합성·올렸다.
   물결 줄 밀기(ROWSHIFT 모양 2)는 위상이 t 로 돌므로(폭이 0 이 아니면) 늘 바뀜으로 본다 */
static int cue_changes(int tp, int t)
{
   for (int i = 0; i < g_nops; i++) {
      const ovop_t *o = &g_ops[i];
      if (o->op == OV_OP_ROWSHIFT && ((o->a >> 10) & 3) == 2 && (o->c || o->d)) return 1;
      if (o->dur < 0) {
         if (t < o->t0) continue;                                  /* 아직 시작 전(앞 단계 값 그대로) */
         if (tp < o->t0) return 1;                                  /* 이번에 시작 */
         if (op_transient(o->op) && ambient_key(o, tp - o->t0) != ambient_key(o, t - o->t0)) return 1;
         continue;
      }
      if (o->t0 <= t && op_stop(o) > tp) return 1;
   }
   return 0;
}

/* ── 올리기(setter) ────────────────────────────────────────────────────── */

static int map_ok(int ly, const uint16_t *cells, int col0, int row0, int cols, int rows, int tile_base)
{
   const ovbank_t *B = &BK[ly];
   if (cols <= 0 || rows <= 0 || col0 < 0 || row0 < 0 || col0 + cols > B->cols || row0 + rows > B->rows) return 0;
   if (tile_base < 0) return 0;
   for (int i = 0; i < cols * rows; i++) {
      uint16_t e = cells[i];
      if (e != OV_EMPTY && tile_base + (e & OV_M_TILE) >= B->ntiles) return 0;
   }
   return 1;
}
static void map_put(int ly, const uint16_t *cells, int col0, int row0, int cols, int rows, int tile_base)
{
   const ovbank_t *B = &BK[ly];
   for (int r = 0; r < rows; r++)
      for (int c = 0; c < cols; c++) {
         uint16_t e = cells[r * cols + c];
         uint32_t v = IM_EMPTY;
         if (e != OV_EMPTY)
            v = (uint32_t)(tile_base + (e & OV_M_TILE)) | ((uint32_t)((e >> OV_M_PAL_SHIFT) & 15) << IM_PSH)
              | ((e & OV_M_VFLIP) ? IM_V : 0) | ((e & OV_M_HFLIP) ? IM_H : 0);
         B->map[(row0 + r) * B->cols + col0 + c] = v;
      }
}

int ov_layer(int layer, const uint8_t *tiles, int ntiles, int tile_base,
             const uint16_t *map, int cols, int rows, int col0, int row0,
             const uint16_t *pals, int npals, int pal_base)
{
   if (layer != OV_W && layer != OV_L) return 0;
   const ovbank_t *B = &BK[layer];
   if (tiles && (ntiles < 0 || ntiles > OV_CHUNK_MAX || tile_base < 0 || tile_base + ntiles > B->ntiles)) return 0;
   if (pals && (npals < 0 || pal_base < 0 || pal_base + npals > OV_PALS)) return 0;
   if (map && !map_ok(layer, map, col0, row0, cols, rows, tile_base)) return 0;
   LOCK();
   ensure_init();
   bake_ended_locked();
   if (tiles)
      for (int k = 0; k < ntiles; k++)
         for (int y = 0; y < 8; y++) {
            unsigned v = (unsigned)tiles[k * 16 + 2 * y] | ((unsigned)tiles[k * 16 + 2 * y + 1] << 8);
            for (int x = 0; x < 8; x++) B->px[tile_base + k][y * 8 + x] = (uint8_t)((v >> (14 - 2 * x)) & 3);
         }
   if (map) { map_write_guard_locked(layer, col0, row0, col0 + cols - 1, row0 + rows - 1); map_put(layer, map, col0, row0, cols, rows, tile_base); }
   if (pals)
      for (int p = 0; p < npals; p++)
         for (int k = 0; k < 4; k++) g_base.l[layer].pal[pal_base + p][k] = (uint16_t)(pals[p * 4 + k] & 0xFFF);
   g_dirty = 1;
   UNLOCK();
   return 1;
}

int ov_cells(int layer, const uint16_t *cells, int col0, int row0, int cols, int rows, int tile_base)
{
   if ((layer != OV_W && layer != OV_L) || !cells || !map_ok(layer, cells, col0, row0, cols, rows, tile_base)) return 0;
   LOCK();
   ensure_init();
   bake_ended_locked();
   map_write_guard_locked(layer, col0, row0, col0 + cols - 1, row0 + rows - 1);
   map_put(layer, cells, col0, row0, cols, rows, tile_base);
   g_dirty = 1;
   UNLOCK();
   return 1;
}

int ov_recolor(int layer, int col0, int row0, int cols, int rows, int pal)
{
   if (layer != OV_W && layer != OV_L) return 0;
   const ovbank_t *B = &BK[layer];
   if (cols <= 0 || rows <= 0 || col0 < 0 || row0 < 0 || col0 + cols > B->cols || row0 + rows > B->rows || pal < 0 || pal >= OV_PALS) return 0;
   LOCK();
   ensure_init();
   bake_ended_locked();
   for (int r = row0; r < row0 + rows; r++)
      for (int c = col0; c < col0 + cols; c++) {
         uint32_t *e = &B->map[r * B->cols + c];
         if (*e != IM_EMPTY) *e = (*e & ~(15u << IM_PSH)) | ((uint32_t)pal << IM_PSH);
      }
   g_dirty = 1;
   UNLOCK();
   return 1;
}

int ov_pal(int layer, int pal, int c1, int c2, int c3)
{
   if ((layer != OV_W && layer != OV_L) || pal < 0 || pal >= OV_PALS) return 0;
   LOCK();
   ensure_init();
   bake_ended_locked();
   uint16_t *p = g_base.l[layer].pal[pal];
   if (c1 >= 0) p[1] = (uint16_t)(c1 & 0xFFF);
   if (c2 >= 0) p[2] = (uint16_t)(c2 & 0xFFF);
   if (c3 >= 0) p[3] = (uint16_t)(c3 & 0xFFF);
   g_dirty = 1;
   UNLOCK();
   return 1;
}

int ov_quad(int layer, int idx, int x, int y, int tile, int pal, int flags)
{
   if ((layer != OV_W && layer != OV_L) || idx < 0 || idx >= OV_QUADS || pal < 0 || pal >= OV_PALS) return 0;
   if ((flags & OV_Q_SHOW) && (tile < 0 || tile + ((flags & OV_Q_16) ? 3 : 0) >= BK[layer].ntiles)) return 0;
   LOCK();
   ensure_init();
   bake_ended_locked();
   ovquad_t *q = &g_base.l[layer].quad[idx];
   q->x = x; q->y = y; q->tile = tile; q->pal = pal; q->flags = flags;
   g_dirty = 1;
   UNLOCK();
   return 1;
}

int ov_pane(int layer, int idx, int c0, int r0, int c1, int r1, int dx, int dy, int visible)
{
   if ((layer != OV_W && layer != OV_L) || idx < 0 || idx >= OV_PANES) return 0;
   const ovbank_t *B = &BK[layer];
   if (c1 >= c0 && (c0 < 0 || r0 < 0 || r0 > r1 || c1 >= B->cols || r1 >= B->rows)) return 0;
   LOCK();
   ensure_init();
   bake_ended_locked();
   ovpane_t *p = &g_base.l[layer].pane[idx];
   if (c1 < c0) { memset(p, 0, sizeof *p); p->c1 = -1; }
   else { p->c0 = c0; p->r0 = r0; p->c1 = c1; p->r1 = r1; p->dx = dx; p->dy = dy; p->vis = visible ? 1 : 0; }
   g_dirty = 1;
   UNLOCK();
   return 1;
}

int ov_scroll(int layer, int x, int y, int clip_y0, int clip_y1)
{
   if (layer != OV_W && layer != OV_L) return 0;
   LOCK();
   ensure_init();
   bake_ended_locked();
   ovlstate_t *L = &g_base.l[layer];
   L->sx = x; L->sy = y; L->clip0 = clip_y0; L->clip1 = clip_y1;
   g_dirty = 1;
   UNLOCK();
   return 1;
}

int ov_base(int slot, int mode, int y0, int y1, int arg, int color)
{
   if (slot < 0 || slot >= OV_BASE_SLOTS || mode < OV_BASE_NONE || mode > OV_BASE_SHIFT) return 0;
   LOCK();
   ensure_init();
   bake_ended_locked();
   ovbslot_t *b = &g_base.base[slot];
   b->mode = mode; b->y0 = y0; b->y1 = y1; b->arg = arg; b->color = color & 0xFFF;
   g_dirty = 1;
   UNLOCK();
   return 1;
}

int ov_cue(const int32_t *ops, int nops, int flags)
{
   if (!ops && (flags & OV_SKIP_TO_END)) {                 /* 돌던 대본 건너뛰기 */
      int r = 0;
      LOCK();
      ensure_init();
      if (g_cue_on && g_started) {
         if (g_t < g_cue_end) g_t = g_cue_end;
         g_skip_pending = 1; g_dirty = 1; r = 1;
      } else if (g_cue_on && g_pending) { g_skip_start = 1; r = 1; }
      UNLOCK();
      return r;
   }
   int arm = (flags & OV_ARM_ON_LATCH) != 0, now = (flags & OV_START_NOW) != 0;
   if (arm == now || nops < 0 || nops > OV_MAX_OPS || (nops > 0 && !ops)) return 0;
   ovop_t tmp[OV_MAX_OPS];
   int end = -1;
   for (int i = 0; i < nops; i++) {
      const int32_t *v = ops + i * OV_OP_INTS;
      ovop_t o = { v[0], v[1], v[2], v[3], v[4], v[5], v[6] };
      if (o.t0 < 0 || !op_valid(&o)) return 0;
      if (i > 0 && o.t0 < tmp[i - 1].t0) return 0;        /* t0 오름차순(Cue.build) */
      if (o.dur >= 0 && o.t0 + o.dur > end) end = o.t0 + o.dur;
      tmp[i] = o;
   }
   LOCK();
   ensure_init();
   if (g_cue_on) bake_all_locked();
   memcpy(g_ops, tmp, sizeof(ovop_t) * (size_t)nops);
   g_nops = nops; g_cue_on = 1; g_cue_end = end; g_cue_id = (int)((unsigned)flags >> OV_CUE_ID_SHIFT);
   g_started = 0; g_toff = g_t = 0; g_skip_pending = 0; g_skip_start = (flags & OV_SKIP_TO_END) != 0;
   if (arm) { g_armed = 1; g_pending = 0; g_visible = 0; g_shown = 0; update_inblk(); }
   else { g_pending = 1; g_armed = 0; }
   g_dirty = 1;
   UNLOCK();
   return 1;
}

void ov_hold_set(int on)
{
   LOCK(); ensure_init();
   g_hold = on ? 1 : 0; update_inblk();
   UNLOCK();
}
void ov_block_set(int bits)
{
   LOCK(); ensure_init();
   g_block = bits; update_inblk();
   UNLOCK();
}

void ov_clear(int mask)
{
   LOCK();
   ensure_init();
   if (mask & OV_CLR_W) { layer_reset(OV_W); drop_target_locked(OV_W); }
   if (mask & OV_CLR_L) { layer_reset(OV_L); drop_target_locked(OV_L); }
   if (mask & OV_CLR_BASE) { base_reset(); drop_target_locked(2); }
   if (mask & OV_CLR_CUE) cue_drop_locked();
   if (mask & OV_CLR_HOLD) { g_hold = 0; g_block = 0; }
   if (mask == OV_CLR_ALL) { g_visible = 0; g_shown = 0; }
   update_inblk();
   g_dirty = 1;
   UNLOCK();
}

void ov_status(int v[OV_NSTAT])
{
   LOCK();
   ensure_init();
   for (int i = 0; i < OV_NSTAT; i++) v[i] = 0;
   int running = g_cue_on && g_cue_end >= 0 && (!g_started || g_t < g_cue_end);
   v[OVS_VER] = OV_VERSION;
   v[OVS_FLAGS] = (g_visible ? OVF_VISIBLE : 0) | (g_cue_on && g_armed ? OVF_ARMED : 0) | (g_hold ? OVF_HOLD : 0)
                | (running ? OVF_RUNNING : 0) | (g_rect_ok ? OVF_RECT_OK : 0) | (g_visible && g_shown ? OVF_SHOWN : 0);
   v[OVS_T] = g_cue_on && g_started ? g_t : -1;
   v[OVS_END] = g_cue_on ? g_cue_end : -1;
   v[OVS_CUE_ID] = g_cue_on ? g_cue_id : 0;
   v[OVS_STARTS] = g_starts;
   v[OVS_RX] = g_rx; v[OVS_RY] = g_ry; v[OVS_SUB] = g_sub; v[OVS_FBW] = g_fbw; v[OVS_FBH] = g_fbh;
   v[OVS_VX] = g_vx; v[OVS_VY] = g_vy; v[OVS_VW] = g_vw; v[OVS_VH] = g_vh;
   if (g_ram && g_ramn >= 0x2200) {
      v[OVS_GPAUSE] = g_ram[0x61D4 - 0x4000] == 1 ? 1 : 0;
      v[OVS_W1] = g_ram[0x5821 - 0x4000];
      v[OVS_W2] = g_ram[0x5822 - 0x4000];
   } else v[OVS_GPAUSE] = v[OVS_W1] = v[OVS_W2] = -1;
   v[OVS_LATCH] = g_latch;
   v[OVS_COMPOSITES] = g_composites;
   UNLOCK();
}

/* ── native.c 연결 ─────────────────────────────────────────────────────── */

int ov_hold(void) { return g_hold; }
int ov_in_block(void) { return g_inblk; }
int ov_active(void) { return g_visible; }

void ov_game_ram(const uint8_t *ram, size_t n)
{
   LOCK(); g_ram = ram; g_ramn = ram ? n : 0; UNLOCK();
}

void ov_tick(double now_s, int latch)
{
   LOCK();
   ensure_init();
   double gap = g_last_now >= 0.0 ? now_s - g_last_now : 0.0;
   g_last_now = now_s;
   g_latch = latch;
   if (g_cue_on) {
      if (g_pending || (g_armed && latch)) {               /* 시작 — 걸어 둔 것은 걸쇠 레벨(이미 서 있어도) */
         g_pending = g_armed = 0; g_started = 1; g_t0s = now_s;
         g_toff = g_skip_start ? (g_cue_end > 0 ? g_cue_end : 0) : 0;
         g_t = g_toff; g_skip_start = g_skip_pending = 0;
         g_visible = 1; g_shown = 0; g_starts++; g_dirty = 1;
         update_inblk();
      } else if (g_started) {
         /* 앱이 내려가 있던 동안(nativeFrame 이 안 불림 — 단조 시계는 그래도 감) 대본 시계는 멈춘다: 돌아온 첫 tick 은 멈춘 데서 이어서.
            (안 그러면 «연속 대전» 10초가 밖에 나가 있던 동안 다 타서, 돌아오자마자 다음 상대로 넘어갔다 — 153 찾기 겹창 시계도 선다) */
         if (gap > OV_GAP_S) g_t0s += gap;
         int raw = (int)floor((now_s - g_t0s) * 60.0 + TEPS), t = raw + g_toff;
         if (g_skip_pending) {                                /* 건너뛰기: 이 vsync 의 t 를 끝으로 — 그 뒤는 끝부터 이어서 */
            if (t < g_cue_end) { g_toff += g_cue_end - t; t = g_cue_end; }
            g_skip_pending = 0;
         }
         if (t < g_t) t = g_t;                                /* 되돌아가지 않는다 */
         if (t != g_t) { if (cue_changes(g_t, t)) g_dirty = 1; g_t = t; }
      }
   }
   UNLOCK();
}

int ov_find_rect(unsigned fbw, unsigned fbh, int *x0, int *y0, int *sub)
{
   unsigned s = (fbw >= 320 && fbw % 160 == 0 && fbh % (fbw / 160) == 0
                 && fbh / (fbw / 160) >= 152u && fbh / (fbw / 160) <= 184u) ? fbw / 160 : 1;
   unsigned lw = fbw / s, lh = fbh / s;
   int ok = lw >= 160 && lw <= 320 && lh >= 152 && lh <= 184;
   if (x0) *x0 = ok ? (int)(((lw - 160) / 2) * s) : 0;
   if (y0) *y0 = ok ? (int)((lh - 152) * s) : 0;
   if (sub) *sub = (int)s;
   return ok;
}

void ov_viewport(int vp_x, int vp_y, int vp_w, int vp_h, int view_h, unsigned fbw, unsigned fbh)
{
   int x0 = 0, y0 = 0, sub = 1;
   int ok = fbw && fbh && ov_find_rect(fbw, fbh, &x0, &y0, &sub);
   LOCK();
   g_fbw = (int)fbw; g_fbh = (int)fbh; g_rect_ok = ok; g_rx = x0; g_ry = y0; g_sub = sub;
   if (ok && vp_w > 0 && vp_h > 0) {
      int64_t lw = fbw / (unsigned)sub, lh = fbh / (unsigned)sub;
      g_vx = vp_x + (int)((int64_t)(x0 / sub) * vp_w / lw);
      g_vy = (view_h - (vp_y + vp_h)) + (int)((int64_t)(y0 / sub) * vp_h / lh);
      g_vw = (int)((int64_t)OV_GAME_W * vp_w / lw);
      g_vh = (int)((int64_t)OV_GAME_H * vp_h / lh);
   } else g_vx = g_vy = g_vw = g_vh = 0;
   UNLOCK();
}

/* ── 합성 ──────────────────────────────────────────────────────────────── */

static inline void put(int x, int y, uint32_t c) { g_ovl[y][x] = c; }

static void draw_layer(const oveff_t *E, int ly)
{
   const ovbank_t *B = &BK[ly];
   const ovlstate_t *L = &E->s.l[ly];
   const uint32_t *map = B->map;
   int cols = B->cols, rows = B->rows, ncell = cols * rows;
   int cy0 = L->clip0 < 0 ? 0 : L->clip0, cy1 = L->clip1 > OV_GAME_H - 1 ? OV_GAME_H - 1 : L->clip1;
   if (cy0 > cy1) return;
   {  /* COUNT — 칸 프레임을 바꿔 낀 맵 */
      int has = 0;
      for (int i = 0; i < E->ncnt; i++) if (E->cnt[i].layer == ly) { has = 1; break; }
      if (has) {
         memcpy(B->emap, B->map, sizeof(uint32_t) * (size_t)ncell);
         for (int i = 0; i < E->ncnt; i++) {
            const ovcnt_t *c = &E->cnt[i];
            if (c->layer != ly) continue;
            for (int r = 0; r < c->h; r++) for (int q = 0; q < c->w; q++)
               B->emap[(c->r0 + r) * cols + c->c0 + q] = B->map[(c->sr + r) * cols + c->sc + q];
         }
         map = B->emap;
      }
   }
   memset(g_pidx, -1, (size_t)ncell);
   for (int p = 0; p < OV_PANES; p++) {
      const ovpane_t *P = &L->pane[p];
      if (P->c1 < P->c0) continue;
      for (int r = P->r0; r <= P->r1; r++) for (int c = P->c0; c <= P->c1; c++) if (g_pidx[r * cols + c] < 0) g_pidx[r * cols + c] = (int8_t)p;
   }
   uint32_t prgb[OV_PALS][4];
   for (int p = 0; p < OV_PALS; p++) for (int k = 0; k < 4; k++) prgb[p][k] = ov_rgb(L->pal[p][k]);
   const ovwipe_t *Wp = &L->wipe;
   int WW = (Wp->c1 - Wp->c0 + 1) * 8;
   uint32_t ecol = Wp->ecol ? prgb[Wp->epal][Wp->ecol] : 0;

   for (int r = 0; r < rows; r++)
      for (int c = 0; c < cols; c++) {
         uint32_t me = map[r * cols + c];
         int inw = Wp->on && c >= Wp->c0 && c <= Wp->c1 && r >= Wp->r0 && r <= Wp->r1;
         if (me == IM_EMPTY && !(inw && Wp->ecol)) continue;
         int p = g_pidx[r * cols + c], dx = 0, dy = 0;
         if (p >= 0) { if (!L->pane[p].vis) continue; dx = L->pane[p].dx; dy = L->pane[p].dy; }
         int by = 8 * r - L->sy + dy;
         if (by > cy1 || by + 7 < cy0) continue;
         const uint8_t *tp = me == IM_EMPTY ? NULL : B->px[me & IM_TILE];
         int pal = (int)((me >> IM_PSH) & 15), hf = (me & IM_H) != 0, vf = (me & IM_V) != 0;
         for (int py = 0; py < 8; py++) {
            int sy = by + py;
            if (sy < cy0 || sy > cy1) continue;
            int bx = 8 * c - L->sx + dx + L->rowx[sy];
            if (bx > OV_GAME_W - 1 || bx + 7 < 0) continue;
            const uint8_t *trow = tp ? tp + (vf ? 7 - py : py) * 8 : NULL;
            for (int px = 0; px < 8; px++) {
               int sx = bx + px;
               if (sx < 0 || sx > OV_GAME_W - 1) continue;
               int ci = trow ? trow[hf ? 7 - px : px] : 0;
               if (inw) {
                  int rx = 8 * (c - Wp->c0) + px, ry = 8 * (r - Wp->r0) + py;
                  if (Wp->dir) rx = WW - 1 - rx;
                  int key = rx + ry * Wp->slope;
                  if (key >= Wp->front) continue;
                  if (key >= Wp->front - 2) { if (Wp->ecol) put(sx, sy, ecol); continue; }
               }
               if (ci) put(sx, sy, prgb[pal][ci]);
            }
         }
      }
   /* 채움(GAUGE) — 왼위 픽셀 칸의 창유리를 따른다 */
   for (int i = 0; i < OV_FILLS; i++) {
      const ovfill_t *F = &L->fill[i];
      if (F->w <= 0 || F->h <= 0 || F->col < 1 || F->col > 3) continue;
      int cx = F->x >> 3, cyy = F->y >> 3, dx = 0, dy = 0;
      if (cx < cols && cyy < rows) {
         int p = g_pidx[cyy * cols + cx];
         if (p >= 0) { if (!L->pane[p].vis) continue; dx = L->pane[p].dx; dy = L->pane[p].dy; }
      }
      uint32_t cc = prgb[F->pal][F->col];
      for (int yy = 0; yy < F->h; yy++) {
         int sy = F->y + yy - L->sy + dy;
         if (sy < cy0 || sy > cy1) continue;
         int bx = F->x - L->sx + dx + L->rowx[sy];
         for (int xx = 0; xx < F->w; xx++) {
            int sx = bx + xx;
            if (sx >= 0 && sx < OV_GAME_W) put(sx, sy, cc);
         }
      }
   }
   /* 쿼드 */
   for (int q = 0; q < OV_QUADS; q++) {
      const ovquad_t *Q = &L->quad[q];
      if (!(Q->flags & OV_Q_SHOW)) continue;
      int sz = (Q->flags & OV_Q_16) ? 16 : 8, dx = 0, dy = 0;
      if (Q->x >= 0 && Q->y >= 0 && (Q->x >> 3) < cols && (Q->y >> 3) < rows) {
         int p = g_pidx[(Q->y >> 3) * cols + (Q->x >> 3)];
         if (p >= 0) { if (!L->pane[p].vis) continue; dx = L->pane[p].dx; dy = L->pane[p].dy; }
      }
      int bxo = E->qdx[ly][q], byo = E->qdy[ly][q];
      for (int qy = 0; qy < sz; qy++) {
         int sy = Q->y + qy - L->sy + dy + byo;
         if (sy < cy0 || sy > cy1) continue;
         int bx = Q->x - L->sx + dx + L->rowx[sy] + bxo;
         int uy = (Q->flags & OV_Q_VFLIP) ? sz - 1 - qy : qy;
         for (int qx = 0; qx < sz; qx++) {
            int sx = bx + qx;
            if (sx < 0 || sx >= OV_GAME_W) continue;
            int ux = (Q->flags & OV_Q_HFLIP) ? sz - 1 - qx : qx;
            int tile = Q->tile + (sz == 16 ? (ux >> 3) + 2 * (uy >> 3) : 0);
            int ci = B->px[tile][(uy & 7) * 8 + (ux & 7)];
            if (ci) put(sx, sy, prgb[Q->pal][ci]);
         }
      }
   }
}

static inline uint32_t mix_white(uint32_t c, int k)    /* c + ((255−c)·k)/16 채널마다 */
{
   unsigned r = c & 0xFF, g = (c >> 8) & 0xFF, b = (c >> 16) & 0xFF;
   r += ((255 - r) * (unsigned)k) / 16; g += ((255 - g) * (unsigned)k) / 16; b += ((255 - b) * (unsigned)k) / 16;
   return BLACK | (b << 16) | (g << 8) | r;
}

/* 줄마다 «바탕 처리» 사본(줄 밀기·바탕 칸·흰 번쩍) — 지난 합성과 같고 층 그림 줄(g_ovl)도 같으면 그 줄은 다시 만들지도 올리지도 않는다.
   쉬는 메뉴(고른 줄 일렁임·단도 까딱만 도는 결판·쉼·서랍)에서 바뀐 줄만 합성하고, gl_draw 는 그 줄 범위(ov_compose_span)만 올린다 */
typedef struct { int dx, white, n; int m[OV_BASE_SLOTS], k[OV_BASE_SLOTS]; uint32_t c[OV_BASE_SLOTS]; } ovrow_t;
static ovrow_t   g_rowd[OV_GAME_H], g_prowd[OV_GAME_H];
static uint32_t  g_povl[OV_GAME_H][OV_GAME_W];  /* 지난 합성의 층 그림 */
static int       g_prev_ok, g_prev_shake;
static uint32_t *g_row;                         /* 한 줄 만들 자리(w 픽셀) */
static size_t    g_rowcap;
static int       g_span0 = 0, g_span1 = -1;     /* 마지막 합성이 바꾼 그림 줄(포함) — 없으면 span1 < span0 */

/* 그림 줄 oy 하나를 dst(w 픽셀)에 — 흔들림(shake)은 게임 사각형 안 줄을 옮겨 오고, 빈 줄은 검정 */
static void build_row(uint32_t *dst, const uint32_t *S, unsigned w, int oy, int x0, int y0, int sub, int shake)
{
   memcpy(dst, S + (size_t)oy * w, (size_t)w * 4);
   int GW = OV_GAME_W * sub, H = OV_GAME_H * sub, y = oy - y0;
   if (y < 0 || y >= H) return;                          /* 게임 사각형 밖(위 띠) — 그림 그대로 */
   int py = y - shake * sub;
   uint32_t *g = dst + x0;
   if (py < 0 || py >= H) { for (int i = 0; i < GW; i++) g[i] = BLACK; return; }
   const uint32_t *sr = S + (size_t)(y0 + py) * w + (size_t)x0;
   const ovrow_t *r = &g_rowd[py / sub];
   /* 1. 바탕 줄 밀기 */
   if (r->dx) {
      int DX = r->dx * sub;
      for (int i = 0; i < GW; i++) { int si = i - DX; g[i] = (si >= 0 && si < GW) ? sr[si] : BLACK; }
   } else if (py != y) memcpy(g, sr, (size_t)GW * 4);
   /* 2. 바탕 칸 0→3 · 일섬 흰 번쩍 */
   for (int n = 0; n < r->n; n++) {
      int m = r->m[n], kk = r->k[n];
      if (m == OV_BASE_DIM) {
         const uint8_t *d5 = g_dim5[kk], *d6 = g_dim6[kk];
         for (int i = 0; i < GW; i++) {
            uint32_t c = g[i];
            g[i] = BLACK | ((uint32_t)d5[(c >> 16) & 0xFF] << 16) | ((uint32_t)d6[(c >> 8) & 0xFF] << 8) | d5[c & 0xFF];
         }
      } else if (m == OV_BASE_FLASH) {
         for (int i = 0; i < GW; i++) g[i] = mix_white(g[i], kk);
      } else { uint32_t fc = r->c[n]; for (int i = 0; i < GW; i++) g[i] = fc; }
   }
   if (r->white) for (int i = 0; i < GW; i++) g[i] = mix_white(g[i], 9);
   /* 3·4·5. 층 그림 */
   const uint32_t *ol = g_ovl[py / sub];
   for (int gx = 0; gx < OV_GAME_W; gx++) {
      uint32_t c = ol[gx];
      if (!c) continue;
      for (int i = 0; i < sub; i++) g[gx * sub + i] = c;
   }
}

/* full = 그림(src)이 바뀌었을 수 있음 — 모든 줄을 다시 만들고 지난 결과와 견줌. fresh = g_out 이 아직 쓸모없음(처음·크기 바뀜) — 견주지 않고 다 바뀜 */
static void compose_locked(const uint8_t *src, unsigned w, unsigned h, int x0, int y0, int sub, int full, int fresh)
{
   static oveff_t E;
   eval_locked(&E);
   const uint32_t *S = (const uint32_t *)src;
   uint32_t *O = g_out;
   size_t rowb = (size_t)w * 4;

   /* 층 그림(게임 좌표) — L → W → 일섬 칼선 */
   memset(g_ovl, 0, sizeof g_ovl);
   draw_layer(&E, OV_L);
   draw_layer(&E, OV_W);
   if (E.ff >= 0 && E.ff <= 7) {
      int xe = E.ff <= 3 ? 40 * (E.ff + 1) : OV_GAME_W;
      if (xe > OV_GAME_W) xe = OV_GAME_W;
      uint32_t cc = ov_rgb(E.fc), cd = ov_rgb(E.fd);
      for (int dy = -1; dy <= 1; dy++) {
         int y = E.fy + dy;
         if (y < 0 || y >= OV_GAME_H) continue;
         for (int x = 0; x < xe; x++) put(x, y, dy ? cd : cc);
      }
   }
   /* 줄 사본 — 바탕 칸 범위(FILL 은 위에서부터 arg 줄 · FILLUP 은 아래에서부터) · 줄 밀기는 나중 것이 이김(줄 표 → 바탕 칸 SHIFT → 일섬 베기) */
   int ya[OV_BASE_SLOTS], yb[OV_BASE_SLOTS], kk[OV_BASE_SLOTS];
   uint32_t fc[OV_BASE_SLOTS];
   for (int k = 0; k < OV_BASE_SLOTS; k++) {
      const ovbslot_t *b = &E.s.base[k];
      int m = b->mode;
      ya[k] = 1; yb[k] = 0; kk[k] = b->arg; fc[k] = 0;
      if (m != OV_BASE_DIM && m != OV_BASE_FILL && m != OV_BASE_FILLUP && m != OV_BASE_FLASH) continue;
      ya[k] = b->y0 < 0 ? 0 : b->y0; yb[k] = b->y1 > OV_GAME_H - 1 ? OV_GAME_H - 1 : b->y1;
      if (m == OV_BASE_FILL) { int e = b->y0 + b->arg - 1; if (yb[k] > e) yb[k] = e; }
      if (m == OV_BASE_FILLUP) { int s0 = b->y1 - b->arg + 1; if (ya[k] < s0) ya[k] = s0; }
      if (m == OV_BASE_DIM) { if (kk[k] < 0) kk[k] = 0; if (kk[k] > 4) kk[k] = 4; }
      if (m == OV_BASE_FLASH) { if (kk[k] < 0) kk[k] = 0; if (kk[k] > 16) kk[k] = 16; }
      if (m == OV_BASE_FILL || m == OV_BASE_FILLUP) { fc[k] = ov_rgb(b->color); kk[k] = 0; }
   }
   for (int gy = 0; gy < OV_GAME_H; gy++) {
      ovrow_t *r = &g_rowd[gy];
      memset(r, 0, sizeof *r);
      int dx = E.s.bgx[gy];
      for (int k = 0; k < OV_BASE_SLOTS; k++) {
         const ovbslot_t *b = &E.s.base[k];
         if (b->mode == OV_BASE_SHIFT && gy >= b->y0 && gy <= b->y1) dx = b->arg;
      }
      if (E.ff >= 6 && E.ff <= 9) dx = gy < E.fy ? -4 : 4;
      r->dx = dx; r->white = E.ff >= 4 && E.ff <= 5;
      for (int k = 0; k < OV_BASE_SLOTS; k++)
         if (gy >= ya[k] && gy <= yb[k]) { r->m[r->n] = E.s.base[k].mode; r->k[r->n] = kk[k]; r->c[r->n] = fc[k]; r->n++; }
   }
   int lo = 0x7FFFFFFF, hi = -1;
#define ROW(oy) do { \
      uint32_t *d_ = O + (size_t)(oy) * w; \
      build_row(g_row, S, w, (oy), x0, y0, sub, E.shake); \
      if (fresh || memcmp(g_row, d_, rowb)) { memcpy(d_, g_row, rowb); if ((oy) < lo) lo = (oy); if ((oy) > hi) hi = (oy); } \
   } while (0)
   if (full || fresh || !g_prev_ok || E.shake || g_prev_shake) {
      for (int oy = 0; oy < (int)h; oy++) ROW(oy);
   } else {
      for (int gy = 0; gy < OV_GAME_H; gy++) {
         if (!memcmp(&g_rowd[gy], &g_prowd[gy], sizeof(ovrow_t)) && !memcmp(g_ovl[gy], g_povl[gy], sizeof g_ovl[gy])) continue;
         for (int j = 0; j < sub; j++) ROW(y0 + gy * sub + j);
      }
   }
#undef ROW
   memcpy(g_prowd, g_rowd, sizeof g_rowd);
   memcpy(g_povl, g_ovl, sizeof g_ovl);
   g_prev_shake = E.shake; g_prev_ok = 1;
   g_span0 = hi >= 0 ? lo : 0; g_span1 = hi;
}

const uint8_t *ov_compose(const uint8_t *src, unsigned w, unsigned h, int src_changed, int *changed)
{
   int x0, y0, sub;
   if (changed) *changed = 0;
   LOCK();
   ensure_init();
   g_span0 = 0; g_span1 = -1;
   if (!src || !w || !h || !ov_find_rect(w, h, &x0, &y0, &sub)) { g_rect_ok = 0; UNLOCK(); return NULL; }
   g_rect_ok = 1; g_rx = x0; g_ry = y0; g_sub = sub; g_fbw = (int)w; g_fbh = (int)h;
   if (!g_visible) { UNLOCK(); return NULL; }
   size_t need = (size_t)w * h;
   if (need > g_outcap) {
      uint32_t *nb = (uint32_t *)realloc(g_out, need * 4);
      if (!nb) { UNLOCK(); return NULL; }
      g_out = nb; g_outcap = need; g_out_valid = 0;
   }
   if ((size_t)w > g_rowcap) {
      uint32_t *nr = (uint32_t *)realloc(g_row, (size_t)w * 4);
      if (!nr) { UNLOCK(); return NULL; }
      g_row = nr; g_rowcap = w;
   }
   int geom = !g_out_valid || w != g_outw || h != g_outh;
   /* 보이기 시작한 뒤 첫 합성(g_shown 0)은 그림 전체를 견줌 — 안 보이던 동안 게임 그림이 바뀌었어도 gl_draw 는 여기를 부르지 않았다 */
   if (src_changed || g_dirty || geom || src != g_last_src || !g_shown) {
      if (geom) g_prev_ok = 0;
      compose_locked(src, w, h, x0, y0, sub, src_changed || src != g_last_src || !g_shown, geom);
      g_outw = w; g_outh = h; g_out_valid = 1; g_dirty = 0; g_last_src = src;
      g_composites++;
      if (changed) *changed = g_span1 >= g_span0;
   }
   g_shown = 1;
   UNLOCK();
   return (const uint8_t *)g_out;
}

void ov_compose_span(int *y0, int *y1)
{
   LOCK();
   if (y0) *y0 = g_span0;
   if (y1) *y1 = g_span1;
   UNLOCK();
}

/* ── JNI(Emu.java) — 모양이 계약. 배열은 길이를 보고 GetXxxArrayRegion 으로 복사한다 ───────────── */
#define JNI(ret, name) JNIEXPORT ret JNICALL Java_com_dudu_pocketcore_Emu_##name

JNI(jboolean, nativeOvLayer)(JNIEnv *env, jclass cls, jint layer, jbyteArray tiles, jint tileBase, jshortArray map,
                             jint cols, jint rows, jint col0, jint row0, jshortArray pals, jint palBase)
{
   (void)cls;
   static uint8_t  tb[OV_CHUNK_MAX * 16];
   static uint16_t mb[OV_W_COLS * OV_L_ROWS];
   static uint16_t pb[OV_PALS * 4];
   int nt = 0, np = 0;
   if (tiles) {
      jsize n = (*env)->GetArrayLength(env, tiles);
      if (n % 16 || n > (jsize)sizeof tb) return JNI_FALSE;
      (*env)->GetByteArrayRegion(env, tiles, 0, n, (jbyte *)tb); nt = n / 16;
   }
   if (map) {
      jsize n = (*env)->GetArrayLength(env, map);
      if (cols <= 0 || rows <= 0 || n != cols * rows || n > (jsize)(sizeof mb / 2)) return JNI_FALSE;
      (*env)->GetShortArrayRegion(env, map, 0, n, (jshort *)mb);
   }
   if (pals) {
      jsize n = (*env)->GetArrayLength(env, pals);
      if (n % 4 || n > (jsize)(sizeof pb / 2)) return JNI_FALSE;
      (*env)->GetShortArrayRegion(env, pals, 0, n, (jshort *)pb); np = n / 4;
   }
   return ov_layer(layer, tiles ? tb : NULL, nt, tileBase, map ? mb : NULL, cols, rows, col0, row0, pals ? pb : NULL, np, palBase) ? JNI_TRUE : JNI_FALSE;
}

JNI(jboolean, nativeOvCells)(JNIEnv *env, jclass cls, jint layer, jshortArray cells, jint col0, jint row0, jint cols, jint rows, jint tileBase)
{
   (void)cls;
   static uint16_t cb[OV_W_COLS * OV_L_ROWS];
   if (!cells) return JNI_FALSE;
   jsize n = (*env)->GetArrayLength(env, cells);
   if (cols <= 0 || rows <= 0 || n != cols * rows || n > (jsize)(sizeof cb / 2)) return JNI_FALSE;
   (*env)->GetShortArrayRegion(env, cells, 0, n, (jshort *)cb);
   return ov_cells(layer, cb, col0, row0, cols, rows, tileBase) ? JNI_TRUE : JNI_FALSE;
}

JNI(jboolean, nativeOvRecolor)(JNIEnv *env, jclass cls, jint layer, jint col0, jint row0, jint cols, jint rows, jint pal)
{ (void)env; (void)cls; return ov_recolor(layer, col0, row0, cols, rows, pal) ? JNI_TRUE : JNI_FALSE; }

JNI(jboolean, nativeOvPal)(JNIEnv *env, jclass cls, jint layer, jint pal, jint c1, jint c2, jint c3)
{ (void)env; (void)cls; return ov_pal(layer, pal, c1, c2, c3) ? JNI_TRUE : JNI_FALSE; }

JNI(jboolean, nativeOvQuad)(JNIEnv *env, jclass cls, jint layer, jint idx, jint x, jint y, jint tile, jint pal, jint flags)
{ (void)env; (void)cls; return ov_quad(layer, idx, x, y, tile, pal, flags) ? JNI_TRUE : JNI_FALSE; }

JNI(jboolean, nativeOvPane)(JNIEnv *env, jclass cls, jint layer, jint idx, jint c0, jint r0, jint c1, jint r1, jint dx, jint dy, jboolean visible)
{ (void)env; (void)cls; return ov_pane(layer, idx, c0, r0, c1, r1, dx, dy, visible ? 1 : 0) ? JNI_TRUE : JNI_FALSE; }

JNI(jboolean, nativeOvScroll)(JNIEnv *env, jclass cls, jint layer, jint x, jint y, jint clipY0, jint clipY1)
{ (void)env; (void)cls; return ov_scroll(layer, x, y, clipY0, clipY1) ? JNI_TRUE : JNI_FALSE; }

JNI(jboolean, nativeOvBase)(JNIEnv *env, jclass cls, jint slot, jint mode, jint y0, jint y1, jint arg, jint color)
{ (void)env; (void)cls; return ov_base(slot, mode, y0, y1, arg, color) ? JNI_TRUE : JNI_FALSE; }

JNI(jboolean, nativeOvCue)(JNIEnv *env, jclass cls, jintArray ops, jint flags)
{
   (void)cls;
   static int32_t ob[OV_MAX_OPS * OV_OP_INTS];
   if (!ops) return ov_cue(NULL, 0, flags) ? JNI_TRUE : JNI_FALSE;
   jsize n = (*env)->GetArrayLength(env, ops);
   if (n % OV_OP_INTS || n > (jsize)(sizeof ob / sizeof ob[0])) return JNI_FALSE;
   (*env)->GetIntArrayRegion(env, ops, 0, n, (jint *)ob);
   return ov_cue(ob, n / OV_OP_INTS, flags) ? JNI_TRUE : JNI_FALSE;
}

JNI(void, nativeOvHold)(JNIEnv *env, jclass cls, jboolean on) { (void)env; (void)cls; ov_hold_set(on ? 1 : 0); }
JNI(void, nativeOvBlock)(JNIEnv *env, jclass cls, jint bits) { (void)env; (void)cls; ov_block_set(bits); }
JNI(void, nativeOvClear)(JNIEnv *env, jclass cls, jint mask) { (void)env; (void)cls; ov_clear(mask); }

JNI(jintArray, nativeOvStatus)(JNIEnv *env, jclass cls)
{
   (void)cls;
   int v[OV_NSTAT]; jint jv[OV_NSTAT];
   ov_status(v);
   for (int i = 0; i < OV_NSTAT; i++) jv[i] = v[i];
   jintArray a = (*env)->NewIntArray(env, OV_NSTAT);
   if (a) (*env)->SetIntArrayRegion(env, a, 0, OV_NSTAT, jv);
   return a;
}
