package com.fadcam.ui.faditor.puppet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.transform.mesh.MeshBuffers;
import com.fadcam.ui.faditor.transform.mesh.PuppetDeformer;
import com.fadcam.ui.faditor.transform.mesh.PuppetTopology;

/**
 * The triangles, deformed, for the Character scope's <b>Show · Mesh</b> toggle.
 *
 * <h3>Why this class exists at all</h3>
 * <p>{@code rig.showMesh} was written, saved and read by nobody: the overlay drew pins and bones
 * and nothing else. A toggle that changes no pixel is the same defect as the rotate arc that was
 * removed on 2026-09-16 — it teaches the user the feature is broken rather than missing.
 *
 * <h3>Why it deforms rather than drawing the rest pose</h3>
 * <p>Drawing the REST triangles over a bent picture would have been four lines instead of this
 * file, and it would have been a lie: the wireframe is a debug view, and a debug view that
 * disagrees with what it is debugging is worse than no view. So the same {@link PuppetDeformer}
 * the renderer uses solves the same vertices on the CPU, and what you see is where the triangles
 * actually are.
 *
 * <h3>Why that is affordable</h3>
 * <p>It is not free — a solve is O(vertices × pins) — so it is CACHED on the topology stamp and a
 * hash of the pose, and it only runs at all while the toggle is on. The toggle is off by default
 * and the spec is explicit that <i>"the triangles are a debug view, not a workflow"</i>, so the
 * cost is paid by exactly the person who asked for it.
 */
public final class PuppetMeshWire {

    /** Deformed vertex positions, interleaved x,y, in UNIT space (0..1 of the item's box). */
    @Nullable public float[] xy;

    /** Triangle indices into {@link #xy}. */
    @Nullable public short[] idx;

    /** How many entries of {@link #idx} are live. */
    public int indexCount;

    private final MeshBuffers buffers = new MeshBuffers();
    private final PuppetDeformer deformer = new PuppetDeformer();

    private int cachedStamp = Integer.MIN_VALUE;
    private int cachedPoseHash;
    private boolean valid;

    /** True when there is something worth drawing. */
    public boolean has() { return valid && xy != null && idx != null && indexCount >= 3; }

    /**
     * Bring the wireframe up to date.
     *
     * @param topology the rigged mesh, or null when the item has none yet
     * @param pose     the current handle values (dx,dy per pin), or null for the rest pose
     * @return true when {@link #has()} will now be true
     */
    public boolean update(@Nullable PuppetTopology topology, @Nullable float[] pose) {
        if (topology == null || topology.indexCount() < 3) { valid = false; return false; }

        int stamp = topology.topologyId();
        int poseHash = hash(pose);
        if (valid && stamp == cachedStamp && poseHash == cachedPoseHash) return true;

        if (stamp != cachedStamp || buffers.topologyStamp() != stamp) {
            if (!buffers.bind(topology)) { valid = false; return false; }
        }

        // A pose of the wrong arity is not an error worth shouting about — it happens for one
        // frame after a pin is added, before the pose array has been remapped. Fall back to rest
        // rather than refusing to draw, so the wireframe does not blink on every edit.
        float[] use = pose;
        if (use == null || use.length != topology.handleArity()) {
            use = new float[topology.handleArity()];
        }
        if (!deformer.solve(topology, buffers, use)) {
            buffers.resetToRest();
        }

        int n = buffers.vertexCount() * 2;
        if (xy == null || xy.length < n) xy = new float[n];
        System.arraycopy(buffers.positions, 0, xy, 0, Math.min(n, buffers.positions.length));

        indexCount = buffers.indexCount();
        if (idx == null || idx.length < indexCount) idx = new short[indexCount];
        System.arraycopy(buffers.indices, 0, idx, 0, indexCount);

        cachedStamp = stamp;
        cachedPoseHash = poseHash;
        valid = true;
        return true;
    }

    /** Drop the cache — used when the rig is replaced under us. */
    public void invalidate() { valid = false; cachedStamp = Integer.MIN_VALUE; }

    /**
     * A cheap hash of the pose.
     *
     * <p>Quantised to about a thousandth of the item's width: finer than that and a pose that is
     * visually identical still misses the cache every frame, which is the whole cost this is here
     * to avoid.
     */
    private static int hash(@Nullable float[] pose) {
        if (pose == null) return 0;
        int h = pose.length;
        for (float v : pose) h = h * 31 + Math.round(v * 1000f);
        return h;
    }

    @NonNull
    @Override
    public String toString() {
        return "PuppetMeshWire{" + (valid ? indexCount / 3 + " triangles" : "empty") + "}";
    }
}
