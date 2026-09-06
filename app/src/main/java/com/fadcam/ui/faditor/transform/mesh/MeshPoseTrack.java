package com.fadcam.ui.faditor.transform.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ANIMATED DEFORMATION: a sorted list of POSES, where one pose is the WHOLE set of authored handle
 * values at one instant.
 *
 * <p>The track is typed on ONE number — {@link #arity()}, the count of floats in a pose — and that
 * number comes from the topology. It has no idea whether those floats are twenty-five lattice
 * nudges or seven puppet pins. That is the point: <b>puppeteering inherits this file unchanged.</b></p>
 *
 * <h3>Why this is a separate type and not fifty KeyframeTracks</h3>
 * <p>A 5x5 lattice is fifty floats. {@code KeyframeSet} holds one float per named track and
 * {@code KeyframeCodec} is a generic {@code property -> [{t,v,e}]} map, so fifty tracks would
 * serialise perfectly happily. Nothing would <i>fail</i>. What would happen instead is fifty
 * diamonds in the keyframe drawer and fifty track writes for one drag of one dot — while the
 * owner's standing ruling is that one press of undo takes back one gesture. Fifty would ship, and
 * then need unpicking.</p>
 *
 * <p>So the guard is structural rather than a rule someone has to remember:</p>
 * <ul>
 *   <li><b>The only write is {@link #put}, and it takes the whole pose.</b> There is no API here
 *       that can address a single handle at a single time. Fifty separate writes are not
 *       discouraged, they are <i>unrepresentable</i>.</li>
 *   <li><b>One pose is one diamond.</b> {@link #times} returns one entry per pose — the single
 *       "Shape" diamond the design promised, at every level and for every topology.</li>
 *   <li><b>Every pose shares the track's arity.</b> {@link #put} refuses an array of the wrong
 *       length, so the whole family of "keyframe A is 9 points and keyframe B is 25" bugs cannot
 *       exist. Changing topology goes through {@link #remap}, which rewrites every pose at once.</li>
 * </ul>
 *
 * <h3>Interpolation semantics are copied, not invented</h3>
 * <p>{@link #valueAt} is {@code KeyframeTrack.valueAt} with a vector where the float was: empty
 * gives nothing, one pose is a constant, values are HELD (not extrapolated) outside the range, and
 * a span's progress runs through the LEFT pose's easing — including {@code Easing.HOLD} returning 0
 * so the value steps. Deliberately identical, because a bend that eased differently from the
 * position it is attached to would look broken and nobody would be able to say why.</p>
 *
 * <p>No Android imports, and no FadCam import at all — see {@link Curve}.</p>
 */
public final class MeshPoseTrack {

    /**
     * The easing seam. This package must stay loadable on the desktop JVM harness and liftable into
     * another app, so it cannot import {@code com.fadcam.ui.faditor.keyframe.Easing} (which imports
     * {@code androidx.annotation}). A pose therefore stores the easing's NAME and asks a
     * {@link Curve} to apply it.
     *
     * <p>The one-line adapter the app installs is
     * {@code track.setCurve((name, t) -> Easing.fromName(name).apply(t))} — so there is exactly one
     * easing implementation in the app, not a second copy here that could drift from the first.
     * With no curve installed the track interpolates linearly, which is what the harness uses.</p>
     */
    public interface Curve {
        float apply(String easingName, float t);
    }

    /** Rewrites one pose when the topology changes. Supplied by whichever lane owns the topology. */
    public interface Remapper {
        float[] remap(float[] pose);
    }

    /** Default easing name, matching {@code Easing.fromName}'s own fallback. */
    public static final String DEFAULT_EASING = "EASE_IN_OUT";

    /** One instant of the whole deformation. */
    public static final class Pose {
        public long timeMs;
        /** The complete authored pose at this instant. Length is the track's arity. */
        public final float[] values;
        public String easing;
        /** True when an animation preset owns this pose, mirroring {@code Keyframe.presetOwned}. */
        public boolean presetOwned;

        Pose(long timeMs, float[] values, String easing) {
            this.timeMs = timeMs;
            this.values = values;
            this.easing = easing == null ? DEFAULT_EASING : easing;
        }

        public Pose copy() {
            Pose p = new Pose(timeMs, values.clone(), easing);
            p.presetOwned = presetOwned;
            return p;
        }
    }

    private int arity;
    private final List<Pose> poses = new ArrayList<>();
    private Curve curve;

    public MeshPoseTrack(int arity) {
        this.arity = Math.max(0, arity);
    }

    /** Floats in one pose. The topology's {@code handleArity()}, and nothing else. */
    public int arity() { return arity; }

    public int size() { return poses.size(); }

    public boolean isEmpty() { return poses.isEmpty(); }

    /** Two or more poses is what "this deformation actually moves" means. */
    public boolean isAnimated() { return poses.size() >= 2; }

    public List<Pose> poses() { return Collections.unmodifiableList(poses); }

    public void setCurve(Curve c) { this.curve = c; }

    /** The instants the drawer draws a diamond at — ONE per pose, never one per handle. */
    public long[] times() {
        long[] t = new long[poses.size()];
        for (int i = 0; i < t.length; i++) t[i] = poses.get(i).timeMs;
        return t;
    }

    /**
     * Write the whole pose at {@code timeMs}, replacing any pose already there.
     *
     * <p>This is the entire write surface, and it is why one gesture is one undo press: a drag calls
     * it exactly once, on commit, with the pose it ended on. There is no partial write to batch and
     * no per-handle write to collapse.</p>
     *
     * @return false when {@code values} is the wrong arity — refused rather than stored, so a
     *         mixed-arity track cannot come into existence
     */
    public boolean put(long timeMs, float[] values, String easingName) {
        if (values == null || values.length != arity) return false;
        for (int i = 0; i < poses.size(); i++) {
            if (poses.get(i).timeMs == timeMs) {
                Pose p = poses.get(i);
                System.arraycopy(values, 0, p.values, 0, arity);
                p.easing = easingName == null ? DEFAULT_EASING : easingName;
                return true;
            }
        }
        poses.add(new Pose(timeMs, values.clone(), easingName));
        sort();
        return true;
    }

    public void removeAt(long timeMs) {
        for (int i = 0; i < poses.size(); i++) {
            if (poses.get(i).timeMs == timeMs) {
                poses.remove(i);
                return;
            }
        }
    }

    public void clear() { poses.clear(); }

    /**
     * The pose at {@code timeMs}, written into {@code out}.
     *
     * <p>Allocation-free — {@code out} is the caller's, and a renderer holds one for the life of its
     * GL thread. Fifty component lerps per frame is noise next to a single texture fetch.</p>
     *
     * @return false when the track is empty or {@code out} is too short; {@code out} is then
     *         untouched and the caller uses the spec's static pose
     */
    public boolean valueAt(long timeMs, float[] out) {
        if (poses.isEmpty() || out == null || out.length < arity) return false;
        int n = arity;
        if (poses.size() == 1) {
            System.arraycopy(poses.get(0).values, 0, out, 0, n);
            return true;
        }
        Pose first = poses.get(0);
        if (timeMs <= first.timeMs) {
            System.arraycopy(first.values, 0, out, 0, n);
            return true;
        }
        Pose last = poses.get(poses.size() - 1);
        if (timeMs >= last.timeMs) {
            System.arraycopy(last.values, 0, out, 0, n);
            return true;
        }
        for (int i = 0; i < poses.size() - 1; i++) {
            Pose a = poses.get(i), b = poses.get(i + 1);
            if (timeMs >= a.timeMs && timeMs <= b.timeMs) {
                long span = b.timeMs - a.timeMs;
                if (span <= 0) {
                    System.arraycopy(b.values, 0, out, 0, n);
                    return true;
                }
                float t = (timeMs - a.timeMs) / (float) span;
                float eased = curve == null ? t : curve.apply(a.easing, t);
                for (int k = 0; k < n; k++) {
                    out[k] = a.values[k] + (b.values[k] - a.values[k]) * eased;
                }
                return true;
            }
        }
        System.arraycopy(last.values, 0, out, 0, n);
        return true;
    }

    /**
     * Move every pose along this track's own time base.
     *
     * <p>{@code KeyframeSet.shiftAll}'s contract, including the part that matters: <b>negative times
     * are kept, not clamped.</b> Trimming an object's start later pushes early poses before its own
     * zero; the lookup floors at zero so such a pose is never reached, but it still shapes the
     * interpolation into the first visible one — and trimming back out restores it exactly. Clamping
     * to zero would pile several poses onto one instant and destroy the animation irreversibly,
     * which a trim handle may not do.</p>
     */
    public void shiftAll(long deltaMs) {
        if (deltaMs == 0) return;
        for (Pose p : poses) p.timeMs += deltaMs;
    }

    /**
     * Rewrite every pose for a new topology, all at once.
     *
     * <p>The rewriting RULE is the topology lane's ({@link LatticeDeformer#subdivide} for the grid;
     * a pin resample for a puppet). This file only guarantees that no pose is left behind, which is
     * the invariant that makes mixed arity impossible.</p>
     *
     * @return false when the remapper returned a pose of the wrong length for {@code newArity};
     *         the track is then left completely unchanged
     */
    public boolean remap(int newArity, Remapper remapper) {
        if (remapper == null || newArity < 0) return false;
        List<Pose> rebuilt = new ArrayList<>(poses.size());
        for (Pose p : poses) {
            float[] v = remapper.remap(p.values);
            if (v == null || v.length != newArity) return false;
            Pose q = new Pose(p.timeMs, v, p.easing);
            q.presetOwned = p.presetOwned;
            rebuilt.add(q);
        }
        poses.clear();
        poses.addAll(rebuilt);
        arity = newArity;
        return true;
    }

    public MeshPoseTrack copy() {
        MeshPoseTrack t = new MeshPoseTrack(arity);
        for (Pose p : poses) t.poses.add(p.copy());
        t.curve = curve;
        return t;
    }

    private void sort() {
        Collections.sort(poses, new java.util.Comparator<Pose>() {
            @Override public int compare(Pose a, Pose b) { return Long.compare(a.timeMs, b.timeMs); }
        });
    }
}
