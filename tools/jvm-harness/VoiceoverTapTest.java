import com.fadcam.ui.faditor.audio.PcmBlocks;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * X4 (Avatar build): Studio's voiceover recorder gives a sample listener and a level, and its file is unchanged.
 * A scripted microphone drives {@link PcmBlocks} the way {@code VoiceoverRecorder}'s drain thread does; the bytes must be
 * exactly what the recorder wrote before X4 (the old inline conversion is kept here as the reference).
 */
public class VoiceoverTapTest {
    static int pass, fail;

    static void check(boolean ok, String what) {
        if (ok) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    /** The recorder's conversion before X4, copied as it was (VoiceoverRecorder, 11-27). */
    static byte[] oldBytes(List<short[]> blocks) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (short[] shortBuf : blocks) {
            byte[] byteBuf = new byte[shortBuf.length * 2];
            int read = shortBuf.length;
            for (int i = 0; i < read; i++) {
                byteBuf[i * 2] = (byte) (shortBuf[i] & 0xFF);
                byteBuf[i * 2 + 1] = (byte) ((shortBuf[i] >> 8) & 0xFF);
            }
            o.write(byteBuf, 0, read * 2);
        }
        return o.toByteArray();
    }

    /** A microphone that hands out these blocks (each read gets at most one), then nothing. */
    static PcmBlocks.Source script(List<short[]> blocks) {
        int[] at = {0};
        return (buf, off, len) -> {
            if (at[0] >= blocks.size()) return 0;
            short[] b = blocks.get(at[0]++);
            int n = Math.min(len, b.length);
            System.arraycopy(b, 0, buf, off, n);
            return n;
        };
    }

    static List<short[]> speech(long seed, int count, int size) {
        Random r = new Random(seed);
        List<short[]> l = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            short[] b = new short[size - (k % 3)];   // blocks of uneven length, as AudioRecord can give
            for (int i = 0; i < b.length; i++) b[i] = (short) (r.nextInt(65536) - 32768);
            l.add(b);
        }
        l.get(0)[0] = Short.MIN_VALUE;
        l.get(0)[1] = Short.MAX_VALUE;
        l.get(0)[2] = -1;
        l.get(0)[3] = 0;
        return l;
    }

    static byte[] run(List<short[]> blocks, PcmBlocks.PcmListener listener, int[] reads) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PcmBlocks p = new PcmBlocks(script(blocks), out, new Object(), 44100, 2205);
        p.setListener(listener);
        int n;
        while ((n = p.step()) > 0) reads[0] += n;
        return out.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        List<short[]> blocks = speech(7, 40, 2205);
        int total = 0;
        for (short[] b : blocks) total += b.length;
        byte[] want = oldBytes(blocks);

        // 1. The file is byte for byte what Studio wrote before, with no listener.
        int[] reads = {0};
        byte[] got = run(blocks, null, reads);
        check(Arrays.equals(want, got), "no listener: the WAV data is not byte for byte the old recorder's");
        check(reads[0] == total, "no listener: samples counted " + reads[0] + ", want " + total);

        // 2. With a listener: same bytes, and the listener hears every sample in order at 44 100.
        List<Short> heard = new ArrayList<>();
        int[] rate = {0};
        reads[0] = 0;
        got = run(blocks, (s, c, r) -> {
            rate[0] = r;
            for (int i = 0; i < c; i++) heard.add(s[i]);
        }, reads);
        check(Arrays.equals(want, got), "listener on: the WAV data changed");
        check(heard.size() == total, "listener heard " + heard.size() + " samples, want " + total);
        boolean same = heard.size() == total;
        int k = 0;
        for (short[] b : blocks) for (short v : b) if (same && heard.get(k++) != v) same = false;
        check(same, "listener heard the samples out of order or changed");
        check(rate[0] == 44100, "listener told rate " + rate[0]);

        // 3. A listener that scribbles on the block cannot change the file (the bytes are written first).
        reads[0] = 0;
        got = run(blocks, (s, c, r) -> Arrays.fill(s, 0, c, (short) 0), reads);
        check(Arrays.equals(want, got), "a listener that changes the block changed the file");

        // 4. A listener that throws is dropped; the take goes on, unchanged.
        int[] calls = {0};
        reads[0] = 0;
        got = run(blocks, (s, c, r) -> {
            calls[0]++;
            throw new IllegalStateException("broken lip sync");
        }, reads);
        check(Arrays.equals(want, got), "a throwing listener changed the file");
        check(reads[0] == total, "a throwing listener stopped the recording at " + reads[0]);
        check(calls[0] == 1, "a throwing listener was called " + calls[0] + " times, want once then dropped");

        // 5. The level: silence 0, half scale 0.5, full square wave 1, the last block's.
        List<short[]> lv = new ArrayList<>();
        lv.add(new short[2000]);
        short[] half = new short[2000];
        Arrays.fill(half, (short) 16384);
        lv.add(half);
        short[] sq = new short[2000];
        for (int i = 0; i < sq.length; i++) sq[i] = (short) (i % 2 == 0 ? 32767 : -32768);
        lv.add(sq);
        PcmBlocks p = new PcmBlocks(script(lv), new ByteArrayOutputStream(), new Object(), 44100, 2205);
        check(p.level() == 0f, "level before any sound is " + p.level());
        p.step();
        check(p.level() == 0f, "silence gives level " + p.level());
        p.step();
        check(Math.abs(p.level() - 0.5f) < 1e-4, "half scale gives level " + p.level());
        p.step();
        check(Math.abs(p.level() - 1f) < 1e-3, "full scale gives level " + p.level());
        check(p.samples() == 6000, "samples written " + p.samples());

        // 6. A read error writes nothing and is passed back.
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        PcmBlocks e = new PcmBlocks((b, off, len) -> -3, o, new Object(), 44100, 100);
        check(e.step() == -3 && o.size() == 0, "a read error wrote something or was swallowed");

        // 7. The recorder really goes through PcmBlocks (and so through what this test proves).
        String src = new String(Files.readAllBytes(Paths.get("app/src/main/java/com/fadcam/ui/faditor/audio/VoiceoverRecorder.java")), "UTF-8");
        check(src.contains("blocks.step()") && !src.contains("byteBuf[i * 2]"), "VoiceoverRecorder does not use PcmBlocks for its blocks");
        check(src.contains("setPcmListener") && src.contains("getLevel()"), "VoiceoverRecorder has no listener or level");

        System.out.println((fail == 0 ? "PASSED " : "FAILED ") + pass + " checks, " + fail + " failures");
        System.exit(fail == 0 ? 0 : 1);
    }
}
