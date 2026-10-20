/* 타격 알림 — 설명은 callout.h. 램 주소·칸 규칙은 practice.c(연습 대전 프레임 미터)와 같은 것만 쓴다. */
#include "callout.h"
#include "callout_atlas.h"
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
#define RAM_NEED (A(0x61D4) + 1)

enum { K_IDLE = 0, K_MOVE, K_STARTUP, K_ACTIVE, K_RECOVERY, K_HITSTUN, K_BLOCKSTUN, K_DOWN };
enum { C_NONE = 0, C_HIT, C_BLOCK, C_THROW, C_OTHER, C_MULTI };

static atomic_int co_on = 0;
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
   int stats[8];
} S;
static callout_t L[2][CO_ROWS];                 /* 쪽마다 줄 — t0 < 0 = 빈 칸 */

void callout_enable(int on)
{
   if (!atomic_exchange(&co_on, on ? 1 : 0) && on) atomic_store(&co_reset_req, 1);   /* 꺼져 있던 동안의 앞 프레임 값으로 판정하지 않게 */
}
int  callout_enabled(void) { return atomic_load(&co_on); }

void callout_reset(void) { atomic_store(&co_reset_req, 1); }

static void reset_now(void)
{
   int st[8];
   memcpy(st, S.stats, sizeof st);
   memset(&S, 0, sizeof S);
   memcpy(S.stats, st, sizeof st);
   memset(S.armed, -1, sizeof S.armed);
   for (int s = 0; s < 2; s++) for (int r = 0; r < CO_ROWS; r++) { L[s][r].t0 = -1e9; L[s][r].kind = -1; L[s][r].side = s; L[s][r].row = r; }
}

static void emit(int kind, int side, double now)
{
   int r, use = -1;
   S.stats[kind]++;
   for (r = 0; r < CO_ROWS; r++) if (L[side][r].kind == kind && now - L[side][r].t0 < CO_LIFE_F / 60.0) { use = r; break; }   /* 같은 라벨이 떠 있으면 다시 시작 */
   if (use < 0) for (r = 0; r < CO_ROWS; r++) if (now - L[side][r].t0 >= CO_LIFE_F / 60.0) { use = r; break; }
   if (use < 0) { use = 0; for (r = 1; r < CO_ROWS; r++) if (L[side][r].t0 < L[side][use].t0) use = r; }   /* 다 차면 가장 오래된 줄 */
   L[side][use].kind = kind; L[side][use].side = side; L[side][use].row = use; L[side][use].t0 = now;
}

static int rd16(const uint8_t *ram, int o) { return ram[o] | (ram[o + 1] << 8); }

void callout_tick(const uint8_t *ram, size_t ramsz, double now)
{
   uint8_t ctrl[2], combo[2], st[2], box[2], proj[2], cap[32];
   uint16_t act[2], y[2];
   int hs, live, p;
   if (atomic_exchange(&co_reset_req, 0)) reset_now();
   if (!atomic_load(&co_on) || !ram || ramsz < (size_t)RAM_NEED) return;
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
            if (S.cls[d] == K_STARTUP || S.cls[d] == K_ACTIVE) emit(CO_COUNTER, a, now);
            else if (S.cls[d] == K_RECOVERY) emit(CO_PUNISH, a, now);
         }
      }
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
   for (p = 0; p < 2; p++) { S.p_ctrl[p] = ctrl[p]; S.p_combo[p] = combo[p]; S.p_act[p] = act[p]; }
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
