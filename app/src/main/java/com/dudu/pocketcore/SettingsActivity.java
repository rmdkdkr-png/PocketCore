package com.dudu.pocketcore;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 앱 자체 설정 화면.
 *
 * 2026-10-10 다시 짰다(유저 「각론 메뉴 말고 전체 메뉴를 체계화해서 정리」). 예전엔 한 장짜리 긴 목록에
 * 범용 항목을 먼저 늘어놓고 그 아래 게임마다 제 묶음을 붙였다 — 같은 종류(예: 프레임 생성)가 위아래로 흩어졌다.
 * 이제는 첫 화면 = 큰 갈래(화면 · 움직임·반응 · 조작 · 게임 · 소리 · 롬 · 업데이트), 누르면 그 갈래 화면.
 * 갈래 안에서는 «기능»으로 모으고, 특정 게임 전용 항목엔 그 게임 이름표를 단다.
 *
 * 같은 Activity 를 인텐트 "page" 로 다시 연다(null = 첫 화면). "rom" 이 있으면 게임 안에서 연 것 —
 * 그 게임이 쓰는 항목과 범용 항목만 보인다.
 *
 * 값은 options.txt 에 그대로 쓴다. 화면(업스케일러·필터)·움직임(프레임 생성·런어헤드)은 게임으로 돌아가면
 * 바로 반영되고(EmuActivity.onResume), 언어·조작 패치처럼 롬을 다시 굽는 것은 게임을 다시 열 때 반영된다.
 */
public class SettingsActivity extends Activity {

    private static final int BG = 0xff101014, CARD = 0xff191b22, LINE = 0xff2a2f3b;
    private static final int TXT = 0xffe6e8ee, DIM = 0xff8b93a6, GOLD = 0xffd9a441, TAG = 0xff7fb0e0;

    private Map<String, String> vals;
    /** 어느 게임의 설정인가. 게임 안에서 열면 그 롬(인텐트 "rom"), 런처에서 열면 null. */
    private Games.Game game;
    private String rom;
    /** 지금 보이는 갈래(Settings.Page.id). null = 첫 화면(갈래 목록). */
    private String page;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        /* 볼륨 키 = 미디어(게임 소리) 음량 — 안 정하면 삼성은 «재생 중»을 못 알아챌 때 벨소리 음량을 바꾼다(유저 2026-10-10 「볼륨 조절이 앱에서 안 되던데」) */
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        Orient.apply(this);
        vals = Settings.load();
        rom = getIntent().getStringExtra("rom");
        game = (rom != null) ? Games.identify(rom) : null;
        page = getIntent().getStringExtra("page");
        setContentView(build());
    }

    @Override protected void onResume() {
        super.onResume();
        /* 갈래 화면에서 돌아오면 첫 화면의 «바뀐 것» 요약을 새로 */
        if (page == null) { vals = Settings.load(); setContentView(build()); }
    }

    /* ── 거르기 ─────────────────────────────────────────────────── */

    /** 이 항목을 지금 보일까. 게임 안: 범용 + 그 게임 것. 런처: 전부(게임 전용은 이름표를 단다). */
    private boolean shows(Settings.Item it) {
        if (it.feature == null && it.game == null) return true;
        return game == null || matches(it, game);
    }
    /** 항목이 이 게임 것인가 — 기능 토큰(features) 또는 게임 id 스코프(조작 패치). */
    private static boolean matches(Settings.Item it, Games.Game gm) {
        return (it.feature != null && gm.has(it.feature)) || (it.game != null && gm.id.equals(it.game));
    }
    /** 게임 전용 항목의 이름표 — 그 기능을 쓰는 게임들 이름. 범용이면 null. */
    private static String tagOf(Settings.Item it) {
        if (it.feature == null && it.game == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Games.Game gm : Games.displayOrder())
            if (matches(it, gm)) { if (sb.length() > 0) sb.append(" · "); sb.append(gm.ko); }
        return sb.length() > 0 ? sb.toString() + " 전용" : null;
    }
    private List<Settings.Item> visible(String section) {
        List<Settings.Item> out = new ArrayList<>();
        Settings.Item[] arr = Settings.GROUPS.get(section);
        if (arr != null) for (Settings.Item it : arr) if (shows(it)) out.add(it);
        return out;
    }
    /** 이 갈래에 보일 것이 있는가 — 빈 갈래는 첫 화면에서 뺀다(게임 안에서 롬 갈래 등). */
    private boolean pageHasContent(Settings.Page p) {
        if ("rom".equals(p.id)) return game == null;
        if ("control".equals(p.id)) return true;                  /* 물리 패드 매핑은 늘 */
        for (String sec : p.sections) if (!visible(sec).isEmpty()) return true;
        return false;
    }
    /** 기본값과 다른 것 — 첫 화면 요약. */
    private String changedSummary(Settings.Page p) {
        List<String> ch = new ArrayList<>();
        for (String sec : p.sections)
            for (Settings.Item it : visible(sec)) {
                String v = Settings.get(vals, it);
                if (!v.equals(it.def)) ch.add(it.label + " " + it.names[it.indexOf(v)]);
            }
        if (ch.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("바뀐 것: ");
        for (int i = 0; i < ch.size() && i < 3; i++) { if (i > 0) sb.append(" · "); sb.append(ch.get(i)); }
        if (ch.size() > 3) sb.append(" 외 ").append(ch.size() - 3);
        return sb.toString();
    }

    /* ── 화면 짜기 ──────────────────────────────────────────────── */

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(24), dp(16), dp(40));
        sv.addView(col);
        Settings.Page p = page != null ? Settings.page(page) : null;
        if (p == null) buildTop(col); else buildPage(col, p);
        return sv;
    }

    private void header(LinearLayout col, String title, String note) {
        TextView h = new TextView(this);
        h.setText(game != null ? title + " — " + game.ko : title);
        h.setTextColor(TXT); h.setTextSize(22);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        h.setPadding(dp(4), 0, 0, dp(4));
        col.addView(h);
        if (note != null) {
            TextView t = new TextView(this);
            t.setText(note);
            t.setTextColor(DIM); t.setTextSize(13);
            t.setPadding(dp(4), 0, dp(4), dp(14));
            col.addView(t);
        }
    }

    /** 첫 화면 — 갈래 목록. */
    private void buildTop(LinearLayout col) {
        header(col, "설정", null);
        LinearLayout card = card(col);
        boolean first = true;
        for (final Settings.Page p : Settings.PAGES) {
            if (!pageHasContent(p)) continue;
            if (!first) card.addView(divider(), divLp());
            first = false;
            card.addView(pageRow(p));
        }
        TextView foot = new TextView(this);
        foot.setText("파일로도 고칠 수 있습니다\n" + MainActivity.optsFile().getAbsolutePath());
        foot.setTextColor(DIM); foot.setTextSize(12);
        foot.setPadding(dp(4), dp(22), dp(4), 0);
        col.addView(foot);
    }

    private View pageRow(final Settings.Page p) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(p.title);
        name.setTextColor(TXT); name.setTextSize(17);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        txt.addView(name);
        TextView sub = new TextView(this);
        sub.setText(p.sub);
        sub.setTextColor(DIM); sub.setTextSize(12);
        sub.setPadding(0, dp(3), 0, 0);
        txt.addView(sub);
        String ch = changedSummary(p);
        if (ch != null) {
            TextView c = new TextView(this);
            c.setText(ch);
            c.setTextColor(GOLD); c.setTextSize(12);
            c.setPadding(0, dp(3), 0, 0);
            txt.addView(c);
        }
        row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(DIM); arrow.setTextSize(24);
        arrow.setPadding(dp(10), 0, 0, 0);
        row.addView(arrow);
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(SettingsActivity.this, SettingsActivity.class).putExtra("page", p.id);
                if (rom != null) i.putExtra("rom", rom);
                startActivity(i);
            }
        });
        return row;
    }

    /** 갈래 화면 — 소절마다 카드. */
    private void buildPage(LinearLayout col, Settings.Page p) {
        String note = null;
        if ("screen".equals(p.id) || "motion".equals(p.id))
            note = "게임으로 돌아가면 바로 바뀝니다.";
        else if ("motion_adv".equals(p.id))
            note = "여기서 하나라도 바꾸면 보간은 「직접」이 됩니다. 「움직임·반응」에서 120Hz·60Hz 를 다시 고르면 한꺼번에 돌아갑니다.";
        else if ("control".equals(p.id) || "game".equals(p.id) || "sound".equals(p.id))
            note = "조작 패치·언어·배경음악은 게임을 다시 열 때 반영됩니다(게임 안 「설정 › 적용하고 이어하기」로 바로).";
        header(col, p.title, note);

        for (String sec : p.sections) section(col, sec, visible(sec));

        if ("motion".equals(p.id)) {
            /* 고급 — 자주 안 쓰는 세부는 한 칸 안쪽에(유저 2026-10-11 「나머지 조정하려면 고급으로」) */
            LinearLayout adv = sectionCard(col, "고급");
            adv.addView(actionRow("보간 고급",
                    "코어 보간 · 앱 보간 · 방식 · 배수 · 이펙트 · 날아가는 몸을 하나씩",
                    new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent i = new Intent(SettingsActivity.this, SettingsActivity.class).putExtra("page", "motion_adv");
                    if (rom != null) i.putExtra("rom", rom);
                    startActivity(i);
                }
            }));
        }

        if ("control".equals(p.id)) {
            /* 조작 패치(mods) — 게임마다 따로라 게임 이름으로 소절을 나눈다 */
            if (game != null) {
                List<Settings.Item> ms = new ArrayList<>();
                for (Settings.Item it : Settings.modItems()) if (matches(it, game)) ms.add(it);
                section(col, "조작 패치", ms);
            } else {
                for (Games.Game gm : Games.displayOrder()) {
                    List<Settings.Item> ms = new ArrayList<>();
                    for (Settings.Item it : Settings.modItems()) if (matches(it, gm)) ms.add(it);
                    section(col, "조작 패치 — " + gm.ko, ms);
                }
            }
            LinearLayout padCard = sectionCard(col, "물리 패드");
            padCard.addView(actionRow("물리 패드 매핑",
                    "게임기·블루투스 패드의 버튼을 기능(약P·강P·SP·OPTION·메뉴 …)에 배정합니다",
                    new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(SettingsActivity.this, KeymapActivity.class));
                }
            }));
        }

        if ("update".equals(p.id)) {
            /* 업데이트 확인 — 런처의 B(업뎃) 말고도 설정에서 바로 */
            LinearLayout upCard = sectionCard(col, "지금 확인");
            upCard.addView(actionRow("업데이트 확인",
                    "앱·한글패치·조작 패치의 새 판을 찾아 받습니다(배포 레벨에 따라)",
                    new View.OnClickListener() {
                @Override public void onClick(View v) { Updater.check(SettingsActivity.this); }
            }));
        }

        if ("rom".equals(p.id)) {
            /* 롬 가져오기 — 설정에서도 언제든 (빈 화면에만 있으면 나중에 추가할 길이 없다) */
            LinearLayout romCard = sectionCard(col, "롬 가져오기");
            romCard.addView(actionRow("롬 폴더 주소 복사",
                    "PC 연결이나 파일 앱에서 붙여넣어 찾아가기", new View.OnClickListener() {
                @Override public void onClick(View v) { RomImport.copyPath(SettingsActivity.this); }
            }));
            romCard.addView(divider(), divLp());
            romCard.addView(actionRow("파일 골라 가져오기",
                    "파일 선택기에서 롬(또는 zip·7z)을 고르면 앱 폴더로 복사합니다 (여러 개 가능)",
                    new View.OnClickListener() {
                @Override public void onClick(View v) { RomImport.pick(SettingsActivity.this); }
            }));
            romCard.addView(divider(), divLp());
            romCard.addView(actionRow("저장소에서 롬 스캔",
                    "기기 저장소를 훑어 .ngc/.ngp 를 찾아 모아옵니다 (zip·7z 안도 꺼냄)", new View.OnClickListener() {
                @Override public void onClick(View v) { RomImport.scan(SettingsActivity.this); }
            }));
            TextView foot = new TextView(this);
            foot.setText("롬 폴더: " + MainActivity.romsDir().getAbsolutePath()
                    + "\n음성·효과 팩: " + MainActivity.sysDir().getAbsolutePath());
            foot.setTextColor(DIM); foot.setTextSize(12);
            foot.setPadding(dp(4), dp(18), dp(4), 0);
            col.addView(foot);
        }
    }

    private LinearLayout card(LinearLayout col) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD);
        col.addView(card, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(6)));
        return card;
    }

    private LinearLayout sectionCard(LinearLayout col, String title) {
        TextView sec = new TextView(this);
        sec.setText(title);
        sec.setTextColor(GOLD); sec.setTextSize(12);
        sec.setLetterSpacing(0.12f);
        sec.setTypeface(sec.getTypeface(), android.graphics.Typeface.BOLD);
        sec.setPadding(dp(4), dp(14), 0, dp(6));
        col.addView(sec);
        return card(col);
    }

    private void section(LinearLayout col, String title, List<Settings.Item> items) {
        if (items.isEmpty()) return;
        LinearLayout card = sectionCard(col, title);
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) card.addView(divider(), divLp());
            Settings.Item it = items.get(i);
            card.addView(it.pct ? seekRow(it) : row(it));
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        return v;
    }
    private LinearLayout.LayoutParams divLp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
    }

    /** 행동 한 줄 — 값 순환이 아니라 즉시 실행. */
    private View actionRow(String label, String help, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(TXT); name.setTextSize(16);
        row.addView(name);
        TextView h2 = new TextView(this);
        h2.setText(help);
        h2.setTextColor(DIM); h2.setTextSize(12);
        h2.setPadding(0, dp(4), dp(40), 0);
        row.addView(h2);
        row.setClickable(true);
        row.setOnClickListener(l);
        return row;
    }

    @Override protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (rc == RomImport.REQ_PICK && res == RESULT_OK)
            RomImport.onPicked(this, data);     /* 목록 갱신은 돌아간 런처의 onResume 몫 */
    }

    /** 이름 줄 + (런처에서) 게임 이름표 + 도움말. 값 칸(TextView)을 돌려준다. */
    private TextView head(LinearLayout row, Settings.Item it) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(it.label);
        name.setTextColor(TXT); name.setTextSize(16);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(this);
        val.setTextColor(GOLD); val.setTextSize(15);
        val.setTypeface(val.getTypeface(), android.graphics.Typeface.BOLD);
        top.addView(val);
        row.addView(top);
        String tag = (game == null) ? tagOf(it) : null;
        if (tag != null) {
            TextView t = new TextView(this);
            t.setText(tag);
            t.setTextColor(TAG); t.setTextSize(11);
            t.setPadding(0, dp(2), 0, 0);
            row.addView(t);
        }
        if (it.help != null && !it.help.isEmpty()) {
            TextView help = new TextView(this);
            help.setText(it.help);
            help.setTextColor(DIM); help.setTextSize(12);
            help.setPadding(0, dp(4), dp(40), 0);
            row.addView(help);
        }
        return val;
    }

    private void store(Settings.Item it, String v) {
        Settings.putUser(it.key, v); vals = Settings.load();     /* 묶음(보간 120·60)이 세부 값을 같이 바꿀 수 있다 */
        if ("pocketcore_lang".equals(it.key)) {   /* 전역 언어를 바꾸면 게임별 선택(실행 전 선택창)은 지운다 — 여기 값이 다시 보이는 값이 되게 */
            Settings.removePrefix("pocketcore_lang_");
            java.util.Iterator<String> ki = vals.keySet().iterator();
            while (ki.hasNext()) if (ki.next().startsWith("pocketcore_lang_")) ki.remove();
        }
    }

    /** 한 줄 — 값이 둘이면 눌러서 토글, 여럿이면 가로 다이얼 바. */
    private View row(final Settings.Item it) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        final TextView val = head(row, it);

        final String[] cur = { Settings.get(vals, it) };
        val.setText(it.names[it.indexOf(cur[0])]);
        if (it.vals.length > 2) {                             /* 다단 = 가로 다이얼 바(유저 2026-09-05) */
            DialBar bar = new DialBar(this, it.names, it.indexOf(cur[0]), new DialBar.OnPick() {
                @Override public void onPick(int k) {
                    cur[0] = it.vals[k]; val.setText(it.names[k]);
                    store(it, cur[0]);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
            lp.topMargin = dp(8);
            row.addView(bar, lp);
        }

        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (it.vals.length > 2) return;                /* 다이얼 줄은 바에서 고른다 */
                int i = (it.indexOf(cur[0]) + 1) % it.vals.length;
                cur[0] = it.vals[i];
                val.setText(it.names[i]);
                store(it, cur[0]);
            }
        });
        return row;
    }

    /** 세기(%) 줄 — 슬라이더. 끌 때는 숫자만 바꾸고, 손을 떼면 저장한다(끌 때마다 파일을 쓰지 않게). */
    private View seekRow(final Settings.Item it) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(8));
        final TextView val = head(row, it);
        int idx = it.indexOf(Settings.get(vals, it));
        val.setText(it.names[idx]);
        SeekBar sb = new SeekBar(this);
        sb.setMax(it.vals.length - 1);
        sb.setProgress(idx);
        sb.setProgressTintList(ColorStateList.valueOf(GOLD));
        sb.setThumbTintList(ColorStateList.valueOf(GOLD));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            boolean dragging = false;
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                val.setText(it.names[p]);
                if (user && !dragging) store(it, it.vals[p]);       /* 키·접근성으로 바꾼 경우 */
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; store(it, it.vals[s.getProgress()]); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        lp.topMargin = dp(6);
        row.addView(sb, lp);
        return row;
    }

    @Override public void onBackPressed() {
        if (page == null)
            Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show();
        super.onBackPressed();
    }
}
