package cc.joycreator.avatar.perform;

import java.util.List;

/**
 * The voice kept with a take (a port of the pure half of {@code tools/avatarlab/al-voice.js}).
 *
 * <p>The same rule as every other channel: a pass with the voice on (the Mic, or a voice file playing) replaces the
 * voice for the stretch it was on, and only that stretch; a few milliseconds are blended at each end, so there is no
 * click. A pass without it leaves the voice alone. The voice is exactly as long as the take and starts with it; it is
 * a mono 16-bit WAV at {@link #RATE} a second (a WAV of any rate reads back). Undo takes the pass back whole, voice and
 * all (the take's backup holds it).</p>
 *
 * <p>On the phone the recorder (Studio's {@code VoiceoverRecorder}) runs from the pass's first frame, so the sound's
 * time is the pass's time: a stretch's {@code t0} is its {@code ph0}.</p>
 */
public final class TakeVoice {
    public static final int RATE = 24000;
    /** 6 ms blended at each end of a stretch. */
    public static final int FADE = (int) Math.round(0.006 * RATE);

    /** One stretch of a pass during which the voice was on: the take's place [ph0, ph1) and the sound's time at ph0. */
    public static final class Stretch {
        public final double ph0, ph1, t0;

        public Stretch(double ph0, double ph1, double t0) {
            this.ph0 = ph0;
            this.ph1 = ph1;
            this.t0 = t0;
        }
    }

    private TakeVoice() {}

    /**
     * The voice for the whole take: the one it had (null when it had none, or when the pass began a fresh take) with
     * this pass's stretches laid over it. Null when there is nothing to keep.
     *
     * @param takeSeconds the take's length now
     * @param heard       what the microphone heard in this pass (its time is the sound's time)
     */
    public static Wav.Pcm compose(double takeSeconds, Wav.Pcm old, List<Stretch> stretches, Wav.Pcm heard) {
        int len = (int) Math.round(takeSeconds * RATE);
        if (old == null && stretches.isEmpty()) return null;
        float[] out = new float[len];
        if (old != null) {
            if (old.rate == RATE) System.arraycopy(old.d, 0, out, 0, Math.min(len, old.d.length));
            else for (int k = 0; k < len; k++) out[k] = (float) old.at(k / (double) RATE);
        }
        // a gentle three-tap smoothing before dropping to 24 000 a second, so a faster recording does not alias
        boolean smooth = heard != null && heard.rate / (double) RATE >= 1.5;
        for (Stretch s : stretches) {
            int p0 = Math.max(0, (int) Math.round(s.ph0 * RATE)), p1 = Math.min(len, (int) Math.round(s.ph1 * RATE));
            for (int k = p0; k < p1; k++) {
                double a = Math.min(1, Math.min((k - p0 + 1) / (double) FADE, (p1 - k) / (double) FADE));
                double h = heard == null ? 0 : heardAt(heard, s.t0 + (k / (double) RATE - s.ph0), smooth);
                out[k] = (float) (out[k] * (1 - a) + h * a);
            }
        }
        return new Wav.Pcm(RATE, out);
    }

    private static double heardAt(Wav.Pcm p, double t, boolean smooth) {
        double x = t * p.rate;
        int i = (int) Math.floor(x);
        double f = x - i;
        return tap(p, i, smooth) * (1 - f) + tap(p, i + 1, smooth) * f;
    }

    private static double tap(Wav.Pcm p, int j, boolean smooth) {
        return smooth ? 0.25 * get(p, j - 1) + 0.5 * get(p, j) + 0.25 * get(p, j + 1) : get(p, j);
    }

    private static double get(Wav.Pcm p, int j) {
        return j >= 0 && j < p.d.length ? p.d[j] : 0;
    }
}
