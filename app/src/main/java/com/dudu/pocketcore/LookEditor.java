package com.dudu.pocketcore;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * «컬러 만들기» 창 — 사무쇼2 캐릭터·검질 고르기에서 OPTION 길게(또는 멈춤 메뉴 «컬러 만들기»)로 연다(native look.c 가 만들기 요청을 센다).
 *
 * 세 색(밝은 색 = 살·하이라이트 · 옷 색 = 옷 주색 · 테 색 = 테·어두운 색)을 12비트(0x0BGR, 채널 0~15)로 고른다. 게임은 멈추지 않는다 —
 * 멈추면 미리보기 그림이 다시 안 그려진다. 바꿀 때마다 Host.preview 로 게임 화면 가운데 미리보기가 바로 그 색이 되고(look_edit),
 * 창이 열린 동안 look 이 게임 입력을 모두 막는다(고르기 화면엔 시간 제한이 없다 — 실측 4000프레임).
 *
 * 엄지로 쓰게 칸을 크게: 세 색을 한 번에 막대 아홉 개로 펴면 덮개 화면·가로 화면에 안 들어가서, 위에 세 색 견본(누르면 그 색을 고침) +
 * 아래 빨강·초록·파랑 막대 셋(−/+ 단추로 한 칸씩)을 둔다. 시작 색은 16가지(+ 만들어 둔 내 색) 중 하나 — 처음엔 지금 보이는 색.
 */
final class LookEditor extends LinearLayout {
    interface Host {
        void preview(int[] c3);
        void save(int k, int[] c3);
        void cancel();
    }

    private static final int BG = 0xf2140d08, GOLD = 0xffd9a441, TITLE = 0xffffd76a, TXT = 0xfff3ead7, DIM = 0xff8a7a5c, WARN = 0xffff8a65;
    private static final String[] PART = { "밝은 색", "옷 색", "테 색" };
    private static final String[] CH = { "빨강", "초록", "파랑" };

    private final Host host;
    private final Typeface font;
    private final float dp;
    private final int[] tri;                 /* nativeLookTriples — 18색 × 세 색 */
    private final boolean[] usable = new boolean[Look.NIDX];
    private final int[] cur = new int[3];
    private int part = 1, start;
    private final TextView startName, warn;
    private final TextView[] swatch = new TextView[3];
    private final SeekBar[] bar = new SeekBar[3];
    private final TextView[] val = new TextView[3];
    private boolean syncing = false;

    /** ch·sty = 고르기 화면의 캐릭터·검질, tri = 그 캐릭터·검질의 18색, startIdx = 지금 보이는 컬러, cus = 그 캐릭터의 내 색 1·2(없으면 null) */
    LookEditor(Context c, Typeface font, String charName, int[] tri, int startIdx, int[][] cus, Host host) {
        super(c);
        this.host = host; this.font = font; this.tri = tri;
        dp = c.getResources().getDisplayMetrics().density;
        for (int i = 0; i < Look.NIDX; i++) usable[i] = i < Look.NPRE || (cus != null && cus[i - Look.NPRE] != null);
        usable[1] = false;                                        /* 165: 옛 2P색은 시작 색으로도 안 보인다(플레이어가 안 고르는 번호) */
        start = startIdx >= 0 && startIdx < Look.NIDX && usable[startIdx] ? startIdx : 0;
        setOrientation(VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(BG); g.setStroke(Math.round(3 * dp), GOLD); g.setCornerRadius(6 * dp);
        setBackground(g);
        setPadding(px(12), px(8), px(12), px(10));
        setClickable(true);                                       /* 창 위 누름이 아래 패드로 새지 않게 */

        TextView t = text("컬러 만들기 — " + charName, 17, TITLE);
        t.setGravity(Gravity.CENTER);
        addView(t, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout row = hrow();                                 /* 처음 색: ◀ 쪽빛 ▶ */
        row.addView(text("처음 색:", 14, DIM));
        row.addView(pill("◀", () -> stepStart(-1)), btnLp(0));
        startName = text("", 15, TXT);
        startName.setGravity(Gravity.CENTER);
        row.addView(startName, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(pill("▶", () -> stepStart(1)), btnLp(0));
        addView(row, rowLp());

        LinearLayout sw = hrow();                                  /* 세 색 견본 — 누르면 그 색을 고친다 */
        for (int i = 0; i < 3; i++) {
            final int k = i;
            TextView s = text(PART[i], 14, TXT);
            s.setGravity(Gravity.CENTER);
            s.setMinHeight(px(48));
            s.setShadowLayer(2 * dp, 0, 0, 0xff000000);            /* 밝은 견본 위에서도 글자가 읽히게 */
            s.setOnClickListener(v -> { part = k; sync(); });
            swatch[i] = s;
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = px(3); lp.rightMargin = px(3);
            sw.addView(s, lp);
        }
        addView(sw, rowLp());

        for (int i = 0; i < 3; i++) {                              /* 빨강·초록·파랑 0~15 */
            final int k = i;
            LinearLayout r = hrow();
            TextView l = text(CH[i], 14, TXT);
            l.setMinWidth(px(44));
            r.addView(l);
            r.addView(pill("−", () -> nudge(k, -1)), btnLp(0));
            SeekBar b = new SeekBar(c);
            b.setMax(15);
            int tint = i == 0 ? 0xffe05050 : i == 1 ? 0xff50c060 : 0xff5080e0;
            b.setProgressTintList(ColorStateList.valueOf(tint));
            b.setThumbTintList(ColorStateList.valueOf(GOLD));
            b.setMinimumHeight(px(44));
            b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && !syncing) setCh(k, p); }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            bar[i] = b;
            r.addView(b, new LayoutParams(0, px(44), 1f));
            r.addView(pill("+", () -> nudge(k, 1)), btnLp(0));
            TextView v = text("0", 14, TXT);
            v.setMinWidth(px(26)); v.setGravity(Gravity.CENTER);
            val[i] = v;
            r.addView(v);
            addView(r, rowLp());
        }

        warn = text("세 색이 너무 비슷해요", 13, WARN);
        warn.setGravity(Gravity.CENTER);
        warn.setVisibility(View.INVISIBLE);                        /* 자리는 지킨다 — 나타날 때 창 높이가 출렁이지 않게 */
        addView(warn, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout bt = hrow();
        bt.setGravity(Gravity.CENTER);
        bt.addView(pill("내 색 1에 저장", () -> host.save(0, cur.clone())), btnLp(1));
        bt.addView(pill("내 색 2에 저장", () -> host.save(1, cur.clone())), btnLp(1));
        bt.addView(pill("취소", host::cancel), btnLp(0));
        addView(bt, rowLp());

        loadStart();
    }

    private void stepStart(int d) {
        for (int s = 1; s <= Look.NIDX; s++) {
            int i = ((start + d * s) % Look.NIDX + Look.NIDX) % Look.NIDX;
            if (usable[i]) { start = i; break; }
        }
        loadStart();
    }
    private void loadStart() {
        for (int j = 0; j < 3; j++) cur[j] = tri[start * 3 + j] & 0xFFF;
        startName.setText(Look.NAMES[start]);
        sync();
        host.preview(cur.clone());
    }
    /** 고르는 색의 채널 k(0 빨강 · 1 초록 · 2 파랑 — 0x0BGR 의 아래부터 4비트씩) */
    private int chOf(int c, int k) { return (c >> (4 * k)) & 15; }
    private void setCh(int k, int v) {
        v = Math.max(0, Math.min(15, v));
        cur[part] = (cur[part] & ~(15 << (4 * k))) | (v << (4 * k));
        sync();
        host.preview(cur.clone());
    }
    private void nudge(int k, int d) { setCh(k, chOf(cur[part], k) + d); }
    private void sync() {
        syncing = true;
        for (int i = 0; i < 3; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(Look.argb(cur[i])); g.setCornerRadius(4 * dp);
            g.setStroke(Math.round((i == part ? 3 : 1) * dp), i == part ? GOLD : DIM);
            swatch[i].setBackground(g);
            swatch[i].setTextColor(i == part ? TITLE : TXT);
        }
        for (int k = 0; k < 3; k++) { int v = chOf(cur[part], k); bar[k].setProgress(v); val[k].setText(String.valueOf(v)); }
        warn.setVisibility(Look.tooClose(cur) ? View.VISIBLE : View.INVISIBLE);
        syncing = false;
    }

    private int px(float v) { return Math.round(v * dp); }
    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(getContext());
        t.setTypeface(font); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        return t;
    }
    private LinearLayout hrow() {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }
    private LayoutParams rowLp() {
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(4);
        return lp;
    }
    private LayoutParams btnLp(float w) {
        LayoutParams lp = new LayoutParams(w > 0 ? 0 : LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, w);
        lp.leftMargin = px(3); lp.rightMargin = px(3);
        return lp;
    }
    /** 금빛 단추 — 결과 카드 단추(hubPill)와 같은 모습, 누르는 칸 48dp */
    private TextView pill(String s, final Runnable r) {
        TextView t = text(s, 14, 0xff140d08);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(GOLD); g.setCornerRadius(4 * dp);
        t.setBackground(g);
        t.setPadding(px(10), px(6), px(10), px(6));
        t.setMinHeight(px(48)); t.setMinWidth(px(44));
        t.setClickable(true);
        t.setOnClickListener(v -> r.run());
        return t;
    }
}
