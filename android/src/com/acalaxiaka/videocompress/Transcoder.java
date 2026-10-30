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

    public interface Listener { void onProgress(float p); }
    public static class TranscodeException extends Exception { public TranscodeException(String m) { super(m); } }

    private volatile boolean canceled;
    public void cancel() { canceled = true; }

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

    // ---------- 转码 ----------
    private static final long TO = 10_000;
    private MediaMuxer muxer; private boolean muxStarted; private int vTrack = -1, aTrack = -1;
    private final List<Object[]> pending = new ArrayList<>();
    private boolean needAudio;

    public void run(Context ctx, Uri uri, Probe p, Plan pl, File out, Listener l) throws Exception {
        MediaExtractor vEx = new MediaExtractor(), aEx = null;
        MediaCodec dec = null, enc = null, aDec = null, aEnc = null;
        InputSurface inS = null; OutputSurface outS = null;
        try {
            vEx.setDataSource(ctx, uri, null);
            int vt = selectTrack(vEx, "video/");
            MediaFormat vf = vEx.getTrackFormat(vt); vEx.selectTrack(vt);

            MediaFormat ef = MediaFormat.createVideoFormat(pl.mime, pl.w, pl.h);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.min(Integer.MAX_VALUE, pl.vBps));
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, (int) Math.max(1, Math.round(p.fps)));
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            enc = MediaCodec.createByCodecName(pl.encName);
            try {
                MediaCodecInfo.EncoderCapabilities ec = enc.getCodecInfo().getCapabilitiesForType(pl.mime).getEncoderCapabilities();
                if (ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                    ef.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            } catch (Exception ignored) {}
            if (BitrateCalc.HEVC.equals(pl.mime)) ef.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
            if (BitrateCalc.AVC.equals(pl.mime)) ef.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            try { enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); }
            catch (Exception e) { // 部分设备不接受 profile 指定，去掉重试
                ef.setString(MediaFormat.KEY_PROFILE, null); ef = stripProfile(ef);
                enc.reset(); enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            inS = new InputSurface(enc.createInputSurface()); inS.makeCurrent();
            enc.start();
            outS = new OutputSurface(pl.w, pl.h);
            dec = MediaCodec.createByCodecName(findDecoder(vf));
            dec.configure(vf, outS.surface, null, 0); dec.start();

            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(((p.rotation % 360) + 360) % 360);

            // 音频
            int at = -1; MediaFormat af = null;
            if (p.hasAudio) {
                aEx = new MediaExtractor(); aEx.setDataSource(ctx, uri, null);
                at = selectTrack(aEx, "audio/"); af = aEx.getTrackFormat(at); aEx.selectTrack(at);
                needAudio = true;
                if (pl.audioCopy) {
                    aTrack = muxer.addTrack(af);
                } else {
                    String adn = findDecoder(af);
                    if (adn == null) { needAudio = false; aEx.release(); aEx = null; } // 音频不可解码：输出无声视频
                    else {
                        aDec = MediaCodec.createByCodecName(adn); aDec.configure(af, null, null, 0); aDec.start();
                        int sr = af.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = Math.min(2, af.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                        MediaFormat aef = MediaFormat.createAudioFormat("audio/mp4a-latm", sr, ch);
                        aef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                        aef.setInteger(MediaFormat.KEY_BIT_RATE, (int) pl.aBps);
                        aef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
                        aEnc = MediaCodec.createEncoderByType("audio/mp4a-latm");
                        aEnc.configure(aef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); aEnc.start();
                        srcChannels = af.getInteger(MediaFormat.KEY_CHANNEL_COUNT); outChannels = ch; sampleRate = sr;
                    }
                }
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean exDone = false, decDone = false, encDone = false;
            boolean aExDone = !needAudio, aDecDone = !needAudio || pl.audioCopy, aEncDone = !needAudio;
            ByteBuffer copyBuf = ByteBuffer.allocateDirect(af != null && af.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? Math.max(1 << 16, af.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) : 1 << 20);
            long lastVPts = 0, aPts = 0;
            long lastProg = 0;

            while (!encDone || !aEncDone) {
                if (canceled) throw new InterruptedException("canceled");
                // --- 视频：送入解码器
                if (!exDone) {
                    int ii = dec.dequeueInputBuffer(TO);
                    if (ii >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ii);
                        int n = vEx.readSampleData(b, 0);
                        if (n < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); exDone = true; }
                        else { dec.queueInputBuffer(ii, 0, n, vEx.getSampleTime(), 0); vEx.advance(); }
                    }
                }
                // --- 视频：解码输出 → GL → 编码器
                if (!decDone) {
                    int oi = dec.dequeueOutputBuffer(info, TO);
                    if (oi >= 0) {
                        boolean render = info.size > 0;
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        long pts = info.presentationTimeUs;
                        dec.releaseOutputBuffer(oi, render);
                        if (render) {
                            outS.awaitNewImage(); outS.drawImage();
                            inS.setPresentationTime(pts * 1000); inS.swapBuffers();
                            lastVPts = pts;
                            if (l != null && p.durUs > 0 && System.currentTimeMillis() - lastProg > 200) {
                                lastProg = System.currentTimeMillis(); l.onProgress(Math.min(0.99f, pts / (float) p.durUs));
                            }
                        }
                        if (eos) { enc.signalEndOfInputStream(); decDone = true; }
                    }
                }
                // --- 视频：编码输出 → 复用器
                if (!encDone) {
                    int oi = enc.dequeueOutputBuffer(info, decDone ? TO : 0);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { vTrack = muxer.addTrack(enc.getOutputFormat()); maybeStart(); }
                    else if (oi >= 0) {
                        ByteBuffer b = enc.getOutputBuffer(oi);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                        if (info.size > 0) write(vTrack, b, info);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encDone = true;
                        enc.releaseOutputBuffer(oi, false);
                    }
                }
                // --- 音频（按视频时间轴交错，避免内存堆积）
                boolean audioTurn = encDone || aPts <= lastVPts + 500_000;
                if (needAudio && pl.audioCopy && !aEncDone && audioTurn && muxStarted) {
                    int n = aEx.readSampleData(copyBuf, 0);
                    if (n < 0) { aEncDone = true; }
                    else {
                        MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
                        aPts = aEx.getSampleTime();
                        int fl = (aEx.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        ai.set(0, n, aPts, fl); muxer.writeSampleData(aTrack, copyBuf, ai); aEx.advance();
                    }
                } else if (needAudio && !pl.audioCopy && !aEncDone) {
                    if (!aExDone && audioTurn) {
                        int ii = aDec.dequeueInputBuffer(0);
                        if (ii >= 0) {
                            int n = aEx.readSampleData(aDec.getInputBuffer(ii), 0);
                            if (n < 0) { aDec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); aExDone = true; }
                            else { aPts = aEx.getSampleTime(); aDec.queueInputBuffer(ii, 0, n, aPts, 0); aEx.advance(); }
                        }
                    }
                    pumpAudio(aDec, aEnc);
                    int oi = aEnc.dequeueOutputBuffer(info, 0);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { aTrack = muxer.addTrack(aEnc.getOutputFormat()); maybeStart(); }
                    else if (oi >= 0) {
                        ByteBuffer b = aEnc.getOutputBuffer(oi);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                        if (info.size > 0) write(aTrack, b, info);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) aEncDone = true;
                        aEnc.releaseOutputBuffer(oi, false);
                    }
                }
            }
            if (l != null) l.onProgress(1f);
        } finally {
            rel(dec); rel(enc); rel(aDec); rel(aEnc);
            if (inS != null) inS.release(); if (outS != null) outS.release();
            vEx.release(); if (aEx != null) aEx.release();
            if (muxer != null) { try { if (muxStarted) muxer.stop(); } catch (Exception ignored) {} try { muxer.release(); } catch (Exception ignored) {} }
        }
    }

    // 音频 PCM 搬运：解码器输出 → 编码器输入（可能下混为双声道）
    private int pendIdx = -1, pendOff = 0, srcChannels = 2, outChannels = 2, sampleRate = 44100;
    private final MediaCodec.BufferInfo pendInfo = new MediaCodec.BufferInfo();
    private boolean aDecEosSent;
    private void pumpAudio(MediaCodec aDec, MediaCodec aEnc) {
        if (aDecEosSent) return;
        if (pendIdx < 0) {
            int oi = aDec.dequeueOutputBuffer(pendInfo, 0);
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = aDec.getOutputFormat();
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) srcChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                return;
            }
            if (oi < 0) return;
            pendIdx = oi; pendOff = 0;
        }
        int ii = aEnc.dequeueInputBuffer(0);
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
            else for (int f = 0; f < frames; f++) { // 简单下混：取前两个声道
                for (int c = 0; c < outChannels; c++) dst.putShort(s.getShort((f * srcChannels + Math.min(c, srcChannels - 1)) * 2));
            }
            pendOff += frames * inFrame;
        }
        boolean done = pendInfo.size - pendOff < inFrame;
        aEnc.queueInputBuffer(ii, 0, frames * outFrame, pts, done && eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
        if (done) { aDec.releaseOutputBuffer(pendIdx, false); pendIdx = -1; if (eos) aDecEosSent = true; }
    }

    private void maybeStart() {
        if (muxStarted || vTrack < 0 || (needAudio && aTrack < 0)) return;
        muxer.start(); muxStarted = true;
        for (Object[] o : pending) muxer.writeSampleData((Integer) o[0], (ByteBuffer) o[1], (MediaCodec.BufferInfo) o[2]);
        pending.clear();
    }

    private void write(int track, ByteBuffer b, MediaCodec.BufferInfo info) {
        if (muxStarted) { b.position(info.offset); b.limit(info.offset + info.size); muxer.writeSampleData(track, b, info); return; }
        ByteBuffer c = ByteBuffer.allocateDirect(info.size);
        b.position(info.offset); b.limit(info.offset + info.size); c.put(b); c.flip();
        MediaCodec.BufferInfo i2 = new MediaCodec.BufferInfo(); i2.set(0, info.size, info.presentationTimeUs, info.flags);
        pending.add(new Object[]{track, c, i2});
    }

    private static MediaFormat stripProfile(MediaFormat f) {
        MediaFormat n = MediaFormat.createVideoFormat(f.getString(MediaFormat.KEY_MIME), f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT));
        for (String k : new String[]{MediaFormat.KEY_COLOR_FORMAT, MediaFormat.KEY_BIT_RATE, MediaFormat.KEY_FRAME_RATE, MediaFormat.KEY_I_FRAME_INTERVAL})
            n.setInteger(k, f.getInteger(k));
        return n;
    }

    private static int selectTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void rel(MediaCodec c) {
        if (c == null) return;
        try { c.stop(); } catch (Exception ignored) {}
        try { c.release(); } catch (Exception ignored) {}
    }

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
        final SurfaceTexture st; final Surface surface; final HandlerThread ht;
        private final Object lock = new Object(); private boolean avail;
        private final int w, h, prog, tex, aPos, aTex, uMvp, uSt;
        private final float[] stM = new float[16], mvp = new float[16];
        private final FloatBuffer verts;
        private static final String VS = "uniform mat4 uMVP;uniform mat4 uST;attribute vec4 aPos;attribute vec4 aTex;varying vec2 vTex;" +
                "void main(){gl_Position=uMVP*aPos;vTex=(uST*aTex).xy;}";
        private static final String FS = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 vTex;" +
                "uniform samplerExternalOES sTex;void main(){gl_FragColor=texture2D(sTex,vTex);}";

        OutputSurface(int w, int h) {
            this.w = w; this.h = h;
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
            ht = new HandlerThread("frames"); ht.start();
            st = new SurfaceTexture(tex);
            st.setOnFrameAvailableListener(this, new Handler(ht.getLooper()));
            surface = new Surface(st);
            Matrix.setIdentityM(mvp, 0);
        }
        @Override public void onFrameAvailable(SurfaceTexture s) { synchronized (lock) { avail = true; lock.notifyAll(); } }
        void awaitNewImage() throws InterruptedException {
            synchronized (lock) {
                long end = System.currentTimeMillis() + 5000;
                while (!avail) { long r = end - System.currentTimeMillis(); if (r <= 0) throw new RuntimeException("解码帧等待超时"); lock.wait(r); }
                avail = false;
            }
            st.updateTexImage();
        }
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
        void release() { surface.release(); st.release(); ht.quitSafely(); GLES20.glDeleteProgram(prog); }

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
