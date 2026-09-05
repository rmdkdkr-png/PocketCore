package com.dudu.pocketcore;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * options.txt 를 읽고 쓰는 자리 + 어떤 항목이 있는지 아는 표.
 *
 * 지금까지 설정은 **파일을 손으로 고치는 것**뿐이었다. 코어 오버레이(아래+옵션)에 몇 개가
 * 있지만 그건 코어가 그리는 것이라 게임 밖에서는 못 쓰고, 항목도 코어가 정하는 것만 나온다.
 * 앱 쪽 설정(언어 같은)은 아예 낄 자리가 없었다.
 *
 * 그래서 여기 한 표에 모은다. 항목을 늘리는 일 = 이 표에 한 줄 늘리는 일.
 * 값은 전부 options.txt 한 파일에 남는다 — 코어 옵션이든 앱 옵션이든 읽는 쪽이 알아서 가져간다.
 */
public final class Settings {

    /** 한 항목. 고를 수 있는 값과 사람에게 보일 이름을 같이 들고 있다. */
    public static final class Item {
        public final String key, label, help;
        public final String[] vals, names;
        public final String def;
        /** 이 항목이 붙는 기능 토큰(Games.F_*). null = 범용(모든 게임). 설정 화면이 현재
         *  게임의 features 로 거를 때 쓴다. key 는 그대로라 options.txt 는 안 바뀐다. */
        public String feature;
        /** 게임 id 스코프(조작 패치처럼 특정 게임에만 뜨는 항목). null = 스코프 없음. */
        public String game;
        /** 실행 전 선택창(LaunchSheet)에도 보이는 항목인가 — 그 게임에 「적용할 것」으로 고를 만한 토글만. */
        public boolean launch;
        Item(String key, String label, String help, String[] vals, String[] names, String def) {
            this.key = key; this.label = label; this.help = help;
            this.vals = vals; this.names = names; this.def = def;
        }
        Item f(String feature) { this.feature = feature; return this; }
        Item g(String game) { this.game = game; return this; }
        Item l() { this.launch = true; return this; }
        public int indexOf(String v) {
            for (int i = 0; i < vals.length; i++) if (vals[i].equals(v)) return i;
            return indexOf0();
        }
        private int indexOf0() {
            for (int i = 0; i < vals.length; i++) if (vals[i].equals(def)) return i;
            return 0;
        }
    }

    private static final String[] ONOFF   = { "enabled", "disabled" };
    private static final String[] ONOFF_K = { "켬", "끔" };

    /** 묶음 이름 → 항목들. 순서가 곧 화면 순서다. */
    public static final LinkedHashMap<String, Item[]> GROUPS = new LinkedHashMap<>();
    static {
        GROUPS.put("게임", new Item[]{
            new Item("pocketcore_autosave", "오토세이브",
                "게임을 벗어날 때(홈·롬 바꾸기) 자동으로 상태를 저장하고, 같은 롬을 다시 열면"
                + " 그 자리에서 이어합니다. 수동 슬롯(1~3)과는 별개의 자리를 씁니다.",
                ONOFF, ONOFF_K, "enabled"),
            new Item("pocketcore_lang", "언어",
                "일본어·영어는 롬에 원래 들어 있어 설정만 바뀝니다. 한국어는 번역 패치를 롬 사본에 입힙니다"
                + " — 원본 롬은 건드리지 않습니다. 패치가 어느 쪽 표를 덮었는지는 게임마다 달라서 바탕을 고릅니다."
                + " 실행 전 선택창에서 게임별로 고른 「한글패치」가 있으면 그쪽이 우선하며, 여기 값을 바꾸면 게임별 선택은 지워집니다.",
                Games.LANGS, Games.LANGS_KO, "ko-ja"),
        });
        GROUPS.put("업데이트", new Item[]{
            new Item("pocketcore_level", "배포 레벨",
                "정식 = 검증을 거친 판만 받습니다. 시험 = 새 판이 나오는 대로 바로 받습니다(실험 기능 포함, 문제가 있으면"
                + " 다음 판으로 되돌립니다). 바꾼 뒤 「업데이트 확인」을 누르세요. 앱·코어 모두에 적용됩니다.",
                new String[]{ "stable", "test" }, new String[]{ "정식", "시험" }, "stable"),
        });
        GROUPS.put("화면", new Item[]{
            /* 화면 크기·자리 — 손가락 패드가 그림을 가리는 문제 때문에 필요하다.
               코어가 아니라 **앱이** 그리는 자리를 정하는 값이라 options.txt 에만 남고
               코어는 이 키를 모른다. 화면을 줄이면 코어에는 줄어든 크기가 그대로 전달되어
               (nativeResize) 코어가 알아서 비율을 맞춘다. */
            new Item("pocketcore_screen_size", "화면 크기",
                "화면에서 게임 그림이 차지할 비율입니다. 게임 안 상단바 「키」 편집에서"
                + " 게임 화면을 **직접 끌어 옮기고** [－][＋]로 크기를 바꿀 수도 있습니다.",
                new String[]{ "50","60","70","80","90","100" },
                new String[]{ "50%","60%","70%","80%","90%","100% (꽉)" }, "100"),
            new Item("pocketcore_screen_v", "세로 자리",
                "줄인 화면을 위·가운데·아래 중 어디에 붙일지.",
                new String[]{ "top","center","bottom" },
                new String[]{ "위","가운데","아래" }, "center"),
            new Item("pocketcore_screen_h", "가로 자리",
                "줄인 화면을 왼쪽·가운데·오른쪽 중 어디에 붙일지.",
                new String[]{ "left","center","right" },
                new String[]{ "왼쪽","가운데","오른쪽" }, "center"),
            new Item("pocketcore_orientation", "화면 방향",
                "자동은 기기 회전을 따릅니다. 가로는 게임기·거치 플레이용 — 기둥 아트도 양옆에 다 들어갑니다.",
                new String[]{ "auto", "portrait", "landscape" },
                new String[]{ "자동", "세로", "가로" }, "auto"),
            new Item("ngp_ss2sp_sides", "기둥 아트",
                "게임 양옆에 64px 기둥을 세웁니다. 폭이 160 → 288 이 됩니다.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_SIDES),
            new Item("ngp_svcsp_band", "기술명 띠",
                "기술 이름을 화면 **밖** 띠에 띄웁니다. 끄면 게임 그림 위에 겹칩니다."
                + " 세로가 32px 늘어납니다. SS2 는 해설 띠가 이미 그 자리를 씁니다.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_BAND),
            new Item("ngp_ss2sp_comm_draw", "해설창 자리",
                "SS2 해설 자막을 어디에 그릴지. 「위 띠」가 게임 그림을 안 가립니다.",
                new String[]{ "above", "inside_bottom", "disabled" },
                new String[]{ "위 띠", "화면 안 아래", "끔" }, "above").f(Games.F_COMM),
        });
        GROUPS.put("소리", new Item[]{
            new Item("pocketcore_launcher_snd", "런처 소리",
                "롬 고르는 화면의 부팅음과 테마곡. 게임에 들어가면 멈춥니다.",
                ONOFF, ONOFF_K, "enabled"),
            new Item("ngp_ss2sp_dub", "해설 음성",
                "system/ss2_voice_<언어>.pak 이 있어야 납니다. 없으면 자막만 나옵니다.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_COMM),
            new Item("ngp_ss2sp_comm_vol", "해설 크기",
                "게임 소리는 안 줄입니다. 해설만 이 값만큼 얹습니다.",
                new String[]{ "0","50","80","100","120","150" },
                new String[]{ "끔","50%","80%","100%","120%","150%" }, "100").f(Games.F_COMM),
            new Item("ngp_ss2sp_comm_lang", "해설 언어",
                "음성팩 이름과 짝입니다 — system/ss2_voice_<이 값>.pak 을 읽습니다."
                + " 재생 키가 문장 해시라 표와 팩의 판이 어긋나면 그냥 조용해집니다.",
                new String[]{ "ko" }, new String[]{ "한국어" }, "ko").f(Games.F_COMM),
        });
        GROUPS.put("해설", new Item[]{
            /* 유저 지시(2026-09-03): 캐릭터 챗은 기본 끔, 심판(쿠로코) 목소리는 남긴다 — 코어의 마스터
               스위치(ngp_ss2sp_comm)를 끄면 둘 다 죽으므로 따로 난 키 두 개를 쓴다. */
            new Item("ngp_ss2sp_chat", "캐릭터 챗",
                "SS2 전용. 해설 캐릭터가 경기 상황·관계 대사·메뉴 잡담을 합니다. 꺼도 심판(쿠로코)은 남습니다.",
                ONOFF, ONOFF_K, "disabled").f(Games.F_COMM).l(),
            new Item("ngp_ss2sp_ref", "심판 (쿠로코)",
                "SS2 전용. 대진 호명·라운드 개시·판정. 음성팩이 있으면 목소리도 납니다. 캐릭터 챗과 따로 켜고 끕니다.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_COMM).l(),
            new Item("ngp_ss2sp_comm", "해설 전체 스위치",
                "끄면 캐릭터 챗·심판·자막창이 모두 꺼집니다. 보통은 켜 두고 위 두 항목으로 고릅니다.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_COMM),
        });
        GROUPS.put("조작", new Item[]{
            new Item("pocketcore_touchpad", "터치 패드",
                "자동은 물리 게임패드가 연결되면 터치 버튼을 숨기고 메뉴 알약만 남깁니다. 버튼 배정은 설정 아래 「물리 패드 매핑」.",
                new String[]{ "auto", "on", "off" },
                new String[]{ "자동", "항상 표시", "숨김" }, "auto"),
            new Item("ngp_svcsp_engine", "원버튼 필살기",
                "SvC 전용. 기술키 하나로 커맨드를 대신 넣습니다. 방향에 따라 다른 기술이 나갑니다.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_SP_SVC).l(),
            new Item("ngp_kofsp_engine", "KOF 원버튼 필살기",
                "KOF R-2 전용. SP 버튼(패드의 R) 하나로 커맨드를 대신 넣습니다 —"
                + " 방향없음=장풍 · 앞=대공 · 앞아래=초필살기 · 공중에서도 나갑니다."
                + " 탭=약 / 꾹=강. 끄면 SP 버튼은 순정처럼 A+B 로 동작합니다.",
                new String[]{ "disabled", "enabled" },
                new String[]{ "끔", "켬" }, "disabled").f(Games.F_SP_KOF).l(),
            new Item("ngp_kofsp_toast", "KOF 기술 표기 표시",
                "KOF R-2 원버튼으로 기술이 나갈 때 커맨드 표기(↓↘→ + 펀치)를 띄웁니다."
                + " 같은 슬롯이라도 캐릭터마다 다른 기술이라 이름 대신 표기를 적습니다 —"
                + " 손으로 치는 법이 그대로 보입니다.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_SP_KOF).l(),
            new Item("ngp_svcsp_toast", "기술명 표시",
                "원버튼으로 기술이 나갈 때 이름을 띄웁니다.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_SP_SVC),
            new Item("ngp_svcsp_basics", "SVC 강약 버튼 구분",
                "켬 = 약P·약K·강P·강K 4버튼(약은 짧게 고정, 강은 즉발). 끔 = 순정 2버튼"
                + "(A·B 탭=약/꾹=강, 8프레임부터 강 — 게임 원판정) — 단 강P·강K 버튼은 끔에서도 즉발 강으로"
                + " 살아 있습니다. 게임을 다시 열면 적용됩니다.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_BASICS).l(),
            new Item("ngp_svcsp_holdsync", "SVC 강 발동 당김",
                "강약 구분 끔(2버튼)에서 A·B 를 꾹 눌러 내는 강이 언제 시작하는지. 게임이 판정을 내리고도 2프레임을"
                + " 더 끄는데, 그 몫을 돌려받는 것입니다. 쥐는 시간(8프레임)도 강 모션 길이(캐릭터마다 다름)도 그대로입니다."
                + " 보통 = 2프레임 당김, 약으로 남는 탭 6프레임 유지. 최대 = 4프레임 당김, 약 탭은 4프레임으로 줍니다."
                + " 강이 굼떠서 답답하면 이것 말고 「빠른 기본기(FastCD)」를 켜세요 — 그쪽이 캐릭터별 모션을 줄입니다.",
                new String[]{ "mid", "max", "off" },
                new String[]{ "보통 (약 창 유지)", "최대 (약 창 4f)", "순정" }, "mid").f(Games.F_BASICS).l(),
            new Item("ngp_svcsp_land", "SVC 착지 선입력",
                "점프 공격 뒤 착지 직전에 누른 기본기를 엔진이 기억했다가 착지하는 순간 대신"
                + " 눌러 줍니다 — 강P·강K 는 강으로, A·B 는 강약 구분이 끔일 때 쥔 채 착지하면 강·탭이면 약"
                + "(켬이면 A·B 는 약 고정). 끔(기본) = 순정 그대로,"
                + " 공중에서 누른 건 공중기로만 쓰이고 지상기는 착지 뒤 다시 눌러야 나갑니다."
                + " 게임을 다시 열면 적용됩니다.",
                ONOFF, ONOFF_K, "disabled").f(Games.F_SP_SVC).l(),
            new Item("pocketcore_svc_actshow", "판독 오버레이 (동작번호)",
                "SvC 전용. 화면 왼쪽 위에 「내 동작번호|상대반응」을 상시 표시합니다."
                + " 영상만 찍어도 무슨 기술이 나갔는지(약·강 구분 포함) 확정할 수 있는"
                + " 검증용 표시입니다. 바꾸면 게임을 다시 시작해야 적용됩니다.",
                ONOFF, ONOFF_K, "disabled").f(Games.F_ACTSHOW),
            /* faststrong(pocketcore_svc_fastrom)은 문턱을 낮추는 연구용 패치로 부작용
               (공중 강공격 불발)이 있어 메뉴에서 뺐다 — 빠른 기본기는 이제 FastCD 가
               부작용 없이 대신한다. 배관(EmuActivity·Patcher)은 남겨 두어 연구 시
               options.txt 에 pocketcore_svc_fastrom=enabled 로 손수 켤 수 있다. */
            new Item("ngp_ss2sp", "SS2 원버튼",
                "SS2 전용. 이쪽은 게임에 원래 간이입력(ABLE)이 있어 기본은 그것을 씁니다.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_SP_SS2),
        });
    }

    /* ── 조작 패치(mods) ──────────────────────────────────────────
       PocketCore/mods/mods.json(업데이트 확인이 받음; 첫 실행엔 동봉 스냅샷을 시드) 에 적힌 게임플레이 패치들.
       항목 하나 = 옵션 키 pocketcore_<id> 토글. 켜진 것만 Patcher 가 .patched 사본에 순서대로 얹는다.
       한패(patches.json)와 분리된 채널 — 「조작 패치는 다른 데」(유저). */
    public static final class Mod {
        public final String id, game, ko, ver, help, def;
        /** 배타 묶음 — 같은 자리를 다른 값으로 덮는 패치들. 묶이면 한 줄짜리 다이얼이 되고 옵션 키는
         *  pocketcore_<group> 이며 값이 곧 고른 패치의 id 다. 비어 있으면 예전대로 개별 토글. */
        public final String group, groupKo, pick;
        Mod(String id, String game, String ko, String ver, String help, String def,
            String group, String groupKo, String pick) {
            this.id = id; this.game = game; this.ko = ko; this.ver = ver; this.help = help; this.def = def;
            this.group = group; this.groupKo = groupKo; this.pick = pick;
        }
        public boolean grouped() { return group != null && !group.isEmpty(); }
    }
    public static File modsDir()  { return new File(MainActivity.root(), "mods"); }
    public static List<Mod> mods() {
        List<Mod> out = new ArrayList<>();
        File f = new File(modsDir(), "mods.json");
        if (!f.exists()) return out;
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            in.close();
            org.json.JSONArray arr = new org.json.JSONObject(new String(b, 0, n, "UTF-8")).getJSONArray("mods");
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject m = arr.getJSONObject(i);
                String id = m.optString("id", ""), game = m.optString("game", "");
                if (id.isEmpty() || game.isEmpty() || !id.matches("[A-Za-z0-9_]+")) continue;
                out.add(new Mod(id, game, m.optString("ko", id), m.optString("ver", ""),
                                m.optString("help", ""), m.optString("default", "disabled"),
                                m.optString("group", ""), m.optString("group_ko", ""), m.optString("pick", "")));
            }
        } catch (Exception ignored) { }
        return out;
    }
    /** mods → 설정 항목. 라벨에 판을 붙여 무엇이 깔렸는지 보이게 한다. */
    public static List<Item> modItems() {
        List<Item> out = new ArrayList<>();
        LinkedHashMap<String, List<Mod>> groups = new LinkedHashMap<>();
        for (Mod m : mods()) {
            if (m.grouped()) {                         /* 배타 묶음 — 아래에서 한 줄로 합친다 */
                List<Mod> g = groups.get(m.group);
                if (g == null) { g = new ArrayList<>(); groups.put(m.group, g); }
                g.add(m);
                continue;
            }
            out.add(new Item("pocketcore_" + m.id, m.ko + (m.ver.isEmpty() ? "" : "  " + m.ver), m.help,
                             ONOFF, ONOFF_K, "enabled".equals(m.def) ? "enabled" : "disabled").g(m.game));
        }
        for (Map.Entry<String, List<Mod>> e : groups.entrySet()) {
            List<Mod> g = e.getValue();
            String[] vals = new String[g.size() + 1], names = new String[g.size() + 1];
            vals[0] = "disabled"; names[0] = "끔";
            String def = "disabled";
            for (int i = 0; i < g.size(); i++) {
                Mod m = g.get(i);
                vals[i + 1] = m.id;
                names[i + 1] = m.pick.isEmpty() ? m.ko : m.pick;
                if ("enabled".equals(m.def)) def = m.id;      /* 색인이 기본으로 고른 값 */
            }
            Mod f = g.get(0);
            out.add(new Item("pocketcore_" + e.getKey(),
                             f.groupKo.isEmpty() ? f.ko : f.groupKo, f.help,
                             vals, names, def).g(f.game));
        }
        return out;
    }

    /* ── 파일 ────────────────────────────────────────────────────── */

    /** options.txt 를 읽어 key=value 로. 주석과 빈 줄은 버린다. */
    public static Map<String, String> load() {
        Map<String, String> m = new LinkedHashMap<>();
        File f = MainActivity.optsFile();
        if (!f.exists()) return m;
        try {
            Scanner sc = new Scanner(f, "UTF-8");
            while (sc.hasNextLine()) {
                String ln = sc.nextLine().trim();
                if (ln.isEmpty() || ln.startsWith("#")) continue;
                int i = ln.indexOf('=');
                if (i > 0) m.put(ln.substring(0, i).trim(), ln.substring(i + 1).trim());
            }
            sc.close();
        } catch (Exception ignored) { }
        return m;
    }

    /**
     * 옛 옵션 값을 «같은 뜻의» 새 값으로 옮긴다.
     *
     * 패치 이름이 바뀌면 옵션 키도 바뀌어 **유저 설정이 조용히 꺼진다** — v1.1→v1.2 때 한 번 그랬다.
     * 「빠른 기본기(FastCD)」가 「강 기본기 당기기」 단계 다이얼로 합쳐지면서 또 그럴 자리다.
     * 다행히 바이트가 같은 판끼리 대응이 실측으로 확인돼 있어 **어림이 아니라 정확히** 옮길 수 있다:
     *   SvC FastCD v1.5 ≡ −8 단계 · KOF R-2 FastCD v1.2 ≡ −6 단계 (이식소 실측, md5 동일)
     *
     * 새 키가 이미 있으면 건드리지 않는다 — 유저가 직접 고른 값이 이깁니다.
     */
    public static void migrate() {
        Map<String, String> m = load();
        if (!m.containsKey("pocketcore_svc_faststrong")
                && "enabled".equals(m.get("pocketcore_svc_fastcd")))
            put("pocketcore_svc_faststrong", "svc_faststrong_8");

        String k = m.get("pocketcore_kofr2_speed");
        if (!m.containsKey("pocketcore_kofr2_faststrong") && k != null && !"disabled".equals(k)) {
            String v = null;
            if ("kofr2_fastcd".equals(k)) v = "kofr2_faststrong_6";
            else if (k.startsWith("kofr2_fastpunch_"))
                v = "kofr2_faststrong_" + k.substring("kofr2_fastpunch_".length());
            if (v != null) put("pocketcore_kofr2_faststrong", v);
        }
    }

    /** 값 하나를 바꿔 쓴다. **주석은 살린다** — 파일을 손으로 고치는 사람이 아직 있다. */
    public static void put(String key, String val) {
        File f = MainActivity.optsFile();
        List<String> out = new ArrayList<>();
        boolean hit = false;
        try {
            if (f.exists()) {
                Scanner sc = new Scanner(f, "UTF-8");
                while (sc.hasNextLine()) {
                    String ln = sc.nextLine();
                    String t = ln.trim();
                    if (!t.startsWith("#") && t.startsWith(key + "=")) {
                        out.add(key + "=" + val); hit = true;
                    } else out.add(ln);
                }
                sc.close();
            }
            if (!hit) out.add(key + "=" + val);
            StringBuilder sb = new StringBuilder();
            for (String s : out) sb.append(s).append('\n');
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(sb.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) { }
    }

    /** 접두사로 시작하는 키를 모두 지운다(주석은 살린다). 전역 「언어」를 바꾸면 게임별 선택(pocketcore_lang_<id>)을 지우는 데 쓴다. */
    public static void removePrefix(String prefix) {
        File f = MainActivity.optsFile();
        if (!f.exists()) return;
        List<String> out = new ArrayList<>();
        boolean hit = false;
        try {
            Scanner sc = new Scanner(f, "UTF-8");
            while (sc.hasNextLine()) {
                String ln = sc.nextLine(); String t = ln.trim();
                if (!t.startsWith("#") && t.startsWith(prefix)) { hit = true; continue; }
                out.add(ln);
            }
            sc.close();
            if (!hit) return;
            StringBuilder sb = new StringBuilder();
            for (String s : out) sb.append(s).append('\n');
            try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(sb.toString().getBytes("UTF-8")); }
        } catch (Exception ignored) { }
    }

    public static String get(Map<String, String> m, Item it) {
        String v = m.get(it.key);
        return (v != null) ? v : it.def;
    }

    private Settings() { }
}
