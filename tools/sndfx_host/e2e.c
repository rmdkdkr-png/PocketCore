/* 179 끝에서 끝: native.c 그대로(정적까지) + 진짜 코어(.so) — load_locked 로 올리고 retro_run 을 돌려 cb_audio_batch → fx_apply → 고리.
   배속 소리 길(g_turbo_audio)로 늘림을 건너 고리에 들어간 그대로를 파일로. 모드 off/all/bgm, 값은 기본(50). */
#include "native.c"
#include <unistd.h>
struct AAudioStreamBuilderStruct { int x; };
struct AAudioStreamStruct { int x; };
aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **b) { *b = calloc(1, sizeof **b); return 0; }
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *b) { free(b); return 0; }
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *b, int32_t n) { (void)b; (void)n; }
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *b, AAudioStream_dataCallback cb, void *ud) { (void)b; (void)cb; (void)ud; }
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *b, AAudioStream_errorCallback cb, void *ud) { (void)b; (void)cb; (void)ud; }
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *b, aaudio_format_t f) { (void)b; (void)f; }
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *b, int32_t m) { (void)b; (void)m; }
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *b, int32_t r) { (void)b; (void)r; }
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *b, AAudioStream **s) { (void)b; *s = calloc(1, sizeof **s); return 0; }
aaudio_result_t AAudioStream_requestStart(AAudioStream *s) { (void)s; return 0; }
aaudio_result_t AAudioStream_requestStop(AAudioStream *s) { (void)s; return 0; }
aaudio_result_t AAudioStream_close(AAudioStream *s) { free(s); return 0; }

static FILE *rawf, *nzf; static long nz_null;
static size_t rec_ab(const int16_t *d, size_t f)
{
   const int16_t *nz = c_snd_noise ? c_snd_noise(d, (unsigned)f) : NULL; static int16_t z[1 << 16];
   if (rawf) fwrite(d, 4, f, rawf);
   if (nzf) { if (nz) fwrite(nz, 4, f, nzf); else { fwrite(z, 4, f, nzf); nz_null++; } }
   return cb_audio_batch(d, f);
}
static const char *fake_get(JNIEnv *e, jstring s, jboolean *c) { (void)e; (void)c; return (const char *)s; }
static void fake_rel(JNIEnv *e, jstring s, const char *c) { (void)e; (void)s; (void)c; }

int main(int argc, char **argv)
{
   /* core rom opts frames mode out hz */
   struct JNINativeInterface_ ni; const struct JNINativeInterface_ *nip = &ni; JNIEnv *env = (JNIEnv *)&nip;
   int frames = atoi(argv[4]), f; const char *mode = argv[5]; FILE *o = fopen(argv[6], "wb");
   memset(&ni, 0, sizeof ni); ni.GetStringUTFChars = fake_get; ni.ReleaseStringUTFChars = fake_rel;
   g_panel_hz_hint = argc > 7 ? (float)atof(argv[7]) : 60.f;
   Java_com_dudu_pocketcore_Emu_nativeSetSoundFx(env, NULL, strcmp(mode, "off") != 0, 50, 50, 50, 50, 50, strcmp(mode, "bgm") == 0);
   if (load_locked(env, (jstring)argv[1], (jstring)argv[2], (jstring)"/tmp", (jstring)"/tmp", (jstring)argv[3]) != 0) { fprintf(stderr, "load fail\n"); return 1; }
   fprintf(stderr, "snd_noise %s\n", c_snd_noise ? "yes" : "no");
   if (argc > 9) { rawf = fopen(argv[8], "wb"); nzf = fopen(argv[9], "wb"); c_set_audio_sample_batch(rec_ab); }
   g_turbo_audio = 1;
   for (f = 0; f < frames; f++) {
      uint32_t rd, wr;
      CORE_LOCK(); c_run(); CORE_UNLOCK();
      rd = g_rd; wr = g_wr;
      while (rd != wr) { fwrite(&g_ring[rd * 2], 4, 1, o); rd = (rd + 1) % ARING_FRAMES; }
      g_rd = rd;
   }
   fclose(o); if (rawf) fclose(rawf); if (nzf) { fclose(nzf); fprintf(stderr, "noise null batches %ld\n", nz_null); }
   fprintf(stderr, "frames %d done\n", frames);
   return 0;
}
