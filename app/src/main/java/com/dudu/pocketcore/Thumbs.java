package com.dudu.pocketcore;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * 런처 썸네일 — 롬을 **몰래 1회 부팅**해 타이틀 화면을 찍어 둔다.
 *
 * 「로딩 전에 롬에서 그림만 뽑기」는 일반해가 없다 — 어느 타일이 타이틀인지 게임마다
 * 달라서다. 대신 GL 없이 코어만 돌리는 nativeRunFrames 로 900프레임을 재우고
 * (cb_video 가 CPU 버퍼를 채운다) 그 화면을 PNG 로 박제한다. **롬당 한 번뿐**이고
 * 다음부터는 캐시만 읽으므로 런처가 로딩 없이 뜬다.
 *
 * 한패가 있으면 패치 사본으로 찍는다 — 썸네일부터 한글 타이틀이 뜨는 것이 맞고,
 * 그 사본은 어차피 실행 때 다시 쓰인다.
 */
public final class Thumbs {

    private static final int BOOT_FRAMES = 900;   /* 타이틀 도달 + 판번호 토스트 소멸 */

    /** 코어 전역 상태는 하나뿐 — 캡처와 게임 실행이 겹치면 안 된다.
     *  실행 쪽은 stop 을 세우고 LOCK 을 한 번 잡았다 놓는 것으로 현재 캡처를 기다린다. */
    public static final Object LOCK = new Object();
    public static volatile boolean stop = false;

    public static File dir() { return new File(MainActivity.root(), "design/thumbs"); }

    /** 캐시 파일 — 원본 롬 이름 기준 (패치 사본과 무관하게 하나). */
    public static File of(File rom) { return new File(dir(), rom.getName() + ".png"); }

    /** 캐시가 없으면 만들고, 성공하면 true. UI 스레드에서 부르지 말 것. */
    public static boolean ensure(Context ctx, File rom) {
        synchronized (LOCK) {
            return ensure0(ctx, rom);
        }
    }

    private static boolean ensure0(Context ctx, File rom) {
        File out = of(rom);
        if (out.exists()) return true;
        if (stop) return false;
        try {
            Games.Game game = Games.identify(rom.getPath());
            String lang = "ko-ja";
            try {
                java.util.Map<String, String> m = Settings.load();
                /* 선택창의 게임별 선택(pocketcore_lang_<id>)이 있으면 그것 — EmuActivity.readLang 과 같은 우선순위(리뷰) */
                String v = (game != null) ? m.get("pocketcore_lang_" + game.id) : null;
                if (v == null) v = m.get("pocketcore_lang");
                if (v != null && !v.isEmpty()) lang = v;
            } catch (Throwable ignored) { }
            String romPath = Patcher.resolve(ctx, rom.getPath(), game, lang, false, false);

            /* 코어 선택 — EmuActivity 와 같은 순서의 축약판: 내부 자동 → 동봉 */
            String want = (game != null && "ss2".equals(game.id)) ? "ss2" : "svc";
            File auto = new File(new File(ctx.getFilesDir(), "cores"), want + ".so");
            String core = auto.exists() ? auto.getAbsolutePath()
                    : ctx.getApplicationInfo().nativeLibraryDir + "/"
                      + ((game != null) ? game.core : Games.fallbackCore());

            int rc = Emu.nativeLoad(core, romPath,
                    MainActivity.sysDir().getAbsolutePath(),
                    MainActivity.saveDir().getAbsolutePath(),
                    MainActivity.optsFile().getAbsolutePath());
            if (rc != 0) return false;
            Emu.nativeAudioPause();               /* 소리 없이 */
            /* 띠·기둥·해설창 없이 게임 화면만 — 런타임 옵션이라 options.txt 는 안 건드린다 */
            Emu.nativeSetOption("ngp_svcsp_band", "disabled");
            Emu.nativeSetOption("ngp_ss2sp_sides", "disabled");
            Emu.nativeSetOption("ngp_ss2sp_comm_draw", "disabled");
            Emu.nativeRunFrames(BOOT_FRAMES);
            int w = Emu.nativeFrameWidth(), h = Emu.nativeFrameHeight();
            Bitmap bmp = null;
            if (w > 0 && h > 0) {
                ByteBuffer buf = Emu.nativeFrameBuffer();
                if (buf != null) {
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    buf.rewind();
                    bmp.copyPixelsFromBuffer(buf);
                }
            }
            Emu.nativeUnload();
            if (bmp == null) return false;
            dir().mkdirs();
            try (FileOutputStream fo = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
            }
            return true;
        } catch (Throwable t) {
            return false;                          /* 썸네일은 장식 — 실패해도 목록은 뜬다 */
        }
    }

    private Thumbs() { }
}
