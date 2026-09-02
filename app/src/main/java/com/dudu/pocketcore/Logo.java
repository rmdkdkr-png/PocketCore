package com.dudu.pocketcore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;

/** 픽셀 로고(assets/logo.png) — 한 번 읽어 캐시. 없으면 null(텍스트 폴백). */
final class Logo {
    private static Bitmap bmp;
    private static boolean tried;

    static Bitmap get(Context c) {
        if (!tried) {
            tried = true;
            try (InputStream in = c.getAssets().open("logo.png")) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inScaled = false;                 /* 픽셀 그대로 — 확대는 그리는 쪽이 */
                bmp = BitmapFactory.decodeStream(in, null, o);
            } catch (Exception ignored) { }
        }
        return bmp;
    }

    private Logo() { }
}
