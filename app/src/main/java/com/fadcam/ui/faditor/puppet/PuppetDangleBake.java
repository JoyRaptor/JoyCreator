package com.fadcam.ui.faditor.puppet;


import com.fadcam.ui.faditor.avatar.DangleSim;
import com.fadcam.ui.faditor.avatar.PuppetRigSolver;
import com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;
import com.fadcam.ui.faditor.transform.mesh.PuppetTopology;

/**
 * RUN THE DANGLE PINS, and write what they did into the pose track.
 *
 * <p>The last piece of wiring: {@code PuppetPin.Type.DANGLE} existed, its four sliders were
 * authored and saved, {@code DangleSim} was written and tested, and nothing in the app ever called
 * it. Hair, ears and tails were a pin type that did nothing.
 *
 * <h3>Baked, never live</h3>
 * <p>A verlet chain is a forward simulation: it can step from now to the next frame and has no way
 * to answer "what does this look like at five seconds" without running every frame in between. So
 * a timeline cannot scrub one. This runs it ONCE over the item's own span and writes ordinary
 * keyframes, after which scrubbing, exporting and undo all work because there is nothing left to
 * simulate. {@code PuppetPoseResolver} already calls this the bake-to-parameter-track doctrine;
 * this is the same doctrine applied to pins.
 *
 * <h3>What counts as a dangle chain</h3>
 * <p>A run of DANGLE pins hanging off something that is not one. Walking from each dangle pin that
 * has no dangle child back towards the root, the chain is every DANGLE pin plus the FIRST pin that
 * is not — the anchor, whose own animation is the excitation. Move the head and the hair follows,
 * with nothing plumbed between them.
 *
 * <p>Plain Java with no annotations, so this runs on the desktop harness where its proof
 * lives — the same reason PuppetPin and PuppetRig carry none.
 *
 * <p>A dangle pin with no bone at all is not a chain and is left alone: it is a pin somebody
 * marked Dangle and has not rigged yet, and guessing an anchor for it would produce motion the
 * user never asked for.
 */
public final class PuppetDangleBake {

    private PuppetDangleBake() {}

    /**
     * Simulation step. 33ms is a frame at 30fps; the bake is the same on every phone because this
     * is fixed rather than taken from playback, which is what makes a reopened project produce the
     * performance that was approved rather than a new one.
     */
    private static final int STEP_MS = 33;

    /** Longest span worth baking, so a dangle on an hour-long still cannot lock the UI. */
    private static final long MAX_SPAN_MS = 60_000L;

    /**
     * Bake every dangle chain in {@code rig} into {@code spec}'s pose track.
     *
     * @param spanMs how long the item is on screen; the bake covers 0..spanMs in its own local time
     * @return how many chains were baked
     */
    public static int bakeAll(MeshWarpSpec spec, PuppetRig rig, long spanMs) {
        if (spec == null || rig == null) return 0;
        if (!(spec.topology() instanceof PuppetTopology)) return 0;
        PuppetTopology topo = (PuppetTopology) spec.topology();
        if (topo.handleCount() != rig.pinCount()) return 0;      // a stale mesh bakes nothing
        long span = Math.min(MAX_SPAN_MS, spanMs);
        if (span < STEP_MS * 2L) return 0;

        float[] rest = new float[rig.pinCount() * 2];
        for (int i = 0; i < rig.pinCount(); i++) {
            rest[i * 2] = topo.handleRestX(i);
            rest[i * 2 + 1] = topo.handleRestY(i);
        }
        MeshPoseTrack track = spec.ensureTrack();
        if (track.arity() != topo.handleArity()) return 0;

        int baked = 0;
        for (int i = 0; i < rig.pinCount(); i++) {
            if (rig.pin(i).type != PuppetPin.Type.DANGLE) continue;
            if (hasDangleChild(rig, i)) continue;                // only bake from the TIP
            int[] chain = dangleChain(rig, i);
            if (chain == null) continue;
            DangleSim.Params params = paramsFor(rig, rig.pin(i));
            PuppetRigSolver.Chain c = new PuppetRigSolver.Chain();
            c.pins = chain;
            if (PuppetRigSolver.bakeDangle(c, rest, track, 0L, span, STEP_MS, params) > 0) baked++;
        }
        return baked;
    }

    /** True when some other DANGLE pin hangs off this one, so this is not the end of the chain. */
    private static boolean hasDangleChild(PuppetRig rig, int pin) {
        for (int b = 0; b < rig.boneCount(); b++) {
            PuppetRig.Bone bone = rig.bone(b);
            if (bone.rootPin != pin) continue;
            if (bone.tipPin >= 0 && bone.tipPin < rig.pinCount()
                    && rig.pin(bone.tipPin).type == PuppetPin.Type.DANGLE) {
                return true;
            }
        }
        return false;
    }

    /**
     * The chain hanging off an anchor, ROOT FIRST — the order {@code PuppetRigSolver} expects.
     *
     * @return null when the pin has no anchor to hang from, or the chain is too short to swing
     */
    private static int[] dangleChain(PuppetRig rig, int tip) {
        int[] toRoot = rig.chainToRoot(tip);                     // tip .. root
        if (toRoot == null || toRoot.length < 2) return null;
        // Walk outward from the tip, keeping dangle pins, and stop on the FIRST that is not —
        // that one is the anchor and belongs in the chain as its fixed end.
        int keep = 1;
        while (keep < toRoot.length && rig.pin(toRoot[keep]).type == PuppetPin.Type.DANGLE) keep++;
        if (keep >= toRoot.length) return null;                  // all dangle, nothing anchors it
        keep++;                                                  // include the anchor itself
        int[] out = new int[keep];
        for (int i = 0; i < keep; i++) out[i] = toRoot[keep - 1 - i];
        return out.length >= 2 ? out : null;
    }

    /**
     * The drawer's sliders as physics. The tip pin's feel settings drive the whole chain, because
     * a tail with a different mass per segment is a control nobody asked for and four more sliders
     * to reconcile.
     */
    private static DangleSim.Params paramsFor(PuppetRig rig, PuppetPin tip) {
        return PuppetRigSolver.paramsFor(rig.gravity, rig.wind, rig.windDirDeg,
                tip.spring, tip.settle, tip.mass, tip.maxStretch);
    }
}
