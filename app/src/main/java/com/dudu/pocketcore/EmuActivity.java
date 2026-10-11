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
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        /* 볼륨 키 = 미디어(게임 소리) 음량 — 안 정하면 삼성은 «재생 중»을 못 알아챌 때 벨소리 음량을 바꾼다(유저 2026-10-10 「볼륨 조절이 앱에서 안 되던데」) */
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        romPath = getIntent().getStringExtra("rom");
        game = Games.identify(romPath);
        romType = (game != null) ? game.id : "svc";
        lang = readLang();
        {   /* 언어 하나로 두 축을 함께 움직인다.
               한국어면 번역 패치를 **사본**에 입히고(유저 롬은 안 건드린다) 그 패치가 덮은
               언어로 코어를 맞춘다. 일본어·영어는 롬에 원래 든 것이라 패치가 필요 없고
               코어 설정만 바꾸면 된다. ngp_language 는 코어가 뜰 때 읽으므로 **로드 전에** 써야 한다. */
            String p = Patcher.resolve(this, romPath, game, lang,
                    "enabled".equals(readOpt("pocketcore_svc_fastrom", "disabled")),
                    true);                                   /* 조작 패치(mods) 중 켜진 것 적용 */
            patched = !p.equals(romPath);
            romPath = p;
            persistOption("ngp_language", Games.ngpLanguage(game, lang));
        }
        autoSave = "enabled".equals(readOpt("pocketcore_autosave", "enabled"));
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
                Emu.nativeSetPaused(open || (sheet != null && sheet.isShowing()));
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

        loadCore();
        watchRom();
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
        if (pad != null) pad.setArt(!"flat".equals(or(m.get("pocketcore_padskin"), "art")));
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
        int rc = Emu.nativeLoad(corePath(), romPath,
                MainActivity.sysDir().getAbsolutePath(),
                MainActivity.saveDir().getAbsolutePath(),
                MainActivity.optsFile().getAbsolutePath());
        loaded = rc == 0;
        snapCoreOpts();
        romMtime = new File(romPath).lastModified();
        toast(loaded ? coreLabel : "코어/롬 로드 실패 (code " + rc + ")");
        /* 이어하기 — 옵션 바꾸고 다시 연 경우는 그 직전 상태(resume), 아니면 나갈 때 자동 저장해 둔 자리. 로드와 같은 스레드라 안전하다. */
        String resume = getIntent().getStringExtra("resume");
        boolean resumed = false;
        if (loaded && resume != null && new File(resume).exists()) {
            if (Emu.nativeLoadState(resume) == 0) { toast("옵션 적용 — 그 자리에서 이어하기"); resumed = true; }
            new File(resume).delete();
        } else if (loaded && autoSave && autoStatePath().exists()
                && Emu.nativeLoadState(autoStatePath().getAbsolutePath()) == 0) {
            toast("이어하기"); resumed = true;
        }
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
        final int mix = Settings.pctOf(m, "pocketcore_upscaler_mix", 100);
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
        "ngp_framegen", "ngp_framegen_mode", "ngp_framegen_mult", "ngp_framegen_fx", "ngp_framegen_pose", "ngp_runahead",
        "ngp_svcsp_band" };
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
                Intent i2 = new Intent(EmuActivity.this, MainActivity.class);
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
        goList();
    }

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
            if (pad != null && pad.isBarOpen() && (gamepad || navKey)) {
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
        releasePhysical();
        try { ((android.hardware.input.InputManager) getSystemService(INPUT_SERVICE))
                .unregisterInputDeviceListener(devListener); } catch (Exception ignored) { }
        if (loaded) {
            Emu.nativeSaveSram();
            /* 오토세이브 — SRAM 저장과 같은 자리·같은 방식(UI 스레드 직접 호출 전례).
               gl.onPause() 전이어야 한다. */
            if (loaded && autoSave) Emu.nativeSaveState(autoStatePath().getAbsolutePath());   /* 다시 굽으려 내린 뒤엔 안 쓴다 */
        }
        /* 오디오 장치를 놓는다 — 백그라운드에서 물고 있으면 다른 앱 재생 뒤
           스트림이 죽은 채 돌아오는 무음 사고가 난다. 복귀 때 새로 연다. */
        Emu.nativeAudioPause();
        gl.onPause();
    }

    @Override protected void onResume() {
        super.onResume(); Orient.apply(this); immersive(); gl.onResume();
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
}
