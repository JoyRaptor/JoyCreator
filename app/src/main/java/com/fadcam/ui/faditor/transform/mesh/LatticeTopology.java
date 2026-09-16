package com.fadcam.ui.faditor.transform.mesh;

/**
 * THE ONE PLACE THAT KNOWS ABOUT ROWS AND COLUMNS. A regular {@code side x side} lattice over the
 * object's unit square, tessellated into a finer regular grid of triangles.
 *
 * <p>Everything typed on {@code level}, {@code side} or {@code gridN} lives in this file and in
 * {@link LatticeDeformer}. Nothing outside those two — not {@link MeshBuffers}, not
 * {@link MeshPoseTrack}, not {@link MeshGuard}, not {@link MeshWarpSpec}'s wire format, and not the
 * renderer — has a word for a row. That containment is exactly what a puppet topology needs in
 * order to slot in without a rewrite, and it is why the earlier attempt at this engine was thrown
 * away: it had spread {@code level} through the core.</p>
 *
 * <h3>Levels</h3>
 * <p>{@code side = (1 << level) + 1}, so level 1/2/3 give 2x2, 3x3, 5x5 control points — four,
 * nine and twenty-five handles. One rule, no lookup table.</p>
 *
 * <h3>Tessellation is decoupled from the lattice, deliberately</h3>
 * <p>The lattice is what a thumb drags; the tessellation is how finely the picture is chopped to
 * hide the maths. 8 / 16 / 24 quads per axis gives 128 / 512 / 1,152 triangles. A mid-range 2020
 * phone GPU processes well over a hundred thousand triangles a frame; every real cost in this
 * feature is fill rate, and fill rate is set by the frame size, not the triangle count. Do not
 * "optimise" these down — that trades the one free thing for visible faceting.</p>
 *
 * <p><b>Deviation from SPEC_20260902 §2.3, stated on purpose.</b> The spec proposed a single quad
 * (gridN = 1) at level 1, on the grounds that a 2x2 lattice "bends nothing a single quad cannot
 * express". That is true of a HOMOGRAPHY and false of this deformer: at 2x2 the Catmull-Rom
 * degenerates to a plain BILINEAR map (see {@link LatticeDeformer}), and a bilinear map is not
 * affine on either triangle of a quad — drawn as two triangles it shows the classic diagonal seam
 * across the picture. 8x8 costs 128 triangles, which is nothing, and removes the artefact.
 * Corner-pin perspective is a separate stage and still gets its exactness from the homography's
 * {@code w}, not from the triangle count.</p>
 *
 * <p>Immutable. No Android imports.</p>
 */
public final class LatticeTopology implements MeshTopology {

    /** Wire name. {@link MeshTopologies} maps it back to this class on read. */
    public static final String KIND = "lattice";

    /** 2x2 — four corners. */
    public static final int L1 = 1;
    /** 3x3 — corners, edge midpoints, centre. The first "+ Finer". */
    public static final int L2 = 2;
    /** 5x5 — the second "+ Finer". */
    public static final int L3 = 3;

    public static final int MIN_LEVEL = L1, MAX_LEVEL = L3;
    /** Largest {@code side} any level produces. Sizes the axis-weight scratch in the deformer. */
    public static final int MAX_SIDE = 5;

    private final int level;
    private final int side;
    private final int gridN;

    public LatticeTopology(int level) {
        this.level = clampLevel(level);
        this.side = sideFor(this.level);
        this.gridN = tessellationFor(this.level);
    }

    // ── Level arithmetic. The only file allowed to do this. ──────────────

    public static int clampLevel(int level) {
        return level < MIN_LEVEL ? MIN_LEVEL : (level > MAX_LEVEL ? MAX_LEVEL : level);
    }

    /** {@code side} for a level: 2, 3, 5. One rule, no lookup table. */
    public static int sideFor(int level) { return (1 << (clampLevel(level) - 1)) + 1; }

    /** The level whose {@code side} is this, or {@link #L1} for anything unrecognised. */
    public static int levelForSide(int side) {
        if (side == 3) return L2;
        if (side == 5) return L3;
        return L1;
    }

    /** Quads per axis in the rendered mesh. See the class note on the level-1 deviation. */
    public static int tessellationFor(int level) {
        switch (clampLevel(level)) {
            case L3: return 24;
            case L2: return 16;
            default: return 8;
        }
    }

    /** Floats in a whole pose at this level: {@code side*side*2}. */
    public static int arityFor(int level) {
        int s = sideFor(level);
        return s * s * 2;
    }

    public int level() { return level; }

    public int side() { return side; }

    /** Quads per axis in this instance's tessellation. */
    public int gridN() { return gridN; }

    /** Flat handle index of lattice point {@code (row, col)}; multiply by 2 to index a pose. */
    public int handleIndex(int row, int col) { return row * side + col; }

    /** The undeformed u of lattice column {@code col}. */
    public static float baseU(int col, int side) { return col / (float) (side - 1); }

    /** The undeformed v of lattice row {@code row}. */
    public static float baseV(int row, int side) { return row / (float) (side - 1); }

    // ── MeshTopology ────────────────────────────────────────────────────

    @Override public String kind() { return KIND; }

    @Override public float[] params() { return new float[]{level}; }

    /** A grid is one piece and cannot overlap itself, so it is always a single draw group. */
    @Override public int groupCount() { return 1; }

    @Override
    public void groupIndexStart(int[] out) {
        if (out == null || out.length < 2) return;
        out[0] = 0;
        out[1] = indexCount();
    }

    @Override public int topologyId() { return 0x1A77_0000 | level; }

    @Override public int vertexCount() { return (gridN + 1) * (gridN + 1); }

    @Override public int indexCount() { return gridN * gridN * 6; }

    @Override public int handleCount() { return side * side; }

    @Override public int handleComponents() { return 2; }

    @Override public int handleArity() { return side * side * 2; }

    @Override public float handleRestX(int i) { return baseU(i % side, side); }

    @Override public float handleRestY(int i) { return baseV(i / side, side); }

    @Override
    public void buildRest(float[] outRest, float[] outUv, short[] outIndices) {
        int stride = gridN + 1;
        float inv = 1f / gridN;
        for (int r = 0; r < stride; r++) {
            float v = r * inv;
            int rowBase = r * stride * 2;
            for (int c = 0; c < stride; c++) {
                float u = c * inv;
                int k = rowBase + c * 2;
                outRest[k] = u;
                outRest[k + 1] = v;
                if (outUv != null) {
                    outUv[k] = u;
                    outUv[k + 1] = v;
                }
            }
        }
        if (outIndices == null) return;
        int w = 0;
        for (int r = 0; r < gridN; r++) {
            for (int c = 0; c < gridN; c++) {
                int k = r * stride + c;
                outIndices[w++] = (short) k;
                outIndices[w++] = (short) (k + stride);
                outIndices[w++] = (short) (k + 1);
                outIndices[w++] = (short) (k + 1);
                outIndices[w++] = (short) (k + stride);
                outIndices[w++] = (short) (k + stride + 1);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LatticeTopology && ((LatticeTopology) o).level == level;
    }

    @Override public int hashCode() { return topologyId(); }

    @Override public String toString() { return "LatticeTopology(L" + level + ", " + side + "x" + side + ")"; }
}
