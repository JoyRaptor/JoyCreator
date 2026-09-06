package com.fadcam.ui.faditor.transform.mesh;

/**
 * THE PER-FRAME BUFFER. Owns the vertex, UV and index arrays a GL renderer uploads, and refills
 * them from a lattice without allocating.
 *
 * <h3>Why this exists rather than "just call MeshWarp.tessellate"</h3>
 * <p>{@link MeshWarp#tessellate} takes caller arrays on purpose, so it can be driven from a
 * renderer that already owns its buffers. This class is what a renderer that does NOT should hold:
 * one instance, created once, {@link #update} called every frame. After the first
 * {@link #resize} nothing here allocates — no {@code new float[]}, no autoboxing, no iterator.
 * A per-frame allocation on the GL thread is how a preview develops a stutter that only shows up
 * on a hot phone, so the no-allocation property is pinned by a harness test, not just intended.</p>
 *
 * <h3>What a renderer calls, in order</h3>
 * <ol>
 *   <li>{@code new MeshTessellator()} once, on the GL thread.</li>
 *   <li>{@link #update}(spec, timeMs) per frame. It re-sizes only when the level changed, and it
 *       re-fills {@link #uvs} and {@link #indices} only when the tessellation grid changed — so the
 *       steady-state per-frame work is one refill of {@link #positions}, about 20,000 flops at 5x5,
 *       well under 0.1 ms on any phone of the last decade.</li>
 *   <li>Upload {@link #positions} (per frame), {@link #uvs} and {@link #indices} (only when
 *       {@link #topologyStamp()} changes), then draw {@link #indexCount()} indices as
 *       {@code GL_TRIANGLES} with back-face culling OFF.</li>
 * </ol>
 *
 * <p>{@link #positions} are in the object's UNIT SPACE, deformed. The renderer's vertex shader
 * pushes them through the corner-pin homography and then the placement matrix, in that order, and
 * must NOT divide by w itself — handing the rasteriser a real w is what makes UVs interpolate with
 * correct perspective weighting instead of showing a diagonal seam across every quad.</p>
 *
 * <p>No Android imports. This is the single vertex authority both renderers share; two renderers
 * building their own geometry is precisely how a preview starts lying about an export.</p>
 */
public final class MeshTessellator {

    /** Deformed unit-space vertex positions, {@code x,y} interleaved, row-major. */
    public float[] positions = new float[0];
    /** Undeformed source texture coordinates, {@code u,v} interleaved. Changes only with grid. */
    public float[] uvs = new float[0];
    /** Triangle indices, two triangles per quad. Changes only with grid. */
    public short[] indices = new short[0];

    private final MeshWarp.Scratch scratch = new MeshWarp.Scratch();
    /** Evaluated lattice for the current time — reused, never reallocated per frame. */
    private float[] animated = new float[0];

    private int gridN = -1;
    private int level = -1;
    private int topologyStamp;

    public int gridN() { return gridN; }

    public int level() { return level; }

    public int vertexCount() { return gridN < 0 ? 0 : MeshWarp.vertexCount(gridN); }

    public int indexCount() { return gridN < 0 ? 0 : MeshWarp.indexCount(gridN); }

    /**
     * Bumped whenever {@link #uvs} or {@link #indices} were rebuilt. A renderer re-uploads those two
     * buffers only when this changes; {@link #positions} it uploads every frame.
     */
    public int topologyStamp() { return topologyStamp; }

    /** The shared scratch, so a caller can run {@link MeshWarp#isValid} without its own. */
    public MeshWarp.Scratch scratch() { return scratch; }

    /**
     * Refill from a spec at a timestamp.
     *
     * @return false when there is nothing to draw — a null spec or an identity lattice. The
     *         renderer then takes the ordinary unbent path, which is the whole "a project with no
     *         warp costs exactly zero" guarantee.
     */
    public boolean update(MeshWarpSpec spec, long timeMs) {
        if (spec == null || !spec.hasWarp()) return false;
        int lvl = spec.level();
        resize(lvl);
        if (animated.length != spec.arity()) animated = new float[spec.arity()];
        float[] off = spec.offsetsAt(timeMs, animated);
        MeshWarp.tessellate(off, MeshWarp.sideFor(lvl), gridN, positions, uvs, scratch);
        return true;
    }

    /** Refill from a bare lattice — what the harness and the handle overlay use. */
    public void update(float[] off, int side) {
        resize(MeshWarp.levelForSide(side));
        MeshWarp.tessellate(off, side, gridN, positions, uvs, scratch);
    }

    /**
     * Grow the buffers for a level. A no-op — and therefore allocation-free — when the level has
     * not changed, which is every frame but the first.
     */
    public void resize(int newLevel) {
        int lvl = MeshWarp.clampLevel(newLevel);
        if (lvl == level) return;
        int n = MeshWarp.tessellationFor(lvl);
        level = lvl;
        if (n != gridN) {
            gridN = n;
            positions = new float[MeshWarp.coordCount(n)];
            uvs = new float[MeshWarp.coordCount(n)];
            indices = new short[MeshWarp.indexCount(n)];
            MeshWarp.buildIndices(n, indices);
            topologyStamp++;
        }
    }
}
