package com.dudu.pocketcore;

/**
 * 157 리그 인게임 메뉴 상태 기계 — 결판 · 그만할까요? · 쉼(+ 기권 두 번 묻기) · 서랍(순위·전적·설정).
 * GL 스레드 전용(EmuActivity.onDrawFrame 의 feed, gl.queueEvent 로 오는 나머지). League 를 읽지 않는다 — MenuModel(불변)·LgmPages.Scene 만.
 * 동작(다음 상대·재대결·그만…)은 Actions 를 UI 스레드에(Handler.post) 부른다 — 기존 EmuActivity 함수(rankNext·rankRematch·hubReturn…)가 받는다.
 * native 는 OvPort 로만(기본 = Emu.nativeOv*) — JVM 시험은 가짜 OvPort 를 넣는다.
 * 계약서 docs/157_contract.md §7.
 *
 * 입력(feed): 열린 동안 게임엔 0 · 연 순간 쥐고 있던 키는 뗄 때까지 무시 · 닫은 순간 쥔 키는 뗄 때까지 게임에서 뺌(qHeld) ·
 * 장면이 자리 잡기 전(readyT) A·B·방향·OPTION = 연출 건너뛰기만 · ▲▼ 첫 누름 한 칸, 18f 쥐면 5f 마다(60Hz 시계 — vsync 수 아님).
 * 닫힌 동안 쉼 자격(setPauseEligible)이면 OPTION(비트 3)을 늘 지우고, 다른 버튼 없이 눌렀다 30f 안에 떼면(탭) 쉼을 연다.
 * 화면 그림은 모두 장면(LgmPages)이 미리 만든 것 — 여기서는 올리고(층·창유리·쿼드·바탕·대본) 커서 줄의 팔레트만 바꾼다(recolor).
 */
final class LeagueMenu {
    /* NGPC 버튼 = libretro 비트(Emu) */
    static final int IN_A = 1 << Emu.B, IN_B = 1 << Emu.A, IN_OPT = 1 << Emu.START,
            IN_UP = 1 << Emu.UP, IN_DOWN = 1 << Emu.DOWN, IN_LEFT = 1 << Emu.LEFT, IN_RIGHT = 1 << Emu.RIGHT;
    static final int IN_GAME_ALL = 0xFFF;                       /* 게임 입력 전부(SP 접기 비트 1·9·10·11 포함) */
    static final int IN_KEYS = IN_A | IN_B | IN_OPT | IN_UP | IN_DOWN | IN_LEFT | IN_RIGHT;
    /* 상태 */
    static final int ST_CLOSED = 0, ST_ARMED = 1, ST_RESULT = 2, ST_QUIT = 3, ST_PAUSE_PENDING = 4, ST_PAUSE = 5, ST_CONFIRM = 6, ST_DRAWER = 7, ST_CLOSING = 8;
    /* 터치 동작(MotionEvent 와 같은 값) */
    static final int TOUCH_DOWN = 0, TOUCH_UP = 1, TOUCH_MOVE = 2, TOUCH_CANCEL = 3;
    /* 박자(60Hz 시계 프레임) */
    static final int REPEAT_DELAY = 18, REPEAT_EVERY = 5, OPT_TAP_MAX = 30, AUTO_NEXT_FRAMES = 600;
    /** UI 가 답하지 않을 때 풀어 주는 끝(쉼 기다림 · 동작 뒤 기다림) */
    static final int PENDING_MAX = 300, BUSY_MAX = 300;
    /** 서랍 목록 넘기기 — 한 칸 8px · 2px/f(쥐고 있으면 4px/f) */
    static final int LIST_STEP = 8;

    /** 덧판 native — Emu.nativeOv* 와 같은 모양. GL 스레드에서만 부른다 */
    interface OvPort {
        boolean layer(int layer, byte[] tiles, int tileBase, short[] map, int cols, int rows, int col0, int row0, short[] pals, int palBase);
        boolean cells(int layer, short[] cells, int col0, int row0, int cols, int rows, int tileBase);
        boolean recolor(int layer, int col0, int row0, int cols, int rows, int pal);
        boolean pal(int layer, int pal, int c1, int c2, int c3);
        boolean quad(int layer, int idx, int x, int y, int tile, int pal, int flags);
        boolean pane(int layer, int idx, int c0, int r0, int c1, int r1, int dx, int dy, boolean visible);
        boolean scroll(int layer, int x, int y, int clipY0, int clipY1);
        boolean base(int slot, int mode, int y0, int y1, int arg, int color);
        boolean cue(int[] ops, int flags);
        void hold(boolean on);
        void block(int bits);
        void clear(int mask);
        int[] status();
        /** Emu.nativeOv* 로 — 옛 native(UnsatisfiedLinkError)면 null */
        static OvPort emu() {
            try { if (Emu.nativeOvStatus() == null) return null; } catch (UnsatisfiedLinkError e) { return null; }
            return new OvPort() {
                @Override public boolean layer(int a, byte[] t, int tb, short[] m, int c, int r, int c0, int r0, short[] p, int pb) { return Emu.nativeOvLayer(a, t, tb, m, c, r, c0, r0, p, pb); }
                @Override public boolean cells(int a, short[] cl, int c0, int r0, int c, int r, int tb) { return Emu.nativeOvCells(a, cl, c0, r0, c, r, tb); }
                @Override public boolean recolor(int a, int c0, int r0, int c, int r, int p) { return Emu.nativeOvRecolor(a, c0, r0, c, r, p); }
                @Override public boolean pal(int a, int p, int c1, int c2, int c3) { return Emu.nativeOvPal(a, p, c1, c2, c3); }
                @Override public boolean quad(int a, int i, int x, int y, int t, int p, int f) { return Emu.nativeOvQuad(a, i, x, y, t, p, f); }
                @Override public boolean pane(int a, int i, int c0, int r0, int c1, int r1, int dx, int dy, boolean v) { return Emu.nativeOvPane(a, i, c0, r0, c1, r1, dx, dy, v); }
                @Override public boolean scroll(int a, int x, int y, int y0, int y1) { return Emu.nativeOvScroll(a, x, y, y0, y1); }
                @Override public boolean base(int s, int m, int y0, int y1, int arg, int c) { return Emu.nativeOvBase(s, m, y0, y1, arg, c); }
                @Override public boolean cue(int[] ops, int flags) { return Emu.nativeOvCue(ops, flags); }
                @Override public void hold(boolean on) { Emu.nativeOvHold(on); }
                @Override public void block(int bits) { Emu.nativeOvBlock(bits); }
                @Override public void clear(int mask) { Emu.nativeOvClear(mask); }
                @Override public int[] status() { return Emu.nativeOvStatus(); }
            };
        }
    }

    /** 메뉴가 고른 것 — 늘 UI 스레드에서 불린다 */
    interface Actions {
        void lgmNext(boolean newChar);            // 다음 상대(newChar = «새 캐릭터»: rankNext(true))
        void lgmRematch();                        // 재대결 · 설욕전(G9: 무대 = pref.stage)
        void lgmStage(int dir);                   // ◀▶ 무대 — UI 가 pref 를 바꾸고(rankStageStep) 메뉴에 새 값을 알림(setStage)
        void lgmQuit(int where);                  // MenuModel.QUIT_TITLE · QUIT_BOARD · QUIT_GAME · QUIT_LIST
        void lgmResume();                         // 쉼 이어하기(메뉴는 이미 닫음 — 쥔 키는 메뉴가 삼킴)
        void lgmLater();                          // 쉼 «나중에»(체크포인트 → 리그 화면)
        void lgmForfeit();                        // 쉼 기권 확정(두 번 물은 뒤) · 첫 라운드 전이면 «그만두기 (무효)»
        void lgmWantPause();                      // OPTION 탭을 잡음 — 코어는 이미 멈춤(ov_hold). UI 가 사본·장면을 만들어 openPause, 못 열면 cancelPause
        void lgmWantDrawer(int tab);              // 서랍 쪽이 필요(순위표 데우기 · 장면 만들기 → showDrawer)
        void lgmPrefs(MenuModel.Prefs p);         // 설정 탭에서 바꿈 → League.setMenuPrefs
        void lgmFallback(int why);                // 덧판을 못 씀(FB_*) → 옛 Java 카드로
    }
    static final int FB_NO_NATIVE = 1, FB_RECT = 2, FB_RENDER = 3, FB_NOT_SHOWN = 4;

    /* 장면 갈아 끼우기 방식 */
    private static final int SHOW_FULL = 0, SHOW_SWAP = 1, SHOW_END = 2;

    private final OvPort ov;
    private final Actions act;
    private final android.os.Handler ui;

    private volatile boolean pauseEligible;
    private volatile boolean portrait = true;
    private volatile int st = ST_CLOSED;
    /** UI 스레드 hitTest 가 읽는 불변 사본 */
    private volatile Hit hit = Hit.NONE;

    private MenuModel model;
    private LgmPages.Scene sResult, sQuit, sPause, sConfirm, sDrawer, cur;
    private int cursor = -1;                 // cur.rows 칸
    private int resultCursor = -1, pauseCursor = -1;
    private int drawerFrom = ST_CLOSED;      // 서랍을 부른 화면(결판·그만할까요?·쉼)
    private int drawerTab = -1;
    private boolean drawerWaiting;           // 서랍 장면을 기다림(lgmWantDrawer 뒤)
    private int cueId = 0;
    private int curReadyT = 0;
    private TileCanvas.Encoded lastPatch;    // 결판·그만할까요? 장면을 다시 올리면 다시 붙임
    private int stageSel = Integer.MIN_VALUE;
    /* 입력 */
    private int prev;                        // 지난 vsync 의 날 입력
    private int ignore;                      // 연 순간 쥐고 있던 키 — 뗄 때까지 무시
    private int qHeld;                       // 닫은 순간 쥐고 있던 키 — 뗄 때까지 게임에서 뺌
    private boolean capture;                 // 다음 feed 에서 ignore = in
    private long now = Long.MIN_VALUE;       // 60Hz 시계(프레임)
    private int repDir; private long repNext;
    /* OPTION 탭 */
    private boolean optDown, optPass, optCombo; private long optT0;
    /* 기다림 */
    private long pendT0, busyT0; private boolean busy, pendFresh;
    /* 닫기 연출 뒤 할 일 */
    private long closeAt; private Runnable afterClose;
    /* 연속 대전 */
    private boolean autoOn, autoOff;
    /* 서랍 목록 넘기기 */
    private int listY, listTarget, listMin, listMax;
    /* 터치 */
    private int touchKind = 0, touchIdx = -1;   // DOWN 때 맞힌 것(1 줄 · 2 무대 왼쪽 · 3 무대 오른쪽 · 4 탭 · 5 칼집 밖)

    LeagueMenu(OvPort ov, Actions actions, android.os.Handler ui) {
        this.ov = ov; this.act = actions; this.ui = ui;
    }

    /* ── UI 스레드 → (값만) ── */
    /** 랭크 경기 중(H_MATCH 또는 체크포인트 이어 하기의 싸움) · 결판 전 · 찾기 겹창 없음 · 결과 카드 없음 — 이때만 OPTION 을 가로챈다(volatile) */
    void setPauseEligible(boolean on) { pauseEligible = on; }
    /** 세로 화면인가 — 쉼에서 칼집 밖 탭 = 이어하기는 세로만(가로는 패드 단추가 게임 상자에 겹칠 수 있다) */
    void setPortrait(boolean on) { portrait = on; }

    /* ── GL 스레드 ── */
    /** 결판 — 정산 때 미리 그린 장면을 «걸쇠에 맞춰 시작»으로 걸어 둔다(ARM_ON_LATCH). 걸쇠가 이미 섰으면 다음 vsync 에 */
    void armResult(MenuModel m, LgmPages.Scene result, LgmPages.Scene quit) {
        if (m == null || result == null) return;
        if (st == ST_PAUSE_PENDING) ov.hold(false);
        if (st == ST_RESULT || st == ST_QUIT || st == ST_DRAWER || st == ST_CLOSING) return;   /* 이미 결판이 열림 */
        if (st == ST_PAUSE || st == ST_CONFIRM) { ov.clear(Emu.OV_CLR_ALL); st = ST_CLOSED; }
        model = m; sResult = result; sQuit = quit; sPause = sConfirm = sDrawer = null; drawerTab = -1;
        lastPatch = null; stageSel = result.stageSel; busy = false; afterClose = null;
        autoOn = m.prefs != null && m.prefs.autoNextSec == 10; autoOff = false;
        resultCursor = clampRow(result, result.defaultRow);
        if (!show(result, SHOW_FULL, Emu.OV_ARM_ON_LATCH, resultCursor)) return;
        st = ST_ARMED;
        updateHit();
    }
    /** 결판을 지금(기권 뒤 — 코어는 ov_hold 로 멈춘 채) */
    void openResultNow(MenuModel m, LgmPages.Scene result, LgmPages.Scene quit) {
        if (m == null || result == null) return;
        model = m; sResult = result; sQuit = quit; sPause = sConfirm = sDrawer = null; drawerTab = -1;
        lastPatch = null; stageSel = result.stageSel; busy = false; afterClose = null;
        autoOn = m.prefs != null && m.prefs.autoNextSec == 10; autoOff = false;
        resultCursor = clampRow(result, result.defaultRow);
        ov.hold(true);
        if (!show(result, SHOW_FULL, Emu.OV_START_NOW, resultCursor)) return;
        st = ST_RESULT; capture = true;
        updateHit();
    }
    /** 쉼 — lgmWantPause 뒤 UI 가 준 사본·장면(뒤로·패드 바는 바로 이것만) */
    void openPause(MenuModel m, LgmPages.Scene pause, LgmPages.Scene confirm) {
        if (m == null || pause == null) { cancelPause(); return; }
        if (st != ST_CLOSED && st != ST_PAUSE_PENDING) return;
        model = m; sPause = pause; sConfirm = confirm; sResult = sQuit = sDrawer = null; drawerTab = -1;
        busy = false; afterClose = null; autoOn = false;
        ov.hold(true);
        pauseCursor = clampRow(pause, pause.defaultRow);
        if (!show(pause, SHOW_FULL, Emu.OV_START_NOW, pauseCursor)) return;
        st = ST_PAUSE; capture = true;
        updateHit();
    }
    /** 뒤로·패드 바 «목록/종료»(랭크 경기 중) — OPTION 탭과 같은 길: 이 vsync 부터 코어를 멈추고 UI 에 쉼 사본을 청함 */
    void requestPause() {
        if (st != ST_CLOSED) return;
        ov.hold(true);
        st = ST_PAUSE_PENDING; pendFresh = true; optDown = false;
        updateHit();
        post(act::lgmWantPause);
    }
    /** 쉼을 못 엶(결판이 이미 남 등) — 멈춤만 풂 */
    void cancelPause() {
        if (st != ST_PAUSE_PENDING) return;
        ov.hold(false);
        st = ST_CLOSED; qHeld = prev;
        updateHit();
    }
    /** 결판이 열린 채 패드 바 «목록/종료» — 칼집 안을 «그만할까요?»로(쉼이면 쉼 그대로) */
    void openQuit() {
        if (busy || sQuit == null) return;
        if (st == ST_RESULT) toQuit(0);
        else if (st == ST_DRAWER && (drawerFrom == ST_RESULT || drawerFrom == ST_QUIT)) toQuit(0);
    }
    /** 서랍 쪽(순위·전적·설정)이 준비됨 — 지금 서랍이면 바꿔 끼움 */
    void showDrawer(int tab, LgmPages.Scene page) {
        if (page == null || st != ST_DRAWER) return;
        if (tab != drawerTab && !drawerWaiting) return;                /* 그 사이 다른 탭으로 갔다 */
        boolean first = sDrawer == null;
        boolean sameTab = sDrawer != null && sDrawer.tab == page.tab;
        int c = sameTab && cursor >= 0 ? cursor : page.defaultRow;
        sDrawer = page; drawerWaiting = false; busy = false;
        drawerTab = page.tab;
        show(page, first ? SHOW_FULL : sameTab ? SHOW_END : SHOW_SWAP, Emu.OV_START_NOW, clampRow(page, c));
        listMin = page.lScrollMin; listMax = page.lScrollMax;
        listY = listTarget = page.lScrollY;
        updateHit();
    }
    /** ◀▶ 뒤 새 무대 값 */
    void setStage(int sel) {
        stageSel = sel;
        if (cur != null && cur == sResult) applyStage(cur);
    }
    /** 늦게 온 조각(순위 «12,483위 ▲212» 등) — 장면의 W 칸 일부를 바꿔 끼움 */
    void patch(TileCanvas.Encoded cells) {
        if (cells == null) return;
        lastPatch = cells;
        if (cur != null && (cur == sResult || cur == sQuit)) applyPatch(cells);
    }
    /** 메뉴 사본만 바꿈(설정 탭 — 진 판 기본 커서·연속 대전) */
    void setModel(MenuModel m) { if (m != null) model = m; }

    /** vsync 마다(onDrawFrame, nativeFrame 앞) — in = padMask|keyMask|axisMask. 돌려줌: 게임에 줄 입력(열려 있으면 0, 닫은 뒤 쥔 키는 뗄 때까지 뺌) */
    int feed(int in, long frameNanos) {
        now = frameNanos / 1000L * 60L / 1_000_000L;
        int s = st;
        if (s == ST_CLOSED) {
            int game = in;
            if (qHeld != 0) { qHeld &= in; game &= ~qHeld; }
            if (pauseEligible) game = optionGate(in, game);
            else optDown = false;
            prev = in;
            if (st == ST_PAUSE_PENDING) return 0;
            return game;
        }
        if (s == ST_PAUSE_PENDING) {
            prev = in;
            if (pendFresh) { pendT0 = now; pendFresh = false; }
            if (now - pendT0 > PENDING_MAX) cancelPause();
            return 0;
        }
        int[] v = ov.status();
        int fl = v != null && v.length > Emu.OVS_FLAGS ? v[Emu.OVS_FLAGS] : 0;
        if (s == ST_ARMED) {
            if ((fl & Emu.OVF_VISIBLE) != 0) {                       /* 걸쇠 vsync 에 native 가 열었다 */
                st = ST_RESULT; capture = true; updateHit();
            } else if ((fl & Emu.OVF_ARMED) == 0) {                   /* native 가 저절로 지웠다(리셋·불러오기·허브 돌아가기) */
                resetClosed(in);
                return in & ~qHeld;
            } else { prev = in; return in; }                          /* 승리 대사 중 — 게임 그대로(B 건너뛰기) */
        } else if ((fl & (Emu.OVF_VISIBLE | Emu.OVF_ARMED)) == 0 && v != null && v[Emu.OVS_VER] >= 1) {
            resetClosed(in);                                          /* native 가 저절로 지웠다 */
            return in & ~qHeld;
        } else if ((fl & Emu.OVF_VISIBLE) != 0 && (fl & Emu.OVF_RECT_OK) == 0) {   /* 게임 사각형을 잃음 — 안 보이는데 입력만 막힌다 */
            close();
            post(() -> act.lgmFallback(FB_RECT));
            return 0;
        }
        if (capture) { ignore = in & IN_GAME_ALL; capture = false; prev = in; return 0; }
        ignore &= in;
        int held = in & ~ignore;
        int rise = held & ~prev;
        prev = in;
        if (st == ST_CLOSING) {
            if (afterClose != null && now >= closeAt) { Runnable r = afterClose; afterClose = null; r.run(); }
            else if (afterClose == null && busy && now - busyT0 > BUSY_MAX) recover();   /* 동작을 보냈는데 UI 가 닫지 않았다 */
            return 0;
        }
        if (st == ST_DRAWER) stepList(held);
        if (busy) { if (now - busyT0 > BUSY_MAX) busy = false; return 0; }
        int t = v != null && v.length > Emu.OVS_T ? v[Emu.OVS_T] : -1;
        boolean running = (fl & Emu.OVF_RUNNING) != 0 && t >= 0 && t < curReadyT;
        if ((rise & IN_KEYS) != 0) {
            cancelAuto();
            if (running) { ov.cue(null, Emu.OV_SKIP_TO_END); return 0; }
        }
        if (st == ST_RESULT && autoOn && !autoOff && t >= 0 && model != null && model.prefs.autoNextSec == 10
                && t >= curReadyT + AUTO_NEXT_FRAMES && !running) {
            autoOff = true;
            select(rowById(cur, MenuModel.ROW_NEXT));
            return 0;
        }
        handle(rise, held);
        return 0;
    }

    /** 게임 픽셀 좌표(0..159 × 0..151 — 칸 = >>3). EmuActivity 가 OVS_VX.. 로 뷰 좌표를 바꿔 넘긴다 */
    void onTouch(int x, int y, int action) {
        int s = st;
        if (s == ST_CLOSED || s == ST_ARMED || s == ST_PAUSE_PENDING || s == ST_CLOSING || cur == null) return;
        if (action == TOUCH_CANCEL || (s == ST_DRAWER && drawerWaiting)) { touchKind = 0; return; }
        int c = Math.floorDiv(x, 8), r = Math.floorDiv(y, 8);
        if (action == TOUCH_DOWN) {
            touchKind = 0; touchIdx = -1;
            int i = rowAt(cur, c, r);
            if (i >= 0) { touchKind = 1; touchIdx = i; return; }
            if (cur.stageRow != null && inRow(cur.stageRow, c, r)) { touchKind = x < (cur.stageRow.c0 + cur.stageRow.c1 + 1) * 4 ? 2 : 3; return; }
            if (cur.tabs != null) for (int k = 0; k < cur.tabs.length; k++) if (inRow(cur.tabs[k], c, r)) { touchKind = 4; touchIdx = k; return; }
            if ((s == ST_PAUSE) && portrait && cur.portraitOutside && !inHitRect(cur, c, r)) { touchKind = 5; return; }
            return;
        }
        if (action != TOUCH_UP || touchKind == 0) return;
        int kind = touchKind, idx = touchIdx;
        touchKind = 0;
        cancelAuto();
        int[] v = ov.status();
        int fl = v != null && v.length > Emu.OVS_FLAGS ? v[Emu.OVS_FLAGS] : 0, t = v != null && v.length > Emu.OVS_T ? v[Emu.OVS_T] : -1;
        if ((fl & Emu.OVF_RUNNING) != 0 && t >= 0 && t < curReadyT) { ov.cue(null, Emu.OV_SKIP_TO_END); return; }
        if (busy) return;
        switch (kind) {
            case 1: {
                if (rowAt(cur, c, r) != idx) return;                 /* 손가락이 줄 밖에서 떨어짐 */
                LgmPages.Row row = cur.rows[idx];
                if (!row.enabled) return;
                if (idx == cursor) select(idx);                       /* 같은 줄 다시 탭 = 결정(기권은 늘 두 단계 — 확인 칸도 첫 탭은 커서만) */
                else moveCursorTo(idx);
                break;
            }
            case 2: case 3:
                if (st == ST_RESULT) post(() -> act.lgmStage(kind == 2 ? -1 : 1));
                break;
            case 4:
                if (st == ST_DRAWER && cur.tabs != null && idx < cur.tabs.length) switchTab(cur.tabs[idx].id);
                break;
            case 5:
                if (st == ST_PAUSE) resume();
                break;
            default: break;
        }
    }
    /** UI 스레드 — 게임 px 가 메뉴가 받을 자리인가(줄·탭·칼집·무대 줄, 세로 화면 쉼은 칼집 밖도). 장면이 바뀔 때 GL 스레드가 고친 불변 스냅숏을 읽는다 */
    boolean hitTest(int x, int y) {
        Hit h = hit;
        if (!h.open || x < 0 || y < 0 || x >= 160 || y >= 152) return false;
        int c = x >> 3, r = y >> 3;
        for (int i = 0; i + 3 < h.rects.length; i += 4) if (c >= h.rects[i] && c <= h.rects[i + 2] && r >= h.rects[i + 1] && r <= h.rects[i + 3]) return true;
        return h.outside && portrait;
    }
    /** 안드로이드 뒤로 = B */
    void back() {
        int s = st;
        if (s == ST_CLOSED || s == ST_ARMED || s == ST_PAUSE_PENDING || s == ST_CLOSING || busy) return;
        cancelAuto();
        int[] v = ov.status();
        int fl = v != null && v.length > Emu.OVS_FLAGS ? v[Emu.OVS_FLAGS] : 0, t = v != null && v.length > Emu.OVS_T ? v[Emu.OVS_T] : -1;
        if ((fl & Emu.OVF_RUNNING) != 0 && t >= 0 && t < curReadyT) { ov.cue(null, Emu.OV_SKIP_TO_END); return; }
        handle(IN_B, 0);
    }
    boolean isOpen() { int s = st; return s != ST_CLOSED && s != ST_ARMED; }
    int state() { return st; }
    /** 지금 결판 장면을 걸었거나 열었나(결판·그만할까요?·결판에서 부른 서랍) */
    boolean resultUp() { int s = st; return s == ST_ARMED || s == ST_RESULT || s == ST_QUIT || (s == ST_DRAWER && sResult != null) || (s == ST_CLOSING && sResult != null); }
    /** 바로 닫기 — 덧판 지움(OV_CLR_ALL: 멈춤·막기도 풂). hubReturn·나가기 전에 GL 스레드에서 */
    void close() {
        ov.clear(Emu.OV_CLR_ALL);
        resetClosed(prev);
    }

    /* ────────────────────────────────────────────────────────────────── 안쪽 */

    private void resetClosed(int in) {
        st = ST_CLOSED; qHeld = in & IN_GAME_ALL; cur = null; model = null;
        sResult = sQuit = sPause = sConfirm = sDrawer = null; drawerTab = -1; drawerWaiting = false;
        busy = false; afterClose = null; capture = false; ignore = 0; repDir = 0; autoOn = false;
        optDown = false; lastPatch = null; cursor = -1;
        updateHit();
    }

    /** 닫힌 동안 OPTION — 늘 지우고, 탭이면 쉼 */
    private int optionGate(int in, int game) {
        boolean down = (in & IN_OPT) != 0, was = (prev & IN_OPT) != 0;
        int others = in & IN_GAME_ALL & ~IN_OPT;
        if (down && !was) {
            int[] v = ov.status();
            optPass = v != null && v.length > Emu.OVS_GPAUSE && v[Emu.OVS_GPAUSE] == 1;   /* 게임이 이미 자기 PAUSE — 이 누름은 게임에 */
            optDown = true; optCombo = others != 0; optT0 = now;
        } else if (down && optDown && others != 0) optCombo = true;
        if (!down && was && optDown) {
            optDown = false;
            if (!optPass && !optCombo && now - optT0 <= OPT_TAP_MAX) {
                ov.hold(true);                                         /* 이 vsync 부터 코어 0 프레임 */
                st = ST_PAUSE_PENDING; pendT0 = now; pendFresh = false;
                updateHit();
                post(act::lgmWantPause);
                return 0;
            }
        }
        if (!(optDown && optPass)) game &= ~IN_OPT;
        return game;
    }

    /** 장면을 올린다 — 층·창유리·쿼드·바탕 → 커서 → 대본. 실패하면 닫고 옛 길로 */
    private boolean show(LgmPages.Scene sc, int how, int startFlag, int cursorIdx) {
        ov.clear(Emu.OV_CLR_W | Emu.OV_CLR_L | Emu.OV_CLR_BASE | Emu.OV_CLR_CUE);
        boolean ok = upload(sc);
        cur = sc;
        cursor = -1;
        if (ok && sc.rows != null && sc.rows.length > 0) setCursor(cursorIdx, true);
        if (ok && sc == sResult) applyStage(sc);
        if (ok && (sc == sResult || sc == sQuit) && lastPatch != null) applyPatch(lastPatch);
        int[] ops = how == SHOW_FULL ? sc.cue : sc.swapCue;
        int flags = startFlag | (++cueId << Emu.OV_CUE_ID_SHIFT) | (how == SHOW_END ? Emu.OV_SKIP_TO_END : 0);
        if (ok) ok = ov.cue(ops != null ? ops : new int[0], flags);
        curReadyT = how == SHOW_FULL ? sc.readyT : 0;
        if (how != SHOW_FULL && sc.fusePane >= 0) fuseOff(sc);           /* 돌아온 결판 — 연속 대전은 이미 꺼짐 */
        if (!ok) {
            android.util.Log.i("PocketCore", "lgm fallback upload");
            ov.clear(Emu.OV_CLR_ALL);
            resetClosed(prev);
            post(() -> act.lgmFallback(FB_RENDER));
        }
        return ok;
    }

    private boolean upload(LgmPages.Scene sc) {
        boolean ok = true;
        if (sc.w != null) ok = uploadEnc(Emu.OV_W, sc.w);
        if (ok && sc.l != null) for (TileCanvas.Encoded e : sc.l) if (e != null && !uploadEnc(Emu.OV_L, e)) { ok = false; break; }
        if (ok && sc.l != null) ok = ov.scroll(Emu.OV_L, 0, sc.lScrollY, sc.lClipY0, sc.lClipY1);
        if (ok && sc.panes != null)
            for (int i = 0; i + 8 < sc.panes.length; i += 9)
                ok &= ov.pane(sc.panes[i], sc.panes[i + 1], sc.panes[i + 2], sc.panes[i + 3], sc.panes[i + 4], sc.panes[i + 5], sc.panes[i + 6], sc.panes[i + 7], sc.panes[i + 8] != 0);
        if (ok && sc.quads != null)
            for (int i = 0; i + 6 < sc.quads.length; i += 7)
                ok &= ov.quad(sc.quads[i], sc.quads[i + 1], sc.quads[i + 2], sc.quads[i + 3], sc.quads[i + 4], sc.quads[i + 5], sc.quads[i + 6]);
        if (ok && sc.base != null)
            for (int i = 0; i + 5 < sc.base.length; i += 6)
                ok &= ov.base(sc.base[i], sc.base[i + 1], sc.base[i + 2], sc.base[i + 3], sc.base[i + 4], sc.base[i + 5]);
        return ok;
    }
    /** 한 묶음 올리기 — 타일이 511 을 넘으면 나눠서(맵은 마지막에 한 번) */
    private boolean uploadEnc(int layer, TileCanvas.Encoded e) {
        int n = e.tiles != null ? e.tiles.length / 16 : 0;
        if (n <= TileCanvas.MAX_CHUNK) return ov.layer(layer, e.tiles, e.tileBase, e.map, e.cols, e.rows, e.col0, e.row0, e.pals, 0);
        for (int k = 0; k < n; k += TileCanvas.MAX_CHUNK) {
            int m = Math.min(TileCanvas.MAX_CHUNK, n - k);
            byte[] t = java.util.Arrays.copyOfRange(e.tiles, k * 16, (k + m) * 16);
            if (!ov.layer(layer, t, e.tileBase + k, null, 0, 0, 0, 0, k == 0 ? e.pals : null, 0)) return false;
        }
        return ov.layer(layer, null, e.tileBase, e.map, e.cols, e.rows, e.col0, e.row0, null, 0);
    }

    /** 입력 하나 처리(열린 화면) */
    private void handle(int rise, int held) {
        LgmPages.Scene sc = cur;
        if (sc == null) return;
        int dir = held & (IN_UP | IN_DOWN);
        if (dir == (IN_UP | IN_DOWN)) dir = 0;
        int step = 0;
        if ((rise & (IN_UP | IN_DOWN)) != 0 && dir != 0) { step = dir == IN_UP ? -1 : 1; repDir = dir; repNext = now + REPEAT_DELAY; }
        else if (dir != 0 && dir == repDir && now >= repNext) { step = dir == IN_UP ? -1 : 1; repNext = now + REPEAT_EVERY; }
        if (dir == 0) repDir = 0;
        int lr = (rise & IN_LEFT) != 0 ? -1 : (rise & IN_RIGHT) != 0 ? 1 : 0;
        switch (st) {
            case ST_RESULT:
                if (step != 0) moveCursor(step);
                if (lr != 0) { final int d = lr; post(() -> act.lgmStage(d)); }
                if ((rise & IN_A) != 0) select(cursor);
                else if ((rise & IN_B) != 0) {
                    int q = rowById(sc, MenuModel.ROW_QUIT);
                    if (q >= 0 && cursor != q) moveCursorTo(q);       /* 처음: 커서만 «그만하기» */
                    else if (q >= 0) toQuit(0);                        /* 그 위에서 또: 그만할까요? */
                } else if ((rise & IN_OPT) != 0) openDrawer(firstTab());
                break;
            case ST_QUIT:
                if (step != 0) moveCursor(step);
                if ((rise & IN_A) != 0) select(cursor);
                else if ((rise & IN_B) != 0) backToResult();
                break;
            case ST_PAUSE:
                if (step != 0) moveCursor(step);
                if ((rise & IN_A) != 0) select(cursor);
                else if ((rise & (IN_B | IN_OPT)) != 0) resume();
                break;
            case ST_CONFIRM:
                if (lr != 0) moveCursorTo(lr < 0 ? 0 : Math.min(1, sc.rows.length - 1));
                if ((rise & IN_A) != 0) select(cursor);
                else if ((rise & (IN_B | IN_OPT)) != 0) collapseConfirm();
                break;
            case ST_DRAWER:
                if (drawerWaiting) { if ((rise & (IN_B | IN_OPT)) != 0) closeDrawer(); break; }
                if (lr != 0) switchTab(nextTab(drawerTab, lr));
                else if (step != 0) {
                    if (sc.rows != null && sc.rows.length > 0) moveCursor(step);
                    else scrollList(step);
                }
                if ((rise & IN_A) != 0 && sc.rows != null && sc.rows.length > 0) select(cursor);
                else if ((rise & (IN_B | IN_OPT)) != 0) closeDrawer();
                break;
            default: break;
        }
    }

    /** 결정 — 줄 칸 i */
    private void select(int i) {
        LgmPages.Scene sc = cur;
        if (sc == null || sc.rows == null || i < 0 || i >= sc.rows.length || !sc.rows[i].enabled) return;
        int id = sc.rows[i].id;
        switch (st) {
            case ST_RESULT:
                resultCursor = i;
                switch (id) {
                    case MenuModel.ROW_NEXT: closeThen(() -> act.lgmNext(false)); break;
                    case MenuModel.ROW_NEWCHAR: closeThen(() -> act.lgmNext(true)); break;
                    case MenuModel.ROW_REMATCH: closeThen(act::lgmRematch); break;
                    case MenuModel.ROW_DRAWER: openDrawer(firstTab()); break;
                    case MenuModel.ROW_QUIT: toQuit(0); break;
                    default: break;
                }
                break;
            case ST_QUIT:
                if (id == MenuModel.QUIT_CANCEL) { backToResult(); break; }
                setBusy();
                post(() -> act.lgmQuit(id));
                break;
            case ST_PAUSE:
                pauseCursor = i;
                switch (id) {
                    case MenuModel.ROW_RESUME: resume(); break;
                    case MenuModel.ROW_DRAWER: openDrawer(firstTab()); break;
                    case MenuModel.ROW_LATER: setBusy(); post(act::lgmLater); break;
                    case MenuModel.ROW_FORFEIT: openConfirm(); break;
                    case MenuModel.ROW_VOIDQUIT: setBusy(); post(act::lgmForfeit); break;
                    default: break;
                }
                break;
            case ST_CONFIRM:
                if (id == LgmPages.CONFIRM_YES) { setBusy(); post(act::lgmForfeit); }
                else collapseConfirm();
                break;
            case ST_DRAWER:
                if (model == null) break;
                MenuModel.Prefs p = model.prefs, n = p;
                switch (id) {
                    case LgmPages.SET_FX: n = p.withFx((p.fx + 1) % 3); break;
                    case LgmPages.SET_LOSS: n = p.withLoss(p.lossCursor == 0 ? 1 : 0); break;
                    case LgmPages.SET_AUTO: n = p.withAuto(p.autoNextSec == 10 ? 0 : 10); break;
                    case LgmPages.SET_REMEMBER: n = p.withRemember(!p.rememberTab); break;
                    default: break;
                }
                if (n != p) {
                    model = model.withPrefs(n);
                    if (id == LgmPages.SET_AUTO) autoOff = true;            /* 이 결판에서는 켜지 않는다(바꾼 것도 입력) */
                    final MenuModel.Prefs np = n;
                    post(() -> act.lgmPrefs(np));                          /* UI 가 저장하고 설정 쪽을 다시 그려 showDrawer */
                    drawerWaiting = false;
                }
                break;
            default: break;
        }
    }

    private void toQuit(int c) {
        if (sQuit == null) return;
        if (st == ST_RESULT) resultCursor = cursor;
        if (!show(sQuit, SHOW_SWAP, Emu.OV_START_NOW, clampRow(sQuit, c))) return;
        st = ST_QUIT;
        updateHit();
    }
    private void backToResult() {
        if (sResult == null) return;
        int q = rowById(sResult, MenuModel.ROW_QUIT);
        if (!show(sResult, SHOW_SWAP, Emu.OV_START_NOW, q >= 0 ? q : clampRow(sResult, resultCursor))) return;
        st = ST_RESULT;
        updateHit();
    }
    private void openConfirm() {
        if (sConfirm == null) { setBusy(); post(act::lgmForfeit); return; }    /* 확인 장면이 없으면(그림 실패) — 그래도 두 번째로 묻지 않고 넘기지 않게 */
        pauseCursor = cursor;
        if (!show(sConfirm, SHOW_FULL, Emu.OV_START_NOW, clampRow(sConfirm, sConfirm.defaultRow))) return;
        st = ST_CONFIRM;
        updateHit();
    }
    private void collapseConfirm() {
        if (sPause == null) return;
        int f = rowById(sPause, MenuModel.ROW_FORFEIT);
        if (!show(sPause, SHOW_END, Emu.OV_START_NOW, f >= 0 ? f : clampRow(sPause, pauseCursor))) return;
        st = ST_PAUSE;
        updateHit();
    }
    /** 쉼 이어하기 — 칼집 나감 6f 뒤 닫고(멈춤 풂) UI 에 알림 */
    private void resume() {
        LgmPages.Scene sc = cur;
        Runnable done = () -> { close(); post(act::lgmResume); };
        if (sc != null && sc.closeCue != null && sc.closeDur > 0) closing(sc, done);
        else done.run();
    }
    /** 다음 상대·재대결 — 칼집 닫힘 6f 뒤 동작(겹창 rankQ 가 바로 덮는다). 닫기는 UI 의 hubReturn 이 */
    private void closeThen(Runnable action) {
        LgmPages.Scene sc = cur;
        Runnable done = () -> { setBusy(); post(action); };
        if (sc != null && sc.closeCue != null && sc.closeDur > 0) closing(sc, done);
        else done.run();
    }
    private void closing(LgmPages.Scene sc, Runnable done) {
        ov.cue(sc.closeCue, Emu.OV_START_NOW | (++cueId << Emu.OV_CUE_ID_SHIFT));
        curReadyT = 0;
        st = ST_CLOSING; closeAt = now + sc.closeDur; afterClose = done;
        updateHit();
    }

    private int firstTab() {
        MenuModel.Prefs p = model != null ? model.prefs : null;
        return p != null && p.rememberTab ? Math.max(0, Math.min(2, p.lastTab)) : MenuModel.Prefs.TAB_BOARD;
    }
    private static int nextTab(int tab, int d) { return Math.floorMod(Math.max(0, tab) + d, 3); }
    private void openDrawer(int tab) {
        drawerFrom = st;
        if (st == ST_RESULT || st == ST_QUIT) resultCursor = st == ST_RESULT ? cursor : resultCursor;
        if (st == ST_PAUSE) pauseCursor = cursor;
        st = ST_DRAWER; sDrawer = null; drawerTab = tab; drawerWaiting = true;
        setBusy(); busy = false;                                           /* 입력은 받는다(B = 되돌아감) */
        updateHit();
        post(() -> act.lgmWantDrawer(tab));
    }
    private void switchTab(int tab) {
        if (tab == drawerTab && sDrawer != null) return;
        drawerTab = tab; drawerWaiting = true;
        if (model != null && model.prefs.rememberTab && model.prefs.lastTab != tab) {
            model = model.withPrefs(model.prefs.withTab(tab));
            final MenuModel.Prefs np = model.prefs;
            post(() -> act.lgmPrefs(np));
        }
        post(() -> act.lgmWantDrawer(tab));
    }
    private void closeDrawer() {
        LgmPages.Scene back = drawerFrom == ST_PAUSE ? sPause : drawerFrom == ST_QUIT ? sQuit : sResult;
        int to = drawerFrom;
        if (back == null) { close(); return; }
        Runnable done = () -> {
            int c = to == ST_PAUSE ? pauseCursor : to == ST_QUIT ? 0 : resultCursor;
            sDrawer = null; drawerTab = -1; drawerWaiting = false;
            if (show(back, SHOW_END, Emu.OV_START_NOW, clampRow(back, c))) { st = to; capture = true; updateHit(); }
        };
        LgmPages.Scene sc = sDrawer;
        if (sc != null && sc.closeCue != null && sc.closeDur > 0) closing(sc, done);
        else done.run();
    }

    /* 서랍 목록 넘기기 — 목표를 8px 씩, 화면은 vsync 마다 2px(쥐면 4px) */
    private void scrollList(int step) {
        listTarget = Math.max(listMin, Math.min(listMax, listTarget + step * LIST_STEP));
    }
    private void stepList(int held) {
        if (cur == null || cur.l == null || listY == listTarget) return;
        int sp = (held & (IN_UP | IN_DOWN)) != 0 && repDir != 0 && now >= repNext - REPEAT_EVERY ? 4 : 2;
        listY += Integer.signum(listTarget - listY) * Math.min(sp, Math.abs(listTarget - listY));
        ov.scroll(Emu.OV_L, 0, listY, cur.lClipY0, cur.lClipY1);
    }

    /* 커서 */
    private void moveCursor(int d) {
        LgmPages.Scene sc = cur;
        if (sc == null || sc.rows == null || sc.rows.length == 0) return;
        int n = sc.rows.length, i = cursor < 0 ? 0 : cursor;
        for (int k = 0; k < n; k++) {
            i = Math.floorMod(i + d, n);
            if (sc.rows[i].enabled) { setCursor(i, false); return; }
        }
    }
    private void moveCursorTo(int i) {
        LgmPages.Scene sc = cur;
        if (sc == null || sc.rows == null || i < 0 || i >= sc.rows.length || !sc.rows[i].enabled) return;
        setCursor(i, false);
    }
    private void setCursor(int i, boolean fresh) {
        LgmPages.Scene sc = cur;
        if (sc == null || sc.rows == null || sc.rows.length == 0) return;
        i = clampRow(sc, i);
        if (!fresh && i == cursor) return;
        if (cursor >= 0 && cursor < sc.rows.length && cursor != i) { LgmPages.Row o = sc.rows[cursor]; ov.recolor(Emu.OV_W, o.c0, o.r0, o.c1 - o.c0 + 1, o.r1 - o.r0 + 1, o.palOff); }
        LgmPages.Row r = sc.rows[i];
        if (fresh) for (int k = 0; k < sc.rows.length; k++) if (k != i) { LgmPages.Row o = sc.rows[k]; ov.recolor(Emu.OV_W, o.c0, o.r0, o.c1 - o.c0 + 1, o.r1 - o.r0 + 1, o.palOff); }
        ov.recolor(Emu.OV_W, r.c0, r.r0, r.c1 - r.c0 + 1, r.r1 - r.r0 + 1, r.palOn);
        int[] q = quadDef(sc, LgmPages.Q_CURSOR);
        if (q != null) ov.quad(q[0], q[1], r.quadX, r.quadY, q[4], r.palOn, q[6] | Emu.OV_Q_SHOW);
        if (r.descC >= 0) copyCells(sc, r.descC, r.descR, r.descDstC, r.descDstR, r.descW, 1);
        cursor = i;
        updateHit();
    }
    private static int clampRow(LgmPages.Scene sc, int i) {
        if (sc.rows == null || sc.rows.length == 0) return -1;
        if (i < 0 || i >= sc.rows.length) i = 0;
        if (sc.rows[i].enabled) return i;
        for (int k = 0; k < sc.rows.length; k++) if (sc.rows[k].enabled) return k;
        return i;
    }
    private static int rowById(LgmPages.Scene sc, int id) {
        if (sc == null || sc.rows == null) return -1;
        for (int i = 0; i < sc.rows.length; i++) if (sc.rows[i].id == id) return i;
        return -1;
    }
    private static int[] quadDef(LgmPages.Scene sc, int idx) {
        if (sc.quads == null) return null;
        for (int i = 0; i + 6 < sc.quads.length; i += 7) if (sc.quads[i] == Emu.OV_W && sc.quads[i + 1] == idx) return java.util.Arrays.copyOfRange(sc.quads, i, i + 7);
        return null;
    }
    private static int[] paneDef(LgmPages.Scene sc, int idx) {
        if (sc.panes == null || idx < 0) return null;
        for (int i = 0; i + 8 < sc.panes.length; i += 9) if (sc.panes[i] == Emu.OV_W && sc.panes[i + 1] == idx) return java.util.Arrays.copyOfRange(sc.panes, i, i + 9);
        return null;
    }
    /** 창고 칸 → 화면 칸 복사(W 맵 사본에서 칸 값을 읽어 nativeOvCells) */
    private void copyCells(LgmPages.Scene sc, int sc0, int sr0, int dc0, int dr0, int w, int h) {
        TileCanvas.Encoded e = sc.w;
        if (e == null || e.map == null || w <= 0 || h <= 0) return;
        short[] cells = new short[w * h];
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int mc = sc0 + c - e.col0, mr = sr0 + r - e.row0;
            cells[r * w + c] = mc >= 0 && mr >= 0 && mc < e.cols && mr < e.rows ? e.map[mr * e.cols + mc] : (short) 0xFFFF;
        }
        ov.cells(Emu.OV_W, cells, dc0, dr0, w, h, e.tileBase);
    }
    private void applyStage(LgmPages.Scene sc) {
        if (sc.stageCells == null || stageSel == Integer.MIN_VALUE) return;
        int k = -1;
        for (int i = 0; i < LgmPages.STAGE_ORDER.length; i++) if (LgmPages.STAGE_ORDER[i] == stageSel) k = i;
        if (k < 0 || k * 3 + 2 >= sc.stageCells.length) return;
        copyCells(sc, sc.stageCells[k * 3], sc.stageCells[k * 3 + 1], sc.stageDstC, sc.stageDstR, sc.stageCells[k * 3 + 2], 1);
    }
    private void applyPatch(TileCanvas.Encoded p) {
        ov.layer(Emu.OV_W, p.tiles, p.tileBase, p.map, p.cols, p.rows, p.col0, p.row0, null, 0);
    }
    /** 연속 대전 끔 — 도화선 감추고 안내 줄 되돌림 */
    private void cancelAuto() {
        if (!autoOn || autoOff) return;
        autoOff = true;
        if (st == ST_RESULT && cur == sResult && cur != null && cur.fusePane >= 0) {
            ov.cue(null, Emu.OV_SKIP_TO_END);                                /* 도화선(GAUGE)이 끝 = 폭 0 */
            fuseOff(cur);
        }
    }
    private void fuseOff(LgmPages.Scene sc) {
        int[] f = paneDef(sc, sc.fusePane), h = paneDef(sc, sc.hintPane);
        if (f != null) ov.pane(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], false);
        if (h != null) ov.pane(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], true);
    }
    private void setBusy() { busy = true; busyT0 = now; }
    /** 동작 뒤 아무 일도 없었다(UI 가 못 받음) — 부른 장면을 끝 상태로 다시 */
    private void recover() {
        busy = false;
        LgmPages.Scene back = sResult != null ? sResult : sPause;
        if (back == null) { close(); return; }
        if (show(back, SHOW_END, Emu.OV_START_NOW, clampRow(back, back == sResult ? resultCursor : pauseCursor))) { st = back == sResult ? ST_RESULT : ST_PAUSE; capture = true; updateHit(); }
    }

    private static boolean inRow(LgmPages.Row r, int c, int rr) {
        int c0 = r.rc0 >= 0 ? r.rc0 : r.c0, r0 = r.rc0 >= 0 ? r.rr0 : r.r0, c1 = r.rc0 >= 0 ? r.rc1 : r.c1, r1 = r.rc0 >= 0 ? r.rr1 : r.r1;
        return c >= c0 && c <= c1 && rr >= r0 && rr <= r1;
    }
    private static int rowAt(LgmPages.Scene sc, int c, int r) {
        if (sc.rows == null) return -1;
        for (int i = 0; i < sc.rows.length; i++) if (inRow(sc.rows[i], c, r)) return i;
        return -1;
    }
    private static boolean inHitRect(LgmPages.Scene sc, int c, int r) {
        if (rowAt(sc, c, r) >= 0) return true;
        if (sc.hitRect != null) for (int i = 0; i + 3 < sc.hitRect.length; i += 4)
            if (c >= sc.hitRect[i] && c <= sc.hitRect[i + 2] && r >= sc.hitRect[i + 1] && r <= sc.hitRect[i + 3]) return true;
        return false;
    }

    /** hitTest 용 불변 사본 */
    private static final class Hit {
        static final Hit NONE = new Hit(false, new int[0], false);
        final boolean open, outside; final int[] rects;
        Hit(boolean open, int[] rects, boolean outside) { this.open = open; this.rects = rects; this.outside = outside; }
    }
    private void updateHit() {
        int s = st;
        LgmPages.Scene sc = cur;
        if (sc == null || s == ST_CLOSED || s == ST_ARMED || s == ST_PAUSE_PENDING) { hit = Hit.NONE; return; }
        java.util.ArrayList<Integer> a = new java.util.ArrayList<>();
        if (sc.rows != null) for (LgmPages.Row r : sc.rows) addRect(a, r);
        if (sc.stageRow != null) addRect(a, sc.stageRow);
        if (sc.tabs != null) for (LgmPages.Row r : sc.tabs) addRect(a, r);
        if (sc.hitRect != null) for (int v : sc.hitRect) a.add(v);
        int[] rects = new int[a.size()];
        for (int i = 0; i < rects.length; i++) rects[i] = a.get(i);
        hit = new Hit(true, rects, (s == ST_PAUSE) && sc.portraitOutside);
    }
    private static void addRect(java.util.List<Integer> a, LgmPages.Row r) {
        if (r.rc0 >= 0) { a.add(r.rc0); a.add(r.rr0); a.add(r.rc1); a.add(r.rr1); }
        else { a.add(r.c0); a.add(r.r0); a.add(r.c1); a.add(r.r1); }
    }

    private void post(Runnable r) {
        if (ui != null) ui.post(r); else r.run();
    }
}
