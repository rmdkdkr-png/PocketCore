/* 음색 다듬기 — 설명은 sndfx.h. */
#include "sndfx.h"
#include "sndfx_ir.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define B    512              /* 울림 칸(샘플) — 응답 앞 지연(20ms = 882)보다 작아야 지연 0 */
#define N    (2 * B)          /* FFT 크기 */
#define NB   (B + 1)          /* 실수 신호 스펙트럼 칸 0..B */

#define RT_S     1.3          /* 울림 길이(−60dB 까지) */
#define TAIL_S   1.0          /* 응답을 이만큼까지 쓴다 — 1.0 뒤 꼬리는 −42dB 아래(1.3 과 견줘 출력 차이 −50dB), 계산 −20% */
#define PRE_S    0.02         /* 울림 앞 지연 */
#define DRY      0.8f
#define WET      0.32f        /* 0.2 × 1.6 — 들어 보기 ② 와 같다 */
#define GAIN     0.91f        /* 크기 맞춤 — 원래 소리와 RMS 가 같게(tools/sndfx_ref.py 실측: 데모 대전 0.883 · 대전곡만 0.932, 들어 보기 ② 가 그 구간에 쓴 배율 0.918) */

struct sndfx {
   int rate, P, xpos, bpos, wlen, wpos, macp;
   float lb0, lb1, la1, hb0, hb1, ha1, gl, gh, gain;
   float lz[2], hz[2];
   float *wbuf;
   float *Hr[2], *Hi[2];      /* 칸 p 응답 스펙트럼: [p * NB + k] */
   float *Xr[2], *Xi[2];      /* 지난 입력 창 스펙트럼 고리: [q * NB + k] */
   float in[2][N];            /* 앞 칸 + 이번 칸 */
   float wet[2][B];           /* 이번 칸에 낼 울림 */
   float accr[2][NB], acci[2][NB];   /* 이번 칸 울림 스펙트럼 중 지난 입력 몫(p ≥ 1) — 샘플을 받는 동안 나눠 더한다 */
   float tw_re[N / 2], tw_im[N / 2];
   int rev[N];
   float *ir[2];              /* 시험용: 앞 지연을 포함한 응답 */
   size_t irlen;
};

static void fft(const sndfx_t *f, float *re, float *im, int inv)
{
   int i, j, len;
   for (i = 0; i < N; i++) {
      j = f->rev[i];
      if (j > i) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
   }
   for (len = 2; len <= N; len <<= 1) {
      int half = len >> 1, step = N / len;
      for (i = 0; i < N; i += len)
         for (j = 0; j < half; j++) {
            float wr = f->tw_re[j * step], wi = inv ? -f->tw_im[j * step] : f->tw_im[j * step];
            float xr = re[i + j + half], xi = im[i + j + half];
            float tr = xr * wr - xi * wi, ti = xr * wi + xi * wr;
            re[i + j + half] = re[i + j] - tr; im[i + j + half] = im[i + j] - ti;
            re[i + j] += tr; im[i + j] += ti;
         }
   }
}

/* 44.1kHz 는 들어 보기 ② 의 응답을 그대로(sndfx_ir.h — tools/sndfx_ir_gen.py). 다른 빠르기만 여기서 만든다:
   결정적 잡음 — splitmix64 카운터(씨앗 + (i+1)·황금비)를 섞어 [0,1) → 박스-뮬러. 씨앗은 ② 응답과 옥타브 대역 에너지가 ±0.4dB 안인 것
   (3000개 중 — 음 하나하나의 울림 크기까지 같진 않다) */
#define SEED_L 0x5EED0149ULL
#define SEED_R 0x5EED0B73ULL
static double noise_u(uint64_t seed, uint64_t i)
{
   uint64_t z = seed + (i + 1) * 0x9E3779B97F4A7C15ULL;
   z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL; z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL; z ^= z >> 31;
   return ((double)(z >> 11) + 0.5) * (1.0 / 9007199254740992.0);
}

sndfx_t *sndfx_new(int rate)
{
   sndfx_t *f;
   int c, i, p, D, L, Lt, Lg;
   double K;
   if (rate < 22050 || rate > 192000) return NULL;
   D = (int)(PRE_S * rate);
   if (D < B) return NULL;
   f = (sndfx_t *)calloc(1, sizeof *f);
   if (!f) return NULL;
   f->rate = rate;
   /* 셸프 — 1차 버터워스(쌍선형, 미리 휨): 저역 b0=b1=K/(1+K) · 고역 b0=1/(1+K), b1=−b0 · a1=(K−1)/(K+1) */
   K = tan(M_PI * 180.0 / rate);
   f->lb0 = f->lb1 = (float)(K / (1 + K)); f->la1 = (float)((K - 1) / (K + 1));
   K = tan(M_PI * 4500.0 / rate);
   f->hb0 = (float)(1 / (1 + K)); f->hb1 = -f->hb0; f->ha1 = (float)((K - 1) / (K + 1));
   f->gl = (float)(pow(10.0, 3.5 / 20) - 1); f->gh = (float)(pow(10.0, -4.0 / 20) - 1);
   f->gain = GAIN;
   f->macp = 1;
   f->wlen = (int)(9.0 / 1000 * rate);
   f->wbuf = (float *)calloc((size_t)f->wlen, sizeof(float));
   /* FFT 표 */
   for (i = 0; i < N / 2; i++) { f->tw_re[i] = (float)cos(-2 * M_PI * i / N); f->tw_im[i] = (float)sin(-2 * M_PI * i / N); }
   for (i = 0; i < N; i++) { int r = 0, b, v = i; for (b = 1; b < N; b <<= 1) { r = (r << 1) | (v & 1); v >>= 1; } f->rev[i] = r; }
   /* 울림 응답: 잡음 × exp(−6.9 t / RT) → 6kHz 2차 버터워스 저역 → 에너지 1 → 앞에 D 샘플 0 */
   L = (int)(RT_S * rate); Lt = (int)(TAIL_S * rate); if (Lt > L) Lt = L;
   f->irlen = (size_t)(D + Lt);
   Lg = D + Lt - B;                                 /* 응답을 B 만큼 앞당긴 것(앞 B 칸은 늘 0) */
   f->P = (Lg + B - 1) / B;
   for (c = 0; c < 2; c++) {
      uint64_t s = c ? SEED_R : SEED_L;
      double *g = (double *)malloc(sizeof(double) * (size_t)L), e = 0, z1 = 0, z2 = 0;
      double Kq = tan(M_PI * 6000.0 / rate), q = sqrt(2.0), nn = 1 / (1 + Kq * q + Kq * Kq);
      double b0 = Kq * Kq * nn, b1 = 2 * b0, b2 = b0, a1 = 2 * (Kq * Kq - 1) * nn, a2 = (1 - Kq * q + Kq * Kq) * nn;
      float *re = (float *)malloc(sizeof(float) * N), *im = (float *)malloc(sizeof(float) * N);
      f->ir[c] = (float *)calloc(f->irlen, sizeof(float));
      f->Hr[c] = (float *)calloc((size_t)f->P * NB, sizeof(float)); f->Hi[c] = (float *)calloc((size_t)f->P * NB, sizeof(float));
      f->Xr[c] = (float *)calloc((size_t)f->P * NB, sizeof(float)); f->Xi[c] = (float *)calloc((size_t)f->P * NB, sizeof(float));
      if (!g || !re || !im || !f->ir[c] || !f->Hr[c] || !f->Hi[c] || !f->Xr[c] || !f->Xi[c] || !f->wbuf) {
         free(g); free(re); free(im); sndfx_free(f); return NULL;
      }
      if (rate == SNDFX_IR_RATE)                       /* 들어 보기 ② 응답 그대로 */
         for (i = 0; i < Lt; i++) f->ir[c][D + i] = i < SNDFX_IR_LEN ? SNDFX_IR[c][i] * SNDFX_IR_SCALE : 0.0f;
      else {
         for (i = 0; i < L; i += 2) {
            double u1 = noise_u(s, (uint64_t)i), u2 = noise_u(s, (uint64_t)i + 1), r = sqrt(-2 * log(u1));
            g[i] = r * cos(2 * M_PI * u2);
            if (i + 1 < L) g[i + 1] = r * sin(2 * M_PI * u2);
         }
         for (i = 0; i < L; i++) {
            double x = g[i] * exp(-6.9 * ((double)i / rate) / RT_S), y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y;
            g[i] = y; e += y * y;
         }
         e = 1 / sqrt(e);
         for (i = 0; i < Lt; i++) f->ir[c][D + i] = (float)(g[i] * e);
      }
      free(g);
      for (p = 0; p < f->P; p++) {
         int k;
         memset(re, 0, sizeof(float) * N); memset(im, 0, sizeof(float) * N);
         for (k = 0; k < B; k++) { size_t t = (size_t)B + (size_t)p * B + (size_t)k; re[k] = t < f->irlen ? f->ir[c][t] : 0; }
         fft(f, re, im, 0);
         for (k = 0; k < NB; k++) { f->Hr[c][p * NB + k] = re[k]; f->Hi[c][p * NB + k] = im[k]; }
      }
      free(re); free(im);
   }
   return f;
}

void sndfx_free(sndfx_t *f)
{
   int c;
   if (!f) return;
   for (c = 0; c < 2; c++) { free(f->Hr[c]); free(f->Hi[c]); free(f->Xr[c]); free(f->Xi[c]); free(f->ir[c]); }
   free(f->wbuf);
   free(f);
}

void sndfx_reset(sndfx_t *f)
{
   int c;
   if (!f) return;
   for (c = 0; c < 2; c++) {
      f->lz[c] = f->hz[c] = 0;
      memset(f->Xr[c], 0, sizeof(float) * (size_t)f->P * NB); memset(f->Xi[c], 0, sizeof(float) * (size_t)f->P * NB);
   }
   memset(f->in, 0, sizeof f->in); memset(f->wet, 0, sizeof f->wet);
   memset(f->wbuf, 0, sizeof(float) * (size_t)f->wlen);
   memset(f->accr, 0, sizeof f->accr); memset(f->acci, 0, sizeof f->acci);
   f->xpos = f->bpos = f->wpos = 0; f->macp = 1;
}

/* 지난 입력 몫(칸 p ≥ 1) 을 p = macp 부터 upto 앞까지 더한다 — 지금 받는 칸 j 의 울림은 X_{j−p}·H_p 의 합이고,
   p ≥ 1 몫은 앞 칸까지의 입력만 쓰니 칸을 받는 동안 미리 나눠 계산해 둘 수 있다(소리 카드 콜백마다 일이 고르게 — 칸 끝에 몰리지 않게).
   고리에서 X_{j−p} 자리 = xpos − (p − 1) (xpos = 가장 최근에 넣은 X_{j−1}) */
static void mac_upto(sndfx_t *f, int upto)
{
   int c, k;
   for (; f->macp < upto; f->macp++) {
      int q = f->xpos - (f->macp - 1); if (q < 0) q += f->P;
      for (c = 0; c < 2; c++) {
         const float *restrict xr = f->Xr[c] + (size_t)q * NB, *restrict xi = f->Xi[c] + (size_t)q * NB;
         const float *restrict hr = f->Hr[c] + (size_t)f->macp * NB, *restrict hi = f->Hi[c] + (size_t)f->macp * NB;
         float *restrict yr = f->accr[c], *restrict yi = f->acci[c];
         for (k = 0; k < NB; k++) { yr[k] += xr[k] * hr[k] - xi[k] * hi[k]; yi[k] += xr[k] * hi[k] + xi[k] * hr[k]; }
      }
   }
}

/* 한 칸을 다 받았다 — 다음 칸에 낼 울림을 만든다(응답을 B 앞당겨 두어 이번 칸의 겹쳐 버리기 결과가 곧 다음 칸 울림).
   좌우를 한 복소 FFT 로(왼쪽 = 실수부, 오른쪽 = 허수부) — 앞으로 한 번, 뒤로 한 번 */
static void block(sndfx_t *f)
{
   float re[N], im[N];
   int c, k;
   mac_upto(f, f->P);                                  /* 못 다 한 p ≥ 1 몫 */
   f->xpos = f->xpos + 1 == f->P ? 0 : f->xpos + 1;    /* X_j 자리(가장 오래된 X_{j−P} 를 덮는다 — 위에서 다 썼다) */
   memcpy(re, f->in[0], sizeof re); memcpy(im, f->in[1], sizeof im);
   fft(f, re, im, 0);
   for (k = 0; k < NB; k++) {                          /* Z = XL + i·XR 에서 가르기: XL = (Z[k] + Z*[N−k]) / 2 · XR = (Z[k] − Z*[N−k]) / 2i */
      int kn = k ? N - k : 0;
      float ar = re[k], ai = im[k], br = re[kn], bi = -im[kn];
      size_t o = (size_t)f->xpos * NB + (size_t)k;
      f->Xr[0][o] = 0.5f * (ar + br); f->Xi[0][o] = 0.5f * (ai + bi);
      f->Xr[1][o] = 0.5f * (ai - bi); f->Xi[1][o] = -0.5f * (ar - br);
   }
   for (c = 0; c < 2; c++) {                           /* p = 0 몫(X_j·H_0) + 미리 더해 둔 몫 → accr/acci 에 */
      const float *hr = f->Hr[c], *hi = f->Hi[c], *xr = f->Xr[c] + (size_t)f->xpos * NB, *xi = f->Xi[c] + (size_t)f->xpos * NB;
      for (k = 0; k < NB; k++) {
         f->accr[c][k] += xr[k] * hr[k] - xi[k] * hi[k];
         f->acci[c][k] += xr[k] * hi[k] + xi[k] * hr[k];
      }
   }
   for (k = 0; k < NB; k++) {                          /* Y = YL + i·YR (둘 다 실수 신호의 스펙트럼) → 뒤로 한 번이면 실수부 = 왼쪽, 허수부 = 오른쪽 */
      re[k] = f->accr[0][k] - f->acci[1][k]; im[k] = f->acci[0][k] + f->accr[1][k];
      if (k && k < B) { re[N - k] = f->accr[0][k] + f->acci[1][k]; im[N - k] = -f->acci[0][k] + f->accr[1][k]; }
   }
   fft(f, re, im, 1);
   for (k = 0; k < B; k++) { f->wet[0][k] = re[B + k] * (1.0f / N); f->wet[1][k] = im[B + k] * (1.0f / N); }
   for (c = 0; c < 2; c++) {
      memmove(f->in[c], f->in[c] + B, sizeof(float) * B);
      memset(f->accr[c], 0, sizeof f->accr[c]); memset(f->acci[c], 0, sizeof f->acci[c]);
   }
   f->macp = 1;
}

void sndfx_run(sndfx_t *f, int16_t *buf, size_t frames)
{
   size_t i = 0;
   int c;
   while (i < frames) {
      size_t seg = (size_t)(B - f->bpos), e;
      if (seg > frames - i) seg = frames - i;
      for (e = i + seg; i < e; i++) {
         float y[2];
         for (c = 0; c < 2; c++) {
            float v = buf[2 * i + c], lp, hp;
            lp = f->lb0 * v + f->lz[c]; f->lz[c] = f->lb1 * v - f->la1 * lp; v += f->gl * lp;
            hp = f->hb0 * v + f->hz[c]; f->hz[c] = f->hb1 * v - f->ha1 * hp; v += f->gh * hp;
            y[c] = v;
         }
         {
            float m = 0.5f * (y[0] + y[1]), d = f->wbuf[f->wpos];
            f->wbuf[f->wpos] = m; if (++f->wpos == f->wlen) f->wpos = 0;
            y[0] += 0.35f * d; y[1] -= 0.21f * d;
         }
         for (c = 0; c < 2; c++) {
            float o = f->gain * (DRY * y[c] + WET * f->wet[c][f->bpos]);
            f->in[c][B + f->bpos] = y[c];
            buf[2 * i + c] = (int16_t)(o > 32767.f ? 32767 : o < -32768.f ? -32768 : lrintf(o));
         }
         f->bpos++;
      }
      if (f->bpos == B) { block(f); f->bpos = 0; }
      else mac_upto(f, 1 + (int)((long)(f->P - 1) * f->bpos / B));   /* 받은 만큼 비례해서 */
   }
}

const float *sndfx_ir(const sndfx_t *f, int ch, size_t *len) { if (len) *len = f->irlen; return f->ir[ch & 1]; }
float sndfx_gain(const sndfx_t *f) { return f->gain; }
