package com.dudu.pocketcore;

/**
 * 157 순위표 캐시(G12) — LeagueActivity 안에 있던 boardKey 캐시·백그라운드 계산을 빼내 리그 화면과 게임 안(서랍·결판 «12,483위 ▲212»)이 같이 쓴다.
 * RankCore.top·rankOf·around 와 프로필·아바타 만들기는 무겁다(에뮬레이터 UI 스레드가 수십 초 멈춘 기록) — 늘 백그라운드 스레드 «league-board».
 * 계약서 docs/157_contract.md §9.
 *
 * 열쇠가 같으면 판도 같다(같은 씨앗·한국 날짜·내 LP/MR·판 수·모양) — 최근 4개를 기억한다(이 판 전·후 순위 비교, 리그 화면의 위 100 / 내 주변).
 * 만든 판(Board)과 그 줄(RankCore.Row)은 고치지 않는다. invalidate(새 시즌·처음부터·다시 읽기) 뒤 끝난 옛 계산은 버린다.
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
        /** 줄마다 프로필·아바타를 미리 데웠나 — 게임 안(서랍)은 안 데우고, 리그 화면은 데운 판만 «준비됨»으로 본다(아니면 UI 스레드가 100줄을 만든다) */
        volatile boolean avatars;
    }

    static final int KEEP = 4;
    private static final java.util.LinkedHashMap<String, Board> CACHE = new java.util.LinkedHashMap<String, Board>(8, 0.75f, true) {
        private static final long serialVersionUID = 1L;
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Board> e) { return size() > KEEP; }
    };
    /** 도는 계산 하나(열쇠마다 하나) — 끝나면 부를 것들 · 아바타까지 데울지(늦게 온 warm 이 켤 수 있음) */
    private static final class Job { final java.util.List<Runnable> waiters = new java.util.ArrayList<>(); boolean avatars; }
    private static final java.util.HashMap<String, Job> BUSY = new java.util.HashMap<>();
    private static int gen = 0;
    /** 메인 스레드로 보내기 — 앱은 Looper.getMainLooper() · JVM 시험은 바꿔 넣는다 */
    static volatile java.util.function.Consumer<Runnable> main = null;
    /** 시험용 — 계산을 바로 이 스레드에서(true) · 계산한 횟수 */
    static volatile boolean inline = false;
    static volatile int computes = 0;

    /** 열쇠 — 씨앗:한국 날짜:내 열쇠(LP·무쌍 MR):around:k:판 수(LeagueActivity 옛 boardKey 와 같은 요소 + k) */
    static String key(RankCore.Me me, boolean around, int k) {
        return League.rankSeed() + ":" + League.today() + ":" + League.myKey(me) + ":" + around + ":" + k + ":" + me.games;
    }
    /** 캐시에 있으면 그것(없으면 null) — 아무 스레드. 최근 4개 판을 기억(이 판 전·후 순위 비교용) */
    static Board get(RankCore.Me me, boolean around, int k) { return get(me, around, k, false); }
    /** needAvatars = 줄마다 프로필·아바타까지 데운 판만(리그 화면 순위표 — UI 스레드에서 100줄을 만들지 않게). 아니면 null */
    static Board get(RankCore.Me me, boolean around, int k, boolean needAvatars) {
        if (me == null) return null;
        String key = key(me, around, k);
        Board b;
        synchronized (CACHE) { b = CACHE.get(key); }
        return b != null && (!needAvatars || b.avatars) ? b : null;
    }
    /** 백그라운드로 만들기(같은 열쇠가 도는 중이면 하나만) — 끝나면 onReady 를 메인 스레드에(Handler) · avatars = 줄마다 프로필·아바타도 미리.
     *  이미 있으면 계산 없이 onReady 만 보낸다 — 아바타를 원하는데 아바타 없이 만든 판이면(게임 안 서랍이 먼저 데움) 아바타만 마저 데운 뒤 */
    static void warm(final android.content.Context c, final RankCore.Me me, final boolean around, final int k, final boolean avatars, final Runnable onReady) {
        if (me == null) return;
        final String key = key(me, around, k);
        final int g;
        final Job job;
        synchronized (CACHE) {
            Board have = CACHE.get(key);
            if (have != null && (!avatars || have.avatars)) { if (onReady != null) post(onReady); return; }
            Job running = BUSY.get(key);
            if (running != null) { running.avatars |= avatars; if (onReady != null) running.waiters.add(onReady); return; }
            job = new Job();
            job.avatars = avatars;
            if (onReady != null) job.waiters.add(onReady);
            BUSY.put(key, job);
            g = gen;
        }
        Runnable run = () -> {
            Board b;
            synchronized (CACHE) { b = CACHE.get(key); }
            if (b == null) b = compute(c, me, around, k, false, key);
            java.util.List<Runnable> w;
            while (true) {
                boolean av;
                synchronized (CACHE) {
                    /* 그 사이 버렸다(새 시즌·다시 읽기) — 새 계산(같은 열쇠의 새 Job)은 건드리지 않는다: 그 대기 목록·BUSY 칸은 그것 몫 */
                    if (g != gen) { if (BUSY.get(key) == job) BUSY.remove(key); return; }
                    CACHE.put(key, b);                         /* 줄은 이미 쓸 수 있다(아바타를 마저 데우는 동안에도 get(…, false) 는 이 판) */
                    av = job.avatars && !b.avatars;
                    if (!av) {
                        if (BUSY.get(key) == job) BUSY.remove(key);
                        w = new java.util.ArrayList<>(job.waiters);
                        break;
                    }
                }
                warmAvatars(c, b);                              /* 늦게 온 warm 이 아바타를 켤 수 있어 다시 본다 */
            }
            for (Runnable r : w) post(r);
        };
        if (inline) run.run();
        else new Thread(run, "league-board").start();
    }
    /** 줄마다 프로필·아바타 미리(리그 화면 순위표) — 판은 그대로(아바타는 Avatar·League 캐시에 든다) */
    static int avatarPasses = 0;                                /* 시험용 */
    private static void warmAvatars(android.content.Context c, Board b) {
        avatarPasses++;
        try { for (RankCore.Row r : b.rows) if (!r.me && r.p != null) Avatar.of(League.pfOf(c, r.p), r.id); }
        catch (Exception ignored) { }
        b.avatars = true;
    }
    /** 그 열쇠가 계산 중인가 */
    static boolean busy(RankCore.Me me, boolean around, int k) {
        if (me == null) return false;
        String key = key(me, around, k);
        synchronized (CACHE) { return BUSY.containsKey(key); }
    }
    /** 새 시즌·처음부터 뒤 — 모두 버림(도는 계산의 결과도 버린다 — 기다리던 onReady 는 부르지 않음) */
    static void invalidate() {
        synchronized (CACHE) { CACHE.clear(); BUSY.clear(); gen++; }
    }

    /** 실제 계산 — LeagueActivity 옛 boardLoad 와 같은 함수(rankOf · around/top · 아바타) + 위 3명 */
    static Board compute(android.content.Context c, RankCore.Me me, boolean around, int k, boolean avatars, String key) {
        computes++;
        Board b = new Board();
        b.key = key; b.around = around; b.k = k; b.total = RankCore.total();
        try {
            long seed = League.rankSeed(); int day = League.today(); RankCore.Book bk = League.book();
            long mk = League.myKey(me);
            b.rank = RankCore.rankOf(seed, day, bk, mk);
            b.rows = RankCore.unmodifiable(around ? RankCore.around(seed, day, bk, mk, "나", k) : RankCore.top(seed, day, k, bk, mk, "나"));
            b.top3 = RankCore.unmodifiable(RankCore.top(seed, day, 3, bk, mk, "나"));
            if (avatars) warmAvatars(c, b);                    /* 프로필·아바타도 미리 */
        } catch (Exception e) {
            b.rows = RankCore.unmodifiable(new java.util.ArrayList<RankCore.Row>());
            if (b.top3 == null) b.top3 = RankCore.unmodifiable(new java.util.ArrayList<RankCore.Row>());
        }
        b.at = System.currentTimeMillis();
        return b;
    }

    private static void post(Runnable r) {
        java.util.function.Consumer<Runnable> m = main;
        if (m != null) { m.accept(r); return; }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
