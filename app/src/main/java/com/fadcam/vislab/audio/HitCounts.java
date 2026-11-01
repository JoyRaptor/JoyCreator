package com.fadcam.vislab.audio;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Every beat and kick of a whole sound, found once from its start: how many have landed by any
 * moment is then a lookup, the same answer playing, scrubbing, exporting or after a reopen. A
 * count kept while listening only knows the part it heard, so a frame-per-beat sprite showed one
 * frame in the preview after a seek and another in the export.
 *
 * <p>The analysis is VisLab's own, stepped as {@code SoundClock} steps it. Pure Java: the JVM
 * harness tests it.</p>
 */
public final class HitCounts {

    /** The sound's samples, -1..1. */
    public interface Pcm {
        float at(int index);
    }

    private final double step;
    private final int[] beatSteps, kickSteps;

    HitCounts(double step, @NonNull int[] beatSteps, @NonNull int[] kickSteps) {
        this.step = step;
        this.beatSteps = beatSteps;
        this.kickSteps = kickSteps;
    }

    /**
     * Listen to all of it.
     *
     * @param stop polled now and then
     * @return the counts, or null when stopped
     */
    public static HitCounts scan(@NonNull Pcm pcm, int frames, int rate, int fft, double step,
                                 @NonNull BooleanSupplier stop) {
        SpectrumAnalyzer spectrum = new SpectrumAnalyzer(fft);
        FeatureExtractor extractor = new FeatureExtractor(rate, fft);
        float[] mono = new float[fft], wave = new float[fft];
        Steps beats = new Steps(), kicks = new Steps();
        int total = (int) Math.floor(frames / (double) rate / step);
        float lastPhase = -1f;
        for (int n = 1; n <= total; n++) {
            if ((n & 255) == 0 && stop.getAsBoolean()) return null;
            int start = (int) Math.round(n * step * rate) - fft;
            for (int i = 0; i < fft; i++) {
                int s = start + i;
                mono[i] = s >= 0 && s < frames ? pcm.at(s) : 0f;
                wave[i] = mono[i];   // the spectrum may window its input in place
            }
            Features f = extractor.update(spectrum.analyze(mono), wave, null, step);
            if (lastPhase >= 0f && f.beatPhase < lastPhase - 0.5f) beats.add(n);
            lastPhase = f.beatPhase;
            if (f.punch > 0f) kicks.add(n);
        }
        return new HitCounts(step, beats.done(), kicks.done());
    }

    /** Beats landed by {@code seconds} into the sound. */
    public long beatsAt(double seconds) {
        return landed(beatSteps, seconds);
    }

    /** Kicks landed by {@code seconds} into the sound. */
    public long kicksAt(double seconds) {
        return landed(kickSteps, seconds);
    }

    private long landed(@NonNull int[] steps, double seconds) {
        int n = (int) Math.floor(seconds / step + 1e-9);
        int i = Arrays.binarySearch(steps, n);
        return i >= 0 ? i + 1 : -i - 1;
    }

    private static final class Steps {
        private int[] v = new int[256];
        private int n;

        void add(int step) {
            if (n == v.length) v = Arrays.copyOf(v, n * 2);
            v[n++] = step;
        }

        int[] done() {
            return Arrays.copyOf(v, n);
        }
    }
}
