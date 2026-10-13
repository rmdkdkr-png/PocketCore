package com.dudu.pocketcore;

import android.app.Application;

/** 앱 프로세스 — 자동 업데이트(SelfUpdate)가 «지금 앱을 쓰고 있나»를 알게 화면 수를 센다.
 *  런처(MainActivity)를 거치지 않고 게임 화면부터 되살아나는 경우(프로세스가 죽었다 살아남)도 세야 해서
 *  액티비티가 아니라 여기서 건다. 주기 작업(UpdateJob)도 여기서 걸어 둔다. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        SelfUpdate.attach(this);
    }
}
