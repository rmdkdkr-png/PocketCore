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
        if (why == null && rom == null) why = "롬 경로가 없습니다 — 런처에서 사무쇼2 카드를 눌러 「가상 리그」로 들어오세요";
        if (why == null && !rom.equals(romChecked)) why = "롬 확인 중입니다 — 잠시 뒤 다시 눌러 주세요";
        if (why == null && romWhy != null) why = romWhy;
        return why;
    }

    /** 결과 창은 롬 확인이 끝난 뒤에 띄운다 — 그래야 재대결·다음 버튼이 롬 확인 결과를 따른다. */
    private String pendingResult = null;
    private void showResultWhenChecked(String id) {
        if (rom == null || rom.equals(romChecked)) showResult(id); else pendingResult = id;
    }

    /* ── 랭킹 ─────────────────────────────────────────────────────────── */

    private static final class Row { String id, nick, sub; int score, order; boolean me; JSONObject o; }

    /** League.order() 순서(점수 내림차순, 동점이면 직전 순위 그대로, 처음엔 고정 id 순). */
    private List<Row> table() {
        List<Row> rows = new ArrayList<>();
        int k = 0;
        for (String id : League.order()) {
            Row r = new Row(); r.id = id; r.order = k++;
            if ("me".equals(id)) {
                JSONObject me = League.me();
                r.nick = me.optString("name", "나"); r.me = true; r.score = me.optInt("score");
                r.sub = me.optInt("w") + "승 " + me.optInt("l") + "패" + (me.optInt("d") > 0 ? " " + me.optInt("d") + "무" : "")
                      + (me.optInt("streak") > 0 ? " · " + me.optInt("streak") + "연승" : "");
            } else {
                JSONObject o = League.oppById(id);
                if (o == null) continue;
                r.nick = o.optString("nick"); r.o = o; r.score = o.optInt("score");
                r.sub = League.oppLine(o);
                String rc = League.recent(id, 3);
                if (!rc.isEmpty()) r.sub += " · 최근 " + rc;
            }
            rows.add(r);
        }
        return rows;
    }

    private void render() {
        col.removeAllViews();
        TextView h = text("가상 리그", 24, TXT, true);
        col.addView(h);
        TextView sub = text("사무라이 쇼다운! 2 · 랭크전(시험) — 가상 상대 12명은 모두 게임 CPU 입니다. 닉네임은 지어낸 이름이며 실제 사람이 아닙니다.", 12, DIM, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        col.addView(sub);

        final JSONObject open = League.openMatch();
        if (open != null) col.addView(openCard(open));
        if (open == null && rom != null) {                 /* 게임 속 리그전 — 타이틀 «리그 대전»에서 상대 고르기 */
            TextView go = pill("게임 속 리그전  ›  (타이틀 「리그 대전」)", GOLD, INK);
            go.setOnClickListener(v -> {
                String why = League.dbProblem != null ? League.dbProblem : romWhy != null ? romWhy
                        : !rom.equals(romChecked) ? "롬 확인 중입니다 — 잠시 뒤 다시 눌러 주세요" : null;
                if (why != null) { toast(why); return; }
                if (launching) return;
                launching = true;
                Intent i = new Intent(this, EmuActivity.class);
                i.putExtra("rom", rom);
                i.putExtra(EmuActivity.EXTRA_LEAGUE_HUB, true);
                startActivity(i);
                finish();
            });
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            glp.bottomMargin = dp(12);
            col.addView(go, glp);
        }

        List<Row> rows = table();
        col.addView(meCard(rows));

        /* 주변 순위 먼저(기획서 3장) — 내 위·아래 두 명씩 */
        List<String> nb = League.neighbors(2);
        if (!nb.isEmpty()) {
            col.addView(section("가까운 순위"));
            LinearLayout nc = card();
            int k = 0;
            for (Row r : rows) {
                if (r.me || !nb.contains(r.id)) continue;
                if (k++ > 0) nc.addView(line());
                nc.addView(rankRow(r.order + 1, r, open == null));
            }
            col.addView(nc);
        }

        col.addView(section("전체 순위 · " + rows.size() + "명"));
        LinearLayout card = card();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) card.addView(line());
            card.addView(rankRow(i + 1, rows.get(i), open == null));
        }
        col.addView(card);

        TextView rules = text("규칙 · " + League.POINTS + "선승(3판 2승) · 라운드 " + League.TIME
                + "초 · 카드 전원 올카드 해금(CPU 는 카드 없음) · 도전하면 1인 대전 캐릭터 고르기까지 자동으로 들어갑니다."
                + " 내 캐릭터·검질·카드를 고르면 첫 상대가 가상 상대로 나옵니다. 경기 전체 결판만 기록하고,"
                + " 랭크전 중에는 불러오기·되돌리기·리셋을 쓸 수 없습니다. 랭크전 세이브는 원작 세이브와 따로입니다."
                + "\n점수 · 시작 때 두 점수 차이로 정합니다: 상대가 101점 이상 높으면 이기면 +18·지면 −6, ±100 안이면 ±12,"
                + " 101점 이상 낮으면 +6·−18. 내가 얻은 만큼 상대가 잃습니다. 무승부는 점수 그대로. 상대 난도는 점수에 따라 바뀌지 않습니다.", 12, DIM, false);
        rules.setPadding(0, dp(12), 0, 0);
        col.addView(rules);
        List<JSONObject> hs = League.holds(5);
        if (!hs.isEmpty()) {
            TextView hh = text("보류·무효 경기 — 점수에 넣지 않았습니다", 13, GOLD, true);
            hh.setPadding(0, dp(16), 0, dp(4));
            col.addView(hh);
            LinearLayout hc = card();
            for (int i = 0; i < hs.size(); i++) {
                final JSONObject m = hs.get(i);
                if (i > 0) hc.addView(line());
                JSONObject o = League.oppById(m.optString("opp"));
                TextView t = text(League.statusKo(m.optString("status")) + " · " + (o != null ? o.optString("nick") : "?") + "\n" + m.optString("why"), 12, DIM, false);
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
            TextView w = text(rom == null ? "롬 경로가 없어 도전할 수 없습니다 — 런처에서 사무쇼2 카드를 눌러 「가상 리그」로 들어오세요."
                                          : "도전할 수 없습니다: " + romWhy, 12, LOSE, false);
            w.setPadding(0, dp(10), 0, 0);
            col.addView(w);
        }
    }

    private View rankRow(int rank, final Row r, boolean canChallenge) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TextView n = text(String.valueOf(rank), 18, r.me ? GOLD : DIM, true);
        n.setGravity(Gravity.CENTER);
        row.addView(n, new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.addView(text(r.nick + (r.me ? "  (나)" : ""), 16, TXT, true));
        String extra = r.sub;
        if (!r.me) {
            int myW = r.o.optInt("l"), myL = r.o.optInt("w");
            extra += "\n나와의 전적 " + myW + "승 " + myL + "패" + (r.o.optInt("d") > 0 ? " " + r.o.optInt("d") + "무" : "");
        }
        tt.addView(text(extra, 12, DIM, false));
        if (!r.me) {
            List<String> tg = League.tags(r.o);
            if (!tg.isEmpty()) tt.addView(text(android.text.TextUtils.join(" · ", tg), 12, LOSE, false));
        }
        row.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text(String.valueOf(r.score), 16, r.me ? GOLD : TXT, true));
        if (!r.me) {
            row.setClickable(true);
            row.setOnClickListener(v -> detail(r.o, canChallenge));
        }
        return row;
    }

    /* ── 상대 상세 ────────────────────────────────────────────────────── */

    private void detail(final JSONObject o, boolean canChallenge) {
        StringBuilder sb = new StringBuilder();
        sb.append("캐릭터 ").append(League.CHAR[o.optInt("char")]).append(" · 검질 ").append(League.STYLE[o.optInt("style")])
          .append("\nCPU 난도 ").append(League.DIFF[o.optInt("diff")])
          .append("\n점수 ").append(o.optInt("score"))
          .append("\n나와의 전적 ").append(o.optInt("l")).append("승 ").append(o.optInt("w")).append("패");
        if (o.optInt("d") > 0) sb.append(" ").append(o.optInt("d")).append("무");
        List<String> tg = League.tags(o);
        if (!tg.isEmpty()) sb.append("\n").append(android.text.TextUtils.join(" · ", tg));
        sb.append("\n순위 ").append(League.rankOf(o.optString("id"))).append("위 (나 ").append(League.rankOf("me")).append("위)");
        sb.append("\n\n경기 규칙: ").append(League.POINTS).append("선승 · 라운드 ").append(League.TIME).append("초 · 카드 올카드 해금(CPU 카드 없음)")
          .append("\n이 상대는 늘 같은 캐릭터·검질·난도로 나옵니다. 난도는 원작 게임 설정의 난이도로 적용합니다(한글판 표기 쉬움 = 하 · 보통 = 중 · 어려움 = 상).")
          .append("\n내 캐릭터·검질·카드는 도전한 뒤 게임 안에서 고릅니다(카드 전원 올카드 해금).")
          .append("\n스테이지 ").append(League.stageLabel(League.prefStage(), o.optString("id")))
          .append(" · 내 컬러 «").append(League.COLORS[League.prefColor()]).append("» — 게임 속 리그전에서 B(스테이지)·옵션(컬러)으로 바꿉니다.");
        List<JSONObject> hist = League.history(o.optString("id"), 5);
        if (!hist.isEmpty()) {
            sb.append("\n\n최근 경기");
            for (JSONObject m : hist) sb.append("\n · ").append(League.resultLine(m)).append(m.optBoolean("first_win") ? " · 첫 승" : "");
        }
        String why = challengeWhy(o);
        if (why != null) sb.append("\n\n도전할 수 없음: ").append(why);
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(o.optString("nick"))
                .setMessage(sb.toString())
                .setNegativeButton("닫기", null);
        if (canChallenge && why == null)
            d.setPositiveButton("도전", (x, y) -> challenge(o.optString("id")));
        if (!hist.isEmpty()) {
            final String last = hist.get(0).optString("id");
            d.setNeutralButton("지난 결과", (x, y) -> showResult(last));   /* 그 경기 직후 값으로 다시 본다 */
        }
        d.show();
    }

    private boolean launching = false;          /* 두 번 눌러 게임 창이 둘 뜨지 않게 */

    private void challenge(String oppId) {
        if (launching) return;
        if (rom == null || !new File(rom).exists()) { toast("롬을 찾을 수 없습니다"); return; }
        if (League.openMatch() != null) { toast("끝나지 않은 경기가 있습니다 — 먼저 재개하거나 정리하세요"); render(); return; }
        String why = challengeWhy(League.oppById(oppId));
        if (why != null) { toast(why); return; }
        JSONObject m = League.newMatch(oppId);
        if (m == null) { toast("경기 기록을 저장하지 못해 시작하지 않았습니다"); return; }
        launch(m.optString("id"), false);
    }

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
        c.addView(text((o != null ? o.optString("nick") + " · " + League.oppLine(o) : "?") + "\n" + note, 13, TXT, false));
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
        AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle(title).setMessage(sb.toString())
                .setNegativeButton("랭킹으로", null);
        if (o != null && challengeWhy(o) == null)
            d.setPositiveButton("재대결", (x, y) -> challenge(o.optString("id")));
        String nid = League.nextOpp(m.optString("opp"));
        final JSONObject nx = nid != null ? League.oppById(nid) : null;
        if (nx != null && challengeWhy(nx) == null)
            d.setNeutralButton("다음: " + nx.optString("nick"), (x, y) -> detail(nx, true));
        d.show();
    }

    /** 내 순위·점수·누적 전적·연승(기획서 2장 «랭킹: 내 순위와 점수»). */
    private View meCard(List<Row> rows) {
        JSONObject me = League.me();
        int rank = 0;
        for (Row r : rows) if (r.me) rank = r.order + 1;
        LinearLayout c = card();
        c.addView(text("내 순위 " + rank + "위 / " + rows.size() + "명 · " + me.optInt("score") + "점", 18, GOLD, true));
        String s = "누적 " + me.optInt("w") + "승 " + me.optInt("l") + "패" + (me.optInt("d") > 0 ? " " + me.optInt("d") + "무" : "")
                 + " · 연승 " + me.optInt("streak") + " (최장 " + me.optInt("best") + ")";
        c.addView(text(s, 13, TXT, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        c.setLayoutParams(lp);
        return c;
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
