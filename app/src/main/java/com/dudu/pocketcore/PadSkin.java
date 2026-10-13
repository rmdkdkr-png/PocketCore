package com.dudu.pocketcore;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.File;
import java.util.HashMap;

/** 터치 패드 아트 — 「포켓 진열장」 런처와 같은 결의 기기 질감.
 *
 *  NGPC 실기를 닮은 «먹색 몸체 + 금속 테 + 유광 버튼» 을 코드로 그린다(그림 파일 없이).
 *  매번 그라디언트를 새로 만들면 터치마다 무거우니 «모양·크기·눌림» 별로 비트맵을 한 번 굽고
 *  onDraw 에서는 붙이기만 한다. 크기가 바뀌면(회전·배치 편집) 그 키로 새로 굽는다.
 *
 *  ★ 그림 덮어쓰기: PocketCore/design/skin/<이름>.png (눌림은 <이름>_on.png) 가 있으면
 *    코드 그림 대신 그 그림을 칸에 맞춰 붙인다. 이름 = dpad · a · b · sp · ab · wp · wk · tech ·
 *    opt · ff · exit · menu · bar. 업데이트 배달(design/)과 같은 자리라 나중에 릴리즈로도 보낼 수 있다.
 *    십자는 dpad_up/down/left/right_on.png 로 갈래별 눌림도 줄 수 있다(없으면 dpad_on 하나).
 *
 *  121: 테마(Theme)마다 다른 모양 — 기본 = 위 금속, 먹·금 = 옻칠 몸체·금테·붉은 A, 포켓 = 은색 스틱(누르면 손잡이가 기운다)·고무 버튼,
 *  모던 = 반투명 선 버튼(누르면 주황). 테마가 바뀌면(Theme.gen) 구운 그림을 버리고 새로 굽는다. 덮어쓰기 그림은 테마보다 먼저. */
final class PadSkin {

    /* 팔레트 — 런처 진열장(0xff0b0b0e 바탕·금빛 별 0xd9cba4)과 같은 계열 */
    static final int BODY_HI = 0xff3a3d48, BODY_LO = 0xff15161b;
    static final int RIM_HI  = 0xffb9bcc6, RIM_LO  = 0xff3c3f49;
    static final int GOLD    = 0xffd9cba4;
    static final int IDLE_ALPHA = 215, PRESS_ALPHA = 255;

    private final HashMap<String, Bitmap> cache = new HashMap<>();
    private final HashMap<String, Bitmap> user = new HashMap<>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    PadSkin() {
        tx.setTextAlign(Paint.Align.CENTER);
        tx.setFakeBoldText(true);
    }

    /** 지금 테마 — 바뀌었으면 구운 그림을 버린다. */
    private int gen = -1;
    private Theme theme() {
        Theme t = Theme.get();
        if (gen != Theme.gen) { cache.clear(); gen = Theme.gen; }
        return t;
    }

    /** 덮어쓰기 그림 다시 읽기 — 설정에서 돌아올 때 등. */
    void reloadUser() {
        user.clear();
        File dir = new File(MainActivity.root(), "design/skin");
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName().toLowerCase();
            if (!n.endsWith(".png")) continue;
            try {
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (b != null) user.put(n.substring(0, n.length() - 4), b);
            } catch (Throwable ignored) { }
        }
    }

    /** 구운 그림 버리기. recycle 은 하지 않는다 — 하드웨어 가속 화면은 방금 그린 목록이
     *  비트맵을 아직 물고 있을 수 있어 recycle 하면 「recycled bitmap」 충돌이 난다. GC 에 맡긴다. */
    void clear() { cache.clear(); }

    /** 배치 편집에서 크기를 계속 바꾸면 크기마다 굽는다 — 많이 쌓이면 한 번 비운다. */
    private void put(String k, Bitmap b) {
        if (cache.size() > 80) cache.clear();
        cache.put(k, b);
    }

    private boolean drawUser(Canvas c, String name, boolean on, RectF box, int alpha) {
        Bitmap b = on ? user.get(name + "_on") : null;
        if (b == null) b = user.get(name);
        if (b == null) return false;
        blit.setAlpha(alpha);
        c.drawBitmap(b, null, box, blit);
        return true;
    }

    /* ── 둥근 버튼 ───────────────────────────────────────────────── */

    /** 유광 원형 버튼. hue = 버튼 고유색(불투명), accent = 기술 버튼처럼 금테를 두를 것인가. */
    void button(Canvas c, String name, float cx, float cy, float rad, int hue, boolean on,
                boolean accent, String label) {
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        int alpha = on ? PRESS_ALPHA : IDLE_ALPHA;
        Theme th = theme();
        if (drawUser(c, name, on, r, alpha)) { labelOf(th, c, cx, cy, rad, label, on, true, hue); return; }
        int R = Math.max(4, Math.round(rad));
        String key = "btn" + R + ":" + Integer.toHexString(hue) + (on ? "+" : "-") + (accent ? "g" : "");
        Bitmap b = cache.get(key);
        if (b == null) {
            b = th.ink() ? bakeInkButton(R, hue, on, accent) : th.pocket() ? bakePocketButton(R, on, accent)
              : th.modern() ? bakeModernButton(R, on, accent) : bakeButton(R, hue, on, accent);
            put(key, b);
        }
        float pad = b.getWidth() / 2f;
        blit.setAlpha(th.modern() || th.ink() || th.pocket() ? (on ? PRESS_ALPHA : 235) : alpha);
        c.drawBitmap(b, cx - pad, cy - pad, blit);
        float dy = on ? R * (th.pocket() ? 0.03f : th.ink() ? 0.04f : th.modern() ? 0f : 0.05f) : 0;
        labelOf(th, c, cx, cy + dy, rad, label, on, false, hue);
    }

    /** 테마별 버튼 글자. */
    private void labelOf(Theme th, Canvas c, float cx, float cy, float rad, String s, boolean on, boolean user, int hue) {
        if (th.classic() || s == null || s.isEmpty()) { label(c, cx, cy, rad, s, on, user); return; }
        float size = rad * (s.length() >= 3 ? 0.44f : 0.56f);
        tx.setTextSize(size);
        float by = cy + size * 0.36f;
        if (th.ink()) {
            tx.setTypeface(Theme.font("ink_b")); tx.setFakeBoldText(false);
            int L = inkKind(hue);
            tx.setColor(0xa0000000);
            c.drawText(s, cx, by + Math.max(1f, rad * 0.04f), tx);
            tx.setColor(L == 1 ? 0xff2a1a08 : (L == 0 || L == 2) ? 0xfff6e2b0 : 0xffe9c46f);
            c.drawText(s, cx, by, tx);
        } else if (th.pocket()) {
            tx.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); tx.setFakeBoldText(true);
            tx.setColor(0xb0000000);                                   /* 새긴 글자 — 위에 어두운 홈, 아래 밝은 턱 */
            c.drawText(s, cx, by - Math.max(1f, rad * 0.03f), tx);
            tx.setColor(on ? 0xffaeb2ba : 0xffc9ccd3);
            c.drawText(s, cx, by, tx);
        } else {
            tx.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); tx.setFakeBoldText(false);
            tx.setColor(on ? 0xff2a1206 : 0xd8ffffff);
            c.drawText(s, cx, by, tx);
        }
        tx.setTypeface(android.graphics.Typeface.DEFAULT); tx.setFakeBoldText(true);
    }

    /* ── 먹·금(121) ─────────────────────────────────────────────── */

    /** 옻칠 종류 — 0 주칠(A·WP 청색 자리) · 1 금칠(SP·켜진 배속) · 2 녹칠(AB) · 3 흑칠(나머지). */
    private static int inkKind(int hue) {
        if (hue == 0xff3f6fd1) return 0;
        if (hue == 0xffe0a43a) return 1;
        if (hue == 0xff3f9e7c) return 2;
        return 3;
    }
    private static int[] inkLacquer(int kind, boolean on) {
        int[] L;
        switch (kind) {
            case 0:  L = new int[]{ 0xffe2533b, 0xffa72a1b, 0xff4a0d07 }; break;
            case 1:  L = new int[]{ 0xfff0cf7e, 0xffc0913e, 0xff5e3f14 }; break;
            case 2:  L = new int[]{ 0xff4f8f6a, 0xff1f4a36, 0xff08160f }; break;
            default: L = new int[]{ 0xff3c3732, 0xff16130f, 0xff050403 };
        }
        if (on) for (int i = 0; i < 3; i++) L[i] = mix(L[i], 0xff000000, 0.28f);
        return L;
    }
    private static final int[] GOLDS = { 0xfff6dc95, 0xffd4a548, 0xff8d6524 };

    private Bitmap bakeInkButton(int R, int hue, boolean on, boolean accent) {
        int pad = Math.round(R * 1.30f), S = pad * 2;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = pad, cy = pad, ring = R * 1.16f;
        p.reset(); p.setAntiAlias(true);
        /* 받침 — 먹색 원 + 옅은 금테 */
        p.setColor(0xf00b0907);
        c.drawCircle(cx, cy, ring, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1.2f, R * 0.028f));
        p.setShader(new LinearGradient(0, cy - ring, 0, cy + ring, 0x80f6dc95, 0x808d6524, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, ring - p.getStrokeWidth() / 2f, p);
        p.setShader(null); p.setStyle(Paint.Style.FILL);
        /* 그림자 */
        p.setColor(0xa0000000);
        p.setMaskFilter(new BlurMaskFilter(R * 0.10f, BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(cx, cy + R * (on ? 0.02f : 0.07f), R, p);
        p.setMaskFilter(null);
        /* 옻칠 몸체 */
        float oy = on ? R * 0.04f : 0f, body = on ? R * 0.97f : R;
        p.setShader(new RadialGradient(cx - R * 0.16f, cy - R * 0.36f + oy, R * 1.5f,
                inkLacquer(inkKind(hue), on), new float[]{ 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy + oy, body, p);
        /* 금테 */
        float sw = R * (accent ? 0.075f : 0.055f);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sw);
        p.setShader(new LinearGradient(0, cy - R, 0, cy + R, GOLDS, new float[]{ 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy + oy, body - sw / 2f, p);
        p.setShader(null); p.setStyle(Paint.Style.FILL);
        /* 윗면 광 */
        p.setColor(on ? 0x12ffffff : 0x29ffffff);
        r.set(cx - R * 0.67f, cy - R * 0.74f + oy, cx + R * 0.43f, cy - R * 0.22f + oy);
        c.drawOval(r, p);
        return b;
    }

    private Bitmap bakeInkDpad(int R) {
        int pad = Math.round(R * 1.28f), S = pad * 2;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = pad, cy = pad, a = R * 0.34f, rr = R * 0.12f, base = R * 1.2f;
        p.reset(); p.setAntiAlias(true);
        p.setShader(new RadialGradient(cx, cy - R * 0.1f, base, new int[]{ 0xf01e1a15, 0xf0090706 }, null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, base, p);
        p.setShader(new LinearGradient(0, cy - base, 0, cy + base, 0x73f6dc95, 0x738d6524, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1.2f, R * 0.018f));
        c.drawCircle(cx, cy, base - p.getStrokeWidth() / 2f, p);
        p.setShader(null);
        p.setColor(0x99000000); p.setStrokeWidth(R * 0.035f);
        c.drawCircle(cx, cy, R * 1.11f, p);
        p.setStyle(Paint.Style.FILL);
        /* 그림자 → 옻칠 십자 → 금테 */
        p.setColor(0x8c000000);
        p.setMaskFilter(new BlurMaskFilter(R * 0.06f, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(cross(cx, cy + R * 0.035f, R, a, rr), p);
        p.setMaskFilter(null);
        Path cr = cross(cx, cy, R, a, rr);
        p.setShader(new LinearGradient(0, cy - R, 0, cy + R, new int[]{ 0xff34302b, 0xff15120f, 0xff060504 },
                new float[]{ 0f, 0.45f, 1f }, Shader.TileMode.CLAMP));
        c.drawPath(cr, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1.5f, R * 0.03f));
        p.setShader(new LinearGradient(0, cy - R, 0, cy + R, GOLDS, new float[]{ 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
        c.drawPath(cr, p);
        p.setShader(null);
        p.setColor(0x12ffffff); p.setStrokeWidth(Math.max(1f, R * 0.014f));
        c.drawPath(cross(cx, cy, R * 0.93f, a * 0.8f, rr), p);
        p.setStyle(Paint.Style.FILL);
        /* 금 화살 */
        float d = R * 0.70f, tt = R * 0.12f;
        p.setColor(0xffd4a548);
        for (int k = 0; k < 4; k++) {
            float ax = k == 2 ? -1 : k == 3 ? 1 : 0, ay = k == 0 ? -1 : k == 1 ? 1 : 0;
            c.drawPath(arrow(cx + ax * d, cy + ay * d, ax, ay, tt), p);
        }
        /* 가운데 */
        p.setColor(0xff070605);
        c.drawCircle(cx, cy, R * 0.19f, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1f, R * 0.014f)); p.setColor(0x8cd4a548);
        c.drawCircle(cx, cy, R * 0.19f, p);
        p.setStyle(Paint.Style.FILL);
        return b;
    }

    /* ── 포켓(121) ──────────────────────────────────────────────── */

    private Bitmap bakePocketButton(int R, boolean on, boolean accent) {
        int pad = Math.round(R * 1.32f), S = pad * 2;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = pad, cy = pad, ring = R * 1.18f;
        p.reset(); p.setAntiAlias(true);
        /* 몸통에 파인 자리 — 은색 테 */
        p.setShader(new LinearGradient(0, cy - ring, 0, cy + ring, accent ? 0xfff1d27a : 0xffb7bcc4,
                accent ? 0xffb8902f : 0xff8f949d, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, ring, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1f, R * 0.025f)); p.setColor(0x8cffffff);
        c.drawCircle(cx, cy, ring - p.getStrokeWidth() / 2f, p);
        p.setStyle(Paint.Style.FILL);
        /* 그림자 */
        p.setColor(0x60000000);
        p.setMaskFilter(new BlurMaskFilter(R * 0.10f, BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(cx, cy + R * (on ? 0.03f : 0.09f), R, p);
        p.setMaskFilter(null);
        /* 고무 반구 */
        float oy = on ? R * 0.03f : 0f, body = on ? R * 0.96f : R;
        int[] dome = on ? new int[]{ 0xff575b63, 0xff2c2f35, 0xff141518 } : new int[]{ 0xff6b7079, 0xff383b42, 0xff1c1e22 };
        p.setShader(new RadialGradient(cx - R * 0.1f, cy - R * 0.32f + oy, R * 1.45f, dome,
                new float[]{ 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy + oy, body, p);
        p.setShader(null);
        p.setColor(on ? 0x14ffffff : 0x33ffffff);
        r.set(cx - R * 0.62f, cy - R * 0.67f + oy, cx + R * 0.38f, cy - R * 0.23f + oy);
        c.drawOval(r, p);
        return b;
    }

    /** 스틱 받침 — 은색 원판 + 여덟 갈래 홈. */
    private Bitmap bakeStickBase(int R) {
        int pad = Math.round(R * 1.22f), S = pad * 2;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = pad, cy = pad, ring = R * 1.08f;
        p.reset(); p.setAntiAlias(true);
        p.setColor(0x38000000);
        p.setMaskFilter(new BlurMaskFilter(R * 0.08f, BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(cx, cy + R * 0.05f, ring, p);
        p.setMaskFilter(null);
        p.setColor(0xff9ca1aa);
        c.drawCircle(cx, cy, ring, p);
        p.setShader(new RadialGradient(cx, cy - R * 0.1f, R * 1.25f, new int[]{ 0xffd9dce2, 0xffa7acb5, 0xff8d929c },
                new float[]{ 0f, 0.8f, 1f }, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, R, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1f, R * 0.02f)); p.setColor(0x99ffffff);
        c.drawCircle(cx, cy, R, p);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int k = 0; k < 8; k++) {
            double an = Math.toRadians(k * 45 - 90);
            float x1 = cx + R * 0.66f * (float) Math.cos(an), y1 = cy + R * 0.66f * (float) Math.sin(an);
            float x2 = cx + R * 0.86f * (float) Math.cos(an), y2 = cy + R * 0.86f * (float) Math.sin(an);
            p.setColor(k % 2 == 0 ? 0xe66e737c : 0x736e737c);
            p.setStrokeWidth(R * (k % 2 == 0 ? 0.04f : 0.02f));
            c.drawLine(x1, y1, x2, y2, p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xff6e737c);
        float d = R * 0.94f, tt = R * 0.06f;
        for (int k = 0; k < 4; k++) {
            float ax = k == 2 ? -1 : k == 3 ? 1 : 0, ay = k == 0 ? -1 : k == 1 ? 1 : 0;
            c.drawPath(arrow(cx + ax * d, cy + ay * d, ax, ay, tt), p);
        }
        return b;
    }

    /** 스틱 손잡이 — 짙은 고무 원 + 동심원 결. */
    private Bitmap bakeStickKnob(int R) {
        float k = R * 0.5f;
        int pad = Math.round(k * 1.5f), S = pad * 2;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = pad, cy = pad;
        p.reset(); p.setAntiAlias(true);
        p.setColor(0x73000000);
        p.setMaskFilter(new BlurMaskFilter(k * 0.18f, BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(cx, cy + k * 0.16f, k, p);
        p.setMaskFilter(null);
        p.setShader(new RadialGradient(cx - k * 0.16f, cy - k * 0.3f, k * 1.4f, new int[]{ 0xff5a5e67, 0xff2c2f35, 0xff16181c },
                new float[]{ 0f, 0.6f, 1f }, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, k, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1f, k * 0.03f)); p.setColor(0x59000000);
        for (int i = 1; i <= 3; i++) c.drawCircle(cx, cy, k * i / 4f, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x2effffff);
        r.set(cx - k * 0.46f, cy - k * 0.66f, cx + k * 0.26f, cy - k * 0.28f);
        c.drawOval(r, p);
        return b;
    }

    /* ── 모던(121) ──────────────────────────────────────────────── */

    private static final int MOD_ACC = 0xffff9147;

    private Bitmap bakeModernButton(int R, boolean on, boolean accent) {
        int pad = Math.round(R * 1.12f), S = pad * 2;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = pad, cy = pad;
        p.reset(); p.setAntiAlias(true);
        p.setColor(on ? (MOD_ACC & 0x00ffffff) | 0xd0000000 : 0x2a000000);
        c.drawCircle(cx, cy, R, p);
        p.setColor(on ? 0x00000000 : 0x14ffffff);
        c.drawCircle(cx, cy, R, p);
        float sw = Math.max(1.5f, R * 0.035f);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sw);
        p.setColor(on ? MOD_ACC : accent ? 0xe6ff9147 : 0x66ffffff);
        c.drawCircle(cx, cy, R - sw / 2f, p);
        p.setStyle(Paint.Style.FILL);
        return b;
    }

    private Bitmap bakeModernDpad(int R) {
        int pad = Math.round(R * 1.12f), S = pad * 2;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = pad, cy = pad;
        p.reset(); p.setAntiAlias(true);
        for (int k = 0; k < 4; k++) modernCell(c, cx, cy, R, k, false);
        return b;
    }
    /** 모던 십자 한 칸 — k: 0 위 1 아래 2 왼쪽 3 오른쪽. */
    private void modernCell(Canvas c, float cx, float cy, float R, int k, boolean on) {
        float a = R * 0.30f, d = R * 0.62f, rr = a * 0.46f;
        float ax = k == 2 ? -1 : k == 3 ? 1 : 0, ay = k == 0 ? -1 : k == 1 ? 1 : 0;
        float x = cx + ax * d, y = cy + ay * d;
        r.set(x - a, y - a, x + a, y + a);
        p.setStyle(Paint.Style.FILL);
        p.setColor(on ? (MOD_ACC & 0x00ffffff) | 0xd0000000 : 0x2a000000);
        c.drawRoundRect(r, rr, rr, p);
        if (!on) { p.setColor(0x14ffffff); c.drawRoundRect(r, rr, rr, p); }
        float sw = Math.max(1.4f, R * 0.024f);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sw);
        p.setColor(on ? MOD_ACC : 0x66ffffff);
        r.inset(sw / 2f, sw / 2f);
        c.drawRoundRect(r, rr, rr, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(on ? 0xff2a1206 : 0xc0ffffff);
        c.drawPath(arrow(x, y, ax, ay, a * 0.36f), p);
    }

    private Bitmap bakeButton(int R, int hue, boolean on, boolean accent) {
        int pad = Math.round(R * 1.25f), S = pad * 2;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = pad, cy = pad;
        p.reset(); p.setAntiAlias(true);

        /* 1) 그림자 — 바닥에 살짝 뜬 느낌. 눌리면 그림자가 줄어든다(가라앉음) */
        p.setColor(0x90000000);
        p.setMaskFilter(new BlurMaskFilter(R * (on ? 0.10f : 0.20f), BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(cx, cy + R * (on ? 0.04f : 0.12f), R * 1.0f, p);
        p.setMaskFilter(null);

        /* 2) 금속 테 — 위가 밝고 아래가 어두운 선형. 기술 버튼은 금테 */
        int rimHi = accent ? GOLD : RIM_HI, rimLo = accent ? 0xff6b5a32 : RIM_LO;
        p.setShader(new LinearGradient(0, cy - R, 0, cy + R, rimHi, rimLo, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, R, p);
        /* 테 안쪽 홈 */
        p.setShader(new LinearGradient(0, cy - R, 0, cy + R, 0xff0c0c10, 0xff2a2c33, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, R * 0.90f, p);

        /* 3) 몸체 — 왼쪽 위 광원의 방사형. 눌리면 전체가 어두워지고 광원이 아래로 내려간다 */
        float body = R * 0.82f, oy = on ? R * 0.05f : 0f;
        int lite = mix(hue, 0xffffffff, on ? 0.10f : 0.38f);
        int base = on ? mix(hue, 0xff000000, 0.25f) : hue;
        int dark = mix(hue, 0xff000000, on ? 0.65f : 0.55f);
        p.setShader(new RadialGradient(cx - body * 0.30f, cy - body * 0.35f + oy, body * 1.45f,
                new int[]{ lite, base, dark }, new float[]{ 0f, 0.45f, 1f }, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy + oy, body, p);

        /* 4) 하이라이트 — 위쪽 반달 유광. 눌림엔 약하게 */
        p.setShader(new LinearGradient(0, cy - body + oy, 0, cy + oy,
                on ? 0x30ffffff : 0x80ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
        r.set(cx - body * 0.72f, cy - body * 0.92f + oy, cx + body * 0.72f, cy + body * 0.10f + oy);
        c.drawOval(r, p);

        /* 5) 아래 가장자리 반사광 — 입체감 */
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, R * 0.04f));
        p.setColor(mix(hue, 0xffffffff, 0.5f) & 0x60ffffff);
        r.set(cx - body * 0.86f, cy - body * 0.86f + oy, cx + body * 0.86f, cy + body * 0.86f + oy);
        c.drawArc(r, 25, 130, false, p);
        p.setStyle(Paint.Style.FILL);

        if (on) {   /* 눌림 — 고유색 빛무리 */
            p.setShader(new RadialGradient(cx, cy, R * 1.22f,
                    new int[]{ 0x00000000, (hue & 0x00ffffff) | 0x00000000, (hue & 0x00ffffff) | 0x70000000, 0x00000000 },
                    new float[]{ 0f, 0.80f, 0.88f, 1f }, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, R * 1.22f, p);
            p.setShader(null);
        }
        return b;
    }

    /** 버튼 글자 — 각인처럼 아래에 어두운 그림자 한 겹. */
    private void label(Canvas c, float cx, float cy, float rad, String s, boolean on, boolean user) {
        if (s == null || s.isEmpty()) return;
        float size = rad * (s.length() >= 3 ? 0.44f : 0.56f);
        tx.setTextSize(size);
        float by = cy + size * 0.36f;
        tx.setColor(0xa0000000);
        c.drawText(s, cx, by + Math.max(1f, rad * 0.04f), tx);
        tx.setColor(on ? 0xffffffff : (user ? 0xf0ffffff : 0xe8f2ede0));
        c.drawText(s, cx, by, tx);
    }

    /* ── 십자키 ──────────────────────────────────────────────────── */

    /** dir 비트: 1=위 2=아래 4=왼쪽 8=오른쪽. 갈래별로 눌린 쪽이 가라앉고 금빛이 든다. */
    void dpad(Canvas c, float cx, float cy, float R, int dir) {
        r.set(cx - R, cy - R, cx + R, cy + R);
        boolean any = dir != 0;
        Theme th = theme();
        if (!user.isEmpty() && user.containsKey("dpad")) {
            drawUser(c, "dpad", false, r, any ? PRESS_ALPHA : IDLE_ALPHA);
            String[] k = { "up", "down", "left", "right" };
            boolean drewDir = false;
            for (int i = 0; i < 4; i++)
                if ((dir & (1 << i)) != 0 && user.containsKey("dpad_" + k[i] + "_on")) {
                    drawUser(c, "dpad_" + k[i], true, r, PRESS_ALPHA); drewDir = true;
                }
            if (any && !drewDir) drawUser(c, "dpad", true, r, PRESS_ALPHA);
            return;
        }
        int Ri = Math.max(8, Math.round(R));
        if (th.pocket()) { stick(c, cx, cy, Ri, dir); return; }
        String key = "dpad" + Ri;
        Bitmap b = cache.get(key);
        if (b == null) { b = th.ink() ? bakeInkDpad(Ri) : th.modern() ? bakeModernDpad(Ri) : bakeDpad(Ri); put(key, b); }
        float pad = b.getWidth() / 2f;
        blit.setAlpha(th.classic() ? (any ? PRESS_ALPHA : IDLE_ALPHA) : (any ? PRESS_ALPHA : 235));
        c.drawBitmap(b, cx - pad, cy - pad, blit);
        if (!any) return;
        if (th.modern()) { for (int k = 0; k < 4; k++) if ((dir & (1 << k)) != 0) modernCell(c, cx, cy, R, k, true); return; }
        if (th.ink()) { inkPress(c, cx, cy, R, dir); return; }
        dpadPress(c, cx, cy, R, dir);
    }

    /** 포켓 스틱 — 받침 위에 손잡이. 누른 쪽으로 손잡이가 기울고 그 갈래 홈에 노란 빛. */
    private void stick(Canvas c, float cx, float cy, int R, int dir) {
        String kb = "stick" + R, kk = "knob" + R;
        Bitmap base = cache.get(kb), knob = cache.get(kk);
        if (base == null) { base = bakeStickBase(R); put(kb, base); }
        if (knob == null) { knob = bakeStickKnob(R); put(kk, knob); }
        blit.setAlpha(dir != 0 ? PRESS_ALPHA : 240);
        c.drawBitmap(base, cx - base.getWidth() / 2f, cy - base.getHeight() / 2f, blit);
        float dx = ((dir & 8) != 0 ? 1 : 0) - ((dir & 4) != 0 ? 1 : 0), dy = ((dir & 2) != 0 ? 1 : 0) - ((dir & 1) != 0 ? 1 : 0);
        if (dx != 0 || dy != 0) {
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            dx /= len; dy /= len;
            float ang = (float) Math.toDegrees(Math.atan2(dy, dx));
            p.reset(); p.setAntiAlias(true);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(R * 0.07f); p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xd8ffcc4d);
            r.set(cx - R * 0.93f, cy - R * 0.93f, cx + R * 0.93f, cy + R * 0.93f);
            c.drawArc(r, ang - 22, 44, false, p);
            p.setStrokeCap(Paint.Cap.BUTT); p.setStyle(Paint.Style.FILL);
        }
        float off = R * 0.20f;
        blit.setAlpha(255);
        c.drawBitmap(knob, cx + dx * off - knob.getWidth() / 2f, cy + dy * off - knob.getHeight() / 2f, blit);
    }

    /** 먹·금 십자 눌림 — 그 팔을 어둡게 가라앉히고 끝에 금빛. */
    private void inkPress(Canvas c, float cx, float cy, float R, int dir) {
        float arm = R * 0.34f;
        p.reset(); p.setAntiAlias(true);
        for (int k = 0; k < 4; k++) {
            if ((dir & (1 << k)) == 0) continue;
            float ax = k == 2 ? -1 : k == 3 ? 1 : 0, ay = k == 0 ? -1 : k == 1 ? 1 : 0;
            float x0 = ax == 0 ? cx - arm : (ax < 0 ? cx - R : cx + arm);
            float x1 = ax == 0 ? cx + arm : (ax < 0 ? cx - arm : cx + R);
            float y0 = ay == 0 ? cy - arm : (ay < 0 ? cy - R : cy + arm);
            float y1 = ay == 0 ? cy + arm : (ay < 0 ? cy - arm : cy + R);
            r.set(x0, y0, x1, y1);
            p.setShader(null);
            p.setColor(0x66000000);
            c.drawRoundRect(r, R * 0.12f, R * 0.12f, p);
            float ex = cx + ax * R, ey = cy + ay * R;
            p.setShader(new LinearGradient(ex, ey, cx, cy, 0xc0f6dc95, 0x00d4a548, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, R * 0.12f, R * 0.12f, p);
        }
        p.setShader(null);
    }

    private Path cross(float cx, float cy, float R, float arm, float round) {
        Path path = new Path();
        RectF h = new RectF(cx - R, cy - arm, cx + R, cy + arm);
        RectF v = new RectF(cx - arm, cy - R, cx + arm, cy + R);
        path.addRoundRect(h, round, round, Path.Direction.CW);
        Path pv = new Path();
        pv.addRoundRect(v, round, round, Path.Direction.CW);
        path.op(pv, Path.Op.UNION);
        return path;
    }

    private Bitmap bakeDpad(int R) {
        int pad = Math.round(R * 1.18f), S = pad * 2;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = pad, cy = pad, arm = R * 0.40f;
        p.reset(); p.setAntiAlias(true);

        /* 받침 — 실기의 오목한 원형 자리 */
        p.setShader(new RadialGradient(cx, cy - R * 0.1f, R * 1.15f,
                new int[]{ 0x40000000, 0x70000000, 0x00000000 }, new float[]{ 0f, 0.86f, 1f },
                Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, R * 1.15f, p);
        p.setShader(new LinearGradient(0, cy - R, 0, cy + R, 0x30ffffff, 0x18000000, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1f, R * 0.025f));
        c.drawCircle(cx, cy, R * 1.08f, p);
        p.setStyle(Paint.Style.FILL);

        /* 그림자 */
        p.setShader(null);
        p.setColor(0xa0000000);
        p.setMaskFilter(new BlurMaskFilter(R * 0.09f, BlurMaskFilter.Blur.NORMAL));
        Path sh = cross(cx, cy + R * 0.07f, R * 0.98f, arm, R * 0.12f);
        c.drawPath(sh, p);
        p.setMaskFilter(null);

        /* 테 → 몸체 */
        Path outer = cross(cx, cy, R * 0.98f, arm, R * 0.12f);
        p.setShader(new LinearGradient(0, cy - R, 0, cy + R, RIM_HI, RIM_LO, Shader.TileMode.CLAMP));
        c.drawPath(outer, p);
        Path inner = cross(cx, cy, R * 0.93f, arm * 0.88f, R * 0.09f);
        p.setShader(new LinearGradient(cx - R, cy - R, cx + R, cy + R, BODY_HI, BODY_LO, Shader.TileMode.CLAMP));
        c.drawPath(inner, p);

        /* 윗면 유광 — 가로팔 위쪽 띠 */
        p.setShader(new LinearGradient(0, cy - R, 0, cy, 0x38ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
        c.drawPath(inner, p);

        /* 가운데 오목 */
        p.setShader(new RadialGradient(cx, cy + arm * 0.15f, arm * 0.62f,
                new int[]{ 0xff0b0b0e, 0xff23252c }, new float[]{ 0f, 1f }, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, arm * 0.55f, p);

        /* 갈래 화살 — 각인 */
        p.setShader(null);
        float t = arm * 0.42f, d = R * 0.70f;
        for (int k = 0; k < 4; k++) {
            float ax = k == 2 ? -1 : k == 3 ? 1 : 0, ay = k == 0 ? -1 : k == 1 ? 1 : 0;
            Path tri = arrow(cx + ax * d, cy + ay * d, ax, ay, t);
            p.setColor(0x90000000);
            c.save(); c.translate(0, Math.max(1f, R * 0.015f)); c.drawPath(tri, p); c.restore();
            p.setColor(0x80d9cba4);
            c.drawPath(tri, p);
        }
        return b;
    }

    private static Path arrow(float x, float y, float ax, float ay, float t) {
        Path a = new Path();
        /* 끝점 + 밑변 두 점 (ax,ay 방향을 가리킨다) */
        a.moveTo(x + ax * t, y + ay * t);
        a.lineTo(x - ax * t * 0.6f + ay * t, y - ay * t * 0.6f + ax * t);
        a.lineTo(x - ax * t * 0.6f - ay * t, y - ay * t * 0.6f - ax * t);
        a.close();
        return a;
    }

    /** 눌린 갈래 — 그 팔만 어둡게 가라앉히고 끝에 금빛. 대각은 두 팔이 같이 든다. */
    private void dpadPress(Canvas c, float cx, float cy, float R, int dir) {
        float arm = R * 0.40f * 0.88f;
        p.reset(); p.setAntiAlias(true);
        for (int k = 0; k < 4; k++) {
            if ((dir & (1 << k)) == 0) continue;
            float ax = k == 2 ? -1 : k == 3 ? 1 : 0, ay = k == 0 ? -1 : k == 1 ? 1 : 0;
            float x0 = ax == 0 ? cx - arm : (ax < 0 ? cx - R * 0.93f : cx + arm);
            float x1 = ax == 0 ? cx + arm : (ax < 0 ? cx - arm : cx + R * 0.93f);
            float y0 = ay == 0 ? cy - arm : (ay < 0 ? cy - R * 0.93f : cy + arm);
            float y1 = ay == 0 ? cy + arm : (ay < 0 ? cy - arm : cy + R * 0.93f);
            r.set(x0, y0, x1, y1);
            p.setShader(null);
            p.setColor(0x55000000);
            c.drawRoundRect(r, R * 0.08f, R * 0.08f, p);
            /* 끝 쪽이 밝은 금빛 → 가운데로 사라짐 */
            float ex = cx + ax * R * 0.93f, ey = cy + ay * R * 0.93f;
            p.setShader(new LinearGradient(ex, ey, cx, cy, 0xb0d9cba4, 0x00d9cba4, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, R * 0.08f, R * 0.08f, p);
        }
        p.setShader(null);
    }

    /* ── 알약(OPTION · 메뉴 · 상단바 칸) ─────────────────────────────── */

    /** 가로로 긴 알약. kind: 0=기기 버튼(OPTION) 1=메뉴 손잡이 2=상단바 칸. hl = 강조(선택·켜짐). */
    void pill(Canvas c, String name, RectF box, boolean on, boolean hl, int kind, String label) {
        int alpha = on || hl ? PRESS_ALPHA : (kind == 2 ? 235 : IDLE_ALPHA);
        Theme th = theme();
        labelTheme = th; labelKind = kind;
        if (drawUser(c, name, on || hl, box, alpha)) { pillLabel(c, box, label, on || hl); labelTheme = null; return; }
        int W = Math.max(4, Math.round(box.width())), H = Math.max(4, Math.round(box.height()));
        String key = "pill" + W + "x" + H + ":" + kind + (on ? "+" : "-") + (hl ? "h" : "");
        Bitmap b = cache.get(key);
        if (b == null) { b = th.classic() ? bakePill(W, H, on, hl, kind) : bakeThemePill(th, W, H, on, hl, kind); put(key, b); }
        float m = (b.getWidth() - W) / 2f;
        blit.setAlpha(th.classic() ? alpha : (on || hl ? PRESS_ALPHA : 240));
        c.drawBitmap(b, box.left - m, box.top - m, blit);
        pillLabel(c, box, label, on || hl);
        labelTheme = null;
    }
    private Theme labelTheme;     /* pillLabel 이 테마 글꼴·색을 고르게 — pill() 안에서만 */
    private int labelKind;

    /** 121: 먹·금(옻칠 + 금테) · 포켓(고무 알약 / 칸은 남색 액정) · 모던(반투명 + 가는 테, 강조는 주황) */
    private Bitmap bakeThemePill(Theme th, int W, int H, boolean on, boolean hl, int kind) {
        int m = Math.round(H * 0.35f);
        Bitmap b = Bitmap.createBitmap(W + m * 2, H + m * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        RectF o = new RectF(m, m, m + W, m + H);
        boolean lcd = th.pocket() && kind == 2;
        float rr = th.pocket() ? (lcd ? 0 : H / 2f) : th.ink() ? Math.min(H / 2f, H * 0.22f + 2) : H / 2f;
        p.reset(); p.setAntiAlias(true);
        p.setColor(0x70000000);
        p.setMaskFilter(new BlurMaskFilter(H * 0.14f, BlurMaskFilter.Blur.NORMAL));
        RectF s = new RectF(o); s.offset(0, on ? H * 0.03f : H * 0.08f);
        c.drawRoundRect(s, rr, rr, p);
        p.setMaskFilter(null);
        float sw = Math.max(1.5f, H * (th.pocket() ? 0.06f : 0.045f));
        if (th.ink()) {
            int top = hl ? 0xff3a2a10 : 0xff1b1611, bot = hl ? 0xff1d1408 : 0xff0c0a08;
            if (on) { int x = top; top = bot; bot = x; }
            p.setShader(new LinearGradient(0, o.top, 0, o.bottom, top, bot, Shader.TileMode.CLAMP));
            c.drawRoundRect(o, rr, rr, p);
            p.setShader(hl ? new LinearGradient(0, o.top, 0, o.bottom, GOLDS, new float[]{ 0f, 0.5f, 1f }, Shader.TileMode.CLAMP) : null);
            p.setColor(0x80d9a441);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sw);
            RectF in = new RectF(o); in.inset(sw / 2f, sw / 2f);
            c.drawRoundRect(in, rr, rr, p);
            p.setShader(null); p.setStyle(Paint.Style.FILL);
        } else if (lcd) {
            p.setShader(new LinearGradient(0, o.top, 0, o.bottom, hl ? 0xff2a3a1c : 0xff182436, hl ? 0xff151d0c : 0xff0c121c, Shader.TileMode.CLAMP));
            c.drawRect(o, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sw);
            p.setColor(hl ? 0xffffcc4d : 0xff8fa3c0);
            RectF in = new RectF(o); in.inset(sw / 2f, sw / 2f);
            c.drawRect(in, p);
            p.setStyle(Paint.Style.FILL);
        } else if (th.pocket()) {
            int top = hl ? 0xffd6b45a : 0xff8a8f99, bot = hl ? 0xff9c7a24 : 0xff5d626c;
            if (on) { int x = top; top = bot; bot = x; }
            p.setShader(new LinearGradient(0, o.top, 0, o.bottom, top, bot, Shader.TileMode.CLAMP));
            c.drawRoundRect(o, rr, rr, p);
            p.setShader(new LinearGradient(0, o.top, 0, o.centerY(), 0x55ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
            RectF g = new RectF(o.left + rr * 0.5f, o.top + sw * 0.5f, o.right - rr * 0.5f, o.centerY());
            c.drawRoundRect(g, rr * 0.6f, rr * 0.6f, p);
            p.setShader(null);
        } else {   /* 모던 */
            p.setColor(hl ? 0x38ff9147 : on ? 0x30ffffff : 0xd8141419);
            c.drawRoundRect(o, rr, rr, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sw);
            p.setColor(hl ? MOD_ACC : 0x26ffffff);
            RectF in = new RectF(o); in.inset(sw / 2f, sw / 2f);
            c.drawRoundRect(in, rr, rr, p);
            p.setStyle(Paint.Style.FILL);
        }
        return b;
    }

    private Bitmap bakePill(int W, int H, boolean on, boolean hl, int kind) {
        int m = Math.round(H * 0.35f);
        Bitmap b = Bitmap.createBitmap(W + m * 2, H + m * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float rr = H / 2f;
        RectF o = new RectF(m, m, m + W, m + H);
        p.reset(); p.setAntiAlias(true);
        /* 그림자 */
        p.setColor(0x80000000);
        p.setMaskFilter(new BlurMaskFilter(H * 0.18f, BlurMaskFilter.Blur.NORMAL));
        RectF s = new RectF(o); s.offset(0, on ? H * 0.03f : H * 0.10f);
        c.drawRoundRect(s, rr, rr, p);
        p.setMaskFilter(null);
        /* 테 */
        int rimHi = hl ? GOLD : RIM_HI, rimLo = hl ? 0xff6b5a32 : RIM_LO;
        p.setShader(new LinearGradient(0, o.top, 0, o.bottom, rimHi, rimLo, Shader.TileMode.CLAMP));
        c.drawRoundRect(o, rr, rr, p);
        /* 몸체 */
        RectF in = new RectF(o); float t = Math.max(1.5f, H * 0.09f); in.inset(t, t);
        float ir = in.height() / 2f;
        int hi = kind == 2 ? 0xff2c2e37 : BODY_HI, lo = kind == 2 ? 0xff121318 : BODY_LO;
        if (on) { int x = hi; hi = lo; lo = x; }                  /* 눌림 = 빛 방향이 뒤집힌 오목 */
        if (hl) { hi = mix(hi, GOLD, 0.25f); lo = mix(lo, GOLD, 0.12f); }
        p.setShader(new LinearGradient(0, in.top, 0, in.bottom, hi, lo, Shader.TileMode.CLAMP));
        c.drawRoundRect(in, ir, ir, p);
        if (!on) {   /* 윗면 유광 줄 */
            p.setShader(new LinearGradient(0, in.top, 0, in.centerY(), 0x40ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
            RectF g = new RectF(in.left + ir * 0.4f, in.top + t * 0.3f, in.right - ir * 0.4f, in.centerY());
            c.drawRoundRect(g, ir * 0.6f, ir * 0.6f, p);
        }
        return b;
    }

    private void pillLabel(Canvas c, RectF box, String s, boolean lit) {
        if (s == null || s.isEmpty()) return;
        Theme th = labelTheme;
        if (th != null && !th.classic()) { themePillLabel(th, c, box, s, lit); return; }
        int nl = s.indexOf('\n');
        if (nl >= 0) {                     /* 두 줄 — 위 이름(크게), 아래 상태(작고 흐리게): 「로드 / 3분 전」 */
            String a = s.substring(0, nl), b = s.substring(nl + 1);
            float sa = box.height() * 0.36f, sb = box.height() * 0.25f, lim = box.width() * 0.86f;
            tx.setTextSize(sa); float wa = tx.measureText(a); if (wa > lim) sa *= lim / wa;
            tx.setTextSize(sb); float wb = tx.measureText(b); if (wb > lim) sb *= lim / wb;
            float ya = box.centerY() - box.height() * 0.03f, yb = box.centerY() + box.height() * 0.30f;
            tx.setTextSize(sa);
            tx.setColor(0x90000000);
            c.drawText(a, box.centerX(), ya + Math.max(1f, sa * 0.07f), tx);
            tx.setColor(lit ? 0xfffff3d6 : 0xd8e8e4da);
            c.drawText(a, box.centerX(), ya, tx);
            tx.setTextSize(sb);
            tx.setColor(lit ? 0xd0fff3d6 : 0x9ad8d4ca);
            c.drawText(b, box.centerX(), yb, tx);
            return;
        }
        float size = box.height() * 0.48f;
        tx.setTextSize(size);
        /* 칸이 좁으면 글자를 줄인다 — 「보간:움직임」처럼 긴 라벨 */
        float tw = tx.measureText(s);
        if (tw > box.width() * 0.88f) { size *= box.width() * 0.88f / tw; tx.setTextSize(size); }
        float by = box.centerY() + size * 0.36f;
        tx.setColor(0x90000000);
        c.drawText(s, box.centerX(), by + Math.max(1f, size * 0.07f), tx);
        tx.setColor(lit ? 0xfffff3d6 : 0xd8e8e4da);
        c.drawText(s, box.centerX(), by, tx);
    }

    /** 121: 알약 글자 — 먹·금은 고운바탕(크림/금), 포켓 칸은 갈무리(흰/노랑)·버튼은 굵은 흰 글, 모던은 흰 글(강조는 주황 옅게). */
    private void themePillLabel(Theme th, Canvas c, RectF box, String s, boolean lit) {
        boolean lcd = th.pocket() && labelKind == 2;
        android.graphics.Typeface face = th.ink() ? Theme.font("ink_b") : lcd ? Theme.font("pixel_b") : android.graphics.Typeface.DEFAULT_BOLD;
        tx.setTypeface(face); tx.setFakeBoldText(false);
        int main, sub;
        if (th.ink()) { main = lit ? 0xfffff1d0 : 0xffecdcb8; sub = lit ? 0xd0f6dc95 : 0x9ad6c9a8; }
        else if (lcd) { main = lit ? 0xffffcc4d : 0xffe9eef7; sub = lit ? 0xd0ffcc4d : 0xa08fa3c0; }
        else if (th.pocket()) { main = 0xfff2f3f6; sub = 0xc0e4e6ea; }
        else { main = lit ? 0xffffd2b0 : 0xf2ffffff; sub = lit ? 0xd0ffd2b0 : 0x99ffffff; }
        int nl = s.indexOf('\n');
        if (nl >= 0) {
            String a = s.substring(0, nl), b = s.substring(nl + 1);
            float sa = box.height() * 0.36f, sb = box.height() * 0.25f, lim = box.width() * 0.86f;
            tx.setTextSize(sa); float wa = tx.measureText(a); if (wa > lim) sa *= lim / wa;
            tx.setTextSize(sb); float wb = tx.measureText(b); if (wb > lim) sb *= lim / wb;
            float ya = box.centerY() - box.height() * 0.03f, yb = box.centerY() + box.height() * 0.30f;
            tx.setTextSize(sa);
            if (!th.modern()) { tx.setColor(0x90000000); c.drawText(a, box.centerX(), ya + Math.max(1f, sa * 0.07f), tx); }
            tx.setColor(main);
            c.drawText(a, box.centerX(), ya, tx);
            tx.setTextSize(sb);
            tx.setColor(sub);
            c.drawText(b, box.centerX(), yb, tx);
        } else {
            float size = box.height() * 0.48f;
            tx.setTextSize(size);
            float tw = tx.measureText(s);
            if (tw > box.width() * 0.88f) { size *= box.width() * 0.88f / tw; tx.setTextSize(size); }
            float by = box.centerY() + size * 0.36f;
            if (!th.modern()) { tx.setColor(0x90000000); c.drawText(s, box.centerX(), by + Math.max(1f, size * 0.07f), tx); }
            tx.setColor(main);
            c.drawText(s, box.centerX(), by, tx);
        }
        tx.setTypeface(android.graphics.Typeface.DEFAULT); tx.setFakeBoldText(true);
    }

    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xff000000
             | (Math.round(ar + (br - ar) * t) << 16)
             | (Math.round(ag + (bg - ag) * t) << 8)
             |  Math.round(ab + (bb - ab) * t);
    }
}
