package com.dudu.pocketcore;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 롬을 앱 폴더로 데려오는 세 가지 길 (제보: 「경로를 잘 가르쳐 주던지, 주워서 복사해오는
 * 기능을 만들던지, 스캔해서 모을 수 있던지 — 하나는 해라 전부 다 하던지」 → 전부 다).
 *
 *  1. 경로 복사 — 정확한 폴더 주소를 클립보드로. PC 연결·파일 앱에서 그대로 붙여넣기.
 *  2. 파일 골라 가져오기 — 안드로이드 파일 선택기(SAF)로 다운로드 폴더든 어디든
 *     직접 골라 오면 앱이 roms/ 로 복사한다 (여러 개 동시 선택 가능).
 *  3. 저장소 스캔 — 내장 저장소를 훑어 .ngc/.ngp/.npc 를 전부 찾아 보여 주고,
 *     체크한 것을 한꺼번에 복사한다.
 *
 * 어느 길이든 **원본은 그대로 두고 사본을 만든다** — 패치와 같은 원칙.
 */
public final class RomImport {

    public static final int REQ_PICK = 77;
    /** 복사가 일어났다는 표시 — MainActivity.onResume 이 보고 목록을 다시 그린다. */
    public static volatile boolean changed = false;

    private static boolean isRom(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".ngc") || n.endsWith(".ngp") || n.endsWith(".npc");
    }

    /* ── 1. 경로 복사 ── */

    public static void copyPath(Activity a) {
        String p = MainActivity.romsDir().getAbsolutePath();
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("PocketCore roms", p));
        Toast.makeText(a, "복사했습니다\n" + p, Toast.LENGTH_LONG).show();
    }

    /* ── 2. 파일 골라 가져오기 (SAF) ── */

    public static void pick(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");   /* .ngc 는 표준 MIME 이 없어 전체에서 고른다 */
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            a.startActivityForResult(Intent.createChooser(i, "롬 파일 선택"), REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(a, "파일 선택기를 열 수 없습니다", Toast.LENGTH_SHORT).show();
        }
    }

    /** onActivityResult(REQ_PICK) 에서 호출 — 고른 파일들을 roms/ 로 복사. */
    public static void onPicked(Activity a, Intent data) {
        if (data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null)
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());

        int ok = 0, skip = 0, notRom = 0;
        for (Uri u : uris) {
            String name = displayName(a, u);
            if (name == null || !isRom(name)) { notRom++; continue; }
            File dst = new File(MainActivity.romsDir(), name);
            try {
                long len = -1;
                try (InputStream in = a.getContentResolver().openInputStream(u)) {
                    len = copy(in, dst, dst.exists() ? dst.length() : -1);
                }
                if (len < 0) skip++; else { ok++; changed = true; }
            } catch (Exception e) {
                Toast.makeText(a, name + " 복사 실패", Toast.LENGTH_SHORT).show();
            }
        }
        report(a, ok, skip, notRom);
    }

    /* ── 3. 저장소 스캔 ── */

    public static void scan(final Activity a) {
        final AlertDialog wait = new AlertDialog.Builder(a)
                .setMessage("저장소에서 롬을 찾는 중…").setCancelable(false).create();
        wait.show();
        new Thread(new Runnable() { @Override public void run() {
            final List<File> found = new ArrayList<>();
            walk(Environment.getExternalStorageDirectory(), 0, new int[]{ 0 }, found);
            /* 머리표를 읽어 «패치가 준비된 게임»만 가려낸다. 파일마다 12바이트를 읽으므로
               반드시 여기(작업 스레드)에서 한다 — 목록 그릴 때 하면 화면이 걸린다. */
            final java.util.Map<String, java.util.Set<String>> known = knownRoms();
            final List<File> ready = new ArrayList<>();
            final java.util.Map<File, String> why = new java.util.HashMap<>();   /* 빠진 이유 — 라벨에 단다 */
            for (File f : found) {
                Games.Game g = Games.identify(f.getPath());
                if (g == null)          { why.put(f, "모르는 롬"); continue; }
                if (!g.patchable)       { why.put(f, "한글패치 없음"); continue; }      /* ① 한패가 있는 게임만 */
                java.util.Set<String> hs = known.get(g.id);
                if (hs != null && !hs.contains(md5(f))) {                             /* ② 아는 원본만 */
                    why.put(f, "확인 안 한 덤프이거나 이미 패치된 롬");
                    continue;
                }
                ready.add(f);
            }
            a.runOnUiThread(new Runnable() { @Override public void run() {
                wait.dismiss();
                showList(a, found, ready, why, false);
            }});
        }}).start();
    }

    /** 재귀 탐색 — Android/(앱 전용)·숨김·roms 자신은 건너뛴다. 깊이 8, 폴더 4000개 상한. */
    private static void walk(File dir, int depth, int[] visited, List<File> out) {
        if (depth > 8 || visited[0] > 4000) return;
        visited[0]++;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        String self = MainActivity.romsDir().getAbsolutePath();
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth == 0 && n.equals("Android")) continue;
                if (f.getAbsolutePath().equals(self)) continue;
                walk(f, depth + 1, visited, out);
            } else if (isRom(n) && f.length() > 0 && f.length() <= 8 * 1024 * 1024) {
                out.add(f);
            }
        }
    }

    /**
     * 찾은 것을 보여 준다. 기본은 **패치가 준비된 롬만** (유저 지시) — 남은 것은
     * 「나머지 N개도 보기」로 언제든 꺼낼 수 있다. 조용히 빼면 스캔이 고장 난 걸로 읽힌다.
     */
    private static void showList(final Activity a, final List<File> found,
                                 final List<File> ready, final java.util.Map<File, String> why,
                                 final boolean all) {
        final int etc = found.size() - ready.size();
        final List<File> show = all ? found : ready;
        if (show.isEmpty()) {
            String msg = found.isEmpty()
                    ? "저장소에서 롬(.ngc/.ngp/.npc)을 찾지 못했습니다."
                    : "롬 " + found.size() + "개를 찾았지만, 한글패치가 있는 순정 롬이 없습니다.\n"
                            + "(이미 패치된 롬이거나, 우리가 확인하지 않은 덤프일 수 있습니다)";
            AlertDialog.Builder b = new AlertDialog.Builder(a)
                    .setTitle("스캔 결과")
                    .setMessage(msg + "\n\nPC 에서 넣을 폴더:\n"
                            + MainActivity.romsDir().getAbsolutePath())
                    .setPositiveButton("확인", null);
            if (etc > 0) b.setNeutralButton("찾은 것 " + found.size() + "개 보기",
                    new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            showList(a, found, ready, why, true);
                        }
                    });
            b.show();
            return;
        }
        String base = Environment.getExternalStorageDirectory().getAbsolutePath();
        final String[] labels = new String[show.size()];
        final boolean[] checked = new boolean[show.size()];
        for (int i = 0; i < show.size(); i++) {
            File f = show.get(i);
            File dst = new File(MainActivity.romsDir(), f.getName());
            boolean dup = dst.exists() && dst.length() == f.length();
            String rel = f.getAbsolutePath().startsWith(base)
                    ? f.getAbsolutePath().substring(base.length() + 1) : f.getAbsolutePath();
            Games.Game g = Games.identify(f.getPath());
            String reason = why.get(f);                                  /* 걸러진 롬이면 왜 걸러졌나 */
            labels[i] = (g == null ? rel : g.ko + "\n" + rel)
                    + String.format("  (%.1fMB)", f.length() / 1048576f)
                    + (dup ? " — 이미 있음" : "")
                    + (reason != null ? "\n⚠ " + reason : "");
            /* 걸러진 롬은 「전부 보기」에서도 기본 체크 해제 — 유저가 «일부러» 켜야 들어온다.
               안 그러면 이 기능이 막으려던 사고(패치된 롬에 또 패치)가 그 버튼 뒤에서 난다. */
            checked[i] = !dup && reason == null;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(all ? "찾은 롬 " + found.size() + "개"
                              : "한글패치가 있는 순정 롬 " + ready.size() + "개")
                .setMultiChoiceItems(labels, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override public void onClick(DialogInterface d, int w, boolean c) {
                                checked[w] = c;
                            }
                        })
                .setPositiveButton("가져오기", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int ok = 0, skip = 0;
                        for (int i = 0; i < show.size(); i++) {
                            if (!checked[i]) continue;
                            File src = show.get(i);
                            File dst = new File(MainActivity.romsDir(), src.getName());
                            try (InputStream in = new FileInputStream(src)) {
                                long r = copy(in, dst, dst.exists() ? dst.length() : -1);
                                if (r < 0) skip++; else { ok++; changed = true; }
                            } catch (Exception e) { /* 개별 실패는 집계만 */ }
                        }
                        report(a, ok, skip, 0);
                    }
                })
                .setNegativeButton("취소", null);
        if (!all && etc > 0)
            b.setNeutralButton("나머지 " + etc + "개도 보기",
                    new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            showList(a, found, ready, why, true);
                        }
                    });
        b.show();
    }

    /* ── 「아는 원본」 표 ─────────────────────────────────────────── */

    /** 색인(patch/patches.json)의 게임별 rom_md5 목록. 없으면 빈 map — 그러면 안 가린다. */
    private static java.util.Map<String, java.util.Set<String>> knownRoms() {
        java.util.Map<String, java.util.Set<String>> out = new java.util.HashMap<>();
        File f = new File(MainActivity.root(), "patch/patches.json");
        if (!f.exists()) return out;
        try {
            byte[] b = new byte[(int) f.length()];
            try (InputStream in = new FileInputStream(f)) {
                int n = 0, r;
                while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            }
            org.json.JSONObject j = new org.json.JSONObject(new String(b, "UTF-8"))
                    .getJSONObject("patches");
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String id = it.next();
                org.json.JSONArray a = j.getJSONObject(id).optJSONArray("rom_md5");
                if (a == null || a.length() == 0) continue;
                java.util.Set<String> s = new java.util.HashSet<>();
                for (int i = 0; i < a.length(); i++) s.add(a.getString(i).toLowerCase());
                out.put(id, s);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 파일 md5. 못 읽으면 빈 문자열 — 그러면 「아는 원본」에 안 들어가 나머지로 간다. */
    private static String md5(File f) {
        try (InputStream in = new FileInputStream(f)) {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) d.update(buf, 0, r);
            StringBuilder sb = new StringBuilder();
            for (byte x : d.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /* ── 공용 ── */

    /** 복사. 같은 이름이 이미 있고 크기가 같으면 건너뛴다(-1). 크기가 다르면 「이름 (2)」. */
    private static long copy(InputStream in, File dst, long existLen) throws Exception {
        if (existLen >= 0) {
            /* 크기를 미리 모르니 임시로 받아 비교한다 — 롬은 커야 4MB 라 부담이 없다 */
            File tmp = new File(dst.getParentFile(), "." + dst.getName() + ".part");
            long n = stream(in, tmp);
            if (n == existLen) { tmp.delete(); return -1; }        /* 같은 크기 = 같은 롬 취급 */
            String base = dst.getName(), ext = "";
            int dot = base.lastIndexOf('.');
            if (dot > 0) { ext = base.substring(dot); base = base.substring(0, dot); }
            File alt = new File(dst.getParentFile(), base + " (2)" + ext);
            tmp.renameTo(alt);
            return n;
        }
        return stream(in, dst);
    }

    private static long stream(InputStream in, File dst) throws Exception {
        dst.getParentFile().mkdirs();
        long total = 0;
        try (FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) { out.write(buf, 0, r); total += r; }
        }
        return total;
    }

    private static String displayName(Activity a, Uri u) {
        try (android.database.Cursor c = a.getContentResolver()
                .query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) { }
        String p = u.getLastPathSegment();
        return p == null ? null : p.substring(p.lastIndexOf('/') + 1);
    }

    private static void report(Activity a, int ok, int skip, int notRom) {
        StringBuilder sb = new StringBuilder();
        if (ok > 0) sb.append(ok).append("개 가져왔습니다");
        if (skip > 0) sb.append(sb.length() > 0 ? " · " : "").append(skip).append("개는 이미 있어 건너뜀");
        if (notRom > 0) sb.append(sb.length() > 0 ? " · " : "").append(notRom).append("개는 롬이 아님");
        if (sb.length() == 0) sb.append("가져온 것이 없습니다");
        Toast.makeText(a, sb.toString(), Toast.LENGTH_LONG).show();
    }

    private RomImport() { }
}
