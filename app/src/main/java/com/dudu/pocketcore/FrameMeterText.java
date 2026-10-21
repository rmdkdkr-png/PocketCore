package com.dudu.pocketcore;

/** 프레임 미터 숫자 줄 — practice.c practice_fd_get 의 sum[40] 을 짧은 한국어로(안드로이드 없이 JVM 시험이 되게 순수 자바).
 *  글꼴(갈무리11 pixel.ttf)에 U+2212(−)가 없어 빼기는 ASCII '-' 로 쓴다(없는 글자는 시스템 글꼴로 대신 그려져 모양이 튄다). */
public final class FrameMeterText {
    private FrameMeterText() {}
    public static final int NSUM = 40, VIEW = 80, NONE = 0x7FFF;
    /* sum 칸 */
    public static final int S_ON = 0, S_SEQ = 1, S_LEN = 2, S_SEQ_ON = 3, S_FROZEN = 4, S_GAP = 5, S_VER = 6, S_FOCUS = 7,
            S_P1 = 8, S_P2 = 22, S_ADV = 36, S_ADV_KIND = 37, S_ADV_ATT = 38, S_ADV_ID = 39;
    /* 동작 묶음 안 칸(S_P1/S_P2 + …) */
    public static final int A_ID = 0, A_STARTUP = 1, A_ACTIVE = 2, A_RECOVERY = 3, A_TOTAL = 4, A_CONTACT = 5, A_PSTART = 6, A_PLEN = 7,
            A_MEM_ACTIVE = 8, A_MEM_RECOVERY = 9, A_MEM_HIT = 10, A_MEM_DOWN = 11, A_MEM_BLOCK = 12, A_MEM_THROW = 13;
    /* 닿음 종류 */
    public static final int C_NONE = 0, C_HIT = 1, C_BLOCK = 2, C_THROW = 3, C_OTHER = 4, C_DOWN = 5;
    /* 칸 종류 */
    public static final int K_IDLE = 0, K_MOVE = 1, K_STARTUP = 2, K_ACTIVE = 3, K_RECOVERY = 4, K_ACTION = 5,
            K_HITSTUN = 6, K_BLOCKSTUN = 7, K_DOWN = 8, K_GAP = 9, F_PARRY = 0x40, F_PROJ = 0x80, EMPTY = 0xFF;

    /** 숫자 줄 한 조각 — 글과 «지금 일어난 것» 여부(밝게 그림), 유불리면 부호(+1 유리 · −1 불리 · 0 아님/같음) */
    public static final class Part {
        public final String text; public final boolean now; public final int sign;
        Part(String t, boolean n, int s) { text = t; now = n; sign = s; }
        @Override public String toString() { return text; }
    }

    /** +6 · -6 · ±0 */
    public static String signed(int v) { return v > 0 ? "+" + v : v < 0 ? "-" + (-v) : "±0"; }

    /** 내가 친 기술의 결과 이름(히트·가드·…) · 상대가 친 기술을 내가 받은 결과 이름(맞음·막음·…) */
    static String kindWord(int kind, boolean mine) {
        switch (kind) {
        case C_HIT: return mine ? "히트" : "맞음";
        case C_BLOCK: return mine ? "가드" : "막음";
        case C_THROW: return mine ? "잡기" : "잡힘";
        case C_DOWN: return mine ? "다운" : "다운됨";
        case C_OTHER: return "상쇄";
        default: return mine ? "헛침" : "";
        }
    }

    /** 숫자 줄 조각들. 없으면 빈 배열(뷰는 안내 «공격하면 프레임이 나와요» 를 그린다). */
    public static Part[] parts(int[] s) {
        java.util.ArrayList<Part> out = new java.util.ArrayList<>();
        if (s == null || s.length < NSUM) return new Part[0];
        int f = s[S_FOCUS];
        if (f != 0 && f != 1) return new Part[0];
        int b = f == 0 ? S_P1 : S_P2;
        if (s[b + A_ID] == 0) return new Part[0];
        String who = f == 1 ? "상대 " : "";
        int st = s[b + A_STARTUP], contact = s[b + A_CONTACT];
        if (st < 0) {                                              /* 판정 없는 동작 */
            if (s[b + A_PSTART] > 0) out.add(new Part(who + "튕겨내기 " + s[b + A_PSTART] + "F · 지속 " + s[b + A_PLEN] + "F", false, 0));
            else out.add(new Part(who + "동작", false, 0));
            out.add(new Part("전체 " + s[b + A_TOTAL] + "F", false, 0));
            return out.toArray(new Part[0]);
        }
        out.add(new Part(who + "발동 " + st + "F", true, 0));
        if (s[b + A_ACTIVE] == -2) out.add(new Part("장풍", false, 0));
        else if (contact == C_NONE) out.add(new Part("지속 " + s[b + A_ACTIVE] + "F · 경직 " + s[b + A_RECOVERY] + "F", true, 0));
        else if (s[b + A_MEM_ACTIVE] >= 0) out.add(new Part("지속 " + s[b + A_MEM_ACTIVE] + "F · 경직 " + s[b + A_MEM_RECOVERY] + "F", false, 0));   /* 헛쳤을 때 잰 값 */
        if (f == 0) {                                              /* 내 기술 — 기억한 유불리를 다 보이고 방금 것만 밝게 */
            int[] slot = { A_MEM_HIT, A_MEM_DOWN, A_MEM_BLOCK, A_MEM_THROW };
            int[] kind = { C_HIT, C_DOWN, C_BLOCK, C_THROW };
            int curKind = (s[S_ADV_ATT] == 0 && s[S_ADV_ID] > 0) ? s[S_ADV_KIND] : -1;
            for (int i = 0; i < 4; i++) {
                int v = s[b + slot[i]];
                if (v == NONE) continue;
                out.add(new Part(kindWord(kind[i], true) + " " + signed(v), kind[i] == curKind && contact != C_NONE, Integer.signum(v)));
            }
        } else if (s[S_ADV_ATT] == 1 && s[S_ADV_ID] > 0 && s[S_ADV] != NONE) {   /* 상대 기술을 받음 — 내 쪽 유불리(198 부터 native 가 focus 1 · att 1 을 안 내 쓰이지 않음 — 옛 자료 호환) */
            out.add(new Part(kindWord(s[S_ADV_KIND], false) + " " + signed(s[S_ADV]), true, Integer.signum(s[S_ADV])));
        }
        return out.toArray(new Part[0]);
    }

    /** 한 줄 글(조각을 « · » 로) */
    public static String line(int[] s) {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts(s)) { if (sb.length() > 0) sb.append(" · "); sb.append(p.text); }
        return sb.toString();
    }

    public static final String HINT = "공격하면 프레임이 나와요";
    public static final String[] LEGEND = { "발동", "지속", "경직", "판정 없음", "맞음", "막음", "다운" };
    public static final int[] LEGEND_K = { K_STARTUP, K_ACTIVE, K_RECOVERY, K_ACTION, K_HITSTUN, K_BLOCKSTUN, K_DOWN };
}
