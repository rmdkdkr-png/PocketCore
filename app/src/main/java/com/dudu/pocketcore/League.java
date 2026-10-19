package com.dudu.pocketcore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;

/**
 * 가상 리그(사무쇼2) — 기록 저장소와 정산 규칙. 기획서 「NGP 사무라이 쇼다운 2 가상 리그 v1」 2단계(랭크전 완성: 가상 상대 12명).
 *
 * 상대는 게임의 CPU 다. 닉네임·주캐·검질·난도를 가진 «가상 상대»를 실제 경기의 P2 로 불러내고(대진 덮어쓰기),
 * 실행 중인 게임에서 읽은 경기 최종 결과만 전적에 넣는다(league.c). 원작 세이브와 따로 둔다:
 *   기록      = 루트/league/league.json   (원자 저장: 임시 파일 → fsync → 이름 바꾸기, 같은 내용 한 벌 더 .bak)
 *   랭크전 세이브 = 루트/league/saves/       (원작 세이브 폴더와 다른 곳 — 랭크전이 원작 세이브를 건드리지 않는다)
 *   랭크전 롬   = 루트/league/rom/          (원본 + 번역 패치만 — 조작 패치·배경음악 없이 고정 판, Patcher.resolveLeague)
 *   체크포인트 = 루트/league/ckpt_<경기id>.state (앱이 내려갈 때 그 순간만, 한 번 쓰면 지운다) + 옆 .state.look(경기 컬러·경기 베기 색 — 171 부터 같이 옮기고 같이 지움)
 *
 * 경기 상태(기획서 4장): ready 준비 · playing 진행 중 · settled 정산 완료 · resume 복귀 대기 · forfeit 기권 · hold 오류 보류
 *                       · cancelled 무효(대진 전에 멈춤). 끝난 상태(settled·forfeit·hold·cancelled)는 다시 바뀌지 않는다.
 * 「최종 결과 감지」는 정산과 **같은 한 번의 저장**으로 기록한다 — 결과와 점수 변경을 한 번에(기획서 «저장 데이터»).
 */
final class League {
    private League() { }

    /* ── 원작 정보 ─────────────────────────────────────────────────────── */

    /** 0x57DF 캐릭터 id 0~14 (15 이상은 게임이 멈춘다 — 0단계 결과). 표기는 한글패치 이름판과 같게(게임 안 이름 = 이것). */
    static final String[] CHAR = { "카즈키", "소게츠", "하오마루", "겐쥬로", "나코루루", "리무루루", "한조", "갈포드",
                                   "아수라", "샬럿", "모로즈미", "우쿄", "주베이", "시키", "유가" };
    static final String[] STYLE = { "수라", "나찰" };
    static final String[] DIFF  = { "쉬움", "보통", "어려움" };
    /** 배경(스테이지) 0~7 — 1인 대전에서 배경은 «몇 번째 판»(0x5827)을 따른다(league.c R_BOUT). 실측한 여덟 개 */
    static final String[] STAGES = { "후지산 마을", "숲속 호숫가", "산 위 신사", "화염 사원", "폐허 사원", "불꽃 동굴", "마계 제단", "수정 신전" };
    /** 컬러 이름 — 0 게임 기본 · 1 2P색(옛 기록 이름 — 165 부터 플레이어는 안 고름) · 2~15 프리셋 · 16·17 내 색(Look — 모든 모드 컬러).
     *  옛 기록(cv 없음)의 2·3 은 «컬러 3·4» — 이름은 Look.name(idx, cv) */
    static final String[] COLORS = Look.NAMES;
    /** 가상 상대마다 기본 배경(«상대 기본» 일 때) — 명단(ROSTER)과 따로 둬서 옛 기록을 안 고친다 */
    private static final String[][] HOME_STAGE = {
        { "o01", "2" }, { "o02", "3" }, { "o03", "1" }, { "o04", "0" }, { "o05", "4" }, { "o06", "1" },
        { "o07", "5" }, { "o08", "0" }, { "o09", "5" }, { "o10", "6" }, { "o11", "6" }, { "o12", "7" } };
    static int homeStage(String oppId) {
        for (String[] h : HOME_STAGE) if (h[0].equals(oppId)) return Integer.parseInt(h[1]);
        return -1;
    }
    /** 배경 고르기 이름 — sel −1 상대 기본 · −2 무작위 · 0~7 */
    static String stageLabel(int sel, String oppId) {
        if (sel == -2) return "무작위";
        if (sel >= 0 && sel < STAGES.length) return STAGES[sel];
        int h = oppId != null ? homeStage(oppId) : -1;
        return h >= 0 ? STAGES[h] + "(상대 기본)" : "게임 기본";
    }
    static String stageName(int st) { return st >= 0 && st < STAGES.length ? STAGES[st] : STAGES[0]; }

    /** 자동 정산을 허용하는 실행 롬(사본) md5 — 판정 주소를 실측한 판만. 다른 판의 주소는 재사용하지 않는다(기획서 4장). */
    static final String[][] ROMS = {
        { "5f980fc074aba4941778f14c1ef32693", "ss2.ngc + 한글패치 v1.01" },
        { "d8e29d0ff16a9b34fc7ee91268fad462", "ss2.ngc + 한글패치 앱 동봉판" },
        { "b93268c117cb17baea1557bf44a81654", "ss2_h1.ngc + 한글패치 v1.01" },
        { "514ef6931b082c11966c67d1db580086", "ss2_h1.ngc + 한글패치 앱 동봉판" },
        { "8d6a36acd3b171970d87d2fca6796a4a", "순정 ss2.ngc (일본어·영어)" },
        { "3e46575450756953d755c23db12c4dd0", "순정 ss2_h1.ngc (일본어·영어)" },
    };
    /** CPU 상대로 쓰면 안 되는 캐릭터·검질(id<<1|검질). 덮어쓰면 CPU 가 싸우지 않는다 — 14캐릭 × 2검질 P1 28조합 모두에서
     *  가만히 있는 사람에게도, 다가가 공격하는 사람에게도 거의 안 때림(0단계 «가만히 있는 CPU», 28×30 표). 스토리가 내지 않는 검질들이다. */
    static final int[] BAD_OPP = { (1 << 1) | 0, (4 << 1) | 1, (5 << 1) | 1, (6 << 1) | 1, (10 << 1) | 1, (12 << 1) | 1 };
    static boolean oppSupported(int ch, int style) {
        if (ch < 0 || ch > 14 || style < 0 || style > 1) return false;
        for (int b : BAD_OPP) if (b == ((ch << 1) | style)) return false;
        return true;
    }

    static final int POINTS = 2;      /* 2선승(3판 2승) — 롬 세이브의 기본값(1 일 수도 있다)에 기대지 않고 정한다 */
    static final int TIME   = 60;     /* 라운드 60초 */

    static String romLabel(String md5) {
        for (String[] r : ROMS) if (r[0].equals(md5)) return r[1];
        return null;
    }

    static String md5(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    /* ── 파일 ─────────────────────────────────────────────────────────── */

    static File dir()      { File d = new File(MainActivity.root(), "league"); d.mkdirs(); return d; }
    static File saveDir()  { File d = new File(dir(), "saves"); d.mkdirs(); return d; }
    static File romDir()   { File d = new File(dir(), "rom"); d.mkdirs(); return d; }
    static File ckpt(String id) { return new File(dir(), "ckpt_" + id + ".state"); }
    /** 체크포인트 옆 .look(경기 컬러·경기 베기 색 — nativeSaveState 가 씀, 171 부터 체크포인트와 같이 옮김) */
    static File ckptLook(String id) { return new File(ckpt(id).getPath() + ".look"); }
    /** 체크포인트를 지운다 — 옆 .look 도(작은 파일이 경기마다 쌓이지 않게) */
    static void ckptDelete(String id) { ckpt(id).delete(); ckptLook(id).delete(); }
    private static File file() { return new File(dir(), "league.json"); }
    private static File bak()  { return new File(dir(), "league.json.bak"); }

    private static JSONObject db;
    /** 기록 파일을 못 읽는데 옆으로 옮기지도 못했다 — 이때는 새 기록을 메모리에만 두고 절대 저장하지 않는다(진짜 기록을 덮지 않게). */
    static volatile String dbProblem = null;
    /** 게임 속 리그(EmuActivity 허브)가 살아 있다 — 리그 화면이 그 판의 «준비» 경기를 닫거나 시즌을 바꾸지 않게. 프로세스가 죽으면 저절로 false */
    static volatile boolean liveHub = false;
    /** 앱 판 번호(versionCode) — 보관 파일의 "app". 이 클래스는 JVM 시험에서도 돌아서 안드로이드 쪽(화면)이 넣어 준다(모르면 0) */
    static volatile int appVer = 0;

    /** 기록을 읽는다(없으면 새로). 깨졌으면 직전 판(.bak)으로. 명단이 옛 판(1단계: 상대 1명)이면 빠진 상대를 더해 바로 저장한다
     *  — 이미 있는 상대의 설정·점수·전적은 그대로(같은 상대는 다시 만나도 설정이 같다). 저장이 실패해도 메모리 판으로 쓰고,
     *  다음 저장 때 같이 들어간다(더하는 내용이 늘 같아서 다음 실행에서 다시 더해도 결과가 같다). */
    /** 파일 없이 새로 만든 기록을 아직 한 번도 못 썼다 — 저장소 권한 없이 처음 읽은 경우. 그 뒤 권한이 생겨 진짜 파일이 보이면
     *  빈 기록으로 덮어쓰지 않게 다시 읽는다(첫 저장이 진짜 league.json 을 새 기록으로 덮던 위험) */
    private static boolean dbFresh;
    static synchronized JSONObject db() {
        if (db != null && !(dbFresh && file().exists())) return db;
        if (db != null) {                                   /* 진짜 파일이 나타났다 */
            JSONObject seen = read(file());
            if (seen == null) seen = read(bak());
            if (seen == null) {                             /* 보이지만 못 읽는다 — 다시 만들지 않고(씨앗이 매번 바뀌던 고리) 덮지도 않게 막아 둔다: «다시 확인»(reload)이 옆으로 치운다 */
                dbFresh = false;
                dbProblem = "리그 기록 파일을 읽지 못했습니다 — 저장소를 확인한 뒤 다시 읽어 주세요(기록은 건드리지 않았습니다)";
                return db;
            }
            db = null; dbProblem = null; PF_CACHE.clear();  /* 메모리의 빈 기록은 버린다 */
        }
        JSONObject j = read(file());
        if (j == null) j = read(bak());
        if (j == null) {
            /* 둘 다 못 읽는데 파일은 있다(깨짐·다른 판의 형식 등) — 새 기록으로 덮지 않게 옆으로 치워 둔다(지우지 않는다) */
            long t = System.currentTimeMillis();
            boolean moved = (!file().exists() || file().renameTo(new File(dir(), "league.json.unreadable-" + t)))
                          & (!bak().exists() || bak().renameTo(new File(dir(), "league.json.bak.unreadable-" + t)));
            if (!moved) dbProblem = "리그 기록 파일을 읽지도 옮기지도 못했습니다 — 저장소 권한을 확인하고 앱을 다시 여세요(기록은 건드리지 않았습니다)";
            j = fresh();
            dbFresh = !file().exists() && !bak().exists();   /* 아무것도 못 보고 새로 만듦(권한 전 등) — 못 치운 파일이 남았으면(dbProblem) 다시 읽기를 노리지 않는다 */
        } else dbFresh = false;
        db = j;
        if (upgrade(j) | upgradeRanked(j)) save();       /* 랭크 칸은 처음 한 번 예전 기록에서 이어받는다(아래 «랭크 매치») */
        return db;
    }

    /** 기록 폴더에 실제로 쓸 수 있나 — 권한이 없으면 경기를 만들 때(고른 뒤)에야 실패하니 들어갈 때 먼저 본다 */
    static String writeProblem() {
        File t = new File(dir(), ".write_test");
        try { new FileOutputStream(t).close(); t.delete(); return null; }
        catch (Exception e) { return "리그 기록 폴더(" + dir() + ")에 쓸 수 없습니다 — 저장소 권한을 확인하세요"; }
    }

    /** 다시 읽기 — 저장소 권한을 고친 뒤 «다시 확인». 메모리 판을 버리고 파일에서 새로. 남은 문제(못 읽음·못 씀)를 돌려준다(없으면 null) */
    static synchronized String reload() {
        db = null; dbProblem = null; dbFresh = false;
        PF_CACHE.clear();
        db();
        return dbProblem != null ? dbProblem : writeProblem();
    }

    private static JSONObject read(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONObject j = new JSONObject(new String(b, 0, off, "UTF-8"));
            return j.optInt("v") == 1 ? j : null;
        } catch (Exception e) { return null; }
    }

    /** 원자 저장 — 쓰다 죽어도 옛 판 아니면 새 판이 남는다(반쯤 쓴 파일은 안 남는다). 본 파일을 바꾼 뒤 같은 내용을
     *  .bak 에도 같은 방식으로 한 벌 더 둔다(본 파일이 깨지면 그걸 읽는다). 본 파일 저장이 실패하면 false(호출자가 다시 시도). */
    static synchronized boolean save() {
        if (db == null || dbProblem != null) return false;
        try {
            byte[] b = db.toString(1).getBytes("UTF-8");
            if (!atomicWrite(file(), b)) return false;
            atomicWrite(bak(), b);
            dbFresh = false;                          /* 이제 디스크의 파일이 이 기록이다 */
            return true;
        } catch (Exception e) {
            android.util.Log.e("PocketCore", "league save failed", e);
            return false;
        }
    }

    private static boolean atomicWrite(File f, byte[] b) {
        File tmp = new File(f.getPath() + ".tmp");
        try {
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(b); o.getFD().sync(); }
            if (!tmp.renameTo(f)) throw new IOException("rename " + f);
            return true;
        } catch (Exception e) {
            android.util.Log.e("PocketCore", "league write failed " + f, e);
            tmp.delete();
            return false;
        }
    }

    /**
     * 2단계 명단 — 가상 상대 12명. 고정 id·닉네임·주캐·검질·CPU 난도·초기 점수. 닉네임은 지어낸 이름이다(실제 사람 아님).
     * 캐릭터·검질은 게임이 CPU 로 내는 24조합(무한 대전 뽑기 표 0x22F952 의 22칸 + 유가 2) 안에서만 — 0단계 «싸우지 않는 CPU».
     * 초기 점수는 실측한 난도 구간(0단계 «CPU 난도»: 1인 대전 첫 경기 CPU 단계 1/4/7 · 배율 하 00C0/0140 · 중 00D9/0126 · 상 00F3/010C)에
     * 맞춰 쉬움 880~940 · 보통 970~1030 · 어려움 1060~1120 으로 둔다(사용자 시작 1000). 같은 점수는 없다(나와 칼끝노인 1000 만 같음 —
     * 최초 동점은 고정 id 순, 나 먼저). o01 은 1단계와 같은 상대다(1단계 기록을 그대로 잇는다).
     */
    static final Object[][] ROSTER = {
        { "o01", "칼끝노인",   11, 0, 1, 1000 },   /* 우쿄 · 수라 */
        { "o02", "퇴근후한판",  3, 1, 1, 1020 },   /* 겐쥬로 · 나찰 */
        { "o03", "달빛나코",    4, 0, 0,  940 },   /* 나코루루 · 수라 */
        { "o04", "새벽검객",    2, 0, 1,  980 },   /* 하오마루 · 수라 */
        { "o05", "장미기사",    9, 1, 1, 1030 },   /* 샬럿 · 나찰 */
        { "o06", "얼음요정",    5, 0, 0,  920 },   /* 리무루루 · 수라 */
        { "o07", "그늘닌자",    6, 0, 0,  900 },   /* 한조 · 수라 */
        { "o08", "독수리산책",  7, 0, 0,  880 },   /* 갈포드 · 수라 */
        { "o09", "불꽃형님",    0, 1, 2, 1060 },   /* 카즈키 · 나찰 */
        { "o10", "검은달",      8, 1, 2, 1080 },   /* 아수라 · 나찰 */
        { "o11", "꽃잎칼날",   13, 0, 2, 1100 },   /* 시키 · 수라 */
        { "o12", "끝판왕",     14, 1, 2, 1120 },   /* 유가 · 나찰 */
    };
    static final int ROSTER_VER = 2;

    private static JSONObject fresh() {
        try {
            JSONObject j = new JSONObject();
            j.put("v", 1);
            JSONObject me = new JSONObject();
            me.put("name", "나"); me.put("score", 1000);
            me.put("w", 0); me.put("l", 0); me.put("d", 0); me.put("streak", 0); me.put("best", 0);
            j.put("me", me);
            j.put("opps", new JSONArray());                 /* 명단은 upgrade 가 ROSTER 순서(고정 id 순)로 채운다 */
            j.put("matches", new JSONArray());
            return j;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** 1단계 경기 기록(시간 순)을 다시 훑어 상대별 «첫 승» 경기 표시와 «연승을 끊은 상대»를 채운다 — 2단계 apply 와 같은 규칙. */
    private static void backfill(JSONObject j) throws Exception {
        JSONArray ms = j.optJSONArray("matches"), os = j.optJSONArray("opps");
        java.util.Map<String, JSONObject> byId = new java.util.HashMap<>();
        for (int i = 0; i < os.length(); i++) byId.put(os.optJSONObject(i).optString("id"), os.optJSONObject(i));
        java.util.Set<String> won = new java.util.HashSet<>();
        for (int i = 0; i < ms.length(); i++) {
            JSONObject m = ms.optJSONObject(i), o = byId.get(m.optString("opp"));
            String st = m.optString("status"), out = m.optString("outcome");
            if (o == null || !("settled".equals(st) || "forfeit".equals(st))) continue;
            if ("W".equals(out)) {
                if (won.add(o.optString("id"))) { m.put("first_win", true); o.put("first_win_at", m.optLong("settled")); }
                o.remove("broke_streak"); o.remove("broke_at");
            } else if ("L".equals(out) && m.optInt("broke_streak") >= 2) {
                o.put("broke_streak", m.optInt("broke_streak")); o.put("broke_at", m.optLong("settled"));
            }
        }
    }

    /** 명단에 없는 상대를 ROSTER 순서로 뒤에 더한다(1단계 기록 → 2단계). 있는 상대는 건드리지 않는다. 바뀌었으면 true. */
    private static boolean upgrade(JSONObject j) {
        try {
            boolean changed = false;
            JSONArray a = j.optJSONArray("opps");
            if (a == null) { a = new JSONArray(); j.put("opps", a); changed = true; }
            if (j.optJSONArray("matches") == null) { j.put("matches", new JSONArray()); changed = true; }
            java.util.Set<String> have = new java.util.HashSet<>();
            for (int i = 0; i < a.length(); i++) have.add(a.optJSONObject(i).optString("id"));
            boolean added = false;
            for (Object[] r : ROSTER) {
                if (have.contains((String) r[0])) continue;
                a.put(opp((String) r[0], (String) r[1], (Integer) r[2], (Integer) r[3], (Integer) r[4], (Integer) r[5]));
                changed = added = true;
            }
            JSONArray ms = j.optJSONArray("matches");
            if (added && !have.isEmpty()) {
                /* 옛 명단에서 시작한 채 끝나지 않은 경기 — 시작 순위가 옛 명단(2명) 기준이라 결과 창에 엉뚱한 순위 변화가 나온다.
                   지워 두면 정산 때 지금 명단 기준으로 다시 잰다(점수 구간은 시작 점수로 그대로). */
                for (int i = 0; i < ms.length(); i++) {
                    JSONObject m = ms.optJSONObject(i);
                    String st = m.optString("status");
                    if (("ready".equals(st) || "playing".equals(st) || "resume".equals(st)) && m.has("rank_before")) m.remove("rank_before");
                }
            }
            if (j.optInt("roster") < ROSTER_VER) {
                backfill(j);                                 /* 1단계 기록에서 재대결 표시를 되살린다 */
                j.put("roster", ROSTER_VER); changed = true;
            }
            return changed;
        } catch (Exception e) { return false; }
    }

    private static JSONObject opp(String id, String nick, int ch, int style, int diff, int score) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("nick", nick); o.put("char", ch); o.put("style", style); o.put("diff", diff);
        o.put("score", score);
        o.put("w", 0); o.put("l", 0); o.put("d", 0);     /* 이 상대의 전적 = 나와의 상대전적(가상 상대끼리는 경기하지 않는다 — 기획서 3장) */
        return o;
    }

    /* ── 조회 ─────────────────────────────────────────────────────────── */

    static JSONObject me() { return db().optJSONObject("me"); }

    /* ── 배경·컬러 고르기(게임 속 리그의 B·옵션, 2단계 «도전»도 같은 값) ── */
    private static JSONObject pref() {
        JSONObject p = db().optJSONObject("pref");
        if (p == null) { p = new JSONObject(); try { db().put("pref", p); } catch (Exception ignored) { } }
        return p;
    }
    static synchronized int prefStage() { int v = pref().optInt("stage", -1); return v >= -2 && v < STAGES.length ? v : -1; }
    /** 옛 리그 «내 컬러»(리그 판 전체에 하나) — 이제 컬러는 캐릭터마다(Look, 앱 설정)라 Look.migrate 가 처음 한 번만 읽는다 */
    static synchronized int prefColor() { int v = pref().optInt("color", 0); return v >= 0 && v < COLORS.length ? v : 0; }
    /** 고른 배경만 남긴다(컬러는 Look — pref.color 는 더 안 쓴다, 남아 있어도 무시) */
    static synchronized void setPrefStage(int stageSel) {
        try {
            JSONObject p = pref();
            if (p.optInt("stage", -1) == stageSel) return;
            p.put("stage", stageSel);
            save();                                     /* 실패해도 메모리 값으로 쓰고 다음 저장 때 같이 들어간다 */
        } catch (Exception ignored) { }
    }
    /** 마지막으로 리그를 연 원래 롬 경로(pref.last_rom) — 롬 경로 없이 리그 화면에 들어왔을 때 쓴다. 없으면 null(pref 칸을 만들지 않는다) */
    static synchronized String lastRom() {
        JSONObject p = db().optJSONObject("pref");
        String s = p != null ? p.optString("last_rom", "") : "";
        return s.isEmpty() ? null : s;
    }
    /** 리그를 연 원래 롬 경로를 남긴다 — 바뀌었을 때만 저장(같으면 파일을 안 건드린다) */
    static synchronized boolean setLastRom(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            JSONObject p = pref();
            if (path.equals(p.optString("last_rom", null))) return true;
            p.put("last_rom", path);
            return save();                              /* 실패해도 메모리 값으로 쓰고 다음 저장 때 같이 들어간다 */
        } catch (Exception e) { return false; }
    }
    static synchronized void setPrefLook(int stageSel, int color) {
        try {
            JSONObject p = pref();
            if (p.optInt("stage", -1) == stageSel && p.optInt("color", 0) == color) return;
            p.put("stage", stageSel); p.put("color", color);
            save();                                     /* 실패해도 메모리 값으로 쓰고 다음 저장 때 같이 들어간다 */
        } catch (Exception ignored) { }
    }
    /* ── 153 랭크 «내 캐릭터»(pref.main) — 랭크에 처음 들어와 게임 고르기 화면에서 한 번 고른 것(nativeHubMyPick).
       {v:1, cell 0~13 · 15 가운데 무작위 칸, ch 실제 캐릭터(무작위면 마지막에 뽑힌 것), sty, b2p(165 부터 늘 false · 읽을 때 무시 — 옛 판이 읽어도 깨지지 않게 칸만),
        k1, k2(카드 칸 0~3 · −1 없음), colour 0~17(Look.NAMES), at}. 베기 색은 여기 없다(Look 전역 값 하나가 출처).
       pref 칸에 더한다 — league.json v 1 그대로(147/148/152 는 모르는 칸을 읽고 다시 써도 남긴다). 다음 판부터 native 가 이걸로 대신 고른다 ── */
    static final class Main { int cell, ch, sty, k1 = -1, k2 = -1, colour; long at; }
    /** 저장된 내 캐릭터 — 없거나 값이 깨졌으면 null(첫 판처럼 직접 고른다) */
    static synchronized Main main() {
        JSONObject p = db().optJSONObject("pref"), j = p != null ? p.optJSONObject("main") : null;
        return j != null ? mainOf(j) : null;
    }
    static Main mainOf(JSONObject j) {
        if (j == null || j.optInt("v") != 1) return null;
        Main m = new Main();
        m.cell = j.optInt("cell", -1); m.ch = j.optInt("ch", -1); m.sty = j.optInt("sty", -1);   /* b2p 는 안 읽는다(옛 true = 보통 색) */
        m.k1 = j.optInt("k1", -1); m.k2 = j.optInt("k2", -1); m.colour = j.optInt("colour", 0); m.at = j.optLong("at");
        if (!(m.cell >= 0 && m.cell <= 13 || m.cell == 15) || (m.sty != 0 && m.sty != 1)) return null;
        if (m.cell == 15) { if (m.ch < 0 || m.ch > 14) m.ch = -1; }        /* 무작위 칸 — 뽑힌 캐릭터는 아직 모를 수 있다(1라운드에 다시) */
        else if (m.ch != m.cell) return null;
        if (m.k1 < -1 || m.k1 > 3 || m.k2 < -1 || m.k2 > 3) { m.k1 = -1; m.k2 = -1; }
        if (m.colour < 0 || m.colour >= Look.NIDX || m.colour == 1) m.colour = 0;
        return m;
    }
    /** nativeHubMyPick [있음, 칸, 캐릭터, 검질, (늘 0), 카드 1, 2, 컬러] → pref.main(b2p 는 늘 false). 같으면 파일을 안 건드린다. 저장 실패면 false(메모리 값은 남아 다음 저장 때 같이) */
    static synchronized boolean setMain(int[] mp) {
        if (mp == null || mp.length < 8 || mp[0] != 1) return false;
        try {
            JSONObject j = new JSONObject().put("v", 1).put("cell", mp[1]).put("ch", mp[2]).put("sty", mp[3])
                    .put("k1", mp[5]).put("k2", mp[6]).put("colour", mp[7]);
            Main m = mainOf(j);
            if (m == null) return false;
            Main old = main();
            JSONObject oj = pref().optJSONObject("main");
            if (old != null && old.cell == m.cell && old.ch == m.ch && old.sty == m.sty && old.k1 == m.k1 && old.k2 == m.k2 && old.colour == m.colour
                    && oj != null && !oj.optBoolean("b2p")) return true;   /* 옛 b2p true 가 남아 있으면 한 번 false 로 고쳐 쓴다 */
            pref().put("main", new JSONObject().put("v", 1).put("cell", m.cell).put("ch", m.ch).put("sty", m.sty).put("b2p", false)
                    .put("k1", m.k1).put("k2", m.k2).put("colour", m.colour).put("at", System.currentTimeMillis()));   /* 고른 값(범위 밖은 없음·기본으로) */
            return save();
        } catch (Exception e) { return false; }
    }
    /** «캐릭터 바꾸기» — 지우면 다음 판에 다시 직접 고르고, 그걸 새로 남긴다 */
    static synchronized boolean clearMain() {
        JSONObject p = db().optJSONObject("pref");
        if (p == null || !p.has("main")) return true;
        p.remove("main");
        return save();
    }
    /** «샬럿 나찰 · 다홍» — colour 는 지금 보일 컬러(Look 캐릭터 기억), 모르면 −1(빼고) */
    static String mainLabel(Main m, int colour) {
        if (m == null) return "";
        String s = m.cell == 15 ? "무작위 칸" : CHAR[m.ch] + " " + STYLE[m.sty];
        return colour >= 0 && colour < COLORS.length ? s + " · " + COLORS[colour] : s;
    }
    /** 랭크 화면·VS 띠·이름표·결판 띠·순위표에 쓰는 내 이름 — 기록의 me.name(없으면 «나») */
    static String myNick() {
        JSONObject me = me();
        String n = me != null ? cleanName(me.optString("name", "")) : "";
        return n.isEmpty() ? "나" : n;
    }
    /** 168(11) 내 이름 길이 — 이름표(62px)·순위표(54px) 칸에 «…» 없이 드는 갈무리 글자 수 근처 */
    static final int NAME_MAX = 8;
    /** 내 이름으로 쓸 수 있는 글자만 — KS X 1001 한글 2,350자·영문·숫자·밑줄·붙임표·마침표(앱 픽셀 글꼴 갈무리7·11·11B 가 다 가진 것 —
     *  assets/fonts/README: «ASCII + KS X 1001 한글 2,350자») · 앞뒤·겹친 빈칸 정리 · NAME_MAX 자.
     *  다른 글자(KS X 1001 밖 음절 «똠»·이모지·한자·자모 따로 등)는 뺀다 — 덧판·이름표는 «?», VS 띠는 시스템 글꼴로 그려져 픽셀 글꼴이 깨지기 때문 */
    static String cleanName(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        boolean sp = false;
        for (int i = 0; i < raw.length() && b.length() < NAME_MAX; ) {
            int cp = raw.codePointAt(i); i += Character.charCount(cp);
            boolean ok = (cp >= 0xAC00 && cp <= 0xD7A3 && ksHangul(cp)) || (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')
                    || cp == '_' || cp == '-' || cp == '.';
            if (cp == ' ' || cp == 0x3000) { if (b.length() > 0) sp = true; continue; }
            if (!ok) continue;
            if (sp && b.length() < NAME_MAX - 1) b.append(' ');
            sp = false;
            b.appendCodePoint(cp);
        }
        return b.toString().trim();
    }
    /** KS X 1001 완성형 한글 2,350자인가(EUC-KR 로 쓸 수 있는 음절) — 글꼴 셋이 모두 가진 음절. EUC-KR 이 없는 JVM 이면 모든 음절을 받는다 */
    private static boolean ksHangul(int cp) {
        try { return java.nio.charset.Charset.forName("EUC-KR").newEncoder().canEncode((char) cp); }
        catch (Exception e) { return true; }
    }
    /** 내 이름 바꾸기 — 다듬은 값(비면 «나»)을 me.name 에. 같으면 파일을 안 건드린다. 저장 실패면 false(메모리는 되돌림) */
    static synchronized boolean setMyName(String raw) {
        String n = cleanName(raw);
        if (n.isEmpty()) n = "나";
        try {
            JSONObject me = me();
            if (me == null) { me = new JSONObject(); db().put("me", me); }
            String old = me.optString("name", "나");
            if (n.equals(old)) return true;
            me.put("name", n);
            if (!save()) { me.put("name", old); return false; }
            return true;
        } catch (Exception e) { return false; }
    }

    /** 고른 배경을 실제 배경(0~7)으로 — 상대 기본·무작위 풀기 */
    static int resolveStage(int sel, String oppId) {
        if (sel == -2) return new java.util.Random().nextInt(STAGES.length);
        if (sel >= 0 && sel < STAGES.length) return sel;
        return homeStage(oppId);
    }
    /** 경기의 배경·컬러(기록·이어 하기용). 끝난 경기는 안 바꾼다 */
    static synchronized void setLook(String id, int stage, int color) { setLook(id, stage, color, null); }
    /** 컬러 판 cv 2(16가지 + 내 색) — 내 색(16·17)이면 그 경기의 세 색도(tri — 나중에 내 색을 고쳐도 기록은 그대로). cv 없는 옛 기록 = 컬러 4개 시절 */
    static synchronized void setLook(String id, int stage, int color, int[] tri) {
        JSONObject m = matchById(id);
        if (m == null || terminal(m.optString("status"))) return;
        try {
            JSONObject lk = m.optJSONObject("look");
            boolean custom = tri != null && tri.length >= 3 && color >= Look.NPRE;
            JSONArray ta = lk != null ? lk.optJSONArray("tri") : null;
            boolean sameTri = custom ? ta != null && ta.length() >= 3 && ta.optInt(0) == tri[0] && ta.optInt(1) == tri[1] && ta.optInt(2) == tri[2] : ta == null;
            if (lk != null && lk.optInt("stage", -9) == stage && lk.optInt("color", -9) == color && lk.optInt("cv") == 2 && sameTri) return;
            JSONObject n = new JSONObject().put("stage", stage).put("color", color).put("cv", 2);
            if (custom) n.put("tri", new JSONArray().put(tri[0] & 0xFFF).put(tri[1] & 0xFFF).put(tri[2] & 0xFFF));
            m.put("look", n);
            save();
        } catch (Exception ignored) { }
    }
    static JSONArray opps() { return db().optJSONArray("opps"); }
    static JSONArray matches() { return db().optJSONArray("matches"); }
    /** 랭크 경기는 ranked.matches 에 따로 — 같은 폴더를 쓰는 예전 앱(랭크 이전)은 이 칸을 모르니 열린 랭크 경기를 예전 규칙으로
     *  정산·기권하려다 막히지 않는다(예전 앱은 모르는 칸을 그대로 다시 쓴다) */
    static synchronized JSONArray rmatches() {
        JSONObject r = ranked();
        JSONArray a = r.optJSONArray("matches");
        if (a == null) { a = new JSONArray(); try { r.put("matches", a); } catch (Exception ignored) { } }
        return a;
    }
    /** 아직 ranked 칸이 없으면 만들지 않고 빈 목록(찾기만 할 때) */
    private static JSONArray rmatchesIfAny() {
        JSONObject r = db().optJSONObject("ranked");
        JSONArray a = r != null ? r.optJSONArray("matches") : null;
        return a != null ? a : new JSONArray();
    }

    static JSONObject oppById(String id) {
        JSONArray a = opps();
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optJSONObject(i).optString("id"))) return a.optJSONObject(i);
        return null;
    }

    static JSONObject matchById(String id) {
        if (id == null) return null;
        for (JSONArray a : new JSONArray[]{ matches(), rmatchesIfAny() })
            for (int i = a.length() - 1; i >= 0; i--) if (id.equals(a.optJSONObject(i).optString("id"))) return a.optJSONObject(i);
        return null;
    }

    /** 끝나지 않은 경기(준비·진행·복귀 대기) — 있으면 새 도전 전에 재개/기권을 고르게 한다. */
    static JSONObject openMatch() {
        for (JSONArray a : new JSONArray[]{ matches(), rmatchesIfAny() })
            for (int i = a.length() - 1; i >= 0; i--) {
                String s = a.optJSONObject(i).optString("status");
                if ("ready".equals(s) || "playing".equals(s) || "resume".equals(s)) return a.optJSONObject(i);
            }
        return null;
    }

    /** 받침 따라 「과/와」 — 「칼끝노인과」 · 「달빛나코와」. 한글이 아니면 「와」. */
    static String gwa(String s) {
        if (s == null || s.isEmpty()) return "";
        char c = s.charAt(s.length() - 1);
        return s + ((c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 != 0) ? "과" : "와");
    }

    static String oppLine(JSONObject o) {
        return CHAR[o.optInt("char")] + " · " + STYLE[o.optInt("style")] + " · 난도 " + DIFF[o.optInt("diff")];
    }

    /* ── 경기 ─────────────────────────────────────────────────────────── */

    /** 끝난 상태 — 여기서는 다른 상태로 못 바꾼다(정산 완료·기권·오류 보류·무효). */
    static boolean terminal(String s) {
        return "settled".equals(s) || "forfeit".equals(s) || "hold".equals(s) || "cancelled".equals(s);
    }

    /** 시작 전에 경기 id·설정·시작 점수를 영속 저장한다(기획서 «중단과 복원»·«시작 시점 두 점수로 구간 고정»).
     *  이미 열린 경기가 있거나 저장에 실패하면 null — 시작하지 않는다. */
    static synchronized JSONObject newMatch(String oppId) {
        try {
            if (openMatch() != null) return null;
            JSONObject o = oppById(oppId);
            if (o == null || !oppSupported(o.optInt("char"), o.optInt("style"))) return null;   /* 미지원 대진은 시작하지 않는다 */
            JSONObject m = new JSONObject();
            m.put("id", java.util.UUID.randomUUID().toString());
            m.put("opp", oppId);
            m.put("created", System.currentTimeMillis());
            m.put("status", "ready");
            m.put("game", "ss2");                       /* 게임 id(기획서 4장 «실행 버전») — 롬 md5·코어는 시작 때 EmuActivity 가 적는다 */
            m.put("side", "P1");                        /* 1P PLAY 에서 사람은 늘 P1 블록 */
            JSONObject rule = new JSONObject();
            rule.put("char", o.optInt("char")); rule.put("style", o.optInt("style")); rule.put("diff", o.optInt("diff"));
            rule.put("points", POINTS); rule.put("time", TIME);
            rule.put("cards", "all");                   /* 전원 올카드 해금(기획서 2장) — CPU 도 자기 카드 두 장(155 · 센 상대는 ★ 높은 카드) */
            m.put("rule", rule);
            m.put("start_score", new JSONObject().put("me", me().optInt("score")).put("opp", o.optInt("score")));
            m.put("rank_before", rankOf("me"));
            matches().put(m);
            if (!save()) { matches().remove(matches().length() - 1); return null; }
            return m;
        } catch (Exception e) { return null; }
    }

    /** 상태 바꾸기 — 경기 id 로 찾는다(메모리를 되돌린 뒤에도 옛 객체에 쓰지 않게). 끝난 경기는 안 바꾼다. */
    static synchronized boolean setStatus(String id, String status, String why) {
        JSONObject m = matchById(id);
        if (m == null || terminal(m.optString("status"))) return false;
        try {
            m.put("status", status);
            if ("playing".equals(status)) { m.remove("resume_fail"); m.remove("suspend"); }   /* 다시 이어 하게 됐다 — 지난 이어 하기 실패·사용자 멈춤 표시(voidKind 가 보는)는 지운다(168 K3) */
            if (why != null) {
                if (why.isEmpty()) { m.remove("why"); m.remove("fix"); }   /* 빈 사유 = 지난 사유를 지운다 */
                else m.put("why", why);
            }
            m.put("updated", System.currentTimeMillis());
            return save();
        } catch (Exception e) { return false; }
    }

    /** 리그 화면을 여는 때 «진행 중»인 경기는 게임 창이 이미 없다(앱이 경기 중에 내려갔다) — 복귀 대기로 바꾼다. */
    static synchronized void markInterrupted() {
        for (JSONArray a : new JSONArray[]{ matches(), rmatchesIfAny() })   /* 두 목록 모두 — 같은 폴더의 다른 판이 연 경기도 */
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                if ("playing".equals(m.optString("status"))) setStatus(m.optString("id"), "resume", "앱이 경기 중에 내려감");
            }
    }

    static synchronized boolean note(String id, String key, Object val) {
        JSONObject m = matchById(id);
        if (m == null) return false;
        try { m.put(key, val); return save(); } catch (Exception e) { return false; }
    }

    /** 168 §A — 사용자가 멈춘 경기(리셋·«나중에»·연습으로 갈아타기): 복귀 대기 + 사유 + 사용자 멈춤 표지 + 그때까지 먹은 치트 비트를 «한 번의 저장»으로(K3 — 두 저장 사이에
     *  죽으면 표지 없는 복귀 대기가 남아 «앱이 멈춤»으로 자동 무효될 수 있었다). 체크포인트는 부르는 쪽이 먼저 쓴다. 끝난 경기·저장 실패면 false(메모리 되돌림) */
    static synchronized boolean suspendUser(String id, String why, int cheatBits) {
        JSONObject m = matchById(id);
        if (m == null || terminal(m.optString("status"))) return false;
        String backup = db.toString();
        try {
            m.put("status", "resume"); m.put("why", why); m.put("suspend", "user");
            if (cheatBits != 0) m.put("cheat_bits", m.optInt("cheat_bits") | cheatBits);
            m.put("updated", System.currentTimeMillis());
            if (!save()) { db = new JSONObject(backup); return false; }
            return true;
        } catch (Exception e) {
            try { db = new JSONObject(backup); } catch (Exception ignored) { }
            return false;
        }
    }
    /** 사용자가 스스로 멈춘 경기인가(K3·K4) — 표지 «user» · 복귀 대기 · 경기를 시작한 앱 판 그대로(앱이 바뀌어 체크포인트를 못 읽는 건 사용자 탓이 아니다).
     *  이런 경기는 «무효로 닫기»가 없고(이어 하기·기권만), 리그를 다시 열어도 바로 이어 하지 않고 타이틀에서 A 를 기다린다 */
    static boolean userSuspended(JSONObject m) {
        if (m == null || !"user".equals(m.optString("suspend")) || !"resume".equals(m.optString("status"))) return false;
        return appVer <= 0 || !m.has("app") || m.optInt("app") == appVer;
    }
    /** 168 §9 — 이 경기에 먹은 치트 비트를 더한다(1 << Cheats 번호). 끝난 경기는 그대로. 같으면 파일을 안 건드린다 */
    static synchronized boolean orCheat(String id, int bits) {
        if (bits == 0) return true;
        JSONObject m = matchById(id);
        if (m == null || terminal(m.optString("status"))) return false;
        int o = m.optInt("cheat_bits");
        if ((o | bits) == o) return true;
        try { m.put("cheat_bits", o | bits); return save(); } catch (Exception e) { return false; }
    }

    /** 오류 보류 — 원인과 복구 방법을 같이 남긴다(기획서 «판정 오류는 보류하고 원인과 복구 방법을 기록»). 점수는 안 움직인다. */
    static synchronized boolean hold(String id, String why, String fix) {
        JSONObject m = matchById(id);
        if (m == null || terminal(m.optString("status"))) return false;
        String backup = db.toString();                     /* 저장이 실패하면 메모리를 되돌린다(끝난 상태로 굳어 다시 못 바꾸는 일이 없게) */
        try {
            m.put("status", "hold"); m.put("why", why); m.put("fix", fix);
            m.put("updated", System.currentTimeMillis());
            if (isRanked(m)) releasePending(m.optString("opp"));   /* 보류는 잡힌 상대를 푼다 — 같은 원인(손잡이 불일치 등)으로 같은 상대에게 되풀이해 막히지 않게 */
            if (!save()) { db = new JSONObject(backup); return false; }
            ckptDelete(id);
            return true;
        } catch (Exception e) {
            try { db = new JSONObject(backup); } catch (Exception ignored) { }
            return false;
        }
    }

    /** 무효 — 아직 대진 전(준비)에 멈춘 경기. 대진 준비 실패는 점수에 넣지 않는다. 사용자가 스스로 뒤로 간 것 — 잡힌 상대 miss 아님 */
    static synchronized boolean cancel(String id, String why) { return cancelReady(id, why, false); }
    /** 무효(남은 판) — 대진 전에 앱이 죽어 남은 «준비» 경기. cancel 과 같고 잡힌 상대 miss 하나(두 번째면 풂 — 같은 상대가 끝없이 막지 않게) */
    static synchronized boolean cancelStale(String id, String why) { return cancelReady(id, why, true); }
    private static boolean cancelReady(String id, String why, boolean stale) {
        JSONObject m = matchById(id);
        if (m == null || !"ready".equals(m.optString("status"))) return false;
        String backup = db.toString();                     /* 저장 실패면 «준비» 그대로 — 다시 시도하거나 그대로 경기를 이어 할 수 있게 */
        try {
            m.put("status", "cancelled"); m.put("why", why); m.put("updated", System.currentTimeMillis());
            if (stale && isRanked(m)) pendingMiss(m.optString("opp"));
            if (!save()) { db = new JSONObject(backup); return false; }
            ckptDelete(id);
            return true;
        } catch (Exception e) {
            try { db = new JSONObject(backup); } catch (Exception ignored) { }
            return false;
        }
    }

    /** 이어 하기 실패 종류 — "nockpt"(체크포인트 없음) · "badckpt"(불러오지 못함) · "rom"(롬·코어가 안 맞아 막힘 — leagueVersionProblem·로드 실패).
     *  voidKind 가 한글 사유(why) 대신 이걸 본다. null = 지움. 이어 하기가 되면(setStatus playing) 저절로 지워진다 */
    static synchronized boolean setResumeFail(String id, String kind) {
        JSONObject m = matchById(id);
        if (m == null || terminal(m.optString("status"))) return false;
        try {
            if (kind == null ? !m.has("resume_fail") : kind.equals(m.optString("resume_fail", null))) return true;
            if (kind == null) m.remove("resume_fail"); else m.put("resume_fail", kind);
            return save();                                  /* 실패해도 메모리 값으로 쓰고 다음 저장 때 같이 들어간다(note 와 같게) */
        } catch (Exception e) { return false; }
    }

    /** 시작한 경기를 «무효로 닫고 계속» 할 수 있나 — 앱 탓일 때만: 이어 할 자리가 없음(앱이 죽음) "crash" · 자리를 못 불러옴 "ckpt"
     *  · 롬·코어로 막혔는데(resume_fail rom) 경기를 시작한 앱 판이 다름 "app". 준비(대진 전)·끝난 경기·이어 할 수 있는 경기·롬만 바뀐 경기(같은 앱)·
     *  앱만 바뀐 경기(체크포인트·롬 멀쩡 — 이어 하면 된다)는 null(롬 바꾸기·앱 업데이트만으로는 무효가 안 열린다).
     *  이어 하기 실패 표시는 복귀 대기일 때만 본다(진행 중이면 그 뒤 다시 이어 한 판 — 옛 판이 남긴 표시). 게임 속 리그가 지금 하는 판(진행 중 · liveHub)은 null.
     *  appVer ≤ 0(모름)이면 판은 안 본다 */
    static String voidKind(JSONObject m, int appVer) {
        if (m == null) return null;
        if (userSuspended(m)) return null;                    /* 168 K3: 사용자가 멈춘 판 — 이어 하기·기권만(지는 판을 멈추고 파일을 지워 빠지지 못하게) */
        String s = m.optString("status");
        if (!"playing".equals(s) && !"resume".equals(s)) return null;
        if ("playing".equals(s) && liveHub) return null;      /* 체크포인트는 onPause 에서야 생긴다 — 지금 하는 판을 «앱이 멈춤»으로 보지 않게 */
        String rf = "resume".equals(s) ? m.optString("resume_fail", "") : "";
        if ("badckpt".equals(rf)) return "ckpt";
        if ("nockpt".equals(rf) || !ckpt(m.optString("id")).exists()) return "crash";
        if ("rom".equals(rf) && appVer > 0 && m.has("app") && m.optInt("app") != appVer) return "app";
        return null;
    }
    /** 무효(시작한 뒤) 사유 — 리그 화면 «보류·무효 경기» 줄에 그대로 */
    static String voidWhy(String kind) {
        return "app".equals(kind) ? "앱이 바뀌어 닫음" : "ckpt".equals(kind) ? "이어할 자리를 불러오지 못해 닫음" : "앱이 멈춰 이어할 자리가 없음";
    }
    /** 무효(시작한 뒤) — 앱 탓으로 이어 할 수 없는 경기를 닫는다. LP·MMR·전적·배치·상대 전적은 그대로(정산하지 않는다).
     *  끝난 상태 cancelled + void_after_start·void_kind(147 은 랭크 무효로 보고 숨긴다). 체크포인트는 지우고, 잡힌 상대 miss 하나(두 번째면 풂).
     *  저장이 실패하면 메모리를 되돌리고 false(체크포인트도 그대로) */
    static synchronized boolean voidStarted(String id, String kind) {
        JSONObject m = matchById(id);
        if (m == null) return false;
        String s = m.optString("status");
        if (!"playing".equals(s) && !"resume".equals(s)) return false;
        if ("playing".equals(s) && liveHub) return false;      /* 게임 속 리그가 지금 하는 판(voidKind 와 같게) */
        if (userSuspended(m)) return false;                   /* 168 K3: 마지막 관문 — 사용자가 멈춘 판은 무효로 닫지 않는다 */
        String k = "app".equals(kind) || "ckpt".equals(kind) ? kind : "crash";
        String backup = db.toString();
        try {
            m.put("status", "cancelled"); m.put("void_after_start", true); m.put("void_kind", k);
            m.put("why", voidWhy(k)); m.remove("fix");      /* «원래 판으로 되돌리면 …» 같은 지난 안내는 더 안 맞는다 */
            m.put("updated", System.currentTimeMillis());
            if (isRanked(m)) pendingMiss(m.optString("opp"));
            if (!save()) { db = new JSONObject(backup); return false; }
            ckptDelete(id); new File(ckpt(id).getPath() + ".tmp").delete(); new File(ckpt(id).getPath() + ".tmp.look").delete();
            return true;
        } catch (Exception e) {
            try { db = new JSONObject(backup); } catch (Exception ignored) { }
            return false;
        }
    }
    /** 리그 화면을 열 때 남은 «준비»(대진 전에 앱이 죽은) 경기를 무효로 — 남은 판이라 잡힌 상대 miss 하나씩. 닫은 수.
     *  게임 속 리그가 살아 있으면(liveHub — 그 «준비»는 지금 쓰는 중) 안 건드리고 0 */
    static synchronized int voidReady(String why) {
        if (liveHub) return 0;
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JSONArray a : new JSONArray[]{ matches(), rmatchesIfAny() })
            for (int i = 0; i < a.length(); i++)
                if ("ready".equals(a.optJSONObject(i).optString("status"))) ids.add(a.optJSONObject(i).optString("id"));
        int n = 0;
        for (String id : ids) if (cancelStale(id, why)) n++;
        return n;
    }

    /** 점수 구간 — 경기 시작 시점 두 점수로 고정(기획서 3장 표). 내 변화량의 반대를 상대에게. */
    static int delta(int myScore, int oppScore, String outcome) {
        int gap = oppScore - myScore;
        if ("D".equals(outcome)) return 0;
        boolean win = "W".equals(outcome);
        if (gap >= 101)  return win ? 18 : -6;
        if (gap <= -101) return win ? 6 : -18;
        return win ? 12 : -12;
    }

    /* ── 순위 ─────────────────────────────────────────────────────────── */

    /** 지금 순위(id 목록, 1위부터). 점수 내림차순, 동점이면 **직전 순위 그대로**, 처음엔 고정 id 순(나 먼저). */
    static synchronized java.util.List<String> order() {
        JSONObject d = db();
        java.util.List<String> prev = new java.util.ArrayList<>();
        JSONArray po = d.optJSONArray("order");
        if (po != null) for (int i = 0; i < po.length(); i++) prev.add(po.optString(i));
        final java.util.List<String> ids = new java.util.ArrayList<>();
        ids.add("me");
        JSONArray a = opps();
        for (int i = 0; i < a.length(); i++) ids.add(a.optJSONObject(i).optString("id"));
        final java.util.Map<String, Integer> pos = new java.util.HashMap<>();
        for (int i = 0; i < ids.size(); i++) {                 /* 직전 순위에 없던 사람(새로 든 상대)은 고정 id 순으로 뒤에 */
            int k = prev.indexOf(ids.get(i));
            pos.put(ids.get(i), k >= 0 ? k : 1000 + i);
        }
        java.util.Collections.sort(ids, (x, y) -> {
            int sx = scoreOf(x), sy = scoreOf(y);
            return sx != sy ? sy - sx : pos.get(x) - pos.get(y);
        });
        return ids;
    }

    static int scoreOf(String id) {
        if ("me".equals(id)) return me().optInt("score");
        JSONObject o = oppById(id);
        return o != null ? o.optInt("score") : 0;
    }

    static int rankOf(String id) { return order().indexOf(id) + 1; }

    /** 점수가 바뀐 뒤 지금 순위를 직전 순위로 굳힌다(다음 동점 판정의 기준). save 는 호출자가. */
    private static void commitOrder() throws Exception {
        db.put("order", new JSONArray(order()));
    }

    /* ── 정산 ─────────────────────────────────────────────────────────── */

    /**
     * 정산 — 같은 경기는 한 번만. 이미 끝난 경기면 그대로 돌려준다(결과 화면 재진입·앱 재시작 중복 방지).
     * st = Emu.nativeLeagueStatus() 결판 줄. 다음이면 점수에 넣지 않고 보류한다:
     *   대진 불일치(결판 때 P2 블록 ≠ 표시한 상대) · 규칙 불일치(실효 포인트·시간 ≠ 랭크전 규칙 — 예: 무한 대전 1라운드 결판).
     * 저장에 실패하면 메모리를 되돌리고 null — 호출자가 다시 시도한다(결과는 코어 쪽에 그대로 있다).
     */
    static synchronized JSONObject settle(String matchId, int[] st) { return settle(matchId, st, 0); }
    /** 치트 번호(cheat.h CH_* = Cheats.TIME·DIFF) — League 는 Cheats(설정 묶음)를 안 끌어온다(JVM 시험이 League 만 따로 엮는다) */
    static final int CH_TIME = 1, CH_DIFF = 7;
    /** 168 §9 — cheatBits = 이 판에 먹은 치트(Emu.nativeCheatMatch · 1 << Cheats 번호) | 경기에 적힌 cheat_bits. 치트 판도 LP·전적은 그대로 반영하고(D1)
     *  «cheat» 표를 남긴다. 검사 면제는 그 치트가 설명하는 것만: 시간 무한(TIME) → 실효 시간 0x80 · CPU 세기(DIFF) → 손잡이·난도 확인. 대진·포인트·결판 비트는 그대로 */
    static synchronized JSONObject settle(String matchId, int[] st, int cheatBits) {
        JSONObject m = matchById(matchId);
        if (m == null) return null;
        if (terminal(m.optString("status"))) return m;
        String backup = db.toString();
        try {
            int cb = cheatBits | m.optInt("cheat_bits");
            boolean cTime = (cb & (1 << CH_TIME)) != 0, cDiff = (cb & (1 << CH_DIFF)) != 0;
            if (cb != 0) { m.put("cheat", cb); m.put("cheat_bits", cb); }
            JSONObject rule = m.optJSONObject("rule");
            boolean rk = isRanked(m);
            JSONObject o = rk ? null : oppById(m.optString("opp"));
            JSONObject me = me();
            int bits = st[5], w1 = st[6], w2 = st[7], p1 = st[10], p2 = st[11];
            int need = st[9], time = st.length > 17 ? st[17] : rule.optInt("time");
            JSONObject res = new JSONObject();
            res.put("bits", bits); res.put("w1", w1); res.put("w2", w2); res.put("ko", st[8]); res.put("need", need); res.put("time", time);
            res.put("p1blk", p1); res.put("p2blk", p2); res.put("frame", st[14]);
            res.put("start_seen", st[3] == 1); res.put("p2blk_start", st[12]); res.put("start_frame", st[13]);
            res.put("cards", String.format("%02X %02X", st.length > 15 ? st[15] : 0xFF, st.length > 16 ? st[16] : 0xFF));
            res.put("my_char", (p1 >> 4) & 0x0F); res.put("my_style", (p1 >> 3) & 1);
            if (st.length > 20) {                    /* 난도 적용 확인 — 1인 대전 첫 경기 CPU 단계 1+3×난도, 배율(하 00C0/0140 · 중 00D9/0126 · 상 00F3/010C) */
                res.put("cpu_level", st[18]);
                res.put("cpu_mult", String.format("%04X %04X", st[19], st[20]));
                res.put("diff_ok", cDiff || (rk ? rankedKnobWhy(rule, st) == null : st[18] == 1 + 3 * rule.optInt("diff")));
                if (st.length > 21) res.put("cpu_cap", st[21]);
            }
            res.put("at", System.currentTimeMillis());
            m.put("result", res);
            int want = (rule.optInt("char") << 4) | (rule.optInt("style") << 3);
            String why = null;
            if ((p2 & 0xF8) != want)
                why = String.format("대진 불일치: 요청 %s·%s, 실제 P2 블록 0x%02X", CHAR[rule.optInt("char")], STYLE[rule.optInt("style")], p2);
            else if (need != rule.optInt("points") || (time != rule.optInt("time") && !(cTime && time == 0x80)))
                why = "규칙 불일치: 실효 포인트 " + need + "·시간 " + time + " (랭크전 " + rule.optInt("points") + "·" + rule.optInt("time")
                    + ") — 1인 대전 첫 경기가 아닌 곳(무한 대전 등)에서 결판이 났습니다";
            if (why != null) {
                m.put("status", "hold"); m.put("why", why);
                m.put("fix", "점수는 그대로입니다. 새로 도전하면 1인 대전 캐릭터 고르기로 자동으로 들어갑니다.");
                if (rk) releasePending(m.optString("opp"));   /* 보류는 잡힌 상대를 푼다(hold 와 같게) */
                if (!save()) throw new IOException("save");
                ckptDelete(matchId);
                return m;
            }
            /* 결판 비트와 승수가 서로 맞을 때만 정산한다(0단계 «결과 감지 규칙»: 승자 = 낮은 비트, 무승부 결판 = 0x90 3:3).
               1인 대전 첫 경기에서 이긴 쪽은 늘 승수가 더 많고 필요 승수 이상이다. 안 맞으면 추정하지 않고 보류(기획서 3장). */
            String out = null;
            if ((bits & 0x40) != 0 && (bits & 0x20) == 0 && w1 > w2 && w1 >= need) out = "W";
            else if ((bits & 0x20) != 0 && (bits & 0x40) == 0 && w2 > w1 && w2 >= need) out = "L";
            else if ((bits & 0x60) == 0 && (bits & 0x10) != 0 && w1 == w2) out = "D";
            String why2 = null;
            if (out == null)
                why2 = String.format("결판 판정 불일치: 결과 비트 0x%02X · 승수 %d:%d · 필요 승수 %d — 승패를 정할 수 없습니다", bits, w1, w2, need);
            else if (rk) { if (!cDiff) why2 = rankedKnobWhy(rule, st); }   /* 랭크: 손잡이(단계·상한·배율)가 실제로 먹었나 — CPU 세기 치트가 덮은 판만 면제 */
            else if (!cDiff && st.length > 20 && st[18] != 1 + 3 * rule.optInt("diff"))
                why2 = "난도 불일치: CPU 단계 " + st[18] + " (요청 난도 " + DIFF[rule.optInt("diff")] + " → " + (1 + 3 * rule.optInt("diff")) + ")";
            if (why2 != null) {
                m.put("status", "hold"); m.put("why", why2);
                m.put("fix", "점수는 그대로입니다. 같은 상대에게 다시 도전하면 새 경기로 기록됩니다.");
                if (rk) releasePending(m.optString("opp"));   /* 손잡이 불일치가 그 상대 탓이면 되풀이된다 — 풀어서 다음엔 새 상대 */
                if (!save()) throw new IOException("save");
                ckptDelete(matchId);
                return m;
            }
            if (rk) applyRanked(m, out, false); else apply(m, me, o, out);
            m.put("status", "settled");
            m.put("settled", System.currentTimeMillis());
            if (rk && liveHub) noteSessionMem(m);           /* 157 G2 — 같은 한 번의 저장으로 */
            if (!save()) throw new IOException("save");
            ckptDelete(matchId);
            return m;
        } catch (Exception e) {
            try { db = new JSONObject(backup); } catch (Exception ignored) { }
            return null;
        }
    }

    /** 결과를 점수·전적·연승·순위에 반영한다(메모리만 — 저장은 호출자). 구간은 시작 때 저장한 두 점수로. */
    private static void apply(JSONObject m, JSONObject me, JSONObject o, String out) throws Exception {
        JSONObject ss = m.optJSONObject("start_score");
        int my0 = me.optInt("score"), op0 = o.optInt("score");
        int d = (ss != null) ? delta(ss.optInt("me"), ss.optInt("opp"), out) : delta(my0, op0, out);
        m.put("outcome", out);
        m.put("score_before", new JSONObject().put("me", my0).put("opp", op0));
        m.put("delta", d);
        if (!m.has("rank_before")) m.put("rank_before", rankOf("me"));
        me.put("score", my0 + d);
        o.put("score", op0 - d);
        long now = System.currentTimeMillis();
        if ("W".equals(out)) {
            if (o.optInt("l") == 0) { m.put("first_win", true); o.put("first_win_at", now); }   /* 이 상대에게 처음 이김 */
            o.remove("broke_streak"); o.remove("broke_at");          /* 설욕 — «연승을 끊은 상대»는 이기면 지운다 */
            me.put("w", me.optInt("w") + 1); o.put("l", o.optInt("l") + 1);
            me.put("streak", me.optInt("streak") + 1);
            me.put("best", Math.max(me.optInt("best"), me.optInt("streak")));
        } else if ("L".equals(out)) {
            me.put("l", me.optInt("l") + 1); o.put("w", o.optInt("w") + 1);
            if (me.optInt("streak") >= 2) {                 /* 이 상대가 내 연승(2연승 이상)을 끊었다 — 상대에도 남긴다(재대결 동기) */
                m.put("broke_streak", me.optInt("streak"));
                o.put("broke_streak", me.optInt("streak")); o.put("broke_at", now);
            }
            me.put("streak", 0);
        } else {
            me.put("d", me.optInt("d") + 1); o.put("d", o.optInt("d") + 1);
        }
        commitOrder();
        m.put("rank_after", rankOf("me"));
        /* 그 경기 직후 모습 — 결과 창을 나중에 다시 열어도 그때 값으로 보이게(내 연승·최장, 이 상대와의 전적: 내 쪽 승·패·무) */
        m.put("after", new JSONObject().put("streak", me.optInt("streak")).put("best", me.optInt("best"))
                .put("h2h_w", o.optInt("l")).put("h2h_l", o.optInt("w")).put("h2h_d", o.optInt("d")));
    }

    /** 기권패 — 시작한 경기를 사용자가 포기했을 때. 점수는 패배와 같게 움직인다. 아직 대진 전(준비)이면 cancel 을 쓴다. */
    static synchronized JSONObject forfeit(String matchId) {
        JSONObject m = matchById(matchId);
        if (m == null) return null;
        if (terminal(m.optString("status"))) return m;
        String backup = db.toString();
        try {
            forfeitMem(m);
            if (isRanked(m) && liveHub) noteSessionMem(m);  /* 157 G2 */
            if (!save()) throw new IOException("save");
            ckptDelete(matchId);
            return m;
        } catch (Exception e) {
            try { db = new JSONObject(backup); } catch (Exception ignored) { }
            return null;
        }
    }

    /** 기권 반영(메모리만 — 저장은 호출자). 먹은 치트가 적혀 있으면 «cheat» 표도 */
    private static void forfeitMem(JSONObject m) throws Exception {
        m.put("forfeit", true);
        if (isRanked(m)) applyRanked(m, "L", true); else apply(m, me(), oppById(m.optString("opp")), "L");
        if (m.optInt("cheat_bits") != 0) m.put("cheat", m.optInt("cheat_bits"));
        m.put("status", "forfeit");
        m.put("settled", System.currentTimeMillis());
    }

    /** 이 상대와의 최근 경기들(정산·기권만, 새것부터). */
    static java.util.List<JSONObject> history(String oppId, int max) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        JSONArray a = matches();
        for (int i = a.length() - 1; i >= 0 && out.size() < max; i--) {
            JSONObject m = a.optJSONObject(i);
            String s = m.optString("status");
            if (oppId.equals(m.optString("opp")) && ("settled".equals(s) || "forfeit".equals(s))) out.add(m);
        }
        return out;
    }

    /** 최근 결과 글자(새것부터) — 「승 패 패」. 이 상대와의 정산·기권 경기만. */
    static String recent(String oppId, int max) {
        StringBuilder sb = new StringBuilder();
        for (JSONObject m : history(oppId, max)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(outcomeKo(m));
        }
        return sb.toString();
    }

    /**
     * 재대결 동기 표시(기획서 3장 «처음 이긴 상대, 자주 지는 상대, 연승을 끊은 상대» · 2장 «아직 이기지 못한 상대»).
     * 상대 기록의 w/l 은 상대 쪽 수다 — 내 승 = o.l, 내 패 = o.w.
     */
    static java.util.List<String> tags(JSONObject o) {
        java.util.List<String> t = new java.util.ArrayList<>();
        int myW = o.optInt("l"), myL = o.optInt("w");
        if (myW == 0 && myL > 0) t.add("아직 못 이긴 상대");
        else if (myL >= 3 && myL > myW) t.add("자주 지는 상대");
        java.util.List<JSONObject> last = history(o.optString("id"), 1);
        if (!last.isEmpty() && last.get(0).optBoolean("first_win")) t.add("처음 이긴 상대");
        if (o.has("broke_streak")) t.add("내 " + o.optInt("broke_streak") + "연승을 끊은 상대");
        return t;
    }

    /** 주변 순위 상대(기획서 3장 «주변 순위의 상대를 우선») — 내 위로 k명·아래로 k명, 순위 순. 맨 위·맨 아래면 반대쪽에서 채운다. */
    static java.util.List<String> neighbors(int k) {
        java.util.List<String> ord = order();
        int me = ord.indexOf("me");
        int lo = Math.max(0, me - k), hi = Math.min(ord.size() - 1, me + k);
        while (hi - lo < 2 * k && (lo > 0 || hi < ord.size() - 1)) {
            if (lo > 0) lo--; else hi++;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = lo; i <= hi; i++) if (i != me) out.add(ord.get(i));
        return out;
    }

    /** 결과 뒤 «다음 상대» 추천 — 바로 위 순위(없으면 바로 아래)에서 방금 상대가 아닌 사람. */
    static String nextOpp(String lastOpp) {
        java.util.List<String> ord = order();
        int me = ord.indexOf("me");
        for (int i = me - 1; i >= 0; i--) if (!ord.get(i).equals(lastOpp)) return ord.get(i);
        for (int i = me + 1; i < ord.size(); i++) if (!ord.get(i).equals(lastOpp)) return ord.get(i);
        return null;
    }

    /** 도전할 수 없는 이유(없으면 null) — 실제로 호출 가능한 상대만 도전 버튼을 켠다(기획서 3장). 롬 확인은 화면 쪽에서. */
    static String blockedWhy(JSONObject o) {
        if (dbProblem != null) return dbProblem;
        if (o == null) return "상대 기록이 없습니다";
        if (!oppSupported(o.optInt("char"), o.optInt("style"))) return "이 캐릭터·검질은 CPU 가 싸우지 않아 지원하지 않습니다";
        if (openMatch() != null) return "끝나지 않은 경기가 있습니다 — 먼저 재개하거나 정리하세요";
        return null;
    }

    /** 보류·무효 경기(새것부터) — 원인과 복구 방법을 리그 화면에서 볼 수 있게. */
    static java.util.List<JSONObject> holds(int max) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        for (JSONArray a : new JSONArray[]{ matches(), rmatchesIfAny() })
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject m = a.optJSONObject(i);
                String s = m.optString("status");
                if ("cancelled".equals(s) && isRanked(m) && !m.optBoolean("void_after_start")) continue;   /* 랭크 무효(대진 전) = 첫 라운드 전에 나감뿐 — 목록에 안 쌓는다. 시작한 뒤 무효(앱 탓)는 보인다 */
                if ("hold".equals(s) || "cancelled".equals(s)) out.add(m);
            }
        out.sort((x, y) -> Long.compare(y.optLong("created"), x.optLong("created")));   /* 두 목록을 합쳐 새것부터 */
        return out.size() > max ? new java.util.ArrayList<>(out.subList(0, max)) : out;
    }

    static String statusKo(String s) {
        switch (s) {
            case "ready": return "준비";
            case "playing": return "진행 중";
            case "resume": return "복귀 대기";
            case "settled": return "정산 완료";
            case "forfeit": return "기권";
            case "hold": return "오류 보류";
            case "cancelled": return "무효";
            default: return s;
        }
    }

    static String outcomeKo(JSONObject m) {
        String o = m.optString("outcome");
        if (m.optBoolean("forfeit")) return "기권패";
        return "W".equals(o) ? "승" : "L".equals(o) ? "패" : "D".equals(o) ? "무" : "?";
    }

    /** 결과 줄 — 「승 2:1 · +12」 */
    static String resultLine(JSONObject m) {
        JSONObject r = m.optJSONObject("result");
        String sc = (r != null && !m.optBoolean("forfeit")) ? " " + r.optInt("w1") + ":" + r.optInt("w2") : "";
        int d = m.optInt("delta");
        return outcomeKo(m) + sc + " · " + (d > 0 ? "+" + d : String.valueOf(d));
    }

    /* ── 랭크 매치(끝없는 매칭) — 내부 144 ───────────────────────────────
       league.json 에 "ranked" 칸을 더한다(v 는 1 그대로 — 예전 앱은 모르는 칸을 읽고 다시 써도 지우지 않는다).
       상대는 씨앗 하나로 만드는 가상 플레이어 4만 명 남짓(RankCore — id p<칸 3자리><번호 4자리>)과 예전 12명(o01~o12 = 라이벌).
       숫자(MMR·LP·전적)는 진짜 경기로만 움직이고, 닉네임·칭호·지역·한마디·아바타는 지어낸 것 — 만난 상대는 그 모습을 글자 그대로 저장한다
       (pool.json 을 고쳐도 이미 만난 상대는 안 바뀐다). 설계: handoff/ss2_next/설계/ranked.md · profile.md */

    static final int RANK_RV = 1;
    static final long PENDING_MS = 24L * 3600_000L;        /* 잡힌 상대는 하루 지나면 푼다 */

    /** 랭크 칸 새로 — 예전 리그 경기가 없는 사람과 같게: MMR 1000(점수 1000) · 배치 5판 · LP 0 · 무쌍·강등 위기·연승 없음 · 상대 전적·최근 상대 없음.
     *  upgradeRanked(처음 이어받기)와 restartRanked(처음부터 다시)가 같이 쓴다 */
    private static JSONObject freshRanked(String seed, int season) throws Exception {
        JSONObject r = new JSONObject();
        r.put("rv", RANK_RV); r.put("gen", RankCore.GEN);
        r.put("seed", seed);
        r.put("season", season); r.put("season_start", System.currentTimeMillis());
        r.put("players", new JSONObject());
        RankCore.Me me = new RankCore.Me();
        me.mmr = 1000; me.placementLeft = 5;
        putMe(r, me);
        return r;
    }

    /** 처음 한 번 — 예전 리그 기록에서 이어받아 "ranked" 칸을 만든다. 예전 칸(me·opps·order)은 지우지 않는다. 바뀌었으면 true */
    private static boolean upgradeRanked(JSONObject j) {
        if (j.optJSONObject("ranked") != null) return false;
        try {
            JSONObject r = freshRanked(String.format("%016x", new java.security.SecureRandom().nextLong()), 1);
            JSONArray ms = j.optJSONArray("matches"), os = j.optJSONArray("opps");
            JSONObject me0 = j.optJSONObject("me");
            int old = 0;
            java.util.List<String> recent = new java.util.ArrayList<>();
            java.util.Map<String, Integer> last = new java.util.HashMap<>();
            for (int i = ms == null ? -1 : ms.length() - 1; i >= 0; i--) {
                JSONObject m = ms.optJSONObject(i);
                String st = m.optString("status");
                if (m.has("mode") || !("settled".equals(st) || "forfeit".equals(st))) continue;
                old++;
                String oid = m.optString("opp"), out = m.optString("outcome");
                if (!last.containsKey(oid)) last.put(oid, "W".equals(out) ? 1 : "L".equals(out) ? -1 : 0);
                if (!recent.contains(oid) && recent.size() < 8) recent.add(oid);
            }
            int score = me0 != null ? me0.optInt("score", 1000) : 1000;
            RankCore.Me me = new RankCore.Me();
            if (old >= 5) {                                  /* 예전 점수(1000 = 보통 난도 상대들 사이)는 MMR 척도와 같은 자리 — 배치 없이 */
                me.mmr = Math.max(700, Math.min(1300, score));
                me.placementLeft = 0; me.games = 5;      /* 배치를 막 마친 사람과 같게 — K 64(배치) 대신 40부터 */
                me.lp = Math.max(0, (int) Math.round(RankCore.lpOfMmr(me.mmr) - 200) / 10 * 10);
                me.peakLp = me.lp;
            } else {
                me.mmr = Math.max(600, Math.min(1400, score)); me.placementLeft = 5;
            }
            me.recent.addAll(recent);
            JSONObject ps = r.optJSONObject("players");
            for (int i = 0; os != null && i < os.length(); i++) {
                JSONObject o = os.optJSONObject(i);
                if (o.optInt("w") + o.optInt("l") + o.optInt("d") == 0) continue;
                String id = o.optString("id");
                ps.put(id, new JSONObject().put("w", o.optInt("l")).put("l", o.optInt("w")).put("d", o.optInt("d"))
                        .put("last", last.containsKey(id) ? last.get(id) : 0).put("broke", o.optInt("broke_streak"))
                        .put("mmr_adj", 0).put("lp_adj", 0));
            }
            if (me0 != null) r.put("legacy", new JSONObject().put("score", score).put("w", me0.optInt("w")).put("l", me0.optInt("l"))
                    .put("d", me0.optInt("d")).put("games", old).put("migrated_at", System.currentTimeMillis()));
            putMe(r, me);
            j.put("ranked", r);
            return true;
        } catch (Exception e) { return false; }
    }

    static synchronized JSONObject ranked() {
        JSONObject r = db().optJSONObject("ranked");
        if (r == null) { upgradeRanked(db()); save(); r = db().optJSONObject("ranked"); }
        return r;
    }
    static long rankSeed() {
        try { return Long.parseUnsignedLong(ranked().optString("seed", "0"), 16); } catch (Exception e) { return 0x5EED; }
    }
    static int today() { return RankCore.today(System.currentTimeMillis()); }

    private static RankCore.Me meOf(JSONObject r) {
        RankCore.Me m = new RankCore.Me();
        m.mmr = r.optDouble("mmr", 1000); m.games = r.optInt("games"); m.placementLeft = r.optInt("placement", 5);
        m.lp = r.optInt("lp"); m.shield = r.optBoolean("shield"); m.master = r.optBoolean("master"); m.mr = r.optInt("mr");
        m.peakLp = r.optInt("peak_lp"); m.w = r.optInt("w"); m.l = r.optInt("l"); m.d = r.optInt("d");
        m.streak = r.optInt("streak"); m.lstreak = r.optInt("lstreak"); m.best = r.optInt("best");
        m.lastWinDay = r.has("last_win_day") ? r.optInt("last_win_day") : Integer.MIN_VALUE;
        JSONArray rc = r.optJSONArray("recent");
        for (int i = 0; rc != null && i < rc.length(); i++) m.recent.add(rc.optString(i));
        JSONObject pd = r.optJSONObject("pending");
        if (pd != null && System.currentTimeMillis() - pd.optLong("at") < PENDING_MS) m.pendingId = pd.optString("id", null);
        return m;
    }
    private static void putMe(JSONObject r, RankCore.Me m) throws Exception {
        r.put("mmr", Math.round(m.mmr * 10) / 10.0); r.put("games", m.games); r.put("placement", m.placementLeft);
        r.put("lp", m.lp); r.put("shield", m.shield); r.put("master", m.master); r.put("mr", m.mr); r.put("peak_lp", m.peakLp);
        r.put("w", m.w); r.put("l", m.l); r.put("d", m.d); r.put("streak", m.streak); r.put("lstreak", m.lstreak); r.put("best", m.best);
        if (m.lastWinDay != Integer.MIN_VALUE) r.put("last_win_day", m.lastWinDay);
        JSONArray rc = new JSONArray();
        for (int i = 0; i < Math.min(12, m.recent.size()); i++) rc.put(m.recent.get(i));
        r.put("recent", rc);
    }
    static synchronized RankCore.Me rankMe() { return meOf(ranked()); }

    private static JSONObject players() {
        JSONObject ps = ranked().optJSONObject("players");
        if (ps == null) { ps = new JSONObject(); try { ranked().put("players", ps); } catch (Exception ignored) { } }
        return ps;
    }

    /** 예전 12명 → 라이벌(RankCore.legacy) — CPU 가 싸우는 조합만 */
    static java.util.List<RankCore.Player> rivals() {
        java.util.List<RankCore.Player> out = new java.util.ArrayList<>();
        JSONArray os = opps();
        for (int i = 0; os != null && i < os.length(); i++) {
            JSONObject o = os.optJSONObject(i);
            if (!oppSupported(o.optInt("char"), o.optInt("style"))) continue;
            out.add(RankCore.legacy(o.optString("id"), o.optString("nick"), o.optInt("char"), o.optInt("style"), o.optInt("diff"), o.optInt("score", 1000)));
        }
        return out;
    }

    static synchronized RankCore.Book book() {
        final JSONObject ps = players();
        final java.util.List<RankCore.Player> rv = rivals();
        return new RankCore.Book() {
            @Override public double adj(String id) { JSONObject p = ps.optJSONObject(id); return p != null ? p.optDouble("mmr_adj", 0) : 0; }
            @Override public int[] h2h(String id) {
                JSONObject p = ps.optJSONObject(id);
                return p == null ? new int[5] : new int[]{ p.optInt("w"), p.optInt("l"), p.optInt("d"), p.optInt("last"), p.optInt("broke") };
            }
            @Override public java.util.List<RankCore.Player> rivals() { return rv; }
            @Override public int lpAdj(String id) { JSONObject p = ps.optJSONObject(id); return p != null ? p.optInt("lp_adj") : 0; }
        };
    }
    static int lpAdj(String id) { JSONObject p = players().optJSONObject(id); return p != null ? p.optInt("lp_adj") : 0; }
    static int[] h2h(String id) { return book().h2h(id); }

    static synchronized RankCore.Player rankPlayer(String id) { return RankCore.resolve(rankSeed(), id, book()); }

    /** 상대의 지금 모습(오늘 MMR·LP·티어) — 화면용 */
    static final class OppView { RankCore.Player p; double mmr; int lp, mr; String label; boolean master; }
    static synchronized OppView oppView(RankCore.Player p) {
        OppView v = new OppView(); v.p = p;
        int day = today(); double adj = book().adj(p.id);
        v.mmr = p.mmr(day, adj); v.lp = p.lp(day, adj, lpAdj(p.id));
        v.mr = p.mr(day, adj);                        /* 무쌍이 아니어도 — 내가 무쌍이면 정산이 이 값을 상대 MR 로 쓴다(start.opp_mr) */
        v.master = v.lp >= RankCore.MASTER_LP;
        if (v.master) { v.lp = RankCore.MASTER_LP; v.label = RankCore.TIER[7] + " MR " + v.mr; }
        else v.label = RankCore.label(v.lp);
        return v;
    }

    /* ── 꾸밈 프로필(pool.json — ProfileGen) ── */
    private static JSONObject pool;
    static synchronized JSONObject pool(android.content.Context c) {
        if (pool != null) return pool;
        if (c == null) return null;
        try (java.io.InputStream in = c.getAssets().open("league/pool.json")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            pool = new JSONObject(bo.toString("UTF-8"));
        } catch (Exception e) { android.util.Log.e("PocketCore", "pool.json", e); }
        return pool;
    }
    /** 이 상대의 프로필 — 만난 상대는 저장한 그대로(players[id].pf), 아니면 씨앗으로 만든다(같은 씨앗·같은 id = 늘 같음).
     *  pool 을 못 읽으면 RankCore 의 닉네임·칭호·지역으로 */
    private static final java.util.Map<String, JSONObject> PF_CACHE = new java.util.HashMap<>();   /* 안 만난 상대 프로필(씨앗·id 로 늘 같다) — 순위표를 다시 그릴 때 또 안 만들게 */
    static synchronized JSONObject pfOf(android.content.Context c, RankCore.Player p) {
        JSONObject saved = players().optJSONObject(p.id);
        if (saved != null && saved.optJSONObject("pf") != null) return saved.optJSONObject("pf");
        String ck = rankSeed() + ":" + p.id;
        JSONObject hit = PF_CACHE.get(ck);
        if (hit != null) return hit;
        JSONObject made = makePf(c, p);
        if (pool != null) { if (PF_CACHE.size() > 600) PF_CACHE.clear(); PF_CACHE.put(ck, made); }
        return made;
    }
    private static JSONObject makePf(android.content.Context c, RankCore.Player p) {
        JSONObject pl = pool(c);
        JSONObject pf = null;
        try {
            if (p.legacy && pl != null && pl.optJSONObject("roster") != null && pl.optJSONObject("roster").optJSONObject(p.id) != null) {
                pf = new JSONObject(pl.optJSONObject("roster").optJSONObject(p.id).toString());
                pf.put("nick", p.nick);
            } else if (pl != null) {
                int diff = p.baseMmr < 900 ? 0 : p.baseMmr < 1100 ? 1 : 2;
                long sd = RankCore.Rng.mix(rankSeed(), p.idHash(), 0x9F0F11EL);
                for (int k = 0; k < 4 && pf == null; k++) {
                    JSONObject x = ProfileGen.make(pl, sd + k, p.ch, p.sty, diff, java.util.Collections.<String>emptySet());
                    if (!x.isNull("nick")) pf = x;
                }
            }
            if (pf == null) {
                pf = new JSONObject().put("nick", p.nick).put("title", p.title).put("region", p.region).put("cc", "KR")
                        .put("tags", new JSONArray()).put("mood", "polite").put("cmt", "")
                        .put("av", String.format("%016x", AvatarGen.fnv1a64("av:" + p.id)));
            }
            pf.put("persona", p.persona);
            OppLook.Col col = OppLook.of(rankSeed(), p);                 /* 177 상대 컬러 — 프로필과 함께(난수 줄은 따로 — 위 프로필·이름은 그대로) */
            if (col != null) pf.put("col", col.json());
        } catch (Exception e) { pf = new JSONObject(); }
        return pf;
    }
    /** 177 이 상대의 컬러 — 프로필(만난 상대는 저장한 pf)의 col, 없거나 깨졌으면 같은 함수로 계산(결정론 — 옛 pf·기록이 지워진 상대도 늘 같은 색).
     *  상대가 될 수 없는 캐릭터면 null */
    static synchronized OppLook.Col colOf(android.content.Context c, RankCore.Player p) {
        if (p == null) return null;
        OppLook.Col col = OppLook.fromJson(pfOf(c, p).optJSONObject("col"));
        return col != null ? col : OppLook.of(rankSeed(), p);
    }
    /** 177 경기 기록의 상대 컬러(opp_snap.col — 정본, 이어 하기는 이것만). 없거나 깨졌으면 null(옛 경기 — 판 중간에 색이 생기지 않게 끈다) */
    static OppLook.Col matchCol(JSONObject m) {
        JSONObject s = m != null ? m.optJSONObject("opp_snap") : null;
        return s != null ? OppLook.fromJson(s.optJSONObject("col")) : null;
    }
    static String nickOf(android.content.Context c, RankCore.Player p) { return pfOf(c, p).optString("nick", p.nick); }

    /* ── 매칭 ── */
    /** 상대 찾기 — 잡힌 상대가 있으면 그 상대, 아니면 RankCore.pick. 148: 여기선 아무것도 저장하지 않는다 — «잡힘»은 카드를 띄울 때
     *  holdPending(찾는 중에 그만두면 아무도 안 잡힌다). 대기창 시간 waitMs = 설정 «매칭 대기»(보통·빠르게) + 한국 시각 */
    static synchronized RankCore.Pick rankedPick(android.content.Context c) {
        RankCore.Me me = rankMe();
        long seed = rankSeed();
        RankCore.Pick pk = RankCore.pick(seed, me, today(), book(), new RankCore.Rng(System.nanoTime() ^ seed));
        pk.waitMs = RankCore.waitMs(pk, me.mmr, waitFast(), hourOfDay());
        return pk;
    }
    /** 설정 «매칭 대기» — pocketcore_rank_wait = normal(기본) | fast */
    static boolean waitFast() {
        try { return "fast".equals(Settings.load().get("pocketcore_rank_wait")); } catch (Exception e) { return false; }
    }
    static int hourOfDay() { return RankCore.hourOf(System.currentTimeMillis()); }
    /** 카드를 띄울 때 고른 상대를 «잡힘»으로 저장 — 카드에서 나가거나 캐릭터 고르기에서 뒤로 가도 다음 찾기에 같은 상대(쉬운 상대만 골라 받기 막기).
     *  같은 상대를 다시 잡으면 처음 at·miss 그대로(하루 기한은 처음 잡은 때부터). 저장이 실패해도 메모리 값으로 쓰고 다음 저장 때 같이 들어간다 */
    static synchronized boolean holdPending(RankCore.Pick pk) {
        if (pk == null || pk.p == null) return false;
        try {
            JSONObject r = ranked(), pd = r.optJSONObject("pending");
            if (pd != null && pk.p.id.equals(pd.optString("id")) && System.currentTimeMillis() - pd.optLong("at") < PENDING_MS) return true;
            RankCore.Knobs k = RankCore.knobs(pk.mmr, pk.p.personaIdx);
            r.put("pending", new JSONObject().put("id", pk.p.id).put("at", System.currentTimeMillis())
                    .put("mmr", Math.round(pk.mmr * 10) / 10.0).put("stage", pk.p.stage).put("miss", 0)
                    .put("knobs", new JSONObject().put("diff", k.diff).put("lv", k.lv).put("cap", k.cap).put("floor", k.floor).put("atk", k.atk).put("dfn", k.dfn)
                            .put("cards", k.cards()).put("eatk", k.effAtk(pk.p.sty)).put("edfn", k.effDfn(pk.p.sty))));
            return save();
        } catch (Exception e) { return false; }
    }
    /** 잡힌 상대 풀기 — 그 상대일 때만(메모리만, 저장은 호출자) */
    private static void releasePending(String opp) {
        JSONObject r = db().optJSONObject("ranked"), pd = r != null ? r.optJSONObject("pending") : null;
        if (pd != null && opp != null && opp.equals(pd.optString("id"))) r.remove("pending");
    }
    /** 잡힌 상대와 못 붙은 판 하나(앱이 죽어 남은 «준비»·시작한 뒤 무효) — 두 번째면 푼다(메모리만, 저장은 호출자) */
    private static void pendingMiss(String opp) throws Exception {
        JSONObject r = db().optJSONObject("ranked"), pd = r != null ? r.optJSONObject("pending") : null;
        if (pd == null || opp == null || !opp.equals(pd.optString("id"))) return;
        int miss = pd.optInt("miss") + 1;
        if (miss >= 2) r.remove("pending"); else pd.put("miss", miss);
    }

    /** 랭크 경기 시작 전 영속 저장 — 상대 모습 사본·손잡이·시작 값. v·k 는 카드에서 대진을 정한 그 값(허브에 넣은 손잡이와 같아야
     *  정산의 «난도 확인»을 통과한다 — 자정을 넘겨도 같게 다시 계산하지 않는다). 열린 경기가 있거나 저장 실패면 null */
    static synchronized JSONObject newRankedMatch(android.content.Context c, OppView v, RankCore.Knobs k, int stage, int rematchN) {
        try {
            RankCore.Player p = v != null ? v.p : null;
            if (openMatch() != null || p == null || k == null || !oppSupported(p.ch, p.sty)) return null;
            RankCore.Me me = rankMe();
            JSONObject pf = pfOf(c, p);
            OppLook.Col col = colOf(c, p);                                /* 177: 처음 만날 때 굳힌다(기록 사본 = 정본) */
            JSONObject m = new JSONObject();
            m.put("id", java.util.UUID.randomUUID().toString());
            m.put("mode", "ranked");
            m.put("opp", p.id);
            m.put("created", System.currentTimeMillis());
            m.put("status", "ready");
            m.put("game", "ss2"); m.put("side", "P1");
            JSONObject rule = new JSONObject();
            rule.put("char", p.ch); rule.put("style", p.sty); rule.put("diff", k.diff);
            rule.put("points", POINTS); rule.put("time", TIME); rule.put("cards", "all");
            /* atk/dfn = 바탕 손잡이(이어 하기가 native 에 그대로 넘긴다 — league.c 가 카드 몫을 더함) · eatk/edfn = 경기 중 실제 배율(바탕 + 카드 몫, 157b) */
            rule.put("cpu", new JSONObject().put("lv", k.lv).put("cap", k.cap).put("floor", k.floor).put("atk", k.atk).put("dfn", k.dfn)
                    .put("cards", k.cards()).put("eatk", k.effAtk(p.sty)).put("edfn", k.effDfn(p.sty)));
            m.put("rule", rule);
            m.put("start", new JSONObject().put("mmr", Math.round(me.mmr * 10) / 10.0).put("lp", me.lp).put("mr", me.mr).put("master", me.master)
                    .put("opp_mmr", Math.round(v.mmr * 10) / 10.0).put("opp_lp", v.lp).put("opp_mr", v.mr)
                    .put("tier", me.placementLeft > 0 ? "배치" : me.master ? RankCore.TIER[7] : RankCore.label(me.lp)).put("placement_left", me.placementLeft));
            m.put("opp_snap", new JSONObject().put("nick", pf.optString("nick", p.nick)).put("ch", p.ch).put("sty", p.sty).put("label", v.label)
                    .put("lp", v.lp).put("mr", v.master ? v.mr : 0).put("title", pf.optString("title")).put("region", ProfileGen.regionLabel(pf))
                    .put("av", pf.optString("av")).put("persona", p.persona).put("mood", pf.optString("mood")).put("legacy", p.legacy));
            if (col != null) m.optJSONObject("opp_snap").put("col", col.json());
            m.put("rematch_n", rematchN);
            m.put("look", new JSONObject().put("stage", stage).put("color", 0));
            JSONObject ps = players();
            JSONObject pr = ps.optJSONObject(p.id);
            if (pr == null) { pr = new JSONObject().put("w", 0).put("l", 0).put("d", 0).put("last", 0).put("broke", 0).put("mmr_adj", 0).put("lp_adj", 0); ps.put(p.id, pr); }
            if (pr.optJSONObject("pf") == null) pr.put("pf", pf);       /* 처음 만난 모습을 글자 그대로 */
            if (col != null && OppLook.fromJson(pr.optJSONObject("pf").optJSONObject("col")) == null) pr.optJSONObject("pf").put("col", col.json());   /* 177: 옛 pf(col 없음)엔 이번 색을 */
            pr.put("at", System.currentTimeMillis());
            trimPlayers(ps);
            JSONArray rm = rmatches();
            rm.put(m);
            if (!save()) { rm.remove(rm.length() - 1); return null; }
            return m;
        } catch (Exception e) { return null; }
    }
    /** players 칸이 너무 커지지 않게 — 300명 넘으면 오래 안 만난(at) 사람부터(전적은 경기 기록에 남는다). 잡힌 상대·라이벌은 남긴다 */
    private static void trimPlayers(JSONObject ps) {
        if (ps.length() <= 300) return;
        JSONObject pd = ranked().optJSONObject("pending");
        String keep = pd != null ? pd.optString("id") : "";
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.Iterator<String> it = ps.keys();
        while (it.hasNext()) { String id = it.next(); if (id.startsWith("p") && !id.equals(keep)) ids.add(id); }
        ids.sort((a, b) -> Long.compare(ps.optJSONObject(a).optLong("at"), ps.optJSONObject(b).optLong("at")));
        for (int i = 0; i < ids.size() && ps.length() > 300; i++) ps.remove(ids.get(i));
    }

    static boolean isRanked(JSONObject m) { return m != null && "ranked".equals(m.optString("mode")); }

    /** 같은 상대와 이어 붙은 판 수(마지막부터 — 정산·기권만, 무효·보류는 건너뛴다) */
    static synchronized int rematchCount(String oppId) {
        JSONArray a = rmatches(); int n = 0;
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject m = a.optJSONObject(i); String s = m.optString("status");
            if (!isRanked(m)) continue;
            if ("cancelled".equals(s) || "hold".equals(s)) continue;
            if (!oppId.equals(m.optString("opp"))) break;
            if ("settled".equals(s) || "forfeit".equals(s)) n++;
        }
        return n;
    }

    /** 결과를 MMR·LP·전적·최근 상대에 넣는다(메모리만 — 저장은 호출자). 시작 때 적은 상대 MMR·MR 로 */
    private static void applyRanked(JSONObject m, String out, boolean forfeit) throws Exception {
        JSONObject r = ranked();
        RankCore.Me me = meOf(r);
        JSONObject st = m.optJSONObject("start");
        String oid = m.optString("opp");
        JSONObject ps = players();
        JSONObject pr = ps.optJSONObject(oid);
        if (pr == null) { pr = new JSONObject().put("w", 0).put("l", 0).put("d", 0).put("last", 0).put("broke", 0).put("mmr_adj", 0).put("lp_adj", 0); ps.put(oid, pr); }
        boolean revenge = m.optInt("rematch_n") > 0 && pr.optInt("last") == -1;
        int streak0 = me.streak;
        int omr = st.optInt("opp_mr");
        if (omr <= 0) omr = (int) Math.round(1500 + (st.optDouble("opp_mmr", me.mmr) - 1600) * 1.5);   /* 상대 MR 이 안 적힌 경기 — Player.mr 과 같은 식 */
        RankCore.Outcome o = RankCore.settle(me, out.charAt(0), st.optDouble("opp_mmr", me.mmr), omr, revenge, today());
        /* 157 post: 상대 LP 변화 — 결판 띠의 두 게이지(유저 «게이지로 상대와 나 점수 변화»). 상대 모습(순위표·프로필·카드와 같은 셈: Player.lp(오늘, mmr_adj, lp_adj))을
           이 경기 조정 전·뒤로 — 같은 날 값이라 차이는 이 경기 몫뿐(MMR −16(S−E) · LP ∓(20~60)), 뒤 값 = 지금 순위표 값. 무쌍(LP 9000)이면 MR */
        RankCore.Player op = RankCore.resolve(rankSeed(), oid, book());
        int day = today();
        double adj0 = pr.optDouble("mmr_adj"); int la0 = pr.optInt("lp_adj");
        if ("W".equals(out)) { pr.put("w", pr.optInt("w") + 1); pr.put("last", 1); pr.put("broke", 0); }
        else if ("L".equals(out)) { pr.put("l", pr.optInt("l") + 1); pr.put("last", -1); if (streak0 >= 2) { pr.put("broke", streak0); m.put("broke_streak", streak0); } }
        else { pr.put("d", pr.optInt("d") + 1); pr.put("last", 0); }
        pr.put("mmr_adj", Math.max(-150, Math.min(150, pr.optDouble("mmr_adj") + o.oppMmrDelta)));
        pr.put("lp_adj", Math.max(-600, Math.min(600, pr.optInt("lp_adj") + o.oppLpDelta)));
        pr.put("at", System.currentTimeMillis());
        if (op != null) {
            int lb = op.lp(day, adj0, la0), la = op.lp(day, pr.optDouble("mmr_adj"), pr.optInt("lp_adj"));
            m.put("opp_lp_before", Math.min(lb, RankCore.MASTER_LP)).put("opp_lp_after", Math.min(la, RankCore.MASTER_LP));
            if (lb >= RankCore.MASTER_LP || la >= RankCore.MASTER_LP) m.put("opp_mr_before", op.mr(day, adj0)).put("opp_mr_after", op.mr(day, pr.optDouble("mmr_adj")));
        }
        me.recent.remove(oid); me.recent.add(0, oid);
        JSONObject pd = r.optJSONObject("pending");
        if (pd != null && oid.equals(pd.optString("id"))) r.remove("pending");
        putMe(r, me);
        m.put("outcome", out);
        if (forfeit) m.put("forfeit", true);
        m.put("lp_before", o.lpBefore); m.put("lp_after", o.lpAfter); m.put("lp_delta", o.lpDelta);
        m.put("mmr_delta", Math.round((o.mmrAfter - o.mmrBefore) * 10) / 10.0);
        m.put("mr_before", o.mrBefore); m.put("mr_after", o.mrAfter);
        m.put("tier_before", st.optString("tier"));
        m.put("tier_after", me.placementLeft > 0 ? "배치" : me.master ? RankCore.TIER[7] : RankCore.label(me.lp));
        if (o.event != null) m.put("event", o.event);
        m.put("bonus", new JSONObject().put("streak", o.bonusStreak).put("revenge", o.bonusRevenge).put("daily", o.bonusDaily));
        m.put("placement_left", me.placementLeft);
        m.put("after", new JSONObject().put("streak", me.streak).put("best", me.best)
                .put("h2h_w", pr.optInt("w")).put("h2h_l", pr.optInt("l")).put("h2h_d", pr.optInt("d")));
    }

    /** 랭크 경기 정산의 확인(대진·규칙은 settle 이 본다) — CPU 손잡이가 실제로 먹었나: 첫 라운드 단계·배율, 결판 때 상한.
     *  배율은 바탕 + 카드 몫(157b — league.c 가 단계로 고른 카드의 몫을 매 프레임 더한다). 몫은 적힌 eatk 가 아니라 단계·검질로 다시 센다:
     *  156·157(af75fe7)이 연 경기도 이어 하면 지금 league.c 가 그 단계의 카드와 몫으로 싸우기 때문 */
    private static String rankedKnobWhy(JSONObject rule, int[] st) {
        JSONObject k = rule.optJSONObject("cpu");
        if (k == null || st.length <= 20) return null;
        int lv = k.optInt("lv"), sty = rule.optInt("style") & 1;
        int ea = RankCore.effAtk(k.optInt("atk"), lv, sty), ed = RankCore.effDfn(k.optInt("dfn"), lv, sty);
        if (st[18] != lv || st[19] != ea || st[20] != ed || (st.length > 21 && st[21] != k.optInt("cap")))
            return String.format("난도 불일치: CPU 단계 %d·상한 %d·배율 %04X/%04X (요청 %d·%d·%04X/%04X)", st[18], st.length > 21 ? st[21] : -1, st[19], st[20],
                    lv, k.optInt("cap"), ea, ed);
        return null;
    }

    /** 최근 랭크 경기(정산·기권, 새것부터) */
    static java.util.List<JSONObject> rankedHistory(int max) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        JSONArray a = rmatches();
        for (int i = a.length() - 1; i >= 0 && out.size() < max; i--) {
            JSONObject m = a.optJSONObject(i); String s = m.optString("status");
            if (isRanked(m) && ("settled".equals(s) || "forfeit".equals(s))) out.add(m);
        }
        return out;
    }
    private static String signed(int v) { return v > 0 ? "+" + v : v < 0 ? "-" + (-v) : "±0"; }   /* RankUi.signed 와 같게(여기선 화면 클래스를 안 부른다) */
    /** 랭크 결과 줄 — «승 2:1 · +95 LP» */
    static String rankedResultLine(JSONObject m) {
        JSONObject r = m.optJSONObject("result");
        String sc = (r != null && !m.optBoolean("forfeit")) ? " " + r.optInt("w1") + ":" + r.optInt("w2") : "";
        int d = m.optInt("lp_delta");
        String lp;
        if (m.optInt("placement_left", 0) > 0 || "배치".equals(m.optString("tier_before"))) lp = "배치";
        else if (RankCore.TIER[7].equals(m.optString("tier_before"))) lp = signed(m.optInt("mr_after") - m.optInt("mr_before")) + " MR";   /* 무쌍은 LP 가 9000 그대로 — MR 로 */
        else lp = signed(d) + " LP";
        return outcomeKo(m) + sc + " · " + lp + (m.optInt("cheat") != 0 ? " · 치트" : "");
    }
    /** 내 랭크 한 줄 — «검객 ★3 · 2410 LP» / «배치 2/5» / «무쌍 MR 1712» */
    static String myRankLabel(RankCore.Me me) {
        if (me.placementLeft > 0) return "배치 경기 " + (5 - me.placementLeft) + "/5";
        if (me.master) return RankCore.TIER[7] + " MR " + me.mr;
        return RankCore.label(me.lp) + " · " + me.lp + " LP";
    }
    static long myKey(RankCore.Me me) { return RankCore.key(me.lp, me.master, me.mr); }
    /** 경기의 상대 한 줄 — «달빛검객77 (나코루루 · 수라)». 랭크 경기는 경기에 적힌 모습 사본으로, 예전 경기는 명단으로 */
    static String oppLabel(JSONObject m) {
        if (m == null) return "?";
        if (isRanked(m)) {
            JSONObject s = m.optJSONObject("opp_snap"); JSONObject r = m.optJSONObject("rule");
            if (s == null || r == null) return "?";
            return s.optString("nick") + " (" + CHAR[r.optInt("char")] + " · " + STYLE[r.optInt("style") & 1] + ")";
        }
        JSONObject o = oppById(m.optString("opp"));
        return o != null ? o.optString("nick") + " (" + CHAR[o.optInt("char")] + " · " + STYLE[o.optInt("style")] + ")" : "?";
    }
    static String oppNick(JSONObject m) {
        if (m == null) return "?";
        if (isRanked(m)) { JSONObject s = m.optJSONObject("opp_snap"); return s != null ? s.optString("nick", "?") : "?"; }
        JSONObject o = oppById(m.optString("opp"));
        return o != null ? o.optString("nick") : "?";
    }

    /* ── 대기창 자료(148) ── */

    /** 오늘(한국 시각) 랭크 전적 — 대기창 «오늘 2승 1패 · +35 LP ●●○». 정산·기권(= 패)만, 무효·보류는 뺀다. last5 = 새것부터 'W' 'L' 'D' 5개까지.
     *  lpSum 은 배치 경기를 빼고 더한다(배치를 마친 판은 LP 가 0 에서 한 번에 뛰어 «오늘 ±» 가 엉뚱해진다) */
    static final class Today { int w, l, d, lpSum; char[] last5 = new char[0]; int games() { return w + l + d; } }
    static Today rankedToday() { return rankedToday(System.currentTimeMillis()); }
    static synchronized Today rankedToday(long nowMs) {
        Today t = new Today();
        int day = RankCore.today(nowMs);
        StringBuilder sb = new StringBuilder();
        JSONArray a = rmatchesIfAny();
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject m = a.optJSONObject(i); String s = m.optString("status");
            if (!isRanked(m) || !("settled".equals(s) || "forfeit".equals(s))) continue;
            if (RankCore.today(m.optLong("settled", m.optLong("updated", m.optLong("created")))) != day) continue;
            String o = "forfeit".equals(s) || m.optBoolean("forfeit") ? "L" : m.optString("outcome");
            char c = "W".equals(o) ? 'W' : "L".equals(o) ? 'L' : 'D';
            if (c == 'W') t.w++; else if (c == 'L') t.l++; else t.d++;
            if (!"배치".equals(m.optString("tier_before"))) t.lpSum += m.optInt("lp_delta");
            if (sb.length() < 5) sb.append(c);
        }
        t.last5 = sb.toString().toCharArray();
        return t;
    }
    /** 오늘 첫 승 보너스가 남았나(대기창 «첫 승 보너스 +30 남았어요») — 배치·무쌍 판엔 보너스가 없어 false */
    static synchronized boolean bonusLeft() {
        RankCore.Me me = rankMe();
        return me.placementLeft == 0 && !me.master && me.lastWinDay != today();
    }

    /* ── 157 리그 인게임 메뉴 자료(계약서 §11) — 모두 league.json 의 선택 칸(v 1 그대로 · 옛 판은 모르는 칸을 읽고 다시 써도 남긴다) ──
       G2  ranked.session {start, w, l, d, lp, rank0, last} — 게임 속 리그를 켤 때(hubStart) 새로. 정산·기권이 같은 저장으로 더한다(liveHub 일 때만).
       G10 forfeitPreview — 기권하면 무엇이 기록되나(쉼의 두 번 묻기 · askForfeit 가 같이 쓴다).
       pref.lgm {fx, loss, auto, remember, tab} — 메뉴 설정(서랍 «설정» 탭). */

    /** 이번 대전 세션(게임 속 리그를 한 번 켠 동안) — 그만할까요?·쉼 «오늘 4승 1패 +152» · 순위 ▲▼ 기준(rank0) */
    static final class Session { long start; int w, l, d, lp; long rank0 = -1; }
    /** 새 세션 — hubStart 에서. 같은 한국 날짜의 세션이 이미 있으면 그대로 이어 간다(리그 화면을 다녀와도 «오늘» 이 끊기지 않고,
     *  rank0 = 오늘 첫 판 전 순위 — 기획서 «오늘 첫 판 전»). 날이 바뀌었으면 새로. 저장 실패해도 메모리 값으로 쓰고 다음 저장 때 같이 들어간다 */
    static synchronized void sessionStart() {
        try {
            JSONObject old = ranked().optJSONObject("session");
            if (old != null && old.optLong("start") > 0 && RankCore.today(old.optLong("start")) == today()) return;
            ranked().put("session", new JSONObject().put("start", System.currentTimeMillis()).put("w", 0).put("l", 0).put("d", 0)
                    .put("lp", 0).put("rank0", -1));
            save();
        } catch (Exception ignored) { }
    }
    /** 168 M1 — 새 시즌·처음부터 뒤: 같은 날이어도 세션을 새로(«오늘 4승 1패 +152»·rank0 이 지난 시즌 값으로 남지 않게). 저장 실패해도 메모리 값으로 */
    static synchronized void sessionReset() {
        try {
            ranked().put("session", new JSONObject().put("start", System.currentTimeMillis()).put("w", 0).put("l", 0).put("d", 0)
                    .put("lp", 0).put("rank0", -1));
            save();
        } catch (Exception ignored) { }
    }
    /** 지금 세션(없으면 null) */
    static synchronized Session session() {
        JSONObject r = db().optJSONObject("ranked"), s = r != null ? r.optJSONObject("session") : null;
        if (s == null || s.optLong("start") <= 0) return null;
        Session x = new Session();
        x.start = s.optLong("start"); x.w = Math.max(0, s.optInt("w")); x.l = Math.max(0, s.optInt("l")); x.d = Math.max(0, s.optInt("d"));
        x.lp = s.optInt("lp"); x.rank0 = s.optLong("rank0", -1);
        return x;
    }
    /** 끝난 랭크 경기 하나를 세션에 — 같은 경기는 한 번만(last). 배치 판 LP 는 뺀다(rankedToday 와 같게). 저장하면 true */
    static synchronized boolean sessionNote(JSONObject m) {
        if (!noteSessionMem(m)) return false;
        return save();
    }
    /** 메모리만(정산·기권이 같은 저장으로 부른다) — 더했으면 true */
    private static boolean noteSessionMem(JSONObject m) {
        try {
            JSONObject r = db().optJSONObject("ranked"), s = r != null ? r.optJSONObject("session") : null;
            if (s == null || m == null || !isRanked(m)) return false;
            String st = m.optString("status"), id = m.optString("id");
            if (!("settled".equals(st) || "forfeit".equals(st)) || id.isEmpty() || id.equals(s.optString("last"))) return false;
            String o = "forfeit".equals(st) || m.optBoolean("forfeit") ? "L" : m.optString("outcome");
            String k = "W".equals(o) ? "w" : "L".equals(o) ? "l" : "d";
            s.put(k, s.optInt(k) + 1);
            if (!"배치".equals(m.optString("tier_before"))) s.put("lp", s.optInt("lp") + m.optInt("lp_delta"));
            s.put("last", id);
            return true;
        } catch (Exception e) { return false; }
    }
    /** 세션 첫 순위(BoardCache 가 처음 알았을 때 한 번만) — 이미 있으면 그대로 */
    static synchronized void sessionRank0(long rank) {
        try {
            JSONObject r = db().optJSONObject("ranked"), s = r != null ? r.optJSONObject("session") : null;
            if (s == null || rank <= 0 || s.optLong("rank0", -1) > 0) return;
            s.put("rank0", rank);
            save();
        } catch (Exception ignored) { }
    }

    /** G10 기권 미리보기 — kind LP(lpDelta ≤ 0) · PLACEMENT(배치 1패) · NOVICE(수련생 — LP 그대로) · MASTER(무쌍 — MR).
     *  demoted/shieldUsed = 이 기권으로 강등 / 강등 위기(방패) · tierAfter = 판 뒤 티어(강등이면 아래 티어) */
    static final class ForfeitPreview {
        static final int LP = 0, PLACEMENT = 1, NOVICE = 2, MASTER = 3;
        int kind, lpDelta, mrDelta, brokeStreak;     /* brokeStreak = 끊길 내 연승(2 이상일 때만, 아니면 0) */
        boolean demoted, shieldUsed; int tierAfter = -1;
    }
    /** 기권이 실제로 정산될 셈 그대로(applyRanked → RankCore.settle(me,'L',…)) — 지금 나의 사본에서 settle 을 돌리고 버린다(기록은 안 바뀜).
     *  157 전엔 RankCore.stakes(보너스·바닥·방패·강등 없는 날값)를 보여 «−80 LP» 라 해 놓고 실제는 −310 LP 강등이 될 수 있었다 */
    static synchronized ForfeitPreview forfeitPreview(JSONObject m) {
        ForfeitPreview f = new ForfeitPreview();
        RankCore.Me me = rankMe();
        JSONObject s0 = m != null ? m.optJSONObject("start") : null;
        f.brokeStreak = me.streak >= 2 ? me.streak : 0;
        if ((s0 != null ? s0.optInt("placement_left", me.placementLeft) : me.placementLeft) > 0) { f.kind = ForfeitPreview.PLACEMENT; return f; }
        double omm = s0 != null ? s0.optDouble("opp_mmr", me.mmr) : me.mmr;
        int omr = s0 != null ? s0.optInt("opp_mr") : 0;
        if (omr <= 0) omr = (int) Math.round(1500 + (omm - 1600) * 1.5);   /* applyRanked 와 같은 식 */
        RankCore.Me cp = rankMe();                                         /* 새 사본(meOf) — settle 이 바꿔도 기록은 그대로 */
        RankCore.Outcome o = RankCore.settle(cp, 'L', omm, omr, false, today());
        if (me.master) { f.kind = ForfeitPreview.MASTER; f.mrDelta = o.mrAfter - o.mrBefore; return f; }
        f.lpDelta = o.lpDelta; f.demoted = o.demoted; f.shieldUsed = o.shieldUsed; f.tierAfter = o.tierAfter;
        f.kind = RankCore.tierOf(me.lp) == 0 && o.lpDelta == 0 ? ForfeitPreview.NOVICE : ForfeitPreview.LP;
        return f;
    }
    /** 진 판 «설 이기면 +N» — 이 상대와 재대결(설욕전)을 이기면 실제로 받을 LP(설욕 +20 · 오늘 첫 승 +30 · 연승 보너스까지 정산 그대로).
     *  배치·무쌍·모름이면 Integer.MIN_VALUE */
    static synchronized int rematchWinLp(RankCore.Player p) {
        if (p == null) return Integer.MIN_VALUE;
        RankCore.Me cp = rankMe();
        if (cp.placementLeft > 0 || cp.master) return Integer.MIN_VALUE;
        OppView v = oppView(p);
        double omm = Math.round(v.mmr * 10) / 10.0;                         /* newRankedMatch 가 start.opp_mmr 에 적는 값 */
        int omr = v.mr > 0 ? v.mr : (int) Math.round(1500 + (omm - 1600) * 1.5);
        return RankCore.settle(cp, 'W', omm, omr, true, today()).lpDelta;
    }

    /** 메뉴 설정 — 기본 fx 0(보통) · loss 0(설욕전) · auto 0(끔) · remember true · tab 0. 범위 밖 값은 기본으로 */
    static synchronized MenuModel.Prefs menuPrefs() {
        JSONObject p = db().optJSONObject("pref"), g = p != null ? p.optJSONObject("lgm") : null;
        if (g == null) return MenuModel.Prefs.defaults();
        int fx = g.optInt("fx", 0), loss = g.optInt("loss", 0), auto = g.optInt("auto", 0), tab = g.optInt("tab", 0);
        return new MenuModel.Prefs(fx >= 0 && fx <= 2 ? fx : 0, loss == 1 ? 1 : 0, auto == 10 ? 10 : 0, g.optBoolean("remember", true), tab >= 0 && tab <= 3 ? tab : 0);   /* 168: 리그 탭 3 */
    }
    /** 바뀔 때만 저장 — 저장했거나 같으면 true */
    static synchronized boolean setMenuPrefs(MenuModel.Prefs n) {
        if (n == null) return false;
        MenuModel.Prefs o = menuPrefs();
        JSONObject p0 = db().optJSONObject("pref");
        boolean had = p0 != null && p0.optJSONObject("lgm") != null;
        if (had && o.fx == n.fx && o.lossCursor == n.lossCursor && o.autoNextSec == n.autoNextSec && o.rememberTab == n.rememberTab && o.lastTab == n.lastTab) return true;
        if (!had && n.fx == 0 && n.lossCursor == 0 && n.autoNextSec == 0 && n.rememberTab && n.lastTab == 0) return true;   /* 기본값 그대로 — 칸을 만들지 않는다 */
        try {
            pref().put("lgm", new JSONObject().put("fx", Math.max(0, Math.min(2, n.fx))).put("loss", n.lossCursor == 1 ? 1 : 0)
                    .put("auto", n.autoNextSec == 10 ? 10 : 0).put("remember", n.rememberTab).put("tab", Math.max(0, Math.min(3, n.lastTab))));
            return save();
        } catch (Exception e) { return false; }
    }

    /** 서랍 «전적» 라이벌 한 줄 — 예전 12명(전적 있음) 또는 4판 넘게 붙고 승패 차 1 이하인 상대 중 가장 최근에 만난 사람. 없으면 null */
    static final class RivalLine { String id, nick; int w, l, d, last, broke; }
    static synchronized RivalLine rivalLine() {
        JSONObject ps = players();
        RivalLine best = null; long bestAt = Long.MIN_VALUE;
        java.util.Iterator<String> it = ps.keys();
        while (it.hasNext()) {
            String id = it.next(); JSONObject p = ps.optJSONObject(id);
            if (p == null) continue;
            int w = p.optInt("w"), l = p.optInt("l"), d = p.optInt("d");
            boolean legacy = id.startsWith("o");
            if (!(legacy && w + l + d > 0 || w + l >= 4 && Math.abs(w - l) <= 1)) continue;
            long at = p.optLong("at");
            if (best != null && at <= bestAt) continue;
            RivalLine r = new RivalLine(); r.id = id; r.w = w; r.l = l; r.d = d; r.last = p.optInt("last"); r.broke = p.optInt("broke");
            JSONObject pf = p.optJSONObject("pf");
            r.nick = pf != null ? pf.optString("nick", null) : null;
            if (r.nick == null) { JSONObject o = legacy ? oppById(id) : null; r.nick = o != null ? o.optString("nick") : null; }
            if (r.nick == null) { RankCore.Player rp = rankPlayer(id); r.nick = rp != null ? rp.nick : id; }
            best = r; bestAt = at;
        }
        return best;
    }

    /* ── 시즌·처음부터(148) ───────────────────────────────────────────────
       «새 시즌 시작» = LP 반쯤 내림(실력 점수·상대 전적 그대로) · «처음부터 다시» = 배치 5판부터(같은 씨앗 = 같은 세상).
       둘 다 지금 ranked 칸 전체를 league/archive/ 에 먼저 써서 다시 읽어 본 뒤에야 league.json 을 바꾸고, 지난 시즌 줄(ranked.seasons)을 더한다.
       아무것도 지우지 않는다(보관 파일은 저장 실패로 되돌릴 때만 지운다). 예전 칸(me·opps·matches·pref)은 안 건드린다. */

    static synchronized int season() { JSONObject r = db().optJSONObject("ranked"); return r != null ? Math.max(1, r.optInt("season", 1)) : 1; }
    static synchronized long seasonStart() { JSONObject r = db().optJSONObject("ranked"); return r != null ? r.optLong("season_start") : 0; }
    /** 지난 시즌 줄(오래된 것부터) — {n, start, end, kind season|restart, games, w, l, d, peak_lp, peak_label, final_lp, final_label, master, mr, best, file} */
    static synchronized java.util.List<JSONObject> seasons() {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        JSONObject r = db().optJSONObject("ranked");
        JSONArray a = r != null ? r.optJSONArray("seasons") : null;
        for (int i = 0; a != null && i < a.length(); i++) if (a.optJSONObject(i) != null) out.add(a.optJSONObject(i));
        return out;
    }
    /** 통산 승·패·무 = 지난 시즌 줄 합 + 이번 시즌 */
    static synchronized int[] careerWLD() {
        JSONObject r = db().optJSONObject("ranked");
        int[] t = r != null ? new int[]{ r.optInt("w"), r.optInt("l"), r.optInt("d") } : new int[3];
        for (JSONObject x : seasons()) { t[0] += x.optInt("w"); t[1] += x.optInt("l"); t[2] += x.optInt("d"); }
        return t;
    }
    private static boolean startedOpen() {
        for (JSONArray a : new JSONArray[]{ matches(), rmatchesIfAny() })
            for (int i = 0; i < a.length(); i++) {
                String s = a.optJSONObject(i).optString("status");
                if ("playing".equals(s) || "resume".equals(s)) return true;
            }
        return false;
    }
    /** 새 시즌·처음부터를 지금 못 하는 이유(없으면 null) — 기록 파일 문제 · 게임 속 리그가 살아 있음 · 시작한 경기가 열려 있음.
     *  «준비» 경기는 막지 않는다(랭크 «준비»는 먼저 무효로 닫고, 예전 칸 «준비»는 그대로 둔다). 배치 중 새 시즌은 newSeasonPreview()==null 로 따로 본다 */
    static synchronized String resetBlockedWhy() { return resetBlockedWhy(false, false); }
    /** 168 §2-5 — inGame = 게임 속 리그(그 허브)가 부름: liveHub 는 안 본다(부르는 쪽이 그 판 · 준비 경기는 cancelReadyMem 이 닫는다).
     *  forfeitOpen = 시작한 경기(멈춘 경기)는 기권패로 닫고 하겠다(M1 — 두 번 묻기 글이 그렇게 말했다) — 그때는 막지 않는다 */
    static synchronized String resetBlockedWhy(boolean inGame, boolean forfeitOpen) {
        if (dbProblem != null) return dbProblem;
        if (liveHub && !inGame) return "게임 속 리그를 닫은 뒤에 할 수 있어요";
        if (startedOpen() && !forfeitOpen) return "끝나지 않은 경기를 먼저 정리해 주세요";
        return null;
    }
    /** 168 고침 — 런처 리그 화면(LeagueActivity) 시즌 도구: 멈춘 경기는 막지 않고 두 번 묻기 글에 «기권패로 닫고»(게임 안 리그 탭과 같게 — M1·D6).
     *  막는 것은 기록 파일 문제·게임 속 리그가 살아 있음뿐(«먼저 정리해 주세요» 같은 글은 안 보인다 — 리셋·«나중에»마다 멈춘 경기가 생긴다) */
    static synchronized String seasonToolWhy() { return resetBlockedWhy(false, true); }
    /** 두 번 묻기 글 한 줄 — 멈춘 경기가 있으면 «멈춘 경기(vs 칼끝노인)는 기권패로 닫아요 · -80 LP», 없으면 null */
    static synchronized String openForfeitLine() {
        JSONObject open = startedOpenMatch();
        if (open == null) return null;
        String lp = null;
        if (isRanked(open)) {
            ForfeitPreview fp = forfeitPreview(open);
            lp = fp.kind == ForfeitPreview.PLACEMENT ? "배치 1패" : fp.kind == ForfeitPreview.LP && fp.lpDelta != 0 ? signed(fp.lpDelta) + " LP" : null;
        }
        return "멈춘 경기(vs " + oppNick(open) + ")는 기권패로 닫아요" + (lp != null ? " · " + lp : "");
    }
    /** 시작한(멈춘) 경기 — 진행 중·복귀 대기(새것부터 첫째). 없으면 null */
    static synchronized JSONObject startedOpenMatch() {
        JSONObject o = openMatch();
        return o != null && !"ready".equals(o.optString("status")) ? o : null;
    }
    /** M1 — 시즌 도구 앞에서 멈춘 경기를 기권패로(메모리만 · 같은 저장 안). 닫은 id 목록(체크포인트는 저장 뒤 지운다) */
    private static java.util.List<String> forfeitStartedMem() throws Exception {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JSONArray a : new JSONArray[]{ matches(), rmatchesIfAny() })
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                String st = m.optString("status");
                if (!"playing".equals(st) && !"resume".equals(st)) continue;
                forfeitMem(m);
                if (isRanked(m) && liveHub) noteSessionMem(m);
                ids.add(m.optString("id"));
            }
        return ids;
    }

    /** 새 시즌 미리보기 — 시즌 N · LP lp0 → lp1 (label0 → label1). 배치 중이면 null(새 시즌을 못 연다) */
    static final class SeasonPreview { int season, lp0, lp1; String label0, label1; }
    static synchronized SeasonPreview newSeasonPreview() {
        RankCore.Me me = rankMe();
        if (me.placementLeft > 0) return null;
        SeasonPreview p = new SeasonPreview();
        p.season = season(); p.lp0 = me.lp; p.lp1 = RankCore.newSeasonLp(me.lp, me.mmr);
        p.label0 = me.master ? RankCore.TIER[7] : RankCore.label(me.lp);
        p.label1 = RankCore.label(p.lp1);
        return p;
    }

    /** 새 시즌 — LP 를 배치 자리 쪽으로 반(RankCore.newSeasonLp), 무쌍 풂(MR 0 — 다시 오르면 새로), 승패·연승·최장·최고 LP·강등 위기 0부터.
     *  MMR·판수·최근 상대·상대 전적·오늘 첫 승·씨앗·경기 기록은 그대로. 잡힌 상대는 푼다. 막히면(resetBlockedWhy·배치 중)·저장 실패면 false(기록 그대로) */
    static synchronized boolean newSeason() { return newSeason(false, false); }
    /** 168 — inGame·forfeitOpen 은 resetBlockedWhy(inGame, forfeitOpen). 멈춘 경기 기권은 보관 파일보다 먼저(보관 = 기권 뒤 상태 — 기권패는 지난 시즌에 남는다) */
    static synchronized boolean newSeason(boolean inGame, boolean forfeitOpen) {
        if (resetBlockedWhy(inGame, forfeitOpen) != null) return false;
        String backup = db.toString();
        File arc = null;
        java.util.List<String> ff = new java.util.ArrayList<>();
        try {
            if (forfeitOpen) ff = forfeitStartedMem();
            if (rankMe().placementLeft > 0) { db = new JSONObject(backup); return false; }   /* 기권으로 배치가 끝날 수는 없지만(배치 1패) — 배치 중이면 새 시즌 없음 */
            int n = season();
            arc = writeArchive("season");                   /* 바꾸기 직전 그대로(준비 경기를 닫기 전) */
            if (arc == null) { db = new JSONObject(backup); return false; }
            java.util.List<String> ready = cancelReadyMem("새 시즌을 열며 정리(대진 전)");
            JSONObject r = ranked();
            RankCore.Me me = meOf(r);
            JSONArray rows = r.optJSONArray("seasons");
            if (rows == null) { rows = new JSONArray(); r.put("seasons", rows); }
            rows.put(seasonRow(r, "season", arc.getName()));
            me.lp = RankCore.newSeasonLp(me.lp, me.mmr); me.peakLp = me.lp;
            me.master = false; me.mr = 0; me.shield = false;
            me.w = 0; me.l = 0; me.d = 0; me.streak = 0; me.lstreak = 0; me.best = 0;
            putMe(r, me);
            r.remove("pending");
            r.put("season", n + 1); r.put("season_start", System.currentTimeMillis());
            if (!save()) { db = new JSONObject(backup); arc.delete(); return false; }
            for (String id : ready) ckptDelete(id);
            for (String id : ff) { ckptDelete(id); new File(ckpt(id).getPath() + ".tmp").delete(); new File(ckpt(id).getPath() + ".tmp.look").delete(); }   /* 171: .look 도 */
            return true;
        } catch (Exception e) {
            try { db = new JSONObject(backup); } catch (Exception ignored) { }
            if (arc != null) arc.delete();
            return false;
        }
    }

    /** 처음부터 다시 — 같은 rv·gen·씨앗으로 랭크 칸을 새로(freshRanked: 배치 5판·LP 0·판수 0·상대 전적·최근 상대·경기 기록 없음),
     *  시즌 번호는 이어서(+1), 지난 시즌 줄은 이어받고 이번 줄(restart)을 더한다. 잡힌 상대·옛 리그 이어받기(legacy)·오늘 첫 승은 없다.
     *  저장되면 체크포인트(ckpt_*)를 지운다. 예전 칸(me·opps·matches·pref)은 그대로. 막히면·저장 실패면 false(기록 그대로) */
    static synchronized boolean restartRanked() { return restartRanked(false, false); }
    static synchronized boolean restartRanked(boolean inGame, boolean forfeitOpen) {
        if (resetBlockedWhy(inGame, forfeitOpen) != null) return false;
        String backup = db.toString();
        File arc = null;
        try {
            if (forfeitOpen) forfeitStartedMem();           /* 기권패는 보관 파일(지난 시즌 줄)에 — 처음부터는 어차피 배치부터라 피할 이득이 없다 */
            arc = writeArchive("restart");                  /* 바꾸기 직전 그대로(준비 경기를 닫기 전) */
            if (arc == null) { db = new JSONObject(backup); return false; }
            cancelReadyMem("처음부터 다시 하며 정리(대진 전)");
            JSONObject r = ranked();
            JSONArray rows = new JSONArray();
            JSONArray old = r.optJSONArray("seasons");
            for (int i = 0; old != null && i < old.length(); i++) rows.put(old.get(i));
            rows.put(seasonRow(r, "restart", arc.getName()));
            JSONObject n = freshRanked(r.optString("seed"), season() + 1);
            n.put("rv", r.optInt("rv", RANK_RV)); n.put("gen", r.optInt("gen", RankCore.GEN));
            n.put("matches", new JSONArray());
            n.put("seasons", rows);
            db.put("ranked", n);
            if (!save()) { db = new JSONObject(backup); arc.delete(); return false; }
            File[] fs = dir().listFiles();
            for (int i = 0; fs != null && i < fs.length; i++) if (fs[i].getName().startsWith("ckpt_")) fs[i].delete();   /* .state·.state.tmp·.look */
            return true;
        } catch (Exception e) {
            try { db = new JSONObject(backup); } catch (Exception ignored) { }
            if (arc != null) arc.delete();
            return false;
        }
    }

    /** 시즌을 바꾸기 전 랭크 «준비» 경기를 무효로(사용자가 스스로 그만둔 것 — miss 아님). 메모리만 — 저장은 호출자. 닫은 id.
     *  예전 칸(위쪽 matches)의 «준비»는 안 건드린다(점수 없음 — 리그 화면 voidReady 가 닫는다) */
    private static java.util.List<String> cancelReadyMem(String why) throws Exception {
        java.util.List<String> ids = new java.util.ArrayList<>();
        JSONArray a = rmatchesIfAny();
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            if (!"ready".equals(m.optString("status"))) continue;
            m.put("status", "cancelled"); m.put("why", why); m.put("updated", System.currentTimeMillis());
            ids.add(m.optString("id"));
        }
        return ids;
    }

    /** 지난 시즌 한 줄(바꾸기 직전 값) */
    private static JSONObject seasonRow(JSONObject r, String kind, String file) throws Exception {
        RankCore.Me me = meOf(r);
        boolean pl = me.placementLeft > 0;
        JSONObject row = new JSONObject();
        row.put("n", Math.max(1, r.optInt("season", 1))); row.put("start", r.optLong("season_start")); row.put("end", System.currentTimeMillis());
        row.put("kind", kind);
        row.put("games", me.w + me.l + me.d); row.put("w", me.w); row.put("l", me.l); row.put("d", me.d);
        row.put("peak_lp", me.peakLp);
        row.put("peak_label", pl && me.peakLp == 0 ? "배치" : me.peakLp >= RankCore.MASTER_LP ? RankCore.TIER[7] : RankCore.label(me.peakLp));
        row.put("final_lp", me.lp);
        row.put("final_label", pl ? "배치" : me.master ? RankCore.TIER[7] : RankCore.label(me.lp));
        row.put("master", me.master); row.put("mr", me.mr); row.put("best", me.best);
        row.put("file", file);
        return row;
    }

    /* ── 168 §2 게임 안 서랍 «리그» 탭 자료 ── */

    /** 리그 탭 사본 — UI 스레드에서 한 번(MenuModel.LeagueInfo · 불변). pause = 쉼(경기 중)에서 연 서랍: 시즌 도구·내 캐릭터 줄은 안 넣는다 */
    static synchronized MenuModel.LeagueInfo leagueInfo(boolean pause) {
        MenuModel.LeagueInfo li = new MenuModel.LeagueInfo();
        RankCore.Me me = rankMe();
        li.season = season();
        long t0 = seasonStart();
        java.text.SimpleDateFormat md = new java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREA);
        md.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));
        li.since = t0 > 0 ? md.format(new java.util.Date(t0)) + "부터" : null;
        li.wld = me.w + "승 " + me.l + "패" + (me.d > 0 ? " " + me.d + "무" : "");
        java.util.List<JSONObject> past = seasons();
        java.util.List<String> rows = new java.util.ArrayList<>();
        for (int i = past.size() - 1; i >= 0 && rows.size() < 6; i--) {
            JSONObject x = past.get(i);
            String fin = x.optString("final_label");
            rows.add("시즌 " + x.optInt("n", 1) + ("restart".equals(x.optString("kind")) ? " 처음부터" : "") + " · " + fin + " · "
                    + x.optInt("w") + "승 " + x.optInt("l") + "패");
        }
        li.pastRows = rows.toArray(new String[0]);
        li.placement = me.placementLeft > 0;
        li.placementPlayed = 5 - me.placementLeft;
        SeasonPreview sp = newSeasonPreview();
        if (sp != null) { li.lp0 = sp.lp0; li.lp1 = sp.lp1; li.label0 = sp.label0; li.label1 = sp.label1; }
        li.canSeason = !pause && dbProblem == null;
        li.canMain = !pause;
        JSONObject open = startedOpenMatch();
        li.openVs = open != null ? oppNick(open) : null;
        if (open != null && isRanked(open)) {
            ForfeitPreview fp = forfeitPreview(open);
            li.openForfeit = fp.kind == ForfeitPreview.PLACEMENT ? "배치 1패" : fp.kind == ForfeitPreview.LP && fp.lpDelta != 0 ? signed(fp.lpDelta) + " LP" : "기권패";
        }
        java.util.List<String> hs = new java.util.ArrayList<>();
        for (JSONObject m : holds(5)) {
            String w = m.optString("why", "");
            if (w.length() > 40) w = w.substring(0, 40) + "…";
            hs.add(statusKo(m.optString("status")) + " · " + oppNick(m) + (w.isEmpty() ? "" : " · " + w));
        }
        li.holds = hs.toArray(new String[0]);
        Main mn = main();
        li.mainLabel = mn != null ? mainLabel(mn, -1) : null;
        li.name = myNick();
        li.rules = rulesShort();
        li.cheatGames = cheatGames();
        return li;
    }
    /** 이번 시즌 «치트» 표가 붙은 랭크 경기 수(정산·기권) — 서랍 전적 «치트 n» */
    static synchronized int cheatGames() {
        long t0 = seasonStart();
        int n = 0;
        JSONArray a = rmatchesIfAny();
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            String st = m.optString("status");
            if (m.optInt("cheat") != 0 && ("settled".equals(st) || "forfeit".equals(st)) && m.optLong("settled") >= t0) n++;
        }
        return n;
    }
    /** 규칙 글 — 리그 화면(LeagueActivity 규칙 탭)과 게임 안 서랍 리그 탭이 같이 쓴다(168) */
    static String rulesText() {
        return "경기 · " + POINTS + "선승(3판 2승) · 라운드 " + TIME + "초 · 카드 전원 올카드 해금. "
                + "랭크에 처음 들어오면 내 캐릭터·검질·카드를 한 번만 골라요 — 그다음부터는 상대를 찾자마자 그 캐릭터로 바로 붙어요(«새 캐릭터»로 다시 골라요 — 내 캐릭터부터 고르고 상대를 찾아요). "
                + "리그 판엔 저장·불러오기가 없고, 리셋·«나중에»는 경기를 그 자리에 저장하고 타이틀로 가요 — 타이틀에서 A 로 그 자리부터 이어 해요. 리그 세이브는 원작 세이브와 따로예요. "
                + "치트도 먹어요 — 그 판은 기록에 «치트»로 남아요.\n\n"
                + "상대 · 씨앗 하나로 만든 가상 플레이어 약 4만 명(모두 게임 CPU)과 예전 리그 12명(라이벌). "
                + "CPU 세기 = 원작 난도처럼(낮은 티어는 약하게) · 높은 티어는 자기 카드(효과 그대로). "
                + "실력(숨은 MMR)에 따라 CPU 가 주는 피해·받는 피해가 원작 1인 대전의 «하» 첫 판부터 «상» 끝판 값까지 오르고, AI 단계도 같이 올라요 — 같은 티어면 비슷하게 세요. "
                + "검호 위쪽부터는 CPU 도 자기 카드를 껴요 — 처음엔 ★ 카드 한 장, 검성쯤부터 ★★★ 카드 한 장을 더 껴요. 카드의 공격·방어 보너스는 원작처럼 그대로 붙어요.\n\n"
                + "티어 · 수련생 · 낭인 · 무사 · 검객 · 검호 · 달인 · 검성(각 ★1~5) · 무쌍(MR). 처음 5판은 배치 경기 — 끝나면 첫 티어가 정해져요. "
                + "이기면 LP 가 오르고(센 상대일수록 더), 지면 내려가요. 수련생은 안 깎이고 낭인은 반만. "
                + "3연승부터 연승 보너스, 진 상대를 재대결로 이기면 설욕 +" + RankCore.BONUS_REVENGE + ", 오늘 첫 승 +" + RankCore.BONUS_DAILY + ". "
                + "무사 이상은 바닥 아래로 떨어질 때 한 번 «강등 위기»로 버텨요. 무쌍은 강등이 없어요.\n\n"
                + "매칭 · «상대 발견!»이 뜬 뒤 나가면, 그 상대와 붙을 때까지 다음 찾기에 같은 상대가 나와요 (최대 24시간). 찾는 중에 그만두면 잡히지 않아요. "
                + "재대결은 같은 상대와 연속 3판까지.";
    }
    /** 서랍 리그 탭의 짧은 규칙 줄들(156px 갈무리7 한 줄씩 — LgmPages 가 넘치면 «…») */
    static String[] rulesShort() {
        return new String[]{
            "2선승 · 라운드 " + TIME + "초 · 카드 전부 열림",
            "상대 = 게임 CPU · 티어 따라 세기",
            "첫 5판 배치 → 첫 티어",
            "이기면 LP+ · 지면 LP− (수련생 그대로)",
            "3연승 보너스 · 설욕 +" + RankCore.BONUS_REVENGE + " · 첫 승 +" + RankCore.BONUS_DAILY,
            "무사 위는 한 번 «강등 위기»로 버팀",
            "리셋·나중에 = 저장 후 타이틀 · A 로 이어서",
            "치트 판은 기록에 «치트»",
            "재대결은 같은 상대와 3판까지" };
    }
    /** 168 §10·S1 — 리그 판을 켤 때 쓸 언어: 시작한 경기(멈춘·이어 할)가 있으면 그 경기 롬 판(rom_md5 → 한글패치 판이면 ko · 순정 판이면 원어)으로 고정 —
     *  언어를 바꿔 롬 사본이 달라지면 «롬이 바뀜»으로 막히기 때문. 그 밖(경기 없음·롬을 안 적은 옛 경기)은 지금 설정 그대로. 새 언어는 다음 리그 판부터 */
    static String matchLang(JSONObject open, String cur) {
        if (open == null || "ready".equals(open.optString("status"))) return cur;
        String md5 = open.optString("rom_md5", "");
        String lab = md5.isEmpty() ? null : romLabel(md5);
        if (lab == null) return cur;
        boolean ko = lab.contains("한글패치");
        if (ko) return "ko";
        return "ko".equals(cur) ? "ja" : cur;                 /* 순정 판 — ja·en 은 같은 롬(코어 언어만 다름) */
    }

    static File archiveDir() { File d = new File(dir(), "archive"); d.mkdirs(); return d; }
    /** 보관 파일 — league/archive/ranked-s<N>-<season|restart>-<yyyyMMdd-HHmmss>.json 에 {"v":1, kind, archived_at, app, ranked: 지금 칸 전체}.
     *  원자 저장 뒤 다시 읽어 풀어 보고 내용이 같을 때만 돌려준다(league.json 을 바꾸기 전에). 실패면 null — 쓰다 만 파일은 지운다 */
    private static File writeArchive(String kind) {
        File f = null;
        try {
            JSONObject r = ranked();
            String base = "ranked-s" + season() + "-" + kind + "-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
            File d = archiveDir();
            f = new File(d, base + ".json");
            for (int i = 2; f.exists(); i++) f = new File(d, base + "-" + i + ".json");   /* 같은 초에 두 번이어도 덮지 않게 */
            JSONObject a = new JSONObject().put("v", 1).put("kind", "ranked_" + kind).put("archived_at", System.currentTimeMillis())
                    .put("app", appVer).put("ranked", r);
            if (!atomicWrite(f, a.toString(1).getBytes("UTF-8"))) return null;
            JSONObject back = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            if (back.optInt("v") != 1 || !("ranked_" + kind).equals(back.optString("kind")) || !sameJson(back.optJSONObject("ranked"), r)) { f.delete(); return null; }
            return f;
        } catch (Exception e) {
            if (f != null) f.delete();
            return null;
        }
    }
    /** JSON 두 값이 같은 내용인가(키 순서·숫자 꼴은 안 본다) — 보관 파일을 다시 읽어 맞춰 볼 때 */
    static boolean sameJson(Object a, Object b) {
        if (a == null || b == null || a == JSONObject.NULL || b == JSONObject.NULL) return (a == null || a == JSONObject.NULL) && (b == null || b == JSONObject.NULL);
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject x = (JSONObject) a, y = (JSONObject) b;
            if (x.length() != y.length()) return false;
            java.util.Iterator<String> it = x.keys();
            while (it.hasNext()) { String k = it.next(); if (!y.has(k) || !sameJson(x.opt(k), y.opt(k))) return false; }
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray x = (JSONArray) a, y = (JSONArray) b;
            if (x.length() != y.length()) return false;
            for (int i = 0; i < x.length(); i++) if (!sameJson(x.opt(i), y.opt(i))) return false;
            return true;
        }
        if (a instanceof Number && b instanceof Number) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        return a.equals(b);
    }
}
