package com.dudu.pocketcore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 사무쇼2 사본에 사무쇼1 배경음악을 심는다 (유저 2026-10-10 「음악이 SS1 이 훨씬 나」).
 *
 * 두 게임은 같은 소리 엔진을 쓴다 — Z80 사운드 드라이버가 바이트까지 같고(2,749B), 메인 CPU 의 «조립 코드»도 같다.
 * 장면마다 메인 CPU 가 롬의 «세트»(조립서)를 보고 Z80 램 0x0AB5 에 소리 덩어리를 이어 붙인다:
 *   [머리 6: C3 0A · 곡 ptr · 효과음 ptr] [악기 9바이트 × n] [곡 항목] [효과음 조각들]   (끝이 0x0FFF 를 넘으면 안 됨)
 * 세트 = [머리 6] n [은행 번호 n개] [곡 레코드 ptr(4)] k [곡 악기칸 k개] ([효과음 조각 ptr(4)] c [악기칸 c개])* [00 00 00 00]
 * 곡 레코드 = [곡번호][00][악기칸 자리] + 곡 항목(첫 낱말 하위 14비트 = 길이, 안은 상대 오프셋뿐이라 어디로 옮겨도 됨).
 * 악기 정의는 «은행»(9바이트 배열)에 있고, 은행 시작 주소와 세트표 주소는 조립 코드의 즉시값에 하나씩 들어 있다.
 *
 * 그래서 SS1 곡을 SS2 세트에 넣으려면: SS1 곡 레코드를 복사하고, 그 곡이 쓰는 SS1 악기 정의를 SS2 은행(옮긴 새 은행)에
 * 덧붙이고, SS2 세트의 악기 목록은 «공통 0~9 + 효과음이 쓰는 칸»을 그대로 둔 채 나머지 칸을 SS1 악기로 갈아 끼운다.
 * 효과음은 SS2 것 그대로다. 비트 단위로 압축된 악보 자체는 손대지 않는다.
 *
 * 롬은 앱이 폰에 있는 사용자의 SS1 롬에서 그때그때 읽는다 — 배포물에 SS1 데이터는 없다.
 * 위치는 고정 주소가 아니라 조립 코드의 서명으로 찾는다(한패·판 차이에 견디게). 어긋나면 null — 원래 음악 그대로.
 */
final class Ss1Music {
    private static final int ROMBASE = 0x200000;
    private static final int BLOB_AT = 0x0AB5, Z80_TOP = 0x1000;
    private static final byte[] BANK_SIG = hex("00f307ece8333109001e");
    private static final byte[] TABLE_SIG = hex("00e880e880e883a323ebcf");

    /** 같은 곡: SS2 가 SS1 에서 물려받은 10곡 (SS2 칸, SS1 칸) — 내용 비교 0.85 이상 */
    static final int[][] MAP_SAME = { {6, 4}, {7, 7}, {8, 8}, {10, 10}, {11, 11}, {12, 12}, {13, 13}, {14, 14}, {18, 18}, {19, 19} };
    /** 전부: 같은 곡 + 장면이 같은 칸(인트로·타이틀·라운드 시작·막간·엔딩) + SS2 에만 있는 무대 ← SS1 에만 있는 대전곡.
     *  장면은 두 게임을 자동 진행시켜 확인(SS2: 21판 → 엔딩, SS1: 9무대 → 엔딩). */
    static final int[][] MAP_ALL = {
        {6, 4}, {7, 7}, {8, 8}, {10, 10}, {11, 11}, {12, 12}, {13, 13}, {14, 14}, {18, 18}, {19, 19},
        {0, 0}, {1, 1}, {2, 2}, {3, 3}, {4, 5}, {5, 6},
        {9, 9}, {15, 15}, {16, 16}, {17, 17}, {20, 20}, {23, 23}, {21, 9}, {22, 10} };

    private static final class Set1 {
        int slot, at, end, bgm, id;
        int[] ins, refs;
        List<int[]> sfx = new ArrayList<>();   /* [ptr(rom off), slots...] */
    }

    /** ss2 사본에 SS1 곡을 심은 새 배열. all=false 면 같은 곡만. 못 하면 null. */
    static byte[] apply(byte[] ss2, byte[] ss1, boolean all) {
        try {
            int[] l2 = layout(ss2), l1 = layout(ss1);
            if (l2 == null || l1 == null) return null;
            List<Set1> s2 = parseSets(ss2, l2[2]), s1 = parseSets(ss1, l1[2]);
            if (s2.size() < 24 || s1.size() < 24) return null;
            byte[] out = ss2.clone();
            int b2 = l2[0], b1 = l1[0];
            int nBank2 = 0;
            for (Set1 s : s2) for (int x : s.ins) nBank2 = Math.max(nBank2, x + 1);
            List<byte[]> bank = new ArrayList<>();
            for (int i = 0; i < nBank2; i++) bank.add(Arrays.copyOfRange(ss2, b2 + 9 * i, b2 + 9 * i + 9));

            int[][] pairs = all ? MAP_ALL : MAP_SAME;
            List<Object[]> plans = new ArrayList<>();
            for (int[] pr : pairs) {
                Set1 s = s2.get(pr[0]), t = s1.get(pr[1]);
                int lb = entryLen(ss1, t.bgm);
                Set<Integer> keep = new HashSet<>();
                for (int i = 0; i < Math.min(10, s.ins.length); i++) keep.add(i);
                for (int[] sf : s.sfx) for (int j = 1; j < sf.length; j++) keep.add(sf[j]);
                List<Integer> ins = new ArrayList<>();
                for (int x : s.ins) ins.add(x);
                List<Integer> freePos = new ArrayList<>();
                for (int i = 0; i < ins.size(); i++) if (!keep.contains(i)) freePos.add(i);
                int[] refs = new int[t.refs.length];
                for (int j = 0; j < t.refs.length; j++) {
                    int bi1 = t.ins[t.refs[j]];
                    byte[] defn = Arrays.copyOfRange(ss1, b1 + 9 * bi1, b1 + 9 * bi1 + 9);
                    int bi = -1;
                    for (int q = 0; q < bank.size(); q++) if (Arrays.equals(bank.get(q), defn)) { bi = q; break; }
                    if (bi < 0) { bank.add(defn); bi = bank.size() - 1; }
                    int loc = -1;
                    for (int q = 0; q < ins.size(); q++)
                        if (ins.get(q) == bi && (keep.contains(q) || !freePos.contains(q))) { loc = q; break; }
                    if (loc < 0) {
                        if (!freePos.isEmpty()) { loc = freePos.remove(0); ins.set(loc, bi); }
                        else { ins.add(bi); loc = ins.size() - 1; }
                    }
                    refs[j] = loc;
                }
                int sfxLen = 0;
                for (int[] sf : s.sfx) sfxLen += entryLen(ss2, sf[0]);
                int size = 14 + 9 * ins.size() + lb + sfxLen;
                if (size > Z80_TOP - BLOB_AT) continue;            /* Z80 램에 안 들어감 — 그 장면은 원래 곡 */
                plans.add(new Object[]{ s, t, ins, refs, lb });
            }
            if (plans.isEmpty()) return null;
            if (bank.size() > 255) return null;

            /* 빈 자리: 0xFF 빈칸(세이브 구역 0x1F0000~ 은 피함) + 갈아 끼워 안 쓰게 된 SS2 곡 레코드·세트 */
            Set<Integer> moved = new HashSet<>();
            for (Object[] p : plans) moved.add(((Set1) p[0]).slot);
            List<int[]> regions = new ArrayList<>();
            int lim = Math.min(out.length, 0x1F0000);
            for (int i = 0; i < lim; ) {
                if ((out[i] & 0xFF) == 0xFF) {
                    int j = i;
                    while (j < lim && (out[j] & 0xFF) == 0xFF) j++;
                    if (j - i >= 64) regions.add(new int[]{ i + 8, j - 8 });
                    i = j;
                } else i++;
            }
            for (Set1 s : s2) {
                if (!moved.contains(s.slot)) continue;
                boolean shared = false;
                for (Set1 o : s2) if (o.bgm == s.bgm && !moved.contains(o.slot)) shared = true;
                if (shared) continue;
                regions.add(new int[]{ s.bgm, s.bgm + 3 + entryLen(ss2, s.bgm) });
                regions.add(new int[]{ s.at, s.end });
            }
            regions.sort((a, b) -> Integer.compare(a[0], b[0]));

            byte[] bankB = new byte[bank.size() * 9];
            for (int i = 0; i < bank.size(); i++) System.arraycopy(bank.get(i), 0, bankB, 9 * i, 9);
            int bankAt = alloc(regions, bankB.length);
            if (bankAt < 0) return null;
            System.arraycopy(bankB, 0, out, bankAt, bankB.length);
            put32(out, l2[1], bankAt + ROMBASE);

            for (Object[] p : plans) {
                Set1 s = (Set1) p[0], t = (Set1) p[1];
                @SuppressWarnings("unchecked") List<Integer> ins = (List<Integer>) p[2];
                int[] refs = (int[]) p[3];
                int lb = (Integer) p[4];
                int ra = alloc(regions, 3 + lb);
                if (ra < 0) return null;
                System.arraycopy(ss1, t.bgm, out, ra, 3 + lb);
                int pb = BLOB_AT + 14 + 9 * ins.size(), ps = pb + lb;
                java.io.ByteArrayOutputStream d = new java.io.ByteArrayOutputStream();
                d.write(0xC3); d.write(0x0A);
                d.write(pb & 0xFF); d.write(pb >> 8); d.write(ps & 0xFF); d.write(ps >> 8);
                d.write(ins.size());
                for (int x : ins) d.write(x);
                w32(d, ra + ROMBASE); d.write(refs.length);
                for (int x : refs) d.write(x);
                for (int[] sf : s.sfx) {
                    w32(d, sf[0] + ROMBASE); d.write(sf.length - 1);
                    for (int j = 1; j < sf.length; j++) d.write(sf[j]);
                }
                w32(d, 0);
                byte[] db = d.toByteArray();
                int da = alloc(regions, db.length);
                if (da < 0) return null;
                System.arraycopy(db, 0, out, da, db.length);
                put32(out, l2[2] + 4 * s.slot, da + ROMBASE);
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** [은행 시작, 은행 즉시값 자리, 세트표] — 조립 코드 서명으로. 서명이 하나씩만 있어야 한다. */
    private static int[] layout(byte[] r) {
        int a = find(r, BANK_SIG, 0), b = find(r, TABLE_SIG, 0);
        if (a < 3 || b < 3 || find(r, BANK_SIG, a + 1) >= 0 || find(r, TABLE_SIG, b + 1) >= 0) return null;
        int bank = u32(r, a - 3) - ROMBASE, table = u32(r, b - 3) - ROMBASE;
        if (bank < 0 || bank >= r.length || table < 0 || table >= r.length) return null;
        return new int[]{ bank, a - 3, table };
    }

    private static List<Set1> parseSets(byte[] r, int table) {
        List<Set1> sets = new ArrayList<>();
        for (int i = table; ; i += 4) {
            int p = u32(r, i);
            if (p == 0) break;
            int s = p - ROMBASE;
            if (s < 0 || s + 8 > r.length || (r[s] & 0xFF) != 0xC3 || (r[s + 1] & 0xFF) != 0x0A) throw new IllegalStateException();
            Set1 o = new Set1();
            o.slot = sets.size(); o.at = s;
            int n = r[s + 6] & 0xFF;
            o.ins = new int[n];
            for (int k = 0; k < n; k++) o.ins[k] = r[s + 7 + k] & 0xFF;
            int j = s + 7 + n;
            o.bgm = u32(r, j) - ROMBASE;
            int k = r[j + 4] & 0xFF;
            o.refs = new int[k];
            for (int q = 0; q < k; q++) o.refs[q] = r[j + 5 + q] & 0xFF;
            j += 5 + k;
            while (true) {
                int q = u32(r, j);
                if (q == 0) { j += 4; break; }
                int c = r[j + 4] & 0xFF;
                int[] sf = new int[1 + c];
                sf[0] = q - ROMBASE;
                for (int z = 0; z < c; z++) sf[1 + z] = r[j + 5 + z] & 0xFF;
                o.sfx.add(sf);
                j += 5 + c;
                if (o.sfx.size() > 64) throw new IllegalStateException();
            }
            o.end = j;
            o.id = r[o.bgm] & 0xFF;
            sets.add(o);
            if (sets.size() > 64) throw new IllegalStateException();
        }
        return sets;
    }

    /** 레코드(3바이트 머리 + 항목)의 항목 길이 */
    private static int entryLen(byte[] r, int rec) {
        return ((r[rec + 3] & 0xFF) | ((r[rec + 4] & 0xFF) << 8)) & 0x3FFF;
    }

    private static int alloc(List<int[]> regions, int n) {
        for (int[] rg : regions)
            if (rg[1] - rg[0] >= n) { int a = rg[0]; rg[0] += n; return a; }
        return -1;
    }

    private static int find(byte[] r, byte[] sig, int from) {
        outer:
        for (int i = from; i + sig.length <= r.length; i++) {
            for (int j = 0; j < sig.length; j++) if (r[i + j] != sig[j]) continue outer;
            return i;
        }
        return -1;
    }
    private static int u32(byte[] r, int a) {
        return (r[a] & 0xFF) | ((r[a + 1] & 0xFF) << 8) | ((r[a + 2] & 0xFF) << 16) | ((r[a + 3] & 0xFF) << 24);
    }
    private static void put32(byte[] r, int a, int v) {
        r[a] = (byte) v; r[a + 1] = (byte) (v >> 8); r[a + 2] = (byte) (v >> 16); r[a + 3] = (byte) (v >> 24);
    }
    private static void w32(java.io.ByteArrayOutputStream d, int v) {
        d.write(v & 0xFF); d.write((v >> 8) & 0xFF); d.write((v >> 16) & 0xFF); d.write((v >> 24) & 0xFF);
    }
    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** 롬 폴더에서 사무쇼1 롬(헤더 SAMURAI, SAMURAI2 아님)을 찾는다. 없으면 null.
     *  롬 폴더에 압축(.zip/.7z)째로 넣어 뒀으면 안의 SS1 롬을 꺼내 옆에 풀어 두고 그걸 쓴다
     *  (유저 2026-10-10 — SS1 롬이 7z 째라 못 찾음). */
    static java.io.File findSs1Rom() {
        java.io.File dir = MainActivity.romsDir();
        java.io.File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (java.io.File f : fs) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (!(n.endsWith(".ngp") || n.endsWith(".ngc") || n.endsWith(".npc"))) continue;
            Games.Game g = Games.identify(f.getAbsolutePath());
            if (g != null && "ss1".equals(g.id)) return f;
        }
        for (java.io.File f : fs) {
            if (!f.isFile() || !Archives.isArchiveName(f.getName())) continue;
            final java.io.File[] got = new java.io.File[1];
            final java.io.File d0 = dir;
            Archives.forEachRom(f, new Archives.Visitor() {
                @Override public boolean rom(String e, byte[] data) {
                    Games.Game g = Games.identifyBytes(data);
                    if (g == null || !"ss1".equals(g.id)) return true;
                    java.io.File out = new java.io.File(d0, Archives.baseName(e));
                    if (out.exists()) out = new java.io.File(d0, "SS1 " + System.currentTimeMillis() + ".ngp");
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(out)) { o.write(data); }
                    catch (Exception x) { return true; }
                    got[0] = out;
                    return false;
                }
            });
            if (got[0] != null) return got[0];
        }
        return null;
    }
}
