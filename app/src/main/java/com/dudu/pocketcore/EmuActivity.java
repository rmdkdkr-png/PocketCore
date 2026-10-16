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
    /* 리그·연습 «둘 다»(실행 창 «리그·연습», pocketcore_ss2_hub) — 타이틀 셋째 줄 ◀▶ 로 연습 대전 ↔ 리그 대전. 리그는 따로 세이브·고정 롬·동봉 코어(랭크전 판)라
       고르면 이 창을 내리고 그 판으로 새로 연다(switchSession). 리그 판으로 갈아탄 창은 EXTRA_HUB_GO 로 로고·메뉴를 넘겨 바로 상대 선택까지 */
    static final String EXTRA_HUB_GO = "hub_go", EXTRA_FROM_GAME = "hub_from_game";   /* FROM_GAME = 일반 판에서 갈아타 왔다(리그를 못 열면 그 게임으로 돌려보낸다) */
    private boolean practiceDual = false, hubDual = false, hubGoUsed = false, dualWarned = false;
    private int[] hubDualV;
    private boolean dualHintShown = false;
    /** 랭크전 판으로 돌고 있나 — 한 경기(leagueId) 또는 게임 속 리그 세션 전체. 롬 사본·동봉 코어·리그 세이브 폴더·저장/불러오기 막기 */
    private boolean ranked() { return leagueId != null || leagueHub; }
    private String leagueId = null;            /* 랭크전이면 경기 id — 기록은 늘 이 id 로 League 에서 다시 찾는다 */
    private boolean leagueArmed = false, leagueDone = false;
    private Ask leagueDlg;                      /* 나가기 카드 — 떠 있는 동안 게임은 멈춘다(창이 아니라 덮개 카드 — Ask) */
    private int leagueRetry = 0;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        /* 볼륨 키 = 미디어(게임 소리) 음량 — 안 정하면 삼성은 «재생 중»을 못 알아챌 때 벨소리 음량을 바꾼다(유저 2026-10-10 「볼륨 조절이 앱에서 안 되던데」) */
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        try { League.appVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception ignored) { }   /* 무효 «앱이 바뀜»(voidKind)·보관 파일 판 — BuildConfig 가 없어 여기서 */
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
        applyFrameGen();
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
                if (rankView != null) rankView.setVisibility(open ? View.INVISIBLE : View.VISIBLE);   /* 148: 찾기는 그만두지 않고 멈춤(rankTick) — 대기창은 바 윗줄을 덮으니 바가 열린 동안 숨긴다 */
                if (fdView != null && fdWanted) fdView.setVisibility(open ? View.GONE : View.VISIBLE);   /* 프레임 미터가 버튼 아래 줄에 서면 메뉴 칸(저장·로드) 위를 덮는다 — 메뉴 동안 숨김 */
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
        if (ask != null) ask.place();
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
        placeFrameMeter();                                  /* 프레임 미터가 먼저 자리를 잡고 안내 띠는 그 아래로 */
        placeHubBanner();
        if (rankView != null) { h.removeCallbacks(rankPlace); h.post(rankPlace); }   /* 148 대기창·상대 카드 — 레이아웃이 끝난 뒤에 */
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

    /** 받아 둔 코어(files/cores/<id>.ver) 판 표식 — 없으면 null */
    static String readVer(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[64];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8").trim() : null;
        } catch (Exception e) { return null; }
    }

    /** 150: 동봉 코어가 받아 둔 코어보다 새것인가. 받은 코어가 늘 동봉보다 새것이라는 가정이 처음 깨진 판 —
     *  동봉 SVC 코어 = SP 4.06 + 앱 방 패치(151: 흑백 창 고침. 150 엔 코어 보간도 있었다)인데 색인의 svc 는 아직 그냥 4.06 이라,
     *  받아 둔 기기는 새 앱을 깔아도 옛 코어로 돌았을 것. 숫자만인 판(예 "4.06", 시험 레벨 "-test" 붙음)이 4.06 이하면 동봉을 쓴다.
     *  색인에 더 새 판(4.07…, 또는 "-fg" 처럼 표식 붙은 판)이 올라오면 그건 다시 받은 코어가 이긴다. */
    static boolean bundledIsNewer(String id, String ver) {
        if (!"svc".equals(id) || ver == null) return false;
        String v = ver.endsWith("-test") ? ver.substring(0, ver.length() - 5) : ver;
        if (!v.matches("\\d+(\\.\\d+)?")) return false;
        try { return Double.parseDouble(v) <= 4.06 + 1e-9; } catch (NumberFormatException e) { return false; }
    }

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
            coreLabel = game.ko + " · 동봉 코어(리그)" + suffix;
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
            String v = readVer(new File(dir, want + ".ver"));
            if (!bundledIsNewer(want, v)) {
                coreLabel = ((game != null) ? game.ko : "순정 NGPC")
                          + ((v != null) ? " · 코어 " + v : "") + suffix;
                return auto.getAbsolutePath();
            }
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
        leagueCellSetup();
        lookSetup();
        cheatSetup();
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
            toast(leagueId != null ? "리그 경기 중엔 저장·불러오기·리셋·설정을 못 써요 — 그만두려면 뒤로"
                    : hubDual ? "리그 판에선 저장·불러오기·리셋·설정이 막혀요 — 셋째 줄 «연습 대전»이나 뒤로로 나가면 돼요"
                    : "리그 판에선 저장·불러오기·리셋·설정이 막혀요 — 뒤로로 나가면 돼요");   /* 저장도 막는다: 슬롯 파일 이름이 자유 플레이와 같다 */
            return;
        }
        if (ranked() && (action == PadView.ACT_PICK || action == PadView.ACT_QUIT)) {
            /* 메뉴 바가 닫히며 멈춤을 푸는(menuChanged) 것보다 뒤에 창을 띄운다 */
            /* 148: 게임 속 리그에서 상대만 잡히고 첫 라운드 전(준비 기록)이면 고르기 화면 창으로 — 계속 / 타이틀로 / 내 게임으로 / 순위표 */
            h.post(new Runnable() { @Override public void run() { if (leagueId != null && !hubPickBack()) leagueLeave(); else hubLeave(); } });
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
            sheet.cheatRom = new File(orig);                   /* 치트 «세이브에 남기기» — 세이브 보관 이름 */
            if (game != null && Cheats.GAME.equals(game.id) && !cheatOk) sheet.cheatOff = ranked() ? "리그 대전에선 치트가 꺼져요" : "이 롬 판에선 치트가 안 켜져요";
            sheet.onClose = new Runnable() { @Override public void run() {
                Emu.nativeSetPaused(pad.isBarOpen());          /* 메뉴가 아직 열려 있으면 계속 멈춤 */
                applyDisplay(); pushCoreOpts();
                if (sheet != null && sheet.cheatsChanged() && pushCheats()) toast("치트 적용"); } };   /* 치트 창에서 바꾼 값 — 다시 열지 않고 바로 */
            break; }
        case PadView.ACT_PICK:
            goList();
            break;
        case PadView.ACT_LOOK:                             /* 멈춤 메뉴 «컬러 만들기» — 바가 닫히며 멈춤이 풀린 뒤에 창을 연다(미리보기는 게임이 돌아야 그려진다) */
            h.post(new Runnable() { @Override public void run() { int[] v = lookV; if (v != null && v.length >= 14 && v[1] == 1) openLookEditor(v[12], v[13]); } });
            break;
        case PadView.ACT_LEAGUE:                           /* 148 멈춤 바 «리그 대전» — 바가 닫히며 멈춤을 푼 뒤에(확인 카드가 그 뒤에 떠야 멈춤을 쥔다) */
            h.post(new Runnable() { @Override public void run() { leagueFromGame(); } });
            break;
        case PadView.ACT_QUIT:
            /* 앱 종료 — 오토세이브는 onPause 가, 코어 내리기는 onDestroy 가 챙긴다. 다음에 켜면 목록부터. */
            MainActivity.forgetLast(this);
            finishAffinity();
            break;
        }
    }

    /* ── 프레임 패치(옛 «코어 보간») — 사무쇼2 코어만 ─────────────────────────
       코어가 다음 2프레임을 미리 돌려 보고 몸·배경 자리는 ¼픽셀로, 서기·동작은 사이 그림(캐시)으로, 이펙트는 옮겨서 4배로 다시 그린다.
       설정은 켬/끔 하나(Settings MOTION — 켜면 늘 최선 조합, ngp_framegen=auto). 120Hz 화면이면 코어가 120.5fps 로 내보낸다 —
       삼성은 정적 화면을 60Hz 로 내리므로 켜져 있을 때는 창·표면에 최고 주사율을 요청한다.
       151: 앱 보간(완성된 화면에서 움직임을 짐작해 사이 그림 — framegen.c·fg_cadence.c) 폐기(유저 「앱보간 폐기해라」),
       150 의 SVC 코어(다른 게임) 프레임 패치도 뺐다(「ss2만 되는걸로 해」 — 자리만 옮겨 체감이 없었다) */
    /** 이 게임은 프레임 패치가 있는가 — 사무쇼2 코어만(151) */
    private boolean coreFg() { return "ss2".equals(romType); }
    private boolean coreFgOn() { return coreFg() && !"disabled".equals(readOpt("ngp_framegen", "auto")); }

    /** 프레임 패치 상태를 화면 주사율 요청에 맞춘다(게임 시작·설정에서 돌아올 때) */
    private void applyFrameGen() {
        if (pad != null) pad.setCoreFg(coreFg(), coreFgOn() ? "프레임 패치:켬" : "프레임 패치:끔");
        applyFrameRate();
    }

    /** 120Hz 를 요청할 이유 — 프레임 패치가 켜졌을 때. 삼성은 요청이 없으면 60 으로 내린다. */
    private boolean wantHighRefresh() { return coreFgOn(); }

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
        liveHubOff();
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
                if (!startOrStay(i2)) return;
                finish();
            }});
        }});
    }
    /** 화면 열기 — 못 열면(148 G19) 나가는 중 표시를 풀고 알린다: 다시 누르면 또 시도(코어는 이미 내려 있어 그냥 연다) */
    private boolean startOrStay(Intent i) {
        try { startActivity(i); return true; }
        catch (Exception e) {
            android.util.Log.w("PocketCore", "startActivity: " + e);
            leaving = false;
            toast("화면을 열지 못했어요 — 다시 눌러 주세요");
            return false;
        }
    }
    private boolean leaving = false;   /* 목록으로 나가는 중 — 뒤로가기 연타로 두 번 내리지 않게 */

    /** 뒤로가기 = 목록으로 — 예전엔 앱이 그냥 닫혀서 「게임을 닫으면 테마가 다시
     *  나와야 한다」는 흐름 자체가 없었다(제보). */
    @Override public void onBackPressed() {
        if (ask != null) { ask.cancel(); return; }            /* 확인 카드 — 뒤로 = 취소(취소 못 하는 카드면 그대로) */
        if (lookEditor != null) { lookEditorClose(); return; }   /* 컬러 만들기 창에서 뒤로 = 취소 */
        if (hubCard != null) { hubReturn(-1); return; }      /* 결과 카드에서 뒤로 = 계속 */
        if (rankView != null && !(pad != null && pad.isBarOpen())) { rankBack(); return; }   /* 찾는 중·상대 카드 — 찾기만 그만(카드였으면 상대는 잡힌 채). 바가 열려 있으면 멈춘 채 아래(hubLeave)로 */
        if (hubPickBack()) { hubReturn(-1); return; }         /* 리그 판 고르기 화면 — 확인 창 없이 바로 타이틀로(유저 「타이틀로 가는 거 메시지 없이」) */
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
            ask(null, "이 장면을 런처 썸네일로 지정할까요?")
                .btn("지정", new Runnable() {
                    @Override public void run() {
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
                .btn("아니오", null)
                .show();
        }});
    }

    /* ---- physical gamepad ---- */
    /* 매핑표(KeyMap, 설정 pocketcore_keymap)로 푼다 — 예전 고정 switch 는 KeyMap 의 기본값으로 옮겼다. */
    private int mapKey(int code) { return keymap != null ? keymap.bitOf(code) : 0; }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (KeyMap.isVolume(e.getKeyCode())) return super.dispatchKeyEvent(e);   /* 음량은 늘 시스템으로 — 창이 떠 있어도 */
        if (ask != null) { ask.handleKey(e); return true; }   /* 확인 카드가 떠 있으면 패드·키는 카드만 — 게임엔 안 간다 */
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
        if (ask != null) {                     /* 확인 카드 — 스틱·HAT 십자로 단추 고르기, 게임·바엔 안 간다 */
            int rise = all & ~axisPrev;
            if ((rise & ((1 << Emu.UP) | (1 << Emu.LEFT))) != 0)    ask.nav(-1);
            else if ((rise & ((1 << Emu.DOWN) | (1 << Emu.RIGHT))) != 0) ask.nav(+1);
            axisPrev = all; axisMask = 0;
            return true;
        }
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
        if (rankView != null) {                          /* 앱이 내려가면 찾기 그만 — 카드였으면 상대는 잡힌 채(카드에서 holdPending). 알림은 돌아왔을 때 */
            if (rankFound != null || rankLanded) { rankGoAt = 0; Emu.nativeHubSearch(false); rankClose(); rankStopNote = 2; }   /* 상대가 보였으면 잡힌 채 */
            else { rankCancel(null); rankStopNote = 1; }
        }
        releasePhysical();
        try { ((android.hardware.input.InputManager) getSystemService(INPUT_SERVICE))
                .unregisterInputDeviceListener(devListener); } catch (Exception ignored) { }
        if (loaded && lookOn) lookSaveMem();     /* 컬러 기억 — 폴(200ms)이 아직 못 남긴 마지막 바뀜도(다시 굽기·갈아타기로 창이 닫히기 전에) */
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
        if (rankStopNote != 0) {
            toast(rankStopNote == 1 ? "앱이 내려가서 찾기를 멈췄어요 — A로 다시 찾기" : "상대가 기다려요 — 다음에 또 만나요");
            rankStopNote = 0;
        }
        if (loaded) Emu.nativeAudioResume();
        keymap = KeyMap.load();   /* 매핑 화면에서 돌아온 경우 */
        pushCoreOpts();           /* 설정에서 코어 옵션(프레임 생성·런어헤드·띠)을 바꾸고 돌아온 경우 — 게임 중에 바로 */
        applyFrameGen();     /* 설정에서 프레임 패치를 바꾸고 돌아온 경우 */
        applyDisplay();           /* 업스케일러·필터 */
        applyScreenLayout();      /* 설정에서 돌아온 경우 바로 반영 (터치 패드 모드 포함) */
        try { ((android.hardware.input.InputManager) getSystemService(INPUT_SERVICE))
                .registerInputDeviceListener(devListener, h); } catch (Exception ignored) { }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        liveHubOff();
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
        leagueTag = new TextView(this);
        leagueTag.setText((League.isRanked(m) ? "랭크 경기 · " : "리그 경기 · ") + League.oppLabel(m));
        leagueTag.setTextColor(0xffd9a441);
        leagueTag.setTextSize(11);
        float dp = getResources().getDisplayMetrics().density;
        leagueTag.setPadding(Math.round(8 * dp), Math.round(2 * dp), 0, 0);
        root.addView(leagueTag, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.LEFT));
    }

    /** 자동 정산 가능한 판인가 — 롬 사본 md5 와 코어(앱 동봉)를 고정 목록과 맞추고, 재개면 시작 때 기록한 판과도 맞춘다. 아니면 이유. */
    private String leagueVersionProblem(org.json.JSONObject m) {
        leagueRomNow = null;
        String md5 = League.md5(new File(romPath));
        String label = League.romLabel(md5);
        if (label == null) return "판정 주소를 확인하지 않은 롬입니다 (md5 " + md5 + ")";
        String core = corePathCache;
        if (core == null || !core.startsWith(getApplicationInfo().nativeLibraryDir))
            return "앱 동봉 코어가 아닌 코어로 실행 중입니다 (" + core + ")";
        String was = m.optString("rom_md5", "");
        if (!was.isEmpty() && !was.equals(md5)) {
            leagueRomNow = label;                              /* 롬만 바뀜(같은 판정 목록 안) — 카드 B «원래 롬으로 되돌리면 …» */
            return "경기를 시작한 롬과 지금 롬이 다릅니다 (" + m.optString("rom") + " → " + label + ")";
        }
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
        if (m == null) { leagueVanished(); return; }
        String st = m.optString("status");
        if (League.terminal(st)) { leagueDone = true; leagueExit(leagueId); return; }
        boolean fresh = "ready".equals(st);
        leagueRomNow = null;
        String why = !loaded ? "코어/롬 로드 실패" : leagueVersionProblem(m);
        if (why != null) {
            if (fresh) leagueHoldExit("자동 정산 중지 — " + why,
                    "앱 동봉 코어와 지원 롬(순정 ss2.ngc·ss2_h1.ngc + 한글패치 v1.01/동봉판, 또는 순정)으로 되돌린 뒤 새로 도전하세요.");
            else {
                /* 148: 실패 종류를 먼저 적는다(voidKind 가 한글 사유 대신 본다) — 체크포인트까지 없으면 카드 A, 경기를 시작한 앱 판이 다르면 B′ */
                League.setResumeFail(leagueId, "rom");
                League.setStatus(leagueId, "resume", why);
                League.note(leagueId, "fix", "경기를 시작한 판(롬 " + m.optString("rom", "?") + " · 앱 동봉 코어)으로 되돌리면 재개할 수 있습니다. 아니면 기권만 할 수 있습니다.");
                leagueBlocked(BLK_ROM, why);
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
                League.setResumeFail(leagueId, had ? "badckpt" : "nockpt");
                League.setStatus(leagueId, "resume", had ? "체크포인트를 불러오지 못함" : "체크포인트 없음");
                leagueBlocked(had ? BLK_BADCKPT : BLK_NOCKPT, null);
                return;
            }
            ck.delete();                                      /* 한 번 쓰면 끝 */
        }
        org.json.JSONObject rule = m.optJSONObject("rule");
        {   /* 배경·컬러 — 경기에 적힌 값(이어 하기), 없으면 새 판만 고른 배경(게임 속 리그 OPTION 과 같은 설정)으로 정해 적는다.
               컬러는 새 판이면 −1 — 고르기 화면에서 정한다(look, 캐릭터마다 기억 · 기록은 lookTick 이 고친다).
               적힌 값 없이 체크포인트에서 이어 하는 경기(이 기능 전에 시작한 경기)는 게임 그대로 — 판 중간에 배경·색이 바뀌지 않게 */
            org.json.JSONObject lk = m.optJSONObject("look");
            int stg = lk != null ? lk.optInt("stage", -1) : fromCk ? -1 : League.resolveStage(League.prefStage(), m.optString("opp"));
            int col = lk != null ? lk.optInt("color", 0) : -1;
            if (lk == null) League.setLook(leagueId, stg, 0);
            Emu.nativeLeagueLook(stg, col);
        }
        if (League.isRanked(m) && rule.optJSONObject("cpu") != null) {   /* 랭크 경기 이어 하기 — 경기에 적힌 CPU 손잡이 그대로(다음 무장 한 번만) */
            org.json.JSONObject k = rule.optJSONObject("cpu");
            Emu.nativeLeagueCpu(k.optInt("lv", -1), k.optInt("cap", -1), k.optInt("atk", -1), k.optInt("dfn", -1), k.optInt("floor", -1));
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
        toast(fromCk ? "리그 경기 — 멈춘 자리부터 이어 해요" : "리그 경기 — 내 캐릭터·검질·카드를 고르세요");
        h.postDelayed(leaguePoll, 250);
    }

    private boolean leagueStarted = false;      /* 첫 라운드가 실제로 시작됐나(league.c 경기 시작 규칙) */
    private String leagueRomNow;                /* leagueVersionProblem 이 «롬만 바뀜»으로 막았을 때 지금 롬 이름(카드 B) — 아니면 null */

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
        new File(tmp.getPath() + ".look").delete();          /* 경기 컬러는 경기 기록(look)이 정한다(leagueStart → nativeLeagueLook) — 옆 파일을 남기지 않는다 */
        if (!tmp.renameTo(ck)) { tmp.delete(); return false; }
        return true;
    }

    /** 결판 감시 — 진짜 프레임에서 league.c 가 잡은 결과를 가져와 한 번만 정산한다. 화면에 떠 있을 때만 리그 화면으로 넘어간다. */
    private final Runnable leaguePoll = new Runnable() { @Override public void run() {
        if (leagueId == null) return;
        if (leagueDone) { leagueSaveAskClose(); if (resumedNow && !leaving) leagueExit(leagueId); return; }
        if (!loaded || leaving) return;
        if (lm() == null) { leagueVanished(); return; }    /* 경기 기록이 사라졌다(다시 읽기 등 — 있을 수 없는 일) */
        leagueSyncStarted();
        if (nativeDone()) {
            if (leagueDlg != null && leagueDlg.isShowing()) leagueDlg.dismiss();
            if (!leagueSettleNow() || !leagueDone) {           /* 저장 실패 — 결과는 코어 쪽에 그대로 있으니 다시(1초마다 — 카드 D 가 떠 있어도) */
                ++leagueRetry;
                if (leagueRetry >= 3) { if (leagueSaveAsk == null) leagueSaveShow(); }   /* 3번(≈3초) — 끝없는 알림 대신 그 자리에서 푸는 카드 */
                else if (leagueRetry == 1) toast("기록 저장 실패 — 다시 시도 중");
                h.postDelayed(this, 1000);
                return;
            }
            leagueSaveAskClose();                              /* 다시 시도가 됐다 — 카드는 저절로 닫고 이어서 */
            if (resumedNow) leagueExit(leagueId);
            return;
        }
        h.postDelayed(this, 250);
    }};

    /** 카드 D — 결과 저장이 3번 실패. leagueDlg 와 따로 둔다(poll 이 결판 때마다 leagueDlg 를 닫는다) */
    private Ask leagueSaveAsk;
    private void leagueSaveShow() {
        if (leaving || leagueDone) return;
        final Ask[] me = new Ask[1];
        me[0] = ask("결과를 저장하지 못했어요", "결과는 이 판에 그대로 있어요.\n저장소를 확인한 뒤 다시 시도해 주세요.")
                .cancelable(false)
                .btn("다시 시도", new Runnable() { @Override public void run() { h.removeCallbacks(leaguePoll); h.post(leaguePoll); } })
                .btn("권한 설정 열기", new Runnable() { @Override public void run() { StorageAccess.open(EmuActivity.this); } })   /* 돌아오면 poll 이 카드를 다시 띄운다 */
                .btn("나중에 정산하기", new Runnable() { @Override public void run() { leagueSaveLater(); } })
                .onDismiss(new Runnable() { @Override public void run() { if (leagueSaveAsk == me[0]) leagueSaveAsk = null; } });
        leagueSaveAsk = me[0];
        me[0].show();
    }
    private void leagueSaveAskClose() { Ask a = leagueSaveAsk; leagueSaveAsk = null; if (a != null) a.dismiss(); }
    /** «나중에 정산하기» — 결판 프레임의 체크포인트를 남기고 목록으로. 다음에 리그를 열면 그 자리에서 다시 잡혀 정산된다(FROM_CKPT) */
    private void leagueSaveLater() {
        if (leaving || leagueDone || leagueId == null) return;
        gl.queueEvent(new Runnable() { @Override public void run() {
            final boolean ok = loaded && leagueSaveCkpt();
            h.post(new Runnable() { @Override public void run() {
                if (leagueDone || leaving) return;            /* 그 사이 정산됐다 — poll 이 결과로 */
                if (!ok) { toast("이 판도 저장하지 못했어요 — 권한을 먼저 확인해 주세요"); if (leagueSaveAsk == null) leagueSaveShow(); return; }
                h.removeCallbacks(leaguePoll);
                toast("다음에 리그를 열면 정산해요");
                goList();
            }});
        }});
    }

    /** 경기 기록이 사라졌다(방어) — 리그 화면으로만 */
    private boolean leagueVanishedShown = false;
    private void leagueVanished() {
        if (leagueVanishedShown || leaving) return;
        leagueVanishedShown = true;
        leagueDone = true; leagueArmed = false;               /* 복귀(onResume)·뒤로가 없는 경기로 결과를 찾지 않게 */
        h.post(new Runnable() { @Override public void run() {
            Emu.nativeSetPaused(true);
            ask("리그 기록이 바뀌었어요", null)
                    .cancelable(false)
                    .btn("리그 화면으로", new Runnable() { @Override public void run() { leagueExit(null); } })
                    .show();
        }});
    }

    /** 아직 대진 전(준비)에 롬·코어가 안 맞으면 — 보류(점수 없음), 원인과 복구 방법을 남기고 결과 창으로. */
    private void leagueHoldExit(final String why, String fix) {
        League.hold(leagueId, why, fix);
        leagueDone = true;
        h.post(new Runnable() { @Override public void run() {
            ask("가상 리그", why + "\n\n이 경기는 점수에 넣지 않습니다(보류).")
                    .cancelable(false)
                    .btn("확인", new Runnable() { @Override public void run() { leagueExit(leagueId); } })
                    .show();
        }});
    }

    /** 시작한 경기를 지금 이어갈 수 없을 때 — 무장하지 않고 상태를 그대로 둔 채 리그 화면으로(재개 또는 기권). 148: 판·코어·로드 실패처럼 앱 안에서 못 푸는 것만 이 글 */
    private void leagueBlocked(final String msg) {
        leagueDone = true;
        h.post(new Runnable() { @Override public void run() {
            Ask d0 = ask(leagueId != null ? "멈춘 리그 경기를 이어할 수 없어요" : "리그 대전을 열 수 없어요", msg)
                    .cancelable(false)
                    .btn("리그 화면으로", new Runnable() { @Override public void run() { leagueExit(null); } });
            if (fromGame())                                  /* 일반 판에서 갈아타 왔다 — 그 게임으로도 */
                d0.btn("게임으로", new Runnable() { @Override public void run() { switchSession(false, false); } });
            d0.show();
        }});
    }
    private boolean fromGame() { return leagueHub && getIntent().getBooleanExtra(EXTRA_FROM_GAME, false); }

    /* ── 막힘 카드(148 설계 3.5) — 카드마다 그 자리에서 앞으로 가는 단추. 모두 취소 못 하는 확인 카드(첫 키는 고르기만 — 패드 실수 막기),
       기권은 한 번 더 묻는다(터치 실수 막기) ── */
    private static final int BLK_NOCKPT = 1, BLK_BADCKPT = 2, BLK_ROM = 3, BLK_DB = 4, BLK_WRITE = 5;

    /** kind: 경기(NOCKPT·BADCKPT·ROM — 카드 A·B·B′, 무효 가능 여부는 League.voidKind 가 정한다) · 저장소(DB·WRITE — 카드 C). why = 막힌 사유(ROM·저장소) */
    private void leagueBlocked(final int kind, final String why) {
        if (kind == BLK_DB || kind == BLK_WRITE) { storageBlocked(kind); return; }
        final org.json.JSONObject m = lm();
        if (m == null) { leagueVanished(); return; }
        leagueDone = true;
        String vk = League.voidKind(m, League.appVer);
        if (vk == null && kind != BLK_ROM) vk = kind == BLK_BADCKPT ? "ckpt" : "crash";   /* 기록이 따라오지 못했어도(저장 실패) 이 카드의 종류로 */
        final String voidKind = vk;
        final boolean hub = leagueHub, single = !leagueHub;
        String title, body;
        org.json.JSONObject snap = m.optJSONObject("opp_snap");
        String vs = "vs " + League.oppNick(m) + (snap != null && !snap.optString("label").isEmpty() ? " · " + snap.optString("label") : "");
        if ("crash".equals(vk) || "ckpt".equals(vk)) {                              /* A — 앱이 멈춤 · 자리를 못 불러옴 */
            title = "멈춘 경기를 이어할 수 없어요";
            body = vs + "\n" + ("ckpt".equals(vk) ? "이어할 자리를 불러오지 못했어요." : "앱이 멈춰서 이어할 자리가 없어요.") + "\n무효로 닫으면 LP·전적은 그대로예요.";
        } else if ("app".equals(vk)) {                                              /* B′ — 앱 판이 바뀜 */
            title = "앱이 바뀌어 이어할 수 없어요";
            body = "앱을 업데이트해서 이 경기를 이어할 수 없어요.\n무효로 닫으면 LP·전적은 그대로예요.";
        } else if (leagueRomNow != null) {                                          /* B — 롬만 바뀜(같은 앱) */
            title = "다른 롬으로 바뀌었어요";
            body = "경기를 시작한 롬: " + m.optString("rom", "?") + "\n지금 롬: " + leagueRomNow + "\n원래 롬으로 되돌리면 이어할 수 있어요.";
        } else {                                                                    /* 미확인 롬·코어·로드 실패 — 앱 안에서 못 푼다(147 글 그대로) */
            leagueBlocked(why + "\n\n경기는 그대로 남겨 둡니다. 원래 판으로 되돌리면 이어할 수 있고, 아니면 기권만 할 수 있습니다.");
            return;
        }
        final String t = title, b = body;
        h.post(new Runnable() { @Override public void run() {
            if (leaving) return;
            Ask d = ask(t, b).cancelable(false);
            if (voidKind != null)
                d.btn(single ? "무효로 닫기" : "무효로 닫고 계속", new Runnable() { @Override public void run() { leagueResolve(true, kind, why); } });
            d.btn(single ? "기권" : "기권하고 계속", new Runnable() { @Override public void run() { leagueResolve(false, kind, why); } });
            d.btn("리그 화면으로", new Runnable() { @Override public void run() { leagueExit(null); } });
            if (hub && fromGame()) d.btn("게임으로", new Runnable() { @Override public void run() { switchSession(false, false); } });
            d.show();
        }});
    }

    /** 막힘 카드 A·B·B′ 의 무효·기권. 게임 속 리그: 무효 → 바로 다시 찾기(같은 상대가 1.2~2.4초에) · 기권 → 결과 카드([다음 상대]). 한 경기 판: 둘 다 리그 화면으로 */
    private void leagueResolve(boolean voidIt, final int kind, final String why) {
        final String id = leagueId;
        final org.json.JSONObject m = lm();
        if (id == null || m == null) { leagueVanished(); return; }
        if (voidIt) {
            String vk = League.voidKind(m, League.appVer);
            if (vk == null) vk = kind == BLK_BADCKPT ? "ckpt" : "crash";
            if (!League.voidStarted(id, vk)) { toast("저장 실패 — 다시 시도하세요"); leagueBlocked(kind, why); return; }
            if (leagueHub) {
                toast("무효로 닫았어요 — LP 그대로 · 바로 상대를 찾아요");
                rankOppV = null;
                hubReturn(-3);                                      /* 리셋 뒤 바로 상대 찾기 — 잡힌 상대면 그 상대가 다시 */
            } else { toast("무효로 닫았어요 — LP 그대로"); leagueExit(null); }
            return;
        }
        askForfeit(m, new Runnable() { @Override public void run() {
            if (League.forfeit(id) == null) { toast("저장 실패 — 다시 시도하세요"); leagueBlocked(kind, why); return; }
            leagueExit(id);                                         /* 게임 속 리그면 결과 카드(다음 상대) */
        } }, new Runnable() { @Override public void run() { leagueBlocked(kind, why); } });
    }

    /** 기권 확인 — «기권할까요?» (LP -N · 배치면 «배치 1패»). 취소·뒤로면 back(막힘 카드를 다시) */
    private void askForfeit(org.json.JSONObject m, final Runnable yes, final Runnable back) {
        String body = "이 경기는 기권패로 기록돼요.";
        if (League.isRanked(m)) {
            org.json.JSONObject s0 = m.optJSONObject("start");
            RankCore.Me me = League.rankMe();
            if ((s0 != null ? s0.optInt("placement_left", me.placementLeft) : me.placementLeft) > 0) body = "배치 1패로 기록돼요.";
            else {
                int[] st = RankCore.stakes(me, s0 != null ? s0.optDouble("opp_mmr", me.mmr) : me.mmr);
                if (st != null && st[1] != 0) body = "이 경기는 기권패로 기록돼요 (LP " + RankUi.signed(st[1]) + ").";   /* 수련생은 안 깎인다 — ±0 은 빼고(리그 화면과 같게) */
            }
        }
        final boolean[] went = { false };
        ask("기권할까요?", body)
                .btn("기권", new Runnable() { @Override public void run() { went[0] = true; yes.run(); } })
                .btn("취소", null)
                .onDismiss(new Runnable() { @Override public void run() { if (!went[0] && back != null && !leaving) back.run(); } })
                .show();
    }

    /** «다시 확인» 뒤 hubStart — 이번엔 게임 프레임이 돌고 있다. 멈춘 채로 불러 멈춘 경기 이어 하기(체크포인트 → 배경·CPU → 무장)가
     *  프레임 사이에 갈라지지 않게 하고, 끝나면 멈춤을 푼다(결과 카드·나가는 중이면 그쪽이 쥔다) */
    private void hubStartAgain() {
        if (leaving) return;
        Emu.nativeSetPaused(true);
        hubStart();
        if (!leaving && hubCard == null) Emu.nativeSetPaused(pad.isBarOpen());   /* hubReturn 과 같게 */
    }

    /** 카드 C — 리그 기록을 못 읽음·못 씀. «다시 확인» = 다시 읽고 풀렸으면 hubStart 다시(막힌 hubStart 는 native 를 부르기 전에 돌아왔다) */
    private void storageBlocked(final int kind) {
        h.post(new Runnable() { @Override public void run() {
            if (leaving) return;
            Ask d = ask(kind == BLK_DB ? "리그 기록 파일을 못 읽어요" : "리그 기록 폴더에 쓸 수 없어요",
                    "저장소 권한을 확인한 뒤 «다시 확인»을 눌러 주세요.\n(" + League.dir().getAbsolutePath() + ")")
                    .cancelable(false)
                    .btn("다시 확인", new Runnable() { @Override public void run() {
                        String p = League.reload();
                        if (p == null) { leagueDone = false; h.post(new Runnable() { @Override public void run() { hubStartAgain(); } }); return; }
                        toast("아직 안 돼요 — " + p);
                        storageBlocked(League.dbProblem != null ? BLK_DB : BLK_WRITE);
                    } })
                    .btn("권한 설정 열기", new Runnable() { @Override public void run() {
                        StorageAccess.open(EmuActivity.this);
                        storageBlocked(kind);                              /* 돌아와서 «다시 확인» */
                    } });
            if (fromGame()) d.btn("게임으로", new Runnable() { @Override public void run() { switchSession(false, false); } });
            else d.btn("리그 화면으로", new Runnable() { @Override public void run() { leagueExit(null); } });
            d.show();
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
        leagueDlg = ask("리그 경기 중", "나중에 이어하면 지금 자리에서 한 번만 다시 열 수 있습니다. 기권하면 패배로 기록됩니다.")
                .btn("계속하기", null)
                .btn("나중에 이어하기", new Runnable() {
                    @Override public void run() {
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
                .btn("기권", new Runnable() {
                    @Override public void run() {
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
                .onDismiss(new Runnable() {
                    @Override public void run() {
                        if (!leagueDone && !leagueChoosing && !nativeDone()) Emu.nativeSetPaused(pad.isBarOpen());
                    } })
                .show();
    }

    /** 첫 라운드 전(메뉴·캐릭터 고르기·이야기)에 나가기 — 아직 승부가 아무것도 정해지지 않았으니 무효로 닫는다(점수 없음). */
    private void leagueLeaveBeforeStart() {
        leagueChoosing = false;
        Emu.nativeSetPaused(true);
        leagueDlg = ask("리그 경기 — 시작 전", "아직 첫 라운드가 시작되지 않았습니다. 그만두면 이 경기는 무효로 닫히고 점수는 그대로입니다.")
                .btn("계속하기", null)
                .btn("그만두기(무효)", new Runnable() {
                    @Override public void run() {
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
                .onDismiss(new Runnable() {
                    @Override public void run() {
                        if (!leagueDone && !leagueChoosing && !nativeDone()) Emu.nativeSetPaused(pad.isBarOpen());
                    } })
                .show();
    }

    /** 리그 화면으로 — 결과가 있으면 그 결과 창을 띄운다. 코어는 GL 스레드에서 내린다(goList 와 같은 이유, 내릴 때 native 가 무장도 푼다). */
    private void leagueExit(final String resultId) {
        if (leagueHub && resultId != null) { hubResult(resultId); return; }   /* 게임 속 리그: 결과 카드를 게임 위에 — 나가지 않는다 */
        if (leaving) return;
        leaving = true;
        liveHubOff();
        MainActivity.forgetLast(this);
        gl.queueEvent(new Runnable() { @Override public void run() {
            Emu.nativeLeagueDisarm();
            if (loaded) { Emu.nativeSaveSram(); Emu.nativeUnload(); loaded = false; }
            runOnUiThread(new Runnable() { @Override public void run() {
                Intent i = new Intent(EmuActivity.this, LeagueActivity.class);
                i.putExtra(LeagueActivity.EXTRA_ROM, getIntent().getStringExtra("rom"));
                if (resultId != null) i.putExtra(LeagueActivity.EXTRA_RESULT, resultId);
                if (!startOrStay(i)) return;
                finish();
            }});
        }});
    }

    /* League.liveHub — 이 창이 켰을 때만 끈다(갈아타기: 새 창의 hubStart 가 켠 뒤에 옛 창의 onDestroy 가 오므로 번호로 가린다) */
    private static int liveGen = 0;
    private int myLiveGen = 0;
    private void liveHubOn() { myLiveGen = ++liveGen; League.liveHub = true; }
    private void liveHubOff() { if (myLiveGen != 0 && myLiveGen == liveGen) League.liveHub = false; myLiveGen = 0; }

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
        hubBanner.setText("리그 대전 — 메뉴 셋째 줄");
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
            int fdT = fdBox[3] > 0 ? fdBox[1] - Math.round(4 * dp) : 0, fdB = fdBox[3] > 0 ? fdBox[3] + Math.round(4 * dp) : 0;   /* 프레임 미터 자리는 건너뛴다 — 예전엔 미터 아래부터 찾아, 미터가 버튼 아래 줄에 서면 띠가 갈 곳이 없어 게임 그림 위(HUD)로 올라갔다 */
            int minW = Math.min(maxW, Math.round(W * (port ? 0.50f : 0.36f)));
            left = -1; top = -1; width = maxW;
            for (; y < H; y += step) {
                int hh = bannerHeightAt(maxW);
                if (y + hh > H - Math.round(4 * dp)) break;
                if (fdB > 0 && y < fdB && y + hh > fdT) continue;
                float[] sp = pad != null ? pad.freeSpan(y, y + hh) : new float[] { 0, W };
                int spW = Math.round(sp[1] - sp[0]) - 2 * edge;
                if (spW < minW) continue;
                int w2 = Math.min(maxW, spW), h2 = bannerHeightAt(w2);   /* 좁으면 줄이 늘 수 있다 — 늘어난 높이로 다시 본다 */
                if (h2 > hh) {
                    if (y + h2 > H - Math.round(4 * dp)) break;
                    if (fdB > 0 && y < fdB && y + h2 > fdT) continue;
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

    /* ── 연습 대전 프레임 미터(SF6 식) ─────────────────────────────────
       연습 메뉴 «표시: 프레임 · 둘 다» 일 때 경기 중에만 보인다. 자료는 practice.c 가 진짜 프레임마다 쌓고(런어헤드·프레임 생성의 숨은 프레임은 안 셈),
       여기선 33ms 마다 판 번호가 바뀌었을 때만 다시 그린다(할당 없음).
       자리: 안내 띠와 같은 규칙 — 게임 그림 아래에서 패드 버튼과 안 겹치는 첫 줄(PadView.freeSpan, 칸 3dp 이상이 들어가는 폭) →
       없으면(가로 화면) 게임 그림 아래 끝에 반투명으로 겹친다(HUD 맨 아래 «PAUSE» 줄 · 분노 막대 아래 반). 안내 띠는 미터 아래로 비킨다.
       손가락으로 끌면 그 자리를 기억(세로·가로 따로), 두 번 톡 = 자동 자리, 한 번 톡 = 색 설명 켜고 끄기. */
    private FrameMeterView fdView;
    private final byte[] fdCells = new byte[2 * FrameMeterText.VIEW];
    private final int[] fdSum = new int[FrameMeterText.NSUM];
    private boolean fdWanted = false, fdUserPos = false, fdDragging = false;
    private final int[] fdBox = new int[4];                  /* 미터가 차지한 자리(안내 띠가 피한다) — 숨기면 0 */
    private String fdKey() { return root != null && root.getHeight() > root.getWidth() ? "pocketcore_fdmeter_p" : "pocketcore_fdmeter_l"; }

    private void setFrameMeter(boolean on) {
        if (root == null || (on == fdWanted && (!on || fdView != null))) return;
        fdWanted = on;
        if (on) {
            if (fdView == null) {
                fdView = new FrameMeterView(this, hubFont());
                root.addView(fdView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.LEFT));
                fdView.setOnTouchListener(fdDrag);
            }
            fdView.setVisibility(pad != null && pad.isBarOpen() ? View.GONE : View.VISIBLE);
            placeFrameMeter();
            h.removeCallbacks(fdPoll); h.post(fdPoll);
        } else {
            h.removeCallbacks(fdPoll);
            if (fdView != null) fdView.setVisibility(View.GONE);
            fdBox[0] = fdBox[1] = fdBox[2] = fdBox[3] = 0;
        }
        placeHubBanner();
    }

    private final Runnable fdPoll = new Runnable() { @Override public void run() {
        if (!fdWanted || fdView == null || leaving || !loaded) return;
        int v = Emu.nativePracticeFrames(fdCells, fdSum);
        if (fdSum[FrameMeterText.S_ON] == 0) { setFrameMeter(false); return; }
        fdView.set(fdCells, fdSum, v);
        h.postDelayed(this, 33);
    }};

    private void placeFrameMeter() {
        if (fdView == null || root == null || !fdWanted) return;
        int W = root.getWidth(), H = root.getHeight();
        if (W <= 0 || H <= 0) return;
        float dp = getResources().getDisplayMetrics().density;
        boolean port = H > W;
        int edge = Math.round(8 * dp);
        int minW = Math.round(80 * 3 * dp + 40 * dp);            /* 칸 3dp × 80 + «상대» 이름 칸 */
        int maxW = Math.min(W - 2 * edge, port ? W - 2 * edge : Math.round(W * 0.62f));
        int left = -1, top = -1, width = maxW, hh = fdView.heightFor(maxW);
        boolean overlay = false;
        float[] pos = null;
        String saved = Settings.load().get(fdKey());
        if (saved != null) {
            try { String[] a = saved.split(","); pos = new float[] { Float.parseFloat(a[0]), Float.parseFloat(a[1]) }; } catch (Exception e) { pos = null; }
        }
        fdUserPos = pos != null;
        if (pos != null) {
            left = Math.max(0, Math.min(W - width, Math.round(pos[0] * W - width / 2f)));
            top = Math.max(0, Math.min(H - hh, Math.round(pos[1] * H - hh / 2f)));
        } else {
            for (int y = gameBox[3] + Math.round(4 * dp); y < H; y += Math.round(6 * dp)) {
                if (y + hh > H - Math.round(4 * dp)) break;
                float[] sp = pad != null ? pad.freeSpan(y, y + hh) : new float[] { 0, W };
                int spW = Math.round(sp[1] - sp[0]) - 2 * edge;
                if (spW < minW) continue;
                width = Math.min(maxW, spW); hh = fdView.heightFor(width);
                float[] sp2 = pad != null ? pad.freeSpan(y, y + hh) : sp;          /* 좁아져 칸이 높아졌을 수 있다 — 그 높이로 다시 */
                if (Math.round(sp2[1] - sp2[0]) - 2 * edge < width) { width = maxW; hh = fdView.heightFor(maxW); continue; }
                left = Math.round((sp[0] + sp[1]) / 2f - width / 2f); top = y;
                break;
            }
            if (top < 0) {                                      /* 빈 줄이 없다 — 게임 그림 아래 끝에 겹쳐 */
                width = Math.min(maxW, gameBox[2] - gameBox[0] - Math.round(8 * dp));
                hh = fdView.heightFor(width);
                left = gameBox[0] + ((gameBox[2] - gameBox[0]) - width) / 2;
                top = gameBox[3] - hh - Math.round(2 * dp);
                overlay = true;
            }
        }
        fdView.setOverlay(overlay);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fdView.getLayoutParams();
        lp.width = width; lp.height = hh; lp.leftMargin = left; lp.topMargin = top; lp.rightMargin = 0;
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        fdView.setLayoutParams(lp);
        fdBox[0] = left; fdBox[1] = top; fdBox[2] = left + width; fdBox[3] = overlay || fdUserPos ? 0 : top + hh;   /* 겹친 자리·옮긴 자리는 안내 띠가 안 피함 */
    }

    private final View.OnTouchListener fdDrag = new View.OnTouchListener() {
        float x0, y0; int l0, t0; long lastUp = 0;
        @Override public boolean onTouch(View v, android.view.MotionEvent e) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            float slop = android.view.ViewConfiguration.get(EmuActivity.this).getScaledTouchSlop();
            switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                x0 = e.getRawX(); y0 = e.getRawY(); l0 = lp.leftMargin; t0 = lp.topMargin; fdDragging = false;
                return true;
            case android.view.MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                if (!fdDragging && Math.hypot(dx, dy) < slop) return true;
                fdDragging = true;
                lp.leftMargin = Math.max(0, Math.min(root.getWidth() - v.getWidth(), Math.round(l0 + dx)));
                lp.topMargin = Math.max(0, Math.min(root.getHeight() - v.getHeight(), Math.round(t0 + dy)));
                v.setLayoutParams(lp);
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
                if (fdDragging) {
                    Settings.put(fdKey(), String.format(java.util.Locale.US, "%.4f,%.4f",
                            (lp.leftMargin + v.getWidth() / 2f) / root.getWidth(), (lp.topMargin + v.getHeight() / 2f) / root.getHeight()));
                    fdDragging = false;
                    placeFrameMeter(); placeHubBanner();
                } else {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastUp < 350) { Settings.removePrefix(fdKey()); placeFrameMeter(); placeHubBanner(); toast("프레임 미터를 기본 자리로 돌렸어요"); lastUp = 0; }
                    else { lastUp = now; fdView.toggleLegend(); placeFrameMeter(); }
                }
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                fdDragging = false;
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
        boolean fromGame = getIntent().getBooleanExtra(EXTRA_FROM_GAME, false);
        if (!loaded) { if (fromGame) hubBlockedBack("코어·롬을 불러오지 못했습니다."); else leagueBlocked("코어·롬을 불러오지 못했습니다."); return; }
        String why = hubVersionProblem();
        if (why != null) {                                              /* 판·코어 — 앱 안에서 못 푼다(147 글 그대로) */
            if (fromGame) hubBlockedBack(why);                         /* 일반 판에서 갈아타 왔다 — 원래 게임으로 돌려보낸다(순위표에서 열었으면 순위표로) */
            else leagueBlocked("리그 대전을 열 수 없어요 — " + why);
            return;
        }
        League.db();                                                     /* dbProblem 은 기록 파일을 한 번 읽어야 정해진다 */
        if (League.dbProblem != null) { leagueBlocked(BLK_DB, League.dbProblem); return; }       /* 카드 C — 다시 확인 · 권한 설정 · 게임으로/리그 화면으로 */
        if (League.writeProblem() != null) { leagueBlocked(BLK_WRITE, null); return; }
        liveHubOn();                                                     /* 리그 화면이 이 판의 «준비»를 닫거나 시즌을 바꾸지 않게 */
        League.setLastRom(getIntent().getStringExtra("rom"));            /* 롬 경로 없이 리그 화면에 들어오면 이 롬으로 */
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
        hubHome = home;
        hubStageSel = League.prefStage(); hubColor = 0;                  /* 컬러는 look 이 캐릭터마다 기억(Look) — 허브엔 배경만 */
        Emu.nativeLeagueHubLook(home, hubStageSel, 0);
        hubDual = hubOn() && !"0".equals(Settings.load().get(practiceOkKey()));   /* 셋째 줄 ◀▶ — «연습 대전»을 고르면 일반 판으로(그 판에서 연습이 안 켜진다고 알면 리그만) */
        Emu.nativeHubDual(hubDual);
        hubRanked = true; Emu.nativeHubRanked(true);                     /* 랭크 매치: 셋째 줄 A = 상대 찾기(예전 «상대 선택»은 끔 — 12명은 라이벌로 나온다) */
        org.json.JSONObject open;
        boolean resumeOpen = false;
        while ((open = League.openMatch()) != null && "ready".equals(open.optString("status")))   /* 대진 전 경기는 모두 무효로(같은 폴더의 다른 판이 만든 것까지) — 남은 판: 잡힌 상대 miss 하나(두 번째면 풂) */
            if (!League.cancelStale(open.optString("id"), "게임 속 리그를 새로 열며 정리(대진 전)")) break;
        if (open != null && !"ready".equals(open.optString("status"))) {
            leagueId = open.optString("id"); leagueStart(); resumeOpen = true;    /* 내려간 경기 — 체크포인트로 이어 하고, 결판 뒤 허브로 */
        }
        if (!resumeOpen && !hubGoUsed && getIntent().getBooleanExtra(EXTRA_HUB_GO, false)) { hubGoUsed = true; Emu.nativeHubGo(); }   /* 바로 상대 선택까지 */
        if (hubOn() && !hubDual && !dualHintShown) { dualHintShown = true; toast("이 롬 사본에선 연습이 안 켜져서 셋째 줄은 리그 대전만 돼요 — 조작 패치를 끄면 둘 다"); }
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
        hubDualV = Emu.nativeHubDualStatus();                            /* 둘 다 꺼져 있어도 — 메뉴 화면(v[5])으로 띠를 고른다 */
        if (hubDual && hubDualV != null && hubDualV.length >= 5 && hubDualV[2] != 0) {
            if (hubDualV[2] == 2 && leagueId == null && hubCard == null) { switchSession(false, true); return; }   /* 셋째 줄 «연습 대전» — 일반 판으로 */
            Emu.nativeHubHandoffClear();                                 /* 지금은 못 갈아탄다(남은 경기 기록 등) — 메뉴가 멈춘 채 남지 않게 */
            toast("지금은 연습 대전으로 못 가요 — 뒤로 가기로 나간 뒤 다시");
        }
        if (hs.length > 8) hubGen = hs[8];
        if (hs.length > 11) {                                           /* OPTION(배경)을 바꿨으면 다음에도 그대로(기록 파일 pref) — 컬러는 look 이 캐릭터마다 기억 */
            if (hs[9] != hubStageSel) { hubStageSel = hs[9]; League.setPrefStage(hubStageSel); }
            hubColor = hs[10];                                           /* 고르기 화면에 보이는 컬러(look_cur) */
            hubStage = hs[11];
            if (leagueId != null && !leagueStarted && hubStage >= 0) League.setLook(leagueId, hubStage, hubColor, lookTri(hubColor));   /* 대진 전에 컬러를 바꿔도 기록에 */
        }
        if (hubGen < hubWaitGen) {                                      /* 결과 카드·«타이틀로» 뒤 리셋이 아직 GL 스레드에 안 닿음 — 띠는 숨긴 채(돌아가는 안내 없이) */
            if (hubBanner != null && hubCard == null) hubBanner.setVisibility(View.GONE);
            return;
        }
        /* 상대를 골랐다 → 경기 기록(준비). 뒤로 가기 창이 떠 있으면 창이 정한 뒤에(계속하기 → 다음 확인에서 만듦, 나가기·타이틀로 → 안 만듦) */
        if (hubRanked && st == 3 && leagueId == null && hubCard == null && !(leagueDlg != null && leagueDlg.isShowing())) {
            if (!rankNewMatch()) return;
        } else if (st == 3 && leagueId == null && hubCard == null && !(leagueDlg != null && leagueDlg.isShowing())) {
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
            League.setLook(leagueId, hubStage, hubColor, lookTri(hubColor));
            h.removeCallbacks(leaguePoll); h.postDelayed(leaguePoll, 250);
        }
        hubLastState = st;
        if (hubRanked) rankTick(st);
        /* 결과 카드가 떠 있는 동안은 카드 안내를 그대로 두고, 카드를 닫은 직후(리셋이 GL 스레드에 닿기 전 — 허브는 아직 «경기»)는 «돌아가는 중» */
        /* 이어 하는 경기(체크포인트)는 허브가 타이틀 상태인 채로 싸운다 — 띠는 경기 안내로 */
        int shown = leagueId != null && st <= 2 ? 4 : st == 4 && leagueId == null ? 5 : st;
        if (hubBanner != null && hubCard == null) {
            hubBanner.setText(hubText(shown, hover, picked, hs[4]));
            /* 경기 중 띠가 게임 그림 위로 올라가 있으면(가로 화면 — 아래 자리가 없음) 숨긴다: 체력 막대를 경기 내내 가리지 않게(연습과 같은 규칙) */
            boolean over = shown == 4 && !bannerUserPos && ((FrameLayout.LayoutParams) hubBanner.getLayoutParams()).topMargin < gameBox[3];
            hubBanner.setVisibility(over || shown == 5 ? View.GONE : View.VISIBLE);   /* 5 = 타이틀·상대 선택으로 돌아가는 중 — 안내 없이 */
        }
    }

    /** 상대 고르기 안내 네 줄 — 이름·캐릭터·난도 / 순위·점수·상대 전적 / 걸린 점수(SF6 LP 처럼 «이기면 +12 · 지면 −12»)·별칭 / 버튼.
     *  머리말이 이미 «상대 선택» 이라 앞말은 빼고, 스테이지 이름은 게임 화면 오른쪽 세로 칸에 나오니 버튼만.
     *  줄 수를 늘 넷으로 — 커서를 옮길 때 띠 높이가 출렁이지 않게(좁은 띠에서 한 줄이 접히지 않게 줄마다 짧게) */
    private String hubOppLine(org.json.JSONObject o, String why) {
        int myW = o.optInt("l"), myL = o.optInt("w");
        java.util.List<String> tg = League.tags(o);
        String tag = o.optInt("char") == 14 ? "가운데 칸 = 숨은 상대" : tg.isEmpty() ? null : tg.get(0);   /* 유가는 고르기 판에 없어 무작위 칸에 */
        int me = League.me().optInt("score"), op = o.optInt("score");
        int win = League.delta(me, op, "W"), lose = League.delta(me, op, "L");
        String t = o.optString("nick") + "  " + League.CHAR[o.optInt("char")] + " " + League.STYLE[o.optInt("style")]
                 + " · " + League.DIFF[o.optInt("diff")] + "\n" + League.rankOf(o.optString("id")) + "위 " + op + "점 · 나와 "
                 + myW + "승 " + myL + "패" + (o.optInt("d") > 0 ? " " + o.optInt("d") + "무" : "") + "\n"
                 + (tag == null ? "이기면 +" + win + " · 지면 −" + (-lose) : "+" + win + "/−" + (-lose) + " · " + tag) + "\n";
        if (why != null) return t + "도전 불가 — " + why;
        return t + "A 도전  ·  OPTION 스테이지";
    }

    /** 랭크 판 타이틀 둘째 줄 — «검객 ★3 · 2410 LP · 14승 9패» */
    private static String myRankedLine() {
        RankCore.Me me = League.rankMe();
        return League.myRankLabel(me) + " · " + me.w + "승 " + me.l + "패";
    }

    /** 리그 판 타이틀 둘째 줄 — 내 순위 */
    private static String myRankLine() {
        org.json.JSONObject me = League.me();
        return "내 순위 " + League.rankOf("me") + "위/" + League.order().size() + "명 · " + me.optInt("score") + "점 · " + me.optInt("w") + "승 " + me.optInt("l") + "패";
    }

    private String hubText(int st, int hover, int picked, int label) {
        switch (st) {
        case 1: {
            int[] dv = hubDualV;
            String ml = hubRanked ? myRankedLine() : myRankLine();
            if (dv != null && dv.length >= 5 && dv[0] == 1 && dv[3] == 1)      /* 셋째 줄에 커서 — ◀▶ 로 고른다 */
                return "◀▶ 리그 대전 / 연습 대전  ·  A 시작\n" + (dv[1] == 1 ? "연습은 내 세이브 판에서 해요" : ml);
            if (hubRanked) return "리그 대전 · A 상대 찾기\n" + ml;
            return "리그 대전 — 메뉴 셋째 줄" + (label == 2 ? " «LEAGUE»" : "") + "\n" + ml;
        }
        case 6: return rankGoAt != 0 ? "대전 준비 중…" : rankFound != null ? "A 대전  ·  B 나가기  ·  ◀▶ 스테이지"
                     : rankLanded ? "B 나가기  ·  ◀▶ 스테이지" : "B 취소  ·  ◀▶ 스테이지  ·  ▲▼ 팁";   /* 대전을 받아 고르기로 넘어가는 사이엔 찾기 안내를 다시 띄우지 않는다 */
        case 2: {
            if (hover < 0) return "고를 수 없는 칸\n리그 상대가 있는\n캐릭터 칸을 고르세요\n ";
            String oid = hover < 15 ? hubOpp[hover] : null;
            org.json.JSONObject o = oid != null ? League.oppById(oid) : null;
            if (o == null) return League.CHAR[hover] + " — 리그 상대 없음\n다른 캐릭터를 고르세요\n \n ";
            return hubOppLine(o, League.blockedWhy(o));
        }
        case 3: {
            int scr = hubDualV != null && hubDualV.length >= 6 ? hubDualV[5] : 2;
            if (hubRanked) {
                org.json.JSONObject rm = lm();
                String who = rm != null ? League.oppLabel(rm) : rankOppV != null ? League.nickOf(this, rankOppV.p) : "?";
                String lab = rm != null && rm.optJSONObject("opp_snap") != null ? rm.optJSONObject("opp_snap").optString("label") : rankOppV != null ? rankOppV.label : "";
                return "vs " + who + " · " + lab + "\n스테이지 " + League.stageName(hubStage) + " · 카드 전부 열림\n" + myPickKeys(scr, true);
            }
            org.json.JSONObject o = (picked >= 0 && picked < 15 && hubOpp[picked] != null) ? League.oppById(hubOpp[picked]) : null;
            return "vs " + (o != null ? o.optString("nick") + " (" + League.CHAR[o.optInt("char")] + " " + League.STYLE[o.optInt("style")]
                    + ") · " + League.DIFF[o.optInt("diff")] : "?") + "\n스테이지 " + League.stageName(hubStage) + " · 카드 전부 열림"
                    + "\n" + myPickKeys(scr, true);   /* 컬러 이름은 게임 화면 오른쪽 세로 칸에 */
        }
        case 4: {
            org.json.JSONObject m = lm();
            if (League.isRanked(m)) return "랭크 · vs " + League.oppLabel(m) + "\n2선승 · 라운드 60초 · 뒤로 = 그만두기";
            org.json.JSONObject o = m != null ? League.oppById(m.optString("opp")) : null;
            return "리그 경기 · vs " + (o != null ? o.optString("nick") + " (" + League.CHAR[o.optInt("char")] + " " + League.STYLE[o.optInt("style")] + ")" : "?")
                    + "\n2선승 · 라운드 60초 · 뒤로 = 그만두기";
        }
        case 5: return "";                                           /* 돌아가는 중 — 띠를 숨긴다(hubTick) */
        default: return "리그 대전";
        }
    }

    /** 결과 카드 — 게임 위, 그 경기 직후 값(after)으로. 계속 / 재대결 / 순위표. */
    private void hubResult(final String resultId) {
        if (hubCard != null || leaving) return;
        final org.json.JSONObject m = League.matchById(resultId);
        if (m == null) { hubReturn(-1); return; }
        if (League.isRanked(m)) { rankResult(m); return; }
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
            org.json.JSONObject s0 = m.optJSONObject("score_before");
            int d = m.optInt("delta");
            if (s0 != null) sb.append("점수 ").append(s0.optInt("me")).append(" → ").append(s0.optInt("me") + d).append(" (").append(d > 0 ? "+" + d : String.valueOf(d)).append(")")
                              .append("  ·  순위 ").append(m.optInt("rank_before")).append(" → ").append(m.optInt("rank_after")).append("위\n");
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
        /* 랭크 판(hubRanked)에서는 예전 12명 고르기 흐름이 경기를 못 만든다(허브 3 → rankNewMatch) — 업데이트 전 경기를 끝낸 뒤엔 바로 랭크 찾기로 */
        final boolean canRe = !hubRanked && o != null && League.blockedWhy(o) == null && o.optInt("char", -1) >= 0 && o.optInt("char") < 15 && hubOpp[o.optInt("char")] != null;
        if (canRe) row.addView(hubPill("재대결", new Runnable() { @Override public void run() { hubReturn(o.optInt("char")); } }));
        if (hubRanked) row.addView(hubPill("랭크 매치", new Runnable() { @Override public void run() { rankOppV = null; hubReturn(-3); } }));   /* 리셋 뒤 바로 상대 찾기(−3) */
        else row.addView(hubPill("다른 상대", new Runnable() { @Override public void run() { hubReturn(-2); } }));   /* 리셋 뒤 바로 상대 선택(−2) */
        row.addView(hubPill("순위표", new Runnable() { @Override public void run() { hubCard = null; leagueExitBoard(); } }));
        card.addView(row);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        lp.topMargin = gameBox[1] + Math.round(8 * dp);
        root.addView(card, lp);
        hubCard = card;
        if (hubBanner != null) hubBanner.setText("결과를 기록했어요");
    }

    /* ── 확인 카드(창 없이) ─────────────────────────────────────────────
       게임 화면의 확인(그만두기·나가기·보류 안내 등)은 AlertDialog 창 대신 이 덮개 카드로 띄운다.
       안드로이드 9 에뮬레이터에서 게임 중 확인 창을 띄웠을 때 SurfaceFlinger 메인 스레드가 Region::orSelf 에서 돌기만 하다
       시스템이 통째로 재시작된 일이 있었다(10-13 리그 허브에서 뒤로 · 144 랭크 경기 중 뒤로 — 141 치트 창과 같은 스택).
       창을 새로 만들지 않으면 창 애니메이션 층(leash)·어둡게 깔기 층·포커스 이동이 없다.
       누르기 = 손가락 · 패드 ◀▶ 고르고 B·START·확인으로 누름, A·뒤로 = 취소(취소 가능할 때 — 바 메뉴와 같은 키). */
    private Ask ask;                                 /* 지금 떠 있는 카드(없으면 null) */
    final class Ask {
        private final FrameLayout scrim;
        private final android.widget.LinearLayout card, btns;
        private final java.util.List<TextView> list = new java.util.ArrayList<>();
        private boolean cancelable = true, showing;
        private int sel = -1;
        private Runnable onDismiss;
        private Ask under;                           /* 이 카드가 뜰 때 떠 있던 카드 — 닫으면 그 카드가 다시 맨 위(먼저 카드를 닫지 않는다: 그 닫힘 리스너가 경기를 풀지 않게) */
        Ask(String title, String msg) {
            float dp = getResources().getDisplayMetrics().density;
            scrim = new FrameLayout(EmuActivity.this);
            scrim.setBackgroundColor(0x99000000);
            scrim.setClickable(true);                /* 바깥 탭은 삼킨다(아래 패드·게임으로 안 감) — 닫지는 않는다(실수로 기권 창이 사라지지 않게) */
            card = new android.widget.LinearLayout(EmuActivity.this);
            card.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(0xf7140d08); g.setStroke(Math.round(3 * dp), 0xffd9a441); g.setCornerRadius(6 * dp);
            card.setBackground(g);
            card.setPadding(Math.round(16 * dp), Math.round(12 * dp), Math.round(16 * dp), Math.round(12 * dp));
            card.setClickable(true);
            if (title != null) {
                TextView t = new TextView(EmuActivity.this); t.setTypeface(hubFont()); t.setTextSize(20); t.setTextColor(0xffffd76a); t.setText(title);
                card.addView(t);
            }
            if (msg != null) {
                TextView b = new TextView(EmuActivity.this); b.setTypeface(hubFont()); b.setTextSize(14); b.setTextColor(0xfff3ead7); b.setText(msg);
                b.setLineSpacing(0, 1.15f); b.setPadding(0, Math.round(6 * dp), 0, Math.round(4 * dp));
                card.addView(b);
            }
            btns = new android.widget.LinearLayout(EmuActivity.this);
            btns.setOrientation(android.widget.LinearLayout.VERTICAL);   /* 세로로 — 엄지로 누르기 쉽고 «나중에 이어하기»처럼 긴 글자도 한 줄 */
            btns.setPadding(0, Math.round(8 * dp), 0, 0);
            card.addView(btns);
            scrim.addView(card, cardLp());
        }
        /** 세로 화면 = 아래(엄지 자리, 패드 위) · 가로 화면 = 가운데. 폭은 360dp 까지 */
        private FrameLayout.LayoutParams cardLp() {
            float dp = getResources().getDisplayMetrics().density;
            android.content.res.Configuration c = getResources().getConfiguration();
            boolean land = c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(Math.min(360, c.screenWidthDp - 32) * dp),
                    FrameLayout.LayoutParams.WRAP_CONTENT, land ? android.view.Gravity.CENTER : (android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL));
            if (!land) lp.bottomMargin = Math.round(28 * dp);
            return lp;
        }
        void place() { card.setLayoutParams(cardLp()); }
        /** 단추 — r 은 누른 뒤(카드를 닫기 전에) 한 번. null = 닫기만(AlertDialog 의 null 리스너처럼) */
        Ask btn(String label, final Runnable r) {
            float dp = getResources().getDisplayMetrics().density;
            final TextView t = hubPill(label, new Runnable() { @Override public void run() { press(r); } });
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(6 * dp);
            t.setLayoutParams(lp);
            if (!list.isEmpty()) { t.setTextColor(0xfff3ead7); ((android.graphics.drawable.GradientDrawable) t.getBackground()).setColor(0xff3a2a18); }   /* 첫 단추(계속)만 금색 */
            list.add(t); btns.addView(t);
            return this;
        }
        Ask cancelable(boolean c) { cancelable = c; return this; }
        Ask onDismiss(Runnable r) { onDismiss = r; return this; }
        Ask show() {
            if (showing) return this;
            under = ask;
            releasePhysical();                       /* 쥐고 있던 패드 키가 게임에 남지 않게(창이 포커스를 가져갈 때와 같게) */
            root.addView(scrim, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            showing = true; ask = this;
            return this;
        }
        boolean isShowing() { return showing; }
        void dismiss() {
            if (!showing) return;
            showing = false;
            root.removeView(scrim);
            if (ask == this) { ask = under; while (ask != null && !ask.showing) ask = ask.under; }   /* 아래 카드 중 아직 떠 있는 것 */
            if (onDismiss != null) onDismiss.run();
        }
        void cancel() { if (cancelable) dismiss(); }
        private boolean pressing;
        private void press(Runnable r) {
            if (!showing || pressing) return;        /* 두 번 눌러도 한 번만 */
            pressing = true;
            try { if (r != null) r.run(); } finally { pressing = false; }
            dismiss();                                /* AlertDialog 와 같은 차례 — 리스너 다음에 닫힘(닫힘 리스너가 리스너의 표시를 본다) */
        }
        private void select(int i) {
            if (list.isEmpty()) return;
            sel = (i + list.size()) % list.size();
            for (int k = 0; k < list.size(); k++) {
                TextView t = list.get(k);
                ((android.graphics.drawable.GradientDrawable) t.getBackground()).setStroke(k == sel ? Math.round(2 * getResources().getDisplayMetrics().density) : 0, 0xfffff3c4);
            }
        }
        /** 스틱·HAT — 처음엔 고르기 표시만 */
        void nav(int d) { if (sel < 0) select(0); else select(sel + d); }
        /** 패드·키보드 — 처음 누르면 고르기 표시만, 그다음부터 옮기기/누르기 */
        void handleKey(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return;
            int code = e.getKeyCode();
            String f = keymap != null ? keymap.funcOf(code) : null;
            boolean prev = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP || "left".equals(f) || "up".equals(f);
            boolean next = code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN || "right".equals(f) || "down".equals(f);
            boolean go = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BUTTON_START || "b".equals(f) || "start".equals(f);
            boolean back = code == KeyEvent.KEYCODE_BACK || "a".equals(f);
            if (back) { cancel(); return; }
            if (sel < 0) { if (prev || next || go) select(0); return; }
            if (prev) select(sel - 1);
            else if (next) select(sel + 1);
            else if (go) list.get(sel).performClick();
        }
    }
    private Ask ask(String title, String msg) { return new Ask(title, msg); }

    private TextView hubPill(String s, final Runnable r) {
        float dp = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setTypeface(hubFont()); t.setText(s); t.setTextSize(15); t.setTextColor(0xff140d08); t.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xffd9a441); g.setCornerRadius(4 * dp);
        t.setBackground(g);
        t.setPadding(Math.round(14 * dp), Math.round(8 * dp), Math.round(14 * dp), Math.round(8 * dp));
        t.setMinHeight(Math.round(48 * dp));                 /* 손가락이 닿게 */
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.round(5 * dp); lp.rightMargin = Math.round(5 * dp);
        t.setLayoutParams(lp);
        t.setClickable(true);
        t.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { r.run(); } });
        return t;
    }

    /* ── 랭크 매치(끝없는 매칭) — 허브 6(H_SEARCH): 타이틀 «리그 대전» A → «상대 찾는 중…»(2~12초) → «상대 발견!» 카드(A 대전 · B 나가기 ·
       ◀▶·OPTION 스테이지 · 3초 뒤 자동) → 허브가 «상대 선택» 없이 바로 내 캐릭터 고르기(대진·CPU 손잡이 무장, 허브 3) → 경기 → 결과 카드
       (다음 상대 −3 · 재대결 −4 · 그만 −1). 찾는 동안 게임 입력은 허브가 막고 누름만 모은다(nativeHubTakeEv). 설계 ranked.md 3-5 ── */
    private boolean hubRanked = false;
    private long rankGoAt;                       /* 0 = 없음 · 대전(go)을 native 가 받은 시각 — 허브가 6 을 떠날 때까지 찾기를 다시 안 연다 */
    private long rankFrozenAt;                   /* 확인 카드·멈춤 바 때문에 찾기 시계를 멈춘 시각(0 = 안 멈춤) */
    private View rankView;                          /* 대기창 / 카드 — 게임 위 */
    private RankUi.Search rankSearch;
    private RankUi.Found rankFound;
    private RankCore.Pick rankPick;
    private League.OppView rankOppV;                /* 대진할 상대(카드 A·재대결) — 허브 3 에서 경기 기록을 만들 때 그 값 그대로 */
    private RankCore.Knobs rankKnobs;
    private int rankStageSel = -1, rankStageRes = -1, rankRematchN = 0;
    private long rankT0, rankEnd, rankCardAt;
    private boolean rankAuto = true, rankLanded = false;
    private RankUi.ReelItem rankLand;               /* 내 상대 릴 칸(백그라운드에서 미리) — 대기창이 멈출 칸 */
    private int rankTipIdx = 0;
    private int rankStopNote = 0;                   /* 앱이 내려가서 멈춘 것 — 1 찾는 중 · 2 상대 카드(돌아오면 알림) */
    private byte[] hubHome;                         /* 캐릭터 → 그 상대의 기본 배경(hubStart) — 대기창에서 고른 배경을 허브에도 넣을 때 */
    private static final int[] RANK_STAGE_ORDER = { -1, -2, 0, 1, 2, 3, 4, 5, 6, 7 };   /* 상대 기본 → 무작위 → 8개 */

    private void rankTick(int st) {
        if (st != 6) {
            boolean vanished = rankView != null && rankGoAt == 0;   /* 찾는 중·카드인데 타이틀 메뉴가 사라졌다(카드였으면 상대는 잡힌 채) */
            rankGoAt = 0; rankFrozenAt = 0;
            if (rankView != null) rankClose();
            if (vanished && !leaving) toast("찾기를 멈췄어요 — A로 다시 찾기");
            return;
        }
        if (hubCard != null || leaving) return;
        if (!resumedNow && rankGoAt == 0) {      /* 앱이 내려가 있다 — 찾기를 새로 열거나 이어 가지 않는다(A 직후 홈: 못 본 상대가 잡히지 않게) */
            if (rankView == null) { Emu.nativeHubSearch(false); rankStopNote = 1; }
            return;
        }
        boolean barOpen = pad != null && pad.isBarOpen();
        if (ask != null || barOpen) {   /* 확인 카드·멈춤 바가 떠 있는 동안은 찾기·자동 대전 시계를 멈춘다 — 닫자마자 자동 대전이 나가지 않게. 바가 열린 동안은 대기창을 숨긴다(바 윗줄을 덮는다) */
            if (rankFrozenAt == 0) rankFrozenAt = android.os.SystemClock.uptimeMillis();
            if (rankView != null) rankView.setVisibility(barOpen ? View.INVISIBLE : View.VISIBLE);
            return;
        }
        if (rankView != null && rankView.getVisibility() != View.VISIBLE) rankView.setVisibility(View.VISIBLE);
        if (rankFrozenAt != 0) {
            long d = android.os.SystemClock.uptimeMillis() - rankFrozenAt;
            rankT0 += d; rankEnd += d; rankCardAt += d; if (rankGoAt != 0) rankGoAt += d; rankFrozenAt = 0;
        }
        if (rankGoAt != 0) {                     /* 대전을 눌렀다 — native 는 다음 게임 프레임에 고르기로 넘어간다: 그 사이 폴링이 찾기를 다시 열지 않게 */
            if (android.os.SystemClock.uptimeMillis() - rankGoAt < 2000) return;
            rankGoAt = 0; Emu.nativeHubSearch(false); toast("상대를 잡지 못했어요 — 다시 찾아 주세요");   /* 2초 넘게 안 넘어감(멈춤 등) — 메뉴로 */
            return;
        }
        if (rankView == null) { if (!rankSearchShow()) return; }
        int ev = Emu.nativeHubTakeEv();
        long now = android.os.SystemClock.uptimeMillis();
        if (rankFound == null) {                 /* 찾는 중 — B 취소 · ←→/OPTION 스테이지 · ▲▼ 팁 · A 는 안 받는다 */
            if ((ev & 2) != 0) { rankBack(); return; }   /* 릴이 상대에게 멈춘 뒤면 «나가기»(상대는 잡힌 채) */
            if ((ev & (4 | 8 | 16)) != 0) rankStageStepSearch((ev & 8) != 0 ? -1 : 1);
            if ((ev & (32 | 64)) != 0) rankTipStep((ev & 32) != 0 ? -1 : 1);
            rankSearch.tick(now - rankT0, rankPick.waitMs);
            if (!rankLanded && now >= rankEnd - 500) {   /* 릴이 내 상대에게 멈춤 → 0.5초 뒤 카드. 보인 그 순간 잡는다 — 보고 B 로 빼서 다시 뽑기를 막는다 */
                rankLanded = true;
                RankUi.ReelItem li = rankLandItem();
                rankSearch.land(li);
                if (li != null) League.holdPending(rankPick);
            }
            if (now >= rankEnd) rankCardShow();
            return;
        }
        if ((ev & 1) != 0 && now - rankCardAt >= 400) { rankGo(); return; }   /* 카드가 뜬 뒤 0.4초는 A 를 안 받는다 — 찾기 끝에 누른 A 가 못 본 상대를 받지 않게 */
        if ((ev & 2) != 0) { rankLeave(); return; }
        if ((ev & (4 | 8 | 16)) != 0) rankStageStep((ev & 8) != 0 ? -1 : 1);
        long left = 3000 - (now - rankCardAt);
        if (rankAuto && left <= 0) { rankGo(); return; }
        String g = rankAuto ? "대전 (A) " + (left / 1000 + 1) : "대전 (A)";
        if (!g.contentEquals(rankFound.go.getText())) rankFound.go.setText(g);
    }

    /** 대기창 — 상대는 여기서 정해 두고(rankedPick — 아무것도 저장 안 함) 카드를 띄울 때 잡는다(holdPending). 시간 = 설정 «매칭 대기»의 waitMs, 예상은 처음에 정한다 */
    private boolean rankSearchShow() {
        try { rankPick = League.rankedPick(this); } catch (Exception e) { rankPick = null; }
        if (rankPick == null || rankPick.p == null) { Emu.nativeHubSearch(false); toast("상대를 찾지 못했어요"); return false; }
        if (rankPick.waitMs <= 0) rankPick.waitMs = rankPick.searchMs;
        RankCore.Me me = League.rankMe();
        rankT0 = android.os.SystemClock.uptimeMillis(); rankEnd = rankT0 + rankPick.waitMs; rankFound = null; rankAuto = true; rankOppV = null; rankFrozenAt = 0;
        rankLanded = false; rankLand = null;
        rankStageSel = League.prefStage();
        rankTipIdx = RankTips.next();                                    /* 찾기 한 번에 새 팁 하나 */
        rankSearch = RankUi.search(this, hubFont(), rankWait(me), this::rankBack,
                () -> rankStageStepSearch(-1), () -> rankStageStepSearch(1), () -> rankTipStep(1));
        rankShow(rankSearch.root);                                       /* 높이는 rankPlace 가 게임 그림 안으로(팁·오늘 줄부터 뺀다) */
        rankReelLoad(rankPick, rankSearch);
        return true;
    }

    /** 대기창 글자 — 내 티어·LP 막대·오늘 전적·한 줄 알림·팁·스테이지 */
    private RankUi.Wait rankWait(RankCore.Me me) {
        RankUi.Wait w = new RankUi.Wait();
        Locale ko = Locale.KOREA;
        if (me.placementLeft > 0) {
            w.rankLine = "배치 경기 " + (6 - me.placementLeft) + "/5"; w.rankColor = RankUi.GOLD;   /* 찾는 그 판 — 이어 뜨는 상대 카드(«배치 경기 n/5 — …»)와 같은 수 */
            w.barLine = "5판을 마치면 첫 티어가 정해져요";
        } else if (me.master) {
            w.rankLine = "무쌍 · MR " + String.format(ko, "%,d", me.mr); w.rankColor = RankUi.tierColor(me.lp, true);
        } else {
            int tier = RankCore.tierOf(me.lp), star = RankCore.starOf(me.lp);
            int lo = RankCore.FLOOR[tier] + (star - 1) * RankCore.STEP[tier], hi = star >= 5 ? RankCore.FLOOR[tier + 1] : lo + RankCore.STEP[tier];
            w.rankLine = RankCore.label(me.lp) + " · " + String.format(ko, "%,d", me.lp) + " LP"; w.rankColor = RankUi.tierColor(me.lp, false);
            w.barFrac = Math.max(0f, Math.min(1f, (me.lp - lo) / (float) Math.max(1, hi - lo)));
            w.danger = me.shield;
            w.barLine = me.shield ? "강등 위기!" : (star >= 5 ? "승급까지 " : "다음 ★까지 ") + Math.max(0, hi - me.lp) + " LP";
        }
        League.Today td = League.rankedToday();
        if (td.games() == 0) w.todayLine = "오늘 첫 판이에요";
        else {
            w.todayLine = "오늘 " + td.w + "승 " + td.l + "패 · " + RankUi.signed(td.lpSum) + " LP";   /* 무승부는 점(·)으로만 */
            StringBuilder sb = new StringBuilder();
            for (char c : td.last5) sb.append(c == 'W' ? '●' : c == 'L' ? '○' : '·');   /* 새것이 왼쪽 */
            w.dots = sb.toString();
        }
        w.noteLine = me.streak >= 2 ? me.streak + "연승 중!" : me.lstreak >= 3 ? "다음엔 이겨요"
                   : League.bonusLeft() ? "첫 승 보너스 +" + RankCore.BONUS_DAILY + " 남았어요" : null;
        w.estSec = RankCore.estSec(rankPick.waitMs);
        w.held = rankPick.pending;
        w.stageLabel = rankStageLabelSearch();
        w.tip = RankTips.TIPS[rankTipIdx];
        w.active = RankCore.activeToday(League.rankSeed(), League.today());
        w.compact = rankLow();
        return w;
    }

    /** 릴 — 실제로 훑어본 후보(Pick.reel)·내 상대의 아바타·«닉 · 티어»를 백그라운드에서(프로필 풀을 처음 읽을 때 느리다) */
    private void rankReelLoad(final RankCore.Pick pk, final RankUi.Search s) {
        final java.util.List<RankCore.Player> ps = new java.util.ArrayList<>(pk.reel);
        new Thread(new Runnable() { @Override public void run() {
            final java.util.List<RankUi.ReelItem> out = new java.util.ArrayList<>();
            RankUi.ReelItem tg = null;
            try {
                tg = rankReelItem(pk.p);
                for (RankCore.Player p : ps) out.add(rankReelItem(p));
            } catch (Exception e) { android.util.Log.w("PocketCore", "rank reel: " + e); out.clear(); }
            final RankUi.ReelItem target = tg;
            h.post(new Runnable() { @Override public void run() {
                if (rankSearch != s || rankPick != pk) return;               /* 그 사이 그만뒀다·새로 찾는다 */
                if (target != null && rankLand == null) rankLand = target;
                if (pk.pending && target != null) s.setHeld(target.av);
                s.setReel(out);
            }});
        }}, "rank-reel").start();
    }
    private RankUi.ReelItem rankReelItem(RankCore.Player p) {
        org.json.JSONObject pf = League.pfOf(this, p);
        League.OppView v = League.oppView(p);
        return new RankUi.ReelItem(Avatar.of(pf, p.id), pf.optString("nick", p.nick) + " · " + v.label, RankUi.tierColor(v.lp, v.master));
    }
    private RankUi.ReelItem rankLandItem() {
        if (rankLand == null && rankPick != null) {
            try { rankLand = rankReelItem(rankPick.p); } catch (Exception e) { rankLand = null; }
        }
        return rankLand;
    }

    private void rankShow(View v) {
        if (rankView != null) root.removeView(rankView);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        rankView = v;
        rankPlaceLp(lp);
        root.addView(v, lp);
        if (pad != null && pad.isBarOpen()) v.setVisibility(View.INVISIBLE);
    }
    /** 게임 그림이 300dp 보다 낮다(가로·작은 폰) — 대기창은 늘 compact */
    private boolean rankLow() { return gameBox[3] - gameBox[1] < Math.round(300 * getResources().getDisplayMetrics().density); }
    /** 덮개(대기창·상대 카드) 자리 — 게임 그림 위쪽, 폭 min(화면−24dp, 340dp). 메뉴 알약 아래부터(알약을 덮지 않게).
     *  대기창은 높이를 게임 그림 안으로(rankSearch.fit — 폭·상한이 바뀔 때만 다시 잰다) */
    private void rankPlaceLp(FrameLayout.LayoutParams lp) {
        float dp = getResources().getDisplayMetrics().density;
        int W = root.getWidth(), H = root.getHeight();
        lp.width = Math.max(Math.round(240 * dp), Math.min(W - Math.round(24 * dp), Math.round(340 * dp)));
        int pill = W > 0 && H > 0 ? Math.round(PadView.handleH(W, H, dp) + 4 * dp) : 0;
        lp.topMargin = Math.max(gameBox[1], pill) + Math.round(6 * dp);
        if (rankSearch != null && rankView == rankSearch.root) rankSearch.fit(lp.width, gameBox[3] - lp.topMargin, rankLow());
    }
    /** 회전·화면 크기 바꿈(placeScreen) 뒤 — 덮개를 새 게임 그림에 맞춰 다시 */
    private final Runnable rankPlace = new Runnable() { @Override public void run() {
        if (rankView == null || !(rankView.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rankView.getLayoutParams();
        int w0 = lp.width, t0 = lp.topMargin;
        rankPlaceLp(lp);
        if (lp.width != w0 || lp.topMargin != t0) rankView.setLayoutParams(lp);
    }};
    private void rankClose() {
        if (rankView != null) root.removeView(rankView);
        rankView = null; rankSearch = null; rankFound = null;
    }
    private int rankHomeStage(RankCore.Player p) { int s = p.legacy ? League.homeStage(p.id) : p.stage; return s >= 0 && s < 8 ? s : 0; }
    private String rankStageText() {
        RankCore.Player p = rankPick.p;
        return "스테이지 " + (rankStageSel == -1 ? League.stageName(rankHomeStage(p)) + " (상대 기본)" : rankStageSel == -2 ? "무작위" : League.stageName(rankStageSel));
    }
    /** 대기창의 스테이지 이름 — 상대 기본은 이름을 안 보인다(누가 나올지 미리 알려 주지 않게) */
    private String rankStageLabelSearch() { return rankStageSel == -1 ? "상대 기본" : rankStageSel == -2 ? "무작위" : League.stageName(rankStageSel); }
    private int rankStageNext(int d) {
        int i = 0; while (i < RANK_STAGE_ORDER.length && RANK_STAGE_ORDER[i] != rankStageSel) i++;
        if (i >= RANK_STAGE_ORDER.length) i = 0;
        return RANK_STAGE_ORDER[(i + d + RANK_STAGE_ORDER.length) % RANK_STAGE_ORDER.length];
    }
    private void rankStageStep(int d) {
        rankAuto = false;
        rankStageSel = rankStageNext(d);
        League.setPrefStage(rankStageSel);
        if (rankFound != null) rankFound.stage.setText(rankStageText());
    }
    /** 찾는 중에 스테이지 — 같은 차례로 바꾸고 다음에도 그대로(pref · 허브 값도 맞춘다). 자동 대전은 그대로 켜 둔다(카드에서 바꿀 때만 끈다) */
    private void rankStageStepSearch(int d) {
        rankStageSel = rankStageNext(d);
        League.setPrefStage(rankStageSel);
        if (hubHome != null) { Emu.nativeLeagueHubLook(hubHome, rankStageSel, 0); hubStageSel = rankStageSel; }   /* 허브 값이 옛 값이면 hubTick 이 pref 를 되돌린다 */
        if (rankSearch != null) rankSearch.setStage(rankStageLabelSearch());
    }
    private void rankTipStep(int d) {
        rankTipIdx = Math.floorMod(rankTipIdx + d, RankTips.TIPS.length);
        if (rankSearch != null) rankSearch.setTip(RankTips.TIPS[rankTipIdx]);
    }
    private void rankCardShow() {
        RankCore.Player p = rankPick.p;
        League.holdPending(rankPick);                                    /* 카드를 본 다음에만 잡힌다 — 찾는 중에 그만두면 아무도 안 잡힘 */
        rankOppV = League.oppView(p);
        org.json.JSONObject pf = League.pfOf(this, p);
        RankCore.Me me = League.rankMe();
        int[] stakes = RankCore.stakes(me, rankOppV.mmr);
        String plc = me.placementLeft > 0 ? "배치 경기 " + (6 - me.placementLeft) + "/5 — 결과로 첫 티어가 정해져요" : null;
        rankStageSel = League.prefStage();
        long waited = Math.max(0, android.os.SystemClock.uptimeMillis() - rankT0) / 1000;
        rankFound = RankUi.found(this, hubFont(), pf, rankOppV, rankPick.tags, League.h2h(p.id), stakes, plc, rankStageText(),
                () -> { if (android.os.SystemClock.uptimeMillis() - rankCardAt >= 400) rankGo(); },   /* 손가락도 0.4초 — 취소 자리를 누르다 받지 않게 */
                this::rankLeave, () -> rankStageStep(-1), () -> rankStageStep(1),
                String.format(Locale.ROOT, "기다린 시간 %d:%02d", waited / 60, waited % 60));
        rankCardAt = android.os.SystemClock.uptimeMillis(); rankAuto = true;
        rankShow(rankFound.root);
        rankFound.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);   /* «상대 발견!» — 권한 없이 되는 짧은 진동 */
    }
    private void rankGo() {
        if (rankPick == null || rankView == null) return;
        RankCore.Player p = rankPick.p;
        if (rankOppV == null) rankOppV = League.oppView(p);
        rankKnobs = RankCore.knobs(rankOppV.mmr, p.personaIdx);
        rankStageRes = rankStageSel >= 0 ? rankStageSel : rankStageSel == -2 ? (int) (android.os.SystemClock.uptimeMillis() % 8) : rankHomeStage(p);
        rankRematchN = League.rematchCount(p.id);
        RankCore.Knobs k = rankKnobs;
        boolean ok = Emu.nativeHubMatch(p.ch, p.sty, k.diff, rankStageRes, k.lv, k.cap, k.atk, k.dfn, k.floor, true);
        rankClose();
        if (!ok) { Emu.nativeHubSearch(false); toast("상대를 잡지 못했어요 — 다시 찾아 주세요"); }
        else rankGoAt = android.os.SystemClock.uptimeMillis();
    }
    private void rankLeave() { rankGoAt = 0; Emu.nativeHubSearch(false); rankClose(); toast("상대가 기다려요 — 다음에 또 만나요"); }
    private void rankCancel(String msg) { rankGoAt = 0; Emu.nativeHubSearch(false); rankClose(); if (msg != null) toast(msg); }
    /** 뒤로 — 찾기는 그만(아무도 안 잡힘) · 카드였으면 상대는 잡힌 채 */
    private void rankBack() { if (rankFound != null || rankLanded) rankLeave(); else rankCancel("그만뒀어요"); }   /* 상대가 보인 뒤(릴 멈춤·카드)엔 나가기 — 잡힌 채 */

    /** 허브 3(내 캐릭터 고르기)에 닿았다 — 카드에서 정한 상대로 경기 기록(준비) */
    private boolean rankNewMatch() {
        org.json.JSONObject m = rankOppV != null ? League.newRankedMatch(this, rankOppV, rankKnobs, rankStageRes, rankRematchN) : null;
        if (m == null) {
            final org.json.JSONObject open = rankOppV != null ? League.openMatch() : null;
            hubReturn(-1);
            if (open != null && !"ready".equals(open.optString("status"))) {   /* 다른 판(같은 폴더)이 남긴 끝나지 않은 경기 — 막다른 안내 대신 그 자리에서 풀기 */
                Emu.nativeSetPaused(true);
                ask("끝나지 않은 경기가 있어요", "vs " + League.oppLabel(open) + "\n먼저 이어 하거나 기권해야 새 경기를 할 수 있어요.")
                        .btn("이어 하기", new Runnable() { @Override public void run() { switchSession(true, false); } })   /* 리그 판을 새로 열면 시작 때 이어 한다 */
                        .btn("기권", new Runnable() { @Override public void run() {
                            if (League.forfeit(open.optString("id")) == null) toast("저장 실패 — 다시 시도하세요"); else toast("기권으로 기록했어요"); } })
                        .btn("나중에", null)
                        .onDismiss(new Runnable() { @Override public void run() { if (hubCard == null && !leaving) Emu.nativeSetPaused(pad.isBarOpen()); } })
                        .show();
            } else toast("경기를 만들지 못했어요 — " + (rankOppV == null ? "상대 정보가 없어요" : "기록 저장 실패"));
            return false;
        }
        leagueId = m.optString("id"); leagueArmed = true; leagueDone = false; leagueStarted = false; leagueRetry = 0;
        leagueVersionProblem(m);
        League.setLook(leagueId, hubStage, hubColor, lookTri(hubColor));
        h.removeCallbacks(leaguePoll); h.postDelayed(leaguePoll, 250);
        return true;
    }

    /** 랭크 결과 카드 — LP·티어 변화, 보너스, 사건, 상대 한마디. 다음 상대 / 재대결(같은 상대 3판째까지) / 그만 */
    private void rankResult(final org.json.JSONObject m) {
        Emu.nativeSetPaused(true);
        float dp = getResources().getDisplayMetrics().density;
        android.graphics.Typeface f = hubFont();
        String st = m.optString("status"), title;
        org.json.JSONObject snap = m.optJSONObject("opp_snap");
        if (snap == null) snap = new org.json.JSONObject();
        java.util.List<String> lines = new java.util.ArrayList<>();
        String sub = null, event = null, quote = null;
        boolean hold = "hold".equals(st) || "cancelled".equals(st);
        if (hold) {
            title = "hold".equals(st) ? "보류" : "무효";
            lines.add(m.optString("why")); lines.add("LP 는 그대로예요.");
        } else {
            String out = m.optString("outcome");
            event = m.optString("event", null);
            boolean promo = event != null && event.startsWith("승급");
            title = m.optBoolean("forfeit") ? "기권패" : "W".equals(out) ? (promo ? "승급!" : "승리!") : "L".equals(out) ? "패배" : "무승부";
            org.json.JSONObject r = m.optJSONObject("result");
            if (r != null && !m.optBoolean("forfeit")) lines.add("최종 " + r.optInt("w1") + " : " + r.optInt("w2"));
            String tb = m.optString("tier_before"), ta = m.optString("tier_after");
            org.json.JSONObject s0 = m.optJSONObject("start");
            if ("배치".equals(tb)) {
                RankCore.Me me = League.rankMe();
                lines.add(m.optInt("placement_left") > 0 ? "배치 " + (5 - m.optInt("placement_left")) + "/5 · " + me.w + "승 " + me.l + "패" : "배치 완료!");
                if (m.optInt("placement_left") == 0) lines.add(ta + " · " + m.optInt("lp_after") + " LP");
            } else if (s0 != null && s0.optBoolean("master")) {
                int d = m.optInt("mr_after") - m.optInt("mr_before");
                lines.add("무쌍 MR " + m.optInt("mr_before") + " → " + m.optInt("mr_after") + " (" + RankUi.signed(d) + ")");
            } else {
                lines.add(tb.equals(ta) ? ta : tb + " → " + ta);
                lines.add(m.optInt("lp_before") + " → " + m.optInt("lp_after") + " LP (" + RankUi.signed(m.optInt("lp_delta")) + ")");
                org.json.JSONObject b = m.optJSONObject("bonus");
                if (b != null && "W".equals(out)) {
                    StringBuilder sb = new StringBuilder();
                    int base = m.optInt("lp_delta") - b.optInt("streak") - b.optInt("revenge") - b.optInt("daily");
                    if (b.optInt("streak") + b.optInt("revenge") + b.optInt("daily") > 0) {
                        sb.append("기본 +").append(base);
                        if (b.optInt("streak") > 0) sb.append(" · 연승 +").append(b.optInt("streak"));
                        if (b.optInt("revenge") > 0) sb.append(" · 설욕 +").append(b.optInt("revenge"));
                        if (b.optInt("daily") > 0) sb.append(" · 오늘 첫 승 +").append(b.optInt("daily"));
                        sub = sb.toString();
                    }
                }
                int lp = m.optInt("lp_after"), t = RankCore.tierOf(lp);
                if (t < 7 && !"무쌍".equals(ta)) {
                    int next = RankCore.starOf(lp) >= 5 ? RankCore.FLOOR[t + 1] : RankCore.FLOOR[t] + RankCore.starOf(lp) * RankCore.STEP[t];
                    lines.add((RankCore.starOf(lp) >= 5 ? "승급까지 " : "다음 ★까지 ") + Math.max(0, next - lp) + " LP");
                }
            }
            org.json.JSONObject af = m.optJSONObject("after");
            if (af != null) {
                if (af.optInt("streak") >= 2 && "W".equals(out)) lines.add(af.optInt("streak") + "연승!");
                lines.add(League.gwa(snap.optString("nick")) + "의 전적 " + af.optInt("h2h_w") + "승 " + af.optInt("h2h_l") + "패" + (af.optInt("h2h_d") > 0 ? " " + af.optInt("h2h_d") + "무" : ""));
            }
            if (m.optInt("broke_streak") >= 2) lines.add(m.optInt("broke_streak") + "연승이 끊겼어요");
            org.json.JSONObject pool = League.pool(this);
            String mood = snap.optString("mood", "polite");
            if (pool != null && !m.optBoolean("forfeit") && ("W".equals(out) || "L".equals(out))) {
                try { quote = ProfileGen.matchLine(pool, mood, m.optString("id"), "W".equals(out) ? "after_lose" : "after_win"); } catch (Exception ignored) { }
            }
        }
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        card.setBackground(RankUi.box(this, RankUi.BG, RankUi.STROKE, 3, 6));
        card.setPadding(Math.round(16 * dp), Math.round(12 * dp), Math.round(16 * dp), Math.round(12 * dp));
        card.setClickable(true);
        TextView t = RankUi.tv(this, f, title, 26, RankUi.GOLD); t.setGravity(android.view.Gravity.CENTER);
        card.addView(t);
        if (!hold) { View hd = RankUi.resultHead(this, f, snap, m.optString("opp")); hd.setPadding(0, Math.round(6 * dp), 0, Math.round(4 * dp)); card.addView(hd); }
        TextView b = RankUi.tv(this, f, android.text.TextUtils.join("\n", lines), 14, RankUi.BODY);
        b.setGravity(android.view.Gravity.CENTER); b.setLineSpacing(0, 1.15f); b.setPadding(0, Math.round(4 * dp), 0, Math.round(2 * dp));
        card.addView(b);
        if (sub != null) { TextView s = RankUi.tv(this, f, sub, 11, RankUi.DIM); s.setGravity(android.view.Gravity.CENTER); card.addView(s); }
        if (event != null) { TextView e = RankUi.tv(this, f, event, 15, RankUi.GOLD); e.setGravity(android.view.Gravity.CENTER); e.setPadding(0, Math.round(4 * dp), 0, 0); card.addView(e); }
        if (quote != null) { TextView q = RankUi.tv(this, f, snap.optString("nick") + ": “" + quote + "”", 12, RankUi.DIM); q.setGravity(android.view.Gravity.CENTER); q.setPadding(0, Math.round(6 * dp), 0, 0); card.addView(q); }
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setPadding(0, Math.round(10 * dp), 0, 0);
        final String oid = m.optString("opp");
        final RankCore.Player rp = League.rankPlayer(oid);
        row.addView(hubPill("다음 상대", () -> { rankOppV = null; hubReturn(-3); }));       /* 리셋 뒤 바로 다시 찾기 */
        if (rp != null && League.rematchCount(oid) < 3 && League.oppSupported(rp.ch, rp.sty))
            row.addView(hubPill("재대결", () -> rankRematch(m, rp)));
        row.addView(hubPill("그만", () -> { rankOppV = null; hubReturn(-1); }));
        card.addView(row);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        lp.topMargin = gameBox[1] + Math.round(8 * dp);
        root.addView(card, lp);
        hubCard = card;
        if (hubBanner != null) hubBanner.setText("결과를 기록했어요");
    }
    /** 재대결 — 그 상대의 지금 MMR 로 손잡이를 다시 정해(진 상대는 그만큼 세지거나 약해진다) 허브에 넣고 리셋 → 바로 내 캐릭터 고르기 */
    private void rankRematch(org.json.JSONObject m, RankCore.Player p) {
        rankPick = null;
        rankOppV = League.oppView(p);
        rankKnobs = RankCore.knobs(rankOppV.mmr, p.personaIdx);
        org.json.JSONObject lk = m.optJSONObject("look");
        rankStageRes = lk != null ? lk.optInt("stage", -1) : -1;
        rankRematchN = League.rematchCount(p.id);
        RankCore.Knobs k = rankKnobs;
        Emu.nativeHubMatch(p.ch, p.sty, k.diff, rankStageRes, k.lv, k.cap, k.atk, k.dfn, k.floor, false);
        hubReturn(-4);
    }

    /** 결과 카드 닫기 → 리셋 뒤 타이틀 «리그 대전» 칸(rematch ≥ 0 이면 같은 상대로 바로 내 캐릭터 고르기까지 · −2 «다른 상대» = 바로 상대 선택). */
    private void hubReturn(final int rematch) {
        if (hubCard != null) { root.removeView(hubCard); hubCard = null; }
        org.json.JSONObject m = lm();                       /* 상대만 고르고 대진 전에 타이틀로 — 준비 기록은 무효로(열린 채 남지 않게) */
        if (m != null && "ready".equals(m.optString("status"))) League.cancel(leagueId, "게임 속 리그 — 대진 전에 타이틀로");
        hubWaitGen = hubGen + 1;
        leagueId = null; leagueArmed = false; leagueDone = false; leagueStarted = false; leagueRetry = 0; rankGoAt = 0;
        h.removeCallbacks(leaguePoll);
        Emu.nativeSetPaused(pad != null && pad.isBarOpen());
        gl.queueEvent(new Runnable() { @Override public void run() { Emu.nativeLeagueHubReturn(rematch); } });
    }

    /** 리그 판에서 뒤로 = 바로 타이틀로 갈 화면인가 — 상대 고르기, 또는 상대를 고르고 첫 라운드 전(준비 기록은 hubReturn 이 무효로 닫는다, 점수 없음).
     *  점수가 걸린 경기(첫 라운드 뒤)·타이틀에서는 예전처럼 창을 띄운다 */
    private boolean hubPickBack() {
        if (!leagueHub || hubCard != null || leaving) return false;
        if (leagueId == null) return hubLastState == 2 || hubLastState == 3;
        leagueSyncStarted();
        org.json.JSONObject m = lm();
        return !leagueStarted && m != null && "ready".equals(m.optString("status"));
    }

    /** 경기 밖(타이틀·상대 고르기 — 상대를 잡고 첫 라운드 전 포함)에서 메뉴 «목록»·«종료» — 타이틀: 계속 / 내 게임으로 / 순위표 / 목록으로 · 상대 고르기: 계속 / 타이틀로 / 내 게임으로 / 순위표.
     *  찾는 중·상대 카드면 그대로 멈춰 있다(rankTick) — 어느 단추든 깨끗이 나간다(카드였으면 상대는 잡힌 채) */
    private void hubLeave() {
        if (leagueDlg != null && leagueDlg.isShowing()) return;
        Emu.nativeSetPaused(true);
        boolean pick = leagueId != null || hubLastState == 2 || hubLastState == 3;   /* 준비 기록(상대를 잡고 첫 라운드 전)도 고르기 화면으로 친다 */
        Ask d = ask("리그 대전", pick ? "상대 고르기를 그만둘까요? 이 게임은 캐릭터 고르기에서 뒤로 갈 수 없어 리셋해 타이틀로 가요."
                                    : "어디로 갈까요?")
                .btn("계속", null);
        if (pick) d.btn("타이틀로", new Runnable() { @Override public void run() { if (!hubLeaveStarted()) hubReturn(-1); } });
        d.btn("내 게임으로", new Runnable() { @Override public void run() { if (!hubLeaveStarted()) hubToGame(); } });
        d.btn("순위표", new Runnable() { @Override public void run() { if (!hubLeaveStarted()) leagueExitBoard(); } });
        if (!pick) d.btn("목록으로", new Runnable() { @Override public void run() { leagueHub = false; goList(); } });
        leagueDlg = d.onDismiss(new Runnable() {
            @Override public void run() { if (hubCard == null && !leaving) Emu.nativeSetPaused(pad.isBarOpen()); } }).show();
    }

    /** 창이 뜨는 사이 첫 라운드가 시작됐다(준비 기록이 있을 때) — 점수가 걸렸으니 보통 나가기 창으로 */
    private boolean hubLeaveStarted() {
        if (leagueId == null) return false;
        leagueSyncStarted();
        if (!leagueStarted) return false;
        h.post(new Runnable() { @Override public void run() { leagueLeave(); } });
        return true;
    }

    /** «내 게임으로» — 대진 전 기록은 사용자가 그만둔 것으로 닫고(잡힌 상대 miss 아님) 일반 판으로. 일반 판은 오토세이브(싸우던 자리·연습 중이던 자리)부터 */
    private void hubToGame() {
        org.json.JSONObject m = lm();
        if (m != null && "ready".equals(m.optString("status"))) League.cancel(leagueId, "게임 속 리그 — 대진 전에 내 게임으로");
        switchSession(false, false);
    }

    /** 리그 판을 못 열 때(연습 판에서 셋째 줄 «리그 대전»으로 갈아타 왔을 때) — 이유를 보이고 원래 게임(일반 판)으로 돌려보낸다 */
    private void hubBlockedBack(final String why) {
        leagueDone = true;
        h.post(new Runnable() { @Override public void run() {
            ask("리그 대전을 열 수 없어요", why)
                    .cancelable(false)
                    .btn("게임으로 돌아가기", new Runnable() { @Override public void run() { switchSession(false, false); } })
                    .show();
        }});
    }

    /** 일반 판 ↔ 리그 판 갈아타기(타이틀 셋째 줄 ◀▶ 에서 다른 판의 모드를 A) — 이 창은 코어를 내리고(일반 판은 오토세이브), 같은 롬으로 새 창을 연다.
     *  리그 판은 바로 상대 선택까지(EXTRA_HUB_GO), 일반 판은 practiceAuto 면 연습 상대 선택까지(EXTRA_PRACTICE) */
    private void switchSession(final boolean toLeague, final boolean practiceAuto) {
        if (leaving) return;
        leaving = true;
        liveHubOff();
        if (hubBanner != null) { hubBanner.setText(toLeague ? "리그 대전으로…" : practiceAuto ? "연습 대전으로…" : "게임으로…"); hubBanner.setVisibility(View.VISIBLE); }
        final String rom = getIntent().getStringExtra("rom");
        final boolean saveAuto = autoSave && !ranked();
        gl.queueEvent(new Runnable() { @Override public void run() {
            if (loaded) {
                Emu.nativeLeagueDisarm();
                Emu.nativeSaveSram();
                if (saveAuto) Emu.nativeSaveState(autoStatePath().getAbsolutePath());   /* 다음에 평소처럼 켜면 이 타이틀에서 */
                Emu.nativeUnload(); loaded = false;
            }
            runOnUiThread(new Runnable() { @Override public void run() {
                Intent i = new Intent(EmuActivity.this, EmuActivity.class);
                i.putExtra("rom", rom);
                if (toLeague) { i.putExtra(EXTRA_LEAGUE_HUB, true); i.putExtra(EXTRA_HUB_GO, true); i.putExtra(EXTRA_FROM_GAME, true); }
                else if (practiceAuto) i.putExtra(EXTRA_PRACTICE, true);
                try { startActivity(i); }
                catch (Exception e) {                          /* 코어는 이미 내렸다 — 목록으로라도(그것도 안 되면 닫기만) */
                    android.util.Log.w("PocketCore", "switchSession: " + e);
                    try { startActivity(new Intent(EmuActivity.this, Home.class).putExtra("menu", true)); } catch (Exception ignored) { }
                }
                overridePendingTransition(0, 0);
                finish();
                overridePendingTransition(0, 0);
            }});
        }});
    }

    /* ── 멈춤 바 «리그 대전» 칸(148) — 일반 사무쇼2 판 어디서나(타이틀·아케이드·연습·싸우는 중) 뒤로 → 한 번. 리그 판은 자기 롬 사본을 만들어서
       practiceRomOk 는 안 본다(조작 패치 판에서도 리그는 열린다). 지금 자리는 오토세이브로 남기고(갈아타기 = switchSession) 리그 판 대기창으로 ── */

    /** 칸을 보일지 — 코어를 올린 뒤(loadCore — UI·GL 스레드 어느 쪽에서든). 사무쇼2 일반 판 · «리그·연습» 켬 · 리그 기록을 읽을 수 있을 때 */
    private void leagueCellSetup() {
        boolean on = loaded && !ranked() && leagueId == null && !leagueHub && game != null && "ss2".equals(game.id) && hubOn();
        if (on) { League.db(); on = League.dbProblem == null; }       /* dbProblem 은 기록 파일을 한 번 읽어야 정해진다 */
        final boolean v = on;
        h.post(new Runnable() { @Override public void run() { if (pad != null) pad.setLeagueCell(v); } });
    }

    /** 칸을 눌렀다 — 못 쓰면 알림 · 오토세이브 꺼짐이면 한 번 묻기(지금 판은 안 이어진다 — 롬을 고친 판에선 타이틀인지 앱이 못 가린다) · 아니면 바로 */
    private void leagueFromGame() {
        if (ranked() || leaving || !loaded) return;
        League.db();
        if (League.dbProblem != null || League.writeProblem() != null) { toast("리그 기록을 쓸 수 없어요 — 저장소 권한을 확인해 주세요"); return; }
        if (autoSave) { leagueGo(); return; }
        Emu.nativeSetPaused(true);
        ask("리그 대전으로", "자동 저장이 꺼져 있어서 지금 판은 이어지지 않아요. 갈까요?")
                .btn("가기", new Runnable() { @Override public void run() { leagueGo(); } })
                .btn("취소", null)
                .onDismiss(new Runnable() { @Override public void run() { if (!leaving) Emu.nativeSetPaused(pad.isBarOpen() || (sheet != null && sheet.isShowing())); } })
                .show();
    }
    private void leagueGo() {
        if (hubBanner == null) addHubBanner();                          /* «리그 대전으로…» 띠 — 연습이 안 켜진 판엔 띠가 없다 */
        switchSession(true, false);
    }

    /** 순위표(리그 화면)로 나가기 — 결과 창 없이 */
    private void leagueExitBoard() {
        org.json.JSONObject m = lm();                       /* 대진 전에 나가면 준비 기록은 무효로(열린 채 남지 않게) */
        if (m != null && "ready".equals(m.optString("status"))) League.cancel(leagueId, "게임 속 리그 — 대진 전에 순위표로");
        leagueHub = false;                                   /* leagueExit 가 결과 카드로 새지 않게 */
        leagueExit(null);
    }

    /* ── 치트(cheat.c · Cheats) ─────────────────────────────────────────
       일반 세션이고 사무쇼2이며 판정 주소를 확인한 롬 판(연습과 같은 판 확인 — practiceRomOk)이면 options 값을 native 에 넣는다.
       리그 판(랭크전 한 경기·게임 속 리그)은 잠근다 — 값을 아예 안 넣고, native 도 허브 종류를 보고 한 번 더 막는다(기록이 깨끗하게).
       코어 쪽은 올리고 내릴 때 이미 모두 끈다. 게임 안 「설정」 › «치트»에서 바꾸면 닫을 때 값만 다시 넣는다(코어를 다시 올리지 않음). */

    private volatile boolean cheatOk = false, cheatFight = false;   /* cheatSetup 은 GL 스레드(롬 바뀜 다시 로드)에서도 돈다 */
    private boolean cheatLeagueTold = false;
    private int cheatPermPrev = -1;
    private TextView cheatTag;

    /** 코어를 올린 뒤(loadCore — UI·GL 스레드 어느 쪽에서든) */
    private void cheatSetup() {
        cheatOk = false;
        if (!loaded) return;
        java.util.Map<String, String> m = Settings.load();
        if (ranked()) {
            Emu.nativeCheatLock(true);
            if (Cheats.any(m) && !cheatLeagueTold) { cheatLeagueTold = true; toast("리그 대전에선 치트가 꺼져요"); }
            return;
        }
        if (game == null || !Cheats.GAME.equals(game.id)) return;
        cheatOk = practiceRomOk();
        if (!cheatOk) { if (Cheats.any(m)) toast("이 롬 판에선 치트가 안 켜져요"); return; }
        pushCheats();
        h.post(new Runnable() { @Override public void run() { h.removeCallbacks(cheatPoll); h.postDelayed(cheatPoll, 500); } });
    }

    /** options 의 치트 값을 native 에 — 넣었으면 true(일반 세션 · 확인한 판일 때만) */
    private boolean pushCheats() {
        if (!loaded || !cheatOk || ranked()) return false;
        java.util.Map<String, String> m = Settings.load();
        Cheats.push(m);
        cheatFight = Cheats.fight(m);
        return true;
    }

    /** 1초마다 — 왼쪽 위 작은 «치트» 표시(먹는 치트가 있을 때 · 리그 이름표와 같은 자리, 색만 흐리게), «세이브에 남기기»가 세이브에 들어간 순간 한 번 알림 */
    private final Runnable cheatPoll = new Runnable() { @Override public void run() {
        if (leaving || !cheatOk || ranked()) { showCheatTag(null); return; }
        if (loaded) {
            int[] v = Emu.nativeCheatStatus();
            if (v != null && v.length >= 16) {
                showCheatTag(v[1] == 0 || v[0] != 0 ? null : v[7] == 3 && cheatFight ? "치트 · 연습 중엔 경기 치트 쉼" : "치트");
                /* [5]: 게임이 세이브를 다시 읽은 값이 모두 8(1)·아님(0)·아직 모름(−1 — 올린 뒤·불러온 뒤 첫 다시 읽기 전). «모름 → 1» 은 원래 들어가 있던 것 —
                   이 세션에서 «0 → 1» 이 된 순간만 알린다(이어하기로 연 판에서 처음 모드를 고를 때 잘못 알리지 않게) */
                if (v[5] < 0) cheatPermPrev = -1;
                else { if (cheatPermPrev == 0 && v[5] == 1 && v[15] == 1) toast("올카드가 세이브에 들어갔어요"); cheatPermPrev = v[5]; }
            }
        }
        h.postDelayed(this, 1000);
    }};

    private void showCheatTag(String t) {
        if (t == null) { if (cheatTag != null) cheatTag.setVisibility(View.GONE); return; }
        if (cheatTag == null) {
            cheatTag = new TextView(this);
            cheatTag.setTextColor(0x99d9a441);                           /* 리그 이름표 색을 흐리게 */
            cheatTag.setTextSize(11);
            float dp = getResources().getDisplayMetrics().density;
            cheatTag.setPadding(Math.round(8 * dp), Math.round(2 * dp), 0, 0);
            root.addView(cheatTag, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.LEFT));
        }
        if (!t.contentEquals(cheatTag.getText())) cheatTag.setText(t);
        cheatTag.setVisibility(View.VISIBLE);
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

    /** 실행 창 «리그·연습» 줄 — 켜면(기본) 타이틀 셋째 줄이 «◀ 연습 대전 ▶», ←→ 로 «리그 대전». 끄면 원래 «2인 대전» */
    private boolean hubOn() { return "enabled".equals(readOpt("pocketcore_ss2_hub", "enabled")); }
    /** 일반 판에서 연습이 켜지나(지금 롬 사본·패치 기준) — 리그 판이 셋째 줄에 «연습 대전»을 내놓을지 정할 때 쓴다(리그 판은 다른 사본이라 직접 못 본다) */
    private String practiceOkKey() {                                   /* 롬 파일마다(원본 경로 이름) — 다른 롬·사본 결과와 섞이지 않게 */
        String r = getIntent().getStringExtra("rom");
        return "pocketcore_ss2_practice_ok_" + (r != null ? new File(r).getName().replaceAll("[^A-Za-z0-9._-]", "_") : "");
    }
    private void notePracticeOk(boolean ok) {
        String v = ok ? "1" : "0";
        if (!v.equals(Settings.load().get(practiceOkKey()))) Settings.put(practiceOkKey(), v);
    }

    /** 코어를 올린 뒤(loadCore — UI·GL 스레드 어느 쪽에서든) — resumed = 이어하기 상태를 불러왔나(그러면 자동 진입은 안 한다) */
    private void practiceSetup(boolean resumed) {
        practiceHub = false; practiceDual = false;
        if (!loaded || ranked() || game == null || !"ss2".equals(game.id)) { hidePracticeBanner(); return; }
        final boolean want = getIntent().getBooleanExtra(EXTRA_PRACTICE, false) && !practiceAutoUsed;
        if (!hubOn() && !want) { hidePracticeBanner(); return; }        /* «리그·연습» 끔 — 원래 게임 그대로 */
        if (!practiceRomOk()) {
            hidePracticeBanner();
            notePracticeOk(false);
            String md5 = League.md5(new File(romPath));                  /* 알림은 롬 판마다 한 번(또는 연습을 고르고 들어왔을 때) — 켤 때마다 뜨지 않게 */
            if (want || !md5.equals(Settings.load().get("pocketcore_ss2_hub_warned"))) {
                if (!dualWarned) toast("이 롬 판에선 리그·연습이 안 켜져요 — 조작·수동 패치가 들었거나 확인 안 된 판");
                dualWarned = true; Settings.put("pocketcore_ss2_hub_warned", md5);
            }
            return;
        }
        notePracticeOk(true);
        Emu.nativePracticeHub(true, want && !resumed);
        League.db();                                                     /* dbProblem 은 기록 파일을 한 번 읽어야 정해진다 */
        practiceDual = hubOn() && League.dbProblem == null && League.writeProblem() == null;   /* 리그 기록을 못 쓰면 셋째 줄은 연습만 */
        if (hubOn() && !practiceDual && !dualHintShown) { dualHintShown = true; toast("리그 기록을 못 써서 셋째 줄은 연습 대전만 돼요 — 저장소 권한을 확인해 주세요"); }
        Emu.nativeHubDual(practiceDual);
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
            setFrameMeter(false);
        }});
    }

    private final Runnable practicePoll = new Runnable() { @Override public void run() {
        if (!practiceHub || leaving || leagueHub || leagueId != null) return;
        if (loaded && hubBanner != null) {
            int[] hs = Emu.nativeLeagueHubStatus(), ps = Emu.nativePracticeStatus();
            int[] dv = Emu.nativeHubDualStatus();                         /* 둘 다 꺼져 있어도 — 메뉴 화면(v[5])으로 띠를 고른다 */
            if (practiceDual && dv != null && dv.length >= 5 && dv[2] == 1) { switchSession(true, false); return; }   /* 셋째 줄 «리그 대전» — 리그 판으로 */
            setFrameMeter(hs != null && ps != null && hs[0] == 4 && ps.length > 24 && ps[24] == 1);   /* 연습 메뉴 «표시: 프레임·둘 다» — 경기 중에만 */
            String t = (hs != null && ps != null) ? practiceText(hs[0], ps, dv) : null;
            if (t == null && hs != null && hs[0] == 1) t = lookHint();   /* 허브는 타이틀 그대로 1인·무한 대전 — 고르기 화면이면 컬러 안내 */
            /* 경기 중(메뉴 닫힘)엔 띠가 게임 그림 위로 올라가면(가로 화면 — 아래 자리가 없음) 숨긴다: 체력 막대·«2P» 를 연습 내내 가리지 않게 */
            if (t != null && hs[0] == 4 && (ps.length < 2 || ps[1] == 0) && !bannerUserPos     /* 유저가 옮겨 둔 자리면 그대로 보인다 */
                    && ((FrameLayout.LayoutParams) hubBanner.getLayoutParams()).topMargin < gameBox[3]) t = null;
            if (t == null) hubBanner.setVisibility(View.GONE);
            else { if (!t.contentEquals(hubBanner.getText())) hubBanner.setText(t); hubBanner.setVisibility(View.VISIBLE); }
        }
        h.postDelayed(this, 150);
    }};

    /** 띠 안내 — 버튼만(허브 상태 2 상대 선택 · 3 내 캐릭터 · 4 경기). 타이틀은 셋째 줄에 커서가 있을 때만(◀▶ 고르기), 그 밖·돌아가는 중엔 숨긴다.
     *  버튼 이름은 패드 글자 그대로(A · B · OPTION) — 레퍼런스: 철권8 «[BACK]+[←] 벽을 등지고», KOF XV «왼쪽 = 화면 왼쪽 끝» 처럼 방향마다 결과를 적는다 */
    /** 내 캐릭터 쪽 버튼 안내 — 화면마다(캐릭터: B = 2P색으로 결정 · 검질: B 는 게임의 «뒤로» · 카드: 게임 그대로). 그 밖(이야기·라운드 연출)은 빈 줄/숨김 */
    private static String myPickKeys(int scr, boolean league) {
        if (!lookKeys && (scr == 2 || scr == 4)) return "A 결정";      /* 설정 «컬러 고르기» 끔 — OPTION·B 는 게임 그대로(아무 일 없음) */
        if (scr == 2) return "A 결정  ·  B 2P색  ·  OPTION 컬러";
        if (scr == 4) return "A 결정  ·  OPTION 컬러";
        if (scr == 6) return league ? "카드 고르기 (전부 열림)" : "카드 고르기";
        return league ? "곧 시작" : null;
    }

    private static String practiceText(int hubState, int[] ps, int[] dv) {
        switch (hubState) {
        case 1:
            if (dv != null && dv.length >= 5 && dv[0] == 1 && dv[3] == 1)
                return "◀▶ 연습 대전 / 리그 대전  ·  A 시작" + (dv[1] == 0 ? "\n리그는 따로 세이브 판에서 해요" : "");
            return null;
        case 2: return "A 수라 · B 나찰 · OPTION 스테이지";   /* 고르는 캐릭터 = 더미(검질은 A·B) — 좁은 띠에서도 한 줄 */
        case 3: return myPickKeys(dv != null && dv.length >= 6 ? dv[5] : 2, false);
        case 4:   /* 좁은 띠(버튼 사이)에서도 줄이 접히지 않게 두 줄로 */
            if (ps.length > 1 && ps[1] != 0) return "↑↓ 고르기 · ←→ 바꾸기\nA 실행 · B 닫기";
            return "OPTION 메뉴 · OPTION+↓ 리셋\n+← 왼쪽 · +↑ 가운데 · +→ 오른쪽";
        default: return null;
        }
    }

    /* ── 모든 모드 컬러(look.c) ─────────────────────────────────────────
       사무쇼2 확인한 판(일반 판 practiceRomOk · 리그 판 hubVersionProblem 없음)이고 설정 «컬러 고르기»가 켬이면 1인·무한·연습·리그 어디서나
       캐릭터·검질 고르기에서 OPTION = 다음 컬러(길게 = 컬러 만들기) · B = 2P색으로 결정. 기억(캐릭터마다)·내 색은 앱 설정(Look) —
       native 가 바꾼 기억은 폴(200ms)이 받아 남긴다. 띠 안내는 허브 띠가 없는 화면(일반 1인·무한 대전)의 고르기 화면에서만. */
    private boolean lookOn = false;
    private static volatile boolean lookKeys = true;   /* 띠의 «B 2P색 · OPTION 컬러» 안내를 보일지(컬러가 켜진 판) — 연습·리그 띠(myPickKeys)도 본다 */
    private int[] lookV;                         /* 마지막 nativeLookStatus */
    private int lookMemGen = 0, lookEditReq = 0, lookLost = 0, lookRecColor = -9;
    private int[][][] lookCus = new int[Look.NCH][2][];
    private android.view.View lookEditor;        /* 컬러 만들기 창(스크롤 틀) — 떠 있으면 게임 입력은 look 이 모두 막는다 */

    /** 코어를 올린 뒤(loadCore — UI·GL 스레드 어느 쪽에서든) — 켤 판이면 기억·내 색을 넣고 폴을 돌린다. 코어를 올릴 때 native 가 이미 끈 채로 시작한다 */
    private void lookSetup() {
        boolean ok = loaded && game != null && "ss2".equals(game.id) && Look.on()
                && (ranked() ? hubVersionProblem() == null : practiceRomOk());
        lookOn = ok; lookKeys = ok;
        Emu.nativeLookEnable(ok);
        if (ok) {
            Look.push();
            lookCus = Look.loadCustom();
            int[] v = Emu.nativeLookStatus();                            /* native 셈은 프로세스 내내 이어진다 — 지난 판의 요청·바뀜으로 창이 뜨지 않게 지금 값부터 */
            if (v != null && v.length >= 14) { lookMemGen = v[2]; lookEditReq = v[7]; }
        }
        h.post(new Runnable() { @Override public void run() {
            h.removeCallbacks(lookPoll);
            if (lookOn) h.postDelayed(lookPoll, 200);
            else { if (pad != null) pad.setLookCell(false); lookEditorClose(); }
        }});
    }

    private final Runnable lookPoll = new Runnable() { @Override public void run() {
        if (!lookOn || leaving) return;
        if (loaded) {
            int[] v = Emu.nativeLookStatus();
            if (v != null && v.length >= 14) lookTick(v);
        }
        h.postDelayed(this, 200);
    }};

    private void lookSaveMem() {
        int[] v = Emu.nativeLookStatus();
        if (v == null || v.length < 14 || v[2] == lookMemGen) return;
        lookMemGen = v[2];
        byte[] m = Emu.nativeLookGetMem();
        if (m != null) Look.saveMem(m);
    }

    private void lookTick(int[] v) {
        lookV = v;
        if (v[2] != lookMemGen) lookSaveMem();                           /* OPTION 으로 바꾼 컬러 — 캐릭터마다 기억(다음에도 그 색) */
        if (v[7] != lookEditReq) {                                       /* OPTION 길게 — 컬러 만들기 */
            lookEditReq = v[7];
            if (v[1] == 1) openLookEditor(v[12], v[13]);
        }
        if (lookEditor != null && v[1] != 1) { if (++lookLost > 5) lookEditorClose(); }   /* 고르기 화면을 떠났다(불러오기 등) — 창을 닫는다 */
        else lookLost = 0;
        if (pad != null) pad.setLookCell(v[1] == 1 && v[12] >= 0 && lookEditor == null);
        if (leagueId != null && !leagueHub && !leagueStarted && !leagueDone) {   /* 리그 화면에서 연 한 경기 — 고른 컬러를 기록에(체크포인트 이어 하기가 이 값을 본다) */
            int c = Math.max(0, v[4]);
            if (c != lookRecColor) {
                org.json.JSONObject m = lm(), lk = m != null ? m.optJSONObject("look") : null;
                League.setLook(leagueId, lk != null ? lk.optInt("stage", -1) : -1, c, lookTri(c));
                lookRecColor = c;
            }
        }
        if (!practiceHub && !leagueHub && leagueId == null) {          /* 허브 띠가 없는 일반 판(«리그·연습» 끔 등) — 고르기 화면에서만 컬러 안내 */
            String t = lookHint();
            if (t != null) {
                if (hubBanner == null) addHubBanner();
                if (!t.contentEquals(hubBanner.getText())) hubBanner.setText(t);
                hubBanner.setVisibility(View.VISIBLE);
            } else if (hubBanner != null) hubBanner.setVisibility(View.GONE);
        }
    }

    /** 일반 1인·무한 대전 고르기 화면의 띠 — 캐릭터: «A 결정 · B 2P색 · OPTION 컬러» / «OPTION 길게 = 컬러 만들기», 검질: «A 결정 · OPTION 컬러». 그 밖 null */
    private String lookHint() {
        int[] v = lookV;
        if (!lookOn || v == null || v.length < 14 || v[1] != 1 || lookEditor != null) return null;
        if (v[8] >= 0) return myPickKeys(4, false);
        return myPickKeys(2, false) + (v[12] >= 0 ? "\nOPTION 길게 = 컬러 만들기" : "");
    }

    /** 경기 기록에 남길 내 색 세 색(16·17 일 때만 — 그 캐릭터의 내 색, 무작위 칸이면 모름) */
    private int[] lookTri(int color) {
        int[] v = lookV;
        if (color < Look.NPRE || color >= Look.NIDX || v == null || v.length < 14 || v[5] < 0 || v[5] >= Look.NCH) return null;
        return lookCus[v[5]][color - Look.NPRE];
    }

    /** «컬러 만들기» 창 — 화면 아래(세로)·오른쪽(가로)에, 가운데 미리보기 그림이 보이게. ch·sty = 고르기 화면의 캐릭터·검질 */
    private void openLookEditor(final int ch, int sty) {
        if (lookEditor != null || !lookOn || ch < 0 || ch >= Look.NCH || root == null) return;
        int[] tri = Emu.nativeLookTriples(ch, sty);
        if (tri == null || tri.length < Look.NIDX * 3) return;
        int[] v = lookV;
        final String nm = League.CHAR[ch];
        LookEditor ed = new LookEditor(this, hubFont(), nm, tri, v != null && v.length >= 14 ? v[3] : 0, lookCus[ch], new LookEditor.Host() {
            @Override public void preview(int[] c) { Emu.nativeLookEdit(true, c[0], c[1], c[2]); }
            @Override public void save(int k, int[] c) {
                Look.saveCustom(ch, k, c);
                lookCus = Look.loadCustom();
                Emu.nativeLookSetCustom(ch, k, c[0], c[1], c[2]);
                byte[] m = Emu.nativeLookGetMem();                   /* 그 캐릭터 기억 = 방금 만든 내 색(창이 열린 동안 게임 입력은 막혀 native 기억이 안 바뀐다) */
                if (m != null && m.length >= Look.NSLOT) { m[ch] = (byte) (Look.NPRE + k); Emu.nativeLookSetMem(m); Look.saveMem(m); }
                lookEditorClose();
                toast("내 색 " + (k + 1) + " 저장 — " + nm);
            }
            @Override public void cancel() { lookEditorClose(); }
        });
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(ed);
        root.addView(sv, lookEditorLp());
        root.addOnLayoutChangeListener(lookRelayout);                   /* 화면을 돌리면(액티비티는 그대로) 자리를 다시 */
        lookEditor = sv;
        padMask = 0; releasePhysical();                                  /* 쥐고 있던 버튼이 창이 닫힌 뒤 눌린 채 남지 않게 */
        if (pad != null) pad.setLookCell(false);
        if (hubBanner != null && !practiceHub && !leagueHub && leagueId == null) hubBanner.setVisibility(View.GONE);
    }

    /** 창 자리 — 세로는 아래(패드 자리 — 창이 떠 있는 동안 게임 입력은 막힌다), 가로는 게임 그림 오른쪽 */
    private FrameLayout.LayoutParams lookEditorLp() {
        float dp = getResources().getDisplayMetrics().density;
        int W = root.getWidth(), H = root.getHeight();
        FrameLayout.LayoutParams lp;
        if (W > H) {
            int w = Math.max(Math.round(320 * dp), W - gameBox[2] - Math.round(12 * dp));
            lp = new FrameLayout.LayoutParams(Math.min(w, Math.round(W * 0.48f)), FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
            lp.rightMargin = Math.round(6 * dp);
        } else {
            lp = new FrameLayout.LayoutParams(Math.min(W - Math.round(12 * dp), Math.round(480 * dp)), FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = Math.round(8 * dp);
        }
        return lp;
    }
    private final View.OnLayoutChangeListener lookRelayout = (v, l, t, r, b, ol, ot, or, ob) -> {
        if ((r - l) != (or - ol) || (b - t) != (ob - ot))
            h.post(() -> { if (lookEditor != null) lookEditor.setLayoutParams(lookEditorLp()); });
    };

    /** 창 닫기(저장·취소·뒤로) — 미리보기를 보이는 컬러로 되돌린다 */
    private void lookEditorClose() {
        if (lookEditor == null) return;
        root.removeOnLayoutChangeListener(lookRelayout);
        root.removeView(lookEditor);
        lookEditor = null;
        Emu.nativeLookEdit(false, 0, 0, 0);
    }
}
