package com.dudu.pocketcore;

/**
 * 157 리그 인게임 메뉴 쪽(장면) 그리기 — MenuModel(불변) → TileCanvas → 덧판에 올릴 «장면»(타일·맵·팔레트·창유리·쿼드·바탕·대본·줄 자리).
 * 그림 스레드(«lgm-render» 하나)에서 돈다 — UI·GL 스레드를 막지 않는다. League 를 읽지 않는다(MenuModel·BoardCache.Board 만).
 * 배치·색·그림은 목업 mock_final.py(우리 그림)를 옮긴다. 계약서 docs/157_contract.md §10. 담당: 메뉴 담당(menu builder). 설계 단계 껍데기.
 */
final class LgmPages {
    static final int SC_RESULT = 1, SC_QUIT = 2, SC_PAUSE = 3, SC_PAUSE_CONFIRM = 4, SC_BOARD = 5, SC_RECORD = 6, SC_SETTINGS = 7;

    /** 커서·터치 한 줄 */
    static final class Row {
        int id;                          // MenuModel.ROW_* · QUIT_* · 설정 항목 번호
        int c0, r0, c1, r1;              // 칸 사각형(포함) — 커서 = nativeOvRecolor(palOn/palOff) · 터치 맞힘
        int palOn, palOff;               // 고른 줄 팔레트 · 보통 팔레트
        int quadX, quadY;                // 단도 쿼드 자리(층 px)
        boolean enabled;
        String desc;                     // 아래 띠 설명(고른 줄)
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
