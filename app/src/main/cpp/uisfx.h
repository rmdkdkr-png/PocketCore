/* 176 덧판 메뉴 효과음 — 사무쇼2 메뉴 소리를 그대로 합성(롬 소리를 담지 않음).
 * 호스트 코어에서 잰 Z80 소리 명령 0x0E(커서) · 0x02(결정) · 0x06(뒤로)의 T6W28 사각파 채널 2 쓰기를
 * {44.1kHz 샘플, 주기 N, 감쇠} 표로 옮겨, 오디오 콜백(audio_cb)이 고리 버퍼 소리 위에 더한다 — 코어가 멈춰 있어도(ov_hold) 난다.
 * 스레드: uisfx_request 는 아무 스레드(잠금 없음 — 비트 하나 OR). uisfx_mix 는 오디오 스레드만.
 *         uisfx_reset 은 콜백이 돌지 않을 때만(스트림을 열기 전). */
#ifndef UISFX_H
#define UISFX_H
#include <stdint.h>

#define UISFX_STOP   0      /* 지금 소리를 짧게 줄여 끔 */
#define UISFX_CURSOR 1      /* 0x0E — C5 사각파 하나, 194ms 감쇠 */
#define UISFX_SELECT 2      /* 0x02 — G#5 C6 C#6 F6 G#6 오름 + 메아리 둘, 538ms */
#define UISFX_BACK   3      /* 0x06 — C4 → G#5 온음 빠른 오름, 166ms */
#define UISFX_N      4

int  uisfx_request(int kind);                       /* 1 = 받음(다음 콜백이 소리 냄) · 0 = 모르는 kind */
void uisfx_reset(int rate);                         /* 소리·요청 지움 + 출력 샘플레이트 */
void uisfx_mix(int16_t *out, int frames, int mute); /* 스테레오 int16 위에 더함(포화) · mute = 요청 버리고 지금 소리는 짧게 줄여 끔 */
int  uisfx_active(void);                            /* 소리 나는 중(지금 kind · 0 없음) — 시험용 */

#endif
