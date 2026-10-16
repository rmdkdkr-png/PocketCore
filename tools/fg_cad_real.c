/* «박자» 모드 — 실제 게임 프레임(1배 PPM 연속)으로 돌려 보기.
 * gcc -O2 -I app/src/main/cpp tools/fg_cad_real.c app/src/main/cpp/fg_cadence.c app/src/main/cpp/framegen.c -o fg_cad_real
 * ./fg_cad_real <패턴 예: dir/f_%05d.ppm> <첫> <끝> <출력폴더|-> [줄 위] [줄 아래]
 *   120Hz 로 보이는 순서(G(k), k½, G(k+1) …)대로, 앞 장과 비교한 배경 가로 이동(¼px 단위)을 줄 범위에서 잰다:
 *   그대로(실제 프레임 두 번씩) · 움직임(2배 1배 그림을 4배로) · 박자(4배).  출력폴더를 주면 박자 4배 그림을 PPM 으로. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "framegen.h"
#define S 4
static int W, H;
static uint8_t *load(const char *fn) {
  FILE *f = fopen(fn, "rb"); if (!f) return NULL;
  int w, h, mx; if (fscanf(f, "P6 %d %d %d", &w, &h, &mx) != 3) { fclose(f); return NULL; } fgetc(f);
  W = w; H = h; uint8_t *rgb = malloc((size_t)w * h * 3), *o = malloc((size_t)w * h * 4);
  if (fread(rgb, 1, (size_t)w * h * 3, f) != (size_t)w * h * 3) { fclose(f); free(rgb); free(o); return NULL; }
  fclose(f);
  for (int i = 0; i < w * h; i++) { o[i * 4] = rgb[i * 3]; o[i * 4 + 1] = rgb[i * 3 + 1]; o[i * 4 + 2] = rgb[i * 3 + 2]; o[i * 4 + 3] = 255; }
  free(rgb); return o;
}
static void up4(const uint8_t *a, uint8_t *o) {
  for (int Y = 0; Y < H * S; Y++) for (int X = 0; X < W * S; X++) memcpy(o + ((size_t)Y * W * S + X) * 4, a + ((Y / S) * W + X / S) * 4, 4);
}
/* 4배 그림 a→b 의 가로 이동(b 가 a 를 dx 만큼 옮긴 것) — 줄 [y0,y1)(1배) 에서 SAD 가장 작은 dx(-16..16) */
static int hshift(const uint8_t *a, const uint8_t *b, int y0, int y1) {
  int WW = W * S, best = 0; long bs = -1;
  for (int dx = -16; dx <= 16; dx++) {
    long s = 0;
    for (int Y = y0 * S; Y < y1 * S; Y += 2)
      for (int X = 24; X < WW - 24; X++) {
        const uint8_t *p = b + ((size_t)Y * WW + X) * 4, *q = a + ((size_t)Y * WW + X - dx) * 4;
        s += abs(p[0] - q[0]) + abs(p[1] - q[1]) + abs(p[2] - q[2]);
      }
    if (bs < 0 || s < bs) { bs = s; best = dx; }
  }
  return best;
}
static void wppm(const char *fn, const uint8_t *o, int w, int h) {
  FILE *f = fopen(fn, "wb"); fprintf(f, "P6\n%d %d\n255\n", w, h);
  for (int i = 0; i < w * h; i++) fwrite(o + i * 4, 1, 3, f);
  fclose(f);
}
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }
int main(int argc, char **argv) {
  if (argc < 5) { fprintf(stderr, "usage\n"); return 1; }
  const char *pat = argv[1]; int a = atoi(argv[2]), b = atoi(argv[3]); const char *od = argv[4];
  int y0 = argc > 5 ? atoi(argv[5]) : 40, y1 = argc > 6 ? atoi(argv[6]) : 60;
  int N = b - a + 1; uint8_t **fr = calloc(N, sizeof *fr);
  for (int i = 0; i < N; i++) { char fn[512]; snprintf(fn, sizeof fn, pat, a + i); fr[i] = load(fn); if (!fr[i]) { fprintf(stderr, "no %s\n", fn); return 1; } }
  size_t n4 = (size_t)W * H * S * S * 4;
  uint8_t *prevR = malloc(n4), *curR = malloc(n4), *prevM = malloc(n4), *curM = malloc(n4), *prevB = malloc(n4), *curB = malloc(n4), *mid = malloc((size_t)W * H * 4);
  int have = 0; char lr[4096] = "", lm[4096] = "", lb[4096] = ""; int nl = 0;
  double tp = 0, tr = 0; int np = 0, nr = 0;
  fg_cad_reset();
  for (int k = 0; k < N; k++) {
    double t0 = now(); int ready = fg_cad_push(fr[k], W, H); tp += now() - t0; np++;
    if (!ready) continue;
    int n = k - 2;                                    /* 이번에 보일 두 칸: n½, G(n+1) */
    for (int slot = 1; slot >= 0; slot--) {
      /* 그대로: n½ 칸엔 F(n), G(n+1) 칸엔 F(n+1) */
      up4(slot == 1 ? fr[n] : fr[n + 1], curR);
      /* 움직임: n½ 칸엔 mid(F(n),F(n+1)), 실제 칸엔 F(n+1) */
      if (slot == 1) { fg_build(fr[n], fr[n + 1], mid, W, H, FG_MOTION); up4(mid, curM); } else up4(fr[n + 1], curM);
      t0 = now(); fg_cad_render(slot, curB, S); tr += now() - t0; nr++;
      if (have && nl < 64) {
        int r = hshift(prevR, curR, y0, y1), m = hshift(prevM, curM, y0, y1), bb = hshift(prevB, curB, y0, y1);
        char t[16];
        snprintf(t, sizeof t, "%2d ", -r); strcat(lr, t);
        snprintf(t, sizeof t, "%2d ", -m); strcat(lm, t);
        snprintf(t, sizeof t, "%2d ", -bb); strcat(lb, t);
        nl++;
      }
      if (od[0] != '-') { char fn[512]; snprintf(fn, sizeof fn, "%s/o_%05d_%d.ppm", od, a + n + (slot == 0 ? 1 : 0), slot == 1); wppm(fn, curB, W * S, H * S); }
      memcpy(prevR, curR, n4); memcpy(prevM, curM, n4); memcpy(prevB, curB, n4); have = 1;
    }
  }
  printf("배경 가로 이동(¼px, 줄 %d~%d, 120Hz 한 장마다):\n그대로 %s\n움직임 %s\n박자   %s\n", y0, y1, lr, lm, lb);
  printf("시간: 넣기 %.2f ms/장, 그리기 %.2f ms/칸\n", tp / np * 1000, tr / nr * 1000);
  return 0;
}
