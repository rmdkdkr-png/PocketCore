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
        /* 기본은 PocketCore 저장소의 고정 태그 app — 앱은 앱 레포에서 받는다
           (한패 IPS 는 KrPatch, 코어·음성팩은 ss2-sp-core 릴리즈를 색인이 가리킨다).
           v3.48 이하는 KrPatch 의 옛 태그를 보지만, 앱 확인이 먼저라 새 APK 로
           갈아탄 뒤 여기로 온다. 개발 중 집 와이파이가 더 빠르면 options.txt 에
           pocketcore_update_url=http://192.168.1.68:8765 를 적어 갈아탈 수 있다. */
        return (v != null && !v.isEmpty()) ? v
                : "https://github.com/rmdkdkr-png/PocketCore/releases/download/app";
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
            String base = baseUrl();
            boolean idle;                      /* 앱이 최신이라 설치 흐름으로 안 빠졌나 */
            try {
                idle = checkApk(act, base);
            } catch (Exception e) {
                toast(act, "업데이트 실패: " + e.getClass().getSimpleName()
                        + " — 인터넷 연결을 확인해 주세요");
                return;                        /* 릴리즈에 못 닿으면 한패도 못 받는다 */
            }
            /* 앱이 최신일 때만 컨텐츠 동기화 — 새 앱을 설치하러 떠나는 중이면 다음에 받는다.
               앱(APK)은 앱이 바뀔 때만 갈고, 코어·음성팩·한글패치는 여기서 따로 받는다. */
            if (idle) { syncCores(act, base); syncPatches(act, base);
                        syncDesign(base); showNews(act, base); }
        }}).start();
    }

    /** 디자인 메타데이터(런처 정보줄 + 배포 썸네일) — 정보는 덮고, 썸은 판 비교로 받는다. */
    private static void syncDesign(String base) {
        try {
            byte[] jb = fetch(base + "/design.json", 10000);   /* 모바일 리다이렉트가 4초를 넘겨 SocketTimeout 나던 제보 */
            JSONObject j = new JSONObject(new String(jb, "UTF-8"));
            File dir = new File(MainActivity.root(), "design");
            dir.mkdirs();
            FileOutputStream fo = new FileOutputStream(new File(dir, "design.json"));
            fo.write(jb); fo.close();

            /* 배포 지정 썸네일 — PC 지정툴(thumbtool)이 고른 장면. 런처 우선순위는
               내 지정 > 이것 > 자동 캡처. 판(ver=파일 md5)이 바뀐 것만 받는다. */
            JSONObject th = j.optJSONObject("thumbs");
            if (th == null) return;
            File td = new File(dir, "thumbs/byid");
            td.mkdirs();
            java.util.Iterator<String> it = th.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject e = th.getJSONObject(id);
                String ver = e.getString("ver");
                File png = new File(td, id + ".png");
                File verf = new File(td, id + ".ver");
                if (png.exists() && ver.equals(readSmall(verf))) continue;
                byte[] b = fetch(e.getString("url"), 15000);
                if (b.length < 8 || b[0] != (byte) 0x89 || b[1] != 'P'
                                 || b[2] != 'N' || b[3] != 'G') continue;
                FileOutputStream po = new FileOutputStream(png);
                po.write(b); po.close();
                writeSmall(verf, ver);
            }
        } catch (Exception ignored) { /* 장식 — 없어도 런처는 뜬다 */ }
    }

    /** 소식 창 — 릴리즈의 news.json(배포 때마다 자동으로 쌓인다)을 그대로 보여 준다.
     *  앱·코어·한패·음성팩이 각자 따로 갱신되니, 뭐가 언제 바뀌었는지 한 자리에. */
    private static void showNews(final Activity act, String base) {
        try {
            byte[] jb = fetch(base + "/news.json", 10000);   /* 모바일 리다이렉트가 4초를 넘겨 SocketTimeout 나던 제보 */
            org.json.JSONArray items = new JSONObject(new String(jb, "UTF-8"))
                    .getJSONArray("items");
            final StringBuilder sb = new StringBuilder();
            int n = Math.min(items.length(), 12);
            for (int i = 0; i < n; i++) {
                org.json.JSONObject e = items.getJSONObject(i);
                sb.append(e.optString("date", "")).append("  ")
                  .append(e.optString("text", "")).append('\n');
            }
            if (sb.length() == 0) return;
            act.runOnUiThread(new Runnable() { @Override public void run() {
                new android.app.AlertDialog.Builder(act)
                        .setTitle("최근 업데이트")
                        .setMessage(sb.toString())
                        .setPositiveButton("확인", null)
                        .show();
            }});
        } catch (Exception ignored) { /* 소식은 부가 정보 — 실패해도 조용히 */ }
    }

    /** 코어·음성팩 동기화 — cores.json 을 보고 기기 ABI 에 맞는 코어를 **앱 내부
     *  저장소**(files/cores/)에 받는다. sdcard 는 실행권이 없어(noexec) dlopen 이
     *  안 되는 기기가 많다 — 그래서 내부다. 음성팩은 데이터라 PocketCore/system/ 에 둔다.
     *  덕분에 코어만 바뀐 날은 APK 재설치(옆설치 경고) 없이 여기서 끝난다. */
    private static void syncCores(Activity act, String base) {
        try {
            byte[] jb = fetch(base + "/cores.json", 10000);   /* 모바일 리다이렉트가 4초를 넘겨 SocketTimeout 나던 제보 */
            JSONObject root = new JSONObject(new String(jb, "UTF-8"));
            StringBuilder got = new StringBuilder();

            JSONObject cores = root.optJSONObject("cores");
            if (cores != null) {
                String abi = android.os.Build.SUPPORTED_ABIS[0];
                File dir = new File(act.getFilesDir(), "cores");
                dir.mkdirs();
                java.util.Iterator<String> it = cores.keys();
                while (it.hasNext()) {
                    String id = it.next();
                    JSONObject e = cores.getJSONObject(id);
                    String ver = e.getString("ver");
                    JSONObject abis = e.getJSONObject("abis");
                    if (!abis.has(abi)) continue;
                    File so = new File(dir, id + ".so");
                    File verf = new File(dir, id + ".ver");
                    if (so.exists() && ver.equals(readSmall(verf))) continue;
                    File tmp = new File(dir, id + ".part");
                    fetchToFile(abis.getString(abi), tmp, 60000);
                    if (!looksLike(tmp, 0x7f, 'E', 'L', 'F')) { tmp.delete(); continue; }
                    so.delete();
                    if (!tmp.renameTo(so)) continue;
                    writeSmall(verf, ver);
                    if (got.length() > 0) got.append(" · ");
                    got.append(e.optString("ko", id)).append(' ').append(ver);
                }
            }

            JSONObject packs = root.optJSONObject("packs");
            if (packs != null) {
                java.util.Iterator<String> it = packs.keys();
                while (it.hasNext()) {
                    JSONObject e = packs.getJSONObject(it.next());
                    String ver = e.getString("ver");
                    File dst = new File(MainActivity.sysDir(), e.getString("file"));
                    File verf = new File(dst.getPath() + ".ver");
                    if (dst.exists() && ver.equals(readSmall(verf))) continue;
                    long mb = e.optLong("size", 0) >> 20;
                    toast(act, e.optString("ko", "팩") + " " + ver + " 내려받는 중…"
                            + (mb > 0 ? " (" + mb + "MB)" : ""));
                    File tmp = new File(dst.getPath() + ".part");
                    fetchToFile(e.getString("url"), tmp, 600000);
                    long want = e.optLong("size", 0);
                    if (want > 0 && tmp.length() != want) { tmp.delete(); continue; }
                    dst.delete();
                    if (!tmp.renameTo(dst)) continue;
                    writeSmall(verf, ver);
                    if (got.length() > 0) got.append(" · ");
                    got.append(e.optString("ko", "팩")).append(' ').append(ver);
                }
            }

            if (got.length() > 0)
                toast(act, "새로 받음: " + got + " — 게임을 다시 열면 적용됩니다");
        } catch (Exception e) {
            toast(act, "코어 확인 실패: " + e.getClass().getSimpleName());
        }
    }

    /** 파일 머리 몇 바이트 대조 — 오류 페이지를 코어라고 저장하는 사고 방지. */
    private static boolean looksLike(File f, int... magic) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            for (int m : magic) if (in.read() != (m & 0xff)) return false;
            return true;
        } catch (Exception e) { return false; }
    }

    /** 큰 파일용 스트리밍 다운로드 — 통째로 메모리에 안 올린다(음성팩은 수백 MB). */
    private static void fetchToFile(String url, File out, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(timeoutMs);
        InputStream in = new BufferedInputStream(c.getInputStream());
        FileOutputStream fo = new FileOutputStream(out);
        byte[] buf = new byte[262144];
        int n;
        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        fo.close(); in.close(); c.disconnect();
    }

    /** 앱 새 판 확인. 설치·설정 화면으로 넘어가면 false, 이미 최신이면 true. */
    private static boolean checkApk(Activity act, String base) throws Exception {
        byte[] jb = fetch(base + "/version.json", 10000);   /* 모바일 리다이렉트가 4초를 넘겨 SocketTimeout 나던 제보 */
        JSONObject j = new JSONObject(new String(jb, "UTF-8"));
        int rc = j.getInt("versionCode");
        String rn = j.optString("versionName", "?");
        String apk = j.getString("apk");
        int my;
        try { my = (int) act.getPackageManager()
                .getPackageInfo(act.getPackageName(), 0).getLongVersionCode(); }
        catch (Throwable t) { my = act.getPackageManager()
                .getPackageInfo(act.getPackageName(), 0).versionCode; }
        if (rc <= my) { toast(act, "앱은 최신 버전입니다 (v" + rn + ")"); return true; }
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
            return false;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse("content://com.dudu.pocketcore.apk/update.apk"),
                "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        act.startActivity(i);
        return false;
    }

    /** 한글패치 동기화 — 릴리즈의 patches.json(게임 id → 최신 IPS 주소·판)을 보고
     *  PocketCore/patch/ 에 내려받는다. Patcher 는 이 폴더를 동봉 assets 보다 먼저 본다.
     *  판 비교는 .ver 파일 내용으로 한다 — 크기 비교는 「번역만 바뀐 같은 크기 판」을
     *  건너뛴 전력이 있어(배포처 관례) 판 문자열로만 가른다. */
    private static void syncPatches(Activity act, String base) {
        try {
            byte[] jb = fetch(base + "/patches.json", 10000);   /* 모바일 리다이렉트가 4초를 넘겨 SocketTimeout 나던 제보 */
            JSONObject j = new JSONObject(new String(jb, "UTF-8")).getJSONObject("patches");
            File dir = new File(MainActivity.root(), "patch");
            dir.mkdirs();
            StringBuilder got = new StringBuilder();
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject e = j.getJSONObject(id);
                String ver = e.getString("ver");
                File ips = new File(dir, id + "_ko.ips");
                File verf = new File(dir, id + "_ko.ver");
                if (ips.exists() && ver.equals(readSmall(verf))) continue;
                byte[] b = fetch(e.getString("url"), 30000);
                /* IPS 서명 확인 — 오류 페이지를 패치로 저장하는 사고 방지 */
                if (b.length < 8 || b[0] != 'P' || b[1] != 'A' || b[2] != 'T'
                                 || b[3] != 'C' || b[4] != 'H') continue;
                FileOutputStream fo = new FileOutputStream(ips);
                fo.write(b); fo.close();
                writeSmall(verf, ver);
                if (got.length() > 0) got.append(" · ");
                got.append(e.optString("ko", id)).append(' ').append(ver);
            }
            toast(act, got.length() > 0
                    ? "한글패치 새 판: " + got + " — 게임을 다시 열면 적용됩니다"
                    : "한글패치도 모두 최신입니다");
        } catch (Exception e) {
            toast(act, "한글패치 확인 실패: " + e.getClass().getSimpleName());
        }
    }

    private static String readSmall(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[128];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8").trim() : null;
        } catch (Exception e) { return null; }
    }

    private static void writeSmall(File f, String s) {
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(s.getBytes("UTF-8"));
        } catch (Exception ignored) { }
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
