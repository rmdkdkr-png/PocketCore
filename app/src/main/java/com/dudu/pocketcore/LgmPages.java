package com.dudu.pocketcore;

/**
 * 157 리그 인게임 메뉴 쪽(장면) 그리기 — MenuModel(불변) → TileCanvas → 덧판에 올릴 «장면»(타일·맵·팔레트·창유리·쿼드·바탕·대본·줄 자리).
 * 그림 스레드(«lgm-render» 하나)에서 돈다 — UI·GL 스레드를 막지 않는다. League 를 읽지 않는다(MenuModel·BoardCache.Board 만).
 * 배치·색·그림은 목업 mock_final.py(우리 그림)를 옮긴다. 계약서 docs/157_contract.md §10.
 * (몸통은 다음 커밋 — 이 판은 LeagueMenu 가 쓰는 장면 모양만)
 */
final class LgmPages {
    static final int SC_RESULT = 1, SC_QUIT = 2, SC_PAUSE = 3, SC_PAUSE_CONFIRM = 4, SC_BOARD = 5, SC_RECORD = 6, SC_SETTINGS = 7;
    /** 기권 확인 두 칸(Row.id) */
    static final int CONFIRM_NO = 101, CONFIRM_YES = 102;
    /** 설정 탭 줄(Row.id) */
    static final int SET_FX = 201, SET_LOSS = 202, SET_AUTO = 203, SET_REMEMBER = 204;
    /** 무대 줄 이름 순서(stageCells) — EmuActivity RANK_STAGE_ORDER 와 같다: 상대 기본 → 무작위 → 8곳 */
    static final int[] STAGE_ORDER = { -1, -2, 0, 1, 2, 3, 4, 5, 6, 7 };
    /** 단도 커서 = W 쿼드 0 */
    static final int Q_CURSOR = 0;

    /** 커서·터치 한 줄 */
    static final class Row {
        int id;                          // MenuModel.ROW_* · QUIT_* · CONFIRM_* · SET_*
        int c0, r0, c1, r1;              // 칸 사각형(포함) — 커서 = nativeOvRecolor(palOn/palOff) · 터치 맞힘
        int palOn, palOff;               // 고른 줄 팔레트 · 보통 팔레트
        int quadX, quadY;                // 단도 쿼드 자리(층 px)
        boolean enabled;
        String desc;                     // 아래 띠 설명(고른 줄)
        int descC = -1, descR, descW, descDstC, descDstR;   // 설명 그림이 든 창고 칸(descC < 0 = 없음) → 고르면 (descDstC, descDstR) 로 칸 복사
        int rc0 = -1, rr0, rc1, rr1;     // 터치 맞힘 사각형(rc0 < 0 이면 c0..r1) — 칼집 금 테까지 넓게
    }
    /** 덧판에 올릴 한 판 — 만든 뒤 고치지 않는다. 올리는 쪽은 LeagueMenu(GL 스레드) */
    static final class Scene {
        int kind;
        TileCanvas.Encoded w;            // W 층(32×32 맵 한 장 — 화면 20×19 + 창고 칸)
        TileCanvas.Encoded[] l;          // L 층 묶음(없으면 null)
        int[] panes;                     // {층, idx, c0, r0, c1, r1, dx, dy, 보임} × n
        int[] quads;                     // {층, idx, x, y, tile, pal, flags} × n
        int[] base;                      // {slot, mode, y0, y1, arg, color} × n (OV_BASE_*)
        int lScrollY, lClipY0, lClipY1;
        int[] cue;                       // Cue.build()
        int cueEnd;                      // Cue.end()
        int readyT;                      // 이 프레임부터 고르기를 받음(그 전 A = 연출만 건너뜀) — 결판 «보통» 평범한 판 16
        Row[] rows;                      // 칼집 줄(위에서 아래로) — MenuModel.rows 와 같은 순서
        int[] stageCells;                // 무대 줄 «◀ 무대 이름 ▶» 바꿔 끼울 창고 칸(이름마다 c,r,w) — 없으면 null
        Row stageRow;                    // 무대 줄(◀▶ 터치 반쪽)
        /* ── 메뉴 담당이 더한 칸(장면 ↔ LeagueMenu 약속) ── */
        int stageDstC, stageDstR;        // 무대 이름을 놓을 칸(stageCells 의 w 칸)
        int stageSel = -1;               // 그린 무대 값
        int[] swapCue;                   // 다른 장면에서 돌아올 때 — 끝 상태 + 칼 휘두름(WIPE) · 늘 도는 것
        int[] closeCue; int closeDur;    // 닫기 연출(칼집 닫힘·쉼 닫기·족자 말림) — 끝나면 LeagueMenu 가 동작/닫기
        int defaultRow;                  // 처음 커서
        int fusePane = -1, hintPane = -1;// 연속 대전 도화선(보이는 동안 r18 오른쪽 안내 대신) — 입력이 오면 안내로 되돌림
        int tab = -1;                    // 서랍 탭(MenuModel.Prefs.TAB_*) · 서랍 아니면 −1
        Row[] tabs;                      // 서랍 탭 줄(터치)
        int lScrollMin, lScrollMax;      // 서랍 목록 넘기기 범위(L 스크롤 y)
        boolean portraitOutside;         // (쉼) 칼집 밖 탭 = 이어하기를 받는 장면
        int[] hitRect;                   // {c0,r0,c1,r1} × n — 줄 말고도 메뉴가 받을 자리(칼집 몸통·판 정보 패)
    }
    /** 157-B 자리 — 캐릭터 얼굴 타일 묶음(우리 그림 또는 따로 조사한 것). 157-A 는 늘 null */
    interface FaceProvider { TileCanvas.Encoded face(int ch, int sty, int pal); }

    LgmPages(TileCanvas.Inks inks) { throw todo(); }

    Scene result(MenuModel m) { throw todo(); }
    Scene quit(MenuModel m) { throw todo(); }
    Scene pause(MenuModel m, boolean confirm) { throw todo(); }
    /** 순위 — 위 3명 단상 + 내 주변(내 줄 r11 고정) · b == null 이면 «먹 가는 중…» */
    Scene board(MenuModel m, BoardCache.Board b) { throw todo(); }
    Scene record(MenuModel m) { throw todo(); }
    Scene settings(MenuModel.Prefs p, int cursor) { throw todo(); }

    private static UnsupportedOperationException todo() { return new UnsupportedOperationException("157-A LgmPages: 메뉴 담당이 채움"); }
}
