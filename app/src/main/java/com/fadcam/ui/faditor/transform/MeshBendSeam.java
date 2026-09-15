package com.fadcam.ui.faditor.transform;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.transform.mesh.LatticeTopology;
import com.fadcam.ui.faditor.transform.mesh.MeshBuffers;
import com.fadcam.ui.faditor.transform.mesh.MeshDeformer;
import com.fadcam.ui.faditor.transform.mesh.MeshGuard;
import com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack;
import com.fadcam.ui.faditor.transform.mesh.MeshProjection;
import com.fadcam.ui.faditor.transform.mesh.MeshTopologies;
import com.fadcam.ui.faditor.transform.mesh.MeshTopology;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;

/**
 * THE bend-editing seam — one copy, shared by every host whose object can be warped.
 *
 * <p>This was ~285 lines inside {@code CornerPinTransformHost}, correct while images were the only
 * thing that could bend. SPEC Z gives a mesh to sprites (and later PiP, text and the spine), and
 * the alternative to moving it was a second copy in {@code AffineTransformHost} — which is exactly
 * the "hard coded per situation" that SPEC Y exists to end. JoyRaptor, 2026-09-13: <i>"if I need to
 * change something about the transform tool, that'll happen to all of them uniformly."</i>
 *
 * <p>What was type-specific turned out to be six mesh accessors, and they are the whole of
 * {@link Owner}. Everything else — the guard, the refusal diary, the arity discipline, the
 * armed/unarmed commit — is object-agnostic and lives here once.
 *
 * <p>The net's dots live in the picture's OWN unit space and are re-projected through the CURRENT
 * quad every frame ({@link MeshProjection}), so a structural edit carries the bend instead of
 * wiping it. One finger drags one dot; the guard refuses folds; commit writes ONCE.
 *
 * <p><b>Undo.</b> The host snapshots in its own {@code beginGesture} (the snapshot already
 * deep-copies the mesh) and records ONE step through the same commit every other gesture uses.
 * Unarmed, the pose lives in the static handles — no track, no drawer diamond for a static bend.
 * Armed, it is put ONCE to the pose track at the local clock. Either way one drag is one undo press.
 *
 * <p>All scratch here is UI-thread only; the GL threads keep their own engine and scratch inside
 * {@code MeshStampGl}. Nothing here is ever shared across threads.
 */
public final class MeshBendSeam {

    /**
     * Everything this seam needs from the object it is bending, and the whole list of it.
     *
     * <p>Six methods, all of which {@code TextOverlayItem}, {@code SpriteOverlayItem} and (once
     * SPEC ZB lands) {@code Clip} already expose under these exact names — which is why the model
     * work insisted on mirroring the names rather than inventing per-type ones.
     */
    public interface Owner {
        @Nullable MeshWarpSpec getMesh();

        void setMesh(@Nullable MeshWarpSpec m);

        void installMeshCurve();

        /** The object's own local clock — the base every animated property here is read on. */
        long meshLocalTime(long timelineMs);

        boolean hasMesh();

        /** Armed = the gesture writes a keyframe; unarmed = it writes the static pose. */
        boolean isArmed();
    }

    /** Largest pose any registered topology may ask for. A sanity bound, not a lattice fact. */
    private static final int MAX_ARITY = 4096;

    /**
     * The net the tool WOULD edit before the first drag: the approved 3x3. Reporting the default
     * keeps the rest grid drawable and grabbable with no model change — opening the tool writes
     * nothing, because {@code MeshWarpSpec.toJson} returns null for an identity pose.
     */
    private static final LatticeTopology DEFAULT_TOPO = new LatticeTopology(LatticeTopology.L2);

    @NonNull private final Owner owner;
    @NonNull private final CornerPinTransformHost.Playhead playhead;
    @NonNull private final Runnable onChanged;

    /** UI-thread guard scratch. Bound to the spec's topology before every check. */
    @NonNull private final MeshBuffers scratch = new MeshBuffers();
    /** UI-thread deformer, built lazily per kind (never shared with the GL threads). */
    @Nullable private MeshDeformer deformer;

    /**
     * UI-thread pose working space, sized EXACTLY to the topology's arity. Never shared.
     *
     * <p>SPEC O — these used to be fixed {@code float[50]} buffers (the largest lattice's arity),
     * which silently disabled the whole tool: {@link MeshDeformer#solve} takes a pose whose length
     * IS the arity, so a 50-float candidate handed to a 3x3 (18-float) lattice made the solve
     * return false, the guard refuse, and every drag frame do nothing at all. Sized on the arity,
     * so it allocates once per topology and never per frame.
     */
    @NonNull private float[] pose = new float[0];
    /** Candidate pose, guard-checked before it reaches the live handles. */
    @NonNull private float[] cand = new float[0];
    @NonNull private final float[] out2 = new float[2];

    /** The refusal already reported for the gesture in flight — say WHY once, not once per frame. */
    @Nullable private String said;
    /** Whether this gesture already recorded its one "bend applied" line. */
    private boolean appliedSaid;

    public MeshBendSeam(@NonNull Owner owner,
                        @NonNull CornerPinTransformHost.Playhead playhead,
                        @NonNull Runnable onChanged) {
        this.owner = owner;
        this.playhead = playhead;
        this.onChanged = onChanged;
    }

    private long now() { return playhead.timelineMs(); }

    /** Grow/shrink a pose buffer to exactly {@code arity}. Allocation-free when it already is. */
    @NonNull
    private static float[] fit(@NonNull float[] a, int arity) {
        return a.length == arity ? a : new float[arity];
    }

    /**
     * SPEC O — no silent refusal survives in the bend path. Records the reason ONCE per gesture
     * (the same frame-by-frame reason is not news) and returns false so callers stay one-liners.
     */
    private boolean refuse(@NonNull String why) {
        if (!why.equals(said)) {
            said = why;
            TransformDiag.log("bend refused: " + why);
        }
        return false;
    }

    public boolean hasBend() { return owner.hasMesh(); }

    @NonNull
    private MeshTopology topoOrDefault() {
        MeshWarpSpec s = owner.getMesh();
        return (s == null || s.topology() == null) ? DEFAULT_TOPO : s.topology();
    }

    public int handleCount() {
        try {
            return Math.max(0, topoOrDefault().handleCount());
        } catch (Exception ignored) {
            return 0;
        }
    }

    public int gridSide() {
        try {
            MeshTopology t = topoOrDefault();
            if (t instanceof LatticeTopology) return ((LatticeTopology) t).side();
        } catch (Exception ignored) { }
        return 0;
    }

    /**
     * Ensure a bend spec exists (the approved 3x3 lattice), creating it when the tool is opened on
     * an unbent picture. Creating HERE rather than on selection keeps opening the tool
     * byte-identical: an identity pose serialises to nothing.
     */
    @Nullable
    private MeshWarpSpec ensureSpec() {
        try {
            MeshWarpSpec s = owner.getMesh();
            if (s == null || s.topology() == null) {
                owner.setMesh(MeshWarpSpec.lattice(LatticeTopology.L2));
            }
            owner.installMeshCurve();
            return owner.getMesh();
        } catch (Exception e) {
            TransformDiag.log("bend ensureSpec threw " + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The pose to draw/drag: the track value at the playhead when present, else static. Writes
     * directly into {@code out} (caller-owned, at least arity long) — no allocation.
     */
    private int currentPose(@NonNull MeshWarpSpec s, @NonNull float[] out) {
        try {
            int arity = s.arity();
            if (arity <= 0 || out.length < arity) return 0;
            if (s.handlesAt(owner.meshLocalTime(now()), out)) return arity;
            float[] h = s.handles();
            if (h != null && h.length >= arity) {
                System.arraycopy(h, 0, out, 0, arity);
                return arity;
            }
        } catch (Exception ignored) { }
        return 0;
    }

    @Nullable
    private MeshDeformer deformerFor(@NonNull MeshTopology topo) {
        MeshDeformer d = deformer;
        if (d == null || !d.supports(topo)) {
            d = MeshTopologies.deformerFor(topo);
            if (d != null) deformer = d;
        }
        return deformer;
    }

    /** Where net dot {@code i} sits on screen, through the current quad's homography {@code h}. */
    public boolean handlePosition(int i, @NonNull float[] h, @NonNull float[] dst2) {
        try {
            MeshWarpSpec s = owner.getMesh();
            MeshTopology topo = topoOrDefault();
            if (i < 0 || i >= topo.handleCount()) return false;
            int arity = topo.handleArity();
            if (arity <= 0 || arity > MAX_ARITY) return false;
            pose = fit(pose, arity);
            if (s != null && currentPose(s, pose) == arity) {
                return MeshProjection.projectHandle(topo, pose, i, h, dst2);
            }
            // No spec yet (tool opened, nothing dragged): rest grid, zero nudges.
            java.util.Arrays.fill(pose, 0, arity, 0f);
            return MeshProjection.projectHandle(topo, pose, i, h, dst2);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Drag net dot {@code i} to a stage point, through the inverse homography {@code hInv}. */
    public boolean dragTo(int i, @NonNull float[] hInv, float stageX, float stageY) {
        try {
            if (!Float.isFinite(stageX) || !Float.isFinite(stageY)) {
                return refuse("stage point not finite");
            }
            MeshWarpSpec s = ensureSpec();
            if (s == null || s.topology() == null) return refuse("no spec/topology");
            MeshTopology topo = s.topology();
            if (i < 0 || i >= topo.handleCount()) {
                return refuse("handle " + i + " outside 0.." + (topo.handleCount() - 1));
            }
            MeshDeformer d = deformerFor(topo);
            if (d == null) return refuse("no deformer for kind " + topo.kind());
            if (!d.supports(topo)) return refuse("deformer rejects kind " + topo.kind());
            int arity = s.arity();
            if (arity <= 0 || arity > MAX_ARITY) return refuse("arity " + arity);
            // EXACTLY the arity, never merely big enough — see the pose field note; a longer pose
            // is what made this whole tool a no-op once already.
            pose = fit(pose, arity);
            cand = fit(cand, arity);
            if (currentPose(s, pose) != arity) {
                java.util.Arrays.fill(pose, 0, arity, 0f);
            }
            if (!MeshProjection.dragToHandle(topo, d, i, hInv, stageX, stageY, out2)) {
                return refuse("degenerate inverse homography");
            }
            System.arraycopy(pose, 0, cand, 0, arity);
            cand[i * 2] = out2[0];
            cand[i * 2 + 1] = out2[1];
            // The guard measures the SOLVED triangles, not the handles: bind first, or the scratch
            // has no rest shape and every fold would pass. Refusal simply stops the drag — always
            // recoverable, unlike rendering through a fold.
            scratch.bind(topo);
            if (!MeshGuard.accepts(topo, d, scratch, cand)) {
                return refuse("guard: fold/crush at handle " + i);
            }
            float[] live = s.handles();
            if (live == null || live.length != arity) {
                return refuse("live pose " + (live == null ? "null" : live.length)
                        + " != arity " + arity);
            }
            System.arraycopy(cand, 0, live, 0, arity);
            if (!appliedSaid) {
                // One line per gesture, on the FIRST frame that actually deformed anything.
                appliedSaid = true;
                TransformDiag.log("bend applied i=" + i + " du=" + out2[0]
                        + " dv=" + out2[1] + " arity=" + arity + " kind=" + topo.kind());
            }
            onChanged.run();
            return true;
        } catch (Exception e) {
            return refuse("threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Call from the host's {@code beginBendGesture}, BEFORE it snapshots. */
    public void begin() {
        // Ensure BEFORE the snapshot: the first bend's undo then restores "no bend at all".
        said = null;
        appliedSaid = false;
        ensureSpec();
    }

    /** Call from the host's {@code commitBendGesture}, BEFORE it commits the gesture. */
    public void commit() {
        try {
            // Armed: the pose at the playhead becomes ONE track key (local clock, like every other
            // animated property). Unarmed: the static handles already hold it — no track, no
            // drawer diamond for a static bend. Either way exactly one write, one undo.
            if (owner.isArmed()) {
                MeshWarpSpec s = owner.getMesh();
                if (s != null && s.topology() != null) {
                    int arity = s.arity();
                    float[] live = s.handles();
                    if (arity > 0 && live != null && live.length == arity) {
                        s.ensureTrack().put(owner.meshLocalTime(now()), live,
                                MeshPoseTrack.DEFAULT_EASING);
                        owner.installMeshCurve();
                    }
                }
            }
        } catch (Exception e) {
            TransformDiag.log("bend commit threw " + e.getClass().getSimpleName());
        }
        // One line per gesture: did the drag actually leave a warp on the model? This is the line
        // SPEC O's repro was missing, and it is what "mesh" in project.json is written from.
        TransformDiag.log("bend commit hasMesh=" + owner.hasMesh()
                + " armed=" + owner.isArmed());
    }
}
