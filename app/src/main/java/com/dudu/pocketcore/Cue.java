package com.dudu.pocketcore;

/**
 * 157 연출 대본 짓기 — native 대본 실행기(ovplane.c)가 도는 op 목록 int[7×n] {op, t0, dur, a, b, c, d} 를 만든다.
 * 계약서 docs/157_contract.md §4 · ovplane.h OV_OP_* 와 칸 배치가 같아야 한다(여기 인코더와 ease 는 계약 — 바꾸면 native·계약서도 같이).
 * 담당: 그림 담당(render builder).
 *
 * 시간 = 60Hz 프레임(대본 시작 = 0). dur < 0 = 늘 도는 op(끝 없음). 쓰는 쪽(LgmPages)은 t0 순서 상관없이 더하고 build() 가 t0 순으로(같으면 넣은 순서) 정렬한다.
 * 세 층으로 쓴다:
 *  1) op 인코더(dim·slide·…·bob) — 계약.
 *  2) 전환 이름표(기획서 §8): 먹 번짐 inkBleed · 낙관 쾅 sealSlam · 칼집 들어옴/나감 sheathIn/sheathOut · 칼 휘두름 swordSwing ·
 *     족자 펼침/말림 scrollUnroll/scrollRollUp · 칼집 닫힘 closeSheath · 일섬 issen · 호명 띠 ribbon · 승급 문장 promoCrest · 팔레트 흐름 palFlow · 단도 까딱 cursorBob.
 *  3) 장면 대본(Plan): result(결판 «보통/짧게» — 일섬·호명 띠·승급 문장·★ 문턱·진 판 꼬리) · pause · pauseClose · confirmOpen · swap(결판↔그만할까요?) ·
 *     drawerOpen · drawerTab · drawerClose — 창유리·팔레트 번호는 장면(LgmPages)이 Spec 에 적는다(−1 = 그 op 안 넣음).
 * stateAt()/bake() = 계약서 §4.3 평가기(native 와 같은 규칙 — 시험·벡터용, 앱에서는 안 씀).
 */
final class Cue {
    /* op — ovplane.h 와 같은 번호 */
    static final int OP_DIM = 1, OP_SLIDE = 2, OP_CLIP = 3, OP_STAMP = 4, OP_COUNT = 5, OP_GAUGE = 6, OP_PALCYCLE = 7, OP_WIPE = 8,
            OP_SCROLLY = 9, OP_ROWSHIFT = 10, OP_FLASH = 11, OP_SHOW = 12, OP_PAL = 13, OP_BASE = 14, OP_BOB = 15;
    static final int EASE_LINEAR = 0, EASE_OUT = 1, EASE_IN = 2, EASE_STEP = 3;
    static final int W = Emu.OV_W, L = Emu.OV_L, BASE = Emu.OV_BASE_LAYER;
    static final int AXIS_X = 0, AXIS_Y = 1;
    static final int EDGE_TOP = 0, EDGE_BOTTOM = 1;
    static final int SHAPE_CONST = 0, SHAPE_SHUTTER = 1, SHAPE_WAVE = 2;
    static final int AMBIENT = -1;          /* dur — 끝 없음 */
    static final int FLASH_DUR = 12;        /* 일섬 안무 길이(native 고정) */
    static final int MAX_OPS = 64, INTS = 7;

    private int[] v = new int[INTS * 8];
    private int n;                           /* op 수 */
    private int seq;                         /* 넣은 순서(정렬 안정) — build 에서 씀 */
    private int[] order = new int[8];

    private Cue add(int op, int t0, int dur, int a, int b, int c, int d) {
        if (n >= MAX_OPS) throw new IllegalStateException("op 64개 넘음");
        if (v.length < (n + 1) * INTS) { v = java.util.Arrays.copyOf(v, v.length * 2); order = java.util.Arrays.copyOf(order, order.length * 2); }
        int i = n * INTS;
        v[i] = op; v[i + 1] = t0; v[i + 2] = dur; v[i + 3] = a; v[i + 4] = b; v[i + 5] = c; v[i + 6] = d;
        order[n] = seq++;
        n++;
        return this;
    }
    private static int lines(int y0, int y1) { return (y0 & 0xFF) | ((y1 & 0xFF) << 8); }
    private static int cellRect(int c0, int r0, int c1, int r1) { return (c0 & 0xFF) | ((r0 & 0xFF) << 8) | ((c1 & 0xFF) << 16) | ((r1 & 0xFF) << 24); }

    /* ── op 인코더(계약) ── */
    /** 바탕 칸 slot 을 DIM 으로 — 게임 줄 y0..y1, k(니블 k/4) kFrom → kTo */
    Cue dim(int t0, int dur, int slot, int y0, int y1, int kFrom, int kTo) {
        return add(OP_DIM, t0, dur, (slot & 15) << 4, lines(y0, y1), kFrom, kTo);
    }
    /** 창유리 밀기 — axis AXIS_X/AXIS_Y, from → to px */
    Cue slide(int t0, int dur, int layer, int pane, int axis, int from, int to, int ease) {
        return add(OP_SLIDE, t0, dur, (layer & 15) | ((pane & 15) << 4) | ((ease & 3) << 8) | ((axis & 1) << 10), from, to, 0);
    }
    /** 층 자르기 줄 — edge EDGE_TOP(clip y0)/EDGE_BOTTOM(clip y1), 화면 줄 from → to (족자 펼침) */
    Cue clip(int t0, int dur, int layer, int edge, int from, int to, int ease) {
        return add(OP_CLIP, t0, dur, (layer & 15) | ((edge & 15) << 4) | ((ease & 3) << 8), from, to, 0);
    }
    /** 낙관 쾅 — 팔레트 pal 을 flashColor 로 flashFrames 동안 + 화면 세로 흔들림 ±shakePx 를 shakeDur 동안 */
    Cue stamp(int t0, int shakeDur, int layer, int pal, int flashFrames, int shakePx, int flashColor) {
        return add(OP_STAMP, t0, shakeDur, (layer & 15) | ((pal & 15) << 4), flashFrames, shakePx, flashColor & 0xFFF);
    }
    /** 칸 프레임 — dst 사각형(dstC,dstR,w,h)을 프레임 k(0..nframes−1)의 칸(srcC + k·stepC, srcR + k·stepR …)으로. 시작 전 = 프레임 0 · 끝 = 마지막 */
    Cue count(int t0, int dur, int layer, int nframes, int dstC, int dstR, int w, int h, int srcC, int srcR, int stepC, int stepR, int ease) {
        return add(OP_COUNT, t0, dur, (layer & 15) | ((ease & 3) << 8) | ((nframes & 0xFF) << 16),
                (dstC & 0xFF) | ((dstR & 0xFF) << 8) | ((w & 0xFF) << 16) | ((h & 0xFF) << 24),
                (srcC & 0xFF) | ((srcR & 0xFF) << 8), (stepC & 0xFF) | ((stepR & 0xFF) << 8));
    }
    /** 채움 사각형(막대) — 층 px (x,y) 높이 h, 폭 wFrom → wTo, 칠 = 팔레트 pal 의 색 color(1..3). slot 0..7 */
    Cue gauge(int t0, int dur, int layer, int slot, int pal, int color, int x, int y, int h, int wFrom, int wTo, int ease) {
        return add(OP_GAUGE, t0, dur, (layer & 15) | ((slot & 15) << 4) | ((ease & 3) << 8) | ((color & 3) << 10) | ((pal & 15) << 12),
                (x & 0xFFF) | ((y & 0xFFF) << 12) | ((h & 0xFF) << 24), wFrom, wTo);
    }
    /** 팔레트 pal 의 색 idx(1..3) 를 A↔B — toggle false = 니블 삼각 섞기(주기 period) · true = 반 주기마다 깜빡 */
    Cue palCycle(int t0, int dur, int layer, int pal, int idx, int colorA, int colorB, int period, boolean toggle) {
        return add(OP_PALCYCLE, t0, dur, (layer & 15) | ((pal & 15) << 4) | ((idx & 3) << 10) | ((toggle ? 1 : 0) << 12), colorA & 0xFFF, colorB & 0xFFF, period);
    }
    /** 사선 드러냄 — 칸 사각형(포함)을 기울기 slope 로 쓸며 보이게 · edgeColor 1..3(0 = 테 없음) */
    Cue wipe(int t0, int dur, int layer, int c0, int r0, int c1, int r1, int slope, boolean rightToLeft, int edgePal, int edgeColor, int ease) {
        return add(OP_WIPE, t0, dur, (layer & 15) | ((rightToLeft ? 1 : 0) << 4) | ((ease & 3) << 8) | ((edgeColor & 3) << 10) | ((edgePal & 15) << 12),
                cellRect(c0, r0, c1, r1), slope, 0);
    }
    /** 층 스크롤 y */
    Cue scrollY(int t0, int dur, int layer, int from, int to, int ease) {
        return add(OP_SCROLLY, t0, dur, (layer & 15) | ((ease & 3) << 8), from, to, 0);
    }
    /** 줄 x 어긋남 — layer W/L/BASE · 화면 줄 y0..y1 · shape SHAPE_* · 폭 ampFrom → ampTo */
    Cue rowShift(int t0, int dur, int layer, int y0, int y1, int shape, int ampFrom, int ampTo, int ease) {
        return add(OP_ROWSHIFT, t0, dur, (layer & 15) | ((ease & 3) << 8) | ((shape & 3) << 10), lines(y0, y1), ampFrom, ampTo);
    }
    /** 일섬 — 줄 y 에 칼선(가운데 coreColor · 테 edgeColor), 12프레임 고정 안무(계약서 §4.4) */
    Cue flash(int t0, int y, int coreColor, int edgeColor) {
        return add(OP_FLASH, t0, FLASH_DUR, 0, y, coreColor & 0xFFF, edgeColor & 0xFFF);
    }
    /** 창유리 보이기 — t0 부터 on(시작 전 = 반대) */
    Cue show(int t0, int layer, int pane, boolean on) {
        return add(OP_SHOW, t0, 0, (layer & 15) | ((pane & 15) << 4), on ? 1 : 0, 0, 0);
    }
    /** 팔레트 바꿈 — t0 부터 그대로(−1 = 그 색 그대로) */
    Cue pal(int t0, int layer, int pal, int c1, int c2, int c3) {
        return add(OP_PAL, t0, 0, (layer & 15) | ((pal & 15) << 4), c1, c2, c3);
    }
    /** 바탕 칸 일반 — mode Emu.OV_BASE_*, 게임 줄 y0..y1, arg from → to, color(FILL 12비트) */
    Cue base(int t0, int dur, int slot, int mode, int y0, int y1, int argFrom, int argTo, int color, int ease) {
        return add(OP_BASE, t0, dur, ((slot & 15) << 4) | ((ease & 3) << 8) | ((mode & 15) << 10) | ((color & 0xFFF) << 16), lines(y0, y1), argFrom, argTo);
    }
    /** 쿼드 까딱 — 반 주기마다 0 ↔ amp px(늘 도는 것이 보통: dur AMBIENT) */
    Cue bob(int t0, int dur, int layer, int quad, int axis, int amp, int period) {
        return add(OP_BOB, t0, dur, (layer & 15) | ((quad & 15) << 4) | ((axis & 1) << 10), amp, period, 0);
    }

    /** other 의 op 를 dt 만큼 늦춰 붙임(넣은 순서 그대로) */
    Cue append(Cue other, int dt) {
        int[] o = other.sortedIndex();
        for (int k = 0; k < o.length; k++) {
            int i = o[k] * INTS;
            add(other.v[i], other.v[i + 1] + dt, other.v[i + 2], other.v[i + 3], other.v[i + 4], other.v[i + 5], other.v[i + 6]);
        }
        return this;
    }
    /** 넣은 순서(seq) 로 줄 세운 op 번호 */
    private int[] sortedIndex() {
        Integer[] ix = new Integer[n];
        for (int k = 0; k < n; k++) ix[k] = k;
        java.util.Arrays.sort(ix, (x, y) -> Integer.compare(order[x], order[y]));
        int[] r = new int[n];
        for (int k = 0; k < n; k++) r[k] = ix[k];
        return r;
    }
    /** op 수 */
    int size() { return n; }
    /** 유한 op 의 max(t0+dur) — 없으면 0 */
    int end() {
        int e = 0;
        for (int k = 0; k < n; k++) { int i = k * INTS; if (v[i + 2] >= 0) e = Math.max(e, v[i + 1] + v[i + 2]); }
        return e;
    }
    /** 검사(op 번호·범위·dur·≤64) 후 t0 순(같으면 넣은 순)으로 정렬한 int[7n] — 틀리면 IllegalArgumentException */
    int[] build() {
        if (n > MAX_OPS) throw new IllegalArgumentException("op " + n + " > " + MAX_OPS);
        Integer[] ix = new Integer[n];
        for (int k = 0; k < n; k++) { ix[k] = k; check(v, k * INTS); }
        java.util.Arrays.sort(ix, (x, y) -> v[x * INTS + 1] != v[y * INTS + 1] ? Integer.compare(v[x * INTS + 1], v[y * INTS + 1]) : Integer.compare(order[x], order[y]));
        int[] out = new int[n * INTS];
        for (int k = 0; k < n; k++) System.arraycopy(v, ix[k] * INTS, out, k * INTS, INTS);
        return out;
    }
    /** 이미 지은 대본(int[7n])의 끝 = 유한 op 의 max(t0+dur), 없으면 0 */
    static int end(int[] ops) {
        int e = 0;
        if (ops != null) for (int i = 0; i + INTS <= ops.length; i += INTS) if (ops[i + 2] >= 0) e = Math.max(e, ops[i + 1] + ops[i + 2]);
        return e;
    }

    /* 층·칸 한도(ovplane.h) */
    static final int GAME_W = 160, GAME_H = 152, PANES = 16, FILLS = 8, QUADS = 16, PALS = 16, BASE_SLOTS = 4;
    static final int[] MAP_COLS = { 32, 20 }, MAP_ROWS = { 32, 128 };
    private static boolean longLived(int op) {
        switch (op) {
            case OP_STAMP: case OP_PALCYCLE: case OP_FLASH: case OP_BOB: return false;
            default: return true;
        }
    }
    private static void need(boolean c, int[] o, int i, String why) {
        if (!c) throw new IllegalArgumentException("op " + (i / INTS) + " " + java.util.Arrays.toString(java.util.Arrays.copyOfRange(o, i, i + INTS)) + ": " + why);
    }
    private static boolean c12(int c) { return c >= 0 && c <= 0xFFF; }
    private static boolean line(int y) { return y >= 0 && y < GAME_H; }
    /** op 하나 검사 — native ov_cue 가 거절할 것을 Java 에서 먼저(계약서 §4.1·§4.4) */
    static void check(int[] o, int i) {
        int op = o[i], t0 = o[i + 1], dur = o[i + 2], a = o[i + 3], b = o[i + 4], c = o[i + 5], d = o[i + 6];
        int layer = a & 15, idx = (a >> 4) & 15;
        need(op >= 1 && op <= OP_BOB, o, i, "op 번호");
        need(t0 >= 0, o, i, "t0 < 0");
        need(dur >= 0 || !longLived(op) || op == OP_ROWSHIFT, o, i, "오래 남는 op 는 dur ≥ 0");
        boolean wl = layer == W || layer == L;
        switch (op) {
            case OP_DIM:
                need(layer == 0 && idx < BASE_SLOTS && (a >> 8) == 0, o, i, "칸");
                need(line(b & 0xFF) && line((b >> 8) & 0xFF) && (b & 0xFF) <= ((b >> 8) & 0xFF) && (b >>> 16) == 0, o, i, "줄 y0..y1");
                need(c >= 0 && c <= 4 && d >= 0 && d <= 4, o, i, "k 0..4");
                break;
            case OP_SLIDE:
                need(wl && (a >> 11) == 0, o, i, "층·축");
                break;
            case OP_CLIP:
                need(wl && idx <= 1 && (a >> 10) == 0, o, i, "층·가장자리");
                need(b >= 0 && b < GAME_H && c >= 0 && c < GAME_H, o, i, "줄 0..151");
                break;
            case OP_STAMP:
                need(wl && (a >> 8) == 0, o, i, "층·팔레트");
                need(b >= 0 && c >= 0 && c12(d), o, i, "번쩍·흔들·색");
                need(dur < 0 || b <= dur, o, i, "번쩍 프레임 > dur");
                break;
            case OP_COUNT: {
                int nf = (a >> 16) & 0xFF, dc = b & 0xFF, dr = (b >> 8) & 0xFF, w = (b >> 16) & 0xFF, h = (b >>> 24);
                int sc = c & 0xFF, sr = (c >> 8) & 0xFF, stc = (byte) (d & 0xFF), str = (byte) ((d >> 8) & 0xFF);
                need(wl && ((a >> 10) & 0x3F) == 0 && (a >>> 24) == 0 && idx == 0, o, i, "층");
                need(nf >= 1 && w >= 1 && h >= 1, o, i, "프레임 수·크기");
                need(dc + w <= MAP_COLS[layer] && dr + h <= MAP_ROWS[layer], o, i, "dst 칸이 맵 밖");
                for (int k = 0; k < nf; k++) {
                    int x = sc + k * stc, y = sr + k * str;
                    need(x >= 0 && y >= 0 && x + w <= MAP_COLS[layer] && y + h <= MAP_ROWS[layer], o, i, "프레임 " + k + " src 칸이 맵 밖");
                }
                break;
            }
            case OP_GAUGE: {
                int col = (a >> 10) & 3;
                need(wl && idx < FILLS && col >= 1 && (a >>> 16) == 0, o, i, "층·채움 칸·색");
                need(((b >>> 24) & 0xFF) >= 1, o, i, "높이");
                break;
            }
            case OP_PALCYCLE:
                need(wl && ((a >> 10) & 3) >= 1 && (a >> 13) == 0, o, i, "층·팔레트·색 칸");
                need(c12(b) && c12(c) && d >= 1, o, i, "색·주기");
                break;
            case OP_WIPE: {
                int c0 = b & 0xFF, r0 = (b >> 8) & 0xFF, c1 = (b >> 16) & 0xFF, r1 = b >>> 24;
                need(wl && idx <= 1 && (a >> 16) == 0, o, i, "층·방향");
                need(c0 <= c1 && r0 <= r1 && c1 < MAP_COLS[layer] && r1 < MAP_ROWS[layer], o, i, "칸 사각형");
                need(c >= 0 && c <= 4, o, i, "기울기 0..4");
                break;
            }
            case OP_SCROLLY:
                need(wl && idx == 0 && (a >> 10) == 0, o, i, "층");
                break;
            case OP_ROWSHIFT:
                need(layer <= BASE && idx == 0 && ((a >> 10) & 3) <= SHAPE_WAVE && (a >> 12) == 0, o, i, "층·모양");
                need(line(b & 0xFF) && line((b >> 8) & 0xFF) && (b & 0xFF) <= ((b >> 8) & 0xFF) && (b >>> 16) == 0, o, i, "줄 y0..y1");
                break;
            case OP_FLASH:
                need(a == 0 && dur == FLASH_DUR && line(b) && c12(c) && c12(d), o, i, "일섬(a 0 · dur 12 · 줄 · 색)");
                break;
            case OP_SHOW:
                need(wl && (a >> 8) == 0 && (b == 0 || b == 1), o, i, "층·창유리·b");
                break;
            case OP_PAL:
                need(wl && (a >> 8) == 0, o, i, "층·팔레트");
                need((b == -1 || c12(b)) && (c == -1 || c12(c)) && (d == -1 || c12(d)), o, i, "색(−1 또는 12비트)");
                break;
            case OP_BASE: {
                int mode = (a >> 10) & 15;
                need((a & 15) == 0 && idx < BASE_SLOTS && mode <= Emu.OV_BASE_SHIFT, o, i, "칸·모드");
                need(line(b & 0xFF) && line((b >> 8) & 0xFF) && (b & 0xFF) <= ((b >> 8) & 0xFF) && (b >>> 16) == 0, o, i, "줄 y0..y1");
                if (mode == Emu.OV_BASE_DIM) need(c >= 0 && c <= 4 && d >= 0 && d <= 4, o, i, "DIM k 0..4");
                if (mode == Emu.OV_BASE_FLASH) need(c >= 0 && c <= 16 && d >= 0 && d <= 16, o, i, "FLASH 0..16");
                if (mode == Emu.OV_BASE_FILL || mode == Emu.OV_BASE_FILLUP) need(c >= 0 && d >= 0, o, i, "FILL 줄 수 ≥ 0");
                break;
            }
            case OP_BOB:
                need(wl && (a >> 11) == 0 && ((a >> 8) & 3) == 0, o, i, "층·쿼드·축");
                break;
            default:
                break;
        }
    }

    /* ── 박자(60Hz 프레임) — 기획서 §5·§8 · 계약서 §10.4 ── */
    static final int FX_NORMAL = 0, FX_SHORT = 1;      // MenuModel.Prefs.FX_NORMAL/FX_SHORT 와 같은 값(끄기 = 덧판 없음)
    static final int ISSEN = FLASH_DUR;                // 일섬(사건 판 «보통») — 표 전체를 이만큼 늦춤
    static final int ISSEN_Y = 84;
    static final int BLEED = 4, NAME_SLIDE = 6, SEAL_FLASH = 2, SEAL_SHAKE = 4, SHAKE_PX = 2;
    static final int SHEATH_IN = 10, SHEATH_IN_SHORT = 8, SHEATH_OUT = 6, CLOSE = 6;
    static final int LP_COUNT = 20, LP_COUNT_SHORT = 10, STAR_FLASH = 12, STAR_FLASH_PERIOD = 4, TAIL_HOLD = 20, TAIL_SHRINK = 6;
    static final int TAG_GAP = 4, RIBBON_IN = 4, RIBBON_HOLD = 24, PROMO_HOLD = 36;
    static final int SWING = 6, TAB_SWING = 10, UNROLL = 10, ROLLUP = 8, CONFIRM = 6;
    static final int PAUSE_BLEED = 4, PAUSE_INFO = 8;
    static final int SEL_PERIOD = 12, BOB_PERIOD = 32, CROWN_PERIOD = 8;
    static final int INK = 0x012;                      // 먹(칼집 닫힘 띠·서랍 바탕)

    /* ── 전환 이름표(기획서 §8) — 모두 this 를 돌려줌(이어 쓰기) ── */
    /** 먹 번짐 — 바탕 칸 slot 을 DIM k 4 → k 로(dur 프레임) */
    Cue inkBleed(int t0, int dur, int slot, int y0, int y1, int kFrom, int kTo) { return dim(t0, dur, slot, y0, y1, kFrom, kTo); }
    /** 낙관 쾅 — 창유리 pane 을 t0 에 보이고, 팔레트 pal 을 flashColor 로 2f + 화면 세로 흔들 ±px 4f */
    Cue sealSlam(int t0, int layer, int pane, int pal, int flashColor, int shakePx) {
        if (pane >= 0) show(t0, layer, pane, true);
        if (pal >= 0) stamp(t0, SEAL_SHAKE, layer, pal, SEAL_FLASH, shakePx, flashColor);
        return this;
    }
    /** 칼집 들어옴 — 창유리를 t0 에 보이고 x from → 0 (감속 OUT) */
    Cue sheathIn(int t0, int dur, int layer, int pane, int fromPx) {
        show(t0, layer, pane, true);
        return slide(t0, dur, layer, pane, AXIS_X, fromPx, 0, EASE_OUT);
    }
    /** 칼집 나감 — x 0 → toPx (가속 IN) */
    Cue sheathOut(int t0, int dur, int layer, int pane, int toPx) { return slide(t0, dur, layer, pane, AXIS_X, 0, toPx, EASE_IN); }
    /** 칼 휘두름 — 칸 사각형을 사선(기울기 2)으로 왼→오 드러냄, 테 = edgePal 의 색 edgeColor(0 = 테 없음) */
    Cue swordSwing(int t0, int dur, int layer, int c0, int r0, int c1, int r1, int edgePal, int edgeColor) {
        return wipe(t0, dur, layer, c0, r0, c1, r1, 2, false, Math.max(0, edgePal), edgeColor, EASE_LINEAR);
    }
    /** 족자 펼침 — 층의 아래 자르기 줄 from → to (위에서 아래로 드러남). L 처럼 위 자르기가 y0 이면 from = y0 − 1 로 시작하면 처음엔 안 보임 */
    Cue scrollUnroll(int t0, int dur, int layer, int from, int to) { return clip(t0, dur, layer, EDGE_BOTTOM, from, to, EASE_LINEAR); }
    /** 족자 말림 — 아래 자르기 줄 from → to(위로 말려 올라감) */
    Cue scrollRollUp(int t0, int dur, int layer, int from, int to) { return clip(t0, dur, layer, EDGE_BOTTOM, from, to, EASE_LINEAR); }
    /** 칼집 닫힘(다음 상대·재대결) — 칼집 0 → 80 + 위아래 먹 띠가 가운데로(FILL 위 32..83 · FILLUP 아래 84..135) · 그 밖 창유리 숨김 */
    Cue closeSheath(int t0, int paneSheath, int slotTop, int slotBot, int color, int... hidePanes) {
        if (paneSheath >= 0) sheathOut(t0, CLOSE, W, paneSheath, 80);
        if (slotTop >= 0) base(t0, CLOSE, slotTop, Emu.OV_BASE_FILL, 32, 83, 0, 52, color, EASE_IN);
        if (slotBot >= 0) base(t0, CLOSE, slotBot, Emu.OV_BASE_FILLUP, 84, 135, 0, 52, color, EASE_IN);
        for (int p : hidePanes) if (p >= 0) show(t0, W, p, false);
        return this;
    }
    /** 일섬 — 줄 y 칼선(흰 · 금 테) 12f */
    Cue issen(int t0, int y) { return flash(t0, y, 0xFFF, 0x7DF); }
    /** 호명 띠 — t0 에 보이고 오른쪽(+fromPx)에서 4f 만에(감속) 들어옴 · hideAt ≥ 0 이면 그때 숨김(다음 띠로 바뀜) */
    Cue ribbon(int t0, int pane, int fromPx, int hideAt) {
        show(t0, W, pane, true);
        slide(t0, RIBBON_IN, W, pane, AXIS_X, fromPx, 0, EASE_OUT);
        if (hideAt >= 0) show(hideAt, W, pane, false);
        return this;
    }
    /** 승급 문장 — t0 에 보이고 쾅(팔레트 흰 2f + 흔들 2px 4f), hold 프레임 뒤 숨김(끝 장면엔 띠의 «승급! …» 만 남음) */
    Cue promoCrest(int t0, int pane, int pal, int hold) {
        sealSlam(t0, W, pane, pal, 0xFFF, SHAKE_PX);
        if (pane >= 0 && hold > 0) show(t0 + hold, W, pane, false);
        return this;
    }
    /** 팔레트 흐름 — 팔레트 pal 의 색 idx 를 A↔B 부드럽게(늘 도는 것, 타일 0개) */
    Cue palFlow(int t0, int layer, int pal, int idx, int colorA, int colorB, int period) {
        return palCycle(t0, AMBIENT, layer, pal, idx, colorA, colorB, period, false);
    }
    /** 단도 까딱 — 쿼드를 16f 마다 1px(늘 도는 것) */
    Cue cursorBob(int t0, int layer, int quad) { return bob(t0, AMBIENT, layer, quad, AXIS_X, 1, BOB_PERIOD); }
    /** 고른 줄 일렁임 + 단도 까딱 — 새 대본마다 다시 넣어야 함(native 는 새 대본이 오면 늘 도는 op 를 버림) */
    Cue ambient(int t0, int palSel, int selIdx, int selA, int selB, int quadCursor) {
        if (palSel >= 0) palFlow(t0, W, palSel, selIdx, selA, selB, SEL_PERIOD);
        if (quadCursor >= 0) cursorBob(t0, W, quadCursor);
        return this;
    }

    /* ── 장면 대본 ── */
    /** 지은 대본 + 고르기를 받기 시작하는 프레임(그 전 A = 연출만 건너뜀) */
    static final class Plan {
        final Cue cue; final int readyT;
        Plan(Cue cue, int readyT) { this.cue = cue; this.readyT = readyT; }
        int[] build() { return cue.build(); }
        int end() { return cue.end(); }
    }
    /** 고른 줄 일렁임·단도(장면 공통) */
    static class Ambient {
        int palSel = -1, selIdx = 2, selA = 0x7DF, selB = 0x5BE;   // SEL 칠 2(금 단도 7DF ↔ 금 5BE) · 주기 12
        int quadCursor = -1;                                      // 단도 쿼드(W)
    }
    /** 결판 대본 재료 — 창유리·팔레트·칸 번호는 장면이 정함(−1 = 그 op 안 넣음). 기본값 = 계약서 §10.4 권장 박자·자리.
     *  장면 규칙(미리보기에서 확인): ① 대본이 번쩍·회색으로 바꾸는 팔레트(palSeal·gaugePal/gaugeFlashPal·loserPals·palCrest)는 그것만 쓰는 칸 —
     *  같은 색의 다른 글자와 나누면 같이 번쩍인다(TileCanvas.pal 은 같은 색을 한 칸으로 합치니 setPal 로 따로). ② 어떤 판에서만 나오는 창유리
     *  (호명 띠 둘·승급 문장)는 바탕 상태에서 숨김(SHOW 가 드러냄) — 대본이 안 건드리면 바탕이 끝 상태다. */
    static final class ResultSpec extends Ambient {
        int fx = FX_NORMAL;              // FX_NORMAL «보통» · FX_SHORT «짧게»
        boolean eventful;                // 일섬(★ 올라감·승급·무쌍 달성·설욕) — «보통»에서만
        int ribbons;                     // 호명 띠 수 0..2 — «보통»에서만
        boolean promo;                   // 승급 문장(CREST32 쾅) — «보통»에서만
        boolean starUp;                  // 막대가 ★ 문턱을 넘음: 끝까지 참 → (보통) 금↔흰 3번 번쩍 → 새 칸에서 0 → to
        boolean loss;                    // 진 판: 잃은 몫 붉은 꼬리를 20f 남겼다가 줄임(tail*)
        int dimSlot = 0, dimY0 = 32, dimY1 = 135, dimK = 3;
        int paneNameL = -1, paneNameR = -1, nameFrom = 48;          // 띠 이름(왼 c0–7 r0–3 · 오른 c12–19 r0–3)
        int paneSeal = -1, palSeal = -1, sealFlash = 0xFFF, shakePx = SHAKE_PX;
        int[] loserPals = {};            // 진 쪽 글자 팔레트(띠) — 낙관 쾅 때 회색으로
        int[][] loserBright = null;      // 그 팔레트의 처음 색 {c1,c2,c3}(null = 바탕 팔레트 그대로 두고 회색만)
        int[] loserDim = { 0x888, 0x444, 0x000 };                   // B_DIM
        int paneSheath = -1, sheathFrom = 72;
        int[] paneBottom = {};           // 아래 띠(r17–18, 도장 빼고 — 사각형 여럿) — 칼집과 같이 보임(목업 스토리보드 ④)
        int countFrames = 0, countDstC, countDstR, countW, countH, countSrcC, countSrcR, countStepC, countStepR;   // LP 숫자(창고 칸 프레임)
        int gaugeSlot = -1, gaugePal, gaugeColor = 2, gaugeX, gaugeY, gaugeH = 4, gaugeFrom, gaugeTo, gaugeFull;  // 막대(층 px)
        int gaugeHiSlot = -1, gaugeHiColor = 1;                     // 막대 윗빛 줄(목업 gauge 의 찬 몫 첫 줄 = 칠 1) — 같은 폭으로 1px 높이
        int gaugeFlashPal = -1, gaugeFlashIdx = 2, gaugeFlashA = 0x5BE, gaugeFlashB = 0xFFF;                   // ★ 문턱 번쩍
        int tailSlot = -1, tailPal, tailColor = 1;                  // 진 판 꼬리 — 막대 x+gaugeTo 에서 폭 gaugeFrom−gaugeTo → 0
        int[] paneTags = {};             // 아래 띠 도장(왼→오)
        int paneRibbon1 = -1, paneRibbon2 = -1, ribbonFrom = 80;
        int paneCrest = -1, palCrest = -1;
    }
    /** 결판 — «보통» 평범한 판 ≈30f(readyT 16) · 진 판 36f · 사건 판 일섬 +12 · ★ ≈54–60f · 승급 ≈82f / «짧게» ≈16–18f(readyT 10) */
    static Plan result(ResultSpec s) {
        Cue q = new Cue();
        boolean nm = s.fx != FX_SHORT;
        int e = nm && s.eventful ? ISSEN : 0;
        if (e > 0) q.issen(0, ISSEN_Y);
        if (s.dimSlot >= 0) q.inkBleed(e, nm ? BLEED : 2, s.dimSlot, s.dimY0, s.dimY1, 4, s.dimK);
        if (nm) {
            if (s.paneNameL >= 0) { q.show(e + 2, W, s.paneNameL, true); q.slide(e + 2, NAME_SLIDE, W, s.paneNameL, AXIS_X, -s.nameFrom, 0, EASE_LINEAR); }
            if (s.paneNameR >= 0) { q.show(e + 2, W, s.paneNameR, true); q.slide(e + 2, NAME_SLIDE, W, s.paneNameR, AXIS_X, s.nameFrom, 0, EASE_LINEAR); }
        }
        int tS = nm ? e + 4 : 0;
        q.sealSlam(tS, W, s.paneSeal, s.palSeal, s.sealFlash, s.shakePx);
        for (int k = 0; k < s.loserPals.length; k++) {
            int p = s.loserPals[k];
            if (p < 0) continue;
            if (s.loserBright != null && k < s.loserBright.length && s.loserBright[k] != null && tS > 0)
                q.pal(0, W, p, s.loserBright[k][0], s.loserBright[k][1], s.loserBright[k][2]);
            q.pal(tS, W, p, s.loserDim[0], s.loserDim[1], s.loserDim[2]);
        }
        int tK = nm ? e + 6 : 2, dK = nm ? SHEATH_IN : SHEATH_IN_SHORT;
        if (s.paneSheath >= 0) q.sheathIn(tK, dK, W, s.paneSheath, s.sheathFrom);
        for (int p : s.paneBottom) if (p >= 0) q.show(tK, W, p, true);
        int ready = tK + dK;
        // LP: 숫자(COUNT) · 막대(GAUGE) · ★ 문턱 · 진 판 꼬리
        int tL = nm ? e + 10 : 6, dL = nm ? LP_COUNT : LP_COUNT_SHORT, lpEnd = tL + dL;
        if (s.gaugeSlot >= 0) {
            if (s.starUp) {
                int d1 = dL / 2, d2 = dL - d1, fl = nm && s.gaugeFlashPal >= 0 ? STAR_FLASH : 0;
                q.lpGauge(s, tL, d1, s.gaugeFrom, s.gaugeFull, true);
                if (fl > 0) q.palCycle(tL + d1, fl, W, s.gaugeFlashPal, s.gaugeFlashIdx, s.gaugeFlashA, s.gaugeFlashB, STAR_FLASH_PERIOD, true);
                q.lpGauge(s, tL + d1 + fl, d2, 0, s.gaugeTo, true);
                lpEnd = tL + d1 + fl + d2;
            } else if (s.loss) {
                q.lpGauge(s, tL, 0, s.gaugeTo, s.gaugeTo, false);
                if (s.tailSlot >= 0 && s.gaugeFrom > s.gaugeTo) {
                    int tt = tL + (nm ? TAIL_HOLD : 6), dt = nm ? TAIL_SHRINK : 4;
                    q.gauge(tt, dt, W, s.tailSlot, s.tailPal, s.tailColor, s.gaugeX + s.gaugeTo, s.gaugeY, s.gaugeH, s.gaugeFrom - s.gaugeTo, 0, EASE_IN);
                    lpEnd = Math.max(lpEnd, tt + dt);
                }
            } else {
                q.lpGauge(s, tL, dL, s.gaugeFrom, s.gaugeTo, true);
            }
        }
        if (s.countFrames > 0)
            q.count(tL, (s.starUp ? lpEnd : tL + dL) - tL, W, s.countFrames, s.countDstC, s.countDstR, s.countW, s.countH, s.countSrcC, s.countSrcR, s.countStepC, s.countStepR, EASE_LINEAR);
        // 아래 띠 도장 — 보통 4f 간격 · 짧게 한꺼번에
        int tT = nm ? e + 18 : 10;
        for (int k = 0; k < s.paneTags.length; k++) if (s.paneTags[k] >= 0) q.show(tT + (nm ? k * TAG_GAP : 0), W, s.paneTags[k], true);
        // 호명 띠 · 승급 문장 — «보통»에서만
        if (nm) {
            int tR = tK, tR2 = tR + RIBBON_IN + RIBBON_HOLD;
            boolean two = s.ribbons >= 2 && s.paneRibbon2 >= 0;
            if (s.ribbons >= 1 && s.paneRibbon1 >= 0) q.ribbon(tR, s.paneRibbon1, s.ribbonFrom, two ? tR2 : -1);
            if (two) q.ribbon(tR2, s.paneRibbon2, s.ribbonFrom, -1);
            if (s.promo && s.paneCrest >= 0) q.promoCrest(tR2, s.paneCrest, s.palCrest, PROMO_HOLD);
        }
        q.ambient(ready, s.palSel, s.selIdx, s.selA, s.selB, s.quadCursor);
        return new Plan(q, ready);
    }
    /** 막대 한 몫(본 채움 + 윗빛 줄) — 진 판은 윗빛 없음(목업 gauge tail_to: 찬 몫 첫 줄도 금) */
    private Cue lpGauge(ResultSpec s, int t0, int dur, int wFrom, int wTo, boolean hi) {
        gauge(t0, dur, W, s.gaugeSlot, s.gaugePal, s.gaugeColor, s.gaugeX, s.gaugeY, s.gaugeH, wFrom, wTo, EASE_LINEAR);
        if (hi && s.gaugeHiSlot >= 0) gauge(t0, dur, W, s.gaugeHiSlot, s.gaugePal, s.gaugeHiColor, s.gaugeX, s.gaugeY, 1, wFrom, wTo, EASE_LINEAR);
        return this;
    }
    /** 쉼 재료 */
    static final class PauseSpec extends Ambient {
        int dimSlot = 0, dimY0 = 32, dimY1 = 151, dimK = 2;          // HUD(0–31) 안 가림 · 무대 니블 2/4
        int paneSheath = -1, sheathFrom = 72;
        int paneInfo = -1, infoFrom = -80;                           // 왼쪽 위 판 정보 패
    }
    /** 쉼 — 먹 번짐 4→2 4f · 칼집 72→0 10f · 판 정보 패 −80→0 8f · readyT 10 */
    static Plan pause(PauseSpec s) {
        Cue q = new Cue();
        if (s.dimSlot >= 0) q.inkBleed(0, PAUSE_BLEED, s.dimSlot, s.dimY0, s.dimY1, 4, s.dimK);
        if (s.paneSheath >= 0) q.sheathIn(0, SHEATH_IN, W, s.paneSheath, s.sheathFrom);
        if (s.paneInfo >= 0) { q.show(0, W, s.paneInfo, true); q.slide(0, PAUSE_INFO, W, s.paneInfo, AXIS_X, s.infoFrom, 0, EASE_OUT); }
        q.ambient(SHEATH_IN, s.palSel, s.selIdx, s.selA, s.selB, s.quadCursor);
        return new Plan(q, SHEATH_IN);
    }
    /** 쉼 닫기(이어하기) 6f — 칼집 0→80 · 판 정보 패 0→−80 · 바탕 k→4. 끝나면 LeagueMenu 가 close */
    static Cue pauseClose(PauseSpec s) {
        Cue q = new Cue();
        if (s.paneSheath >= 0) q.sheathOut(0, SHEATH_OUT, W, s.paneSheath, 80);
        if (s.paneInfo >= 0) q.slide(0, SHEATH_OUT, W, s.paneInfo, AXIS_X, 0, s.infoFrom, EASE_IN);
        if (s.dimSlot >= 0) q.dim(0, SHEATH_OUT, s.dimSlot, s.dimY0, s.dimY1, s.dimK, 4);
        return q;
    }
    /** 기권 확인 펼침 — 확인 칸(c0..c1 × r0..r1)을 칼 휘두름 6f 로 · 기권 줄 팔레트 맥박(주홍↔흰 깜빡, 늘) */
    static Plan confirmOpen(Ambient a, int c0, int r0, int c1, int r1, int edgePal, int warnPal, int warnIdx) {
        Cue q = new Cue();
        q.swordSwing(0, CONFIRM, W, c0, r0, c1, r1, edgePal, edgePal >= 0 ? 2 : 0);
        if (warnPal >= 0) q.palCycle(0, AMBIENT, W, warnPal, warnIdx, 0x46E, 0xFFF, 16, true);
        q.ambient(CONFIRM, a.palSel, a.selIdx, a.selA, a.selB, a.quadCursor);
        return new Plan(q, CONFIRM);
    }
    /** 결판 ↔ 그만할까요? — 새 장면을 올린 뒤 칼집 칸(c10–19 × r4–16)만 칼 휘두름 6f(띠·아래 띠는 같은 칸이라 안 움직여 보임) */
    static Plan swap(Ambient a, int c0, int r0, int c1, int r1, int edgePal, int edgeColor) {
        Cue q = new Cue();
        q.swordSwing(0, SWING, W, c0, r0, c1, r1, edgePal, edgeColor);
        q.ambient(SWING, a.palSel, a.selIdx, a.selA, a.selB, a.quadCursor);
        return new Plan(q, SWING);
    }
    /** 서랍 재료 */
    static final class DrawerSpec extends Ambient {
        int fillSlot = 1, fillColor = INK;           // 바탕 «먹 채움»(결판의 DIM 칸 0 은 그대로 — 닫으면 그 위로 돌아감)
        int lClipY0 = 56, lClipY1 = 127;             // L 층 목록 자리(장면의 자르기와 같게)
        boolean hasL = true;
        int palCrown = -1, crownIdx = 1, crownA = 0xFFF, crownB = 0x7DF;   // 1위 왕관 반짝임(늘)
        int lScrollY = 0;                            // L 층 스크롤(장면 lScrollY) — 탭 휘두름을 W 사선과 맞출 때
        int bodyR0 = 3, bodyR1 = 18;                 // 탭 바꿀 때 휘두를 칸 줄(머리·탭 줄 빼고)
        int edgePal = -1, edgeColor = 0;
    }
    /** 서랍 열기 «족자 펼침» 10f — 먹 채움이 위에서 내려오고(FILL 0→152) W 아래 자르기 0→151 · L 은 앞선 줄이 목록 자리에 닿을 때부터 */
    static Plan drawerOpen(DrawerSpec s) {
        Cue q = new Cue();
        if (s.fillSlot >= 0) q.base(0, UNROLL, s.fillSlot, Emu.OV_BASE_FILL, 0, GAME_H - 1, 0, GAME_H, s.fillColor, EASE_LINEAR);
        q.scrollUnroll(0, UNROLL, W, 0, GAME_H - 1);
        if (s.hasL) {
            int t0 = s.lClipY0 * UNROLL / GAME_H, t1 = ((s.lClipY1 + 1) * UNROLL + GAME_H - 1) / GAME_H;
            q.scrollUnroll(t0, Math.max(1, t1 - t0), L, s.lClipY0 - 1, s.lClipY1);
        }
        if (s.palCrown >= 0) q.palFlow(UNROLL, W, s.palCrown, s.crownIdx, s.crownA, s.crownB, CROWN_PERIOD);
        q.ambient(UNROLL, s.palSel, s.selIdx, s.selA, s.selB, s.quadCursor);
        return new Plan(q, UNROLL);
    }
    /** 서랍 탭 바꿈 «칼 휘두름» 10f — 새 쪽을 올린 뒤 몸통 칸(머리·탭 줄 빼고)을 W·L 같이 쓸며 드러냄 */
    static Plan drawerTab(DrawerSpec s) {
        Cue q = new Cue();
        q.swordSwing(0, TAB_SWING, W, 0, s.bodyR0, 19, s.bodyR1, s.edgePal, s.edgeColor);
        if (s.hasL) {   // L 은 보이는 목록 줄만 — 사선 앞이 W 와 같은 화면 자리를 지나게 시작·길이를 맞춤(같은 기울기 2)
            int rTop = Math.max(0, Math.floorDiv(s.lClipY0 + s.lScrollY, 8)), rBot = Math.min(MAP_ROWS[1] - 1, Math.floorDiv(s.lClipY1 + s.lScrollY, 8));
            if (rBot >= rTop) {
                int yTop = rTop * 8 - s.lScrollY;
                double speed = (GAME_W + (s.bodyR1 - s.bodyR0 + 1) * 16 + 3) / (double) TAB_SWING;
                int dt = Math.max(0, (int) Math.round((yTop - s.bodyR0 * 8) * 2 / speed));
                int dl = Math.max(1, (int) Math.round((GAME_W + (rBot - rTop + 1) * 16 + 3) / speed));
                q.swordSwing(dt, dl, L, 0, rTop, 19, rBot, -1, 0);
            }
        }
        if (s.palCrown >= 0) q.palFlow(0, W, s.palCrown, s.crownIdx, s.crownA, s.crownB, CROWN_PERIOD);
        q.ambient(0, s.palSel, s.selIdx, s.selA, s.selB, s.quadCursor);
        return new Plan(q, 0);
    }
    /** 서랍 닫기 «족자 말림» 8f — 끝나면 부른 장면을 START_NOW|SKIP_TO_END 로 다시 올림(LeagueMenu) */
    static Cue drawerClose(DrawerSpec s) {
        Cue q = new Cue();
        if (s.fillSlot >= 0) q.base(0, ROLLUP, s.fillSlot, Emu.OV_BASE_FILL, 0, GAME_H - 1, GAME_H, 0, s.fillColor, EASE_LINEAR);
        q.scrollRollUp(0, ROLLUP, W, GAME_H - 1, 0);
        if (s.hasL) {
            int t0 = (GAME_H - 1 - s.lClipY1) * ROLLUP / GAME_H, t1 = ((GAME_H - s.lClipY0) * ROLLUP + GAME_H - 1) / GAME_H;
            q.scrollRollUp(t0, Math.max(1, t1 - t0), L, s.lClipY1, s.lClipY0 - 1);
        }
        return q;
    }
    /** 다음 상대·재대결 — 칼집 닫힘 6f(끝나면 동작) · 그 밖 창유리(호명 띠·문장 등)는 숨김 */
    static Cue sheathClose(int paneSheath, int slotTop, int slotBot, int... hidePanes) {
        return new Cue().closeSheath(0, paneSheath, slotTop, slotBot, INK, hidePanes);
    }
    /** 감속 보간(계약 — ovplane.c ov_ease 와 같아야 함). t < t0 → from · t ≥ t0+dur 또는 dur ≤ 0 → to */
    static int ease(int e, int t, int t0, int dur, int from, int to) {
        if (dur <= 0 || t >= t0 + dur) return to;
        if (t <= t0) return from;
        long u = ((long) (t - t0) << 16) / dur;
        long k;
        switch (e) {
            case EASE_OUT: k = 65536 - (((65536 - u) * (65536 - u)) >> 16); break;
            case EASE_IN: k = (u * u) >> 16; break;
            case EASE_STEP: k = 0; break;
            default: k = u; break;
        }
        return from + (int) Math.floorDiv((long) (to - from) * k, 65536L);
    }
    /** 12비트 → 0xFFRRGGBB(안드로이드 색) — 코어 565 규칙(ovplane.c ov_rgb 와 같은 값) */
    static int argb(int c12) {
        int r8 = (c12 & 15) * 17, g8 = ((c12 >> 4) & 15) * 17, b8 = ((c12 >> 8) & 15) * 17;
        int r5 = r8 >> 3, g6 = g8 >> 2, b5 = b8 >> 3;
        int r = (r5 << 3) | (r5 >> 2), g = (g6 << 2) | (g6 >> 4), b = (b5 << 3) | (b5 >> 2);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }


    /* ── 평가기(계약서 §4.3) — native 대본 실행기와 같은 규칙으로 «유효 상태»를 셈. 시험·벡터용(앱 흐름에서는 안 부름) ── */
    /** 물결표(계약서 §4.2) */
    static final int[] SIN16 = { 0, 98, 181, 236, 256, 236, 181, 98, 0, -98, -181, -236, -256, -236, -181, -98 };
    /** 덧판 «유효 상태»(대본이 건드리는 것만). 바탕 상태(Java setter 가 정한 것)도 같은 모양 */
    static final class State {
        final int[] base = new int[BASE_SLOTS * 5];             // 칸 × {mode, y0, y1, arg, color}
        final int[] pane = new int[2 * PANES * 3];               // 층 × 창유리 × {dx, dy, vis}
        final int[] clip = { 0, GAME_H - 1, 0, GAME_H - 1 };     // 층 × {y0, y1}
        final int[] scrollY = new int[2];
        final int[] pal = new int[2 * PALS * 3];                 // 층 × 팔레트 × 색 1..3
        final int[] fill = new int[2 * FILLS * 6];               // 층 × 채움 칸 × {x, y, h, w, pal, color} — w ≤ 0 = 없음
        final int[] wipe = new int[2 * 10];                      // 층 × {on, c0, r0, c1, r1, slope, dir, front, edgePal, edgeColor}
        final int[] rowx = new int[3 * GAME_H];                  // W·L·바탕 × 화면 줄 x 어긋남
        final java.util.ArrayList<int[]> cells = new java.util.ArrayList<>();   // COUNT 결과 {층, dstC, dstR, w, h, srcC, srcR} — 적용 순서(뒤가 이김)
        int shakeDy;                                             // STAMP 세로 흔들림
        int flashF = -1, flashY, flashCore, flashEdge;           // FLASH 안무 프레임 0..11(없음 −1)
        final int[] bob = new int[2 * QUADS * 2];                // 층 × 쿼드 × {dx, dy}
        boolean running;                                         // 유한 op 남음
        int t;
        State() { for (int i = 0; i < 2 * PANES; i++) pane[i * 3 + 2] = 1; }
        State copy() {
            State s = new State();
            System.arraycopy(base, 0, s.base, 0, base.length); System.arraycopy(pane, 0, s.pane, 0, pane.length);
            System.arraycopy(clip, 0, s.clip, 0, clip.length); System.arraycopy(scrollY, 0, s.scrollY, 0, 2);
            System.arraycopy(pal, 0, s.pal, 0, pal.length); System.arraycopy(fill, 0, s.fill, 0, fill.length);
            System.arraycopy(wipe, 0, s.wipe, 0, wipe.length); System.arraycopy(rowx, 0, s.rowx, 0, rowx.length);
            for (int[] c : cells) s.cells.add(c.clone());
            s.shakeDy = shakeDy; s.flashF = flashF; s.flashY = flashY; s.flashCore = flashCore; s.flashEdge = flashEdge;
            System.arraycopy(bob, 0, s.bob, 0, bob.length); s.running = running; s.t = t;
            return s;
        }
        void clearTransient() { shakeDy = 0; flashF = -1; flashY = flashCore = flashEdge = 0; java.util.Arrays.fill(bob, 0); running = false; }
        /** 바탕 setter 흉내(시험용) */
        State setPal(int layer, int p, int c1, int c2, int c3) { pal[(layer * PALS + p) * 3] = c1; pal[(layer * PALS + p) * 3 + 1] = c2; pal[(layer * PALS + p) * 3 + 2] = c3; return this; }
        State setPane(int layer, int p, int dx, int dy, boolean vis) { int i = (layer * PANES + p) * 3; pane[i] = dx; pane[i + 1] = dy; pane[i + 2] = vis ? 1 : 0; return this; }
        State setBase(int slot, int mode, int y0, int y1, int arg, int color) { int i = slot * 5; base[i] = mode; base[i + 1] = y0; base[i + 2] = y1; base[i + 3] = arg; base[i + 4] = color; return this; }
        State setClip(int layer, int y0, int y1) { clip[layer * 2] = y0; clip[layer * 2 + 1] = y1; return this; }
        int paneDx(int layer, int p) { return pane[(layer * PANES + p) * 3]; }
        int paneDy(int layer, int p) { return pane[(layer * PANES + p) * 3 + 1]; }
        boolean paneVis(int layer, int p) { return pane[(layer * PANES + p) * 3 + 2] != 0; }
        int palColor(int layer, int p, int idx) { return pal[(layer * PALS + p) * 3 + idx - 1]; }
        int fillW(int layer, int slot) { return fill[(layer * FILLS + slot) * 6 + 3]; }
    }
    /** 대본 ops(build 결과)를 바탕 상태 base 에서 시각 t 로 — 계약서 §4.3: 앞 단계(뒤에서부터 시작 전 값) → 진행·끝(앞에서부터, 나중 시작이 이김) → 잠깐 op */
    static State stateAt(int[] ops, State base, int t) {
        State s = base.copy();
        s.clearTransient();
        s.t = t;
        int n = ops.length / INTS;
        for (int k = n - 1; k >= 0; k--) { int i = k * INTS; if (longLived(ops[i]) && t < ops[i + 1]) applyLong(s, ops, i, t, true); }
        for (int k = 0; k < n; k++) { int i = k * INTS; if (longLived(ops[i]) && t >= ops[i + 1]) applyLong(s, ops, i, t, false); }
        for (int k = 0; k < n; k++) { int i = k * INTS; if (!longLived(ops[i])) applyTransient(s, ops, i, t); }
        boolean fin = false;
        for (int k = 0; k < n; k++) if (ops[k * INTS + 2] >= 0) fin = true;
        s.running = fin && t < end(ops);
        return s;
    }
    /** 새 대본이 올 때 native 가 하는 일 — 유한 op 를 모두 끝 상태로 바탕에 굽고(op 순서, 나중이 이김) 잠깐·늘 도는 op 는 버림 */
    static State bake(int[] ops, State base) {
        State s = base.copy();
        for (int i = 0; i + INTS <= ops.length; i += INTS)
            if (ops[i + 2] >= 0 && longLived(ops[i])) applyLong(s, ops, i, ops[i + 1] + ops[i + 2], false);
        s.clearTransient();
        return s;
    }
    private static void applyLong(State s, int[] o, int i, int t, boolean pre) {
        int op = o[i], t0 = o[i + 1], dur = o[i + 2], a = o[i + 3], b = o[i + 4], c = o[i + 5], d = o[i + 6];
        int layer = a & 15, idx = (a >> 4) & 15, e = (a >> 8) & 3;
        boolean done = !pre && (dur <= 0 || t >= t0 + dur);
        switch (op) {
            case OP_DIM: {
                int k = pre ? c : ease(EASE_LINEAR, t, t0, dur, c, d), j = idx * 5;
                s.base[j] = Emu.OV_BASE_DIM; s.base[j + 1] = b & 0xFF; s.base[j + 2] = (b >> 8) & 0xFF; s.base[j + 3] = k; s.base[j + 4] = 0;
                break;
            }
            case OP_BASE: {
                int j = idx * 5;
                s.base[j] = (a >> 10) & 15; s.base[j + 1] = b & 0xFF; s.base[j + 2] = (b >> 8) & 0xFF;
                s.base[j + 3] = pre ? c : ease(e, t, t0, dur, c, d); s.base[j + 4] = (a >>> 16) & 0xFFF;
                break;
            }
            case OP_SLIDE: s.pane[(layer * PANES + idx) * 3 + ((a >> 10) & 1)] = pre ? b : ease(e, t, t0, dur, b, c); break;
            case OP_CLIP: s.clip[layer * 2 + (idx & 1)] = pre ? b : ease(e, t, t0, dur, b, c); break;
            case OP_SCROLLY: s.scrollY[layer] = pre ? b : ease(e, t, t0, dur, b, c); break;
            case OP_SHOW: s.pane[(layer * PANES + idx) * 3 + 2] = pre ? 1 - b : b; break;
            case OP_PAL: if (!pre) { int j = (layer * PALS + idx) * 3; if (b >= 0) s.pal[j] = b; if (c >= 0) s.pal[j + 1] = c; if (d >= 0) s.pal[j + 2] = d; } break;
            case OP_GAUGE: {
                int j = (layer * FILLS + idx) * 6;
                s.fill[j] = b & 0xFFF; s.fill[j + 1] = (b >> 12) & 0xFFF; s.fill[j + 2] = (b >>> 24) & 0xFF;
                s.fill[j + 3] = pre ? c : ease(e, t, t0, dur, c, d); s.fill[j + 4] = (a >> 12) & 15; s.fill[j + 5] = (a >> 10) & 3;
                break;
            }
            case OP_COUNT: {
                int nf = (a >> 16) & 0xFF, k;
                if (pre) k = 0;
                else if (done) k = nf - 1;
                else k = Math.min(nf - 1, (int) Math.floorDiv((long) ease(e, t, t0, dur, 0, 65536) * nf, 65536L));
                s.cells.add(new int[]{ layer, b & 0xFF, (b >> 8) & 0xFF, (b >> 16) & 0xFF, b >>> 24,
                        (c & 0xFF) + k * (byte) (d & 0xFF), ((c >> 8) & 0xFF) + k * (byte) ((d >> 8) & 0xFF) });
                break;
            }
            case OP_WIPE: {
                int j = layer * 10;
                if (done) { java.util.Arrays.fill(s.wipe, j, j + 10, 0); break; }
                int c0 = b & 0xFF, r0 = (b >> 8) & 0xFF, c1 = (b >> 16) & 0xFF, r1 = b >>> 24;
                int wpx = (c1 - c0 + 1) * 8, hpx = (r1 - r0 + 1) * 8;
                s.wipe[j] = 1; s.wipe[j + 1] = c0; s.wipe[j + 2] = r0; s.wipe[j + 3] = c1; s.wipe[j + 4] = r1; s.wipe[j + 5] = c; s.wipe[j + 6] = idx & 1;
                s.wipe[j + 7] = pre ? -1 : ease(e, t, t0, dur, -1, wpx + hpx * c + 2); s.wipe[j + 8] = (a >> 12) & 15; s.wipe[j + 9] = (a >> 10) & 3;
                break;
            }
            case OP_ROWSHIFT: {
                int y0 = b & 0xFF, y1 = (b >> 8) & 0xFF, shape = (a >> 10) & 3;
                int v = pre ? c : ease(e, t, t0, dur, c, d);
                for (int y = y0; y <= y1 && y < GAME_H; y++) s.rowx[layer * GAME_H + y] = rowShiftAt(shape, v, y - y0, t);
                break;
            }
            default: break;
        }
    }
    /** 줄 어긋남 모양 — 0 그대로 · 1 셔터 · 2 물결(SIN16) */
    static int rowShiftAt(int shape, int v, int dy, int t) {
        if (shape == SHAPE_SHUTTER) return ((dy >> 1) & 1) != 0 ? -v : v;
        if (shape == SHAPE_WAVE) return (int) Math.floorDiv((long) v * SIN16[(dy + t) & 15], 256L);
        return v;
    }
    private static void applyTransient(State s, int[] o, int i, int t) {
        int op = o[i], t0 = o[i + 1], dur = o[i + 2], a = o[i + 3], b = o[i + 4], c = o[i + 5], d = o[i + 6];
        int layer = a & 15, idx = (a >> 4) & 15, f = t - t0;
        if (f < 0) return;
        switch (op) {
            case OP_STAMP:
                if (f < b) { int j = (layer * PALS + idx) * 3; s.pal[j] = d; s.pal[j + 1] = d; s.pal[j + 2] = d; }
                if (dur < 0 || f < dur) s.shakeDy = (f & 1) != 0 ? -c : c;
                break;
            case OP_PALCYCLE:
                if (dur >= 0 && f >= dur) break;
                s.pal[(layer * PALS + idx) * 3 + ((a >> 10) & 3) - 1] = palCycleAt(((a >> 12) & 1) != 0, b, c, d, f);
                break;
            case OP_FLASH:
                if (f < FLASH_DUR) { s.flashF = f; s.flashY = b; s.flashCore = c; s.flashEdge = d; }
                break;
            case OP_BOB:
                if (dur >= 0 && f >= dur) break;
                s.bob[(layer * QUADS + idx) * 2 + ((a >> 10) & 1)] = ((f / Math.max(1, c / 2)) % 2) != 0 ? b : 0;
                break;
            default: break;
        }
    }
    /** PALCYCLE 색 — 부드럽게: 삼각 섞기(니블마다) · 깜빡: 반 주기마다 A/B (계약서 §4.4) */
    static int palCycleAt(boolean toggle, int A, int B, int P, int f) {
        if (toggle) return ((f / Math.max(1, P / 2)) % 2) == 0 ? A : B;
        int p = f % P, tri = p < P / 2 ? p : P - p, w = tri * 2 * 65536 / P, out = 0;
        for (int k = 0; k < 12; k += 4) {
            int na = (A >> k) & 15, nb = (B >> k) & 15;
            out |= (na + (int) Math.floorDiv((long) (nb - na) * w, 65536L)) << k;
        }
        return out;
    }
}
