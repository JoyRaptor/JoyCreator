import com.fadcam.ui.faditor.waveform.Biquad;
import com.fadcam.ui.faditor.waveform.Fft;

/**
 * The DSP arithmetic under the FX chain: {@link Fft} and {@link Biquad}.
 *
 * <p>Both shipped untested while everything above them was tested. That is the wrong way round:
 * a fault here does not announce itself, it just makes every processor slightly wrong, and the
 * processor tests can still pass because they compare a filter against itself.</p>
 *
 * <p>These are checked against ANALYTIC answers rather than tolerances — an impulse must give a
 * flat spectrum, DC must land entirely in bin 0, Parseval's identity must hold exactly, a
 * 0 dB peaking filter must be unity at every frequency. Where the true answer is known, agreeing
 * with it is the only evidence worth having; a test that merely says "the output changed" cannot
 * tell a correct transform from a plausible one.</p>
 */
public class DspMathTest {
    static int fails = 0, total = 0;

    static void check(boolean c, String n) {
        total++;
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static final int N = 256;
    static final int SR = 48000;

    static double mag(float[] re, float[] im, int k) {
        return Math.sqrt(re[k] * re[k] + im[k] * im[k]);
    }

    /** Steady-state gain of a filter at a frequency, by RMS ratio over a settled window. */
    static double gainAt(Biquad b, double hz) {
        b.reset();
        int n = SR / 20;                       // 50 ms
        double inE = 0, outE = 0;
        for (int i = 0; i < n; i++) {
            float x = (float) Math.sin(2 * Math.PI * hz * i / SR);
            float y = b.process(x);
            if (i > n / 2) {                   // skip the transient
                inE += x * x;
                outE += y * y;
            }
        }
        return Math.sqrt(outE / Math.max(1e-12, inE));
    }

    static double chainGainAt(Biquad[] c, double hz) {
        for (Biquad b : c) b.reset();
        int n = SR / 20;
        double inE = 0, outE = 0;
        for (int i = 0; i < n; i++) {
            float x = (float) Math.sin(2 * Math.PI * hz * i / SR);
            float y = Biquad.processChain(c, x);
            if (i > n / 2) { inE += x * x; outE += y * y; }
        }
        return Math.sqrt(outE / Math.max(1e-12, inE));
    }

    public static void main(String[] args) {

        // ── FFT: known answers ──────────────────────────────────────────────────────
        // 1. An impulse at n=0 has a FLAT spectrum, every bin magnitude exactly 1.
        float[] re = new float[N], im = new float[N];
        re[0] = 1f;
        Fft.transform(re, im);
        double lo = Double.MAX_VALUE, hi = 0;
        for (int k = 0; k < N; k++) {
            double m = mag(re, im, k);
            lo = Math.min(lo, m);
            hi = Math.max(hi, m);
        }
        System.out.println("      impulse spectrum: min " + String.format("%.6f", lo)
                + "  max " + String.format("%.6f", hi));
        check(Math.abs(lo - 1.0) < 1e-4 && Math.abs(hi - 1.0) < 1e-4,
                "impulse gives a FLAT unit spectrum (the definition of an impulse)");

        // 2. DC puts ALL energy in bin 0 and nothing anywhere else.
        re = new float[N]; im = new float[N];
        for (int i = 0; i < N; i++) re[i] = 1f;
        Fft.transform(re, im);
        double dc = mag(re, im, 0), leak = 0;
        for (int k = 1; k < N; k++) leak = Math.max(leak, mag(re, im, k));
        System.out.println("      DC: bin0 " + String.format("%.3f", dc)
                + "  worst other bin " + String.format("%.6f", leak));
        check(Math.abs(dc - N) < 1e-2, "DC lands entirely in bin 0 with magnitude N (" + dc + ")");
        check(leak < 1e-3, "and leaks nothing into any other bin (" + leak + ")");

        // 3. A sinusoid at exactly bin k concentrates there and at its mirror N-k.
        final int K = 8;
        re = new float[N]; im = new float[N];
        for (int i = 0; i < N; i++) re[i] = (float) Math.cos(2 * Math.PI * K * i / N);
        Fft.transform(re, im);
        double atK = mag(re, im, K), atMirror = mag(re, im, N - K), elsewhere = 0;
        for (int k = 0; k < N; k++) {
            if (k == K || k == N - K) continue;
            elsewhere = Math.max(elsewhere, mag(re, im, k));
        }
        check(Math.abs(atK - N / 2.0) < 1e-2, "a bin-aligned cosine puts N/2 in bin K (" + atK + ")");
        check(Math.abs(atMirror - N / 2.0) < 1e-2, "and N/2 in its mirror bin N-K");
        check(elsewhere < 1e-2, "with nothing elsewhere (" + elsewhere + ")");

        // 4. PARSEVAL. Total energy is conserved between domains. This is the check that
        //    catches a wrong scale factor or a dropped twiddle — the spectrum can look
        //    entirely reasonable and still be wrong by a constant.
        float[] x = new float[N];
        long seed = 7;
        double timeE = 0;
        for (int i = 0; i < N; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            x[i] = ((seed >>> 40) % 2000 - 1000) / 1000f;
            timeE += x[i] * x[i];
        }
        re = x.clone(); im = new float[N];
        Fft.transform(re, im);
        double freqE = 0;
        for (int k = 0; k < N; k++) freqE += re[k] * re[k] + im[k] * im[k];
        freqE /= N;
        System.out.println("      Parseval: time " + String.format("%.4f", timeE)
                + "  freq/N " + String.format("%.4f", freqE));
        check(Math.abs(timeE - freqE) / Math.max(1e-9, timeE) < 1e-4,
                "Parseval holds — energy is conserved (rel err "
                        + String.format("%.2e", Math.abs(timeE - freqE) / timeE) + ")");

        // 5. Nyquist: alternating +1/-1 is the highest representable frequency.
        re = new float[N]; im = new float[N];
        for (int i = 0; i < N; i++) re[i] = (i % 2 == 0) ? 1f : -1f;
        Fft.transform(re, im);
        check(Math.abs(mag(re, im, N / 2) - N) < 1e-2,
                "alternating sign lands at Nyquist, bin N/2 (" + mag(re, im, N / 2) + ")");

        // NEGCTRL: the impulse check would pass on a transform that returns its input
        // untouched, since an impulse IS flat in time too. A cosine would not survive that,
        // so this pins the transform as a real one.
        check(Math.abs(atK - N / 2.0) < 1e-2 && atK > 100,
                "NEGCTRL: a passthrough would NOT produce N/2 at bin K");

        // ── BIQUAD: known answers ──────────────────────────────────────────────────
        Biquad lp = Biquad.lowpass(SR, 1000);
        double lpDc = gainAt(lp, 20), lpPass = gainAt(lp, 200), lpStop = gainAt(lp, 12000);
        System.out.println("      lowpass@1k: 20Hz " + String.format("%.3f", lpDc)
                + "  200Hz " + String.format("%.3f", lpPass)
                + "  12kHz " + String.format("%.4f", lpStop));
        check(Math.abs(lpPass - 1.0) < 0.10, "lowpass passes below its corner (" + lpPass + ")");
        check(lpStop < 0.05, "lowpass stops well above it (" + lpStop + ")");

        Biquad hp = Biquad.highpass(SR, 1000);
        double hpStop = gainAt(hp, 60), hpPass = gainAt(hp, 8000);
        System.out.println("      highpass@1k: 60Hz " + String.format("%.4f", hpStop)
                + "  8kHz " + String.format("%.3f", hpPass));
        check(hpPass > 0.90, "highpass passes above its corner (" + hpPass + ")");
        check(hpStop < 0.05, "highpass stops well below it (" + hpStop + ")");
        check(lpStop < lpPass && hpStop < hpPass, "the two filters are genuinely opposite");

        // Peaking: 0 dB must be EXACTLY unity everywhere. This is the one that catches a
        // filter that quietly colours the signal even when told to do nothing — which is
        // precisely the defect the EQ shipped with (an attenuating bandpass ignoring gainDb).
        Biquad flat = Biquad.peaking(SR, 3000, 0.0, 1.0);
        double f1 = gainAt(flat, 300), f2 = gainAt(flat, 3000), f3 = gainAt(flat, 12000);
        System.out.println("      peaking 0dB: 300Hz " + String.format("%.4f", f1)
                + "  3kHz " + String.format("%.4f", f2)
                + "  12kHz " + String.format("%.4f", f3));
        check(Math.abs(f1 - 1) < 0.01 && Math.abs(f2 - 1) < 0.01 && Math.abs(f3 - 1) < 0.01,
                "a 0 dB peaking filter is unity at EVERY frequency");

        Biquad boost = Biquad.peaking(SR, 3000, 12.0, 1.0);
        double bAt = gainAt(boost, 3000), bFar = gainAt(boost, 200);
        System.out.println("      peaking +12dB@3k: at 3kHz " + String.format("%.3f", bAt)
                + "  at 200Hz " + String.format("%.3f", bFar));
        check(Math.abs(bAt - 3.98) < 0.30, "+12 dB really is ~3.98x at the centre (" + bAt + ")");
        check(Math.abs(bFar - 1.0) < 0.15, "and ~unity far away (" + bFar + ")");

        Biquad cut = Biquad.peaking(SR, 3000, -12.0, 1.0);
        double cAt = gainAt(cut, 3000);
        check(Math.abs(cAt - 0.251) < 0.06, "-12 dB really is ~0.251x at the centre (" + cAt + ")");
        check(cAt < 1.0 && bAt > 1.0, "boost and cut go in OPPOSITE directions");

        // reset() must actually clear state, or a filter reused across clips carries the
        // previous clip's tail into the next one.
        Biquad r = Biquad.peaking(SR, 3000, 12.0, 1.0);
        for (int i = 0; i < 5000; i++) r.process((float) Math.sin(i));
        r.reset();
        float first = r.process(1f);
        Biquad fresh = Biquad.peaking(SR, 3000, 12.0, 1.0);
        float firstFresh = fresh.process(1f);
        check(Math.abs(first - firstFresh) < 1e-6,
                "reset() truly clears state (" + first + " vs " + firstFresh + ")");

        // Stability: a long, loud run must not blow up.
        Biquad st = Biquad.peaking(SR, 3000, 12.0, 8.0);
        float last = 0;
        for (int i = 0; i < 200_000; i++) last = st.process((i % 2 == 0) ? 1f : -1f);
        check(!Float.isNaN(last) && !Float.isInfinite(last) && Math.abs(last) < 100,
                "a high-Q filter stays stable over 200k samples (" + last + ")");

        System.out.println(fails == 0 ? ("ALL GREEN (" + total + "/" + total + ")")
                : (fails + " FAILED of " + total));
        if (fails != 0) System.exit(1);
    }
}
