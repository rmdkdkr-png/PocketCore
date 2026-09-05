package com.dudu.pocketcore;

import java.io.File;
import java.io.FileInputStream;

/**
 * PocketCore 가 아우르는 게임 표.
 *
 * 게임을 늘리는 일 = 여기 한 줄 늘리는 일. 코어 이름·한패·음성팩이 한 자리에 모여 있어야
 * 「어느 코어가 어느 롬에 붙는지」를 코드 여기저기서 따로 판단하는 사고가 안 난다.
 * (실제로 그 사고가 났다 — 코어 파일 이름이 libretro_core.so 라는 일반명이라
 *  SVC 코어인 줄 모르고 SS2 만 빌드해 놓고 「갱신됐겠지」 한 적이 있다. 그래서 이름을 바꿨다.)
 *
 * 지금은 네오지오 포켓뿐이지만 표에 플랫폼이 있는 이유는, 이 앱이 원래 libretro 프론트엔드라
 * 코어만 갈아 끼우면 다른 기기 게임(예: 환세취호전 — DOS)도 같은 틀로 받을 수 있어서다.
 */
public final class Games {

    /** 롬 헤더에서 표식을 읽는 위치·길이 (네오지오 포켓 규약). */
    private static final int TAG_OFF = 0x24, TAG_LEN = 12;

    public static final class Game {
        public final String id;        /** 파일 이름에 쓰는 짧은 이름 */
        public final String platform;  /** "ngpc" — 나중에 "dos" 등이 붙을 자리 */
        public final String tag;       /** 롬 헤더 표식. 앞부분만 맞으면 된다 */
        public final String ko;        /** 사람에게 보일 이름 */
        public final String core;      /** 동봉 코어 파일명 (nativeLibraryDir 안) */
        public final boolean patchable;/** assets/patch 에 이 게임 패치가 있을 수 있는가 */
        public final String baseLang;  /** 번역 패치가 **덮는** 원래 언어 (ngp_language 값) */
        public final String voice;     /** 해설 음성팩 파일명. 없으면 null */
        public final String[] features;/** 이 게임이 쓰는 기능 토큰 (아래 어휘). 설정 필터·런처
                                           배지·배포 스크립트·게임별 문서가 전부 이 하나에서 나온다. */

        Game(String id, String platform, String tag, String ko,
             String core, boolean patchable, String baseLang, String voice,
             String[] features) {
            this.id = id; this.platform = platform; this.tag = tag; this.ko = ko;
            this.core = core; this.patchable = patchable;
            this.baseLang = baseLang; this.voice = voice; this.features = features;
        }

        /** 우리가 이 게임에 «준비해 둔 것»이 있는가 — 한글패치 또는 입력 패치(원버튼·빠른 기본기).
         *  롬 스캔이 이걸로 거른다: 준비된 것만 데려온다. 표에 있어도 준비물이 없으면
         *  (월화 일본판·카드 파이터즈 일본판/2편) 스캔이 기본으로는 안 집는다. */
        public boolean prepared() {
            if (patchable) return true;
            for (String f : features)
                if (f.startsWith("sp:") || f.startsWith("fastcd:")) return true;
            return false;
        }

        /** 이 게임이 해당 기능을 쓰는가. 설정 필터·배지·패드 프로필의 단일 판정. */
        public boolean has(String feature) {
            for (String f : features) if (f.equals(feature)) return true;
            return false;
        }

        /** 그 언어의 번역 패치 파일 이름. 원어(일·영)는 패치가 필요 없으므로 null.
         *  ko-ja / ko-en 은 **같은 한글 패치**를 쓰고 바탕 언어만 다르다. */
        public String patchFor(String lang) {
            if (!patchable || lang == null || !lang.startsWith("ko")) return null;
            return id + "_ko.ips";
        }
    }

    /* 코어 두 벌만 동봉한다.
     *   libretro_ss2.so — SS2 전용. 해설·더빙이 들어 있어 크다.
     *   libretro_svc.so — SVC 원버튼 엔진. 다른 NGPC 롬도 그냥 돈다(엔진은 롬 표식을 보고 잔다).
     * 표식이 더 구체적인 것을 앞에 둔다 — SAMURAI2 가 SAMURAI 보다 먼저 걸려야 한다.
     *
     * baseLang 은 **번역 패치가 덮는 원래 언어**다. 네오지오 포켓 롬은 일어와 영어를 함께
     * 담고 BIOS 설정(ngp_language)으로 고른다. 번역 패치는 그중 한쪽만 덮으므로,
     * 패치를 쓸 때는 그 언어로 맞춰 줘야 번역이 보인다. 반대로 덮지 않은 쪽은 **원문 그대로**라
     * 같은 롬 하나로 한국어·일본어·영어가 다 나온다.
     *
     * SvC 는 실측으로 확정했다 — 패치본과 순정을 같은 프레임에서 화소 비교했더니
     * 일어에선 12613 화소가 다르고 영어에선 **0 화소**, 즉 영어는 손대지 않았다.
     * 나머지는 같은 방식으로 확인하기 전까지 일어로 둔다(대부분의 한패가 그렇다). */
    /* ── 기능 토큰 어휘 (features[]) ──────────────────────────────────
       설정 필터·런처 배지·배포 스크립트·게임별 문서의 단일 출처. 이 토큰들은
       코어의 권위 게이팅(svcsp_rom_ok / kofsp_rom_ok / ss2comm_rom_is_ss2, native C)의
       **거울**이다 — 코어가 어느 롬에 어느 엔진을 붙이는지 바뀌면 여기도 따라야 한다
       (기존 tag 문자열이 코어 헤더매칭을 거울한 것과 같은 성질, 단지 한 곳에 명시).
         F_SP_SVC  원버튼 필살기(SvC)  = ngp_svcsp_engine · ngp_svcsp_toast · ngp_svcsp_land
         F_SP_KOF  원버튼 필살기(R-2)  = ngp_kofsp_engine · ngp_kofsp_toast
         F_SP_SS2  SS2 간이입력(ABLE) = ngp_ss2sp
         F_BASICS  강약 4버튼 구분      = ngp_svcsp_basics · ngp_svcsp_holdsync
         F_ACTSHOW 판독 오버레이        = pocketcore_svc_actshow
         F_BAND    기술명 띠            = ngp_svcsp_band
         F_FASTCD_SVC / F_FASTCD_KOF   빠른 기본기 = pocketcore_<id>_fastcd
         F_COMM    캐릭터 해설·더빙     = ngp_ss2sp_comm(+dub/vol/lang/draw)
         F_SIDES   기둥 아트            = ngp_ss2sp_sides   */
    public static final String
        F_SP_SVC="sp:svc", F_SP_KOF="sp:kof", F_SP_SS2="sp:ss2", F_BASICS="basics",
        F_ACTSHOW="actshow", F_BAND="band", F_FASTCD_SVC="fastcd:svc", F_FASTCD_KOF="fastcd:kof",
        F_COMM="comm", F_SIDES="sides";
    private static final String[] NONE = {};

    private static final Game[] ALL = {
        new Game("ss2", "ngpc", "SAMURAI2",     "사무라이 쇼다운! 2",
                 "libretro_ss2.so", true, "japanese", null,             /* 3.90: 해설·더빙 아웃(유저) — 음성팩·배지 없음 */
                 new String[]{ F_SP_SS2, F_SIDES }),
        new Game("svc", "ngpc", "SNKvsCAPCOM1", "정상결전 최강 파이터즈",
                 "libretro_svc.so", true, "japanese", null,   /* 실측 확정 */
                 new String[]{ F_SP_SVC, F_BAND, F_BASICS, F_ACTSHOW, F_FASTCD_SVC }),
        new Game("ss1", "ngpc", "SAMURAI",      "사무라이 쇼다운!",
                 "libretro_svc.so", true, "japanese", null, NONE),
        new Game("lb",  "ngpc", "LASTBLADE",    "월화의 검사 특별편",
                 "libretro_svc.so", true, "japanese", null, NONE),
        /* 월화 일본판 — UE판과 84% 다른 별개 빌드(GEKKA v1.1, 실측 2026-09-03)라 한패를
           못 바른다. 그래도 표에 있어야 「정체불명 카드」가 아니라 「아는데 미지원」으로
           보인다 — 조용한 부재는 늘 버그로 읽힌다(이식소 제보). 게임 자체는 순정으로 돈다. */
        new Game("lbj", "ngpc", "GEKKA",        "월화의 검사 특별편 (일본판)",
                 "libretro_svc.so", false, "japanese", null, NONE),
        /* 아래 둘의 표식은 롬셋 헤더에서 실측 — R2 는 뒤가 공백 패딩이라 앞 일치로 충분. */
        new Game("kofr2", "ngpc", "KOF R2",       "더 킹 오브 파이터즈 R-2",
                 "libretro_svc.so", true, "japanese", null,
                 new String[]{ F_SP_KOF, F_FASTCD_KOF }),
        new Game("ffc",   "ngpc", "RB_F_CONTACT", "아랑전설 퍼스트 컨택트",
                 "libretro_svc.so", true, "japanese", null, NONE),
        new Game("ms1",   "ngpc", "METALSLUG1ST", "메탈슬러그 1st 미션",
                 "libretro_svc.so", true, "japanese", null, NONE),
        new Game("ms2",   "ngpc", "METALSLUG2ND", "메탈슬러그 2nd 미션",
                 "libretro_svc.so", true, "japanese", null, NONE),
        /* 카드 파이터즈 — 머리표를 실측해 세 갈래로 갈랐다(0x24~).
           「CARD FIGHT E」 영문판(UE) · 「CARD FIGHTER」 일본판(J) · 「CARD FIGHT 2」 2편.
           ★ 한패는 «영문판 전용»이다. 일본판은 27.6% 다른 별개 빌드라 얹으면 글자가 깨진다
           (실측: 캐릭터 고르기 화면이 「進終最大」技血罡数後N」로 깨졌다). 그래서 갈라 둔다.
           baseLang 이 «english» 인 첫 항목이다 — 이 게임은 언어 분기가 아예 없고(0x6F87 참조 1곳,
           그마저 자료다) 판이 따로라, 한패가 덮은 것은 영문 쪽이다. */
        new Game("cfc1",  "ngpc", "CARD FIGHT E", "카드 파이터즈 클래시",
                 "libretro_svc.so", true,  "english",  null, NONE),
        new Game("cfc1j", "ngpc", "CARD FIGHTER", "카드 파이터즈 클래시 (일본판)",
                 "libretro_svc.so", false, "japanese", null, NONE),
        new Game("cfc2",  "ngpc", "CARD FIGHT 2", "카드 파이터즈 클래시 2",
                 "libretro_svc.so", true, "japanese", null, NONE),      /* 한패 v0.1 (2026-09-05, 일본판 전용) — patchable 은 색인으로 못 바꿔 APK 3.89 */
    };

    /** 마스터 표 전체 (identify 순서 — 구체 tag 먼저). 배포·문서 export 가 읽는다. */
    public static Game[] all() { return ALL; }

    /** 사람에게 보이는 순서 — 설정의 게임별 소절, 배포 표, 문서. identify 순서(ALL)와 다르다:
     *  ALL 은 구체 tag 가 먼저여야 하고, 표시는 대표작(SvC)이 먼저가 자연스럽다. 한패 없는
     *  lbj 는 여기 없다(배포 표에서 빠짐) — displayOrder() 는 빠진 게임을 뒤에 붙여 준다.
     *  배포 스크립트는 ExportGames 가 뽑은 이 배열을 그대로 쓴다(games_catalog 의 것은 예비). */
    public static final String[] DISPLAY_ORDER =
        { "svc", "ss2", "ss1", "lb", "kofr2", "ffc", "cfc1", "cfc2", "ms1", "ms2" };
    public static Game[] displayOrder() {
        java.util.ArrayList<Game> out = new java.util.ArrayList<>();
        for (String id : DISPLAY_ORDER) for (Game g : ALL) if (g.id.equals(id)) out.add(g);
        for (Game g : ALL) if (!out.contains(g)) out.add(g);
        return out.toArray(new Game[0]);
    }

    /** 고를 수 있는 언어.
     *  네오지오 포켓 롬은 일어와 영어를 **함께** 담고 BIOS 설정으로 고른다.
     *  한글 패치는 그중 한쪽 표만 덮으므로, 덮인 쪽으로 맞춰야 한글이 보인다.
     *  어느 쪽을 덮었는지는 **게임마다 다르다** — 그래서 바탕을 손으로 고를 수 있게 둔다.
     *  (SvC 는 실측으로 일어 쪽이 확정됐다. 영어 화면은 패치 전후가 0 화소 차이였다.) */
    public static final String[] LANGS    = { "ko-ja", "ko-en", "ja", "en" };
    public static final String[] LANGS_KO = { "한국어(일어 바탕)", "한국어(영어 바탕)",
                                              "일본어", "English" };

    /** 그 언어를 내려면 코어의 ngp_language 를 무엇으로 둬야 하나. */
    public static String ngpLanguage(Game g, String lang) {
        if ("en".equals(lang) || "ko-en".equals(lang)) return "english";
        if ("ja".equals(lang) || "ko-ja".equals(lang)) return "japanese";
        return (g != null) ? g.baseLang : "japanese";
    }

    /** 어느 게임인지 롬 헤더로 가른다. 모르는 롬이면 null — 그래도 순정 코어로 돌아간다. */
    /** 내려받은 «덧붙임» 게임들. games.json 이 있으면 채워진다. 없으면 빈 배열.
     *  ★ 내장표(ALL)를 못 덮는다 — identify 는 늘 ALL 을 먼저 본다. */
    private static Game[] EXTRA = new Game[0];

    /** 앱 업데이트 없이 게임을 늘리는 자리. 색인 파일을 읽어 EXTRA 를 채운다.
     *  깨져 있으면 조용히 아무것도 안 한다 — 이 경로는 부팅 전에 도는 곳이라
     *  예외 하나가 「롬을 못 알아본다」로 보인다. */
    public static void loadExtras(java.io.File dir) {
        try {
            java.io.File f = new java.io.File(dir, "games.json");
            if (!f.isFile()) return;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try { int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; } }
            finally { in.close(); }
            org.json.JSONArray a = new org.json.JSONObject(new String(b, "UTF-8")).getJSONArray("games");
            java.util.ArrayList<Game> out = new java.util.ArrayList<Game>();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                String id = o.optString("id", null), tag = o.optString("tag", null);
                if (id == null || tag == null || tag.length() == 0) continue;
                boolean dup = false;                       /* ② 내장표와 겹치면 버린다 */
                for (Game g : ALL) if (g.id.equals(id) || g.tag.equals(tag)) { dup = true; break; }
                if (dup) continue;
                org.json.JSONArray fa = o.optJSONArray("features");
                String[] fs = new String[fa == null ? 0 : fa.length()];
                for (int k = 0; k < fs.length; k++) fs[k] = fa.optString(k, "");
                out.add(new Game(id, o.optString("platform", "ngpc"), tag, o.optString("ko", id),
                                 o.optString("core", "libretro_svc.so"), o.optBoolean("patchable", false),
                                 o.optString("baseLang", "japanese"),
                                 o.isNull("voice") ? null : o.optString("voice", null), fs));
            }
            EXTRA = out.toArray(new Game[0]);
        } catch (Throwable ignored) {                      /* ③ 무슨 일이 있어도 내장표는 산다 */
        }
    }

    public static Game identify(String romPath) {
        String tag = readTag(romPath);
        if (tag == null) return null;
        for (Game g : ALL)   if (tag.startsWith(g.tag)) return g;   /* ① 내장표가 늘 먼저 */
        for (Game g : EXTRA) if (tag.startsWith(g.tag)) return g;
        return null;
    }

    /** 내장표 + 내려받은 것. 진열장·설정이 게임을 훑을 때 쓴다. */
    public static Game[] allIncludingExtras() {
        if (EXTRA.length == 0) return ALL;
        Game[] r = new Game[ALL.length + EXTRA.length];
        System.arraycopy(ALL, 0, r, 0, ALL.length);
        System.arraycopy(EXTRA, 0, r, ALL.length, EXTRA.length);
        return r;
    }

    /** 표에 없는 롬이 쓸 코어. 원버튼 엔진은 롬 표식을 보고 스스로 자므로 안전하다. */
    public static String fallbackCore() { return "libretro_svc.so"; }

    private static String readTag(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] h = new byte[TAG_OFF + TAG_LEN];
            int n = 0;
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r < 0) break;
                n += r;
            }
            if (n < h.length) return null;
            return new String(h, TAG_OFF, TAG_LEN, "US-ASCII");
        } catch (Exception e) {
            return null;
        }
    }

    private Games() { }
}
