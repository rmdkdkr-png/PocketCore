package com.dudu.pocketcore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.LruCache;
import android.view.View;

import org.json.JSONObject;

/**
 * 아바타 비트맵(24×24) — AvatarGen 이 그린 점을 비트맵으로, 메모리 캐시(96장 ≈ 220KB).
 * 그릴 때는 PixelView 로 «정수 배율 · 보간 없음» — 픽셀이 뭉개지지 않게.
 */
final class Avatar {
    private Avatar() { }

    private static final LruCache<Long, Bitmap> CACHE = new LruCache<>(200);   /* 순위표 위 100명 + 내 주변 + 라이벌 */

    static Bitmap bitmap(long seed) {
        Bitmap b = CACHE.get(seed);
        if (b == null) {
            b = Bitmap.createBitmap(AvatarGen.render(seed), AvatarGen.N, AvatarGen.N, Bitmap.Config.ARGB_8888);
            CACHE.put(seed, b);
        }
        return b;
    }

    /** 프로필(pf.av = 16진 64비트 문자열) → 씨앗. 없거나 깨졌으면 id 로. */
    static long seedOf(JSONObject pf, String fallbackId) {
        String s = pf != null ? pf.optString("av", "") : "";
        try { if (!s.isEmpty()) return Long.parseUnsignedLong(s, 16); } catch (NumberFormatException ignored) { }
        return AvatarGen.fnv1a64("av:" + fallbackId);
    }

    static Bitmap of(JSONObject pf, String id) { return bitmap(seedOf(pf, id)); }
}

/** 픽셀 그림 한 장 — 가장 큰 정수 배율로 가운데에, 보간 없이. 테두리 색(0 이면 없음)은 2dp. */
final class PixelView extends View {
    private Bitmap bmp;
    private int frame = 0, dim = 0;
    private final Paint p = new Paint();
    private final Rect src = new Rect(), dst = new Rect();

    PixelView(Context c) { super(c); p.setFilterBitmap(false); p.setAntiAlias(false); p.setDither(false); }

    void set(Bitmap b, int frameColor) { bmp = b; frame = frameColor; invalidate(); }
    /** 굴리는 중(찾는 중) — 위에 검은 막(알파 0~255) */
    void setDim(int alpha) { dim = alpha; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        int fw = frame != 0 ? Math.round(2 * d) : 0;
        if (frame != 0) { p.setColor(frame); c.drawRect(0, 0, w, h, p); }
        if (bmp == null) return;
        int iw = w - 2 * fw, ih = h - 2 * fw;
        int k = Math.max(1, Math.min(iw / bmp.getWidth(), ih / bmp.getHeight()));
        int bw = bmp.getWidth() * k, bh = bmp.getHeight() * k;
        int x0 = fw + (iw - bw) / 2, y0 = fw + (ih - bh) / 2;
        if (frame != 0 && (bw < iw || bh < ih)) { p.setColor(0xff000000); c.drawRect(fw, fw, w - fw, h - fw, p); }  /* 정수 배율 남는 틈 */
        src.set(0, 0, bmp.getWidth(), bmp.getHeight()); dst.set(x0, y0, x0 + bw, y0 + bh);
        p.setColor(0xff000000);                                   /* 비트맵은 페인트 알파를 탄다 — 늘 불투명으로 */
        c.drawBitmap(bmp, src, dst, p);
        if (dim > 0) { p.setColor(dim << 24); c.drawRect(dst, p); }
    }
}
