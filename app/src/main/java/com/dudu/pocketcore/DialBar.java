package com.dudu.pocketcore;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** 가로 다이얼 바 — 다단 항목(끔·−2·−4·−6·−8 같은 것)을 구간으로 그려 탭하거나 가로로 드래그해 고른다.
 *  (유저 2026-09-05: 「다이얼은 가로 바로 드래그해서 선택하게 하던가」). 실행 전 선택 창과 설정 화면이 같이 쓴다. */
public class DialBar extends View {
    public interface OnPick { void onPick(int idx); }

    private final String[] names;
    private int idx;
    private final OnPick cb;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public DialBar(Context c, String[] names, int idx, OnPick cb) {
        super(c);
        this.names = names; this.idx = Math.max(0, Math.min(names.length - 1, idx)); this.cb = cb;
    }

    public void setIndex(int i) {
        i = Math.max(0, Math.min(names.length - 1, i));
        if (i != idx) { idx = i; invalidate(); }
    }

    private int seg(float x) {
        int n = names.length;
        int k = (int) (x / Math.max(1f, getWidth()) * n);
        return Math.max(0, Math.min(n - 1, k));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight(), n = names.length;
        Theme th = Theme.get();                       /* 121: 테마 색·글꼴 — 포켓은 모서리 없는 칸, 나머지는 둥근 알약 */
        float rad = th.pocket() ? 0f : h / 2f, sw = (float) w / n, in = th.pocket() ? 4 : 3;
        p.setStyle(Paint.Style.FILL); p.setColor(th.dialBg);
        r.set(0, 0, w, h); c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(th.pocket() ? 4 : 2); p.setColor(th.dialLine);
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL); p.setColor(th.classic() ? 0xffe0b050 : th.accent);
        r.set(idx * sw + in, in, (idx + 1) * sw - in, h - in); c.drawRoundRect(r, Math.max(0, rad - in), Math.max(0, rad - in), p);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(th.bold()); p.setFakeBoldText(!th.pocket());
        for (int i = 0; i < n; i++) {
            float ts = h * 0.42f; p.setTextSize(ts);
            while (p.measureText(names[i]) > sw - 8 && ts > 9) { ts -= 1; p.setTextSize(ts); }   /* 긴 이름은 줄여 맞춘다 */
            float ty = h / 2f - (p.descent() + p.ascent()) / 2f;
            p.setColor(i == idx ? (th.classic() ? 0xff1a1d26 : th.onAccent) : th.dialDim);
            c.drawText(names[i], (i + 0.5f) * sw, ty, p);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN && getParent() != null)
            getParent().requestDisallowInterceptTouchEvent(true);          /* 스크롤 안에서도 가로 드래그가 먹게 */
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
            int k = seg(e.getX());
            if (k != idx) {
                idx = k; invalidate();
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                if (cb != null) cb.onPick(k);
            }
            return true;
        }
        return super.onTouchEvent(e);
    }
}
