/* 153 VS 띠 — 랭크 매치 경기 직전 무대 인트로(«대사» 단계)에 게임 화면 맨 위 검은 띠(판 0 = scroll plane 1, 0~3줄 × 0~19칸,
   경기에선 체력·이름 HUD 가 서는 자리)에 Java 가 그린 글자(내·상대 닉네임·티어·LP·VS)를 얹는다.

   실측(u153/vsgame — 한글 동봉판 내 캐릭터 14명 전부 × 상대 15명 + JUE, 인트로 243번):
     - 인트로 동안 띠 80칸은 모두 타일 0x001(불투명 검정, 칠 번호 0) — «자아 당당히» 배너가 시작되면(0x57CB 0x0C→0x18…0x21) 게임이 같은 타일을
       칠 번호 15 로 다시 쓰고(0x1E01), 경기(MODE 241)가 되면 HUD 로 바꾼다.
     - 타일: 판 0 = 0x000~0x056(띠·대사 글자·배너 큰 글자·경기 HUD) · 무대(판 1) = 0x106~0x1FF · 싸우는 캐릭터 스프라이트 = 0x057~0x09C,
       그 뒤는 딸린 것(나코루루 마마하하·리무루루 콘루·갈포드 포피 — 둘 다 딸린 것이 있는 인트로에서 0x0AA 까지)·경기 중 효과(경기에서 0x0CF 까지 봄).
       0x0D0~0x105 는 인트로·경기 모두 아무도 안 썼다 → 우리 글자 첫 자리(54타일). 모자라면 0x0B6~0x0CF(인트로 243번 모두 안 쓰임 — 0x0AA 위로 11타일 여유, 26타일).
     - 판 0 팔레트 2·3·4·5·9·13·14 는 인트로·배너 내내 그대로(0 은 대사 단계에 바뀌고 배너 동안 깜빡임, 1·15 는 배너 때, 8~11 은 경기 때 바뀜).
   안전판: 매 프레임 우리 자리 타일 그림이 그대로인지·스프라이트가 우리 자리 타일을 가리키지 않는지 본다 — 아니면 띠를 거두고(그림이 아직 우리 것이면
   원래 그림으로) 이번 띠는 포기한다(vsband_clash). 칸의 주인은 여기 하나(원래 칸 값·원래 타일 그림 보관 · 되돌리기).
   모두 코어 잠금 안(GL 스레드 retro_run 뒤 · 상태 저장 직전)에서만 불린다. */
#include <string.h>
#include "vsband.h"

#define VB_BLANK 0x001                /* 게임 띠 빈칸 타일 */
#define VB_A0 0x0D0                   /* 첫 자리 0x0D0~0x105 (54) */
#define VB_AN 54
#define VB_B0 0x0B6                   /* 다음 자리 0x0B6~0x0CF (26) */
static uint8_t *b_chr, *b_scr, *b_spr;
static uint8_t vt[VB_MAXT * 16];      /* Java 가 준 타일 */
static uint16_t vm[VB_CELLS];         /* 칸표: 0xFFFF 게임 칸 그대로 · 아니면 (팔레트<<9)|번호 */
static int vn;                        /* 타일 수(0 = 띠 없음) */
static int on;                        /* 지금 화면에 얹혀 있나 */
static int clash;                     /* 우리 타일 자리를 게임이 썼거나 스프라이트가 가리켰다(이번 띠는 포기) */
static uint8_t t_orig[VB_MAXT * 16];  /* 얹기 전 그 자리 타일 그림 */
static uint16_t c_orig[VB_CELLS];     /* 얹기 전 칸 값(게임이 빈칸을 다시 쓰면 새 값으로) */

int vsband_slot(int i) { return i < VB_AN ? VB_A0 + i : VB_B0 + (i - VB_AN); }
void vsband_vram(void *chr, void *scr) { b_chr = (uint8_t *)chr; b_scr = (uint8_t *)scr; }
void vsband_spr(void *spr) { b_spr = (uint8_t *)spr; }
static uint8_t *cell(int i) { return b_scr + ((i / VB_COLS) * 32 + (i % VB_COLS)) * 2; }   /* 판 0(0x000~) */
static uint16_t rd(int i) { const uint8_t *e = cell(i); return (uint16_t)(e[0] | (e[1] << 8)); }
static void wr(int i, uint16_t v) { uint8_t *e = cell(i); e[0] = (uint8_t)v; e[1] = (uint8_t)(v >> 8); }
static uint16_t ours(int i) { return (uint16_t)((vm[i] & 0x1E00) | vsband_slot(vm[i] & 0x1FF)); }
static int is_ours(int i, uint16_t v) { return vm[i] != 0xFFFF && v == ours(i); }
static int tiles_ours(void)           /* 우리 자리 그림이 아직 우리 것인가 */
{
   for (int k = 0; k < vn; k++) if (memcmp(b_chr + vsband_slot(k) * 16, vt + k * 16, 16)) return 0;
   return 1;
}
static int spr_hits(void)             /* 보이는 스프라이트가 우리 자리 타일을 가리키나(스프라이트 램을 모르면 0) */
{
   if (!b_spr) return 0;
   for (int s = 0; s < 64; s++) {
      const uint8_t *e = b_spr + s * 4;
      if (!((e[1] >> 3) & 3)) continue;                         /* 우선순위 0 = 안 보임 */
      int t = e[0] | ((e[1] & 1) << 8);
      if (t >= VB_A0 && t < VB_A0 + (vn < VB_AN ? vn : VB_AN)) return 1;
      if (vn > VB_AN && t >= VB_B0 && t < VB_B0 + (vn - VB_AN)) return 1;
   }
   return 0;
}
static void tiles_put(void) { for (int k = 0; k < vn; k++) memcpy(b_chr + vsband_slot(k) * 16, vt + k * 16, 16); }
static void tiles_back(void) { for (int k = 0; k < vn; k++) memcpy(b_chr + vsband_slot(k) * 16, t_orig + k * 16, 16); }

int vsband_set(const uint8_t *tiles, int n, const uint16_t *map)
{
   if (on) vsband_restore();
   vn = 0; clash = 0;
   if (!tiles || !map || n <= 0 || n > VB_MAXT) return 0;
   for (int i = 0; i < VB_CELLS; i++) if (map[i] != 0xFFFF && (map[i] & 0x1FF) >= n) return 0;
   memcpy(vt, tiles, (size_t)n * 16); memcpy(vm, map, sizeof vm); vn = n;
   return 1;
}
void vsband_clear(void) { if (on) vsband_restore(); vn = 0; clash = 0; }

/* 띠 자리가 게임 빈칸(또는 우리 것)인가 — 다른 화면에 얹지 않게 */
int vsband_layout(void)
{
   if (!b_chr || !b_scr) return 0;
   for (int i = 0; i < VB_CELLS; i++) {
      uint16_t v = rd(i);
      if ((v & 0x1FF) != VB_BLANK && !is_ours(i, v)) return 0;
   }
   return 1;
}

/* 매 프레임(인트로 동안). 처음이면 원래 타일·칸을 보관하고 얹는다. 이미 얹혀 있으면: 게임이 빈칸을 다시 쓴 칸(배너 시작의 0x1E01)만 다시 얹고,
   게임이 다른 그림을 쓴 칸은 그대로 둔다. 우리 자리 타일을 게임이 썼거나 스프라이트가 가리키면 띠를 거두고 포기한다. */
int vsband_put(void)
{
   if (!vn || clash || !b_chr || !b_scr) return 0;
   if (!on) {
      if (!vsband_layout()) return 0;
      if (spr_hits()) { clash = 1; return 0; }                   /* 이미 그 자리를 쓰는 스프라이트가 있다(본 적 없는 캐릭터·연출) — 얹지 않는다 */
      for (int k = 0; k < vn; k++) memcpy(t_orig + k * 16, b_chr + vsband_slot(k) * 16, 16);
      tiles_put();
      for (int i = 0; i < VB_CELLS; i++) if (vm[i] != 0xFFFF) { c_orig[i] = rd(i); wr(i, ours(i)); }
      on = 1;
      return 1;
   }
   int mine = tiles_ours();
   if (!mine || spr_hits()) {                                    /* 게임이 그 자리를 쓴다 — 칸을 되돌리고, 그림이 아직 우리 것이면 원래 그림도 */
      for (int i = 0; i < VB_CELLS; i++) if (vm[i] != 0xFFFF && is_ours(i, rd(i))) wr(i, c_orig[i]);
      if (mine) tiles_back();
      on = 0; clash = 1;
      return 0;
   }
   for (int i = 0; i < VB_CELLS; i++) {
      if (vm[i] == 0xFFFF) continue;
      uint16_t v = rd(i);
      if (is_ours(i, v)) continue;
      if ((v & 0x1FF) == VB_BLANK) { c_orig[i] = v; wr(i, ours(i)); }   /* 게임이 빈칸을 다시 썼다(칠 번호가 바뀌어도) — 새 값을 보관하고 다시 */
   }
   return 1;
}

/* 거두기 — 우리 값인 칸만 보관한 값으로, 우리 자리 타일은 그림이 아직 우리 것일 때만 원래 그림으로 */
void vsband_restore(void)
{
   if (!on) return;
   on = 0;
   if (!b_chr || !b_scr) return;
   for (int i = 0; i < VB_CELLS; i++) if (vm[i] != 0xFFFF && is_ours(i, rd(i))) wr(i, c_orig[i]);
   if (tiles_ours()) tiles_back();
}
void vsband_forget(void) { on = 0; clash = 0; }          /* 화면이 통째로 바뀜(리셋·불러오기) — 되돌리지 않고 잊는다(띠는 남김) */
int vsband_on(void) { return on; }
int vsband_ready(void) { return vn > 0 && !clash; }
int vsband_clash(void) { return clash; }
