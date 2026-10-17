/* 호스트 모의: 코어를 언제 돌리나(pace.c) + 소리 맞추기(drc) — 예전 시계 방식과 견준다.
 *   cc -O2 -I app/src/main/cpp tools/pace_sim.c app/src/main/cpp/pace.c -lm -o /tmp/pace_sim && /tmp/pace_sim
 *
 * 안드로이드 GLSurfaceView 처럼 본다: GL 스레드는 그림 하나를 큐에 넣고(eglSwapBuffers), 큐가 차 있으면(대기 그림 2장)
 * 화면이 vsync 에 한 장을 가져갈 때까지 막혀 있다가 조금 늦게(0.05~wake ms) 깨어나 다음 nativeFrame 을 부른다.
 * 일하는 시간은 2~4ms, 가끔(spike%) 10~20ms. 화면은 vsync 마다 큐 맨 앞을 내고, 비었으면 앞 그림을 한 번 더 낸다(놓침).
 * 소리 카드는 정확한 시계로 묶음씩 가져간다.
 * 잰다: 게임 프레임이 화면에 몇 vsync 머물렀나(늘 k 여야 매끈) · 화면 놓침 · 소리 끊김(빈 샘플) · 버림 · 고리 평균(지연) */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "pace.h"

#ifndef TGT_ADD
#define TGT_ADD 700      /* native.c 와 같게: 목표 = 소리 카드 묶음 + 700 (최소 900) */
#endif
#ifndef TGT_MIN
#define TGT_MIN 900
#endif

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

typedef struct { double hz, fps, wake_ms, spike; int burst, secs; double fps2_at; double hz2_at, hz2; } scen;

static void sim(int newp, scen sc)
{
   pace_t P; drc_t D;
   double hz = sc.hz, T = 1.0 / hz, fps = sc.fps;
   double tv = T, tcall = 0.0001, tq = -1, tcb = 0.0007, acc = 0, ema = 0, last = 0, next_t = 0, end = sc.secs;
   int q[8], qn = 0, cur = -1, frame = 0, pending = -1, blocked = 0;
   long slots = 0, missed = 0, hold_hist[8] = {0}, holds = 0, irregular = 0, run = 0, fill_sum = 0, fill_n = 0;
   int prev_shown = -2;
   unsigned target = (unsigned)sc.burst + TGT_ADD; if (target < TGT_MIN) target = TGT_MIN;
   int16_t in[4096 * 2], out[8192 * 2];
   int i;
   pace_reset(&P); drc_reset(&D); rd = wr = 0; under = dropped = 0;
   srand(11);
   for (i = 0; i < 4096 * 2; i++) in[i] = (int16_t)(i * 37);
   while (1)
   {
      double tn = tv; int ev = 0;                         /* 0 vsync · 1 호출 시작 · 2 큐에 넣기 · 3 소리 */
      if (!blocked && tq < 0 && tcall < tn) { tn = tcall; ev = 1; }
      if (tq >= 0 && tq < tn) { tn = tq; ev = 2; }
      if (tcb < tn) { tn = tcb; ev = 3; }
      if (tn > end) break;
      if (sc.hz2_at > 0 && tn > sc.hz2_at && hz != sc.hz2) { hz = sc.hz2; T = 1.0 / hz; }
      if (sc.fps2_at > 0 && tn > sc.fps2_at) { fps = fps == 60.25 ? 120.5 : 60.25; sc.fps2_at = -1; }   /* 사무쇼2 처럼 코어가 fps 를 바꿈 */
      if (ev == 0)
      {  /* 화면: 큐 맨 앞을 낸다 */
         int shown;
         if (qn) { cur = q[0]; memmove(q, q + 1, sizeof(int) * (size_t)(--qn)); if (blocked) { blocked = 0; tcall = tn + (0.05 + urand() * (sc.wake_ms - 0.05)) * 1e-3; } }
         else if (cur >= 0) missed++;
         shown = cur;
         slots++;
         if (tn > 1.0)
         {  /* 머묾 길이(첫 1초는 뺀다) */
            if (shown == prev_shown) run++;
            else
            {
               if (prev_shown >= 0 && run > 0)
               {
                  int k = (int)(hz / fps + 0.5); if (k < 1) k = 1;
                  hold_hist[run < 7 ? run : 7]++; holds++;
                  if (run != k) irregular++;
               }
               run = 1;
            }
         }
         prev_shown = shown;
         tv += T;
      }
      else if (ev == 1)
      {  /* nativeFrame */
         double t = tn; int steps, s;
         if (last > 0) { double iv = t - last; if (iv > 0.002 && iv < 0.1) ema = ema > 0 ? ema * 0.9 + iv * 0.1 : iv; }
         last = t;
         if (newp) steps = pace_steps(&P, t, fps, sc.hz, 0, 0);
         else
         {  /* 예전 native.c */
            double dt = 1.0 / fps; int maxs = 2; steps = 0;
            if (next_t <= 0.0 || t - next_t > 0.25) next_t = t;
            while (next_t <= t && steps < maxs) { next_t += dt; steps++; }
         }
         for (s = 0; s < steps; s++)
         {
            int n;
            acc += 44100.0 / fps; n = (int)acc; acc -= n;
            if (newp) { size_t w = drc_run(&D, in, (size_t)n, out, 8192, drc_ratio(P.drc_base, fillf(), target)); push(out, w); }
            else push(in, (size_t)n);
            frame++;
         }
         pending = frame;
         tq = t + (urand() < sc.spike ? 10 + urand() * 10 : 2 + urand() * 2) * 1e-3;
      }
      else if (ev == 2)
      {  /* eglSwapBuffers — 큐에 넣고, 큐가 찼으면 막힌다 */
         q[qn++] = pending; tq = -1;
         if (qn >= 2) blocked = 1;
         else tcall = tn + 0.0001;
      }
      else
      {
         int k;
         for (k = 0; k < sc.burst; k++) { if (rd == wr) under++; else rd = (rd + 1) % RING; }
         tcb += sc.burst / 44100.0;
         if (tn > 1.0) { fill_sum += fillf(); fill_n++; }
      }
   }
   printf("%s 화면 %3.0f%s 게임 %6.2f%s 깨어남≤%.1fms 늦음 %2.0f%% 묶음 %3d: 고르지 않은 머묾 %5.2f/초 (1:%ld 2:%ld 3:%ld 4:%ld) 화면 놓침 %ld · 소리 끊김 %4ld/분 버림 %ld · 고리 %4.0f(%2.0fms)%s\n",
          newp ? "새" : "옛", sc.hz, sc.hz2_at > 0 ? "→60" : "  ", sc.fps, sc.fps2_at != 0 ? "↔" : " ", sc.wake_ms, sc.spike * 100, sc.burst,
          irregular / (double)(sc.secs - 1), hold_hist[1], hold_hist[2], hold_hist[3], hold_hist[4], missed,
          under * 60 / sc.secs, dropped, fill_n ? (double)fill_sum / fill_n : 0, fill_n ? (double)fill_sum / fill_n / 44.1 : 0,
          newp ? (P.lock_k ? "" : " (잠금 없음)") : "");
   if (newp && P.catchup) printf("      따라잡기·쉬기 %ld 번\n", P.catchup);
}

int main(void)
{
   scen S[] = {
      { 120, 60.25, 0.3, 0.00, 192, 60, 0, 0, 0 },
      { 120, 60.25, 1.0, 0.00, 192, 60, 0, 0, 0 },
      { 120, 60.25, 2.0, 0.00, 192, 60, 0, 0, 0 },
      { 120, 60.25, 1.0, 0.02, 192, 60, 0, 0, 0 },
      { 120, 60.25, 1.0, 0.10, 192, 60, 0, 0, 0 },
      { 120, 120.5, 0.3, 0.00, 192, 60, 0, 0, 0 },
      { 120, 120.5, 1.0, 0.00, 192, 60, 0, 0, 0 },
      { 120, 120.5, 1.0, 0.02, 192, 60, 0, 0, 0 },
      { 120, 60.25, 1.0, 0.00, 960, 60, 0, 0, 0 },
      {  60, 60.25, 1.0, 0.00, 192, 60, 0, 0, 0 },
      {  90, 60.25, 1.0, 0.00, 192, 60, 0, 0, 0 },
      { 120, 60.25, 1.0, 0.00, 192, 60, 20, 0, 0 },
      { 120, 60.25, 1.0, 0.00, 192, 60, 0, 20, 60 },
   };
   unsigned i;
   int newp;
   for (i = 0; i < sizeof S / sizeof S[0]; i++)
      for (newp = 0; newp < 2; newp++) sim(newp, S[i]);
   return 0;
}
