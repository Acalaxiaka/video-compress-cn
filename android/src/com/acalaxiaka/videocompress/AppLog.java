package com.acalaxiaka.videocompress;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** 应用内诊断日志（环形缓冲 600 行），界面可查看/复制。 */
public final class AppLog {
    public interface L { void onLog(); }
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat F = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    public static volatile L listener;
    public static void i(String s) { add("I", s); }
    public static void w(String s) { add("W", s); }
    private static void add(String lv, String s) {
        android.util.Log.println(lv.equals("W") ? android.util.Log.WARN : android.util.Log.INFO, "VideoCompress", s);
        synchronized (lines) { lines.add(F.format(new Date()) + " " + lv + " " + s); while (lines.size() > 600) lines.poll(); }
        L l = listener; if (l != null) l.onLog();
    }
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("VideoCompress ").append(BuildInfo.VERSION).append(" | ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
          .append(" | Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        synchronized (lines) { for (String s : lines) sb.append(s).append('\n'); }
        return sb.toString();
    }
    public static String tail(int n) {
        synchronized (lines) { StringBuilder sb = new StringBuilder(); int skip = Math.max(0, lines.size() - n), k = 0;
            for (String s : lines) if (k++ >= skip) sb.append(s).append('\n'); return sb.toString(); }
    }
}
