package com.dudu.pocketcore;

/**
 * 157 리그 인게임 메뉴 상태 기계 — 결판 · 그만할까요? · 쉼(+ 기권 두 번 묻기) · 서랍(순위·전적·설정).
 * GL 스레드 전용(EmuActivity.onDrawFrame 의 feed, gl.queueEvent 로 오는 나머지). League 를 읽지 않는다 — MenuModel(불변)·LgmPages.Scene 만.
 * 동작(다음 상대·재대결·그만…)은 Actions 를 UI 스레드에(Handler.post) 부른다 — 기존 EmuActivity 함수(rankNext·rankRematch·hubReturn…)가 받는다.
 * native 는 OvPort 로만(기본 = Emu.nativeOv*) — JVM 시험은 가짜 OvPort 를 넣는다.
 * 계약서 docs/157_contract.md §7. 담당: 메뉴 담당(menu builder). 설계 단계 껍데기 — 공개 모양이 계약, 몸통은 TODO.
 */
final class LeagueMenu {
    /* NGPC 버튼 = libretro 비트(Emu) */
    static final int IN_A = 1 << Emu.B, IN_B = 1 << Emu.A, IN_OPT = 1 << Emu.START,
            IN_UP = 1 << Emu.UP, IN_DOWN = 1 << Emu.DOWN, IN_LEFT = 1 << Emu.LEFT, IN_RIGHT = 1 << Emu.RIGHT;
    static final int IN_GAME_ALL = 0xFFF;                       /* 게임 입력 전부(SP 접기 비트 1·9·10·11 포함) */
    /* 상태 */
    static final int ST_CLOSED = 0, ST_ARMED = 1, ST_RESULT = 2, ST_QUIT = 3, ST_PAUSE_PENDING = 4, ST_PAUSE = 5, ST_CONFIRM = 6, ST_DRAWER = 7, ST_CLOSING = 8;
    /* 터치 동작(MotionEvent 와 같은 값) */
    static final int TOUCH_DOWN = 0, TOUCH_UP = 1, TOUCH_MOVE = 2, TOUCH_CANCEL = 3;
    /* 박자(60Hz 시계 프레임) */
    static final int REPEAT_DELAY = 18, REPEAT_EVERY = 5, OPT_TAP_MAX = 30, AUTO_NEXT_FRAMES = 600;

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
        static OvPort emu() { throw new UnsupportedOperationException("157-A TODO"); }
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

    LeagueMenu(OvPort ov, Actions actions, android.os.Handler ui) { throw todo(); }

    /* ── UI 스레드 → (값만) ── */
    /** 랭크 경기 중(H_MATCH 또는 체크포인트 이어 하기의 싸움) · 결판 전 · 찾기 겹창 없음 · 결과 카드 없음 — 이때만 OPTION 을 가로챈다(volatile) */
    void setPauseEligible(boolean on) { throw todo(); }

    /* ── GL 스레드 ── */
    /** 결판 — 정산 때 미리 그린 장면을 «걸쇠에 맞춰 시작»으로 걸어 둔다(ARM_ON_LATCH). 걸쇠가 이미 섰으면 다음 vsync 에 */
    void armResult(MenuModel m, LgmPages.Scene result, LgmPages.Scene quit) { throw todo(); }
    /** 결판을 지금(기권 뒤 — 코어는 ov_hold 로 멈춘 채) */
    void openResultNow(MenuModel m, LgmPages.Scene result, LgmPages.Scene quit) { throw todo(); }
    /** 쉼 — lgmWantPause 뒤 UI 가 준 사본·장면 */
    void openPause(MenuModel m, LgmPages.Scene pause, LgmPages.Scene confirm) { throw todo(); }
    /** 쉼을 못 엶(결판이 이미 남 등) — 멈춤만 풂 */
    void cancelPause() { throw todo(); }
    /** 결판이 열린 채 패드 바 «목록/종료» — 칼집 안을 «그만할까요?»로(쉼이면 쉼 그대로) */
    void openQuit() { throw todo(); }
    /** 서랍 쪽(순위·전적·설정)이 준비됨 — 지금 서랍이면 바꿔 끼움 */
    void showDrawer(int tab, LgmPages.Scene page) { throw todo(); }
    /** ◀▶ 뒤 새 무대 값 */
    void setStage(int stageSel) { throw todo(); }
    /** 늦게 온 조각(순위 «12,483위 ▲212» 등) — 장면의 W 칸 일부를 바꿔 끼움 */
    void patch(TileCanvas.Encoded cells) { throw todo(); }
    /** vsync 마다(onDrawFrame, nativeFrame 앞) — in = padMask|keyMask|axisMask. 돌려줌: 게임에 줄 입력(열려 있으면 0, 닫은 뒤 쥔 키는 뗄 때까지 뺌) */
    int feed(int in, long frameNanos) { throw todo(); }
    /** 게임 픽셀 좌표(0..159 × 0..151 — 칸 = >>3). EmuActivity 가 OVS_VX.. 로 뷰 좌표를 바꿔 넘긴다 */
    void onTouch(int x, int y, int action) { throw todo(); }
    /** UI 스레드 — 게임 px 가 메뉴가 받을 자리인가(줄·탭·칼집·무대 줄, 세로 화면 쉼은 칼집 밖도). 장면이 바뀔 때 GL 스레드가 고친 불변 스냅숏을 읽는다 */
    boolean hitTest(int x, int y) { throw todo(); }
    /** 안드로이드 뒤로 = B */
    void back() { throw todo(); }
    boolean isOpen() { throw todo(); }
    int state() { throw todo(); }
    /** 바로 닫기 — 덧판 지움(OV_CLR_ALL: 멈춤·막기도 풂). hubReturn·나가기 전에 GL 스레드에서 */
    void close() { throw todo(); }

    private static UnsupportedOperationException todo() { return new UnsupportedOperationException("157-A LeagueMenu: 메뉴 담당이 채움"); }
}
