package com.fadcam.ui.faditor.transform.mesh;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THE REGISTRY. Maps a wire {@code kind} back to a {@link MeshTopology} and to the
 * {@link MeshDeformer} that drives it.
 *
 * <p>This is the file a puppet lane edits, and it is the ONLY one. Adding puppeteering is:</p>
 * <pre>  register("puppet", ContourTopology::fromParams, PuppetDeformer::new);</pre>
 * <p>after which {@link MeshWarpSpec} serialises puppet specs, {@link MeshPoseTrack} keyframes
 * puppet pins, {@link MeshGuard} refuses folded puppet triangles and {@link MeshEngine} drives the
 * renderer — none of them changed, none of them recompiled for a new idea, because none of them
 * ever learned what a lattice was.</p>
 *
 * <p>An unknown kind resolves to null rather than throwing: a project written by a newer build that
 * has a topology this build has never heard of must open with that one object un-deformed, not
 * fail to open. Same "one bad track does not cost the others" discipline as
 * {@code KeyframeCodec.fromJson}.</p>
 *
 * <p>Registration is static and happens once in this class's initialiser. No Android imports.</p>
 */
public final class MeshTopologies {

    private MeshTopologies() {}

    /** Rebuilds a topology from the floats {@link MeshTopology#params()} wrote. */
    public interface Factory {
        MeshTopology create(float[] params);
    }

    /** Makes a fresh, thread-owned deformer for a kind. */
    public interface DeformerFactory {
        MeshDeformer create();
    }

    private static final Map<String, Factory> TOPOLOGIES = new LinkedHashMap<>();
    private static final Map<String, DeformerFactory> DEFORMERS = new LinkedHashMap<>();

    static {
        register(LatticeTopology.KIND,
                new Factory() {
                    @Override public MeshTopology create(float[] params) {
                        int level = (params == null || params.length < 1)
                                ? LatticeTopology.L2 : (int) params[0];
                        return new LatticeTopology(level);
                    }
                },
                new DeformerFactory() {
                    @Override public MeshDeformer create() { return new LatticeDeformer(); }
                });
    }

    public static void register(String kind, Factory topology, DeformerFactory deformer) {
        if (kind == null || topology == null || deformer == null) return;
        TOPOLOGIES.put(kind, topology);
        DEFORMERS.put(kind, deformer);
    }

    public static boolean isKnown(String kind) {
        return kind != null && TOPOLOGIES.containsKey(kind);
    }

    /** @return null for an unknown kind — the caller then treats the object as un-deformed */
    public static MeshTopology create(String kind, float[] params) {
        Factory f = kind == null ? null : TOPOLOGIES.get(kind);
        return f == null ? null : f.create(params);
    }

    /** A deformer for a topology's kind. One per thread; never share across GL threads. */
    public static MeshDeformer deformerFor(MeshTopology topology) {
        if (topology == null) return null;
        DeformerFactory f = DEFORMERS.get(topology.kind());
        return f == null ? null : f.create();
    }
}
