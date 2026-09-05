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
    private long romMtime;
    private int padMask = 0, keyMask = 0;
    private String romType = "svc";   /* Games 표의 id. 모르는 롬이면 "svc"(순정 코어) */
    private Games.Game game;          /* 표에 있는 게임이면 여기 — 코어·한패·음성팩이 다 들어 있다 */
    private boolean patched = false;  /* 번역 패치 사본을 실행 중인가 */
    private String lang = "ko";       /* ko=번역 패치 / ja·en=롬에 원래 든 언어 */
    private int slot = 1;
    private boolean autoSave = true;  /* 나갈 때 자동 저장, 열 때 이어하기 */
    /* v4 로스터 11인 — 재캐스팅 아웃(샤를로트/소게츠/모로즈미/유가) 제외 */
    private boolean loaded = false;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
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

        pad = new PadView(this);
        /* 패드는 네 벌 — SS2 전용, SvC(원버튼 6키), KOF R-2(R=SP 4키), 순정 NGPC(A·B 만).
           엔진 없는 게임에 기술·강약 버튼을 두면 안 나가는 버튼이 화면만 차지한다.
           배치 파일은 **게임마다** 따로다. */
        String profile = (game != null && game.has(Games.F_SP_SS2)) ? "ss2"   /* 게임 표의 features 가 단일 출처 */
                       : (game != null && game.has(Games.F_SP_SVC)) ? "svc"
                       : (game != null && game.has(Games.F_SP_KOF)) ? "kof" : "ngp";
        pad.setProfile(profile, (game != null) ? game.id : "ngp");
        /* 강약 구분이 꺼져 있으면 화면의 전용 강P·강K 는 뺀다 — 그 모드에선 A·B 꾹이 강이다(유저 2026-09-04).
           「강 발동 맞춤」이 중간이면 꾹 강이 즉발과 같은 프레임이라 전용 버튼이 할 일이 더 없다. */
        pad.setSvcStrongKeys(!"disabled".equals(readOpt("ngp_svcsp_basics", "enabled")));
        /* 상단바의 게임별 칸 — 코어가 이 게임에서 실제로 쓰는 기능만. 게임 표가 단일 출처다. */
        pad.setCoreFeatures(game != null && game.has(Games.F_BAND),
                            game != null && game.has(Games.F_SIDES));
        pad.setListener(new PadView.Listener() {
            @Override public void onMask(int mask) { padMask = mask; }
            @Override public void onAction(int action) { handleAction(action); }
            @Override public void onTurbo(boolean on) { Emu.nativeSetTurbo(on); }
            @Override public void onScreenDrag(float dxFrac, float dyFrac) {
                int w = root.getWidth(), hgt = root.getHeight();
                int gw = w * scrPct / 100, gh = hgt * scrPct / 100;
                if (w > gw)  scrX = clamp(scrX + Math.round(dxFrac * w * 100f / (w - gw)), 0, 100);
                if (hgt > gh) scrY = clamp(scrY + Math.round(dyFrac * hgt * 100f / (hgt - gh)), 0, 100);
                placeScreen();
            }
            @Override public void onScreenScale(int dPct) {
                scrPct = clamp(scrPct + dPct, 20, 100);
                placeScreen(); persistScreen();
            }
            @Override public void onScreenDrop() { persistScreen(); }
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
        checkVoicePack();
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
        int top = (w > hgt) ? Math.round(Math.min(w, hgt) * 0.05f) : 0;
        int capH = (hgt - top) * scrPct / 100;
        if (gh > capH) { gh = capH; gw = gh * gameW / fh; }
        if (gameW != fw) gw = w;
        android.util.Log.i("PocketCore", "placeScreen root " + w + "x" + hgt + " frame " + fw + "x" + fh + " box " + gw + "x" + gh);
        int mx = (w - gw) * clamp(scrX, 0, 100) / 100;
        int my = top + (hgt - top - gh) * clamp(scrY, 0, 100) / 100;
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

    /** 해설 음성팩이 있어야 하는 게임인데 없으면 알려 준다 — 조용한 실패가 제일 나쁘다. */
    private void checkVoicePack() {
        if (game == null || game.voice == null) return;
        if (new File(MainActivity.sysDir(), game.voice).exists()) return;
        if (new File(MainActivity.sysDir(), "ngpvoice.pak").exists()) return;  /* 옛 이름 */
        h.postDelayed(new Runnable() { @Override public void run() {
            toast("해설 음성팩 없음 — system/" + game.voice + " 에 넣으세요");
        }}, 2500);
    }

    private void loadCore() {
        int rc = Emu.nativeLoad(corePath(), romPath,
                MainActivity.sysDir().getAbsolutePath(),
                MainActivity.saveDir().getAbsolutePath(),
                MainActivity.optsFile().getAbsolutePath());
        loaded = rc == 0;
        romMtime = new File(romPath).lastModified();
        toast(loaded ? coreLabel : "코어/롬 로드 실패 (code " + rc + ")");
        /* 이어하기 — 나갈 때 자동 저장해 둔 자리에서 계속. 로드와 같은 스레드라 안전하다. */
        if (loaded && autoSave && autoStatePath().exists()
                && Emu.nativeLoadState(autoStatePath().getAbsolutePath()) == 0)
            toast("이어하기");
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

    private void handleAction(int action) {
        switch (action) {
        case PadView.ACT_SAVE:
            gl.queueEvent(new Runnable() { @Override public void run() {
                final int rc = Emu.nativeSaveState(statePath().getAbsolutePath());
                toast(rc == 0 ? "상태 저장" : "상태 저장 실패");
            }});
            break;
        case PadView.ACT_LOAD:
            gl.queueEvent(new Runnable() { @Override public void run() {
                final int rc = Emu.nativeLoadState(statePath().getAbsolutePath());
                toast(rc == 0 ? "상태 불러옴" : "저장된 상태 없음");
            }});
            break;
        case PadView.ACT_SHOT:
            gl.queueEvent(new Runnable() { @Override public void run() { screenshot(); }});
            break;
        case PadView.ACT_RESET:
            gl.queueEvent(new Runnable() { @Override public void run() { Emu.nativeReset(); }});
            break;
        case PadView.ACT_SLOT:
            slot = slot % 3 + 1;
            pad.setSlotLabel(slot);
            toast("상태 슬롯 " + slot);
            break;
        case PadView.ACT_BAND:
            toggleCoreOpt("ngp_svcsp_band", "기술명 띠");
            break;
        case PadView.ACT_SIDES:
            toggleCoreOpt("ngp_ss2sp_sides", "기둥 아트");
            break;
        case PadView.ACT_CFG:
            /* 게임을 켠 채로 설정을 연다. 돌아오면 onResume 이 화면 설정을 다시 읽는다. */
            startActivity(new Intent(this, SettingsActivity.class)
                    .putExtra("rom", getIntent().getStringExtra("rom")));  /* 이 게임 설정만 */
            break;
        case PadView.ACT_PICK:
            goList();
            break;
        }
    }

    /** 코어 옵션을 게임 중에 즉시 뒤집는다. 화면 크기가 바뀌는 것(띠·기둥)은
     *  onDrawFrame 이 프레임 크기 변화를 보고 화면 상자를 다시 잡는다. */
    private void toggleCoreOpt(String key, String ko) {
        boolean on = !"disabled".equals(readOpt(key, "enabled"));
        String v = on ? "disabled" : "enabled";
        Emu.nativeSetOption(key, v);
        persistOption(key, v);
        toast(ko + (on ? " 끔" : " 켬"));
    }

    /** 목록으로 — 「목록」키·뒤로가기 공용. 오토세이브는 onPause 가 챙긴다. */
    private void goList() {
        MainActivity.forgetLast(this);
        Emu.nativeUnload();
        /* menu 를 달아야 목록이 뜬다 — 롬이 하나뿐이면 바로 그 롬으로 되돌아가서
           설정에 닿을 길이 없어진다 */
        Intent i2 = new Intent(this, MainActivity.class);
        i2.putExtra("menu", true);
        startActivity(i2);
        finish();
    }

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
        return "ko-ja";
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
            if (autoSave) Emu.nativeSaveState(autoStatePath().getAbsolutePath());
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
