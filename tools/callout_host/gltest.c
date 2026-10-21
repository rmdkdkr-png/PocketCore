/* 182 타격 알림 GL 시험 — native.c 를 그대로 넣고(#include) 메사 EGL(surfaceless) + GLES2 로 진짜 gl_setup · gl_draw 를 돌린다.
   가짜 램 순서로 callout_tick 을 돌려 네 라벨(P1: FIRST ATTACK·COUNTER / P2: REVERSAL·PUNISHER)을 띄우고,
   게임 그림(160×152 · 서기 그리기 4배 640×608) 위에 그려 읽어 낸다 — 라벨 자리 색, 뷰포트·게임 사각형 밖은 그대로, 수명 뒤엔 게임 그림과 같음.
     J=/usr/lib/jvm/java-21-openjdk-amd64/include; C=app/src/main/cpp; L=/usr/lib/x86_64-linux-gnu
     gcc -std=gnu17 -O1 -g -w -I tools/sndfx_host/inc -I $C -I $J -I $J/linux tools/callout_host/gltest.c \
         $C/pace.c $C/sndfx.c $C/league.c $C/practice.c $C/look.c $C/slash.c $C/vpanel.c $C/vsband.c $C/cheat.c $C/ovplane.c $C/uisfx.c $C/callout.c $C/hitflash.c \
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
/* 186 가짜 코어 바꿔 쓰기 표(retro_ngp_palremap 과 같은 꼴) — FR[칸][자리] = {from, to}, from −1 = 비움 */
static int FR[256][2][2];
static void fake_remap(int idx, int slot, int from, int to)
{
   if (idx < 0) { for (int i = 0; i < 256; i++) for (int k = 0; k < 2; k++) FR[i][k][0] = -1; return; }
   if (idx > 255 || slot < 0 || slot > 1) return;
   FR[idx][slot][0] = from < 0 ? -1 : from; FR[idx][slot][1] = to;
}
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

static uint32_t rgb12_test(int c) { uint32_t r = (c & 15) * 17, g = ((c >> 4) & 15) * 17, b = ((c >> 8) & 15) * 17; return r | g << 8 | b << 16 | 0xFF000000u; }
static int put_badv, put_att = -2;   /* 193 v(컷 값)가 1~3 이 아니거나 흰 심(1)이 흰색이 아님 · 194 마지막으로 받은 때린 쪽 */
static void put_test(void *ctx, int gx, int gy, uint16_t c12, int v, int att) { uint32_t c = rgb12_test(c12); if (v < 1 || v > 3 || (v == 1) != (c12 == 0xFFF)) put_badv++; put_att = att; memcpy((uint8_t *)ctx + ((size_t)gy * 160 + gx) * 4, &c, 4); }

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
      CHECK(callout_fx_count() == 1, "fx 하나(보통 센 맞음 = 혜성 — 일섬은 앞 강베기만, 189)");
      CHECK(fr > 0.05, "%s %s 이펙트 그려짐(자리 안 %.0f%%)", fi ? "4x" : "1x", mode == 0 ? "보통" : mode == 1 ? "잔상" : "쪽지", fr * 100);
      CHECK(frac_diff(vx + 20, vy + 10, vx + 940, vy + 32 * 6 - 10, bg) == 0 && frac_diff(vx + 20, vy + 136 * 6 + 10, vx + 940, vy + dh - 10, bg) == 0, "%s 놀이 칸 밖(위·아래 HUD 줄) 그대로(187 강베기 흔들림 15·6 px 띠는 빼고)", fi ? "4x" : "1x");
      CHECK(frac_diff(0, 0, W, vy - 10, blk_c()) == 0 && frac_diff(0, vy + dh + 10, W, H, blk_c()) == 0, "%s 뷰포트 밖 그대로(흔들림 10 px 빼고)", fi ? "4x" : "1x");
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
   /* ── 186 COUNTER 몸 번쩍 — 반격 맞음이면 맞은 쪽 + 1 · 몸 팔레트(0 P1 · 5 P2) 밝은 두 색만 흰색 · 2 진짜 프레임 뒤 되돌림 ·
         그 사이 게임이 다시 쓰면 게임 것 그대로 · 저장 직전 되돌림 · 잊기(불러온 상태) · 윗 4비트 · 이펙트 끄면 부탁 없음 ── */
   {
      static uint8_t PB2[16 * 8];
      const uint16_t body0[3] = { 0xBDF, 0x00D, 0x005 }, body5[3] = { 0xCDF, 0x980, 0x230 }, tint[3] = { 0xFFF, 0xF9B, 0xF58 };
      #define PSET(pp, c) do { for (int j_ = 0; j_ < 3; j_++) { PB2[(pp) * 8 + (j_ + 1) * 2] = (uint8_t)(c)[j_]; PB2[(pp) * 8 + (j_ + 1) * 2 + 1] = (uint8_t)((c)[j_] >> 8); } } while (0)
      #define PGET(pp, j) ((uint16_t)(PB2[(pp) * 8 + ((j) + 1) * 2] | PB2[(pp) * 8 + ((j) + 1) * 2 + 1] << 8))
      PSET(0, body0); PSET(5, body5);
      hitflash_forget();
      double T4 = 700.0; int f4 = 0;
      #define TICK4() do { f4++; callout_fx_pal(PB2); callout_tick(RAM, sizeof RAM, T4 + f4 / 60.0); } while (0)
      #define COUNTER_SEQ() do { callout_reset(); base(); TICK4(); CTRL(1) = 0x02; TICK4(); HS = 8; COMBO(1) = 1; CTRL(1) = 0x0A; TICK4(); } while (0)
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 1);
      Java_com_dudu_pocketcore_Emu_nativeSetHitFlash(NULL, NULL, 1);   /* 191 몸 번쩍은 따로 켬 */
      callout_reset(); base(); TICK4(); CTRL(1) = 0x02; TICK4();
      CHECK(callout_flash_take() == 0, "몸 번쩍: 닿기 전엔 부탁 없음");
      RAM[RA(0x4E30) + 0x0E] = 0x68; RAM[RA(0x4E30) + 0x0F] = 0x00;
      HS = 8; COMBO(1) = 1; CTRL(1) = 0x0A; RAM[RA(0x5980) + 0x200 + 0xC6] = 108; TICK4();   /* P1 이 앞 강베기(ACT 0x68)로 P2 를(피해 20 · COUNTER 이기도) */
      int fs = callout_flash_take();
      CHECK(fs == 0, "193 몸 번쩍 뺌: 앞 강베기에도 부탁 없음(%d)", fs);
      fs = 2;                                                /* 아래는 hitflash 모듈 자체 시험(코어 ss2hit 가 아직 들고 있음) */
      hitflash_start(PB2, fs - 1);
      CHECK(PGET(5, 0) == 0xFFF && PGET(5, 1) == 0xFFF && PGET(5, 2) == 0x230 && PGET(0, 0) == 0xBDF && PGET(0, 1) == 0x00D,
            "몸 번쩍: P2 팔레트 5 = 흰·흰·테두리 그대로(%03X %03X %03X), P1 팔레트 0 그대로", PGET(5, 0), PGET(5, 1), PGET(5, 2));
      hitflash_tick(PB2);
      CHECK(hitflash_active() == 2 && PGET(5, 0) == 0xFFF, "몸 번쩍: 1 프레임 뒤 아직 흰색");
      hitflash_tick(PB2);
      CHECK(hitflash_active() == 0 && PGET(5, 0) == 0xCDF && PGET(5, 1) == 0x980 && PGET(5, 2) == 0x230, "몸 번쩍: 2 프레임 뒤 원래 색");
      hitflash_start(PB2, 1); PSET(5, tint); hitflash_tick(PB2); hitflash_tick(PB2);
      CHECK(hitflash_active() == 0 && PGET(5, 0) == 0xFFF && PGET(5, 1) == 0xF9B && PGET(5, 2) == 0xF58, "몸 번쩍: 그 사이 게임이 쓴 물들임은 그대로");
      PSET(5, body5);
      hitflash_start(PB2, 0);
      CHECK(PGET(0, 0) == 0xFFF && PGET(0, 1) == 0xFFF && PGET(0, 2) == 0x005, "몸 번쩍: P1 팔레트 0 흰색");
      hitflash_start(PB2, 0); hitflash_tick(PB2);
      CHECK(hitflash_active() == 1 && PGET(0, 0) == 0xFFF, "몸 번쩍: 번쩍이는 중 또 반격 — 길이만 다시(흰색을 원래 색으로 저장하지 않음)");
      hitflash_end(PB2);
      CHECK(hitflash_active() == 0 && PGET(0, 0) == 0xBDF && PGET(0, 1) == 0x00D && PGET(0, 2) == 0x005, "몸 번쩍: 저장 직전 되돌림");
      hitflash_start(PB2, 0); hitflash_forget(); hitflash_tick(PB2); hitflash_tick(PB2);
      CHECK(PGET(0, 0) == 0xFFF && hitflash_active() == 0, "몸 번쩍: 잊으면 안 되돌림(불러온 상태가 팔레트를 덮음)");
      PSET(0, body0);
      PB2[5 * 8 + 3] |= 0x30;                               /* 팔레트 5 색 1 윗바이트의 쓰지 않는 4비트 */
      hitflash_start(PB2, 1); hitflash_tick(PB2); hitflash_tick(PB2);
      CHECK(PGET(5, 0) == 0x3CDF, "몸 번쩍: 윗 4비트까지 되돌림(%04X)", PGET(5, 0));
      PSET(5, body5);
      { uint16_t in[3] = { 0x00D, 0xBDF, 0x005 }, out[3]; hitflash_colors(in, out);
        CHECK(out[0] == 0xFFF && out[1] == 0xFFF && out[2] == 0x005, "몸 번쩍: 가장 어두운 칸만 남김(칸 순서 무관)"); }
      { uint16_t in[3] = { 0x111, 0xFFF, 0x888 }, out[3]; hitflash_colors(in, out);
        CHECK(out[0] == 0x111 && out[1] == 0xFFF && out[2] == 0xFFF, "몸 번쩍: 어두운 칸이 앞이어도"); }
      { static uint8_t Z[16 * 8]; hitflash_start(Z, 1); CHECK(hitflash_active() == 0 && !Z[5 * 8 + 2], "몸 번쩍: 빈 팔레트는 안 씀"); }
      hitflash_start(NULL, 0); CHECK(hitflash_active() == 0, "몸 번쩍: 팔레트 없음 무시");
      #define FRONT_SEQ() do { callout_reset(); base(); TICK4(); RAM[RA(0x4E30) + 0x0E] = 0x68; RAM[RA(0x4E30) + 0x0F] = 0; \
                               HS = 8; COMBO(1) = 1; CTRL(1) = 0x0A; RAM[RA(0x5980) + 0x200 + 0xC6] = 108; TICK4(); } while (0)
      Java_com_dudu_pocketcore_Emu_nativeSetHitFlash(NULL, NULL, 0);
      FRONT_SEQ();
      CHECK(callout_flash_take() == 0 && callout_fx_count() == 1, "몸 번쩍: 191 번쩍만 끄면 부탁 없음(이펙트는 그대로 — 192 앞 강베기 일섬 하나)");
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 0);
      Java_com_dudu_pocketcore_Emu_nativeSetHitFlash(NULL, NULL, 1);
      FRONT_SEQ();
      CHECK(callout_flash_take() == 0 && callout_fx_count() == 0, "193 몸 번쩍 뺌: 번쩍만 켜도 부탁 없음(판정만 돎, 이펙트 없음)");
      Java_com_dudu_pocketcore_Emu_nativeSetHitFlash(NULL, NULL, 0);
      FRONT_SEQ();
      CHECK(callout_flash_take() == 0, "몸 번쩍: 셋 다 끄면 판정도 안 돎");
      Java_com_dudu_pocketcore_Emu_nativeSetHitFlash(NULL, NULL, 1);
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 1);
      COUNTER_SEQ(); callout_reset(); TICK4();
      CHECK(callout_flash_take() == 0, "몸 번쩍: 지우기(리셋) 뒤엔 부탁 없음");
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 0);
      Java_com_dudu_pocketcore_Emu_nativeSetHitFlash(NULL, NULL, 0);
      /* 코어 바꿔 쓰기 표 겹치기(look.c) — 번쩍 동안만 팔레트 5 자리 0 = «번쩍 전 세 색 → 흰색», 끝나면 look 이 바란 표로 */
      look_palremap_fn((void *)fake_remap);
      look_vram(NULL, NULL, PB2);
      base(); look_tick(RAM, sizeof RAM, 0, LOOK_CTX_FREE);
      CHECK(FR[21][0][0] < 0 && FR[1][0][0] < 0, "표: 번쩍 전엔 몸 팔레트 줄 없음");
      hitflash_start(PB2, 1); look_flash_sync();
      CHECK(FR[21][0][0] == 0xCDF && FR[21][0][1] == 0xFFF && FR[22][0][0] == 0x980 && FR[22][0][1] == 0xFFF && FR[23][0][0] == 0x230 && FR[23][0][1] == 0x230,
            "표: 번쩍 시작 → 팔레트 5 «CDF 980 230 → FFF FFF 230»");
      PSET(5, body5);                                        /* 게임이 다시 씀 — 코어라면 표가 흰색으로 바꿔 넣는다(여기선 가짜라 직접) */
      { uint16_t w5[3] = { 0xFFF, 0xFFF, 0x230 }; PSET(5, w5); }
      hitflash_tick(PB2); look_tick(RAM, sizeof RAM, 0, LOOK_CTX_FREE);
      CHECK(hitflash_active() == 2 && FR[21][0][0] == 0xCDF, "표: 1 프레임 뒤 look_tick 도 번쩍 줄 유지");
      hitflash_tick(PB2); look_tick(RAM, sizeof RAM, 0, LOOK_CTX_FREE);
      CHECK(hitflash_active() == 0 && FR[21][0][0] < 0 && FR[22][0][0] < 0 && FR[23][0][0] < 0 && PGET(5, 0) == 0xCDF, "표: 끝나면 줄 빠지고 팔레트 원래 색");
      hitflash_start(PB2, 0); look_flash_sync();
      CHECK(FR[1][0][0] == 0xBDF && FR[1][0][1] == 0xFFF && FR[3][0][1] == 0x005, "표: P1 팔레트 0 도");
      hitflash_end(PB2); look_flash_sync();
      CHECK(FR[1][0][0] < 0 && PGET(0, 0) == 0xBDF, "표: 저장 직전 끝 → 줄 빠지고 팔레트 원래 색");
      hitflash_start(PB2, 0); look_flash_sync(); hitflash_forget(); look_forget(); look_flash_sync();
      CHECK(FR[1][0][0] == 0xBDF, "표: look_forget 뒤엔 다음 look_tick 까지 안 건드림(표 통째로 다시 넣기 전)");
      base(); look_tick(RAM, sizeof RAM, 0, LOOK_CTX_FREE);
      CHECK(FR[1][0][0] < 0, "표: 다음 look_tick 에 통째로 비우고 다시(번쩍 줄 없음)");
      PSET(0, body0);
      look_palremap_fn(NULL); look_vram(NULL, NULL, NULL); look_forget();
   }
   /* ── 186 화면 흔들림 — 센 맞음 2 · 막타 3 · 약한 COUNTER·칼 부딪힘 1 · 센 것 도는 중 약한 것 무시 · 이펙트 끄면 없음 · gl_draw 가 게임 그림만 옮김 ── */
   {
      float gx = 0.f, gy = 0.f, seq[40];
      int n5;
      double T5 = 900.0; int f5 = 0;
      #define TICK5() do { f5++; callout_fx_pal(NULL); callout_tick(RAM, sizeof RAM, T5 + f5 / 60.0); } while (0)
      #define RUNSHAKE() do { n5 = 0; while (n5 < 40 && callout_shake(&gx, &gy)) { seq[n5++] = gx; TICK5(); } } while (0)
      #define HIT_SEQ(hp, act) do { callout_reset(); base(); RAM[RA(0x4E30) + 8] = 100; RAM[RA(0x4E30) + 0x40 + 8] = 70; RAM[RA(0x4E30) + 0xA] = 128; RAM[RA(0x4E30) + 0x40 + 0xA] = 128; \
                                 RAM[RA(0x4E30) + 0x40 + 0x0E] = (uint8_t)(act); RAM[RA(0x4E30) + 0x40 + 0x0F] = (uint8_t)((act) >> 8); \
                                 TICK5(); TICK5(); HS = 8; COMBO(0) = 1; CTRL(0) = 0x0A; RAM[RA(0x5980) + 0xC6] = (hp); TICK5(); } while (0)
      #define HEAVY_SEQ(hp) HIT_SEQ(hp, 0x100)
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 1);
      Java_com_dudu_pocketcore_Emu_nativeSetHitFlash(NULL, NULL, 1);
      HEAVY_SEQ(108);
      CHECK(!callout_shake(&gx, &gy), "흔들림: 189 기본 끔 — 강베기에도 안 흔듦(멀미)");
      callout_shake_enable(1);
      callout_reset(); base(); TICK5(); TICK5();
      CHECK(!callout_shake(&gx, &gy), "흔들림: 맞기 전엔 없음");
      HEAVY_SEQ(108);                                       /* P2(70) 가 P1(100)을 보통 기술로 — 피해 20 = 강베기 */
      CHECK(callout_shake(&gx, &gy) && gx == 2.5f && gy == -1.f, "흔들림: 강베기 → 막타와 같은 가장 큰 흔들림, 밀리는 오른쪽 2.5 px 먼저 + 위아래(%.2f, %.2f)", gx, gy);
      CHECK(callout_flash_take() == 0 && callout_fx_count() == 1, "강베기(ACT 0x100): 번쩍 없음 · 일섬 하나(193 — 모든 강베기)");
      RUNSHAKE();
      CHECK(n5 == 12 && seq[1] == -2.5f, "흔들림: 강베기 12 프레임(%d, %.2f)", n5, seq[1]);
      HIT_SEQ(108, 0x68);                                   /* 앞 강베기 */
      CHECK(callout_flash_take() == 0 && callout_fx_count() == 1, "앞 강베기(ACT 0x68): 번쩍 없음(193) · 일섬 하나");
      HIT_SEQ(100, 0x64);                                   /* 내딛는 칸(0x64)에 닿아도 */
      CHECK(callout_flash_take() == 0 && callout_fx_count() == 1, "앞 강베기(ACT 0x64): 번쩍 없음 · 일섬 하나");
      RUNSHAKE();
      HIT_SEQ(108, 0x1B0);                                  /* 필살로 피해 20 — 그림은 절제(작은 섬광), 흔들림은 1.5 px */
      CHECK(callout_shake(&gx, &gy) && gx == 1.5f && gy == 0.f, "흔들림: 필살 센 맞음 1.5 px(%.2f, %.2f)", gx, gy);
      CHECK(callout_flash_take() == 0 && callout_fx_count() == 1, "필살 센 맞음: 번쩍 없음 · 이펙트 하나");
      RUNSHAKE();
      CHECK(n5 == 9 && seq[1] == -1.5f && seq[8] == 0.25f, "흔들림: 필살 센 맞음 9 프레임 좌우 번갈아 줄어듦(%d, %.2f … %.2f)", n5, seq[1], seq[n5 > 0 ? n5 - 1 : 0]);
      HEAVY_SEQ(0);                                         /* 막타 */
      CHECK(callout_shake(&gx, &gy) && gx == 2.5f && gy == -1.f, "흔들림: 막타 2.5 px + 위아래(%.2f, %.2f)", gx, gy);
      CHECK(callout_flash_take() == 0 && callout_fx_count() == 1, "막타: 번쩍 없음(189) · 붉은 일섬 하나");
      RUNSHAKE();
      CHECK(n5 == 12, "흔들림: 막타 12 프레임(%d)", n5);
      callout_reset(); base(); RAM[RA(0x4E30) + 8] = 100; RAM[RA(0x4E30) + 0x40 + 8] = 70; RAM[RA(0x5980) + 0x200 + 0x49] = 0x40;   /* P2 앉아 강베기 */
      TICK5(); TICK5(); HS = 8; COMBO(0) = 1; CTRL(0) = 0x0A; RAM[RA(0x5980) + 0xC6] = 108; TICK5();
      CHECK(callout_flash_take() == 0 && callout_fx_count() == 1, "앉아 강베기: 번쩍 없음 · 이펙트 하나");
      callout_reset(); base(); TICK5(); CTRL(1) = 0x02; TICK5(); HS = 8; COMBO(1) = 1; CTRL(1) = 0x0A; TICK5();
      CHECK(callout_flash_take() == 0, "약한 COUNTER: 번쩍 없음(189)");
      callout_reset(); base(); TICK5(); CTRL(1) = 0x02; TICK5(); HS = 8; COMBO(1) = 1; CTRL(1) = 0x0A; TICK5();   /* 피해 없는 반격(약) */
      CHECK(callout_shake(&gx, &gy) && gx == 1.f, "흔들림: 약한 COUNTER 1 px(%.2f)", gx);
      RUNSHAKE();
      CHECK(n5 == 4, "흔들림: 약한 것 4 프레임(%d)", n5);
      HEAVY_SEQ(0); TICK5();                                /* 막타 흔들림 도는 중(2칸째)에 약한 반격 */
      CTRL(1) = 0x02; TICK5(); HS = 0; TICK5(); HS = 8; COMBO(1) = 1; CTRL(1) = 0x0A; TICK5();
      CHECK(callout_shake(&gx, &gy) && (gx == 6 / 4.f || gx == -6 / 4.f || gx == 2.f || gx == -2.f), "흔들림: 센 것 도는 중 약한 것은 무시(%.2f)", gx);
      {  /* 187 보는 램이 바뀌면(런어헤드 사본 ↔ 실제) 앞 프레임 값으로 판정하지 않음 */
         int a0[8], a1[8];
         callout_reset(); base(); TICK5(); TICK5(); callout_stats(a0);
         callout_resync(); HS = 8; COMBO(0) = 1; CTRL(0) = 0x0A; TICK5(); callout_stats(a1);
         CHECK(a1[4] == a0[4] && callout_fx_count() == 0, "다시 맞춤: 보는 램이 바뀐 첫 프레임은 판정 없이 기록만");
         HS = 0; TICK5(); HS = 8; COMBO(0) = 2; TICK5(); callout_stats(a1);
         CHECK(a1[4] == a0[4] + 1, "다시 맞춤: 그다음부터는 그대로 판정");
      }
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 0);
      CHECK(!callout_shake(&gx, &gy), "흔들림: 이펙트 끄면 바로 없음");
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 1);
      /* gl_draw — 1배 160×152, 정수배 6: 1.5 px(필살 센 맞음) → 화면 9 px 오른쪽. 왼쪽 9 줄은 검정(glClear), 오른쪽 밖 9 줄에 게임 그림 */
      make_frame(160, 152);
      HIT_SEQ(108, 0x1B0);
      int vx = 60, dw = 960, dh = 912, vy = H - (H - dh - (int)(H * 0.07f)) - dh;
      const uint8_t bg[3] = { 0x40, 0x60, 0x80 };
      g_fb_dirty = 1; glClear(GL_COLOR_BUFFER_BIT); gl_draw(); grab();
      save(dir, "shake_1x");
      CHECK(frac_diff(vx, vy + 10, vx + 9, vy + dh - 10, blk_c()) == 0 && frac_diff(vx + dw, vy + 10, vx + dw + 9, vy + dh - 10, bg) == 0
            && frac_diff(vx + 9, vy + 40 * 6, vx + 9 + 60, vy + 90 * 6, bg) == 0,
            "흔들림: gl_draw 가 게임 그림을 9 px 오른쪽으로(왼쪽 띠 검정 · 오른쪽 밖에 그림)");
      CHECK(glGetError() == GL_NO_ERROR, "흔들림: GL 오류 없음");
      g_paused = 1; glClear(GL_COLOR_BUFFER_BIT); gl_draw(); grab();
      CHECK(frac_diff(vx, vy + 10, vx + 9, vy + dh - 10, bg) == 0 && frac_diff(vx + dw, vy + 10, vx + dw + 9, vy + dh - 10, blk_c()) == 0, "흔들림: 멈춤 중엔 제자리");
      g_paused = 0;
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 0);
      glClear(GL_COLOR_BUFFER_BIT); gl_draw(); grab();
      CHECK(frac_diff(vx, vy + 10, vx + 9, vy + dh - 10, bg) == 0, "흔들림: 끄면 제자리");
      callout_shake_enable(0);
   }
   /* ── 191 코어용 callout_fx_each 가 callout_fx_draw 와 같은 화소·같은 색(코어 ss2hit.c 가 쓴다) ── */
   {
      static uint8_t PB3[16 * 8];
      static uint8_t A1[160 * 152 * 4], A2[160 * 152 * 4];
      { const uint16_t g[3] = { 0xBFF, 0x0EF, 0x0AF }; for (int pp = 2; pp <= 7; pp += 5) for (int j = 0; j < 3; j++) { PB3[pp * 8 + (j + 1) * 2] = (uint8_t)g[j]; PB3[pp * 8 + (j + 1) * 2 + 1] = (uint8_t)(g[j] >> 8); } }
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 1);
      double T6 = 1100.0; int f6 = 0, same = 1, cnt = 0;
      #define TICK6() do { f6++; callout_fx_pal(PB3); callout_tick(RAM, sizeof RAM, T6 + f6 / 60.0); } while (0)
      callout_reset(); base(); RAM[RA(0x4E30) + 8] = 100; RAM[RA(0x4E30) + 0x40 + 8] = 70; RAM[RA(0x4E30) + 0xA] = 128; RAM[RA(0x4E30) + 0x40 + 0xA] = 128;
      RAM[RA(0x4E30) + 0x40 + 0x0E] = 0x68; TICK6(); TICK6();
      HS = 8; COMBO(0) = 1; CTRL(0) = 0x0A; RAM[RA(0x5980) + 0xC6] = 100; TICK6();   /* 앞 강베기 — 일섬(192) */
      for (int step = 0; step < 14; step++) {
         memset(A1, 0, sizeof A1); memset(A2, 0, sizeof A2);
         callout_fx_draw(A1, NULL, 0, 152, 160, 0, 0, 1, rgb12_test);
         callout_fx_each(put_test, A2);
         if (memcmp(A1, A2, sizeof A1)) same = 0;
         for (size_t i = 0; i < sizeof A1; i += 4) cnt += A1[i + 3] != 0;
         if (step == 0) HS = 0;
         TICK6();
      }
      CHECK(same && cnt > 0 && !put_badv, "191 fx_each = fx_draw(화소 %d 개, 14 프레임) · 193 컷 값 v 맞음(틀림 %d)", cnt, put_badv);
      CHECK(put_att == 1, "194 fx_each 때린 쪽 = P2(맞은 몸 P1 — 받은 att %d)", put_att);
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 0);
   }
   /* ── 192·193 강베기 일섬: 히트스톱 동안에도 움직임(7컷 12 프레임) · 크기 31×23 안 · 약 섬광은 그대로 멈춤 ·
         193 강베기 = 보통 기술 피해 12 이상 또는 히트스톱 9 이상(나찰 하오마루 0x88: 피해 8 · 히트스톱 11) · 한 번에 하나 · 타격 지점 높이 ── */
   {
      static uint8_t PB4[16 * 8];
      static uint8_t B1[160 * 152 * 4], B2[160 * 152 * 4];
      double T7 = 1300.0; int f7 = 0, changed = 0, life = 0;
      #define TICK7() do { f7++; callout_fx_pal(PB4); callout_tick(RAM, sizeof RAM, T7 + f7 / 60.0); } while (0)
      #define SNAP(B) do { memset(B, 0, sizeof B1); callout_fx_draw(B, NULL, 0, 152, 160, 0, 0, 1, rgb12_test); } while (0)
      #define HIT7(actv, hp, hsv) do { callout_reset(); base(); RAM[RA(0x4E30) + 8] = 100; RAM[RA(0x4E30) + 0x40 + 8] = 70; RAM[RA(0x4E30) + 0xA] = 128; RAM[RA(0x4E30) + 0x40 + 0xA] = 128; \
                                     RAM[RA(0x4E30) + 0x40 + 0x0E] = (uint8_t)(actv); RAM[RA(0x4E30) + 0x40 + 0x0F] = (uint8_t)((actv) >> 8); TICK7(); TICK7(); \
                                     HS = (hsv); COMBO(0) = 1; CTRL(0) = 0x0A; RAM[RA(0x5980) + 0xC6] = (uint8_t)(hp); TICK7(); } while (0)
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 1);
      static const struct { int act, hp, hs; const char *nm; int heavy; } KC[] = {
         { 0x68, 108, 10, "앞 강베기(아수라 0x68)", 1 }, { 0x6C, 100, 11, "앞 강베기(나찰 하오마루 0x6C)", 1 },
         { 0x88, 120, 11, "나찰 하오마루 0x88 — 피해 8 · 히트스톱 11", 1 }, { 0x50, 120, 3, "약베기", 0 }, { 0x1B0, 100, 10, "필살", 0 },
         { 0x0C, 116, 3, "ACT 0x0C(장풍 날아가는 동안)", 0 } };
      for (int kc = 0; kc < (int)(sizeof KC / sizeof KC[0]); kc++) {
         HIT7(KC[kc].act, KC[kc].hp, KC[kc].hs);
         SNAP(B1); changed = 0; life = 1;
         for (int i = 0; i < 9; i++) { TICK7(); SNAP(B2); if (memcmp(B1, B2, sizeof B1)) changed++; memcpy(B1, B2, sizeof B1); if (callout_fx_count()) life++; }
         HS = 0;
         for (int i = 0; i < 20 && callout_fx_count(); i++) { TICK7(); if (callout_fx_count()) life++; }
         if (KC[kc].heavy) CHECK(changed >= 5 && life == 12, "193 %s: 일섬 — 히트스톱 동안 바뀜 %d · 길이 %d(12)", KC[kc].nm, changed, life);
         else              CHECK(changed == 0 && life != 12, "193 %s: 작은 섬광 — 히트스톱 동안 멈춤(바뀜 %d · 길이 %d)", KC[kc].nm, changed, life);
      }
      {  /* 크기 — 그린 화소 상자 31×23 안 */
         int x0 = 999, y0 = 999, x1 = -1, y1 = -1;
         HIT7(0x68, 108, 10);
         for (int i = 0; i < 14; i++) { SNAP(B1); for (int y = 0; y < 152; y++) for (int x = 0; x < 160; x++) if (B1[(y * 160 + x) * 4 + 3]) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; } TICK7(); }
         HS = 0;
         CHECK(x1 >= x0 && x1 - x0 + 1 <= 31 && y1 - y0 + 1 <= 23, "192 일섬 크기 %d×%d(31×23 안)", x1 - x0 + 1, y1 - y0 + 1);
         /* 193 자리 — 맞은 몸(x 100) 가운데 4px 뒤가 가운데(때린 몸 x 70 쪽으로 안 넘침): 그린 화소 상자가 89~119 안 */
         CHECK(x0 >= 89 && x1 <= 119 && (x0 + x1) / 2 >= 100 && (x0 + x1) / 2 <= 106, "193 일섬 자리 x %d~%d(맞은 몸 100 · 때린 몸 70 — 89~119 안, 가운데 100~106 — 196 GPT 그림 기준점 17)", x0, x1);
      }
      {  /* 193 한 번에 하나 — 연달아 맞아도 이펙트 하나 */
         HIT7(0x50, 120, 3); HS = 0; TICK7(); CTRL(0) = 0; TICK7(); TICK7();
         HS = 3; COMBO(0) = 2; CTRL(0) = 0x0A; RAM[RA(0x5980) + 0xC6] = 112; TICK7();
         CHECK(callout_fx_count() == 1, "193 한 번에 하나(연달아 맞음 — 이펙트 %d)", callout_fx_count());
      }
      {  /* 193 타격 지점 높이: 서서 친 것 = 가슴(땅 위 24) · 점프 공격 = 머리(32) · 앉아 친 것 = 다리(12) — 그린 화소 가운데 y 로 */
         int cy[3];
         for (int v = 0; v < 3; v++) {
            callout_reset(); base(); RAM[RA(0x4E30) + 8] = 100; RAM[RA(0x4E30) + 0x40 + 8] = 70; RAM[RA(0x4E30) + 0xA] = 128;
            RAM[RA(0x4E30) + 0x40 + 0xA] = v == 1 ? 100 : 128; RAM[RA(0x5980) + 0x200 + 0x49] = v == 1 ? 0x80 : v == 2 ? 0x40 : 0x20;
            RAM[RA(0x4E30) + 0x40 + 0x0E] = 0x50; RAM[RA(0x4E30) + 0x40 + 0x0F] = 0; TICK7(); TICK7();
            HS = 3; COMBO(0) = 1; CTRL(0) = 0x0A; RAM[RA(0x5980) + 0xC6] = 120; TICK7();
            SNAP(B1); long sy = 0, n = 0;
            for (int y = 0; y < 152; y++) for (int x = 0; x < 160; x++) if (B1[(y * 160 + x) * 4 + 3]) { sy += y; n++; }
            cy[v] = n ? (int)(sy / n) : -1; HS = 0;
         }
         CHECK(abs(cy[0] - (128 - 24)) <= 1 && abs(cy[1] - (128 - 32)) <= 1 && abs(cy[2] - (128 - 12)) <= 1,
               "193 타격 지점 높이 서서 %d · 점프 %d · 앉아 %d(104 · 96 · 116 ±1 — 196 GPT 약 섬광은 화소 무게가 1px 위)", cy[0], cy[1], cy[2]);
      }
      {  /* 195 크게 — 일섬 47×35 안 · 작게보다 큼 · 같은 길이(12) · 약 섬광 23×23 안. 다음에 낼 것부터(이미 떠 있는 건 그대로) */
         int x0 = 999, y0 = 999, x1 = -1, y1 = -1, lifeb = 1;
         callout_fx_size(1);
         HIT7(0x68, 108, 10);
         for (int i = 0; i < 14; i++) { SNAP(B1); for (int y = 0; y < 152; y++) for (int x = 0; x < 160; x++) if (B1[(y * 160 + x) * 4 + 3]) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; } TICK7(); if (callout_fx_count()) lifeb++; else break; }
         HS = 0;
         CHECK(x1 >= x0 && x1 - x0 + 1 <= 47 && y1 - y0 + 1 <= 35 && x1 - x0 + 1 > 31 && lifeb == 12, "195 크게 일섬 %d×%d(31×23 보다 크고 47×35 안) · 길이 %d(12)", x1 - x0 + 1, y1 - y0 + 1, lifeb);
         x0 = y0 = 999; x1 = y1 = -1;
         HIT7(0x50, 120, 3);
         for (int i = 0; i < 6; i++) { SNAP(B1); for (int y = 0; y < 152; y++) for (int x = 0; x < 160; x++) if (B1[(y * 160 + x) * 4 + 3]) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; } if (i == 0) HS = 0; TICK7(); }
         CHECK(x1 >= x0 && x1 - x0 + 1 <= 23 && y1 - y0 + 1 <= 23 && x1 - x0 + 1 > 17, "195 크게 약 섬광 %d×%d(17 보다 크고 23×23 안)", x1 - x0 + 1, y1 - y0 + 1);
         callout_fx_size(0);
         HIT7(0x68, 108, 10);
         { int big = 0; SNAP(B1); TICK7(); TICK7(); SNAP(B1); for (int y = 0; y < 152; y++) for (int x = 0; x < 160; x++) if (B1[(y * 160 + x) * 4 + 3]) big++; HS = 0;
           CHECK(big > 0 && callout_fx_size_get() == 0, "195 작게로 돌리면 다음 것부터 작게(화소 %d)", big); }
      }
      {  /* 196 막타 — 게임이 막타 프레임에 판 끝남(0x5823)을 같이 세움: 그 프레임도 판정해 막타 일섬(빨강 0x23F)이 나야 */
         #define KRED(B, n) do { n = 0; for (int q = 0; q < 160 * 152; q++) if (B[q * 4] == 255 && B[q * 4 + 1] == 51 && B[q * 4 + 2] == 34) n++; } while (0)
         int nk = 0, nk2 = 0, nk3 = 0;
         callout_reset(); base(); RAM[RA(0x4E30) + 8] = 100; RAM[RA(0x4E30) + 0x40 + 8] = 70; RAM[RA(0x4E30) + 0xA] = 128; RAM[RA(0x4E30) + 0x40 + 0xA] = 128;
         RAM[RA(0x4E30) + 0x40 + 0x0E] = 0x50; RAM[RA(0x4E30) + 0x40 + 0x0F] = 0; TICK7(); TICK7();
         HS = 5; COMBO(0) = 1; CTRL(0) = 0x0A; RAM[RA(0x5980) + 0xC6] = 0; RAM[RA(0x5823)] = 136; TICK7();
         SNAP(B1); KRED(B1, nk);
         int n_after = callout_fx_count();
         for (int i = 0; i < 3; i++) TICK7();                       /* 판 끝 뒤엔 판정 안 함 — 그래도 이펙트는 끝까지 */
         SNAP(B2); KRED(B2, nk2);
         CHECK(nk > 20 && n_after == 1 && nk2 > 20, "196 막타 프레임에 판 끝남이 같이 서도 막타 일섬(빨강 화소 %d · 이펙트 %d · 3프레임 뒤 %d)", nk, n_after, nk2);
         /* 치트 «한 방» — 판정 뒤에 체력 0: 맞은 프레임엔 약 섬광, 다음 프레임(판 끝남)에 막타 일섬으로 바뀜 */
         callout_reset(); base(); RAM[RA(0x4E30) + 8] = 100; RAM[RA(0x4E30) + 0x40 + 8] = 70; RAM[RA(0x4E30) + 0xA] = 128; RAM[RA(0x4E30) + 0x40 + 0xA] = 128;
         RAM[RA(0x4E30) + 0x40 + 0x0E] = 0x50; RAM[RA(0x4E30) + 0x40 + 0x0F] = 0; TICK7(); TICK7();
         HS = 5; COMBO(0) = 1; CTRL(0) = 0x0A; RAM[RA(0x5980) + 0xC6] = 118; TICK7();
         SNAP(B1); KRED(B1, nk);
         RAM[RA(0x5980) + 0xC6] = 0; TICK7(); RAM[RA(0x5823)] = 136; TICK7();
         SNAP(B2); KRED(B2, nk3);
         CHECK(nk == 0 && nk3 > 20, "196 치트 한 방(판정 뒤 체력 0) — 약 섬광(빨강 %d) → 판 끝남 프레임에 막타 일섬(빨강 %d)", nk, nk3);
         /* 판 끝남이 섰는데 앞 프레임이 판정 중이 아니었으면(라운드 시작 전 등) 아무것도 안 냄 */
         callout_reset(); base(); RAM[RA(0x5823)] = 136; TICK7(); TICK7();
         HS = 5; COMBO(0) = 1; CTRL(0) = 0x0A; RAM[RA(0x5980) + 0xC6] = 0; TICK7();
         CHECK(callout_fx_count() == 0, "196 판 끝남이 먼저 서 있던 판(판정 밖)엔 이펙트 없음(%d)", callout_fx_count());
         RAM[RA(0x5823)] = 0;
      }
      {  /* 196 칼 부딪힘 — 게임 히트스톱이 길어도(30) 첫 컷은 6 프레임까지만 */
         int moved = -1;
         callout_reset(); base(); RAM[RA(0x4E30) + 8] = 100; RAM[RA(0x4E30) + 0x40 + 8] = 70; RAM[RA(0x4E30) + 0xA] = 128; RAM[RA(0x4E30) + 0x40 + 0xA] = 128;
         TICK7(); TICK7();
         HS = 30; TICK7();                                         /* 맞음·가드 없이 히트스톱만 = 칼 부딪힘 */
         SNAP(B1);
         for (int i = 1; i <= 12 && moved < 0; i++) { TICK7(); SNAP(B2); if (memcmp(B1, B2, sizeof B1)) moved = i; }
         CHECK(callout_fx_count() <= 1 && moved >= 5 && moved <= 7, "196 칼 부딪힘 첫 컷이 히트스톱 30 중 %d 프레임째에 넘어감(6 근처)", moved);
         HS = 0;
      }
      {  /* 196 크게 — 크게가 없던 다섯(T·G·C·D·K)도 작게보다 큼: 막타 일섬·칼 부딪힘으로 */
         int a[2], c2[2];
         for (int big = 0; big < 2; big++) {
            callout_fx_size(big);
            callout_reset(); base(); RAM[RA(0x4E30) + 8] = 100; RAM[RA(0x4E30) + 0x40 + 8] = 70; RAM[RA(0x4E30) + 0xA] = 128; RAM[RA(0x4E30) + 0x40 + 0xA] = 128;
            RAM[RA(0x4E30) + 0x40 + 0x0E] = 0x50; RAM[RA(0x4E30) + 0x40 + 0x0F] = 0; TICK7(); TICK7();
            HS = 5; COMBO(0) = 1; CTRL(0) = 0x0A; RAM[RA(0x5980) + 0xC6] = 0; RAM[RA(0x5823)] = 136; TICK7();
            SNAP(B1); { int x0 = 999, x1 = -1; for (int q = 0; q < 160 * 152; q++) if (B1[q * 4 + 3]) { if (q % 160 < x0) x0 = q % 160; if (q % 160 > x1) x1 = q % 160; } a[big] = x1 - x0 + 1; }
            RAM[RA(0x5823)] = 0;
            callout_reset(); base(); RAM[RA(0x4E30) + 8] = 100; RAM[RA(0x4E30) + 0x40 + 8] = 70; RAM[RA(0x4E30) + 0xA] = 128; RAM[RA(0x4E30) + 0x40 + 0xA] = 128;
            TICK7(); TICK7(); HS = 30; TICK7();
            SNAP(B1); { int x0 = 999, x1 = -1; for (int q = 0; q < 160 * 152; q++) if (B1[q * 4 + 3]) { if (q % 160 < x0) x0 = q % 160; if (q % 160 > x1) x1 = q % 160; } c2[big] = x1 - x0 + 1; }
            HS = 0;
         }
         callout_fx_size(0);
         CHECK(a[1] > a[0] && c2[1] > c2[0] && a[0] > 0 && c2[0] > 0, "196 크게 막타 일섬 폭 %d > 작게 %d · 칼 부딪힘 폭 %d > %d", a[1], a[0], c2[1], c2[0]);
      }
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 0);
   }
   /* ── 191 코어가 그리면(g_core_hits) 앱 쪽은 꺼진 채 코어 옵션만 바뀜 ── */
   {
      g_core_hits = 7;
      Java_com_dudu_pocketcore_Emu_nativeSetCallouts(NULL, NULL, 1);
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 1);
      Java_com_dudu_pocketcore_Emu_nativeSetHitFlash(NULL, NULL, 0);
      CHECK(!callout_enabled() && callout_fx_mode_get() == FXM_OFF, "191 코어가 그리면 앱 라벨·이펙트 끔");
      CHECK(!strcmp(opt_get("ngp_hit_callout", "?"), "pixel") && !strcmp(opt_get("ngp_hit_fx", "?"), "translucent") && !strcmp(opt_get("ngp_hit_fx_size", "?"), "small") && !strcmp(opt_get("ngp_hit_flash", "?"), "disabled") && g_var_dirty,
            "191·195 코어 옵션 ngp_hit_* = 설정(%s %s %s %s)", opt_get("ngp_hit_callout", "?"), opt_get("ngp_hit_fx", "?"), opt_get("ngp_hit_fx_size", "?"), opt_get("ngp_hit_flash", "?"));
      g_var_dirty = 0;
      Java_com_dudu_pocketcore_Emu_nativeSetHitStyle(NULL, NULL, 3, 3, 1);
      CHECK(!strcmp(opt_get("ngp_hit_callout", "?"), "inkband") && !strcmp(opt_get("ngp_hit_fx", "?"), "under") && !strcmp(opt_get("ngp_hit_fx_size", "?"), "big") && g_var_dirty,
            "195 모양 → 코어 옵션(먹 띠 · 때린 몸 밑 · 크게 — %s %s %s)", opt_get("ngp_hit_callout", "?"), opt_get("ngp_hit_fx", "?"), opt_get("ngp_hit_fx_size", "?"));
      Java_com_dudu_pocketcore_Emu_nativeSetHitStyle(NULL, NULL, 1, 2, 0);
      CHECK(!strcmp(opt_get("ngp_hit_callout", "?"), "brush") && !strcmp(opt_get("ngp_hit_fx", "?"), "opaque") && !strcmp(opt_get("ngp_hit_fx_size", "?"), "small"), "195 모양 → 코어 옵션(붓 한자 · 불투명 · 작게)");
      Java_com_dudu_pocketcore_Emu_nativeSetHitStyle(NULL, NULL, 9, 0, 0);
      CHECK(!strcmp(opt_get("ngp_hit_callout", "?"), "pixel") && !strcmp(opt_get("ngp_hit_fx", "?"), "translucent"), "195 이상한 모양 번호 → 기본(큰 한글 도트 · 반투명)");
      g_var_dirty = 0;
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 1);
      CHECK(!g_var_dirty, "191 같은 값이면 코어에 다시 읽으라 안 함");
      g_core_hits = 0;
      Java_com_dudu_pocketcore_Emu_nativeSetCallouts(NULL, NULL, 1);
      CHECK(callout_enabled() && callout_fx_mode_get() == FXM_ON, "191 옛 코어면 앱이 그림");
      Java_com_dudu_pocketcore_Emu_nativeSetCallouts(NULL, NULL, 0);
      Java_com_dudu_pocketcore_Emu_nativeSetHitFx(NULL, NULL, 0);
      {  /* 옵션 파일 → ngp_hit_* (코어가 처음 읽는 값) */
         char tp[] = "/tmp/gltest_optsXXXXXX"; int fd = mkstemp(tp); FILE *o = fdopen(fd, "w");
         fprintf(o, "pocketcore_callout=disabled\npocketcore_hitfx=on\n"); fclose(o);
         load_options(tp); unlink(tp);
         CHECK(!strcmp(opt_get("ngp_hit_callout", "?"), "disabled") && !strcmp(opt_get("ngp_hit_fx", "?"), "translucent") && !strcmp(opt_get("ngp_hit_fx_size", "?"), "small") && !strcmp(opt_get("ngp_hit_flash", "?"), "disabled"),
               "191 옵션 파일 → 코어 옵션(라벨 끔 · 옛 «on» = 반투명 · 193 번쩍은 늘 끔)");
      }
      {  /* 195 옛 «enabled» = 큰 한글 도트 · 새 값 그대로 */
         char tp[] = "/tmp/gltest_optsXXXXXX"; int fd = mkstemp(tp); FILE *o = fdopen(fd, "w");
         fprintf(o, "pocketcore_callout=enabled\npocketcore_hitfx=opaque\npocketcore_hitfx_size=big\n"); fclose(o);
         load_options(tp); unlink(tp);
         CHECK(!strcmp(opt_get("ngp_hit_callout", "?"), "pixel") && !strcmp(opt_get("ngp_hit_fx", "?"), "opaque") && !strcmp(opt_get("ngp_hit_fx_size", "?"), "big"),
               "195 옵션 파일 → 코어 옵션(옛 enabled = 큰 한글 도트 · 불투명 · 크게 — %s %s %s)", opt_get("ngp_hit_callout", "?"), opt_get("ngp_hit_fx", "?"), opt_get("ngp_hit_fx_size", "?"));
         o = NULL; (void)o;
         char tp2[] = "/tmp/gltest_optsXXXXXX"; fd = mkstemp(tp2); o = fdopen(fd, "w");
         fprintf(o, "pocketcore_callout=plaque\npocketcore_hitfx=off\n"); fclose(o);
         load_options(tp2); unlink(tp2);
         CHECK(!strcmp(opt_get("ngp_hit_callout", "?"), "plaque") && !strcmp(opt_get("ngp_hit_fx", "?"), "disabled") && !strcmp(opt_get("ngp_hit_fx_size", "?"), "small"),
               "195 옵션 파일 → 코어 옵션(명패 · 이펙트 끔)");
      }
   }
   fprintf(stderr, "%s (%d 실패)\n", fails ? "실패" : "통과", fails);
   return fails ? 1 : 0;
}
