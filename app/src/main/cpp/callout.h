/* 182 타격 알림(callout.c) — 사무쇼2 경기 중 «FIRST ATTACK · COUNTER · PUNISHER · REVERSAL» 라벨.
 * 유저 10-20 「맞을 때 이펙트 효과를 추가로 넣거나」 → 「화면 효과는 그런 거 말고 카운터 퍼스트어택 퍼니셔 리버설 같은 거」.
 *
 * 판정은 램을 읽기만 한다(쓰기 0 — 켜고 꺼도 게임은 같다). 규칙은 연습 대전 프레임 미터(practice.c fd_tick)와 같다:
 *   닿음     히트스톱 0x5DC7 이 0 → 0 아님 = 닿은 프레임, 맞은 쪽 = 콤보 수(+0x117)가 오른 쪽(가드 비트·튕겨내기 비트 쪽은 맞음 아님).
 *            히트스톱 없이 콤보 수 0 → 1 = 잡기.
 *   칸       조작 0x4E: 0 = 움직일 수 있음 · 04/06/08 대시·점프 · 0A/0C/10/12 맞음·공중 맞음·다운·가드 · 그 밖 = 동작.
 *            동작 칸 = 발동(공격 상자 아직) · 지속(상자 켬 — 게임은 켠 다음 틱에 맞춤을 보니 2칸 지연 줄) · 경직(상자가 꺼진 뒤).
 *            장풍은 주인이 동작 중에 켠 물체 상자를 그 동작의 지속으로.
 *   FIRST ATTACK  라운드 첫 맞음(잡기 포함) — 때린 쪽.
 *   COUNTER       맞은 쪽이 바로 앞 프레임에 발동·지속 칸(기술을 내는 중)이었다.
 *   PUNISHER      맞은 쪽이 바로 앞 프레임에 경직 칸(헛치거나 막힌 기술의 빈틈)이었다.
 *   REVERSAL      맞음·가드·다운 경직이 풀린 뒤 첫 틱(2 프레임 안)에 공격 동작(조작 02·FF)을 시작했다 — 낸 쪽.
 * 봇 8판(16라운드) 실측: 맞음 291 중 발동·지속 41 · 경직 11 · 첫 타 16 · 경직 끝→동작 간격은 2 프레임이 가장 짧음(게임 틱 30Hz).
 *
 * 그리기: 라벨은 쪽마다 두 줄(게임 y 33 · 다음 줄), 때린 쪽 가장자리에서 밀려 들어와 1초. 그림(callout_atlas.h — 4배)과 움직임 셈은 여기,
 * GL 은 native.c(gl_draw 뒤 알파 쿼드). 스레드: tick·get·layout·atlas 는 GL 스레드, enable·reset 은 아무 스레드(부탁만 — 지우기는 다음 tick·get 이). */
#ifndef CALLOUT_H
#define CALLOUT_H

#include <stddef.h>
#include <stdint.h>

enum { CO_FIRST = 0, CO_COUNTER, CO_PUNISH, CO_REVERSAL, CO_KINDS };
#define CO_LIFE_F   60          /* 라벨 수명(60Hz 프레임) */
#define CO_ROWS     2           /* 쪽마다 줄 */
#define CO_Y0       33          /* 첫 줄 위(게임 y) — HUD 검은 띠(24..31) 바로 아래 */
#define CO_MARGIN   2           /* 가장자리 여백(게임 px) */

typedef struct { int kind, side, row; double t0; } callout_t;

void callout_enable(int on);          /* 아무 스레드 */
int  callout_enabled(void);
void callout_reset(void);             /* 코어 올림·내림·상태 불러오기·리셋 — 아무 스레드(다음 tick·get 이 지운다) */
/* 진짜 프레임마다(합성 호출 아님) — ram = 시스템 램(CPU 0x4000~), now = 초 */
void callout_tick(const uint8_t *ram, size_t ramsz, double now);
/* 지금 보이는 라벨(수명 안) — 그릴 차례(오래된 것 먼저) */
int  callout_get(callout_t *out, int max, double now);
/* 움직임: age 초 → slide(0 = 제자리, 1 = 라벨 폭 + 여백만큼 바깥) · alpha · flash(흰색 섞기). 수명 밖이면 0 */
int  callout_anim(double age, float *slide, float *alpha, float *flash);
/* 그릴 자리 — 게임 좌표(160×152, 소수) 사각형 x,y,w,h · 텍스처 사각형 tr{x,y,w,h} · 알파 · 번쩍. 수명 밖·아틀라스 실패면 0.
   GL(native.c)·호스트 미리보기가 같은 셈을 쓴다 */
int  callout_layout(const callout_t *c, double now, float q[4], int tr[4], float *alpha, float *flash);
/* 라벨 그림(RGBA, 곧은 알파) 아틀라스 — 한 번 만들어 둔다. rect[k] = {x, y, w, h}(텍스처 px), scale = 게임 화소당 텍스처 px */
int  callout_atlas(const uint8_t **rgba, int *w, int *h, int rect[CO_KINDS][4], int *scale);
/* 시험: [0..3] 종류별 낸 수 · [4] 맞음 · [5] 라운드 · [6] 잡기 · [7] 경직 끝 → 동작 */
void callout_stats(int v[8]);

/* ── 타격 이펙트(유저 10-20 「이펙트가 1번」 — 맞을 때 불꽃·번개 같은 이펙트를 더 얹기) ──
   같은 닿음 판정에서 맞을 때마다(히트스톱 맞음 + 히트스톱 없는 여러 번 맞음) 맞은 몸 앞(때린 쪽 7px · 몸 가운데 높이)에 이펙트 하나.
   그림은 native.c 의 셰이더가 그린다(텍스처 없이 셈 — 반지름 r 게임 px 쿼드, 진행 0..1). 자동: COUNTER·PUNISHER = 번개 · 센 맞음(피해 12 이상·필살) = 불꽃 · 나머지 = 섬광 */
enum { FX_IMPACT = 0, FX_FIRE, FX_ELEC, FX_KINDS };
enum { FXM_OFF = 0, FXM_AUTO, FXM_FIRE, FXM_ELEC, FXM_IMPACT, FXM_N };
#define FX_MAX 6
typedef struct {
   int kind;            /* FX_* (−1 = 빈 칸) */
   float wx, y, r, dir; /* 무대 X(화면 x = wx − 카메라) · 화면 y · 반지름(게임 px) · 밀리는 쪽 ±1 */
   double t0, life;     /* 시작(초) · 길이(초) */
   float p[8][4];       /* 줄기·불씨·번개마다 무작위 값(각도 0..1 · 길이 · 위상 둘) */
} hitfx_t;
void callout_fx_mode(int mode);       /* FXM_* — 아무 스레드 */
int  callout_fx_mode_get(void);
/* 지금 보이는 이펙트(오래된 것 먼저) · cam = 지금 카메라(화면 x = wx − cam) — GL 스레드 */
int  callout_fx_get(hitfx_t *out, int max, double now, float *cam);

#endif
