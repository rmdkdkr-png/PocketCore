/* 157 덧판(ovplane.c) — 게임 화면 위 «가상 오버레이 판». 리그 인게임 메뉴(결판·쉼·서랍)를 그린다.
 *
 * 코어 램·VRAM 은 한 바이트도 안 건드린다. Java(TileCanvas)가 NGPC 규칙대로 만든 타일·맵·팔레트를 받아
 * native.c gl_draw 가 잔상(ghost) 뒤에 게임 그림 위로 합성한다. nativeFrameBuffer(g_fb)는 늘 날것 그대로.
 * 계약서: docs/157_contract.md (이 헤더가 바뀌면 계약서도 같이).
 *
 * 규칙(NGPC 와 같음)
 *  - 타일 8×8, 2bpp: 줄마다 16비트 리틀 엔디언(바이트 2개), 왼쪽 픽셀이 높은 비트 — (pix(x) & 3) << (14 - 2x). RankVs.encode 와 같다.
 *  - 맵 칸 u16: bit0-8 타일(올린 묶음의 tile_base 기준 상대 번호 0..510) · bit9-12 팔레트 0..15 · bit13 0 · bit14 V 뒤집기 · bit15 H 뒤집기.
 *    0xFFFF = 빈 칸(투명, 우리 그림 없음). 한 묶음은 타일 511개까지(상대 511 + 팔레트 15 + HV = 0xFFFF 와 겹치지 않게).
 *  - 팔레트 16개 × 4색, 12비트 0x0BGR(R = 하위 니블). 색 0 은 늘 투명(팔레트 칸 0 은 무시).
 *  - 색 펼침은 코어와 같다: 니블×17 → RGB565(r>>3, g>>2, b>>3) → native.c 의 565→8888((r5<<3)|(r5>>2) …). ov_rgb() 참고.
 *
 * 층
 *  - W(창, OV_W): 타일 512 · 맵 32×32 칸. 화면은 칸 0..19 × 0..18(스크롤 0 일 때). 나머지 칸은 화면 밖 «창고»(COUNT 프레임 등).
 *  - L(목록, OV_L): 타일 2048 · 맵 20×128 칸 · 스크롤 y · 자르기 줄(clip). 묶음마다 tile_base 를 달리 올려 2048 을 다 쓴다.
 *  - 그리는 순서: 바탕(게임 그림 + 바탕 처리) → L 맵 → L 채움·쿼드 → W 맵 → W 채움·쿼드 → 일섬(FLASH) 칼선 → 흔들림.
 *
 * 스레드: ov_status() 만 아무 스레드. 나머지 ov_* 는 GL 스레드(LeagueMenu)에서만 — 그래도 안에서는 ov 자물쇠를 잡는다.
 *         ov_hold()·ov_in_block() 은 잠금 없이 int 하나(pace·입력 콜백).
 * 시험: 하네스(build_nh.sh — nh_stubs 의 가짜 JNIEnv 는 배열을 모름)는 JNI 대신 아래 평범한 C 함수를 ctypes 로 부른다.
 */
#pragma once
#include <stddef.h>
#include <stdint.h>

#define OV_VERSION 1            /* ov_status()[OVS_VER] — 0 = 덧판 없음(빈 껍데기) → Java 는 옛 결과 카드로 (HUD 이름표 ov_tag* 는 nativeOvTag* 가 있으면 — Java 가 UnsatisfiedLinkError 로 가림) */

/* ── 크기 ── */
#define OV_GAME_W 160
#define OV_GAME_H 152
#define OV_W 0                  /* 창 층 */
#define OV_L 1                  /* 목록 층 */
#define OV_BASE_LAYER 2         /* ROWSHIFT 의 대상 «바탕»(게임 그림 줄 밀기) */
#define OV_NLAYERS 2
#define OV_W_TILES 512
#define OV_W_COLS 32
#define OV_W_ROWS 32
#define OV_L_TILES 2048
#define OV_L_COLS 20
#define OV_L_ROWS 128
#define OV_PALS 16
#define OV_QUADS 16             /* 층마다 */
#define OV_PANES 16             /* 층마다 — 칸 사각형 묶음(밀기·보이기) */
#define OV_FILLS 8              /* 층마다 — 채움 사각형(GAUGE) */
#define OV_BASE_SLOTS 4
#define OV_MAX_OPS 64
#define OV_OP_INTS 7            /* {op, t0, dur, a, b, c, d} */
#define OV_CHUNK_MAX 511        /* ov_layer 한 번에 올리는 타일 수 상한 */

/* ── 맵 칸 ── */
#define OV_EMPTY   0xFFFF
#define OV_M_TILE  0x01FF
#define OV_M_PAL_SHIFT 9
#define OV_M_PAL   0x1E00
#define OV_M_VFLIP 0x4000
#define OV_M_HFLIP 0x8000

/* ── 쿼드(스프라이트 같은 8×8 / 16×16) 깃발 ── */
#define OV_Q_SHOW  1
#define OV_Q_HFLIP 2
#define OV_Q_VFLIP 4
#define OV_Q_16    8            /* 16×16 = 타일 t(왼위) t+1(오른위) t+2(왼아래) t+3(오른아래) — 뒤집기는 넷 묶음째 */

/* ── 바탕 처리(게임 줄 y0..y1, 게임 좌표 0..151 — 칸 순서대로 0→3 적용) ── */
#define OV_BASE_NONE   0
#define OV_BASE_DIM    1        /* arg = k 0..4: 채널마다 니블 n=(c+8)/17 → (n*k)>>2 → 565 규칙 */
#define OV_BASE_FILL   2        /* color 로 줄 y0 .. min(y1, y0+arg-1) 칠함(arg ≥ 줄 수 = 전부) — «먹 채움»·위 띠 닫힘 */
#define OV_BASE_FILLUP 3        /* color 로 줄 max(y0, y1-arg+1) .. y1 칠함 — 아래 띠 닫힘 */
#define OV_BASE_FLASH  4        /* arg = 0..16: 흰색 섞기 c + ((255-c)*arg)/16 */
#define OV_BASE_SHIFT  5        /* arg = dx px(±): 줄을 가로로 밀기(빈 곳 검정) */

/* ── 대본 시작 깃발(nativeOvCue flags) — bit 16..31 = 대본 id(상태에 그대로 돌려줌) ── */
#define OV_ARM_ON_LATCH 1       /* 걸어 둠: league_post_hold() 가 1 인 첫 vsync(ov_tick)에 t=0 으로 시작하고 보이게. 이미 1 이면 다음 ov_tick 에 */
#define OV_START_NOW    2       /* 다음 ov_tick(같은 vsync — GL 스레드에서 nativeFrame 앞에 부르면 그 vsync)에 t=0 으로 시작하고 보이게 */
#define OV_SKIP_TO_END  4       /* ops == NULL: 돌던 대본을 끝 상태로(유한 op 만 — 늘 도는 op 는 계속). ops 있음: 새 대본을 끝 상태에서 시작 */
#define OV_CUE_ID_SHIFT 16

/* ── 대본 op — int[7] {op, t0, dur, a, b, c, d}. t·t0·dur = 60Hz 프레임(시계 기준 — 120Hz 화면도 같은 박자).
 *    dur < 0 = 늘 도는 op(끝 없음 · SKIP_TO_END 무시 · 새 대본이 오면 버림). 계약서 §4 에 op 마다 정확한 뜻.
 *    a 의 공통 칸: bit0-3 층(OV_W/OV_L/OV_BASE_LAYER) · bit4-7 번호(창유리·칸·팔레트·쿼드) · bit8-9 감속(OV_EASE_*) · bit10~ op 마다 ── */
#define OV_OP_DIM      1        /* 바탕 칸 DIM k: a=칸<<4 · b=y0|y1<<8 · c=k from · d=k to */
#define OV_OP_SLIDE    2        /* 창유리 밀기: a=층|창유리<<4|감속<<8|축<<10(0 x·1 y) · b=from px · c=to px */
#define OV_OP_CLIP     3        /* 층 자르기 줄: a=층|가장자리<<4(0 위 y0·1 아래 y1)|감속<<8 · b=from · c=to */
#define OV_OP_STAMP    4        /* 낙관 쾅(잠깐): a=층|팔레트<<4 · b=번쩍 프레임 수 · c=흔들 px · d=번쩍 색 12비트 · dur=흔들 프레임 수 */
#define OV_OP_COUNT    5        /* 칸 프레임(숫자 오름 등): a=층|감속<<8|프레임 수<<16 · b=dst c|r<<8|w<<16|h<<24 · c=src(프레임 0) c|r<<8 · d=프레임마다 dc|dr<<8(부호 있는 8비트) */
#define OV_OP_GAUGE    6        /* 채움 사각형 폭: a=층|채움 칸<<4|감속<<8|색<<10(1..3)|팔레트<<12 · b=x|y<<12|h<<24(층 px) · c=폭 from · d=폭 to */
#define OV_OP_PALCYCLE 7        /* 팔레트 색 일렁임(잠깐): a=층|팔레트<<4|색 칸<<10(1..3)|방식<<12(0 부드럽게·1 깜빡) · b=색 A · c=색 B · d=주기 프레임 */
#define OV_OP_WIPE     8        /* 사선 드러냄: a=층|방향<<4(0 왼→오·1 오→왼)|감속<<8|테 색<<10(0 없음)|테 팔레트<<12 · b=c0|r0<<8|c1<<16|r1<<24(칸, 포함) · c=기울기(줄마다 px 0..4) */
#define OV_OP_SCROLLY  9        /* 층 스크롤 y: a=층|감속<<8 · b=from · c=to */
#define OV_OP_ROWSHIFT 10       /* 줄 x 어긋남: a=층(0·1·2=바탕)|감속<<8|모양<<10(0 그대로·1 셔터·2 물결) · b=y0|y1<<8(화면 줄) · c=폭 from · d=폭 to */
#define OV_OP_FLASH    11       /* 일섬(잠깐, 12프레임 고정 안무): b=줄 y · c=칼선 가운데 색 · d=칼선 테 색 */
#define OV_OP_SHOW     12       /* 창유리 보이기: a=층|창유리<<4 · b=1 보임·0 숨김(t0 부터) */
#define OV_OP_PAL      13       /* 팔레트 바꿈(t0 부터 그대로): a=층|팔레트<<4 · b,c,d = 색 1,2,3(−1 = 그대로) */
#define OV_OP_BASE     14       /* 바탕 칸 일반: a=칸<<4|감속<<8|모드<<10(OV_BASE_*)|색<<16(12비트) · b=y0|y1<<8 · c=arg from · d=arg to */
#define OV_OP_BOB      15       /* 쿼드 까딱(늘 도는 것 권장): a=층|쿼드<<4|축<<10 · b=폭 px · c=주기 프레임 */
#define OV_OP_LAST     15

#define OV_EASE_LINEAR 0
#define OV_EASE_OUT    1
#define OV_EASE_IN     2
#define OV_EASE_STEP   3

/* ── 지우기(nativeOvClear mask) ── */
#define OV_CLR_W     1          /* W 타일·맵·팔레트·창유리·채움·쿼드·스크롤·자르기 */
#define OV_CLR_L     2
#define OV_CLR_BASE  4          /* 바탕 칸 4개 */
#define OV_CLR_CUE   8          /* 대본(돌던 것·걸어 둔 것) — 끝 상태로 굽지 않고 버림 */
#define OV_CLR_HOLD  16         /* ov_hold · 막기 비트 */
#define OV_CLR_ALL   (-1)       /* 위 전부 + 숨김(보이기 끝) */

/* ── 상태 ov_status(v[OV_NSTAT]) ── */
#define OVS_VER        0        /* OV_VERSION(빈 껍데기 0) */
#define OVS_FLAGS      1        /* OVF_* */
#define OVS_T          2        /* 대본 프레임 t(없으면 −1) */
#define OVS_END        3        /* 대본 끝 프레임 = 유한 op 의 max(t0+dur)(없으면 −1) */
#define OVS_CUE_ID     4        /* 돌거나 걸어 둔 대본 id(flags>>16), 없으면 0 */
#define OVS_STARTS     5        /* 대본이 시작된 횟수(라이브러리 올림부터 늘기만 — 차이로 볼 것) — 걸쇠 시작도 센다 */
#define OVS_RX         6        /* 게임 사각형 왼위(g_fb 픽셀) */
#define OVS_RY         7
#define OVS_SUB        8        /* 1 · 4(서기 그리기 4배) */
#define OVS_FBW        9
#define OVS_FBH        10
#define OVS_VX         11       /* 게임 160×152 자리 — GL 뷰 픽셀, 왼위 원점(마지막 gl_draw). 모르면 0,0,0,0 */
#define OVS_VY         12
#define OVS_VW         13
#define OVS_VH         14
#define OVS_GPAUSE     15       /* 게임 자체 일시정지(사무쇼2 램 0x61D4 == 1) 1·0 · 모르면 −1 */
#define OVS_LATCH      16       /* league_post_hold() */
#define OVS_COMPOSITES 17       /* 합성한 횟수 */
#define OVS_W1         18       /* 지금 라운드 승수 P1(램 0x5821) · 모르면 −1 */
#define OVS_W2         19       /* P2(0x5822) */
#define OV_NSTAT       20

#define OVF_VISIBLE   1         /* 보이는 중(합성) */
#define OVF_ARMED     2         /* 걸쇠를 기다리는 중 */
#define OVF_HOLD      4         /* ov_hold */
#define OVF_RUNNING   8         /* 유한 op 가 아직 끝나지 않음 */
#define OVF_RECT_OK   16        /* 지금 프레임 크기에서 게임 사각형을 찾음 */
#define OVF_SHOWN     32        /* 보이기 시작한 뒤 한 번 이상 합성함 */

/* ── 올리기(GL 스레드) — 1 = 받음, 0 = 인자 틀림(아무것도 안 씀) ──
   setter 는 모두 먼저 «끝난 유한 op» 를 바탕에 굽고 대본에서 뺀다(계약서 §3.2). native 쪽 안전 규칙 둘(계약 밖 — 장면 갈아 끼우기를 덜 깨지게):
    · 맵 쓰기(ov_layer 의 map · ov_cells)가 덮는 칸을 «이미 시작한» COUNT(dst)·GAUGE(왼위 칸)가 쥐고 있으면 그 op 를 굽지 않고 뺀다.
      왼위 칸이 그 사각형 안인 바탕 채움(구운 GAUGE 막대)도 지운다. 아직 시작 안 한 op(걸어 둔 대본·다음 tick 에 시작할 대본)는 그대로.
    · ov_clear 의 OV_CLR_W / OV_CLR_L / OV_CLR_BASE 는 그 대상을 쥔 op 도 굽지 않고 뺀다.
   장면을 통째로 갈 때 옛 대본이 아직 돌면(창유리 SLIDE 등) 새 대본이 와서 끝 상태로 굽는다(§4.3) — 먼저 nativeOvCue(null, SKIP_TO_END) 를 부르면 확실하다 */
int  ov_layer(int layer, const uint8_t *tiles, int ntiles, int tile_base,
              const uint16_t *map, int cols, int rows, int col0, int row0,
              const uint16_t *pals, int npals, int pal_base);
int  ov_cells(int layer, const uint16_t *cells, int col0, int row0, int cols, int rows, int tile_base);
int  ov_recolor(int layer, int col0, int row0, int cols, int rows, int pal);    /* 빈 칸 아닌 칸의 팔레트만 */
int  ov_pal(int layer, int pal, int c1, int c2, int c3);                         /* −1 = 그대로 */
int  ov_quad(int layer, int idx, int x, int y, int tile, int pal, int flags);   /* tile = 층 타일 은행 절대 번호 · x,y = 층 px */
int  ov_pane(int layer, int idx, int c0, int r0, int c1, int r1, int dx, int dy, int visible);   /* c1<c0 = 지움 */
int  ov_scroll(int layer, int x, int y, int clip_y0, int clip_y1);               /* clip = 화면 줄(포함) */
int  ov_base(int slot, int mode, int y0, int y1, int arg, int color);
/* nops = op 수(정수 수 / 7) · ops 는 t0 오름차순(Cue.build — 아니면 0) · ARM_ON_LATCH 와 START_NOW 중 꼭 하나 ·
   ops NULL + SKIP_TO_END = 돌던 대본 건너뛰기. 새 대본은 먼저 옛 대본의 유한 op 를 모두 끝 상태로 바탕에 굽고(건너뛴 것처럼) 늘 도는 op 는 버린다(계약서 §4.3) */
int  ov_cue(const int32_t *ops, int nops, int flags);
void ov_hold_set(int on);
void ov_block_set(int bits);
void ov_clear(int mask);
void ov_status(int v[OV_NSTAT]);                                                  /* 아무 스레드 */

/* ── native.c 연결 ── */
int  ov_hold(void);                  /* pace_steps 멈춤 조건에 || — 잠금 없이 */
int  ov_in_block(void);              /* 입력 콜백 blk 에 | — 막기 비트 | (보임·멈춤이면 0xFFF) · 잠금 없이 */
void ov_tick(double now_s, int latch);   /* nativeFrame: 스텝 루프 뒤 · gl_draw 앞(스텝 0 이어도). 걸기·시작·t 계산(t = floor((now−start)·60 + 1e−6)).
                                            지난 tick 과 0.25초 넘게 벌어지면(앱이 내려가 있었음) 그 틈만큼 시작 시각을 미뤄 t 는 멈춘 데서 이어진다 */
int  ov_active(void);                /* 보이는 중 — gl_draw 가 합성할지 */
/* 게임 사각형(160×152) 찾기 — gl_draw 의 sub 판정과 같은 규칙. 1 = 찾음 */
int  ov_find_rect(unsigned fbw, unsigned fbh, int *x0, int *y0, int *sub);
/* src(RGBA8888, w×h — 잔상 뒤 그림)에 덧판을 합성한 버퍼(같은 크기, ovplane 소유)를 돌려준다.
   src_changed = src 내용이 지난번과 다를 수 있음(g_fb 새 프레임·잔상 켬) — 0 이고 덧판도 그대로면 다시 합성하지 않는다.
   *changed = 지난번에 돌려준 것과 픽셀이 다르면 1(올려야 함 — 다른 줄 범위는 ov_compose_span). NULL = 합성 안 함(사각형 실패 등 — 그때는 src 를 그대로 그린다) */
const uint8_t *ov_compose(const uint8_t *src, unsigned w, unsigned h, int src_changed, int *changed);
/* 마지막 ov_compose 가 바꾼 그림 줄 범위(포함 · 그림 픽셀 줄) — gl_draw 는 이 줄만 다시 올린다(glTexSubImage2D yoffset).
   바뀐 줄을 줄마다 견주므로 쉬는 메뉴(일렁임·까딱)는 몇 줄만, 같은 그림이면 *changed 0. 바뀐 것이 없으면 *y1 < *y0 */
void ov_compose_span(int *y0, int *y1);
/* gl_draw 가 정한 뷰포트(glViewport 값, 아래 원점)·뷰 높이·그림 크기 — 상태의 OVS_VX.. 계산 */
void ov_viewport(int vp_x, int vp_y, int vp_w, int vp_h, int view_h, unsigned fbw, unsigned fbh);
/* 상태용 게임 램(시스템 램 16KB, CPU 0x4000~) — nativeFrame 이 포인터만(NULL = 모름) */
void ov_game_ram(const uint8_t *ram, size_t n);

/* ── 157 post: 경기 중 HUD 이름표(체력 막대 아래 검은 줄 — 사무쇼2 HUD 게임 줄 24..31) ──
   메뉴 층(W·L)·대본과 따로인 작은 칸 둘(0 = P1 나 · 1 = P2 상대). 입력을 막지 않고(ov_in_block 에 안 들어감) OVF_VISIBLE 도 아니다.
   native 가 vsync 마다 램(ov_game_ram)으로 «라운드 조작 중»(0x40A7==241 · 0x411A==1 · 0x5824==0 · 0x5823==0)인지 보고, 아니면 숨김(KO·대사·걸쇠).
   새 게임 그림마다(ov_tag_scan — 날 그림 g_fb) 이름표 자리 아래를 살펴 게임이 무엇이든 그렸으면(히트 수·MAX·글…) 그 이름표를 숨겼다가
   OV_TAG_CLEAR_F 프레임(60Hz 시계) 동안 비어 있고 지금 화면의 그림(마지막 살핌)도 비었으면 다시 보인다(«비켜 주기» — 쉼으로 멈춘 그림에 남은 히트 수는 안 덮음). 화면 전체 번쩍(흰·검정 한 프레임 · 둘째 라운드 첫 붉은 프레임)은 그 프레임만 숨김.
   라운드 첫 조작 프레임부터 OV_TAG_EMPH_F 강조(강조 팔레트 + 표지 쪽에서 8프레임 펼침) 뒤 OV_TAG_FADE_F 에 걸쳐 보통 팔레트로.
   그리는 자리: 메뉴가 안 보이면 gl_draw 가 그려 낼 게임 텍스처의 그 줄만 덧칠(ov_tag_band — 4배 그림이라도 32줄),
   메뉴가 보이면 ov_compose 가 게임 그림 위·바탕 처리 앞에 넣는다(쉼 DIM 은 32줄부터라 그대로 보이고, 서랍 먹 채움·결판 띠는 덮는다).
   ov_clear 는 건드리지 않는다(메뉴 닫기가 이름표를 지우지 않게) — 코어 상태가 통째로 바뀌는 다섯 곳에서 native.c 가 ov_tag_clear. */
#define OV_TAG_SIDES   2
#define OV_TAG_MAXW    64
#define OV_TAG_H       8
#define OV_TAG_CLEAR_F 20       /* 비켜 준 뒤 이만큼 비어 있어야 다시 보임 */
#define OV_TAG_EMPH_F  120      /* 라운드 시작 강조 */
#define OV_TAG_FADE_F  15       /* 강조 → 보통 */
#define OV_TAG_REVEAL_F 8       /* 표지 쪽에서 펼침 */
#define OV_TAG_NSTAT   10
#define OVT_ON     0            /* ov_tag_status: 켬 */
#define OVT_LIVE   1            /* 라운드 조작 중 */
#define OVT_VIS    2            /* 보이는 칸 비트(1 P1 · 2 P2) */
#define OVT_FLASH  3            /* 마지막 살핌이 화면 전체 번쩍 */
#define OVT_OCC    4            /* 마지막 살핌에서 게임이 그린 칸 비트 */
#define OVT_SCANS  5            /* 살핀 게임 그림 수 */
#define OVT_YIELDS 6            /* 비켜 준(보이다 숨은) 횟수 */
#define OVT_EMPH   7            /* 강조 중 1 */
#define OVT_SIG    8            /* 보이는 그림 서명(0 = 안 보임) */
#define OVT_ROUNDS 9            /* 라운드 시작(조작 가능) 본 횟수 */
int  ov_tag(int side, const uint8_t *pix, int w, int h, int x, int y, const uint16_t *pals6);   /* pix w×h 칠 0..3(0 투명) · pals6 = 강조 c1..c3 · 보통 c1..c3 · pix NULL = 그 칸 지움 */
void ov_tag_on(int on);
void ov_tag_clear(void);
void ov_tag_status(int v[OV_TAG_NSTAT]);                                        /* 아무 스레드 */
void ov_tag_scan(const uint8_t *raw, unsigned w, unsigned h);                    /* gl_draw: 새 게임 그림(날 그림)마다 */
uint32_t ov_tag_sig(void);                                                       /* 지금 보이는 이름표 그림 서명 — 0 = 안 보임 */
/* 이름표 줄(켠 칸들의 줄 범위 × sub)만 src 에서 베껴 보이는 이름표를 그린 버퍼(ovplane 소유) — *y0 그림 줄 · *rows 줄 수.
   켠 칸이 없으면 지난 줄 범위의 날 그림(텍스처에 남은 이름표 되돌림) · 한 번도 켠 적 없거나 사각형 실패면 NULL */
const uint8_t *ov_tag_band(const uint8_t *src, unsigned w, unsigned h, int *y0, int *rows);

/* ── 시험·Java 와 같은 셈(계약서 §4.2) ── */
int      ov_ease(int ease, int t, int t0, int dur, int from, int to);
uint32_t ov_rgb(int c12);            /* 12비트 → 0xAABBGGRR(메모리 순서 R,G,B,A) — 코어 565 규칙 */
