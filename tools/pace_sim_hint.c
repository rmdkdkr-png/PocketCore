/* 호스트 모의(173): Java 값(Display.getRefreshRate — 게임 열 때 한 번, 120Hz 요청이 걸리기 전 60 일 수 있다)이 맞을 때·틀릴 때·없을 때,
 * 무거운 구간(경기 초반)·화면 Hz 바뀜·코어 fps 바뀜에서 pace.c 를 본다. 잰다: 소리 늘림(평균·최대 — 무거운 구간 뒤 따로), drc_base 최대,
 * 빈 샘플(첫 1초 빼고), 고르지 않은 머묾, 게임 빠르기 어긋남(fps 대비), 잠금 k·잠근 화면 Hz.
 *   cc -O2 -I app/src/main/cpp tools/pace_sim_hint.c app/src/main/cpp/pace.c -lm -o /tmp/psh2 && /tmp/psh2 [씨앗]
 * 173 첫 판(잠글 때 Java 값 아니면 실측을 1% 안 흔한 값, 아니면 실측 그대로)은 Java 값이 틀리고 무거울 때 잠그면 117.5 를 굳혀
 * 빈 샘플이 분당 2~6만 — 그래서 흔한 값 ±3.5% 로 맞춘다. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "pace.h"

#define RING 16384
static int16_t ring[RING * 2];
static unsigned rd, wr;
static unsigned fillf(void) { return (wr - rd + RING) % RING; }
static long under, dropped;
static double urand(void) { return (rand() + 0.5) / (RAND_MAX + 1.0); }
static void push(const int16_t *d, size_t n)
{
   size_t i;
   for (i = 0; i < n; i++) { unsigned nx = (wr + 1) % RING; if (nx == rd) { dropped += (long)(n - i); break; } ring[wr * 2] = d[i * 2]; ring[wr * 2 + 1] = d[i * 2 + 1]; wr = nx; }
}

typedef struct {
   const char *name;
   double hz, hint, fps; int secs;
   double h0, h1, heavy_lo, heavy_hi, spike_in, spike_out;
   double hz2_at, hz2, fps2_at;
   double wake_ms;
} scen;

static void sim(const scen *sc, unsigned seed)
{
   pace_t P; drc_t D;
   double hz = sc->hz, T = 1.0 / hz, fps = sc->fps;
   double tv = T, tcall = 0.0001, tq = -1, tcb = 0.0007, acc = 0, end = sc->secs, expf = 0, lastt = 0;
   int q[8], qn = 0, cur = -1, frame = 0, pending = -1, blocked = 0, alt = 0, last_steps = 0;
   long irregular = 0, run = 0, rn = 0, rn2 = 0, bhigh = 0, under1 = 0;
   double rsum = 0, rmax = 0, bmax = 0, rsum2 = 0, rmax2 = 0;
   int prev_shown = -2, burst = 192;
   unsigned target = (unsigned)burst + 700; if (target < 900) target = 900;
   int16_t in[4096 * 2], out[8192 * 2];
   int i;
   pace_reset(&P); drc_reset(&D); rd = wr = 0; under = dropped = 0;
   srand(seed);
   for (i = 0; i < 4096 * 2; i++) in[i] = (int16_t)(i * 37);
   while (1)
   {
      double tn = tv; int ev = 0;
      if (!blocked && tq < 0 && tcall < tn) { tn = tcall; ev = 1; }
      if (tq >= 0 && tq < tn) { tn = tq; ev = 2; }
      if (tcb < tn) { tn = tcb; ev = 3; }
      if (tn > end) break;
      if (sc->hz2_at > 0 && tn > sc->hz2_at && hz != sc->hz2) { hz = sc->hz2; T = 1.0 / hz; }
      if (sc->fps2_at > 0 && tn > sc->fps2_at && fps == sc->fps) fps = fps < 100 ? 120.5 : 60.25;
      if (tn > 1.0) expf += fps * (tn - (lastt > 1.0 ? lastt : 1.0));
      lastt = tn;
      if (ev == 0)
      {
         int shown;
         if (qn) { cur = q[0]; memmove(q, q + 1, sizeof(int) * (size_t)(--qn)); if (blocked) { blocked = 0; tcall = tn + (0.05 + urand() * (sc->wake_ms - 0.05)) * 1e-3; } }
         shown = cur;
         if (tn > 1.0)
         {
            if (shown == prev_shown) run++;
            else
            {
               if (prev_shown >= 0 && run > 0) { int k = (int)(hz / fps + 0.5); if (k < 1) k = 1; if (run != k) irregular++; }
               run = 1;
            }
         }
         prev_shown = shown;
         tv += T;
      }
      else if (ev == 1)
      {
         double t = tn, w; int steps, s, heavy = t >= sc->h0 && t < sc->h1;
         steps = pace_steps(&P, t, fps, sc->hint, 0, 0);
         for (s = 0; s < steps; s++)
         {
            int n;
            acc += 44100.0 / fps; n = (int)acc; acc -= n;
            { size_t ww = drc_run(&D, in, (size_t)n, out, 8192, drc_ratio(P.drc_base, fillf(), target)); push(out, ww); }
            if (t > 1.0) frame++;
         }
         if (t > 1.0) { double r = drc_ratio(P.drc_base, fillf(), target); rsum += r; rn++; if (r > rmax) rmax = r; if (P.drc_base > bmax) bmax = P.drc_base; if (P.drc_base > 1.008) bhigh++;
                        if (t >= sc->h1 + 2.0) { rsum2 += r; rn2++; if (r > rmax2) rmax2 = r; } }
         pending = frame;
         {
            int real;
            if (fps > 100) { alt ^= 1; real = alt; } else real = steps > 0;
            if (!real) w = 0.3 + urand() * 0.3;
            else if (heavy) w = urand() < sc->spike_in ? 10 + urand() * 8 : sc->heavy_lo + urand() * (sc->heavy_hi - sc->heavy_lo);
            else w = urand() < sc->spike_out ? 10 + urand() * 8 : 3 + urand() * 2;
            tq = t + w * 1e-3;
         }
         last_steps = steps;
      }
      else if (ev == 2)
      {
         q[qn++] = pending; tq = -1;
         if (qn >= 2) blocked = 1; else tcall = tn + 0.0001;
      }
      else
      {
         int k;
         for (k = 0; k < burst; k++) { if (rd == wr) { under++; if (tn > 1.0) under1++; } else rd = (rd + 1) % RING; }
         tcb += burst / 44100.0;
      }
   }
   (void)last_steps;
   printf("  %-30s 늘림 평균 %+5.2f%% 최대 %+5.2f%% | 무거움 뒤 평균 %+5.2f%% 최대 %+5.2f%% | base최대 %.4f >1.008 %4.1f%% | 빈샘플 %5ld/분 | 머묾어긋 %5.2f/초 | 게임속도 %+5.2f%% | 잠금 k=%d",
          sc->name, rn ? (rsum / rn - 1) * 100 : 0, (rmax - 1) * 100, rn2 ? (rsum2 / rn2 - 1) * 100 : 0, rn2 ? (rmax2 - 1) * 100 : 0,
          bmax, rn ? bhigh * 100.0 / rn : 0, under1 * 60 / (sc->secs - 1), irregular / (double)(sc->secs - 1), expf > 0 ? (frame / expf - 1) * 100 : 0, P.lock_k);
   printf(" Hz=%.2f", P.lock_hzv);
   printf(" 따라잡기 %ld\n", P.catchup);
}

int main(int argc, char **argv)
{
   scen S[] = {
      /* name                      hz   hint  fps    s   h0  h1  lo  hi  sp_in sp_out hz2_at hz2 fps2 wake */
      { "120 맞음 무거움 내내 15%",  120, 120, 120.5, 60, 0, 99,  6,  9, 0.15, 0.01, 0, 0, 0, 0.3 },
      { "120 맞음 무거움 내내 30%",  120, 120, 120.5, 60, 0, 99,  6,  9, 0.30, 0.01, 0, 0, 0, 0.3 },
      { "120 맞음 초반15초 30%",     120, 120, 120.5, 60, 0, 15,  6,  9, 0.30, 0.01, 0, 0, 0, 0.3 },
      { "120 틀림60 무거움 내내 15%", 120, 60, 120.5, 60, 0, 99,  6,  9, 0.15, 0.01, 0, 0, 0, 0.3 },
      { "120 틀림60 무거움 내내 30%", 120, 60, 120.5, 60, 0, 99,  6,  9, 0.30, 0.01, 0, 0, 0, 0.3 },
      { "120 틀림60 초반15초 30%",    120, 60, 120.5, 60, 0, 15,  6,  9, 0.30, 0.01, 0, 0, 0, 0.3 },
      { "120 틀림60 초반15초 15%",    120, 60, 120.5, 60, 0, 15,  6,  9, 0.15, 0.01, 0, 0, 0, 0.3 },
      { "120 없음0 초반15초 30%",     120,  0, 120.5, 60, 0, 15,  6,  9, 0.30, 0.01, 0, 0, 0, 0.3 },
      { "120 맞음 60fps 초반 30%",    120, 120, 60.25, 60, 0, 15,  6,  9, 0.30, 0.01, 0, 0, 0, 0.3 },
      { "120 틀림60 60fps 초반 30%",  120, 60, 60.25, 60, 0, 15,  6,  9, 0.30, 0.01, 0, 0, 0, 0.3 },
      { "60 맞음 60fps 가벼움",        60,  60, 60.25, 60, 0,  0,  6,  9, 0.00, 0.01, 0, 0, 0, 0.3 },
      { "60 틀림120 60fps 가벼움",     60, 120, 60.25, 60, 0,  0,  6,  9, 0.00, 0.01, 0, 0, 0, 0.3 },
      { "120→60(20초) hint120 60fps", 120, 120, 60.25, 60, 0,  0,  6,  9, 0.00, 0.01, 20, 60, 0, 0.3 },
      { "120→60(20초) hint60 60fps",  120, 60, 60.25, 60, 0,  0,  6,  9, 0.00, 0.01, 20, 60, 0, 0.3 },
      { "60→120(20초) hint60 60fps",   60, 60, 60.25, 60, 0,  0,  6,  9, 0.00, 0.01, 20, 120, 0, 0.3 },
      { "60→120(20초) hint60 120fps",  60, 60, 120.5, 60, 0,  0,  6,  9, 0.00, 0.01, 20, 120, 0, 0.3 },
      { "120 fps 60↔120(20초) hint120",120, 120, 60.25, 60, 0,  0,  6,  9, 0.00, 0.01, 0, 0, 20, 0.3 },
      { "120 fps 120↔60(20초) hint60", 120, 60, 120.5, 60, 0,  0,  6,  9, 0.00, 0.01, 0, 0, 20, 0.3 },
      { "120 맞음 가벼움 깨어남1ms",   120, 120, 120.5, 60, 0,  0,  6,  9, 0.00, 0.02, 0, 0, 0, 1.0 },
   };
   unsigned i, seed = argc > 1 ? (unsigned)atoi(argv[1]) : 11;
   for (i = 0; i < sizeof S / sizeof S[0]; i++) sim(&S[i], seed);
   return 0;
}
