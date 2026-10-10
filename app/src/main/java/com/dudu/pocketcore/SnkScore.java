package com.dudu.pocketcore;

import java.util.ArrayList;
import java.util.List;

/**
 * 사무쇼 1·2 (같은 Z80 소리 드라이버)의 곡 악보 — 비트 스트림 해석기. 2026-10-10 드라이버를 뜯어 알아낸 문법.
 *
 * 곡 항목(레코드 머리 3바이트 뒤): [낱말: 하위 14비트 길이 · 상위 2비트 트랙 수−1] [트랙마다 낱말: 상위 2비트 종류 · 하위 14비트 상대 위치]
 * 트랙 시작 = (그 트랙 낱말 자리 + 2) + 상대 위치. 비트는 바이트마다 위(MSB)부터 읽는다(드라이버 0x048F).
 *
 * 사건 하나 = C(1비트) + 길이(캐시: 1=지난 값, 0 → 크기 1비트 → 4 또는 8비트)
 *   C=1 명령: cmd 4비트, 매개변수 비트 수 = {5,4,4,7,5,0,8,11,0}[cmd]
 *       0·2·3 = 칸 값, 1 = **음량(4비트 감쇠, 0 이 가장 큼)**, 4 = 되풀이 시작(횟수, 0=끝없이 — 자리 저장하고 비트 버퍼 비움),
 *       5 = 되풀이 끝, 6 = 빠르기, 7 = 부르기(11비트 상대), 8 = 돌아가기(비트 버퍼 비움), 15 = 트랙 끝
 *   C=0 음표: t 2비트(3 이 아니면: t&1==0 → 옥타브 3비트, t&2==0 → 음 4비트) · f 1비트(0 → 4비트) · 캐시 값 하나
 * 실제 드라이버가 2분 동안 읽은 자리와 이 해석기가 읽는 자리가 SS1 곡 12개에서 완전히 같음을 확인(세션 도구 validate.py).
 */
final class SnkScore {
    private static final int[] NB = { 5, 4, 4, 7, 5, 0, 8, 11, 0 };

    private SnkScore() { }

    private static final class Bits {
        final byte[] r; int pos; final int lim;
        Bits(byte[] r, int pos, int lim) { this.r = r; this.pos = pos; this.lim = lim; }
        int read(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                if (pos >= lim) throw new IndexOutOfBoundsException();
                v = (v << 1) | ((r[pos >> 3] >> (7 - (pos & 7))) & 1);
                pos++;
            }
            return v;
        }
        void align() { pos = (pos + 7) & ~7; }
    }

    private static void cached(Bits b) {
        if (b.read(1) == 0) { int sz = b.read(1); b.read(sz != 0 ? 8 : 4); }
    }

    /** 레코드(rec = 롬 안 자리, 머리 3바이트 포함)의 음량 명령 매개변수 4비트 자리들(롬 기준 비트 번호).
     *  문법에 안 맞는 곳을 만나면 null — 그 곡은 건드리지 않는다. */
    static int[] volumeBits(byte[] r, int rec) {
        int e = rec + 3;
        if (e + 2 > r.length) return null;
        int w = (r[e] & 0xFF) | ((r[e + 1] & 0xFF) << 8);
        int len = w & 0x3FFF, n = (w >>> 14) + 1;
        int end = e + len;
        if (end > r.length) return null;
        int[] starts = new int[n];
        for (int k = 0; k < n; k++) {
            int a = e + 2 + 2 * k;
            int v = (r[a] & 0xFF) | ((r[a + 1] & 0xFF) << 8);
            starts[k] = a + 2 + (v & 0x3FFF);
            if (starts[k] < e || starts[k] > end) return null;
        }
        List<Integer> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int tEnd = end;                                   /* 다음 트랙 시작(뒤쪽에서 가장 가까운) 까지 */
            for (int q = 0; q < n; q++) if (starts[q] > starts[k] && starts[q] < tEnd) tEnd = starts[q];
            Bits b = new Bits(r, starts[k] * 8, tEnd * 8);
            List<Integer> loops = new ArrayList<>();
            try {
                while (b.pos < tEnd * 8) {
                    int c = b.read(1);
                    cached(b);
                    if (c != 0) {
                        int cmd = b.read(4);
                        if (cmd == 15) { b.align(); continue; }
                        if (cmd > 8) return null;
                        int at = b.pos;
                        int p = b.read(NB[cmd]);
                        if (cmd == 1) out.add(at);
                        if (cmd == 4) { loops.add(p); b.align(); }
                        else if (cmd == 5) { int cnt = loops.isEmpty() ? 0 : loops.remove(loops.size() - 1); if (cnt == 0) b.align(); }
                        else if (cmd == 8) b.align();
                    } else {
                        int t = b.read(2);
                        if (t != 3) {
                            if ((t & 1) == 0) b.read(3);
                            if ((t & 2) == 0) b.read(4);
                        }
                        if (b.read(1) == 0) b.read(4);
                        cached(b);
                    }
                }
            } catch (IndexOutOfBoundsException ignored) {
                /* 트랙 끝의 남는 비트 — 정상 */
            }
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** 곡 레코드(rec)의 음량 명령을 모두 k 단계 크게(감쇠 −k, 0~15 로 자름). 해석이 안 되면 false(그대로). */
    static boolean shiftVolume(byte[] r, int rec, int k) {
        if (k == 0) return true;
        int[] vb = volumeBits(r, rec);
        if (vb == null) return false;
        for (int bit : vb) {
            int v = 0;
            for (int i = 0; i < 4; i++) v = (v << 1) | ((r[(bit + i) >> 3] >> (7 - ((bit + i) & 7))) & 1);
            v = Math.max(0, Math.min(15, v - k));
            for (int i = 0; i < 4; i++) {
                int pos = bit + i, sh = 7 - (pos & 7);
                int bv = (v >> (3 - i)) & 1;
                r[pos >> 3] = (byte) ((r[pos >> 3] & ~(1 << sh)) | (bv << sh));
            }
        }
        return true;
    }

    /** 소리 없는 곡 레코드 — 트랙 하나가 곧바로 끝난다(C=1, 길이 새 값 4비트 0, cmd 15).
     *  곡 번호는 원래 곡 것을 그대로 둔다(메인 CPU 가 곡 번호로 무엇을 가를 수 있어 장면마다 원래처럼).
     *  메인 CPU 의 조립 코드(0x3DF4CD 의 LDIR)는 곡 항목을 세 번에 나눠 Z80 램으로 옮긴다:
     *  ① 항목 앞 «악기칸 자리»만큼 ② 세트의 악기칸 k개 ③ 나머지(길이 − 자리 − k). LDIR 는 개수 0 이면 65,536 바이트를 옮기므로
     *  셋 다 1 이상이어야 한다 — 2026-10-10 시험에서 ③ 이 0 인 레코드가 Z80 램을 넘어 화면 칩 레지스터(0x8006 화면 주기)를
     *  덮어 한 프레임이 끝나지 않는 멈춤으로 확인. 그래서 자리 6 · 칸 1 · 끝에 남는 바이트 1. */
    static byte[] silentRecord(int songId) {
        /* 항목: [길이 낱말: 트랙 1개, 길이 8] [트랙 0: 종류 0, 상대 0 → 항목+4] [악보 0x81 0xE0] [악기칸 1] [남는 바이트 1] */
        byte[] e = { 8, 0, 0, 0, (byte) 0x81, (byte) 0xE0, 0, 0 };
        byte[] rec = new byte[3 + e.length];
        rec[0] = (byte) songId; rec[1] = 0; rec[2] = 6;       /* 악기칸 자리 = 항목 6번째 바이트(악보가 읽지 않는 곳) */
        System.arraycopy(e, 0, rec, 3, e.length);
        return rec;
    }
}
