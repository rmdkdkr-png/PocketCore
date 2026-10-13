package com.dudu.pocketcore;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/** 앱 아이콘 고르기(내부 121~) — 기본 · 먹·금 · 포켓 · 모던. 옵션 키 pocketcore_icon.
 *
 *  런처 아이콘은 매니페스트의 «별칭(activity-alias)» 넷이 나눠 든다: .MainActivity(기본 — 120 까지 쓰던 이름이라
 *  업데이트해도 홈 화면 아이콘이 그대로 붙어 있다) · .IconInk · .IconPocket · .IconModern. 넷 다 진짜 화면 Home 을 연다.
 *  고른 별칭만 켜고 나머지는 끈다(켜기 먼저 — 잠깐이라도 아이콘이 하나도 없으면 홈 앱이 앱을 지운 것으로 볼 수 있다).
 *
 *  ★ 테마(Theme)와 따로 둔 까닭: 별칭을 바꾸면 삼성·픽셀 홈 화면은 «옛 아이콘»을 홈 화면에서 지운다(앱 서랍엔 새 아이콘).
 *    로고를 눌러 테마를 넘겨 보다가 홈 화면 아이콘이 사라지면 안 되니, 아이콘은 설정에서 일부러 고를 때만 바꾼다. */
final class AppIcon {
    static final String KEY = "pocketcore_icon";
    /** Theme.IDS 와 같은 순서 */
    private static final String[] ALIAS = { ".MainActivity", ".IconInk", ".IconPocket", ".IconModern" };

    private static ComponentName cn(Context c, int i) {
        return new ComponentName(c.getPackageName(), "com.dudu.pocketcore" + ALIAS[i]);
    }

    /** 지금 켜진 별칭 번호(못 알면 0). */
    static int current(Context c) {
        PackageManager pm = c.getPackageManager();
        for (int i = ALIAS.length - 1; i >= 1; i--) {
            try {
                if (pm.getComponentEnabledSetting(cn(c, i)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i;
            } catch (Throwable ignored) { }
        }
        return 0;
    }

    /** 고른 아이콘으로 — 이미 그것이면 아무것도 안 한다. 바꿨으면 true. */
    static boolean apply(Context c, String id) {
        int want = Theme.indexOf(Theme.valid(id) ? id : Theme.CLASSIC);
        if (current(c) == want) return false;
        PackageManager pm = c.getPackageManager();
        try {
            pm.setComponentEnabledSetting(cn(c, want), want == 0
                    ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            for (int i = 0; i < ALIAS.length; i++) {
                if (i == want) continue;
                pm.setComponentEnabledSetting(cn(c, i), i == 0
                        ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        PackageManager.DONT_KILL_APP);
            }
            Log.i("PocketUi", "icon " + Theme.IDS[want]);
            return true;
        } catch (Throwable t) {
            Log.w("PocketUi", "icon", t);
            return false;
        }
    }

    /** 앱이 켜질 때 — options.txt 에 아이콘 값이 있는데(손으로 고쳤거나 다른 기기에서 옮겨 옴) 지금과 다르면 맞춘다.
     *  값이 없으면(고른 적 없음) 아무것도 안 한다 — 매니페스트 기본(.MainActivity)이 그대로. */
    static void sync(Context c) {
        try {
            String v = Settings.load().get(KEY);
            if (v != null && Theme.valid(v)) apply(c, v);
        } catch (Throwable ignored) { }
    }

    private AppIcon() { }
}
