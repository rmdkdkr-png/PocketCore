/* 186 COUNTER 몸 번쩍 — 설명은 hitflash.h */
#include <string.h>
#include "hitflash.h"

static struct { int on, left; uint16_t save[3], put[3]; } HF[2];   /* 팔레트 램 16비트 칸 그대로(윗 4비트까지 되돌리려고) */

static int pal_of(int side) { return side ? 5 : 0; }
static void rd(const uint8_t *pal, int p, uint16_t c[3])
{
   for (int j = 0; j < 3; j++) { const uint8_t *e = pal + p * 8 + (j + 1) * 2; c[j] = (uint16_t)(e[0] | (e[1] << 8)); }
}
static void wr(uint8_t *pal, int p, const uint16_t c[3])
{
   for (int j = 0; j < 3; j++) { uint8_t *e = pal + p * 8 + (j + 1) * 2; e[0] = (uint8_t)c[j]; e[1] = (uint8_t)(c[j] >> 8); }
}
/* 우리 흰색인가 — 12비트만(게임이 다시 쓴 것을 코어 표가 흰색으로 바꿔 넣으면 윗 4비트는 0) */
static int ours(const uint16_t raw[3], const uint16_t put[3])
{
   for (int j = 0; j < 3; j++) if ((raw[j] & 0xFFF) != (put[j] & 0xFFF)) return 0;
   return 1;
}
static int luma(uint16_t c) { return 299 * (c & 15) + 587 * ((c >> 4) & 15) + 114 * ((c >> 8) & 15); }

/* 가장 어두운 색(같으면 뒤 칸 — 표의 색 3 이 보통 테두리)만 그대로, 나머지 둘은 흰색 */
void hitflash_colors(const uint16_t in[3], uint16_t out[3])
{
   int d = 2;
   for (int j = 1; j >= 0; j--) if (luma(in[j] & 0xFFF) < luma(in[d] & 0xFFF)) d = j;
   for (int j = 0; j < 3; j++) out[j] = j == d ? (uint16_t)(in[j] & 0xFFF) : 0xFFF;
}

void hitflash_start(uint8_t *pal, int side)
{
   uint16_t raw[3], c[3], f[3];
   if (!pal || side < 0 || side > 1) return;
   rd(pal, pal_of(side), raw);
   if (HF[side].on && ours(raw, HF[side].put)) { HF[side].left = HITFLASH_FRAMES; return; }   /* 아직 우리 흰색 — 길이만 */
   for (int j = 0; j < 3; j++) c[j] = (uint16_t)(raw[j] & 0xFFF);
   HF[side].on = 0;
   if (!(c[0] | c[1] | c[2])) return;                                   /* 빈 팔레트(몸이 없음) */
   hitflash_colors(c, f);
   memcpy(HF[side].save, raw, sizeof raw);
   for (int j = 0; j < 3; j++) HF[side].put[j] = (uint16_t)(f[j] | (raw[j] & 0xF000));
   if (!memcmp(HF[side].put, raw, sizeof raw)) return;                 /* 이미 그 색(흰 옷 · 게임 물들임이 흰색) — 할 일 없음 */
   wr(pal, pal_of(side), HF[side].put);
   HF[side].on = 1; HF[side].left = HITFLASH_FRAMES;
}

void hitflash_tick(uint8_t *pal)
{
   for (int s = 0; s < 2; s++) {
      uint16_t raw[3];
      if (!HF[s].on) continue;
      if (!pal) { HF[s].on = 0; continue; }
      rd(pal, pal_of(s), raw);
      if (!ours(raw, HF[s].put)) { HF[s].on = 0; continue; }               /* 게임이 다른 색을 썼다(물들임 등) — 게임 색 그대로 */
      if (--HF[s].left > 0) continue;
      wr(pal, pal_of(s), HF[s].save);
      HF[s].on = 0;
   }
}

void hitflash_end(uint8_t *pal)
{
   for (int s = 0; s < 2; s++) {
      uint16_t raw[3];
      if (!HF[s].on) continue;
      HF[s].on = 0;
      if (!pal) continue;
      rd(pal, pal_of(s), raw);
      if (ours(raw, HF[s].put)) wr(pal, pal_of(s), HF[s].save);
   }
}

void hitflash_forget(void) { memset(HF, 0, sizeof HF); }

int hitflash_active(void) { return (HF[0].on ? 1 : 0) | (HF[1].on ? 2 : 0); }

int hitflash_remap(int side, uint16_t from[3], uint16_t to[3])
{
   if (side < 0 || side > 1 || !HF[side].on) return 0;
   for (int j = 0; j < 3; j++) { from[j] = (uint16_t)(HF[side].save[j] & 0xFFF); to[j] = (uint16_t)(HF[side].put[j] & 0xFFF); }
   return 1;
}
