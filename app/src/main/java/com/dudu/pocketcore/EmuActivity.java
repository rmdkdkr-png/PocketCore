package com.dudu.pocketcore;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class EmuActivity extends Activity {

    private GLSurfaceView gl;
    private FrameLayout root;
    private PadView pad;
    private String romPath;
    private LaunchSheet sheet;                 /* 게임 중 옵션 창(실행 전 선택 창과 같은 것) */
    private long romMtime;
    private int padMask = 0, keyMask = 0;
    /* 짧은 방향 걸쇠(3프레임)를 넣었다가 유저 지시로 뺐다(2026-09-06).
       방향을 붙잡아 두면 236 처럼 방향이 빨리 갈아타는 커맨드가 뭉갠다 —
       짧은 탭이 반쯤 새는 것보다 그쪽이 크다는 판단이다.
       ★ 다시 필요해지면 «늘리지» 말고 «코어가 한 번 읽어 갈 때까지만 안 지우는» 쪽으로 짜라.
         정확히 한 프레임만 보장하니 뭉개지 않는다(이식소 제안). */
    private String romType = "svc";   /* Games 표의 id. 모르는 롬이면 "svc"(순정 코어) */
    private Games.Game game;          /* 표에 있는 게임이면 여기 — 코어·한패·음성팩이 다 들어 있다 */
    private boolean patched = false;  /* 번역 패치 사본을 실행 중인가 */
    private String lang = "ko";       /* ko=번역 패치 / ja·en=롬에 원래 든 언어 */
    private int slot = 1;
    private boolean autoSave = true;  /* 나갈 때 자동 저장, 열 때 이어하기 */
    /* v4 로스터 11인 — 재캐스팅 아웃(샤를로트/소게츠/모로즈미/유가) 제외 */
    private volatile boolean loaded = false;   /* GL 스레드(내리기)와 UI 스레드가 같이 본다 */
    /* 가상 리그 랭크전 — LeagueActivity 가 경기 id 를 주면 이 창은 랭크전 모드다(League.java · league.c).
       원작 세이브·오토세이브와 따로 돈다: 세이브 폴더는 League.saveDir(), 이어하기는 경기 체크포인트만. */
    static final String EXTRA_LEAGUE = "league_match", EXTRA_LEAGUE_RESUME = "league_resume";
    /* 게임 속 리그(허브) — 게임을 랭크전 판으로 켜고, 타이틀 «2인 대전» 자리(«리그 대전»)에서 상대를 고르게 한다(league.c 허브) */
    static final String EXTRA_LEAGUE_HUB = "league_hub";
    private boolean leagueHub = false;
    /* 연습 대전(practice.c) — 일반 세션이고 판정 주소를 확인한 롬 판이면 타이틀 «2 인 대전» 자리가 «연습 대전». 실행 창 링크로 열면 상대 선택까지 자동 */
    static final String EXTRA_PRACTICE = "practice_hub";
    private boolean practiceHub = false, practiceAutoUsed = false;
    /** 랭크전 판으로 돌고 있나 — 한 경기(leagueId) 또는 게임 속 리그 세션 전체. 롬 사본·동봉 코어·리그 세이브 폴더·저장/불러오기 막기 */
    private boolean ranked() { return leagueId != null || leagueHub; }
    private String leagueId = null;            /* 랭크전이면 경기 id — 기록은 늘 이 id 로 League 에서 다시 찾는다 */
    private boolean leagueArmed = false, leagueDone = false;
    private android.app.AlertDialog leagueDlg;  /* 나가기 창 — 떠 있는 동안 게임은 멈춘다 */
    private int leagueRetry = 0;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        /* 볼륨 키 = 미디어(게임 소리) 음량 — 안 정하면 삼성은 «재생 중»을 못 알아챌 때 벨소리 음량을 바꾼다(유저 2026-10-10 「볼륨 조절이 앱에서 안 되던데」) */
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        romPath = getIntent().getStringExtra("rom");
        leagueId = getIntent().getStringExtra(EXTRA_LEAGUE);
        if (leagueId != null && League.matchById(leagueId) == null) leagueId = null;
        leagueHub = leagueId == null && getIntent().getBooleanExtra(EXTRA_LEAGUE_HUB, false);
        game = Games.identify(romPath);
        romType = (game != null) ? game.id : "svc";
        lang = readLang();
        {   /* 언어 하나로 두 축을 함께 움직인다.
               한국어면 번역 패치를 **사본**에 입히고(유저 롬은 안 건드린다) 그 패치가 덮은
               언어로 코어를 맞춘다. 일본어·영어는 롬에 원래 든 것이라 패치가 필요 없고
               코어 설정만 바꾸면 된다. ngp_language 는 코어가 뜰 때 읽으므로 **로드 전에** 써야 한다. */
            String p = ranked()
                    ? Patcher.resolveLeague(this, romPath, game, lang)   /* 랭크전: 원본 + 번역 패치만(고정 판) */
                    : Patcher.resolve(this, romPath, game, lang,
                    "enabled".equals(readOpt("pocketcore_svc_fastrom", "disabled")),
                    true);                                   /* 조작 패치(mods) 중 켜진 것 적용 */
            patched = !p.equals(romPath);
            romPath = p;
            persistOption("ngp_language", Games.ngpLanguage(game, lang));
        }
        autoSave = "enabled".equals(readOpt("pocketcore_autosave", "enabled"));
        if (ranked()) autoSave = false;   /* 랭크전은 원작 오토세이브를 읽지도 쓰지도 않는다 */
        try {   /* 판독 오버레이 — 코어가 뜰 때 getenv 로 한 번 읽으므로 **로드 전에** 심는다.
                   화면 왼쪽 위에 「내 동작번호|상대반응」을 상시 표시 — 영상만 찍어도
                   무슨 기술이 실제로 나갔는지(약/강 포함) 게임이 직접 말해 준다. */
            android.system.Os.setenv("SVCSP_ACTSHOW",
                    "enabled".equals(readOpt("pocketcore_svc_actshow", "disabled")) ? "1" : "0", true);
        } catch (Exception ignored) { }
        Orient.apply(this);                       /* 화면 방향 설정(자동/세로/가로) — 게임기 가로 화면 */
        keymap = KeyMap.load();                   /* 물리 패드 매핑 */
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setRenderer(new GLSurfaceView.Renderer() {
            @Override public void onSurfaceCreated(GL10 g, EGLConfig c) {
                Emu.nativeSurfaceCreated();
            }
            @Override public void onSurfaceChanged(GL10 g, int w, int hgt) {
                Emu.nativeResize(w, hgt);
            }
            @Override public void onDrawFrame(GL10 g) {
                Emu.nativeSetInput(padMask | keyMask | axisMask);
                Emu.nativeFrame();
                /* 프레임 크기가 바뀌면(기둥·띠 토글) 화면 상자를 다시 잡는다 */
                int fw = Emu.nativeFrameWidth(), fh = Emu.nativeFrameHeight();
                if (fw > 0 && (fw != lastFW || fh != lastFH)) {
                    lastFW = fw; lastFH = fh;
                    runOnUiThread(new Runnable() { @Override public void run() {
                        placeScreen();
                    }});
                }
            }
        });
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        /* 표면이 생길 때마다 주사율 요청을 다시 건다 — Surface.setFrameRate 는 표면에 붙는 값이다 */
        gl.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            @Override public void surfaceCreated(android.view.SurfaceHolder sh) { applyFrameRate(); }
            @Override public void surfaceChanged(android.view.SurfaceHolder sh, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(android.view.SurfaceHolder sh) { }
        });

        pad = new PadView(this);
        /* 패드는 네 벌 — SS2 전용, SvC(원버튼 6키), KOF R-2·월화(R=SP · L=A+B), 순정 NGPC(A·B 만).
           엔진 없는 게임에 기술·강약 버튼을 두면 안 나가는 버튼이 화면만 차지한다.
           배치 파일은 **게임마다** 따로다. */
        String profile = (game != null && game.has(Games.F_SP_SS2)) ? "ss2"   /* 게임 표의 features 가 단일 출처 */
                       : (game != null && game.has(Games.F_SP_SVC)) ? "svc"
                       : (game != null && game.has(Games.F_SP_KOF)) ? "kof"
                       /* 월화도 KOF 와 «같은 비트»다 — R(11)=SP · L(10)=A+B (이식소 실측).
                          여기가 비어 있어서 월화가 「A·B 뿐」인 순정 패드로 떨어졌고,
                          유저에게는 «A+B 가 없어진» 것으로 보였다. */
                       : (game != null && game.has(Games.F_SP_LB)) ? "kof" : "ngp";
        pad.setProfile(profile, (game != null) ? game.id : "ngp");
        /* 상단바의 게임별 칸 — 코어가 이 게임에서 실제로 쓰는 기능만. 게임 표가 단일 출처다. */
        pad.setCoreFeatures(game != null && game.has(Games.F_BAND));
        applyFrameGen(false);
        applyDisplay();
        pad.setListener(new PadView.Listener() {
            @Override public void onMask(int mask) { padMask = mask; }
            @Override public void onAction(int action) { handleAction(action); }
            @Override public void onTurbo(boolean on) { Emu.nativeSetTurbo(on); }
            @Override public void onScreenDrag(float dxFrac, float dyFrac) {
                freezeAutoFit();
                int w = root.getWidth(), hgt = root.getHeight();
                int gw = w * scrPct / 100, gh = hgt * scrPct / 100;
                if (w > gw)  scrX = clamp(scrX + Math.round(dxFrac * w * 100f / (w - gw)), 0, 100);
                if (hgt > gh) scrY = clamp(scrY + Math.round(dyFrac * hgt * 100f / (hgt - gh)), 0, 100);
                placeScreen();
            }
            @Override public void onScreenScale(int dPct) {
                freezeAutoFit();
                scrPct = clamp(scrPct + dPct, 20, 100);
                placeScreen(); persistScreen();
            }
            @Override public void onScreenDrop() { persistScreen(); }
            /* 메뉴가 열려 있는 동안 게임을 멈춘다 — 고르는 사이 맞지 않게(Delta 의 멈춤 메뉴). 옵션 창이 떠 있으면 그 창이 멈춤을 쥔다 */
            @Override public void onMenu(boolean open) {
                if (open) { releasePhysical(); refreshSlotInfo(); }
                if (!loaded) return;
                Emu.nativeSetPaused(open || (sheet != null && sheet.isShowing()) || (leagueDlg != null && leagueDlg.isShowing())
                        || (leagueId != null && leagueArmed && nativeDone())     /* 결판이 잡혀 정산을 기다리는 동안은 멈춘 채로 */
                        || hubCard != null);                                     /* 게임 속 리그 결과 카드가 떠 있는 동안도 */
            }
        });

        root = new FrameLayout(this);
        root.addView(gl);
        root.addView(pad);
        /* 회전(가로 모드) — onConfigurationChanged 시점엔 루트가 아직 옛 크기라, 실제 크기가 바뀐 뒤에
           화면 상자를 다시 잡아야 GL 뷰가 새 크기로 재생성된다(안 그러면 옛 뷰포트로 잘려 보였다). */
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int tp, int r, int btm, int ol, int ot, int orr, int ob) {
                if ((r - l) != (orr - ol) || (btm - tp) != (ob - ot)) placeScreen();
            }
        });
        setContentView(root);
        applyScreenLayout();
        if (leagueHub) addHubBanner();
        else if (leagueId != null) addLeagueTag();

        loadCore();
        if (!ranked()) watchRom();                /* 랭크전 중에 롬이 바뀌어 다시 로드되면 경기가 깨진다 */
        else if (leagueHub) hubStart();
        else leagueStart();
    }

    /** 게임 그림의 크기와 자리를 설정대로 잡는다.
     *
     *  비율(%)로 정하는 값이라 화면이 실제 몇 픽셀인지 알아야 계산이 된다. 그 크기는
     *  레이아웃이 한 번 끝나야 정해지므로, 아직 모르면 다 그려진 뒤에 한 번 듣고 넣는다.
     *  설정에서 돌아왔을 때도 다시 부르므로 **100% 로 되돌리는 것도 반영**돼야 한다 —
     *  그래서 기본값일 때도 그냥 넘기지 않고 꽉 찬 크기를 명시해 준다.
     *
     *  줄어든 크기는 GLSurfaceView 가 onSurfaceChanged 로 코어에 그대로 넘기므로
     *  (nativeResize) 코어가 알아서 비율을 맞춘다. 여기서는 자리만 정한다. */
    private void applyScreenLayout() {
        if (root == null || gl == null) return;
        int w = root.getWidth(), hgt = root.getHeight();
        if (w <= 0 || hgt <= 0) {
            root.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    if (root.getWidth() <= 0) return;
                    root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    applyScreenLayout();
                }
            });
            return;
        }
        java.util.Map<String, String> m = Settings.load();
        scrPct = clamp(intOf(m.get("pocketcore_screen_size"), 100), 20, 100);
        /* 크기·자리를 한 번도 안 정했으면(옵션 없음) 세로 화면에서 «패드 위에 맞춤» — 폴드 큰 화면처럼 정사각에 가까우면
           가로 꽉 채운 게임이 높이의 80% 를 먹어 패드가 그림을 반쯤 덮었다(유저 2026-10-10 「인터페이스 좀 손봐 줘」).
           배치에서 끌거나 크기를 바꾸면 그 값이 저장돼 이 자동 맞춤은 꺼진다. */
        autoFit = m.get("pocketcore_screen_size") == null && m.get("pocketcore_screen_x") == null
               && m.get("pocketcore_screen_y") == null && m.get("pocketcore_screen_v") == null;
        /* 자리: x·y 퍼센트(남는 공간 대비 0~100). 없으면 옛 top/center 계열에서 변환.
           「키」 편집에서 화면 상자를 끌면 이 값이 갱신·저장된다. */
        String v = or(m.get("pocketcore_screen_v"), "center");
        String h = or(m.get("pocketcore_screen_h"), "center");
        scrX = intOf(m.get("pocketcore_screen_x"),
                "left".equals(h) ? 0 : "right".equals(h) ? 100 : 50);
        scrY = intOf(m.get("pocketcore_screen_y"),
                "top".equals(v) ? 0 : "bottom".equals(v) ? 100 : 50);
        placeScreen();
        applyPadMode(m);
    }

    /** 터치 패드 표시 — auto: 실제 게임패드가 붙어 있으면 숨기고 메뉴 알약만 남긴다(게임기 용). */
    private void applyPadMode(java.util.Map<String, String> m) {
        String v = or(m.get("pocketcore_touchpad"), "auto");
        boolean hide = "off".equals(v) || ("auto".equals(v) && KeyMap.physicalPresent());
        if (pad != null) pad.setPhysicalMode(hide);
        Theme.init(this); Theme.refresh();      /* 121: 버튼 모양이 테마를 따른다 — 설정에서 바꾸고 돌아오면 새로 굽는다 */
        if (pad != null) pad.setArt(!"flat".equals(or(m.get("pocketcore_padskin"), "art")));
        if (pad != null) pad.setPadAlpha(intOf(m.get("pocketcore_pad_alpha"), 100));     /* 122: 버튼 투명도 */
    }
    private final android.hardware.input.InputManager.InputDeviceListener devListener =
            new android.hardware.input.InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id)   { applyScreenLayout(); }
        @Override public void onInputDeviceRemoved(int id) { axisByDev.delete(id); releasePhysical(); applyScreenLayout(); }
        @Override public void onInputDeviceChanged(int id) { }
    };
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        immersive(); applyScreenLayout();     /* 회전 — 액티비티 재생성 없이 자리만 다시 잡는다 */
    }

    private int scrPct = 100, scrX = 50, scrY = 50;
    private boolean autoFit = false;       /* 크기·자리 미설정 — 세로에서 게임을 위쪽, 패드 자리(아래 44%) 위에 맞춘다 */
    /** 자동 맞춤 상자를 같은 자리의 크기·자리 값으로 바꿔 넣는다 — 배치에서 끌기 시작할 때 튀지 않게. */
    private void freezeAutoFit() {
        if (!autoFit || gl == null || root == null) return;
        autoFit = false;
        int w = root.getWidth(), hgt = root.getHeight();
        android.view.ViewGroup.LayoutParams lp0 = gl.getLayoutParams();
        if (w <= 0 || hgt <= 0 || !(lp0 instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lp0;
        scrPct = clamp(Math.round(lp.width * 100f / w), 20, 100);
        scrX = 50;
        scrY = hgt > lp.height ? clamp(Math.round(lp.topMargin * 100f / (hgt - lp.height)), 0, 100) : 50;
    }
    private KeyMap keymap;                 /* 물리 패드 매핑 표 */
    private volatile int axisMask;         /* 스틱·HAT 십자 → 방향 비트 (장치별 합, 리뷰 F3) */
    private final android.util.SparseIntArray axisByDev = new android.util.SparseIntArray();
    private int axisPrev;                  /* 스틱 엣지 검출(바 조작용) */
    /** 물리 입력 상태 전부 놓기 — 포커스 상실·패드 제거·일시정지 때(리뷰 F1/F9/F16). */
    private void releasePhysical() { keyMask = 0; axisByDev.clear(); axisMask = 0; Emu.nativeSetTurbo(false); }
    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) immersive(); else releasePhysical();
    }
    private int lastFW = 0, lastFH = 0;   /* 프레임 크기 변화 감지 (기둥·띠 토글) */

    /** 현재 scrPct/scrX/scrY 로 게임 화면을 놓고, 편집 상자에도 알려 준다.
     *  상자는 **게임 프레임의 실제 종횡비**로 잡는다 — 기둥 아트(폭 288)를 켜면
     *  프레임이 넓어지는데, 예전엔 상자가 160 기준 그대로라 화면이 쪼그라들었다(제보). */
    private void placeScreen() {
        if (root == null || gl == null) return;
        int w = root.getWidth(), hgt = root.getHeight();
        if (w <= 0 || hgt <= 0) return;
        int fw = Emu.nativeFrameWidth(), fh = Emu.nativeFrameHeight();
        if (fw <= 0 || fh <= 0) { fw = 160; fh = 152; }
        /* 기둥(288폭) 프레임: 화면 상자 크기는 **게임 160폭** 기준으로 잡고, 기둥은 남는 옆자리에만
           보이게 상자 폭을 화면 전체로 편다(넘치는 기둥은 잘린다). 288 전체를 폭에 맞추면 게임이
           작아지고 아래가 비는 제보(유저 2026-09-03). native.c gl_draw 도 같은 규칙. */
        int gameW = (fw > 160 && fw <= 320) ? 160 : fw;
        int gw = w * scrPct / 100;
        int gh = gw * fh / gameW;
        /* 가로에선 메뉴 알약 띠(짧은 변의 4.6%)를 게임 상자 위에 예약 — 알약이 HUD 를 덮지 않게(리뷰 F14) */
        float dpx = getResources().getDisplayMetrics().density;
        int pillGap = Math.round(PadView.handleH(w, hgt, dpx) + 4 * dpx);      /* 메뉴 알약 아래부터 — 알약이 커져도 HUD 를 안 덮게 */
        int top = (w > hgt) ? Math.max(Math.round(Math.min(w, hgt) * 0.05f), pillGap) : 0;
        int capH = (hgt - top) * scrPct / 100;
        if (gh > capH) { gh = capH; gw = gh * gameW / fh; }
        if (gameW != fw) gw = w;
        android.util.Log.i("PocketCore", "placeScreen root " + w + "x" + hgt + " frame " + fw + "x" + fh + " box " + gw + "x" + gh);
        int mx = (w - gw) * clamp(scrX, 0, 100) / 100;
        int my = top + (hgt - top - gh) * clamp(scrY, 0, 100) / 100;
        if (autoFit && hgt > w && gameW == fw) {
            /* 세로·자동: 메뉴 알약 아래(짧은 변 5%)부터 높이 56% 까지 — 그 아래는 패드 자리 */
            int top2 = Math.max(Math.round(Math.min(w, hgt) * 0.05f), pillGap), maxH = Math.round(hgt * 0.56f) - top2;
            gw = w; gh = w * fh / gameW;
            if (gh > maxH) { gh = maxH; gw = gh * gameW / fh; }
            mx = (w - gw) / 2; my = top2;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(gw, gh,
                android.view.Gravity.TOP | android.view.Gravity.LEFT);
        lp.leftMargin = mx; lp.topMargin = my;
        gl.setLayoutParams(lp);
        if (pad != null) pad.setScreenBox(mx, my, mx + gw, my + gh);
        gameBox[0] = mx; gameBox[1] = my; gameBox[2] = mx + gw; gameBox[3] = my + gh;
        placeHubBanner();
    }

    private void persistScreen() {
        Settings.put("pocketcore_screen_size", String.valueOf(scrPct));
        Settings.put("pocketcore_screen_x", String.valueOf(scrX));
        Settings.put("pocketcore_screen_y", String.valueOf(scrY));
    }

    private static int intOf(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static String or(String s, String def) { return (s == null || s.isEmpty()) ? def : s; }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private String coreLabel = "";

    /** 코어 선택 — 밖에 둔 코어는 **게임별 이름 규칙일 때만** 받는다.
     *  cores/ss2*.so → SS2 롬에만, cores/svc*.so → 그 밖의 롬에. 나머지 .so 는 무시한다.
     *  (아무 파일이나 두면 모든 게임이 그 코어로 돌아 「롤백된 느낌」 사고가 났다.) */
    private String corePath() {
        String want = "ss2".equals(romType) ? "ss2" : "svc";
        String suffix = "";
        for (int i = 0; i < Games.LANGS.length; i++)
            if (Games.LANGS[i].equals(lang)) suffix += " · " + Games.LANGS_KO[i];
        if (patched) suffix += " 패치";
        if (ranked() && game != null) {          /* 랭크전은 롬처럼 코어도 고정 — 판정 주소를 실측한 앱 동봉 코어만 */
            coreLabel = game.ko + " · 동봉 코어(랭크전)" + suffix;
            return getApplicationInfo().nativeLibraryDir + "/" + game.core;
        }
        File[] f = new File(MainActivity.root(), "cores").listFiles();
        if (f != null) for (File x : f) {
            String n = x.getName().toLowerCase();
            if (n.endsWith(".so") && n.startsWith(want)) {
                coreLabel = "외부 코어: " + x.getName();
                return x.getAbsolutePath();
            }
        }
        /* 「업데이트 확인」이 받아 둔 코어 — 앱 **내부** 저장소. sdcard 는 실행권이
           없어(noexec) dlopen 이 안 되는 기기가 많아 내부에 둔다. 동봉 코어보다 새것. */
        File dir = new File(getFilesDir(), "cores");
        File auto = new File(dir, want + ".so");
        if (auto.exists()) {
            String v = null;
            try (java.io.FileInputStream in =
                         new java.io.FileInputStream(new File(dir, want + ".ver"))) {
                byte[] b = new byte[64];
                int n2 = in.read(b);
                if (n2 > 0) v = new String(b, 0, n2, "UTF-8").trim();
            } catch (Exception ignored) { }
            coreLabel = ((game != null) ? game.ko : "순정 NGPC")
                      + ((v != null) ? " · 코어 " + v : "") + suffix;
            return auto.getAbsolutePath();
        }
        String lib = (game != null) ? game.core : Games.fallbackCore();
        coreLabel = ((game != null) ? game.ko : "순정 NGPC") + suffix;
        return getApplicationInfo().nativeLibraryDir + "/" + lib;
    }


    private void loadCore() {
        Emu.nativeSetPaused(false);   /* 멈춤 표시는 프로세스 전역이다 — 창이 떠 있던 채로 넘어왔어도 새 판은 돈다 */
        /* 코어가 로드 중에 주사율을 물을 수 있다 — 실측 전이라 시스템 값을 먼저 넣어 둔다 */
        try { Emu.nativeSetPanelHz(getWindowManager().getDefaultDisplay().getRefreshRate()); } catch (Exception ignored) { }
        corePathCache = corePath();
        int rc = Emu.nativeLoad(corePathCache, romPath,
                MainActivity.sysDir().getAbsolutePath(),
                (ranked() ? League.saveDir() : MainActivity.saveDir()).getAbsolutePath(),
                MainActivity.optsFile().getAbsolutePath());
        loaded = rc == 0;
        snapCoreOpts();
        romMtime = new File(romPath).lastModified();
        toast(loaded ? coreLabel : "코어/롬 로드 실패 (code " + rc + ")");
        /* 이어하기 — 옵션 바꾸고 다시 연 경우는 그 직전 상태(resume), 아니면 나갈 때 자동 저장해 둔 자리. 로드와 같은 스레드라 안전하다. */
        String resume = getIntent().getStringExtra("resume");
        boolean resumed = false;
        if (ranked()) resume = null;       /* 랭크전: 이어하기는 leagueStart 가 경기 체크포인트로만 */
        if (leagueId != null) { /* 오토세이브 이어하기도 안 한다(autoSave=false) */ }
        else if (loaded && resume != null && new File(resume).exists()) {
            if (Emu.nativeLoadState(resume) == 0) { toast("옵션 적용 — 그 자리에서 이어하기"); resumed = true; }
            new File(resume).delete();
        } else if (loaded && autoSave && !practiceLinkFresh() && autoStatePath().exists()
                && Emu.nativeLoadState(autoStatePath().getAbsolutePath()) == 0) {
            toast("이어하기"); resumed = true;
        }
        practiceSetup(resumed);
        /* 사무쇼2 배경음악(SS1) 결과를 알려 준다 — 「넣었는데 안 바뀐다」를 눈으로 가릴 수 있게(유저 2026-10-10).
           이어하기면 지금 울리는 곡은 저장 당시 것이라(소리칩 램에 이미 올라가 있다) 다음 장면부터 바뀐다. */
        String mn = Patcher.musicNote;
        if (loaded && mn != null)
            toast((Patcher.MUSIC_OK.equals(mn) || Patcher.MUSIC_MUTE.equals(mn)) && resumed ? mn + " (이어하기라 다음 장면부터)" : mn);
    }

    /** ROM hacking convenience: rebuild the ROM and the emulator picks it up by itself. */
    private void watchRom() {
        h.postDelayed(new Runnable() {
            @Override public void run() {
                long m = new File(romPath).lastModified();
                if (loaded && m != 0 && m != romMtime) {
                    romMtime = m;
                    gl.queueEvent(new Runnable() {
                        @Override public void run() {
                            Emu.nativeUnload();
                            loadCore();
                        }
                    });
                    toast("롬 변경 감지 — 다시 로드");
                }
                h.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private File statePath() {
        String suffix = slot == 1 ? ".state" : ".state" + slot;
        return new File(MainActivity.saveDir(), new File(romPath).getName() + suffix);
    }

    /** 오토세이브 자리 — 수동 슬롯(1~3)과 절대 안 겹치는 별도 파일. */
    private File autoStatePath() {
        return new File(MainActivity.saveDir(), new File(romPath).getName() + ".state.auto");
    }

    /** 되돌리기 자리 — 불러오기·리셋 직전 상태. 슬롯·오토세이브와 따로. */
    private File undoPath() {
        return new File(MainActivity.saveDir(), new File(romPath).getName() + ".state.undo");
    }

    /** 저장 칸·로드 칸 아래 줄 — 지금 슬롯에 뭐가 있는지(«3분 전» / «없음»). 메뉴를 열 때·저장·슬롯 바꿀 때 */
    private void refreshSlotInfo() {
        if (pad == null || romPath == null) return;
        File f = statePath();
        boolean has = f.exists() && f.length() > 0;
        pad.setSlotInfo(has ? "덮어쓰기" : "빈 칸", has ? ago(f.lastModified()) : "없음");
    }
    private static String ago(long t) {
        long sec = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (sec < 60) return "방금";
        if (sec < 3600) return (sec / 60) + "분 전";
        if (sec < 86400) return (sec / 3600) + "시간 전";
        return (sec / 86400) + "일 전";
    }

    /** 지금 자리를 되돌리기 자리에 떠 두고 일(불러오기·리셋)을 한 뒤, 5초 동안 «되돌리기» 칩을 띄운다.
     *  메뉴는 닫는다(게임이 그 자리에서 바로 이어진다 — 멈춘 채 불러오면 화면이 안 바뀌어 됐는지 모른다). */
    private void withUndo(final String done, final Runnable work) {
        pad.barClose();
        gl.queueEvent(new Runnable() { @Override public void run() {
            final boolean backed = Emu.nativeSaveState(undoPath().getAbsolutePath()) == 0;
            work.run();
            toast(done);
            if (backed) h.post(new Runnable() { @Override public void run() { pad.offerUndo(); } });
        }});
    }

    private void handleAction(int action) {
        if (ranked() && (action == PadView.ACT_LOAD || action == PadView.ACT_UNDO || action == PadView.ACT_RESET
                || action == PadView.ACT_CFG || action == PadView.ACT_SAVE || action == PadView.ACT_QSAVE)) {
            toast("랭크전 중에는 쓸 수 없어요 — 그만두려면 뒤로 가기");   /* 저장도 막는다: 슬롯 파일 이름이 자유 플레이와 같다 */
            return;
        }
        if (ranked() && (action == PadView.ACT_PICK || action == PadView.ACT_QUIT)) {
            /* 메뉴 바가 닫히며 멈춤을 푸는(menuChanged) 것보다 뒤에 창을 띄운다 */
            h.post(new Runnable() { @Override public void run() { if (leagueId != null) leagueLeave(); else hubLeave(); } });
            return;
        }
        switch (action) {
        case PadView.ACT_SAVE:
        case PadView.ACT_QSAVE: {
            final boolean quick = action == PadView.ACT_QSAVE;
            gl.queueEvent(new Runnable() { @Override public void run() {
                final int rc = Emu.nativeSaveState(statePath().getAbsolutePath());
                toast(rc != 0 ? "저장 실패" : (quick ? "빠른 저장 · 슬롯 " : "저장 · 슬롯 ") + slot);
                h.post(new Runnable() { @Override public void run() { refreshSlotInfo(); } });
            }});
            break; }
        case PadView.ACT_LOAD: {
            final File st = statePath();
            if (!st.exists()) { toast("슬롯 " + slot + "은 비어 있어요"); break; }
            withUndo("불러옴 · 슬롯 " + slot, new Runnable() { @Override public void run() {
                if (Emu.nativeLoadState(st.getAbsolutePath()) != 0) toast("불러오기 실패");
            }});
            break; }
        case PadView.ACT_UNDO:
            gl.queueEvent(new Runnable() { @Override public void run() {
                toast(Emu.nativeLoadState(undoPath().getAbsolutePath()) == 0 ? "되돌렸어요" : "되돌리기 실패");
            }});
            break;
        case PadView.ACT_LAYOUT_DEFAULT:
            /* 배치 「처음대로」 — 버튼은 PadView 가 되돌렸다. 게임 화면 크기·자리 값을 지워 자동 맞춤으로 */
            Settings.removePrefix("pocketcore_screen_");
            applyScreenLayout();
            toast("버튼과 화면을 처음 자리로");
            break;
        case PadView.ACT_SHOT:
            gl.queueEvent(new Runnable() { @Override public void run() { screenshot(); }});
            break;
        case PadView.ACT_RESET:
            withUndo("리셋했어요", new Runnable() { @Override public void run() { Emu.nativeReset(); } });
            break;
        case PadView.ACT_SLOT:
            slot = slot % 3 + 1;
            pad.setSlotLabel(slot);
            refreshSlotInfo();
            break;
        case PadView.ACT_BAND:
            toggleCoreOpt("ngp_svcsp_band", "기술명 띠");
            break;
        case PadView.ACT_FRAMEGEN: {
            /* 앱 방식: 끔 → 움직임 → 섞기 → 끔 */
            String cur = readOpt("pocketcore_framegen", "off");
            persistOption("pocketcore_framegen", "off".equals(cur) ? "motion" : "motion".equals(cur) ? "blend" : "off");
            applyFrameGen(true);
            break; }
        case PadView.ACT_COREFG: {
            /* 코어 방식(사무쇼2): 자동 ↔ 끔. 게임 중에 바로 — nativeSetOption 이 코어에 «옵션 바뀜»을 알린다 */
            String v = coreFgOn() ? "disabled" : "auto";
            persistOption("ngp_framegen", v);
            if (loaded) Emu.nativeSetOption("ngp_framegen", v);
            synchronized (coreOptSnap) { coreOptSnap.put("ngp_framegen", v); }
            applyFrameGen(true);
            break; }
        case PadView.ACT_CFG: {
            /* 게임 중 옵션 창 — 실행 전 선택 창과 같은 것. 「적용하고 이어하기」= 지금 자리 저장 → 옵션대로 다시 굽기 → 다시 열어 그 자리부터
               (유저 2026-09-05 「게임 중간에도 이 창 되게 — 오토세이브하고 리로드」). 전체 설정은 창 아래 링크로. */
            final String orig = getIntent().getStringExtra("rom");
            /* 창이 떠 있는 동안 게임을 멈춘다 — 전엔 뒤에서 게임이 계속 돌고, 쥐고 있던 버튼이 눌린 채 남았다 */
            padMask = 0; releasePhysical();
            Emu.nativeSetPaused(true);
            sheet = LaunchSheet.showInGame(this, game, game != null ? game.ko : new File(orig).getName(),
                    new Runnable() { @Override public void run() { applyLive(orig); } },
                    new Runnable() { @Override public void run() {
                        startActivity(new Intent(EmuActivity.this, SettingsActivity.class).putExtra("rom", orig)); } });
            sheet.onClose = new Runnable() { @Override public void run() {
                Emu.nativeSetPaused(pad.isBarOpen());          /* 메뉴가 아직 열려 있으면 계속 멈춤 */
                applyDisplay(); pushCoreOpts(); } };
            break; }
        case PadView.ACT_PICK:
            goList();
            break;
        case PadView.ACT_QUIT:
            /* 앱 종료 — 오토세이브는 onPause 가, 코어 내리기는 onDestroy 가 챙긴다. 다음에 켜면 목록부터. */
            MainActivity.forgetLast(this);
            finishAffinity();
            break;
        }
    }

    /* ── 프레임 생성 ─────────────────────────────────────────────
       코어는 60.25fps 그대로 돌리고, 120Hz 화면의 «빈 vsync» 에 중간 그림을 끼운다(native.c · framegen.c).
       그러려면 화면이 실제로 120Hz 여야 한다 — 삼성은 정적 화면을 60Hz 로 내리므로
       켜져 있을 때는 창·표면에 최고 주사율을 요청한다. 끄면 요청도 거둔다(배터리). */
    /* ── 프레임 생성 — 시험판이라 «두 방식을 따로» 켜고 끈다 ──
       앱 방식(pocketcore_framegen: 끔/움직임/섞기) — 모든 게임. 완성된 화면에서 움직임을 찾아 중간 그림.
       코어 방식(ngp_framegen: 자동/켬/끔) — 사무쇼2 코어만. 스프라이트·스크롤 위치를 보간해 다시 그림.
       둘 다 켜면: 코어가 120.5fps 로 내보내는 동안은 120Hz 화면에 빈 vsync 가 없어 앱 방식은 저절로 비켜선다.
       코어가 60 으로 돌아가면(패널 60·차단) 앱 방식이 대신 끼운다 — 결과적으로 «코어 우선, 앱은 대타». */
    private int fgMode = 0;   /* 앱 보간 모드(native) 0 끔 · 1 섞기 · 2 움직임 */

    private static int fgModeOf(String v) { return "motion".equals(v) ? 2 : "blend".equals(v) ? 1 : 0; }
    /** 이 게임은 코어 프레임 생성이 있는가 — ss2 코어(ss2-sp-core framegen). */
    private boolean coreFg() { return "ss2".equals(romType); }
    private boolean coreFgOn() { return coreFg() && !"disabled".equals(readOpt("ngp_framegen", "auto")); }

    private void applyFrameGen(boolean announce) {
        fgMode = fgModeOf(readOpt("pocketcore_framegen", "off"));
        Emu.nativeSetFrameGen(fgMode);
        if (pad != null) {
            pad.setFrameGenLabel(fgMode == 2 ? "앱:움직임" : fgMode == 1 ? "앱:섞기" : "앱:끔");
            pad.setCoreFg(coreFg(), coreFgOn() ? "코어:켬" : "코어:끔");
        }
        applyFrameRate();
        if (!announce) return;
        /* 화면이 실제 몇 Hz 인지·코어가 켰는지는 조금 지나야 안다(주사율 전환·코어 판정) */
        h.postDelayed(new Runnable() { @Override public void run() { toast(fgStatus()); }}, 1800);
    }

    /** 지금 실제로 무슨 일이 일어나는지 한 줄 — 「켰는데 되는 건가」를 이 토스트로 판정한다. */
    private String fgStatus() {
        int hz = Math.round(Emu.nativePanelHz());
        boolean coreOut = Emu.nativeCoreFps() > 90;          /* 코어가 120.5 를 선언했다 */
        StringBuilder sb = new StringBuilder("화면 " + hz + "Hz");
        if (coreFg()) {
            boolean four = "4".equals(readOpt("ngp_framegen_mult", "4"));
            boolean interp = "interp".equals(readOpt("ngp_framegen_mode", "predict"));
            sb.append(" · 코어: ").append(!coreFgOn() ? "끔" : coreOut
                    ? "120Hz 출력 중(" + (four ? "4배" : "2배") + "·" + (interp ? "보간" : "예측") + ")" : "60Hz 사이 그림(예측)");
        }
        sb.append(" · 앱: ");
        if (fgMode == 0) sb.append("끔");
        else if (coreOut) sb.append("비켜섬(코어가 이미 120)");
        else if (Emu.nativeFrameGenActive() > 0) sb.append(fgMode == 2 ? "움직임 보간 중" : "섞기 보간 중");
        else sb.append("대기(화면이 게임보다 빠르지 않음)");
        return sb.toString();
    }

    /** 120Hz 를 요청할 이유 — 프레임 생성이 켜졌을 때(앱이든 코어든). 삼성은 요청이 없으면 60 으로 내린다. */
    /* 「60Hz」(코어 사이 그림)은 120Hz 를 요청하지 않는다 — 60Hz 화면용·배터리 */
    private boolean wantHighRefresh() { return fgMode != 0 || (coreFgOn() && !"60".equals(readOpt("ngp_framegen", "auto"))); }

    /** 창에는 최고 주사율 모드를, 표면에는 120fps 를 요청한다(끄면 기본으로). */
    private void applyFrameRate() {
        try {
            android.view.Display d = getWindowManager().getDefaultDisplay();
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            int want = 0;
            float best = 0f;
            if (wantHighRefresh()) {
                android.view.Display.Mode cur = d.getMode();
                for (android.view.Display.Mode m : d.getSupportedModes())
                    if (m.getPhysicalWidth() == cur.getPhysicalWidth()
                            && m.getPhysicalHeight() == cur.getPhysicalHeight()
                            && m.getRefreshRate() > best) { best = m.getRefreshRate(); want = m.getModeId(); }
            }
            if (lp.preferredDisplayModeId != want) {
                lp.preferredDisplayModeId = want;          /* 0 = 시스템에 맡김 */
                getWindow().setAttributes(lp);
            }
            if (android.os.Build.VERSION.SDK_INT >= 30 && gl != null) {
                android.view.Surface s = gl.getHolder().getSurface();
                if (s != null && s.isValid())
                    s.setFrameRate(wantHighRefresh() ? Math.max(best, 120f) : 0f,
                            android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            }
        } catch (Exception e) {
            android.util.Log.w("PocketCore", "frame rate request: " + e);
        }
    }

    /* ── 화면 표시(업스케일러·필터) ──────────────────────────────
       업스케일러 = 도트를 키우는 방식 하나, 필터 = 그 위에 덧입히는 효과 여럿(각자 세기). 셰이더는 native.c.
       값은 GL 스레드로 넘긴다 — 그리는 쪽이 읽는 값이라 그리는 도중에 바뀌지 않게. */
    private void applyDisplay() {
        if (gl == null) return;
        java.util.Map<String, String> m = Settings.load();
        final int up = Settings.upscalerIndex(m.get("pocketcore_upscaler"));
        final int mix = 100;                     /* 114: 「업스케일러 세기」 뺌 — 늘 100%(유저 「블렌더 레벨 필요 없어」) */
        final int grid = Settings.pctOf(m, "pocketcore_flt_grid", 0);
        final int scan = Settings.pctOf(m, "pocketcore_flt_scan", 0);
        final int ghost = Settings.pctOf(m, "pocketcore_flt_ghost", 0);
        final int color = Settings.pctOf(m, "pocketcore_flt_color", 0);
        final int soft = Settings.pctOf(m, "pocketcore_flt_soft", 0);
        final boolean integer = !"disabled".equals(m.get("pocketcore_integer"));
        gl.queueEvent(new Runnable() { @Override public void run() {
            Emu.nativeSetDisplay(up, mix, grid, scan, ghost, color, soft, integer);
        }});
    }

    /* ── 게임 중에 바로 바꿀 수 있는 코어 옵션 ──
       코어는 로드할 때 options.txt 를 읽는다. 설정 화면에서 이 값들을 바꾸고 돌아오면 «로드 때와 다른 것만»
       코어에 다시 넣는다(nativeSetOption → 코어가 다음 프레임에 다시 읽음). 롬을 다시 굽는 언어·조작 패치는 여기 없다. */
    private static final String[] LIVE_CORE_OPTS = {
        "ngp_framegen", "ngp_framegen_mode", "ngp_framegen_mult", "ngp_framegen_fx", "ngp_framegen_pose", "ngp_framegen_idle", "ngp_framegen_motion", "ngp_runahead",
        "ngp_svcsp_band", "ngp_flash_reduce" };
    private final java.util.Map<String, String> coreOptSnap = new java.util.HashMap<>();
    private void snapCoreOpts() {
        java.util.Map<String, String> m = Settings.load();
        synchronized (coreOptSnap) {
            coreOptSnap.clear();
            for (String k : LIVE_CORE_OPTS) coreOptSnap.put(k, m.get(k));
        }
    }
    private void pushCoreOpts() {
        if (!loaded || gl == null) return;
        java.util.Map<String, String> m = Settings.load();
        synchronized (coreOptSnap) {
            for (final String k : LIVE_CORE_OPTS) {
                final String v = m.get(k);
                if (v == null || v.equals(coreOptSnap.get(k))) continue;
                coreOptSnap.put(k, v);
                gl.queueEvent(new Runnable() { @Override public void run() { if (loaded) Emu.nativeSetOption(k, v); }});
            }
        }
    }

    /** 코어 옵션을 게임 중에 즉시 뒤집는다. 화면 크기가 바뀌는 것(띠·기둥)은
     *  onDrawFrame 이 프레임 크기 변화를 보고 화면 상자를 다시 잡는다. */
    private void toggleCoreOpt(String key, String ko) {
        boolean on = !"disabled".equals(readOpt(key, "enabled"));
        String v = on ? "disabled" : "enabled";
        Emu.nativeSetOption(key, v);
        persistOption(key, v);
        synchronized (coreOptSnap) { if (coreOptSnap.containsKey(key)) coreOptSnap.put(key, v); }
        toast(ko + (on ? " 끔" : " 켬"));
    }

    /** 옵션을 바꾼 채 이어하기 — 상태를 임시 파일에 저장하고 같은 롬으로 다시 연다.
     *  새 EmuActivity 가 옵션대로 다시 굽고(Patcher.resolve) "resume" 상태를 되읽는다. 세이브 상태에 롬 바이트는 없어 패치가 달라도 이어진다. */
    private void applyLive(final String orig) {
        /* 저장·내리기는 «GL 스레드에서» — 예전엔 UI 스레드에서 해서, 코어가 프레임을 도는 도중에
           상태를 뜨거나 코어를 내려 깨진 상태·튕김이 날 수 있었다(게임 안 「설정」이 불안정하던 원인). */
        final File st = new File(getCacheDir(), "live.state");
        gl.queueEvent(new Runnable() { @Override public void run() {
            final boolean had = loaded;
            if (had && Emu.nativeSaveState(st.getAbsolutePath()) != 0) {
                toast("상태 저장 실패 — 그대로 둡니다");
                Emu.nativeSetPaused(false);
                return;
            }
            if (had) Emu.nativeSaveSram();
            Emu.nativeUnload(); loaded = false;
            Emu.nativeSetPaused(false);
            runOnUiThread(new Runnable() { @Override public void run() {
                Intent i = new Intent(EmuActivity.this, EmuActivity.class).putExtra("rom", orig);
                if (had) i.putExtra("resume", st.getAbsolutePath());
                MainActivity.forgetLast(EmuActivity.this);
                startActivity(i);
                finish();
            }});
        }});
    }

    /** 목록으로 — 「목록」키·뒤로가기 공용. 오토세이브는 onPause 가 챙긴다. */
    private void goList() {
        MainActivity.forgetLast(this);
        /* 목록(런처)은 썸네일을 찍느라 같은 코어 자리(Emu)를 쓰므로, 목록을 띄우기 «전에» 내려야 한다.
           다만 내리기·오토세이브는 GL 스레드에서 — UI 스레드에서 바로 내리면 프레임 도중에 코어가 사라진다.
           (예전엔 내린 뒤 onPause 가 오토세이브를 시도해 조용히 실패했다 — 목록으로 나가면 이어하기가 옛 자리였다) */
        if (leaving) return;
        leaving = true;
        gl.queueEvent(new Runnable() { @Override public void run() {
            if (loaded) {
                Emu.nativeSaveSram();
                if (autoSave) Emu.nativeSaveState(autoStatePath().getAbsolutePath());
                Emu.nativeUnload(); loaded = false;
            }
            runOnUiThread(new Runnable() { @Override public void run() {
                /* menu 를 달아야 목록이 뜬다 — 롬이 하나뿐이면 바로 그 롬으로 되돌아가서
                   설정에 닿을 길이 없어진다 */
                Intent i2 = new Intent(EmuActivity.this, Home.class);   /* 121: 런처는 Home(MainActivity 는 아이콘 별칭) */
                i2.putExtra("menu", true);
                startActivity(i2);
                finish();
            }});
        }});
    }
    private boolean leaving = false;   /* 목록으로 나가는 중 — 뒤로가기 연타로 두 번 내리지 않게 */

    /** 뒤로가기 = 목록으로 — 예전엔 앱이 그냥 닫혀서 「게임을 닫으면 테마가 다시
     *  나와야 한다」는 흐름 자체가 없었다(제보). */
    @Override public void onBackPressed() {
        if (hubCard != null) { hubReturn(-1); return; }      /* 결과 카드에서 뒤로 = 계속 */
        if (leagueId != null) { leagueLeave(); return; }
        if (leagueHub) { hubLeave(); return; }
        /* 135(접근성 검수 — 유저는 덮개 화면을 엄지로만 쓴다): 뒤로 = 멈춤 메뉴(덮개 화면에선 칸이 아래 엄지 자리), 한 번 더 = 목록.
           「메뉴」 알약은 화면 맨 위라 엄지가 안 닿았고, 십자에서 엄지가 가장자리로 밀리면 한 번에 목록으로 튕겼다.
           목록으로 나갈 때 오토세이브는 그대로라 «한 번 더»의 길은 예전과 같다. 배치 편집 중이면 편집만 끝낸다. */
        if (pad != null && pad.endEdit()) return;
        if (pad != null && loaded && !pad.isBarOpen()) {
            pad.openBar();
            if (!backHintShown) { backHintShown = true; toast("멈춤 — 한 번 더 뒤로 가면 목록으로"); }
            return;
        }
        goList();
    }
    private boolean backHintShown = false;

    /* options.txt 에 키를 갈아 끼워 재시작 후에도 유지 */
    private void persistOption(String key, String val) {
        try {
            java.io.File f = MainActivity.optsFile();
            StringBuilder sb = new StringBuilder();
            boolean hit = false;
            if (f.exists()) {
                java.util.Scanner sc = new java.util.Scanner(f, "UTF-8");
                while (sc.hasNextLine()) {
                    String ln = sc.nextLine();
                    if (ln.startsWith(key + "=")) { sb.append(key).append('=').append(val).append('\n'); hit = true; }
                    else sb.append(ln).append('\n');
                }
                sc.close();
            }
            if (!hit) sb.append(key).append('=').append(val).append('\n');
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.close();
        } catch (Exception ignored) { }
    }

    /** options.txt 의 pocketcore_lang. 없으면 한국어. */
    /** 옵션 파일에서 key 하나를 읽는다. 없으면 def. */
    private String readOpt(String key, String def) {
        try {
            java.util.Scanner sc = new java.util.Scanner(MainActivity.optsFile(), "UTF-8");
            while (sc.hasNextLine()) {
                String ln = sc.nextLine().trim();
                if (!ln.startsWith(key + "=")) continue;
                sc.close();
                return ln.substring(ln.indexOf('=') + 1).trim();
            }
            sc.close();
        } catch (Exception ignored) { }
        return def;
    }

    private String readLang() {
        /* 실행 전 선택창이 게임별로 고른 한글패치(pocketcore_lang_<id>)가 있으면 그것, 없으면 전역 pocketcore_lang. */
        java.util.Map<String, String> m = Settings.load();
        String v = (game != null) ? m.get("pocketcore_lang_" + game.id) : null;
        if (v == null) v = m.get("pocketcore_lang");
        if (v != null) for (String k : Games.LANGS) if (k.equals(v)) return v;
        return "ko";
    }


    private void toast(final String s) {
        h.post(new Runnable() { @Override public void run() {
            Toast.makeText(EmuActivity.this, s, Toast.LENGTH_SHORT).show(); }});
    }

    /** Saves the raw emulated frame (no scaling, no overlay) as PNG next to the ROM. */
    private void screenshot() {
        int w = Emu.nativeFrameWidth(), hgt = Emu.nativeFrameHeight();
        ByteBuffer buf = Emu.nativeFrameBuffer();
        if (buf == null || w <= 0 || hgt <= 0) { toast("프레임 없음"); return; }
        Bitmap bm = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888);
        buf.rewind();
        bm.copyPixelsFromBuffer(buf);
        String name = new File(romPath).getName() + "_"
                + new SimpleDateFormat("MMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        File out = new File(new File(MainActivity.root(), "shots"), name);
        out.getParentFile().mkdirs();
        boolean ok = false;
        try (FileOutputStream fo = new FileOutputStream(out)) {
            bm.compress(Bitmap.CompressFormat.PNG, 100, fo);
            toast("스크린샷: shots/" + name);
            ok = true;
        } catch (Exception e) {
            toast("스크린샷 실패");
        }
        bm.recycle();
        if (!ok) return;
        /* 썸네일 지정툴 — 찍는 순간이 곧 지정하고 싶은 순간이다. 지정하면 런처가
           이 장면을 쓴다 (내 지정 > 배포 지정 > 자동 캡처 순서라 항상 이긴다). */
        final File shot = out;
        final String romName = new File(romPath).getName();
        runOnUiThread(new Runnable() { @Override public void run() {
            new android.app.AlertDialog.Builder(EmuActivity.this)
                .setMessage("이 장면을 런처 썸네일로 지정할까요?")
                .setPositiveButton("지정", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w2) {
                        File dst = new File(new File(MainActivity.root(),
                                "design/thumbs/pick"), romName + ".png");
                        dst.getParentFile().mkdirs();
                        try (java.io.FileInputStream in = new java.io.FileInputStream(shot);
                             FileOutputStream fo = new FileOutputStream(dst)) {
                            byte[] b = new byte[65536];
                            int n;
                            while ((n = in.read(b)) > 0) fo.write(b, 0, n);
                            toast("썸네일 지정됨");
                        } catch (Exception e) {
                            toast("지정 실패");
                        }
                    }})
                .setNegativeButton("아니오", null)
                .show();
        }});
    }

    /* ---- physical gamepad ---- */
    /* 매핑표(KeyMap, 설정 pocketcore_keymap)로 푼다 — 예전 고정 switch 는 KeyMap 의 기본값으로 옮겼다. */
    private int mapKey(int code) { return keymap != null ? keymap.bitOf(code) : 0; }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (KeyMap.isVolume(e.getKeyCode())) return super.dispatchKeyEvent(e);   /* 음량은 늘 시스템으로 — 창이 떠 있어도 */
        if (sheet != null && sheet.isShowing()) {           /* 옵션 창이 떠 있으면 패드는 창을 조작한다 */
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) return sheet.handleKey(e) || true;
            return true;
        }
        boolean gamepad = KeyMap.isGamepad(e);
        {   /* 바가 열려 있으면 입력은 바를 조작한다 — 게임엔 안 간다(리뷰 F17). 방향·BACK·확인 키는 출처와 무관하게 받는다
               (게임기 내장 십자는 키보드 출처로 올 때가 있다). */
            int code = e.getKeyCode();
            boolean navKey = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                          || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK;
            /* 135: 화면의 «뒤로»(내비 버튼·제스처 — 가상 장치)는 바를 닫지 않고 onBackPressed 로 — 메뉴가 열려 있으면 «한 번 더 = 목록».
               패드의 BACK(B 버튼 등)은 예전처럼 바 닫기 */
            boolean sysBack = code == KeyEvent.KEYCODE_BACK && (e.getDeviceId() == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
                    || e.getDevice() == null || e.getDevice().isVirtual());
            if (pad != null && pad.isBarOpen() && (gamepad || navKey) && !sysBack) {
                String f = keymap != null ? keymap.funcOf(code) : null;
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    if ("menu".equals(f)) pad.toggleBar();
                    else if ("left".equals(f)  || code == KeyEvent.KEYCODE_DPAD_LEFT)  pad.barMove(-1);
                    else if ("right".equals(f) || code == KeyEvent.KEYCODE_DPAD_RIGHT) pad.barMove(+1);
                    else if ("b".equals(f) || "start".equals(f) || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) pad.barActivate();
                    else if ("a".equals(f) || code == KeyEvent.KEYCODE_BACK) pad.barClose();
                }
                keyMask = 0;
                return true;
            }
        }
        if (gamepad && keymap != null) {
            String f = keymap.funcOf(e.getKeyCode());
            if ("menu".equals(f)) {                       /* 앱 메뉴 알약 여닫기 — 터치 패드가 숨겨진 게임기에서의 입구 */
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) pad.toggleBar();
                return true;
            }
            if ("turbo".equals(f)) {                      /* 배속 — 누르는 동안 */
                if (e.getAction() == KeyEvent.ACTION_DOWN) Emu.nativeSetTurbo(true);
                else if (e.getAction() == KeyEvent.ACTION_UP) Emu.nativeSetTurbo(false);
                return true;
            }
        }
        int bit = mapKey(e.getKeyCode());
        if (gamepad && bit != 0) {
            /* 반복(repeat) DOWN 은 안 세운다 — 런처에서 확인 버튼을 쥔 채 게임이 뜨면 반복만 넘어와 유령 입력이 되던 것(리뷰).
               쥐고 있는 키는 첫 DOWN 이 이미 마스크에 있으므로 반복은 정보가 없다. */
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    keyMask |= bit;
                    /* 물리 키 → 기능 → 비트를 한 줄 남긴다 — 「키가 반대다」 같은 제보를 logcat 한 줄로 가르기 위해 */
                    android.util.Log.i("PocketCore", "key " + e.getKeyCode() + " → " + (keymap != null ? keymap.funcOf(e.getKeyCode()) : "?") + " bit 0x" + Integer.toHexString(bit));
                }
            }
            else if (e.getAction() == KeyEvent.ACTION_UP) keyMask &= ~bit;
            return true;
        }
        /* 안 배정된 패드 버튼은 삼킨다 — 안 그러면 시스템이 BACK 으로 폴백해 게임이 목록으로 튕긴다(리뷰 F11) */
        if (gamepad && KeyMap.isPadButton(e.getKeyCode())) return true;
        return super.dispatchKeyEvent(e);
    }

    /** 스틱·HAT 십자 — 많은 패드가 십자를 키가 아니라 축으로 보낸다. */
    @Override public boolean onGenericMotionEvent(android.view.MotionEvent e) {
        int m = KeyMap.axisMask(e);
        if (m < 0) return super.onGenericMotionEvent(e);
        if (m == 0) axisByDev.delete(e.getDeviceId()); else axisByDev.put(e.getDeviceId(), m);
        int all = 0;
        for (int i = 0; i < axisByDev.size(); i++) all |= axisByDev.valueAt(i);
        if (sheet != null && sheet.isShowing()) {   /* 135: 옵션 창이 떠 있으면 스틱·HAT 십자도 창으로(전엔 키 십자만 갔다) — 게임엔 안 간다 */
            int rise = all & ~axisPrev;
            if ((rise & (1 << Emu.UP)) != 0)    sheet.moveFocus(-1);
            if ((rise & (1 << Emu.DOWN)) != 0)  sheet.moveFocus(+1);
            if ((rise & (1 << Emu.LEFT)) != 0)  sheet.adjust(-1);
            if ((rise & (1 << Emu.RIGHT)) != 0) sheet.adjust(+1);
            axisPrev = all; axisMask = 0;
            return true;
        }
        if (pad.isBarOpen()) {                 /* 바가 열려 있으면 스틱 좌우 엣지로 칸 이동, 게임엔 안 간다 */
            int rise = all & ~axisPrev;
            if ((rise & (1 << Emu.LEFT)) != 0)  pad.barMove(-1);
            if ((rise & (1 << Emu.RIGHT)) != 0) pad.barMove(+1);
            axisPrev = all; axisMask = 0;
            return true;
        }
        axisPrev = all;
        axisMask = all;                        /* 장치별로 들고 OR — 두 번째 장치의 중립이 첫 장치의 방향을 지우지 않게 */
        return true;
    }

    @Override protected void onPause() {
        super.onPause();
        resumedNow = false;
        releasePhysical();
        try { ((android.hardware.input.InputManager) getSystemService(INPUT_SERVICE))
                .unregisterInputDeviceListener(devListener); } catch (Exception ignored) { }
        if (loaded) {
            Emu.nativeSaveSram();
            /* 오토세이브 — SRAM 저장과 같은 자리·같은 방식(UI 스레드 직접 호출 전례).
               gl.onPause() 전이어야 한다. */
            if (loaded && autoSave) Emu.nativeSaveState(autoStatePath().getAbsolutePath());   /* 다시 굽으려 내린 뒤엔 안 쓴다 */
            /* 랭크전: 앱이 내려가는 «그 순간»만 체크포인트로 — 다시 올라오면 지운다(되감기로 쓰지 못하게).
               이미 결판이 잡혀 있으면 체크포인트 대신 바로 정산한다(정산 직전 종료). */
            if (loaded && leagueId != null && leagueArmed && !leagueDone) {
                leagueSyncStarted();
                leagueSettleNow();
                /* 정산 저장이 실패해도 체크포인트는 쓴다 — 게임은 결판 프레임에 멈춰 있어 bit7 이 선 채로 담기고,
                   재개하면 FROM_CKPT 가 같은 결과를 다시 잡는다(재시도가 성공하면 settle 이 체크포인트를 지운다).
                   아직 경기 시작 전(준비)이면 체크포인트가 필요 없다 — 다시 열면 새 판으로 시작한다. */
                org.json.JSONObject m = lm();
                if (!leagueDone && m != null && !"ready".equals(m.optString("status"))) leagueSaveCkpt();
            }
        }
        /* 오디오 장치를 놓는다 — 백그라운드에서 물고 있으면 다른 앱 재생 뒤
           스트림이 죽은 채 돌아오는 무음 사고가 난다. 복귀 때 새로 연다. */
        Emu.nativeAudioPause();
        gl.onPause();
    }

    @Override protected void onResume() {
        super.onResume(); Orient.apply(this); immersive(); gl.onResume();
        resumedNow = true;
        if (leagueId != null && leagueArmed && !leagueDone) League.ckpt(leagueId).delete();   /* 다시 올라왔다 — 경기는 이어서 돈다 */
        if (leagueId != null && leagueArmed && leagueDone && !leaving) leagueExit(leagueId);  /* 백그라운드에서 정산됐다 — 이제 결과 창으로 */
        if (loaded) Emu.nativeAudioResume();
        keymap = KeyMap.load();   /* 매핑 화면에서 돌아온 경우 */
        pushCoreOpts();           /* 설정에서 코어 옵션(프레임 생성·런어헤드·띠)을 바꾸고 돌아온 경우 — 게임 중에 바로 */
        applyFrameGen(false);     /* 설정에서 프레임 생성을 바꾸고 돌아온 경우 */
        applyDisplay();           /* 업스케일러·필터 */
        applyScreenLayout();      /* 설정에서 돌아온 경우 바로 반영 (터치 패드 모드 포함) */
        try { ((android.hardware.input.InputManager) getSystemService(INPUT_SERVICE))
                .registerInputDeviceListener(devListener, h); } catch (Exception ignored) { }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (loaded) Emu.nativeUnload();
    }

    /* ── 가상 리그 랭크전 ─────────────────────────────────────────────── */

    private TextView leagueTag;
    private String corePathCache;
    private boolean resumedNow = false;        /* 화면에 떠 있나 — 백그라운드에서는 리그 화면을 띄우지 않고 복귀 때 띄운다 */
    private boolean leagueChoosing = false;    /* 나가기 창에서 「나중에」·「기권」을 골랐다 — 창이 닫혀도 멈춤을 풀지 않는다 */

    private org.json.JSONObject lm() { return leagueId != null ? League.matchById(leagueId) : null; }

    /** 게임 화면 밖 이름표 — 위쪽 메뉴 알약 줄의 왼쪽(게임 화면·버튼을 덮지 않는다). 게임 안 이름(우쿄)과 닉네임(칼끝노인)을 갈라 보인다. */
    private void addLeagueTag() {
        org.json.JSONObject m = lm();
        org.json.JSONObject o = (m != null) ? League.oppById(m.optString("opp")) : null;
        leagueTag = new TextView(this);
        leagueTag.setText("랭크전 · " + (o != null ? o.optString("nick") + " (" + League.CHAR[o.optInt("char")] + " · "
                + League.STYLE[o.optInt("style")] + ")" : "?"));
        leagueTag.setTextColor(0xffd9a441);
        leagueTag.setTextSize(11);
        float dp = getResources().getDisplayMetrics().density;
        leagueTag.setPadding(Math.round(8 * dp), Math.round(2 * dp), 0, 0);
        root.addView(leagueTag, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.LEFT));
    }

    /** 자동 정산 가능한 판인가 — 롬 사본 md5 와 코어(앱 동봉)를 고정 목록과 맞추고, 재개면 시작 때 기록한 판과도 맞춘다. 아니면 이유. */
    private String leagueVersionProblem(org.json.JSONObject m) {
        String md5 = League.md5(new File(romPath));
        String label = League.romLabel(md5);
        if (label == null) return "판정 주소를 확인하지 않은 롬입니다 (md5 " + md5 + ")";
        String core = corePathCache;
        if (core == null || !core.startsWith(getApplicationInfo().nativeLibraryDir))
            return "앱 동봉 코어가 아닌 코어로 실행 중입니다 (" + core + ")";
        String was = m.optString("rom_md5", "");
        if (!was.isEmpty() && !was.equals(md5))
            return "경기를 시작한 롬과 지금 롬이 다릅니다 (" + m.optString("rom") + " → " + label + ")";
        if (!was.equals(md5)) {
            League.note(leagueId, "rom_md5", md5);
            League.note(leagueId, "rom", label);
            League.note(leagueId, "core", coreLabel);
            try { League.note(leagueId, "app", getPackageManager().getPackageInfo(getPackageName(), 0).versionCode); } catch (Exception ignored) { }
        }
        return null;
    }

    /**
     * 랭크전 시작 — 인텐트 표시가 아니라 **경기 상태**로 가른다(액티비티가 다시 만들어지면 처음 인텐트가 그대로 온다).
     *   준비(ready)      → 새로 켠 판 + 메뉴 자동 진입. 남은 체크포인트는 있을 수 없으니 지운다.
     *   진행·복귀 대기    → 체크포인트로만 이어간다. 없거나 못 불러오면 새 판을 열지 않고 리그 화면으로(재개 불가 → 기권).
     *   끝난 경기         → 무장하지 않고 결과 창으로.
     * 롬·코어가 안 맞으면: 준비면 보류(대진 전 실패 — 점수 없음), 이미 시작한 경기면 상태·체크포인트를 그대로 두고 리그 화면으로
     * (원래 판으로 되돌리면 재개, 아니면 기권 — 판을 바꿔 지는 경기를 무효로 만들 수 없다).
     * 체크포인트 불러오기·무장은 UI 스레드에서 바로 한다(loadCore 의 자동 이어하기와 같은 자리) — onResume 이 체크포인트를 지우기 전에.
     */
    private void leagueStart() {
        org.json.JSONObject m = lm();
        String st = m.optString("status");
        if (League.terminal(st)) { leagueDone = true; leagueExit(leagueId); return; }
        boolean fresh = "ready".equals(st);
        String why = !loaded ? "코어/롬 로드 실패" : leagueVersionProblem(m);
        if (why != null) {
            if (fresh) leagueHoldExit("자동 정산 중지 — " + why,
                    "앱 동봉 코어와 지원 롬(순정 ss2.ngc·ss2_h1.ngc + 한글패치 v1.01/동봉판, 또는 순정)으로 되돌린 뒤 새로 도전하세요.");
            else {
                League.setStatus(leagueId, "resume", why);
                League.note(leagueId, "fix", "경기를 시작한 판(롬 " + m.optString("rom", "?") + " · 앱 동봉 코어)으로 되돌리면 재개할 수 있습니다. 아니면 기권만 할 수 있습니다.");
                leagueBlocked(why + "\n\n경기는 그대로 남겨 둡니다. 원래 판으로 되돌리면 이어할 수 있고, 아니면 기권만 할 수 있습니다.");
            }
            return;
        }
        File ck = League.ckpt(leagueId);
        boolean fromCk = false;
        if (fresh) ck.delete();
        else {
            boolean had = ck.exists();
            if (had) fromCk = Emu.nativeLoadState(ck.getAbsolutePath()) == 0;
            if (!fromCk) {
                League.setStatus(leagueId, "resume", had ? "체크포인트를 불러오지 못함" : "체크포인트 없음");
                leagueBlocked(had ? "체크포인트를 불러오지 못했습니다(파일은 남겨 둡니다)." : "이어할 자리가 없습니다(체크포인트 없음) — 기권만 할 수 있습니다.");
                return;
            }
            ck.delete();                                      /* 한 번 쓰면 끝 */
        }
        org.json.JSONObject rule = m.optJSONObject("rule");
        {   /* 배경·컬러 — 경기에 적힌 값(이어 하기), 없으면 새 판만 고른 값(게임 속 리그 B·옵션과 같은 설정)으로 정해 적는다.
               적힌 값 없이 체크포인트에서 이어 하는 경기(이 기능 전에 시작한 경기)는 게임 그대로 — 판 중간에 배경·색이 바뀌지 않게 */
            org.json.JSONObject lk = m.optJSONObject("look");
            int stg = lk != null ? lk.optInt("stage", -1) : fromCk ? -1 : League.resolveStage(League.prefStage(), m.optString("opp"));
            int col = lk != null ? lk.optInt("color", 0) : fromCk ? 0 : League.prefColor();
            if (lk == null) League.setLook(leagueId, stg, col);
            Emu.nativeLeagueLook(stg, col);
        }
        /* 올카드(랭크전 전용 세이브라 원작 세이브엔 안 남는다) + 새 판이면 메뉴 자동 진입, 재개면 이미 선 결판도 잡기 */
        Emu.nativeLeagueArm(rule.optInt("char"), rule.optInt("style"), rule.optInt("diff"),
                rule.optInt("points"), rule.optInt("time"),
                Emu.LEAGUE_ALLCARDS | (fromCk ? Emu.LEAGUE_FROM_CKPT : Emu.LEAGUE_AUTONAV));
        leagueArmed = true;
        /* 새 판은 첫 라운드가 실제로 시작될 때까지 «준비» 그대로 — 그 전에 멈추면 무효로 닫을 수 있다(대진 준비 실패는 점수에 안 넣는다).
           재개는 이미 시작한 경기라 바로 «진행 중»(지난 사유는 지운다). */
        leagueStarted = fromCk;
        if (fromCk) League.setStatus(leagueId, "playing", "");
        toast(fromCk ? "랭크전 — 내려간 자리에서 이어합니다" : "랭크전 — 캐릭터 고르기로 들어갑니다. 캐릭터·검질·카드를 고르세요");
        h.postDelayed(leaguePoll, 250);
    }

    private boolean leagueStarted = false;      /* 첫 라운드가 실제로 시작됐나(league.c 경기 시작 규칙) */

    /** 경기 시작을 봤으면 «준비» → «진행 중». poll·onPause·나가기에서 부른다. */
    private void leagueSyncStarted() {
        if (leagueStarted || !leagueArmed || leagueDone) return;
        int[] st = Emu.nativeLeagueStatus();
        if (st != null && st.length >= 21 && st[3] == 1) {
            leagueStarted = true;
            League.setStatus(leagueId, "playing", "");
        }
    }

    private boolean nativeDone() {
        int[] st = Emu.nativeLeagueStatus();
        return st != null && st.length >= 21 && st[4] == 1;
    }

    /** 결판이 잡혀 있으면 바로 정산한다. 잡혀 있으면 true — 정산 저장이 실패했어도(그때는 체크포인트를 쓰지 않고, 결과는 코어 쪽에 둔 채 poll 이 다시 시도). */
    private boolean leagueSettleNow() {
        if (leagueDone) return true;
        if (!leagueArmed) return false;
        int[] st = Emu.nativeLeagueStatus();
        if (st == null || st.length < 21 || st[4] != 1) return false;
        Emu.nativeSetPaused(true);                            /* 결과 화면은 최종 승패가 확정된 뒤에만 — 게임은 그 자리에서 멈춘다 */
        if (League.settle(leagueId, st) == null) return true;
        leagueDone = true;
        Emu.nativeLeagueDisarm();
        return true;
    }

    /** 체크포인트 — 임시 파일에 쓰고 이름을 바꿔 넣는다(쓰다 죽어도 반쪽 파일이 안 남는다). 실패하면 옛 것을 그대로 둔다. */
    private boolean leagueSaveCkpt() {
        File ck = League.ckpt(leagueId), tmp = new File(ck.getPath() + ".tmp");
        if (Emu.nativeSaveState(tmp.getAbsolutePath()) != 0) { tmp.delete(); return false; }
        if (!tmp.renameTo(ck)) { tmp.delete(); return false; }
        return true;
    }

    /** 결판 감시 — 진짜 프레임에서 league.c 가 잡은 결과를 가져와 한 번만 정산한다. 화면에 떠 있을 때만 리그 화면으로 넘어간다. */
    private final Runnable leaguePoll = new Runnable() { @Override public void run() {
        if (leagueId == null) return;
        if (leagueDone) { if (resumedNow && !leaving) leagueExit(leagueId); return; }
        if (!loaded) return;
        leagueSyncStarted();
        if (nativeDone()) {
            if (leagueDlg != null && leagueDlg.isShowing()) leagueDlg.dismiss();
            if (!leagueSettleNow() || !leagueDone) {           /* 저장 실패 — 결과는 코어 쪽에 그대로 있으니 다시 */
                if (++leagueRetry % 8 == 1) toast("기록 저장 실패 — 다시 시도 중");
                h.postDelayed(this, 1000);
                return;
            }
            if (resumedNow) leagueExit(leagueId);
            return;
        }
        h.postDelayed(this, 250);
    }};

    /** 아직 대진 전(준비)에 롬·코어가 안 맞으면 — 보류(점수 없음), 원인과 복구 방법을 남기고 결과 창으로. */
    private void leagueHoldExit(final String why, String fix) {
        League.hold(leagueId, why, fix);
        leagueDone = true;
        h.post(new Runnable() { @Override public void run() {
            new android.app.AlertDialog.Builder(EmuActivity.this).setTitle("가상 리그")
                    .setMessage(why + "\n\n이 경기는 점수에 넣지 않습니다(보류).")
                    .setCancelable(false)
                    .setPositiveButton("확인", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) { leagueExit(leagueId); } })
                    .show();
        }});
    }

    /** 시작한 경기를 지금 이어갈 수 없을 때 — 무장하지 않고 상태를 그대로 둔 채 리그 화면으로(재개 또는 기권). */
    private void leagueBlocked(final String msg) {
        leagueDone = true;
        h.post(new Runnable() { @Override public void run() {
            new android.app.AlertDialog.Builder(EmuActivity.this).setTitle("가상 리그 — 이어할 수 없음")
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton("리그 화면으로", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) { leagueExit(null); } })
                    .show();
        }});
    }

    /** 뒤로 가기 — 계속 / 나중에 이어하기(체크포인트) / 기권. 결판이 이미 잡혀 있으면 창 없이 정산으로. */
    private void leagueLeave() {
        if (leagueDone || !leagueArmed) { leagueExit(null); return; }
        if (leagueSettleNow()) { if (leagueDone && resumedNow) leagueExit(leagueId); return; }
        if (leagueDlg != null && leagueDlg.isShowing()) return;
        leagueSyncStarted();
        if (!leagueStarted) { leagueLeaveBeforeStart(); return; }
        leagueChoosing = false;
        Emu.nativeSetPaused(true);
        leagueDlg = new android.app.AlertDialog.Builder(this).setTitle("랭크전 중")
                .setMessage("나중에 이어하면 지금 자리에서 한 번만 다시 열 수 있습니다. 기권하면 패배로 기록됩니다.")
                .setPositiveButton("계속하기", null)
                .setNeutralButton("나중에 이어하기", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        leagueChoosing = true;
                        if (leagueSettleNow()) { leagueChoosing = false; return; }   /* poll 이 결과 화면으로 */
                        gl.queueEvent(new Runnable() { @Override public void run() {
                            final boolean ok = leagueSaveCkpt();
                            h.post(new Runnable() { @Override public void run() {
                                if (leagueDone) return;
                                if (!ok) {
                                    leagueChoosing = false;
                                    toast("체크포인트를 저장하지 못했어요 — 경기를 계속합니다");
                                    Emu.nativeSetPaused(pad.isBarOpen());
                                    return;
                                }
                                League.setStatus(leagueId, "resume", "사용자가 나중에 이어하기");
                                leagueDone = true; Emu.nativeLeagueDisarm(); leagueExit(null);
                            }});
                        }});
                    } })
                .setNegativeButton("기권", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        leagueChoosing = true;
                        if (leagueSettleNow()) { leagueChoosing = false; return; }
                        if (League.forfeit(leagueId) == null) {
                            leagueChoosing = false;
                            toast("저장 실패 — 다시 시도하세요");
                            Emu.nativeSetPaused(pad.isBarOpen());
                            return;
                        }
                        leagueDone = true; Emu.nativeLeagueDisarm(); leagueExit(leagueId);
                    } })
                .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(android.content.DialogInterface d) {
                        if (!leagueDone && !leagueChoosing && !nativeDone()) Emu.nativeSetPaused(pad.isBarOpen());
                    } })
                .show();
    }

    /** 첫 라운드 전(메뉴·캐릭터 고르기·이야기)에 나가기 — 아직 승부가 아무것도 정해지지 않았으니 무효로 닫는다(점수 없음). */
    private void leagueLeaveBeforeStart() {
        leagueChoosing = false;
        Emu.nativeSetPaused(true);
        leagueDlg = new android.app.AlertDialog.Builder(this).setTitle("랭크전 — 경기 시작 전")
                .setMessage("아직 첫 라운드가 시작되지 않았습니다. 그만두면 이 경기는 무효로 닫히고 점수는 그대로입니다.")
                .setPositiveButton("계속하기", null)
                .setNegativeButton("그만두기(무효)", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        leagueChoosing = true;
                        leagueSyncStarted();
                        if (leagueStarted) {                   /* 창이 뜨는 사이 첫 라운드가 시작됐다 — 보통 나가기로 */
                            leagueChoosing = false;
                            h.post(new Runnable() { @Override public void run() { leagueLeave(); } });
                            return;
                        }
                        if (!League.cancel(leagueId, "경기 시작 전에 사용자가 그만둠")) {
                            leagueChoosing = false;
                            toast("저장 실패 — 다시 시도하세요");
                            Emu.nativeSetPaused(pad.isBarOpen());
                            return;
                        }
                        leagueDone = true; Emu.nativeLeagueDisarm(); leagueExit(leagueId);
                    } })
                .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(android.content.DialogInterface d) {
                        if (!leagueDone && !leagueChoosing && !nativeDone()) Emu.nativeSetPaused(pad.isBarOpen());
                    } })
                .show();
    }

    /** 리그 화면으로 — 결과가 있으면 그 결과 창을 띄운다. 코어는 GL 스레드에서 내린다(goList 와 같은 이유, 내릴 때 native 가 무장도 푼다). */
    private void leagueExit(final String resultId) {
        if (leagueHub && resultId != null) { hubResult(resultId); return; }   /* 게임 속 리그: 결과 카드를 게임 위에 — 나가지 않는다 */
        if (leaving) return;
        leaving = true;
        MainActivity.forgetLast(this);
        gl.queueEvent(new Runnable() { @Override public void run() {
            Emu.nativeLeagueDisarm();
            if (loaded) { Emu.nativeSaveSram(); Emu.nativeUnload(); loaded = false; }
            runOnUiThread(new Runnable() { @Override public void run() {
                Intent i = new Intent(EmuActivity.this, LeagueActivity.class);
                i.putExtra(LeagueActivity.EXTRA_ROM, getIntent().getStringExtra("rom"));
                if (resultId != null) i.putExtra(LeagueActivity.EXTRA_RESULT, resultId);
                startActivity(i);
                finish();
            }});
        }});
    }

    /* ── 게임 속 리그(허브) ─────────────────────────────────────────────
       타이틀 메뉴 «2인 대전» 자리가 «리그 대전»(league.c 가 메뉴 글자를 바꿔 그린다). 그 칸을 고르면 1인 대전 캐릭터 고르기가
       «상대 선택» 화면이 되고(커서 캐릭터 = 그 캐릭터를 주캐로 쓰는 가상 상대), A 로 상대를 고르면 경기 기록을 만들고(준비),
       같은 화면에서 내 캐릭터·검질·카드를 고른다. 그 뒤는 1단계 랭크전과 같다(시작 → 진행 중 → 결판 → 정산·체크포인트·기권).
       결판 뒤엔 게임 위에 결과 카드 → 계속(리셋 뒤 타이틀 «리그 대전» 칸) · 재대결(같은 상대로 바로) · 순위표. */

    private final int[] gameBox = new int[4];
    private TextView hubBanner;
    private android.view.View hubCard;
    private final String[] hubOpp = new String[15];       /* 캐릭터 id → 가상 상대 id */
    private android.graphics.Typeface hubFont;
    private int hubLastState = -1;
    private int hubGen = 0, hubWaitGen = 0;
    private int hubStageSel = -1, hubColor = 0, hubStage = -1;   /* 허브가 고른 배경(−1 상대 기본·−2 무작위·0~7)·내 컬러·무장한 경기의 배경 */     /* 코어가 받은 «타이틀로» 횟수 · 기다리는 횟수 — 닿기 전 옛 상태(3 = 상대 고름)로 경기 기록을 또 만들지 않게 */

    private android.graphics.Typeface hubFont() {
        if (hubFont == null) {
            try { hubFont = android.graphics.Typeface.createFromAsset(getAssets(), "fonts/pixel.ttf"); }
            catch (Exception e) { hubFont = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); }
        }
        return hubFont;
    }

    /** 안내 띠 — 게임 그림 아래에서 패드 버튼과 겹치지 않는 첫 빈 줄에(버튼 배치를 바꿔도 따라감). 자리가 없으면 게임 그림 위쪽 가장자리.
     *  손가락으로 끌어 옮길 수 있고(세로·가로 따로 기억), 두 번 톡 치면 자동 자리로 돌아간다
     *  (유저 2026-10-14 「밑에 옵션 설명해둔 거 패드를 가리는 데다가 이동도 안 되네」 — 전엔 SP·A+B 버튼 줄에 걸쳤다). */
    private void addHubBanner() {
        float dp = getResources().getDisplayMetrics().density;
        hubBanner = new TextView(this);
        hubBanner.setTypeface(hubFont());
        hubBanner.setTextColor(0xffffd76a);
        hubBanner.setTextSize(11);
        hubBanner.setLineSpacing(0, 1.1f);
        hubBanner.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xe6140d08); g.setStroke(Math.round(2 * dp), 0xffb8862b); g.setCornerRadius(4 * dp);
        hubBanner.setBackground(g);
        hubBanner.setPadding(Math.round(10 * dp), Math.round(6 * dp), Math.round(10 * dp), Math.round(6 * dp));
        hubBanner.setText("가상 리그 — 메뉴의 「리그 대전」을 고르세요");
        root.addView(hubBanner, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.LEFT));
        hubBanner.setOnTouchListener(bannerDrag);
        hubBanner.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!bannerDragging && (b - t) != (ob - ot) && (b - t) != bannerPlacedH) h.post(this::placeHubBanner);   /* 글이 바뀌어 줄 수가 달라짐 */
        });
        placeHubBanner();
    }

    private boolean bannerDragging = false, bannerUserPos = false;
    private int bannerPlacedH = -1;
    private String bannerKey() { return root != null && root.getHeight() > root.getWidth() ? "pocketcore_hubbanner_p" : "pocketcore_hubbanner_l"; }

    private int bannerHeightAt(int width) {
        hubBanner.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return hubBanner.getMeasuredHeight();
    }

    private void placeHubBanner() {
        if (hubBanner == null || root == null) return;
        int W = root.getWidth(), H = root.getHeight();
        if (W <= 0 || H <= 0) return;
        float dp = getResources().getDisplayMetrics().density;
        boolean port = H > W;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hubBanner.getLayoutParams();
        int edge = Math.round(8 * dp);
        int maxW = Math.min(W - 2 * edge, Math.round(W * (port ? 0.92f : 0.56f)));
        int left, top, width;
        String saved = Settings.load().get(bannerKey());
        float[] pos = null;
        if (saved != null) {
            try { String[] a = saved.split(","); pos = new float[] { Float.parseFloat(a[0]), Float.parseFloat(a[1]) }; } catch (Exception e) { pos = null; }
        }
        bannerUserPos = pos != null;
        if (pos != null) {                                       /* 유저가 옮겨 둔 자리 — 가운데 점(화면 비율)을 지킨다 */
            width = Math.min(maxW, Math.round(W * (port ? 0.80f : 0.50f)));
            int hh = bannerHeightAt(width);
            left = Math.round(pos[0] * W - width / 2f); top = Math.round(pos[1] * H - hh / 2f);
            left = Math.max(0, Math.min(W - width, left)); top = Math.max(0, Math.min(H - hh, top));
            bannerPlacedH = hh;
        } else {                                                 /* 자동 — 게임 그림 아래, 버튼과 안 겹치는 첫 줄(폭이 화면 절반 이상) */
            int y = gameBox[3] + Math.round(6 * dp), step = Math.round(6 * dp);
            int minW = Math.min(maxW, Math.round(W * (port ? 0.50f : 0.36f)));
            left = -1; top = -1; width = maxW;
            for (; y < H; y += step) {
                int hh = bannerHeightAt(maxW);
                if (y + hh > H - Math.round(4 * dp)) break;
                float[] sp = pad != null ? pad.freeSpan(y, y + hh) : new float[] { 0, W };
                int spW = Math.round(sp[1] - sp[0]) - 2 * edge;
                if (spW < minW) continue;
                int w2 = Math.min(maxW, spW), h2 = bannerHeightAt(w2);   /* 좁으면 줄이 늘 수 있다 — 늘어난 높이로 다시 본다 */
                if (h2 > hh) {
                    if (y + h2 > H - Math.round(4 * dp)) break;
                    float[] sp2 = pad != null ? pad.freeSpan(y, y + h2) : sp;
                    if (Math.round(sp2[1] - sp2[0]) - 2 * edge < w2) continue;
                }
                left = Math.round((sp[0] + sp[1]) / 2f - w2 / 2f); top = y; width = w2; bannerPlacedH = h2;
                break;
            }
            if (top < 0) {                                       /* 빈 줄이 없다(가로 화면 등) — 게임 그림 위쪽 가장자리에 얇게 */
                width = Math.min(maxW, gameBox[2] - gameBox[0]);
                left = gameBox[0] + ((gameBox[2] - gameBox[0]) - width) / 2;
                top = gameBox[1] + Math.round(4 * dp);
                bannerPlacedH = bannerHeightAt(width);
            }
        }
        lp.width = width; lp.leftMargin = left; lp.topMargin = top; lp.rightMargin = 0;
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        hubBanner.setLayoutParams(lp);
    }

    private final View.OnTouchListener bannerDrag = new View.OnTouchListener() {
        float x0, y0; int l0, t0; long lastUp = 0;
        @Override public boolean onTouch(View v, android.view.MotionEvent e) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            float slop = android.view.ViewConfiguration.get(EmuActivity.this).getScaledTouchSlop();
            switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                x0 = e.getRawX(); y0 = e.getRawY(); l0 = lp.leftMargin; t0 = lp.topMargin; bannerDragging = false;
                return true;
            case android.view.MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                if (!bannerDragging && Math.hypot(dx, dy) < slop) return true;
                bannerDragging = true;
                lp.leftMargin = Math.max(0, Math.min(root.getWidth() - v.getWidth(), Math.round(l0 + dx)));
                lp.topMargin = Math.max(0, Math.min(root.getHeight() - v.getHeight(), Math.round(t0 + dy)));
                v.setLayoutParams(lp);
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
                if (bannerDragging) {                             /* 옮긴 자리 기억 — 가운데 점을 화면 비율로 */
                    float cxF = (lp.leftMargin + v.getWidth() / 2f) / root.getWidth();
                    float cyF = (lp.topMargin + v.getHeight() / 2f) / root.getHeight();
                    Settings.put(bannerKey(), String.format(java.util.Locale.US, "%.4f,%.4f", cxF, cyF));
                    bannerUserPos = true;
                    bannerDragging = false;
                } else {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastUp < 350) {                     /* 두 번 톡 — 자동 자리로 */
                        Settings.removePrefix(bannerKey());
                        placeHubBanner();
                        toast("안내 띠를 기본 자리로 돌렸어요");
                        lastUp = 0;
                    } else lastUp = now;
                }
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                bannerDragging = false;
                return true;
            }
            return false;
        }
    };

    /** 판정 주소를 실측한 롬 사본·동봉 코어인가(경기 없이 보는 판) — 아니면 이유 */
    private String hubVersionProblem() {
        String md5 = League.md5(new File(romPath));
        if (League.romLabel(md5) == null) return "판정 주소를 확인하지 않은 롬입니다 (md5 " + md5 + ")";
        String core = corePathCache;
        if (core == null || !core.startsWith(getApplicationInfo().nativeLibraryDir)) return "앱 동봉 코어가 아닌 코어로 실행 중입니다 (" + core + ")";
        return null;
    }

    private void hubStart() {
        if (!loaded) { leagueBlocked("코어·롬을 불러오지 못했습니다."); return; }
        String why = hubVersionProblem();
        if (why == null) why = League.dbProblem;
        if (why == null) why = League.writeProblem();
        if (why != null) { leagueBlocked("가상 리그를 열 수 없습니다 — " + why); return; }
        byte[] sty = new byte[15], dif = new byte[15];
        java.util.Arrays.fill(sty, (byte) -1); java.util.Arrays.fill(dif, (byte) 1);
        org.json.JSONArray os = League.opps();
        for (int i = 0; i < os.length(); i++) {
            org.json.JSONObject o = os.optJSONObject(i);
            int c = o.optInt("char", -1), st = o.optInt("style");
            if (c < 0 || c >= 15 || !League.oppSupported(c, st)) continue;
            hubOpp[c] = o.optString("id"); sty[c] = (byte) st; dif[c] = (byte) o.optInt("diff");
        }
        Emu.nativeLeagueHub(true, sty, dif);
        byte[] home = new byte[15];                                       /* 캐릭터 → 그 상대의 기본 배경(«상대 기본») */
        for (int c = 0; c < 15; c++) home[c] = (byte) (hubOpp[c] != null ? League.homeStage(hubOpp[c]) : -1);
        hubStageSel = League.prefStage(); hubColor = League.prefColor();
        Emu.nativeLeagueHubLook(home, hubStageSel, hubColor);
        org.json.JSONObject open = League.openMatch();
        if (open != null) {
            if ("ready".equals(open.optString("status"))) League.cancel(open.optString("id"), "게임 속 리그를 새로 열며 정리(대진 전)");
            else { leagueId = open.optString("id"); leagueStart(); }      /* 내려간 경기 — 체크포인트로 이어 하고, 결판 뒤 허브로 */
        }
        h.postDelayed(hubPoll, 200);
    }

    private final Runnable hubPoll = new Runnable() { @Override public void run() {
        if (!leagueHub || leaving) return;
        if (loaded) {
            int[] hs = Emu.nativeLeagueHubStatus();
            if (hs != null && hs.length >= 8) hubTick(hs);
        }
        h.postDelayed(this, 150);
    }};

    private void hubTick(int[] hs) {
        int st = hs[0], hover = hs[1], picked = hs[2];
        if (hs.length > 8) hubGen = hs[8];
        if (hs.length > 11) {                                           /* B(배경)·옵션(컬러)을 바꿨으면 다음에도 그대로(기록 파일 pref) */
            if (hs[9] != hubStageSel || hs[10] != hubColor) { hubStageSel = hs[9]; hubColor = hs[10]; League.setPrefLook(hubStageSel, hubColor); }
            hubStage = hs[11];
            if (leagueId != null && !leagueStarted && hubStage >= 0) League.setLook(leagueId, hubStage, hubColor);   /* 대진 전에 컬러를 바꿔도 기록에 */
        }
        if (hubGen < hubWaitGen) {                                      /* 결과 카드·«타이틀로» 뒤 리셋이 아직 GL 스레드에 안 닿음 */
            if (hubBanner != null && hubCard == null) hubBanner.setText(hubText(5, hover, picked, hs[4]));
            return;
        }
        /* 상대를 골랐다 → 경기 기록(준비). 뒤로 가기 창이 떠 있으면 창이 정한 뒤에(계속하기 → 다음 확인에서 만듦, 나가기·타이틀로 → 안 만듦) */
        if (st == 3 && leagueId == null && hubCard == null && !(leagueDlg != null && leagueDlg.isShowing())) {
            String oid = (picked >= 0 && picked < 15) ? hubOpp[picked] : null;
            org.json.JSONObject m = oid != null ? League.newMatch(oid) : null;
            if (m == null) {
                String why = oid == null ? "그 캐릭터는 리그 상대가 없습니다" : League.blockedWhy(League.oppById(oid));
                toast("경기를 만들지 못했어요 — " + (why != null ? why : "기록 저장 실패"));
                hubReturn(-1);
                return;
            }
            leagueId = m.optString("id"); leagueArmed = true; leagueDone = false; leagueStarted = false; leagueRetry = 0;
            leagueVersionProblem(m);                                     /* 롬 md5·코어·앱 판을 경기에 적는다(판은 hubStart 에서 확인) */
            League.setLook(leagueId, hubStage, hubColor);
            h.removeCallbacks(leaguePoll); h.postDelayed(leaguePoll, 250);
        }
        hubLastState = st;
        /* 결과 카드가 떠 있는 동안은 카드 안내를 그대로 두고, 카드를 닫은 직후(리셋이 GL 스레드에 닿기 전 — 허브는 아직 «경기»)는 «돌아가는 중» */
        /* 이어 하는 경기(체크포인트)는 허브가 타이틀 상태인 채로 싸운다 — 띠는 경기 안내로 */
        int shown = leagueId != null && st <= 2 ? 4 : st == 4 && leagueId == null ? 5 : st;
        if (hubBanner != null && hubCard == null) hubBanner.setText(hubText(shown, hover, picked, hs[4]));
    }

    /** 상대 고르기 안내 세 줄 — 이름·캐릭터·난도 / 순위·점수·상대 전적 / 별칭·버튼 (머리말이 이미 «상대 선택» 이라 앞말은 빼고,
     *  스테이지 이름은 게임 화면 오른쪽 세로 칸에 나오니 버튼만) */
    private String hubOppLine(org.json.JSONObject o, String why) {
        int myW = o.optInt("l"), myL = o.optInt("w");
        String t = o.optString("nick") + " · " + League.CHAR[o.optInt("char")] + " " + League.STYLE[o.optInt("style")]
                 + " · " + League.DIFF[o.optInt("diff")] + "\n" + League.rankOf(o.optString("id")) + "위 · " + o.optInt("score") + "점 · 나와 "
                 + myW + "승 " + myL + "패" + (o.optInt("d") > 0 ? " " + o.optInt("d") + "무" : "") + "\n";
        if (why != null) return t + "도전할 수 없음: " + why;
        java.util.List<String> tg = League.tags(o);
        String tag = o.optInt("char") == 14 ? "가운데 칸 = 숨은 상대" : tg.isEmpty() ? null : tg.get(0);   /* 유가는 고르기 판에 없어 무작위 칸에 */
        return t + (tag == null ? "" : tag + "  ·  ") + "A 도전  ·  B 스테이지";
    }

    private String hubText(int st, int hover, int picked, int label) {
        org.json.JSONObject me = League.me();
        switch (st) {
        case 1: return "가상 리그 — 메뉴의 " + (label == 1 ? "「리그 대전」을" : label == 2 ? "「LEAGUE」를" : "셋째 줄을")
                + " 고르세요\n다른 메뉴는 평소대로 · 리그 기록 안 됨\n내 순위 " + League.rankOf("me") + "위 / " + League.order().size() + "명 · "
                + me.optInt("score") + "점 · " + me.optInt("w") + "승 " + me.optInt("l") + "패";
        case 2: {
            if (hover < 0) return "이 칸은 고를 수 없습니다\n캐릭터마다 그 캐릭터를 주캐로 쓰는\n가상 상대가 있습니다";
            String oid = hover < 15 ? hubOpp[hover] : null;
            org.json.JSONObject o = oid != null ? League.oppById(oid) : null;
            if (o == null) return League.CHAR[hover] + " : 리그 상대 없음\n다른 캐릭터를 고르세요\n ";
            return hubOppLine(o, League.blockedWhy(o));
        }
        case 3: {
            org.json.JSONObject o = (picked >= 0 && picked < 15 && hubOpp[picked] != null) ? League.oppById(hubOpp[picked]) : null;
            return "상대: " + (o != null ? o.optString("nick") + " · " + League.CHAR[o.optInt("char")] + " " + League.STYLE[o.optInt("style")]
                    + " · " + League.DIFF[o.optInt("diff")] : "?") + "\n내 캐릭터·검질·카드 (카드는 올카드) · 스테이지 " + League.stageName(hubStage)
                    + "\nA 결정  ·  B 2P색으로 결정  ·  옵션 컬러";   /* 컬러 이름은 게임 화면 오른쪽 세로 칸에 */
        }
        case 4: {
            org.json.JSONObject m = lm();
            org.json.JSONObject o = m != null ? League.oppById(m.optString("opp")) : null;
            org.json.JSONObject lk = m != null ? m.optJSONObject("look") : null;
            return "랭크전 · " + (o != null ? o.optString("nick") + " · " + League.CHAR[o.optInt("char")] + " " + League.STYLE[o.optInt("style")] : "?")
                    + "\n2선승 · 라운드 60초 · 경기 전체 결판만 기록"
                    + (lk != null ? "\n스테이지 " + League.stageName(lk.optInt("stage", 0)) + " · 내 컬러 «" + League.COLORS[lk.optInt("color", 0) & 3] + "»" : "");
        }
        case 5: return "가상 리그 · 타이틀로 돌아가는 중…";
        default: return "가상 리그";
        }
    }

    /** 결과 카드 — 게임 위, 그 경기 직후 값(after)으로. 계속 / 재대결 / 순위표. */
    private void hubResult(final String resultId) {
        if (hubCard != null || leaving) return;
        final org.json.JSONObject m = League.matchById(resultId);
        if (m == null) { hubReturn(-1); return; }
        Emu.nativeSetPaused(true);
        float dp = getResources().getDisplayMetrics().density;
        final org.json.JSONObject o = League.oppById(m.optString("opp"));
        String st = m.optString("status"), title;
        StringBuilder sb = new StringBuilder();
        if ("hold".equals(st) || "cancelled".equals(st)) {
            title = "hold".equals(st) ? "보류" : "무효";
            sb.append(m.optString("why")).append("\n점수는 그대로입니다.");
        } else {
            String out = m.optString("outcome");
            title = m.optBoolean("forfeit") ? "기권패" : "W".equals(out) ? "승리!" : "L".equals(out) ? "패배" : "무승부";
            org.json.JSONObject r = m.optJSONObject("result");
            if (o != null) sb.append(o.optString("nick")).append(" (").append(League.CHAR[o.optInt("char")]).append(" ").append(League.STYLE[o.optInt("style")]).append(")\n");
            if (r != null && !m.optBoolean("forfeit")) sb.append("최종 ").append(r.optInt("w1")).append(" : ").append(r.optInt("w2")).append("\n");
            org.json.JSONObject lk = m.optJSONObject("look");
            if (lk != null) sb.append(League.stageName(lk.optInt("stage", 0))).append(" · 내 컬러 «").append(League.COLORS[lk.optInt("color", 0) & 3]).append("»\n");
            org.json.JSONObject s0 = m.optJSONObject("score_before");
            int d = m.optInt("delta");
            if (s0 != null) sb.append("점수 ").append(s0.optInt("me")).append(" → ").append(s0.optInt("me") + d).append(" (").append(d > 0 ? "+" + d : String.valueOf(d)).append(")\n")
                              .append("순위 ").append(m.optInt("rank_before")).append("위 → ").append(m.optInt("rank_after")).append("위\n");
            org.json.JSONObject af = m.optJSONObject("after");
            if (af != null && o != null) sb.append(League.gwa(o.optString("nick"))).append("의 전적 ").append(af.optInt("h2h_w")).append("승 ").append(af.optInt("h2h_l")).append("패")
                    .append(af.optInt("h2h_d") > 0 ? " " + af.optInt("h2h_d") + "무" : "").append("\n");
            if (m.optBoolean("first_win")) sb.append("이 상대에게 첫 승!\n");
            if (af != null && af.optInt("streak") > 1) sb.append(af.optInt("streak")).append("연승 (최장 ").append(af.optInt("best")).append(")\n");
            if (m.optInt("broke_streak") >= 2) sb.append(m.optInt("broke_streak")).append("연승이 끊겼습니다\n");
        }
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xf2140d08); g.setStroke(Math.round(3 * dp), 0xffd9a441); g.setCornerRadius(6 * dp);
        card.setBackground(g);
        card.setPadding(Math.round(16 * dp), Math.round(12 * dp), Math.round(16 * dp), Math.round(12 * dp));
        TextView t = new TextView(this); t.setTypeface(hubFont()); t.setTextSize(26); t.setTextColor(0xffffd76a); t.setText(title); t.setGravity(android.view.Gravity.CENTER);
        card.addView(t);
        TextView b = new TextView(this); b.setTypeface(hubFont()); b.setTextSize(14); b.setTextColor(0xfff3ead7); b.setText(sb.toString().trim());
        b.setGravity(android.view.Gravity.CENTER); b.setLineSpacing(0, 1.15f); b.setPadding(0, Math.round(6 * dp), 0, Math.round(10 * dp));
        card.addView(b);
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        final boolean canRe = o != null && League.blockedWhy(o) == null && o.optInt("char", -1) >= 0 && o.optInt("char") < 15 && hubOpp[o.optInt("char")] != null;
        if (canRe) row.addView(hubPill("재대결", new Runnable() { @Override public void run() { hubReturn(o.optInt("char")); } }));
        row.addView(hubPill("계속", new Runnable() { @Override public void run() { hubReturn(-1); } }));
        row.addView(hubPill("순위표", new Runnable() { @Override public void run() { hubCard = null; leagueExitBoard(); } }));
        card.addView(row);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        lp.topMargin = gameBox[1] + Math.round(8 * dp);
        root.addView(card, lp);
        hubCard = card;
        if (hubBanner != null) hubBanner.setText("가상 리그 · 결과가 기록되었습니다");
    }

    private TextView hubPill(String s, final Runnable r) {
        float dp = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setTypeface(hubFont()); t.setText(s); t.setTextSize(15); t.setTextColor(0xff140d08); t.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xffd9a441); g.setCornerRadius(4 * dp);
        t.setBackground(g);
        t.setPadding(Math.round(14 * dp), Math.round(8 * dp), Math.round(14 * dp), Math.round(8 * dp));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.round(5 * dp); lp.rightMargin = Math.round(5 * dp);
        t.setLayoutParams(lp);
        t.setClickable(true);
        t.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { r.run(); } });
        return t;
    }

    /** 결과 카드 닫기 → 리셋 뒤 타이틀 «리그 대전» 칸(rematch ≥ 0 이면 같은 상대로 바로 내 캐릭터 고르기까지). */
    private void hubReturn(final int rematch) {
        if (hubCard != null) { root.removeView(hubCard); hubCard = null; }
        org.json.JSONObject m = lm();                       /* 상대만 고르고 대진 전에 타이틀로 — 준비 기록은 무효로(열린 채 남지 않게) */
        if (m != null && "ready".equals(m.optString("status"))) League.cancel(leagueId, "게임 속 리그 — 대진 전에 타이틀로");
        hubWaitGen = hubGen + 1;
        leagueId = null; leagueArmed = false; leagueDone = false; leagueStarted = false; leagueRetry = 0;
        h.removeCallbacks(leaguePoll);
        Emu.nativeSetPaused(pad != null && pad.isBarOpen());
        gl.queueEvent(new Runnable() { @Override public void run() { Emu.nativeLeagueHubReturn(rematch); } });
    }

    /** 경기 밖(타이틀·상대 고르기)에서 뒤로 가기 — 상대 다시 고르기(타이틀로) / 순위표로 나가기 / 계속 */
    private void hubLeave() {
        if (leagueDlg != null && leagueDlg.isShowing()) return;
        Emu.nativeSetPaused(true);
        android.app.AlertDialog.Builder d = new android.app.AlertDialog.Builder(this).setTitle("가상 리그")
                .setPositiveButton("계속하기", null)
                .setNegativeButton("순위표로 나가기", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface x, int w) { leagueExitBoard(); } });
        if (hubLastState == 1 || hubLastState == 2) {
            d.setMessage(hubLastState == 2 ? "상대 고르기를 그만두고 타이틀로 돌아갈 수 있습니다(이 게임은 캐릭터 고르기에서 뒤로 갈 수 없어 리셋합니다)."
                    : "리그 순위표로 나가거나, 게임을 리셋해 타이틀 메뉴(「리그 대전」 칸)로 돌아갈 수 있습니다.");
            d.setNeutralButton("타이틀로", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface x, int w) { hubReturn(-1); } });
        } else d.setMessage("리그 순위표로 나갈까요?");
        leagueDlg = d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface x) { if (hubCard == null) Emu.nativeSetPaused(pad.isBarOpen()); } }).show();
    }

    /** 순위표(리그 화면)로 나가기 — 결과 창 없이 */
    private void leagueExitBoard() {
        org.json.JSONObject m = lm();                       /* 대진 전에 나가면 준비 기록은 무효로(열린 채 남지 않게) */
        if (m != null && "ready".equals(m.optString("status"))) League.cancel(leagueId, "게임 속 리그 — 대진 전에 순위표로");
        leagueHub = false;                                   /* leagueExit 가 결과 카드로 새지 않게 */
        leagueExit(null);
    }

    /* ── 연습 대전(practice.c) ─────────────────────────────────────────
       일반 세션(랭크전 아님)이고 사무쇼2이며, 지금 실행하는 롬(사본)의 md5 가 판정 주소를 실측한 판(League.romLabel)이거나,
       그 판에 배경음악(사무쇼1 곡·끔)만 심은 사본일 때(Patcher.musicBaseLabel — 소리 자료만 바뀌고 게임 램 배치는 같음, 끔 사본 롬 6판 시험)만 켠다 —
       모르는 판(수동·조작 패치를 입힌 사본 등)에서는 연습이 램을 하나도 쓰지 않는다.
       화면 아래 띠에는 버튼 안내만(설계 4-사). 정보는 게임 화면 안(상대 선택 머리말·스테이지 칸·연습 메뉴 창·대미지 숫자)에 있다. */

    /** 연습을 켤 수 있는 판인가 — 사무쇼2 일반 세션이고 사본이 실측한 판이거나 그 판에 배경음악만 심은 것 */
    private boolean practiceRomOk() {
        if (!loaded || ranked() || game == null || !"ss2".equals(game.id)) return false;
        String md5 = League.md5(new File(romPath));
        return League.romLabel(md5) != null || Patcher.musicBaseLabel(romPath, md5) != null;
    }

    /** 실행 창 «연습 대전으로 ›» 로 막 연 판 — 그러면 자동 저장 이어하기를 건너뛰고 전원부터 상대 선택까지 대신 누른다
        (안 그러면 사무쇼2 를 한 번이라도 한 사람은 늘 지난 자리로 이어져 링크가 아무 일도 안 했다 — 리뷰). 자동 저장 파일은 그대로 둔다 */
    private boolean practiceLinkFresh() {
        return getIntent().getBooleanExtra(EXTRA_PRACTICE, false) && !practiceAutoUsed && practiceRomOk();
    }

    /** 코어를 올린 뒤(loadCore — UI·GL 스레드 어느 쪽에서든) — resumed = 이어하기 상태를 불러왔나(그러면 자동 진입은 안 한다) */
    private void practiceSetup(boolean resumed) {
        practiceHub = false;
        if (!loaded || ranked() || game == null || !"ss2".equals(game.id)) { hidePracticeBanner(); return; }
        final boolean want = getIntent().getBooleanExtra(EXTRA_PRACTICE, false) && !practiceAutoUsed;
        if (!practiceRomOk()) {
            hidePracticeBanner();
            if (want) toast("연습 대전을 켤 수 없는 롬 판입니다 — 수동 패치·조작 패치가 든 사본");
            return;
        }
        Emu.nativePracticeHub(true, want && !resumed);
        if (want) practiceAutoUsed = true;
        practiceHub = true;
        h.post(new Runnable() { @Override public void run() {
            if (hubBanner == null) { addHubBanner(); hubBanner.setVisibility(View.GONE); }
            h.removeCallbacks(practicePoll); h.postDelayed(practicePoll, 200);
        }});
    }

    /** 연습이 꺼지면 띠도 내린다(코어를 다시 올렸는데 판이 바뀌어 연습이 안 켜진 경우 — 띠가 남아 있지 않게). 리그 띠는 건드리지 않는다 */
    private void hidePracticeBanner() {
        h.post(new Runnable() { @Override public void run() {
            if (hubBanner != null && !leagueHub && leagueId == null) hubBanner.setVisibility(View.GONE);
        }});
    }

    private final Runnable practicePoll = new Runnable() { @Override public void run() {
        if (!practiceHub || leaving || leagueHub || leagueId != null) return;
        if (loaded && hubBanner != null) {
            int[] hs = Emu.nativeLeagueHubStatus(), ps = Emu.nativePracticeStatus();
            String t = (hs != null && ps != null) ? practiceText(hs[0], ps) : null;
            /* 경기 중(메뉴 닫힘)엔 띠가 게임 그림 위로 올라가면(가로 화면 — 아래 자리가 없음) 숨긴다: 체력 막대·«2P» 를 연습 내내 가리지 않게 */
            if (t != null && hs[0] == 4 && (ps.length < 2 || ps[1] == 0) && !bannerUserPos     /* 유저가 옮겨 둔 자리면 그대로 보인다 */
                    && ((FrameLayout.LayoutParams) hubBanner.getLayoutParams()).topMargin < gameBox[3]) t = null;
            if (t == null) hubBanner.setVisibility(View.GONE);
            else { if (!t.contentEquals(hubBanner.getText())) hubBanner.setText(t); hubBanner.setVisibility(View.VISIBLE); }
        }
        h.postDelayed(this, 150);
    }};

    /** 띠 안내 — 버튼만(허브 상태 2 상대 선택 · 3 내 캐릭터 · 4 경기). 타이틀·리셋 뒤 돌아가는 중엔 띠를 숨긴다 */
    private static String practiceText(int hubState, int[] ps) {
        switch (hubState) {
        case 2: return "A 수라  ·  B 나찰  ·  옵션 스테이지";
        case 3: return "A 결정  ·  B 2P색으로 결정  ·  옵션 컬러";
        case 4:
            if (ps.length > 1 && ps[1] != 0) return "↑↓ 고르기  ·  ←→ 바꾸기  ·  A 실행  ·  B 닫기";
            return "옵션 연습 메뉴  ·  옵션+↓ 리셋  ·  옵션+←↑→ 위치";
        default: return null;
        }
    }
}
