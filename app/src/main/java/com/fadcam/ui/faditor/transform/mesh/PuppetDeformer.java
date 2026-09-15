package com.fadcam.ui.faditor.transform.mesh;

/**
 * Stage 3b of puppeteering: the SOLVER. Sparse pins in, every vertex moved out.
 *
 * <p>The spec calls this "the single biggest unknown", names ARAP and MLS as the candidates, and
 * says MLS "is cheaper and simpler and may be enough on a phone". This is rigid MLS — Schaefer,
 * McPhail and Warren, <i>Image Deformation Using Moving Least Squares</i> (2006).
 *
 * <h3>Why RIGID and not affine or similarity</h3>
 * <p>All three are one formula apart, and the difference is exactly what a character looks like
 * when you pull an arm:
 * <ul>
 *   <li><b>affine</b> — shears. A limb becomes a parallelogram and the character looks melted.</li>
 *   <li><b>similarity</b> — no shear, but scales. Pull a hand and the whole arm inflates.</li>
 *   <li><b>rigid</b> — rotation and translation only. The picture bends and nothing swells, which
 *       is what "as rigid as possible" means and what a puppet is expected to do.</li>
 * </ul>
 * Similarity is kept as the fallback for the one degenerate case where rigid has no answer (see
 * {@link #solve}), because falling back to a slightly-wrong pose beats refusing to draw.
 *
 * <h3>Cost, which is the thing the spec asked to be measured</h3>
 * <p>O(vertices x pins) per solve, no matrix inversion, no iteration — that is the whole reason
 * MLS was chosen over ARAP, which needs a sparse linear solve per frame. Roughly 30 floating-point
 * operations per vertex-pin pair. {@code PuppetSolverTest} measures it rather than asserting it.
 *
 * <p><b>Solve once per POSE CHANGE, not per frame.</b> Nothing here caches, because a deformer is
 * shared by threads and holding state would be the bug. The engine above already only re-solves
 * when the pose changes, which is where that decision belongs.
 *
 * <p>No Android imports.
 */
public final class PuppetDeformer implements MeshDeformer {

    /**
     * Falloff exponent. {@code w = 1 / d^(2*alpha)}. Higher = pins hold their neighbourhood more
     * tightly and influence falls off faster. 1.0 is the paper's default and behaves well; this is
     * the first number to expose if posing ever feels too loose or too stiff.
     */
    private static final float ALPHA = 1.0f;

    /** Nearer than this to a pin and the vertex simply IS the pin — avoids dividing by zero. */
    private static final float SNAP = 1e-8f;

    @Override
    public boolean supports(MeshTopology topology) {
        return topology instanceof PuppetTopology;
    }

    @Override
    public boolean isIdentity(float[] handleValues) {
        if (handleValues == null) return true;
        for (float v : handleValues) {
            if (Math.abs(v) > 1e-6f) return false;
        }
        return true;
    }

    @Override
    public void identityPose(MeshTopology topology, float[] out) {
        if (out != null) java.util.Arrays.fill(out, 0f);
    }

    @Override
    public float clampComponent(float v) {
        // A pin may be dragged well outside the picture — that is a legitimate pose (an arm
        // reaching out of frame), unlike a lattice nudge which folds its own cell. The bound is a
        // sanity rail against NaN and runaway values, not a style choice.
        if (Float.isNaN(v)) return 0f;
        return Math.max(-8f, Math.min(8f, v));
    }

    /**
     * Move every vertex so the pins land where the pose says.
     *
     * @param handleValues {@code (dx,dy)} per pin, OFFSETS from the pin's rest position
     * @return false when the inputs do not line up; the caller then draws flat rather than wrong
     */
    @Override
    public boolean solve(MeshTopology topology, MeshBuffers buffers, float[] handleValues) {
        if (!(topology instanceof PuppetTopology) || buffers == null) return false;
        PuppetTopology topo = (PuppetTopology) topology;
        int pinCount = topo.handleCount();
        int n = topo.vertexCount();
        if (n <= 0) return false;
        if (buffers.rest == null || buffers.rest.length < n * 2) return false;
        if (buffers.positions == null || buffers.positions.length < n * 2) return false;
        if (handleValues == null || handleValues.length != pinCount * 2) return false;

        final float[] rest = buffers.rest;
        final float[] pos = buffers.positions;

        // NO PINS: the identity. Not an error — a freshly traced puppet has none yet.
        if (pinCount == 0) {
            System.arraycopy(rest, 0, pos, 0, n * 2);
            return true;
        }

        // Pin rest (p) and posed (q) positions.
        float[] px = new float[pinCount], py = new float[pinCount];
        float[] qx = new float[pinCount], qy = new float[pinCount];
        for (int i = 0; i < pinCount; i++) {
            px[i] = topo.handleRestX(i);
            py[i] = topo.handleRestY(i);
            qx[i] = px[i] + handleValues[i * 2];
            qy[i] = py[i] + handleValues[i * 2 + 1];
        }

        // ONE PIN is pure translation. MLS is undefined there — with a single control point every
        // weight cancels and the rotation is unconstrained — so it is handled as the thing it
        // obviously is rather than left to produce NaN.
        if (pinCount == 1) {
            float dx = qx[0] - px[0], dy = qy[0] - py[0];
            for (int v = 0; v < n; v++) {
                pos[v * 2] = rest[v * 2] + dx;
                pos[v * 2 + 1] = rest[v * 2 + 1] + dy;
            }
            return true;
        }

        float[] w = new float[pinCount];

        for (int v = 0; v < n; v++) {
            final float vx = rest[v * 2], vy = rest[v * 2 + 1];

            // ── weights, with the coincident-pin escape ────────────────────────────────────
            int snapped = -1;
            float wsum = 0f;
            for (int i = 0; i < pinCount; i++) {
                float dx = px[i] - vx, dy = py[i] - vy;
                float d2 = dx * dx + dy * dy;
                if (d2 < SNAP) { snapped = i; break; }
                // w = 1/d^(2a); with a=1 that is simply 1/d2, which avoids a pow per pin per
                // vertex — the inner loop of the whole feature.
                float wi = ALPHA == 1.0f ? 1f / d2 : (float) (1.0 / Math.pow(d2, ALPHA));
                w[i] = wi;
                wsum += wi;
            }
            if (snapped >= 0) {
                // The vertex sits exactly on a pin: it goes exactly where that pin went. Any
                // weighted answer here would be a division by zero dressed up.
                pos[v * 2] = qx[snapped];
                pos[v * 2 + 1] = qy[snapped];
                continue;
            }
            if (!(wsum > 0f) || Float.isInfinite(wsum)) {
                pos[v * 2] = vx;
                pos[v * 2 + 1] = vy;
                continue;
            }

            // ── weighted centroids ────────────────────────────────────────────────────────
            float psx = 0f, psy = 0f, qsx = 0f, qsy = 0f;
            for (int i = 0; i < pinCount; i++) {
                psx += w[i] * px[i];
                psy += w[i] * py[i];
                qsx += w[i] * qx[i];
                qsy += w[i] * qy[i];
            }
            psx /= wsum; psy /= wsum; qsx /= wsum; qsy /= wsum;

            final float ux = vx - psx, uy = vy - psy;

            // ── the rigid accumulation ────────────────────────────────────────────────────
            // For each pin: d = p^ . u, c = p^ x u. Then the contribution of q^ through the
            // 2x2 [[d, c], [-c, d]] is (qx*d - qy*c, qx*c + qy*d). Summing those and normalising
            // to |u| is what makes the map a rotation rather than a scale.
            float fx = 0f, fy = 0f;
            for (int i = 0; i < pinCount; i++) {
                float phx = px[i] - psx, phy = py[i] - psy;
                float qhx = qx[i] - qsx, qhy = qy[i] - qsy;
                float d = phx * ux + phy * uy;
                float c = phx * uy - phy * ux;
                fx += w[i] * (qhx * d - qhy * c);
                fy += w[i] * (qhx * c + qhy * d);
            }

            float flen = (float) Math.sqrt(fx * fx + fy * fy);
            float ulen = (float) Math.sqrt(ux * ux + uy * uy);
            if (flen > 1e-12f && ulen > 1e-12f) {
                float s = ulen / flen;                   // RIGID: keep the length, take the angle
                pos[v * 2] = qsx + fx * s;
                pos[v * 2 + 1] = qsy + fy * s;
            } else {
                // Degenerate: the vertex sits on the weighted centroid (ulen 0), or the pins are
                // collinear in a way that cancels the rotation (flen 0). Fall back to SIMILARITY,
                // which has an answer here, rather than refusing to place the vertex.
                float mu = 0f;
                for (int i = 0; i < pinCount; i++) {
                    float phx = px[i] - psx, phy = py[i] - psy;
                    mu += w[i] * (phx * phx + phy * phy);
                }
                if (mu > 1e-12f) {
                    pos[v * 2] = qsx + fx / mu;
                    pos[v * 2 + 1] = qsy + fy / mu;
                } else {
                    // Every pin is at the same place: nothing is determined but the translation.
                    pos[v * 2] = vx + (qsx - psx);
                    pos[v * 2 + 1] = vy + (qsy - psy);
                }
            }
        }
        return true;
    }
}
