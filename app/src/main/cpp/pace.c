/* 코어를 언제 돌리나 + 소리 맞추기 (내부 150). 설명은 pace.h. 안드로이드에 기대지 않는다(호스트 모의 tools/pace_sim.c). */
#include "pace.h"
#include <math.h>
#include <string.h>

#define LOCK_IN   0.03      /* 정수배에서 이만큼 안이면 잠근다(±3%) */
#define LOCK_OUT  0.06      /* 잠근 뒤엔 이만큼 벗어나야 푼다 */
#define LOCK_WAIT 20        /* 조건이 이 호출 수만큼 이어져야 잠그거나 푼다 */

void pace_reset(pace_t *p)
{
   memset(p, 0, sizeof *p);
   p->drc_base = 1.0;
}

/* 173: 잠글 때 화면 Hz — 잠금은 실측이 정수배 ±3% 안에 20 번 이어져야 걸린다. Java 값이 실측 ±3% 안이면 Java 값(정확),
   아니면 실측에 가장 가까운 흔한 값(±3.5% 안), 그것도 없으면 실측. Java 값은 게임을 열 때 한 번만 받아(120Hz 요청이 걸리기 전 60 일 수 있다)
   틀릴 수 있다 — 그때도 무거운 그림에 처진 실측(117~118)을 그대로 굳히지 않게 흔한 값으로 맞춘다 */
static double lock_hz_at_lock(const pace_t *p, double hint)
{
   static const double std_hz[] = { 48, 50, 60, 72, 75, 90, 96, 100, 120, 144, 165, 180, 240 };
   double m = p->ema > 0 ? 1.0 / p->ema : 0, best = 0, bd = 1e9;
   unsigned i;
   if (m <= 1) return hint > 1 ? hint : 0;
   if (hint > 1 && fabs(m - hint) <= hint * LOCK_IN) return hint;
   for (i = 0; i < sizeof std_hz / sizeof std_hz[0]; i++) { double d = fabs(m - std_hz[i]); if (d < bd) { bd = d; best = std_hz[i]; } }
   return bd <= best * (LOCK_IN + 0.005) ? best : m;   /* 잠금 창(정수배 ±3%)을 다 덮게 3.5% */
}

static int near_k(double r, double tol)
{
   int kk = (int)(r + 0.5);
   if (kk >= 1 && kk <= 4 && fabs(r - kk) <= tol * kk) return kk;
   return 0;
}

/* 따라잡기가 잦다(2초에 놓친 칸 8 개 넘게 — 이 기기가 지금 그 Hz 를 못 버틴다: 그림 한 장에 10ms 넘게 자주 걸림) — 잠금을 풀고 5초 쉰다.
   잠금은 놓친 칸을 0.5초 늦게 알아채므로, 놓침이 잦으면 그 사이 소리가 모자란다 — 그땐 예전 시계 방식이 낫다 */
static void pace_unstable(pace_t *p, double t, int k)
{
   int i, n = 0;
   p->corr_t[p->corr_i] = t; p->corr_i = (p->corr_i + 1) % 8;
   for (i = 0; i < 8; i++) if (p->corr_t[i] > 0 && t - p->corr_t[i] < 2.0) n++;
   if (n * k >= 8)
   {  /* 쉬는 시간은 거듭되면 5 → 10 → 20 → 40 → 80 초(잠금이 30초 넘게 버티면 처음부터) */
      double w = 5.0;
      for (i = 0; i < p->cool_n && i < 4; i++) w *= 2;
      if (p->lock_since > 0 && t - p->lock_since > 30.0) { p->cool_n = 0; w = 5.0; }
      p->cool_n++;
      p->lock_k = 0; p->streak = 0; p->lock_t0 = 0; p->cool_until = t + w; memset(p->corr_t, 0, sizeof p->corr_t);
   }
}

int pace_fast_display(const pace_t *p, double fps)
{
   if (fps <= 1.0) fps = 60.0;
   if (p->lock_k) return p->lock_k >= 2;
   return p->ema > 0 && p->ema < (1.0 / fps) * 0.70;
}

int pace_steps(pace_t *p, double t, double fps, double hz_hint, int turbo, int paused)
{
   double dt;
   int steps = 0, maxsteps = 2;
   if (fps <= 1.0) fps = 60.0;
   dt = 1.0 / fps;
   /* 호출 간격 — 큐가 비었다 차며 몰려 오는 호출(0.5ms·16ms)·한 번 늦은 호출에 덜 흔들리게 0.5~1.5 배로 눌러 넣는다 */
   if (p->last_t > 0 && t - p->last_t > 0.25)
   {  /* 긴 공백(뒤로 갔다 옴·멈춤) — 벽시계 기준을 다시 잡는다(밀린 만큼 몰아 돌리지 않게) */
      p->lock_t0 = 0; p->next_t = t;
   }
   if (p->last_t > 0)
   {
      double iv = t - p->last_t;
      if (iv > 0.0005 && iv < 0.1)
      {
         if (p->ema <= 0) p->ema = iv;
         else
         {
            if (iv < p->ema * 0.5) iv = p->ema * 0.5;
            if (iv > p->ema * 1.5) iv = p->ema * 1.5;
            p->ema = p->ema * 0.95 + iv * 0.05;
         }
      }
   }
   p->last_t = t;
   p->since++;
   if (paused)
   {  /* 멈춤 — 풀리면 시계를 지금부터 다시 */
      p->next_t = t; p->since = 0; p->lock_k = 0; p->streak = 0; p->drc_base = 1.0;
      return 0;
   }
   if (turbo) p->lock_k = 0, p->streak = 0;
   else if (p->ema > 0)
   {
      double r = dt / p->ema;                /* 게임 한 프레임에 vsync 몇 번 (120Hz·60.25 → 1.99, 120Hz·120.5 → 0.996) */
      int kin = near_k(r, LOCK_IN);
      if (p->lock_k)
      {
         if (kin && kin != p->lock_k)
         {  /* 코어가 fps 를 바꿨다(사무쇼2 프레임 생성 60.25 ↔ 120.5) — 화면은 그대로니 바로 새 k 로(화면 Hz 도 그대로) */
            p->lock_k = kin; p->streak = 0;
            p->lock_t0 = 0;
         }
         else if (!near_k(r, LOCK_OUT) || near_k(r, LOCK_OUT) != p->lock_k)
         {
            if (++p->streak >= LOCK_WAIT) { p->lock_k = 0; p->streak = 0; }
         }
         else p->streak = 0;
      }
      else if (kin && t >= p->cool_until)
      {
         if (++p->streak >= LOCK_WAIT) { p->lock_k = kin; p->streak = 0; p->lock_t0 = 0; p->lock_since = t; p->lock_hzv = lock_hz_at_lock(p, hz_hint); }
      }
      else p->streak = 0;
   }
   if (p->lock_k)
   {
      int k = p->lock_k, step = p->since >= k;
      /* 173: 화면 Hz 는 잠글 때 정한 값 그대로. 예전엔 부를 때마다 실측(호출 간격 EMA)을 봐서, 그림 한 장이 vsync 를 넘기면
         (판 시작 전 소개·멀리 미리 보기처럼 무거운 때) 실측이 117~118Hz 로 처져 Java 값(120)과 1.5% 넘게 벌어지면 그 실측을 화면 Hz 로
         믿었다 — 소리 늘림 drc_base 가 1.004 → 최대 1.03(3% 느리게, 반음의 반)으로 튀어 «테이프 느려진 듯» 들리고
         (유저 10-19 「타이밍이 밀리는지 소리가 늘어질 때가 있다」·「경기 초반」), 놓친 칸 셈(D)도 틀린 Hz 로 했다.
         호스트 모의(실제 프레임 부름 6~9ms + 5% 가 10~18ms): base > 1.008 인 부름 4% · 최대 +3.2% → 0 */
      double hz = p->lock_hzv > 1 ? p->lock_hzv : (p->lock_hzv = lock_hz_at_lock(p, hz_hint));
      p->lock_fps = hz > 1 ? hz / k : fps;
      p->drc_base = fps / p->lock_fps;      /* 소리를 만드는 속도가 fps → 화면 Hz / k 로 느려진 만큼 늘려 낸다 */
      if (p->drc_base < 0.985 || p->drc_base > 1.015) p->drc_base = 1.0;   /* 173: ±3% → ±1.5% (맞는 값은 1.004 안팎) */
      /* 화면이 vsync 를 실제로 놓쳤나(큐가 비어 앞 그림을 한 번 더 냄) — 놓친 칸 D = 지난 vsync 수 − 호출 수.
         평소엔 큐 깊이(0~2칸)·깨어남 흔들림만큼 오르내리고, 한 번 늦은 호출 뒤 몰려 오는 호출에 잠깐 튄다 — 그래서 최근 64 호출의
         가운데값으로 본다. 처음 가운데값을 기준으로, 기준보다 k 칸(게임 한 프레임) 늘면 한 번 더 돌려 따라잡고, k 칸 줄면 한 번 쉰다 */
      if (p->lock_t0 <= 0 || hz <= 1) { p->lock_t0 = t; p->lock_c = 0; p->dwn = 0; p->dwi = 0; p->base_set = 0; p->corr = 0; }
      else
      {
         double D = (t - p->lock_t0) * hz - (double)(++p->lock_c), m;
         p->dwin[p->dwi] = D; p->dwi = (p->dwi + 1) % PACE_WIN;
         if (p->dwn < PACE_WIN) p->dwn++;
         if (p->dwn == PACE_WIN)
         {
            double tmp[PACE_WIN];
            int i, j;
            memcpy(tmp, p->dwin, sizeof tmp);
            for (i = 1; i < PACE_WIN; i++) { double v = tmp[i]; for (j = i; j > 0 && tmp[j - 1] > v; j--) tmp[j] = tmp[j - 1]; tmp[j] = v; }
            m = 0.5 * (tmp[PACE_WIN / 2 - 1] + tmp[PACE_WIN / 2]);
            if (!p->base_set) { p->base = m; p->base_set = 1; }
            else
            {
               double behind = (m - p->base) / k - (double)p->corr;
               if (behind > 8.0 || behind < -8.0) { p->lock_t0 = t; p->lock_c = 0; p->dwn = 0; p->dwi = 0; p->base_set = 0; p->corr = 0; }   /* 너무 어긋났다 — 기준을 다시 */
               else if (behind >= 1.0) { step++; p->corr++; p->catchup++; pace_unstable(p, t, k); }
               else if (behind <= -1.0 && step) { step = 0; p->corr--; p->catchup++; pace_unstable(p, t, k); }
            }
         }
      }
      if (step)
      {
         p->since = 0;
         p->next_t = t + dt;                 /* 시계 방식으로 돌아갈 때 박자를 잇는다 */
         return step;
      }
      return 0;
   }
   p->drc_base = 1.0;
   if (turbo) { dt *= 0.25; maxsteps = 6; }
   if (p->next_t <= 0.0 || t - p->next_t > 0.25) p->next_t = t;
   while (p->next_t <= t && steps < maxsteps) { p->next_t += dt; steps++; }
   if (steps) p->since = 0;
   return steps;
}

void drc_reset(drc_t *d)
{
   memset(d, 0, sizeof *d);
}

double drc_ratio(double base, unsigned fill, unsigned target)
{
   double e;
   if (target == 0) return base;
   e = ((double)target - (double)fill) / (double)target;   /* + = 모자람 → 늘린다 */
   if (e > 1.0) e = 1.0;
   if (e < -1.0) e = -1.0;
   return base * (1.0 + 0.005 * e);
}

size_t drc_run(drc_t *d, const int16_t *in, size_t n, int16_t *out, size_t cap, double ratio)
{
   double step, p;
   size_t w = 0;
   if (n == 0) return 0;
   if (ratio < 0.9) ratio = 0.9;
   if (ratio > 1.1) ratio = 1.1;
   if (!d->primed)
   {  /* 첫 묶음 — 앞 샘플이 없으니 첫 샘플부터 그대로 시작 */
      d->last[0] = in[0]; d->last[1] = in[1];
      d->pos = 0.0; d->primed = 1;
   }
   step = 1.0 / ratio;
   p = d->pos;
   /* 가상 입력: x[-1] = last, x[0..n-1] = in. x[i]·x[i+1] 사이를 선형으로 */
   while (p < (double)n - 1.0 && w < cap)
   {
      int i = (int)floor(p);
      double f = p - i;
      const int16_t *a = i < 0 ? d->last : in + (size_t)i * 2;
      const int16_t *b = in + (size_t)(i + 1) * 2;
      int l = (int)lrint(a[0] + (b[0] - a[0]) * f);
      int r = (int)lrint(a[1] + (b[1] - a[1]) * f);
      out[w * 2]     = (int16_t)(l < -32768 ? -32768 : l > 32767 ? 32767 : l);
      out[w * 2 + 1] = (int16_t)(r < -32768 ? -32768 : r > 32767 ? 32767 : r);
      w++;
      p += step;
   }
   if (p < (double)n - 1.0) p = (double)n - 1.0;   /* 출력이 찼다 — 남은 입력은 버린다(넘침과 같다) */
   d->pos = p - (double)n;
   d->last[0] = in[(n - 1) * 2]; d->last[1] = in[(n - 1) * 2 + 1];
   return w;
}
