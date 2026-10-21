/* LD_PRELOAD — 벽시계를 FIXT(유닉스 초)에 고정: 롬 RTC 가 벽시계를 읽어 판이 띄운 시각에 따라 갈리는 것 막기 */
#define _GNU_SOURCE
#include <time.h>
#include <sys/time.h>
#include <stdlib.h>
#include <dlfcn.h>
static time_t fixt(void) { const char *e = getenv("FIXT"); return e ? (time_t)atoll(e) : (time_t)1792578000; }
time_t time(time_t *t) { time_t v = fixt(); if (t) *t = v; return v; }
int gettimeofday(struct timeval *tv, void *tz) { (void)tz; if (tv) { tv->tv_sec = fixt(); tv->tv_usec = 0; } return 0; }
int clock_gettime(clockid_t c, struct timespec *ts) {
  static int (*real)(clockid_t, struct timespec *);
  if (c == CLOCK_REALTIME || c == CLOCK_REALTIME_COARSE) { ts->tv_sec = fixt(); ts->tv_nsec = 0; return 0; }
  if (!real) real = (int (*)(clockid_t, struct timespec *))dlsym(RTLD_NEXT, "clock_gettime");
  return real(c, ts);
}
