package com.dudu.pocketcore;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 앱 안 「업데이트 확인」 — PC(같은 와이파이)의 릴리즈 서버에서 새 판을 받아 설치창까지.
 *  채팅으로 zip 받아 파일앱을 뒤지는 흐름이 귀찮다는 제보로 만들었다.
 *  서버 주소는 options.txt 의 pocketcore_update_url (기본 http://192.168.1.68:8765).
 *  서버에는 version.json({"versionCode":..,"versionName":"..","apk":".."})과 APK 가 있다. */
public final class Updater {

    static String baseUrl() {
        String v = null;
        try { v = Settings2.readOpt("pocketcore_update_url"); } catch (Throwable ignored) { }
        /* 기본은 KrPatch 고정 태그 릴리즈 — 어디서든(모바일 데이터 포함), 받은 사람 전부 된다.
           개발 중 집 와이파이가 더 빠르면 options.txt 에
           pocketcore_update_url=http://192.168.1.68:8765 를 적어 갈아탈 수 있다. */
        return (v != null && !v.isEmpty()) ? v
                : "https://github.com/rmdkdkr-png/KrPatch/releases/download/pocketcore";
    }

    /* Settings.load() 는 앱 Settings 클래스지만 이름이 android.provider.Settings 와 겹쳐
       여기서만 쓰는 얇은 우회를 둔다. */
    static final class Settings2 {
        static String readOpt(String key) {
            java.util.Map<String, String> m = com.dudu.pocketcore.Settings.load();
            return m.get(key);
        }
    }

    public static void check(final Activity act) {
        toast(act, "업데이트 확인 중…");
        new Thread(new Runnable() { @Override public void run() {
            try {
                String base = baseUrl();
                byte[] jb = fetch(base + "/version.json", 4000);
                JSONObject j = new JSONObject(new String(jb, "UTF-8"));
                int rc = j.getInt("versionCode");
                String rn = j.optString("versionName", "?");
                String apk = j.getString("apk");
                int my;
                try { my = (int) act.getPackageManager()
                        .getPackageInfo(act.getPackageName(), 0).getLongVersionCode(); }
                catch (Throwable t) { my = act.getPackageManager()
                        .getPackageInfo(act.getPackageName(), 0).versionCode; }
                if (rc <= my) { toast(act, "최신 버전입니다 (v" + rn + ")"); return; }
                toast(act, "v" + rn + " 다운로드 중…");
                byte[] ab = fetch(base + "/" + Uri.encode(apk), 60000);
                File out = new File(act.getCacheDir(), "update.apk");
                FileOutputStream fo = new FileOutputStream(out);
                fo.write(ab); fo.close();
                if (!act.getPackageManager().canRequestPackageInstalls()) {
                    toast(act, "「이 출처 허용」을 켠 뒤 다시 눌러 주세요 (최초 1회)");
                    Intent p = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + act.getPackageName()));
                    p.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(p);
                    return;
                }
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse("content://com.dudu.pocketcore.apk/update.apk"),
                        "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Exception e) {
                toast(act, "업데이트 실패: " + e.getClass().getSimpleName()
                        + " — PC 서버가 켜져 있고 같은 와이파이인지 확인");
            }
        }}).start();
    }

    private static byte[] fetch(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
        InputStream in = new BufferedInputStream(c.getInputStream());
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close(); c.disconnect();
        return bo.toByteArray();
    }

    private static void toast(final Activity a, final String s) {
        a.runOnUiThread(new Runnable() { @Override public void run() {
            Toast.makeText(a, s, Toast.LENGTH_LONG).show(); }});
    }
}
