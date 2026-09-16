package com.fadcam.ui.faditor.transform.mesh;

/**
 * THE NEUTRAL OUTPUT. Everything a renderer needs to draw a deformed picture, and NOTHING that
 * says where the deformation came from.
 *
 * <p>There is no {@code side}, no {@code row}, no {@code col}, no {@code level} anywhere in this
 * class, and that absence is the single most important property of the whole engine. A regular
 * lattice fills these arrays today; a triangulated alpha contour with six pins will fill exactly
 * the same arrays later. The GL side is written once, against this, and never learns the
 * difference — which matters because the renderer, the export path and the preview/export parity
 * story are the expensive half of both features.</p>
 *
 * <h3>What is in here</h3>
 * <ul>
 *   <li>{@link #positions} — DEFORMED vertex positions in the object's own unit space,
 *       {@code x,y} interleaved. This is the only array that changes per frame.</li>
 *   <li>{@link #rest} — the UNDEFORMED position of the same vertices. The deformer reads it; the
 *       fold guard measures against it. Changes only when the topology changes.</li>
 *   <li>{@link #uvs} — source texture coordinates. Equal to {@link #rest} for both the lattice and
 *       a puppet cut from its own image, but kept separate so a topology that packs into an atlas
 *       is expressible without touching a renderer.</li>
 *   <li>{@link #indices} — triangle list, {@code GL_TRIANGLES}. Irregular topologies are the
 *       reason this is an explicit index list and not an implied grid stride.</li>
 * </ul>
 *
 * <h3>Allocation</h3>
 * <p>{@link #bind} allocates only when the topology's identity changes — once, when the object is
 * first drawn, and again only if the user presses "+ Finer". Every other frame it does nothing.
 * A per-frame allocation on the GL thread is how a preview grows a stutter that only appears on a
 * hot phone, so this is pinned by a harness test rather than merely intended.</p>
 *
 * <p><b>Back-face culling must be OFF.</b> Winding is consistent for an unfolded mesh, but a
 * deliberate fold reverses it, and a culled fold vanishes rather than showing its back.</p>
 *
 * <p>No Android imports anywhere in this package.</p>
 */
public final class MeshBuffers {

    /** Deformed unit-space positions, {@code x,y} interleaved. Uploaded every frame. */
    public float[] positions = new float[0];
    /** Undeformed unit-space positions, {@code x,y} interleaved. Uploaded never (CPU only). */
    public float[] rest = new float[0];
    /** Source texture coordinates, {@code u,v} interleaved. Uploaded on topology change. */
    public float[] uvs = new float[0];
    /** Triangle indices. Uploaded on topology change. */
    public short[] indices = new short[0];

    /**
     * Where each DRAW GROUP's triangles start, plus a final total. Always at least
     * {@code {0, indexCount}} — a one-group mesh, which is what a lattice always is.
     *
     * <p>Here rather than asked of the topology at draw time because the renderer holds buffers,
     * not topologies, and because it must never need to know which KIND of topology filled them.
     */
    public int[] groupIndexStart = new int[]{0, 0};

    /**
     * Which order to composite the groups in, BACK TO FRONT. Group {@code groupOrder[0]} is drawn
     * first and therefore sits furthest behind.
     *
     * <p>Identity until something says otherwise, so a mesh nobody ordered draws in the order its
     * pieces were traced — which for a puppet is largest first, a sane default for a character
     * whose body is its biggest piece.
     */
    public int[] groupOrder = new int[]{0};

    private int vertexCount;
    private int indexCount;
    private int topologyId = Integer.MIN_VALUE;
    private int topologyStamp;

    /** Vertices actually in use — the arrays may be longer after a shrink. */
    public int vertexCount() { return vertexCount; }

    /** Indices actually in use. Pass this to {@code glDrawElements}. */
    public int indexCount() { return indexCount; }

    /** How many groups {@link #groupIndexStart} describes. */
    public int groupCount() { return Math.max(1, groupCount); }

    /**
     * Bumped whenever {@link #rest}, {@link #uvs} or {@link #indices} were rebuilt. A renderer
     * re-uploads those three only when this changes, and {@link #positions} every frame.
     */
    public int topologyStamp() { return topologyStamp; }

    private int groupCount = 1;

    /** True once a topology has been bound and there is geometry to draw. */
    public boolean hasGeometry() { return indexCount > 0; }

    /**
     * Point these buffers at a topology, rebuilding the static arrays only if it actually changed.
     *
     * <p>The no-op path is the steady state and is the reason nothing allocates per frame: a
     * topology reports the same {@link MeshTopology#topologyId()} for as long as its structure is
     * unchanged, and moving a handle does not change the structure.</p>
     *
     * @return true when the static arrays were rebuilt (so a renderer knows to re-upload)
     */
    public boolean bind(MeshTopology topology) {
        if (topology == null) {
            vertexCount = 0;
            indexCount = 0;
            topologyId = Integer.MIN_VALUE;
            return false;
        }
        if (topology.topologyId() == topologyId && vertexCount == topology.vertexCount()) {
            return false;
        }
        topologyId = topology.topologyId();
        vertexCount = topology.vertexCount();
        indexCount = topology.indexCount();
        int coords = vertexCount * 2;
        if (positions.length < coords) positions = new float[coords];
        if (rest.length < coords) rest = new float[coords];
        if (uvs.length < coords) uvs = new float[coords];
        if (indices.length < indexCount) indices = new short[indexCount];
        int groups = Math.max(1, topology.groupCount());
        if (groupIndexStart.length < groups + 1) groupIndexStart = new int[groups + 1];
        topology.groupIndexStart(groupIndexStart);
        // A topology that declined to fill it (a short array, an older implementation) still gets
        // a valid single group rather than a mesh that draws nothing.
        if (groupIndexStart[groups] <= 0) {
            groupIndexStart[0] = 0;
            groupIndexStart[groups] = indexCount;
        }
        groupCount = groups;
        if (groupOrder.length < groups) groupOrder = new int[groups];
        for (int i = 0; i < groups; i++) groupOrder[i] = i;
        topology.buildRest(rest, uvs, indices);
        System.arraycopy(rest, 0, positions, 0, coords);
        topologyStamp++;
        return true;
    }

    /** Reset {@link #positions} to the undeformed shape — the "no bend" draw. */
    public void resetToRest() {
        System.arraycopy(rest, 0, positions, 0, vertexCount * 2);
    }
}
