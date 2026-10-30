import com.acalaxiaka.videocompress.BitrateCalc;
public class BitrateCalcTest {
    static int fails = 0, n = 0;
    static void check(boolean c, String m) { n++; if (!c) { fails++; System.out.println("FAIL: " + m); } else System.out.println("ok: " + m); }
    public static void main(String[] a) {
        // 不放大
        int[] s = BitrateCalc.targetSize(1280, 720, 1080); check(s[0] == 1280 && s[1] == 720, "720p 设 1080 不放大");
        s = BitrateCalc.targetSize(3840, 2160, 1080); check(s[0] == 1920 && s[1] == 1080, "4K → 1080p");
        s = BitrateCalc.targetSize(1080, 1920, 720); check(s[0] == 720 && s[1] == 1280, "竖屏 1080x1920 → 720x1280");
        s = BitrateCalc.targetSize(1921, 1081, 0); check(s[0] == 1920 && s[1] == 1080, "奇数尺寸对齐偶数");
        s = BitrateCalc.targetSize(1440, 1080, 720); check(s[0] == 960 && s[1] == 720, "4:3 → 960x720");
        // 均衡 HEVC 1080p30 ≈ 0.065 bpp → ~4.04 Mbps
        long b = BitrateCalc.videoBitrate(BitrateCalc.HEVC, BitrateCalc.baseBpp(BitrateCalc.BALANCED), 1920, 1080, 30, 1920, 1080, 0, false);
        check(b > 3_000_000 && b < 5_200_000, "HEVC 1080p30 均衡 ≈4 Mbps: " + b);
        double bpp = b / (1920.0 * 1080 * 30); check(bpp >= 0.05 && bpp <= 0.08, "均衡 bpp 在 0.05–0.08: " + bpp);
        long avc = BitrateCalc.videoBitrate(BitrateCalc.AVC, BitrateCalc.baseBpp(1), 1920, 1080, 30, 1920, 1080, 0, false);
        check(avc > b, "H.264 码率高于 HEVC");
        long av1 = BitrateCalc.videoBitrate(BitrateCalc.AV1, BitrateCalc.baseBpp(1), 1920, 1080, 30, 1920, 1080, 0, false);
        check(av1 < b, "AV1 码率低于 HEVC");
        long hi = BitrateCalc.videoBitrate(BitrateCalc.HEVC, BitrateCalc.baseBpp(0), 1920, 1080, 30, 1920, 1080, 0, true);
        long sm = BitrateCalc.videoBitrate(BitrateCalc.HEVC, BitrateCalc.baseBpp(2), 1920, 1080, 30, 1920, 1080, 0, false);
        check(hi > b && b > sm, "高画质 > 均衡 > 小体积");
        long f60 = BitrateCalc.videoBitrate(BitrateCalc.HEVC, 0.065, 1920, 1080, 60, 1920, 1080, 0, false);
        check(f60 > b && f60 < 2 * b, "60fps 码率增加但小于2倍");
        // 源码率上限
        long cap = BitrateCalc.videoBitrate(BitrateCalc.HEVC, 0.10, 1920, 1080, 30, 1920, 1080, 2_000_000, true);
        check(cap <= 1_800_000, "不超过源码率*0.9: " + cap);
        long capScaled = BitrateCalc.videoBitrate(BitrateCalc.HEVC, 0.065, 1280, 720, 30, 3840, 2160, 40_000_000, false);
        check(capScaled <= 40_000_000 * (1280*720.0/(3840*2160)) * 0.75 + 1, "缩放后按像素比例限制");
        check(BitrateCalc.videoBitrate(BitrateCalc.HEVC, 0.065, 160, 120, 15, 0, 0, 0, false) == BitrateCalc.MIN_VIDEO_BPS, "最低码率下限");
        // 估算 / 跳过
        long est = BitrateCalc.estimateBytes(4_000_000, 128_000, 60_000_000L);
        check(Math.abs(est - 4_128_000 * 60 / 8 * 1.02) < 10, "体积估算 60s: " + est);
        check(BitrateCalc.shouldSkip(96, 100), "估算≥95% 跳过");
        check(!BitrateCalc.shouldSkip(50, 100), "估算 50% 不跳过");
        check(BitrateCalc.qualityToBpp(1) == 0.02 && Math.abs(BitrateCalc.qualityToBpp(100) - 0.15) < 1e-9, "画质滑块映射");
        check(BitrateCalc.effectiveFps(0) == 30, "未知帧率按30");
        check(BitrateCalc.human(1536).equals("1.5 KB") && BitrateCalc.human(5L<<20).equals("5.0 MB"), "人类可读体积");
        System.out.println(n - fails + "/" + n + " passed"); if (fails > 0) System.exit(1);
    }
}
