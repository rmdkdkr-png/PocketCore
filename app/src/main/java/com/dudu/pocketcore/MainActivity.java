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
        if (!started && hasStorage()) setup();
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
            showList(listRoms());
            return;
        }

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String last = sp.getString(KEY_LAST, null);
        if (last != null && new File(last).exists()) { launch(last); return; }

        List<File> roms = listRoms();
        if (roms.size() == 1) { launch(roms.get(0).getAbsolutePath()); return; }
        showList(roms);
    }

    private List<File> listRoms() {
        File[] f = romsDir().listFiles();
        List<File> out = new ArrayList<>();
        if (f == null) return out;
        Arrays.sort(f);
        for (File x : f) if (x.isFile() && !x.getName().startsWith(".")) out.add(x);
        return out;
    }

    private void showList(final List<File> roms) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xff101014);

        TextView t = new TextView(this);
        t.setPadding(40, 60, 40, 30);
        t.setTextColor(0xffdddddd);
        t.setTextSize(15);
        t.setText(roms.isEmpty()
                ? "롬이 없습니다.\n\n" + romsDir().getAbsolutePath() + "\n위 폴더에 롬 파일을 넣고 앱을 다시 여세요."
                : "롬 선택 (한 번만 — 다음부터는 바로 실행됩니다)");
        col.addView(t);

        ListView lv = new ListView(this);
        List<String> names = new ArrayList<>();
        for (File r : roms) names.add(r.getName());
        lv.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, names) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView v = (TextView) super.getView(pos, cv, parent);
                v.setTextColor(0xffeeeeee);
                return v;
            }
        });
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                launch(roms.get(pos).getAbsolutePath());
            }
        });
        col.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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
}
