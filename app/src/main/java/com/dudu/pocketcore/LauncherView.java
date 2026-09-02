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
 * 원칙: **NGPC 해상도(160x152) 캔버스에 그리고 정수배로 확대**한다(최근접 —
 * 필터 없음). 로우파이가 목적이므로 안티에일리어싱도 끈다. 스팀 셀렉션풍:
 * 가운데 큰 타이틀 썸네일(실제 게임 화면 캡처) + 양옆 이전/다음 + 아래 정보줄
 * (한패·SP·더빙 배지). 썸네일은 Thumbs 가 롬당 1회 캡처해 캐시한 것.
 */
public final class LauncherView extends View {

    public static final class Item {
        public final File rom;
        public String title;          /* Games.ko 또는 파일명 */
        public String sub = "";       /* 연도·장르 (design.json) */
        public Bitmap thumb;          /* 없으면 이름 카드로 그린다 */
        public boolean pat, sp, dub;  /* 한패 · 원버튼 · 더빙 */
        public Item(File rom, String title) { this.rom = rom; this.title = title; }
    }

    public interface Listener {
        void onLaunch(File rom);
        void onSettings();   /* OPTION — 콘솔 관례대로 메뉴(설정) */
        void onUpdate();     /* B — 업데이트 확인 */
    }

    private static final int W = 160, H = 152;   /* NGPC 그대로 */
    private final Bitmap fb = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas fc = new Canvas(fb);
    private final Paint p = new Paint();          /* 필터·AA 전부 끔 — 픽셀 그대로 */
    private final Rect srcR = new Rect(), dstR = new Rect();

    private List<Item> items;
    private int sel = 0;
    private Listener listener;
    private float downX = -1;

    public LauncherView(Context c) {
        super(c);
        p.setAntiAlias(false);
        p.setFilterBitmap(false);
        setBackgroundColor(0xff000000);
    }

    public void setItems(List<Item> it) { items = it; sel = 0; invalidate(); }
    public void setListener(Listener l) { listener = l; }
    public Item selected() { return (items == null || items.isEmpty()) ? null : items.get(sel); }
    /** 백그라운드 캡처가 끝났을 때 — 그 항목이 화면에 있으면 다시 그린다. */
    public void thumbReady() { postInvalidate(); }

    /* ── 그리기 ─────────────────────────────────────────────────── */

    @Override protected void onDraw(Canvas cv) {
        drawFB();
        int s = Math.max(1, Math.min(getWidth() / W, (int) (getHeight() * 0.62f) / H));
        int dw = W * s, dh = H * s;
        int x0 = (getWidth() - dw) / 2, y0 = (int) (getHeight() * 0.03f);
        srcR.set(0, 0, W, H);
        dstR.set(x0, y0, x0 + dw, y0 + dh);
        cv.drawBitmap(fb, srcR, dstR, p);
        drawPad(cv);
    }

    /* ── 패드식 컨트롤 — 게임 패드(PadView)와 같은 룩·같은 자리 감각.
       십자(좌우만 유효)=고르기 · A=실행 · B=업데이트 · OPTION=설정 */
    private float dcx, dcy, dR, aX, aY, aR, bX, bY, bR;
    private final android.graphics.RectF optR = new android.graphics.RectF();

    private void drawPad(Canvas c) {
        float w = getWidth(), h = getHeight();
        dcx = w * 0.20f; dcy = h * 0.80f; dR = Math.min(w, h) * 0.13f;
        aX = w * 0.88f; aY = h * 0.76f; aR = Math.min(w, h) * 0.062f;
        bX = w * 0.72f; bY = h * 0.84f; bR = Math.min(w, h) * 0.055f;
        float arm = dR * 0.42f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x22ffffff);
        c.drawRect(dcx - arm, dcy - dR, dcx + arm, dcy - arm, p);   /* 위(비활성) */
        c.drawRect(dcx - arm, dcy + arm, dcx + arm, dcy + dR, p);   /* 아래(비활성) */
        p.setColor(0x3cffffff);
        c.drawRect(dcx - dR, dcy - arm, dcx - arm, dcy + arm, p);   /* ◀ */
        c.drawRect(dcx + arm, dcy - arm, dcx + dR, dcy + arm, p);   /* ▶ */
        p.setColor(0x22ffffff);
        c.drawRect(dcx - arm, dcy - arm, dcx + arm, dcy + arm, p);

        p.setColor(0x38ffffff);
        c.drawCircle(aX, aY, aR, p);
        c.drawCircle(bX, bY, bR, p);
        optR.set(w * 0.5f - w * 0.10f, h * 0.955f - h * 0.021f,
                 w * 0.5f + w * 0.10f, h * 0.955f + h * 0.021f);
        p.setColor(0x2affffff);
        c.drawRoundRect(optR, 12, 12, p);

        p.setColor(0xffdddddd);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(aR * 0.7f);
        c.drawText("A", aX, aY + aR * 0.25f, p);
        p.setTextSize(bR * 0.7f);
        c.drawText("B", bX, bY + bR * 0.25f, p);
        p.setTextSize(optR.height() * 0.5f);
        c.drawText("OPTION", optR.centerX(), optR.centerY() + optR.height() * 0.18f, p);
        p.setTextSize(aR * 0.42f);
        p.setColor(0x88ffffff);
        c.drawText("실행", aX, aY - aR - 6, p);
        c.drawText("업뎃", bX, bY + bR + bR * 0.55f + 8, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private static float dist(float x, float y, float cx2, float cy2) {
        float dx = x - cx2, dy = y - cy2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void drawFB() {
        fc.drawColor(0xff101014);
        int n = (items == null) ? 0 : items.size();
        text("POCKETCORE", 4, 10, 8, 0xffd9a441, false);
        if (n == 0) { text("PocketCore/roms 에 롬을 넣으세요", 8, 76, 8, 0xffbbbbbb, false); return; }
        text((sel + 1) + "/" + n, W - 4 - tw((sel + 1) + "/" + n, 8), 10, 8, 0xff888899, false);

        /* 캐러셀 — 양옆은 어둡고 작게, 가운데는 크고 테두리 */
        if (n > 1) thumbBox(items.get((sel + n - 1) % n), 2, 22, 42, 40, false);
        if (n > 1) thumbBox(items.get((sel + 1) % n),  W - 44, 22, 42, 40, false);
        Item cur = items.get(sel);
        thumbBox(cur, 47, 14, 66, 62, true);

        /* 정보줄 */
        String t = cur.title;
        if (tw(t, 10) > 152) t = cut(t, 152, 10);
        text(t, (W - tw(t, 10)) / 2, 88, 10, 0xffffffff, true);
        int bx = badgesX(cur);
        if (cur.pat) bx = badge("한패", bx, 94, 0xff2e7d32);
        if (cur.sp)  bx = badge("SP",   bx, 94, 0xffb26500);
        if (cur.dub) bx = badge("더빙", bx, 94, 0xff5e35b1);
        if (!cur.sub.isEmpty())
            text(cur.sub, (W - tw(cur.sub, 8)) / 2, 116, 8, 0xff9999aa, false);
        text("◀▶ 고르기 · A 실행 · B 업뎃 · OPT 설정",
             (W - tw("◀▶ 고르기 · A 실행 · B 업뎃 · OPT 설정", 8)) / 2, 146, 8,
             0xff777788, false);
    }

    private int badgesX(Item it) {
        int w = 0;
        if (it.pat) w += tw("한패", 8) + 6 + 3;
        if (it.sp)  w += tw("SP", 8) + 6 + 3;
        if (it.dub) w += tw("더빙", 8) + 6 + 3;
        return (W - Math.max(0, w - 3)) / 2;
    }

    private int badge(String s, int x, int yTop, int col) {
        int w = tw(s, 8) + 6;
        p.setStyle(Paint.Style.FILL);
        p.setColor(col);
        fc.drawRect(x, yTop, x + w, yTop + 12, p);
        text(s, x + 3, yTop + 10, 8, 0xffffffff, false);
        return x + w + 3;
    }

    /** 썸네일 칸 — 160x152 캡처를 칸에 맞춰(비율 유지) 그린다. 없으면 이름 카드. */
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
        } else {
            String s = cut(it.title, w - 4, 8);
            text(s, x + (w - tw(s, 8)) / 2, y + h / 2 + 3, 8,
                 focus ? 0xffcccccc : 0xff555566, false);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setColor(focus ? 0xffd9a441 : 0xff33333d);
        fc.drawRect(x, y, x + w - 1, y + h - 1, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void text(String s, float x, float y, int size, int col, boolean bold) {
        p.setColor(col);
        p.setTextSize(size);
        p.setFakeBoldText(bold);
        fc.drawText(s, x, y, p);
        p.setFakeBoldText(false);
    }
    private int tw(String s, int size) { p.setTextSize(size); return (int) p.measureText(s); }
    private String cut(String s, int maxPx, int size) {
        if (tw(s, size) <= maxPx) return s;
        while (s.length() > 1 && tw(s + "…", size) > maxPx) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    /* ── 입력 — 좌/우 1/3 탭 = 이전/다음, 가운데 탭 = 실행, 가로 스와이프 ── */

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (items == null || items.isEmpty()) return true;
        float x = e.getX(), y = e.getY(), h = getHeight();
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            /* 패드 컨트롤 — 게임과 같은 감각으로 누르는 즉시 반응 */
            if (dist(x, y, dcx, dcy) < dR * 1.35f) {
                if (x < dcx - dR * 0.2f) move(-1);
                else if (x > dcx + dR * 0.2f) move(1);
                downX = -1;
                return true;
            }
            if (dist(x, y, aX, aY) < aR * 1.35f) {
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
            if (downX < 0) return true;
            float dx = x - downX;
            if (Math.abs(dx) > getWidth() / 8f) {           /* 스와이프 */
                move(dx < 0 ? 1 : -1);
            } else if (y < h * 0.62f) {                     /* 캐러셀 영역 탭 */
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
        sel = (sel + d % n + n) % n;
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        invalidate();
    }
}
