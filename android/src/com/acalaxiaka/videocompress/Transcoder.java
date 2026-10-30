package com.acalaxiaka.videocompress;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.*;
import android.net.Uri;
import android.opengl.*;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/** 硬件转码：MediaExtractor → 硬解 → SurfaceTexture/GLES 缩放 → 硬编(HEVC/AVC/AV1) → MediaMuxer(MP4)。 */
public final class Transcoder {

    public static final class Settings {
        public int preset = BitrateCalc.BALANCED;
        public String codec = null;        // null=自动 (AV1硬件>HEVC硬件>AVC)
        public int maxShort = 0;           // 0=保持, 1080, 720
        public int quality = 50;           // 自定义画质 1..100
        public long customBps = 0;         // 自定义码率，0=按画质
        public int audioBps = -1;          // -1=尽量直接复制, >0=AAC 重编码码率
    }

    public static final class Probe {
        public int w, h, rotation; public double fps; public long durUs, size, srcVideoBps;
        public String vMime, aMime; public boolean hasAudio; public long aBps;
    }

    public static final class Plan {
        public String mime, encName; public int w, h; public long vBps, aBps, estBytes; public boolean audioCopy;
    }

    public static class TranscodeException extends Exception { public TranscodeException(String m) { super(m); } }

    private volatile boolean canceled;
    public void cancel() { abort(); }

    // ---------- 探测 ----------
    public static Probe probe(Context ctx, Uri uri, long size) throws TranscodeException {
        Probe p = new Probe(); p.size = size;
        MediaExtractor ex = new MediaExtractor();
        try {
            try { ex.setDataSource(ctx, uri, null); }
            catch (Exception e) { throw new TranscodeException("无法读取该文件：系统解析器不支持此封装格式（如 AVI、RMVB、FLV 等）。请先在电脑上转为 MP4 再试。"); }
            int vt = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i); String m = f.getString(MediaFormat.KEY_MIME);
                if (m == null) continue;
                if (m.startsWith("video/") && vt < 0) {
                    vt = i; p.vMime = m; p.w = f.getInteger(MediaFormat.KEY_WIDTH); p.h = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey(MediaFormat.KEY_DURATION)) p.durUs = f.getLong(MediaFormat.KEY_DURATION);
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) p.fps = num(f, MediaFormat.KEY_FRAME_RATE);
                    if (f.containsKey(MediaFormat.KEY_ROTATION)) p.rotation = f.getInteger(MediaFormat.KEY_ROTATION);
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE)) p.srcVideoBps = f.getInteger(MediaFormat.KEY_BIT_RATE);
                } else if (m.startsWith("audio/") && !p.hasAudio) {
                    p.hasAudio = true; p.aMime = m;
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE)) p.aBps = f.getInteger(MediaFormat.KEY_BIT_RATE);
                }
            }
            if (vt < 0) throw new TranscodeException("未找到可解码的视频轨（可能是 AVI/不支持的编码）。");
            if (findDecoder(ex.getTrackFormat(vt)) == null)
                throw new TranscodeException("本机没有可用的 " + p.vMime + " 解码器，无法转换此视频。");
        } finally { ex.release(); }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rot != null) p.rotation = Integer.parseInt(rot);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (p.durUs <= 0 && d != null) p.durUs = Long.parseLong(d) * 1000;
            if (!(p.fps > 0)) {
                String c = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
                if (c != null) try { p.fps = Double.parseDouble(c); } catch (Exception ignored) {}
                if (!(p.fps > 0) && Build.VERSION.SDK_INT >= 28) {
                    String fc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                    if (fc != null && p.durUs > 0) p.fps = Long.parseLong(fc) / (p.durUs / 1e6);
                }
            }
        } catch (Exception ignored) {
        } finally { try { r.release(); } catch (Exception ignored) {} }
        if (!(p.fps > 0) || p.fps > 240) p.fps = 30;
        if (p.srcVideoBps <= 0 && p.durUs > 0 && size > 0) {
            long total = (long) (size * 8 / (p.durUs / 1e6));
            p.srcVideoBps = Math.max(0, total - (p.hasAudio ? (p.aBps > 0 ? p.aBps : 128_000) : 0));
        }
        return p;
    }

    static double num(MediaFormat f, String k) {
        try { return f.getInteger(k); } catch (Exception e) { try { return f.getFloat(k); } catch (Exception e2) { return 0; } }
    }

    static String findDecoder(MediaFormat f) {
        MediaFormat c = new MediaFormat(f);
        if (Build.VERSION.SDK_INT == 21) c.setString(MediaFormat.KEY_FRAME_RATE, null);
        c.setString(MediaFormat.KEY_FRAME_RATE, null);
        return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(c);
    }

    // ---------- 方案 ----------
    public static Plan plan(Probe p, Settings s) throws TranscodeException {
        Plan pl = new Plan();
        int[] sz = BitrateCalc.targetSize(p.w, p.h, s.maxShort);
        List<String> order = new ArrayList<>();
        if (s.codec != null) order.add(s.codec);
        if (s.codec == null && Build.VERSION.SDK_INT >= 34) order.add(BitrateCalc.AV1);
        for (String m : new String[]{BitrateCalc.HEVC, BitrateCalc.AVC}) if (!order.contains(m)) order.add(m);
        for (String m : order) {
            List<CodecSupport.Enc> encs = CodecSupport.encoders(m);
            for (CodecSupport.Enc e : encs) {
                if (!e.hw && s.codec == null && !BitrateCalc.AVC.equals(m)) continue; // 自动模式下 HEVC/AV1 只用硬件
                if (BitrateCalc.AV1.equals(m) && (!e.hw || Build.VERSION.SDK_INT < 34)) continue;
                int w = sz[0], h = sz[1];
                if (!CodecSupport.sizeOk(e, w, h)) {
                    // 有些编码器只接受 16 对齐 / 竖屏尺寸
                    int w16 = (w / 16) * 16, h16 = (h / 16) * 16;
                    if (CodecSupport.sizeOk(e, w16, h16)) { w = w16; h = h16; } else continue;
                }
                pl.mime = m; pl.encName = e.name; pl.w = w; pl.h = h; break;
            }
            if (pl.mime != null) break;
        }
        if (pl.mime == null) throw new TranscodeException("本机没有能处理 " + sz[0] + "×" + sz[1] + " 的视频编码器，请改用 1080p/720p 输出。");
        double bpp = s.preset == BitrateCalc.CUSTOM ? BitrateCalc.qualityToBpp(s.quality) : BitrateCalc.baseBpp(s.preset);
        if (s.preset == BitrateCalc.CUSTOM && s.customBps > 0) pl.vBps = s.customBps;
        else pl.vBps = BitrateCalc.videoBitrate(pl.mime, bpp, pl.w, pl.h, p.fps, p.w, p.h, p.srcVideoBps, s.preset == BitrateCalc.HIGH);
        if (p.hasAudio) {
            pl.audioCopy = s.audioBps < 0 && "audio/mp4a-latm".equals(p.aMime);
            pl.aBps = pl.audioCopy ? (p.aBps > 0 ? p.aBps : 128_000) : (s.audioBps > 0 ? s.audioBps : 128_000);
        }
        pl.estBytes = BitrateCalc.estimateBytes(pl.vBps, pl.aBps, p.durUs);
        return pl;
    }

    // ---------- 转码（异步 MediaCodec + 看门狗） ----------
    /** 单个输出分段（MP4 32 位偏移上限约 4 GB，超过 SEG_LIMIT 自动切分为多个文件）。 */
    public static abstract class Seg {
        public abstract MediaMuxer createMuxer() throws java.io.IOException;
        public abstract void commit(String suffix) throws java.io.IOException;
        public abstract void discard();
        public abstract String where();
        public long bytes;
    }
    public interface Sink { Seg open(int index) throws java.io.IOException; }
    public interface Listener { void onProgress(float p); }
    public static final class Result { public final List<Seg> segs = new ArrayList<>(); public long bytes; public boolean partial; public String partialReason; }

    public static long SEG_LIMIT = 3_500_000_000L;   // 留足 moov 空间，低于 4 GiB
    public static long STALL_MS = 20_000;

    private final Object mx = new Object(), gate = new Object();
    private MediaMuxer muxer; private boolean muxStarted; private int vTrack = -1, aTrack = -1;
    private MediaFormat vOutFmt, aOutFmt; private boolean needAudio;
    private final List<Object[]> pending = new ArrayList<>();
    private final Result result = new Result(); private Seg seg; private Sink sink; private int rotation;
    private volatile boolean rollRequested, videoDone, audioDone, decEos, rendering, failedFlag;
    private volatile Throwable error; private volatile long lastProgressMs, lastVideoPts = -1, lastRenderPts = -1, framesIn, framesOut, framesEnc;
    private volatile String stage = "init";
    private final java.util.ArrayDeque<long[]> decQ = new java.util.ArrayDeque<>();
    private MediaCodec dec, enc, aDec, aEnc; private MediaExtractor vEx, aEx;
    private InputSurface inS; private OutputSurface outS; private HandlerThread glT, encT, audT; private Handler glH;
    private Thread audioThread;

    private void fail(Throwable t) { if (error == null) error = t; failedFlag = true; AppLog.w("错误：" + t); }
    private void touch() { lastProgressMs = System.currentTimeMillis(); }

    public String diag() {
        return "stage=" + stage + " in=" + framesIn + " dec->gl=" + framesOut + " enc=" + framesEnc + " decQ=" + decQ.size() + " rendering=" + rendering
                + " decEos=" + decEos + " videoDone=" + videoDone + " audioDone=" + audioDone + " lastVPts=" + lastVideoPts / 1000 + "ms"
                + " seg#" + result.segs.size() + " segBytes=" + (seg != null ? seg.bytes : 0) + " muxStarted=" + muxStarted;
    }

    public Result run(Context ctx, Uri uri, Probe p, Plan pl, Sink sink, Listener l) throws Exception {
        this.sink = sink; this.rotation = ((p.rotation % 360) + 360) % 360;
        touch();
        try {
            vEx = new MediaExtractor(); vEx.setDataSource(ctx, uri, null);
            int vt = selectTrack(vEx, "video/"); MediaFormat vf = vEx.getTrackFormat(vt); vEx.selectTrack(vt);
            needAudio = p.hasAudio;
            MediaFormat af = null;
            if (needAudio) {
                aEx = new MediaExtractor(); aEx.setDataSource(ctx, uri, null);
                int at = selectTrack(aEx, "audio/"); af = aEx.getTrackFormat(at); aEx.selectTrack(at);
                if (pl.audioCopy) aOutFmt = af;
                else if (findDecoder(af) == null) { needAudio = false; AppLog.w("音频无法解码，输出无声视频"); }
            }
            seg = sink.open(0); result.segs.add(seg);
            muxer = seg.createMuxer(); muxer.setOrientationHint(rotation);
            if (needAudio && pl.audioCopy) aTrack = muxer.addTrack(aOutFmt);

            glT = new HandlerThread("vc-gl"); glT.start(); glH = new Handler(glT.getLooper());
            encT = new HandlerThread("vc-enc"); encT.start(); Handler encH = new Handler(encT.getLooper());
            final MediaFormat fvf = vf;
            stage = "setup";
            Object setup = runOn(glH, 10_000, () -> { setupVideo(fvf, pl, p, encH, l); return null; });
            if (setup instanceof Throwable) throw new TranscodeException("初始化编解码器失败 [E-INIT]：" + setup);

            if (needAudio) {
                final MediaFormat faf = af;
                audioThread = new Thread(() -> {
                    try { if (pl.audioCopy) copyAudio(faf); else reencodeAudio(faf, pl); }
                    catch (Throwable t) { if (!canceled) fail(t); }
                    finally { audioDone = true; }
                }, "vc-audio");
                audioThread.start();
            } else audioDone = true;

            stage = "running";
            // 控制线程：只做带超时的等待，永不阻塞在编解码调用上
            while (true) {
                synchronized (gate) { gate.wait(300); }
                if (canceled) throw new InterruptedException("canceled");
                if (failedFlag) throw new TranscodeException("编解码出错 [E-CODEC]：" + error);
                if (videoDone && audioDone) break;
                long idle = System.currentTimeMillis() - lastProgressMs;
                if (idle > STALL_MS) {
                    AppLog.w("看门狗：" + (idle / 1000) + " 秒无进展。" + diag());
                    result.partial = true;
                    result.partialReason = String.format(java.util.Locale.CHINA, "处理在 %.0f%% 处停滞超过 %d 秒 [E-STALL]", p.durUs > 0 ? 100.0 * Math.max(0, lastVideoPts) / p.durUs : 0, STALL_MS / 1000);
                    break;
                }
            }
            stage = result.partial ? "finalize-partial" : "finalize";
            if (!finishMuxer(8_000)) {
                throw new TranscodeException((result.partial ? result.partialReason + "，且" : "") + "无法完成写入文件（复用器无响应）[E-MUX]。" + diag());
            }
            if (l != null && !result.partial) l.onProgress(1f);
            for (Seg s : result.segs) result.bytes += s.bytes;
            return result;
        } finally {
            teardown(5_000);
        }
    }

    /** 外部（看门狗/取消）可随时调用：在独立线程中释放资源，最多等待 timeout。 */
    public void abort() { canceled = true; synchronized (gate) { gate.notifyAll(); } }

    private void setupVideo(MediaFormat vf, Plan pl, Probe p, Handler encH, Listener l) throws Exception {
        MediaFormat ef = encoderFormat(pl, p, true);
        enc = MediaCodec.createByCodecName(pl.encName);
        MediaCodec.Callback encCb = new MediaCodec.Callback() {
            public void onInputBufferAvailable(MediaCodec c, int i) {}
            public void onOutputBufferAvailable(MediaCodec c, int i, MediaCodec.BufferInfo info) {
                try {
                    ByteBuffer b = c.getOutputBuffer(i);
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && b != null) {
                        writeSample(true, b, info); framesEnc++; lastVideoPts = info.presentationTimeUs;
                    }
                    c.releaseOutputBuffer(i, false);
                    if (eos) { videoDone = true; AppLog.i("视频编码结束，帧数 " + framesEnc); synchronized (gate) { gate.notifyAll(); } }
                } catch (Throwable t) { if (!canceled) fail(t); }
            }
            public void onError(MediaCodec c, MediaCodec.CodecException e) { if (!canceled) fail(e); }
            public void onOutputFormatChanged(MediaCodec c, MediaFormat f) { synchronized (mx) { vOutFmt = f; vTrack = muxer.addTrack(f); maybeStart(); } }
        };
        enc.setCallback(encCb, encH);
        try { enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); }
        catch (Exception e) {
            AppLog.w("编码器不接受扩展参数（profile/priority/operating-rate），使用基本参数重试：" + e);
            enc.reset(); enc.setCallback(encCb, encH);
            enc.configure(encoderFormat(pl, p, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
        AppLog.i("编码器 " + pl.encName + " " + pl.w + "x" + pl.h + " " + pl.vBps / 1000 + "kbps");
        inS = new InputSurface(enc.createInputSurface()); inS.makeCurrent();
        outS = new OutputSurface(pl.w, pl.h, glH, this::onFrame);
        dec = MediaCodec.createByCodecName(findDecoder(vf));
        dec.setCallback(new MediaCodec.Callback() {
            public void onInputBufferAvailable(MediaCodec c, int i) {
                if (canceled || decEos) return;
                try {
                    ByteBuffer b = c.getInputBuffer(i);
                    int n = vEx.readSampleData(b, 0);
                    if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); decEos = true; AppLog.i("视频读取完毕，共 " + framesIn + " 帧"); }
                    else { c.queueInputBuffer(i, 0, n, vEx.getSampleTime(), 0); vEx.advance(); framesIn++; }
                } catch (Throwable t) { if (!canceled) fail(t); }
            }
            public void onOutputBufferAvailable(MediaCodec c, int i, MediaCodec.BufferInfo info) {
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                decQ.add(new long[]{i, info.presentationTimeUs, info.size > 0 ? 1 : 0, eos ? 1 : 0});
                pump();
            }
            public void onError(MediaCodec c, MediaCodec.CodecException e) { if (!canceled) fail(e); }
            public void onOutputFormatChanged(MediaCodec c, MediaFormat f) {}
        }, glH);
        MediaFormat dvf = new MediaFormat(vf);
        if (Build.VERSION.SDK_INT >= 23) { dvf.setInteger(MediaFormat.KEY_PRIORITY, 1); }
        dec.configure(dvf, outS.surface, null, 0);
        enc.start(); dec.start();
        this.listener = l; this.durUs = p.durUs;
    }
    private Listener listener; private long durUs; private long renderStartMs;

    /** 一次只把一帧送到 SurfaceTexture，确保不丢帧、无背压死锁；带 3 秒丢帧超时。 */
    private void pump() {
        if (canceled || rendering) return;
        while (!decQ.isEmpty()) {
            long[] f = decQ.poll();
            int idx = (int) f[0]; boolean render = f[2] == 1, eos = f[3] == 1;
            try { dec.releaseOutputBuffer(idx, render); } catch (Throwable t) { if (!canceled) fail(t); return; }
            if (render) {
                rendering = true; pendingPts = f[1]; pendingEos = eos; renderStartMs = System.currentTimeMillis();
                final long token = ++renderToken;
                glH.postDelayed(() -> { if (rendering && renderToken == token) { AppLog.w("帧回调超时，跳过一帧 pts=" + pendingPts / 1000 + "ms"); rendering = false; if (pendingEos) endVideoInput(); else pump(); } }, 3000);
                return;
            }
            if (eos) { endVideoInput(); return; }
        }
    }
    private long pendingPts, renderToken; private boolean pendingEos, inputEnded;

    private void onFrame() {
        if (canceled || !rendering) { try { outS.latch(); } catch (Throwable ignored) {} return; }
        try {
            outS.latch(); outS.drawImage();
            long pts = Math.max(pendingPts, lastRenderPts + 1); // 保证时间戳单调递增
            lastRenderPts = pts;
            inS.setPresentationTime(pts * 1000); inS.swapBuffers();
            framesOut++; touch();
            if (listener != null && durUs > 0 && (framesOut % 10 == 0)) listener.onProgress(Math.min(0.995f, pts / (float) durUs));
        } catch (Throwable t) { if (!canceled) fail(t); }
        rendering = false;
        if (pendingEos) endVideoInput(); else pump();
    }

    private void endVideoInput() {
        if (inputEnded) return; inputEnded = true;
        try { enc.signalEndOfInputStream(); AppLog.i("已向编码器发送 EOS"); } catch (Throwable t) { if (!canceled) fail(t); }
    }

    private MediaFormat encoderFormat(Plan pl, Probe p, boolean extras) {
        MediaFormat ef = MediaFormat.createVideoFormat(pl.mime, pl.w, pl.h);
        ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        ef.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.min(Integer.MAX_VALUE, pl.vBps));
        ef.setInteger(MediaFormat.KEY_FRAME_RATE, (int) Math.max(1, Math.round(p.fps)));
        ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        try {
            MediaCodecInfo.EncoderCapabilities ec = CodecSupport.byName(pl.encName).getCapabilitiesForType(pl.mime).getEncoderCapabilities();
            if (ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                ef.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        } catch (Exception ignored) {}
        if (extras) {
            if (BitrateCalc.HEVC.equals(pl.mime)) ef.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
            if (BitrateCalc.AVC.equals(pl.mime)) ef.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            // 非实时、尽可能快
            ef.setInteger(MediaFormat.KEY_PRIORITY, 1);
            ef.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);
        }
        return ef;
    }

    // ---- 复用器（线程安全；分段；记录进度） ----
    private void maybeStart() {
        if (muxStarted || vTrack < 0 || (needAudio && aTrack < 0)) return;
        muxer.start(); muxStarted = true; AppLog.i("复用器启动");
        for (Object[] o : pending) { MediaCodec.BufferInfo bi = (MediaCodec.BufferInfo) o[2]; muxer.writeSampleData((Boolean) o[0] ? vTrack : aTrack, (ByteBuffer) o[1], bi); seg.bytes += bi.size; }
        pending.clear();
    }

    private void writeSample(boolean video, ByteBuffer b, MediaCodec.BufferInfo info) throws Exception {
        if (canceled) return;
        synchronized (mx) {
            if (muxer == null) return;
            b.position(info.offset); b.limit(info.offset + info.size);
            if (!muxStarted) {
                ByteBuffer c = ByteBuffer.allocateDirect(info.size); c.put(b); c.flip();
                MediaCodec.BufferInfo i2 = new MediaCodec.BufferInfo(); i2.set(0, info.size, info.presentationTimeUs, info.flags);
                pending.add(new Object[]{video, c, i2}); touch(); return;
            }
            if (video && rollRequested && (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) roll();
            muxer.writeSampleData(video ? vTrack : aTrack, b, info);
            seg.bytes += info.size; touch();
            if (video && !rollRequested && seg.bytes > SEG_LIMIT) {
                rollRequested = true; AppLog.i("分段达到 " + BitrateCalc.human(seg.bytes) + "，请求关键帧以切分（MP4 4GB 限制）");
                android.os.Bundle bd = new android.os.Bundle(); bd.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                try { enc.setParameters(bd); } catch (Exception ignored) {}
            }
        }
        synchronized (gate) { gate.notifyAll(); }
    }

    private void roll() throws Exception {
        muxer.stop(); muxer.release();
        AppLog.i("分段 " + result.segs.size() + " 完成：" + BitrateCalc.human(seg.bytes));
        seg = sink.open(result.segs.size()); result.segs.add(seg);
        muxer = seg.createMuxer(); muxer.setOrientationHint(rotation);
        vTrack = muxer.addTrack(vOutFmt);
        if (needAudio) aTrack = muxer.addTrack(aOutFmt);
        muxer.start(); rollRequested = false;
    }

    /** 在独立线程中结束复用器，超时则放弃（避免卡死）。 */
    private boolean finishMuxer(long timeoutMs) {
        // 先停编解码器，避免回调继续写入
        stopCodecs(3000);
        final boolean[] ok = {false};
        Thread t = new Thread(() -> {
            synchronized (mx) {
                try { if (muxStarted) { muxer.stop(); ok[0] = true; } else ok[0] = false; } catch (Throwable e) { AppLog.w("muxer.stop 失败：" + e); }
                try { muxer.release(); } catch (Throwable ignored) {}
                muxer = null;
            }
        }, "vc-mux-stop");
        t.setDaemon(true); t.start();
        try { t.join(timeoutMs); } catch (InterruptedException ignored) {}
        if (t.isAlive()) { AppLog.w("muxer.stop 超时（可能复用器内部阻塞）"); return false; }
        return ok[0];
    }

    private void stopCodecs(long timeoutMs) {
        final MediaCodec d = dec, e = enc, ad = aDec, ae = aEnc;
        Thread t = new Thread(() -> { stop(d); stop(e); stop(ad); stop(ae); }, "vc-stop");
        t.setDaemon(true); t.start();
        try { t.join(timeoutMs); } catch (InterruptedException ignored) {}
        if (t.isAlive()) AppLog.w("停止编解码器超时，强制继续");
    }
    private static void stop(MediaCodec c) { if (c != null) try { c.stop(); } catch (Throwable ignored) {} }

    private void teardown(long timeoutMs) {
        canceled = true;
        synchronized (gate) { gate.notifyAll(); }
        Thread t = new Thread(() -> {
            stopCodecs(2000);
            rel(dec); rel(enc); rel(aDec); rel(aEnc);
            if (glH != null) runOn(glH, 2000, () -> { if (outS != null) outS.release(); if (inS != null) inS.release(); return null; });
            try { if (vEx != null) vEx.release(); } catch (Throwable ignored) {}
            try { if (aEx != null) aEx.release(); } catch (Throwable ignored) {}
            if (glT != null) glT.quitSafely(); if (encT != null) encT.quitSafely();
        }, "vc-teardown");
        t.setDaemon(true); t.start();
        try { t.join(timeoutMs); } catch (InterruptedException ignored) {}
        if (t.isAlive()) AppLog.w("资源释放超时，已放弃等待（后台线程将自行结束）");
        if (audioThread != null) audioThread.interrupt();
        // 若复用器仍未释放（异常路径），尝试在后台释放
        final MediaMuxer m = muxer; muxer = null;
        if (m != null) { Thread r = new Thread(() -> { try { m.release(); } catch (Throwable ignored) {} }); r.setDaemon(true); r.start(); }
    }

    private static Object runOn(Handler h, long timeoutMs, java.util.concurrent.Callable<Object> c) {
        final Object[] res = {null}; final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        if (!h.post(() -> { try { res[0] = c.call(); } catch (Throwable t) { res[0] = t; } latch.countDown(); })) return new RuntimeException("线程已退出");
        try { if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return new RuntimeException("超时"); }
        catch (InterruptedException e) { return e; }
        return res[0];
    }

    // ---- 音频：独立线程，按视频时间轴交错（最多领先 1 秒），所有等待带超时 ----
    private void waitAudioTurn(long aPts) throws InterruptedException {
        while (!canceled && !failedFlag && !videoDone && (!muxStarted || aPts > lastVideoPts + 1_000_000)) {
            if (result.partial) return;
            synchronized (gate) { gate.wait(50); }
        }
    }

    private void copyAudio(MediaFormat af) throws Exception {
        int cap = af.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? Math.max(1 << 16, af.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) : 1 << 20;
        ByteBuffer buf = ByteBuffer.allocateDirect(cap); MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
        while (!canceled && !failedFlag) {
            int n = aEx.readSampleData(buf, 0);
            if (n < 0) break;
            long pts = aEx.getSampleTime();
            waitAudioTurn(pts);
            if (canceled || stageIsFinal()) break;
            int fl = (aEx.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            ai.set(0, n, pts, fl); writeSample(false, buf, ai); aEx.advance();
        }
        AppLog.i("音频复制完成");
    }
    private boolean stageIsFinal() { return stage.startsWith("finalize"); }

    private void reencodeAudio(MediaFormat af, Plan pl) throws Exception {
        aDec = MediaCodec.createByCodecName(findDecoder(af)); aDec.configure(af, null, null, 0); aDec.start();
        sampleRate = af.getInteger(MediaFormat.KEY_SAMPLE_RATE); srcChannels = af.getInteger(MediaFormat.KEY_CHANNEL_COUNT); outChannels = Math.min(2, srcChannels);
        MediaFormat aef = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, outChannels);
        aef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        aef.setInteger(MediaFormat.KEY_BIT_RATE, (int) pl.aBps); aef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
        aEnc = MediaCodec.createEncoderByType("audio/mp4a-latm"); aEnc.configure(aef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); aEnc.start();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean exDone = false; long aPts = 0;
        while (!canceled && !failedFlag && !stageIsFinal()) {
            if (!exDone) {
                if (aOutFmt != null) waitAudioTurn(aPts); // 格式未知前不等待，避免与复用器启动互相等待
                int ii = aDec.dequeueInputBuffer(5_000);
                if (ii >= 0) {
                    int n = aEx.readSampleData(aDec.getInputBuffer(ii), 0);
                    if (n < 0) { aDec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); exDone = true; }
                    else { aPts = aEx.getSampleTime(); aDec.queueInputBuffer(ii, 0, n, aPts, 0); aEx.advance(); }
                }
            }
            pumpAudio(aDec, aEnc);
            int oi = aEnc.dequeueOutputBuffer(info, 5_000);
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { synchronized (mx) { aOutFmt = aEnc.getOutputFormat(); aTrack = muxer.addTrack(aOutFmt); maybeStart(); } }
            else if (oi >= 0) {
                ByteBuffer b = aEnc.getOutputBuffer(oi);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (info.size > 0) writeSample(false, b, info);
                aEnc.releaseOutputBuffer(oi, false);
                if (eos) break;
            }
        }
        AppLog.i("音频重编码完成");
    }

    // 音频 PCM 搬运：解码器输出 → 编码器输入（可能下混为双声道）
    private int pendIdx = -1, pendOff = 0, srcChannels = 2, outChannels = 2, sampleRate = 44100;
    private final MediaCodec.BufferInfo pendInfo = new MediaCodec.BufferInfo();
    private boolean aDecEosSent;
    private void pumpAudio(MediaCodec aDec, MediaCodec aEnc) {
        if (aDecEosSent) return;
        if (pendIdx < 0) {
            int oi = aDec.dequeueOutputBuffer(pendInfo, 5_000);
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = aDec.getOutputFormat();
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) srcChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                return;
            }
            if (oi < 0) return;
            pendIdx = oi; pendOff = 0;
        }
        int ii = aEnc.dequeueInputBuffer(5_000);
        if (ii < 0) return;
        ByteBuffer src = aDec.getOutputBuffer(pendIdx), dst = aEnc.getInputBuffer(ii);
        boolean eos = (pendInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        int remain = pendInfo.size - pendOff;
        int inFrame = 2 * srcChannels, outFrame = 2 * outChannels;
        int frames = Math.min(remain / inFrame, dst.remaining() / outFrame);
        long pts = pendInfo.presentationTimeUs + (long) (pendOff / inFrame) * 1_000_000L / sampleRate;
        if (frames > 0) {
            src.position(pendInfo.offset + pendOff); src.limit(pendInfo.offset + pendInfo.size);
            ByteBuffer s = src.slice().order(ByteOrder.nativeOrder()); dst.order(ByteOrder.nativeOrder());
            if (srcChannels == outChannels) { s.limit(frames * inFrame); dst.put(s); }
            else for (int f = 0; f < frames; f++)
                for (int c = 0; c < outChannels; c++) dst.putShort(s.getShort((f * srcChannels + Math.min(c, srcChannels - 1)) * 2));
            pendOff += frames * inFrame;
        }
        boolean done = pendInfo.size - pendOff < inFrame;
        aEnc.queueInputBuffer(ii, 0, frames * outFrame, pts, done && eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
        if (done) { aDec.releaseOutputBuffer(pendIdx, false); pendIdx = -1; if (eos) aDecEosSent = true; }
    }

    private static int selectTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void rel(MediaCodec c) { if (c != null) try { c.release(); } catch (Throwable ignored) {} }


    // ---------- EGL：编码器输入 Surface ----------
    static final class InputSurface {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;
        EGLDisplay dpy; EGLContext ctx; EGLSurface surf; final Surface surface;
        InputSurface(Surface s) {
            surface = s;
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2]; EGL14.eglInitialize(dpy, v, 0, v, 1);
            int[] attr = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1]; int[] n = new int[1];
            EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, n, 0);
            if (n[0] < 1) throw new RuntimeException("EGL 配置失败");
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surf = EGL14.eglCreateWindowSurface(dpy, cfg[0], s, new int[]{EGL14.EGL_NONE}, 0);
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) throw new RuntimeException("EGL surface 创建失败");
        }
        void makeCurrent() { EGL14.eglMakeCurrent(dpy, surf, surf, ctx); }
        void swapBuffers() { EGL14.eglSwapBuffers(dpy, surf); }
        void setPresentationTime(long ns) { EGLExt.eglPresentationTimeANDROID(dpy, surf, ns); }
        void release() {
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx);
                EGL14.eglReleaseThread(); EGL14.eglTerminate(dpy);
            }
            surface.release(); dpy = EGL14.EGL_NO_DISPLAY;
        }
    }

    // ---------- 解码器输出 SurfaceTexture + 绘制（缩放） ----------
    static final class OutputSurface implements SurfaceTexture.OnFrameAvailableListener {
        final SurfaceTexture st; final Surface surface; final Runnable onFrame;
        private final int w, h, prog, tex, aPos, aTex, uMvp, uSt;
        private final float[] stM = new float[16], mvp = new float[16];
        private final FloatBuffer verts;
        private static final String VS = "uniform mat4 uMVP;uniform mat4 uST;attribute vec4 aPos;attribute vec4 aTex;varying vec2 vTex;" +
                "void main(){gl_Position=uMVP*aPos;vTex=(uST*aTex).xy;}";
        private static final String FS = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 vTex;" +
                "uniform samplerExternalOES sTex;void main(){gl_FragColor=texture2D(sTex,vTex);}";

        OutputSurface(int w, int h, Handler handler, Runnable onFrame) {
            this.w = w; this.h = h; this.onFrame = onFrame;
            float[] v = {-1, -1, 0, 0, 0, 1, -1, 0, 1, 0, -1, 1, 0, 0, 1, 1, 1, 0, 1, 1};
            verts = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); verts.put(v).position(0);
            prog = program(VS, FS);
            aPos = GLES20.glGetAttribLocation(prog, "aPos"); aTex = GLES20.glGetAttribLocation(prog, "aTex");
            uMvp = GLES20.glGetUniformLocation(prog, "uMVP"); uSt = GLES20.glGetUniformLocation(prog, "uST");
            int[] t = new int[1]; GLES20.glGenTextures(1, t, 0); tex = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            st = new SurfaceTexture(tex);
            st.setOnFrameAvailableListener(this, handler);
            surface = new Surface(st);
            Matrix.setIdentityM(mvp, 0);
        }
        @Override public void onFrameAvailable(SurfaceTexture s) { onFrame.run(); }
        void latch() { st.updateTexImage(); }
        void drawImage() {
            st.getTransformMatrix(stM);
            GLES20.glViewport(0, 0, w, h);
            GLES20.glClearColor(0, 0, 0, 1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(prog);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
            verts.position(0); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, verts); GLES20.glEnableVertexAttribArray(aPos);
            verts.position(3); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 20, verts); GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0); GLES20.glUniformMatrix4fv(uSt, 1, false, stM, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
        void release() { surface.release(); st.release(); GLES20.glDeleteProgram(prog); }

        static int shader(int type, String src) {
            int s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s);
            int[] ok = new int[1]; GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }
        static int program(String vs, String fs) {
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
            GLES20.glLinkProgram(p); return p;
        }
    }
}
