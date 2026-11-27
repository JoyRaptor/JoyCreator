package cc.joycreator.avatar.studio;

/**
 * A voice to a talking mouth: how open, and which mouth shape, from a short stretch of sound. A port of the pilot's
 * {@code P.listen} in {@code tools/avatarlab/al-inputs.js} (Hold to talk with the Mic or a Voice file), so a character
 * lip-synced in Studio moves its mouth as it does in the pilot's room.
 *
 * <p>The pilot reads the browser's analyser (a 1024-sample window, Blackman, 0.45 smoothing, bytes from -100 to
 * -30 dB) and sums four bands of it; this does the same sums on the same kind of numbers, with the window lasting as
 * long as the pilot's does at the browser's 48 000 a second.</p>
 *
 * <p>Plain Java; stateful only for the analyser's smoothing, so feed it windows in time order ({@link #reset} between
 * passes).</p>
 */
public final class VoiceMouth {

    /** Where the samples come from (a decoded sound layer, a recorded voice, a test's tone). */
    public interface Samples {
        int count();

        /** -1 to 1. */
        float at(int i);
    }

    public static final class Mouth {
        public final float open;
        public final String vis;

        Mouth(float open, String vis) {
            this.open = open;
            this.vis = vis;
        }
    }

    private static final double SMOOTH = 0.45, MIN_DB = -100, MAX_DB = -30;
    private static final double[] EDGES = {200, 520, 1250, 3000, 7200};

    private final int rate, size;
    private final double[] window, smoothed, re, im;
    private boolean primed;

    /** @param rate samples a second of what will be fed in */
    public VoiceMouth(int rate) {
        this.rate = rate;
        // the pilot's window is 1024 samples at the browser's 48 000: about 21 ms; the same time at this rate
        double target = rate * 1024.0 / 48000.0;
        int n = 64;
        while (n * 2 <= target * Math.sqrt(2)) n *= 2;   // the nearest power of two
        size = n;
        window = new double[size];
        for (int i = 0; i < size; i++) {
            double a = 2 * Math.PI * i / size;
            window[i] = 0.42 - 0.5 * Math.cos(a) + 0.08 * Math.cos(2 * a);
        }
        smoothed = new double[size / 2];
        re = new double[size];
        im = new double[size];
    }

    public int windowSize() {
        return size;
    }

    public void reset() {
        java.util.Arrays.fill(smoothed, 0);
        primed = false;
    }

    /** The mouth for the window that ends at sample {@code end} (samples before the start read as silence). */
    public Mouth at(Samples s, int end) {
        double sum = 0;
        for (int i = 0; i < size; i++) {
            int k = end - size + i;
            double v = k >= 0 && k < s.count() ? s.at(k) : 0;
            sum += v * v;
            re[i] = v * window[i];
            im[i] = 0;
        }
        double rms = Math.sqrt(sum / size);
        float open = (float) Math.pow(clamp((rms - 0.012) / 0.13, 0, 1), 0.65);
        fft(re, im);
        double hz = rate / (double) size;
        double[] b = new double[4];
        for (int i = 0; i < size / 2; i++) {
            double mag = Math.hypot(re[i], im[i]) / size;
            smoothed[i] = primed ? SMOOTH * smoothed[i] + (1 - SMOOTH) * mag : (1 - SMOOTH) * mag;
            if (i == 0) continue;
            double db = smoothed[i] > 0 ? 20 * Math.log10(smoothed[i]) : MIN_DB;
            double by = Math.floor(clamp(255 * (db - MIN_DB) / (MAX_DB - MIN_DB), 0, 255));
            double f = i * hz;
            for (int k = 0; k < 4; k++) if (f >= EDGES[k] && f < EDGES[k + 1]) b[k] += by;
        }
        primed = true;
        double tot = b[0] + b[1] + b[2] + b[3] + 1, lo = b[0] / tot, mid = b[1] / tot, him = b[2] / tot, hi = b[3] / tot;
        String vis;
        if (open < 0.06) vis = "Neutral";
        else if (hi > 0.42) vis = "S";
        else if (lo > 0.5 && him < 0.16) vis = open < 0.4 ? "W-Oo" : "Oh";
        else if (him > 0.3 && mid < 0.32) vis = "Ee";
        else vis = "Aa";
        return new Mouth(open, vis);
    }

    /**
     * The mouth for every frame of a stretch: frame {@code i} (0-based) is the window ending at its time
     * {@code startSec + i / fps} in the sound. Returns {open[], vis[]}.
     */
    public static Object[] frames(Samples s, int rate, double startSec, int fps, int n) {
        VoiceMouth vm = new VoiceMouth(rate);
        float[] open = new float[n];
        String[] vis = new String[n];
        for (int i = 0; i < n; i++) {
            int end = (int) Math.round((startSec + i / (double) fps) * rate);
            Mouth m = vm.at(s, end);
            open[i] = m.open < 0.015f ? 0f : m.open;   // the pilot's dead zone (al-inputs.js: sm.mouth < 0.015 is shut)
            vis[i] = m.vis;
        }
        return new Object[]{open, vis};
    }

    private static double clamp(double v, double a, double b) {
        return v < a ? a : v > b ? b : v;
    }

    /** In-place radix-2 FFT. */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }
}
