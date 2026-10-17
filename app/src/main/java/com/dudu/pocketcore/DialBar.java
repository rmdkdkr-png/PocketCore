package com.dudu.pocketcore;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

/** 가로 다이얼 바 — 다단 항목(끔·−2·−4·−6·−8 같은 것)을 구간으로 그려 탭하거나 가로로 드래그해 고른다.
 *  (유저 2026-09-05: 「다이얼은 가로 바로 드래그해서 선택하게 하던가」). 실행 전 선택 창과 설정 화면이 같이 쓴다.
 *
 *  135(접근성 검수): 예전엔 닿는 순간(DOWN) 값이 바뀌고 부모 스크롤을 막아, 엄지로 목록을 내리다 바 위에서 시작하면
 *  설정이 바뀌고(테마는 화면을 통째로 다시 짬, 앱 아이콘은 홈 화면 아이콘이 빠질 수 있음) 목록은 안 움직였다.
 *  → 가로로 움직임 문턱(touchSlop)을 넘을 때만 바가 손가락을 잡고, 값은 손을 뗄 때 정한다. 세로로 움직이면 스크롤에 넘긴다(CANCEL = 원래 값).
 *  패드·키: 좌우로 한 칸씩(step). 톡백: 값 읽기 + 앞뒤로 넘기기. */
public class DialBar extends View {
    public interface OnPick { void onPick(int idx); }

    private final String[] names;
    private int idx;
    private final OnPick cb;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int slop;
    private String label = null;              /* 톡백이 읽을 항목 이름 */

    public DialBar(Context c, String[] names, int idx, OnPick cb) {
        super(c);
        this.names = names; this.idx = Math.max(0, Math.min(names.length - 1, idx)); this.cb = cb;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setIndex(int i) {
        i = Math.max(0, Math.min(names.length - 1, i));
        if (i != idx) { idx = i; invalidate(); }
    }
    public int index() { return idx; }
    public void setLabel(String s) { label = s; setContentDescription(s); }

    /** 한 칸 옮겨 바로 정한다(패드 좌우·톡백·줄 누름) — 옮겼으면 true */
    public boolean step(int d) {
        int k = Math.max(0, Math.min(names.length - 1, idx + d));
        if (k == idx) return false;
        idx = k; invalidate();
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        if (cb != null) cb.onPick(k);
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED);
        return true;
    }

    private int seg(float x) {
        int n = names.length;
        int k = (int) (x / Math.max(1f, getWidth()) * n);
        return Math.max(0, Math.min(n - 1, k));
    }

    private float sp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics()); }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight(), n = names.length;
        Theme th = Theme.get();                       /* 121: 테마 색·글꼴 — 포켓은 모서리 없는 칸, 나머지는 둥근 알약 */
        float rad = th.pocket() ? 0f : h / 2f, sw = (float) w / n, in = th.pocket() ? 4 : 3;
        p.setStyle(Paint.Style.FILL); p.setColor(th.dialBg);
        r.set(0, 0, w, h); c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(th.pocket() ? 4 : 2); p.setColor(th.dialLine);
        c.drawRoundRect(r, rad, rad, p);
        if (isFocused()) {                            /* 135: 패드로 섰을 때 잘 보이게 — 테마 강조색 굵은 테 */
            float fw = Math.max(3f, getResources().getDisplayMetrics().density * 2f);
            p.setStrokeWidth(fw); p.setColor(th.classic() ? 0xffe0b050 : th.accent);
            r.set(fw / 2f, fw / 2f, w - fw / 2f, h - fw / 2f);
            c.drawRoundRect(r, Math.max(0, rad - fw / 2f), Math.max(0, rad - fw / 2f), p);
        }
        p.setStyle(Paint.Style.FILL); p.setColor(th.classic() ? 0xffe0b050 : th.accent);
        r.set(idx * sw + in, in, (idx + 1) * sw - in, h - in); c.drawRoundRect(r, Math.max(0, rad - in), Math.max(0, rad - in), p);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(th.bold()); p.setFakeBoldText(!th.pocket());
        /* 135: 글자는 sp 로(글자 크기 설정을 따름) — 15sp 를 넘지 않게, 칸이 좁으면 10sp 까지만 줄인다(전엔 9px=3.4dp 까지 줄었다) */
        float cap = Math.min(h * 0.42f, sp(15)), floor = Math.min(cap, sp(10));
        for (int i = 0; i < n; i++) {
            float ts = cap; p.setTextSize(ts);
            while (p.measureText(names[i]) > sw - 8 && ts > floor) { ts -= 1; p.setTextSize(ts); }   /* 긴 이름은 줄여 맞춘다 */
            float ty = h / 2f - (p.descent() + p.ascent()) / 2f;
            p.setColor(i == idx ? (th.classic() ? 0xff1a1d26 : th.onAccent) : th.dialDim);
            c.drawText(names[i], (i + 0.5f) * sw, ty, p);
        }
    }

    /* ── 터치: 가로로 움직여야 잡고, 손 뗄 때 정한다 ── */
    private float downX, downY;
    private int downIdx;
    private boolean dragging = false;

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); downIdx = idx; dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!dragging) {
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);   /* 가로 끌기는 바가 갖는다 */
                    } else return true;            /* 세로로 가면 부모(스크롤)가 가로챈다 → CANCEL */
                }
                int k = seg(e.getX());
                if (k != idx) { idx = k; invalidate(); performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int k = dragging ? idx : seg(e.getX());   /* 그냥 톡 = 누른 칸 */
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                idx = k; invalidate();
                if (k != downIdx) {
                    if (!(Math.abs(e.getX() - downX) > slop)) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    if (cb != null) cb.onPick(k);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (idx != downIdx) { idx = downIdx; invalidate(); }   /* 스크롤로 넘어감 — 값은 그대로 */
                return true;
        }
        return super.onTouchEvent(e);
    }

    /* ── 패드·키 (설정 화면처럼 바가 포커스를 받을 때) ── */
    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) { step(-1); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { step(+1); return true; }
        return super.onKeyDown(code, e);
    }
    @Override protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
        super.onFocusChanged(gain, dir, prev); invalidate();
    }

    /* ── 톡백 ── */
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        String v = names[idx];
        info.setContentDescription((label != null ? label + ", " : "") + v);
        if (android.os.Build.VERSION.SDK_INT >= 30) info.setStateDescription(v);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, names.length - 1, idx));
        if (idx > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (idx < names.length - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    }
    @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return step(+1);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return step(-1);
        return super.performAccessibilityAction(action, args);
    }
}
