package com.dudu.pocketcore;

/**
 * 153 랭크 매치 VS 띠 — 경기 직전 게임 무대 인트로(두 캐릭터가 고른 무대에 마주 선 장면)의 맨 위 검은 띠(160×32 = 판 0 의 20칸 × 4줄,
 * 경기에선 체력·이름 HUD 가 서는 자리)에 내·상대 닉네임·티어·LP·VS 를 게임 타일로 얹는다(Emu.nativeHubVs → league.c vs_tick · vsband.c).
 * 글꼴 = 앱 픽셀 글꼴(assets/fonts/pixel.ttf = 갈무리11, 12px, 안티에일리어스 끔) + 우리가 그린 작은 숫자·별. 색 = 게임 HUD 팔레트
 * (인트로 내내 그대로인 판 0 팔레트 3·4·5·13·14 와 칠 3 이 검정인 2·9 — 실측 u153/vsgame). 칸(8×8)마다 팔레트가 하나라
 * 색이 다른 글자 덩어리는 칸 경계에서 바꾼다. 바탕은 칠 3(검정 — 게임 띠 타일 0x001 과 같은 불투명 검정).
 *
 * 배치: 줄 1(0~1줄) 왼쪽 = 나(인트로에서 늘 왼쪽, P1) 닉네임 [+ 검질], 오른쪽 = 상대 닉네임 — 각 76px 안.
 *       줄 2(2~3줄) 왼쪽 [티어 ★n][LP] · 가운데 VS 상자(9·10칸, HUD «1P»·«COM» 과 같은 빨강) · 오른쪽 [LP][티어 ★n].
 */
final class RankVs {
    static final int W = 160, H = 32, COLS = 20, ROWS = 4, MAXT = 80;
    static final int PAL_NAME = 13, PAL_VS = 5, PAL_STY = 4, PAL_LP = 3;
    /** 티어 → {팔레트, 글자 칠, 그림자 칠(3 = 없음)} — 수련생 회 · 낭인 연회 · 무사 주황 · 검객 흰 · 검호 연노랑 · 달인 연청 · 검성 빨강 · 무쌍 흰+빨강 */
    static final int[][] TIER_STYLE = { {9, 2, 3}, {9, 1, 2}, {3, 2, 3}, {13, 1, 2}, {2, 1, 2}, {4, 1, 2}, {5, 2, 3}, {5, 1, 2} };
    /** 대사 단계 몇 프레임째에 허브가 A 를 눌러 «자아 당당히» 배너로 넘길지 — 띠가 보이는 시간(인트로 처음 ~19프레임은 검은 화면) */
    static final int HOLD = 180;
    static final int FLAG_THRU_BANNER = 1;
    /** 기본 0 = «자아 당당히» 배너가 시작되는 프레임에 띠를 거둔다 — 그때 게임이 화면을 8프레임 비워서 안 보이게 사라진다.
     *  FLAG_THRU_BANNER 면 배너 동안에도 남지만, 게임이 배너 시작 12프레임쯤 띠 칸을 다시 쓰는 순간 런어헤드(2) 때문에 3프레임 꺼졌다 켜진다(실측) */
    static final int FLAGS = 0;

    /** s 를 12px 로 그린 잉크 [y][x] — y 0 = 글꼴 줄 맨 위(갈무리11 한글은 1줄부터 11줄) */
    /** 157: TileCanvas.Ink 와 같은 모양(넓힘) — RankVs 는 TileCanvas 위로 옮기되 출력은 바이트까지 같게(계약서 §5.4) */
    interface Ink extends TileCanvas.Ink { }
    static final class Side { String nick; String sty; int tier; String tierName; int stars; int lp = -1; }
    static final class Out { byte[] tiles; short[] map; int cells; }

    private final Ink ink;
    /** 157: 그림은 TileCanvas(20×4 칸, 바탕 칠 3) — 팔레트는 게임 팔레트 번호를 claimPal 로(먼저 매긴 것이 이김), 인코딩 encode(0, MAXT, 뒤집기 없음) */
    private final TileCanvas c = new TileCanvas(COLS, ROWS, 3);

    private RankVs(Ink ink) { this.ink = ink; }

    static Out render(Ink ink, Side me, Side opp) {
        RankVs b = new RankVs(ink);
        b.box(72, 17, 16, 14, PAL_VS);
        int vw = b.width("VS"); b.text("VS", 72 + (16 - vw) / 2, 17, PAL_VS, 1, 3, 0);
        String mn = b.fit(me.nick, NAME_W), on = b.fit(opp.nick, NAME_W);    /* 넘치면 «…» */
        int nw = b.text(mn, 2, 1, PAL_NAME, 1, 2, 0);
        if (me.sty != null && !me.sty.isEmpty()) {
            int sx = snap(2 + nw + 3), sw = b.width(me.sty);
            if (sx + sw <= 70) b.text(me.sty, sx, 1, PAL_STY, 2, 1, 0);         /* 검질 — HUD «수라» 처럼 파랑 글자 */
        }
        int ow = b.width(on); b.text(on, 157 - ow, 1, PAL_NAME, 1, 2, 0);
        int w = b.tier(me, 2);
        if (me.lp >= 0) { int lx = snap(2 + w + 2), lw = miniW(Integer.toString(me.lp)); if (lx + lw <= 70) b.mini(Integer.toString(me.lp), lx, 20, PAL_LP, 1, 2); }   /* 그림자까지 70 — VS 상자(72~) 왼쪽 1px 띄움(69 면 수련생 ★2~5 «100»~«499» 가 빠졌다) */
        int tw = b.tierW(opp), tx = 157 - tw;
        if (opp.lp >= 0) {
            String s = Integer.toString(opp.lp); int lw = miniW(s), lx = (tx / 8) * 8 - 2 - lw;
            if (lx >= 88) b.mini(s, lx, 20, PAL_LP, 1, 2);       /* VS 상자(72~87 · 가장자리는 검정) 바로 다음 칸부터 — 89 면 수련생 ★2~5 가 빠졌다 */
        }
        b.tier(opp, tx);
        return b.encode();
    }

    static int snap(int x) { return (x + 7) / 8 * 8; }
    static final int NAME_W = 77;                       /* 닉네임 한 쪽 폭(줄 1, 왼쪽 2~78 · 오른쪽 80~157) */
    /** 폭 안에 들게 — 넘치면 끝을 줄이고 «…»(픽셀 글꼴에 있음) */
    String fit(String s, int maxw) { return c.fit(s, maxw, ink); }

    /* ── 글자 ── */
    int width(String s) { return c.textW(s, ink); }
    /** 글자(x,y = 줄 맨 위) — inkC 글자 칠, shC 그림자 칠(+1,+1, 3 = 없음), maxw 넘으면 자름. 돌려줌: 폭 */
    int text(String s, int x, int y, int p, int inkC, int shC, int maxw) {
        if (s == null || s.isEmpty()) return 0;
        int w = c.textTop(s, x, y, inkC, ink, shC != 3 ? shC : -1, maxw) - x;
        mark(x, y, w + (shC != 3 ? 1 : 0), 14, p);
        return w;
    }
    int tier(Side d, int x) {
        int[] st = TIER_STYLE[Math.max(0, Math.min(7, d.tier))];
        int w = text(d.tierName, x, 17, st[0], st[1], st[2], 0);
        if (d.stars > 0) w += 2 + mini("*" + d.stars, x + w + 2, 20, st[0], st[1], st[2]);
        return w;
    }
    int tierW(Side d) { return width(d.tierName) + (d.stars > 0 ? 2 + miniW("*" + d.stars) : 0); }

    /* 작은 숫자 4×7 · 별 5×5 (우리 그림 — TileCanvas.mini4) */
    static int miniW(String s) { return TileCanvas.mini4W(s); }
    int mini(String s, int x, int y, int p, int inkC, int shC) {
        int w = c.mini4(s, x, y, inkC, shC != 3 ? shC : -1, TileCanvas.LEFT) - x;
        mark(x, y, w, 9, p);
        return w;
    }
    void box(int x, int y, int w, int h, int p) {
        c.box(x, y, w, h, 2, 3);
        mark(x, y, w, h, p);
    }
    /** 덮는 칸에 팔레트를 매긴다 — 다른 팔레트와 겹치면 나중 것을 버린다(배치 실수 — 시험이 잡는다) */
    void mark(int x, int y, int w, int h, int p) { c.claimPal(x, y, w, h, p); }

    /** NGPC 2bpp 타일(행마다 16비트 LE, 왼쪽 픽셀이 높은 비트) + 칸표(0xFFFF 게임 칸 그대로 · (팔레트<<9)|번호). 같은 그림은 한 타일 — MAXT 넘으면 null */
    Out encode() {
        TileCanvas.Encoded e;
        try { e = c.encode(0, MAXT, false, false); } catch (TileCanvas.Overflow x) { return null; }
        Out o = new Out(); o.tiles = e.tiles; o.map = e.map;
        for (short v : e.map) if (v != (short) 0xFFFF) o.cells++;
        return o;
    }

    /** 안드로이드 글꼴 잉크(픽셀 글꼴 12px, 안티에일리어스 끔) — 줄 맨 위 = 글꼴 ascent 위 */
    static Ink androidInk(final android.graphics.Typeface tf) {
        return new Ink() {
            final java.util.HashMap<String, boolean[][]> cache = new java.util.HashMap<>();
            @Override public boolean[][] mask(String s) {
                boolean[][] m = cache.get(s);
                if (m != null) return m;
                android.graphics.Paint p = new android.graphics.Paint();
                p.setTypeface(tf); p.setTextSize(12); p.setAntiAlias(false); p.setColor(0xffffffff);
                android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(256, 20, android.graphics.Bitmap.Config.ARGB_8888);
                new android.graphics.Canvas(bm).drawText(s, 0, -p.ascent(), p);
                int[] px = new int[256 * 20];
                bm.getPixels(px, 0, 256, 0, 0, 256, 20);
                m = new boolean[20][256];
                for (int y = 0; y < 20; y++) for (int x = 0; x < 256; x++) m[y][x] = (px[y * 256 + x] >>> 24) > 127;
                bm.recycle(); cache.put(s, m);
                return m;
            }
        };
    }

    /** 배치 경기 중인 나 — 티어 자리에 «배치 n/5»(수련생 색) · LP 없음 */
    static Side placement(Side s, int n) {
        s.tier = 0; s.tierName = "배치 " + Math.max(1, Math.min(5, n)) + "/5"; s.stars = 0; s.lp = -1;
        return s;
    }
    /** 랭크 상대·나 → 띠. me 닉네임은 League.myNick()(기록의 me.name — 지금은 «나») */
    static Side side(String nick, String sty, int lp, boolean master, int mr) {
        Side s = new Side(); s.nick = nick; s.sty = sty;
        s.tier = master ? 7 : RankCore.tierOf(lp); s.tierName = RankCore.TIER[s.tier];
        s.stars = master ? 0 : RankCore.starOf(lp); s.lp = master ? mr : lp;
        return s;
    }
}
