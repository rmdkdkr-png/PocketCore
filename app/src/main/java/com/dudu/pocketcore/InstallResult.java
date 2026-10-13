package com.dudu.pocketcore;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

/** 세션 설치 결과. 조용히 깔리면(STATUS_SUCCESS) 보통 이걸 받기 전에 프로세스가 끝난다.
 *  확인이 필요하다고 오면(STATUS_PENDING_USER_ACTION) — 유저가 직접 누른 설치면 확인 창을 띄우고,
 *  뒤에서 돈 설치면 아무것도 안 띄운다(런처의 「새 판 받기」 버튼이 남는다). */
public class InstallResult extends BroadcastReceiver {
    @SuppressWarnings("deprecation")
    @Override public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        boolean interactive = i.getBooleanExtra("interactive", false);
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(SelfUpdate.TAG, "status " + st + " " + msg + (interactive ? " (interactive)" : ""));
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33
                    ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (interactive && confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); } catch (Throwable t) { Log.w(SelfUpdate.TAG, "confirm", t); }
            }
            SelfUpdate.prefs(c).edit().putString("last", "needs-confirm").apply();
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            SelfUpdate.prefs(c).edit().putString("last", "fail " + st + " " + msg).apply();
            if (interactive && st != PackageInstaller.STATUS_FAILURE_ABORTED)
                Toast.makeText(c, "설치 실패: " + msg, Toast.LENGTH_LONG).show();
        }
    }
}
