package com.dudu.pocketcore;

import java.nio.ByteBuffer;

/** Thin JNI surface. Everything real happens in native.c. */
public final class Emu {
    static { System.loadLibrary("pocketcore"); }

    // libretro joypad bit positions
    public static final int B = 0, Y = 1, SELECT = 2, START = 3,
            UP = 4, DOWN = 5, LEFT = 6, RIGHT = 7, A = 8, X = 9, L = 10, R = 11;

    public static native int  nativeLoad(String corePath, String romPath,
                                         String sysDir, String saveDir, String optionsFile);
    public static native void nativeUnload();
    public static native void nativeSurfaceCreated();
    public static native void nativeResize(int w, int h);
    public static native void nativeFrame();
    public static native void nativeSetInput(int mask);
    public static native void nativeReset();
    public static native void nativeSetOption(String key, String value);
    public static native void nativeSetIntegerScale(boolean on);
    public static native void nativeSetTurbo(boolean on);
    public static native int  nativeSaveState(String path);
    public static native int  nativeLoadState(String path);
    public static native int  nativeFrameWidth();
    public static native int  nativeFrameHeight();
    public static native ByteBuffer nativeFrameBuffer();
    public static native void nativeSaveSram();
    public static native void nativeAudioPause();   /* 백그라운드 — 오디오 장치를 놓는다 */
    public static native void nativeAudioResume();  /* 복귀 — 새 스트림으로 다시 연다 */
    public static native void nativeRunFrames(int n); /* GL 없이 n프레임 — 썸네일 캡처용 */
    /* 프레임 생성(중간 프레임 보간) — 0=끔 1=섞기 2=움직임 보정. 120Hz 같은 빠른 화면에서만 실제로 끼운다 */
    public static native void nativeSetFrameGen(int mode);
    /** 실측 전 대용 패널 주사율(Display.getRefreshRate) — 코어의 GET_TARGET_REFRESH_RATE 답에 쓴다. */
    public static native void nativeSetPanelHz(float hz);
    /** 코어 멈춤 — 게임 안 「설정」 창이 떠 있는 동안. 그림은 마지막 것을 계속 그린다. */
    public static native void nativeSetPaused(boolean on);
    /** 지금 끼우고 있으면 화면 Hz, 화면이 느려 못 끼우면 0, 아직 모르면 -1. */
    public static native int  nativeFrameGenActive();
    /** 실측 패널 주사율(60/90/120…) — 상태 토스트용. */
    public static native float  nativePanelHz();
    /** 코어가 지금 선언한 fps — 사무쇼2 코어가 프레임 생성을 켜면 120.5. */
    public static native double nativeCoreFps();
}
