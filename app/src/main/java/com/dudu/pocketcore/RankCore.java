package com.dudu.pocketcore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 랭크 매치(끝없는 매칭) 순수 자바 핵심 — 안드로이드·JSON 없이 JVM 에서 시험한다(RankCoreTest).
 * League.java 가 league.json 의 "ranked" 칸과 이 클래스 사이를 옮긴다.
 *
 *  1) 상대 풀   — 씨앗(world seed) 하나로 4만 명 남짓을 «필요할 때» 만든다. id = 칸(MMR 10 폭) + 칸 안 번호. 같은 씨앗·같은 id = 늘 같은 사람.
 *  2) 세기 손잡이 — MMR → CPU 시작 단계·상한·바닥(0x5814·0x5815·0x5CF5)·공격 배율 0x57E2·받는 피해 배율 0x57E4.
 *                  게임 자신의 장별 배율 표(T = C0 CC D9 E6 F3 100 10C 119 · U = 140 133 126 119 10C 100 F3 E6)를 한 칸 = MMR 100 으로 펴고 양끝을 늘였다.
 *                  실측(E4·E5): 배율은 피해에 그대로 곱해지고 CPU 움직임은 안 바뀜 · 단계는 0→15 에서 CPU 가 주는 피해 +34% · 같은 봇이 사다리 한 칸마다 승률이 Elo 400 척도대로 떨어짐.
 *  3) 등급     — 숨은 MMR(Elo) + 보이는 LP·티어(수련생 … 검성 ★1~5, 무쌍 = MR).
 *  4) 매칭     — 내 MMR 근처, 최근 상대 빼기, 라이벌·설욕전 가끔, 연승·연패 기울기, 잡힌 상대는 붙을 때까지 남김(도망 막기).
 *  5) 순위표   — 위 100명 + 내 주변, 날마다 조금씩 바뀜.
 * 생성 규칙을 바꾸면 옛 id 가 다른 사람이 된다 — GEN 을 올리고 옛 판 코드는 그대로 둔다(경기 기록엔 상대 모습 사본도 남긴다).
 */
final class RankCore {
    private RankCore() { }
    static final int GEN = 1;

    /* ── 난수 (SplitMix64 — 플랫폼·자바 판과 무관하게 같은 값) ───────────────── */
    static final class Rng {
        private long s;
        Rng(long seed) { s = seed; }
        long next() { long z = (s += 0x9E3779B97F4A7C15L); z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L; z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL; return z ^ (z >>> 31); }
        int nextInt(int n) { return (int) Long.remainderUnsigned(next(), n); }
        double nextDouble() { return (next() >>> 11) * 0x1.0p-53; }
        double gauss() { double u = Math.max(1e-12, nextDouble()), v = nextDouble(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); }
        static long mix(long... xs) { long h = 0x243F6A8885A308D3L; for (long x : xs) { h ^= x; h = new Rng(h).next(); } return h; }
    }

    /* ── 게임 정보 ─────────────────────────────────────────────────────── */
    static final String[] CHAR = { "카즈키", "소게츠", "하오마루", "겐쥬로", "나코루루", "리무루루", "한조", "갈포드",
                                   "아수라", "샬럿", "모로즈미", "우쿄", "주베이", "시키", "유가" };
    static final String[] STYLE = { "수라", "나찰" };
    /** CPU 가 싸우는 24조합(무한 대전 뽑기 표 22칸 + 유가 2) = League.oppSupported 와 같다. 가중치 = 인기(주캐 고르는 비율) */
    static final int[][] MAINS = {   // ch, sty, 가중치
        { 2, 0, 9 }, { 2, 1, 6 }, { 11, 0, 8 }, { 11, 1, 5 }, { 4, 0, 9 }, { 3, 1, 7 }, { 3, 0, 5 }, { 7, 0, 5 }, { 7, 1, 4 },
        { 6, 0, 5 }, { 0, 1, 5 }, { 0, 0, 4 }, { 9, 0, 5 }, { 9, 1, 3 }, { 8, 0, 3 }, { 8, 1, 3 }, { 13, 1, 4 }, { 13, 0, 4 },
        { 5, 0, 5 }, { 1, 1, 3 }, { 10, 0, 3 }, { 12, 0, 3 }, { 14, 0, 1 }, { 14, 1, 1 } };
    static final int[] BAD_OPP = { (1 << 1) | 0, (4 << 1) | 1, (5 << 1) | 1, (6 << 1) | 1, (10 << 1) | 1, (12 << 1) | 1 };
    static boolean supported(int ch, int sty) {
        if (ch < 0 || ch > 14 || sty < 0 || sty > 1) return false;
        for (int b : BAD_OPP) if (b == ((ch << 1) | sty)) return false;
        return true;
    }

    /* ── 티어·LP ──────────────────────────────────────────────────────── */
    static final String[] TIER = { "수련생", "낭인", "무사", "검객", "검호", "달인", "검성", "무쌍" };
    /** 티어 바닥 LP 와 별 하나 폭(★1~5). 무쌍(7)은 9000 부터 — LP 대신 MR */
    static final int[] FLOOR = { 0, 500, 1250, 2250, 3500, 5000, 6750, 9000 };
    static final int[] STEP  = { 100, 150, 200, 250, 300, 350, 450, 0 };
    static final int MASTER_LP = 9000;
    static int tierOf(int lp) { for (int t = TIER.length - 1; t > 0; t--) if (lp >= FLOOR[t]) return t; return 0; }
    static int starOf(int lp) { int t = tierOf(lp); return t == 7 ? 0 : Math.min(5, 1 + (lp - FLOOR[t]) / STEP[t]); }
    static String label(int lp) { int t = tierOf(lp); return t == 7 ? TIER[7] : TIER[t] + " ★" + starOf(lp); }
    /** MMR ↔ LP 기준선(«이 실력이면 LP 가 여기쯤으로 수렴») — 조각 직선. 원래 난도 하(800) ≈ 낭인 ★5, 중(1000) = 검객 ★1, 상(1200) ≈ 검호 ★4 */
    static final double[] MM = { 500, 700, 850, 1000, 1150, 1300, 1450, 1600 };
    static final double[] LPK = { 0, 500, 1250, 2250, 3500, 5000, 6750, 9000 };
    static double lpOfMmr(double m) { return interp(MM, LPK, m); }
    static double mmrOfLp(double lp) { return interp(LPK, MM, lp); }
    private static double interp(double[] xs, double[] ys, double x) {
        int n = xs.length;
        if (x <= xs[0]) return ys[0] + (x - xs[0]) * (ys[1] - ys[0]) / (xs[1] - xs[0]);
        if (x >= xs[n - 1]) return ys[n - 1] + (x - xs[n - 1]) * (ys[n - 1] - ys[n - 2]) / (xs[n - 1] - xs[n - 2]);
        for (int i = 1; i < n; i++) if (x <= xs[i]) return ys[i - 1] + (x - xs[i - 1]) * (ys[i] - ys[i - 1]) / (xs[i] - xs[i - 1]);
        return ys[n - 1];
    }

    /* ── CPU 세기 손잡이 ─────────────────────────────────────────────────── */
    static final class Knobs {
        int diff, lv, cap, floor, atk, dfn;
        double strength() { return (atk / (double) dfn) * Math.exp(0.026 * floor); }   // 실측 근사: 피해 경주 배수 × 단계 효과
        @Override public String toString() { return String.format("diff%d lv%d cap%d floor%d atk%04X dfn%04X", diff, lv, cap, floor, atk, dfn); }
    }
    /** 기준점(MMR, 시작 단계, 상한, 공격 배율, 받는 피해 배율) — 800·1000·1200 = 게임 난도 하·중·상 첫 경기 값(0단계 실측). 나머지는 게임 장별 표를 한 칸 = 100 으로 */
    static final int[][] KNOB_ANCHOR = {
        { 500, 0, 1, 0x00A0, 0x0160 }, { 650, 0, 2, 0x00B0, 0x0150 }, { 800, 1, 3, 0x00C0, 0x0140 }, { 900, 2, 4, 0x00CC, 0x0133 },
        { 1000, 4, 6, 0x00D9, 0x0126 }, { 1100, 5, 7, 0x00E6, 0x0119 }, { 1200, 7, 9, 0x00F3, 0x010C }, { 1300, 8, 11, 0x0100, 0x0100 },
        { 1400, 10, 13, 0x010C, 0x00F3 }, { 1500, 11, 14, 0x0119, 0x00E6 }, { 1650, 13, 15, 0x0126, 0x00E0 }, { 1800, 15, 15, 0x0134, 0x00E0 } };
    /** 성향: 0 균형 · 1 공격(단계↑ 배율 조금↓) · 2 한방(주고받는 피해 둘 다↑) · 3 끈기(둘 다↓) */
    static final String[] PERSONA = { "균형", "공격형", "한방형", "끈기형" };
    static Knobs knobs(double mmr, int persona) {
        int[][] A = KNOB_ANCHOR; double m = Math.max(A[0][0], Math.min(A[A.length - 1][0], mmr));
        int i = 1; while (i < A.length - 1 && m > A[i][0]) i++;
        double f = (m - A[i - 1][0]) / (double) (A[i][0] - A[i - 1][0]);
        Knobs k = new Knobs();
        double lv = A[i - 1][1] + f * (A[i][1] - A[i - 1][1]), cap = A[i - 1][2] + f * (A[i][2] - A[i - 1][2]);
        double atk = A[i - 1][3] + f * (A[i][3] - A[i - 1][3]), dfn = A[i - 1][4] + f * (A[i][4] - A[i - 1][4]);
        if (persona == 1) { lv += 2; cap += 1; atk *= 0.95; dfn *= 1.05; }
        else if (persona == 2) { atk *= 1.08; dfn *= 1.08; }
        else if (persona == 3) { atk *= 0.93; dfn *= 0.93; }
        k.lv = clamp((int) Math.round(lv), 0, 15);
        k.cap = clamp((int) Math.round(cap), k.lv, 15);
        k.floor = Math.max(0, k.lv - 2);               // 실시간 단계 바닥 — 맞을수록 내려가는 게임 적응은 두되 너무 물러지지 않게
        k.atk = clamp((int) Math.round(atk), 0x00A0, 0x0134);
        k.dfn = clamp((int) Math.round(dfn), 0x00E0, 0x0160);
        k.diff = mmr < 900 ? 0 : mmr < 1100 ? 1 : 2;   // 옵션 난도(R_OPT) — 손잡이가 덮지만 0x57F5 사본 등 나머지가 가까운 난도와 같게
        return k;
    }
    static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

    /* ── 상대 풀 ──────────────────────────────────────────────────────── */
    static final int MMR_MIN = 400, BW = 10, NB = 180;      // 400~2200, 칸 180개
    static final double MU = 1050, SIGMA = 230;
    static final int N_TOTAL = 40000;
    private static final int[] COUNT = new int[NB];
    private static final long TOTAL;
    static {
        long t = 0;
        for (int b = 0; b < NB; b++) {
            double lo = MMR_MIN + b * BW, hi = lo + BW;
            COUNT[b] = (int) Math.round(N_TOTAL * (phi((hi - MU) / SIGMA) - phi((lo - MU) / SIGMA)));
            t += COUNT[b];
        }
        TOTAL = t;
    }
    static int bucketCount(int b) { return b >= 0 && b < NB ? COUNT[b] : 0; }
    static long total() { return TOTAL; }
    static int bucketOf(double m) { return clamp((int) Math.floor((m - MMR_MIN) / BW), 0, NB - 1); }
    static double phi(double z) {   // 표준 정규 누적(Abramowitz–Stegun 7.1.26, 오차 < 1.5e-7)
        double t = 1 / (1 + 0.3275911 * Math.abs(z) / Math.sqrt(2));
        double e = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-z * z / 2);
        return z >= 0 ? 0.5 * (1 + e) : 0.5 * (1 - e);
    }

    static final class Player {
        String id, nick, title, region, persona;
        int avatar, ch, sty, stage, personaIdx, games0, lpLag;
        double baseMmr, wr0;
        boolean legacy;                         // 예전 리그 12명(o01~o12) — 라이벌
        /** 지금 모습 — 날짜(흔들림)와 나와의 경기(조정)를 더한 값 */
        double mmr(int day, double adj) { return baseMmr + drift(day) + adj; }
        double drift(int day) {
            if (legacy) return 0;
            int wk = Math.floorDiv(day, 7); double fr = Math.floorMod(day, 7) / 7.0;
            double a = new Rng(Rng.mix(idHash(), wk)).nextDouble() * 2 - 1, b = new Rng(Rng.mix(idHash(), wk + 1)).nextDouble() * 2 - 1;
            return 45 * (a + (b - a) * fr);
        }
        long idHash() { long h = 1125899906842597L; for (int i = 0; i < id.length(); i++) h = 31 * h + id.charAt(i); return h; }
        int lp(int day, double adj, int lpAdj) { return Math.max(0, (int) Math.round(lpOfMmr(mmr(day, adj)) + lpLag + lpAdj) / 10 * 10); }
        int mr(int day, double adj) { return (int) Math.round(1500 + (mmr(day, adj) - 1600) * 1.5); }
        Knobs knobs(int day, double adj) { return RankCore.knobs(mmr(day, adj), personaIdx); }
        int games(int day, int seasonDay) { return games0 + Math.max(0, day - seasonDay) * (1 + (int) (idHash() & 3)); }
    }

    static String idOf(int b, int slot) { return String.format("p%03d%04d", b, slot); }
    /** id → 사람(풀 밖 id 면 null). 예전 리그 12명은 League 가 legacy(...) 로 만든다 */
    static Player player(long seed, String id) {
        if (id == null || id.length() != 8 || id.charAt(0) != 'p') return null;
        try {
            int b = Integer.parseInt(id.substring(1, 4)), slot = Integer.parseInt(id.substring(4));
            if (b < 0 || b >= NB || slot < 0 || slot >= COUNT[b]) return null;
            return playerAt(seed, b, slot);
        } catch (NumberFormatException e) { return null; }
    }

    static Player playerAt(long seed, int b, int slot) {
        Rng r = new Rng(Rng.mix(seed, GEN, b, slot));
        Player p = new Player();
        p.id = idOf(b, slot);
        p.baseMmr = MMR_MIN + b * BW + r.nextDouble() * BW;
        int[] mc = pickMain(r, p.baseMmr);
        p.ch = mc[0]; p.sty = mc[1];
        p.personaIdx = r.nextInt(10) < 4 ? 0 : 1 + r.nextInt(3);
        p.persona = PERSONA[p.personaIdx];
        p.nick = Names.nick(r, p.ch);
        p.avatar = r.nextInt(1 << 16);
        p.region = Names.region(r);
        p.title = Names.title(r, p.baseMmr, p.ch, p.sty);
        p.stage = r.nextInt(8);
        p.lpLag = (int) Math.round(-60 + r.gauss() * 140);   // 대개 실력보다 조금 아래(올라가는 중) — 위·아래 다 있다
        p.games0 = 30 + (int) (Math.max(0, p.baseMmr - 500) * (0.3 + r.nextDouble() * 0.9));
        p.wr0 = Math.max(0.38, Math.min(0.74, 0.5 + 0.045 * r.gauss() + (p.baseMmr - MU) / 5500));   /* 상위권이 모두 70% 로 붙지 않게(에뮬레이터 순위표에서 봄) — 난수 순서는 그대로라 같은 id 는 같은 사람 */
        return p;
    }
    private static int[] pickMain(Rng r, double mmr) {
        int tot = 0;
        for (int[] m : MAINS) tot += w(m, mmr);
        int x = r.nextInt(tot);
        for (int[] m : MAINS) { x -= w(m, mmr); if (x < 0) return m; }
        return MAINS[0];
    }
    private static int w(int[] m, double mmr) { return m[0] == 14 ? (mmr >= 1400 ? 4 : 0) : m[2]; }   // 유가(고르기 판에 없음) 장인은 상위권에만

    /** 예전 리그 상대 → 라이벌. MMR 은 난도 기준점(하 800 · 중 1000 · 상 1200) + (예전 점수 − 그 난도 초기 점수 가운데) */
    static Player legacy(String id, String nick, int ch, int sty, int diff, int oldScore) {
        Player p = new Player();
        p.id = id; p.nick = nick; p.ch = ch; p.sty = sty; p.legacy = true;
        p.baseMmr = new int[]{ 800, 1000, 1200 }[clamp(diff, 0, 2)] + (oldScore - new int[]{ 910, 1000, 1090 }[clamp(diff, 0, 2)]);
        Rng r = new Rng(Rng.mix(0x1E6AC1L, p.idHash()));
        p.personaIdx = 0; p.persona = PERSONA[0];
        p.avatar = r.nextInt(1 << 16); p.region = Names.region(r); p.title = "오랜 라이벌"; p.stage = -1;
        p.lpLag = 0; p.games0 = 300 + r.nextInt(400); p.wr0 = 0.5;
        return p;
    }

    /* ── 닉네임·지역·칭호 ─────────────────────────────────────────────── */
    static final class Names {
        static final String[] PRE = { "새벽", "달빛", "붉은", "검은", "푸른", "하얀", "고독한", "조용한", "졸린", "배고픈", "퇴근후", "야근중", "출근길", "주말",
            "방구석", "동네", "전설의", "무명", "떠돌이", "늙은", "어린", "바람", "벚꽃", "서리", "번개", "그림자", "불꽃", "얼음", "강철", "황금", "은빛", "한밤",
            "비오는", "첫눈", "사나운", "느긋한", "수상한", "착한", "심심한", "진지한", "웃는", "산골", "바닷가", "남쪽", "북쪽", "하늘", "골목", "옥상", "편의점", "새내기",
            "졸업반", "새벽녘", "노을", "안개", "천둥", "회색", "보라", "초록", "자정", "정오", "봄날", "여름밤", "가을", "겨울", "외로운", "용감한", "겁없는", "날쌘",
            "무거운", "가벼운", "단단한", "말없는", "시골", "도시", "항구", "섬마을", "고개", "강변", "숲속", "절벽", "들판", "달밤", "별빛", "햇살", "먹구름", "소나기", "폭풍" };
        static final String[] SUF = { "검객", "낭인", "무사", "사무라이", "닌자", "칼잡이", "자객", "검사", "장인", "고수", "초보", "형님", "동생", "아재", "삼촌",
            "요정", "도령", "낭자", "호랑이", "늑대", "여우", "고양이", "곰", "매", "까마귀", "독수리", "학", "용", "너구리", "다람쥐", "한판", "연습생", "방랑자",
            "나그네", "선비", "무녀", "스님", "포졸", "대장", "막내",
            "검귀", "검신", "검호", "협객", "무희", "궁수", "창잡이", "도사", "약장수", "훈장", "사냥꾼", "어부", "농부", "목수", "대장장이", "불주먹", "칼바람", "외톨이",
            "올빼미", "참새", "까치", "고래", "상어", "멧돼지", "사슴", "토끼", "거북이" };
        static final String[] CSHORT = { "카즈키", "소게츠", "하오", "겐쥬로", "나코", "리무", "한조", "갈포드", "아수라", "샬럿", "모로즈미", "우쿄", "주베이", "시키", "유가" };
        static final String[] CSUF = { "사랑", "장인", "매니아", "짱", "러버", "덕후", "일짱", "원툴", "고수", "만세", "한판", "최고" };
        static final String[] ROM = { "kazuki", "sogetsu", "haoh", "genjuro", "nako", "rimu", "hanzo", "galford", "asura", "charlotte", "mrz", "ukyo", "jubei", "shiki", "yuga" };
        static final String[] ROMSUF = { "", "_kr", "x", "_main", "lover", "fan", "god", "zz", "_" };
        static String nick(Rng r, int ch) {
            int k = r.nextInt(100);
            if (k < 30) return PRE[r.nextInt(PRE.length)] + SUF[r.nextInt(SUF.length)];
            if (k < 68) return PRE[r.nextInt(PRE.length)] + SUF[r.nextInt(SUF.length)] + (1 + r.nextInt(r.nextInt(3) == 0 ? 999 : 99));
            if (k < 80) { String c = r.nextInt(3) == 0 ? CSHORT[r.nextInt(CSHORT.length)] : CSHORT[ch]; return c + CSUF[r.nextInt(CSUF.length)] + (r.nextInt(4) == 0 ? "" : String.valueOf(1 + r.nextInt(999))); }
            String base = r.nextInt(3) == 0 ? ROM[r.nextInt(ROM.length)] : ROM[ch];       // 주캐 이름을 딴 영문 아이디가 흔하다
            String s = base + ROMSUF[r.nextInt(ROMSUF.length)];
            if (r.nextInt(8) != 0) s += (r.nextInt(4) == 0 ? 1985 + r.nextInt(25) : r.nextInt(1000));   // 영문 아이디는 거의 숫자가 붙는다(이미 쓰는 이름이라)
            if (r.nextInt(5) == 0) s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
            return s;
        }
        static final String[] REGION = { "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주", "해외" };
        static final int[] REGION_W = { 190, 65, 47, 58, 28, 29, 22, 8, 270, 30, 32, 42, 35, 35, 51, 64, 13, 21 };
        static String region(Rng r) {
            int t = 0; for (int w : REGION_W) t += w;
            int x = r.nextInt(t);
            for (int i = 0; i < REGION.length; i++) { x -= REGION_W[i]; if (x < 0) return REGION[i]; }
            return REGION[0];
        }
        static final String[] TITLE_ANY = { "연습벌레", "한 판만 더", "출근 전 한 판", "밤샘 수련", "막기 장인", "분노 폭발", "역전의 명수", "첫 승의 기쁨",
            "돌아온 고수", "신예", "꾸준함", "주말 무사", "무기 파괴자", "점프 금지", "거리 재기", "한 방의 미학" };
        static final String[] TITLE_HIGH = { "검의 극의", "무패 행진", "천하제일", "백전노장", "수라의 길", "나찰의 길", "끝판왕 사냥꾼", "전국 상위권" };
        static String title(Rng r, double mmr, int ch, int sty) {
            if (mmr >= 1350 && r.nextInt(3) == 0) {
                String t = TITLE_HIGH[r.nextInt(TITLE_HIGH.length)];
                if (t.startsWith("수라") && sty != 0) t = "나찰의 길";
                if (t.startsWith("나찰") && sty != 1) t = "수라의 길";
                return t;
            }
            if (r.nextInt(4) == 0) return CHAR[ch] + " 장인";
            return TITLE_ANY[r.nextInt(TITLE_ANY.length)];
        }
    }

    /* ── 등급 계산 ────────────────────────────────────────────────────── */
    /** 내 랭크 상태(League 가 JSON 과 옮긴다) */
    static final class Me {
        double mmr = 1000; int games; int lp; boolean shield; int placementLeft = 5; boolean master; int mr;
        int w, l, d, streak, lstreak, best; int peakLp;
        List<String> recent = new ArrayList<>();      // 최근 상대 id(새것 먼저, 12명까지)
        String pendingId;                             // 잡힌 상대(붙을 때까지)
        int lastWinDay = Integer.MIN_VALUE;           // 오늘 첫 승 보너스
    }
    static final class Outcome {
        int lpBefore, lpAfter, lpDelta, mrBefore, mrAfter; double mmrBefore, mmrAfter, oppMmrDelta;
        int tierBefore, tierAfter, starBefore, starAfter;
        boolean promoted, demoted, shieldUsed, placementDone, enteredMaster;
        int bonusStreak, bonusRevenge, bonusDaily; int oppLpDelta;
        String event;   // 결과 카드 한 줄 («승급! 검호 ★1» · «강등 위기» · «무사로 강등» · «배치 완료: 검객 ★2» …)
    }
    static double expect(double my, double opp) { return 1 / (1 + Math.pow(10, (opp - my) / 400)); }
    static int round5(double v) { return (int) Math.round(v / 5) * 5; }
    /** 오늘 첫 승·설욕(재대결 승) 보너스 LP — 정산과 팁(RankTips)이 같은 값을 쓴다 */
    static final int BONUS_DAILY = 30, BONUS_REVENGE = 20;
    static int round10(double v) { return (int) Math.round(v) / 10 * 10; }   // 배치 LP 와 같은 10 단위(끝자리는 버림)
    /** 배치를 마친 자리(«이 실력이면 여기서 시작») — 기준선 LP - 300, 10 단위 */
    static int placementLp(double mmr) { return Math.max(0, round10(lpOfMmr(mmr) - 300)); }
    /** 새 시즌 LP(소프트 리셋) — 배치 자리(base) 아래면 그대로, 위면 base 쪽으로 반(10 단위, 끝자리는 base 쪽으로). 무쌍은 풀고 검성 ★5 끝(9000 - 10)까지 */
    static int newSeasonLp(int lp, double mmr) {
        int base = placementLp(mmr);
        int v = lp <= base ? lp : round10(base + (lp - base) / 2.0);
        return Math.max(0, Math.min(MASTER_LP - 10, v));
    }

    /**
     * 경기 하나 정산 — me 를 바꾸고 결과를 돌려준다. out: 'W' 'L' 'D'(기권 = 'L'). revenge = 바로 앞 경기에서 이 상대에게 졌다(재대결로 이김 보너스).
     * oppMmr·oppMr = 경기 시작 때 상대 값(시작 때 저장한 걸 쓴다 — 기획서 «시작 시점 두 점수로 구간 고정»).
     */
    static Outcome settle(Me me, char out, double oppMmr, int oppMr, boolean revenge, int day) {
        Outcome o = new Outcome();
        o.lpBefore = me.lp; o.mmrBefore = me.mmr; o.mrBefore = me.mr;
        o.tierBefore = me.master ? 7 : tierOf(me.lp); o.starBefore = starOf(me.lp);
        double S = out == 'W' ? 1 : out == 'L' ? 0 : 0.5, E = expect(me.mmr, oppMmr);
        int K = me.games < 5 ? 64 : me.games < 20 ? 40 : 28;
        me.mmr += K * (S - E);
        o.oppMmrDelta = -16 * (S - E);
        me.games++;
        if (out == 'W') { me.w++; me.streak++; me.lstreak = 0; me.best = Math.max(me.best, me.streak); }
        else if (out == 'L') { me.l++; me.lstreak++; me.streak = 0; }
        else me.d++;
        if (me.placementLeft > 0) {                               // 배치: LP 는 마지막 판에 한 번에
            me.placementLeft--;
            if (me.placementLeft == 0) {
                me.lp = placementLp(me.mmr);
                if (me.lp >= MASTER_LP) { me.lp = MASTER_LP - 10; }    // 배치로 바로 무쌍은 안 준다
                o.placementDone = true; o.event = "배치 완료: " + label(me.lp);
            }
        } else if (me.master) {                                    // 무쌍: MR(엘로), 강등 없음
            double Em = expect(me.mr, oppMr);
            int dm = (int) Math.round(32 * (S - Em));
            me.mr += dm;
        } else {
            int t = tierOf(me.lp), d = 0;
            double g = Math.max(-1, Math.min(1, (me.mmr - mmrOfLp(me.lp)) / 150));   // 실력이 LP 보다 위면 더 오르고 덜 떨어진다(LP 가 MMR 을 따라감)
            if (out == 'W') {
                d = round5((50 + 100 * (1 - E)) * (1 + 0.5 * g));
                if (me.streak >= 3) o.bonusStreak = 10 * Math.min(me.streak - 2, 4);
                if (revenge) o.bonusRevenge = BONUS_REVENGE;
                if (me.lastWinDay != day) { o.bonusDaily = BONUS_DAILY; me.lastWinDay = day; }
                d += o.bonusStreak + o.bonusRevenge + o.bonusDaily;
            } else if (out == 'L') {
                d = -round5((30 + 100 * E) * (1 - 0.4 * g));
                if (t == 0) d = 0;                                 // 수련생은 안 깎인다
                else if (t == 1) d = round5(d * 0.5);              // 낭인은 반만
            }
            int nl = me.lp + d;
            if (out == 'L' && nl < FLOOR[t]) {
                if (t <= 1) nl = FLOOR[t];                         // 낭인 → 수련생으로는 안 떨어진다
                else if (!me.shield) { nl = FLOOR[t]; me.shield = true; o.shieldUsed = true; o.event = "강등 위기! 한 번 더 지면 " + TIER[t - 1]; }
                else { nl = FLOOR[t - 1] + 3 * STEP[t - 1]; me.shield = false; o.demoted = true; o.event = TIER[t - 1] + "(으)로 강등"; }
            }
            if (out == 'W') me.shield = false;
            nl = Math.max(0, nl);
            if (nl >= MASTER_LP) { nl = MASTER_LP; me.master = true; me.mr = (int) Math.round(1500 + Math.max(0, me.mmr - 1600) * 1.5); o.enteredMaster = true; o.event = "무쌍 달성!"; }
            me.lp = nl;
        }
        me.peakLp = Math.max(me.peakLp, me.lp);
        o.lpAfter = me.lp; o.lpDelta = o.lpAfter - o.lpBefore; o.mmrAfter = me.mmr; o.mrAfter = me.mr;
        o.tierAfter = me.master ? 7 : tierOf(me.lp); o.starAfter = starOf(me.lp);
        if (!o.placementDone && !o.enteredMaster && o.tierAfter > o.tierBefore) { o.promoted = true; o.event = "승급! " + label(me.lp); }
        else if (o.event == null && o.tierAfter == o.tierBefore && o.starAfter > o.starBefore && !me.master) o.event = "★ 올라감 — " + label(me.lp);
        o.oppLpDelta = out == 'W' ? -round5(20 + 40 * E) : out == 'L' ? round5(20 + 40 * (1 - E)) : 0;
        return o;
    }

    /** 이기면·지면 LP 얼마(상대 카드 «이기면 +85 · 지면 −60») — 정산과 같은 식, 보너스는 빼고 */
    static int[] stakes(Me me, double oppMmr) {
        if (me.placementLeft > 0 || me.master) return null;
        double E = expect(me.mmr, oppMmr), g = Math.max(-1, Math.min(1, (me.mmr - mmrOfLp(me.lp)) / 150));
        int t = tierOf(me.lp);
        int win = round5((50 + 100 * (1 - E)) * (1 + 0.5 * g));
        int lose = t == 0 ? 0 : -round5((30 + 100 * E) * (1 - 0.4 * g) * (t == 1 ? 0.5 : 1));
        return new int[]{ win, lose };
    }

    /* ── 매칭 ─────────────────────────────────────────────────────────── */
    static final class Pick {
        Player p; double mmr; long searchMs; final List<String> tags = new ArrayList<>(); boolean pending;
        int waitU = -1;                                   // 찾는 시간 난수(0~3999) — 잡힌 상대·라이벌은 -1(대기창 시간 = searchMs)
        long waitMs;                                      // 대기창에 쓰는 시간(League.rankedPick 이 waitMs(...) 로 채운다)
        final List<Player> reel = new ArrayList<>();     // 실제로 훑어본 후보(고른 상대·최근 상대 빼고 8명까지) — 대기창 릴
    }
    /** 내 쪽에서 본 상대 기록(League 가 players 칸에서 채운다) */
    interface Book {
        double adj(String id);           // 나와의 경기로 바뀐 MMR
        int[] h2h(String id);            // {내 승, 내 패, 무, 마지막 결과(1 승 · −1 패 · 0 무/없음), 내 연승을 끊은 수}
        List<Player> rivals();           // 예전 리그 12명 등
        default int lpAdj(String id) { return 0; }   // 나와의 경기로 바뀐 LP(players[id].lp_adj) — 순위표·프로필·카드가 같은 LP 를 쓰게
    }
    static final double RIVAL_P = 0.12;
    static Pick pick(long seed, Me me, int day, Book book, Rng r) {
        Pick pk = new Pick();
        if (me.pendingId != null) {                                            // 잡힌 상대 — 붙을 때까지 같은 상대(카드에서 나가도 도망 아님)
            Player p = resolve(seed, me.pendingId, book);
            if (p != null) { pk.p = p; pk.mmr = p.mmr(day, book.adj(p.id)); pk.searchMs = 1200 + r.nextInt(1200); pk.pending = true; tag(pk, book, me); return pk; }
        }
        Set<String> recent = new HashSet<>(me.recent.subList(0, Math.min(8, me.recent.size())));
        Set<String> hard = new HashSet<>(me.recent.subList(0, Math.min(3, me.recent.size())));
        double sigma = me.games < 10 ? 90 : 60;
        double skew = me.streak >= 3 ? 25 * Math.min(me.streak - 2, 4) : me.lstreak >= 3 ? -25 * Math.min(me.lstreak - 2, 3) : 0;
        double target = me.mmr + skew;
        int u = r.nextInt(4000); pk.waitU = u;                                     // 같은 한 번 뽑기 — searchMs·고른 상대·난수 순서는 147 그대로
        long ms = 2000 + u + (long) (Math.max(0, me.mmr - 1450) * 60);             // 상위권일수록 오래
        pk.searchMs = Math.min(12000, ms);
        if (r.nextDouble() < RIVAL_P) {                                            // 라이벌·설욕전
            List<Player> c = new ArrayList<>();
            for (Player p : book.rivals()) if (!recent.contains(p.id) && Math.abs(p.mmr(day, book.adj(p.id)) - me.mmr) <= 200) c.add(p);
            if (!c.isEmpty()) { pk.p = c.get(r.nextInt(c.size())); pk.mmr = pk.p.mmr(day, book.adj(pk.p.id)); pk.waitU = -1; tag(pk, book, me); return pk; }
        }
        Player best = null; double bestCost = 1e18;
        int lastCh = -1;
        if (!me.recent.isEmpty()) { Player lp = resolve(seed, me.recent.get(0), book); if (lp != null) lastCh = lp.ch; }
        for (int i = 0; i < 12; i++) {
            double m = target + r.gauss() * sigma;
            int b = bucketOf(m);
            for (int tries = 0; tries < 6 && COUNT[b] == 0; tries++) b += (b < bucketOf(MU) ? 1 : -1);   // 끝쪽 빈 칸이면 가운데로
            if (COUNT[b] == 0) continue;
            Player p = playerAt(seed, b, r.nextInt(COUNT[b]));
            if (recent.contains(p.id)) continue;
            if (pk.reel.size() < 12) pk.reel.add(p);                              // 릴 — 난수를 더 뽑지 않는다
            double pm = p.mmr(day, book.adj(p.id));
            double cost = Math.abs(pm - target) + (p.ch == lastCh ? 40 : 0) + r.nextDouble() * 20;
            if (cost < bestCost) { bestCost = cost; best = p; }
        }
        if (best == null) best = playerAt(seed, bucketOf(target), r.nextInt(Math.max(1, COUNT[bucketOf(target)])));
        Set<String> seen = new HashSet<>(); seen.add(best.id);
        pk.reel.removeIf(x -> !seen.add(x.id));                                   // 고른 상대·겹친 사람 빼고
        while (pk.reel.size() > 8) pk.reel.remove(pk.reel.size() - 1);
        pk.p = best; pk.mmr = best.mmr(day, book.adj(best.id));
        tag(pk, book, me);
        return pk;
    }
    /** 대기창 시간 — «보통»: 4~9초 + 상위권 덤, 저녁(19~23시) ×0.85 · 새벽(2~6시) ×1.4, 15초 끝. 잡힌 상대·라이벌·«빠르게»는 searchMs(147 그대로) */
    static long waitMs(Pick pk, double myMmr, boolean fast, int hour) {
        if (pk.pending || fast || pk.waitU < 0) return pk.searchMs;
        long base = 4000 + pk.waitU * 5L / 4;
        double top = Math.max(0, myMmr - 1450) * 60;
        double hourF = hour >= 19 && hour <= 23 ? 0.85 : hour >= 2 && hour <= 6 ? 1.4 : 1.0;
        return Math.min(15000, Math.round((base + top) * hourF));
    }
    /** 예상 시간(초) — 처음에 정하고 안 늘린다. 1초 넉넉히라 카드는 제때나 조금 일찍 뜬다 */
    static int estSec(long waitMs) { return (int) Math.ceil(waitMs / 1000.0) + 1; }
    /** 153 짧은 찾기 겹창 — 이름 몇 번 넘기고 «상대 발견!» 까지(유저 «뚜뚜뚜뚜 짧게 하고 바로 다음 대전»). 새 난수를 뽑지 않고 pick 이 이미 뽑은
     *  값으로만(147~152 매칭 순서 그대로): 잡힌 상대 0.8~1.1초 · 라이벌 1.0~1.4초 · 보통 1.0~1.4초(waitU 0~3999). native 는 그동안 4배속으로 고르기를 밟는다 */
    static final long QUICK_MIN = 800, QUICK_MAX = 1400;
    static long quickMs(Pick pk) {
        if (pk == null) return 1000;
        if (pk.pending) return 800 + Math.max(0, Math.min(1199, pk.searchMs - 1200)) / 4;
        if (pk.waitU < 0) return 1000 + Math.floorMod(pk.searchMs, 400);
        return 1000 + Math.max(0, Math.min(3999, pk.waitU)) / 10;
    }
    static Player resolve(long seed, String id, Book book) {
        if (id == null) return null;
        if (id.startsWith("p")) return player(seed, id);
        for (Player p : book.rivals()) if (p.id.equals(id)) return p;
        return null;
    }
    private static void tag(Pick pk, Book book, Me me) {
        int[] h = book.h2h(pk.p.id);
        if (pk.p.legacy || h[0] + h[1] >= 4 && Math.abs(h[0] - h[1]) <= 1) pk.tags.add("라이벌");
        if (h[3] == -1) pk.tags.add("설욕전");
        if (h[1] >= 3 && h[1] >= 2 * h[0]) pk.tags.add("천적");
        if (h[4] > 0) pk.tags.add("내 연승을 끊은 상대");
        if (h[0] + h[1] + h[2] == 0 && !pk.p.legacy) pk.tags.add("첫 대전");
        if (me.streak >= 3) pk.tags.add(me.streak + "연승 중");
        int[] st = stakes(me, pk.mmr);
        if (st != null && !me.master && tierOf(me.lp + st[0]) > tierOf(me.lp)) pk.tags.add("승급 기회");
        if (st != null && me.shield) pk.tags.add("강등 위기");
    }

    /* ── 순위표 ───────────────────────────────────────────────────────── */
    static final class Row { long rank; String id, nick, label; int lp, mr, ch, sty; double wr; boolean me; Player p; }
    /** 줄 세우는 값 — 무쌍은 MR 로, 아니면 LP */
    static long key(int lp, boolean master, int mr) { return master ? 1_000_000L + mr : lp; }
    static int today(long nowMs) { return (int) Math.floorDiv(nowMs + 9 * 3600_000L, 86_400_000L); }   // 한국 시각 자정에 바뀜
    static int hourOf(long nowMs) { return (int) Math.floorMod(Math.floorDiv(nowMs + 9 * 3600_000L, 3600_000L), 24L); }   // 한국 시각 몇 시(today 와 같은 기준)

    /** 위 n명(+ 내가 그 안이면 나도). myKey = key(내 LP, 무쌍, MR) */
    static List<Row> top(long seed, int day, int n, Book book, long myKey, String myNick) {
        List<Row> rows = new ArrayList<>();
        int b = NB - 1, have = 0;
        while (b >= 0 && have < n + 120) { have += COUNT[b]; b--; }               // 날짜 흔들림(±45)만큼 넉넉히
        for (int bb = NB - 1; bb > b; bb--)
            for (int s = 0; s < COUNT[bb]; s++) rows.add(row(seed, day, playerAt(seed, bb, s), book));
        for (Player p : book.rivals()) rows.add(row(seed, day, p, book));
        Row me = new Row(); me.me = true; me.id = "me"; me.nick = myNick;
        me.lp = myKey >= 1_000_000L ? MASTER_LP : (int) myKey; me.mr = myKey >= 1_000_000L ? (int) (myKey - 1_000_000L) : 0;
        me.label = myKey >= 1_000_000L ? TIER[7] : label((int) myKey);
        rows.add(me);
        rows.sort((x, y) -> {
            long kx = x.me ? myKey : key(x.lp, x.lp >= MASTER_LP, x.mr), ky = y.me ? myKey : key(y.lp, y.lp >= MASTER_LP, y.mr);
            if (kx != ky) return Long.compare(ky, kx);
            if (x.me != y.me) return x.me ? -1 : 1;            // 같으면 나 먼저(내 순위를 낙관적으로 — 화면에선 흔한 관례)
            return x.id.compareTo(y.id);
        });
        for (int i = 0; i < rows.size(); i++) rows.get(i).rank = i + 1;
        return new ArrayList<>(rows.subList(0, Math.min(n, rows.size())));
    }
    private static Row row(long seed, int day, Player p, Book book) {
        Row r = new Row(); r.p = p; r.id = p.id; r.nick = p.nick; r.ch = p.ch; r.sty = p.sty;
        double adj = book.adj(p.id);
        r.lp = p.lp(day, adj, book.lpAdj(p.id));
        if (r.lp >= MASTER_LP) { r.lp = MASTER_LP; r.mr = p.mr(day, adj); r.label = TIER[7]; } else r.label = label(r.lp);
        r.wr = p.wr0;
        return r;
    }
    /** 내 열쇠 → 분포에서 설 MMR — 풀의 LP 는 평균 60 아래로 처져 있다. 무쌍은 MR 로, 다만 무쌍 경계(LP 9000) 아래로는 안 내려간다
     *  (MR 1500 아래 무쌍이 무쌍 아닌 사람보다 아래로 매겨지지 않게 — 열쇠는 무쌍이면 늘 위) */
    static double centre(long myKey) {
        return myKey >= 1_000_000L ? Math.max(mmrOfLp(MASTER_LP + 60), 1600 + (myKey - 1_000_000L - 1500) / 1.5) : mmrOfLp(myKey + 60);
    }
    /** 내 순위 — 위 150 안이면 top 으로 정확히, 아니면 분포로 어림(LP → MMR → 위쪽 비율) */
    static long rankOf(long seed, int day, Book book, long myKey) {
        List<Row> t = top(seed, day, 150, book, myKey, "나");
        for (Row r : t) if (r.me) return r.rank;
        double m = centre(myKey);
        long above = Math.round(TOTAL * (1 - phi((m - MU) / SIGMA)));
        return Math.max(151, above + 1);
    }
    /** 내 주변 k명씩(위·아래) — 내 MMR 근처 칸에서 만든 사람들로. 순위 번호는 내 순위에서 하나씩 */
    static List<Row> around(long seed, int day, Book book, long myKey, String myNick, int k) {
        long myRank = rankOf(seed, day, book, myKey);
        if (myRank <= 150) {
            List<Row> t = top(seed, day, 150, book, myKey, myNick);
            int i = 0; while (i < t.size() && !t.get(i).me) i++;
            return new ArrayList<>(t.subList(Math.max(0, i - k), Math.min(t.size(), i + k + 1)));
        }
        double m = centre(myKey);                                                     // rankOf 와 같은 자리에서
        List<Row> up = new ArrayList<>(), dn = new ArrayList<>();
        Rng r = new Rng(Rng.mix(seed, day, myKey));
        for (int bb = bucketOf(m) - 3; bb <= bucketOf(m) + 3; bb++) {
            if (bb < 0 || bb >= NB || COUNT[bb] == 0) continue;
            for (int s = 0; s < 8; s++) {
                Row x = row(seed, day, playerAt(seed, bb, r.nextInt(COUNT[bb])), book);
                long kx = key(x.lp, x.lp >= MASTER_LP, x.mr);
                if (kx > myKey) up.add(x); else if (kx < myKey) dn.add(x);
            }
        }
        up.sort((x, y) -> Long.compare(key(x.lp, x.lp >= MASTER_LP, x.mr), key(y.lp, y.lp >= MASTER_LP, y.mr)));   // 무쌍은 LP 가 다 9000 — MR 까지 본 열쇠로
        dn.sort((x, y) -> Long.compare(key(y.lp, y.lp >= MASTER_LP, y.mr), key(x.lp, x.lp >= MASTER_LP, x.mr)));
        dedupe(up); dedupe(dn);
        List<Row> out = new ArrayList<>();
        for (int i = Math.min(k, up.size()) - 1; i >= 0; i--) { Row x = up.get(i); x.rank = myRank - 1 - i; out.add(x); }
        Row me = new Row(); me.me = true; me.id = "me"; me.nick = myNick; me.rank = myRank;
        me.lp = myKey >= 1_000_000L ? MASTER_LP : (int) myKey; me.mr = myKey >= 1_000_000L ? (int) (myKey - 1_000_000L) : 0;
        me.label = myKey >= 1_000_000L ? TIER[7] : label((int) myKey);
        out.add(me);
        for (int i = 0; i < Math.min(k, dn.size()); i++) { Row x = dn.get(i); x.rank = myRank + 1 + i; out.add(x); }
        return out;
    }
    private static void dedupe(List<Row> l) { Set<String> s = new HashSet<>(); l.removeIf(x -> !s.add(x.id)); }
    /** 오늘 활동 인원(화면 꾸밈 — 날마다 전체의 92~98%: 전체 인원보다 많아지지 않게) */
    static long activeToday(long seed, int day) { return Math.round(TOTAL * (0.92 + 0.06 * new Rng(Rng.mix(seed, day, 7)).nextDouble())); }
    static List<Row> unmodifiable(List<Row> l) { return Collections.unmodifiableList(l); }
}
