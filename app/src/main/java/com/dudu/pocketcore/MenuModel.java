package com.dudu.pocketcore;

/**
 * 157 리그 인게임 메뉴의 «불변 사본» — UI 스레드에서 League·RankCore 를 읽어 한 번 만들고, 그 뒤로는 아무도 고치지 않는다.
 * GL 스레드(LeagueMenu)와 그림 스레드(LgmPages)는 이것만 읽는다(League 를 직접 읽지 않음).
 * 계약서 docs/157_contract.md §8. 담당: 메뉴 담당(menu builder). 설계 단계 껍데기 — 칸 목록이 계약, 만드는 몸통은 TODO.
 *
 * 157-B 이후를 위한 자리: 내·상대 캐릭터 id(Side.ch/sty)는 늘 채운다 — 나중에 «캐릭터 얼굴 타일 묶음»(롬 밖 우리 그림 또는 별도 조사)을
 * LgmPages 가 선택적으로 얹을 수 있게(FaceProvider, 157-A 는 늘 null).
 */
final class MenuModel {
    /* 종류 */
    static final int K_RESULT = 1, K_PAUSE = 2;                 // 157-B: K_LOBBY = 3
    /* 결과 */
    static final int O_WIN = 1, O_LOSS = 2, O_DRAW = 3, O_FORFEIT = 4, O_VOID = 5;   // VOID = 보류(hold)·무효(cancelled)
    /* 경기 시작 때의 사다리 */
    static final int M_LP = 0, M_PLACEMENT = 1, M_MASTER = 2;
    /* 사건 — 호명 띠(최대 2) · 아래 띠 도장 */
    static final int E_PROMOTE = 1, E_DEMOTE = 2, E_MASTER = 3, E_STAR_UP = 4, E_REVENGE = 5, E_RIVAL = 6, E_STREAK = 7,
            E_FIRST_WIN = 8, E_PLACED = 9, E_RISK = 10, E_BROKE = 11, E_PROTECT = 12;
    /** 호명 띠 우선순위(앞이 먼저) — 기획서 §5 «승급·강등 > 설욕·라이벌 > 3연승 이상 > 오늘 첫 승 > 연승 끊음 > 배치 완료» 에
     *  무쌍 달성(승급과 같은 급)·★ 올라감(목업 «★4 올라감!»)을 끼움. E_RISK·E_BROKE·E_PROTECT 는 띠 없이 머리·도장으로만 */
    static final int[] RIBBON_ORDER = { E_PROMOTE, E_MASTER, E_DEMOTE, E_STAR_UP, E_REVENGE, E_RIVAL, E_STREAK, E_FIRST_WIN, E_PLACED };
    /* 칼집 줄 */
    static final int ROW_NEXT = 1, ROW_REMATCH = 2, ROW_NEWCHAR = 3, ROW_DRAWER = 4, ROW_QUIT = 5,
            ROW_RESUME = 6, ROW_LATER = 7, ROW_FORFEIT = 8, ROW_VOIDQUIT = 9;
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
        Forfeit(int kind, int lpDelta, int brokeStreak, String line) { this.kind = kind; this.lpDelta = lpDelta; this.brokeStreak = brokeStreak; this.line = line; }
    }
    /** 메뉴 설정(League pref.lgm) — 서랍 «설정» 탭 */
    static final class Prefs {
        static final int FX_NORMAL = 0, FX_SHORT = 1, FX_OFF = 2;            // 결판 연출 보통(기본) · 짧게 · 끄기(= 옛 Java 결과 카드)
        static final int LOSS_REVENGE = 0, LOSS_NEXT = 1;                    // 진 판 기본 커서 설욕전(기본) · 다음 상대
        static final int TAB_BOARD = 0, TAB_RECORD = 1, TAB_SETTINGS = 2;
        final int fx, lossCursor, autoNextSec, lastTab;                       // autoNextSec 0 끔(기본) · 10
        final boolean rememberTab;                                            // 기본 true
        Prefs(int fx, int lossCursor, int autoNextSec, boolean rememberTab, int lastTab) {
            this.fx = fx; this.lossCursor = lossCursor; this.autoNextSec = autoNextSec; this.rememberTab = rememberTab; this.lastTab = lastTab;
        }
        static Prefs defaults() { return new Prefs(FX_NORMAL, LOSS_REVENGE, 0, true, TAB_BOARD); }
    }
    /** 서랍 «전적» 요약(이번 시즌 · 통산) */
    static final class Record {
        int season, w, l, d, streak, best, winPct;        // winPct = 0..100(판 0 이면 −1)
        char[] last10;                                     // 새것부터 'W' 'L' 'D'
        int todayW, todayL, todayD, todayLp; boolean firstWinTaken;
        int[] lpTrail;                                     // 최근 배치 아닌 판 lp_after 20개, 오래된 것부터(지금 값이 끝)
        int careerW, careerL, careerD; String peakLabel;  // 통산 · 최고 티어(«검호★1»)
        String rivalNick; int rivalW, rivalL; String rivalTag;   // ⛓ 줄(없으면 rivalNick null)
    }

    /* ── 공통 ── */
    final int kind;
    final String matchId;
    final Side me, opp;
    final Prefs prefs;
    final int todayW, todayL, todayD, todayLp; final boolean bonusLeft;   // rankedToday · bonusLeft
    final int sessW, sessL, sessD, sessLp;                               // G2 ranked.session
    final long rankNow, rankBefore, total;      // BoardCache — 모르면 −1(rankBefore = 이 판 전, 없으면 세션 시작 rank0)
    final Record record;                        // 서랍 전적(만들 때 채움 — 가벼움)

    /* ── 결판 ── */
    final int outcome, mode;                    // O_* · M_*
    final int w1, w2;                           // 최종 라운드 승수(기권·무효 −1)
    final int lpBefore, lpAfter, lpDelta, mrBefore, mrAfter;
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

    private MenuModel() { throw new UnsupportedOperationException("157-A MenuModel: 메뉴 담당이 채움(빌더로 만든다)"); }

    /** 만들 때 필요한 그 밖의 값(UI 스레드가 채움) */
    static final class Ctx {
        int rankAuto;                    // EmuActivity.rankAuto(2 = 저장한 내 캐릭터로 대신 고름 → «새 캐릭터» 줄 켬)
        int stageSel;                    // League.prefStage()
        int[] ov;                        // Emu.nativeOvStatus()(라운드 승수 — 없으면 null)
        boolean started;                 // 쉼: 첫 라운드를 봤나
        BoardCache.Board board, boardBefore;   // 지금 · 이 판 전(없으면 null)
        Prefs prefs;
    }
    /** 결판 사본 — m = 정산·기권·보류·무효로 끝난 랭크 경기 기록. 틀리면 null(옛 카드로) */
    static MenuModel result(android.content.Context c, org.json.JSONObject m, Ctx ctx) { throw new UnsupportedOperationException("157-A TODO"); }
    /** 쉼 사본 — m = 진행 중인 랭크 경기 기록 */
    static MenuModel pause(android.content.Context c, org.json.JSONObject m, Ctx ctx) { throw new UnsupportedOperationException("157-A TODO"); }
}
