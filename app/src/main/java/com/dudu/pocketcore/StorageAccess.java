package com.dudu.pocketcore;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * 저장소 권한 설정 화면 열기 — 리그 막힘 카드(«권한 설정 열기»)에서. 11+ = 이 앱의 «모든 파일 접근» 화면, 8~10 = 앱 정보(권한) 화면.
 * 게임 위에서 requestPermissions 창을 띄우지 않는다(창 없이 — 확인 카드 원칙). 런처 첫 실행의 권한 요청은 MainActivity.askStorage 가 따로 한다.
 * 그 화면이 없는 기기(설정 앱을 바꾼 기기 등)면 앱 정보로, 그것도 없으면 아무 일 없이.
 */
final class StorageAccess {
    private StorageAccess() { }

    static void open(Activity a) {
        if (a == null) return;
        Uri pkg = Uri.parse("package:" + a.getPackageName());
        if (Build.VERSION.SDK_INT >= 30) {
            try { a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)); return; }
            catch (ActivityNotFoundException | SecurityException e) { /* 앱 정보로 */ }
        }
        try { a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)); }
        catch (ActivityNotFoundException | SecurityException ignored) { }
    }
}
