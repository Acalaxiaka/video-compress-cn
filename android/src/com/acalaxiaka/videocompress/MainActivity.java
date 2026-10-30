package com.acalaxiaka.videocompress;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    static final int REQ_PICK = 1;
    static final String OUT_DIR = "AngioCompress";

    static final class Job {
        Uri uri; String name; long size; Transcoder.Probe probe; String error;
        long outSize; TextView tv; ProgressBar pb; volatile int state; // 0待处理 1进行中 2完成 3跳过 4失败 5取消
    }

    private final List<Job> jobs = new ArrayList<>();
    private LinearLayout jobList, customPanel;
    private RadioGroup presetGroup;
    private Spinner spCodec, spRes, spAudio;
    private SeekBar sbQuality; private TextView tvQuality, tvSummary;
    private EditText etBitrate;
    private Button btnStart, btnCancel, btnPick, btnClear;
    private volatile Transcoder current; private volatile boolean stopAll; private Thread worker;
    private PowerManager.WakeLock wl;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        handleShare(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleShare(i); }

    private void handleShare(Intent i) {
        if (i == null) return;
        List<Uri> l = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(i.getAction())) { Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM); if (u != null) l.add(u); }
        else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) { ArrayList<Uri> a = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (a != null) l.addAll(a); }
        // 测试钩子：仅接受本应用私有外部目录中的文件（adb 自动化测试用）
        String[] dbg = i.getStringArrayExtra("debug_files");
        File own = getExternalFilesDir(null);
        if (dbg != null && own != null) for (String f : dbg) { File ff = new File(own, new File(f).getName()); if (ff.isFile()) l.add(Uri.fromFile(ff)); }
        if (dbg != null) { String pr = i.getStringExtra("debug_preset"); if (pr != null) presetGroup.check(100 + Integer.parseInt(pr)); }
        if (!l.isEmpty()) addUris(l, false);
        if (dbg != null && i.getBooleanExtra("debug_autostart", false)) jobList.postDelayed(this::start, 4000);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView label(String s, int sp, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(0xFF212121);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); t.setPadding(0, dp(8), 0, dp(4)); return t;
    }

    private Spinner spinner(String[] items, int sel) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); s.setSelection(sel);
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { refreshEstimates(); }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        return s;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24)); sv.addView(root);

        TextView title = label("视频压缩 · 硬件加速", 22, true); title.setTextColor(0xFF1565C0); root.addView(title);
        root.addView(label("使用手机硬件编码器（优先 HEVC/H.265，H.264 兜底，支持时用 AV1）。原视频不会被修改，输出保存到 Movies/" + OUT_DIR + "。", 13, false));

        root.addView(label("本机编码器支持", 16, true));
        TextView codecs = label("检测中…", 13, false); codecs.setBackgroundColor(0xFFF1F5FB); codecs.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(codecs);
        new Thread(() -> { String r; try { r = CodecSupport.report(); } catch (Exception e) { r = "检测失败：" + e; } final String fr = r; runOnUiThread(() -> codecs.setText(fr)); }).start();

        root.addView(label("压缩预设", 16, true));
        presetGroup = new RadioGroup(this);
        String[] presets = {"高画质（接近视觉无损）", "均衡（推荐）", "小体积", "自定义"};
        for (int i = 0; i < presets.length; i++) { RadioButton rb = new RadioButton(this); rb.setText(presets[i]); rb.setId(100 + i); presetGroup.addView(rb); }
        presetGroup.check(101);
        presetGroup.setOnCheckedChangeListener((g, id) -> { customPanel.setVisibility(id == 103 ? View.VISIBLE : View.GONE); refreshEstimates(); });
        root.addView(presetGroup);

        customPanel = new LinearLayout(this); customPanel.setOrientation(LinearLayout.VERTICAL); customPanel.setVisibility(View.GONE);
        customPanel.setPadding(dp(12), 0, 0, 0);
        customPanel.addView(label("编码格式", 14, false));
        spCodec = spinner(new String[]{"自动（AV1硬件 > HEVC > H.264）", "HEVC / H.265", "H.264 / AVC", "AV1（需硬件支持）"}, 0); customPanel.addView(spCodec);
        customPanel.addView(label("分辨率（不会放大）", 14, false));
        spRes = spinner(new String[]{"保持原始", "1080p", "720p"}, 0); customPanel.addView(spRes);
        customPanel.addView(label("帧率：保持原始帧率", 14, false));
        tvQuality = label("画质：50", 14, false); customPanel.addView(tvQuality);
        sbQuality = new SeekBar(this); sbQuality.setMax(99); sbQuality.setProgress(49);
        sbQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { tvQuality.setText("画质：" + (p + 1) + "（数值越大画质越高、文件越大）"); refreshEstimates(); }
            public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {}
        });
        customPanel.addView(sbQuality);
        customPanel.addView(label("或指定视频码率 (Mbps，留空则按画质)", 14, false));
        etBitrate = new EditText(this); etBitrate.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); etBitrate.setHint("例如 4");
        etBitrate.setOnFocusChangeListener((v, f) -> { if (!f) refreshEstimates(); });
        customPanel.addView(etBitrate);
        customPanel.addView(label("音频", 14, false));
        spAudio = spinner(new String[]{"直接复制（AAC 源）", "AAC 192 kbps", "AAC 128 kbps", "AAC 96 kbps", "AAC 64 kbps"}, 0); customPanel.addView(spAudio);
        root.addView(customPanel);

        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(0, dp(12), 0, dp(4));
        btnPick = new Button(this); btnPick.setText("选择视频");
        btnStart = new Button(this); btnStart.setText("开始压缩");
        btnCancel = new Button(this); btnCancel.setText("取消"); btnCancel.setEnabled(false);
        btnClear = new Button(this); btnClear.setText("清空");
        for (Button x : new Button[]{btnPick, btnStart, btnCancel, btnClear}) row.addView(x, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row);
        btnPick.setOnClickListener(v -> pick());
        btnStart.setOnClickListener(v -> start());
        btnCancel.setOnClickListener(v -> cancel());
        btnClear.setOnClickListener(v -> { if (worker == null) { jobs.clear(); jobList.removeAllViews(); updateSummary(); } });

        tvSummary = label("尚未选择视频", 14, true); root.addView(tvSummary);
        jobList = new LinearLayout(this); jobList.setOrientation(LinearLayout.VERTICAL); root.addView(jobList);

        root.addView(label("说明：若预计输出不小于原文件，将自动跳过并提示；压缩后若实际体积反而更大，也不会保存。AVI 等系统不支持的格式会提示错误。处理时请保持本页面在前台。", 12, false));
        setContentView(sv);
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("video/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_PICK); } catch (Exception e) { toast("无法打开文件选择器"); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        List<Uri> l = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) l.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) l.add(data.getData());
        addUris(l, true);
    }

    private void addUris(List<Uri> uris, boolean persist) {
        for (Uri u : uris) {
            if (persist) try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            Job j = new Job(); j.uri = u; j.name = "video"; j.size = -1;
            if ("file".equals(u.getScheme())) { File f = new File(u.getPath()); j.name = f.getName(); j.size = f.length(); }
            try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0) j.name = c.getString(ni); if (si >= 0 && !c.isNull(si)) j.size = c.getLong(si);
                }
            } catch (Exception ignored) {}
            LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(8), dp(8), dp(8), dp(8));
            box.setBackgroundColor(0xFFFAFAFA);
            j.tv = new TextView(this); j.tv.setTextSize(13); j.tv.setTextColor(0xFF333333); j.tv.setText(j.name + "\n正在读取信息…");
            j.pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); j.pb.setMax(1000);
            box.addView(j.tv); box.addView(j.pb);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(6);
            jobList.addView(box, lp);
            jobs.add(j);
            new Thread(() -> {
                try { j.probe = Transcoder.probe(this, j.uri, j.size); }
                catch (Transcoder.TranscodeException e) { j.error = e.getMessage(); j.state = 4; }
                catch (Exception e) { j.error = "读取失败：" + e.getMessage(); j.state = 4; }
                runOnUiThread(this::refreshEstimates);
            }).start();
        }
        updateSummary();
    }

    private Transcoder.Settings settings() {
        Transcoder.Settings s = new Transcoder.Settings();
        s.preset = presetGroup.getCheckedRadioButtonId() - 100;
        if (s.preset == BitrateCalc.CUSTOM) {
            int c = spCodec.getSelectedItemPosition();
            s.codec = c == 1 ? BitrateCalc.HEVC : c == 2 ? BitrateCalc.AVC : c == 3 ? BitrateCalc.AV1 : null;
            int r = spRes.getSelectedItemPosition(); s.maxShort = r == 1 ? 1080 : r == 2 ? 720 : 0;
            s.quality = sbQuality.getProgress() + 1;
            try { String t = etBitrate.getText().toString().trim(); if (!t.isEmpty()) s.customBps = (long) (Double.parseDouble(t) * 1_000_000); } catch (Exception ignored) {}
            int a = spAudio.getSelectedItemPosition(); s.audioBps = new int[]{-1, 192_000, 128_000, 96_000, 64_000}[a];
        } else if (s.preset == BitrateCalc.SMALL) { s.maxShort = 1080; s.audioBps = 96_000; }
        return s;
    }

    private void refreshEstimates() {
        if (presetGroup == null) return;
        Transcoder.Settings s = settings();
        for (Job j : jobs) {
            if (j.state != 0 && j.state != 4) continue;
            if (j.state == 4) { j.tv.setText(j.name + "\n❌ " + j.error); j.tv.setTextColor(0xFFC62828); continue; }
            if (j.probe == null) continue;
            Transcoder.Probe p = j.probe;
            String info = String.format(Locale.CHINA, "%s\n%d×%d · %.2f fps · %s · %s · 旋转 %d°", j.name, p.w, p.h, p.fps,
                    fmtDur(p.durUs), BitrateCalc.human(p.size), p.rotation);
            try {
                Transcoder.Plan pl = Transcoder.plan(p, s);
                info += String.format(Locale.CHINA, "\n→ %s %d×%d · %.2f Mbps · 预计 %s (%s)", CodecSupport.label(pl.mime), pl.w, pl.h, pl.vBps / 1e6,
                        BitrateCalc.human(pl.estBytes), pct(pl.estBytes, p.size));
                if (BitrateCalc.shouldSkip(pl.estBytes, p.size)) info += "\n⚠️ 预计不会变小，将跳过";
            } catch (Exception e) { info += "\n⚠️ " + e.getMessage(); }
            j.tv.setText(info);
        }
        updateSummary();
    }

    static String pct(long out, long in) {
        if (in <= 0) return "?";
        return String.format(Locale.CHINA, "%.0f%%，缩小 %.1f 倍", out * 100.0 / in, in / (double) Math.max(1, out));
    }
    static String fmtDur(long us) { long s = us / 1_000_000; return String.format(Locale.CHINA, "%d:%02d", s / 60, s % 60); }

    private void updateSummary() {
        long in = 0, out = 0; int done = 0;
        for (Job j : jobs) if (j.state == 2) { done++; in += j.size; out += j.outSize; }
        String s = "队列：" + jobs.size() + " 个";
        if (done > 0) s += String.format(Locale.CHINA, "；已完成 %d 个：%s → %s（%s）", done, BitrateCalc.human(in), BitrateCalc.human(out), pct(out, in));
        tvSummary.setText(s);
    }

    private void start() {
        if (worker != null) return;
        boolean any = false; for (Job j : jobs) if (j.state == 0 && j.probe != null) any = true;
        if (!any) { toast("请先选择视频"); return; }
        final Transcoder.Settings s = settings();
        stopAll = false; btnStart.setEnabled(false); btnCancel.setEnabled(true); btnPick.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vc:job"); wl.acquire(6 * 3600_000L); } catch (Exception ignored) {}
        worker = new Thread(() -> {
            for (Job j : jobs) {
                if (stopAll) break;
                if (j.state != 0 || j.probe == null) continue;
                process(j, s);
            }
            runOnUiThread(() -> {
                worker = null; btnStart.setEnabled(true); btnCancel.setEnabled(false); btnPick.setEnabled(true);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) {}
                updateSummary(); toast("队列处理结束");
            });
        });
        worker.start();
    }

    private void cancel() {
        stopAll = true; Transcoder t = current; if (t != null) t.cancel();
        toast("正在取消…");
    }

    private void ui(Job j, String text, int color) { runOnUiThread(() -> { j.tv.setText(text); j.tv.setTextColor(color); }); }

    private void process(Job j, Transcoder.Settings s) {
        Transcoder.Probe p = j.probe;
        Transcoder.Plan pl;
        try { pl = Transcoder.plan(p, s); } catch (Exception e) { j.state = 4; ui(j, j.name + "\n❌ " + e.getMessage(), 0xFFC62828); return; }
        if (BitrateCalc.shouldSkip(pl.estBytes, p.size)) {
            j.state = 3; ui(j, j.name + "\n⏭ 已跳过：预计输出 " + BitrateCalc.human(pl.estBytes) + " 不小于原文件 " + BitrateCalc.human(p.size) + "，无需重新编码。", 0xFFEF6C00); return;
        }
        j.state = 1;
        final String head = j.name + "\n" + CodecSupport.label(pl.mime) + " · " + pl.encName + " · " + pl.w + "×" + pl.h + " · "
                + String.format(Locale.CHINA, "%.2f Mbps", pl.vBps / 1e6) + " · 预计 " + BitrateCalc.human(pl.estBytes);
        ui(j, head + "\n压缩中… 0%", 0xFF1565C0);
        File tmp = new File(getCacheDir(), "out_" + System.nanoTime() + ".mp4");
        Transcoder t = new Transcoder(); current = t;
        long t0 = System.currentTimeMillis();
        try {
            t.run(this, j.uri, p, pl, tmp, prog -> runOnUiThread(() -> {
                j.pb.setProgress((int) (prog * 1000));
                long el = System.currentTimeMillis() - t0;
                String eta = prog > 0.02 ? "，剩余约 " + Math.max(1, Math.round(el * (1 - prog) / prog / 1000)) + " 秒" : "";
                j.tv.setText(head + String.format(Locale.CHINA, "\n压缩中… %.0f%%%s", prog * 100, eta));
            }));
            long outSize = tmp.length();
            if (outSize <= 0) throw new IOException("输出为空");
            if (outSize >= p.size) {
                j.state = 3; ui(j, j.name + "\n⏭ 压缩后 " + BitrateCalc.human(outSize) + " 不小于原文件 " + BitrateCalc.human(p.size) + "，已放弃保存，原视频保持不变。", 0xFFEF6C00);
                return;
            }
            String outName = baseName(j.name) + "_" + tag(pl.mime) + ".mp4";
            String where = save(tmp, outName);
            j.outSize = outSize; j.state = 2;
            double secs = (System.currentTimeMillis() - t0) / 1000.0;
            ui(j, String.format(Locale.CHINA, "%s\n✅ 完成：%s → %s（%s）\n%s %d×%d · 用时 %.0f 秒\n已保存：%s", j.name,
                    BitrateCalc.human(p.size), BitrateCalc.human(outSize), pct(outSize, p.size), CodecSupport.label(pl.mime), pl.w, pl.h, secs, where), 0xFF2E7D32);
            runOnUiThread(() -> j.pb.setProgress(1000));
        } catch (InterruptedException e) {
            j.state = 5; ui(j, j.name + "\n已取消", 0xFF757575);
        } catch (Throwable e) {
            j.state = 4; ui(j, j.name + "\n❌ 转换失败：" + friendly(e), 0xFFC62828);
        } finally {
            current = null; tmp.delete();
            runOnUiThread(this::updateSummary);
        }
    }

    static String friendly(Throwable e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof IllegalStateException || e instanceof android.media.MediaCodec.CodecException)
            return "编解码器出错（可能该编码/分辨率不被硬件支持，可尝试自定义中改用 H.264 或 720p）。" + m;
        return m;
    }

    static String tag(String mime) { return BitrateCalc.HEVC.equals(mime) ? "hevc" : BitrateCalc.AV1.equals(mime) ? "av1" : "h264"; }
    static String baseName(String n) { int i = n.lastIndexOf('.'); return (i > 0 ? n.substring(0, i) : n).replaceAll("[\\\\/:*?\"<>|]", "_"); }

    private String save(File src, String name) throws IOException {
        ContentResolver cr = getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + OUT_DIR);
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri u = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (u == null) throw new IOException("无法在相册创建文件");
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(u)) { copy(in, out); }
            catch (IOException e) { cr.delete(u, null, null); throw e; }
            v.clear(); v.put(MediaStore.Video.Media.IS_PENDING, 0); cr.update(u, v, null, null);
            return "Movies/" + OUT_DIR + "/" + name;
        } else {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2));
                throw new IOException("需要存储权限，请授权后重试");
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), OUT_DIR); dir.mkdirs();
            File f = new File(dir, name); int k = 1;
            while (f.exists()) f = new File(dir, baseName(name) + "(" + (k++) + ").mp4");
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(f)) { copy(in, out); }
            android.media.MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
            return f.getAbsolutePath();
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
