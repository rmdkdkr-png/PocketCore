/* 176 덧판 메뉴 효과음 — uisfx.h 참고.
 *
 * 잰 것(u176s/ref/params.md · 호스트 코어 173 · JUE = 한글판 = 배경음악 끈 판 같은 바이트):
 *   소리칩 T6W28 3.072MHz 사각파 채널 2 하나 · 잡음·DAC 없음 · 왼쪽 = 오른쪽.
 *   f = 3072000 / (32·N) Hz · 감쇠 att → 볼륨 VOL[att](≈2dB 한 칸, 15 = 끔) · 코어 int16 출력에서 ±34·VOL(고리 버퍼는 이득 없음) —
 *   띠 제한 사각파 RMS 를 맞추려면 33·VOL.
 *   주기·감쇠가 바뀌어도(다시 울려도) 위상은 이어진다 · 앞뒤는 정확히 0.
 *   한 채널에 소리 하나: 새 소리는 우선순위가 같거나 높으면 바꾸고, 낮으면 버린다(커서 1 < 뒤로 2 < 결정 3).
 *   게임은 소리 끝 뒤 조용한 꼬리까지 막지만(결정 576ms · 뒤로 363ms), 여기서는 «머리»(결정 = 첫 메아리 전 208ms · 뒤로 = 들리는 166ms)
 *   동안만 낮은 소리를 막는다 — 결정 뒤 바로 옮긴 커서가 소리 없이 지나가지 않게.
 * 합성: 표의 {샘플@44.1k, N, att} 를 출력 샘플레이트로 늘려 그대로 · PolyBLEP 사각파 · 볼륨이 바뀔 때 0.5ms 직선(딸깍 없앰) ·
 *       끌 때(STOP·음소거) 4ms 로 줄임. */
#include "uisfx.h"
#include <stdatomic.h>
#include <string.h>
#include <math.h>

typedef struct { uint16_t at; uint16_t n; uint8_t att; } sfx_ev;   /* 44.1kHz 샘플(시작부터) · 주기 N · 감쇠 — 다음 줄까지 그대로, 마지막 줄 = 끔 */

static const sfx_ev EV_CURSOR[] = {   /* 0x0E */
   {0,183,0}, {445,183,1}, {985,183,2}, {1740,183,3}, {2246,183,4}, {3001,183,5}, {3541,183,6}, {4227,183,7}, {4767,183,8}, {5342,183,9},
   {6028,183,10}, {6568,183,11}, {7322,183,12}, {7829,183,13}, {8550,183,15}
};
static const sfx_ev EV_SELECT[] = {   /* 0x02 */
   {0,115,0}, {341,115,1}, {678,91,1}, {748,91,0}, {1241,91,1}, {1533,91,2}, {1754,86,2}, {1824,86,0}, {2254,86,1}, {2655,68,1},
   {2725,68,0}, {3155,68,1}, {3572,57,1}, {3642,57,0}, {4089,57,1}, {4235,57,2}, {4594,57,3}, {4990,57,4}, {6575,57,5}, {7116,57,6},
   {7476,57,7}, {7835,57,8}, {8376,57,9}, {8771,57,10}, {9158,115,10}, {9228,115,6}, {9705,115,7}, {10041,91,7}, {10111,91,6},
   {10606,91,7}, {10897,91,8}, {11120,86,8}, {11190,86,6}, {11618,86,7}, {12019,68,7}, {12089,68,6}, {12517,68,7}, {12935,57,7},
   {13005,57,6}, {13454,57,7}, {13598,57,8}, {13959,57,9}, {14353,57,10}, {15940,57,11}, {16479,57,12}, {16840,57,13}, {17200,57,14},
   {17740,57,15}, {18521,115,15}, {18591,115,11}, {19069,115,12}, {19407,91,12}, {19477,91,11}, {19970,91,12}, {20261,91,13},
   {20482,86,13}, {20551,86,11}, {20981,86,12}, {21382,68,12}, {21452,68,11}, {21882,68,12}, {22299,57,12}, {22369,57,11}, {22817,57,12},
   {22962,57,13}, {23323,57,14}, {23718,57,15}
};
static const sfx_ev EV_BACK[] = {     /* 0x06 */
   {0,367,0}, {107,367,1}, {347,327,1}, {417,327,0}, {708,291,0}, {993,291,1}, {1247,259,1}, {1317,259,0}, {1534,259,1}, {1788,231,1},
   {1858,231,0}, {2148,206,0}, {2434,206,1}, {2694,183,1}, {2765,183,0}, {3049,163,0}, {3334,163,1}, {3589,145,1}, {3659,145,0},
   {3875,145,1}, {4129,129,1}, {4199,129,0}, {4512,115,0}, {4775,115,1}, {4989,115,3}, {5135,115,5}, {5349,115,6}, {5495,115,8},
   {5676,115,10}, {5890,115,11}, {6062,115,12}, {6215,115,13}, {6790,115,14}, {7330,115,15}
};
#define NEV(a) ((int)(sizeof(a) / sizeof((a)[0])))
static const struct { const sfx_ev *ev; int n, prio, head; } PROG[UISFX_N] = {
   { NULL, 0, 0, 0 },
   { EV_CURSOR, NEV(EV_CURSOR), 1, 8550 },
   { EV_SELECT, NEV(EV_SELECT), 3, 9158 },
   { EV_BACK,   NEV(EV_BACK),   2, 7330 },
};
static const uint8_t VOL[16] = { 64, 50, 39, 31, 24, 19, 15, 12, 9, 7, 5, 4, 3, 2, 1, 0 };
#define AMP_UNIT 33.0f
#define CHIP_HZ  3072000.0
#define SRC_RATE 44100

static _Atomic uint32_t g_req;        /* 1 << kind — 콜백 사이에 온 요청을 모은다(가장 센 것 하나만 낸다) */
static struct {
   int rate, rlen, flen;               /* 출력 샘플레이트 · 볼륨 바뀜 직선 · 끌 때 줄임(샘플) */
   int kind, idx, fin;                 /* 지금 소리(0 없음) · 표 줄 · 1 = 마지막 줄(끔)에 닿음 · 2 = 줄여 끄는 중 */
   uint32_t pos, step;                 /* 표 시각(44.1kHz 샘플, 16.16) · 출력 샘플마다 더함 */
   double ph, dt;                      /* 사각파 위상(주기 단위) · 샘플마다 */
   float amp, tgt, inc; int ramp;      /* 볼륨(int16 반 진폭) · 목표 · 직선 남은 샘플 */
} S;

int uisfx_request(int kind)
{
   if (kind < 0 || kind >= UISFX_N) return 0;
   atomic_fetch_or_explicit(&g_req, 1u << kind, memory_order_release);
   return 1;
}

void uisfx_reset(int rate)
{
   memset(&S, 0, sizeof S);
   S.rate = rate >= 8000 && rate <= 192000 ? rate : SRC_RATE;
   S.rlen = S.rate / 2000;             /* 0.5ms */
   S.flen = S.rate / 250;              /* 4ms */
   S.step = (uint32_t)(((uint64_t)SRC_RATE << 16) / (uint32_t)S.rate);
   atomic_store_explicit(&g_req, 0, memory_order_relaxed);
}

int uisfx_active(void) { return S.kind; }

static void ramp_to(float t, int len)
{
   S.tgt = t; S.ramp = len > 0 ? len : 1;
   S.inc = (t - S.amp) / (float)S.ramp;
}
static void apply(const sfx_ev *e)
{
   S.dt = CHIP_HZ / (32.0 * e->n) / S.rate;
   ramp_to(AMP_UNIT * VOL[e->att & 15], S.rlen);
}
static void stop(void)
{
   if (!S.kind || S.fin == 2) return;
   S.fin = 2;
   ramp_to(0.0f, S.flen);
}
static void start(int kind)
{
   int cur = S.fin == 2 ? 0 : S.kind;  /* 줄여 끄는 중이면 빈 채널 */
   if (cur && PROG[kind].prio < PROG[cur].prio && (int)(S.pos >> 16) < PROG[cur].head) return;   /* 게임처럼 버림(줄 세우지 않음) */
   if (!S.kind) S.ph = 0.0;            /* 쉬던 채널 — 첫 반 주기 +A(게임과 같음). 울리는 중이면 위상 그대로 */
   S.kind = kind; S.idx = 0; S.pos = 0; S.fin = 0;
   apply(&PROG[kind].ev[0]);
}

static inline float blep(double t, double dt)
{
   if (t < dt) { t /= dt; return (float)(t + t - t * t - 1.0); }
   if (t > 1.0 - dt) { t = (t - 1.0) / dt; return (float)(t * t + t + t + 1.0); }
   return 0.0f;
}

void uisfx_mix(int16_t *out, int frames, int mute)
{
   uint32_t req = atomic_exchange_explicit(&g_req, 0, memory_order_acquire);
   if (!S.kind && (!req || mute)) return;
   if (!S.rate) uisfx_reset(SRC_RATE);
   if (mute) stop();                   /* 음소거(league_mute) — 새 요청은 버림 */
   else if (req) {
      int best = 0;
      if (req & (1u << UISFX_STOP)) stop();
      for (int k = 1; k < UISFX_N; k++) if ((req & (1u << k)) && PROG[k].prio > PROG[best].prio) best = k;
      if (best) start(best);
   }
   if (!S.kind) return;
   const sfx_ev *ev = PROG[S.kind].ev;
   int n = PROG[S.kind].n;
   for (int i = 0; i < frames; i++) {
      if (!S.fin) {
         unsigned at = S.pos >> 16;
         while (S.idx + 1 < n && at >= ev[S.idx + 1].at) apply(&ev[++S.idx]);
         if (S.idx + 1 >= n) S.fin = 1;
         S.pos += S.step;
      }
      if (S.ramp > 0) { S.amp += S.inc; if (--S.ramp == 0) S.amp = S.tgt; }
      double ph = S.ph, ph2 = ph + 0.5;
      if (ph2 >= 1.0) ph2 -= 1.0;
      float v = (ph < 0.5 ? 1.0f : -1.0f) + blep(ph, S.dt) - blep(ph2, S.dt);
      ph += S.dt;
      S.ph = ph >= 1.0 ? ph - 1.0 : ph;
      int s = (int)lrintf(v * S.amp);
      for (int c = 0; c < 2; c++) {
         int o = out[i * 2 + c] + s;
         out[i * 2 + c] = (int16_t)(o > 32767 ? 32767 : o < -32768 ? -32768 : o);
      }
      if (S.fin && S.ramp == 0 && S.tgt == 0.0f) { S.kind = 0; S.fin = 0; S.amp = 0.0f; break; }   /* 끝 — 다음 소리는 위상 0 부터 */
   }
}
