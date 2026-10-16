#ifndef POCKETCORE_FRAMEGEN_H
#define POCKETCORE_FRAMEGEN_H
#include <stdint.h>

enum { FG_OFF = 0, FG_BLEND = 1, FG_MOTION = 2, FG_CADENCE = 3 };

/* prev·cur(RGBA8888, w×h) 사이의 중간 그림을 out 에 만든다. */
void fg_build(const uint8_t *prev, const uint8_t *cur, uint8_t *out, int w, int h, int mode);
void fg_free(void);

/* «박자» 모드(fg_cadence.c, 내부 149) — 실제 프레임 다섯 장(F(n-2)…F(n+2))을 고리에 두고 게임 박자(30Hz·60Hz)에 맞춰
   두 칸을 그린다: slot 0 = G(n+1)(60 목표 — 쉬는 쪽을 반 걸음), slot 1 = n 과 n+1 사이(120 목표 — ¼·½·¾).
   out 은 (w·S)×(h·S) RGBA, S = 4(¼px) 또는 1(업스케일러용). */
void fg_cad_reset(void);
int  fg_cad_push(const uint8_t *rgba, int w, int h);   /* 1 = 다섯 장이 찼다(그릴 수 있음) */
int  fg_cad_ready(void);
void fg_cad_render(int slot, uint8_t *out, int S);
void fg_cad_free(void);

#endif
