import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import com.fadcam.ui.faditor.compositor.WindowSeekMap;
import com.fadcam.ui.faditor.export.VolumeAudioProcessor;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.PipSourceClock;
import com.fadcam.ui.faditor.model.TimeMap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The export-timing rows of the 2026-11-11 code review that came down to a unit:
 *  - D3: which source moment a picture-in-picture shows at a timeline moment, at its speed
 *        (PipSourceClock);
 *  - D10: seek units inside a looped clip of the gapless player (WindowSeekMap);
 *  - E5: the volume curve read from where a transition piece starts on the clip's own clock
 *        (TimeMap.clipLocalMsAt + VolumeAudioProcessor.setEnvelopeStartMs, driven with real PCM).
 *
 * Usage:  bash tools/jvm-harness/run-seekclock.sh
 */
public class SeekClockTest {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; } else { fail++; System.out.println("FAIL " + name); }
    }

    static void eq(long got, long want, String name) {
        check(got == want, name + "  (got " + got + ", want " + want + ")");
    }

    public static void main(String[] args) throws Exception {
        // ---- D3: picture-in-picture source clock ----
        // A 10 s source played at 2x occupies a 5 s window starting at 20 s.
        eq(PipSourceClock.sourceMsAt(0, 10_000, 20_000, 2f, 20_000), 0, "2x: window start is the in-point");
        eq(PipSourceClock.sourceMsAt(0, 10_000, 20_000, 2f, 22_500), 5_000, "2x: halfway through the window is halfway through the footage");
        eq(PipSourceClock.sourceMsAt(0, 10_000, 20_000, 2f, 25_000), 9_999, "2x: the end of the window is the last frame");
        eq(PipSourceClock.sourceMsAt(0, 10_000, 20_000, 2f, 99_000), 9_999, "never past the last frame");
        // Half speed: a 4 s source fills an 8 s window.
        eq(PipSourceClock.sourceMsAt(1_000, 5_000, 0, 0.5f, 4_000), 3_000, "0.5x: 4 s into the window is 2 s of footage");
        // Normal speed is exactly the old one-to-one read.
        eq(PipSourceClock.sourceMsAt(1_000, 9_000, 3_000, 1f, 5_500), 3_500, "1x: unchanged");
        eq(PipSourceClock.sourceMsAt(1_000, 9_000, 3_000, 1f, 1_000), 1_000, "before the window: the in-point");
        // Window length and source length agree (the point of the fix): the last timeline ms of
        // the window is the last source ms, for a spread of speeds.
        for (float s : new float[]{0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 4f, 10f}) {
            Clip c = new Clip(null, 60_000);
            c.setInPointMs(2_000);
            c.setOutPointMs(12_000);
            c.setSpeedMultiplier(s);
            long start = 7_000;
            long end = start + c.getTrimmedDurationMs();
            long atEnd = PipSourceClock.sourceMsAt(c.getInPointMs(), c.getOutPointMs(), start, s, end);
            check(Math.abs(atEnd - (c.getOutPointMs() - 1)) <= 1 || atEnd >= c.getOutPointMs() - 1 - Math.ceil(s),
                    "speed " + s + ": the window's end reaches the footage's end (got " + atEnd + ")");
            long mid = PipSourceClock.sourceMsAt(c.getInPointMs(), c.getOutPointMs(), start, s, (start + end) / 2);
            check(Math.abs(mid - (c.getInPointMs() + c.getOutPointMs()) / 2) <= Math.ceil(s) + 1,
                    "speed " + s + ": the window's middle is the footage's middle (got " + mid + ")");
        }

        // ---- D10: seek units in a looped clip ----
        // A clip at 2x: 10 s of source = 5 s on the timeline; loop-after of 10 s = two more 5 s
        // windows. Windows start (timeline ms) 0, 5000, 10000 with length 5000 each; the player's
        // own position inside a window is source ms, 0..10000.
        long[] vs = {0, 5_000, 10_000};
        long[] vl = {5_000, 5_000, 5_000};
        long[] r;
        r = WindowSeekMap.resolve(vs, vl, 2f, 0);
        eq(r[0], 0, "2x: start -> window 0"); eq(r[1], 0, "2x: start -> local 0");
        r = WindowSeekMap.resolve(vs, vl, 2f, 8_000);
        eq(r[0], 0, "2x: 8000 source ms is still the first window"); eq(r[1], 8_000, "2x: and 8000 into it (was clamped to 5000)");
        r = WindowSeekMap.resolve(vs, vl, 2f, 10_000);
        eq(r[0], 1, "2x: 10000 is the second window's start"); eq(r[1], 0, "2x: local 0");
        r = WindowSeekMap.resolve(vs, vl, 2f, 25_000);
        eq(r[0], 2, "2x: 25000 is in the third window"); eq(r[1], 5_000, "2x: 5000 into it");
        r = WindowSeekMap.resolve(vs, vl, 2f, 99_000);
        eq(r[0], 2, "2x: past the end clamps to the last window"); eq(r[1], 10_000, "2x: at its end");
        // The round trip the player relies on: a position read from the player seeks back to itself.
        for (float s : new float[]{0.5f, 0.75f, 1f, 1.5f, 2f, 3f}) {
            long len = 4_000;
            long[] starts = {0, len, 2 * len, 3 * len};
            long[] lens = {len, len, len, len};
            for (int w = 0; w < 4; w++) {
                long maxLocal = WindowSeekMap.mediaLen(len, s);
                for (long local = 0; local < maxLocal; local += 137) {
                    long pos = WindowSeekMap.positionOf(starts[w], s, local);
                    long[] back = WindowSeekMap.resolve(starts, lens, s, pos);
                    check(back[0] == w && back[1] == local,
                            "round trip at " + s + "x window " + w + " local " + local
                                    + " -> " + back[0] + "/" + back[1]);
                }
            }
        }
        // Normal speed: exactly the old arithmetic.
        r = WindowSeekMap.resolve(vs, vl, 1f, 7_000);
        eq(r[0], 1, "1x: window"); eq(r[1], 2_000, "1x: local");

        // ---- E5: the volume curve through a transition piece ----
        // A 4 s clip whose sound fades from full at 3 s to nothing at 4 s, followed by a 600 ms
        // transition: the piece starts at source 3400, i.e. 3400 ms on the clip's own clock.
        Clip c = new Clip(null, 60_000);
        c.setInPointMs(0);
        c.setOutPointMs(4_000);
        long pieceStart = TimeMap.clipLocalMsAt(c, 3_400);
        eq(pieceStart, 3_400, "the transition piece starts at 3400 on the clip's clock");
        short first = firstSampleAfterProcessing(pieceStart, 0, 10_000);
        // 3400 ms is 40% of the way down the 3000->4000 ramp: gain 0.6.
        check(Math.abs(first - 6_000) <= 20, "piece start: gain 0.6 (sample " + first + ")");
        short unstarted = firstSampleAfterProcessing(0, 0, 10_000);
        check(unstarted >= 9_990, "without the start the curve would have run from full (sample " + unstarted + ")");
        // 300 ms (14400 frames at 48 kHz) into the piece: clip clock 3700 -> gain 0.3.
        short later = firstSampleAfterProcessing(pieceStart, 14_400, 10_000);
        check(Math.abs(later - 3_000) <= 30, "300 ms in: gain 0.3 (sample " + later + ")");

        System.out.println("SeekClockTest: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * Runs a 48 kHz mono constant signal through a VolumeAudioProcessor with the fade curve above,
     * starting at {@code startMs} on the clip's clock, and returns the output sample at frame
     * {@code atFrame}.
     */
    static short firstSampleAfterProcessing(long startMs, int atFrame, int level) throws Exception {
        VolumeAudioProcessor vp = new VolumeAudioProcessor();
        vp.setVolume(1f);
        vp.setVolumeEnvelope(new long[]{0, 3_000, 4_000}, new float[]{1f, 1f, 0f});
        vp.setEnvelopeStartMs(startMs);
        vp.configure(new AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT));
        vp.flush();
        int frames = atFrame + 1;
        ByteBuffer in = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < frames; i++) in.putShort((short) level);
        in.flip();
        vp.queueInput(in);
        ByteBuffer out = vp.getOutput().order(ByteOrder.nativeOrder());
        return out.getShort(atFrame * 2);
    }
}
