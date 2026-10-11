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
        /** 메뉴(상단바)나 배치가 열리고 닫힐 때 — 열려 있는 동안 게임을 멈춘다(Delta 처럼 «멈춤 메뉴»). */
        void onMenu(boolean open);
    }

    /* 게임 화면의 현재 자리 — EmuActivity 가 배치 때마다 알려 준다 (편집 상자용) */
    private final RectF screenBox = new RectF();
    private boolean selScreen = false, dragScreen = false;
    private float lastSX, lastSY;
    public void setScreenBox(float l, float t, float r, float b) {
        screenBox.set(l, t, r, b);
        if (edit) invalidate();
    }

    public static final int ACT_BAND = 9;   /* 코어 옵션을 게임 중에 뒤집는 칸 */
    /* ACT_SIDES(기둥) 는 2026-09-07 폐기 — 코어에서 빠지는 기능을 화면에 남기지 않는다. */
    public static final int ACT_QUIT = 11;                    /* 앱 종료 — 「목록」(ACT_PICK)과 달라야 한다(유저 2026-09-05) */
    public static final int ACT_FRAMEGEN = 12;                /* 앱 프레임 생성 끔→움직임→섞기 */
    public static final int ACT_COREFG = 13;                  /* 코어 프레임 생성(사무쇼2) 켬/끔 */
    public static final int ACT_UNDO = 14;                    /* 되돌리기 — 불러오기·리셋 직전 자리로(5초 동안 칩) */
    public static final int ACT_QSAVE = 15;                   /* 빠른 저장 — 메뉴 알약 길게 누르기 */
    public static final int ACT_LAYOUT_DEFAULT = 16;          /* 배치 「처음대로」 — 게임 화면 자리도 자동 맞춤으로 */
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
        { "TECH", "기술", 11, 0.90f, 0.60f, 1.25f },
        { "AB",   "A+B", 10, 0.10f, 0.59f, 0.95f },
        { "OPT",  "",     -2, 0.50f, 0.955f, 1.00f },
        { "FF",   "▶▶",  -3, 0.94f, 0.17f, 0.80f },
    };
    private static final Object[][] P_SS2 = {
        { "DPAD", "",     -1, 0.22f, 0.76f, 1.00f },
        { "A",    "A",     0, 0.66f, 0.80f, 1.10f },
        { "B",    "B",     8, 0.88f, 0.72f, 1.10f },
        { "SP",   "SP",    9, 0.90f, 0.56f, 1.20f },
        { "AB",   "A+B",   1, 0.10f, 0.59f, 0.95f },
        { "OPT",  "",     -2, 0.50f, 0.955f, 1.00f },
        { "FF",   "▶▶",  -3, 0.94f, 0.17f, 0.80f },
    };
    /* KOF R-2 — SP 엔진(설정 「KOF 원버튼」 켬): R(비트11)=SP, 탭=약/홀드=강(문턱 6프레임,
       코어가 잰다 — SVC 의 12 와 다르다, 이식소 실측). L(비트10)=A+B. 슬롯은 잡은 방향:
       방향없음=장풍 · 앞=대공 · 앞아래=초필살기 · 공중 가능.
       ★ 엔진을 끄면 R 은 «아무 일도 안 한다» — 코어가 A+B 로 접지 않는다(이식소 실측).
       A+B 는 켬·끔 무관하게 언제나 L 이다. 월화도 같은 계약을 쓴다. */
    private static final Object[][] P_KOF = {
        { "DPAD", "",     -1, 0.22f, 0.76f, 1.00f },
        { "A",    "A",     0, 0.66f, 0.80f, 1.10f },
        { "B",    "B",     8, 0.88f, 0.72f, 1.10f },
        { "SP",   "SP",   11, 0.90f, 0.56f, 1.20f },
        { "AB",   "A+B",  10, 0.10f, 0.59f, 0.95f },
        { "OPT",  "",     -2, 0.50f, 0.955f, 1.00f },
        { "FF",   "▶▶",  -3, 0.94f, 0.17f, 0.80f },
    };
    /* 순정 NGPC — 원버튼 엔진이 없는 게임(메탈슬러그 등). NGP 실기 그대로 A·B 두 개만.
       기술·강약 버튼을 여기 두면 안 나가는 버튼이 화면만 차지한다는 제보로 분리했다. */
    private static final Object[][] P_NGP = {
        { "DPAD", "",     -1, 0.22f, 0.76f, 1.00f },
        { "A",    "A",     0, 0.68f, 0.80f, 1.15f },
        { "B",    "B",     8, 0.88f, 0.70f, 1.15f },
        { "OPT",  "",     -2, 0.50f, 0.955f, 1.00f },
        { "FF",   "▶▶",  -3, 0.94f, 0.17f, 0.80f },
    };
    private static final int[] BTN_COL_ON  = { 0x8866aaff, 0x88ff5566, 0x884477cc, 0x88cc3344, 0x88ffcc44, 0x8899eeaa };
    private static final int[] BTN_COL_OFF = { 0x4466aaff, 0x44ff5566, 0x444477cc, 0x44cc3344, 0x44ffcc44, 0x4499eeaa };

    /* 버튼 아트(PadSkin) — 설정 「버튼 모양」 아트/단순. 단순 = 예전 반투명 도형 그대로 */
    private final PadSkin skin = new PadSkin();
    private boolean art = true;
    /** 아트를 켜고 끈다. 덮어쓰기 그림(design/skin/*.png)도 이때 다시 읽는다. */
    public void setArt(boolean on) {
        art = on;
        skin.reloadUser();
        skin.clear();
        invalidate();
    }
    /** 버튼 고유색 — 이름으로 정한다(프로필이 달라도 A 는 늘 같은 색). */
    private static int hueOf(Object name) {
        if ("A".equals(name) || "WP".equals(name)) return 0xff3f6fd1;   /* 청 */
        if ("B".equals(name) || "WK".equals(name)) return 0xffd13f4f;   /* 홍 */
        if ("SP".equals(name) || "TECH".equals(name)) return 0xffe0a43a; /* 호박 — 금테 */
        if ("AB".equals(name)) return 0xff3f9e7c;                       /* 비취 */
        return 0xff5a5e6a;
    }

    private Object[][] prof = P_SVC;
    private String profName = "svc";
    private boolean svcPlaceholders = true;
    /* 강P·강K 버튼은 2026-09-06 에 걷어냈다 — 코어에서 강약 구분이 통째로 빠져
       기본기가 게임 원판정(탭=약 / 꾹=강)으로 돌아왔다. 눌러도 안 되는 버튼은 두지 않는다.
       옛 배치 파일에 SP_P/SP_K 줄이 남아 있어도 이름으로 찾으므로 남은 자리는 안 깨진다. */
    private float[] fx, fy, sc;
    private int nC;

    private float dpadR, btnR;
    private final RectF opt = new RectF();
    /* 상단바 칸 — 2026-10-10 유저 「버튼이나 전반적 인터페이스 좀 손봐 줘, 뺄 거 빼고」 로 정리:
         상태(슬롯·저장·로드·리셋) · 설정·배치 · 나가기(목록·종료). 한 줄로 들어가면 한 줄(폴드 큰 화면), 좁으면 두 줄.
       뺀 것: 샷(폰 화면 캡처로 충분) · 띠·앱보간·코어120 즉석 토글(설정에 있음, 실험용이었다) ·
       화면 구석의 둥근 「목록」 버튼(메뉴 칸과 겹쳐 그려졌다 — 이제 메뉴 안 「목록」) */
    private final RectF[] util = new RectF[8];
    /* 「종료」= 게임을 닫고 고르는 창으로 (제보: 「롬」은 사실 종료 버튼인데 이름이 달랐다).
       「배치」= 버튼 자리·크기 + 게임 화면 상자까지 한꺼번에 편집(제보: 「키」란 이름이 좁았다). */
    /* 세 무리로 묶어 둔다 — 두 줄로 접힐 때 무리가 갈리지 않게 순서가 곧 배치다.
         ① 상태  슬롯·저장·로드·샷·리셋
         ② 이 게임(코어 기능)  띠 — 게임마다 있는 것만 칸이 생긴다 (기둥은 2026-09-07 폐기)
         ③ 마무리  설정·배치·종료 */
    private final String[] utilLabel = { "슬롯 1", "저장", "로드", "리셋", "설정", "버튼 배치", "목록", "종료" };
    /* 칸 아래 작은 줄 — 저장 칸 «빈 칸/덮어쓰기», 로드 칸 «3분 전/없음». 불러오기 전에 무엇을 불러오는지 보이게(2026-10-11) */
    private final String[] utilSub = new String[8];
    private final int[] utilAct = { ACT_SLOT, ACT_SAVE, ACT_LOAD, ACT_RESET, ACT_CFG, 0, ACT_PICK, ACT_QUIT };
    private static final int UTIL_EDIT = 5;   /* 「배치」 칸 = 편집 토글 (액션이 아니다) */
    private boolean hasCoreFg = false;
    /* 이 게임이 코어에서 쓰는 기능 — 게임별 칸은 여기서만 생긴다.
       전에는 「이 게임에 그 기능이 있나」를 패드 프로필 모양(prof == P_SS2)으로 판단했다.
       게임 표에 이미 있는 사실을 모양으로 되짚은 것이라 진실이 두 벌이 됐다.
       이제 EmuActivity 가 게임 표를 읽어 알려 준다. */
    private boolean hasBand = false;
    private final RectF labPlate = new RectF();    /* 배치 모드 「화면」 이름표 판 */
    private final RectF hintPlate = new RectF();   /* 배치 모드 안내 글 뒤 어두운 판 */
    private static final String[] HINT_ONE = { "끌어서 옮기기 · 두 손가락·[－][＋]로 크기 · 「배치」로 저장" };
    private static final String[] HINT_TWO = { "끌어서 옮기기 · 두 손가락·[－][＋]로 크기", "「배치」를 다시 누르면 저장" };
    private float barBottom = 0;   /* 두 줄로 접히므로 편집 안내문은 «마지막 줄» 아래에 놓는다 */
    private final RectF minus = new RectF(), plus = new RectF();
    private final RectF barHandle = new RectF();
    private boolean barOpen = false;   /* 상단바는 기본 접힘 — [≡]로 여닫는 순수 토글 */

    private int mask = 0;
    private boolean edit = false;
    private boolean land = false;      /* 가로 화면 — 배치 파일과 기본 좌표가 따로다 */
    private boolean physical = false;  /* 물리 패드가 붙어 있나 (지금은 화면을 안 바꾼다) */
    /* ★ 2026-09-06 유저: 「터치랑 패드입력은 둘다되도록해 왜 터치를 잠그냐」.
       예전엔 물리 패드가 붙으면 화면 버튼을 그리지도 받지도 않았다.
       물리 패드는 «더해지는» 것이지 «갈아타는» 것이 아니다 — 둘 다 살린다.
       배관은 남겨 둔다. 「패드 붙으면 버튼 숨겨 달라」가 다시 나오면 이 상수만 되돌린다. */
    private static final boolean PHYSICAL_HIDES_TOUCH = false;
    private boolean hidesTouch() { return PHYSICAL_HIDES_TOUCH && physical && !edit; }
    /* 가로 기본 좌표(이름별). 십자는 왼쪽 아래, 버튼 무리는 오른쪽 아래, 유틸은 양 위 구석. */
    private static final Object[][] LAND = {
        /* 가로에서 게임 상자는 가운데 약 43%(160:152 를 세로에 맞춤)를 차지하므로 오른쪽 무리는 72% 밖에 둔다 */
        { "DPAD", 0.13f, 0.64f }, { "WP", 0.78f, 0.82f }, { "WK", 0.90f, 0.72f },         { "TECH", 0.95f, 0.30f }, { "AB", 0.05f, 0.36f }, { "A", 0.80f, 0.78f },
        { "B", 0.92f, 0.62f }, { "SP", 0.94f, 0.36f }, { "OPT", 0.50f, 0.96f }, { "FF", 0.965f, 0.10f },
    };
    private int dragIdx = -1, dragPid = -1, selIdx = -1;
    /* 배치 모드 두 손가락 핀치 — 고른 상자(없으면 게임 화면)의 크기 (유저 2026-09-05 「두 손 드래그로 크기 변경」) */
    private boolean pinching = false; private float pinch0 = 0f, pinchBase = 1f; private int pinchSentPct = 0;
    private static float pinchDist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1); return (float) Math.sqrt(dx * dx + dy * dy);
    }
    private boolean ffDown = false;
    private int ffPid = -1;
    /* 십자 소유제 — 십자를 잡은 손가락은 화면 어디로 흘러도 십자를 놓지 않는다.
       (구판: 정사각 판정 + 축 임계값 — 대각이 과대하고, 박스 밖으로 새면 뚝 끊겼다) */
    private int dpadPid = -1;
    private int dpadMask = 0, dpadLast = 0;
    /* ★★ 십자키 «단일 출처» — 판정과 그리기가 이 표 하나를 같이 본다.
       따로 두면 오늘 고친 병(그림과 판정이 다르다)이 그대로 다시 난다.
       DPAD_HALF = 정방향 반폭(도). 대각 반폭은 남는 각을 넷으로 나눠 자동으로 정해진다.
       48/42 → 54/36 으로 넓혔다: ← 홀드 가드가 ↙ 로 새는 것을 줄인다(이식소 권고).
       각은 «수학 기준»(반시계, 0=오른쪽, 90=위). 화면각 = −수학각이다. */
    private static final float DPAD_HALF = 27f;                 /* 정방향 54도 */
    private static float diagHalf() { return (360f - 8f * DPAD_HALF) / 8f; }   /* = 18도 */
    /* 여덟 갈래의 «중심각». 0·2·4·6 이 정방향, 1·3·5·7 이 대각이다. */
    private static final float[] DPAD_CENTER = { 0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f };
    private static float halfOf(int k) { return (k % 2 == 0) ? DPAD_HALF : diagHalf(); }
    /** 갈래 k 가 내는 비트. */
    private static int dpadBits(int k) {
        switch (k) {
            case 0: return 1 << Emu.RIGHT;
            case 1: return (1 << Emu.RIGHT) | (1 << Emu.UP);
            case 2: return 1 << Emu.UP;
            case 3: return (1 << Emu.LEFT) | (1 << Emu.UP);
            case 4: return 1 << Emu.LEFT;
            case 5: return (1 << Emu.LEFT) | (1 << Emu.DOWN);
            case 6: return 1 << Emu.DOWN;
            default: return (1 << Emu.RIGHT) | (1 << Emu.DOWN);
        }
    }

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
    public void setSlotLabel(int n) { utilLabel[0] = "슬롯 " + n; invalidate(); }
    /** 지금 슬롯의 저장 상태 — 저장 칸·로드 칸 아래 줄 */
    public void setSlotInfo(String saveSub, String loadSub) { utilSub[1] = saveSub; utilSub[2] = loadSub; invalidate(); }
    private String cellLabel(int i) { return utilSub[i] == null ? utilLabel[i] : utilLabel[i] + "\n" + utilSub[i]; }

    /* ── 되돌리기 칩 — 불러오기·리셋은 확인창 대신 «되돌리기»(애플 HIG: 자주 쓰는 동작은 묻지 말고 되돌릴 수 있게).
       메뉴 알약 바로 아래에 5초 동안 뜨고, 누르면 그 직전 자리로 돌아간다. ── */
    private static final long UNDO_MS = 5000;
    private final RectF undoChip = new RectF();
    private long undoUntil = 0;
    private final Runnable undoExpire = new Runnable() { @Override public void run() { undoUntil = 0; invalidate(); } };
    private boolean undoLive() { return android.os.SystemClock.uptimeMillis() < undoUntil; }
    public void offerUndo() {
        undoUntil = android.os.SystemClock.uptimeMillis() + UNDO_MS;
        removeCallbacks(undoExpire); postDelayed(undoExpire, UNDO_MS + 30); invalidate();
    }

    /* ── 메뉴 열림을 EmuActivity 에 알린다(게임 멈춤) — barOpen·edit 가 바뀌는 모든 길에서 부른다 ── */
    private boolean menuShown = false;
    private void menuChanged() {
        boolean open = barOpen || edit;
        if (open == menuShown) return;
        menuShown = open;
        if (listener != null) listener.onMenu(open);
    }

    /* ── 메뉴 알약 길게 누르기 = 빠른 저장(Delta 의 메뉴 버튼 제스처). 짧게 = 여닫기 ── */
    private static final long HOLD_MS = 450;
    private int handlePid = -1;
    private boolean handleFired = false;
    private final Runnable handleHold = new Runnable() { @Override public void run() {
        if (handlePid < 0) return;
        handleFired = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (listener != null) listener.onAction(ACT_QSAVE);
    } };

    /* ── 배치 「처음대로」 — 두 번 눌러야(2.5초 안) 버튼 자리·크기와 게임 화면 자리를 처음으로 ── */
    private final RectF resetBtn = new RectF();
    private long resetArmUntil = 0;
    /** (옛 「앱보간」·「코어120」 칸 — 2026-10-10 상단바에서 뺐다. 부르는 쪽이 남아 있어도 아무 일 없게) */
    public void setFrameGenLabel(String s) { }
    public void setCoreFg(boolean has, String label) { hasCoreFg = has; }

    /** "ss2"·"svc"·"ngp" — 롬 헤더로 EmuActivity 가 정한다 */
    /** 화면에 전용 강P·강K 버튼을 둘 것인가 — 「SVC 강약 버튼 구분」과 짝(EmuActivity 가 알려 준다). */
    /** 화면에 적을 글자. 강약 구분을 끄면 강 버튼이 사라지므로 「약P·약K」의 «약» 이
     *  가리킬 짝이 없다 — 그때는 그 둘이 곧 A·B 다(탭=약, 꾹=강). 유저 지적 2026-09-06. */
    private String label(int i) {
        Object nm = prof[i][0];
        if (false) {
            if ("WP".equals(nm)) return "A";
            if ("WK".equals(nm)) return "B";
        }
        return (String) prof[i][1];
    }

    /** 이 컨트롤을 지금 화면에 두는가. 숨긴 것은 그리지도, 누르지도, 편집에서 잡히지도 않는다. */
    private boolean ctrlVisible(int i) {
        return true;   /* 숨기는 버튼이 없다 — 강P·강K 를 걷어낸 뒤로(2026-09-06) */
    }

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

    /** 이 게임이 코어에서 쓰는 기능을 알려 준다 — 상단바의 게임별 칸이 이걸 보고 생긴다. */
    public void setCoreFeatures(boolean band) {
        hasBand = band;
        if (getWidth() > 0) layoutBar(getWidth(), getHeight());
        invalidate();
    }

    /** 상단바 i번 칸이 이 게임에 존재하는가 — 지금은 모든 칸이 모든 게임에 있다(게임별 칸은 설정으로 옮겼다). */
    private boolean utilVisible(int i) { return i >= 0 && i < util.length; }

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
        if (on && PHYSICAL_HIDES_TOUCH) {
            mask = 0; dpadPid = -1; dpadMask = 0; dpadLast = 0;
            if (ffDown) { ffDown = false; ffPid = -1; if (listener != null) listener.onTurbo(false); }   /* 배속 고착 방지(리뷰 F2) */
            if (listener != null) listener.onMask(0);
        }
        invalidate();
    }
    public void toggleBar() { barOpen = !barOpen; if (barOpen) barSel = firstVisible(); menuChanged(); invalidate(); }
    public boolean isBarOpen() { return barOpen || edit; }

    /* ── 유틸 바 패드 조작 — 물리 패드만 있는 게임기에서 저장·로드·설정·종료에 닿는 길(리뷰 F17) ── */
    private int barSel = -1;               /* 패드 커서가 선 칸(-1 = 없음). 터치로 열면 -1 이라 하이라이트가 없다 */
    private int firstVisible() { for (int i = 0; i < util.length; i++) if (utilVisible(i)) return i; return -1; }
    /** 좌우 이동 — 보이는 칸만 돌며 끝에서 반대편으로 감는다. */
    public void barMove(int d) {
        if (!(barOpen || edit)) return;
        int n = util.length, i = barSel < 0 ? (d > 0 ? -1 : n) : barSel;
        for (int k = 0; k < n; k++) {
            i = ((i + d) % n + n) % n;
            if (utilVisible(i)) { barSel = i; break; }
        }
        invalidate();
    }
    /** 선 칸 실행 — 터치로 누른 것과 같은 길(「배치」 칸은 편집 토글). */
    public void barActivate() {
        if (!(barOpen || edit) || barSel < 0 || !utilVisible(barSel)) return;
        if (barSel == UTIL_EDIT) {
            edit = !edit;
            if (!edit) save();
            mask = 0; dragIdx = -1; selIdx = -1; dpadPid = -1; dpadMask = 0; dpadLast = 0;
            if (listener != null) listener.onMask(0);
        } else {
            if (listener != null) listener.onAction(utilAct[barSel]);
            if (utilAct[barSel] == ACT_PICK || utilAct[barSel] == ACT_QUIT) barOpen = false;
        }
        menuChanged();
        invalidate();
    }
    public void barClose() { barOpen = false; barSel = -1; menuChanged(); invalidate(); }

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
            if (edit && fx != null) save();       /* 편집 중이던 배치는 지금 방향 파일에 먼저 적는다(리뷰 F7) */
            land = l;
            selIdx = -1; dragIdx = -1; dragPid = -1; selScreen = false; dragScreen = false;
            if (fx != null) { applyDefaults(); load(); }
        }
        float padH = h * 0.44f;
        dpadR = Math.min(w * 0.17f, padH * 0.36f);
        btnR = Math.min(w * 0.082f, padH * 0.17f);
        layoutBar(w, h);
        skin.clear();                             /* 크기별로 구운 그림 — 새 크기로 다시 */
    }

    /** 메뉴 알약 높이 — 짧은 변 4.6% 또는 30dp 중 큰 것. EmuActivity.handleH 와 같은 공식(게임 화면을 그 아래에 둔다). */
    static float handleH(float w, float h, float dp) { return Math.max(Math.min(w, h) * 0.046f, 30 * dp); }
    private float handleH(float w, float h) { return handleH(w, h, getResources().getDisplayMetrics().density); }
    /** 알약을 누르는 자리 — 그림보다 넓게(애플 HIG 44pt·안드로이드 48dp). 메뉴가 열려 있으면 아래 칸과 겹치지 않게 그림 높이까지만 */
    private boolean handleHit(float x, float y) {
        float dp = getResources().getDisplayMetrics().density;
        float bottom = (barOpen || edit) ? barHandle.bottom : Math.max(barHandle.bottom, 48 * dp);
        return y >= 0 && y <= bottom && x >= barHandle.left - 10 * dp && x <= barHandle.right + 10 * dp;
    }

    /** 상단바 배치 — 한 줄에 칸마다 52dp 이상 들어가면 한 줄(폴드 큰 화면·가로), 아니면 두 줄로 접는다(덮개 화면).
     *  바는 「메뉴」 알약 바로 아래. 열려 있는 동안 칸 뒤에 어두운 판을 깔아 게임 그림 위에서도 글자가 읽히게 한다. */
    private final RectF barPlate = new RectF();
    private void layoutBar(int w, int h) {
        float base = Math.min(w, h);              /* 가로에서도 짧은 변 기준 — 알약·유틸 칸이 반토막 나지 않게(리뷰 F12) */
        float dp = getResources().getDisplayMetrics().density;
        float uh = Math.max(base * 0.050f, 34 * dp), gap = Math.max(w * 0.006f, 4 * dp), gapY = base * 0.010f;
        int vis = 0;
        for (int i = 0; i < util.length; i++) if (utilVisible(i)) vis++;
        /* 가로에서는 양 끝 10% 를 비운다 — 오른쪽 위 구석 「▶▶」(배속)이 열린 바 끝 칸과 겹치지 않게(폴드 큰 화면 가로처럼 정사각에 가까울 때) */
        float rowW = w * (w > h ? 0.80f : 0.96f);
        int rows = (rowW - gap * (vis - 1)) / vis >= 52 * dp ? 1 : 2;
        int perRow = (vis + rows - 1) / rows;
        float maxw = (w > h) ? base * 0.16f : w * 0.22f;
        float uw = Math.min(maxw, (rowW - gap * (perRow - 1)) / perRow);
        float hh = handleH(w, h);
        float y = Math.max(base * 0.052f, hh + 4 * dp);
        int nThis = Math.min(perRow, vis), placed = 0, inRow = 0;
        float x = (w - (uw * nThis + gap * (nThis - 1))) / 2f;
        float left = x;
        for (int i = 0; i < util.length; i++) {
            if (!utilVisible(i)) { util[i].setEmpty(); continue; }   /* 빈 칸 = 히트도 없다 */
            util[i].set(x, y, x + uw, y + uh); x += uw + gap; placed++; inRow++;
            if (inRow == perRow && placed < vis) {                    /* 줄 바꿈 — 남은 만큼으로 가운데 맞춤 */
                inRow = 0; y += uh + gapY;
                nThis = Math.min(perRow, vis - placed);
                x = (w - (uw * nThis + gap * (nThis - 1))) / 2f;
            }
        }
        barBottom = y + uh;
        float pad = gap * 1.5f;
        barPlate.set(left - pad, Math.max(base * 0.052f, hh + 4 * dp) - pad, w - left + pad, barBottom + pad);
        /* 메뉴 버튼 — [≡] 실핸들이 너무 작다는 제보. 항상 보이는 알약 버튼으로. */
        /* 메뉴 알약 — 덮개 화면에서 높이 16dp 라 누르기 힘들었다. 최소 30dp·폭 96dp(그림), 누르는 자리는 더 넓게(handleHit) */
        float hw = Math.max(base * 0.16f, 96 * dp);
        barHandle.set(w * 0.5f - hw / 2f, 0, w * 0.5f + hw / 2f, hh);
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
            /* 이름표는 상자 «아래쪽» 안에 — 위쪽은 화면 자동 맞춤으로 열린 메뉴 줄·안내 판 밑에 깔린다(찍은 판 확인).
               게임 그림 위에서도 읽히게 어두운 판. 크기 조절 방법은 위 안내 판에 있으니 여기선 «선택됨» 만. */
            String lab = selScreen ? "화면 (선택됨)" : "화면";
            float ts = h * 0.018f, pad = ts * 0.4f;
            text.setTextSize(ts);
            float tw = text.measureText(lab), by = screenBox.bottom - pad - ts * 0.35f;
            labPlate.set(screenBox.centerX() - tw / 2f - pad, by - ts - pad * 0.5f,
                    screenBox.centerX() + tw / 2f + pad, by + ts * 0.3f + pad * 0.5f);
            fill.setColor(0xc0101014);
            c.drawRoundRect(labPlate, pad, pad, fill);
            c.drawText(lab, screenBox.centerX(), by, text);
        }

        int btnColorIdx = 0;
        if (!hidesTouch()) for (int i = 0; i < nC; i++) {
            if (!ctrlVisible(i)) continue;
            int b = bitOf(i);
            if (art && drawArt(c, i, b)) {
                /* 아트로 그렸다 */
            } else if (b == -1) {                                   /* 십자 */
                /* 유저: 「디패드는 판정박스를 보여줄 필요 없음」 — 여덟 갈래로 그려 봤다가
                   되돌렸다(2026-09-06). 그래서 «그리는 모양»과 «걸리는 각»이 일부러 다르다.
                   걸리는 각은 DPAD_CENTER/halfOf() 표 하나에서만 온다 — 아래 그림은 표식이다. */
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
                float base = Math.min(getWidth(), getHeight());
                float ow2 = base * 0.10f * sc[i], oh2 = base * 0.021f * sc[i];
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
                c.drawText(label(i), cx(i), cy(i) + r * 0.20f, text);
            } else if (b == -4) {                            /* 목록으로 나가기 */
                float r = radOf(i);
                fill.setColor(0x33ffffff);
                c.drawCircle(cx(i), cy(i), r, fill);
                text.setTextSize(r * 0.42f);
                c.drawText(label(i), cx(i), cy(i) + r * 0.16f, text);
            } else {                                         /* 게임 버튼 */
                float r = radOf(i);
                int ci = btnColorIdx % BTN_COL_ON.length; btnColorIdx++;
                fill.setColor(bit(b) ? BTN_COL_ON[ci] : BTN_COL_OFF[ci]);
                c.drawCircle(cx(i), cy(i), r, fill);
                text.setTextSize(r * 0.52f);
                c.drawText(label(i), cx(i), cy(i) + r * 0.19f, text);
            }
            if (edit && i == selIdx) {
                line.setColor(0xccffcc44);
                c.drawCircle(cx(i), cy(i), radOf(i) + 8, line);
            }
        }

        if (barOpen || edit) {                       /* 칸 뒤 어두운 판 — 게임 그림 위에서도 칸이 또렷하게 */
            fill.setColor(0xb0101014);
            float pr = Math.min(barPlate.height() * 0.25f, 18f * getResources().getDisplayMetrics().density);
            c.drawRoundRect(barPlate, pr, pr, fill);
        }
        /* 알약 글 = 지금 누르면 일어나는 일 — 닫힘 «메뉴», 열림(게임 멈춤) «이어하기», 배치 중 «배치 중» */
        String handleLabel = edit ? "배치 중" : barOpen ? "이어하기 \u25b8" : "\uba54\ub274 \u25be";
        if (art) {
            skin.pill(c, "menu", barHandle, false, barOpen || edit, 1, handleLabel);
        } else {
        fill.setColor(barOpen || edit ? 0x55ffffff : 0x30ffffff);
        c.drawRoundRect(barHandle, 14, 14, fill);
        text.setTextSize(barHandle.height() * 0.52f);
        c.drawText(handleLabel, barHandle.centerX(), barHandle.bottom - barHandle.height() * 0.30f, text);
        }
        /* 되돌리기 칩 — 불러오기·리셋 뒤 5초. 메뉴가 열려 있으면 숨긴다(칸과 겹치지 않게) */
        if (undoLive() && !(barOpen || edit)) {
            float base = Math.min(w, getHeight()), dpx = getResources().getDisplayMetrics().density;
            float chH = Math.max(base * 0.050f, 38 * dpx);
            text.setTextSize(chH * 0.42f);
            String ul = "되돌리기";
            float chW = text.measureText(ul) + chH * 1.2f;
            float top = barHandle.bottom + base * 0.012f;
            undoChip.set(w / 2f - chW / 2f, top, w / 2f + chW / 2f, top + chH);
            fill.setColor(0xe6141418);
            c.drawRoundRect(undoChip, chH / 2f, chH / 2f, fill);
            line.setColor(0xffd9a441);
            c.drawRoundRect(undoChip, chH / 2f, chH / 2f, line);
            int keep = text.getColor();
            text.setColor(0xfff3dfb2);
            c.drawText(ul, undoChip.centerX(), undoChip.centerY() + chH * 0.15f, text);
            text.setColor(keep);
        } else undoChip.setEmpty();
        if ((barOpen || edit) && art) {
            for (int i = 0; i < util.length; i++) {
                if (util[i].isEmpty()) continue;
                boolean hl = (i == UTIL_EDIT && edit) || i == barSel;
                skin.pill(c, "bar", util[i], false, hl, 2, cellLabel(i));
            }
        } else if (barOpen || edit) {
            fill.setColor(0x22ffffff);
            text.setTextSize(util[0].height() * 0.5f);
            for (int i = 0; i < util.length; i++) {
                if (util[i].isEmpty()) continue;                     /* 이 프로필에 없는 기능 */
                boolean hl = (i == UTIL_EDIT && edit) || i == barSel;   /* 패드 커서 칸도 같은 강조색 */
                if (hl) fill.setColor(0x66ffcc44);
                c.drawRoundRect(util[i], 10, 10, fill);
                if (hl) fill.setColor(0x22ffffff);
                if (utilSub[i] == null)
                    c.drawText(utilLabel[i], util[i].centerX(), util[i].centerY() + util[i].height() * 0.18f, text);
                else {                                                /* 두 줄 — 위 이름, 아래 작은 상태 */
                    float th = text.getTextSize();
                    text.setTextSize(th * 0.78f);
                    c.drawText(utilLabel[i], util[i].centerX(), util[i].centerY() - util[i].height() * 0.02f, text);
                    text.setTextSize(th * 0.52f);
                    c.drawText(utilSub[i], util[i].centerX(), util[i].centerY() + util[i].height() * 0.32f, text);
                    text.setTextSize(th);
                }
            }
        }

        if (edit) {
            /* 안내 — 화면 자동 맞춤으로 게임 그림이 위에 붙어 이 글이 그림 위에 얹힌다(흰 화면이면 안 보였다).
               어두운 판을 깔고, 오른쪽 위 ▶▶ 를 가리지 않게 가운데 72% 안에 넣는다 — 안 들어가면 두 줄, 그래도 넘치면 글자를 줄인다(덮개 화면). */
            float ts = getHeight() * 0.017f, lim = w * 0.72f;
            text.setTextSize(ts);
            String[] hint = text.measureText(HINT_ONE[0]) <= lim ? HINT_ONE : HINT_TWO;
            float tw = 0;
            for (String s : hint) tw = Math.max(tw, text.measureText(s));
            if (tw > lim) { ts *= lim / tw; text.setTextSize(ts); tw = lim; }
            float pad = ts * 0.45f, lh = ts * 1.35f, top = barBottom + getHeight() * 0.008f;
            hintPlate.set(w / 2f - tw / 2f - pad, top, w / 2f + tw / 2f + pad, top + lh * hint.length + pad * 2);
            fill.setColor(0xc0101014);
            c.drawRoundRect(hintPlate, pad, pad, fill);
            for (int k = 0; k < hint.length; k++)
                c.drawText(hint[k], w / 2f, top + pad + ts + lh * k, text);
            float bw = w * 0.10f, bh = getHeight() * 0.038f, byy = hintPlate.bottom + getHeight() * 0.010f;
            minus.set(w * 0.26f, byy, w * 0.26f + bw, byy + bh);
            resetBtn.set(w * 0.41f, byy, w * 0.59f, byy + bh);
            plus.set(w * 0.64f, byy, w * 0.64f + bw, byy + bh);
            boolean armed = android.os.SystemClock.uptimeMillis() < resetArmUntil;
            fill.setColor(0xe0202020);                       /* 불투명 — 게임 화면 위에서도 버튼으로 보이게 */
            c.drawRoundRect(minus, 10, 10, fill);
            c.drawRoundRect(plus, 10, 10, fill);
            if (armed) fill.setColor(0xe0402a10);
            c.drawRoundRect(resetBtn, 10, 10, fill);
            line.setColor(0xccffffff);
            c.drawRoundRect(minus, 10, 10, line);
            c.drawRoundRect(plus, 10, 10, line);
            if (armed) line.setColor(0xffd9a441);
            c.drawRoundRect(resetBtn, 10, 10, line);
            text.setTextSize(bh * 0.6f);
            c.drawText("－", minus.centerX(), minus.centerY() + bh * 0.2f, text);
            c.drawText("＋", plus.centerX(), plus.centerY() + bh * 0.2f, text);
            String rl = armed ? "한 번 더" : "처음대로";
            text.setTextSize(bh * 0.42f);
            float rw = text.measureText(rl);
            if (rw > resetBtn.width() * 0.86f) text.setTextSize(bh * 0.42f * resetBtn.width() * 0.86f / rw);
            c.drawText(rl, resetBtn.centerX(), resetBtn.centerY() + bh * 0.15f, text);
        }
    }

    /** 아트로 컨트롤 i 를 그린다. 못 그리는 종류면 false(단순 도형으로 떨어진다). */
    private boolean drawArt(Canvas c, int i, int b) {
        Object nm = prof[i][0];
        String key = String.valueOf(nm).toLowerCase();
        if (b == -1) {
            int dir = (bit(Emu.UP) ? 1 : 0) | (bit(Emu.DOWN) ? 2 : 0)
                    | (bit(Emu.LEFT) ? 4 : 0) | (bit(Emu.RIGHT) ? 8 : 0);
            skin.dpad(c, cx(i), cy(i), radOf(i), dir);
            return true;
        }
        if (b == -2) {                                   /* OPTION — 실기처럼 작은 알약 */
            float base = Math.min(getWidth(), getHeight());
            float ow2 = base * 0.10f * sc[i], oh2 = base * 0.021f * sc[i];
            opt.set(cx(i) - ow2, cy(i) - oh2, cx(i) + ow2, cy(i) + oh2);
            skin.pill(c, "opt", opt, bit(Emu.START), false, 0, "OPTION");
            return true;
        }
        if (b == -3) {
            skin.button(c, "ff", cx(i), cy(i), radOf(i), ffDown ? 0xffe0a43a : 0xff4a4e5a, ffDown, false, label(i));
            return true;
        }
        if (b == -4) {
            skin.button(c, "exit", cx(i), cy(i), radOf(i), 0xff4a4e5a, false, false, label(i));
            return true;
        }
        if (b >= 0) {
            boolean acc = "SP".equals(nm) || "TECH".equals(nm);
            skin.button(c, key, cx(i), cy(i), radOf(i), hueOf(nm), bit(b), acc, label(i));
            return true;
        }
        return false;
    }

    private int nearestControl(float x, float y) {
        int best = -1; float bd = 1e9f;
        for (int i = 0; i < nC; i++) {
            if (!ctrlVisible(i)) continue;
            float r = radOf(i) * 1.4f + 20f;
            float d = dist(x, y, cx(i), cy(i));
            if (d < r && d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private int hitButtons(float x, float y) {
        int m = 0;
        for (int i = 0; i < nC; i++) {
            if (!ctrlVisible(i)) continue;
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

    /* dirCenter()·isDiag() 는 뺐다 — 각도는 이제 DPAD_CENTER/halfOf() «한 곳»에서만 온다.
       두 벌로 두면 그림과 판정이 어긋난다(2026-09-06 에 그 병으로 고쳤다). */
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
        /* 붙잡기: 직전 갈래의 섹터를 +6도 넓혀 본다 — 경계에서 벌벌 떨리지 않게 */
        for (int k = 0; k < 8; k++)
            if (dpadLast != 0 && dpadBits(k) == dpadLast
                && angDist(ang, DPAD_CENTER[k]) <= halfOf(k) + 6f)
                return dpadLast;
        /* ★ 넓이는 위 표에서만 온다. 그리기와 같은 값이다. */
        for (int k = 0; k < 8; k++)
            if (angDist(ang, DPAD_CENTER[k]) <= halfOf(k)) { dpadLast = dpadBits(k); return dpadLast; }
        return dpadLast;   /* 표가 360도를 덮으므로 여기 안 온다 */
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
        /* 버튼을 숨기는 모드일 때만 눌림(DOWN)을 메뉴 알약·유틸 바용으로 걸러 받는다.
           지금은 안 숨기므로 이 가름막이 서지 않는다 — 터치가 언제나 산다. */
        if (hidesTouch() && act != MotionEvent.ACTION_DOWN && act != MotionEvent.ACTION_POINTER_DOWN)
            return true;

        if (handlePid >= 0 && (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP
                || act == MotionEvent.ACTION_CANCEL)) {
            if (act == MotionEvent.ACTION_CANCEL || e.getPointerId(e.getActionIndex()) == handlePid) {
                removeCallbacks(handleHold);
                boolean fired = handleFired;
                handlePid = -1; handleFired = false;
                if (!fired && act != MotionEvent.ACTION_CANCEL) {
                    barOpen = !barOpen;
                    if (!barOpen) barSel = -1;
                    menuChanged();
                    invalidate();
                }
                if (act == MotionEvent.ACTION_POINTER_UP) return true;
            }
        }
        if (edit && act == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
            pinching = true; pinch0 = pinchDist(e); pinchSentPct = 0;
            if (!selScreen && selIdx < 0) selScreen = true;          /* 아무것도 안 골랐으면 게임 화면 */
            pinchBase = selScreen ? 1f : sc[selIdx];
            dragIdx = -1; dragScreen = false;
            invalidate(); return true;
        }
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) {
            int idx = e.getActionIndex();
            float x = e.getX(idx), y = e.getY(idx);
            if (!undoChip.isEmpty() && undoLive() && !(barOpen || edit) && undoChip.contains(x, y)) {   /* 되돌리기 칩 */
                undoUntil = 0; removeCallbacks(undoExpire);
                if (listener != null) listener.onAction(ACT_UNDO);
                invalidate();
                return true;
            }
            if (handleHit(x, y) && handlePid < 0) {   /* 메뉴 알약 — 짧게 = 여닫기(뗄 때), 길게 = 빠른 저장 */
                handlePid = e.getPointerId(idx); handleFired = false;
                removeCallbacks(handleHold); postDelayed(handleHold, HOLD_MS);
                return true;
            }
            if ((barOpen || edit) && util[UTIL_EDIT].contains(x, y)) {   /* 「키」 */
                edit = !edit;
                if (!edit) save();
                mask = 0; dragIdx = -1; selIdx = -1;
                dpadPid = -1; dpadMask = 0; dpadLast = 0;
                if (listener != null) listener.onMask(0);
                menuChanged();
                invalidate();
                return true;
            }
            if (edit && resetBtn.contains(x, y)) {       /* 「처음대로」 — 두 번째 누름에서만 */
                long now = android.os.SystemClock.uptimeMillis();
                if (now < resetArmUntil) {
                    resetArmUntil = 0;
                    applyDefaults(); save();
                    selIdx = -1; selScreen = false;
                    if (listener != null) listener.onAction(ACT_LAYOUT_DEFAULT);
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } else {
                    resetArmUntil = now + 2500;
                    postDelayed(new Runnable() { @Override public void run() { invalidate(); } }, 2600);
                }
                invalidate();
                return true;
            }
            if (edit) {
                if (minus.contains(x, y) || plus.contains(x, y)) {
                    /* 아무것도 안 골랐으면 게임 화면이 대상 — 전엔 눌러도 조용히 먹기만 해서 「＋－가 안 된다」로 보였다
                       (이식소 진단 2026-09-04). 상자를 고르려다 ＋－를 먼저 누르는 순환도 이걸로 끊긴다. */
                    if (!selScreen && selIdx < 0) selScreen = true;
                    int d = minus.contains(x, y) ? -1 : +1;
                    if (selScreen) { if (listener != null) listener.onScreenScale(5 * d); }
                    else sc[selIdx] = d < 0 ? Math.max(0.6f, sc[selIdx] - 0.1f) : Math.min(1.8f, sc[selIdx] + 0.1f);
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
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
                /* 「배치」(UTIL_EDIT) 는 위에서 따로 처리 — 그 뒤 칸(종료)까지 전부 본다.
                   전엔 i < UTIL_EDIT 라 9번 칸 「종료」가 눌러도 안 잡혔다(제보 2026-09-05). */
                for (int i = 0; i < util.length; i++) {
                    if (i == UTIL_EDIT || !utilVisible(i)) continue;
                    if (util[i].contains(x, y)) {
                        if (listener != null) listener.onAction(utilAct[i]);
                        /* 순수 토글 — [≡]를 다시 눌러야 닫힌다. 저장·로드·샷은 연달아 쓰는데
                           매번 다시 열어야 했다(제보). 다만 화면을 떠나는 「롬」만은 접는다. */
                        if (utilAct[i] == ACT_PICK || utilAct[i] == ACT_QUIT) barOpen = false;
                        menuChanged();
                        invalidate();
                        return true;
                    }
                }
            }
            if (hidesTouch()) return true;            /* 버튼을 숨겼을 때만 게임 입력도 안 받는다 */
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
            if (pinching) {
                if (act == MotionEvent.ACTION_MOVE && e.getPointerCount() >= 2) {
                    float r = pinchDist(e) / Math.max(1f, pinch0);
                    if (selScreen) {
                        int pct = Math.round((r - 1f) * 100f);
                        if (pct != pinchSentPct && listener != null) { listener.onScreenScale(pct - pinchSentPct); pinchSentPct = pct; }
                    } else if (selIdx >= 0) {
                        sc[selIdx] = Math.max(0.6f, Math.min(1.8f, pinchBase * r));
                    }
                    invalidate();
                } else if (act == MotionEvent.ACTION_POINTER_UP || act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    pinching = false;
                    if (selScreen && listener != null) listener.onScreenDrop();
                }
                return true;
            }
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
                if (e.getPointerId(i) == handlePid) continue;   /* 메뉴 알약을 누르고 있는 손가락 */
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
