package com.fadcam.ui.faditor.transform.mesh;

/**
 * WHAT A RENDERER HOLDS. One instance per GL thread, {@link #update} once per frame, upload,
 * draw. Nothing here allocates once it is warm.
 *
 * <h3>The whole renderer contract</h3>
 * <ol>
 *   <li>{@code MeshEngine engine = new MeshEngine();} once, on the GL thread.</li>
 *   <li>{@code if (!engine.update(spec, timeMs)) drawTheOrdinaryUnbentWay();} — a false return is
 *       the identity fast path: null spec, unknown topology, or a pose that deforms nothing.</li>
 *   <li>Upload {@code buffers().positions} every frame; upload {@code buffers().uvs} and
 *       {@code buffers().indices} only when {@code buffers().topologyStamp()} changed.</li>
 *   <li>{@code glDrawElements(GL_TRIANGLES, buffers().indexCount(), GL_UNSIGNED_SHORT, 0)} with
 *       <b>back-face culling OFF</b> — a deliberate fold reverses winding and a culled fold
 *       vanishes instead of showing its back.</li>
 * </ol>
 *
 * <p>{@code positions} are in the object's UNIT space, deformed. The vertex shader pushes them
 * through the corner-pin homography and then the placement matrix, in that order, and must
 * <b>not divide by w itself</b> — handing the rasteriser a real {@code w} is what makes UVs
 * interpolate with correct perspective weighting instead of showing a diagonal seam across every
 * quad. That is the single easiest thing to get wrong here and it is invisible until someone pins
 * a corner hard.</p>
 *
 * <h3>Why the per-frame allocation rule is a rule</h3>
 * <p>A {@code new float[]} on the GL thread is a stutter that only appears once the phone is hot
 * and the collector is busy — the hardest class of bug to reproduce and the easiest to introduce.
 * After the first {@link #update} at a given topology, this class performs no allocation at all:
 * the buffers are re-bound only on a topology change, the pose scratch grows only on an arity
 * change, and the deformer is built once per kind. Pinned by a harness test that measures the JVM's
 * allocated-bytes counter across ten thousand frames, not by intention.</p>
 *
 * <p>No Android imports.</p>
 */
public final class MeshEngine {

    private final MeshBuffers buffers = new MeshBuffers();
    private MeshDeformer deformer;
    private String deformerKind;
    private float[] pose = new float[0];
    private boolean live;

    /** The neutral output. Valid only after {@link #update} returned true. */
    public MeshBuffers buffers() { return buffers; }

    /** The deformer currently driving this engine, or null before the first {@link #update}. */
    public MeshDeformer deformer() { return deformer; }

    /** True when the last {@link #update} produced geometry the renderer should draw. */
    public boolean isLive() { return live; }

    /**
     * Refill from a spec at a timestamp.
     *
     * @return false when there is nothing to draw — a null spec, a topology this build does not
     *         know, or an identity pose. The renderer then takes the ordinary unbent path, which is
     *         the whole "a project with no deformation costs exactly zero" guarantee.
     */
    public boolean update(MeshWarpSpec spec, long timeMs) {
        live = false;
        if (spec == null || !spec.hasWarp()) return false;
        MeshTopology topo = spec.topology();
        if (topo == null) return false;
        if (deformer == null || !topo.kind().equals(deformerKind) || !deformer.supports(topo)) {
            deformer = MeshTopologies.deformerFor(topo);
            deformerKind = topo.kind();
            if (deformer == null) return false;
        }
        buffers.bind(topo);
        int arity = topo.handleArity();
        if (pose.length != arity) pose = new float[arity];
        if (!spec.handlesAt(timeMs, pose)) return false;
        boolean identity = deformer.isIdentity(pose);
        // AN UNBENT PUPPET STILL NEEDS THIS PATH when its pieces are ordered. Bailing on an
        // identity pose is what keeps "a project with no deformation costs exactly zero" true, and
        // that stays true for everything with one draw group. But a character whose arm has been
        // put behind its body is a different picture from the flat texture even at rest, and the
        // only place that reordering happens is here.
        if (identity && !spec.hasGroupDepth()) return false;
        if (identity) {
            buffers.resetToRest();
        } else if (!deformer.solve(topo, buffers, pose)) {
            return false;
        }
        if (buffers.groupOrder.length < buffers.groupCount()) {
            buffers.groupOrder = new int[buffers.groupCount()];
        }
        spec.groupOrderAt(timeMs, buffers.groupOrder);
        orderTriangles(spec, topo, timeMs);
        live = true;
        return true;
    }

    /**
     * Sort the triangles back to front when the picture has a depth, and leave them alone when it
     * does not — which is every project that existed before depth did.
     *
     * <p>Re-sorted only when the DEPTHS change. The field comes from the weights and the authored
     * numbers, never from the pose, so bending a character does not re-order it: a z-order that
     * shifted as a limb moved would pop the character inside out mid-gesture for no reason the
     * animator could see.
     */
    private void orderTriangles(MeshWarpSpec spec, MeshTopology topo, long timeMs) {
        int tris = buffers.indexCount() / 3;
        if (tris <= 0) return;
        if (!spec.hasGroupDepth()) {
            if (depthStamp != 0) {                     // depth was removed: back to built order
                System.arraycopy(buffers.indices, 0, buffers.drawIndices, 0,
                        buffers.indexCount());
                buffers.drawOrderStamp++;
                depthStamp = 0;
            }
            return;
        }
        int handles = topo.handleCount();
        int groups = Math.max(1, topo.groupCount());
        if (handleZ.length != handles) handleZ = new float[handles];
        if (groupZ.length != groups) groupZ = new float[groups];
        spec.handleZAt(timeMs, handleZ);
        spec.groupZAt(timeMs, groupZ);

        int stamp = MeshDepth.stampOf(handleZ, groupZ) * 31 + buffers.topologyStamp();
        if (stamp == depthStamp) return;

        int verts = buffers.vertexCount();
        if (vertexDepth.length < verts) vertexDepth = new float[verts];
        if (triOrder.length < tris) {
            triOrder = new int[tris];
            triScratch = new int[tris];
            triKeys = new float[tris];
        }
        if (!topo.vertexField(handleZ, groupZ, vertexDepth)) return;
        int n = MeshDepth.sortTriangles(buffers.indices, buffers.indexCount(), vertexDepth,
                triOrder, triScratch, triKeys);
        if (n <= 0) return;
        if (buffers.drawIndices.length < buffers.indexCount()) {
            buffers.drawIndices = new short[buffers.indexCount()];
        }
        if (MeshDepth.applyOrder(buffers.indices, triOrder, n, buffers.drawIndices)) {
            buffers.drawOrderStamp++;
            depthStamp = stamp;
        }
    }

    private int depthStamp = 0;
    private float[] handleZ = new float[0];
    private float[] groupZ = new float[0];
    private float[] vertexDepth = new float[0];
    private int[] triOrder = new int[0];
    private int[] triScratch = new int[0];
    private float[] triKeys = new float[0];

    /**
     * Drive the engine from a bare pose, bypassing the spec — what the handle overlay uses while a
     * drag is in flight and what the harness uses. Allocation-free on repeat calls.
     */
    public boolean update(MeshTopology topo, float[] handleValues) {
        live = false;
        if (topo == null) return false;
        if (deformer == null || !topo.kind().equals(deformerKind) || !deformer.supports(topo)) {
            deformer = MeshTopologies.deformerFor(topo);
            deformerKind = topo.kind();
            if (deformer == null) return false;
        }
        buffers.bind(topo);
        if (!deformer.solve(topo, buffers, handleValues)) return false;
        live = true;
        return true;
    }
}
