package com.fadcam.ui.faditor.avatar;

import java.util.List;

/**
 * A6 dangle physics (PLAN_AVATAR_STUDIO §MINED "life package" sibling) — a cheap,
 * DETERMINISTIC verlet chain for dangle-tagged parts (hair/ears/tails).
 *
 * <p>The chain anchors at the part's FIRST pin and hangs one node per remaining pin,
 * with rest lengths taken from the part's authored rest chain. Each frame the caller
 * moves the anchor (node 0) to wherever the part's first pin currently sits on screen;
 * the anchor's own motion IS the excitation — no explicit parent-velocity plumbing.
 * Gravity pulls the free nodes toward +Y, verlet inertia makes them lag and swing,
 * distance constraints keep the bone lengths rigid (matching FABRIK's rigid-bone
 * read), and damping settles the chain. The solved node positions become the part's
 * POSED PINS, so the existing {@link PinWarpStrip} warp renders the bend — dangle and
 * pin-warp are one pipeline, not two systems.</p>
 *
 * <p>Pure Java, zero randomness: identical (anchor sequence, dt sequence) inputs
 * produce identical output — the same bake-replay determinism contract LifeSignals
 * keeps ({@code tools/jvm-harness/DangleTest.java} pins it).</p>
 */
public final class DangleSim {

    /** Gravity in px/s² at scale 1 — tuned for a puppet-scale strip (~200px). */
    private static final float GRAVITY = 2200f;
    /** Velocity kept per frame (verlet damping). */
    private static final float DAMPING = 0.90f;
    /** Rigid-bone constraint passes per step. */
    private static final int ITERATIONS = 3;
    /** dt clamp so a paused frame doesn't slingshot the chain (s). */
    private static final float MAX_DT = 1f / 20f;

    /**
     * THE AUTHORED DANGLE SETTINGS — gravity, wind, and the per-pin feel controls.
     *
     * <p>Added 2026-09-15. Every field defaults to the constant this class already used, so a
     * {@code DangleSim} built without params behaves EXACTLY as it did — which is what keeps the
     * avatar preview and {@code DangleTest}'s determinism contract intact while the puppet rig
     * gets knobs that do something.
     *
     * <p>The drawer's names map on as: Springiness -> {@link #spring}, Settle -> {@link #damping}
     * (inverted: settling sooner means keeping less velocity), Mass -> {@link #mass}, Max stretch
     * -> {@link #maxStretch}, and the character's Gravity/Wind -> {@link #gravity} and
     * {@link #windX}/{@link #windY}.
     */
    public static final class Params {
        /** Downward acceleration, px/s². 0 makes a chain float. */
        public float gravity = GRAVITY;
        /** Sideways acceleration, px/s². The character's Wind, resolved into a direction. */
        public float windX = 0f, windY = 0f;
        /** Velocity kept per frame. LOWER settles sooner. */
        public float damping = DAMPING;
        /**
         * How heavily the chain resists being moved, 0..1 around a neutral 0.5.
         *
         * <p>A verlet chain has no real mass term — every node accelerates the same under
         * gravity, which is correct physics and useless as a control. What "heavy" means to an
         * animator is LAG: a heavy tail keeps going when the body stops. So mass scales the
         * inherited velocity, which is exactly that.
         */
        public float mass = 0.5f;
        /**
         * How strongly a bone pulls back to its rest length, 0..1. 1 is the rigid bone this
         * class always had; lower lets the chain breathe before the final snap.
         */
        public float spring = 1f;
        /** How far past rest a bone may sit, as a fraction. 0 keeps bones exactly rigid. */
        public float maxStretch = 0f;
    }

    private Params params = new Params();

    /** Replace the settings. Takes effect on the next {@link #step}; does not reset the state. */
    public void setParams(Params p) { if (p != null) this.params = p; }

    public Params params() { return params; }

    private final int nodeCount;
    private final float[] restLen;   // bone lengths, px
    private final float[] x, y;      // current node positions, px (view/world space)
    private final float[] px, py;    // previous positions (verlet state)
    private boolean primed = false;

    /**
     * @param restPinsPx the part's rest chain mapped to PIXELS (defines bone lengths
     *                   and node count — one node per pin).
     */
    public DangleSim(List<float[]> restPinsPx) {
        nodeCount = restPinsPx.size();
        restLen = new float[Math.max(0, nodeCount - 1)];
        for (int i = 0; i < nodeCount - 1; i++) {
            float dx = restPinsPx.get(i + 1)[0] - restPinsPx.get(i)[0];
            float dy = restPinsPx.get(i + 1)[1] - restPinsPx.get(i)[1];
            restLen[i] = (float) Math.sqrt(dx * dx + dy * dy);
        }
        x = new float[nodeCount];
        y = new float[nodeCount];
        px = new float[nodeCount];
        py = new float[nodeCount];
    }

    public int nodeCount() { return nodeCount; }

    /** True once the chain has a valid state (first step primes it). */
    public boolean isPrimed() { return primed; }

    /**
     * Advances the chain one frame. Node 0 is pinned to the anchor; free nodes
     * verlet-integrate under gravity, then {@value #ITERATIONS} constraint passes
     * restore bone lengths (anchor-out, so the chain never detaches).
     *
     * @param anchorX/anchorY where the part's first pin sits NOW (px).
     * @param dtSeconds       frame delta, clamped to {@value #MAX_DT}s.
     */
    public void step(float anchorX, float anchorY, float dtSeconds) {
        if (nodeCount == 0) return;
        if (!primed) {
            // First contact: hang straight down from the anchor, at rest.
            float cy = anchorY;
            for (int i = 0; i < nodeCount; i++) {
                x[i] = anchorX;
                y[i] = cy;
                px[i] = x[i];
                py[i] = y[i];
                if (i < nodeCount - 1) cy += restLen[i];
            }
            primed = true;
        }
        float dt = Math.max(1e-4f, Math.min(MAX_DT, dtSeconds));

        x[0] = anchorX;
        y[0] = anchorY;
        // Mass reads as LAG: a heavier chain keeps more of the velocity it had, so it carries on
        // when the anchor stops. Centred on 0.5 so the default is the plain damping this class
        // always used.
        float keep = params.damping * (0.7f + 0.6f * clamp01(params.mass));
        if (keep > 0.995f) keep = 0.995f;
        float ax = params.windX * dt * dt;
        float ay = params.gravity * dt * dt + params.windY * dt * dt;
        for (int i = 1; i < nodeCount; i++) {
            float vx = (x[i] - px[i]) * keep;
            float vy = (y[i] - py[i]) * keep;
            px[i] = x[i];
            py[i] = y[i];
            x[i] += vx + ax;
            y[i] += vy + ay;
        }
        for (int pass = 0; pass < ITERATIONS; pass++) {
            x[0] = anchorX;
            y[0] = anchorY;
            for (int i = 0; i < nodeCount - 1; i++) {
                float dx = x[i + 1] - x[i];
                float dy = y[i + 1] - y[i];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1e-6f) { dy = 1e-3f; len = 1e-3f; }
                float diff = (len - restLen[i]) / len * clamp01(params.spring);
                if (i == 0) {
                    // Anchor is immovable: the child absorbs the full correction.
                    x[i + 1] -= dx * diff;
                    y[i + 1] -= dy * diff;
                } else {
                    x[i] += dx * diff * 0.5f;
                    y[i] += dy * diff * 0.5f;
                    x[i + 1] -= dx * diff * 0.5f;
                    y[i + 1] -= dy * diff * 0.5f;
                }
            }
        }
        // Final anchor-out normalization (FABRIK-backward-pass style): each bone
        // snapped to EXACT rest length, child-only corrections sweeping outward.
        // Relaxation passes above shape the motion; this guarantees rigid bones
        // even under violent anchor whips (DangleTest's rigidity case).
        x[0] = anchorX;
        y[0] = anchorY;
        for (int i = 0; i < nodeCount - 1; i++) {
            float dx = x[i + 1] - x[i];
            float dy = y[i + 1] - y[i];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-6f) { dx = 0f; dy = 1f; len = 1f; }
            // Max stretch is a CEILING, not a target: a bone may sit anywhere between rest and
            // rest*(1+maxStretch), and is snapped back only when it exceeds that. With the
            // default of 0 this is the exact rigid bone the avatar preview relies on.
            float cap = restLen[i] * (1f + Math.max(0f, params.maxStretch));
            float want = len > cap ? cap : (len < restLen[i] ? restLen[i] : len);
            x[i + 1] = x[i] + dx / len * want;
            y[i + 1] = y[i] + dy / len * want;
        }
    }

    /** Solved node position (px). Valid after the first {@link #step}. */
    public float nodeX(int i) { return x[i]; }

    public float nodeY(int i) { return y[i]; }

    /** Drops the state so the next step re-primes (e.g. rig rebind). */
    public void reset() { primed = false; }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0f : (v < 0f ? 0f : (v > 1f ? 1f : v));
    }
}
