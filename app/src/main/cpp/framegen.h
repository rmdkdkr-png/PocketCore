#ifndef POCKETCORE_FRAMEGEN_H
#define POCKETCORE_FRAMEGEN_H
#include <stdint.h>

enum { FG_OFF = 0, FG_BLEND = 1, FG_MOTION = 2 };

/* prev·cur(RGBA8888, w×h) 사이의 중간 그림을 out 에 만든다. */
void fg_build(const uint8_t *prev, const uint8_t *cur, uint8_t *out, int w, int h, int mode);
void fg_free(void);

#endif
