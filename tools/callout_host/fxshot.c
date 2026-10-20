/* (gltest.c 머리 그대로) 182 타격 알림 GL 시험 — native.c 를 그대로 넣고(#include) 메사 EGL(surfaceless) + GLES2 로 진짜 gl_setup · gl_draw 를 돌린다.
   가짜 램 순서로 callout_tick 을 돌려 네 라벨(P1: FIRST ATTACK·COUNTER / P2: REVERSAL·PUNISHER)을 띄우고,
   게임 그림(160×152 · 서기 그리기 4배 640×608) 위에 그려 읽어 낸다 — 라벨 자리 색, 뷰포트·게임 사각형 밖은 그대로, 수명 뒤엔 게임 그림과 같음.
     J=/usr/lib/jvm/java-21-openjdk-amd64/include; C=app/src/main/cpp; L=/usr/lib/x86_64-linux-gnu
     gcc -std=gnu17 -O1 -g -w -I tools/sndfx_host/inc -I $C -I $J -I $J/linux tools/callout_host/gltest.c \
         $C/pace.c $C/sndfx.c $C/league.c $C/practice.c $C/look.c $C/slash.c $C/vpanel.c $C/vsband.c $C/cheat.c $C/ovplane.c $C/uisfx.c $C/callout.c \
         $L/libGLESv2.so.2 $L/libEGL.so.1 -lm -ldl -lpthread -o gltest
     ./gltest out_dir      (PPM 그림 + 검사 결과, 실패면 1) */
#include "native.c"
#include <stdarg.h>
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
int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{ va_list ap; va_start(ap, fmt); fprintf(stderr, "[%d %s] ", prio, tag); vfprintf(stderr, fmt, ap); fputc('\n', stderr); va_end(ap); return 0; }

/* EGL — 헤더 없이 쓰는 몇 개 */
typedef void *EGLDisplay, *EGLConfig, *EGLContext, *EGLSurface; typedef int32_t EGLint; typedef intptr_t EGLAttrib; typedef unsigned EGLenum, EGLBoolean;
EGLDisplay eglGetPlatformDisplay(EGLenum, void *, const EGLAttrib *);
EGLBoolean eglInitialize(EGLDisplay, EGLint *, EGLint *);
EGLBoolean eglBindAPI(EGLenum);
EGLBoolean eglChooseConfig(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
EGLContext eglCreateContext(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
EGLBoolean eglMakeCurrent(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
EGLint eglGetError(void);


/* 182 타격 이펙트 그림 뽑기 — 진짜 게임 그림(PPM 160×152) 위에 이펙트 셋(섬광·불꽃·번개)을 시간마다 그려 한 장으로.
     ./fxshot frame.ppm 맞은X 맞은Y 때린X 카메라 out.png-prefix */
static uint8_t RAM[16384];
#define RA(x) ((x) - 0x4000)
static int W = 1080, H = 2400;
static uint8_t *px;
static void base(int dx, int ax, int y, int cam)
{
   memset(RAM, 0, sizeof RAM);
   RAM[RA(0x40A7)] = 241; RAM[RA(0x411A)] = 1; RAM[RA(0x576D)] = (uint8_t)cam;
   for (int p = 0; p < 2; p++) {
      int b = RA(0x5980) + p * 0x200, o = RA(0x4E30) + p * 0x40, x = p == 0 ? dx : ax;
      RAM[b + 0xC6] = 128; RAM[b + 0x5A] = 128; RAM[b + 0x49] = 0x20; RAM[o + 0x1C] = 0xFF; RAM[o + 0x0E] = 0x00; RAM[o + 0x0F] = 0x01;
      RAM[o + 8] = (uint8_t)x; RAM[o + 9] = (uint8_t)(x >> 8); RAM[o + 0xA] = (uint8_t)y; RAM[o + 0xB] = 0;
   }
}
#define CTRL(p) RAM[RA(0x5980) + (p) * 0x200 + 0x4E]
#define COMBO(p) RAM[RA(0x5980) + (p) * 0x200 + 0x117]
#define HP(p) RAM[RA(0x5980) + (p) * 0x200 + 0xC6]
#define HS RAM[RA(0x5DC7)]
int main(int argc, char **argv)
{
   if (argc < 7) { fprintf(stderr, "usage\n"); return 2; }
   int dx = atoi(argv[2]), dy = atoi(argv[3]), ax = atoi(argv[4]), cam = atoi(argv[5]);
   EGLDisplay dpy = eglGetPlatformDisplay(0x31DD, NULL, NULL);
   EGLint maj, min, nc; EGLConfig cfg;
   if (!dpy || !eglInitialize(dpy, &maj, &min)) return 2;
   eglBindAPI(0x30A0);
   const EGLint cb[] = { 0x3040, 0x0004, 0x3033, 0x0001, 0x3038 };
   if (!eglChooseConfig(dpy, cb, &cfg, 1, &nc) || nc < 1) { fprintf(stderr, "no config\n"); return 2; }
   const EGLint xa[] = { 0x3098, 2, 0x3038 };
   EGLContext ctx = eglCreateContext(dpy, cfg, NULL, xa);
   if (!ctx || !eglMakeCurrent(dpy, NULL, NULL, ctx)) return 2;
   GLuint fbo, ct; glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
   glGenTextures(1, &ct); glBindTexture(GL_TEXTURE_2D, ct);
   glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
   glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ct, 0);
   px = (uint8_t *)malloc((size_t)W * H * 4);
   /* 그림 */
   FILE *f = fopen(argv[1], "rb"); int fw, fh, mx; if (!f || fscanf(f, "P6 %d %d %d", &fw, &fh, &mx) != 3) return 2; fgetc(f);
   uint8_t *rgb = malloc((size_t)fw * fh * 3); if (fread(rgb, 3, (size_t)fw * fh, f) != (size_t)fw * fh) return 2; fclose(f);
   g_fb = malloc((size_t)fw * fh * 4); for (int i = 0; i < fw * fh; i++) { g_fb[i * 4] = rgb[i * 3]; g_fb[i * 4 + 1] = rgb[i * 3 + 1]; g_fb[i * 4 + 2] = rgb[i * 3 + 2]; g_fb[i * 4 + 3] = 255; }
   g_fb_w = fw; g_fb_h = fh; g_fb_dirty = 1;
   gl_setup(); g_vw = W; g_vh = H;
   Java_com_dudu_pocketcore_Emu_nativeSetCallouts(NULL, NULL, 0);
   static const double TS[6] = { 0.02, 0.06, 0.11, 0.17, 0.25, 0.36 };
   static const int MODES[3] = { FXM_IMPACT, FXM_FIRE, FXM_ELEC };
   int vx = 60, dh = 912, vy = H - (H - dh - (int)(H * 0.07f)) - dh;
   for (int m = 0; m < 3; m++) for (int strong = 0; strong < 2; strong++) {
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, MODES[m]);
      callout_reset();
      double T0 = 50.0 + m * 10 + strong * 5; int fr = 0;
      #define TICK() do { fr++; callout_tick(RAM, sizeof RAM, T0 + fr / 60.0); } while (0)
      base(dx, ax, dy, cam); TICK(); TICK();
      HS = 8; COMBO(0) = 1; CTRL(0) = 0x0A; if (strong) HP(0) = 128 - 20; else HP(0) = 128 - 6; TICK();
      double th = T0 + fr / 60.0;
      for (int k = 0; k < 6; k++) {
         g_last_vsync = th + TS[k]; g_fb_dirty = 1;
         glClear(GL_COLOR_BUFFER_BIT); gl_draw();
         glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px);
         char p[700]; snprintf(p, sizeof p, "%s_m%d_s%d_%d.ppm", argv[6], m, strong, k);
         FILE *o = fopen(p, "wb"); fprintf(o, "P6 %d %d 255\n", 960, dh);
         for (int y = 0; y < dh; y++) { int gy = H - 1 - (vy + y); for (int x = 0; x < 960; x++) fwrite(px + ((size_t)gy * W + vx + x) * 4, 1, 3, o); }
         fclose(o);
      }
      fprintf(stderr, "mode %d strong %d GL %x\n", MODES[m], strong, glGetError());
   }
   return 0;
}
