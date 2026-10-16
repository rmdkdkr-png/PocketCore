/* 코어 보간(ngpfg, SVC 코어) 호스트 시험 — 코어 오브젝트를 그대로 링크한다.
 *   cc -O2 -I <core>/libretro-common/include -I <core> tools/ngpfg_harness.c <libcore.a> -lstdc++ -lm -o ngpfg_harness
 *   ngpfg_harness exact  롬 실제프레임수                 — 옮김 0 으로 다시 그린 그림이 원래 그림과 화소까지 같은가(흑백·컬러)
 *   ngpfg_harness dump   롬 실제프레임수 outdir c0 c1     — 출력(120Hz 면 호출마다) c0..c1 을 PPM 으로
 *   ngpfg_harness motion 롬 실제프레임수 c0 c1 y0 y1      — 출력마다 줄 y0..y1(1배 줄) 띠의 가로 이동(¼px)을 앞 출력과 견줘 적는다
 *   ngpfg_harness time   롬 실제프레임수                 — 실제 호출·합성 호출 시간
 * 환경: SCRIPT="a-b:KEYS,…"(실제 프레임 번호, 키 U D L R A B S, 소문자는 8 프레임 중 4), OPT=auto|enabled|60|disabled, HZ=120 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdarg.h>
#include <time.h>
#include "libretro.h"
#include "ngpfg.h"

void retro_ngp_fg_state(int *active, int *phase, unsigned *frames);
void retro_ngp_fg_stats(long *out8);
const uint16_t *retro_ngp_fg_real(void);

/* 롬 RTC(rtc.c)가 호스트 시계를 읽어 같은 대본도 돌 때마다 달라진다 — 시험에선 시계를 멈춘다 */
time_t time(time_t *t) { if (t) *t = 1700000000; return 1700000000; }

static const char *opt = "auto";
static float hz = 120.f;
static double fps = 60.25;
static long realn = 0, calln = 0;
static char script[8192];
static unsigned vw, vh; static size_t vpitch;
static uint16_t vbuf[640 * 800], prevbuf[640 * 800];
static unsigned pw, ph; static int have_prev = 0;
static int dup_last = 0;

static void logcb(enum retro_log_level l, const char *fmt, ...) { (void)l; (void)fmt; }
static bool env(unsigned cmd, void *data)
{
   switch (cmd)
   {
   case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: return *(int *)data == RETRO_PIXEL_FORMAT_RGB565;
   case RETRO_ENVIRONMENT_GET_VARIABLE: {
      struct retro_variable *v = data;
      if (!strcmp(v->key, "ngp_framegen")) { v->value = opt; return true; }
      if (!strcmp(v->key, "ngp_language")) { v->value = "english"; return true; }
      v->value = NULL; return false; }
   case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool *)data = false; return true;
   case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE: *(float *)data = hz; return true;
   case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char **)data = "/tmp"; return true;
   case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool *)data = true; return true;
   case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: ((struct retro_log_callback *)data)->log = logcb; return true;
   case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: fps = ((const struct retro_system_av_info *)data)->timing.fps; return true;
   case RETRO_ENVIRONMENT_SET_GEOMETRY: return true;
   case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE: *(int *)data = 3; return true;
   default: return false;
   }
}
static void video(const void *d, unsigned w, unsigned h, size_t pitch)
{
   unsigned y;
   if (!d) { dup_last = 1; return; }
   dup_last = 0;
   vw = w; vh = h; vpitch = pitch;
   for (y = 0; y < h; y++) memcpy(vbuf + (size_t)y * w, (const uint8_t *)d + y * pitch, w * 2);
}
static void audio1(int16_t l, int16_t r) { (void)l; (void)r; }
static size_t audiob(const int16_t *d, size_t n) { (void)d; return n; }
static void poll(void) { }
static int keymask(void)
{
   int m = 0; const char *p = script;
   while (*p)
   {
      long a, b; char keys[32]; int n = 0;
      if (sscanf(p, "%ld-%ld:%31[^,]%n", &a, &b, keys, &n) < 3) break;
      if (realn >= a && realn <= b)
         for (char *k = keys; *k; k++)
         {
            int pulse = (*k >= 'a' && *k <= 'z'); char K = pulse ? *k - 32 : *k;
            if (pulse && (realn % 8) >= 4) continue;
            int id = K == 'U' ? RETRO_DEVICE_ID_JOYPAD_UP : K == 'D' ? RETRO_DEVICE_ID_JOYPAD_DOWN : K == 'L' ? RETRO_DEVICE_ID_JOYPAD_LEFT : K == 'R' ? RETRO_DEVICE_ID_JOYPAD_RIGHT
                   : K == 'A' ? RETRO_DEVICE_ID_JOYPAD_B : K == 'B' ? RETRO_DEVICE_ID_JOYPAD_A : K == 'S' ? RETRO_DEVICE_ID_JOYPAD_START : -1;
            if (id >= 0) m |= 1 << id;
         }
      p += n; if (*p == ',') p++;
   }
   return m;
}
static int16_t st(unsigned port, unsigned dev, unsigned idx, unsigned id)
{
   (void)idx;
   if (port || dev != RETRO_DEVICE_JOYPAD) return 0;
   if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)keymask();
   return (keymask() >> id) & 1;
}
static void rgb(uint16_t p, uint8_t *o) { o[0] = ((p >> 11) & 31) * 255 / 31; o[1] = ((p >> 5) & 63) * 255 / 63; o[2] = (p & 31) * 255 / 31; }
static void ppm(const char *fn, const uint16_t *b, unsigned w, unsigned h)
{
   FILE *f = fopen(fn, "wb"); unsigned i; uint8_t o[3];
   fprintf(f, "P6\n%u %u\n255\n", w, h);
   for (i = 0; i < w * h; i++) { rgb(b[i], o); fwrite(o, 1, 3, f); }
   fclose(f);
}
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }

/* 띠(4배 줄 Y0..Y1)의 가로 이동 — 앞 출력의 띠를 dx(¼px, −40..40)만큼 옮겨 가장 잘 맞는 dx */
static int band_shift(int Y0, int Y1, int *bestcost)
{
   int dx, best = 0, bc = 1 << 30, Y, X;
   for (dx = -40; dx <= 40; dx++)
   {
      int c = 0;
      for (Y = Y0; Y < Y1; Y += 2)
         for (X = 48; X < (int)vw - 48; X += 2)
            c += vbuf[Y * vw + X] != prevbuf[Y * vw + X - dx];
      if (c < bc || (c == bc && abs(dx) < abs(best))) { bc = c; best = dx; }
   }
   *bestcost = bc;
   return best;
}

int main(int argc, char **argv)
{
   const char *mode = argv[1], *outdir = NULL;
   long frames, c0 = 0, c1 = -1, y0 = 0, y1 = 0, mism_frames = 0, mism_px = 0, checked = 0;
   double treal = 0, tsyn = 0; long nreal = 0, nsyn = 0;
   static uint8_t rom[4 << 20];
   FILE *rf; size_t rs; struct retro_game_info gi;
   if (argc < 4) { fprintf(stderr, "usage\n"); return 1; }
   if (getenv("OPT")) opt = getenv("OPT");
   if (getenv("HZ")) hz = (float)atof(getenv("HZ"));
   snprintf(script, sizeof script, "%s", getenv("SCRIPT") ? getenv("SCRIPT") : "");
   retro_set_environment(env); retro_init(); retro_set_video_refresh(video); retro_set_audio_sample(audio1); retro_set_audio_sample_batch(audiob);
   retro_set_input_poll(poll); retro_set_input_state(st);
   rf = fopen(argv[2], "rb"); if (!rf) { perror(argv[2]); return 1; }
   rs = fread(rom, 1, sizeof rom, rf); fclose(rf);
   gi.path = argv[2]; gi.data = rom; gi.size = rs; gi.meta = NULL;
   if (!retro_load_game(&gi)) { fprintf(stderr, "load fail\n"); return 1; }
   frames = atol(argv[3]);
   if (!strcmp(mode, "dump")) { outdir = argv[4]; c0 = atol(argv[5]); c1 = atol(argv[6]); }
   if (!strcmp(mode, "motion")) { c0 = atol(argv[4]); c1 = atol(argv[5]); y0 = atol(argv[6]); y1 = atol(argv[7]); }
   while (realn < frames)
   {
      int act = 0, ph0 = 0; unsigned fr;
      double t0, t1;
      retro_ngp_fg_state(&act, &ph0, &fr);
      t0 = now();
      retro_run();
      t1 = now();
      if (act && ph0 == 1) { tsyn += t1 - t0; nsyn++; } else { treal += t1 - t0; nreal++; }
      if (!strcmp(mode, "trace") && !(act && ph0 == 1) && !dup_last)
      {  /* 실제 프레임마다 게임 그림 해시 — 코어 보간을 켜도(숨은 프레임·되돌림) 게임이 한 비트도 안 바뀌는가 */
         const uint16_t *g; uint64_t h = 1469598103934665603ULL; int i;
         if (vw == 160) g = vbuf + (size_t)(vh - 152) * 160; else g = retro_ngp_fg_real();
         for (i = 0; i < 160 * 152; i++) { h ^= g[i]; h *= 1099511628211ULL; }
         printf("%ld %016llx\n", realn, (unsigned long long)h);
         if (getenv("TRACEDUMP") && realn == atol(getenv("TRACEDUMP")))
         {
            char fn[256];
            snprintf(fn, sizeof fn, "/tmp/claude-0/svcfg/trace_%s_%ld.ppm", vw == 160 ? "off" : "on", realn);
            if (vw == 160) ppm(fn, vbuf, vw, vh); else ppm(fn, g, 160, 152);
         }
      }
      if (!strcmp(mode, "exact") && !(act && ph0 == 1))
      {
         const ngpfg_frame *f = ngpfg_hist(0);
         static uint16_t mine[160 * 152];
         static uint32_t cmap[4096];
         static int cmap_ok = 0;
         if (!cmap_ok)
         {  /* gfx.c 의 RGB565 색표와 같게 */
            int i;
            for (i = 0; i < 4096; i++) { int r = (i & 15) * 17, g = ((i >> 4) & 15) * 17, b = ((i >> 8) & 15) * 17; cmap[i] = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3); }
            cmap_ok = 1;
         }
         if (f && ngpfg_render1(f, mine, 160, cmap))
         {
            const uint16_t *real = retro_ngp_fg_real();
            int i, n = 0;
            for (i = 0; i < 160 * 152; i++) n += mine[i] != real[i];
            checked++;
            if (n) { mism_frames++; mism_px += n; if (mism_frames <= 5) { char fn[256]; fprintf(stderr, "frame %ld: %d px differ (mono %d dirty %d)\n", realn, n, f->mono, f->dirty); snprintf(fn, sizeof fn, "/tmp/claude-0/svcfg/exact_%ld_mine.ppm", realn); ppm(fn, mine, 160, 152); snprintf(fn, sizeof fn, "/tmp/claude-0/svcfg/exact_%ld_real.ppm", realn); ppm(fn, real, 160, 152); } }
         }
      }
      if (!dup_last && calln >= c0 && calln <= c1)
      {
         if (!strcmp(mode, "dump") && (!getenv("STEP") || (calln - c0) % atol(getenv("STEP")) == 0))
         {
            char fn[512]; snprintf(fn, sizeof fn, "%s/o_%06ld.ppm", outdir, calln);
            ppm(fn, vbuf, vw, vh);
         }
         else if (!strcmp(mode, "motion"))
         {
            int S = vw / 160, cost = 0, sh = 0;
            if (have_prev && pw == vw && ph == vh) sh = band_shift((int)y0 * S, (int)y1 * S, &cost);
            printf("%ld %s r%ld shift %d cost %d\n", calln, (act && ph0 == 1) ? "합" : "실", realn, sh, cost);
         }
         memcpy(prevbuf, vbuf, (size_t)vw * vh * 2); pw = vw; ph = vh; have_prev = 1;
      }
      calln++;
      if (!(act && ph0 == 1)) realn++;
   }
   if (!strcmp(mode, "exact")) printf("확인 %ld 프레임 · 다른 프레임 %ld · 다른 화소 %ld\n", checked, mism_frames, mism_px);
   if (!strcmp(mode, "time") || !strcmp(mode, "exact"))
   {
      long s8[8]; retro_ngp_fg_stats(s8);
      printf("실제 호출 %.3f ms (%ld) · 합성 호출 %.3f ms (%ld) · fps %.2f · 계기 계획 %ld 옮긴조각 %ld 짝없음 %ld 스크롤줄 %ld 불가 %ld\n",
             nreal ? treal / nreal * 1000 : 0, nreal, nsyn ? tsyn / nsyn * 1000 : 0, nsyn, fps, s8[0], s8[1], s8[2], s8[3], s8[4]);
   }
   return 0;
}
