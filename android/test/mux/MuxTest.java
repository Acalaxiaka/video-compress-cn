import com.acalaxiaka.videocompress.Mp4Writer;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** 读取 extract.py 生成的样本流，用 Mp4Writer 复用。参数: in.bin out.mp4 [rotation] [force64] [repeatUntilBytes] */
public class MuxTest {
    public static void main(String[] a) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(a[0]), 1 << 20));
        String hdr = readLine(in);
        String vc = js(hdr, "vcodec"); int w = Integer.parseInt(js(hdr, "w")), h = Integer.parseInt(js(hdr, "h"));
        byte[] csd0 = hex(js(hdr, "csd0")); boolean audio = js(hdr, "audio").equals("true");
        int rot = a.length > 2 ? Integer.parseInt(a[2]) : 0; boolean f64 = a.length > 3 && a[3].equals("1");
        long repeatUntil = a.length > 4 ? Long.parseLong(a[4]) : 0;
        List<Object[]> s = new ArrayList<>();
        while (in.available() > 0) {
            int tr = in.readUnsignedByte(), key = in.readUnsignedByte(); long pts = in.readLong(); int n = in.readInt();
            byte[] d = new byte[n]; in.readFully(d); s.add(new Object[]{tr, key == 1, pts, d});
        }
        Files.deleteIfExists(Paths.get(a[1]));
        try (FileChannel ch = new RandomAccessFile(a[1], "rw").getChannel()) {
            Mp4Writer m = new Mp4Writer(ch, f64); m.setRotation(rot);
            int vt = m.addVideoTrack(vc.equals("hevc") ? Mp4Writer.HEVC : Mp4Writer.AVC, w, h, csd0, null);
            int at = audio ? m.addAudioTrack(Integer.parseInt(js(hdr, "sr")), Integer.parseInt(js(hdr, "ch")), hex(js(hdr, "asc")), 128000) : -1;
            m.start();
            long span = 0; for (Object[] o : s) span = Math.max(span, (Long) o[2]); span += 100_000;
            int loop = 0;
            do {
                for (Object[] o : s) {
                    int tr = (Integer) o[0];
                    m.writeSample(tr == 0 ? vt : at, ByteBuffer.wrap((byte[]) o[3]), (Long) o[2] + loop * span, (Boolean) o[1]);
                }
                loop++;
            } while (m.bytesWritten() < repeatUntil);
            m.finish();
            System.out.println("wrote " + m.bytesWritten() + " bytes, loops=" + loop + ", samples=" + s.size() * loop);
        }
    }
    static String readLine(DataInputStream in) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); int c; while ((c = in.read()) != '\n') b.write(c); return b.toString("UTF-8"); }
    static String js(String j, String k) { int i = j.indexOf("\"" + k + "\": ") + k.length() + 4; if (j.charAt(i) == '"') return j.substring(i + 1, j.indexOf('"', i + 1)); int e = i; while (e < j.length() && ",}".indexOf(j.charAt(e)) < 0) e++; return j.substring(i, e).trim(); }
    static byte[] hex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return b; }
}
