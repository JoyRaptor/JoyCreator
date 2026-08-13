package com.fadcam.ui.faditor.keyframe;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A bag of named {@link KeyframeTrack}s for one element (an overlay, or a clip's
 * zoom). Each track animates one scalar property; absent or empty tracks fall
 * back to the element's static value, so adding keyframes is purely additive.
 *
 * <p>Standard property keys: {@code x}, {@code y} (normalised centre 0..1),
 * {@code scale} (multiplier), {@code opacity} (0..1), {@code rotation} (degrees).
 * Zoom uses {@code zoom}, {@code zx}, {@code zy}.</p>
 */
public class KeyframeSet {

    public static final String X = "x";
    public static final String Y = "y";
    public static final String SCALE = "scale";
    /** Per-axis scale multipliers (the image-overlay chain unlink). Default 1 = no change. */
    public static final String SCALE_X = "scaleX";
    public static final String SCALE_Y = "scaleY";
    public static final String OPACITY = "opacity";
    public static final String ROTATION = "rotation";

    /**
     * How far a canvas position ({@link #X}/{@link #Y}, and a mask's centre) may travel, in
     * canvas-normalised units where 0..1 is the visible frame.
     *
     * <p><b>Why this is not 0..1.</b> These coordinates address an object's CENTRE, so a range
     * of 0..1 can only ever move an object until its centre reaches the edge — half of it is
     * still on screen at both extremes. Panning something on from off-stage left and away off
     * to the right, which is the most ordinary move in motion graphics, was simply not
     * expressible. It also produced a subtler bug: a mask linked to its object tracked it
     * correctly until the mask's centre hit the frame edge, then clamped and silently stopped
     * following (user, 2026-08-06).</p>
     *
     * <p>One full frame of travel beyond each edge takes any object up to 2× the frame size
     * completely out of view, and keeps the numbers legible (-100%..200%). It is a limit rather
     * than no limit at all so a slider still has ends and a stray keyframe cannot strand an
     * object a thousand frames away.</p>
     */
    public static final float POS_MIN = -1f;
    /** @see #POS_MIN */
    public static final float POS_MAX = 2f;

    /** Clamp a canvas position into {@link #POS_MIN}..{@link #POS_MAX}. */
    public static float clampPos(float v) {
        return v < POS_MIN ? POS_MIN : (v > POS_MAX ? POS_MAX : v);
    }

    /**
     * Hard ceiling on the size-aware rails below, so a stray keyframe still cannot strand an
     * object somewhere unreachable however large it is.
     */
    private static final float POS_ABS = 8f;

    /**
     * The position rails for an object whose extent is {@code extent} canvas-widths (or heights).
     *
     * <p><b>{@link #POS_MIN}'s reasoning only holds up to 2× the frame.</b> Its own note says one
     * frame of travel beyond each edge "takes any object up to 2× the frame size completely out of
     * view" — true, and false for anything bigger. At 400% the centre has to reach 2 frames out
     * before the trailing edge clears the canvas, and the fixed ±1 rail stopped it at exactly the
     * point where the object still covered the whole frame. JoyRaptor (2026-08-12): "I made it big and
     * I wanna have it come in from the right side. And I can't get it off screen. I can't move it
     * far enough" — which is the difference between being able to stage a pan-on and not.</p>
     *
     * <p>Half the extent beyond each edge is precisely "just fully off-frame", never further than
     * needed. The fixed rails stay the FLOOR, so every object at or below 2× keeps exactly the
     * range it has today and no existing project's numbers change.</p>
     */
    public static float posMinFor(float extent) {
        return Math.max(-POS_ABS, Math.min(POS_MIN, -Math.max(0f, extent) / 2f));
    }

    /** @see #posMinFor */
    public static float posMaxFor(float extent) {
        return Math.min(POS_ABS, Math.max(POS_MAX, 1f + Math.max(0f, extent) / 2f));
    }

    /** {@link #clampPos} with the size-aware rails. */
    public static float clampPos(float v, float extent) {
        float lo = posMinFor(extent), hi = posMaxFor(extent);
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @NonNull
    private final Map<String, KeyframeTrack> tracks = new LinkedHashMap<>();

    public boolean isEmpty() {
        for (KeyframeTrack t : tracks.values()) {
            if (!t.isEmpty()) return false;
        }
        return true;
    }

    /** True if any track has 2+ keyframes (i.e. actually animates). */
    public boolean isAnimated() {
        for (KeyframeTrack t : tracks.values()) {
            if (t.isAnimated()) return true;
        }
        return false;
    }

    @Nullable
    public KeyframeTrack get(@NonNull String property) {
        return tracks.get(property);
    }

    @NonNull
    public KeyframeTrack getOrCreate(@NonNull String property) {
        KeyframeTrack t = tracks.get(property);
        if (t == null) {
            t = new KeyframeTrack(property);
            tracks.put(property, t);
        }
        return t;
    }

    @NonNull
    public Iterable<KeyframeTrack> tracks() {
        return tracks.values();
    }

    /**
     * Move every key in every track by {@code deltaMs} along this set's own time base.
     *
     * <p>Used when the ELEMENT's time base moves under keys that must not move with it — see
     * {@code TextOverlayItem.setTrimmedTimeRange}. Shifting the keys by exactly the amount the
     * base moved is what leaves them where they were in project time.</p>
     *
     * <p><b>Negative times are kept, not clamped.</b> Trimming a start LATER pushes early keys
     * before the element's own zero, and {@code TextOverlayItem.localTime} floors its lookup at 0,
     * so such a key is simply never reached while still shaping the interpolation into the first
     * visible one. Clamping them to 0 instead would pile several keys onto the same instant and
     * destroy the animation — and trimming back out again could not restore it. Keeping them makes
     * the operation exactly reversible, which is what a trim handle has to be.</p>
     */
    public void shiftAll(long deltaMs) {
        if (deltaMs == 0) return;
        for (KeyframeTrack t : tracks.values()) {
            for (Keyframe k : t.keyframes) {
                k.timeMs += deltaMs;
            }
        }
    }

    public boolean hasProperty(@NonNull String property) {
        KeyframeTrack t = tracks.get(property);
        return t != null && !t.isEmpty();
    }

    /** Animated value of a property at time, or {@code fallback} if not keyed. */
    public float valueAt(@NonNull String property, long timeMs, float fallback) {
        KeyframeTrack t = tracks.get(property);
        return t == null ? fallback : t.valueAt(timeMs, fallback);
    }

    /** Drop a keyframe (or the whole track if it becomes empty). */
    public void removeKey(@NonNull String property, long timeMs) {
        KeyframeTrack t = tracks.get(property);
        if (t == null) return;
        t.removeAt(timeMs);
        if (t.isEmpty()) tracks.remove(property);
    }

    /**
     * Drop a whole named track. Needed by any model that DELETES an animatable thing —
     * {@code CompositingSpec.removeShape} is the first — because leaving the track behind
     * would resurrect the deleted object's animation the moment a new one reused the name.
     * (It cannot: slots are never reused. This is the belt to that suspenders.)
     *
     * @return true if a track was actually removed
     */
    public boolean removeProperty(@NonNull String property) {
        return tracks.remove(property) != null;
    }

    @NonNull
    public KeyframeSet copy() {
        KeyframeSet s = new KeyframeSet();
        for (Map.Entry<String, KeyframeTrack> e : tracks.entrySet()) {
            s.tracks.put(e.getKey(), e.getValue().copy());
        }
        return s;
    }

    /**
     * Replace this set's contents with a deep copy of {@code other}'s tracks.
     * Used by undo/redo to restore a snapshot in-place (the owning element holds
     * a final reference to its KeyframeSet, so it cannot be reassigned).
     */
    public void copyFrom(@NonNull KeyframeSet other) {
        tracks.clear();
        for (Map.Entry<String, KeyframeTrack> e : other.tracks.entrySet()) {
            tracks.put(e.getKey(), e.getValue().copy());
        }
    }
}
