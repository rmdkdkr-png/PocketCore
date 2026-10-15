/* 오른쪽 세로 이름 칸의 주인 하나 — league.c 에 있던 vp_layout·vp_put·vp_restore·menu_kind 를 그대로 옮긴 것(동작 같음).
   주인이 둘(허브 H.vp_orig · 컬러 쪽)이면 넘겨받는 쪽이 원래 값을 잃는다: 허브가 상대 선택에서 배경 이름으로 바꿔 둔 칸을
   컬러 쪽이 «이미 우리 타일»로 보고 원래 값을 안 남기고, 되돌릴 때 엉뚱한 값을 쓴다. 그래서 원래 값(vp_orig)은 여기 하나만 둔다.

   칸: 둘째 판 3~11줄 × 17·18칸(위아래 테: 2줄 0x16C · 12줄 0x16E, 검질 고르기는 0x19C · 0x19E). 원래는 왼쪽 칸과 같은 커서 캐릭터
   이름(전용 타일 0x1E8 빈칸 ~ 0x1F9). 글자 그림은 빈 타일 0x130~0x141(모든 롬·고르기 화면에서 안 쓰임, 실측)에, 칸 18개는 그 타일을
   가리키게(칠 번호 비트는 원래대로). 게임이 칸을 다시 쓰면(이름 길이가 다른 캐릭터로 커서를 옮김) 그 값을 원래 값으로 새로 보관하고 다시 바꾼다. */
#include <string.h>
#include "vpanel.h"

#define VP_T0 0x130
static uint8_t *v_chr, *v_scr;
static int vp_on;
static uint16_t vp_orig[18];

void vpanel_vram(void *chr, void *scr) { v_chr = (uint8_t *)chr; v_scr = (uint8_t *)scr; }
static int st(int row, int col) { const uint8_t *e = v_scr + 0x800 + (row * 32 + col) * 2; return (e[0] | (e[1] << 8)) & 0x1FF; }
static uint8_t *ent(int i) { return v_scr + 0x800 + ((3 + i / 2) * 32 + 17 + (i & 1)) * 2; }

int vpanel_layout(void)
{
   if (!v_chr || !v_scr) return 0;
   int t2 = st(2, 17), t12 = st(12, 17);
   if (!((t2 == 0x16C && t12 == 0x16E) || (t2 == 0x19C && t12 == 0x19E))) return 0;
   for (int i = 0; i < 18; i++) { int t = st(3 + i / 2, 17 + (i & 1)); if (!((t >= 0x1E8 && t <= 0x1F9) || t == VP_T0 + i)) return 0; }
   return 1;
}
void vpanel_put(const uint8_t *tiles)
{
   if (!tiles || !vpanel_layout()) return;
   memcpy(v_chr + VP_T0 * 16, tiles, 18 * 16);
   for (int i = 0; i < 18; i++) {
      uint8_t *e = ent(i); uint16_t v = (uint16_t)(e[0] | (e[1] << 8));
      if ((v & 0x1FF) == VP_T0 + i) continue;                     /* 이미 우리 타일 — 원래 값은 처음 바꿀 때 남긴 것 그대로 */
      vp_orig[i] = v; v = (uint16_t)((v & 0x3E00) | (VP_T0 + i));  /* 칠 번호만 두고 뒤집기는 끔 */
      e[0] = (uint8_t)v; e[1] = (uint8_t)(v >> 8);
   }
   vp_on = 1;
}
void vpanel_restore(void)
{
   if (!vp_on) return;
   vp_on = 0;
   if (!v_scr) return;
   for (int i = 0; i < 18; i++) {
      uint8_t *e = ent(i);
      if (((e[0] | (e[1] << 8)) & 0x1FF) == VP_T0 + i) { e[0] = (uint8_t)vp_orig[i]; e[1] = (uint8_t)(vp_orig[i] >> 8); }
   }
}
void vpanel_forget(void) { vp_on = 0; }
int vpanel_on(void) { return vp_on; }

/* 타이틀 메뉴가 떠 있나 — 메뉴 글자 타일이 제자리에 있는지로 본다(램 표시는 리셋 뒤에 안 서는 게 있고, 이어하기 뒤 SCR 0 이 8프레임 서는 일도 있어
   SCR 만으론 안 된다 — 실측). 둘째 판 12~16줄. 1 = 한글판(7칸부터 «1 인 대전» 0x1B1… · «2 인 대전» 0x1BB~0x1BF 전용 타일) · 2 = 순정판(5칸부터 «1P PLAY» 0x1B1…) */
int vpanel_menu_kind(void)
{
   if (!v_scr) return 0;
   if (st(12, 7) == 0x1B1 && st(13, 7) == 0x1B6 && st(16, 7) == 0x1C6) return 1;
   if (st(12, 5) == 0x1B1 && st(13, 5) == 0x1B7 && st(15, 5) == 0x1C5) return 2;
   return 0;
}
