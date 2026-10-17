/* 157 덧판 — 빈 껍데기(설계 단계 w157-base). 네이티브 담당이 이 파일 전체를 채운다(계약서 docs/157_contract.md §3·§4).
 *
 * 지금 들어 있는 것
 *  - JNI 껍데기(Emu.nativeOv*) — 모양(인자·반환)이 계약이다. 몸통은 아래 ov_* 평범한 C 함수를 부른다(하네스가 ctypes 로 같은 것을 부름).
 *  - ov_ease · ov_rgb — 계약의 셈 그대로(Java Cue.ease 와 같아야 한다). 바꾸지 말 것.
 *  - 나머지 ov_* 는 아무것도 안 하는 자리(상태 OVS_VER = 0 → Java 는 «덧판 없음»으로 보고 옛 결과 카드).
 * native.c 에는 아직 연결하지 않았다(ov_tick·ov_compose·ov_hold·ov_in_block 자리는 계약서 §3.6).
 */
#include <jni.h>
#include <string.h>
#include "ovplane.h"

/* ── 계약 셈 ─────────────────────────────────────────────────────────── */

static int64_t floor_div16(int64_t v) { return v >= 0 ? (v >> 16) : -((-v + 65535) >> 16); }

/* 감속 보간 — t < t0 → from · t ≥ t0+dur(또는 dur ≤ 0) → to · 그 사이 e(u) 0..65536 */
int ov_ease(int ease, int t, int t0, int dur, int from, int to)
{
   if (dur <= 0 || t >= t0 + dur) return to;
   if (t <= t0) return from;
   int64_t u = ((int64_t)(t - t0) << 16) / dur;          /* 0..65535 */
   int64_t e;
   switch (ease) {
   case OV_EASE_OUT:  e = 65536 - (((65536 - u) * (65536 - u)) >> 16); break;
   case OV_EASE_IN:   e = (u * u) >> 16; break;
   case OV_EASE_STEP: e = 0; break;
   default:           e = u; break;
   }
   return from + (int)floor_div16((int64_t)(to - from) * e);
}

/* 12비트 0x0BGR → 코어와 같은 색(니블×17 → 565 → 8888). 돌려줌 0xAABBGGRR(리틀 엔디언 메모리 R,G,B,A) */
uint32_t ov_rgb(int c12)
{
   unsigned r8 = (unsigned)(c12 & 15) * 17, g8 = (unsigned)((c12 >> 4) & 15) * 17, b8 = (unsigned)((c12 >> 8) & 15) * 17;
   unsigned r5 = r8 >> 3, g6 = g8 >> 2, b5 = b8 >> 3;
   unsigned r = (r5 << 3) | (r5 >> 2), g = (g6 << 2) | (g6 >> 4), b = (b5 << 3) | (b5 >> 2);
   return 0xFF000000u | (b << 16) | (g << 8) | r;
}

/* ── 자리(네이티브 담당이 채움) ─────────────────────────────────────────── */

int  ov_layer(int layer, const uint8_t *tiles, int ntiles, int tile_base, const uint16_t *map, int cols, int rows,
              int col0, int row0, const uint16_t *pals, int npals, int pal_base)
{ (void)layer; (void)tiles; (void)ntiles; (void)tile_base; (void)map; (void)cols; (void)rows; (void)col0; (void)row0; (void)pals; (void)npals; (void)pal_base; return 0; }
int  ov_cells(int layer, const uint16_t *cells, int col0, int row0, int cols, int rows, int tile_base)
{ (void)layer; (void)cells; (void)col0; (void)row0; (void)cols; (void)rows; (void)tile_base; return 0; }
int  ov_recolor(int layer, int col0, int row0, int cols, int rows, int pal)
{ (void)layer; (void)col0; (void)row0; (void)cols; (void)rows; (void)pal; return 0; }
int  ov_pal(int layer, int pal, int c1, int c2, int c3) { (void)layer; (void)pal; (void)c1; (void)c2; (void)c3; return 0; }
int  ov_quad(int layer, int idx, int x, int y, int tile, int pal, int flags)
{ (void)layer; (void)idx; (void)x; (void)y; (void)tile; (void)pal; (void)flags; return 0; }
int  ov_pane(int layer, int idx, int c0, int r0, int c1, int r1, int dx, int dy, int visible)
{ (void)layer; (void)idx; (void)c0; (void)r0; (void)c1; (void)r1; (void)dx; (void)dy; (void)visible; return 0; }
int  ov_scroll(int layer, int x, int y, int clip_y0, int clip_y1) { (void)layer; (void)x; (void)y; (void)clip_y0; (void)clip_y1; return 0; }
int  ov_base(int slot, int mode, int y0, int y1, int arg, int color) { (void)slot; (void)mode; (void)y0; (void)y1; (void)arg; (void)color; return 0; }
int  ov_cue(const int32_t *ops, int nops, int flags) { (void)ops; (void)nops; (void)flags; return 0; }
void ov_hold_set(int on) { (void)on; }
void ov_block_set(int bits) { (void)bits; }
void ov_clear(int mask) { (void)mask; }
void ov_status(int v[OV_NSTAT])
{
   for (int i = 0; i < OV_NSTAT; i++) v[i] = 0;
   v[OVS_VER] = 0;                 /* 빈 껍데기 */
   v[OVS_T] = v[OVS_END] = -1;
   v[OVS_GPAUSE] = v[OVS_W1] = v[OVS_W2] = -1;
}
int  ov_hold(void) { return 0; }
int  ov_in_block(void) { return 0; }
void ov_tick(double now_s, int latch) { (void)now_s; (void)latch; }
int  ov_active(void) { return 0; }
int  ov_find_rect(unsigned fbw, unsigned fbh, int *x0, int *y0, int *sub)
{ (void)fbw; (void)fbh; if (x0) *x0 = 0; if (y0) *y0 = 0; if (sub) *sub = 1; return 0; }
const uint8_t *ov_compose(const uint8_t *src, unsigned w, unsigned h, int src_changed, int *changed)
{ (void)src; (void)w; (void)h; (void)src_changed; if (changed) *changed = 0; return NULL; }
void ov_viewport(int vp_x, int vp_y, int vp_w, int vp_h, int view_h, unsigned fbw, unsigned fbh)
{ (void)vp_x; (void)vp_y; (void)vp_w; (void)vp_h; (void)view_h; (void)fbw; (void)fbh; }
void ov_game_ram(const uint8_t *ram, size_t n) { (void)ram; (void)n; }

/* ── JNI(Emu.java) — 모양이 계약. 배열은 길이를 보고 GetXxxArrayRegion 으로 복사한다 ───────────── */
#define JNI(ret, name) JNIEXPORT ret JNICALL Java_com_dudu_pocketcore_Emu_##name

JNI(jboolean, nativeOvLayer)(JNIEnv *env, jclass cls, jint layer, jbyteArray tiles, jint tileBase, jshortArray map,
                             jint cols, jint rows, jint col0, jint row0, jshortArray pals, jint palBase)
{
   (void)cls;
   static uint8_t  tb[OV_CHUNK_MAX * 16];
   static uint16_t mb[OV_W_COLS * OV_L_ROWS];
   static uint16_t pb[OV_PALS * 4];
   int nt = 0, np = 0;
   if (tiles) {
      jsize n = (*env)->GetArrayLength(env, tiles);
      if (n % 16 || n > (jsize)sizeof tb) return JNI_FALSE;
      (*env)->GetByteArrayRegion(env, tiles, 0, n, (jbyte *)tb); nt = n / 16;
   }
   if (map) {
      jsize n = (*env)->GetArrayLength(env, map);
      if (cols <= 0 || rows <= 0 || n != cols * rows || n > (jsize)(sizeof mb / 2)) return JNI_FALSE;
      (*env)->GetShortArrayRegion(env, map, 0, n, (jshort *)mb);
   }
   if (pals) {
      jsize n = (*env)->GetArrayLength(env, pals);
      if (n % 4 || n > (jsize)(sizeof pb / 2)) return JNI_FALSE;
      (*env)->GetShortArrayRegion(env, pals, 0, n, (jshort *)pb); np = n / 4;
   }
   return ov_layer(layer, tiles ? tb : NULL, nt, tileBase, map ? mb : NULL, cols, rows, col0, row0, pals ? pb : NULL, np, palBase) ? JNI_TRUE : JNI_FALSE;
}

JNI(jboolean, nativeOvCells)(JNIEnv *env, jclass cls, jint layer, jshortArray cells, jint col0, jint row0, jint cols, jint rows, jint tileBase)
{
   (void)cls;
   static uint16_t cb[OV_W_COLS * OV_L_ROWS];
   if (!cells) return JNI_FALSE;
   jsize n = (*env)->GetArrayLength(env, cells);
   if (cols <= 0 || rows <= 0 || n != cols * rows || n > (jsize)(sizeof cb / 2)) return JNI_FALSE;
   (*env)->GetShortArrayRegion(env, cells, 0, n, (jshort *)cb);
   return ov_cells(layer, cb, col0, row0, cols, rows, tileBase) ? JNI_TRUE : JNI_FALSE;
}

JNI(jboolean, nativeOvRecolor)(JNIEnv *env, jclass cls, jint layer, jint col0, jint row0, jint cols, jint rows, jint pal)
{ (void)env; (void)cls; return ov_recolor(layer, col0, row0, cols, rows, pal) ? JNI_TRUE : JNI_FALSE; }

JNI(jboolean, nativeOvPal)(JNIEnv *env, jclass cls, jint layer, jint pal, jint c1, jint c2, jint c3)
{ (void)env; (void)cls; return ov_pal(layer, pal, c1, c2, c3) ? JNI_TRUE : JNI_FALSE; }

JNI(jboolean, nativeOvQuad)(JNIEnv *env, jclass cls, jint layer, jint idx, jint x, jint y, jint tile, jint pal, jint flags)
{ (void)env; (void)cls; return ov_quad(layer, idx, x, y, tile, pal, flags) ? JNI_TRUE : JNI_FALSE; }

JNI(jboolean, nativeOvPane)(JNIEnv *env, jclass cls, jint layer, jint idx, jint c0, jint r0, jint c1, jint r1, jint dx, jint dy, jboolean visible)
{ (void)env; (void)cls; return ov_pane(layer, idx, c0, r0, c1, r1, dx, dy, visible ? 1 : 0) ? JNI_TRUE : JNI_FALSE; }

JNI(jboolean, nativeOvScroll)(JNIEnv *env, jclass cls, jint layer, jint x, jint y, jint clipY0, jint clipY1)
{ (void)env; (void)cls; return ov_scroll(layer, x, y, clipY0, clipY1) ? JNI_TRUE : JNI_FALSE; }

JNI(jboolean, nativeOvBase)(JNIEnv *env, jclass cls, jint slot, jint mode, jint y0, jint y1, jint arg, jint color)
{ (void)env; (void)cls; return ov_base(slot, mode, y0, y1, arg, color) ? JNI_TRUE : JNI_FALSE; }

JNI(jboolean, nativeOvCue)(JNIEnv *env, jclass cls, jintArray ops, jint flags)
{
   (void)cls;
   static int32_t ob[OV_MAX_OPS * OV_OP_INTS];
   if (!ops) return ov_cue(NULL, 0, flags) ? JNI_TRUE : JNI_FALSE;
   jsize n = (*env)->GetArrayLength(env, ops);
   if (n % OV_OP_INTS || n > (jsize)(sizeof ob / sizeof ob[0])) return JNI_FALSE;
   (*env)->GetIntArrayRegion(env, ops, 0, n, (jint *)ob);
   return ov_cue(ob, n / OV_OP_INTS, flags) ? JNI_TRUE : JNI_FALSE;
}

JNI(void, nativeOvHold)(JNIEnv *env, jclass cls, jboolean on) { (void)env; (void)cls; ov_hold_set(on ? 1 : 0); }
JNI(void, nativeOvBlock)(JNIEnv *env, jclass cls, jint bits) { (void)env; (void)cls; ov_block_set(bits); }
JNI(void, nativeOvClear)(JNIEnv *env, jclass cls, jint mask) { (void)env; (void)cls; ov_clear(mask); }

JNI(jintArray, nativeOvStatus)(JNIEnv *env, jclass cls)
{
   (void)cls;
   int v[OV_NSTAT]; jint jv[OV_NSTAT];
   ov_status(v);
   for (int i = 0; i < OV_NSTAT; i++) jv[i] = v[i];
   jintArray a = (*env)->NewIntArray(env, OV_NSTAT);
   if (a) (*env)->SetIntArrayRegion(env, a, 0, OV_NSTAT, jv);
   return a;
}
