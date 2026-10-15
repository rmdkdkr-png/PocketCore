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
 *   체크포인트 = 루트/league/ckpt_<경기id>.state (앱이 내려갈 때 그 순간만, 한 번 쓰면 지운다)
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
    /** 컬러 이름 — 0 게임 기본 · 1 2P색(같은 캐릭터 미러전에서 게임이 P2 에 쓰는 색) · 2~15 프리셋 · 16·17 내 색(Look — 모든 모드 컬러).
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
    private static File file() { return new File(dir(), "league.json"); }
    private static File bak()  { return new File(dir(), "league.json.bak"); }

    private static JSONObject db;
    /** 기록 파일을 못 읽는데 옆으로 옮기지도 못했다 — 이때는 새 기록을 메모리에만 두고 절대 저장하지 않는다(진짜 기록을 덮지 않게). */
    static volatile String dbProblem = null;

    /** 기록을 읽는다(없으면 새로). 깨졌으면 직전 판(.bak)으로. 명단이 옛 판(1단계: 상대 1명)이면 빠진 상대를 더해 바로 저장한다
     *  — 이미 있는 상대의 설정·점수·전적은 그대로(같은 상대는 다시 만나도 설정이 같다). 저장이 실패해도 메모리 판으로 쓰고,
     *  다음 저장 때 같이 들어간다(더하는 내용이 늘 같아서 다음 실행에서 다시 더해도 결과가 같다). */
    static synchronized JSONObject db() {
        if (db != null) return db;
        JSONObject j = read(file());
        if (j == null) j = read(bak());
        if (j == null) {
            /* 둘 다 못 읽는데 파일은 있다(깨짐·다른 판의 형식 등) — 새 기록으로 덮지 않게 옆으로 치워 둔다(지우지 않는다) */
            long t = System.currentTimeMillis();
            boolean moved = (!file().exists() || file().renameTo(new File(dir(), "league.json.unreadable-" + t)))
                          & (!bak().exists() || bak().renameTo(new File(dir(), "league.json.bak.unreadable-" + t)));
            if (!moved) dbProblem = "리그 기록 파일을 읽지도 옮기지도 못했습니다 — 저장소 권한을 확인하고 앱을 다시 여세요(기록은 건드리지 않았습니다)";
            j = fresh();
        }
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
    static synchronized void setPrefLook(int stageSel, int color) {
        try {
            JSONObject p = pref();
            if (p.optInt("stage", -1) == stageSel && p.optInt("color", 0) == color) return;
            p.put("stage", stageSel); p.put("color", color);
            save();                                     /* 실패해도 메모리 값으로 쓰고 다음 저장 때 같이 들어간다 */
        } catch (Exception ignored) { }
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
            rule.put("cards", "all");                   /* 초기안: 전원 올카드 해금(기획서 2장) — CPU 는 카드 없음 */
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

    /** 오류 보류 — 원인과 복구 방법을 같이 남긴다(기획서 «판정 오류는 보류하고 원인과 복구 방법을 기록»). 점수는 안 움직인다. */
    static synchronized boolean hold(String id, String why, String fix) {
        JSONObject m = matchById(id);
        if (m == null || terminal(m.optString("status"))) return false;
        String backup = db.toString();                     /* 저장이 실패하면 메모리를 되돌린다(끝난 상태로 굳어 다시 못 바꾸는 일이 없게) */
        try {
            m.put("status", "hold"); m.put("why", why); m.put("fix", fix);
            m.put("updated", System.currentTimeMillis());
            if (!save()) { db = new JSONObject(backup); return false; }
            ckpt(id).delete();
            return true;
        } catch (Exception e) {
            try { db = new JSONObject(backup); } catch (Exception ignored) { }
            return false;
        }
    }

    /** 무효 — 아직 대진 전(준비)에 멈춘 경기. 대진 준비 실패는 점수에 넣지 않는다. */
    static synchronized boolean cancel(String id, String why) {
        JSONObject m = matchById(id);
        if (m == null || !"ready".equals(m.optString("status"))) return false;
        String backup = db.toString();                     /* 저장 실패면 «준비» 그대로 — 다시 시도하거나 그대로 경기를 이어 할 수 있게 */
        try {
            m.put("status", "cancelled"); m.put("why", why); m.put("updated", System.currentTimeMillis());
            if (!save()) { db = new JSONObject(backup); return false; }
            ckpt(id).delete();
            return true;
        } catch (Exception e) {
            try { db = new JSONObject(backup); } catch (Exception ignored) { }
            return false;
        }
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
    static synchronized JSONObject settle(String matchId, int[] st) {
        JSONObject m = matchById(matchId);
        if (m == null) return null;
        if (terminal(m.optString("status"))) return m;
        String backup = db.toString();
        try {
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
                res.put("diff_ok", rk ? rankedKnobWhy(rule, st) == null : st[18] == 1 + 3 * rule.optInt("diff"));
                if (st.length > 21) res.put("cpu_cap", st[21]);
            }
            res.put("at", System.currentTimeMillis());
            m.put("result", res);
            int want = (rule.optInt("char") << 4) | (rule.optInt("style") << 3);
            String why = null;
            if ((p2 & 0xF8) != want)
                why = String.format("대진 불일치: 요청 %s·%s, 실제 P2 블록 0x%02X", CHAR[rule.optInt("char")], STYLE[rule.optInt("style")], p2);
            else if (need != rule.optInt("points") || time != rule.optInt("time"))
                why = "규칙 불일치: 실효 포인트 " + need + "·시간 " + time + " (랭크전 " + rule.optInt("points") + "·" + rule.optInt("time")
                    + ") — 1인 대전 첫 경기가 아닌 곳(무한 대전 등)에서 결판이 났습니다";
            if (why != null) {
                m.put("status", "hold"); m.put("why", why);
                m.put("fix", "점수는 그대로입니다. 새로 도전하면 1인 대전 캐릭터 고르기로 자동으로 들어갑니다.");
                if (!save()) throw new IOException("save");
                ckpt(matchId).delete();
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
            else if (rk) why2 = rankedKnobWhy(rule, st);     /* 랭크: 손잡이(단계·상한·배율)가 실제로 먹었나 */
            else if (st.length > 20 && st[18] != 1 + 3 * rule.optInt("diff"))
                why2 = "난도 불일치: CPU 단계 " + st[18] + " (요청 난도 " + DIFF[rule.optInt("diff")] + " → " + (1 + 3 * rule.optInt("diff")) + ")";
            if (why2 != null) {
                m.put("status", "hold"); m.put("why", why2);
                m.put("fix", "점수는 그대로입니다. 같은 상대에게 다시 도전하면 새 경기로 기록됩니다.");
                if (!save()) throw new IOException("save");
                ckpt(matchId).delete();
                return m;
            }
            if (rk) applyRanked(m, out, false); else apply(m, me, o, out);
            m.put("status", "settled");
            m.put("settled", System.currentTimeMillis());
            if (!save()) throw new IOException("save");
            ckpt(matchId).delete();
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
            m.put("forfeit", true);
            if (isRanked(m)) applyRanked(m, "L", true); else apply(m, me(), oppById(m.optString("opp")), "L");
            m.put("status", "forfeit");
            m.put("settled", System.currentTimeMillis());
            if (!save()) throw new IOException("save");
            ckpt(matchId).delete();
            return m;
        } catch (Exception e) {
            try { db = new JSONObject(backup); } catch (Exception ignored) { }
            return null;
        }
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
                if ("cancelled".equals(s) && isRanked(m)) continue;   /* 랭크 무효 = 첫 라운드 전에 나감뿐(상대는 잡힌 채) — 오류가 아니라 목록에 안 쌓는다 */
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

    /** 처음 한 번 — 예전 리그 기록에서 이어받아 "ranked" 칸을 만든다. 예전 칸(me·opps·order)은 지우지 않는다. 바뀌었으면 true */
    private static boolean upgradeRanked(JSONObject j) {
        if (j.optJSONObject("ranked") != null) return false;
        try {
            JSONObject r = new JSONObject();
            r.put("rv", RANK_RV); r.put("gen", RankCore.GEN);
            r.put("seed", String.format("%016x", new java.security.SecureRandom().nextLong()));
            r.put("season", 1); r.put("season_start", System.currentTimeMillis());
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
            JSONObject ps = new JSONObject();
            for (int i = 0; os != null && i < os.length(); i++) {
                JSONObject o = os.optJSONObject(i);
                if (o.optInt("w") + o.optInt("l") + o.optInt("d") == 0) continue;
                String id = o.optString("id");
                ps.put(id, new JSONObject().put("w", o.optInt("l")).put("l", o.optInt("w")).put("d", o.optInt("d"))
                        .put("last", last.containsKey(id) ? last.get(id) : 0).put("broke", o.optInt("broke_streak"))
                        .put("mmr_adj", 0).put("lp_adj", 0));
            }
            r.put("players", ps);
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
        } catch (Exception e) { pf = new JSONObject(); }
        return pf;
    }
    static String nickOf(android.content.Context c, RankCore.Player p) { return pfOf(c, p).optString("nick", p.nick); }

    /* ── 매칭 ── */
    /** 상대 찾기 — 잡힌 상대가 있으면 그 상대, 아니면 RankCore.pick. 고른 상대는 카드를 띄우기 전에 «잡힘»으로 바로 저장한다
     *  (카드에서 나가거나 캐릭터 고르기에서 뒤로 가도 다음 찾기에 같은 상대 — 쉬운 상대만 골라 받기를 막는다) */
    static synchronized RankCore.Pick rankedPick(android.content.Context c) {
        RankCore.Me me = rankMe();
        long seed = rankSeed();
        RankCore.Pick pk = RankCore.pick(seed, me, today(), book(), new RankCore.Rng(System.nanoTime() ^ seed));
        try {
            JSONObject pd = ranked().optJSONObject("pending");
            if (!pk.pending || pd == null || !pk.p.id.equals(pd.optString("id"))) {
                RankCore.Knobs k = RankCore.knobs(pk.mmr, pk.p.personaIdx);
                ranked().put("pending", new JSONObject().put("id", pk.p.id).put("at", System.currentTimeMillis())
                        .put("mmr", Math.round(pk.mmr * 10) / 10.0).put("stage", pk.p.stage)
                        .put("knobs", new JSONObject().put("diff", k.diff).put("lv", k.lv).put("cap", k.cap).put("floor", k.floor).put("atk", k.atk).put("dfn", k.dfn)));
                save();
            }
        } catch (Exception ignored) { }
        return pk;
    }

    /** 랭크 경기 시작 전 영속 저장 — 상대 모습 사본·손잡이·시작 값. v·k 는 카드에서 대진을 정한 그 값(허브에 넣은 손잡이와 같아야
     *  정산의 «난도 확인»을 통과한다 — 자정을 넘겨도 같게 다시 계산하지 않는다). 열린 경기가 있거나 저장 실패면 null */
    static synchronized JSONObject newRankedMatch(android.content.Context c, OppView v, RankCore.Knobs k, int stage, int rematchN) {
        try {
            RankCore.Player p = v != null ? v.p : null;
            if (openMatch() != null || p == null || k == null || !oppSupported(p.ch, p.sty)) return null;
            RankCore.Me me = rankMe();
            JSONObject pf = pfOf(c, p);
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
            rule.put("cpu", new JSONObject().put("lv", k.lv).put("cap", k.cap).put("floor", k.floor).put("atk", k.atk).put("dfn", k.dfn));
            m.put("rule", rule);
            m.put("start", new JSONObject().put("mmr", Math.round(me.mmr * 10) / 10.0).put("lp", me.lp).put("mr", me.mr).put("master", me.master)
                    .put("opp_mmr", Math.round(v.mmr * 10) / 10.0).put("opp_lp", v.lp).put("opp_mr", v.mr)
                    .put("tier", me.placementLeft > 0 ? "배치" : me.master ? RankCore.TIER[7] : RankCore.label(me.lp)).put("placement_left", me.placementLeft));
            m.put("opp_snap", new JSONObject().put("nick", pf.optString("nick", p.nick)).put("ch", p.ch).put("sty", p.sty).put("label", v.label)
                    .put("lp", v.lp).put("mr", v.master ? v.mr : 0).put("title", pf.optString("title")).put("region", ProfileGen.regionLabel(pf))
                    .put("av", pf.optString("av")).put("persona", p.persona).put("mood", pf.optString("mood")).put("legacy", p.legacy));
            m.put("rematch_n", rematchN);
            m.put("look", new JSONObject().put("stage", stage).put("color", 0));
            JSONObject ps = players();
            JSONObject pr = ps.optJSONObject(p.id);
            if (pr == null) { pr = new JSONObject().put("w", 0).put("l", 0).put("d", 0).put("last", 0).put("broke", 0).put("mmr_adj", 0).put("lp_adj", 0); ps.put(p.id, pr); }
            if (pr.optJSONObject("pf") == null) pr.put("pf", pf);       /* 처음 만난 모습을 글자 그대로 */
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
        if ("W".equals(out)) { pr.put("w", pr.optInt("w") + 1); pr.put("last", 1); pr.put("broke", 0); }
        else if ("L".equals(out)) { pr.put("l", pr.optInt("l") + 1); pr.put("last", -1); if (streak0 >= 2) { pr.put("broke", streak0); m.put("broke_streak", streak0); } }
        else { pr.put("d", pr.optInt("d") + 1); pr.put("last", 0); }
        pr.put("mmr_adj", Math.max(-150, Math.min(150, pr.optDouble("mmr_adj") + o.oppMmrDelta)));
        pr.put("lp_adj", Math.max(-600, Math.min(600, pr.optInt("lp_adj") + o.oppLpDelta)));
        pr.put("at", System.currentTimeMillis());
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

    /** 랭크 경기 정산의 확인(대진·규칙은 settle 이 본다) — CPU 손잡이가 실제로 먹었나: 첫 라운드 단계·배율, 결판 때 상한 */
    private static String rankedKnobWhy(JSONObject rule, int[] st) {
        JSONObject k = rule.optJSONObject("cpu");
        if (k == null || st.length <= 20) return null;
        if (st[18] != k.optInt("lv") || st[19] != k.optInt("atk") || st[20] != k.optInt("dfn") || (st.length > 21 && st[21] != k.optInt("cap")))
            return String.format("난도 불일치: CPU 단계 %d·상한 %d·배율 %04X/%04X (요청 %d·%d·%04X/%04X)", st[18], st.length > 21 ? st[21] : -1, st[19], st[20],
                    k.optInt("lv"), k.optInt("cap"), k.optInt("atk"), k.optInt("dfn"));
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
        return outcomeKo(m) + sc + " · " + lp;
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
}
