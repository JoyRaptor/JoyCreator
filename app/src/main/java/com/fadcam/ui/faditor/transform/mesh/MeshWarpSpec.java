package com.fadcam.ui.faditor.transform.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * THE MODEL for one object's deformation: which topology, the authored pose, an optional pose
 * track, and the JSON that persists all three.
 *
 * <p>Self-serialising, like {@code CompositingSpec} — the in-tree precedent for a model class that
 * owns its own wire format and reads it tolerantly. {@code ProjectStorage} calls {@link #toJson} /
 * {@link #fromJson} and never needs to know a field name.</p>
 *
 * <h3>Absent means zero cost, and that is a guarantee, not a hope</h3>
 * <ul>
 *   <li><b>Read:</b> no {@code mesh} field means a null spec means no code path entered. Every
 *       project written before this feature existed takes that branch.</li>
 *   <li><b>Write:</b> {@link #toJson} returns null when the pose is identity and there is no track,
 *       so an object whose Shape tool was opened and closed without a drag writes a
 *       <b>byte-identical</b> file. Same omit-empty discipline {@code KeyframeCodec.toJson} uses.</li>
 *   <li><b>Render:</b> {@link #hasWarp} is the gate, and it is checked BEFORE any GL object is
 *       created — no framebuffer, no program, no extra pass.</li>
 * </ul>
 *
 * <h3>Wire format</h3>
 * <pre>
 * "mesh": {
 *   "k":  "lattice",
 *   "tp": [2],
 *   "h":  [0,0, 0,-0.06, 0,0,  0,0, 0,0, 0,0,  0,0, 0,0.06, 0,0],
 *   "keys": [ { "t": 0,    "e": "EASE_OUT", "h": [ ...18 floats... ] },
 *             { "t": 1200, "e": "LINEAR",   "h": [ ...18 floats... ] } ]
 * }
 * </pre>
 * <p><b>{@code k} plus {@code tp} is the whole reason this format survives puppeteering.</b> The
 * topology writes itself as a name and a float array, so a traced contour with three hundred points
 * lands in the same field a lattice level does, and this file needs no edit. {@code h} is
 * "the authored handle values", whatever those are for that kind — nudges for the grid, pin
 * positions for a puppet.</p>
 * <p>For the grid, {@code h} is <b>nudges</b>: see {@link LatticeDeformer} for why a nudge and not
 * an absolute point. SPEC_20260902 §3.5 sketched the field as {@code "pts"} with absolute
 * positions and a bare {@code "lvl"}; {@link #fromJson} accepts both spellings and converts, so a
 * file written against either reading of that spec loads. Only the format above is ever written.</p>
 *
 * <h3>Tolerance rules — what keeps the owner's existing projects working</h3>
 * <ol>
 *   <li>Unknown {@code k} → the whole spec is DROPPED and the object kept. A newer build's
 *       topology must not stop a project opening.</li>
 *   <li>{@code h} of the wrong arity → the spec is dropped, the object kept. Never thrown.</li>
 *   <li>A pose whose array disagrees with the topology → that pose is dropped, the rest kept. Same
 *       "one bad track does not cost the others" reasoning as {@code KeyframeCodec.fromJson}.</li>
 *   <li>Unknown easing → left as the stored string; {@code Easing.fromName} already falls back when
 *       the app installs its {@link MeshPoseTrack.Curve}. Not re-implemented here.</li>
 *   <li>Anything that throws → null spec, object intact.</li>
 * </ol>
 *
 * <p><b>Size.</b> Fifty floats is about 450 bytes of JSON per pose; a ten-pose animated 5x5 bend is
 * roughly 4.5 KB per object, twenty such objects 90 KB. Project files are already well past that,
 * so this stays plain diffable JSON rather than a binary blob nobody could repair by hand.</p>
 *
 * <p>gson only. No Android imports.</p>
 */
public final class MeshWarpSpec {

    public static final String KEY_KIND = "k";
    public static final String KEY_TOPO_PARAMS = "tp";
    public static final String KEY_HANDLES = "h";
    public static final String KEY_POSES = "keys";
    public static final String KEY_TIME = "t";
    public static final String KEY_EASING = "e";

    /** Accepted on read only — SPEC_20260902's spellings. */
    public static final String LEGACY_KEY_LEVEL = "lvl";
    public static final String LEGACY_KEY_OFFSETS = "off";
    /** Accepted on read only, in ABSOLUTE unit-square positions rather than nudges. */
    public static final String LEGACY_KEY_POINTS = "pts";

    /** Per-group depth, and its keyframes. Absent means every piece sits at zero. */
    public static final String KEY_GROUP_Z = "gz";
    public static final String KEY_GROUP_Z_KEYS = "gzk";

    private MeshTopology topology;
    private float[] handles;
    private MeshPoseTrack track;
    /** One depth per draw group. Higher is nearer the viewer. */
    private float[] groupZ;
    /** Keyframed depth, arity == groupCount, one component per group. Null when nothing animates. */
    private MeshPoseTrack groupZTrack;
    private MeshDeformer probe;      // lazily made, only to answer isIdentity

    public MeshWarpSpec(MeshTopology topology) {
        this.topology = topology;
        this.handles = new float[topology == null ? 0 : topology.handleArity()];
        identityProbe().identityPose(topology, handles);
    }

    /** The grid-warp constructor the transform tool uses. */
    public static MeshWarpSpec lattice(int level) {
        return new MeshWarpSpec(new LatticeTopology(level));
    }

    public MeshTopology topology() { return topology; }

    /** The authored pose. Live — the edit seam writes into it and clamps through the deformer. */
    public float[] handles() { return handles; }

    public int arity() { return topology == null ? 0 : topology.handleArity(); }

    public MeshPoseTrack track() { return track; }

    // ── draw order ──────────────────────────────────────────────────────────────────────────

    /**
     * WHICH PIECE IS IN FRONT. One number per draw group; higher is nearer the viewer.
     *
     * <p>Per GROUP, never per handle. Two pins on one arm cannot put it both in front of and
     * behind the body, so a pin-level depth would be a control that cannot express the thing it
     * looks like it expresses. A piece of artwork is the smallest thing that can have a depth.
     *
     * <p>Defaults to all zeros, which leaves the traced order alone — largest piece first.
     */
    public float[] groupZ() {
        int n = groupCount();
        if (groupZ == null || groupZ.length != n) {
            float[] grown = new float[n];
            if (groupZ != null) System.arraycopy(groupZ, 0, grown, 0, Math.min(n, groupZ.length));
            groupZ = grown;
        }
        return groupZ;
    }

    public int groupCount() { return topology == null ? 1 : Math.max(1, topology.groupCount()); }

    public void setGroupZ(int group, float z) {
        float[] g = groupZ();
        if (group >= 0 && group < g.length && !Float.isNaN(z)) g[group] = z;
    }

    /** The keyframed depth track, made on first ask. Arity is one float per group. */
    public MeshPoseTrack groupZTrack() {
        if (groupZTrack == null || groupZTrack.arity() != groupCount()) {
            groupZTrack = new MeshPoseTrack(groupCount());
        }
        return groupZTrack;
    }

    public MeshPoseTrack groupZTrackOrNull() { return groupZTrack; }

    public void setGroupZTrack(MeshPoseTrack t) { this.groupZTrack = t; }

    /** True when the pieces do not all sit at the same depth, statically or over time. */
    public boolean hasGroupDepth() {
        if (groupZTrack != null && !groupZTrack.isEmpty()) return true;
        if (groupZ == null) return false;
        for (float z : groupZ) if (z != 0f) return true;
        return false;
    }

    /**
     * The order to composite the groups in at {@code timeMs}: BACK TO FRONT.
     *
     * <p>A stable sort on depth, so groups at the same depth keep the order they were traced in
     * and nothing flickers between two pieces that were never told which is in front. Stability is
     * the whole reason this is an insertion sort rather than a library call.
     *
     * @param out at least {@code groupCount()} long
     * @return false when {@code out} is too short; it is then untouched
     */
    public boolean groupOrderAt(long timeMs, int[] out) {
        int n = groupCount();
        if (out == null || out.length < n) return false;
        float[] z = groupZ();
        float[] at = z;
        if (groupZTrack != null && !groupZTrack.isEmpty() && groupZTrack.arity() == n) {
            float[] animated = new float[n];
            if (groupZTrack.valueAt(timeMs, animated)) at = animated;
        }
        for (int i = 0; i < n; i++) out[i] = i;
        for (int i = 1; i < n; i++) {
            int v = out[i];
            float key = at[v];
            int j = i - 1;
            while (j >= 0 && at[out[j]] > key) {     // strict: equal depths keep traced order
                out[j + 1] = out[j];
                j--;
            }
            out[j + 1] = v;
        }
        return true;
    }

    /** Create the track on first keyframe. One diamond, whatever the topology. */
    public MeshPoseTrack ensureTrack() {
        if (track == null) track = new MeshPoseTrack(arity());
        return track;
    }

    public void setTrack(MeshPoseTrack t) { this.track = t; }

    /**
     * THE GATE. False means "there is nothing here" — do not create a framebuffer, do not compile a
     * program, do not emit an effect, write nothing to the project file.
     */
    public boolean hasWarp() {
        if (topology == null) return false;
        if (track != null && !track.isEmpty()) return true;
        // ORDERED PIECES ARE A WARP even with no bend in them. A character whose arm has been put
        // behind its body is not the flat texture any more, and the reordering happens nowhere
        // else. Only ever true for a mesh with more than one piece, so "no deformation costs
        // exactly zero" still holds for every lattice and every ordinary picture.
        if (groupCount() > 1 && hasGroupDepth()) return true;
        return !identityProbe().isIdentity(handles);
    }

    /**
     * The pose at a timestamp, written into {@code out}. Falls back to the static pose when there
     * is no track. Allocation-free.
     */
    public boolean handlesAt(long timeMs, float[] out) {
        int n = arity();
        if (out == null || out.length < n) return false;
        if (track != null && track.valueAt(timeMs, out)) return true;
        System.arraycopy(handles, 0, out, 0, n);
        return true;
    }

    /**
     * Move to a different topology, rewriting the static pose and EVERY keyframed pose together.
     *
     * <p>All-at-once is the invariant that makes a mixed-arity track unrepresentable. The rewriting
     * rule is the topology lane's — {@link LatticeDeformer#setLevel} supplies the grid's.</p>
     *
     * @return false when the remapper produced a pose of the wrong length; the spec is then left
     *         completely unchanged
     */
    public boolean retopologize(MeshTopology to, MeshPoseTrack.Remapper remapper) {
        if (to == null || remapper == null) return false;
        int newArity = to.handleArity();
        float[] newHandles = remapper.remap(handles);
        if (newHandles == null || newHandles.length != newArity) return false;
        if (track != null && !track.remap(newArity, remapper)) return false;
        int oldGroups = groupCount();
        topology = to;
        handles = newHandles;
        probe = null;
        int newGroups = groupCount();
        if (newGroups != oldGroups) {
            // The pieces changed, so a depth indexed by the old ones means nothing. Kept where the
            // groups still line up, dropped where they do not — the same rule as a pin's pose,
            // because the alternative is a limb silently jumping in front of the body on rebuild.
            float[] grown = new float[newGroups];
            if (groupZ != null) {
                System.arraycopy(groupZ, 0, grown, 0, Math.min(newGroups, groupZ.length));
            }
            groupZ = grown;
            if (groupZTrack != null) {
                final int keep = Math.min(newGroups, oldGroups);
                boolean ok = groupZTrack.remap(newGroups, new MeshPoseTrack.Remapper() {
                    @Override public float[] remap(float[] pose) {
                        float[] o = new float[newGroups];
                        if (pose != null) System.arraycopy(pose, 0, o, 0, Math.min(keep, pose.length));
                        return o;
                    }
                });
                if (!ok) groupZTrack = null;
            }
        }
        return true;
    }

    public MeshWarpSpec copy() {
        MeshWarpSpec s = new MeshWarpSpec(topology);
        s.handles = handles.clone();
        s.track = track == null ? null : track.copy();
        s.groupZ = groupZ == null ? null : groupZ.clone();
        s.groupZTrack = groupZTrack == null ? null : groupZTrack.copy();
        return s;
    }

    private MeshDeformer identityProbe() {
        if (probe == null) probe = MeshTopologies.deformerFor(topology);
        return probe == null ? ZERO_PROBE : probe;
    }

    /** Last resort when a kind has no registered deformer: all-zero means identity. */
    private static final MeshDeformer ZERO_PROBE = new MeshDeformer() {
        @Override public boolean supports(MeshTopology t) { return true; }
        @Override public boolean isIdentity(float[] v) { return LatticeDeformer.isIdentityPose(v); }
        @Override public void identityPose(MeshTopology t, float[] out) {
            if (out != null) java.util.Arrays.fill(out, 0f);
        }
        @Override public float clampComponent(float v) { return v; }
        @Override public boolean solve(MeshTopology t, MeshBuffers b, float[] v) { return false; }
    };

    // ── Serialisation ───────────────────────────────────────────────────

    /** @return null when there is nothing to persist — see the byte-identical-save guarantee */
    public JsonObject toJson() {
        if (!hasWarp()) return null;
        JsonObject o = new JsonObject();
        o.addProperty(KEY_KIND, topology.kind());
        o.add(KEY_TOPO_PARAMS, floats(topology.params()));
        o.add(KEY_HANDLES, floats(handles));
        if (track != null && !track.isEmpty()) {
            JsonArray keys = new JsonArray();
            for (MeshPoseTrack.Pose p : track.poses()) {
                JsonObject k = new JsonObject();
                k.addProperty(KEY_TIME, p.timeMs);
                k.addProperty(KEY_EASING, p.easing);
                k.add(KEY_HANDLES, floats(p.values));
                keys.add(k);
            }
            o.add(KEY_POSES, keys);
        }
        // SPARSE: written only when a piece has actually been moved in depth, so every existing
        // project's JSON is byte-identical to what it was.
        if (groupZ != null && hasGroupDepth()) {
            o.add(KEY_GROUP_Z, floats(groupZ));
        }
        if (groupZTrack != null && !groupZTrack.isEmpty()) {
            JsonArray keys = new JsonArray();
            for (MeshPoseTrack.Pose p : groupZTrack.poses()) {
                JsonObject k = new JsonObject();
                k.addProperty(KEY_TIME, p.timeMs);
                k.addProperty(KEY_EASING, p.easing);
                k.add(KEY_HANDLES, floats(p.values));
                keys.add(k);
            }
            o.add(KEY_GROUP_Z_KEYS, keys);
        }
        return o;
    }

    /** @return null for absent, malformed or unknown-kind data. Never throws. */
    public static MeshWarpSpec fromJson(JsonObject o) {
        if (o == null) return null;
        try {
            MeshTopology topo = readTopology(o);
            if (topo == null) return null;
            MeshWarpSpec s = new MeshWarpSpec(topo);
            float[] h = readPose(o, topo);
            if (h == null) return null;
            s.handles = h;
            if (o.has(KEY_POSES) && o.get(KEY_POSES).isJsonArray()) {
                JsonArray keys = o.getAsJsonArray(KEY_POSES);
                MeshPoseTrack t = new MeshPoseTrack(topo.handleArity());
                for (int i = 0; i < keys.size(); i++) {
                    try {
                        JsonObject k = keys.get(i).getAsJsonObject();
                        float[] pv = readPose(k, topo);
                        if (pv == null) continue;                       // rule 3: drop that pose only
                        String e = k.has(KEY_EASING) && !k.get(KEY_EASING).isJsonNull()
                                ? k.get(KEY_EASING).getAsString() : MeshPoseTrack.DEFAULT_EASING;
                        t.put(k.get(KEY_TIME).getAsLong(), pv, e);
                    } catch (RuntimeException ignored) {
                        // one bad pose does not cost the others
                    }
                }
                if (!t.isEmpty()) s.track = t;
            }
            int groups = s.groupCount();
            if (o.has(KEY_GROUP_Z)) {
                float[] z = readFloats(o.get(KEY_GROUP_Z));
                // A depth list from a different number of pieces is not this puppet's. Kept where
                // the pieces line up and dropped where they do not, rather than refusing the whole
                // warp over it: a wrong order is a visible annoyance, no warp at all is lost work.
                if (z != null) {
                    float[] fit = new float[groups];
                    System.arraycopy(z, 0, fit, 0, Math.min(groups, z.length));
                    s.groupZ = fit;
                }
            }
            if (o.has(KEY_GROUP_Z_KEYS) && o.get(KEY_GROUP_Z_KEYS).isJsonArray()) {
                JsonArray keys = o.getAsJsonArray(KEY_GROUP_Z_KEYS);
                MeshPoseTrack zt = new MeshPoseTrack(groups);
                for (int i = 0; i < keys.size(); i++) {
                    try {
                        JsonObject k = keys.get(i).getAsJsonObject();
                        float[] zv = readFloats(k.get(KEY_HANDLES));
                        if (zv == null || zv.length != groups) continue;
                        String e = k.has(KEY_EASING) && !k.get(KEY_EASING).isJsonNull()
                                ? k.get(KEY_EASING).getAsString() : MeshPoseTrack.DEFAULT_EASING;
                        zt.put(k.get(KEY_TIME).getAsLong(), zv, e);
                    } catch (RuntimeException ignored) {
                        // one bad depth key does not cost the others
                    }
                }
                if (!zt.isEmpty()) s.groupZTrack = zt;
            }
            return s;
        } catch (RuntimeException e) {
            return null;                                                // rule 5
        }
    }

    private static MeshTopology readTopology(JsonObject o) {
        if (o.has(KEY_KIND) && !o.get(KEY_KIND).isJsonNull()) {
            String kind = o.get(KEY_KIND).getAsString();
            float[] params = readFloats(o.get(KEY_TOPO_PARAMS));
            return MeshTopologies.create(kind, params);                 // null for unknown: rule 1
        }
        if (o.has(LEGACY_KEY_LEVEL) && !o.get(LEGACY_KEY_LEVEL).isJsonNull()) {
            return new LatticeTopology(o.get(LEGACY_KEY_LEVEL).getAsInt());
        }
        return null;
    }

    /**
     * Read one pose, accepting the legacy {@code off} (nudges) and {@code pts} (absolute) spellings.
     *
     * <p>{@code pts} is converted by subtracting the handle's rest position, which is the definition
     * of a nudge and is asked of the TOPOLOGY rather than computed from a row and column — so the
     * conversion is not a lattice special case either.</p>
     */
    private static float[] readPose(JsonObject o, MeshTopology topo) {
        int arity = topo.handleArity();
        JsonElement el = o.has(KEY_HANDLES) ? o.get(KEY_HANDLES)
                : (o.has(LEGACY_KEY_OFFSETS) ? o.get(LEGACY_KEY_OFFSETS) : null);
        boolean absolute = false;
        if (el == null || el.isJsonNull()) {
            el = o.has(LEGACY_KEY_POINTS) ? o.get(LEGACY_KEY_POINTS) : null;
            absolute = true;
        }
        if (el == null || !el.isJsonArray()) return null;
        float[] v = readFloats(el);
        if (v == null || v.length != arity) return null;                // rule 2
        if (absolute && topo.handleComponents() == 2) {
            for (int i = 0; i < topo.handleCount(); i++) {
                v[i * 2] -= topo.handleRestX(i);
                v[i * 2 + 1] -= topo.handleRestY(i);
            }
        }
        return v;
    }

    private static float[] readFloats(JsonElement el) {
        if (el == null || !el.isJsonArray()) return null;
        JsonArray a = el.getAsJsonArray();
        float[] out = new float[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    private static JsonArray floats(float[] a) {
        JsonArray j = new JsonArray();
        if (a != null) for (float v : a) j.add(v);
        return j;
    }
}
