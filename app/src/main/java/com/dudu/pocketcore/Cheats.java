package com.dudu.pocketcore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;

/**
 * 치트(사무쇼2 · native cheat.c) — options.txt 키·값과 native 번호를 잇는 자리, 올카드 «세이브에 남기기»의 .flash 보관·되돌리기.
 * 설계: next/cheat/design.md (7장).
 *
 * 값은 options.txt 의 pocketcore_ss2_cheat_* 에 게임별로 남는다(키에 ss2 를 넣는다 — 다음 게임에 치트가 생기면 pocketcore_<게임id>_cheat_* 로 같은 모양).
 * 값 이름(«off»…)으로 저장하는 까닭: 고르는 순서를 바꿔도 옛 options.txt 가 엉뚱한 값이 되지 않게(모르는 값은 기본값 — Settings.Item 기존 동작).
 * 고르는 순서(VALS)가 곧 native 번호(cheat.h CH_* 값)다.
 *
 * 마스터(«치트» 켬/끔, 기본 켬)를 끄면 native 에 모두 0 을 넣는다 — 고른 값은 그대로 남아 다시 켜면 돌아온다. 하나하나는 기본 끔.
 * 168: 리그 판(랭크전·게임 속 리그)에서도 먹는다(유저 «리그 때도 쓸 수 있게») — 그 판에 실제로 먹은 치트(native cheat_match_bits)는 경기 기록에 «치트»로 남고,
 * 정산 검사는 그 치트가 설명하는 것만 면제한다(시간 무한 → 실효 시간 · CPU 세기 → 손잡이). 연습 대전 흐름은 예전처럼 경기 치트를 쉰다.
 */
final class Cheats {

    static final String GAME = "ss2";
    static final String MASTER = "pocketcore_ss2_cheat";
    static final String PREFIX = "pocketcore_ss2_cheat_";
    /** Settings.GROUPS 의 소절 이름 — 실행 창·게임 안 「설정」의 «치트 ›» 고리가 여는 창의 줄들 */
    static final String GROUP = "치트";

    /** native CH_* 순서 (cheat.h) */
    static final String[] KEYS = { "cards", "time", "hp1", "hp2", "rage1", "rage2", "cpu", "diff", "pow", "yuga" };
    static final int CARDS = 0, TIME = 1, HP1 = 2, HP2 = 3, RAGE1 = 4, RAGE2 = 5, CPU = 6, DIFF = 7, POW = 8, YUGA = 9;
    static final String CARDS_KEY = PREFIX + "cards", CARDS_TEMP = "temp", CARDS_SAVE = "save";

    private static final String[] OFF_INF = { "off", "inf" }, HP = { "off", "inf", "one" }, OFF_MAX = { "off", "max" };
    private static final String[] HP_K = { "보통", "무한", "한 방" }, MAX_K = { "보통", "MAX" };
    static final String[][] VALS = {
        { "off", CARDS_TEMP, CARDS_SAVE }, OFF_INF, HP, HP, OFF_MAX, OFF_MAX, { "off", "idle" },
        { "game", "vease", "easy", "normal", "hard", "max" }, { "1", "1.5", "2", "3" }, { "off", "shura", "rasetsu" } };
    static final String[][] NAMES = {
        { "끔", "이번만", "세이브에 남기기" }, { "보통", "무한" }, HP_K, HP_K, MAX_K, MAX_K, { "보통", "가만히" },
        { "게임대로", "아주 쉬움", "하", "중", "상", "최강" }, { "보통", "1.5배", "2배", "3배" }, { "끔", "수라", "나찰" } };
    static final String[] LABELS = { "올카드", "시간", "내 체력", "상대 체력", "내 분노", "상대 분노", "상대", "CPU 세기", "내 공격력", "유가" };
    /* 설명 — 짧게, 게임 말투. 올카드는 게임이 언제 저장하는지까지(사용자가 «세이브에 남기기» 뒤 바로 남기는 법을 알게) */
    static final String[] HELPS = {
        "이번만 = 고르는 동안만 120장 다 가진 걸로, 세이브엔 안 남아요. 세이브에 남기기 = 게임이 다음에 저장할 때(게임 오버·스토리 클리어·무한 대전 끝·게임 설정 저장) 모두 8장으로 남아요."
            + " 바로 남기려면 게임 설정에서 값 하나를 바꿨다 돌려 «예».",
        "라운드 시간이 멈춰요.",
        "한 방 = 한 대 맞으면 쓰러져요.",
        "한 방 = 한 대 맞히면 이겨요.",
        "분노 MAX 가 계속돼요. 비오의를 써도 다시 차요.",
        "상대도 MAX 그대로.",
        "CPU 가 움직이지 않아요(막지도 않아요).",
        "게임 설정은 그대로 — 공격·방어 배율과 AI 단계만 게임 표 값으로. 게임대로로 돌리면 다음 상대부터.",
        "카드 효과에 곱해져요.",
        "캐릭터 선택 가운데(쿠로코) 칸이 유가가 돼요. 수라·나찰은 여기서 골라요." };
    /** 치트 창 끝 한 줄 — 기록 경고 */
    static final String WARN = "치트로 이겨도 스토리 클리어·연승·카드 상은 세이브에 남아요. 리그 대전에서도 먹어요 — 그 판은 기록에 «치트»로 남아요.";
    /** 168 — 먹은 치트 비트(1 << 번호) → «시간·CPU 세기·상대 체력» (없으면 빈 글) */
    static String names(int bits) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < LABELS.length; i++) if ((bits & (1 << i)) != 0) { if (b.length() > 0) b.append('·'); b.append(LABELS[i]); }
        return b.toString();
    }

    /** Settings.GROUPS «치트» 의 항목들 — 모두 사무쇼2 전용(.g), 실행 창 본문엔 안 낀다(.l() 없음 — 고리 하나로 따로 연다) */
    static Settings.Item[] items() {
        Settings.Item[] out = new Settings.Item[KEYS.length + 1];
        out[0] = new Settings.Item(MASTER, "치트", "끄면 아래 것을 다 쉬어요(고른 건 그대로).",
                new String[]{ "enabled", "disabled" }, new String[]{ "켬", "끔" }, "enabled").g(GAME);
        for (int i = 0; i < KEYS.length; i++)
            out[i + 1] = new Settings.Item(PREFIX + KEYS[i], LABELS[i], HELPS[i], VALS[i], NAMES[i], VALS[i][0]).g(GAME);
        return out;
    }

    static boolean master(Map<String, String> m) { return !"disabled".equals(m.get(MASTER)); }

    /** 키 → native 값(0..n). 마스터 끔이면 모두 0, 모르는 값·없는 키는 0(= 기본 끔) */
    static int[] values(Map<String, String> m) {
        int[] v = new int[KEYS.length];
        if (!master(m)) return v;
        for (int i = 0; i < KEYS.length; i++) {
            String s = m.get(PREFIX + KEYS[i]);
            for (int k = 0; s != null && k < VALS[i].length; k++) if (VALS[i][k].equals(s)) { v[i] = k; break; }
        }
        return v;
    }
    /** 켜진(먹는) 치트 수 — 고리 글 «치트 · 3개 켬». 마스터 끔이면 0 */
    static int count(Map<String, String> m) { int n = 0; for (int x : values(m)) if (x != 0) n++; return n; }
    static boolean any(Map<String, String> m) { return count(m) > 0; }
    /** 경기 치트(시간~공격력)가 하나라도 켜졌나 — 연습 대전 중엔 이것들이 쉰다(띠 안내) */
    static boolean fight(Map<String, String> m) { int[] v = values(m); for (int i = TIME; i <= POW; i++) if (v[i] != 0) return true; return false; }
    /** native 에 값만 넣는다(코어를 다시 올리지 않음 — 다음 진짜 프레임부터 먹는다). 잠긴 세션(리그)에선 native 가 무시 */
    static void push(Map<String, String> m) { int[] v = values(m); for (int i = 0; i < v.length; i++) Emu.nativeCheatSet(i, v[i]); }

    /* ── 올카드 «세이브에 남기기» — 세이브 보관·되돌리기 ──
       코어는 롬을 읽을 때 saves/<롬 이름(확장자 뺌)>.flash 를 롬의 세이브 구역 위에 되쓰고, 내릴 때 바뀐 칸만 다시 남긴다.
       그래서 «세이브에 남기기»를 고르는 순간의 .flash 가 «올카드 전» 세이브다(코어가 돌고 있어도 그 세션에서 게임이 저장한 칸은 내릴 때 써진다).
       보관본은 처음 것 하나만(이미 있으면 그대로 — 두 번 켜도 올카드 뒤 세이브로 덮이지 않게). .flash 가 없던 사람(롬 안 세이브만)은 빈 파일로
       «없었음»을 남기고, 되돌리면 .flash 를 지운다. .flash 를 앱이 직접 고쳐 «지금 바로 영구»로 만드는 길은 안 쓴다(일지 칸 고르기를 모른다 — 설계 3장). */

    static String base(String romPath) {
        String n = new File(romPath).getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
    static File flash(File saveDir, String romPath)  { return new File(saveDir, base(romPath) + ".flash"); }
    static File backup(File saveDir, String romPath) { return new File(saveDir, base(romPath) + ".flash.cheatbak"); }
    static boolean hasBackup(File saveDir, String romPath) { return backup(saveDir, romPath).exists(); }

    /** «세이브에 남기기»를 고를 때 한 번 — <롬>.flash → <롬>.flash.cheatbak (이미 있으면 그대로). 보관이 확실하면 true */
    static boolean backupFlash(File saveDir, String romPath) {
        File bk = backup(saveDir, romPath), fl = flash(saveDir, romPath);
        if (bk.exists()) return true;
        saveDir.mkdirs();
        File tmp = new File(bk.getPath() + ".tmp");
        try {
            if (fl.exists()) copy(fl, tmp);
            else new FileOutputStream(tmp).close();          /* 빈 파일 = .flash 가 없었음(롬 안 세이브) */
            return tmp.renameTo(bk);
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
    }

    /** 런처에서만(코어가 이 롬을 안 돌 때) — 보관본으로 .flash 를 되돌리고, 그 게임의 오토세이브(상태 파일은 플래시 내용을 싣고 다닌다)는
     *  <롬 파일 이름>.state.auto.cheat 로 비켜 둔다(연습 옆 파일 .prac 도 같이). 다 되면 보관본을 지운다. */
    static boolean restoreFlash(File saveDir, String romPath) {
        File bk = backup(saveDir, romPath), fl = flash(saveDir, romPath);
        if (!bk.exists()) return false;
        try {
            if (bk.length() == 0) { if (fl.exists() && !fl.delete()) return false; }
            else {
                File tmp = new File(fl.getPath() + ".tmp");
                copy(bk, tmp);
                if (fl.exists() && !fl.delete()) { tmp.delete(); return false; }
                if (!tmp.renameTo(fl)) return false;
            }
        } catch (Exception e) {
            return false;
        }
        String rn = new File(romPath).getName();
        for (String sfx : new String[]{ ".state.auto", ".state.auto.prac" }) {
            File a = new File(saveDir, rn + sfx);
            if (!a.exists()) continue;
            File to = new File(saveDir, rn + sfx.replace(".state.auto", ".state.auto.cheat"));
            to.delete();
            a.renameTo(to);
        }
        bk.delete();
        return true;
    }

    private static void copy(File from, File to) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.getFD().sync();
        }
    }

    private Cheats() { }
}
