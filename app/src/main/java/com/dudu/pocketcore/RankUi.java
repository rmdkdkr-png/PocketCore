package com.dudu.pocketcore;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 랭크 매치 덮개 뷰(게임 화면 위) — «상대 찾는 중…» · «상대 발견!» 카드 · 결과 카드의 아바타 줄 · 순위표 한 줄.
 * 색·글꼴은 리그 띠·결과 카드와 같다(짙은 바탕 · 금 테 · 픽셀 글꼴). 숫자(티어·LP·전적)는 진짜, 닉네임·칭호·지역·한마디·아바타는 지어낸 것 —
 * 그래서 카드엔 늘 «CPU» 칩을 붙이고, 핑·접속 막대 같은 온라인 흉내는 넣지 않는다(LeagueActivity 원칙 «온라인 사람처럼 보이게 하지 않는다»).
 */
final class RankUi {
    private RankUi() { }

    static final int BG = 0xf2140d08, STROKE = 0xffd9a441, STROKE2 = 0xffb8862b, GOLD = 0xffffd76a, BODY = 0xfff3ead7,
                     DIM = 0xff8b93a6, WIN = 0xff6fcf8f, LOSE = 0xffe5737a, INK = 0xff140d08;
    /** 티어 색 — 수련생 … 무쌍 */
    static final int[] TIER_COLOR = { 0xff8b93a6, 0xffa08a6a, 0xffc08040, 0xffc8d0dc, 0xffd9a441, 0xff6fcfcf, 0xffe5737a, 0xffd28cff };

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    static TextView tv(Context c, Typeface f, String s, float sp, int color) {
        TextView t = new TextView(c); t.setTypeface(f); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }
    static TextView line(Context c, Typeface f, String s, float sp, int color) {
        TextView t = tv(c, f, s, sp, color);
        t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }
    static GradientDrawable box(Context c, int fill, int stroke, float strokeDp, float radiusDp) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(c, strokeDp), stroke);
        g.setCornerRadius(dp(c, radiusDp)); return g;
    }
    static TextView chip(Context c, Typeface f, String s, int fg, int bg) {
        TextView t = tv(c, f, s, 10, fg); t.setBackground(box(c, bg, 0, 0, 3)); t.setPadding(dp(c, 4), dp(c, 2), dp(c, 4), dp(c, 2)); return t;
    }
    static PixelView avatar(Context c, JSONObject pf, String id, int sizeDp, int frame) {
        PixelView v = new PixelView(c); v.set(Avatar.of(pf, id), frame);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }
    static int tierColor(int lp, boolean master) { return TIER_COLOR[master ? 7 : RankCore.tierOf(lp)]; }
    static String signed(int v) { return v > 0 ? "+" + v : v < 0 ? "-" + (-v) : "±0"; }   /* 빼기는 ASCII — 픽셀 글꼴에 U+2212 가 없다 */

    private static LinearLayout card(Context c) {
        LinearLayout k = new LinearLayout(c);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setBackground(box(c, BG, STROKE, 3, 6));
        k.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        k.setClickable(true);                                   /* 카드 위 탭이 게임 화면(패드)으로 새지 않게 */
        return k;
    }
    static TextView pill(Context c, Typeface f, String s, boolean primary, final Runnable r) {
        TextView t = tv(c, f, s, 15, primary ? INK : BODY);
        t.setGravity(Gravity.CENTER);
        t.setBackground(box(c, primary ? STROKE : 0xff2a2218, primary ? 0 : STROKE2, 1.5f, 4));
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setMinHeight(dp(c, 48));
        t.setClickable(true);
        t.setOnClickListener(v -> r.run());
        return t;
    }
    private static LinearLayout.LayoutParams weight(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(c, 4); lp.rightMargin = dp(c, 4); return lp;
    }

    /* ── 찾는 중 ── */
    static final class Search {
        LinearLayout root; TextView time, sub; PixelView spin;
        private int n = 0;
        private final String[] lines;
        Search(String[] lines) { this.lines = lines; }
        /** 150ms 마다 — 시간·번갈아 문구·굴러가는 아바타 */
        void tick(long ms, long seed) {
            long s = ms / 1000;
            time.setText(String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60));
            int k = (int) (ms / 2500) % lines.length;
            String t = ms > 6000 && k == lines.length - 1 ? "범위 넓히는 중…" : lines[k];
            if (!t.contentEquals(sub.getText())) sub.setText(t);
            if ((n++ & 1) == 0) spin.set(Avatar.bitmap(RankCore.Rng.mix(seed, ms / 300)), STROKE2);
        }
    }
    static Search search(Context c, Typeface f, String myLabel, String extra, long active, final Runnable onCancel) {
        Search s = new Search(new String[]{ "오늘 " + String.format(java.util.Locale.KOREA, "%,d", active) + "명 접속",
                "비슷한 실력 찾는 중", "상대는 모두 게임 CPU 예요", "범위 넓히는 중…" });
        LinearLayout k = card(c);
        k.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout top = new LinearLayout(c); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        s.spin = new PixelView(c); s.spin.setDim(0x70);
        top.addView(s.spin, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(c, 10), 0, 0, 0);
        col.addView(tv(c, f, "상대 찾는 중…", 18, GOLD));
        s.time = tv(c, f, "0:00", 13, BODY); s.time.setPadding(0, dp(c, 4), 0, 0);
        col.addView(s.time);
        top.addView(col);
        k.addView(top);
        TextView me = line(c, f, myLabel, 12, BODY); me.setPadding(0, dp(c, 8), 0, 0); me.setGravity(Gravity.CENTER);
        k.addView(me);
        if (extra != null) { TextView x = line(c, f, extra, 12, GOLD); x.setGravity(Gravity.CENTER); k.addView(x); }
        s.sub = line(c, f, "", 11, DIM); s.sub.setGravity(Gravity.CENTER); s.sub.setPadding(0, dp(c, 4), 0, dp(c, 8));
        k.addView(s.sub);
        k.addView(pill(c, f, "취소 (B)", false, onCancel));
        s.root = k;
        return s;
    }

    /* ── 상대 발견! ── */
    static final class Found { LinearLayout root; TextView stage, go; }
    /**
     * pf = 꾸밈 프로필 · v = 지금 모습(티어·LP) · h2h = {내 승, 내 패, 무, 마지막, 끊은 연승} · stakes = {이기면, 지면}(배치·무쌍이면 null)
     */
    static Found found(Context c, Typeface f, JSONObject pf, League.OppView v, java.util.List<String> tags, int[] h2h, int[] stakes,
                       String placement, String stageText, Runnable onGo, Runnable onLeave, Runnable onStageL, Runnable onStageR) {
        Found o = new Found();
        RankCore.Player p = v.p;
        LinearLayout k = card(c);
        LinearLayout head = new LinearLayout(c); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = tv(c, f, "상대 발견!", 18, GOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (!tags.isEmpty()) { TextView tg = chip(c, f, tags.get(0), INK, GOLD); head.addView(tg); }
        TextView cpu = chip(c, f, "CPU", BODY, 0xff3a3226);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = dp(c, 6); head.addView(cpu, cl);
        k.addView(head);

        LinearLayout row = new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 8), 0, dp(c, 4));
        row.addView(avatar(c, pf, p.id, 64, tierColor(v.lp, v.master)));
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(c, 10), 0, 0, 0);
        col.addView(line(c, f, pf.optString("nick", p.nick), 17, BODY));
        TextView lab = line(c, f, v.master ? v.label : v.label + " · " + v.lp + " LP", 13, tierColor(v.lp, v.master)); lab.setPadding(0, dp(c, 3), 0, 0);
        col.addView(lab);
        TextView reg = line(c, f, ProfileGen.regionLabel(pf) + (pf.optString("title").isEmpty() ? "" : " · «" + pf.optString("title") + "»"), 11, DIM);
        reg.setPadding(0, dp(c, 3), 0, 0); col.addView(reg);
        String tg2 = "";
        JSONArray ta = pf.optJSONArray("tags");
        if (ta != null && ta.length() > 0) tg2 = " · " + ta.optString(0);
        TextView ch = line(c, f, League.CHAR[p.ch] + " " + League.STYLE[p.sty] + tg2, 12, BODY); ch.setPadding(0, dp(c, 3), 0, 0);
        col.addView(ch);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        k.addView(row);

        String cmt = pf.optString("cmt");
        if (!cmt.isEmpty()) { TextView q = line(c, f, "“" + cmt + "”", 12, GOLD); q.setPadding(0, dp(c, 2), 0, dp(c, 2)); k.addView(q); }
        StringBuilder sb = new StringBuilder();
        int games = h2h[0] + h2h[1] + h2h[2];
        sb.append(games == 0 ? "첫 대전" : "나와 " + h2h[0] + "승 " + h2h[1] + "패" + (h2h[2] > 0 ? " " + h2h[2] + "무" : ""));
        for (int i = 1; i < tags.size(); i++) if (!"첫 대전".equals(tags.get(i))) sb.append(" · ").append(tags.get(i));
        k.addView(line(c, f, sb.toString(), 12, BODY));
        TextView st = line(c, f, placement != null ? placement : stakes != null ? "이기면 +" + stakes[0] + " · 지면 " + signed(stakes[1]) + " LP" : "무쌍 — MR 승부", 12,
                placement != null ? GOLD : BODY);
        st.setPadding(0, dp(c, 3), 0, 0); k.addView(st);

        LinearLayout sr = new LinearLayout(c); sr.setOrientation(LinearLayout.HORIZONTAL); sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setPadding(0, dp(c, 6), 0, dp(c, 8));
        TextView l = tv(c, f, " ◀ ", 15, GOLD); l.setOnClickListener(x -> onStageL.run());
        TextView r = tv(c, f, " ▶ ", 15, GOLD); r.setOnClickListener(x -> onStageR.run());
        o.stage = line(c, f, stageText, 12, BODY); o.stage.setGravity(Gravity.CENTER);
        sr.addView(l); sr.addView(o.stage, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); sr.addView(r);
        k.addView(sr);

        LinearLayout btn = new LinearLayout(c); btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.addView(pill(c, f, "나가기 (B)", false, onLeave), weight(c));
        o.go = pill(c, f, "대전 (A)", true, onGo);
        btn.addView(o.go, weight(c));
        k.addView(btn);
        o.root = k;
        return o;
    }

    /** 결과 카드 머리 — 아바타 + 상대 이름·티어 한 줄 */
    static View resultHead(Context c, Typeface f, JSONObject snap, String id) {
        LinearLayout row = new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        JSONObject pf = new JSONObject();
        try { pf.put("av", snap.optString("av")); } catch (Exception ignored) { }
        row.addView(avatar(c, pf, id, 40, STROKE2));
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(c, 8), 0, 0, 0);
        col.addView(line(c, f, snap.optString("nick") + "  " + League.CHAR[Math.max(0, Math.min(14, snap.optInt("ch")))] + " " + League.STYLE[snap.optInt("sty") & 1], 13, BODY));
        TextView lb = line(c, f, snap.optString("label") + (snap.optBoolean("legacy") ? " · 라이벌" : ""), 11, DIM); lb.setPadding(0, dp(c, 2), 0, 0);
        col.addView(lb);
        row.addView(col);
        return row;
    }
}
