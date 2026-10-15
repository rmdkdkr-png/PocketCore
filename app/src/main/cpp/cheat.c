/* 치트 — 사무쇼2(NGPC) 일반 세션용 램 치트 (설계: next/cheat/design.md · 시제품 proto/cheat.c). 롬·세이브 파일은 건드리지 않는다.
   매 진짜 프레임(retro_run 뒤, 코어 잠금 안, league_tick_hub · practice_tick 다음)에 cheat_tick 이 작업 램을 고친다.
   치트를 가장 뒤에 두는 까닭: 리그·연습이 같은 바이트를 쓰는 세션이면 치트는 «막혔을 때 아예 안 쓴다»로 정리되고,
   막히지 않았을 땐 리그·연습이 그 바이트를 안 쓰는 상황이다.

     막기   랭크전·게임 속 리그(허브 종류 0) 세션이면 아무것도 안 쓴다(기록이 깨끗해야 한다 — Java 도 잠그지만 C 가 허브 종류를 보고 한 번 더).
            연습 대전 흐름(허브 종류 1 의 상대 고르기 → 내 캐릭터 → 연습 경기)이면 경기 치트(시간·체력·분노·상대·세기·공격력)는 쉰다 —
            연습이 시간·체력 바닥·분노·더미 CPU 비트·CPU 배율을 매 프레임 맡고, 경기 전에 쓴 공격 배율(0x57D8)은 연습이 안 고쳐 연습 경기까지 샌다.
            올카드·유가는 연습에서도 먹는다(연습 상대 고르기는 허브가 A 를 받아 게임이 확정하지 않으므로 유가 판정에 안 걸린다).
     화면   0x57C6 = 메뉴 프로그램(03 타이틀 · 06 1인 대전 · 0C 무한 대전 · 12 카드 모음집 · 0F 게임 설정, 고르는 프레임에 세이브를 다시 읽음)
            0x5726 = 1인/무한 대전 단계(06 캐릭터 고르기 · 08 검질 고르기 · 0A 카드 화면 · 0E/0C 그 뒤 — 이어하기 화면도 0C 라 SCR 로는 못 가른다)
     올카드 이번만: 고르기 창(06·08·0A)과 카드 모음집(12) 동안만 소유 개수 120칸 = 8, 창을 떠나면 들어올 때 보관한 값으로 되돌린다
            → 게임이 세이브(게임오버·이어하기 끝·스토리 클리어·무한 대전 끝·설정 저장)를 쓸 때 램은 늘 원래 값(실측: .flash 카드 원래 값, 롬 4판).
            고른 카드는 되돌린 뒤에도 싸움에 먹는다(카드 화면에서 0x57DC·배율이 이미 정해짐).
            세이브에 남기기: 늘 8(부팅 뒤) — 게임이 다음에 세이브를 쓸 때 영구로 남는다. 모드를 고를 때 다시 읽은 값이 모두 8 이면 «세이브에 들어감».
            중간에 끄면 이번 판에서 8 로 덮은 램을 그 전 값(게임이 마지막에 세이브에서 읽은 값)으로 되돌린다 — 이미 게임이 저장했으면 세이브엔 남아 있다
            (되돌리기는 실행 창의 «세이브 되돌리기» — Java 가 켤 때 .flash 를 따로 보관).
     유가   캐릭터 고르기에서 가운데(쿠로코 · 커서 0x4E3C = 60) 칸으로 확정하면(0x5726 06→08), 검질 확정 뒤 0x57D5/0x57D6 = 14/검질.
            (15 간다라는 첫 라운드 끝에서 멈춤 — 안 씀) */

#include <jni.h>
#include <pthread.h>
#include <string.h>
#include "cheat.h"
#include "league.h"

#define RAM_BASE 0x4000
#define A(x) ((x) - RAM_BASE)
#define R_MODE   A(0x40A7)   /* 240 메뉴·연출 · 241 전투측 */
#define R_SCR    A(0x41C0)
#define R_FIGHT  A(0x411A)   /* 1 = 라운드 진행 */
#define R_RES    A(0x5824)   /* 0 = 라운드 중 */
#define R_PROG   A(0x57C6)   /* 메뉴 프로그램 */
#define R_SUB    A(0x5726)   /* 1인/무한 대전 단계 */
#define R_HOVER  A(0x4E3C)   /* 캐릭터 고르기 커서 = id × 4, 가운데 무작위 칸 = 60 */
#define R_P1CH   A(0x57D5)   /* P1 캐릭터(검질 확정 때 씀, 그 전 255) — 전투 로드 때 P1 블록을 만든다 */
#define R_P1STY  A(0x57D6)
#define R_OWN    A(0x63BA)   /* 카드 소유 개수 120칸(칸 = 8×캐릭터 + 4×검질 + k, 세이브 블록 0x635A~0x6459 안 — 체크섬 없음) */
#define R_TIMER  A(0x582F)   /* u16, 0xFFFF = ∞ */
#define R_NEXTT  A(0x5812)   /* 이번 경기 시간(라운드마다 여기서 타이머를 만든다), 0x80 = ∞ */
#define R_OPTT   A(0x636C)   /* 옵션 시간(1인 대전은 상대 뽑을 때 0x5812 로 복사) */
#define R_P1ATK  A(0x57D8)   /* P1 공격 배율 u16(카드가 정함, 0x100 = 1.0) — 싸움 중에 써도 바로 먹고 곱해진다 */
#define R_CPUATK A(0x57E2)   /* CPU 공격 배율 · 받는 피해 배율 u16 */
#define R_CPUDEF A(0x57E4)
#define R_CPULV  A(0x5814)   /* CPU 시작 단계 · 상한 */
#define R_CPUCAP A(0x5815)
#define R_P2LV   A(0x5CF5)   /* P2 실시간 단계 */
#define PB(p)    (A(0x5980) + (p) * 0x200)
#define O_FLAGS  0x4A        /* bit2 진짜 MAX */
#define O_CPU    0x4B        /* bit0 CPU 가 조종 */
#define O_CTRL   0x4E        /* 0 = 움직일 수 있다 */
#define O_Y      0x5A        /* u16, 땅 128 */
#define O_HP     0xC6
#define O_RAGE   0xC7
#define O_MAXT   0xC8        /* u16 */
#define O_DHP    0xCA
#define O_DRAGE  0xCB
#define O_SECRET 0xD5        /* bit7 = 이번 판 비오의를 씀(분노 막힘) */

static pthread_mutex_t C_mu = PTHREAD_MUTEX_INITIALIZER;
static int CV[CH_N];                 /* 사용자가 고른 값(Java 가 마스터 끔이면 모두 0 으로 넣는다) */
static int locked;
static struct {
   int win; uint8_t snap[120];       /* 올카드(이번만) 창 안 · 들어올 때 보관한 소유 개수 */
   int perm_on, perm_ok;             /* 세이브에 남기기로 8 을 쓰는 중 · perm_snap 이 지금 램에 맞는 값인가 */
   uint8_t perm_snap[120];           /* 그 전 값(게임이 마지막에 세이브에서 읽은 값) — 세이브에 남기기를 끄면 되돌린다 */
   int perm_saved;                   /* 모드 고를 때 다시 읽은 값이 모두 8 이었다 */
   int prev_prog, prev_sub, hover;
   int yuga_pick;                    /* 이번 고르기에서 가운데 칸을 확정 */
   int prev_hp[2], hp_fixes, last_ko;
   int max_keep[2];
   int cpu_off;                      /* 우리가 CPU 비트를 끈 채 */
   int time_on;                      /* 우리가 0x5812 를 ∞ 로 바꿔 둠 */
   int atk_base, atk_last;           /* P1 공격 배율 기준(게임이 정한 값) · 마지막으로 쓴 값(−1 없음) */
   int diff_prev;
   int used, why;
   unsigned f;
} S = { .atk_last = -1 };

static int rd16(const uint8_t *r, int o) { return r[o] | (r[o + 1] << 8); }
static void wr16(uint8_t *r, int o, int v) { r[o] = (uint8_t)v; r[o + 1] = (uint8_t)(v >> 8); }

/* CPU 세기 — 게임 표에서 그대로 가져온 값만(리그 0단계 «CPU 난도»: 하·중·상 첫 장, 아주 쉬움 = 하에 단계 0, 최강 = 상 9장).
   옵션 0x636A 는 세이브 블록 안이라 게임오버·설정 저장 때 남으므로 안 건드리고, 게임이 상대 뽑을 때 정하는 실효 값을 덮는다.
   체감 차이는 대부분 배율에서 난다(단계만으론 CPU 피해량이 표본마다 들쭉날쭉 — 설계 2장 «CPU 세기 실측») */
static const uint16_t DIFF_T[6][4] = {           /* 시작 단계, 상한, CPU 공격 배율, CPU 받는 배율 */
   { 0, 0, 0, 0 }, { 0, 0, 0xC0, 0x140 }, { 1, 3, 0xC0, 0x140 }, { 4, 6, 0xD9, 0x126 }, { 7, 9, 0xF3, 0x10C }, { 10, 15, 0x119, 0xE6 } };
static const int POW_K[4] = { 0x100, 0x180, 0x200, 0x300 };

/* 진짜 MAX — 연습 F6 과 같은 코드(분노 0x30 만 쓰는 가짜 MAX 는 타이머가 안 돌고 MAX 기술이 안 나간다 — 리그 0단계) */
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

static void cards_restore(uint8_t *ram)
{
   if (S.win && ram) memcpy(ram + R_OWN, S.snap, 120);
   S.win = 0;
}

static void cards_tick(uint8_t *ram, int mode, int prog, int sub)
{
   /* 게임이 방금 세이브 칸을 램에 통째로 읽었다 — 부팅(0→03)과 모드 고르기(03→X) 바로 그 프레임(설계 2장 «세이브 다시 읽기») */
   int reread = prog != S.prev_prog && (S.prev_prog == 0x03 || S.prev_prog == 0);
   if (reread) {
      int all = 1;
      for (int i = 0; i < 120; i++) if (ram[R_OWN + i] < 8) { all = 0; break; }
      S.perm_saved = all;
   }
   if (CV[CH_CARDS] == 2) {                          /* 세이브에 남기기 — 부팅 뒤 늘 8 */
      if (S.win) { memcpy(S.perm_snap, S.snap, 120); S.perm_ok = 1; S.win = 0; }   /* 이번만 창 안에서 바꿨다 — 램은 이미 8, 원래 값은 창 보관본 */
      if (prog != 0) {
         if (!S.perm_ok || reread) { memcpy(S.perm_snap, ram + R_OWN, 120); S.perm_ok = 1; }
         memset(ram + R_OWN, 8, 120);
      }
      S.perm_on = 1;
      return;
   }
   if (S.perm_on) {                                  /* 세이브에 남기기를 껐다 — 이번 판에서 8 로 덮은 램을 그 전 값으로 */
      if (S.perm_ok) memcpy(ram + R_OWN, S.perm_snap, 120);
      S.perm_on = 0; S.perm_ok = 0;
   }
   int inwin = CV[CH_CARDS] == 1 && mode == 240
            && (((prog == 0x06 || prog == 0x0C) && (sub == 0x06 || sub == 0x08 || sub == 0x0A)) || prog == 0x12);
   if (inwin) {
      if (!S.win) { memcpy(S.snap, ram + R_OWN, 120); S.win = 1; }
      memset(ram + R_OWN, 8, 120);
   } else if (S.win) cards_restore(ram);
}

static void yuga_tick(uint8_t *ram, int mode, int prog, int sub)
{
   if (prog != 0x06 && prog != 0x0C) { S.yuga_pick = 0; return; }
   if (mode == 240 && sub == 0x06 && ram[R_SCR] == 2) S.hover = ram[R_HOVER];
   if (S.prev_sub == 0x06 && sub == 0x08) S.yuga_pick = CV[CH_YUGA] && S.hover == 60;   /* 캐릭터 확정 순간 */
   if (sub == 0x06) return;                                 /* 다시 고르는 중(이어하기 포함) — 확정 때 다시 정한다 */
   if (S.yuga_pick && CV[CH_YUGA] && ram[R_P1CH] != 255) {   /* 가운데 칸은 게임이 검질 확정 뒤 캐릭터·검질을 굴려 정한다 — 그 값을 유가로(전투 로드 전) */
      ram[R_P1CH] = 14; ram[R_P1STY] = (uint8_t)(CV[CH_YUGA] == 2);
   }
}

/* 경기 치트를 쉬게 됐다(연습 대전 흐름·잠김·리그) — 우리가 바꿔 두고 게임이 다시 안 쓰는 값만 되돌리고(공격 배율), 나머지(시간·CPU 비트·분노·
   CPU 세기)는 연습이 매 프레임 직접 쓰거나 게임이 다음 경기 로드 때 다시 정하니 «우리가 바꿔 둠» 표시만 지운다(쉬다 풀렸을 때 엉뚱한 값을 되돌리지 않게) */
static void fight_release(uint8_t *ram)
{
   if (S.atk_last >= 0) {
      if (rd16(ram, R_P1ATK) == S.atk_last) wr16(ram, R_P1ATK, S.atk_base);
      S.atk_last = -1;
   }
   S.cpu_off = 0; S.time_on = 0; S.max_keep[0] = S.max_keep[1] = 0; S.prev_hp[0] = S.prev_hp[1] = 128;
}

static void fight_tick(uint8_t *ram, int mode, int prog)
{
   int ours = prog == 0x06 || prog == 0x0C;                 /* 1인 대전 · 무한 대전(CPU 전)만 — 통신 대전·데모(03)는 아님 */
   int live = ours && mode == 241 && ram[R_FIGHT] == 1 && ram[R_RES] == 0;

   /* 시간 무한 — 라운드 중 타이머 ∞, 이번 경기 시간도 ∞(다음 라운드). 끄면 0x5812 를 옵션 값으로(무한 대전은 원래 ∞).
      지금 라운드는 ∞ 그대로 — 다음 라운드부터 원래 시간 */
   if (CV[CH_TIME] && ours) {
      ram[R_NEXTT] = 0x80; S.time_on = 1;
      if (live) { ram[R_TIMER] = 0xFF; ram[R_TIMER + 1] = 0xFF; }
   } else if (S.time_on) {
      if (ours) ram[R_NEXTT] = prog == 0x0C ? 0x80 : ram[R_OPTT];
      S.time_on = 0;
   }

   /* CPU 세기 — 게임이 상대 뽑을 때 정하는 실효 값을 매 프레임 덮는다(상대 뽑기·전투 로드 전후 모두) */
   if (CV[CH_DIFF] && ours) {
      const uint16_t *t = DIFF_T[CV[CH_DIFF]];
      ram[R_CPULV] = (uint8_t)t[0]; ram[R_CPUCAP] = (uint8_t)t[1]; wr16(ram, R_CPUATK, t[2]); wr16(ram, R_CPUDEF, t[3]);
      if (live && S.diff_prev != CV[CH_DIFF]) ram[R_P2LV] = (uint8_t)t[0];   /* 라운드 중에 바꾸면 지금 단계도 */
   }
   S.diff_prev = live ? CV[CH_DIFF] : S.diff_prev;

   /* 내 공격력 — 게임(카드)이 정한 값에 곱한다(카드 공격 상승 0x133 과 곱해짐). 램 값이 우리가 마지막에 쓴 값과 다르면 게임이 다시 정한 것(다음 경기) → 새 기준 */
   if (ours && mode == 241) {
      int cur = rd16(ram, R_P1ATK);
      if (S.atk_last < 0 || cur != S.atk_last) S.atk_base = cur;
      if (CV[CH_POW]) {
         int v = S.atk_base * POW_K[CV[CH_POW]] >> 8; if (v > 0x7FFF) v = 0x7FFF;
         wr16(ram, R_P1ATK, v); S.atk_last = v;
      } else if (S.atk_last >= 0) { wr16(ram, R_P1ATK, S.atk_base); S.atk_last = -1; }
   }

   /* 상대 가만히 — CPU 비트를 끈다(사람 갈래 · 패드 0 — 가드도 안 함). 끄면 한 번 다시 켬 */
   if (live) {
      if (CV[CH_CPU]) { ram[PB(1) + O_CPU] &= (uint8_t)~1; S.cpu_off = 1; }
      else if (S.cpu_off) { ram[PB(1) + O_CPU] |= 1; S.cpu_off = 0; }
   }

   if (!live) { S.prev_hp[0] = S.prev_hp[1] = 128; return; }

   /* 체력 — 무한: 128 밑이면 128(표시 체력도 — 가장 큰 한 방 52 · 배율 최강 ×1.1 이라도 128 에서 0 까지 못 가 «0 이 되는 프레임에 KO» 위험 없음).
      한 방: 이번 프레임에 깎였으면 0(게임이 다음 틱에 KO 로 끝낸다 — 가드 깎임에도) */
   for (int p = 0; p < 2; p++) {
      int b = PB(p), hp = ram[b + O_HP], mode_hp = CV[CH_HP1 + p];
      if (mode_hp == 1 && hp < 128) { ram[b + O_HP] = 128; ram[b + O_DHP] = 128; S.hp_fixes++; hp = 128; }
      else if (mode_hp == 2 && hp > 0 && hp < S.prev_hp[p]) { ram[b + O_HP] = 0; S.last_ko = (int)S.f; hp = 0; }
      S.prev_hp[p] = hp;
   }

   /* 분노 MAX 유지 — 진짜 MAX(비트·타이머), 타이머 < 0x1000 이면 다시 0x3000. 비오의 뒤 분노 막힘(0x5A55 bit7)도 움직일 수 있고 땅이면 푼다.
      비트가 꺼졌을 땐(MAX 기술을 씀) 움직일 수 있을 때만 다시 켠다 — 기술 도중에 켜면 연출이 꼬인다 */
   for (int p = 0; p < 2; p++) {
      int b = PB(p), act = ram[b + O_CTRL] == 0, ground = rd16(ram, b + O_Y) == 128;
      if (CV[CH_RAGE1 + p]) {
         if (!(ram[b + O_FLAGS] & 0x04)) { if (act) set_max(ram, p); }
         else if (rd16(ram, b + O_MAXT) < 0x1000) wr16(ram, b + O_MAXT, 0x3000);
         if ((ram[b + O_SECRET] & 0x80) && act && ground && !(ram[b + O_FLAGS] & 0x04)) ram[b + O_SECRET] &= (uint8_t)~0x80;
         S.max_keep[p] = 1;
      } else if (S.max_keep[p] && act && ground) {        /* 끄면 움직일 수 있을 때 MAX 를 거둔다 */
         if (ram[b + O_FLAGS] & 0x04) clear_max(ram, p);
         S.max_keep[p] = 0;
      }
   }
}

void cheat_tick(uint8_t *ram, size_t n, int input)
{
   (void)input;                                     /* 1단계 치트는 입력을 막거나 더하지 않는다(2단계 게임 속 창이 들어오면 cheat_in_block) */
   if (!ram || n < 0x4000) return;
   int own = league_session_owner();                /* 잠금 밖에서 먼저 — 자물쇠 순서는 L_mu → C_mu 만(practice_tick 과 같다) */
   pthread_mutex_lock(&C_mu);
   S.f++;
   int mode = ram[R_MODE], prog = ram[R_PROG], sub = ram[R_SUB];
   int why = locked ? 1 : own == 1 ? 2 : own == 2 ? 3 : 0;
   if (why == 1 || why == 2) {                      /* 아무것도 안 쓴다 — 혹시 남은 창이 있으면 되돌린다 */
      cards_restore(ram);
      fight_release(ram);
   } else {
      cards_tick(ram, mode, prog, sub);
      yuga_tick(ram, mode, prog, sub);
      if (why == 3) fight_release(ram);
      else {
         fight_tick(ram, mode, prog);
         if (mode == 241 && ram[R_FIGHT] == 1 && (CV[CH_TIME] | CV[CH_HP1] | CV[CH_HP2] | CV[CH_RAGE1] | CV[CH_RAGE2] | CV[CH_CPU] | CV[CH_DIFF] | CV[CH_POW])
             && (prog == 0x06 || prog == 0x0C))
            S.used = 1;
      }
   }
   S.why = why;
   S.prev_prog = prog; S.prev_sub = sub;
   pthread_mutex_unlock(&C_mu);
}

void cheat_set(int key, int value)
{
   static const int MAXV[CH_N] = { 2, 1, 2, 2, 1, 1, 1, 5, 3, 2 };
   if (key < 0 || key >= CH_N) return;
   pthread_mutex_lock(&C_mu);
   if (!locked) CV[key] = value >= 0 && value <= MAXV[key] ? value : 0;
   pthread_mutex_unlock(&C_mu);
}

void cheat_reset_all(void)
{
   pthread_mutex_lock(&C_mu);
   memset(CV, 0, sizeof CV); memset(&S, 0, sizeof S); S.atk_last = -1; locked = 0;
   pthread_mutex_unlock(&C_mu);
}

void cheat_lock(int on)
{
   pthread_mutex_lock(&C_mu);
   locked = on ? 1 : 0;
   if (locked) memset(CV, 0, sizeof CV);
   pthread_mutex_unlock(&C_mu);
}

void cheat_forget(void)
{
   pthread_mutex_lock(&C_mu);
   S.win = 0; S.perm_ok = 0; S.yuga_pick = 0; S.cpu_off = 0; S.time_on = 0; S.atk_last = -1; S.max_keep[0] = S.max_keep[1] = 0;
   S.prev_hp[0] = S.prev_hp[1] = 128; S.prev_prog = -1;
   pthread_mutex_unlock(&C_mu);
}

void cheat_before_save(uint8_t *ram)
{
   pthread_mutex_lock(&C_mu);
   if (S.win && ram) { memcpy(ram + R_OWN, S.snap, 120); S.win = 0; }   /* 다음 프레임에 창이면 다시 보관·쓰기 */
   pthread_mutex_unlock(&C_mu);
}

void cheat_status(int v[CHEAT_NSTAT])
{
   pthread_mutex_lock(&C_mu);
   int on = 0, sum = 0;
   for (int i = 0; i < CH_N; i++) on += CV[i] != 0;
   for (int i = 0; i < 120; i++) sum += S.snap[i];
   v[0] = locked; v[1] = on; v[2] = S.used; v[3] = S.win; v[4] = sum; v[5] = S.perm_saved;
   v[6] = S.yuga_pick; v[7] = S.why; v[8] = S.atk_last; v[9] = S.atk_base; v[10] = S.last_ko; v[11] = S.hp_fixes;
   v[12] = (int)S.f; v[13] = S.prev_prog; v[14] = S.prev_sub; v[15] = S.perm_on;
   pthread_mutex_unlock(&C_mu);
}

/* ── JNI (Emu.java) — league.c·practice.c 와 같은 모양 ─────────────────────────── */
#define JNI(ret, name) JNIEXPORT ret JNICALL Java_com_dudu_pocketcore_Emu_##name

JNI(void, nativeCheatSet)(JNIEnv *env, jclass cls, jint key, jint value)
{ (void)env; (void)cls; cheat_set(key, value); }

JNI(void, nativeCheatLock)(JNIEnv *env, jclass cls, jboolean on)
{ (void)env; (void)cls; cheat_lock(on ? 1 : 0); }

JNI(jintArray, nativeCheatStatus)(JNIEnv *env, jclass cls)
{
   (void)cls;
   int v[CHEAT_NSTAT];
   jint jv[CHEAT_NSTAT];
   cheat_status(v);
   for (int i = 0; i < CHEAT_NSTAT; i++) jv[i] = v[i];
   jintArray a = (*env)->NewIntArray(env, CHEAT_NSTAT);
   if (a) (*env)->SetIntArrayRegion(env, a, 0, CHEAT_NSTAT, jv);
   return a;
}
