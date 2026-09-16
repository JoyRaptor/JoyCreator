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
        if (identity && !(spec.groupCount() > 1 && spec.hasGroupDepth())) return false;
        if (identity) {
            buffers.resetToRest();
        } else if (!deformer.solve(topo, buffers, pose)) {
            return false;
        }
        if (buffers.groupOrder.length < buffers.groupCount()) {
            buffers.groupOrder = new int[buffers.groupCount()];
        }
        spec.groupOrderAt(timeMs, buffers.groupOrder);
        live = true;
        return true;
    }

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
