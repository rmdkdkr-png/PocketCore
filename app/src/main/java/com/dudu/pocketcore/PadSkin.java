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
 *    십자는 dpad_up/down/left/right_on.png 로 갈래별 눌림도 줄 수 있다(없으면 dpad_on 하나). */
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
        if (drawUser(c, name, on, r, alpha)) { label(c, cx, cy, rad, label, on, true); return; }
        int R = Math.max(4, Math.round(rad));
        String key = "btn" + R + ":" + Integer.toHexString(hue) + (on ? "+" : "-") + (accent ? "g" : "");
        Bitmap b = cache.get(key);
        if (b == null) { b = bakeButton(R, hue, on, accent); put(key, b); }
        float pad = b.getWidth() / 2f;
        blit.setAlpha(alpha);
        c.drawBitmap(b, cx - pad, cy - pad, blit);
        label(c, cx, cy + (on ? R * 0.05f : 0), rad, label, on, false);
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
        String key = "dpad" + Ri;
        Bitmap b = cache.get(key);
        if (b == null) { b = bakeDpad(Ri); put(key, b); }
        float pad = b.getWidth() / 2f;
        blit.setAlpha(any ? PRESS_ALPHA : IDLE_ALPHA);
        c.drawBitmap(b, cx - pad, cy - pad, blit);
        if (any) dpadPress(c, cx, cy, R, dir);
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
        if (drawUser(c, name, on || hl, box, alpha)) { pillLabel(c, box, label, on || hl); return; }
        int W = Math.max(4, Math.round(box.width())), H = Math.max(4, Math.round(box.height()));
        String key = "pill" + W + "x" + H + ":" + kind + (on ? "+" : "-") + (hl ? "h" : "");
        Bitmap b = cache.get(key);
        if (b == null) { b = bakePill(W, H, on, hl, kind); put(key, b); }
        float m = (b.getWidth() - W) / 2f;
        blit.setAlpha(alpha);
        c.drawBitmap(b, box.left - m, box.top - m, blit);
        pillLabel(c, box, label, on || hl);
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

    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xff000000
             | (Math.round(ar + (br - ar) * t) << 16)
             | (Math.round(ag + (bg - ag) * t) << 8)
             |  Math.round(ab + (bb - ab) * t);
    }
}
