package com.dudu.pocketcore;

/**
 * 157 연출 대본 짓기 — native 대본 실행기(ovplane.c)가 도는 op 목록 int[7×n] {op, t0, dur, a, b, c, d} 를 만든다.
 * 계약서 docs/157_contract.md §4 · ovplane.h OV_OP_* 와 칸 배치가 같아야 한다(여기 인코더와 ease 는 계약 — 바꾸면 native·계약서도 같이).
 * 담당: 그림 담당(render builder) — build() 검사·정렬, stateAt() 평가기(시험용), 시험 벡터는 TODO.
 *
 * 시간 = 60Hz 프레임(대본 시작 = 0). dur < 0 = 늘 도는 op(끝 없음). 쓰는 쪽(LgmPages)은 t0 순서 상관없이 더하고 build() 가 t0 순으로(같으면 넣은 순서) 정렬한다.
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

    /** other 의 op 를 dt 만큼 늦춰 붙임 */
    Cue append(Cue other, int dt) { throw todo(); }
    /** op 수 */
    int size() { return n; }
    /** 유한 op 의 max(t0+dur) — 없으면 0 */
    int end() { throw todo(); }
    /** 검사(op 번호·범위·dur·≤64) 후 t0 순(같으면 넣은 순)으로 정렬한 int[7n] — 틀리면 IllegalArgumentException */
    int[] build() { throw todo(); }

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

    private static UnsupportedOperationException todo() { return new UnsupportedOperationException("157-A Cue: 그림 담당이 채움"); }
}
