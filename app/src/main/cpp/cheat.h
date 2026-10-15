/* 치트 — 사무쇼2 (cheat.c). 일반 세션에서만. 랭크전·게임 속 리그 세션에서는 아무것도 안 쓰고, 연습 대전 흐름에서는 경기 치트를 쉰다. */
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
   CH_N
};

/* 진짜 프레임 한 번(retro_run) 뒤, 코어 잠금 안에서 practice_tick 다음에 부른다(리그·연습이 먼저 쓰고 치트가 가장 뒤) */
void cheat_tick(uint8_t *ram, size_t n, int input);
/* 값 바꾸기(UI 스레드 — 다음 진짜 프레임부터). 잠겨 있으면 무시, 범위 밖이면 0 */
void cheat_set(int key, int value);
/* 모두 끔 + 잠금 풂 + 상태 비움(코어 올리기·내리기 — Java 가 일반 세션이면 다시 넣는다) */
void cheat_reset_all(void);
/* 랭크전·게임 속 리그 세션 — 켜면 값을 0 으로 비우고 cheat_set 을 받지 않으며 tick 은 아무것도 안 쓴다 */
void cheat_lock(int on);
/* 리셋·상태 불러오기 뒤 — 램이 통째로 바뀌었으니 보관한 카드 개수는 되돌리지 않고 버린다 */
void cheat_forget(void);
/* 상태 저장 직전(코어 잠금 안) — 올카드(이번만)로 바꿔 둔 소유 개수를 원래대로(저장된 상태에 8 이 안 실리게). 다음 프레임에 다시 쓴다 */
void cheat_before_save(uint8_t *ram);

#define CHEAT_NSTAT 16
/* [0 잠김, 1 켜진 치트 수, 2 이번 세션에 경기 치트가 먹은 적 있음, 3 올카드 창 안(이번만), 4 보관한 개수 합,
    5 세이브에 이미 올카드(부팅·모드 고를 때 게임이 읽은 값이 모두 8), 6 유가 고름,
    7 막힌 이유(0 없음 · 1 잠김 · 2 리그 세션 · 3 연습 대전 흐름 — 3 은 경기 치트만), 8 마지막으로 쓴 P1 공격 배율(−1 없음), 9 공격 배율 기준값,
    10 마지막 한 방 KO 프레임, 11 무한 체력으로 되돌린 횟수, 12 프레임, 13 지금 프로그램(0x57C6), 14 지금 단계(0x5726),
    15 세이브에 남기기로 8 을 쓰는 중] */
void cheat_status(int v[CHEAT_NSTAT]);
