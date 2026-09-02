package com.dudu.pocketcore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.util.List;

/**
 * 레트로 런처 v2.
 *
 * v1 사고 둘의 수리(제보):
 *  · 「글자 크기 말같지도 않게」 — 크기 계산 실수(h 의 20%짜리 글자). 전부 제정신
 *    비율로 다시 (제목 3.0%, 배지 2.0%, 힌트 1.6%).
 *  · 「오버레이 해상도 원래 저 정도냐」 — 썸네일을 중간 캔버스(66px 칸)로 **줄였다가**
 *    다시 튀겨서 지글거렸다. 중간 캔버스를 없애고 원본(160x152)을 **정수배(3~4배)로
 *    직행** blit — 무필터라 픽셀은 그대로, 해상도는 원본 그대로.
 *
 * 부팅 연출: 스마일 볼(자작 도트)이 굴러 들어와 로고 자리에 안착하면 목록이 떠오른다 —
 * 실기 네오지오 포켓 부트 오마주. 부팅음(0.57초)과 박자를 맞췄다.
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
        void onSettings();
        void onUpdate();
    }

    private final Paint px = new Paint();   /* 픽셀 그림 — 필터·AA 끔 */
    private final Paint tp = new Paint();   /* 글자 — AA 켬 */
    private final Rect srcR = new Rect(), dstR = new Rect();

    private List<Item> items;
    private int sel = 0;
    private Listener listener;
    private float downX = -1;
    private long introAt = 0;               /* 부팅 연출 시작 시각 (0 = 안 함) */
    private static final long INTRO_MS = 950;

    public LauncherView(Context c) {
        super(c);
        px.setAntiAlias(false);
        px.setFilterBitmap(false);
        tp.setAntiAlias(true);
        setBackgroundColor(0xff0b0b0e);
    }

    public void setItems(List<Item> it) { items = it; sel = 0; invalidate(); }
    public void setListener(Listener l) { listener = l; }
    public void startIntro() { introAt = System.currentTimeMillis(); invalidate(); }
    public void thumbReady() { postInvalidate(); }

    /* ── 그리기 ─────────────────────────────────────────────────── */

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float t = 1f;
        if (introAt > 0) {
            t = (System.currentTimeMillis() - introAt) / (float) INTRO_MS;
            if (t >= 1f) { t = 1f; introAt = 0; }
            else postInvalidateOnAnimation();
        }

        /* 로고 — 좌상단. 인트로 중에는 스마일 볼이 굴러와 심볼 자리에 안착한다 */
        Bitmap logo = Logo.get(getContext());
        float lx = w * 0.06f, ly = h * 0.025f;
        int ls = Math.max(2, (int) (w / 160f));           /* 로고 배율 */
        float logoH = (logo != null ? logo.getHeight() : 12) * ls;
        if (logo != null && t >= 1f) {
            srcR.set(0, 0, logo.getWidth(), logo.getHeight());
            dstR.set((int) lx, (int) ly, (int) (lx + logo.getWidth() * ls), (int) (ly + logoH));
            cv.drawBitmap(logo, srcR, dstR, px);
        } else if (logo != null) {
            /* 워드마크는 심볼 자리(왼쪽 12px)를 비우고 먼저 — 볼이 거기로 들어간다 */
            int cut = 14 * ls;
            srcR.set(14, 0, logo.getWidth(), logo.getHeight());
            dstR.set((int) lx + cut, (int) ly,
                     (int) (lx + logo.getWidth() * ls), (int) (ly + logoH));
            int a = (int) (255 * Math.min(1f, t * 2f));
            px.setAlpha(a);
            cv.drawBitmap(logo, srcR, dstR, px);
            px.setAlpha(255);
        }
        if (introAt > 0) {                                 /* 스마일 볼 굴리기 */
            float r = logoH * 0.55f;
            float p2 = Math.min(1f, t / 0.75f);
            float e = 1 - (1 - p2) * (1 - p2);             /* ease-out */
            float bx = -r + (lx + r * 0.9f + r) * e + (w * 0.55f) * (1 - e) * 0f;
            float sx = -2 * r + (lx + 6 * ls + 2 * r) * e; /* 왼쪽 밖 → 심볼 자리 */
            float sy = ly + logoH * 0.5f
                     + (t > 0.75f ? 0 : (h * 0.015f) * (float) Math.abs(Math.sin(t * 18)));
            drawSmile(cv, sx, sy, r, t * 720f);
        }

        int n = (items == null) ? 0 : items.size();
        float listA = introAt > 0 ? Math.max(0f, (t - 0.55f) / 0.45f) : 1f;
        int la = (int) (255 * listA);

        /* ── 카드 — 원본을 정수배로 직행. 중앙 3~4배, 양옆은 그 절반 ── */
        float top = ly + logoH + h * 0.02f;
        float availH = h * 0.40f;
        int cs = Math.max(2, Math.min((int) (w * 0.52f) / 160, (int) availH / 152));
        int ss2 = Math.max(1, cs / 2);
        float cy2 = top + (availH - 152 * cs) / 2f + 152 * cs / 2f;   /* 중앙 카드 세로중심 */

        if (n == 0) {
            tp.setColor(0xffbbbbbb);
            tp.setTextSize(h * 0.022f);
            tp.setTextAlign(Paint.Align.CENTER);
            cv.drawText("PocketCore/roms 에 롬을 넣으세요", w / 2, top + availH / 2, tp);
        } else {
            if (n > 1) {
                drawCard(cv, items.get((sel + n - 1) % n), w * 0.13f, cy2, ss2, false, la);
                drawCard(cv, items.get((sel + 1) % n),      w * 0.87f, cy2, ss2, false, la);
            }
            drawCard(cv, items.get(sel), w * 0.5f, cy2, cs, true, la);

            /* ── 글자 — 제정신 크기(뷰 높이 비율) ── */
            Item cur = items.get(sel);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setColor(withA(0xffffffff, la));
            tp.setTextSize(h * 0.030f);
            tp.setFakeBoldText(true);
            float ty = top + availH + h * 0.045f;
            cv.drawText(cur.title, w / 2, ty, tp);
            tp.setFakeBoldText(false);

            /* 배지 */
            tp.setTextSize(h * 0.018f);
            String[] lb = new String[3];
            int[] bc = new int[3];
            int bn = 0;
            if (cur.pat) { lb[bn] = "한패"; bc[bn++] = 0xff2e7d32; }
            if (cur.sp)  { lb[bn] = "SP";   bc[bn++] = 0xffb26500; }
            if (cur.dub) { lb[bn] = "더빙"; bc[bn++] = 0xff5e35b1; }
            float padX = h * 0.010f, gap = h * 0.008f, bh = h * 0.028f;
            float bw = 0;
            for (int i = 0; i < bn; i++) bw += tp.measureText(lb[i]) + padX * 2 + (i > 0 ? gap : 0);
            float bx2 = (w - bw) / 2;
            float byTop = ty + h * 0.015f;
            for (int i = 0; i < bn; i++) {
                float tw2 = tp.measureText(lb[i]);
                tp.setColor(withA(bc[i], la));
                cv.drawRoundRect(new RectF(bx2, byTop, bx2 + tw2 + padX * 2, byTop + bh),
                        bh * 0.3f, bh * 0.3f, tp);
                tp.setColor(withA(0xffffffff, la));
                cv.drawText(lb[i], bx2 + padX + tw2 / 2, byTop + bh * 0.72f, tp);
                bx2 += tw2 + padX * 2 + gap;
            }
            if (!cur.sub.isEmpty()) {
                tp.setColor(withA(0xff9999aa, la));
                tp.setTextSize(h * 0.017f);
                cv.drawText(cur.sub, w / 2, byTop + bh + h * 0.028f, tp);
            }
            tp.setColor(withA(0xff777788, la));
            tp.setTextSize(h * 0.015f);
            cv.drawText((sel + 1) + " / " + n, w * 0.5f, top - h * 0.006f, tp);
        }
        drawPad(cv);
    }

    private static int withA(int col, int a) {
        return (col & 0x00ffffff) | (Math.min(255, a) << 24);
    }

    /** 스마일 볼 — 자작 도트 감성(원 + 눈 2 + 웃는 입), 굴러가는 회전. */
    private void drawSmile(Canvas c, float x, float y, float r, float rot) {
        c.save();
        c.rotate(rot, x, y);
        tp.setColor(0xffd9a441);
        c.drawCircle(x, y, r, tp);
        tp.setColor(0xff1a1a20);
        float er = r * 0.13f;
        c.drawCircle(x - r * 0.32f, y - r * 0.22f, er, tp);
        c.drawCircle(x + r * 0.32f, y - r * 0.22f, er, tp);
        RectF m = new RectF(x - r * 0.45f, y - r * 0.15f, x + r * 0.45f, y + r * 0.55f);
        tp.setStyle(Paint.Style.STROKE);
        tp.setStrokeWidth(r * 0.14f);
        c.drawArc(m, 20, 140, false, tp);
        tp.setStyle(Paint.Style.FILL);
        c.restore();
    }

    /** 카드 — 원본 비트맵을 정수배 blit. cx=가로중심, cy=세로중심. */
    private void drawCard(Canvas c, Item it, float cx, float cy, int scale,
                          boolean focus, int alpha) {
        int bw = 160, bh = 152;
        if (it.thumb != null) { bw = it.thumb.getWidth(); bh = it.thumb.getHeight(); }
        int dw = bw * scale, dh = bh * scale;
        int l = (int) (cx - dw / 2f), t2 = (int) (cy - dh / 2f);
        px.setStyle(Paint.Style.FILL);
        px.setColor(withA(focus ? 0xff000000 : 0xff0a0a0d, alpha));
        c.drawRect(l - 4, t2 - 4, l + dw + 4, t2 + dh + 4, px);
        if (it.thumb != null) {
            srcR.set(0, 0, bw, bh);
            dstR.set(l, t2, l + dw, t2 + dh);
            px.setAlpha(focus ? alpha : alpha * 120 / 255);
            c.drawBitmap(it.thumb, srcR, dstR, px);
            px.setAlpha(255);
        } else {
            tp.setColor(withA(focus ? 0xffcccccc : 0xff555566, alpha));
            tp.setTextSize(getHeight() * 0.018f);
            tp.setTextAlign(Paint.Align.CENTER);
            c.drawText(it.title, cx, cy, tp);
        }
        px.setStyle(Paint.Style.STROKE);
        px.setStrokeWidth(Math.max(2, scale));
        px.setColor(withA(focus ? 0xffd9a441 : 0xff33333d, alpha));
        c.drawRect(l - 4, t2 - 4, l + dw + 4, t2 + dh + 4, px);
        px.setStyle(Paint.Style.FILL);
        px.setStrokeWidth(1);
    }

    /* ── 패드식 컨트롤 — A(실행)=안쪽 아래 · B(업뎃)=바깥 위 ── */
    private float dcx, dcy, dR, aX, aY, aR, bX, bY, bR;
    private final RectF optR = new RectF();

    private void drawPad(Canvas c) {
        float w = getWidth(), h = getHeight();
        dcx = w * 0.20f; dcy = h * 0.80f; dR = Math.min(w, h) * 0.13f;
        aX = w * 0.72f; aY = h * 0.84f; aR = Math.min(w, h) * 0.062f;
        bX = w * 0.88f; bY = h * 0.76f; bR = Math.min(w, h) * 0.055f;
        float arm = dR * 0.42f;
        tp.setStyle(Paint.Style.FILL);
        tp.setColor(0x22ffffff);
        c.drawRect(dcx - arm, dcy - dR, dcx + arm, dcy - arm, tp);
        c.drawRect(dcx - arm, dcy + arm, dcx + arm, dcy + dR, tp);
        tp.setColor(0x3cffffff);
        c.drawRect(dcx - dR, dcy - arm, dcx - arm, dcy + arm, tp);
        c.drawRect(dcx + arm, dcy - arm, dcx + dR, dcy + arm, tp);
        tp.setColor(0x22ffffff);
        c.drawRect(dcx - arm, dcy - arm, dcx + arm, dcy + arm, tp);

        tp.setColor(0x38ffffff);
        c.drawCircle(aX, aY, aR, tp);
        c.drawCircle(bX, bY, bR, tp);
        optR.set(w * 0.5f - w * 0.10f, h * 0.955f - h * 0.021f,
                 w * 0.5f + w * 0.10f, h * 0.955f + h * 0.021f);
        tp.setColor(0x2affffff);
        c.drawRoundRect(optR, 12, 12, tp);

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
