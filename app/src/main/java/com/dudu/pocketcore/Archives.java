package com.dudu.pocketcore;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;

/**
 * 압축 파일(.zip · .7z) 안의 롬을 꺼낸다 — 롬 스캔·파일 골라 가져오기·SS1 음악이 쓴다.
 * 유저 2026-10-10 「ss1 롬을 롬스캔에서 안 끌어오는 것 같아」: 받아 둔 롬이 7z 째라 스캔(.ngc/.ngp 만 봄)에 안 걸렸다.
 *
 * 7z 은 라이브러리 없이 직접 읽는다(앱에 의존성 0 유지). 지원: 코더 하나짜리 폴더의 LZMA · LZMA2 · 무압축,
 * 묶음(solid) 압축, 머리 압축(encoded header). 그 밖(BCJ·암호·PPMd 등)은 조용히 건너뛴다 — 롬 압축엔 안 쓰인다.
 * zip 은 java.util.zip(저장·deflate).
 */
public final class Archives {

    /** 롬 하나 크기 상한 — 네오지오 포켓 롬은 최대 4MB. */
    public static final int MAX_ROM = 8 * 1024 * 1024;
    /** 압축 하나에서 풀어 볼 총량 상한(묶음 압축 롬 모음이 커도 폰이 오래 붙잡히지 않게). */
    private static final long MAX_TOTAL = 256L * 1024 * 1024;

    public static boolean isRomName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".ngc") || n.endsWith(".ngp") || n.endsWith(".npc");
    }

    public static boolean isArchiveName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".7z");
    }

    /** 롬 하나를 받을 때마다 불린다. false 를 돌려주면 그만 푼다. */
    public interface Visitor { boolean rom(String entry, byte[] data); }

    /** 압축 안의 롬(.ngc/.ngp/.npc, 8MB 이하)을 차례로 풀어 visitor 에 넘긴다. 못 읽는 압축이면 조용히 끝. */
    public static void forEachRom(File archive, Visitor v) {
        try {
            String n = archive.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".zip")) zip(archive, v);
            else if (n.endsWith(".7z")) new SevenZ(archive).run(v);
        } catch (Throwable ignored) { }
    }

    /** 압축 안의 롬 하나(entry 이름)를 꺼낸다. 없으면 null. */
    public static byte[] extract(File archive, final String entry) {
        final byte[][] out = new byte[1][];
        forEachRom(archive, new Visitor() {
            @Override public boolean rom(String e, byte[] data) {
                if (e.equals(entry)) { out[0] = data; return false; }
                return true;
            }
        });
        return out[0];
    }

    /** 경로를 뗀 파일 이름. */
    public static String baseName(String entry) {
        String s = entry.replace('\\', '/');
        return s.substring(s.lastIndexOf('/') + 1);
    }

    /* ── zip ─────────────────────────────────────────────────────── */

    private static void zip(File f, Visitor v) throws IOException {
        /* 중앙 목록만 읽고 롬 항목만 연다 — 사진 묶음 같은 큰 zip 도 금방 지나간다.
           이름이 UTF-8 이 아니면(윈도 한글 zip = CP949) 그 인코딩으로 다시 연다. */
        java.util.zip.ZipFile z = null;
        try {
            z = new java.util.zip.ZipFile(f, java.nio.charset.StandardCharsets.UTF_8);
            /* 안드로이드는 이름이 깨졌으면 항목을 꺼낼 때 터진다 — 먼저 이름만 한 바퀴 훑어 본다 */
            java.util.Enumeration<? extends ZipEntry> en0 = z.entries();
            while (en0.hasMoreElements()) en0.nextElement().getName();
        } catch (IllegalArgumentException | java.util.zip.ZipException e) {
            if (z != null) try { z.close(); } catch (IOException ignored) { }
            java.nio.charset.Charset cs;
            try { cs = java.nio.charset.Charset.forName("MS949"); }
            catch (Exception x) { cs = java.nio.charset.StandardCharsets.ISO_8859_1; }
            z = new java.util.zip.ZipFile(f, cs);
        }
        long total = 0;
        try {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            byte[] buf = new byte[65536];
            while (en.hasMoreElements()) {
                ZipEntry e;
                try { e = en.nextElement(); } catch (IllegalArgumentException bad) { continue; }
                if (e.isDirectory() || !isRomName(e.getName())) continue;
                if (e.getSize() > MAX_ROM) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream(e.getSize() > 0 ? (int) e.getSize() : 1 << 20);
                boolean big = false;
                try (InputStream in = z.getInputStream(e)) {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        bo.write(buf, 0, r);
                        if (bo.size() > MAX_ROM) { big = true; break; }
                    }
                }
                total += bo.size();
                if (!big && !v.rom(e.getName(), bo.toByteArray())) return;
                if (total > MAX_TOTAL) return;
            }
        } finally {
            z.close();
        }
    }

    /* ── 7z ──────────────────────────────────────────────────────── */

    private static final class Folder {
        byte[] method, props;
        boolean ok;                    /* 코더 하나 + 아는 방식 */
        boolean crc;                   /* 폴더 CRC 가 UnpackInfo 에 있음 */
        long unpackSize;
        int numSub = 1;
        long[] subSizes;
    }

    private static final class SevenZ {
        final File file;
        long packPos;
        long[] packSizes = new long[0];
        Folder[] folders = new Folder[0];
        String[] names = new String[0];
        boolean[] emptyStream = new boolean[0];

        SevenZ(File f) { this.file = f; }

        void run(Visitor v) throws IOException {
            byte[] sig = new byte[32];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.readFully(sig);
                if ((sig[0] & 0xFF) != '7' || (sig[1] & 0xFF) != 'z' || (sig[2] & 0xFF) != 0xBC || (sig[3] & 0xFF) != 0xAF
                        || (sig[4] & 0xFF) != 0x27 || (sig[5] & 0xFF) != 0x1C) return;
                long off = le64(sig, 12), size = le64(sig, 20);
                if (size <= 0 || size > 64L * 1024 * 1024 || 32 + off + size > raf.length()) return;
                byte[] h = new byte[(int) size];
                raf.seek(32 + off);
                raf.readFully(h);
                Buf b = new Buf(h);
                int id = b.u8();
                if (id == 0x17) {                                   /* 머리가 압축돼 있다 — 풀어서 다시 */
                    readStreamsInfo(b);
                    if (folders.length < 1 || !folders[0].ok || folders[0].unpackSize > 64L * 1024 * 1024) return;
                    byte[] hh = decodeWhole(raf, 0);
                    packPos = 0; packSizes = new long[0]; folders = new Folder[0];
                    b = new Buf(hh);
                    id = b.u8();
                }
                if (id != 0x01) return;
                readHeader(b);
                emit(raf, v);
            }
        }

        private void readHeader(Buf b) throws IOException {
            int id = b.u8();
            if (id == 0x02) {                                       /* 압축 속성 — 건너뜀 */
                for (;;) { int t = b.u8(); if (t == 0) break; b.skip(b.num()); }
                id = b.u8();
            }
            if (id == 0x03) { new SevenZ(file).readStreamsInfo(b); id = b.u8(); }   /* 덧 스트림 — 안 씀 */
            if (id == 0x04) { readStreamsInfo(b); id = b.u8(); }
            if (id == 0x05) { readFilesInfo(b); id = b.u8(); }
        }

        private void readStreamsInfo(Buf b) throws IOException {
            int id = b.u8();
            if (id == 0x06) {
                packPos = b.num();
                int n = (int) b.num();
                packSizes = new long[n];
                id = b.u8();
                if (id == 0x09) { for (int i = 0; i < n; i++) packSizes[i] = b.num(); id = b.u8(); }
                if (id == 0x0A) { readDigests(b, n); id = b.u8(); }
                if (id != 0) throw new IOException("7z pack end");
                id = b.u8();
            }
            if (id == 0x07) {
                if (b.u8() != 0x0B) throw new IOException("7z folder");
                int n = (int) b.num();
                if (b.u8() != 0) throw new IOException("7z external");
                folders = new Folder[n];
                int[] outs = new int[n];
                for (int i = 0; i < n; i++) { folders[i] = new Folder(); outs[i] = readFolder(b, folders[i]); }
                if (b.u8() != 0x0C) throw new IOException("7z sizes");
                for (int i = 0; i < n; i++) {
                    long last = 0;
                    for (int k = 0; k < outs[i]; k++) last = b.num();
                    folders[i].unpackSize = last;                   /* 코더 하나일 때만 쓰므로 마지막 = 유일 */
                    folders[i].subSizes = new long[]{ last };
                }
                id = b.u8();
                if (id == 0x0A) {
                    boolean[] d = readDigests(b, n);
                    for (int i = 0; i < n; i++) folders[i].crc = d[i];
                    id = b.u8();
                }
                if (id != 0) throw new IOException("7z unpack end");
                id = b.u8();
            }
            if (id == 0x08) {
                id = b.u8();
                if (id == 0x0D) {
                    for (Folder f : folders) f.numSub = (int) b.num();
                    id = b.u8();
                }
                for (Folder f : folders) {
                    long[] s = new long[f.numSub];
                    long sum = 0;
                    if (id == 0x09) for (int k = 0; k < f.numSub - 1; k++) { s[k] = b.num(); sum += s[k]; }
                    if (f.numSub > 0) s[f.numSub - 1] = f.unpackSize - sum;
                    f.subSizes = s;
                }
                if (id == 0x09) id = b.u8();
                if (id == 0x0A) {
                    int nd = 0;                                     /* 폴더 CRC 로 이미 아는 «한 파일 폴더»는 빠진다 */
                    for (Folder f : folders) if (!(f.numSub == 1 && f.crc)) nd += f.numSub;
                    readDigests(b, nd);
                    id = b.u8();
                }
                if (id != 0) throw new IOException("7z substreams end");
                id = b.u8();
            }
            if (id != 0) throw new IOException("7z streams end");
        }

        /** 폴더 하나를 읽고 출력 스트림 수를 돌려준다. */
        private int readFolder(Buf b, Folder f) throws IOException {
            int nc = (int) b.num(), tin = 0, tout = 0;
            for (int c = 0; c < nc; c++) {
                int flag = b.u8();
                byte[] m = b.bytes(flag & 0x0F);
                int ni = 1, no = 1;
                if ((flag & 0x10) != 0) { ni = (int) b.num(); no = (int) b.num(); }
                byte[] p = null;
                if ((flag & 0x20) != 0) p = b.bytes((int) b.num());
                if ((flag & 0x80) != 0) throw new IOException("7z alt");
                tin += ni; tout += no;
                if (c == 0) { f.method = m; f.props = p; }
            }
            for (int k = 0; k < tout - 1; k++) { b.num(); b.num(); }
            int npk = tin - (tout - 1);
            if (npk > 1) for (int k = 0; k < npk; k++) b.num();
            f.ok = nc == 1 && tin == 1 && tout == 1 && f.method != null
                    && (isMethod(f.method, 0x03, 0x01, 0x01) || isMethod(f.method, 0x21) || isMethod(f.method, 0x00));
            return tout;
        }

        private void readFilesInfo(Buf b) throws IOException {
            int n = (int) b.num();
            names = new String[n];
            emptyStream = new boolean[n];
            for (;;) {
                int t = b.u8();
                if (t == 0) break;
                long size = b.num();
                int end = b.pos + (int) size;
                if (t == 0x0E) {
                    emptyStream = bits(b, n);
                } else if (t == 0x11) {
                    if (b.u8() == 0) {
                        for (int i = 0; i < n; i++) {
                            StringBuilder sb = new StringBuilder();
                            for (;;) {
                                int c = b.u8() | (b.u8() << 8);
                                if (c == 0) break;
                                sb.append((char) c);
                            }
                            names[i] = sb.toString();
                        }
                    }
                }
                b.pos = end;
            }
        }

        /** 파일 ↔ 스트림을 맞춰 롬만 풀어 넘긴다. 롬이 든 폴더만 푼다. */
        private void emit(RandomAccessFile raf, Visitor v) throws IOException {
            int fi = 0;
            long total = 0;
            for (int k = 0; k < folders.length; k++) {
                Folder f = folders[k];
                String[] fn = new String[f.numSub];
                boolean want = false;
                for (int s = 0; s < f.numSub; s++) {
                    while (fi < names.length && emptyStream.length > fi && emptyStream[fi]) fi++;
                    fn[s] = fi < names.length ? names[fi] : null;
                    fi++;
                    if (fn[s] != null && isRomName(fn[s]) && f.subSizes[s] <= MAX_ROM) want = true;
                }
                if (!want || !f.ok) continue;
                if (total + f.unpackSize > MAX_TOTAL) return;
                total += f.unpackSize;
                final String[] names2 = fn;
                final Folder ff = f;
                final Visitor vv = v;
                final boolean[] stop = new boolean[1];
                Sink sink = new Sink() {
                    int idx = 0; long left = ff.numSub > 0 ? ff.subSizes[0] : 0;
                    ByteArrayOutputStream cur = start(0);
                    ByteArrayOutputStream start(int i) {
                        return (i < names2.length && names2[i] != null && isRomName(names2[i]) && ff.subSizes[i] <= MAX_ROM)
                                ? new ByteArrayOutputStream((int) ff.subSizes[i]) : null;
                    }
                    @Override public void write(byte[] buf, int off, int len) throws IOException {
                        while (len > 0 && idx < ff.numSub) {
                            int n = (int) Math.min(len, left);
                            if (cur != null) cur.write(buf, off, n);
                            off += n; len -= n; left -= n;
                            while (left == 0 && idx < ff.numSub) {
                                if (cur != null && !vv.rom(names2[idx], cur.toByteArray())) { stop[0] = true; throw new StopException(); }
                                idx++;
                                if (idx < ff.numSub) { left = ff.subSizes[idx]; cur = start(idx); }
                            }
                        }
                    }
                };
                try { decode(raf, k, sink); }
                catch (StopException e) { return; }
                if (stop[0]) return;
            }
        }

        private byte[] decodeWhole(RandomAccessFile raf, int k) throws IOException {
            final ByteArrayOutputStream bo = new ByteArrayOutputStream((int) folders[k].unpackSize);
            decode(raf, k, new Sink() {
                @Override public void write(byte[] buf, int off, int len) { bo.write(buf, off, len); }
            });
            return bo.toByteArray();
        }

        /** 폴더 k 를 풀어 sink 로. 폴더마다 압축 스트림 하나(코더 하나)라 k 번째 압축 스트림이 곧 그 폴더. */
        private void decode(RandomAccessFile raf, int k, Sink sink) throws IOException {
            Folder f = folders[k];
            long off = 32 + packPos;
            for (int i = 0; i < k; i++) off += packSizes[i];
            InputStream in = new BufferedInputStream(new RafStream(raf, off, packSizes[k]), 65536);
            if (isMethod(f.method, 0x00)) {
                byte[] buf = new byte[65536];
                long left = f.unpackSize;
                while (left > 0) {
                    int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r < 0) throw new EOFException();
                    sink.write(buf, 0, r);
                    left -= r;
                }
            } else if (isMethod(f.method, 0x03, 0x01, 0x01)) {
                byte[] p = f.props;
                if (p == null || p.length < 5) throw new IOException("lzma props");
                long dict = (p[1] & 0xFFL) | (p[2] & 0xFFL) << 8 | (p[3] & 0xFFL) << 16 | (p[4] & 0xFFL) << 24;
                Lzma d = new Lzma(window(dict, f.unpackSize), sink);
                d.setProps(p[0] & 0xFF);
                d.reset();
                d.chunk(in, f.unpackSize, true);
                d.flush();
            } else {                                                /* LZMA2 */
                int bits = f.props != null && f.props.length > 0 ? f.props[0] & 0x3F : 40;
                long dict = bits >= 40 ? 0xFFFFFFFFL : (2L | (bits & 1)) << (bits / 2 + 11);
                Lzma d = new Lzma(window(dict, f.unpackSize), sink);
                lzma2(in, d);
                d.flush();
            }
        }

        private static int window(long dict, long unpack) {
            long w = Math.min(Math.max(dict, 4096), Math.max(unpack, 4096));
            return (int) Math.min(w, 64L * 1024 * 1024);
        }

        private static void lzma2(InputStream in, Lzma d) throws IOException {
            boolean needProps = true;
            for (;;) {
                int c = rd(in);
                if (c == 0) return;
                if (c == 1 || c == 2) {                             /* 무압축 조각 */
                    int n = ((rd(in) << 8) | rd(in)) + 1;
                    for (int i = 0; i < n; i++) d.putRaw(rd(in));
                    continue;
                }
                if (c < 0x80) throw new IOException("lzma2 ctl");
                long un = ((long) (c & 0x1F) << 16) + ((rd(in) << 8) | rd(in)) + 1;
                rd(in); rd(in);                                     /* 압축 크기 — 레인지 디코더가 알아서 읽는다 */
                int mode = (c >> 5) & 3;
                if (mode >= 2) { d.setProps(rd(in)); needProps = false; }
                else if (needProps) throw new IOException("lzma2 props");
                if (mode >= 1) d.reset();
                d.chunk(in, un, false);
            }
        }

        private static boolean isMethod(byte[] m, int... id) {
            if (m.length != id.length) return false;
            for (int i = 0; i < id.length; i++) if ((m[i] & 0xFF) != id[i]) return false;
            return true;
        }

        private static boolean[] readDigests(Buf b, int n) throws IOException {
            boolean[] def;
            if (b.u8() != 0) { def = new boolean[n]; java.util.Arrays.fill(def, true); }
            else def = bits(b, n);
            for (boolean x : def) if (x) b.skip(4);
            return def;
        }

        private static boolean[] bits(Buf b, int n) throws IOException {
            boolean[] r = new boolean[n];
            int mask = 0, cur = 0;
            for (int i = 0; i < n; i++) {
                if (mask == 0) { cur = b.u8(); mask = 0x80; }
                r[i] = (cur & mask) != 0;
                mask >>= 1;
            }
            return r;
        }
    }

    private static final class StopException extends IOException { }

    private static long le64(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xFF);
        return v;
    }

    private static int rd(InputStream in) throws IOException {
        int c = in.read();
        if (c < 0) throw new EOFException();
        return c;
    }

    private static final class Buf {
        final byte[] a; int pos;
        Buf(byte[] a) { this.a = a; }
        int u8() throws IOException { if (pos >= a.length) throw new EOFException(); return a[pos++] & 0xFF; }
        byte[] bytes(int n) throws IOException {
            if (n < 0 || pos + n > a.length) throw new EOFException();
            byte[] r = new byte[n]; System.arraycopy(a, pos, r, 0, n); pos += n; return r;
        }
        void skip(long n) throws IOException { if (n < 0 || pos + n > a.length) throw new EOFException(); pos += (int) n; }
        /** 7z 가변 길이 정수 */
        long num() throws IOException {
            int first = u8(), mask = 0x80;
            long v = 0;
            for (int i = 0; i < 8; i++) {
                if ((first & mask) == 0) return v | ((long) (first & (mask - 1)) << (8 * i));
                v |= (long) u8() << (8 * i);
                mask >>>= 1;
            }
            return v;
        }
    }

    private static final class RafStream extends InputStream {
        final RandomAccessFile raf; long pos, left;
        RafStream(RandomAccessFile r, long off, long len) { raf = r; pos = off; left = len; }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            raf.seek(pos);
            int r = raf.read(b, off, (int) Math.min(len, left));
            if (r > 0) { pos += r; left -= r; }
            return r;
        }
    }

    interface Sink { void write(byte[] buf, int off, int len) throws IOException; }

    /**
     * LZMA 풀기(LZMA SDK 의 공개 명세 LzmaSpec 을 그대로 옮김). 창(사전)을 돌려 쓰며 나온 바이트를 sink 로 흘린다.
     * LZMA2 는 같은 상태를 이어 쓰며 조각마다 레인지 디코더만 새로 연다(chunk).
     */
    private static final class Lzma {
        private final byte[] win;
        private int wpos, flushed;
        private boolean full;
        private long total;
        private final Sink sink;

        private int lc, lp, pb;
        private short[] lit;
        private final short[] isMatch = new short[192], isRep = new short[12], isRepG0 = new short[12],
                isRepG1 = new short[12], isRepG2 = new short[12], isRep0Long = new short[192],
                posSlot = new short[4 * 64], posDec = new short[115], align = new short[16];
        private final Len len = new Len(), repLen = new Len();
        private int state, rep0, rep1, rep2, rep3;

        private InputStream in;
        private int range, code;

        Lzma(int windowSize, Sink sink) { this.win = new byte[windowSize]; this.sink = sink; }

        void setProps(int d) throws IOException {
            if (d >= 9 * 5 * 5) throw new IOException("lzma props");
            lc = d % 9; d /= 9; lp = d % 5; pb = d / 5;
            lit = new short[0x300 << (lc + lp)];
        }

        void reset() {
            java.util.Arrays.fill(lit, (short) 1024);
            for (short[] p : new short[][]{ isMatch, isRep, isRepG0, isRepG1, isRepG2, isRep0Long, posSlot, posDec, align })
                java.util.Arrays.fill(p, (short) 1024);
            len.reset(); repLen.reset();
            state = 0; rep0 = rep1 = rep2 = rep3 = 0;
        }

        /* ── 창 ── */
        private void put(int b) throws IOException {
            win[wpos++] = (byte) b;
            total++;
            if (wpos == win.length) { flush(); wpos = 0; flushed = 0; full = true; }
        }
        void putRaw(int b) throws IOException { put(b); }
        private int get(int dist) {                                 /* dist = 1 이면 바로 앞 바이트 */
            int i = wpos - dist;
            if (i < 0) i += win.length;
            return win[i] & 0xFF;
        }
        void flush() throws IOException {
            if (wpos > flushed) sink.write(win, flushed, wpos - flushed);
            flushed = wpos;
        }

        /* ── 레인지 디코더 ── */
        private void rcInit(InputStream s) throws IOException {
            in = s;
            if (rd(in) != 0) throw new IOException("lzma rc");
            range = 0xFFFFFFFF; code = 0;
            for (int i = 0; i < 4; i++) code = (code << 8) | rd(in);
        }
        private int bit(short[] p, int i) throws IOException {
            int v = p[i];
            int bound = (range >>> 11) * v;
            int r;
            if (Integer.compareUnsigned(code, bound) < 0) { v += (2048 - v) >>> 5; range = bound; r = 0; }
            else { v -= v >>> 5; code -= bound; range -= bound; r = 1; }
            p[i] = (short) v;
            if ((range & 0xFF000000) == 0) { range <<= 8; code = (code << 8) | rd(in); }
            return r;
        }
        private int direct(int n) throws IOException {
            int res = 0;
            do {
                range >>>= 1;
                code -= range;
                int t = 0 - (code >>> 31);
                code += range & t;
                if ((range & 0xFF000000) == 0) { range <<= 8; code = (code << 8) | rd(in); }
                res = (res << 1) + (t + 1);
            } while (--n > 0);
            return res;
        }
        private int tree(short[] p, int base, int n) throws IOException {
            int m = 1;
            for (int i = 0; i < n; i++) m = (m << 1) + bit(p, base + m);
            return m - (1 << n);
        }
        private int rtree(short[] p, int base, int n) throws IOException {
            int m = 1, s = 0;
            for (int i = 0; i < n; i++) { int b = bit(p, base + m); m = (m << 1) + b; s |= b << i; }
            return s;
        }

        private final class Len {
            final short[] ch = new short[2], low = new short[16 * 8], mid = new short[16 * 8], high = new short[256];
            void reset() { for (short[] p : new short[][]{ ch, low, mid, high }) java.util.Arrays.fill(p, (short) 1024); }
            int dec(int ps) throws IOException {
                if (bit(ch, 0) == 0) return tree(low, ps * 8, 3);
                if (bit(ch, 1) == 0) return 8 + tree(mid, ps * 8, 3);
                return 16 + tree(high, 0, 8);
            }
        }

        private boolean empty() { return !full && wpos == 0 && total == 0; }

        /** n 바이트를 푼다. lzma1 이면 끝 표시(거리 0xFFFFFFFF)를 만나도 끝. */
        void chunk(InputStream s, long n, boolean lzma1) throws IOException {
            rcInit(s);
            long left = n;
            int pbMask = (1 << pb) - 1, lpMask = (1 << lp) - 1;
            while (left > 0) {
                int ps = (int) total & pbMask;
                if (bit(isMatch, (state << 4) + ps) == 0) {
                    int prev = empty() ? 0 : get(1);
                    int base = 0x300 * ((((int) total & lpMask) << lc) + (prev >>> (8 - lc)));
                    int sym = 1;
                    if (state >= 7) {
                        int mb = get(rep0 + 1);
                        do {
                            int m = (mb >>> 7) & 1;
                            mb <<= 1;
                            int b = bit(lit, base + ((1 + m) << 8) + sym);
                            sym = (sym << 1) | b;
                            if (m != b) break;
                        } while (sym < 0x100);
                    }
                    while (sym < 0x100) sym = (sym << 1) | bit(lit, base + sym);
                    put(sym - 0x100);
                    state = state < 4 ? 0 : state < 10 ? state - 3 : state - 6;
                    left--;
                    continue;
                }
                int l;
                if (bit(isRep, state) != 0) {
                    if (empty()) throw new IOException("lzma rep");
                    if (bit(isRepG0, state) == 0) {
                        if (bit(isRep0Long, (state << 4) + ps) == 0) {
                            state = state < 7 ? 9 : 11;
                            put(get(rep0 + 1));
                            left--;
                            continue;
                        }
                    } else {
                        int dist;
                        if (bit(isRepG1, state) == 0) dist = rep1;
                        else {
                            if (bit(isRepG2, state) == 0) dist = rep2;
                            else { dist = rep3; rep3 = rep2; }
                            rep2 = rep1;
                        }
                        rep1 = rep0; rep0 = dist;
                    }
                    l = repLen.dec(ps);
                    state = state < 7 ? 8 : 11;
                } else {
                    rep3 = rep2; rep2 = rep1; rep1 = rep0;
                    l = len.dec(ps);
                    state = state < 7 ? 7 : 10;
                    int ls = l < 3 ? l : 3;
                    int slot = tree(posSlot, ls * 64, 6);
                    int dist;
                    if (slot < 4) dist = slot;
                    else {
                        int nd = (slot >>> 1) - 1;
                        dist = (2 | (slot & 1)) << nd;
                        if (slot < 14) dist += rtree(posDec, dist - slot, nd);
                        else { dist += direct(nd - 4) << 4; dist += rtree(align, 0, 4); }
                    }
                    rep0 = dist;
                    if (rep0 == 0xFFFFFFFF) { if (lzma1) return; throw new IOException("lzma marker"); }
                    if (Integer.compareUnsigned(rep0, win.length) >= 0 || (!full && rep0 >= wpos))
                        throw new IOException("lzma dist");
                }
                l += 2;
                while (l-- > 0 && left > 0) { put(get(rep0 + 1)); left--; }
            }
        }
    }

    private Archives() { }
}
