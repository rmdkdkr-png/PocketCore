package com.dudu.pocketcore;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/** 「새 판 받기」 세션 설치 결과를 받는 화면 없는 액티비티(SelfUpdate).
 *  - STATUS_PENDING_USER_ACTION(안드로이드 11 이하·조건 어긋남): 시스템 확인 창을 띄운다 — 예전과 같은 흐름.
 *  - STATUS_SUCCESS: 자기 업데이트라 시스템이 앱을 이미 끝냈다 → 런처를 새로 연다(다 깔리면 앱이 저절로 다시 켜짐).
 *  - 그 밖(실패): 이유를 토스트로. 유저가 확인 창에서 취소(ABORTED)한 것은 조용히. */
public class InstallStatusActivity extends Activity {
    @SuppressWarnings("deprecation")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(SelfUpdate.TAG, "status " + st + " " + msg);
        try {
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = Build.VERSION.SDK_INT >= 33
                        ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) startActivity(confirm);
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            } else if (st != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(this, "설치 실패: " + msg, Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Log.w(SelfUpdate.TAG, "status", t);
        }
        finish();
    }
}
