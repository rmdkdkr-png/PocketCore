/* 오른쪽 세로 이름 칸의 주인 하나 — league.c 에 있던 vp_layout·vp_put·vp_restore·menu_kind 를 그대로 옮긴 것(동작 같음).
   주인이 둘(허브 H.vp_orig · 컬러 쪽)이면 넘겨받는 쪽이 원래 값을 잃는다: 허브가 상대 선택에서 배경 이름으로 바꿔 둔 칸을
   컬러 쪽이 «이미 우리 타일»로 보고 원래 값을 안 남기고, 되돌릴 때 엉뚱한 값을 쓴다. 그래서 원래 값(vp_orig)은 여기 하나만 둔다.

   칸: 둘째 판 3~11줄 × 17·18칸(위아래 테: 2줄 0x16C · 12줄 0x16E, 검질 고르기는 0x19C · 0x19E). 원래는 왼쪽 칸과 같은 커서 캐릭터
   이름(전용 타일 0x1E8 빈칸 ~ 0x1F9). 글자 그림은 빈 타일 0x130~0x141(모든 롬·고르기 화면에서 안 쓰임, 실측)에, 칸 18개는 그 타일을
   가리키게(칠 번호 비트는 원래대로). 게임이 칸을 다시 쓰면(이름 길이가 다른 캐릭터로 커서를 옮김) 그 값을 원래 값으로 새로 보관하고 다시 바꾼다.
   165: 되돌릴 때 타일 그림도(처음 빌릴 때 보관한 것 — 아직 우리 그림일 때만) — 저장된 상태의 문자 램이 컬러를 끈 판과 같게.

   165 베기 색 아이콘: 이름 칸 바로 위 문양 칸(둘째 판 0~1줄 × 17·18칸, 원래 태극 문양 — 캐릭터 고르기 0x168~0x16B 칠 15 · 검질 고르기 0x198~0x19B,
   롬 6판 같음)을 우리 16×16 그림으로. 타일 0x142~0x145(이름 타일 바로 뒤 — 6롬 × 15칸 × 두 고르기 화면에서 어느 판·스프라이트도 안 가리키고 늘 빔, 실측)
   · 둘째 판 팔레트 하나(고르기 화면 둘째 판이 쓰는 칠 = 1·8~12·15 뿐 — 7 은 FFF/666/000 그대로, 이야기 장면에서야 게임이 씀. 둘째 판 32×32 칸 어디도 안 쓰는
   첫 번째를 7·6·5·4·3·2·13 순으로) · 칸 4개 (원래 & 0x2000) | (칠 << 9) | (0x142 + i). 첫째 판 같은 자리는 타일 0(투명) — 색 0 은 바탕(검정).
   게임이 칸·팔레트를 다시 쓰면 그 값을 새 원래 값으로 보관하고 다시 우리 것(이름 칸·practice.c 와 같은 규칙). 고른 팔레트를 게임 칸이 쓰기 시작하면
   아이콘을 거두고 다음에 다시 고른다. 되돌릴 땐 칸·팔레트·타일 그림 모두 «아직 우리 것일 때만» 원래 값(게임이 새로 쓴 것을 옛 값으로 덮지 않게). */
#include <string.h>
#include "vpanel.h"

#define VP_T0 0x130
#define IC_T0 0x142
static uint8_t *v_chr, *v_scr, *v_pal;
static int vp_on;
static uint16_t vp_orig[18];
static int vp_tsaved;                    /* 이름 타일 원래 그림을 보관했나(처음 빌릴 때) */
static uint8_t vp_tsave[18 * 16], vp_tlast[18 * 16];
static struct {
   int on, pal, tsaved, psaved;
   uint16_t orig[4];                     /* 문양 칸 원래 값 */
   uint16_t porig[3], pours[3];          /* 빌린 팔레트 색 1~3 원래 값 · 마지막에 쓴 우리 색 */
   uint8_t tsave[64], tlast[64];         /* 타일 0x142~0x145 원래 그림 · 마지막에 쓴 우리 그림 */
} IC = { .pal = -1 };
static const int IC_ORDER[7] = { 7, 6, 5, 4, 3, 2, 13 };

void vpanel_vram(void *chr, void *scr) { v_chr = (uint8_t *)chr; v_scr = (uint8_t *)scr; }
void vpanel_pal(void *pal) { v_pal = (uint8_t *)pal; }
static int st(int row, int col) { const uint8_t *e = v_scr + 0x800 + (row * 32 + col) * 2; return (e[0] | (e[1] << 8)) & 0x1FF; }
static uint8_t *ent(int i) { return v_scr + 0x800 + ((3 + i / 2) * 32 + 17 + (i & 1)) * 2; }
static uint8_t *ient(int i) { return v_scr + 0x800 + ((i / 2) * 32 + 17 + (i & 1)) * 2; }
static uint16_t rd16(const uint8_t *e) { return (uint16_t)(e[0] | (e[1] << 8)); }
static void wr16(uint8_t *e, uint16_t v) { e[0] = (uint8_t)v; e[1] = (uint8_t)(v >> 8); }

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
   uint8_t *t = v_chr + VP_T0 * 16;
   if (!vp_tsaved) { memcpy(vp_tsave, t, sizeof vp_tsave); vp_tsaved = 1; }
   memcpy(t, tiles, 18 * 16); memcpy(vp_tlast, tiles, sizeof vp_tlast);
   for (int i = 0; i < 18; i++) {
      uint8_t *e = ent(i); uint16_t v = rd16(e);
      if ((v & 0x1FF) == VP_T0 + i) continue;                     /* 이미 우리 타일 — 원래 값은 처음 바꿀 때 남긴 것 그대로 */
      vp_orig[i] = v; v = (uint16_t)((v & 0x3E00) | (VP_T0 + i));  /* 칠 번호만 두고 뒤집기는 끔 */
      wr16(e, v);
   }
   vp_on = 1;
}

/* ── 아이콘 ── */
int vpanel_icon_layout(void)
{
   if (!vpanel_layout()) return 0;
   int base = st(2, 17) == 0x16C ? 0x168 : 0x198;
   for (int i = 0; i < 4; i++) { int t = st(i / 2, 17 + (i & 1)); if (t != base + i && t != IC_T0 + i) return 0; }
   return 1;
}
static void pal_get(int p, uint16_t c[3]) { const uint8_t *e = v_pal + 0x100 + p * 8 + 2; for (int j = 0; j < 3; j++) c[j] = (uint16_t)(rd16(e + 2 * j) & 0xFFF); }
static void pal_set(int p, const uint16_t c[3]) { uint8_t *e = v_pal + 0x100 + p * 8 + 2; for (int j = 0; j < 3; j++) wr16(e + 2 * j, c[j]); }
static int tri_eq(const uint16_t a[3], const uint16_t b[3]) { return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]; }
/* 177 §10-8: 허브가 머리말 칸(0~1줄 5~14칸)도 아이콘이 빌린 팔레트로 칠하는 중(상대 화면 빨강 — 같은 팔레트) */
static int hdr_mark;
void vpanel_hdr_mark(int on) { hdr_mark = on ? 1 : 0; }
/* 둘째 판 칸 중 팔레트 p 를 쓰는 것이 있나 — 우리 아이콘 칸 넷은 뺀다(허브가 머리말을 빌린 팔레트로 칠하는 동안엔 그 칸도) */
static int pal_used(int p)
{
   for (int i = 0; i < 1024; i++) {
      int r = i >> 5, c = i & 31;
      if (r < 2 && (c == 17 || c == 18)) continue;
      if (hdr_mark && r < 2 && c >= 5 && c <= 14) continue;
      if (((rd16(v_scr + 0x800 + i * 2) >> 9) & 15) == p) return 1;
   }
   return 0;
}
static void icon_restore(void)
{
   if (!v_scr) { memset(&IC, 0, sizeof IC); IC.pal = -1; return; }
   if (IC.on)
      for (int i = 0; i < 4; i++) { uint8_t *e = ient(i); if ((rd16(e) & 0x1FF) == IC_T0 + i) wr16(e, IC.orig[i]); }
   if (IC.pal >= 0 && IC.psaved && v_pal) { uint16_t c[3]; pal_get(IC.pal, c); if (tri_eq(c, IC.pours)) pal_set(IC.pal, IC.porig); }
   if (IC.tsaved && v_chr && !memcmp(v_chr + IC_T0 * 16, IC.tlast, 64)) memcpy(v_chr + IC_T0 * 16, IC.tsave, 64);
   IC.on = 0; IC.pal = -1; IC.psaved = 0; IC.tsaved = 0;
}
int vpanel_icon_put(const uint8_t *tiles4, const uint16_t c[3])
{
   if (!tiles4 || !c || !v_pal || !vpanel_icon_layout()) return 0;
   if (IC.pal >= 0 && pal_used(IC.pal)) { icon_restore(); return 0; }   /* 게임이 그 팔레트를 쓰기 시작했다 — 거두고 다음에 다시 고른다 */
   if (IC.pal < 0) {
      for (int k = 0; k < 7 && IC.pal < 0; k++) if (!pal_used(IC_ORDER[k])) IC.pal = IC_ORDER[k];
      if (IC.pal < 0) return 0;
      IC.psaved = 0;
   }
   /* 팔레트 — 처음이거나 게임이 다시 썼으면(지금 색이 마지막 우리 색이 아님) 그것을 원래 값으로 */
   uint16_t cur[3], want[3] = { (uint16_t)(c[0] & 0xFFF), (uint16_t)(c[1] & 0xFFF), (uint16_t)(c[2] & 0xFFF) };
   pal_get(IC.pal, cur);
   if (!IC.psaved || !tri_eq(cur, IC.pours)) { memcpy(IC.porig, cur, 6); IC.psaved = 1; }
   if (!tri_eq(cur, want)) pal_set(IC.pal, want);
   memcpy(IC.pours, want, 6);
   /* 타일 그림 */
   uint8_t *t = v_chr + IC_T0 * 16;
   if (!IC.tsaved || memcmp(t, IC.tlast, 64)) { memcpy(IC.tsave, t, 64); IC.tsaved = 1; }   /* 처음 · 게임이 다시 씀 = 원래 그림 */
   if (memcmp(t, tiles4, 64)) memcpy(t, tiles4, 64);
   memcpy(IC.tlast, tiles4, 64);
   /* 칸 넷 — 원래 칸의 0x2000(CP.C) 비트만 두고 뒤집기는 끔 */
   for (int i = 0; i < 4; i++) {
      uint8_t *e = ient(i); uint16_t v = rd16(e);
      if ((v & 0x1FF) != IC_T0 + i) IC.orig[i] = v;
      uint16_t nv = (uint16_t)((IC.orig[i] & 0x2000) | (IC.pal << 9) | (IC_T0 + i));
      if (v != nv) wr16(e, nv);
   }
   IC.on = 1;
   return 1;
}
int vpanel_icon_pal(void) { return IC.on ? IC.pal : -1; }

void vpanel_restore(void)
{
   icon_restore();
   if (!vp_on) return;
   vp_on = 0;
   if (!v_scr) return;
   for (int i = 0; i < 18; i++) {
      uint8_t *e = ent(i);
      if ((rd16(e) & 0x1FF) == VP_T0 + i) wr16(e, vp_orig[i]);
   }
   if (vp_tsaved && v_chr && !memcmp(v_chr + VP_T0 * 16, vp_tlast, sizeof vp_tlast)) memcpy(v_chr + VP_T0 * 16, vp_tsave, sizeof vp_tsave);
   vp_tsaved = 0;
}
void vpanel_forget(void) { vp_on = 0; vp_tsaved = 0; memset(&IC, 0, sizeof IC); IC.pal = -1; hdr_mark = 0; }
int vpanel_on(void) { return vp_on || IC.on; }

/* 타이틀 메뉴가 떠 있나 — 메뉴 글자 타일이 제자리에 있는지로 본다(램 표시는 리셋 뒤에 안 서는 게 있고, 이어하기 뒤 SCR 0 이 8프레임 서는 일도 있어
   SCR 만으론 안 된다 — 실측). 둘째 판 12~16줄. 1 = 한글판(7칸부터 «1 인 대전» 0x1B1… · «2 인 대전» 0x1BB~0x1BF 전용 타일) · 2 = 순정판(5칸부터 «1P PLAY» 0x1B1…) */
int vpanel_menu_kind(void)
{
   if (!v_scr) return 0;
   if (st(12, 7) == 0x1B1 && st(13, 7) == 0x1B6 && st(16, 7) == 0x1C6) return 1;
   if (st(12, 5) == 0x1B1 && st(13, 5) == 0x1B7 && st(15, 5) == 0x1C5) return 2;
   return 0;
}
