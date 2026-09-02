package com.dudu.pocketcore;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

/**
 * 앱 자체 설정 화면.
 *
 * 코어 오버레이(아래+옵션)와 다른 점: 게임 밖에서 열리고, **앱 쪽 설정도 같이 다룬다**.
 * 언어처럼 롬에 패치를 입히는 설정은 코어가 모르므로 오버레이엔 낄 자리가 없었다.
 *
 * 값은 options.txt 에 그대로 쓴다. 코어 옵션은 다음에 롬을 띄울 때 반영된다 —
 * 코어는 로드할 때 옵션을 읽으므로, 여기서 바꾸고 게임을 다시 열어야 한다.
 * 그 사실을 화면에 적어 둔다. 안 적으면 「안 먹는다」는 오해가 난다.
 */
public class SettingsActivity extends Activity {

    private static final int BG = 0xff101014, CARD = 0xff191b22, LINE = 0xff2a2f3b;
    private static final int TXT = 0xffe6e8ee, DIM = 0xff8b93a6, GOLD = 0xffd9a441;

    private Map<String, String> vals;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        vals = Settings.load();
        setContentView(build());
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(24), dp(16), dp(40));
        sv.addView(col);

        TextView h = new TextView(this);
        h.setText("설정");
        h.setTextColor(TXT); h.setTextSize(22);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        h.setPadding(dp(4), 0, 0, dp(4));
        col.addView(h);

        TextView note = new TextView(this);
        note.setText("바꾼 값은 게임을 다시 열 때 반영됩니다 — 코어가 로드할 때 읽기 때문입니다.");
        note.setTextColor(DIM); note.setTextSize(13);
        note.setPadding(dp(4), 0, dp(4), dp(18));
        col.addView(note);

        for (Map.Entry<String, Settings.Item[]> g : Settings.GROUPS.entrySet()) {
            TextView sec = new TextView(this);
            sec.setText(g.getKey());
            sec.setTextColor(GOLD); sec.setTextSize(12);
            sec.setLetterSpacing(0.12f);
            sec.setTypeface(sec.getTypeface(), android.graphics.Typeface.BOLD);
            sec.setPadding(dp(4), dp(14), 0, dp(6));
            col.addView(sec);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(CARD);
            col.addView(card, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(6)));

            for (int i = 0; i < g.getValue().length; i++) {
                if (i > 0) card.addView(divider());
                card.addView(row(g.getValue()[i]));
            }
        }

        /* ── 롬 가져오기 — 설정에서도 언제든 (빈 화면에만 있으면 나중에 추가할 길이 없다) ── */
        TextView romSec = new TextView(this);
        romSec.setText("롬 가져오기");
        romSec.setTextColor(GOLD); romSec.setTextSize(12);
        romSec.setLetterSpacing(0.12f);
        romSec.setTypeface(romSec.getTypeface(), android.graphics.Typeface.BOLD);
        romSec.setPadding(dp(4), dp(14), 0, dp(6));
        col.addView(romSec);

        LinearLayout romCard = new LinearLayout(this);
        romCard.setOrientation(LinearLayout.VERTICAL);
        romCard.setBackgroundColor(CARD);
        col.addView(romCard, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(6)));
        romCard.addView(actionRow("롬 폴더 주소 복사",
                "PC 연결이나 파일 앱에서 붙여넣어 찾아가기", new View.OnClickListener() {
            @Override public void onClick(View v) { RomImport.copyPath(SettingsActivity.this); }
        }));
        romCard.addView(divider());
        romCard.addView(actionRow("파일 골라 가져오기",
                "파일 선택기에서 롬을 고르면 앱 폴더로 복사합니다 (여러 개 가능)",
                new View.OnClickListener() {
            @Override public void onClick(View v) { RomImport.pick(SettingsActivity.this); }
        }));
        romCard.addView(divider());
        romCard.addView(actionRow("저장소에서 롬 스캔",
                "기기 저장소를 훑어 .ngc/.ngp 를 찾아 모아옵니다", new View.OnClickListener() {
            @Override public void onClick(View v) { RomImport.scan(SettingsActivity.this); }
        }));

        TextView foot = new TextView(this);
        foot.setText("파일로도 고칠 수 있습니다\n" + MainActivity.optsFile().getAbsolutePath()
                + "\n\n롬 폴더: " + MainActivity.romsDir().getAbsolutePath()
                + "\n음성·효과 팩: " + MainActivity.sysDir().getAbsolutePath());
        foot.setTextColor(DIM); foot.setTextSize(12);
        foot.setPadding(dp(4), dp(22), dp(4), 0);
        col.addView(foot);
        return sv;
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

    @Override protected void onActivityResult(int rc, int res, android.content.Intent data) {
        super.onActivityResult(rc, res, data);
        if (rc == RomImport.REQ_PICK && res == RESULT_OK)
            RomImport.onPicked(this, data);     /* 목록 갱신은 돌아간 런처의 onResume 몫 */
    }

    /** 한 줄 — 누르면 다음 값으로 넘어간다. 값이 둘이면 토글, 여럿이면 순환. */
    private View row(final Settings.Item it) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(it.label);
        name.setTextColor(TXT); name.setTextSize(16);
        top.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView val = new TextView(this);
        val.setTextColor(GOLD); val.setTextSize(15);
        val.setTypeface(val.getTypeface(), android.graphics.Typeface.BOLD);
        top.addView(val);
        row.addView(top);

        if (it.help != null && !it.help.isEmpty()) {
            TextView help = new TextView(this);
            help.setText(it.help);
            help.setTextColor(DIM); help.setTextSize(12);
            help.setPadding(0, dp(4), dp(40), 0);
            row.addView(help);
        }

        final String[] cur = { Settings.get(vals, it) };
        val.setText(it.names[it.indexOf(cur[0])]);

        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int i = (it.indexOf(cur[0]) + 1) % it.vals.length;
                cur[0] = it.vals[i];
                val.setText(it.names[i]);
                Settings.put(it.key, cur[0]);
                vals.put(it.key, cur[0]);
            }
        });
        return row;
    }

    @Override public void onBackPressed() {
        Toast.makeText(this, "저장했습니다 — 게임을 다시 열면 반영됩니다",
                Toast.LENGTH_SHORT).show();
        super.onBackPressed();
    }
}
