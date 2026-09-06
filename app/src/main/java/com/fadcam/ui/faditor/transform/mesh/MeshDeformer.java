package com.fadcam.ui.faditor.transform.mesh;

/**
 * HOW AUTHORED HANDLE VALUES BECOME MOVED VERTICES. The other half of the seam.
 *
 * <p>One method carries the whole contract:</p>
 * <pre>  solve(topology, buffers, handleValues)   // reads buffers.rest, writes buffers.positions</pre>
 *
 * <p>{@link LatticeDeformer} implements it by evaluating a Catmull-Rom tensor product — every
 * vertex moves by an interpolation of the nine or twenty-five nudges around it. A puppet deformer
 * would implement the identical method by running an as-rigid-as-possible or moving-least-squares
 * solve driven by six pins. Neither the buffers, the pose track, the fold guard, the serialiser nor
 * the renderer can tell which one ran, and that is the point: a solver swap must not cost a
 * rewrite of the expensive half.</p>
 *
 * <h3>Threading and allocation</h3>
 * <p>An implementation MAY hold mutable scratch state, so an instance belongs to exactly one
 * thread — a renderer holds one for the life of its GL thread. In exchange, {@link #solve} must not
 * allocate: it is called every frame.</p>
 *
 * <h3>Why a deformer may refuse a topology</h3>
 * <p>A deformer is paired with topologies it understands ({@link #supports}). That pairing is not a
 * leak — it is the ONE place the two concerns are allowed to meet, and it is made at construction
 * (or by {@link MeshTopologies#deformerFor}) rather than baked into the data. Everything downstream
 * of {@link #solve} stays generic.</p>
 *
 * <p>No Android imports.</p>
 */
public interface MeshDeformer {

    /** True when this deformer can drive that topology. */
    boolean supports(MeshTopology topology);

    /**
     * True when these handle values describe no deformation at all.
     *
     * <p>The identity fast path is a guarantee, not an optimisation: an untouched mesh must be
     * detected BEFORE any GL object exists, so a project that has never used the tool creates no
     * framebuffer, compiles no program and costs exactly zero.</p>
     */
    boolean isIdentity(float[] handleValues);

    /**
     * Write the pose that means "no deformation" for this topology into {@code out}.
     *
     * <p>Not always all-zeros, and that is exactly why it is asked of the deformer rather than
     * assumed: the grid stores NUDGES, whose identity is zero, while a pin-based puppet stores pin
     * POSITIONS, whose identity is the pins' rest locations. A spec that assumed zeros would create
     * every new puppet already collapsed onto the origin.</p>
     */
    void identityPose(MeshTopology topology, float[] out);

    /**
     * Clamp one authored component into the range the deformer can render safely. Applied at the
     * edit seam so a stray drag or a corrupt file cannot demand a vertex a thousand widths away.
     */
    float clampComponent(float v);

    /**
     * Deform. Reads {@code buffers.rest}, writes {@code buffers.positions}, allocates nothing.
     *
     * @param handleValues {@code topology.handleArity()} floats, or null for identity
     * @return false when nothing could be solved (unsupported topology, wrong arity); the caller
     *         then draws the rest shape rather than something undefined
     */
    boolean solve(MeshTopology topology, MeshBuffers buffers, float[] handleValues);
}
