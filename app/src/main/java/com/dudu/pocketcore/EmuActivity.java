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
    private int spkIdx = 0;   /* 해설자 — options.txt 의 ngp_ss2sp_comm_spk 와 동기 */
    /* v4 로스터 11인 — 재캐스팅 아웃(샤를로트/소게츠/모로즈미/유가) 제외 */
    private static final String[] SPK_KEY = {
        "haohmaru","nakoruru","hanzo","galford","rimururu","genjuro","ukyo",
        "jubei","kazuki","asura","shiki" };
    private static final String[] SPK_KO = {
        "하오마루","나코루루","한조","갈포드","리무루루","겐주로","우쿄",
        "쥬베이","카즈키","아수라","시키" };
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
                    "enabled".equals(readOpt("pocketcore_svc_fastrom", "disabled")));
            patched = !p.equals(romPath);
            romPath = p;
            persistOption("ngp_language", Games.ngpLanguage(game, lang));
        }
        spkIdx = readSpkIdx();
        autoSave = "enabled".equals(readOpt("pocketcore_autosave", "enabled"));
        try {   /* 판독 오버레이 — 코어가 뜰 때 getenv 로 한 번 읽으므로 **로드 전에** 심는다.
                   화면 왼쪽 위에 「내 동작번호|상대반응」을 상시 표시 — 영상만 찍어도
                   무슨 기술이 실제로 나갔는지(약/강 포함) 게임이 직접 말해 준다. */
            android.system.Os.setenv("SVCSP_ACTSHOW",
                    "enabled".equals(readOpt("pocketcore_svc_actshow", "disabled")) ? "1" : "0", true);
        } catch (Exception ignored) { }
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
                Emu.nativeSetInput(padMask | keyMask);
                Emu.nativeFrame();
            }
        });
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        pad = new PadView(this);
        /* 패드는 세 벌 — SS2 전용, SvC(원버튼 6키), 순정 NGPC(A·B 만).
           원버튼 엔진은 SvC 롬에서만 도니, 다른 게임에 기술·강약 버튼을 두면
           안 나가는 버튼이 화면만 차지한다. 배치 파일은 **게임마다** 따로다. */
        String profile = "ss2".equals(romType) ? "ss2"
                       : (game != null && "svc".equals(game.id)) ? "svc" : "ngp";
        pad.setProfile(profile, (game != null) ? game.id : "ngp");
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
    }

    private int scrPct = 100, scrX = 50, scrY = 50;

    /** 현재 scrPct/scrX/scrY 로 게임 화면을 놓고, 편집 상자에도 알려 준다 */
    private void placeScreen() {
        if (root == null || gl == null) return;
        int w = root.getWidth(), hgt = root.getHeight();
        if (w <= 0 || hgt <= 0) return;
        int gw = w * scrPct / 100, gh = hgt * scrPct / 100;
        int mx = (w - gw) * clamp(scrX, 0, 100) / 100;
        int my = (hgt - gh) * clamp(scrY, 0, 100) / 100;
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
        case PadView.ACT_SPK:
            if (!"ss2".equals(romType)) { toast("해설은 SS2 전용"); break; }
            /* 탭마다 즉시 다음 해설자 — 선택창은 번거롭다는 제보로 폐지 */
            spkIdx = (spkIdx + 1) % SPK_KEY.length;
            Emu.nativeSetOption("ngp_ss2sp_comm_spk", SPK_KEY[spkIdx]);
            persistOption("ngp_ss2sp_comm_spk", SPK_KEY[spkIdx]);
            toast("해설: " + SPK_KO[spkIdx]);
            break;
        case PadView.ACT_CFG:
            /* 게임을 켠 채로 설정을 연다. 돌아오면 onResume 이 화면 설정을 다시 읽는다. */
            startActivity(new Intent(this, SettingsActivity.class));
            break;
        case PadView.ACT_PICK:
            MainActivity.forgetLast(this);
            Emu.nativeUnload();
            {   /* menu 를 달아야 목록이 뜬다 — 롬이 하나뿐이면 바로 그 롬으로 되돌아가서
                   설정에 닿을 길이 없어진다 */
                Intent i2 = new Intent(this, MainActivity.class);
                i2.putExtra("menu", true);
                startActivity(i2);
            }
            finish();
            break;
        }
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
        try {
            java.util.Scanner sc = new java.util.Scanner(MainActivity.optsFile(), "UTF-8");
            while (sc.hasNextLine()) {
                String ln = sc.nextLine().trim();
                if (!ln.startsWith("pocketcore_lang=")) continue;
                String v = ln.substring(ln.indexOf('=') + 1).trim();
                sc.close();
                for (String k : Games.LANGS) if (k.equals(v)) return v;
                if ("ko".equals(v)) return "ko-ja";      /* 예전 표기 */
                return "ko-ja";
            }
            sc.close();
        } catch (Exception ignored) { }
        return "ko-ja";
    }

    private int readSpkIdx() {
        try {
            java.util.Scanner sc = new java.util.Scanner(MainActivity.optsFile(), "UTF-8");
            while (sc.hasNextLine()) {
                String ln = sc.nextLine();
                if (!ln.startsWith("ngp_ss2sp_comm_spk=")) continue;
                String v = ln.substring(ln.indexOf('=') + 1).trim();
                for (int i = 0; i < SPK_KEY.length; i++) if (SPK_KEY[i].equals(v)) { sc.close(); return i; }
            }
            sc.close();
        } catch (Exception ignored) { }
        return 0;
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
    private int mapKey(int code) {
        switch (code) {
        case KeyEvent.KEYCODE_DPAD_UP:    return 1 << Emu.UP;
        case KeyEvent.KEYCODE_DPAD_DOWN:  return 1 << Emu.DOWN;
        case KeyEvent.KEYCODE_DPAD_LEFT:  return 1 << Emu.LEFT;
        case KeyEvent.KEYCODE_DPAD_RIGHT: return 1 << Emu.RIGHT;
        case KeyEvent.KEYCODE_BUTTON_A:   return 1 << Emu.A;
        case KeyEvent.KEYCODE_BUTTON_B:   return 1 << Emu.B;
        case KeyEvent.KEYCODE_BUTTON_X:   return 1 << Emu.X;
        case KeyEvent.KEYCODE_BUTTON_Y:   return 1 << Emu.Y;
        case KeyEvent.KEYCODE_BUTTON_START: return 1 << Emu.START;
        case KeyEvent.KEYCODE_BUTTON_SELECT: return 1 << Emu.SELECT;
        case KeyEvent.KEYCODE_BUTTON_L1:  return 1 << Emu.L;
        case KeyEvent.KEYCODE_BUTTON_R1:  return 1 << Emu.R;
        default: return 0;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        boolean gamepad = (e.getSource() & InputDevice.SOURCE_GAMEPAD) != 0
                       || (e.getSource() & InputDevice.SOURCE_DPAD) != 0;
        int bit = mapKey(e.getKeyCode());
        if (gamepad && bit != 0) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) keyMask |= bit;
            else if (e.getAction() == KeyEvent.ACTION_UP) keyMask &= ~bit;
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override protected void onPause() {
        super.onPause();
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
        super.onResume(); immersive(); gl.onResume();
        if (loaded) Emu.nativeAudioResume();
        applyScreenLayout();      /* 설정에서 돌아온 경우 바로 반영 */
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (loaded) Emu.nativeUnload();
    }
}
