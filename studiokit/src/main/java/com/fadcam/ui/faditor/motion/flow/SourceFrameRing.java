package com.fadcam.ui.faditor.motion.flow;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * The last few DECODED SOURCE frames of one clip slot, keyed by presentation time (STU-7 C6,
 * spec §4 and §6; Codex review 11-22 point 1).
 *
 * <p>Live flow needs the previous source frame, never effect output ({@code FxHistory} holds
 * effect output and would give trails). This class is the bookkeeping only: what to keep, when
 * a neighbour is valid, and when everything must go. The GL host owns the textures and passes a
 * release callback; {@code T} is its frame handle (a texture id holder in the app, anything in
 * tests).
 *
 * <p>Rules:
 * <ul>
 *   <li>A new frame from a different clip or source, a PTS that goes backwards (seek, loop), or a
 *       gap longer than {@code maxGapUs} flushes the ring first: frames from before the break are
 *       never paired with frames after it.</li>
 *   <li>{@link #invalidate()} (seek, cut, slot reassignment, size/rotation change, GL loss) flushes
 *       and bumps the generation. Async work stamped with an older generation must be dropped
 *       ({@link #isCurrent}).</li>
 *   <li>Capacity is fixed; the oldest frame is released when a new one arrives.</li>
 *   <li>A frame with the same PTS as the newest (a paused redraw) is released at once; the held
 *       copy stays.</li>
 *   <li>Until a valid neighbour exists, {@link #previous} returns null and the caller draws sharp.</li>
 * </ul>
 */
public final class SourceFrameRing<T> {

    /** One held frame. */
    public static final class Frame<T> {
        public final long ptsUs;
        public final String sourceKey;
        public final int width;
        public final int height;
        public final int rotation;
        public final T handle;

        public Frame(long ptsUs, String sourceKey, int width, int height, int rotation, T handle) {
            this.ptsUs = ptsUs;
            this.sourceKey = sourceKey;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
            this.handle = handle;
        }

        boolean sameShape(Frame<?> o) {
            return width == o.width && height == o.height && rotation == o.rotation
                    && sourceKey.equals(o.sourceKey);
        }
    }

    private final int capacity;
    private final long maxGapUs;
    private final Consumer<T> release;
    private final ArrayDeque<Frame<T>> frames = new ArrayDeque<>();
    private long generation;

    /**
     * @param capacity frames kept (2 = previous + current; 3 adds one for centred tracking)
     * @param maxGapUs a longer gap between consecutive frames counts as a break
     * @param release  called exactly once for every frame that leaves the ring
     */
    public SourceFrameRing(int capacity, long maxGapUs, Consumer<T> release) {
        if (capacity < 2) throw new IllegalArgumentException("need at least two frames");
        this.capacity = capacity;
        this.maxGapUs = maxGapUs;
        this.release = release;
    }

    public long generation() { return generation; }

    /** Whether work started at {@code gen} may still be used. */
    public boolean isCurrent(long gen) { return gen == generation; }

    public int size() { return frames.size(); }

    /** Adds the newest decoded frame, flushing first when it cannot continue the sequence. */
    public void push(Frame<T> frame) {
        Frame<T> last = frames.peekLast();
        if (last != null) {
            long gap = frame.ptsUs - last.ptsUs;
            if (gap == 0) {          // same frame redrawn (paused): keep the one we have
                release.accept(frame.handle);
                return;
            }
            if (gap < 0 || gap > maxGapUs || !last.sameShape(frame)) invalidate();
        }
        frames.addLast(frame);
        while (frames.size() > capacity) release.accept(frames.pollFirst().handle);
    }

    /** The newest frame, or null. */
    public Frame<T> newest() { return frames.peekLast(); }

    /**
     * The frame just before {@code ptsUs} in this unbroken run, or null when none is held (the
     * caller then draws sharp: spec §6, "a fresh seek or a cut shows a sharp frame").
     */
    public Frame<T> previous(long ptsUs) {
        Frame<T> best = null;
        for (Frame<T> f : frames) {
            if (f.ptsUs < ptsUs && (best == null || f.ptsUs > best.ptsUs)) best = f;
        }
        Frame<T> at = find(ptsUs);
        if (best == null || at == null || ptsUs - best.ptsUs > maxGapUs) return null;
        return best;
    }

    /** The frame just after {@code ptsUs}, or null (only export and paused frames have one). */
    public Frame<T> next(long ptsUs) {
        Frame<T> best = null;
        for (Frame<T> f : frames) {
            if (f.ptsUs > ptsUs && (best == null || f.ptsUs < best.ptsUs)) best = f;
        }
        Frame<T> at = find(ptsUs);
        if (best == null || at == null || best.ptsUs - ptsUs > maxGapUs) return null;
        return best;
    }

    /** Drops everything and bumps the generation. Call on seek, cut, slot change, GL loss. */
    public void invalidate() {
        generation++;
        for (Iterator<Frame<T>> it = frames.iterator(); it.hasNext(); ) {
            release.accept(it.next().handle);
            it.remove();
        }
    }

    private Frame<T> find(long ptsUs) {
        for (Frame<T> f : frames) if (f.ptsUs == ptsUs) return f;
        return null;
    }
}
