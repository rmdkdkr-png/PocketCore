/* sndfx — 음색 다듬기 (내부 176). 유저 10-20 「소리가 역시 너무 심심한데」 → 들어 보기 넷 중 「2 가 나은 방식이네」.
 *
 * 곡·효과음은 그대로 두고 게임 소리 위에 넷을 얹는다(들어 보기 ② 와 같은 계산 — tools/sndfx_test.c·tools/sndfx_ref.py 로 맞춤):
 *   1. 저음 보강   180Hz 1차 저역 셸프 +3.5dB  — 얇은 사각파에 바닥
 *   2. 고음 눅이기 4.5kHz 1차 고역 셸프 −4dB  — 사각파의 쨍한 날
 *   3. 좌우 넓힘   가운데(L+R)/2 를 9ms 늦춰 왼쪽에 +0.35, 오른쪽에 −0.21
 *   4. 울림        방 울림 응답(좌우 따로 만든 잡음 × 지수 감쇠 1.3초 · 6kHz 2차 저역 — 앞 1.0초) 을 20ms 늦게 시작, 마른 소리 0.8 + 울림 0.32
 *                  44.1kHz 응답은 들어 보기 ② 것 그대로(sndfx_ir.h ← tools/sndfx_ir_gen.py) — 사각파 음악은 응답 잡음이 다르면 음마다 울림 크기가 달라서
 *   그리고 크기 맞춤(원래 소리와 같은 크기로 들리게 — 실측 배율).
 * 울림은 응답을 512 칸씩 나눠 FFT(1024)로 곱해 더하는 «고르게 나눈 겹쳐 버리기»(UPOLS). 응답이 882 샘플(20ms) 늦게 시작해서
 * 한 칸을 다 받은 뒤 계산해도 다음 칸에 늦지 않다 — 지연 0. 소리 카드 콜백(AAudio)에서 돈다(게임 프레임 시간에 안 들어간다).
 * 안드로이드에 기대지 않는다(호스트 시험 tools/sndfx_test.c). */
#ifndef SNDFX_H
#define SNDFX_H

#include <stddef.h>
#include <stdint.h>

typedef struct sndfx sndfx_t;

sndfx_t *sndfx_new(int rate);                       /* 응답·필터를 만든다(약 1.8MB). 못 만들면 NULL */
void     sndfx_free(sndfx_t *fx);
void     sndfx_reset(sndfx_t *fx);                  /* 꼬리·필터 상태를 비운다(켤 때·소리를 버린 뒤) */
void     sndfx_run(sndfx_t *fx, int16_t *buf, size_t frames);   /* 제자리 — 스테레오 섞어 둔 s16 */

/* 시험용 — 만든 응답(마른 소리 뒤 울림, 앞 지연 포함)과 크기 맞춤 배율 */
const float *sndfx_ir(const sndfx_t *fx, int ch, size_t *len);
float        sndfx_gain(const sndfx_t *fx);

#endif
