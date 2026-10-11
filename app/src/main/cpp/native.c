/* PocketCore - a minimal, menu-free libretro frontend for Android.
 * All emulation, video and audio live here; Java only supplies a surface,
 * an input bitmask and file paths. */

#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <pthread.h>
#include <android/log.h>
#include <GLES2/gl2.h>
#include <aaudio/AAudio.h>
#include <time.h>
#include "libretro.h"
#include "framegen.h"
#include "display_shaders.h"
#include <math.h>

#define TAG "PocketCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* core handle                                                         */
/* ------------------------------------------------------------------ */
static void *g_lib = NULL;

static void   (*c_set_environment)(retro_environment_t);
static void   (*c_set_video_refresh)(retro_video_refresh_t);
static void   (*c_set_audio_sample)(retro_audio_sample_t);
static void   (*c_set_audio_sample_batch)(retro_audio_sample_batch_t);
static void   (*c_set_input_poll)(retro_input_poll_t);
static void   (*c_set_input_state)(retro_input_state_t);
static void   (*c_init)(void);
static void   (*c_deinit)(void);
static void   (*c_run)(void);
static void   (*c_reset)(void);
static bool   (*c_load_game)(const struct retro_game_info *);
static void   (*c_unload_game)(void);
static void   (*c_get_system_info)(struct retro_system_info *);
static void   (*c_get_system_av_info)(struct retro_system_av_info *);
static void   (*c_set_controller_port_device)(unsigned, unsigned);
static void  *(*c_get_memory_data)(unsigned);
static size_t (*c_get_memory_size)(unsigned);
static size_t (*c_serialize_size)(void);
static bool   (*c_serialize)(void *, size_t);
static bool   (*c_unserialize)(const void *, size_t);

/* ------------------------------------------------------------------ */
/* state                                                               */
/* ------------------------------------------------------------------ */
static char g_sys_dir[512];
static char g_save_dir[512];
static char g_rom_path[512];

static enum retro_pixel_format g_pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;
static struct retro_system_av_info g_av;

static uint8_t  *g_fb = NULL;          /* RGBA8888 staging buffer      */
static unsigned  g_fb_cap = 0;
static unsigned  g_fb_w = 0, g_fb_h = 0;
static int       g_fb_dirty = 0;

/* 프레임 생성(framegen.c) — 직전 코어 프레임 사본과 만들어 둔 중간 그림 */
static uint8_t  *g_prev = NULL, *g_mid = NULL;
static unsigned  g_fg_cap = 0;
static unsigned  g_prev_w = 0, g_prev_h = 0;
static int       g_fg_mode = FG_OFF;     /* Java 가 설정에서 넣는다 */
static int       g_mid_ready = 0, g_mid_dirty = 0;
static double    g_vsync_ema = 0.0;      /* 실측 vsync 간격(초) — 120Hz 인지는 이걸로 판단 */
static double    g_last_vsync = 0.0;
static float     g_panel_hz_hint = 0.f;  /* Java 가 알려 준 Display.getRefreshRate() — 실측 전 대용 */

/* 패널 주사율 — 코어의 GET_TARGET_REFRESH_RATE 에 답한다(ss2 코어 프레임 생성 「자동」이 이걸 본다).
   vsync 실측값을 흔한 주사율로 맞춰 돌려준다(120Hz 면 120.0, 60 이면 60.0). 실측 전이면 Java 값. */
static float panel_hz(void)
{
   static const float std_hz[] = { 48.f, 50.f, 60.f, 72.f, 90.f, 96.f, 120.f, 144.f, 165.f, 240.f };
   float hz = g_vsync_ema > 0.0 ? (float)(1.0 / g_vsync_ema) : g_panel_hz_hint;
   if (hz <= 0.f) return 60.f;
   float best = std_hz[0], bd = 1e9f;
   for (unsigned i = 0; i < sizeof std_hz / sizeof std_hz[0]; i++) {
      float d = hz > std_hz[i] ? hz - std_hz[i] : std_hz[i] - hz;
      if (d < bd) { bd = d; best = std_hz[i]; }
   }
   return best;
}

static volatile int32_t g_input = 0;   /* bitmask of RETRO_DEVICE_ID_* */
static int g_loaded = 0;

/* GL */
static GLuint g_prog = 0, g_tex = 0, g_tex_mid = 0;
static unsigned g_tex_w = 0, g_tex_h = 0;   /* g_tex 에 지금 잡힌 크기 — 같으면 glTexSubImage2D 로 덮기만 */
static GLint  a_pos, a_uv, u_tex;
static int    g_vw = 1, g_vh = 1;
static int    g_integer_scale = 1;

/* 화면 표시 — 업스케일러(도트를 키우는 방식) 하나 + 필터(덧입히기) 여럿, 둘 다 세기(%).
   Java 가 설정에서 nativeSetDisplay 로 넣는다(GL 스레드에서). 전부 0 이면 예전 단순 셰이더 그대로. */
typedef struct {
   GLuint prog; int failed;
   GLint a_pos, a_uv, u_tex, u_size, u_out, u_mix, u_grid, u_scan, u_color, u_soft;
} disp_prog;
static disp_prog g_dp[DISP_N_UP];
static int g_d_up = 0, g_d_mix = 100, g_d_grid = 0, g_d_scan = 0, g_d_ghost = 0, g_d_color = 0, g_d_soft = 0;
/* 잔상(LCD 응답 느림 흉내) — 셰이더가 아니라 CPU 누적: 화면에 낸 그림을 지수 평균한다.
   acc 는 값×256(16비트)이라 끝까지 따라간다(8비트로 섞으면 희미한 찌꺼기가 남는다). */
static uint16_t *g_gh = NULL;
static uint8_t  *g_gh_out = NULL;
static unsigned  g_gh_cap = 0, g_gh_w = 0, g_gh_h = 0;
static int       g_gh_valid = 0;
static GLuint    g_tex_gh = 0;

/* audio ring buffer (stereo int16) */
#define ARING_FRAMES 16384
static int16_t g_ring[ARING_FRAMES * 2];
static volatile uint32_t g_rd = 0, g_wr = 0;
static AAudioStream *g_stream = NULL;

/* core options loaded from a plain key=value text file (no menus) */
#define MAX_OPTS 64
static struct { char k[64]; char v[96]; } g_opt[MAX_OPTS];
static int g_nopt = 0;
static int g_var_dirty = 0;   /* 런타임 옵션 변경 — 코어가 다음 프레임에 다시 읽는다 */

/* ------------------------------------------------------------------ */
/* helpers                                                             */
/* ------------------------------------------------------------------ */
static void ensure_fb(unsigned w, unsigned h)
{
   unsigned need = w * h * 4;
   if (need > g_fb_cap) {
      free(g_fb);
      g_fb = (uint8_t *)malloc(need);
      g_fb_cap = need;
   }
}

static void core_log(enum retro_log_level lvl, const char *fmt, ...)
{
   char buf[1024];
   va_list ap; va_start(ap, fmt);
   vsnprintf(buf, sizeof(buf), fmt, ap);
   va_end(ap);
   __android_log_print(lvl >= RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                       "core", "%s", buf);
}

static void load_options(const char *path)
{
   FILE *f = fopen(path, "r");
   g_nopt = 0;
   if (!f) return;
   char line[256];
   while (fgets(line, sizeof(line), f) && g_nopt < MAX_OPTS) {
      char *eq;
      if (line[0] == '#' || line[0] == '\n') continue;
      eq = strchr(line, '=');
      if (!eq) continue;
      *eq = 0;
      snprintf(g_opt[g_nopt].k, sizeof(g_opt[g_nopt].k), "%s", line);
      snprintf(g_opt[g_nopt].v, sizeof(g_opt[g_nopt].v), "%s", eq + 1);
      /* trim trailing whitespace/newline */
      for (char *p = g_opt[g_nopt].v + strlen(g_opt[g_nopt].v) - 1;
           p >= g_opt[g_nopt].v && (*p == '\n' || *p == '\r' || *p == ' '); --p) *p = 0;
      g_nopt++;
   }
   fclose(f);
   LOGI("loaded %d core options", g_nopt);
}

/* ------------------------------------------------------------------ */
/* libretro callbacks                                                  */
/* ------------------------------------------------------------------ */
static bool cb_environment(unsigned cmd, void *data)
{
   switch (cmd) {
   case RETRO_ENVIRONMENT_GET_CAN_DUPE:
      *(bool *)data = true; return true;

   case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
      g_pixfmt = *(const enum retro_pixel_format *)data;
      LOGI("pixel format = %d", (int)g_pixfmt);
      return true;

   case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
      *(const char **)data = g_sys_dir; return true;

   case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
   case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
      *(const char **)data = g_save_dir; return true;

   case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
      ((struct retro_log_callback *)data)->log = core_log; return true;

   case RETRO_ENVIRONMENT_GET_VARIABLE: {
      struct retro_variable *v = (struct retro_variable *)data;
      for (int i = 0; i < g_nopt; i++) {
         if (!strcmp(g_opt[i].k, v->key)) { v->value = g_opt[i].v; return true; }
      }
      v->value = NULL;
      return false;
   }

   case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
      *(bool *)data = g_var_dirty ? true : false;
      g_var_dirty = 0;
      return true;

   case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
      *(unsigned *)data = 0;   /* keep cores on the simple v0 path */
      return true;

   case RETRO_ENVIRONMENT_SET_VARIABLES:
   case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
   case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
   case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
   case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
   case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
   case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
   case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
   case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
   case RETRO_ENVIRONMENT_SET_MESSAGE:
   case RETRO_ENVIRONMENT_SET_MESSAGE_EXT:
   case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
      return true;

   case RETRO_ENVIRONMENT_SET_GEOMETRY:
      g_av.geometry = *(const struct retro_game_geometry *)data; return true;

   case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
      g_av = *(const struct retro_system_av_info *)data; return true;

   case RETRO_ENVIRONMENT_GET_LANGUAGE:
      *(unsigned *)data = 0; return true;

   case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
      *(int *)data = 3; return true;

   case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
      *(float *)data = panel_hz(); return true;

   case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
      *(bool *)data = false; return true;

   case RETRO_ENVIRONMENT_GET_LIBRETRO_PATH:
      *(const char **)data = g_sys_dir; return true;

   default:
      return false;
   }
}

static void cb_video(const void *data, unsigned w, unsigned h, size_t pitch)
{
   if (!data || !w || !h) return;      /* frame dupe */
   ensure_fb(w, h);
   if (!g_fb) return;
   g_fb_w = w; g_fb_h = h;

   uint8_t *dst = g_fb;
   if (g_pixfmt == RETRO_PIXEL_FORMAT_RGB565) {
      for (unsigned y = 0; y < h; y++) {
         const uint16_t *src = (const uint16_t *)((const uint8_t *)data + y * pitch);
         for (unsigned x = 0; x < w; x++) {
            uint16_t p = src[x];
            unsigned r = (p >> 11) & 0x1f, g = (p >> 5) & 0x3f, b = p & 0x1f;
            *dst++ = (uint8_t)((r << 3) | (r >> 2));
            *dst++ = (uint8_t)((g << 2) | (g >> 4));
            *dst++ = (uint8_t)((b << 3) | (b >> 2));
            *dst++ = 0xff;
         }
      }
   } else if (g_pixfmt == RETRO_PIXEL_FORMAT_0RGB1555) {
      for (unsigned y = 0; y < h; y++) {
         const uint16_t *src = (const uint16_t *)((const uint8_t *)data + y * pitch);
         for (unsigned x = 0; x < w; x++) {
            uint16_t p = src[x];
            unsigned r = (p >> 10) & 0x1f, g = (p >> 5) & 0x1f, b = p & 0x1f;
            *dst++ = (uint8_t)((r << 3) | (r >> 2));
            *dst++ = (uint8_t)((g << 3) | (g >> 2));
            *dst++ = (uint8_t)((b << 3) | (b >> 2));
            *dst++ = 0xff;
         }
      }
   } else { /* XRGB8888 */
      for (unsigned y = 0; y < h; y++) {
         const uint32_t *src = (const uint32_t *)((const uint8_t *)data + y * pitch);
         for (unsigned x = 0; x < w; x++) {
            uint32_t p = src[x];
            *dst++ = (uint8_t)((p >> 16) & 0xff);
            *dst++ = (uint8_t)((p >> 8) & 0xff);
            *dst++ = (uint8_t)(p & 0xff);
            *dst++ = 0xff;
         }
      }
   }
   g_fb_dirty = 1;
}

static size_t cb_audio_batch(const int16_t *data, size_t frames)
{
   uint32_t wr = g_wr;
   for (size_t i = 0; i < frames; i++) {
      uint32_t next = (wr + 1) % ARING_FRAMES;
      if (next == g_rd) break;         /* full: drop (keeps latency bounded) */
      g_ring[wr * 2]     = data[i * 2];
      g_ring[wr * 2 + 1] = data[i * 2 + 1];
      wr = next;
   }
   g_wr = wr;
   return frames;
}

static void cb_audio_sample(int16_t l, int16_t r)
{
   int16_t f[2] = { l, r };
   cb_audio_batch(f, 1);
}

static void cb_input_poll(void) { }

static int16_t cb_input_state(unsigned port, unsigned device, unsigned index, unsigned id)
{
   (void)index;
   if (port != 0 || device != RETRO_DEVICE_JOYPAD || id > 15) return 0;
   return (g_input >> id) & 1;
}

/* ------------------------------------------------------------------ */
/* audio                                                               */
/* ------------------------------------------------------------------ */
static aaudio_data_callback_result_t audio_cb(AAudioStream *s, void *ud,
                                              void *audioData, int32_t numFrames)
{
   (void)s; (void)ud;
   int16_t *out = (int16_t *)audioData;
   uint32_t rd = g_rd;
   for (int32_t i = 0; i < numFrames; i++) {
      if (rd == g_wr) {                /* underrun: hold silence */
         out[i * 2] = 0; out[i * 2 + 1] = 0;
      } else {
         out[i * 2]     = g_ring[rd * 2];
         out[i * 2 + 1] = g_ring[rd * 2 + 1];
         rd = (rd + 1) % ARING_FRAMES;
      }
   }
   g_rd = rd;
   return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

/* 스트림이 죽었다는 표시 — 다른 앱이 장치를 가져가거나 라우팅이 바뀌면
   AAudio 가 에러 콜백으로 알려 준다. 콜백 안에서는 그 스트림을 닫으면 안 되므로
   표시만 남기고, 프레임 루프(GL 스레드)가 새 스트림으로 다시 연다. */
static volatile int g_audio_dead = 0;
static int g_audio_rate = 44100;

static void audio_err_cb(AAudioStream *s, void *ud, aaudio_result_t err)
{
   (void)s; (void)ud;
   if (err == AAUDIO_ERROR_DISCONNECTED) g_audio_dead = 1;
}

static void audio_start(int rate)
{
   AAudioStreamBuilder *b = NULL;
   g_audio_rate = rate;
   g_audio_dead = 0;
   if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return;
   AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
   AAudioStreamBuilder_setChannelCount(b, 2);
   AAudioStreamBuilder_setSampleRate(b, rate);
   AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
   AAudioStreamBuilder_setDataCallback(b, audio_cb, NULL);
   AAudioStreamBuilder_setErrorCallback(b, audio_err_cb, NULL);
   if (AAudioStreamBuilder_openStream(b, &g_stream) == AAUDIO_OK)
      AAudioStream_requestStart(g_stream);
   AAudioStreamBuilder_delete(b);
   LOGI("audio started @%d Hz (%s)", rate, g_stream ? "ok" : "failed");
}

static void audio_stop(void)
{
   if (!g_stream) return;
   AAudioStream_requestStop(g_stream);
   AAudioStream_close(g_stream);
   g_stream = NULL;
}

/* ------------------------------------------------------------------ */
/* GL                                                                  */
/* ------------------------------------------------------------------ */
static const char *VS =
   "attribute vec2 aPos;attribute vec2 aUV;varying vec2 vUV;"
   "void main(){vUV=aUV;gl_Position=vec4(aPos,0.0,1.0);}";
static const char *FS =
   "precision mediump float;varying vec2 vUV;uniform sampler2D uTex;"
   "void main(){gl_FragColor=texture2D(uTex,vUV);}";

static GLuint compile(GLenum type, const char *src)
{
   GLuint s = glCreateShader(type);
   glShaderSource(s, 1, &src, NULL);
   glCompileShader(s);
   GLint ok = 0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
   if (!ok) { char log[512]; glGetShaderInfoLog(s, 512, NULL, log); LOGE("shader: %s", log); }
   return s;
}

static GLuint compile_ok(GLenum type, const char *src)
{
   GLuint sh = compile(type, src);
   GLint ok = 0; glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
   if (!ok) { glDeleteShader(sh); return 0; }
   return sh;
}

/* 업스케일러 k 의 프로그램 — 처음 쓸 때 만든다. 못 만들면(옛 GPU) 0 → 단순 셰이더로 그린다. */
static disp_prog *disp_get(int k)
{
   if (k < 0 || k >= DISP_N_UP) k = 0;
   disp_prog *d = &g_dp[k];
   if (d->prog || d->failed) return d->prog ? d : NULL;
   GLuint vs = compile_ok(GL_VERTEX_SHADER, VS), fs = compile_ok(GL_FRAGMENT_SHADER, FS_UP[k]);
   if (!vs || !fs) { d->failed = 1; LOGE("display shader %d: compile failed", k); return NULL; }
   GLuint pr = glCreateProgram();
   glAttachShader(pr, vs); glAttachShader(pr, fs);
   glLinkProgram(pr);
   glDeleteShader(vs); glDeleteShader(fs);
   GLint ok = 0; glGetProgramiv(pr, GL_LINK_STATUS, &ok);
   if (!ok) {
      char log[512]; glGetProgramInfoLog(pr, 512, NULL, log);
      LOGE("display shader %d: link failed: %s", k, log);
      glDeleteProgram(pr); d->failed = 1; return NULL;
   }
   d->prog = pr;
   d->a_pos = glGetAttribLocation(pr, "aPos");   d->a_uv = glGetAttribLocation(pr, "aUV");
   d->u_tex = glGetUniformLocation(pr, "uTex");  d->u_size = glGetUniformLocation(pr, "uSize");
   d->u_out = glGetUniformLocation(pr, "uOut");  d->u_mix = glGetUniformLocation(pr, "uUpMix");
   d->u_grid = glGetUniformLocation(pr, "uGrid"); d->u_scan = glGetUniformLocation(pr, "uScan");
   d->u_color = glGetUniformLocation(pr, "uColor"); d->u_soft = glGetUniformLocation(pr, "uSoft");
   LOGI("display shader %d ready", k);
   return d;
}

/* 잔상 — src(지금 화면에 낼 그림)를 누적에 섞어 g_gh_out 으로. 세기는 «60Hz 한 프레임에 이전 그림이 남는 몫»으로
   정하고, 실제 vsync 간격만큼 거듭제곱해 120Hz 화면에서도 같은 길이로 남게 한다. */
static const uint8_t *ghost_apply(const uint8_t *src, unsigned w, unsigned h)
{
   unsigned n = w * h * 4;
   if (n > g_gh_cap) {
      free(g_gh); free(g_gh_out);
      g_gh = (uint16_t *)malloc(n * sizeof(uint16_t)); g_gh_out = (uint8_t *)malloc(n);
      g_gh_cap = (g_gh && g_gh_out) ? n : 0; g_gh_valid = 0;
      if (!g_gh_cap) return src;
   }
   if (!g_gh_valid || g_gh_w != w || g_gh_h != h) {
      for (unsigned i = 0; i < n; i++) g_gh[i] = (uint16_t)(src[i] << 8);
      g_gh_w = w; g_gh_h = h; g_gh_valid = 1;
   }
   double dv = (g_vsync_ema > 0.002 && g_vsync_ema < 0.1) ? g_vsync_ema : 1.0 / 60.0;
   double k60 = g_d_ghost / 100.0 * 0.80;                   /* 100% = 60Hz 한 프레임에 80% 남음 */
   double k = pow(k60, dv * 60.0);
   int a = (int)((1.0 - k) * 65536.0);                       /* 새 그림이 들어오는 몫 */
   for (unsigned i = 0; i < n; i += 4) {
      for (int c = 0; c < 3; c++) {
         int acc = g_gh[i + c];
         acc += (int)(((int64_t)(((int)src[i + c] << 8) - acc) * a) >> 16);
         if (acc < 0) acc = 0; else if (acc > 65535) acc = 65535;
         g_gh[i + c] = (uint16_t)acc;
         g_gh_out[i + c] = (uint8_t)((acc + 128) >> 8 > 255 ? 255 : (acc + 128) >> 8);
      }
      g_gh_out[i + 3] = 255;
   }
   return g_gh_out;
}

static void gl_setup(void)
{
   GLuint vs = compile(GL_VERTEX_SHADER, VS);
   GLuint fs = compile(GL_FRAGMENT_SHADER, FS);
   g_prog = glCreateProgram();
   glAttachShader(g_prog, vs);
   glAttachShader(g_prog, fs);
   glLinkProgram(g_prog);
   a_pos = glGetAttribLocation(g_prog, "aPos");
   a_uv  = glGetAttribLocation(g_prog, "aUV");
   u_tex = glGetUniformLocation(g_prog, "uTex");

   glGenTextures(1, &g_tex);
   g_tex_w = g_tex_h = 0;
   glBindTexture(GL_TEXTURE_2D, g_tex);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
   glGenTextures(1, &g_tex_mid);
   glBindTexture(GL_TEXTURE_2D, g_tex_mid);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
   glGenTextures(1, &g_tex_gh);
   glBindTexture(GL_TEXTURE_2D, g_tex_gh);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
   memset(g_dp, 0, sizeof(g_dp));   /* 새 컨텍스트 — 예전 프로그램은 이미 사라졌다. 쓸 때 다시 만든다 */
   g_gh_valid = 0;
   g_mid_dirty = g_mid_ready;   /* 표면이 새로 생기면 텍스처도 새것 — 다시 올린다 */
   g_fb_dirty = 1;
   glClearColor(0.f, 0.f, 0.f, 1.f);
}

static void gl_draw(int show_mid)
{
   if (!g_fb || !g_fb_w) return;

   glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
   glBindTexture(GL_TEXTURE_2D, g_tex);
   if (g_fb_dirty) {
      /* 서기 그리기(4배, 640×608) 는 한 장이 1.5MB — 크기가 같으면 텍스처를 다시 잡지 않고 덮기만 */
      if (g_tex_w != g_fb_w || g_tex_h != g_fb_h) {
         glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, g_fb_w, g_fb_h, 0,
                      GL_RGBA, GL_UNSIGNED_BYTE, g_fb);
         g_tex_w = g_fb_w; g_tex_h = g_fb_h;
      } else
         glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, g_fb_w, g_fb_h, GL_RGBA, GL_UNSIGNED_BYTE, g_fb);
      g_fb_dirty = 0;
   }
   if (show_mid && g_mid_dirty) {
      glBindTexture(GL_TEXTURE_2D, g_tex_mid);
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, g_fb_w, g_fb_h, 0,
                   GL_RGBA, GL_UNSIGNED_BYTE, g_mid);
      g_mid_dirty = 0;
   }

   /* aspect-correct, integer-scaled viewport.
      기둥(사이드 아트) 프레임은 288폭이지만 **게임 몫은 160** — 크기는 게임 160폭으로 정하고 기둥은
      남는 옆자리에만 그린다(넘치면 잘린다). 288 전체를 화면 폭에 맞추면 게임이 반토막 나고 아래가
      텅 빈다는 제보(유저: 「양쪽에 붙이면 될 걸 표시영역을 아래로 늘리지 마라」). */
   /* 서기 그리기(코어 패치 95)를 켜면 코어가 4배(640×608)로 내보낸다 — 화면 상자·정수배·필터는 «게임 칸»(160×152) 기준 */
   int sub = (g_fb_w >= 320 && g_fb_w % 160 == 0 && g_fb_h == 152u * (g_fb_w / 160)) ? (int)(g_fb_w / 160) : 1;
   int lw = (int)g_fb_w / sub, lh = (int)g_fb_h / sub;
   float ar = g_av.geometry.aspect_ratio > 0.f
            ? g_av.geometry.aspect_ratio : (float)lw / (float)lh;
   int game_w = (lw > 160 && lw <= 320) ? 160 : lw;   /* NGP 게임 화면은 늘 160 */
   int dw, dh;
   if (g_integer_scale) {
      int s = g_vw / game_w;
      int sy = g_vh / lh;
      if (sy < s) s = sy;
      if (s < 1) s = 1;
      dw = lw * s; dh = lh * s;
   } else {
      dh = g_vh; dw = (int)(g_vh * ar);
      int game_dw = dh * game_w / lh;
      if (game_dw > g_vw) { dh = g_vw * lh / game_w; dw = (int)(dh * ar); }
   }
   {  /* 위로 붙인다(유틸 줄 여백만 남김) — 가운데 두면 패드에 깔린다(제보) */
      int y0 = g_vh - dh - (int)(g_vh * 0.07f);
      if (g_vw > g_vh) y0 = (g_vh - dh) / 2;   /* 가로 화면(게임기)에선 세로 가운데 */
      if (y0 < 0) y0 = 0;
      { static int ldw=-1, ldh=-1, lvw=-1; if (dw!=ldw||dh!=ldh||g_vw!=lvw) { LOGI("viewport %d x %d in %d x %d (fb %u x %u, game_w %d, int %d)", dw, dh, g_vw, g_vh, g_fb_w, g_fb_h, game_w, g_integer_scale); ldw=dw; ldh=dh; lvw=g_vw; } }
      glViewport((g_vw - dw) / 2, y0, dw, dh);
   }

   static const GLfloat pos[] = { -1,-1,  1,-1, -1, 1,  1, 1 };
   static const GLfloat uv[]  = {  0, 1,  1, 1,  0, 0,  1, 0 };

   GLuint tex = show_mid ? g_tex_mid : g_tex;
   if (g_d_ghost > 0) {           /* 잔상 — 화면에 낼 그림(중간 그림 포함)을 누적해 따로 올린다 */
      const uint8_t *gs = ghost_apply(show_mid ? g_mid : g_fb, g_fb_w, g_fb_h);
      glBindTexture(GL_TEXTURE_2D, g_tex_gh);
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, g_fb_w, g_fb_h, 0, GL_RGBA, GL_UNSIGNED_BYTE, gs);
      tex = g_tex_gh;
   } else g_gh_valid = 0;

   int up = (g_d_mix > 0 && sub == 1) ? g_d_up : 0;    /* 업스케일러는 도트 한 칸 = 텍셀 한 칸일 때만 — 4배 그림엔 쉰다 */
   disp_prog *d = (up || g_d_grid || g_d_scan || g_d_color || g_d_soft) ? disp_get(up) : NULL;
   GLint ap = a_pos, au = a_uv;
   if (d) {
      glUseProgram(d->prog);
      glUniform1i(d->u_tex, 0);
      glUniform2f(d->u_size, (float)lw, (float)lh);      /* 격자·스캔라인·번짐은 게임 칸 기준 */
      glUniform2f(d->u_out, (float)dw, (float)dh);
      glUniform1f(d->u_mix, up ? g_d_mix / 100.f : 0.f);
      glUniform1f(d->u_grid, g_d_grid / 100.f);
      glUniform1f(d->u_scan, g_d_scan / 100.f);
      glUniform1f(d->u_color, g_d_color / 100.f);
      glUniform1f(d->u_soft, g_d_soft / 100.f);
      ap = d->a_pos; au = d->a_uv;
   } else {
      glUseProgram(g_prog);
      glUniform1i(u_tex, 0);
   }
   glActiveTexture(GL_TEXTURE0);
   glBindTexture(GL_TEXTURE_2D, tex);
   glVertexAttribPointer(ap, 2, GL_FLOAT, GL_FALSE, 0, pos);
   glVertexAttribPointer(au, 2, GL_FLOAT, GL_FALSE, 0, uv);
   glEnableVertexAttribArray(ap);
   glEnableVertexAttribArray(au);
   glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

/* ------------------------------------------------------------------ */
/* sram                                                                */
/* ------------------------------------------------------------------ */
static void sram_path(char *out, size_t n)
{
   const char *base = strrchr(g_rom_path, '/');
   base = base ? base + 1 : g_rom_path;
   snprintf(out, n, "%s/%s.srm", g_save_dir, base);
}

static void sram_save(void)
{
   if (!c_get_memory_data) return;
   void *m = c_get_memory_data(RETRO_MEMORY_SAVE_RAM);
   size_t sz = c_get_memory_size(RETRO_MEMORY_SAVE_RAM);
   if (!m || !sz) return;
   char p[600]; sram_path(p, sizeof(p));
   FILE *f = fopen(p, "wb");
   if (!f) return;
   fwrite(m, 1, sz, f); fclose(f);
   LOGI("sram saved (%zu bytes)", sz);
}

static void sram_load(void)
{
   if (!c_get_memory_data) return;
   void *m = c_get_memory_data(RETRO_MEMORY_SAVE_RAM);
   size_t sz = c_get_memory_size(RETRO_MEMORY_SAVE_RAM);
   if (!m || !sz) return;
   char p[600]; sram_path(p, sizeof(p));
   FILE *f = fopen(p, "rb");
   if (!f) return;
   fread(m, 1, sz, f); fclose(f);
   LOGI("sram loaded");
}

/* ------------------------------------------------------------------ */
/* symbol binding                                                      */
/* ------------------------------------------------------------------ */
#define SYM(v, name) do { *(void **)&v = dlsym(g_lib, name); \
   if (!v) { LOGE("missing symbol %s", name); return 0; } } while (0)

static int bind_core(void)
{
   SYM(c_set_environment,        "retro_set_environment");
   SYM(c_set_video_refresh,      "retro_set_video_refresh");
   SYM(c_set_audio_sample,       "retro_set_audio_sample");
   SYM(c_set_audio_sample_batch, "retro_set_audio_sample_batch");
   SYM(c_set_input_poll,         "retro_set_input_poll");
   SYM(c_set_input_state,        "retro_set_input_state");
   SYM(c_init,                   "retro_init");
   SYM(c_deinit,                 "retro_deinit");
   SYM(c_run,                    "retro_run");
   SYM(c_reset,                  "retro_reset");
   SYM(c_load_game,              "retro_load_game");
   SYM(c_unload_game,            "retro_unload_game");
   SYM(c_get_system_info,        "retro_get_system_info");
   SYM(c_get_system_av_info,     "retro_get_system_av_info");
   SYM(c_set_controller_port_device, "retro_set_controller_port_device");
   /* optional */
   *(void **)&c_get_memory_data = dlsym(g_lib, "retro_get_memory_data");
   *(void **)&c_get_memory_size = dlsym(g_lib, "retro_get_memory_size");
   *(void **)&c_serialize_size  = dlsym(g_lib, "retro_serialize_size");
   *(void **)&c_serialize       = dlsym(g_lib, "retro_serialize");
   *(void **)&c_unserialize     = dlsym(g_lib, "retro_unserialize");
   return 1;
}

/* ------------------------------------------------------------------ */
/* JNI                                                                 */
/* ------------------------------------------------------------------ */
#define JNI(ret, name) JNIEXPORT ret JNICALL Java_com_dudu_pocketcore_Emu_##name

static char *jstr(JNIEnv *env, jstring s, char *buf, size_t n)
{
   const char *c = (*env)->GetStringUTFChars(env, s, NULL);
   snprintf(buf, n, "%s", c);
   (*env)->ReleaseStringUTFChars(env, s, c);
   return buf;
}

JNI(void, nativeSetOption)(JNIEnv *env, jclass cls, jstring jkey, jstring jval)
{
   (void)cls;
   char k[64], v[96]; int i;
   jstr(env, jkey, k, sizeof(k));
   jstr(env, jval, v, sizeof(v));
   for (i = 0; i < g_nopt; i++)
      if (!strcmp(g_opt[i].k, k)) { snprintf(g_opt[i].v, sizeof(g_opt[i].v), "%s", v); g_var_dirty = 1; return; }
   if (g_nopt < MAX_OPTS) {
      snprintf(g_opt[g_nopt].k, sizeof(g_opt[g_nopt].k), "%s", k);
      snprintf(g_opt[g_nopt].v, sizeof(g_opt[g_nopt].v), "%s", v);
      g_nopt++; g_var_dirty = 1;
   }
}

JNI(jint, nativeLoad)(JNIEnv *env, jclass cls, jstring jcore, jstring jrom,
                      jstring jsys, jstring jsave, jstring jopts)
{
   (void)cls;
   char core[512], opts[600];
   jstr(env, jcore, core, sizeof(core));
   jstr(env, jrom,  g_rom_path, sizeof(g_rom_path));
   jstr(env, jsys,  g_sys_dir,  sizeof(g_sys_dir));
   jstr(env, jsave, g_save_dir, sizeof(g_save_dir));
   jstr(env, jopts, opts, sizeof(opts));

   load_options(opts);

   g_lib = dlopen(core, RTLD_LAZY | RTLD_LOCAL);
   if (!g_lib) { LOGE("dlopen failed: %s", dlerror()); return -1; }
   if (!bind_core()) return -2;

   c_set_environment(cb_environment);
   c_init();
   c_set_video_refresh(cb_video);
   c_set_audio_sample(cb_audio_sample);
   c_set_audio_sample_batch(cb_audio_batch);
   c_set_input_poll(cb_input_poll);
   c_set_input_state(cb_input_state);

   struct retro_system_info si; memset(&si, 0, sizeof(si));
   c_get_system_info(&si);
   LOGI("core: %s %s (need_fullpath=%d)", si.library_name, si.library_version, si.need_fullpath);

   struct retro_game_info gi; memset(&gi, 0, sizeof(gi));
   gi.path = g_rom_path;
   void *rom = NULL; long romsz = 0;
   if (!si.need_fullpath) {
      FILE *f = fopen(g_rom_path, "rb");
      if (!f) { LOGE("rom not found: %s", g_rom_path); return -3; }
      fseek(f, 0, SEEK_END); romsz = ftell(f); fseek(f, 0, SEEK_SET);
      rom = malloc((size_t)romsz);
      if (fread(rom, 1, (size_t)romsz, f) != (size_t)romsz) { fclose(f); free(rom); return -3; }
      fclose(f);
      gi.data = rom; gi.size = (size_t)romsz;
   }
   if (!c_load_game(&gi)) { LOGE("retro_load_game failed"); free(rom); return -4; }
   free(rom);

   memset(&g_av, 0, sizeof(g_av));
   c_get_system_av_info(&g_av);
   ensure_fb(g_av.geometry.max_width  ? g_av.geometry.max_width  : 640,
             g_av.geometry.max_height ? g_av.geometry.max_height : 480);
   c_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
   sram_load();
   g_loaded = 1;

   audio_start(g_av.timing.sample_rate > 0 ? (int)g_av.timing.sample_rate : 44100);
   LOGI("loaded %ux%u @%.2f fps, %.0f Hz",
        g_av.geometry.base_width, g_av.geometry.base_height,
        g_av.timing.fps, g_av.timing.sample_rate);
   return 0;
}

JNI(void, nativeUnload)(JNIEnv *env, jclass cls)
{
   (void)env; (void)cls;
   if (!g_loaded) return;
   sram_save();
   audio_stop();
   c_unload_game();
   c_deinit();
   dlclose(g_lib);
   g_lib = NULL;
   g_loaded = 0;
   g_mid_ready = 0; g_mid_dirty = 0; g_prev_w = g_prev_h = 0;
}

JNI(void, nativeSurfaceCreated)(JNIEnv *env, jclass cls) { (void)env; (void)cls; gl_setup(); }

JNI(void, nativeResize)(JNIEnv *env, jclass cls, jint w, jint h)
{ (void)env; (void)cls; g_vw = w; g_vh = h; LOGI("resize view %d x %d (fb %u x %u)", w, h, g_fb_w, g_fb_h); }

static double g_next_t;   /* 다음 코어 프레임 마감 시각 */
static int g_turbo = 0;   /* 배속(▶▶) 누르는 동안 4배 */
static volatile int g_paused = 0;   /* 게임 안 「설정」 창이 떠 있는 동안 — 코어를 멈추고 마지막 그림만 그린다 */

static double now_s(void)
{
   struct timespec ts;
   clock_gettime(CLOCK_MONOTONIC, &ts);
   return (double)ts.tv_sec + ts.tv_nsec * 1e-9;
}

JNI(void, nativeFrame)(JNIEnv *env, jclass cls)
{
   (void)env; (void)cls;
   /* 죽은 오디오 스트림 재생성 — 에러 콜백은 표시만 남긴다(그 안에서 닫으면 안 됨).
      여기는 GL 스레드라 안전하다. */
   if (g_audio_dead) {
      g_audio_dead = 0;
      audio_stop();
      audio_start(g_audio_rate);
   }
   /* 화면 vsync(60/120Hz)마다 불리지만, 코어는 게임 fps 로만 돌린다 —
      120Hz 폰에서 두 배속으로 돌던 문제의 수정. 밀리면 최대 2프레임까지 따라잡고
      그 이상 밀린 시계는 버린다(일시정지·백그라운드 복귀 폭주 방지). */
   double fps = g_av.timing.fps > 1.0 ? g_av.timing.fps : 60.0;
   double dt = 1.0 / fps, t = now_s();
   int steps = 0, maxsteps = 2;
   if (g_turbo) { dt *= 0.25; maxsteps = 6; }
   if (g_next_t <= 0.0 || t - g_next_t > 0.25) g_next_t = t;
   /* vsync 간격 실측 — 기기가 화면 주사율을 60/120 사이로 바꿔도 따라간다 */
   if (g_last_vsync > 0.0) {
      double iv = t - g_last_vsync;
      if (iv > 0.002 && iv < 0.1)
         g_vsync_ema = g_vsync_ema > 0.0 ? g_vsync_ema * 0.9 + iv * 0.1 : iv;
   }
   g_last_vsync = t;
   /* 프레임 생성은 화면이 게임보다 «충분히» 빠를 때만 — 60Hz 화면에선 끼울 빈 vsync 가 없다.
      배속 중에도 끈다(한 vsync 에 여러 프레임이 돈다). */
   int fg_on = g_fg_mode != FG_OFF && !g_turbo && g_loaded
            && g_vsync_ema > 0.0 && g_vsync_ema < dt * 0.70
            && g_fb_w <= 320;                       /* 4배 그림(서기 그리기)엔 앱 보간을 안 돌린다 — 16배 일 */
   if (g_paused) { g_next_t = t; maxsteps = 0; fg_on = 0; }   /* 멈춤 — 풀리면 시계를 지금부터 다시 */
   while (g_next_t <= t && steps < maxsteps) {
      if (g_loaded) {
         if (fg_on && g_fb && g_fb_w) {      /* 코어가 덮어쓰기 전에 지금 그림을 «직전»으로 */
            unsigned need = g_fb_w * g_fb_h * 4;
            if (need > g_fg_cap) {
               free(g_prev); free(g_mid);
               g_prev = (uint8_t *)malloc(need); g_mid = (uint8_t *)malloc(need);
               g_fg_cap = (g_prev && g_mid) ? need : 0;
            }
            if (g_fg_cap >= need) { memcpy(g_prev, g_fb, need); g_prev_w = g_fb_w; g_prev_h = g_fb_h; }
         }
         c_run();
      }
      g_next_t += dt; steps++;
   }
   /* 이번 vsync 에 새 프레임이 나왔으면 «중간 그림»을 먼저 보여 주고, 다음 vsync 에 새 프레임.
      120Hz 에서 표시 순서 = 중간(n-1→n) · n · 중간(n→n+1) · n+1 … (반 프레임 지연) */
   int show_mid = 0;
   if (fg_on && steps > 0 && g_fg_cap && g_prev_w == g_fb_w && g_prev_h == g_fb_h) {
      fg_build(g_prev, g_fb, g_mid, (int)g_fb_w, (int)g_fb_h, g_fg_mode);
      g_mid_ready = 1; g_mid_dirty = 1; show_mid = 1;
   }
   glClear(GL_COLOR_BUFFER_BIT);
   gl_draw(show_mid);
}

JNI(jfloat, nativePanelHz)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return panel_hz(); }
JNI(jdouble, nativeCoreFps)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return g_av.timing.fps; }

JNI(void, nativeSetPaused)(JNIEnv *env, jclass cls, jboolean on)
{ (void)env; (void)cls; g_paused = on ? 1 : 0; }

JNI(void, nativeSetPanelHz)(JNIEnv *env, jclass cls, jfloat hz)
{ (void)env; (void)cls; g_panel_hz_hint = hz; }

JNI(void, nativeSetFrameGen)(JNIEnv *env, jclass cls, jint mode)
{
   (void)env; (void)cls;
   g_fg_mode = (mode == FG_BLEND || mode == FG_MOTION) ? mode : FG_OFF;
   LOGI("framegen mode %d", g_fg_mode);
}

/* 지금 실제로 중간 프레임을 끼우고 있는가 (화면이 충분히 빠른가) — 토스트·진단용 */
JNI(jint, nativeFrameGenActive)(JNIEnv *env, jclass cls)
{
   (void)env; (void)cls;
   double fps = g_av.timing.fps > 1.0 ? g_av.timing.fps : 60.0;
   if (g_vsync_ema <= 0.0) return -1;                         /* 아직 모름 */
   return (g_fg_mode != FG_OFF && g_vsync_ema < (1.0 / fps) * 0.70) ? (jint)(1.0 / g_vsync_ema + 0.5) : 0;
}

JNI(void, nativeSetInput)(JNIEnv *env, jclass cls, jint mask)
{ (void)env; (void)cls; g_input = mask; }

/* 헤드리스 구동 — GL 없이 코어만 n 프레임 돌린다. cb_video 가 g_fb(CPU 버퍼)를
   채우므로 nativeFrameBuffer 로 화면을 읽을 수 있다. 런처 썸네일 캡처용:
   롬을 몰래 부팅해 타이틀을 찍는다. 오디오는 호출 전에 nativeAudioPause 로 끌 것. */
JNI(void, nativeRunFrames)(JNIEnv *env, jclass cls, jint n)
{
   (void)env; (void)cls;
   if (!g_loaded) return;
   for (jint i = 0; i < n; i++) c_run();
}

/* 백그라운드에서 오디오 장치를 놓았다가 복귀 때 새로 연다 —
   물고 있으면 다른 앱 재생 뒤 스트림이 죽은 채 돌아오는 무음 사고가 난다. */
JNI(void, nativeAudioPause)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; audio_stop(); }

JNI(void, nativeAudioResume)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; if (g_loaded && !g_stream) audio_start(g_audio_rate); }

JNI(void, nativeReset)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; if (g_loaded) c_reset(); }

JNI(void, nativeSetIntegerScale)(JNIEnv *env, jclass cls, jboolean on)
{ (void)env; (void)cls; g_integer_scale = on ? 1 : 0; }

/* 화면 표시 설정 — GL 스레드에서 부를 것(Java 는 queueEvent). up: 0 끔 · 1 샤프 · 2 Scale2x · 3 xBR · 4 OmniScale.
   나머지는 세기 0..100. */
JNI(void, nativeSetDisplay)(JNIEnv *env, jclass cls, jint up, jint mix, jint grid, jint scan,
                            jint ghost, jint color, jint soft, jboolean integer)
{
   (void)env; (void)cls;
#define PCT(v) ((v) < 0 ? 0 : (v) > 100 ? 100 : (int)(v))
   g_d_up = (up >= 0 && up < DISP_N_UP) ? (int)up : 0;
   g_d_mix = PCT(mix); g_d_grid = PCT(grid); g_d_scan = PCT(scan);
   g_d_ghost = PCT(ghost); g_d_color = PCT(color); g_d_soft = PCT(soft);
#undef PCT
   g_integer_scale = integer ? 1 : 0;
   LOGI("display up %d mix %d grid %d scan %d ghost %d color %d soft %d int %d",
        g_d_up, g_d_mix, g_d_grid, g_d_scan, g_d_ghost, g_d_color, g_d_soft, g_integer_scale);
}

JNI(void, nativeSetTurbo)(JNIEnv *env, jclass cls, jboolean on)
{ (void)env; (void)cls; g_turbo = on ? 1 : 0; }

JNI(jint, nativeSaveState)(JNIEnv *env, jclass cls, jstring jpath)
{
   (void)cls;
   if (!g_loaded || !c_serialize_size) return -1;
   size_t sz = c_serialize_size();
   if (!sz) return -1;
   void *buf = malloc(sz);
   if (!c_serialize(buf, sz)) { free(buf); return -1; }
   char p[600]; jstr(env, jpath, p, sizeof(p));
   FILE *f = fopen(p, "wb");
   if (!f) { free(buf); return -1; }
   fwrite(buf, 1, sz, f); fclose(f); free(buf);
   return 0;
}

JNI(jint, nativeLoadState)(JNIEnv *env, jclass cls, jstring jpath)
{
   (void)cls;
   if (!g_loaded || !c_unserialize) return -1;
   char p[600]; jstr(env, jpath, p, sizeof(p));
   FILE *f = fopen(p, "rb");
   if (!f) return -1;
   fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
   void *buf = malloc((size_t)sz);
   if (fread(buf, 1, (size_t)sz, f) != (size_t)sz) { fclose(f); free(buf); return -1; }
   fclose(f);
   int ok = c_unserialize(buf, (size_t)sz);
   free(buf);
   return ok ? 0 : -1;
}

JNI(jint, nativeFrameWidth)(JNIEnv *env, jclass cls)  { (void)env; (void)cls; return (jint)g_fb_w; }
JNI(jint, nativeFrameHeight)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return (jint)g_fb_h; }

/* direct RGBA buffer of the last frame, for screenshots */
JNI(jobject, nativeFrameBuffer)(JNIEnv *env, jclass cls)
{
   (void)cls;
   if (!g_fb || !g_fb_w) return NULL;
   return (*env)->NewDirectByteBuffer(env, g_fb, (jlong)(g_fb_w * g_fb_h * 4));
}

JNI(void, nativeSaveSram)(JNIEnv *env, jclass cls) { (void)env; (void)cls; if (g_loaded) sram_save(); }
