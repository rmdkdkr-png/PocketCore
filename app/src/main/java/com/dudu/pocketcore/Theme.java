package com.dudu.pocketcore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Random;

/** 테마(내부 121~) — 기본 · 먹·금 · 포켓 · 모던.
 *
 *  유저 2026-10-13: 「테마 등 한번 커스텀화하고 앱화 하는거 생각해봐줘」 → 시안 3장 → 「테마 다 되게」.
 *  그래서 지금 모습(기본)에 시안 셋을 더해 넷 중 고른다. 옵션 키 pocketcore_theme(classic·ink·pocket·modern).
 *  런처(LauncherView)·아래 줄(MainActivity)·설정(SettingsActivity)·실행 전 창(LaunchSheet)·다이얼(DialBar)·
 *  게임 중 터치 버튼(PadSkin)이 같이 따른다. 앱 아이콘은 따로(AppIcon, pocketcore_icon) — 바꾸면 홈 화면 아이콘이 빠질 수 있어서.
 *
 *  그릴 때마다 options.txt 를 읽지 않게 값을 들고 있고, refresh() 때만 다시 읽는다(화면이 다시 보일 때·설정에서 바꿀 때).
 *  글꼴(assets/fonts, 둘 다 OFL)·그림(assets/theme)은 처음 쓸 때 한 번 읽어 둔다. */
final class Theme {
    static final String KEY = "pocketcore_theme";
    static final String CLASSIC = "classic", INK = "ink", POCKET = "pocket", MODERN = "modern";
    static final String[] IDS = { CLASSIC, INK, POCKET, MODERN };
    static final String[] NAMES = { "기본", "먹·금", "포켓", "모던" };

    final String id;
    /* 화면(설정·실행 전 창·다이얼) 색 */
    final int bg, card, line, txt, dim, accent, onAccent, tag, focus, btn2, chg;
    final int dialBg, dialLine, dialDim;
    final int cardRadiusDp;

    private Theme(String id) {
        this.id = id;
        switch (id) {
            case INK:       /* 먹·금 — 옻칠 먹색, 금박, 붉은 낙관 */
                bg = 0xff0d0b09; card = 0xff17130f; line = 0xff3a2f1e; txt = 0xffefe4c9; dim = 0xff9b8f78;
                accent = 0xffe0b65d; onAccent = 0xff1a1208; tag = 0xffd98a70; focus = 0x33e0b65d; btn2 = 0xff241c14;
                chg = 0xffe46a4e;
                dialBg = 0xff15110d; dialLine = 0xff4a3b22; dialDim = 0xffa09478; cardRadiusDp = 4;
                break;
            case POCKET:    /* 포켓 — 은색 몸통 안 남색 액정, 노란 글 */
                bg = 0xff0f1622; card = 0xff142031; line = 0xff2a3a52; txt = 0xffe9eef7; dim = 0xff8fa3c0;
                accent = 0xffffcc4d; onAccent = 0xff0c121c; tag = 0xff4fd27a; focus = 0x33ffcc4d; btn2 = 0xff1c283a;
                chg = 0xffff9e6b;
                dialBg = 0xff0c121c; dialLine = 0xff4b5a72; dialDim = 0xff8fa3c0; cardRadiusDp = 0;
                break;
            case MODERN:    /* 모던 — 짙은 바탕, 둥근 카드, 주황 */
                bg = 0xff0b0b10; card = 0xff16161d; line = 0xff24242c; txt = 0xffffffff; dim = 0xff8d8d9b;
                accent = 0xffff9147; onAccent = 0xff2a1206; tag = 0xff8fb8ff; focus = 0x33ff9147; btn2 = 0xff22222b;
                chg = 0xffffb27d;
                dialBg = 0xff1c1c24; dialLine = 0xff2c2c36; dialDim = 0xff9a9aa8; cardRadiusDp = 22;
                break;
            default:        /* 기본 — 120 까지의 모습 그대로 */
                bg = 0xff101014; card = 0xff191b22; line = 0xff2a2f3b; txt = 0xffe6e8ee; dim = 0xff8b93a6;
                accent = 0xffd9a441; onAccent = 0xff101014; tag = 0xff7fb0e0; focus = 0x33d9a441; btn2 = 0xff23262f;
                chg = 0xffd9a441;
                dialBg = 0xff1a1d26; dialLine = 0xff2a2f3d; dialDim = 0xff9aa3b8; cardRadiusDp = 0;
        }
    }

    boolean classic() { return CLASSIC.equals(id); }
    boolean ink()     { return INK.equals(id); }
    boolean pocket()  { return POCKET.equals(id); }
    boolean modern()  { return MODERN.equals(id); }

    /** 제목·이름 글꼴 — 먹·금 = 고운바탕 Bold, 포켓 = 갈무리11 Bold, 모던·기본 = 시스템 굵게. */
    Typeface title() {
        if (ink()) return font("ink_b");
        if (pocket()) return font("pixel_b");
        return Typeface.DEFAULT_BOLD;
    }
    /** 본문(작은 글) 글꼴 — 포켓만 갈무리11, 나머지는 시스템. */
    Typeface body() {
        if (pocket()) return font("pixel");
        return Typeface.DEFAULT;
    }
    /** 강조 글(값·버튼 이름) — 포켓은 갈무리 Bold, 나머지는 시스템 굵게. */
    Typeface bold() {
        if (pocket()) return font("pixel_b");
        return Typeface.DEFAULT_BOLD;
    }
    String name() { return NAMES[indexOf(id)]; }

    /* ── 지금 테마 ─────────────────────────────────────────────── */

    private static Theme cur;
    /** 바뀔 때마다 1씩 — 화면들이 «내가 짤 때의 테마»와 견줘 다시 짤지 정한다. */
    static int gen = 0;

    static Theme get() {
        if (cur == null) refresh();
        return cur;
    }
    /** options.txt 에서 다시 읽는다. 바뀌었으면 true. */
    static boolean refresh() {
        String v = null;
        try { v = Settings.load().get(KEY); } catch (Throwable ignored) { }
        String id = valid(v) ? v : CLASSIC;
        if (cur != null && cur.id.equals(id)) return false;
        cur = new Theme(id);
        gen++;
        return true;
    }
    /** 고른 테마를 쓰고 바로 바꾼다. */
    static void set(String id) {
        if (!valid(id)) id = CLASSIC;
        Settings.put(KEY, id);
        if (cur == null || !cur.id.equals(id)) { cur = new Theme(id); gen++; }
    }
    static String next(String id) { return IDS[(indexOf(id) + 1) % IDS.length]; }
    static int indexOf(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }
    static boolean valid(String id) {
        if (id == null) return false;
        for (String s : IDS) if (s.equals(id)) return true;
        return false;
    }

    /* ── 글꼴·그림 ─────────────────────────────────────────────── */

    private static Context app;
    static void init(Context c) { if (app == null && c != null) app = c.getApplicationContext(); }

    private static final HashMap<String, Typeface> FONTS = new HashMap<>();
    static Typeface font(String name) {
        Typeface t = FONTS.get(name);
        if (t != null) return t;
        if (app != null) {
            try { t = Typeface.createFromAsset(app.getAssets(), "fonts/" + name + ".ttf"); }
            catch (Throwable e) { t = null; }
        }
        if (t == null) t = Typeface.DEFAULT_BOLD;
        FONTS.put(name, t);
        return t;
    }

    private static final HashMap<String, Bitmap> BMPS = new HashMap<>();
    /** assets/theme/<name>.png — 없으면 null(그 장식만 빠진다). */
    static Bitmap bmp(String name) {
        if (BMPS.containsKey(name)) return BMPS.get(name);
        Bitmap b = null;
        if (app != null) {
            try (InputStream in = app.getAssets().open("theme/" + name + ".png")) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inScaled = false;
                b = BitmapFactory.decodeStream(in, null, o);
            } catch (Throwable ignored) { }
        }
        BMPS.put(name, b);
        return b;
    }

    /** 잡음 무늬(종이 결·몸통 결) — size×size, 색 하나에 알파만 흔든다. 한 번 만들어 둔다. */
    static Bitmap noise(String key, int size, int rgb, int maxA, long seed) {
        String k = "noise:" + key;
        if (BMPS.containsKey(k)) return BMPS.get(k);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        int[] px = new int[size * size];
        Random r = new Random(seed);
        for (int i = 0; i < px.length; i++) px[i] = (r.nextInt(maxA + 1) << 24) | (rgb & 0xffffff);
        b.setPixels(px, 0, size, 0, 0, size, size);
        BMPS.put(k, b);
        return b;
    }
}
