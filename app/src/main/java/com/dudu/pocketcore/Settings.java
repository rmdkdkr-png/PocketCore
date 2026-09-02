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
        Item(String key, String label, String help, String[] vals, String[] names, String def) {
            this.key = key; this.label = label; this.help = help;
            this.vals = vals; this.names = names; this.def = def;
        }
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
                + " — 원본 롬은 건드리지 않습니다. 패치가 어느 쪽 표를 덮었는지는 게임마다 달라서 바탕을 고릅니다.",
                Games.LANGS, Games.LANGS_KO, "ko-ja"),
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
            new Item("ngp_ss2sp_sides", "기둥 아트",
                "게임 양옆에 64px 기둥을 세웁니다. 폭이 160 → 288 이 됩니다.",
                ONOFF, ONOFF_K, "enabled"),
            new Item("ngp_svcsp_band", "기술명 띠",
                "기술 이름을 화면 **밖** 띠에 띄웁니다. 끄면 게임 그림 위에 겹칩니다."
                + " 세로가 32px 늘어납니다. SS2 는 해설 띠가 이미 그 자리를 씁니다.",
                ONOFF, ONOFF_K, "enabled"),
            new Item("ngp_ss2sp_comm_draw", "해설창 자리",
                "SS2 해설 자막을 어디에 그릴지. 「위 띠」가 게임 그림을 안 가립니다.",
                new String[]{ "above", "inside_bottom", "disabled" },
                new String[]{ "위 띠", "화면 안 아래", "끔" }, "above"),
        });
        GROUPS.put("소리", new Item[]{
            new Item("pocketcore_launcher_snd", "런처 소리",
                "롬 고르는 화면의 부팅음과 테마곡. 게임에 들어가면 멈춥니다.",
                ONOFF, ONOFF_K, "enabled"),
            new Item("ngp_ss2sp_dub", "해설 음성",
                "system/ss2_voice_<언어>.pak 이 있어야 납니다. 없으면 자막만 나옵니다.",
                ONOFF, ONOFF_K, "enabled"),
            new Item("ngp_ss2sp_comm_vol", "해설 크기",
                "게임 소리는 안 줄입니다. 해설만 이 값만큼 얹습니다.",
                new String[]{ "0","50","80","100","120","150" },
                new String[]{ "끔","50%","80%","100%","120%","150%" }, "100"),
            new Item("ngp_ss2sp_comm_lang", "해설 언어",
                "음성팩 이름과 짝입니다 — system/ss2_voice_<이 값>.pak 을 읽습니다."
                + " 재생 키가 문장 해시라 표와 팩의 판이 어긋나면 그냥 조용해집니다.",
                new String[]{ "ko" }, new String[]{ "한국어" }, "ko"),
        });
        GROUPS.put("해설", new Item[]{
            new Item("ngp_ss2sp_comm", "캐릭터 해설",
                "SS2 전용. 경기 중 캐릭터가 상황에 맞춰 말합니다.",
                ONOFF, ONOFF_K, "enabled"),
        });
        GROUPS.put("조작", new Item[]{
            new Item("ngp_svcsp_engine", "원버튼 필살기",
                "SvC 전용. 기술키 하나로 커맨드를 대신 넣습니다. 방향에 따라 다른 기술이 나갑니다.",
                ONOFF, ONOFF_K, "enabled"),
            new Item("ngp_kofsp_engine", "KOF 원버튼 필살기",
                "KOF R-2 전용. SP 버튼(패드의 R) 하나로 커맨드를 대신 넣습니다 —"
                + " 방향없음=장풍 · 앞=대공 · 앞아래=초필살기 · 공중에서도 나갑니다."
                + " 탭=약 / 꾹=강. 끄면 SP 버튼은 순정처럼 A+B 로 동작합니다.",
                new String[]{ "disabled", "enabled" },
                new String[]{ "끔", "켬" }, "disabled"),
            new Item("ngp_svcsp_toast", "기술명 표시",
                "원버튼으로 기술이 나갈 때 이름을 띄웁니다.",
                ONOFF, ONOFF_K, "enabled"),
            new Item("ngp_svcsp_basics", "SVC 강약 버튼 구분",
                "켬 = 약P·약K·강P·강K 4버튼(약은 짧게 고정, 강은 즉발). 끔 = 순정 2버튼"
                + "(A·B 탭=약/꾹=강, 게임 원판정) — 단 강P·강K 버튼은 끔에서도 즉발 강으로"
                + " 살아 있습니다. 게임을 다시 열면 적용됩니다.",
                ONOFF, ONOFF_K, "enabled"),
            new Item("pocketcore_svc_actshow", "판독 오버레이 (동작번호)",
                "SvC 전용. 화면 왼쪽 위에 「내 동작번호|상대반응」을 상시 표시합니다."
                + " 영상만 찍어도 무슨 기술이 나갔는지(약·강 구분 포함) 확정할 수 있는"
                + " 검증용 표시입니다. 바꾸면 게임을 다시 시작해야 적용됩니다.",
                ONOFF, ONOFF_K, "disabled"),
            new Item("pocketcore_svc_fastrom", "강공격 롬 패치 (부작용 있음)",
                "SvC 전용. 강 문턱을 낮추는 롬 패치 — ⚠️ 공중 강공격이 안 나가게 됩니다"
                + " (값과 무관, 실측). 지상 강은 코어가 이미 즉발로 처리하므로 켤 이유가"
                + " 없습니다. 실기 연구용.",
                ONOFF, ONOFF_K, "disabled"),
            new Item("ngp_ss2sp", "SS2 원버튼",
                "SS2 전용. 이쪽은 게임에 원래 간이입력(ABLE)이 있어 기본은 그것을 씁니다.",
                ONOFF, ONOFF_K, "enabled"),
        });
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

    public static String get(Map<String, String> m, Item it) {
        String v = m.get(it.key);
        return (v != null) ? v : it.def;
    }

    private Settings() { }
}
