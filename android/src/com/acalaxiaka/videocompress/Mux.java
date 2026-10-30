package com.acalaxiaka.videocompress;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** 复用器抽象：默认自研 Mp4Writer（支持 >4GB 单文件），兼容模式使用系统 MediaMuxer。 */
public interface Mux {
    int addTrack(MediaFormat f);
    void setOrientationHint(int deg);
    void start();
    void writeSampleData(int track, ByteBuffer b, MediaCodec.BufferInfo info);
    void stop();
    void release();
    String name();

    final class System implements Mux {
        final MediaMuxer m; System(MediaMuxer m) { this.m = m; }
        public int addTrack(MediaFormat f) { return m.addTrack(f); }
        public void setOrientationHint(int d) { m.setOrientationHint(d); }
        public void start() { m.start(); }
        public void writeSampleData(int t, ByteBuffer b, MediaCodec.BufferInfo i) { m.writeSampleData(t, b, i); }
        public void stop() { m.stop(); }
        public void release() { m.release(); }
        public String name() { return "MediaMuxer(兼容模式)"; }
    }

    final class Mp4 implements Mux {
        final Mp4Writer w; final FileChannel ch;
        Mp4(FileChannel ch) { this.ch = ch; w = new Mp4Writer(ch, false); }
        static byte[] arr(MediaFormat f, String k) {
            if (!f.containsKey(k)) return null;
            ByteBuffer b = f.getByteBuffer(k).duplicate(); byte[] a = new byte[b.remaining()]; b.get(a); return a;
        }
        public int addTrack(MediaFormat f) {
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                byte[] c0 = arr(f, "csd-0");
                if (c0 == null) throw new IllegalArgumentException("视频格式缺少 csd-0（编码配置）");
                return w.addVideoTrack(mime, f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT), c0, arr(f, "csd-1"));
            }
            if (!Mp4Writer.AAC.equals(mime)) throw new IllegalArgumentException("不支持的音频：" + mime);
            byte[] asc = arr(f, "csd-0");
            int sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE), chn = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (asc == null) asc = Mp4Writer.defaultAsc(sr, chn);
            return w.addAudioTrack(sr, chn, asc, f.containsKey(MediaFormat.KEY_BIT_RATE) ? f.getInteger(MediaFormat.KEY_BIT_RATE) : 128000);
        }
        public void setOrientationHint(int d) { w.setRotation(d); }
        public void start() { try { w.start(); } catch (IOException e) { throw new IllegalStateException("写入失败 [E-IO]：" + e.getMessage(), e); } }
        public void writeSampleData(int t, ByteBuffer b, MediaCodec.BufferInfo i) {
            ByteBuffer s = b.duplicate(); s.position(i.offset); s.limit(i.offset + i.size);
            try { w.writeSample(t, s, i.presentationTimeUs, (i.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0); }
            catch (IOException e) { throw new IllegalStateException("写入失败（存储空间不足？）[E-IO]：" + e.getMessage(), e); }
        }
        public void stop() { try { w.finish(); } catch (IOException e) { throw new IllegalStateException("结束写入失败 [E-IO]：" + e.getMessage(), e); } }
        public void release() { try { ch.close(); } catch (IOException ignored) {} }
        public String name() { return "Mp4Writer(64位)"; }
    }
}
