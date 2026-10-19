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
#include "pace.h"
#include "league.h"
#include "practice.h"
#include "look.h"
#include "cheat.h"
#include "ovplane.h"
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
static void  *c_palremap;   /* retro_ngp_palremap — 게임이 팔레트를 쓰는 순간 컬러 바꿔 넣기(가상 리그 컬러, 옛 코어엔 없음) */
static void (*c_fg_state)(int *, int *, unsigned *);   /* retro_ngp_fg_state — 코어 2배 출력(120Hz) 중 이번 호출이 «합성»(에뮬레이션 없음)인가 */
static const uint8_t *(*c_fg_phase)(unsigned *, unsigned *, unsigned *);   /* retro_ngp_fg_phase — 4배 그림의 화소마다 원래 모눈 어긋남(코어 zzz_fg_phase) */
static void  *(*c_charram)(void), *(*c_scrollram)(void), *(*c_palram)(void);   /* 사무쇼2 코어의 K1GE 문자·스크롤·팔레트 램(가상 리그가 화면 글자·컬러를 바꿀 때) */
static void  *(*c_spriteram)(void);   /* K1GE 스프라이트 램 — 연습 메뉴 창이 빌릴 타일을 고를 때(스프라이트가 가리키는 타일은 안 빌림) */
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
/* 프레임 패치 쪽지 — 코어가 4배 그림(640×608)과 함께 넘기는 «화소마다 원래 화소의 4×4 칸이 몇 칸 비켜 시작하나»(x 0..3 | y 0..3 << 2).
   화면 필터(업스케일러·격자·스캔라인)가 화소마다 제 모눈으로 원래 화소를 읽어 ¼ px 자리를 지킨다 — 전엔 게임 칸마다 가운데
   한 점만 읽어(업스케일러는 1배로 줄여) ¼·½ 이 0 으로 반올림됐다(유저 2026-10-17 「필터를 켜면 그리 된다는 게 말이 되나」 → 「해봐」).
   색 버퍼와 따로 둬서 스크린샷·잔상은 그대로 */
static uint8_t  *g_ph = NULL;
static unsigned  g_ph_cap = 0;
static int       g_fb_ph = 0;          /* g_fb 의 그림에 쪽지가 있나(g_ph 에 같은 크기) */

/* 151: 앱 보간(framegen.c 움직임·섞기, fg_cadence.c 박자) 폐기 — 유저 2026-10-16 「앱보간 폐기해라」.
   완성된 화면에서 움직임을 짐작해 사이 그림을 끼우던 것 — 다음 그림을 기다려 25ms 늦고, 포즈 바뀜은 못 잇고, 이동은 짐작이라
   체감이 거의 없었다. 120Hz 사이 칸은 코어의 «프레임 패치»(옛 코어 보간)만 */
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
static GLuint g_prog = 0, g_tex = 0;
static unsigned g_tex_w = 0, g_tex_h = 0;   /* g_tex 에 지금 잡힌 크기 — 같으면 glTexSubImage2D 로 덮기만 */
static GLuint g_tex_ph = 0;                 /* 쪽지 텍스처(LUMINANCE, 0..15) — 텍스처 단위 1 */
static unsigned g_tph_w = 0, g_tph_h = 0;
static int g_tex_has_ph = 0;                /* 지금 g_tex 의 그림에 쪽지가 있나 */
static GLint  a_pos, a_uv, u_tex;
static int    g_vw = 1, g_vh = 1;
static int    g_integer_scale = 1;

/* 화면 표시 — 업스케일러(도트를 키우는 방식) 하나 + 필터(덧입히기) 여럿, 둘 다 세기(%).
   Java 가 설정에서 nativeSetDisplay 로 넣는다(GL 스레드에서). 전부 0 이면 예전 단순 셰이더 그대로. */
typedef struct {
   GLuint prog; int failed;
   GLint a_pos, a_uv, u_tex, u_size, u_out, u_mix, u_grid, u_scan, u_color, u_soft;
   GLint u_sub, u_phase, u_ph;   /* 텍스처 배율(1·4) · 쪽지 있음 · 쪽지 텍스처 */
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
/* 157 덧판 — 합성한 그림(ovplane.c 버퍼)을 올리는 따로 텍스처. g_tex(날것 g_fb)는 그대로 */
static GLuint    g_tex_ov = 0;
static unsigned  g_tex_ov_w = 0, g_tex_ov_h = 0;
static uint32_t  g_tex_tag = 0;            /* 157 post: g_tex 의 이름표 줄에 지금 덧칠된 HUD 이름표 서명(0 = 날 그림 그대로) */
/* 160: 프레임 패치 쪽지 × 덧판 — 덧판 합성 그림(g_tex_ov)엔 ovplane 이 같이 합성한 쪽지(g_tex_ovph: 덧판이 칠한 화소 0 · 나머지는 게임 쪽지를 같이 옮김).
   이름표만 덧칠하는 게임 텍스처(g_tex)엔 쪽지 텍스처(g_tex_ph)의 그 줄도 이름표 화소만 0 으로(g_tph_tag = 지금 덮인 서명) */
static GLuint    g_tex_ovph = 0;
static unsigned  g_tovph_w = 0, g_tovph_h = 0;
static uint32_t  g_tph_tag = 0;

/* audio ring buffer (stereo int16) */
#define ARING_FRAMES 16384
static int16_t g_ring[ARING_FRAMES * 2];
static volatile uint32_t g_rd = 0, g_wr = 0;
static AAudioStream *g_stream = NULL;
/* 150: 소리 늘리고 줄이기 — 코어를 화면 vsync 수로 돌리면(pace.c) 게임이 60.25 → 60.0 처럼 조금 느려져 소리가 모자란다.
   고리 버퍼를 «소리 카드가 한 번에 가져가는 양 + 700» 근처로 지키게 ±0.5% 늘리고 줄인다(음높이 차이 8.6 cent 안 — 안 들림) */
static pace_t g_pace;
static drc_t  g_drc;
static volatile int32_t g_cb_frames = 0;   /* 소리 카드가 한 번에 가져간 가장 큰 양(오디오 스레드가 적는다) */
static int16_t g_drc_out[8192 * 2];
static int g_turbo_audio = 0;             /* 배속 중 — 소리를 늘리고 줄이지 않는다(nativeFrame 이 g_turbo 를 옮겨 적는다) */

/* 코어 자물쇠(116) — libretro 코어는 한 스레드에서만 불려야 한다. 예전엔 onPause 오토세이브·SRAM 저장·옵션 바꾸기를
   UI 스레드에서 바로 불러, GL 스레드가 retro_run 을 도는 도중과 겹쳤다. 숨은 프레임(런어헤드·프레임 생성)이 «소리 칸을
   뺀 가벼운 저장» 표시를 세운 순간과 겹치면 오토세이브에 소리 칸이 빠져, 다음에 이어하기 하면 소리칩이 꺼진 채였다
   (유저 2026-10-11 「사운드가 자주 안 나옴 // 리셋하면 나옴」 — 하니스로 겹쳐 저장 40번 중 34번 무음 재현).
   코어로 들어가는 모든 호출(retro_run 한 번 단위)을 이 자물쇠 안에서 한다. 콜백 안에서는 잡지 않는다(이미 잡혀 있다). */
static pthread_mutex_t g_core_mx  = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_audio_mx = PTHREAD_MUTEX_INITIALIZER;   /* 오디오 스트림 열고 닫기 — GL·UI 스레드가 같이 만진다 */
#define CORE_LOCK()   pthread_mutex_lock(&g_core_mx)
#define CORE_UNLOCK() pthread_mutex_unlock(&g_core_mx)

/* core options loaded from a plain key=value text file (no menus)
   114: 64 → 256 + 같은 키는 한 칸(뒤 줄이 이긴다). 앱은 새 키를 options.txt 끝에 붙이는데(Settings.put), 64 칸이 차면
   그 뒤 키가 코어에 조용히 안 넘어갔다 — 설정을 많이 만진 기기에서 111 의 «동작 사이 그림» 같은 새 키가 빠질 수 있었다(검수 2026-10-11) */
#define MAX_OPTS 256
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
      int at = g_nopt;
      for (int i = 0; i < g_nopt; i++) if (!strcmp(g_opt[i].k, line)) { at = i; break; }   /* 같은 키 — 뒤 줄이 이긴다 */
      snprintf(g_opt[at].k, sizeof(g_opt[at].k), "%s", line);
      snprintf(g_opt[at].v, sizeof(g_opt[at].v), "%s", eq + 1);
      /* trim trailing whitespace/newline */
      for (char *p = g_opt[at].v + strlen(g_opt[at].v) - 1;
           p >= g_opt[at].v && (*p == '\n' || *p == '\r' || *p == ' '); --p) *p = 0;
      if (at == g_nopt) g_nopt++;
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
   /* 쪽지 — 코어가 이 그림(4배)에 붙여 준 것. 크기가 안 맞으면 없는 것으로 */
   const uint8_t *ph = NULL;
   unsigned phw = 0, phh = 0, php = 0;
   if (c_fg_phase && g_pixfmt == RETRO_PIXEL_FORMAT_RGB565 && w >= 320) {
      ph = c_fg_phase(&phw, &phh, &php);
      if (ph && (phw != w || phh != h || php < w)) ph = NULL;
   }
   g_fb_ph = 0;
   /* 쪽지가 없는 코어에서 업스케일러를 켰으면 사무쇼2 의 4배 그림(서기·동작 사이 그림을 ¼픽셀로 그린 640×608)을 1배로 — 칸마다 가운데 한 점.
      업스케일러는 «도트 한 칸 = 텍셀 한 칸»이어야 돈다. 사이 그림은 ¼픽셀 대신 정수 픽셀로 반올림된다.
      유저 2026-10-11 「업스케일러가 안 되네 — 업스케일러는 블렌더 레벨 필요 없어」. 쪽지가 있으면 4배 그대로 두고 셰이더가 화소마다 제 모눈으로 */
   unsigned ds = 1;
   /* 150: SVC 코어 보간도 4배 — 기술명 띠(최대 32줄)가 붙으면 세로가 (152+띠)·4 라 «152 의 배수»만 보면 못 알아봤다 */
   if (g_d_up > 0 && !ph && g_pixfmt == RETRO_PIXEL_FORMAT_RGB565 && w >= 320 && w % 160 == 0 && h % (w / 160) == 0
       && h / (w / 160) >= 152u && h / (w / 160) <= 184u)
      ds = w / 160;
   if (ds > 1) {
      unsigned ow = w / ds, oh = h / ds;
      uint8_t *d1 = g_fb;
      g_fb_w = ow; g_fb_h = oh;
      for (unsigned y = 0; y < oh; y++) {
         const uint16_t *src = (const uint16_t *)((const uint8_t *)data + (y * ds + ds / 2) * pitch) + ds / 2;
         for (unsigned x = 0; x < ow; x++) {
            uint16_t p = src[x * ds];
            unsigned r = (p >> 11) & 0x1f, g = (p >> 5) & 0x3f, b = p & 0x1f;
            *d1++ = (uint8_t)((r << 3) | (r >> 2));
            *d1++ = (uint8_t)((g << 2) | (g >> 4));
            *d1++ = (uint8_t)((b << 3) | (b >> 2));
            *d1++ = 0xff;
         }
      }
      g_fb_dirty = 1;
      return;
   }
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
   if (ph) {
      size_t n = (size_t)w * h;
      if (n > g_ph_cap) { free(g_ph); g_ph = (uint8_t *)malloc(n); g_ph_cap = g_ph ? (unsigned)n : 0; }
      if (g_ph) {
         for (unsigned y = 0; y < h; y++) memcpy(g_ph + (size_t)y * w, ph + (size_t)y * php, w);
         g_fb_ph = 1;
      }
   }
   g_fb_dirty = 1;
}

static void ring_push(const int16_t *data, size_t frames)
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
}

static int g_mute_drop = 0;                 /* 153: 소리를 버리던 중 — 풀리면 늘림(drc)을 새로(버린 앞 샘플과 잇지 않게) */
static size_t cb_audio_batch(const int16_t *data, size_t frames)
{
   size_t done = 0;
   if (league_mute) { g_mute_drop = 1; return frames; }   /* 153: 랭크 자동 진행(4배속) 동안 — 고리에 안 넣는다(배속 소리가 남아 뒤에 나오지 않게) */
   if (g_mute_drop) { g_mute_drop = 0; drc_reset(&g_drc); }
   while (done < frames) {                       /* 큰 묶음은 나눠서(출력 칸 8192) */
      size_t n = frames - done > 4096 ? 4096 : frames - done;
      uint32_t fill = (g_wr - g_rd + ARING_FRAMES) % ARING_FRAMES;
      unsigned cb = (unsigned)g_cb_frames, target = cb + 700;
      if (target < 900) target = 900;
      if (g_turbo_audio) ring_push(data + done * 2, n);   /* 배속 중엔 그대로(넘치면 버림 — 예전과 같다) */
      else {
         size_t w = drc_run(&g_drc, data + done * 2, n, g_drc_out, 8192, drc_ratio(g_pace.drc_base, fill, target));
         ring_push(g_drc_out, w);
      }
      done += n;
   }
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
   /* 가상 리그: 메뉴 자동 진입 중엔 그 입력, 게임 속 리그가 대신 받는 버튼(리그 대전 칸·상대 고르기의 A)은 게임에 안 준다.
      연습 대전: 경기 중 Option(탭 = 메뉴 · 조합 = 리셋)·메뉴가 열린 동안의 버튼은 게임에 안 주고, 일시정지를 걸고 풀 Option·이야기 넘기는 A 는 더한다
      컬러(look.c): 고르기 화면의 OPTION(컬러)·A·B·A+B(가르기 — A+B 는 베기 색)·컬러 만들기 창이 열린 동안의 모든 버튼은 게임에 안 주고, 가르기가 낸 결정의 A(검질 고르기 «뒤로»는 B)는 더한다 */
   /* 157 덧판: 메뉴가 보이거나 멈춤(ov_hold) 중이면 게임 비트 전부(0xFFF — A·B·OPTION·방향·SELECT·SP 접기 1·9·10·11)를 막는 백스톱 */
   int in = g_input, blk = league_in_block | practice_in_block | look_in_block | ov_in_block();
   if (in & (1 << RETRO_DEVICE_ID_JOYPAD_START)) blk |= practice_opt_mask;   /* Option 과 같은 프레임의 방향·A·B(연습 경기 중) */
   return ((league_ovr_on ? league_ovr_mask : ((in & ~blk) | league_in_or | practice_in_or | look_in_or)) >> id) & 1;
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
   if (numFrames > g_cb_frames && numFrames < 8192) g_cb_frames = numFrames;
   int mute = league_mute;             /* 153: 랭크 자동 진행이 찾기 겹창 뒤에서 고르기·이야기를 밟는 동안 — 고리에 남은 것도 무음으로 비운다(새 소리는 cb_audio_batch 가 버림) */
   for (int32_t i = 0; i < numFrames; i++) {
      if (rd == g_wr) {                /* underrun: hold silence */
         out[i * 2] = 0; out[i * 2 + 1] = 0;
      } else {
         out[i * 2]     = mute ? 0 : g_ring[rd * 2];
         out[i * 2 + 1] = mute ? 0 : g_ring[rd * 2 + 1];
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
   pthread_mutex_lock(&g_audio_mx);
   if (g_stream) { pthread_mutex_unlock(&g_audio_mx); return; }   /* 이미 열려 있다 — 두 번 열면 앞 스트림이 샌다 */
   g_audio_rate = rate;
   g_audio_dead = 0;
   if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) { pthread_mutex_unlock(&g_audio_mx); return; }
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
   pthread_mutex_unlock(&g_audio_mx);
}

static void audio_stop(void)
{
   pthread_mutex_lock(&g_audio_mx);
   if (g_stream) {
      AAudioStream_requestStop(g_stream);
      AAudioStream_close(g_stream);
      g_stream = NULL;
   }
   pthread_mutex_unlock(&g_audio_mx);
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
   d->u_sub = glGetUniformLocation(pr, "uSub");   d->u_phase = glGetUniformLocation(pr, "uPhase");
   d->u_ph = glGetUniformLocation(pr, "uPh");
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
   glGenTextures(1, &g_tex_gh);
   glBindTexture(GL_TEXTURE_2D, g_tex_gh);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
   glGenTextures(1, &g_tex_ph);
   g_tph_w = g_tph_h = 0; g_tex_has_ph = 0;
   glBindTexture(GL_TEXTURE_2D, g_tex_ph);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
   glGenTextures(1, &g_tex_ov);           /* 157 덧판 — 새 컨텍스트면 다음 합성 그림을 통째로 다시 올린다(덧판 상태는 ovplane 에 남음) */
   g_tex_ov_w = g_tex_ov_h = 0;
   glBindTexture(GL_TEXTURE_2D, g_tex_ov);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
   glGenTextures(1, &g_tex_ovph);         /* 160: 덧판 합성 그림의 쪽지(LUMINANCE) — 덧판이 그린 화소는 0(게임 칸 모눈), 나머지는 게임 쪽지를 같이 옮김 */
   g_tovph_w = g_tovph_h = 0;
   glBindTexture(GL_TEXTURE_2D, g_tex_ovph);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
   memset(g_dp, 0, sizeof(g_dp));   /* 새 컨텍스트 — 예전 프로그램은 이미 사라졌다. 쓸 때 다시 만든다 */
   g_gh_valid = 0;
   g_fb_dirty = 1;
   glClearColor(0.f, 0.f, 0.f, 1.f);
}

static void gl_draw(void)
{
   if (!g_fb || !g_fb_w) return;
   const uint8_t *img = g_fb;
   unsigned iw = g_fb_w, ih = g_fb_h;
   int fresh = g_fb_dirty;                /* 157: 이번 vsync 에 새 그림이 왔나 — 덧판은 새 그림·잔상일 때만 다시 합성 */

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
      g_tex_has_ph = 0;
      if (g_fb_ph && g_ph && g_tex_ph) {        /* 쪽지 — 같은 크기의 한 바이트 텍스처(단위 1) */
         glActiveTexture(GL_TEXTURE1);
         glBindTexture(GL_TEXTURE_2D, g_tex_ph);
         if (g_tph_w != g_fb_w || g_tph_h != g_fb_h) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, g_fb_w, g_fb_h, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, g_ph);
            g_tph_w = g_fb_w; g_tph_h = g_fb_h;
         } else
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, g_fb_w, g_fb_h, GL_LUMINANCE, GL_UNSIGNED_BYTE, g_ph);
         glActiveTexture(GL_TEXTURE0);
         glBindTexture(GL_TEXTURE_2D, g_tex);
         g_tex_has_ph = 1;
      }
      g_tph_tag = 0;
      g_tex_tag = 0;
      ov_tag_scan(img, iw, ih);           /* 157 post: 새 게임 그림 — HUD 이름표 자리에 게임이 무엇을 그렸나(날 그림으로) */
   }

   /* aspect-correct, integer-scaled viewport.
      기둥(사이드 아트) 프레임은 288폭이지만 **게임 몫은 160** — 크기는 게임 160폭으로 정하고 기둥은
      남는 옆자리에만 그린다(넘치면 잘린다). 288 전체를 화면 폭에 맞추면 게임이 반토막 나고 아래가
      텅 빈다는 제보(유저: 「양쪽에 붙이면 될 걸 표시영역을 아래로 늘리지 마라」). */
   /* 서기 그리기(코어 패치 95)를 켜면 코어가 4배(640×608)로 내보낸다 — 화면 상자·정수배·필터는 «게임 칸»(160×152) 기준 */
   int sub = (g_fb_w >= 320 && g_fb_w % 160 == 0 && g_fb_h % (g_fb_w / 160) == 0
              && g_fb_h / (g_fb_w / 160) >= 152u && g_fb_h / (g_fb_w / 160) <= 184u) ? (int)(g_fb_w / 160) : 1;   /* 150: 띠가 붙은 4배(SVC 프레임 패치)도 */
   int lw = (int)iw / sub, lh = (int)ih / sub;
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
      { static int ldw=-1, ldh=-1, lvw=-1; if (dw!=ldw||dh!=ldh||g_vw!=lvw) { LOGI("viewport %d x %d in %d x %d (img %u x %u, sub %d, game_w %d, int %d)", dw, dh, g_vw, g_vh, iw, ih, sub, game_w, g_integer_scale); ldw=dw; ldh=dh; lvw=g_vw; } }
      glViewport((g_vw - dw) / 2, y0, dw, dh);
      ov_viewport((g_vw - dw) / 2, y0, dw, dh, g_vh, g_fb_w, g_fb_h);   /* 157: 게임 칸 자리(터치)·게임 사각형 판정 */
   }

   static const GLfloat pos[] = { -1,-1,  1,-1, -1, 1,  1, 1 };
   static const GLfloat uv[]  = {  0, 1,  1, 1,  0, 0,  1, 0 };

   GLuint tex = g_tex, phtex = g_tex_ph;
   int phon = g_tex_has_ph;                       /* 셰이더에 쪽지를 줄까(덧판이 쪽지를 못 만들면 끔 — 메뉴 글자를 게임 쪽지로 밀어 읽지 않게) */
   const uint8_t *phs = g_tex_has_ph ? g_ph : NULL;   /* 160: g_tex 그림의 쪽지(g_ph 는 g_fb 와 같이만 바뀐다) */
   const uint8_t *shown = img, *ob = NULL;
   int ghost = g_d_ghost > 0, ch = 0;
   if (ghost) shown = ghost_apply(img, iw, ih);   /* 잔상 — 화면에 낼 그림(중간 그림 포함)을 누적 */
   else g_gh_valid = 0;
   /* 157 덧판 — 잔상 뒤(메뉴엔 잔상이 안 끼게) 따로 버퍼에 합성해 따로 텍스처로. 바뀐 그림일 때만 다시 합성·올림(4배 한 장 1.5MB).
      업스케일러·격자·스캔라인·색 셰이더는 합성 결과에 그대로 걸린다(게임 글자처럼). 게임 사각형을 못 찾으면 NULL — 그냥 그림 */
   if (ov_active()) ob = ov_compose_ph(shown, phs, iw, ih, fresh || ghost, &ch);
   if (ob) {
      glBindTexture(GL_TEXTURE_2D, g_tex_ov);
      if (g_tex_ov_w != iw || g_tex_ov_h != ih) {
         glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, iw, ih, 0, GL_RGBA, GL_UNSIGNED_BYTE, ob);
         g_tex_ov_w = iw; g_tex_ov_h = ih;
      } else if (ch) {           /* 바뀐 줄 범위만(쉬는 메뉴의 일렁임·까딱은 몇 줄 — 4배 한 장 1.5MB 를 vsync 마다 올리지 않게) */
         int sy0 = 0, sy1 = (int)ih - 1;
         ov_compose_span(&sy0, &sy1);
         if (sy0 < 0) sy0 = 0;
         if (sy1 > (int)ih - 1) sy1 = (int)ih - 1;
         if (sy1 >= sy0)
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, sy0, iw, sy1 - sy0 + 1, GL_RGBA, GL_UNSIGNED_BYTE, ob + (size_t)sy0 * iw * 4);
      }
      tex = g_tex_ov;
      const uint8_t *pb = phs ? ov_compose_phase() : NULL;   /* 160: 합성 쪽지 — 바뀐 줄 범위는 그림과 같다(ov_compose_span) */
      phon = 0;
      if (pb && g_tex_ovph) {
         glActiveTexture(GL_TEXTURE1);
         glBindTexture(GL_TEXTURE_2D, g_tex_ovph);
         if (g_tovph_w != iw || g_tovph_h != ih) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, iw, ih, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, pb);
            g_tovph_w = iw; g_tovph_h = ih;
         } else if (ch) {
            int sy0 = 0, sy1 = (int)ih - 1;
            ov_compose_span(&sy0, &sy1);
            if (sy0 < 0) sy0 = 0;
            if (sy1 > (int)ih - 1) sy1 = (int)ih - 1;
            if (sy1 >= sy0)
               glTexSubImage2D(GL_TEXTURE_2D, 0, 0, sy0, iw, sy1 - sy0 + 1, GL_LUMINANCE, GL_UNSIGNED_BYTE, pb + (size_t)sy0 * iw);
         }
         glActiveTexture(GL_TEXTURE0);
         phtex = g_tex_ovph; phon = 1;
      }
   } else if (ghost) {            /* 잔상 그림은 따로 올린다(덧판이 그리면 그 안에 이미 들어 있어 안 올림) */
      glBindTexture(GL_TEXTURE_2D, g_tex_gh);
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, iw, ih, 0, GL_RGBA, GL_UNSIGNED_BYTE, shown);
      tex = g_tex_gh;
   }
   if (!ob) {                     /* 157 post: HUD 이름표 — 메뉴가 안 보이면 그려 낼 게임 텍스처의 이름표 줄만 덧칠(4배 그림도 32줄) ·
                                     잔상 텍스처는 vsync 마다 새로 올라오니 늘, 게임 텍스처는 서명이 바뀔 때만(숨길 땐 날 줄로 되돌림) */
      uint32_t sig = ov_tag_sig();
      int need = ghost ? sig != 0 : sig != g_tex_tag;
      int phneed = phs && sig != g_tph_tag;        /* 160: 쪽지 텍스처의 이름표 줄 — 서명이 바뀔 때만(쪽지는 새 그림 때만 통째로 올라온다) */
      int by = 0, bn = 0;
      const uint8_t *pband = NULL;
      const uint8_t *band = (need || phneed) ? ov_tag_band2(shown, phneed ? phs : NULL, iw, ih, &by, &bn, &pband) : NULL;
      if (band && bn > 0 && by >= 0 && by + bn <= (int)ih) {
         if (need) {
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, by, iw, bn, GL_RGBA, GL_UNSIGNED_BYTE, band);
            if (!ghost) g_tex_tag = sig;
         }
         if (phneed && pband) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, g_tex_ph);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, by, iw, bn, GL_LUMINANCE, GL_UNSIGNED_BYTE, pband);
            glActiveTexture(GL_TEXTURE0);
            g_tph_tag = sig;
         }
      } else {
         if (need && !ghost) g_tex_tag = sig;
         if (phneed) g_tph_tag = sig;
      }
   }

   /* 업스케일러는 «원래 화소 하나 = 텍셀 하나»를 읽어야 돈다 — 1배 그림이거나, 4배 그림이면 쪽지로 화소마다 제 모눈을 알 때 */
   int up = (g_d_mix > 0 && (sub == 1 || g_tex_has_ph)) ? g_d_up : 0;
   disp_prog *d = (up || g_d_grid || g_d_scan || g_d_color || g_d_soft) ? disp_get(up) : NULL;
   GLint ap = a_pos, au = a_uv;
   if (d) {
      glUseProgram(d->prog);
      glUniform1i(d->u_tex, 0);
      glUniform1f(d->u_sub, (float)sub);                 /* 4배 그림이면 색은 텍셀 그대로(¼ px), 격자·스캔라인 줄만 게임 칸에 */
      glUniform1f(d->u_phase, phon ? 1.f : 0.f);
      glUniform1i(d->u_ph, 1);
      glActiveTexture(GL_TEXTURE1);
      glBindTexture(GL_TEXTURE_2D, phtex);
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
   *(void **)&c_charram         = dlsym(g_lib, "retro_ngp_charram");
   *(void **)&c_scrollram       = dlsym(g_lib, "retro_ngp_scrollram");
   *(void **)&c_palram          = dlsym(g_lib, "retro_ngp_palram");
   *(void **)&c_spriteram       = dlsym(g_lib, "retro_ngp_spriteram");
   c_palremap                   = dlsym(g_lib, "retro_ngp_palremap");
   *(void **)&c_fg_state        = dlsym(g_lib, "retro_ngp_fg_state");
   *(void **)&c_fg_phase        = dlsym(g_lib, "retro_ngp_fg_phase");   /* 없으면(옛 코어·다른 게임) 업스케일러는 예전처럼 1배로 줄여서 */
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
   CORE_LOCK();                                   /* 코어가 retro_run 안에서 옵션 표를 읽는 중일 수 있다 */
   for (i = 0; i < g_nopt; i++)
      if (!strcmp(g_opt[i].k, k)) { snprintf(g_opt[i].v, sizeof(g_opt[i].v), "%s", v); g_var_dirty = 1; CORE_UNLOCK(); return; }
   if (g_nopt < MAX_OPTS) {
      snprintf(g_opt[g_nopt].k, sizeof(g_opt[g_nopt].k), "%s", k);
      snprintf(g_opt[g_nopt].v, sizeof(g_opt[g_nopt].v), "%s", v);
      g_nopt++; g_var_dirty = 1;
   }
   CORE_UNLOCK();
}

/* 165 고침: 코어 안 화면·팔레트·스프라이트 램과 팔레트 바꿔 쓰기 함수를 허브·컬러(look·vpanel)·연습·VS 띠에 — 코어를 올린 직후와 상태 불러오기 앞(live 1),
   올리기 시작할 때·내릴 때 dlclose 전(live 0 = NULL). 예전엔 nativeFrame 에서만 넣어서, 코어를 내리고 다시 올린 뒤 첫 프레임 전 nativeLoadState
   (이어하기·옵션 적용·랭크 체크포인트)의 look_after_load 가 내린 코어의 retro_ngp_palremap 을 부르고 풀린 NGPGfx 를 읽었다(리뷰 — SIGSEGV) */
static void core_ptrs(int live)
{
   void *chr = live && c_charram ? c_charram() : NULL, *scr = live && c_scrollram ? c_scrollram() : NULL;
   void *pal = live && c_palram ? c_palram() : NULL, *spr = live && c_spriteram ? c_spriteram() : NULL;
   league_vram(chr, scr, pal);           /* look_vram(vpanel 칸·아이콘 · 컬러 팔레트)까지 */
   league_spr(spr);
   league_palremap_fn(live ? c_palremap : NULL);
   practice_vram(chr, scr, pal, spr);
}

static jint load_locked(JNIEnv *env, jstring jcore, jstring jrom,
                        jstring jsys, jstring jsave, jstring jopts)
{
   core_ptrs(0);                         /* 165 고침: 아래 무엇도 앞 코어(내렸으면 dlclose 된)의 램·함수를 만지지 않게 — 올린 뒤 core_ptrs(1) */
   league_disarm();                      /* 새 게임은 무장 없이 시작 — 랭크전이면 Java 가 로드 뒤에 다시 무장한다 */
   ov_clear(OV_CLR_ALL);                 /* 157 덧판: 코어 상태가 통째로 바뀜 — 메뉴·대본·멈춤·막기 모두(상태용 램 포인터는 올린 뒤 새로) */
   ov_tag_clear();                       /* 157 post: HUD 이름표도(메뉴 닫기는 안 지움 — 여기 다섯 곳만) */
   ov_game_ram(NULL, 0);
   league_hub(0, NULL, NULL);            /* 게임 속 리그도 끈 채로 — 허브로 켠 화면만 로드 뒤에 다시 켠다(앞 세션의 허브가 자유 플레이로 새지 않게) */
   practice_forget();                    /* 연습 대전도 — 일반 세션이고 확인한 롬 판이면 Java 가 로드 뒤에 연습 허브를 켠다 */
   league_practice_resume(NULL, 0);      /* 지난 판에서 기다리던 연습 경기 되돌리기는 버린다(이 판의 상태 불러오기가 다시 남긴다) */
   look_enable(0); look_forget();        /* 컬러도 끈 채로 — 사쇼2 확인한 판이면 Java 가 로드 뒤에 켠다(다른 게임 램·팔레트에 손대지 않게) */
   look_set_opp(-1, 0, -1, 0, 0, 0);     /* 177: 리그 상대 컬러도 — 리그 판이면 Java 가 경기마다 다시 넣는다(다른 게임·일반 판으로 새지 않게) */
   cheat_reset_all();                    /* 치트도 모두 끈 채로 — 일반 세션이고 확인한 롬 판이면 Java 가 로드 뒤에 값을 넣는다(리그 판은 잠근다) */
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
   core_ptrs(1);                         /* 165 고침: 첫 프레임 전 불러오기·저장(이어하기 · 옵션 적용 · 체크포인트 · onPause 오토세이브)도 이 코어의 램으로 */
   if (c_get_memory_data && c_get_memory_size)   /* 157 덧판 상태(게임 PAUSE 0x61D4 · 라운드 승수)용 시스템 램 — 포인터만(코어가 내려갈 때 거둠) */
      ov_game_ram((const uint8_t *)c_get_memory_data(RETRO_MEMORY_SYSTEM_RAM), c_get_memory_size(RETRO_MEMORY_SYSTEM_RAM));

   pace_reset(&g_pace); drc_reset(&g_drc);   /* 150: 새 게임 — 박자 잠금·소리 늘림을 처음부터 */
   audio_start(g_av.timing.sample_rate > 0 ? (int)g_av.timing.sample_rate : 44100);
   LOGI("loaded %ux%u @%.2f fps, %.0f Hz",
        g_av.geometry.base_width, g_av.geometry.base_height,
        g_av.timing.fps, g_av.timing.sample_rate);
   return 0;
}

JNI(jint, nativeLoad)(JNIEnv *env, jclass cls, jstring jcore, jstring jrom,
                      jstring jsys, jstring jsave, jstring jopts)
{
   (void)cls;
   CORE_LOCK();
   jint rc = load_locked(env, jcore, jrom, jsys, jsave, jopts);
   CORE_UNLOCK();
   return rc;
}

static void unload_locked(void)
{
   league_disarm();                      /* 코어가 내려가면 랭크전 무장은 무조건 끝 — 다음 게임으로 새지 않게 */
   ov_clear(OV_CLR_ALL);                 /* 157 덧판 지움 · 상태용 램 포인터도 버림(코어가 내려감 — dlclose 뒤 읽지 않게) */
   ov_tag_clear();
   ov_game_ram(NULL, 0);
   league_hub(0, NULL, NULL);
   practice_forget();
   look_enable(0); look_forget();
   look_set_opp(-1, 0, -1, 0, 0, 0);     /* 177: 리그 상대 컬러도 */
   cheat_reset_all();                    /* 앞 세션의 치트 값·보관한 카드가 다음 게임으로 새지 않게 */
   core_ptrs(0);                         /* 165 고침: dlclose 뒤 내린 코어의 바꿔 쓰기 함수·풀린 화면 램을 아무도 안 부르게(다음 코어를 올리기 전 불러오기·저장 포함) */
   if (!g_loaded) return;
   sram_save();
   audio_stop();
   c_unload_game();
   c_deinit();
   dlclose(g_lib);
   g_lib = NULL;
   g_loaded = 0;
}

JNI(void, nativeUnload)(JNIEnv *env, jclass cls)
{
   (void)env; (void)cls;
   CORE_LOCK(); unload_locked(); CORE_UNLOCK();
}

JNI(void, nativeSurfaceCreated)(JNIEnv *env, jclass cls) { (void)env; (void)cls; gl_setup(); }

JNI(void, nativeResize)(JNIEnv *env, jclass cls, jint w, jint h)
{ (void)env; (void)cls; g_vw = w; g_vh = h; LOGI("resize view %d x %d (fb %u x %u)", w, h, g_fb_w, g_fb_h); }

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
      120Hz 폰에서 두 배속으로 돌던 문제의 수정. 150: 몇 번 돌릴지는 pace.c — 화면 vsync 수가 게임 프레임의 정수배면
      «k 번에 한 번»을 세어(예전 «시계가 마감을 넘었나»는 깨어나는 때가 조금만 흔들려도 한 번 거르고 두 번 돌았다),
      아니면 예전처럼 시계로(밀리면 최대 2프레임까지 따라잡고, 그 이상 밀린 시계는 버린다). */
   double fps = g_av.timing.fps > 1.0 ? g_av.timing.fps : 60.0;
   double t = now_s();
   int steps = 0, nsteps;
   /* vsync 간격 실측 — 기기가 화면 주사율을 60/120 사이로 바꿔도 따라간다 */
   if (g_last_vsync > 0.0) {
      double iv = t - g_last_vsync;
      if (iv > 0.002 && iv < 0.1)
         g_vsync_ema = g_vsync_ema > 0.0 ? g_vsync_ema * 0.9 + iv * 0.1 : iv;
   }
   g_last_vsync = t;
   g_turbo_audio = g_turbo;
   /* 153: 리그 결판 뒤 승리 대사가 끝난 프레임(0x5825)에 league.c 가 세운 걸쇠 — Java 의 멈춤 풀기(바 닫기·창 닫기)와 상관없이
      리셋(nativeLeagueHubReturn)·새 무장·코어 내림 전까지 게임을 한 프레임도 더 돌리지 않는다(다음 장·이어하기 화면으로 안 감).
      멈춤과 같게 마지막 진짜 프레임(g_fb)만 그린다 */
   /* 153: 랭크 자동 진행(찾기 겹창 뒤 고르기·이야기, autopick flags 4)은 배속(▶▶)과 같은 4배 — 소리는 league_mute 로 버린다 */
   /* 157: 덧판 메뉴 멈춤(ov_hold) — g_paused 와 따로(Java 가 바·창을 닫을 때 nativeSetPaused 로 덮어써도 안 풀린다) */
   nsteps = pace_steps(&g_pace, t, fps, g_panel_hz_hint, g_turbo || league_ff, g_paused || !g_loaded || league_post_hold() || ov_hold());
   while (steps < nsteps) {
      int stop = 0;
      CORE_LOCK();                       /* retro_run 한 번 단위 — 그 사이에 UI 스레드의 저장이 끼어들 수 있게 */
      if (g_loaded) {
         /* 코어가 2배 출력(120Hz) 중이면 호출 두 번에 한 번은 «합성» — 에뮬레이션도 입력 읽기도 없다(코어 retro_run: fg_active && fg_phase == 1).
            그때 허브·연습을 돌리면 프레임 셈이 두 배로 가고, 다음 프레임에 줄 입력(대신 누름)이 게임이 읽기 전에 바뀐다 — 진짜 프레임 뒤에만 돌린다 */
         int synth = 0;
         if (c_fg_state) { int fa = 0, fp = 0; unsigned ff = 0; c_fg_state(&fa, &fp, &ff); synth = fa && fp == 1; }
         c_run();
         /* 가상 리그 랭크전 — 진짜 프레임마다 대진 덮어쓰기·결판 감지(무장 안 했으면 바로 돌아온다) */
         if (!synth && c_get_memory_data && c_get_memory_size) {
            void *chr = c_charram ? c_charram() : NULL, *scr = c_scrollram ? c_scrollram() : NULL, *pal = c_palram ? c_palram() : NULL;
            uint8_t *ram = (uint8_t *)c_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
            size_t ramsz = c_get_memory_size(RETRO_MEMORY_SYSTEM_RAM);
            int in = g_input;                    /* 이번 프레임 사용자 입력 — 허브·연습이 같은 값을 본다 */
            league_vram(chr, scr, pal);
            league_spr(c_spriteram ? c_spriteram() : NULL);   /* 153: VS 띠가 스프라이트 타일과 겹치는지 보려고 */
            league_palremap_fn(c_palremap);
            league_tick_hub(ram, ramsz, in);
            /* 컬러(모든 모드) — 허브가 고르기 화면 입력을 쓰는 동안(상대 선택·대신 누르기)엔 «바쁨»: 컬러는 손대지 않는다. 연습 «내 캐릭터 먼저»는
               «쥠»(HOLD — 컬러·베기 색·A·B 가르기는 look, 결정은 허브). 허브 잠금은 league_hub_look_ctx 안에서 풀린 뒤 look 잠금을 잡는다(겹치지 않음).
               베기 색(look → slash.c)도 여기 — 코어 바꿔 쓰기 표 + 프레임 뒤 보정. 합성 호출엔 안 돈다(표는 코어 안·상태 밖이라 런어헤드·숨은 프레임에도 그대로) */
            look_set_opp_live(league_opp_live());   /* 177: 상대 컬러 게이트(랭크 경기 무장·승리 대사·걸쇠) — 리그 잠금을 풀고 난 뒤 look 잠금(겹치지 않음) */
            look_tick(ram, ramsz, in, league_hub_look_ctx());
            /* 연습 대전(허브가 연습 경기일 때만 일함) — 같은 잠금·같은 순서. 메뉴에서 나가기를 고르면 여기서 리셋하고 허브를 돌려보낸다 */
            practice_vram(chr, scr, pal, c_spriteram ? c_spriteram() : NULL);
            practice_tick(ram, ramsz, in);
            /* 치트(일반 세션) — 가장 뒤. 리그 세션이면 아무것도 안 쓰고, 연습 대전 흐름이면 올카드·유가만 */
            cheat_tick(ram, ramsz, in);
            int ex = practice_take_exit();
            if (ex) { c_reset(); league_practice_return(ex == 2); practice_forget(); look_forget(); cheat_forget(); }
            stop = league_post_hold();       /* 153: 걸쇠가 이 진짜 프레임에 섰다(합성 호출에선 안 선다) */
         }
      }
      CORE_UNLOCK();
      steps++;
      if (stop) break;                   /* 153: 이번 vsync 의 남은 스텝(따라잡기·배속·코어 합성)도 없이 — 이 프레임 그대로 */
   }
   /* 157 덧판: 스텝 루프 뒤(스텝 0 이어도 — 걸쇠·멈춤 중에도 연출이 돈다) · gl_draw 앞. 걸쇠가 이 vsync 에 섰으면 걸어 둔 대본이 지금 t=0 으로.
      상태용 램 포인터(ov_game_ram)는 코어가 올라갈 때 한 번 주고 내려갈 때 거둔다(코어 안 고정 배열 — vsync 마다 코어 잠금을 잡지 않게) */
   ov_tick(t, league_post_hold());
   glClear(GL_COLOR_BUFFER_BIT);
   gl_draw();
}

JNI(jfloat, nativePanelHz)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return panel_hz(); }
JNI(jdouble, nativeCoreFps)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return g_av.timing.fps; }

JNI(void, nativeSetPaused)(JNIEnv *env, jclass cls, jboolean on)
{ (void)env; (void)cls; g_paused = on ? 1 : 0; }

JNI(void, nativeSetPanelHz)(JNIEnv *env, jclass cls, jfloat hz)
{ (void)env; (void)cls; g_panel_hz_hint = hz; }

JNI(void, nativeSetInput)(JNIEnv *env, jclass cls, jint mask)
{ (void)env; (void)cls; g_input = mask; }

/* 헤드리스 구동 — GL 없이 코어만 n 프레임 돌린다. cb_video 가 g_fb(CPU 버퍼)를
   채우므로 nativeFrameBuffer 로 화면을 읽을 수 있다. 런처 썸네일 캡처용:
   롬을 몰래 부팅해 타이틀을 찍는다. 오디오는 호출 전에 nativeAudioPause 로 끌 것. */
JNI(void, nativeRunFrames)(JNIEnv *env, jclass cls, jint n)
{
   (void)env; (void)cls;
   if (!g_loaded) return;
   for (jint i = 0; i < n; i++) { CORE_LOCK(); if (g_loaded) c_run(); CORE_UNLOCK(); }
}

/* 백그라운드에서 오디오 장치를 놓았다가 복귀 때 새로 연다 —
   물고 있으면 다른 앱 재생 뒤 스트림이 죽은 채 돌아오는 무음 사고가 난다. */
JNI(void, nativeAudioPause)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; audio_stop(); }

JNI(void, nativeAudioResume)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; if (g_loaded && !g_stream) audio_start(g_audio_rate); }

/* 게임 속 리그: 결판 뒤 리셋하고 타이틀 «리그 대전»까지(rematch = 바로 다시 붙을 상대 캐릭터, −1 없음) */
JNI(void, nativeLeagueHubReturn)(JNIEnv *env, jclass cls, jint rematch)
{ (void)env; (void)cls; CORE_LOCK(); if (g_loaded) c_reset(); league_hub_return(rematch); look_forget(); cheat_forget(); ov_clear(OV_CLR_ALL); ov_tag_clear(); CORE_UNLOCK(); }

JNI(void, nativeReset)(JNIEnv *env, jclass cls)
{
   (void)env; (void)cls;
   CORE_LOCK();
   if (g_loaded) c_reset();
   practice_forget(); league_practice_title();   /* 연습 대전: 램이 새로 시작 — 고르던 것·경기를 잊고 타이틀 상태로(리그 허브는 그대로) */
   league_post_clear();                          /* 153: 승리 대사 걸쇠도 푼다(리그 판은 리셋이 막혀 있어 방어용) */
   look_forget();                                /* 컬러: 경기 컬러·세로 칸을 잊는다(기억·내 색은 그대로) */
   cheat_forget();                               /* 치트: 보관한 카드는 되돌리지 않고 버린다(램이 새로 시작) */
   ov_clear(OV_CLR_ALL);                         /* 157 덧판: 코어가 새로 시작 — 메뉴·멈춤·막기 지움 */
   ov_tag_clear();
   CORE_UNLOCK();
}

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

/* 연습 경기 이어가기 — 상태 파일 옆 «<경로>.prac»: "PRC1" · 허브 크기 · 설정 크기 · 상태 크기 · 상태 FNV-1a · 허브 · 설정.
   연습 경기(허브 H_MATCH) 중에 저장할 때만 쓰고, 아니면 지운다(옛 것이 다른 상태에 붙지 않게). 불러올 때 상태 크기·지문이 맞아야 쓴다. */
#define PRAC_SIDE_MAX 4096
static uint32_t fnv1a(const void *d, size_t n)
{
   const uint8_t *b = (const uint8_t *)d; uint32_t h = 2166136261u;
   for (size_t i = 0; i < n; i++) { h ^= b[i]; h *= 16777619u; }
   return h;
}
static size_t prac_side_make(uint8_t *side, const void *state, size_t sz)
{
   size_t hn = league_practice_blob(side + 20, PRAC_SIDE_MAX - 20);
   if (!hn) return 0;
   size_t pn = practice_settings_get(side + 20 + hn, PRAC_SIDE_MAX - 20 - hn);
   if (!pn) return 0;
   uint32_t v[4] = { (uint32_t)hn, (uint32_t)pn, (uint32_t)sz, fnv1a(state, sz) };
   memcpy(side, "PRC1", 4); memcpy(side + 4, v, sizeof v);
   return 20 + hn + pn;
}
static void prac_side_write(const char *p, const uint8_t *side, size_t sn)
{
   char q[620]; snprintf(q, sizeof q, "%s.prac", p);
   if (!sn) { remove(q); return; }
   FILE *f = fopen(q, "wb");
   if (!f) return;
   size_t wr = fwrite(side, 1, sn, f);
   if (fclose(f) != 0 || wr != sn) remove(q);
}
static size_t prac_side_read(const char *p, uint8_t *side, const void *state, size_t sz)
{
   char q[620]; snprintf(q, sizeof q, "%s.prac", p);
   FILE *f = fopen(q, "rb");
   if (!f) return 0;
   size_t n = fread(side, 1, PRAC_SIDE_MAX, f);
   fclose(f);
   uint32_t v[4];
   if (n < 20 || memcmp(side, "PRC1", 4)) return 0;
   memcpy(v, side + 4, sizeof v);
   if ((size_t)20 + v[0] + v[1] != n || v[2] != (uint32_t)sz || v[3] != fnv1a(state, sz)) return 0;
   return n;
}
static void prac_side_apply(const uint8_t *side, size_t sn)
{
   if (!sn) { league_practice_resume(NULL, 0); return; }
   uint32_t v[4];
   memcpy(v, side + 4, sizeof v);
   practice_settings_set(side + 20 + v[0], v[1]);
   league_practice_resume(side + 20, v[0]);
}

/* 경기 컬러 — 상태 파일 옆 «<경로>.look»: "LOK1" · 상태 크기 · 상태 FNV-1a · look 덩어리(16바이트 — [5] 경기 베기 색). 경기 컬러나 베기 색이 있을 때만 쓰고, 없으면 지운다.
   불러올 때 크기·지문이 맞아야 쓴다(.prac 와 같은 규칙) — .look 없이 불러온 상태는 원래 색(저장 직전에 look_before_save 가 팔레트를 되돌려 둠) */
#define LOOK_SIDE_MAX 64
static size_t look_side_make(uint8_t *side, const void *state, size_t sz)
{
   size_t ln = look_blob(side + 12, LOOK_SIDE_MAX - 12);
   if (!ln) return 0;
   uint32_t v[2] = { (uint32_t)sz, fnv1a(state, sz) };
   memcpy(side, "LOK1", 4); memcpy(side + 4, v, sizeof v);
   return 12 + ln;
}
static void look_side_write(const char *p, const uint8_t *side, size_t sn)
{
   char q[620]; snprintf(q, sizeof q, "%s.look", p);
   if (!sn) { remove(q); return; }
   FILE *f = fopen(q, "wb");
   if (!f) return;
   size_t wr = fwrite(side, 1, sn, f);
   if (fclose(f) != 0 || wr != sn) remove(q);
}
static size_t look_side_read(const char *p, uint8_t *side, const void *state, size_t sz)
{
   char q[620]; snprintf(q, sizeof q, "%s.look", p);
   FILE *f = fopen(q, "rb");
   if (!f) return 0;
   size_t n = fread(side, 1, LOOK_SIDE_MAX, f);
   fclose(f);
   uint32_t v[2];
   if (n < 12 || memcmp(side, "LOK1", 4)) return 0;
   memcpy(v, side + 4, sizeof v);
   if (v[0] != (uint32_t)sz || v[1] != fnv1a(state, sz)) return 0;
   return n;
}

JNI(jint, nativeSaveState)(JNIEnv *env, jclass cls, jstring jpath)
{
   (void)cls;
   CORE_LOCK();
   if (!g_loaded || !c_serialize_size) { CORE_UNLOCK(); return -1; }
   practice_before_save();               /* 연습 메뉴 창·대미지 숫자·«2P» 그림, 허브 머리말·세로 칸을 원래대로 — 저장된 상태에 우리 그림이 안 섞이게(다음 프레임에 다시 그린다) */
   league_hub_before_save();
   look_before_save();                   /* 컬러: 세로 칸·아이콘·몸 팔레트 0·베기 색 팔레트를 원래 색으로(저장 직후 look_after_save 가 다시) */
   if (c_get_memory_data) cheat_before_save((uint8_t *)c_get_memory_data(RETRO_MEMORY_SYSTEM_RAM));   /* 올카드(이번만)로 8 을 쓴 소유 개수를 원래 값으로 — 저장된 상태에 8 이 안 실리게 */
   size_t sz = c_serialize_size();
   void *buf = sz ? malloc(sz) : NULL;
   int ok = buf && c_serialize(buf, sz);
   look_after_save();                    /* 컬러: 되돌린 것을 바로 우리 것으로(다음 화면 한 프레임이 원래 색으로 안 나오게) */
   if (c_get_memory_data) cheat_after_save((uint8_t *)c_get_memory_data(RETRO_MEMORY_SYSTEM_RAM));   /* before_save 가 돌려 둔 치트 값을 되씀 */
   uint8_t side[PRAC_SIDE_MAX];          /* 연습 경기 중이면 허브·설정 — 같은 잠금 안에서(상태와 한 순간) */
   size_t sn = ok ? prac_side_make(side, buf, sz) : 0;
   uint8_t lside[LOOK_SIDE_MAX];         /* 경기 컬러 — 같은 잠금 안에서 */
   size_t lsn = ok ? look_side_make(lside, buf, sz) : 0;
   CORE_UNLOCK();
   if (!ok) { free(buf); return -1; }
   char p[600]; jstr(env, jpath, p, sizeof(p));
   FILE *f = fopen(p, "wb");
   if (!f) { free(buf); return -1; }
   /* 쓰기·닫기 실패도 실패로 알린다 — 저장 공간이 모자라 잘린 파일을 «저장됨»으로 믿지 않게(가상 리그 체크포인트가 이 값을 본다) */
   size_t wr = fwrite(buf, 1, sz, f);
   int cl = fclose(f);
   free(buf);
   prac_side_write(p, side, sn);
   look_side_write(p, lside, lsn);
   return (wr == sz && cl == 0) ? 0 : -1;
}

JNI(jint, nativeLoadState)(JNIEnv *env, jclass cls, jstring jpath)
{
   (void)cls;
   if (!c_unserialize) return -1;
   char p[600]; jstr(env, jpath, p, sizeof(p));
   FILE *f = fopen(p, "rb");
   if (!f) return -1;
   fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
   void *buf = malloc((size_t)sz);
   if (fread(buf, 1, (size_t)sz, f) != (size_t)sz) { fclose(f); free(buf); return -1; }
   fclose(f);
   uint8_t side[PRAC_SIDE_MAX];
   size_t sn = prac_side_read(p, side, buf, (size_t)sz);
   uint8_t lside[LOOK_SIDE_MAX];
   size_t lsn = look_side_read(p, lside, buf, (size_t)sz);
   CORE_LOCK();
   int ok = g_loaded && c_unserialize(buf, (size_t)sz);
   if (ok) {
      core_ptrs(1);                                  /* 165 고침: 아래 look_after_load 가 쓰는 화면 램·팔레트·바꿔 쓰기 함수 = 지금 코어 것(첫 프레임 전이어도) */
      practice_forget(); league_practice_title();   /* 연습 대전: 램·화면이 통째로 바뀜 — 지금 것은 되돌리지 않고 잊는다 */
      prac_side_apply(side, sn);                     /* 연습 경기 중에 저장한 상태면 그 연습 경기로(아니면 기다리던 것도 버림) */
      look_forget();                                /* 컬러: 화면 칸은 잊고, 경기 컬러는 .look 이 맞을 때만(아니면 버림) */
      look_resume(lsn ? lside + 12 : NULL, lsn ? lsn - 12 : 0);
      if (c_get_memory_data && c_get_memory_size)    /* 컬러: 불러온 램으로 바꿔 쓰기 표를 새로 넣고 지금 팔레트도(첫 프레임부터 경기 컬러·베기 색 — 끈 판이면 원래 색) */
         look_after_load((const uint8_t *)c_get_memory_data(RETRO_MEMORY_SYSTEM_RAM), c_get_memory_size(RETRO_MEMORY_SYSTEM_RAM));
      cheat_forget();                                /* 치트: 램이 통째로 바뀜 — 보관한 카드는 버리고 다음 프레임부터 새로 */
      ov_clear(OV_CLR_ALL);                          /* 157 덧판: 코어 상태가 통째로 바뀜 */
      ov_tag_clear();
   }
   CORE_UNLOCK();
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

JNI(void, nativeSaveSram)(JNIEnv *env, jclass cls) { (void)env; (void)cls; CORE_LOCK(); if (g_loaded) sram_save(); CORE_UNLOCK(); }
