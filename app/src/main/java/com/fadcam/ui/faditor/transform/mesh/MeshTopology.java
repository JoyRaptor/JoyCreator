package com.fadcam.ui.faditor.transform.mesh;

/**
 * WHAT SHAPE THE PICTURE IS CUT INTO, and WHERE THE AUTHORED HANDLES SIT. Half of the seam that
 * lets one renderer serve both the grid warp and, later, puppeteering.
 *
 * <p>A topology answers three questions and no others:</p>
 * <ol>
 *   <li>What triangles is the picture made of, at rest? ({@link #buildRest})</li>
 *   <li>How many things does the user author, and where do they sit at rest?
 *       ({@link #handleCount}, {@link #handleRestX}, {@link #handleRestY})</li>
 *   <li>How do I write myself down? ({@link #kind}, {@link #params})</li>
 * </ol>
 *
 * <p>It does <b>not</b> know how a handle value turns into a moved vertex. That is the
 * {@link MeshDeformer}'s job, and keeping the two apart is the whole architectural point:</p>
 *
 * <table border="1">
 *   <caption>the two features this interface exists to serve</caption>
 *   <tr><th></th><th>grid warp (built)</th><th>puppet (later)</th></tr>
 *   <tr><td>topology</td><td>{@link LatticeTopology} — regular MxN</td>
 *       <td>triangulated alpha contour</td></tr>
 *   <tr><td>deformer</td><td>{@link LatticeDeformer} — direct per-vertex offsets</td>
 *       <td>sparse pins + ARAP/MLS solver</td></tr>
 *   <tr><td>handles</td><td>9 or 25 lattice points</td><td>6-8 pins</td></tr>
 *   <tr><td>output</td><td colspan="2">{@link MeshBuffers} — identical</td></tr>
 * </table>
 *
 * <p><b>Handles are numbered 0..handleCount-1 and that is all the pose track knows.</b>
 * {@link MeshPoseTrack} stores "an array of {@link #handleArity()} floats"; it has never heard of a
 * row, a column or a level, so a puppet's pin poses need no new pose code at all.</p>
 *
 * <p>Implementations are immutable value objects. Changing the structure means constructing a new
 * one, which is what makes {@link #topologyId()} a safe change-detection stamp.</p>
 *
 * <p>No Android imports. This whole package runs on the desktop JVM harness, which is what makes
 * the maths provable off device and what makes it liftable into another app whole.</p>
 */
public interface MeshTopology {

    /** Stable wire name, e.g. {@code "lattice"}. Used by {@link MeshTopologies} on read. */
    String kind();

    /**
     * The numbers that, with {@link #kind()}, rebuild this topology exactly.
     *
     * <p>Floats rather than ints because a puppet's parameters ARE its traced contour. A lattice's
     * is a single element, {@code {level}}. Returned defensively — callers may keep it.</p>
     */
    float[] params();

    /**
     * A value that changes if and only if the STRUCTURE changed. Moving a handle must not change
     * it — that is what keeps {@link MeshBuffers#bind} allocation-free in the steady state.
     */
    int topologyId();

    int vertexCount();

    /** Number of indices, i.e. {@code triangles * 3}. */
    int indexCount();

    /** How many things the user authors. 9 or 25 for a lattice; 6-8 pins for a puppet. */
    int handleCount();

    /** Floats per authored handle. Two ({@code dx, dy}) for everything built so far. */
    int handleComponents();

    /** Floats in one whole pose: {@code handleCount() * handleComponents()}. */
    int handleArity();

    /**
     * Fill the static, per-topology arrays. Called only when {@link #topologyId()} changes.
     *
     * @param outRest    at least {@code vertexCount()*2} floats — undeformed unit-space x,y
     * @param outUv      at least {@code vertexCount()*2} floats — source texture u,v
     * @param outIndices at least {@code indexCount()} shorts
     */
    void buildRest(float[] outRest, float[] outUv, short[] outIndices);

    /** Undeformed unit-space x of authored handle {@code i}. Where its dot is drawn before a drag. */
    float handleRestX(int i);

    /** Undeformed unit-space y of authored handle {@code i}. */
    float handleRestY(int i);
}
