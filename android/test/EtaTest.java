import com.acalaxiaka.videocompress.Eta;
public class EtaTest {
    public static void main(String[] a) {
        int fails = 0;
        // 匀速：1000 秒完成，每秒 0.1%
        Eta e = new Eta(60_000); double r = -1;
        for (int s = 0; s <= 230; s++) r = e.update(s * 1000L, s / 1000.0);
        System.out.println("匀速 23% 时 ETA=" + r + "（应≈770）");
        if (Math.abs(r - 770) > 40) { fails++; System.out.println("FAIL 匀速"); }
        // 变慢：前 230 秒 0.1%/s，之后 0.03%/s（模拟发热降频）；窗口应快速跟上新速度
        for (int s = 231; s <= 600; s++) r = e.update(s * 1000L, 0.23 + (s - 230) * 0.0003);
        double p = 0.23 + 370 * 0.0003, truth = (1 - p) / 0.0003;
        System.out.println("降速后 ETA=" + Math.round(r) + " 真实≈" + Math.round(truth));
        if (Math.abs(r - truth) / truth > 0.15) { fails++; System.out.println("FAIL 降速跟踪"); }
        Eta e2 = new Eta(60_000); if (e2.update(0, 0) != -1 || e2.update(1000, 0.01) != -1) { fails++; System.out.println("FAIL 数据不足应返回 -1"); }
        if (!Eta.fmt(3725).equals("1 小时 02 分") || !Eta.fmt(75).equals("1 分 15 秒")) { fails++; System.out.println("FAIL fmt"); }
        System.out.println(fails == 0 ? "Eta tests passed" : fails + " failed"); if (fails > 0) System.exit(1);
    }
}
