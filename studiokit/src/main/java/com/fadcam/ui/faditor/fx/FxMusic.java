package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Effect knobs that hear the soundtrack (roadmap R14, VisLab's music links in Studio): a card's
 * parameter can follow the loudness, the bass, the kick, the snare, the hi-hats or the beat of the
 * clip under it, pushed by an amount on top of its own (keyed or set) value. So Glow can bloom on
 * every kick, Shake can hit on the snare, a Warp can swell with the bass.
 *
 * <p>The sound is read by the host ({@link Ears}): the same analysis VisLab looks hear in Studio,
 * at the same timeline time in the preview and the export. Android-free.</p>
 */
public final class FxMusic {

    private FxMusic() {}

    /** Signal names, as stored, and how the panel names them. Add, never rename. */
    public static final String[] SIGNALS = {"level", "bass", "kick", "snare", "hat", "beat"};
    public static final String[] LABELS = {"Loudness", "Bass", "Kick", "Snare", "Hi-hats", "Beat"};

    /** The music at one moment: each signal 0..1. */
    public interface Ears {
        float get(@NonNull String signal);
    }

    /** One parameter's link: which signal, and how far it pushes (a share of the slider's range, -1..1). */
    public static final class Link {
        @NonNull public final String signal;
        public final float amount;

        public Link(@NonNull String signal, float amount) {
            this.signal = signal;
            this.amount = Math.max(-1f, Math.min(1f, amount));
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof Link && ((Link) o).signal.equals(signal) && ((Link) o).amount == amount;
        }

        @Override
        public int hashCode() {
            return signal.hashCode() * 31 + Float.floatToIntBits(amount);
        }
    }

    /** The panel's name for {@code signal}. */
    @NonNull
    public static String label(@NonNull String signal) {
        for (int i = 0; i < SIGNALS.length; i++) if (SIGNALS[i].equals(signal)) return LABELS[i];
        return signal;
    }

    /**
     * {@code value} pushed by the music: plus {@code amount} of the parameter's range at full
     * signal, kept inside the range. Only the first component of a multi-component parameter (a
     * point's x) is pushed; the panel offers links on single sliders only.
     */
    static float push(@NonNull FxParam p, float value, @NonNull Link l, @NonNull Ears ears) {
        float s = Math.max(0f, Math.min(1f, ears.get(l.signal)));
        float span = p.max - p.min;
        return Math.max(p.min, Math.min(p.max, value + l.amount * span * s));
    }
}
