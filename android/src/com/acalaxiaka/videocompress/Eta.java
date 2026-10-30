package com.acalaxiaka.videocompress;

import java.util.ArrayDeque;

/** 剩余时间估算：滑动窗口（默认 60 秒）内的处理速度 + 指数平滑；纯 Java，可单元测试。 */
public final class Eta {
    private final ArrayDeque<double[]> win = new ArrayDeque<>();
    private final long windowMs; private double smoothed = -1; private long startMs = -1;
    public Eta(long windowMs) { this.windowMs = windowMs; }

    /** @return 剩余秒数，数据不足时返回 -1 */
    public double update(long nowMs, double progress) {
        if (startMs < 0) startMs = nowMs;
        win.add(new double[]{nowMs, progress});
        while (win.size() > 2 && nowMs - win.peekFirst()[0] > windowMs) win.pollFirst();
        double[] a = win.peekFirst();
        double dt = (nowMs - a[0]) / 1000.0, dp = progress - a[1];
        if (dt < 3 || dp <= 0) return smoothed;
        double rate = dp / dt;                       // 进度/秒（窗口内）
        double totalRate = progress / Math.max(1e-3, (nowMs - startMs) / 1000.0);
        double r = 0.9 * rate + 0.1 * totalRate;     // 以近期速度为主，兼顾整体
        double raw = (1 - progress) / r;
        smoothed = smoothed < 0 ? raw : 0.7 * smoothed + 0.3 * raw;
        return smoothed;
    }

    public static String fmt(double sec) {
        if (sec < 0) return "估算中";
        long s = Math.round(sec);
        if (s >= 3600) return String.format(java.util.Locale.CHINA, "%d 小时 %02d 分", s / 3600, (s % 3600) / 60);
        if (s >= 60) return String.format(java.util.Locale.CHINA, "%d 分 %02d 秒", s / 60, s % 60);
        return s + " 秒";
    }
}
