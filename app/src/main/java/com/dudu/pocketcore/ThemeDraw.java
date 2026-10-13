package com.dudu.pocketcore;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/** 테마 바탕 그림(내부 121) — 설정 화면·실행 전 창·런처 아래 줄이 같이 쓴다. 런처 캔버스(LauncherView)도 일부를 불러 쓴다.
 *  전부 코드로 그린다(먹·금의 붓 원·산·낙관만 assets/theme 그림). */
final class ThemeDraw {

    /* ── 먹·금: 종이 결 + 위쪽 따뜻한 빛 ─────────────────────────── */

    /** 먹색 바탕 위에 종이 결(잡음 두 겹)과 위쪽 금빛 번짐. */
    static void inkPaper(Canvas c, float l, float t, float r, float b, Paint p) {
        float w = r - l, h = b - t;
        p.reset();
        p.setColor(0xff0d0b09);
        c.drawRect(l, t, r, b, p);
        p.setShader(new RadialGradient(l + w * 0.5f, t + h * 0.16f, Math.max(w, h) * 0.75f,
                new int[]{ 0x38785828, 0x14785828, 0x00000000 }, new float[]{ 0f, 0.45f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(l, t, r, b, p);
        Bitmap n = Theme.noise("paper", 128, 0xd9b880, 20, 7);
        BitmapShader s = new BitmapShader(n, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        p.setShader(s);
        c.drawRect(l, t, r, b, p);
        Bitmap f = Theme.noise("fiber", 64, 0xe6cc99, 14, 11);   /* 섬유 — 가로로 늘인 잡음 */
        BitmapShader fs = new BitmapShader(f, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Matrix m = new Matrix(); m.setScale(9f, 1.6f);
        fs.setLocalMatrix(m);
        p.setShader(fs);
        p.setFilterBitmap(true);
        c.drawRect(l, t, r, b, p);
        p.setShader(null);
        p.setFilterBitmap(false);
    }

    static final class InkPaper extends Drawable {
        private final Paint p = new Paint();
        @Override public void draw(Canvas c) { Rect b = getBounds(); inkPaper(c, b.left, b.top, b.right, b.bottom, p); }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /* ── 포켓: 은색 몸통 + 베젤 + 액정 ─────────────────────────── */

    /** 은색 몸통(결 포함). */
    static void pocketBody(Canvas c, float l, float t, float r, float b, Paint p) {
        p.reset(); p.setAntiAlias(true);
        p.setShader(new LinearGradient(l, t, l + (r - l) * 0.15f, b,
                new int[]{ 0xffeceef2, 0xffd3d6dc, 0xffb9bdc5 }, new float[]{ 0f, 0.48f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(l, t, r, b, p);
        Bitmap n = Theme.noise("grain", 96, 0x7a7a88, 24, 3);
        p.setShader(new BitmapShader(n, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        c.drawRect(l, t, r, b, p);
        p.setShader(null);
        p.setColor(0xccffffff);                                   /* 위 가장자리 빛 */
        c.drawRect(l, t, r, t + 2, p);
    }

    /** 베젤(짙은 판) — 오른쪽 아래만 크게 둥글린 실기 모양. */
    static Path bezelPath(RectF z, float rad, float big) {
        Path path = new Path();
        path.addRoundRect(z, new float[]{ rad, rad, rad, rad, big, big, rad, rad }, Path.Direction.CW);
        return path;
    }
    static void pocketBezel(Canvas c, RectF z, float rad, float big, Paint p) {
        p.reset(); p.setAntiAlias(true);
        Path bz = bezelPath(z, rad, big);
        p.setColor(0x80ffffff);                                   /* 아래쪽 흰 반사 — 몸통에 박힌 느낌 */
        c.save(); c.translate(0, 2); c.drawPath(bz, p); c.restore();
        p.setShader(new LinearGradient(0, z.top, 0, z.bottom, 0xff3a3d45, 0xff25272d, Shader.TileMode.CLAMP));
        c.drawPath(bz, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(0x1effffff);
        c.drawPath(bz, p);
        p.setStyle(Paint.Style.FILL);
    }
    /** 액정 바탕(남색 방사). 내용은 위에 그리고, 끝에 lcdGlass 로 주사선·반사. */
    static void lcdBase(Canvas c, RectF z, Paint p) {
        p.reset(); p.setAntiAlias(true);
        p.setShader(new RadialGradient(z.centerX(), z.top + z.height() * 0.4f, Math.max(z.width(), z.height()) * 0.75f,
                new int[]{ 0xff182436, 0xff0c121c }, new float[]{ 0f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(z, p);
        p.setShader(null);
    }
    static void lcdGlass(Canvas c, RectF z, Paint p, float dp) {
        p.reset();
        c.save();
        c.clipRect(z);
        p.setColor(0x09ffffff);                                   /* 주사선 */
        float step = Math.max(3f, dp * 1.15f);
        for (float y = z.top; y < z.bottom; y += step) c.drawRect(z.left, y, z.right, y + Math.max(1f, step / 3f), p);
        p.setAntiAlias(true);                                     /* 왼쪽 위 유리 반사 */
        p.setShader(new LinearGradient(z.left, z.top, z.left + z.width() * 0.55f, z.top + z.height() * 0.6f,
                0x12ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
        Path g = new Path();
        g.moveTo(z.left, z.top); g.lineTo(z.left + z.width() * 0.62f, z.top);
        g.lineTo(z.left, z.top + z.height() * 0.70f); g.close();
        c.drawPath(g, p);
        p.setShader(null);
        c.restore();
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, dp)); p.setColor(0xff0a0d13);   /* 액정 테 */
        c.drawRect(z, p);
        p.setStyle(Paint.Style.FILL);
    }
    /** 베젤 아래 줄 — 초록 POWER 불, 비스듬한 LEGACITO 실크. */
    static void pocketSilk(Canvas c, RectF bezel, RectF lcd, Paint p, float dp) {
        float y = (lcd.bottom + bezel.bottom) / 2f;
        p.reset(); p.setAntiAlias(true);
        float lx = bezel.left + Math.max(dp * 14, bezel.width() * 0.04f);
        p.setColor(0x5563ff86);
        c.drawCircle(lx, y, dp * 6, p);
        p.setColor(0xff63ff86);
        c.drawCircle(lx, y, dp * 3.2f, p);
        p.setColor(0xff9aa1ad);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setTextSize(dp * 8.5f);
        p.setLetterSpacing(0.18f);
        c.drawText("POWER", lx + dp * 9, y + dp * 3, p);
        p.setColor(0xffc3c8d3);
        p.setTextSize(Math.min(dp * 15, bezel.width() * 0.045f));
        p.setLetterSpacing(0.32f);
        p.setTextSkewX(-0.22f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("LEGACITO", bezel.centerX() + bezel.width() * 0.16f, y + p.getTextSize() * 0.36f, p);
        p.setTextSkewX(0); p.setLetterSpacing(0); p.setTextAlign(Paint.Align.LEFT);
    }

    /** 설정 화면 바탕 — 몸통·베젤·액정을 한 장에. 액정 자리는 lcdInset(dp) 안쪽. */
    static final class PocketScreen extends Drawable {
        private final Paint p = new Paint();
        private final float dp;
        PocketScreen(float dp) { this.dp = dp; }
        static final int BEZEL = 8, SIDE = 22, BOTTOM = 44;      /* dp — SettingsActivity 가 같은 값으로 목록 자리를 잡는다 */
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            pocketBody(c, b.left, b.top, b.right, b.bottom, p);
            RectF bz = new RectF(b.left + BEZEL * dp, b.top + BEZEL * dp, b.right - BEZEL * dp, b.bottom - BEZEL * dp);
            pocketBezel(c, bz, 18 * dp, 52 * dp, p);
            RectF lcd = new RectF(b.left + SIDE * dp, b.top + SIDE * dp, b.right - SIDE * dp, b.bottom - BOTTOM * dp);
            lcdBase(c, lcd, p);
            pocketSilk(c, bz, lcd, p, dp);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /** 액정 위 덧칠(주사선·반사) — ScrollView 앞(foreground)에. */
    static final class LcdGlass extends Drawable {
        private final Paint p = new Paint();
        private final float dp;
        LcdGlass(float dp) { this.dp = dp; }
        @Override public void draw(Canvas c) { lcdGlass(c, new RectF(getBounds()), p, dp); }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /* ── 공통: 카드·버튼 판 ─────────────────────────────────────── */

    /** 설정 카드 판. */
    static Drawable card(Theme th, float dp) {
        GradientDrawable g = new GradientDrawable();
        if (th.ink()) {
            g.setColors(new int[]{ 0xd01b1611, 0xd00e0b09 });
            g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            g.setCornerRadius(th.cardRadiusDp * dp);
            g.setStroke(Math.max(1, Math.round(dp)), 0x40d9a441);
        } else if (th.pocket()) {
            g.setColor(0x00000000);
            g.setStroke(Math.max(2, Math.round(dp * 1.6f)), 0xff2a3a52);
        } else if (th.modern()) {
            g.setColor(th.card);
            g.setCornerRadius(th.cardRadiusDp * dp);
        } else {
            g.setColor(th.card);
        }
        return g;
    }

    /** 런처 아래 줄·창 버튼 판. hl = 금색(강조). */
    static Drawable button(Theme th, float dp, boolean hl) {
        GradientDrawable g = new GradientDrawable();
        if (th.ink()) {
            g.setColors(hl ? new int[]{ 0xffe9c46f, 0xffb3893a } : new int[]{ 0xff1b1611, 0xff0c0a08 });
            g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            g.setCornerRadius(6 * dp);
            g.setStroke(Math.max(1, Math.round(dp)), hl ? 0xfff6dc95 : 0x61d9a441);
        } else if (th.pocket()) {
            g.setColor(hl ? th.accent : 0xff1c283a);
            g.setStroke(Math.max(2, Math.round(dp * 2)), hl ? 0xff0c121c : 0xff8fa3c0);
        } else if (th.modern()) {
            g.setColor(hl ? th.accent : 0x14ffffff);
            g.setCornerRadius(999 * dp);
            if (!hl) g.setStroke(Math.max(1, Math.round(dp)), 0x22ffffff);
        } else {
            g.setColor(hl ? th.accent : 0xff191b22);
            g.setCornerRadius(14 * dp);
            if (!hl) g.setStroke(Math.max(1, Math.round(dp)), 0xff4a3d22);
        }
        return g;
    }

    private ThemeDraw() { }
}
