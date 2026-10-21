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
void callout_resync(void);            /* 187 보는 램이 바뀜(런어헤드 사본 ↔ 실제 램·앞선 프레임 수) — 다음 tick 은 판정 없이 기록만. 코어 잠금 안 */
/* 진짜 프레임마다(합성 호출 아님) — ram = 시스템 램(CPU 0x4000~), now = 초 */
void callout_tick(const uint8_t *ram, size_t ramsz, double now);
/* 지금 보이는 라벨(수명 안) — 그릴 차례(오래된 것 먼저) */
int  callout_get(callout_t *out, int max, double now);
/* 195 줄 하나(side 0 왼쪽 · 1 오른쪽, row)의 마지막 라벨 — 수명이 지났어도(코어가 줄 1 자리를 줄 0 라벨 높이로 정할 때). 없으면 0 */
int  callout_peek(int side, int row, callout_t *out);
/* 움직임: age 초 → slide(0 = 제자리, 1 = 라벨 폭 + 여백만큼 바깥) · alpha · flash(흰색 섞기). 수명 밖이면 0 */
int  callout_anim(double age, float *slide, float *alpha, float *flash);
/* 그릴 자리 — 게임 좌표(160×152, 소수) 사각형 x,y,w,h · 텍스처 사각형 tr{x,y,w,h} · 알파 · 번쩍. 수명 밖·아틀라스 실패면 0.
   GL(native.c)·호스트 미리보기가 같은 셈을 쓴다 */
int  callout_layout(const callout_t *c, double now, float q[4], int tr[4], float *alpha, float *flash);
/* 라벨 그림(RGBA, 곧은 알파) 아틀라스 — 한 번 만들어 둔다. rect[k] = {x, y, w, h}(텍스처 px), scale = 게임 화소당 텍스처 px */
int  callout_atlas(const uint8_t **rgba, int *w, int *h, int rect[CO_KINDS][4], int *scale);
/* 시험: [0..3] 종류별 낸 수 · [4] 맞음 · [5] 라운드 · [6] 잡기 · [7] 경직 끝 → 동작 */
void callout_stats(int v[8]);

/* ── 타격 이펙트(유저 10-20 「이펙트가 1번」 → 「인게임에 어울리게 녹이기」 — 184 도트) ──
   같은 닿음 판정에서 맞을 때마다 맞은 몸 앞(때린 쪽 7px · 몸 가운데 높이)에 도트 이펙트(hitfx_cels.h — 게임 화소, 3색 + 투명).
   약 = 섬광 4컷 · 강(보통 기술로 피해 12 이상) = 맞은 쪽으로 뻗는 혜성 5컷 · 필살은 원작 이펙트가 커서 작은 섬광만
   185(2단계): 가드 = 때린 쪽으로 볼록한 푸른 반달 3컷 · 맞음·가드 없이 히트스톱만 선 닿음 = 두 몸 사이 칼 부딪힘 «깡» 4컷 · 다운 시작·공중 맞음 착지 = 발밑 흙먼지 4컷 ·
   체력 0 이 된 맞음 = 맞은 몸을 가로지르는 붓 일섬 5컷 · COUNTER/PUNISHER 는 밑에 각진 고리(노랑/빨강) · 다단히트 = 작은 것 2컷.
   색 = 흰 심 + 때린 쪽 칼 궤적 색(베기 색을 바꿨으면 그 색). 컷은 게임 프레임으로 — 히트스톱 동안 첫 컷 그대로, 그다음 2프레임(1/30초)마다.
   192(유저 「강베기 이펙트가 너무 커서 … 정지화면 뚝이라 애니메이션으로 · 크기가 작게」): 혜성 26×11 · 앞 강베기는 혜성 없이 짧은 초승달 일섬(31×23) 하나 —
   둘은 히트스톱 동안에도 컷마다 길이(hitfx_cels.h dur)대로 넘어간다(그어짐 1·1 → 굵음 → 가늘어짐 → 두 줄로 벌어짐 → 불티, 10~12 프레임).
   193(유저 「내가 넣는 번쩍 효과는 빼자 · 이펙트 겹치는 거 빼자 · 맞는 자에게 임팩트 포인트에서」 · 「나찰 하오마루 강베기 때는 강베기 효과가 안 뜨네」):
   한 번에 하나(새 것이 앞 것을 대신, 반격 고리 없음) · 자리 = 타격 지점(맞은 몸 앞 6px, 높이는 때린 쪽 자세·장풍) ·
   강베기 = 보통 기술(ACT 0x40~0x17F) 피해 12 이상 또는 히트스톱 9 이상 → 모두 작은 일섬(앞 강베기 ACT 는 캐릭터마다 달라서) · 몸 번쩍 안 냄.
   그리기는 게임 그림 안(native.c 가 텍스처 줄에 합성 — 업스케일러·필터가 같이 걸림), 놀이 칸(게임 줄 32~135) 안만. */
enum { FXM_OFF = 0, FXM_ON, FXM_N };
#define FX_MAX 6
void callout_fx_mode(int mode);       /* FXM_* — 아무 스레드 */
int  callout_fx_mode_get(void);
/* 195 이펙트 크기(유저 「큰 효과 작은 효과도」) — 0 작게(기본, 192 그림) · 1 크게(약 섬광 23×23 · 혜성 40×15 · 일섬 47×35, 같은 움직임). 다음에 낼 것부터. 아무 스레드 */
void callout_fx_size(int big);
int  callout_fx_size_get(void);
void callout_fx_art(int gpt);           /* 196 그림 0 = 195 · 1 = GPT 외주 — 다음에 낼 것부터. 아무 스레드 */
int  callout_fx_art_get(void);
void callout_fx_pal(const uint8_t *pal);   /* 코어 스프라이트 팔레트 램(0x8200~) — tick 바로 앞(코어 잠금 안, GL 스레드) */
/* 지금 이펙트가 덮는 게임 줄(gy0..gy1 포함, 놀이 칸 안) — 없으면 0. GL 스레드 */
int  callout_fx_span(int *gy0, int *gy1);
/* RGBA 그림의 텍스처 줄 [row0, row0 + nrows)(rows = 그 첫 줄, 폭 fbw 텍셀)에 이펙트를 그린다. ox·oy = 게임 사각형 왼쪽 위(텍셀) ·
   sub = 게임 화소당 텍셀 · rgb = 12비트 0x0BGR → 메모리 R,G,B,A 순 32비트(ov_rgb) · ph = 같은 줄의 쪽지(NULL 가능) — 이펙트 텍셀은 0. GL 스레드 */
void callout_fx_draw(uint8_t *rows, uint8_t *ph, int row0, int nrows, int fbw, int ox, int oy, int sub, uint32_t (*rgb)(int c12));   /* rows NULL = 쪽지만 */
int  callout_fx_count(void);          /* 시험 — 지금 이펙트 수 */
void callout_fx_each(void (*put)(void *ctx, int gx, int gy, uint16_t c12, int v, int att), void *ctx);   /* 191 코어 — 이펙트 화소마다(게임 좌표·12비트 색 · 193 v = 1 흰 심 · 2 가운데 · 3 테두리 — 코어가 값마다 불투명도를 다르게 · 194 att = 때린 쪽 0/1, 없으면 −1 — 코어가 그 몸 밑으로) */
/* 186 몸 번쩍 — 이번 tick 에 앞 강베기 맞음(189·190)이 있었으면 맞은 쪽 + 1(가져가면 0). callout_flash_enable 로 켰을 때만(191 — 전엔 이펙트를 켰을 때).
   native.c(앱)·ss2hit.c(코어)가 callout_tick 바로 뒤 → hitflash_start */
int  callout_flash_take(void);
void callout_flash_enable(int on);    /* 191 몸 번쩍 켬/끔 — 이펙트와 따로(기본 끔). 아무 스레드 */
/* 186 화면 흔들림 — 지금 게임 그림을 옮길 양(게임 px, 오른쪽·아래 +). 없으면 0. 진짜 프레임마다 한 칸(callout_tick) · 이펙트를 켰을 때만. GL 스레드 */
int  callout_shake(float *gx, float *gy);
void callout_shake_enable(int on);     /* 189 화면 흔들림 켬/끔 — 기본 끔(멀미). 아무 스레드 */

#endif
