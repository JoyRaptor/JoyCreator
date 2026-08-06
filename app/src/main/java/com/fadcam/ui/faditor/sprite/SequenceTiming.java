package com.fadcam.ui.faditor.sprite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * WEIGHTS — the whole timing model for image sequences (SPEC_IMAGE_SEQUENCE §2), as pure
 * arithmetic with no Android and no state.
 *
 * <p>Every frame carries a weight, default 1, and
 * {@code frame_duration = total_duration × weight / Σ weights}. That single idea collapses the
 * feature list: "on ones" is all-1s, "on twos" is all-2s, a five-beat hold is {@code w=5}, a
 * slideshow with uneven slides is proportions, and "gradually faster" is a descending ramp.</p>
 *
 * <p><b>Why weights and not per-frame milliseconds</b> (§2): resizing the object must not destroy
 * authored holds. Stretching rescales everything and every hold survives proportionally, for
 * free. It is also what makes §7's AI tractable — a sequence's entire timing is ONE integer
 * array, a shape a model can read and rewrite.</p>
 *
 * <h3>How this folds into the EXISTING sprite model rather than replacing it</h3>
 * The spec's §0 is emphatic that a second evaluator must not be written. It is not. Weights ride
 * {@link SpriteSheet.Preset}, and the tick domain is the one
 * {@link SpriteFrameResolver} already computes:
 *
 * <pre>  frameOrdinal = floor(elapsedMs × fps / 1000)</pre>
 *
 * With no weights, tick <i>n</i> is frame <i>n</i> — exactly today's behaviour, which is why an
 * unweighted preset is byte-for-byte the animation it always was. With weights, tick <i>n</i> is
 * looked up through the cumulative weight array instead. <b>fps stays the stored authority and
 * total duration is a derived VIEW of it</b> ({@code fps = Σw / totalSeconds}, §2's own
 * equation), so "frame rate", "seconds per frame" and "total duration" are three windows onto one
 * array rather than three modes.
 */
public final class SequenceTiming {

    private SequenceTiming() {}

    /** Weight of a frame that has never been edited. */
    public static final int DEFAULT_WEIGHT = 1;

    /** A frame may not vanish: the minimum weight is one tick (§5b "min 1"). */
    public static final int MIN_WEIGHT = 1;

    /**
     * Upper bound on a single frame's weight. Not a model limit — a guard, so a fat-fingered
     * drag or a hallucinated AI value cannot produce a hold measured in hours that the user then
     * has to find and undo.
     */
    public static final int MAX_WEIGHT = 9999;

    // ── The array ────────────────────────────────────────────────────────────

    /** Σ weights, treating a missing/short array as 1 per frame. Never less than {@code n}. */
    public static int totalWeight(@Nullable List<Integer> weights, int frameCount) {
        if (frameCount <= 0) return 0;
        if (weights == null || weights.isEmpty()) return frameCount;
        int sum = 0;
        for (int i = 0; i < frameCount; i++) sum += weightAt(weights, i);
        return sum;
    }

    /**
     * Weight of frame {@code i}, tolerating a null, short or junk-bearing array.
     *
     * <p>Tolerant rather than validating on purpose: weights arrive from hand-edited JSON and
     * from an LLM, and the house convention for both is that a malformed element drops itself
     * back to the default instead of taking the project down with it.</p>
     */
    public static int weightAt(@Nullable List<Integer> weights, int i) {
        if (weights == null || i < 0 || i >= weights.size()) return DEFAULT_WEIGHT;
        Integer w = weights.get(i);
        if (w == null) return DEFAULT_WEIGHT;
        return clampWeight(w);
    }

    public static int clampWeight(int w) {
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, w));
    }

    /** A fresh all-default array of length {@code n}. */
    @NonNull
    public static List<Integer> defaultWeights(int n) {
        List<Integer> out = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) out.add(DEFAULT_WEIGHT);
        return out;
    }

    /**
     * {@code weights} normalised to exactly {@code frameCount} entries — padded with the default
     * and truncated as needed.
     *
     * <p>Called wherever a frame count can change under an existing array (a re-import that finds
     * more files, an ABSOLUTE resize, a reorder). Without it a stale short array reads as "the
     * tail is all 1s", which is a silent timing change rather than a visible one.</p>
     */
    @NonNull
    public static List<Integer> fit(@Nullable List<Integer> weights, int frameCount) {
        List<Integer> out = new ArrayList<>(Math.max(0, frameCount));
        for (int i = 0; i < frameCount; i++) out.add(weightAt(weights, i));
        return out;
    }

    // ── Tick ⇄ frame (what the resolver actually asks) ───────────────────────

    /**
     * The frame index occupying tick {@code tick} of one FORWARD pass.
     *
     * <p>Frame <i>i</i> owns {@code w[i]} consecutive ticks. Ticks outside
     * {@code [0, totalWeight)} clamp to the ends, so a caller that has already applied its own
     * wrap cannot fall off either edge.</p>
     */
    public static int indexAtTick(@Nullable List<Integer> weights, int frameCount, long tick) {
        if (frameCount <= 0) return 0;
        if (tick <= 0) return 0;
        long acc = 0;
        for (int i = 0; i < frameCount; i++) {
            acc += weightAt(weights, i);
            if (tick < acc) return i;
        }
        return frameCount - 1;
    }

    /** First tick of frame {@code i} in a forward pass — the inverse of {@link #indexAtTick}. */
    public static long tickAtIndex(@Nullable List<Integer> weights, int frameCount, int i) {
        long acc = 0;
        for (int k = 0; k < Math.min(i, frameCount); k++) acc += weightAt(weights, k);
        return acc;
    }

    /**
     * Period of one ping-pong cycle in ticks.
     *
     * <p>The mirror runs {@code n-2 … 1}: the two endpoints are NOT repeated, or the sequence
     * visibly stutters on them. So the period is {@code 2Σw − w[0] − w[n-1]}, and each mirrored
     * frame keeps its OWN weight — which is §6's "ping-pong must preserve weights when it
     * mirrors", and is automatic here rather than a special case.</p>
     */
    public static long pingPongPeriod(@Nullable List<Integer> weights, int frameCount) {
        if (frameCount <= 1) return Math.max(1, totalWeight(weights, frameCount));
        long total = totalWeight(weights, frameCount);
        return Math.max(1, 2 * total - weightAt(weights, 0) - weightAt(weights, frameCount - 1));
    }

    /** The frame index at tick {@code tick} of a ping-pong cycle (already reduced by period). */
    public static int pingPongIndexAtTick(@Nullable List<Integer> weights, int frameCount,
                                          long tick) {
        if (frameCount <= 1) return 0;
        long total = totalWeight(weights, frameCount);
        if (tick < total) return indexAtTick(weights, frameCount, tick);
        // Past the forward pass: walk the mirror n-2 … 1, each with its own weight.
        long acc = total;
        for (int i = frameCount - 2; i >= 1; i--) {
            acc += weightAt(weights, i);
            if (tick < acc) return i;
        }
        return 0;
    }

    // ── The three VIEWS of the array (§2: not three modes) ───────────────────

    /**
     * fps such that one forward pass fills exactly {@code totalMs}.
     * This is §2's {@code fps = Σweights / total_duration}, and it is how "set the total
     * duration" and "set the frame rate" end up writing the same field.
     */
    public static float fpsForTotalMs(@Nullable List<Integer> weights, int frameCount,
                                      long totalMs) {
        if (totalMs <= 0 || frameCount <= 0) return 1f;
        return clampFps(totalWeight(weights, frameCount) * 1000f / totalMs);
    }

    /** Length of one forward pass at {@code fps}, in ms — the inverse of the above. */
    public static long totalMsForFps(@Nullable List<Integer> weights, int frameCount, float fps) {
        if (frameCount <= 0) return 0;
        return Math.max(1, Math.round(totalWeight(weights, frameCount) * 1000.0 / clampFps(fps)));
    }

    /** Mean ms per frame — the "duration per image" field of the import dialog (§3b). */
    public static float perFrameMsForFps(@Nullable List<Integer> weights, int frameCount,
                                         float fps) {
        if (frameCount <= 0) return 0f;
        return totalMsForFps(weights, frameCount, fps) / (float) frameCount;
    }

    /**
     * fps such that the AVERAGE frame lasts {@code perFrameMs}. With all-default weights this is
     * exactly {@code 1000/perFrameMs}; with holds it is the rate that makes the whole run come
     * out at {@code frameCount × perFrameMs}, which is what someone typing "3 seconds each"
     * means even when three of the slides are held.
     */
    public static float fpsForPerFrameMs(@Nullable List<Integer> weights, int frameCount,
                                         float perFrameMs) {
        if (perFrameMs <= 0f || frameCount <= 0) return 1f;
        return fpsForTotalMs(weights, frameCount, Math.round(perFrameMs * frameCount));
    }

    /**
     * Frame rate bounds. The floor is not cosmetic — fps is a DIVISOR in
     * {@link #totalMsForFps}, and a zero reaching it from hand-edited JSON is an infinite hold.
     */
    public static final float MIN_FPS = 0.01f;
    public static final float MAX_FPS = 240f;

    public static float clampFps(float fps) {
        if (Float.isNaN(fps)) return 1f;
        return Math.max(MIN_FPS, Math.min(MAX_FPS, fps));
    }

    // ── The two resize modes (§2a) ───────────────────────────────────────────

    /** What dragging the object's edge MEANS. Visible on the tape, never only in a drawer. */
    public enum ResizeMode {
        /**
         * Keep the weights, change the total duration. Ten images squeezed to half the length
         * are still ten images, each half as long. The slideshow/animation default.
         */
        RELATIVE,
        /**
         * Keep each frame's resolved milliseconds, change the frame COUNT. Ten one-second images
         * squeezed to five seconds shows five frames. The "film strip" mental model.
         */
        ABSOLUTE;

        @NonNull
        public static ResizeMode fromName(@Nullable String s) {
            return "ABSOLUTE".equalsIgnoreCase(s) ? ABSOLUTE : RELATIVE;
        }
    }

    /**
     * How many frames of a forward pass fit in {@code spanMs} at {@code fps} — the ABSOLUTE
     * resize answer, and the frame-count half of the §9c live readout.
     *
     * <p>Counts frames that START inside the span, so a frame only partly visible at the edge
     * still counts as shown. Always at least 1: an object holding zero frames would render
     * nothing while still occupying the timeline, which reads as a bug rather than a short clip.
     */
    public static int framesFittingIn(@Nullable List<Integer> weights, int frameCount,
                                      float fps, long spanMs) {
        if (frameCount <= 0 || spanMs <= 0) return 0;
        long ticks = (long) Math.floor(spanMs * clampFps(fps) / 1000.0);
        if (ticks <= 0) return 1;
        // Count frames whose START tick falls inside the span. Asking instead which frame
        // CONTAINS the final tick counts one too many: a span of 5 ticks covers ticks 0..4, so
        // the frame beginning exactly at tick 5 is the first one that does not appear.
        long acc = 0;
        for (int i = 0; i < frameCount; i++) {
            if (acc >= ticks) return i;
            acc += weightAt(weights, i);
        }
        return frameCount;
    }

    /**
     * The §9c live readout while dragging an edge: {@code "24 frames · 4.0s · 6.0 fps"}.
     *
     * <p>Since aligning by eye against music is the sanctioned method for beat-syncing
     * (§9b's third reason), this is what makes the eye accurate — it turns "about right" into
     * "landed on 6 fps exactly". Built here, next to the arithmetic it reports, so the number on
     * screen cannot drift from the number the resolver uses.</p>
     */
    @NonNull
    public static String readout(@Nullable List<Integer> weights, int frameCount,
                                 @NonNull ResizeMode mode, float fps, long spanMs) {
        int shown = mode == ResizeMode.ABSOLUTE
                ? framesFittingIn(weights, frameCount, fps, spanMs) : frameCount;
        float effFps = mode == ResizeMode.ABSOLUTE
                ? clampFps(fps) : fpsForTotalMs(weights, frameCount, spanMs);
        return String.format(java.util.Locale.US, "%d frame%s · %.1fs · %.1f fps",
                shown, shown == 1 ? "" : "s", spanMs / 1000f, effFps);
    }

    // ── Bulk / pattern edits (§5c toolkit, and §7b's AI tools call these too) ─

    /**
     * Set every selected index to {@code weight}. {@code selection} empty = the whole array,
     * matching §5b's "with nothing selected it affects the frame under the finger" generalised
     * to the batch tools, where "nothing selected" means "all".
     */
    @NonNull
    public static List<Integer> setWeight(@Nullable List<Integer> weights, int frameCount,
                                          @Nullable List<Integer> selection, int weight) {
        List<Integer> out = fit(weights, frameCount);
        int w = clampWeight(weight);
        if (selection == null || selection.isEmpty()) {
            for (int i = 0; i < out.size(); i++) out.set(i, w);
        } else {
            for (Integer i : selection) {
                if (i != null && i >= 0 && i < out.size()) out.set(i, w);
            }
        }
        return out;
    }

    /**
     * Add {@code delta} to every selected index — the §5b vertical drag, which applies to the
     * SELECTION rather than one frame. That is the whole anti-tedium point: it is what stops
     * "clicking a button 200 times across 20 images".
     */
    @NonNull
    public static List<Integer> nudge(@Nullable List<Integer> weights, int frameCount,
                                      @Nullable List<Integer> selection, int delta) {
        List<Integer> out = fit(weights, frameCount);
        if (selection == null || selection.isEmpty()) return out;
        for (Integer i : selection) {
            if (i != null && i >= 0 && i < out.size()) {
                out.set(i, clampWeight(out.get(i) + delta));
            }
        }
        return out;
    }

    /**
     * §5c.2 / §7b {@code applyStride}: every {@code every}-th frame from {@code start} gets
     * {@code weight}. Serves the user's own example — "every 6th animation hold for five frames"
     * — and is also how "on twos" is expressed with {@code start=0, every=1, weight=2}.
     */
    @NonNull
    public static List<Integer> applyStride(@Nullable List<Integer> weights, int frameCount,
                                            int start, int every, int weight) {
        List<Integer> out = fit(weights, frameCount);
        int step = Math.max(1, every);
        int w = clampWeight(weight);
        for (int i = Math.max(0, start); i < out.size(); i += step) out.set(i, w);
        return out;
    }

    /**
     * §5c.5 / §7b {@code applyRamp}: weights interpolating {@code w0 → w1} across
     * {@code [fromIdx, toIdx]} through {@code ease}. This is "a walk cycle gradually getting
     * faster" as a single control.
     *
     * <p>The endpoints are placed exactly, so a ramp always starts and ends on the numbers that
     * were asked for regardless of the curve in between.</p>
     */
    @NonNull
    public static List<Integer> applyRamp(@Nullable List<Integer> weights, int frameCount,
                                          int fromIdx, int toIdx, int w0, int w1,
                                          @Nullable com.fadcam.ui.faditor.keyframe.Easing ease) {
        List<Integer> out = fit(weights, frameCount);
        int a = Math.max(0, Math.min(fromIdx, toIdx));
        int b = Math.min(out.size() - 1, Math.max(fromIdx, toIdx));
        if (a > b) return out;
        // Ramp direction follows the ARGUMENTS, not the sorted bounds: "ramp from frame 20 back
        // to frame 5" should still run w0 at 20 and w1 at 5.
        boolean reversed = fromIdx > toIdx;
        int span = b - a;
        for (int i = a; i <= b; i++) {
            float t = span == 0 ? 0f : (i - a) / (float) span;
            if (reversed) t = 1f - t;
            float e = ease == null ? t : ease.apply(t);
            out.set(i, clampWeight(Math.round(w0 + (w1 - w0) * e)));
        }
        return out;
    }

    /** §5c.7 reverse — an ORDER operation, so the weights travel with their frames. */
    public static void reverse(@NonNull List<String> frameUris, @NonNull List<Integer> weights) {
        Collections.reverse(frameUris);
        Collections.reverse(weights);
    }

    /**
     * §5c.7 shuffle. Seeded rather than {@code Math.random()} so a shuffle is reproducible from
     * the project, and so the JVM harness can assert on it at all.
     */
    public static void shuffle(@NonNull List<String> frameUris, @NonNull List<Integer> weights,
                               long seed) {
        int n = Math.min(frameUris.size(), weights.size());
        Random rnd = new Random(seed);
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            Collections.swap(frameUris, i, j);
            Collections.swap(weights, i, j);
        }
    }

    // ── Import presets (§3b) ────────────────────────────────────────────────

    /** "Animation" — thinking in frames, holds and "on twos". */
    public static final float PRESET_ANIMATION_FPS = 12f;
    /** "Slideshow" — ~3 seconds per image. */
    public static final float PRESET_SLIDESHOW_MS_PER_FRAME = 3000f;
}
