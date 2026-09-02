package com.dudu.pocketcore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.util.List;

/**
 * 레트로 런처 — 게임 화면 창 하나 안에서 롬을 고른다.
 *
 * 그림 층과 글자 층을 가른다(제보: 「로어한데 해상도가 너무 구려」):
 *   · 그림(썸네일 카드·테두리)은 NGPC 해상도(160x152) 캔버스에 그리고 정수배 확대
 *     — 필터 없음, 픽셀 그대로. 로우파이는 여기서 나온다.
 *   · 글자(로고·제목·배지·힌트)는 **뷰 해상도**에 직접 그린다 — 한글 8px 를
 *     정수배로 키우면 뭉개져서 읽을 수가 없다. 무드는 그림이, 가독은 글자가.
 * 조작은 패드식 컨트롤(십자·A·B·OPTION) — 게임 패드와 같은 룩.
 * A(실행)가 안쪽 아래, B 가 바깥 위 — 실기 네오지오 포켓의 A/B 배치다.
 */
public final class LauncherView extends View {

    public static final class Item {
        public final File rom;
        public String title;
        public String sub = "";
        public Bitmap thumb;
        public boolean pat, sp, dub;
        public Item(File rom, String title) { this.rom = rom; this.title = title; }
    }

    public interface Listener {
        void onLaunch(File rom);
        void onSettings();   /* OPTION — 콘솔 관례대로 메뉴(설정) */
        void onUpdate();     /* B — 업데이트 확인 */
    }

    private static final int W = 160, H = 96;    /* 캔버스는 캐러셀만 담는다 */
    private final Bitmap fb = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas fc = new Canvas(fb);
    private final Paint p = new Paint();          /* 캔버스용 — 필터·AA 끔 */
    private final Paint tp = new Paint();         /* 글자용 — AA 켬, 뷰 해상도 */
    private final Rect srcR = new Rect(), dstR = new Rect();
    private final Rect blitR = new Rect();        /* 캔버스가 뷰에 놓인 자리 */

    private List<Item> items;
    private int sel = 0;
    private Listener listener;
    private float downX = -1;

    public LauncherView(Context c) {
        super(c);
        p.setAntiAlias(false);
        p.setFilterBitmap(false);
        tp.setAntiAlias(true);
        setBackgroundColor(0xff000000);
    }

    public void setItems(List<Item> it) { items = it; sel = 0; invalidate(); }
    public void setListener(Listener l) { listener = l; }
    public Item selected() { return (items == null || items.isEmpty()) ? null : items.get(sel); }
    public void thumbReady() { postInvalidate(); }

    /* ── 그리기 ─────────────────────────────────────────────────── */

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        drawFB();
        int s = Math.max(1, Math.min((int) (w / W), (int) (h * 0.42f) / H));
        int dw = W * s, dh = H * s;
        int x0 = (int) (w - dw) / 2, y0 = (int) (h * 0.075f);
        srcR.set(0, 0, W, H);
        blitR.set(x0, y0, x0 + dw, y0 + dh);
        cv.drawBitmap(fb, srcR, blitR, p);

        /* 글자 층 — 뷰 해상도로 선명하게 */
        float u = h * 0.001f;                     /* 크기 단위 */
        tp.setTextAlign(Paint.Align.LEFT);
        Bitmap logo = Logo.get(getContext());
        if (logo != null) {
            int ls = Math.max(2, s * 3 / 4);      /* 캔버스 배율에 얹혀 같이 커진다 */
            int lw = logo.getWidth() * ls, lh = logo.getHeight() * ls;
            srcR.set(0, 0, logo.getWidth(), logo.getHeight());
            dstR.set(x0, (int) (y0 - lh - 8 * u * 10), x0 + lw, (int) (y0 - 8 * u * 10));
            if (dstR.top < 0) dstR.offset(0, -dstR.top);
            cv.drawBitmap(logo, srcR, dstR, p);
        } else {
            tp.setColor(0xffd9a441);
            tp.setTextSize(22 * u * 10);
            tp.setFakeBoldText(true);
            cv.drawText("POCKETCORE", x0, y0 - 10 * u * 10, tp);
            tp.setFakeBoldText(false);
        }
        int n = (items == null) ? 0 : items.size();
        tp.setColor(0xff888899);
        tp.setTextSize(15 * u * 10);
        tp.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(n == 0 ? "" : (sel + 1) + " / " + n, x0 + dw, y0 - 10 * u * 10, tp);
        tp.setTextAlign(Paint.Align.CENTER);
        if (n == 0) {
            tp.setColor(0xffbbbbbb);
            tp.setTextSize(16 * u * 10);
            cv.drawText("PocketCore/roms 에 롬을 넣으세요", w / 2, y0 + dh / 2f, tp);
        } else {
            Item cur = items.get(sel);
            float ty = blitR.bottom + 26 * u * 10;
            tp.setColor(0xffffffff);
            tp.setTextSize(20 * u * 10);
            tp.setFakeBoldText(true);
            cv.drawText(cur.title, w / 2, ty, tp);
            tp.setFakeBoldText(false);
            /* 배지 */
            float by = ty + 22 * u * 10;
            tp.setTextSize(13 * u * 10);
            float bw = 0;
            String[] lb = new String[3];
            int[] bc = new int[3];
            int bn = 0;
            if (cur.pat) { lb[bn] = "한패"; bc[bn++] = 0xff2e7d32; }
            if (cur.sp)  { lb[bn] = "SP";   bc[bn++] = 0xffb26500; }
            if (cur.dub) { lb[bn] = "더빙"; bc[bn++] = 0xff5e35b1; }
            float pad2 = 10 * u * 10, gap = 6 * u * 10;
            for (int i = 0; i < bn; i++) bw += tp.measureText(lb[i]) + pad2 * 2 + (i > 0 ? gap : 0);
            float bx = (w - bw) / 2;
            for (int i = 0; i < bn; i++) {
                float tw2 = tp.measureText(lb[i]);
                tp.setColor(bc[i]);
                tp.setTextAlign(Paint.Align.LEFT);
                cv.drawRoundRect(bx, by - 13 * u * 10, bx + tw2 + pad2 * 2, by + 5 * u * 10,
                        6 * u * 10, 6 * u * 10, tp);
                tp.setColor(0xffffffff);
                cv.drawText(lb[i], bx + pad2, by, tp);
                bx += tw2 + pad2 * 2 + gap;
                tp.setTextAlign(Paint.Align.CENTER);
            }
            if (!cur.sub.isEmpty()) {
                tp.setColor(0xff9999aa);
                tp.setTextSize(14 * u * 10);
                cv.drawText(cur.sub, w / 2, by + 22 * u * 10, tp);
            }
        }
        tp.setTextAlign(Paint.Align.LEFT);
        drawPad(cv);
    }

    /* ── 패드식 컨트롤 — A(실행)=안쪽 아래 · B(업뎃)=바깥 위 (실기 배치) ── */
    private float dcx, dcy, dR, aX, aY, aR, bX, bY, bR;
    private final android.graphics.RectF optR = new android.graphics.RectF();

    private void drawPad(Canvas c) {
        float w = getWidth(), h = getHeight();
        dcx = w * 0.20f; dcy = h * 0.80f; dR = Math.min(w, h) * 0.13f;
        aX = w * 0.72f; aY = h * 0.84f; aR = Math.min(w, h) * 0.062f;   /* A 안쪽 아래 */
        bX = w * 0.88f; bY = h * 0.76f; bR = Math.min(w, h) * 0.055f;   /* B 바깥 위 */
        float arm = dR * 0.42f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x22ffffff);
        c.drawRect(dcx - arm, dcy - dR, dcx + arm, dcy - arm, p);
        c.drawRect(dcx - arm, dcy + arm, dcx + arm, dcy + dR, p);
        p.setColor(0x3cffffff);
        c.drawRect(dcx - dR, dcy - arm, dcx - arm, dcy + arm, p);
        c.drawRect(dcx + arm, dcy - arm, dcx + dR, dcy + arm, p);
        p.setColor(0x22ffffff);
        c.drawRect(dcx - arm, dcy - arm, dcx + arm, dcy + arm, p);

        p.setColor(0x38ffffff);
        c.drawCircle(aX, aY, aR, p);
        c.drawCircle(bX, bY, bR, p);
        optR.set(w * 0.5f - w * 0.10f, h * 0.955f - h * 0.021f,
                 w * 0.5f + w * 0.10f, h * 0.955f + h * 0.021f);
        p.setColor(0x2affffff);
        c.drawRoundRect(optR, 12, 12, p);

        tp.setColor(0xffdddddd);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(aR * 0.7f);
        c.drawText("A", aX, aY + aR * 0.25f, tp);
        tp.setTextSize(bR * 0.7f);
        c.drawText("B", bX, bY + bR * 0.25f, tp);
        tp.setTextSize(optR.height() * 0.5f);
        c.drawText("OPTION", optR.centerX(), optR.centerY() + optR.height() * 0.18f, tp);
        tp.setTextSize(aR * 0.42f);
        tp.setColor(0x88ffffff);
        c.drawText("실행", aX, aY + aR + aR * 0.55f, tp);
        c.drawText("업뎃", bX, bY - bR - bR * 0.35f, tp);
        tp.setTextAlign(Paint.Align.LEFT);
    }

    private static float dist(float x, float y, float cx2, float cy2) {
        float dx = x - cx2, dy = y - cy2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** 캔버스에는 그림만 — 캐러셀 카드 세 장. */
    private void drawFB() {
        fc.drawColor(0xff101014);
        int n = (items == null) ? 0 : items.size();
        if (n == 0) return;
        if (n > 1) thumbBox(items.get((sel + n - 1) % n), 2, 26, 42, 40, false);
        if (n > 1) thumbBox(items.get((sel + 1) % n),  W - 44, 26, 42, 40, false);
        thumbBox(items.get(sel), 47, 14, 66, 68, true);
    }

    private void thumbBox(Item it, int x, int y, int w, int h, boolean focus) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(focus ? 0xff000000 : 0xff0a0a0d);
        fc.drawRect(x, y, x + w, y + h, p);
        if (it.thumb != null) {
            int tw2 = it.thumb.getWidth(), th2 = it.thumb.getHeight();
            int dh2 = h, dw2 = tw2 * h / th2;
            if (dw2 > w) { dw2 = w; dh2 = th2 * w / tw2; }
            int dx = x + (w - dw2) / 2, dy = y + (h - dh2) / 2;
            srcR.set(0, 0, tw2, th2);
            dstR.set(dx, dy, dx + dw2, dy + dh2);
            int old = p.getAlpha();
            if (!focus) p.setAlpha(120);
            fc.drawBitmap(it.thumb, srcR, dstR, p);
            p.setAlpha(old);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setColor(focus ? 0xffd9a441 : 0xff33333d);
        fc.drawRect(x, y, x + w - 1, y + h - 1, p);
        p.setStyle(Paint.Style.FILL);
    }

    /* ── 입력 ───────────────────────────────────────────────────── */

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (items == null) return true;
        float x = e.getX(), y = e.getY(), h = getHeight();
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            if (dist(x, y, dcx, dcy) < dR * 1.35f) {
                if (x < dcx - dR * 0.2f) move(-1);
                else if (x > dcx + dR * 0.2f) move(1);
                downX = -1;
                return true;
            }
            if (!items.isEmpty() && dist(x, y, aX, aY) < aR * 1.35f) {
                if (listener != null) listener.onLaunch(items.get(sel).rom);
                downX = -1;
                return true;
            }
            if (dist(x, y, bX, bY) < bR * 1.35f) {
                if (listener != null) listener.onUpdate();
                downX = -1;
                return true;
            }
            if (optR.contains(x, y)) {
                if (listener != null) listener.onSettings();
                downX = -1;
                return true;
            }
            downX = x;
            return true;
        case MotionEvent.ACTION_UP:
            if (downX < 0 || items.isEmpty()) return true;
            float dx = x - downX;
            if (Math.abs(dx) > getWidth() / 8f) {
                move(dx < 0 ? 1 : -1);
            } else if (y < h * 0.55f) {
                float fx2 = x / getWidth();
                if (fx2 < 0.33f) move(-1);
                else if (fx2 > 0.67f) move(1);
                else if (listener != null) listener.onLaunch(items.get(sel).rom);
            }
            return true;
        }
        return true;
    }

    private void move(int d) {
        int n = items.size();
        if (n == 0) return;
        sel = (sel + d % n + n) % n;
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        invalidate();
    }
}
