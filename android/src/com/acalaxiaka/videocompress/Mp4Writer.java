package com.acalaxiaka.videocompress;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 纯 Java MP4 复用器（无 Android 依赖），支持 >4 GB 单文件：
 * mdat 使用 64 位 largesize，偏移超过 4 GB 时用 co64，时长超 32 位时用 version 1 的 mvhd/tkhd/mdhd/elst。
 * 支持 HEVC(hvc1/hvcC)、H.264(avc1/avcC)、AAC(mp4a/esds)；B 帧通过 ctts + elst 处理；旋转写入 tkhd 矩阵。
 * 样本直接流式写入 FileChannel（定位写入，需可 seek 的 rw 描述符），样本表以紧凑数组保存，moov 写在文件末尾。
 */
public final class Mp4Writer {
    public static final String HEVC = "video/hevc", AVC = "video/avc", AAC = "audio/mp4a-latm";

    static final class LongList { long[] a = new long[4096]; int n; void add(long v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; } }
    static final class IntList { int[] a = new int[4096]; int n; void add(int v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; } }

    static final class Track {
        boolean video; int nal; // 0=无 1=AVC 2=HEVC
        int width, height, timescale, sampleRate, channels, bitrate; byte[] csdBox; String fourcc;
        final IntList sizes = new IntList(), keys = new IntList(), chunkCount = new IntList();
        final LongList pts = new LongList(), chunkOff = new LongList();
    }

    private final FileChannel ch; private final boolean force64;
    private final List<Track> tracks = new ArrayList<>();
    private final ByteBuffer buf = ByteBuffer.allocate(1 << 20);
    private long pos, flushed, mdatStart; private int lastTrack = -1, rotation; private boolean started, finished;

    public Mp4Writer(FileChannel ch, boolean force64) { this.ch = ch; this.force64 = force64; }

    public void setRotation(int deg) { rotation = ((deg % 360) + 360) % 360; }

    public synchronized int addVideoTrack(String mime, int w, int h, byte[] csd0, byte[] csd1) {
        if (started) throw new IllegalStateException("already started");
        Track t = new Track(); t.video = true; t.width = w; t.height = h; t.timescale = 90000;
        List<byte[]> nals = new ArrayList<>(); splitNals(csd0, nals); if (csd1 != null) splitNals(csd1, nals);
        if (HEVC.equals(mime)) { t.nal = 2; t.fourcc = "hvc1"; t.csdBox = box("hvcC", hvcC(nals)); }
        else if (AVC.equals(mime)) { t.nal = 1; t.fourcc = "avc1"; t.csdBox = box("avcC", avcC(nals)); }
        else throw new IllegalArgumentException("不支持的视频编码：" + mime);
        tracks.add(t); return tracks.size() - 1;
    }

    public synchronized int addAudioTrack(int sampleRate, int channels, byte[] asc, int bitrate) {
        if (started) throw new IllegalStateException("already started");
        Track t = new Track(); t.video = false; t.sampleRate = sampleRate; t.channels = channels; t.timescale = sampleRate; t.bitrate = bitrate; t.fourcc = "mp4a";
        t.csdBox = full("esds", 0, 0, esds(asc, bitrate));
        tracks.add(t); return tracks.size() - 1;
    }

    public synchronized void start() throws IOException {
        D d = new D(); d.str("isom"); d.u32(0x200); d.str("isom"); d.str("iso2"); d.str("mp41");
        put(ByteBuffer.wrap(box("ftyp", d.bytes())));
        mdatStart = pos;
        D m = new D(); m.u32(1); m.str("mdat"); m.u64(0); put(ByteBuffer.wrap(m.bytes()));
        started = true;
    }

    /** data: position..limit 为一个样本；视频可为 Annex-B（自动转为 4 字节长度前缀并去掉带内参数集/AUD）。 */
    public synchronized void writeSample(int track, ByteBuffer data, long ptsUs, boolean key) throws IOException {
        if (!started || finished) throw new IllegalStateException("not started");
        Track t = tracks.get(track);
        long off = pos; int size;
        if (t.nal != 0 && isAnnexB(data)) size = writeAnnexB(t, data);
        else { size = data.remaining(); put(data.duplicate()); }
        if (size == 0) return;
        if (lastTrack != track) { t.chunkOff.add(off); t.chunkCount.add(0); lastTrack = track; }
        t.chunkCount.a[t.chunkCount.n - 1]++;
        t.sizes.add(size); t.pts.add(ptsUs); if (key) t.keys.add(t.sizes.n);
    }

    public synchronized long bytesWritten() { return pos; }

    public synchronized void finish() throws IOException {
        if (finished) return; finished = true;
        flush();
        ByteBuffer sz = ByteBuffer.allocate(8); sz.putLong(pos - mdatStart).flip();
        writeFully(sz, mdatStart + 8);
        byte[] moov = moov();
        writeFully(ByteBuffer.wrap(moov), pos); pos += moov.length; flushed = pos;
        try { ch.force(false); } catch (IOException ignored) {}
    }

    /** 由采样率/声道生成 AAC-LC AudioSpecificConfig。 */
    public static byte[] defaultAsc(int sr, int ch) {
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
        int idx = 4; for (int i = 0; i < rates.length; i++) if (rates[i] == sr) idx = i;
        int v = (2 << 11) | (idx << 7) | (ch << 3); return new byte[]{(byte) (v >> 8), (byte) v};
    }

    // ---------- 写入 ----------
    private void put(ByteBuffer src) throws IOException {
        while (src.hasRemaining()) {
            if (!buf.hasRemaining()) flush();
            int n = Math.min(buf.remaining(), src.remaining());
            ByteBuffer s = src.duplicate(); s.limit(s.position() + n); buf.put(s); src.position(src.position() + n); pos += n;
        }
    }
    private void flush() throws IOException { buf.flip(); writeFully(buf, flushed); flushed += buf.limit(); buf.clear(); }
    private void writeFully(ByteBuffer b, long at) throws IOException { while (b.hasRemaining()) at += ch.write(b, at); }

    static boolean isAnnexB(ByteBuffer b) {
        int p = b.position(); if (b.remaining() < 4) return false;
        return b.get(p) == 0 && b.get(p + 1) == 0 && (b.get(p + 2) == 1 || (b.get(p + 2) == 0 && b.get(p + 3) == 1));
    }

    private final ByteBuffer len4 = ByteBuffer.allocate(4);
    private int writeAnnexB(Track t, ByteBuffer b) throws IOException {
        int start = b.position(), end = b.limit(), total = 0;
        int i = start, nalStart = -1;
        while (i <= end) {
            int sc = (i + 3 <= end && b.get(i) == 0 && b.get(i + 1) == 0 && b.get(i + 2) == 1) ? 3 : 0;
            if (sc > 0 || i == end) {
                if (nalStart >= 0) {
                    int ne = i; if (i < end) while (ne > nalStart && b.get(ne - 1) == 0) ne--;
                    if (ne > nalStart && !skipNal(t.nal, b.get(nalStart))) {
                        len4.clear(); len4.putInt(ne - nalStart).flip(); put(len4);
                        ByteBuffer s = b.duplicate(); s.position(nalStart).limit(ne); put(s); total += 4 + ne - nalStart;
                    }
                }
                if (i == end) break;
                i += 3; nalStart = i; continue;
            }
            i++;
        }
        return total;
    }
    static boolean skipNal(int kind, byte h) {
        if (kind == 1) { int ty = h & 0x1f; return ty == 7 || ty == 8 || ty == 9 || ty == 12; }
        int ty = (h >> 1) & 0x3f; return ty == 32 || ty == 33 || ty == 34 || ty == 35;
    }

    static void splitNals(byte[] a, List<byte[]> out) {
        if (a == null) return;
        ByteBuffer b = ByteBuffer.wrap(a);
        if (!isAnnexB(b)) { out.add(a); return; }
        int i = 0, s = -1, n = a.length;
        while (i <= n) {
            boolean sc = i + 3 <= n && a[i] == 0 && a[i + 1] == 0 && a[i + 2] == 1;
            if (sc || i == n) {
                if (s >= 0) { int e = i; if (i < n) while (e > s && a[e - 1] == 0) e--; if (e > s) out.add(Arrays.copyOfRange(a, s, e)); }
                if (i == n) break; i += 3; s = i; continue;
            }
            i++;
        }
    }

    // ---------- 编码配置 ----------
    static byte[] rbsp(byte[] nal, int from) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); int z = 0;
        for (int i = from; i < nal.length; i++) {
            int v = nal[i] & 0xff;
            if (z >= 2 && v == 3) { z = 0; continue; }
            o.write(v); z = v == 0 ? z + 1 : 0;
        }
        return o.toByteArray();
    }
    static final class Bits {
        final byte[] d; int p; Bits(byte[] d) { this.d = d; }
        int bit() { int v = p / 8 < d.length ? (d[p / 8] >> (7 - p % 8)) & 1 : 0; p++; return v; }
        long bits(int n) { long v = 0; for (int i = 0; i < n; i++) v = (v << 1) | bit(); return v; }
        int ue() { int z = 0; while (bit() == 0 && z < 32) z++; return (int) ((1L << z) - 1 + bits(z)); }
    }

    static byte[] avcC(List<byte[]> nals) {
        List<byte[]> sps = new ArrayList<>(), pps = new ArrayList<>();
        for (byte[] n : nals) { int ty = n[0] & 0x1f; if (ty == 7) sps.add(n); else if (ty == 8) pps.add(n); }
        if (sps.isEmpty() || pps.isEmpty()) throw new IllegalArgumentException("缺少 SPS/PPS");
        byte[] s = sps.get(0); D d = new D();
        d.u8(1); d.u8(s[1] & 0xff); d.u8(s[2] & 0xff); d.u8(s[3] & 0xff); d.u8(0xFF);
        d.u8(0xE0 | sps.size()); for (byte[] x : sps) { d.u16(x.length); d.raw(x); }
        d.u8(pps.size()); for (byte[] x : pps) { d.u16(x.length); d.raw(x); }
        int prof = s[1] & 0xff;
        if (prof == 100 || prof == 110 || prof == 122 || prof == 144) {
            int chroma = 1, bdl = 0, bdc = 0;
            try { Bits b = new Bits(rbsp(s, 4)); b.ue(); chroma = b.ue(); if (chroma == 3) b.bit(); bdl = b.ue(); bdc = b.ue(); } catch (Exception ignored) {}
            d.u8(0xFC | (chroma & 3)); d.u8(0xF8 | (bdl & 7)); d.u8(0xF8 | (bdc & 7)); d.u8(0);
        }
        return d.bytes();
    }

    static byte[] hvcC(List<byte[]> nals) {
        List<byte[]> vps = new ArrayList<>(), sps = new ArrayList<>(), pps = new ArrayList<>();
        for (byte[] n : nals) { int ty = (n[0] >> 1) & 0x3f; if (ty == 32) vps.add(n); else if (ty == 33) sps.add(n); else if (ty == 34) pps.add(n); }
        if (sps.isEmpty() || pps.isEmpty()) throw new IllegalArgumentException("缺少 HEVC SPS/PPS");
        byte[] r = rbsp(sps.get(0), 2);
        Bits b = new Bits(r);
        b.bits(4); int maxSub = (int) b.bits(3); int nested = b.bit();
        byte[] ptl = Arrays.copyOfRange(r, 1, 13); // general_profile_space..general_level_idc（12 字节，字节对齐）
        b.p = 8 + 96;
        int[] subP = new int[8], subL = new int[8];
        for (int i = 0; i < maxSub; i++) { subP[i] = b.bit(); subL[i] = b.bit(); }
        if (maxSub > 0) for (int i = maxSub; i < 8; i++) b.bits(2);
        for (int i = 0; i < maxSub; i++) { if (subP[i] == 1) b.bits(88); if (subL[i] == 1) b.bits(8); }
        b.ue(); int chroma = b.ue(); if (chroma == 3) b.bit();
        b.ue(); b.ue(); if (b.bit() == 1) { b.ue(); b.ue(); b.ue(); b.ue(); }
        int bdl = b.ue(), bdc = b.ue();
        D d = new D();
        d.u8(1); d.raw(ptl, 0, 1); d.raw(ptl, 1, 4); d.raw(ptl, 5, 6); d.u8(ptl[11] & 0xff);
        d.u16(0xF000); d.u8(0xFC); d.u8(0xFC | (chroma & 3)); d.u8(0xF8 | (bdl & 7)); d.u8(0xF8 | (bdc & 7));
        d.u16(0); d.u8(((maxSub + 1) & 7) << 3 | (nested & 1) << 2 | 3);
        int arrays = (vps.isEmpty() ? 0 : 1) + 2; d.u8(arrays);
        Object[][] groups = {{32, vps}, {33, sps}, {34, pps}};
        for (Object[] g : groups) {
            @SuppressWarnings("unchecked") List<byte[]> l = (List<byte[]>) g[1]; if (l.isEmpty()) continue;
            d.u8(0x80 | (Integer) g[0]); d.u16(l.size()); for (byte[] x : l) { d.u16(x.length); d.raw(x); }
        }
        return d.bytes();
    }

    static byte[] esds(byte[] asc, int bitrate) {
        D dsi = new D(); dsi.raw(asc);
        D dcd = new D(); dcd.u8(0x40); dcd.u8(0x15); dcd.u24(0); dcd.u32(bitrate); dcd.u32(bitrate); desc(dcd, 5, dsi.bytes());
        D es = new D(); es.u16(0); es.u8(0); desc(es, 4, dcd.bytes()); desc(es, 6, new byte[]{2});
        D out = new D(); desc(out, 3, es.bytes()); return out.bytes();
    }
    static void desc(D d, int tag, byte[] body) {
        d.u8(tag); int n = body.length; d.u8(0x80 | ((n >> 21) & 0x7f)); d.u8(0x80 | ((n >> 14) & 0x7f)); d.u8(0x80 | ((n >> 7) & 0x7f)); d.u8(n & 0x7f); d.raw(body);
    }

    // ---------- moov ----------
    static final int MOVIE_TS = 1000;

    private byte[] moov() {
        long globalStart = Long.MAX_VALUE;
        for (Track t : tracks) if (t.pts.n > 0) globalStart = Math.min(globalStart, minOf(t.pts));
        if (globalStart == Long.MAX_VALUE) globalStart = 0;
        List<byte[]> traks = new ArrayList<>(); long movieDur = 0;
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i); if (t.sizes.n == 0) continue;
            long[] res = new long[1];
            traks.add(trak(t, i + 1, globalStart, res)); movieDur = Math.max(movieDur, res[0]);
        }
        boolean v1 = movieDur > 0xFFFFFFFFL;
        D mv = new D(); if (v1) { mv.u64(0); mv.u64(0); mv.u32(MOVIE_TS); mv.u64(movieDur); } else { mv.u32(0); mv.u32(0); mv.u32(MOVIE_TS); mv.u32(movieDur); }
        mv.u32(0x00010000); mv.u16(0x0100); mv.zeros(10); matrix(mv, 0, 0, 0); mv.zeros(24); mv.u32(tracks.size() + 1);
        List<byte[]> parts = new ArrayList<>(); parts.add(full("mvhd", v1 ? 1 : 0, 0, mv.bytes())); parts.addAll(traks);
        return box("moov", parts.toArray(new byte[0][]));
    }

    static long minOf(LongList l) { long m = Long.MAX_VALUE; for (int i = 0; i < l.n; i++) m = Math.min(m, l.a[i]); return m; }

    private byte[] trak(Track t, int id, long globalStartUs, long[] outMovieDur) {
        int n = t.sizes.n; long ts = t.timescale;
        long[] tick = new long[n]; for (int i = 0; i < n; i++) tick[i] = Math.round((t.pts.a[i] - globalStartUs) * (double) ts / 1e6);
        long[] sorted = tick.clone(); Arrays.sort(sorted);
        long minC = 0; for (int i = 0; i < n; i++) minC = Math.min(minC, tick[i] - sorted[i]);
        long[] dts = new long[n];
        for (int i = 0; i < n; i++) { dts[i] = sorted[i] + minC; if (i > 0 && dts[i] <= dts[i - 1]) dts[i] = dts[i - 1] + 1; }
        long defDelta = t.video ? (n > 1 ? Math.max(1, (dts[n - 1] - dts[0]) / (n - 1)) : ts / 30) : 1024;
        // stts
        D stts = new D(); IntList runs = new IntList(); LongList deltas = new LongList();
        for (int i = 0; i < n; i++) {
            long dl = i + 1 < n ? dts[i + 1] - dts[i] : defDelta;
            if (deltas.n > 0 && deltas.a[deltas.n - 1] == dl) runs.a[runs.n - 1]++; else { deltas.add(dl); runs.add(1); }
        }
        stts.u32(runs.n); for (int i = 0; i < runs.n; i++) { stts.u32(runs.a[i]); stts.u32(deltas.a[i]); }
        long mediaDur = dts[n - 1] + defDelta - dts[0];
        // ctts
        boolean needCtts = false, neg = false;
        long[] ct = new long[n]; for (int i = 0; i < n; i++) { ct[i] = tick[i] - dts[i]; if (ct[i] != 0) needCtts = true; if (ct[i] < 0) neg = true; }
        byte[] cttsBox = null;
        if (needCtts) {
            D c = new D(); IntList cr = new IntList(); LongList cv = new LongList();
            for (int i = 0; i < n; i++) { if (cv.n > 0 && cv.a[cv.n - 1] == ct[i]) cr.a[cr.n - 1]++; else { cv.add(ct[i]); cr.add(1); } }
            c.u32(cr.n); for (int i = 0; i < cr.n; i++) { c.u32(cr.a[i]); c.u32(cv.a[i]); }
            cttsBox = full("ctts", neg ? 1 : 0, 0, c.bytes());
        }
        // 编辑列表：B 帧偏移 + 起始空白
        long firstPres = sorted[0] - dts[0];              // 媒体时间轴上第一个显示时刻
        long emptyMovie = Math.round(sorted[0] * (double) MOVIE_TS / ts); // 相对全局起点
        long presentedMedia = sorted[n - 1] + defDelta - sorted[0]; // 显示区间 = 最后显示时刻 + 一帧 - 第一显示时刻
        long presentedMovie = Math.round(presentedMedia * (double) MOVIE_TS / ts);
        long tkDur = emptyMovie + presentedMovie; outMovieDur[0] = tkDur;
        byte[] edts = null;
        if (firstPres != 0 || emptyMovie > 0) {
            boolean ev1 = tkDur > 0xFFFFFFFFL || firstPres > 0x7FFFFFFFL;
            D e = new D(); e.u32(emptyMovie > 0 ? 2 : 1);
            if (emptyMovie > 0) { if (ev1) { e.u64(emptyMovie); e.u64(-1); } else { e.u32(emptyMovie); e.u32(0xFFFFFFFFL); } e.u32(0x00010000); }
            if (ev1) { e.u64(presentedMovie); e.u64(firstPres); } else { e.u32(presentedMovie); e.u32(firstPres); }
            e.u32(0x00010000);
            edts = box("edts", full("elst", ev1 ? 1 : 0, 0, e.bytes()));
        }
        // stsz / stsc / stco|co64 / stss
        D sz = new D(); sz.u32(0); sz.u32(n); for (int i = 0; i < n; i++) sz.u32(t.sizes.a[i]);
        D sc = new D(); IntList first = new IntList(), per = new IntList();
        for (int c = 0; c < t.chunkCount.n; c++) { int k = t.chunkCount.a[c]; if (per.n == 0 || per.a[per.n - 1] != k) { first.add(c + 1); per.add(k); } }
        sc.u32(first.n); for (int i = 0; i < first.n; i++) { sc.u32(first.a[i]); sc.u32(per.a[i]); sc.u32(1); }
        boolean co64 = force64; for (int c = 0; c < t.chunkOff.n; c++) if (t.chunkOff.a[c] > 0xFFFFFFFFL) co64 = true;
        D co = new D(); co.u32(t.chunkOff.n); for (int c = 0; c < t.chunkOff.n; c++) { if (co64) co.u64(t.chunkOff.a[c]); else co.u32(t.chunkOff.a[c]); }
        byte[] stss = null;
        if (t.video && t.keys.n < n) { D k = new D(); k.u32(t.keys.n); for (int i = 0; i < t.keys.n; i++) k.u32(t.keys.a[i]); stss = full("stss", 0, 0, k.bytes()); }

        List<byte[]> stbl = new ArrayList<>();
        stbl.add(full("stsd", 0, 0, cat(u32b(1), sampleEntry(t))));
        stbl.add(full("stts", 0, 0, stts.bytes()));
        if (cttsBox != null) stbl.add(cttsBox);
        if (stss != null) stbl.add(stss);
        stbl.add(full("stsc", 0, 0, sc.bytes()));
        stbl.add(full("stsz", 0, 0, sz.bytes()));
        stbl.add(full(co64 ? "co64" : "stco", 0, 0, co.bytes()));

        byte[] mhd = t.video ? full("vmhd", 0, 1, new byte[8]) : full("smhd", 0, 0, new byte[4]);
        byte[] dinf = box("dinf", full("dref", 0, 0, cat(u32b(1), full("url ", 0, 1, new byte[0]))));
        byte[] minf = box("minf", mhd, dinf, box("stbl", stbl.toArray(new byte[0][])));
        boolean mv1 = mediaDur > 0xFFFFFFFFL;
        D md = new D(); if (mv1) { md.u64(0); md.u64(0); md.u32(ts); md.u64(mediaDur); } else { md.u32(0); md.u32(0); md.u32(ts); md.u32(mediaDur); }
        md.u16(0x55C4); md.u16(0);
        D hd = new D(); hd.u32(0); hd.str(t.video ? "vide" : "soun"); hd.zeros(12); hd.raw((t.video ? "VideoHandler" : "SoundHandler").getBytes()); hd.u8(0);
        byte[] mdia = box("mdia", full("mdhd", mv1 ? 1 : 0, 0, md.bytes()), full("hdlr", 0, 0, hd.bytes()), minf);

        boolean tv1 = tkDur > 0xFFFFFFFFL;
        D tk = new D();
        if (tv1) { tk.u64(0); tk.u64(0); tk.u32(id); tk.u32(0); tk.u64(tkDur); } else { tk.u32(0); tk.u32(0); tk.u32(id); tk.u32(0); tk.u32(tkDur); }
        tk.zeros(8); tk.u16(0); tk.u16(0); tk.u16(t.video ? 0 : 0x0100); tk.u16(0);
        if (t.video) matrix(tk, rotation, t.width, t.height); else matrix(tk, 0, 0, 0);
        tk.u32(t.video ? (long) t.width << 16 : 0); tk.u32(t.video ? (long) t.height << 16 : 0);
        List<byte[]> tr = new ArrayList<>(); tr.add(full("tkhd", tv1 ? 1 : 0, 3, tk.bytes())); if (edts != null) tr.add(edts); tr.add(mdia);
        return box("trak", tr.toArray(new byte[0][]));
    }

    private static byte[] sampleEntry(Track t) {
        D d = new D(); d.zeros(6); d.u16(1);
        if (t.video) {
            d.zeros(16); d.u16(t.width); d.u16(t.height); d.u32(0x00480000); d.u32(0x00480000); d.u32(0); d.u16(1);
            d.zeros(32); d.u16(0x18); d.u16(0xFFFF); d.raw(t.csdBox);
        } else {
            d.zeros(8); d.u16(t.channels); d.u16(16); d.u16(0); d.u16(0); d.u32(t.sampleRate <= 0xFFFF ? (long) t.sampleRate << 16 : 0); d.raw(t.csdBox);
        }
        return box(t.fourcc, d.bytes());
    }

    static void matrix(D d, int rot, int w, int h) {
        long one = 0x00010000L, W = 0x40000000L; long a, b, c, dd, x = 0, y = 0;
        switch (rot) {
            case 90: a = 0; b = one; c = -one; dd = 0; x = (long) h << 16; break;
            case 180: a = -one; b = 0; c = 0; dd = -one; x = (long) w << 16; y = (long) h << 16; break;
            case 270: a = 0; b = -one; c = one; dd = 0; y = (long) w << 16; break;
            default: a = one; b = 0; c = 0; dd = one;
        }
        d.u32(a); d.u32(b); d.u32(0); d.u32(c); d.u32(dd); d.u32(0); d.u32(x); d.u32(y); d.u32(W);
    }

    // ---------- 盒子工具 ----------
    static final class D {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        void u8(int v) { o.write(v); }
        void u16(int v) { o.write(v >> 8); o.write(v); }
        void u24(int v) { o.write(v >> 16); o.write(v >> 8); o.write(v); }
        void u32(long v) { o.write((int) (v >> 24)); o.write((int) (v >> 16)); o.write((int) (v >> 8)); o.write((int) v); }
        void u64(long v) { u32(v >>> 32); u32(v); }
        void str(String s) { for (int i = 0; i < 4; i++) o.write(s.charAt(i)); }
        void zeros(int n) { for (int i = 0; i < n; i++) o.write(0); }
        void raw(byte[] b) { o.write(b, 0, b.length); }
        void raw(byte[] b, int off, int len) { o.write(b, off, len); }
        byte[] bytes() { return o.toByteArray(); }
    }
    static byte[] u32b(long v) { D d = new D(); d.u32(v); return d.bytes(); }
    static byte[] cat(byte[]... p) { D d = new D(); for (byte[] x : p) d.raw(x); return d.bytes(); }
    static byte[] box(String type, byte[]... children) {
        long n = 8; for (byte[] c : children) n += c.length;
        D d = new D(); d.u32(n); d.str(type); for (byte[] c : children) d.raw(c); return d.bytes();
    }
    static byte[] full(String type, int ver, int flags, byte[] payload) {
        D d = new D(); d.u8(ver); d.u24(flags); d.raw(payload); return box(type, d.bytes());
    }
}
