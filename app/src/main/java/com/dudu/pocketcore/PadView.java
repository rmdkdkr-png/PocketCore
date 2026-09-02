package com.dudu.pocketcore;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Scanner;

/** 고정 온스크린 패드 v3 — 게임별 프로필 + 「키」 편집(드래그 이동·크기 조절).
 *  SVC 프로필(6키): 약P/약K/강P/강K/기술(방향+기술=필살기)/A+B, ↓+OPTION=설정
 *  SS2 프로필(4키): A(펀치)/B(킥) — 짧게 약·길게 강 — /SP(원버튼)/A+B
 *  배속(▶▶)은 누르는 동안만. 배치는 pad_svc.txt / pad_ss2.txt 에 저장. */
public class PadView extends View {

    public interface Listener {
        void onMask(int mask);
        void onAction(int action);
        void onTurbo(boolean on);
    }

    public static final int ACT_SAVE = 1, ACT_LOAD = 2, ACT_SHOT = 3, ACT_RESET = 4, ACT_PICK = 5, ACT_SLOT = 6, ACT_SPK = 7,
            /* 설정 — 게임 안에서 바로 연다. 예전에는 「롬」으로 게임을 내리고
               목록 맨 아래까지 가야 닿았다. 설정 하나 보려고 게임을 끄는 건 말이 안 된다. */
            ACT_CFG = 8;

    private Listener listener;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

    /* 컨트롤: {이름, 라벨, 비트(-1=십자, -2=OPTION, -3=배속), 기본fx, 기본fy, 기본크기} */
    private static final Object[][] P_SVC = {
        { "DPAD", "",     -1, 0.22f, 0.76f, 1.00f },
        { "WP",   "약P",   0, 0.74f, 0.84f, 1.00f },
        { "WK",   "약K",   8, 0.88f, 0.76f, 1.00f },
        { "SP_P", "강P",   1, 0.60f, 0.76f, 1.00f },
        { "SP_K", "강K",   9, 0.74f, 0.68f, 1.00f },
        { "TECH", "기술", 11, 0.90f, 0.60f, 1.25f },
        { "AB",   "A+B", 10, 0.10f, 0.59f, 0.95f },
        { "OPT",  "",     -2, 0.50f, 0.955f, 1.00f },
        { "FF",   "▶▶",  -3, 0.94f, 0.09f, 0.80f },
    };
    private static final Object[][] P_SS2 = {
        { "DPAD", "",     -1, 0.22f, 0.76f, 1.00f },
        { "A",    "A",     0, 0.66f, 0.80f, 1.10f },
        { "B",    "B",     8, 0.88f, 0.72f, 1.10f },
        { "SP",   "SP",    9, 0.90f, 0.56f, 1.20f },
        { "AB",   "A+B",   1, 0.10f, 0.59f, 0.95f },
        { "OPT",  "",     -2, 0.50f, 0.955f, 1.00f },
        { "FF",   "▶▶",  -3, 0.94f, 0.09f, 0.80f },
    };
    private static final int[] BTN_COL_ON  = { 0x8866aaff, 0x88ff5566, 0x884477cc, 0x88cc3344, 0x88ffcc44, 0x8899eeaa };
    private static final int[] BTN_COL_OFF = { 0x4466aaff, 0x44ff5566, 0x444477cc, 0x44cc3344, 0x44ffcc44, 0x4499eeaa };

    private Object[][] prof = P_SVC;
    private String profName = "svc";
    private boolean svcPlaceholders = true;
    private float[] fx, fy, sc;
    private int nC;

    private float dpadR, btnR;
    private final RectF opt = new RectF();
    private final RectF[] util = new RectF[9];
    private final String[] utilLabel = { "슬롯1", "저장", "로드", "샷", "리셋", "롬", "해설", "설정", "키" };
    private final int[] utilAct = { ACT_SLOT, ACT_SAVE, ACT_LOAD, ACT_SHOT, ACT_RESET, ACT_PICK, ACT_SPK, ACT_CFG, 0 };
    private static final int UTIL_EDIT = 8;   /* 마지막 칸 「키」 = 배치 편집 토글 (액션이 아니다) */
    private final RectF minus = new RectF(), plus = new RectF();
    private final RectF barHandle = new RectF();
    private boolean barOpen = false;   /* 상단바는 기본 접힘 — [≡]로 여닫는 순수 토글 */

    private int mask = 0;
    private boolean edit = false;
    private int dragIdx = -1, dragPid = -1, selIdx = -1;
    private boolean ffDown = false;
    private int ffPid = -1;
    /* 십자 소유제 — 십자를 잡은 손가락은 화면 어디로 흘러도 십자를 놓지 않는다.
       (구판: 정사각 판정 + 축 임계값 — 대각이 과대하고, 박스 밖으로 새면 뚝 끊겼다) */
    private int dpadPid = -1;
    private int dpadMask = 0, dpadLast = 0;

    public PadView(Context c) {
        super(c);
        for (int i = 0; i < util.length; i++) util[i] = new RectF();
        text.setColor(0xccffffff);
        text.setTextAlign(Paint.Align.CENTER);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f);
        setProfile("svc");
    }

    public void setListener(Listener l) { listener = l; }
    public void setSlotLabel(int n) { utilLabel[0] = "슬롯" + n; invalidate(); }

    /** "ss2" 또는 "svc" — 롬 헤더로 EmuActivity 가 정한다 */
    public void setProfile(String name) {
        profName = name;
        prof = "ss2".equals(name) ? P_SS2 : P_SVC;
        svcPlaceholders = !"ss2".equals(name);
        nC = prof.length;
        fx = new float[nC]; fy = new float[nC]; sc = new float[nC];
        for (int i = 0; i < nC; i++) {
            fx[i] = (Float) prof[i][3];
            fy[i] = (Float) prof[i][4];
            sc[i] = (Float) prof[i][5];
        }
        load();
        invalidate();
    }

    private File cfg() { return new File(MainActivity.root(), "pad_" + profName + ".txt"); }

    private void load() {
        try (Scanner s = new Scanner(cfg(), "UTF-8")) {
            while (s.hasNextLine()) {
                String[] kv = s.nextLine().trim().split("[=,]");
                if (kv.length < 3) continue;
                for (int i = 0; i < nC; i++)
                    if (prof[i][0].equals(kv[0])) {
                        fx[i] = Float.parseFloat(kv[1]);
                        fy[i] = Float.parseFloat(kv[2]);
                        if (kv.length >= 4) sc[i] = Float.parseFloat(kv[3]);
                    }
            }
        } catch (Exception ignored) { }
    }

    private void save() {
        StringBuilder sb = new StringBuilder("# 패드 배치 (이름=가로,세로,크기) — 지우면 기본값\n");
        for (int i = 0; i < nC; i++)
            sb.append(prof[i][0]).append('=').append(fx[i]).append(',')
              .append(fy[i]).append(',').append(sc[i]).append('\n');
        try (FileOutputStream fo = new FileOutputStream(cfg())) {
            fo.write(sb.toString().getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float padH = h * 0.44f;
        dpadR = Math.min(w * 0.17f, padH * 0.36f);
        btnR = Math.min(w * 0.082f, padH * 0.17f);
        float uw = w * 0.099f, uh = h * 0.042f, gap = w * 0.008f;   /* 9칸이 되며 조금 좁혔다 */
        float total = uw * util.length + gap * (util.length - 1), x = (w - total) / 2f, y = h * 0.012f;
        for (int i = 0; i < util.length; i++) { util[i].set(x, y, x + uw, y + uh); x += uw + gap; }
        barHandle.set(w * 0.46f, 0, w * 0.54f, h * 0.030f);
    }

    private boolean bit(int b) { return b >= 0 && (mask & (1 << b)) != 0; }
    private float cx(int i) { return fx[i] * getWidth(); }
    private float cy(int i) { return fy[i] * getHeight(); }
    private int bitOf(int i) { return (Integer) prof[i][2]; }
    private float radOf(int i) {
        int b = bitOf(i);
        if (b == -1) return dpadR * sc[i];
        return btnR * sc[i];
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();

        /* SVC — 기둥 아트·해설 자리 (지금은 틀만, 콘텐츠는 다음 단계) */
        if (svcPlaceholders) {
            line.setColor(0x33ffcc44);
            fill.setColor(0x14ffffff);
            float top = h * 0.075f, bot = h * 0.50f, pw = w * 0.105f;
            RectF lp = new RectF(w * 0.005f, top, w * 0.005f + pw, bot);
            RectF rp = new RectF(w * 0.995f - pw, top, w * 0.995f, bot);
            c.drawRoundRect(lp, 8, 8, fill); c.drawRoundRect(lp, 8, 8, line);
            c.drawRoundRect(rp, 8, 8, fill); c.drawRoundRect(rp, 8, 8, line);
            text.setTextSize(h * 0.013f);
            c.drawText("기둥", lp.centerX(), lp.centerY(), text);
            c.drawText("아트", lp.centerX(), lp.centerY() + h * 0.016f, text);
            c.drawText("기둥", rp.centerX(), rp.centerY(), text);
            c.drawText("아트", rp.centerX(), rp.centerY() + h * 0.016f, text);
            RectF band = new RectF(w * 0.005f, h * 0.056f, w * 0.995f, h * 0.073f);
            c.drawRoundRect(band, 6, 6, fill); c.drawRoundRect(band, 6, 6, line);
            text.setTextSize(band.height() * 0.62f);
            c.drawText("해설 자리", band.centerX(), band.centerY() + band.height() * 0.22f, text);
        }

        int btnColorIdx = 0;
        for (int i = 0; i < nC; i++) {
            int b = bitOf(i);
            if (b == -1) {                                   /* 십자 */
                float dcx = cx(i), dcy = cy(i), R = radOf(i), arm = R * 0.42f;
                fill.setColor(bit(Emu.UP)    ? 0x66ffffff : 0x33ffffff);
                c.drawRect(dcx - arm, dcy - R, dcx + arm, dcy - arm, fill);
                fill.setColor(bit(Emu.DOWN)  ? 0x66ffffff : 0x33ffffff);
                c.drawRect(dcx - arm, dcy + arm, dcx + arm, dcy + R, fill);
                fill.setColor(bit(Emu.LEFT)  ? 0x66ffffff : 0x33ffffff);
                c.drawRect(dcx - R, dcy - arm, dcx - arm, dcy + arm, fill);
                fill.setColor(bit(Emu.RIGHT) ? 0x66ffffff : 0x33ffffff);
                c.drawRect(dcx + arm, dcy - arm, dcx + R, dcy + arm, fill);
                fill.setColor(0x22ffffff);
                c.drawRect(dcx - arm, dcy - arm, dcx + arm, dcy + arm, fill);
            } else if (b == -2) {                            /* OPTION */
                float ow2 = getWidth() * 0.10f * sc[i], oh2 = getHeight() * 0.021f * sc[i];
                opt.set(cx(i) - ow2, cy(i) - oh2, cx(i) + ow2, cy(i) + oh2);
                fill.setColor(bit(Emu.START) ? 0x66ffffff : 0x2affffff);
                c.drawRoundRect(opt, 12, 12, fill);
                text.setTextSize(opt.height() * 0.5f);
                c.drawText("OPTION", opt.centerX(), opt.centerY() + opt.height() * 0.18f, text);
            } else if (b == -3) {                            /* 배속 */
                float r = radOf(i);
                fill.setColor(ffDown ? 0x88ffcc44 : 0x33ffffff);
                c.drawCircle(cx(i), cy(i), r, fill);
                text.setTextSize(r * 0.55f);
                c.drawText((String) prof[i][1], cx(i), cy(i) + r * 0.20f, text);
            } else {                                         /* 게임 버튼 */
                float r = radOf(i);
                int ci = btnColorIdx % BTN_COL_ON.length; btnColorIdx++;
                fill.setColor(bit(b) ? BTN_COL_ON[ci] : BTN_COL_OFF[ci]);
                c.drawCircle(cx(i), cy(i), r, fill);
                text.setTextSize(r * 0.52f);
                c.drawText((String) prof[i][1], cx(i), cy(i) + r * 0.19f, text);
            }
            if (edit && i == selIdx) {
                line.setColor(0xccffcc44);
                c.drawCircle(cx(i), cy(i), radOf(i) + 8, line);
            }
        }

        fill.setColor(barOpen || edit ? 0x44ffffff : 0x1effffff);
        c.drawRoundRect(barHandle, 8, 8, fill);
        text.setTextSize(barHandle.height() * 0.75f);
        c.drawText("\u2261", barHandle.centerX(), barHandle.bottom - barHandle.height() * 0.22f, text);
        if (barOpen || edit) {
            fill.setColor(0x22ffffff);
            text.setTextSize(util[0].height() * 0.5f);
            for (int i = 0; i < util.length; i++) {
                if (i == UTIL_EDIT && edit) fill.setColor(0x66ffcc44);
                c.drawRoundRect(util[i], 10, 10, fill);
                if (i == UTIL_EDIT && edit) fill.setColor(0x22ffffff);
                c.drawText(utilLabel[i], util[i].centerX(),
                        util[i].centerY() + util[i].height() * 0.18f, text);
            }
        }

        if (edit) {
            text.setTextSize(getHeight() * 0.019f);
            c.drawText("편집: 끌어서 이동 · 버튼 잡고 [－][＋]로 크기 · 「키」로 저장",
                    w / 2f, util[0].bottom + getHeight() * 0.032f, text);
            float bw = w * 0.10f, bh = getHeight() * 0.038f, byy = util[0].bottom + getHeight() * 0.042f;
            minus.set(w * 0.30f, byy, w * 0.30f + bw, byy + bh);
            plus.set(w * 0.60f, byy, w * 0.60f + bw, byy + bh);
            fill.setColor(0x44ffffff);
            c.drawRoundRect(minus, 10, 10, fill);
            c.drawRoundRect(plus, 10, 10, fill);
            text.setTextSize(bh * 0.6f);
            c.drawText("－", minus.centerX(), minus.centerY() + bh * 0.2f, text);
            c.drawText("＋", plus.centerX(), plus.centerY() + bh * 0.2f, text);
        }
    }

    private int nearestControl(float x, float y) {
        int best = -1; float bd = 1e9f;
        for (int i = 0; i < nC; i++) {
            float r = radOf(i) * 1.4f + 20f;
            float d = dist(x, y, cx(i), cy(i));
            if (d < r && d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private int hitButtons(float x, float y) {
        int m = 0;
        for (int i = 0; i < nC; i++) {
            int b = bitOf(i);
            if (b == -2) {
                if (opt.contains(x, y)) m |= 1 << Emu.START;
            } else if (b >= 0) {
                /* 누른 채 미끄러져도 강약 홀드가 끊기지 않게, 이미 눌린 버튼은 이탈 반경을 넓게 */
                float mul = (mask & (1 << b)) != 0 ? 1.60f : 1.25f;
                if (dist(x, y, cx(i), cy(i)) < radOf(i) * mul) m |= 1 << b;
            }
        }
        return m;
    }

    private int dpadIndex() {
        for (int i = 0; i < nC; i++) if (bitOf(i) == -1) return i;
        return -1;
    }

    /* 8방 섹터 중심각 — 비트 마스크 기준 */
    private static float dirCenter(int m) {
        boolean u = (m & (1 << Emu.UP)) != 0, d = (m & (1 << Emu.DOWN)) != 0;
        boolean l = (m & (1 << Emu.LEFT)) != 0, r = (m & (1 << Emu.RIGHT)) != 0;
        if (r && !u && !d) return 0;   if (r && u) return 45;
        if (u && !l && !r) return 90;  if (l && u) return 135;
        if (l && !u && !d) return 180; if (l && d) return 225;
        if (d && !l && !r) return 270; if (r && d) return 315;
        return -1;
    }
    private static boolean isDiag(int m) { return Integer.bitCount(m) == 2; }
    private static float angDist(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    /* 각도 8분할 — 정방향 56도 / 대각 34도 (걷기·점프가 대각으로 새지 않게 정방향 우대).
       직전 방향에는 +7도 히스테리시스: 경계에서 벌벌 떨리지 않는다. */
    private int dpadDir(float x, float y) {
        int di = dpadIndex();
        if (di < 0) return 0;
        float dx = x - cx(di), dy = y - cy(di), R = radOf(di);
        /* 데드존: 중립 판정은 12%, 방향이 잡혀 있으면 7%까지 내려와야 놓는다
           (제보: 「가운데 데드존이 너무 큼」 — 20%는 큰 패드에서 무반응 원판이 됐다) */
        float dead = R * (dpadLast != 0 ? 0.07f : 0.12f);
        if (dx * dx + dy * dy < dead * dead) { dpadLast = 0; return 0; }
        float ang = (float) Math.toDegrees(Math.atan2(-dy, dx));
        if (ang < 0) ang += 360f;
        /* 직전 방향 섹터(+7도) 안이면 유지 */
        if (dpadLast != 0) {
            float c0 = dirCenter(dpadLast);
            if (c0 >= 0 && angDist(ang, c0) <= (isDiag(dpadLast) ? 21f : 24f) + 5f)
                return dpadLast;
        }
        /* 정방향 48도/대각 42도 — 과한 정방향 편향은 원을 그려도 네모처럼 걸린다(제보) */
        int m;
        if      (angDist(ang,   0) <= 24f) m = 1 << Emu.RIGHT;
        else if (angDist(ang,  90) <= 24f) m = 1 << Emu.UP;
        else if (angDist(ang, 180) <= 24f) m = 1 << Emu.LEFT;
        else if (angDist(ang, 270) <= 24f) m = 1 << Emu.DOWN;
        else if (ang <  90f) m = (1 << Emu.RIGHT) | (1 << Emu.UP);
        else if (ang < 180f) m = (1 << Emu.LEFT)  | (1 << Emu.UP);
        else if (ang < 270f) m = (1 << Emu.LEFT)  | (1 << Emu.DOWN);
        else                 m = (1 << Emu.RIGHT) | (1 << Emu.DOWN);
        dpadLast = m;
        return m;
    }

    private int ffIndex() {
        for (int i = 0; i < nC; i++) if (bitOf(i) == -3) return i;
        return -1;
    }

    private static float dist(float x, float y, float cx, float cy) {
        float dx = x - cx, dy = y - cy;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();

        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) {
            int idx = e.getActionIndex();
            float x = e.getX(idx), y = e.getY(idx);
            if (barHandle.contains(x, y)) {            /* [≡] 상단바 토글 */
                barOpen = !barOpen;
                invalidate();
                return true;
            }
            if ((barOpen || edit) && util[UTIL_EDIT].contains(x, y)) {   /* 「키」 */
                edit = !edit;
                if (!edit) save();
                mask = 0; dragIdx = -1; selIdx = -1;
                dpadPid = -1; dpadMask = 0; dpadLast = 0;
                if (listener != null) listener.onMask(0);
                invalidate();
                return true;
            }
            if (edit) {
                if (selIdx >= 0 && minus.contains(x, y)) {
                    sc[selIdx] = Math.max(0.6f, sc[selIdx] - 0.1f); invalidate(); return true;
                }
                if (selIdx >= 0 && plus.contains(x, y)) {
                    sc[selIdx] = Math.min(1.8f, sc[selIdx] + 0.1f); invalidate(); return true;
                }
                int ci = nearestControl(x, y);
                if (ci >= 0) { dragIdx = ci; selIdx = ci; dragPid = e.getPointerId(idx); invalidate(); }
                return true;
            }
            if (barOpen) {
                for (int i = 0; i < UTIL_EDIT; i++) {
                    if (util[i].contains(x, y)) {
                        if (listener != null) listener.onAction(utilAct[i]);
                        /* 순수 토글 — [≡]를 다시 눌러야 닫힌다. 저장·로드·샷은 연달아 쓰는데
                           매번 다시 열어야 했다(제보). 다만 화면을 떠나는 「롬」만은 접는다. */
                        if (utilAct[i] == ACT_PICK) barOpen = false;
                        invalidate();
                        return true;
                    }
                }
            }
            int fi = ffIndex();
            if (fi >= 0 && dist(x, y, cx(fi), cy(fi)) < radOf(fi) * 1.3f) {
                ffDown = true; ffPid = e.getPointerId(idx);
                if (listener != null) listener.onTurbo(true);
                invalidate();
                return true;
            }
            /* 십자 잡기 — 시작점이 십자 근방(1.35R)이면 이 손가락이 십자를 소유한다 */
            int di = dpadIndex();
            if (dpadPid < 0 && di >= 0
                    && dist(x, y, cx(di), cy(di)) < radOf(di) * 1.35f) {
                dpadPid = e.getPointerId(idx);
                dpadLast = 0;
            }
        }

        if (edit) {
            if (act == MotionEvent.ACTION_MOVE && dragIdx >= 0) {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (e.getPointerId(i) != dragPid) continue;
                    fx[dragIdx] = Math.max(0.03f, Math.min(0.97f, e.getX(i) / getWidth()));
                    fy[dragIdx] = Math.max(0.08f, Math.min(0.985f, e.getY(i) / getHeight()));
                    invalidate();
                }
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                dragIdx = -1;
            } else if (act == MotionEvent.ACTION_POINTER_UP
                    && e.getPointerId(e.getActionIndex()) == dragPid) {
                dragIdx = -1;
            }
            return true;
        }

        if (ffDown) {                                  /* 배속 해제 검사 */
            boolean still = false;
            if (act != MotionEvent.ACTION_UP && act != MotionEvent.ACTION_CANCEL) {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (act == MotionEvent.ACTION_POINTER_UP && i == e.getActionIndex()) continue;
                    if (e.getPointerId(i) == ffPid) still = true;
                }
            }
            if (!still) {
                ffDown = false; ffPid = -1;
                if (listener != null) listener.onTurbo(false);
                invalidate();
            }
        }

        int m = 0;
        boolean dpadHeld = false;
        if (act != MotionEvent.ACTION_UP && act != MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (act == MotionEvent.ACTION_POINTER_UP && i == e.getActionIndex()) continue;
                if (e.getPointerId(i) == ffPid) continue;
                if (e.getPointerId(i) == dpadPid) {        /* 소유 손가락 — 어디에 있든 십자만 */
                    dpadMask = dpadDir(e.getX(i), e.getY(i));
                    dpadHeld = true;
                    continue;
                }
                m |= hitButtons(e.getX(i), e.getY(i));
            }
        }
        if (!dpadHeld) { dpadPid = -1; dpadMask = 0; dpadLast = 0; }
        m |= dpadMask;
        if (m != mask) {
            if ((m & ~mask) != 0)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            mask = m;
            if (listener != null) listener.onMask(mask);
            invalidate();
        }
        return true;
    }
}
