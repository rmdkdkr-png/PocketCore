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
 * 패드: 십자 위아래 = 이동, 좌우 = 값 한 칸(135), A(펀치 자리)·DPAD_CENTER·ENTER·START = 바꾸기/시작, B·BACK = 닫기.
 * 처음 커서는 「시작」에 있다 — 확인 한 번이면 그대로 들어간다.
 *
 * 135(접근성 검수): 커서는 이 창이 그리는 것 하나뿐 — 안드로이드 포커스는 창 안 어디에도 안 간다(FOCUS_BLOCK_DESCENDANTS).
 * 전엔 줄·버튼이 눌리는 뷰라 포커스를 받을 수 있어, 화면을 만진 뒤 첫 십자 입력을 안드로이드가 «터치 모드 빠져나가기»로
 * 먹고 커서 둘이 따로 놀았다. 아래 글 고리(앱 전체 설정·연습 대전·리그)도 커서에 넣었다(전엔 패드로 못 갔다).
 */
public final class LaunchSheet {

    /* 121: 색·글꼴은 테마(Theme)에서 — 이름은 예전 그대로(GOLD = 강조색, INK = 강조색 위 글자) */
    private final Theme th;
    private final int CARD, LINE, TXT, DIM, GOLD, INK, FOCUS, BTN2;

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
    private final java.util.HashMap<Integer, DialBar> dials = new java.util.HashMap<>();
    private final List<TextView> valViews = new ArrayList<>();
    private final List<TextView> helpViews = new ArrayList<>();   /* 줄마다 설명(없으면 null) — 커서 줄은 끝까지 펼친다(135) */
    private final List<View> linkViews = new ArrayList<>();       /* 아래 글 고리 — 패드 커서 순서: 줄들 → 시작 → 고리들(135) */
    private final List<Runnable> linkActs = new ArrayList<>();
    private View startBtn;
    private String startLabel = "시작  ▶", subtitle = null;   /* 게임 안 모드에서 바뀐다 */
    private Runnable onSettings = null;                          /* 게임 안 모드: 「앱 전체 설정 열기」 */
    private int focus;            /* 0..opts.size()-1 = 줄, opts.size() = 시작 버튼, 그 뒤 = 아래 글 고리 */
    /* 135: 커서 테는 패드·키를 쓸 때만 그린다(패드가 붙어 있거나 키가 한 번이라도 오면) — 손가락만 쓰면 테 없이 깔끔하게 */
    private boolean padMode = KeyMap.physicalPresent();
    private Dialog dlg;
    /* 139: 창은 화면 전체(투명) 하나로 고정하고 시트는 그 안 아래에 — 치트 창으로 갈아 끼우거나 설명이 펼쳐져 시트 높이가 바뀌어도
       창 크기·자리는 그대로다. 예전엔 아래 붙은 WRAP_CONTENT 창이라 높이가 바뀌면 창이 옮겨 가며 안드로이드 9 의 창 이동 애니메이션이 돌았고,
       그 끝에서 SurfaceFlinger 가 멈춰 시스템이 통째로 재시작했다(에뮬레이터 API 28 · «치트 ›» 누를 때마다, 런처·게임 안 모두). */
    private android.widget.FrameLayout frame;
    private KeyMap keymap;
    /* 치트 창(사무쇼2) — 같은 창 안에서 내용만 갈아 끼운다: 런처·게임이 쥔 이 창 하나로 패드 키가 그대로 오고(창을 따로 띄우면 키를 받는 창이 갈린다),
       닫기·바깥 탭의 onClose(게임 안: 멈춤 풀기 + 치트 넣기)도 그대로 돈다. 겉 창으로 돌아올 때 다시 그리려고 롬·썸네일·제목을 둔다 */
    private boolean cheatView = false, inGame = false, cheatsChanged = false;
    private File romFile; private Bitmap thumbBmp; private String titleText;
    private int cheatLink = -1;                                  /* 겉 창에서 «치트 ›» 고리의 자리(linkViews) — 돌아올 때 커서를 거기로 */
    /** 게임 안: 치트 세이브 보관에 쓸 롬(원래 경로 — 사본도 이름이 같다) · 이 롬 판에서 치트가 안 켜지면 그 글(치트 창 부제). EmuActivity 가 창을 연 뒤 넣는다 */
    public File cheatRom; public String cheatOff;

    public static LaunchSheet show(Activity a, File rom, Games.Game g, Bitmap thumb, String title, Runnable onStart) {
        LaunchSheet s = new LaunchSheet(a, g, onStart);
        s.open(rom, thumb, title);
        return s;
    }
    /** 게임 안에서 — 같은 창, 「적용하고 이어하기」. 지금 자리를 저장하고 옵션대로 다시 구워 그 자리에서 이어간다(유저 2026-09-05). */
    public static LaunchSheet showInGame(Activity a, Games.Game g, String title, Runnable onApply, Runnable onSettings) {
        LaunchSheet s = new LaunchSheet(a, g, onApply);
        s.startLabel = "적용하고 이어하기  ▶";
        s.subtitle = "바꾸면 지금 자리를 저장하고 다시 열어 그대로 이어갑니다.";
        s.onSettings = onSettings;
        s.inGame = true;
        s.open(null, null, title);
        return s;
    }
    /** 런처 카드 아래 한 줄 — 이 게임에 지금 걸린 실행 옵션. 창을 열지 않아도 무엇으로 켜질지 보이게(2026-10-11).
     *  켜진 것만 짧게: 「한글 · 원버튼 · SS1 음악 · 코어 보간 자동」. */
    public static String summary(Activity a, Games.Game g) {
        if (g == null) return "";
        LaunchSheet s = new LaunchSheet(a, g, null);
        List<String> parts = new ArrayList<>();
        for (Opt o : s.opts) {
            if (!o.enabled) continue;
            if (o.kind == 0) { parts.add("on".equals(o.cur) ? "한글" : "원판"); continue; }
            String v = o.names[o.idx()];
            if ("pocketcore_ss2_music".equals(o.key)) {
                parts.add("mute".equals(o.cur) ? "배경음 끔" : "off".equals(o.cur) ? "SS2 음악" : v);
                continue;
            }
            if ("off".equals(o.cur) || "disabled".equals(o.cur)) continue;
            parts.add(o.vals.length == 2 ? o.label : o.label + " " + v);
        }
        if (Cheats.GAME.equals(g.id) && Cheats.any(Settings.load())) parts.add("치트");   /* 켜진 치트가 있으면 한 낱말 */
        return android.text.TextUtils.join(" · ", parts);
    }
    /** 떠 있는가 — 런처가 패드 키를 이 창으로 넘길지 판단하는 데 쓴다. */
    public boolean isShowing() { return dlg != null && dlg.isShowing(); }
    /** 창이 닫힐 때(취소·바깥 탭·적용 모두) — 게임 안에서는 멈춘 코어를 다시 돌린다. */
    public Runnable onClose;
    /** 런처(Activity)가 받은 키를 이 창이 처리한다. 창이 윈도우 포커스를 못 받는 환경(실측: 에뮬 + 풀스크린 액티비티)에서도
     *  패드가 창을 조작하게 — 창이 포커스를 받았으면 Dialog 의 OnKeyListener 가 같은 함수를 부르므로 이중 처리는 없다(창 둘 중 하나만 키를 받는다). */
    public boolean handleKey(KeyEvent e) { return key(e.getKeyCode(), e); }

    private LaunchSheet(Activity a, Games.Game g, Runnable onStart) {
        this.a = a; this.g = g; this.onStart = onStart;
        th = Theme.get();
        CARD = th.card; LINE = th.line; TXT = th.txt; DIM = th.dim; GOLD = th.accent; INK = th.onAccent; FOCUS = th.focus; BTN2 = th.btn2;
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
            String lang = m.get(o.key); if (lang == null) lang = m.get("pocketcore_lang"); if (lang == null) lang = "ko";
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
            /* ④ 모든 게임 공통 — launch 표시된 범용 항목(프레임 생성·버튼 모양). 게임 안 「설정」에서 바로 닿게 */
            for (Settings.Item[] arr : Settings.GROUPS.values())
                for (Settings.Item it : arr)
                    if (it.launch && it.feature == null && it.game == null) opts.add(fromItem(it, m));
        }
    }
    /** 「안 함」이 실제로 쓰는 언어 값 — 전역이 원어(ja/en)면 그대로, 한국어면 이 게임 바탕의 원어. 도움말과 toggle 이 같이 쓴다. */
    private static String offLang(Map<String, String> m, Games.Game g) {
        String global = m.get("pocketcore_lang"); if (global == null) global = "ko";
        boolean enBase = "english".equals(g.baseLang);
        return !global.startsWith("ko") ? global : (enBase ? "en" : "ja");
    }
    private static String onLang(Map<String, String> m, Games.Game g) {
        String global = m.get("pocketcore_lang"); if (global == null) global = "ko";
        return global.startsWith("ko") ? "ko" : "ko";
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
        setOpt(i, (o.idx() + 1) % o.vals.length);
    }
    private void setOpt(int i, int k) {
        if (!guardCheat(i, k)) applyOpt(i, k);
    }
    private void applyOpt(int i, int k) {
        Opt o = opts.get(i);
        if (!o.enabled) return;
        o.cur = o.vals[k];
        if (o.kind == 0) {
            Map<String, String> m = Settings.load();
            Settings.put(o.key, "on".equals(o.cur) ? onLang(m, o.g) : offLang(m, o.g));
        } else Settings.putUser(o.key, o.cur);   /* 보간 묶음이면 세부 값도 같이 */
        valViews.get(i).setText(o.name());
        DialBar b = dials.get(i); if (b != null) b.setIndex(o.idx());          /* 패드로 돌려도 바가 따라간다 */
        a.getWindow().getDecorView().performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
    }
    /** 135: 패드 좌우 — 커서 줄 값을 한 칸 옮긴다(끝에서 멈춤 — 돌지 않는다). 줄이 아니면 아무것도 안 한다. */
    public void adjust(int d) {
        padOn();
        if (focus < 0 || focus >= opts.size()) return;
        Opt o = opts.get(focus);
        if (!o.enabled) return;
        int k = Math.max(0, Math.min(o.vals.length - 1, o.idx() + d));
        if (k != o.idx()) setOpt(focus, k);
    }

    /* ── 화면 ─────────────────────────────────────────────────────── */

    private void open(File rom, Bitmap thumb, String title) {
        romFile = rom; thumbBmp = thumb; titleText = title;
        dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);   /* 창이 떠 있어도 볼륨 키 = 게임 소리 */
        frame = new android.widget.FrameLayout(a);
        frame.setOnClickListener(new View.OnClickListener() {     /* 시트 바깥(투명한 곳) 탭 = 예전 «바깥 탭 닫기»와 같다 */
            @Override public void onClick(View v) { dlg.cancel(); }
        });
        dlg.setContentView(frame);
        setSheet(build(rom, thumb, title));
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);   /* 139: 창 크기 고정(머리말) */
            w.setDimAmount(0.62f);
            w.getDecorView().setSystemUiVisibility(a.getWindow().getDecorView().getSystemUiVisibility());
        }
        dlg.setCanceledOnTouchOutside(true);
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) { if (onClose != null) onClose.run(); }
        });
        dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface d, int code, KeyEvent e) { return key(code, e); }
        });
        dlg.show();
        paintFocus();
    }

    /** 시트 갈아 끼우기 — 창은 그대로, 틀 안의 내용만(139). 148: 시트는 겉 틀(SheetScroll) 안에 — 화면보다 길면 시트째 스크롤 */
    private void setSheet(View sheet) {
        sheet.setClickable(true);                                   /* 시트 위 탭이 틀(바깥 닫기)로 새지 않게 */
        frame.removeAllViews();
        sheetScroll = new SheetScroll();
        sheetScroll.addView(sheet, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addView(sheetScroll, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    /** 높이 상한이 있는 스크롤 — 재기(onMeasure)에서 바로 자른다. 예전 OnGlobalLayoutListener 는 떼지 않아 창에 쌓였고 그릴 때마다 다시 쟀다(139).
     *  148: 상한(cap)은 시트가 화면보다 길 때 겉 틀이 줄인다 — 잴 때마다 max 로 되돌린 뒤 다시 정한다 */
    private final class CapScroll extends ScrollView {
        final int max; int cap;
        CapScroll(int maxH) { super(a); max = cap = maxH; }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            int mode = View.MeasureSpec.getMode(hSpec), size = View.MeasureSpec.getSize(hSpec);
            if (mode == View.MeasureSpec.UNSPECIFIED || size > cap) hSpec = View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST);
            super.onMeasure(wSpec, hSpec);
        }
    }
    private CapScroll capScroll(int maxH) { CapScroll c = new CapScroll(maxH); listScroll = c; return c; }

    /** 148: 시트 겉 틀. 다 들어가면 147 그대로(스크롤 없음 · 줄 목록 상한 = 화면 반).
     *  넘치면 ① 줄 목록을 한 줄 남짓(LIST_MIN)까지 줄여 맞추고, ② 그래도 넘치면(가로 폰 360dp — 아래 고리가 랭크 매치·순위표 둘로 늘었다)
     *  시트째 스크롤한다. fix 라운드의 weight 는 목록만 깎아 가로 런처에서 줄이 0dp 로 사라졌다(147 은 거꾸로 아래 고리·안내가 눌려 사라졌다).
     *  안드로이드 포커스는 안 받는다(135 — 첫 십자 입력을 «터치 모드 빠져나가기»가 먹지 않게) */
    private final class SheetScroll extends ScrollView {
        SheetScroll() {
            super(a);
            setFocusable(false);
            setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            setVerticalScrollBarEnabled(true);
            setScrollbarFadingEnabled(false);                        /* 넘칠 때만 그려진다 — 아래에 더 있다는 표시 */
        }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            View c = getChildAt(0);
            if (c == null) { super.onMeasure(wSpec, hSpec); return; }
            int w = View.MeasureSpec.getSize(wSpec), avail = View.MeasureSpec.getSize(hSpec);
            boolean bounded = View.MeasureSpec.getMode(hSpec) != View.MeasureSpec.UNSPECIFIED;
            int cw = View.MeasureSpec.makeMeasureSpec(Math.max(0, w - getPaddingLeft() - getPaddingRight()), View.MeasureSpec.EXACTLY);
            int ch = View.MeasureSpec.makeMeasureSpec(bounded ? Math.max(0, avail - getPaddingTop() - getPaddingBottom()) : 0,
                    View.MeasureSpec.UNSPECIFIED);                     /* ScrollView 와 같다 — 시트는 제 키대로 */
            CapScroll l = listScroll != null && listScroll.getParent() == c ? listScroll : null;
            if (l != null && l.cap != l.max) { l.cap = l.max; l.forceLayout(); c.forceLayout(); }   /* 잰 값 캐시를 건너뛰지 않게 */
            c.measure(cw, ch);
            if (bounded && l != null && c.getMeasuredHeight() > avail) {
                int lh = l.getMeasuredHeight();
                int want = Math.max(Math.min(lh, dp(LIST_MIN)), lh - (c.getMeasuredHeight() - avail));
                if (want < lh) { l.cap = want; l.forceLayout(); c.forceLayout(); c.measure(cw, ch); }
            }
            int h = c.getMeasuredHeight() + getPaddingTop() + getPaddingBottom();
            boolean over = bounded && h > avail;
            /* 시트째 스크롤할 때만 — 목록을 끝까지 끌면 남은 만큼 시트가 따라간다(안 그러면 겉 틀이 목록 끌기를 가로챈다).
               들어갈 땐 끈다: 147 처럼 목록 끝 번짐(glow)이 그대로 */
            if (l != null && l.isNestedScrollingEnabled() != over) l.setNestedScrollingEnabled(over);
            setMeasuredDimension(w, over ? avail : h);
        }
        boolean scrolls() { return canScrollVertically(1) || canScrollVertically(-1); }
    }
    private static final int LIST_MIN = 112;                         /* 줄 목록 최소 높이(dp) — 켜고 끄는 줄 하나(약 77dp) + 다음 줄 머리 */
    private SheetScroll sheetScroll;
    private CapScroll listScroll;                                     /* 지금 시트의 줄 목록(겉 창·치트 창) */

    private View build(File rom, Bitmap thumb, String title) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);   /* 135: 커서는 이 창 것 하나만(머리말 참고) */
        col.setBackground(sheetBg());
        col.setPadding(dp(18), dp(th.pocket() ? 18 : 14), dp(18), dp(14));

        /* 손잡이 */
        View grip = new View(a);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(th.ink() ? 0x66d9a441 : th.pocket() ? 0xff8fa3c0 : th.modern() ? 0x33ffffff : 0xff3a3f4d);
        gd.setCornerRadius(th.pocket() ? 0 : dp(3));
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
        h.setText(title); h.setTextColor(TXT); h.setTextSize(th.ink() ? 21 : 19);
        face(h, true, true);
        tt.addView(h);
        TextView s = new TextView(a);
        s.setText(subtitle != null ? subtitle
                : opts.isEmpty() ? "이 롬에 붙는 패치가 없습니다 — 「시작」을 누르면 순정 그대로 엽니다."
                                 : "실행 전에 적용할 것을 고르세요. 선택은 이 게임에 저장됩니다.");
        s.setTextColor(DIM); s.setTextSize(12); s.setPadding(0, dp(2), 0, 0);
        face(s, false, false);
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
        /* 항목이 많아 반 화면을 넘으면 스크롤된다 — 아래에 더 있다는 걸 보이게 스크롤바를 켜 둔다 */
        ScrollView sv = capScroll((int) (a.getResources().getDisplayMetrics().heightPixels * 0.50f));   /* 148: 화면보다 길면 겉 틀이 줄인다(SheetScroll) */
        sv.addView(list);
        sv.setVerticalScrollBarEnabled(true);
        sv.setScrollbarFadingEnabled(false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        col.addView(sv, sp);

        /* 버튼: 취소 | 시작 */
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = pill("취소", BTN2, DIM, false);
        cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } });
        TextView start = pill(startLabel, GOLD, INK, true);
        start.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { go(); } });
        startBtn = start;
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        cp.rightMargin = dp(10); cp.topMargin = stp.topMargin = dp(14);
        btns.addView(cancel, cp); btns.addView(start, stp);
        col.addView(btns);
        cheatLink = -1;
        if (g != null && Cheats.GAME.equals(g.id)) {                /* 치트 — 따로 창(줄 11개가 실행 창 본문에 끼지 않게). 켜진 게 있으면 강조색 */
            int n = Cheats.count(Settings.load());
            cheatLink = linkViews.size();
            link(col, n > 0 ? "치트 · " + n + "개 켬  ›" : "치트  ›", n > 0 ? GOLD : DIM, new Runnable() { @Override public void run() { openCheats(); } });
        }
        if (onSettings != null) {                                   /* 게임 안: 전체 설정으로 가는 길 */
            link(col, "앱 전체 설정 열기  ›", DIM, new Runnable() { @Override public void run() { dlg.dismiss(); onSettings.run(); } });
        }

        if (onSettings == null && rom != null && g != null && "ss2".equals(g.id) && a instanceof MainActivity) {
            /* 연습 대전·리그 대전은 위 «리그·연습» 줄 하나로 켠다(타이틀 셋째 줄 ◀▶ — 예전의 «연습 대전으로»·«가상 리그로» 두 링크는 둘 중 하나만 됐다).
               여기엔 랭크 매치 바로 가기(148 — 대기창으로 곧장)와 순위표 */
            link(col, "랭크 매치 바로 가기  ›", GOLD, new Runnable() { @Override public void run() {
                dlg.dismiss(); ((MainActivity) a).openRanked(rom); } });
            link(col, "리그 순위표  ›", GOLD, new Runnable() { @Override public void run() {
                dlg.dismiss(); ((MainActivity) a).openLeague(rom); } });
        }
        if (KeyMap.physicalPresent()) {                             /* 패드가 붙어 있을 때만 — 터치만 쓰면 군더더기 */
            TextView hint = new TextView(a);
            hint.setText("패드: 위아래 이동 · 좌우 값 바꾸기 · 펀치 버튼 고르기/시작 · 킥 버튼 닫기");
            hint.setTextColor(DIM); hint.setTextSize(12); hint.setGravity(Gravity.CENTER);   /* 135: 11sp·옅은 회색(2.9:1) → 12sp·DIM */
            hint.setPadding(0, dp(10), 0, 0);
            col.addView(hint);
        }
        return col;
    }

    /** 아래 글 고리 한 줄 — 135: 높이 48dp(전엔 글자만 24~31dp 라 엄지로 옆 줄을 누르기 쉬웠다), 패드 커서에도 든다. */
    private void link(LinearLayout col, String text, int color, final Runnable act) {
        TextView t = new TextView(a);
        t.setText(text); t.setTextColor(color); t.setTextSize(13); t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48)); t.setPadding(dp(8), dp(4), dp(8), dp(4));
        t.setClickable(true);
        t.setBackground(linkBg(false));
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { act.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(linkViews.isEmpty() ? 8 : 2);
        col.addView(t, lp);
        linkViews.add(t); linkActs.add(act);
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
        face(name, true, false);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView val = new TextView(a);
        val.setText(o.name()); val.setTextColor(o.enabled ? GOLD : DIM); val.setTextSize(15);
        if (th.pocket()) val.setTypeface(th.bold()); else val.setTypeface(val.getTypeface(), android.graphics.Typeface.BOLD);
        top.addView(val);
        row.addView(top);
        if (o.enabled && o.vals.length > 2) {                 /* 다단 = 가로 다이얼 바 — 탭/드래그로 고른다(유저 2026-09-05) */
            DialBar bar = new DialBar(a, o.names, o.idx(), new DialBar.OnPick() {
                @Override public void onPick(int k) {
                    if (guardCheat(i, k)) return;                 /* 치트 «세이브에 남기기» — 확인 창이 값을 정한다 */
                    o.cur = o.vals[k];
                    if (o.kind == 0) { Map<String, String> m = Settings.load(); Settings.put(o.key, "on".equals(o.cur) ? onLang(m, o.g) : offLang(m, o.g)); }
                    else Settings.putUser(o.key, o.cur);   /* 보간 묶음이면 세부 값도 같이 — 107 까지 put 이라 바로 고른 60Hz 가 이름만 바뀌었다 */
                    val.setText(o.name());
                }
            });
            bar.setLabel(o.label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));   /* 135: 36 → 48dp */
            lp.topMargin = dp(8);
            row.addView(bar, lp);
            dials.put(i, bar);
        }
        TextView help = null;
        if (o.help != null && !o.help.isEmpty()) {
            help = new TextView(a);
            help.setText(o.help.replace(" 게임을 다시 열면 적용됩니다.", "").replace(" 게임을 다시 열면 적용.", ""));
            help.setTextColor(DIM); help.setTextSize(12);
            face(help, false, false);
            help.setMaxLines(2); help.setEllipsize(android.text.TextUtils.TruncateAt.END);   /* 커서(누른) 줄은 끝까지 — paintFocus */
            help.setPadding(0, dp(3), dp(24), 0);
            row.addView(help);
        }
        helpViews.add(help);
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { focus = i; paintFocus(); if (o.vals.length <= 2) toggle(i); }   /* 다이얼 줄은 바로 고른다 */
        });
        rowViews.add(row); valViews.add(val);
        return row;
    }

    private TextView pill(String text, int bg, int fg, boolean bold) {
        TextView t = new TextView(a);
        t.setText(text); t.setTextColor(fg); t.setTextSize(17); t.setGravity(Gravity.CENTER);
        if (th.ink() || th.pocket()) t.setTypeface(th.title());
        else if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(14), 0, dp(14));
        GradientDrawable d = new GradientDrawable(); d.setColor(bg); d.setCornerRadius(pillRadius());
        if (th.pocket()) d.setStroke(dp(2), bold ? 0xff0c121c : 0xff8fa3c0);
        t.setBackground(d);
        t.setClickable(true);
        return t;
    }
    /** 121: 테마별 버튼 모서리 — 먹·금 6dp · 포켓 각진 · 모던 알약 · 기본 12dp */
    private float pillRadius() { return th.ink() ? dp(6) : th.pocket() ? 0 : th.modern() ? dp(40) : dp(12); }

    /** 121: 창 바탕 — 먹·금 옻칠 + 금 테, 포켓 은색 테 + 남색 액정, 모던 크게 둥근 판, 기본 그대로. */
    private android.graphics.drawable.Drawable sheetBg() {
        float r = dp(th.modern() ? 28 : th.ink() ? 10 : 18);
        float[] top = { r, r, r, r, 0, 0, 0, 0 };
        if (th.pocket()) {
            GradientDrawable body = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{ 0xffd9dce2, 0xffa9aeb7 });
            GradientDrawable lcd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{ 0xff182436, 0xff0c121c });
            lcd.setStroke(dp(2), 0xff0a0d13);
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{ body, lcd });
            ld.setLayerInset(1, dp(7), dp(7), dp(7), 0);
            return ld;
        }
        GradientDrawable bg = new GradientDrawable();
        if (th.ink()) {
            bg.setColors(new int[]{ 0xff1d1812, 0xff0d0b09 });
            bg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            bg.setStroke(Math.max(1, dp(1)), 0x66d9a441);
        } else bg.setColor(CARD);
        bg.setCornerRadii(top);
        return bg;
    }
    /** 121: 테마 글꼴 — 먹·금·포켓은 제목 글꼴/본문 글꼴, 기본·모던은 bold 일 때만 굵게. */
    private void face(TextView v, boolean titleFace, boolean bold) {
        if (th.ink() || th.pocket()) v.setTypeface(titleFace ? th.title() : th.body());
        else if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
    }

    /* 135(접근성 검수): 커서 모양 — 전엔 줄 바탕만 옅게(카드와 1.4~1.6:1 — 잘 안 보였다), 시작 단추는 연한 금테(금색 위라 거의 안 보임).
       → 줄·고리: 강조색 2dp 테 + 옅은 바탕. 시작 단추: 바깥 밝은 테 + 안쪽 어두운 테(무슨 바탕에서도 보이게). 누르는 동안은 옅게 밝아진다. */
    private android.graphics.drawable.Drawable rowBg(boolean on) {
        float rad = th.pocket() ? 0 : dp(8);
        GradientDrawable idle = new GradientDrawable(); idle.setCornerRadius(rad);
        idle.setColor(on ? (GOLD & 0x00ffffff) | 0x1f000000 : Color.TRANSPARENT);
        if (on) idle.setStroke(dp(2), GOLD);
        GradientDrawable pr = new GradientDrawable(); pr.setCornerRadius(rad);
        pr.setColor((TXT & 0x00ffffff) | 0x1a000000);
        if (on) pr.setStroke(dp(2), GOLD);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{ android.R.attr.state_pressed }, pr);
        sl.addState(new int[]{}, idle);
        return sl;
    }
    private android.graphics.drawable.Drawable linkBg(boolean on) { return rowBg(on); }

    private void paintFocus() {
        int n = opts.size();
        for (int i = 0; i < rowViews.size(); i++) {
            boolean on = i == focus;
            rowViews.get(i).setBackground(rowBg(on && padMode));
            TextView hv = i < helpViews.size() ? helpViews.get(i) : null;
            if (hv != null) hv.setMaxLines(on ? 20 : 2);           /* 커서 줄은 설명을 끝까지(전엔 늘 두 줄에서 잘렸다) */
        }
        for (int j = 0; j < linkViews.size(); j++) linkViews.get(j).setBackground(linkBg(padMode && focus == n + 1 + j));
        if (startBtn != null) {
            GradientDrawable d = new GradientDrawable(); d.setColor(GOLD); d.setCornerRadius(pillRadius());
            if (focus == n && padMode) {
                d.setStroke(dp(2), th.pocket() ? 0xffffffff : TXT);                       /* 바깥 밝은 테 */
                GradientDrawable in = new GradientDrawable(); in.setColor(Color.TRANSPARENT);
                in.setCornerRadius(Math.max(0, pillRadius() - dp(2)));
                in.setStroke(dp(2), INK);                                                 /* 안쪽 어두운 테 */
                android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{ d, in });
                ld.setLayerInset(1, dp(2), dp(2), dp(2), dp(2));
                startBtn.setBackground(ld);
            } else {
                if (th.pocket()) d.setStroke(dp(2), 0xff0c121c);
                startBtn.setBackground(d);
            }
        }
        /* 커서 줄이 스크롤 밖이면 보이게 — 안드로이드 포커스를 안 쓰니 스크롤이 저절로 안 따라온다.
           148: 시트째 스크롤할 때(SheetScroll)는 시작·고리도 — 이때 줄은 바로 옮긴다(목록이 부드럽게 도는 중엔 줄 자리가 옛 값이라 겉 틀이 엉뚱한 데로 간다) */
        int lj = focus - n - 1;
        final View fv = focus < n ? (focus < rowViews.size() ? rowViews.get(focus) : null)
                      : focus == n ? startBtn : (lj < linkViews.size() ? linkViews.get(lj) : null);
        final boolean row = focus < n;
        if (fv != null) fv.post(new Runnable() { @Override public void run() {
            boolean whole = sheetScroll != null && sheetScroll.scrolls();
            if (row || whole) fv.requestRectangleOnScreen(new android.graphics.Rect(0, 0, fv.getWidth(), fv.getHeight()), whole);
        } });
    }
    private int stops() { return opts.size() + 1 + linkViews.size(); }

    private void go() {
        if (cheatView) {                                            /* 치트 창 단추 — 게임 안 «적용»은 바로 게임으로(onClose 가 값을 넣는다), 런처 «닫기»는 겉 창으로 */
            if (inGame) { try { dlg.dismiss(); } catch (Exception ignored) { } }
            else closeCheats();
            return;
        }
        try { dlg.dismiss(); } catch (Exception ignored) { }
        onStart.run();
    }

    /* ── 패드 ─────────────────────────────────────────────────────── */

    private boolean key(int code, KeyEvent e) {
        if (KeyMap.isVolume(code)) return false;                   /* 음량은 시스템으로 */
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
        boolean left  = "left".equals(f)  || (!padBtn && code == KeyEvent.KEYCODE_DPAD_LEFT);    /* 135: 좌우 = 값 한 칸 */
        boolean right = "right".equals(f) || (!padBtn && code == KeyEvent.KEYCODE_DPAD_RIGHT);
        if (!(up || down || left || right || ok || back)) return padBtn;   /* 배정 없는 패드 버튼은 삼킨다 — 뒤 창으로 안 샌다 */
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;   /* UP 은 삼킨다 — 게임 화면으로 새지 않게 */
        if (e.getRepeatCount() > 0 && (ok || back)) return true;
        if (!back) padOn();
        if (up || down) moveFocus(up ? -1 : 1);
        else if (left || right) adjust(right ? 1 : -1);
        else if (ok) confirm();
        else if (cheatView) closeCheats();                          /* 치트 창에서 뒤로 = 겉 창으로 */
        else dlg.dismiss();
        return true;
    }

    /** 스틱·HAT 십자로 오는 방향(-1 위 / +1 아래). 축은 MotionEvent 라 key() 를 못 탄다 —
     *  MainActivity.onGenericMotionEvent 가 여기로 넘겨 준다. */
    private void padOn() { if (!padMode) { padMode = true; paintFocus(); } }
    public void moveFocus(int d) {
        padMode = true;
        int t = stops();
        focus = ((focus + d) % t + t) % t;
        paintFocus();
    }

    /** 확인 — 줄이면 다음 값, 시작이면 시작, 아래 고리면 그 고리. key() 와 축 쪽이 같이 쓴다. */
    public void confirm() {
        padOn();
        int n = opts.size();
        if (focus < n) toggle(focus);
        else if (focus == n) go();
        else { int j = focus - n - 1; if (j >= 0 && j < linkActs.size()) linkActs.get(j).run(); }
    }

    /* ── 치트 창(사무쇼2 · Cheats) ──────────────────────────────────
       실행 창·게임 안 「설정」의 «치트 ›» 고리 → 같은 창 틀에 Settings.GROUPS «치트» 줄만. 값은 바꾸는 즉시 options.txt 에(겉 창과 같다).
       게임 안에선 닫을 때 EmuActivity 가 native 에 값만 넣는다(코어를 다시 올리지 않음 — 다음 프레임부터 먹는다). */

    /** 이 창에서 치트 값을 바꿨나 — 게임 안: 닫을 때 «치트 적용» 알림 */
    public boolean cheatsChanged() { return cheatsChanged; }

    private void clearViews() {
        rowViews.clear(); dials.clear(); valViews.clear(); helpViews.clear(); linkViews.clear(); linkActs.clear(); startBtn = null;
    }

    private void openCheats() {
        cheatView = true;
        opts.clear(); clearViews();
        Map<String, String> m = Settings.load();
        for (Settings.Item it : Settings.GROUPS.get(Cheats.GROUP)) opts.add(fromItem(it, m));
        focus = 0;
        setSheet(buildCheats());
        paintFocus();
    }

    /** 겉 창으로 — 줄·고리 글(«치트 · 3개 켬»)을 새 값으로 다시 그리고 커서는 «치트 ›» 고리에 */
    private void closeCheats() {
        cheatView = false;
        opts.clear(); clearViews();
        buildOpts();
        setSheet(build(romFile, thumbBmp, titleText));
        focus = cheatLink >= 0 ? opts.size() + 1 + cheatLink : opts.size();
        paintFocus();
    }

    private View buildCheats() {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);   /* 커서는 이 창 것 하나만(머리말 참고) */
        col.setBackground(sheetBg());
        col.setPadding(dp(18), dp(th.pocket() ? 18 : 14), dp(18), dp(14));
        View grip = new View(a);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(th.ink() ? 0x66d9a441 : th.pocket() ? 0xff8fa3c0 : th.modern() ? 0x33ffffff : 0xff3a3f4d);
        gd.setCornerRadius(th.pocket() ? 0 : dp(3));
        grip.setBackground(gd);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(40), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL; gp.bottomMargin = dp(12);
        col.addView(grip, gp);

        TextView h = new TextView(a);
        h.setText("치트"); h.setTextColor(TXT); h.setTextSize(th.ink() ? 21 : 19);
        face(h, true, true);
        col.addView(h);
        TextView s = new TextView(a);
        s.setText(cheatOff != null ? cheatOff
                : inGame ? "바꾸면 바로 먹어요. 리그 대전엔 안 먹어요." : "고른 건 이 게임에 저장돼요. 리그 대전엔 안 먹어요.");
        s.setTextColor(cheatOff != null ? GOLD : DIM); s.setTextSize(12); s.setPadding(0, dp(2), 0, 0);
        face(s, false, false);
        col.addView(s);

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < opts.size(); i++) {
            if (i > 0) { View dv = new View(a); dv.setBackgroundColor(LINE);
                         list.addView(dv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)); }
            list.addView(row(i));
        }
        TextView warn = new TextView(a);                            /* 기록 경고 — 줄 끝에(스크롤 안) */
        warn.setText(Cheats.WARN); warn.setTextColor(DIM); warn.setTextSize(12);
        face(warn, false, false);
        warn.setPadding(dp(12), dp(10), dp(12), dp(4));
        list.addView(warn);
        ScrollView sv = capScroll((int) (a.getResources().getDisplayMetrics().heightPixels * 0.58f));   /* 줄 11개 — 겉 창(50%)보다 조금 길게 */
        sv.addView(list);
        sv.setVerticalScrollBarEnabled(true);
        sv.setScrollbarFadingEnabled(false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        col.addView(sv, sp);

        TextView btn = pill(inGame ? "적용  ▶" : "닫기", GOLD, INK, true);   /* 단추 하나 — 게임 안은 다시 열지 않고 바로 게임으로 */
        btn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { go(); } });
        startBtn = btn;
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(14);
        col.addView(btn, bp);
        if (!inGame && romFile != null && Cheats.hasBackup(MainActivity.saveDir(), romFile.getPath()))   /* 코어가 안 돌 때(런처)만 */
            link(col, "세이브 되돌리기 (올카드 전)  ›", DIM, new Runnable() { @Override public void run() { confirmRestore(); } });
        if (KeyMap.physicalPresent()) {
            TextView hint = new TextView(a);
            hint.setText("패드: 위아래 이동 · 좌우 값 바꾸기 · 펀치 버튼 고르기 · 킥 버튼 돌아가기");
            hint.setTextColor(DIM); hint.setTextSize(12); hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, dp(10), 0, 0);
            col.addView(hint);
        }
        return col;
    }

    /** 치트 창: 올카드를 «세이브에 남기기»로 바꾸는 순간 확인 창 — 넣기면 지금 세이브(.flash)를 따로 보관한 뒤에만 값을 쓴다, 그만이면 그대로.
     *  창을 띄웠으면 true(값은 창이 정한다). 치트 줄이면 «바뀜» 표시도 여기서 */
    private boolean guardCheat(final int i, final int k) {
        if (!cheatView) return false;
        final Opt o = opts.get(i);
        if (k != o.idx()) cheatsChanged = true;                     /* 같은 값을 다시 누른 건 바뀜이 아니다(«치트 적용» 알림 없이) */
        if (!Cheats.CARDS_KEY.equals(o.key) || !Cheats.CARDS_SAVE.equals(o.vals[k]) || Cheats.CARDS_SAVE.equals(o.cur)) return false;
        final int prev = o.idx();
        final File rom = inGame ? cheatRom : romFile;
        final boolean[] decided = { false };
        new android.app.AlertDialog.Builder(a)
                .setTitle("올카드를 세이브에 넣을까요?")
                .setMessage("다음 저장(게임 오버·클리어·설정 저장) 때 120장이 모두 8장이 돼요. 지금 세이브는 따로 보관해요."
                        + "\n\n바로 남기려면 게임 설정에서 값 하나를 바꿨다 돌려 «예». 되돌리기는 실행 창 «치트»에서.")
                .setPositiveButton("넣기", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                    decided[0] = true;
                    if (rom == null || !Cheats.backupFlash(MainActivity.saveDir(), rom.getPath())) {
                        android.widget.Toast.makeText(a, "세이브를 보관하지 못해 그대로 둬요", android.widget.Toast.LENGTH_SHORT).show();
                        applyOpt(i, prev);
                        return;
                    }
                    applyOpt(i, k);
                } })
                .setNegativeButton("그만", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                    decided[0] = true; applyOpt(i, prev); } })
                .setOnDismissListener(new DialogInterface.OnDismissListener() { @Override public void onDismiss(DialogInterface d) {
                    if (!decided[0]) applyOpt(i, prev); } })        /* 바깥 탭·뒤로 = 그만(다이얼 바가 먼저 옮겨 간 것도 되돌린다) */
                .show();
        return true;
    }

    /** 런처 치트 창 «세이브 되돌리기» — 보관본으로 .flash 를 되돌리고 오토세이브는 비켜 둔다. 올카드가 «세이브에 남기기»면 끔으로(다시 8 로 덮이지 않게) */
    private void confirmRestore() {
        new android.app.AlertDialog.Builder(a)
                .setTitle("올카드 넣기 전 세이브로 되돌릴까요?")
                .setMessage("그 뒤에 쌓은 기록도 사라져요. 그 뒤 저장한 슬롯을 불러오면 카드가 다시 생겨요.")
                .setPositiveButton("되돌리기", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                    boolean ok = romFile != null && Cheats.restoreFlash(MainActivity.saveDir(), romFile.getPath());
                    if (ok && Cheats.CARDS_SAVE.equals(Settings.load().get(Cheats.CARDS_KEY))) Settings.put(Cheats.CARDS_KEY, "off");
                    android.widget.Toast.makeText(a, ok ? "세이브를 되돌렸어요" : "되돌리지 못했어요", android.widget.Toast.LENGTH_SHORT).show();
                    if (cheatView && dlg.isShowing()) openCheats();       /* 값·고리 다시 그림 */
                } })
                .setNegativeButton("그만", null)
                .show();
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
