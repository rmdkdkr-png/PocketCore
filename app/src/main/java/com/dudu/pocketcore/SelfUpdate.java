package com.dudu.pocketcore;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 「새 판 받기」 설치 — PackageInstaller 세션으로 자기 자신을 새 판으로 바꾼다(120~).
 *
 *  유저 2026-10-13: 설치 때 불편한 것 «① 업데이트하시겠습니까 확인 창 ② Play 프로텍트 앱 검사·차단 창».
 *  둘 다 시스템 설치 화면(ACTION_VIEW → 패키지 설치기)에서 뜬다. 세션 설치는 그 화면을 거치지 않는다.
 *
 *  안드로이드 12(API 31)부터 앱이 «자기 자신»을 업데이트할 때는 세션에 setRequireUserAction(USER_ACTION_NOT_REQUIRED) 를 주고
 *  UPDATE_PACKAGES_WITHOUT_USER_ACTION 권한을 선언하면 확인 창 없이 깔린다(CI 에뮬레이터 API 33 에서 확인).
 *  시스템 조건(안드로이드 16 PackageInstallerSession 소스): 새 APK targetSdk 33 이상(우리 34) · 같은 서명 · 「이 출처 허용」 ·
 *  같은 앱을 30초 안에 또 조용히 깔지 않음. 어긋나면 STATUS_PENDING_USER_ACTION → 예전처럼 시스템 확인 창.
 *
 *  ★ 유저가 누를 때만 깐다. 앱을 안 쓰는 동안 뒤에서 저절로 까는 것은 넣지 않는다(유저 「안 쓰는 동안 깔리는 게 더 문제」).
 *  설치 결과는 InstallStatusActivity(화면 없음)가 받는다 — 확인이 필요하면 확인 창.
 *  다 깔린 뒤 앱을 저절로 다시 여는 것은 안 된다: 자기 업데이트는 시스템이 앱을 끝내고, 그 뒤 결과 전달은 «뒤에서 화면 띄우기»로
 *  막힌다(CI API 33 로그 «Background activity start … allowBackgroundActivityStart: false»). 알림으로 열게 하려면 알림 권한 창이
 *  또 떠서 뺐다. 유저는 앱을 다시 열면 되고, 그때 «새 판으로 바뀌었습니다» 토스트가 뜬다. */
public final class SelfUpdate {
    static final String TAG = "SelfUpdate";

    private SelfUpdate() { }

    static boolean supported() { return Build.VERSION.SDK_INT >= 31; }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE); }

    @SuppressWarnings("deprecation")
    static int codeOf(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
    }

    static int myCode(Context c) {
        try { return codeOf(c.getPackageManager().getPackageInfo(c.getPackageName(), 0)); }
        catch (Throwable t) { return 0; }
    }

    /** 새 판 APK 를 앱 안쪽(files/update/next.apk)에 받아 둔다. 이미 같은 판이 있으면 그대로.
     *  받은 파일이 우리 패키지의 그 판이 아니면(오류 페이지·다른 앱) 버리고 null. */
    static File stage(Context c, String url, int rc) throws Exception {
        File dir = new File(c.getFilesDir(), "update");
        dir.mkdirs();
        File apk = new File(dir, "next.apk");
        if (apk.exists() && archiveCode(c, apk) == rc) return apk;
        File part = new File(dir, "next.apk.part");
        Updater.fetchToFile(url, part, 60000);
        if (archiveCode(c, part) != rc) { part.delete(); return null; }
        apk.delete();
        return part.renameTo(apk) ? apk : null;
    }

    static int archiveCode(Context c, File f) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
            if (pi == null || !c.getPackageName().equals(pi.packageName)) return -1;
            return codeOf(pi);
        } catch (Throwable t) { return -1; }
    }

    /** 이미 깔린 판 이하의 받아 둔 APK 는 지운다. */
    static void clean(Context c) {
        File apk = new File(new File(c.getFilesDir(), "update"), "next.apk");
        if (apk.exists() && archiveCode(c, apk) <= myCode(c)) apk.delete();
    }

    /** 세션 설치 — 조용히 깔리면 1~2초 뒤 시스템이 이 프로세스를 끝낸다(앱이 닫힘). */
    static void install(Context c, File apk, int rc) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        sp.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int sid = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(sid)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[262144];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent it = new Intent(c, InstallStatusActivity.class).putExtra("to", rc)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getActivity(c, sid, it, fl).getIntentSender());
            Log.i(TAG, "commit session " + sid + " -> " + rc);
        } catch (Exception e) {
            try { pi.abandonSession(sid); } catch (Throwable ignored) { }
            throw e;
        }
    }

    /** 런처가 켜질 때 — 지난번보다 판이 올라 있으면 알려 준다. */
    static void afterLaunch(Activity a) {
        try {
            SharedPreferences p = prefs(a);
            int my = myCode(a), last = p.getInt("seen", 0);
            if (last > 0 && my > last)
                Toast.makeText(a, "새 판으로 바뀌었습니다 · 내부 " + last + " → " + my
                        + (last < 121 && my >= 121 ? "\n테마가 생겼습니다 — 런처 왼쪽 위 로고를 누르면 바뀝니다" : ""), Toast.LENGTH_LONG).show();
            if (my != last) p.edit().putInt("seen", my).apply();
            clean(a);
        } catch (Throwable ignored) { }
    }
}
