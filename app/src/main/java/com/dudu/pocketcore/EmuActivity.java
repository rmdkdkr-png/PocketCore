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
    /* 연습 대전(practice.c) — 일반 세션이고 판정 주소를 확인한 롬 판이면 타이틀 «2 인 대전» 자리가 «연습 대전». 실행 창 링크로 열면 «캐릭터 선택»(내 캐릭터 먼저 → 상대)까지 자동 */
    static final String EXTRA_PRACTICE = "practice_hub";
    private boolean practiceHub = false, practiceAutoUsed = false;
    /* 리그·연습 «둘 다»(실행 창 «리그·연습», pocketcore_ss2_hub) — 타이틀 셋째 줄 ◀▶ 로 연습 대전 ↔ 리그 대전. 리그는 따로 세이브·고정 롬·동봉 코어(랭크전 판)라
       고르면 이 창을 내리고 그 판으로 새로 연다(switchSession). 리그 판으로 갈아탄 창은 EXTRA_HUB_GO 로 로고·메뉴를 넘겨 바로 상대 선택까지 */
    static final String EXTRA_HUB_GO = "hub_go", EXTRA_FROM_GAME = "hub_from_game";   /* FROM_GAME = 일반 판에서 갈아타 왔다(리그를 못 열면 그 게임으로 돌려보낸다) */
    /* 168 K4 — 리그 화면 «멈춘 경기 이어서 하기»로 연 판: 사용자가 멈춘 경기(리셋·«나중에»)도 타이틀에서 기다리지 않고 바로 이어 한다 */
    static final String EXTRA_RESUME_NOW = "hub_resume_now";
    /* 168 §10 — 경기 밖에서 설정을 다시 올린 판: 로고만 넘겨 타이틀(리그 켠 채)로(찾기는 안 함) */
    static final String EXTRA_HUB_TITLE = "hub_title";
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
        hubRestore(b);                                                   /* 168 고침: 다시 만든 창(설정 바뀜·프로세스가 죽은 뒤 최근 앱) — 같은 인텐트의 RESUME_NOW 를 또 쓰지 않게 */
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
        if (leagueHub) lang = League.matchLang(League.openMatch(), lang);   /* 168 §10: 시작한 경기가 있으면 그 경기의 롬 판(언어)으로 — 언어를 바꿔도 «롬이 바뀜»으로 막히지 않게(새 언어는 다음 리그 판부터) */
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
            private int qHeld;                    /* 153 겹창이 내려갈 때 쥐고 있던 키 — 뗄 때까지 게임에 안 준다(겹창 동안 누른 A·B 가 고르기 화면·타이틀에 새지 않게) */
            @Override public void onDrawFrame(GL10 g) {
                int in = padMask | keyMask | axisMask;
                if (rankQBlock) { qHeld = in; in = 0; }   /* 153 찾기 겹창 동안 게임엔 아무것도 안 간다 */
                else if (qHeld != 0) { qHeld &= in; in &= ~qHeld; }
                LeagueMenu mm = lgm;                      /* 157 리그 인게임 메뉴 — 열린 동안 게임엔 0 · 쉼 자격이면 OPTION 탭 = 쉼 */
                if (mm != null) in = mm.feed(in, System.nanoTime());
                Emu.nativeSetInput(in);
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
            @Override public void onMask(int mask) { padMask = mask; if (rankQ != null || rankQFading != null) rankQKey(mask | keyMask | axisMask); }   /* 153 겹창 B = 그만(내려가며 사라지는 동안도) */
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
                if (open) { releasePhysical(); refreshSlotInfo(); cheatCellSync(); }
                if (fdView != null && fdWanted) fdView.setVisibility(open || sheetUp() ? View.GONE : View.VISIBLE);   /* 프레임 미터가 버튼 아래 줄에 서면 메뉴 칸(저장·로드) 위를 덮는다 — 메뉴 동안 숨김(176: 창이 떠 있어도) */
                if (!loaded) return;
                Emu.nativeSetPaused(open || (sheet != null && sheet.isShowing()) || (leagueDlg != null && leagueDlg.isShowing())
                        || (leagueId != null && leagueArmed && nativeDone() && postState() != 1)   /* 결판이 잡혀 정산을 기다리는 동안은 멈춘 채로(153: 승리 대사 중엔 닫으면 대사가 이어 돈다 — 끝은 native 걸쇠가 멈춘다) */
                        || hubCard != null || lgmShown);                         /* 게임 속 리그 결과 카드(157 결판 메뉴)가 떠 있는 동안도 */
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

    /** 터치 패드 표시 — auto: 물리 게임패드가 붙어 있거나(장치 목록) 이번 판에 패드로 게임 입력이 들어왔으면 숨기고 메뉴 알약만 남긴다.
     *  화면을 만지면 다시 나온다(PadView). off(숨김) = 늘 숨김. 151: 전엔 PadView 상수로 숨김이 꺼져 있어 둘 다 아무 일도 안 했다 */
    private String touchpadOpt = "auto";
    private int padHideMode() {
        return "off".equals(touchpadOpt) ? 2 : ("auto".equals(touchpadOpt) && (physSeen || KeyMap.physicalPresent())) ? 1 : 0;
    }
    private void applyPadMode(java.util.Map<String, String> m) {
        touchpadOpt = or(m.get("pocketcore_touchpad"), "auto");
        if (pad != null) pad.setPhysicalMode(padHideMode());
        Theme.init(this); Theme.refresh();      /* 121: 버튼 모양이 테마를 따른다 — 설정에서 바꾸고 돌아오면 새로 굽는다 */
        if (pad != null) pad.setArt(!"flat".equals(or(m.get("pocketcore_padskin"), "art")));
        if (pad != null) pad.setPadAlpha(intOf(m.get("pocketcore_pad_alpha"), 100));     /* 122: 버튼 투명도 */
    }
    private final android.hardware.input.InputManager.InputDeviceListener devListener =
            new android.hardware.input.InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id)   { applyScreenLayout(); }
        @Override public void onInputDeviceRemoved(int id) { axisByDev.delete(id); releasePhysical(); physSeen = false; applyScreenLayout(); }
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
        rankQPlace();                                       /* 153 찾기 겹창 — 게임 그림 자리 그대로 */
        lgmTouchPlace();                                    /* 157 메뉴 터치 — 게임 그림 자리 그대로 */
        if (hubCard != null && hubCard.getLayoutParams() instanceof FrameLayout.LayoutParams) {   /* 결과 카드 — 돌리면 새 게임 그림 위로(랭크 카드는 남는 높이만큼 — 넘치면 민다) */
            FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) hubCard.getLayoutParams();
            cl.topMargin = gameBox[1] + Math.round(8 * getResources().getDisplayMetrics().density);
            hubCard.setLayoutParams(cl);
        }
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
            coreLabel = game.ko + " · 동봉 코어(리그)" + suffix;
            return getApplicationInfo().nativeLibraryDir + "/" + game.core;
        }
        /* 154: 늘 동봉 코어 — 밖에 둔 코어(PocketCore/cores/*.so)·「업데이트 확인」이 받아 둔 코어(files/cores)는 안 쓴다.
           인터넷·저장소에서 온 실행 코드를 dlopen 하는 모양이 Play 프로텍트 «유해한 앱» 판정 거리(유저 「유해한앱이라고 안뜨게해줭」).
           새 코어는 새 APK 로 */
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
        /* 175: 리그 판(ranked)은 코어 이름 알림(«… · 동봉 코어(리그)»)을 안 띄운다 — 리그 판을 열 때·경기 중 설정 «적용하고 이어하기»로 다시 올릴 때마다
           뜨던 안내 글(유저 «안내 글을 보고 싶지 않음»). 실패 글은 그대로. 코어 이름은 경기 기록(League.note «core»)에 남는다 */
        if (!loaded || !ranked()) toast(loaded ? coreLabel : "코어/롬 로드 실패 (code " + rc + ")");
        /* 컬러(look)는 이어하기 «앞»에 켠다 — 그래야 불러오기(look_after_load)가 경기 컬러·베기 색을 첫 프레임부터 넣는다(165 리뷰: 뒤에 켜면
           불러온 첫 프레임이 원래 색으로 한 번 보였다). lookSetup 은 resumed·연습·리그 칸 설정과 상관없다 */
        lookSetup();
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
        if (ranked()) {
            switch (action) {
            case PadView.ACT_SAVE: case PadView.ACT_QSAVE: case PadView.ACT_LOAD: case PadView.ACT_UNDO: case PadView.ACT_SLOT:
                return;                                     /* 168(3b): 리그 판엔 저장·불러오기가 없다 — 칸은 숨겼고(setRankedBar) 남은 길은 조용히(글 없음) */
            case PadView.ACT_RESET:
                h.post(this::leagueReset); return;          /* 168(3b): 리셋 = 경기를 저장하고 타이틀로(A 로 이어서) — 바가 닫히며 멈춤을 푼 뒤 */
            default: break;                                 /* 설정(§10)·치트(§9)·나머지는 아래 평소 길 */
            }
        }
        if (ranked() && (action == PadView.ACT_PICK || action == PadView.ACT_QUIT)) {
            /* 메뉴 바가 닫히며 멈춤을 푸는(menuChanged) 것보다 뒤에 처리한다.
               157 post(유저 «랭매 등 들어가고 나오는 거 자유롭게, 끊김 없이, '나가나요?' 같은 것 하지 않는다»): 점수가 걸린 경기 중이 아니면 묻지 않고 바로 —
               «목록» = 게임 목록 · «종료» = 앱 종료(고르기 화면의 준비 기록은 무효로 닫고). 경기 중이면 쉼(덧판) — 쉼이 나가기 메뉴(나중에·기권) */
            h.post(new Runnable() { @Override public void run() {
                if (lgm != null && lgm.isOpen() && !lgmShown) return;                        /* 쉼이 이미 나가기 메뉴 */
                if (lgmEligible(true)) { gl.queueEvent(lgm::requestPause); return; }           /* 랭크 경기 중 — 쉼 */
                if (leagueHub && !hubMatchLive()) { hubLeaveTo(action); return; }              /* 타이틀·고르기·결판 뒤 — 바로 */
                if (leagueId != null && !hubPickBack()) leagueLeave(); else if (leagueHub) hubLeaveTo(action);
            } });
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
            cfgSnap = reloadSnap();                            /* 168 §10: 리그 판은 다시 올려야 먹는 값(원버튼·컬러 고르기)이 바뀐 때만 체크포인트 길 */
            sheet = LaunchSheet.showInGame(this, game, game != null ? game.ko : new File(orig).getName(),
                    new Runnable() { @Override public void run() { if (ranked()) applyLiveLeague(orig); else applyLive(orig); } },
                    new Runnable() { @Override public void run() {
                        startActivity(new Intent(EmuActivity.this, SettingsActivity.class).putExtra("rom", orig)); } });
            sheet.cheatRom = new File(orig);                   /* 치트 «세이브에 남기기» — 세이브 보관 이름 */
            sheet.leagueSession = ranked();                    /* 168: 리그 판 — 치트 창에 올카드 줄 없음 */
            if (game != null && Cheats.GAME.equals(game.id) && !cheatOk) sheet.cheatOff = "이 롬 판에선 치트가 안 켜져요";
            final int[] cg0 = cheatGame();
            sheet.onClose = new Runnable() { @Override public void run() {
                Emu.nativeSetPaused(pad.isBarOpen());          /* 메뉴가 아직 열려 있으면 계속 멈춤 */
                applyDisplay(); pushCoreOpts();
                cheatClose(cg0, false); } };                   /* 치트 창에서 바꾼 값 — 다시 열지 않고 바로 · 175: 리그 판은 글 없음(알약 «치트 ON») */
            break; }
        case PadView.ACT_PICK:
            goList();
            break;
        case PadView.ACT_LOOK:                             /* 멈춤 메뉴 «컬러 만들기» — 바가 닫히며 멈춤이 풀린 뒤에 창을 연다(미리보기는 게임이 돌아야 그려진다) */
            h.post(new Runnable() { @Override public void run() { int[] v = lookV; if (v != null && v.length >= 14 && v[1] == 1) openLookEditor(v[12], v[13]); } });
            break;
        case PadView.ACT_LEAGUE:                           /* 148 멈춤 바 «리그 대전» — 바가 닫히며 멈춤을 푼 뒤에(확인 카드가 그 뒤에 떠야 멈춤을 쥔다) · 168 리그 판에선 «리그 메뉴» */
            h.post(new Runnable() { @Override public void run() { if (leagueHub) leagueMenuCell(); else leagueFromGame(); } });
            break;
        case PadView.ACT_CHEAT:                            /* 168 §9 화면 «치트» 알약 — 치트 창 바로 */
            openCheatSheet();
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
        Emu.nativeSetSoundFx(!"disabled".equals(m.get(Settings.SND_FX)));   /* 176 음색 다듬기 — 값 하나라 바로(소리 카드 콜백이 다음 묶음부터 봄) */
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

    /* ── 168 §10 리그 판 «적용하고 이어하기» ──
       리그 사본(Patcher.resolveLeague)은 원본 + 한글패치만 굽는다 — 화면·필터·프레임 패치·런어헤드 등은 창을 닫을 때(onClose) 바로 먹고,
       다시 올려야 먹는 것은 원버튼(ngp_ss2sp — 코어가 올릴 때만 읽음) · 컬러 고르기(lookSetup 은 loadCore 에서만) · 언어(롬 사본이 바뀜) 셋뿐(검토 S1).
       그 셋이 바뀌었으면: 점수가 걸린 경기면 체크포인트 → 리그 판을 다시 올림(새 창의 hubStart 가 그 경기를 체크포인트로 이어 함), 경기 밖이면 그냥 다시 올림.
       언어만 바뀐 경기 중이면 다시 올리지 않는다 — 그 경기는 시작한 롬 판으로 고정(matchLang)이라 올려도 같은 판이고, 경기가 끝나 타이틀에 서면 한 번 다시 올린다(M10). */
    private String cfgSnap;                          /* 창을 열 때 «다시 올려야 먹는 값» 셋 */
    private boolean cfgReloadPending = false;        /* 경기가 끝나면(타이틀) 다시 올릴 것 — 언어·결판이 막 선 판 */
    private String cfgSnapLoaded;                    /* 이 판을 올린 값(hubStart) — 미뤄 둔 다시 올리기는 지금 값이 이것과 다를 때만 */
    private String reloadSnap() { return readOpt("ngp_ss2sp", "enabled") + "|" + Look.on() + "|" + readLang(); }
    private static String langOf(String snap) { int i = snap != null ? snap.lastIndexOf('|') : -1; return i >= 0 ? snap.substring(i + 1) : ""; }
    private static String headOf(String snap) { int i = snap != null ? snap.lastIndexOf('|') : -1; return i >= 0 ? snap.substring(0, i) : ""; }

    private void applyLiveLeague(final String orig) {
        if (leaving || !loaded) return;
        String now = reloadSnap();
        boolean other = !headOf(now).equals(headOf(cfgSnap)), langCh = !langOf(now).equals(langOf(cfgSnap));
        cfgSnap = now;
        if (!other && !langCh) return;                                    /* 다시 올릴 값은 그대로 — 나머지는 onClose 가 이미 넣었다 */
        if (!leagueHub) { leagueSuspend("설정 다시 올리기", null); return; }   /* 옛 한 판(EXTRA_LEAGUE — 쓰이지 않음) */
        final boolean live = hubMatchLive();                              /* 결판이 막 섰으면 여기서 정산 → false */
        if (leagueId != null && leagueDone) { cfgReloadPending = true; return; }   /* M10: 결판부터 — 바뀐 값은 타이틀에서 한 번 다시 올림 */
        if (live && !other) {                                             /* 언어만 — 이 경기 롬 판에서 그 언어가 되면(순정 판 ja↔en — 같은 롬, 코어 언어만) 지금 다시 올리고,
                                                                             아니면(한글패치 판 ↔ 원어 — 롬 사본이 달라 이 경기는 시작한 판 그대로) 경기가 끝나 타이틀에서 */
            String nl = langOf(now);
            if (nl.equals(lang) || !nl.equals(League.matchLang(lm(), nl))) { cfgReloadPending = true; return; }
        }
        if (!live && !other) {                                            /* 168 고침: 경기 밖 언어만 — 멈춘 경기(리셋·«나중에»)가 있으면 새 창도 그 경기 판(matchLang)으로 고정된다.
                                                                             그 판이 지금 창의 언어와 같으면 올려도 바뀌는 게 없다(까만 화면만) — 그 경기가 끝나 타이틀에 서면 한 번 */
            org.json.JSONObject open = League.startedOpenMatch();
            if (open != null && League.matchLang(open, langOf(now)).equals(lang)) { cfgReloadPending = true; return; }
        }
        if (live && !lgmShown && lgm != null && lgm.isOpen()) lgmCloseNow();
        reloadLeague(live);
    }
    /** 리그 판을 다시 올린다 — live 면 체크포인트를 먼저(실패면 그대로 둔다). 새 창은 «준비가 아닌 열린 경기»를 체크포인트로 이어 한다(사용자 멈춤 표지 없음 — 타이틀에서 기다리지 않음) */
    private void reloadLeague(final boolean live) {
        if (leaving) return;
        final String orig = getIntent().getStringExtra("rom");
        final String id = leagueId;
        final int cb0 = live ? cheatBitsNow() : 0;
        leagueChoosing = live;
        Emu.nativeSetPaused(true);
        gl.queueEvent(() -> {
            if (live && (!loaded || !leagueSaveCkpt())) {
                h.post(() -> { leagueChoosing = false; toast("저장하지 못했어요 — 그대로 둡니다"); Emu.nativeSetPaused(barOpen()); });
                return;
            }
            final int cb = live ? cb0 | cheatMatch() : 0;
            Emu.nativeLeagueDisarm(); Emu.nativeSaveSram(); Emu.nativeUnload(); loaded = false;
            runOnUiThread(() -> {
                if (live && id != null) League.orCheat(id, cb); else cancelReady("설정 다시 올리기 — 대진 전");
                leaving = true; liveHubOff(); lgmReset(); MainActivity.forgetLast(this);   /* 코어는 내렸다 — 덧판은 새 창이 새로 */
                Intent i = new Intent(this, EmuActivity.class).putExtra("rom", orig).putExtra(EXTRA_LEAGUE_HUB, true);
                if (!live) i.putExtra(EXTRA_HUB_TITLE, true);                /* 경기 밖 — 새 창은 로고만 넘겨 타이틀로(이어 할 경기는 hubStart 가 먼저 본다) */
                if (fromGame()) i.putExtra(EXTRA_FROM_GAME, true);
                try { startActivity(i); } catch (Exception e) { android.util.Log.w("PocketCore", "reloadLeague: " + e); }
                overridePendingTransition(0, 0);
                finish();
                overridePendingTransition(0, 0);
            });
        });
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
        if (lgm != null && (lgmShown || lgm.isOpen())) { gl.queueEvent(lgm::back); return; }   /* 157 메뉴 — 뒤로 = B */
        if (hubCard != null) { hubReturn(-1); return; }      /* 결과 카드에서 뒤로 = 계속 */
        if (lgmEligible(true)) { if (pad != null && pad.isBarOpen()) pad.barClose(); gl.queueEvent(lgm::requestPause); return; }   /* 157 랭크 경기 중 뒤로 = 쉼 */
        if ((rankQ != null || rankQFading != null) && !(pad != null && pad.isBarOpen())) { rankQBack(); return; }   /* 153 찾기 겹창(사라지는 중 포함) — 그만(상대를 보였으면 잡힌 채). 바가 열려 있으면 멈춘 채 아래(hubLeave)로 */
        if (hubPickBack()) { hubReturn(-1); return; }         /* 리그 판 고르기 화면 — 확인 창 없이 바로 타이틀로(유저 「타이틀로 가는 거 메시지 없이」) */
        if (leagueId != null) { leagueLeave(); return; }
        if (leagueHub) { hubLeaveTo(0); return; }            /* 157 post: 타이틀에서 뒤로 = 묻지 않고 온 곳으로(게임에서 왔으면 그 게임 · 아니면 리그 화면) */
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

    /** 이번 판에 물리 패드로 게임 입력이 들어왔나 — 장치 목록에 게임패드로 안 잡히는 패드(키보드·십자 출처로 오는 것)도 터치 버튼 자동 숨김에 넣는다 */
    private boolean physSeen = false;
    private void onPhysicalInput() {
        if (!physSeen) { physSeen = true; if (pad != null) pad.setPhysicalMode(padHideMode()); }
        if (pad != null) pad.physicalInput();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (KeyMap.isVolume(e.getKeyCode())) return super.dispatchKeyEvent(e);   /* 음량은 늘 시스템으로 — 창이 떠 있어도 */
        if (ask != null && ask.hasField() && !KeyMap.isGamepad(e) && e.getKeyCode() != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);   /* 168(11) 글 칸 카드 — 키보드 글자는 칸으로 */
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
                    onPhysicalInput();
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
        if ((all & ~axisPrev) != 0) onPhysicalInput();   /* 새로 누른 방향 — 터치 버튼 자동 숨김 */
        axisPrev = all;
        axisMask = all;                        /* 장치별로 들고 OR — 두 번째 장치의 중립이 첫 장치의 방향을 지우지 않게 */
        return true;
    }

    @Override protected void onPause() {
        super.onPause();
        resumedNow = false;
        /* 153 찾기 겹창은 그대로 — 코어가 멈춰(gl.onPause) native 자동 진행도 멈추고 겹창 시계도 선다(rankQStep · resumedNow). 돌아오면 이어서 */
        releasePhysical();
        try { ((android.hardware.input.InputManager) getSystemService(INPUT_SERVICE))
                .unregisterInputDeviceListener(devListener); } catch (Exception ignored) { }
        if (lookOn) { lookSaveMem(); lookSaveFx(); }   /* 컬러 기억·베기 색 — 폴(200ms)이 아직 못 남긴 마지막 바뀜도(다시 굽기·갈아타기·목록으로 창이 닫히기 전에).
                                                    loaded 는 안 본다: 갈아타기·다시 굽기는 GL 스레드에서 먼저 내려 이미 거짓이고, native 기억은 코어 없이도 남는다 */
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
                if (!leagueDone && m != null && !"ready".equals(m.optString("status"))) { leagueCheatSync(); leagueSaveCkpt(); }   /* 168 §9: 내려가기 전까지 먹은 치트도 기록에 */
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
        if (leagueId != null && leagueArmed && !leagueDone) League.ckptDelete(leagueId);   /* 다시 올라왔다 — 경기는 이어서 돈다(.look 도) */
        if (leagueId != null && leagueArmed && leagueDone && !leaving) {                    /* 백그라운드에서 정산됐다 — 이제 결과 창으로 */
            if (postState() == 1) { h.removeCallbacks(leaguePoll); h.post(leaguePoll); }      /* 153: 승리 대사 도중 내려갔다 — 대사를 마저 보고 */
            else leagueExit(leagueId);
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
        if (fresh) League.ckptDelete(leagueId);
        else {
            boolean had = ck.exists();
            if (had) fromCk = Emu.nativeLoadState(ck.getAbsolutePath()) == 0;
            if (!fromCk) {
                League.setResumeFail(leagueId, had ? "badckpt" : "nockpt");
                League.setStatus(leagueId, "resume", had ? "체크포인트를 불러오지 못함" : "체크포인트 없음");
                leagueBlocked(had ? BLK_BADCKPT : BLK_NOCKPT, null);
                return;
            }
            League.ckptDelete(leagueId);                      /* 한 번 쓰면 끝(.look 은 불러오며 이미 읽었다) */
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
        leagueCheatSeen = m.optInt("cheat_bits");                  /* 168 §9: 멈추기 전까지 먹은 치트는 기록에 이미 — native 비트는 무장이 비웠다 */
        if (fromCk) League.setStatus(leagueId, "playing", "");     /* 168 K3: 이어 하기에 성공 — 사용자 멈춤 표지도 지운다(그 뒤 진짜 앱 멈춤은 예전 규칙) */
        if (leagueHub) lgmTagsArm();                                 /* 157 post: 이어 하는 랭크 경기도 HUD 이름표(체크포인트 불러오기 뒤 — native 가 불러오며 지웠다) */
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
            League.setStatus(leagueId, "playing", "");                  /* 175: 치트 알림 글 없음 — 알약 «치트 ON» 만 */
        }
    }

    private boolean nativeDone() {
        int[] st = Emu.nativeLeagueStatus();
        return st != null && st.length >= 21 && st[4] == 1;
    }

    /** 153: 결판 뒤 승리 대사 — 0 없음 · 1 게임이 대사까지 도는 중(결과는 이미 정산) · 2 대사 끝 걸쇠(native 가 게임을 멈춰 둠 — 결과 카드 차례).
     *  옛 native(칸 22개)면 −1 — 예전처럼 결판 자리에서 멈추고 바로 결과 */
    private int postState() {
        int[] st = Emu.nativeLeagueStatus();
        return st != null && st.length > 22 ? st[22] : -1;
    }
    /** 153: 승리 대사 동안 게임을 멈춰 둘 이유 — 메뉴 바·옵션 창·확인 카드. 없으면 대사는 돈다(정산 전에 걸린 멈춤이 남지 않게 poll 이 다시 맞춘다) */
    private boolean postPaused() { return (pad != null && pad.isBarOpen()) || (sheet != null && sheet.isShowing()) || ask != null || hubCard != null || lgmShown; }
    /** 승리 대사가 도는 중이면 true — 멈춤을 맞추고 100ms 뒤 다시 본다(화면에 떠 있을 때만 — 내려가 있으면 onResume 이 다시 건다).
     *  결판 순간엔 아무 글도 안 띄운다 — 결과는 대사가 끝난 뒤 카드로만(유저 «승부 정해지고 끝나고 메시지»). B·뒤로 건너뛰기는 말없이 된다 */
    private boolean postWait() {
        if (leaving || postState() != 1) return false;
        if (resumedNow) {
            Emu.nativeSetPaused(postPaused());
            h.postDelayed(leaguePoll, 100);
        }
        return true;
    }
    /** 승리 대사 건너뛰기(뒤로 가기) — native 가 다음 진짜 프레임에 걸쇠를 세운다. 메뉴 바로 멈춰 있으면 닫는다(그래야 그 프레임이 돈다) */
    private void postSkip() {
        Emu.nativeLeaguePostSkip();
        if (pad != null && pad.isBarOpen()) pad.barClose();
        Emu.nativeSetPaused(postPaused());
        h.removeCallbacks(leaguePoll); h.post(leaguePoll);
    }

    /** 결판이 잡혀 있으면 바로 정산한다. 잡혀 있으면 true — 정산 저장이 실패했어도(그때는 체크포인트를 쓰지 않고, 결과는 코어 쪽에 둔 채 poll 이 다시 시도). */
    private boolean leagueSettleNow() {
        if (leagueDone) return true;
        if (!leagueArmed) return false;
        int[] st = Emu.nativeLeagueStatus();
        if (st == null || st.length < 21 || st[4] != 1) return false;
        /* 153: 정산은 결판 프레임 값으로 바로(대사 중에 나가도·꺼져도 결과는 같다) — 게임은 멈추지 않고 승리 대사까지 돈다(입력은 native 가 막음).
           대사가 끝난 프레임에 native 걸쇠가 멈춘다 — 다음 장·이어하기 화면으로는 안 간다. 옛 native 면 예전처럼 그 자리에서 */
        if (st.length <= 22) Emu.nativeSetPaused(true);
        if (League.settle(leagueId, st, cheatBitsNow()) == null) return true;   /* 168 §9: 먹은 치트 — 그 치트가 설명하는 검사만 면제 · «치트» 표 */
        leagueDone = true;
        Emu.nativeLeagueDisarm();
        return true;
    }

    /** 체크포인트 — 임시 파일에 쓰고 이름을 바꿔 넣는다(쓰다 죽어도 반쪽 파일이 안 남는다). 실패하면 옛 것을 그대로 둔다.
     *  171: 상태 옆 .look(nativeSaveState 가 씀)도 같이 옮긴다 — 경기 베기 색(fx_sel)은 경기 기록에 없고 .look 에만 있다(지우면 이어 한 판이
     *  끝까지 원래 베기 색). 몸 경기 컬러는 그대로 기록이 정한다: leagueStart 가 불러온 바로 뒤 nativeLeagueLook 으로 덮는다(옛 기록이면 −1).
     *  .look 은 상태 크기·지문이 맞을 때만 쓰이니(native look_side_read) 남은 옛 것이 다른 상태에 붙지 않는다 */
    private boolean leagueSaveCkpt() {
        File ck = League.ckpt(leagueId), tmp = new File(ck.getPath() + ".tmp");
        File tl = new File(tmp.getPath() + ".look"), cl = League.ckptLook(leagueId);
        if (Emu.nativeSaveState(tmp.getAbsolutePath()) != 0) { tmp.delete(); tl.delete(); return false; }
        if (!tmp.renameTo(ck)) { tmp.delete(); tl.delete(); return false; }
        if (!tl.exists()) cl.delete();                      /* 컬러·베기 색 없는 판 — 지난 체크포인트의 .look 을 남기지 않는다 */
        else if (!tl.renameTo(cl)) { tl.delete(); cl.delete(); }   /* 못 옮기면 .look 없는 체크포인트(원래 베기 색 — 예전과 같음) */
        return true;
    }

    /** 결판 감시 — 진짜 프레임에서 league.c 가 잡은 결과를 가져와 한 번만 정산한다. 화면에 떠 있을 때만 리그 화면으로 넘어간다. */
    private final Runnable leaguePoll = new Runnable() { @Override public void run() {
        if (leagueId == null) return;
        lgmEligibleSync();
        if (leagueDone) {
            lgmOnSettle();                                     /* 157 결판 메뉴 — 정산한 판이면 한 번만 걸어 둔다(걸쇠에 맞춰 열림) */
            if (postWait()) return;                            /* 153: 승리 대사 도는 중 — 끝(걸쇠)·B·뒤로를 기다린다 */
            leagueSaveAskClose(); if (resumedNow && !leaving) leagueExit(leagueId); return;
        }
        if (!loaded || leaving) return;
        if (lm() == null) { leagueVanished(); return; }    /* 경기 기록이 사라졌다(다시 읽기 등 — 있을 수 없는 일) */
        leagueSyncStarted();
        leagueCheatSync();                                 /* 168 §9: 새로 먹은 치트 → 경기 기록 */
        lgmTagsWatch();                                    /* 165: 이름표가 꺼졌으면 다시 걸고 · 오래 안 보이면 까닭을 남김 */
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
            lgmOnSettle();                                     /* 157: 결판 장면을 미리 그려 걸쇠에 걸어 둔다 */
            if (postWait()) return;                            /* 153: 결과는 적었다 — 승리 대사 뒤 카드 */
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
                h.removeCallbacks(leaguePoll);                 /* 175: 알림 글 없음 */
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
                    .btn(leagueHub ? "타이틀로" : "리그 화면으로", new Runnable() { @Override public void run() { leagueExit(null); } })   /* 168: 게임 속 리그는 타이틀로(리그 화면으로 안 나감) */
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
            boolean hubOn = leagueHub && myLiveGen != 0;         /* 168: 허브가 켜진 판 — 타이틀로 · 허브를 못 켠 판 — 게임 목록(리그 화면으로 안 나감) */
            Ask d0 = ask(leagueId != null ? "멈춘 리그 경기를 이어할 수 없어요" : "리그 대전을 열 수 없어요", msg)
                    .cancelable(false);
            if (!leagueHub) d0.btn("리그 화면으로", new Runnable() { @Override public void run() { leagueExit(null); } });
            else if (hubOn) d0.btn("타이틀로", new Runnable() { @Override public void run() { leagueDone = false; hubReturn(-1); } });
            else if (!fromGame()) d0.btn("게임 목록", new Runnable() { @Override public void run() { leagueHub = false; goList(); } });
            if (fromGame())                                  /* 일반 판에서 갈아타 왔다 — 그 게임으로도 */
                d0.btn("게임으로", new Runnable() { @Override public void run() { switchSession(false, false); } });
            d0.show();
        }});
    }
    private boolean fromGame() { return leagueHub && getIntent().getBooleanExtra(EXTRA_FROM_GAME, false); }

    /* ── 막힘 카드(148 설계 3.5) — 카드마다 그 자리에서 앞으로 가는 단추. 모두 취소 못 하는 확인 카드(첫 키는 고르기만 — 패드 실수 막기),
       기권은 한 번 더 묻는다(터치 실수 막기) ── */
    private static final int BLK_NOCKPT = 1, BLK_BADCKPT = 2, BLK_ROM = 3, BLK_DB = 4, BLK_WRITE = 5;
    private String autoVoidId;                                   /* 157 post: 묻지 않고 무효로 닫아 본 경기(실패하면 카드로 — 다시 안 시도) */

    /** kind: 경기(NOCKPT·BADCKPT·ROM — 카드 A·B·B′, 무효 가능 여부는 League.voidKind 가 정한다) · 저장소(DB·WRITE — 카드 C). why = 막힌 사유(ROM·저장소) */
    private void leagueBlocked(final int kind, final String why) {
        if (kind == BLK_DB || kind == BLK_WRITE) { storageBlocked(kind); return; }
        final org.json.JSONObject m = lm();
        if (m == null) { leagueVanished(); return; }
        leagueDone = true;
        String vk = League.voidKind(m, League.appVer);
        final boolean userSusp = League.userSuspended(m);
        if (vk == null && kind != BLK_ROM && !userSusp) vk = kind == BLK_BADCKPT ? "ckpt" : "crash";   /* 기록이 따라오지 못했어도(저장 실패) 이 카드의 종류로 · 168 K3: 사용자가 멈춘 판은 무효 없음(기권만) */
        final String voidKind = vk;
        /* 168 K3: 사용자가 멈춘 판(체크포인트가 없음·못 읽음)도 같은 A 카드 — 무효 단추만 없다(기권하고 계속 · 타이틀로) */
        String look = vk != null ? vk : userSusp && kind != BLK_ROM ? (kind == BLK_BADCKPT ? "ckpt" : "crash") : null;
        final boolean hub = leagueHub, single = !leagueHub;
        String title, body;
        org.json.JSONObject snap = m.optJSONObject("opp_snap");
        String vs = "vs " + League.oppNick(m) + (snap != null && !snap.optString("label").isEmpty() ? " · " + snap.optString("label") : "");
        if ("crash".equals(look) || "ckpt".equals(look)) {                          /* A — 앱이 멈춤 · 자리를 못 불러옴 */
            title = "멈춘 경기를 이어할 수 없어요";
            body = vs + "\n" + ("ckpt".equals(look) ? "이어할 자리를 불러오지 못했어요." : userSusp ? "저장해 둔 자리가 없어요." : "앱이 멈춰서 이어할 자리가 없어요.")
                    + (voidKind != null ? "\n무효로 닫으면 LP·전적은 그대로예요." : "\n기권하면 패배로 기록돼요.");
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
        if (hub && voidKind != null && !m.optString("id").equals(autoVoidId)) {   /* 157 post: 이어 할 수 없는 경기(앱이 멈춤·자리 못 불러옴·앱 바뀜)는 묻지 않고 무효로 닫고 바로 찾기 —
                                                                                   LP·전적 그대로(카드의 «무효로 닫고 계속»과 같은 길). 저장에 실패하면 그때만 카드(한 번만 시도) */
            autoVoidId = m.optString("id");
            h.post(new Runnable() { @Override public void run() { if (!leaving) leagueResolve(true, kind, why); } });
            return;
        }
        h.post(new Runnable() { @Override public void run() {
            if (leaving) return;
            Ask d = ask(t, b).cancelable(false);
            if (voidKind != null)
                d.btn(single ? "무효로 닫기" : "무효로 닫고 계속", new Runnable() { @Override public void run() { leagueResolve(true, kind, why); } });
            d.btn(single ? "기권" : "기권하고 계속", new Runnable() { @Override public void run() { leagueResolve(false, kind, why); } });
            d.btn(single ? "리그 화면으로" : "타이틀로", new Runnable() { @Override public void run() { leagueExit(null); } });   /* 168: 게임 속 리그는 타이틀로(멈춘 경기는 그대로 — A 로 다시) */
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
            if (vk == null && League.userSuspended(m)) { leagueBlocked(kind, why); return; }   /* 168 K3: 사용자가 멈춘 판 — 무효 없음 */
            if (vk == null) vk = kind == BLK_BADCKPT ? "ckpt" : "crash";
            if (!League.voidStarted(id, vk)) { toast("저장 실패 — 다시 시도하세요"); leagueBlocked(kind, why); return; }
            if (leagueHub) {                                        /* 175: 알림 글 없음 */
                rankOppV = null;
                hubReturn(-3);                                      /* 리셋 뒤 바로 상대 찾기 — 잡힌 상대면 그 상대가 다시 */
            } else leagueExit(null);
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
        if (League.isRanked(m)) {                              /* 157 G10 — 셈은 League.forfeitPreview(쉼의 두 번 묻기와 같은 값), 글은 그대로 */
            League.ForfeitPreview fp = League.forfeitPreview(m);
            if (fp.kind == League.ForfeitPreview.PLACEMENT) body = "배치 1패로 기록돼요.";
            else if (fp.kind == League.ForfeitPreview.LP && (fp.lpDelta != 0 || fp.demoted || fp.shieldUsed)) {   /* 수련생은 안 깎인다 — ±0 은 빼고(리그 화면과 같게) */
                body = "이 경기는 기권패로 기록돼요 (LP " + RankUi.signed(fp.lpDelta) + ").";
                if (fp.demoted && fp.tierAfter >= 0 && fp.tierAfter < RankCore.TIER.length) body += " " + MenuModel.ro(RankCore.TIER[fp.tierAfter]) + " 강등돼요.";   /* 157: 실제 정산 그대로 — 강등·강등 위기도 */
                else if (fp.shieldUsed) body += " 강등 위기가 돼요.";
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
        if (!leaving && hubCard == null && !lgmShown) Emu.nativeSetPaused(pad.isBarOpen());   /* hubReturn 과 같게 */
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
            else if (leagueHub) d.btn("게임 목록", new Runnable() { @Override public void run() { leagueHub = false; goList(); } });   /* 168: 리그 화면으로 안 나감 */
            else d.btn("리그 화면으로", new Runnable() { @Override public void run() { leagueExit(null); } });
            d.show();
        }});
    }

    /** 뒤로 가기 — 계속 / 나중에 이어하기(체크포인트) / 기권. 결판이 이미 잡혀 있으면 창 없이 정산으로. */
    private void leagueLeave() {
        int ps = leagueDone && leagueArmed && !leaving && hubCard == null && !lgmShown ? postState() : -1;
        if (ps >= 1) { if (ps == 1) postSkip(); else leagueExit(leagueId); return; }   /* 153: 승리 대사 중(·걸쇠 뒤 카드 전) 뒤로 = 바로 결과 카드(카드가 떠 있으면 예전대로) */
        if (leagueDone || !leagueArmed) { leagueExit(null); return; }
        if (leagueSettleNow()) {                              /* 결판이 막 잡혔다(poll 전) — 153: 대사 중이면 위와 같이 건너뛰기(카드를 먼저 띄우면 poll 이 대사를 다시 돌린다) */
            if (leagueDone && resumedNow) { if (postState() == 1) postSkip(); else leagueExit(leagueId); }
            return;
        }
        if (leagueDlg != null && leagueDlg.isShowing()) return;
        leagueSyncStarted();
        if (!leagueStarted) { leagueLeaveBeforeStart(); return; }
        leagueChoosing = false;
        Emu.nativeSetPaused(true);
        leagueDlg = ask("리그 경기 중", leagueHub ? "나중에 = 지금 자리를 저장하고 타이틀로 · A 로 이어서. 기권하면 패배로 기록돼요."
                        : "나중에 이어하면 지금 자리에서 한 번만 다시 열 수 있습니다. 기권하면 패배로 기록됩니다.")
                .btn("계속하기", null)
                .btn(leagueHub ? "나중에" : "나중에 이어하기", new Runnable() {
                    @Override public void run() {
                        leagueChoosing = true;
                        if (leagueSettleNow()) { leagueChoosing = false; return; }   /* poll 이 결과 화면으로 */
                        leagueChoosing = false;
                        leagueSuspend("사용자가 나중에 이어하기", null);      /* 168 §1: 저장 → 타이틀(리그는 켠 채 · A 로 이어서) — 리그 화면으로 안 나감 */
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

    /** 첫 라운드 전(메뉴·캐릭터 고르기·이야기)에 나가기 — 아직 승부가 아무것도 정해지지 않았으니 묻지 않고 무효로 닫는다(점수 없음 · 157 post «'나가나요?' 하지 않는다»). */
    private void leagueLeaveBeforeStart() {
        leagueChoosing = false;
        leagueSyncStarted();
        if (leagueStarted) { leagueLeave(); return; }           /* 그사이 첫 라운드가 시작됐다 — 점수가 걸렸으니 보통 나가기로 */
        if (!League.cancel(leagueId, "경기 시작 전에 사용자가 그만둠")) { toast("저장 실패 — 다시 시도하세요"); return; }
        leagueDone = true; Emu.nativeLeagueDisarm();             /* 175: 알림 글 없음(무효 — LP 그대로) */
        if (leagueHub) { hubReturn(-1); return; }               /* 게임 속 리그 — «무효» 결과 카드 없이 바로 타이틀(고르기 화면 뒤로와 같게 · 리그는 켠 채) */
        leagueExit(leagueId);
    }

    /* ── 168 §A 멈춘 경기 — 체크포인트로 멈추고 타이틀로(리그는 켠 채) · 게임 안에서 이어 하기 ──────────────────────
       리셋(3b)·«나중에»(쉼·옛 나가기 카드)·셋째 줄 «연습 대전»이 같은 길. 멈춘 경기는 타이틀 «리그 대전» A(허브 찾기 6)·대기 «이어서 하기»로 이어 한다.
       기록: 복귀 대기 + 사용자 멈춤 표지 + 그때까지 먹은 치트를 한 번에(League.suspendUser — K3) — 이 표지가 있는 판은 «무효로 닫기»가 없고(지는 판을 멈추고
       체크포인트를 지워 빠지지 못하게) 앱을 다시 켜도 바로 이어 하지 않고 타이틀에서 A 를 기다린다(K4). 이어 하기에 성공하면 표지를 지운다. */

    /** 3b — 바 «리셋»: 열린 덧판·결과 카드·찾기 겹창을 닫고 경기를 저장한 뒤 타이틀로(경기 밖이면 그냥 타이틀로). 글 없음 — 멈췄을 때만 «A 로 이어서» */
    private void leagueReset() {
        if (leaving || !loaded || !leagueHub) return;
        if (ask != null) return;                                            /* 확인 카드가 떠 있다(있을 수 없는 일 — 바가 열려 있었다) */
        if (leagueChoosing) return;                                        /* 이미 멈추는 중 */
        leagueSuspend("사용자가 리셋 — 타이틀로", null);
    }

    /** 점수가 걸린 경기를 체크포인트로 멈추고 타이틀로(리그는 켠 채 — A 로 이어서). 경기 밖이면 그냥 타이틀로(준비 기록은 hubReturn 이 무효로 — 점수 없음).
     *  결판이 이미 섰으면 결판 길(대사 → 결판 메뉴 · 결판이 떠 있으면 타이틀 — M4). 저장 실패면 경기를 계속(저장소 글 한 줄). after = 타이틀로 보낸 뒤 */
    private void leagueSuspend(final String why, final Runnable after) {
        if (leaving) return;
        if (!leagueHub) {                                                   /* 옛 한 판(EXTRA_LEAGUE — 지금 안 씀): 체크포인트 → 복귀 대기 → 리그 화면 */
            if (leagueId == null || !leagueArmed || leagueDone || leagueSettleNow()) return;
            final String id0 = leagueId;
            gl.queueEvent(() -> { final boolean ok = loaded && leagueSaveCkpt(); h.post(() -> {
                if (leagueDone || !id0.equals(leagueId)) return;
                if (!ok || !League.suspendUser(id0, why, cheatBitsNow())) { toast("저장하지 못했어요 — 경기를 계속해요"); Emu.nativeSetPaused(barOpen()); return; }
                leagueDone = true; Emu.nativeLeagueDisarm(); leagueExit(null);
            }); });
            return;
        }
        if (rankQ != null || rankQFading != null) { rankQClose(false); hubVs(null, null, 0, 0); }
        if (leagueId != null && leagueArmed && !leagueDone) { leagueSyncStarted(); leagueSettleNow(); }   /* 결판 프레임이 이미 왔으면 여기서 정산 */
        if (leagueId != null && leagueArmed && leagueDone) {                /* M4: 정산된 판 — 결판을 건너뛰지 않는다 */
            int ps = !lgmShown && hubCard == null ? postState() : -1;
            if (ps == 1) { postSkip(); return; }                            /* 승리 대사 — 건너뛰면 결판 메뉴 */
            if (!lgmShown && hubCard == null) { leagueExit(leagueId); return; }   /* 걸쇠 뒤 결판 전 — 결판 메뉴 */
            rankOppV = null; hubReturn(-1);                                 /* 결판 메뉴가 떠 있다 — 그 «그만»과 같게 타이틀 */
            if (after != null) after.run();
            return;
        }
        if (leagueId == null || !leagueArmed || !leagueStarted) {          /* 경기 밖·대진 전 — 그냥 타이틀로(준비 기록은 무효) */
            rankOppV = null; rankPickOnly = false;
            hubReturn(-1);
            if (after != null) after.run();
            return;
        }
        leagueChoosing = true;
        Emu.nativeSetPaused(true);
        final String id = leagueId;
        final int cb0 = cheatBitsNow();
        gl.queueEvent(() -> {
            final boolean ok = loaded && leagueSaveCkpt();
            final int cb = cb0 | cheatMatch();
            h.post(() -> {
                leagueChoosing = false;
                if (leagueDone || !id.equals(leagueId) || leaving) return;
                if (!ok || !League.suspendUser(id, why, cb)) {
                    if (ok) League.ckptDelete(id);                          /* 기록이 못 따라왔다 — 남은 체크포인트로 되감지 않게(171: .look 도) */
                    toast("저장하지 못했어요 — 경기를 계속해요");
                    lgmCloseNow();
                    Emu.nativeSetPaused(barOpen());
                    return;
                }
                leagueCheatSeen = 0;
                leagueDone = true; Emu.nativeLeagueDisarm();
                rankOppV = null;
                hubReturn(-1);                                              /* 덧판도 닫힘(GL 에서 리셋 전에) · 175: 알림 글 없음(대기 머리 패 «멈춤 · vs …»·A 로 이어서) */
                if (after != null) after.run();
            });
        });
    }

    /** 열린 경기(진행·복귀 대기)를 지금 판에서 이어 한다 — 타이틀 «리그 대전» A(허브 찾기 6)·대기 «이어서 하기»·«끝나지 않은 경기» 카드.
     *  없거나 준비면 false(평소처럼 찾기). 허브가 돌려보내는 중(H_RETURN · 대신 누르기)이면 미뤘다가 타이틀·찾기에 서면 hubTick 이 소비(K2 — 대신 누르기가
     *  체크포인트 경기 입력을 덮지 않게). 허브는 타이틀인 채 싸운다(hubStart 이어 하기와 같은 상태) */
    private boolean hubResumeOpen() {
        if (leaving || !loaded || !leagueHub || leagueId != null) return false;
        org.json.JSONObject open = League.openMatch();
        if (open == null || "ready".equals(open.optString("status"))) { hubResumePending = false; return false; }
        int[] hs = Emu.nativeLeagueHubStatus();
        if (hs == null || hs.length < 9 || !(hs[0] == 1 || hs[0] == 6) || hs[7] != 0 || hs[8] < hubWaitGen || hubCard != null || lgmShown || ask != null) {
            hubResumePending = true;
            return true;
        }
        hubResumePending = false;
        rankQClose(false); rankPickOnly = false;
        if (hs[0] == 6) Emu.nativeHubSearch(false);                        /* H_SEARCH → H_TITLE */
        Emu.nativeSetPaused(true);                                          /* hubStartAgain 과 같은 까닭: 불러오기 → 배경·CPU → 무장이 프레임 사이에 갈라지지 않게 */
        leagueId = open.optString("id"); leagueArmed = false; leagueDone = false; leagueStarted = false; leagueRetry = 0;
        leagueStart();                                                      /* 체크포인트 불러오기 · FROM_CKPT · 이름표 · poll — 막히면 기존 막힘 카드 */
        if (!leaving && hubCard == null && !lgmShown && ask == null) Emu.nativeSetPaused(barOpen());
        return true;
    }
    private boolean hubResumePending = false;
    private boolean barOpen() { return pad != null && pad.isBarOpen(); }

    /** 리그 화면으로 — 결과가 있으면 그 결과 창을 띄운다. 코어는 GL 스레드에서 내린다(goList 와 같은 이유, 내릴 때 native 가 무장도 푼다). */
    private void leagueExit(final String resultId) {
        if (leagueHub && resultId != null) { hubResult(resultId); return; }   /* 게임 속 리그: 결과 카드를 게임 위에 — 나가지 않는다 */
        if (leagueHub && myLiveGen != 0) {                                    /* 168 §1·M3: 게임 흐름에서 LeagueActivity(순위표·리그 화면)로 안 나간다 — 타이틀로(리그는 켠 채) */
            if (leaving) return;
            leagueDone = false;
            hubReturn(-1);
            return;
        }
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

    /** 안내 띠가 게임 그림에 걸쳐 있나(아래 빈 줄이 없어 그림 위쪽 가장자리로 올라갔거나 유저가 옮겨 둠) */
    private boolean bannerOnGame() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hubBanner.getLayoutParams();
        int bw = hubBanner.getWidth() > 0 ? hubBanner.getWidth() : Math.max(0, lp.width), bh = hubBanner.getHeight() > 0 ? hubBanner.getHeight() : Math.max(0, bannerPlacedH);
        return lp.topMargin < gameBox[3] && lp.topMargin + bh > gameBox[1] && lp.leftMargin < gameBox[2] && lp.leftMargin + bw > gameBox[0];
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
       자리(176): 폭은 게임 그림 폭 그대로 — 그림 바로 아래, 그 폭에 패드 버튼이 안 걸치는 줄(PadView.freeSpan) →
       없으면 게임 그림 아래 끝에 반투명으로 겹친다(버튼이 그림 안으로 들어와 있으면 그 위로). 자세한 규칙은 placeFrameMeter. 안내 띠는 미터 아래로 비킨다.
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
            fdView.setVisibility(barOpen() || sheetUp() ? View.GONE : View.VISIBLE);
            placeFrameMeter();
            h.removeCallbacks(fdPoll); h.post(fdPoll);
        } else {
            h.removeCallbacks(fdPoll);
            if (fdView != null) fdView.setVisibility(View.GONE);
            fdBox[0] = fdBox[1] = fdBox[2] = fdBox[3] = 0;
        }
        placeHubBanner();
    }

    /** 176 — 창(설정·치트)이 떠 있나: 프레임 미터를 바처럼 숨긴다 */
    private boolean sheetUp() { return sheet != null && sheet.isShowing(); }

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
        java.util.Map<String, String> st = Settings.load();
        /* 176: 폭 = 게임 그림 폭 그대로 — 왼·오른 끝이 «보이는» 그림과 같다(유저 «프레임미터 가로길이 디스플레이랑 맞춰»). 전엔 화면 폭(세로 W−16dp · 가로 0.62W)이나
           버튼 사이 빈 폭이라 그림과 어긋났다. 크기·자리를 바꾼 그림(scrPct·scrX·scrY)·회전도 placeScreen → 여기서 같은 규칙.
           176 검토: 그림 = fdShown(정수배 맞춤(기본 켬)이면 GL 상자 gameBox 보다 작다 — 1080 폭 세로: 상자 1080 · 그림 160×6 = 960). 상자에 맞추면 미터가 양옆 60px 씩 삐져나왔다.
           자리: ① 그림 바로 아래(4dp 띄움 · 버튼이 걸치면 24dp 까지 6dp 씩 내려 봄) — 그 줄의 [그림 왼끝, 오른끝]에 패드 버튼이 없어야(PadView.freeSpan 이 그 구간을 품음).
           멀리 화면 아래(손이 덮는 줄)까지는 안 내려간다 — 그림에서 떨어지면 «그림과 같은 폭»이 안 보인다.
           ② 없으면 그림 아래 끝에 겹침(반투명 · 같은 폭) — 버튼(가로 OPTION 알약 · 16:9 세로 SP)이 그림 아래로 들어와 있으면 그 위까지 올림(미터가 버튼을 덮어 못 누르게 되지 않게).
              올려도 그림 아래 ⅓ 안까지만 — 더 올라가면 싸움꾼(그림 가운데)을 가린다.
           ③ 그래도 버튼이 걸리면(그림을 버튼 줄까지 내려 둠 — 세로 70% 등) 그림 아래 더 먼 첫 빈 줄(같은 폭·같은 끝) — 미터는 손을 받으니 덮인 버튼(SP·A+B)은 안 눌린다(176 검토).
           ④ 그 줄도 없으면(4:3 가로 등 — 그림이 화면 아래까지) 그림 아래 끝 그대로(옛 겹침 자리). 끌어 둔 자리는 가운데 점을 지키고 폭만 그림 폭.
           그림이 칸 80개 × 2px 보다 좁으면(정수배 1× = 160px — 20% 로 줄인 그림) 미터는 그 최소 폭(FrameMeterView.minWidth)으로 그림 가운데에 — 칸이 잘리지 않게. */
        int[] g = fdShown(st);
        int gx0 = Math.max(0, Math.min(W, g[0])), gx1 = Math.max(0, Math.min(W, g[2]));
        if (gx1 <= gx0) { gx0 = 0; gx1 = W; }                   /* 그림 자리가 아직 없다(첫 배치 전) — 화면 폭 */
        int width = Math.max(gx1 - gx0, Math.min(W, fdView.minWidth()));
        int hh = fdView.heightFor(width), left = Math.max(0, Math.min(W - width, (gx0 + gx1 - width) / 2)), top = -1, step = Math.max(1, Math.round(6 * dp));
        int fx0 = left, fx1 = left + width;                      /* 버튼이 없어야 하는 가로 구간 = 미터 자리 */
        boolean overlay = false;
        float[] pos = null;
        String saved = st.get(fdKey());
        if (saved != null) {
            try { String[] a = saved.split(","); pos = new float[] { Float.parseFloat(a[0]), Float.parseFloat(a[1]) }; } catch (Exception e) { pos = null; }
        }
        fdUserPos = pos != null;
        if (pos != null) {
            left = Math.max(0, Math.min(W - width, Math.round(pos[0] * W - width / 2f)));
            top = Math.max(0, Math.min(H - hh, Math.round(pos[1] * H - hh / 2f)));
        } else {
            int y0 = g[3] + Math.round(4 * dp), yEnd = H - Math.round(4 * dp);
            for (int y = y0; y <= y0 + Math.round(24 * dp) && y + hh <= yEnd; y += step)
                if (fdFree(y, y + hh, fx0, fx1)) { top = y; break; }
            int y1 = g[3] - hh - Math.round(2 * dp);
            if (top < 0)                                        /* ② 바로 아래 빈 줄이 없다 — 그림 아래 끝에 겹쳐(버튼이 들어와 있으면 그 위로 · 아래 ⅓ 안) */
                for (int y = y1, yMin = g[1] + (g[3] - g[1]) * 2 / 3; y == y1 || y >= yMin; y -= step)
                    if (fdFree(y, y + hh, fx0, fx1)) { top = y; overlay = true; break; }
            if (top < 0)                                        /* ③ 그림 아래 끝도 버튼이 걸린다 — 더 아래 첫 빈 줄 */
                for (int y = y0; y + hh <= yEnd; y += step)
                    if (fdFree(y, y + hh, fx0, fx1)) { top = y; break; }
            if (top < 0) { overlay = true; top = y1; }          /* ④ 옛 겹침 자리 */
            top = Math.max(0, Math.min(H - hh, top));
        }
        fdView.setOverlay(overlay);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fdView.getLayoutParams();
        lp.width = width; lp.height = hh; lp.leftMargin = left; lp.topMargin = top; lp.rightMargin = 0;
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        fdView.setLayoutParams(lp);
        fdBox[0] = left; fdBox[1] = top; fdBox[2] = left + width; fdBox[3] = overlay || fdUserPos ? 0 : top + hh;   /* 겹친 자리·옮긴 자리는 안내 띠가 안 피함 */
    }

    /** 미터 줄 [t, b] × [l, r] 에 보이는 패드 버튼이 없나(PadView.freeSpan — 가장 넓은 빈 구간이 [l, r] 를 품음) */
    private boolean fdFree(int t, int b, int l, int r) {
        if (pad == null) return true;
        float[] sp = pad.freeSpan(t, b);
        return sp[0] <= l + 0.5f && sp[1] >= r - 0.5f;
    }

    /** 176 검토: 게임 그림이 화면에 실제로 그려지는 자리 {왼, 위, 오른, 아래} — native.c gl_draw 의 뷰포트 셈 그대로(GL 상자 = gameBox).
     *  정수배 맞춤(설정 pocketcore_integer, 기본 켬)이면 도트 배수 s = min(상자 폭/160, 상자 높이/152) 로 그려 상자보다 작고 가운데(상자가 가로로 길면 세로도 가운데 ·
     *  세로로 길면 위 7% 띄움). 끄면 상자를 채운다(코어 aspect = 프레임 폭/높이). 기둥(288폭) 프레임은 기둥까지 — 상자 밖은 잘린다. */
    private int[] fdShown(java.util.Map<String, String> st) {
        int[] o = { gameBox[0], gameBox[1], gameBox[2], gameBox[3] };
        int gw = gameBox[2] - gameBox[0], gh = gameBox[3] - gameBox[1];
        if (gw <= 0 || gh <= 0) return o;
        int fw = Emu.nativeFrameWidth(), fh = Emu.nativeFrameHeight();
        if (fw <= 0 || fh <= 0) { fw = 160; fh = 152; }
        int sub = (fw >= 320 && fw % 160 == 0 && fh % (fw / 160) == 0 && fh / (fw / 160) >= 152 && fh / (fw / 160) <= 184) ? fw / 160 : 1;   /* 서기 그리기 4배 프레임 */
        int lw = fw / sub, lh = fh / sub, gameW = (lw > 160 && lw <= 320) ? 160 : lw;
        float ar = (float) lw / lh;
        int dw, dh;
        if (!"disabled".equals(st.get("pocketcore_integer"))) {
            int s = Math.max(1, Math.min(gw / gameW, gh / lh));
            dw = lw * s; dh = lh * s;
        } else {
            dh = gh; dw = (int) (gh * ar);
            if (dh * gameW / lh > gw) { dh = gw * lh / gameW; dw = (int) (dh * ar); }
        }
        int y0 = gw > gh ? (gh - dh) / 2 : gh - dh - (int) (gh * 0.07f);   /* GL 아래 기준 */
        if (y0 < 0) y0 = 0;
        int x = (gw - dw) / 2, t = gh - (y0 + dh);
        o[0] = gameBox[0] + Math.max(0, x); o[2] = gameBox[0] + Math.min(gw, x + dw);
        o[1] = gameBox[1] + Math.max(0, t); o[3] = gameBox[1] + Math.min(gh, t + dh);
        return o;
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

    private boolean hubResumeNowUsed = false;
    private static final String KEY_RNOW_USED = "hub_rnow_used";
    /** 168 고침(K4·D7) — EXTRA_RESUME_NOW 는 이 인텐트로 처음 연 창에서 한 번만. 안드로이드가 창을 다시 만들면(목록에 없는 설정 바뀜 · 프로세스가 죽은 뒤 최근 앱에서)
     *  인텐트는 그대로 오고 필드는 새로라, 리셋·«나중에»로 멈춘 경기가 타이틀에서 A 를 기다리지 않고 바로 이어졌다 → 쓴 것을 상태 묶음에 남긴다 */
    @Override protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        o.putBoolean(KEY_RNOW_USED, hubResumeNowUsed || !getIntent().getBooleanExtra(EXTRA_RESUME_NOW, false));
    }
    /** onCreate 첫머리 — 되살린 창이면(b ≠ null) 앞 창이 RESUME_NOW 를 이미 썼는지(모르면 쓴 것으로) */
    private void hubRestore(Bundle b) {
        if (b != null) hubResumeNowUsed = b.getBoolean(KEY_RNOW_USED, true);
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
        cfgSnapLoaded = readOpt("ngp_ss2sp", "enabled") + "|" + Look.on() + "|" + lang;   /* 168 §10: 이 판이 실제로 올린 값(언어는 경기 판으로 고정됐을 수 있다) */
        if (!lang.equals(readLang())) cfgReloadPending = true;          /* 시작한 경기의 판으로 올렸다 — 그 경기가 끝나 타이틀에 서면 새 언어로 한 번 다시 */
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
        lgmSetup();                                                      /* 157 리그 인게임 메뉴(덧판) · 이번 대전 세션(G2) */
        leagueCellSetup();                                               /* 168: 덧판이 있으면 멈춤 바 «리그 메뉴» 칸 */
        pushMain();                                                      /* 153: 내 캐릭터(pref.main) — 있으면 go 마다 native 가 대신 고른다 · 없으면 첫 판만 직접(tail) */
        org.json.JSONObject open;
        boolean resumeOpen = false;
        while ((open = League.openMatch()) != null && "ready".equals(open.optString("status")))   /* 대진 전 경기는 모두 무효로(같은 폴더의 다른 판이 만든 것까지) — 남은 판: 잡힌 상대 miss 하나(두 번째면 풂) */
            if (!League.cancelStale(open.optString("id"), "게임 속 리그를 새로 열며 정리(대진 전)")) break;
        boolean waitOpen = false;
        boolean now = getIntent().getBooleanExtra(EXTRA_RESUME_NOW, false) && !hubResumeNowUsed;
        hubResumeNowUsed = true;                                         /* 168 고침: 열린 경기가 없어도 이 창에서 한 번 본 것으로(그 뒤 이 창에서 멈춘 경기가 다시 만든 창에서 바로 이어지지 않게) */
        getIntent().removeExtra(EXTRA_RESUME_NOW);                       /* 같은 프로세스에서 다시 만든 창도 안 보게(프로세스가 죽은 뒤는 hubRestore 가) */
        if (open != null && !"ready".equals(open.optString("status"))) {
            if (League.userSuspended(open) && !now) waitOpen = true;      /* 168 K4: 사용자가 멈춘 경기(리셋·«나중에») — 타이틀에서 A(대기 «이어서 하기»)를 기다린다 */
            else { leagueId = open.optString("id"); leagueStart(); resumeOpen = true; }   /* 내려간 경기(앱 멈춤·백그라운드·설정 다시 올리기) — 체크포인트로 바로 이어 하고, 결판 뒤 허브로 */
        }
        boolean toTitle = getIntent().getBooleanExtra(EXTRA_HUB_TITLE, false);
        if (!resumeOpen && !hubGoUsed && (toTitle || getIntent().getBooleanExtra(EXTRA_HUB_GO, false))) {
            hubGoUsed = true;
            if (waitOpen || toTitle) hubReturn(-1);                      /* 168 K4·D7: 사용자가 멈춘 경기 — 로고만 넘겨 타이틀(리그 켠 채)에서 A·대기 «이어서 하기»를 기다린다 ·
                                                                              설정 다시 올리기(경기 밖)도 타이틀로 */
            else {
                Emu.nativeHubGo();                                       /* 로고·메뉴를 넘겨 바로 상대 찾기까지 */
                if (League.main() != null || !pickOnlyOk()) rankSearchStart();   /* 153: 로고가 도는 동안부터 찾기 겹창 — 허브가 찾기(6)에 닿으면 바로 go ·
                                                                              168: 내 캐릭터가 없으면 찾기(6)에서 내 캐릭터부터(rankTick) */
            }
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
        hubDualV = Emu.nativeHubDualStatus();                            /* 둘 다 꺼져 있어도 — 메뉴 화면(v[5])으로 띠를 고른다 */
        if (hubDual && hubDualV != null && hubDualV.length >= 5 && hubDualV[2] != 0) {
            if (hubDualV[2] == 2 && !leaving && !leagueChoosing) {          /* 셋째 줄 «연습 대전» — 일반 판으로(168: 막지 않고 글도 없이) */
                org.json.JSONObject m = lm();
                if (leagueId == null || m == null || "ready".equals(m.optString("status")) || leagueDone) {   /* 경기 밖 · 대진 전(무효로 — 점수 없음) · 결판 뒤 */
                    cancelReady("게임 속 리그 — 대진 전에 연습 대전으로");
                    if (hubCard != null) { root.removeView(hubCard); hubCard = null; }
                    lgmCloseNow();
                    switchSession(false, true); return;
                }
                leagueSuspend("연습 대전으로 갈아타기", () -> switchSession(false, true));   /* 시작한 경기 — 저장하고(A 로 이어서) 갈아탐(검토: 실제로는 경기 중 메뉴가 안 보여 방어로만) */
                return;
            }
            Emu.nativeHubHandoffClear();                                 /* 지금은 못 갈아탄다 — 메뉴가 멈춘 채 남지 않게(글 없음) */
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
            lgmEligibleSync(); lgmLobbySync();
            return;
        }
        /* 168 K2: 미뤄 둔 이어 하기 — 허브가 타이틀·찾기에 서고 대신 누르기가 끝난 뒤 */
        if (hubResumePending && (st == 1 || st == 6) && hs[7] == 0 && leagueId == null && !leaving) {
            hubResumeOpen();
            if (leagueId != null) { hubLastState = st; lgmEligibleSync(); return; }   /* 이어 했다 — 아니면(카드·덧판이 아직) 평소 틱 */
        }
        if (rankPickOnly) rankPickOnlyTick(st);                         /* 168 §6 내 캐릭터 먼저 — 다 골랐으면 그다음 상대 */
        /* 168 §10·M10: 경기 동안 미뤄 둔 «다시 올리기»(언어 등) — 타이틀 메뉴에 섰고 열린 것이 없을 때 한 번 */
        if (cfgReloadPending && st == 1 && hs[7] == 0 && leagueId == null && hubCard == null && !lgmShown && ask == null && rankQ == null && !rankPickOnly
                && !(lgm != null && lgm.isOpen()) && !(sheet != null && sheet.isShowing()) && League.startedOpenMatch() == null && resumedNow) {
            cfgReloadPending = false;
            if (!reloadSnap().equals(cfgSnapLoaded)) { reloadLeague(false); return; }
        }
        /* 168 대기 «리그 메뉴»가 타이틀에서 열기를 기다린다(바 칸·뒤로 — 고르기·돌아가는 중에 눌렀다) */
        if (lobbyWanted) {                                               /* 168 고침: 이번 틱 상태로 본다 · 못 열면 남겨 두고 다음 틱에 다시 */
            final LeagueMenu mm = lgm;
            boolean gone = mm == null || leaving || leagueId != null || rankPickOnly || hubResumePending || rankQ != null || lgmShown || hubCard != null
                    || android.os.SystemClock.uptimeMillis() - lobbyWantedAt > LOBBY_WANT_MAX;   /* 경기·찾기·고르기·결판이 시작됐다 — 사용자가 다른 걸 골랐다 */
            if (gone) lobbyUnwant();
            else if (mm.isOpen()) lobbyUnwant();                         /* 이미 열렸다(OPTION 등) */
            else if (st == 1 && hs[7] == 0 && lobbyEligibleAt(st, false)) { lobbyWanted = false; gl.queueEvent(mm::requestLobby); }   /* lobbyViaBack 은 lgmWantLobby 가 읽고 지운다 */
        }
        /* 상대를 골랐다 → 경기 기록(준비). 뒤로 가기 창이 떠 있으면 창이 정한 뒤에(계속하기 → 다음 확인에서 만듦, 나가기·타이틀로 → 안 만듦) */
        if (hubRanked && st == 3 && leagueId == null && hubCard == null && !lgmShown && !(leagueDlg != null && leagueDlg.isShowing())) {
            if (!(rankQ != null && !rankQRevealed) && !rankNewMatch()) return;   /* 153: 겹창이 상대를 보인 뒤에(잡힘과 같은 때 — 보기 전에 그만두면 기록 없음) */
        } else if (st == 3 && leagueId == null && hubCard == null && !lgmShown && !(leagueDlg != null && leagueDlg.isShowing())) {
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
        if (hubRanked) { rankTick(st); rankCapTick(st, hs); }
        lgmEligibleSync();
        lgmLobbySync();
        lgmUiSync();
        /* 결과 카드가 떠 있는 동안은 카드 안내를 그대로 두고, 카드를 닫은 직후(리셋이 GL 스레드에 닿기 전 — 허브는 아직 «경기»)는 «돌아가는 중» */
        /* 이어 하는 경기(체크포인트)는 허브가 타이틀 상태인 채로 싸운다 — 띠는 경기 안내로 */
        int shown = leagueId != null && st <= 2 ? 4 : st == 4 && leagueId == null ? 5 : st;
        if (hubBanner != null && hubCard == null && lgmOpen) hubBanner.setVisibility(View.GONE);   /* 157 메뉴가 열린 동안 안내 띠 숨김(VS 띠 때와 같은 규칙) */
        else if (hubBanner != null && hubCard == null && hubRanked && !hubBannerNeeded(shown)) hubBanner.setVisibility(View.GONE);   /* 157 post: 랭크 판은 아래 안내 띠 없음(정보는 인게임 메뉴) — 직접 고르는 화면만 */
        else if (hubBanner != null && hubCard == null) {
            hubBanner.setText(rankQ != null ? rankQBanner() : hubText(shown, hover, picked, hs[4]));   /* 153 겹창 동안은 상대를 미리 안 보인다 */
            /* 경기 중 띠가 게임 그림 위로 올라가 있으면(가로 화면 — 아래 자리가 없음) 숨긴다: 체력 막대를 경기 내내 가리지 않게(연습과 같은 규칙) */
            boolean over = shown == 4 && !bannerUserPos && ((FrameLayout.LayoutParams) hubBanner.getLayoutParams()).topMargin < gameBox[3];
            if (!over && hubRanked && !bannerDragging && bannerOnGame()) {                  /* 153 띠가 게임 그림 위(가로 화면 등) — 찾기 겹창 머리·VS 띠(序章~배너 2~4)를 가리지 않게. 직접 고르기 화면(VS 1)엔 그대로 ·
                                                                                               5(인트로 끝 ~ 첫 라운드 조작 전 — 허브는 아직 3)도 숨김: 체력 막대 자리에 한순간 다시 뜨지 않게(첫 라운드면 VS 가 0 이 되고 위 규칙) */
                int[] vs = rankQ == null ? vsStatus() : null;
                int vp = vs != null ? vs[0] & 15 : 0;
                over = rankQ != null || (vp >= 2 && vp <= 5);
            }
            hubBanner.setVisibility(over || shown == 5 ? View.GONE : View.VISIBLE);   /* 5 = 타이틀·상대 선택으로 돌아가는 중 — 안내 없이 */
        }
    }

    /** 157 post — 랭크 판의 아래 안내 띠(유저 «아래 메시지 박스는 무용» — 판·상대·LP 정보는 인게임 메뉴·VS 띠·HUD 이름표로 옮김)는
     *  게임 고르기 화면을 직접 밟을 때만(첫 판 내 캐릭터 고르기 · 자동이 막혀 직접 고르기 — 고르기 키·«한 번만 골라요» 안내가 필요). 찾기 겹창 동안·대신 고르는 판·타이틀·경기 중엔 없음 */
    private boolean hubBannerNeeded(int shown) {
        if ((shown != 3 && shown != 9) || rankQ != null || rankQFading != null) return false;   /* 168: 9 = 내 캐릭터 먼저(상대 없이 직접 고름) */
        int scr = hubDualV != null && hubDualV.length >= 6 ? hubDualV[5] : -1;
        return scr == 2 || scr == 4 || scr == 6;
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
        case 6: return "상대 찾는 중  ·  B 그만두기";                  /* 153: 찾기는 겹창(rankQ) — 겹창이 뜨기 전 한순간(멈춤 바 등) */
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
                if (rankCap && (scr == 2 || scr == 4)) return "랭크에서 쓸 내 캐릭터 — 한 번만 골라요\nvs " + who + " · " + lab + "\n" + myPickKeys(scr, true);   /* 153 첫 판(·바꾸기) */
                if (rankCap && scr == 6) return "고른 카드는 다음 판부터 그대로\nvs " + who + " · " + lab + "\n" + myPickKeys(scr, true);
                League.Main mn = rankAuto == 2 && scr != 2 && scr != 4 && scr != 6 ? League.main() : null;   /* 대신 고른 판 — 고르기 화면 대신 내 캐릭터 */
                if (mn != null) return "vs " + who + " · " + lab + "\n스테이지 " + League.stageName(hubStage) + " · " + League.mainLabel(mn, -1) + "\n곧 시작해요";
                return "vs " + who + " · " + lab + "\n스테이지 " + League.stageName(hubStage) + " · 카드 전부 열림\n" + myPickKeys(scr, true);
            }
            org.json.JSONObject o = (picked >= 0 && picked < 15 && hubOpp[picked] != null) ? League.oppById(hubOpp[picked]) : null;
            return "vs " + (o != null ? o.optString("nick") + " (" + League.CHAR[o.optInt("char")] + " " + League.STYLE[o.optInt("style")]
                    + ") · " + League.DIFF[o.optInt("diff")] : "?") + "\n스테이지 " + League.stageName(hubStage) + " · 카드 전부 열림"
                    + "\n" + myPickKeys(scr, true);   /* 컬러 이름은 게임 화면 오른쪽 세로 칸에 */
        }
        case 9: {                                                     /* 168 §6 내 캐릭터 먼저(상대 없음) */
            int scr = hubDualV != null && hubDualV.length >= 6 ? hubDualV[5] : 2;
            return "내 캐릭터 고르기 — 고르면 상대를 찾아요\n" + myPickKeys(scr, true);
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
        if (hubCard != null || lgmShown || leaving) return;
        final org.json.JSONObject m = League.matchById(resultId);
        if (m == null) { hubReturn(-1); return; }
        if (League.isRanked(m)) {
            if (lgmResultAtLatch(m)) return;                                /* 157 결판 메뉴(덧판) — 카드 없음 */
            if (lgm != null && lgm.isOpen()) gl.queueEvent(lgm::close);     /* 쉼에서 기권했는데 덧판 결판을 못 엶 — 옛 카드로 */
            rankResult(m); return;
        }
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
        row.addView(hubPill("리그 메뉴", new Runnable() { @Override public void run() { hubReturn(-1); lobbyWant(false); } }));   /* 168 §1: 순위표는 게임 안 대기 › 서랍(리그 화면으로 안 나감) */
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
        private boolean sfx;                         /* 176: 덧판 메뉴 위 카드(내 이름) — 단추 옮김 = 커서 · 첫 단추 = 결정 · 다른 단추·취소 = 뒤로(메뉴 효과음) */
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
            final boolean first = list.isEmpty();
            final TextView t = hubPill(label, new Runnable() { @Override public void run() { press(r, first); } });
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(6 * dp);
            t.setLayoutParams(lp);
            if (!list.isEmpty()) { t.setTextColor(0xfff3ead7); ((android.graphics.drawable.GradientDrawable) t.getBackground()).setColor(0xff3a2a18); }   /* 첫 단추(계속)만 금색 */
            list.add(t); btns.addView(t);
            return this;
        }
        Ask cancelable(boolean c) { cancelable = c; return this; }
        Ask sfx() { sfx = true; return this; }
        private void sfx(int kind) { LeagueMenu mm = lgm; if (sfx && mm != null) mm.sfxNow(kind); }
        /** 168(11) 글 칸 — 단추 줄 위에. 글자 키는 칸이 받는다(dispatchKeyEvent) · 키보드 «완료» = 첫 단추 */
        private android.widget.EditText field;
        Ask field(android.widget.EditText e) {
            field = e;
            float dp = getResources().getDisplayMetrics().density;
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(6 * dp);
            e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            e.setOnEditorActionListener((v, id, ev) -> { if (!list.isEmpty()) list.get(0).performClick(); return true; });
            card.addView(e, card.indexOfChild(btns), lp);
            return this;
        }
        boolean hasField() { return field != null; }
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
        void cancel() { if (cancelable) { sfx(Emu.UI_SFX_BACK); dismiss(); } }
        private boolean pressing;
        private void press(Runnable r, boolean first) {
            if (!showing || pressing) return;        /* 두 번 눌러도 한 번만 */
            sfx(first ? Emu.UI_SFX_SELECT : Emu.UI_SFX_BACK);
            pressing = true;
            try { if (r != null) r.run(); } finally { pressing = false; }
            dismiss();                                /* AlertDialog 와 같은 차례 — 리스너 다음에 닫힘(닫힘 리스너가 리스너의 표시를 본다) */
        }
        private void select(int i) {
            if (list.isEmpty()) return;
            int s = (i + list.size()) % list.size();
            if (s != sel) sfx(Emu.UI_SFX_CURSOR);
            sel = s;
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

    /* ── 랭크 매치(끝없는 매칭) — 153: 유저 «리그 들어갈 때 캐릭터 하나 정하고 끝 · 다음 대전 정하면 뚜뚜뚜뚜 짧게 하고 바로 다음 대전 ·
       유저 정보·캐릭터 픽 보이는 VS 창»(u153 flow_ui·nav·vsgame).
       찾기 = 게임 그림을 통째로 덮는 짧은 VS 겹창(rankQ ~1초: 나 | VS | 훑어본 후보 몇 번 → «상대 발견!»). 상대는 바로 정하고(rankedPick —
       보인 순간 holdPending, 148 규칙) 허브에 넣는다: 찾기(허브 6)면 go, 결과 카드 «다음 상대»·«재대결»은 go 0 + 돌려보내기 −4.
       native 는 저장한 내 캐릭터(pref.main)로 고르기 화면을 4배속·무음으로 대신 밟고(nativeHubAutoPick) 序章을 넘긴다 → 무대 인트로에 닿으면
       (자동 단계 6) 겹창을 내리고, 인트로 위 검은 띠에 게임 속 VS 띠(RankVs — 두 사람 닉네임·티어·LP, ~3초) → «자아 당당히 · 1회전» → 경기 → 결과 카드
       (다음 상대 · 재대결 · 그만 · 캐릭터 바꾸기 · 스테이지 ◀▶).
       내 캐릭터가 없으면(첫 판·바꾸기) 그 판만 겹창이 «상대 발견!» 뒤 내려가 게임 고르기 화면에서 직접(tail — 이야기부터 자동) 고르고, 이야기가
       시작되면 그걸 남긴다(nativeHubMyPick). 옛 native(153 함수 없음)면 매 판 직접 고르기(152 처럼) · 띠 없음.
       148 대기창(4~15초)·«상대 발견!» 카드·3초 자동 대전은 안 쓴다 ── */
    private boolean hubRanked = false;
    private RankCore.Pick rankPick;
    private League.OppView rankOppV;                /* 대진할 상대 — 허브 3 에서 경기 기록을 만들 때 그 값 그대로 */
    private RankCore.Knobs rankKnobs;
    private int rankStageRes = -1, rankRematchN = 0;
    private byte[] hubHome;                         /* 캐릭터 → 그 상대의 기본 배경(hubStart) — 결과 카드에서 고른 배경을 허브에도 넣을 때 */
    private static final int[] RANK_STAGE_ORDER = { -1, -2, 0, 1, 2, 3, 4, 5, 6, 7 };   /* 상대 기본 → 무작위 → 8개 */

    /* 겹창 — Q_SEARCH = 허브 찾기(6)에서 go 를 기다림(로고·메뉴가 도는 동안 포함) · Q_RUN = go 보냄(−4 포함): 인트로(대신 고름)·고르기 화면(직접)을 기다림 */
    private static final int Q_SEARCH = 1, Q_RUN = 2;
    private static final long Q_FLASH = 500, Q_GO_LOST = 2500, Q_TAIL_MAX = 6000, Q_AUTO_HARD = 60000;   /* 자동이 6·8 어느 쪽에도 안 닿는 마지막 안전망 */
    private static final long Q_FLASH_TAIL = 1100;  /* 직접 고르는 판 — «상대 발견! · B 그만두기»를 읽을 틈(내려가면 B 는 고르기 화면의 결정 — 겹창 B 는 뗄 때까지 막음) */
    private RankUi.Quick rankQ;
    private RankUi.Quick rankQFading;               /* 내려가며 사라지는 겹창(160ms) — 그동안도 입력 0 · B = 그만(아직 «B 그만두기»가 보인다) */
    private int rankQMode;
    private boolean rankQTail, rankQRevealed, rankQRematch, rankNoSearch;
    private long rankQT0, rankQEnd, rankQRevealAt, rankQGoAt, rankQFrozenAt, rankQNoMenuAt;
    private int rankQKeys;                          /* 지난 키(B 누름 시작만 본다) */
    private volatile boolean rankQBlock;            /* 겹창 동안 게임 입력 0 — 직접 고르는 판의 고르기 화면이 겹창 아래에서 먼저 떠도 안 눌리게 */
    /* 내 캐릭터 — native 에 넣은 것: −1 아직 · 0 옛 native(매 판 직접) · 1 tail(이번 판 직접 고르고 남김) · 2 저장한 캐릭터로 대신 */
    private int rankAuto = -1;
    private boolean rankCap, rankCapRefine;         /* 직접 고른 판 — 이야기가 시작되면 pref.main 으로 · 무작위 칸이면 경기 시작 때 뽑힌 캐릭터로 한 번 더 */
    private static boolean natAuto = true, natVs = true;   /* 153 native 가 있나 — UnsatisfiedLinkError 면 끄고 152 흐름 */
    private RankVs.Ink rankInk;

    /* ── 153 native(옛 native 엔 없음 — 못 부르면 152 흐름) ── */
    private boolean autoPick(boolean on, int cell, int sty, boolean b2p, int k1, int k2, int flags) {
        if (!natAuto || !loaded) return false;
        try { return Emu.nativeHubAutoPick(on, cell, sty, b2p, k1, k2, flags); }
        catch (UnsatisfiedLinkError e) { natAuto = false; return false; }
    }
    private int[] autoStatus() {
        if (!natAuto || !loaded) return null;
        try { int[] v = Emu.nativeHubAutoStatus(); return v != null && v.length >= 5 ? v : null; }
        catch (UnsatisfiedLinkError e) { natAuto = false; return null; }
    }
    private int[] myPick() {
        if (!natAuto || !loaded) return null;
        try { int[] v = Emu.nativeHubMyPick(); return v != null && v.length >= 8 ? v : null; }
        catch (UnsatisfiedLinkError e) { natAuto = false; return null; }
    }
    private boolean hubVs(byte[] t, short[] m, int hold, int flags) {
        if (!natVs || !loaded) return false;
        try { return Emu.nativeHubVs(t, m, hold, flags); }
        catch (UnsatisfiedLinkError e) { natVs = false; return false; }
    }
    private int[] vsStatus() {
        if (!natVs || !loaded) return null;
        try { int[] v = Emu.nativeHubVsStatus(); return v != null && v.length >= 3 ? v : null; }
        catch (UnsatisfiedLinkError e) { natVs = false; return null; }
    }

    /** 내 캐릭터를 native 에 — 있으면 다음 go 부터 대신 고르기(4배속·무음), 없으면 그 판만 직접(tail). hubStart · 남긴 뒤 · 매 go 전(리그 화면에서 바꿨을 수도) */
    private void pushMain() {
        if (natAuto && loaded) {
            try { Emu.nativeHubAutoStatus(); Emu.nativeHubMyPick(); } catch (UnsatisfiedLinkError e) { natAuto = false; }   /* 셋 다 있어야 쓴다 */
        }
        if (!natAuto || !loaded) { rankAuto = 0; return; }
        League.Main mn = League.main();
        if (mn != null && autoPick(true, mn.cell, mn.sty, false, mn.k1, mn.k2, Emu.AUTO_FF)) { rankAuto = 2; return; }   /* 165: 2P색 없음(옛 b2p 는 무시) */
        rankAuto = autoPick(false, 0, 0, false, -1, -1, Emu.AUTO_TAIL) ? 1 : 0;
    }

    /** 허브 상태마다 — 찾기(6)에 닿았는데 겹창이 없으면(타이틀에서 «리그 대전» A) 상대를 정하고 겹창 */
    private void rankTick(int st) {
        if (st == 1) rankNoSearch = false;                               /* 타이틀에 섰다(로고 뒤 찾기에 못 닿음) — 남은 «그만»이 사용자의 다음 A 를 막지 않게 */
        if (st != 6 || rankQ != null || hubCard != null || lgmShown || leaving) return;
        if (rankNoSearch) { rankNoSearch = false; Emu.nativeHubSearch(false); return; }   /* 로고가 도는 동안 그만뒀다 — 찾기에 닿자마자 타이틀로 */
        if (!resumedNow || ask != null || (pad != null && pad.isBarOpen())) return;     /* 내려가 있거나 창·멈춤 바 — 닫힌 뒤에 */
        if (rankPickOnly || leagueId != null) return;                    /* 168: 내 캐릭터 먼저 요청이 찾기에서 가는 중 · 이어 하는 경기 */
        rankSearchGo();
    }
    /** 168 «상대 찾기»(타이틀 A · 대기 · 갈아탄 판) — 멈춘 경기가 있으면 그 경기로(3b «A 로 이어서») · 내 캐릭터가 없으면 내 캐릭터부터(§6) · 아니면 찾기 */
    private void rankSearchGo() {
        if (hubResumeOpen()) return;
        if (League.main() == null && hubPickOnly(false)) return;
        rankSearchStart();
    }
    /** 찾기 길(허브 6 · hubStart 의 로고부터) — 상대를 정하고 겹창. go 는 rankQStep 이 허브가 6 에 닿으면 */
    private void rankSearchStart() {
        pushMain();
        try { rankPick = League.rankedPick(this); } catch (Exception e) { rankPick = null; }
        if (rankPick == null || rankPick.p == null) { Emu.nativeHubSearch(false); toast("상대를 찾지 못했어요"); return; }
        rankOppV = null;
        rankQOpen(Q_SEARCH, false);
    }
    /* ── 168 §6 «내 캐릭터 먼저»(pick-only) — 상대 없이(허브 9 · 무장 안 함) 게임 고르기 화면에서 내 캐릭터·검질·카드를 고르면(이야기 시작에 native 가 남김)
       pref.main 으로 굳히고 그다음 평소 «다음 상대»(겹창 «상대 찾는 중» → −4 자동 → VS → 경기). 고르다 뒤로 나가면 예전 캐릭터 그대로.
       결판 뒤 «새 캐릭터»는 −5(리셋 → 바로 고르기), 타이틀·찾기·대기에서는 리셋 없이. 허브 9 는 3 이 아니라 hubTick 의 «허브 3 → 경기 기록»에 안 걸린다(K1) ── */
    private boolean rankPickOnly = false, rankPickOnlySeen = false;
    private long rankPickOnlyAt;
    private static boolean natPickOnly = true;
    /** 내 캐릭터 먼저 고르기를 쓸 수 있는 native 인가 */
    private boolean pickOnlyOk() {
        if (!natPickOnly || !natAuto || !loaded) return false;
        try { Emu.nativeHubPickOnlyStatus(); return true; } catch (UnsatisfiedLinkError e) { natPickOnly = false; return false; }
    }
    /** 시작 — viaReset = 결판 뒤(리셋 −5). 못 쓰면 false(부른 쪽이 예전 tail 길) */
    private boolean hubPickOnly(boolean viaReset) {
        if (!pickOnlyOk() || leaving) return false;
        if (!viaReset) {
            boolean ok;
            try { ok = Emu.nativeHubPickOnly(); } catch (UnsatisfiedLinkError e) { natPickOnly = false; return false; }
            if (!ok) return false;
        }
        rankQClose(false); hubVs(null, null, 0, 0);
        rankCap = false; rankCapRefine = false;
        rankPickOnly = true; rankPickOnlySeen = false; rankPickOnlyAt = android.os.SystemClock.uptimeMillis();
        if (viaReset) { rankOppV = null; hubReturn(-5); }
        return true;
    }
    /** hubTick 마다 — 다 골랐으면 내 캐릭터로 굳히고 그다음 상대 · 고르기에서 나갔으면 끝 */
    private void rankPickOnlyTick(int st) {
        int[] po;
        try { po = Emu.nativeHubPickOnlyStatus(); } catch (UnsatisfiedLinkError e) { natPickOnly = false; rankPickOnly = false; return; }
        if (po == null || po.length < 4) return;
        if (st == 9 || po[1] == 1) rankPickOnlySeen = true;
        if (po[2] == 1) {                                                /* 이야기가 시작됐다 — 고른 것(MY)이 남았다(게임은 입력을 막은 채 멈춰 둠) */
            rankPickOnly = false;
            int[] mp = myPick();
            if (mp == null || mp[0] != 1 || (!League.setMain(mp) && League.main() == null)) { hubReturn(-1); return; }
            pushMain();
            if (League.startedOpenMatch() != null) { rankOppV = null; hubReturn(-1); return; }   /* M5: 멈춘 경기가 있다 — 타이틀로(대기 첫 줄 «이어서 하기» · 새 캐릭터는 다음 새 경기부터) */
            rankNext(false);
            return;
        }
        long age = android.os.SystemClock.uptimeMillis() - rankPickOnlyAt;
        if (rankPickOnlySeen && st != 9 && po[1] == 0) rankPickOnly = false;              /* 고르기에서 뒤로 나감(타이틀) — 예전 캐릭터 그대로 */
        else if (!rankPickOnlySeen && po[0] == 0 && po[1] == 0 && st == 1 && age > 4000) rankPickOnly = false;   /* 요청이 사라졌다(리셋 등) */
        else if (!rankPickOnlySeen && po[0] == 1 && st == 1 && !hubTitleMenu() && age > 2000) {   /* 168 고침(K5): 메뉴가 없는 화면(게임 옵션·데모)에서 매달린 요청 — 지운다(나중에 그 화면을 나오자 저절로 고르기로 튀지 않게) */
            rankPickOnly = false;
            Emu.nativeLeagueHubCancel();                                 /* 타이틀 상태 · PO 비움(경기 없음 — 무장도 없다) */
        }
    }

    /** 결과 카드 «다음 상대»(change = «캐릭터 바꾸기» — 168: 내 캐릭터부터 고르고 그다음 상대 · 옛 native 면 예전처럼 내 캐릭터를 지우고 이번 판은 직접) — 상대를 바로 정해 넣고 −4 */
    private void rankNext(boolean change) {
        if (change && hubPickOnly(true)) return;
        if (change) League.clearMain();
        pushMain();
        RankCore.Pick pk;
        try { pk = League.rankedPick(this); } catch (Exception e) { pk = null; }
        if (pk == null || pk.p == null) { toast("상대를 찾지 못했어요"); rankOppV = null; hubReturn(-1); return; }
        rankPick = pk;
        rankPrepare(pk.p, rankStageFor(pk.p));
        rankQOpen(Q_RUN, false);                                         /* 리셋 «전에» 덮는다 — 로고·고르기 화면이 안 보이게 */
        rankArmNext(pk.p);
    }
    /** 재대결 — 그 상대의 지금 MMR 로 손잡이를 다시 정해(진 상대는 그만큼 세지거나 약해진다) 같은 배경으로 −4 */
    private void rankRematch(org.json.JSONObject m, RankCore.Player p) {
        pushMain();
        rankPick = null;
        rankPrepare(p, rankStageFor(p));                                 /* 157 G9: 무대 = 고른 무대(pref.stage — ◀▶ 로 바꾼 값). 156 은 지난 경기 look.stage */
        rankQOpen(Q_RUN, true);
        rankArmNext(p);
    }
    private void rankPrepare(RankCore.Player p, int stageRes) {
        rankOppV = League.oppView(p);
        rankKnobs = RankCore.knobs(rankOppV.mmr, p.personaIdx);
        rankStageRes = stageRes;
        rankRematchN = League.rematchCount(p.id);
    }
    private int rankStageFor(RankCore.Player p) {
        int sel = League.prefStage();
        return sel >= 0 ? sel : sel == -2 ? (int) (android.os.SystemClock.uptimeMillis() % 8) : rankHomeStage(p);
    }
    /** −4 길 — 상대를 허브에(go 0) · VS 띠 · 돌려보내기(GL 스레드) */
    private void rankArmNext(RankCore.Player p) {
        RankCore.Knobs k = rankKnobs;
        if (!Emu.nativeHubMatch(p.ch, p.sty, k.diff, rankStageRes, k.lv, k.cap, k.atk, k.dfn, k.floor, false)) {
            rankQClose(false); hubReturn(-1); toast("상대를 잡지 못했어요 — 다시 찾아 주세요");   /* 옛 대진이 남아 다른 상대로 붙지 않게 */
            return;
        }
        rankVsArm(p);                                                    /* 띠는 돌려보내기(−4) 뒤에도 남는다 */
        rankCap = rankAuto == 1; rankCapRefine = false;
        hubReturn(-4);
        rankQGoAt = android.os.SystemClock.uptimeMillis();
    }
    /** 찾기 길의 go — 허브가 6 에 닿았다(대신 고르는 판은 바로 · 직접 고르는 판은 상대를 보인 뒤) */
    private void rankQGo() {
        RankCore.Player p = rankPick != null ? rankPick.p : null;
        if (p == null) { rankQClose(false); Emu.nativeHubSearch(false); return; }
        rankPrepare(p, rankStageFor(p));
        RankCore.Knobs k = rankKnobs;
        if (!Emu.nativeHubMatch(p.ch, p.sty, k.diff, rankStageRes, k.lv, k.cap, k.atk, k.dfn, k.floor, true)) {
            rankQClose(false); Emu.nativeHubSearch(false); toast("상대를 잡지 못했어요 — 다시 찾아 주세요");
            return;
        }
        rankVsArm(p);
        rankCap = rankAuto == 1; rankCapRefine = false;
        rankQMode = Q_RUN; rankQGoAt = android.os.SystemClock.uptimeMillis();
    }

    /** 153 VS 띠 — 경기 직전 무대 인트로(두 캐릭터가 고른 무대에 마주 선 장면)의 맨 위 검은 띠에 나·상대(닉네임·티어·LP·VS)를 게임 타일로(RankVs).
     *  허브가 序章을 A 로 넘기고, 대사 단계를 RankVs.HOLD 프레임 보여 준 뒤 A 를 대신 눌러 «자아 당당히 → 1회전 → 승부» 배너 → 첫 라운드.
     *  못 그리면(타일 겹침·다른 상대·옛 native) 띠 없이 게임 그대로 */
    private void rankVsArm(RankCore.Player p) { rankVsArm(p, rankAuto == 2); }
    /** mySty = 내 이름 옆 검질 표시(저장한 내 캐릭터로 대신 고르는 판) — 자동이 캐릭터·검질에서 막혀 직접 고르면 뺀다 */
    private void rankVsArm(RankCore.Player p, boolean mySty) {
        if (!natVs || p == null) return;
        try {
            RankCore.Me me = League.rankMe();
            League.OppView v = rankOppV != null && rankOppV.p == p ? rankOppV : League.oppView(p);
            org.json.JSONObject pf = League.pfOf(this, p);
            League.Main mn = mySty ? League.main() : null;
            RankVs.Side a = RankVs.side(League.myNick(), mn != null && mn.cell != 15 ? League.STYLE[mn.sty] : null, me.lp, me.master, me.mr);
            if (me.placementLeft > 0) RankVs.placement(a, 6 - me.placementLeft);
            RankVs.Side b = RankVs.side(pf.optString("nick", p.nick), null, v.lp, v.master, v.mr);
            if (rankInk == null) rankInk = RankVs.androidInk(hubFont());
            RankVs.Out o = RankVs.render(rankInk, a, b);
            if (o == null || !hubVs(o.tiles, o.map, RankVs.HOLD, RankVs.FLAGS)) hubVs(null, null, 0, 0);
        } catch (Exception e) { hubVs(null, null, 0, 0); }
    }

    /** 겹창을 띄운다 — 게임 그림을 바로(동기) 덮는다. gl 바로 위 · 패드 아래(멈춤 바가 위에 그려진다) */
    private void rankQOpen(int mode, boolean rematch) {
        rankQClose(false);
        long now = android.os.SystemClock.uptimeMillis();
        RankCore.Me me = League.rankMe();
        rankQMode = mode; rankQTail = rankAuto != 2; rankQRematch = rematch;
        rankQRevealed = false; rankQRevealAt = 0; rankQGoAt = 0; rankQFrozenAt = 0; rankQNoMenuAt = 0; rankNoSearch = false;
        boolean held = rankPick != null && rankPick.pending;
        rankQT0 = now; rankQEnd = now + (rematch ? 300 : RankCore.quickMs(rankPick));
        rankQ = RankUi.quick(this, hubFont(), rankMeSide(me), rematch ? "랭크 매치 · 재대결" : "랭크 매치", "2선승 · 라운드 60초",
                rematch ? "재대결" : held ? "기다리던 상대" : "상대 찾는 중", rankQEnd - rankQT0, rankQLow());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                android.view.Gravity.TOP | android.view.Gravity.LEFT);
        rankQPlaceLp(lp);
        root.addView(rankQ.root, Math.min(1, root.getChildCount()), lp);
        rankQBlock = true;
        rankQKeys = padMask | keyMask | axisMask;                       /* 쥐고 있던 B 는 안 센다 */
        if (rankPick != null && !rematch) rankQReelLoad(rankPick, rankQ);
        h.removeCallbacks(rankQPoll); h.postDelayed(rankQPoll, 50);
    }
    private boolean rankQLow() { return gameBox[3] - gameBox[1] > 0 && gameBox[3] - gameBox[1] < Math.round(250 * getResources().getDisplayMetrics().density); }
    /** 게임 그림 자리 그대로(기둥 그림까지) — 레이아웃 전(첫 판 onCreate)이면 화면 전체 */
    private void rankQPlaceLp(FrameLayout.LayoutParams lp) {
        if (gameBox[2] > gameBox[0] && gameBox[3] > gameBox[1]) {
            lp.width = gameBox[2] - gameBox[0]; lp.height = gameBox[3] - gameBox[1]; lp.leftMargin = gameBox[0]; lp.topMargin = gameBox[1];
        } else { lp.width = FrameLayout.LayoutParams.MATCH_PARENT; lp.height = FrameLayout.LayoutParams.MATCH_PARENT; lp.leftMargin = 0; lp.topMargin = 0; }
    }
    private void rankQPlace() {
        if (rankQ == null || !(rankQ.root.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rankQ.root.getLayoutParams();
        rankQPlaceLp(lp);
        rankQ.root.setLayoutParams(lp);
    }
    /** 겹창을 닫는다 — fade = 무대 인트로가 보이게 살짝(인트로 첫 ~19프레임은 검은 화면). 사라지는 동안도 입력 0 · B = 그만 —
     *  다 사라지면 입력을 푼다(그때 쥐고 있던 키는 뗄 때까지 qHeld 가 삼킨다). 바로 닫기(fade false)는 사라지던 것도 바로 */
    private void rankQClose(boolean fade) {
        h.removeCallbacks(rankQPoll);
        final RankUi.Quick q = rankQ, f = rankQFading;
        rankQ = null; rankQMode = 0;
        if (fade && q == null) return;
        if (f != null) { f.root.animate().cancel(); root.removeView(f.root); rankQFading = null; }   /* cancel — 끝 동작(입력 풂)은 안 돈다 */
        if (!fade) { rankQBlock = false; if (q != null) root.removeView(q.root); return; }
        rankQFading = q;
        q.root.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() { @Override public void run() {
            root.removeView(q.root);
            if (rankQFading == q) { rankQFading = null; if (rankQ == null) rankQBlock = false; }
        } }).start();
        h.postDelayed(rankQPoll, 50);                                    /* 사라지는 동안도 물리 패드 B 를 본다 */
    }
    /** 나 — 아바타(씨앗 고정) · 이름 · 티어 · 내 캐릭터 */
    private RankUi.Side rankMeSide(RankCore.Me me) {
        RankUi.Side s = new RankUi.Side();
        Locale ko = Locale.KOREA;
        s.av = Avatar.bitmap(AvatarGen.fnv1a64("av:me:" + League.rankSeed()));
        s.nick = League.myNick();
        if (me.placementLeft > 0) { s.tier = "배치 " + (6 - me.placementLeft) + "/5"; s.tierColor = RankUi.GOLD; }
        else if (me.master) { s.tier = "무쌍 · MR " + String.format(ko, "%,d", me.mr); s.tierColor = RankUi.tierColor(me.lp, true); }
        else { s.tier = RankCore.label(me.lp) + " · " + String.format(ko, "%,d", me.lp) + " LP"; s.tierColor = RankUi.tierColor(me.lp, false); }
        s.frame = s.tierColor;
        League.Main mn = rankAuto == 2 ? League.main() : null;
        s.ch = mn != null ? League.mainLabel(mn, -1) : rankAuto == 1 ? "이번에 골라요" : "직접 골라요";
        return s;
    }
    /** 릴 — 실제로 훑어본 후보(Pick.reel)의 아바타·«닉 · 티어»를 백그라운드에서(프로필 풀을 처음 읽을 때 느리다). 상대 프로필도 미리 */
    private void rankQReelLoad(final RankCore.Pick pk, final RankUi.Quick q) {
        final java.util.List<RankCore.Player> ps = new java.util.ArrayList<>(pk.reel);
        new Thread(new Runnable() { @Override public void run() {
            final java.util.List<RankUi.ReelItem> out = new java.util.ArrayList<>();
            try {
                League.pfOf(EmuActivity.this, pk.p);
                for (RankCore.Player p : ps) out.add(rankReelItem(p));
            } catch (Exception e) { android.util.Log.w("PocketCore", "rank reel: " + e); out.clear(); }
            h.post(new Runnable() { @Override public void run() { if (rankQ == q) q.setReel(out); } });
        }}, "rank-reel").start();
    }
    private RankUi.ReelItem rankReelItem(RankCore.Player p) {
        org.json.JSONObject pf = League.pfOf(this, p);
        League.OppView v = League.oppView(p);
        return new RankUi.ReelItem(Avatar.of(pf, p.id), pf.optString("nick", p.nick) + " · " + v.label, RankUi.tierColor(v.lp, v.master));
    }

    /** «상대 발견!» — 보인 그 순간 잡는다(holdPending — 보고 B 로 빼서 다시 뽑기를 막는다, 148). 재대결은 잡지 않는다 */
    private void rankQReveal() {
        rankQRevealed = true; rankQRevealAt = android.os.SystemClock.uptimeMillis();
        RankCore.Player p = rankPick != null ? rankPick.p : rankOppV != null ? rankOppV.p : null;
        if (rankPick != null && !rankQRematch) League.holdPending(rankPick);
        if (p == null || rankQ == null) return;
        League.OppView v = rankOppV != null && rankOppV.p == p ? rankOppV : League.oppView(p);
        org.json.JSONObject pf = League.pfOf(this, p);
        RankUi.Side o = new RankUi.Side();
        o.av = Avatar.of(pf, p.id); o.frame = RankUi.tierColor(v.lp, v.master); o.tierColor = o.frame;
        o.nick = pf.optString("nick", p.nick);
        o.tier = v.master ? v.label : v.label + " · " + String.format(Locale.KOREA, "%,d", v.lp) + " LP";
        o.ch = League.CHAR[p.ch] + " " + League.STYLE[p.sty];
        RankCore.Me me = League.rankMe();
        int[] stakes = RankCore.stakes(me, v.mmr);
        String sub = me.placementLeft > 0 ? "배치 경기 " + (6 - me.placementLeft) + "/5" : stakes != null ? "이기면 +" + stakes[0] + " · 지면 " + RankUi.signed(stakes[1]) + " LP" : "무쌍 — MR 승부";
        java.util.List<String> tags = rankPick != null ? rankPick.tags : java.util.Collections.<String>emptyList();
        if (!rankQRematch && !tags.isEmpty()) sub = tags.get(0) + " · " + sub;
        rankQ.reveal(o, rankQRematch ? "재대결!" : "상대 발견!", sub, me.placementLeft > 0 ? RankUi.GOLD : RankUi.BODY,
                rankQTail ? "내 캐릭터를 골라요 · B 그만두기" : "곧 시작해요 · B 그만두기", true);
    }

    private final Runnable rankQPoll = new Runnable() { @Override public void run() {
        if (rankQ == null) {                                             /* 내려가며 사라지는 중 — B 만 본다 */
            if (rankQFading != null && !leaving && !rankQKey(padMask | keyMask | axisMask)) h.postDelayed(this, 50);
            return;
        }
        if (!leaving) rankQStep();
        if (rankQ != null) h.postDelayed(this, 50);
    }};
    /** 50ms 마다 — 멈춤(창·멈춤 바·내려감) 동안은 시계를 세우고(native 도 멈춰 있다), B 면 그만, 상대를 보일 때·go·내릴 때를 본다 */
    private void rankQStep() {
        long now = android.os.SystemClock.uptimeMillis();
        boolean frozen = !resumedNow || !loaded || ask != null || (pad != null && pad.isBarOpen()) || (leagueDlg != null && leagueDlg.isShowing())
                || (sheet != null && sheet.isShowing()) || lookEditor != null;   /* 코어가 멈춘 동안 — native 도 안 간다 */
        if (frozen) { if (rankQFrozenAt == 0) rankQFrozenAt = now; return; }
        if (rankQFrozenAt != 0) {
            long d = now - rankQFrozenAt; rankQFrozenAt = 0;
            rankQT0 += d; rankQEnd += d; if (rankQRevealAt != 0) rankQRevealAt += d; if (rankQGoAt != 0) rankQGoAt += d; if (rankQNoMenuAt != 0) rankQNoMenuAt += d;
        }
        if (rankQKey(padMask | keyMask | axisMask)) return;
        rankQ.tick(now - rankQT0);
        if (!rankQRevealed && now >= rankQEnd) rankQReveal();
        if (rankQ == null) return;
        int[] hs = Emu.nativeLeagueHubStatus();
        if (hs == null || hs.length < 9 || hs[8] < hubWaitGen) return;     /* 돌려보내기(−4)가 아직 GL 스레드에 안 닿음 — 지난 판 값 */
        int st = hs[0];
        if (rankQMode == Q_SEARCH) {
            if (st == 1) {                                                   /* 타이틀에 섰다(찾기 전) — 찾기로. 168 고침(K5): 메뉴 글자가 없으면(게임 옵션·데모) 찾기가 곧 타이틀로 되돌아와
                                                                                1→6→1 로 끝없이 돌았다 — 메뉴가 있을 때만 · 오래 없으면 겹창을 닫는다 */
                if (hubTitleMenu()) { rankQNoMenuAt = 0; Emu.nativeHubSearch(true); }
                else if (rankQNoMenuAt == 0) rankQNoMenuAt = now;
                else if (now - rankQNoMenuAt > Q_GO_LOST) { rankQClose(false); rankCap = false; hubVs(null, null, 0, 0); toast("상대를 잡지 못했어요 — 다시 찾아 주세요"); }
                return;
            }
            rankQNoMenuAt = 0;
            if (st == 6 && (!rankQTail || rankQRevealed)) rankQGo();         /* 대신 고르는 판은 바로(겹창 동안 4배속) · 직접 고르는 판은 상대를 보인 뒤 */
            return;
        }
        if (st == 6 || st == 1) {                                            /* go 를 보냈는데 찾기·타이틀에 그대로 — 못 넘어감(메뉴 커서 등) */
            if (now - rankQGoAt > Q_GO_LOST) {
                rankQClose(false); rankCap = false; hubVs(null, null, 0, 0);
                if (st == 6) Emu.nativeHubSearch(false);
                toast("상대를 잡지 못했어요 — 다시 찾아 주세요");
            }
            return;
        }
        if (st == 4) { rankQClose(false); return; }                          /* 벌써 경기(라운드) — 바로(사라지는 틈의 B 를 «그만»으로 받지 않게) */
        if (st != 3 || !rankQRevealed || now - rankQRevealAt < (rankQTail ? Q_FLASH_TAIL : Q_FLASH)) return;   /* «상대 발견!» 은 잠깐이라도 보인다 */
        if (!rankQTail) {
            int[] as = autoStatus(), vs = vsStatus();
            if (as != null && as[0] == 8) {                                   /* 자동이 막힘 — 직접 고르기로 */
                if (as[4] <= 3) rankVsArm(rankOppV != null ? rankOppV.p : null, false);   /* 부팅·캐릭터·검질에서 막힘 — 다른 걸 고를 수 있으니 띠의 내 검질 표시를 빼고 다시(띠가 얹히기 전이라 바뀐다) · 카드부터면 그대로 */
                rankQDrop(2);
            }
            else if (as != null && as[0] >= 6) rankQDrop(0);                  /* 무대 인트로 — VS 띠 */
            else if (vs != null && ((vs[0] & 15) == 3 || (vs[0] & 15) == 4)) rankQDrop(0);
            else if (as == null) rankQDrop(2);                                /* 옛 native — 직접 */
            else if (now - rankQGoAt > Q_AUTO_HARD) { rankQClose(false); hubReturn(-1); toast("준비가 너무 늦어 그만뒀어요 — 다시 찾아 주세요"); }
            /* 오래 걸려도 native 가 아직 1~5 단계(4배·입력 막음)면 겹창을 내리지 않는다 — 단계마다 제한이 있어 곧 6(인트로) 이나 8(막힘)이 온다 */
            return;
        }
        int[] dv = Emu.nativeHubDualStatus();
        int scr = dv != null && dv.length >= 6 ? dv[5] : -1;
        int[] as = autoStatus();
        if (scr == 2 || scr == 4 || scr == 6 || (as != null && as[0] >= 5 && as[0] <= 7) || now - rankQGoAt > Q_TAIL_MAX) rankQDrop(1);
    }
    /** 겹창을 내린다 — 0 무대 인트로(VS 띠) · 1 직접 고르기 화면 · 2 자동이 막혀 직접 */
    private void rankQDrop(int why) {
        rankQClose(true);
        if (why == 2) toast("자동으로 못 골랐어요 — 직접 골라 주세요");   /* 175: 직접 고르기 안내 글은 없음 */
    }
    private static final int NGP_B = 1 << Emu.A;   /* 게임(NGPC)의 B = libretro A(8) — league.c IN_B */
    /** B 누름(터치 패드 · 물리 패드) — 그만. 창·멈춤 바가 떠 있으면 그쪽 몫 */
    private boolean rankQKey(int m) {
        int rise = m & ~rankQKeys; rankQKeys = m;
        if ((rankQ == null && rankQFading == null) || (rise & NGP_B) == 0 || ask != null || (pad != null && pad.isBarOpen())) return false;
        rankQBack();
        return true;
    }
    /** 겹창에서 B·뒤로 — 그만(LP 그대로 · 준비 기록은 사용자가 그만둔 것으로 닫음). 상대를 보였으면 잡힌 채(다음 찾기에 같은 상대).
     *  내려가며 사라지는 중(rankQFading — 늘 go 뒤 · 상대를 보인 뒤)도 같다: 고르기 화면·인트로에 B 가 새어 기본 캐릭터로 골라지지 않게 */
    private void rankQBack() {
        if (rankQ == null && rankQFading == null) return;
        boolean search = rankQMode == Q_SEARCH;
        rankQClose(false);
        rankCap = false;
        if (search) {                                                        /* go 전 — 타이틀 메뉴 그대로(로고가 도는 중이면 찾기에 닿자마자) */
            int[] hs = Emu.nativeLeagueHubStatus();
            if (hs != null && hs.length > 0 && hs[0] == 6) Emu.nativeHubSearch(false); else if (hs == null || hs.length == 0 || hs[0] != 1) rankNoSearch = true;   /* 타이틀(1)이면 할 일 없음 */
        } else { hubVs(null, null, 0, 0); hubReturn(-1); }                   /* go 뒤(고르기·−4·사라지는 중) — 리셋해 타이틀로(자동도 native 가 지운다) · 175: 글 없음 */
    }
    /** 겹창 동안 허브 안내 띠 — 상대가 미리 보이지 않게(case 3 의 «vs …» 대신) */
    private String rankQBanner() {
        if (!rankQRevealed) return "상대 찾는 중  ·  B 그만두기";
        return rankQTail ? "상대 발견!  ·  곧 내 캐릭터 고르기" : "상대 발견!  ·  곧 시작해요";
    }

    /** 직접 고른 판 — 이야기가 시작되면(자동 단계 5 · native 가 내 고르기를 남김) pref.main 으로 · 다음 go 부터 대신 고르기. 무작위 칸은 경기 시작 때 뽑힌 캐릭터로 */
    private void rankCapTick(int st, int[] hs) {
        if (!rankCap && !rankCapRefine) return;
        if (hs.length > 8 && hs[8] < hubWaitGen) return;
        int[] mp = myPick();
        if (mp == null) { rankCap = false; rankCapRefine = false; return; }
        if (rankCap) {
            int[] as = autoStatus();
            if (as == null) { rankCap = false; return; }
            boolean late8 = as[0] == 8 && as.length > 4 && as[4] >= 5;       /* 고르기는 끝났고 이야기·인트로에서 막힘 — 고른 건 그대로 쓴다 */
            if (!(st == 3 || st == 4) || !((as[0] >= 5 && as[0] <= 7) || late8) || mp[0] != 1) return;
            rankCap = false;
            if (!League.setMain(mp) && League.main() == null) return;       /* 값이 이상하다 — 다음 판도 직접 */
            pushMain();
            rankCapRefine = mp[1] == 15;                                    /* 175: 알림 글 없음 */
        } else if (st == 4) {
            rankCapRefine = false;
            if (mp[0] == 1 && mp[1] == 15) League.setMain(mp);
        }
    }

    private int rankHomeStage(RankCore.Player p) { int s = p.legacy ? League.homeStage(p.id) : p.stage; return s >= 0 && s < 8 ? s : 0; }
    /** 결과 카드의 스테이지 이름 — 상대 기본은 이름을 안 보인다(누가 나올지 미리 알려 주지 않게). «스테이지 · » 앞말은 뺀다(◀▶ 가 말해 준다 — 320dp 세로에서 잘렸다) */
    private static String rankStageLabel(int sel) { return sel == -1 ? "상대 기본" : sel == -2 ? "무작위" : League.stageName(sel); }
    /** 결과 카드 ◀▶ — 다음 판부터 그대로(pref · 허브 값도 맞춘다 — 허브 값이 옛 값이면 hubTick 이 pref 를 되돌린다) */
    private int rankStageStep(int d) {
        int sel = League.prefStage(), i = 0;
        while (i < RANK_STAGE_ORDER.length && RANK_STAGE_ORDER[i] != sel) i++;
        if (i >= RANK_STAGE_ORDER.length) i = 0;
        sel = RANK_STAGE_ORDER[(i + d + RANK_STAGE_ORDER.length) % RANK_STAGE_ORDER.length];
        League.setPrefStage(sel);
        if (hubHome != null) { Emu.nativeLeagueHubLook(hubHome, sel, 0); hubStageSel = sel; }
        return sel;
    }

    /** 허브 3(내 캐릭터 고르기)에 닿았다 — 카드에서 정한 상대로 경기 기록(준비) */
    private boolean rankNewMatch() {
        org.json.JSONObject m = rankOppV != null ? League.newRankedMatch(this, rankOppV, rankKnobs, rankStageRes, rankRematchN) : null;
        if (m == null) {
            final org.json.JSONObject open = rankOppV != null ? League.openMatch() : null;
            hubReturn(-1);
            if (open != null && !"ready".equals(open.optString("status"))) {   /* 다른 판(같은 폴더)이 남긴 끝나지 않은 경기 — 막다른 안내 대신 그 자리에서 풀기 */
                Emu.nativeSetPaused(true);
                ask("끝나지 않은 경기가 있어요", "vs " + League.oppLabel(open) + "\n먼저 이어 하거나 기권해야 새 경기를 할 수 있어요.")
                        .btn("이어 하기", new Runnable() { @Override public void run() { hubResumeOpen(); } })   /* 168: 이 판에서 바로(허브가 타이틀에 서면 — K2) */
                        .btn("기권", new Runnable() { @Override public void run() {
                            if (League.forfeit(open.optString("id")) == null) toast("저장 실패 — 다시 시도하세요"); } })
                        .btn("나중에", null)
                        .onDismiss(new Runnable() { @Override public void run() { if (hubCard == null && !lgmShown && !leaving) Emu.nativeSetPaused(pad.isBarOpen()); } })
                        .show();
            } else toast("경기를 만들지 못했어요 — " + (rankOppV == null ? "상대 정보가 없어요" : "기록 저장 실패"));
            return false;
        }
        leagueId = m.optString("id"); leagueArmed = true; leagueDone = false; leagueStarted = false; leagueRetry = 0;
        leagueVersionProblem(m);
        League.setLook(leagueId, hubStage, hubColor, lookTri(hubColor));
        h.removeCallbacks(leaguePoll); h.postDelayed(leaguePoll, 250);
        lgmMatchStart();                                                 /* 157 — 이 판 전 순위(결판 «▲212») 미리 */
        return true;
    }

    /** 랭크 결과 카드 — LP·티어 변화, 보너스, 사건, 상대 한마디. 다음 상대 / 재대결(같은 상대 3판째까지) / 그만 · 스테이지 ◀▶ · 캐릭터 바꾸기 */
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
        row.addView(hubPill("다음 상대", () -> rankNext(false)));        /* 153: 상대를 바로 정해 −4 — 짧은 겹창 뒤 바로 다음 대전 */
        if (rp != null && League.rematchCount(oid) < 3 && League.oppSupported(rp.ch, rp.sty))
            row.addView(hubPill("재대결", () -> rankRematch(m, rp)));
        row.addView(hubPill("그만", () -> { rankOppV = null; hubReturn(-1); }));
        card.addView(row);
        /* 153: 둘째 줄 — 스테이지 ◀▶(148 대기창에서 옮김 · 다음 판부터) · «캐릭터 바꾸기»(내 캐릭터를 지우고 바로 다음 상대 — 그 판은 직접 고르고 새로 남긴다) */
        android.widget.LinearLayout row2 = new android.widget.LinearLayout(this);
        row2.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row2.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row2.setPadding(0, Math.round(6 * dp), 0, 0);
        final TextView sv = RankUi.line(this, f, rankStageLabel(League.prefStage()), 12, RankUi.BODY);
        sv.setGravity(android.view.Gravity.CENTER);
        TextView sl = RankUi.tv(this, f, " ◀ ", 15, RankUi.GOLD), sr = RankUi.tv(this, f, " ▶ ", 15, RankUi.GOLD);
        for (TextView a : new TextView[]{ sl, sr }) { a.setGravity(android.view.Gravity.CENTER); a.setMinHeight(Math.round(40 * dp)); a.setMinWidth(Math.round(36 * dp)); }
        sl.setOnClickListener(v -> sv.setText(rankStageLabel(rankStageStep(-1))));
        sr.setOnClickListener(v -> sv.setText(rankStageLabel(rankStageStep(1))));
        row2.addView(sl); row2.addView(sv, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); row2.addView(sr);
        {                                                                   /* 168 §6: 늘 — 내 캐릭터부터 고르고 그다음 상대 */
            TextView ch = RankUi.pill(this, f, "캐릭터 바꾸기", false, () -> rankNext(true));
            ch.setTextSize(13); ch.setMinHeight(Math.round(40 * dp)); ch.setPadding(Math.round(10 * dp), Math.round(4 * dp), Math.round(10 * dp), Math.round(4 * dp));
            android.widget.LinearLayout.LayoutParams cl = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            cl.leftMargin = Math.round(8 * dp);
            row2.addView(ch, cl);
        }
        card.addView(row2, new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        android.widget.ScrollView sc = new android.widget.ScrollView(this);   /* 가로 화면(높이 360dp 안팎)이면 카드가 화면보다 길다 — 밀어서 둘째 줄까지 닿게 */
        sc.setScrollbarFadingEnabled(false);                              /* 밀 수 있을 때만 막대가 보인다 */
        sc.addView(card, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        lp.topMargin = gameBox[1] + Math.round(8 * dp); lp.bottomMargin = Math.round(8 * dp);
        root.addView(sc, lp);
        hubCard = sc;
        if (hubBanner != null) hubBanner.setText("결과를 기록했어요");
    }
    /** 결과 카드 닫기 → 리셋 뒤 타이틀 «리그 대전» 칸(rematch ≥ 0 이면 같은 상대로 바로 내 캐릭터 고르기까지 · −2 «다른 상대» = 바로 상대 선택). */
    private void hubReturn(final int rematch) {
        if (hubCard != null) { root.removeView(hubCard); hubCard = null; }
        lgmReset();                                                      /* 157 결판·쉼 메뉴 — 표시 끄고(덧판은 아래 GL 에서 먼저 닫음) */
        org.json.JSONObject m = lm();                       /* 상대만 고르고 대진 전에 타이틀로 — 준비 기록은 무효로(열린 채 남지 않게) */
        if (m != null && "ready".equals(m.optString("status"))) League.cancel(leagueId, "게임 속 리그 — 대진 전에 타이틀로");
        hubWaitGen = Math.max(hubWaitGen, hubGen) + 1;     /* 153: 앞의 돌려보내기가 아직 GL 스레드에 안 닿았으면 그다음 것까지(−4 바로 뒤 그만 −1) */
        leagueId = null; leagueArmed = false; leagueDone = false; leagueStarted = false; leagueRetry = 0; leagueCheatSeen = 0;
        if (rematch != -4) { rankQClose(false); rankCap = false; rankCapRefine = false; }   /* 153: −4(다음 상대·재대결)는 부른 쪽이 겹창을 먼저 띄웠다 */
        if (rematch != -5) rankPickOnly = false;                         /* 168: −5 = 내 캐릭터 먼저(hubPickOnly 가 켰다) */
        hubResumePending = false;
        h.removeCallbacks(leaguePoll);
        Emu.nativeSetPaused(pad != null && pad.isBarOpen());
        final LeagueMenu mm = lgm;
        gl.queueEvent(new Runnable() { @Override public void run() { if (mm != null) mm.close(); Emu.nativeLeagueHubReturn(rematch); } });
    }

    /** 리그 판에서 뒤로 = 바로 타이틀로 갈 화면인가 — 상대 고르기, 또는 상대를 고르고 첫 라운드 전(준비 기록은 hubReturn 이 무효로 닫는다, 점수 없음).
     *  점수가 걸린 경기(첫 라운드 뒤)·타이틀에서는 예전처럼 창을 띄운다 */
    private boolean hubPickBack() {
        if (!leagueHub || hubCard != null || lgmShown || leaving) return false;
        if (leagueId == null) return hubLastState == 2 || hubLastState == 3 || hubLastState == 9;   /* 168: 9 = 내 캐릭터 먼저 */
        leagueSyncStarted();
        org.json.JSONObject m = lm();
        return !leagueStarted && m != null && "ready".equals(m.optString("status"));
    }

    /** 점수가 걸린 경기가 도는 중인가(첫 라운드 뒤 · 결판 전) — 결판이 막 잡혔으면 여기서 정산(그러면 아님: 기록은 이미 적힘) */
    private boolean hubMatchLive() {
        if (leagueId == null || !leagueArmed || leagueDone) return false;
        leagueSyncStarted();
        if (leagueSettleNow() && leagueDone) return false;          /* 결판 프레임이 이미 왔다 — 정산했다(나가도 기록은 같다) */
        return leagueStarted;
    }
    /** 157 post — 경기 밖(타이틀·상대 찾기·고르기 · 결판 메뉴·결과 카드)에서 나가기: 묻지 않고 바로(유저 «'나가나요?' 같은 것 하지 않는다»).
     *  dest = PadView.ACT_PICK(게임 목록) · ACT_QUIT(앱 종료) · 0(뒤로 — 게임에서 왔으면 그 게임, 아니면 리그 화면). 고르기 화면의 준비 기록은 무효로(점수 없음 — 각 길이 닫는다).
     *  점수가 걸린 경기 중이면 부르지 않는다(쉼·나가기 카드가 받음) — 그래도 왔으면(경합) 경기 쪽으로 돌린다 */
    private void hubLeaveTo(int dest) {
        if (leaving) return;
        if (hubMatchLive()) { if (lgmEligible(true)) gl.queueEvent(lgm::requestPause); else leagueLeave(); return; }
        if (hubCard != null) { root.removeView(hubCard); hubCard = null; }
        rankQClose(false);
        if (dest == PadView.ACT_PICK) { cancelReady("게임 속 리그 — 대진 전에 목록으로"); lgmCloseNow(); leagueHub = false; goList(); }
        else if (dest == PadView.ACT_QUIT) { cancelReady("게임 속 리그 — 대진 전에 앱 종료"); lgmCloseNow(); MainActivity.forgetLast(this); finishAffinity(); }
        else if (fromGame()) hubToGame();
        else if (!lobbyFromBack()) { cancelReady("게임 속 리그 — 대진 전에 목록으로"); lgmCloseNow(); leagueHub = false; goList(); }   /* 168 D2·K6: 대기를 못 쓰는 판 — 게임 목록(리그 화면으로 안 나감) */
    }
    /** 168 D2 — 타이틀에서 뒤로 = 대기(거기 «리그 나가기 › 내 게임 / 게임 목록»). 대기를 못 쓰면(덧판 없음) false */
    private boolean lobbyFromBack() {
        final LeagueMenu mm = lgm;
        if (mm == null || leaving) return false;
        if (mm.isOpen()) return true;
        if (lobbyEligible(true)) { lobbyViaBack = true; gl.queueEvent(mm::requestLobby); return true; }
        if (leagueId == null && !lgmShown && hubCard == null) {           /* 타이틀 메뉴가 아니다(데모·고르기·돌아가는 중·게임 옵션·컬렉션) — 타이틀로 보내고 거기서 */
            lobbyWant(true);
            if (hubLastState != 1 || !hubTitleMenu()) { rankOppV = null; hubReturn(-1); }
            return true;
        }
        return false;
    }
    /** 대진 전(준비) 기록이면 무효로(사용자가 그만둔 것 — 잡힌 상대 miss 아님) · 이 창은 그 경기를 잊는다(내려가며 onPause 가 닫은 경기의 체크포인트를 쓰지 않게) */
    private void cancelReady(String why) {
        org.json.JSONObject m = lm();
        if (m == null || !"ready".equals(m.optString("status"))) return;
        League.cancel(leagueId, why);
        Emu.nativeLeagueDisarm();
        leagueId = null; leagueArmed = false; leagueDone = false; leagueStarted = false;
        h.removeCallbacks(leaguePoll);
    }

    /** «내 게임으로» — 대진 전 기록은 사용자가 그만둔 것으로 닫고(잡힌 상대 miss 아님) 일반 판으로. 일반 판은 오토세이브(싸우던 자리·연습 중이던 자리)부터 */
    private void hubToGame() {
        lgmCloseNow();
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
     *  리그 판은 바로 상대 선택까지(EXTRA_HUB_GO), 일반 판은 practiceAuto 면 연습 «캐릭터 선택»까지(EXTRA_PRACTICE — 내 캐릭터 먼저) */
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
        final boolean v = on, rk = ranked(), hub = loaded && leagueHub;
        h.post(new Runnable() { @Override public void run() {
            if (pad == null) return;
            pad.setRankedBar(rk);                                     /* 168(3b): 리그 판 바 — 저장·로드·슬롯 칸 없음 · 리셋 «저장 후 타이틀» */
            if (hub) pad.setLeagueCell(lgm != null, "리그 메뉴");     /* 168 §2: 리그 판 — 같은 칸이 «리그 메뉴»(대기 · 경기 중이면 쉼) · 덧판이 없으면 칸도 없음 */
            else pad.setLeagueCell(v);
        } });
    }

    /** 칸을 눌렀다 — 못 쓰면 알림 · 아니면 바로(157 post «들어가고 나오는 거 … '나가나요?' 같은 것 하지 않는다»: 오토세이브 꺼짐도 묻지 않고 —
     *  목록으로 나갈 때처럼 지금 판은 안 이어진다는 것만 알림) */
    private void leagueFromGame() {
        if (ranked() || leaving || !loaded) return;
        League.db();
        if (League.dbProblem != null || League.writeProblem() != null) { toast("리그 기록을 쓸 수 없어요 — 저장소 권한을 확인해 주세요"); return; }
        if (!autoSave) toast("자동 저장이 꺼져 있어 지금 판은 이어지지 않아요");
        leagueGo();
    }
    private void leagueGo() {
        if (hubBanner == null) addHubBanner();                          /* «리그 대전으로…» 띠 — 연습이 안 켜진 판엔 띠가 없다 */
        switchSession(true, false);
    }

    /** 순위표(리그 화면)로 나가기 — 결과 창 없이 */
    private void leagueExitBoard() {
        lgmCloseNow();
        org.json.JSONObject m = lm();                       /* 대진 전에 나가면 준비 기록은 무효로(열린 채 남지 않게) */
        if (m != null && "ready".equals(m.optString("status"))) League.cancel(leagueId, "게임 속 리그 — 대진 전에 순위표로");
        leagueHub = false;                                   /* leagueExit 가 결과 카드로 새지 않게 */
        leagueExit(null);
    }


    /* ── 157 리그 인게임 메뉴(덧판 — 계약서 docs/157_contract.md §12) ─────────────────────────
       결판(경기 끝): 정산 순간 사본(MenuModel)·장면(LgmPages, 그림 스레드)을 만들어 «걸쇠에 맞춰 시작»으로 걸어 둔다 — 승리 대사가 끝나 걸쇠가 서는
       vsync 에 native 가 연다(Java 결과 카드 없음). 쉼(경기 중): OPTION 탭·뒤로·패드 바 «목록/종료» → 코어 멈춤(ov_hold) → 사본·장면 → 칼집.
       메뉴(LeagueMenu)는 GL 스레드 · 동작은 여기(UI 스레드)의 기존 함수(rankNext·rankRematch·hubReturn·leagueExitBoard·hubToGame·goList·
       leagueLeave 몸통·League.forfeit)로. 못 쓰면(설정 «끄기» · 옛 native · 게임 사각형 못 찾음 · 그림 실패) 옛 카드 그대로. */
    private volatile LeagueMenu lgm;                 /* null = 덧판 없음(옛 native·리그 판 아님) */
    private boolean lgmShown;                        /* 결판 메뉴가 떠 있다 — hubCard 와 같은 뜻 */
    private boolean lgmOpen;                         /* 결판·쉼·서랍 무엇이든 열림(UI 표시 — 안내 띠 숨김·터치 뷰) */
    private String lgmArmedId, lgmResultId;          /* 걸어 둔 경기 · 결판 메뉴에 보이는 경기 */
    private String lgmFailedId;                      /* 정산 때 그리기에 실패한 경기 — 걸쇠 때 한 번 더 그려 보고(지금 열기), 또 실패하면 옛 카드 */
    private MenuModel lgmModel;                      /* 지금 열린 사본(서랍 그림용) */
    private RankCore.Me lgmMeBefore, lgmMeAfter;     /* 이 판 전·뒤 나(순위 ▲▼) */
    private int lgmGen, lgmTries, lgmDrawerTab = -1;
    private java.util.concurrent.ExecutorService lgmExec;
    private LgmPages lgmPages;                       /* 그림 스레드에서만 */
    private View lgmTouch;
    private static final String LGM = "PocketCore";
    /* 165: 기기 163 에서 결판이 옛 카드로 간 까닭 — 정산 뒤 그림 스레드가 결판 장면을 다 그리기 전에(첫 판·디버그 판은 글꼴·JIT 가 차가워 몇 초)
       승리 대사를 B 로 넘겨 걸쇠가 먼저 서면, 걸쇠 뒤 1.5초 기다림이 끝나 «not_shown» → 옛 카드였다(sim 재현: 그림 3.3초 + 대사 건너뛰기).
       이제 그림이 아직이면 걸쇠 화면 그대로 기다리고(LGM_RENDER_WAIT 까지), 걸어 둔 장면이 끝내 안 보이거나 정산 때 그림이 실패했으면 한 번 지금 다시 그려 연다.
       그래도 못 쓰면 옛 카드 — 그 까닭을 lgmWhy 로 남긴다(logcat · 디버그 판은 짧은 토스트, 까닭마다 한 번) */
    private boolean lgmRendering;                    /* 결판 장면을 그림 스레드가 그리는 중(정산 → armResult) */
    private long lgmRenderT0, lgmLatchAt;            /* 그리기 시작 · 걸쇠 뒤 기다리기 시작(uptime ms) */
    private volatile String lgmRenderErr;            /* 그림 스레드의 마지막 예외(까닭 글) */
    static final long LGM_LATCH_WAIT = 1500, LGM_RENDER_WAIT = 10000;
    /** 덧판을 못 써 옛 길로 가는 까닭 — logcat «lgm fallback <key> · <detail>» 만(토스트 없음 — 기기 제보는 logcat 으로) */
    private void lgmWhy(String key, String detail) {
        /* logcat 에만 — 시험판(fgtest)도 디버그 판이라 토스트를 띄우면 유저에게 보인다(유저: 안내 글을 보고 싶지 않음) */
        android.util.Log.w(LGM, "lgm fallback " + key + (detail != null && !detail.isEmpty() ? " · " + detail : ""));
    }
    /** 덧판·이름표 상태 한 줄(까닭 글에 붙임) */
    private String lgmState() {
        int[] v = lgmStatus();
        LeagueMenu mm = lgm;
        return "lgm " + (mm != null ? mm.state() : -1) + " · ov 0x" + (v != null ? Integer.toHexString(v[Emu.OVS_FLAGS]) : "-")
                + (v != null ? " fb " + v[Emu.OVS_FBW] + "x" + v[Emu.OVS_FBH] : "") + " · post " + postState();
    }

    /** hubStart — 덧판이 있으면 메뉴를 만들고 이번 대전 세션(G2)을 새로 · 세션 첫 순위 미리 */
    private void lgmSetup() {
        League.sessionStart();
        if (lgm == null) {
            int[] v = lgmStatus();
            if (v == null || v[Emu.OVS_VER] < 1) { lgmWhy("no_native", v == null ? "nativeOvStatus 없음" : "VER " + v[Emu.OVS_VER]); return; }
        }
        final RankCore.Me me = League.rankMe();
        if (me.placementLeft == 0) BoardCache.warm(this, me, true, 4, false, () -> { BoardCache.Board b = BoardCache.get(me, true, 4); if (b != null && b.rank > 0) League.sessionRank0(b.rank); });
        if (lgm != null) return;
        LeagueMenu.OvPort port = LeagueMenu.OvPort.emu();
        if (port == null) return;
        if (lgmExec == null) lgmExec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "lgm-render"); t.setDaemon(true); return t; });
        LeagueMenu mm = new LeagueMenu(port, lgmActions, h);
        mm.setPortrait(root == null || root.getHeight() >= root.getWidth());
        lgm = mm;
        lgmRender(this::lgmPages, null);                                  /* 165: 글꼴 셋은 허브를 켤 때 미리(첫 결판 그림이 늦어 걸쇠를 놓치지 않게) */
    }
    /** nativeOvStatus — 옛 native 면 null */
    private static int[] lgmStatus() {
        try { int[] v = Emu.nativeOvStatus(); return v != null && v.length >= Emu.OV_NSTAT ? v : null; }
        catch (UnsatisfiedLinkError e) { return null; }
    }
    /** 랭크 경기 시작 — 이 판 전 나(결판 순위 비교) · HUD 이름표 */
    private void lgmMatchStart() {
        lgmMeBefore = League.rankMe();
        if (lgm != null && lgmMeBefore.placementLeft == 0) BoardCache.warm(this, lgmMeBefore, true, 4, false, null);
        lgmTagsArm();
    }
    /** 157 post — 경기 중 HUD 이름표(유저 «리그 시작하고 나서 양측 에너지바 아래에 유저 이름/계급» · «체력바 아래 (비켜 주기)»):
     *  나(P1)·상대(P2)의 «검객★1 닉» 을 그림 스레드에서 그려 GL 에(LeagueMenu.setTags). 보이기·숨기기는 native 가 프레임마다 —
     *  라운드 조작 중일 때만(KO·대사·걸쇠엔 숨김) · 게임이 그 자리에 무엇을 그리면(히트 수 등) 비켜 줌 · 라운드 시작 2초 강조.
     *  경기 기록을 만들 때(rankNewMatch)·체크포인트로 이어 할 때(leagueStart). 코어가 바뀌면(허브 돌아가기·내림·리셋·불러오기) native 가 저절로 지운다 */
    private void lgmTagsArm() {
        final LeagueMenu mm = lgm;
        final String id = leagueId;
        if (mm == null || id == null || !leagueHub || !hubRanked || leaving) return;
        final org.json.JSONObject m = lm();
        final MenuModel.Side[] sd = MenuModel.tagSides(m);
        if (sd == null) { lgmWhy("tags_model", "tagSides null"); return; }
        lgmTagsId = id; lgmTagsAt = android.os.SystemClock.uptimeMillis(); lgmTagsHidden = 0; lgmTagsRearm = 0; lgmTagsPending = true;
        final MenuModel warm = lgmWarmModel(m);                         /* 165: 결판 장면을 한 번 미리(버림) — 정산 때 그림이 빨라 걸쇠를 안 놓치게 */
        lgmRender(() -> {
            LgmPages pg = lgmPages();
            final boolean dmg = (viewBits & 2) != 0;                     /* 176: 대미지 미터 숫자(줄 24~31 · x 64~87)와 안 겹치는 폭으로 */
            final LgmPages.HudTag a = pg.hudTag(sd[0], false, LgmPages.tagRoom(false, dmg)), b = pg.hudTag(sd[1], true, LgmPages.tagRoom(true, dmg));
            h.post(() -> {
                if (id.equals(lgmTagsId)) { lgmTagsPending = false; lgmTagsAt = android.os.SystemClock.uptimeMillis(); }
                if (!id.equals(leagueId) || leaving || leagueDone) return;
                gl.queueEvent(() -> { if (!mm.setTags(a, b)) h.post(() -> lgmWhy("tags_off", "setTags 실패(" + (a == null ? "나 없음 " : "") + (b == null ? "상대 없음" : "") + ")")); });
            });
            if (warm != null) {
                long t0 = android.os.SystemClock.uptimeMillis();
                try { pg.result(warm); } catch (Throwable t) { android.util.Log.w(LGM, "lgm warm: " + t); }
                long ms = android.os.SystemClock.uptimeMillis() - t0;
                if (ms > 300) android.util.Log.i(LGM, "lgm warm result " + ms + "ms");
            }
        }, () -> { if (id.equals(lgmTagsId)) lgmTagsPending = false; lgmWhy("tags_render", lgmRenderErr); });
    }
    private String lgmTagsId;                        /* 이름표를 건 경기 */
    private long lgmTagsAt;                          /* 건 시각(uptime) */
    private int lgmTagsHidden, lgmTagsRearm;         /* 조작 중인데 안 보인 연속 폴 수 · 다시 건 횟수 */
    private boolean lgmTagsPending;                  /* 이름표를 그림 스레드가 그리는 중(아직 native 에 안 감) */
    /** 결판 미리 그리기용 사본 — 이 경기를 «이김 2:1 · +50» 으로 끝난 것처럼(그림만 데우고 버림). 못 만들면 null */
    private MenuModel lgmWarmModel(org.json.JSONObject m) {
        try {
            if (m == null || m.optJSONObject("start") == null) return null;
            org.json.JSONObject f = new org.json.JSONObject(m.toString());
            int lp = f.getJSONObject("start").optInt("lp");
            f.put("status", "settled").put("outcome", "W").put("result", new org.json.JSONObject().put("w1", 2).put("w2", 1))
             .put("lp_before", lp).put("lp_after", Math.min(RankCore.MASTER_LP - 1, lp + 50)).put("lp_delta", 50);
            return MenuModel.result(null, f, lgmCtx(League.menuPrefs(), lgmStatus(), true, null));
        } catch (Exception e) { return null; }
    }
    /** 165 — 경기 중 이름표 살핌(leaguePoll 마다): native 가 이름표를 지웠으면(코어 바뀜 등) 한 번 다시 걸고, 조작 중인데 15초 넘게 안 보이면 까닭(비킴·번쩍)을 남긴다 */
    private void lgmTagsWatch() {
        if (lgm == null || leagueId == null || !leagueId.equals(lgmTagsId) || leagueDone || !leagueStarted || leaving) return;
        int[] t;
        try { t = Emu.nativeOvTagStatus(); } catch (UnsatisfiedLinkError e) { return; }
        if (t == null || t.length < Emu.OV_TAG_NSTAT) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (t[Emu.OVT_ON] == 0 && !lgmTagsPending && now - lgmTagsAt > 2000) {   /* 걸어 보냈는데 꺼져 있다(GL 이 받을 틈 2초) */
            if (lgmTagsRearm++ == 0) { lgmWhy("tags_cleared", "이름표가 꺼져 있어 다시 걺 · " + lgmState()); lgmTagsArm(); lgmTagsRearm = 1; }
            return;
        }
        if (t[Emu.OVT_ON] == 1 && t[Emu.OVT_LIVE] == 1 && (t[Emu.OVT_VIS] & 3) == 0) {
            if (++lgmTagsHidden == 60) lgmWhy("tags_hidden", "조작 중 15초 안 보임 · occ " + t[Emu.OVT_OCC] + " flash " + t[Emu.OVT_FLASH] + " scans " + t[Emu.OVT_SCANS] + " yields " + t[Emu.OVT_YIELDS]);
        } else lgmTagsHidden = 0;
    }
    /** 쉼 자격 — 랭크 경기(첫 라운드 뒤) · 결판 전 · 찾기 겹창·결과·카드·바 없음 */
    private boolean lgmEligible() { return lgmEligible(false); }
    private boolean lgmEligible(boolean ignoreBar) {
        if (lgm == null || !leagueHub || !hubRanked || leaving || !loaded) return false;
        if (leagueId == null || !leagueArmed || leagueDone || !leagueStarted) return false;
        if (postState() > 0) return false;
        if (!(hubLastState == 4 || hubLastState <= 2)) return false;           /* 경기(4) · 체크포인트 이어 하기(허브는 타이틀인 채 싸움) */
        if (rankQ != null || rankQFading != null || lgmShown || hubCard != null || ask != null || lookEditor != null) return false;
        if (sheet != null && sheet.isShowing()) return false;
        return ignoreBar || !(pad != null && pad.isBarOpen());
    }
    private void lgmEligibleSync() { LeagueMenu mm = lgm; if (mm != null) mm.setPauseEligible(lgmEligible()); }
    /** 메뉴 상태 ↔ UI 표시(안내 띠·터치 뷰) — hubTick 마다 */
    private void lgmUiSync() {
        LeagueMenu mm = lgm;
        boolean open = mm != null && (mm.isOpen() || lgmShown);
        if (open != lgmOpen) lgmOpenUi(open);
        cheatCellSync();                                                  /* 176: 바 «치트» 칸 — 결판 덧판 동안 숨김 */
    }
    private void lgmOpenUi(boolean open) {
        lgmOpen = open;
        if (open) {
            if (hubBanner != null) hubBanner.setVisibility(View.GONE);
            lgmTouchAttach();
        } else lgmTouchDetach();                                          /* 안내 띠는 다음 hubTick 이 글·자리·보이기를 다시 정한다 */
    }
    /** 결판·쉼 표시를 끈다(덧판 닫기는 부른 쪽이 GL 에서 — hubReturn 은 nativeLeagueHubReturn 앞) */
    private void lgmReset() {
        lgmShown = false; lgmArmedId = null; lgmResultId = null; lgmModel = null; lgmGen++; lgmDrawerTab = -1; lgmRendering = false;
        if (lgmOpen) lgmOpenUi(false);
    }
    /** 리그를 나가기 전 — 표시 끄고 GL 에서 덧판 닫기(코어 내리기보다 먼저 — queueEvent 순서) */
    private void lgmCloseNow() {
        final LeagueMenu mm = lgm;
        lgmReset();
        if (mm != null) gl.queueEvent(mm::close);
    }

    /** 정산을 막 했다 — 결판 장면을 미리 그려 걸쇠에 걸어 둔다(판마다 한 번 — 폴마다 다시 안 함). 못 쓰면 까닭을 남기고 걸쇠 때 지금 열기로 한 번 더 */
    private void lgmOnSettle() {
        final LeagueMenu mm = lgm;
        if (mm == null || !leagueHub || !hubRanked || leagueId == null || leaving) return;
        if (leagueId.equals(lgmSettleId)) return;
        final org.json.JSONObject m = lm();
        if (m == null || !League.isRanked(m) || !League.terminal(m.optString("status"))) return;
        lgmSettleId = leagueId; lgmReopenId = null; lgmFailedId = null;
        MenuModel.Prefs p = League.menuPrefs();
        if (p.fx == MenuModel.Prefs.FX_OFF) { lgmWhy("fx_off", "설정 «결판 연출» 끄기 — 옛 카드"); return; }
        int[] v = lgmStatus();
        if (v == null || (v[Emu.OVS_FLAGS] & Emu.OVF_RECT_OK) == 0) { lgmWhy("rect", "정산 때 게임 사각형 못 찾음(걸쇠 때 다시) · " + lgmState()); return; }
        final String id = leagueId;
        lgmMeAfter = League.rankMe();
        final RankCore.Me after = lgmMeAfter;
        if (after.placementLeft == 0) BoardCache.warm(this, after, true, 4, false, () -> lgmRankReady(id));
        final MenuModel model = MenuModel.result(this, m, lgmCtx(p, v, true, after));
        if (model == null) { lgmWhy("model", "MenuModel.result null · status " + m.optString("status") + " · outcome " + m.optString("outcome")); return; }
        lgmArmedId = id; lgmModel = model; lgmResultId = id;
        final int gen = ++lgmGen;
        lgmRendering = true; lgmRenderT0 = android.os.SystemClock.uptimeMillis();
        lgmRender(() -> {
            LgmPages pg = lgmPages();
            final LgmPages.Scene r = pg.result(model);                  /* 157 post: «그만할까요?» 장면 없음 — «그만하기» 는 바로 타이틀로 */
            h.post(() -> {
                if (gen != lgmGen) return;
                lgmRendering = false;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lgmRenderT0 > 1000) android.util.Log.i(LGM, "lgm result render " + (now - lgmRenderT0) + "ms" + (lgmShown ? " — 걸쇠 뒤 " + (now - lgmLatchAt) + "ms" : ""));
                if (leaving || !id.equals(lgmArmedId)) return;
                gl.queueEvent(() -> mm.armResult(model, r, null));
            });
        }, () -> {
            if (gen != lgmGen) return;
            lgmRendering = false;
            lgmWhy("render", "정산 때 결판 그림 실패 · " + lgmRenderErr);
            lgmFailedId = id;
            if (lgmShown && id.equals(lgmResultId)) { lgmReopenNow(League.matchById(id), "render"); return; }   /* 걸쇠가 먼저 섰다 — 지금 한 번 더 */
            lgmArmedId = null; lgmResultId = null; lgmModel = null;                                             /* 걸쇠 때 지금 열기로 한 번 더 */
        });
    }
    private String lgmSettleId, lgmReopenId;          /* 정산 뒤 걸기를 해 본 경기 · 지금 다시 그려 열기를 해 본 경기(판마다 한 번) */
    /** MenuModel 만들 재료 */
    private MenuModel.Ctx lgmCtx(MenuModel.Prefs p, int[] ov, boolean started, RankCore.Me now) {
        MenuModel.Ctx c = new MenuModel.Ctx();
        c.rankAuto = rankAuto; c.stageSel = League.prefStage(); c.ov = ov; c.started = started; c.prefs = p;
        c.board = now != null ? BoardCache.get(now, true, 4) : null;
        c.boardBefore = lgmMeBefore != null ? BoardCache.get(lgmMeBefore, true, 4) : null;
        return c;
    }
    /** 그림 스레드에서 — 실패(넘침·글꼴 없음·예외)면 onFail 을 UI 스레드에 */
    private void lgmRender(final Runnable job, final Runnable onFail) {
        java.util.concurrent.ExecutorService ex = lgmExec;
        if (ex == null) { if (onFail != null) onFail.run(); return; }
        try {
            ex.execute(() -> {
                try { job.run(); }
                catch (Throwable t) {
                    StackTraceElement[] st = t.getStackTrace();
                    lgmRenderErr = t + (st.length > 0 ? " @" + st[0].getFileName() + ":" + st[0].getLineNumber() : "");
                    android.util.Log.w(LGM, "lgm render: " + lgmRenderErr);
                    if (onFail != null) h.post(onFail);
                }
            });
        } catch (Exception e) { if (onFail != null) onFail.run(); }
    }
    /** 그림 스레드 전용 — 글꼴 셋(앱 assets)을 처음 한 번 */
    private LgmPages lgmPages() {
        if (lgmPages == null) {
            TileCanvas.Inks k = TileCanvas.androidInks(getAssets());
            if (k == null) throw new IllegalStateException("글꼴 없음");
            lgmPages = new LgmPages(k);
        }
        return lgmPages;
    }
    /** 정산 뒤 순위가 나왔다 — 결판 아래 띠 «12,483위 ▲212» 조각 */
    private void lgmRankReady(final String id) {
        final LeagueMenu mm = lgm;
        if (mm == null || lgmModel == null || !id.equals(lgmResultId) || lgmMeAfter == null) return;
        BoardCache.Board b = BoardCache.get(lgmMeAfter, true, 4);
        if (b == null || b.rank <= 0) return;
        BoardCache.Board bb = lgmMeBefore != null ? BoardCache.get(lgmMeBefore, true, 4) : null;
        League.Session ss = League.session();
        long before = bb != null && bb.rank > 0 ? bb.rank : ss != null && ss.rank0 > 0 ? ss.rank0 : -1;
        final MenuModel nm = lgmModel.withRank(b.rank, before);
        lgmModel = nm;
        final int gen = lgmGen;
        lgmRender(() -> {
            final TileCanvas.Encoded p = lgmPages().rankPatch(nm);
            if (p != null) h.post(() -> { if (gen == lgmGen && id.equals(lgmResultId)) gl.queueEvent(() -> mm.patch(p)); });
        }, null);
    }

    /** 걸쇠 뒤 결과(hubResult) — 덧판 결판이면 true(카드 없음). 걸어 둔 장면이 아직이면 걸쇠 화면 그대로 기다림 · 걸지 못한 판(쉼에서 기권·무효,
     *  정산 때 그림 실패·사각형 못 찾음)이면 지금 그려 연다 */
    private boolean lgmResultAtLatch(final org.json.JSONObject m) {
        final LeagueMenu mm = lgm;
        if (mm == null || !leagueHub || !hubRanked) { if (leagueHub && hubRanked) lgmWhy("no_menu", "덧판 메뉴 없음(옛 native?)"); return false; }
        String id = m.optString("id");
        lgmLatchAt = android.os.SystemClock.uptimeMillis();
        if (id.equals(lgmArmedId)) {                                      /* 정산 때 걸어 둔 판(그림이 아직일 수도) */
            lgmShown = true; lgmTries = 0;
            lgmOpenUi(true);
            h.removeCallbacks(lgmLatchCheck); h.post(lgmLatchCheck);
            return true;
        }
        return lgmOpenNow(m, id.equals(lgmFailedId) ? "render" : null);
    }
    /** 결판을 지금(걸쇠·멈춘 화면 위) 그려 연다 — 판마다 한 번. 못 쓰면 까닭을 남기고 false(부른 쪽이 옛 카드) */
    private boolean lgmOpenNow(final org.json.JSONObject m, String retryOf) {
        final LeagueMenu mm = lgm;
        if (mm == null || m == null) return false;
        final String id = m.optString("id");
        if (id.equals(lgmReopenId)) return false;                         /* 이 판은 이미 한 번 지금 열기를 해 봤다 */
        lgmReopenId = id;
        MenuModel.Prefs p = League.menuPrefs();
        if (p.fx == MenuModel.Prefs.FX_OFF) { lgmWhy("fx_off", "설정 «결판 연출» 끄기 — 옛 카드"); return false; }
        int[] v = lgmStatus();
        if (v == null || (v[Emu.OVS_FLAGS] & Emu.OVF_RECT_OK) == 0) { lgmWhy("rect", "게임 사각형 못 찾음 · " + lgmState()); return false; }
        final RankCore.Me after = League.rankMe();
        lgmMeAfter = after;
        if (after.placementLeft == 0) BoardCache.warm(this, after, true, 4, false, () -> lgmRankReady(id));
        final MenuModel model = MenuModel.result(this, m, lgmCtx(p, v, true, after));
        if (model == null) { lgmWhy("model", "MenuModel.result null · status " + m.optString("status")); return false; }
        if (retryOf != null) android.util.Log.i(LGM, "lgm result reopen (" + retryOf + ")");
        lgmModel = model; lgmResultId = id; lgmArmedId = id; lgmFailedId = null;
        lgmShown = true;
        lgmOpenUi(true);
        final int gen = ++lgmGen;
        lgmRendering = true; lgmRenderT0 = lgmLatchAt = android.os.SystemClock.uptimeMillis();
        lgmRender(() -> {
            LgmPages pg = lgmPages();
            final LgmPages.Scene r = pg.result(model);
            h.post(() -> {
                if (gen != lgmGen) return;
                lgmRendering = false;
                if (!leaving) gl.queueEvent(() -> mm.openResultNow(model, r, null));
            });
        }, () -> { if (gen == lgmGen) { lgmRendering = false; lgmWhy("render", "결판 그림 실패 · " + lgmRenderErr); lgmToCard(m, "render"); } });
        h.removeCallbacks(lgmLatchCheck); h.postDelayed(lgmLatchCheck, 100);
        return true;
    }
    /** 지금 다시 그려 열기 — 못 하면 옛 카드 */
    private void lgmReopenNow(org.json.JSONObject m, String why) {
        if (!lgmOpenNow(m, why)) lgmToCard(m, why);
    }
    /** 걸쇠 뒤 — 덧판이 보이기를 100ms 마다(걸쇠는 레벨이라 장면이 늦게 걸려도 바로 열림). 그림 스레드가 아직 그리는 중이거나 걸어 둔 채면
     *  LGM_RENDER_WAIT 까지 걸쇠 화면 그대로 기다리고, 그 밖은 LGM_LATCH_WAIT 뒤 한 번 지금 다시 그려 열기 → 그래도 안 보이면 옛 카드 */
    private final Runnable lgmLatchCheck = new Runnable() { @Override public void run() {
        if (!lgmShown || leaving || lgmResultId == null) return;
        int[] v = lgmStatus();
        int fl = v != null ? v[Emu.OVS_FLAGS] : 0;
        long now = android.os.SystemClock.uptimeMillis();
        if (!resumedNow) { lgmLatchAt += 100; h.postDelayed(this, 100); return; }   /* 내려가 있는 동안(GL 멈춤)은 기다림 시계를 세운다 */
        long waited = now - lgmLatchAt;
        if ((fl & Emu.OVF_VISIBLE) != 0) {                                /* 열렸다 */
            if (waited > LGM_LATCH_WAIT) android.util.Log.i(LGM, "lgm result shown " + waited + "ms after latch");
            return;
        }
        LeagueMenu mm = lgm;
        boolean armed = mm != null && mm.state() == LeagueMenu.ST_ARMED;
        lgmTries++;
        if ((lgmRendering || armed) && waited < LGM_RENDER_WAIT) { h.postDelayed(this, 100); return; }   /* 그리는 중 · 걸어 둔 채 — 기다림 */
        if (waited < LGM_LATCH_WAIT) { h.postDelayed(this, 100); return; }
        org.json.JSONObject m = League.matchById(lgmResultId);
        lgmWhy("not_shown", (lgmRendering ? "그림 " + (now - lgmRenderT0) + "ms 넘음" : armed ? "걸어 둔 채 안 열림" : "걸린 장면 없음") + " · 걸쇠 뒤 " + waited + "ms · " + lgmState());
        lgmReopenNow(m, "not_shown");
    } };
    /** 덧판 결판을 버리고 옛 결과 카드로(까닭은 부른 쪽이 lgmWhy 로) */
    private void lgmToCard(org.json.JSONObject m, String why) {
        android.util.Log.i(LGM, "lgm fallback card · " + why);
        final LeagueMenu mm = lgm;
        lgmReset();
        if (mm != null) gl.queueEvent(mm::close);
        if (m != null && !leaving && hubCard == null) rankResult(m);
    }

    /** 메뉴가 고른 것(UI 스레드) — 기존 함수로 */
    private final LeagueMenu.Actions lgmActions = new LeagueMenu.Actions() {
        @Override public void lgmNext(boolean newChar) {
            if (leaving) return;
            lgmReset();
            rankNext(newChar);                                           /* 겹창이 바로 덮고 hubReturn(−4) — 그 안에서 덧판 닫음 */
        }
        @Override public void lgmRematch() {
            if (leaving) return;
            org.json.JSONObject m = lgmResultId != null ? League.matchById(lgmResultId) : null;
            RankCore.Player rp = m != null ? League.rankPlayer(m.optString("opp")) : null;
            lgmReset();
            if (m == null || rp == null || League.rematchCount(rp.id) >= 3 || !League.oppSupported(rp.ch, rp.sty)) { rankOppV = null; hubReturn(-1); return; }   /* 175: 거절 글 없음(줄이 원래 안 보임 — 안전망) */
            rankRematch(m, rp);
        }
        @Override public void lgmStage(int dir) {
            final int sel = rankStageStep(dir);
            final LeagueMenu mm = lgm;
            if (mm != null) gl.queueEvent(() -> mm.setStage(sel));
        }
        @Override public void lgmQuit(int where) {
            if (leaving) return;
            switch (where) {
                case MenuModel.QUIT_GAME: hubToGame(); break;
                case MenuModel.QUIT_LIST: lgmCloseNow(); leagueHub = false; goList(); break;
                default: rankOppV = null; hubReturn(-1); break;      /* 타이틀로 — 리그는 켠 채 */
            }
        }
        @Override public void lgmResume() { lgmModel = null; lgmDrawerTab = -1; if (lgmOpen && !lgmShown) lgmOpenUi(false); }
        @Override public void lgmLater() {
            if (leaving || leagueDone || leagueId == null) { lgmCloseNow(); return; }
            if (leagueSettleNow()) { lgmCloseNow(); return; }                /* 결판이 막 잡혔다 — poll 이 결과로 */
            leagueSuspend("사용자가 나중에 이어하기", null);                  /* 168 §1: 저장 → 타이틀(리그는 켠 채 · A 로 이어서) — 리그 화면·순위표로 안 나감 */
        }
        @Override public void lgmForfeit() {
            if (leaving || leagueId == null) { lgmCloseNow(); return; }
            if (leagueDone) { lgmCloseNow(); return; }
            leagueChoosing = true;
            leagueSyncStarted();
            if (leagueSettleNow()) { leagueChoosing = false; lgmCloseNow(); return; }
            if (!leagueStarted) {                                        /* 첫 라운드 전 — «그만두기 (무효)»(leagueLeaveBeforeStart 몸통) */
                if (!League.cancel(leagueId, "경기 시작 전에 사용자가 그만둠")) {
                    leagueChoosing = false; toast("저장 실패 — 다시 시도하세요"); lgmCloseNow(); return;
                }
            } else if (League.forfeit(leagueId) == null) {
                leagueChoosing = false; toast("저장 실패 — 다시 시도하세요"); lgmCloseNow(); return;
            }
            leagueDone = true; Emu.nativeLeagueDisarm();
            leagueExit(leagueId);                                        /* → hubResult → 덧판 결판(기권패·무효)을 지금(멈춘 화면 위) */
        }
        @Override public void lgmWantPause() {
            final LeagueMenu mm = lgm;
            if (mm == null) return;
            if (!lgmEligible(true) || (leagueSettleNow() && leagueDone)) { gl.queueEvent(mm::cancelPause); return; }
            org.json.JSONObject m = lm();
            int[] v = lgmStatus();
            MenuModel.Prefs p = League.menuPrefs();
            final MenuModel model = MenuModel.pause(EmuActivity.this, m, lgmCtx(p, v, leagueStarted, League.rankMe()));
            if (model == null || v == null || (v[Emu.OVS_FLAGS] & Emu.OVF_RECT_OK) == 0) {
                lgmWhy("pause", (model == null ? "MenuModel.pause null" : "게임 사각형 못 찾음") + " · " + lgmState());
                gl.queueEvent(mm::cancelPause);
                h.post(() -> { if (!leaving && leagueId != null && !leagueDone) leagueLeave(); });   /* 옛 길 — 나가기 카드 */
                return;
            }
            lgmModel = model;
            final int gen = ++lgmGen;
            lgmRender(() -> {
                LgmPages pg = lgmPages();
                final LgmPages.Scene a = pg.pause(model, false), c = pg.pause(model, true);
                h.post(() -> {
                    if (gen != lgmGen || leaving) { gl.queueEvent(mm::cancelPause); return; }
                    gl.queueEvent(() -> mm.openPause(model, a, c));
                    lgmOpenUi(true);
                });
            }, () -> { lgmWhy("pause_render", lgmRenderErr); gl.queueEvent(mm::cancelPause); if (!leaving && leagueId != null && !leagueDone) leagueLeave(); });
        }
        @Override public void lgmWantDrawer(int tab) {
            lgmDrawerTab = tab;
            lgmDrawer(tab);
        }
        @Override public void lgmPrefs(MenuModel.Prefs p) {
            MenuModel.Prefs o = League.menuPrefs();
            League.setMenuPrefs(p);
            if (lgmModel != null) lgmModel = lgmModel.withPrefs(p);
            boolean shown = o.fx != p.fx || o.lossCursor != p.lossCursor || o.autoNextSec != p.autoNextSec || o.rememberTab != p.rememberTab;
            if (shown && lgmDrawerTab == MenuModel.Prefs.TAB_SETTINGS) lgmDrawer(MenuModel.Prefs.TAB_SETTINGS);
        }
        /* ── 168 §2 대기(157-B — 리그 타이틀 위) · 서랍 «리그» 탭 ── */
        @Override public void lgmWantLobby() {
            final LeagueMenu mm = lgm;
            if (mm == null) return;
            final boolean back = lobbyViaBack;
            lobbyViaBack = false;
            if (!lobbyEligible(true)) { gl.queueEvent(mm::cancelPause); return; }
            int[] v = lgmStatus();
            final MenuModel model = lobbyModel(v);
            if (model == null || v == null || (v[Emu.OVS_FLAGS] & Emu.OVF_RECT_OK) == 0) {
                lgmWhy("lobby", (model == null ? "MenuModel.lobby null" : "게임 사각형 못 찾음") + " · " + lgmState());
                gl.queueEvent(mm::cancelPause);
                if (back) lobbyFallback();                                   /* K6: 뒤로로 부른 대기를 못 엶 — 예전 갈래(게임 · 게임 목록) */
                return;
            }
            lgmModel = model;
            final int gen = ++lgmGen;
            lgmRender(() -> {
                final LgmPages.Scene[] sc = lobbyScenes(model);
                h.post(() -> {
                    if (gen != lgmGen || leaving || !lobbyEligible(true)) { gl.queueEvent(mm::cancelPause); return; }
                    gl.queueEvent(() -> mm.openLobby(model, sc[0], sc[1], sc[2]));
                    lgmOpenUi(true);
                });
            }, () -> { lgmWhy("lobby_render", lgmRenderErr); gl.queueEvent(mm::cancelPause); if (back) lobbyFallback(); });
        }
        @Override public void lgmSearch() {
            if (leaving) return;
            lgmCloseNow();
            rankSearchGo();                                                  /* 멈춘 경기면 이어 하기 · 내 캐릭터가 없으면 내 캐릭터부터 · 아니면 찾기(겹창이 허브를 찾기로) */
        }
        @Override public void lgmResumeOpen() {
            if (leaving) return;
            lgmCloseNow();
            if (!hubResumeOpen()) rankSearchGo();                            /* 그사이 경기가 닫혔다 — 찾기로 */
        }
        @Override public void lgmMain() {
            if (leaving) return;
            lgmCloseNow();
            if (!hubPickOnly(false)) { League.clearMain(); rankSearchGo(); }   /* 옛 native — 예전 길(지우고 첫 판처럼 직접) */
        }
        @Override public void lgmOpenForfeit() {
            final LeagueMenu mm = lgm;
            org.json.JSONObject open = League.startedOpenMatch();
            if (leaving || mm == null) return;
            if (open == null || leagueId != null) { lobbyRedraw(); return; }
            final String id = open.optString("id");
            if (League.forfeit(id) == null) { toast("저장하지 못했어요 — 다시 해 주세요"); gl.queueEvent(mm::recoverNow); return; }
            hubResumePending = false;
            leagueId = id; leagueArmed = true; leagueStarted = true; leagueDone = true;   /* 기권패 결판 — 다음 상대·재대결·그만은 결판 메뉴 그대로 */
            lgmReset();
            leagueExit(id);                                                  /* → hubResult → 덧판 결판을 지금(대기 위 — 코어는 멈춘 채) */
        }
        @Override public void lgmOpenVoid() {
            final LeagueMenu mm = lgm;
            org.json.JSONObject open = League.startedOpenMatch();
            if (leaving || mm == null) return;
            String vk = open != null ? League.voidKind(open, League.appVer) : null;
            if (open != null && vk != null && !League.voidStarted(open.optString("id"), vk)) toast("저장하지 못했어요 — 다시 해 주세요");
            lobbyRedraw();
        }
        @Override public void lgmSeason(int kind) {
            if (leaving) return;
            boolean ff = League.startedOpenMatch() != null;                  /* M1: 멈춘 경기는 기권패로 닫고(두 번 묻기 글이 그렇게 말했다) */
            String why = League.resetBlockedWhy(true, ff);
            if (why == null) {
                cancelReady("시즌 도구 — 대진 전");
                boolean ok = kind == 1 ? League.newSeason(true, ff) : League.restartRanked(true, ff);
                if (ok) {
                    BoardCache.invalidate(); League.sessionReset(); pushMain();
                    hubResumePending = false;                                 /* 175: 알림 글 없음 */
                } else toast("저장하지 못했어요 — 기록은 그대로예요");
            } else toast(why);                                               /* 기록 파일 문제(저장소) — 리그 탓 글 아님 */
            lobbyRedraw();
            lgmDrawer(MenuModel.Prefs.TAB_LEAGUE);
        }
        @Override public void lgmName() {
            if (leaving) return;
            askName();
        }
        @Override public void lgmFallback(int why) {
            boolean result = lgmShown && lgmResultId != null;
            org.json.JSONObject m = result ? League.matchById(lgmResultId) : null;
            LeagueMenu mm = lgm;
            String w = why == LeagueMenu.FB_RECT ? "rect_lost" : why == LeagueMenu.FB_RENDER ? "upload" : why == LeagueMenu.FB_NOT_SHOWN ? "menu_not_shown" : "menu_" + why;
            lgmWhy(w, (result ? "결판" : lgmArmedId != null ? "걸어 둔 결판" : "쉼") + " · " + (mm != null && mm.failWhy() != null ? mm.failWhy() + " · " : "") + lgmState());
            lgmReset();
            if (result && m != null && !leaving && hubCard == null) lgmReopenNow(m, w);   /* 165: 한 번 지금 다시 그려 열기 → 그래도 못 쓰면 옛 카드 */
            else if (!result && leagueDone) { /* 걸쇠 전 결판 걸기 실패 — 걸쇠 때 지금 열기로(lgmResultAtLatch) */ }
            else if (!leaving && leagueId != null && !leagueDone) leagueLeave();   /* 쉼이던 판 — 옛 나가기 카드 */
        }
    };

    /* ── 168 §2 대기(157-B) — 리그 타이틀 위 «리그 메뉴»: OPTION 탭 · 타이틀에서 뒤로 · 멈춤 바 «리그 메뉴» ── */
    private boolean lobbyWanted = false, lobbyViaBack = false;      /* 타이틀에 서면 대기를 연다(바 칸·뒤로를 고르기·돌아가는 중에 누름) · 뒤로로 불렀다(못 열면 예전 갈래 — K6) */
    private long lobbyWantedAt;
    private static final long LOBBY_WANT_MAX = 60000;               /* 그 안에 타이틀 메뉴에 못 서면 버림(데모가 끝없이 도는 등 — 늦게 저절로 열리지 않게) */
    /** 168 고침: 대기를 타이틀에 서면 열기로 — 열리거나(hubTick) 다른 일이 시작될 때까지 남는다(전엔 돌아온 첫 타이틀 틱에서 hubLastState 가 5·9 라 버려졌다) */
    private void lobbyWant(boolean viaBack) { lobbyWanted = true; lobbyViaBack = viaBack; lobbyWantedAt = android.os.SystemClock.uptimeMillis(); }
    private void lobbyUnwant() { lobbyWanted = false; lobbyViaBack = false; }
    /** 대기 자격 — 리그 랭크 판 · 허브 타이틀 · 타이틀 메뉴 화면(데모 아님 — K5) · 경기 기록 없음 · 겹창·카드·창·바 없음 */
    private boolean lobbyEligible(boolean ignoreBar) { return lobbyEligibleAt(hubLastState, ignoreBar); }
    /** st = 허브 상태(hubTick 은 이번 틱 값을 — hubLastState 는 틱 끝에야 바뀐다: 돌아온 첫 타이틀 틱에 5·9 가 남아 미뤄 둔 대기가 버려졌다) */
    private boolean lobbyEligibleAt(int st, boolean ignoreBar) {
        if (lgm == null || !leagueHub || !hubRanked || leaving || !loaded || !resumedNow) return false;
        if (st != 1 || leagueId != null || rankPickOnly || hubResumePending || leagueChoosing) return false;
        if (!hubTitleMenu()) return false;                               /* 168 고침(K5): 게임 메뉴 글자가 정말 떠 있다 — GAME OPTION·COLLECTION·인트로 그림도 화면 0 이다 */
        if (hubGen < hubWaitGen) return false;
        if (rankQ != null || rankQFading != null || lgmShown || hubCard != null || ask != null || lookEditor != null) return false;
        if (sheet != null && sheet.isShowing()) return false;
        return ignoreBar || !(pad != null && pad.isBarOpen());
    }
    private void lgmLobbySync() { LeagueMenu mm = lgm; if (mm != null) mm.setLobbyEligible(lobbyEligible(false)); }
    private static boolean natHubMenu = true;
    /** 168 고침(K5) — 타이틀 메뉴(게임 메뉴 글자)가 지난 진짜 프레임에 떠 있었나. native menu_kind — 옛 native 면 메뉴 화면 번호(0)로 */
    private boolean hubTitleMenu() {
        if (!loaded) return false;
        boolean scr0 = hubDualV != null && hubDualV.length >= 6 && hubDualV[5] == 0;
        if (natHubMenu) {
            try { return scr0 && Emu.nativeHubMenu() != 0; } catch (UnsatisfiedLinkError e) { natHubMenu = false; }
        }
        return scr0;
    }
    /** 대기 사본 — 열린 경기(시작한 것)·무효 가능 여부·내 캐릭터 */
    private MenuModel lobbyModel(int[] v) {
        org.json.JSONObject open = League.startedOpenMatch();
        String vk = open != null ? League.voidKind(open, League.appVer) : null;
        RankCore.Me me = League.rankMe();
        if (me.placementLeft == 0 && BoardCache.get(me, true, 4) == null) BoardCache.warm(this, me, true, 4, false, null);
        return MenuModel.lobby(this, lgmCtx(League.menuPrefs(), v, false, me), open, vk);
    }
    /** 그림 스레드 — 대기 · 기권 두 번 묻기(멈춘 경기가 있을 때) · 리그 나가기 */
    private LgmPages.Scene[] lobbyScenes(MenuModel model) {
        LgmPages pg = lgmPages();
        boolean ff = false;
        for (int r : model.rows) ff |= r == MenuModel.ROW_OPEN_FORFEIT;
        return new LgmPages.Scene[]{ pg.lobby(model, 0), ff ? pg.lobby(model, 1) : null, pg.lobby(model, 2) };
    }
    /** 시즌·이름·무효 뒤 — 대기 사본·장면을 새 값으로(서랍에서 돌아갈 판 · 대기가 보이면 끝 상태로 다시) */
    private void lobbyRedraw() {
        final LeagueMenu mm = lgm;
        if (mm == null || leaving) return;
        final MenuModel model = lobbyModel(lgmStatus());
        if (model == null) { gl.queueEvent(mm::recoverNow); return; }
        if (lgmModel == null || lgmModel.kind == MenuModel.K_LOBBY) lgmModel = model;
        lgmRender(() -> {
            final LgmPages.Scene[] sc = lobbyScenes(model);
            h.post(() -> { if (!leaving) gl.queueEvent(() -> mm.updateLobby(model, sc[0], sc[1], sc[2])); });
        }, () -> { lgmWhy("lobby_render", lgmRenderErr); gl.queueEvent(mm::recoverNow); });
    }
    /** K6 — 대기를 못 쓰는 판에서 뒤로: 게임에서 왔으면 그 게임 · 아니면 게임 목록(리그 화면으로 안 나감) */
    private void lobbyFallback() {
        if (leaving) return;
        if (fromGame()) hubToGame();
        else { cancelReady("게임 속 리그 — 대진 전에 목록으로"); lgmCloseNow(); leagueHub = false; goList(); }
    }
    /** 멈춤 바 «리그 메뉴» 칸(리그 판) — 경기 중이면 쉼 · 결판이 떠 있으면 그대로 · 찾기 겹창이면 그만 · 그 밖은 대기(타이틀이 아니면 타이틀로 보내고 거기서) */
    private void leagueMenuCell() {
        final LeagueMenu mm = lgm;
        if (mm == null || leaving || !loaded) return;
        if (mm.isOpen() || lgmShown || hubCard != null) return;
        if (lgmEligible(true)) { gl.queueEvent(mm::requestPause); return; }
        if (leagueId != null && leagueArmed && !leagueDone && leagueStarted) return;   /* 점수가 걸린 경기인데 쉼을 못 엶(승리 대사 등) — 아무 일 없음 */
        if (rankQ != null || rankQFading != null) rankQBack();
        if (lobbyEligible(true)) { gl.queueEvent(mm::requestLobby); return; }
        lobbyWant(false);
        if (leagueId != null || hubLastState != 1 || !hubTitleMenu()) { rankOppV = null; hubReturn(-1); }   /* 고르기·데모·게임 옵션·컬렉션 — 타이틀로(준비 기록은 무효) */
    }
    /** 168(11) «내 이름» — 게임 위 카드에 글 칸(덧판·코어는 멈춘 채). 정하면 기록(me.name — 글꼴에 있는 글자만 · 비우면 «나») → 리그 탭·대기 다시 그림 */
    private void askName() {
        if (ask != null || root == null) { lgmDrawer(MenuModel.Prefs.TAB_LEAGUE); return; }   /* 카드를 못 띄움 — 서랍을 다시 그려 바쁨을 푼다 */
        final android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setText(League.myNick());
        try { e.setSelection(e.getText().length()); } catch (Exception ignored) { }
        e.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(League.NAME_MAX + 4) });
        e.setTextColor(0xfff3ead7); e.setTypeface(hubFont()); e.setTextSize(18);
        final boolean[] done = { false };
        Ask d = ask("내 이름", "한글·영문·숫자 " + League.NAME_MAX + "자까지 · 비우면 «나»\n이름표·VS 띠·결판·순위표에 나와요")
                .field(e).sfx()
                .btn("정하기", () -> {
                    done[0] = true;
                    String before = League.myNick();
                    if (!League.setMyName(e.getText().toString())) toast("저장하지 못했어요 — 이름은 그대로예요");
                    else if (!before.equals(League.myNick())) BoardCache.invalidate();
                    hideKeyboard(e);
                    lobbyRedraw();
                    lgmDrawer(MenuModel.Prefs.TAB_LEAGUE);
                })
                .btn("취소", () -> { done[0] = true; hideKeyboard(e); lgmDrawer(MenuModel.Prefs.TAB_LEAGUE); })
                .onDismiss(() -> { if (!done[0]) { hideKeyboard(e); lgmDrawer(MenuModel.Prefs.TAB_LEAGUE); } });
        d.show();
        e.requestFocus();
        try {
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(e, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        } catch (Exception ignored) { }
    }
    private void hideKeyboard(View v) {
        try {
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null && v != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception ignored) { }
    }

    /** 서랍 쪽 그리기 — 순위(없으면 «먹 가는 중…» 먼저, 데워지면 다시) · 전적 · 리그(168) · 설정 */
    private void lgmDrawer(final int tab) {
        final LeagueMenu mm = lgm;
        final MenuModel model = lgmModel;
        if (mm == null || model == null) return;
        final int gen = lgmGen;
        if (tab == MenuModel.Prefs.TAB_LEAGUE) {                           /* 168 §2 리그 탭 — 시즌 도구·내 캐릭터는 대기에서 연 서랍만(쉼·결판에서는 시즌·내 이름·목록만) */
            final MenuModel.LeagueInfo li = League.leagueInfo(model.kind != MenuModel.K_LOBBY);
            lgmRender(() -> { final LgmPages.Scene sc = lgmPages().league(model, li, 0); h.post(() -> lgmDrawerShow(gen, tab, sc)); }, () -> lgmWhy("drawer_render", lgmRenderErr));
            return;
        }
        if (tab == MenuModel.Prefs.TAB_SETTINGS) {
            final MenuModel.Prefs p = League.menuPrefs();
            lgmRender(() -> { final LgmPages.Scene sc = lgmPages().settings(p, 0); h.post(() -> lgmDrawerShow(gen, tab, sc)); }, () -> lgmWhy("drawer_render", lgmRenderErr));
            return;
        }
        if (tab == MenuModel.Prefs.TAB_RECORD) {
            lgmRender(() -> { final LgmPages.Scene sc = lgmPages().record(model); h.post(() -> lgmDrawerShow(gen, tab, sc)); }, () -> lgmWhy("drawer_render", lgmRenderErr));
            return;
        }
        final RankCore.Me me = League.rankMe();
        BoardCache.Board around = me.placementLeft == 0 ? BoardCache.get(me, true, 4) : null;
        BoardCache.Board top = BoardCache.get(me, false, 100);
        boolean wantAround = me.placementLeft == 0 && around == null, wantTop = top == null;
        if (wantAround) BoardCache.warm(this, me, true, 4, false, () -> { if (lgmDrawerTab == MenuModel.Prefs.TAB_BOARD && gen == lgmGen) lgmDrawer(tab); });
        if (wantTop) BoardCache.warm(this, me, false, 100, false, () -> { if (lgmDrawerTab == MenuModel.Prefs.TAB_BOARD && gen == lgmGen) lgmDrawer(tab); });
        final BoardCache.Board a0 = around, t0 = top;
        lgmRender(() -> {
            BoardCache.Board a = lgmNicks(a0), t = lgmNicks(t0);          /* 단상(top3)도 같은 닉 */
            final LgmPages.Scene sc = lgmPages().board(model, me.placementLeft == 0 ? a : null, t);
            h.post(() -> lgmDrawerShow(gen, tab, sc));
        }, () -> lgmWhy("drawer_render", lgmRenderErr));
    }
    private void lgmDrawerShow(int gen, int tab, LgmPages.Scene sc) {
        final LeagueMenu mm = lgm;
        if (mm == null || gen != lgmGen || lgmDrawerTab != tab || leaving) return;
        gl.queueEvent(() -> mm.showDrawer(tab, sc));
    }
    /** 순위표 줄의 닉 — 리그 화면과 같게 프로필 닉(만난 상대는 저장한 그대로). 그림 스레드에서(판은 고치지 않고 사본) */
    private BoardCache.Board lgmNicks(BoardCache.Board b) {
        if (b == null) return null;
        BoardCache.Board o = new BoardCache.Board();
        o.key = b.key; o.rank = b.rank; o.total = b.total; o.around = b.around; o.k = b.k; o.at = b.at;
        o.rows = lgmNickRows(b.rows); o.top3 = lgmNickRows(b.top3);
        return o;
    }
    private java.util.List<RankCore.Row> lgmNickRows(java.util.List<RankCore.Row> rows) {
        if (rows == null) return null;
        java.util.List<RankCore.Row> out = new java.util.ArrayList<>(rows.size());
        for (RankCore.Row r : rows) {
            RankCore.Row c = new RankCore.Row();
            c.rank = r.rank; c.id = r.id; c.nick = r.nick; c.label = r.label; c.lp = r.lp; c.mr = r.mr; c.ch = r.ch; c.sty = r.sty; c.wr = r.wr; c.me = r.me; c.p = r.p;
            if (!r.me && r.p != null) { try { c.nick = League.nickOf(this, r.p); } catch (Exception ignored) { } }
            out.add(c);
        }
        return out;
    }

    /* 터치 — 게임 그림 자리 그대로 맨 위(패드 위). DOWN 이 메뉴 자리가 아니면 안 받는다(패드로 감) */
    private void lgmTouchAttach() {
        if (lgmTouch != null || root == null) return;
        lgmTouch = new View(this);
        lgmTouch.setOnTouchListener(lgmTouchL);
        root.addView(lgmTouch, new FrameLayout.LayoutParams(1, 1, android.view.Gravity.TOP | android.view.Gravity.LEFT));
        lgmTouchPlace();
    }
    private void lgmTouchDetach() { if (lgmTouch != null) { root.removeView(lgmTouch); lgmTouch = null; } }
    private void lgmTouchPlace() {
        LeagueMenu mm = lgm;
        if (mm != null && root != null) mm.setPortrait(root.getHeight() >= root.getWidth());
        if (lgmTouch == null || !(lgmTouch.getLayoutParams() instanceof FrameLayout.LayoutParams) || gameBox[2] <= gameBox[0]) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lgmTouch.getLayoutParams();
        lp.width = gameBox[2] - gameBox[0]; lp.height = gameBox[3] - gameBox[1]; lp.leftMargin = gameBox[0]; lp.topMargin = gameBox[1];
        lgmTouch.setLayoutParams(lp);
    }
    private final View.OnTouchListener lgmTouchL = new View.OnTouchListener() {
        @Override public boolean onTouch(View v, android.view.MotionEvent e) {
            final LeagueMenu mm = lgm;
            if (mm == null || (!mm.isOpen() && !lgmShown) || ask != null) return false;
            int[] s = lgmStatus();
            if (s == null || s[Emu.OVS_VW] <= 0 || s[Emu.OVS_VH] <= 0) return false;
            final int gx = (int) Math.floor((e.getX() - s[Emu.OVS_VX]) * 160f / s[Emu.OVS_VW]), gy = (int) Math.floor((e.getY() - s[Emu.OVS_VY]) * 152f / s[Emu.OVS_VH]);
            int act = e.getActionMasked();
            final int a = act == android.view.MotionEvent.ACTION_DOWN ? LeagueMenu.TOUCH_DOWN : act == android.view.MotionEvent.ACTION_UP ? LeagueMenu.TOUCH_UP
                    : act == android.view.MotionEvent.ACTION_MOVE ? LeagueMenu.TOUCH_MOVE : act == android.view.MotionEvent.ACTION_CANCEL ? LeagueMenu.TOUCH_CANCEL : -1;
            if (a < 0) return true;
            if (a == LeagueMenu.TOUCH_DOWN && !mm.hitTest(gx, gy)) return false;
            if (a != LeagueMenu.TOUCH_MOVE) gl.queueEvent(() -> mm.onTouch(gx, gy, a));
            return true;
        }
    };

    /* ── 치트(cheat.c · Cheats) ─────────────────────────────────────────
       일반 세션이고 사무쇼2이며 판정 주소를 확인한 롬 판(연습과 같은 판 확인 — practiceRomOk)이면 options 값을 native 에 넣는다.
       리그 판(랭크전 한 경기·게임 속 리그)은 잠근다 — 값을 아예 안 넣고, native 도 허브 종류를 보고 한 번 더 막는다(기록이 깨끗하게).
       코어 쪽은 올리고 내릴 때 이미 모두 끈다. 게임 안 「설정」 › «치트»에서 바꾸면 닫을 때 값만 다시 넣는다(코어를 다시 올리지 않음). */

    private volatile boolean cheatOk = false, cheatFight = false;   /* cheatSetup 은 GL 스레드(롬 바뀜 다시 로드)에서도 돈다 */
    private int cheatPermPrev = -1;
    private TextView cheatTag;

    /** 코어를 올린 뒤(loadCore — UI·GL 스레드 어느 쪽에서든) */
    private void cheatSetup() {
        cheatOk = false; cheatActiveMask = 0; viewBits = 0;
        if (!loaded) { cheatPillPost(false); return; }
        java.util.Map<String, String> m = Settings.load();
        if (game == null || !Cheats.GAME.equals(game.id)) { cheatPillPost(false); return; }
        cheatOk = ranked() ? hubVersionProblem() == null : practiceRomOk();   /* 168 §9: 리그 판도 — 리그 사본은 판정 주소를 확인한 판(허브가 이미 본다) */
        if (!cheatOk) { if (Cheats.any(m) && !ranked()) toast("이 롬 판에선 치트가 안 켜져요"); cheatActiveMask = 0; cheatPillPost(false); return; }
        pushCheats();
        cheatPillPost(true);
        h.post(new Runnable() { @Override public void run() { h.removeCallbacks(cheatPoll); h.postDelayed(cheatPoll, 500); } });
    }
    private void cheatPillPost(final boolean on) { h.post(new Runnable() { @Override public void run() { if (pad != null) pad.setCheatPill(on); } }); }

    /** 168 §9 — «치트» 알약(176: 바 «치트» 칸도): 치트 창만 바로. 확인 카드·컬러 만들기 창·다른 창이 떠 있으면 무시 —
     *  게임 속 리그 덧판은 쉬는 메뉴(쉼·서랍·대기)면 그 위에 연다(cheatLgmOk). 닫으면 값만 넣는다(코어를 다시 올리지 않음) */
    private void openCheatSheet() {
        if (!cheatCellOk() || (sheet != null && sheet.isShowing())) return;
        final String orig = getIntent().getStringExtra("rom");
        padMask = 0; releasePhysical();
        Emu.nativeSetPaused(true);
        final int[] cg0 = cheatGame();
        sheet = LaunchSheet.showCheats(this, game, orig != null ? new File(orig) : null, ranked());
        if (fdView != null) fdView.setVisibility(View.GONE);             /* 176: 창이 떠 있는 동안 프레임 미터 숨김(바와 같게) */
        sheet.onClose = new Runnable() { @Override public void run() {
            Emu.nativeSetPaused(barOpen() || hubCard != null || lgmShown);   /* 덧판 쉼·대기는 native ov_hold 가 따로 멈춰 둔다 */
            cheatClose(cg0, true);                                        /* 175: 알약 길은 글 없음 — 알약 «치트 ON»·왼쪽 위 «치트» 가 보인다 */
            if (fdView != null && fdWanted) fdView.setVisibility(barOpen() ? View.GONE : View.VISIBLE);
        } };
    }
    /** 176 검토 — 치트 창을 열 수 있나(알약·바 «치트» 칸 공통 · 설정 창은 따로 막지 않음). 바 칸은 못 열 때 숨긴다(cheatCellSync) — 눌러도 바만 접히던 칸 */
    private boolean cheatCellOk() {
        return cheatOk && loaded && !leaving && ask == null && lookEditor == null && cheatLgmOk();
    }
    /** 덧판이 없거나 쉬는 메뉴(쉼·쉼 확인·서랍·대기 — 코어는 ov_hold 로 멈춘 채, 창이 떠 있는 동안 키는 창으로)면 true.
     *  결판(연속 대전 도화선이 돈다)·여는 중·닫는 중은 false */
    private boolean cheatLgmOk() {
        LeagueMenu mm = lgm;
        if (mm == null) return true;
        int s = mm.state();
        if (s == LeagueMenu.ST_CLOSED || s == LeagueMenu.ST_ARMED) return !lgmShown;
        return s == LeagueMenu.ST_PAUSE || s == LeagueMenu.ST_CONFIRM || s == LeagueMenu.ST_DRAWER || s == LeagueMenu.ST_LOBBY;
    }
    /** 바 «치트» 칸 보이기 — 바를 열 때·hubTick(덧판 상태)마다. 알약을 쓸 수 없는 판이면 칸은 원래 없다(setCheatPill) */
    private void cheatCellSync() { if (pad != null) pad.setCheatCellOff(!cheatCellOk()); }
    /** 176 검토 — 지금 옵션의 게임 치트 값(보기 «프레임 표시»·«대미지 미터» 뺌 · 리그 판은 올카드도 뺌) */
    private int[] cheatGame() { return Cheats.gameVals(Settings.load(), ranked()); }
    /** 치트 창을 닫을 때 — 바꾼 값을 넣고, 게임 치트가 바뀐 때만 «치트 적용»(보기만 바꿨으면 말없이) */
    private void cheatClose(int[] before, boolean quiet) {           /* quiet = 알약 길(175: 글 없음) · 리그 판도 글 없음(175) */
        if (sheet != null && sheet.cheatsChanged() && pushCheats() && !quiet && !ranked() && !java.util.Arrays.equals(before, cheatGame())) toast("치트 적용");
    }
    /** 168 §9 — 이 판에 먹은 치트(native 가 무장 때 비움 · 옛 native 면 0) */
    private static boolean natCheatMatch = true;
    private int cheatMatch() {
        if (!natCheatMatch || !loaded) return 0;
        int b;
        try { b = Emu.nativeCheatMatch(false); } catch (UnsatisfiedLinkError e) { natCheatMatch = false; return 0; }
        return ranked() ? b & ~(1 << Cheats.CARDS) : b;     /* 168 고침: 리그는 늘 카드 전부 열림 — 올카드는 게임에 차이가 없으니 «치트»로 안 적는다(안전망 · pushCheats 가 0 을 넣음) */
    }
    private int leagueCheatSeen = 0;                 /* 이 경기에 먹은 치트 — 경기 기록(cheat_bits)에 적은 것 */
    private volatile int cheatActiveMask = 0;        /* 168 고침: 지금 먹는 치트(옵션 값 · 리그 판은 올카드 뺌) — 알약 «치트 ON» */
    private int cheatBitsNow() { return leagueCheatSeen | cheatMatch(); }
    /** leaguePoll 마다 — 새로 먹은 치트가 있으면 경기 기록에 */
    private void leagueCheatSync() {
        if (leagueId == null || !leagueArmed || leagueDone) return;
        int cb = cheatMatch();
        if ((cb & ~leagueCheatSeen) == 0) return;
        leagueCheatSeen |= cb;
        League.orCheat(leagueId, leagueCheatSeen);
    }

    /** options 의 치트 값을 native 에 — 넣었으면 true(일반 세션 · 확인한 판일 때만) */
    private boolean pushCheats() {
        if (!loaded || !cheatOk) return false;
        java.util.Map<String, String> m = Settings.load();
        Cheats.push(m);
        if (ranked()) Emu.nativeCheatSet(Cheats.CARDS, 0);  /* 168 고침: 리그 판엔 올카드를 안 넣는다 — 리그 치트 창에 그 줄이 없고(끌 수 없음) 리그는 이미 카드 전부 열림.
                                                              넣으면 리그 세이브를 다시 읽는 순간(prog 03→06 · 카드 8 미만) 비트가 서서 매 판 «치트»로 적혔다. «세이브에 남기기»도 리그 세이브에 안 들어감 */
        cheatFight = Cheats.fight(m);
        cheatActiveMask = Cheats.activeMask(m, ranked());
        final int vb0 = viewBits;
        viewBits = Cheats.views(m);                                      /* 176 보기 — native 는 위 push 로 이미 받았다 */
        final boolean retag = ((vb0 ^ viewBits) & 2) != 0;
        h.post(new Runnable() { @Override public void run() {
            h.removeCallbacks(viewPoll); h.post(viewPoll);
            if (retag && lgmTagsId != null && lgmTagsId.equals(leagueId) && !leagueDone) lgmTagsArm();   /* 리그 경기 중 대미지 미터를 켜고 끔 — 이름표 폭 다시 */
        } });
        final boolean act = cheatActiveMask != 0;
        h.post(new Runnable() { @Override public void run() { if (pad != null) pad.setCheatActive(act); } });   /* 168 고침: 알약 «치트 ON» — 리그 판의 경기 중 표시 */
        return true;
    }

    /* ── 176 치트 보기 — «프레임 표시»·«대미지 미터»(practice.c 연습 표시 그대로) ─────────────────
       숫자는 native 가 게임 HUD 칸에 그대로 그린다(연습과 같은 칸). 여기선 프레임 미터(연습과 같은 FrameMeterView · 자리·끌기·톡 그대로)만 켜고 끈다.
       연습 허브가 도는 판(일반 판 «리그·연습» 켬)은 practicePoll 이 연습 «표시»와 함께 정하고, 안 도는 판(리그 판·«리그·연습» 끔)은 viewPoll 이.
       게임 속 리그 덧판 메뉴(쉼·서랍·결판)가 떠 있으면 숨김 · 바·창이 떠 있으면 숨김(onMenu·sheetUp). 보기는 기록에 «치트»로 안 남는다(Cheats.activeMask). */
    private volatile int viewBits = 0;                               /* 1 프레임 표시 · 2 대미지 미터 — pushCheats 가 정함(확인한 판만 · 아니면 0) */
    private final Runnable viewPoll = new Runnable() { @Override public void run() {
        if (leaving || !cheatOk || (viewBits & 1) == 0) { if (!practiceHub) setFrameMeter(false); return; }
        if (!practiceHub && loaded) setFrameMeter(viewFdNow());
        h.postDelayed(this, 150);                                    /* practicePoll 과 같은 박자 */
    }};
    /** native 가 지금 보기 미터를 켰나(1인·무한 대전 전투측 · 연습 흐름 밖) — 덧판 메뉴가 떠 있으면 false */
    private boolean viewFdNow() {
        if ((viewBits & 1) == 0 || !loaded || !cheatOk) return false;
        if (lgm != null && (lgm.isOpen() || lgmShown)) return false;
        if (hubCard != null || ask != null || (leagueDlg != null && leagueDlg.isShowing()) || postState() > 0) return false;   /* 176 검토: 옛 결과 카드·확인 카드·결판 뒤(정산 대기)도 */
        int[] v = Emu.nativeViewStatus();
        return v != null && v.length > 2 && v[2] == 1;
    }

    /** 1초마다 — 왼쪽 위 작은 «치트» 표시(먹는 치트가 있을 때 · 리그 이름표와 같은 자리, 색만 흐리게), «세이브에 남기기»가 세이브에 들어간 순간 한 번 알림 */
    private final Runnable cheatPoll = new Runnable() { @Override public void run() {
        if (leaving || !cheatOk) { showCheatTag(null); return; }
        if (loaded) {
            int[] v = Emu.nativeCheatStatus();
            if (v != null && v.length >= 16) {
                showCheatTag(ranked() || v[1] == 0 || v[0] != 0 ? null : v[7] == 3 && cheatFight ? "치트 · 연습 중엔 경기 치트 쉼" : "치트");   /* 168: 리그 판엔 이름표(HUD)와 겹치니 안 띄움 — 결판 «치» 도장·기록만 */
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
       화면 아래 띠에는 버튼 안내만(설계 4-사). 정보는 게임 화면 안(캐릭터·상대 선택 머리말·스테이지 칸·연습 메뉴 창·대미지 숫자)에 있다. */

    /** 연습을 켤 수 있는 판인가 — 사무쇼2 일반 세션이고 사본이 실측한 판이거나 그 판에 배경음악만 심은 것 */
    private boolean practiceRomOk() {
        if (!loaded || ranked() || game == null || !"ss2".equals(game.id)) return false;
        String md5 = League.md5(new File(romPath));
        return League.romLabel(md5) != null || Patcher.musicBaseLabel(romPath, md5) != null;
    }

    /** 실행 창 «연습 대전으로 ›» 로 막 연 판 — 그러면 자동 저장 이어하기를 건너뛰고 전원부터 «캐릭터 선택»(내 캐릭터 먼저 → 상대)까지 대신 누른다
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
            setFrameMeter((hs != null && ps != null && hs[0] == 4 && ps.length > 24 && ps[24] == 1) || viewFdNow());   /* 연습 메뉴 «표시: 프레임·둘 다» — 경기 중에만 · 176 치트 «프레임 표시»(일반 경기) */
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

    /** 띠 안내 — 버튼만(허브 상태 7 내 캐릭터 먼저 · 2 상대 선택 · 3 내 캐릭터 확정 뒤 · 4 경기). 타이틀은 셋째 줄에 커서가 있을 때만(◀▶ 고르기), 그 밖·돌아가는 중엔 숨긴다.
     *  버튼 이름은 패드 글자 그대로(A · B · OPTION) — 레퍼런스: 철권8 «[BACK]+[←] 벽을 등지고», KOF XV «왼쪽 = 화면 왼쪽 끝» 처럼 방향마다 결과를 적는다 */
    /** 내 캐릭터 쪽 버튼 안내 — 화면마다(캐릭터·검질: A 결정 · OPTION 몸 컬러 · A+B 베기 색 — B 도 결정(검질은 «뒤로»)이지만 안내엔 안 씀 · 카드: 게임 그대로).
     *  그 밖(이야기·라운드 연출)은 빈 줄/숨김 */
    static String myPickKeys(int scr, boolean league) {
        if (!lookKeys && (scr == 2 || scr == 4)) return "A 결정";      /* 설정 «컬러 고르기» 끔 — OPTION·B·A+B 는 게임 그대로(아무 일 없음) */
        if (scr == 2 || scr == 4) return "A 결정  ·  OPTION 컬러  ·  A+B 베기 색";
        if (scr == 6) return league ? "카드 고르기 (전부 열림)" : "카드 고르기";
        return league ? "곧 시작" : null;
    }

    private static String practiceText(int hubState, int[] ps, int[] dv) {
        switch (hubState) {
        case 1:
            if (dv != null && dv.length >= 5 && dv[0] == 1 && dv[3] == 1)
                return "◀▶ 연습 대전 / 리그 대전  ·  A 시작" + (dv[1] == 0 ? "\n리그는 따로 세이브 판에서 해요" : "");
            return null;
        case 2: return "A 수라 · B 나찰 · OPTION 스테이지";   /* 고르는 캐릭터 = 더미(검질은 A·B) — 머리말이 이미 «상대 선택». «상대 ·» 를 붙이면 세로 화면에서 두 줄로 꺾인다(153 에뮬 확인) */
        case 7:   /* 내 캐릭터 먼저(캐릭터 선택 — A 결정 · OPTION 컬러 · A+B 베기 색), 그다음 상대(2) */
        case 3: return myPickKeys(dv != null && dv.length >= 6 ? dv[5] : 2, false);
        case 4:   /* 좁은 띠(버튼 사이)에서도 줄이 접히지 않게 두 줄로 */
            if (ps.length > 1 && ps[1] != 0) return "↑↓ 고르기 · ←→ 바꾸기\nA 실행 · B 닫기";
            return "OPTION 메뉴 · OPTION+↓ 리셋\n+← 왼쪽 · +↑ 가운데 · +→ 오른쪽";
        default: return null;
        }
    }

    /* ── 모든 모드 컬러(look.c) ─────────────────────────────────────────
       사무쇼2 확인한 판(일반 판 practiceRomOk · 리그 판 hubVersionProblem 없음)이고 설정 «컬러 고르기»가 켬이면 1인·무한·연습·리그 어디서나
       캐릭터·검질 고르기에서 OPTION = 다음 컬러(길게 = 컬러 만들기) · A+B = 베기 색 다음 것 · A·B = 결정(보통 색). 기억(캐릭터마다)·내 색·베기 색은
       앱 설정(Look) — native 가 바꾼 기억·베기 색은 폴(200ms)이 받아 남긴다. 띠 안내는 허브 띠가 없는 화면(일반 1인·무한 대전)의 고르기 화면에서만. */
    private boolean lookOn = false;
    private static volatile boolean lookKeys = true;   /* 띠의 «OPTION 컬러 · A+B 베기 색» 안내를 보일지(컬러가 켜진 판) — 연습·리그 띠(myPickKeys)도 본다 */
    private int[] lookV;                         /* 마지막 nativeLookStatus */
    private int lookMemGen = 0, lookEditReq = 0, lookLost = 0, lookRecColor = -9, lookFxGen = 0;
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
            if (v != null && v.length >= 18) lookFxGen = v[15];             /* 165 베기 색 — A+B 로 바뀐 횟수(옛 native 는 14칸) */
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

    /** A+B 로 바꾼 베기 색 — 하나로 기억(폴 · onPause: 떠나기 200ms 안에 바꾼 것도 — 165 리뷰) */
    private void lookSaveFx() { lookSaveFx(Emu.nativeLookStatus()); }
    private void lookSaveFx(int[] v) {
        if (v != null && v.length >= 18 && v[15] != lookFxGen) { lookFxGen = v[15]; Look.saveFx(v[14]); }
    }

    private void lookTick(int[] v) {
        lookV = v;
        if (v[2] != lookMemGen) lookSaveMem();                           /* OPTION 으로 바꾼 컬러 — 캐릭터마다 기억(다음에도 그 색) */
        lookSaveFx(v);                                                   /* A+B 로 바꾼 베기 색 */
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

    /** 일반 1인·무한 대전 고르기 화면의 띠 — 캐릭터: «A 결정 · OPTION 컬러 · A+B 베기 색» / «OPTION 길게 = 컬러 만들기», 검질: 첫 줄만. 그 밖 null */
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
