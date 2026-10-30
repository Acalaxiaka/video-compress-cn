package com.acalaxiaka.videocompress;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** 前台服务：熄屏/切到后台时继续处理，通知栏显示进度并可取消。 */
public class VcService extends Service {
    static final String CH = "vc_progress", ACTION_CANCEL = "com.acalaxiaka.videocompress.CANCEL";
    static final int NID = 1;
    private static volatile VcService instance;
    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wl; private Thread worker;

    static void start(Context c) {
        Intent i = new Intent(c, VcService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    static void stop() { VcService s = instance; if (s != null) s.h.post(s::finish); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        instance = this;
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) { Engine.cancel(); return START_NOT_STICKY; }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "压缩进度", NotificationManager.IMPORTANCE_LOW));
        Notification n = build("准备中…", 0);
        if (Build.VERSION.SDK_INT >= 35) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        else if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NID, n);
        if (worker == null || !worker.isAlive() || !Engine.running) {
            try { wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vc:job"); wl.acquire(8 * 3600_000L); } catch (Exception ignored) {}
            worker = new Thread(() -> { try { Engine.runQueue(getApplicationContext()); } finally { h.post(this::finish); } }, "vc-queue");
            worker.start();
            tick();
        }
        return START_NOT_STICKY;
    }

    private void tick() {
        if (instance != this) return;
        if (Engine.running) getSystemService(NotificationManager.class).notify(NID, build(Engine.notifText.isEmpty() ? "处理中…" : Engine.notifText, Engine.notifProgress));
        h.postDelayed(this::tick, 1000);
    }

    private Notification build(String text, float p) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, VcService.class).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("视频压缩中").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(open).setProgress(1000, Math.round(p * 1000), p <= 0).addAction(new Notification.Action.Builder(null, "取消", cancel).build());
        return b.build();
    }

    private void finish() {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf(); instance = null;
    }

    @Override public void onTimeout(int startId, int fgsType) { // Android 15：dataSync/mediaProcessing 每日 6 小时上限
        AppLog.w("系统前台服务时限到达，停止处理");
        Engine.cancel(); finish();
    }
}
