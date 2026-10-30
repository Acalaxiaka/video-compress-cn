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

    private final java.util.Map<Engine.Job, TextView> tvs = new java.util.HashMap<>();
    private final java.util.Map<Engine.Job, ProgressBar> pbs = new java.util.HashMap<>();
    private TextView tvLog;
    private static final List<Engine.Job> jobs = Engine.jobs;
    private LinearLayout jobList, customPanel;
    private RadioGroup presetGroup;
    private Spinner spCodec, spRes, spAudio;
    private SeekBar sbQuality; private TextView tvQuality, tvSummary;
    private EditText etBitrate; private CheckBox cbCompat;
    private Button btnStart, btnCancel, btnPick, btnClear;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        for (Engine.Job j : jobs) addRow(j);
        Engine.listener = this::render; AppLog.listener = () -> runOnUiThread(this::renderLog);
        render(); renderLog();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
        handleShare(getIntent());
    }

    @Override protected void onDestroy() { super.onDestroy(); if (Engine.listener != null) Engine.listener = null; AppLog.listener = null; }

    private void render() {
        for (Engine.Job j : jobs) {
            TextView tv = tvs.get(j); ProgressBar pb = pbs.get(j); if (tv == null) continue;
            if (j.state != Engine.WAIT || j.text.isEmpty()) { if (!j.text.isEmpty()) { tv.setText(j.text); tv.setTextColor(j.color); } }
            pb.setProgress(Math.round(j.progress * 1000));
        }
        boolean r = Engine.running;
        btnStart.setEnabled(!r); btnCancel.setEnabled(r); btnPick.setEnabled(!r); btnClear.setEnabled(!r);
        if (r) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateSummary();
    }

    private void renderLog() { if (tvLog != null) tvLog.setText(AppLog.tail(logExpanded ? 300 : 25)); }
    private boolean logExpanded;

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
        String[] presets = {"高画质（接近视觉无损）", "均衡（推荐）", "小体积（约均衡 70% 码率）", "自定义"};
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
        cbCompat = new CheckBox(this); cbCompat.setText("兼容模式：使用系统 MediaMuxer（单文件 ≤4GB，仅在默认写入器出错时使用）");
        cbCompat.setOnCheckedChangeListener((b2, c) -> refreshEstimates());
        customPanel.addView(cbCompat);
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
        btnClear.setOnClickListener(v -> { if (!Engine.running) { synchronized (jobs) { jobs.clear(); } tvs.clear(); pbs.clear(); jobList.removeAllViews(); updateSummary(); } });

        tvSummary = label("尚未选择视频", 14, true); root.addView(tvSummary);
        jobList = new LinearLayout(this); jobList.setOrientation(LinearLayout.VERTICAL); root.addView(jobList);

        root.addView(label("诊断日志", 16, true));
        LinearLayout lrow = new LinearLayout(this);
        Button bCopy = new Button(this); bCopy.setText("复制日志");
        Button bMore = new Button(this); bMore.setText("展开/收起");
        lrow.addView(bCopy, new LinearLayout.LayoutParams(0, -2, 1)); lrow.addView(bMore, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(lrow);
        tvLog = new TextView(this); tvLog.setTextSize(11); tvLog.setTypeface(Typeface.MONOSPACE); tvLog.setTextIsSelectable(true);
        tvLog.setBackgroundColor(0xFF263238); tvLog.setTextColor(0xFFECEFF1); tvLog.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(tvLog);
        bCopy.setOnClickListener(v -> {
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("VideoCompress 日志", AppLog.dump()));
            toast("日志已复制，可粘贴发送");
        });
        bMore.setOnClickListener(v -> { logExpanded = !logExpanded; renderLog(); });
        root.addView(label("说明：若预计输出不小于原文件，将自动跳过并提示；压缩后若实际体积反而更大，也不会保存。AVI 等系统不支持的格式会提示错误。处理在前台服务中进行，熄屏或切换应用也会继续，通知栏可查看进度和取消。输出使用自研 64 位 MP4 写入器，超过 4 GB 也保存为单个文件。", 12, false));
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
            Engine.Job j = new Engine.Job(); j.uri = u; j.name = "video"; j.size = -1;
            if ("file".equals(u.getScheme())) { File f = new File(u.getPath()); j.name = f.getName(); j.size = f.length(); }
            try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0) j.name = c.getString(ni); if (si >= 0 && !c.isNull(si)) j.size = c.getLong(si);
                }
            } catch (Exception ignored) {}
            synchronized (jobs) { jobs.add(j); } addRow(j);
            new Thread(() -> {
                try { j.probe = Transcoder.probe(this, j.uri, j.size); }
                catch (Transcoder.TranscodeException e) { j.error = e.getMessage(); j.state = Engine.FAILED; }
                catch (Exception e) { j.error = "读取失败：" + e.getMessage(); j.state = Engine.FAILED; }
                runOnUiThread(this::refreshEstimates);
            }).start();
        }
        updateSummary();
    }

    private void addRow(Engine.Job j) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.setBackgroundColor(0xFFFAFAFA);
        TextView tv = new TextView(this); tv.setTextSize(13); tv.setTextColor(0xFF333333); tv.setText(j.text.isEmpty() ? j.name + "\n正在读取信息…" : j.text);
        tv.setTextIsSelectable(true);
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(1000);
        box.addView(tv); box.addView(pb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(6);
        jobList.addView(box, lp); tvs.put(j, tv); pbs.put(j, pb);
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
            s.compat = cbCompat.isChecked();
            int a = spAudio.getSelectedItemPosition(); s.audioBps = new int[]{-1, 192_000, 128_000, 96_000, 64_000}[a];
        } else if (s.preset == BitrateCalc.SMALL) { s.audioBps = 96_000; } // 分辨率/帧率与均衡一致，仅码率为均衡的 70%
        return s;
    }

    private void refreshEstimates() {
        if (presetGroup == null) return;
        Transcoder.Settings s = settings();
        for (Engine.Job j : jobs) {
            TextView jtv = tvs.get(j); if (jtv == null) continue;
            if (j.state != Engine.WAIT && j.state != Engine.FAILED) continue;
            if (j.state == Engine.FAILED) { if (j.text.isEmpty()) { j.text = j.name + "\n❌ " + j.error; j.color = 0xFFC62828; } jtv.setText(j.text); jtv.setTextColor(j.color); continue; }
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
            jtv.setText(info);
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
        for (Engine.Job j : jobs) if (j.state == Engine.DONE) { done++; in += j.size; out += j.outSize; }
        String s = "队列：" + jobs.size() + " 个";
        if (done > 0) s += String.format(Locale.CHINA, "；已完成 %d 个：%s → %s（%s）", done, BitrateCalc.human(in), BitrateCalc.human(out), pct(out, in));
        tvSummary.setText(s);
    }

    private void start() {
        if (Engine.running) return;
        boolean any = false; for (Engine.Job j : jobs) if (j.state == Engine.WAIT && j.probe != null) any = true;
        if (!any) { toast("请先选择视频（或等待读取完成）"); return; }
        Engine.settings = settings();
        AppLog.i("预设=" + Engine.settings.preset + " 编码=" + Engine.settings.codec + " 短边=" + Engine.settings.maxShort);
        VcService.start(this);
        btnStart.setEnabled(false); btnCancel.setEnabled(true); btnPick.setEnabled(false);
    }

    private void cancel() {
        Engine.cancel(); toast("正在取消…（最多 5 秒）");
    }

    static String friendly(Throwable e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof IllegalStateException || e instanceof android.media.MediaCodec.CodecException)
            return "编解码器出错（可能该编码/分辨率不被硬件支持，可尝试自定义中改用 H.264 或 720p）。" + m;
        return m;
    }

    static String tag(String mime) { return BitrateCalc.HEVC.equals(mime) ? "hevc" : BitrateCalc.AV1.equals(mime) ? "av1" : "h264"; }
    static String baseName(String n) { int i = n.lastIndexOf('.'); return (i > 0 ? n.substring(0, i) : n).replaceAll("[\\\\/:*?\"<>|]", "_"); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
