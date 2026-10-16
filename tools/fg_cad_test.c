/* «박자» 모드 호스트 시험 — gcc -O2 -I app/src/main/cpp tools/fg_cad_test.c app/src/main/cpp/fg_cadence.c app/src/main/cpp/framegen.c
 * 장면을 «게임 프레임 k 의 정수 자리»로 1배로 그려 고리에 넣고, 120Hz 두 칸(G(n+1)·n½)을 4배로 받아
 * 정답(게임 박자 사이를 고르게 나눈 ¼px 자리로 4배에 그린 장면)과 화소 일치율을 비교한다.
 * 견줌: 그대로(보간 없음) · 움직임(framegen.c 2배를 4배로 키움) · 박자(4배). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "framegen.h"
#ifdef FG_CAD_DEBUG
extern uint8_t *fg_cad_dbg;
extern int fg_cad_slow_only;
void fg_cad_dbg_px(int x, int y);
static uint8_t dbgmap[160 * 152 * 16];
#endif
#define W 160
#define H 152
#define S 4
static uint32_t pal[8] = {0x101820,0x2a4060,0x5080a0,0xa0c0d0,0x603020,0xc06040,0xf0d080,0xffffff};
static int dither = 0;
static uint32_t bgcol(int x, int y) {
  unsigned hsh = ((unsigned)(x >> 2) * 73856093u) ^ ((unsigned)(y >> 2) * 19349663u);
  if (dither && ((hsh >> 9) & 1)) return ((x + y) & 1) ? pal[1] : pal[3];
  return pal[(hsh >> 5) % 4];
}
static int fdiv(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }
/* 자리는 ¼px 단위(q). sc = 1 이면 q 는 4 의 배수여야 한다. */
typedef struct { int bg, s1x, s1y, s2x, s2y; } pose_t;
static void scene(uint8_t *f, int sc, pose_t p) {
  int w = W * sc, h = H * sc;
  for (int Y = 0; Y < h; Y++) for (int X = 0; X < w; X++) {
    int xq = 4 * X / sc, y = Y / sc;
    uint32_t c = bgcol(fdiv(xq + p.bg, 4), y);
    if (y < 16) c = pal[7];
    int lx = fdiv(xq - p.s1x, 4), ly = fdiv(4 * Y / sc - p.s1y, 4);
    if (y >= 16 && lx >= 0 && lx < 16 && ly >= 0 && ly < 24) {      /* 몸1: 테두리 있는 타원, 안은 무늬(반복 없음) */
      int e = (lx - 8) * (lx - 8) + (ly - 12) * (ly - 12) / 2;
      if (e < 60) c = e >= 44 ? 0x000000 : pal[4 + ((((unsigned)lx * 2654435761u) ^ ((unsigned)ly * 40503u)) >> 13) % 3];
    }
    lx = fdiv(xq - p.s2x, 4); ly = fdiv(4 * Y / sc - p.s2y, 4);
    if (y >= 16 && lx >= 0 && lx < 20 && ly >= 0 && ly < 20) {      /* 몸2: 테두리 네모, 안은 무늬 */
      if (lx == 0 || ly == 0 || lx == 19 || ly == 19) c = 0x000000;
      else if (((lx ^ ly) & 4) || ((lx * 7 + ly * 3) % 5 == 0)) c = pal[6];
    }
    uint8_t *o = f + ((size_t)Y * w + X) * 4; o[0] = c >> 16; o[1] = c >> 8; o[2] = c; o[3] = 255;
  }
}
/* 움직임 하나: kind 0 정지 · 30 = 2 프레임마다 d(위상 ph) · 60 = 프레임마다 d.  pos 는 q 단위. */
typedef struct { int kind, d, ph, base; } mot_t;
static int pos_real(mot_t m, int k) {           /* 게임 프레임 k 의 실제 자리(정수 px → q) */
  if (m.kind == 30) return m.base + 4 * m.d * fdiv(k + m.ph, 2);
  if (m.kind == 60) return m.base + 4 * m.d * k;
  return m.base;
}
static int pos_ideal(mot_t m, int t2) {         /* 보여 줄 때 τ = t2/2 (게임 프레임) 의 고른 자리(q) */
  if (m.kind == 30) return m.base + m.d * (t2 + 2 * m.ph);   /* d·(τ+ph)/2 px = d·(t2+2ph) q */
  if (m.kind == 60) return m.base + 2 * m.d * t2;
  return m.base;
}
typedef struct { const char *name; mot_t bg, s1x, s2x; int s1y, s2y, dith; } case_t;
static pose_t at_real(const case_t *c, int k) { pose_t p = { pos_real(c->bg, k), pos_real(c->s1x, k), c->s1y, pos_real(c->s2x, k), c->s2y }; return p; }
static pose_t at_ideal(const case_t *c, int t2) { pose_t p = { pos_ideal(c->bg, t2), pos_ideal(c->s1x, t2), c->s1y, pos_ideal(c->s2x, t2), c->s2y }; return p; }
static void up4(const uint8_t *a, uint8_t *o) {
  for (int Y = 0; Y < H * S; Y++) for (int X = 0; X < W * S; X++) memcpy(o + ((size_t)Y * W * S + X) * 4, a + ((Y / S) * W + X / S) * 4, 4);
}
static double match(const uint8_t *a, const uint8_t *b, int n) { int ok = 0; for (int i = 0; i < n; i++) if (!memcmp(a + i * 4, b + i * 4, 3)) ok++; return 100.0 * ok / n; }
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }

int main(int argc, char **argv) {
  static uint8_t fr[64][W * H * 4], gt[W * H * 4 * S * S], o4[W * H * 4 * S * S], tmp[W * H * 4], tmp4[W * H * 4 * S * S];
  const char *dump = argc > 1 ? argv[1] : NULL;
  case_t cases[] = {
    /* 이름, 배경, 몸1 x, 몸2 x, 몸1 y, 몸2 y */
    { "멈춤",                 {0,0,0,0},      {0,0,0,160},   {0,0,0,400},   280, 360, 0 },
    { "사무쇼2식 걷기(배경 1px/2f 홀수, 몸1 2px/2f 짝수, 몸2 60Hz)", {30,1,1,0}, {30,2,0,160}, {60,-1,0,400}, 280, 360, 0 },
    { "30Hz 큰 걸음(몸1 4px 홀수, 몸2 -3px 짝수)", {0,0,0,0}, {30,4,1,120}, {30,-3,0,480}, 280, 360, 0 },
    { "60Hz 전체(배경 1px, 몸 2px)", {60,1,0,0}, {60,2,0,120}, {0,0,0,400}, 280, 360, 0 },
    { "사무쇼2식 + 디더 배경", {30,1,1,0}, {30,2,0,160}, {60,-1,0,400}, 280, 360, 1 },
  };
  int nc = sizeof cases / sizeof cases[0];
  for (int ci = 0; ci < nc; ci++) {
    case_t *c = &cases[ci]; dither = c->dith;
    int NF = 40;
    for (int k = 0; k < NF; k++) scene(fr[k], 1, at_real(c, k));
    fg_cad_reset();
    double sum[3][2] = {{0}}; int cnt = 0; double tpush = 0, trend = 0;
    for (int k = 0; k < NF; k++) {
      double t0 = now();
      int ready = fg_cad_push(fr[k], W, H);
      tpush += now() - t0;
      if (!ready) continue;
      int n = k - 2;                                     /* 그리는 칸: G(n+1) 과 n·½ */
      if (n < 4) continue;                               /* 박자를 알 만큼 쌓인 뒤부터 */
      for (int slot = 0; slot < 2; slot++) {
        int t2 = slot == 0 ? 2 * (n + 1) : 2 * n + 1;
        scene(gt, S, at_ideal(c, t2));
        /* 그대로 */
        up4(slot == 0 ? fr[n + 1] : fr[n], tmp4); sum[0][slot] += match(tmp4, gt, W * H * S * S);
        /* 움직임(2배) */
        if (slot == 0) up4(fr[n + 1], tmp4); else { fg_build(fr[n], fr[n + 1], tmp, W, H, FG_MOTION); up4(tmp, tmp4); }
        sum[1][slot] += match(tmp4, gt, W * H * S * S);
        /* 박자 */
#ifdef FG_CAD_DEBUG
        memset(dbgmap, 0, sizeof dbgmap); fg_cad_dbg = dbgmap;
#endif
        t0 = now(); fg_cad_render(slot, o4, S); trend += now() - t0;
        sum[2][slot] += match(o4, gt, W * H * S * S);
#ifdef FG_CAD_DEBUG
        { static uint8_t o4s[160 * 152 * 4 * 16]; uint8_t *keep = fg_cad_dbg; fg_cad_dbg = NULL; fg_cad_slow_only = 1; fg_cad_render(slot, o4s, S); fg_cad_slow_only = 0; fg_cad_dbg = keep;
          int diff = 0; for (int i = 0; i < W * H * S * S; i++) if (memcmp(o4 + i * 4, o4s + i * 4, 4)) diff++;
          if (diff) printf("    !! 빠른 길 ≠ 느린 길: case %d n%d slot%d — %d 화소\n", ci, n, slot, diff); }
        fg_cad_dbg = NULL;
        if (getenv("DBGCASE") && atoi(getenv("DBGCASE")) == ci) {
          int hist[16] = {0}, tot = 0;
          for (int i = 0; i < W * H * S * S; i++) if (memcmp(o4 + i * 4, gt + i * 4, 3)) { hist[dbgmap[i]]++; tot++;
            if (getenv("DBGXY") && tot <= 12) printf("    n%d s%d miss X%d Y%d rule %d\n", n, slot, i % (W * S), i / (W * S), dbgmap[i]); }
          if (getenv("DBGPX") && n == atoi(getenv("DBGN")) && slot == atoi(getenv("DBGS"))) {
            int px, py; sscanf(getenv("DBGPX"), "%d,%d", &px, &py);
            for (int dx = -1; dx <= 2; dx++) fg_cad_dbg_px(px + dx, py);
          }
          if (tot) { printf("    n%d slot%d miss %d:", n, slot, tot); for (int r = 0; r < 16; r++) if (hist[r]) printf(" r%d=%d", r, hist[r]); printf("\n"); }
        }
#endif
        if (dump && ci == 1 && n == 10) {
          char nm[256]; snprintf(nm, sizeof nm, "%s_c%d_n%d_s%d.ppm", dump, ci, n, slot);
          FILE *fp = fopen(nm, "wb"); fprintf(fp, "P6\n%d %d\n255\n", W * S, H * S);
          for (int i = 0; i < W * H * S * S; i++) { fwrite(o4 + i * 4, 1, 3, fp); }
          fclose(fp);
          snprintf(nm, sizeof nm, "%s_c%d_n%d_s%d_gt.ppm", dump, ci, n, slot);
          fp = fopen(nm, "wb"); fprintf(fp, "P6\n%d %d\n255\n", W * S, H * S);
          for (int i = 0; i < W * H * S * S; i++) { fwrite(gt + i * 4, 1, 3, fp); }
          fclose(fp);
        }
      }
      cnt++;
    }
    printf("[%s]\n", c->name);
    const char *nm[3] = { "그대로", "움직임", "박자" };
    for (int m = 0; m < 3; m++) printf("  %-6s  실제칸 %.1f%%  사이칸 %.1f%%\n", nm[m], sum[m][0] / cnt, sum[m][1] / cnt);
    printf("  시간: 넣기 %.2f ms/장, 그리기 %.2f ms/칸\n", tpush / NF * 1000, trend / (2 * cnt) * 1000);
  }
  fg_cad_free(); fg_free();
  return 0;
}
