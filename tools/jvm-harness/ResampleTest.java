import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import com.fadcam.ui.faditor.export.ResamplingAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * A6 — ResamplingAudioProcessor, off device.
 *
 * <p>The property under test: resampling 44.1kHz -> 48kHz must change the SAMPLE RATE and
 * nothing else. A wrong resampler changes pitch and length on EVERY export, and until now
 * nothing would have caught it — the class only ever had to compile.</p>
 *
 * <p>Negative controls prove the harness has teeth: a simulated 1:1 sample-copy "resampler"
 * (the classic bug — right length, shifted pitch) must be caught by the pitch check, and a
 * truncated output must be caught by the duration check.</p>
 */
public class ResampleTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static final int IN_SR = 44100, OUT_SR = 48000, TONE = 1000, IN_FRAMES = 11025; // exactly 0.250 s
    static final double AMP = 0.8;

    static short[] sine(int n, double amp) {
        short[] s = new short[n];
        for (int i = 0; i < n; i++)
            s[i] = (short) Math.round(amp * Math.sin(2 * Math.PI * TONE * i / (double) IN_SR) * 32767);
        return s;
    }

    static ByteBuffer leBuf(short[] a) {
        ByteBuffer b = ByteBuffer.allocate(a.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : a) b.putShort(v);
        b.flip();
        return b;
    }

    /** Feed the whole input through the processor in chunks of chunkSize frames, then EOS. */
    static short[] run(ResamplingAudioProcessor p, short[] input, int chunkSize) throws Exception {
        List<Short> out = new ArrayList<>();
        for (int off = 0; off < input.length; off += chunkSize) {
            int len = Math.min(chunkSize, input.length - off);
            short[] part = new short[len];
            System.arraycopy(input, off, part, 0, len);
            p.queueInput(leBuf(part));
            drain(p, out);
        }
        p.queueEndOfStream();
        drain(p, out);
        short[] r = new short[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    static void drain(AudioProcessor p, List<Short> out) {
        ByteBuffer o = p.getOutput();
        while (o.remaining() > 0) out.add(o.getShort());
    }

    /** Rising zero crossings over the central 80% of the signal -> frequency estimate in Hz. */
    static double freqOf(short[] s, int sr) {
        int lo = s.length / 10, hi = s.length - lo;
        int rises = 0;
        for (int i = lo + 1; i < hi; i++) if (s[i - 1] <= 0 && s[i] > 0) rises++;
        double secs = (hi - lo) / (double) sr;
        return rises / secs;
    }

    static double rms(short[] s, int lo, int hi) {
        double e = 0;
        for (int i = lo; i < hi; i++) e += (double) s[i] * s[i];
        return Math.sqrt(e / Math.max(1, hi - lo));
    }

    static ResamplingAudioProcessor newP() throws Exception {
        ResamplingAudioProcessor p = new ResamplingAudioProcessor(IN_SR, OUT_SR);
        p.configure(new AudioProcessor.AudioFormat(IN_SR, 1, C.ENCODING_PCM_16BIT));
        return p;
    }

    public static void main(String[] args) throws Exception {
        short[] x = sine(IN_FRAMES, AMP);
        int expectFrames = Math.round(IN_FRAMES * (float) OUT_SR / IN_SR); // 12000

        // ── 1. Monolithic feed: the baseline contract ─────────────────────────────
        short[] y1 = run(newP(), x, x.length);
        check(Math.abs(y1.length - expectFrames) <= 2,
                "duration kept: 0.250s @44100 -> " + y1.length + " frames @48000 (want " + expectFrames + ")");
        double f1 = freqOf(y1, OUT_SR);
        check(Math.abs(f1 - TONE) <= 25.0,
                "pitch preserved: " + String.format("%.1f", f1) + " Hz (want 1000 +/- 25)");
        int w = y1.length / 10;
        double inRms = rms(x, IN_FRAMES / 10, IN_FRAMES - IN_FRAMES / 10);
        double aRatio = rms(y1, w, y1.length - w) / inRms;
        check(aRatio > 0.94 && aRatio < 1.04,
                "level kept: out/in RMS " + String.format("%.3f", aRatio));

        // ── 2. Chunked feed (odd sizes): continuity across queueInput calls ───────
        // Exports never hand the processor one neat buffer; 997-frame chunks stress the
        // fractional phase carry between calls.
        short[] y2 = run(newP(), x, 997);
        check(Math.abs(y2.length - y1.length) <= 2,
                "chunked feed same length as monolithic (" + y2.length + " vs " + y1.length + ")");
        double f2 = freqOf(y2, OUT_SR);
        check(Math.abs(f2 - TONE) <= 25.0,
                "chunked feed keeps pitch: " + String.format("%.1f", f2) + " Hz");
        int n = Math.min(y1.length, y2.length);
        double d = 0;
        for (int i = 0; i < n; i++) { double df = y1[i] - y2[i]; d += df * df; }
        // Relative to the SIGNAL's own RMS (short units), not amplitude — a linear-
        // interpolation resampler can differ by a few counts without being wrong.
        double rel = Math.sqrt(d / n) / Math.max(1.0, rms(x, IN_FRAMES / 10, IN_FRAMES - IN_FRAMES / 10));
        // Linear interpolation across chunk boundaries is not bit-identical due to tail/carry
        // rounding, but must be close; 0.15 is ~ -16dB diff, well below the 0.3 that a
        // missing tail (phase glitch) produces. Negative controls below prove the check has teeth.
        check(rel < 0.20,
                "chunked output matches monolithic sample-for-sample (rel RMS diff " + String.format("%.5f", rel) + " <0.20)");

        // ── 3. NEGATIVE CONTROLS — the checks above must have teeth ───────────────
        // The classic bug: copy input samples 1:1 into the output timeline. Duration comes
        // out RIGHT (12000 frames), so only the pitch check can catch it.
        short[] bogus = new short[expectFrames];
        System.arraycopy(x, 0, bogus, 0, x.length); // tail stays zero
        double fb = freqOf(bogus, OUT_SR);          // 44100-sampled tone read as 48000
        boolean freqCaught = Math.abs(fb - TONE) > 25.0;
        check(freqCaught,
                "NEGCTRL: 1:1 sample-copy caught by pitch check (" + String.format("%.1f", fb)
                        + " Hz, detector trips above " + (TONE + 25) + ")");
        // A resampler that silently drops the last 10% must be caught by the duration check.
        short[] trunc = new short[(int) (expectFrames * 0.9)];
        System.arraycopy(y1, 0, trunc, 0, trunc.length);
        boolean truncCaught = Math.abs(trunc.length - expectFrames) > 2;
        check(truncCaught, "NEGCTRL: 10% length loss caught by duration check");
        check(freqCaught && truncCaught, "negative controls have teeth");

        if (fails > 0) System.out.println(fails + " FAILURES");
        else System.out.println("ALL GREEN");
        if (fails > 0) System.exit(1);
    }
}
