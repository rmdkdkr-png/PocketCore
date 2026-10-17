package com.dudu.pocketcore;

/**
 * 157 순위표 캐시(G12) — LeagueActivity 안에 있던 boardKey 캐시·백그라운드 계산을 빼내 리그 화면과 게임 안(서랍·결판 «12,483위 ▲212»)이 같이 쓴다.
 * RankCore.top·rankOf·around 와 프로필·아바타 만들기는 무겁다(에뮬레이터 UI 스레드가 수십 초 멈춘 기록) — 늘 백그라운드 스레드 «league-board».
 * 계약서 docs/157_contract.md §9. 담당: 메뉴 담당(menu builder). 설계 단계 껍데기.
 */
final class BoardCache {
    private BoardCache() { }

    /** 한 번 계산한 판 — 만든 뒤 고치지 않는다(rows 의 Row 도 고치지 않음) */
    static final class Board {
        String key;
        long rank, total;                     // 내 순위 · 총원(RankCore.total)
        boolean around; int k;                // true = 내 주변 ±k · false = 위 k명
        java.util.List<RankCore.Row> rows;    // around: 위 k · 나 · 아래 k(순위순) / top: 1..k
        java.util.List<RankCore.Row> top3;    // 늘 1~3위(단상)
        long at;                              // 만든 시각(ms)
    }
    /** 열쇠 — 씨앗:한국 날짜:내 열쇠(LP·무쌍 MR):around:k:판 수(LeagueActivity 옛 boardKey 와 같은 요소 + k) */
    static String key(RankCore.Me me, boolean around, int k) { throw todo(); }
    /** 캐시에 있으면 그것(없으면 null) — 아무 스레드. 최근 4개 판을 기억(이 판 전·후 순위 비교용) */
    static Board get(RankCore.Me me, boolean around, int k) { throw todo(); }
    /** 백그라운드로 만들기(같은 열쇠가 도는 중이면 하나만) — 끝나면 onReady 를 메인 스레드에(Handler) · avatars = 줄마다 프로필·아바타도 미리 */
    static void warm(android.content.Context c, RankCore.Me me, boolean around, int k, boolean avatars, Runnable onReady) { throw todo(); }
    /** 그 열쇠가 계산 중인가 */
    static boolean busy(RankCore.Me me, boolean around, int k) { throw todo(); }
    /** 새 시즌·처음부터 뒤 — 모두 버림 */
    static void invalidate() { throw todo(); }

    private static UnsupportedOperationException todo() { return new UnsupportedOperationException("157-A BoardCache: 메뉴 담당이 채움"); }
}
