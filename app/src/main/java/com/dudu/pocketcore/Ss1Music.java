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

    /** 심는 방법의 판 — Patcher 도장에 들어간다. 바뀌면 예전에 구워 둔 사본을 다시 굽는다
     *  (2 = 빈칸이 모자라면 통째 포기하던 것을 «들어가는 만큼» + 꼭 맞는 자리 배치로, 2026-10-10)
     *  (3 = 빈칸을 «원래 게임의 끝 채움 두 곳(KNOWN_PAD)의 앞머리·꼬리 중 패치가 안 건드린 곳»으로만 + 풀린 옛 곡·세트·은행 자리를
     *       맞닿은 것끼리 합쳐 씀. 짧은 0xFF 줄은 그림(색 3 통 칸)·표·대사의 빈 줄일 수 있다. 2026-10-10 유저 「프리징 뜸」:
     *       한패 v1.01 이 대사 칸을 0xFF 로 채워 «빈 줄»로 가리키는 자리(0x038EC5~ 등)를 빈칸으로 알고 곡을 써서,
     *       그 장면 대사 엔진이 곡 바이트를 글자로 읽다 엉뚱한 주소로 튀어 0xFF 를 찾아 끝없이 돌았다) */
    static final int VER = 3;

    /** 사무쇼2 원판([!]·[h1] 같음)의 끝 채움 두 곳 — 이 안에서만 빈칸을 찾는다.
     *  0x04F38A~0x050000(블록 끝까지 3,190B) · 0x1DF762~0x1F0000(소리 자료 뒤, 세이브 구역 앞 67,742B).
     *  이미 한패가 입혀진 롬이 들어와도(패치 전 모습을 모름) 한패가 0xFF 로 비운 다른 칸을 빈칸으로 오인하지 않게. */
    static final int[][] KNOWN_PAD = { {0x04F38A, 0x050000}, {0x1DF762, 0x1F0000} };

    /** 빈칸으로 써도 되는 바이트 표. orig = 패치 전 롬(없으면 ss2 자체로 판단), touched = 패치가 쓴 바이트(없으면 null).
     *  끝 채움마다 «앞머리»와 «꼬리»에 남은 0xFF 만 쓴다 — 패치 자료 사이에 낀 0xFF 줄은 IPS 가 안 적었을 뿐
     *  패치 자료(빈 줄·통 칸)일 수 있다(IPS 는 원래 값과 같은 0xFF 를 건너뛴다). 세이브 구역(0x1F0000~)은 늘 뺀다. */
    static boolean[] spare(byte[] ss2, byte[] orig, boolean[] touched) {
        byte[] o = (orig != null && orig.length == ss2.length) ? orig : ss2;
        boolean[] ok = new boolean[ss2.length];
        for (int[] pad : KNOWN_PAD) {
            int a = pad[0], b = Math.min(Math.min(ss2.length, pad[1]), 0x1F0000);
            if (a >= b) continue;
            /* 원판에서도 그 칸 전체가 0xFF 여야(판이 다르면 끝 채움 자리가 아닐 수 있다) */
            int run = 0;
            for (int k = a; k < b; k++) if ((o[k] & 0xFF) == 0xFF) run++;
            if (run < b - a || b - a < 64) continue;
            int h = a;
            while (h < b && free1(ss2, touched, h)) h++;
            for (int k = a; k < h; k++) ok[k] = true;
            int t = b;
            while (t > h && free1(ss2, touched, t - 1)) t--;
            for (int k = t; k < b; k++) ok[k] = true;
        }
        return ok;
    }

    private static boolean free1(byte[] r, boolean[] touched, int k) {
        return (r[k] & 0xFF) == 0xFF && (touched == null || k >= touched.length || !touched[k]);
    }

    static byte[] apply(byte[] ss2, byte[] ss1, boolean all) { return apply(ss2, ss1, all, null); }

    /** 마지막 apply 가 심은 곡 수 / 시도한 곡 수 (토스트용) */
    static volatile int lastPlaced, lastWanted;

    /** ss2 사본에 SS1 곡을 심은 새 배열. all=false 면 같은 곡만. 하나도 못 하면 null.
     *  빈칸이 모자라면(한패 v1.01 은 빈칸을 12K 더 쓴다 — 유저 2026-10-10 「SS1 뮤직을 들려주는 것 같지가 않아」의 원인:
     *  예전엔 하나라도 안 들어가면 통째로 포기해서 원래 음악이 나왔다) 우선순위가 낮은 장면부터 원래 곡으로 남기고 나머지는 심는다. */
    static byte[] apply(byte[] ss2, byte[] ss1, boolean all, boolean[] spare) {
        lastPlaced = 0; lastWanted = 0;
        if (spare == null) spare = spare(ss2, null, null);
        try {
            int[] l2 = layout(ss2), l1 = layout(ss1);
            if (l2 == null || l1 == null) return null;
            List<Set1> s2 = parseSets(ss2, l2[2]), s1 = parseSets(ss1, l1[2]);
            if (s2.size() < 24 || s1.size() < 24) return null;
            int[][] pairs = all ? MAP_ALL : MAP_SAME;
            lastWanted = pairs.length;
            /* 우선순위 순(앞일수록 중요) — 뒤에서부터 빼 보며 빈칸에 들어가는 가장 큰 묶음을 찾는다 */
            List<int[]> want = new ArrayList<>(Arrays.asList(pairs));
            want.sort((x, y) -> Integer.compare(prio(x[0]), prio(y[0])));
            while (!want.isEmpty()) {
                byte[] out = build(ss2, ss1, l2, l1, s2, s1, want, spare);
                if (out != null) { lastPlaced = want.size(); return out; }
                want.remove(want.size() - 1);
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 장면 우선순위(작을수록 먼저) — 들어서 바로 «SS1 곡»이라고 알 수 있는 것부터:
     *  타이틀 → SS2 에만 있는 무대(SS1 대전곡으로 바뀜) → 라운드 시작·막간 → 물려받은 같은 곡 → 엔딩·인트로 */
    private static int prio(int ss2slot) {
        switch (ss2slot) {
            case 1: return 0;                                   /* 타이틀 */
            case 15: case 16: case 17: case 20: case 23: case 21: case 22: case 9: return 10;
            case 2: case 3: return 20;
            case 6: case 7: case 8: case 10: case 11: case 12: case 13: case 14: case 18: case 19: return 30;
            default: return 40;                                 /* 0 인트로 · 4·5 엔딩 */
        }
    }

    /** pairs 를 전부 심어 본다. 빈칸이 모자라면 null(원본은 건드리지 않음). */
    private static byte[] build(byte[] ss2, byte[] ss1, int[] l2, int[] l1, List<Set1> s2, List<Set1> s1, List<int[]> pairs,
                                boolean[] spare) {
        byte[] out = ss2.clone();
        int b2 = l2[0], b1 = l1[0];
        int nBank2 = 0;
        for (Set1 s : s2) for (int x : s.ins) nBank2 = Math.max(nBank2, x + 1);
        List<byte[]> bank = new ArrayList<>();
        for (int i = 0; i < nBank2; i++) bank.add(Arrays.copyOfRange(ss2, b2 + 9 * i, b2 + 9 * i + 9));

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

        /* 빈 자리: 원래 게임의 끝 채움 중 패치가 안 건드린 0xFF(spare) + 갈아 끼워 안 쓰게 된 SS2 곡 레코드·세트 */
        Set<Integer> moved = new HashSet<>();
        for (Object[] p : plans) moved.add(((Set1) p[0]).slot);
        List<int[]> regions = new ArrayList<>();
        int lim = Math.min(Math.min(out.length, spare.length), 0x1F0000);
        for (int i = 0; i < lim; ) {
            if (spare[i] && (out[i] & 0xFF) == 0xFF) {
                int j = i;
                while (j < lim && spare[j] && (out[j] & 0xFF) == 0xFF) j++;
                if (j - i >= 64) regions.add(new int[]{ i + 8, j - 8 });
                i = j;
            } else i++;
        }
        Set<Integer> freedRec = new HashSet<>(), freedSet = new HashSet<>();
        for (Set1 s : s2) {
            if (!moved.contains(s.slot)) continue;
            boolean sharedRec = false, sharedSet = false;
            for (Set1 o : s2) {
                if (moved.contains(o.slot)) continue;
                if (o.bgm == s.bgm) sharedRec = true;
                if (o.at == s.at) sharedSet = true;
            }
            if (!sharedRec && freedRec.add(s.bgm)) regions.add(new int[]{ s.bgm, s.bgm + 3 + entryLen(ss2, s.bgm) });
            if (!sharedSet && freedSet.add(s.at)) regions.add(new int[]{ s.at, s.end });
        }
        /* 옛 은행 — 새 은행으로 옮기고 즉시값을 고치므로 비게 된다 */
        regions.add(new int[]{ b2, b2 + 9 * nBank2 });
        /* 맞닿은 빈자리는 하나로 — 풀린 SS2 곡 레코드들은 거의 줄지어 있어 합치면 큰 SS1 곡도 들어간다 */
        regions.sort((x, y) -> Integer.compare(x[0], y[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] rg : regions) {
            if (rg[1] <= rg[0]) continue;
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && rg[0] <= last[1]) last[1] = Math.max(last[1], rg[1]);
            else merged.add(new int[]{ rg[0], rg[1] });
        }
        regions = merged;

        byte[] bankB = new byte[bank.size() * 9];
        for (int i = 0; i < bank.size(); i++) System.arraycopy(bank.get(i), 0, bankB, 9 * i, 9);
        /* 큰 것부터 «꼭 맞는 자리»에 — 조각난 빈칸을 덜 낭비한다. 쓰기는 자리를 다 정한 뒤에 한 번에 */
        int bankAt = alloc(regions, bankB.length);
        if (bankAt < 0) return null;
        int np = plans.size();
        int[] recAt = new int[np], setAt = new int[np];
        byte[][] desc = new byte[np][];
        Integer[] order = new Integer[np];
        for (int i = 0; i < np; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Integer.compare((Integer) plans.get(y)[4], (Integer) plans.get(x)[4]));
        for (int oi : order) {
            int lb = (Integer) plans.get(oi)[4];
            recAt[oi] = alloc(regions, 3 + lb);
            if (recAt[oi] < 0) return null;
        }
        for (int i = 0; i < np; i++) {
            Object[] p = plans.get(i);
            Set1 s = (Set1) p[0];
            @SuppressWarnings("unchecked") List<Integer> ins = (List<Integer>) p[2];
            int[] refs = (int[]) p[3];
            int lb = (Integer) p[4];
            int pb = BLOB_AT + 14 + 9 * ins.size(), ps = pb + lb;
            java.io.ByteArrayOutputStream d = new java.io.ByteArrayOutputStream();
            d.write(0xC3); d.write(0x0A);
            d.write(pb & 0xFF); d.write(pb >> 8); d.write(ps & 0xFF); d.write(ps >> 8);
            d.write(ins.size());
            for (int x : ins) d.write(x);
            w32(d, recAt[i] + ROMBASE); d.write(refs.length);
            for (int x : refs) d.write(x);
            for (int[] sf : s.sfx) {
                w32(d, sf[0] + ROMBASE); d.write(sf.length - 1);
                for (int j = 1; j < sf.length; j++) d.write(sf[j]);
            }
            w32(d, 0);
            desc[i] = d.toByteArray();
            setAt[i] = alloc(regions, desc[i].length);
            if (setAt[i] < 0) return null;
        }
        /* 자리가 다 정해졌다 — 이제 쓴다 */
        System.arraycopy(bankB, 0, out, bankAt, bankB.length);
        put32(out, l2[1], bankAt + ROMBASE);
        for (int i = 0; i < np; i++) {
            Object[] p = plans.get(i);
            Set1 s = (Set1) p[0], t = (Set1) p[1];
            int lb = (Integer) p[4];
            System.arraycopy(ss1, t.bgm, out, recAt[i], 3 + lb);
            System.arraycopy(desc[i], 0, out, setAt[i], desc[i].length);
            put32(out, l2[2] + 4 * s.slot, setAt[i] + ROMBASE);
        }
        return out;
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

    /** 들어가는 빈칸 중 가장 작은 곳(꼭 맞는 자리)에 */
    private static int alloc(List<int[]> regions, int n) {
        int[] best = null;
        for (int[] rg : regions)
            if (rg[1] - rg[0] >= n && (best == null || rg[1] - rg[0] < best[1] - best[0])) best = rg;
        if (best == null) return -1;
        int a = best[0]; best[0] += n;
        return a;
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

    /** 사무쇼1 롬(헤더 SAMURAI, SAMURAI2 아님)을 찾는다. 없으면 null.
     *  ① 롬 폴더(와 그 아래 한 칸) ② 다운로드 폴더(와 그 아래 한 칸). 맨 롬이든 zip·7z 안이든.
     *  롬 폴더 밖이나 압축 안에서 찾으면 롬 폴더로 꺼내 두고 그걸 쓴다(다음부턴 바로 찾게).
     *  유저 2026-10-10 「ss1 롬을 롬스캔에서 안 끌어오는 것 같아」·「폴더에 넣었는데도 소리도 안 끌고 오는 듯」. */
    static java.io.File findSs1Rom() {
        java.io.File roms = MainActivity.romsDir();
        java.io.File f = findIn(roms, roms, 1);
        if (f != null) return f;
        try {
            java.io.File dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dl != null) f = findIn(dl, roms, 1);
        } catch (Throwable ignored) { }
        return f;
    }

    private static boolean isSs1(java.io.File f) {
        Games.Game g = Games.identify(f.getAbsolutePath());
        return g != null && "ss1".equals(g.id);
    }

    /** dir 안(깊이 depth 까지)에서 SS1 롬을 찾는다. 롬 폴더(roms) 바로 아래의 맨 롬이 아니면 roms 로 꺼내 두고 그 사본을 돌려준다. */
    private static java.io.File findIn(java.io.File dir, final java.io.File roms, int depth) {
        java.io.File[] fs = dir.listFiles();
        if (fs == null) return null;
        java.util.Arrays.sort(fs);
        for (java.io.File f : fs) {                         /* 맨 롬 먼저 */
            if (!f.isFile() || !Archives.isRomName(f.getName()) || f.length() > Archives.MAX_ROM) continue;
            if (!isSs1(f)) continue;
            if (dir.equals(roms)) return f;
            java.io.File out = new java.io.File(roms, f.getName());
            if (out.exists() && out.length() == f.length() && isSs1(out)) return out;
            byte[] d = Patcher.readFile(f);
            if (d == null) continue;
            return save(roms, f.getName(), d);
        }
        for (java.io.File f : fs) {                         /* 그다음 압축 안 */
            if (!f.isFile() || !Archives.isArchiveName(f.getName()) || f.length() > 256L * 1024 * 1024) continue;
            final java.io.File[] got = new java.io.File[1];
            Archives.forEachRom(f, new Archives.Visitor() {
                @Override public boolean rom(String e, byte[] data) {
                    Games.Game g = Games.identifyBytes(data);
                    if (g == null || !"ss1".equals(g.id)) return true;
                    got[0] = save(roms, Archives.baseName(e), data);
                    return got[0] == null;
                }
            });
            if (got[0] != null) return got[0];
        }
        if (depth > 0)
            for (java.io.File f : fs) {
                if (!f.isDirectory() || f.getName().startsWith(".")) continue;
                java.io.File r = findIn(f, roms, depth - 1);
                if (r != null) return r;
            }
        return null;
    }

    private static java.io.File save(java.io.File roms, String name, byte[] data) {
        roms.mkdirs();
        java.io.File out = new java.io.File(roms, name);
        if (out.exists()) {
            if (out.length() == data.length && isSs1(out)) return out;
            out = new java.io.File(roms, "SS1 " + System.currentTimeMillis() + ".ngp");
        }
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(out)) { o.write(data); }
        catch (Exception x) { return null; }
        return out;
    }
}
