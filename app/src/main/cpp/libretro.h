/* Minimal libretro API subset - enough to host a standard core.
   Based on the public libretro.h API (public domain / unlicense). */
#ifndef POCKETCORE_LIBRETRO_H
#define POCKETCORE_LIBRETRO_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#define RETRO_API_VERSION 1

/* --- device / input ------------------------------------------------ */
#define RETRO_DEVICE_NONE   0
#define RETRO_DEVICE_JOYPAD 1

#define RETRO_DEVICE_ID_JOYPAD_B      0
#define RETRO_DEVICE_ID_JOYPAD_Y      1
#define RETRO_DEVICE_ID_JOYPAD_SELECT 2
#define RETRO_DEVICE_ID_JOYPAD_START  3
#define RETRO_DEVICE_ID_JOYPAD_UP     4
#define RETRO_DEVICE_ID_JOYPAD_DOWN   5
#define RETRO_DEVICE_ID_JOYPAD_LEFT   6
#define RETRO_DEVICE_ID_JOYPAD_RIGHT  7
#define RETRO_DEVICE_ID_JOYPAD_A      8
#define RETRO_DEVICE_ID_JOYPAD_X      9
#define RETRO_DEVICE_ID_JOYPAD_L     10
#define RETRO_DEVICE_ID_JOYPAD_R     11

/* --- memory -------------------------------------------------------- */
#define RETRO_MEMORY_SAVE_RAM  0
#define RETRO_MEMORY_RTC       1
#define RETRO_MEMORY_SYSTEM_RAM 2
#define RETRO_MEMORY_VIDEO_RAM  3

/* --- environment --------------------------------------------------- */
#define RETRO_ENVIRONMENT_GET_CAN_DUPE              3
#define RETRO_ENVIRONMENT_SET_MESSAGE               6
#define RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL     8
#define RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY      9
#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT         10
#define RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS    11
#define RETRO_ENVIRONMENT_GET_VARIABLE             15
#define RETRO_ENVIRONMENT_SET_VARIABLES            16
#define RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE      17
#define RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME      18
#define RETRO_ENVIRONMENT_GET_LIBRETRO_PATH        19
#define RETRO_ENVIRONMENT_GET_LOG_INTERFACE        27
#define RETRO_ENVIRONMENT_GET_PERF_INTERFACE       28
#define RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY 30
#define RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY       31
#define RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO       32
#define RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO       34
#define RETRO_ENVIRONMENT_SET_CONTROLLER_INFO      35
#define RETRO_ENVIRONMENT_SET_MEMORY_MAPS          (36|0x10000)
#define RETRO_ENVIRONMENT_SET_GEOMETRY             37
#define RETRO_ENVIRONMENT_GET_USERNAME             38
#define RETRO_ENVIRONMENT_GET_LANGUAGE             39
#define RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE   (47|0x800000)
#define RETRO_ENVIRONMENT_GET_INPUT_BITMASKS       (51|0x800000)
#define RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION 52
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS         53
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL    54
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY 55
#define RETRO_ENVIRONMENT_GET_FASTFORWARDING       (57|0x800000)
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2      67
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL 68
#define RETRO_ENVIRONMENT_SET_MESSAGE_EXT          (60|0x800000)

/* --- pixel format -------------------------------------------------- */
enum retro_pixel_format {
   RETRO_PIXEL_FORMAT_0RGB1555 = 0,
   RETRO_PIXEL_FORMAT_XRGB8888 = 1,
   RETRO_PIXEL_FORMAT_RGB565   = 2
};

enum retro_log_level { RETRO_LOG_DEBUG = 0, RETRO_LOG_INFO, RETRO_LOG_WARN, RETRO_LOG_ERROR };

typedef void (*retro_log_printf_t)(enum retro_log_level level, const char *fmt, ...);
struct retro_log_callback { retro_log_printf_t log; };

struct retro_variable { const char *key; const char *value; };

struct retro_game_geometry {
   unsigned base_width, base_height, max_width, max_height;
   float aspect_ratio;
};
struct retro_system_timing { double fps; double sample_rate; };
struct retro_system_av_info {
   struct retro_game_geometry geometry;
   struct retro_system_timing timing;
};
struct retro_system_info {
   const char *library_name;
   const char *library_version;
   const char *valid_extensions;
   bool need_fullpath;
   bool block_extract;
};
struct retro_game_info {
   const char *path;
   const void *data;
   size_t size;
   const char *meta;
};

/* --- callbacks ----------------------------------------------------- */
typedef bool   (*retro_environment_t)(unsigned cmd, void *data);
typedef void   (*retro_video_refresh_t)(const void *data, unsigned width, unsigned height, size_t pitch);
typedef void   (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void   (*retro_input_poll_t)(void);
typedef int16_t(*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);

#endif
