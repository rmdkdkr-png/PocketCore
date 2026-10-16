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
 * 랭크 매치 덮개 뷰(게임 화면 위) — 대기창(«상대 찾는 중…» · 148) · «상대 발견!» 카드 · 결과 카드의 아바타 줄 · 순위표 한 줄.
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

    /* ── 찾는 중(대기창) ──
       게임 그림 위쪽에 — 릴(실제로 훑어본 후보가 돌다 내 상대에게 멈춤) · 시간 «0:03 · 예상 0:07» · 내 티어·LP 막대 · 오늘 전적 · 한 줄 알림 ·
       팁(▲▼) · 스테이지(◀▶) · 취소 · 번갈아 문구. 게임 그림이 300dp 보다 낮으면(가로·작은 폰) 팁·오늘 줄을 뺀다(compact).
       높이는 게임 그림 안으로 — 그래도 길면 한 줄 알림·번갈아 문구까지 뺀다(fit). 148 설계 3.2 */

    /** 대기창에 넣는 값 — EmuActivity 가 League(rankMe·rankedToday·bonusLeft)·RankTips 로 채운다. 글자는 다 만들어 넣는다(여기선 그리기만) */
    static final class Wait {
        String rankLine;                 // «검객 ★2 · 1,240 LP» · «배치 경기 2/5» · «무쌍 · MR 1,712»
        int rankColor = BODY;
        float barFrac = -1;              // LP 막대 0~1(없으면 −1 — 배치·무쌍)
        String barLine;                  // «다음 ★까지 60 LP» · «승급까지 …» · «강등 위기!» · 배치 «5판을 마치면 첫 티어가 정해져요»
        boolean danger;                  // 강등 위기 — barLine 을 LOSE 색으로
        String todayLine, dots = "";     // «오늘 2승 1패 · +35 LP» · «●●○»(새것이 왼쪽)
        String noteLine;                 // «3연승 중!» 등 한 줄(없으면 null)
        int estSec;                      // 예상(초) — 처음에 정하고 안 늘린다
        boolean held;                    // 잡힌 상대에게 가는 중(«기다리던 상대에게 가는 중…»)
        android.graphics.Bitmap heldAv;  // 그 상대 아바타(흐리게) — 늦게 오면 setHeld
        String stageLabel = "상대 기본", tip;
        long active;                     // «오늘 N명 접속»
        boolean compact;
    }
    /** 릴 한 칸 — 아바타 + «닉 · 티어» */
    static final class ReelItem {
        final android.graphics.Bitmap av; final String line; final int color;
        ReelItem(android.graphics.Bitmap av, String line, int color) { this.av = av; this.line = line; this.color = color; }
    }

    static final class Search {
        LinearLayout root; TextView title, time, reelLine, rank, barLine, today, dots, note, tip, stage, sub, cancel; PixelView spin;
        private View sep2, todayRow, tipRow;
        private final String[] lines, linesWide;
        private final boolean held; private final int estSec; private final long seed;
        private java.util.List<ReelItem> reel = java.util.Collections.emptyList();
        private boolean landed, hasNote;
        private int fitW = -1, fitMax = -1; private boolean fitLow;
        private long lastE, lastAt, waitMs = 1;
        private int shown = Integer.MIN_VALUE;
        private Search(Wait w) {
            String a = "오늘 " + String.format(java.util.Locale.KOREA, "%,d", w.active) + "명 접속";
            lines = new String[]{ a, "비슷한 실력 찾는 중", "상대는 모두 게임 CPU 예요" };
            linesWide = new String[]{ a, "비슷한 실력 찾는 중", "상대는 모두 게임 CPU 예요", "범위 넓히는 중…" };
            held = w.held; estSec = w.estSec; seed = System.nanoTime();
        }
        private static void set(TextView t, String s) { if (!s.contentEquals(t.getText())) t.setText(s); }
        private static String mss(long sec) { return String.format(java.util.Locale.ROOT, "%d:%02d", sec / 60, sec % 60); }

        /** 폴(150ms)마다 — 시간·번갈아 문구·릴. 멈춤(확인 카드·멈춤 바) 동안은 안 불린다 — 릴도 곧 선다(step 이 마지막 tick 뒤 160ms 까지만 앞으로 간다) */
        void tick(long elapsedMs, long waitMs) {
            this.waitMs = Math.max(1, waitMs);
            lastE = elapsedMs; lastAt = android.os.SystemClock.uptimeMillis();
            set(time, held ? mss(elapsedMs / 1000) + " · 곧 만나요" : mss(elapsedMs / 1000) + " · 예상 " + mss(estSec));
            String[] ls = waitMs >= 6000 && elapsedMs >= waitMs / 2 ? linesWide : lines;   /* 반을 넘기면(긴 대기만) «범위 넓히는 중…» */
            set(sub, ls[(int) (elapsedMs / 2500) % ls.length]);
            render();
            root.removeCallbacks(step); root.postDelayed(step, 50);
        }
        private final Runnable step = new Runnable() { @Override public void run() {
            if (landed || !root.isAttachedToWindow()) return;
            render();
            if (android.os.SystemClock.uptimeMillis() - lastAt < 400) root.postDelayed(this, 50);   /* 멈춤이 길면 쉬고 다음 tick 이 다시 깨운다 */
        } };
        /** 지금 칸 — 250ms 마다, 마지막 두 칸은 350 · 450ms(대기 2.5초 미만은 늦추지 않음). 내릴 때(waitMs − 500)엔 마지막 칸에 선 채 land 를 기다린다 */
        private int slot(long e) {
            long L = Math.max(0, waitMs - 500);
            if (e > L) e = L;
            if (waitMs < 2500) return (int) (e / 250);
            long t1 = L - 800, t2 = L - 450;
            int n = (int) Math.max(0, (t1 + 249) / 250);
            if (e >= t2) return n + 1;
            if (e >= t1) return n;
            return (int) Math.max(0, n - 1 - (t1 - 1 - e) / 250);
        }
        private void render() {
            if (landed || held) return;
            long e = lastE + Math.min(160, android.os.SystemClock.uptimeMillis() - lastAt);
            int s = slot(e);
            if (s == shown) return;
            shown = s;
            if (reel.isEmpty()) { spin.set(Avatar.bitmap(RankCore.Rng.mix(seed, s)), STROKE2); return; }   /* 라이벌·잡힌 상대·프로필 아직 — 이름 없이 도는 아바타 */
            ReelItem it = reel.get(s % reel.size());
            spin.set(it.av, STROKE2);
            reelLine.setTextColor(DIM); set(reelLine, it.line);
        }
        /** 훑어본 후보(백그라운드에서 만든 아바타·줄) — 비면 도는 아바타 그대로 */
        void setReel(java.util.List<ReelItem> r) {
            if (r == null || held || landed) return;
            reel = new java.util.ArrayList<>(r);
            shown = Integer.MIN_VALUE; render();
        }
        /** 잡힌 상대 아바타(흐리게) */
        void setHeld(android.graphics.Bitmap av) { if (held && !landed && av != null) spin.set(av, STROKE2); }
        /** 내 상대에게 멈춤 — 밝게 */
        void land(ReelItem it) {
            if (landed || it == null) return;
            landed = true; root.removeCallbacks(step);
            spin.set(it.av, it.color); spin.setDim(0);
            reelLine.setTextColor(it.color); set(reelLine, it.line);
            if (cancel != null) set(cancel, "나가기 (B)");     /* 상대가 보였다 — 이제 B 는 «나가기»(상대는 잡힌 채) */
        }
        void setStage(String s) { set(stage, "스테이지 · " + s); }
        void setTip(String s) { set(tip, "팁 · " + s); }
        /** 낮은 화면 단계 — 0 다 보임 · 1 팁·오늘 줄을 뺀다(compact) · 2 한 줄 알림·번갈아 문구까지 */
        private void setLevel(int lv) {
            int v1 = lv >= 1 ? View.GONE : View.VISIBLE, v2 = lv >= 2 ? View.GONE : View.VISIBLE;
            if (todayRow.getVisibility() != v1) { todayRow.setVisibility(v1); tipRow.setVisibility(v1); sep2.setVisibility(v1); }
            int vn = hasNote ? v2 : View.GONE;
            if (note.getVisibility() != vn) note.setVisibility(vn);
            if (sub.getVisibility() != v2) sub.setVisibility(v2);
        }
        /** 높이 상한(설계 3.2) — 폭 w 로 단계마다 높이를 재어 maxH 안에 드는 첫 단계로. low = 게임 그림이 300dp 보다 낮음(늘 compact부터).
         *  폭·상한이 그대로면 다시 재지 않는다 — 회전·화면 크기를 바꿀 때만 다시 재고, 넓어지면 되돌린다. 마지막 단계도 넘치면 그대로 둔다 */
        void fit(int w, int maxH, boolean low) {
            if (w <= 0 || maxH <= 0 || (w == fitW && maxH == fitMax && low == fitLow)) return;
            fitW = w; fitMax = maxH; fitLow = low;
            int ws = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), hs = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            int lv = low ? 1 : 0;
            for (; lv < 2; lv++) { setLevel(lv); root.measure(ws, hs); if (root.getMeasuredHeight() <= maxH) break; }
            setLevel(lv);
        }
    }
    private static View sep(Context c) {
        View v = new View(c); v.setBackgroundColor(0x66b8862b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.topMargin = dp(c, 6); lp.bottomMargin = dp(c, 6); v.setLayoutParams(lp);
        return v;
    }
    /** LP 막대 — 바탕 위에 f(0~1)만큼 색 */
    static View lpBar(Context c, float f, int color) {
        android.widget.FrameLayout fl = new android.widget.FrameLayout(c);
        fl.setBackground(box(c, 0xff3a3226, 0, 0, 3));
        final View in = new View(c);
        in.setBackground(box(c, color, 0, 0, 3));
        fl.addView(in, new android.widget.FrameLayout.LayoutParams(0, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        final float ff = Math.max(0f, Math.min(1f, f));
        fl.addOnLayoutChangeListener((v, l, tp, rr, b, ol, ot, or, ob) -> {
            int w = Math.round((rr - l) * ff);
            if (in.getLayoutParams().width != w) { in.getLayoutParams().width = w; in.post(in::requestLayout); }
        });
        return fl;
    }
    static Search search(Context c, Typeface f, Wait w, final Runnable onCancel, final Runnable onStageL, final Runnable onStageR, final Runnable onTip) {
        Search s = new Search(w);
        LinearLayout k = card(c);
        k.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        LinearLayout top = new LinearLayout(c); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        s.spin = new PixelView(c); s.spin.setDim(0x70);
        if (w.held && w.heldAv != null) s.spin.set(w.heldAv, STROKE2);
        top.addView(s.spin, new LinearLayout.LayoutParams(dp(c, 56), dp(c, 56)));
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(c, 10), 0, 0, 0);
        s.title = line(c, f, w.held ? "기다리던 상대에게 가는 중…" : "상대 찾는 중…", 17, GOLD);
        col.addView(s.title);
        s.time = line(c, f, w.held ? "0:00 · 곧 만나요" : "0:00 · 예상 " + Search.mss(w.estSec), 13, BODY); s.time.setPadding(0, dp(c, 4), 0, 0);
        col.addView(s.time);
        top.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        k.addView(top);
        s.reelLine = line(c, f, w.held ? "지난번 카드에서 본 상대예요" : "", 12, DIM); s.reelLine.setPadding(0, dp(c, 6), 0, 0);
        s.reelLine.setMinHeight(dp(c, 18));
        k.addView(s.reelLine);
        k.addView(sep(c));

        s.rank = line(c, f, w.rankLine, 14, w.rankColor);
        k.addView(s.rank);
        LinearLayout br = new LinearLayout(c); br.setOrientation(LinearLayout.HORIZONTAL); br.setGravity(Gravity.CENTER_VERTICAL);
        br.setPadding(0, dp(c, 4), 0, 0);
        if (w.barFrac >= 0) {
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(c, 6), 1f); bl.rightMargin = dp(c, 8);
            br.addView(lpBar(c, w.barFrac, w.rankColor), bl);
        }
        s.barLine = line(c, f, w.barLine != null ? w.barLine : "", 11, w.danger ? LOSE : w.barFrac >= 0 ? BODY : DIM);
        br.addView(s.barLine, w.barFrac >= 0 ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                                             : new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (w.barLine == null && w.barFrac < 0) br.setVisibility(View.GONE);
        k.addView(br);
        LinearLayout tr = new LinearLayout(c); tr.setOrientation(LinearLayout.HORIZONTAL); tr.setGravity(Gravity.CENTER_VERTICAL);
        tr.setPadding(0, dp(c, 4), 0, 0);
        s.today = line(c, f, w.todayLine != null ? w.todayLine : "", 12, BODY);
        tr.addView(s.today, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        s.dots = line(c, f, w.dots, 12, GOLD); s.dots.setPadding(dp(c, 6), 0, 0, 0);
        tr.addView(s.dots);
        s.todayRow = tr; k.addView(tr);
        s.note = line(c, f, w.noteLine != null ? w.noteLine : "", 12, GOLD); s.note.setPadding(0, dp(c, 4), 0, 0);
        s.hasNote = w.noteLine != null;
        if (!s.hasNote) s.note.setVisibility(View.GONE);
        k.addView(s.note);
        s.sep2 = sep(c); k.addView(s.sep2);

        LinearLayout tp = new LinearLayout(c); tp.setOrientation(LinearLayout.HORIZONTAL); tp.setGravity(Gravity.CENTER_VERTICAL);
        s.tip = tv(c, f, "팁 · " + (w.tip != null ? w.tip : ""), 11, BODY); s.tip.setMaxLines(2); s.tip.setLineSpacing(0, 1.1f);
        s.tip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tp.addView(s.tip, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView ud = tv(c, f, " ▲▼", 12, GOLD); tp.addView(ud);
        tp.setClickable(true); tp.setOnClickListener(x -> onTip.run());
        tp.setMinimumHeight(dp(c, 32));
        s.tipRow = tp; k.addView(tp);

        LinearLayout sr = new LinearLayout(c); sr.setOrientation(LinearLayout.HORIZONTAL); sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setPadding(0, dp(c, 2), 0, dp(c, 6));
        TextView l = tv(c, f, " ◀ ", 15, GOLD); l.setMinHeight(dp(c, 36)); l.setGravity(Gravity.CENTER); l.setOnClickListener(x -> onStageL.run());
        TextView r = tv(c, f, " ▶ ", 15, GOLD); r.setMinHeight(dp(c, 36)); r.setGravity(Gravity.CENTER); r.setOnClickListener(x -> onStageR.run());
        s.stage = line(c, f, "스테이지 · " + w.stageLabel, 12, BODY); s.stage.setGravity(Gravity.CENTER);
        sr.addView(l); sr.addView(s.stage, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); sr.addView(r);
        k.addView(sr);

        s.cancel = pill(c, f, "취소 (B)", false, onCancel); k.addView(s.cancel);
        s.sub = line(c, f, s.lines[0], 11, DIM); s.sub.setGravity(Gravity.CENTER); s.sub.setPadding(0, dp(c, 6), 0, 0);
        k.addView(s.sub);
        s.root = k;
        if (w.compact) s.setLevel(1);
        return s;
    }

    /* ── 상대 발견! ── */
    static final class Found { LinearLayout root; TextView stage, go; }
    /**
     * pf = 꾸밈 프로필 · v = 지금 모습(티어·LP) · h2h = {내 승, 내 패, 무, 마지막, 끊은 연승} · stakes = {이기면, 지면}(배치·무쌍이면 null)
     * · waitedLine = «기다린 시간 0:07»(흐리게, 없으면 null)
     */
    static Found found(Context c, Typeface f, JSONObject pf, League.OppView v, java.util.List<String> tags, int[] h2h, int[] stakes,
                       String placement, String stageText, Runnable onGo, Runnable onLeave, Runnable onStageL, Runnable onStageR, String waitedLine) {
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
        if (waitedLine != null) { TextView wl = line(c, f, waitedLine, 11, DIM); wl.setPadding(0, dp(c, 3), 0, 0); k.addView(wl); }

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
