package com.fadcam.vislab.studio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The clocks behind {@link StudioEars}, one per WALKER of the timeline and source, and what each
 * heard lately. A walker is the preview or one export part: every effect in it hears the same
 * moment, so none needs a clock of its own (nine effects with a clock each pushed one another out
 * of the eight kept, and each replayed 2.2 s of analysis on every frame). An effect further down
 * the chain can be a frame behind the one before it, which a clock could only answer by starting
 * over, so a recent moment is answered from {@link #SEEN}. Pure Java: the JVM harness tests it.
 */
public final class EarShelf<C> {

    public interface Listen<C> {
        @NonNull Heard at(@NonNull C clock);
    }

    /** What a clock heard; {@code real} = false while it is deaf (not remembered). */
    public static final class Heard {
        final float[] values;
        final boolean real;

        public Heard(@NonNull float[] values, boolean real) {
            this.values = values;
            this.real = real;
        }
    }

    /** Moments remembered per walker: comfortably more frames than a chain holds at once. */
    private static final int SEEN = 32;
    /** Rounding apart, a remembered moment is this moment (60 fps frames are 16 ms apart). */
    private static final long SAME_MS = 1;

    private static final class Walker<C> {
        final C clock;
        final long[] seenMs = new long[SEEN];
        final float[][] seen = new float[SEEN][];
        int next;

        Walker(C clock) {
            this.clock = clock;
        }

        @Nullable
        float[] recall(long ms) {
            for (int i = 0; i < SEEN; i++) {
                if (seen[i] != null && Math.abs(seenMs[i] - ms) <= SAME_MS) return seen[i];
            }
            return null;
        }

        void keep(long ms, @NonNull float[] heard) {
            seenMs[next] = ms;
            seen[next] = heard;
            next = (next + 1) % SEEN;
        }
    }

    private final int maxClocks;
    private final Map<String, Walker<C>> walkers = new LinkedHashMap<>(8, 0.75f, true);

    public EarShelf(int maxClocks) {
        this.maxClocks = maxClocks;
    }

    /** What {@code key}'s clock hears at {@code sourceMs}. */
    @NonNull
    public float[] heard(@NonNull String key, long sourceMs, @NonNull Supplier<C> open,
                         @NonNull Listen<C> listen) {
        Walker<C> w = walkers.get(key);
        if (w == null) {
            w = new Walker<>(open.get());
            walkers.put(key, w);
            Iterator<Map.Entry<String, Walker<C>>> it = walkers.entrySet().iterator();
            while (walkers.size() > maxClocks && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        float[] before = w.recall(sourceMs);
        if (before != null) return before;
        Heard now = listen.at(w.clock);
        if (now.real) w.keep(sourceMs, now.values);
        return now.values;
    }
}
