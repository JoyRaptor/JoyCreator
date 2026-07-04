package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * THE single evaluation authority for avatar rigs (PLAN_AVATAR_STUDIO single-
 * authority rule, mirroring {@code SpriteFrameResolver}): editor preview, the
 * recorder overlay, the bake-to-parameter-track replay, and AI validation ALL
 * call {@link #resolve} — no other code may blend poses or pick cells.
 *
 * <p>PURE function of its inputs: no clocks, no internal state. Hysteresis
 * memory for discrete swaps lives in the caller-owned {@link DiscreteState}
 * passed in and returned updated — so replaying baked parameters through this
 * resolver is deterministic (the MINED bake-to-param-track doctrine).</p>
 *
 * <p>Semantics (MINED decisions baked in):</p>
 * <ul>
 *   <li>Domains are 1-D strips ({@code driverY == null}; limbs) or 2-D grids
 *       (heads). Driver values are normalized [-1..1] per axis.</li>
 *   <li>CONTINUOUS properties (x/y/scale/rotation) and PINS blend
 *       (bi)linearly between the surrounding cells.</li>
 *   <li>EMPTY-CELL INHERITANCE (authoring gold): a missing cell or missing
 *       per-part pose simply drops out of the blend and the remaining weights
 *       renormalize — rig 3 cells and the rest auto-blend. No authored cell at
 *       all → the part's neutral base pose.</li>
 *   <li>DISCRETE properties (sprite cell / z / flips) come from the dominant
 *       corner cell with HYSTERESIS: the previous choice sticks until another
 *       cell's weight exceeds it by {@link #HYSTERESIS}. A committed change
 *       reports {@code swapped=true} so the renderer can run the pin-snap
 *       crossfade (draw both cells briefly at identical pin geometry).</li>
 * </ul>
 */
public final class PuppetPoseResolver {

    private PuppetPoseResolver() {}

    /** Weight margin a challenger cell must win by before a discrete swap commits. */
    public static final float HYSTERESIS = 0.15f;

    /** Caller-owned discrete-swap memory, keyed by domainId (one dominant cell each). */
    public static class DiscreteState {
        final Map<String, Integer> dominantCell = new HashMap<>(); // domainId -> cell linear index
    }

    /** Resolved state for one part at one instant. */
    public static class PartState {
        @NonNull public final String partId;
        public float x, y;              // canvas-normalized offset from base placement
        public float scale = 1f;
        public float rotationDeg;
        public int cellIndex;           // sprite cell to show
        public int z;
        public boolean flipH, flipV;
        /** Blended warp pins (item-normalized), empty when the part has none. */
        @NonNull public final List<float[]> pins = new ArrayList<>();
        /** True on the exact resolve where this part's discrete choice changed —
         *  the renderer starts its pin-snap crossfade window on this signal. */
        public boolean swapped;

        PartState(@NonNull String partId) { this.partId = partId; }
    }

    /**
     * Evaluate every part of {@code rig} for the given driver params (normalized
     * [-1..1] by driver name, e.g. "yaw","pitch","armL_angle"). Missing driver
     * params read as 0 (center). Parent composition (parent∘child transform
     * chains, followWeight) is the RENDERER's concern — this resolves per-part
     * pose-space state; keeping hierarchy math at draw time lets the same
     * resolved state feed both Canvas and GL paths.
     */
    @NonNull
    public static Map<String, PartState> resolve(@NonNull AvatarRig rig,
                                                 @NonNull Map<String, Float> params,
                                                 @NonNull DiscreteState state) {
        Map<String, PartState> out = new HashMap<>();
        for (AvatarRig.Part part : rig.getParts()) {
            PartState ps = new PartState(part.id);
            ps.z = part.z;
            out.put(part.id, ps);
        }
        for (AvatarRig.PoseDomain domain : rig.getDomains()) {
            resolveDomain(rig, domain, params, state, out);
        }
        return out;
    }

    private static void resolveDomain(@NonNull AvatarRig rig,
                                      @NonNull AvatarRig.PoseDomain d,
                                      @NonNull Map<String, Float> params,
                                      @NonNull DiscreteState state,
                                      @NonNull Map<String, PartState> out) {
        int cols = Math.max(1, d.cols);
        int rows = Math.max(1, d.rows);
        float dx = clamp1(param(params, d.driverX));
        float dy = d.driverY != null ? clamp1(param(params, d.driverY)) : 0f;
        float gx = (dx + 1f) / 2f * (cols - 1);
        float gy = rows > 1 ? (dy + 1f) / 2f * (rows - 1) : 0f;
        int c0 = (int) Math.floor(gx), r0 = (int) Math.floor(gy);
        int c1 = Math.min(c0 + 1, cols - 1), r1 = Math.min(r0 + 1, rows - 1);
        float fx = gx - c0, fy = gy - r0;

        // Up to 4 corners with bilinear weights (2 in 1-D, where r0==r1).
        int[][] corners = {{c0, r0}, {c1, r0}, {c0, r1}, {c1, r1}};
        float[] weights = {(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy};

        // Which parts does this domain pose? Union of partIds across its cells.
        java.util.Set<String> partIds = new java.util.LinkedHashSet<>();
        for (AvatarRig.Cell c : d.cells) {
            for (AvatarRig.PartPose pp : c.poses) partIds.add(pp.partId);
        }

        for (String partId : partIds) {
            PartState ps = out.get(partId);
            if (ps == null) continue; // pose for a part the rig no longer has — skip
            float wSum = 0f;
            float x = 0f, y = 0f, scale = 0f, rot = 0f;
            List<float[]> pinAcc = null;
            int pinCount = Integer.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                if (weights[i] <= 0f) continue;
                AvatarRig.Cell cell = d.cellAt(corners[i][0], corners[i][1]);
                AvatarRig.PartPose pp = cell != null ? cell.poseFor(partId) : null;
                if (pp == null) continue; // EMPTY-CELL INHERITANCE: drop + renormalize
                float w = weights[i];
                wSum += w;
                x += pp.x * w;
                y += pp.y * w;
                scale += pp.scale * w;
                rot += pp.rotationDeg * w;
                if (!pp.pins.isEmpty()) {
                    pinCount = Math.min(pinCount, pp.pins.size());
                    if (pinAcc == null) {
                        pinAcc = new ArrayList<>();
                        for (int q = 0; q < pp.pins.size(); q++) pinAcc.add(new float[]{0f, 0f});
                    }
                    for (int q = 0; q < Math.min(pinAcc.size(), pp.pins.size()); q++) {
                        pinAcc.get(q)[0] += pp.pins.get(q)[0] * w;
                        pinAcc.get(q)[1] += pp.pins.get(q)[1] * w;
                    }
                }
            }
            if (wSum > 0f) {
                ps.x += x / wSum;
                ps.y += y / wSum;
                ps.scale *= scale / wSum;
                ps.rotationDeg += rot / wSum;
                if (pinAcc != null) {
                    ps.pins.clear();
                    for (int q = 0; q < Math.min(pinCount, pinAcc.size()); q++) {
                        ps.pins.add(new float[]{pinAcc.get(q)[0] / wSum, pinAcc.get(q)[1] / wSum});
                    }
                }
            }
            // else: no authored pose anywhere near — neutral base (inheritance floor).
        }

        // ── Discrete choice with hysteresis (one dominant cell per domain) ──
        int bestIdx = -1;
        float bestW = -1f;
        for (int i = 0; i < 4; i++) {
            if (weights[i] > bestW) { bestW = weights[i]; bestIdx = i; }
        }
        int bestLinear = corners[bestIdx][1] * cols + corners[bestIdx][0];
        Integer prev = state.dominantCell.get(d.id);
        int chosenLinear;
        boolean swapped = false;
        if (prev == null) {
            chosenLinear = bestLinear;
            state.dominantCell.put(d.id, chosenLinear);
        } else if (bestLinear != prev) {
            float prevW = weightOfLinear(prev, cols, corners, weights);
            if (bestW > prevW + HYSTERESIS) {
                chosenLinear = bestLinear;
                state.dominantCell.put(d.id, chosenLinear);
                swapped = true;
            } else {
                chosenLinear = prev;
            }
        } else {
            chosenLinear = prev;
        }
        AvatarRig.Cell chosen = d.cellAt(chosenLinear % cols, chosenLinear / cols);
        if (chosen != null) {
            for (AvatarRig.PartPose pp : chosen.poses) {
                PartState ps = out.get(pp.partId);
                if (ps == null) continue;
                ps.cellIndex = pp.cellIndex;
                ps.z = pp.z != 0 ? pp.z : ps.z;
                ps.flipH = pp.flipH;
                ps.flipV = pp.flipV;
                ps.swapped |= swapped;
            }
        }
    }

    /** The bilinear weight of a previously-chosen cell if it's one of the current
     *  corners, else 0 (it has fully left the neighborhood — challenger wins). */
    private static float weightOfLinear(int linear, int cols,
                                        int[][] corners, float[] weights) {
        int col = linear % cols, row = linear / cols;
        for (int i = 0; i < 4; i++) {
            if (corners[i][0] == col && corners[i][1] == row) return weights[i];
        }
        return 0f;
    }

    private static float param(@NonNull Map<String, Float> params, @Nullable String name) {
        if (name == null) return 0f;
        Float v = params.get(name);
        return v != null ? v : 0f;
    }

    private static float clamp1(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
