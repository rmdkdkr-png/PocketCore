/* 치트 — 사무쇼2 (cheat.c). 일반 세션·랭크전·게임 속 리그(168 — 리그에서 먹은 판은 기록에 «치트», cheat_match_bits). 연습 대전 흐름에서는 경기 치트를 쉰다. */
#pragma once
#include <stddef.h>
#include <stdint.h>

/* 치트 번호(cheat_set key) — options.txt 키 pocketcore_ss2_cheat_<이름> 과 1:1 (Java Cheats.KEYS 순서와 같게) */
enum {
   CH_CARDS = 0,   /* 올카드   0 끔 · 1 이번만(세이브엔 안 남음) · 2 세이브에 남기기(다음 저장 때 영구) */
   CH_TIME,        /* 시간     0 보통 · 1 무한 */
   CH_HP1,         /* 내 체력  0 보통 · 1 무한 · 2 한 방(맞으면 KO) */
   CH_HP2,         /* 상대 체력 0 보통 · 1 무한 · 2 한 방 */
   CH_RAGE1,       /* 내 분노  0 보통 · 1 MAX 유지 */
   CH_RAGE2,       /* 상대 분노 0 보통 · 1 MAX 유지 */
   CH_CPU,         /* 상대     0 보통 · 1 가만히 */
   CH_DIFF,        /* CPU 세기 0 게임대로 · 1 아주 쉬움 · 2 하 · 3 중 · 4 상 · 5 최강 */
   CH_POW,         /* 내 공격력 0 보통 · 1 1.5배 · 2 2배 · 3 3배 */
   CH_YUGA,        /* 유가     0 끔 · 1 수라 · 2 나찰 — 캐릭터 고르기 가운데(쿠로코) 칸 = 유가 */
   CH_FD,          /* 176 보기: 프레임 표시 0 끔 · 1 켬 — 연습 대전과 같은 프레임 미터(게임 화면 밖 앱 뷰 · practice.c) */
   CH_DMG,         /* 176 보기: 대미지 미터 0 끔 · 1 켬 — 연습 대전과 같은 HUD 숫자(practice.c). 보기 둘은 게임 램을 안 써서 «치트» 기록·정산 면제가 없다 */
   CH_N
};

/* 진짜 프레임 한 번(retro_run) 뒤, 코어 잠금 안에서 practice_tick 다음에 부른다(리그·연습이 먼저 쓰고 치트가 가장 뒤) */
void cheat_tick(uint8_t *ram, size_t n, int input);
/* 값 바꾸기(UI 스레드 — 다음 진짜 프레임부터). 잠겨 있으면 무시, 범위 밖이면 0 */
void cheat_set(int key, int value);
/* 모두 끔 + 잠금 풂 + 상태 비움(코어 올리기·내리기 — Java 가 일반 세션이면 다시 넣는다) */
void cheat_reset_all(void);
/* 잠금 — 켜면 값을 0 으로 비우고 cheat_set 을 받지 않으며 tick 은 아무것도 안 쓴다(168: 시험용 — Java 는 리그에서도 안 부른다) */
void cheat_lock(int on);
/* 168: 마지막으로 비운 뒤 실제로 쓴 치트(1 << CH_*) — 경기 치트는 라운드 중(live) 쓴 것만 · 올카드는 8 이 아닌 칸을 바꿨을 때 · 유가는 P1 을 14 로 바꿨을 때.
   clear 면 0 으로. league.c arm_locked(L_mu 안 — L_mu → C_mu 순서)가 무장마다 비운다 */
unsigned cheat_match_bits(int clear);
/* 리셋·상태 불러오기 뒤 — 램이 통째로 바뀌었으니 보관한 카드 개수는 되돌리지 않고 버린다 */
void cheat_forget(void);
/* 상태 저장 직전(코어 잠금 안) — 올카드(이번만)로 바꿔 둔 소유 개수를 원래대로(저장된 상태에 8 이 안 실리게 — 다음 프레임에 다시 쓴다),
   공격 배율·상대 CPU 비트·다음 라운드 시간도 원래 값으로(불러올 때 배율이 또 곱해지거나 치트를 끈 뒤에도 남지 않게) */
void cheat_before_save(uint8_t *ram);
/* 상태 저장 직후(같은 잠금 안) — before_save 가 돌려 둔 경기 치트 값을 되쓴다 */
void cheat_after_save(uint8_t *ram);
/* 176 보기 비트 — bit0 프레임 표시 · bit1 대미지 미터(잠겨 있으면 0). practice_tick 이 P_mu 를 잡기 전에 부른다(C_mu 단독) */
int cheat_view_bits(void);

#define CHEAT_NSTAT 16
/* [0 잠김, 1 켜진 치트 수(176: 게임을 바꾸는 것만 — 보기 CH_FD·CH_DMG 는 안 셈), 2 이번 세션에 경기 치트가 먹은 적 있음, 3 올카드 창 안(이번만), 4 보관한 개수 합,
    5 세이브에 이미 올카드(부팅·모드 고를 때 게임이 읽은 값이 모두 8 → 1 · 아님 0 · 올린 뒤·불러온 뒤 아직 모름 −1), 6 유가 고름,
    7 막힌 이유(0 없음 · 1 잠김 · 2 (옛: 리그 세션 — 168 부터 안 씀) · 3 연습 대전 흐름 — 3 은 경기 치트만 · 4 리그 세션에서 먹음(막지 않음)), 8 마지막으로 쓴 P1 공격 배율(−1 없음), 9 공격 배율 기준값,
    10 마지막 한 방 KO 프레임, 11 무한 체력으로 되돌린 횟수, 12 프레임, 13 지금 프로그램(0x57C6), 14 지금 단계(0x5726),
    15 세이브에 남기기로 8 을 쓰는 중] */
void cheat_status(int v[CHEAT_NSTAT]);
