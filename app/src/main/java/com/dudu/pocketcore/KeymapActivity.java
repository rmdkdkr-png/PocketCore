package com.dudu.pocketcore;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 물리 패드 매핑 — 기능 줄을 누른 뒤 패드 버튼을 누르면 그 키가 배정된다.
 *  게임기(안드로이드 핸드헬드) 지원 요청(2026-09-04). 저장은 options.txt 의 pocketcore_keymap. */
public class KeymapActivity extends Activity {
    private static final int BG = 0xff0f1216, CARD = 0xff181d24, TXT = 0xffe8ecf0, DIM = 0xff8a94a0,
                             GOLD = 0xffe6c25a, WAIT = 0xff2a3a55;
    private KeyMap km;
    private String waiting = null;            /* 지금 배정 대기 중인 기능 id */
    private final java.util.Map<String, TextView> vals = new java.util.HashMap<>();
    private final java.util.Map<String, LinearLayout> rows = new java.util.HashMap<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Orient.apply(this);
        km = KeyMap.load();
        setContentView(build());
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(18), dp(14), dp(24));
        sv.addView(col);

        TextView title = new TextView(this);
        title.setText("물리 패드 매핑");
        title.setTextColor(TXT); title.setTextSize(20);
        col.addView(title);
        TextView help = new TextView(this);
        help.setText("줄을 누른 뒤 패드 버튼을 누르면 배정됩니다. 십자키는 스틱·HAT 로도 자동 인식됩니다.\n"
                   + "같은 버튼을 두 기능에 두면 위쪽 기능이 이깁니다. 「앱 메뉴 열기」는 게임 중 터치 메뉴 알약을 여닫습니다.");
        help.setTextColor(DIM); help.setTextSize(12);
        help.setPadding(0, dp(6), 0, dp(12));
        col.addView(help);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD);
        col.addView(card);
        for (Object[] f : KeyMap.FUNCS) {
            final String id = (String) f[0];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText((String) f[1]); name.setTextColor(TXT); name.setTextSize(15);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView val = new TextView(this);
            val.setTextColor(GOLD); val.setTextSize(14);
            row.addView(val);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setWaiting(id); }
            });
            card.addView(row);
            View line = new View(this); line.setBackgroundColor(0xff262d36);
            card.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            vals.put(id, val); rows.put(id, row);
        }
        refresh();

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(14), 0, 0);
        col.addView(btns);
        btns.addView(button("기본값으로", new View.OnClickListener() {
            @Override public void onClick(View v) { km.reset(); km.save(); waiting = null; refresh(); }
        }));
        btns.addView(button("닫기", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }));
        return sv;
    }

    private View button(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextColor(TXT); t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(CARD);
        t.setPadding(dp(18), dp(12), dp(18), dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        t.setLayoutParams(p);
        t.setOnClickListener(l);
        return t;
    }

    private void setWaiting(String id) {
        waiting = id;
        refresh();
    }

    private void refresh() {
        for (Object[] f : KeyMap.FUNCS) {
            String id = (String) f[0];
            TextView v = vals.get(id); LinearLayout r = rows.get(id);
            if (v == null || r == null) continue;
            boolean w = id.equals(waiting);
            v.setText(w ? "버튼을 누르세요…" : KeyMap.keyName(km.codeOf(id)));
            v.setTextColor(w ? Color.WHITE : GOLD);
            r.setBackgroundColor(w ? WAIT : CARD);
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (waiting != null && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK) { waiting = null; refresh(); return true; }
            if (KeyMap.isGamepad(e) || code >= KeyEvent.KEYCODE_BUTTON_A) {
                km.set(waiting, code); km.save();
                waiting = null; refresh();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }
}
