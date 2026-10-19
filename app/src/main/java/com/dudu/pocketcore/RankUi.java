package com.dudu.pocketcore;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 랭크 매치 덮개 뷰(게임 화면 위) — 153 짧은 찾기 겹창(VS 판 — 148 대기창·«상대 발견!» 카드를 대신) · 결과 카드의 아바타 줄 · 순위표 한 줄.
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

    /* ── 153 짧은 찾기 겹창 — 게임 그림을 통째로 덮는 VS 판 ──
       왼쪽 = 나(아바타·이름·티어·내 캐릭터) · 가운데 VS · 오른쪽 = 상대. 찾는 동안(~1초)은 실제로 훑어본 후보가 몇 번 넘어가고(흐리게),
       «상대 발견!» 에 밝게 멈춘다(번쩍 · VS 튕김 · 짧은 진동). 그동안 native 가 리셋·고르기 화면을 4배속·무음으로 밟고, 무대 인트로
       (게임 속 VS 띠)에 닿으면 EmuActivity 가 내린다. 바탕은 불투명 — 리셋·고르기 화면이 비치지 않게. 누르기는 안 받는다
       (패드 아래에 깔려 멈춤 바가 위에 그려진다) — B·뒤로는 EmuActivity 가. 148 대기창·«상대 발견!» 카드를 대신한다 */

    /** 릴 한 칸 — 아바타 + «닉 · 티어» */
    static final class ReelItem {
        final android.graphics.Bitmap av; final String line; final int color;
        ReelItem(android.graphics.Bitmap av, String line, int color) { this.av = av; this.line = line; this.color = color; }
    }
    /** VS 판 한쪽 — 아바타(테두리 색) · 이름 · 티어 줄(색) · 캐릭터 줄 */
    static final class Side {
        android.graphics.Bitmap av; int frame = STROKE2;
        String nick = "", tier = "", ch = ""; int tierColor = BODY;
        String chCol;                                                    /* 177 상대 컬러 이름(«상대 발견!» 캐릭터 줄 뒤 — 들 때만) · null 없음 */
    }
    static final class Quick {
        android.widget.FrameLayout root;
        private TextView title, sub, hint, oppNick, oppTier, oppCh, vs;
        private PixelView oppAv;
        private View flash;
        private java.util.List<ReelItem> reel = java.util.Collections.emptyList();
        private long quickMs = 1000;
        private final long seed = System.nanoTime();
        private boolean revealed;
        private int shown = Integer.MIN_VALUE, dots = -1;
        private String searchTitle = "";
        private static void set(TextView t, String s) { if (!s.contentEquals(t.getText())) t.setText(s); }
        boolean revealed() { return revealed; }

        /** 폴(50ms)마다 — 점 셋 · 릴(140ms 마다, 마지막 두 칸은 200 · 280ms). 드러낸 뒤엔 아무것도 안 한다 */
        void tick(long e) {
            if (revealed) return;
            int d = (int) ((e / 250) % 3);
            if (d != dots) { dots = d; set(title, searchTitle + (d == 0 ? " ·" : d == 1 ? " · ·" : " · · ·")); }
            int s = slot(Math.max(0, Math.min(e, quickMs)));
            if (s == shown) return;
            shown = s;
            if (reel.isEmpty()) { oppAv.set(Avatar.bitmap(RankCore.Rng.mix(seed, s)), STROKE2); return; }   /* 잡힌 상대·라이벌·프로필 아직 — 이름 없이 도는 아바타 */
            ReelItem it = reel.get(s % reel.size());
            oppAv.set(it.av, STROKE2);
            set(oppNick, it.line);
        }
        private int slot(long e) {
            long L = Math.max(0, quickMs - 480);
            if (e < L) return (int) (e / 140);
            int n = (int) (L / 140);
            return e < L + 200 ? n + 1 : n + 2;
        }
        /** 훑어본 후보(백그라운드에서 만든 아바타·줄) */
        void setReel(java.util.List<ReelItem> r) {
            if (r == null || r.isEmpty() || revealed) return;
            reel = new java.util.ArrayList<>(r);
            shown = Integer.MIN_VALUE;
        }
        /** 상대 발견 — 밝게 멈춤. pop = 번쩍·VS 튕김·진동 */
        void reveal(Side o, String t, String subLine, int subColor, String hintLine, boolean pop) {
            revealed = true;
            oppAv.set(o.av, o.frame); oppAv.setDim(0);
            oppNick.setTextColor(BODY); set(oppNick, o.nick);
            oppTier.setTextColor(o.tierColor); set(oppTier, o.tier);
            String chs = o.ch;
            if (o.chCol != null) {                                       /* 177: «하오마루 수라 · 남빛» — 한 줄에 다 들 때만(좁은 화면에서 잘리면 캐릭터만) */
                String full = o.ch + " · " + o.chCol;
                int avail = oppCh.getWidth() - oppCh.getPaddingLeft() - oppCh.getPaddingRight();
                if (avail > 0 && oppCh.getPaint().measureText(full) <= avail) chs = full;
            }
            set(oppCh, chs);
            set(title, t);
            if (subLine != null) { sub.setTextColor(subColor); set(sub, subLine); sub.setVisibility(View.VISIBLE); }
            if (hintLine != null) set(hint, hintLine);
            if (!pop) return;
            flash.setAlpha(0.55f); flash.animate().alpha(0f).setDuration(160).start();
            vs.setScaleX(1.8f); vs.setScaleY(1.8f);
            vs.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
            root.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);   /* 148 의 긴 진동 대신 짧게 */
        }
        void setHint(String s) { if (hint != null) set(hint, s); }
    }
    private static LinearLayout.LayoutParams chipLp(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 4);
        return lp;
    }
    private static LinearLayout sideCol(Context c, Typeface f, Side s, int avDp, boolean compact, TextView[] out, PixelView[] avOut) {
        LinearLayout k = new LinearLayout(c); k.setOrientation(LinearLayout.VERTICAL); k.setGravity(Gravity.CENTER_HORIZONTAL);
        PixelView av = new PixelView(c);
        if (s.av != null) av.set(s.av, s.frame);
        k.addView(av, new LinearLayout.LayoutParams(dp(c, avDp), dp(c, avDp)));
        TextView n = line(c, f, s.nick, compact ? 13 : 15, BODY); n.setGravity(Gravity.CENTER); n.setPadding(0, dp(c, 5), 0, 0);
        TextView t = line(c, f, s.tier, 11, s.tierColor); t.setGravity(Gravity.CENTER); t.setPadding(0, dp(c, 3), 0, 0);
        TextView ch = line(c, f, s.ch, compact ? 12 : 13, GOLD); ch.setGravity(Gravity.CENTER); ch.setPadding(0, dp(c, 3), 0, 0);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        k.addView(n, wl); k.addView(t, new LinearLayout.LayoutParams(wl)); k.addView(ch, new LinearLayout.LayoutParams(wl));
        out[0] = n; out[1] = t; out[2] = ch; avOut[0] = av;
        return k;
    }
    /**
     * 겹창 — me = 나 · topL/topR = 맨 위 줄(«랭크 매치» · «2선승 · 라운드 60초») · searchTitle = «상대 찾는 중»(점은 tick 이) ·
     * quickMs = 상대를 보일 때까지(릴 늦추기 기준) · compact = 게임 그림이 낮다(가로·작은 폰)
     */
    static Quick quick(Context c, Typeface f, Side me, String topL, String topR, String searchTitle, long quickMs, boolean compact) {
        Quick q = new Quick();
        q.quickMs = Math.max(300, quickMs); q.searchTitle = searchTitle;
        android.widget.FrameLayout root = new android.widget.FrameLayout(c);
        root.setBackground(box(c, 0xff140d08, STROKE, 3, 0));          /* 불투명 · 네모(게임 그림 그대로) */
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pd = dp(c, compact ? 8 : 12);
        col.setPadding(pd, pd, pd, pd);
        col.setBackground(new android.graphics.drawable.InsetDrawable(box(c, 0, STROKE2, 1, 0), dp(c, 5)));   /* 안쪽 가는 테 — 게임 창 느낌 */
        LinearLayout top = new LinearLayout(c); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(c, 4), 0, dp(c, 4), 0);
        top.addView(line(c, f, topL, 11, DIM), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(line(c, f, topR, 11, DIM));
        col.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        int av = compact ? 40 : 60;
        TextView[] mt = new TextView[3]; PixelView[] ma = new PixelView[1];
        LinearLayout mc = sideCol(c, f, me, av, compact, mt, ma);
        mc.addView(chip(c, f, "나", INK, GOLD), 0, chipLp(c));            /* 칩 줄 — 상대는 늘 «CPU»(148 카드와 같게 · 온라인 사람처럼 보이지 않게) */
        row.addView(mc, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        q.vs = tv(c, f, "VS", compact ? 22 : 28, GOLD); q.vs.setGravity(Gravity.CENTER);
        q.vs.setShadowLayer(1f, dp(c, 1), dp(c, 1), INK); q.vs.setPadding(dp(c, 6), 0, dp(c, 6), 0);
        row.addView(q.vs);
        Side unknown = new Side(); unknown.nick = "· · ·";
        TextView[] ot = new TextView[3]; PixelView[] oa = new PixelView[1];
        LinearLayout oc = sideCol(c, f, unknown, av, compact, ot, oa);
        oc.addView(chip(c, f, "CPU", BODY, 0xff3a3226), 0, chipLp(c));
        row.addView(oc, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        q.oppNick = ot[0]; q.oppTier = ot[1]; q.oppCh = ot[2]; q.oppAv = oa[0];
        q.oppNick.setTextColor(DIM); q.oppAv.setDim(0x70);
        col.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));   /* 남는 높이 가운데 */

        q.title = line(c, f, searchTitle, compact ? 16 : 19, GOLD); q.title.setGravity(Gravity.CENTER);
        col.addView(q.title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        q.sub = line(c, f, "", 12, BODY); q.sub.setGravity(Gravity.CENTER); q.sub.setPadding(0, dp(c, 4), 0, 0); q.sub.setVisibility(View.INVISIBLE);
        col.addView(q.sub, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        q.hint = line(c, f, "B 그만두기", 10, DIM); q.hint.setGravity(Gravity.CENTER); q.hint.setPadding(0, dp(c, compact ? 3 : 6), 0, 0);
        col.addView(q.hint, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(col, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        q.flash = new View(c); q.flash.setBackgroundColor(0xffffffff); q.flash.setAlpha(0f);
        root.addView(q.flash, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        q.root = root;
        q.tick(0);
        return q;
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
