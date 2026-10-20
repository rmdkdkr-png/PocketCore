/* qbot_hx core.so rom state frames outdir — 191 코어 타격 알림·이펙트·몸 번쩍 시험(앱 판정과 견주기). 여럿 띄우기는 qbot_hx_par.sh.
   HXMODE=app  : 앱 쪽 판정(callout.c·hitflash.c 를 호스트에서, native.c 차례 — 120Hz 합성 호출엔 안 돎) + 코어 옵션 ngp_hit_* 끔
   HXMODE=core : 코어 옵션 켬(코어가 판정·그리기) — retro_ngp_hit_debug 로 사건을 센다
   HXMODE=off  : 둘 다 끔(바탕 해시 비교)
   ev.txt = «EV 호출 종류 늘어난 수» · «FLASH 호출 쪽» · «FX 호출 지금 수» 줄(앱·코어 같은 꼴 — diff 로 견줌).
   환경: PRELUDE(«n mask» — 고르기·대전까지) · CHAR · POKEAT · SEED · IDLE(대전 실제 프레임) · CPF(프레임당 호출, 기본 2) · PROBE(앞 강베기 대본) · NOFIX
         OPTS="k=v,…" · HZ · FAKECLK(속도 감시 시계 = 호출 / HZ) · HASHALL(hash.txt) · WHITELOG(«W 호출 흰 화소 수») · APPCOMP(앱 모드에서 앱식 이펙트 합성 뒤 해시)
         DUMP=a-b · DUMPLIST=파일 · DUMPEV=n(사건마다 n 호출 PPM, DUMPMAX) · SAVETEST(번쩍 다음 호출 앞 저장 — 상태에 흰 몸 없나·팔레트 그대로인가)
         LOADAT=f(그 프레임 상태 떠 두고 f+300 에 불러옴) · FRA(프론트엔드 런어헤드 1 흉내: 숨은 호출 → 저장 → 보이는 호출 → 불러오기)
   ⚠ 롬 RTC 가 벽시계를 읽는다 — 견줄 판은 같은 초에 띄울 것(qbot_hx_par.sh). 앱 libretro.h 의 GET_AUDIO_VIDEO_ENABLE 값은 틀려서 여기선 47|0x10000 을 직접 쓴다. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdarg.h>
#include <dlfcn.h>
#include "callout.h"
#include "hitflash.h"
#include "libretro.h"
static struct { char k[64], v[64]; } opt[48]; static int nopt = 0, var_dirty = 1;
static void setopt(const char *kv) { const char *e = strchr(kv, '='); if (!e) return; int i; char k[64]; snprintf(k, sizeof k, "%.*s", (int)(e - kv), kv);
  for (i = 0; i < nopt; i++) if (!strcmp(opt[i].k, k)) break; if (i == nopt) nopt++;
  snprintf(opt[i].k, 64, "%s", k); snprintf(opt[i].v, 64, "%s", e + 1); var_dirty = 1; }
static float hz = 60.f; static double fps = 0;
static void logcb(enum retro_log_level l, const char *fmt, ...) { (void)l; (void)fmt; }
static int av_enable = 3;
static bool env(unsigned cmd, void *data) {
  switch (cmd) {
  case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: return true;
  case RETRO_ENVIRONMENT_GET_VARIABLE: { struct retro_variable *v = data; for (int i = 0; i < nopt; i++) if (!strcmp(opt[i].k, v->key)) { v->value = opt[i].v; return true; } v->value = NULL; return false; }
  case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool *)data = var_dirty; var_dirty = 0; return true;
  case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE: *(float *)data = hz; return true;
  case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: fps = ((struct retro_system_av_info *)data)->timing.fps; return true;
  case RETRO_ENVIRONMENT_SET_GEOMETRY: return true;
  case (47 | 0x10000): *(int *)data = av_enable; return true;   /* 앱 libretro.h 의 값(0x800000)은 틀림 — 코어 쪽 값 */
  case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: ((struct retro_log_callback *)data)->log = logcb; return true;
  case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char **)data = getenv("SYSDIR") ? getenv("SYSDIR") : "/tmp"; return true;
  case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool *)data = true; return true;
  case RETRO_ENVIRONMENT_SET_MESSAGE: return true;
  default: return false; } }
static FILE *hf, *evf; static long g_calls; static const char *outdir; static unsigned char *dlist; static long dlist_n = 4000000; static long d0 = -1, d1 = -1;
static unsigned g_w, g_h; static uint16_t g_frame[640 * 608]; static int g_frame_ok;
static void video(const void *d, unsigned w, unsigned h, size_t pitch) {
  g_frame_ok = 0;
  if (!d) return;
  g_w = w; g_h = h;
  for (unsigned y = 0; y < h && y < 608; y++) memcpy(g_frame + y * w, (const uint8_t *)d + y * pitch, (size_t)w * 2);
  g_frame_ok = 1;
}
static void hash_frame(void)
{
  if (!getenv("HASHALL") || !g_frame_ok) return;
  uint64_t hh = 1469598103934665603ULL;
  for (unsigned i = 0; i < g_w * g_h; i++) hh = (hh ^ g_frame[i]) * 1099511628211ULL;
  fprintf(hf, "%ld %ux%u %016llx\n", g_calls, g_w, g_h, (unsigned long long)hh);
}
static uint32_t rgb12(int c) { uint32_t r = (c & 15) * 17, g = ((c >> 4) & 15) * 17, b = ((c >> 8) & 15) * 17; return r | g << 8 | b << 16 | 0xFF000000u; }
/* APPCOMP: 앱처럼 이펙트를 그림 줄에 얹는다(callout_fx_draw — RGBA) → 565 로 되돌려 g_frame 에 */
static void app_composite(void)
{
  static uint8_t rgba[640 * 608 * 4];
  int sub = g_w == 640 ? 4 : 1;
  if (!g_frame_ok || (g_w != 160 && g_w != 640)) return;
  memset(rgba, 0, (size_t)g_w * g_h * 4);
  callout_fx_draw(rgba, NULL, 0, (int)g_h, (int)g_w, 0, 0, sub, rgb12);
  for (unsigned i = 0; i < g_w * g_h; i++) if (rgba[i * 4 + 3]) {
    int r = rgba[i * 4], g = rgba[i * 4 + 1], b = rgba[i * 4 + 2];
    g_frame[i] = (uint16_t)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)); }
}
static void dump(long call)
{
  char p[700]; snprintf(p, sizeof p, "%s/f%06ld.ppm", outdir, call); FILE *o = fopen(p, "wb"); if (!o) return;
  fprintf(o, "P6 %u %u 255\n", g_w, g_h);
  for (unsigned i = 0; i < g_w * g_h; i++) { uint16_t q = g_frame[i]; unsigned char c[3] = { (unsigned char)(((q >> 11) & 31) << 3 | ((q >> 11) & 31) >> 2), (unsigned char)(((q >> 5) & 63) << 2 | ((q >> 5) & 63) >> 4), (unsigned char)((q & 31) << 3 | (q & 31) >> 2) }; fwrite(c, 1, 3, o); }
  fclose(o);
}
static void a1(int16_t l, int16_t r) { (void)l; (void)r; }
static size_t ab(const int16_t *d, size_t f) { (void)d; return f; }
typedef void (*retro_run_t)(void); static retro_run_t g_run;
static void *(*g_palram)(void); static void (*g_remap)(int, int, int, int);
static const uint8_t *(*g_shown)(int *, const uint8_t **);
static void (*g_hitdbg)(int v[11]);
static void (*g_fgstate)(int *, int *, unsigned *);
static size_t (*g_sersize)(void); static bool (*g_ser)(void *, size_t);
static const uint8_t *g_ram; static int mode;   /* 0 app · 1 core · 2 off */
static int sh_prev = -1;
static void flsync(void) { if (!g_remap) return; static int had[2];
  for (int s2 = 0; s2 < 2; s2++) { uint16_t f[3], t[3]; int p = s2 ? 5 : 0, on = hitflash_remap(s2, f, t);
    if (on) for (int j = 0; j < 3; j++) g_remap(p * 4 + 1 + j, 0, f[j], t[j]);
    else if (had[s2]) for (int j = 0; j < 3; j++) g_remap(p * 4 + 1 + j, 0, -1, 0);
    had[s2] = on; } }
static int g_ev_now = 0;
static int pv[11], pfx = 0, save_next = 0, save_bad = 0, save_n = 0;
static int white_px(void) { int n = 0; for (unsigned i = 0; i < g_w * g_h; i++) n += g_frame[i] == 0xFFFF; return n; }
static void run1(void) {
  if (save_next && g_ser && g_sersize && g_palram) {   /* 번쩍 중 저장 — 상태 안 팔레트(몸 0·5)가 흰색이 아니어야, 저장 뒤 팔레트 램은 그대로 */
    const uint8_t *P = (const uint8_t *)g_palram(); uint8_t before[0x200]; memcpy(before, P, sizeof before);
    size_t n = g_sersize(); uint8_t *b = malloc(n); bool ok = g_ser(b, n);
    int same = !memcmp(before, P, sizeof before), whitein = 0;
    /* 상태에서 팔레트 램을 찾는다: 몸 팔레트 0 의 4색 + 1~4 팔레트 일부(앞 40바이트)를 그대로 찾되, 흰색 세 칸이면 «흰 몸이 상태에 있음» */
    int act = hitflash_active();
    for (size_t i = 0; i + 0x200 <= n; i++) if (!memcmp(b + i, before + 0x30, 0x40)) { /* 팔레트 6~13 이 같은 곳 = 팔레트 램 사본 */
        const uint8_t *q = b + i - 0x30; for (int s = 0; s < 2; s++) { int p = s ? 5 : 0, w = 0; for (int j = 1; j <= 3; j++) w += ((q[p * 8 + j * 2] | q[p * 8 + j * 2 + 1] << 8) & 0xFFF) == 0xFFF; if (w >= 2) whitein |= 1 << s; } break; }
    fprintf(evf, "SAVE %ld ok %d same %d whitein %d\n", g_calls, ok, same, whitein); save_n++; if (!ok || !same || whitein) save_bad++;
    (void)act; free(b); save_next = 0; }
  int synth = 0;
  if (g_fgstate) { int fa = 0, fp = 0; unsigned ff = 0; g_fgstate(&fa, &fp, &ff); synth = fa && fp == 1; }
  g_run(); g_calls++;
  if (mode == 0 && !synth) {
    const uint8_t *sh_ram = NULL, *sh_pal = NULL; int sh_lead = 0;
    if (g_shown) sh_ram = g_shown(&sh_lead, &sh_pal);
    if (!sh_ram) sh_lead = 0;
    if (g_palram) hitflash_tick((uint8_t *)g_palram());
    flsync();
    if (sh_lead != sh_prev) { callout_resync(); sh_prev = sh_lead; }
    callout_fx_pal(sh_pal ? sh_pal : g_palram ? (const uint8_t *)g_palram() : NULL);
    int a[8], b[8]; callout_stats(a); callout_tick(sh_ram ? sh_ram : g_ram, 16384, g_calls / 60.0); callout_stats(b);
    for (int k = 0; k < 8; k++) if (b[k] != a[k]) { fprintf(evf, "EV %ld %d %d\n", g_calls, k, b[k] - a[k]); if (k < 4) g_ev_now = 1; }
    int fs = callout_flash_take(); if (fs && g_palram) { hitflash_start((uint8_t *)g_palram(), fs - 1); flsync(); fprintf(evf, "FLASH %ld %d\n", g_calls, fs - 1); g_ev_now = 1; if (getenv("SAVETEST")) save_next = 1; }
    int nfx = callout_fx_count(); if (nfx != pfx) fprintf(evf, "FX %ld %d\n", g_calls, nfx); pfx = nfx;
  } else if (mode == 1 && g_hitdbg) {
    int v[11]; g_hitdbg(v);
    for (int k = 0; k < 8; k++) if (v[k] != pv[k]) { fprintf(evf, "EV %ld %d %d\n", g_calls, k, v[k] - pv[k]); if (k < 4) g_ev_now = 1; }
    if (v[10] != pv[10]) { fprintf(evf, "FLASH %ld %d\n", g_calls, (v[9] & 2) ? 1 : 0); g_ev_now = 1; if (getenv("SAVETEST")) save_next = 1; }
    if (v[8] != pv[8]) fprintf(evf, "FX %ld %d\n", g_calls, v[8]);
    memcpy(pv, v, sizeof pv);
  }
  {  /* DUMPEV=n: 라벨·번쩍 사건마다 그 호출부터 n 호출 PPM(DUMPMAX 번까지) */
    static long until = -1; static int nev = 0; static int lastn = 0;
    if (getenv("DUMPEV")) { long n = ftell(evf);
      if (n != lastn) { /* 이번 호출에 사건 줄이 생겼나 — 마지막 줄만 훑기 귀찮으니 길이로 */ }
      lastn = (int)n;
      if (g_ev_now && nev < (getenv("DUMPMAX") ? atoi(getenv("DUMPMAX")) : 6)) { nev++; until = g_calls + atol(getenv("DUMPEV")); }
      if (g_calls <= until && g_frame_ok) dump(g_calls); }
    g_ev_now = 0; }
  if (mode == 0 && getenv("APPCOMP")) app_composite();
  hash_frame();
  if (getenv("WHITELOG") && g_frame_ok) fprintf(evf, "W %ld %d\n", g_calls, white_px());
  if (g_frame_ok && ((dlist && g_calls < dlist_n && dlist[g_calls]) || (g_calls >= d0 && g_calls <= d1))) dump(g_calls);
}
static double fakeclk(void) { return g_calls / (double)hz; }
static int mask; static void poll(void) {}
static int16_t st(unsigned port, unsigned dev, unsigned idx, unsigned id) { (void)idx; return port == 0 && dev == RETRO_DEVICE_JOYPAD && id < 16 ? (mask >> id) & 1 : 0; }
#define S(n) n##_t n = (n##_t)dlsym(h, #n)
typedef void (*retro_set_environment_t)(retro_environment_t); typedef void (*retro_init_t)(void);
typedef void (*retro_set_video_refresh_t)(retro_video_refresh_t); typedef void (*retro_set_audio_sample_t)(retro_audio_sample_t);
typedef void (*retro_set_audio_sample_batch_t)(retro_audio_sample_batch_t); typedef void (*retro_set_input_poll_t)(retro_input_poll_t);
typedef void (*retro_set_input_state_t)(retro_input_state_t); typedef bool (*retro_load_game_t)(const struct retro_game_info *);
typedef bool (*retro_unserialize_t)(const void *, size_t); typedef void *(*retro_get_memory_data_t)(unsigned);
typedef void (*retro_unload_game_t)(void); typedef void (*retro_deinit_t)(void);
int main(int argc, char **argv) {
  if (argc < 6) { fprintf(stderr, "usage\n"); return 2; }
  void *h = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL); if (!h) { fprintf(stderr, "%s\n", dlerror()); return 1; }
  S(retro_set_environment); S(retro_init); S(retro_set_video_refresh); S(retro_set_audio_sample); S(retro_set_audio_sample_batch);
  S(retro_set_input_poll); S(retro_set_input_state); S(retro_load_game); S(retro_run); g_run = retro_run; S(retro_unserialize); S(retro_get_memory_data);
  S(retro_unload_game); S(retro_deinit);
  g_sersize = (size_t (*)(void))dlsym(h, "retro_serialize_size"); g_ser = (bool (*)(void *, size_t))dlsym(h, "retro_serialize");
  outdir = argv[5]; long frames = atol(argv[4]); (void)frames;
  const char *m = getenv("HXMODE"); mode = !m || !strcmp(m, "core") ? 1 : !strcmp(m, "app") ? 0 : 2;
  if (getenv("HZ")) hz = atof(getenv("HZ"));
  if (getenv("DUMP")) sscanf(getenv("DUMP"), "%ld-%ld", &d0, &d1);
  if (getenv("DUMPLIST")) { FILE *lf = fopen(getenv("DUMPLIST"), "r"); long v; dlist = calloc(dlist_n, 1); if (lf) { while (fscanf(lf, "%ld", &v) == 1) if (v >= 0 && v < dlist_n) dlist[v] = 1; fclose(lf); } }
  setopt("ngp_language=japanese"); setopt("ngp_runahead=0"); setopt("ngp_framegen=disabled");
  setopt(mode == 1 ? "ngp_hit_callout=enabled" : "ngp_hit_callout=disabled");
  setopt(mode == 1 ? "ngp_hit_fx=enabled" : "ngp_hit_fx=disabled");
  setopt(mode == 1 ? "ngp_hit_flash=enabled" : "ngp_hit_flash=disabled");
  if (getenv("OPTS")) { char t[1024]; snprintf(t, sizeof t, "%s", getenv("OPTS")); for (char *p = strtok(t, ","); p; p = strtok(NULL, ",")) setopt(p); }
  retro_set_environment(env); retro_init(); retro_set_video_refresh(video); retro_set_audio_sample(a1); retro_set_audio_sample_batch(ab);
  retro_set_input_poll(poll); retro_set_input_state(st);
  FILE *rf = fopen(argv[2], "rb"); static uint8_t rom[4 << 20]; size_t rs = fread(rom, 1, sizeof rom, rf); fclose(rf);
  struct retro_game_info gi = { argv[2], rom, rs, NULL }; if (!retro_load_game(&gi)) { fprintf(stderr, "load fail\n"); return 1; }
  char hp[600]; snprintf(hp, sizeof hp, "%s/hash.txt", outdir); hf = fopen(hp, "w");
  snprintf(hp, sizeof hp, "%s/ev.txt", outdir); evf = fopen(hp, "w");
  if (strcmp(argv[3], "-")) { FILE *sf = fopen(argv[3], "rb"); static uint8_t sb[1 << 22]; size_t sn = fread(sb, 1, sizeof sb, sf); fclose(sf); fprintf(stderr, "loadst %d\n", (int)retro_unserialize(sb, sn)); }
  uint8_t *ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM); g_ram = ram;
  g_palram = (void *(*)(void))dlsym(h, "retro_ngp_palram"); g_remap = (void (*)(int, int, int, int))dlsym(h, "retro_ngp_palremap");
  g_shown = (const uint8_t *(*)(int *, const uint8_t **))dlsym(h, "retro_ngp_shown_ram"); g_hitdbg = (void (*)(int *))dlsym(h, "retro_ngp_hit_debug");
  g_fgstate = (void (*)(int *, int *, unsigned *))dlsym(h, "retro_ngp_fg_state");
  { void (*setclk)(double (*)(void)) = (void (*)(double (*)(void)))dlsym(h, "retro_ngp_fg_set_clock"); if (setclk && getenv("FAKECLK")) setclk(fakeclk); }
  if (mode == 1 && !g_hitdbg) { fprintf(stderr, "core has no hit module\n"); return 1; }
  if (mode == 0) { callout_reset(); callout_enable(1); callout_fx_mode(FXM_ON); callout_flash_enable(1); }
  int chr_id = getenv("CHAR") ? atoi(getenv("CHAR")) : -1, pokeat = getenv("POKEAT") ? atoi(getenv("POKEAT")) : 17;
  long idle = getenv("IDLE") ? atol(getenv("IDLE")) : 1200;
  FILE *sc = fopen(getenv("PRELUDE"), "r"); char ln[256]; int lno = 0;
  while (sc && fgets(ln, sizeof ln, sc)) { int n; unsigned mm; if (sscanf(ln, "%d %x", &n, &mm) != 2) continue; lno++;
    if (lno == pokeat && chr_id >= 0) ram[0x0E3C] = (uint8_t)(chr_id * 4);
    mask = (int)mm; n = (n + 1) / 2; for (int i = 0; i < n; i++) { run1(); run1(); } }
  if (sc) fclose(sc); mask = 0;
  unsigned rseed = getenv("SEED") ? (unsigned)atoi(getenv("SEED")) : 1u;
  #define RND() (rseed = rseed * 1103515245u + 12345u, (rseed >> 16) & 0x7fff)
  int act = 0, left = 0; long loadat = getenv("LOADAT") ? atol(getenv("LOADAT")) : -1; uint8_t *lst = NULL; size_t lsn = 0;
  int calls_per = getenv("CPF") ? atoi(getenv("CPF")) : 2;
  for (long f = 0; f < idle; f++) {
    int b = ram[0xA7] == 241 && !(ram[0x0E36] == 0xFF && ram[0x0E37] == 0xFF);
    if (b && getenv("PROBE")) {   /* 앞 강베기 대본: 쉼 40 · 중립 A 8 · 쉼 40 · 앞+A 8 · 쉼 40 · 아래+A 8 · 쉼 40 · 뒤+A 8(실제 프레임 × CPF 호출) */
      static int ph = 0, cnt = 0;
      if (!getenv("NOFIX")) ram[0x1A46] = 0x80;
      int p1x = ram[0x0E38] | ram[0x0E39] << 8, p2x = ram[0x0E78] | ram[0x0E79] << 8;
      int fwd = p1x <= p2x ? 1 << 7 : 1 << 6, back = p1x <= p2x ? 1 << 6 : 1 << 7;
      static const int len[8] = { 40, 8, 40, 8, 40, 8, 40, 8 };
      if (cnt == 0) act = ph == 1 ? 1 : ph == 3 ? (1 | fwd) : ph == 5 ? (1 | (1 << 5)) : ph == 7 ? (1 | back) : 0;
      mask = act;
      if (++cnt >= len[ph]) { cnt = 0; ph = (ph + 1) % 8; act = 0; }
    } else if (b) {
      if (!getenv("NOFIX")) ram[0x1A46] = 0x80;
      if (left <= 0) {
        int r = RND() % 100;
        static const int dirs[8] = { 1<<6, 1<<7, 1<<4, 1<<5, (1<<5)|(1<<6), (1<<5)|(1<<7), (1<<4)|(1<<6), (1<<4)|(1<<7) };
        int d = dirs[RND() % 8];
        if (r < 28)      { act = d; left = 10 + RND() % 50; }
        else if (r < 44) { act = (RND() & 1) ? 1 << 0 : 1 << 8; left = 3; }
        else if (r < 50) { act = ((RND() & 1) ? d : 0) | (1 << 0) | (1 << 8); left = 3; }
        else if (r < 72) { act = ((RND() % 5) ? d : 0) | (1 << 9); left = 3; }
        else if (r < 80) { act = d | ((RND() & 1) ? 1 << 0 : 1 << 8); left = 3; }
        else             { act = 0; left = 30 + RND() % 120; }
      }
      mask = act; left--;
      if (left == 0 && act && (act & ((1<<0)|(1<<8)|(1<<9)))) { act = 0; left = 6 + RND() % 20; }
    } else {
      mask = 0; act = 0; left = 0;
      if (f % 25 < 4) mask |= 1 << 0;
      if (f % 55 < 4) mask |= 1 << 3;
    }
    if (f == loadat && g_ser && g_sersize) {   /* LOADAT: 그 프레임 상태를 떠 두었다가 LOADAT+300 에 불러온다(불러오기 지우기 시험) */
      lsn = g_sersize(); lst = malloc(lsn); g_ser(lst, lsn); fprintf(evf, "SAVED %ld\n", g_calls); }
    if (loadat >= 0 && f == loadat + 300 && lst) { retro_unserialize(lst, lsn); fprintf(evf, "LOADED %ld\n", g_calls); if (mode == 0) { callout_reset(); hitflash_forget(); flsync(); } }
    if (getenv("FRA") && g_ser && g_sersize) {   /* 프론트엔드 런어헤드 1(레트로아크 한 인스턴스): 숨은 호출 → 저장 → 보이는 호출 → 불러오기 */
      static uint8_t *fb; static size_t fn;
      for (int c = 0; c < calls_per; c++) {
        av_enable = 0; g_run(); av_enable = 3;
        if (!fb) { fn = g_sersize(); fb = malloc(fn); }
        g_ser(fb, fn);
        run1();
        retro_unserialize(fb, fn);
      }
      continue;
    }
    for (int c = 0; c < calls_per; c++) run1();
  }
  if (mode == 0) { int v[8]; callout_stats(v); fprintf(stderr, "app   first %d counter %d punish %d reversal %d | hits %d rounds %d throws %d starts %d\n", v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]); }
  if (mode == 1) { int v[11]; g_hitdbg(v); fprintf(stderr, "core  first %d counter %d punish %d reversal %d | hits %d rounds %d throws %d starts %d | flashes %d\n", v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[10]); }
  if (getenv("SAVETEST")) fprintf(stderr, "savetest %d bad %d\n", save_n, save_bad);
  fclose(evf); fclose(hf);
  retro_unload_game(); retro_deinit();
  fprintf(stderr, "calls %ld fps %.2f\n", g_calls, fps); return 0; }
