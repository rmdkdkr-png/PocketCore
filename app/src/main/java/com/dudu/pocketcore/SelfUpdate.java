package com.dudu.pocketcore;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.widget.Toast;

import java.io.File;

/** 새 판으로 바뀐 뒤 알림·뒷정리.
 *
 *  120~153 은 여기서 APK 를 받아 PackageInstaller 세션으로 자기 자신을 깔았다(그 전엔 설치창 호출, 120~131 은 확인 없이).
 *  154 부터 뺐다 — 유저 2026-10-17 「유해한앱이라고 안뜨게해줭」. 인터넷에서 APK 를 받아 스스로 까는 것(REQUEST_INSTALL_PACKAGES)과
 *  코어(.so)를 받아 실행하는 것이 Play 프로텍트가 «유해한 앱»으로 보는 드로퍼·로더 모양이라. 이제 「새 판 받기」는 시스템 다운로드에
 *  맡기고(Updater.checkApk), 유저가 다운로드 알림을 눌러 시스템 설치기로 깐다. */
public final class SelfUpdate {
    private SelfUpdate() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE); }

    @SuppressWarnings("deprecation")
    static int codeOf(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
    }

    static int myCode(Context c) {
        try { return codeOf(c.getPackageManager().getPackageInfo(c.getPackageName(), 0)); }
        catch (Throwable t) { return 0; }
    }

    /** 런처가 켜질 때 — 지난번보다 판이 올라 있으면 알려 주고, 예전 판이 앱 안쪽에 남긴 것(받아 둔 APK·받은 코어)을 지운다. */
    static void afterLaunch(Activity a) {
        try {
            SharedPreferences p = prefs(a);
            int my = myCode(a), last = p.getInt("seen", 0);
            if (last > 0 && my > last)
                Toast.makeText(a, "새 판으로 바뀌었습니다 · 내부 " + last + " → " + my
                        + (last < 121 && my >= 121 ? "\n테마가 생겼습니다 — 런처 왼쪽 위 로고를 누르면 바뀝니다" : ""), Toast.LENGTH_LONG).show();
            if (my != last) p.edit().putInt("seen", my).apply();
            wipe(new File(a.getFilesDir(), "update"));   /* 153 까지의 받아 둔 APK */
            wipe(new File(a.getFilesDir(), "cores"));    /* 153 까지 받아 쓰던 코어(.so·.ver) — 154 부터 늘 동봉 코어 */
        } catch (Throwable ignored) { }
    }

    private static void wipe(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) { if (f.isDirectory()) wipe(f); f.delete(); }
        d.delete();
    }
}
