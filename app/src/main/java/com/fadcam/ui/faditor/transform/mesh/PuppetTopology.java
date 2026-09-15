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

    private final float[] ring;        // contour, interleaved x,y, unit space
    private final int interior;        // interior seeding density, one axis
    private final float[] pins;        // pin REST positions, interleaved x,y, unit space

    private final float[] verts;       // derived
    private final short[] indices;     // derived
    private final int contourCount;    // derived
    private final int stamp;

    /**
     * @param ring     the simplified contour from {@link AlphaContour}
     * @param interior interior seeding density (see {@link PuppetTriangulator#triangulate})
     * @param pins     pin rest positions, interleaved x,y, unit space. May be empty — a puppet
     *                 with no pins is legal and simply cannot be posed yet, which is the state it
     *                 is in between "traced" and "the user placed a pin".
     */
    public PuppetTopology(float[] ring, int interior, float[] pins) {
        if (ring == null || ring.length < 6) {
            throw new IllegalArgumentException("puppet needs a contour of at least 3 points");
        }
        this.ring = ring.clone();
        this.interior = Math.max(0, interior);
        this.pins = pins == null ? new float[0] : pins.clone();

        PuppetTriangulator.Mesh m = PuppetTriangulator.triangulate(this.ring, this.interior);
        if (m == null) throw new IllegalArgumentException("contour did not triangulate");
        this.verts = m.verts;
        this.indices = m.indices;
        this.contourCount = m.contourCount;

        // Structure only — the pin POSITIONS are structure (they change the solve), but a pin's
        // POSE is not, and no pose is in here.
        int h = 17;
        h = h * 31 + this.ring.length;
        h = h * 31 + this.interior;
        h = h * 31 + this.pins.length;
        h = h * 31 + verts.length;
        h = h * 31 + indices.length;
        for (int i = 0; i < this.pins.length; i++) h = h * 31 + Float.floatToIntBits(this.pins[i]);
        this.stamp = h;
    }

    @Override public String kind() { return KIND; }

    /**
     * {@code [FORMAT, interior, pinCount, ringPointCount, pins..., ring...]}.
     *
     * <p>Floats throughout because {@link MeshTopology#params()} is float[] — which its own doc
     * explains was chosen precisely because "a puppet's parameters ARE its traced contour".
     */
    @Override
    public float[] params() {
        int pinN = pins.length / 2, ringN = ring.length / 2;
        float[] out = new float[4 + pins.length + ring.length];
        out[0] = FORMAT_V1;
        out[1] = interior;
        out[2] = pinN;
        out[3] = ringN;
        System.arraycopy(pins, 0, out, 4, pins.length);
        System.arraycopy(ring, 0, out, 4 + pins.length, ring.length);
        return out;
    }

    /** Rebuild from {@link #params()}. Returns null for anything malformed — never throws. */
    public static PuppetTopology fromParams(float[] p) {
        try {
            if (p == null || p.length < 4) return null;
            if (Math.round(p[0]) != Math.round(FORMAT_V1)) return null;
            int interior = Math.round(p[1]);
            int pinN = Math.round(p[2]);
            int ringN = Math.round(p[3]);
            if (pinN < 0 || ringN < 3) return null;
            if (p.length < 4 + pinN * 2 + ringN * 2) return null;
            float[] pins = new float[pinN * 2];
            float[] ring = new float[ringN * 2];
            System.arraycopy(p, 4, pins, 0, pins.length);
            System.arraycopy(p, 4 + pins.length, ring, 0, ring.length);
            return new PuppetTopology(ring, interior, pins);
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

    /** How many vertices came from the contour; the rest are interior. Diagnostics and tests. */
    public int contourCount() { return contourCount; }

    /** The contour, defensively copied. */
    public float[] ring() { return ring.clone(); }
}
