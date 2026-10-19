#ifndef POCKETCORE_PACE_H
#define POCKETCORE_PACE_H
#include <stddef.h>
#include <stdint.h>

/* 코어를 언제 돌리나 (내부 150).
 * 예전엔 vsync(onDrawFrame)마다 «시계가 마감을 넘었나»로 정했다. 화면 120Hz · 게임 60.25 면 마감이 거의 늘 vsync 바로
 * 그 자리에 걸려, 깨어나는 때가 0.5ms 만 흔들려도 «이번엔 안 돌고 다음에 두 번» 이 1초에 4~8 번 났다(호스트 모의
 * tools/pace_sim.c). 그 순간 앱 보간의 중간 그림이 빠지거나 겹치고, 코어 120 출력(사무쇼2)은 한 장이 빠지고 한 장이 두 번.
 * 이제 화면 vsync 수가 게임 프레임의 정수배(±3%)면 «k 번에 한 번» 을 세어 돌린다 — 게임은 화면 Hz / k(60.25 → 60.0,
 * 0.4% 느림), 소리는 그만큼 늘려(drc) 맞춘다. vsync 를 실제로 놓쳐 벽시계보다 1.5 프레임 넘게 처지면 한 번 따라잡는다.
 * 정수배가 아니면(90Hz 등)·배속 중엔 예전 시계 방식. */
typedef struct pace_t {
   double next_t;      /* 시계 방식: 다음 코어 프레임 마감 시각(초) */
   int    since;       /* 지난 코어 프레임 뒤 지나간 호출(vsync) 수 */
   int    lock_k;      /* 지금 잠근 배수(0 = 시계 방식) */
   double drc_base;    /* 소리 늘림 기본 비(출력/입력) — 잠금이면 fps / (화면 Hz / k), 아니면 1 */
   double ema;         /* 호출 간격 EMA(초) — 튀는 값은 0.5~1.5 배로 눌러 넣는다 */
   double last_t;
   int    streak;      /* 잠글 조건이 이어진 호출 수 / 풀 조건이 이어진 호출 수 */
   double lock_t0;     /* 잠근 뒤 기준 시각 */
   long   lock_c;      /* 그 뒤 호출 수 */
   double lock_fps;    /* 잠금 중 게임 fps(화면 Hz / k) */
#define PACE_WIN 64
   double dwin[PACE_WIN];  /* 놓친 칸(지난 vsync 수 − 호출 수) 최근 값 */
   int    dwn, dwi, base_set;
   double base;        /* 처음 가운데값(큐 깊이) */
   long   corr;        /* 지금까지 따라잡은(+)·쉰(−) 프레임 */
   double corr_t[8];   /* 최근 따라잡기 시각 — 잦으면 잠금을 푼다 */
   int    corr_i;
   double cool_until;  /* 이때까지 다시 안 잠근다 */
   int    cool_n;      /* 쉰 횟수(거듭되면 더 오래) */
   double lock_since;  /* 이번 잠금을 시작한 때 */
   long   catchup;     /* 계기: 따라잡기·건너뛰기 횟수 */
   double lock_hzv;    /* 173: 잠글 때 정한 화면 Hz — 잠금 동안 그대로(늦은 그림에 흔들리는 실측으로 소리 늘림·놓친 칸 셈이 흔들리지 않게) */
} pace_t;

void pace_reset(pace_t *p);
/* 이번 호출에 코어를 몇 번 돌리나. t = 지금(초), fps = 코어가 선언한 fps, hz_hint = Java 가 알려 준 화면 Hz(0 = 모름),
   turbo = 배속(▶▶), paused = 멈춤 */
int  pace_steps(pace_t *p, double t, double fps, double hz_hint, int turbo, int paused);
/* 화면이 게임보다 빠른가(앱 보간이 끼울 빈 vsync 가 있나) — 잠금 k ≥ 2, 또는 실측 간격이 게임 프레임의 0.7 배 미만 */
int  pace_fast_display(const pace_t *p, double fps);

/* 소리 늘리고 줄이기(동적 비율 — 고리 버퍼 찬 정도로 ±0.5%) */
typedef struct drc_t {
   double  pos;        /* 다음 출력 샘플의 입력 위치(-1 = 앞 묶음 마지막 샘플) */
   int16_t last[2];
   int     primed;
} drc_t;
void   drc_reset(drc_t *d);
/* in(n 프레임, 스테레오) → out(최대 cap 프레임)을 ratio(출력/입력)로. 반환 = 쓴 프레임 수 */
size_t drc_run(drc_t *d, const int16_t *in, size_t n, int16_t *out, size_t cap, double ratio);
/* 고리 버퍼 찬 정도(fill)와 목표(target)로 이번 비율 */
double drc_ratio(double base, unsigned fill, unsigned target);

#endif
