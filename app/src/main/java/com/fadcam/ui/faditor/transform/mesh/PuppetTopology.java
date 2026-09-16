package com.fadcam.ui.faditor.transform.mesh;

/**
 * Stage 3a of puppeteering: the traced, triangulated shape AS A {@link MeshTopology}.
 *
 * <p>This is the class the 2026-09-04 architecture note was written to make possible. It answers
 * the same three questions {@link LatticeTopology} answers — what triangles, where are the handles,
 * how do I write myself down — while being structurally nothing like it: irregular triangles, no
 * rows, no columns, and six-to-eight authored pins instead of nine or twenty-five lattice knots.
 * Because both answer only those three questions, the pose track, the fold guard, the buffers, the
 * serialiser and the GL stamp cannot tell them apart.
 *
 * <h3>Handles are PINS, and their values are OFFSETS</h3>
 * <p>Handle {@code i} is pin {@code i}; its two floats are {@code (dx, dy)} FROM the pin's rest
 * position, exactly like a lattice knot's nudge. That is not a coincidence to be tidied up later —
 * it is what lets {@link MeshPoseTrack} store a puppet pose with no new code, and what makes a
 * keyframed puppet work the day it is switched on.
 *
 * <h3>What {@code params()} stores, and the property that buys</h3>
 * <p>Not the triangles — the CONTOUR, the interior density and the pin rest positions. The mesh is
 * re-triangulated deterministically on load from exactly those inputs.
 *
 * <p>That is smaller, but the reason is not size. <b>The pose is per-PIN, and pins are stored;
 * vertices are derived.</b> So improving the triangulator later — edge-flipping toward Delaunay,
 * say — changes the triangles under an existing puppet WITHOUT invalidating a single authored
 * keyframe, because no keyframe ever referred to a vertex. Storing triangles would have frozen
 * today's triangulator into the file format forever.
 *
 * <p>Immutable, like every topology. No Android imports.
 */
public final class PuppetTopology implements MeshTopology {

    public static final String KIND = "puppet";

    /** Bumped only if {@link #params()}'s LAYOUT changes, never when the triangulator improves. */
    private static final float FORMAT_V1 = 1f;

    /**
     * Several contours instead of one. A v1 puppet still loads — it is simply a v2 with one
     * island — because a project saved before detached limbs existed must open unchanged.
     */
    private static final float FORMAT_V2 = 2f;

    /**
     * Carries the authored weight knobs — one softness for the character, an area and a strength
     * per pin. They belong in here because they are inputs to the WEIGHT TABLE, which this class
     * owns and caches; a v1 or v2 puppet loads with the neutral values and poses exactly as it
     * did, because neutral softness is the constant the table used before the slider was wired.
     */
    private static final float FORMAT_V3 = 3f;

    /**
     * Adds the per-pin MUTE. Same argument as softness: a muted pin changes the weight table, so
     * it is part of what this class is, and a puppet reloaded without it would quietly start
     * bending from a pin the user had switched off. v1 through v3 load with nothing muted, which
     * is what they meant.
     */
    private static final float FORMAT_V4 = 4f;

    /**
     * Adds the per-pin WEIGHT OVERRIDE. Same argument again: an override changes the weight
     * table, so it is part of what this class is, and a puppet reloaded without it would bend
     * differently from the one the user authored. v1 through v4 load with every pin automatic,
     * which is what they meant.
     */
    private static final float FORMAT_V5 = 5f;

    private final float[][] rings;     // one contour per island, interleaved x,y, unit space
    private final int interior;        // interior seeding density, one axis, for the LARGEST island
    private final float[] pins;        // pin REST positions, interleaved x,y, unit space

    private final float[] verts;       // derived
    private final short[] indices;     // derived
    private final int contourCount;    // derived
    private final int[] islandStart;   // derived
    private final int[] indexStart;    // derived

    private final float softness;      // 0..1, the character's Softness
    /**
     * Per pin, a multiplier on its influence before normalisation. 1 is automatic.
     *
     * <p>Wire format V5. A V4 file loads with every entry at 1, which is exactly what those files
     * meant, so there is nothing to migrate and no version of this app reads a file wrong.
     */
    private final float[] weightScale;
    private final float[] stiffArea;   // per pin, reach along the mesh
    private final float[] stiffStr;    // per pin, 0 = an ordinary pin
    private final boolean[] muted;     // per pin, true = affects nothing
    private final int stamp;

    /** Lazily derived; see {@link #weights()} for why a plain volatile is enough here. */
    private volatile PuppetWeights weights;

    /**
     * @param ring     the simplified contour from {@link AlphaContour}
     * @param interior interior seeding density (see {@link PuppetTriangulator#triangulate})
     * @param pins     pin rest positions, interleaved x,y, unit space. May be empty — a puppet
     *                 with no pins is legal and simply cannot be posed yet, which is the state it
     *                 is in between "traced" and "the user placed a pin".
     */
    public PuppetTopology(float[] ring, int interior, float[] pins) {
        this(new float[][]{ring}, interior, pins);
    }

    /**
     * The DETACHED-LIMBS constructor: one ring per opaque piece of the artwork.
     *
     * <p>This is the ordinary case for the art this feature was built for. The pieces become one
     * topology with one pose and one set of pins — a character, not several puppets — while
     * staying separate in the mesh, so no triangle ever spans the gap between two limbs.
     *
     * @param rings one closed contour per island, as {@link AlphaContour#traceAll} returns them.
     *              Rings that fail to triangulate are dropped; at least one must survive.
     */
    public PuppetTopology(float[][] rings, int interior, float[] pins) {
        this(rings, interior, pins, PuppetWeights.SOFTNESS_NEUTRAL, null, null);
    }

    /**
     * The full constructor: geometry, pins, and the authored knobs that shape the weight table.
     *
     * @param softness      0..1 from the character's Softness slider
     * @param stiffArea     per pin, how far its stiffness reaches along the mesh, or null
     * @param stiffStrength per pin, 0 for an ordinary pin, or null when nothing is stiff
     */
    public PuppetTopology(float[][] rings, int interior, float[] pins, float softness,
                          float[] stiffArea, float[] stiffStrength) {
        this(rings, interior, pins, softness, stiffArea, stiffStrength, null);
    }

    /** @param muted per pin: true stops it moving anything, keeping its animation. */
    public PuppetTopology(float[][] rings, int interior, float[] pins, float softness,
                          float[] stiffArea, float[] stiffStrength, boolean[] muted) {
        this(rings, interior, pins, softness, stiffArea, stiffStrength, muted, null);
    }

    /** @param weightScale per pin, a multiplier on its influence; null or 1 is automatic. */
    public PuppetTopology(float[][] rings, int interior, float[] pins, float softness,
                          float[] stiffArea, float[] stiffStrength, boolean[] muted,
                          float[] weightScale) {
        if (rings == null || rings.length == 0) {
            throw new IllegalArgumentException("puppet needs at least one contour");
        }
        java.util.List<float[]> kept = new java.util.ArrayList<>(rings.length);
        for (float[] r : rings) {
            if (r != null && r.length >= 6) kept.add(r.clone());
        }
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("puppet needs a contour of at least 3 points");
        }
        this.rings = kept.toArray(new float[kept.size()][]);
        this.interior = Math.max(0, interior);
        this.pins = pins == null ? new float[0] : pins.clone();
        int pinN = this.pins.length / 2;
        this.softness = Float.isNaN(softness)
                ? PuppetWeights.SOFTNESS_NEUTRAL : Math.max(0f, Math.min(1f, softness));
        this.weightScale = fitPerPin(weightScale, pinN, 1f);
        this.stiffArea = fitPerPin(stiffArea, pinN, 0.25f);
        this.stiffStr = fitPerPin(stiffStrength, pinN, 0f);
        this.muted = new boolean[pinN];
        for (int i = 0; i < pinN; i++) {
            this.muted[i] = muted != null && i < muted.length && muted[i];
        }

        PuppetTriangulator.Mesh m = PuppetTriangulator.triangulate(this.rings, this.interior);
        if (m == null) throw new IllegalArgumentException("contour did not triangulate");
        this.verts = m.verts;
        this.indices = m.indices;
        this.contourCount = m.contourCount;
        this.islandStart = m.islandStart;
        this.indexStart = m.indexStart;

        // Structure only — the pin POSITIONS are structure (they change the solve), but a pin's
        // POSE is not, and no pose is in here.
        int h = 17;
        for (float[] r : this.rings) h = h * 31 + r.length;
        h = h * 31 + this.rings.length;
        h = h * 31 + this.interior;
        h = h * 31 + this.pins.length;
        h = h * 31 + Float.floatToIntBits(this.softness);
        for (float f : this.weightScale) h = h * 31 + Float.floatToIntBits(f);
        for (float f : this.stiffArea) h = h * 31 + Float.floatToIntBits(f);
        for (float f : this.stiffStr) h = h * 31 + Float.floatToIntBits(f);
        for (boolean b : this.muted) h = h * 31 + (b ? 1 : 0);
        h = h * 31 + verts.length;
        h = h * 31 + indices.length;
        for (int i = 0; i < this.pins.length; i++) h = h * 31 + Float.floatToIntBits(this.pins[i]);
        this.stamp = h;
    }

    @Override public String kind() { return KIND; }

    /**
     * {@code [FORMAT_V5, interior, pinCount, islandCount, softness, pointsPerIsland...,
     * stiffArea..., stiffStrength..., muted..., weightScale..., pins..., rings...]}.
     *
     * <p>Floats throughout because {@link MeshTopology#params()} is float[] — which its own doc
     * explains was chosen precisely because "a puppet's parameters ARE its traced contour".
     */
    @Override
    public float[] params() {
        int pinN = pins.length / 2;
        int ringFloats = 0;
        for (float[] r : rings) ringFloats += r.length;
        float[] out = new float[5 + rings.length + pinN * 4 + pins.length + ringFloats];
        out[0] = FORMAT_V5;
        out[1] = interior;
        out[2] = pinN;
        out[3] = rings.length;
        out[4] = softness;
        int at = 5;
        for (float[] r : rings) out[at++] = r.length / 2;
        System.arraycopy(stiffArea, 0, out, at, pinN);
        at += pinN;
        System.arraycopy(stiffStr, 0, out, at, pinN);
        at += pinN;
        for (int i = 0; i < pinN; i++) out[at + i] = muted[i] ? 1f : 0f;
        at += pinN;
        System.arraycopy(weightScale, 0, out, at, pinN);
        at += pinN;
        System.arraycopy(pins, 0, out, at, pins.length);
        at += pins.length;
        for (float[] r : rings) {
            System.arraycopy(r, 0, out, at, r.length);
            at += r.length;
        }
        return out;
    }

    /** Rebuild from {@link #params()}. Returns null for anything malformed — never throws. */
    public static PuppetTopology fromParams(float[] p) {
        try {
            if (p == null || p.length < 4) return null;
            int format = Math.round(p[0]);
            int interior = Math.round(p[1]);
            int pinN = Math.round(p[2]);
            if (pinN < 0) return null;

            if (format == Math.round(FORMAT_V1)) {
                // A puppet saved before detached limbs existed: one ring, no per-island table.
                int ringN = Math.round(p[3]);
                if (ringN < 3 || p.length < 4 + pinN * 2 + ringN * 2) return null;
                float[] pins = new float[pinN * 2];
                float[] ring = new float[ringN * 2];
                System.arraycopy(p, 4, pins, 0, pins.length);
                System.arraycopy(p, 4 + pins.length, ring, 0, ring.length);
                return new PuppetTopology(ring, interior, pins);
            }
            boolean v5 = format == Math.round(FORMAT_V5);
            boolean v4 = v5 || format == Math.round(FORMAT_V4);
            boolean v3 = v4 || format == Math.round(FORMAT_V3);
            if (!v3 && format != Math.round(FORMAT_V2)) return null;

            int islands = Math.round(p[3]);
            int head = v3 ? 5 : 4;
            if (islands < 1 || islands > 4096 || p.length < head + islands) return null;
            float softness = v3 ? p[4] : PuppetWeights.SOFTNESS_NEUTRAL;
            int[] counts = new int[islands];
            int ringFloats = 0;
            for (int i = 0; i < islands; i++) {
                counts[i] = Math.round(p[head + i]);
                if (counts[i] < 3) return null;
                ringFloats += counts[i] * 2;
            }
            int at = head + islands;
            int knobFloats = (v3 ? pinN * 2 : 0) + (v4 ? pinN : 0) + (v5 ? pinN : 0);
            if (p.length < at + knobFloats + pinN * 2 + ringFloats) return null;
            float[] area = null, strength = null;
            boolean[] mute = null;
            if (v3) {
                area = new float[pinN];
                strength = new float[pinN];
                System.arraycopy(p, at, area, 0, pinN);
                at += pinN;
                System.arraycopy(p, at, strength, 0, pinN);
                at += pinN;
            }
            if (v4) {
                mute = new boolean[pinN];
                for (int i = 0; i < pinN; i++) mute[i] = p[at + i] >= 0.5f;
                at += pinN;
            }
            float[] wscale = null;
            if (v5) {
                wscale = new float[pinN];
                System.arraycopy(p, at, wscale, 0, pinN);
                at += pinN;
            }
            float[] pins = new float[pinN * 2];
            System.arraycopy(p, at, pins, 0, pins.length);
            at += pins.length;
            float[][] rings = new float[islands][];
            for (int i = 0; i < islands; i++) {
                rings[i] = new float[counts[i] * 2];
                System.arraycopy(p, at, rings[i], 0, rings[i].length);
                at += rings[i].length;
            }
            return new PuppetTopology(rings, interior, pins, softness, area, strength, mute,
                    wscale);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override public int topologyId() { return stamp; }

    @Override public int vertexCount() { return verts.length / 2; }

    @Override public int indexCount() { return indices.length; }

    @Override public int handleCount() { return pins.length / 2; }

    @Override public int handleComponents() { return 2; }

    @Override public int handleArity() { return handleCount() * 2; }

    @Override
    public void buildRest(float[] outRest, float[] outUv, short[] outIndices) {
        int n = verts.length;
        if (outRest != null && outRest.length >= n) System.arraycopy(verts, 0, outRest, 0, n);
        // UV EQUALS REST, exactly as the lattice does: the picture is sampled where the vertex
        // started, and deformation moves the vertex, never its source pixel. MeshBuffers' own doc
        // states this is true for both topologies, and it is what keeps the shader identical.
        if (outUv != null && outUv.length >= n) System.arraycopy(verts, 0, outUv, 0, n);
        if (outIndices != null && outIndices.length >= indices.length) {
            System.arraycopy(indices, 0, outIndices, 0, indices.length);
        }
    }

    @Override
    public float handleRestX(int i) {
        return (i < 0 || i * 2 >= pins.length) ? 0f : pins[i * 2];
    }

    @Override
    public float handleRestY(int i) {
        return (i < 0 || i * 2 + 1 >= pins.length) ? 0f : pins[i * 2 + 1];
    }

    /**
     * The bind-time weight table — which pin moves which vertex, measured ACROSS THE BODY.
     *
     * <p>Built on first ask and then held forever, which is safe precisely because a topology is
     * immutable: the pins are structure, so <b>moving a pin builds a new topology</b> and this
     * never goes stale. A pose, which is not structure, cannot touch it.
     *
     * <p>The race between two threads asking at once is benign — both compute the same table from
     * the same immutable inputs and one wins — so this costs no lock on the render path.
     *
     * @return null when there are no pins; the deformer is then the identity anyway
     */
    public PuppetWeights weights() {
        PuppetWeights w = weights;
        if (w == null && pins.length >= 2) {
            w = PuppetWeights.build(verts, indices, pins, islandStart, softness,
                    stiffArea, stiffStr, muted, weightScale);
            weights = w;
        }
        return w;
    }

    /** How many vertices came from the contour; the rest are interior. Diagnostics and tests. */
    public int contourCount() { return contourCount; }

    /**
     * The FIRST island's contour, defensively copied — the largest piece, since
     * {@link AlphaContour#traceAll} returns them largest first.
     */
    public float[] ring() { return rings[0].clone(); }

    /** Every island's contour, defensively copied. */
    public float[][] rings() {
        float[][] out = new float[rings.length][];
        for (int i = 0; i < rings.length; i++) out[i] = rings[i].clone();
        return out;
    }

    /** Trim or pad a per-pin array to exactly the pin count, so no caller can desynchronise it. */
    private static float[] fitPerPin(float[] src, int pinN, float fallback) {
        float[] out = new float[pinN];
        for (int i = 0; i < pinN; i++) {
            float v = (src != null && i < src.length) ? src[i] : fallback;
            out[i] = Float.isNaN(v) ? fallback : v;
        }
        return out;
    }

    /** The character's Softness, 0..1. */
    public float softness() { return softness; }

    /** Per-pin stiffness reach, defensively copied. */
    public float[] stiffArea() { return stiffArea.clone(); }

    /** Per-pin stiffness strength, defensively copied. 0 means an ordinary pin. */
    public float[] stiffStrength() { return stiffStr.clone(); }

    /** Per-pin mute, defensively copied. A muted pin moves nothing and keeps its animation. */
    public boolean[] muted() { return muted.clone(); }

    /** Per pin, the authored influence multiplier. 1 is automatic. */
    public float[] weightScale() { return weightScale.clone(); }

    /**
     * The DEPTH FIELD: each pin's depth spread across the mesh by the weights that bend it.
     *
     * <p>A vertex takes the weighted average of the pins that reach it, plus its own island's
     * depth. Two consequences worth being explicit about:
     * <ul>
     *   <li>a limb whose pins run from behind to in front hands over somewhere ALONG the limb,
     *       which is what a 3/4 stance needs and what a per-piece order cannot express;</li>
     *   <li>a pin on another island contributes exactly nothing, because its weight there is
     *       exactly zero — so an arm's depth cannot leak into the body it is crossing.</li>
     * </ul>
     *
     * <p>Falls back to the island's depth alone when there is no weight table (no pins yet), which
     * is the per-piece behaviour and the correct answer for a puppet nobody has rigged.
     */
    @Override
    public boolean vertexField(float[] handleValues, float[] groupValues, float[] out) {
        int n = vertexCount();
        if (out == null || out.length < n) return false;
        int pinN = handleCount();
        PuppetWeights w = (handleValues != null && handleValues.length >= pinN && pinN > 0)
                ? weights() : null;
        for (int v = 0; v < n; v++) {
            float z = 0f;
            if (groupValues != null) {
                int g = islandOfVertex(v);
                if (g >= 0 && g < groupValues.length) z = groupValues[g];
            }
            if (w != null && w.vertexCount() == n && w.pinCount() == pinN) {
                for (int i = 0; i < pinN; i++) z += w.weight(v, i) * handleValues[i];
            }
            out[v] = z;
        }
        return true;
    }

    /** Which island a vertex sits in, or -1. Linear over a handful of islands. */
    public int islandOfVertex(int vertex) {
        for (int i = 0; i + 1 < islandStart.length; i++) {
            if (vertex >= islandStart[i] && vertex < islandStart[i + 1]) return i;
        }
        return -1;
    }

    /** How many separate pieces of artwork this puppet covers. One for ordinary art. */
    public int islandCount() { return rings.length; }

    /** One draw group per island: the pieces composite in an order, not as one flat sample. */
    @Override public int groupCount() { return indexStart.length - 1; }

    @Override
    public void groupIndexStart(int[] out) {
        if (out == null || out.length < indexStart.length) return;
        System.arraycopy(indexStart, 0, out, 0, indexStart.length);
    }

    /** Where island {@code i}'s vertices begin; the last entry is the total vertex count. */
    public int[] islandStart() { return islandStart.clone(); }
}
