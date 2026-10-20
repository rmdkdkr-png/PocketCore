/* 179 바꿔 끼우기 시험 — native.c 그대로. GL 스레드(코어 흉내: CORE_LOCK 아래 cb_audio_batch 를 여러 크기로, 가짜 잡음 칸 함수) +
   UI 스레드(nativeSetSoundFx 값·켬끔·배경음만 마구) + 소리 카드 스레드(가짜 AAudio — 고리 비우기). TSan·ASan 으로 */
#include "native.c"
#include <unistd.h>
struct AAudioStreamBuilderStruct { AAudioStream_dataCallback cb; void *ud; int rate; };
struct AAudioStreamStruct { AAudioStream_dataCallback cb; void *ud; int rate; pthread_t th; atomic_int run; };
aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **b) { *b = calloc(1, sizeof **b); return 0; }
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *b) { free(b); return 0; }
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *b, int32_t n) { (void)b; (void)n; }
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *b, AAudioStream_dataCallback cb, void *ud) { b->cb = cb; b->ud = ud; }
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *b, AAudioStream_errorCallback cb, void *ud) { (void)b; (void)cb; (void)ud; }
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *b, aaudio_format_t f) { (void)b; (void)f; }
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *b, int32_t m) { (void)b; (void)m; }
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *b, int32_t r) { b->rate = r; }
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *b, AAudioStream **s)
{ *s = calloc(1, sizeof **s); (*s)->cb = b->cb; (*s)->ud = b->ud; (*s)->rate = b->rate; return 0; }
static void *cbthread(void *a)
{
   AAudioStream *s = a; int16_t *buf = malloc(4096 * 4); unsigned seed = 5;
   while (atomic_load(&s->run)) { int n = 96 + (int)((seed = seed * 1103515245u + 12345u) >> 16) % 1000; s->cb(s, s->ud, buf, n); usleep(1000); }
   free(buf); return NULL;
}
aaudio_result_t AAudioStream_requestStart(AAudioStream *s) { atomic_store(&s->run, 1); pthread_create(&s->th, NULL, cbthread, s); return 0; }
aaudio_result_t AAudioStream_requestStop(AAudioStream *s) { if (atomic_exchange(&s->run, 0)) pthread_join(s->th, NULL); return 0; }
aaudio_result_t AAudioStream_close(AAudioStream *s) { AAudioStream_requestStop(s); free(s); return 0; }

static int16_t core_buf[8192 * 2], core_noise[8192 * 2];
static const int16_t *fake_noise(const int16_t *d, unsigned f)
{ uintptr_t x = (uintptr_t)d; if (x >= (uintptr_t)core_buf && x + f * 4 <= (uintptr_t)(core_buf + 8192 * 2)) return core_noise + (d - core_buf); return NULL; }
static atomic_int g_quit; static atomic_long g_batches;
static void *glthread(void *a)
{
   unsigned seed = 9; long k = 0; (void)a;
   while (!atomic_load(&g_quit)) {
      int n = 1 + (int)((seed = seed * 1103515245u + 12345u) >> 16) % 2000, i;
      CORE_LOCK();
      for (i = 0; i < n * 2; i++) { core_buf[i] = (int16_t)((k + i) * 37); core_noise[i] = (int16_t)(((k + i) * 11) & 0x3FF); }
      cb_audio_batch(core_buf + ((seed >> 20) & 1) * 2, (size_t)(n > 8190 ? 8190 : n));
      CORE_UNLOCK();
      k += n; atomic_fetch_add(&g_batches, 1);
      if (((seed >> 8) & 31) == 0) { /* 무음 길: GL 스레드만 보는 g_fx_cur 를 0 으로 — 소리 카드 콜백이 읽는 league_mute 는 안 건드림(기존 경쟁) */ g_fx_cur = 0; }
      usleep(700);
   }
   return NULL;
}
int main(int argc, char **argv)
{
   int secs = argc > 1 ? atoi(argv[1]) : 10; unsigned seed = 77; long sets = 0, restarts = 0;
   pthread_t gl; struct timespec t0, t; clock_gettime(CLOCK_MONOTONIC, &t0);
   c_snd_noise = fake_noise;
   g_turbo_audio = 0;
   audio_start(44100);
   pthread_create(&gl, NULL, glthread, NULL);
   for (;;) {
      int v[5], k;
      clock_gettime(CLOCK_MONOTONIC, &t); if (t.tv_sec - t0.tv_sec >= secs) break;
      seed = seed * 1103515245u + 12345u;
      for (k = 0; k < 5; k++) v[k] = ((seed >> (3 + k * 5)) % 3 == 0) ? (int)((seed >> (k * 4)) % 21) * 5 : 50;
      Java_com_dudu_pocketcore_Emu_nativeSetSoundFx(NULL, NULL, (seed >> 29) != 0, v[0], v[1], v[2], v[3], v[4], (seed >> 27) & 1); sets++;
      if ((seed >> 20) % 23 == 0) { audio_stop(); usleep(300); audio_start(44100); restarts++; }
      usleep((seed >> 8) % 6000);
   }
   atomic_store(&g_quit, 1); pthread_join(gl, NULL);
   audio_stop();
   CORE_LOCK(); { sndfx_t *nx = atomic_exchange(&g_fx_next, NULL); sndfx_free(nx); sndfx_free(g_fx); g_fx = NULL; } CORE_UNLOCK();
   printf("sets %ld restarts %ld batches %ld\n", sets, restarts, atomic_load(&g_batches));
   return 0;
}
