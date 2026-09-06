package com.fadcam.ui.faditor.transform.mesh;

/**
 * THE GRID-WARP DEFORMER. Direct per-vertex offsets, smoothed by a Catmull-Rom tensor product over
 * a regular lattice. One implementation of {@link MeshDeformer}, not the only shape the engine can
 * express.
 *
 * <h3>What a pose stores: a NUDGE, not a position</h3>
 * <p>{@code handles[2i], handles[2i+1]} is the {@code (du,dv)} that lattice point {@code i} has
 * been dragged by, in the object's own unit space. All zeros is "no bend".</p>
 * <p>This is the property the owner tested in the interaction prototype and specifically asked
 * for. Because the nudge lives in the picture's own space and is re-projected through the CURRENT
 * quad every frame, a structural edit — scale an edge, tilt a corner, pinch, fold — <i>carries</i>
 * the bend instead of wiping it. Storing absolute stage positions is exactly the design that made
 * "scaling the edge undid my bend" happen in the prototype's first round.</p>
 *
 * <h3>Why Catmull-Rom</h3>
 * <ul>
 *   <li><b>It interpolates.</b> Direct manipulation demands the picture goes exactly where the
 *       thumb put the dot. A B-spline only approximates its controls and the image visibly lags
 *       the handle; users read that as broken.</li>
 *   <li><b>It is separable and local.</b> Weights are computed per axis; a moved point affects only
 *       its neighbourhood. A 25-point lattice costs the same per output vertex as a 9-point one.</li>
 *   <li><b>It has linear precision.</b> Not a nicety — it is the entire reason subdivision can be
 *       proved not to change the picture ({@link #subdivide}), and the reason to pick it over a
 *       uniform cubic B-spline.</li>
 *   <li><b>The cost is trivial.</b> 625 vertices x 2 axes x 25 multiply-adds is about 31,000 flops
 *       per frame per warped object — well under 0.1 ms on any phone of the last decade. The GPU
 *       only ever sees the finished buffer.</li>
 * </ul>
 * <p>Thin-plate splines and moving least squares are the right answer for SCATTERED pins, which is
 * what a puppet has. They are the wrong answer for a lattice: they throw away the grid structure
 * that makes locality and subdivision tractable, and they need a linear solve per pose. That is a
 * different {@link MeshDeformer}, which is the whole reason this is an interface.</p>
 *
 * <h3>Boundary rule — the part that is easy to get wrong</h3>
 * <p>A Catmull-Rom segment needs a control on each side of the two it spans, so the edge segments
 * need ghost controls outside the lattice. This file uses <b>linear extrapolation</b>
 * ({@code p[-1] = 2*p[0] - p[1]}), NOT the usual "duplicate the endpoint" clamp. Duplicating the
 * endpoint forces the end tangent to zero, which kills linear precision on the first and last
 * segment — and with it the subdivision guarantee, and with it even the claim that a 2x2 lattice is
 * a plain bilinear map. Every exactness claim in this package rests on {@link #addTap}.</p>
 *
 * <h3>Threading</h3>
 * <p>Holds scratch. One instance per thread; a renderer keeps one for the life of its GL thread.
 * Allocates nothing after construction.</p>
 *
 * <p>No Android imports.</p>
 */
public final class LatticeDeformer implements MeshDeformer {

    /**
     * How far one control point may be nudged, in units of the object's own size.
     *
     * <p>A LIMIT for the same reason {@code CornerPin.MAX_OFFSET} and {@code KeyframeSet.POS_ABS}
     * are limits: a stray drag or a bad keyframe must not be able to demand a vertex a thousand
     * widths away and hand the rasteriser a degenerate triangle.</p>
     */
    public static final float MAX_NUDGE = 0.85f;

    private final float[] wu = new float[LatticeTopology.MAX_SIDE];
    private final float[] wv = new float[LatticeTopology.MAX_SIDE];
    private final float[] t2 = new float[2];

    // ── MeshDeformer ────────────────────────────────────────────────────

    @Override
    public boolean supports(MeshTopology topology) {
        return topology instanceof LatticeTopology;
    }

    @Override
    public boolean isIdentity(float[] handleValues) {
        return isIdentityPose(handleValues);
    }

    /** Static form, so a gate can test a pose before any deformer instance exists. */
    public static boolean isIdentityPose(float[] handleValues) {
        if (handleValues == null) return true;
        for (float v : handleValues) {
            if (v != 0f) return false;
        }
        return true;
    }

    /** A nudge lattice's identity is all zeros — no point has been dragged anywhere. */
    @Override
    public void identityPose(MeshTopology topology, float[] out) {
        if (out != null) java.util.Arrays.fill(out, 0f);
    }

    @Override
    public float clampComponent(float v) {
        if (v != v) return 0f;                                  // NaN in, zero out
        return v < -MAX_NUDGE ? -MAX_NUDGE : (v > MAX_NUDGE ? MAX_NUDGE : v);
    }

    /**
     * Deform every vertex. Reads {@code buffers.rest}, writes {@code buffers.positions}.
     *
     * <p>The v-axis weights are recomputed only when the rest v actually changes. A row-major grid
     * hits that cache on every vertex but the first of each row, which is where the "same cost per
     * vertex at 5x5 as at 3x3" claim comes from. The check is on the DATA, not on a grid stride, so
     * it stays correct for a topology that has no rows at all — it simply stops helping.</p>
     */
    @Override
    public boolean solve(MeshTopology topology, MeshBuffers buffers, float[] handleValues) {
        if (!(topology instanceof LatticeTopology) || buffers == null) return false;
        int side = ((LatticeTopology) topology).side();
        int n = buffers.vertexCount();
        float[] rest = buffers.rest, pos = buffers.positions;
        if (handleValues == null || handleValues.length != side * side * 2) {
            System.arraycopy(rest, 0, pos, 0, n * 2);
            return handleValues == null;
        }
        float lastV = Float.NaN;
        for (int i = 0; i < n; i++) {
            int k = i * 2;
            float u = rest[k], v = rest[k + 1];
            if (v != lastV) {
                axisWeights(side, v, wv);
                lastV = v;
            }
            axisWeights(side, u, wu);
            evalWeighted(handleValues, side, wu, wv, t2);
            pos[k] = u + t2[0];
            pos[k + 1] = v + t2[1];
        }
        return true;
    }

    // ── The map itself ──────────────────────────────────────────────────

    /**
     * The deformation map: where the picture's point {@code (u,v)} actually lands, still in unit
     * space, before any homography or placement.
     *
     * <p>{@code D(u,v) = (u,v) + CatmullRom(nudges)(u,v)}. An identity lattice gives back
     * {@code (u,v)} exactly, and a lattice knot lands exactly on its own nudged position — an
     * interpolating spline passes through its controls, which is the point of choosing one.</p>
     *
     * <p>Not on {@link MeshDeformer}: evaluating the map at an arbitrary parameter is a lattice
     * idea. A puppet solve has no such thing, and does not need one — the guard, the pose track and
     * the buffers all work on the SOLVED vertices, never on a continuous map.</p>
     */
    public void eval(float[] handles, int side, float u, float v, float[] out2) {
        if (handles == null || side < 2) {
            out2[0] = u;
            out2[1] = v;
            return;
        }
        axisWeights(side, u, wu);
        axisWeights(side, v, wv);
        evalWeighted(handles, side, wu, wv, out2);
        out2[0] += u;
        out2[1] += v;
    }

    /** The nudge field alone, from weights already computed for each axis. */
    static void evalWeighted(float[] off, int side, float[] wu, float[] wv, float[] out2) {
        float ox = 0f, oy = 0f;
        for (int r = 0; r < side; r++) {
            float cw = wv[r];
            if (cw == 0f) continue;
            int base = r * side * 2;
            float rx = 0f, ry = 0f;
            for (int c = 0; c < side; c++) {
                float w = wu[c];
                if (w == 0f) continue;
                rx += w * off[base + c * 2];
                ry += w * off[base + c * 2 + 1];
            }
            ox += cw * rx;
            oy += cw * ry;
        }
        out2[0] = ox;
        out2[1] = oy;
    }

    /**
     * The Catmull-Rom basis for one axis, EXPANDED over the real controls — {@code w} receives one
     * weight per lattice control, with the ghost controls' linear extrapolation already folded in.
     *
     * <p>Expanding to a dense length-{@code side} weight vector (side is 2, 3 or 5) rather than
     * carrying four taps plus index gymnastics costs at most 25 multiply-adds per evaluated vertex,
     * makes the ghost rule impossible to get wrong at a corner (where BOTH axes extrapolate at
     * once), and makes linear precision inspectable — for a 2x2 lattice the weights come out
     * exactly {@code {1-t, t}}, which is what makes level 1 a plain bilinear map and what makes
     * 2x2 -&gt; 3x3 subdivision provably exact.</p>
     */
    static void axisWeights(int side, float p, float[] w) {
        for (int i = 0; i < side; i++) w[i] = 0f;
        int n = side - 1;                              // segments
        float g = p * n;
        int seg = (int) Math.floor(g);
        if (seg < 0) seg = 0;
        if (seg > n - 1) seg = n - 1;
        float t = g - seg;
        float tt = t * t, ttt = tt * t;
        // Uniform Catmull-Rom (tension 1/2) as a cubic basis over controls seg-1 .. seg+2.
        addTap(w, side, seg - 1, -0.5f * ttt + tt - 0.5f * t);
        addTap(w, side, seg,      1.5f * ttt - 2.5f * tt + 1f);
        addTap(w, side, seg + 1, -1.5f * ttt + 2f * tt + 0.5f * t);
        addTap(w, side, seg + 2,  0.5f * ttt - 0.5f * tt);
    }

    /**
     * Accumulate one basis tap, resolving a ghost index by LINEAR EXTRAPOLATION:
     * {@code p[-1] = 2*p[0] - p[1]} and {@code p[side] = 2*p[side-1] - p[side-2]}.
     *
     * <p>{@code k} is only ever in {@code -1 .. side} (from the four taps above), so one level of
     * extrapolation is all that can be asked for. See the class note for why the usual
     * duplicate-the-endpoint clamp would silently break every exactness claim in this package.</p>
     */
    private static void addTap(float[] w, int side, int k, float c) {
        if (c == 0f) return;
        if (k < 0) {
            w[0] += 2f * c;
            w[1] -= c;
        } else if (k >= side) {
            w[side - 1] += 2f * c;
            w[side - 2] -= c;
        } else {
            w[k] += c;
        }
    }

    // ── Subdivision ─────────────────────────────────────────────────────

    /**
     * Re-express a lattice pose at a different level WITHOUT changing the deformation it describes.
     *
     * <h3>The rule</h3>
     * <pre>  off'[i][j] = D_current(u_j, v_i) - (u_j, v_i)</pre>
     * <p>Every new control point is placed at the value the CURRENT map already gives at that
     * point's parameter location. Old points land on themselves — an interpolating spline passes
     * through its own controls, so {@code D_current} at an old knot IS that knot's stored value.
     * New points land exactly on the surface currently being drawn.</p>
     *
     * <h3>Why 2x2 -&gt; 3x3 is EXACT, not "close"</h3>
     * <p><b>Fact 1: at {@code side == 2} the map is bilinear.</b> With linear extrapolation at the
     * ends, the four taps over a two-control axis collapse to {@code {1-t, t}} exactly. Expand
     * {@code w[0] = 2*b(-1) + b(0) - b(2)} and {@code w[1] = -b(-1) + b(1) + 2*b(2)} with the cubic
     * basis above and every {@code t^3} and {@code t^2} term cancels. So each axis interpolates
     * linearly and the tensor product is bilinear, component-wise:
     * {@code O(u,v) = a + b*u + c*v + d*u*v}.</p>
     * <p><b>Fact 2: the tensor product has bilinear precision.</b> The weights sum to 1 and
     * reproduce linears per axis — {@code sum_i w_i(u) * (alpha + beta*u_i) = alpha + beta*u} for
     * uniform knots. That is exactly what "linear precision" means. Feed the tensor product samples
     * of a bilinear {@code F = a + b*u + c*v + d*u*v}:</p>
     * <pre>  sum_j w_j(v) sum_i w_i(u) F(u_i, v_j)
     *    = sum_j w_j(v) [ a + b*u + (c + d*u) * v_j ]      (u-sum, by linear precision)
     *    = a + b*u + c*v + d*u*v                            (v-sum, likewise)
     *    = F(u, v)</pre>
     * <p>Sampling a bilinear map at {@code {0, 1/2, 1}^2} and re-fitting a 3x3 lattice therefore
     * reproduces the original map at EVERY point, not merely at the knots. Zero error. Pinned by
     * sweeping 65x65 parameter values and asserting agreement to 1e-6, which is float noise rather
     * than a tolerance.</p>
     *
     * <h3>Why 3x3 -&gt; 5x5 is exact only to O(h^4), and why there is no correction pass</h3>
     * <p>A 3x3 map is piecewise cubic per axis. Catmull-Rom reproduces quadratics exactly (its
     * divided-difference tangent {@code (p[k+1]-p[k-1])/2} is the true derivative of a quadratic)
     * but not cubics (for {@code p[k] = k^3} it gives {@code 3k^2+1} where the truth is
     * {@code 3k^2}), so a half-spacing resample leaves a residual.</p>
     * <p>SPEC_20260902 §3.4 proposed three Gauss-Seidel sweeps to drive that residual to zero.
     * <b>That correction is arithmetically a no-op and is deliberately not implemented.</b> The
     * sweep is {@code p' += D_current(u_ij,v_ij) - D_new(u_ij,v_ij)}, but the new lattice's knots
     * are precisely where the new interpolant passes through its own controls, so
     * {@code D_new(u_ij,v_ij) == p'[i][j]} identically and every sweep adds exactly zero. That is
     * not a slip in the spec's algebra; it is what "interpolating" means. The residual lives
     * strictly BETWEEN knots, and no adjustment of control values under an interpolatory basis can
     * remove it without moving the dots off the places the user put them — which is the one thing
     * direct manipulation may not do.</p>
     * <p>So the residual is MEASURED instead of pretended away, and it turns out to be a clean
     * constant. Interpolation and resampling are both linear operators in the control values, so
     * the error {@code D_new - D_current} is a linear functional of the pose; the harness finds</p>
     * <pre>  max |D_before - D_after|  ==  |nudge| / 32     exactly, and linearly</pre>
     * <p>for a single dragged dot, and under 0.04 of the unit square for the worst pose out of four
     * thousand random ones that the fold guard will actually let through. In pixels: a bend of ten
     * per cent of the picture's own width shifts a full-frame 1080-wide photo by 3.4 px at the one
     * worst point on the whole surface, and less everywhere else. <b>The press people actually make
     * — L1 to L2, from an untouched picture — is exact; this cost exists only on the second
     * press.</b></p>
     * <p>The alternative would be a least-squares refit, which trades a smaller picture shift for
     * control points that no longer sit where the thumb left them. That is the wrong trade for a
     * direct-manipulation tool, so it is not made here. It is recorded rather than hidden because
     * "the picture does not change" is a claim someone will one day test with a screenshot diff.</p>
     *
     * <h3>Coarsening is lossy and says so</h3>
     * <p>5x5 -&gt; 3x3 runs the same rule and cannot preserve a bend only the fine points express.
     * The operation exists because a level change must be reversible for undo to restore a whole
     * spec, but there is no "- Coarser" button. If one is ever added it must confirm.</p>
     *
     * @param out at least {@code newSide*newSide*2} floats
     */
    public void subdivide(float[] handles, int side, int newSide, float[] out) {
        if (handles == null || side < 2) {
            java.util.Arrays.fill(out, 0, newSide * newSide * 2, 0f);
            return;
        }
        for (int r = 0; r < newSide; r++) {
            float v = LatticeTopology.baseV(r, newSide);
            for (int c = 0; c < newSide; c++) {
                float u = LatticeTopology.baseU(c, newSide);
                eval(handles, side, u, v, t2);
                int k = (r * newSide + c) * 2;
                out[k] = clampComponent(t2[0] - u);
                out[k + 1] = clampComponent(t2[1] - v);
            }
        }
    }

    /**
     * Move a whole spec — static pose and every keyframed pose together — to a new level.
     *
     * <p>All poses move at once, which is the invariant that makes a mixed-arity track
     * ("keyframe A is 9 points, keyframe B is 25") unrepresentable rather than merely discouraged.
     * The generic half of this lives on {@link MeshWarpSpec#retopologize}; only the resampling rule
     * is lattice-specific, and only that part is here.</p>
     *
     * @return false when the spec is not a lattice, or the level is already that
     */
    public boolean setLevel(MeshWarpSpec spec, int newLevel) {
        if (spec == null || !(spec.topology() instanceof LatticeTopology)) return false;
        final int oldSide = ((LatticeTopology) spec.topology()).side();
        LatticeTopology to = new LatticeTopology(newLevel);
        if (to.side() == oldSide) return false;
        final int newSide = to.side();
        return spec.retopologize(to, new MeshPoseTrack.Remapper() {
            @Override public float[] remap(float[] pose) {
                float[] out = new float[newSide * newSide * 2];
                subdivide(pose, oldSide, newSide, out);
                return out;
            }
        });
    }
}
