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
 *   <li><b>Every write stores a whole pose.</b> {@link #put} takes one; {@link #putComponents} and
 *       {@link #putHandle} name a few components and fill the rest from the value the track was
 *       already producing at that instant. Either way the unit of storage is the pose, so fifty
 *       independently-timed per-handle key lists are not discouraged, they are
 *       <i>unrepresentable</i>.
 *       <p><i>Amended 2026-09-15.</i> This file used to say there was no API that could address a
 *       single handle — true, and right for a lattice dot, but wrong for a puppet pin, which is
 *       named, selectable and individually performable. Without a per-pin write, live overdub is
 *       impossible: recording pin B erases pin A. The guard that actually mattered was never
 *       "one write per gesture", it was "one pose per instant", and that one still holds
 *       absolutely.</p></li>
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

    /**
     * Write SOME components at {@code timeMs}, leaving every other handle exactly as the animation
     * already had it. This is what makes live overdub possible.
     *
     * <h3>Why this does not break the one-diamond rule above</h3>
     * <p>It still writes a whole pose — it just fills the components nobody named from
     * {@link #valueAt}, the value the track was already producing at that instant. So:
     * <ul>
     *   <li><b>Recording pin B does not erase pin A.</b> Pin A keeps the value it was interpolating
     *       to, rather than snapping to zero, which is the bug a naive whole-pose write causes and
     *       the reason section 5.1 of the UI spec asked for this.</li>
     *   <li><b>One pose is still one diamond and one undo.</b> There is no per-handle storage and
     *       therefore no per-handle key times to fall out of step — see {@link #simplifyRange}.</li>
     *   <li><b>A chain is written atomically</b> by naming all of its components in one call. The
     *       limb cannot tear between two half-written pins because there is no instant at which it
     *       is half-written.</li>
     * </ul>
     *
     * <p>Seeding from the interpolated value is also section 5.5's "anchor in": a punch-in inherits
     * the existing animated value, so the in-point never jumps.
     *
     * @param componentIndices indices into the pose — for a topology with two components per handle
     *                         that is {@code handle*2} and {@code handle*2+1}
     * @param values           one value per named index, in the same order
     * @return false when the arrays disagree or an index is out of range; nothing is written
     */
    public boolean putComponents(long timeMs, int[] componentIndices, float[] values,
                                 String easingName) {
        if (componentIndices == null || values == null) return false;
        if (componentIndices.length != values.length) return false;
        for (int idx : componentIndices) {
            if (idx < 0 || idx >= arity) return false;
        }
        float[] pose = new float[arity];
        // An empty track seeds zeros, which is the identity pose for every deformer in the family.
        valueAt(timeMs, pose);
        for (int k = 0; k < componentIndices.length; k++) pose[componentIndices[k]] = values[k];
        return put(timeMs, pose, easingName);
    }

    /**
     * One handle, at one time. Thin wrapper over {@link #putComponents} — the caller supplies
     * {@code MeshTopology.handleComponents()} because this file deliberately knows nothing about
     * topologies.
     */
    public boolean putHandle(long timeMs, int handleIndex, int componentsPerHandle,
                             float[] handleValues, String easingName) {
        if (handleValues == null || componentsPerHandle <= 0) return false;
        if (handleValues.length != componentsPerHandle) return false;
        int[] idx = new int[componentsPerHandle];
        for (int c = 0; c < componentsPerHandle; c++) idx[c] = handleIndex * componentsPerHandle + c;
        return putComponents(timeMs, idx, handleValues, easingName);
    }

    /**
     * The instants at which the named components actually carry information — the tape for one pin,
     * or for one chain.
     *
     * <p>Derived, never stored. A pose counts for these components when dropping it would move
     * them by more than {@code tolerance}; a pose written while recording a different pin
     * therefore does NOT show up as a key on this pin's tape, even though it is a real pose.
     *
     * <p>The ends always count: they are where a value starts and stops being held.
     */
    public long[] componentTimes(int[] componentIndices, float tolerance) {
        if (componentIndices == null || componentIndices.length == 0 || poses.size() == 0) {
            return new long[0];
        }
        int n = poses.size();
        if (n <= 2) return times();
        java.util.ArrayList<Long> out = new java.util.ArrayList<>();
        out.add(poses.get(0).timeMs);
        for (int i = 1; i < n - 1; i++) {
            if (componentError(i - 1, i, i + 1, componentIndices) > tolerance) {
                out.add(poses.get(i).timeMs);
            }
        }
        out.add(poses.get(n - 1).timeMs);
        long[] t = new long[out.size()];
        for (int i = 0; i < t.length; i++) t[i] = out.get(i);
        return t;
    }

    /**
     * Thin the poses in {@code [fromMs, toMs]}, greedily dropping the least-informative one until
     * dropping another would move some component by more than {@code tolerance}.
     *
     * <h3>A chain simplifies on ONE set of key times, and this is why</h3>
     * <p>Section 5.2 of the UI spec warns that thinning each pin of an arm independently lands its
     * keys at different instants, after which the chain solves to a different shape between them
     * and the limb wobbles. That failure is <b>unrepresentable</b> here: the unit of storage is the
     * pose, so a pose is either kept for everyone or dropped for everyone. The error test below is
     * a MAXIMUM over every component, so a pose that matters to the wrist is kept for the elbow
     * too, at the same instant, by construction rather than by discipline.
     *
     * <p>Poses outside the range are never touched — a take covers part of a timeline.
     *
     * @return how many poses were removed
     */
    public int simplifyRange(long fromMs, long toMs, float tolerance) {
        if (tolerance < 0f || poses.size() <= 2) return 0;
        int[] all = new int[arity];
        for (int i = 0; i < arity; i++) all[i] = i;
        int removed = 0;
        while (true) {
            int best = -1;
            float bestErr = Float.MAX_VALUE;
            for (int i = 1; i < poses.size() - 1; i++) {
                long t = poses.get(i).timeMs;
                if (t < fromMs || t > toMs) continue;
                // A pose that an animation preset owns is not ours to thin away.
                if (poses.get(i).presetOwned) continue;
                float err = componentError(i - 1, i, i + 1, all);
                if (err < bestErr) { bestErr = err; best = i; }
            }
            if (best < 0 || bestErr > tolerance) break;
            poses.remove(best);
            removed++;
            if (poses.size() <= 2) break;
        }
        return removed;
    }

    /**
     * How far the named components would move if pose {@code mid} were dropped and the span
     * {@code left..right} interpolated straight through. The easing is deliberately ignored here:
     * this asks about the SHAPE of the data, and a hold curve would otherwise report every pose as
     * indispensable.
     */
    private float componentError(int left, int mid, int right, int[] componentIndices) {
        Pose a = poses.get(left), m = poses.get(mid), b = poses.get(right);
        long span = b.timeMs - a.timeMs;
        float t = span <= 0 ? 0f : (m.timeMs - a.timeMs) / (float) span;
        float worst = 0f;
        for (int idx : componentIndices) {
            if (idx < 0 || idx >= arity) continue;
            float lerped = a.values[idx] + (b.values[idx] - a.values[idx]) * t;
            float err = Math.abs(m.values[idx] - lerped);
            if (err > worst) worst = err;
        }
        return worst;
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
