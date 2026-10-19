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
        try { League.appVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception ignored) { }   /* voidKind «앱이 바뀜» · 보관 파일 "app" */
        rom = getIntent().getStringExtra(EXTRA_ROM);
        if (rom == null) rom = lastRom();
        tidy();
        writeWhy = League.writeProblem();
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
        if (rom == null) rom = lastRom();
        launching = false;
        hubAfter = null;
        tidy();
        writeWhy = League.writeProblem();
        render();
        checkRom();
        String res = i.getStringExtra(EXTRA_RESULT);
        if (res != null) { i.removeExtra(EXTRA_RESULT); showResultWhenChecked(res); }
    }

    private boolean away = false;                    /* 한 번 내려갔다(권한 설정 등) — 처음 onResume 은 onCreate 가 막 본 값이라 다시 안 본다 */
    private boolean resumed = false;                 /* 앞에 보이는 중 — 내려간 사이(홈 등) 롬 확인이 끝나도 허브는 돌아올 때 연다(뒤에서 띄우면 막히고 이 화면만 닫힌다) */
    @Override protected void onPause() { super.onPause(); away = true; resumed = false; }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        boolean again = !tidyDone && !League.liveHub;          /* 열 때 게임 속 리그가 아직 살아 있었다(창 바뀌는 사이) — 닫힌 뒤 한 번 정리 */
        if (again) tidy();
        boolean changed = false;
        if (away && storeWhy() != null) {                      /* 저장소 카드를 보이던 중 돌아왔다(권한 설정 등) — 풀렸나 */
            String w = League.writeProblem();
            if (!League.liveHub && (League.dbProblem != null || w == null)) {
                /* 풀렸으면(못 읽음은 늘) 파일에서 다시 — 메모리 판은 파일이 안 보일 때 만든 빈 기록일 수 있다(그대로 두면 다음 저장이 진짜 기록을 덮는다) */
                reread();
                changed = true;
            } else {
                changed = w == null ? writeWhy != null : !w.equals(writeWhy);
                writeWhy = w;
            }
        }
        away = false;
        if (again || changed) render();
        if (hubAfter != null) hubAfterCheck();                 /* 닫은 뒤 허브를 기다리다 내려갔었다 */
    }

    /** 파일에서 다시 읽기 — [다시 읽기] · 저장소 카드를 보이다 돌아왔을 때. 메모리 판을 버리고 새로 읽어 열 때처럼 정리한다.
     *  남은 문제(못 읽음·못 씀 — 없으면 null). 게임 속 리그가 살아 있을 땐 부르지 않는다(그 판이 쓰는 기록을 버리게 된다) */
    private String reread() {
        String left = League.reload();
        writeWhy = League.writeProblem();
        BoardCache.invalidate();
        if (rom == null) { rom = lastRom(); checkRom(); }       /* 이제 읽히면 pref.last_rom 도 */
        tidy();
        return left;
    }

    /** 열 때 정리 — 게임 창이 없는 «진행 중» 경기는 복귀 대기로, 남은 «준비» 경기는 무효로(남은 판 — 잡힌 상대 miss).
     *  게임 속 리그가 살아 있으면(League.liveHub — 그 판이 쓰는 중) 안 건드린다 */
    private boolean tidyDone = false;
    private void tidy() {
        if (League.liveHub) { tidyDone = false; return; }
        tidyDone = true;
        League.markInterrupted();
        int n = League.voidReady("순위표를 열며 정리(대진 전)");
        if (n > 0) toast("첫 라운드 전에 멈춘 경기를 무효로 닫았어요 — 같은 상대가 기다려요");
    }

    /** 롬 경로 없이 들어왔을 때 — 마지막으로 리그를 연 원래 롬(pref.last_rom)이 아직 있으면 그것 */
    private static String lastRom() {
        String r = League.lastRom();
        return r != null && new File(r).exists() ? r : null;
    }

    /** 기록 폴더에 쓸 수 없는 이유 — 열 때·다시 읽기·돌아올 때만 본다(그릴 때마다 시험 파일을 만들지 않게) */
    private String writeWhy = null;
    /** 기록 파일 문제(못 읽음 · 못 씀) — 있으면 저장소 카드 */
    private String storeWhy() { return League.dbProblem != null ? League.dbProblem : writeWhy; }

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
    private volatile String romMd5 = null;           /* 확인한 리그 롬(사본) md5 — 롬 때문에 막힌 경기가 원래 롬으로 돌아왔나(EmuActivity.leagueVersionProblem 과 같은 파일) */
    private static final String ROM_CHECKING = "롬 확인 중입니다 — 잠시 뒤 다시 눌러 주세요";

    private void checkRom() {
        final String r = rom;
        if (r == null || r.equals(romChecked)) return;
        new Thread(() -> {
            String why = null, sum = null;
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
                    sum = md5;
                    if (League.romLabel(md5) == null)
                        why = "판정 주소를 확인하지 않은 롬입니다(md5 " + md5 + ") — 순정 롬이나 한글패치 v1.01·앱 동봉 한패로 바꾸세요";
                }
            } catch (Exception e) { why = null; sum = null; }   /* 확인 자체가 실패하면 막지 않는다 — 시작 때 다시 본다 */
            final String w = why, mv = sum;
            runOnUiThread(() -> {
                romWhy = w; romMd5 = mv; romChecked = r;
                if (isFinishing()) return;
                render();
                if (pendingResult != null) { String p = pendingResult; pendingResult = null; showResult(p); }
                if (hubAfter != null) hubAfterCheck();
            });
        }, "league-romcheck").start();
    }

    /** 도전할 수 없는 이유(없으면 null) — 상대·열린 경기·기록 파일(League) + 롬(확인 중이면 그것도 이유). */
    private String challengeWhy(JSONObject o) {
        String why = League.blockedWhy(o);
        if (why == null && rom == null) why = "롬 경로가 없습니다 — 런처에서 사무쇼2 카드를 눌러 「리그 순위표」로 들어오세요";
        if (why == null && !rom.equals(romChecked)) why = ROM_CHECKING;
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
    /** 순위표·내 순위는 다른 스레드에서 만든다(위 100명 + 프로필 + 아바타 — 에뮬레이터에서 화면이 수십 초 멈췄다). 같은 날·같은 내 LP·같은 탭이면 다시 안 만든다.
     *  157: 캐시와 계산은 BoardCache(게임 속 메뉴의 순위표와 같이 쓴다) — 내 주변 ±5 · 위 100 · 아바타 미리 */
    private int boardK() { return boardAround ? 5 : 100; }
    private void boardLoad(final RankCore.Me me) {
        BoardCache.warm(this, me, boardAround, boardK(), true, () -> { if (!isFinishing()) render(); });
    }
    /** 순위표 줄은 프로필·아바타까지 데운 판만(게임 안 서랍이 아바타 없이 데운 판이면 아바타만 마저 데운 뒤 — UI 스레드에서 100줄을 만들지 않게) */
    private BoardCache.Board boardFor(RankCore.Me me) { return BoardCache.get(me, boardAround, boardK(), true); }
    private boolean boardAround = false;         /* 순위표: 위 100 / 내 주변 */
    private static final String[] TABS = { "순위표", "최근 경기", "라이벌", "규칙" };

    private void render() {
        col.removeAllViews();
        col.addView(text("랭크 매치", 24, TXT, true));
        TextView sub = text("사무라이 쇼다운! 2 · 리그 대전 — 상대는 모두 게임 CPU 예요. 닉네임·칭호·사진은 지어낸 것이고, 티어·LP·전적은 실제 경기로만 움직여요.", 12, DIM, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        col.addView(sub);

        final String store = storeWhy();
        if (store != null) col.addView(storeCard(store));
        final JSONObject open = League.openMatch();
        if (open != null) col.addView(openCard(open, store == null));   /* 저장소 문제면 글만 — 닫아도 저장이 안 된다(위 저장소 카드부터) */
        RankCore.Me me = League.rankMe();
        col.addView(myCard(me));
        col.addView(mainRow());                                   /* 153 내 캐릭터 — 랭크는 이걸로 바로 붙는다 */
        col.addView(nameRow());                                   /* 168(11) 내 이름 — 이름표·VS 띠·결판 띠·순위표 */
        /* 큰 버튼(148 — 늘 보인다): 열린 경기 없음 «하러 가기» · 이어 할 수 있는 경기 «멈춘 경기 이어서 하기» — 둘 다 게임 속 리그(허브)로.
           허브가 열린 경기를 체크포인트로 이어 하고, 결판 뒤 결과 카드 [다음 상대]. 무효로 닫을 경기(voidKind)·원래 롬이 아닌 롬 막힘은 카드 버튼만 · 저장소 문제면 저장소 카드만 */
        boolean started = open != null && !"ready".equals(open.optString("status"));
        if (store == null && rom != null && hubOk(open)) {
            TextView go = pill(started ? "멈춘 경기 이어서 하기  ›" : "랭크 매치 하러 가기  ›", GOLD, INK);
            go.setOnClickListener(v -> goHub());
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
                String hl = m.optBoolean("void_after_start")         /* 시작한 뒤 무효(앱 탓) — «무효 · 칼끝노인 — 앱이 멈춰 이어할 자리가 없음» */
                        ? League.statusKo(m.optString("status")) + " · " + League.oppNick(m) + " — " + m.optString("why")
                        : League.statusKo(m.optString("status")) + " · " + League.oppNick(m) + "\n" + m.optString("why");
                TextView t = text(hl, 12, DIM, false);
                t.setPadding(0, dp(6), 0, dp(6));
                t.setClickable(true);
                t.setOnClickListener(v -> showResult(m.optString("id")));
                hc.addView(t);
            }
            col.addView(hc);
        }
        if (store == null && (rom == null || romWhy != null)) {   /* 기록 파일 문제는 위 저장소 카드가 보인다 */
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
        BoardCache.Board bd = boardFor(me);
        if (bd == null && me.placementLeft == 0) boardLoad(me);
        String rk = me.placementLeft > 0 ? "배치를 마치면 순위가 나와요" : bd == null ? "전국 순위 계산 중…"
                  : String.format(java.util.Locale.KOREA, "전국 %,d위 / %,d명", bd.rank, RankCore.total());
        TextView r = text(rk + " · 시즌 " + League.season(), 13, GOLD, false); r.setPadding(0, dp(6), 0, 0);
        c.addView(r);
        List<JSONObject> past = League.seasons();              /* 지난 시즌이 있으면 승패는 이번 시즌 것 — 통산은 따로 */
        String s = (past.isEmpty() ? "통산 " : "이번 시즌 ") + me.w + "승 " + me.l + "패" + (me.d > 0 ? " " + me.d + "무" : "") + " · 연승 " + me.streak + " (최장 " + me.best + ")"
                 + (me.peakLp > 0 && !me.master ? " · 최고 " + RankCore.label(me.peakLp) : "");
        if (!past.isEmpty()) {
            int[] cw = League.careerWLD();
            s += " · 통산 " + cw[0] + "승 " + cw[1] + "패" + (cw[2] > 0 ? " " + cw[2] + "무" : "");
        }
        c.addView(text(s, 12, DIM, false));
        if (!past.isEmpty()) {
            String pk = past.get(past.size() - 1).optString("peak_label");
            if (!pk.isEmpty() && !"배치".equals(pk)) c.addView(text("지난 시즌 최고 · " + pk, 12, DIM, false));   /* 배치만 하다 처음부터 한 줄은 안 보인다 */
        }
        String held = heldLine(me);
        if (held != null) { TextView hv = text(held, 12, TXT, false); hv.setPadding(0, dp(4), 0, 0); c.addView(hv); }
        return c;
    }

    /** 153 내 캐릭터 줄 — «내 캐릭터 · 샬럿 나찰 · 다홍 [바꾸기]». 랭크에 처음 들어와 게임 고르기 화면에서 한 번 고른 것(League.main) —
     *  다음 판부터 앱이 대신 고른다. [바꾸기] = 지우기(다음 판에 다시 직접 고르고 그걸 남긴다). 컬러는 지금 보일 것(2P색 · 캐릭터별 컬러 기억) */
    private View mainRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(10), 0, 0);
        League.Main mn = League.main();
        TextView t = text(mn == null ? "내 캐릭터 · 첫 판에서 한 번 골라요" : "내 캐릭터 · " + League.mainLabel(mn, mainColour(mn)), 13, mn == null ? DIM : TXT, mn != null);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (mn != null) {
            TextView b = pill("바꾸기", BTN2, TXT);
            b.setTextSize(13);
            b.setPadding(dp(14), dp(6), dp(14), dp(6));
            b.setOnClickListener(v -> {
                if (!League.clearMain()) toast("저장하지 못했어요 — 다음 판에 다시 골라요");
                else toast("다음 판에 새로 골라요");
                render();
            });
            r.addView(b);
        }
        return r;
    }
    /** 168(11) 내 이름 줄 — «내 이름 · 새벽검객77 [바꾸기]». 게임 안 서랍 «리그» 탭과 같은 값(League.setMyName — 글꼴에 있는 글자만 · 비우면 «나») */
    private View nameRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(6), 0, 0);
        r.addView(text("내 이름 · " + League.myNick(), 13, TXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView b = pill("바꾸기", BTN2, TXT);
        b.setTextSize(13);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setOnClickListener(v -> askName());
        r.addView(b);
        return r;
    }
    private void askName() {
        if (toolDlgUp()) return;
        final android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setText(League.myNick());
        e.setSelection(e.getText().length());
        e.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(League.NAME_MAX + 4) });
        toolDlg = new AlertDialog.Builder(this).setTitle("내 이름").setMessage("한글·영문·숫자 " + League.NAME_MAX + "자까지 · 비우면 «나»").setView(e)
            .setPositiveButton("정하기", (x, y) -> {
                if (!League.setMyName(e.getText().toString())) toast("저장하지 못했어요 — 이름은 그대로예요");
                BoardCache.invalidate();
                render();
            })
            .setNegativeButton("취소", null).show();
    }
    /** 지금 보일 컬러 번호 — 2P색이면 1, 아니면 컬러 기억(캐릭터 칸 · 무작위 칸 15). 컬러 고르기 끔이면 기본 */
    private static int mainColour(League.Main mn) {
        if (mn.b2p) return 1;
        try {
            if (!Look.on()) return 0;
            byte[] m = Look.loadMem();
            int i = mn.cell == 15 ? 15 : mn.cell;
            return m != null && i < m.length ? m[i] : mn.colour;
        } catch (Exception e) { return mn.colour; }
    }

    /** 잡힌 상대 한 줄 — «기다리는 상대 · 칼끝노인 (검호 ★1) — 내일 21:04 까지»(잡은 때 + 24시간, 한국 시각 — League.today 와 같은 기준). 없으면 null */
    private String heldLine(RankCore.Me me) {
        if (me.pendingId == null) return null;
        try {
            JSONObject pd = League.ranked().optJSONObject("pending");
            if (pd == null || !me.pendingId.equals(pd.optString("id"))) return null;
            RankCore.Player p = League.rankPlayer(me.pendingId);
            if (p == null) return null;
            long until = pd.optLong("at") + League.PENDING_MS, now = System.currentTimeMillis();
            if (until <= now) return null;
            java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA);
            hm.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));
            String day = RankCore.today(until) == RankCore.today(now) ? "오늘 " : "내일 ";
            return "기다리는 상대 · " + League.nickOf(this, p) + " (" + League.oppView(p).label + ") — " + day + hm.format(new java.util.Date(until)) + " 까지";
        } catch (Exception e) { return null; }
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
        BoardCache.Board bd = boardFor(me);
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
        String nick = r.me ? League.myNick() : pf.optString("nick", r.nick);
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
        String s = League.rulesText();                          /* 168: 게임 안 서랍 «리그» 탭과 같은 글 */
        if (lg != null && lg.optInt("games") > 0)
            s += "\n\n옛 리그 기록 · " + lg.optInt("w") + "승 " + lg.optInt("l") + "패" + (lg.optInt("d") > 0 ? " " + lg.optInt("d") + "무" : "")
               + " · 최종 " + lg.optInt("score") + "점 — 이 기록으로 시작 실력을 정했어요.";
        TextView t = text(s, 12, DIM, false);
        col.addView(t);
        seasonSection();
    }

    /* ── 시즌 · 기록(148) — 이번 시즌 · 지난 시즌 줄 · [새 시즌 시작] · [처음부터 다시] ──
       둘 다 League 가 지금 랭크 칸을 league/archive/ 에 먼저 보관하고 바꾼다(지우지 않는다). 시작한 경기가 열려 있거나 게임 속 리그가 살아 있으면 못 한다 */
    private void seasonSection() {
        col.addView(section("시즌 · 기록"));
        RankCore.Me me = League.rankMe();
        LinearLayout c = card();
        java.text.SimpleDateFormat md = new java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREA);
        md.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));          /* 리그의 «오늘»은 한국 시각 */
        long t0 = League.seasonStart();
        c.addView(text("시즌 " + League.season() + (t0 > 0 ? " · " + md.format(new java.util.Date(t0)) + "부터" : "") + " · " + wld(me.w, me.l, me.d), 14, TXT, true));
        List<JSONObject> past = League.seasons();
        for (int i = past.size() - 1; i >= 0; i--) {                            /* 새것부터 */
            JSONObject x = past.get(i);
            String fin = x.optString("final_label") + (x.optBoolean("master") && x.optInt("mr") > 0 ? " MR " + x.optInt("mr") : "");
            String row = "시즌 " + x.optInt("n", 1) + ("restart".equals(x.optString("kind")) ? " · 처음부터 다시" : "")
                    + " · 최고 " + x.optString("peak_label") + " · 마지막 " + fin + " · " + wld(x.optInt("w"), x.optInt("l"), x.optInt("d"));
            TextView rv = text(row, 12, DIM, false); rv.setPadding(0, dp(4), 0, 0);
            c.addView(rv);
        }
        col.addView(c);

        /* 153: «매칭 대기»(보통·빠르게)는 뺐다 — 찾기는 늘 ~1초(RankCore.quickMs). Settings pocketcore_rank_wait 는 남아도 안 읽는다 */

        /* 새 시즌 · 처음부터 — 막힘(기록 파일)이면 둘 다 끄고 이유를 보인다. 168 고침: 멈춘 경기는 막지 않는다 — 두 번 묻기 글에 «기권패로 닫아요»(게임 안 리그 탭과 같게) */
        if (League.liveHub) return;                              /* 168 M2: 리그 판이 떠 있다 — 시즌 도구 줄은 숨김(글 없이 · 게임 안 «리그 메뉴» › 리그 탭에 있다) */
        final String blk = League.seasonToolWhy();
        if (me.placementLeft > 0) {
            TextView pl = text("배치 경기를 마치면 새 시즌을 열 수 있어요", 12, DIM, false); pl.setPadding(0, dp(14), 0, 0);
            col.addView(pl);
        } else {
            TextView ns = pill("새 시즌 시작", BTN2, blk == null ? TXT : DIM);
            toolBtn(ns, blk == null ? () -> askNewSeason() : null);
            col.addView(ns, toolLp());
            col.addView(caption("LP 를 반쯤 내리고 승패를 0부터 세요 · 실력 점수는 그대로"));
        }
        TextView rs = pill("처음부터 다시", BTN2, blk == null ? LOSE : DIM);
        toolBtn(rs, blk == null ? () -> askRestart() : null);
        col.addView(rs, toolLp());
        col.addView(caption("지금 기록은 보관해요 · 배치 5판부터"));
        if (blk != null) {
            TextView bw = text(blk, 12, LOSE, false); bw.setPadding(0, dp(8), 0, 0);
            col.addView(bw);
        }
    }
    private static String wld(int w, int l, int d) { return w + "승 " + l + "패" + (d > 0 ? " " + d + "무" : ""); }
    private TextView caption(String s) { TextView t = text(s, 11, DIM, false); t.setPadding(dp(2), dp(4), 0, 0); return t; }
    private LinearLayout.LayoutParams toolLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        return lp;
    }
    /** 시즌 도구 버튼 — 못 쓰면(go == null) 흐리게·눌러도 아무것도 안 함 */
    private void toolBtn(TextView b, Runnable go) {
        if (go == null) { b.setEnabled(false); b.setClickable(false); b.setAlpha(0.55f); return; }
        b.setOnClickListener(v -> go.run());
    }

    /** 확인 창(시즌 도구·기권) — 빨리 두 번 눌러 창이 둘 뜨고 두 번 바뀌지 않게(시즌은 확인 때 번호도 다시 본다) */
    private AlertDialog toolDlg;
    private boolean toolDlgUp() { return toolDlg != null && toolDlg.isShowing(); }

    private void askNewSeason() {
        if (toolDlgUp()) return;
        final League.SeasonPreview p = League.newSeasonPreview();
        if (p == null) { render(); return; }                   /* 그사이 배치로 돌아갔다(처음부터 다시 등) */
        final String ffLine = League.openForfeitLine();         /* 168 고침: 멈춘 경기가 있으면 기권패로 닫고 새 시즌(M1·D6) */
        final String ffId = openStartedId();
        String body = (ffLine != null ? ffLine + ".\n\n" : "") + "시즌 " + p.season + " 기록은 ‘지난 시즌’에 남아요.\n"
                + String.format(java.util.Locale.KOREA, "LP %,d → %,d", p.lp0, p.lp1) + " (" + p.label0 + " → " + p.label1 + ")\n"
                + "승패·연승은 0부터, 실력 점수와 상대 전적은 그대로라 빨리 올라와요.";
        toolDlg = new AlertDialog.Builder(this).setTitle("새 시즌을 시작할까요?").setMessage(body)
            .setPositiveButton(ffLine != null ? "기권패로 닫고 시작" : "시작", (x, y) -> {
                if (League.season() != p.season) { render(); return; }   /* 그사이 이미 바뀌었다 — 미리보기도 옛것 */
                if (!java.util.Objects.equals(ffId, openStartedId())) { render(); return; }   /* 그사이 멈춘 경기가 바뀌었다 — 물은 글과 다르다 */
                String why = League.seasonToolWhy();
                if (why != null) { if (!League.liveHub) toast(why); render(); return; }   /* 168 M2: 그사이 리그 판이 떴으면 글 없이(줄이 숨는다) */
                if (!League.newSeason(false, ffId != null)) toast("저장하지 못했어요 — 기록은 그대로예요");
                else { League.sessionReset(); toast("시즌 " + League.season() + " 시작!"); }
                afterReset();
            })
            .setNegativeButton("취소", null).show();
    }

    private void askRestart() {
        if (toolDlgUp()) return;
        final int s0 = League.season();
        final String ffLine = League.openForfeitLine();         /* 168 고침: 멈춘 경기가 있으면 기권패로 닫고 처음부터(M1·D6) */
        final String ffId = openStartedId();
        toolDlg = new AlertDialog.Builder(this).setTitle("처음부터 다시 할까요?")
            .setMessage((ffLine != null ? ffLine + ".\n\n" : "") + "티어·LP·전적·상대 전적을 처음으로 돌리고 배치 경기 5판부터 시작해요.\n"
                    + "지금 기록은 지우지 않고 보관 파일과 ‘지난 시즌’에 남겨요.\n"
                    + "앱 안에서 되돌릴 수는 없어요.")
            .setPositiveButton(ffLine != null ? "기권패로 닫고 처음부터" : "처음부터", (x, y) -> {
                if (League.season() != s0) { render(); return; }         /* 그사이 이미 바뀌었다 */
                if (!java.util.Objects.equals(ffId, openStartedId())) { render(); return; }   /* 그사이 멈춘 경기가 바뀌었다 */
                String why = League.seasonToolWhy();
                if (why != null) { if (!League.liveHub) toast(why); render(); return; }   /* 168 M2: 그사이 리그 판이 떴으면 글 없이(줄이 숨는다) */
                if (!League.restartRanked(false, ffId != null)) toast("저장하지 못했어요 — 기록은 그대로예요");
                else toast("시즌 " + League.season() + " — 배치 경기부터 시작해요");
                afterReset();
            })
            .setNegativeButton("취소", null).show();
    }

    private static String openStartedId() { JSONObject o = League.startedOpenMatch(); return o != null ? o.optString("id") : null; }
    /** 시즌을 바꾼 뒤 — 순위표 캐시(boardKey: 같은 날·같은 LP 면 다시 안 만든다)를 버리고 다시 그린다 */
    private void afterReset() {
        BoardCache.invalidate();
        render();
    }

    private boolean launching = false;          /* 두 번 눌러 게임 창이 둘 뜨지 않게 */

    /** 롬·코어로 막힌 시작한 경기 — resume_fail «rom»(148 EmuActivity.leagueStart). 147 이 막은 경기는 표시 없이 복구 안내(fix)만 남겼다(복귀 대기에 fix 는 그때뿐) */
    private static boolean romBlocked(JSONObject m) {
        if (m == null || !"resume".equals(m.optString("status"))) return false;
        String rf = m.optString("resume_fail", "");
        return "rom".equals(rf) || (rf.isEmpty() && m.has("fix"));
    }
    /** 롬 확인이 끝났나 — 확인 중·확인 실패(md5 모름)면 false */
    private boolean romKnown() { return rom != null && rom.equals(romChecked) && (romWhy != null || romMd5 != null); }
    /** 롬 때문에 막힌 경기가 지금 롬으로 이어질 것 같나 — 확인한 리그 롬이 판정 목록에 있고 md5 가 경기를 시작한 롬과 같다. 코어 문제는 허브가 다시 본다 */
    private boolean romBack(JSONObject m) {
        String was = m.optString("rom_md5", "");
        return romKnown() && romWhy == null && (was.isEmpty() || was.equals(romMd5));
    }
    /** 무효로 닫을 수 있나(League.voidKind). 147 이 막은 경기(표시 없음)는 지금 롬도 안 맞는 걸 확인했으면 허브가 «rom» 을 적은 뒤 볼 값과 같게 — 앱 판이 바뀌었으면 «app» */
    private String voidKindNow(JSONObject m) {
        String vk = League.voidKind(m, League.appVer);
        if (vk != null || !romBlocked(m) || !m.optString("resume_fail", "").isEmpty()) return vk;
        if (!romKnown() || romBack(m)) return null;
        try { JSONObject c = new JSONObject(m.toString()); c.put("resume_fail", "rom"); return League.voidKind(c, League.appVer); }
        catch (Exception e) { return null; }
    }
    /** 허브(큰 버튼·이어서 하기)로 갈 상태인가 — 열린 경기 없음 · «준비»(허브가 닫고 새로 찾는다) · 이어 할 수 있는 경기.
     *  무효로 닫을 경기 · 원래 롬으로 안 돌아온 롬 막힘(허브에 가도 같은 카드 — 오가기만 한다)은 카드의 [무효로 닫고 찾기]·[기권하고 찾기]만 */
    private boolean hubOk(JSONObject o) {
        if (o == null || "ready".equals(o.optString("status"))) return true;
        return voidKindNow(o) == null && (!romBlocked(o) || romBack(o));
    }

    /** 게임 속 리그(허브)를 못 여는 이유(없으면 null) — 허브가 시작할 때 한 번 더 본다 */
    private String hubWhy() {
        String sw = storeWhy();                                  /* 168 M2: 게임 속 리그가 살아 있으면 goHub 가 그 게임을 앞으로(글 없이) */
        if (sw != null) return sw;
        if (rom == null) return "롬 경로가 없습니다 — 런처에서 사무쇼2 카드를 눌러 「리그 순위표」로 들어오세요";
        if (!new File(rom).exists()) return "롬을 찾을 수 없습니다 — 원래 자리에 롬을 두세요";
        if (romWhy != null) return romWhy;
        if (!rom.equals(romChecked)) return ROM_CHECKING;
        return null;
    }

    /** 게임을 리그 판으로 켜서 로고·메뉴를 넘겨 바로 상대 찾기까지(EXTRA_HUB_GO). 열린 경기가 있으면 허브가 체크포인트로 이어 하고 결판 뒤 결과 카드로.
     *  148: «이어서 하기»도 이 길 — 예전 한 판 열기(EXTRA_LEAGUE)는 안 쓴다(끝나면 [다음 상대] 없이 이 화면으로 돌아왔다) */
    private void goHub() {
        if (launching) return;
        if (League.liveHub) { toGame(); return; }                /* 168 M2: 리그 판이 아직 떠 있다 — 새로 열지 않고 그 게임으로(창이 둘 뜨지 않게) */
        if (!tidyDone && !League.liveHub) { tidy(); render(); }   /* 열 때 살아 있던 게임 속 리그가 그사이 닫혔다 — 먼저 정리하고 다시 그린다 */
        if (!hubOk(League.openMatch())) { render(); return; }  /* 그사이 무효로 닫을 경기·롬 막힘이 됐다 — 큰 버튼이 숨는 상태라 카드에서 고르게 */
        String why = hubWhy();
        if (why != null) { toast(why); return; }
        launching = true;
        Intent i = new Intent(this, EmuActivity.class);
        i.putExtra("rom", rom);
        i.putExtra(EmuActivity.EXTRA_LEAGUE_HUB, true);
        i.putExtra(EmuActivity.EXTRA_HUB_GO, true);
        JSONObject om = League.openMatch();
        if (om != null && !"ready".equals(om.optString("status")))     /* 168 K4: 여기서 «멈춘 경기 이어서 하기»를 눌렀다 — 리셋·«나중에»로 멈춘 경기도 타이틀에서 기다리지 않고 바로 */
            i.putExtra(EmuActivity.EXTRA_RESUME_NOW, true);          /* 168 고침: 그 경기가 있을 때만(«하러 가기»·닫은 뒤 찾기엔 안 붙임 — 그 창에서 나중에 멈춘 경기가 다시 만든 창에서 바로 이어지지 않게) */
        try { startActivity(i); }
        catch (Exception e) { launching = false; toast("화면을 열지 못했어요 — 다시 눌러 주세요"); return; }
        finish();
    }

    /** 168 M2 — 홈으로 내려 둔 리그 판(EmuActivity)을 앞으로 */
    private void toGame() {
        launching = true;
        try { startActivity(new Intent(this, EmuActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
        catch (Exception e) { launching = false; return; }
        finish();
    }

    /* ── 진행 중 경기 ──────────────────────────────────────────────────── */

    /** 열린 경기 카드(148 표) — 준비(대진 전) [무효로 닫기] · 이어 할 수 있음 [기권하고 찾기] [이어서 하기] ·
     *  앱 탓으로 못 이음(voidKind) [무효로 닫고 찾기] [기권하고 찾기] · 롬만 바뀜(같은 앱) [기권하고 찾기](원래 롬으로 돌아왔으면 [이어서 하기]도).
     *  닫은 뒤엔 바로 허브로. acts == false(저장소 문제)면 글만 */
    private View openCard(final JSONObject m, boolean acts) {
        LinearLayout c = card();
        JSONObject o = League.oppById(m.optString("opp"));
        final String id = m.optString("id"), st = m.optString("status");
        final boolean ready = "ready".equals(st);
        final String vk = ready ? null : voidKindNow(m);
        final boolean romBlock = !ready && vk == null && romBlocked(m);
        final boolean live = League.liveHub;                   /* 게임 속 리그가 살아 있다 — 그 판의 경기를 여기서 닫지 않는다 */
        c.addView(text("진행 중 경기 · " + League.statusKo(st), 13, GOLD, true));
        String why = m.optString("why", "");
        String note;
        if (live) note = "게임에서 하고 있어요";
        else if (ready) note = "첫 라운드 전에 멈춘 경기입니다 — 무효로 닫으면 점수는 그대로입니다.";
        else if ("app".equals(vk)) note = "앱을 업데이트해서 이 경기를 이어할 수 없어요.\n무효로 닫으면 LP·전적은 그대로예요.";
        else if ("ckpt".equals(vk)) note = "이어할 자리를 불러오지 못했어요. 무효로 닫으면 LP·전적은 그대로예요.";
        else if (vk != null) note = "앱이 멈춰서 이어할 자리가 없어요. 무효로 닫으면 LP·전적은 그대로예요.";
        else if (romBlock) {                                   /* 어느 롬으로 되돌릴지 — 게임 속 카드 B 와 같게(사유가 이미 그 롬을 말하면 안 붙인다) */
            String r0 = m.optString("rom");
            note = "원래 롬으로 되돌리면 이어할 수 있어요." + (r0.isEmpty() || why.contains(r0) ? "" : "\n경기를 시작한 롬: " + r0) + (why.isEmpty() ? "" : "\n(" + why + ")");
        }
        else note = "앱이 내려간 그 자리에서 한 번 이어할 수 있습니다." + (why.isEmpty() ? "" : "\n(" + why + ")");
        c.addView(text((League.isRanked(m) ? "랭크 · " + League.oppLabel(m) : o != null ? o.optString("nick") + " · " + League.oppLine(o) : "?") + "\n" + note, 13, TXT, false));
        if (live) {                                            /* 168 M2: 막는 글 대신 그 게임으로 */
            TextView go = pill("게임으로 돌아가기", GOLD, INK);
            go.setOnClickListener(v -> toGame());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            c.addView(go, lp);
            cardMargin(c); return c;
        }
        if (!acts) { cardMargin(c); return c; }
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        if (ready) {                                           /* 다시 시작은 큰 버튼(허브가 이 «준비»를 닫고 새로 찾는다) */
            TextView cx = pill("무효로 닫기", BTN2, LOSE);
            cx.setOnClickListener(v -> {
                if (!League.cancel(id, "대진 전에 멈춤 — 사용자가 무효로 닫음")) toast("저장 실패 — 다시 시도하세요");
                render();
            });
            btns.addView(cx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        } else if (vk != null) {
            TextView vd = pill("무효로 닫고 찾기", GOLD, INK);
            vd.setOnClickListener(v -> {
                if (launching) return;
                if (!vk.equals(voidKindNow(League.matchById(id)))) { render(); return; }   /* 그린 뒤 상태가 바뀌었다(이어 할 수 있게 됨 등) — 다시 보고 고르게 */
                if (!League.voidStarted(id, vk)) { toast("저장 실패 — 다시 시도하세요"); render(); return; }
                afterClose(id, "무효로 닫았어요 — LP 그대로 · 바로 상대를 찾아요", false);
            });
            btns.addView(vd, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView ff = pill("기권하고 찾기", BTN2, LOSE);
            ff.setOnClickListener(v -> askForfeit(m));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(10);
            btns.addView(ff, lp);
        } else {
            TextView ff = pill("기권하고 찾기", BTN2, LOSE);
            ff.setOnClickListener(v -> askForfeit(m));
            btns.addView(ff, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if ((!romBlock || romBack(m)) && rom != null) {    /* 체크포인트로 이어 하기 — 허브로(큰 버튼과 같다). 롬 막힘은 원래 롬으로 돌아왔을 때만 */
                TextView go = pill("이어서 하기", GOLD, INK);
                go.setOnClickListener(v -> goHub());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(10);
                btns.addView(go, lp);
            }
        }
        btns.setPadding(0, dp(10), 0, 0);
        c.addView(btns);
        cardMargin(c);
        return c;
    }
    private void cardMargin(View c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
    }

    /** 기권 확인 — «기권하고 새로 찾을까요?» LP 는 지금 내 값과 시작 때 상대 MMR 로(RankCore.stakes — 정산과 같은 식). 기권 뒤 바로 허브로 */
    private void askForfeit(final JSONObject m) {
        if (launching || toolDlgUp()) return;
        final String id = m.optString("id");
        RankCore.Me me = League.rankMe();
        JSONObject s0 = m.optJSONObject("start");
        String body;
        if (League.isRanked(m) && me.placementLeft > 0) body = "멈춘 경기는 배치 1패로 기록돼요. 그다음 바로 새 상대를 찾아요.";
        else {
            int[] sk = League.isRanked(m) && s0 != null ? RankCore.stakes(me, s0.optDouble("opp_mmr", me.mmr)) : null;
            body = "멈춘 경기는 기권패로 기록돼요" + (sk != null && sk[1] != 0 ? " (LP " + RankUi.signed(sk[1]) + ")" : "") + ". 그다음 바로 새 상대를 찾아요.";   /* 수련생은 안 깎인다 — ±0 은 빼고 */
        }
        toolDlg = new AlertDialog.Builder(this).setTitle("기권하고 새로 찾을까요?").setMessage(body)
            .setPositiveButton("기권하고 찾기", (x, y) -> {
                if (League.forfeit(id) == null) { toast("저장 실패 — 다시 시도하세요"); render(); return; }
                afterClose(id, null, true);
            })
            .setNegativeButton("취소", null).show();
    }

    /** 경기를 닫은 뒤(무효·기권) — 허브를 열 수 있으면 바로 새 상대 찾기. 롬 확인만 남았으면 끝나는 대로 허브로(hubAfter).
     *  아니면 이 화면에 남아 닫은 것과 이유를 함께 보인다(기권이면 결과 창도) */
    private String hubAfter = null;                  /* 롬 확인이 끝나면 허브로 갈 — 닫은 경기 id */
    private boolean hubAfterRes = false;             /* 그때도 못 열면 결과 창(기권) */
    private void afterClose(String id, String okToast, boolean showRes) {
        String why = hubWhy();
        if (why == null) { if (okToast != null) toast(okToast); goHub(); return; }
        render();
        if (ROM_CHECKING.equals(why)) {
            if (okToast != null) toast(okToast);
            hubAfter = id; hubAfterRes = showRes;
            return;
        }
        toast(okToast != null ? "무효로 닫았어요 — LP 그대로\n" + why : why);   /* okToast 는 무효만 준다 — «바로 상대를 찾아요»는 빼고 */
        if (showRes) showResult(id);
    }
    /** 롬 확인이 끝났다 — 닫은 뒤 기다리던 허브 열기 */
    private void hubAfterCheck() {
        if (!resumed || isFinishing()) return;                 /* 내려가 있다 — onResume 에서 다시 */
        String why = hubWhy();
        if (ROM_CHECKING.equals(why)) return;                  /* 다른 롬 확인이 아직 — 그것이 끝날 때 다시 */
        String id = hubAfter; boolean res = hubAfterRes;
        hubAfter = null;
        if (why == null) { goHub(); return; }
        toast(why);
        if (res) showResult(id);
    }

    /** 기록 파일 카드 — 못 읽음 «리그 기록 파일을 못 읽어요» · 못 씀 «리그 기록 폴더에 쓸 수 없어요», 이유·폴더 + [다시 읽기] [권한 설정 열기] */
    private View storeCard(String why) {
        LinearLayout c = card();
        c.addView(text(League.dbProblem != null ? "리그 기록 파일을 못 읽어요" : "리그 기록 폴더에 쓸 수 없어요", 15, LOSE, true));
        String path = League.dir().getPath();
        TextView w = text(why + (why.contains(path) ? "" : "\n(" + path + ")"), 12, TXT, false);
        w.setPadding(0, dp(4), 0, 0);
        c.addView(w);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView re = pill("다시 읽기", GOLD, INK);
        re.setOnClickListener(v -> {
            if (League.liveHub) { toGame(); return; }        /* 168 M2: 그 판이 쓰는 메모리 기록을 버리지 않게 — 글 대신 그 게임으로 */
            String left = reread();
            render();
            if (left != null) toast("아직 안 돼요 — " + left);
        });
        btns.addView(re, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView perm = pill("권한 설정 열기", BTN2, TXT);
        perm.setOnClickListener(v -> StorageAccess.open(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(10);
        btns.addView(perm, lp);
        btns.setPadding(0, dp(10), 0, 0);
        c.addView(btns);
        cardMargin(c);
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
