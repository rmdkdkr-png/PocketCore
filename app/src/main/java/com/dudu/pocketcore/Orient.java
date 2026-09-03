package com.dudu.pocketcore;

import android.app.Activity;
import android.content.pm.ActivityInfo;

/** 화면 방향 — 설정 pocketcore_orientation (auto / portrait / landscape).
 *  세로 고정이던 앱을 게임기(가로 화면·물리 패드)에서도 쓰려는 요청(2026-09-04)으로 신설.
 *  세 액티비티가 onCreate/onResume 에서 부른다. 매니페스트의 screenOrientation 은 뺐다. */
final class Orient {
    private Orient() { }

    static int mode() {
        String v = Settings.load().get("pocketcore_orientation");
        if ("portrait".equals(v))  return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if ("landscape".equals(v)) return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        return ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;          /* auto: 기기 회전을 따른다 */
    }

    static void apply(Activity a) {
        int o = mode();
        if (a.getRequestedOrientation() != o) a.setRequestedOrientation(o);
    }
}
