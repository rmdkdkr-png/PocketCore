package com.dudu.pocketcore;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * No settings screen, no core downloader, no menu tree.
 * Boot -> last ROM resumes immediately. Only when there is no last ROM
 * does a flat file list appear.
 */
public class MainActivity extends Activity {

    public static final String ROOT = "PocketCore";
    private static final String PREFS = "pc";
    private static final String KEY_LAST = "lastRom";

    public static File root()    { return new File(Environment.getExternalStorageDirectory(), ROOT); }
    public static File romsDir() { return new File(root(), "roms"); }
    public static File saveDir() { return new File(root(), "saves"); }
    public static File sysDir()  { return new File(root(), "system"); }
    public static File optsFile(){ return new File(root(), "options.txt"); }

    private boolean started = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!hasStorage()) { askStorage(); return; }
        setup();
    }

    @Override protected void onResume() {
        super.onResume();
        fg = true;
        if (!started && hasStorage()) setup();
        if (bgm != null) {
            try { bgm.start(); } catch (Exception ignored) { }
        } else if (bootMp != null) {
            try { bootMp.start(); } catch (Exception ignored) { }
        }
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this, "파일 접근을 허용한 뒤 앱으로 돌아오세요", Toast.LENGTH_LONG).show();
        } else {
            requestPermissions(new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE }, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        if (hasStorage()) setup();
    }

    private void setup() {
        if (started) return;
        started = true;
        romsDir().mkdirs(); saveDir().mkdirs(); sysDir().mkdirs();
        seedOptions();

        /* 게임에서 「롬 바꾸기」로 온 경우엔 목록을 **반드시** 보여 준다.
           안 그러면 롬이 하나뿐일 때 그 롬으로 바로 되돌아가서 목록도 설정도 영영 못 본다. */
        if (getIntent() != null && getIntent().getBooleanExtra("menu", false)) {
            if (!bootedOnce) playBoot(); else playTheme();   /* 부팅송 → 테마 체이닝 */
            showList(listRoms());
            return;
        }

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String last = sp.getString(KEY_LAST, null);
        if (last != null && new File(last).exists()) { launch(last); return; }

        List<File> roms = listRoms();
        if (roms.size() == 1) { launch(roms.get(0).getAbsolutePath()); return; }
        if (!bootedOnce) playBoot(); else playTheme();       /* 부팅송 → 테마 체이닝 */
        showList(roms);
    }

    private List<File> listRoms() {
        File[] f = romsDir().listFiles();
        List<File> out = new ArrayList<>();
        if (f == null) return out;
        Arrays.sort(f);
        for (File x : f) {
            if (!x.isFile() || x.getName().startsWith(".")) continue;
            String n = x.getName().toLowerCase();
            /* 롬만 — 아무 파일이나 목록에 들어가 흰 카드가 생기던 제보(「roms」) */
            if (n.endsWith(".ngc") || n.endsWith(".ngp") || n.endsWith(".npc"))
                out.add(x);
        }
        return out;
    }

    /* ── 런처 소리 — 부팅음·인트로는 **프로세스당 1회**, 테마는 끊기지 않게:
       홈 왕복은 일시정지/재개, 게임 왕복은 재생 위치를 승계한다 (제보: 「끊기는 게 싫다」) ── */
    private android.media.MediaPlayer bgm;
    private android.media.MediaPlayer bootMp; /* 부팅송 — 게임 진입 시 반드시 죽인다 */
    private boolean fg = false;               /* 화면에 떠 있는가 (onResume~onPause) */
    private static int themePos = 0;          /* 액티비티가 죽어도 위치는 남는다 */
    static boolean bootedOnce = false;        /* 부팅 연출·부팅음 1회 게이트 */

    private boolean sndOn() {
        /* android.provider.Settings 와 이름이 겹쳐 FQN — askStorage 가 그쪽을 쓴다 */
        java.util.Map<String, String> m = com.dudu.pocketcore.Settings.load();
        String v = m.get("pocketcore_launcher_snd");
        return v == null || !v.equals("disabled");
    }

    /** 부팅송(내장 boot.mp3, ~7초) — 끝나면 테마곡으로 이어진다.
     *  ★ 플레이어를 필드로 들고 있는다 — 로컬로 두면 부팅송이 도는 7초 안에
     *  게임을 켰을 때 멈출 방법이 없고, 완료 리스너가 **게임 중에** 테마를
     *  틀어 버린다(제보: 「게임 중에 테마송이 나와」). */
    private void playBoot() {
        if (!sndOn()) { playTheme(); return; }
        try {
            android.content.res.AssetFileDescriptor fd = getAssets().openFd("boot.mp3");
            final android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            mp.setVolume(0.8f, 0.8f);
            mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(android.media.MediaPlayer m2) {
                    m2.release();
                    if (bootMp == m2) bootMp = null;
                    /* 그 사이 게임으로 떠났거나 화면 밖이면 테마를 시작하지 않는다 */
                    if (fg && !isFinishing()) playTheme();
                }
            });
            mp.prepare();
            mp.start();
            bootMp = mp;
        } catch (Exception e) {
            playTheme();                    /* 부팅송이 없거나 실패해도 테마는 흐른다 */
        }
    }

    private void stopBoot() {
        if (bootMp != null) {
            try { bootMp.stop(); bootMp.release(); } catch (Exception ignored) { }
            bootMp = null;
        }
    }

    private void playTheme() {
        if (!sndOn() || bgm != null) return;
        try {
            /* 유저 교체 경로 우선 — system/theme.{wav,mp3,ogg} 를 넣으면 그 곡이 흐른다
               (Suno 등에서 받은 mp3 그대로 됨) */
            File user = null;
            for (String ext : new String[]{ "wav", "mp3", "ogg" }) {
                File f = new File(sysDir(), "theme." + ext);
                if (f.exists()) { user = f; break; }
            }
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            if (user != null) {
                mp.setDataSource(user.getPath());
            } else {
                android.content.res.AssetFileDescriptor fd = getAssets().openFd("theme.wav");
                mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                fd.close();
            }
            mp.setLooping(true);
            mp.setVolume(0.55f, 0.55f);
            mp.prepare();
            if (themePos > 0) {
                try { mp.seekTo(themePos); } catch (Exception ignored) { }
            }
            mp.start();
            bgm = mp;
        } catch (Exception ignored) { }
    }

    private void stopTheme() {
        if (bgm != null) {
            try { themePos = bgm.getCurrentPosition(); } catch (Exception ignored) { }
            try { bgm.stop(); bgm.release(); } catch (Exception ignored) { }
            bgm = null;
        }
    }

    @Override protected void onPause() {
        super.onPause();
        fg = false;
        /* 홈으로 잠깐 나간 것 — 죽이지 말고 멈췄다가 돌아오면 그 자리부터 */
        if (bgm != null) {
            try { themePos = bgm.getCurrentPosition(); bgm.pause(); }
            catch (Exception ignored) { }
        }
        if (bootMp != null) {
            try { bootMp.pause(); } catch (Exception ignored) { }
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopBoot();
        stopTheme();
    }

    private void showList(final List<File> roms) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xff101014);

        if (roms.isEmpty()) {
            TextView t = new TextView(this);
            t.setPadding(40, 60, 40, 30);
            t.setTextColor(0xffdddddd);
            t.setTextSize(15);
            t.setText("롬이 없습니다.\n\n" + romsDir().getAbsolutePath()
                    + "\n위 폴더에 롬 파일을 넣고 앱을 다시 여세요.");
            col.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            /* 레트로 런처 — NGPC 해상도 캔버스에 카트리지 캐러셀.
               썸네일은 롬당 1회 몰래 부팅해 캡처(Thumbs), 그다음부터는 캐시. */
            final LauncherView lv = new LauncherView(this);
            final List<LauncherView.Item> items = new ArrayList<>();
            java.util.Map<String, String> meta = designMeta();
            for (File r : roms) {
                Games.Game g = Games.identify(r.getPath());
                String nm = (g != null) ? g.ko : stripExt(r.getName());
                LauncherView.Item it = new LauncherView.Item(r, nm);
                if (g != null) {
                    it.sp  = "svc".equals(g.id);
                    it.dub = g.voice != null && new File(sysDir(), g.voice).exists();
                    it.pat = new File(new File(root(), "patch"), g.id + "_ko.ips").exists()
                          || assetExists("patch/" + g.id + "_ko.ips");
                    String sub = meta.get(g.id);
                    if (sub != null) it.sub = sub;
                    /* 한패가 못 붙는 판본은 그 사실을 카드에 바로 적는다 —
                       「왜 한글이 안 나오지」가 앱 고장으로 읽히지 않게 */
                    if ("lbj".equals(g.id)) it.sub = "일본판 롬 — 한글패치는 UE(영문)판 전용";
                }
                File tf = thumbFor(r, g);
                if (tf != null)
                    it.thumb = android.graphics.BitmapFactory.decodeFile(tf.getPath());
                items.add(it);
            }
            lv.setItems(items);
            if (!bootedOnce) {             /* 스마일 볼 연출은 프로세스당 1회 — 복귀 땐 조용히 */
                lv.startIntro();
                bootedOnce = true;
            }
            lv.setListener(new LauncherView.Listener() {
                @Override public void onLaunch(File rom) { launch(rom.getAbsolutePath()); }
                @Override public void onSettings() {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                }
                @Override public void onUpdate() { Updater.check(MainActivity.this); }
            });
            col.addView(lv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            /* 빠진 썸네일을 뒤에서 하나씩 굽는다 — 다 되는 대로 즉시 반영.
               실행(launch)이 stop 을 세우면 현재 것만 마치고 그만둔다. */
            Thumbs.stop = false;
            new Thread(new Runnable() { @Override public void run() {
                for (LauncherView.Item it : items) {
                    if (Thumbs.stop) break;
                    if (it.thumb != null) continue;
                    if (Thumbs.ensure(MainActivity.this, it.rom)) {
                        it.thumb = android.graphics.BitmapFactory
                                .decodeFile(Thumbs.of(it.rom).getPath());
                        lv.thumbReady();
                    }
                }
            }}).start();
        }

        /* 하단 바: 설정 + 업데이트. 오버레이(아래+옵션)는 게임 안에서만 열리고 코어가 정한
           항목만 나오므로, 언어처럼 앱이 다루는 설정은 여기 아니면 갈 데가 없다.
           업데이트는 PC 릴리즈 서버(같은 와이파이)에서 새 판을 받아 설치창까지 간다. */
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        TextView cfg = new TextView(this);
        cfg.setText("설정");
        cfg.setTextColor(0xffd9a441);
        cfg.setTextSize(17);
        cfg.setGravity(android.view.Gravity.CENTER);
        cfg.setPadding(0, 46, 0, 46);
        cfg.setBackgroundColor(0xff191b22);
        cfg.setClickable(true);
        cfg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        TextView upd = new TextView(this);
        upd.setText("업데이트 확인");
        upd.setTextColor(0xff7fc97f);
        upd.setTextSize(17);
        upd.setGravity(android.view.Gravity.CENTER);
        upd.setPadding(0, 46, 0, 46);
        upd.setBackgroundColor(0xff16211a);
        upd.setClickable(true);
        upd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Updater.check(MainActivity.this); }
        });
        bar.addView(cfg, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(upd, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(bar);
        setContentView(col);
    }

    private void launch(String romPath) {
        stopBoot();      /* 부팅송이 도는 7초 안에 게임을 켜도 확실히 끊는다 */
        stopTheme();
        /* 백그라운드 썸네일 캡처와 코어 전역 상태가 겹치면 안 된다 —
           멈추라고 표시하고, 진행 중인 한 개가 끝나기를 잠깐 기다린다. */
        Thumbs.stop = true;
        synchronized (Thumbs.LOCK) { }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST, romPath).apply();
        Intent i = new Intent(this, EmuActivity.class);
        i.putExtra("rom", romPath);
        startActivity(i);
        finish();
    }

    /** 코어 기본 옵션 — 메뉴가 없으므로 첫 실행 때 한 번 써 둔다. 수정은 파일에서. */
    private void seedOptions() {
        File f = optsFile();
        if (f.exists()) return;
        String def = "# PocketCore 옵션 (key=value). 수정 후 앱 재시작.\n"
                + "#\n"
                + "# 게임 언어: ko-ja / ko-en / ja / en\n"
                + "#   네오지오 포켓 롬은 일어와 영어를 **함께** 담고 BIOS 설정으로 고른다.\n"
                + "#   한글 패치는 그중 한쪽 표만 덮으므로, 덮인 쪽으로 맞춰야 한글이 보인다.\n"
                + "#   어느 쪽을 덮었는지는 게임마다 달라서 바탕을 고르게 뒀다:\n"
                + "#     ko-ja = 한글(일어 바탕)   ko-en = 한글(영어 바탕)\n"
                + "#   패치는 롬 **사본**에 입힌다 — 원본 롬은 안 건드린다.\n"
                + "#   ngp_language 는 앱이 이 값에 맞춰 알아서 쓴다.\n"
                + "pocketcore_lang=ko-ja\n"
                + "#\n"
                + "# 기술명 띠 — 화면 밖 띠에 기술 이름을 띄운다(게임 그림을 안 가린다).\n"
                + "ngp_svcsp_band=enabled\n"
                + "#\n"
                + "# 해설 언어. 음성팩 이름과 짝이다 — system/ss2_voice_<언어>.pak 을 읽는다.\n"
                + "# 재생 키가 문장 해시라서 표와 팩의 판이 어긋나면 그냥 조용해진다.\n"
                + "ngp_ss2sp_comm_lang=ko\n"
                + "ngp_language=japanese\n"
                + "ngp_svcsp_engine=enabled\n"
                + "ngp_svcsp_toast=enabled\n"
                + "ngp_ss2sp=enabled\n"
                + "ngp_ss2sp_comm=enabled\n"
                + "ngp_ss2sp_comm_draw=above\n"
                + "ngp_ss2sp_sides=enabled\n"
                + "ngp_ss2sp_comm_vol=100\n"
                + "ngp_ss2sp_dub=enabled\n";
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
            fo.write(def.getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    /** EmuActivity calls this when the user asks for a different ROM. */
    public static void forgetLast(Activity a) {
        a.getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_LAST).apply();
    }

    /* ── 런처 보조 ──────────────────────────────────────────────── */

    /** 썸네일 우선순위: 내 지정(게임 중 샷→지정) > 배포 지정(업데이트로 받은 것) >
     *  자동 캡처. 없으면 null — 백그라운드 캡처가 채운다. */
    private static File thumbFor(File rom, Games.Game g) {
        File base = new File(root(), "design/thumbs");
        File f = new File(new File(base, "pick"), rom.getName() + ".png");
        if (f.exists()) return f;
        if (g != null) {
            f = new File(new File(base, "byid"), g.id + ".png");
            if (f.exists()) return f;
        }
        f = new File(base, rom.getName() + ".png");
        return f.exists() ? f : null;
    }

    private static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }

    private boolean assetExists(String path) {
        try (java.io.InputStream in = getAssets().open(path)) { return true; }
        catch (Exception e) { return false; }
    }

    /** 디자인 메타데이터 — 릴리즈의 design.json 을 「업데이트 확인」이 받아 둔다.
     *  게임 id → 정보줄(연도·장르). 없으면 그냥 배지만 나온다. */
    private java.util.Map<String, String> designMeta() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        try {
            File f = new File(new File(root(), "design"), "design.json");
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
            }
            org.json.JSONObject g = new org.json.JSONObject(new String(b, "UTF-8"))
                    .getJSONObject("games");
            java.util.Iterator<String> it = g.keys();
            while (it.hasNext()) {
                String id = it.next();
                m.put(id, g.getJSONObject(id).optString("sub", ""));
            }
        } catch (Exception ignored) { }
        return m;
    }
}
