import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import com.fadcam.ui.faditor.export.VolumeAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * The volume processor on real PCM, every channel layout (Codex round 2, R2-AUD-01 and R2-AUD-03).
 *
 * <p>One processor serves the export and the editor's preview, so what is checked here is heard in
 * both. A constant tone goes in; each channel's level comes out at known points of a fade. A
 * listener's terms: a fade on a stereo song fades both sides, a panned fade stays panned, a 5.1 file
 * plays instead of stopping the export.</p>
 */
public class StereoGainTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }
    static boolean near(int got, double want) { return Math.abs(got - want) <= 60; }

    static final int RATE = 1000;          // 1 frame = 1 ms, so frame N is the curve at N ms
    static final short TONE = 10000;

    /** A processor at RATE with {@code channels}, fading 0 -> 1 over the first second when asked. */
    static VolumeAudioProcessor proc(int channels, boolean fade, float volume, float pan) throws Exception {
        VolumeAudioProcessor p = new VolumeAudioProcessor();
        p.setVolume(volume);
        p.setPan(pan);
        if (fade) p.setVolumeEnvelope(new long[]{0L, 1000L}, new float[]{0f, 1f});
        p.configure(new AudioProcessor.AudioFormat(RATE, channels, C.ENCODING_PCM_16BIT));
        p.flush();
        return p;
    }

    static short[] run(VolumeAudioProcessor p, int channels, int frames) {
        short[] in = new short[channels * frames];
        java.util.Arrays.fill(in, TONE);
        ByteBuffer b = ByteBuffer.allocateDirect(in.length * 2).order(ByteOrder.nativeOrder());
        b.asShortBuffer().put(in);
        p.queueInput(b);
        ByteBuffer out = p.getOutput().order(ByteOrder.nativeOrder());
        ShortBuffer s = out.asShortBuffer();
        short[] r = new short[s.remaining()];
        s.get(r);
        return r;
    }

    static int at(short[] pcm, int channels, int frame, int ch) { return pcm[frame * channels + ch]; }

    public static void main(String[] a) throws Exception {
        // 1. Mono follows the fade (the path that always worked: the control).
        short[] mono = run(proc(1, true, 1f, 0f), 1, 1000);
        check(near(at(mono, 1, 0, 0), 0), "mono fade: silent at the first key");
        check(near(at(mono, 1, 500, 0), 5000), "mono fade: half way at 500 ms (" + at(mono, 1, 500, 0) + ")");

        // 2. STEREO follows the same fade, both sides (R2-AUD-01: it stayed at full level).
        short[] st = run(proc(2, true, 1f, 0f), 2, 1000);
        check(near(at(st, 2, 0, 0), 0) && near(at(st, 2, 0, 1), 0),
                "stereo fade: both sides silent at the first key (" + at(st, 2, 0, 0) + ", " + at(st, 2, 0, 1) + ")");
        check(near(at(st, 2, 500, 0), 5000) && near(at(st, 2, 500, 1), 5000),
                "stereo fade: both sides half way at 500 ms (" + at(st, 2, 500, 0) + ", " + at(st, 2, 500, 1) + ")");
        check(near(at(st, 2, 999, 0), 9990) && near(at(st, 2, 999, 1), 9990),
                "stereo fade: both sides near full at the end");

        // 3. The volume is applied once, not twice, on top of the curve.
        short[] half = run(proc(2, true, 0.5f, 0f), 2, 1000);
        check(near(at(half, 2, 500, 0), 2500), "stereo fade at volume 0.5: a quarter at 500 ms (" + at(half, 2, 500, 0) + ")");

        // 4. No curve: unchanged behaviour (static volume, centre pan).
        short[] flat = run(proc(2, false, 0.5f, 0f), 2, 10);
        check(near(at(flat, 2, 5, 0), 5000) && near(at(flat, 2, 5, 1), 5000), "stereo, no curve: volume 0.5 on both");
        short[] unity = run(proc(2, false, 1f, 0f), 2, 10);
        check(at(unity, 2, 5, 0) == TONE && at(unity, 2, 5, 1) == TONE, "stereo, unity, centre: bit-exact passthrough");

        // 5. A panned fade stays panned AND fades (equal power: full left = root two on the left).
        short[] left = run(proc(2, true, 1f, -1f), 2, 1000);
        check(near(at(left, 2, 500, 0), 5000 * Math.sqrt(2)) && near(at(left, 2, 500, 1), 0),
                "full-left fade: left at root-two x half, right silent (" + at(left, 2, 500, 0) + ", " + at(left, 2, 500, 1) + ")");

        // 6. 5.1 (six channels): no crash, every channel fades (R2-AUD-03).
        short[] six = null;
        try {
            six = run(proc(6, true, 1f, 0f), 6, 1000);
        } catch (RuntimeException e) {
            System.out.println("      threw: " + e);
        }
        check(six != null, "5.1 sound goes through without an error");
        if (six != null) {
            boolean all = true;
            for (int ch = 0; ch < 6; ch++) all &= near(at(six, 6, 500, ch), 5000);
            check(all, "5.1: all six channels half way at 500 ms");
        }
        // Pan moves the front pair only; centre, sub and surrounds keep the fade but are not panned away.
        short[] sixR = null;
        try { sixR = run(proc(6, false, 1f, 1f), 6, 10); } catch (RuntimeException e) { System.out.println("      threw: " + e); }
        check(sixR != null && near(at(sixR, 6, 5, 0), 0) && near(at(sixR, 6, 5, 1), 14142)
                        && at(sixR, 6, 5, 2) == TONE && at(sixR, 6, 5, 5) == TONE,
                "5.1 panned right: front left silent, front right root two, the rest untouched");
        short[] three = null;
        try { three = run(proc(3, true, 1f, 0f), 3, 1000); } catch (RuntimeException e) { System.out.println("      threw: " + e); }
        check(three != null && near(at(three, 3, 500, 2), 5000), "three channels: no crash, the third fades too");

        if (fails > 0) { System.out.println(fails + " FAILED"); System.exit(1); }
        System.out.println("StereoGainTest: all passed");
    }
}
