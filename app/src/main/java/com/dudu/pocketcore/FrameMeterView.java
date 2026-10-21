package com.dudu.pocketcore;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** 연습 대전 프레임 미터(SF6 식) — 두 줄 × 80칸(나 · 상대) + 숫자 줄. 자료는 Emu.nativePracticeFrames 가 채운 배열 그대로.
 *  색은 SF6 와 같은 뜻: 초록 발동 · 빨강 지속 · 파랑 경직 · 노랑 맞음 · 주황 막음 · 보라 다운 · 청록 판정 없는 동작 · 회색 움직임.
 *  위 흰 띠 = 튕겨내기 판정 · 아래 빨간 띠 = 내 장풍이 닿을 수 있음 · 점 세 개 칸 = 줄임(긴 다운·장풍 비행). 80칸 넘으면 감기고 흰 세로줄이 지금 자리. */
public class FrameMeterView extends View {
    private final byte[] cells = new byte[2 * FrameMeterText.VIEW];
    private final int[] sum = new int[FrameMeterText.NSUM];
    private final Paint fill = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint();
    private final RectF r = new RectF();
    private final float dp;
    private boolean legend = false, overlay = false;
    private int ver = -1;

    static final int[] COL = {
        0xFF26262B,   /* 0 움직일 수 있음 */
        0xFF6B7380,   /* 1 움직임(점프·대시) */
        0xFF2FD07A,   /* 2 발동 */
        0xFFFF3D57,   /* 3 지속 */
        0xFF3A82FF,   /* 4 경직 */
        0xFF35C9C9,   /* 5 판정 없는 동작 */
        0xFFFFD43B,   /* 6 맞음 */
        0xFFFF9E2C,   /* 7 막음 */
        0xFFB07CFF,   /* 8 다운 */
        0xFF505055,   /* 9 줄임 */
    };
    static final int BG = 0xE6140D08, EDGE = 0xFFB8862B, GOLD = 0xFFFFD76A, TXT = 0xFFE8DCC0, NOW = 0xFFFFFFFF, PLUS = 0xFF6BD0FF, MINUS = 0xFFFF6B6B;

    public FrameMeterView(Context c, Typeface tf) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        text.setTypeface(tf);
        text.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11, c.getResources().getDisplayMetrics()));   /* 안내 띠와 같은 11sp */
        line.setColor(0xFFFFFFFF); line.setStrokeWidth(Math.max(1, dp));
        label.setTypeface(tf); label.setColor(GOLD);
        label.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10, c.getResources().getDisplayMetrics()));
    }
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 칸 높이 — 칸 폭 × 1.5(최소 5dp), 이름 글자(«나»·«상대»)보다 낮지 않게(낮으면 두 줄 이름이 겹친다 — 에뮬레이터 세로 화면에서 봄) · 이름 칸을 뺀 좁은 판은 글자 높이 안 봄 */
    private float rowH(float cw, boolean names) { return Math.max(Math.max(5 * dp, cw * 1.5f), names ? -label.ascent() + label.descent() : 0); }

    /** 새 자료 — 판 번호가 같으면 다시 그리지 않는다 */
    public void set(byte[] c, int[] s, int v) {
        if (v == ver) return;
        ver = v;
        System.arraycopy(c, 0, cells, 0, cells.length);
        System.arraycopy(s, 0, sum, 0, sum.length);
        invalidate();
    }
    public void toggleLegend() { legend = !legend; requestLayout(); invalidate(); }
    /** 게임 그림 위에 겹칠 때 — 바탕을 더 비춰 싸움꾼이 보이게 */
    public void setOverlay(boolean on) { if (overlay != on) { overlay = on; invalidate(); } }

    /** 폭 w 에서 필요한 높이(칸 높이 rowH) */
    public int heightFor(int w) {
        float cw = cellW(w), ch = rowH(cw, !narrow(w));
        float th = -text.ascent() + text.descent();
        return Math.round(6 * dp + 2 * ch + 2 * dp + 4 * dp + th * (legend ? 2.2f : 1.1f) + 4 * dp);
    }
    private float labelW() { return label.measureText("상대") + 6 * dp; }
    /** 176: 미터 폭 = 게임 그림 폭이라 그림을 작게 줄이면 좁아진다 — 이름 칸(«나»·«상대»)과 옆 여백 6dp 를 두면 칸이 3px 아래로 내려갈 때는
     *  이름 칸을 빼고 옆 여백을 2dp 로(위 줄 = 나 · 아래 줄 = 상대 차례는 그대로) — 칸을 되도록 넓게 */
    boolean narrow(int w) { return (w - 12 * dp - labelW()) / FrameMeterText.VIEW < 3; }
    private float padX(int w) { return narrow(w) ? 2 * dp : 6 * dp; }
    float cellW(int w) { return Math.max(2, (w - 2 * padX(w) - (narrow(w) ? 0 : labelW())) / FrameMeterText.VIEW); }
    /** 176 검토: 칸 80개가 2px 씩 다 들어가는 가장 좁은 폭(좁은 판 — 옆 여백 2dp · 이름 칸 없음). 그림이 이보다 좁으면(정수배 1× = 160px)
     *  cellW 가 2 에 걸려 오른쪽 칸들이 미터 밖으로 잘렸다 → placeFrameMeter 가 이 폭으로 그림 가운데에 둔다 */
    int minWidth() { return (int) Math.ceil(2 * FrameMeterText.VIEW + 4 * dp); }

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        setMeasuredDimension(w, heightFor(w));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        fill.setStyle(Paint.Style.FILL); fill.setColor(overlay ? 0xA0140D08 : BG);
        r.set(0, 0, w, h); c.drawRoundRect(r, 4 * dp, 4 * dp, fill);
        fill.setStyle(Paint.Style.STROKE); fill.setStrokeWidth(Math.max(1, 1.5f * dp)); fill.setColor(EDGE);
        c.drawRoundRect(r, 4 * dp, 4 * dp, fill);
        fill.setStyle(Paint.Style.FILL);
        boolean names = !narrow(w);
        float px = padX(w), x0 = px + (names ? labelW() : 0), cw = cellW(w), ch = rowH(cw, names), y = 6 * dp;
        float gap = cw >= 4 * dp ? 1 * dp : Math.min(0.5f * dp, cw * 0.25f);   /* 칸 사이 틈 — 좁은 칸은 칸 폭의 ¼ 까지만(색이 틈에 묻히지 않게) */
        int len = sum[FrameMeterText.S_LEN];
        for (int row = 0; row < 2; row++) {
            if (names) c.drawText(row == 0 ? "나" : "상대", px, y + ch / 2 - (label.ascent() + label.descent()) / 2, label);
            for (int i = 0; i < FrameMeterText.VIEW; i++) {
                int v = cells[row * FrameMeterText.VIEW + i] & 0xFF;
                float L = x0 + i * cw, R = L + cw - gap;
                if (v == FrameMeterText.EMPTY) {
                    fill.setColor(0x30FFFFFF); c.drawRect(L, y + ch - Math.max(1, dp), R, y + ch, fill);   /* 빈칸 = 바닥 선만 */
                    continue;
                }
                int k = v & 0x0F;
                fill.setColor(k < COL.length ? COL[k] : 0xFF808080);
                c.drawRect(L, y, R, y + ch, fill);
                if ((v & FrameMeterText.F_PARRY) != 0) { fill.setColor(0xFFFFFFFF); c.drawRect(L, y, R, y + ch * 0.25f, fill); }
                if ((v & FrameMeterText.F_PROJ) != 0) { fill.setColor(COL[3]); c.drawRect(L, y + ch * 0.72f, R, y + ch, fill); }
                if (k == FrameMeterText.K_GAP) {
                    fill.setColor(0xFFC8C8C8);
                    float cx = (L + R) / 2, s = Math.max(1, dp * 0.8f);
                    for (int d = 0; d < 3; d++) { float cy = y + ch * (0.25f + 0.25f * d); c.drawRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, fill); }
                }
            }
            y += ch + 2 * dp;
        }
        if (len > FrameMeterText.VIEW) {                        /* 감김 — 지금 자리 */
            float xc = x0 + ((len - 1) % FrameMeterText.VIEW + 1) * cw - gap / 2;
            c.drawLine(xc, 6 * dp - 2 * dp, xc, y, line);
        }
        y += 2 * dp - text.ascent();
        FrameMeterText.Part[] ps = FrameMeterText.parts(sum);
        float x = px, avail = w - 2 * px;
        float tw = ps.length == 0 ? text.measureText(FrameMeterText.HINT) : 0;
        for (int i = 0; i < ps.length; i++) tw += (i > 0 ? text.measureText(" · ") : 0) + text.measureText(ps[i].text);
        int keep = squeeze(c, tw, avail, px);                   /* 176: 좁은 그림 — 줄이 넘치면 가로로만 좁혀(0.6 배까지) 끝이 잘리지 않게 */
        if (ps.length == 0) { text.setColor(0xA0E8DCC0); c.drawText(FrameMeterText.HINT, x, y, text); }
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) { text.setColor(0x80E8DCC0); c.drawText(" · ", x, y, text); x += text.measureText(" · "); }
            text.setColor(ps[i].sign > 0 ? PLUS : ps[i].sign < 0 ? MINUS : ps[i].now ? NOW : TXT);
            if (!ps[i].now && ps[i].sign != 0) text.setAlpha(0xA0);
            c.drawText(ps[i].text, x, y, text); x += text.measureText(ps[i].text);
            text.setAlpha(0xFF);
        }
        if (keep >= 0) c.restoreToCount(keep);
        if (legend) {                                           /* 톡 = 색 설명 켜고 끄기 */
            y += (-text.ascent() + text.descent()) * 1.1f;
            x = px;
            float sq = -text.ascent() * 0.8f, lw = -6 * dp;
            for (int i = 0; i < FrameMeterText.LEGEND.length; i++) lw += sq + 2 * dp + text.measureText(FrameMeterText.LEGEND[i]) + 6 * dp;
            keep = squeeze(c, lw, avail, px);
            for (int i = 0; i < FrameMeterText.LEGEND.length; i++) {
                fill.setColor(COL[FrameMeterText.LEGEND_K[i]]); c.drawRect(x, y - sq, x + sq, y, fill);
                x += sq + 2 * dp; text.setColor(TXT); c.drawText(FrameMeterText.LEGEND[i], x, y, text);
                x += text.measureText(FrameMeterText.LEGEND[i]) + 6 * dp;
            }
            if (keep >= 0) c.restoreToCount(keep);
        }
    }

    /** 폭 need 인 줄이 avail 에 안 들어가면 왼끝 x0 을 축으로 가로만 좁힌다(0.6 배까지 — 그보다 좁으면 끝이 잘림). 돌려준 값으로 restoreToCount · 안 좁히면 −1 */
    private static int squeeze(Canvas c, float need, float avail, float x0) {
        if (need <= avail || need <= 0) return -1;
        int k = c.save();
        c.scale(Math.max(0.6f, avail / need), 1f, x0, 0);
        return k;
    }
}
