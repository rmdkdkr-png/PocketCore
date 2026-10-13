package com.dudu.pocketcore;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 자동 업데이트 — 확인 창 없이 자기 자신을 새 판으로 바꾼다(유저 2026-10-13 「설치 때마다 개불편」, 120~).
 *
 *  안드로이드 12(API 31)부터 앱이 «자기 자신»을 업데이트할 때는 PackageInstaller 세션에
 *  setRequireUserAction(USER_ACTION_NOT_REQUIRED) 를 주고 UPDATE_PACKAGES_WITHOUT_USER_ACTION 권한을 선언하면 확인 창이 없다.
 *  시스템 조건(안드로이드 16 소스 PackageInstallerSession 으로 확인): 새 APK 의 targetSdk 33 이상(우리는 34) · 같은 서명 ·
 *  「이 출처 허용」 켜짐 · 같은 앱을 30초 안에 또 조용히 깔지 않음 · 다른 설치기가 업데이트 주인으로 잡혀 있지 않음.
 *  하나라도 어긋나면 STATUS_PENDING_USER_ACTION 이 온다 — 직접 누른 경우는 예전처럼 확인 창, 뒤에서 돈 경우는
 *  런처의 「새 판 받기」 버튼이 그대로 남는다(더 나빠지지 않는다).
 *
 *  언제 까나 — 앱을 안 쓸 때만: 보이는 화면이 없고(started 0) 게임 화면도 없을 때(games 0).
 *  설치하면 시스템이 앱 프로세스를 끝내므로, 게임 화면이 뒤에 남아 있기만 해도 안 깐다.
 *    · 주기 작업(UpdateJob, 3시간마다 · 네트워크 있을 때) — 앱을 안 연 날에도 깔아 둔다
 *    · 앱을 나가는 순간(마지막 화면이 멈춤) 새 판이 있다고 알고 있으면 15초 뒤 한 번
 *  다음에 앱을 열면 런처가 «새 판으로 바뀌었습니다 · 내부 A → B» 토스트를 띄운다.
 *  설정 › 업데이트 › 자동 업데이트(pocketcore_autoupdate=off)로 끈다. */
public final class SelfUpdate {
    static final String TAG = "SelfUpdate";
    static final int JOB_PERIODIC = 7760, JOB_SOON = 7761;

    /** 지금 보이는 화면 수 · 살아 있는 게임 화면 수 — 메인 스레드에서만 바뀐다 */
    static volatile int started = 0, games = 0;
    /** 런처나 주기 작업이 본 새 판 번호(내부). 없거나 모르면 0 */
    static volatile int newer = 0;

    private SelfUpdate() { }

    static void attach(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) { if (a instanceof EmuActivity) games++; }
            @Override public void onActivityStarted(Activity a) { started++; }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) {
                if (started > 0) started--;
                /* 회전 등으로 다시 만들어지는 중이면 «나간» 게 아니다 */
                if (started == 0 && !a.isChangingConfigurations()) soon(a.getApplicationContext());
            }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { if (a instanceof EmuActivity && games > 0) games--; }
        });
        schedule(app);
    }

    static boolean supported() { return Build.VERSION.SDK_INT >= 31; }

    static boolean enabled() {
        String v = null;
        try { v = Updater.Settings2.readOpt("pocketcore_autoupdate"); } catch (Throwable ignored) { }
        return !"off".equals(v);
    }

    static boolean busy() { return started > 0 || games > 0; }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("selfupdate", Context.MODE_PRIVATE); }

    @SuppressWarnings("deprecation")
    static int codeOf(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
    }

    static int myCode(Context c) {
        try { return codeOf(c.getPackageManager().getPackageInfo(c.getPackageName(), 0)); }
        catch (Throwable t) { return 0; }
    }

    /** 주기 작업 걸기 — 이미 걸려 있으면 그대로(다시 걸면 주기가 처음부터 다시 센다). 꺼져 있으면 지운다. */
    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            if (!supported() || !enabled()) { js.cancel(JOB_PERIODIC); js.cancel(JOB_SOON); return; }
            if (js.getPendingJob(JOB_PERIODIC) != null) return;
            js.schedule(new JobInfo.Builder(JOB_PERIODIC, new ComponentName(c, UpdateJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(3 * 3600_000L, 3600_000L)
                    .setPersisted(true)
                    .build());
        } catch (Throwable t) { Log.w(TAG, "schedule", t); }
    }

    /** 앱을 막 나갔다 — 새 판이 있다고 알고 있으면 15초 뒤 한 번 돌린다(그 사이 돌아오면 작업이 알아서 쉰다). */
    static void soon(Context c) {
        if (newer <= 0 || !supported() || !enabled()) return;
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            js.schedule(new JobInfo.Builder(JOB_SOON, new ComponentName(c, UpdateJob.class))
                    .setMinimumLatency(15_000)
                    .setOverrideDeadline(120_000)
                    .build());
        } catch (Throwable t) { Log.w(TAG, "soon", t); }
    }

    /** 작업 본문(작업 스레드) — 새 판이 있으면 받아서 조용히 깐다. 돌려주는 글은 로그용. */
    static String runBackground(Context c) {
        if (!supported()) return "sdk " + Build.VERSION.SDK_INT;
        if (!enabled()) return "off";
        if (busy()) return "busy";
        if (!c.getPackageManager().canRequestPackageInstalls()) return "no-install-permission";
        try {
            JSONObject j = Updater.versionEntry();
            int rc = j.getInt("versionCode"), my = myCode(c);
            if (rc <= my) { newer = 0; clean(c); return "latest " + my; }
            newer = rc;
            File apk = stage(c, Updater.baseUrl() + "/" + Uri.encode(j.getString("apk")), rc);
            if (apk == null) return "bad-download " + rc;
            if (busy()) return "busy-after-download";
            install(c, apk, rc, false);
            return "committed " + my + " -> " + rc;
        } catch (Throwable t) {
            return "error " + t;
        }
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

    /** 세션 설치. interactive = 유저가 직접 눌렀다(확인 창이 필요하면 띄운다). 조용히 깔리면 곧 이 프로세스가 끝난다. */
    static void install(Context c, File apk, int rc, boolean interactive) throws Exception {
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
            prefs(c).edit().putInt("from", myCode(c)).putInt("to", rc).commit();   /* 곧 프로세스가 끝나니 동기로 */
            Intent it = new Intent(c, InstallResult.class).putExtra("interactive", interactive).putExtra("to", rc);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, sid, it, fl).getIntentSender());
            Log.i(TAG, "commit session " + sid + " -> " + rc + (interactive ? " (interactive)" : ""));
        } catch (Exception e) {
            try { pi.abandonSession(sid); } catch (Throwable ignored) { }
            throw e;
        }
    }

    /** 런처가 켜질 때 — 지난번보다 판이 올라 있으면 알려 준다(뒤에서 조용히 바뀐 것을 알 수 있게). */
    static void afterLaunch(Activity a) {
        try {
            SharedPreferences p = prefs(a);
            int my = myCode(a), last = p.getInt("seen", 0);
            if (last > 0 && my > last)
                Toast.makeText(a, "새 판으로 바뀌었습니다 · 내부 " + last + " → " + my, Toast.LENGTH_LONG).show();
            if (my != last) p.edit().putInt("seen", my).apply();
            clean(a);
        } catch (Throwable ignored) { }
    }
}
