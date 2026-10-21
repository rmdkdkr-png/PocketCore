package com.dudu.pocketcore;

/**
 * 가상 상대 아바타 — 씨앗 하나로 24×24 픽셀 초상을 그린다(바깥 그림·롬 데이터 없음, 안드로이드 API 도 안 씀).
 * 설계: next/profile/design.md 5장. 원형 avatar.py 와 점 하나까지 같다(같은 씨앗 → 같은 576점, AvatarParity 로 확인).
 *  · 정수 연산만 · 난수 SplitMix64 하나(뽑는 순서까지 같게) · 문자열 씨앗은 FNV-1a 64(UTF-8)
 * render(seed) → 576개 0xAARRGGBB(가로 24 × 세로 24). 비트맵은 Avatar(안드로이드 쪽)가 만든다.
 * 규칙을 바꾸면 VERSION 을 올리고 옛 판도 남긴다 — 이미 만든 상대의 얼굴이 바뀌지 않게(상대 기록에 av_ver 를 둔다).
 */
final class AvatarGen {
    private AvatarGen() { }

    static final int N = 24, VERSION = 1;

    static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte x : b) { h ^= (x & 0xff); h *= 0x100000001b3L; }
        return h;
    }

    static final class Rng {
        private long s;
        Rng(long seed) { s = seed; }
        long next() {
            s += 0x9E3779B97F4A7C15L;
            long z = s;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
        int below(int n) { return (int) ((next() >>> 33) % n); }
        boolean chance(int pct) { return below(100) < pct; }
        int pickW(int... w) {
            int t = 0;
            for (int v : w) t += v;
            int r = below(t);
            for (int i = 0; i < w.length; i++) { if (r < w[i]) return i; r -= w[i]; }
            return w.length - 1;
        }
    }

    static int c12(int v) {
        int r = (v >> 8) & 15, g = (v >> 4) & 15, b = v & 15;
        return 0xFF000000 | (r * 17 << 16) | (g * 17 << 8) | (b * 17);
    }

    /* ── 팔레트(기본·그늘·밝음), 12비트 ── */
    private static final int[][] SKIN = { {0xFDB, 0xE98, 0xFEC}, {0xEB8, 0xC86, 0xFDB}, {0xD96, 0xA64, 0xEB8},
                                          {0xA64, 0x743, 0xC85}, {0x743, 0x532, 0x964}, {0xEEE, 0xBCD, 0xFFF} };
    private static final int[] SKIN_W = { 22, 26, 22, 14, 10, 6 };
    private static final int[][] HAIR = { {0x223, 0x112, 0x446}, {0x432, 0x211, 0x643}, {0x742, 0x521, 0x963}, {0xB32, 0x821, 0xD53},
                                          {0xEC5, 0xB93, 0xFE9}, {0xDDE, 0x99A, 0xFFF}, {0x9AC, 0x678, 0xCDE}, {0x236, 0x124, 0x459},
                                          {0x364, 0x243, 0x596}, {0x537, 0x325, 0x85A} };
    private static final int[] HAIR_W = { 30, 16, 10, 7, 6, 7, 5, 8, 4, 4 };
    private static final int[][] CLOTH = { {0x237, 0x125, 0x45A}, {0xA22, 0x711, 0xD44}, {0x253, 0x132, 0x485}, {0x526, 0x314, 0x849},
                                           {0x333, 0x222, 0x555}, {0xDDC, 0xAA9, 0xFFE}, {0xB83, 0x852, 0xDB5}, {0x266, 0x144, 0x499},
                                           {0x642, 0x421, 0x864} };
    private static final int[][] METAL = { {0x667, 0x445, 0x99A}, {0xDA3, 0xA72, 0xFD6}, {0xA63, 0x742, 0xC95}, {0x911, 0x600, 0xC33},
                                           {0x333, 0x112, 0x556} };
    private static final int[][] FUR = { {0xE83, 0xB52, 0xFB6}, {0x889, 0x556, 0xBBC}, {0x853, 0x532, 0xB85}, {0xEEE, 0xBBB, 0xFFF},
                                         {0x334, 0x112, 0x556}, {0xEDB, 0xCA8, 0xFFE}, {0xC96, 0x964, 0xEC9} };
    private static final int[] FUR2 = { 0xFFE, 0xDDC, 0xFFF };
    private static final int[][] FROG = { {0x6B4, 0x483, 0x9D6}, {0x4A8, 0x376, 0x7CB} };
    private static final int[][] ONI = { {0xC22, 0x911, 0xE54}, {0x24B, 0x128, 0x57D}, {0x5A3, 0x372, 0x8C5} };
    private static final int[] KITSUNE = { 0xFFE, 0xCCB, 0xFFF };
    private static final int[] TENGU = { 0xC32, 0x921, 0xE65 };
    private static final int[] STRAW = { 0xCA5, 0x973, 0xEC7 };
    private static final int[][] BG = { {0x124, 0x236}, {0x412, 0x623}, {0x232, 0x353}, {0x314, 0x526}, {0x234, 0x456},
                                        {0x321, 0x532}, {0x133, 0x255}, {0x222, 0x334}, {0x423, 0x734} };
    private static final int[] MOON = { 0xEEC, 0xEB4, 0xD43, 0x9BD };
    private static final int[] IRIS = { 0x632, 0x35C, 0xD92, 0x384, 0x112, 0x846 };
    private static final int[] ANIMAL_IRIS = { 0x284, 0xD92, 0x35C, 0x112 };
    private static final int[] GLOW = { 0xFE6, 0x8FF, 0xF54 };
    private static final int OUTLINE = 0x112;

    /* 재질 번호(avatar.py 와 같은 값) */
    private static final int E = 0, SK = 1, HR = 2, CL = 3, CL2 = 4, MT = 5, AC = 6, MK = 7, ST = 8, NK = 9, DK = 10, WH = 11,
                             MO = 12, RD = 13, IR = 14, BL = 15, BN = 16, GL = 17, IN = 18, OL = 19, SC = 20, PIN = 21, NMAT = 22;
    /** 고정 색 재질(그늘 없음) — 없으면 −1 */
    private static final int[] FIXED = new int[NMAT];
    static {
        java.util.Arrays.fill(FIXED, -1);
        FIXED[DK] = 0x112; FIXED[WH] = 0xFFF; FIXED[MO] = 0x722; FIXED[RD] = 0xD22; FIXED[BL] = 0xF99; FIXED[BN] = 0xEED;
        FIXED[IN] = 0xF9A; FIXED[OL] = OUTLINE; FIXED[SC] = 0xC77; FIXED[PIN] = 0xFD6;
    }
    private static boolean shaded(int m) { return m == SK || m == HR || m == CL || m == CL2 || m == MT || m == AC || m == MK || m == ST; }

    private static final int[][] HEADS = {
        { 3, 4, 5, 5, 6, 6, 6, 6, 6, 6, 5, 5, 4, 3 },   /* 둥근 */
        { 3, 4, 5, 6, 6, 6, 6, 6, 6, 6, 6, 5, 5, 4 },   /* 각진 턱 */
        { 3, 4, 5, 5, 6, 6, 6, 6, 6, 5, 4, 3, 2, 1 },   /* 뾰족 턱 */
        { 2, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 4, 3, 2 },   /* 갸름 */
    };
    private static final int[] ANIMAL_HEAD = { 5, 6, 6, 7, 7, 7, 7, 7, 7, 6, 6, 5, 4, 3 };
    private static final int[] FOX_HEAD = { 4, 5, 6, 6, 7, 7, 7, 7, 6, 5, 4, 3, 2, 1 };
    private static final int[] FROG_HEAD = { 3, 4, 6, 7, 7, 8, 8, 8, 8, 7, 7, 6, 5, 3 };
    private static final int[] SHOULDER = { 6, 8, 9, 10, 11 };

    /* ── 그림판 ── */
    private static final class Canvas {
        final int[] m = new int[N * N], t = new int[N * N];
        final int[][] col = new int[NMAT][];
        Canvas() { java.util.Arrays.fill(t, -1); }
        void set(int x, int y, int mat, int tone) { if (x >= 0 && x < N && y >= 0 && y < N) { m[y * N + x] = mat; t[y * N + x] = tone; } }
        void set(int x, int y, int mat) { set(x, y, mat, -1); }
        int get(int x, int y) { return (x >= 0 && x < N && y >= 0 && y < N) ? m[y * N + x] : E; }
        void sym(int x, int y, int mat, int tone) { set(x, y, mat, tone); set(23 - x, y, mat, tone); }
        void sym(int x, int y, int mat) { sym(x, y, mat, -1); }
        void row(int y, int k, int mat, int tone) { for (int x = 12 - k; x < 12 + k; x++) set(x, y, mat, tone); }
        void row(int y, int k, int mat) { row(y, k, mat, -1); }
        void rect(int x0, int y0, int x1, int y1, int mat, int tone) { for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) set(x, y, mat, tone); }
        void rect(int x0, int y0, int x1, int y1, int mat) { rect(x0, y0, x1, y1, mat, -1); }
    }

    private static int[] same(int v) { return new int[] { v, v, v }; }

    static int[] render(long seed) {
        Rng r = new Rng(seed);
        Canvas c = new Canvas();
        int kind = r.pickW(58, 16, 26);                     /* 0 사람 · 1 가면 · 2 동물 */
        int[] bg = BG[r.below(BG.length)];
        int moon = r.below(4);
        int moonc = MOON[r.below(MOON.length)];
        int ci = r.below(CLOTH.length);
        int ci2 = r.below(CLOTH.length);
        if (ci2 == ci) ci2 = (ci + 5) % CLOTH.length;
        int body = r.pickW(50, 22, 14, 14);
        int head = r.below(HEADS.length);
        int top = 4 + r.below(2);
        int[] skin = kind == 2 ? FUR[r.below(FUR.length)] : SKIN[r.pickW(SKIN_W)];
        int[] hair = HAIR[r.pickW(HAIR_W)];
        int[] met = METAL[r.below(METAL.length)];
        c.col[SK] = skin; c.col[NK] = skin; c.col[HR] = hair;
        c.col[CL] = CLOTH[ci]; c.col[CL2] = CLOTH[ci2];
        c.col[MT] = met; c.col[AC] = METAL[1]; c.col[ST] = STRAW;
        c.col[IR] = same(0x112); c.col[GL] = same(0xFE6); c.col[MK] = KITSUNE;

        drawBody(c, body);
        int[] hrows = HEADS[head];
        int bottom = top + hrows.length - 1;
        for (int y = bottom - 1; y < 20; y++) c.row(y, 2, NK, 1);
        if (kind == 2) {
            animal(c, r, top);
        } else {
            for (int i = 0; i < hrows.length; i++) c.row(top + i, hrows[i], SK);
            int eyeY = top + 6;
            c.sym(12 - hrows[6] - 1, eyeY, SK); c.sym(12 - hrows[6] - 1, eyeY + 1, SK, 1);
            if (kind == 0) human(c, r, top, hrows, eyeY);
            else masked(c, r, top, hrows, eyeY);
        }
        return finish(c, bg, moon, moonc);
    }

    private static void drawBody(Canvas c, int body) {
        for (int i = 0; i < 5; i++) c.row(19 + i, SHOULDER[i], CL);
        if (body == 0) {
            for (int y = 19; y < 24; y++) {
                int d = y - 19;
                c.set(10 - d / 2, y, CL2); c.set(11 - d / 2, y, CL2);
                c.set(13 + d / 2, y, CL2, 1);
                if (d >= 1) c.set(12 + d / 2, y, CL2, 1);
            }
            c.set(11, 19, NK, 1); c.set(12, 19, NK, 1);
        } else if (body == 1) {
            for (int y = 20; y < 24; y++) {
                int tone = (y % 2 == 1) ? 1 : -1;
                for (int x = 1; x < 6; x++)
                    if (x >= 12 - SHOULDER[y - 19]) { c.set(x, y, MT, tone); c.set(23 - x, y, MT, tone); }
            }
            c.set(11, 21, AC); c.set(12, 21, AC); c.set(11, 22, CL2); c.set(12, 22, CL2);
        } else if (body == 2) {
            c.row(19, 5, CL2); c.row(20, 4, CL2, 1);
            c.set(16, 21, CL2); c.set(17, 22, CL2, 1);
        } else {
            for (int y = 19; y < 24; y++) c.set(9 + (y - 19), y, CL2, 1);
            c.set(8, 20, BN); c.set(10, 21, BN); c.set(12, 22, BN); c.set(14, 21, BN); c.set(16, 20, BN);
        }
    }

    private static void eyes(Canvas c, int eyeY, int style, int brow, int browMat) {
        int lx = 8, rx = 15;
        if (style == 0) {
            c.set(lx + 1, eyeY, DK); c.set(lx + 1, eyeY + 1, DK);
            c.set(rx - 1, eyeY, DK); c.set(rx - 1, eyeY + 1, DK);
        } else if (style == 1) {
            c.set(lx, eyeY, WH); c.set(lx + 1, eyeY, IR); c.set(lx, eyeY + 1, WH); c.set(lx + 1, eyeY + 1, DK);
            c.set(rx, eyeY, WH); c.set(rx - 1, eyeY, IR); c.set(rx, eyeY + 1, WH); c.set(rx - 1, eyeY + 1, DK);
            c.set(lx, eyeY - 1, DK); c.set(lx + 1, eyeY - 1, DK); c.set(rx, eyeY - 1, DK); c.set(rx - 1, eyeY - 1, DK);
        } else if (style == 2) {
            for (int d = 0; d < 3; d++) { c.set(lx + d, eyeY, DK); c.set(rx - d, eyeY, DK); }
            c.set(lx + 1, eyeY + 1, IR); c.set(rx - 1, eyeY + 1, IR);
        } else if (style == 3) {
            c.set(lx, eyeY + 1, DK); c.set(lx + 1, eyeY, DK); c.set(lx + 2, eyeY + 1, DK);
            c.set(rx, eyeY + 1, DK); c.set(rx - 1, eyeY, DK); c.set(rx - 2, eyeY + 1, DK);
        } else {
            c.set(lx, eyeY + 1, DK); c.set(lx + 1, eyeY + 1, DK);
            c.set(rx, eyeY + 1, DK); c.set(rx - 1, eyeY + 1, DK);
        }
        if (brow == 1) {
            c.set(lx, eyeY - 2, browMat, 1); c.set(lx + 1, eyeY - 2, browMat, 1);
            c.set(rx, eyeY - 2, browMat, 1); c.set(rx - 1, eyeY - 2, browMat, 1);
        } else if (brow == 2) {
            c.set(lx - 1, eyeY - 3, browMat, 1); c.set(lx, eyeY - 2, browMat, 1); c.set(lx + 1, eyeY - 2, browMat, 1); c.set(lx + 2, eyeY - 1, browMat, 1);
            c.set(rx + 1, eyeY - 3, browMat, 1); c.set(rx, eyeY - 2, browMat, 1); c.set(rx - 1, eyeY - 2, browMat, 1); c.set(rx - 2, eyeY - 1, browMat, 1);
        } else if (brow == 3) {
            for (int d = -1; d < 3; d++) { c.set(lx + d, eyeY - 2, browMat, 1); c.set(rx - d, eyeY - 2, browMat, 1); }
        }
    }

    private static void mouth(Canvas c, int my, int style) {
        if (style == 0) { c.set(11, my, MO); c.set(12, my, MO); }
        else if (style == 1) { c.set(10, my - 1, MO); c.set(11, my, MO); c.set(12, my, MO); c.set(13, my - 1, MO); }
        else if (style == 2) { c.set(11, my, DK); c.set(12, my, DK); c.set(11, my + 1, MO); c.set(12, my + 1, MO); }
        else if (style == 3) { for (int x = 10; x < 14; x++) c.set(x, my, WH); c.set(9, my, DK); c.set(14, my, DK); }
        else { c.set(10, my + 1, MO); c.set(11, my, MO); c.set(12, my, MO); c.set(13, my + 1, MO); }
    }

    private static void hairCap(Canvas c, int top, int[] hrows, int rows) {
        for (int i = 0; i < rows; i++) c.row(top + i, hrows[i] + (i >= 1 ? 1 : 0), HR);
        c.row(top - 1, Math.max(1, hrows[0] - 1), HR);
        c.set(9, top, HR, 2); c.set(10, top, HR, 2); c.set(8, top + 1, HR, 2);
    }

    private static void human(Canvas c, Rng r, int top, int[] hrows, int eyeY) {
        int style = r.pickW(18, 20, 14, 16, 10, 8, 8, 6);
        int gear = r.pickW(40, 18, 12, 10, 10, 10);
        int eye = r.pickW(16, 30, 26, 12, 10);
        int brow = r.pickW(20, 30, 30, 20);
        int mo = r.pickW(34, 22, 12, 10, 22);
        int beard = r.pickW(70, 12, 10, 8);
        boolean scar = r.chance(12);
        boolean patch = r.chance(7);
        boolean blush = r.chance(18);
        boolean pin = r.chance(40);
        int iris = IRIS[r.below(IRIS.length)];
        c.col[IR] = same(iris);
        if (style != 5) {
            hairCap(c, top, hrows, 3);
            c.sym(12 - hrows[3] - 1, top + 3, HR); c.sym(12 - hrows[4] - 1, top + 4, HR);
            c.sym(12 - hrows[5] - 1, top + 5, HR, 1);
        }
        if (style == 1) {
            int[][] sp = { {7, 2}, {9, 3}, {11, 2}, {14, 3}, {16, 2}, {5, 1}, {18, 1} };
            for (int[] s : sp) for (int d = 1; d <= s[1]; d++) { c.set(s[0], top - d, HR); c.set(s[0] + 1, top - d, HR); }
            c.set(6, top + 3, HR); c.set(17, top + 3, HR);
        } else if (style == 2) {
            c.rect(10, top - 4, 13, top - 2, HR); c.row(top - 1, 3, HR);
            c.set(11, top - 3, HR, 2);
        } else if (style == 3) {
            for (int y = top + 3; y < 21; y++) {
                int k = (y - top < hrows.length ? hrows[y - top] : 5) + 1;
                c.set(11 - k, y, HR); c.set(12 - k, y, HR); c.set(11 + k, y, HR); c.set(12 + k, y, HR);
            }
            for (int x = 8; x < 16; x += 2) c.set(x, top + 3, HR, 1);
            if (pin) { c.set(16, top, PIN); c.set(17, top - 1, PIN); c.set(15, top + 1, RD); }
        } else if (style == 4) {
            c.rect(15, top - 3, 17, top - 1, HR);
            for (int d = 0; d < 6; d++) { c.set(18 + d / 2, top - 2 + d, HR); c.set(19 + d / 2, top - 2 + d, HR, 1); }
        } else if (style == 6) {
            c.rect(6, top - 2, 8, top, HR); c.rect(15, top - 2, 17, top, HR);
            if (pin) { c.set(5, top - 3, PIN); c.set(18, top - 3, PIN); }
        } else if (style == 7) {
            for (int i = 0; i < 5; i++) c.set(13 + i, top + 3 + i / 2, HR);
            c.set(16, top + 5, HR); c.set(17, top + 5, HR);
        } else if (style == 5) {
            c.set(9, top + 1, SK, 2); c.set(10, top + 1, SK, 2); c.set(9, top + 2, SK, 2);
        }
        eyes(c, eyeY, eye, brow, style == 5 ? SK : HR);
        c.set(12, eyeY + 2, SK, 1);
        int my = eyeY + 4;
        mouth(c, my, mo);
        if (blush && (eye == 0 || eye == 1 || eye == 3)) { c.set(7, eyeY + 2, BL); c.set(16, eyeY + 2, BL); }
        if (beard == 1) {
            c.set(9, my - 1, HR); c.set(10, my - 1, HR); c.set(13, my - 1, HR); c.set(14, my - 1, HR);
            c.set(11, my - 1, HR, 1); c.set(12, my - 1, HR, 1);
        } else if (beard == 2) {
            for (int y = my + 1; y < top + hrows.length; y++) { int k = hrows[y - top] - 1; if (k > 0) c.row(y, k, HR); }
            c.set(9, my, HR); c.set(14, my, HR);
        } else if (beard == 3) {
            for (int y = my + 1; y < top + hrows.length - 1; y++)
                for (int x = 9; x < 15; x++) if ((x + y) % 2 == 0) c.set(x, y, SK, 1);
        }
        if (scar) for (int d = 0; d < 4; d++) c.set(14 + d / 2 + (d % 2), eyeY - 2 + d, SC);
        if (patch) {
            c.rect(7, eyeY - 1, 9, eyeY + 1, DK);
            for (int x = 10; x < 18; x++) { int g = c.get(x, eyeY - 2); if (g == SK || g == HR) c.set(x, eyeY - 2 - (x - 10) / 4, DK); }
        }
        if (gear == 1) {
            for (int x = 12 - hrows[3] - 1; x < 12 + hrows[3] + 1; x++) c.set(x, top + 3, CL2, 0);
            int tx = 12 + hrows[3] + 1;
            c.set(tx, top + 3, CL2); c.set(tx + 1, top + 4, CL2); c.set(tx + 2, top + 4, CL2, 1);
            c.set(tx + 1, top + 2, CL2, 1); c.set(tx + 2, top + 1, CL2, 1);
        } else if (gear == 2) {
            for (int i = 0; i < 6; i++) c.row(top - 2 + i, Math.min(2 + i * 2, 11), ST);
            for (int x = 1; x < 23; x += 3) c.set(x, top + 3, ST, 1);
            c.row(top + 4, 7, SK, 1);
            if (eye == 1 || eye == 2) {
                c.col[GL] = same(GLOW[r.below(3)]);
                c.set(9, eyeY, GL); c.set(14, eyeY, GL);
            }
        } else if (gear == 3) {
            for (int i = 0; i < 4; i++) c.row(top - 1 + i, hrows[i] + 1, MT);
            c.row(top + 3, hrows[3] + 2, MT, 1);
            for (int y = top + 3; y < eyeY + 3; y++) {
                c.set(10 - hrows[3], y, MT); c.set(13 + hrows[3], y, MT, 1);
                c.set(9 - hrows[3], y + 1, MT, 1); c.set(14 + hrows[3], y + 1, MT, 1);
            }
            c.set(11, top + 2, AC); c.set(12, top + 2, AC);
            for (int d = 1; d < 5; d++) { c.set(11 - d, top + 1 - d, AC); c.set(12 + d, top + 1 - d, AC); }
        } else if (gear == 4) {
            for (int i = 0; i < hrows.length; i++) {
                int y = top + i;
                if (eyeY - 1 <= y && y <= eyeY + 1) continue;
                c.row(y, hrows[i] + ((2 <= i && i <= 9) ? 1 : 0), CL);
            }
            c.row(top - 1, hrows[0], CL);
            c.set(11 - hrows[6], eyeY, CL); c.set(12 + hrows[6], eyeY, CL);
            eyes(c, eyeY, 2, 2, CL);
            c.set(17, top + 2, CL); c.set(18, top + 3, CL); c.set(19, top + 3, CL, 1);
        } else if (gear == 5) {
            for (int i = 0; i < 3; i++) c.row(top - 1 + i, hrows[i] + (i != 0 ? 1 : 0), CL2);
            c.set(10 - hrows[2], top + 1, CL2); c.set(9 - hrows[2], top + 2, CL2, 1);
            c.set(9, top, CL2, 2); c.set(12, top, CL2, 2); c.set(15, top, CL2, 2);
        }
    }

    private static void masked(Canvas c, Rng r, int top, int[] hrows, int eyeY) {
        int t = r.pickW(30, 28, 18, 24);
        if (t == 0) {
            c.col[MK] = ONI[r.below(ONI.length)];
            for (int i = 0; i < hrows.length; i++) c.row(top + i, hrows[i], MK);
            c.sym(11 - hrows[6], eyeY, MK); c.sym(11 - hrows[6], eyeY + 1, MK, 1);
            hairCap(c, top, hrows, 2);
            int[][] sp = { {6, 2}, {8, 3}, {10, 2}, {13, 2}, {15, 3}, {17, 2} };
            for (int[] s : sp) for (int d = 0; d < s[1]; d++) c.set(s[0], top - 1 - d, HR);
            for (int d = 0; d < 4; d++) { c.set(7 - d / 2, top + 1 - d, BN); c.set(16 + d / 2, top + 1 - d, BN); }
            int[] ex = { 8, 9, 14, 15 };
            for (int x : ex) { c.set(x, eyeY, GL); c.set(x, eyeY + 1, GL); }
            c.set(9, eyeY + 1, DK); c.set(14, eyeY + 1, DK);
            for (int x = 7; x < 11; x++) c.set(x, eyeY - 2 + (x - 7) / 2, DK);
            for (int x = 13; x < 17; x++) c.set(x, eyeY - 2 + (16 - x) / 2, DK);
            int my = eyeY + 4;
            for (int x = 9; x < 15; x++) { c.set(x, my, DK); c.set(x, my + 1, DK); }
            c.set(9, my, BN); c.set(14, my, BN); c.set(10, my + 1, BN); c.set(13, my + 1, BN);
            c.set(11, eyeY + 2, MK, 1); c.set(12, eyeY + 2, MK, 1);
        } else if (t == 1) {
            c.col[MK] = KITSUNE;
            hairCap(c, top, hrows, 3);
            for (int i = 0; i < hrows.length; i++) c.row(top + i, hrows[i], MK);
            for (int d = 0; d < 4; d++) {
                int w = d / 2;
                for (int x = 7 - w; x < 8 + w; x++) { c.set(x, top - 3 + d, MK); c.set(23 - x, top - 3 + d, MK); }
            }
            c.set(7, top - 1, RD); c.set(16, top - 1, RD); c.set(7, top, RD); c.set(16, top, RD);
            c.set(8, eyeY + 1, DK); c.set(9, eyeY, DK); c.set(10, eyeY, DK);
            c.set(15, eyeY + 1, DK); c.set(14, eyeY, DK); c.set(13, eyeY, DK);
            c.set(8, eyeY - 2, RD); c.set(9, eyeY - 2, RD); c.set(14, eyeY - 2, RD); c.set(15, eyeY - 2, RD);
            c.set(7, eyeY + 3, RD); c.set(8, eyeY + 3, RD); c.set(16, eyeY + 3, RD); c.set(15, eyeY + 3, RD);
            c.rect(11, eyeY - 4, 12, eyeY - 3, RD);
            c.set(11, eyeY + 3, DK); c.set(12, eyeY + 3, DK);
            c.set(11, eyeY + 5, MO); c.set(12, eyeY + 5, MO);
        } else if (t == 2) {
            c.col[MK] = TENGU;
            for (int i = 0; i < hrows.length; i++) c.row(top + i, hrows[i], MK);
            c.sym(11 - hrows[6], eyeY, MK); c.sym(11 - hrows[6], eyeY + 1, MK, 1);
            hairCap(c, top, hrows, 2);
            c.rect(10, top - 2, 13, top, DK);
            c.set(11, top - 3, DK); c.set(12, top - 3, DK);
            c.set(7, eyeY - 2, WH); c.set(8, eyeY - 1, WH); c.set(9, eyeY - 1, WH);
            c.set(16, eyeY - 2, WH); c.set(15, eyeY - 1, WH); c.set(14, eyeY - 1, WH);
            c.set(9, eyeY, DK); c.set(14, eyeY, DK); c.set(8, eyeY, GL); c.set(15, eyeY, GL);
            c.rect(11, eyeY, 12, eyeY + 3, MK, 2); c.set(12, eyeY + 3, MK, 0);
            c.set(11, eyeY + 4, MK, 1); c.set(12, eyeY + 4, MK, 1);
            for (int y = eyeY + 4; y < top + hrows.length + 2; y++) {
                int k = y < top + hrows.length ? 3 : 2;
                for (int x = 12 - k; x < 12 + k; x++) if (c.get(x, y) != MK || y > eyeY + 4) c.set(x, y, WH);
            }
            c.set(11, eyeY + 5, DK); c.set(12, eyeY + 5, DK);
        } else {
            hairCap(c, top, hrows, 3);
            c.sym(11 - hrows[3], top + 3, HR);
            eyes(c, eyeY, 2, 2, HR);
            for (int y = eyeY + 2; y < top + hrows.length; y++) c.row(y, hrows[y - top], MT);
            c.row(eyeY + 2, hrows[eyeY + 2 - top] + 1, MT, 2);
            c.set(9, eyeY + 4, WH); c.set(10, eyeY + 4, WH); c.set(13, eyeY + 4, WH); c.set(14, eyeY + 4, WH);
            c.set(8, eyeY + 5, WH); c.set(15, eyeY + 5, WH);
            c.set(11, eyeY + 4, DK); c.set(12, eyeY + 4, DK);
            c.set(11, eyeY + 2, MT, 1); c.set(12, eyeY + 2, MT, 1); c.set(11, eyeY + 3, MT, 1);
            if (r.chance(50)) {
                for (int i = 0; i < 3; i++) c.row(top - 1 + i, hrows[i] + 1, MT);
                for (int d = 1; d < 5; d++) { c.set(11 - d, top - d, AC); c.set(12 + d, top - d, AC); }
                c.set(11, top, AC); c.set(12, top, AC);
            }
        }
    }

    private static void animal(Canvas c, Rng r, int top) {
        int t = r.pickW(18, 16, 14, 14, 10, 8, 8, 6, 6);
        if (t == 1 || t == 8) c.col[SK] = FUR[0];
        else if (t == 3) c.col[SK] = FUR[1 + r.below(2)];
        else if (t == 6) c.col[SK] = FUR[3];
        else if (t == 7) c.col[SK] = FROG[r.below(2)];
        c.col[NK] = c.col[SK];
        c.col[MK] = FUR2;
        int[] shape = t == 1 ? FOX_HEAD : t == 7 ? FROG_HEAD : ANIMAL_HEAD;
        for (int i = 0; i < shape.length; i++) c.row(top + i, shape[i], SK);
        int eyeY = top + 6;
        if (t == 0 || t == 1 || t == 8) {
            for (int d = 0; d < 4; d++) {
                int w = t == 1 ? (d + 1) / 2 : d / 2;
                for (int x = 6 - w; x < 7 + w; x++) { c.set(x, top - 3 + d, SK); c.set(23 - x, top - 3 + d, SK); }
            }
            c.set(6, top - 1, IN); c.set(17, top - 1, IN); c.set(6, top, IN); c.set(17, top, IN);
            if (t == 1) { c.set(6, top - 2, IN); c.set(17, top - 2, IN); }
        } else if (t == 2) {
            for (int y = top - 1; y < top + 3; y++) { c.set(5, y + 1, SK, 1); c.set(18, y + 1, SK, 1); c.set(6, y, SK); c.set(17, y, SK); }
            c.set(5, top + 4, SK, 1); c.set(18, top + 4, SK, 1);
        } else if (t == 3 || t == 4 || t == 6) {
            int ear = t == 6 ? DK : SK;
            c.rect(5, top - 1, 7, top + 1, ear); c.rect(16, top - 1, 18, top + 1, ear);
            if (t != 6) { c.set(6, top, IN); c.set(17, top, IN); }
        } else if (t == 5) {
            for (int y = top - 6; y < top + 1; y++) {
                c.set(8, y, SK); c.set(9, y, SK); c.set(14, y, SK); c.set(15, y, SK);
                if (top - 5 <= y && y <= top - 1) { c.set(9, y, IN); c.set(14, y, IN); }
            }
        } else {
            c.rect(6, top - 2, 10, top + 2, SK); c.rect(13, top - 2, 17, top + 2, SK);
        }
        if (t == 1) {
            for (int y = eyeY + 1; y < top + shape.length; y++) {
                int k = shape[y - top];
                for (int x = 12 - k; x < 15 - k; x++) c.set(x, y, MK);
                for (int x = 9 + k; x < 12 + k; x++) c.set(x, y, MK);
            }
            c.row(top + shape.length - 2, 2, MK); c.row(top + shape.length - 1, 1, MK);
        } else if (t == 0 || t == 2 || t == 4 || t == 8) {
            for (int y = eyeY + 2; y < eyeY + 6; y++) c.row(y, y < eyeY + 5 ? 3 : 2, MK);
        } else if (t == 3) {
            for (int y = eyeY - 1; y < eyeY + 3; y++) {
                for (int x = 6; x < 11; x++) c.set(x, y, (y > eyeY - 1 || x > 7) ? DK : SK, 1);
                for (int x = 13; x < 18; x++) c.set(x, y, (y > eyeY - 1 || x < 16) ? DK : SK, 1);
            }
            for (int y = eyeY + 3; y < eyeY + 6; y++) c.row(y, 3, MK);
        } else if (t == 6) {
            c.rect(7, eyeY - 1, 9, eyeY + 2, DK); c.rect(14, eyeY - 1, 16, eyeY + 2, DK);
        }
        if (t == 8) {
            c.set(9, top + 1, DK); c.set(11, top + 1, DK); c.set(13, top + 1, DK);
            c.set(10, top + 2, DK); c.set(12, top + 2, DK);
            c.set(5, eyeY + 1, DK); c.set(6, eyeY + 2, DK); c.set(18, eyeY + 1, DK); c.set(17, eyeY + 2, DK);
        }
        if (t == 7) {
            int[] xs = { 7, 14 };
            for (int x0 : xs) { c.rect(x0, top - 1, x0 + 2, top + 1, WH); c.set(x0 + 1, top, DK); c.set(x0 + 1, top + 1, DK); }
            for (int x = 8; x < 16; x++) c.set(x, eyeY + 3, DK);
            c.set(7, eyeY + 2, DK); c.set(16, eyeY + 2, DK);
            c.set(7, eyeY + 1, BL); c.set(16, eyeY + 1, BL);
        } else {
            int es = r.pickW(30, 40, 15, 15);
            c.col[IR] = same(ANIMAL_IRIS[r.below(4)]);
            if (es == 0) {
                c.set(9, eyeY, DK); c.set(9, eyeY + 1, DK); c.set(14, eyeY, DK); c.set(14, eyeY + 1, DK);
            } else if (es == 1) {
                c.rect(8, eyeY, 9, eyeY + 1, DK); c.rect(14, eyeY, 15, eyeY + 1, DK);
                c.set(8, eyeY, WH); c.set(15, eyeY, WH);
                if (t == 6) { c.set(9, eyeY + 1, IR); c.set(14, eyeY + 1, IR); }
            } else if (es == 2) {
                c.set(8, eyeY, IR); c.set(9, eyeY, IR); c.set(9, eyeY + 1, DK); c.set(8, eyeY + 1, IR);
                c.set(14, eyeY, IR); c.set(15, eyeY, IR); c.set(14, eyeY + 1, DK); c.set(15, eyeY + 1, IR);
            } else {
                c.set(8, eyeY + 1, DK); c.set(9, eyeY, DK); c.set(10, eyeY + 1, DK);
                c.set(15, eyeY + 1, DK); c.set(14, eyeY, DK); c.set(13, eyeY + 1, DK);
            }
            int nose = (t == 0 || t == 5) ? IN : DK;
            c.set(11, eyeY + 2, nose); c.set(12, eyeY + 2, nose);
            c.set(11, eyeY + 3, DK); c.set(12, eyeY + 3, DK); c.set(10, eyeY + 4, DK); c.set(13, eyeY + 4, DK);
            if (t == 0 || t == 8) {
                int[] ys = { eyeY + 2, eyeY + 4 };
                for (int y : ys) { c.set(3, y, OL); c.set(4, y, OL); c.set(19, y, OL); c.set(20, y, OL); }
            }
            if (t == 2 && r.chance(60)) { c.set(8, eyeY - 2, MK); c.set(15, eyeY - 2, MK); }
        }
        int g = r.pickW(70, 20, 10);
        if (g == 1) {
            c.col[CL2] = CLOTH[r.below(CLOTH.length)];
            for (int x = 12 - shape[2]; x < 12 + shape[2]; x++) c.set(x, top + 2, CL2);
            c.set(12 + shape[2], top + 2, CL2); c.set(13 + shape[2], top + 3, CL2); c.set(14 + shape[2], top + 3, CL2, 1);
        } else if (g == 2) {
            for (int i = 0; i < 4; i++) c.row(top - 3 + i, Math.min(2 + i * 3, 11), ST);
            for (int x = 1; x < 23; x += 3) c.set(x, top, ST, 1);
        }
    }

    private static int[] finish(Canvas c, int[] bg, int moon, int moonc) {
        int[] m = c.m, t = c.t, out = new int[N * N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            int i = y * N + x, mat = m[i];
            if (mat == E) continue;
            if (FIXED[mat] >= 0) { out[i] = c12(FIXED[mat]); continue; }
            int[] pal = c.col[mat];
            int tone = t[i];
            if (tone < 0) {
                tone = 0;
                if (shaded(mat)) {
                    int rm = c.get(x + 1, y), dm = c.get(x, y + 1), um = c.get(x, y - 1), lm = c.get(x - 1, y);
                    if (x >= 12 && rm != mat && (rm != E || mat != HR)) tone = 1;
                    if ((mat == HR || mat == CL || mat == MT || mat == ST || mat == CL2) && dm != mat && dm != E) tone = 1;
                    if (mat == SK && (um == HR || um == MT || um == ST || um == CL || um == CL2)) tone = 1;
                    if (tone == 0 && x <= 11 && (lm == E || um == E) && (mat == HR || mat == MT || mat == ST || mat == MK)) tone = 2;
                }
            }
            out[i] = c12(pal[tone]);
        }
        int bg0 = c12(bg[0]), bg1 = c12(bg[1]);
        int mx = -1;
        if (moon != 0) {
            for (int k = 0; k < 2; k++) {
                int cx = ((moon == 1) == (k == 0)) ? 3 : 20;
                if (discFree(c, cx)) { mx = cx; break; }
            }
        }
        int[] res = new int[N * N];
        int[][] nb = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            int i = y * N + x;
            if (m[i] != E) { res[i] = out[i]; continue; }
            boolean edge = false;
            for (int[] d : nb) { int v = c.get(x + d[0], y + d[1]); if (v != E && v != OL) edge = true; }
            if (edge) { res[i] = c12(OUTLINE); continue; }
            int v = y < 10 ? bg0 : y >= 12 ? bg1 : ((x + y) % 2 == 0 ? bg0 : bg1);
            if (mx >= 0) {
                int dx = x - mx, dy = y - 3;
                if (dx * dx + dy * dy <= 5) {
                    int ex = x - mx - (mx < 12 ? 1 : -1), ey = y - 2;
                    if (moon == 3 || ex * ex + ey * ey > 4) v = c12(moonc);
                }
            }
            res[i] = v;
        }
        return res;
    }

    private static boolean discFree(Canvas c, int cx) {
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            int dx = x - cx, dy = y - 3;
            if (dx * dx + dy * dy <= 5 && near(c, x, y)) return false;
        }
        return true;
    }

    private static boolean near(Canvas c, int x, int y) {
        for (int dy = -1; dy < 2; dy++) for (int dx = -1; dx < 2; dx++) if (c.get(x + dx, y + dy) != E) return true;
        return false;
    }
}
