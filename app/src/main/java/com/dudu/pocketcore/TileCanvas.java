package com.dudu.pocketcore;

/**
 * 157 TileCanvas — NGPC 규칙(8×8 칸 · 칸마다 4색 팔레트 하나 · 2bpp · 12비트 색)으로 그리는 화판. 덧판(ovplane)·VS 띠(RankVs)가 같이 쓴다.
 * 계약서 docs/157_contract.md §5. 목업(handoff/.../157_인게임메뉴_기획/시안/mock_final.py 의 Frame)과 같은 그리기 규칙.
 *
 * 좌표: 픽셀 (x, y) — 캔버스 왼위 0,0. 칸 (c, r) = 픽셀 8c..8c+7 × 8r..8r+7. 칠(ci) 0..3, 0 = 투명(덧판) / RankVs 는 바탕 칠 3.
 * 글자 y = 한글 몸통 맨 위 줄(목업 mock_final.py 와 같은 기준). 잉크의 마스크 줄 0 은 글꼴 줄 맨 위(ascent) — text() 가 bodyTop 만큼 올려 그린다.
 * 인코딩: 타일 = 16바이트(줄마다 16비트 LE, 왼쪽 픽셀이 높은 비트 — (ci & 3) << (14 - 2x)) · 맵 칸 = (팔레트<<9)|상대 번호 | 0x4000 V | 0x8000 H · 0xFFFF 빈 칸.
 * 줄 먼저(row-major)로 훑으며 처음 나온 순서대로 번호(RankVs 와 같은 순서). 같은 그림은 팔레트가 달라도 한 타일. 넘치면 Overflow(검사되지 않는 예외).
 * 그리기는 팔레트를 매기지 않는다 — 부르는 쪽이 cellPal/claimPal 로 매긴다(목업 setpal · RankVs mark).
 * 스레드: 한 캔버스는 한 스레드에서만(그림 스레드). 잉크(Ink)는 캐시를 가져도 되지만 여러 캔버스가 같은 스레드에서 쓴다.
 */
final class TileCanvas {
    /** 글꼴 잉크 — RankVs.Ink 가 이것을 넓힌다(람다 하나로 만들 수 있게 추상 메서드는 mask 하나) */
    interface Ink {
        /** s 를 그린 잉크 [y][x] — y 0 = 글꼴 줄 맨 위(ascent), 안티에일리어스 끔 · 길이는 구현이 정함(RankVs 안드로이드 잉크 20×256) */
        boolean[][] mask(String s);
        /** 한글 몸통 맨 위 줄 = mask("가") 의 첫 잉크 줄(갈무리11 12px = 1 · 갈무리7 8px = 1 — AWT·PIL 잼). 구현이 캐시해도 된다 */
        default int bodyTop() {
            boolean[][] m = mask("가");
            for (int y = 0; y < m.length; y++) for (boolean b : m[y]) if (b) return y;
            return 0;
        }
        /** 이 글꼴에 그 글자가 있나(없으면 시스템 글꼴로 그려져 픽셀 글꼴이 깨진다) — 모르면 true */
        default boolean has(int codePoint) { return true; }
    }
    /** 앱 글꼴 셋 — g11 = 갈무리11 12px(assets/fonts/pixel.ttf) · g11b = 갈무리11 Bold 12px(pixel_b.ttf) · g7 = 갈무리7 8px(galmuri7.ttf) */
    static final class Inks {
        final Ink g11, g11b, g7;
        Inks(Ink g11, Ink g11b, Ink g7) { this.g11 = g11; this.g11b = g11b; this.g7 = g7; }
    }
    /** 칸 예산·팔레트 넘침·팔레트 없는 칸에 잉크(strict) — 부르는 쪽(LgmPages)이 받아 옛 카드로 돌아간다 */
    static final class Overflow extends RuntimeException { private static final long serialVersionUID = 1L; Overflow(String m) { super(m); } }
    /** 인코딩 결과 — map 은 cols×rows(줄 먼저), 칸 (col0 + i % cols, row0 + i / cols) 자리 · tiles 의 번호는 tileBase 기준 상대 */
    static final class Encoded {
        byte[] tiles;        // ntiles × 16
        short[] map;         // cols × rows, 0xFFFF = 빈 칸
        int cols, rows, col0, row0, tileBase, ntiles;
        short[] pals;        // 16 × 4 (칸 0 무시) — 이 캔버스의 팔레트 표(RankVs 처럼 바깥 팔레트 번호만 쓰면 0)
        int npals;           // 쓴 팔레트 칸 수(가장 큰 번호 + 1)
    }

    static final int LEFT = 0, RIGHT = 1, CENTER = 2;
    static final int MAX_PALS = 16, MAX_CHUNK = 511;
    /** 덧판 층 한도(ovplane.h) — W 타일 512 · L 2048 */
    static final int W_TILES = 512, L_TILES = 2048;
    static final int EMPTY = 0xFFFF, M_VFLIP = 0x4000, M_HFLIP = 0x8000;

    private final int cols, rows, w, h, bg;
    private final byte[] pix;           // w × h, 칠 0..3
    private final byte[] cpal;          // cols × rows, −1 = 팔레트 없음
    private final int[] palC = new int[MAX_PALS * 3];
    private final boolean[] palSet = new boolean[MAX_PALS];

    /** 바탕 칠 0(투명) */
    TileCanvas(int cols, int rows) { this(cols, rows, 0); }
    /** 바탕 칠 fill(RankVs = 3 검정) */
    TileCanvas(int cols, int rows, int fill) {
        if (cols < 1 || rows < 1 || cols > 256 || rows > 256) throw new IllegalArgumentException("칸 " + cols + "×" + rows);
        if (fill < 0 || fill > 3) throw new IllegalArgumentException("바탕 칠 " + fill);
        this.cols = cols; this.rows = rows; this.w = cols * 8; this.h = rows * 8; this.bg = fill;
        pix = new byte[w * h];
        if (fill != 0) java.util.Arrays.fill(pix, (byte) fill);
        cpal = new byte[cols * rows];
        java.util.Arrays.fill(cpal, (byte) -1);
    }

    int cols() { return cols; }
    int rows() { return rows; }
    int width() { return w; }
    int height() { return h; }
    int background() { return bg; }

    /* ── 팔레트 ── */
    /** 세 색(12비트) 팔레트 칸 — 같은 세 색이면 같은 칸을 돌려줌 · 16 넘으면 Overflow */
    int pal(int c1, int c2, int c3) {
        c1 &= 0xFFF; c2 &= 0xFFF; c3 &= 0xFFF;
        for (int s = 0; s < MAX_PALS; s++)
            if (palSet[s] && palC[s * 3] == c1 && palC[s * 3 + 1] == c2 && palC[s * 3 + 2] == c3) return s;
        for (int s = 0; s < MAX_PALS; s++) if (!palSet[s] && !slotInUse(s)) { setPal(s, c1, c2, c3); return s; }
        throw new Overflow("팔레트 16칸 넘음(" + Integer.toHexString(c1) + "," + Integer.toHexString(c2) + "," + Integer.toHexString(c3) + ")");
    }
    /** 칸 표에 바깥 팔레트 번호(색 없음)로 이미 쓰인 칸 — pal() 이 새 색을 거기 주지 않게 */
    private boolean slotInUse(int s) {
        for (byte p : cpal) if (p == s) return true;
        return false;
    }
    void setPal(int slot, int c1, int c2, int c3) {
        checkSlot(slot);
        palSet[slot] = true; palC[slot * 3] = c1 & 0xFFF; palC[slot * 3 + 1] = c2 & 0xFFF; palC[slot * 3 + 2] = c3 & 0xFFF;
    }
    /** 팔레트 칸의 색 1..3(12비트) — 정하지 않은 칸 −1 */
    int palColor(int slot, int idx) {
        checkSlot(slot);
        if (idx < 1 || idx > 3) throw new IllegalArgumentException("색 칸 " + idx);
        return palSet[slot] ? palC[slot * 3 + idx - 1] : -1;
    }
    /** 칸 범위(포함)에 팔레트 — 덮어씀(목업 setpal) */
    void cellPal(int c0, int r0, int c1, int r1, int slot) {
        checkSlot(slot);
        for (int r = Math.max(0, r0); r <= Math.min(rows - 1, r1); r++)
            for (int c = Math.max(0, c0); c <= Math.min(cols - 1, c1); c++) cpal[r * cols + c] = (byte) slot;
    }
    /** 칸 범위의 팔레트를 지움(빈 칸 — 인코딩에서 0xFFFF) */
    void clearPal(int c0, int r0, int c1, int r1) {
        for (int r = Math.max(0, r0); r <= Math.min(rows - 1, r1); r++)
            for (int c = Math.max(0, c0); c <= Math.min(cols - 1, c1); c++) cpal[r * cols + c] = -1;
    }
    /** 픽셀 사각형이 닿는 칸에 팔레트 — 이미 매긴 칸은 그대로(먼저 매긴 것이 이김 — RankVs mark) */
    void claimPal(int x, int y, int w, int h, int slot) {
        checkSlot(slot);
        if (w <= 0 || h <= 0) return;
        for (int cy = Math.max(0, Math.floorDiv(y, 8)); cy <= Math.min(rows - 1, Math.floorDiv(y + h - 1, 8)); cy++)
            for (int cx = Math.max(0, Math.floorDiv(x, 8)); cx <= Math.min(cols - 1, Math.floorDiv(x + w - 1, 8)); cx++)
                if (cpal[cy * cols + cx] < 0) cpal[cy * cols + cx] = (byte) slot;
    }
    /** −1 = 없음 */
    int cellPalAt(int c, int r) {
        if (c < 0 || r < 0 || c >= cols || r >= rows) return -1;
        return cpal[r * cols + c];
    }
    private static void checkSlot(int slot) { if (slot < 0 || slot >= MAX_PALS) throw new IllegalArgumentException("팔레트 칸 " + slot); }

    /* ── 그리기(캔버스 밖은 자름) ── */
    void px(int x, int y, int ci) {
        if (x >= 0 && y >= 0 && x < w && y < h) pix[y * w + x] = (byte) (ci & 3);
    }
    /** 캔버스 밖 = 바탕 칠 */
    int get(int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) return bg;
        return pix[y * w + x];
    }
    /** (x0,y0)..(x1,y1) 포함 */
    void fill(int x0, int y0, int x1, int y1, int ci) {
        int xa = Math.max(0, Math.min(x0, x1)), xb = Math.min(w - 1, Math.max(x0, x1));
        int ya = Math.max(0, Math.min(y0, y1)), yb = Math.min(h - 1, Math.max(y0, y1));
        byte v = (byte) (ci & 3);
        for (int y = ya; y <= yb; y++) java.util.Arrays.fill(pix, y * w + xa, y * w + xb + 1, v);
    }
    /** RankVs.box 와 같음: 테두리 edgeCi, 안 fillCi */
    void box(int x, int y, int w, int h, int fillCi, int edgeCi) {
        for (int yy = y; yy < y + h; yy++) for (int xx = x; xx < x + w; xx++)
            px(xx, yy, (yy == y || yy == y + h - 1 || xx == x || xx == x + w - 1) ? edgeCi : fillCi);
    }
    /** 글자 — y = 한글 몸통 맨 위. 돌려줌: 끝 x(왼쪽 맞춤 기준) */
    int text(String s, int x, int y, int ci, Ink ink) { return text(s, x, y, ci, ink, -1, LEFT, 0); }
    /** shadowCi ≥ 0 이면 (+1,+1)에 그림자 — 그 자리가 0 또는 3 일 때만(목업·RankVs 규칙) · align LEFT/RIGHT/CENTER(x 기준) · maxW > 0 이면 자름 */
    int text(String s, int x, int y, int ci, Ink ink, int shadowCi, int align, int maxW) {
        if (s == null || s.isEmpty()) return alignX(x, 0, align);
        boolean[][] m = ink.mask(s);
        int tw = maskW(m);
        if (maxW > 0 && tw > maxW) tw = maxW;
        int lx = alignX(x, tw, align);
        drawMask(m, tw, lx, y - ink.bodyTop(), ci, shadowCi);
        return lx + tw;
    }
    /** 글자 — 마스크 줄 0(글꼴 ascent)을 yTop 에(RankVs 의 «줄 맨 위» 기준). 돌려줌: 끝 x */
    int textTop(String s, int x, int yTop, int ci, Ink ink, int shadowCi, int maxW) {
        if (s == null || s.isEmpty()) return x;
        boolean[][] m = ink.mask(s);
        int tw = maskW(m);
        if (maxW > 0 && tw > maxW) tw = maxW;
        drawMask(m, tw, x, yTop, ci, shadowCi);
        return x + tw;
    }
    private static int alignX(int x, int tw, int align) { return align == RIGHT ? x - tw : align == CENTER ? x - tw / 2 : x; }
    /** 그림자 먼저(잉크 픽셀마다 (+1,+1) 이 0·3 일 때) → 잉크. 칸 tw 열까지만 */
    private void drawMask(boolean[][] m, int tw, int x, int y, int ci, int shadowCi) {
        if (shadowCi >= 0) {
            for (int yy = 0; yy < m.length; yy++) for (int xx = 0, n = Math.min(tw, m[yy].length); xx < n; xx++) {
                if (!m[yy][xx]) continue;
                int X = x + xx + 1, Y = y + yy + 1;
                if (X >= 0 && Y >= 0 && X < w && Y < h) { int v = pix[Y * w + X]; if (v == 0 || v == 3) pix[Y * w + X] = (byte) (shadowCi & 3); }
            }
        }
        for (int yy = 0; yy < m.length; yy++) for (int xx = 0, n = Math.min(tw, m[yy].length); xx < n; xx++)
            if (m[yy][xx]) px(x + xx, y + yy, ci);
    }
    private static int maskW(boolean[][] m) {
        int tw = 0;
        for (boolean[] row : m) for (int x = row.length - 1; x >= tw; x--) if (row[x]) { tw = x + 1; break; }
        return tw;
    }
    int textW(String s, Ink ink) {
        if (s == null || s.isEmpty()) return 0;
        return maskW(ink.mask(s));
    }
    /** 폭 안에 들게 — 넘치면 끝을 줄이고 «…»(RankVs.fit 와 같음) */
    String fit(String s, int maxW, Ink ink) {
        if (s == null) return "";
        if (textW(s, ink) <= maxW) return s;
        for (int n = s.codePointCount(0, s.length()) - 1; n > 0; n--) {
            String t = s.substring(0, s.offsetByCodePoints(0, n)) + "…";
            if (textW(t, ink) <= maxW) return t;
        }
        return "…";
    }
    /** 잉크가 s 의 모든 글자를 가졌나(Ink.has) — 아니면 LgmPages 가 갈무리11 로 대신 */
    static boolean covers(Ink ink, String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); ) { int cp = s.codePointAt(i); if (!ink.has(cp)) return false; i += Character.charCount(cp); }
        return true;
    }

    /* 작은 숫자 — 우리 그림(목업 MINI3 · RankVs MINI/STAR) */
    private static final String MINI3_CH = "0123456789/+-,";
    private static final String[][] MINI3 = {
        { "111", "101", "101", "101", "111" }, { "010", "110", "010", "010", "111" }, { "111", "001", "111", "100", "111" },
        { "111", "001", "011", "001", "111" }, { "101", "101", "111", "001", "001" }, { "111", "100", "111", "001", "111" },
        { "111", "100", "111", "101", "111" }, { "111", "001", "010", "010", "010" }, { "111", "101", "111", "101", "111" },
        { "111", "101", "111", "001", "111" }, { "001", "001", "010", "100", "100" }, { "000", "010", "111", "010", "000" },
        { "000", "000", "111", "000", "000" }, { "000", "000", "000", "010", "100" },
    };
    private static final String[][] MINI4 = {
        { ".##.", "#..#", "#..#", "#..#", "#..#", "#..#", ".##." }, { ".#..", "##..", ".#..", ".#..", ".#..", ".#..", "###." },
        { ".##.", "#..#", "...#", "..#.", ".#..", "#...", "####" }, { ".##.", "#..#", "...#", "..#.", "...#", "#..#", ".##." },
        { "..#.", ".##.", "#.#.", "#.#.", "####", "..#.", "..#." }, { "####", "#...", "###.", "...#", "...#", "#..#", ".##." },
        { ".##.", "#...", "###.", "#..#", "#..#", "#..#", ".##." }, { "####", "...#", "..#.", "..#.", ".#..", ".#..", ".#.." },
        { ".##.", "#..#", "#..#", ".##.", "#..#", "#..#", ".##." }, { ".##.", "#..#", "#..#", ".###", "...#", "...#", ".##." },
    };
    private static final String[] STAR = { "..#..", ".###.", "#####", ".###.", ".#.#." };

    /** 3×5 작은 숫자 — 0-9 / + - , (글자 사이 1px, 모르는 글자는 2px 띄움 — 목업 mini3) · y = 맨 위 줄. 돌려줌: 끝 x(마지막 글자 뒤 1px 포함) */
    int mini3(String s, int x, int y, int ci, int align) {
        if (s == null) return x;
        x = alignX(x, mini3W(s), align);
        for (int i = 0; i < s.length(); i++) {
            int k = MINI3_CH.indexOf(s.charAt(i));
            if (k < 0) { x += 2; continue; }
            String[] g = MINI3[k];
            for (int yy = 0; yy < 5; yy++) for (int xx = 0; xx < 3; xx++) if (g[yy].charAt(xx) == '1') px(x + xx, y + yy, ci);
            x += 4;
        }
        return x;
    }
    static int mini3W(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) n += MINI3_CH.indexOf(s.charAt(i)) >= 0 ? 4 : 2;
        return n - 1;
    }
    /** 4×7 작은 숫자 · '*' = 별 5×5(한 줄 아래) — RankVs 그림 그대로 · shadowCi ≥ 0 이면 (+1,+1) 그림자(0·3 자리만).
     *  모르는 글자는 건너뜀(폭 0). 돌려줌: 끝 x(마지막 글자 뒤 1px 포함 — RankVs.mini 의 cx) */
    int mini4(String s, int x, int y, int ci, int shadowCi, int align) {
        if (s == null) return x;
        int cx = alignX(x, mini4W(s), align);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i); String[] g = glyph4(c);
            if (g == null) continue;
            int oy = y + (c == '*' ? 1 : 0);
            for (int yy = 0; yy < g.length; yy++) for (int xx = 0; xx < g[yy].length(); xx++) {
                if (g[yy].charAt(xx) != '#') continue;
                int X = cx + xx, Y = oy + yy;
                if (shadowCi >= 0 && X + 1 >= 0 && Y + 1 >= 0 && X + 1 < w && Y + 1 < h) {
                    int v = pix[(Y + 1) * w + X + 1];
                    if (v == 0 || v == 3) pix[(Y + 1) * w + X + 1] = (byte) (shadowCi & 3);
                }
                px(X, Y, ci);
            }
            cx += g[0].length() + 1;
        }
        return cx;
    }
    private static String[] glyph4(char c) { return c == '*' ? STAR : (c >= '0' && c <= '9') ? MINI4[c - '0'] : null; }
    static int mini4W(String s) {
        if (s == null) return 0;
        int n = 0, k = 0;
        for (int i = 0; i < s.length(); i++) { String[] g = glyph4(s.charAt(i)); if (g != null) { n += g[0].length() + 1; k++; } }
        return k == 0 ? 0 : n - 1;
    }
    /** OvArt 그림 — 그림의 칠 1·2·3 을 ci1·ci2·ci3 으로(0 = 안 그림), hflip = 좌우 뒤집기 */
    void art(int id, int x, int y, int ci1, int ci2, int ci3, boolean hflip) {
        int aw = OvArt.w(id), ah = OvArt.h(id);
        for (int yy = 0; yy < ah; yy++) for (int xx = 0; xx < aw; xx++) {
            int v = OvArt.px(id, hflip ? aw - 1 - xx : xx, yy);
            int ci = v == 1 ? ci1 : v == 2 ? ci2 : v == 3 ? ci3 : 0;
            if (ci != 0) px(x + xx, y + yy, ci);
        }
    }

    /* ── 인코딩 ── */
    /** 캔버스 전체 — flipDedupe = H/V/HV 뒤집기로 같은 타일을 하나로(RankVs 는 false) · strict = 팔레트 없는 칸에 0 아닌(바탕 칠과 다른) 픽셀이면 Overflow ·
     *  maxTiles ≤ 511(덧판 한 묶음) · 팔레트 없는 칸 = 0xFFFF */
    Encoded encode(int tileBase, int maxTiles, boolean flipDedupe, boolean strict) {
        return encodeRect(0, 0, cols - 1, rows - 1, tileBase, maxTiles, flipDedupe, strict);
    }
    /** 칸 사각형만(포함) — Encoded.col0/row0 = c0/r0 */
    Encoded encodeRect(int c0, int r0, int c1, int r1, int tileBase, int maxTiles, boolean flipDedupe, boolean strict) {
        if (c0 < 0 || r0 < 0 || c1 >= cols || r1 >= rows || c1 < c0 || r1 < r0) throw new IllegalArgumentException("칸 사각형 " + c0 + "," + r0 + ".." + c1 + "," + r1);
        if (maxTiles < 0 || maxTiles > MAX_CHUNK) throw new IllegalArgumentException("maxTiles " + maxTiles + " (≤ " + MAX_CHUNK + ")");
        if (tileBase < 0) throw new IllegalArgumentException("tileBase " + tileBase);
        Encoded e = new Encoded();
        e.cols = c1 - c0 + 1; e.rows = r1 - r0 + 1; e.col0 = c0; e.row0 = r0; e.tileBase = tileBase;
        e.map = new short[e.cols * e.rows];
        java.util.HashMap<Key, Integer> idx = new java.util.HashMap<>();
        java.io.ByteArrayOutputStream tiles = new java.io.ByteArrayOutputStream();
        byte[] t = new byte[16];
        for (int r = r0; r <= r1; r++) for (int c = c0; c <= c1; c++) {
            int i = (r - r0) * e.cols + (c - c0);
            int p = cpal[r * cols + c];
            if (p < 0) {
                if (strict) checkBlank(c, r);
                e.map[i] = (short) EMPTY;
                continue;
            }
            tileAt(c, r, t);
            int flip = 0;
            Integer n = idx.get(new Key(t));
            if (n == null && flipDedupe) {
                byte[] f = new byte[16];
                flipTile(t, f, true, false); n = idx.get(new Key(f)); if (n != null) flip = M_HFLIP;
                if (n == null) { flipTile(t, f, false, true); n = idx.get(new Key(f)); if (n != null) flip = M_VFLIP; }
                if (n == null) { flipTile(t, f, true, true); n = idx.get(new Key(f)); if (n != null) flip = M_HFLIP | M_VFLIP; }
            }
            if (n == null) {
                n = idx.size();
                if (n >= maxTiles) throw new Overflow("타일 " + (n + 1) + "개 > " + maxTiles + " (칸 " + c + "," + r + ")");
                idx.put(new Key(t.clone()), n);
                tiles.write(t, 0, 16);
            }
            e.map[i] = (short) ((p << 9) | n | flip);
        }
        e.tiles = tiles.toByteArray();
        e.ntiles = idx.size();
        palsInto(e);
        return e;
    }
    /** L 층용 — 줄을 위에서부터 묶어 묶음마다 고유 타일 ≤ maxPerBand(≤511, 묶음 안에서만 합침), tileBase 를 이어 붙임.
     *  합이 maxTotal(L 2048 − tileBase0) 넘으면 Overflow. 한 줄이 maxPerBand 를 넘으면 Overflow · 뒤집기 합침 켬 · strict */
    Encoded[] encodeBands(int tileBase0, int maxPerBand, int maxTotal) { return encodeBands(tileBase0, maxPerBand, maxTotal, true, true); }
    Encoded[] encodeBands(int tileBase0, int maxPerBand, int maxTotal, boolean flipDedupe, boolean strict) {
        if (maxPerBand < 1 || maxPerBand > MAX_CHUNK) throw new IllegalArgumentException("maxPerBand " + maxPerBand);
        java.util.ArrayList<Encoded> out = new java.util.ArrayList<>();
        int base = tileBase0, total = 0, r0 = 0;
        while (r0 < rows) {
            Encoded best = null; int r1 = r0;
            for (int r = r0; r < rows; r++) {
                Encoded e;
                try { e = encodeRect(0, r0, cols - 1, r, base, maxPerBand, flipDedupe, strict); }
                catch (Overflow o) {
                    boolean tiles = o.getMessage().startsWith("타일");
                    if (tiles && best != null) break;
                    throw tiles ? new Overflow("줄 " + r + " 혼자 " + o.getMessage()) : o;
                }
                best = e; r1 = r;
            }
            total += best.ntiles;
            if (total > maxTotal) throw new Overflow("L 타일 합 " + total + " > " + maxTotal + " (줄 " + r0 + ".." + r1 + ")");
            out.add(best);
            base += best.ntiles;
            r0 = r1 + 1;
        }
        return out.toArray(new Encoded[0]);
    }
    private void palsInto(Encoded e) {
        e.pals = new short[MAX_PALS * 4];
        e.npals = 0;
        for (int s = 0; s < MAX_PALS; s++) if (palSet[s]) {
            e.pals[s * 4 + 1] = (short) palC[s * 3]; e.pals[s * 4 + 2] = (short) palC[s * 3 + 1]; e.pals[s * 4 + 3] = (short) palC[s * 3 + 2];
            e.npals = s + 1;
        }
    }
    private void checkBlank(int c, int r) {
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
            if (pix[(r * 8 + y) * w + c * 8 + x] != bg) throw new Overflow("팔레트 없는 칸에 잉크 (칸 " + c + "," + r + " 픽셀 " + (c * 8 + x) + "," + (r * 8 + y) + ")");
    }
    /** 칸 (c,r) 의 타일 16바이트 */
    void tileAt(int c, int r, byte[] t) {
        for (int y = 0; y < 8; y++) {
            int v = 0, o = (r * 8 + y) * w + c * 8;
            for (int x = 0; x < 8; x++) v |= (pix[o + x] & 3) << (14 - 2 * x);
            t[y * 2] = (byte) v; t[y * 2 + 1] = (byte) (v >> 8);
        }
    }
    /** 타일 뒤집기(H = x → 7−x · V = y → 7−y) — src ≠ dst */
    static void flipTile(byte[] src, byte[] dst, boolean hf, boolean vf) {
        for (int y = 0; y < 8; y++) {
            int sy = vf ? 7 - y : y;
            int v = (src[sy * 2] & 0xFF) | ((src[sy * 2 + 1] & 0xFF) << 8), o = 0;
            for (int x = 0; x < 8; x++) {
                int sx = hf ? 7 - x : x;
                o |= ((v >> (14 - 2 * sx)) & 3) << (14 - 2 * x);
            }
            dst[y * 2] = (byte) o; dst[y * 2 + 1] = (byte) (o >> 8);
        }
    }
    /** 16바이트 타일 열쇠 */
    private static final class Key {
        final long a, b;
        Key(byte[] t) {
            long x = 0, y = 0;
            for (int i = 0; i < 8; i++) { x = (x << 8) | (t[i] & 0xFF); y = (y << 8) | (t[i + 8] & 0xFF); }
            a = x; b = y;
        }
        @Override public boolean equals(Object o) { return o instanceof Key && ((Key) o).a == a && ((Key) o).b == b; }
        @Override public int hashCode() { return Long.hashCode(a * 31 + b); }
    }

    /* ── 글꼴 cmap(어떤 글자가 들었나) — 안드로이드 Paint.hasGlyph 는 대체 글꼴까지 보므로 TTF 를 직접 읽는다 ── */
    /** TTF 바이트의 cmap(형식 4·12) 이 덮는 코드 포인트 — 못 읽으면 null */
    static java.util.BitSet cmap(byte[] f) {
        try {
            int n = u16(f, 4), cm = -1;
            for (int i = 0; i < n; i++) {
                int r = 12 + 16 * i;
                if (f[r] == 'c' && f[r + 1] == 'm' && f[r + 2] == 'a' && f[r + 3] == 'p') { cm = (int) u32(f, r + 8); break; }
            }
            if (cm < 0) return null;
            int nt = u16(f, cm + 2), best = -1, bestScore = -1;
            for (int i = 0; i < nt; i++) {
                int pid = u16(f, cm + 4 + 8 * i), eid = u16(f, cm + 6 + 8 * i), off = cm + (int) u32(f, cm + 8 + 8 * i);
                int fmt = u16(f, off), score = -1;
                if (fmt == 12 && (pid == 3 && eid == 10 || pid == 0)) score = 3;
                else if (fmt == 4 && (pid == 3 && eid == 1 || pid == 0)) score = 2;
                if (score > bestScore) { bestScore = score; best = off; }
            }
            if (best < 0) return null;
            java.util.BitSet bs = new java.util.BitSet();
            int fmt = u16(f, best);
            if (fmt == 12) {
                long ng = u32(f, best + 12);
                for (long g = 0; g < ng; g++) {
                    int o = best + 16 + (int) g * 12;
                    long s = u32(f, o), e = u32(f, o + 4), gid = u32(f, o + 8);
                    for (long c = s; c <= e && c < 0x110000; c++) if (gid + (c - s) != 0) bs.set((int) c);
                }
            } else {
                int seg = u16(f, best + 6) / 2;
                int ends = best + 14, starts = ends + seg * 2 + 2, deltas = starts + seg * 2, ranges = deltas + seg * 2;
                for (int i = 0; i < seg; i++) {
                    int e = u16(f, ends + 2 * i), s = u16(f, starts + 2 * i), d = u16(f, deltas + 2 * i), ro = u16(f, ranges + 2 * i);
                    for (int c = s; c <= e && c != 0xFFFF; c++) {
                        int g;
                        if (ro == 0) g = (c + d) & 0xFFFF;
                        else { g = u16(f, ranges + 2 * i + ro + 2 * (c - s)); if (g != 0) g = (g + d) & 0xFFFF; }
                        if (g != 0) bs.set(c);
                    }
                }
            }
            return bs;
        } catch (RuntimeException e) {
            return null;
        }
    }
    private static int u16(byte[] f, int o) { return ((f[o] & 0xFF) << 8) | (f[o + 1] & 0xFF); }
    private static long u32(byte[] f, int o) { return ((long) u16(f, o) << 16) | u16(f, o + 2); }

    /* ── 안드로이드 잉크 ── */
    /** 픽셀 글꼴 sizePx(안티에일리어스 끔) — 마스크 줄 0 = ascent 위(RankVs.androidInk 와 같은 그림) · has() = Paint.hasGlyph */
    static Ink androidInk(android.graphics.Typeface tf, int sizePx) { return androidInk(tf, sizePx, null); }
    /** cover = 글꼴 cmap(cmap(바이트)) — 있으면 has() 가 이것을 봄(대체 글꼴 무시), 없으면 Paint.hasGlyph */
    static Ink androidInk(final android.graphics.Typeface tf, final int sizePx, final java.util.BitSet cover) {
        return new Ink() {
            final java.util.HashMap<String, boolean[][]> cache = new java.util.HashMap<>();
            final android.graphics.Paint p = new android.graphics.Paint();
            int top = -1;
            {
                p.setTypeface(tf); p.setTextSize(sizePx); p.setAntiAlias(false); p.setColor(0xffffffff);
            }
            @Override public boolean[][] mask(String s) {
                boolean[][] m = cache.get(s);
                if (m != null) return m;
                int bw = Math.max(256, (int) Math.ceil(p.measureText(s)) + 8), bh = Math.max(20, sizePx * 2);
                android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
                new android.graphics.Canvas(bm).drawText(s, 0, -p.ascent(), p);
                int[] px = new int[bw * bh];
                bm.getPixels(px, 0, bw, 0, 0, bw, bh);
                m = new boolean[bh][bw];
                for (int y = 0; y < bh; y++) for (int x = 0; x < bw; x++) m[y][x] = (px[y * bw + x] >>> 24) > 127;
                bm.recycle();
                if (cache.size() > 512) cache.clear();
                cache.put(s, m);
                return m;
            }
            @Override public int bodyTop() {
                if (top < 0) top = Ink.super.bodyTop();
                return top;
            }
            @Override public boolean has(int cp) {
                if (cover != null) return cover.get(cp);
                return p.hasGlyph(new String(Character.toChars(cp)));
            }
        };
    }
    /** assets/fonts/pixel.ttf 12 · pixel_b.ttf 12 · galmuri7.ttf 8 — 못 읽으면 null(부르는 쪽이 옛 카드로) */
    static Inks androidInks(android.content.res.AssetManager am) {
        try {
            Ink a = assetInk(am, "fonts/pixel.ttf", 12), b = assetInk(am, "fonts/pixel_b.ttf", 12), c = assetInk(am, "fonts/galmuri7.ttf", 8);
            return new Inks(a, b, c);
        } catch (Exception | Error e) {
            android.util.Log.w("PocketCore", "TileCanvas inks: " + e);
            return null;
        }
    }
    private static Ink assetInk(android.content.res.AssetManager am, String path, int size) throws java.io.IOException {
        byte[] bytes;
        try (java.io.InputStream in = am.open(path)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int k; (k = in.read(buf)) > 0; ) o.write(buf, 0, k);
            bytes = o.toByteArray();
        }
        android.graphics.Typeface tf = android.graphics.Typeface.createFromAsset(am, path);
        if (tf == null) throw new java.io.IOException("글꼴 " + path);
        return androidInk(tf, size, cmap(bytes));
    }
}
