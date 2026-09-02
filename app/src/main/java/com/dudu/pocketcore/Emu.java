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
}
