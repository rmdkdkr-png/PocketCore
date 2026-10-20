/* 182 타격 알림 GL 시험 — native.c 를 그대로 넣고(#include) 메사 EGL(surfaceless) + GLES2 로 진짜 gl_setup · gl_draw 를 돌린다.
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

static uint8_t RAM[16384];
#define RA(x) ((x) - 0x4000)
static void base(void)
{
   memset(RAM, 0, sizeof RAM);
   RAM[RA(0x40A7)] = 241; RAM[RA(0x411A)] = 1;
   for (int p = 0; p < 2; p++) {
      int b = RA(0x5980) + p * 0x200, o = RA(0x4E30) + p * 0x40;
      RAM[b + 0xC6] = 128; RAM[b + 0x5A] = 128; RAM[b + 0x49] = 0x20; RAM[o + 0x1C] = 0xFF; RAM[o + 0x0E] = 0x00; RAM[o + 0x0F] = 0x01;
   }
}
#define CTRL(p) RAM[RA(0x5980) + (p) * 0x200 + 0x4E]
#define COMBO(p) RAM[RA(0x5980) + (p) * 0x200 + 0x117]
#define BOX(p) RAM[RA(0x4E30) + (p) * 0x40 + 0x1C]
#define HS RAM[RA(0x5DC7)]

static int W = 1080, H = 2400;
static uint8_t *px;
static void grab(void) { glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px); }
static void save(const char *dir, const char *nm)
{
   char p[600]; snprintf(p, sizeof p, "%s/%s.ppm", dir, nm);
   FILE *f = fopen(p, "wb"); fprintf(f, "P6 %d %d 255\n", W, H);
   for (int y = H - 1; y >= 0; y--) for (int x = 0; x < W; x++) fwrite(px + ((size_t)y * W + x) * 4, 1, 3, f);   /* GL 아래 원점 → 위부터 */
   fclose(f);
}
/* 화면(위 원점) 사각형 안에서 배경색과 다른 화소 비율 */
static double frac_diff(int x0, int y0, int x1, int y1, const uint8_t bg[3])
{
   long n = 0, d = 0;
   for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
      if (x < 0 || y < 0 || x >= W || y >= H) continue;
      const uint8_t *q = px + ((size_t)(H - 1 - y) * W + x) * 4; n++;
      if (abs(q[0] - bg[0]) + abs(q[1] - bg[1]) + abs(q[2] - bg[2]) > 24) d++;
   }
   return n ? (double)d / n : 0;
}

static int fails = 0;
static int fx_count_ok(void) { return callout_fx_count() == 1; }
static const uint8_t *blk_c(void) { static const uint8_t b[3] = { 0, 0, 0 }; return b; }
static const char *KN[CO_KINDS] = { "FIRST ATTACK", "COUNTER", "PUNISHER", "REVERSAL" };
#define CHECK(c, ...) do { int ok_ = (c); fprintf(stderr, "%s ", ok_ ? "ok  " : "FAIL"); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); if (!ok_) fails++; } while (0)

static void make_frame(unsigned fw, unsigned fh)
{
   free(g_fb); g_fb = (uint8_t *)malloc((size_t)fw * fh * 4);
   for (unsigned y = 0; y < fh; y++) for (unsigned x = 0; x < fw; x++) { uint8_t *q = g_fb + ((size_t)y * fw + x) * 4; q[0] = 0x40; q[1] = 0x60; q[2] = 0x80; q[3] = 0xFF; }
   g_fb_w = fw; g_fb_h = fh; g_fb_dirty = 1; g_fb_ph = 0;
}

int main(int argc, char **argv)
{
   const char *dir = argc > 1 ? argv[1] : ".";
   EGLDisplay dpy = eglGetPlatformDisplay(0x31DD /* SURFACELESS_MESA */, NULL, NULL);
   EGLint maj, min, nc; EGLConfig cfg;
   if (!dpy || !eglInitialize(dpy, &maj, &min)) { fprintf(stderr, "egl init %x\n", eglGetError()); return 2; }
   eglBindAPI(0x30A0);
   const EGLint ca[] = { 0x3040, 0x0004, 0x3033, 0x0001, 0x3038 };   /* RENDERABLE ES2 · SURFACE PBUFFER */
   if (!eglChooseConfig(dpy, ca, &cfg, 1, &nc) || nc < 1) { const EGLint cb[] = { 0x3040, 0x0004, 0x3038 }; if (!eglChooseConfig(dpy, cb, &cfg, 1, &nc) || nc < 1) { fprintf(stderr, "no config\n"); return 2; } }
   const EGLint xa[] = { 0x3098, 2, 0x3038 };
   EGLContext ctx = eglCreateContext(dpy, cfg, NULL, xa);
   if (!ctx || !eglMakeCurrent(dpy, NULL, NULL, ctx)) { fprintf(stderr, "ctx %x\n", eglGetError()); return 2; }
   fprintf(stderr, "GL %s | %s\n", glGetString(GL_VERSION), glGetString(GL_RENDERER));
   GLuint fbo, ct; glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
   glGenTextures(1, &ct); glBindTexture(GL_TEXTURE_2D, ct);
   glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
   glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ct, 0);
   if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { fprintf(stderr, "fbo incomplete\n"); return 2; }
   px = (uint8_t *)malloc((size_t)W * H * 4);

   gl_setup();
   g_vw = W; g_vh = H;
   Java_com_dudu_pocketcore_Emu_nativeSetCallouts(NULL, NULL, 1);

   /* 가짜 경기: f3 P1 이 발동 중인 P2 를 맞힘(FIRST·COUNTER) → P2 경직 풀리자마자 공격(REVERSAL, f12) → P1 공격 경직에 P2 가 맞힘(PUNISHER, f18) */
   double T0 = 100.0; int f = 0;
   #define TICK() do { f++; callout_tick(RAM, sizeof RAM, T0 + f / 60.0); } while (0)
   base(); TICK();                                  /* f1 라운드 시작 */
   CTRL(1) = 0x02; TICK();                          /* f2 P2 발동 */
   HS = 8; COMBO(1) = 1; CTRL(1) = 0x0A; TICK();    /* f3 닿음 */
   for (int i = 0; i < 5; i++) TICK();              /* f4..f8 히트스톱 */
   HS = 0; TICK(); TICK();                          /* f9 f10 맞음 경직 */
   CTRL(1) = 0; TICK();                             /* f11 풀림 */
   CTRL(1) = 0x02; TICK();                          /* f12 바로 공격 */
   CTRL(0) = 0x02; TICK();                          /* f13 P1 공격 시작(상자 없음) */
   BOX(0) = 0x10; TICK();                           /* f14 상자 켬 */
   BOX(0) = 0xFF; TICK(); TICK(); TICK();           /* f15..f17 상자 끔 → 지속(2칸 지연) → 경직 */
   HS = 8; COMBO(0) = 1; CTRL(0) = 0x0A; TICK();    /* f18 P1 경직에 맞음 */
   int st[8]; callout_stats(st);
   CHECK(st[0] == 1 && st[1] == 1 && st[2] == 1 && st[3] == 1, "판정 FIRST %d COUNTER %d PUNISH %d REVERSAL %d (각 1)", st[0], st[1], st[2], st[3]);

   static const char *frames[2] = { "1x", "4x" };
   for (int fi = 0; fi < 2; fi++) {
      unsigned fw = fi ? 640 : 160, fh = fi ? 608 : 152;
      make_frame(fw, fh);
      /* 뷰포트(gl_draw 와 같은 셈) — 세로 1080×2400: 정수배 6 → 960×912, x 60, 위로 붙임 y0(아래 원점) = 2400−912−168 */
      int dw = 960, dh = 912, vx = 60, vy_gl = H - dh - (int)(H * 0.07f), vy = H - vy_gl - dh;   /* vy = 화면 위 원점 */
      const uint8_t bg[3] = { 0x40, 0x60, 0x80 }, blk[3] = { 0, 0, 0 };
      double sc = 6.0;
      /* ① 다 들어온 뒤(마지막 라벨 12프레임) */
      g_last_vsync = T0 + 30 / 60.0; g_fb_dirty = 1;
      glClear(GL_COLOR_BUFFER_BIT); gl_draw(); grab();
      char nm[64]; snprintf(nm, sizeof nm, "callout_%s_shown", frames[fi]); save(dir, nm);
      CHECK(glGetError() == GL_NO_ERROR, "%s GL 오류 없음", frames[fi]);
      /* 라벨 자리(게임 좌표 → 화면): P1 x 2.., P2 오른쪽 끝, 줄 y 33 · 42.5 */
      int rect[CO_KINDS][4], s4; callout_atlas(NULL, NULL, NULL, rect, &s4);
      int rows[2][2] = { { CO_FIRST, CO_COUNTER }, { CO_REVERSAL, CO_PUNISH } };
      for (int sd = 0; sd < 2; sd++) for (int r = 0; r < 2; r++) {
         int k = rows[sd][r]; double w = rect[k][2] / (double)s4, h = rect[k][3] / (double)s4;
         double gx = sd == 0 ? CO_MARGIN : 160 - CO_MARGIN - w, gy = CO_Y0 + r * (h + 1);
         int x0 = vx + (int)(gx * sc), y0 = vy + (int)(gy * sc), x1 = vx + (int)((gx + w) * sc), y1 = vy + (int)((gy + h) * sc);
         double fr = frac_diff(x0, y0, x1, y1, bg);
         CHECK(fr > 0.30, "%s %s 라벨 자리 [%d,%d)-[%d,%d) 그림 %.0f%%", frames[fi], KN[k], x0, y0, x1, y1, fr * 100);
      }
      CHECK(frac_diff(vx + 20, vy + 100 * 6, vx + 940, vy + 900, bg) == 0, "%s 라벨 밖 게임 그림 그대로", frames[fi]);
      CHECK(frac_diff(0, 0, W, vy, blk) == 0 && frac_diff(0, vy + dh, W, H, blk) == 0 && frac_diff(0, vy, vx, vy + dh, blk) == 0 && frac_diff(vx + dw, vy, W, vy + dh, blk) == 0,
            "%s 뷰포트 밖 검정 그대로", frames[fi]);
      /* 가운데(두 쪽 사이) 비었나 */
      CHECK(frac_diff(vx + 80 * 6 - 30, vy + 33 * 6, vx + 80 * 6 + 30, vy + 52 * 6, bg) == 0, "%s 가운데 빔", frames[fi]);
      /* ② 밀려 들어오는 중(PUNISHER 2프레임째) — 게임 사각형 밖으로 안 나감 */
      g_last_vsync = T0 + 20 / 60.0;
      glClear(GL_COLOR_BUFFER_BIT); gl_draw(); grab();
      snprintf(nm, sizeof nm, "callout_%s_slide", frames[fi]); save(dir, nm);
      CHECK(frac_diff(vx + dw, vy, W, vy + dh, blk) == 0 && frac_diff(0, vy, vx, vy + dh, blk) == 0, "%s 미는 중에도 게임 사각형 밖 그대로", frames[fi]);
      /* ③ 수명 뒤 — 라벨 없음 = 게임 그림만 */
      g_last_vsync = T0 + 200 / 60.0;
      glClear(GL_COLOR_BUFFER_BIT); gl_draw(); grab();
      CHECK(frac_diff(vx, vy, vx + dw, vy + dh, bg) == 0, "%s 수명 뒤 게임 그림만", frames[fi]);
      /* ④ 끔 — 다시 띄워도 안 그림 */
   }
   Java_com_dudu_pocketcore_Emu_nativeSetCallouts(NULL, NULL, 0);
   g_last_vsync = T0 + 30 / 60.0; glClear(GL_COLOR_BUFFER_BIT); gl_draw(); grab();
   { int vx = 60, dh = 912, vy = H - (H - dh - (int)(H * 0.07f)) - dh; const uint8_t bg[3] = { 0x40, 0x60, 0x80 };
     CHECK(frac_diff(vx, vy, vx + 960, vy + dh, bg) == 0, "끔이면 안 그림"); }
   /* 다시 켬 → 지우기 부탁 → 앞 라벨은 사라짐 */
   Java_com_dudu_pocketcore_Emu_nativeSetCallouts(NULL, NULL, 1);
   glClear(GL_COLOR_BUFFER_BIT); gl_draw(); grab();
   { int vx = 60, dh = 912, vy = H - (H - dh - (int)(H * 0.07f)) - dh; const uint8_t bg[3] = { 0x40, 0x60, 0x80 };
     CHECK(frac_diff(vx, vy, vx + 960, vy + dh, bg) == 0, "다시 켜면 앞 라벨 지움"); }
   /* ── 184 타격 이펙트(도트) — 게임 그림 줄에 합성 · 끝나면 깨끗이 되돌림 · 잔상·쪽지 길 ── */
   Java_com_dudu_pocketcore_Emu_nativeSetCallouts(NULL, NULL, 0);
   Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 1);
   static uint8_t PALR[16 * 8];
   { const uint16_t g[3] = { 0xBFF, 0x0EF, 0x0AF }; for (int pp = 2; pp <= 7; pp += 5) for (int j = 0; j < 3; j++) { PALR[pp * 8 + (j + 1) * 2] = (uint8_t)g[j]; PALR[pp * 8 + (j + 1) * 2 + 1] = (uint8_t)(g[j] >> 8); } }
   for (int fi = 0; fi < 2; fi++) for (int mode = 0; mode < 3; mode++) {      /* mode 0 보통 · 1 잔상 · 2 쪽지(4배만) */
      unsigned fw = fi ? 640 : 160, fh = fi ? 608 : 152;
      if (mode == 2 && !fi) continue;
      make_frame(fw, fh);
      static uint8_t *phb; free(phb); phb = NULL;
      if (mode == 2) { phb = calloc((size_t)fw * fh, 1); for (size_t i = 0; i < (size_t)fw * fh; i++) phb[i] = (uint8_t)(i % 7 == 0 ? 5 : 0); g_ph = phb; g_fb_ph = 1; } else { g_fb_ph = 0; }
      g_d_ghost = mode == 1 ? 40 : 0;
      int vx = 60, dh = 912, vy = H - (H - dh - (int)(H * 0.07f)) - dh;
      const uint8_t bg[3] = { 0x40, 0x60, 0x80 };
      callout_reset();
      double T1 = 300.0 + fi * 10 + mode; int fr2 = 0;
      #define TICK2() do { fr2++; callout_fx_pal(PALR); callout_tick(RAM, sizeof RAM, T1 + fr2 / 60.0); } while (0)
      base(); RAM[RA(0x4E30) + 8] = 100; RAM[RA(0x4E30) + 0x40 + 8] = 70; RAM[RA(0x4E30) + 0xA] = 128; RAM[RA(0x4E30) + 0x40 + 0xA] = 128;
      TICK2(); TICK2();
      HS = 8; COMBO(0) = 1; CTRL(0) = 0x0A; RAM[RA(0x5980) + 0xC6] = 108; TICK2();      /* P2(70) 가 P1(100)을 — 강(피해 20) */
      g_fb_dirty = 1; glClear(GL_COLOR_BUFFER_BIT); gl_draw(); grab();
      /* 이펙트 자리: 맞은 몸 100 − 7 = 93, 몸 가운데 128 − 25 = 103 (게임 좌표) → 화면 */
      double fr = frac_diff(vx + 85 * 6, vy + 96 * 6, vx + 120 * 6, vy + 110 * 6, bg);
      CHECK(fx_count_ok(), "fx 하나");
      CHECK(fr > 0.05, "%s %s 이펙트 그려짐(자리 안 %.0f%%)", fi ? "4x" : "1x", mode == 0 ? "보통" : mode == 1 ? "잔상" : "쪽지", fr * 100);
      CHECK(frac_diff(vx, vy, vx + 960, vy + 32 * 6, bg) == 0 && frac_diff(vx, vy + 136 * 6, vx + 960, vy + dh, bg) == 0, "%s 놀이 칸 밖(위·아래 HUD 줄) 그대로", fi ? "4x" : "1x");
      CHECK(frac_diff(0, 0, W, vy, blk_c()) == 0 && frac_diff(0, vy + dh, W, H, blk_c()) == 0, "%s 뷰포트 밖 그대로", fi ? "4x" : "1x");
      HS = 0;
      for (int i = 0; i < 20; i++) TICK2();
      CHECK(callout_fx_count() == 0, "%s 이펙트 끝남", fi ? "4x" : "1x");
      for (int rep2 = 0; rep2 < 2; rep2++) { glClear(GL_COLOR_BUFFER_BIT); gl_draw(); }   /* 새 그림 없이(g_fb_dirty 0) — 텍스처 줄만 되돌려야 */
      grab();
      CHECK(frac_diff(vx, vy, vx + 960, vy + dh, bg) == 0, "%s %s 끝난 뒤 깨끗이 되돌림(새 그림 없이)", fi ? "4x" : "1x", mode == 0 ? "보통" : mode == 1 ? "잔상" : "쪽지");
      CHECK(glGetError() == GL_NO_ERROR, "GL 오류 없음");
      g_d_ghost = 0; g_fb_ph = 0; g_ph = NULL;
   }
   /* 끄면 그리던 것도 바로 되돌림 */
   make_frame(160, 152); callout_reset();
   { double T2 = 500.0; int fr3 = 0;
     #define TICK3() do { fr3++; callout_fx_pal(PALR); callout_tick(RAM, sizeof RAM, T2 + fr3 / 60.0); } while (0)
     base(); RAM[RA(0x4E30) + 8] = 100; RAM[RA(0x4E30) + 0x40 + 8] = 70; RAM[RA(0x4E30) + 0xA] = 128; RAM[RA(0x4E30) + 0x40 + 0xA] = 128;
     TICK3(); TICK3(); HS = 8; COMBO(0) = 1; CTRL(0) = 0x0A; TICK3();
     g_fb_dirty = 1; glClear(GL_COLOR_BUFFER_BIT); gl_draw();
     Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 0);
     glClear(GL_COLOR_BUFFER_BIT); gl_draw(); grab();
     int vx = 60, dh = 912, vy = H - (H - dh - (int)(H * 0.07f)) - dh; const uint8_t bg[3] = { 0x40, 0x60, 0x80 };
     CHECK(frac_diff(vx, vy, vx + 960, vy + dh, bg) == 0, "이펙트 끄면 바로 되돌림"); }
   fprintf(stderr, "%s (%d 실패)\n", fails ? "실패" : "통과", fails);
   return fails ? 1 : 0;
}
