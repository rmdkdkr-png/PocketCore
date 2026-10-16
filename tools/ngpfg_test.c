/* 코어 보간(ngpfg) 합성 시험 — 에뮬레이터 없이 프레임을 지어 계획·그리기만 본다.
 *   cc -O2 -I <core> tools/ngpfg_test.c <core>/ngpfg.c -o ngpfg_test && ./ngpfg_test
 * 장면: 플레인 2 에 세로 경계 하나(가로 스크롤), 스프라이트 둘(체인 2 조각 묶음 A, 단독 B), 오락가락 C.
 * 출력(120Hz 한 칸)마다 경계의 4배 x 를 재서 한 칸에 몇 ¼px 움직였나 — 등속이면 늘 같아야 한다. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "ngpfg.h"

static uint8_t scroll[4096], chr[8192], spr[256], sprcol[64], pal[512];
static uint32_t cmap[4096];
static uint16_t out[640 * 608];

static void st16(uint8_t *p, uint16_t v) { p[0] = v & 255; p[1] = v >> 8; }

/* 타일 t 의 줄 데이터: 화소 p(0..7) 색 번호 */
static void tile_cols(int t, const int *idx8)
{
   int y, p;
   for (y = 0; y < 8; y++)
   {
      uint16_t d = 0;
      for (p = 0; p < 8; p++) d |= (uint16_t)((idx8[p] & 3) << (14 - 2 * p));
      st16(chr + t * 16 + y * 2, d);
   }
}

typedef struct { int s2x, ax, bx, cx, by; } scene;

static void capture(const scene *sc)
{
   int y;
   ngpfg_regs r;
   memset(&r, 0, sizeof r);
   r.winx = 0; r.winw = 160; r.winy = 0; r.winh = 152;
   r.s2x = (uint8_t)sc->s2x;
   r.bgc = 0; r.oowc = 0;
   for (y = 0; y < 152; y++) ngpfg_capture_line(y, &r);
   memset(spr, 0, sizeof spr);
   /* A: 조각 0 (타일 3) + 조각 1 (타일 3, 가로 체인 +8) — 우선순위 3, 팔레트 1 */
   st16(spr + 0, (uint16_t)(3 | (3 << 11))); spr[2] = (uint8_t)sc->ax; spr[3] = 100;
   st16(spr + 4, (uint16_t)(3 | (3 << 11) | 0x0400)); spr[6] = 8; spr[7] = 0;
   sprcol[0] = sprcol[1] = 1;
   /* B: 조각 2 (타일 4) 단독 — 팔레트 2 */
   st16(spr + 8, (uint16_t)(4 | (3 << 11))); spr[10] = (uint8_t)sc->bx; spr[11] = (uint8_t)sc->by;
   sprcol[2] = 2;
   /* C: 조각 3 (타일 5) — 오락가락, 팔레트 3 */
   st16(spr + 12, (uint16_t)(5 | (3 << 11))); spr[14] = (uint8_t)sc->cx; spr[15] = 60;
   sprcol[3] = 3;
   ngpfg_capture_end(scroll, chr, spr, sprcol, pal, NULL, 0, 7);
}

/* 4배 줄 Y 에서 색 c 가 처음 나오는 X (없으면 -1) */
static int first_x(int Y, uint16_t c)
{
   int X;
   for (X = 0; X < 640; X++) if (out[Y * 640 + X] == c) return X;
   return -1;
}

int main(void)
{
   int i, n, fails = 0;
   const int idxL[8] = { 1, 1, 1, 1, 2, 2, 2, 2 }, idxS[8] = { 1, 1, 1, 1, 1, 1, 1, 1 }, idxB[8] = { 2, 2, 2, 2, 2, 2, 2, 2 }, idxC[8] = { 3, 3, 3, 3, 3, 3, 3, 3 };
   for (i = 0; i < 4096; i++) cmap[i] = (uint32_t)i;          /* 12비트 색 그대로 */
   tile_cols(2, idxL); tile_cols(3, idxS); tile_cols(4, idxB); tile_cols(5, idxC);
   /* 플레인 2 (0x0800): 맵 줄 0..18 의 칸 10 에 타일 2(왼 4 화소 색 1, 오른 4 화소 색 2) */
   for (i = 0; i < 19; i++) st16(scroll + 0x0800 + ((10 + i * 32) << 1), 2);
   /* 팔레트: 플레인 2 팔레트 0 = 0x100.. 색 1 = 0x00F, 색 2 = 0x0F0; 스프라이트 팔레트 1 색 1 = 0xF00, 팔레트 2 색 2 = 0xFF0, 팔레트 3 색 3 = 0x0FF */
   st16(pal + 0x100 + 2, 0x00F); st16(pal + 0x100 + 4, 0x0F0);
   st16(pal + 8 + 2, 0xF00); st16(pal + 16 + 4, 0xFF0); st16(pal + 24 + 6, 0x0FF);
   st16(pal + 0x1E0, 0x777);

   /* 프레임 0..N: s2x 는 2 프레임마다 −1(30Hz, 배경이 오른쪽으로), A 는 2 프레임마다 +2(30Hz, 위상 1), B 는 매 프레임 +1(60Hz),
      C 는 2 프레임마다 ±3 오락가락 */
   n = 40;
   {
      scene sc[64];
      int D, h, prevE = -1, prevA = -1, prevB = -1, prevC = -1;
      int dE[128], dA[128], dB[128], dC[128], m = 0;
      for (i = 0; i < n + 3; i++)
      {
         sc[i].s2x = 40 - i / 2;
         sc[i].ax = 30 + 2 * ((i + 1) / 2);
         sc[i].bx = 20 + i; sc[i].by = 120;
         sc[i].cx = 120 + ((i / 2) % 2 ? 3 : 0);
      }
      ngpfg_reset();
      for (D = 0; D < n; D++)
      {
         int bits;
         capture(&sc[D]);
         capture(&sc[D + 1]); capture(&sc[D + 2]);
         bits = ngpfg_plan(ngpfg_hist(4), ngpfg_hist(3), ngpfg_hist(2), ngpfg_hist(1), ngpfg_hist(0));
         for (h = 0; h < 2; h++)
         {
            int E, A, B, C;
            if (bits < 0) { printf("D%d plan 불가\n", D); fails++; break; }
            ngpfg_render4(h, out, 640, cmap);
            E = first_x(4 * 10 + 1, 0x0F0);           /* 경계(색 2 시작) — 4배 줄 41 (조각 없는 줄) */
            A = first_x(4 * 100 + 1, 0xF00);
            B = first_x(4 * 120 + 1, 0xFF0);
            C = first_x(4 * 60 + 1, 0x0FF);
            if (D >= 4)
            {
               dE[m] = prevE >= 0 ? E - prevE : 0; dA[m] = prevA >= 0 ? A - prevA : 0;
               dB[m] = prevB >= 0 ? B - prevB : 0; dC[m] = prevC >= 0 ? C - prevC : 0; m++;
            }
            prevE = E; prevA = A; prevB = B; prevC = C;
         }
         ngpfg_capture_pop(); ngpfg_capture_pop();
      }
      printf("칸마다 ¼px 이동 (D≥4)\n");
      printf("배경(30Hz −1px) "); for (i = 1; i < m; i++) printf("%d ", dE[i]); printf("\n");
      printf("A(30Hz +2px)    "); for (i = 1; i < m; i++) printf("%d ", dA[i]); printf("\n");
      printf("B(60Hz +1px)    "); for (i = 1; i < m; i++) printf("%d ", dB[i]); printf("\n");
      printf("C(오락가락 ±3)  "); for (i = 1; i < m; i++) printf("%d ", dC[i]); printf("\n");
      for (i = 1; i < m; i++)
      {
         if (dE[i] != 1) fails++;
         if (dA[i] != 2) fails++;
         if (dB[i] != 2) fails++;
         if (dC[i] != 0 && dC[i] != 12 && dC[i] != -12) fails++;
      }
   }
   printf("%s (어긋남 %d)\n", fails ? "실패" : "통과", fails);
   return fails ? 1 : 0;
}
