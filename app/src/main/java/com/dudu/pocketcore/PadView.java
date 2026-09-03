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
        /* 편집 모드에서 게임 화면을 끌고(전체 폭/높이 대비 이동분) 크기를 바꾼다 */
        void onScreenDrag(float dxFrac, float dyFrac);
        void onScreenScale(int dPct);
        void onScreenDrop();
    }

    /* 게임 화면의 현재 자리 — EmuActivity 가 배치 때마다 알려 준다 (편집 상자용) */
    private final RectF screenBox = new RectF();
    private boolean selScreen = false, dragScreen = false;
    private float lastSX, lastSY;
    public void setScreenBox(float l, float t, float r, float b) {
        screenBox.set(l, t, r, b);
        if (edit) invalidate();
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
        { "EXIT", "목록", -4, 0.06f, 0.09f, 0.75f },
    };
    private static final Object[][] P_SS2 = {
        { "DPAD", "",     -1, 0.22f, 0.76f, 1.00f },
        { "A",    "A",     0, 0.66f, 0.80f, 1.10f },
        { "B",    "B",     8, 0.88f, 0.72f, 1.10f },
        { "SP",   "SP",    9, 0.90f, 0.56f, 1.20f },
        { "AB",   "A+B",   1, 0.10f, 0.59f, 0.95f },
        { "OPT",  "",     -2, 0.50f, 0.955f, 1.00f },
        { "FF",   "▶▶",  -3, 0.94f, 0.09f, 0.80f },
        { "EXIT", "목록", -4, 0.06f, 0.09f, 0.75f },
    };
    /* KOF R-2 — SP 엔진(설정 「KOF 원버튼」 켬): R(비트11)=SP, 탭=약/홀드=강(문턱 6프레임,
       코어가 잰다 — SVC 의 12 와 다르다, 이식소 실측). L(비트10)=A+B. 슬롯은 잡은 방향:
       방향없음=장풍 · 앞=대공 · 앞아래=초필살기 · 공중 가능. 엔진을 끄면 코어가 R 을
       A+B 로 도로 접으므로 버튼이 놀지 않는다. */
    private static final Object[][] P_KOF = {
        { "DPAD", "",     -1, 0.22f, 0.76f, 1.00f },
        { "A",    "A",     0, 0.66f, 0.80f, 1.10f },
        { "B",    "B",     8, 0.88f, 0.72f, 1.10f },
        { "SP",   "SP",   11, 0.90f, 0.56f, 1.20f },
        { "AB",   "A+B",  10, 0.10f, 0.59f, 0.95f },
        { "OPT",  "",     -2, 0.50f, 0.955f, 1.00f },
        { "FF",   "▶▶",  -3, 0.94f, 0.09f, 0.80f },
        { "EXIT", "목록", -4, 0.06f, 0.09f, 0.75f },
    };
    /* 순정 NGPC — 원버튼 엔진이 없는 게임(메탈슬러그 등). NGP 실기 그대로 A·B 두 개만.
       기술·강약 버튼을 여기 두면 안 나가는 버튼이 화면만 차지한다는 제보로 분리했다. */
    private static final Object[][] P_NGP = {
        { "DPAD", "",     -1, 0.22f, 0.76f, 1.00f },
        { "A",    "A",     0, 0.68f, 0.80f, 1.15f },
        { "B",    "B",     8, 0.88f, 0.70f, 1.15f },
        { "OPT",  "",     -2, 0.50f, 0.955f, 1.00f },
        { "FF",   "▶▶",  -3, 0.94f, 0.09f, 0.80f },
        { "EXIT", "목록", -4, 0.06f, 0.09f, 0.75f },
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
    /* 「종료」= 게임을 닫고 고르는 창으로 (제보: 「롬」은 사실 종료 버튼인데 이름이 달랐다).
       「해설」은 사무쇼2 에만 있는 기능이라 그 프로필에서만 칸이 생긴다.
       「배치」= 버튼 자리·크기 + 게임 화면 상자까지 한꺼번에 편집(제보: 「키」란 이름이 좁았다). */
    private final String[] utilLabel = { "슬롯1", "저장", "로드", "샷", "리셋", "종료", "해설", "설정", "배치" };
    private final int[] utilAct = { ACT_SLOT, ACT_SAVE, ACT_LOAD, ACT_SHOT, ACT_RESET, ACT_PICK, ACT_SPK, ACT_CFG, 0 };
    private static final int UTIL_EDIT = 8;   /* 마지막 칸 「키」 = 배치 편집 토글 (액션이 아니다) */
    private final RectF minus = new RectF(), plus = new RectF();
    private final RectF barHandle = new RectF();
    private boolean barOpen = false;   /* 상단바는 기본 접힘 — [≡]로 여닫는 순수 토글 */

    private int mask = 0;
    private boolean edit = false;
    private boolean land = false;      /* 가로 화면 — 배치 파일과 기본 좌표가 따로다 */
    private boolean physical = false;  /* 물리 패드 모드 — 게임 버튼을 숨기고 메뉴 알약만 남긴다 */
    /* 가로 기본 좌표(이름별). 십자는 왼쪽 아래, 버튼 무리는 오른쪽 아래, 유틸은 양 위 구석. */
    private static final Object[][] LAND = {
        { "DPAD", 0.13f, 0.64f }, { "WP", 0.74f, 0.80f }, { "WK", 0.86f, 0.66f }, { "SP_P", 0.63f, 0.66f },
        { "SP_K", 0.75f, 0.52f }, { "TECH", 0.92f, 0.40f }, { "AB", 0.06f, 0.38f }, { "A", 0.68f, 0.78f },
        { "B", 0.86f, 0.62f }, { "SP", 0.92f, 0.38f }, { "OPT", 0.50f, 0.93f }, { "FF", 0.965f, 0.10f },
        { "EXIT", 0.035f, 0.10f },
    };
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

    /** "ss2"·"svc"·"ngp" — 롬 헤더로 EmuActivity 가 정한다 */
    public void setProfile(String name) { setProfile(name, name); }

    /** gameKey 는 배치 파일 이름 (pad_<gameKey>.txt) — **게임마다** 따로 저장한다.
     *  프로필(버튼 구성)은 세 벌뿐이지만 자리는 게임별로 달리 두고 싶다는 제보로 분리. */
    public void setProfile(String name, String gameKey) {
        profName = gameKey;
        prof = "ss2".equals(name) ? P_SS2 : "svc".equals(name) ? P_SVC
             : "kof".equals(name) ? P_KOF : P_NGP;
        svcPlaceholders = !"ss2".equals(name);
        nC = prof.length;
        fx = new float[nC]; fy = new float[nC]; sc = new float[nC];
        applyDefaults();
        load();
        if (getWidth() > 0) layoutBar(getWidth(), getHeight());   /* 해설 칸 유무가 바뀐다 */
        invalidate();
    }

    /** 이 프로필에서 상단바 i번 칸이 존재하는가 — 해설(SPK)은 사무쇼2 전용. */
    private boolean utilVisible(int i) {
        return utilAct[i] != ACT_SPK || prof == P_SS2;
    }

    /** 배치 파일 — 세로 pad_<게임>.txt / 가로 pad_<게임>_land.txt 로 따로 둔다. */
    private File cfg() { return new File(MainActivity.root(), "pad_" + profName + (land ? "_land" : "") + ".txt"); }

    private void applyDefaults() {
        for (int i = 0; i < nC; i++) {
            fx[i] = (Float) prof[i][3]; fy[i] = (Float) prof[i][4]; sc[i] = (Float) prof[i][5];
            if (land) for (Object[] l : LAND)
                if (l[0].equals(prof[i][0])) { fx[i] = (Float) l[1]; fy[i] = (Float) l[2]; }
        }
    }

    /** 물리 패드 모드 — 게임 버튼·십자를 그리지도 받지도 않는다. 메뉴 알약과 유틸 바만 남는다. */
    public void setPhysicalMode(boolean on) {
        if (physical == on) return;
        physical = on;
        if (on) { mask = 0; dpadPid = -1; dpadMask = 0; dpadLast = 0; if (listener != null) listener.onMask(0); }
        invalidate();
    }
    public void toggleBar() { barOpen = !barOpen; invalidate(); }

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
        boolean l = w > h;
        if (l != land) {                          /* 회전 — 그 방향의 배치 파일/기본값으로 */
            land = l;
            if (fx != null) { applyDefaults(); load(); }
        }
        float padH = h * 0.44f;
        dpadR = Math.min(w * 0.17f, padH * 0.36f);
        btnR = Math.min(w * 0.082f, padH * 0.17f);
        layoutBar(w, h);
    }

    private void layoutBar(int w, int h) {
        float uw = w * 0.104f, uh = h * 0.056f, gap = w * 0.006f;   /* 너무 작다는 제보 — 키움 */
        int vis = 0;
        for (int i = 0; i < util.length; i++) if (utilVisible(i)) vis++;
        /* 바는 「메뉴」 버튼 바로 아래 — 버튼이 커지면서 겹치던 것을 층으로 분리 */
        float total = uw * vis + gap * (vis - 1), x = (w - total) / 2f, y = h * 0.052f;
        for (int i = 0; i < util.length; i++) {
            if (!utilVisible(i)) { util[i].setEmpty(); continue; }   /* 빈 칸 = 히트도 없다 */
            util[i].set(x, y, x + uw, y + uh); x += uw + gap;
        }
        /* 메뉴 버튼 — [≡] 실핸들이 너무 작다는 제보. 항상 보이는 알약 버튼으로. */
        barHandle.set(w * 0.42f, 0, w * 0.58f, h * 0.046f);
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

        /* (기둥 아트 틀은 뺐다 — 콘텐츠 없이 자리만 차지한다는 제보. 화면은 「키」 편집에서
           직접 끌어 옮기고 크기를 조절한다) */

        if (edit && !screenBox.isEmpty()) {   /* 편집 모드: 게임 화면 상자 — 끌어서 이동 */
            line.setColor(selScreen ? 0xccffcc44 : 0x8844ccff);
            c.drawRect(screenBox, line);
            text.setTextSize(h * 0.020f);
            c.drawText("화면" + (selScreen ? " (선택됨 — [－][＋]로 크기)" : ""),
                    screenBox.centerX(), screenBox.top + h * 0.028f, text);
        }

        int btnColorIdx = 0;
        if (!physical || edit) for (int i = 0; i < nC; i++) {
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
            } else if (b == -4) {                            /* 목록으로 나가기 */
                float r = radOf(i);
                fill.setColor(0x33ffffff);
                c.drawCircle(cx(i), cy(i), r, fill);
                text.setTextSize(r * 0.42f);
                c.drawText((String) prof[i][1], cx(i), cy(i) + r * 0.16f, text);
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

        fill.setColor(barOpen || edit ? 0x55ffffff : 0x30ffffff);
        c.drawRoundRect(barHandle, 14, 14, fill);
        text.setTextSize(barHandle.height() * 0.52f);
        c.drawText(barOpen || edit ? "\uba54\ub274 \u25b4" : "\uba54\ub274 \u25be",
                barHandle.centerX(), barHandle.bottom - barHandle.height() * 0.30f, text);
        if (barOpen || edit) {
            fill.setColor(0x22ffffff);
            text.setTextSize(util[0].height() * 0.5f);
            for (int i = 0; i < util.length; i++) {
                if (util[i].isEmpty()) continue;                     /* 이 프로필에 없는 기능 */
                if (i == UTIL_EDIT && edit) fill.setColor(0x66ffcc44);
                c.drawRoundRect(util[i], 10, 10, fill);
                if (i == UTIL_EDIT && edit) fill.setColor(0x22ffffff);
                c.drawText(utilLabel[i], util[i].centerX(),
                        util[i].centerY() + util[i].height() * 0.18f, text);
            }
        }

        if (edit) {
            text.setTextSize(getHeight() * 0.019f);
            c.drawText("편집: 버튼·게임화면 끌어서 이동 · 잡고 [－][＋]로 크기 · 「키」로 저장",
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

    private int exitIndex() {
        for (int i = 0; i < nC; i++) if (bitOf(i) == -4) return i;
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
        /* 물리 패드 모드: 눌림(DOWN)만 메뉴 알약·유틸 바용으로 받고 나머지 터치는 버린다 */
        if (physical && !edit && act != MotionEvent.ACTION_DOWN && act != MotionEvent.ACTION_POINTER_DOWN)
            return true;

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
                if (minus.contains(x, y)) {
                    if (selScreen) { if (listener != null) listener.onScreenScale(-5); }
                    else if (selIdx >= 0) sc[selIdx] = Math.max(0.6f, sc[selIdx] - 0.1f);
                    invalidate(); return true;
                }
                if (plus.contains(x, y)) {
                    if (selScreen) { if (listener != null) listener.onScreenScale(+5); }
                    else if (selIdx >= 0) sc[selIdx] = Math.min(1.8f, sc[selIdx] + 0.1f);
                    invalidate(); return true;
                }
                int ci = nearestControl(x, y);
                if (ci >= 0) {
                    dragIdx = ci; selIdx = ci; selScreen = false;
                    dragPid = e.getPointerId(idx); invalidate();
                } else if (screenBox.contains(x, y)) {   /* 화면 상자 잡기 */
                    selScreen = true; selIdx = -1; dragScreen = true;
                    dragPid = e.getPointerId(idx); lastSX = x; lastSY = y; invalidate();
                }
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
            if (physical && !edit) return true;       /* 버튼이 안 보이니 게임 입력도 안 받는다 */
            int fi = ffIndex();
            if (fi >= 0 && dist(x, y, cx(fi), cy(fi)) < radOf(fi) * 1.3f) {
                ffDown = true; ffPid = e.getPointerId(idx);
                if (listener != null) listener.onTurbo(true);
                invalidate();
                return true;
            }
            /* 「목록」 키 — 고르는 창으로 나간다. 나갈 때 오토세이브가 걸리므로
               잘못 눌러도 이어하기로 바로 복귀된다. */
            int xi = exitIndex();
            if (xi >= 0 && dist(x, y, cx(xi), cy(xi)) < radOf(xi) * 1.3f) {
                if (listener != null) listener.onAction(ACT_PICK);
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
            } else if (act == MotionEvent.ACTION_MOVE && dragScreen) {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (e.getPointerId(i) != dragPid) continue;
                    float x = e.getX(i), y = e.getY(i);
                    if (listener != null)
                        listener.onScreenDrag((x - lastSX) / getWidth(), (y - lastSY) / getHeight());
                    lastSX = x; lastSY = y;
                }
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                dragIdx = -1;
                if (dragScreen) { dragScreen = false; if (listener != null) listener.onScreenDrop(); }
            } else if (act == MotionEvent.ACTION_POINTER_UP
                    && e.getPointerId(e.getActionIndex()) == dragPid) {
                dragIdx = -1;
                if (dragScreen) { dragScreen = false; if (listener != null) listener.onScreenDrop(); }
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
