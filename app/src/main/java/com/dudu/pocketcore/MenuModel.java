package com.dudu.pocketcore;

import org.json.JSONObject;

/**
 * 157 리그 인게임 메뉴의 «불변 사본» — UI 스레드에서 League·RankCore 를 읽어 한 번 만들고, 그 뒤로는 아무도 고치지 않는다.
 * GL 스레드(LeagueMenu)와 그림 스레드(LgmPages)는 이것만 읽는다(League 를 직접 읽지 않음).
 * 계약서 docs/157_contract.md §8.
 *
 * 157-B 이후를 위한 자리: 내·상대 캐릭터 id(Side.ch/sty)는 늘 채운다 — 나중에 «캐릭터 얼굴 타일 묶음»(롬 밖 우리 그림 또는 별도 조사)을
 * LgmPages 가 선택적으로 얹을 수 있게(FaceProvider, 157-A 는 늘 null).
 *
 * 글은 한국어 «~해요» 체, 짧게. 빼기는 ASCII '-'(픽셀 글꼴에 U+2212 가 없다 — RankUi.signed 와 같게).
 */
final class MenuModel {
    /* 종류 */
    static final int K_RESULT = 1, K_PAUSE = 2, K_LOBBY = 3;    // 168: K_LOBBY = 157-B «대기»(리그 타이틀)
    /* 결과 */
    static final int O_WIN = 1, O_LOSS = 2, O_DRAW = 3, O_FORFEIT = 4, O_VOID = 5;   // VOID = 보류(hold)·무효(cancelled)
    /* 경기 시작 때의 사다리 */
    static final int M_LP = 0, M_PLACEMENT = 1, M_MASTER = 2;
    /* 사건 — 호명 띠(최대 2) · 아래 띠 도장 */
    static final int E_PROMOTE = 1, E_DEMOTE = 2, E_MASTER = 3, E_STAR_UP = 4, E_REVENGE = 5, E_RIVAL = 6, E_STREAK = 7,
            E_FIRST_WIN = 8, E_PLACED = 9, E_RISK = 10, E_BROKE = 11, E_PROTECT = 12, E_CHEAT = 13;   // 168: 치트 판(도장 «치»)
    /** 호명 띠 우선순위(앞이 먼저) — 기획서 §5 «승급·강등 > 설욕·라이벌 > 3연승 이상 > 오늘 첫 승 > 연승 끊음 > 배치 완료» 에
     *  무쌍 달성(승급과 같은 급)·★ 올라감(목업 «★4 올라감!»)을 끼움. E_RISK·E_BROKE·E_PROTECT 는 띠 없이 머리·도장으로만 */
    static final int[] RIBBON_ORDER = { E_PROMOTE, E_MASTER, E_DEMOTE, E_STAR_UP, E_REVENGE, E_RIVAL, E_STREAK, E_FIRST_WIN, E_PLACED };
    /* 칼집 줄 */
    static final int ROW_NEXT = 1, ROW_REMATCH = 2, ROW_NEWCHAR = 3, ROW_DRAWER = 4, ROW_QUIT = 5,
            ROW_RESUME = 6, ROW_LATER = 7, ROW_FORFEIT = 8, ROW_VOIDQUIT = 9,
            /* 168 대기(K_LOBBY) — 상대 찾기 / 멈춘 경기 이어서 · 서랍 · 내 캐릭터 바꾸기 · 멈춘 경기 기권·무효 · 리그 나가기 */
            ROW_SEARCH = 10, ROW_RESUME_OPEN = 11, ROW_MAIN = 12, ROW_OPEN_FORFEIT = 13, ROW_OPEN_VOID = 14, ROW_LEAVE = 15;
    /* 그만할까요? 줄 */
    static final int QUIT_TITLE = 1, QUIT_BOARD = 2, QUIT_GAME = 3, QUIT_LIST = 4, QUIT_CANCEL = 5;

    /** 띠 한쪽 — VS 띠(RankVs.Side)와 같은 값 */
    static final class Side {
        final String nick;          // 닉네임(나 = League.myNick())
        final String style;         // «수라»·«나찰» — 내 쪽, 대신 고른 판만(없으면 null)
        final int tier, stars, lp;  // tier 0..7(RankCore.TIER) · stars 0..5 · lp(무쌍이면 MR)
        final boolean master, placement;
        final int placementPlayed;  // 배치 n/5 의 n(배치 아니면 0)
        final int ch, sty;          // 캐릭터 id 0..14 · 검질 0/1 (−1 모름) — 157-B 얼굴 자리
        Side(String nick, String style, int tier, int stars, int lp, boolean master, boolean placement, int placementPlayed, int ch, int sty) {
            this.nick = nick; this.style = style; this.tier = tier; this.stars = stars; this.lp = lp; this.master = master;
            this.placement = placement; this.placementPlayed = placementPlayed; this.ch = ch; this.sty = sty;
        }
    }
    /** 아래 띠 꼬리표 도장 — seal 은 한 글자(연·설·첫·위·라), text 는 그 옆 짧은 말(«3연승» «+30» «이기면 +62») */
    static final class Tag {
        final char seal; final String text; final int event;
        Tag(char seal, String text, int event) { this.seal = seal; this.text = text; this.event = event; }
    }
    /** G10 기권 미리보기(League.forfeitPreview) — 쉼의 아래 띠 */
    static final class Forfeit {
        static final int FP_LP = 0, FP_PLACEMENT = 1, FP_NOVICE = 2, FP_VOID = 3;   // VOID = 첫 라운드 전(무효 · LP 없음)
        final int kind, lpDelta, brokeStreak;    // lpDelta ≤ 0 · brokeStreak = 끊길 내 연승(2 이상일 때만, 아니면 0)
        final String line;                       // «패배로 기록 · −18 LP · 연승 끊김» / «배치 1패로 기록» / «LP 그대로 · 수련생 보호» / «무효 · LP 없음»
        final String shorter;                    // 줄이 넘칠 때(156px) — 덜 중요한 «연승 끊김»을 뺀 것(강등·강등 위기는 남김)
        Forfeit(int kind, int lpDelta, int brokeStreak, String line) { this(kind, lpDelta, brokeStreak, line, line); }
        Forfeit(int kind, int lpDelta, int brokeStreak, String line, String shorter) { this.kind = kind; this.lpDelta = lpDelta; this.brokeStreak = brokeStreak; this.line = line; this.shorter = shorter; }
    }
    /** 메뉴 설정(League pref.lgm) — 서랍 «설정» 탭 */
    static final class Prefs {
        static final int FX_NORMAL = 0, FX_SHORT = 1, FX_OFF = 2;            // 결판 연출 보통(기본) · 짧게 · 끄기(= 옛 Java 결과 카드)
        static final int LOSS_REVENGE = 0, LOSS_NEXT = 1;                    // 진 판 기본 커서 설욕전(기본) · 다음 상대
        static final int TAB_BOARD = 0, TAB_RECORD = 1, TAB_SETTINGS = 2, TAB_LEAGUE = 3;   /* 저장 값(pref.lgm.tab)은 번호 그대로 */
        /** 168 — 보이는 차례: 순위 · 전적 · 리그 · 설정 */
        static final int[] TAB_ORDER = { TAB_BOARD, TAB_RECORD, TAB_LEAGUE, TAB_SETTINGS };
        final int fx, lossCursor, autoNextSec, lastTab;                       // autoNextSec 0 끔(기본) · 10
        final boolean rememberTab;                                            // 기본 true
        Prefs(int fx, int lossCursor, int autoNextSec, boolean rememberTab, int lastTab) {
            this.fx = fx; this.lossCursor = lossCursor; this.autoNextSec = autoNextSec; this.rememberTab = rememberTab; this.lastTab = lastTab;
        }
        static Prefs defaults() { return new Prefs(FX_NORMAL, LOSS_REVENGE, 0, true, TAB_BOARD); }
        Prefs withFx(int v) { return new Prefs(v, lossCursor, autoNextSec, rememberTab, lastTab); }
        Prefs withLoss(int v) { return new Prefs(fx, v, autoNextSec, rememberTab, lastTab); }
        Prefs withAuto(int v) { return new Prefs(fx, lossCursor, v, rememberTab, lastTab); }
        Prefs withRemember(boolean v) { return new Prefs(fx, lossCursor, autoNextSec, v, lastTab); }
        Prefs withTab(int v) { return new Prefs(fx, lossCursor, autoNextSec, rememberTab, v); }
        boolean same(Prefs o) { return o != null && fx == o.fx && lossCursor == o.lossCursor && autoNextSec == o.autoNextSec && rememberTab == o.rememberTab && lastTab == o.lastTab; }
    }
    /** 168 §2 — 서랍 «리그» 탭 사본(League.leagueInfo · UI 스레드에서 한 번 · 불변으로 씀) */
    static final class LeagueInfo {
        int season; String since, wld; String[] pastRows = new String[0];   // 시즌 줄 · 지난 시즌(새것부터)
        boolean placement; int placementPlayed;                            // 배치 중이면 새 시즌 줄 없음(못 쓰는 줄은 안 넣는다)
        int lp0, lp1; String label0, label1;                                // 새 시즌 미리보기 LP lp0 → lp1
        boolean canSeason, canMain;                                         // 시즌 도구(새 시즌·처음부터·179 리그 초기화) 줄 · 내 캐릭터 줄을 넣나(179: 시즌 도구는 쉼·결판에서 연 서랍도 — 기록 파일 문제면 false · 내 캐릭터는 대기만)
        String openVs, openForfeit;                                         // 멈춘 경기(시작한 열린 경기) 상대 닉 · 기권 몫(«−18 LP»/«배치 1패») — 두 번 묻기 글
        boolean openLive;                                                   // 179: 그 열린 경기가 지금 이 판에서 도는 경기(쉼에서 연 서랍) — 두 번 묻기 글 «지금 경기…»(아니면 «멈춘 경기…»)
        String[] holds = new String[0];                                     // 보류·무효 줄(새것부터 5)
        String mainLabel, name;                                             // 내 캐릭터(null = 아직) · 내 이름
        String[] rules = new String[0];                                     // 짧은 규칙 줄
        int cheatGames;                                                     // 이번 시즌 치트 판 수
    }
    /** 서랍 «전적» 요약(이번 시즌 · 통산) */
    static final class Record {
        int season, w, l, d, streak, best, winPct;        // winPct = 0..100(판 0 이면 −1)
        char[] last10;                                     // 새것부터 'W' 'L' 'D'
        int todayW, todayL, todayD, todayLp; boolean firstWinTaken;
        int[] lpTrail;                                     // 최근 배치 아닌 판 lp_after 20개, 오래된 것부터(지금 값이 끝)
        int careerW, careerL, careerD; String peakLabel;  // 통산 · 최고 티어(«검호★1»)
        String rivalNick; int rivalW, rivalL; String rivalTag;   // ⛓ 줄(없으면 rivalNick null)
        int lp; boolean master, placement; int mr, placementPlayed;   // 지금 내 값(차트 끝·머리 «무사★4 · 1935»)
        int cheatGames;                                    // 168: 이번 시즌 «치트» 판 수
    }

    /* ── 공통 ── */
    final int kind;
    final String matchId;
    final Side me, opp;
    final Prefs prefs;
    final int todayW, todayL, todayD, todayLp; final boolean bonusLeft;   // rankedToday · bonusLeft
    final int sessW, sessL, sessD, sessLp;                               // G2 ranked.session(세션이 없으면 sessW = −1 — 화면은 오늘 값으로)
    final long rankNow, rankBefore, total;      // BoardCache — 모르면 −1(rankBefore = 이 판 전, 없으면 세션 시작 rank0)
    final Record record;                        // 서랍 전적(만들 때 채움 — 가벼움)

    /* ── 결판 ── */
    final int outcome, mode;                    // O_* · M_*
    final int w1, w2;                           // 최종 라운드 승수(기권·무효 −1)
    final int lpBefore, lpAfter, lpDelta, mrBefore, mrAfter;
    /** 157 post — 상대 LP(무쌍이면 MR) 판 전 → 뒤(League.applyRanked 가 적은 opp_lp_before/after — 순위표와 같은 값). 옛 기록·보류·무효면 둘 다 경기 사본 값 */
    final int oppBefore, oppAfter; final boolean oppMasterBefore, oppMasterAfter;
    final int tierBefore, tierAfter, starBefore, starAfter;
    final int starFloorBefore, starNextBefore, starFloorAfter, starNextAfter;   // 막대: 지금 ★ 칸의 바닥 LP · 다음 ★(또는 승급) LP
    final int placementLeft;                    // 판 뒤(배치 아니면 0)
    final boolean promoted, demoted, enteredMaster, placementDone, starUp, shieldUsed, shieldActive, noviceProtect;
    final int bonusStreak, bonusRevenge, bonusDaily, streakAfter, brokeStreak;
    final int h2hW, h2hL, h2hD;
    final String event;                         // RankCore Outcome.event 그대로(없으면 null)
    final int[] ribbons;                        // E_* 최대 2개(RIBBON_ORDER)
    final Tag[] tags;                           // 아래 띠 도장(띠에 못 든 사건 + 연승·설욕·첫 승·위·라)
    final boolean eventful;                     // 일섬 조건(★ 올라감·승급·무쌍 달성·설욕 성공) — 설정 «보통»에서만 씀
    final int[] rows;                           // ROW_* 순서(칼집 위에서 아래로)
    final boolean[] rowOn;                      // 고를 수 있나(흐린 줄은 커서가 건너뜀)
    final String[] rowDesc;                     // 아래 띠 r18 설명(흐린 줄이면 이유)
    final String[] rowRight;                    // 줄 오른쪽 작은 글(«2/3» «저장» «−18»), 없으면 null
    final int defaultRow;                       // rows 의 칸 번호(진 판 = 설욕전, prefs.lossCursor)
    final int rematchPlayed;                    // 재대결 «n/3» 의 n = 이 상대와 이어 붙은 판 수(이번 판 포함)
    final int stageSel;                         // pref.stage(−1 상대 기본 · −2 무작위 · 0..7)

    /* ── 쉼 ── */
    final boolean started;                      // false = 첫 라운드 전 — 기권 자리에 «그만두기 (무효)»
    final int roundsMe, roundsOpp;              // 지금 라운드 승수(덧판 상태 OVS_W1/W2 — 모르면 −1)
    final String oppTag;                        // RankCore 꼬리표 첫째(«설욕전»·«라이벌»…) 없으면 null
    final int[] stakes;                         // {이기면, 지면} LP — 배치·무쌍이면 null
    final Forfeit forfeit;                      // G10
    final int[] oppSwatch;                      // 177 쉼 정보 패 상대 컬러 견본 — 상대가 지금 보이는 세 색(거울 대신 색이면 그 색) · 상대 컬러가 없으면 null

    /** 만들 때 필요한 그 밖의 값(UI 스레드가 채움) */
    static final class Ctx {
        int rankAuto;                    // EmuActivity.rankAuto(2 = 저장한 내 캐릭터로 대신 고름 → «새 캐릭터» 줄 켬)
        int stageSel;                    // League.prefStage()
        int[] ov;                        // Emu.nativeOvStatus()(라운드 승수 — 없으면 null)
        boolean started;                 // 쉼: 첫 라운드를 봤나
        int[] look;                      // 177 Emu.nativeLookStatus() — [18] 상대 컬러 상태 · [19] 대신 색 번호(옛 native·없으면 null)
        BoardCache.Board board, boardBefore;   // 지금 · 이 판 전(없으면 null)
        Prefs prefs;
    }

    /** 다른 메뉴 값만 바꾼 사본(설정 탭에서 바꾼 뒤 결판 줄 기본 커서·연속 대전이 새 값을 쓰게) */
    MenuModel withPrefs(Prefs p) {
        B b = new B(this);
        b.prefs = p != null ? p : prefs;
        return new MenuModel(b);
    }
    /** 순위가 늦게 왔을 때(BoardCache onReady) — 순위 칸만 바꾼 사본 */
    MenuModel withRank(long now, long before) {
        B b = new B(this);
        b.rankNow = now; b.rankBefore = before;
        return new MenuModel(b);
    }

    /* ────────────────────────────────────────────────────────────────────── 만들기 */

    /** 결판 사본 — m = 정산·기권·보류·무효로 끝난 랭크 경기 기록. 틀리면 null(옛 카드로) */
    static MenuModel result(android.content.Context c, JSONObject m, Ctx ctx) {
        try {
            if (m == null || !League.isRanked(m) || ctx == null) return null;
            String st = m.optString("status");
            if (!League.terminal(st)) return null;
            B b = common(c, m, ctx, K_RESULT);
            boolean voidM = "hold".equals(st) || "cancelled".equals(st);
            boolean forfeit = "forfeit".equals(st) || m.optBoolean("forfeit");
            String out = m.optString("outcome");
            if (voidM) b.outcome = O_VOID;
            else if (forfeit) b.outcome = O_FORFEIT;
            else if ("W".equals(out)) b.outcome = O_WIN;
            else if ("L".equals(out)) b.outcome = O_LOSS;
            else if ("D".equals(out)) b.outcome = O_DRAW;
            else return null;
            JSONObject s0 = m.optJSONObject("start");
            if (s0 == null) return null;
            String tb = m.optString("tier_before", s0.optString("tier"));
            b.mode = "배치".equals(tb) ? M_PLACEMENT : s0.optBoolean("master") ? M_MASTER : M_LP;
            JSONObject res = m.optJSONObject("result");
            if (voidM || forfeit || res == null) { b.w1 = -1; b.w2 = -1; }
            else { b.w1 = res.optInt("w1"); b.w2 = res.optInt("w2"); }
            if (voidM) {                                          /* 보류·무효 — LP 그대로(시작 값) */
                b.lpBefore = b.lpAfter = s0.optInt("lp"); b.lpDelta = 0;
                b.mrBefore = b.mrAfter = s0.optInt("mr");
                b.placementLeft = s0.optInt("placement_left");
            } else {
                b.lpBefore = m.optInt("lp_before", s0.optInt("lp")); b.lpAfter = m.optInt("lp_after", b.lpBefore); b.lpDelta = m.optInt("lp_delta", b.lpAfter - b.lpBefore);
                b.mrBefore = m.optInt("mr_before", s0.optInt("mr")); b.mrAfter = m.optInt("mr_after", b.mrBefore);
                b.placementLeft = Math.max(0, m.optInt("placement_left"));
            }
            boolean masterAfter = RankCore.TIER[7].equals(m.optString("tier_after")) || (b.mode == M_MASTER);
            b.tierBefore = b.mode == M_MASTER ? 7 : RankCore.tierOf(b.lpBefore);
            b.tierAfter = masterAfter ? 7 : RankCore.tierOf(b.lpAfter);
            b.starBefore = b.tierBefore == 7 ? 0 : RankCore.starOf(b.lpBefore);
            b.starAfter = b.tierAfter == 7 ? 0 : RankCore.starOf(b.lpAfter);
            int[] fb = starBand(b.lpBefore), fa = starBand(b.lpAfter);
            b.starFloorBefore = fb[0]; b.starNextBefore = fb[1]; b.starFloorAfter = fa[0]; b.starNextAfter = fa[1];
            {   /* 157 post: 상대 판 전·뒤 — 띠 오른쪽 게이지·숫자가 이 둘 사이를 같이 오르내린다. 띠의 상대 칸(Side)은 판 뒤 모습(나처럼) */
                JSONObject sn = m.optJSONObject("opp_snap");
                int slp = sn != null ? sn.optInt("lp") : 0, smr = sn != null ? sn.optInt("mr") : 0;
                boolean has = !voidM && m.has("opp_lp_before") && m.has("opp_lp_after");
                int lb = has ? m.optInt("opp_lp_before") : slp, la = has ? m.optInt("opp_lp_after") : slp;
                b.oppMasterBefore = lb >= RankCore.MASTER_LP || (!has && smr > 0);
                b.oppMasterAfter = la >= RankCore.MASTER_LP || (!has && smr > 0);
                b.oppBefore = b.oppMasterBefore ? (has ? m.optInt("opp_mr_before", smr) : smr) : lb;
                b.oppAfter = b.oppMasterAfter ? (has ? m.optInt("opp_mr_after", smr) : smr) : la;
                if (has && b.opp != null) {
                    Side o = b.opp;
                    b.opp = new Side(o.nick, o.style, b.oppMasterAfter ? 7 : RankCore.tierOf(la), b.oppMasterAfter ? 0 : RankCore.starOf(la), b.oppAfter,
                            b.oppMasterAfter, false, 0, o.ch, o.sty);
                }
            }
            String ev = voidM ? null : m.optString("event", null);
            if (ev != null && ev.isEmpty()) ev = null;
            b.event = ev;
            b.promoted = ev != null && ev.startsWith("승급");
            b.enteredMaster = ev != null && ev.startsWith("무쌍 달성");
            b.demoted = ev != null && ev.endsWith("강등") && !ev.startsWith("강등 위기");
            b.shieldUsed = ev != null && ev.startsWith("강등 위기");
            b.starUp = ev != null && ev.startsWith("★ 올라감");
            b.placementDone = !voidM && (ev != null && ev.startsWith("배치 완료") || "배치".equals(tb) && b.placementLeft == 0);
            RankCore.Me now = League.rankMe();
            b.shieldActive = now.shield;
            b.noviceProtect = (b.outcome == O_LOSS || b.outcome == O_FORFEIT) && tb.startsWith(RankCore.TIER[0]) && b.lpDelta == 0;
            JSONObject bo = m.optJSONObject("bonus");
            if (bo != null && !voidM) { b.bonusStreak = bo.optInt("streak"); b.bonusRevenge = bo.optInt("revenge"); b.bonusDaily = bo.optInt("daily"); }
            JSONObject af = m.optJSONObject("after");
            String oid = m.optString("opp");
            int[] h = League.h2h(oid);
            if (af != null) { b.streakAfter = af.optInt("streak"); b.h2hW = af.optInt("h2h_w"); b.h2hL = af.optInt("h2h_l"); b.h2hD = af.optInt("h2h_d"); }
            else { b.streakAfter = now.streak; b.h2hW = h[0]; b.h2hL = h[1]; b.h2hD = h[2]; }
            b.brokeStreak = voidM ? 0 : m.optInt("broke_streak");
            boolean win = b.outcome == O_WIN, loss = b.outcome == O_LOSS || b.outcome == O_FORFEIT;
            RankCore.Player rp = League.rankPlayer(oid);
            boolean rival = rp != null && rp.legacy || b.h2hW + b.h2hL >= 4 && Math.abs(b.h2hW - b.h2hL) <= 1;
            /* 재대결 줄 */
            int played = League.rematchCount(oid);
            b.rematchPlayed = played;
            boolean supported = rp != null && League.oppSupported(rp.ch, rp.sty);
            boolean canRe = !voidM && supported && played < 3;
            /* 사건 → 띠·도장 */
            java.util.List<Integer> evs = new java.util.ArrayList<>();
            if (b.promoted) evs.add(E_PROMOTE);
            if (b.enteredMaster) evs.add(E_MASTER);
            if (b.demoted) evs.add(E_DEMOTE);
            if (b.starUp) evs.add(E_STAR_UP);
            if (win && b.bonusRevenge > 0) evs.add(E_REVENGE);
            if (win && rival) evs.add(E_RIVAL);
            if (win && b.streakAfter >= 3) evs.add(E_STREAK);
            if (win && b.bonusDaily > 0) evs.add(E_FIRST_WIN);
            if (b.placementDone) evs.add(E_PLACED);
            int maxR = b.prefs.fx == Prefs.FX_NORMAL ? 2 : 0;
            java.util.List<Integer> rib = new java.util.ArrayList<>();
            for (int e : RIBBON_ORDER) if (rib.size() < maxR && evs.contains(e)) rib.add(e);
            b.ribbons = new int[rib.size()];
            for (int i = 0; i < rib.size(); i++) b.ribbons[i] = rib.get(i);
            java.util.List<Tag> tags = new java.util.ArrayList<>();
            if (b.shieldUsed) tags.add(new Tag('위', "강등 위기", E_RISK));           /* 진 판 — 자리가 모자라면 이것부터 */
            if (win && b.streakAfter >= 2 && !rib.contains(E_STREAK)) tags.add(new Tag('연', b.streakAfter + "연승", E_STREAK));
            if (loss && b.brokeStreak >= 2) tags.add(new Tag('연', b.brokeStreak + "연승 끊김", E_BROKE));
            if (win && b.bonusRevenge > 0 && !rib.contains(E_REVENGE)) tags.add(new Tag('설', "+" + b.bonusRevenge, E_REVENGE));
            if (loss && canRe) {
                int reWin = League.rematchWinLp(rp);                        /* 재대결 승 정산 그대로(설욕 +20 · 오늘 첫 승 +30 포함) */
                if (reWin != Integer.MIN_VALUE) tags.add(new Tag('설', "이기면 " + signed(reWin), E_REVENGE));
            }
            if (win && b.bonusDaily > 0 && !rib.contains(E_FIRST_WIN)) tags.add(new Tag('첫', "+" + b.bonusDaily, E_FIRST_WIN));
            if (rival && !rib.contains(E_RIVAL)) tags.add(new Tag('라', b.h2hW + "-" + b.h2hL, E_RIVAL));
            if (m.optInt("cheat") != 0) tags.add(0, new Tag('치', "치트", E_CHEAT));   /* 168 §9 — 치트가 먹은 판(기록 표) — 자리가 모자라도 먼저 */
            b.tags = tags.toArray(new Tag[0]);
            b.eventful = !voidM && (b.starUp || b.promoted || b.enteredMaster || win && b.bonusRevenge > 0);
            /* 줄 */
            int[] rows;
            if (voidM) rows = new int[]{ ROW_NEXT, ROW_QUIT };
            else if (!canRe) rows = new int[]{ ROW_NEXT, ROW_NEWCHAR, ROW_DRAWER, ROW_QUIT };   /* 168: 못 쓰는 재대결 줄은 빼고 이유 글도 없음(유저 «안 돼요» 싫음) */
            else if (b.outcome == O_LOSS) rows = new int[]{ ROW_REMATCH, ROW_NEXT, ROW_NEWCHAR, ROW_DRAWER, ROW_QUIT };
            else rows = new int[]{ ROW_NEXT, ROW_REMATCH, ROW_NEWCHAR, ROW_DRAWER, ROW_QUIT };
            b.rows = rows;
            b.rowOn = new boolean[rows.length]; b.rowDesc = new String[rows.length]; b.rowRight = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                boolean on = true; String d, r = null;
                switch (rows[i]) {
                    case ROW_NEXT: d = "새 상대와 바로 한 판"; break;
                    case ROW_REMATCH:
                        on = canRe;
                        r = Math.max(1, Math.min(3, played)) + "/3";
                        if (on) d = b.outcome == O_LOSS ? "같은 상대와 바로 다시" : "같은 상대와 한 판 더";
                        else d = "";                                    /* 168: 못 쓰는 재대결 줄은 아예 안 넣는다(위 rows) — 이유 글 없음 */
                        break;
                    case ROW_NEWCHAR: d = "내 캐릭터부터 고르고 상대를 찾아요"; break;   /* 168 §6: 늘 켬 */
                    case ROW_DRAWER: d = "순위·전적·리그 보기"; break;
                    default: d = "타이틀로 · 리그는 켠 채"; break;   /* 157 post: 바로 타이틀로(A 로 다시 찾기) */
                }
                b.rowOn[i] = on; b.rowDesc[i] = d; b.rowRight[i] = r;
            }
            int def = indexOf(rows, ROW_NEXT);
            if (b.outcome == O_LOSS && b.prefs.lossCursor == Prefs.LOSS_REVENGE && canRe) def = indexOf(rows, ROW_REMATCH);
            b.defaultRow = Math.max(0, def);
            b.started = true; b.roundsMe = -1; b.roundsOpp = -1;
            b.stakes = null; b.forfeit = null; b.oppTag = null;
            return new MenuModel(b);
        } catch (Exception e) {
            android.util.Log.e("PocketCore", "MenuModel.result", e);
            return null;
        }
    }

    /** 177 쉼 견본 — native 가 이 프레임 실제로 칠한 것(look [18]): 0 → null · 1 주 색(기록 opp_snap.col.t) · 2 거울 대신 색(LookTab — 상대 줄·검질의 [19] 번) */
    static int[] oppSwatch(JSONObject m, int[] look) {
        if (m == null || look == null || look.length < 20 || look[18] == 0) return null;
        if (look[18] == 1) { OppLook.Col c = League.matchCol(m); return c != null ? c.t.clone() : null; }
        JSONObject r = m.optJSONObject("rule");
        int row = LookTab.row(r != null ? r.optInt("char", -1) : -1), sty = r != null ? r.optInt("style") & 1 : 0, i = look[19];
        if (look[18] != 2 || row < 0 || i < 0 || i >= LookTab.NPRE) return null;
        short[] t = LookTab.TAB[row][sty][i];
        return new int[]{ t[0], t[1], t[2] };
    }
    /** 쉼 사본 — m = 진행 중인 랭크 경기 기록 */
    static MenuModel pause(android.content.Context c, JSONObject m, Ctx ctx) {
        try {
            if (m == null || !League.isRanked(m) || ctx == null || League.terminal(m.optString("status"))) return null;
            JSONObject s0 = m.optJSONObject("start");
            if (s0 == null) return null;
            B b = common(c, m, ctx, K_PAUSE);
            RankCore.Me me = League.rankMe();
            b.started = ctx.started;
            int[] ov = ctx.ov;
            b.roundsMe = ov != null && ov.length > Emu.OVS_W2 && ov[Emu.OVS_W1] >= 0 && ov[Emu.OVS_W1] <= 3 ? ov[Emu.OVS_W1] : -1;
            b.roundsOpp = ov != null && ov.length > Emu.OVS_W2 && ov[Emu.OVS_W2] >= 0 && ov[Emu.OVS_W2] <= 3 ? ov[Emu.OVS_W2] : -1;
            if (b.roundsMe < 0 || b.roundsOpp < 0) { b.roundsMe = -1; b.roundsOpp = -1; }
            String oid = m.optString("opp");
            int[] h = League.h2h(oid);
            b.h2hW = h[0]; b.h2hL = h[1]; b.h2hD = h[2];
            RankCore.Player rp = League.rankPlayer(oid);
            boolean legacy = rp != null && rp.legacy || m.optJSONObject("opp_snap") != null && m.optJSONObject("opp_snap").optBoolean("legacy");
            b.oppTag = firstTag(h, legacy);
            b.oppSwatch = oppSwatch(m, ctx.look);
            b.stakes = RankCore.stakes(me, s0.optDouble("opp_mmr", me.mmr));
            b.mode = s0.optInt("placement_left", me.placementLeft) > 0 ? M_PLACEMENT : s0.optBoolean("master") ? M_MASTER : M_LP;
            b.lpBefore = b.lpAfter = me.lp; b.mrBefore = b.mrAfter = me.mr;
            b.tierBefore = b.tierAfter = me.master ? 7 : RankCore.tierOf(me.lp);
            b.starBefore = b.starAfter = me.master ? 0 : RankCore.starOf(me.lp);
            b.placementLeft = me.placementLeft;
            b.streakAfter = me.streak;
            b.w1 = -1; b.w2 = -1;
            if (!b.started) b.forfeit = new Forfeit(Forfeit.FP_VOID, 0, 0, "무효로 닫아요 · LP 그대로");
            else {
                League.ForfeitPreview fp = League.forfeitPreview(m);
                String broke = fp.brokeStreak >= 2 ? " · 연승 끊김" : "";
                switch (fp.kind) {
                    case League.ForfeitPreview.PLACEMENT: b.forfeit = new Forfeit(Forfeit.FP_PLACEMENT, 0, fp.brokeStreak, "배치 1패로 기록" + broke); break;
                    case League.ForfeitPreview.NOVICE: b.forfeit = new Forfeit(Forfeit.FP_NOVICE, 0, fp.brokeStreak, "패배로 기록 · LP 그대로 · 수련생 보호"); break;
                    case League.ForfeitPreview.MASTER: b.forfeit = new Forfeit(Forfeit.FP_LP, 0, fp.brokeStreak, "패배로 기록 · MR " + signed(fp.mrDelta) + broke); break;
                    default: {
                        /* 실제 정산(settle) 그대로 — 바닥에 걸리면 덜 잃고, 방패가 이미 쓰였으면 강등까지 */
                        String ev = fp.demoted && fp.tierAfter >= 0 && fp.tierAfter < RankCore.TIER.length ? " · " + ro(RankCore.TIER[fp.tierAfter]) + " 강등"
                                : fp.shieldUsed ? " · 강등 위기" : "";
                        String base = "패배로 기록 · " + signed(fp.lpDelta) + " LP" + ev;
                        b.forfeit = new Forfeit(Forfeit.FP_LP, Math.min(0, fp.lpDelta), fp.brokeStreak, base + broke, base);
                        break;
                    }
                }
            }
            int[] rows = b.started ? new int[]{ ROW_RESUME, ROW_DRAWER, ROW_LATER, ROW_FORFEIT } : new int[]{ ROW_RESUME, ROW_DRAWER, ROW_VOIDQUIT };
            b.rows = rows;
            b.rowOn = new boolean[rows.length]; b.rowDesc = new String[rows.length]; b.rowRight = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                b.rowOn[i] = true;
                switch (rows[i]) {
                    case ROW_RESUME: b.rowDesc[i] = "멈춘 곳에서 바로 이어서"; break;
                    case ROW_DRAWER: b.rowDesc[i] = "순위·전적·리그 보기"; break;
                    case ROW_LATER: b.rowDesc[i] = "저장하고 타이틀로 · A 로 이어서"; b.rowRight[i] = "저장"; break;
                    case ROW_FORFEIT:
                        b.rowDesc[i] = "패배로 기록돼요";
                        b.rowRight[i] = b.forfeit.kind == Forfeit.FP_PLACEMENT ? "배치" : b.forfeit.kind == Forfeit.FP_NOVICE ? "±0"
                                : b.mode == M_MASTER ? null : signed(b.forfeit.lpDelta);
                        break;
                    default: b.rowDesc[i] = "무효로 닫아요 · LP 그대로"; break;
                }
            }
            b.defaultRow = 0;
            b.ribbons = new int[0]; b.tags = new Tag[0];
            b.rematchPlayed = League.rematchCount(oid);
            return new MenuModel(b);
        } catch (Exception e) {
            android.util.Log.e("PocketCore", "MenuModel.pause", e);
            return null;
        }
    }

    /** 168 §2 대기(157-B — 리그 타이틀) 사본. open = 열린 경기(없으면 null — «준비»는 넘기지 않음) · voidKind = League.voidKind(그 경기)
     *  줄: 상대 찾기(또는 멈춘 경기 이어서) · 순위·전적·리그 · 내 캐릭터 바꾸기 · (멈춘 경기) 기권 · (앱 탓) 무효로 닫기 · 리그 나가기.
     *  못 쓰는 줄은 아예 안 넣는다(흐린 줄·이유 글 없음) */
    static MenuModel lobby(android.content.Context c, Ctx ctx, JSONObject open, String voidKind) {
        try {
            if (ctx == null) return null;
            if (open != null && "ready".equals(open.optString("status"))) open = null;
            B b = new B();
            b.kind = K_LOBBY;
            b.matchId = open != null ? open.optString("id") : "";
            b.prefs = ctx.prefs != null ? ctx.prefs : Prefs.defaults();
            b.stageSel = ctx.stageSel;
            RankCore.Me me = League.rankMe();
            League.Main mn = League.main();
            boolean plc = me.placementLeft > 0, master = me.master;
            int myCh = mn != null && mn.cell != 15 ? mn.ch : -1, mySty = mn != null && mn.cell != 15 ? mn.sty : -1;
            b.me = new Side(League.myNick(), mySty >= 0 && mySty <= 1 ? League.STYLE[mySty] : null, plc ? 0 : master ? 7 : RankCore.tierOf(me.lp), plc || master ? 0 : RankCore.starOf(me.lp),
                    plc ? 0 : master ? me.mr : me.lp, master, plc, plc ? 5 - me.placementLeft : 0, myCh, mySty);
            if (open != null) {
                JSONObject snap = open.optJSONObject("opp_snap"); if (snap == null) snap = new JSONObject();
                JSONObject rule = open.optJSONObject("rule");
                int olp = snap.optInt("lp"), omr = snap.optInt("mr");
                boolean om = olp >= RankCore.MASTER_LP || omr > 0;
                String on = League.oppNick(open);
                b.opp = new Side(on == null || on.isEmpty() ? "?" : on, null, om ? 7 : RankCore.tierOf(olp), om ? 0 : RankCore.starOf(olp), om ? omr : olp, om, false, 0,
                        rule != null ? rule.optInt("char", -1) : -1, rule != null ? rule.optInt("style", -1) : -1);
            } else b.opp = new Side("", null, 0, 0, 0, false, false, 0, -1, -1);
            League.Today t = League.rankedToday();
            b.todayW = t.w; b.todayL = t.l; b.todayD = t.d; b.todayLp = t.lpSum;
            b.bonusLeft = League.bonusLeft();
            League.Session ss = League.session();
            if (ss != null && RankCore.today(ss.start) == League.today()) { b.sessW = ss.w; b.sessL = ss.l; b.sessD = ss.d; b.sessLp = ss.lp; }
            else b.sessW = -1;
            b.total = RankCore.total();
            b.rankNow = !plc && ctx.board != null && ctx.board.rank > 0 ? ctx.board.rank : -1;
            b.rankBefore = -1;
            b.record = record(me);
            b.mode = plc ? M_PLACEMENT : master ? M_MASTER : M_LP;
            b.lpBefore = b.lpAfter = me.lp; b.mrBefore = b.mrAfter = me.mr;
            b.tierBefore = b.tierAfter = master ? 7 : RankCore.tierOf(me.lp);
            b.starBefore = b.starAfter = master ? 0 : RankCore.starOf(me.lp);
            b.placementLeft = me.placementLeft;
            b.streakAfter = me.streak;
            b.started = open != null;
            if (open != null && League.isRanked(open)) {
                League.ForfeitPreview fp = League.forfeitPreview(open);
                switch (fp.kind) {
                    case League.ForfeitPreview.PLACEMENT: b.forfeit = new Forfeit(Forfeit.FP_PLACEMENT, 0, fp.brokeStreak, "배치 1패로 기록"); break;
                    case League.ForfeitPreview.NOVICE: b.forfeit = new Forfeit(Forfeit.FP_NOVICE, 0, fp.brokeStreak, "패배로 기록 · LP 그대로"); break;
                    case League.ForfeitPreview.MASTER: b.forfeit = new Forfeit(Forfeit.FP_LP, 0, fp.brokeStreak, "패배로 기록 · MR " + signed(fp.mrDelta)); break;
                    default: b.forfeit = new Forfeit(Forfeit.FP_LP, Math.min(0, fp.lpDelta), fp.brokeStreak, "패배로 기록 · " + signed(fp.lpDelta) + " LP"); break;
                }
            } else if (open != null) b.forfeit = new Forfeit(Forfeit.FP_LP, 0, 0, "패배로 기록");
            java.util.List<Integer> rl = new java.util.ArrayList<>();
            rl.add(open != null ? ROW_RESUME_OPEN : ROW_SEARCH);
            rl.add(ROW_DRAWER);
            rl.add(ROW_MAIN);
            if (open != null) rl.add(ROW_OPEN_FORFEIT);
            if (open != null && voidKind != null) rl.add(ROW_OPEN_VOID);
            rl.add(ROW_LEAVE);
            int[] rows = new int[rl.size()];
            for (int i = 0; i < rows.length; i++) rows[i] = rl.get(i);
            b.rows = rows;
            b.rowOn = new boolean[rows.length]; b.rowDesc = new String[rows.length]; b.rowRight = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                b.rowOn[i] = true;
                switch (rows[i]) {
                    case ROW_SEARCH: b.rowDesc[i] = mn == null ? "내 캐릭터부터 고르고 상대 찾기" : "상대를 찾아 바로 한 판"; break;
                    case ROW_RESUME_OPEN: b.rowDesc[i] = "vs " + b.opp.nick + " · 멈춘 자리부터"; break;
                    case ROW_DRAWER: b.rowDesc[i] = "순위·전적·리그 보기"; break;
                    case ROW_MAIN: b.rowDesc[i] = mn != null ? League.mainLabel(mn, -1) + " → 다시 고르기" : "내 캐릭터를 골라 둬요"; break;
                    case ROW_OPEN_FORFEIT:
                        b.rowDesc[i] = b.forfeit != null ? b.forfeit.line : "패배로 기록돼요";
                        b.rowRight[i] = b.forfeit == null ? null : b.forfeit.kind == Forfeit.FP_PLACEMENT ? "배치" : b.forfeit.kind == Forfeit.FP_NOVICE ? "±0"
                                : b.mode == M_MASTER ? null : signed(b.forfeit.lpDelta);
                        break;
                    case ROW_OPEN_VOID: b.rowDesc[i] = "LP·전적 그대로 닫아요"; break;
                    default: b.rowDesc[i] = "내 게임 · 게임 목록으로"; break;
                }
            }
            b.defaultRow = 0;
            b.ribbons = new int[0]; b.tags = new Tag[0];
            b.w1 = -1; b.w2 = -1;
            return new MenuModel(b);
        } catch (Exception e) {
            android.util.Log.e("PocketCore", "MenuModel.lobby", e);
            return null;
        }
    }

    /** 결판·쉼 공통 — 두 사람 · 오늘·세션 · 순위 · 전적 요약 */
    private static B common(android.content.Context c, JSONObject m, Ctx ctx, int kind) {
        B b = new B();
        b.kind = kind;
        b.matchId = m.optString("id");
        b.prefs = ctx.prefs != null ? ctx.prefs : Prefs.defaults();
        b.stageSel = ctx.stageSel;
        RankCore.Me me = League.rankMe();
        boolean terminal = League.terminal(m.optString("status"));
        /* 나 — 결판은 판 뒤 값(LP 숫자는 LgmPages 가 lpBefore → lpAfter 로 올린다) */
        League.Main mn = League.main();
        JSONObject res = m.optJSONObject("result");
        int myCh = -1, mySty = -1;
        if (res != null && res.has("my_char")) { myCh = res.optInt("my_char", -1); mySty = res.optInt("my_style", -1); }
        else if (mn != null && mn.cell != 15) { myCh = mn.ch; mySty = mn.sty; }
        if (myCh < 0 || myCh > 14) { myCh = -1; mySty = -1; }
        String sty = ctx.rankAuto == 2 && mySty >= 0 && mySty <= 1 ? League.STYLE[mySty] : null;
        boolean master = me.master;
        int lp = master ? me.mr : me.lp;
        boolean plc = me.placementLeft > 0;
        b.me = new Side(League.myNick(), sty, plc ? 0 : master ? 7 : RankCore.tierOf(me.lp), plc || master ? 0 : RankCore.starOf(me.lp), plc ? 0 : lp,
                master, plc, plc ? 5 - me.placementLeft : 0, myCh, mySty);
        /* 상대 — 경기에 적힌 모습 사본 */
        JSONObject snap = m.optJSONObject("opp_snap");
        if (snap == null) snap = new JSONObject();
        JSONObject rule = m.optJSONObject("rule");
        int olp = snap.optInt("lp"), omr = snap.optInt("mr");
        boolean om = olp >= RankCore.MASTER_LP || omr > 0;
        String onick = snap.optString("nick", "");
        if (onick.isEmpty()) onick = "?";
        b.opp = new Side(onick, null, om ? 7 : RankCore.tierOf(olp), om ? 0 : RankCore.starOf(olp), om ? omr : olp, om, false, 0,
                rule != null ? rule.optInt("char", -1) : -1, rule != null ? rule.optInt("style", -1) : -1);
        /* 오늘 · 세션 */
        League.Today t = League.rankedToday();
        b.todayW = t.w; b.todayL = t.l; b.todayD = t.d; b.todayLp = t.lpSum;
        b.bonusLeft = League.bonusLeft();
        League.Session s = League.session();
        if (s != null && RankCore.today(s.start) == League.today()) { b.sessW = s.w; b.sessL = s.l; b.sessD = s.d; b.sessLp = s.lp; }   /* «오늘 …» 이라 오늘 켠 세션만 */
        else b.sessW = -1;
        /* 순위 */
        b.total = RankCore.total();
        b.rankNow = !plc && ctx.board != null && ctx.board.rank > 0 ? ctx.board.rank : -1;
        long before = ctx.boardBefore != null && ctx.boardBefore.rank > 0 ? ctx.boardBefore.rank : s != null && s.rank0 > 0 ? s.rank0 : -1;
        b.rankBefore = plc ? -1 : before;
        b.record = record(me);
        if (!terminal) b.outcome = 0;
        return b;
    }

    /** 157 post — 경기 중 HUD 이름표의 두 사람(경기 시작 모습): 나 = 지금 내 랭크(배치·무쌍 포함) · 상대 = 경기에 적힌 사본(opp_snap). 랭크 경기가 아니면 null */
    static Side[] tagSides(JSONObject m) {
        try {
            if (m == null || !League.isRanked(m)) return null;
            RankCore.Me me = League.rankMe();
            boolean plc = me.placementLeft > 0, master = me.master;
            Side a = new Side(League.myNick(), null, plc ? 0 : master ? 7 : RankCore.tierOf(me.lp), plc || master ? 0 : RankCore.starOf(me.lp),
                    plc ? 0 : master ? me.mr : me.lp, master, plc, plc ? 5 - me.placementLeft : 0, -1, -1);
            JSONObject snap = m.optJSONObject("opp_snap");
            if (snap == null) return null;
            int olp = snap.optInt("lp"), omr = snap.optInt("mr");
            boolean om = olp >= RankCore.MASTER_LP || omr > 0;
            String onick = snap.optString("nick", "");
            if (onick.isEmpty()) onick = "?";
            JSONObject rule = m.optJSONObject("rule");
            Side b = new Side(onick, null, om ? 7 : RankCore.tierOf(olp), om ? 0 : RankCore.starOf(olp), om ? omr : olp, om, false, 0,
                    rule != null ? rule.optInt("char", -1) : -1, rule != null ? rule.optInt("style", -1) : -1);
            return new Side[]{ a, b };
        } catch (Exception e) { return null; }
    }

    /** 서랍 «전적» 요약 */
    static Record record(RankCore.Me me) {
        Record r = new Record();
        r.season = League.season();
        r.w = me.w; r.l = me.l; r.d = me.d; r.streak = me.streak; r.best = me.best;
        int g = me.w + me.l + me.d;
        r.winPct = g > 0 ? (int) Math.round(100.0 * me.w / g) : -1;
        r.lp = me.lp; r.master = me.master; r.mr = me.mr; r.placement = me.placementLeft > 0; r.placementPlayed = 5 - me.placementLeft;
        java.util.List<JSONObject> h = League.rankedHistory(40);
        StringBuilder sb = new StringBuilder();
        java.util.ArrayList<Integer> trail = new java.util.ArrayList<>();
        for (JSONObject m : h) {
            String o = "forfeit".equals(m.optString("status")) || m.optBoolean("forfeit") ? "L" : m.optString("outcome");
            if (sb.length() < 10) sb.append("W".equals(o) ? 'W' : "L".equals(o) ? 'L' : 'D');
            if (trail.size() < 20 && !"배치".equals(m.optString("tier_before")) && m.has("lp_after")) trail.add(m.optInt("lp_after"));
        }
        r.last10 = sb.toString().toCharArray();
        r.lpTrail = new int[trail.size()];
        for (int i = 0; i < trail.size(); i++) r.lpTrail[i] = trail.get(trail.size() - 1 - i);
        League.Today t = League.rankedToday();
        r.todayW = t.w; r.todayL = t.l; r.todayD = t.d; r.todayLp = t.lpSum;
        r.firstWinTaken = me.placementLeft == 0 && !me.master && me.lastWinDay == League.today();
        int[] cw = League.careerWLD();
        r.careerW = cw[0]; r.careerL = cw[1]; r.careerD = cw[2];
        int peak = me.peakLp; String pl = peak > 0 ? RankCore.label(peak) : null;
        for (JSONObject x : League.seasons()) if (x.optInt("peak_lp") > peak && x.optString("peak_label", "").length() > 0) { peak = x.optInt("peak_lp"); pl = x.optString("peak_label"); }
        if (me.master) pl = RankCore.TIER[7];
        r.peakLabel = pl != null ? pl.replace(" ★", "★") : null;
        r.cheatGames = League.cheatGames();
        League.RivalLine rv = League.rivalLine();
        if (rv != null) {
            r.rivalNick = rv.nick; r.rivalW = rv.w; r.rivalL = rv.l;
            r.rivalTag = rv.last == -1 ? "설욕 대기" : rv.broke > 0 ? "연승 끊은 상대" : null;
        }
        return r;
    }

    /** RankCore 꼬리표 순서(라이벌 · 설욕전 · 천적 · 내 연승을 끊은 상대 · 첫 대전) 첫째 — 없으면 null */
    static String firstTag(int[] h, boolean legacy) {
        if (legacy || h[0] + h[1] >= 4 && Math.abs(h[0] - h[1]) <= 1) return "라이벌";
        if (h[3] == -1) return "설욕전";
        if (h[1] >= 3 && h[1] >= 2 * h[0]) return "천적";
        if (h[4] > 0) return "내 연승을 끊은 상대";
        if (h[0] + h[1] + h[2] == 0 && !legacy) return "첫 대전";
        return null;
    }
    /** 막대 — lp 의 ★ 칸 {바닥, 다음 ★(5★면 승급 LP)} · 무쌍 {9000, 9000} */
    static int[] starBand(int lp) {
        int t = RankCore.tierOf(lp);
        if (t >= 7) return new int[]{ RankCore.MASTER_LP, RankCore.MASTER_LP };
        int s = RankCore.starOf(lp);
        int lo = RankCore.FLOOR[t] + (s - 1) * RankCore.STEP[t];
        int hi = s >= 5 ? RankCore.FLOOR[t + 1] : lo + RankCore.STEP[t];
        return new int[]{ lo, hi };
    }
    static String signed(int v) { return v > 0 ? "+" + v : v < 0 ? "-" + (-v) : "±0"; }
    /** «낭인으로» · «무사로» — 받침 없음·ㄹ 받침이면 «로» */
    static String ro(String w) {
        if (w == null || w.isEmpty()) return "";
        char c = w.charAt(w.length() - 1);
        boolean bat = c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 != 0 && (c - 0xAC00) % 28 != 8;
        return w + (bat ? "으로" : "로");
    }
    /** «오늘 3승 1패 2무» — 결판(그만할까요? r16)·쉼 아래 띠·서랍 전적이 같은 숫자(rankedToday)·같은 글을 쓴다 */
    String todayLine() { return "오늘 " + todayW + "승 " + todayL + "패" + (todayD > 0 ? " " + todayD + "무" : ""); }
    static int indexOf(int[] a, int v) { for (int i = 0; i < a.length; i++) if (a[i] == v) return i; return -1; }
    /** 이 줄 번호의 칸(없으면 −1) */
    int rowIndex(int rowId) { return rows == null ? -1 : indexOf(rows, rowId); }

    /* ────────────────────────────────────────────────────────────────────── 안쪽 */

    /** 만드는 동안만 쓰는 고칠 수 있는 판 — 다 채운 뒤 MenuModel(B) 로 얼린다 */
    private static final class B {
        int kind; String matchId; Side me, opp; Prefs prefs;
        int todayW, todayL, todayD, todayLp; boolean bonusLeft;
        int sessW, sessL, sessD, sessLp;
        long rankNow = -1, rankBefore = -1, total; Record record;
        int outcome, mode, w1 = -1, w2 = -1;
        int lpBefore, lpAfter, lpDelta, mrBefore, mrAfter;
        int oppBefore, oppAfter; boolean oppMasterBefore, oppMasterAfter;
        int tierBefore, tierAfter, starBefore, starAfter;
        int starFloorBefore, starNextBefore, starFloorAfter, starNextAfter;
        int placementLeft;
        boolean promoted, demoted, enteredMaster, placementDone, starUp, shieldUsed, shieldActive, noviceProtect;
        int bonusStreak, bonusRevenge, bonusDaily, streakAfter, brokeStreak;
        int h2hW, h2hL, h2hD;
        String event; int[] ribbons = new int[0]; Tag[] tags = new Tag[0]; boolean eventful;
        int[] rows = new int[0]; boolean[] rowOn = new boolean[0]; String[] rowDesc = new String[0]; String[] rowRight = new String[0];
        int defaultRow, rematchPlayed, stageSel;
        boolean started; int roundsMe = -1, roundsOpp = -1; String oppTag; int[] stakes; Forfeit forfeit; int[] oppSwatch;
        B() { }
        B(MenuModel x) {
            kind = x.kind; matchId = x.matchId; me = x.me; opp = x.opp; prefs = x.prefs;
            todayW = x.todayW; todayL = x.todayL; todayD = x.todayD; todayLp = x.todayLp; bonusLeft = x.bonusLeft;
            sessW = x.sessW; sessL = x.sessL; sessD = x.sessD; sessLp = x.sessLp;
            rankNow = x.rankNow; rankBefore = x.rankBefore; total = x.total; record = x.record;
            outcome = x.outcome; mode = x.mode; w1 = x.w1; w2 = x.w2;
            lpBefore = x.lpBefore; lpAfter = x.lpAfter; lpDelta = x.lpDelta; mrBefore = x.mrBefore; mrAfter = x.mrAfter;
            oppBefore = x.oppBefore; oppAfter = x.oppAfter; oppMasterBefore = x.oppMasterBefore; oppMasterAfter = x.oppMasterAfter;
            tierBefore = x.tierBefore; tierAfter = x.tierAfter; starBefore = x.starBefore; starAfter = x.starAfter;
            starFloorBefore = x.starFloorBefore; starNextBefore = x.starNextBefore; starFloorAfter = x.starFloorAfter; starNextAfter = x.starNextAfter;
            placementLeft = x.placementLeft;
            promoted = x.promoted; demoted = x.demoted; enteredMaster = x.enteredMaster; placementDone = x.placementDone; starUp = x.starUp;
            shieldUsed = x.shieldUsed; shieldActive = x.shieldActive; noviceProtect = x.noviceProtect;
            bonusStreak = x.bonusStreak; bonusRevenge = x.bonusRevenge; bonusDaily = x.bonusDaily; streakAfter = x.streakAfter; brokeStreak = x.brokeStreak;
            h2hW = x.h2hW; h2hL = x.h2hL; h2hD = x.h2hD;
            event = x.event; ribbons = x.ribbons; tags = x.tags; eventful = x.eventful;
            rows = x.rows; rowOn = x.rowOn; rowDesc = x.rowDesc; rowRight = x.rowRight;
            defaultRow = x.defaultRow; rematchPlayed = x.rematchPlayed; stageSel = x.stageSel;
            started = x.started; roundsMe = x.roundsMe; roundsOpp = x.roundsOpp; oppTag = x.oppTag; stakes = x.stakes; forfeit = x.forfeit; oppSwatch = x.oppSwatch;
        }
    }

    private MenuModel(B b) {
        kind = b.kind; matchId = b.matchId; me = b.me; opp = b.opp; prefs = b.prefs;
        todayW = b.todayW; todayL = b.todayL; todayD = b.todayD; todayLp = b.todayLp; bonusLeft = b.bonusLeft;
        sessW = b.sessW; sessL = b.sessL; sessD = b.sessD; sessLp = b.sessLp;
        rankNow = b.rankNow; rankBefore = b.rankBefore; total = b.total; record = b.record;
        outcome = b.outcome; mode = b.mode; w1 = b.w1; w2 = b.w2;
        lpBefore = b.lpBefore; lpAfter = b.lpAfter; lpDelta = b.lpDelta; mrBefore = b.mrBefore; mrAfter = b.mrAfter;
        oppBefore = b.oppBefore; oppAfter = b.oppAfter; oppMasterBefore = b.oppMasterBefore; oppMasterAfter = b.oppMasterAfter;
        tierBefore = b.tierBefore; tierAfter = b.tierAfter; starBefore = b.starBefore; starAfter = b.starAfter;
        starFloorBefore = b.starFloorBefore; starNextBefore = b.starNextBefore; starFloorAfter = b.starFloorAfter; starNextAfter = b.starNextAfter;
        placementLeft = b.placementLeft;
        promoted = b.promoted; demoted = b.demoted; enteredMaster = b.enteredMaster; placementDone = b.placementDone; starUp = b.starUp;
        shieldUsed = b.shieldUsed; shieldActive = b.shieldActive; noviceProtect = b.noviceProtect;
        bonusStreak = b.bonusStreak; bonusRevenge = b.bonusRevenge; bonusDaily = b.bonusDaily; streakAfter = b.streakAfter; brokeStreak = b.brokeStreak;
        h2hW = b.h2hW; h2hL = b.h2hL; h2hD = b.h2hD;
        event = b.event; ribbons = b.ribbons.clone(); tags = b.tags.clone(); eventful = b.eventful;
        rows = b.rows.clone(); rowOn = b.rowOn.clone(); rowDesc = b.rowDesc.clone(); rowRight = b.rowRight.clone();
        defaultRow = b.defaultRow; rematchPlayed = b.rematchPlayed; stageSel = b.stageSel;
        started = b.started; roundsMe = b.roundsMe; roundsOpp = b.roundsOpp; oppTag = b.oppTag; stakes = b.stakes != null ? b.stakes.clone() : null; forfeit = b.forfeit;
        oppSwatch = b.oppSwatch != null ? b.oppSwatch.clone() : null;
    }
}
