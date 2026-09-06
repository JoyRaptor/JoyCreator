package com.fadcam.ui.faditor.transform.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * THE MODEL for one object's bend: its level, its nudge lattice, its optional pose track, and the
 * JSON that persists all three.
 *
 * <p>Self-serialising, like {@code CompositingSpec} — the in-tree precedent for a model class that
 * owns its own wire format and reads it tolerantly. {@code ProjectStorage} calls
 * {@link #toJson} / {@link #fromJson} and does not need to know a single field name.</p>
 *
 * <h3>Absent means zero cost, and that is a guarantee, not a hope</h3>
 * <ul>
 *   <li><b>Read:</b> no {@code warp} field → a null spec → no code path entered. Every project
 *       written before this feature existed takes that branch.</li>
 *   <li><b>Write:</b> {@link #toJson} returns null when the lattice is identity and there is no
 *       track, so an object whose Shape tool was opened and closed without a drag writes a
 *       <b>byte-identical</b> file. Same omit-empty discipline {@code KeyframeCodec.toJson} uses.</li>
 *   <li><b>Render:</b> {@link #hasWarp} is the gate. An identity lattice is treated as "no warp"
 *       BEFORE any GL object exists — no FBO, no program, no extra pass.</li>
 * </ul>
 *
 * <h3>Wire format</h3>
 * <pre>
 * "warp": {
 *   "lvl": 2,
 *   "off": [0,0, 0,-0.06, 0,0,  0,0, 0,0, 0,0,  0,0, 0,0.06, 0,0],
 *   "keys": [ { "t": 0, "e": "EASE_OUT", "off": [ ...18 floats... ] },
 *             { "t": 1200, "e": "LINEAR", "off": [ ...18 floats... ] } ]
 * }
 * </pre>
 * <p>{@code off} is <b>nudges</b>, row-major, {@code side*side*2} floats — see {@link MeshWarp} for
 * why a nudge and not an absolute point. The design spec sketched the field as {@code "pts"} with
 * absolute positions; {@link #fromJson} accepts that spelling too and converts, so a file written
 * against either reading of the spec loads. Only {@code off} is ever written.</p>
 *
 * <h3>Tolerance rules — these are what keep the owner's existing projects working</h3>
 * <ol>
 *   <li>{@code lvl} outside 1..3 → clamped, {@code side} re-derived.</li>
 *   <li>{@code off} of the wrong length → the warp is DROPPED and the object kept. Logged by the
 *       caller if it wants; never thrown. Same "one bad track does not cost the others" reasoning
 *       as {@code KeyframeCodec.fromJson}'s per-track catch.</li>
 *   <li>A pose whose array disagrees with {@code lvl} → that pose is dropped, the rest kept.</li>
 *   <li>Unknown easing → left as the stored string; {@code Easing.fromName} already falls back when
 *       the app installs its {@link MeshPoseTrack.Curve}. Not re-implemented here.</li>
 *   <li>Anything that throws → null spec, object intact.</li>
 * </ol>
 *
 * <p><b>Size.</b> Fifty floats is about 450 bytes of JSON per pose; a ten-pose animated 5x5 bend is
 * roughly 4.5 KB per object. Twenty such objects is 90 KB. Project files are already well past
 * that, so this stays plain diffable JSON rather than a binary blob nobody can repair by hand.</p>
 */
public final class MeshWarpSpec {

    public static final String KEY_LEVEL = "lvl";
    public static final String KEY_OFFSETS = "off";
    /** Accepted on read only — the design spec's spelling, in ABSOLUTE unit-square positions. */
    public static final String KEY_ABS_POINTS = "pts";
    public static final String KEY_POSES = "keys";
    public static final String KEY_TIME = "t";
    public static final String KEY_EASING = "e";
    public static final String KEY_PRESET = "p";

    private int level;
    private float[] off;
    private MeshPoseTrack track;

    public MeshWarpSpec() {
        this(MeshWarp.L1);
    }

    public MeshWarpSpec(int level) {
        this.level = MeshWarp.clampLevel(level);
        this.off = MeshWarp.identity(this.level);
    }

    // ── Shape ────────────────────────────────────────────────────────────

    public int level() { return level; }

    public int side() { return MeshWarp.sideFor(level); }

    public int arity() { return off.length; }

    /** The static lattice, live. Mutate through {@link #setOffset} so the clamp is not bypassed. */
    public float[] offsets() { return off; }

    public MeshPoseTrack track() { return track; }

    public boolean isAnimated() { return track != null && track.isAnimated(); }

    /** No bend at all — neither a static one nor a keyed one. */
    public boolean isIdentity() {
        if (track != null && !track.isEmpty()) {
            for (MeshPoseTrack.Pose p : track.poses()) {
                if (!MeshWarp.isIdentity(p.off)) return false;
            }
        }
        return MeshWarp.isIdentity(off);
    }

    /**
     * THE GATE. Every renderer asks this before it creates anything; false takes the exact code
     * path an unbent picture has always taken.
     */
    public boolean hasWarp() { return !isIdentity(); }

    public void setOffset(int row, int col, float du, float dv) {
        int k = MeshWarp.index(row, col, side()) * 2;
        off[k] = MeshWarp.clampNudge(du);
        off[k + 1] = MeshWarp.clampNudge(dv);
    }

    public void setOffset(int pointIndex, float du, float dv) {
        int k = pointIndex * 2;
        off[k] = MeshWarp.clampNudge(du);
        off[k + 1] = MeshWarp.clampNudge(dv);
    }

    /** Replace the whole static lattice. Ignored (returns false) at the wrong arity. */
    public boolean setOffsets(float[] src) {
        if (src == null || src.length != off.length) return false;
        for (int i = 0; i < src.length; i++) off[i] = MeshWarp.clampNudge(src[i]);
        return true;
    }

    public void reset() {
        java.util.Arrays.fill(off, 0f);
        track = null;
    }

    /**
     * Move one control point and KEEP the move only if the mesh survives it.
     *
     * <p>The fold guard, at the one place a fold can be introduced. A cell turned inside out smears
     * the picture across the screen, so the drag is refused and the gesture simply stops moving —
     * always recoverable, unlike rendering the fold. Same trade {@code TransformQuad} makes when a
     * quad goes non-convex.</p>
     *
     * @return false when the move was rejected; the lattice is then exactly as it was
     */
    public boolean tryOffset(int pointIndex, float du, float dv, MeshWarp.Scratch s) {
        int k = pointIndex * 2;
        if (k < 0 || k + 1 >= off.length) return false;
        float ou = off[k], ov = off[k + 1];
        off[k] = MeshWarp.clampNudge(du);
        off[k + 1] = MeshWarp.clampNudge(dv);
        if (MeshWarp.isValid(off, side(), s)) return true;
        off[k] = ou;
        off[k + 1] = ov;
        return false;
    }

    /** Is the CURRENT lattice renderable? Cheap enough to call on commit. */
    public boolean isValid(MeshWarp.Scratch s) {
        return MeshWarp.isValid(off, side(), s);
    }

    // ── Level changes ────────────────────────────────────────────────────

    /**
     * "+ Finer" (and its lossy reverse). Rewrites the static lattice AND every pose through
     * {@link MeshWarp#subdivide}, so the picture does not move.
     *
     * <p>The static lattice and the track always move together — that is the invariant that makes a
     * mixed-arity track unrepresentable rather than merely discouraged.</p>
     */
    public void setLevel(int newLevel, MeshWarp.Scratch s) {
        int nl = MeshWarp.clampLevel(newLevel);
        if (nl == level) return;
        int oldSide = side();
        int newSide = MeshWarp.sideFor(nl);
        float[] to = new float[newSide * newSide * 2];
        MeshWarp.subdivide(off, oldSide, newSide, to, s);
        if (track != null) track.remapTo(nl, s);
        level = nl;
        off = to;
    }

    // ── Animation ────────────────────────────────────────────────────────

    /** Create the track on demand, at this spec's level. */
    public MeshPoseTrack ensureTrack() {
        if (track == null) track = new MeshPoseTrack(level);
        return track;
    }

    /**
     * ONE COMMIT PER GESTURE. Writes the whole current lattice as a single pose — one diamond, one
     * undo step. There is deliberately no way to key a single dot.
     */
    public boolean keyCurrentPose(long timeMs, String easingName) {
        return ensureTrack().put(timeMs, off, easingName);
    }

    /**
     * The lattice to render at {@code timeMs}: the track's value when keyed, otherwise the static
     * lattice. {@code out} must be {@link #arity} long and is the caller's — no allocation.
     *
     * @return the array actually holding the answer ({@code out}, or the static lattice when there
     *         is no track), so a renderer can pass the result straight to
     *         {@link MeshWarp#tessellate} without a copy
     */
    public float[] offsetsAt(long timeMs, float[] out) {
        if (track != null && out != null && track.valueAt(timeMs, out)) return out;
        return off;
    }

    // ── Copy / undo ──────────────────────────────────────────────────────

    /** A deep copy — what a gesture snapshots on grab and what undo restores. */
    public MeshWarpSpec copy() {
        MeshWarpSpec c = new MeshWarpSpec(level);
        System.arraycopy(off, 0, c.off, 0, off.length);
        c.track = track == null ? null : track.copy();
        return c;
    }

    // ── JSON ─────────────────────────────────────────────────────────────

    /**
     * {@code null} when there is nothing to persist — the caller then omits the field entirely and
     * the file is byte-identical to one written before this feature existed.
     */
    public JsonObject toJson() {
        boolean hasTrack = track != null && !track.isEmpty();
        if (!hasTrack && MeshWarp.isIdentity(off)) return null;
        JsonObject o = new JsonObject();
        o.addProperty(KEY_LEVEL, level);
        if (!MeshWarp.isIdentity(off)) o.add(KEY_OFFSETS, floats(off));
        if (hasTrack) {
            JsonArray keys = new JsonArray();
            for (MeshPoseTrack.Pose p : track.poses()) {
                JsonObject k = new JsonObject();
                k.addProperty(KEY_TIME, p.timeMs);
                k.addProperty(KEY_EASING, p.easing);
                k.add(KEY_OFFSETS, floats(p.off));
                if (p.presetOwned) k.addProperty(KEY_PRESET, true);
                keys.add(k);
            }
            o.add(KEY_POSES, keys);
        }
        return o;
    }

    /**
     * {@code null} for anything unusable, so a caller can simply leave its field null and keep the
     * object. Never throws.
     */
    public static MeshWarpSpec fromJson(JsonObject o) {
        if (o == null) return null;
        try {
            int lvl = o.has(KEY_LEVEL) ? MeshWarp.clampLevel(o.get(KEY_LEVEL).getAsInt())
                    : MeshWarp.L1;
            MeshWarpSpec spec = new MeshWarpSpec(lvl);
            int arity = spec.arity();
            int side = spec.side();

            float[] statics = readLattice(o, arity, side);
            if (statics == NO_MATCH) return null;   // present but wrong length: drop the warp
            if (statics != null) System.arraycopy(statics, 0, spec.off, 0, arity);

            if (o.has(KEY_POSES) && o.get(KEY_POSES).isJsonArray()) {
                JsonArray keys = o.getAsJsonArray(KEY_POSES);
                for (int i = 0; i < keys.size(); i++) {
                    try {
                        JsonObject k = keys.get(i).getAsJsonObject();
                        float[] pose = readLattice(k, arity, side);
                        if (pose == null || pose == NO_MATCH) continue;   // drop this pose only
                        String e = k.has(KEY_EASING) ? k.get(KEY_EASING).getAsString()
                                : MeshPoseTrack.DEFAULT_EASING;
                        MeshPoseTrack t = spec.ensureTrack();
                        if (t.put(k.get(KEY_TIME).getAsLong(), pose, e)
                                && k.has(KEY_PRESET) && k.get(KEY_PRESET).getAsBoolean()) {
                            for (MeshPoseTrack.Pose pp : t.poses()) {
                                if (pp.timeMs == k.get(KEY_TIME).getAsLong()) {
                                    pp.presetOwned = true;
                                    break;
                                }
                            }
                        }
                    } catch (RuntimeException ignored) {
                        // One bad pose does not cost the others.
                    }
                }
                if (spec.track != null && spec.track.isEmpty()) spec.track = null;
            }
            return spec;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Sentinel: the field was present but the wrong length. Distinct from "absent" (null). */
    private static final float[] NO_MATCH = new float[0];

    /**
     * Read {@code off} (nudges) or, for tolerance, {@code pts} (absolute unit-square positions,
     * the design spec's spelling) converted to nudges. Absent → null; wrong length → NO_MATCH.
     */
    private static float[] readLattice(JsonObject o, int arity, int side) {
        JsonElement el = o.has(KEY_OFFSETS) ? o.get(KEY_OFFSETS)
                : (o.has(KEY_ABS_POINTS) ? o.get(KEY_ABS_POINTS) : null);
        if (el == null || !el.isJsonArray()) return null;
        boolean absolute = !o.has(KEY_OFFSETS);
        JsonArray a = el.getAsJsonArray();
        if (a.size() != arity) return NO_MATCH;
        float[] v = new float[arity];
        for (int i = 0; i < arity; i++) {
            float f = a.get(i).getAsFloat();
            if (absolute) {
                int pt = i >> 1;
                f -= ((i & 1) == 0) ? MeshWarp.baseU(pt % side, side)
                        : MeshWarp.baseV(pt / side, side);
            }
            v[i] = MeshWarp.clampNudge(f);
        }
        return v;
    }

    private static JsonArray floats(float[] a) {
        JsonArray j = new JsonArray();
        for (float f : a) j.add(f);
        return j;
    }
}
