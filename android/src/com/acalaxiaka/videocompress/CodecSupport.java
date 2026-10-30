package com.acalaxiaka.videocompress;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

/** 通过 MediaCodecList 枚举设备编码器。 */
public final class CodecSupport {
    public static final class Enc {
        public final String name, mime; public final boolean hw; public final MediaCodecInfo info;
        Enc(String n, String m, boolean h, MediaCodecInfo i) { name = n; mime = m; hw = h; info = i; }
    }

    public static boolean isHardware(MediaCodecInfo i) {
        if (Build.VERSION.SDK_INT >= 29) return i.isHardwareAccelerated() && !i.isSoftwareOnly();
        String n = i.getName().toLowerCase();
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.startsWith("omx.ffmpeg"));
    }

    /** 某 MIME 的编码器列表，硬件优先。 */
    public static List<Enc> encoders(String mime) {
        List<Enc> hw = new ArrayList<>(), sw = new ArrayList<>();
        for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!i.isEncoder()) continue;
            if (Build.VERSION.SDK_INT >= 29 && i.isAlias()) continue;
            for (String t : i.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) {
                boolean h = isHardware(i); (h ? hw : sw).add(new Enc(i.getName(), mime, h, i));
            }
        }
        hw.addAll(sw); return hw;
    }

    public static Enc bestHw(String mime) {
        for (Enc e : encoders(mime)) if (e.hw) return e; return null;
    }
    public static Enc best(String mime) { List<Enc> l = encoders(mime); return l.isEmpty() ? null : l.get(0); }

    public static boolean sizeOk(Enc e, int w, int h) {
        try { return e.info.getCapabilitiesForType(e.mime).getVideoCapabilities().isSizeSupported(w, h); }
        catch (Exception ex) { return false; }
    }

    public static String label(String mime) {
        if (BitrateCalc.HEVC.equals(mime)) return "HEVC/H.265";
        if (BitrateCalc.AV1.equals(mime)) return "AV1";
        return "H.264/AVC";
    }

    public static String report() {
        StringBuilder sb = new StringBuilder();
        for (String m : new String[]{BitrateCalc.HEVC, BitrateCalc.AVC, BitrateCalc.AV1}) {
            List<Enc> l = encoders(m);
            Enc h = null; for (Enc e : l) if (e.hw) { h = e; break; }
            sb.append(label(m)).append("：");
            if (h != null) {
                sb.append("✅ 硬件编码 (").append(h.name);
                try {
                    MediaCodecInfo.VideoCapabilities vc = h.info.getCapabilitiesForType(m).getVideoCapabilities();
                    sb.append("，最大 ").append(vc.getSupportedWidths().getUpper()).append("×").append(vc.getSupportedHeights().getUpper());
                } catch (Exception ignored) {}
                sb.append(")");
            } else if (!l.isEmpty()) sb.append("⚠️ 仅软件编码 (").append(l.get(0).name).append(")");
            else sb.append("❌ 不支持");
            sb.append('\n');
        }
        Enc aac = best("audio/mp4a-latm");
        sb.append("AAC 音频：").append(aac != null ? "✅ " + aac.name : "❌ 不支持");
        return sb.toString();
    }
}
