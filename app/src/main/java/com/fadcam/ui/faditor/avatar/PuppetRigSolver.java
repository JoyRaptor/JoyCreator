package com.fadcam.ui.faditor.avatar;

import com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack;

/**
 * THE RIG LAYER: bones and physics in, PIN POSITIONS out.
 *
 * <p>This is the missing middle of puppeteering, and naming what it is not is the quickest way to
 * say what it is:
 *
 * <pre>
 *   the drawer   -- pin types, bones, sliders          (puppet/, the UI lane)
 *   THIS FILE    -- chains and dangle -> pin positions (the rig layer)
 *   the deformer -- pin positions -> warped triangles  (transform/mesh/)
 * </pre>
 *
 * <p>The deformer has never needed to know what a bone is, and after this file it still does not:
 * everything here ends as {@code (dx, dy)} offsets in the same pose array a finger would have
 * dragged. A keyframed pin, an IK-solved pin and a simulated pin are indistinguishable downstream,
 * which is why they all export, scrub and undo identically without a line of new code.
 *
 * <h3>It knows nothing about {@code PuppetRig}</h3>
 * <p>Deliberately. The rig model belongs to the UI lane, and a solver that imported it would make
 * the two lanes recompile together forever. {@link Chain} is a plain description the caller fills
 * in — the same arrangement that lets the weight table take a stiffness without the engine ever
 * learning what a "Stiff pin" is.
 *
 * <h3>Simulation is BAKED, never live</h3>
 * <p>A verlet chain is a forward simulation: it can step from now to the next frame, and it has no
 * way to answer "what does this look like at 5 seconds" without running every frame in between. So
 * a timeline cannot scrub it. {@link #bakeDangle} runs the simulation once and writes the result
 * into the pose track as ordinary keys, after which scrubbing, exporting and undo work because
 * there is nothing left to simulate. {@code PuppetPoseResolver} already documents this as the
 * bake-to-parameter-track doctrine; this is the same doctrine applied to pins.
 *
 * <p>No Android imports — the maths is provable on the desktop harness, which is where its tests
 * live.
 */
public final class PuppetRigSolver {

    private PuppetRigSolver() {}

    /**
     * How many unit lengths the simulation treats as its "pixels".
     *
     * <p>{@link DangleSim}'s gravity is in px/s² and tuned for a puppet-scale strip of about 200px.
     * A puppet works in unit space where the whole picture is 1, so the anchor is scaled into that
     * space and the result scaled back. Without this, gravity would be two thousand times too
     * strong and every tail would snap straight down in one frame.
     */
    private static final float SIM_SCALE = 200f;

    /**
     * A chain of pins joined by bones, described in plain arrays.
     *
     * <p>Index 0 is the ROOT and does not move — it is the anchor the spec's "tap the hip first"
     * creates. The last entry is the tip: the wrist you drag, or the end of the tail that swings.
     */
    public static final class Chain {
        /** Pin indices, root first. At least two. */
        public int[] pins;

        /** One per link. Null or 0 means "measure it from the rest pose", which is the usual case. */
        public float[] restLength;

        /** Per link. A stretchy bone lengthens rather than letting the hand fall short. */
        public boolean[] stretchy;

        /** Per link, fraction over rest. */
        public float[] maxStretch;

        /** Per pin index WITHIN the chain, meaningful for the interior ones (1..n-2). */
        public boolean[] jointLimits;
        public float[] minAngleDeg;
        public float[] maxAngleDeg;

        /**
         * Which way the chain prefers to fold: +1, -1, or 0 for no preference.
         *
         * <p>One bit, decided at bind time from how the artwork is already bent, rather than a
         * pole-target object the user would have to position. "Flip elbow" toggles it.
         */
        public int bendSign = 1;

        public int size() { return pins == null ? 0 : pins.length; }
    }

    /**
     * Drag a chain's TIP to a point and write where every pin in it ended up.
     *
     * <p>The root stays where the pose already had it, so an arm follows a hip that is itself
     * animated — which is the whole reason a bone carries no keyframes of its own.
     *
     * @param pinRest    every pin's rest position, interleaved x,y, unit space
     * @param pinOffsets the pose: {@code (dx,dy)} per pin, READ for the current positions and
     *                   WRITTEN with the solved ones. Pins outside the chain are untouched.
     * @param targetX    where the tip should go, in unit space (absolute, not an offset)
     * @return true when the tip reached the target; false when the chain is too short to get
     *         there, in which case it is left fully extended towards it, which is the correct
     *         puppet read rather than an error
     */
    public static boolean solveChain(Chain chain, float[] pinRest, float[] pinOffsets,
                                     float targetX, float targetY) {
        if (chain == null || pinRest == null || pinOffsets == null) return false;
        int n = chain.size();
        if (n < 2) return false;
        for (int p : chain.pins) {
            if (p < 0 || p * 2 + 1 >= pinRest.length || p * 2 + 1 >= pinOffsets.length) return false;
        }

        // Current positions = rest + whatever the pose already says.
        float[] joints = new float[n * 2];
        for (int i = 0; i < n; i++) {
            int p = chain.pins[i];
            joints[i * 2] = pinRest[p * 2] + pinOffsets[p * 2];
            joints[i * 2 + 1] = pinRest[p * 2 + 1] + pinOffsets[p * 2 + 1];
        }

        // Rest lengths come from the REST pose, not the current one. Measuring the current pose
        // would let a stretched bone become its own new rest, and the arm would creep longer
        // every drag — the classic accumulating-drift bug.
        float[] len = new float[n - 1];
        for (int i = 0; i < n - 1; i++) {
            float given = (chain.restLength != null && i < chain.restLength.length)
                    ? chain.restLength[i] : 0f;
            if (given > 0f) {
                len[i] = given;
            } else {
                int a = chain.pins[i], b = chain.pins[i + 1];
                len[i] = (float) Math.hypot(pinRest[b * 2] - pinRest[a * 2],
                        pinRest[b * 2 + 1] - pinRest[a * 2 + 1]);
            }
        }

        boolean reached = FabrikSolver.solveConstrained(joints, len, targetX, targetY,
                chain.stretchy, chain.maxStretch,
                chain.jointLimits, chain.minAngleDeg, chain.maxAngleDeg, chain.bendSign);

        for (int i = 0; i < n; i++) {
            int p = chain.pins[i];
            pinOffsets[p * 2] = joints[i * 2] - pinRest[p * 2];
            pinOffsets[p * 2 + 1] = joints[i * 2 + 1] - pinRest[p * 2 + 1];
        }
        return reached;
    }

    /**
     * Run a dangle chain over a stretch of time and write the result into the pose track as keys.
     *
     * <p>The chain hangs from its root, and the root's OWN animation is the excitation — move the
     * head and the hair follows, with no explicit plumbing between them. Nothing else about the
     * character is touched: every pose written keeps the value every other pin already had at that
     * instant, which is what {@link MeshPoseTrack#putComponents} guarantees.
     *
     * <h3>Every anchor position is read BEFORE anything is written</h3>
     * <p>Not tidiness — correctness. Writing a key changes what the track interpolates to between
     * its neighbours, so a loop that read and wrote in step would be simulating against a curve it
     * was editing underneath itself. Sampling first makes the bake a pure function of the
     * animation as it was when the user pressed the button.
     *
     * @param stepMs  simulation step. 16 is a frame; smaller is steadier and slower. Fixed rather
     *                than taken from playback, so the same rig bakes identically on any phone.
     * @return how many poses were written, or -1 when the inputs do not describe a chain
     */
    public static int bakeDangle(Chain chain, float[] pinRest, MeshPoseTrack track,
                                 long fromMs, long toMs, int stepMs, DangleSim.Params params) {
        if (chain == null || pinRest == null || track == null) return -1;
        int n = chain.size();
        if (n < 2 || toMs <= fromMs) return -1;
        int step = Math.max(1, stepMs);
        int arity = track.arity();
        for (int p : chain.pins) {
            if (p < 0 || p * 2 + 1 >= arity || p * 2 + 1 >= pinRest.length) return -1;
        }

        int frames = (int) ((toMs - fromMs) / step) + 1;
        if (frames < 2 || frames > 100000) return -1;

        // 1. Sample the anchor's animated position at every instant, first.
        int root = chain.pins[0];
        float[] anchorX = new float[frames], anchorY = new float[frames];
        float[] pose = new float[arity];
        for (int f = 0; f < frames; f++) {
            long t = fromMs + (long) f * step;
            java.util.Arrays.fill(pose, 0f);
            track.valueAt(t, pose);
            anchorX[f] = pinRest[root * 2] + pose[root * 2];
            anchorY[f] = pinRest[root * 2 + 1] + pose[root * 2 + 1];
        }

        // 2. Simulate, in the scale DangleSim's gravity was tuned for.
        java.util.List<float[]> rest = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int p = chain.pins[i];
            rest.add(new float[]{pinRest[p * 2] * SIM_SCALE, pinRest[p * 2 + 1] * SIM_SCALE});
        }
        DangleSim sim = new DangleSim(rest);
        if (params != null) sim.setParams(params);
        sim.reset();

        float dt = step / 1000f;
        float[][] solved = new float[frames][n * 2];
        for (int f = 0; f < frames; f++) {
            sim.step(anchorX[f] * SIM_SCALE, anchorY[f] * SIM_SCALE, dt);
            for (int i = 0; i < n; i++) {
                solved[f][i * 2] = sim.nodeX(i) / SIM_SCALE;
                solved[f][i * 2 + 1] = sim.nodeY(i) / SIM_SCALE;
            }
        }

        // 3. Write. The ROOT is skipped: it is whatever is driving the chain, and overwriting it
        // with the simulation's copy of itself would fight whoever owns it.
        int[] comps = new int[(n - 1) * 2];
        float[] vals = new float[(n - 1) * 2];
        for (int i = 1; i < n; i++) {
            comps[(i - 1) * 2] = chain.pins[i] * 2;
            comps[(i - 1) * 2 + 1] = chain.pins[i] * 2 + 1;
        }
        int written = 0;
        for (int f = 0; f < frames; f++) {
            for (int i = 1; i < n; i++) {
                int p = chain.pins[i];
                vals[(i - 1) * 2] = solved[f][i * 2] - pinRest[p * 2];
                vals[(i - 1) * 2 + 1] = solved[f][i * 2 + 1] - pinRest[p * 2 + 1];
            }
            if (track.putComponents(fromMs + (long) f * step, comps, vals, null)) written++;
        }
        return written;
    }

    /**
     * Build the {@link DangleSim.Params} the drawer's sliders describe.
     *
     * <p>One place where the authored numbers become physics, so the mapping can be argued about
     * in one file rather than rediscovered in three.
     *
     * @param gravity    character Gravity, 0..1
     * @param wind       character Wind, 0..1
     * @param windDirDeg which way the wind blows, degrees, 0 = towards +x
     * @param spring     pin Springiness, 0..1
     * @param settle     pin Settle, 0..1 — HIGHER settles sooner, so it becomes less damping
     * @param mass       pin Mass, 0..1
     * @param maxStretch pin Max stretch, 0..1
     */
    public static DangleSim.Params paramsFor(float gravity, float wind, float windDirDeg,
                                             float spring, float settle, float mass,
                                             float maxStretch) {
        DangleSim.Params p = new DangleSim.Params();
        p.gravity = 2200f * 2f * clamp01(gravity);          // 0.5 on the slider = the old constant
        float w = 2200f * clamp01(wind);
        double rad = Math.toRadians(windDirDeg);
        p.windX = (float) (w * Math.cos(rad));
        p.windY = (float) (w * Math.sin(rad));
        // Settle runs the other way from damping: "settles sooner" means keeping less velocity.
        p.damping = 0.98f - 0.25f * clamp01(settle);
        p.mass = clamp01(mass);
        p.spring = 0.35f + 0.65f * clamp01(spring);
        p.maxStretch = 0.5f * clamp01(maxStretch);
        return p;
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0f : (v < 0f ? 0f : (v > 1f ? 1f : v));
    }
}
