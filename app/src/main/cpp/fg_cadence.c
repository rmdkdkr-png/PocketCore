/* 앱 보간 «박자» 모드 (내부 149) — 게임 그림이 몇 프레임마다 움직이는지(박자)를 보고 사이를 고르게 채운다.
 *
 * 왜: 2 프레임마다(30Hz) 한 걸음씩 움직이는 게임은, «움직임» 모드(실제 프레임 둘 사이에 반 걸음 한 장)로는
 *     안 움직인 프레임 사이엔 같은 그림이, 움직인 프레임 사이엔 반 걸음이 들어가 «멈춤 셋 + 반 걸음» 계단이 된다
 *     (유저 2026-10-16 「앱보간 타이밍이 틀려서 효과가 없었네」·「60목표로 1차 120목표로 추가」).
 *
 * 어떻게 — 실제 프레임 F(n+2) 가 들어오면 두 칸을 만든다(고리엔 F(n)·F(n+1)·F(n+2)):
 *   1단계 · 60 목표 — G(n+1): n→n+1 에 멈췄다가 n+1→n+2 에 움직일 것(«쉬는 쪽»)을 그 걸음의 ½ 만큼 미리 옮긴 그림.
 *           30Hz 로 움직이는 것이 실제 프레임마다 반 걸음씩 — 60Hz 로 걷는다. 나머지는 실제 F(n+1) 그대로.
 *   2단계 · 120 목표 — n 과 n+1 사이 칸: n 에 쉬던 것(G(n) 에서 ½ 갔던 것)은 ¾, n+1 에 쉴 것은 ¼,
 *           프레임마다 움직이는 것(60Hz)은 ½, 멈춘 것은 그대로.
 *   ⇒ 30Hz 로 움직이는 것은 120Hz 다섯 장에 0·¼·½·¾·1, 60Hz 는 0·½·1.
 *   그림은 4배(¼ 픽셀)로 — 2 프레임에 1 px 가는 배경은 1배로는 반 걸음을 못 그린다(S=1 도 됨 — 업스케일러).
 *   움직임: 8×8 블록 탐색(framegen.c 와 같은 규칙) → 화소마다 «어디서 왔나» 표(후보 = 제 블록·이웃 넷·정지).
 *   겹치는 곳: 그 걸음에 가장 흔한 움직임(대개 배경)이 아닌 쪽이 앞(캐릭터)이라고 본다.
 *   포즈가 바뀌는 곳(모양이 달라 짝을 못 찾음)은 원래처럼 툭 — 그림을 지어내지 않는다.
 * 대가: F(n+2) 를 보고 나서야 G(n+1) 을 그리므로 «움직임» 모드보다 1 프레임(1/60초) 늦다.
 *
 * 안드로이드 의존 없음 — 호스트 시험 tools/fg_cad_test.c. */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "framegen.h"

#define CBS 8          /* 블록 한 변 */
#define CSR 6          /* 탐색 반경(px) */
#define C_BAD 24       /* 블록 화소당 평균 차 한계 — 넘으면 «못 찾음» */
#define C_SAME 24      /* 두 화소를 같은 색으로 볼 차 */
#define CN 3           /* 고리: F(n) · F(n+1) · F(n+2) */
#define NCAND 5
#define LUNK ((int16_t)0x7F7F)   /* 표: 짝 없음(가림·드러남·모양 바뀜) */

typedef struct { int8_t *mv; uint8_t *ok; } cfield;
typedef struct { int16_t v[NCAND]; uint8_t n; } cand_t;

static struct {
   int w, h, nbx, nby, n, cap_px, cap_bk, hvA_ok;
   uint8_t *f[CN], *lum[CN];
   cfield m[CN - 1];             /* m[i] = f[i] → f[i+1] (블록 = 뒤 프레임 자리, 내용이 앞 프레임 (x-dx,y-dy) 에서 옴) */
   cand_t *cd[CN - 1];           /* 블록마다 후보 벡터(제 블록·이웃 넷, 믿을 만하고 0 아닌 것) */
   int16_t *lab[CN - 1];         /* 화소마다 «어디서 왔나»(뒤 프레임 자리) — 0 = 멈춤, LUNK = 짝 없음 */
   int16_t dom[CN - 1];          /* 그 걸음에 가장 흔한 블록 움직임 */
   int16_t *hvA, *hv;            /* 쉬는 쪽 표: F(n)·F(n+1) 화소마다 다음 걸음 벡터(0 = 쉬는 쪽 아님) */
   uint8_t *amb;
} C;

static inline int iabs(int v) { return v < 0 ? -v : v; }
static inline int floordiv(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }
static inline int16_t pk(int dx, int dy) { return (int16_t)(((dx & 0xFF) << 8) | (dy & 0xFF)); }
static inline int ux(int16_t v) { return (int8_t)((uint16_t)v >> 8); }
static inline int uy(int16_t v) { return (int8_t)(v & 0xFF); }

static void free_bufs(void)
{
   for (int i = 0; i < CN; i++) { free(C.f[i]); free(C.lum[i]); C.f[i] = C.lum[i] = NULL; }
   for (int i = 0; i < CN - 1; i++) {
      free(C.m[i].mv); free(C.m[i].ok); free(C.cd[i]); free(C.lab[i]);
      C.m[i].mv = NULL; C.m[i].ok = NULL; C.cd[i] = NULL; C.lab[i] = NULL;
   }
   free(C.hvA); free(C.hv); free(C.amb); C.hvA = C.hv = NULL; C.amb = NULL;
   C.cap_px = C.cap_bk = 0; C.n = 0; C.hvA_ok = 0;
}

static int ensure(int w, int h)
{
   int px = w * h, nbx = (w + CBS - 1) / CBS, nby = (h + CBS - 1) / CBS, bk = nbx * nby;
   if (w != C.w || h != C.h) { C.n = 0; C.hvA_ok = 0; }   /* 크기가 바뀌면 고리를 비운다 */
   C.w = w; C.h = h; C.nbx = nbx; C.nby = nby;
   if (px <= C.cap_px && bk <= C.cap_bk) return 1;
   free_bufs();
   int ok = 1;
   for (int i = 0; i < CN; i++) {
      C.f[i] = (uint8_t *)malloc((size_t)px * 4); C.lum[i] = (uint8_t *)malloc((size_t)px);
      ok &= C.f[i] && C.lum[i];
   }
   for (int i = 0; i < CN - 1; i++) {
      C.m[i].mv = (int8_t *)calloc((size_t)bk * 2, 1); C.m[i].ok = (uint8_t *)calloc((size_t)bk, 1);
      C.cd[i] = (cand_t *)calloc((size_t)bk, sizeof(cand_t)); C.lab[i] = (int16_t *)calloc((size_t)px, 2);
      ok &= C.m[i].mv && C.m[i].ok && C.cd[i] && C.lab[i];
   }
   C.hvA = (int16_t *)calloc((size_t)px, 2); C.hv = (int16_t *)calloc((size_t)px, 2); C.amb = (uint8_t *)malloc((size_t)bk);
   ok &= C.hvA && C.hv && C.amb;
   if (!ok) { free_bufs(); return 0; }
   C.cap_px = px; C.cap_bk = bk; C.n = 0; C.hvA_ok = 0;
   return 1;
}

/* 블록 SAD — 원본이 화면 밖인 화소는 빼고 센 뒤 블록 넓이로 맞춘다(가장자리로 새 그림이 들어오는 스크롤도 짝을 찾게).
   반 넘게 밖이면 못 씀. */
static int sad(const uint8_t *lp, const uint8_t *lc, int x0, int y0, int bw, int bh, int dx, int dy, int limit)
{
   int w = C.w, h = C.h, s = 0, nv = 0, tot = bw * bh;
   for (int y = 0; y < bh; y++) {
      int cy = y0 + y, py = cy - dy;
      if (py < 0 || py >= h) continue;
      const uint8_t *rc = lc + cy * w + x0, *rp = lp + py * w;
      int xa = 0, xb = bw;                      /* 원본 px = x0+x-dx 가 [0,w) 인 x */
      if (x0 - dx < 0) xa = dx - x0;
      if (x0 + bw - dx > w) xb = w - x0 + dx;
      for (int x = xa; x < xb; x++) {
         int d = (int)rc[x] - (int)rp[x0 + x - dx];
         s += d < 0 ? -d : d;
      }
      if (xb > xa) nv += xb - xa;
      if (s >= limit) return s;
   }
   if (nv * 2 < tot) return 1 << 29;
   return nv == tot ? s : (int)((int64_t)s * tot / nv);
}

/* framegen.c search() 와 같은 규칙 — 예측 후보는 지난 걸음(pred) */
static void csearch(const uint8_t *lp, const uint8_t *lc, cfield *out, const cfield *pred)
{
   int w = C.w, h = C.h, nbx = C.nbx, nby = C.nby;
   int8_t *mv = out->mv; uint8_t *okv = out->ok, *amb = C.amb;
   for (int by = 0; by < nby; by++)
   for (int bx = 0; bx < nbx; bx++) {
      int x0 = bx * CBS, y0 = by * CBS, k = by * nbx + bx;
      int bw = (x0 + CBS <= w) ? CBS : w - x0, bh = (y0 + CBS <= h) ? CBS : h - y0;
      int best = sad(lp, lc, x0, y0, bw, bh, 0, 0, 1 << 30), bdx = 0, bdy = 0;
      amb[k] = (best == 0);
      if (best > 0) {
         int pc[3][2], np = 0;
         if (bx > 0) { pc[np][0] = mv[(k - 1) * 2]; pc[np][1] = mv[(k - 1) * 2 + 1]; np++; }
         if (by > 0) { pc[np][0] = mv[(k - nbx) * 2]; pc[np][1] = mv[(k - nbx) * 2 + 1]; np++; }
         if (pred) { pc[np][0] = pred->mv[k * 2]; pc[np][1] = pred->mv[k * 2 + 1]; np++; }
         for (int i = 0; i < np && best > 0; i++) {
            int dx = pc[i][0], dy = pc[i][1];
            if (!dx && !dy) continue;
            int pen = (iabs(dx) + iabs(dy)) * 2;
            int s = sad(lp, lc, x0, y0, bw, bh, dx, dy, best);
            if (s == 0) { best = 0; bdx = dx; bdy = dy; }
            else if (s + pen < best) { best = s + pen; bdx = dx; bdy = dy; }
         }
      }
      if (best > 0) {
         for (int dy = -CSR; dy <= CSR; dy++)
         for (int dx = -CSR; dx <= CSR; dx++) {
            if (!dx && !dy) continue;
            int pen = (iabs(dx) + iabs(dy)) * 2, lim = best - pen;
            if (lim <= 0) continue;
            int s = sad(lp, lc, x0, y0, bw, bh, dx, dy, lim);
            if (s + pen < best) { best = s + pen; bdx = dx; bdy = dy; if (s == 0) goto found; }
         }
      }
   found:
      mv[k * 2] = (int8_t)bdx; mv[k * 2 + 1] = (int8_t)bdy;
      okv[k] = best <= C_BAD * bw * bh;
   }
   /* 모호한 블록(정지도 완전히 맞는 민무늬·디더)은 무늬 있는 이웃 둘 이상의 같은 벡터가 이 블록에도 완전히 맞으면 그것 */
   for (int pass = 0; pass < 2; pass++)
   for (int by = 0; by < nby; by++)
   for (int bx = 0; bx < nbx; bx++) {
      int k = by * nbx + bx;
      if (!amb[k]) continue;
      int nk[4], nn = 0;
      if (bx > 0) nk[nn++] = k - 1;
      if (bx < nbx - 1) nk[nn++] = k + 1;
      if (by > 0) nk[nn++] = k - nbx;
      if (by < nby - 1) nk[nn++] = k + nbx;
      int bestc = 0, mx = 0, my = 0;
      for (int i = 0; i < nn; i++) {
         if (amb[nk[i]]) continue;
         int vx = mv[nk[i] * 2], vy = mv[nk[i] * 2 + 1], c = 0;
         for (int j = 0; j < nn; j++)
            if (!amb[nk[j]] && mv[nk[j] * 2] == vx && mv[nk[j] * 2 + 1] == vy) c++;
         if (c > bestc) { bestc = c; mx = vx; my = vy; }
      }
      if (bestc < 2 || (!mx && !my)) continue;
      int x0 = bx * CBS, y0 = by * CBS;
      int bw = (x0 + CBS <= w) ? CBS : w - x0, bh = (y0 + CBS <= h) ? CBS : h - y0;
      if (sad(lp, lc, x0, y0, bw, bh, mx, my, 1) == 0) { mv[k * 2] = (int8_t)mx; mv[k * 2 + 1] = (int8_t)my; amb[k] = 0; }
   }
}

/* 블록마다 후보(제 블록 먼저, 이웃 넷 — 믿을 만하고 0 아닌 것, 겹침 빼고)와 그 걸음의 가장 흔한 움직임 */
static int16_t cands_and_dom(int i)
{
   const cfield *m = &C.m[i]; cand_t *cd = C.cd[i];
   int nbx = C.nbx, nby = C.nby;
   static uint16_t hist[(2 * CSR + 1) * (2 * CSR + 1)];
   memset(hist, 0, sizeof hist);
   const int off[NCAND][2] = { {0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1} };
   for (int by = 0; by < nby; by++)
   for (int bx = 0; bx < nbx; bx++) {
      int k = by * nbx + bx;
      if (m->ok[k]) hist[(m->mv[k * 2 + 1] + CSR) * (2 * CSR + 1) + m->mv[k * 2] + CSR]++;
      cand_t *c = &cd[k]; c->n = 0;
      for (int o = 0; o < NCAND; o++) {
         int cx = bx + off[o][0], cy = by + off[o][1];
         if (cx < 0 || cy < 0 || cx >= nbx || cy >= nby) continue;
         int kk = cy * nbx + cx;
         if (!m->ok[kk]) continue;
         int dx = m->mv[kk * 2], dy = m->mv[kk * 2 + 1];
         if (!dx && !dy) continue;
         int16_t v = pk(dx, dy); int dup = 0;
         for (int t = 0; t < c->n; t++) if (c->v[t] == v) dup = 1;
         if (!dup) c->v[c->n++] = v;
      }
   }
   int bi = CSR * (2 * CSR + 1) + CSR;
   for (int t = 0; t < (2 * CSR + 1) * (2 * CSR + 1); t++) if (hist[t] > hist[bi]) bi = t;
   return pk(bi % (2 * CSR + 1) - CSR, bi / (2 * CSR + 1) - CSR);
}

static inline int cdiff(const uint8_t *a, const uint8_t *b)
{ return iabs(a[0] - b[0]) + iabs(a[1] - b[1]) + iabs(a[2] - b[2]); }

/* 3×3 밝기 차(가장자리는 붙임) — 한 화소로는 같은 색이라 못 가를 때 */
static int sad3(const uint8_t *la, const uint8_t *lb, int x, int y, int dx, int dy)
{
   int w = C.w, h = C.h, s = 0;
   for (int j = -1; j <= 1; j++)
   for (int i = -1; i <= 1; i++) {
      int bx = x + i, by = y + j;
      bx = bx < 0 ? 0 : (bx >= w ? w - 1 : bx); by = by < 0 ? 0 : (by >= h ? h - 1 : by);
      int ax = bx - dx, ay = by - dy;
      ax = ax < 0 ? 0 : (ax >= w ? w - 1 : ax); ay = ay < 0 ? 0 : (ay >= h ? h - 1 : ay);
      s += iabs((int)lb[by * w + bx] - (int)la[ay * w + ax]);
   }
   return s;
}

/* 화소마다 «어디서 왔나»: 후보 = 제 블록 벡터(0 이어도)·정지·이웃 넷. 한 화소 색이 C_SAME 안에 드는 후보가 여럿이면
   3×3 밝기 차가 가장 작은 것(제자리에 선 몸의 테두리가 배경 움직임에 딸려 가지 않게), 그래도 같으면(민무늬 안쪽)
   제 블록 벡터 → 정지 → 이웃 순(그 덩어리가 움직이는 대로 — 프레임마다 가는 것을 «멈춤»으로 잘못 읽지 않게). 없으면 LUNK. */
static void labels(int i)
{
   const uint8_t *a = C.f[i], *b = C.f[i + 1], *la = C.lum[i], *lb = C.lum[i + 1];
   int16_t *lab = C.lab[i]; const cand_t *cd = C.cd[i]; const cfield *m = &C.m[i];
   int w = C.w, h = C.h;
   for (int y = 0; y < h; y++) {
      const cand_t *crow = cd + (y / CBS) * C.nbx;
      for (int x = 0; x < w; x++) {
         int k = (y / CBS) * C.nbx + x / CBS;
         const cand_t *c = crow + x / CBS;
         const uint8_t *q = b + (y * w + x) * 4;
         int16_t own = m->ok[k] ? pk(m->mv[k * 2], m->mv[k * 2 + 1]) : 0;
         int16_t okv[NCAND + 1]; int nok = 0;
         for (int t = -1; t < c->n; t++) {          /* -1 = 정지 */
            int16_t v = t < 0 ? 0 : c->v[t];
            int sx = x - ux(v), sy = y - uy(v);
            if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
            if (cdiff(a + (sy * w + sx) * 4, q) <= C_SAME) okv[nok++] = v;
         }
         int16_t best = LUNK;
         if (nok == 1) best = okv[0];
         else if (nok > 1) {
            int bs = 1 << 30, bp = 9;
            for (int t = 0; t < nok; t++) {
               int s = sad3(la, lb, x, y, ux(okv[t]), uy(okv[t]));
               int p = okv[t] == own ? 0 : (okv[t] == 0 ? 1 : 2);
               if (s < bs || (s == bs && p < bp)) { bs = s; bp = p; best = okv[t]; }
            }
         }
         lab[y * w + x] = best;
      }
   }
}

/* 쉬는 쪽: F(n+1) 화소 s 가 n→n+1 에 멈췄고(lab[0]=0) n+1→n+2 에 v 로 움직인다(lab[1][s+v]=v) */
/* 쉬는 쪽: F(n+1) 화소 s 가 n→n+1 에 멈췄고(lab[0]=0) n+1→n+2 에 v 로 움직인다(lab[1][s+v]=v).
   n→n+1 에 막 드러난 화소(lab[0]=LUNK — 앞의 몸이 비켜 보이게 된 배경)는 그 걸음에 배경이 멈춰 있었고(d0=0)
   다음 걸음에 배경 움직임(d1)대로 가면 배경의 쉬는 쪽으로 친다(표가 0 이 아닌 화소까지 넓히면 프레임마다 배경과
   같은 벡터로 가는 몸을 쉬는 쪽으로 잘못 읽는다 — 시험에서 확인). */
static void hold_side(void)
{
   int w = C.w, h = C.h;
   const int16_t *l0 = C.lab[0], *l1 = C.lab[1]; const cand_t *cd = C.cd[1];
   int16_t d0 = C.dom[0], d1 = C.dom[1];
   for (int y = 0; y < h; y++)
   for (int x = 0; x < w; x++) {
      int i = y * w + x; int16_t r = 0;
      if (l0[i] == 0) {
         const cand_t *c = cd + (y / CBS) * C.nbx + x / CBS;
         for (int t = 0; t < c->n; t++) {
            int tx = x + ux(c->v[t]), ty = y + uy(c->v[t]);
            if (tx < 0 || ty < 0 || tx >= w || ty >= h) continue;
            if (l1[ty * w + tx] == c->v[t]) { r = c->v[t]; break; }
         }
      }
      if (!r && l0[i] == LUNK && d0 == 0 && d1 != 0) {
         int tx = x + ux(d1), ty = y + uy(d1);
         if (tx >= 0 && ty >= 0 && tx < w && ty < h && l1[ty * w + tx] == d1) r = d1;
      }
      C.hv[i] = r;
   }
}

static void luma(const uint8_t *rgba, uint8_t *out, int n)
{ for (int i = 0; i < n; i++, rgba += 4) out[i] = (uint8_t)((rgba[0] * 77 + rgba[1] * 150 + rgba[2] * 29) >> 8); }

void fg_cad_reset(void) { C.n = 0; C.hvA_ok = 0; }

int fg_cad_ready(void) { return C.n >= CN; }

int fg_cad_push(const uint8_t *rgba, int w, int h)
{
   if (!ensure(w, h)) return 0;
   int px = w * h;
   if (C.n >= CN) {                       /* 가장 옛 장을 버리고 한 칸씩 당긴다(버퍼는 돌려 씀) */
      uint8_t *f0 = C.f[0], *l0 = C.lum[0];
      for (int i = 0; i < CN - 1; i++) { C.f[i] = C.f[i + 1]; C.lum[i] = C.lum[i + 1]; }
      C.f[CN - 1] = f0; C.lum[CN - 1] = l0;
      cfield m0 = C.m[0]; cand_t *c0 = C.cd[0]; int16_t *b0 = C.lab[0];
      for (int i = 0; i < CN - 2; i++) { C.m[i] = C.m[i + 1]; C.cd[i] = C.cd[i + 1]; C.lab[i] = C.lab[i + 1]; C.dom[i] = C.dom[i + 1]; }
      C.m[CN - 2] = m0; C.cd[CN - 2] = c0; C.lab[CN - 2] = b0;
      int16_t *t = C.hvA; C.hvA = C.hv; C.hv = t;   /* 지난번 F(n+1) 의 쉬는 쪽 = 이번 F(n) 의 것 */
      C.hvA_ok = 1;
      C.n = CN - 1;
   }
   int j = C.n;
   memcpy(C.f[j], rgba, (size_t)px * 4);
   luma(C.f[j], C.lum[j], px);
   if (j > 0) {                           /* 새 걸음 f[j-1] → f[j] */
      csearch(C.lum[j - 1], C.lum[j], &C.m[j - 1], j > 1 ? &C.m[j - 2] : NULL);
      C.dom[j - 1] = cands_and_dom(j - 1);
      labels(j - 1);
   }
   C.n = j + 1;
   if (C.n >= CN) hold_side();
   return C.n >= CN;
}

/* 출력 화소(S배 좌표 X,Y)에서 «벡터 v 를 k/4 만큼 옮긴» 원본의 1배 자리 */
static inline void src_of(int X, int Y, int S, int k, int16_t v, int *sx, int *sy)
{
   if (S == 1) { *sx = X - (k * ux(v)) / 4; *sy = Y - (k * uy(v)) / 4; }      /* 1배: 0 쪽으로 자름 — framegen.c 와 같음 */
   else { *sx = floordiv(X * 4 / S - k * ux(v), 4); *sy = floordiv(Y * 4 / S - k * uy(v), 4); }
}

static inline void put(uint8_t *o, const uint8_t *c) { o[0] = c[0]; o[1] = c[1]; o[2] = c[2]; o[3] = 0xff; }
#ifdef FG_CAD_DEBUG
uint8_t *fg_cad_dbg;   /* 시험용: 출력 화소마다 어느 규칙이 골랐나(10 = 빠른 길) */
int fg_cad_slow_only;  /* 시험용: 빠른 길을 끄고 화소마다(같은 그림인지 대조) */
#define DBGI(i, v) do { if (fg_cad_dbg) fg_cad_dbg[i] = (v); } while (0)
#else
#define DBGI(i, v) do { } while (0)
#endif

/* F(n+1) 의 q 내용이 n+1→n+2 에 배경 움직임 d 대로 가지 «않았으면» 0(앞), 갔거나 화면 밖이면 1(뒤) */
static inline int bg_left(int x, int y, int16_t d)
{
   int tx = x + ux(d), ty = y + uy(d);
   if (tx < 0 || ty < 0 || tx >= C.w || ty >= C.h) return 1;
   return C.lab[1][ty * C.w + tx] == d ? 1 : 0;
}

/* 느린 길 — 출력 화소 하나(S배 좌표 X,Y)를 규칙대로 고른다. c0·c1 = 그 화소 블록의 후보. */
static inline const uint8_t *pick_px(int slot, int X, int Y, int S, const cand_t *c0, const cand_t *c1, int di)
{
   const uint8_t *f0 = C.f[0], *f1 = C.f[1], *f2 = C.f[2];
   const int16_t *hv = C.hv, *hvA = C.hvA, *l0 = C.lab[0];
   int w = C.w, h = C.h, x = X / S, y = Y / S, qi = y * w + x, sx, sy;
   int16_t d0 = C.dom[0], d1 = C.dom[1];
   const uint8_t *pick = NULL; int prank = 9;
   (void)di;
   if (slot == 0) {
      /* (가) 쉬는 쪽을 n+1→n+2 걸음의 ½ 만큼 */
      for (int t = 0; t < c1->n && prank > 0; t++) {
         int16_t v = c1->v[t];
         src_of(X, Y, S, 2, v, &sx, &sy);
         if (sx < 0 || sy < 0 || sx >= w || sy >= h || hv[sy * w + sx] != v) continue;
         int r = (v == d1);
         if (r < prank) { prank = r; pick = f1 + (sy * w + sx) * 4; DBGI(di, 7); }
      }
      /* (나) 쉬는 쪽이 아닌 것은 제자리. 앞(캐릭터·HUD)인지: 가장 흔한 움직임(배경)대로 가지 않는 것.
             배경이 다음 걸음에 움직이는데(d1≠0) 이 화소 내용이 그 걸음으로 안 갔으면 앞 — 화면 밖으로 나가는 배경은 뒤.
             둘 다 앞이면 옮겨 온 쪽 */
      if (!hv[qi]) {
         int r = d1 == 0 ? 1 : bg_left(x, y, d1);
         if (!pick || r < prank) { prank = r; pick = f1 + qi * 4; DBGI(di, 8); }
      }
      if (!pick) { pick = f2 + qi * 4; DBGI(di, 9); }   /* 쉬는 쪽이 비우고 간 자리 — 그 뒤에 드러날 것 */
      return pick;
   }
   /* (가) n 에 쉬던 것: n→n+1 걸음의 ¾ */
   if (C.hvA_ok)
      for (int t = 0; t < c0->n && prank > 0; t++) {
         int16_t v = c0->v[t];
         src_of(X, Y, S, 3, v, &sx, &sy);
         if (sx < 0 || sy < 0 || sx >= w || sy >= h || hvA[sy * w + sx] != v) continue;
         int r = (v == d0);
         if (r < prank) { prank = r; pick = f0 + (sy * w + sx) * 4; DBGI(di, 1); }
      }
   /* (다) n+1 에 쉴 것: n+1→n+2 걸음의 ¼ */
   for (int t = 0; t < c1->n && prank > 0; t++) {
      int16_t v = c1->v[t];
      src_of(X, Y, S, 1, v, &sx, &sy);
      if (sx < 0 || sy < 0 || sx >= w || sy >= h || hv[sy * w + sx] != v) continue;
      int r = (v == d1);
      if (r < prank) { prank = r; pick = f1 + (sy * w + sx) * 4; DBGI(di, 2); }
   }
   /* (나) 그 밖에 n→n+1 에 움직이는 것(60Hz·첫걸음): ½ */
   for (int t = 0; t < c0->n && prank > 0; t++) {
      int16_t v = c0->v[t];
      src_of(X, Y, S, 2, v, &sx, &sy);
      if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
      int tx = sx + ux(v), ty = sy + uy(v);
      if (tx < 0 || ty < 0 || tx >= w || ty >= h || l0[ty * w + tx] != v) continue;
      if (C.hvA_ok && hvA[sy * w + sx] == v) continue;
      int r = (v == d0);
      if (r < prank) { prank = r; pick = f0 + (sy * w + sx) * 4; DBGI(di, 3); }
   }
   /* (라) 멈춘 것 — n→n+1 에 배경이 움직였는데 멈춰 있으면 앞, 배경이 n+1→n+2 에 움직이면 (나)와 같은 시험 */
   if (prank > 0 && l0[qi] == 0 && !hv[qi]) {
      int r = d0 != 0 ? 0 : (d1 != 0 ? bg_left(x, y, d1) : 1);
      if (r < prank) { prank = r; pick = f1 + qi * 4; DBGI(di, 4); }
   }
   if (!pick) { pick = hv[qi] ? f2 + qi * 4 : f1 + qi * 4; DBGI(di, hv[qi] ? 5 : 6); }   /* 비우고 간 자리 / 가림·모양 바뀜 — 다음 그림 */
   return pick;
}

/* 한 축의 원본 창: 출력 [a0·S, (a0+len)·S) 를 k/4·v 만큼 옮겼을 때의 1배 원본 [*s0, *s1]. 화면 밖이면 0. */
static inline int swin(int S, int k, int v, int a0, int len, int lim, int *s0, int *s1)
{
   if (S == 1) { int t = (k * v) / 4; *s0 = a0 - t; *s1 = a0 + len - 1 - t; }
   else { *s0 = floordiv(a0 * 4 - k * v, 4); *s1 = floordiv((a0 + len) * 4 - 1 - k * v, 4); }
   return *s0 >= 0 && *s1 < lim;
}

/* 네모 [x0..x1]×[y0..y1](안쪽) 이 모두 v 인가 / 하나도 v 가 아닌가 */
static int rect_all(const int16_t *a, int x0, int y0, int x1, int y1, int16_t v)
{
   for (int y = y0; y <= y1; y++) { const int16_t *r = a + y * C.w; for (int x = x0; x <= x1; x++) if (r[x] != v) return 0; }
   return 1;
}
static int rect_none(const int16_t *a, int x0, int y0, int x1, int y1, int16_t v)
{
   for (int y = y0; y <= y1; y++) { const int16_t *r = a + y * C.w; for (int x = x0; x <= x1; x++) if (r[x] == v) return 0; }
   return 1;
}

/* 블록을 «f 를 k/4·v 만큼 옮긴 것»으로 통째 채운다(4배에서 원본 줄이 같은 출력 줄은 베낌) */
static void blk_shift(uint8_t *out, int S, int x0, int y0, int bw, int bh, const uint8_t *f, int k, int16_t v)
{
   int w = C.w, W = w * S, vx = ux(v), vy = uy(v), prev_sy = -1;
   uint8_t *prev_o = NULL;
   for (int Y = y0 * S; Y < (y0 + bh) * S; Y++) {
      int sy = S == 1 ? Y - (k * vy) / 4 : floordiv(Y * 4 / S - k * vy, 4);
      const uint8_t *src = f + (size_t)sy * w * 4;
      uint8_t *o = out + ((size_t)Y * W + x0 * S) * 4;
      if (S == 1) { memcpy(o, src + (x0 - (k * vx) / 4) * 4, (size_t)bw * 4); for (int X = 0; X < bw; X++) o[X * 4 + 3] = 0xff; continue; }
      if (sy == prev_sy) { memcpy(o, prev_o, (size_t)bw * 16); continue; }
      prev_sy = sy; prev_o = o;
      int X0 = x0 * 4, X1 = (x0 + bw) * 4;
      for (int X = X0; X < X1; X++, o += 4) { const uint8_t *c = src + ((X - k * vx) >> 2) * 4; o[0] = c[0]; o[1] = c[1]; o[2] = c[2]; o[3] = 0xff; }
   }
}

/* 블록을 1배 화소마다 한 색으로(옮겨 그릴 것이 없음): 쉬는 쪽이면 F(n+2)(비우고 간 자리), 아니면 F(n+1) */
static void blk_plain(uint8_t *out, int S, int x0, int y0, int bw, int bh)
{
   int w = C.w, W = w * S;
   for (int y = y0; y < y0 + bh; y++) {
      uint8_t *o0 = out + ((size_t)(y * S) * W + x0 * S) * 4, *o = o0;
      for (int x = x0; x < x0 + bw; x++) {
         int qi = y * w + x;
         const uint8_t *c = (C.hv[qi] ? C.f[2] : C.f[1]) + qi * 4;
         for (int i = 0; i < S; i++, o += 4) { o[0] = c[0]; o[1] = c[1]; o[2] = c[2]; o[3] = 0xff; }
      }
      for (int j = 1; j < S; j++) memcpy(o0 + (size_t)j * W * 4, o0, (size_t)bw * S * 4);
   }
}

/* 빠른 길이 되는 블록인지 — 되면 그리고 1. 느린 길(화소마다)과 똑같은 그림이 나오는 경우만. */
static int blk_fast(int slot, uint8_t *out, int S, int x0, int y0, int bw, int bh, const cand_t *c0, const cand_t *c1)
{
   int w = C.w, h = C.h, xa, xb, ya, yb, x1 = x0 + bw - 1, y1 = y0 + bh - 1;
   if (slot == 0) {
      if (c1->n == 0) { blk_plain(out, S, x0, y0, bw, bh); return 1; }
      if (c1->n == 1) {
         int16_t v = c1->v[0];
         if (!swin(S, 2, ux(v), x0, bw, w, &xa, &xb) || !swin(S, 2, uy(v), y0, bh, h, &ya, &yb)) return 0;
         if (rect_all(C.hv, xa, ya, xb, yb, v) && rect_all(C.hv, x0, y0, x1, y1, v)) { blk_shift(out, S, x0, y0, bw, bh, C.f[1], 2, v); return 1; }
         if (rect_all(C.hv, xa, ya, xb, yb, 0) && rect_all(C.hv, x0, y0, x1, y1, 0)) { blk_plain(out, S, x0, y0, bw, bh); return 1; }
      }
      return 0;
   }
   if (c0->n == 0 && c1->n == 0) { blk_plain(out, S, x0, y0, bw, bh); return 1; }
   if (c0->n == 1 && c1->n == 0 && C.hvA_ok) {          /* n 에 쉬던 배경이 ¾ */
      int16_t v = c0->v[0];
      if (swin(S, 3, ux(v), x0, bw, w, &xa, &xb) && swin(S, 3, uy(v), y0, bh, h, &ya, &yb)
          && rect_all(C.hvA, xa, ya, xb, yb, v) && rect_all(C.lab[0], x0, y0, x1, y1, v)) {
         blk_shift(out, S, x0, y0, bw, bh, C.f[0], 3, v); return 1;
      }
   }
   if (c1->n == 1 && c0->n == 0) {                        /* n+1 에 쉴 배경이 ¼ */
      int16_t v = c1->v[0];
      if (swin(S, 1, ux(v), x0, bw, w, &xa, &xb) && swin(S, 1, uy(v), y0, bh, h, &ya, &yb)
          && rect_all(C.hv, xa, ya, xb, yb, v) && rect_all(C.hv, x0, y0, x1, y1, v)) {
         blk_shift(out, S, x0, y0, bw, bh, C.f[1], 1, v); return 1;
      }
   }
   if (c0->n == 1 && (c1->n == 0 || (c1->n == 1 && c1->v[0] == c0->v[0]))) {   /* 프레임마다 가는 것이 ½ */
      int16_t v = c0->v[0]; int xa3, xb3, ya3, yb3;
      if (swin(S, 2, ux(v), x0, bw, w, &xa, &xb) && swin(S, 2, uy(v), y0, bh, h, &ya, &yb)
          && swin(S, 3, ux(v), x0, bw, w, &xa3, &xb3) && swin(S, 3, uy(v), y0, bh, h, &ya3, &yb3)
          && xa + ux(v) >= 0 && xb + ux(v) < w && ya + uy(v) >= 0 && yb + uy(v) < h
          && rect_all(C.lab[0], xa + ux(v), ya + uy(v), xb + ux(v), yb + uy(v), v)
          && (!C.hvA_ok || (rect_none(C.hvA, xa, ya, xb, yb, v) && rect_none(C.hvA, xa3, ya3, xb3, yb3, v)))
          && rect_all(C.lab[0], x0, y0, x1, y1, v)) {
         int ok = 1;
         if (c1->n) {                                     /* (다) ¼ 이 끼어들지 않게 */
            int xa1, xb1, ya1, yb1;
            if (!swin(S, 1, ux(c1->v[0]), x0, bw, w, &xa1, &xb1) || !swin(S, 1, uy(c1->v[0]), y0, bh, h, &ya1, &yb1)
                || !rect_none(C.hv, xa1, ya1, xb1, yb1, c1->v[0])) ok = 0;
         }
         if (ok) { blk_shift(out, S, x0, y0, bw, bh, C.f[0], 2, v); return 1; }
      }
   }
   return 0;
}

/* slot 0 = G(n+1)(실제 프레임 칸), slot 1 = n·½ 칸. out 은 (w·S)×(h·S) RGBA. S = 4 또는 1. */
void fg_cad_render(int slot, uint8_t *out, int S)
{
   if (C.n < CN || (S != 1 && S != 4)) return;
   int w = C.w, h = C.h, W = w * S;
   for (int by = 0; by < C.nby; by++)
   for (int bx = 0; bx < C.nbx; bx++) {
      int k = by * C.nbx + bx, x0 = bx * CBS, y0 = by * CBS;
      int bw = x0 + CBS <= w ? CBS : w - x0, bh = y0 + CBS <= h ? CBS : h - y0;
      const cand_t *c0 = &C.cd[0][k], *c1 = &C.cd[1][k];
#ifdef FG_CAD_DEBUG
      if (!fg_cad_slow_only)
#endif
      if (blk_fast(slot, out, S, x0, y0, bw, bh, c0, c1)) {
#ifdef FG_CAD_DEBUG
         if (fg_cad_dbg) for (int Y = y0 * S; Y < (y0 + bh) * S; Y++) memset(fg_cad_dbg + (size_t)Y * W + x0 * S, 10, (size_t)bw * S);
#endif
         continue;
      }
      /* 느린 길 — 후보가 모두 가로로만 가면(세로 0) 4배의 네 줄이 같아 한 줄만 고르고 베낀다 */
      int rows_same = S > 1;
      for (int t = 0; t < c0->n; t++) if (uy(c0->v[t])) rows_same = 0;
      for (int t = 0; t < c1->n; t++) if (uy(c1->v[t])) rows_same = 0;
#ifdef FG_CAD_DEBUG
      if (fg_cad_dbg) rows_same = 0;
#endif
      for (int Y = y0 * S; Y < (y0 + bh) * S; Y++) {
         uint8_t *o = out + ((size_t)Y * W + x0 * S) * 4;
         if (rows_same && (Y % S)) { memcpy(o, o - (size_t)W * 4, (size_t)bw * S * 4); continue; }
         for (int X = x0 * S; X < (x0 + bw) * S; X++, o += 4) put(o, pick_px(slot, X, Y, S, c0, c1, Y * W + X));
      }
   }
}

void fg_cad_free(void) { free_bufs(); C.w = C.h = 0; }

#ifdef FG_CAD_DEBUG
#include <stdio.h>
void fg_cad_dbg_px(int x, int y)
{
   int i = y * C.w + x, k = (y / CBS) * C.nbx + x / CBS;
   printf("      px(%d,%d) l0=%04x l1=%04x hv=%04x hvA=%04x | m0 blk ok%d (%d,%d) m1 blk ok%d (%d,%d) | c0:", x, y,
          (uint16_t)C.lab[0][i], (uint16_t)C.lab[1][i], (uint16_t)C.hv[i], (uint16_t)C.hvA[i],
          C.m[0].ok[k], C.m[0].mv[k * 2], C.m[0].mv[k * 2 + 1], C.m[1].ok[k], C.m[1].mv[k * 2], C.m[1].mv[k * 2 + 1]);
   for (int t = 0; t < C.cd[0][k].n; t++) printf(" (%d,%d)", ux(C.cd[0][k].v[t]), uy(C.cd[0][k].v[t]));
   printf(" c1:");
   for (int t = 0; t < C.cd[1][k].n; t++) printf(" (%d,%d)", ux(C.cd[1][k].v[t]), uy(C.cd[1][k].v[t]));
   printf(" dom0 (%d,%d) dom1 (%d,%d)\n", ux(C.dom[0]), uy(C.dom[0]), ux(C.dom[1]), uy(C.dom[1]));
}
#endif
