package com.fadcam.ui.faditor.transform.mesh;

/**
 * THE MESH MATHS. Catmull–Rom lattice evaluation, tessellation, shape-preserving subdivision and
 * the fold guard — every number the bend net produces, in one file.
 *
 * <p><b>Deliberately dependency-free.</b> Not one Android import, not one FadCam import — the same
 * discipline {@code TransformQuad} keeps, and for the same three reasons: it runs on the desktop
 * JVM harness so the maths is provable off device; two renderers (preview and export) can share
 * ONE authority for vertices so a preview cannot start lying about what an export will produce;
 * and the whole thing lifts into another app whole.</p>
 *
 * <h3>What a lattice is</h3>
 * <p>A {@code side x side} grid of control points over the object's OWN UNIT SQUARE — {@code (0,0)}
 * top-left to {@code (1,1)} bottom-right, before any placement, rotation, scale or corner pin.
 * {@code side = (1 << level) + 1}, so level 1/2/3 give 2x2, 3x3, 5x5. One rule, no table.</p>
 *
 * <p><b>What is stored is a NUDGE, not a position.</b> {@code off[2i], off[2i+1]} is the
 * {@code (du,dv)} that control point {@code i} has been dragged by, in unit space. All zeros is
 * "no bend". This is the property the owner tested in the interaction prototype and specifically
 * asked for: because the nudge lives in the object's own space and is re-projected through the
 * CURRENT quad homography every frame, a structural edit — scale an edge, tilt a corner, pinch,
 * fold — <i>carries</i> the bend instead of wiping it. Storing absolute canvas positions is exactly
 * the design that made "scaling the edge undid my bend" happen in the prototype's first round.</p>
 *
 * <p>Nudges and absolute control positions describe the SAME map, incidentally, and that is not a
 * coincidence — see {@link #eval}. The deformation is
 * {@code D(u,v) = (u,v) + CatmullRom(off)(u,v)}, and because the Catmull–Rom tensor product
 * reproduces any bilinear function exactly (§"linear precision" below), interpolating the absolute
 * points {@code base + off} gives {@code base + CatmullRom(off)} — identical. Storing the nudge
 * simply makes "identity == all zeros" a one-line test instead of a floating-point comparison
 * against a computed base grid.</p>
 *
 * <h3>Why Catmull–Rom</h3>
 * <ul>
 *   <li><b>It interpolates.</b> The picture goes EXACTLY where the thumb put the dot. A B-spline
 *       only approximates its controls, the image lags the handle, and users read that as broken.</li>
 *   <li><b>It is separable and local.</b> Weights per axis, evaluated once and reused down a row.</li>
 *   <li><b>It has linear precision</b> — the property that makes subdivision shape-preserving
 *       (see {@link #subdivide}). This is the reason to pick it over a uniform cubic B-spline, not
 *       an incidental nicety.</li>
 * </ul>
 *
 * <h3>Boundary rule — the part that is easy to get wrong</h3>
 * <p>A Catmull–Rom segment needs a control on each side of the two it spans, so the edge segments
 * need ghost controls outside the lattice. This file uses <b>linear extrapolation</b>
 * ({@code p[-1] = 2*p[0] - p[1]}), NOT the usual "duplicate the endpoint" clamp. Duplicating the
 * endpoint kills linear precision on the first and last segment, and with it the whole
 * subdivide-preserves-shape guarantee — a 2x2 lattice would not even reproduce a plain bilinear
 * map. See {@link #addTap}.</p>
 *
 * <h3>Allocation</h3>
 * <p>Nothing here allocates. {@link #tessellate} and {@link #eval} write into caller-provided
 * arrays and take a caller-provided {@link Scratch}; a renderer calls them every frame.
 * {@link MeshTessellator} is the convenience that owns those buffers for you.</p>
 */
public final class MeshWarp {

    private MeshWarp() {}

    // ── Levels ───────────────────────────────────────────────────────────

    /** 2x2 — four corners. The "warp off / corner pin only" level. */
    public static final int L1 = 1;
    /** 3x3 — corners, edge midpoints, centre. The first "+ Finer". */
    public static final int L2 = 2;
    /** 5x5 — the second "+ Finer". */
    public static final int L3 = 3;

    public static final int MIN_LEVEL = L1, MAX_LEVEL = L3;
    /** Largest {@code side} any level produces. Sizes every scratch array in this file. */
    public static final int MAX_SIDE = 5;

    /** {@code side} for a level: 2, 3, 5. Clamps out-of-range levels rather than throwing. */
    public static int sideFor(int level) {
        return (1 << clampLevel(level)) + 1;
    }

    /** Floats in a lattice at this level: {@code side*side*2}. */
    public static int arityFor(int level) {
        int s = sideFor(level);
        return s * s * 2;
    }

    public static int clampLevel(int level) {
        return level < MIN_LEVEL ? MIN_LEVEL : (level > MAX_LEVEL ? MAX_LEVEL : level);
    }

    /** The level whose {@code side} is this, or {@link #L1} for anything unrecognised. */
    public static int levelForSide(int side) {
        if (side == 3) return L2;
        if (side == 5) return L3;
        return L1;
    }

    // ── Limits ───────────────────────────────────────────────────────────

    /**
     * How far one control point may be nudged, in units of the object's own size.
     *
     * <p>The interaction prototype's number, unchanged, because that is the feel the owner tested.
     * It is a LIMIT rather than freedom for the same reason {@code CornerPin.MAX_OFFSET} and
     * {@code KeyframeSet.POS_ABS} are limits: a stray drag or a bad keyframe must not be able to
     * demand a vertex a thousand widths away and hand the rasteriser a degenerate triangle.</p>
     */
    public static final float MAX_NUDGE = 0.85f;

    /**
     * Tessellation grid (quads per axis) for each level. 1 / 16 / 24, per the spec.
     *
     * <p><b>Tessellation is decoupled from the lattice on purpose.</b> The lattice is what the user
     * drags; the tessellation is how finely the picture is chopped to hide the maths. A 24x24 grid
     * is 1,152 triangles, which is not a cost on any GPU of the last decade — every real cost in
     * this feature is fill rate, and fill rate is set by the frame size, not the triangle count.
     * Do not "optimise" these down: that trades the one free thing for visible faceting.</p>
     */
    public static int tessellationFor(int level) {
        switch (clampLevel(level)) {
            case L3: return 24;
            case L2: return 16;
            default: return 1;   // a 2x2 lattice bends nothing a single quad cannot express
        }
    }

    /** Probe density for {@link #isValid}. Finer than any lattice, cheap enough for a drag. */
    public static final int DEFAULT_PROBE = 12;

    /**
     * Smallest fraction of its undeformed area a mesh cell may shrink to before a drag is refused.
     *
     * <p>An <i>area</i> floor rather than an angle, for the same reason {@code TransformQuad}'s
     * {@code CONVEX_EPS} is one: a cell squeezed to a sliver is not merely ugly, it is the state
     * immediately before it turns inside out, and a cell that has turned inside out smears the
     * picture across the screen. Refusing is always recoverable — the gesture simply stops moving.
     * Rendering through a folded cell is not.</p>
     */
    public static final float MIN_CELL_AREA_FRAC = 0.02f;

    // ── Scratch ──────────────────────────────────────────────────────────

    /**
     * Per-caller working space. Hold ONE of these per thread (a renderer holds one for the life of
     * its GL thread) and pass it in — that is what keeps {@link #eval} and {@link #tessellate}
     * allocation-free on the per-frame path.
     */
    public static final class Scratch {
        final float[] wu = new float[MAX_SIDE];
        final float[] wv = new float[MAX_SIDE];
        final float[] p = new float[8];   // four probe corners for isValid
        final float[] t = new float[2];
    }

    // ── Lattice helpers ──────────────────────────────────────────────────

    /** A fresh identity (all-zero) nudge array for {@code level}. */
    public static float[] identity(int level) {
        return new float[arityFor(level)];
    }

    /** True when every nudge is zero (or the array is null) — "there is no bend here". */
    public static boolean isIdentity(float[] off) {
        if (off == null) return true;
        for (float v : off) {
            if (v != 0f) return false;
        }
        return true;
    }

    /** The undeformed u of lattice column {@code col}. */
    public static float baseU(int col, int side) { return col / (float) (side - 1); }

    /** The undeformed v of lattice row {@code row}. */
    public static float baseV(int row, int side) { return row / (float) (side - 1); }

    /** Flat index of lattice point {@code (row, col)}; multiply by 2 to index {@code off}. */
    public static int index(int row, int col, int side) { return row * side + col; }

    public static float clampNudge(float v) {
        if (v != v) return 0f;                       // NaN in, zero out
        return v < -MAX_NUDGE ? -MAX_NUDGE : (v > MAX_NUDGE ? MAX_NUDGE : v);
    }

    // ── Evaluation ───────────────────────────────────────────────────────

    /**
     * The deformation map: where the picture's point {@code (u,v)} actually lands, still in the
     * object's unit space, before any homography or placement.
     *
     * <p>{@code out2} receives {@code {x, y}}. Both are {@code (u,v)} plus the Catmull–Rom
     * tensor-product interpolation of the nudge lattice, so an identity lattice gives back
     * {@code (u,v)} exactly and a lattice knot lands exactly on its own nudged position (an
     * interpolating spline passes through its controls — that is the point of choosing one).</p>
     *
     * <p>{@code u,v} outside {@code 0..1} are evaluated on the extrapolated edge segment rather
     * than clamped; callers do not need it, but it keeps the map continuous for a probe that
     * steps slightly past an edge.</p>
     */
    public static void eval(float[] off, int side, float u, float v, Scratch s, float[] out2) {
        if (off == null || side < 2) {
            out2[0] = u;
            out2[1] = v;
            return;
        }
        axisWeights(side, u, s.wu);
        axisWeights(side, v, s.wv);
        evalWeighted(off, side, s.wu, s.wv, out2);
        out2[0] += u;
        out2[1] += v;
    }

    /**
     * The nudge field alone (no {@code +(u,v)}), given weights already computed for each axis.
     * Exposed so {@link #tessellate} can compute the v weights ONCE per row.
     */
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
     * The Catmull–Rom basis for one axis, EXPANDED over the real controls — {@code w} receives one
     * weight per lattice control, with the ghost controls' linear extrapolation already folded in.
     *
     * <p>Expanding to a dense length-{@code side} weight vector (side is 2, 3 or 5) rather than
     * carrying four taps plus index gymnastics is the deliberate simplification here: it costs at
     * most 25 multiply-adds per evaluated vertex, it makes the ghost rule impossible to get wrong
     * at a corner (where BOTH axes extrapolate at once), and it makes linear precision inspectable
     * — for a 2x2 lattice the weights come out exactly {@code {1-t, t}}, which is what makes level
     * 1 a plain bilinear map and what makes 2x2 → 3x3 subdivision provably exact.</p>
     *
     * <p>{@code w} must be at least {@code side} long. It is fully overwritten.</p>
     */
    static void axisWeights(int side, float p, float[] w) {
        for (int i = 0; i < side; i++) w[i] = 0f;
        int n = side - 1;                       // segments
        float g = p * n;
        int seg = (int) Math.floor(g);
        if (seg < 0) seg = 0;
        if (seg > n - 1) seg = n - 1;
        float t = g - seg;
        float t2 = t * t, t3 = t2 * t;
        // Uniform Catmull-Rom (tension 1/2), as a cubic basis over controls seg-1 .. seg+2.
        addTap(w, side, seg - 1, -0.5f * t3 + t2 - 0.5f * t);
        addTap(w, side, seg,      1.5f * t3 - 2.5f * t2 + 1f);
        addTap(w, side, seg + 1, -1.5f * t3 + 2f * t2 + 0.5f * t);
        addTap(w, side, seg + 2,  0.5f * t3 - 0.5f * t2);
    }

    /**
     * Accumulate one basis tap, resolving a ghost index by LINEAR EXTRAPOLATION.
     *
     * <p>{@code p[-1] = 2*p[0] - p[1]} and {@code p[side] = 2*p[side-1] - p[side-2]}. The usual
     * alternative — duplicating the endpoint — is wrong here and the failure is subtle: a
     * duplicated endpoint makes the end tangent zero, so the first and last segments flatten out,
     * the scheme loses linear precision exactly where the corner handles live, and a 2x2 lattice
     * stops being a bilinear map. Every exactness claim in {@link #subdivide} rests on this line.</p>
     *
     * <p>{@code k} is only ever in {@code -1 .. side} (from the four taps above), so one level of
     * extrapolation is all that can be asked for.</p>
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

    // ── Tessellation ─────────────────────────────────────────────────────

    /** Vertices a {@code gridN x gridN} tessellation emits. */
    public static int vertexCount(int gridN) { return (gridN + 1) * (gridN + 1); }

    /** Floats {@code outPos} / {@code outUv} each need: {@code 2 * vertexCount}. */
    public static int coordCount(int gridN) { return vertexCount(gridN) * 2; }

    /** Shorts the index buffer needs: two triangles per quad. */
    public static int indexCount(int gridN) { return gridN * gridN * 6; }

    /**
     * Fill the triangle mesh a GL renderer draws.
     *
     * <p>{@code outPos} receives the DEFORMED unit-space position of each vertex — the renderer
     * pushes that through the corner-pin homography and then the placement matrix, in that order
     * (mesh first, homography second; see the spec §5.1). {@code outUv} receives the UNdeformed
     * {@code (u,v)}, which is the source texture coordinate and never changes for a given
     * {@code gridN} — a renderer may upload it once and only re-upload {@code outPos} per frame.</p>
     *
     * <p>Row-major, {@code (gridN+1)} vertices per row, row 0 at {@code v = 0}. Nothing is
     * allocated: both arrays and the {@link Scratch} are the caller's.</p>
     *
     * @param off    the nudge lattice, or null for identity
     * @param side   2, 3 or 5
     * @param gridN  quads per axis — see {@link #tessellationFor}
     * @param outPos at least {@link #coordCount}({@code gridN}) floats
     * @param outUv  at least {@link #coordCount}({@code gridN}) floats, or null to skip
     */
    public static void tessellate(float[] off, int side, int gridN,
                                  float[] outPos, float[] outUv, Scratch s) {
        int stride = gridN + 1;
        float inv = 1f / gridN;
        boolean flat = off == null || side < 2;
        for (int r = 0; r < stride; r++) {
            float v = r * inv;
            if (!flat) axisWeights(side, v, s.wv);
            int rowBase = r * stride * 2;
            for (int c = 0; c < stride; c++) {
                float u = c * inv;
                int k = rowBase + c * 2;
                if (outUv != null) {
                    outUv[k] = u;
                    outUv[k + 1] = v;
                }
                if (flat) {
                    outPos[k] = u;
                    outPos[k + 1] = v;
                } else {
                    axisWeights(side, u, s.wu);
                    evalWeighted(off, side, s.wu, s.wv, s.t);
                    outPos[k] = u + s.t[0];
                    outPos[k + 1] = v + s.t[1];
                }
            }
        }
    }

    /**
     * Fill the index buffer. Depends only on {@code gridN}, so build it once when the tessellation
     * level changes and never again.
     *
     * <p><b>Back-face culling must be OFF in the renderer.</b> The winding is consistent for an
     * un-folded mesh, but a fold — which the edge-fold gesture makes deliberately, and which
     * {@link #isValid} permits at the whole-quad level — reverses it, and a culled fold vanishes
     * instead of showing its back.</p>
     */
    public static void buildIndices(int gridN, short[] out) {
        int stride = gridN + 1;
        int w = 0;
        for (int r = 0; r < gridN; r++) {
            for (int c = 0; c < gridN; c++) {
                int k = r * stride + c;
                out[w++] = (short) k;
                out[w++] = (short) (k + stride);
                out[w++] = (short) (k + 1);
                out[w++] = (short) (k + 1);
                out[w++] = (short) (k + stride);
                out[w++] = (short) (k + stride + 1);
            }
        }
    }

    // ── Fold / validity guard ────────────────────────────────────────────

    /**
     * Would this lattice draw a mesh the maths can actually render — no cell turned inside out, no
     * cell squeezed to a sliver, every coordinate finite?
     *
     * <p>Same idea as {@code TransformQuad.isConvex}, applied one level down: there, four corners
     * folding through each other hand the homography solver a near-singular problem; here, a cell
     * folding through itself makes the rasteriser draw the picture backwards over its neighbour,
     * which the interaction prototype found "smears the picture across the screen". The cure is the
     * same — refuse the drag rather than render the result.</p>
     *
     * <p>The probe is FINER than the lattice on purpose. A Catmull–Rom cell can bulge past its own
     * four corners, so checking only the lattice corners would pass a mesh whose interior has
     * already folded. {@link #DEFAULT_PROBE} at 12 gives 144 cells and 576 map evaluations — a few
     * tens of microseconds, paid once per drag frame, never per rendered frame.</p>
     *
     * <p>Unlike {@code TransformQuad.isConvex} this is NOT winding-agnostic: it requires every cell
     * to keep the identity map's positive orientation. That is correct here because the mesh's
     * winding is not the place a deliberate flip is expressed — an edge fold reflects the QUAD
     * (through the homography), and the lattice underneath it is unchanged. A lattice that has
     * reversed its own orientation has folded, full stop.</p>
     */
    public static boolean isValid(float[] off, int side, int probeN, Scratch s) {
        if (off == null) return true;
        for (float v : off) {
            if (Float.isNaN(v) || Float.isInfinite(v) || Math.abs(v) > MAX_NUDGE + 1e-4f) {
                return false;
            }
        }
        if (probeN < 1) probeN = 1;
        float h = 1f / probeN;
        float minArea = MIN_CELL_AREA_FRAC * h * h;
        float[] p = s.p;
        for (int r = 0; r < probeN; r++) {
            float v0 = r * h, v1 = (r + 1) * h;
            for (int c = 0; c < probeN; c++) {
                float u0 = c * h, u1 = (c + 1) * h;
                eval(off, side, u0, v0, s, s.t); p[0] = s.t[0]; p[1] = s.t[1];
                eval(off, side, u1, v0, s, s.t); p[2] = s.t[0]; p[3] = s.t[1];
                eval(off, side, u1, v1, s, s.t); p[4] = s.t[0]; p[5] = s.t[1];
                eval(off, side, u0, v1, s, s.t); p[6] = s.t[0]; p[7] = s.t[1];
                // Both triangles of the cell, in the same winding buildIndices emits.
                if (tri(p, 0, 6, 2) < minArea) return false;
                if (tri(p, 2, 6, 4) < minArea) return false;
            }
        }
        return true;
    }

    /** Convenience at {@link #DEFAULT_PROBE}. */
    public static boolean isValid(float[] off, int side, Scratch s) {
        return isValid(off, side, DEFAULT_PROBE, s);
    }

    /** Twice the signed area of the triangle at packed offsets {@code a,b,c}. */
    private static float tri(float[] p, int a, int b, int c) {
        float abx = p[b] - p[a], aby = p[b + 1] - p[a + 1];
        float acx = p[c] - p[a], acy = p[c + 1] - p[a + 1];
        return (abx * acy - aby * acx) * 0.5f;
    }

    // ── Subdivision ──────────────────────────────────────────────────────

    /**
     * Re-express a lattice at a different level WITHOUT changing the deformation it describes.
     *
     * <h3>The rule</h3>
     * <p>Every control point of the new lattice is placed at the value the CURRENT map already
     * gives at that point's parameter location:</p>
     * <pre>  off'[i][j] = D_current(u_j, v_i) - (u_j, v_i)</pre>
     * <p>Old points land on themselves — an interpolating spline passes through its own controls,
     * so {@code D_current} at an old knot IS that knot's stored value. New points land exactly on
     * the surface currently being drawn.</p>
     *
     * <h3>Why 2x2 → 3x3 is EXACT, not "close"</h3>
     * <p>Two facts, and the result follows.</p>
     * <p><b>Fact 1: at {@code side == 2} the map is bilinear.</b> With linear extrapolation at the
     * ends ({@link #addTap}) the four Catmull–Rom taps over a two-control axis collapse to weights
     * {@code {1-t, t}} exactly — expand
     * {@code w[0] = 2b(-1) + b(0) - b(2)} and {@code w[1] = -b(-1) + b(1) + 2b(2)} with the cubic
     * basis and every {@code t^3} and {@code t^2} term cancels. So each axis interpolates linearly
     * and the tensor product is bilinear:
     * {@code O(u,v) = a + b*u + c*v + d*u*v}, component-wise.</p>
     * <p><b>Fact 2: the tensor product has bilinear precision.</b> The weights sum to 1 and
     * reproduce linears per axis, i.e. {@code sum_i w_i(u) * (alpha + beta*u_i) = alpha + beta*u}
     * for uniform knots — that is what "linear precision" means and it is why Catmull–Rom was
     * chosen over a B-spline. Feed the tensor product samples of a bilinear function
     * {@code F = a + b*u + c*v + d*u*v}:</p>
     * <pre>  sum_j w_j(v) sum_i w_i(u) F(u_i, v_j)
     *   = sum_j w_j(v) [ a + b*u + (c + d*u) * v_j ]        (u-sum, by linear precision)
     *   = a + b*u + c*v + d*u*v                              (v-sum, likewise)
     *   = F(u, v)</pre>
     * <p>Sampling a bilinear map at {@code {0, 1/2, 1}^2} and re-fitting a 3x3 Catmull–Rom lattice
     * therefore reproduces the original map at EVERY point, not just at the knots. Zero error —
     * pinned in the harness by sweeping 65x65 parameter values and asserting agreement to 1e-6
     * (which is float noise, not tolerance).</p>
     *
     * <h3>Why 3x3 → 5x5 is exact only to O(h^4) — and what that actually measures</h3>
     * <p>A 3x3 map is piecewise cubic per axis. Catmull–Rom reproduces quadratics exactly (its
     * divided-difference tangent {@code (p[k+1]-p[k-1])/2} is the true derivative of a quadratic)
     * but NOT cubics (for {@code p[k] = k^3} it gives {@code 3k^2+1} where the truth is
     * {@code 3k^2}), so resampling a piecewise-cubic map at half spacing leaves a residual.</p>
     * <p>The spec proposed a Gauss–Seidel correction of the control points to drive that residual
     * to zero. <b>That correction is a no-op and this file deliberately does not implement it.</b>
     * The correction step is {@code p' += D_current(u_ij,v_ij) - D_new(u_ij,v_ij)}, but the new
     * lattice's own knots are exactly where the new interpolant passes through its controls, so
     * {@code D_new(u_ij,v_ij) == p'[i][j]} identically and every sweep after the first resample
     * adds exactly zero. That is not a bug in the spec's arithmetic, it is what "interpolating"
     * means; the residual lives strictly BETWEEN knots and no adjustment of control values under an
     * interpolatory basis can remove it without also moving the dots off the places the user put
     * them, which is the one thing direct manipulation may not do.</p>
     * <p>So the honest engineering answer is to bound the residual instead of pretending to cancel
     * it. Both the interpolation and the resample are LINEAR operators in the control values, so
     * the whole error {@code D_new - D_current} is a linear functional of the nudge lattice and the
     * worst-case deviation is exactly proportional to the nudge magnitude. The harness measures the
     * constant directly, on the largest bend the UI permits ({@link #MAX_NUDGE} on every point, in
     * an adversarial checkerboard), and asserts it stays under 1e-3 of the unit square — about one
     * pixel at 1080p, on a deformation no user will ever author. On a realistic bend it is two
     * orders of magnitude smaller. That claim is measured, not asserted.</p>
     *
     * <h3>Coarsening is lossy and says so</h3>
     * <p>Going 5x5 → 3x3 runs the same rule and cannot preserve a bend only the fine points express.
     * The operation is offered (a level change must be able to go both ways for undo to restore a
     * whole spec) but there is no "– Coarser" button, and if one is ever added it must confirm.</p>
     *
     * @param off     source nudges, or null for identity
     * @param side    source side
     * @param newSide destination side
     * @param out     destination nudges, at least {@code newSide*newSide*2} floats
     */
    public static void subdivide(float[] off, int side, int newSide, float[] out, Scratch s) {
        if (off == null || side < 2) {
            java.util.Arrays.fill(out, 0, newSide * newSide * 2, 0f);
            return;
        }
        for (int r = 0; r < newSide; r++) {
            float v = baseV(r, newSide);
            for (int c = 0; c < newSide; c++) {
                float u = baseU(c, newSide);
                eval(off, side, u, v, s, s.t);
                int k = (r * newSide + c) * 2;
                out[k] = clampNudge(s.t[0] - u);
                out[k + 1] = clampNudge(s.t[1] - v);
            }
        }
    }

    // ── Interpolation between whole lattices ─────────────────────────────

    /**
     * Component-wise blend of two lattices of the SAME arity — the one operation a mesh keyframe
     * needs. {@code t} is the already-eased progress.
     */
    public static void lerp(float[] a, float[] b, float t, float[] out) {
        int n = Math.min(out.length, Math.min(a.length, b.length));
        for (int i = 0; i < n; i++) {
            out[i] = a[i] + (b[i] - a[i]) * t;
        }
    }

    // ── The projection seam (see TransformQuad's "Where the mesh/bend lane joins") ──

    /**
     * Where a lattice control point is DRAWN: nudge it, then push it through the quad's homography.
     *
     * <p>This is the forward half of the seam {@code TransformQuad} documents. The handle overlay
     * calls this to place a bend dot; the reason it is here and not in the overlay is that the
     * inverse ({@link #stageToUnit}) must be its exact mirror or a dot will not land under the
     * thumb.</p>
     *
     * @param h    the row-major 3x3 from {@code TransformQuad.unitToQuad}
     * @param out2 receives the stage-space {x, y}
     * @return false when the homography is degenerate — the caller draws nothing rather than
     *         drawing at infinity
     */
    public static boolean projectPoint(float[] off, int side, int row, int col,
                                       float[] h, Scratch s, float[] out2) {
        float u = baseU(col, side), v = baseV(row, side);
        int k = index(row, col, side) * 2;
        float du = off == null ? 0f : off[k];
        float dv = off == null ? 0f : off[k + 1];
        return applyHomography(h, u + du, v + dv, out2);
    }

    /**
     * The inverse half of the seam: stage point → the object's unit space, so a drag can be turned
     * into a nudge. {@code hInv} is {@code TransformQuad.invert3x3(unitToQuad(quad))}.
     */
    public static boolean stageToUnit(float[] hInv, float x, float y, float[] out2) {
        return applyHomography(hInv, x, y, out2);
    }

    /**
     * Push {@code (u,v)} through a row-major 3x3.
     *
     * <p>Duplicated from {@code TransformQuad.applyHomography} rather than called, and that is
     * deliberate: this file's whole value is that it has no imports at all, so it loads on the JVM
     * harness and lifts into another app whole. Twelve lines of arithmetic is a cheaper price than
     * a dependency. The two are pinned equal by a harness test.</p>
     */
    public static boolean applyHomography(float[] m, float u, float v, float[] out2) {
        if (m == null) return false;
        float w = m[6] * u + m[7] * v + m[8];
        if (!finite(w) || Math.abs(w) < 1e-9f) return false;
        out2[0] = (m[0] * u + m[1] * v + m[2]) / w;
        out2[1] = (m[3] * u + m[4] * v + m[5]) / w;
        return finite(out2[0]) && finite(out2[1]);
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
