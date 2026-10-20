package com.dudu.pocketcore;

/**
 * 157 리그 인게임 메뉴 쪽(장면) 그리기 — MenuModel(불변) → TileCanvas → 덧판에 올릴 «장면»(타일·맵·팔레트·창유리·쿼드·바탕·대본·줄 자리).
 * 그림 스레드(«lgm-render» 하나)에서 돈다 — UI·GL 스레드를 막지 않는다. League 를 읽지 않는다(MenuModel·BoardCache.Board 만).
 * 배치·색·그림은 목업 mock_final.py(우리 그림)를 옮긴다(좌표는 목업 그대로 — 글자 y = 한글 몸통 맨 위). 계약서 docs/157_contract.md §10.
 *
 * W 층 한 장면 = 32×32 캔버스 한 장: 화면 칸 c0–19 × r0–18 + 창고(c20–31 · r19–31 — 화면 밖). 창고에는
 *  무대 이름 10개(c20–31 r0–4) · LP 숫자 프레임(COUNT, c20–24 r5–14) · 둘째 호명 띠(c0–9 r19–20, 창유리로 r4–5 에 비춤) ·
 *  줄 설명(c0–11 r21–) · 도화선 글(c11–19 r28) · 단도 커서 타일(c31 r31).
 * 타일 0x000–0x1EF 장면 · 0x1F0–0x1FF 늦은 조각(순위 rankPatch). 팔레트 칸은 장면마다 고정 번호(커서 recolor·대본 PAL 이 번호로 가리킴).
 * 커서 규칙(§10.3): 고른 줄·안 고른 줄의 픽셀은 같다(바탕 칠 3 · 글자 칠 1 · 오른쪽 작은 글 칠 2) — 고르면 팔레트만 SEL 로, 단도는 쿼드.
 */
final class LgmPages {
    static final int SC_RESULT = 1, SC_QUIT = 2, SC_PAUSE = 3, SC_PAUSE_CONFIRM = 4, SC_BOARD = 5, SC_RECORD = 6, SC_SETTINGS = 7,
            SC_LOBBY = 8, SC_LEAGUE = 9, SC_LOBBY_CONFIRM = 10, SC_LOBBY_QUIT = 11, SC_LEAGUE_CONFIRM = 12;   /* 168 대기 · 서랍 리그 탭(+ 두 번 묻기) */
    /** 168 서랍 «리그» 탭 줄(Row.id) */
    static final int LG_NEW_SEASON = 301, LG_RESTART = 302, LG_MAIN = 303, LG_NAME = 304, LG_WIPE = 305;   /* 179 리그 초기화 */
    /** 두 번 묻는 리그 탭 줄(첫 A 펼침 · 둘째 A = 그 일) — 새 시즌 · 처음부터 · 179 리그 초기화 */
    static boolean lgConfirmRow(int id) { return id == LG_NEW_SEASON || id == LG_RESTART || id == LG_WIPE; }
    /** 기권 확인 두 칸(Row.id) */
    static final int CONFIRM_NO = 101, CONFIRM_YES = 102;
    /** 설정 탭 줄(Row.id) */
    static final int SET_FX = 201, SET_LOSS = 202, SET_AUTO = 203, SET_REMEMBER = 204;
    /** 무대 줄 이름 순서(stageCells) — EmuActivity RANK_STAGE_ORDER 와 같다: 상대 기본 → 무작위 → 8곳 */
    static final int[] STAGE_ORDER = { -1, -2, 0, 1, 2, 3, 4, 5, 6, 7 };
    static final String[] STAGE_NAME = { "후지산 마을", "숲속 호숫가", "산 위 신사", "화염 사원", "폐허 사원", "불꽃 동굴", "마계 제단", "수정 신전" };
    /** 단도 커서 = W 쿼드 0 */
    static final int Q_CURSOR = 0;
    /** 늦은 조각 타일 자리 */
    static final int PATCH_BASE = 0x1F0, PATCH_MAX = 16, SCENE_TILES = 0x1F0;
    /** 시험이 켬 — 팔레트 없는 칸에 잉크면 Overflow(앱에서는 끔: 그 칸만 빈 칸) */
    static volatile boolean strict = false;

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
        int[] revealCue;                 // (서랍 순위) 족자를 다 편 뒤 — 끝 상태 + L 목록(보이는 줄)을 칼 휘두름으로 드러냄. 없으면 끝 상태 그대로
        int[] closeCue; int closeDur;    // 닫기 연출(칼집 닫힘·쉼 닫기·족자 말림) — 끝나면 LeagueMenu 가 동작/닫기
        int defaultRow;                  // 처음 커서
        int fusePane = -1, hintPane = -1;// 연속 대전 도화선(보이는 동안 r18 오른쪽 안내 대신) — 입력이 오면 안내로 되돌림
        int tab = -1;                    // 서랍 탭(MenuModel.Prefs.TAB_*) · 서랍 아니면 −1
        Row[] tabs;                      // 서랍 탭 줄(터치)
        int lScrollMin, lScrollMax;      // 서랍 목록 넘기기 범위(L 스크롤 y)
        boolean portraitOutside;         // (쉼) 칼집 밖 탭 = 이어하기를 받는 장면
        int[] hitRect;                   // {c0,r0,c1,r1} × n — 줄 말고도 메뉴가 받을 자리(칼집 몸통·판 정보 패)
        int tiles, pals;                 // W 타일 수·팔레트 수(보고·시험)
        /* ── 168 ── */
        int[] variantIds;                // 같은 쪽의 다른 판(두 번 묻기 펼침 등) — 그 줄 id 와 판(같은 칸·같은 줄)
        Scene[] variants;
        Scene variant(int id) { if (variantIds != null) for (int i = 0; i < variantIds.length; i++) if (variantIds[i] == id) return variants[i]; return null; }
    }
    /** 157-B 자리 — 캐릭터 얼굴 타일 묶음(우리 그림 또는 따로 조사한 것). 157-A 는 늘 null */
    interface FaceProvider { TileCanvas.Encoded face(int ch, int sty, int pal); }

    /* ── 색(12비트 0x0BGR) — 목업 PAL 그대로 ── */
    static final int[] C_INK = { 0xCEF, 0x789, 0x012 }, C_INKZ = { 0xCEF, 0x789, 0x123 }, C_GOLD = { 0x7DF, 0x5BE, 0x012 },
            C_SEL = { 0xFFF, 0x7DF, 0x46E }, C_WARN = { 0x46E, 0x789, 0x012 }, C_SEAL = { 0xFFF, 0x46E, 0x113 }, C_SEALK = { 0xBBB, 0x445, 0x111 },
            C_SEALD = { 0xFFF, 0x5BE, 0x135 }, C_TAIL = { 0x46E, 0x5BE, 0x012 }, C_CHART = { 0x7DF, 0x456, 0x012 },
            C_PLATE1 = { 0xFFF, 0x7DF, 0x135 }, C_PLATEM = { 0xFFF, 0x55F, 0x123 }, C_CHAIN = { 0x46E, 0x789, 0x012 }, C_CHAINZ = { 0x46E, 0x789, 0x123 },
            C_WOOD = { 0x5BE, 0x247, 0x123 }, C_CLOTH = { 0x112, 0x678, 0xCEF }, C_CLOTHK = { 0xCEF, 0x789, 0x234 }, C_CLOTHR = { 0xFFF, 0x7DF, 0x46E },
            C_PIP = { 0x7DF, 0x567, 0x012 },
            C_B_NAME = { 0xFFF, 0x666, 0x000 }, C_B_STY = { 0xFFD, 0xE70, 0x000 }, C_B_DIM = { 0x888, 0x444, 0x000 };
    /** 티어 빛깔(RankVs.TIER_STYLE 과 같은 빛 — 목업 TIER) {밝음, 그림자} */
    static final int[][] TIER_C = { { 0x888, 0x444 }, { 0xBBB, 0x666 }, { 0x07F, 0x035 }, { 0xFFF, 0x888 }, { 0xAFF, 0x6AA }, { 0xFE7, 0x993 }, { 0x22F, 0x118 }, { 0xFFF, 0x00E } };
    static final int LP_YELLOW = 0xAFF;

    /* ── 결판·그만할까요? 팔레트 칸 ── */
    static final int R_NAME_L = 0, R_STY_L = 1, R_LP_L = 2, R_NAME_R = 3, R_LP_R = 4, R_SEAL = 5, R_INK = 6, R_GOLD = 7, R_SEL = 8, R_WARN = 9,
            R_HEAD = 10, R_GAUGE = 11, R_RIB = 12, R_CREST = 13;
    /* ── 결판 창유리 ── */
    static final int PN_SHEATH = 0, PN_NAME_L = 1, PN_NAME_R = 2, PN_SEAL = 3, PN_RIB1 = 4, PN_RIB2 = 5, PN_CREST = 6, PN_RANK = 7, PN_HINT = 8, PN_DESC = 9,
            PN_TAG0 = 10, PN_FUSE = 13;
    static final int MAX_TAGS = 3;
    /* ── 쉼 팔레트·창유리 ── */
    static final int P_INK = 0, P_GOLD = 1, P_SEL = 2, P_WARN = 3, P_TIER = 4, P_RIB = 5, P_PULSE = 6, P_OPPCOL = 7;   /* 177 P_OPPCOL = 상대 컬러 견본 */
    /** 177 쉼 정보 패 상대 컬러 견본 자리 — r6 오른쪽 c8–9(x64–79) · r6 글(티어★ LP)이 이 x 를 넘지 않아야(LgmPages 시험이 모든 티어로 잰다) */
    static final int SWATCH_C0 = 8, SWATCH_R = 6, SWATCH_TEXT_MAX = 62;
    static final int PP_SHEATH = 0, PP_INFO = 1, PP_BOTTOM = 2;
    /* ── 서랍 팔레트 ── */
    static final int D_INK = 0, D_GOLD = 1, D_SEL = 2, D_SEAL = 3, D_PLATE1 = 4, D_PLATEM = 5, D_TIER = 6, D_WOOD = 7, D_CLOTH = 8, D_CLOTHK = 9, D_CLOTHR = 10,
            D_PIP = 11, D_CHART = 12, D_CHAIN = 13, D_LOAD = 14;
    /* ── 바탕 칸 ── */
    static final int B_DIM = 0, B_FILL = 1, B_CLOSE_TOP = 2, B_CLOSE_BOT = 3;
    /* ── 채움 칸 ── */
    static final int F_GAUGE = 0, F_GAUGE_HI = 1, F_TAIL = 2, F_FUSE = 3;

    /* ── 박자(60Hz) — 기획서 §5 · 계약서 §10.4 ── */
    static final int ISSEN = 12, ISSEN_Y = 84, BLEED = 4, NAME_SLIDE = 6, SHEATH_IN = 10, SHEATH_IN_SHORT = 8, CLOSE = 6;
    static final int LP_COUNT = 20, LP_COUNT_SHORT = 10, STAR_FLASH = 12, TAIL_HOLD = 20, TAIL_SHRINK = 6, TAG_GAP = 4;
    static final int RIBBON_IN = 4, RIBBON_HOLD = 24, PROMO_HOLD = 36, SWING = 6, TAB_SWING = 10, UNROLL = 10, ROLLUP = 8;
    static final int PAUSE_BLEED = 4, PAUSE_INFO = 8, SEL_PERIOD = 12, BOB_PERIOD = 32, COUNT_FRAMES = 8;
    static final int GAUGE_X = 90, GAUGE_W = 37;      /* 막대 안쪽(목업 gauge 89..127) */

    private final TileCanvas.Inks ink;
    private FaceProvider faces;                       /* 157-B — 지금은 늘 null */

    LgmPages(TileCanvas.Inks inks) {
        if (inks == null || inks.g11 == null || inks.g11b == null || inks.g7 == null) throw new IllegalArgumentException("글꼴 없음");
        this.ink = inks;
    }
    /** 157-B 자리 — 얼굴 타일(null = 없음). 157-A 는 쓰지 않는다 */
    void setFaces(FaceProvider f) { faces = f; }

    /* ════════════════════════════════════════════════════════════════════ 결판 */

    /** 결판 장면 — 띠(두 이름·낙관·점수) · 칼집(머리 막대·명령 5줄·무대 줄) · 아래 띠(도장·순위·설명) · 호명 띠·승급 문장 · 대본 */
    Scene result(MenuModel m) {
        Sb b = new Sb(SC_RESULT);
        TileCanvas cv = b.cv;
        boolean loss = m.outcome == MenuModel.O_LOSS || m.outcome == MenuModel.O_FORFEIT;
        resultPals(cv, m, false);
        Bars bs = band(b, m);
        boolean nm = m.prefs.fx != MenuModel.Prefs.FX_SHORT;
        /* 호명 띠 — 하나(r4–5) · 둘째는 창고(r19–20)에서 창유리로 같은 자리에 */
        int nr = nm ? m.ribbons.length : 0;
        if (nr >= 1) ribbon(cv, 4, ribbonText(m, m.ribbons[nr >= 2 ? 1 : 0]));   /* 둘이면 낮은 것 먼저 → 높은 것(앞)이 끝 장면에 남는다 */
        if (nr >= 2) ribbon(cv, 19, ribbonText(m, m.ribbons[0]));
        boolean crest = nm && (m.promoted || m.enteredMaster);
        if (crest) {
            cv.cellPal(3, 8, 6, 11, R_CREST);
            cv.art(OvArt.CREST32, 24, 64, 1, 2, 3, false);
        }
        sheath(cv, 4, 16);
        Head hd = head(b, m);
        cmdRows(b, m, 6, R_INK, R_WARN, R_SEL, m.rows, m.rowOn, m.rowRight, labelsResult(m), -1);
        b.scene().defaultRow = m.defaultRow;
        stageRow(b, m.stageSel);
        int ntags = bottomBand(b, m, "A 결정 B 그만", true, m.defaultRow);
        boolean fuse = m.prefs.autoNextSec == 10;
        if (fuse) {
            cv.cellPal(11, 28, 19, 28, R_INK);
            cv.text(fit("10초 뒤 다음 상대", 70, ink.g7), 157, 225, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
        }
        katanaStore(cv, R_SEL);
        /* 창유리 */
        b.pane(PN_SHEATH, 10, 4, 19, 16, 0, 0, true);
        b.pane(PN_NAME_L, 0, 0, 7, 3, 0, 0, true);
        b.pane(PN_NAME_R, 12, 0, 19, 3, 0, 0, true);
        b.pane(PN_SEAL, 8, 0, 11, 3, 0, 0, true);
        if (nr >= 1) b.pane(PN_RIB1, 0, 4, 9, 5, 0, 0, false);
        if (nr >= 2) b.pane(PN_RIB2, 0, 19, 9, 20, 0, (4 - 19) * 8, false);
        if (crest) b.pane(PN_CREST, 3, 8, 6, 11, 0, 0, false);
        b.pane(PN_RANK, 11, 17, 19, 17, 0, 0, true);
        b.pane(PN_HINT, 11, 18, 19, 18, 0, 0, true);
        b.pane(PN_DESC, 0, 18, 10, 18, 0, 0, true);
        for (int k = 0; k < ntags; k++) b.pane(PN_TAG0 + k, b.tagC0[k], 17, b.tagC1[k], 17, 0, 0, true);
        if (fuse) b.pane(PN_FUSE, 11, 28, 19, 28, 0, (18 - 28) * 8, false);
        b.base(B_DIM, Emu.OV_BASE_DIM, 32, 135, 3, 0);
        Scene s = b.finish(TileCanvas.RIGHT);
        if (fuse) { s.fusePane = PN_FUSE; s.hintPane = PN_HINT; }
        /* 대본 */
        Cue q = resultCue(m, hd, bs, nr, crest, ntags, fuse, loss);
        s.cue = q.build(); s.cueEnd = q.end();
        s.readyT = resultReady(m);
        int[] noFuse = resultCue(m, hd, bs, nr, crest, ntags, false, loss).build();
        s.swapCue = concat(snap(noFuse), new Cue().wipe(0, SWING, Cue.W, 10, 4, 19, 16, 2, false, R_GOLD, 1, Cue.EASE_LINEAR).build());
        Cue c = new Cue().slide(0, CLOSE, Cue.W, PN_SHEATH, Cue.AXIS_X, 0, 80, Cue.EASE_IN)
                .base(0, CLOSE, B_CLOSE_TOP, Emu.OV_BASE_FILL, 32, 83, 0, 52, 0x012, Cue.EASE_IN)
                .base(0, CLOSE, B_CLOSE_BOT, Emu.OV_BASE_FILLUP, 84, 135, 0, 52, 0x012, Cue.EASE_IN);
        if (nr >= 1) c.show(0, Cue.W, PN_RIB1, false);
        if (nr >= 2) c.show(0, Cue.W, PN_RIB2, false);
        if (crest) c.show(0, Cue.W, PN_CREST, false);
        s.closeCue = c.build(); s.closeDur = CLOSE;
        s.hitRect = new int[]{ 10, 4, 19, 16 };
        return s;
    }

    /** 그만할까요? — 띠·아래 띠는 결판과 같은 칸(끝 상태), 칼집 안만: 머리 «그만할까요?» · 타이틀로/리그 화면/내 게임/게임 목록/취소 · r16 오늘(세션) */
    Scene quit(MenuModel m) {
        Sb b = new Sb(SC_QUIT);
        TileCanvas cv = b.cv;
        resultPals(cv, m, true);
        Bars bs = band(b, m);
        boolean nm = m.prefs.fx != MenuModel.Prefs.FX_SHORT;
        if (nm && m.ribbons.length >= 1) ribbon(cv, 4, ribbonText(m, m.ribbons[0]));   /* 끝 장면에 남는 띠(가장 앞 사건) */
        sheath(cv, 4, 16);
        cv.cellPal(11, 4, 19, 5, R_HEAD);
        cv.text("그만할까요?", 90, 36, 1, ink.g11b);
        int[] rows = { MenuModel.QUIT_TITLE, MenuModel.QUIT_GAME, MenuModel.QUIT_LIST, MenuModel.QUIT_CANCEL };   /* 168: «리그 화면» 뺌 — 순위표는 서랍 */
        boolean[] on = { true, true, true, true };
        String[] lab = { "타이틀로", "내 게임", "게임 목록", "취소" };
        cmdRows(b, null, 6, R_INK, R_WARN, R_SEL, rows, on, new String[4], lab, 3);
        /* r16 «오늘 4승 1패 +152» — 쉼 아래 띠·서랍 전적과 같은 «오늘»(rankedToday). 세션(G2)은 허브를 다시 켤 때마다 끊겨 같은 «오늘» 글자에 다른 숫자가 나왔다 */
        cv.cellPal(11, 16, 19, 16, R_INK);
        String day = m.todayLine();
        String lps = m.todayW + m.todayL + m.todayD > 0 ? MenuModel.signed(m.todayLp) : null;
        int lim = (lps != null ? 155 - TileCanvas.mini3W(lps) - 4 : 156) - 90;
        cv.text(fit(day, lim, ink.g7), 90, 128, 2, ink.g7);
        if (lps != null) cv.mini3(lps, 155, 129, 1, TileCanvas.RIGHT);
        String[] desc = { "리그는 켠 채 · A로 다시", "내 세이브 게임으로", "게임 목록으로 나가요", "결판으로 돌아가요" };
        for (int i = 0; i < b.rows.size(); i++) b.rows.get(i).desc = desc[i];
        bottomBand(b, m, "A 결정 B 취소", false, 0);
        katanaStore(cv, R_SEL);
        b.pane(PN_SHEATH, 10, 4, 19, 16, 0, 0, true);
        b.base(B_DIM, Emu.OV_BASE_DIM, 32, 135, 3, 0);
        Scene s = b.finish(TileCanvas.RIGHT);
        Cue q = new Cue().wipe(0, SWING, Cue.W, 10, 4, 19, 16, 2, false, R_GOLD, 1, Cue.EASE_LINEAR);
        bandCue(q, bs, 0, 0);                                              /* 띠 LP 막대·숫자 끝 상태 */
        ambient(q, 0, R_SEL);
        s.cue = q.build(); s.cueEnd = q.end(); s.readyT = 0;
        s.swapCue = s.cue;
        s.hitRect = new int[]{ 10, 4, 19, 16 };
        return s;
    }

    /** 순위 늦은 조각 — 결판·그만할까요? 아래 띠 r17 오른쪽(c12–19) «12,483위 ▲212» (타일 0x1F0–0x1FF). 그릴 것이 없으면 null */
    TileCanvas.Encoded rankPatch(MenuModel m) {
        if (m == null || m.rankNow <= 0) return null;
        TileCanvas cv = new TileCanvas(32, 32);
        resultPals(cv, m, true);
        if (!drawRank(cv, m)) return null;
        try { return cv.encodeRect(12, 17, 19, 17, PATCH_BASE, PATCH_MAX, true, strict); }
        catch (TileCanvas.Overflow o) { return null; }
    }

    /* ── 결판 조각들 ── */

    /** 팔레트 — quitLook = 진 쪽 글자를 처음부터 회색(대본 없이 끝 상태) */
    private static void resultPals(TileCanvas cv, MenuModel m, boolean quitLook) {
        boolean win = m.outcome == MenuModel.O_WIN, loss = m.outcome == MenuModel.O_LOSS || m.outcome == MenuModel.O_FORFEIT;
        boolean meDim = quitLook && loss, opDim = quitLook && win;
        setPal(cv, R_NAME_L, meDim ? C_B_DIM : C_B_NAME);
        setPal(cv, R_STY_L, meDim ? C_B_DIM : C_B_STY);
        setPal(cv, R_LP_L, lpPal(m.me));                                  /* LP 줄은 진 쪽도 제 빛(157 post) */
        setPal(cv, R_NAME_R, opDim ? C_B_DIM : C_B_NAME);
        setPal(cv, R_LP_R, lpPal(m.opp));
        setPal(cv, R_SEAL, win ? C_SEAL : m.outcome == MenuModel.O_DRAW ? C_SEALD : C_SEALK);
        setPal(cv, R_INK, C_INK); setPal(cv, R_GOLD, C_GOLD); setPal(cv, R_SEL, C_SEL); setPal(cv, R_WARN, C_WARN);
        setPal(cv, R_HEAD, loss ? C_TAIL : C_GOLD);
        setPal(cv, R_GAUGE, loss ? C_TAIL : C_GOLD);
        setPal(cv, R_RIB, C_SEAL);
        int t = Math.max(0, Math.min(7, m.tierAfter));
        cv.setPal(R_CREST, 0xFFF, TIER_C[t][0], 0x135);
    }
    /** 띠 LP 줄 팔레트 — 1 LP 노랑 · 2 티어 빛(배치는 수련생 빛 — 경기 전 VS 띠 RankVs.placement 가 tier 0 으로 그리는 것과 짝) · 3 검정 */
    private static int[] lpPal(MenuModel.Side s) {
        if (s.placement) return new int[]{ LP_YELLOW, TIER_C[0][0], 0x000 };
        return new int[]{ LP_YELLOW, TIER_C[Math.max(0, Math.min(7, s.tier))][0], 0x000 };
    }

    /** 위 검은 띠(r0–3) — 156 VS 띠와 같은 칸 배치: 왼쪽 나(닉·검질 · 티어★ · LP) · 가운데 낙관 24×24 + 점수 · 오른쪽 상대.
     *  157 post(유저 «승리·패배 후 게이지로 상대와 나 점수 변화 주르르릉 — 인게임 디스플레이 느낌»): r2 = 티어(갈무리7)·LP 숫자(COUNT 로 판 전 → 뒤) ·
     *  r3 = LP 막대(지금 ★ 칸 안 자리 — 게임 체력 막대처럼 바깥 끝에 붙음: 나는 왼쪽 끝부터 · 상대는 오른쪽 끝부터) — 두 사람이 같은 때 같이 오르내린다. 돌려줌: 두 쪽 움직임 */
    private Bars band(Sb b, MenuModel m) {
        TileCanvas cv = b.cv;
        /* 나 */
        String sty = m.me.style;
        String nick = cover7or11(m.me.nick);
        int nickMax = sty != null ? 34 : 60;
        String nk = fit(nick, nickMax, ink.g11);
        int nw = cv.textW(nk, ink.g11);
        int sx = sty != null ? (2 + nw + 3 + 7) / 8 * 8 : 64;
        cv.cellPal(0, 0, Math.max(0, sx / 8 - 1), 1, R_NAME_L);
        cv.text(nk, 2, 2, 1, ink.g11, 2, TileCanvas.LEFT, 0);
        if (sty != null && sx < 64) {
            cv.cellPal(sx / 8, 0, 7, 1, R_STY_L);
            cv.text(sty, sx, 2, 2, ink.g11, 1, TileCanvas.LEFT, 64 - sx);
        }
        /* 상대 */
        cv.cellPal(12, 0, 19, 1, R_NAME_R);
        cv.text(fit(cover7or11(m.opp.nick), 62, ink.g11), 157, 2, 1, ink.g11, 2, TileCanvas.RIGHT, 0);
        /* 티어·LP·막대(r2–3) */
        cv.cellPal(0, 2, 7, 3, R_LP_L);
        cv.cellPal(12, 2, 19, 3, R_LP_R);
        Bars bs = new Bars();
        boolean voidM = m.outcome == MenuModel.O_VOID;
        if (m.mode == MenuModel.M_PLACEMENT && !m.placementDone) {
            bs.l = new Bar();
            cv.text("배치 " + Math.max(0, Math.min(5, 5 - m.placementLeft)) + "/5", 2, 16, 2, ink.g7);   /* 배치 중 — 숫자·막대 없음 */
        } else if (m.mode == MenuModel.M_MASTER) bs.l = sideBar(cv, true, m.mrBefore, voidM ? m.mrBefore : m.mrAfter, true, true);
        else bs.l = sideBar(cv, true, m.mode == MenuModel.M_PLACEMENT ? m.lpAfter : m.lpBefore, m.lpAfter, false, m.enteredMaster || m.tierAfter >= 7);
        bs.r = sideBar(cv, false, m.oppBefore, voidM ? m.oppBefore : m.oppAfter, m.oppMasterBefore, m.oppMasterAfter);
        /* 낙관 + 점수 */
        cv.cellPal(8, 0, 11, 2, R_SEAL);
        if (m.outcome == MenuModel.O_FORFEIT || m.outcome == MenuModel.O_VOID)          /* 기획서 §4.2·§8 — 먹 «기권» · «무효» 두 글자 낙관(무승부 «무» 와 헷갈리지 않게) */
            cv.art(m.outcome == MenuModel.O_FORFEIT ? OvArt.SEAL32_GIGWON : OvArt.SEAL32_MUHYO, 64, 0, 1, 2, 3, false);
        else
            cv.art(m.outcome == MenuModel.O_WIN ? OvArt.SEAL24_SEUNG : m.outcome == MenuModel.O_DRAW ? OvArt.SEAL24_MU : OvArt.SEAL24_PAE, 68, 0, 1, 2, 3, false);
        cv.cellPal(8, 3, 11, 3, R_SEL);
        String score = m.outcome == MenuModel.O_FORFEIT || m.outcome == MenuModel.O_VOID ? "" : m.w1 >= 0 ? m.w1 + " : " + m.w2 : "";
        if (!score.isEmpty()) cv.text(score, 80, 25, 1, ink.g7, -1, TileCanvas.CENTER, 0);
        return bs;
    }

    /* ── 띠 LP 칸(157 post) ── 왼쪽(나) c0–7 · 오른쪽(상대) c12–19. r2: 티어 «검객★3»(칠 2 티어 빛) · LP 숫자(mini4 칠 1 노랑, 3칸) · r3: 막대(테 칠 2 · 채움 칠 1 · 빈 칠 3) */
    static final int BAR_W = 58, BAR_XL = 3, BAR_XR = 99, BAR_Y = 26, BAR_H = 4;
    static final int CNT_L_C = 5, CNT_R_C = 12, CNT_W = 3, CNT_SRC_L = 25, CNT_SRC_R = 28, CNT_SRC_R0 = 5;   /* 숫자 칸 · 프레임 창고(c25–27 · c28–30 × r5..) */
    static final int F_BAR_L = 4, F_BAR_R = 5;
    /** 한쪽 움직임 — 막대(같은 ★ 칸이면 한 번 · ★ 칸이 바뀌면 끝까지 갔다 새 칸에서) · 숫자 프레임 수 */
    static final class Bar {
        boolean gauge, count, two, up; int wFrom, wTo, nframes;
    }
    static final class Bars { Bar l = new Bar(), r = new Bar(); }
    private static int bw(int lp) {
        int[] bd = MenuModel.starBand(lp);
        if (bd[1] <= bd[0]) return BAR_W;
        return Math.max(0, Math.min(BAR_W, (int) ((long) BAR_W * (lp - bd[0]) / (bd[1] - bd[0]))));
    }
    /** 티어 글(왼쪽 = x2 부터 · 오른쪽 = x157 까지) · LP 숫자 칸 · 막대 테 — 숫자 프레임은 창고에. master = MR(막대 없음) */
    private Bar sideBar(TileCanvas cv, boolean left, int before, int after, boolean masterBefore, boolean masterAfter) {
        Bar r = new Bar();
        boolean master = masterBefore || masterAfter;
        int tier = master ? 7 : RankCore.tierOf(after), star = master ? 0 : RankCore.starOf(after);
        String name = RankCore.TIER[tier], label = master ? name : name + "★" + star;
        int lim = 37;                                                         /* 숫자 칸(왼쪽 x40.. · 오른쪽 ..x119) 앞까지 */
        if (cv.textW(label, ink.g7) > lim) label = name;
        if (left) cv.text(label, 2, 16, 2, ink.g7);
        else cv.text(label, 157, 16, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
        int cc = left ? CNT_L_C : CNT_R_C;
        numberAt(cv, cc, 2, after, left);
        r.count = before != after;
        if (r.count) {
            r.nframes = COUNT_FRAMES;
            int src = left ? CNT_SRC_L : CNT_SRC_R;
            for (int k = 0; k < r.nframes; k++) {
                cv.cellPal(src, CNT_SRC_R0 + k, src + CNT_W - 1, CNT_SRC_R0 + k, left ? R_LP_L : R_LP_R);
                int v = before + (int) ((long) (after - before) * k / (r.nframes - 1));
                numberAt(cv, src, CNT_SRC_R0 + k, v, left);
            }
        }
        if (!master) {
            int x0 = left ? 2 : 98;
            for (int x = x0; x <= x0 + 59; x++) { cv.px(x, 25, 2); cv.px(x, 30, 2); }
            for (int y = 25; y <= 30; y++) { cv.px(x0, y, 2); cv.px(x0 + 59, y, 2); }
            cv.fill(x0 + 1, 26, x0 + 58, 29, left ? 3 : 1);                   /* 오른쪽은 채운 채로 두고 빈 몫(칠 3)을 왼쪽 안쪽 끝부터 덮는다(오른쪽 끝에 붙은 막대) */
            r.gauge = true;
            int[] b0 = MenuModel.starBand(before), b1 = MenuModel.starBand(after);
            r.wFrom = bw(before); r.wTo = bw(after);
            r.two = b0[0] != b1[0] && before != after;
            r.up = after > before;
        }
        return r;
    }
    /** LP 숫자 — 칸 (c..c+2, row) 안: 왼쪽은 오른쪽 맞춤(x c*8+22) · 오른쪽은 왼쪽 맞춤(x c*8+2) · 7줄(y row*8 — 그림자 한 줄까지 칸 안) */
    private static void numberAt(TileCanvas cv, int c, int row, int v, boolean left) {
        String t = String.valueOf(Math.max(0, v));
        if (left) cv.mini4(t, c * 8 + CNT_W * 8 - 3, row * 8, 1, 2, TileCanvas.RIGHT);
        else cv.mini4(t, c * 8 + 2, row * 8, 1, 2, TileCanvas.LEFT);
    }
    /** 띠 LP 대본 — 두 쪽 숫자·막대가 t0 부터 dur 동안 같이(★ 칸이 바뀌면 반씩: 끝까지 → 새 칸) */
    private static void bandCue(Cue q, Bars bs, int t0, int dur) {
        bandSide(q, bs.l, true, t0, dur);
        bandSide(q, bs.r, false, t0, dur);
    }
    private static void bandSide(Cue q, Bar b, boolean left, int t0, int dur) {
        if (b == null) return;
        if (b.count) q.count(t0, dur, Cue.W, b.nframes, left ? CNT_L_C : CNT_R_C, 2, CNT_W, 1, left ? CNT_SRC_L : CNT_SRC_R, CNT_SRC_R0, 0, 1, Cue.EASE_LINEAR);
        if (!b.gauge) return;
        int slot = left ? F_BAR_L : F_BAR_R, pal = left ? R_LP_L : R_LP_R, col = left ? 1 : 3, x = left ? BAR_XL : BAR_XR;
        if (!b.two) bar(q, t0, dur, slot, pal, col, x, left, b.wFrom, b.wTo);
        else {
            int d1 = dur / 2, d2 = dur - d1;
            bar(q, t0, d1, slot, pal, col, x, left, b.wFrom, b.up ? BAR_W : 0);
            bar(q, t0 + d1, d2, slot, pal, col, x, left, b.up ? 0 : BAR_W, b.wTo);
        }
    }
    /** 막대 한 토막 — 왼쪽은 채움 폭 그대로 · 오른쪽은 빈 몫(BAR_W − 채움)을 왼쪽 안쪽 끝부터 칠 3 으로 */
    private static void bar(Cue q, int t0, int dur, int slot, int pal, int col, int x, boolean left, int from, int to) {
        q.gauge(t0, Math.max(0, dur), Cue.W, slot, pal, col, x, BAR_Y, BAR_H, left ? from : BAR_W - from, left ? to : BAR_W - to, Cue.EASE_LINEAR);
    }

    /** 호명 띠(16px) — 주홍 바탕, 오른쪽 사선 끝(목업 ribbon, x1 76) */
    private void ribbon(TileCanvas cv, int r, String text) {
        int y0 = r * 8, x1 = 76;
        cv.cellPal(0, r, 9, r + 1, R_RIB);
        for (int y = y0 + 1; y < y0 + 15; y++) cv.fill(0, y, Math.min(x1 - (y - y0) / 2, 159), y, 2);
        cv.fill(0, y0 + 14, x1 - 7, y0 + 14, 3);
        cv.text(fit(text, 68, ink.g11b), 4, y0 + 3, 1, ink.g11b, 3, TileCanvas.LEFT, 0);
    }
    private static String ribbonText(MenuModel m, int e) {
        switch (e) {
            case MenuModel.E_PROMOTE: return "승급! " + RankCore.TIER[Math.max(0, Math.min(7, m.tierAfter))];
            case MenuModel.E_MASTER: return "무쌍 달성!";
            case MenuModel.E_DEMOTE: { String t = RankCore.TIER[Math.max(0, Math.min(7, m.tierAfter))]; return t + (batchim(t) ? "으로" : "로") + " 강등"; }
            case MenuModel.E_STAR_UP: return "★" + m.starAfter + " 올라감!";
            case MenuModel.E_REVENGE: return "설욕 성공!";
            case MenuModel.E_RIVAL: return "라이벌 승리!";
            case MenuModel.E_STREAK: return m.streakAfter + "연승!";
            case MenuModel.E_FIRST_WIN: return "오늘 첫 승!";
            case MenuModel.E_PLACED: return "배치 완료!";
            default: return "";
        }
    }
    private static boolean batchim(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.charAt(s.length() - 1);
        return c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 != 0;
    }

    /** 옻칠 칼집(c10–19 × r0..r1) — 왼쪽 금 테·위 금 줄·위아래 금 마개(목업 sheath) */
    private static void sheath(TileCanvas cv, int r0, int r1) {
        int px = 80;
        cv.fill(px + 1, r0 * 8, 159, r1 * 8 + 7, 3);
        cv.cellPal(11, r0, 19, r1, R_INK);
        cv.cellPal(10, r0, 10, r1, R_GOLD);
        for (int y = r0 * 8; y < r1 * 8 + 8; y++) { cv.px(px + 1, y, 2); cv.px(px + 2, y, (y / 4) % 4 != 0 ? 1 : 2); }
        cv.fill(px, r0 * 8, px + 6, r0 * 8 + 2, 2);
        cv.fill(px + 1, r0 * 8, px + 5, r0 * 8, 1);
        cv.fill(px, r1 * 8 + 5, px + 6, r1 * 8 + 7, 2);
        cv.fill(px + 1, r0 * 8, 159, r0 * 8, 2);
    }

    /** 칼집 머리(r4–5) 그린 것 — 대본이 쓸 막대·숫자 프레임 자리 */
    private static final class Head {
        boolean gauge, count, loss, risk;
        int wFrom, wTo, wTail, nframes;         // 막대 폭(안쪽 37) — wTail = 진 판 잃은 몫(from 쪽 끝)
        boolean twoStep;                        // ★·승급: 끝까지 찬 뒤 새 칸에서 0 → wTo
    }
    private static int gw(int lp, int lo, int hi) {
        if (hi <= lo) return lp >= hi ? GAUGE_W : 0;
        return Math.max(0, Math.min(GAUGE_W, (int) ((long) GAUGE_W * (lp - lo) / (hi - lo))));
    }
    private Head head(Sb b, MenuModel m) {
        TileCanvas cv = b.cv;
        Head h = new Head();
        cv.cellPal(11, 4, 19, 5, R_HEAD);
        boolean loss = m.outcome == MenuModel.O_LOSS || m.outcome == MenuModel.O_FORFEIT;
        h.loss = loss;
        String delta = null;
        if (m.outcome == MenuModel.O_VOID) {
            cv.text("LP 그대로", 89, 38, 2, ink.g7);
        } else if (m.mode == MenuModel.M_PLACEMENT && !m.placementDone) {
            int played = Math.max(0, Math.min(5, 5 - m.placementLeft));
            for (int k = 0; k < 5; k++) cv.art(k < played ? OvArt.PIP_WIN : OvArt.PIP_LOSS, 89 + k * 7, 35, 1, 2, 0, false);
            cv.text("배치 " + played + "/5", 89, 41, 2, ink.g7);                 /* 갈무리7 7줄 — 41..47(r5 안, 아래 명령 줄 r6 으로 안 샘) */
        } else if (m.mode == MenuModel.M_PLACEMENT) {
            cv.text("배치 완료", 89, 34, 1, ink.g7);
            String lb = RankCore.TIER[Math.max(0, Math.min(7, m.tierAfter))] + (m.tierAfter < 7 ? "★" + m.starAfter : "");
            cv.text(lb + " · " + m.lpAfter, 89, 41, 2, ink.g7);
        } else if (m.mode == MenuModel.M_MASTER || m.enteredMaster) {
            cv.text(m.enteredMaster ? "무쌍 달성" : "무쌍", 89, 34, m.enteredMaster ? 1 : 2, ink.g7);
            int x = cv.text("MR", 89, 41, 2, ink.g7);
            cv.mini3(String.valueOf(m.mrAfter), x + 3, 42, 1, TileCanvas.LEFT);
            if (m.mode == MenuModel.M_MASTER) delta = MenuModel.signed(m.mrAfter - m.mrBefore);
        } else if (m.demoted) {
            String a = RankCore.TIER[Math.max(0, Math.min(7, m.tierBefore))], z = RankCore.TIER[Math.max(0, Math.min(7, m.tierAfter))];
            cv.text(fit(a + " → " + z, 67, ink.g7), 89, 34, 2, ink.g7);                 /* 잿빛 «검객 → 무사» — 막대 없이 */
            cv.mini3(m.lpAfter + "/" + m.starNextAfter, 89, 43, 2, TileCanvas.LEFT);
            cv.mini3(MenuModel.signed(m.lpDelta), 156, 43, 1, TileCanvas.RIGHT);
        } else if (m.noviceProtect) {
            /* 수련생이 짐 — 막대 없이 «LP 그대로 · 수련생 보호»(기획서 §4.2). 막대(36..41) 밑엔 7줄 글자 자리가 없다 */
            cv.text("LP 그대로", 89, 34, 1, ink.g7);
            delta = MenuModel.signed(m.lpDelta);
            int dw = cv.textW(delta, ink.g11b);
            cv.text(fit("수련생 보호", 156 - dw - 4 - 89, ink.g7), 89, 41, 2, ink.g7);
        } else {
            /* LP 막대(목업 gauge 89..127 · 36..41) — 안쪽 채움은 대본(GAUGE) */
            for (int x = 89; x <= 127; x++) { cv.px(x, 36, 2); cv.px(x, 41, 2); }
            for (int y = 36; y <= 41; y++) { cv.px(89, y, 2); cv.px(127, y, 2); }
            h.gauge = true;
            boolean same = m.starFloorBefore == m.starFloorAfter;
            h.wTo = gw(m.lpAfter, m.starFloorAfter, m.starNextAfter);
            if (loss) {
                h.wFrom = h.wTo;
                h.wTail = same ? gw(m.lpBefore, m.starFloorAfter, m.starNextAfter) : GAUGE_W;
            } else if (same) {
                h.wFrom = gw(m.lpBefore, m.starFloorBefore, m.starNextBefore);
            } else {
                h.twoStep = m.lpAfter > m.lpBefore;
                h.wFrom = h.twoStep ? gw(m.lpBefore, m.starFloorBefore, m.starNextBefore) : h.wTo;
            }
            h.risk = m.shieldUsed;
            delta = MenuModel.signed(m.lpDelta);
            {
                /* 숫자 «지금/다음★» — 프레임(COUNT)은 창고 c20–24 r5..: 머리 r5 c11–15 칸을 그대로 베끼고 숫자만 다르게 */
                h.count = m.lpBefore != m.lpAfter;
                if (h.count) {
                    h.nframes = COUNT_FRAMES;
                    for (int k = 0; k < h.nframes; k++) {
                        int sy = (5 + k) * 8;
                        for (int y = 0; y < 8; y++) for (int x = 0; x < 40; x++) cv.px(160 + x, sy + y, cv.get(88 + x, 40 + y));
                        cv.cellPal(20, 5 + k, 24, 5 + k, R_HEAD);
                        int v = m.lpBefore + (int) ((long) (m.lpAfter - m.lpBefore) * k / (h.nframes - 1));
                        cv.mini3(v + "/" + m.starNextAfter, 161, sy + 3, 2, TileCanvas.LEFT);
                    }
                }
                cv.mini3(m.lpAfter + "/" + m.starNextAfter, 89, 43, 2, TileCanvas.LEFT);
            }
        }
        if (delta != null) cv.text(delta, 156, 36, 1, ink.g11b, -1, TileCanvas.RIGHT, 0);
        return h;
    }

    /** 명령 줄(16px, 갈무리11) — 칸 c11–19 × r(top+2k)..+1 · 글자 (99, y+3) · 오른쪽 작은 글(«2/3» mini3 / «저장» g7) · 흐린 줄 = 글자 칠 2 */
    private void cmdRows(Sb b, MenuModel m, int rTop, int palInk, int palWarn, int palSel, int[] ids, boolean[] on, String[] right, String[] labels, int dimIdx) {
        TileCanvas cv = b.cv;
        for (int k = 0; k < ids.length; k++) {
            int r = rTop + 2 * k, y = r * 8;
            boolean warn = m != null && ids[k] == MenuModel.ROW_FORFEIT;
            boolean dim = !on[k] || k == dimIdx;
            cv.cellPal(11, r, 19, r + 1, warn ? palWarn : palInk);
            cv.text(fit(labels[k], 52, ink.g11), 99, y + 3, dim ? 2 : 1, ink.g11);
            String rt = right != null && k < right.length ? right[k] : null;
            if (rt != null) {
                if (mini3able(rt)) cv.mini3(rt, 155, y + 6, warn ? 1 : 2, TileCanvas.RIGHT);
                else cv.text(rt, 155, y + 5, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
            }
            Row row = new Row();
            row.id = ids[k]; row.c0 = 11; row.r0 = r; row.c1 = 19; row.r1 = r + 1;
            row.rc0 = 10; row.rr0 = r; row.rc1 = 19; row.rr1 = r + 1;
            row.palOn = palSel; row.palOff = warn ? palWarn : palInk;
            row.quadX = 89; row.quadY = y + 4;
            row.enabled = on[k];
            row.desc = m != null && k < m.rowDesc.length ? m.rowDesc[k] : null;
            b.rows.add(row);
        }
    }
    private static String[] labelsResult(MenuModel m) {
        String[] l = new String[m.rows.length];
        for (int i = 0; i < l.length; i++) l[i] = rowLabel(m, m.rows[i]);
        return l;
    }
    static String rowLabel(MenuModel m, int id) {
        switch (id) {
            case MenuModel.ROW_NEXT: return "다음 상대";
            case MenuModel.ROW_REMATCH: return m != null && m.outcome == MenuModel.O_LOSS ? "설욕전" : "재대결";
            case MenuModel.ROW_NEWCHAR: return "새 캐릭터";
            case MenuModel.ROW_DRAWER: return "순위·전적";
            case MenuModel.ROW_QUIT: return "그만하기";
            case MenuModel.ROW_RESUME: return "이어하기";
            case MenuModel.ROW_LATER: return "나중에";
            case MenuModel.ROW_FORFEIT: return "기권";
            case MenuModel.ROW_VOIDQUIT: return "그만두기";
            default: return "";
        }
    }

    /** 무대 줄(r16) «◀ 무대 이름 ▶» — 이름 10개는 창고에(c20–31 r0–4, 6칸씩) · 지금 값은 화면에도 */
    private void stageRow(Sb b, int sel) {
        TileCanvas cv = b.cv;
        int r = 16;
        cv.cellPal(11, r, 11, r, R_GOLD); cv.cellPal(18, r, 19, r, R_GOLD); cv.cellPal(12, r, 17, r, R_INK);
        cv.art(OvArt.ARROW_L, 90, r * 8, 1, 0, 0, false);
        cv.art(OvArt.ARROW_R, 150, r * 8, 1, 0, 0, false);
        int[] cells = new int[STAGE_ORDER.length * 3];
        for (int k = 0; k < STAGE_ORDER.length; k++) {
            int c0 = 20 + 6 * (k % 2), r0 = k / 2, x0 = c0 * 8, y0 = r0 * 8;
            cv.fill(x0, y0, x0 + 47, y0 + 7, 3);
            cv.cellPal(c0, r0, c0 + 5, r0, R_INK);
            stageLabel(cv, STAGE_ORDER[k], x0 + 26, y0);
            cells[k * 3] = c0; cells[k * 3 + 1] = r0; cells[k * 3 + 2] = 6;
            if (STAGE_ORDER[k] == sel) stageLabel(cv, sel, 122, r * 8);
        }
        boolean known = false;
        for (int v : STAGE_ORDER) known |= v == sel;
        if (!known) stageLabel(cv, -1, 122, r * 8);
        b.scene().stageCells = cells;
        b.scene().stageDstC = 12; b.scene().stageDstR = r;
        b.scene().stageSel = known ? sel : -1;
        Row sr = new Row();
        sr.id = -1; sr.c0 = 10; sr.r0 = r; sr.c1 = 19; sr.r1 = r; sr.enabled = true;
        b.scene().stageRow = sr;
    }
    /** «무대 랜덤» 가운데 cx — 길면 «무대» 빼고 이름만(48px 안) */
    private void stageLabel(TileCanvas cv, int sel, int cx, int y) {
        String name = sel == -1 ? "상대 기본" : sel == -2 ? "랜덤" : sel >= 0 && sel < STAGE_NAME.length ? STAGE_NAME[sel] : "?";
        int w = cv.textW("무대 " + name, ink.g7);
        if (w <= 46) {
            int x = cx - w / 2;
            x = cv.text("무대", x, y, 2, ink.g7);
            cv.text(name, x + 4, y, 1, ink.g7);
        } else {
            String n = fit(name, 46, ink.g7);
            cv.text(n, cx, y, 1, ink.g7, -1, TileCanvas.CENTER, 0);
        }
    }

    /** 아래 검은 띠(r17–18) — 도장(창유리마다) · 순위 · 고른 줄 설명(창고에서 바꿔 끼움) · 버튼 안내. 돌려줌: 도장 수 */
    private int bottomBand(Sb b, MenuModel m, String hint, boolean tagPanes, int def) {
        TileCanvas cv = b.cv;
        int c = 0, n = 0;
        for (MenuModel.Tag t : m.tags) {
            if (n >= MAX_TAGS) break;
            String txt = fit(t.text, 60, ink.g7);
            int w = (cv.textW(txt, ink.g7) + 4 + 7) / 8;
            if (c + 1 + w > 11) break;
            seal8(cv, c, 17, t.seal, R_RIB);
            cv.cellPal(c + 1, 17, c + w, 17, R_INK);
            cv.text(txt, (c + 1) * 8 + 2, 137, 1, ink.g7);
            b.tagC0[n] = c; b.tagC1[n] = c + w;
            n++;
            c += 1 + w + 1;
        }
        drawRank(cv, m);
        /* 설명 — 고른 줄마다 창고 r21.. 에(c0–10), 화면엔 처음 커서 줄 것 */
        for (int i = 0; i < b.rows.size(); i++) {
            Row row = b.rows.get(i);
            int sr = 21 + i;
            cv.cellPal(0, sr, 10, sr, R_INK);
            if (row.desc != null) cv.text(fit(row.desc, 86, ink.g7), 2, sr * 8 + 1, 2, ink.g7);
            row.descC = 0; row.descR = sr; row.descW = 11; row.descDstC = 0; row.descDstR = 18;
        }
        cv.cellPal(0, 18, 10, 18, R_INK);
        if (def >= 0 && def < b.rows.size() && b.rows.get(def).desc != null) cv.text(fit(b.rows.get(def).desc, 86, ink.g7), 2, 145, 2, ink.g7);
        cv.cellPal(11, 18, 19, 18, R_INK);
        cv.text(fit(hint, 70, ink.g7), 157, 145, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
        return tagPanes ? n : 0;
    }
    /** 순위 «12,483위 ▲212» — r17 c12–19(금 팔레트, 칠 1·2만) · 모르면 false */
    private boolean drawRank(TileCanvas cv, MenuModel m) {
        if (m.rankNow <= 0) return false;
        int x1 = 157;
        cv.cellPal(12, 17, 19, 17, R_GOLD);
        String rk = String.format(java.util.Locale.KOREA, "%,d위", m.rankNow);
        if (m.rankBefore > 0 && m.rankBefore != m.rankNow) {
            long d = m.rankBefore - m.rankNow;
            String ds = String.format(java.util.Locale.US, "%,d", Math.abs(d));
            int dw = TileCanvas.mini3W(ds);
            cv.mini3(ds, x1, 138, 1, TileCanvas.RIGHT);
            cv.art(d > 0 ? OvArt.TRI_UP : OvArt.TRI_DN, x1 - dw - 7, 138, d > 0 ? 1 : 2, 0, 0, false);
            cv.text(rk, x1 - dw - 10, 136, 2, ink.g7, -1, TileCanvas.RIGHT, 0);        /* 136 — «12,483위» 쉼표 꼬리(8째 줄)까지 r17 안 */
        } else cv.text(rk, x1, 136, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
        return true;
    }
    /** 꼬리표 도장 8×8 — 인주 틀(OvArt) + 갈무리7 한 글자(7×7 안) */
    private void seal8(TileCanvas cv, int c, int r, char ch, int pal) {
        int x = c * 8, y = r * 8;
        cv.cellPal(c, r, c, r, pal);
        cv.art(OvArt.SEAL8_FRAME, x, y, 0, 2, 0, false);
        cv.text(String.valueOf(ch), x, y, 1, ink.g7, -1, TileCanvas.LEFT, 7);
    }
    /** 단도 커서 타일 — 창고 c31 r31(쿼드가 이 타일을 씀) */
    private static void katanaStore(TileCanvas cv, int pal) {
        cv.cellPal(31, 31, 31, 31, pal);
        cv.art(OvArt.KATANA, 248, 248, 1, 2, 0, false);
    }

    /** 결판 대본 — 계약서 §10.4(보통 ≈36f · 사건 판 일섬 +12 · 호명 띠 24f · 승급 문장 36f / 짧게 ≈18f) */
    private Cue resultCue(MenuModel m, Head h, Bars bs, int nr, boolean crest, int ntags, boolean fuse, boolean loss) {
        Cue q = new Cue();
        boolean nm = m.prefs.fx != MenuModel.Prefs.FX_SHORT;
        int e = nm && m.eventful ? ISSEN : 0;
        if (e > 0) q.flash(0, ISSEN_Y, 0xFFF, 0x7DF);
        q.dim(e, nm ? BLEED : 2, B_DIM, 32, 135, 4, 3);
        if (nm) {
            q.show(e + 2, Cue.W, PN_NAME_L, true).slide(e + 2, NAME_SLIDE, Cue.W, PN_NAME_L, Cue.AXIS_X, -48, 0, Cue.EASE_LINEAR);
            q.show(e + 2, Cue.W, PN_NAME_R, true).slide(e + 2, NAME_SLIDE, Cue.W, PN_NAME_R, Cue.AXIS_X, 48, 0, Cue.EASE_LINEAR);
        }
        int tS = nm ? e + 4 : 0;
        q.show(tS, Cue.W, PN_SEAL, true);
        q.stamp(tS, 4, Cue.W, R_SEAL, 2, 2, 0xFFF);
        boolean win = m.outcome == MenuModel.O_WIN;
        /* 진 쪽 이름만 회색 — 157 post: LP 줄(티어·숫자·막대)은 제 빛 그대로(두 사람 LP 가 같이 오르내리는 게 보이게) */
        if (win) q.pal(tS, Cue.W, R_NAME_R, C_B_DIM[0], C_B_DIM[1], C_B_DIM[2]);
        if (loss) { q.pal(tS, Cue.W, R_NAME_L, C_B_DIM[0], C_B_DIM[1], C_B_DIM[2]); q.pal(tS, Cue.W, R_STY_L, C_B_DIM[0], C_B_DIM[1], C_B_DIM[2]); }
        int tK = nm ? e + 6 : 2, dK = nm ? SHEATH_IN : SHEATH_IN_SHORT;
        q.show(tK, Cue.W, PN_SHEATH, true).slide(tK, dK, Cue.W, PN_SHEATH, Cue.AXIS_X, 72, 0, Cue.EASE_OUT);
        q.show(tK, Cue.W, PN_RANK, true).show(tK, Cue.W, PN_HINT, true).show(tK, Cue.W, PN_DESC, true);
        int ready = tK + dK;
        /* LP — 숫자(COUNT) · 막대(GAUGE) · ★ 문턱 번쩍 · 진 판 꼬리 */
        int tL = nm ? e + 10 : 6, dL = nm ? LP_COUNT : LP_COUNT_SHORT, lpEnd = tL + dL;
        if (h.gauge) {
            int gp = R_GAUGE;
            if (h.twoStep) {
                int d1 = dL / 2, d2 = dL - d1, fl = nm ? STAR_FLASH : 0;
                gauge(q, tL, d1, gp, h.wFrom, GAUGE_W, true);
                if (fl > 0) q.palCycle(tL + d1, fl, Cue.W, gp, 2, 0x5BE, 0xFFF, 4, true);
                gauge(q, tL + d1 + fl, d2, gp, 0, h.wTo, true);
                lpEnd = tL + d1 + fl + d2;
            } else if (loss) {
                gauge(q, tL, 0, gp, h.wTo, h.wTo, false);
                if (h.wTail > h.wTo) {
                    int tt = tL + (nm ? TAIL_HOLD : 6), dt = nm ? TAIL_SHRINK : 4;
                    q.gauge(tL, 0, Cue.W, F_TAIL, gp, 1, GAUGE_X + h.wTo, 37, 4, h.wTail - h.wTo, h.wTail - h.wTo, Cue.EASE_LINEAR);
                    q.gauge(tt, dt, Cue.W, F_TAIL, gp, 1, GAUGE_X + h.wTo, 37, 4, h.wTail - h.wTo, 0, Cue.EASE_IN);
                    lpEnd = Math.max(lpEnd, tt + dt);
                }
            } else gauge(q, tL, dL, gp, h.wFrom, h.wTo, true);
        }
        if (h.count) q.count(tL, lpEnd - tL, Cue.W, h.nframes, 11, 5, 5, 1, 20, 5, 0, 1, Cue.EASE_LINEAR);
        bandCue(q, bs, tL, dL);                                            /* 157 post: 띠의 두 사람 LP 숫자·막대 — 칼집 머리 숫자와 같은 때 같이 */
        /* 아래 띠 도장 */
        int tT = nm ? e + 18 : 10;
        for (int k = 0; k < ntags; k++) q.show(tT + (nm ? k * TAG_GAP : 0), Cue.W, PN_TAG0 + k, true);
        /* 호명 띠 · 승급 문장(보통만) */
        if (nr >= 1) {
            int tR = tK, tR2 = tR + RIBBON_IN + RIBBON_HOLD;
            q.show(tR, Cue.W, PN_RIB1, true).slide(tR, RIBBON_IN, Cue.W, PN_RIB1, Cue.AXIS_X, 80, 0, Cue.EASE_OUT);
            if (nr >= 2) {
                q.show(tR2, Cue.W, PN_RIB1, false);
                q.show(tR2, Cue.W, PN_RIB2, true).slide(tR2, RIBBON_IN, Cue.W, PN_RIB2, Cue.AXIS_X, 80, 0, Cue.EASE_OUT);
            }
            if (crest) {
                int tC = tR2;
                q.show(tC, Cue.W, PN_CREST, true).stamp(tC, 4, Cue.W, R_CREST, 2, 2, 0xFFF).show(tC + PROMO_HOLD, Cue.W, PN_CREST, false);
            }
        } else if (crest) {
            q.show(ready, Cue.W, PN_CREST, true).stamp(ready, 4, Cue.W, R_CREST, 2, 2, 0xFFF).show(ready + PROMO_HOLD, Cue.W, PN_CREST, false);
        }
        /* 연속 대전 도화선 — readyT 부터 600f(입력이 오면 LeagueMenu 가 끔) */
        if (fuse) {
            q.show(ready, Cue.W, PN_HINT, false).show(ready, Cue.W, PN_FUSE, true);
            q.gauge(ready, LeagueMenu.AUTO_NEXT_FRAMES, Cue.W, F_FUSE, R_GOLD, 1, 88, 143, 1, 72, 0, Cue.EASE_LINEAR);
        }
        if (h.risk) q.palCycle(ready, Cue.AMBIENT, Cue.W, R_HEAD, 2, 0x5BE, 0x46E, 16, true);   /* 강등 위기 — 막대 테 주홍 깜빡 */
        ambient(q, ready, R_SEL);
        return q;
    }
    private static void gauge(Cue q, int t0, int dur, int pal, int from, int to, boolean hi) {
        if (hi) {
            q.gauge(t0, dur, Cue.W, F_GAUGE, pal, 2, GAUGE_X, 38, 3, from, to, Cue.EASE_LINEAR);
            q.gauge(t0, dur, Cue.W, F_GAUGE_HI, pal, 1, GAUGE_X, 37, 1, from, to, Cue.EASE_LINEAR);
        } else q.gauge(t0, dur, Cue.W, F_GAUGE, pal, 2, GAUGE_X, 37, 4, from, to, Cue.EASE_LINEAR);
    }
    private static int resultReady(MenuModel m) {
        boolean nm = m.prefs.fx != MenuModel.Prefs.FX_SHORT;
        int e = nm && m.eventful ? ISSEN : 0;
        return nm ? e + 6 + SHEATH_IN : 2 + SHEATH_IN_SHORT;
    }
    /** 늘 도는 것 — 고른 줄 금빛 일렁임 · 단도 까딱(새 대본마다 다시) */
    private static void ambient(Cue q, int t0, int palSel) {
        q.palCycle(t0, Cue.AMBIENT, Cue.W, palSel, 2, 0x7DF, 0x5BE, SEL_PERIOD, false);
        q.bob(t0, Cue.AMBIENT, Cue.W, Q_CURSOR, Cue.AXIS_X, 1, BOB_PERIOD);
    }

    /* ════════════════════════════════════════════════════════════════════ 쉼 */

    /** 쉼 — HUD(0–31줄) 안 가림 · 무대 DIM 2/4 · 왼쪽 위 판 정보 패 · 칼집(이어하기/순위·전적/나중에/기권) · 아래 먹 띠. confirm = 기권 두 번 묻기 펼침 */
    Scene pause(MenuModel m, boolean confirm) {
        Sb b = new Sb(confirm ? SC_PAUSE_CONFIRM : SC_PAUSE);
        TileCanvas cv = b.cv;
        setPal(cv, P_INK, C_INK); setPal(cv, P_GOLD, C_GOLD); setPal(cv, P_SEL, C_SEL); setPal(cv, P_WARN, C_WARN); setPal(cv, P_RIB, C_SEAL);
        setPal(cv, P_PULSE, C_SEL);
        cv.setPal(P_TIER, LP_YELLOW, TIER_C[Math.max(0, Math.min(7, m.opp.tier))][0], 0x012);
        infoPlate(cv, m);
        int rEnd = 14;                                                       /* 157 post: 기권 확인은 같은 칼집 안에서(줄 그대로 · 기권 줄 맥박 · 아래 띠 글) — 따로 «아니오/기권» 칸 없음 */
        sheathP(cv, 4, rEnd);
        cv.cellPal(11, 4, 19, 5, P_GOLD);
        cv.text("쉼", 90, 36, 1, ink.g11b);
        if (m.roundsMe >= 0 && m.roundsOpp >= 0) {
            int round = Math.min(5, m.roundsMe + m.roundsOpp + 1);
            cv.text(round + "라운드 " + m.roundsMe + ":" + m.roundsOpp, 156, 38, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
        }
        String[] lab = new String[m.rows.length];
        for (int i = 0; i < lab.length; i++) lab[i] = m.rows[i] == MenuModel.ROW_VOIDQUIT ? "그만두기" : rowLabel(m, m.rows[i]);
        String[] right = m.rowRight.clone();
        for (int i = 0; i < right.length; i++) if (m.rows[i] == MenuModel.ROW_VOIDQUIT) right[i] = null;   /* «그만두기»(48px)와 오른쪽 «무효»가 겹쳤다 — 무효는 아래 띠 설명이 말함 */
        cmdRows(b, m, 6, P_INK, P_WARN, P_SEL, m.rows, m.rowOn, right, lab, -1);
        int fi = m.rowIndex(MenuModel.ROW_FORFEIT);
        if (confirm && fi >= 0) {                                            /* 157 post: 한 번에 묻기 — 기권 줄이 주홍으로 맥박(고른 줄 팔레트를 맥박 칸으로), A 한 번 더 = 기권 */
            Row fr = b.rows.get(fi);
            fr.palOn = P_PULSE;
        }
        /* 아래 먹 띠(불투명) — 위 금 줄 */
        cv.cellPal(0, 16, 10, 16, P_GOLD);
        cv.cellPal(11, 16, 19, 16, P_GOLD);
        cv.fill(0, 135, 159, 135, 2);
        cv.fill(0, 136, 159, 151, 3);
        cv.cellPal(0, 17, 19, 18, P_INK);
        if (confirm) {
            String line = m.forfeit != null ? m.forfeit.line : "패배로 기록돼요";
            if (m.forfeit != null && cv.textW(line, ink.g7) > 155) line = m.forfeit.shorter;   /* 강등·강등 위기가 붙으면 «연승 끊김»은 뺌(잘리지 않게) */
            cv.text(fit(line, 156, ink.g7), 2, 137, 1, ink.g7);
            cv.text(fit("A 한 번 더 = 기권 · B 취소", 156, ink.g7), 157, 145, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
            for (Row row : b.rows) row.descC = -1;                          /* 줄 설명을 아래 띠로 베끼지 않음(위 글이 남게) — 커서가 옮기면 쉼으로 돌아간다 */
        } else {
            String today = m.todayLine();
            int tw = cv.textW(today, ink.g7);
            cv.text(today, 157, 137, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
            int dw = Math.max(40, 157 - tw - 6 - 2);
            for (int i = 0; i < b.rows.size(); i++) {      /* 줄 설명 — 창고 r21.. (먹 바탕 그대로) */
                Row row = b.rows.get(i);
                int sr = 21 + i;
                cv.fill(0, sr * 8, 12 * 8 - 1, sr * 8 + 7, 3);
                cv.cellPal(0, sr, 11, sr, P_INK);
                if (row.desc != null) cv.text(fit(row.desc, Math.min(dw, 94), ink.g7), 2, sr * 8 + 1, 1, ink.g7);
                row.descC = 0; row.descR = sr; row.descW = 12; row.descDstC = 0; row.descDstR = 17;
            }
            Row d0 = b.rows.isEmpty() ? null : b.rows.get(0);
            if (d0 != null && d0.desc != null) cv.text(fit(d0.desc, Math.min(dw, 94), ink.g7), 2, 137, 1, ink.g7);
            cv.text(fit("A 결정 · B/OPTION 이어하기", 156, ink.g7), 157, 145, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
        }
        katanaStore(cv, P_SEL);
        b.pane(PP_SHEATH, 10, 4, 19, rEnd, 0, 0, true);
        b.pane(PP_INFO, 0, 4, 9, 9, 0, 0, true);
        b.pane(PP_BOTTOM, 0, 16, 19, 18, 0, 0, true);
        b.base(B_DIM, Emu.OV_BASE_DIM, 32, 151, 2, 0);
        Scene s = b.finish(TileCanvas.RIGHT);
        s.portraitOutside = true;
        s.hitRect = new int[]{ 10, 4, 19, rEnd, 0, 4, 9, 9 };
        if (confirm) {
            s.defaultRow = Math.max(0, fi);
            Cue q = new Cue().wipe(0, SWING, Cue.W, 0, 17, 19, 18, 2, false, P_GOLD, 1, Cue.EASE_LINEAR);   /* 아래 띠 글만 칼 휘두름으로 바뀜 */
            q.palCycle(0, Cue.AMBIENT, Cue.W, P_PULSE, 3, 0x46E, 0x239, 16, false);
            ambient(q, 0, P_SEL);
            s.cue = q.build(); s.cueEnd = q.end(); s.readyT = 0; s.swapCue = s.cue;
        } else {
            Cue q = new Cue().dim(0, PAUSE_BLEED, B_DIM, 32, 151, 4, 2);
            q.show(0, Cue.W, PP_SHEATH, true).slide(0, SHEATH_IN, Cue.W, PP_SHEATH, Cue.AXIS_X, 72, 0, Cue.EASE_OUT);
            q.show(0, Cue.W, PP_INFO, true).slide(0, PAUSE_INFO, Cue.W, PP_INFO, Cue.AXIS_X, -80, 0, Cue.EASE_OUT);
            ambient(q, SHEATH_IN, P_SEL);
            s.cue = q.build(); s.cueEnd = q.end(); s.readyT = SHEATH_IN;
            s.swapCue = snap(s.cue);
        }
        Cue c = new Cue().slide(0, CLOSE, Cue.W, PP_SHEATH, Cue.AXIS_X, 0, 80, Cue.EASE_IN)
                .slide(0, CLOSE, Cue.W, PP_INFO, Cue.AXIS_X, 0, -80, Cue.EASE_IN)
                .dim(0, CLOSE, B_DIM, 32, 151, 2, 4).show(0, Cue.W, PP_BOTTOM, false);
        s.closeCue = c.build(); s.closeDur = CLOSE;
        return s;
    }
    /** 쉼 칼집 — 결판 칼집과 같은 그림, 팔레트 칸만 쉼 장면 번호 */
    private static void sheathP(TileCanvas cv, int r0, int r1) {
        int px = 80;
        cv.fill(px + 1, r0 * 8, 159, r1 * 8 + 7, 3);
        cv.cellPal(11, r0, 19, r1, P_INK);
        cv.cellPal(10, r0, 10, r1, P_GOLD);
        for (int y = r0 * 8; y < r1 * 8 + 8; y++) { cv.px(px + 1, y, 2); cv.px(px + 2, y, (y / 4) % 4 != 0 ? 1 : 2); }
        cv.fill(px, r0 * 8, px + 6, r0 * 8 + 2, 2);
        cv.fill(px + 1, r0 * 8, px + 5, r0 * 8, 1);
        cv.fill(px, r1 * 8 + 5, px + 6, r1 * 8 + 7, 2);
        cv.fill(px + 1, r0 * 8, 159, r0 * 8, 2);
    }
    /** 판 정보 패 r6 «티어★ LP»(무쌍은 «무쌍 MR 1234») — 돌려줌: 글 끝 x */
    int infoR6(TileCanvas cv, MenuModel.Side o) {
        int x = cv.text(RankCore.TIER[Math.max(0, Math.min(7, o.tier))], 4, 49, 2, ink.g7);
        if (o.master) {
            x = cv.text("MR", x + 3, 49, 2, ink.g7);
            return cv.mini3(String.valueOf(o.lp), x + 4, 50, 1, TileCanvas.LEFT);
        }
        cv.art(OvArt.STAR5, x + 1, 50, 2, 0, 0, false);
        x = cv.text(String.valueOf(o.stars), x + 7, 49, 2, ink.g7);
        return cv.mini3(String.valueOf(o.lp), x + 6, 50, 1, TileCanvas.LEFT);
    }
    /** 177 상대 컬러 견본(쉼 정보 패 r6 c8–9 · 타일 2개) — 3색 막대: 옷(색 2) 5px · 밝은색(색 1) 5px · 테(색 3) 5px, 둘레 1px 투명.
     *  팔레트 P_OPPCOL = 상대가 지금 보이는 세 색(거울 대신 색이면 그 색 — MenuModel.oppSwatch) */
    void oppSwatch(TileCanvas cv, int[] t) {
        int x0 = SWATCH_C0 * 8, y0 = SWATCH_R * 8;
        cv.setPal(P_OPPCOL, t[0] & 0xFFF, t[1] & 0xFFF, t[2] & 0xFFF);
        cv.cellPal(SWATCH_C0, SWATCH_R, SWATCH_C0 + 1, SWATCH_R, P_OPPCOL);
        cv.fill(x0, y0, x0 + 15, y0 + 7, 0);
        cv.fill(x0 + 1, y0 + 1, x0 + 5, y0 + 6, 2);
        cv.fill(x0 + 6, y0 + 1, x0 + 10, y0 + 6, 1);
        cv.fill(x0 + 11, y0 + 1, x0 + 15, y0 + 6, 3);
    }
    /** 판 정보 패(c0–9 × r4–9) — vs 닉 · 티어★ LP(+ 177 상대 컬러 견본) · [꼬리표] 나와 n-m · 이기면/지면(목업 info_plate) */
    private void infoPlate(TileCanvas cv, MenuModel m) {
        cv.fill(0, 32, 79, 71, 3);
        cv.cellPal(0, 4, 9, 5, P_INK);
        cv.text("vs", 4, 36, 2, ink.g7);
        cv.text(fit(cover7or11(m.opp.nick), 62, ink.g11), 16, 34, 1, ink.g11);
        cv.cellPal(0, 6, 9, 6, P_TIER);
        int r6 = infoR6(cv, m.opp);
        if (m.oppSwatch != null && m.oppSwatch.length == 3 && r6 <= SWATCH_TEXT_MAX) oppSwatch(cv, m.oppSwatch);   /* 177 — 글이 자리를 넘으면(시험상 없음) 견본을 빼서 글 색이 안 바뀌게 */
        /* 꼬리표 + 나와 전적 */
        String tag = m.oppTag;
        char seal = tag == null ? 0 : tag.equals("라이벌") ? '라' : tag.equals("설욕전") ? '설' : tag.equals("첫 대전") ? '첫' : tag.equals("천적") ? '천' : '끊';
        String h2h = m.h2hW + m.h2hL + m.h2hD == 0 ? "첫 대전" : "나와 " + m.h2hW + "-" + m.h2hL + (tag != null && !tag.equals("첫 대전") ? " · " + tag : "");
        if (seal != 0) {
            seal8(cv, 0, 7, seal, P_RIB);
            cv.cellPal(1, 7, 9, 7, P_INK);
            cv.text(fit(h2h, 68, ink.g7), 10, 57, 1, ink.g7);
        } else {
            cv.cellPal(0, 7, 9, 7, P_INK);
            cv.text(fit(h2h, 74, ink.g7), 4, 57, 1, ink.g7);
        }
        cv.cellPal(0, 8, 9, 8, P_INK);
        if (m.stakes != null) {
            cv.text("이기면", 4, 65, 2, ink.g7);
            int xs = cv.mini3(MenuModel.signed(m.stakes[0]), 31, 66, 1, TileCanvas.LEFT);
            xs = cv.text("지면", Math.max(48, xs + 3), 65, 2, ink.g7);
            cv.mini3(MenuModel.signed(m.stakes[1]), Math.max(66, xs + 4), 66, 1, TileCanvas.LEFT);
        } else if (m.me.placement) {
            cv.text("배치 경기 " + m.me.placementPlayed + "/5", 4, 65, 2, ink.g7);
        } else cv.text("무쌍 · MR 승부", 4, 65, 2, ink.g7);
        /* 금 아래 줄 */
        cv.cellPal(0, 9, 9, 9, P_GOLD);
        cv.fill(0, 72, 79, 72, 2);
        cv.fill(0, 73, 76, 73, 1);
    }

    /* ════════════════════════════════════════════════════════════════════ 서랍 */

    /** 순위 — 위 3명 단상 + 내 주변(내 줄 r11 고정) · b == null 이면 «먹 가는 중…» */
    Scene board(MenuModel m, BoardCache.Board b) { return board(m, b, null); }
    /** 순위 — top = 위 100명 판(있으면 목록을 위로 100위까지 이어 붙임) */
    Scene board(MenuModel m, BoardCache.Board b, BoardCache.Board top) {
        Sb s = new Sb(SC_BOARD);
        TileCanvas cv = s.cv;
        drawerPals(cv);
        drawerHead(cv, "순", "순위표", "시즌 " + m.record.season, String.format(java.util.Locale.KOREA, "AI 검객 %,d", m.total), D_INK, true);
        tabRow(s, MenuModel.Prefs.TAB_BOARD);
        java.util.List<RankCore.Row> top3 = b != null && b.top3 != null ? b.top3 : top != null && top.rows != null ? top.rows.subList(0, Math.min(3, top.rows.size())) : null;
        if (top3 != null && top3.size() >= 3) podium(cv, top3, m);
        /* 목록(L 층) */
        java.util.List<RankCore.Row> rows = new java.util.ArrayList<>();
        int meIdx = -1;
        if (m.me.placement) {
            if (top != null && top.rows != null) for (RankCore.Row r : top.rows) if (!r.me) rows.add(r);
        } else if (b != null && b.rows != null && !b.rows.isEmpty()) {
            if (top != null && top.rows != null && !top.rows.isEmpty()) {
                for (RankCore.Row r : top.rows) rows.add(r);
                boolean meIn = false;
                for (RankCore.Row r : top.rows) meIn |= r.me;
                if (!meIn) {
                    long last = top.rows.get(top.rows.size() - 1).rank;
                    boolean gap = b.rows.get(0).rank > last + 1;
                    if (gap) rows.add(null);                                       /* «⋯» */
                    for (RankCore.Row r : b.rows) if (r.rank > last) rows.add(r);
                }
            } else rows.addAll(b.rows);
        }
        /* 165: 무쌍은 MR(다른 점수)로 매겨 늘 모든 LP 티어 위 — 유저 «내가 엘피가 높은데 고순위보다»(98~100위 무쌍의 MR 1652~1655 가 «LP» 열에 있어
           7570 LP 보다 낮아 보였다). 무쌍 줄은 값 칸에 «MR» 표 · 무쌍 → LP 경계에 나눔 줄 · 열 머리 «LP·MR» · 아래 줄 설명 */
        boolean anyMaster = false;
        for (RankCore.Row r : rows) if (r != null && r != DIVIDER && r.lp >= RankCore.MASTER_LP) anyMaster = true;
        for (int i = 0; anyMaster && i < rows.size(); i++) {
            RankCore.Row r = rows.get(i);
            if (r == null || r.lp >= RankCore.MASTER_LP) continue;              /* 첫 LP 줄 — 그 앞(«…» 건너) 줄이 무쌍이면 나눔 줄 */
            RankCore.Row q = null;
            for (int j = i - 1; j >= 0 && q == null; j--) q = rows.get(j);
            if (q != null && q.lp >= RankCore.MASTER_LP) rows.add(i, DIVIDER);
            break;
        }
        if (b != null) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i) != null && rows.get(i).me) meIdx = i;
            if (meIdx < 0) for (int i = 0; i < rows.size(); i++) if (rows.get(i) != null && rows.get(i) != DIVIDER && rows.get(i).rank == b.rank) meIdx = i;
        }
        if (rows.size() > 128) rows = rows.subList(0, 128);
        /* 열 머리(r6) — 무쌍이 있으면 «LP·MR» */
        cv.cellPal(0, 6, 19, 6, D_INK);
        cv.text("순위", 6, 49, 2, ink.g7);
        cv.text("이름", 42, 49, 2, ink.g7);
        cv.text("티어", 100, 49, 2, ink.g7);
        cv.text(anyMaster ? "LP·MR" : "LP", 155, 49, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
        Scene sc;
        if (b == null && !m.me.placement) {
            cv.cellPal(4, 11, 15, 11, D_LOAD);
            cv.text("먹 가는 중…", 80, 88, 1, ink.g7, -1, TileCanvas.CENTER, 0);
        } else if (rows.isEmpty()) {
            cv.cellPal(1, 11, 18, 11, D_INK);
            cv.text(m.me.placement ? "배치를 마치면 순위가 나와요" : "순위를 못 불러왔어요", 80, 88, 2, ink.g7, -1, TileCanvas.CENTER, 0);
        }
        /* r16 붓 · r17 요약 · r18 안내 */
        cv.cellPal(0, 16, 19, 16, D_GOLD);
        cv.art(OvArt.BRUSH152, 4, 130, 1, 2, 0, false);
        cv.cellPal(0, 17, 19, 17, D_INK);
        long rank = b != null ? b.rank : -1;
        if (m.me.placement) cv.text("배치 경기 " + m.me.placementPlayed + "/5 · 순위는 배치 뒤에", 2, 137, 2, ink.g7);
        else if (rank > 0) {
            int x = cv.text(String.format(java.util.Locale.KOREA, "전국 %,d위", rank), 2, 136, 1, ink.g7);
            int pct = (int) Math.max(1, Math.min(100, Math.ceil(100.0 * rank / Math.max(1, m.total))));
            boolean dl = m.rankBefore > 0 && m.rankBefore != rank;
            String ds = dl ? String.format(java.util.Locale.US, "%,d", Math.abs(m.rankBefore - rank)) : "";
            int triX = 155 - TileCanvas.mini3W(ds) - 7, gc = triX / 8;               /* ▲▼·숫자는 금 칸(gc..19) — 앞 글은 그 칸 앞에서 끊는다(칸당 팔레트 하나) */
            int lim = (dl ? gc * 8 - 1 : 157) - x - 3;
            if (dl) cv.cellPal(gc, 17, 19, 17, D_GOLD);
            cv.text(fit(String.format(java.util.Locale.KOREA, "/ %,d · 상위 %d%%", m.total, pct), lim, ink.g7), x + 3, 136, 2, ink.g7);
            if (dl) {
                long d = m.rankBefore - rank;
                cv.art(d > 0 ? OvArt.TRI_UP : OvArt.TRI_DN, triX, 139, d > 0 ? 1 : 2, 0, 0, false);
                cv.mini3(ds, 155, 138, 1, TileCanvas.RIGHT);
            }
        }
        cv.cellPal(0, 18, 19, 18, D_INK);
        cv.text(boardHint(cv, anyMaster), 80, 145, 2, ink.g7, -1, TileCanvas.CENTER, 0);
        s.base(B_FILL, Emu.OV_BASE_FILL, 0, 151, 152, 0x012);
        sc = s.finish(TileCanvas.RIGHT);
        if (!rows.isEmpty()) {
            sc.l = listLayer(rows, m);
            sc.lClipY0 = 56; sc.lClipY1 = 127;
            int min = -56, max = Math.max(min, 8 * (rows.size() - 9) - 56);
            int want = meIdx >= 0 ? 8 * meIdx - 88 : min;
            sc.lScrollMin = min; sc.lScrollMax = max;
            sc.lScrollY = Math.max(min, Math.min(max, want));
        }
        int[] vis = null;
        if (!rows.isEmpty()) {                       /* 처음 보이는 목록 줄(L 칸) — 드러냄·탭 휘두름은 이 줄만(100줄 전체를 쓸면 보이는 곳은 한순간에 뜬다) */
            int v0 = Math.max(0, Math.floorDiv(sc.lScrollY + sc.lClipY0, 8)), v1 = Math.min(rows.size() - 1, Math.floorDiv(sc.lScrollY + sc.lClipY1, 8));
            vis = new int[]{ 0, Math.min(v0, 127), 19, Math.min(Math.max(v0, v1), 127) };
        }
        drawerCues(sc, vis, b == null && !m.me.placement);
        if (vis != null) sc.revealCue = concat(snap(sc.cue), new Cue().wipe(0, SWING, Cue.L, vis[0], vis[1], vis[2], vis[3], 2, false, 0, 0, Cue.EASE_LINEAR).build());
        sc.tab = MenuModel.Prefs.TAB_BOARD;
        return sc;
    }
    /** 순위 목록의 무쌍 → LP 나눔 줄(자리표 — 줄 그림은 listLayer) */
    static final RankCore.Row DIVIDER = new RankCore.Row();
    static final String HINT_BOARD = "▲▼ 넘기기  ◀▶ 탭  B 뒤로";
    static final String[] HINT_MASTER = { "무쌍은 MR 순 · 늘 LP 위  ▲▼ ◀▶ B", "무쌍=MR 순·LP 위  ▲▼ ◀▶ B" };
    static final String[] DIVIDER_TEXT = { "▲ 무쌍 · MR 순   ▼ LP 순", "▲무쌍 MR ▼LP" };
    /** 서랍 순위 아래 줄 — 무쌍이 보이면 «무쌍은 MR 순 · 늘 LP 위» 를 앞에(폭 156 안 — 못 들면 짧은 글) */
    private String boardHint(TileCanvas cv, boolean master) {
        if (!master) return HINT_BOARD;
        for (String h : HINT_MASTER) if (cv.textW(h, ink.g7) <= 156) return h;
        return HINT_MASTER[HINT_MASTER.length - 1];
    }
    /** 단상 3장(r3–5): 2위 c0–5 · 1위 c6–13(금 + 왕관) · 3위 c14–19 */
    private void podium(TileCanvas cv, java.util.List<RankCore.Row> top3, MenuModel m) {
        int[][] pos = { { 0, 5, 1 }, { 6, 13, 0 }, { 14, 19, 2 } };
        for (int[] p : pos) {
            int c0 = p[0], c1 = p[1];
            RankCore.Row r = top3.get(p[2]);
            int x0 = c0 * 8, x1 = c1 * 8 + 7, cx = (x0 + x1 + 1) / 2;
            boolean first = p[2] == 0;
            cv.cellPal(c0, 3, c1, 5, first ? D_PLATE1 : D_PLATEM);
            cv.fill(x0 + 1, 25, x1 - 1, 46, 3);
            cv.fill(x0 + 1, 25, x1 - 1, 25, 2);
            boolean master = r.lp >= RankCore.MASTER_LP;
            String tier = master ? RankCore.TIER[7] : RankCore.TIER[RankCore.tierOf(r.lp)];
            if (first) {
                cv.art(OvArt.CROWN, x0 + 4, 27, 1, 2, 0, false);
                cv.text("1위", x0 + 13, 26, 2, ink.g7);
            } else cv.text((p[2] + 1) + "위", x0 + 3, 26, 2, ink.g7);
            cv.text(tier, x1 - 3, 26, 1, ink.g7, -1, TileCanvas.RIGHT, 0);
            String nick = r.me ? m.me.nick : r.nick;
            cv.text(fit(cover7(nick), x1 - x0 - 4, ink.g7), cx, 33, 1, ink.g7, -1, TileCanvas.CENTER, 0);
            String v = String.valueOf(master ? r.mr : r.lp);
            cv.text(master ? "MR" : "LP", cx - 11, 40, 2, ink.g7, -1, TileCanvas.CENTER, 0);
            cv.mini3(v, cx - 3, 41, 1, TileCanvas.LEFT);
        }
    }
    /** 목록 줄(L 층 20×n) — 순위(3×5 오른쪽) · 사슬(라이벌) · 닉 · 티어★ · LP. 내 줄 = 주홍 막대 + 단도 · 줄무늬 */
    private TileCanvas.Encoded[] listLayer(java.util.List<RankCore.Row> rows, MenuModel m) {
        for (int pass = 0; pass < 2; pass++) {
            boolean zebra = pass == 0;
            try {
                TileCanvas cv = new TileCanvas(20, rows.size());
                int ink0 = cv.pal(C_INK[0], C_INK[1], C_INK[2]);
                for (int k = 0; k < rows.size(); k++) {
                    RankCore.Row r = rows.get(k);
                    int y = k * 8;
                    boolean z = zebra && k % 2 == 1;
                    if (r == DIVIDER) {                                            /* 165: 무쌍 → LP 경계 */
                        int gp = cv.pal(C_GOLD[0], C_GOLD[1], z ? C_INKZ[2] : C_INK[2]);
                        cv.cellPal(0, k, 19, k, gp);
                        if (z) cv.fill(0, y, 159, y + 7, 3);
                        String t = DIVIDER_TEXT[0];
                        for (String d : DIVIDER_TEXT) { t = d; if (cv.textW(d, ink.g7) <= 120) break; }
                        int tw = cv.textW(t, ink.g7);
                        cv.text(t, 80, y, 1, ink.g7, -1, TileCanvas.CENTER, 0);
                        cv.fill(4, y + 4, Math.max(4, 80 - tw / 2 - 4), y + 4, 2);
                        cv.fill(Math.min(155, 80 + tw / 2 + 4), y + 4, 155, y + 4, 2);
                        continue;
                    }
                    if (r == null) {                                               /* 위 100 ↔ 내 주변 사이 — 건너뛴 순위를 적는다(«… 101~916위 …») */
                        cv.cellPal(0, k, 19, k, ink0);
                        long a = -1, bb = -1;
                        for (int j = k - 1; j >= 0 && a < 0; j--) { RankCore.Row q = rows.get(j); if (q != null && q != DIVIDER) a = q.rank; }
                        for (int j = k + 1; j < rows.size() && bb < 0; j++) { RankCore.Row q = rows.get(j); if (q != null && q != DIVIDER) bb = q.rank; }
                        String gt = a > 0 && bb > a + 1 ? (bb == a + 2 ? String.format(java.util.Locale.US, "… %,d위 …", a + 1) : String.format(java.util.Locale.US, "… %,d~%,d위 …", a + 1, bb - 1)) : "…";
                        cv.text(fit(gt, 150, ink.g7), 80, y, 2, ink.g7, -1, TileCanvas.CENTER, 0);   /* ⋯ U+22EF 는 갈무리에 없음 — «…» */
                        continue;
                    }
                    boolean master = r.lp >= RankCore.MASTER_LP;
                    int t = master ? 7 : RankCore.tierOf(r.lp);
                    String rk = String.format(java.util.Locale.US, "%,d", r.rank);
                    String nick = r.me ? m.me.nick : r.nick;
                    String tier = master ? RankCore.TIER[7] : RankCore.TIER[t];
                    String lp = String.valueOf(master ? r.mr : r.lp);
                    if (r.me) {
                        int sel = cv.pal(C_SEL[0], C_SEL[1], C_SEL[2]);
                        cv.cellPal(0, k, 19, k, sel);
                        cv.fill(0, y, 159, y + 7, 3);
                        cv.mini3(rk, 29, y + 1, 1, TileCanvas.RIGHT);
                        cv.art(OvArt.KATANA, 32, y, 1, 2, 0, false);
                        cv.text(fit(cover7(nick), 54, ink.g7), 42, y, 1, ink.g7);
                        int x = cv.text(tier, 100, y, 1, ink.g7);
                        if (!master) { cv.art(OvArt.STAR5, x + 1, y + 1, 2, 0, 0, false); cv.text(String.valueOf(RankCore.starOf(r.lp)), x + 7, y, 1, ink.g7); }
                        cv.mini3(lp, 155, y + 1, 1, TileCanvas.RIGHT);
                        if (master) cv.text("MR", 155 - TileCanvas.mini3W(lp) - 2, y, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
                        continue;
                    }
                    int base = z ? cv.pal(C_INKZ[0], C_INKZ[1], C_INKZ[2]) : ink0;
                    cv.cellPal(0, k, 11, k, base);
                    cv.cellPal(16, k, 19, k, base);                                 /* 무쌍이면 아래에서 티어 빛으로(«MR» 표) */
                    if (z) cv.fill(0, y, 159, y + 7, 3);
                    cv.mini3(rk, 29, y + 1, 2, TileCanvas.RIGHT);
                    if (r.p != null && r.p.legacy) {
                        int ch = z ? cv.pal(C_CHAINZ[0], C_CHAINZ[1], C_CHAINZ[2]) : cv.pal(C_CHAIN[0], C_CHAIN[1], C_CHAIN[2]);
                        cv.cellPal(4, k, 4, k, ch);
                        cv.art(OvArt.CHAIN, 32, y + 1, 1, 0, 0, false);
                    }
                    cv.text(fit(cover7(nick), 54, ink.g7), 42, y, 1, ink.g7);       /* 54px — 42..95(c5–11). c12 부터는 티어 팔레트 */
                    int tp = cv.pal(TIER_C[t][0], TIER_C[t][1], z ? 0x123 : 0x012);
                    cv.cellPal(12, k, master ? 19 : 15, k, tp);
                    int x = cv.text(tier, 100, y, 1, ink.g7);
                    if (!master) { cv.art(OvArt.STAR5, x + 1, y + 1, 1, 0, 0, false); cv.text(String.valueOf(RankCore.starOf(r.lp)), x + 7, y, 1, ink.g7); }
                    cv.mini3(lp, 155, y + 1, 1, TileCanvas.RIGHT);
                    if (master) cv.text("MR", 155 - TileCanvas.mini3W(lp) - 2, y, 2, ink.g7, -1, TileCanvas.RIGHT, 0);   /* 무쌍 = MR(빨강 «MR» · 흰 숫자) — LP 와 다른 점수 */
                }
                return cv.encodeBands(0, TileCanvas.MAX_CHUNK, 2048);
            } catch (TileCanvas.Overflow o) {
                if (pass == 1) throw o;
            }
        }
        return null;
    }

    /** 전적 «요약» — 노보리 깃발 셋 · 최근 10 · 오늘 · LP 흐름(★ 경계 점선) · 통산 · 라이벌 */
    Scene record(MenuModel m) {
        Sb s = new Sb(SC_RECORD);
        TileCanvas cv = s.cv;
        drawerPals(cv);
        MenuModel.Record r = m.record;
        int games = r.w + r.l + r.d;
        String me = r.placement ? "배치 " + r.placementPlayed + "/5" : r.master ? "무쌍 · MR " + r.mr
                : RankCore.TIER[RankCore.tierOf(r.lp)] + "★" + RankCore.starOf(r.lp) + " · " + r.lp;
        int t = r.master ? 7 : r.placement ? 0 : RankCore.tierOf(r.lp);
        cv.setPal(D_TIER, TIER_C[t][0], TIER_C[t][1], 0x012);
        drawerHead(cv, "전", "전적", "시즌 " + r.season + " · " + games + "전", me, D_TIER, false);   /* 배치 = 수련생 빛(결판 띠·VS 띠와 같은 빛) */
        tabRow(s, MenuModel.Prefs.TAB_RECORD);
        banner(cv, 1, D_CLOTH, "승", String.valueOf(r.w), r.winPct >= 0 ? r.winPct + "%" : "-");
        banner(cv, 7, D_CLOTHK, "패", String.valueOf(r.l), r.d > 0 ? r.d + "무" : "");
        banner(cv, 13, D_CLOTHR, "연승", String.valueOf(r.streak), "최고 " + r.best);
        /* 최근 10(r8) — 오래된 것이 왼쪽 */
        cv.cellPal(0, 8, 3, 8, D_INK);
        cv.text("최근 10", 2, 65, 2, ink.g7);
        int lw = 0, ll = 0;
        if (r.last10 != null && r.last10.length > 0) {
            cv.cellPal(4, 8, 15, 8, D_PIP);
            for (int k = 0; k < r.last10.length; k++) {
                char ch = r.last10[r.last10.length - 1 - k];
                if (ch == 'W') lw++; else if (ch == 'L') ll++;
                cv.art(ch == 'W' ? OvArt.PIP_WIN : OvArt.PIP_LOSS, 34 + k * 9, 65, 1, 2, 0, false);
            }
            cv.cellPal(16, 8, 19, 8, D_GOLD);
            cv.text(lw + "-" + ll, 157, 65, 1, ink.g7, -1, TileCanvas.RIGHT, 0);
        } else {
            cv.cellPal(4, 8, 15, 8, D_INK);
            cv.text("아직 없어요", 34, 65, 2, ink.g7);
        }
        /* 오늘(r9) */
        cv.cellPal(0, 9, 19, 9, D_INK);
        if (r.todayW + r.todayL + r.todayD > 0) {
            int x = cv.text("오늘 " + r.todayW + "승 " + r.todayL + "패" + (r.todayD > 0 ? " " + r.todayD + "무" : ""), 2, 73, 1, ink.g7);
            String rest = "· " + MenuModel.signed(r.todayLp) + " LP" + (r.firstWinTaken ? " · 첫 승 받음" : "");
            cv.text(fit(rest, 157 - x - 3, ink.g7), x + 3, 73, 2, ink.g7);
        } else cv.text(m.bonusLeft ? "오늘 첫 판 · 첫 승 +30 남았어요" : "오늘 아직 안 했어요", 2, 73, 2, ink.g7);
        /* LP 흐름(r10–15) */
        cv.cellPal(0, 10, 19, 10, D_INK);
        cv.text("LP 흐름 · 최근 " + (r.lpTrail != null ? r.lpTrail.length : 0) + "판", 2, 81, 2, ink.g7);   /* 81..87 — r11 은 차트 팔레트 */
        cv.cellPal(0, 11, 19, 15, D_CHART);
        chart(cv, r.lpTrail);
        /* 통산(r16) · 라이벌(r17) · 안내(r18) */
        cv.cellPal(0, 16, 19, 16, D_INK);
        int[] cw = { r.careerW, r.careerL, r.careerD };
        String car = "통산 " + cw[0] + "승 " + cw[1] + "패" + (cw[2] > 0 ? " " + cw[2] + "무" : "") + (r.peakLabel != null ? " · 최고 " + r.peakLabel : "")
                + (r.cheatGames > 0 ? " · 치트 " + r.cheatGames : "");   /* 168 §9 — 이번 시즌 치트 판 */
        cv.text(fit(car, 156, ink.g7), 2, 129, 2, ink.g7);
        if (r.rivalNick != null) {
            cv.cellPal(0, 17, 0, 17, D_CHAIN);
            cv.art(OvArt.CHAIN, 1, 138, 1, 0, 0, false);
            cv.cellPal(1, 17, 19, 17, D_INK);
            int x = cv.text(fit("라이벌 " + cover7(r.rivalNick) + " " + r.rivalW + "-" + r.rivalL, 110, ink.g7), 11, 137, 1, ink.g7);
            if (r.rivalTag != null) cv.text(fit("· " + r.rivalTag, 157 - x - 3, ink.g7), x + 3, 137, 2, ink.g7);
        } else {
            cv.cellPal(0, 17, 19, 17, D_INK);
            cv.text("라이벌 아직 없어요", 2, 137, 2, ink.g7);
        }
        cv.cellPal(0, 18, 19, 18, D_INK);
        cv.text("◀▶ 탭  B 뒤로", 80, 145, 2, ink.g7, -1, TileCanvas.CENTER, 0);
        s.base(B_FILL, Emu.OV_BASE_FILL, 0, 151, 152, 0x012);
        Scene sc = s.finish(TileCanvas.RIGHT);
        drawerCues(sc, null, false);
        sc.tab = MenuModel.Prefs.TAB_RECORD;
        return sc;
    }
    /** 노보리 깃발(c 깃대 + c+1..c+4 천, r3–7) */
    private void banner(TileCanvas cv, int c, int cloth, String label, String num, String small) {
        int x0 = (c + 1) * 8;
        cv.cellPal(c, 3, c, 7, D_WOOD);
        cv.art(OvArt.BANNER_POLE, c * 8, 24, 1, 2, 0, false);
        cv.cellPal(c + 1, 3, c + 4, 7, cloth);
        cv.art(OvArt.BANNER_CLOTH, x0, 24, 0, 2, 3, false);
        int cx = x0 + 16;
        cv.text(label, cx, 29, 1, ink.g7, -1, TileCanvas.CENTER, 0);
        cv.text(num, cx, 39, 1, ink.g11b, -1, TileCanvas.CENTER, 0);
        if (small != null && !small.isEmpty()) cv.text(fit(small, 30, ink.g7), cx, 53, 2, ink.g7, -1, TileCanvas.CENTER, 0);
    }
    /** LP 꺾은선(최근 20판) + ★ 경계 점선(≤ 3줄) + 지금 값 */
    private void chart(TileCanvas cv, int[] pts) {
        if (pts == null || pts.length < 2) { cv.text("판이 더 쌓이면 보여요", 80, 105, 2, ink.g7, -1, TileCanvas.CENTER, 0); return; }
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int v : pts) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        int pad = Math.max(20, (hi - lo) / 8);
        lo = Math.max(0, lo - pad); hi = hi + pad;
        final int ytop = 92, ybot = 124, lo0 = lo, hi0 = hi;
        java.util.function.IntUnaryOperator yv = v -> ybot - (int) ((long) (v - lo0) * (ybot - ytop) / Math.max(1, hi0 - lo0));
        java.util.List<int[]> th = new java.util.ArrayList<>();
        for (int t = 0; t < 7; t++) for (int s = 0; s < 5; s++) {
            int v = RankCore.FLOOR[t] + s * RankCore.STEP[t];
            if (v > lo && v < hi && v < RankCore.MASTER_LP) th.add(new int[]{ v, t, s + 1 });
        }
        while (th.size() > 3) th.remove(0);
        for (int[] x : th) {
            int y = yv.applyAsInt(x[0]);
            for (int xx = 4; xx < 126; xx += 3) cv.px(xx, y, 2);
            cv.text(x[2] == 1 ? RankCore.TIER[x[1]] : "★" + x[2], 126, y - 3, 2, ink.g7, -1, TileCanvas.LEFT, 0);
        }
        int px = -1, py = -1;
        int n = pts.length, step = n > 1 ? Math.max(1, Math.min(6, 114 / (n - 1))) : 6;
        for (int k = 0; k < n; k++) {
            int x = 6 + k * step, y = yv.applyAsInt(pts[k]);
            if (px >= 0) {
                int d = Math.max(Math.abs(x - px), Math.abs(y - py));
                for (int i = 0; i <= d; i++) cv.px(px + (x - px) * i / Math.max(1, d), py + (y - py) * i / Math.max(1, d), 1);
            }
            px = x; py = y;
        }
        cv.fill(px - 1, py - 1, px + 1, py + 1, 1);
        cv.mini3(String.valueOf(pts[n - 1]), Math.min(px + 4, 124 - TileCanvas.mini3W(String.valueOf(pts[n - 1]))), Math.max(89, Math.min(118, py - 2)), 1, TileCanvas.LEFT);
    }

    /** 설정 — 결판 연출 · 진 판 기본 커서 · 연속 대전 · 마지막 탭 기억(A = 바꾸기) */
    Scene settings(MenuModel.Prefs p, int cursor) {
        Sb s = new Sb(SC_SETTINGS);
        TileCanvas cv = s.cv;
        drawerPals(cv);
        drawerHead(cv, "설", "설정", "인게임 메뉴", "리그 대전만", D_INK, false);
        tabRow(s, MenuModel.Prefs.TAB_SETTINGS);
        int[] ids = { SET_FX, SET_LOSS, SET_AUTO, SET_REMEMBER };
        String[] lab = { "결판 연출", "진 판 기본 커서", "연속 대전", "마지막 탭 기억" };
        String[] val = { p.fx == 0 ? "보통" : p.fx == 1 ? "짧게" : "끄기", p.lossCursor == 0 ? "설욕전" : "다음 상대", p.autoNextSec == 10 ? "10초" : "끔", p.rememberTab ? "켬" : "끔" };
        String[] desc = { "끄기 = 예전 결과 카드", "진 판에서 처음 고를 줄", "10초 뒤 다음 상대 · 키 누르면 멈춤", "서랍을 열면 마지막 탭부터" };
        for (int k = 0; k < ids.length; k++) {
            int r = 4 + 2 * k, y = r * 8;
            cv.cellPal(0, r, 19, r + 1, D_INK);
            cv.fill(0, y + 1, 159, y + 15, 3);
            cv.text(lab[k], 12, y + 3, 1, ink.g11);
            cv.text(val[k], 156, y + 3, 2, ink.g11, -1, TileCanvas.RIGHT, 0);
            Row row = new Row();
            row.id = ids[k]; row.c0 = 0; row.r0 = r; row.c1 = 19; row.r1 = r + 1; row.palOn = D_SEL; row.palOff = D_INK;
            row.quadX = 2; row.quadY = y + 4; row.enabled = true; row.desc = desc[k];
            int sr = 21 + k;
            cv.cellPal(0, sr, 19, sr, D_INK);
            cv.text(fit(desc[k], 156, ink.g7), 80, sr * 8 + 1, 2, ink.g7, -1, TileCanvas.CENTER, 0);
            row.descC = 0; row.descR = sr; row.descW = 20; row.descDstC = 0; row.descDstR = 14;
            s.rows.add(row);
        }
        int c0 = Math.max(0, Math.min(ids.length - 1, cursor));
        cv.cellPal(0, 14, 19, 14, D_INK);
        cv.text(fit(desc[c0], 156, ink.g7), 80, 113, 2, ink.g7, -1, TileCanvas.CENTER, 0);
        cv.cellPal(0, 18, 19, 18, D_INK);
        cv.text("▲▼ 고르기  A 바꾸기  ◀▶ 탭  B 뒤로", 80, 145, 2, ink.g7, -1, TileCanvas.CENTER, 0);
        katanaStore(cv, D_SEL);
        s.base(B_FILL, Emu.OV_BASE_FILL, 0, 151, 152, 0x012);
        s.scene().defaultRow = c0;
        Scene sc = s.finish(TileCanvas.RIGHT);
        drawerCues(sc, null, false);
        sc.tab = MenuModel.Prefs.TAB_SETTINGS;
        return sc;
    }

    /* ════════════════════════════════════════════════════════════════ 168 대기(157-B) */

    /** 대기 — 리그 타이틀 위(코어 멈춤). 왼쪽 위 내 판 패 · 칼집(상대 찾기/멈춘 경기 이어서 · 순위·전적·리그 · 내 캐릭터 바꾸기 · 기권 · 무효로 닫기 · 리그 나가기) · 아래 먹 띠.
     *  variant 0 보통 · 1 기권 두 번 묻기(기권 줄 맥박 · 아래 띠 «A 한 번 더 = 기권») · 2 리그 나가기(내 게임 · 게임 목록 · 취소) */
    Scene lobby(MenuModel m, int variant) {
        Sb b = new Sb(variant == 1 ? SC_LOBBY_CONFIRM : variant == 2 ? SC_LOBBY_QUIT : SC_LOBBY);
        TileCanvas cv = b.cv;
        setPal(cv, P_INK, C_INK); setPal(cv, P_GOLD, C_GOLD); setPal(cv, P_SEL, C_SEL); setPal(cv, P_WARN, C_WARN); setPal(cv, P_RIB, C_SEAL);
        setPal(cv, P_PULSE, C_SEL);
        int t = m.me.placement ? 0 : Math.max(0, Math.min(7, m.me.tier));
        cv.setPal(P_TIER, LP_YELLOW, TIER_C[t][0], 0x012);
        lobbyPlate(cv, m);
        int[] ids; String[] lab, right; boolean[] on; String head;
        if (variant == 2) {
            ids = new int[]{ MenuModel.QUIT_GAME, MenuModel.QUIT_LIST, MenuModel.QUIT_CANCEL };
            lab = new String[]{ "내 게임", "게임 목록", "취소" }; right = new String[3]; on = new boolean[]{ true, true, true };
            head = "리그 나가기";
        } else {
            ids = m.rows; on = m.rowOn; right = m.rowRight.clone();
            lab = new String[ids.length];
            for (int i = 0; i < ids.length; i++) lab[i] = lobbyLabel(ids[i]);
            head = ids.length <= 5 ? "리그 대전" : null;
        }
        int rTop = head != null ? 6 : 4, rEnd = Math.max(rTop + 2 * ids.length - 1, 13);
        sheathP(cv, 4, rEnd);
        if (head != null) { cv.cellPal(11, 4, 19, 5, P_GOLD); cv.text(head, 90, 36, 1, ink.g11b); }
        cmdRowsP(b, rTop, ids, on, right, lab, variant == 2 ? 2 : -1);
        String[] desc = variant == 2 ? new String[]{ "내 세이브 게임으로", "게임 목록으로 나가요", "대기로 돌아가요" } : m.rowDesc;
        for (int i = 0; i < b.rows.size(); i++) b.rows.get(i).desc = i < desc.length ? desc[i] : null;
        int fi = -1;
        if (variant == 1) for (int i = 0; i < ids.length; i++) if (ids[i] == MenuModel.ROW_OPEN_FORFEIT) { fi = i; b.rows.get(i).palOn = P_PULSE; }
        /* 아래 먹 띠 */
        cv.cellPal(0, 16, 10, 16, P_GOLD); cv.cellPal(11, 16, 19, 16, P_GOLD);
        cv.fill(0, 135, 159, 135, 2);
        cv.fill(0, 136, 159, 151, 3);
        cv.cellPal(0, 17, 19, 18, P_INK);
        if (variant == 1) {
            String line = m.forfeit != null ? m.forfeit.line : "패배로 기록돼요";
            cv.text(fit("vs " + m.opp.nick + " · " + line, 156, ink.g7), 2, 137, 1, ink.g7);
            cv.text(fit("A 한 번 더 = 기권 · B 취소", 156, ink.g7), 157, 145, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
            for (Row row : b.rows) row.descC = -1;
        } else {
            for (int i = 0; i < b.rows.size(); i++) {      /* 줄 설명 — 창고 r21.. */
                Row row = b.rows.get(i);
                int sr = 21 + i;
                cv.fill(0, sr * 8, 159, sr * 8 + 7, 3);
                cv.cellPal(0, sr, 19, sr, P_INK);
                if (row.desc != null) cv.text(fit(row.desc, 156, ink.g7), 2, sr * 8 + 1, 1, ink.g7);
                row.descC = 0; row.descR = sr; row.descW = 20; row.descDstC = 0; row.descDstR = 17;
            }
            Row d0 = b.rows.isEmpty() ? null : b.rows.get(0);
            if (d0 != null && d0.desc != null) cv.text(fit(d0.desc, 156, ink.g7), 2, 137, 1, ink.g7);
            cv.text(fit(variant == 2 ? "A 결정 · B 돌아가기" : "A 결정 · B/OPTION 닫기", 156, ink.g7), 157, 145, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
        }
        katanaStore(cv, P_SEL);
        b.pane(PP_SHEATH, 10, 4, 19, rEnd, 0, 0, true);
        b.pane(PP_INFO, 0, 4, 9, 9, 0, 0, true);
        b.pane(PP_BOTTOM, 0, 16, 19, 18, 0, 0, true);
        b.base(B_DIM, Emu.OV_BASE_DIM, 0, 151, 2, 0);
        if (variant == 1) b.scene().defaultRow = Math.max(0, fi);
        Scene sc = b.finish(TileCanvas.RIGHT);
        sc.portraitOutside = variant == 0;
        sc.hitRect = new int[]{ 10, 4, 19, rEnd, 0, 4, 9, 9 };
        if (variant != 0) {
            Cue q = new Cue().wipe(0, SWING, Cue.W, variant == 1 ? 0 : 10, variant == 1 ? 17 : 4, 19, variant == 1 ? 18 : rEnd, 2, false, P_GOLD, 1, Cue.EASE_LINEAR);
            if (variant == 1) q.palCycle(0, Cue.AMBIENT, Cue.W, P_PULSE, 3, 0x46E, 0x239, 16, false);
            ambient(q, 0, P_SEL);
            sc.cue = q.build(); sc.cueEnd = q.end(); sc.readyT = 0; sc.swapCue = sc.cue;
        } else {
            Cue q = new Cue().dim(0, PAUSE_BLEED, B_DIM, 0, 151, 4, 2);
            q.show(0, Cue.W, PP_SHEATH, true).slide(0, SHEATH_IN, Cue.W, PP_SHEATH, Cue.AXIS_X, 72, 0, Cue.EASE_OUT);
            q.show(0, Cue.W, PP_INFO, true).slide(0, PAUSE_INFO, Cue.W, PP_INFO, Cue.AXIS_X, -80, 0, Cue.EASE_OUT);
            ambient(q, SHEATH_IN, P_SEL);
            sc.cue = q.build(); sc.cueEnd = q.end(); sc.readyT = SHEATH_IN;
            sc.swapCue = snap(sc.cue);
        }
        Cue c = new Cue().slide(0, CLOSE, Cue.W, PP_SHEATH, Cue.AXIS_X, 0, 80, Cue.EASE_IN)
                .slide(0, CLOSE, Cue.W, PP_INFO, Cue.AXIS_X, 0, -80, Cue.EASE_IN)
                .dim(0, CLOSE, B_DIM, 0, 151, 2, 4).show(0, Cue.W, PP_BOTTOM, false);
        sc.closeCue = c.build(); sc.closeDur = CLOSE;
        return sc;
    }
    static String lobbyLabel(int id) {
        switch (id) {
            case MenuModel.ROW_SEARCH: return "상대 찾기";
            case MenuModel.ROW_RESUME_OPEN: return "이어하기";           /* 칼집 줄 글은 52px(갈무리11 4자) 안 — 자세한 말은 아래 띠 */
            case MenuModel.ROW_DRAWER: return "순위·전적";
            case MenuModel.ROW_MAIN: return "내 캐릭터";
            case MenuModel.ROW_OPEN_FORFEIT: return "기권";
            case MenuModel.ROW_OPEN_VOID: return "무효 닫기";
            case MenuModel.ROW_LEAVE: return "나가기";
            default: return "";
        }
    }
    /** 쉼·대기 칼집 줄 — cmdRows 와 같은 칸(쉼 팔레트) · 기권 줄은 주홍 */
    private void cmdRowsP(Sb b, int rTop, int[] ids, boolean[] on, String[] right, String[] labels, int dimIdx) {
        TileCanvas cv = b.cv;
        for (int k = 0; k < ids.length; k++) {
            int r = rTop + 2 * k, y = r * 8;
            boolean warn = ids[k] == MenuModel.ROW_OPEN_FORFEIT;
            boolean dim = !on[k] || k == dimIdx;
            cv.cellPal(11, r, 19, r + 1, warn ? P_WARN : P_INK);
            cv.text(fit(labels[k], 52, ink.g11), 99, y + 3, dim ? 2 : 1, ink.g11);
            String rt = right != null && k < right.length ? right[k] : null;
            if (rt != null) {
                if (mini3able(rt)) cv.mini3(rt, 155, y + 6, warn ? 1 : 2, TileCanvas.RIGHT);
                else cv.text(rt, 155, y + 5, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
            }
            Row row = new Row();
            row.id = ids[k]; row.c0 = 11; row.r0 = r; row.c1 = 19; row.r1 = r + 1;
            row.rc0 = 10; row.rr0 = r; row.rc1 = 19; row.rr1 = r + 1;
            row.palOn = P_SEL; row.palOff = warn ? P_WARN : P_INK;
            row.quadX = 89; row.quadY = y + 4;
            row.enabled = on[k];
            b.rows.add(row);
        }
    }
    /** 대기 왼쪽 위 내 판 패(c0–9 × r4–9) — «리그 · 내 이름» · 티어★ LP · 오늘 n승 m패 · 첫 승 보너스/세션 */
    private void lobbyPlate(TileCanvas cv, MenuModel m) {
        cv.fill(0, 32, 79, 71, 3);
        cv.cellPal(0, 4, 9, 5, P_INK);
        cv.text(fit(cover7or11(m.me.nick), 74, ink.g11), 4, 34, 1, ink.g11);   /* 내 이름(8자까지 — 칼집 머리가 «리그 대전») */
        cv.cellPal(0, 6, 9, 6, P_TIER);
        if (m.me.placement) cv.text("배치 경기 " + m.me.placementPlayed + "/5", 4, 49, 2, ink.g7);
        else {
            int x = cv.text(RankCore.TIER[Math.max(0, Math.min(7, m.me.tier))], 4, 49, 2, ink.g7);
            if (m.me.master) { x = cv.text("MR", x + 3, 49, 2, ink.g7); cv.mini3(String.valueOf(m.me.lp), x + 4, 50, 1, TileCanvas.LEFT); }
            else {
                cv.art(OvArt.STAR5, x + 1, 50, 2, 0, 0, false);
                x = cv.text(String.valueOf(m.me.stars), x + 7, 49, 2, ink.g7);
                x = cv.mini3(String.valueOf(m.me.lp), x + 6, 50, 1, TileCanvas.LEFT);
                cv.text("LP", Math.min(66, x + 3), 49, 2, ink.g7);
            }
        }
        cv.cellPal(0, 7, 9, 7, P_INK);
        String day = m.todayW + m.todayL + m.todayD > 0 ? m.todayLine() + " · " + MenuModel.signed(m.todayLp) : "오늘 첫 판";
        cv.text(fit(day, 74, ink.g7), 4, 57, 1, ink.g7);
        cv.cellPal(0, 8, 9, 8, P_INK);
        String sub = m.started ? "멈춤 · vs " + m.opp.nick : m.bonusLeft ? "첫 승 +" + RankCore.BONUS_DAILY + " 남았어요" : m.me.style != null ? "내 캐릭터 · " + m.me.style : "";
        if (!sub.isEmpty()) cv.text(fit(cover7(sub), 74, ink.g7), 4, 65, 2, ink.g7);
        cv.cellPal(0, 9, 9, 9, P_GOLD);
        cv.fill(0, 72, 79, 72, 2);
        cv.fill(0, 73, 76, 73, 1);
    }

    /* ════════════════════════════════════════════════════════ 168 서랍 «리그» 탭 */

    static final int D_PULSE = 15;
    /** 리그 탭 — 줄: 새 시즌(배치 끝난 뒤) · 처음부터 다시 · 리그 초기화(179) · 내 캐릭터(대기만) · 내 이름(A) · 아래 목록(L): 시즌 · 지난 시즌 · 치트 판 · 보류·무효 · 규칙.
     *  줄이 넷까지면 r4–11 · 설명 r12 · 목록 r13–16(4줄) · 다섯이면(대기에서 연 서랍 · 배치 끝난 뒤) r4–13 · 설명 r14 · 목록 r15–16(2줄 — 넘김은 그대로).
     *  confirmId = 두 번 묻기 펼친 줄(lgConfirmRow — 그 줄 맥박 · 아래 띠 «A 한 번 더 = …») · 0 보통.
     *  보통 판은 펼친 판들을 variants 에 같이 싣는다(LeagueMenu 가 바로 갈아 끼움) */
    Scene league(MenuModel m, MenuModel.LeagueInfo li, int confirmId) {
        Scene sc = league0(m, li, confirmId);
        if (confirmId == 0) {
            java.util.List<Integer> vi = new java.util.ArrayList<>();
            java.util.List<Scene> vs = new java.util.ArrayList<>();
            for (Row r : sc.rows) if (lgConfirmRow(r.id)) { vi.add(r.id); vs.add(league0(m, li, r.id)); }
            sc.variantIds = new int[vi.size()];
            for (int i = 0; i < vi.size(); i++) sc.variantIds[i] = vi.get(i);
            sc.variants = vs.toArray(new Scene[0]);
        }
        return sc;
    }
    private Scene league0(MenuModel m, MenuModel.LeagueInfo li, int confirmId) {
        Sb s = new Sb(confirmId != 0 ? SC_LEAGUE_CONFIRM : SC_LEAGUE);
        TileCanvas cv = s.cv;
        drawerPals(cv);
        setPal(cv, D_PULSE, C_SEL);
        drawerHead(cv, "리", "리그", "시즌 " + li.season + (li.since != null ? " · " + li.since : ""), li.wld, D_INK, false);
        tabRow(s, MenuModel.Prefs.TAB_LEAGUE);
        java.util.List<int[]> rows = new java.util.ArrayList<>();          /* {id} */
        java.util.List<String[]> rl = new java.util.ArrayList<>();          /* {label, right, desc} */
        if (li.canSeason && !li.placement && li.label0 != null) {
            rows.add(new int[]{ LG_NEW_SEASON });
            rl.add(new String[]{ "새 시즌", String.format(java.util.Locale.US, "%d→%d", li.lp0, li.lp1),
                    "LP 를 반쯤 내리고 승패 0부터 · " + li.label0 + " → " + li.label1 });
        }
        if (li.canSeason) {
            rows.add(new int[]{ LG_RESTART });
            rl.add(new String[]{ "처음부터 다시", "배치부터", "배치 5판부터 · 지금 기록은 보관해요" });
            rows.add(new int[]{ LG_WIPE });
            rl.add(new String[]{ "리그 초기화", "모두 지움", "기록·시즌·상대를 지우고 처음 설치한 때로" });
        }
        if (li.canMain) {
            rows.add(new int[]{ LG_MAIN });
            rl.add(new String[]{ "내 캐릭터", li.mainLabel != null ? li.mainLabel : "고르기", "내 캐릭터부터 다시 고르고 상대를 찾아요" });
        }
        rows.add(new int[]{ LG_NAME });
        rl.add(new String[]{ "내 이름", li.name, "이름표·VS 띠·결판·순위표에 나와요" });
        final int descR = Math.max(12, 4 + 2 * rows.size()), listR0 = descR + 1, listN = 17 - listR0;   /* 179: 줄 다섯이면 설명 r14 · 목록 r15–16 */
        for (int k = 0; k < rows.size(); k++) {
            int r = 4 + 2 * k, y = r * 8, id = rows.get(k)[0];
            String[] t = rl.get(k);
            cv.cellPal(0, r, 19, r + 1, D_INK);
            cv.fill(0, y + 1, 159, y + 15, 3);
            cv.text(t[0], 12, y + 3, 1, ink.g11);
            String rt = t[1] != null ? t[1] : "";
            if (mini3able(rt.replace("→", "/"))) {
                String[] lr = rt.split("→");
                cv.mini3(lr[lr.length - 1], 156, y + 6, 2, TileCanvas.RIGHT);
                if (lr.length > 1) { cv.text("→", 156 - TileCanvas.mini3W(lr[1]) - 9, y + 3, 2, ink.g7); cv.mini3(lr[0], 156 - TileCanvas.mini3W(lr[1]) - 11, y + 6, 2, TileCanvas.RIGHT); }
            } else cv.text(fit(cover7(rt), 84, ink.g7), 156, y + 5, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
            Row row = new Row();
            row.id = id; row.c0 = 0; row.r0 = r; row.c1 = 19; row.r1 = r + 1; row.palOn = id == confirmId ? D_PULSE : D_SEL; row.palOff = D_INK;
            row.quadX = 2; row.quadY = y + 4; row.enabled = true; row.desc = t[2];
            int sr = 21 + k;
            cv.cellPal(0, sr, 19, sr, D_INK);
            cv.text(fit(t[2], 156, ink.g7), 80, sr * 8 + 1, 2, ink.g7, -1, TileCanvas.CENTER, 0);
            row.descC = confirmId != 0 ? -1 : 0; row.descR = sr; row.descW = 20; row.descDstC = 0; row.descDstR = descR;
            s.rows.add(row);
        }
        int c0 = 0;
        for (int k = 0; k < rows.size(); k++) if (rows.get(k)[0] == confirmId) c0 = k;
        cv.cellPal(0, descR, 19, descR, D_INK);
        cv.text(fit(rl.get(c0)[2], 156, ink.g7), 80, descR * 8 + 1, 2, ink.g7, -1, TileCanvas.CENTER, 0);
        /* 아래(r17–18) — 보통: 목록 넘기기 안내 · 펼침: 무엇이 바뀌나 + «A 한 번 더» */
        cv.cellPal(0, 17, 19, 17, confirmId != 0 ? D_SEL : D_INK);
        cv.cellPal(0, 18, 19, 18, D_INK);
        if (confirmId != 0) {
            cv.text(fit(cover7(confirmWhat(li, confirmId)), 156, ink.g7), 80, 137, 1, ink.g7, -1, TileCanvas.CENTER, 0);
            String go = confirmId == LG_NEW_SEASON ? "새 시즌" : confirmId == LG_WIPE ? "초기화" : "처음부터";
            cv.text(fit("A 한 번 더 = " + go + " · B 취소", 156, ink.g7), 80, 145, 2, ink.g7, -1, TileCanvas.CENTER, 0);
        } else {
            cv.text("▼ 끝에서 더 = 아래 목록", 80, 137, 2, ink.g7, -1, TileCanvas.CENTER, 0);
            cv.text("▲▼ 고르기  A 결정  ◀▶ 탭  B 뒤로", 80, 145, 2, ink.g7, -1, TileCanvas.CENTER, 0);
        }
        katanaStore(cv, D_SEL);
        s.base(B_FILL, Emu.OV_BASE_FILL, 0, 151, 152, 0x012);
        s.scene().defaultRow = c0;
        Scene sc = s.finish(TileCanvas.RIGHT);
        /* 아래 목록(L) — r13–16(4줄 보임) */
        java.util.List<String[]> lines = new java.util.ArrayList<>();       /* {글, 칠(g=금 · d=흐림 · n=보통)} */
        lines.add(new String[]{ "시즌 " + li.season + (li.since != null ? " · " + li.since : "") + " · " + li.wld, "g" });
        for (String r : li.pastRows) lines.add(new String[]{ r, "d" });
        if (li.cheatGames > 0) lines.add(new String[]{ "이번 시즌 치트 판 " + li.cheatGames, "n" });
        if (li.openVs != null) lines.add(new String[]{ "멈춘 경기 · vs " + li.openVs + " · 타이틀에서 A", "n" });
        if (li.holds.length > 0) { lines.add(new String[]{ "보류·무효", "g" }); for (String h : li.holds) lines.add(new String[]{ h, "d" }); }
        lines.add(new String[]{ "규칙", "g" });
        for (String r : li.rules) lines.add(new String[]{ r, "n" });
        sc.l = textList(lines);
        sc.lClipY0 = listR0 * 8; sc.lClipY1 = 135;
        int min = -listR0 * 8, max = Math.max(min, 8 * (lines.size() - listN) + min);
        sc.lScrollMin = min; sc.lScrollMax = max; sc.lScrollY = min;
        int[] vis = { 0, 0, 19, Math.min(listN - 1, lines.size() - 1) };
        drawerCues(sc, vis, false);
        if (confirmId != 0) {                                              /* 펼침 — 아래 띠 휘두름 · 맥박 */
            Cue q = new Cue().wipe(0, SWING, Cue.W, 0, 17, 19, 18, 2, false, D_GOLD, 1, Cue.EASE_LINEAR);
            q.palCycle(0, Cue.AMBIENT, Cue.W, D_PULSE, 3, 0x46E, 0x239, 16, false);
            ambient(q, 0, D_SEL);
            sc.swapCue = concat(snap(sc.cue), q.build());
        }
        sc.revealCue = concat(snap(sc.cue), new Cue().wipe(0, SWING, Cue.L, vis[0], vis[1], vis[2], vis[3], 2, false, 0, 0, Cue.EASE_LINEAR).build());
        sc.tab = MenuModel.Prefs.TAB_LEAGUE;
        return sc;
    }
    /** 두 번 묻기 펼침 아래 띠 첫 줄 — 무엇이 바뀌나. 열린 경기가 있으면 그 경기 몫이 먼저(넘치면 닉이 잘린다 — 할 일이 먼저):
     *  새 시즌·처음부터 = «지금 경기는(쉼에서 연 서랍)/멈춘 경기는 기권패로 닫고 …» · 리그 초기화 = «지금/멈춘 경기도 함께 지워요» */
    static String confirmWhat(MenuModel.LeagueInfo li, int id) {
        String which = li.openLive ? "지금 경기" : "멈춘 경기";
        if (id == LG_WIPE) return li.openVs != null ? which + "도 함께 지워요 · vs " + li.openVs : "기록·순위·상대·시즌을 모두 지워요 · 이름은 그대로";
        boolean ns = id == LG_NEW_SEASON;
        if (li.openVs != null) return which + "는 기권패로 닫고 " + (ns ? "새 시즌" : "처음부터") + " · vs " + li.openVs;
        return ns ? "시즌 " + (li.season + 1) + " · LP " + li.lp0 + "→" + li.lp1 + " · 승패 0부터" : "배치 5판부터 · 지금 기록은 보관";
    }
    /** 글 목록(L 층 20×n) — 줄마다 갈무리7 한 줄(156px «…») · 칠 g 금 · d 흐림 · n 보통 */
    private TileCanvas.Encoded[] textList(java.util.List<String[]> lines) {
        TileCanvas cv = new TileCanvas(20, Math.max(1, lines.size()));
        int ink0 = cv.pal(C_INK[0], C_INK[1], C_INK[2]), gold = cv.pal(C_GOLD[0], C_GOLD[1], C_GOLD[2]);
        for (int k = 0; k < lines.size(); k++) {
            String[] l = lines.get(k);
            boolean g = "g".equals(l[1]);
            cv.cellPal(0, k, 19, k, g ? gold : ink0);
            cv.text(fit(cover7(l[0]), g ? 150 : 152, ink.g7), g ? 2 : 6, k * 8, "d".equals(l[1]) ? 2 : 1, ink.g7);
        }
        return cv.encodeBands(0, TileCanvas.MAX_CHUNK, 2048);
    }

    private static void drawerPals(TileCanvas cv) {
        setPal(cv, D_INK, C_INK); setPal(cv, D_GOLD, C_GOLD); setPal(cv, D_SEL, C_SEL); setPal(cv, D_SEAL, C_SEAL);
        setPal(cv, D_PLATE1, C_PLATE1); setPal(cv, D_PLATEM, C_PLATEM); setPal(cv, D_WOOD, C_WOOD); setPal(cv, D_CLOTH, C_CLOTH);
        setPal(cv, D_CLOTHK, C_CLOTHK); setPal(cv, D_CLOTHR, C_CLOTHR); setPal(cv, D_PIP, C_PIP); setPal(cv, D_CHART, C_CHART);
        setPal(cv, D_CHAIN, C_CHAIN); setPal(cv, D_LOAD, C_GOLD);
        cv.setPal(D_TIER, 0x07F, 0x035, 0x012);
    }
    /** 서랍 머리(r0–1) — 16px 낙관 + 제목(굵게, 그림자) + [CPU] 칩 + 오른쪽 두 줄 */
    private void drawerHead(TileCanvas cv, String sealCh, String title, String right1, String right2, int r2pal, boolean cpu) {
        cv.cellPal(0, 0, 1, 1, D_SEAL);
        cv.art(OvArt.SEAL16_FRAME, 0, 0, 0, 2, 3, false);
        cv.text(sealCh, 2, 2, 1, ink.g11b, -1, TileCanvas.LEFT, 12);
        cv.cellPal(2, 0, 7, 1, D_GOLD);
        int x = cv.text(title, 21, 2, 1, ink.g11b, 2, TileCanvas.LEFT, 0);
        if (cpu) {
            int cx = (x + 4 + 7) / 8 * 8;
            cv.cellPal(cx / 8, 0, cx / 8 + 2, 1, D_INK);
            cv.fill(cx, 3, cx + 18, 12, 2);
            cv.text("CPU", cx + 2, 4, 3, ink.g7);
        }
        cv.cellPal(11, 0, 19, 0, D_INK);                                   /* 168: 69px — 오른쪽 끝 157 에서 칸 11(x 88) 안으로(70 이면 x 87 = 팔레트 없는 칸 10 에 잉크 · 리그 탭 «시즌 12 · 10월 31일부터») */
        cv.text(fit(right1, 69, ink.g7), 157, 0, 2, ink.g7, -1, TileCanvas.RIGHT, 0);
        cv.cellPal(11, 1, 19, 1, r2pal);
        cv.text(fit(right2, 69, ink.g7), 157, 8, r2pal == D_INK ? 2 : 1, ink.g7, -1, TileCanvas.RIGHT, 0);
    }
    /** 탭 줄(r2) — 168: 순위 · 전적 · 리그 · 설정 넷(보이는 차례 MenuModel.Prefs.TAB_ORDER) · 지금 탭 = 주홍 칩 */
    private void tabRow(Sb s, int cur) {
        TileCanvas cv = s.cv;
        String[] names = { "순위", "전적", "리그", "설정" };
        int[] ids = MenuModel.Prefs.TAB_ORDER;
        int[] cols = { 3, 7, 11, 15 };
        java.util.List<Row> tabs = new java.util.ArrayList<>();
        for (int k = 0; k < names.length; k++) {
            int c = cols[k], w = (cv.textW(names[k], ink.g7) + 7) / 8;
            w = Math.max(2, w);
            if (ids[k] == cur) {
                cv.cellPal(c, 2, c + w - 1, 2, D_SEL);
                cv.fill(c * 8, 16, (c + w) * 8 - 1, 23, 3);
                cv.fill(c * 8, 23, (c + w) * 8 - 1, 23, 2);
                cv.text(names[k], c * 8 + (w * 8 - cv.textW(names[k], ink.g7)) / 2, 16, 1, ink.g7);
            } else {
                cv.cellPal(c, 2, c + w - 1, 2, D_INK);
                cv.text(names[k], c * 8 + (w * 8 - cv.textW(names[k], ink.g7)) / 2, 16, 2, ink.g7);
            }
            Row t = new Row();
            t.id = ids[k]; t.c0 = c; t.r0 = 2; t.c1 = c + w - 1; t.r1 = 2; t.enabled = true;
            t.rc0 = c - 1; t.rr0 = 2; t.rc1 = c + w; t.rr1 = 2;
            tabs.add(t);
        }
        cv.cellPal(0, 2, 0, 2, D_GOLD);
        cv.cellPal(19, 2, 19, 2, D_GOLD);
        cv.art(OvArt.ARROW_L, 1, 16, 2, 0, 0, false);
        cv.art(OvArt.ARROW_R, 155, 16, 2, 0, 0, false);
        s.scene().tabs = tabs.toArray(new Row[0]);
    }
    /** 서랍 대본 — 처음 열 때 족자 펼침 10f · 탭 바꿈 칼 휘두름 10f · 닫을 때 족자 말림 8f. lRect = L 층 사각형(없으면 null) */
    private static void drawerCues(Scene sc, int[] lRect, boolean loading) {
        boolean hasL = sc.l != null;
        Cue q = new Cue().base(0, UNROLL, B_FILL, Emu.OV_BASE_FILL, 0, 151, 0, 152, 0x012, Cue.EASE_LINEAR)
                .clip(0, UNROLL, Cue.W, Cue.EDGE_BOTTOM, 0, 151, Cue.EASE_LINEAR);
        if (hasL) q.clip(0, UNROLL, Cue.L, Cue.EDGE_BOTTOM, 0, sc.lClipY1, Cue.EASE_LINEAR);
        drawerAmbient(q, UNROLL, loading, sc.rows != null && sc.rows.length > 0);
        sc.cue = q.build(); sc.cueEnd = q.end(); sc.readyT = UNROLL;
        Cue w = new Cue().wipe(0, TAB_SWING, Cue.W, 0, 3, 19, 18, 2, false, D_GOLD, 1, Cue.EASE_LINEAR);
        if (hasL && lRect != null) w.wipe(0, TAB_SWING, Cue.L, lRect[0], lRect[1], lRect[2], Math.min(127, lRect[3]), 2, false, 0, 0, Cue.EASE_LINEAR);
        sc.swapCue = concat(snap(sc.cue), w.build());
        Cue c = new Cue().base(0, ROLLUP, B_FILL, Emu.OV_BASE_FILL, 0, 151, 152, 0, 0x012, Cue.EASE_IN)
                .clip(0, ROLLUP, Cue.W, Cue.EDGE_BOTTOM, 151, 0, Cue.EASE_IN);
        if (hasL) c.clip(0, ROLLUP, Cue.L, Cue.EDGE_BOTTOM, sc.lClipY1, 0, Cue.EASE_IN);
        sc.closeCue = c.build(); sc.closeDur = ROLLUP;
    }
    private static void drawerAmbient(Cue q, int t0, boolean loading, boolean cursor) {
        q.palCycle(t0, Cue.AMBIENT, Cue.W, D_PLATE1, 2, 0x7DF, 0xFFF, 8, false);     /* 1위 왕관 반짝임 */
        if (loading) q.palCycle(0, Cue.AMBIENT, Cue.W, D_LOAD, 1, 0x7DF, 0xFFF, 12, false);
        if (cursor) ambient(q, t0, D_SEL);
    }

    /* ════════════════════════════════════════════════════════════ 157 post — 경기 중 HUD 이름표 */

    /** 이름표 한쪽 — 덧판 native 의 이름표 칸(Emu.nativeOvTag)에 그대로 올린다(메뉴 층과 따로 · 입력 안 막음).
     *  px = w×h 칠(1 티어 빛 · 2 닉 · 3 그림자) · (x, y) 게임 px · pals = 강조 c1..c3 · 보통 c1..c3 */
    static final class HudTag {
        byte[] px; int w, h, x, y; short[] pals; String label, nick;
    }
    /** 사무쇼2 HUD 실측(scratch u157/impl/post/h/measure — CPU 대 CPU 5판): 체력 막대 아래 검은 줄 = 게임 줄 24..31.
     *  라운드 표지 P1 x0..12(이기면 ★ x0..6) · P2 x146..158(★ x152..158) · 히트 수 P1 x34..63 · P2 x98..127(그때는 native 가 비켜 줌).
     *  P1 = 표지 뒤 x16 부터 왼쪽 맞춤 · P2 = 표지 앞 x142 까지 오른쪽 맞춤 · 한쪽 62px(가운데 80 을 안 넘음) */
    static final int TAG_Y = 24, TAG_X_P1 = 16, TAG_X1_P2 = 142, TAG_MAXW = 62, HUD_GAP = 3;
    /** 강조(라운드 시작 2초) 닉 금빛 · 보통 닉 밝은 회색 */
    static final int TAG_NICK_EMPH = 0x7DF, TAG_NICK = 0xBBB;
    /** 이름표 «검객★1 닉…» — 티어 이름(갈무리7) + 작은 별(STAR5)·숫자(mini3)로 짧게(닉 자리를 늘림) · 티어 빛(배치는 수련생 빛 «배치» · 무쌍 «무쌍») ·
     *  닉은 남는 폭에 «…»로. 강조(라운드 시작 2초) = 닉 금빛 · 티어 제 빛 → 보통 = 닉 밝은 회색 · 티어 빛을 그림자 쪽으로 ⅓. opp = 오른쪽(P2) */
    HudTag hudTag(MenuModel.Side s, boolean opp) {
        if (s == null) return null;
        TileCanvas cv = new TileCanvas(8, 1);
        int t = s.placement ? 0 : Math.max(0, Math.min(7, s.tier));
        String name = s.placement ? "배치" : RankCore.TIER[t];
        int x = cv.text(name, 0, 1, 1, ink.g7);
        String label = name;
        if (!s.placement && !s.master && t < 7) {
            int st = Math.max(0, Math.min(5, s.stars));
            cv.art(OvArt.STAR5, x + 1, 2, 1, 0, 0, false);
            x = cv.mini3(String.valueOf(st), x + 7, 2, 1, TileCanvas.LEFT) - 1;
            label = name + "★" + st;
        }
        String nick = null;
        int nx = x + HUD_GAP, room = TAG_MAXW - nx;
        if (room >= 12 && s.nick != null && !s.nick.isEmpty()) {
            nick = fit(cover7(s.nick), room, ink.g7);
            cv.text(nick, nx, 1, 2, ink.g7);
        }
        int w = 0;
        for (int yy = 0; yy < 8; yy++) for (int xx = 0; xx < 64; xx++) if (cv.get(xx, yy) != 0 && xx + 1 > w) w = xx + 1;
        if (w <= 0) return null;
        w = Math.min(w, TAG_MAXW);
        HudTag g = new HudTag();
        g.w = w; g.h = 8; g.y = TAG_Y; g.x = opp ? TAG_X1_P2 + 1 - w : TAG_X_P1;
        g.px = new byte[w * 8];
        for (int yy = 0; yy < 8; yy++) for (int xx = 0; xx < w; xx++) g.px[yy * w + xx] = (byte) cv.get(xx, yy);
        int tc = TIER_C[t][0], tn = nibMix(TIER_C[t][0], TIER_C[t][1], 1, 3);
        g.pals = new short[]{ (short) tc, (short) TAG_NICK_EMPH, 0, (short) tn, (short) TAG_NICK, 0 };
        g.label = label; g.nick = nick;
        return g;
    }
    /** 12비트 색을 니블마다 a → b 로 k/n */
    static int nibMix(int a, int b, int k, int n) {
        int r = 0;
        for (int sh = 0; sh < 12; sh += 4) { int x = (a >> sh) & 15, y = (b >> sh) & 15; r |= (x + (y - x) * k / n) << sh; }
        return r;
    }

    /* ════════════════════════════════════════════════════════════════════ 공통 */

    /** 장면 짓는 판 */
    private final class Sb {
        final TileCanvas cv = new TileCanvas(32, 32);
        final java.util.List<Row> rows = new java.util.ArrayList<>();
        final java.util.List<int[]> panes = new java.util.ArrayList<>(), base = new java.util.ArrayList<>();
        final int[] tagC0 = new int[MAX_TAGS], tagC1 = new int[MAX_TAGS];
        private final Scene sc = new Scene();
        Sb(int kind) { sc.kind = kind; }
        Scene scene() { return sc; }
        void pane(int idx, int c0, int r0, int c1, int r1, int dx, int dy, boolean vis) { panes.add(new int[]{ Emu.OV_W, idx, c0, r0, c1, r1, dx, dy, vis ? 1 : 0 }); }
        void base(int slot, int mode, int y0, int y1, int arg, int color) { base.add(new int[]{ slot, mode, y0, y1, arg, color }); }
        final java.util.List<int[]> extraQuads = new java.util.ArrayList<>();
        /** 단도 타일을 쓰는 덤 쿼드(커서 말고) */
        void quad(int idx, int x, int y, int pal) { extraQuads.add(new int[]{ idx, x, y, pal }); }
        /** 인코딩 + 쿼드(단도 = 창고 c31 r31 의 타일) */
        Scene finish(int unused) {
            TileCanvas.Encoded e = cv.encode(0, SCENE_TILES, true, strict);
            sc.w = e;
            sc.tiles = e.ntiles; sc.pals = e.npals;
            sc.rows = rows.toArray(new Row[0]);
            sc.panes = flat(panes, 9);
            sc.base = flat(base, 6);
            int ke = e.map[31 * e.cols + 31] & 0xFFFF;
            if (ke != 0xFFFF && sc.rows.length > 0) {
                int tile = e.tileBase + (ke & 0x1FF), pal = (ke >> 9) & 15;
                int fl = ((ke & 0x8000) != 0 ? Emu.OV_Q_HFLIP : 0) | ((ke & 0x4000) != 0 ? Emu.OV_Q_VFLIP : 0);
                Row r0 = sc.rows[Math.max(0, Math.min(sc.rows.length - 1, sc.defaultRow))];
                java.util.List<int[]> q = new java.util.ArrayList<>();
                q.add(new int[]{ Emu.OV_W, Q_CURSOR, r0.quadX, r0.quadY, tile, pal, fl | Emu.OV_Q_SHOW });
                for (int[] x : extraQuads) q.add(new int[]{ Emu.OV_W, x[0], x[1], x[2], tile, x[3], fl | Emu.OV_Q_SHOW });
                sc.quads = flat(q, 7);
            } else sc.quads = new int[0];
            return sc;
        }
    }
    private static int[] flat(java.util.List<int[]> l, int n) {
        int[] o = new int[l.size() * n];
        for (int i = 0; i < l.size(); i++) System.arraycopy(l.get(i), 0, o, i * n, n);
        return o;
    }
    private static void setPal(TileCanvas cv, int slot, int[] c) { cv.setPal(slot, c[0], c[1], c[2]); }
    private String fit(String s, int w, TileCanvas.Ink k) {
        if (s == null) return "";
        TileCanvas tmp = FIT;
        return tmp.fit(s, w, k);
    }
    private final TileCanvas FIT = new TileCanvas(1, 1);
    /** 갈무리7 에 없는 글자는 «?»(8px 줄에 갈무리11 을 그리면 아래 줄로 샌다) */
    private String cover7(String s) {
        if (s == null) return "";
        if (TileCanvas.covers(ink.g7, s)) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); ) { int cp = s.codePointAt(i); b.append(ink.g7.has(cp) ? new String(Character.toChars(cp)) : "?"); i += Character.charCount(cp); }
        return b.toString();
    }
    /** 갈무리11 로 그리는 자리(띠 이름) — 없는 글자는 «?» */
    private String cover7or11(String s) {
        if (s == null) return "";
        if (TileCanvas.covers(ink.g11, s)) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); ) { int cp = s.codePointAt(i); b.append(ink.g11.has(cp) ? new String(Character.toChars(cp)) : "?"); i += Character.charCount(cp); }
        return b.toString();
    }
    static boolean mini3able(String s) {
        for (int i = 0; i < s.length(); i++) if ("0123456789/+-,".indexOf(s.charAt(i)) < 0) return false;
        return !s.isEmpty();
    }

    /** 대본을 «끝 상태»로 — 다른 장면에서 돌아올 때(오래 남는 op 는 t0 0·dur 0 = 바로 끝 값, 잠깐 op 는 뺌, 늘 도는 것은 t0 0) */
    static int[] snap(int[] ops) {
        if (ops == null) return new int[0];
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        for (int i = 0; i + Cue.INTS <= ops.length; i += Cue.INTS) {
            int op = ops[i], dur = ops[i + 2];
            if (op == Cue.OP_STAMP || op == Cue.OP_FLASH) continue;
            if ((op == Cue.OP_PALCYCLE || op == Cue.OP_BOB) && dur >= 0) continue;
            int[] o = java.util.Arrays.copyOfRange(ops, i, i + Cue.INTS);
            o[1] = 0;
            if (dur >= 0) o[2] = 0;
            out.add(o);
        }
        return flat(out, Cue.INTS);
    }
    static int[] concat(int[] a, int[] b) {
        int[] o = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }
}
