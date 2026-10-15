package com.dudu.pocketcore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;

/**
 * 사무쇼2 모든 모드 컬러(native look.c)의 앱 쪽 — 이름 · 캐릭터마다 기억한 컬러 · 내 색(캐릭터마다 2칸) 저장, native 에 넣기.
 *
 * 1인 대전·무한 대전·연습 대전·리그 대전 어디서나 캐릭터·검질 고르기에서 OPTION = 다음 컬러(길게 = 컬러 만들기), B = 2P색으로 결정.
 * 값은 앱 설정(options.txt — 리그 세이브와 따로)에 남는다:
 *   pocketcore_ss2_look      enabled / disabled (설정 «컬러 고르기», 기본 켬)
 *   pocketcore_ss2_look_mem  16개 정수 "0,1,0,9,…" — 칸 0~13 캐릭터 · 14 안 씀(유가) · 15 가운데 무작위 칸, 값 0~17
 *   pocketcore_ss2_look_cus  JSON {"v":1,"c":{"11":[[3039,744,65],null], …}} — 캐릭터 → [내 색 1, 내 색 2], 각 [밝은 색, 옷 색, 테 색] 12비트 0x0BGR 또는 null
 * 번호(기록·기억에 남는 값) = NAMES 순서 = native look_tab.h LOOK_NAME = 세로 칸 글자 LOOK_VLAB 순서.
 * 캐릭터마다 기억하는 것은 스파6·길티기어식 — 16가지면 캐릭터마다 어울리는 색이 다르다. B(2P색)는 그 판만(기억 안 바꿈).
 */
final class Look {
    private Look() { }

    static final String[] NAMES = { "기본", "2P색", "쪽빛", "비취", "보라", "다홍", "분홍", "먹빛", "흰옷", "황금", "은빛", "청동", "흑백", "그림자", "핏빛", "유령", "내 색 1", "내 색 2" };
    /** cv 없는 옛 리그 기록(컬러 4개 시절)의 이름 — 번호 2·3 은 이제 쪽빛·비취지만 옛 기록은 옛 이름으로 */
    static final String[] OLD = { "기본", "2P색", "컬러 3", "컬러 4" };
    static final int NPRE = 16, NIDX = 18, NSLOT = 16, NCH = 14;
    static final String K_ON = "pocketcore_ss2_look", K_MEM = "pocketcore_ss2_look_mem", K_CUS = "pocketcore_ss2_look_cus";

    /** 기록에 남은 컬러 번호의 이름 — cv(기록 판) 2 부터 16가지, 그 전(없음)은 옛 4가지 */
    static String name(int idx, int cv) {
        if (cv < 2) return idx >= 0 && idx < OLD.length ? OLD[idx] : OLD[0];
        return idx >= 0 && idx < NAMES.length ? NAMES[idx] : NAMES[0];
    }

    /* ── 글자 ↔ 값(앱 설정 없이도 — JVM 시험이 바로 본다) ─────────────── */

    /** "0,1,0,9,…" 16개 → 기억 칸. 개수가 모자라거나 깨졌으면 null(처음 = 옛 리그 값에서 옮긴다) */
    static byte[] parseMem(String s) {
        if (s == null) return null;
        String[] a = s.split(",");
        if (a.length < NSLOT) return null;
        byte[] m = new byte[NSLOT];
        try {
            for (int i = 0; i < NSLOT; i++) { int v = Integer.parseInt(a[i].trim()); m[i] = (byte) (v >= 0 && v < NIDX ? v : 0); }
        } catch (NumberFormatException e) { return null; }
        return m;
    }
    static String fmtMem(byte[] m) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NSLOT; i++) { if (i > 0) sb.append(','); sb.append(m != null && i < m.length && m[i] >= 0 && m[i] < NIDX ? m[i] : 0); }
        return sb.toString();
    }
    /** 옛 리그 «내 컬러» 하나(0~3) → 16칸 모두 그 값(2·3 은 같은 번호 = 쪽빛·비취로 넘어간다) */
    static byte[] memFromOld(int prefColor) {
        byte[] m = new byte[NSLOT];
        java.util.Arrays.fill(m, (byte) (prefColor >= 0 && prefColor < OLD.length ? prefColor : 0));
        return m;
    }
    /** 내 색 JSON → [14][2] 세 색(없는 칸 null) */
    static int[][][] parseCus(String s) {
        int[][][] c = new int[NCH][2][];
        if (s == null || s.isEmpty()) return c;
        try {
            JSONObject j = new JSONObject(s).optJSONObject("c");
            if (j == null) return c;
            for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                int ch;
                try { ch = Integer.parseInt(k); } catch (NumberFormatException e) { continue; }
                JSONArray a = j.optJSONArray(k);
                if (ch < 0 || ch >= NCH || a == null) continue;
                for (int s2 = 0; s2 < 2 && s2 < a.length(); s2++) {
                    JSONArray t = a.optJSONArray(s2);
                    if (t == null || t.length() < 3) continue;
                    c[ch][s2] = new int[] { t.optInt(0) & 0xFFF, t.optInt(1) & 0xFFF, t.optInt(2) & 0xFFF };
                }
            }
        } catch (Exception ignored) { }
        return c;
    }
    static String fmtCus(int[][][] c) {
        try {
            JSONObject j = new JSONObject();
            for (int ch = 0; ch < NCH; ch++) {
                if (c[ch][0] == null && c[ch][1] == null) continue;
                JSONArray a = new JSONArray();
                for (int k = 0; k < 2; k++) {
                    if (c[ch][k] == null) a.put(JSONObject.NULL);
                    else a.put(new JSONArray().put(c[ch][k][0] & 0xFFF).put(c[ch][k][1] & 0xFFF).put(c[ch][k][2] & 0xFFF));
                }
                j.put(String.valueOf(ch), a);
            }
            return new JSONObject().put("v", 1).put("c", j).toString();
        } catch (Exception e) { return "{\"v\":1,\"c\":{}}"; }
    }

    /* ── 앱 설정 ─────────────────────────────────────────────────── */

    static boolean on() { return !"disabled".equals(Settings.load().get(K_ON)); }

    static byte[] loadMem() {
        byte[] m = parseMem(Settings.load().get(K_MEM));
        return m != null ? m : migrate();
    }
    static void saveMem(byte[] m) {
        String s = fmtMem(m);
        if (!s.equals(Settings.load().get(K_MEM))) Settings.put(K_MEM, s);
    }
    static int[][][] loadCustom() { return parseCus(Settings.load().get(K_CUS)); }
    /** 내 색 k(0·1) 저장 — c3 null = 지움 */
    static void saveCustom(int ch, int k, int[] c3) {
        if (ch < 0 || ch >= NCH || k < 0 || k > 1) return;
        int[][][] c = loadCustom();
        c[ch][k] = c3 == null ? null : new int[] { c3[0] & 0xFFF, c3[1] & 0xFFF, c3[2] & 0xFFF };
        Settings.put(K_CUS, fmtCus(c));
    }
    /** 처음 한 번 — 옛 리그 세이브의 «내 컬러»(pref.color 0~3, 리그 판 전체에 하나)를 캐릭터마다 기억으로 옮긴다.
     *  리그를 안 써 본 사람은 기록 파일을 새로 만들지 않게 파일이 있을 때만 읽는다. 옮긴 값은 바로 앱 설정에 남긴다 */
    static byte[] migrate() {
        int old = 0;
        try {
            if (new File(new File(MainActivity.root(), "league"), "league.json").exists()) old = League.prefColor();
        } catch (Exception ignored) { }
        byte[] m = memFromOld(old);
        Settings.put(K_MEM, fmtMem(m));
        return m;
    }

    /** native 에 기억·내 색을 모두 넣는다(판을 올릴 때 — 내 색은 두 검질 같이) */
    static void push() {
        Emu.nativeLookSetMem(loadMem());
        int[][][] c = loadCustom();
        for (int ch = 0; ch < NCH; ch++)
            for (int k = 0; k < 2; k++) {
                int[] t = c[ch][k];
                Emu.nativeLookSetCustom(ch, k, t != null ? t[0] : -1, t != null ? t[1] : 0, t != null ? t[2] : 0);
            }
    }

    /** 12비트 0x0BGR → 안드로이드 색(0xAARRGGBB) — 채널 0~15 를 0~255 로(×17) */
    static int argb(int c) {
        int r = c & 15, g = (c >> 4) & 15, b = (c >> 8) & 15;
        return 0xff000000 | (r * 17) << 16 | (g * 17) << 8 | (b * 17);
    }
    /** 세 색이 모두 거의 같나(채널 차 합 < 6) — 알아보기 어려운 색(아랑 «Edited Colors» 조사 항목). 막지는 않고 안내만 */
    static boolean tooClose(int[] c) {
        return dist(c[0], c[1]) < 6 && dist(c[1], c[2]) < 6 && dist(c[0], c[2]) < 6;
    }
    static int dist(int a, int b) {
        int d = 0;
        for (int s = 0; s < 12; s += 4) d += Math.abs(((a >> s) & 15) - ((b >> s) & 15));
        return d;
    }
}
