package com.acalaxiaka.videocompress;

/** 纯逻辑：码率/分辨率/体积估算（无 Android 依赖，可单元测试）。 */
public final class BitrateCalc {
    public static final int HIGH = 0, BALANCED = 1, SMALL = 2, CUSTOM = 3;
    public static final String HEVC = "video/hevc", AVC = "video/avc", AV1 = "video/av01";
    public static final long MIN_VIDEO_BPS = 250_000L;

    private BitrateCalc() {}

    /** 以 HEVC 为基准的每像素比特数（bits per pixel per frame）。 */
    public static double baseBpp(int preset) {
        switch (preset) {
            case HIGH: return 0.10;     // 近视觉无损
            case SMALL: return 0.065 * 0.7; // 小体积：均衡的 70%（≈0.0455，v1.0.3 为 0.035）
            default: return 0.065;      // 均衡（0.05–0.08 区间中位）
        }
    }

    /** 编码效率换算：H.264 需要更多码率，AV1 更少。 */
    public static double codecFactor(String mime) {
        if (AVC.equals(mime)) return 1.45;
        if (AV1.equals(mime)) return 0.75;
        return 1.0;
    }

    /** 自定义“画质”滑块 1..100 → HEVC 基准 bpp 0.02..0.15。 */
    public static double qualityToBpp(int q) {
        if (q < 1) q = 1; if (q > 100) q = 100;
        return 0.02 + (0.15 - 0.02) * (q - 1) / 99.0;
    }

    /** 高帧率的边际码率需求递减：30fps 以上按 0.6 计。 */
    public static double effectiveFps(double fps) {
        if (!(fps > 0)) fps = 30;
        return fps <= 30 ? fps : 30 + (fps - 30) * 0.6;
    }

    /** 目标尺寸：maxShort<=0 表示保持；绝不放大；宽高对齐为偶数。 */
    public static int[] targetSize(int w, int h, int maxShort) {
        int s = Math.min(w, h);
        if (maxShort <= 0 || s <= maxShort) return new int[]{even(w), even(h)};
        double k = maxShort / (double) s;
        int nw = (int) Math.round(w * k), nh = (int) Math.round(h * k);
        if (w <= h) nw = maxShort; else nh = maxShort;
        return new int[]{even(nw), even(nh)};
    }

    static int even(int v) { v = v & ~1; return Math.max(v, 2); }

    /**
     * 视频码率。
     * @param bpp HEVC 基准 bpp（由预设或自定义滑块得到）
     * @param srcVideoBps 源视频码率（未知传 0），按像素比例换算后作为上限，避免“越压越大”
     */
    public static long videoBitrate(String mime, double bpp, int outW, int outH, double fps,
                                    int srcW, int srcH, long srcVideoBps, boolean high) {
        double b = (double) outW * outH * effectiveFps(fps) * bpp * codecFactor(mime);
        if (srcVideoBps > 0 && srcW > 0 && srcH > 0) {
            double pixRatio = Math.min(1.0, (double) outW * outH / ((double) srcW * srcH));
            double cap = srcVideoBps * pixRatio * (high ? 0.9 : 0.75);
            if (b > cap) b = cap;
        }
        return Math.max(MIN_VIDEO_BPS, Math.round(b));
    }

    public static long estimateBytes(long videoBps, long audioBps, long durationUs) {
        if (durationUs <= 0) return 0;
        double sec = durationUs / 1e6;
        return Math.round((videoBps + audioBps) * sec / 8.0 * 1.02); // +2% 容器开销
    }

    /** 估算体积 ≥ 源文件的 95% 时不值得重新编码。 */
    public static boolean shouldSkip(long estimateBytes, long srcBytes) {
        return srcBytes > 0 && estimateBytes >= srcBytes * 0.95;
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0; String[] u = {"KB", "MB", "GB"}; int i = 0;
        while (v >= 1024 && i < 2) { v /= 1024; i++; }
        return String.format(java.util.Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }
}
