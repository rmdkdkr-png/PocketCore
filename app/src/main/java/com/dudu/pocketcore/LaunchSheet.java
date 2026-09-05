package com.dudu.pocketcore;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 실행 전 패치 선택창 (유저 요청 2026-09-04: 「처음 선택창에서 클릭하면 각각 적용 패치 선택 가능하게,
 * 어색하지 않은 메뉴창을 거쳐 확인키 누르면서 진행, 게임별로」).
 *
 * 런처에서 카드를 누르면 바로 실행하지 않고 이 창이 뜬다. 그 게임에 붙는 것만 보인다:
 *   한글패치(게임별 선택 — pocketcore_lang_<id>) → 조작 패치(mods) → 엔진 토글(Settings.Item.launch 표시된 것).
 * 값은 options.txt 에 그대로 쓴다(설정 화면과 같은 저장소) — 다음에 열어도 그대로고, 설정 화면과도 어긋나지 않는다.
 *
 * 패드: 십자 위아래 = 이동, A(펀치 자리)·DPAD_CENTER·ENTER·START = 바꾸기/시작, B·BACK = 닫기.
 * 처음 커서는 「시작」에 있다 — 확인 한 번이면 그대로 들어간다.
 */
public final class LaunchSheet {

    private static final int CARD = 0xff191b22, LINE = 0xff2a2f3b, TXT = 0xffe6e8ee, DIM = 0xff8b93a6,
                             GOLD = 0xffd9a441, INK = 0xff101014, FOCUS = 0x33d9a441, BTN2 = 0xff23262f;

    /** 한 줄. kind: 0 = 한글패치(언어 키를 게임별로), 1 = 일반 Item(options.txt 키 그대로). */
    private static final class Opt {
        String key, label, help; String[] vals, names; String cur; boolean enabled = true; int kind;
        Games.Game g;
        int idx() { for (int i = 0; i < vals.length; i++) if (vals[i].equals(cur)) return i; return 0; }
        String name() { return enabled ? names[idx()] : "없음"; }
    }

    private final Activity a; private final Games.Game g; private final Runnable onStart;
    private final List<Opt> opts = new ArrayList<>();
    private final List<View> rowViews = new ArrayList<>();
    private final List<TextView> valViews = new ArrayList<>();
    private View startBtn;
    private int focus;            /* 0..opts.size()-1 = 줄, opts.size() = 시작 버튼 */
    private Dialog dlg;
    private KeyMap keymap;

    public static LaunchSheet show(Activity a, File rom, Games.Game g, Bitmap thumb, String title, Runnable onStart) {
        LaunchSheet s = new LaunchSheet(a, g, onStart);
        s.open(rom, thumb, title);
        return s;
    }
    /** 떠 있는가 — 런처가 패드 키를 이 창으로 넘길지 판단하는 데 쓴다. */
    public boolean isShowing() { return dlg != null && dlg.isShowing(); }
    /** 런처(Activity)가 받은 키를 이 창이 처리한다. 창이 윈도우 포커스를 못 받는 환경(실측: 에뮬 + 풀스크린 액티비티)에서도
     *  패드가 창을 조작하게 — 창이 포커스를 받았으면 Dialog 의 OnKeyListener 가 같은 함수를 부르므로 이중 처리는 없다(창 둘 중 하나만 키를 받는다). */
    public boolean handleKey(KeyEvent e) { return key(e.getKeyCode(), e); }

    private LaunchSheet(Activity a, Games.Game g, Runnable onStart) {
        this.a = a; this.g = g; this.onStart = onStart;
        buildOpts();
        focus = opts.size();
        try { keymap = KeyMap.load(); } catch (Exception ignored) { keymap = null; }
    }

    /* ── 항목 ─────────────────────────────────────────────────────── */

    private void buildOpts() {
        Map<String, String> m = Settings.load();
        if (g != null && g.patchable) {                       /* ① 한글패치 — 게임별 */
            Opt o = new Opt(); o.kind = 0; o.g = g;
            o.key = "pocketcore_lang_" + g.id;
            String ver = readText(new File(new File(MainActivity.root(), "patch"), g.id + "_ko.ver"));
            o.label = "한글패치" + (ver != null && !ver.isEmpty() ? "  " + ver : "");
            o.vals = new String[]{ "on", "off" }; o.names = new String[]{ "적용", "안 함" };
            String lang = m.get(o.key); if (lang == null) lang = m.get("pocketcore_lang"); if (lang == null) lang = "ko-ja";
            o.cur = lang.startsWith("ko") ? "on" : "off";
            boolean have = new File(new File(MainActivity.root(), "patch"), g.id + "_ko.ips").exists()
                        || assetExists("patch/" + g.id + "_ko.ips");
            o.enabled = have;
            o.help = have ? "원본 롬은 두고 사본에 입힙니다. 안 함이면 " + ("en".equals(offLang(m, g)) ? "영어" : "일본어") + " 원판으로 엽니다."
                          : "패치 파일이 아직 없습니다 — 런처의 「업데이트 확인」으로 받으면 켤 수 있습니다.";
            opts.add(o);
        }
        if (g != null) {                                       /* ② 조작 패치(mods) — 그 게임 것만 */
            for (Settings.Item it : Settings.modItems()) if (g.id.equals(it.game)) opts.add(fromItem(it, m));
            /* ③ 엔진 토글 — launch 표시된 항목 중 이 게임 것 */
            for (Settings.Item[] arr : Settings.GROUPS.values())
                for (Settings.Item it : arr)
                    if (it.launch && ((it.feature != null && g.has(it.feature)) || (it.game != null && g.id.equals(it.game))))
                        opts.add(fromItem(it, m));
        }
    }
    /** 「안 함」이 실제로 쓰는 언어 값 — 전역이 원어(ja/en)면 그대로, 한국어면 이 게임 바탕의 원어. 도움말과 toggle 이 같이 쓴다. */
    private static String offLang(Map<String, String> m, Games.Game g) {
        String global = m.get("pocketcore_lang"); if (global == null) global = "ko-ja";
        boolean enBase = "english".equals(g.baseLang);
        return !global.startsWith("ko") ? global : (enBase ? "en" : "ja");
    }
    private static String onLang(Map<String, String> m, Games.Game g) {
        String global = m.get("pocketcore_lang"); if (global == null) global = "ko-ja";
        return global.startsWith("ko") ? global : ("english".equals(g.baseLang) ? "ko-en" : "ko-ja");
    }
    private static Opt fromItem(Settings.Item it, Map<String, String> m) {
        Opt o = new Opt(); o.kind = 1; o.key = it.key; o.label = it.label; o.help = it.help;
        o.vals = it.vals; o.names = it.names; o.cur = it.vals[it.indexOf(Settings.get(m, it))];   /* 미등록 값 → 항목 기본값(설정 화면과 동일) */
        return o;
    }

    /** 값 바꾸기 — 즉시 options.txt 에. 한글패치 줄은 언어 값으로 번역해 게임별 키에 쓴다. */
    private void toggle(int i) {
        Opt o = opts.get(i);
        if (!o.enabled) return;
        o.cur = o.vals[(o.idx() + 1) % o.vals.length];
        if (o.kind == 0) {
            Map<String, String> m = Settings.load();
            Settings.put(o.key, "on".equals(o.cur) ? onLang(m, o.g) : offLang(m, o.g));
        } else Settings.put(o.key, o.cur);
        valViews.get(i).setText(o.name());
        a.getWindow().getDecorView().performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
    }

    /* ── 화면 ─────────────────────────────────────────────────────── */

    private void open(File rom, Bitmap thumb, String title) {
        dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(build(rom, thumb, title));
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.62f);
            w.getDecorView().setSystemUiVisibility(a.getWindow().getDecorView().getSystemUiVisibility());
        }
        dlg.setCanceledOnTouchOutside(true);
        dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface d, int code, KeyEvent e) { return key(code, e); }
        });
        dlg.show();
        paintFocus();
    }

    private View build(File rom, Bitmap thumb, String title) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        float r = dp(18);
        bg.setCornerRadii(new float[]{ r, r, r, r, 0, 0, 0, 0 });
        col.setBackground(bg);
        col.setPadding(dp(18), dp(14), dp(18), dp(14));

        /* 손잡이 */
        View grip = new View(a);
        GradientDrawable gd = new GradientDrawable(); gd.setColor(0xff3a3f4d); gd.setCornerRadius(dp(3));
        grip.setBackground(gd);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(40), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL; gp.bottomMargin = dp(12);
        col.addView(grip, gp);

        /* 머리: 썸네일 + 제목 + 안내 */
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (thumb != null) {
            ImageView iv = new ImageView(a);
            iv.setImageBitmap(thumb);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(64), dp(61));
            ip.rightMargin = dp(12);
            head.addView(iv, ip);
        }
        LinearLayout tt = new LinearLayout(a);
        tt.setOrientation(LinearLayout.VERTICAL);
        TextView h = new TextView(a);
        h.setText(title); h.setTextColor(TXT); h.setTextSize(19);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        tt.addView(h);
        TextView s = new TextView(a);
        s.setText(opts.isEmpty() ? "이 롬에 붙는 패치가 없습니다 — 「시작」을 누르면 순정 그대로 엽니다."
                                 : "실행 전에 적용할 것을 고르세요. 선택은 이 게임에 저장됩니다.");
        s.setTextColor(DIM); s.setTextSize(12); s.setPadding(0, dp(2), 0, 0);
        tt.addView(s);
        head.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(head);

        /* 줄들 — 화면보다 길면 안에서 스크롤 */
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < opts.size(); i++) {
            if (i > 0) { View dv = new View(a); dv.setBackgroundColor(LINE);
                         list.addView(dv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)); }
            list.addView(row(i));
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(list);
        sv.setVerticalScrollBarEnabled(false);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.50f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        col.addView(sv, sp);
        sv.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (sv.getHeight() > maxH) { sv.getLayoutParams().height = maxH; sv.requestLayout(); }
            }
        });

        /* 버튼: 취소 | 시작 */
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = pill("취소", BTN2, DIM, false);
        cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } });
        TextView start = pill("시작  ▶", GOLD, INK, true);
        start.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { go(); } });
        startBtn = start;
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        cp.rightMargin = dp(10); cp.topMargin = stp.topMargin = dp(14);
        btns.addView(cancel, cp); btns.addView(start, stp);
        col.addView(btns);

        TextView hint = new TextView(a);
        hint.setText("패드: 위아래 이동 · 펀치 버튼 바꾸기/시작 · 킥 버튼 닫기");
        hint.setTextColor(0xff5c6478); hint.setTextSize(11); hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        col.addView(hint);
        return col;
    }

    private View row(final int i) {
        final Opt o = opts.get(i);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(a);
        name.setText(o.label); name.setTextColor(o.enabled ? TXT : DIM); name.setTextSize(16);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(a);
        val.setText(o.name()); val.setTextColor(o.enabled ? GOLD : DIM); val.setTextSize(15);
        val.setTypeface(val.getTypeface(), android.graphics.Typeface.BOLD);
        top.addView(val);
        row.addView(top);
        if (o.help != null && !o.help.isEmpty()) {
            TextView help = new TextView(a);
            help.setText(o.help.replace(" 게임을 다시 열면 적용됩니다.", "").replace(" 게임을 다시 열면 적용.", ""));
            help.setTextColor(DIM); help.setTextSize(12);
            help.setMaxLines(3); help.setEllipsize(android.text.TextUtils.TruncateAt.END);
            help.setPadding(0, dp(3), dp(24), 0);
            row.addView(help);
        }
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { focus = i; paintFocus(); toggle(i); }
        });
        rowViews.add(row); valViews.add(val);
        return row;
    }

    private TextView pill(String text, int bg, int fg, boolean bold) {
        TextView t = new TextView(a);
        t.setText(text); t.setTextColor(fg); t.setTextSize(17); t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(14), 0, dp(14));
        GradientDrawable d = new GradientDrawable(); d.setColor(bg); d.setCornerRadius(dp(12));
        t.setBackground(d);
        t.setClickable(true);
        return t;
    }

    private void paintFocus() {
        for (int i = 0; i < rowViews.size(); i++) rowViews.get(i).setBackgroundColor(i == focus ? FOCUS : Color.TRANSPARENT);
        if (startBtn != null) {
            GradientDrawable d = new GradientDrawable(); d.setColor(GOLD); d.setCornerRadius(dp(12));
            if (focus == opts.size()) d.setStroke(dp(2), 0xffffe9b0);
            startBtn.setBackground(d);
        }
    }

    private void go() {
        try { dlg.dismiss(); } catch (Exception ignored) { }
        onStart.run();
    }

    /* ── 패드 ─────────────────────────────────────────────────────── */

    private boolean key(int code, KeyEvent e) {
        /* 패드 버튼은 **KeyMap 기능**으로만 읽는다(펀치 자리 b=확인, 킥 자리 a=취소 — 게임 안 유틸 바와 같은 규칙).
           날 키코드 BUTTON_A/B 를 폴백으로 두면 기능과 반대로 걸려 충돌한다(실측). 날 폴백은 기능이 없는 DPAD·ENTER·BACK·START 만. */
        String f = (keymap != null && KeyMap.isGamepad(e)) ? keymap.funcOf(code) : null;
        boolean padBtn = KeyMap.isPadButton(code);
        boolean up    = "up".equals(f)    || (!padBtn && code == KeyEvent.KEYCODE_DPAD_UP);
        boolean down  = "down".equals(f)  || (!padBtn && code == KeyEvent.KEYCODE_DPAD_DOWN);
        boolean ok    = "b".equals(f) || "start".equals(f)
                     || (!padBtn && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER))
                     || (f == null && code == KeyEvent.KEYCODE_BUTTON_START);
        boolean back  = "a".equals(f) || (!padBtn && code == KeyEvent.KEYCODE_BACK);
        if (!(up || down || ok || back)) return padBtn;            /* 배정 없는 패드 버튼은 삼킨다 — 뒤 창으로 안 샌다 */
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;   /* UP 은 삼킨다 — 게임 화면으로 새지 않게 */
        if (e.getRepeatCount() > 0 && (ok || back)) return true;
        int n = opts.size();
        if (up)   { focus = (focus - 1 + n + 1) % (n + 1); paintFocus(); }
        else if (down) { focus = (focus + 1) % (n + 1); paintFocus(); }
        else if (ok) { if (focus >= n) go(); else toggle(focus); }
        else dlg.dismiss();
        return true;
    }

    /** 스틱·HAT 십자로 오는 방향(-1 위 / +1 아래). 축은 MotionEvent 라 key() 를 못 탄다 —
     *  MainActivity.onGenericMotionEvent 가 여기로 넘겨 준다. */
    public void moveFocus(int d) {
        int n = opts.size();
        focus = (focus + d + n + 1) % (n + 1);
        paintFocus();
    }

    /** 축 쪽에서 온 확인 — key() 의 ok 갈래와 같은 일을 한다. */
    public void confirm() {
        if (focus >= opts.size()) go(); else toggle(focus);
    }

    /* ── 잡동사니 ───────────────────────────────────────────────── */

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }
    private boolean assetExists(String path) {
        try (java.io.InputStream in = a.getAssets().open(path)) { return true; } catch (Exception e) { return false; }
    }
    private static String readText(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 64)];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8").trim() : null;
        } catch (Exception e) { return null; }
    }
}
