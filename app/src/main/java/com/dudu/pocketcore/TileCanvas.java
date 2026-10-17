package com.dudu.pocketcore;

/**
 * 157 TileCanvas — NGPC 규칙(8×8 칸 · 칸마다 4색 팔레트 하나 · 2bpp · 12비트 색)으로 그리는 화판. 덧판(ovplane)·VS 띠(RankVs)가 같이 쓴다.
 * 계약서 docs/157_contract.md §5. 담당: 그림 담당(render builder). 이 파일은 설계 단계의 껍데기 — 공개 모양이 계약, 몸통은 TODO.
 *
 * 좌표: 픽셀 (x, y) — 캔버스 왼위 0,0. 칸 (c, r) = 픽셀 8c..8c+7 × 8r..8r+7. 칠(ci) 0..3, 0 = 투명(덧판) / RankVs 는 바탕 칠 3.
 * 글자 y = 한글 몸통 맨 위 줄(목업 mock_final.py 와 같은 기준). 잉크의 마스크 줄 0 은 글꼴 줄 맨 위(ascent) — text() 가 bodyTop 만큼 올려 그린다.
 * 인코딩: 타일 = 16바이트(줄마다 16비트 LE, 왼쪽 픽셀이 높은 비트 — (ci & 3) << (14 - 2x)) · 맵 칸 = (팔레트<<9)|상대 번호 | 0x4000 V | 0x8000 H · 0xFFFF 빈 칸.
 * 줄 먼저(row-major)로 훑으며 처음 나온 순서대로 번호(RankVs 와 같은 순서). 넘치면 Overflow(검사되지 않는 예외).
 */
final class TileCanvas {
    /** 글꼴 잉크 — RankVs.Ink 가 이것을 넓힌다(람다 하나로 만들 수 있게 추상 메서드는 mask 하나) */
    interface Ink {
        /** s 를 그린 잉크 [y][x] — y 0 = 글꼴 줄 맨 위(ascent), 안티에일리어스 끔 · 길이는 구현이 정함(RankVs 안드로이드 잉크 20×256) */
        boolean[][] mask(String s);
        /** 한글 몸통 맨 위 줄 = mask("가") 의 첫 잉크 줄(갈무리11 12px = 1). 구현이 캐시해도 된다 */
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
    static final class Overflow extends RuntimeException { Overflow(String m) { super(m); } }
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

    /** 바탕 칠 0(투명) */
    TileCanvas(int cols, int rows) { this(cols, rows, 0); }
    /** 바탕 칠 fill(RankVs = 3 검정) */
    TileCanvas(int cols, int rows, int fill) { throw todo(); }

    int cols() { throw todo(); }
    int rows() { throw todo(); }
    int width() { throw todo(); }
    int height() { throw todo(); }

    /* ── 팔레트 ── */
    /** 세 색(12비트) 팔레트 칸 — 같은 세 색이면 같은 칸을 돌려줌 · 16 넘으면 Overflow */
    int pal(int c1, int c2, int c3) { throw todo(); }
    void setPal(int slot, int c1, int c2, int c3) { throw todo(); }
    /** 칸 범위(포함)에 팔레트 — 덮어씀(목업 setpal) */
    void cellPal(int c0, int r0, int c1, int r1, int slot) { throw todo(); }
    /** 픽셀 사각형이 닿는 칸에 팔레트 — 이미 매긴 칸은 그대로(먼저 매긴 것이 이김 — RankVs mark) */
    void claimPal(int x, int y, int w, int h, int slot) { throw todo(); }
    /** −1 = 없음 */
    int cellPalAt(int c, int r) { throw todo(); }

    /* ── 그리기(캔버스 밖은 자름) ── */
    void px(int x, int y, int ci) { throw todo(); }
    int get(int x, int y) { throw todo(); }
    /** (x0,y0)..(x1,y1) 포함 */
    void fill(int x0, int y0, int x1, int y1, int ci) { throw todo(); }
    /** RankVs.box 와 같음: 테두리 edgeCi, 안 fillCi */
    void box(int x, int y, int w, int h, int fillCi, int edgeCi) { throw todo(); }
    /** 글자 — y = 한글 몸통 맨 위. 돌려줌: 끝 x(왼쪽 맞춤 기준) */
    int text(String s, int x, int y, int ci, Ink ink) { return text(s, x, y, ci, ink, -1, LEFT, 0); }
    /** shadowCi ≥ 0 이면 (+1,+1)에 그림자 — 그 자리가 0 또는 3 일 때만(목업·RankVs 규칙) · align LEFT/RIGHT/CENTER(x 기준) · maxW > 0 이면 자름 */
    int text(String s, int x, int y, int ci, Ink ink, int shadowCi, int align, int maxW) { throw todo(); }
    int textW(String s, Ink ink) { throw todo(); }
    /** 폭 안에 들게 — 넘치면 끝을 줄이고 «…»(RankVs.fit 와 같음) */
    String fit(String s, int maxW, Ink ink) { throw todo(); }
    /** 잉크가 s 의 모든 글자를 가졌나(Ink.has) — 아니면 LgmPages 가 갈무리11 로 대신 */
    static boolean covers(Ink ink, String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); ) { int cp = s.codePointAt(i); if (!ink.has(cp)) return false; i += Character.charCount(cp); }
        return true;
    }
    /** 3×5 작은 숫자 — 0-9 / + - , (글자 사이 1px, 모르는 글자는 2px 띄움 — 목업 mini3) · y = 맨 위 줄. 돌려줌: 끝 x */
    int mini3(String s, int x, int y, int ci, int align) { throw todo(); }
    static int mini3W(String s) { throw todo(); }
    /** 4×7 작은 숫자 · '*' = 별 5×5(한 줄 아래) — RankVs 그림 그대로 · shadowCi ≥ 0 이면 (+1,+1) 그림자 */
    int mini4(String s, int x, int y, int ci, int shadowCi, int align) { throw todo(); }
    static int mini4W(String s) { throw todo(); }
    /** OvArt 그림 — 그림의 칠 1·2·3 을 ci1·ci2·ci3 으로(0 = 안 그림), hflip = 좌우 뒤집기 */
    void art(int id, int x, int y, int ci1, int ci2, int ci3, boolean hflip) { throw todo(); }

    /* ── 인코딩 ── */
    /** 캔버스 전체 — flipDedupe = H/V/HV 뒤집기로 같은 타일을 하나로(RankVs 는 false) · strict = 팔레트 없는 칸에 0 아닌(바탕 칠과 다른) 픽셀이면 Overflow ·
     *  maxTiles ≤ 511(덧판 한 묶음) · 팔레트 없는 칸 = 0xFFFF */
    Encoded encode(int tileBase, int maxTiles, boolean flipDedupe, boolean strict) { throw todo(); }
    /** 칸 사각형만(포함) — Encoded.col0/row0 = c0/r0 */
    Encoded encodeRect(int c0, int r0, int c1, int r1, int tileBase, int maxTiles, boolean flipDedupe, boolean strict) { throw todo(); }
    /** L 층용 — 줄을 위에서부터 묶어 묶음마다 고유 타일 ≤ maxPerBand(≤511, 묶음 안에서만 합침), tileBase 를 이어 붙임.
     *  합이 maxTotal(L 2048 − tileBase0) 넘으면 Overflow. 한 줄이 maxPerBand 를 넘으면 Overflow */
    Encoded[] encodeBands(int tileBase0, int maxPerBand, int maxTotal) { throw todo(); }

    /* ── 안드로이드 잉크 ── */
    /** 픽셀 글꼴 sizePx(안티에일리어스 끔) — 마스크 줄 0 = ascent 위(RankVs.androidInk 와 같은 그림) · has() = Paint.hasGlyph */
    static Ink androidInk(android.graphics.Typeface tf, int sizePx) { throw todo(); }
    /** assets/fonts/pixel.ttf 12 · pixel_b.ttf 12 · galmuri7.ttf 8 — 못 읽으면 null(부르는 쪽이 옛 카드로) */
    static Inks androidInks(android.content.res.AssetManager am) { throw todo(); }

    private static UnsupportedOperationException todo() { return new UnsupportedOperationException("157-A TileCanvas: 그림 담당이 채움"); }
}
