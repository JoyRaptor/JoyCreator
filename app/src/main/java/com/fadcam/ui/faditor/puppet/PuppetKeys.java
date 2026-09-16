package com.fadcam.ui.faditor.puppet;


import com.fadcam.ui.faditor.transform.mesh.MeshCurves;
import com.fadcam.ui.faditor.transform.mesh.MeshEasingFit;
import com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;

/**
 * THE ONE AUTHORITY on a puppet pin's keyframes.
 *
 * <p>Every surface that asks "does this pin have a key here", "drop one", "jump to the next" goes
 * through this file — the drawer's {@code ‹ ♦ ›}, the tape, the live recorder and the overlay. The
 * alternative is four places that each compute a pin's component indices, and the first one to
 * get {@code 2i, 2i+1} backwards moves the wrong limb with nothing to point at.
 *
 * <h3>The pose lives in two places and that is deliberate</h3>
 * <ul>
 *   <li>{@link MeshWarpSpec#handles()} — the STATIC pose. A bend with no animation: one shape,
 *       every frame. Every rig starts here.</li>
 *   <li>{@link MeshWarpSpec#track()} — the ANIMATION. Once a rig has one key, this is the truth
 *       and the static pose is only the fallback the renderer uses before the first key.</li>
 * </ul>
 *
 * <p><b>So dragging a pin means different things, and the rule is the one every editor uses:</b>
 * with no track a drag edits the static pose, and once a track exists a drag also keys at the
 * playhead. Without that second half a drag would silently vanish the moment you scrubbed —
 * the user moved an arm, the track said otherwise, and the track wins.
 *
 * <h3>A chain keys together, always</h3>
 * <p>SPEC_20260915_PUPPET_UI §5.2. Dragging a wrist solves the elbow and shoulder too, so all
 * three must land on the SAME instant: thinned onto different key times, the chain no longer
 * solves to the same shape between them and the limb wobbles. {@code putComponents} writes one
 * pose per instant, so passing the whole chain's components in one call makes that structural
 * rather than a rule somebody has to remember.
 */
public final class PuppetKeys {

    private PuppetKeys() {}

    /** Two components per pin, x then y — the layout {@code PuppetTopology} publishes. */
    public static final int COMPONENTS_PER_PIN = 2;

    /**
     * How close two key times count as the same one, in ms.
     *
     * <p>A frame at 30fps is 33ms, so half a frame is the most generous value that can never
     * merge two keys a user deliberately placed on neighbouring frames. It is also what stops
     * "is the playhead on a key" flickering while a scrub drifts by a millisecond.
     */
    public static final long TIME_TOLERANCE_MS = 16L;

    // ── what a pin owns ──────────────────────────────────────────────────

    /** The components of ONE pin. */
    public static int[] componentsOf(int pin) {
        return new int[]{pin * COMPONENTS_PER_PIN, pin * COMPONENTS_PER_PIN + 1};
    }

    /**
     * The components a DRAG of this pin writes — the whole chain when it has bones.
     *
     * <p>Falls back to the pin alone when there is no rig or no chain, which is the ordinary case
     * for a loose pin and not a failure.
     */
    public static int[] componentsForDrag(PuppetRig rig, int pin) {
        if (rig == null || pin < 0 || pin >= rig.pinCount()) return componentsOf(pin);
        return rig.chainComponents(pin);
    }

    // ── reading ──────────────────────────────────────────────────────────

    /** The instants this pin has a key at, ascending. Empty when it has none. */
    public static long[] keyTimes(MeshWarpSpec spec, int pin) {
        if (spec == null || spec.track() == null || spec.track().isEmpty()) return new long[0];
        try {
            long[] t = spec.track().componentTimes(componentsOf(pin), 1e-5f);
            return t == null ? new long[0] : t;
        } catch (Exception e) {
            return new long[0];
        }
    }

    public static int keyCount(MeshWarpSpec spec, int pin) {
        return keyTimes(spec, pin).length;
    }

    /**
     * What a HUMAN should be told this pin's key count is.
     *
     * <p>{@code componentTimes} always includes the first and last pose — "where a value starts
     * and stops being held" — so a pin nobody has ever animated reports TWO. That is honest about
     * storage and a lie to the user: the drawer would say "2 keys" about a pin they never
     * touched, and the {@code ‹ ♦ ›} would offer to jump between them.
     *
     * <p>So a pin whose value never actually changes reports zero. Caught by
     * {@code PuppetKeysTest}, which expected zero, got two, and was wrong about the engine but
     * right about the person reading the screen.
     */
    public static int displayKeyCount(MeshWarpSpec spec, int pin) {
        long[] t = keyTimes(spec, pin);
        if (t.length == 0) return 0;
        int arity = spec == null ? 0 : spec.arity();
        if (arity <= 0) return 0;
        int[] comps = componentsOf(pin);
        float[] first = new float[arity];
        if (!readPose(spec, t[0], first)) return t.length;
        for (int i = 1; i < t.length; i++) {
            float[] here = new float[arity];
            if (!readPose(spec, t[i], here)) continue;
            for (int c : comps) {
                if (c >= 0 && c < arity && Math.abs(here[c] - first[c]) > 1e-5f) return t.length;
            }
        }
        return 0;       // held at one value for its whole life: not an animation
    }

    /** 1-based position of the key at {@code timeMs}, or 0 when the playhead is between keys. */
    public static int keyIndexAt(MeshWarpSpec spec, int pin, long timeMs) {
        long[] t = keyTimes(spec, pin);
        for (int i = 0; i < t.length; i++) {
            if (Math.abs(t[i] - timeMs) <= TIME_TOLERANCE_MS) return i + 1;
        }
        return 0;
    }

    public static boolean isOnKey(MeshWarpSpec spec, int pin, long timeMs) {
        return keyIndexAt(spec, pin, timeMs) > 0;
    }

    /** The key strictly before {@code timeMs}, or {@code Long.MIN_VALUE} when there is none. */
    public static long prevKey(MeshWarpSpec spec, int pin, long timeMs) {
        long[] t = keyTimes(spec, pin);
        long best = Long.MIN_VALUE;
        for (long k : t) {
            if (k < timeMs - TIME_TOLERANCE_MS && k > best) best = k;
        }
        return best;
    }

    /** The key strictly after {@code timeMs}, or {@code Long.MIN_VALUE} when there is none. */
    public static long nextKey(MeshWarpSpec spec, int pin, long timeMs) {
        long[] t = keyTimes(spec, pin);
        long best = Long.MIN_VALUE;
        for (long k : t) {
            if (k > timeMs + TIME_TOLERANCE_MS && (best == Long.MIN_VALUE || k < best)) best = k;
        }
        return best;
    }

    /** True when this spec is animated at all, as opposed to carrying one static bend. */
    public static boolean isAnimated(MeshWarpSpec spec) {
        return spec != null && spec.track() != null && !spec.track().isEmpty();
    }

    // ── writing ──────────────────────────────────────────────────────────

    /**
     * The pose to edit right now: the animated value at {@code timeMs}, or the static one.
     *
     * <p>This is what makes a punch-in start where the old move already was — SPEC §5.5's
     * "anchor in". The finger grabs the pin at the value the track is producing, so the first
     * sample of a new take equals the last value of the old one and there is nothing to jump
     * from.
     *
     * @return false when there is no pose to read
     */
    public static boolean readPose(MeshWarpSpec spec, long timeMs, float[] out) {
        if (spec == null) return false;
        try {
            if (isAnimated(spec)) return spec.handlesAt(timeMs, out);
            float[] h = spec.handles();
            if (h == null || h.length != out.length) return false;
            System.arraycopy(h, 0, out, 0, out.length);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Write a pin's (or its chain's) offsets at {@code timeMs}.
     *
     * <p>Keys when the spec is already animated or {@code forceKey} says so; otherwise edits the
     * static pose. That single branch is the whole "a drag means different things" rule, in one
     * place, so no caller has to decide it.
     *
     * @param values two floats per component index, in the same order
     * @return true when something was written
     */
    public static boolean writeOffsets(MeshWarpSpec spec, int[] components,
                                       float[] values, long timeMs, boolean forceKey) {
        if (spec == null || components.length != values.length) return false;
        try {
            if (forceKey || isAnimated(spec)) {
                MeshPoseTrack track = spec.ensureTrack();
                return track.putComponents(timeMs, components, values, null);
            }
            float[] h = spec.handles();
            if (h == null) return false;
            for (int i = 0; i < components.length; i++) {
                int c = components[i];
                if (c >= 0 && c < h.length) h[c] = values[i];
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Drop a key for this pin's chain at {@code timeMs}, holding whatever the pose is there.
     *
     * <p>Keying the CURRENT value rather than zero is the point: a key that changed the picture
     * the moment you made it would make the control feel like a mistake.
     *
     * @return true when a key landed
     */
    public static boolean dropKey(MeshWarpSpec spec, PuppetRig rig,
                                  int pin, long timeMs) {
        if (spec == null) return false;
        int arity = spec.arity();
        if (arity <= 0) return false;
        float[] pose = new float[arity];
        if (!readPose(spec, timeMs, pose)) return false;
        int[] comps = componentsForDrag(rig, pin);
        float[] vals = new float[comps.length];
        for (int i = 0; i < comps.length; i++) {
            vals[i] = (comps[i] >= 0 && comps[i] < arity) ? pose[comps[i]] : 0f;
        }
        return writeOffsets(spec, comps, vals, timeMs, true);
    }

    /**
     * Remove THIS PIN's key at {@code timeMs}, leaving every other pin's alone.
     *
     * <p><b>This is not {@code track.removeAt}, and the difference is a bug I nearly shipped.</b>
     * A pose is the unit of storage: one instant holds every pin's value. So dropping the pose
     * would delete the whole character's key — press "delete key" on a hand and the head, the hip
     * and the tail lose theirs too, silently, with one undo press that looks like it did one
     * thing.
     *
     * <p>What "this pin has no key here" actually means is that its components do not CHANGE at
     * this instant — which is what {@code componentTimes} reports on. So the pin's values are set
     * to what they would have been had nobody keyed them: the interpolation of its own
     * neighbouring keys. The pose survives for everyone else; this pin stops having a key.
     *
     * <p>If the pose then carries nothing for anybody it is removed, so deleting the last key of
     * the last pin does not leave an invisible pose behind forever.
     *
     * @return true when a key was there to remove
     */
    public static boolean deleteKey(MeshWarpSpec spec, int pin, long timeMs) {
        if (spec == null || spec.track() == null) return false;
        MeshPoseTrack track = spec.track();
        long at = Long.MIN_VALUE;
        for (long k : keyTimes(spec, pin)) {
            if (Math.abs(k - timeMs) <= TIME_TOLERANCE_MS) { at = k; break; }
        }
        if (at == Long.MIN_VALUE) return false;

        int arity = spec.arity();
        if (arity <= 0) return false;
        int[] comps = componentsOf(pin);

        // What this pin would read had it never been keyed here: straight-line between the keys
        // either side. With only one side, hold that value; with neither, this was the pin's only
        // key and the honest answer is its rest position, which is zero offset.
        long before = prevKey(spec, pin, at), after = nextKey(spec, pin, at);
        float[] vals = new float[comps.length];
        float[] a = new float[arity], b = new float[arity];
        boolean hasA = before != Long.MIN_VALUE && readPose(spec, before, a);
        boolean hasB = after != Long.MIN_VALUE && readPose(spec, after, b);
        for (int i = 0; i < comps.length; i++) {
            int c = comps[i];
            if (hasA && hasB) {
                float span = (float) (after - before);
                float u = span <= 0f ? 0f : (at - before) / span;
                vals[i] = a[c] + (b[c] - a[c]) * u;
            } else if (hasA) {
                vals[i] = a[c];
            } else if (hasB) {
                vals[i] = b[c];
            } else {
                vals[i] = 0f;
            }
        }
        if (!track.putComponents(at, comps, vals, null)) return false;

        // CLEAN UP A POSE THAT NOW SAYS NOTHING. If no component at this instant differs from
        // what the neighbouring poses would interpolate to, the pose is dead weight — and a
        // track full of them would make every later simplify and every tape row slower for
        // nothing.
        if (poseIsRedundant(spec, at)) {
            track.removeAt(at);
        } else if (trackIsOneIdentityPose(track, spec.arity())) {
            // THE LAST KEY. A single pose that is all zeros is not an animation, it is the rest
            // shape written down — and leaving it would mean a rig still reported as animated
            // after its only key was deleted, so every later drag would key instead of editing
            // the static pose. The user would never find their way back.
            track.removeAt(at);
        }
        return true;
    }

    /** True when the whole track is one pose and that pose moves nothing. */
    private static boolean trackIsOneIdentityPose(MeshPoseTrack track, int arity) {
        long[] all = track == null ? null : track.times();
        if (all == null || all.length != 1 || arity <= 0) return false;
        float[] only = new float[arity];
        if (!track.valueAt(all[0], only)) return false;
        for (float v : only) if (Math.abs(v) > 1e-6f) return false;
        return true;
    }

    /** True when the pose at {@code timeMs} is exactly what its neighbours already imply. */
    private static boolean poseIsRedundant(MeshWarpSpec spec, long timeMs) {
        MeshPoseTrack track = spec.track();
        if (track == null) return false;
        long[] all = track.times();
        if (all == null || all.length <= 1) return false;
        int idx = -1;
        for (int i = 0; i < all.length; i++) if (all[i] == timeMs) { idx = i; break; }
        if (idx <= 0 || idx >= all.length - 1) return false;   // the ends always carry meaning

        int arity = spec.arity();
        float[] here = new float[arity], a = new float[arity], b = new float[arity];
        if (!readPose(spec, timeMs, here)) return false;
        if (!readPose(spec, all[idx - 1], a) || !readPose(spec, all[idx + 1], b)) return false;
        float span = (float) (all[idx + 1] - all[idx - 1]);
        if (span <= 0f) return false;
        float u = (timeMs - all[idx - 1]) / span;
        for (int c = 0; c < arity; c++) {
            float lerp = a[c] + (b[c] - a[c]) * u;
            if (Math.abs(here[c] - lerp) > 1e-5f) return false;
        }
        return true;
    }

    /**
     * Thin a recorded range, and say how many poses went.
     *
     * <p>A live take drops a key per frame, so this is not polish — without it the surviving keys
     * cannot be drawn inside a bar or hit with a finger. {@code detail} is the rig's 0..1 slider;
     * higher means fewer keys, which is the direction that reads as "less detail".
     */
    public static int simplify(MeshWarpSpec spec, long fromMs, long toMs, float detail) {
        return simplify(spec, fromMs, toMs, detail, null);
    }

    /**
     * As above, and then give each surviving key the CURVE the performance actually had.
     *
     * <p>Thinning throws away the shape between two survivors and leaves the default easing in
     * its place, so a hand that whipped out and settled plays back as a hand that glides — the
     * user’s performance quietly replaced by an average of it. JoyRaptor asked for the fix by
     * name: <i>"we already have a lot of different types of easing that it could look at and
     * identify what it’s closest to."</i>
     *
     * <p>The copy is taken BEFORE thinning because the evidence is the samples that are about to
     * be discarded. It is one array per pose for the length of one take, freed as soon as the fit
     * is done, and it only happens on release — never per move.
     *
     * @param components the chain being recorded, so a limb is judged as one thing; null = all
     */
    public static int simplify(MeshWarpSpec spec, long fromMs, long toMs, float detail,
                               int[] components) {
        if (spec == null || spec.track() == null) return 0;
        if (detail <= 0.02f) return 0;          // keep every sample
        // Unit space, so this is a FRACTION OF THE PICTURE, not pixels. 0.02 sounded modest and
        // is not: on a 1000px-wide character it allows 20px of error, which is a knuckle. 0.008
        // is about 8px there — below what reads as a change of pose and above a held hand's
        // tremor. The floor keeps a "no thinning" setting from being exactly zero, which would
        // make simplifyRange's own epsilon comparisons meaningless.
        float tolerance = 0.0006f + detail * 0.008f;
        try {
            MeshPoseTrack dense = spec.track().copy();
            int removed = spec.track().simplifyRange(fromMs, toMs, tolerance);
            if (removed > 0) {
                MeshEasingFit.fit(spec.track(), dense, components, fromMs, toMs,
                        MeshCurves.APP_EASING);
            }
            return removed;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Ease the last {@code blendMs} of a take back onto whatever it interrupted.
     *
     * <p>SPEC §5.5's "blend out", and the asymmetry is the whole point: the IN point never jumps
     * because the finger grabbed the pin where the animation already had it, while the OUT point
     * lands wherever the finger stopped and the old motion resumes from somewhere else. Only one
     * end needs help.
     *
     * <p>Applies to the WHOLE chain, not the dragged pin: blending one pin of a limb while its
     * neighbours snap would tear it.
     *
     * @param resumeAt the pose the old animation produces just after the take — read BEFORE the
     *                 take overwrote anything, or this blends towards what it just wrote
     * @return true when a blend was written
     */
    /**
     * REPLACE IN RANGE — clear what the old performance left between the new take's samples.
     *
     * <p>SPEC section 0 settled overdub as "replace in range, anchor in, blend out", and the first
     * third was the one that only half-held. A take writes samples on the frame grid at whatever
     * instants the playhead passed through; any key the OLD animation had at a time BETWEEN two of
     * them survived untouched. The pin then flicks between the new performance and the old one,
     * every few frames, for the length of the overdub — and it looks like the recording glitched
     * rather than like a key that should not be there.
     *
     * <p>A pose inside the range is part of the take when this pin's values CHANGED; one whose
     * values still match the snapshot was never written and is stale. Those get the value the take
     * itself would have at that instant — a straight line between the samples either side — so the
     * curve reads as one continuous move.
     *
     * <p>Poses are not deleted: a pose belongs to every pin, and dropping one would take the other
     * pins' keys with it. Made redundant instead, which is exactly what the simplifier then
     * removes.
     *
     * @param before the spec as it was before the take started
     * @return how many stale poses were rewritten
     */
    public static int replaceInRange(MeshWarpSpec spec, MeshWarpSpec before, int[] components,
                                     long fromMs, long toMs) {
        if (spec == null || before == null || components == null || components.length == 0) return 0;
        if (spec.track() == null || toMs <= fromMs) return 0;
        if (before.arity() != spec.arity()) return 0;
        try {
            java.util.List<MeshPoseTrack.Pose> poses = spec.track().poses();
            int n = poses.size();
            long[] times = new long[n];
            boolean[] stale = new boolean[n];
            float[] old = new float[before.arity()];
            int found = 0;
            for (int i = 0; i < n; i++) {
                MeshPoseTrack.Pose p = poses.get(i);
                times[i] = p.timeMs;
                if (p.timeMs <= fromMs || p.timeMs >= toMs) continue;
                if (!readPose(before, p.timeMs, old)) continue;
                boolean same = true;
                for (int c : components) {
                    if (c < 0 || c >= old.length) continue;
                    if (Math.abs(p.values[c] - old[c]) > 1e-6f) { same = false; break; }
                }
                if (same) { stale[i] = true; found++; }
            }
            if (found == 0) return 0;

            // Collected first, applied second: writing while walking would make a stale pose look
            // like a take sample to the pose after it.
            float[][] fixed = new float[n][];
            for (int i = 0; i < n; i++) {
                if (!stale[i]) continue;
                int lo = i - 1, hi = i + 1;
                while (lo >= 0 && stale[lo]) lo--;
                while (hi < n && stale[hi]) hi++;
                if (lo < 0 || hi >= n) continue;              // no take sample on one side
                MeshPoseTrack.Pose a = poses.get(lo), b = poses.get(hi);
                long span = b.timeMs - a.timeMs;
                float t = span <= 0 ? 0f : (times[i] - a.timeMs) / (float) span;
                float[] v = new float[components.length];
                for (int k = 0; k < components.length; k++) {
                    int c = components[k];
                    if (c < 0 || c >= a.values.length) continue;
                    v[k] = a.values[c] + (b.values[c] - a.values[c]) * t;
                }
                fixed[i] = v;
            }
            int done = 0;
            for (int i = 0; i < n; i++) {
                if (fixed[i] == null) continue;
                if (spec.track().putComponents(times[i], components, fixed[i], null)) done++;
            }
            return done;
        } catch (RuntimeException e) {
            return 0;                       // a take that cannot be tidied is still a take
        }
    }

    public static boolean blendOut(MeshWarpSpec spec, int[] components,
                                   long takeEndMs, int blendMs, float[] resumeAt) {
        if (spec == null || resumeAt == null || blendMs <= 0) return false;
        if (!isAnimated(spec)) return false;
        int arity = spec.arity();
        if (arity <= 0 || resumeAt.length != arity) return false;

        float[] atEnd = new float[arity];
        if (!readPose(spec, takeEndMs, atEnd)) return false;

        // Three steps is enough for an ease over a fifth of a second and cheap enough to be
        // invisible; more would just be keys nobody asked for.
        final int STEPS = 3;
        boolean wrote = false;
        for (int s = 1; s <= STEPS; s++) {
            float u = s / (float) STEPS;
            float e = u * u * (3f - 2f * u);           // smoothstep, the shape of a settle
            long t = takeEndMs + Math.round(blendMs * u);
            float[] vals = new float[components.length];
            for (int i = 0; i < components.length; i++) {
                int c = components[i];
                if (c < 0 || c >= arity) continue;
                vals[i] = atEnd[c] + (resumeAt[c] - atEnd[c]) * e;
            }
            wrote |= writeOffsets(spec, components, vals, t, true);
        }
        return wrote;
    }
}
