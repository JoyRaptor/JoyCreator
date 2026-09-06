package com.fadcam.ui.faditor.transform.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ANIMATED BEND: a sorted list of POSES, where one pose is the WHOLE lattice at one instant.
 *
 * <h3>Why this is a separate type and not fifty KeyframeTracks</h3>
 * <p>A 5x5 lattice is fifty floats. {@code KeyframeSet} holds one float per named track and
 * {@code KeyframeCodec} is a generic {@code property -> [{t,v,e}]} map, so fifty tracks would
 * serialise perfectly happily. Nothing would <i>fail</i>. What would happen instead is fifty
 * diamonds in the keyframe drawer and fifty track writes for one drag of one dot — and the owner's
 * standing ruling is that one press of undo takes back one gesture, while
 * {@code PreviewHandlesOverlay}'s contract is one undo step per {@code commitPointDrag}. Fifty
 * would ship, and then need unpicking.</p>
 *
 * <p>So the guard is structural rather than a rule someone has to remember:</p>
 * <ul>
 *   <li><b>The only write is {@link #put}, and it takes the whole lattice.</b> There is no API on
 *       this class that can address a single control point at a single time. Fifty separate track
 *       writes are not discouraged here, they are <i>unrepresentable</i>.</li>
 *   <li><b>One pose is one diamond.</b> {@link #times} is what the drawer draws, and it returns one
 *       entry per pose — the single "Shape" diamond the design promised, at every level.</li>
 *   <li><b>Every pose in a track shares the spec's level.</b> {@link #level()} is fixed at
 *       construction and {@link #put} refuses an array of the wrong arity, so the whole family of
 *       "keyframe A is 9 points and keyframe B is 25" bugs cannot exist. Changing level goes
 *       through {@link #remapTo}, which rewrites every pose at once through the shape-preserving
 *       subdivision.</li>
 * </ul>
 *
 * <h3>Interpolation semantics are copied, not invented</h3>
 * <p>{@link #valueAt} is {@code KeyframeTrack.valueAt} with a vector where the float was: empty
 * gives the fallback, one pose is a constant, values are held (not extrapolated) outside the
 * range, and the span's progress runs through the LEFT pose's easing — including
 * {@code Easing.HOLD} returning 0 so the value steps. Deliberately identical, because a bend that
 * eased differently from the position it is attached to would look broken and nobody would be able
 * to say why.</p>
 *
 * <p>No Android imports, no FadCam imports beyond the easing enum's <i>name</i> — see
 * {@link Curve}.</p>
 */
public final class MeshPoseTrack {

    /**
     * The easing seam. {@code MeshWarp} and this file must stay loadable on the desktop JVM harness
     * and liftable into another app, so this class cannot import
     * {@code com.fadcam.ui.faditor.keyframe.Easing} (which imports {@code androidx.annotation}).
     * A pose therefore stores the easing's NAME and asks a {@link Curve} to apply it.
     *
     * <p>The one-line adapter the app installs is
     * {@code track.setCurve((name, t) -> Easing.fromName(name).apply(t))} — so there is exactly one
     * easing implementation in the app, not a second copy here that could drift from the first.
     * With no curve installed the track interpolates linearly, which is what the harness uses.</p>
     */
    public interface Curve {
        float apply(String easingName, float t);
    }

    /** Default easing name, matching {@code Easing.fromName}'s own fallback. */
    public static final String DEFAULT_EASING = "EASE_IN_OUT";

    /** One instant of the whole bend. */
    public static final class Pose {
        public long timeMs;
        /** The complete nudge lattice at this instant. Length is the track's arity. */
        public final float[] off;
        public String easing;
        /** True when an animation preset owns this pose, mirroring {@code Keyframe.presetOwned}. */
        public boolean presetOwned;

        Pose(long timeMs, float[] off, String easing) {
            this.timeMs = timeMs;
            this.off = off;
            this.easing = easing == null ? DEFAULT_EASING : easing;
        }

        public Pose copy() {
            Pose p = new Pose(timeMs, off.clone(), easing);
            p.presetOwned = presetOwned;
            return p;
        }
    }

    private int level;
    private final List<Pose> poses = new ArrayList<>();
    private Curve curve;

    public MeshPoseTrack(int level) {
        this.level = MeshWarp.clampLevel(level);
    }

    public int level() { return level; }

    public int arity() { return MeshWarp.arityFor(level); }

    public int size() { return poses.size(); }

    public boolean isEmpty() { return poses.isEmpty(); }

    /** Two or more poses is what "this bend actually moves" means. */
    public boolean isAnimated() { return poses.size() >= 2; }

    public List<Pose> poses() { return Collections.unmodifiableList(poses); }

    public void setCurve(Curve c) { this.curve = c; }

    /** The instants the drawer draws a diamond at — ONE per pose, never one per control point. */
    public long[] times() {
        long[] t = new long[poses.size()];
        for (int i = 0; i < t.length; i++) t[i] = poses.get(i).timeMs;
        return t;
    }

    /**
     * Write the whole lattice at {@code timeMs}, replacing any pose already there.
     *
     * <p>This is the entire write surface, and it is the reason one gesture is one undo press: a
     * drag calls this exactly once, on commit, with the lattice it ended on. There is no partial
     * write to batch and no per-point write to collapse.</p>
     *
     * @return false when {@code off} is the wrong arity for this track's level — refused rather
     *         than stored, so a mixed-arity track cannot come into existence
     */
    public boolean put(long timeMs, float[] off, String easingName) {
        if (off == null || off.length != arity()) return false;
        for (int i = 0; i < poses.size(); i++) {
            if (poses.get(i).timeMs == timeMs) {
                Pose p = poses.get(i);
                System.arraycopy(off, 0, p.off, 0, off.length);
                p.easing = easingName == null ? DEFAULT_EASING : easingName;
                return true;
            }
        }
        poses.add(new Pose(timeMs, off.clone(), easingName));
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
     * The lattice at {@code timeMs}, written into {@code out}.
     *
     * <p>{@code KeyframeTrack.valueAt}'s semantics, vectorised: held outside the range, constant
     * for a single pose, component-wise lerp of the two bracketing poses under the LEFT pose's
     * easing.</p>
     *
     * <p>Allocation-free — {@code out} is the caller's, and a renderer holds one for the life of
     * its GL thread. Fifty component lerps per frame is noise next to a single texture fetch.</p>
     *
     * @return false when the track is empty or {@code out} is the wrong length; {@code out} is then
     *         untouched and the caller uses its static lattice
     */
    public boolean valueAt(long timeMs, float[] out) {
        int n = arity();
        if (poses.isEmpty() || out == null || out.length < n) return false;
        if (poses.size() == 1) {
            System.arraycopy(poses.get(0).off, 0, out, 0, n);
            return true;
        }
        Pose first = poses.get(0);
        if (timeMs <= first.timeMs) {
            System.arraycopy(first.off, 0, out, 0, n);
            return true;
        }
        Pose last = poses.get(poses.size() - 1);
        if (timeMs >= last.timeMs) {
            System.arraycopy(last.off, 0, out, 0, n);
            return true;
        }
        for (int i = 0; i < poses.size() - 1; i++) {
            Pose a = poses.get(i), b = poses.get(i + 1);
            if (timeMs >= a.timeMs && timeMs <= b.timeMs) {
                long span = b.timeMs - a.timeMs;
                if (span <= 0) {
                    System.arraycopy(b.off, 0, out, 0, n);
                    return true;
                }
                float t = (timeMs - a.timeMs) / (float) span;
                float eased = curve == null ? t : curve.apply(a.easing, t);
                for (int k = 0; k < n; k++) {
                    out[k] = a.off[k] + (b.off[k] - a.off[k]) * eased;
                }
                return true;
            }
        }
        System.arraycopy(last.off, 0, out, 0, n);
        return true;
    }

    /**
     * Move every pose along this track's own time base.
     *
     * <p>{@code KeyframeSet.shiftAll}'s contract, including the part that matters: <b>negative
     * times are kept, not clamped.</b> Trimming an object's start later pushes early poses before
     * its own zero; the lookup floors at zero so such a pose is never reached, but it still shapes
     * the interpolation into the first visible one — and trimming back out restores it exactly.
     * Clamping to zero would pile several poses onto one instant and destroy the animation
     * irreversibly, which a trim handle may not do.</p>
     */
    public void shiftAll(long deltaMs) {
        if (deltaMs == 0) return;
        for (Pose p : poses) p.timeMs += deltaMs;
    }

    /**
     * Rewrite every pose at a new level through {@link MeshWarp#subdivide}, so the animation looks
     * the same before and after "+ Finer".
     *
     * <p>All poses move together, which is the invariant that makes mixed arity impossible.</p>
     */
    public void remapTo(int newLevel, MeshWarp.Scratch s) {
        int nl = MeshWarp.clampLevel(newLevel);
        if (nl == level) return;
        int oldSide = MeshWarp.sideFor(level);
        int newSide = MeshWarp.sideFor(nl);
        List<Pose> rebuilt = new ArrayList<>(poses.size());
        for (Pose p : poses) {
            float[] to = new float[newSide * newSide * 2];
            MeshWarp.subdivide(p.off, oldSide, newSide, to, s);
            Pose q = new Pose(p.timeMs, to, p.easing);
            q.presetOwned = p.presetOwned;
            rebuilt.add(q);
        }
        poses.clear();
        poses.addAll(rebuilt);
        level = nl;
    }

    public MeshPoseTrack copy() {
        MeshPoseTrack t = new MeshPoseTrack(level);
        for (Pose p : poses) t.poses.add(p.copy());
        t.curve = curve;
        return t;
    }

    private void sort() {
        Collections.sort(poses, (a, b) -> Long.compare(a.timeMs, b.timeMs));
    }
}
