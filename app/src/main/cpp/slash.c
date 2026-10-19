/* 베기 색 — slash.h 참고. 표는 slash_gen.py 가 만든 slash_tab.h(롬 색 번호만), 프리셋은 아래 손 표.
   색 대응(tmap): 원래 세 색을 밝기(정수 299R+587G+114B) 큰 순으로(같으면 앞 칸 먼저) — 000(검정 테)인 칸은 000 그대로, 나머지 칸에
   차례로 core·mid·edge. 검정 테를 남기면 밝은 무대에서도 효과 모양이 잡히고, 동봉 코어의 «색 3 = 000» 저장 순서 함정이 겉으로 안 나온다(to3 = from3).
   궤적·필살(+1~+4) = T 세 색, 내가 벤 섬광(BA) = H 세 색(FFF · T.core · T.mid — 원래처럼 밝은 번쩍 · 서리만 T).
   짝꿍(나코루루 +4 매·늑대 · 리무루루 +3 콘루 · 갈포드 +3 분신 · +4 포피)·막기 불꽃/별/MAX 기둥(B7)·그림자·상태 이상은 표에 안 넣는다(원래 색). */
#include <string.h>
#include "slash.h"
#include "slash_tab.h"

#define RAM_BASE 0x4000
#define R_MODE  (0x40A7 - RAM_BASE)
#define R_SCR   (0x41C0 - RAM_BASE)
#define R_FIGHT (0x411A - RAM_BASE)
#define R_P1BLK (0x5B51 - RAM_BASE)
#define R_P2BLK (0x5D51 - RAM_BASE)

const char *const SLASH_NAME[SLASH_N] = { "원래 색", "청염", "핏빛", "비취", "벚꽃", "흑염", "서리" };
/* 12비트 0x0BGR — T(core · mid · edge) · H(내가 벤 섬광). 흑염 mid 815 → A27(어두운 무대에서도 몸통이 읽히게, 밝기 2.98 → 4.4/15 — 설계 §3) */
static const uint16_t PRE_T[SLASH_N][3] = {
   { 0, 0, 0 },
   { 0xFFE, 0xFA4, 0xC30 },   /* 청염 */
   { 0xBBF, 0x11E, 0x005 },   /* 핏빛 */
   { 0xEFD, 0x8D2, 0x350 },   /* 비취 */
   { 0xFEF, 0xC8F, 0x62B },   /* 벚꽃 */
   { 0xF9C, 0xA27, 0x201 },   /* 흑염 */
   { 0xFFF, 0xFEB, 0xB74 },   /* 서리 */
};
static const uint16_t PRE_H[SLASH_N][3] = {
   { 0, 0, 0 },
   { 0xFFF, 0xFFE, 0xFA4 },
   { 0xFFF, 0xBBF, 0x11E },
   { 0xFFF, 0xEFD, 0x8D2 },
   { 0xFFF, 0xFEF, 0xC8F },
   { 0xFFF, 0xF9C, 0xA27 },
   { 0xFFF, 0xFEB, 0xB74 },
};
static const uint16_t ICON_GENERIC[3] = { 0xFFF, 0x0DF, 0x09F };   /* 가운데 무작위 칸 — 보통 금빛 궤적 */

static int luma(uint16_t c) { return 299 * (c & 15) + 587 * ((c >> 4) & 15) + 114 * ((c >> 8) & 15); }
/* 밝기 순위(큰 것부터, 같으면 앞 칸) — ord[r] = r 번째로 밝은 칸 */
static void rank3(const uint16_t s[3], int ord[3])
{
   ord[0] = 0; ord[1] = 1; ord[2] = 2;
   for (int i = 0; i < 3; i++)
      for (int j = i + 1; j < 3; j++)
         if (luma(s[ord[j]]) > luma(s[ord[i]]) || (luma(s[ord[j]]) == luma(s[ord[i]]) && ord[j] < ord[i])) { int t = ord[i]; ord[i] = ord[j]; ord[j] = t; }
}
static void tmap(const uint16_t s[3], const uint16_t th[3], int16_t out[3])
{
   int ord[3], r = 0;
   rank3(s, ord);
   for (int k = 0; k < 3; k++) {
      int i = ord[k];
      if (s[i] == 0) out[i] = 0;
      else out[i] = (int16_t)th[r++];
   }
}

void slash_clear(slash_want *w)
{
   for (int p = 0; p < SLASH_NPAL; p++)
      for (int k = 0; k < SLASH_NSLOT; k++) { w->s[p][k].from[0] = -1; w->s[p][k].from[1] = w->s[p][k].from[2] = 0; memset(w->s[p][k].to, 0, sizeof w->s[p][k].to); }
}
static int tri_eq16(const int16_t a[3], const uint16_t b[3]) { return a[0] == (int16_t)b[0] && a[1] == (int16_t)b[1] && a[2] == (int16_t)b[2]; }
/* 그 팔레트의 다음 빈 자리에 «from → tmap(from, th)» — 같은 from 이 이미 있거나 자리가 없으면 안 넣는다 */
static void add(slash_want *w, int p, const uint16_t from[3], const uint16_t th[3])
{
   if (from[0] == SLASH_NONE) return;
   for (int k = 0; k < SLASH_NSLOT; k++) {
      slash_pair *q = &w->s[p][k];
      if (q->from[0] >= 0) { if (tri_eq16(q->from, from)) return; continue; }
      for (int j = 0; j < 3; j++) q->from[j] = (int16_t)from[j];
      tmap(from, th, q->to);
      return;
   }
}
static int partner(int ch, int j)                   /* j = 0~3 → +1~+4 */
{
   return (ch == 4 && j == 3)                       /* 나코루루 +4: 매(수라)·늑대(나찰) */
       || (ch == 5 && j == 2)                       /* 리무루루 +3: 콘루 */
       || (ch == 7 && (j == 2 || j == 3));          /* 갈포드 +3 분신 · +4 포피 */
}

int slash_window(const uint8_t *ram)
{
   int md = ram[R_MODE], scr = ram[R_SCR];
   return (md == 241 && !(scr == 0 && ram[R_FIGHT] == 0)) || (md == 240 && scr >= 8);
}

void slash_build(const uint8_t *ram, int side, int preset, slash_want *w)
{
   if (!ram || preset <= 0 || preset >= SLASH_N || side < 0 || side > 1) return;
   int key = ram[side ? R_P2BLK : R_P1BLK] >> 2, ch = key >> 2, base = side ? 6 : 1;
   const uint16_t *t = PRE_T[preset];
   for (int j = 0; j < 4; j++) if (!partner(ch, j)) add(w, base + j, SLASH_SET[key][j], t);
   if (ch == 11) add(w, base, SLASH_DYN1_UKYO, t);  /* 기술 중 +1(움직이며 쓰는 값) — 자리 1 */
   if (ch == 6) add(w, base, SLASH_6B, t);
   add(w, side ? 10 : 11, SLASH_BA, PRE_H[preset]); /* 내가 벤 섬광 = 맞은 쪽 공용 칸(«때린 사람 색») */
}

/* 원래 색 아이콘 — +2(대부분의 칼 궤적)부터 +1·+3 순으로, 가장 밝은 색이 ICON_MIN_LUMA 이상인 첫 세트(검은 판 위에서 보이게).
   165 리뷰: 아수라 +2 는 D02/701/000(남색·검정 테 — 판 바탕과 같아 거의 안 보임, 1인 대전 처음 커서) → +1 FBF/E6E/C0C. 다 어두우면 보통 금빛 */
#define ICON_MIN_LUMA 6000          /* luma() 최대 15000 의 0.4 — 아수라 +2 core 2080 · 다른 캐릭터 +2 core 는 가장 어두운 것도 11744(시키 나찰) */
void slash_icon(int preset, int look_row, int sty, uint16_t out[3])
{
   static const int TRY[3] = { 1, 0, 2 };
   if (preset > 0 && preset < SLASH_N) { memcpy(out, PRE_T[preset], 6); return; }
   if (look_row < 0 || look_row >= 14) { memcpy(out, ICON_GENERIC, 6); return; }
   for (int i = 0; i < 3; i++) {
      const uint16_t *s = SLASH_SET[(look_row << 2) | ((sty & 1) << 1)][TRY[i]];
      if (s[0] == SLASH_NONE) continue;
      int ord[3];
      rank3(s, ord);
      if (luma(s[ord[0]]) < ICON_MIN_LUMA) continue;
      for (int r = 0; r < 3; r++) out[r] = s[ord[r]];
      return;
   }
   memcpy(out, ICON_GENERIC, 6);
}

static void pget(const uint8_t *pal, int p, uint16_t c[3]) { for (int j = 0; j < 3; j++) { const uint8_t *e = pal + p * 8 + (j + 1) * 2; c[j] = (uint16_t)((e[0] | (e[1] << 8)) & 0xFFF); } }
static void pput(uint8_t *pal, int p, const int16_t c[3]) { for (int j = 0; j < 3; j++) { uint8_t *e = pal + p * 8 + (j + 1) * 2; e[0] = (uint8_t)c[j]; e[1] = (uint8_t)((uint16_t)c[j] >> 8); } }
static int ceq(const uint16_t c[3], const int16_t t[3]) { return c[0] == (uint16_t)t[0] && c[1] == (uint16_t)t[1] && c[2] == (uint16_t)t[2]; }

int slash_fix(uint8_t *pal, const slash_want *w)
{
   int n = 0;
   if (!pal || !w) return 0;
   for (int p = 1; p < SLASH_NPAL; p++) {
      if (w->s[p][0].from[0] < 0) continue;
      uint16_t c[3];
      pget(pal, p, c);
      for (int k = 0; k < SLASH_NSLOT; k++) {
         const slash_pair *q = &w->s[p][k];
         if (q->from[0] < 0) continue;
         if (ceq(c, q->to)) break;
         int nfrom = 0, ok = 1;
         for (int j = 0; j < 3 && ok; j++) {
            if (c[j] == (uint16_t)q->from[j] && c[j] != (uint16_t)q->to[j]) nfrom++;
            else if (c[j] != (uint16_t)q->to[j]) ok = 0;
         }
         if (!ok || !nfrom) continue;
         pput(pal, p, q->to); n++;
         break;
      }
   }
   return n;
}
void slash_restore(uint8_t *pal, const slash_want *w)
{
   if (!pal || !w) return;
   for (int p = 1; p < SLASH_NPAL; p++) {
      uint16_t c[3];
      pget(pal, p, c);
      for (int k = 0; k < SLASH_NSLOT; k++) {
         const slash_pair *q = &w->s[p][k];
         if (q->from[0] >= 0 && ceq(c, q->to)) { pput(pal, p, q->from); break; }
      }
   }
}
void slash_reapply(uint8_t *pal, const slash_want *w)
{
   if (!pal || !w) return;
   for (int p = 1; p < SLASH_NPAL; p++) {
      uint16_t c[3];
      pget(pal, p, c);
      for (int k = 0; k < SLASH_NSLOT; k++) {
         const slash_pair *q = &w->s[p][k];
         if (q->from[0] >= 0 && ceq(c, q->from)) { pput(pal, p, q->to); break; }
      }
   }
}
int slash_any(const slash_want *w)
{
   for (int p = 0; p < SLASH_NPAL; p++) if (w->s[p][0].from[0] >= 0) return 1;
   return 0;
}
