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
    /** 프레임 패치 켬/끔 열쇠(on/off — 151 전엔 120·60·custom 이고 이름 «보간») — 아래 putUser·migrateMotion 참고 */
    public static final String MOTION = "pocketcore_motion";
    /** 176 음색 다듬기 켬/끔 열쇠(enabled/disabled — 기본 켬) */
    public static final String SND_FX = "pocketcore_snd_fx";

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
        /** 세기(%) 항목 — 슬라이더로 그린다 */
        public boolean pct;
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

    /** 세기(%) 항목 — 0..100 을 5 단위로. 설정 화면이 슬라이더로 그린다(값이 많아 다이얼로는 좁다). */
    static Item pct(String key, String label, String help, int def) {
        String[] v = new String[21], n = new String[21];
        for (int i = 0; i <= 20; i++) { v[i] = String.valueOf(i * 5); n[i] = (i * 5) + "%"; }
        Item it = new Item(key, label, help, v, n, String.valueOf(def));
        it.pct = true;
        return it;
    }
    /** 세기 항목 값을 0..100 정수로 (없거나 깨졌으면 기본값). */
    public static int pctOf(Map<String, String> m, String key, int def) {
        try { return Math.max(0, Math.min(100, Integer.parseInt(m.get(key).trim()))); }
        catch (Exception e) { return def; }
    }

    /** 업스케일러 값 → native 번호(native.c · display_shaders.h 의 순서와 같다). */
    public static final String[] UPSCALERS = { "none", "sharp", "scale2x", "xbr", "omni" };
    public static int upscalerIndex(String v) {
        for (int i = 0; i < UPSCALERS.length; i++) if (UPSCALERS[i].equals(v)) return i;
        return 0;
    }

    /** 소절 이름 → 항목들. 순서가 곧 화면 순서다. 어느 쪽(페이지)에 들어가는지는 아래 PAGES 가 정한다.
     *  실행 전 선택창(LaunchSheet)은 이 표 전체에서 .l() 표시된 것만 뽑아 쓴다. */
    public static final LinkedHashMap<String, Item[]> GROUPS = new LinkedHashMap<>();
    static {
        /* 2026-10-10 정리(유저 「뺄 거 빼고 구성 트리 메뉴 좀 다듬어 둬」): 키·값·기본값은 그대로 두고 이름과 설명만 짧게,
           프레임 생성은 «켜고 끄기»와 «사무쇼2 세부»로 나눔, 검증용(판독 오버레이)은 메뉴에서 뺌(options.txt 로는 그대로 켜짐),
           실행 전 선택창(.l())에는 게임마다 고를 만한 것만 — 한글패치·조작 패치·원버튼·배경음악·보간 켜고 끄기. */
        /* ── 화면 ── */
        /* 테마(내부 121) — 유저 2026-10-13 「테마 다 되게」. 런처·설정·실행 전 창·게임 중 버튼이 같이 바뀐다(Theme).
           아이콘은 따로 고른다(AppIcon) — 별칭을 바꾸면 홈 화면의 옛 아이콘이 빠질 수 있어, 테마를 넘겨 볼 때마다 바뀌면 안 된다. */
        GROUPS.put("테마", new Item[]{
            new Item(Theme.KEY, "테마",
                "런처·설정·게임 중 버튼의 모습. 기본 = 지금까지 · 먹·금 = 옻칠·금박·붉은 낙관 · 포켓 = 휴대 게임기(은색 몸통·도트 글씨) · 모던 = 요즘 앱처럼."
                + " 런처 왼쪽 위 로고를 눌러도 차례로 바뀝니다.",
                Theme.IDS, Theme.NAMES, Theme.CLASSIC),
            new Item(AppIcon.KEY, "앱 아이콘",
                "홈 화면 아이콘 그림. 바꾸면 홈 화면에 두었던 아이콘이 빠질 수 있습니다 — 앱 서랍(전체 앱)에 새 아이콘이 있으니 끌어다 놓으면 됩니다.",
                Theme.IDS, Theme.NAMES, Theme.CLASSIC),
        });
        GROUPS.put("크기·자리", new Item[]{
            /* 화면 크기·자리 — 손가락 패드가 그림을 가리는 문제 때문에 필요하다.
               코어가 아니라 **앱이** 그리는 자리를 정하는 값이라 options.txt 에만 남고 코어는 이 키를 모른다. */
            new Item("pocketcore_screen_size", "화면 크기",
                "게임 그림이 차지할 비율. 게임 안 「메뉴 › 배치」에서 화면을 끌어 옮기고 크기를 바꿀 수도 있습니다.",
                new String[]{ "50","60","70","80","90","100" },
                new String[]{ "50%","60%","70%","80%","90%","100%" }, "100"),
            new Item("pocketcore_screen_v", "세로 자리",
                "줄인 화면을 어디에 붙일지.",
                new String[]{ "top","center","bottom" },
                new String[]{ "위","가운데","아래" }, "center"),
            new Item("pocketcore_screen_h", "가로 자리",
                "줄인 화면을 어디에 붙일지.",
                new String[]{ "left","center","right" },
                new String[]{ "왼쪽","가운데","오른쪽" }, "center"),
            new Item("pocketcore_orientation", "화면 방향",
                "자동은 기기 회전을 따릅니다.",
                new String[]{ "auto", "portrait", "landscape" },
                new String[]{ "자동", "세로", "가로" }, "auto"),
            new Item("pocketcore_integer", "정수배 맞춤",
                "도트 한 칸을 화면 몇 칸으로 딱 맞춰 키웁니다. 끄면 더 꽉 차지만 칸 크기가 들쭉날쭉 — 그때는 업스케일러 「샤프」.",
                ONOFF, ONOFF_K, "enabled"),
        });
        GROUPS.put("업스케일러", new Item[]{
            /* 업스케일러 = 작은 도트 그림을 큰 화면으로 «키우는 방식». 필터(아래)와는 따로다 — 업스케일러는 하나만 고르고,
               필터는 그 위에 여러 개를 겹친다. 셰이더는 app/src/main/cpp/shaders (생성기 tools/gen_shaders.py). */
            new Item("pocketcore_upscaler", "업스케일러",
                "도트를 키우는 방식. 끔 = 네모 칸 그대로 · 샤프 = 칸을 고르게 · Scale2x = 계단 한 단계 깎기 · xBR = 곡선을 매끈하게 · Omni = 매끈하되 가는 선을 살림."
                + " 사무쇼2 프레임 패치의 ¼픽셀 움직임은 업스케일러·필터를 켜도 그대로입니다.",
                UPSCALERS, new String[]{ "끔", "샤프", "Scale2x", "xBR", "Omni" }, "none"),
            /* 114: 「업스케일러 세기」(pocketcore_upscaler_mix) 뺐다 — 유저 2026-10-11 「업스케일러는 블렌더 레벨 필요 없어」.
               늘 100%(EmuActivity.applyDisplay). options.txt 에 남은 옛 값은 안 읽는다. */
        });
        GROUPS.put("필터", new Item[]{
            /* 필터 = 키운 그림 «위에 덧입히는» 효과. 각자 세기(섞는 비율)가 있고 여러 개를 함께 켤 수 있다. 0% = 끔. */
            pct("pocketcore_flt_grid", "LCD 격자", "칸 사이에 가는 어두운 줄 — 휴대기 액정처럼.", 0),
            pct("pocketcore_flt_scan", "스캔라인", "가로줄마다 어둡게 — 브라운관처럼.", 0),
            pct("pocketcore_flt_ghost", "잔상", "움직이는 그림이 잠깐 남음 — 실기 액정의 느린 반응.", 0),
            pct("pocketcore_flt_color", "LCD 색감", "채도·대비를 조금 눌러 실기 화면 색처럼.", 0),
            pct("pocketcore_flt_soft", "번짐", "그림 전체를 살짝 흐리게.", 0),
        });
        GROUPS.put("게임 화면", new Item[]{
            new Item("ngp_svcsp_band", "기술명 띠",
                "기술 이름을 게임 그림 밖 띠에 띄웁니다(세로 32px 늘어남). 끄면 그림 위에 겹칩니다.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_BAND),
            /* 「기둥 아트」(ngp_ss2sp_sides) 는 2026-09-07 유저 지시로 폐기했다.
               해설·더빙(ngp_ss2sp_comm 계열)은 3.90 에 이미 뺐고, 남은 배관도 같이 걷어냈다. */
            /* 125: 코어 패치 99l — 유저 2026-10-14 「배경은 … 번쩍도 함」·「번쩍 필터 기능 타 게임에 있거든 그거 알아보고 와」.
               사무쇼2 는 큰 타격 때 창을 접어 화면 전체를 1 프레임 하얗게(또는 까맣게) 칠한다 — 그 프레임만 앞 화면 위에 그 색을 조금 얹는다.
               처음 한 번 깔아 둔다(migrate) — 코어 기본은 끔. 127: 기본 «거의 없게»(유저 「번쩍 화면 전체 효과 말하는 거임」). */
            new Item("ngp_flash_reduce", "번쩍임 줄이기",
                "큰 타격·판 시작 때 화면 전체가 한 프레임 하얗게(또는 까맣게) 번쩍이는 것을 줄입니다 — 바로 앞 화면 위에 그 색을 조금만 얹어 보여 줌(캐릭터·배경 그대로)."
                + " 거의 없게 = 번쩍이 거의 안 보이고, 화면 밝기가 한 번에 크게 바뀌면 몇 칸에 나눠 부드럽게"
                + " · 약하게 = 번쩍 ¼ · 끔 = 원래대로. 프레임 패치가 켜져 있을 때.",
                new String[]{ "strong", "half", "off" },
                new String[]{ "거의 없게", "약하게", "끔" }, "strong").g("ss2"),
        });
        /* ── 움직임·반응 ── */
        /* 프레임 패치 — 151: 켬/끔 한 줄, 사무쇼2 만(유저 2026-10-16 「ss2만 되는걸로 해」·「프레임패치 온오프만 만들어」·
           「세팅은 자동으로 해라 … 최선이 있고 나머진 다 별로니깐」·「이펙트효과는 옮기기로 하면 되겠다」).
           켜면 아래 MOTION_ON(가장 좋은 조합)을 한꺼번에 쓰고, 끄면 MOTION_OFF. 세부(방식·배수·이펙트·날아가는 몸·서기·동작 사이 그림)는
           화면에서 뺐다 — 옛 120Hz·60Hz·커스텀 묶음, 「보간 고급」, 앱 보간(pocketcore_framegen)도. 열쇠는 그대로 pocketcore_motion(값 on/off) */
        GROUPS.put("프레임 패치", new Item[]{
            new Item(MOTION, "프레임 패치",
                "사무쇼2 전용. 코어가 다음 2프레임을 미리 돌려 보고 그 사이 그림을 채웁니다 — 캐릭터·배경 자리는 ¼픽셀씩 옮기고,"
                + " 서기 자세와 공격·맞기 같은 동작은 미리 만들어 둔 사이 그림으로, 장풍·칼 궤적 같은 이펙트는 자리를 옮겨서. 도트 그대로, 지연 없음."
                + " 켜면 늘 가장 좋은 조합으로 맞추고 화면이 120Hz 면 120, 아니면 60 에 맞춥니다.",
                new String[]{ "on", "off" }, new String[]{ "켬", "끔" }, "on").g("ss2").l(),
        });
        GROUPS.put("입력 지연", new Item[]{
            /* 런어헤드 — 코어(50_runahead.patch)가 게임을 몰래 몇 프레임 앞서 돌려 그 결과를 보여 준다. 소리는 진짜 프레임 것.
               대전·메뉴 구분 없이 늘(유저 2026-10-10 「대전중에만 켤 필요가 있나 그냥 하면 되지」). */
            new Item("ngp_runahead", "런어헤드",
                "사무쇼2 전용. 게임을 몰래 앞서 돌려 버튼 반응을 앞당깁니다 — 2프레임 = 약 33ms · 1프레임 = 약 17ms.",
                new String[]{ "2", "1", "0" },
                new String[]{ "2프레임", "1프레임", "끔" }, "2").g("ss2"),
        });
        /* ── 조작 ── */
        GROUPS.put("터치 패드", new Item[]{
            new Item("pocketcore_touchpad", "터치 버튼",
                "자동 = 물리 게임패드를 쓰면 숨기고 메뉴 알약만 남김 — 화면을 만지면 다시 나와 그대로 눌림, 다음 패드 입력에 다시 숨음 · 항상 표시 · 숨김 = 늘 숨김(메뉴 알약만).",
                new String[]{ "auto", "on", "off" },
                new String[]{ "자동", "항상 표시", "숨김" }, "auto"),
            new Item("pocketcore_padskin", "버튼 모양",
                "아트 = 테마 모양(기본은 금속 테·유광 버튼, 먹·금은 옻칠, 포켓은 스틱·고무 버튼, 모던은 선) · 단순 = 반투명 도형. PocketCore/design/skin/ 에 a.png·b.png·dpad.png 등을 넣으면 그 그림으로(눌림은 a_on.png).",
                new String[]{ "art", "flat" },
                new String[]{ "아트", "단순" }, "art"),
            /* 122: 유저 2026-10-13 「버튼 투명도 조절되냐」 — 값은 진하기(100 = 그대로), 고르는 글은 «비침 %» */
            new Item("pocketcore_pad_alpha", "버튼 투명도",
                "게임 중 터치 버튼(십자·A·B·SP·OPTION·배속)이 얼마나 비쳐 보일지. 높일수록 게임 그림이 버튼 너머로 보입니다. 메뉴 알약·메뉴 칸은 그대로.",
                new String[]{ "100", "80", "60", "40", "25" },
                new String[]{ "안 비침", "20%", "40%", "60%", "75%" }, "100"),
        });
        GROUPS.put("원버튼 필살기", new Item[]{
            new Item("ngp_svcsp_engine", "원버튼 필살기",
                "기술키 하나로 커맨드를 대신 넣습니다. 방향에 따라 다른 기술.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_SP_SVC).l(),
            new Item("ngp_svcsp_toast", "기술명 표시",
                "원버튼으로 기술이 나갈 때 이름을 띄웁니다.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_SP_SVC),
            new Item("ngp_kofsp_engine", "원버튼 필살기",
                "SP(R) 하나로 커맨드 — 방향없음=장풍 · 앞=대공 · 앞아래=초필살기, 탭=약 / 꾹=강. 끄면 R 은 A+B. A+B 는 언제나 L 로도.",
                new String[]{ "disabled", "enabled" },
                new String[]{ "끔", "켬" }, "disabled").f(Games.F_SP_KOF).l(),
            new Item("ngp_kofsp_toast", "기술 표기 표시",
                "원버튼으로 기술이 나갈 때 커맨드 표기(↓↘→ + 펀치)를 띄웁니다.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_SP_KOF),
            /* ngp_svcsp_basics(강약 4버튼 구분)·ngp_svcsp_land(착지 선입력) 는
               2026-09-06 유저 지시로 코어에서 «통째로» 빠졌다(이식소 ec4fc34).
               기본기는 이제 게임 원판정 — 탭=약 / 꾹=강. 스위치가 없으니 항목도 없다. */
            /* faststrong(pocketcore_svc_fastrom)은 문턱을 낮추는 연구용 패치로 부작용
               (공중 강공격 불발)이 있어 메뉴에서 뺐다 — 빠른 기본기는 이제 FastCD 가
               부작용 없이 대신한다. 배관(EmuActivity·Patcher)은 남겨 두어 연구 시
               options.txt 에 pocketcore_svc_fastrom=enabled 로 손수 켤 수 있다. */
            /* 월화 SP — 이식소가 코어에 넣은 ngp_lbsp_engine 하나만 건다.
               토스트(ngp_lbsp_toast)는 코어에 아직 없다 — 생기면 그때 만든다.
               ★ 설명은 «지금 실제로 되는 것»만 적는다. 슬롯 일곱이 서면 고친다. */
            new Item("ngp_lbsp_engine", "원버튼 필살기",
                "방향+R 로 필살기(방향없음=질풍 · 앞=대공 …). 끄면 R 은 A+B. ★ 아직 «카에데»만 됩니다.",
                ONOFF, ONOFF_K, "disabled").f(Games.F_SP_LB).l(),
            new Item("ngp_ss2sp", "원버튼",
                "기술키(X·R) 하나로 커맨드 — 방향에 따라 다른 기술. 사무쇼2 는 커맨드를 게임의 입력 기록에 바로 써 넣어 누르자마자 나갑니다(최속)."
                + " 끄면 그 버튼은 A+B. A+B 는 언제나 Y·L 로도.",
                ONOFF, ONOFF_K, "enabled").f(Games.F_SP_SS2).l(),
        });
        /* ── 게임 ── */
        GROUPS.put("게임 공통", new Item[]{
            new Item("pocketcore_lang", "언어",
                "한국어 = 번역 패치를 롬 사본에 입힘(원본은 그대로). 실행 전 선택창에서 게임별로 고른 「한글패치」가 우선하고, 여기를 바꾸면 게임별 선택은 지워집니다.",
                Games.LANGS, Games.LANGS_KO, "ko"),
            new Item("pocketcore_autosave", "오토세이브",
                "게임을 벗어날 때 상태를 저장하고 다시 열면 그 자리에서 이어합니다(수동 슬롯 1~3 과 따로).",
                ONOFF, ONOFF_K, "enabled"),
        });
        /* 치트(사무쇼2 · Cheats/cheat.c) — 실행 창·게임 안 「설정」의 «치트 ›» 고리가 여는 창에만 보인다(PAGES 에 안 넣음:
           설정 화면에서 바꾸면 올카드 «세이브에 남기기»의 확인 창·세이브 보관을 건너뛴다). 키·값·이름은 Cheats 한 곳에 */
        GROUPS.put(Cheats.GROUP, Cheats.items());
        GROUPS.put("검증용", new Item[]{          /* 메뉴에는 안 보인다(PAGES 에 없음) — options.txt 로만 */
            new Item("pocketcore_svc_actshow", "판독 오버레이 (동작번호)",
                "화면 왼쪽 위에 「내 동작번호|상대반응」을 상시 표시(검증용). 게임을 다시 시작해야 적용.",
                ONOFF, ONOFF_K, "disabled").f(Games.F_ACTSHOW),
        });
        /* ── 소리 ── */
        GROUPS.put("소리", new Item[]{
            /* 176 음색 다듬기(native sndfx.c) — 곡·효과음은 그대로, 게임 소리 위에 저음 보강·고음 눅이기·좌우 넓힘·방 울림.
               유저 10-20 「소리가 역시 너무 심심한데」 → 들어 보기 넷(원래·다듬기·악기 화풍·악기 밴드) 중 「2 가 나은 방식이네」 */
            new Item(SND_FX, "음색 다듬기",
                "켬 = 게임 소리에 저음 보강·고음 눅이기·좌우 넓힘·방 울림을 얹어요(곡·효과음은 그대로, 지연 없음). 모든 게임, 바로 반영.",
                ONOFF, ONOFF_K, "enabled").l(),
            new Item("pocketcore_launcher_snd", "런처 소리",
                "롬 고르는 화면의 부팅음과 테마곡. 게임에 들어가면 멈춥니다.",
                ONOFF, ONOFF_K, "enabled"),
            /* 사무쇼2 배경음악을 사무쇼1 것으로 — Ss1Music. 두 게임은 소리 엔진이 같아 SS1 악보를 SS2 소리칩이 그대로 연주한다.
               SS1 롬은 폰의 롬 폴더에서 그때그때 읽는다(배포물에 SS1 데이터 없음). 유저 2026-10-10 「음악이 SS1 이 훨씬 나」 */
            new Item("pocketcore_ss2_music", "배경음악",
                "SS1 음악 = 사무라이 쇼다운!(1편) 롬이 롬 폴더에 있으면 인트로·타이틀·라운드 시작·엔딩·SS2 에만 있는 상대 곡을 SS1 것으로(크기는 SS2 곡에 맞춤)."
                + " 같은 곡만 = 물려받은 10곡만 SS1 판 · SS2 원래 · 끔 = 효과음만. 게임을 다시 열 때 반영.",
                new String[]{ "ss1", "same", "off", "mute" },
                new String[]{ "SS1 음악", "같은 곡만", "SS2 원래", "끔" }, "ss1").g("ss2").l(),
            /* 컬러 고르기(look.c) — 게임에 없던 컬러 고르기를 원래 고르기 화면에 얹는다(유저 «컬러 선택 어느 모드든 되게» · «컬러 추가 확충»).
               게임이 고르기 화면에서 안 쓰던 버튼(OPTION)과 A+B(베기 색 — 165)를 써서 기본 켬. 사쇼2 확인한 롬 판에서만 돈다(조작·수동 패치 사본은 꺼짐) */
            new Item(Look.K_ON, "컬러 고르기",
                "켬 = 캐릭터 고르기에서 OPTION 몸 컬러(15가지 + 내 색 2) · A+B 베기 색(7가지). 1인·무한·연습·리그 모두."
                + " OPTION 길게 = 컬러 만들기. 컬러는 캐릭터마다, 베기 색은 하나로 기억해요.",
                ONOFF, ONOFF_K, "enabled").g("ss2").l(),
            /* 리그·연습 — 켜면 타이틀 셋째 줄(원래 2인 대전)이 «◀ 연습 대전 ▶», ←→ 로 «리그 대전». 둘 다 된다(유저 2026-10-15
               「리그 연습 모드는 옵션만 넣고 되는 걸로 해 줘, 둘 중 택일 말고 둘 다 되게」). 리그는 따로 세이브·순정 판이라 고르면 판을 갈아 끼운다 */
            new Item("pocketcore_ss2_hub", "리그·연습",
                "켬 = 타이틀 셋째 줄이 «◀ 연습 대전 ▶» — ←→ 로 «리그 대전»(가상 상대 12명, 시험)도. 리그 대전은 따로 세이브·순정 판"
                + "(SS2 음악·조작 패치 없이)으로 갈아타서 돕니다. 끔 = 원래 «2인 대전».",
                ONOFF, ONOFF_K, "enabled").g("ss2").l(),
        });
        /* ── 업데이트 ── */
        GROUPS.put("업데이트", new Item[]{
            new Item("pocketcore_level", "배포 레벨",
                "정식 = 검증된 판만 · 시험 = 새 판이 나오는 대로(실험 기능 포함). 바꾼 뒤 아래 「업데이트 확인」.",
                new String[]{ "stable", "test" }, new String[]{ "정식", "시험" }, "stable"),
        });
    }

    /** 설정의 큰 갈래 — 첫 화면에 한 줄씩, 누르면 그 갈래 화면. (유저 2026-10-10 「각론 메뉴 말고 전체 메뉴를 체계화해서」)
     *  sections = 위 GROUPS 의 소절 이름들. 행동 줄(패드 매핑·롬 가져오기)과 조작 패치는 SettingsActivity 가 id 로 붙인다. */
    public static final class Page {
        public final String id, title, sub;
        public final String[] sections;
        Page(String id, String title, String sub, String... sections) {
            this.id = id; this.title = title; this.sub = sub; this.sections = sections;
        }
    }
    public static final Page[] PAGES = {
        new Page("screen",  "화면",        "테마 · 크기·자리 · 업스케일러 · 필터",         "테마", "크기·자리", "업스케일러", "필터", "게임 화면"),
        new Page("motion",  "움직임·반응", "프레임 패치 · 런어헤드",                     "프레임 패치", "입력 지연"),
        new Page("control", "조작",        "터치 버튼 · 원버튼 · 조작 패치 · 물리 패드",  "터치 패드", "원버튼 필살기"),
        new Page("game",    "게임",        "언어 · 오토세이브",                            "게임 공통"),
        new Page("sound",   "소리",        "음색 다듬기 · 런처 소리 · 사무쇼2 배경음악",   "소리"),
        new Page("rom",     "롬",          "롬 가져오기 · 롬 폴더"),
        new Page("update",  "업데이트",    "정식 / 시험 · 업데이트 확인",                  "업데이트"),
    };
    /** 첫 화면 목록에는 안 나오는 쪽 — 다른 쪽의 「고급」 줄로만 간다(애플 설정처럼 자주 안 쓰는 건 한 칸 안쪽에) */
    public static final Page[] SUBPAGES = { };   /* 151: 「보간 고급」(motion_adv) 뺌 — 프레임 패치는 켬/끔 하나 */
    public static Page page(String id) {
        for (Page p : PAGES) if (p.id.equals(id)) return p;
        for (Page p : SUBPAGES) if (p.id.equals(id)) return p;
        return null;
    }

    /* ── 프레임 패치 묶음 ── (MOTION 열쇠는 맨 위 — GROUPS 초기화가 먼저 쓴다)
       켬 = 가장 좋은 조합: 자동(120Hz 화면이면 120 출력, 아니면 60 에 맞춤) · 예측(지연 0) · 4배 · 이펙트 옮기기 · 날아가는 몸 섞기 ·
       서기 그리기 · 동작 사이 그림(캐시). 끔 = 원래 게임 그대로(세부도 모두 끔 — 서기 그리기 4배 출력 등이 남지 않게) */
    static final String[] MOTION_KEYS = { "ngp_framegen", "ngp_framegen_mode", "ngp_framegen_mult", "ngp_framegen_fx", "ngp_framegen_pose", "ngp_framegen_idle", "ngp_framegen_motion" };
    static final String[] MOTION_ON   = { "auto",     "predict", "4", "move", "blend", "draw", "cache" };
    static final String[] MOTION_OFF  = { "disabled", "predict", "4", "off",  "off",   "off",  "off" };
    /** 사람이 고른 값을 쓴다 — 프레임 패치를 켜고 끄면 세부 값을 한꺼번에. 설정 화면·실행 전 창 공용 */
    public static void putUser(String key, String val) {
        put(key, val);
        if (!MOTION.equals(key)) return;
        String[] pre = "off".equals(val) ? MOTION_OFF : MOTION_ON;
        for (int i = 0; i < MOTION_KEYS.length; i++) put(MOTION_KEYS[i], pre[i]);
    }
    /** 앱이 켜질 때 — 151 앞의 묶음(120Hz·60Hz·커스텀, 열쇠가 없던 판)은 켬/끔으로 옮기고, 세부가 켬/끔 조합과 다르면 다시 깐다
     *  (옛 판에서 세부를 손댔어도 이제는 «최선 하나»). 옛 값에서 코어 보간을 꺼 둔 사람만 끔 */
    public static void migrateMotion() {
        Map<String, String> m = load();
        String cur = m.get(MOTION);
        if (!"on".equals(cur) && !"off".equals(cur)) {
            putUser(MOTION, "disabled".equals(m.get("ngp_framegen")) ? "off" : "on");
            return;
        }
        String[] pre = "off".equals(cur) ? MOTION_OFF : MOTION_ON;
        for (int i = 0; i < MOTION_KEYS.length; i++)
            if (!pre[i].equals(m.get(MOTION_KEYS[i]))) { putUser(MOTION, cur); return; }
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
        /** 롬의 «세이브 구역»을 바꾸는 패치라는 표식(예: "allcards"). 비어 있지 않으면 Patcher 가
         *  사본 이름에 이 말을 끼워 넣어 그 판이 자기 .flash 를 갖게 한다 — 안 그러면 기기에 이미
         *  있는 세이브가 로드할 때마다 그 바이트를 덮어 «켜도 아무 일이 없는» 스위치가 된다. */
        public final String saveTag;
        Mod(String id, String game, String ko, String ver, String help, String def,
            String group, String groupKo, String pick, String saveTag) {
            this.id = id; this.game = game; this.ko = ko; this.ver = ver; this.help = help; this.def = def;
            this.group = group; this.groupKo = groupKo; this.pick = pick; this.saveTag = saveTag;
        }
        public boolean grouped() { return group != null && !group.isEmpty(); }
    }
    public static File modsDir()  { return new File(MainActivity.root(), "mods"); }
    /** 폐기한 조작 패치 — 원격 색인(InputPatch 태그 mods)에 남아 있어도 Legacito 는 안 보이고 안 얹는다.
     *  묶음(group) 이름이나 id 로 건다. 옛 PocketCore 가 같은 색인을 읽으므로 색인은 그대로 둔다.
     *  ss2_lightrecover(약베기 후경직 감소 −2~−8): 2026-10-10 유저 지시로 폐기.
     *  ss2_allcards(롬 세이브 구역을 고치던 옛 올카드 — 공장 칸 카드 0xFF·난도 하로 깨짐): 치트 «올카드»(램만, 세이브 안 더럽힘)가 대신한다. */
    static final java.util.Set<String> RETIRED_MODS = new java.util.HashSet<>(java.util.Arrays.asList(
            "ss2_lightrecover", "ss2_allcards"));
    static boolean retired(String id, String group) {
        if (RETIRED_MODS.contains(id) || (group != null && RETIRED_MODS.contains(group))) return true;
        for (String r : RETIRED_MODS) if (id.startsWith(r + "_")) return true;
        return false;
    }
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
                if (retired(id, m.optString("group", ""))) continue;
                out.add(new Mod(id, game, m.optString("ko", id), m.optString("ver", ""),
                                m.optString("help", ""), m.optString("default", "disabled"),
                                m.optString("group", ""), m.optString("group_ko", ""), m.optString("pick", ""),
                                m.optString("save_tag", "")));
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
        /* 3.90 — 유저 지시: 쿠로코 캐릭터 해설은 아웃(메뉴에서 전부 제거), SVC 강 발동 당김 제거.
           메뉴만 지우면 예전 값이 options.txt 에 남아 코어가 계속 켜므로 값도 끈다. 표식 키로 한 번만. */
        if (!"1".equals(m.get("pocketcore_mig390"))) {
            put("pocketcore_mig390", "1");
        }
        /* 125: 번쩍임 줄이기(코어 기본 끔) — 처음 한 번 켬. 127: 기본을 «거의 없게»로 — «약하게»(절반)로는 아직 번쩍이 보였다
           (유저 2026-10-14 「번쩍 화면 전체 효과 말하는 거임」). 125~126 이 넣어 둔 «약하게»도 한 번만 «거의 없게»로, 끔으로 바꾼 사람은 그대로 */
        if (!"1".equals(m.get("pocketcore_mig127_flash"))) {
            put("pocketcore_mig127_flash", "1");
            if (!"off".equals(m.get("ngp_flash_reduce"))) put("ngp_flash_reduce", "strong");
        }
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
