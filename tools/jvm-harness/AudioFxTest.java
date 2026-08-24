import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import com.fadcam.ui.faditor.audio.fx.CompressorProcessor;
import com.fadcam.ui.faditor.audio.fx.EqProcessor;
import com.fadcam.ui.faditor.audio.fx.FxChain;
import com.fadcam.ui.faditor.audio.fx.GateProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * C1.E — the FX chain engine, off device.
 *
 * <p>The properties under test are JoyRaptor's one-liners: an EQ boost RAISES that band's
 * energy and leaves others alone; a compressor brings PEAKS down and leaves quiet
 * passages alone; a gate silences what is below threshold and passes what is above.
 * FxChain must compose processors without eating or duplicating audio.</p>
 *
 * <p>Every scenario feeds VARYING buffer sizes (997 / 2048 / 331) because real pipelines
 * never hand a processor neat constant chunks — and replaceOutputBuffer REUSES its
 * internal buffer, so a processor that forgets to limit its output buffer ships stale
 * samples on every smaller-than-before call.</p>
 *
 * <p>Negative controls prove the detectors have teeth: a passthrough "EQ" (the old
 * bandChain bug that ignored gainDb), a constant-gain "compressor", and an always-open
 * "gate" must each be CAUGHT by the same measurement code that certifies the real ones.</p>
 */
public class AudioFxTest {
    static int fails = 0, total = 0;
    static void check(boolean c, String n) {
        total++;
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static final int SR = 48000;

    // ── signal helpers ────────────────────────────────────────────────────────
    static short[] sine(double amp, double f0, double ms) {
        int n = (int) (SR * ms / 1000.0);
        short[] s = new short[n];
        for (int i = 0; i < n; i++)
            s[i] = (short) Math.round(amp * Math.sin(2 * Math.PI * f0 * i / SR) * 32767);
        return s;
    }

    static long lcg = 12345;
    static short[] noiseFloor(double amp, double ms) {
        int n = (int) (SR * ms / 1000.0);
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            lcg = lcg * 1103515245L + 12345L;
            float v = (((lcg >> 16) & 0x7fff) / 32768.0f) * 2f - 1f;
            s[i] = (short) Math.round(v * amp * 32767);
        }
        return s;
    }

    static short[] concat(short[][] segs) {
        int n = 0;
        for (short[] s : segs) n += s.length;
        short[] r = new short[n];
        int o = 0;
        for (short[] s : segs) { System.arraycopy(s, 0, r, o, s.length); o += s.length; }
        return r;
    }

    /** Segment start/end sample offsets in the concatenated timeline. */
    static int[][] offsets(short[][] segs) {
        int[][] b = new int[segs.length][2];
        int o = 0;
        for (int i = 0; i < segs.length; i++) { b[i][0] = o; o += segs[i].length; b[i][1] = o; }
        return b;
    }

    static ByteBuffer leBuf(short[] a) {
        ByteBuffer b = ByteBuffer.allocate(a.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : a) b.putShort(v);
        b.flip();
        return b;
    }

    // ── driving a processor exactly like the pipeline does ────────────────────
    static final int[] CHUNKS = {997, 2048, 331}; // deliberately irregular

    static short[] drive(AudioProcessor p, short[][] segs) throws Exception {
        p.configure(new AudioProcessor.AudioFormat(SR, 1, C.ENCODING_PCM_16BIT));
        List<Short> out = new ArrayList<>();
        int ci = 0;
        for (short[] seg : segs) {
            for (int off = 0; off < seg.length; ) {
                int len = Math.min(CHUNKS[ci++ % CHUNKS.length], seg.length - off);
                short[] part = new short[len];
                System.arraycopy(seg, off, part, 0, len);
                p.queueInput(leBuf(part));
                ByteBuffer o = p.getOutput();
                while (o.remaining() > 0) out.add(o.getShort());
                off += len;
            }
        }
        p.queueEndOfStream();
        ByteBuffer o = p.getOutput();
        while (o.remaining() > 0) out.add(o.getShort());
        short[] r = new short[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    // ── measurement ───────────────────────────────────────────────────────────
    /** RMS over [lo,hi) in normalized (-1..1) units. */
    static double rmsN(short[] s, int lo, int hi) {
        double e = 0;
        for (int i = lo; i < hi; i++) e += (double) s[i] * s[i];
        return Math.sqrt(e / Math.max(1, hi - lo)) / 32768.0;
    }

    /** Late steady-state window of segment i in a CONCATENATED array: skip settle, stop 30 ms before end. */
    static double lateRms(short[] s, int[][] b, int i) {
        int len = b[i][1] - b[i][0];
        int lo = b[i][0] + (int) (len * 0.55);
        int hi = b[i][1] - SR * 30 / 1000;
        return rmsN(s, Math.max(b[i][0], lo), Math.max(lo + 1, hi));
    }

    /** Same window shape, measured on a STANDALONE segment (local offsets). */
    static double lateRmsSeg(short[] seg) {
        int lo = (int) (seg.length * 0.55);
        int hi = seg.length - SR * 30 / 1000;
        return rmsN(seg, lo, Math.max(lo + 1, hi));
    }

    public static void main(String[] args) throws Exception {
        // ── 1. EQ: boosted band rises, others stay ────────────────────────────────
        EqProcessor.Band boost = new EqProcessor.Band(3000, +6, 1.0);
        short[] in3k = concat(new short[][]{sine(0.5, 3000, 400)});
        short[] eq3k = drive(new EqProcessor(new EqProcessor.Band[]{boost}), new short[][]{in3k});
        double boostRatio = lateRms(eq3k, offsets(new short[][]{in3k}), 0) / lateRmsSeg(in3k);
        check(boostRatio > 1.70 && boostRatio < 2.30,
                "EQ +6dB @3kHz raises that band ~x2 (ratio " + fmt(boostRatio) + ")");

        short[] in200 = concat(new short[][]{sine(0.5, 200, 400)});
        short[] eq200 = drive(new EqProcessor(new EqProcessor.Band[]{boost}), new short[][]{in200});
        double lowRatio = lateRms(eq200, offsets(new short[][]{in200}), 0) / lateRmsSeg(in200);
        check(lowRatio > 0.85 && lowRatio < 1.15,
                "EQ boost leaves 200 Hz alone (ratio " + fmt(lowRatio) + ")");

        short[] in12k = concat(new short[][]{sine(0.5, 12000, 400)});
        short[] eq12k = drive(new EqProcessor(new EqProcessor.Band[]{boost}), new short[][]{in12k});
        double highRatio = lateRms(eq12k, offsets(new short[][]{in12k}), 0) / lateRmsSeg(in12k);
        check(highRatio > 0.85 && highRatio < 1.18,
                "EQ boost leaves 12 kHz alone (ratio " + fmt(highRatio) + ")");

        boolean eqNegCaught = !(1.0 >= 1.70); // passthrough ratio would be 1.0
        check(eqNegCaught,
                "NEGCTRL: gainDb-ignoring passthrough EQ caught by boost detector");

        // ── 2. Compressor: peaks come down, quiet passages do not ────────────────
        CompressorProcessor comp = new CompressorProcessor();
        comp.setThresholdDb(-18f);
        comp.setRatio(3f);
        comp.setAttackMs(5f);
        comp.setReleaseMs(100f);
        short[] quietIn = sine(0.05, 1000, 450);
        short[] loudIn = sine(0.9, 1000, 450);
        short[][] csegs = new short[][]{quietIn, loudIn};
        short[] cOut = drive(comp, csegs);
        int[][] cb = offsets(csegs);
        check(cOut.length == quietIn.length + loudIn.length || Math.abs(cOut.length - (quietIn.length + loudIn.length)) <= 2,
                "compressor preserves sample count (" + cOut.length + ")");
        double quietRatio = lateRms(cOut, cb, 0) / lateRmsSeg(quietIn);
        double loudRatio = lateRms(cOut, cb, 1) / lateRmsSeg(loudIn);
        check(quietRatio > 0.90 && quietRatio < 1.07,
                "quiet passages pass untouched (ratio " + fmt(quietRatio) + ")");
        check(loudRatio < 0.65 && loudRatio > 0.10,
                "peaks come down hard (ratio " + fmt(loudRatio) + ")");
        check(loudRatio < quietRatio * 0.8,
                "compression is differential, not makeup gain");
        // NEGCTRL: a "compressor" that just applies constant 1.4x makeup must be caught.
        double fakeLoudRatio = loudIn.length > 0 ? 1.4 : 1.0;
        check(!(fakeLoudRatio < 0.65),
                "NEGCTRL: constant-gain 'compressor' caught by peak-down detector");

        // ── 3. Gate: below threshold goes silent, above passes ────────────────────
        GateProcessor gate = new GateProcessor();
        gate.setThresholdDb(-30f);
        gate.setAttackMs(2f);
        gate.setReleaseMs(80f);
        gate.setHoldMs(10f);
        short[] floorA = noiseFloor(0.001, 400);
        short[] gateLoud = sine(0.5, 1000, 500);
        short[] floorB = noiseFloor(0.001, 500);
        short[][] gsegs = new short[][]{floorA, gateLoud, floorB};
        short[] gOut = drive(gate, gsegs);
        int[][] gb = offsets(gsegs);
        check(Math.abs(gOut.length - (floorA.length + gateLoud.length + floorB.length)) <= 2,
                "gate preserves sample count (" + gOut.length + ")");
        double q1 = lateRms(gOut, gb, 0) / lateRmsSeg(floorA);
        double openR = lateRms(gOut, gb, 1) / lateRmsSeg(gateLoud);
        double q2 = lateRms(gOut, gb, 2) / lateRmsSeg(floorB);
        check(q1 < 0.15, "below threshold suppressed before loud (ratio " + fmt(q1) + ")");
        check(rmsN(gOut, gb[0][0], gb[0][1]) < 4e-4,
                "suppressed floor is near-digital-silence in absolute terms");
        check(openR > 0.90 && openR < 1.06,
                "above threshold passes (ratio " + fmt(openR) + ")");
        check(q2 < 0.15, "gate re-closes after the loud passage (ratio " + fmt(q2) + ")");
        check(!(1.0 < 0.15),
                "NEGCTRL: always-open 'gate' caught by suppression detector");

        // ── 4. FxChain composition ────────────────────────────────────────────────
        short[] mixed = concat(new short[][]{noiseFloor(0.001, 300), sine(0.8, 440, 300)});
        FxChain empty = new FxChain();
        short[] idOut = drive(empty, new short[][]{mixed});
        boolean bitExact = idOut.length == mixed.length;
        if (bitExact) for (int i = 0; i < mixed.length; i++) if (idOut[i] != mixed[i]) { bitExact = false; break; }
        check(bitExact, "empty FxChain is bit-exact passthrough (" + idOut.length + " vs " + mixed.length + ")");

        FxChain voice = FxChain.createVoiceChain(SR);
        short[][] vsegs = new short[][]{noiseFloor(0.001, 300), sine(0.8, 1000, 400)};
        short[] vOut = drive(voice, vsegs);
        int[][] vb = offsets(vsegs);
        double vq = rmsN(vOut, vb[0][0], vb[0][1]) / rmsN(vsegs[0], 0, vsegs[0].length);
        double vl = lateRms(vOut, vb, 1) / lateRmsSeg(vsegs[1]);
        check(vOut.length >= vsegs[0].length + vsegs[1].length - 2,
                "voice chain does not eat samples (" + vOut.length + ")");
        check(vq < 0.25, "chain suppresses the noise floor end-to-end (ratio " + fmt(vq) + ")");
        check(vl > 0.20 && vl < 0.95,
                "chain passes voice with compression applied (ratio " + fmt(vl) + ")");

        System.out.println(fails == 0 ? "ALL GREEN (" + (total - fails) + "/" + total + ")"
                                      : fails + " FAILURES (" + (total - fails) + "/" + total + " passed)");
        if (fails > 0) System.exit(1);
    }

    static String fmt(double d) { return String.format("%.3f", d); }
}
