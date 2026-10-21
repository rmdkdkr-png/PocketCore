/* sndfx — 음색 다듬기 (내부 176). 유저 10-20 「소리가 역시 너무 심심한데」 → 들어 보기 넷 중 「2 가 나은 방식이네」.
 * 178: 「음색 다듬기도 설정이 있잖아 조절해 보게 해 줘」 — 넷의 세기와 울림 길이를 값으로(sndfx_params). 기본값 = 들어 보기 ② = 176 그대로.
 *
 * 곡·효과음은 그대로 두고 게임 소리 위에 넷을 얹는다(들어 보기 ② 와 같은 계산 — tools/sndfx_test.c·tools/sndfx_ref.py 로 맞춤):
 *   1. 저음 보강   180Hz 1차 저역 셸프 +3.5dB  — 얇은 사각파에 바닥                       (bass_db 0..+7)
 *   2. 고음 눅이기 4.5kHz 1차 고역 셸프 −4dB   — 사각파의 쨍한 날                         (treble_db −8..0)
 *   3. 좌우 넓힘   가운데(L+R)/2 를 9ms 늦춰 왼쪽에 +0.35, 오른쪽에 −0.21                  (width 배수 0..2)
 *   4. 울림        방 울림 응답(좌우 따로 만든 잡음 × 지수 감쇠 1.3초 · 6kHz 2차 저역 — 앞 1.0초) 을 20ms 늦게 시작, 마른 소리 0.8 + 울림 0.32
 *                  (mix 0..1: 마른 1−0.4m · 울림 0.64m — ② = 0.5 · rt 울림 길이 초 — ② = 1.3, 응답 길이는 rt 에 비례)
 *                  44.1kHz 응답은 들어 보기 ② 것 그대로(sndfx_ir.h ← tools/sndfx_ir_gen.py) — 사각파 음악은 응답 잡음이 다르면 음마다 울림 크기가 달라서.
 *                  길이를 바꾸면 그 응답의 감쇠만 다시 입히고(1.0초 넘는 꼬리는 같은 방식으로 만든 잡음) 에너지는 ② 와 같게.
 *   그리고 크기 맞춤 — 값을 바꿔도 원래 소리와 같은 크기로 들리게: ② 에서 실측 배율 0.91, 다른 값은 그 비율을 모델로
 *   (SS2 소리 평균 스펙트럼 sndfx_spec.h × 셸프 응답 × 넓힘 × (마른² + 울림² × 응답 대역 에너지) — 실측과 ±0.5dB, tools/sndfx_model.py).
 * 울림은 응답을 512 칸씩 나눠 FFT(1024)로 곱해 더하는 «고르게 나눈 겹쳐 버리기»(UPOLS). 응답이 882 샘플(20ms) 늦게 시작해서
 * 한 칸을 다 받은 뒤 계산해도 다음 칸에 늦지 않다 — 지연 0. 소리 카드 콜백(AAudio)에서 돈다(게임 프레임 시간에 안 들어간다).
 * 안드로이드에 기대지 않는다(호스트 시험 tools/sndfx_test.c). */
#ifndef SNDFX_H
#define SNDFX_H

#include <stddef.h>
#include <stdint.h>

typedef struct sndfx sndfx_t;

typedef struct {
   float bass_db;      /* 저음 보강(dB, 0..7)        — ② 3.5 */
   float treble_db;    /* 고음 눅이기(dB, −8..0)     — ② −4 */
   float width;        /* 좌우 넓힘 배수(0..2)       — ② 1 */
   float mix;          /* 울림 양(0..1)              — ② 0.5 */
   float rt;           /* 울림 길이(초, 0.4..3.5)    — ② 1.3 */
} sndfx_params;

void     sndfx_defaults(sndfx_params *p);           /* 들어 보기 ② */
/* 설정 값(0..100, 50 = ②) → 값. 저음 0..+7dB · 고음 눅이기 0..−8dB · 넓힘 0..2배 · 울림 0..1 · 울림 길이 1.3초 × 2^((v−50)/40) (0.55..3.1초) */
void     sndfx_from_pct(sndfx_params *p, int bass, int treble, int width, int reverb, int room);
int      sndfx_same(const sndfx_t *fx, int rate, const sndfx_params *p);   /* 같은 빠르기·값으로 만든 것인가 */

sndfx_t *sndfx_new(int rate, const sndfx_params *p); /* 응답·필터를 만든다(약 1.8MB — 긴 울림은 더). p == NULL 이면 ②. 못 만들면 NULL */
void     sndfx_free(sndfx_t *fx);
void     sndfx_reset(sndfx_t *fx);                  /* 꼬리·필터 상태를 비운다(켤 때·소리를 버린 뒤) */
void     sndfx_run(sndfx_t *fx, int16_t *buf, size_t frames);   /* 제자리 — 스테레오 섞어 둔 s16 */

/* 시험용 — 만든 응답(마른 소리 뒤 울림, 앞 지연 포함)·크기 맞춤 배율·칸 수 */
const float *sndfx_ir(const sndfx_t *fx, int ch, size_t *len);
float        sndfx_gain(const sndfx_t *fx);
int          sndfx_parts(const sndfx_t *fx);

#endif
