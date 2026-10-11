/* 프레임 생성(중간 프레임 보간) — 120Hz 화면용.
 *
 * NGPC 는 60.25fps 라 120Hz 폰에서는 vsync 두 번에 코어 프레임 하나가 나온다.
 * 그 빈 vsync 에 «두 프레임 사이» 그림을 만들어 끼워 넣으면 스크롤·캐릭터 이동이
 * 두 배 촘촘하게 보인다. 표시 순서: 중간(n-1→n) · n · 중간(n→n+1) · n+1 …
 * 대가: 표시가 반 프레임(약 8ms) 늦어진다 — 격투게임이라 끌 수 있게 둔다.
 *
 * 두 방식:
 *   FG_BLEND  — 두 프레임을 반반 섞는다. 싸고 단순하지만 움직이는 것엔 잔상이 진다.
 *   FG_MOTION — 8×8 블록마다 움직임(벡터)을 찾아 «반만큼 옮긴» 그림을 만든다.
 *               도트 그림이라 섞지 않고 옮기는 게 핵심이다(번지지 않는다).
 *               블록 경계에서 배경이 따라 끌려가는 것을 막으려고 픽셀마다
 *               {정지, 제 블록, 이웃 넷} 후보 중 앞뒤 프레임이 «가장 잘 맞는» 것을 고른다.
 *
 * 전부 CPU, 160×152 기준 수 ms 미만. GL 쪽은 결과 버퍼를 텍스처로 올리기만 한다.
 * 이 파일은 안드로이드 의존이 없다 — 호스트에서 그대로 컴파일해 시험한다(tools/fg_test.c). */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "framegen.h"

#define BS 8          /* 블록 한 변 */
#define SR 6          /* 탐색 반경(px) — NGPC 캐릭터·스크롤은 한 프레임에 대개 이 안 */
#define BAD_SAD 24    /* 블록 픽셀당 평균 차가 이보다 크면 «못 찾음» → 그 블록은 현재 프레임 */
#define SAME_TH 24    /* 두 샘플이 이 정도로 가까우면 같은 색으로 보고 평균 */

static uint8_t *g_lp = NULL, *g_lc = NULL;   /* 휘도(밝기)판 — 탐색용 */
static int8_t  *g_mv = NULL;                 /* 블록별 벡터 (x,y) 쌍 */
static uint8_t *g_ok = NULL;                 /* 블록별 신뢰 */
static int g_cap_px = 0, g_cap_bk = 0;

static uint8_t *g_amb = NULL;      /* 정지(0)도 «완전히 맞는» 블록 — 디더·민무늬라 움직임을 스스로 못 정한다 */
static int g_amb_cap = 0;

static int ensure(int w, int h)
{
   int px = w * h, bk = ((w + BS - 1) / BS) * ((h + BS - 1) / BS);
   if (px > g_cap_px) {
      free(g_lp); free(g_lc);
      g_lp = (uint8_t *)malloc((size_t)px); g_lc = (uint8_t *)malloc((size_t)px);
      g_cap_px = (g_lp && g_lc) ? px : 0;
   }
   if (bk > g_cap_bk) {
      free(g_mv); free(g_ok);
      g_mv = (int8_t *)malloc((size_t)bk * 2); g_ok = (uint8_t *)malloc((size_t)bk);
      g_cap_bk = (g_mv && g_ok) ? bk : 0;
   }
   if (bk > g_amb_cap) {
      free(g_amb);
      g_amb = (uint8_t *)malloc((size_t)bk);
      g_amb_cap = g_amb ? bk : 0;
   }
   return g_cap_px >= px && g_cap_bk >= bk;
}

static void luma(const uint8_t *rgba, uint8_t *out, int n)
{
   for (int i = 0; i < n; i++, rgba += 4)
      out[i] = (uint8_t)((rgba[0] * 77 + rgba[1] * 150 + rgba[2] * 29) >> 8);
}

static inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

/* cur 의 블록(x0,y0)이 prev 의 (x0-dx, y0-dy) 에서 왔다고 볼 때의 차. limit 넘으면 바로 포기. */
static int sad(const uint8_t *lp, const uint8_t *lc, int w, int h,
               int x0, int y0, int bw, int bh, int dx, int dy, int limit)
{
   int s = 0;
   for (int y = 0; y < bh; y++) {
      int cy = y0 + y, py = cy - dy;
      if (py < 0 || py >= h) { s += 255 * bw; if (s >= limit) return s; continue; }
      const uint8_t *rc = lc + cy * w + x0, *rp = lp + py * w;
      for (int x = 0; x < bw; x++) {
         int px = x0 + x - dx;
         int d = (px < 0 || px >= w) ? 255 : (int)rc[x] - (int)rp[px];
         s += d < 0 ? -d : d;
      }
      if (s >= limit) return s;
   }
   return s;
}

static int8_t *g_mv_last = NULL;   /* 지난 프레임의 블록 벡터 — 예측 후보 */
static int g_last_bk = 0;

static void search(int w, int h)
{
   int nbx = (w + BS - 1) / BS, nby = (h + BS - 1) / BS, nb = nbx * nby;
   if (g_last_bk != nb) {
      free(g_mv_last);
      g_mv_last = (int8_t *)calloc((size_t)nb * 2, 1);
      g_last_bk = g_mv_last ? nb : 0;
   }
   for (int by = 0; by < nby; by++)
   for (int bx = 0; bx < nbx; bx++) {
      int x0 = bx * BS, y0 = by * BS, k = by * nbx + bx;
      int bw = (x0 + BS <= w) ? BS : w - x0, bh = (y0 + BS <= h) ? BS : h - y0;
      int best = sad(g_lp, g_lc, w, h, x0, y0, bw, bh, 0, 0, 1 << 30), bdx = 0, bdy = 0;
      if (g_amb) g_amb[k] = (best == 0);
      /* 1) 예측 후보 — 왼쪽·위 블록, 지난 프레임의 같은 블록. 스크롤·같은 물체는 대개 여기서 끝난다.
            «완전히 같으면» 바로 받는다(전수 탐색 생략 — 폰에서 시간 대부분이 여기서 준다). */
      if (best > 0) {
         int pc[3][2], np = 0;
         if (bx > 0) { pc[np][0] = g_mv[(k-1)*2]; pc[np][1] = g_mv[(k-1)*2+1]; np++; }
         if (by > 0) { pc[np][0] = g_mv[(k-nbx)*2]; pc[np][1] = g_mv[(k-nbx)*2+1]; np++; }
         if (g_last_bk) { pc[np][0] = g_mv_last[k*2]; pc[np][1] = g_mv_last[k*2+1]; np++; }
         for (int i = 0; i < np && best > 0; i++) {
            int dx = pc[i][0], dy = pc[i][1];
            if (!dx && !dy) continue;
            int pen = (abs(dx) + abs(dy)) * 2;
            int s = sad(g_lp, g_lc, w, h, x0, y0, bw, bh, dx, dy, best);
            if (s == 0) { best = 0; bdx = dx; bdy = dy; }
            else if (s + pen < best) { best = s + pen; bdx = dx; bdy = dy; }
         }
      }
      /* 2) 전수 탐색 — 정지에 약간 가산점(무늬 없는 평면에서 엉뚱한 벡터가 이기지 않게) */
      if (best > 0) {
         for (int dy = -SR; dy <= SR; dy++)
         for (int dx = -SR; dx <= SR; dx++) {
            if (!dx && !dy) continue;
            int pen = (abs(dx) + abs(dy)) * 2;
            int lim = best - pen;
            if (lim <= 0) continue;
            int s = sad(g_lp, g_lc, w, h, x0, y0, bw, bh, dx, dy, lim);
            if (s + pen < best) { best = s + pen; bdx = dx; bdy = dy; if (s == 0) goto found; }
         }
      }
   found:
      g_mv[k * 2] = (int8_t)bdx; g_mv[k * 2 + 1] = (int8_t)bdy;
      g_ok[k] = best <= BAD_SAD * bw * bh;
   }
   /* 모호한 블록은 이웃의 움직임을 빌린다 — 체크 디더 바닥이 2px 스크롤되면 정지도 완전히 맞아
      그 블록만 멈춰 있고 이웃 바닥은 움직여, 중간 그림에서 바닥이 자글자글 깨졌다.
      «모호하지 않은» 이웃(무늬가 있어 움직임이 확실한 블록)의 다수 벡터가 이 블록에도 완전히 맞으면 그걸 쓴다.
      정지 HUD 처럼 이웃이 다 0 이면 0 그대로라 멀쩡한 정지 디더를 흔들지 않는다. 두 번 돌려 안쪽까지 번지게. */
   if (g_amb) for (int pass = 0; pass < 2; pass++)
   for (int by = 0; by < nby; by++)
   for (int bx = 0; bx < nbx; bx++) {
      int k = by * nbx + bx;
      if (!g_amb[k]) continue;
      int nk[4], nn = 0;
      if (bx > 0) nk[nn++] = k - 1;
      if (bx < nbx - 1) nk[nn++] = k + 1;
      if (by > 0) nk[nn++] = k - nbx;
      if (by < nby - 1) nk[nn++] = k + nbx;
      int bestc = 0, mx = 0, my = 0;
      for (int i = 0; i < nn; i++) {
         if (g_amb[nk[i]]) continue;
         int vx = g_mv[nk[i] * 2], vy = g_mv[nk[i] * 2 + 1], c = 0;
         for (int j = 0; j < nn; j++)
            if (!g_amb[nk[j]] && g_mv[nk[j] * 2] == vx && g_mv[nk[j] * 2 + 1] == vy) c++;
         if (c > bestc) { bestc = c; mx = vx; my = vy; }
      }
      if (bestc < 2 || (!mx && !my)) continue;   /* 이웃 «둘 이상»이 같은 벡터일 때만 — 캐릭터 한 블록 벡터를 옆 정지 바닥이 빌려 가지 않게 */
      int x0 = bx * BS, y0 = by * BS;
      int bw = (x0 + BS <= w) ? BS : w - x0, bh = (y0 + BS <= h) ? BS : h - y0;
      if (sad(g_lp, g_lc, w, h, x0, y0, bw, bh, mx, my, 1) == 0) {
         g_mv[k * 2] = (int8_t)mx; g_mv[k * 2 + 1] = (int8_t)my;
         g_amb[k] = 0;                      /* 정해졌다 — 다음 판에서 이웃에게 빌려줄 수 있다 */
      }
   }
   if (g_last_bk == nb) memcpy(g_mv_last, g_mv, (size_t)nb * 2);
}

static inline int cdiff(const uint8_t *a, const uint8_t *b)
{
   return abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2]);
}

void fg_build(const uint8_t *prev, const uint8_t *cur, uint8_t *out, int w, int h, int mode)
{
   int n = w * h;
   if (mode == FG_BLEND || !ensure(w, h)) {
      for (int i = 0; i < n * 4; i++) out[i] = (uint8_t)((prev[i] + cur[i] + 1) >> 1);
      return;
   }
   luma(prev, g_lp, n);
   luma(cur, g_lc, n);
   search(w, h);

   int nbx = (w + BS - 1) / BS, nby = (h + BS - 1) / BS;
   for (int y = 0; y < h; y++) {
      int by = y / BS;
      for (int x = 0; x < w; x++) {
         int bx = x / BS, k = by * nbx + bx;
         uint8_t *o = out + (y * w + x) * 4;
         if (!g_ok[k]) { memcpy(o, cur + (y * w + x) * 4, 4); continue; }
         /* 후보: 정지 · 제 블록 · 상하좌우 이웃 블록 */
         int cand[6][2], nc = 0;
         /* 제 블록 벡터가 «먼저» — 동점이면 먼저 것이 이긴다. 정지(0)를 먼저 두면
            NGPC 체크 디더(2px 주기)가 2px 스크롤될 때 정지도 «완전히 맞아» 그 픽셀만 안 움직이고
            이웃은 움직여 자글자글한 노이즈가 됐다(실기 제보 2026-10-10 「움직임 — 노이즈 같은 게」). */
         cand[nc][0] = g_mv[k * 2]; cand[nc][1] = g_mv[k * 2 + 1]; nc++;
         if (g_mv[k * 2] || g_mv[k * 2 + 1]) { cand[nc][0] = 0; cand[nc][1] = 0; nc++; }
         if (bx > 0)       { int j = k - 1;   cand[nc][0] = g_mv[j*2]; cand[nc][1] = g_mv[j*2+1]; nc++; }
         if (bx < nbx - 1) { int j = k + 1;   cand[nc][0] = g_mv[j*2]; cand[nc][1] = g_mv[j*2+1]; nc++; }
         if (by > 0)       { int j = k - nbx; cand[nc][0] = g_mv[j*2]; cand[nc][1] = g_mv[j*2+1]; nc++; }
         if (by < nby - 1) { int j = k + nbx; cand[nc][0] = g_mv[j*2]; cand[nc][1] = g_mv[j*2+1]; nc++; }
         int bestd = 1 << 30; const uint8_t *bp = NULL, *bc = NULL;
         for (int c = 0; c < nc; c++) {
            int dx = cand[c][0], dy = cand[c][1];
            /* 물체가 prev 의 p 에서 cur 의 p+mv 로 갔다면 중간 그림의 p+a 자리에는
               prev(x-a) 와 cur(x+b) 가 같은 것을 가리킨다 (a = mv/2, b = mv-a). */
            int ax = dx / 2, ay = dy / 2, bxv = dx - ax, byv = dy - ay;
            const uint8_t *p = prev + (clampi(y - ay, 0, h - 1) * w + clampi(x - ax, 0, w - 1)) * 4;
            const uint8_t *q = cur  + (clampi(y + byv, 0, h - 1) * w + clampi(x + bxv, 0, w - 1)) * 4;
            int d = cdiff(p, q);
            if (d < bestd) { bestd = d; bp = p; bc = q; if (!d) break; }
         }
         if (bestd <= SAME_TH) {
            o[0] = (uint8_t)((bp[0] + bc[0] + 1) >> 1);
            o[1] = (uint8_t)((bp[1] + bc[1] + 1) >> 1);
            o[2] = (uint8_t)((bp[2] + bc[2] + 1) >> 1);
            o[3] = 0xff;
         } else {
            /* 가림·드러남(앞뒤가 안 맞는 자리) — 섞으면 잔상이니 현재 프레임을 그대로 */
            memcpy(o, cur + (y * w + x) * 4, 4);
         }
      }
   }
}

void fg_free(void)
{
   free(g_lp); free(g_lc); free(g_mv); free(g_ok); free(g_mv_last); free(g_amb); g_amb = NULL; g_amb_cap = 0;
   g_lp = g_lc = NULL; g_mv = NULL; g_ok = NULL; g_mv_last = NULL;
   g_cap_px = g_cap_bk = 0; g_last_bk = 0;
}
