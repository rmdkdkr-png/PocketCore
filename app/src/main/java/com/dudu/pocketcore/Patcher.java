package com.dudu.pocketcore;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 한글패치(IPS)를 롬에 입힌다.
 *
 * 유저의 롬은 **건드리지 않는다.** 패치본은 PocketCore/.patched/ 에 따로 만들고 그걸 실행한다.
 * 숨김 폴더라 롬 목록에도 안 뜬다 — 같은 게임이 두 번 보이면 무엇을 고르는지 헷갈린다.
 *
 * 다시 만드는 조건은 도장(.stamp)으로 판단한다. 롬 크기·수정시각과 패치 크기를 적어 두고
 * 하나라도 다르면 다시 만든다. 매번 다시 만들면 2~4MB 를 실행할 때마다 쓰게 된다.
 *
 * 이미 한패가 입혀진 롬을 유저가 넣었을 수도 있다. 그때는 사본을 만들 이유가 없다.
 * 판단은 **입혀 보고 결과가 원본과 같은지**로 한다. 처음에는 첫 기록만 들여다보는
 * 싼 방법을 썼는데, 그 구간의 바이트가 원래와 같기만 하면 원본도 「이미 패치됨」으로
 * 오판했다 — 실측에서 SS2·월화 원본 셋 다 걸렸다. 그러면 한패가 영영 안 입혀진다.
 * 어차피 입힐 참이라 한 번 더 도는 비용도 없다.
 */
public final class Patcher {

    public static final String DIR = ".patched";

    /** 패치본 경로를 돌려준다. 패치가 없거나 실패하면 원본 경로를 그대로 돌려준다.
     *  lang 이 ja·en 이면 번역 패치는 필요 없지만, SvC 문턱 패치(fastRom)는 언어와
     *  무관하게 얹을 수 있다.
     *
     *  fastRom: SvC 강공격 문턱 패치(assets/patch/svc_faststrong.ips, 0x000D4E 04→02).
     *  강 기본기가 버튼 8프레임 → 4프레임에 선다. 한글패치와 같은 방식으로 사본에만
     *  입히므로 유저 롬은 안 건드린다. 코어(v3.28+)는 롬의 문턱을 읽어 즉발 주입값을
     *  문턱−1 로 맞추므로 병용해도 안전하다(실측).
     *
     *  withMods: 조작 패치 채널(mods.json) 중 켜진 것들을 얹는다. 예 — 빠른 기본기(FastCD): 서서 강펀·강킥의 애니
     *  대본 WAIT 를 줄여 「누름→히트」 총 길이를 원래 모션 길이에 맞춘 것(원본−문턱,
     *  대본 물리 바닥까지만 — 차등 보존, 전 항목 실측 검증). 문턱·판정 알고리즘은 안
     *  건드리므로 약/강 구분은 순정 그대로. 한글패치와 겹치는 바이트 없음(실측).
     *  현재 svc(20기술)·kofr2(14명) 에 자산이 있고, 다른 게임은 자산이 없어 자동 무시된다. */
    public static String resolve(Context ctx, String romPath, Games.Game game, String lang,
                                 boolean fastRom, boolean withMods) {
        String name = (game != null) ? game.patchFor(lang) : null;
        byte[] extra = (fastRom && game != null && "svc".equals(game.id))
                     ? readAsset(ctx, "patch/svc_faststrong.ips") : null;
        /* 조작 패치(mods): mods.json 에 있고 옵션 pocketcore_<id>=enabled 인 것만, 색인 순서대로 얹는다.
           파일은 내려받은 mods/<id>.ips 우선, 없으면 동봉 assets/patch/<id>.ips(svc/kofr2 FastCD 는 동봉). */
        java.util.List<byte[]> mods = new java.util.ArrayList<>();
        StringBuilder modSig = new StringBuilder();
        if (withMods && game != null) {
            java.util.Map<String, String> opts = Settings.load();
            for (Settings.Mod md : Settings.mods()) {
                if (!game.id.equals(md.game)) continue;
                boolean on;
                if (md.grouped()) {
                    /* 배타 묶음 — 옵션 하나(pocketcore_<group>)가 어느 패치를 고를지 담는다.
                       같은 바이트를 덮는 것들이라 하나만 얹혀야 한다(전엔 둘 다 켜면 나중 것이 이겼다). */
                    String k = "pocketcore_" + md.group;
                    String v = opts.containsKey(k) ? opts.get(k)
                             : ("enabled".equals(md.def) ? md.id : "disabled");
                    on = md.id.equals(v);
                } else {
                    String v = opts.containsKey("pocketcore_" + md.id) ? opts.get("pocketcore_" + md.id) : md.def;
                    on = "enabled".equals(v);
                }
                if (!on) continue;
                byte[] b = readFile(new File(Settings.modsDir(), md.id + ".ips"));
                if (b == null) b = readAsset(ctx, "patch/" + md.id + ".ips");
                if (b == null) continue;
                mods.add(b);
                modSig.append(md.id).append('=').append(md.ver).append(':').append(b.length).append(';');
            }
        }
        try {
            File rom = new File(romPath);
            File pdir = new File(MainActivity.root(), "patch");

            /* 패치 고르기 — 우선순위:
               ① 수동: patch/<롬파일명(확장자 뺌)>.ips — 게임 표에 없는 롬도 입힌다.
                  유저가 아무 IPS 나 직접 넣는 자리다. 언어 설정과 무관하게 항상.
               ② 내려받은 것: patch/<게임id>_ko.ips — 「업데이트 확인」이 받아 둔 최신판.
               ③ 동봉: assets/patch/<게임id>_ko.ips. */
            byte[] ips = null;
            String pver = "";                                 /* 도장용 패치판 표시 */
            String base = rom.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            File manual = new File(pdir, base + ".ips");
            byte[] mb = readFile(manual);
            if (mb != null) {
                ips = mb;
                pver = "m" + manual.length() + "." + manual.lastModified();
            } else if (name != null) {
                File dl = new File(pdir, name);
                ips = readFile(dl);
                if (ips != null) {
                    String v = readText(new File(dl.getPath()
                            .substring(0, dl.getPath().length() - 4) + ".ver"));
                    pver = (v != null) ? v : "dl";
                } else {
                    ips = readAsset(ctx, "patch/" + name);
                }
            }
            if (ips == null && extra == null && mods.isEmpty())
                return romPath;                               /* 쓸 패치가 하나도 없다 */

            /* 사본 이름은 원본과 같게 둔다 — 상태저장 파일 이름이 롬 이름에서 나오므로,
               이름이 바뀌면 언어를 바꿀 때마다 세이브가 갈라진다.
               대신 도장에 언어·문턱 여부를 적어 둔다. 어느 쪽이 바뀌어도 다시 만든다. */
            File out = new File(new File(MainActivity.root(), DIR), rom.getName());
            File stamp = new File(out.getPath() + ".stamp");
            String want = lang + ":" + rom.length() + ":" + rom.lastModified() + ":"
                        + (ips != null ? ips.length : 0)
                        + ":F" + (extra != null ? extra.length : 0)
                        + ":M" + modSig
                        + ":P" + pver;                        /* 새 판 받으면 다시 입힌다 */

            if (out.exists() && want.equals(readText(stamp))) return out.getPath();

            byte[] data = readFile(rom);
            if (data == null) return romPath;

            byte[] done = data;
            if (ips != null) {
                done = apply(done, ips);
                if (done == null) return romPath;            /* 형식이 어긋남 — 원본으로 */
            }
            if (extra != null) {
                byte[] d2 = apply(done, extra);              /* 한패 위에 겹쳐 입힌다 */
                if (d2 != null) done = d2;                   /* 문턱 패치 실패는 치명 아님 */
            }
            for (byte[] modb : mods) {                       /* 조작 패치 — 한패·서로 간 겹침 없음이 원칙 */
                byte[] d3 = apply(done, modb);
                if (d3 != null) done = d3;
            }
            if (java.util.Arrays.equals(done, data)) return romPath;  /* 아무 변화 없음 */

            out.getParentFile().mkdirs();
            try (FileOutputStream fo = new FileOutputStream(out)) { fo.write(done); }
            writeText(stamp, want);
            return out.getPath();
        } catch (Exception e) {
            return romPath;
        }
    }

    /** IPS 적용. 형식이 어긋나면 null — 반쯤 입힌 롬을 내보내지 않는다.
     *  들어온 배열은 **건드리지 않는다.** 처음엔 제자리에서 고쳤는데, 그러면 돌려준 배열이
     *  입력과 같은 객체라 「입혀도 그대로인가」 비교가 항상 참이 되어 한패가 영영 안 입혀졌다.
     *  실측에서 네 롬 전부 「이미 한패 롬」으로 나온 것이 이 때문이었다. */
    private static byte[] apply(byte[] rom, byte[] ips) {
        if (ips.length < 8 || ips[0] != 'P' || ips[1] != 'A' || ips[2] != 'T'
                           || ips[3] != 'C' || ips[4] != 'H') return null;
        byte[] out = rom.clone();
        int p = 5;
        while (p + 3 <= ips.length) {
            if (ips[p] == 'E' && ips[p+1] == 'O' && ips[p+2] == 'F') {
                p += 3;
                if (p + 3 <= ips.length) {                  /* 잘라내기 기록 */
                    int cut = ((ips[p] & 0xff) << 16) | ((ips[p+1] & 0xff) << 8) | (ips[p+2] & 0xff);
                    if (cut > 0 && cut < out.length) {
                        byte[] t = new byte[cut];
                        System.arraycopy(out, 0, t, 0, cut);
                        out = t;
                    }
                }
                return out;
            }
            if (p + 5 > ips.length) return null;
            int off = ((ips[p] & 0xff) << 16) | ((ips[p+1] & 0xff) << 8) | (ips[p+2] & 0xff);
            int len = ((ips[p+3] & 0xff) << 8) | (ips[p+4] & 0xff);
            p += 5;
            if (len == 0) {                                 /* RLE */
                if (p + 3 > ips.length) return null;
                int run = ((ips[p] & 0xff) << 8) | (ips[p+1] & 0xff);
                byte v = ips[p+2];
                p += 3;
                out = grow(out, off + run);
                for (int i = 0; i < run; i++) out[off + i] = v;
            } else {
                if (p + len > ips.length) return null;
                out = grow(out, off + len);
                System.arraycopy(ips, p, out, off, len);
                p += len;
            }
        }
        return null;                                        /* EOF 를 못 만났다 */
    }

    private static byte[] grow(byte[] a, int need) {
        if (need <= a.length) return a;
        byte[] b = new byte[need];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    private static byte[] readAsset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readFile(File f) {
        long len = f.length();
        if (len <= 0 || len > 64L * 1024 * 1024) return null;
        byte[] b = new byte[(int) len];
        try (FileInputStream in = new FileInputStream(f)) {
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) return null;
                n += r;
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readText(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[128];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8").trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeText(File f, String s) {
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(s.getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    private Patcher() { }
}
