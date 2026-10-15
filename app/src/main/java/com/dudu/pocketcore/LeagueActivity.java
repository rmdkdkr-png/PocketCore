package com.dudu.pocketcore;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 가상 리그 화면(사무쇼2) — 랭킹 · 상대 상세 · 경기 결과 · 진행 중 경기(기획서 2장 표).
 * 상대는 게임의 CPU 이고, 온라인 사람처럼 보이게 하지 않는다(화면에 그렇게 적는다).
 * 2단계: 사용자 1명 + 가상 상대 12명 · 순위표 · 상대전적 · 재대결. 실제 경기는 EmuActivity 의 랭크전 모드에서 한다.
 */
public class LeagueActivity extends Activity {
    static final String EXTRA_ROM = "rom", EXTRA_RESULT = "league_result";

    private static final int BG = 0xff101218, CARD = 0xff191b22, LINE = 0xff2a2f3b, TXT = 0xffe6e8ee,
                             DIM = 0xff8b93a6, GOLD = 0xffd9a441, INK = 0xff101014, BTN2 = 0xff23262f,
                             WIN = 0xff6fcf8f, LOSE = 0xffe5737a;
    private String rom;
    private LinearLayout col;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        rom = getIntent().getStringExtra(EXTRA_ROM);
        League.markInterrupted();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(28));
        sv.addView(col);
        setContentView(sv);
        render();
        checkRom();
        String res = getIntent().getStringExtra(EXTRA_RESULT);
        if (res != null && b == null) { getIntent().removeExtra(EXTRA_RESULT); showResultWhenChecked(res); }   /* 화면을 돌려 다시 만들 때 또 뜨지 않게 */
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (i.getStringExtra(EXTRA_ROM) != null) rom = i.getStringExtra(EXTRA_ROM);
        render();
        checkRom();
        String res = i.getStringExtra(EXTRA_RESULT);
        if (res != null) { i.removeExtra(EXTRA_RESULT); showResultWhenChecked(res); }
    }

    @Override public void onBackPressed() {
        Intent i = new Intent(this, Home.class);         /* MainActivity 는 앱 아이콘 별칭이라 아이콘을 바꾸면 꺼진다 — 늘 Home 으로 */
        i.putExtra("menu", true);
        startActivity(i);
        finish();
    }

    /* ── 롬 확인 ──────────────────────────────────────────────────────── */

    /** 랭크전 롬(원본 + 번역 패치 사본)이 판정 주소를 실측한 판인지 미리 본다 — 아니면 도전 버튼을 끄고 이유를 보인다
     *  (기획서 3장 «실제로 호출 가능한 상대만 도전 버튼»). 실제 경기 시작 때 EmuActivity 가 한 번 더 본다. 롬을 읽고 md5 를 내니 다른 스레드에서. */
    private volatile String romWhy = null;
    private volatile String romChecked = null;

    private void checkRom() {
        final String r = rom;
        if (r == null || r.equals(romChecked)) return;
        new Thread(() -> {
            String why = null;
            try {
                Games.Game g = Games.identify(r);
                java.util.Map<String, String> m = Settings.load();
                String lang = (g != null) ? m.get("pocketcore_lang_" + g.id) : null;   /* EmuActivity.readLang 과 같게 */
                if (lang == null) lang = m.get("pocketcore_lang");
                boolean known = false;
                if (lang != null) for (String k : Games.LANGS) if (k.equals(lang)) known = true;
                if (!known) lang = "ko";
                if (!new File(r).exists()) why = "롬을 찾을 수 없습니다 — 원래 자리에 롬을 두세요";
                else {
                    String md5 = League.md5(new File(Patcher.resolveLeague(this, r, g, lang)));
                    if (League.romLabel(md5) == null)
                        why = "판정 주소를 확인하지 않은 롬입니다(md5 " + md5 + ") — 순정 롬이나 한글패치 v1.01·앱 동봉 한패로 바꾸세요";
                }
            } catch (Exception e) { why = null; }   /* 확인 자체가 실패하면 막지 않는다 — 시작 때 다시 본다 */
            final String w = why;
            runOnUiThread(() -> {
                romWhy = w; romChecked = r;
                if (isFinishing()) return;
                render();
                if (pendingResult != null) { String p = pendingResult; pendingResult = null; showResult(p); }
            });
        }, "league-romcheck").start();
    }

    /** 도전할 수 없는 이유(없으면 null) — 상대·열린 경기·기록 파일(League) + 롬(확인 중이면 그것도 이유). */
    private String challengeWhy(JSONObject o) {
        String why = League.blockedWhy(o);
        if (why == null && rom == null) why = "롬 경로가 없습니다 — 런처에서 사무쇼2 카드를 눌러 「리그 순위표」로 들어오세요";
        if (why == null && !rom.equals(romChecked)) why = "롬 확인 중입니다 — 잠시 뒤 다시 눌러 주세요";
        if (why == null && romWhy != null) why = romWhy;
        return why;
    }

    /** 결과 창은 롬 확인이 끝난 뒤에 띄운다 — 그래야 재대결·다음 버튼이 롬 확인 결과를 따른다. */
    private String pendingResult = null;
    private void showResultWhenChecked(String id) {
        if (rom == null || rom.equals(romChecked)) showResult(id); else pendingResult = id;
    }

    /* ── 랭크 매치 화면(내부 144) — 내 카드 · 하러 가기 · [순위표 | 최근 경기 | 라이벌 | 규칙] · 보류·무효 ──
       상대는 씨앗 하나로 만드는 가상 플레이어(게임 CPU) — 숫자(티어·LP·전적)는 진짜 경기로만 움직이고 닉네임·칭호·사진은 지어낸 것이라고 화면에 적는다. */
    private int tab = 0;                         /* 0 순위표 · 1 최근 경기 · 2 라이벌 · 3 규칙 */
    /** 순위표·내 순위는 다른 스레드에서 만든다(위 100명 + 프로필 + 아바타 — 에뮬레이터에서 화면이 수십 초 멈췄다). 같은 날·같은 내 LP·같은 탭이면 다시 안 만든다 */
    private static final class Board { String key; long rank; List<RankCore.Row> rows; }
    private volatile Board board;
    private volatile String boardBusy;
    private String boardKey(RankCore.Me me) { return League.rankSeed() + ":" + League.today() + ":" + League.myKey(me) + ":" + boardAround + ":" + me.games; }
    private void boardLoad(final RankCore.Me me) {
        final String key = boardKey(me);
        if (key.equals(boardBusy)) return;
        boardBusy = key;
        final boolean ar = boardAround;
        new Thread(() -> {
            Board b = new Board(); b.key = key;
            try {
                long seed = League.rankSeed(); int day = League.today(); RankCore.Book bk = League.book();
                b.rank = RankCore.rankOf(seed, day, bk, League.myKey(me));
                b.rows = ar ? RankCore.around(seed, day, bk, League.myKey(me), "나", 5) : RankCore.top(seed, day, 100, bk, League.myKey(me), "나");
                for (RankCore.Row r : b.rows) if (!r.me && r.p != null) Avatar.of(League.pfOf(this, r.p), r.id);   /* 프로필·아바타도 미리 */
            } catch (Exception e) { b.rows = new ArrayList<>(); }
            runOnUiThread(() -> { if (key.equals(boardBusy)) boardBusy = null; board = b; if (!isFinishing()) render(); });
        }, "league-board").start();
    }
    private Board boardFor(RankCore.Me me) { Board b = board; return b != null && b.key.equals(boardKey(me)) ? b : null; }
    private boolean boardAround = false;         /* 순위표: 위 100 / 내 주변 */
    private static final String[] TABS = { "순위표", "최근 경기", "라이벌", "규칙" };

    private void render() {
        col.removeAllViews();
        col.addView(text("랭크 매치", 24, TXT, true));
        TextView sub = text("사무라이 쇼다운! 2 · 리그 대전 — 상대는 모두 게임 CPU 예요. 닉네임·칭호·사진은 지어낸 것이고, 티어·LP·전적은 실제 경기로만 움직여요.", 12, DIM, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        col.addView(sub);

        final JSONObject open = League.openMatch();
        if (open != null) col.addView(openCard(open));
        RankCore.Me me = League.rankMe();
        col.addView(myCard(me));
        if (open == null && rom != null) {                 /* 게임을 리그 판으로 켜서 로고·메뉴를 넘겨 바로 상대 찾기까지 */
            TextView go = pill("랭크 매치 하러 가기  ›", GOLD, INK);
            go.setOnClickListener(v -> {
                String why = League.dbProblem != null ? League.dbProblem : romWhy != null ? romWhy
                        : !rom.equals(romChecked) ? "롬 확인 중입니다 — 잠시 뒤 다시 눌러 주세요" : null;
                if (why != null) { toast(why); return; }
                if (launching) return;
                launching = true;
                Intent i = new Intent(this, EmuActivity.class);
                i.putExtra("rom", rom);
                i.putExtra(EmuActivity.EXTRA_LEAGUE_HUB, true);
                i.putExtra(EmuActivity.EXTRA_HUB_GO, true);
                startActivity(i);
                finish();
            });
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            glp.topMargin = dp(10); glp.bottomMargin = dp(4);
            col.addView(go, glp);
        }

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(14), 0, dp(8));
        for (int i = 0; i < TABS.length; i++) {
            final int k = i;
            TextView t = pill(TABS[i], i == tab ? GOLD : BTN2, i == tab ? INK : TXT);
            t.setTextSize(13);
            t.setOnClickListener(v -> { tab = k; render(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = i < TABS.length - 1 ? dp(6) : 0;
            tabs.addView(t, lp);
        }
        col.addView(tabs);
        if (tab == 0) boardTab(me);
        else if (tab == 1) recentTab();
        else if (tab == 2) rivalsTab();
        else rulesTab();

        List<JSONObject> hs = League.holds(5);
        if (!hs.isEmpty()) {
            TextView hh = text("보류·무효 경기 — LP·점수에 넣지 않았습니다", 13, GOLD, true);
            hh.setPadding(0, dp(16), 0, dp(4));
            col.addView(hh);
            LinearLayout hc = card();
            for (int i = 0; i < hs.size(); i++) {
                final JSONObject m = hs.get(i);
                if (i > 0) hc.addView(line());
                TextView t = text(League.statusKo(m.optString("status")) + " · " + League.oppNick(m) + "\n" + m.optString("why"), 12, DIM, false);
                t.setPadding(0, dp(6), 0, dp(6));
                t.setClickable(true);
                t.setOnClickListener(v -> showResult(m.optString("id")));
                hc.addView(t);
            }
            col.addView(hc);
        }
        if (League.dbProblem != null) {
            TextView w = text(League.dbProblem, 12, LOSE, false);
            w.setPadding(0, dp(10), 0, 0);
            col.addView(w);
        } else if (rom == null || romWhy != null) {
            TextView w = text(rom == null ? "롬 경로가 없어 경기를 시작할 수 없습니다 — 런처에서 사무쇼2 카드를 눌러 「리그 순위표」로 들어오세요."
                                          : "경기를 시작할 수 없습니다: " + romWhy, 12, LOSE, false);
            w.setPadding(0, dp(10), 0, 0);
            col.addView(w);
        }
    }

    /** 내 카드 — 티어 배지 · LP 막대 · 전국 순위 · 시즌 · 통산 */
    private View myCard(RankCore.Me me) {
        LinearLayout c = card();
        int tc = RankUi.tierColor(me.lp, me.master);
        String big = me.placementLeft > 0 ? "배치 경기 " + (5 - me.placementLeft) + "/5" : me.master ? "무쌍 · MR " + me.mr : RankCore.label(me.lp);
        TextView t = text(big, 22, me.placementLeft > 0 ? GOLD : tc, true);
        c.addView(t);
        if (me.placementLeft > 0) c.addView(text("5판을 마치면 첫 티어가 정해져요 — 지금 " + me.w + "승 " + me.l + "패", 13, TXT, false));
        else if (!me.master) {
            int tier = RankCore.tierOf(me.lp), star = RankCore.starOf(me.lp);
            int lo = RankCore.FLOOR[tier] + (star - 1) * RankCore.STEP[tier], hi = star >= 5 ? RankCore.FLOOR[tier + 1] : lo + RankCore.STEP[tier];
            c.addView(text(me.lp + " LP · " + (star >= 5 ? "승급까지 " : "다음 ★까지 ") + Math.max(0, hi - me.lp) + (me.shield ? " · 강등 위기!" : ""), 13, me.shield ? LOSE : TXT, false));
            c.addView(bar(Math.max(0f, Math.min(1f, (me.lp - lo) / (float) Math.max(1, hi - lo))), tc));
        }
        Board bd = boardFor(me);
        if (bd == null && me.placementLeft == 0) boardLoad(me);
        String rk = me.placementLeft > 0 ? "배치를 마치면 순위가 나와요" : bd == null ? "전국 순위 계산 중…"
                  : String.format(java.util.Locale.KOREA, "전국 %,d위 / %,d명", bd.rank, RankCore.total());
        TextView r = text(rk + " · 시즌 1", 13, GOLD, false); r.setPadding(0, dp(6), 0, 0);
        c.addView(r);
        String s = "통산 " + me.w + "승 " + me.l + "패" + (me.d > 0 ? " " + me.d + "무" : "") + " · 연승 " + me.streak + " (최장 " + me.best + ")"
                 + (me.peakLp > 0 && !me.master ? " · 최고 " + RankCore.label(me.peakLp) : "");
        c.addView(text(s, 12, DIM, false));
        return c;
    }
    private View bar(float f, int color) {
        android.widget.FrameLayout fl = new android.widget.FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(LINE); bg.setCornerRadius(dp(3));
        fl.setBackground(bg);
        View in = new View(this);
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(3));
        in.setBackground(g);
        fl.addView(in, new android.widget.FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        fl.addOnLayoutChangeListener((v, l, tp, rr, b, ol, ot, or, ob) -> {
            int w = Math.round((rr - l) * f);
            if (in.getLayoutParams().width != w) { in.getLayoutParams().width = w; in.post(in::requestLayout); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        lp.topMargin = dp(6);
        fl.setLayoutParams(lp);
        return fl;
    }

    private void boardTab(RankCore.Me me) {
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(0, 0, 0, dp(6));
        for (int i = 0; i < 2; i++) {
            final boolean ar = i == 1;
            TextView t = text(ar ? "내 주변" : "위 100", 13, ar == boardAround ? GOLD : DIM, true);
            t.setPadding(dp(4), dp(6), dp(14), dp(6));
            t.setClickable(true);
            t.setOnClickListener(v -> { boardAround = ar; render(); });
            seg.addView(t);
        }
        long seed = League.rankSeed(); int day = League.today();
        TextView act = text(String.format(java.util.Locale.KOREA, "오늘 %,d명 · 한국 시각 자정마다 바뀌어요", RankCore.activeToday(seed, day)), 11, DIM, false);
        seg.addView(act);
        col.addView(seg);
        if (boardAround && me.placementLeft > 0) {          /* 배치 중엔 내 자리가 없다(내 카드와 같게) — LP 0 수련생 사이에 세우지 않는다 */
            col.addView(text("배치 경기 " + (5 - me.placementLeft) + "/5 — 배치를 마치면 내 주변 순위가 나와요", 13, DIM, false));
            return;
        }
        Board bd = boardFor(me);
        if (bd == null) { boardLoad(me); col.addView(text("순위표를 불러오는 중…", 13, DIM, false)); return; }
        List<RankCore.Row> rows = bd.rows;
        LinearLayout card = card();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) card.addView(line());
            card.addView(boardRow(rows.get(i), me));
        }
        col.addView(card);
    }
    private View boardRow(final RankCore.Row r, RankCore.Me me) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView n = text(r.rank > 9999 ? String.format(java.util.Locale.KOREA, "%,d", r.rank) : String.valueOf(r.rank), r.rank > 999 ? 11 : 15, r.me ? GOLD : DIM, true);
        n.setGravity(Gravity.CENTER);
        row.addView(n, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        JSONObject pf = r.me ? null : League.pfOf(this, r.p);
        if (!r.me) {
            PixelView av = new PixelView(this); av.set(Avatar.of(pf, r.id), RankUi.tierColor(r.lp, r.lp >= RankCore.MASTER_LP));
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(36), dp(36)); al.rightMargin = dp(8);
            row.addView(av, al);
        } else {
            View sp = new View(this); row.addView(sp, new LinearLayout.LayoutParams(dp(44), dp(36)));
        }
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        String nick = r.me ? "나" : pf.optString("nick", r.nick);
        TextView nt = text(nick + (r.me ? "  (나)" : r.p != null && r.p.legacy ? "  · 라이벌" : ""), 15, TXT, true);
        nt.setSingleLine(true); nt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tt.addView(nt);
        String sub = r.me ? League.myRankLabel(me) : r.label + " · " + League.CHAR[r.ch] + " " + League.STYLE[r.sty] + " · 승률 " + Math.round(r.wr * 100) + "%";
        TextView st = text(sub, 11, r.me ? GOLD : DIM, false);
        st.setSingleLine(true); st.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tt.addView(st);
        row.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean master = r.lp >= RankCore.MASTER_LP;
        row.addView(text(master ? "MR " + r.mr : r.lp + " LP", 13, r.me ? GOLD : RankUi.tierColor(r.lp, master), true));
        if (!r.me && r.p != null) {
            row.setClickable(true);
            row.setOnClickListener(v -> profile(r.p));
        }
        return row;
    }

    /** 상대 프로필 — 지역·칭호·성향·판수·나와의 전적·최근 맞대결 */
    private void profile(RankCore.Player p) {
        JSONObject pf = League.pfOf(this, p);
        League.OppView v = League.oppView(p);
        int[] h = League.h2h(p.id);
        StringBuilder sb = new StringBuilder();
        sb.append(v.label).append(v.master ? "" : " · " + v.lp + " LP")
          .append("\n").append(ProfileGen.regionLabel(pf)).append(pf.optString("title").isEmpty() ? "" : " · «" + pf.optString("title") + "»")
          .append("\n주캐 ").append(League.CHAR[p.ch]).append(" ").append(League.STYLE[p.sty]).append(" · ").append(p.persona);
        JSONArray tg = pf.optJSONArray("tags");
        if (tg != null && tg.length() > 0) { sb.append(" · "); for (int i = 0; i < tg.length(); i++) sb.append(i > 0 ? ", " : "").append(tg.optString(i)); }
        if (!p.legacy) sb.append("\n").append(p.games(League.today(), League.today() - 30)).append("판 · 승률 ").append(Math.round(p.wr0 * 100)).append("%");
        String cmt = pf.optString("cmt");
        if (!cmt.isEmpty()) sb.append("\n“").append(cmt).append("”");
        sb.append("\n\n나와의 전적 ").append(h[0]).append("승 ").append(h[1]).append("패").append(h[2] > 0 ? " " + h[2] + "무" : "");
        if (h[4] > 0) sb.append(" · 내 ").append(h[4]).append("연승을 끊은 상대");
        int k = 0;
        for (JSONObject m : League.rankedHistory(60)) {
            if (!p.id.equals(m.optString("opp"))) continue;
            if (k++ == 0) sb.append("\n최근 맞대결");
            sb.append("\n · ").append(League.rankedResultLine(m));
            if (k >= 5) break;
        }
        sb.append("\n\n상대는 게임 CPU 예요 — 랭크 매치에서 비슷한 실력이면 만나요.");
        android.widget.LinearLayout head = new android.widget.LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(16), dp(20), 0);
        PixelView av = new PixelView(this); av.set(Avatar.of(pf, p.id), RankUi.tierColor(v.lp, v.master));
        head.addView(av, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView nt = text(pf.optString("nick", p.nick), 20, TXT, true); nt.setPadding(dp(12), 0, 0, 0);
        head.addView(nt);
        new AlertDialog.Builder(this).setCustomTitle(head).setMessage(sb.toString()).setNegativeButton("닫기", null).show();
    }

    private void recentTab() {
        List<JSONObject> hs = League.rankedHistory(20);
        if (hs.isEmpty()) { col.addView(text("아직 랭크 경기가 없어요 — «랭크 매치 하러 가기»를 눌러 보세요.", 13, DIM, false)); return; }
        LinearLayout card = card();
        for (int i = 0; i < hs.size(); i++) {
            final JSONObject m = hs.get(i);
            if (i > 0) card.addView(line());
            JSONObject s = m.optJSONObject("opp_snap"); if (s == null) s = new JSONObject();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            JSONObject pf = new JSONObject(); try { pf.put("av", s.optString("av")); } catch (Exception ignored) { }
            PixelView av = new PixelView(this); av.set(Avatar.of(pf, m.optString("opp")), 0);
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(32), dp(32)); al.rightMargin = dp(8);
            row.addView(av, al);
            LinearLayout tt = new LinearLayout(this); tt.setOrientation(LinearLayout.VERTICAL);
            tt.addView(text(s.optString("nick", "?") + " · " + s.optString("label"), 14, TXT, true));
            String ev = m.optString("event", "");
            tt.addView(text(League.CHAR[Math.max(0, Math.min(14, s.optInt("ch")))] + " " + League.STYLE[s.optInt("sty") & 1] + (ev.isEmpty() ? "" : " · " + ev), 11, DIM, false));
            row.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String out = m.optString("outcome");
            row.addView(text(League.rankedResultLine(m), 13, "W".equals(out) ? WIN : "L".equals(out) ? LOSE : TXT, true));
            row.setClickable(true);
            row.setOnClickListener(v -> showResult(m.optString("id")));
            card.addView(row);
        }
        col.addView(card);
    }

    private void rivalsTab() {
        List<RankCore.Player> list = new ArrayList<>(League.rivals());
        JSONObject ps = League.ranked().optJSONObject("players");
        if (ps != null) {
            java.util.Iterator<String> it = ps.keys();
            while (it.hasNext()) {
                String id = it.next();
                if (!id.startsWith("p")) continue;
                JSONObject o = ps.optJSONObject(id);
                if (o.optInt("w") + o.optInt("l") + o.optInt("d") < 3) continue;   /* 여러 번 붙은 사람만 */
                RankCore.Player p = League.rankPlayer(id);
                if (p != null) list.add(p);
            }
        }
        TextView hint = text("예전 리그 12명과, 3판 이상 붙은 상대예요. 누르면 프로필.", 12, DIM, false);
        hint.setPadding(0, 0, 0, dp(6));
        col.addView(hint);
        LinearLayout card = card();
        for (int i = 0; i < list.size(); i++) {
            final RankCore.Player p = list.get(i);
            if (i > 0) card.addView(line());
            JSONObject pf = League.pfOf(this, p);
            League.OppView v = League.oppView(p);
            int[] h = League.h2h(p.id);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            PixelView av = new PixelView(this); av.set(Avatar.of(pf, p.id), RankUi.tierColor(v.lp, v.master));
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(36), dp(36)); al.rightMargin = dp(8);
            row.addView(av, al);
            LinearLayout tt = new LinearLayout(this); tt.setOrientation(LinearLayout.VERTICAL);
            tt.addView(text(pf.optString("nick", p.nick) + (p.legacy ? "  · 라이벌" : ""), 15, TXT, true));
            tt.addView(text(v.label + " · " + League.CHAR[p.ch] + " " + League.STYLE[p.sty], 11, DIM, false));
            row.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(text(h[0] + h[1] + h[2] == 0 ? "아직 안 만남" : h[0] + "승 " + h[1] + "패", 13, h[0] > h[1] ? WIN : h[1] > h[0] ? LOSE : TXT, true));
            row.setClickable(true);
            row.setOnClickListener(x -> profile(p));
            card.addView(row);
        }
        col.addView(card);
    }

    private void rulesTab() {
        JSONObject lg = League.ranked().optJSONObject("legacy");
        String s = "경기 · " + League.POINTS + "선승(3판 2승) · 라운드 " + League.TIME + "초 · 카드 전원 올카드 해금(CPU 는 카드 없음). "
                + "타이틀 셋째 줄 «리그 대전»에서 A 를 누르면 비슷한 실력의 상대를 찾고, 상대가 정해지면 내 캐릭터·검질·카드를 골라 바로 싸워요. "
                + "랭크 경기 중에는 불러오기·되돌리기·리셋을 쓸 수 없고, 리그 세이브는 원작 세이브와 따로예요.\n\n"
                + "상대 · 씨앗 하나로 만든 가상 플레이어 약 4만 명(모두 게임 CPU)과 예전 리그 12명(라이벌). "
                + "실력(숨은 MMR)에 따라 CPU 단계·공격·방어 배율이 바뀌어요 — 같은 티어면 비슷하게 세요.\n\n"
                + "티어 · 수련생 · 낭인 · 무사 · 검객 · 검호 · 달인 · 검성(각 ★1~5) · 무쌍(MR). 처음 5판은 배치 경기 — 끝나면 첫 티어가 정해져요. "
                + "이기면 LP 가 오르고(센 상대일수록 더), 지면 내려가요. 수련생은 안 깎이고 낭인은 반만. "
                + "3연승부터 연승 보너스, 진 상대를 재대결로 이기면 설욕 +20, 오늘 첫 승 +30. "
                + "무사 이상은 바닥 아래로 떨어질 때 한 번 «강등 위기»로 버텨요. 무쌍은 강등이 없어요.\n\n"
                + "매칭 · 상대 카드가 뜨면 그 상대와 붙을 때까지 다음 찾기에도 같은 상대가 나와요(카드에서 나가도). "
                + "재대결은 같은 상대와 연속 3판까지.";
        if (lg != null && lg.optInt("games") > 0)
            s += "\n\n옛 리그 기록 · " + lg.optInt("w") + "승 " + lg.optInt("l") + "패" + (lg.optInt("d") > 0 ? " " + lg.optInt("d") + "무" : "")
               + " · 최종 " + lg.optInt("score") + "점 — 이 기록으로 시작 실력을 정했어요.";
        TextView t = text(s, 12, DIM, false);
        col.addView(t);
    }

    private boolean launching = false;          /* 두 번 눌러 게임 창이 둘 뜨지 않게 */

    private void launch(String matchId, boolean resume) {
        if (launching) return;
        if (rom == null || !new File(rom).exists()) { toast("롬을 찾을 수 없습니다 — 원래 자리에 롬을 두면 재개할 수 있습니다"); return; }
        if (!resume && (romWhy != null || !rom.equals(romChecked))) {   /* 준비 경기를 새로 여는 것은 도전과 같다 — 롬 확인을 따른다 */
            toast(romWhy != null ? romWhy : "롬 확인 중입니다 — 잠시 뒤 다시 눌러 주세요"); return;
        }
        launching = true;
        Intent i = new Intent(this, EmuActivity.class);
        i.putExtra("rom", rom);
        i.putExtra(EmuActivity.EXTRA_LEAGUE, matchId);
        i.putExtra(EmuActivity.EXTRA_LEAGUE_RESUME, resume);
        startActivity(i);
        finish();
    }

    /* ── 진행 중 경기 ──────────────────────────────────────────────────── */

    private View openCard(final JSONObject m) {
        LinearLayout c = card();
        JSONObject o = League.oppById(m.optString("opp"));
        final String st = m.optString("status");
        final boolean hasCk = League.ckpt(m.optString("id")).exists();
        final boolean ready = "ready".equals(st);
        c.addView(text("진행 중 경기 · " + League.statusKo(st), 13, GOLD, true));
        String note = ready ? "첫 라운드 전에 멈춘 경기입니다 — 다시 시작하거나, 무효로 닫으면 점수는 그대로입니다."
                : hasCk ? "앱이 내려간 그 자리에서 한 번 이어할 수 있습니다."
                : "이어할 자리가 없습니다(체크포인트 없음) — 기권만 할 수 있습니다.";
        if (!ready && m.has("why") && !m.optString("why").isEmpty()) note += "\n(" + m.optString("why") + ")";
        if (!ready && m.has("fix")) note += "\n복구: " + m.optString("fix");
        c.addView(text((League.isRanked(m) ? "랭크 · " + League.oppLabel(m) : o != null ? o.optString("nick") + " · " + League.oppLine(o) : "?") + "\n" + note, 13, TXT, false));
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView ff = pill(ready ? "무효로 닫기" : "기권", BTN2, LOSE);
        ff.setOnClickListener(v -> {
            if (ready) {
                if (!League.cancel(m.optString("id"), "대진 전에 멈춤 — 사용자가 무효로 닫음")) toast("저장 실패 — 다시 시도하세요");
                render();
                return;
            }
            new AlertDialog.Builder(this).setTitle("기권")
                .setMessage("이 경기를 기권패로 기록합니다.")
                .setPositiveButton("기권", (x, y) -> {
                    JSONObject r = League.forfeit(m.optString("id"));
                    if (r == null) toast("저장 실패 — 다시 시도하세요"); else { render(); showResult(m.optString("id")); }
                })
                .setNegativeButton("취소", null).show();
        });
        btns.addView(ff, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if ((ready || hasCk) && rom != null) {               /* 준비면 새 판으로 다시 시작, 진행 중이면 체크포인트로 재개 */
            TextView go = pill(ready ? "다시 시작" : "재개", GOLD, INK);
            go.setOnClickListener(v -> launch(m.optString("id"), !ready));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
            lp.leftMargin = dp(10);
            btns.addView(go, lp);
        }
        btns.setPadding(0, dp(10), 0, 0);
        c.addView(btns);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    /* ── 경기 결과 ────────────────────────────────────────────────────── */

    private void showResult(String matchId) {
        JSONObject m = League.matchById(matchId);
        if (m == null) return;
        if (League.isRanked(m)) { rankedResult(m); return; }
        JSONObject o = League.oppById(m.optString("opp"));
        String st = m.optString("status");
        StringBuilder sb = new StringBuilder();
        String title;
        if ("hold".equals(st) || "cancelled".equals(st)) {
            title = ("hold".equals(st) ? "보류" : "무효") + " — 점수에 넣지 않았습니다";
            sb.append(m.optString("why"));
            if (m.has("fix")) sb.append("\n\n복구: ").append(m.optString("fix"));
        } else {
            title = "W".equals(m.optString("outcome")) ? "승리" : "L".equals(m.optString("outcome")) ? (m.optBoolean("forfeit") ? "기권패" : "패배") : "무승부";
            JSONObject r = m.optJSONObject("result");
            if (r != null) sb.append("최종 ").append(r.optInt("w1")).append(" : ").append(r.optInt("w2"))
                    .append("  (").append(League.CHAR[r.optInt("my_char") % 15]).append(" ").append(League.STYLE[r.optInt("my_style") & 1]).append(" vs ")
                    .append(o != null ? League.CHAR[o.optInt("char")] + " " + League.STYLE[o.optInt("style")] : "?").append(")\n");
            JSONObject sb0 = m.optJSONObject("score_before");
            int d = m.optInt("delta");
            if (sb0 != null) {
                int rankBefore = m.optInt("rank_before"), rankAfter = m.optInt("rank_after");
                sb.append("점수 ").append(sb0.optInt("me")).append(" → ").append(sb0.optInt("me") + d)
                  .append(" (").append(d > 0 ? "+" + d : String.valueOf(d)).append(")")
                  .append("\n순위 ").append(rankBefore).append("위 → ").append(rankAfter).append("위");
            }
            JSONObject af = m.optJSONObject("after");                   /* 그 경기 직후 값(없으면 지금 값 — 1단계 기록) */
            int hw = af != null ? af.optInt("h2h_w") : (o != null ? o.optInt("l") : 0);
            int hl = af != null ? af.optInt("h2h_l") : (o != null ? o.optInt("w") : 0);
            int hd = af != null ? af.optInt("h2h_d") : (o != null ? o.optInt("d") : 0);
            if (o != null) sb.append("\n").append(League.gwa(o.optString("nick"))).append("의 전적 ")
                    .append(hw).append("승 ").append(hl).append("패").append(hd > 0 ? " " + hd + "무" : "");
            if (m.optBoolean("first_win")) sb.append("\n이 상대에게 첫 승!");
            int stk = af != null ? af.optInt("streak") : 0, best = af != null ? af.optInt("best") : 0;   /* 직후 값이 없는 1단계 경기는 연승을 안 보인다 */
            if (stk > 0) sb.append("\n").append(stk).append("연승 (최장 ").append(best).append(")");
            if (m.optInt("broke_streak") >= 2) sb.append("\n").append(m.optInt("broke_streak")).append("연승이 끊겼습니다");
        }
        new AlertDialog.Builder(this).setTitle(title).setMessage(sb.toString()).setNegativeButton("닫기", null).show();   /* 예전 리그 경기 — 다시 도전은 게임 속 랭크 매치에서 */
    }

    /** 랭크 경기 결과(그 경기 직후 값) — 티어·LP 변화·보너스·사건·상대 전적 */
    private void rankedResult(JSONObject m) {
        String st = m.optString("status"), title;
        StringBuilder sb = new StringBuilder();
        if ("hold".equals(st) || "cancelled".equals(st)) {
            title = ("hold".equals(st) ? "보류" : "무효") + " — LP 에 넣지 않았습니다";
            sb.append(League.oppLabel(m)).append("\n\n").append(m.optString("why"));
            if (m.has("fix")) sb.append("\n\n복구: ").append(m.optString("fix"));
        } else {
            String out = m.optString("outcome");
            title = m.optBoolean("forfeit") ? "기권패" : "W".equals(out) ? "승리" : "L".equals(out) ? "패배" : "무승부";
            JSONObject r = m.optJSONObject("result");
            sb.append("vs ").append(League.oppLabel(m));
            JSONObject snap = m.optJSONObject("opp_snap");
            if (snap != null) sb.append(" · ").append(snap.optString("label"));
            if (r != null && !m.optBoolean("forfeit")) sb.append("\n최종 ").append(r.optInt("w1")).append(" : ").append(r.optInt("w2"))
                    .append(" (내 ").append(League.CHAR[r.optInt("my_char") % 15]).append(" ").append(League.STYLE[r.optInt("my_style") & 1]).append(")");
            String tb = m.optString("tier_before"), ta = m.optString("tier_after");
            if ("배치".equals(tb)) sb.append("\n배치 경기").append(m.optInt("placement_left") == 0 ? " 완료 → " + ta : " (남은 " + m.optInt("placement_left") + "판)");
            else if (m.optJSONObject("start") != null && m.optJSONObject("start").optBoolean("master"))   /* 시작 때 무쌍이었나 — EmuActivity.rankResult 와 같게 */
                sb.append("\n무쌍 MR ").append(m.optInt("mr_before")).append(" → ").append(m.optInt("mr_after"))
                  .append(" (").append(RankUi.signed(m.optInt("mr_after") - m.optInt("mr_before"))).append(")");
            else sb.append("\n").append(tb.equals(ta) ? ta : tb + " → " + ta).append(" · ").append(m.optInt("lp_before")).append(" → ").append(m.optInt("lp_after"))
                    .append(" LP (").append(RankUi.signed(m.optInt("lp_delta"))).append(")");
            JSONObject b = m.optJSONObject("bonus");
            if (b != null && b.optInt("streak") + b.optInt("revenge") + b.optInt("daily") > 0)
                sb.append("\n보너스 ").append(b.optInt("streak") > 0 ? "연승 +" + b.optInt("streak") + " " : "").append(b.optInt("revenge") > 0 ? "설욕 +" + b.optInt("revenge") + " " : "")
                  .append(b.optInt("daily") > 0 ? "오늘 첫 승 +" + b.optInt("daily") : "");
            if (m.has("event")) sb.append("\n").append(m.optString("event"));
            JSONObject af = m.optJSONObject("after");
            if (af != null) sb.append("\n").append(League.gwa(League.oppNick(m))).append("의 전적 ").append(af.optInt("h2h_w")).append("승 ").append(af.optInt("h2h_l")).append("패")
                    .append(af.optInt("h2h_d") > 0 ? " " + af.optInt("h2h_d") + "무" : "").append(af.optInt("streak") > 1 ? " · " + af.optInt("streak") + "연승" : "");
            if (m.optInt("broke_streak") >= 2) sb.append("\n").append(m.optInt("broke_streak")).append("연승이 끊겼습니다");
        }
        AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle(title).setMessage(sb.toString()).setNegativeButton("닫기", null);
        final RankCore.Player p = League.rankPlayer(m.optString("opp"));
        if (p != null) d.setNeutralButton("상대 프로필", (x, y) -> profile(p));
        d.show();
    }

    private TextView section(String s) {
        TextView t = text(s, 13, GOLD, true);
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    /* ── 그리기 도구 ───────────────────────────────────────────────────── */

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(14));
        c.setBackground(g);
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        return c;
    }
    private View line() { View v = new View(this); v.setBackgroundColor(LINE); v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)); return v; }
    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }
    private TextView pill(String s, int bg, int fg) {
        TextView t = text(s, 15, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable(); g.setColor(bg); g.setCornerRadius(dp(12));
        t.setBackground(g);
        t.setClickable(true);
        return t;
    }
    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
