/* 연습 대전 1단계 — 사무쇼2 1인 대전 첫 경기를 «끝나지 않는 연습 경기»로.

   설계: CustumApKS handoff/ss2_practice/0단계_조사설계.md 3-나 F2~F9 · 4-라~4-바. 주소는 그 문서 2장에서 확정한 것만 쓴다
   (순정 ss2.ngc·ss2_h1.ngc 와 한글패치 v1.01·동봉판에서 같게 동작함을 실측). 모르는 롬에서는 Java 가 연습 허브를 아예 켜지 않는다.
   롬·세이브는 안 건드린다. 진짜 프레임마다(retro_run 뒤, 코어 잠금 안, league_tick_hub 다음) practice_tick 이 작업 램·화면 램을 고친다.

     켜짐     league.c 허브가 연습 종류로 경기(H_MATCH)에 들어갔을 때만. 대진·배경·컬러·CPU 카드 없음은 허브의 무장이 쓴다.
     라운드   «라운드 진행»(0x40A7==241 · 0x411A==1 · 0x5824==0) 이고 일시정지(0x61D4)가 아닐 때만 경기 램을 쓴다.
     F2       타이머 0x582F = ∞(0xFFFF) · 다음 라운드 0x5812 = 0x80 · 배율 0x57E2/0x57E4 = 0x100. 체력 < 64 면 64(바닥 — 가장 큰 한 방 52),
              맞은 쪽이 40프레임 동안 움직일 수 있었으면(0x59CE==0) 체력·표시 체력 = 128.
     F3       더미 서기 = 0x5BCB bit0 끔(사람 갈래 · 패드 0) · CPU = bit0 켬(원래 AI). 싸우지 않는 6조합은 CPU 를 못 고른다.
     F4       가드: 켤 프레임에 땅이면(자세 0x60, 공중 0x80 아님) 0x5BC9 |= 0x18 — 자세 비트는 그대로. 첫 타 뒤 · 랜덤은 설계 그대로.
     F5       리셋: 요청 → «중립»(둘 다 0x59CE==0 · Y==128 · 히트스톱 0 · 물체 수가 라운드 시작 값 이하)이 되는 첫 프레임에 한 번에 씀.
              비오의를 쓰면 게임이 그 판 동안 분노 게이지를 지우고 A+B 분노 오르기를 막는다(0x5A55 bit7) — 리셋이 풀고,
              리셋 없이도 쓴 사람이 다시 움직일 수 있게 되면 되살린다(F6 다음).
     F6       분노 MAX 유지: 진짜 MAX 비트 0x59CA bit2 + 타이머 0x5A48 = 0x3000 + 분노·표시 분노 0x30. 쓰여서 꺼지면 움직일 수 있을 때 다시.
     F7       연습 메뉴: Option 탭 → 게임 일시정지(0x61D4=1) 위, 첫째 판(HUD 판) 줄 4~16 × 칸 3~16 에 창(게임이 틱마다 번갈아 보이는
              줄 19~31 에도 같은 창 — 아래 WBAND). 타일·칸·팔레트는 빌렸다가 닫을 때
              바이트까지 되돌리고, 그다음 프레임에 Option 을 대신 눌러 푼다. 일시정지 중엔 게임이 문자 램·첫째 판·스프라이트 램을 안 건드린다(실측).
     F8       대미지: 맞은 쪽 콤보 수(0x5A97)가 0→1 이면 새 콤보, 체력이 준 만큼 더한다(바닥을 쓰기 전에). HUD 셋째 줄 가운데에 게임 숫자 타일.
     F9       더미가 CPU 가 아니면 HUD «COM»(0x049·0x04A) 그림을 «2P» 로.
     Option   경기 중 사용자의 Option 은 게임에 안 준다. 누르는 동안 방향 = 리셋(↑ 가운데 · ← 왼쪽 벽 · → 오른쪽 벽 · ↓ 메뉴의 리셋 위치),
              그동안 방향·A·B·SP 버튼도 게임에 안 준다. 다른 버튼 없이 떼면(탭) 그때 일시정지 + 메뉴. */

#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <stddef.h>
#include "practice.h"
#include "league.h"
#include "cheat.h"
#define PG_WANT_WINDOW
#include "practice_glyphs.h"
#include "uisfx.h"                                  /* 182: 효과음 종류 번호만(소리는 native.c 가 건 함수로 — 시험 빌드는 uisfx.c 없이도 된다) */

#define RAM_BASE 0x4000
#define A(x) ((x) - RAM_BASE)

#define R_MODE   A(0x40A7)   /* 240 메뉴·연출 / 241 전투 */
#define R_FIGHT  A(0x411A)   /* 1 = 라운드 진행 */
#define R_OBJN   A(0x41C2)   /* 활성 스프라이트 물체 수(둘 + 그림자 둘, 나코루루 매 +1, 장풍·효과 +1) */
#define R_RES    A(0x5824)   /* 라운드 결과(0 = 진행 중) — 쓰지 않는다 */
#define R_TIMER  A(0x582F)   /* 라운드 타이머 u16, 0xFFFF = ∞ */
#define R_NEXTT  A(0x5812)   /* 다음 라운드 시간, 0x80 = ∞ */
#define R_CPUATK A(0x57E2)   /* CPU 공격 배율 · 받는 피해 배율(u16, 0x100 = 1.0) */
#define R_CPUDEF A(0x57E4)
#define R_HSTOP  A(0x5DC7)   /* 히트스톱(두 사람 공통) */
#define R_PAUSE  A(0x61D4)   /* 일시정지 중 1 */
#define R_PROG   A(0x57C6)   /* 메뉴 프로그램 — 06 1인 대전 · 0C 무한 대전(cheat.c 와 같은 주소) — 176 보기만 본다 */
#define R_FXX    A(0x41F8)   /* 첫째 판 효과 층(줄 19~31)을 보여 주는 틱의 가로 스크롤 — 게임이 효과 자리로 옮긴다(아수라 MAX 비오의 뒤 0xC9 로 남음, 실측) */
#define R_BAX    A(0x41F7)   /* 첫째 판 줄 4~16 을 보여 주는 틱의 가로 스크롤 — 보통 0 이지만 효과가 쓰고 남긴다(샬럿 CPU 의 ACT 0x1A0 · 효과 물체 0x1CC 뒤 0x1F, 실측).
                                게임은 틱마다 지금 띠의 값(0x41F7 / 0x41F8)을 0x41F9 로 옮겨 래스터로 줄 32~136 의 가로 스크롤에 쓴다 */
#define R_CMD1RS A(0x5DCD)   /* P1 커맨드 이력 리셋(1 = 다음 틱에 비움) */
#define R_CMD2RS A(0x5E55)
#define PB(p)    (A(0x5980) + (p) * 0x200)   /* 플레이어 블록(P2 = P1 + 0x200) — 아래 오프셋은 이 기준 */
#define O_STANCE 0x49        /* 0x59C9 자세 0x20 서기 · 0x40 앉기 · 0x80 공중 | 0x10 상단 가드 · 0x08 하단 가드 */
#define O_FLAGS  0x4A        /* 0x59CA bit2 = 진짜 분노 MAX · bit7 = 공격 시작 */
#define O_CPU    0x4B        /* 0x59CB bit0 = CPU 가 조종 */
#define O_CTRL   0x4E        /* 0x59CE 0 = 지금 움직일 수 있다 · 0x12 가드 경직 */
#define O_FACE   0x52        /* 0x59D2·0x59D3 좌우(1 = 왼쪽을 봄) */
#define O_XF     0x54        /* X 16.16 — 소수 0x59D4 · 정수 0x59D6 */
#define O_X      0x56
#define O_YF     0x58        /* Y 16.16 — 정수 0x59DA, 땅 = 128 */
#define O_Y      0x5A
#define O_VY     0x5C        /* 수직 속도 s32 */
#define O_LXF    0x64        /* 지난 틱 X 0x59E4/0x59E6 */
#define O_LX     0x66
#define O_HP     0xC6        /* 0x5A46 체력 0~128 */
#define O_RAGE   0xC7        /* 0x5A47 분노 0~0x30 */
#define O_MAXT   0xC8        /* 0x5A48 MAX 유지 타이머 u16 */
#define O_DHP    0xCA        /* 0x5A4A 표시 체력 */
#define O_DRAGE  0xCB        /* 0x5A4B 표시 분노 */
#define O_SECRET 0xD5        /* 0x5A55 bit7 = 이번 판 비오의를 씀 — 켜져 있으면 A+B 로 분노가 안 오른다(판이 끝날 때까지) */
#define O_COMBO  0x117       /* 0x5A97 그 사람이 맞은 콤보 수 */
#define O_CHAR   0x1D1       /* 0x5B51 (캐릭터<<4)|(유파<<3) */
#define OBJ(k)   (A(0x4E30) + (k) * 0x40)    /* 표시 물체 — +8 X · +0xA Y · +0x24 방향(1 = 왼쪽) */
static const int CAM[3] = { A(0x5FA0), A(0x576C), A(0x4880) };   /* 카메라 목표 · 배경 · 스프라이트(8.8, 0~159) */

/* 입력 비트(RETRO_DEVICE_ID_JOYPAD_*) — NGPC A = B(0) · NGPC B = A(8) · Option = START(3) */
#define IN_A     (1 << 0)
#define IN_Y     (1 << 1)
#define IN_SEL   (1 << 2)
#define IN_OPT   (1 << 3)
#define IN_U     (1 << 4)
#define IN_D     (1 << 5)
#define IN_L     (1 << 6)
#define IN_R     (1 << 7)
#define IN_B     (1 << 8)
#define IN_X     (1 << 9)
#define IN_L1    (1 << 10)
#define IN_R1    (1 << 11)
#define P_DIRS   (IN_U | IN_D | IN_L | IN_R)
#define P_FOLD   (IN_Y | IN_X | IN_L1 | IN_R1)   /* 코어가 A+B·원버튼으로 접는 SP 버튼 */
#define P_GAME   (IN_A | IN_B | P_DIRS | P_FOLD | IN_SEL)
#define P_ALL    0x0FFF

volatile int practice_in_block = 0, practice_in_or = 0, practice_opt_mask = 0;
/* 182 연습 메뉴 효과음(유저 고름 «연습 메뉴·나머지 메뉴 효과음») — 덧판 메뉴와 같은 사무쇼2 메뉴 소리: ↑↓ 커서 · A·←→ 값 바꿈 = 결정(게임 GAME OPTION 처럼) ·
   «지금 리셋»·«상대 고르기» = 결정 · «타이틀로»·B·Option 닫기 = 뒤로. 소리 함수는 native.c 가 건다(practice_sfx_fn — 없으면 조용).
   practice_menu_up: 메뉴가 열려 있는 동안 1 — 오디오 콜백이 결정의 메아리를 끝까지 낸다(잠금 없이 읽음) */
volatile int practice_menu_up = 0;
static int (*p_sfx)(int) = NULL;
void practice_sfx_fn(int (*fn)(int)) { p_sfx = fn; }
static void psfx(int kind) { if (p_sfx) p_sfx(kind); }
static pthread_mutex_t P_mu = PTHREAD_MUTEX_INITIALIZER;
static uint8_t *v_chr, *v_scr, *v_pal, *v_spr;

void practice_vram(void *chr, void *scr, void *pal, void *spr)
{ v_chr = (uint8_t *)chr; v_scr = (uint8_t *)scr; v_pal = (uint8_t *)pal; v_spr = (uint8_t *)spr; }

/* 설정 — 세션 안에서 다음 경기에도 그대로 */
typedef struct { int dummy_cpu, guard, rage[2], rpos, swap, num;
                 int last_ok, last_x[2];
                 int fd; } Settings;   /* fd = 프레임 미터(앱 위 덮개) 켬 — 끝에 붙여 옛 .prac(fd 없음)도 읽힌다 */   /* last_* = «고른 자리»로 고른 순간 두 사람 X(무대 좌표) — 다음 경기·불러오기에도 남게 설정에(리뷰) */
static Settings PS;

enum { M_NONE = 0, M_OPENING, M_OPEN, M_CLOSING, M_EXIT };
enum { RP_CENTER = 0, RP_LEFT, RP_RIGHT, RP_LAST };

typedef struct {
   int active, dummy, dsty, cpu_ok;
   unsigned f;                       /* 연습 프레임(경기 중 틱 수) */
   int prev_in, objn0;
   int opt_down, opt_combo, holds;   /* Option 누르는 중 · 그동안 다른 버튼을 눌렀나 · 뗄 때까지 게임에 안 줄 버튼 */
   int menu, row, or_left, tries, rep_bit, pause_n;
   int dir_lock;                     /* 메뉴를 열 때 쥐고 있던 방향 — 뗄 때까지 메뉴에서 안 친다(되풀이도) */
   unsigned m_f, rep_f;
   int exit_req, opens;
   int rq, rq_pos, resets, dropped;  /* 리셋 요청 */
   unsigned rq_f, last_reset_f;
   int menu_x[2];
   int calm[2], prev_hp[2], prev_combo[2], rec_open[2], dmg_cur[2], shown_side, shown;
   int still[2], prev_x[2];          /* X 가 안 바뀐 프레임 수(밀려 미끄러지는 중엔 리셋하지 않는다) */
   int settle, settle_x[2];          /* 리셋 뒤 몇 프레임: 손대지 않은 사람이 제자리에서 밀리면 리셋 자리로 되돌림 */
   unsigned dmg_f[2];
   int g_on, ag_wait, ag_on, ag_idle, ag_prev, prev_atk, rnd_on;
   uint32_t rng;
   int max_keep[2], cpu_set;
   int com_state, dig_state;
   int set_hp2;                      /* 시험용: 다음 프레임에 더미 체력을 이 값으로(대미지로 세지 않고, 회복 셈도 새로) — 0 = 없음 */
   uint8_t com_orig[32], com_ours[32];
   uint16_t dig_orig[3], dig_ours[3];
} State;
static State P;
static void fd_reset(int keep_mem);

/* 연습 메뉴 창이 빌린 것 — 칸(13줄 × 14칸 × 두 띠)·타일·팔레트 13·14 의 원래 값.
   두 띠: 게임은 싸움 내내(일시정지 중에도) 틱마다 첫째 판 가운데 화면줄(32~135)을 판의 줄 4~16 과 줄 19~31 사이에서 번갈아
   보여 준다(0x41F5 = 0 / 0x78 — 래스터로 세로 스크롤 120, 실측). 줄 19~31 은 게임이 큰 효과를 그려 두는 자리(카즈키 불꽃 등).
   한 띠에만 그리면 창이 15Hz 로 깜빡이므로 두 띠에 같은 창을 그린다. */
#define WR 13
#define WC 14
#define POOL_MAX 80
static const int WBAND[2] = { 4, 19 };
static const int WBX[2] = { R_BAX, R_FXX };   /* 띠마다 가로 스크롤 램 */
static struct {
   int open;
   uint16_t cell_orig[2][WR][WC], cell_ours[2][WR][WC];
   int npool;
   uint16_t pool[POOL_MAX];
   uint8_t pool_orig[POOL_MAX][16], pool_ours[POOL_MAX][16];
   uint8_t pool_dead[POOL_MAX];
   int ncand, cand_i, skipped;
   uint16_t cand[512];
   int pal[2];                       /* 빌린 첫째 판 팔레트 두 개(보통 13·14 — 열 때 첫째 판 어느 칸도 안 쓰는 것으로) */
   uint8_t pal_orig[2][8], pal_ours[2][8];
   int fx_orig[2];                   /* 두 띠의 가로 스크롤(0x41F7 · 0x41F8) 원래 값 — 창이 열린 동안 0 으로 둔다(−1 = 안 바꿈) */
} W;
static uint8_t *g_ram;                /* 코어 작업 램(practice_tick 이 받은 것 — 상태 저장 직전 되돌림에 쓴다) */

static int rd16(const uint8_t *ram, int o) { return ram[o] | (ram[o + 1] << 8); }
static void wr16(uint8_t *ram, int o, int v) { ram[o] = (uint8_t)v; ram[o + 1] = (uint8_t)(v >> 8); }
static int live(const uint8_t *ram) { return ram[R_MODE] == 241 && ram[R_FIGHT] == 1 && ram[R_RES] == 0; }
static uint16_t ent_rd(int row, int col) { const uint8_t *e = v_scr + (row * 32 + col) * 2; return (uint16_t)(e[0] | (e[1] << 8)); }
static void ent_wr(int row, int col, uint16_t v) { uint8_t *e = v_scr + (row * 32 + col) * 2; e[0] = (uint8_t)v; e[1] = (uint8_t)(v >> 8); }
static uint32_t rnd(void) { uint32_t x = P.rng; x ^= x << 13; x ^= x >> 17; x ^= x << 5; return P.rng = x; }

/* CPU 로 두면 싸우지 않는 캐릭터·검질(0단계 «가만히 있는 CPU» — League.BAD_OPP 와 같음) */
static int cpu_ok(int ch, int sty)
{
   static const int BAD[6][2] = { { 1, 0 }, { 4, 1 }, { 5, 1 }, { 6, 1 }, { 10, 1 }, { 12, 1 } };
   for (int i = 0; i < 6; i++) if (BAD[i][0] == ch && BAD[i][1] == sty) return 0;
   return 1;
}

/* ── 리셋(F5) ───────────────────────────────────────────────────────── */
/* relax 0: 설계의 중립 + 두 사람 다 4프레임(2틱) 동안 X 그대로(가드·맞고 밀려 미끄러지는 중엔 0x59CE 가 0 이어도 X 가 움직인다 — 그때 쓰면
   새 자리에서 몇 px 더 미끄러진다, 실측) · 1: 더미의 X 는 안 봄(CPU 더미가 계속 걷는 경우 — P1 은 늘 멈춰 있어야) · 2: 남은 장풍·효과 물체 수도 안 봄 */
static int neutral(const uint8_t *ram, int relax)
{
   return ram[PB(0) + O_CTRL] == 0 && ram[PB(1) + O_CTRL] == 0 && rd16(ram, PB(0) + O_Y) == 128 && rd16(ram, PB(1) + O_Y) == 128
       && ram[R_HSTOP] == 0 && P.still[0] >= 4 && (relax >= 1 || P.still[1] >= 4) && (relax >= 2 || ram[R_OBJN] <= P.objn0);
}
static void set_max(uint8_t *ram, int p)
{
   int b = PB(p);
   ram[b + O_RAGE] = 0x30; ram[b + O_DRAGE] = 0x30; wr16(ram, b + O_MAXT, 0x3000); ram[b + O_FLAGS] |= 0x04;
}
static void clear_max(uint8_t *ram, int p)
{
   int b = PB(p);
   ram[b + O_FLAGS] &= (uint8_t)~0x04; wr16(ram, b + O_MAXT, 0); ram[b + O_RAGE] = 0; ram[b + O_DRAGE] = 0;
}
static void set_x(uint8_t *ram, int p, int x)
{
   int b = PB(p);
   wr16(ram, b + O_XF, 0); wr16(ram, b + O_X, x); wr16(ram, b + O_LXF, 0); wr16(ram, b + O_LX, x); wr16(ram, OBJ(p) + 8, x);
}
static void set_player(uint8_t *ram, int p, int x, int face_left)
{
   int b = PB(p), o = OBJ(p);
   wr16(ram, b + O_XF, 0); wr16(ram, b + O_X, x); wr16(ram, b + O_LXF, 0); wr16(ram, b + O_LX, x);
   wr16(ram, b + O_YF, 0); wr16(ram, b + O_Y, 128);
   for (int i = 0; i < 4; i++) ram[b + O_VY + i] = 0;
   wr16(ram, o + 8, x); wr16(ram, o + 0xA, 128);
   ram[b + O_FACE] = (uint8_t)face_left; ram[b + O_FACE + 1] = (uint8_t)face_left; ram[o + 0x24] = (uint8_t)face_left;
}
static void target_pos(int pos, int *x1, int *x2)
{
   switch (pos) {
   case RP_LEFT:  *x1 = 24;  *x2 = 104; break;    /* 왼쪽 벽(카메라 0) — 간격 80 은 라운드 시작과 같다 */
   case RP_RIGHT: *x1 = 216; *x2 = 296; break;    /* 오른쪽 벽(카메라 159) */
   case RP_LAST:
      if (PS.last_ok) { *x1 = PS.last_x[0]; *x2 = PS.last_x[1]; break; }
      /* fall through */
   default:       *x1 = 120; *x2 = 200; break;    /* 가운데 = 라운드 시작(카메라 80) */
   }
   if (PS.swap) { int t = *x1; *x1 = *x2; *x2 = t; }
}
static void do_reset(uint8_t *ram)
{
   int x1, x2;
   target_pos(P.rq_pos, &x1, &x2);
   x1 = x1 < 16 ? 16 : x1 > 303 ? 303 : x1;
   x2 = x2 < 16 ? 16 : x2 > 303 ? 303 : x2;
   if (x1 > x2 - 32 && x1 < x2 + 32) {            /* 너무 붙어 있던 «고른 자리» — 가운데를 두고 32 씩 벌린다 */
      int m = (x1 + x2) / 2; if (m < 32) m = 32; if (m > 287) m = 287;
      if (x1 <= x2) { x1 = m - 16; x2 = m + 16; } else { x1 = m + 16; x2 = m - 16; }
   }
   set_player(ram, 0, x1, x1 > x2); set_player(ram, 1, x2, x2 > x1);
   int cam = (x1 + x2) / 2 - 80;
   cam = cam < 0 ? 0 : cam > 159 ? 159 : cam;
   for (int i = 0; i < 3; i++) wr16(ram, CAM[i], cam << 8);
   for (int p = 0; p < 2; p++) {
      int b = PB(p);
      ram[b + O_HP] = 128; ram[b + O_DHP] = 128;
      if (PS.rage[p]) set_max(ram, p); else clear_max(ram, p);
      ram[b + O_SECRET] &= (uint8_t)~0x80;      /* 비오의를 쓴 뒤에도 리셋하면 A+B 로 다시 분노를 모을 수 있게(연습 경기는 판이 안 끝난다) */
      P.prev_hp[p] = 128; P.calm[p] = 0;
   }
   ram[R_CMD1RS] = 1; ram[R_CMD2RS] = 1;
   /* 나코루루의 매(물체 4번 칸, 나코루루 X ∓ 15 에서 따라다님) — 나코루루가 P1 이면 4번, P1 이 아니고 더미면 4번, 둘 다면 더미 매는 5번 */
   for (int p = 0; p < 2; p++) {
      if ((ram[PB(p) + O_CHAR] >> 4) != 4) continue;
      int slot = (p == 1 && (ram[PB(0) + O_CHAR] >> 4) == 4) ? 5 : 4;
      int x = p ? x2 : x1, fl = p ? (x2 > x1) : (x1 > x2);
      if (rd16(ram, OBJ(slot) + 0xE) == 0) continue;            /* 빈 칸 */
      wr16(ram, OBJ(slot) + 8, fl ? x + 15 : x - 15);
   }
   P.ag_on = 0; P.ag_wait = 0; P.rnd_on = 0;
   P.prev_x[0] = x1; P.prev_x[1] = x2;
   P.settle = 6; P.settle_x[0] = x1; P.settle_x[1] = x2;
   P.resets++; P.last_reset_f = P.f;
   fd_reset(1);                                    /* 순간이동 — 지금 순서·동작·지연 줄은 버리고 기술 기억만 남긴다 */
}
static void request_reset(int pos)
{
   P.rq = 1; P.rq_pos = pos; P.rq_f = P.f;
}

/* F8 대미지 셈 — 맞은 쪽 콤보 수가 0→1 이면 새 콤보, 체력이 준 만큼 더한다. 콤보 수 없이 깎임(가드 깎임)은 40프레임 넘게 떨어지면 따로.
   연습(game_writes · P 칸)과 176 보기(view_tick · AV 칸)가 같은 함수를 부른다. prev_hp 는 부르는 쪽이 이번 프레임에 램을 다 쓴 뒤 잡는다 */
static void dmg_count(const uint8_t *ram, unsigned f, int prev_hp[2], int prev_combo[2], int rec_open[2],
                      int dmg_cur[2], unsigned dmg_f[2], int *side, int *shown)
{
   for (int d = 0; d < 2; d++) {
      int b = PB(d), hp = ram[b + O_HP], combo = ram[b + O_COMBO];
      if (prev_combo[d] == 0 && combo > 0) { dmg_cur[d] = 0; rec_open[d] = 1; *side = d; }
      if (hp < prev_hp[d]) {
         if (!rec_open[d] && combo == 0 && f - dmg_f[d] > 40) dmg_cur[d] = 0;   /* 콤보 수 없이 깎임(가드 깎임) — 따로 센다 */
         dmg_cur[d] += prev_hp[d] - hp; dmg_f[d] = f; *side = d;
      }
      if (combo == 0) rec_open[d] = 0;
      prev_combo[d] = combo;
   }
   if (*side >= 0) *shown = dmg_cur[*side] > 999 ? 999 : dmg_cur[*side];
}

/* ── 경기 램 쓰기(라운드 진행 · 일시정지 아님) ─────────────────────── */
static void game_writes(uint8_t *ram, int input)
{
   /* F2 시간·규칙 */
   ram[R_TIMER] = 0xFF; ram[R_TIMER + 1] = 0xFF; ram[R_NEXTT] = 0x80;
   wr16(ram, R_CPUATK, 0x100); wr16(ram, R_CPUDEF, 0x100);

   /* F4 가드 — 맞은 수·공격 시작은 이번 프레임 값으로(대미지 기록보다 먼저 지난 값을 본다) */
   {
      int b2 = PB(1), st = ram[b2 + O_STANCE], ctrl2 = ram[b2 + O_CTRL], combo2 = ram[b2 + O_COMBO];
      int atk = ram[PB(0) + O_FLAGS] & 0x80, g = 0;
      switch (PS.guard) {
      case 1: g = 1; break;
      case 2:                                    /* 첫 타 뒤: 콤보 첫 타는 맞고, 다시 움직일 수 있게 되는 순간부터 켬 · 1초(움직일 수 있는 60프레임) 안 맞으면 끔 */
         if (combo2 > 0 && P.ag_prev == 0 && !P.ag_on) P.ag_wait = 1;
         if (P.ag_wait && ctrl2 == 0) { P.ag_wait = 0; P.ag_on = 1; P.ag_idle = 0; }
         if (P.ag_on) { if (ctrl2 == 0) { if (++P.ag_idle >= 60) P.ag_on = 0; } else P.ag_idle = 0; }
         g = P.ag_on;
         break;
      case 3:                                    /* 랜덤: P1 공격이 시작되는 프레임마다 반반 — 가드 경직 중엔 안 바꿈 */
         if (atk && !P.prev_atk && ctrl2 != 0x12) P.rnd_on = (int)((rnd() >> 7) & 1);
         g = P.rnd_on;
         break;
      default: break;
      }
      P.ag_prev = combo2; P.prev_atk = atk;
      if (PS.guard != 2) { P.ag_on = 0; P.ag_wait = 0; }
      P.g_on = g;
      if (g && (st & 0x60) && !(st & 0x80)) ram[b2 + O_STANCE] = (uint8_t)(st | 0x18);   /* 땅에서만 · 자세 비트는 그대로 */
      else if ((st & 0x18) == 0x18) ram[b2 + O_STANCE] = (uint8_t)(st & ~0x18);
      /* ↑ 끌 때는 우리가 쓴 비트를 지운다 — 가만히 선 더미(사람 갈래·패드 0)는 게임이 이 비트를 다시 안 써서 한 번 쓰면 다음 가드까지 남는다(실측).
         상단·하단 둘 다(0x18)는 원래 게임에 없는 상태라 CPU 더미가 스스로 쓴 가드 비트와 헷갈리지 않는다 */
   }

   if (P.set_hp2 > 0) {                         /* 시험용 체력 설정 — 대미지·회복 셈에 안 들어가게 */
      ram[PB(1) + O_HP] = (uint8_t)P.set_hp2; ram[PB(1) + O_DHP] = (uint8_t)P.set_hp2;
      P.prev_hp[1] = P.set_hp2; P.calm[1] = 0; P.set_hp2 = 0;
   }
   /* F8 대미지(바닥을 쓰기 전에) */
   dmg_count(ram, P.f, P.prev_hp, P.prev_combo, P.rec_open, P.dmg_cur, P.dmg_f, &P.shown_side, &P.shown);

   /* F2 체력 바닥·회복 */
   for (int p = 0; p < 2; p++) {
      int b = PB(p), hp = ram[b + O_HP];
      if (ram[b + O_CTRL] == 0) P.calm[p]++; else P.calm[p] = 0;
      if (hp < 64) { ram[b + O_HP] = 64; hp = 64; }
      if (P.calm[p] >= 40 && hp < 128) { ram[b + O_HP] = 128; ram[b + O_DHP] = 128; }
   }

   /* F3 더미 서기 / CPU */
   {
      int want = PS.dummy_cpu && P.cpu_ok;
      if (!want) ram[PB(1) + O_CPU] &= (uint8_t)~1;
      else if (P.cpu_set != 1) ram[PB(1) + O_CPU] |= 1;
      P.cpu_set = want;
   }

   /* F6 분노 MAX 유지 */
   for (int p = 0; p < 2; p++) {
      int b = PB(p), act = ram[b + O_CTRL] == 0, ground = rd16(ram, b + O_Y) == 128;
      if (PS.rage[p]) {
         if (!(ram[b + O_FLAGS] & 0x04)) { if (act) set_max(ram, p); }
         else if (rd16(ram, b + O_MAXT) < 0x1000) wr16(ram, b + O_MAXT, 0x3000);
         P.max_keep[p] = 1;
      } else if (P.max_keep[p] && act && ground) {              /* MAX 유지를 끔 — 움직일 수 있을 때 MAX 를 거둔다 */
         if (ram[b + O_FLAGS] & 0x04) clear_max(ram, p);
         P.max_keep[p] = 0;
      }
   }

   /* 분노 게이지 되살리기 — 비오의를 쓰면 게임이 그 판 동안 분노 게이지를 지우고 A+B 분노도 막는다(O_SECRET bit7).
      연습 경기는 판이 안 끝나니, 쓴 사람이 연출을 마치고 다시 움직일 수 있으면(땅·MAX 아님) 되살린다
      (유저 2026-10-14 「분노 소모하고 나면 없어지거든 분노 게이지가」 · 사쇼 2019 연습 «Rage Gauge Revive») */
   for (int p = 0; p < 2; p++) {
      int b = PB(p);
      if ((ram[b + O_SECRET] & 0x80) && ram[b + O_CTRL] == 0 && rd16(ram, b + O_Y) == 128 && !(ram[b + O_FLAGS] & 0x04))
         ram[b + O_SECRET] &= (uint8_t)~0x80;
   }

   /* 리셋 뒤 6프레임 — 겹쳐 있던 두 사람(구석에 몰린 더미에 붙은 P1 등)을 떼어 놓으면 게임이 밀어내기를 한 번 더 해 3px 쯤 밀릴 때가 있다
      (호스트 시험 무작위 리셋 60여 번 중 1번꼴). 손대지 않은 사람(입력 없는 P1 · 서 있는 더미)이 움직일 수 있고 땅에 선 채 자리가 바뀌었으면 되돌린다 */
   if (P.settle > 0) {
      P.settle--;
      for (int p = 0; p < 2; p++) {
         int idle = p == 0 ? !(input & (P_DIRS | IN_A | IN_B | P_FOLD)) : !(PS.dummy_cpu && P.cpu_ok);
         if (idle && ram[PB(p) + O_CTRL] == 0 && rd16(ram, PB(p) + O_Y) == 128 && rd16(ram, PB(p) + O_X) != P.settle_x[p])
            set_x(ram, p, P.settle_x[p]);
      }
   }
   /* F5 리셋 — 중립이 되는 첫 프레임에. 3초 안에 안 오면 버림(1.5초 뒤부터는 멈춰 있음, 2초 뒤부터는 남은 장풍·효과 물체 수는 안 봄) */
   for (int p = 0; p < 2; p++) {
      int x = rd16(ram, PB(p) + O_X);
      P.still[p] = x == P.prev_x[p] ? P.still[p] + 1 : 0; P.prev_x[p] = x;
   }
   if (P.rq) {
      unsigned w = P.f - P.rq_f;
      if (w > 180) { P.rq = 0; P.dropped++; }
      else if (neutral(ram, w > 120 ? 2 : w > 90 ? 1 : 0)) { do_reset(ram); P.rq = 0; }
   }
   for (int p = 0; p < 2; p++) P.prev_hp[p] = ram[PB(p) + O_HP];
}

/* ── 프레임 데이터(FD) — SF6 식 프레임 미터 · 발동/지속/경직 · 유불리 ─────────────
   설계: next/framedata/design.md 5장. 참조 구현 = x/fd_ref.py(같은 규칙, 호스트 시험이 프레임마다 비교).
   진짜 프레임마다 practice_tick 안, game_writes 앞(우리가 램을 고치기 전)에 한 번. 라운드 진행·일시정지 아님일 때만.
   램을 읽기만 한다(쓰기 0) — 켜고 꺼도 게임은 같다.

     닿음      히트스톱 0x5DC7 이 0 → 0 아님 = 닿은 프레임. 맞은 쪽 = 콤보 수(+0x117)가 오른 쪽, 아니면 가드 비트(자세 & 0x18) 선 쪽(가드),
               아니면 튕겨내기 비트(& 0x03) 쪽(상쇄). 히트스톱 없이 콤보 수가 0 → 1 = 잡기(이어지는 다단 타는 잡기가 아니다).
     얼림      히트스톱이 이어지는 프레임(닿은 프레임 다음부터) — 미터에 안 쌓는다(SF6 처럼). 닿은 프레임은 쌓는다.
     지속      싸움꾼 공격 상자 = 물체 +0x1C != 0xFF. 게임은 상자를 켠 다음 틱에 맞춤을 본다 → 쌓인 칸 둘 늦게(2칸 지연 줄)가 «닿을 수 있음».
               순간이동 탐침(멀리서 헛친 기술의 j 번째 프레임에 더미를 앞에 둠)과 18기술 모두 같음.
     장풍      0x4180 활성 목록의 4번 칸부터, 살아 있음(+06 != FF) 이고 공격 상자(+1E 또는 +1C) 켬, 주인 +0x27. 주인이 «동작 중»에 켠 것만(개·매는
               걸을 때도 상자를 켠다). 켜진 프레임(2칸 지연) = 그 동작의 발동.
     움직임    조작 0x4E: 0 = 움직일 수 있음 · 04/06 대시 · 08 점프 · 0A 맞음 · 0C 공중 맞음 · 10 다운 · 12 가드 · 그 밖(02 보통 공격 · FF 필살·잡기·반동 …) = 동작.
               맞는 줄(0A·0C·10·12·콤보·닿음)에 들어가면 0 이 될 때까지 경직(기상 02 도 다운으로).
     동작      움직일 수 있음·대시·점프에서 동작 값으로 바뀌면 시작. 보통 공격(02) 중 필살(ACT ≥ 0x180)로 바뀌면 캔슬 = 새 동작.
     유불리    닿은 뒤 각자 조작이 처음 0 이 된 프레임(히트스톱 0)의 차 = P2 − P1(+ = 내가 먼저). combat_ref 와 같은 규칙(무작위 7대진 35000프레임 같음).
     순서      198 내 공격만(유저 10-21 「프레임미터는 내 공격만 표시」): 내(P1) 동작(발동·지속·경직 칸)이 시작될 때만 새 순서(미터 비움).
               순서는 내가 움직이는 동안(동작·점프·대시·경직) · 상대가 맞음·막음·다운인 동안(내 공격의 결과) · 내 장풍이 날아가는 동안만 이어 가고,
               아니면 끝(미터는 그대로 남김) — 상대 혼자 하는 동작(점프·대시·공격)은 새 순서를 안 열고 내 미터를 지우지도 않는다(이전: 누가 움직이든 새 순서).
               숫자 줄은 늘 내 동작, 유불리는 내가 맞힌·막힌 닿음만(상대가 나를 맞히면 기다리던 유불리도 버림).
               한 사람은 움직일 수 있고 다른 사람은 같은 경직이 8칸 넘게 이어지면 · 장풍만 날아가면 한 칸(줄임)만 남기고 센다. 80칸 넘으면 감는다. */
#define FD_VIEW 80
#define FD_MEM  32
#define FD_RUN  8
#define FD_NONE 0x7FFF
enum { FK_IDLE = 0, FK_MOVE, FK_STARTUP, FK_ACTIVE, FK_RECOVERY, FK_ACTION, FK_HITSTUN, FK_BLOCKSTUN, FK_DOWN, FK_GAP };
#define FK_F_PARRY 0x40
#define FK_F_PROJ  0x80
#define FK_EMPTY   0xFF
enum { FC_NONE = 0, FC_HIT, FC_BLOCK, FC_THROW, FC_OTHER, FC_DOWN };
#define R_OBJLIST A(0x4180)   /* 활성 물체 목록 — (칸 번호, 정렬 값) × 0x41C2 개 */
#define O_SCR    0x4C         /* 0x59CC u16 — 공격 스크립트 자리(기술 열쇠) */

typedef struct { uint8_t kc; uint16_t ka, ks; int16_t startup, active, recovery, total, adv[4]; } FdMem;   /* adv: 히트·다운·가드·잡기 (공격한 쪽 기준) */
typedef struct { int live, start, n, first, last, proj, contact, contact_at, pfirst, plast; uint8_t kc; uint16_t ka, ks; } FdAct;
typedef struct { int id, startup, active, recovery, total, contact, pstart, plen; uint8_t kc; uint16_t ka, ks; } FdSum;
static struct {
   int have_prev; uint8_t p_hs, p_ctrl[2], p_combo[2]; uint16_t p_act[2]; uint8_t p_cap[32];
   int8_t armed[32];
   uint8_t mode[2], stun[2], downseen[2], boxq[2][2], armq[2][2];
   FdAct act[2];
   int seq_on, len, gap_open, gap_n, run, last_pair, frozen_n; uint32_t seq_id;
   uint8_t cell[2][FD_VIEW];
   int wait_on, wait[2], last_kind, att, ckey_ok[2]; uint32_t f, contact_f; uint8_t ckc[2]; uint16_t cka[2], cks[2];
   int adv, adv_kind, adv_att, adv_id, focus;
   FdSum sum[2];
   FdMem mem[FD_MEM]; int mem_n, mem_next;
   uint32_t ver;
} FD;

static void fd_reset(int keep_mem)
{
   FdMem m[FD_MEM]; int mn = FD.mem_n, mx = FD.mem_next; uint32_t v = FD.ver + 1;
   if (keep_mem) memcpy(m, FD.mem, sizeof m);
   memset(&FD, 0, sizeof FD);
   memset(FD.cell, FK_EMPTY, sizeof FD.cell);
   memset(FD.armed, -1, sizeof FD.armed);
   if (keep_mem) { memcpy(FD.mem, m, sizeof m); FD.mem_n = mn; FD.mem_next = mx; }
   FD.adv = FD_NONE; FD.ver = v; FD.focus = -1;
}
static FdMem *fd_mem(uint8_t kc, uint16_t ka, uint16_t ks, int make)
{
   for (int i = 0; i < FD.mem_n; i++) if (FD.mem[i].kc == kc && FD.mem[i].ka == ka && FD.mem[i].ks == ks) return &FD.mem[i];
   if (!make) return NULL;
   int i = FD.mem_n < FD_MEM ? FD.mem_n++ : (FD.mem_next++ % FD_MEM);   /* 가득 차면 오래된 것부터 */
   FdMem *m = &FD.mem[i];
   m->kc = kc; m->ka = ka; m->ks = ks; m->startup = m->active = m->recovery = m->total = -1;
   for (int k = 0; k < 4; k++) m->adv[k] = FD_NONE;
   return m;
}
static void fd_end(int p, int why)          /* why 0 = 움직일 수 있게 됨 · 1 = 맞음 · 2 = 다음 동작(캔슬) */
{
   FdAct *a = &FD.act[p];
   if (!a->live) return;
   a->live = 0;
   if (a->first < 0)                          /* 판정 없이 끝난 동작 — 보이는 칸의 «발동»을 «판정 없음»으로 고쳐 칠한다 */
      for (int i = 0; i < a->n; i++) {
         int j = a->start + i;
         if (j < 0 || j >= FD.len || FD.len - j > FD_VIEW) continue;
         uint8_t *c = &FD.cell[p][j % FD_VIEW];
         if (*c != FK_EMPTY && (*c & 0x0F) == FK_STARTUP) *c = (uint8_t)((*c & 0xF0) | FK_ACTION);
      }
   FdSum *s = &FD.sum[p];
   if (p == 0) FD.focus = p;                  /* 숫자 줄이 보여 줄 동작 — 198 내 동작만(이전: 상대 동작도 닿았으면) */
   s->id++; s->total = a->n; s->contact = a->contact; s->kc = a->kc; s->ka = a->ka; s->ks = a->ks;
   s->pstart = a->pfirst < 0 ? -1 : a->pfirst + 1; s->plen = a->pfirst < 0 ? 0 : a->plast - a->pfirst + 1;
   if (a->first < 0) { s->startup = s->active = s->recovery = -1; }
   else {
      s->startup = a->first + 1; s->active = a->proj ? -2 : a->last - a->first + 1; s->recovery = a->n - 1 - a->last;
      FdMem *m = fd_mem(a->kc, a->ka, a->ks, 1);
      m->startup = (int16_t)s->startup;
      if (!a->contact && why == 0) { m->active = (int16_t)s->active; m->recovery = (int16_t)s->recovery; m->total = (int16_t)s->total; }
   }
   FD.ver++;
}
static void fd_push(int c0, int c1)
{
   FD.cell[0][FD.len % FD_VIEW] = (uint8_t)c0; FD.cell[1][FD.len % FD_VIEW] = (uint8_t)c1;
   FD.len++; FD.ver++;
}
static int fd_stun_kind(int c) { c &= 0x0F; return c == FK_HITSTUN || c == FK_BLOCKSTUN || c == FK_DOWN; }

static void fd_tick(const uint8_t *ram)
{
   uint8_t ctrl[2], combo[2], st[2], box[2], proj[2], cap[32];
   uint16_t act[2], y[2];
   int hs = ram[R_HSTOP];
   for (int p = 0; p < 2; p++) {
      int b = PB(p), o = OBJ(p);
      ctrl[p] = ram[b + O_CTRL]; combo[p] = ram[b + O_COMBO]; st[p] = ram[b + O_STANCE];
      y[p] = (uint16_t)rd16(ram, b + O_Y); act[p] = (uint16_t)rd16(ram, o + 0x0E); box[p] = ram[o + 0x1C] != 0xFF;
   }
   memset(cap, 0, sizeof cap);
   {
      int n = ram[R_OBJN] > 30 ? 30 : ram[R_OBJN];
      for (int i = 0; i < n; i++) {
         int k = ram[R_OBJLIST + 2 * i];
         if (k < 4 || k > 31) continue;
         int o = OBJ(k);
         if (ram[o + 0x06] != 0xFF && (ram[o + 0x1E] != 0xFF || ram[o + 0x1C] != 0xFF)) cap[k] = (uint8_t)(1 | (ram[o + 0x27] & 1) << 1 | (ram[o + 0x27] > 1 ? 4 : 0));
      }
   }
   FD.f++;
   if (!FD.have_prev) goto keep;
   int frozen = hs && FD.p_hs;

   /* 닿음 */
   {
      int kind = 0, d = -1;
      if (hs && !FD.p_hs) {
         for (int q = 0; q < 2 && d < 0; q++) if (combo[q] > FD.p_combo[q]) { d = q; kind = FC_HIT; }
         for (int q = 0; q < 2 && d < 0; q++) if (st[q] & 0x18) { d = q; kind = FC_BLOCK; }
         for (int q = 0; q < 2 && d < 0; q++) if (st[q] & 0x03) { d = q; kind = FC_OTHER; }
         if (d < 0) kind = FC_OTHER;
      } else if (!hs) {
         for (int q = 0; q < 2 && d < 0; q++) if (combo[q] > FD.p_combo[q]) { d = q; kind = FD.p_combo[q] == 0 ? FC_THROW : (FD.last_kind ? FD.last_kind : FC_HIT); }
      }
      if (kind) {
         FD.last_kind = kind;
         if (d == 0) FD.wait_on = 0;                /* 198 상대가 나를 맞힘·막게 함 — 유불리는 내 공격만(기다리던 것도 버림) */
         else { FD.wait_on = 1; FD.wait[0] = FD.wait[1] = -1; FD.contact_f = FD.f; FD.att = d >= 0 ? 1 - d : -1; }
         if (d >= 0) {
            FD.mode[d] = 2; FD.stun[d] = kind == FC_BLOCK ? FC_BLOCK : FC_HIT;
            FdAct *a = &FD.act[1 - d];
            if (a->live && !a->contact) { a->contact = kind; a->contact_at = a->n; }
            if (a->live) { FD.ckey_ok[1 - d] = 1; FD.ckc[1 - d] = a->kc; FD.cka[1 - d] = a->ka; FD.cks[1 - d] = a->ks; }
         }
      }
   }
   /* 장풍 arm — 주인이 동작 중에 켠 공격 상자만 */
   for (int k = 4; k < 32; k++) {
      if (!(cap[k] & 1)) { FD.armed[k] = -1; continue; }
      int ow = (cap[k] & 4) ? -1 : (cap[k] >> 1) & 1;
      if (!(FD.p_cap[k] & 1) && ow >= 0 && FD.mode[ow] == 1) FD.armed[k] = (int8_t)ow;
   }
   proj[0] = proj[1] = 0;
   for (int k = 4; k < 32; k++) if (FD.armed[k] >= 0) proj[FD.armed[k]] = 1;
   if (frozen) { FD.frozen_n++; goto adv; }

   /* 칸 */
   {
      int cls[2];
      for (int p = 0; p < 2; p++) {
         int act_now = FD.boxq[p][1], proj_now = FD.armq[p][1], c;
         int pflag = proj_now ? FK_F_PROJ : 0;                              /* 캔슬로 지연 줄을 비워도 이 칸의 장풍 표시는 그대로(참조 구현과 같게) */
         FD.boxq[p][1] = FD.boxq[p][0]; FD.boxq[p][0] = box[p];
         FD.armq[p][1] = FD.armq[p][0]; FD.armq[p][0] = proj[p];
         int flags = ((st[p] & 0x03) ? FK_F_PARRY : 0);
         if (ctrl[p] == 0) {
            fd_end(p, 0); FD.mode[p] = 0; FD.downseen[p] = 0;
            c = y[p] < 128 ? FK_MOVE : FK_IDLE;
         } else if (FD.mode[p] == 2 || ctrl[p] == 0x0A || ctrl[p] == 0x0C || ctrl[p] == 0x10 || ctrl[p] == 0x12) {
            if (FD.mode[p] != 2) { FD.stun[p] = ctrl[p] == 0x12 ? FC_BLOCK : FC_HIT; FD.downseen[p] = 0; }
            fd_end(p, 1); FD.mode[p] = 2;
            if (ctrl[p] == 0x10) FD.downseen[p] = 1;
            c = FD.downseen[p] ? FK_DOWN : (FD.stun[p] == FC_BLOCK || ctrl[p] == 0x12) ? FK_BLOCKSTUN : FK_HITSTUN;
         } else if (ctrl[p] == 0x04 || ctrl[p] == 0x06 || ctrl[p] == 0x08) {
            fd_end(p, 0); FD.mode[p] = 0;
            c = FK_MOVE;
         } else {
            FdAct *a = &FD.act[p];
            int cancel = FD.mode[p] == 1 && FD.p_ctrl[p] == 0x02 && act[p] >= 0x180 && act[p] != FD.p_act[p];
            if (FD.mode[p] != 1 || cancel) {
               fd_end(p, 2);
               memset(a, 0, sizeof *a);
               a->live = 1; a->start = FD.len; a->first = a->last = a->pfirst = a->plast = -1; a->contact_at = -1;
               a->kc = ram[PB(p) + O_CHAR]; a->ka = act[p]; a->ks = (uint16_t)rd16(ram, PB(p) + O_SCR);
               act_now = 0; FD.boxq[p][0] = box[p]; FD.boxq[p][1] = 0;      /* 캔슬 — 앞 동작의 상자가 지연 줄에 남지 않게 */
               proj_now = 0; FD.armq[p][0] = proj[p]; FD.armq[p][1] = 0;
            }
            FD.mode[p] = 1;
            if (act_now) { if (a->first < 0) a->first = a->n; a->last = a->n; c = FK_ACTIVE; }
            else if (proj_now && a->first < 0) { a->first = a->last = a->n; a->proj = 1; c = FK_ACTIVE; }
            else c = a->first >= 0 ? FK_RECOVERY : FK_STARTUP;
            if (st[p] & 0x03) { if (a->pfirst < 0) a->pfirst = a->n; a->plast = a->n; }
            a->n++;
         }
         cls[p] = c | flags | pflag;
      }
      /* 198 내 공격만 — 열기: 내 동작 칸 · 잇기: 내가 가만있지 않음(동작·점프·대시·경직) 또는 상대가 경직(내 공격의 결과) · 날기: 내 장풍 */
      int c0 = cls[0] & 0x0F, mine_start = c0 == FK_STARTUP || c0 == FK_ACTIVE || c0 == FK_RECOVERY;
      int actor = c0 != FK_IDLE || fd_stun_kind(cls[1]), flying = proj[0];
      if (!FD.seq_on && mine_start) {
         FD.seq_on = 1; FD.seq_id++; FD.len = 0; FD.gap_open = 0; FD.gap_n = 0; FD.run = 0; FD.last_pair = -1; FD.frozen_n = 0;
         memset(FD.cell, FK_EMPTY, sizeof FD.cell);
         for (int p = 0; p < 2; p++) if (FD.act[p].live && FD.mode[p] == 1) FD.act[p].start = 0;
         FD.ver++;
      }
      if (FD.seq_on) {
         int pair = cls[0] << 8 | cls[1];
         FD.run = pair == FD.last_pair ? FD.run + 1 : 1; FD.last_pair = pair;
         int waitrun = actor && FD.run > FD_RUN && ((cls[0] & 0x0F) == FK_IDLE || (cls[1] & 0x0F) == FK_IDLE) && (fd_stun_kind(cls[0]) || fd_stun_kind(cls[1]));
         if (actor && !waitrun) { fd_push(cls[0], cls[1]); FD.gap_open = 0; }
         else if (actor || flying) {
            if (!FD.gap_open) {
               if (waitrun) fd_push(FK_GAP, FK_GAP); else fd_push(FK_GAP | (cls[0] & FK_F_PROJ), FK_GAP | (cls[1] & FK_F_PROJ));
               FD.gap_open = 1;
            }
            FD.gap_n++;
         } else { FD.seq_on = 0; FD.ver++; }
      }
   }
adv:
   /* 유불리 */
   if (FD.wait_on && FD.f - FD.contact_f > 400) FD.wait_on = 0;
   if (FD.wait_on && FD.att >= 0 && ctrl[1 - FD.att] == 0x10 && FD.last_kind == FC_HIT) FD.last_kind = FC_DOWN;
   if (FD.wait_on && !hs) {
      for (int p = 0; p < 2; p++) if (FD.wait[p] < 0 && ctrl[p] == 0) FD.wait[p] = (int)FD.f;
      if (FD.wait[0] >= 0 && FD.wait[1] >= 0) {
         FD.adv = FD.wait[1] - FD.wait[0]; FD.adv_kind = FD.last_kind; FD.adv_att = FD.att; FD.adv_id++; FD.wait_on = 0; FD.ver++;
         if (FD.att >= 0 && FD.ckey_ok[FD.att]) {
            static const int SLOT[6] = { -1, 0, 2, 3, -1, 1 };   /* FC_* → adv[] 칸(히트·다운·가드·잡기) */
            int sl = SLOT[FD.last_kind];
            FdMem *m = sl >= 0 ? fd_mem(FD.ckc[FD.att], FD.cka[FD.att], FD.cks[FD.att], 1) : NULL;
            if (m) m->adv[sl] = (int16_t)(FD.att == 0 ? FD.adv : -FD.adv);
         }
      }
   }
keep:
   FD.p_hs = (uint8_t)hs;
   for (int p = 0; p < 2; p++) { FD.p_ctrl[p] = ctrl[p]; FD.p_combo[p] = combo[p]; FD.p_act[p] = act[p]; }
   memcpy(FD.p_cap, cap, sizeof cap);
   FD.have_prev = 1;
}

/* ── HUD: «COM» → «2P»(F9) · 대미지 숫자(F8) ──────────────────────── */
static int hud_layout(void)
{
   if (!v_chr || !v_scr) return 0;
   return (ent_rd(0, 12) & 0x1FF) == 0x049 && (ent_rd(0, 13) & 0x1FF) == 0x04A && (ent_rd(0, 7) & 0x1FF) == 0x03C;
}
/* 대미지 숫자: 셋째 줄 검은 띠 가운데(칸 10 에 맞춰 오른쪽 정렬 — 두 자리 9·10, 세 자리 8~10), 게임 «N HITS» 숫자 타일 0x002~0x00B · 팔레트 4.
   연습(hud_update · P 칸)과 176 보기(view_tick · AV 칸)가 같은 함수 — 같은 칸·같은 그림. want = 지금 보일지 · st/orig/ours = 그린 상태·원래 값·우리 값 */
static void dig_draw(int want, int v, int *st, uint16_t orig[3], uint16_t ours[3])
{
   if (*st) for (int i = 0; i < 3; i++) if (ent_rd(3, 8 + i) != ours[i]) orig[i] = ent_rd(3, 8 + i);
   if (want) {
      if (!*st) {
         for (int i = 0; i < 3; i++) orig[i] = ent_rd(3, 8 + i);
         if ((orig[0] & 0x1FF) != 0x001 || (orig[1] & 0x1FF) != 0x001 || (orig[2] & 0x1FF) != 0x001) return;   /* 검은 띠가 아니면 안 씀 */
      }
      int dg[3] = { v / 100, (v / 10) % 10, v % 10 }, nd = v >= 100 ? 3 : v >= 10 ? 2 : 1;
      for (int i = 0; i < 3; i++) {
         uint16_t w = i >= 3 - nd ? (uint16_t)((4 << 9) | (0x002 + dg[i])) : orig[i];
         if (ent_rd(3, 8 + i) != w) ent_wr(3, 8 + i, w);
         ours[i] = w;
      }
      *st = 1;
   } else if (*st) {                               /* 끔 · HUD 가 사라짐 — 우리 숫자가 그대로 있는 칸만 되돌린다 */
      for (int i = 0; i < 3; i++) if (ent_rd(3, 8 + i) == ours[i]) ent_wr(3, 8 + i, orig[i]);
      *st = 0;
   }
}
/* 상태 저장 직전 — 우리 숫자가 그대로 있는 칸만 원래대로(다음 프레임에 dig_draw 가 다시 그린다) */
static void dig_restore(int *st, uint16_t orig[3], uint16_t ours[3])
{
   if (v_scr && *st) for (int i = 0; i < 3; i++) if (ent_rd(3, 8 + i) == ours[i]) ent_wr(3, 8 + i, orig[i]);
   *st = 0;
}
static void hud_update(void)
{
   if (!v_chr || !v_scr) return;
   uint8_t *t = v_chr + 0x049 * 16;
   if (P.com_state && memcmp(t, P.com_ours, 32)) P.com_state = 0;     /* 게임이 다시 썼다 — 그게 새 원래 그림 */
   int want2p = !(PS.dummy_cpu && P.cpu_ok);
   if (want2p && hud_layout()) {
      uint8_t ours[32];
      memcpy(ours, PG_TWO, 16); memcpy(ours + 16, v_chr + 0x03C * 16, 16);   /* «2» + 게임 «1P» 의 «P» */
      if (!P.com_state) memcpy(P.com_orig, t, 32);
      if (memcmp(t, ours, 32)) memcpy(t, ours, 32);
      memcpy(P.com_ours, ours, 32); P.com_state = 1;
   } else if (!want2p && P.com_state) { memcpy(t, P.com_orig, 32); P.com_state = 0; }

   dig_draw(PS.num == 0 && P.shown >= 0 && hud_layout(), P.shown, &P.dig_state, P.dig_orig, P.dig_ours);
}
static void hud_restore(void)
{
   if (!v_chr || !v_scr) { P.com_state = 0; P.dig_state = 0; return; }
   if (P.com_state && !memcmp(v_chr + 0x049 * 16, P.com_ours, 32)) memcpy(v_chr + 0x049 * 16, P.com_orig, 32);
   dig_restore(&P.dig_state, P.dig_orig, P.dig_ours);
   P.com_state = 0;
}

/* ── 연습 메뉴 창(F7) ─────────────────────────────────────────────── */
/* 열 때: 빌릴 타일 후보 — 지금 어느 칸(두 판 32×32 전부)·스프라이트도 안 가리키는 타일만. 1순위 0x0D8~0x105, 그다음 0x060~0x1FF
   (0x000~0x05F 는 HUD 글꼴·숫자·PAUSE 라 뺀다). 0x0D8~0x105 는 설계 때 7상태에서 안 쓰였지만 실제로는 많은 캐릭터의 필살 효과가
   첫째 판 효과 층(줄 19~31)에 쓴다(1단계 시험 P-15) — 그래서 «지금 가리키는가»를 열 때마다 본다. 일시정지 중엔 게임이 문자 램·판을
   안 건드리고, 닫을 때 모두 되돌리므로 지금 안 보이는 타일은 빌려도 안전하다(게임이 다시 돌기 전에 원래 그림). */
static void build_candidates(void)
{
   static uint8_t used[512];
   int pal_used[16] = { 0 };
   memset(used, 0, sizeof used);
   for (int pl = 0; pl < 2; pl++)
      for (int i = 0; i < 1024; i++) {
         const uint8_t *e = v_scr + pl * 0x800 + i * 2; int v = e[0] | (e[1] << 8);
         used[v & 0x1FF] = 1;
         if (pl == 0) pal_used[(v >> 9) & 15] = 1;
      }
   /* 팔레트: 첫째 판(HUD·효과 층)이 지금 안 쓰는 것 둘 — 13·14·15·12·6·2 차례(효과 층이 13 을 쓸 때가 있다, 실측). 모자라면 13·14 */
   {
      static const int PREF[6] = { 13, 14, 15, 12, 6, 2 };
      int n = 0;
      for (int i = 0; i < 6 && n < 2; i++) if (!pal_used[PREF[i]]) W.pal[n++] = PREF[i];
      if (n < 2) { W.pal[0] = 13; W.pal[1] = 14; }
   }
   if (v_spr) for (int s = 0; s < 64; s++) used[v_spr[s * 4] | ((v_spr[s * 4 + 1] & 1) << 8)] = 1;
   W.ncand = 0; W.cand_i = 0; W.skipped = 0;
   for (int t = 0x0D8; t <= 0x105; t++) { if (!used[t]) W.cand[W.ncand++] = (uint16_t)t; else W.skipped++; }
   for (int t = 0x060; t < 0x200; t++) if ((t < 0x0D8 || t > 0x105) && !used[t]) W.cand[W.ncand++] = (uint16_t)t;
}
static int pool_get(int k)        /* k 번째로 쓸 타일(없으면 후보에서 빌림) — −1 = 모자람 */
{
   int live_i = -1;
   for (int i = 0, n = 0; i < W.npool; i++) if (!W.pool_dead[i] && n++ == k) { live_i = i; break; }
   if (live_i >= 0) return live_i;
   while (W.npool < POOL_MAX && W.cand_i < W.ncand) {
      int t = W.cand[W.cand_i++];
      W.pool[W.npool] = (uint16_t)t; W.pool_dead[W.npool] = 0;
      memcpy(W.pool_orig[W.npool], v_chr + t * 16, 16); memcpy(W.pool_ours[W.npool], v_chr + t * 16, 16);
      W.npool++;
      int n = 0; for (int i = 0; i < W.npool; i++) if (!W.pool_dead[i]) n++;
      if (n > k) return W.npool - 1;
   }
   return -1;
}
static void window_restore(void)
{
   if (!W.open) return;
   for (int b = 0; b < 2; b++) if (g_ram && W.fx_orig[b] >= 0) { g_ram[WBX[b]] = (uint8_t)W.fx_orig[b]; W.fx_orig[b] = -1; }
   if (v_scr) for (int b = 0; b < 2; b++) for (int r = 0; r < WR; r++) for (int c = 0; c < WC; c++) ent_wr(WBAND[b] + r, 3 + c, W.cell_orig[b][r][c]);
   if (v_chr) for (int i = 0; i < W.npool; i++) memcpy(v_chr + W.pool[i] * 16, W.pool_orig[i], 16);
   if (v_pal) for (int k = 0; k < 2; k++) memcpy(v_pal + 0x80 + W.pal[k] * 8, W.pal_orig[k], 8);
   W.open = 0; W.npool = 0; W.cand_i = 0;
}
/* 창 칸 하나 — 글자 열쇠(0 테두리 가로 · 1 세로 · 2 모서리 · 3 커서 · 4+g 글자 g) */
#define K_NONE (-1)
static int row_value(int r)
{
   switch (r) {
   case 0: return PS.dummy_cpu && P.cpu_ok;
   case 1: return PS.guard;
   case 2: return PS.rage[0];
   case 3: return PS.rage[1];
   case 4: return PS.rpos;
   case 5: return PS.swap;
   case 6: return PS.num == 0 ? (PS.fd ? 2 : 0) : (PS.fd ? 1 : 3);   /* 표시: 0 대미지 · 1 프레임 · 2 둘 다 · 3 끔 */
   default: return -1;
   }
}
static void window_draw(void)
{
   static const uint8_t *BT[4] = { PG_BORDER_H, PG_BORDER_V, PG_BORDER_C, PG_CURSOR };
   int key[WR][WC], pal[WR][WC], flip[WR][WC];
   if (!v_chr || !v_scr || !v_pal) return;
   /* 띠마다 그 틱엔 줄 32~136 이 가로로 0x41F7(줄 4~16) · 0x41F8(효과 층 줄 19~31) 만큼 밀려 보인다 — 창이 열린 동안은 둘 다 0 으로
      (일시정지 중 게임은 이 값을 안 바꾼다, 실측). 줄 4~16 쪽도 효과가 남긴 값이 있으면 창이 틱마다 그만큼 옆으로 뛰고, 프레임 생성(60Hz 사이 그림)은
      두 값 차가 32 px 안이면 그 사이로 옮겨 그려 매 프레임 다른 자리가 된다(0x1F ↔ 0 → 사이 그림 16 px, 실측).
      한 틱씩 X mod 8 px 흔들리지 않게 옮겨 그리는 대신 이렇게 한다. 닫을 때·상태 저장 전에 원래 값 */
   if (g_ram) for (int b = 0; b < 2; b++) {
      if (!W.open) W.fx_orig[b] = g_ram[WBX[b]];
      else if (W.fx_orig[b] >= 0 && g_ram[WBX[b]] != 0) W.fx_orig[b] = g_ram[WBX[b]];   /* 게임이 그사이 썼다 — 새 원래 값 */
      g_ram[WBX[b]] = 0;
   }
   if (!W.open) {                                   /* 처음: 칸·팔레트 원래 값 보관 */
      for (int b = 0; b < 2; b++) for (int r = 0; r < WR; r++) for (int c = 0; c < WC; c++) W.cell_orig[b][r][c] = W.cell_ours[b][r][c] = ent_rd(WBAND[b] + r, 3 + c);
      for (int k = 0; k < 2; k++) { memcpy(W.pal_orig[k], v_pal + 0x80 + W.pal[k] * 8, 8); memcpy(W.pal_ours[k], W.pal_orig[k], 8); }
      W.npool = 0; W.cand_i = 0; W.open = 1;
   } else {                                         /* 게임이 그사이 쓴 것은 새 원래 값으로 */
      for (int b = 0; b < 2; b++) for (int r = 0; r < WR; r++) for (int c = 0; c < WC; c++)
         if (ent_rd(WBAND[b] + r, 3 + c) != W.cell_ours[b][r][c]) W.cell_orig[b][r][c] = ent_rd(WBAND[b] + r, 3 + c);
      for (int i = 0; i < W.npool; i++)
         if (!W.pool_dead[i] && memcmp(v_chr + W.pool[i] * 16, W.pool_ours[i], 16)) { memcpy(W.pool_orig[i], v_chr + W.pool[i] * 16, 16); W.pool_dead[i] = 1; }
      for (int k = 0; k < 2; k++) if (memcmp(v_pal + 0x80 + W.pal[k] * 8, W.pal_ours[k], 8)) memcpy(W.pal_orig[k], v_pal + 0x80 + W.pal[k] * 8, 8);
   }
   /* 칸 배치: 줄 0·12 = 테두리, 칸 0·13 = 테두리, 줄 1 = 제목, 줄 2~11 = 메뉴 10줄(칸 1 커서 · 2~6 이름 · 8~12 값, 실행 줄은 2~12 이름) */
   for (int r = 0; r < WR; r++)
      for (int c = 0; c < WC; c++) { key[r][c] = K_NONE; pal[r][c] = W.pal[0]; flip[r][c] = 0; }
   for (int c = 0; c < WC; c++) {
      int corner = c == 0 || c == WC - 1;
      key[0][c] = corner ? 2 : 0; key[WR - 1][c] = corner ? 2 : 0;
      flip[0][c] = c == WC - 1 ? 0x8000 : 0; flip[WR - 1][c] = 0x4000 | (c == WC - 1 ? 0x8000 : 0);
      pal[0][c] = pal[WR - 1][c] = W.pal[1];
   }
   for (int r = 1; r < WR - 1; r++) { key[r][0] = 1; key[r][WC - 1] = 1; flip[r][WC - 1] = 0x8000; pal[r][0] = pal[r][WC - 1] = W.pal[1]; }
   {
      int c0 = 1 + (12 - PG_TITLE_W) / 2;
      for (int i = 0; i < PG_TITLE_W; i++) { if (PG_TITLE[i]) key[1][c0 + i] = 3 + PG_TITLE[i]; pal[1][c0 + i] = W.pal[1]; }
   }
   for (int i = 0; i < PG_ROWS; i++) {
      int r = 2 + i, sel = i == P.row, p = sel ? W.pal[1] : W.pal[0];
      for (int c = 1; c < WC - 1; c++) pal[r][c] = p;
      if (sel) key[r][1] = 3;
      if (PG_NVAL[i] == 0) {
         for (int k = 0; k < PG_LABEL_W; k++) if (PG_LABEL[i][k]) key[r][2 + k] = 3 + PG_LABEL[i][k];
      } else {
         for (int k = 0; k < PG_VALUE_W; k++) if (PG_LABEL[i][k]) key[r][2 + k] = 3 + PG_LABEL[i][k];
         int v = row_value(i);
         if (v < 0 || v >= PG_NVAL[i]) v = 0;
         for (int k = 0; k < PG_VALUE_W; k++) if (PG_VALUE[i][v][k]) key[r][8 + k] = 3 + PG_VALUE[i][v][k];
      }
   }
   /* 열쇠 → 빌린 타일(나오는 차례대로) */
   int slot_of[4 + PG_N + 1], nslot = 0;
   for (int i = 0; i < 4 + PG_N + 1; i++) slot_of[i] = -1;
   for (int r = 0; r < WR; r++)
      for (int c = 0; c < WC; c++) {
         int k = key[r][c];
         if (k == K_NONE || k > 3 + PG_N || slot_of[k] != -1) continue;
         int pi = pool_get(nslot);
         slot_of[k] = pi >= 0 ? pi : -2;
         if (pi < 0) continue;
         nslot++;
         const uint8_t *src = k < 4 ? BT[k] : PG_GLYPH + (k - 4) * 16;
         if (memcmp(v_chr + W.pool[pi] * 16, src, 16)) memcpy(v_chr + W.pool[pi] * 16, src, 16);
         memcpy(W.pool_ours[pi], src, 16);
      }
   for (int r = 0; r < WR; r++)
      for (int c = 0; c < WC; c++) {
         int k = key[r][c], t = 0x001;                 /* 빈칸 = 게임의 온통 색 3 타일 */
         if (k != K_NONE && k <= 3 + PG_N && slot_of[k] >= 0) t = W.pool[slot_of[k]];
         uint16_t v = (uint16_t)(flip[r][c] | (pal[r][c] << 9) | t);
         for (int b = 0; b < 2; b++) {
            if (ent_rd(WBAND[b] + r, 3 + c) != v) ent_wr(WBAND[b] + r, 3 + c, v);
            W.cell_ours[b][r][c] = v;
         }
      }
   /* 팔레트 W.pal[0](보통 13) = 흰 글자 · 회색 · 검남 바탕, W.pal[1](보통 14) = 노랑 · 금색(테두리) · 검남 (12비트 0x0BGR, 색 0 은 투명이라 원래 값) */
   {
      static const uint16_t CC[2][3] = { { 0xFFF, 0x999, 0x411 }, { 0x5EF, 0x29C, 0x411 } };
      for (int k = 0; k < 2; k++) {
         uint8_t ours[8];
         memcpy(ours, W.pal_orig[k], 2);
         for (int j = 0; j < 3; j++) { ours[2 + j * 2] = (uint8_t)CC[k][j]; ours[3 + j * 2] = (uint8_t)(CC[k][j] >> 8); }
         if (memcmp(v_pal + 0x80 + W.pal[k] * 8, ours, 8)) memcpy(v_pal + 0x80 + W.pal[k] * 8, ours, 8);
         memcpy(W.pal_ours[k], ours, 8);
      }
   }
}

static void menu_close(void)
{
   window_restore();
   P.menu = M_CLOSING; P.m_f = P.f; P.or_left = 4; P.tries = 0;
}
static void menu_exit(int code)
{
   window_restore(); hud_restore();
   P.exit_req = code; P.menu = M_EXIT; P.rq = 0;
}
static int change_value(int r, int d)              /* 182: 보이는 값이 바뀌었나(효과음) */
{
   int n = r < PG_ROWS ? PG_NVAL[r] : 0;
   if (n <= 0) return 0;
   int v = row_value(r), v0 = v;
   v = (v + n + d) % n;
   switch (r) {
   case 0: PS.dummy_cpu = P.cpu_ok ? v : 0; break;              /* 싸우지 않는 조합은 CPU 를 못 고른다 */
   case 1: PS.guard = v; break;
   case 2: PS.rage[0] = v; break;
   case 3: PS.rage[1] = v; break;
   case 4:
      PS.rpos = v;
      if (v == RP_LAST) { PS.last_x[0] = P.menu_x[0]; PS.last_x[1] = P.menu_x[1]; PS.last_ok = 1; }   /* 고른 자리 = 이 값을 고른 순간(= 메뉴를 연 자리) */
      break;
   case 5: PS.swap = v; break;
   case 6: { static const int NUM[4] = { 0, 1, 0, 1 }, FDV[4] = { 0, 1, 1, 0 }; PS.num = NUM[v]; PS.fd = FDV[v]; } break;
   default: break;
   }
   return row_value(r) != v0;
}
static void menu_input(int input, int edge)
{
   /* ↑↓←→ 는 쥐고 있으면 18프레임 뒤부터 5프레임마다 다시. 앉아 막기처럼 방향을 쥔 채 Option 으로 연 메뉴에선 그 방향을 뗄 때까지 안 친다
      (안 그러면 열자마자 줄이 내려가며 설정이 바뀌었다 — 리뷰) */
   P.dir_lock &= input;
   int dirs = input & P_DIRS & ~P.dir_lock, go = edge & P_DIRS & ~P.dir_lock;
   if (dirs && dirs == P.rep_bit) { if (P.f - P.rep_f >= 18 && ((P.f - P.rep_f - 18) % 5) == 0) go |= dirs; }
   else { P.rep_bit = dirs; P.rep_f = P.f; }
   int moved = 0, changed = 0;
   if (go & IN_U) { P.row = (P.row + PG_ROWS - 1) % PG_ROWS; moved = 1; }
   if (go & IN_D) { P.row = (P.row + 1) % PG_ROWS; moved = 1; }
   if (go & IN_L) changed |= change_value(P.row, -1);
   if (go & IN_R) changed |= change_value(P.row, +1);
   if (edge & IN_A) {
      if (PG_NVAL[P.row]) changed |= change_value(P.row, +1);
      else if (P.row == 7) { psfx(UISFX_SELECT); menu_close(); request_reset(PS.rpos); return; }   /* 지금 리셋 — 멈춘 동안엔 동작을 끊을 수 없어 닫고 중립에서 */
      else if (P.row == 8) { psfx(UISFX_SELECT); menu_exit(2); return; }                          /* 상대 고르기(예전 «다시 고르기») */
      else if (P.row == 9) { psfx(UISFX_BACK); menu_exit(1); return; }                            /* 타이틀로(그만하기 — 덧판 «그만»처럼 뒤로) */
   }
   if (edge & (IN_B | IN_OPT)) { psfx(UISFX_BACK); menu_close(); return; }
   if (changed) psfx(UISFX_SELECT);                  /* 182: 같은 프레임에 옮김과 값 바꿈이 겹치면 결정(센 쪽) */
   else if (moved) psfx(UISFX_CURSOR);
}

static void activate(const uint8_t *ram, int dummy, int dsty)
{
   memset(&P, 0, sizeof P);
   memset(&W, 0, sizeof W);
   P.active = 1; P.dummy = dummy; P.dsty = dsty; P.cpu_ok = cpu_ok(dummy, dsty);
   fd_reset(1);
   P.objn0 = ram[R_OBJN] < 4 ? 4 : ram[R_OBJN];
   for (int p = 0; p < 2; p++) { P.prev_hp[p] = ram[PB(p) + O_HP]; P.prev_combo[p] = ram[PB(p) + O_COMBO]; }
   P.shown = -1; P.shown_side = -1; P.cpu_set = -1; P.rng = 0x2545F491u;
   P.prev_in = P_ALL;                               /* 들어올 때 쥐고 있던 버튼은 새로 누른 것으로 치지 않는다 */
   W.pal[0] = 13; W.pal[1] = 14; W.fx_orig[0] = W.fx_orig[1] = -1;
   for (int p = 0; p < 2; p++) P.prev_x[p] = rd16(ram, PB(p) + O_X);
}

/* ── 176 보기 — 치트 «프레임 표시»·«대미지 미터»: 연습 대전의 두 표시를 일반 판·리그 판 경기에 그대로 ─────────────
   새 표시 코드는 없다: 연습이 쓰는 fd_tick(미터 자료) · dmg_count(셈) · dig_draw(HUD 숫자 칸 줄 3 칸 8~10)를 같은 함수로 부르고 상태만 AV 칸.
   작업 램은 한 바이트도 안 쓴다(미터·셈은 읽기, 숫자는 화면 램 세 칸 — 연습과 같은 칸). 입력도 막거나 더하지 않는다.
   켜짐: 1인 대전(06)·무한 대전(0C) 전투측(MODE 241) — 일반 판·리그 판(랭크·게임 속 리그, 보기는 «치트» 기록·정산 면제와 무관)·연습 흐름 밖.
   연습 흐름(허브 연습 종류 — 상대 고르기~연습 경기)에선 꺼진다: 연습 자기 «표시» 설정만 따른다(미터 뷰·숫자 칸이 하나라 겹칠 일 없음).
   라운드: 새 라운드 첫 조작 프레임에 미터·숫자를 지운다(기술 기억은 남김). KO·승리 포즈 동안은 마지막 값이 남는다.
   prev_hp 는 cheat_tick 뒤 practice_view_post 가 — 무한 체력·한 방 치트가 쓴 체력이 대미지로 세지지 않게(연습이 자기 바닥·회복 뒤에 잡는 것과 같은 뜻).
   disp(보이는 숫자): 셈(shown)이 0 보다 클 때만 따라간다 — 잡기·칼 맞물림은 콤보 수가 체력보다 먼저 올라 셈이 0 으로 새로 시작하는데,
   그때 «0» 이 HUD 에 남아 지난 콤보 대미지를 지우지 않게(일반·리그 경기는 CPU 가 잡기를 자주 쓴다). 연습(P 칸)은 그대로 */
static struct {
   int active, bits, why, fd_now, prev_live, rounds, seen;
   unsigned f;
   int prev_hp[2], prev_combo[2], rec_open[2], dmg_cur[2], shown_side, shown, disp; unsigned dmg_f[2];
   int dig_state; uint16_t dig_orig[3], dig_ours[3];
} AV = { .shown = -1, .shown_side = -1, .disp = -1 };

static void view_reset_dmg(const uint8_t *ram)
{
   for (int p = 0; p < 2; p++) {
      AV.prev_hp[p] = ram[PB(p) + O_HP]; AV.prev_combo[p] = ram[PB(p) + O_COMBO];
      AV.rec_open[p] = 0; AV.dmg_cur[p] = 0; AV.dmg_f[p] = 0;
   }
   AV.shown = -1; AV.shown_side = -1; AV.disp = -1;
}
static void view_release(void)                 /* 꺼짐·전투 화면 아님·연습 흐름 — 우리 숫자 칸 되돌림 · 미터 끔 */
{
   dig_restore(&AV.dig_state, AV.dig_orig, AV.dig_ours);
   AV.active = 0; AV.fd_now = 0; AV.seen = 0;
}
/* own = league_session_owner()(0 그 밖 · 1 리그 · 2 연습 흐름) · bits = cheat_view_bits() */
static void view_tick(uint8_t *ram, int bits, int own)
{
   int prog = ram[R_PROG], fight = ram[R_MODE] == 241 && (prog == 0x06 || prog == 0x0C);   /* 데모(03)·통신 대전 아님 — cheat.c fight_tick 의 «ours» 와 같은 판단 */
   AV.bits = bits;
   AV.why = !bits ? 0 : own == 2 ? 3 : !fight ? 4 : 0;
   if (!bits || own == 2 || !fight) { if (AV.active) view_release(); return; }
   if (!AV.active) { AV.active = 1; AV.prev_live = 0; AV.seen = 0; fd_reset(1); view_reset_dmg(ram); }
   AV.f++;
   int lv = live(ram), paused = ram[R_PAUSE] == 1;
   if (lv && !AV.prev_live) { fd_reset(1); view_reset_dmg(ram); AV.rounds++; AV.seen = 1; }   /* 새 라운드 — 지난 라운드 미터·숫자 지움 */
   AV.prev_live = lv;
   if (lv && !paused) fd_tick(ram);                 /* practice_tick 의 연습 경기와 같은 조건 */
   if (!paused) dmg_count(ram, AV.f, AV.prev_hp, AV.prev_combo, AV.rec_open, AV.dmg_cur, AV.dmg_f, &AV.shown_side, &AV.shown);
   /* ↑ 셈은 전투측 내내(라운드 진행이 끝나는 KO 프레임의 마지막 한 대까지) — 라운드 밖엔 체력이 줄지 않는다(새 라운드 채움은 늘어남) */
   if (AV.shown_side >= 0 && AV.dmg_cur[AV.shown_side] > 0) AV.disp = AV.shown;   /* 체력이 준 셈만 — 새 콤보의 «0» 은 안 보임(지난 숫자 그대로) */
   dig_draw((bits & 2) && AV.disp >= 0 && hud_layout(), AV.disp, &AV.dig_state, AV.dig_orig, AV.dig_ours);
   AV.fd_now = (bits & 1) && AV.seen;
}

void practice_tick(uint8_t *ram, size_t n, int input)
{
   int dummy = -1, dsty = 0;
   if (!ram || n < 0x4000) return;
   int vbits = cheat_view_bits(), own = vbits ? league_session_owner() : 0;   /* 176: 잠금 밖에서(C_mu·L_mu 단독 — 겹쳐 잡지 않음) */
   int match = league_practice_match(&dummy, &dsty);
   pthread_mutex_lock(&P_mu);
   g_ram = ram;
   if (!match || dummy < 0 || dummy > 14) {
      if (P.active) { P.active = 0; W.open = 0; }   /* 게임 쪽에서 경기가 끝남 — 화면이 이미 바뀌었으니 되돌리지 않는다 */
      practice_in_block = 0; practice_in_or = 0; practice_opt_mask = 0; practice_menu_up = 0;
      view_tick(ram, vbits, own);                   /* 176 보기(치트) */
      pthread_mutex_unlock(&P_mu);
      return;
   }
   if (AV.active) view_release();                   /* 176: 연습 경기 — 연습 자기 표시만(보통은 연습 흐름에서 이미 꺼져 있다) */
   if (!P.active) activate(ram, dummy, dsty);
   P.f++;
   int lv = live(ram), paused = ram[R_PAUSE] == 1;
   int edge = input & ~P.prev_in;
   int block = 0, orr = 0;
   P.prev_in = input;
   P.holds &= input;                                /* 뗀 버튼은 다시 게임에 */

   switch (P.menu) {
   case M_NONE:
      if (lv && !paused) {
         block |= IN_OPT;                            /* 경기 중 Option 은 게임에 안 준다(탭 = 메뉴, 조합 = 리셋) */
         if (edge & IN_OPT) { P.opt_down = 1; P.opt_combo = 0; }   /* 새로 누른 Option 만 — 메뉴를 Option 으로 닫고 쥐고 있던 것은 탭으로 안 친다 */
         if (P.opt_down && (input & IN_OPT)) {
            block |= P_GAME;
            int d = edge & P_DIRS;
            if (d) {
               int pos = (d & IN_U) ? RP_CENTER : (d & IN_L) ? RP_LEFT : (d & IN_R) ? RP_RIGHT : PS.rpos;   /* ↓ = 메뉴의 리셋 위치 */
               PS.rpos = pos;                        /* ←↑→ 는 리셋 위치도 그걸로(그래서 ↓ 가 같은 자리를 되풀이) */
               request_reset(pos);
               P.opt_combo = 1;
            }
            if (edge & (IN_A | IN_B | P_FOLD | IN_SEL)) P.opt_combo = 1;   /* 2단계: Option+A 녹화 · Option+B 재생 자리 */
            P.holds |= edge & (P_DIRS | IN_A | IN_B | P_FOLD | IN_SEL);   /* 누르는 동안 새로 누른 버튼은 Option 을 뗀 뒤에도 뗄 때까지 안 준다 */
         } else if (P.opt_down) {
            P.opt_down = 0;
            if (!P.opt_combo) { P.menu = M_OPENING; P.m_f = P.f; P.or_left = 4; P.pause_n = 0; block |= P_ALL; }
         }
      } else P.opt_down = 0;                         /* 소개·라운드 콜 동안 Option 은 게임에 그대로 */
      break;
   case M_OPENING:
      block |= P_ALL;
      if (paused && ++P.pause_n >= 4) {           /* 게임이 «PAUSE» 를 다 그린 뒤(멈춘 다음 틱)에 창을 얹는다 */
         P.menu = M_OPEN; P.opens++; P.or_left = 0; P.dir_lock = input & P_DIRS; P.rep_bit = 0; P.rep_f = P.f;
         P.menu_x[0] = rd16(ram, PB(0) + O_X); P.menu_x[1] = rd16(ram, PB(1) + O_X);
         if (v_scr) build_candidates();
         window_draw();
      } else if (!paused && (P.f - P.m_f > 24 || !lv)) P.menu = M_NONE;   /* 게임이 멈추지 않았다 — 그만 */
      break;
   case M_OPEN:
      block |= P_ALL;
      if (!paused) { window_restore(); P.menu = M_NONE; break; }   /* 다른 길로 풀렸다 */
      menu_input(input, edge);
      if (P.menu == M_OPEN) window_draw();
      break;
   case M_CLOSING:
      block |= P_ALL;
      if (!paused) { P.menu = M_NONE; P.or_left = 0; P.holds |= input & (P_DIRS | IN_A | IN_B | P_FOLD | IN_SEL | IN_OPT); }
      else if (P.f - P.m_f > 30) {                    /* 안 풀렸다 — 한 번 더(세 번까지) */
         if (P.tries < 3) { P.tries++; P.m_f = P.f; P.or_left = 4; } else P.menu = M_NONE;
      }
      break;
   default:                                          /* M_EXIT — native.c 가 리셋할 때까지 */
      block |= P_ALL;
      break;
   }
   if (P.or_left > 0 && (P.menu == M_OPENING || P.menu == M_CLOSING)) { orr |= IN_OPT; P.or_left--; }

   if (lv && !paused && P.menu != M_EXIT) { fd_tick(ram); game_writes(ram, input); }   /* 프레임 데이터는 우리가 램을 고치기 전에 읽는다 */
   if (P.menu != M_EXIT) hud_update();
   block |= P.holds;
   practice_in_block = block; practice_in_or = orr;
   /* 다음 프레임 입력 콜백: Option 을 이번에 막 눌렀다면 같은 프레임의 방향·A·B 도 게임에 안 준다(Option+↑ 가 한 프레임 점프로 새지 않게).
      practice_in_block 은 Option 을 본 다음 프레임부터라 한 프레임 늦다 — 리뷰 */
   practice_opt_mask = (P.menu == M_NONE && lv && !paused) ? P_GAME : 0;
   practice_menu_up = P.menu == M_OPENING || P.menu == M_OPEN || P.menu == M_CLOSING;
   pthread_mutex_unlock(&P_mu);
}

void practice_view_post(const uint8_t *ram)
{
   if (!ram) return;
   pthread_mutex_lock(&P_mu);
   if (AV.active) for (int p = 0; p < 2; p++) AV.prev_hp[p] = ram[PB(p) + O_HP];
   pthread_mutex_unlock(&P_mu);
}

void practice_view_status(int v[PRACTICE_VSTAT])
{
   pthread_mutex_lock(&P_mu);
   v[0] = AV.bits; v[1] = AV.active; v[2] = AV.fd_now; v[3] = AV.dig_state; v[4] = AV.why; v[5] = AV.active ? AV.disp : -1;
   v[6] = AV.rounds; v[7] = (int)AV.f;
   pthread_mutex_unlock(&P_mu);
}

int practice_take_exit(void)
{
   pthread_mutex_lock(&P_mu);
   int e = P.exit_req; P.exit_req = 0;
   pthread_mutex_unlock(&P_mu);
   return e;
}

static int sv_win;                 /* 상태 저장 직전에 연습 메뉴 창이 열려 있었나 — practice_after_save 가 바로 다시 그린다 */
void practice_forget(void)
{
   pthread_mutex_lock(&P_mu);
   P.active = 0; P.menu = M_NONE; P.exit_req = 0; W.open = 0; W.npool = 0; W.fx_orig[0] = W.fx_orig[1] = -1;
   memset(&AV, 0, sizeof AV); AV.shown = AV.shown_side = AV.disp = -1;   /* 176 보기도 — 화면 램이 통째로 바뀌었으니 되돌리지 않고 잊는다 */
   fd_reset(1); sv_win = 0;
   practice_in_block = 0; practice_in_or = 0; practice_opt_mask = 0; practice_menu_up = 0;
   pthread_mutex_unlock(&P_mu);
}

void practice_before_save(void)
{
   pthread_mutex_lock(&P_mu);
   sv_win = P.active && W.open;
   if (P.active) { window_restore(); hud_restore(); }
   if (AV.active) dig_restore(&AV.dig_state, AV.dig_orig, AV.dig_ours);   /* 176 보기 숫자도 */
   pthread_mutex_unlock(&P_mu);
}

/* 상태 저장(c_serialize) 바로 뒤 — before_save 가 되돌린 창·숫자·«2P» 를 그 자리에서 다시 그린다.
   view_tick·hud_update·window_draw 는 c_run 뒤에 돌아서, 여기서 안 그리면 저장 다음 화면 한 장(120Hz 프레임 생성이면 두 vsync)에
   HUD 대미지 숫자·«2P»·메뉴 창이 빠졌다가 그다음 장에 돌아온다(빠른 저장마다 깜빡) — look_after_save 와 같은 뜻.
   그리는 조건·값은 그 틱 함수들과 같다(저장 사이엔 에뮬레이션이 없어 램·화면이 저장 직전과 같다) */
void practice_after_save(void)
{
   pthread_mutex_lock(&P_mu);
   if (P.active && P.menu != M_EXIT) {
      if (sv_win && P.menu == M_OPEN) window_draw();
      hud_update();
   }
   if (AV.active) dig_draw((AV.bits & 2) && AV.disp >= 0 && hud_layout(), AV.disp, &AV.dig_state, AV.dig_orig, AV.dig_ours);
   sv_win = 0;
   pthread_mutex_unlock(&P_mu);
}

void practice_status(int v[PRACTICE_NSTAT])
{
   pthread_mutex_lock(&P_mu);
   v[0] = P.active; v[1] = P.menu == M_EXIT ? 0 : P.menu; v[2] = P.row;
   v[3] = PS.dummy_cpu && (P.cpu_ok || !P.active); v[4] = PS.guard; v[5] = PS.rage[0]; v[6] = PS.rage[1];
   v[7] = PS.rpos; v[8] = PS.swap; v[9] = PS.num;
   v[10] = P.shown; v[11] = P.resets; v[12] = P.rq; v[13] = (int)P.last_reset_f; v[14] = P.g_on; v[15] = (int)P.f;
   v[16] = W.npool; v[17] = W.skipped; v[18] = P.exit_req; v[19] = P.objn0; v[20] = P.opens; v[21] = P.dropped;
   v[22] = P.active ? P.dummy : -1; v[23] = P.active ? P.dsty : -1;
   v[24] = PS.fd; v[25] = (int)FD.seq_id;
   pthread_mutex_unlock(&P_mu);
}

void practice_set(int key, int value)
{
   pthread_mutex_lock(&P_mu);
   switch (key) {
   case 0: PS.dummy_cpu = value ? 1 : 0; break;
   case 1: PS.guard = value >= 0 && value <= 3 ? value : 0; break;
   case 2: PS.rage[0] = value ? 1 : 0; break;
   case 3: PS.rage[1] = value ? 1 : 0; break;
   case 4: PS.rpos = value >= 0 && value <= 3 ? value : 0; break;
   case 5: PS.swap = value ? 1 : 0; break;
   case 6: PS.num = value ? 1 : 0; break;
   case 7: if (P.active) request_reset(value >= 0 && value <= 3 ? value : PS.rpos); break;
   case 8: if (P.active && value > 0 && value <= 128) P.set_hp2 = value; break;   /* 시험용: 더미 체력 */
   case 9: PS.fd = value ? 1 : 0; break;                                          /* 프레임 미터 */
   default: break;
   }
   pthread_mutex_unlock(&P_mu);
}

/* 연습 설정(더미·가드·분노·리셋·대미지) — 상태 저장 옆에 허브와 함께 남겨, 앱을 다시 열어 이어해도 그대로 */
size_t practice_settings_get(void *buf, size_t cap)
{
   if (cap < sizeof PS) return 0;
   pthread_mutex_lock(&P_mu);
   memcpy(buf, &PS, sizeof PS);
   pthread_mutex_unlock(&P_mu);
   return sizeof PS;
}
void practice_settings_set(const void *buf, size_t n)
{
   if (!buf || (n != sizeof PS && n != offsetof(Settings, fd))) return;   /* 옛 판(프레임 미터 전) .prac 도 — fd 는 끔 */
   Settings t;
   memset(&t, 0, sizeof t);
   memcpy(&t, buf, n);
   pthread_mutex_lock(&P_mu);
   PS.dummy_cpu = t.dummy_cpu ? 1 : 0; PS.guard = t.guard >= 0 && t.guard <= 3 ? t.guard : 0;
   PS.rage[0] = t.rage[0] ? 1 : 0; PS.rage[1] = t.rage[1] ? 1 : 0;
   PS.rpos = t.rpos >= 0 && t.rpos <= 3 ? t.rpos : 0; PS.swap = t.swap ? 1 : 0; PS.num = t.num ? 1 : 0;
   PS.last_ok = t.last_ok ? 1 : 0; PS.last_x[0] = t.last_x[0]; PS.last_x[1] = t.last_x[1];   /* do_reset 이 무대 안으로 자른다 */
   PS.fd = t.fd ? 1 : 0;
   pthread_mutex_unlock(&P_mu);
}

size_t practice_state_size(void) { return sizeof(State) + sizeof(Settings) + sizeof FD; }
void practice_state_get(void *buf)
{
   pthread_mutex_lock(&P_mu);
   memcpy(buf, &P, sizeof P); memcpy((uint8_t *)buf + sizeof P, &PS, sizeof PS); memcpy((uint8_t *)buf + sizeof P + sizeof PS, &FD, sizeof FD);
   pthread_mutex_unlock(&P_mu);
}
void practice_state_set(const void *buf)
{
   pthread_mutex_lock(&P_mu);
   memcpy(&P, buf, sizeof P); memcpy(&PS, (const uint8_t *)buf + sizeof P, sizeof PS); memcpy(&FD, (const uint8_t *)buf + sizeof P + sizeof PS, sizeof FD);
   W.open = 0; W.npool = 0; W.fx_orig[0] = W.fx_orig[1] = -1;
   pthread_mutex_unlock(&P_mu);
}

/* 프레임 미터 읽기 — cells[2 × FD_VIEW](P1 줄 · P2 줄, FK_* | FK_F_* · 0xFF 빈칸), sum[PRACTICE_FD_NSUM](practice.h). 돌려줌: 바뀔 때마다 오르는 판 번호 */
uint32_t practice_fd_get(uint8_t *cells, int *sum)
{
   pthread_mutex_lock(&P_mu);
   if (cells) memcpy(cells, FD.cell, sizeof FD.cell);
   if (sum) {
      for (int i = 0; i < PRACTICE_FD_NSUM; i++) sum[i] = 0;
      sum[0] = (PS.fd && P.active) || AV.fd_now; sum[1] = (int)FD.seq_id; sum[2] = FD.len; sum[3] = FD.seq_on; sum[4] = FD.frozen_n; sum[5] = FD.gap_n; sum[6] = (int)FD.ver; sum[7] = FD.focus;
      for (int p = 0; p < 2; p++) {
         int *o = sum + 8 + 14 * p; const FdSum *s = &FD.sum[p];
         FdMem *m = s->id ? fd_mem(s->kc, s->ka, s->ks, 0) : NULL;
         o[0] = s->id; o[1] = s->startup; o[2] = s->active; o[3] = s->recovery; o[4] = s->total; o[5] = s->contact; o[6] = s->pstart; o[7] = s->plen;
         o[8] = m ? m->active : -1; o[9] = m ? m->recovery : -1;
         for (int k = 0; k < 4; k++) o[10 + k] = m ? m->adv[k] : FD_NONE;
      }
      sum[36] = FD.adv; sum[37] = FD.adv_kind; sum[38] = FD.adv_att; sum[39] = FD.adv_id;
   }
   uint32_t v = FD.ver;
   pthread_mutex_unlock(&P_mu);
   return v;
}

#define JNI(ret, name) JNIEXPORT ret JNICALL Java_com_dudu_pocketcore_Emu_##name

/* 연습 대전 허브 켜기(일반 세션 · 확인한 롬 판만 — Java 가 가린다). autostart = 메뉴 «연습 대전» → 캐릭터 선택(내 캐릭터 먼저)까지 대신 누름 */
JNI(void, nativePracticeHub)(JNIEnv *env, jclass cls, jboolean on, jboolean autostart)
{ (void)env; (void)cls; practice_forget(); league_practice_hub(on ? 1 : 0, autostart ? 1 : 0); }

JNI(jintArray, nativePracticeStatus)(JNIEnv *env, jclass cls)
{
   (void)cls;
   int v[PRACTICE_NSTAT];
   jint jv[PRACTICE_NSTAT];
   practice_status(v);
   for (int i = 0; i < PRACTICE_NSTAT; i++) jv[i] = v[i];
   jintArray a = (*env)->NewIntArray(env, PRACTICE_NSTAT);
   if (a) (*env)->SetIntArrayRegion(env, a, 0, PRACTICE_NSTAT, jv);
   return a;
}

/* 176 보기 상태(practice.h PRACTICE_VSTAT) — Java viewPoll(150ms) */
JNI(jintArray, nativeViewStatus)(JNIEnv *env, jclass cls)
{
   (void)cls;
   int v[PRACTICE_VSTAT];
   jint jv[PRACTICE_VSTAT];
   practice_view_status(v);
   for (int i = 0; i < PRACTICE_VSTAT; i++) jv[i] = v[i];
   jintArray a = (*env)->NewIntArray(env, PRACTICE_VSTAT);
   if (a) (*env)->SetIntArrayRegion(env, a, 0, PRACTICE_VSTAT, jv);
   return a;
}

/* 프레임 미터 — Java 가 33ms 마다(연습 경기 · 미터 켬일 때만) 부른다. 배열은 Java 가 한 번 만들어 계속 쓴다(할당 없음). 돌려줌: 판 번호(바뀌었을 때만 다시 그림) */
JNI(jint, nativePracticeFrames)(JNIEnv *env, jclass cls, jbyteArray cells, jintArray sum)
{
   (void)cls;
   uint8_t c[2 * FD_VIEW]; int s[PRACTICE_FD_NSUM]; jint js[PRACTICE_FD_NSUM];
   uint32_t v = practice_fd_get(c, s);
   if (cells && (*env)->GetArrayLength(env, cells) >= 2 * FD_VIEW) (*env)->SetByteArrayRegion(env, cells, 0, 2 * FD_VIEW, (const jbyte *)c);
   if (sum && (*env)->GetArrayLength(env, sum) >= PRACTICE_FD_NSUM) {
      for (int i = 0; i < PRACTICE_FD_NSUM; i++) js[i] = s[i];
      (*env)->SetIntArrayRegion(env, sum, 0, PRACTICE_FD_NSUM, js);
   }
   return (jint)v;
}
