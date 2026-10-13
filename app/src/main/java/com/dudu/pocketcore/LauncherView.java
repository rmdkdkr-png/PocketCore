package com.dudu.pocketcore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 레트로 런처 v2.
 *
 * v1 사고 둘의 수리(제보):
 *  · 「글자 크기 말같지도 않게」 — 크기 계산 실수(h 의 20%짜리 글자). 전부 제정신
 *    비율로 다시 (제목 3.0%, 배지 2.0%, 힌트 1.6%).
 *  · 「오버레이 해상도 원래 저 정도냐」 — 썸네일을 중간 캔버스(66px 칸)로 **줄였다가**
 *    다시 튀겨서 지글거렸다. 중간 캔버스를 없애고 원본(160x152)을 **정수배(3~4배)로
 *    직행** blit — 무필터라 픽셀은 그대로, 해상도는 원본 그대로.
 *
 * 부팅 연출: 스마일 볼(자작 도트)이 굴러 들어와 로고 자리에 안착하면 목록이 떠오른다 —
 * 실기 네오지오 포켓 부트 오마주. 부팅음(0.57초)과 박자를 맞췄다.
 *
 * 121: 테마(Theme) 넷 — 기본(위 그대로) · 먹·금(옻칠·금박·낙관, 붓 원·수묵 산) · 포켓(은색 휴대기: 베젤 속 액정에 목록,
 * 몸통에 스틱·A·설정/업데이트 알약) · 모던(흐린 게임 색 바탕, 둥근 카드, 큰 「이어하기」, 아래 탭). 배치·할 일은 같고 겉모습만 다르다.
 * 포켓·모던은 아래 줄(설정·업데이트)을 캔버스에 직접 그린다 — MainActivity 가 안드로이드 아래 줄을 숨긴다.
 * 왼쪽 위 로고를 누르면 다음 테마(Listener.onTheme). 썸네일은 어느 테마에서나 정수배·무필터(도트 그대로).
 */
public final class LauncherView extends View {

    public static final class Item {
        public final File rom;
        public String title;
        public String sub = "";
        public Bitmap thumb;
        public boolean pat, sp, dub;
        public String opt = "";           /* 실행 옵션 한 줄(LaunchSheet.summary) — 카드 아래 칩 */
        public boolean resume;            /* 오토세이브가 있다 — A 아래 글이 「이어하기」 */
        public long resumeAt;             /* 121: 오토세이브 시각(모던 「자동 저장 · 3분 전」) — 0 = 모름 */
        public Item(File rom, String title) { this.rom = rom; this.title = title; }
    }

    public interface Listener {
        void onLaunch(File rom);
        void onOptions(File rom);         /* 카드 아래 옵션 칩 — 실행 전 선택창 */
        void onSettings();
        void onUpdate();
        void onTheme();                   /* 121: 로고를 누르면 다음 테마 */
    }

    private final Paint px = new Paint();   /* 픽셀 그림 — 필터·AA 끔 */
    private final Paint tp = new Paint();   /* 글자 — AA 켬 */
    private final Paint fx = new Paint(Paint.ANTI_ALIAS_FLAG);   /* 121: 장식(그라디언트·그림자) */
    private final Rect srcR = new Rect(), dstR = new Rect();
    private final RectF tr = new RectF(), tr2 = new RectF();

    private List<Item> items;
    private int sel = 0;
    private Listener listener;
    private float downX = -1;
    /* 배포 레벨 배지 — 시험 판을 쓰는 동안 늘 보이게(유저 2026-09-04 「구분 잘 될지 모르겠다」).
       정식이면 null 이라 아무것도 안 그린다 — 보통 사람 화면은 그대로. */
    private String channel;
    public void setChannel(String s) { channel = s; invalidate(); }
    /** 121: 새 판 내부 번호(0 = 없음) — 포켓·모던이 캔버스에 그린 업데이트 버튼이 이걸로 바뀐다. */
    private int newer = 0;
    public void setUpdate(int code) { newer = code; invalidate(); }
    private long introAt = 0;               /* 부팅 연출 시작 시각 (0 = 안 함) */
    private static final long INTRO_MS = 950;

    /* ── 첨화면 아트: 「포켓 진열장」 ──
       위쪽 하늘엔 도트 별밭(정적 — 좌표는 해시로 고정), 화면 전체엔 진열 조명 비네트,
       포커스 카드 밑엔 선반 잔광 두 줄. 실기 부트 화면의 「밤하늘에 기기 하나」 감성. */
    private Paint vigP; private int vigW, vigH;   /* 비네트는 크기 바뀔 때만 다시 만든다 */

    private Paint vignette(float w, float h) {
        if (vigP == null || vigW != (int) w || vigH != (int) h) {
            vigP = new Paint();
            vigP.setShader(new android.graphics.RadialGradient(
                    w * 0.5f, h * 0.34f, Math.max(w, h) * 0.72f,
                    new int[]{ 0xff15151d, 0xff0e0e12, 0xff09090c },
                    new float[]{ 0f, 0.55f, 1f },
                    android.graphics.Shader.TileMode.CLAMP));
            vigW = (int) w; vigH = (int) h;
        }
        return vigP;
    }

    private void drawBackdrop(Canvas c, float w, float h, float introT) {
        c.drawRect(0, 0, w, h, vignette(w, h));
        drawStars(c, w, h, introT, 0x9aa0b8, 0xd9cba4, 34);
    }
    /** 도트 별 — 의사난수(정수 해시)로 좌표·크기·밝기를 고정. 매 프레임 같은 하늘. 먹·금은 금가루로 같이 쓴다. */
    private void drawStars(Canvas c, float w, float h, float introT, int col, int hiCol, int count) {
        float d = Math.max(1f, w / 360f);             /* 도트 한 알의 화면 크기 */
        int fade = (int) (255 * Math.min(1f, introT * 1.6f));
        for (int i = 0; i < count; i++) {
            int hsh = i * 0x9E3779B1;
            float sx2 = ((hsh >>> 8) % 1000) / 1000f * w;
            float sy2 = ((hsh >>> 18) % 560) / 1000f * h;   /* 위 56% 에만 */
            int tier = (hsh >>> 4) & 3;                      /* 0~3 밝기 층 */
            int a = (18 + tier * 14) * fade / 255;
            px.setColor((a << 24) | (tier == 3 ? hiCol : col));
            float s2 = d * (tier == 3 ? 2f : 1f);
            c.drawRect(sx2, sy2, sx2 + s2, sy2 + s2, px);
        }
    }

    /* 런처 버튼도 게임 안 터치 패드와 같은 아트(PadSkin)로 — 설정 「버튼 모양」을 따른다(유저 2026-10-10 「버튼이나 전반적 인터페이스 좀 손봐 줘」) */
    private final PadSkin skin = new PadSkin();
    private boolean art = true;

    public LauncherView(Context c) {
        super(c);
        px.setAntiAlias(false);
        px.setFilterBitmap(false);
        tp.setAntiAlias(true);
        setBackgroundColor(0xff0b0b0e);
        Theme.init(c);
        try { art = !"flat".equals(Settings.load().get("pocketcore_padskin")); } catch (Exception ignored) { }
        if (art) skin.reloadUser();
    }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { skin.clear(); }

    public void setItems(List<Item> it) { items = it; sel = 0; invalidate(); }
    /** 처음 보여 줄 카드(마지막에 한 게임) — 넘기는 손맛(진동)은 없이 */
    public void select(int i) { if (items != null && i >= 0 && i < items.size()) { sel = i; invalidate(); } }
    public void setListener(Listener l) { listener = l; }
    public void startIntro() { introAt = System.currentTimeMillis(); invalidate(); }
    public void thumbReady() { postInvalidate(); }
    /** 물리 패드용 — 카드 이동 / 지금 고른 카드 (없으면 null). */
    public void moveSel(int d) { if (items != null) move(d); }
    public Item selected() { return (items == null || items.isEmpty()) ? null : items.get(sel); }
    /** 121: 지금 카드 번호 — 테마를 바꿔 런처를 다시 짤 때 그 자리를 이어 간다. */
    public int selIndex() { return sel; }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    /* ── 그리기 ─────────────────────────────────────────────────── */

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float t = 1f;
        if (introAt > 0) {
            t = (System.currentTimeMillis() - introAt) / (float) INTRO_MS;
            if (t >= 1f) { t = 1f; introAt = 0; }
            else postInvalidateOnAnimation();
        }
        Theme th = Theme.get();
        logoR.setEmpty(); setR.setEmpty(); updR.setEmpty(); prevR.setEmpty(); nextR.setEmpty(); playR.setEmpty();
        optChip.setEmpty();
        dR = 0; aR = 0;
        if (th.ink()) drawInk(cv, w, h, t);
        else if (th.pocket()) drawPocket(cv, w, h, t);
        else if (th.modern()) drawModern(cv, w, h, t);
        else drawClassic(cv, w, h, t);
        logButtons();
    }

    /* ── 기본(120 까지 그대로) ──────────────────────────────────── */

    private void drawClassic(Canvas cv, float w, float h, float t) {
        drawBackdrop(cv, w, h, introAt > 0 ? t : 1f);

        /* 로고 — 좌상단. 인트로 중에는 스마일 볼이 굴러와 심볼 자리에 안착한다 */
        Bitmap logo = Logo.get(getContext());
        float lx = w * 0.06f, ly = h * 0.025f;
        int ls = Math.max(2, (int) (w / 160f));           /* 로고 배율 */
        float logoH = (logo != null ? logo.getHeight() : 12) * ls;
        if (logo != null && t >= 1f) {
            srcR.set(0, 0, logo.getWidth(), logo.getHeight());
            dstR.set((int) lx, (int) ly, (int) (lx + logo.getWidth() * ls), (int) (ly + logoH));
            cv.drawBitmap(logo, srcR, dstR, px);
        } else if (logo != null) {
            /* 워드마크는 심볼 자리(왼쪽 12px)를 비우고 먼저 — 볼이 거기로 들어간다 */
            int cut = 14 * ls;
            srcR.set(14, 0, logo.getWidth(), logo.getHeight());
            dstR.set((int) lx + cut, (int) ly,
                     (int) (lx + logo.getWidth() * ls), (int) (ly + logoH));
            int a = (int) (255 * Math.min(1f, t * 2f));
            px.setAlpha(a);
            cv.drawBitmap(logo, srcR, dstR, px);
            px.setAlpha(255);
        }
        logoR.set(lx - dp(8), ly - dp(8), lx + (logo != null ? logo.getWidth() : 61) * ls + dp(8), ly + logoH + dp(8));
        if (introAt > 0) {                                 /* 스마일 볼 굴리기 */
            float r = logoH * 0.55f;
            /* 잔상 — 조금 전 위치에 흐린 볼을 겹쳐 「구른다」가 눈에 남게 */
            for (int k = 3; k >= 1; k--) {
                float tk = t - k * 0.055f;
                if (tk <= 0) continue;
                float pk = Math.min(1f, tk / 0.75f);
                float ek = 1 - (1 - pk) * (1 - pk);
                float sxk = -2 * r + (lx + 6 * ls + 2 * r) * ek;
                float syk = ly + logoH * 0.5f
                          + (tk > 0.75f ? 0 : (h * 0.015f) * (float) Math.abs(Math.sin(tk * 18)));
                tp.setColor(((36 - k * 9) << 24) | 0x00d9a441);
                cv.drawCircle(sxk, syk, r * (1 - k * 0.10f), tp);
            }
            float p2 = Math.min(1f, t / 0.75f);
            float e = 1 - (1 - p2) * (1 - p2);             /* ease-out */
            float sx = -2 * r + (lx + 6 * ls + 2 * r) * e; /* 왼쪽 밖 → 심볼 자리 */
            float sy = ly + logoH * 0.5f
                     + (t > 0.75f ? 0 : (h * 0.015f) * (float) Math.abs(Math.sin(t * 18)));
            drawSmile(cv, sx, sy, r, t * 720f);
            /* 안착 — 링 파동 + 스파클 넷. 부팅음 마지막 박에 맞춘 「철컥」 */
            if (t > 0.78f) {
                float q = (t - 0.78f) / 0.22f;
                float cx2 = lx + 6 * ls, cy3 = ly + logoH * 0.5f;
                tp.setStyle(Paint.Style.STROKE);
                tp.setStrokeWidth(Math.max(2f, r * 0.10f * (1 - q)));
                tp.setColor(((int) (150 * (1 - q)) << 24) | 0x00d9a441);
                cv.drawCircle(cx2, cy3, r * (1.05f + q * 2.4f), tp);
                tp.setStyle(Paint.Style.FILL);
                int sa = (int) (220 * (1 - q));
                tp.setColor((sa << 24) | 0x00ffe9b0);
                float sd = r * (1.25f + q * 1.6f), ss = Math.max(2f, r * 0.10f);
                cv.drawRect(cx2 - sd - ss, cy3 - ss, cx2 - sd + ss, cy3 + ss, tp);
                cv.drawRect(cx2 + sd - ss, cy3 - ss, cx2 + sd + ss, cy3 + ss, tp);
                cv.drawRect(cx2 - ss, cy3 - sd - ss, cx2 + ss, cy3 - sd + ss, tp);
                cv.drawRect(cx2 - ss, cy3 + sd - ss, cx2 + ss, cy3 + sd + ss, tp);
            }
        }

        if (channel != null) {          /* 로고 오른쪽 — 「시험 v3.77」 호박색 테두리 알약 */
            tp.setTextAlign(Paint.Align.LEFT);
            tp.setTextSize(h * 0.016f);
            float tw3 = tp.measureText(channel), padx = h * 0.008f, bh3 = h * 0.026f;
            float bx3 = lx + h * 0.001f, by3 = ly + logoH + h * 0.008f;
            RectF pill = new RectF(bx3, by3, bx3 + tw3 + padx * 2, by3 + bh3);
            tp.setStyle(Paint.Style.FILL);
            tp.setColor(0x33d9a441);
            cv.drawRoundRect(pill, bh3 * 0.5f, bh3 * 0.5f, tp);
            tp.setStyle(Paint.Style.STROKE);
            tp.setStrokeWidth(Math.max(1f, h * 0.0012f));
            tp.setColor(0xccd9a441);
            cv.drawRoundRect(pill, bh3 * 0.5f, bh3 * 0.5f, tp);
            tp.setStyle(Paint.Style.FILL);
            tp.setColor(0xffe8c477);
            cv.drawText(channel, bx3 + padx, by3 + bh3 * 0.72f, tp);
        }

        int n = (items == null) ? 0 : items.size();
        float listA = introAt > 0 ? Math.max(0f, (t - 0.55f) / 0.45f) : 1f;
        int la = (int) (255 * listA);

        /* ── 카드 — 원본을 정수배로 직행. 중앙 3~4배, 양옆은 그 절반 ── */
        float top = ly + logoH + h * 0.02f;
        float availH = h * 0.40f;
        int cs = Math.max(2, Math.min((int) (w * 0.52f) / 160, (int) availH / 152));
        int ss2 = Math.max(1, cs / 2);
        float cy2 = top + (availH - 152 * cs) / 2f + 152 * cs / 2f;   /* 중앙 카드 세로중심 */
        cardTop = 0; cardBottom = h * 0.55f;

        if (n == 0) {
            tp.setColor(0xffbbbbbb);
            tp.setTextSize(h * 0.022f);
            tp.setTextAlign(Paint.Align.CENTER);
            cv.drawText("PocketCore/roms 에 롬을 넣으세요", w / 2, top + availH / 2, tp);
        } else {
            if (n > 1) {
                drawCard(cv, items.get((sel + n - 1) % n), w * 0.13f, cy2, ss2, false, la);
                drawCard(cv, items.get((sel + 1) % n),      w * 0.87f, cy2, ss2, false, la);
            }
            drawCard(cv, items.get(sel), w * 0.5f, cy2, cs, true, la);

            /* ── 글자 — 제정신 크기(뷰 높이 비율) ── */
            Item cur = items.get(sel);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setColor(withA(0xffffffff, la));
            tp.setTextSize(h * 0.030f);
            tp.setFakeBoldText(true);
            float ty = top + availH + h * 0.045f;
            cv.drawText(cur.title, w / 2, ty, tp);
            tp.setFakeBoldText(false);

            /* 배지 */
            tp.setTextSize(h * 0.018f);
            String[] lb = new String[3];
            int[] bc = new int[3];
            int bn = 0;
            if (cur.pat) { lb[bn] = "한패"; bc[bn++] = 0xff2e7d32; }
            if (cur.sp)  { lb[bn] = "SP";   bc[bn++] = 0xffb26500; }
            float padX = h * 0.010f, gap = h * 0.008f, bh = h * 0.028f;
            float bw = 0;
            for (int i = 0; i < bn; i++) bw += tp.measureText(lb[i]) + padX * 2 + (i > 0 ? gap : 0);
            float bx2 = (w - bw) / 2;
            float byTop = ty + h * 0.015f;
            for (int i = 0; i < bn; i++) {
                float tw2 = tp.measureText(lb[i]);
                tp.setColor(withA(bc[i], la));
                cv.drawRoundRect(new RectF(bx2, byTop, bx2 + tw2 + padX * 2, byTop + bh),
                        bh * 0.3f, bh * 0.3f, tp);
                tp.setColor(withA(0xffffffff, la));
                cv.drawText(lb[i], bx2 + padX + tw2 / 2, byTop + bh * 0.72f, tp);
                bx2 += tw2 + padX * 2 + gap;
            }
            float ly2 = byTop + bh + h * 0.028f;
            if (!cur.sub.isEmpty()) {
                tp.setColor(withA(0xff9999aa, la));
                tp.setTextSize(h * 0.017f);
                cv.drawText(cur.sub, w / 2, ly2, tp);
                ly2 += h * 0.022f;
            }
            {   /* 롬 파일명 — 같은 게임 롬이 여럿(J/UE/[h1]/패치본)이면 이름만으론 못 가른다(유저 2026-09-05) */
                String fn = romName(cur);
                tp.setColor(withA(0xff6b7388, la));
                tp.setTextSize(h * 0.015f);
                while (fn.length() > 8 && tp.measureText(fn) > w * 0.9f) fn = fn.substring(0, fn.length() - 2);   /* 너무 길면 자른다 */
                cv.drawText(fn, w / 2, ly2, tp);
            }
            {   /* 실행 옵션 칩 — 무엇으로 켜질지 늘 보이고, 누르면 바꾸는 창(실행 전 선택창). 카드·A 는 바로 시작(2026-10-11) */
                String ot = (cur.opt == null || cur.opt.isEmpty()) ? "실행 옵션" : cur.opt;
                String tail = "  옵션 ›";
                float ts = h * 0.017f;
                tp.setTextSize(ts);
                float lim = w * 0.86f;
                ot = fitOpt(ot, tail, lim);
                float cw = tp.measureText(ot + tail) + ts * 1.6f, ch = ts * 2.1f;
                float cyTop = ly2 + h * 0.020f;
                optChip.set(w / 2 - cw / 2, cyTop, w / 2 + cw / 2, cyTop + ch);
                tp.setColor(withA(0xff191b22, la));
                cv.drawRoundRect(optChip, ch / 2, ch / 2, tp);
                tp.setStyle(Paint.Style.STROKE); tp.setStrokeWidth(Math.max(1.5f, h * 0.0015f));
                tp.setColor(withA(0xff4a3d22, la));
                cv.drawRoundRect(optChip, ch / 2, ch / 2, tp);
                tp.setStyle(Paint.Style.FILL);
                float tx0 = optChip.centerX() - tp.measureText(ot + tail) / 2, by = optChip.centerY() + ts * 0.36f;
                tp.setTextAlign(Paint.Align.LEFT);
                tp.setColor(withA(0xffc9cfdb, la));
                cv.drawText(ot, tx0, by, tp);
                tp.setColor(withA(0xffd9a441, la));
                cv.drawText(tail, tx0 + tp.measureText(ot), by, tp);
                tp.setTextAlign(Paint.Align.CENTER);
            }
            tp.setColor(withA(0xff777788, la));
            tp.setTextSize(h * 0.015f);
            cv.drawText((sel + 1) + " / " + n, w * 0.5f, top - h * 0.006f, tp);
        }
        drawPad(cv);
    }

    private static int withA(int col, int a) {
        return (col & 0x00ffffff) | (Math.min(255, a) << 24);
    }
    /** 알파 곱하기 — 색의 알파 × a/255 */
    private static int mulA(int col, int a) {
        return (col & 0x00ffffff) | ((((col >>> 24) * Math.min(255, a)) / 255) << 24);
    }
    private static String romName(Item it) {
        String fn = it.rom.getName(); int dot = fn.lastIndexOf('.'); if (dot > 0) fn = fn.substring(0, dot);
        return fn;
    }
    /** 칩 글이 넘치면 뒤 항목부터 줄인다(tp 크기 기준). */
    private String fitOpt(String ot, String tail, float lim) {
        while (ot.length() > 4 && tp.measureText(ot + tail) > lim) {
            int cut = ot.lastIndexOf(" · ");
            ot = cut > 0 ? ot.substring(0, cut) + " …" : ot.substring(0, ot.length() - 2);
            if (ot.endsWith(" … …")) ot = ot.substring(0, ot.length() - 2);
        }
        return ot;
    }
    /** 너무 긴 글은 끝을 «…»로. */
    private String fit(String s, float lim) {
        if (tp.measureText(s) <= lim) return s;
        while (s.length() > 2 && tp.measureText(s + "…") > lim) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
    private static float ease(float x) { x = Math.max(0f, Math.min(1f, x)); return 1 - (1 - x) * (1 - x); }

    /** 스마일 볼 — 자작 도트 감성(원 + 눈 2 + 웃는 입), 굴러가는 회전. */
    private void drawSmile(Canvas c, float x, float y, float r, float rot) {
        c.save();
        c.rotate(rot, x, y);
        tp.setColor(0xffd9a441);
        c.drawCircle(x, y, r, tp);
        tp.setColor(0xff1a1a20);
        float er = r * 0.13f;
        c.drawCircle(x - r * 0.32f, y - r * 0.22f, er, tp);
        c.drawCircle(x + r * 0.32f, y - r * 0.22f, er, tp);
        RectF m = new RectF(x - r * 0.45f, y - r * 0.15f, x + r * 0.45f, y + r * 0.55f);
        tp.setStyle(Paint.Style.STROKE);
        tp.setStrokeWidth(r * 0.14f);
        c.drawArc(m, 20, 140, false, tp);
        tp.setStyle(Paint.Style.FILL);
        c.restore();
    }

    /** 카드 — 원본 비트맵을 정수배 blit. cx=가로중심, cy=세로중심. */
    private void drawCard(Canvas c, Item it, float cx, float cy, int scale,
                          boolean focus, int alpha) {
        int bw = 160, bh = 152;
        if (it.thumb != null) { bw = it.thumb.getWidth(); bh = it.thumb.getHeight(); }
        int dw = bw * scale, dh = bh * scale;
        int l = (int) (cx - dw / 2f), t2 = (int) (cy - dh / 2f);
        px.setStyle(Paint.Style.FILL);
        px.setColor(withA(focus ? 0xff000000 : 0xff0a0a0d, alpha));
        c.drawRect(l - 4, t2 - 4, l + dw + 4, t2 + dh + 4, px);
        if (it.thumb != null) {
            srcR.set(0, 0, bw, bh);
            dstR.set(l, t2, l + dw, t2 + dh);
            px.setAlpha(focus ? alpha : alpha * 120 / 255);
            c.drawBitmap(it.thumb, srcR, dstR, px);
            px.setAlpha(255);
        } else {
            tp.setColor(withA(focus ? 0xffcccccc : 0xff555566, alpha));
            tp.setTextSize(getHeight() * 0.018f);
            tp.setTextAlign(Paint.Align.CENTER);
            c.drawText(it.title, cx, cy, tp);
        }
        px.setStyle(Paint.Style.STROKE);
        px.setStrokeWidth(Math.max(2, scale));
        px.setColor(withA(focus ? 0xffd9a441 : 0xff33333d, alpha));
        c.drawRect(l - 4, t2 - 4, l + dw + 4, t2 + dh + 4, px);
        px.setStyle(Paint.Style.FILL);
        px.setStrokeWidth(1);
        if (focus) {                       /* 선반 잔광 — 진열장 유리에 비친 골드 */
            px.setColor(withA(0xffd9a441, alpha * 0x2e / 255));
            c.drawRect(l - 4, t2 + dh + 9, l + dw + 4, t2 + dh + 11, px);
            px.setColor(withA(0xffd9a441, alpha * 0x14 / 255));
            c.drawRect(l + dw / 6f, t2 + dh + 14, l + dw - dw / 6f, t2 + dh + 15, px);
        }
    }

    /** 121: 썸네일을 정수배로 dst 자리에(무필터). 없으면 제목 글자. sat < 1 이면 채도를 낮춘다. */
    private void blitThumb(Canvas c, Item it, RectF dst, int alpha, float sat, int textCol) {
        if (it.thumb != null) {
            srcR.set(0, 0, it.thumb.getWidth(), it.thumb.getHeight());
            dstR.set(Math.round(dst.left), Math.round(dst.top), Math.round(dst.right), Math.round(dst.bottom));
            px.setAlpha(alpha);
            if (sat < 1f) { ColorMatrix cm = new ColorMatrix(); cm.setSaturation(sat); px.setColorFilter(new ColorMatrixColorFilter(cm)); }
            c.drawBitmap(it.thumb, srcR, dstR, px);
            px.setColorFilter(null);
            px.setAlpha(255);
        } else {
            tp.setColor(withA(textCol, alpha));
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(Math.max(dp(10), dst.height() * 0.07f));
            String s = fit(it.title, dst.width() * 0.9f);
            c.drawText(s, dst.centerX(), dst.centerY() + tp.getTextSize() * 0.35f, tp);
        }
    }
    private static int thumbW(Item it) { return it.thumb != null ? it.thumb.getWidth() : 160; }
    private static int thumbH(Item it) { return it.thumb != null ? it.thumb.getHeight() : 152; }
    /** 가로 중심 cx, 세로 중심 cy 에 scale 배 카드 자리. */
    private void cardRect(RectF out, Item it, float cx, float cy, int scale) {
        int dw = thumbW(it) * scale, dh = thumbH(it) * scale;
        float l = Math.round(cx - dw / 2f), t = Math.round(cy - dh / 2f);
        out.set(l, t, l + dw, t + dh);
    }

    /* ── 먹·금 ───────────────────────────────────────────────────── */

    private void drawInk(Canvas c, float w, float h, float t) {
        float mn = Math.min(w, h);
        boolean intro = introAt > 0;
        ThemeDraw.inkPaper(c, 0, 0, w, h, fx);
        Bitmap mtn = Theme.bmp("ink_mtn");                      /* 수묵 산 — 아래쪽(패드 뒤) */
        if (mtn != null) {
            fx.reset(); fx.setFilterBitmap(true);
            tr.set(0, h - h * 0.36f, w, h);
            c.drawBitmap(mtn, null, tr, fx);
        }
        drawStars(c, w, h, intro ? t : 1f, 0xb8925a, 0xe9c46f, 26);   /* 금가루 */

        int n = items == null ? 0 : items.size();
        float listA = intro ? Math.max(0f, (t - 0.55f) / 0.45f) : 1f;
        int la = (int) (255 * listA);

        /* 로고 — 붉은 낙관 «레» + 금박 「Legacito」. 인트로: 낙관이 «쾅» 찍히고 글이 번진다 */
        float lx = w * 0.06f, ly = h * 0.025f;
        float logoH = Math.max(dp(22), Math.min(dp(34), mn * 0.042f));
        Bitmap seal = Theme.bmp("ink_seal");
        float st = intro ? Math.min(1f, t / 0.42f) : 1f;
        if (seal != null && st > 0) {
            float sc = 1f + 0.9f * (1f - ease(st));
            c.save();
            c.translate(lx + logoH / 2f, ly + logoH / 2f);
            c.rotate(-5f);
            c.scale(sc, sc);
            fx.reset(); fx.setFilterBitmap(true); fx.setAlpha((int) (255 * Math.min(1f, st * 1.4f)));
            tr.set(-logoH / 2f, -logoH / 2f, logoH / 2f, logoH / 2f);
            c.drawBitmap(seal, null, tr, fx);
            c.restore();
        }
        float wx = lx + logoH + dp(10);
        tp.setTypeface(Theme.font("ink_b"));
        tp.setFakeBoldText(false);
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTextSize(logoH * 0.98f);
        tp.setLetterSpacing(0.04f);
        float ww = tp.measureText("Legacito");
        tp.setShader(new LinearGradient(0, ly, 0, ly + logoH, new int[]{ 0xfffbe7ad, 0xffd9aa4c, 0xff9a6d26 },
                new float[]{ 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
        tp.setAlpha(intro ? (int) (255 * Math.max(0f, Math.min(1f, (t - 0.30f) / 0.30f))) : 255);
        c.drawText("Legacito", wx, ly + logoH * 0.80f, tp);
        tp.setShader(null);
        tp.setLetterSpacing(0f);
        logoR.set(lx - dp(8), ly - dp(8), wx + ww + dp(8), ly + logoH + dp(8));

        float badgeBottom = ly + logoH;
        if (channel != null) {                                   /* 금테 상자 「시험 v0.9」 */
            tp.setTypeface(android.graphics.Typeface.DEFAULT);
            tp.setTextSize(Math.max(dp(10.5f), h * 0.0135f));
            float tw3 = tp.measureText(channel), padx = dp(7), bh3 = tp.getTextSize() * 1.75f;
            float bx3 = lx, by3 = ly + logoH + dp(8);
            tr.set(bx3, by3, bx3 + tw3 + padx * 2, by3 + bh3);
            fx.reset(); fx.setAntiAlias(true);
            fx.setColor(0x59281c0a);
            c.drawRoundRect(tr, dp(2), dp(2), fx);
            fx.setStyle(Paint.Style.STROKE); fx.setStrokeWidth(Math.max(1f, dp(1))); fx.setColor(0x8cd9a441);
            c.drawRoundRect(tr, dp(2), dp(2), fx);
            fx.setStyle(Paint.Style.FILL);
            tp.setColor(0xffe8c477);
            c.drawText(channel, bx3 + padx, by3 + bh3 * 0.70f, tp);
            badgeBottom = tr.bottom;
        }

        /* 카드 */
        float top = ly + logoH + h * 0.04f;
        float availH = h * 0.40f;
        int cs = Math.max(2, Math.min((int) (w * 0.60f) / 160, (int) availH / 152));
        int ss2 = Math.max(1, cs / 2);
        float cy2 = top + availH / 2f;
        cardTop = top - dp(20); cardBottom = top + availH;

        if (n == 0) {
            tp.setTypeface(Theme.font("ink_b"));
            tp.setColor(0xffcdbd98);
            tp.setTextSize(h * 0.022f);
            tp.setTextAlign(Paint.Align.CENTER);
            c.drawText("PocketCore/roms 에 롬을 넣으세요", w / 2, cy2, tp);
            tp.setTypeface(android.graphics.Typeface.DEFAULT);
            drawPad(c);
            return;
        }
        Item cur = items.get(sel);
        RectF cr = new RectF();
        cardRect(cr, cur, w * 0.5f, cy2, cs);

        Bitmap enso = Theme.bmp("ink_enso");                    /* 붓 원 — 카드 뒤 옅게 */
        if (enso != null) {
            float er = Math.max(cr.width(), cr.height()) * 0.70f;
            fx.reset(); fx.setFilterBitmap(true); fx.setAlpha((int) (34 * Math.max(listA, intro ? Math.min(1f, t * 1.5f) : 1f)));
            tr.set(cr.centerX() - er, cr.centerY() - er, cr.centerX() + er, cr.centerY() + er);
            c.drawBitmap(enso, null, tr, fx);
        }
        if (n > 1) {                                             /* 양옆 카드 — 옅고 채도 낮게, 가는 금테 */
            for (int k = -1; k <= 1; k += 2) {
                Item it = items.get((sel + n + k) % n);
                RectF sr = new RectF();
                cardRect(sr, it, w * (k < 0 ? 0.13f : 0.87f), cy2, ss2);
                fx.reset(); fx.setColor(withA(0xff070605, la * 200 / 255));
                c.drawRect(sr, fx);
                blitThumb(c, it, sr, la * 107 / 255, 0.6f, 0xff8c7f66);
                fx.setStyle(Paint.Style.STROKE); fx.setStrokeWidth(Math.max(1f, dp(1))); fx.setColor(withA(0x73c9983f, la));
                c.drawRect(sr, fx);
                fx.setStyle(Paint.Style.FILL);
            }
        }
        /* 가운데 카드 — 그림자 · 안쪽 금선 · 먹 틈 · 바깥 금선 · 모서리 꺾쇠 */
        float g1 = Math.max(1f, dp(1)), gap = dp(6), g2 = Math.max(1.5f, dp(1.5f));
        fx.reset(); fx.setAntiAlias(true);
        fx.setColor(withA(0xbf000000, la));
        fx.setMaskFilter(new android.graphics.BlurMaskFilter(dp(16), android.graphics.BlurMaskFilter.Blur.NORMAL));
        tr.set(cr); tr.inset(-gap, -gap); tr.offset(0, dp(14));
        c.drawRect(tr, fx);
        fx.setMaskFilter(null);
        fx.setColor(withA(0xff0b0907, la));
        tr.set(cr); tr.inset(-(gap + g1), -(gap + g1));
        c.drawRect(tr, fx);
        fx.setStyle(Paint.Style.STROKE);
        fx.setStrokeWidth(g2); fx.setColor(withA(0xffc8983f, la));
        tr.set(cr); tr.inset(-(gap + g1 + g2 / 2f), -(gap + g1 + g2 / 2f));
        c.drawRect(tr, fx);
        fx.setStrokeWidth(g1); fx.setColor(withA(0xe6e9c46f, la));
        tr.set(cr); tr.inset(-g1 / 2f, -g1 / 2f);
        c.drawRect(tr, fx);
        float k1 = dp(22), ko = gap + g1 + g2 + dp(5), kw = Math.max(2f, dp(2));
        fx.setStrokeWidth(kw); fx.setColor(withA(0xffe9c46f, la));
        Path br = new Path();
        br.moveTo(cr.left - ko, cr.top - ko + k1); br.lineTo(cr.left - ko, cr.top - ko); br.lineTo(cr.left - ko + k1, cr.top - ko);
        br.moveTo(cr.right + ko, cr.bottom + ko - k1); br.lineTo(cr.right + ko, cr.bottom + ko); br.lineTo(cr.right + ko - k1, cr.bottom + ko);
        c.drawPath(br, fx);
        fx.setStyle(Paint.Style.FILL);
        blitThumb(c, cur, cr, la, 1f, 0xffcdbd98);
        /* 선반 금빛 반사 두 줄 */
        float ry = cr.bottom + gap + dp(14);
        fx.setShader(new LinearGradient(cr.left + dp(20), 0, cr.right - dp(20), 0,
                new int[]{ 0x00c9983f, withA(0xe9c46f, la * 140 / 255), 0x00c9983f }, null, Shader.TileMode.CLAMP));
        c.drawRect(cr.left + dp(20), ry, cr.right - dp(20), ry + Math.max(1.5f, dp(2)), fx);
        fx.setAlpha(la * 70 / 255);
        c.drawRect(cr.left + cr.width() * 0.25f, ry + dp(7), cr.right - cr.width() * 0.25f, ry + dp(7) + Math.max(1f, dp(1)), fx);
        fx.setShader(null);

        /* 번호 — 카드 위 */
        tp.setTypeface(Theme.font("ink_b"));
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(Math.max(dp(11), h * 0.015f));
        tp.setLetterSpacing(0.2f);
        tp.setColor(withA(0xff8c7f66, la));
        c.drawText((sel + 1) + " / " + n, w * 0.5f, cr.top - ko - dp(6), tp);
        tp.setLetterSpacing(0f);

        /* 제목 — 고운바탕, 크림빛, 금빛 번짐 */
        float ty = Math.max(top + availH + h * 0.035f, cr.bottom + gap + dp(40));
        tp.setTextSize(h * 0.031f);
        tp.setColor(withA(0xfff4e8cc, la));
        tp.setShadowLayer(dp(1), 0, dp(2), withA(0xff000000, la));
        c.drawText(fit(cur.title, w * 0.92f), w / 2, ty, tp);
        tp.clearShadowLayer();

        /* 배지 — 「한패」 붉은 도장 · 「SP」 금테 */
        float bs = Math.max(dp(11), h * 0.0165f);
        tp.setTextSize(bs);
        String[] lb = new String[2]; int bn = 0;
        if (cur.pat) lb[bn++] = "한패";
        if (cur.sp) lb[bn++] = "SP";
        float padX = bs * 0.7f, gp = dp(10), bh = bs * 1.7f, bw = 0;
        for (int i = 0; i < bn; i++) bw += tp.measureText(lb[i]) + padX * 2 + (i > 0 ? gp : 0);
        float bx2 = (w - bw) / 2, byTop = ty + h * 0.016f;
        for (int i = 0; i < bn; i++) {
            float tw2 = tp.measureText(lb[i]);
            tr.set(bx2, byTop, bx2 + tw2 + padX * 2, byTop + bh);
            fx.reset(); fx.setAntiAlias(true);
            if ("한패".equals(lb[i])) {
                c.save(); c.rotate(-2f, tr.centerX(), tr.centerY());
                fx.setColor(withA(0xffb3301f, la)); c.drawRoundRect(tr, dp(2), dp(2), fx);
                tp.setColor(withA(0xfffff1dc, la));
                c.drawText(lb[i], tr.centerX(), tr.top + bh * 0.70f, tp);
                c.restore();
            } else {
                fx.setStyle(Paint.Style.STROKE); fx.setStrokeWidth(Math.max(1f, dp(1))); fx.setColor(withA(0xffd9a441, la));
                c.drawRoundRect(tr, dp(2), dp(2), fx);
                fx.setStyle(Paint.Style.FILL);
                tp.setColor(withA(0xffe8c477, la));
                c.drawText(lb[i], tr.centerX(), tr.top + bh * 0.70f, tp);
            }
            bx2 += tw2 + padX * 2 + gp;
        }
        tp.setTypeface(android.graphics.Typeface.DEFAULT);
        float ly2 = byTop + bh + h * 0.024f;
        if (!cur.sub.isEmpty()) {
            tp.setColor(withA(0xff9b8f78, la));
            tp.setTextSize(h * 0.016f);
            c.drawText(fit(cur.sub, w * 0.9f), w / 2, ly2, tp);
            ly2 += h * 0.021f;
        }
        tp.setColor(withA(0xff7c715f, la));
        tp.setTextSize(h * 0.0145f);
        c.drawText(fit(romName(cur), w * 0.9f), w / 2, ly2, tp);
        /* 옵션 칩 — 먹색 반투명 + 금테 */
        {
            String ot = (cur.opt == null || cur.opt.isEmpty()) ? "실행 옵션" : cur.opt;
            String tail = "  옵션 ›";
            float ts = h * 0.0165f;
            tp.setTextSize(ts);
            ot = fitOpt(ot, tail, w * 0.86f);
            float cw = tp.measureText(ot + tail) + ts * 1.8f, ch = ts * 2.2f;
            float cyTop = ly2 + h * 0.018f;
            optChip.set(w / 2 - cw / 2, cyTop, w / 2 + cw / 2, cyTop + ch);
            fx.reset(); fx.setAntiAlias(true);
            fx.setColor(withA(0xb80a0806, la));
            c.drawRoundRect(optChip, ch / 2, ch / 2, fx);
            fx.setStyle(Paint.Style.STROKE); fx.setStrokeWidth(Math.max(1f, dp(1))); fx.setColor(withA(0x80d9a441, la));
            c.drawRoundRect(optChip, ch / 2, ch / 2, fx);
            fx.setStyle(Paint.Style.FILL);
            float tx0 = optChip.centerX() - tp.measureText(ot + tail) / 2, by = optChip.centerY() + ts * 0.36f;
            tp.setTextAlign(Paint.Align.LEFT);
            tp.setColor(withA(0xffd6ccb4, la));
            c.drawText(ot, tx0, by, tp);
            tp.setColor(withA(0xffe3b05a, la));
            c.drawText(tail, tx0 + tp.measureText(ot), by, tp);
            tp.setTextAlign(Paint.Align.CENTER);
        }
        drawPad(c);
    }

    /* ── 포켓 ────────────────────────────────────────────────────── */

    private void drawPocket(Canvas c, float w, float h, float t) {
        float mn = Math.min(w, h);
        boolean land = w > h * 1.05f, intro = introAt > 0;
        ThemeDraw.pocketBody(c, 0, 0, w, h, fx);
        RectF bz = land ? new RectF(w * 0.17f, h * 0.035f, w * 0.83f, h * 0.80f)
                        : new RectF(w * 0.025f, h * 0.017f, w * 0.975f, h * 0.632f);
        float big = Math.min(dp(64), bz.width() * 0.10f);
        ThemeDraw.pocketBezel(c, bz, dp(18), big, fx);
        float inX = Math.max(dp(12), bz.width() * 0.04f), inTop = Math.max(dp(10), bz.height() * 0.045f),
              inBot = Math.max(dp(30), bz.height() * 0.105f);
        RectF lcd = new RectF(bz.left + inX, bz.top + inTop, bz.right - inX, bz.bottom - inBot);
        ThemeDraw.lcdBase(c, lcd, fx);
        ThemeDraw.pocketSilk(c, bz, lcd, fx, dp(1));

        /* 인트로 — 액정이 켜지듯: 가운데 흰 줄이 위아래로 퍼진 뒤 목록이 떠오른다 */
        float la1 = intro ? Math.max(0f, Math.min(1f, (t - 0.42f) / 0.33f)) : 1f;
        int la = (int) (255 * la1);
        c.save();
        c.clipRect(lcd);
        if (la > 0) drawPocketScreen(c, lcd, la);
        c.restore();
        ThemeDraw.lcdGlass(c, lcd, fx, dp(1));
        if (intro && t < 0.62f) {
            float q = Math.min(1f, Math.max(0f, (t - 0.10f) / 0.30f));
            float bandH = Math.max(dp(2), lcd.height() * ease(q));
            int a = t < 0.40f ? 230 : (int) (230 * Math.max(0f, 1f - (t - 0.40f) / 0.22f));
            fx.reset(); fx.setAntiAlias(true);
            fx.setColor(withA(0xffe9f1ff, a));
            c.save(); c.clipRect(lcd);
            c.drawRect(lcd.left, lcd.centerY() - bandH / 2f, lcd.right, lcd.centerY() + bandH / 2f, fx);
            c.restore();
        }

        /* 몸통 — 스틱 · A · 알약 둘 · 스피커 구멍 */
        if (land) {
            dcx = w * 0.085f; dcy = h * 0.45f;
            aX = w * 0.915f; aY = h * 0.45f;
        } else {
            dcx = w * 0.25f; dcy = Math.max(h * 0.785f, bz.bottom + mn * 0.15f);
            aX = w * 0.775f; aY = Math.max(h * 0.775f, bz.bottom + mn * 0.13f);
        }
        dR = mn * 0.13f; aR = mn * 0.075f;
        if (art) {
            skin.dpad(c, dcx, dcy, dR * 0.86f, 0);
            Item cur0 = items == null || items.isEmpty() ? null : items.get(sel);
            skin.button(c, "a", aX, aY, aR, 0xff3f6fd1, false, false, "A");
            String al = cur0 != null && cur0.resume ? "이어하기" : "시작";
            embossLabel(c, al, aX, aY + aR * 1.18f + dp(16), Math.max(dp(11), mn * 0.022f));
        } else {
            drawPad(c);
        }
        float pw = Math.max(w * 0.13f, dp(58)), ph = pw * 0.27f;
        float py = land ? h * 0.905f : Math.max(h * 0.922f, aY + aR * 1.4f + dp(28));
        py = Math.min(py, h - ph - dp(26));
        float p1 = w / 2f - pw * 0.72f, p2 = w / 2f + pw * 0.72f;
        boolean nw = newer > 0;
        rubberPill(c, p1, py, pw, ph, false);
        rubberPill(c, p2, py, pw, ph, nw);
        float ls = Math.max(dp(10.5f), Math.min(dp(13), mn * 0.018f));
        embossLabel(c, "설정", p1, py + ph / 2f + dp(16), ls);
        if (nw) {
            tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); tp.setTextAlign(Paint.Align.CENTER); tp.setTextSize(ls);
            tp.setLetterSpacing(0.08f);
            tp.setColor(0xffc4560e);
            c.drawText("새 판 받기", p2, py + ph / 2f + dp(16), tp);
            tp.setLetterSpacing(0f);
        } else embossLabel(c, "업데이트", p2, py + ph / 2f + dp(16), ls);
        setR.set(p1 - pw * 0.62f, py - ph * 1.6f, p1 + pw * 0.62f, py + ph / 2f + dp(24));
        updR.set(p2 - pw * 0.62f, py - ph * 1.6f, p2 + pw * 0.62f, py + ph / 2f + dp(24));
        /* 스피커 구멍 */
        int cols = w < h * 0.6f ? 4 : 6, rows = 4;
        float dot = dp(3.4f), step = dp(7.2f) * 2;
        float gw = cols * step, gx = Math.min(w - dp(18) - gw, p2 + pw * 0.62f + dp(26)), gy = land ? h * 0.83f : py - ph * 1.6f;
        c.save();
        c.rotate(-14f, gx + gw / 2f, gy + rows * step / 2f);
        fx.reset(); fx.setAntiAlias(true);
        for (int r = 0; r < rows; r++)
            for (int k = 0; k < cols; k++) {
                float x = gx + k * step + step / 2f, y = gy + r * step + step / 2f;
                fx.setColor(0xb3ffffff); c.drawCircle(x, y + dp(0.8f), dot, fx);
                fx.setColor(0xff7d828c); c.drawCircle(x, y, dot, fx);
                fx.setColor(0xff5d626c); c.drawCircle(x, y - dot * 0.25f, dot * 0.7f, fx);
            }
        c.restore();
        cardTop = lcd.top; cardBottom = pocketCardBottom;
    }
    private float pocketCardBottom;

    /** 액정 안 — 로고·배지·카드·제목·배지·칩(갈무리 도트 글씨, 검은 2px 그림자). */
    private void drawPocketScreen(Canvas c, RectF lcd, int la) {
        float LW = lcd.width(), LH = lcd.height(), sh = Math.max(1.5f, dp(1.6f));
        float fs = Math.max(dp(11), Math.min(LH * 0.042f, LW * 0.05f));
        int n = items == null ? 0 : items.size();
        /* 로고 */
        float lx = lcd.left + LW * 0.03f, ly = lcd.top + LH * 0.035f;
        fx.reset();
        float sq = fs * 0.62f;
        fx.setColor(withA(0xff000000, la)); c.drawRect(lx + sh, ly + fs * 0.22f + sh, lx + sq + sh, ly + fs * 0.22f + sq + sh, fx);
        fx.setColor(withA(0xffffcc4d, la)); c.drawRect(lx, ly + fs * 0.22f, lx + sq, ly + fs * 0.22f + sq, fx);
        tp.setTypeface(Theme.font("pixel_b")); tp.setFakeBoldText(false);
        tp.setTextAlign(Paint.Align.LEFT); tp.setTextSize(fs);
        tp.setLetterSpacing(0.06f);
        float tx = lx + sq + fs * 0.45f, by = ly + fs * 0.92f;
        shadowText(c, "LEGACITO", tx, by, withA(0xffffffff, la), withA(0xff000000, la), sh);
        float lw = tp.measureText("LEGACITO");
        tp.setLetterSpacing(0f);
        logoR.set(lx - dp(10), ly - dp(10), tx + lw + dp(10), ly + fs * 1.3f + dp(6));
        if (channel != null) {
            tp.setTypeface(Theme.font("pixel")); tp.setTextSize(fs * 0.70f);
            float tw = tp.measureText(channel), bw = tw + fs * 0.7f, bh = fs * 1.15f;
            float bx = lcd.right - LW * 0.03f - bw, byt = ly + fs * 0.05f;
            fx.setColor(withA(0xff000000, la)); c.drawRect(bx + sh, byt + sh, bx + bw + sh, byt + bh + sh, fx);
            fx.setStyle(Paint.Style.STROKE); fx.setStrokeWidth(sh); fx.setColor(withA(0xffffcc4d, la));
            c.drawRect(bx, byt, bx + bw, byt + bh, fx);
            fx.setStyle(Paint.Style.FILL);
            tp.setColor(withA(0xffffcc4d, la));
            c.drawText(channel, bx + fs * 0.35f, byt + bh * 0.74f, tp);
        }
        if (n == 0) {
            tp.setTypeface(Theme.font("pixel_b")); tp.setTextAlign(Paint.Align.CENTER); tp.setTextSize(fs);
            shadowText(c, "PocketCore/roms 에 롬을 넣으세요", lcd.centerX(), lcd.centerY(), withA(0xffe9eef7, la), withA(0xff000000, la), sh);
            pocketCardBottom = lcd.bottom;
            tp.setTypeface(android.graphics.Typeface.DEFAULT);
            return;
        }
        Item cur = items.get(sel);
        int cs = Math.max(1, Math.min((int) (LW * (LW < LH ? 0.62f : 0.42f)) / 160, (int) (LH * 0.56f) / 152));   /* 덮개(좁은 액정)는 더 크게 */
        int ss = Math.max(1, cs / 2);
        float ctop = lcd.top + LH * 0.115f + dp(4);
        RectF cr = new RectF();
        cardRect(cr, cur, lcd.centerX(), ctop + thumbH(cur) * cs / 2f, cs);
        if (n > 1) {
            for (int k = -1; k <= 1; k += 2) {
                Item it = items.get((sel + n + k) % n);
                RectF sr = new RectF();
                cardRect(sr, it, k < 0 ? lcd.left + LW * 0.107f : lcd.right - LW * 0.107f, cr.centerY(), ss);
                fx.setColor(withA(0xff000000, la * 102 / 255)); c.drawRect(sr, fx);
                blitThumb(c, it, sr, la * 102 / 255, 1f, 0xff8fa3c0);
                fx.setStyle(Paint.Style.STROKE); fx.setStrokeWidth(Math.max(2f, dp(2.5f))); fx.setColor(withA(0xff4b5a72, la * 160 / 255));
                c.drawRect(sr, fx);
                fx.setStyle(Paint.Style.FILL);
            }
        }
        float b3 = Math.max(2f, dp(2.6f));
        fx.setColor(withA(0x8c000000, la));
        tr.set(cr); tr.inset(-3 * b3, -3 * b3); tr.offset(2 * b3, 2 * b3);
        c.drawRect(tr, fx);
        fx.setColor(withA(0xffffcc4d, la)); tr.set(cr); tr.inset(-3 * b3, -3 * b3); c.drawRect(tr, fx);
        fx.setColor(withA(0xff0c121c, la)); tr.set(cr); tr.inset(-2 * b3, -2 * b3); c.drawRect(tr, fx);
        fx.setColor(withA(0xffffffff, la)); tr.set(cr); tr.inset(-b3, -b3); c.drawRect(tr, fx);
        fx.setColor(withA(0xff000000, la)); c.drawRect(cr, fx);
        blitThumb(c, cur, cr, la, 1f, 0xffe9eef7);
        if (n > 1) {                                            /* 노란 ◀ ▶ */
            float ar = Math.max(dp(6), fs * 0.5f), ax = 3 * b3 + dp(16) + ar;
            fx.setColor(withA(0xffffcc4d, la));
            Path pa = new Path();
            pa.moveTo(cr.left - ax - ar, cr.centerY()); pa.lineTo(cr.left - ax + ar * 0.7f, cr.centerY() - ar); pa.lineTo(cr.left - ax + ar * 0.7f, cr.centerY() + ar); pa.close();
            pa.moveTo(cr.right + ax + ar, cr.centerY()); pa.lineTo(cr.right + ax - ar * 0.7f, cr.centerY() - ar); pa.lineTo(cr.right + ax - ar * 0.7f, cr.centerY() + ar); pa.close();
            c.drawPath(pa, fx);
        }
        pocketCardBottom = cr.bottom + 3 * b3;
        /* 제목 */
        float tsz = Math.max(dp(14), Math.min(LH * 0.052f, LW * 0.062f));
        tp.setTypeface(Theme.font("pixel_b")); tp.setTextAlign(Paint.Align.CENTER); tp.setTextSize(tsz);
        float ty = cr.bottom + 3 * b3 + LH * 0.045f + tsz;
        shadowText(c, fit(cur.title, LW * 0.92f), lcd.centerX(), ty, withA(0xffffffff, la), withA(0xff000000, la), sh);
        /* 배지 — 색 칸 */
        float bs = fs * 0.66f;
        tp.setTypeface(Theme.font("pixel")); tp.setTextSize(bs);
        List<String> lb = new ArrayList<>(); List<Integer> bc = new ArrayList<>();
        if (cur.pat) { lb.add("한패"); bc.add(0xff4fd27a); }
        if (cur.sp) { lb.add("SP"); bc.add(0xffffcc4d); }
        lb.add((sel + 1) + "/" + n); bc.add(0xff8fa3c0);
        float pad = bs * 0.5f, gap = dp(8), bh = bs * 1.5f, bw = 0;
        for (int i = 0; i < lb.size(); i++) bw += tp.measureText(lb.get(i)) + pad * 2 + (i > 0 ? gap : 0);
        float bx = lcd.centerX() - bw / 2f, byt = ty + LH * 0.022f;
        for (int i = 0; i < lb.size(); i++) {
            float tw = tp.measureText(lb.get(i));
            fx.setColor(withA(0xff000000, la)); c.drawRect(bx + sh, byt + sh, bx + tw + pad * 2 + sh, byt + bh + sh, fx);
            fx.setColor(withA(bc.get(i), la)); c.drawRect(bx, byt, bx + tw + pad * 2, byt + bh, fx);
            tp.setColor(withA(0xff0c121c, la));
            c.drawText(lb.get(i), bx + pad + tw / 2f, byt + bh * 0.76f, tp);
            bx += tw + pad * 2 + gap;
        }
        float ly2 = byt + bh + LH * 0.045f;
        tp.setTextSize(fs * 0.6f);
        tp.setColor(withA(0xff6f84a3, la));
        String sub = cur.sub.isEmpty() ? romName(cur) : cur.sub;
        c.drawText(fit(sub, LW * 0.9f), lcd.centerX(), ly2, tp);
        /* 옵션 칩 */
        String ot = (cur.opt == null || cur.opt.isEmpty()) ? "실행 옵션" : cur.opt.replace(" · ", "·");
        String tail = "  옵션▶";
        float ts = fs * 0.66f;
        tp.setTextSize(ts);
        ot = fitOpt(ot, tail, LW * 0.86f);
        float cw = tp.measureText(ot + tail) + ts * 1.4f, ch = ts * 2.0f;
        float cyTop = Math.min(ly2 + LH * 0.03f, lcd.bottom - ch - LH * 0.03f);
        optChip.set(lcd.centerX() - cw / 2f, cyTop, lcd.centerX() + cw / 2f, cyTop + ch);
        fx.setColor(withA(0x59000000, la)); c.drawRect(optChip, fx);
        fx.setStyle(Paint.Style.STROKE); fx.setStrokeWidth(sh); fx.setColor(withA(0xff8fa3c0, la)); c.drawRect(optChip, fx);
        fx.setStyle(Paint.Style.FILL);
        float tx0 = optChip.centerX() - tp.measureText(ot + tail) / 2f, cby = optChip.centerY() + ts * 0.38f;
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setColor(withA(0xffdfe6f2, la)); c.drawText(ot, tx0, cby, tp);
        tp.setColor(withA(0xffffcc4d, la)); c.drawText(tail, tx0 + tp.measureText(ot), cby, tp);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    /** 도트 글자 + 오른쪽 아래 딱딱한 그림자(tp 의 글꼴·크기·정렬 그대로). */
    private void shadowText(Canvas c, String s, float x, float y, int col, int shCol, float sh) {
        tp.setColor(shCol); c.drawText(s, x + sh, y + sh, tp);
        tp.setColor(col); c.drawText(s, x, y, tp);
    }
    /** 몸통에 새긴 글자 — 흰 턱 위에 회색. */
    private void embossLabel(Canvas c, String s, float x, float y, float size) {
        tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); tp.setFakeBoldText(false);
        tp.setTextAlign(Paint.Align.CENTER); tp.setTextSize(size); tp.setLetterSpacing(0.16f);
        tp.setColor(0xb3ffffff); c.drawText(s, x, y + Math.max(1f, dp(1)), tp);
        tp.setColor(0xff6c717c); c.drawText(s, x, y, tp);
        tp.setLetterSpacing(0f); tp.setTypeface(android.graphics.Typeface.DEFAULT);
    }
    /** 비스듬한 고무 알약(설정·업데이트). hot = 새 판 있음(주황). */
    private void rubberPill(Canvas c, float cx, float cy, float pw, float ph, boolean hot) {
        c.save();
        c.rotate(-14f, cx, cy);
        tr.set(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f);
        fx.reset(); fx.setAntiAlias(true);
        fx.setColor(0x12000000); tr2.set(tr); tr2.inset(-dp(3), -dp(3)); c.drawRoundRect(tr2, ph, ph, fx);
        fx.setColor(0x40000000); tr2.set(tr); tr2.offset(0, dp(2)); c.drawRoundRect(tr2, ph / 2f, ph / 2f, fx);
        fx.setShader(new LinearGradient(0, tr.top, 0, tr.bottom, hot ? 0xfff0a35a : 0xff8a8f99, hot ? 0xffc4560e : 0xff5d626c, Shader.TileMode.CLAMP));
        c.drawRoundRect(tr, ph / 2f, ph / 2f, fx);
        fx.setShader(new LinearGradient(0, tr.top, 0, tr.centerY(), 0x59ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
        tr2.set(tr.left + ph * 0.4f, tr.top + 1, tr.right - ph * 0.4f, tr.centerY());
        c.drawRoundRect(tr2, ph * 0.3f, ph * 0.3f, fx);
        fx.setShader(null);
        c.restore();
    }

    /* ── 모던 ────────────────────────────────────────────────────── */

    private Bitmap ambBmp; private Bitmap ambOf;
    /** 흐린 바탕 — 지금 카드 썸네일을 아주 작게 줄였다가 늘려(이중선형) 번지게. 카드가 바뀔 때만 다시. */
    private void drawAmbient(Canvas c, float w, float h, Item cur) {
        Bitmap th = cur != null ? cur.thumb : null;
        if (th != null && th != ambOf) {
            try { ambBmp = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(th, 6, 6, true), 24, 24, true); }
            catch (Throwable e) { ambBmp = null; }
            ambOf = th;
        }
        fx.reset();
        c.drawColor(0xff0b0b10);
        if (th != null && ambBmp != null) {
            ColorMatrix cm = new ColorMatrix(); cm.setSaturation(1.5f);
            ColorMatrix dim = new ColorMatrix(); dim.setScale(0.62f, 0.62f, 0.62f, 1f);
            cm.postConcat(dim);
            fx.setColorFilter(new ColorMatrixColorFilter(cm));
            fx.setFilterBitmap(true);
            tr.set(-w * 0.15f, -h * 0.10f, w * 1.15f, h * 0.80f);
            c.drawBitmap(ambBmp, null, tr, fx);
            fx.setColorFilter(null);
        } else {
            fx.setShader(new RadialGradient(w * 0.5f, h * 0.18f, Math.max(w, h) * 0.7f,
                    new int[]{ 0xff3a2350, 0xff1b1430, 0xff0b0b10 }, new float[]{ 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fx);
        }
        fx.setShader(new LinearGradient(0, 0, 0, h, new int[]{ 0x1a0b0b10, 0x590b0b10, 0xeb0b0b10, 0xff0b0b10 },
                new float[]{ 0f, 0.38f, 0.66f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fx);
        fx.setShader(null);
    }

    private void drawModern(Canvas c, float w, float h, float t) {
        boolean land = w > h * 1.05f, intro = introAt > 0;
        int n = items == null ? 0 : items.size();
        Item cur = n > 0 ? items.get(sel) : null;
        drawAmbient(c, w, h, cur);
        float tabH = Math.max(dp(62), Math.min(dp(76), h * 0.09f));
        float la1 = intro ? ease(Math.max(0f, (t - 0.25f) / 0.6f)) : 1f;
        int la = (int) (255 * la1);
        float rise = (1f - la1) * dp(24);
        c.save();
        c.translate(0, rise);

        /* 로고 — 굵은 「Legacito」 + 시험 알약 */
        float lsz = Math.min(dp(26), w * 0.058f), lx = Math.max(dp(18), w * 0.04f), ly = Math.max(dp(18), h * 0.03f);
        tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); tp.setFakeBoldText(true);
        tp.setTextAlign(Paint.Align.LEFT); tp.setTextSize(lsz); tp.setLetterSpacing(-0.01f);
        tp.setColor(withA(0xffffffff, la));
        c.drawText("Legacito", lx, ly + lsz, tp);
        float lw = tp.measureText("Legacito");
        tp.setLetterSpacing(0f); tp.setFakeBoldText(false);
        logoR.set(lx - dp(10), ly - dp(10), lx + lw + dp(10), ly + lsz + dp(10));
        if (channel != null) {
            tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); tp.setTextSize(lsz * 0.42f);
            float tw = tp.measureText(channel), bh = lsz * 0.80f, bx = lx + lw + dp(10), byt = ly + lsz * 0.22f;
            tr.set(bx, byt, bx + tw + bh * 0.9f, byt + bh);
            fx.reset(); fx.setAntiAlias(true); fx.setColor(withA(0x29ffffff, la));
            c.drawRoundRect(tr, bh / 2f, bh / 2f, fx);
            tp.setColor(withA(0xffffe2c9, la));
            c.drawText(channel, bx + bh * 0.45f, byt + bh * 0.70f, tp);
        }
        float logoBottom = ly + lsz;

        if (cur == null) {
            tp.setTextAlign(Paint.Align.CENTER); tp.setTextSize(dp(15)); tp.setColor(withA(0xffd0d0da, la));
            c.drawText("PocketCore/roms 에 롬을 넣으세요", w / 2f, h * 0.4f, tp);
            c.restore();
            drawTabs(c, w, h, tabH);
            return;
        }

        /* 자리 — 세로: 위에서 아래로 한 줄 / 가로: 왼쪽 카드, 오른쪽 글·버튼 */
        float colL = land ? 0 : 0, colR = land ? w * 0.52f : w;
        float infoL = land ? w * 0.52f : 0, infoR = w;
        float cardsAvailH = land ? (h - tabH - logoBottom) * 0.80f : h * 0.36f;
        int cs = Math.max(2, Math.min((int) ((colR - colL) * (land ? 0.62f : 0.56f)) / 160, (int) cardsAvailH / 152));
        int ss = Math.max(1, cs - 1);
        float ccx = (colL + colR) / 2f;
        float ctop = land ? logoBottom + ((h - tabH - logoBottom) - thumbH(cur) * cs) / 2f
                          : Math.max(h * 0.115f, logoBottom + dp(22));
        RectF cr = new RectF();
        cardRect(cr, cur, ccx, ctop + thumbH(cur) * cs / 2f, cs);
        float rad = Math.min(dp(22), cr.width() * 0.07f);
        cardTop = cr.top; cardBottom = cr.bottom;
        if (n > 1) {
            for (int k = -1; k <= 1; k += 2) {
                Item it = items.get((sel + n + k) % n);
                float sw = thumbW(it) * ss;
                RectF sr = new RectF();
                cardRect(sr, it, ccx + k * (cr.width() / 2f + sw / 2f + dp(24)), cr.centerY(), ss);
                roundThumb(c, it, sr, rad * 0.8f, la * 115 / 255);
            }
        }
        fx.reset(); fx.setAntiAlias(true);
        fx.setColor(withA(0x8c000000, la));
        fx.setMaskFilter(new android.graphics.BlurMaskFilter(dp(22), android.graphics.BlurMaskFilter.Blur.NORMAL));
        tr.set(cr); tr.offset(0, dp(16)); tr.inset(dp(8), dp(4));
        c.drawRoundRect(tr, rad, rad, fx);
        fx.setMaskFilter(null);
        roundThumb(c, cur, cr, rad, la);
        fx.setStyle(Paint.Style.STROKE); fx.setStrokeWidth(Math.max(1f, dp(1))); fx.setColor(withA(0x24ffffff, la));
        c.drawRoundRect(cr, rad, rad, fx);
        fx.setStyle(Paint.Style.FILL);

        /* 글·버튼 — 세로는 카드 아래, 가로는 오른쪽 칸 */
        float icx = (infoL + infoR) / 2f, iw = (infoR - infoL);
        float y = land ? Math.max(logoBottom + dp(24), h * 0.16f) : cr.bottom + dp(26);
        /* 쪽 점 */
        int dots = Math.min(n, 7), first = n <= 7 ? 0 : Math.max(0, Math.min(n - 7, sel - 3));
        float dg = dp(7), dw = dp(22), dd = dp(7), total = (dots - 1) * (dd + dg) + dw;
        float dx = icx - total / 2f;
        fx.reset(); fx.setAntiAlias(true);
        for (int i = 0; i < dots; i++) {
            boolean on = first + i == sel;
            fx.setColor(withA(on ? 0xffff9147 : 0x4dffffff, la));
            tr.set(dx, y - dd / 2f, dx + (on ? dw : dd), y + dd / 2f);
            c.drawRoundRect(tr, dd / 2f, dd / 2f, fx);
            dx += (on ? dw : dd) + dg;
        }
        /* 제목 */
        float tsz = Math.min(dp(28), iw * 0.061f);
        tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); tp.setFakeBoldText(true);
        tp.setTextAlign(Paint.Align.CENTER); tp.setTextSize(tsz); tp.setLetterSpacing(-0.02f);
        tp.setColor(withA(0xffffffff, la));
        y += dp(18) + tsz;
        c.drawText(fit(cur.title, iw * 0.9f), icx, y, tp);
        tp.setLetterSpacing(0f); tp.setFakeBoldText(false);
        /* 메타 — 한패 · SP · 파일 이름 */
        float ms = Math.max(dp(11), Math.min(dp(13), iw * 0.034f));
        tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); tp.setTextSize(ms);
        List<String> tags = new ArrayList<>(); List<int[]> tc = new ArrayList<>();
        if (cur.pat) { tags.add("한패"); tc.add(new int[]{ 0x334cc86e, 0xff8fe7a5 }); }
        if (cur.sp) { tags.add("SP"); tc.add(new int[]{ 0x33ffaa3c, 0xffffc27a }); }
        String fn = romName(cur);
        tp.setTypeface(android.graphics.Typeface.DEFAULT);
        float fnw = tp.measureText(fn);
        tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        float tpad = ms * 0.65f, tg = dp(8), mw = 0;
        for (String s : tags) mw += tp.measureText(s) + tpad * 2 + tg;
        float maxFn = iw * 0.9f - mw;
        tp.setTypeface(android.graphics.Typeface.DEFAULT);
        if (fnw > maxFn) { fn = fit(fn, Math.max(dp(40), maxFn)); fnw = tp.measureText(fn); }
        float mx = icx - (mw + fnw) / 2f, my = y + dp(14);
        float mh = ms * 1.7f;
        for (int i = 0; i < tags.size(); i++) {
            tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            float tw = tp.measureText(tags.get(i));
            tr.set(mx, my, mx + tw + tpad * 2, my + mh);
            fx.setColor(withA(tc.get(i)[0], la)); c.drawRoundRect(tr, dp(8), dp(8), fx);
            tp.setColor(withA(tc.get(i)[1], la));
            c.drawText(tags.get(i), tr.centerX(), my + mh * 0.70f, tp);
            mx += tw + tpad * 2 + tg;
        }
        tp.setTypeface(android.graphics.Typeface.DEFAULT);
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setColor(withA(0xff9a9aa8, la));
        c.drawText(fn, mx, my + mh * 0.70f, tp);
        tp.setTextAlign(Paint.Align.CENTER);
        if (!cur.sub.isEmpty()) {
            my += mh + dp(6);
            tp.setColor(withA(0xff8d8d9b, la)); tp.setTextSize(ms * 0.95f);
            c.drawText(fit(cur.sub, iw * 0.9f), icx, my + ms, tp);
            my += ms * 0.4f;
        }
        /* 옵션 칩 — 하나씩, 줄바꿈 */
        float cy0 = my + mh + dp(16);
        float chipBottom = chips(c, cur, icx, iw, cy0, la);
        /* 재생 버튼 · ◀ ▶ */
        float ph = dp(56), pw = Math.max(dp(170), Math.min(dp(330), iw * 0.50f));
        float contentBottom = h - tabH - rise;
        float pcy = land ? Math.max(chipBottom + dp(48), contentBottom - dp(96))
                         : Math.max(chipBottom + ph / 2f + dp(30), contentBottom - dp(118));
        pcy = Math.min(pcy, contentBottom - ph / 2f - dp(34));
        playR.set(icx - pw / 2f, pcy - ph / 2f, icx + pw / 2f, pcy + ph / 2f);
        fx.reset(); fx.setAntiAlias(true);
        fx.setColor(withA(0x59ff8434, la));
        fx.setMaskFilter(new android.graphics.BlurMaskFilter(dp(16), android.graphics.BlurMaskFilter.Blur.NORMAL));
        tr.set(playR); tr.offset(0, dp(10)); tr.inset(dp(10), dp(6));
        c.drawRoundRect(tr, ph / 2f, ph / 2f, fx);
        fx.setMaskFilter(null);
        fx.setShader(new LinearGradient(0, playR.top, 0, playR.bottom, withA(0xffffa060, la), withA(0xffff8434, la), Shader.TileMode.CLAMP));
        c.drawRoundRect(playR, ph / 2f, ph / 2f, fx);
        fx.setShader(null);
        String pl = cur.resume ? "이어하기" : "시작";
        float psz = Math.min(dp(20), ph * 0.36f);
        tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); tp.setFakeBoldText(true); tp.setTextSize(psz);
        float plw = tp.measureText(pl), tri = psz * 0.85f, tg2 = psz * 0.55f;
        float px0 = icx - (tri + tg2 + plw) / 2f;
        fx.setColor(withA(0xff2a1206, la));
        Path pp = new Path();
        pp.moveTo(px0, pcy - tri * 0.62f); pp.lineTo(px0 + tri, pcy); pp.lineTo(px0, pcy + tri * 0.62f); pp.close();
        c.drawPath(pp, fx);
        tp.setTextAlign(Paint.Align.LEFT); tp.setColor(withA(0xff2a1206, la));
        c.drawText(pl, px0 + tri + tg2, pcy + psz * 0.36f, tp);
        tp.setFakeBoldText(false); tp.setTextAlign(Paint.Align.CENTER);
        float rs = dp(50);
        float rx = Math.max(infoL + rs / 2f + dp(14), (infoL + playR.left) / 2f);
        float rx2 = Math.min(infoR - rs / 2f - dp(14), (playR.right + infoR) / 2f);
        if (playR.left - (rx + rs / 2f) < dp(8)) rx = playR.left - dp(10) - rs / 2f;
        if ((rx2 - rs / 2f) - playR.right < dp(8)) rx2 = playR.right + dp(10) + rs / 2f;
        roundBtn(c, rx, pcy, rs, false, la);
        roundBtn(c, rx2, pcy, rs, true, la);
        prevR.set(rx - rs * 0.6f, pcy - rs * 0.6f, rx + rs * 0.6f, pcy + rs * 0.6f);
        nextR.set(rx2 - rs * 0.6f, pcy - rs * 0.6f, rx2 + rs * 0.6f, pcy + rs * 0.6f);
        /* 이어하기 안내 */
        tp.setTypeface(android.graphics.Typeface.DEFAULT); tp.setTextSize(Math.max(dp(11), Math.min(dp(12.5f), iw * 0.032f)));
        tp.setColor(withA(0xff8d8d9b, la));
        c.drawText(resumeText(cur), icx, playR.bottom + dp(24), tp);
        c.restore();
        playR.offset(0, rise); prevR.offset(0, rise); nextR.offset(0, rise); optChip.offset(0, rise); logoR.offset(0, rise);
        drawTabs(c, w, h, tabH);
    }

    private String resumeText(Item it) {
        if (!it.resume) return "처음부터 시작";
        if (it.resumeAt <= 0) return "자동 저장한 자리부터";
        long m = Math.max(0, (System.currentTimeMillis() - it.resumeAt) / 60000L);
        String ago = m < 1 ? "방금" : m < 60 ? m + "분 전" : m < 60 * 24 ? (m / 60) + "시간 전" : (m / (60 * 24)) + "일 전";
        return "자동 저장 · " + ago;
    }

    /** 둥근 모서리 카드 — 안쪽은 정수배 무필터 그대로, 모서리만 잘라 낸다. */
    private void roundThumb(Canvas c, Item it, RectF r, float rad, int alpha) {
        c.save();
        Path clip = new Path();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        px.setColor(withA(0xff000000, alpha)); c.drawRect(r, px);
        blitThumb(c, it, r, alpha, 1f, 0xffd0d0da);
        c.restore();
    }
    private void roundBtn(Canvas c, float cx, float cy, float s, boolean right, int la) {
        fx.reset(); fx.setAntiAlias(true);
        fx.setColor(withA(0x14ffffff, la)); c.drawCircle(cx, cy, s / 2f, fx);
        fx.setStyle(Paint.Style.STROKE); fx.setStrokeWidth(Math.max(1f, dp(1))); fx.setColor(withA(0x24ffffff, la));
        c.drawCircle(cx, cy, s / 2f - dp(0.5f), fx);
        fx.setStrokeWidth(dp(2.2f)); fx.setStrokeCap(Paint.Cap.ROUND); fx.setStrokeJoin(Paint.Join.ROUND);
        fx.setColor(withA(0xffe6e6ee, la));
        float k = s * 0.13f;
        Path pa = new Path();
        if (right) { pa.moveTo(cx - k * 0.6f, cy - k * 1.4f); pa.lineTo(cx + k * 0.8f, cy); pa.lineTo(cx - k * 0.6f, cy + k * 1.4f); }
        else { pa.moveTo(cx + k * 0.6f, cy - k * 1.4f); pa.lineTo(cx - k * 0.8f, cy); pa.lineTo(cx + k * 0.6f, cy + k * 1.4f); }
        c.drawPath(pa, fx);
        fx.setStyle(Paint.Style.FILL); fx.setStrokeCap(Paint.Cap.BUTT);
    }
    /** 실행 옵션을 칩 여러 개로(가운데 정렬, 넘치면 다음 줄) + 「옵션 ›」. 아래 끝 y 를 돌려준다. */
    private float chips(Canvas c, Item cur, float cx, float width, float top, int la) {
        List<String> cs = new ArrayList<>();
        if (cur.opt != null && !cur.opt.isEmpty()) for (String s : cur.opt.split(" · ")) if (!s.trim().isEmpty()) cs.add(s.trim());
        cs.add(cs.isEmpty() ? "실행 옵션 ›" : "옵션 ›");
        float ts = Math.max(dp(11.5f), Math.min(dp(13), width * 0.036f));
        tp.setTypeface(android.graphics.Typeface.DEFAULT); tp.setTextSize(ts);
        float padx = dp(12), ch = ts + dp(14), gap = dp(8), maxW = width * 0.88f;
        List<List<String>> rows = new ArrayList<>();
        List<Float> rw = new ArrayList<>();
        List<String> row = new ArrayList<>(); float w0 = 0;
        for (String s : cs) {
            float cw = tp.measureText(s) + padx * 2;
            if (!row.isEmpty() && w0 + gap + cw > maxW) { rows.add(row); rw.add(w0); row = new ArrayList<>(); w0 = 0; }
            w0 += (row.isEmpty() ? 0 : gap) + cw; row.add(s);
        }
        rows.add(row); rw.add(w0);
        if (rows.size() > 2) {                                   /* 두 줄까지 — 넘치면 둘째 줄 끝에 «…» */
            List<String> r2 = rows.get(1); r2.add(r2.size() - 1, "…");
            while (rows.size() > 2) rows.remove(2);
            if (!r2.get(r2.size() - 1).endsWith("›")) r2.add("옵션 ›");
            float ww = 0; for (String s : r2) ww += tp.measureText(s) + padx * 2 + gap; rw.set(1, ww - gap);
            while (rw.size() > 2) rw.remove(2);
        }
        float y = top;
        float l0 = Float.MAX_VALUE, r0 = 0;
        fx.reset(); fx.setAntiAlias(true);
        for (int ri = 0; ri < rows.size(); ri++) {
            float x = cx - rw.get(ri) / 2f;
            for (String s : rows.get(ri)) {
                float cw = tp.measureText(s) + padx * 2;
                boolean acc = s.endsWith("›");
                tr.set(x, y, x + cw, y + ch);
                fx.setStyle(Paint.Style.FILL); fx.setColor(withA(acc ? 0x29ff9147 : 0x14ffffff, la));
                c.drawRoundRect(tr, ch / 2f, ch / 2f, fx);
                fx.setStyle(Paint.Style.STROKE); fx.setStrokeWidth(Math.max(1f, dp(1))); fx.setColor(withA(acc ? 0x73ff9147 : 0x21ffffff, la));
                c.drawRoundRect(tr, ch / 2f, ch / 2f, fx);
                fx.setStyle(Paint.Style.FILL);
                tp.setFakeBoldText(acc);
                tp.setColor(withA(acc ? 0xffffb27d : 0xffe6e6ee, la));
                c.drawText(s, tr.centerX(), y + ch / 2f + ts * 0.36f, tp);
                tp.setFakeBoldText(false);
                l0 = Math.min(l0, x); r0 = Math.max(r0, x + cw);
                x += cw + gap;
            }
            y += ch + gap;
        }
        optChip.set(l0, top, r0, y - gap);
        return y - gap;
    }
    /** 아래 탭 — 게임(지금) · 설정 · 업데이트(새 판이면 빨간 점). */
    private void drawTabs(Canvas c, float w, float h, float tabH) {
        float top = h - tabH;
        fx.reset(); fx.setAntiAlias(true);
        fx.setColor(0xeb121218); c.drawRect(0, top, w, h, fx);
        fx.setColor(0x12ffffff); c.drawRect(0, top, w, top + Math.max(1f, dp(1)), fx);
        String[] lb = { "게임", "설정", newer > 0 ? "새 판 받기" : "업데이트" };
        String[] ic = { "ic_game", "ic_gear", "ic_down" };
        float tw = w / 3f, isz = Math.min(dp(26), tabH * 0.36f);
        for (int i = 0; i < 3; i++) {
            float cx = tw * i + tw / 2f, iy = top + tabH * 0.40f;
            boolean on = i == 0, hot = i == 2 && newer > 0;
            int col = on ? 0xffffb27d : hot ? 0xffffd2b0 : 0xff8d8d9b;
            if (on) {
                fx.setColor(0x2eff9147);
                tr.set(cx - isz * 1.2f, iy - isz * 0.72f, cx + isz * 1.2f, iy + isz * 0.72f);
                c.drawRoundRect(tr, isz * 0.72f, isz * 0.72f, fx);
            }
            Bitmap b = Theme.bmp(ic[i]);
            if (b != null) {
                fx.setColorFilter(new android.graphics.PorterDuffColorFilter(col, android.graphics.PorterDuff.Mode.SRC_IN));
                fx.setFilterBitmap(true);
                tr.set(cx - isz / 2f, iy - isz / 2f, cx + isz / 2f, iy + isz / 2f);
                c.drawBitmap(b, null, tr, fx);
                fx.setColorFilter(null);
            }
            if (hot) { fx.setColor(0xffff5a4f); c.drawCircle(cx + isz * 0.62f, iy - isz * 0.5f, dp(4), fx); }
            tp.setTypeface(on || hot ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            tp.setTextAlign(Paint.Align.CENTER); tp.setTextSize(Math.max(dp(10.5f), Math.min(dp(12), tabH * 0.17f)));
            tp.setColor(col);
            c.drawText(lb[i], cx, top + tabH * 0.83f, tp);
        }
        tp.setTypeface(android.graphics.Typeface.DEFAULT);
        setR.set(tw, top, tw * 2, h);
        updR.set(tw * 2, top, w, h);
    }

    /* ── 패드식 컨트롤 — A(실행)=안쪽 아래 · B(업뎃)=바깥 위 ── */
    private float dcx, dcy, dR, aX, aY, aR, bX, bY, bR;
    private final RectF optR = new RectF();
    private final RectF optChip = new RectF();      /* 카드 아래 실행 옵션 칩 */
    private String lastChipLog = "", lastBtnLog = "";
    /* 121: 캔버스에 그린 누를 자리 — 로고(테마 넘기기)·설정·업데이트(포켓·모던)·이전/다음·재생(모던) */
    private final RectF logoR = new RectF(), setR = new RectF(), updR = new RectF(), prevR = new RectF(), nextR = new RectF(), playR = new RectF();
    private float cardTop = 0, cardBottom = 0;      /* 카드 줄 세로 범위 — 탭으로 넘기기·시작 */

    /** ui_shots 가 캔버스 버튼을 누를 수 있게 자리를 로그로(바뀔 때만). */
    private void logButtons() {
        if (!optChip.isEmpty()) {
            String log = optChip.toShortString();
            if (!log.equals(lastChipLog)) { lastChipLog = log; android.util.Log.i("PocketUi", "optchip " + Math.round(optChip.centerX()) + " " + Math.round(optChip.centerY())); }
        }
        String b = (setR.isEmpty() ? "" : "set " + Math.round(setR.centerX()) + " " + Math.round(setR.centerY()))
                 + (updR.isEmpty() ? "" : " upd " + Math.round(updR.centerX()) + " " + Math.round(updR.centerY()))
                 + (logoR.isEmpty() ? "" : " logo " + Math.round(logoR.centerX()) + " " + Math.round(logoR.centerY()))
                 + (playR.isEmpty() ? "" : " play " + Math.round(playR.centerX()) + " " + Math.round(playR.centerY()));
        if (!b.equals(lastBtnLog)) { lastBtnLog = b; android.util.Log.i("PocketUi", "btns " + Theme.get().id + " " + b); }
    }

    /* 런처 패드 — 십자(카드 넘기기) + A(실행)만. B(업뎃)·OPTION(설정)은 아래 줄 「설정 · 업데이트 확인」과 같은 일을 해서
       뺐다(유저 2026-10-10 「뺄 거 빼고」). 버튼 모양은 게임 안 터치 패드와 같은 아트(설정 「버튼 모양」). */
    private void drawPad(Canvas c) {
        float w = getWidth(), h = getHeight(), mn = Math.min(w, h);
        dcx = w * 0.22f; dcy = h * 0.80f; dR = mn * 0.13f;
        aX = w * 0.78f; aY = h * 0.80f; aR = mn * 0.075f;
        bR = 0; optR.setEmpty();
        Theme th = Theme.get();
        if (art) {
            skin.dpad(c, dcx, dcy, dR, 0);
            skin.button(c, "a", aX, aY, aR, 0xff3f6fd1, false, false, "A");
        } else {
            float arm = dR * 0.42f;
            tp.setStyle(Paint.Style.FILL);
            tp.setColor(0x22ffffff);
            c.drawRect(dcx - arm, dcy - dR, dcx + arm, dcy - arm, tp);
            c.drawRect(dcx - arm, dcy + arm, dcx + arm, dcy + dR, tp);
            tp.setColor(0x3cffffff);
            c.drawRect(dcx - dR, dcy - arm, dcx - arm, dcy + arm, tp);
            c.drawRect(dcx + arm, dcy - arm, dcx + dR, dcy + arm, tp);
            tp.setColor(0x22ffffff);
            c.drawRect(dcx - arm, dcy - arm, dcx + arm, dcy + arm, tp);
            tp.setColor(0x38ffffff);
            c.drawCircle(aX, aY, aR, tp);
            tp.setColor(0xffdddddd);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(aR * 0.7f);
            c.drawText("A", aX, aY + aR * 0.25f, tp);
        }
        tp.setStyle(Paint.Style.FILL);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(mn * 0.026f);
        Item cur = (items == null || items.isEmpty()) ? null : items.get(sel);
        String al = cur != null && cur.resume ? "이어하기" : "시작";
        if (th.ink()) {
            tp.setTypeface(Theme.font("ink_b")); tp.setLetterSpacing(0.08f);
            tp.setColor(0xffcdbd98);
            c.drawText(al, aX, aY + aR + aR * 0.62f + (art ? aR * 0.16f : 0), tp);
            tp.setLetterSpacing(0f); tp.setTypeface(android.graphics.Typeface.DEFAULT);
        } else {
            tp.setColor(0x99ffffff);
            c.drawText(al, aX, aY + aR + aR * 0.62f, tp);
        }
        tp.setTextAlign(Paint.Align.LEFT);
    }

    private static float dist(float x, float y, float cx2, float cy2) {
        float dx = x - cx2, dy = y - cy2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /* ── 입력 ───────────────────────────────────────────────────── */

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (items == null) return true;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            if (!logoR.isEmpty() && logoR.contains(x, y)) {      /* 121: 로고 = 다음 테마 */
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) listener.onTheme();
                downX = -1;
                return true;
            }
            if (!setR.isEmpty() && setR.contains(x, y)) {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) listener.onSettings();
                downX = -1;
                return true;
            }
            if (!updR.isEmpty() && updR.contains(x, y)) {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) listener.onUpdate();
                downX = -1;
                return true;
            }
            if (dR > 0 && dist(x, y, dcx, dcy) < dR * 1.35f) {
                if (x < dcx - dR * 0.2f) move(-1);
                else if (x > dcx + dR * 0.2f) move(1);
                downX = -1;
                return true;
            }
            if (!items.isEmpty() && ((aR > 0 && dist(x, y, aX, aY) < aR * 1.35f) || (!playR.isEmpty() && playR.contains(x, y)))) {
                if (listener != null) listener.onLaunch(items.get(sel).rom);
                downX = -1;
                return true;
            }
            if (!prevR.isEmpty() && prevR.contains(x, y)) { move(-1); downX = -1; return true; }
            if (!nextR.isEmpty() && nextR.contains(x, y)) { move(1); downX = -1; return true; }
            if (bR > 0 && dist(x, y, bX, bY) < bR * 1.35f) {
                if (listener != null) listener.onUpdate();
                downX = -1;
                return true;
            }
            if (optR.contains(x, y)) {
                if (listener != null) listener.onSettings();
                downX = -1;
                return true;
            }
            if (!items.isEmpty() && !optChip.isEmpty() && optChip.contains(x, y)) {
                if (listener != null) listener.onOptions(items.get(sel).rom);
                downX = -1;
                return true;
            }
            downX = x;
            return true;
        case MotionEvent.ACTION_UP:
            if (downX < 0 || items.isEmpty()) return true;
            float dx = x - downX;
            if (Math.abs(dx) > getWidth() / 8f) {
                move(dx < 0 ? 1 : -1);
            } else if (y >= cardTop && y < cardBottom) {
                float fx2 = x / getWidth();
                if (fx2 < 0.33f) move(-1);
                else if (fx2 > 0.67f) move(1);
                else if (listener != null) listener.onLaunch(items.get(sel).rom);
            }
            return true;
        }
        return true;
    }

    private void move(int d) {
        int n = items.size();
        if (n == 0) return;
        sel = (sel + d % n + n) % n;
        android.util.Log.i("PocketUi", "sel " + (sel + 1) + " / " + n);     /* ui_shots 가 카드가 넘어갔는지 본다(캔버스라 노드가 없다) */
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        invalidate();
    }
}
