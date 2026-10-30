package com.acalaxiaka.videocompress;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 队列引擎（单例），由前台服务驱动；界面只观察状态。 */
public final class Engine {
    public static final String OUT_DIR = "AngioCompress";
    public static final int WAIT = 0, RUNNING = 1, DONE = 2, SKIPPED = 3, FAILED = 4, CANCELED = 5;

    public static final class Job {
        public Uri uri; public String name; public long size, outSize; public Transcoder.Probe probe; public String error;
        public volatile int state; public volatile String text = ""; public volatile int color = 0xFF333333; public volatile float progress;
    }
    public interface Listener { void onChanged(); }

    public static final List<Job> jobs = new ArrayList<>();
    public static volatile Listener listener;
    public static volatile boolean running; public static volatile String notifText = "";
    public static volatile float notifProgress;
    static Transcoder.Settings settings;
    private static volatile Transcoder current; private static volatile Job currentJob;
    private static volatile boolean stopAll; private static volatile int generation;
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void changed() { Listener l = listener; if (l != null) main.post(l::onChanged); }

    /** 由服务的工作线程调用。 */
    static void runQueue(Context ctx) {
        final int gen = ++generation; running = true; stopAll = false; changed();
        AppLog.i("开始处理队列");
        try {
            List<Job> snapshot; synchronized (jobs) { snapshot = new ArrayList<>(jobs); }
            for (Job j : snapshot) {
                if (stopAll || gen != generation) break;
                if (j.state != WAIT || j.probe == null) continue;
                process(ctx, j, settings, gen);
            }
        } finally {
            if (gen == generation) { running = false; current = null; currentJob = null; changed(); }
            AppLog.i("队列结束");
        }
    }

    /** 取消：立即通知编解码器停止；若 5 秒内工作线程仍未退出，强制将界面复位并放弃该线程。 */
    static void cancel() {
        stopAll = true;
        final Transcoder t = current; final Job j = currentJob; final int gen = generation;
        AppLog.i("用户取消");
        if (t != null) t.abort();
        main.postDelayed(() -> {
            if (gen == generation && running) {
                AppLog.w("工作线程 5 秒内未响应取消，强制复位（诊断：" + (t != null ? t.diag() : "-") + "）");
                generation++;
                if (j != null && j.state == RUNNING) { j.state = CANCELED; j.text = j.name + "\n已取消（强制停止）"; j.color = 0xFF757575; }
                running = false; current = null; currentJob = null; changed();
                VcService.stop();
            }
        }, 5000);
    }

    private static void set(Job j, int gen, String text, int color) {
        if (gen != generation) return; j.text = text; j.color = color; changed();
    }

    private static void process(Context ctx, Job j, Transcoder.Settings s, int gen) {
        Transcoder.Probe p = j.probe; Transcoder.Plan pl;
        try { pl = Transcoder.plan(p, s); } catch (Exception e) { j.state = FAILED; set(j, gen, j.name + "\n❌ " + e.getMessage(), 0xFFC62828); return; }
        if (BitrateCalc.shouldSkip(pl.estBytes, p.size)) {
            j.state = SKIPPED; set(j, gen, j.name + "\n⏭ 已跳过：预计输出 " + BitrateCalc.human(pl.estBytes) + " 不小于原文件 " + BitrateCalc.human(p.size) + "，无需重新编码。", 0xFFEF6C00); return;
        }
        j.state = RUNNING; currentJob = j;
        final String head = j.name + "\n" + CodecSupport.label(pl.mime) + " · " + pl.encName + " · " + pl.w + "×" + pl.h + " · "
                + String.format(Locale.CHINA, "%.2f Mbps", pl.vBps / 1e6) + " · 预计 " + BitrateCalc.human(pl.estBytes);
        set(j, gen, head + "\n准备中…", 0xFF1565C0);
        AppLog.i("开始：" + j.name + " " + p.w + "x" + p.h + " " + String.format(Locale.US, "%.2f", p.fps) + "fps " + p.durUs / 1000 + "ms " + p.size + "B 旋转" + p.rotation
                + " 视频=" + p.vMime + " 音频=" + p.aMime + (pl.audioCopy ? "(复制)" : "(重编码)"));
        final String base = MainActivity.baseName(j.name) + "_" + MainActivity.tag(pl.mime);
        final List<Transcoder.Seg> created = new ArrayList<>();
        Transcoder t = new Transcoder(); current = t;
        final long t0 = System.currentTimeMillis(); final Eta eta = new Eta(60_000);
        try {
            Transcoder.Result r = t.run(ctx, j.uri, p, pl, idx -> { Transcoder.Seg sg = newSeg(ctx, base + (idx == 0 ? "" : "_part" + (idx + 1)) + ".mp4"); created.add(sg); return sg; },
                    prog -> {
                        double e = eta.update(System.currentTimeMillis(), prog);
                        j.progress = prog; notifProgress = prog;
                        notifText = j.name + "  " + Math.round(prog * 100) + "%  剩余 " + Eta.fmt(e);
                        set(j, gen, head + String.format(Locale.CHINA, "\n压缩中… %.1f%%，已用 %s，剩余约 %s", prog * 100, Eta.fmt((System.currentTimeMillis() - t0) / 1000.0), Eta.fmt(e)), 0xFF1565C0);
                    });
            if (gen != generation) { for (Transcoder.Seg sg : created) sg.discard(); return; }
            long out = r.bytes;
            if (!r.partial && out >= p.size) {
                for (Transcoder.Seg sg : created) sg.discard();
                j.state = SKIPPED; set(j, gen, j.name + "\n⏭ 压缩后 " + BitrateCalc.human(out) + " 不小于原文件 " + BitrateCalc.human(p.size) + "，已放弃保存，原视频保持不变。", 0xFFEF6C00);
                return;
            }
            StringBuilder where = new StringBuilder();
            for (int i = 0; i < created.size(); i++) {
                String suf = r.partial ? "_partial" : (created.size() > 1 && i == 0 ? "_part1" : "");
                created.get(i).commit(suf); where.append("\n").append(created.get(i).where());
            }
            j.outSize = out; j.progress = 1;
            double secs = (System.currentTimeMillis() - t0) / 1000.0;
            if (r.partial) {
                j.state = FAILED;
                set(j, gen, j.name + "\n⚠️ " + r.partialReason + "。已保存已完成的部分（" + BitrateCalc.human(out) + "）：" + where + "\n请点击“复制日志”反馈。", 0xFFC62828);
            } else {
                j.state = DONE;
                set(j, gen, String.format(Locale.CHINA, "%s\n✅ 完成：%s → %s（%s）\n%s %d×%d · 用时 %s%s\n已保存：%s", j.name, BitrateCalc.human(p.size), BitrateCalc.human(out),
                        MainActivity.pct(out, p.size), CodecSupport.label(pl.mime), pl.w, pl.h, Eta.fmt(secs), created.size() > 1 ? " · 已分为 " + created.size() + " 段（单个 MP4 限 4GB）" : "", where), 0xFF2E7D32);
            }
            AppLog.i("完成：" + out + "B 用时 " + Math.round(secs) + "s 分段 " + created.size() + (r.partial ? " (partial)" : ""));
        } catch (InterruptedException e) {
            for (Transcoder.Seg sg : created) sg.discard();
            j.state = CANCELED; set(j, gen, j.name + "\n已取消，未保存任何文件", 0xFF757575);
        } catch (Throwable e) {
            for (Transcoder.Seg sg : created) sg.discard();
            AppLog.w("失败：" + e + " | " + t.diag());
            j.state = FAILED; set(j, gen, j.name + "\n❌ 转换失败：" + MainActivity.friendly(e) + "\n请点击“复制日志”反馈。", 0xFFC62828);
        } finally {
            if (gen == generation) { current = null; currentJob = null; }
            changed();
        }
    }

    /** 直接写入 MediaStore（无临时文件，不占双倍空间）。 */
    static Transcoder.Seg newSeg(Context ctx, String name) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name); v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + OUT_DIR); v.put(MediaStore.Video.Media.IS_PENDING, 1);
            final Uri u = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (u == null) throw new IOException("无法在相册创建文件 [E-IO]");
            final ParcelFileDescriptor pfd = cr.openFileDescriptor(u, "rw");
            if (pfd == null) { cr.delete(u, null, null); throw new IOException("无法打开输出文件 [E-IO]"); }
            return new Transcoder.Seg() {
                String finalName = name;
                public MediaMuxer createMuxer() throws IOException { return new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); }
                public void commit(String suffix) throws IOException {
                    try { pfd.close(); } catch (IOException ignored) {}
                    ContentValues c = new ContentValues(); c.put(MediaStore.Video.Media.IS_PENDING, 0);
                    if (!suffix.isEmpty()) { finalName = name.replace(".mp4", suffix + ".mp4"); c.put(MediaStore.Video.Media.DISPLAY_NAME, finalName); }
                    cr.update(u, c, null, null);
                }
                public void discard() { try { pfd.close(); } catch (Exception ignored) {} try { cr.delete(u, null, null); } catch (Exception ignored) {} }
                public String where() { return "Movies/" + OUT_DIR + "/" + finalName; }
            };
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), OUT_DIR); dir.mkdirs();
        File f0 = new File(dir, name); int k = 1;
        while (f0.exists()) f0 = new File(dir, name.replace(".mp4", "(" + (k++) + ").mp4"));
        final File f = f0;
        return new Transcoder.Seg() {
            File cur = f;
            public MediaMuxer createMuxer() throws IOException { return new MediaMuxer(f.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); }
            public void commit(String suffix) {
                if (!suffix.isEmpty()) { File n = new File(dir, f.getName().replace(".mp4", suffix + ".mp4")); if (f.renameTo(n)) cur = n; }
                android.media.MediaScannerConnection.scanFile(ctx, new String[]{cur.getAbsolutePath()}, null, null);
            }
            public void discard() { f.delete(); }
            public String where() { return cur.getAbsolutePath(); }
        };
    }
}
