package com.fadcam.ui.faditor.transform;

/**
 * THE GEOMETRY. A four-cornered quad and every operation the transform overlay performs on it.
 *
 * <p><b>Deliberately dependency-free.</b> Not one Android import, not one FadCam import — this is
 * plain arithmetic on a {@code float[8]}, so it can be unit-tested on a desktop JVM and lifted
 * into another app whole. Everything that knows about {@code TextOverlayItem}, {@code CornerPin},
 * keyframes or undo lives in the adapter; everything that knows about {@code Canvas} and
 * {@code MotionEvent} lives in the View. This file knows about points.</p>
 *
 * <p><b>Corner order is TL, TR, BR, BL</b> — clockwise from the top-left — everywhere, which is
 * the same order {@code android.graphics.Matrix.setPolyToPoly} and {@code CornerPin} are fed, so
 * a quad from here indexes straight into an offsets array with no re-ordering step to get wrong.
 * A quad is packed {@code {x0,y0, x1,y1, x2,y2, x3,y3}}.</p>
 *
 * <p><b>Edge order is TOP, RIGHT, BOTTOM, LEFT.</b> {@link #EDGE_CORNERS} gives the two corners
 * on an edge and {@link #EDGE_OPPOSITE} the two on the edge facing it.</p>
 *
 * <h3>The one thing to understand before changing anything here</h3>
 * <p>{@link #scaleCorner} and {@link #scaleEdge} are the two routines that were rewritten during
 * the prototype rounds and they are rewritten for the same reason. The naive version of either
 * rebuilds the quad from two basis vectors as {@code origin + u*a + v*b}, which <b>forces a
 * parallelogram</b> and therefore silently destroys any trapezoid or free distortion the other
 * corners were holding: pull one corner out to a perspective taper, then touch a scale handle,
 * and the taper snaps flat. The cure in both is the same three steps — freeze the frame at grab
 * time, read <i>every</i> corner into that frame as its own (a, b) pair, and rebuild each corner
 * from <i>its own</i> coordinates with only the basis lengths multiplied. The anchor corner (or
 * edge) sits at coordinate zero and therefore cannot move, every other corner keeps its relative
 * position, and a trapezoid stays a trapezoid — only bigger or smaller.</p>
 *
 * <h3>Where the mesh/bend lane joins</h3>
 * <p>{@link #unitToQuad}, {@link #applyHomography} and {@link #invert3x3} are the seam. A bend net
 * stores its control-point nudges in the quad's OWN unit space (the 0..1 square) and pushes them
 * through the current homography every frame; that is the whole reason scaling an edge does not
 * wipe a bend. Those three functions are the projection. See {@code tasks/SPEC_20260902_MESH_WARP.md}.</p>
 */
public final class TransformQuad {

    private TransformQuad() {}

    // ── Indices ──────────────────────────────────────────────────────────

    public static final int TL = 0, TR = 1, BR = 2, BL = 3;
    public static final int TOP = 0, RIGHT = 1, BOTTOM = 2, LEFT = 3;

    /** The two corners sitting on each edge, in TOP/RIGHT/BOTTOM/LEFT order. */
    public static final int[][] EDGE_CORNERS = {{TL, TR}, {TR, BR}, {BL, BR}, {TL, BL}};
    /** The two corners on the edge FACING each edge, index-aligned with {@link #EDGE_CORNERS}. */
    public static final int[][] EDGE_OPPOSITE = {{BL, BR}, {TL, BL}, {TL, TR}, {TR, BR}};

    /**
     * How far one drag may multiply a dimension in a single gesture.
     *
     * <p>Clamped rather than free because the drag factor is a RATIO against the grab-time size,
     * and a corner dragged through its own anchor makes that ratio pass through zero and come out
     * negative — which is a shape the homography solver cannot draw and the user cannot read. The
     * bounds are the prototype's, unchanged.</p>
     */
    public static final float MIN_FACTOR = 0.12f, MAX_FACTOR = 3.4f;

    /**
     * Minimum absolute cross product between consecutive edges for a corner to count as a real
     * turn rather than a fold-through or a collapse to a line.
     *
     * <p>In squared pixel units, so it is a genuine area floor and not an angle: three corners
     * within a whisker of collinear give the homography solver a near-singular problem, and what
     * comes back is a matrix that draws the picture across half the screen. Rejecting is always
     * recoverable (the gesture simply stops moving); drawing through a degenerate matrix is not.</p>
     */
    private static final float CONVEX_EPS = 12f;

    // ── Basic accessors ──────────────────────────────────────────────────

    public static float x(float[] q, int corner) { return q[corner * 2]; }
    public static float y(float[] q, int corner) { return q[corner * 2 + 1]; }

    public static void set(float[] q, int corner, float x, float y) {
        q[corner * 2] = x;
        q[corner * 2 + 1] = y;
    }

    public static float[] copy(float[] q) {
        float[] out = new float[8];
        System.arraycopy(q, 0, out, 0, 8);
        return out;
    }

    /** Centroid of the four corners. {@code out} receives {x, y}. */
    public static void centroid(float[] q, float[] out) {
        float cx = 0f, cy = 0f;
        for (int i = 0; i < 4; i++) { cx += q[i * 2]; cy += q[i * 2 + 1]; }
        out[0] = cx / 4f;
        out[1] = cy / 4f;
    }

    /** Midpoint of edge {@code e}. {@code out} receives {x, y}. */
    public static void edgeMid(float[] q, int e, float[] out) {
        int a = EDGE_CORNERS[e][0], b = EDGE_CORNERS[e][1];
        out[0] = (q[a * 2] + q[b * 2]) / 2f;
        out[1] = (q[a * 2 + 1] + q[b * 2 + 1]) / 2f;
    }

    /** Length of the shortest edge — the scale the handle spacing and hit radii key off. */
    public static float minEdgeLength(float[] q) {
        float m = Float.MAX_VALUE;
        for (int e = 0; e < 4; e++) {
            int a = EDGE_CORNERS[e][0], b = EDGE_CORNERS[e][1];
            m = Math.min(m, (float) Math.hypot(q[a * 2] - q[b * 2], q[a * 2 + 1] - q[b * 2 + 1]));
        }
        return m;
    }

    // ── Validity ─────────────────────────────────────────────────────────

    /**
     * Is this a quad the maths can actually draw — strictly convex, no fold-through, no collapse?
     *
     * <p>Winding-agnostic on purpose: it asks only that all four turns go the SAME way, not that
     * they go clockwise. A quad reflected by {@link #foldOverEdge} winds the other way and is
     * perfectly drawable; demanding one winding would have rejected every fold.</p>
     */
    public static boolean isConvex(float[] q) {
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            float ax = q[i * 2], ay = q[i * 2 + 1];
            float bx = q[((i + 1) % 4) * 2], by = q[((i + 1) % 4) * 2 + 1];
            float cx = q[((i + 2) % 4) * 2], cy = q[((i + 2) % 4) * 2 + 1];
            float cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx);
            if (!isFinite(cross) || Math.abs(cross) < CONVEX_EPS) return false;
            int s = cross > 0 ? 1 : -1;
            if (sign == 0) sign = s;
            else if (s != sign) return false;
        }
        return true;
    }

    /** Every coordinate finite AND the shape convex. The guard every gesture is tested against. */
    public static boolean isValid(float[] q) {
        for (int i = 0; i < 8; i++) if (!isFinite(q[i])) return false;
        return isConvex(q);
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    /** Is {@code (px,py)} inside the quad? Ray-cast, so it is correct at either winding. */
    public static boolean contains(float[] q, float px, float py) {
        boolean in = false;
        for (int i = 0, j = 3; i < 4; j = i++) {
            float yi = q[i * 2 + 1], yj = q[j * 2 + 1];
            float xi = q[i * 2], xj = q[j * 2];
            if ((yi > py) != (yj > py)
                    && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
                in = !in;
            }
        }
        return in;
    }

    // ── The local affine frame that makes scaling non-destructive ────────

    /**
     * Express {@code (px,py)} in the affine frame {@code (origin; u, v)} — i.e. solve
     * {@code p = origin + a*u + b*v} for {@code (a, b)}.
     *
     * <p>This is the whole trick behind {@link #scaleCorner} and {@link #scaleEdge}. Reading a
     * corner into a frame and rebuilding it from its OWN coordinates is what preserves distortion
     * the frame does not describe; rebuilding every corner from the frame's basis alone is what
     * flattens it. Returns false when the basis is degenerate (the two vectors parallel, which is
     * a collapsed quad) rather than dividing by ~0.</p>
     *
     * @param out receives {a, b}
     */
    public static boolean uvCoords(float px, float py, float ox, float oy,
                                   float ux, float uy, float vx, float vy, float[] out) {
        float det = ux * vy - uy * vx;
        if (!isFinite(det) || Math.abs(det) < 1e-9f) return false;
        float dx = px - ox, dy = py - oy;
        out[0] = (dx * vy - dy * vx) / det;
        out[1] = (ux * dy - uy * dx) / det;
        return isFinite(out[0]) && isFinite(out[1]);
    }

    // ── Corner behaviours ────────────────────────────────────────────────

    /**
     * SCALE on a corner — scales the shape AS IT CURRENTLY IS, about the opposite corner.
     *
     * <p>The opposite corner is the origin of a frame frozen at grab time; every corner is read
     * into that frame; the two basis lengths are multiplied by the drag's two factors; every
     * corner is rebuilt from its own coordinates. The anchor sits at (0,0) and cannot move, and
     * an existing trapezoid survives — see the class note.</p>
     *
     * @param q      the live quad, written in place
     * @param q0     the quad as it stood when the finger went down (never mutated)
     * @param corner which corner is being dragged
     * @param tx,ty  where the finger is now
     * @param outFactors optional {@code float[2]}, receives the two applied factors for the HUD
     * @return true if the quad was rewritten
     */
    public static boolean scaleCorner(float[] q, float[] q0, int corner, float tx, float ty,
                                      float[] outFactors) {
        float[] fac = outFactors != null ? outFactors : new float[2];
        if (!scaleCornerFactors(q0, corner, tx, ty, fac)) return false;
        scaleCornerApply(q, q0, corner, fac[0], fac[1]);
        return true;
    }

    /**
     * The two drag factors of a corner SCALE, without applying them: the ratio of the
     * finger's frame coordinates to the dragged corner's own coordinates at grab time,
     * clamped to {@link #MIN_FACTOR}..{@link #MAX_FACTOR}.
     *
     * @param outFactors receives {@code {fa, fb}}
     * @return false when the frame is degenerate (caller keeps the last good pose)
     */
    public static boolean scaleCornerFactors(float[] q0, int corner, float tx, float ty,
                                             float[] outFactors) {
        if (q0 == null || outFactors == null || outFactors.length < 2) return false;
        if (corner < 0 || corner > 3) return false;
        int opp = (corner + 2) % 4;
        float ox = q0[opp * 2], oy = q0[opp * 2 + 1];
        int ui = (corner + 3) % 4, vi = (corner + 1) % 4;
        float ux = q0[ui * 2] - ox, uy = q0[ui * 2 + 1] - oy;
        float vx = q0[vi * 2] - ox, vy = q0[vi * 2 + 1] - oy;

        float[][] c = new float[4][2];
        float[] tmp = new float[2];
        for (int k = 0; k < 4; k++) {
            if (!uvCoords(q0[k * 2], q0[k * 2 + 1], ox, oy, ux, uy, vx, vy, tmp)) return false;
            c[k][0] = tmp[0];
            c[k][1] = tmp[1];
        }
        if (!uvCoords(tx, ty, ox, oy, ux, uy, vx, vy, tmp)) return false;
        // The dragged corner's own coordinates are the denominator of both factors. At ~0 the
        // corner is effectively ON the anchor and the ratio is meaningless — refuse rather than
        // launch the shape to infinity.
        if (Math.abs(c[corner][0]) < 1e-3f || Math.abs(c[corner][1]) < 1e-3f) return false;
        outFactors[0] = clamp(tmp[0] / c[corner][0], MIN_FACTOR, MAX_FACTOR);
        outFactors[1] = clamp(tmp[1] / c[corner][1], MIN_FACTOR, MAX_FACTOR);
        return isFinite(outFactors[0]) && isFinite(outFactors[1]);
    }

    /**
     * Rebuild the quad from the grab-time frame with EXPLICIT factors — the second half
     * of {@link #scaleCorner}, split out so callers (uniform snap) can substitute their
     * own factors. Every corner is rebuilt from its OWN coordinates, so a trapezoid
     * stays a trapezoid at any factor pair — see the class note.
     */
    public static void scaleCornerApply(float[] q, float[] q0, int corner, float fa, float fb) {
        int opp = (corner + 2) % 4;
        float ox = q0[opp * 2], oy = q0[opp * 2 + 1];
        int ui = (corner + 3) % 4, vi = (corner + 1) % 4;
        float ux = q0[ui * 2] - ox, uy = q0[ui * 2 + 1] - oy;
        float vx = q0[vi * 2] - ox, vy = q0[vi * 2 + 1] - oy;
        float[][] c = new float[4][2];
        float[] tmp = new float[2];
        for (int k = 0; k < 4; k++) {
            if (!uvCoords(q0[k * 2], q0[k * 2 + 1], ox, oy, ux, uy, vx, vy, tmp)) return;
            c[k][0] = tmp[0];
            c[k][1] = tmp[1];
        }
        for (int k = 0; k < 4; k++) {
            q[k * 2] = ox + ux * c[k][0] * fa + vx * c[k][1] * fb;
            q[k * 2 + 1] = oy + uy * c[k][0] * fa + vy * c[k][1] * fb;
        }
    }

    /**
     * Snap two corner-scale factors to uniform when they agree within {@code tol}
     * (relative: {@code |fa-fb|/max(|fa|,|fb|) <= tol}).
     *
     * <p>The touch-screen answer to the Shift key: there is no modifier on a phone, so
     * near-diagonal drags snap to uniform (the common case, and every modern competitor
     * — Photoshop, Affinity, Figma — scales proportionally by default) while a
     * deliberate off-diagonal push breaks out to free aspect. The snapped value is the
     * arithmetic mean, applied to both axes, so a trapezoid stays similar. The caller
     * owns the hysteresis (which tolerance to pass); this function is stateless.
     *
     * @param out receives {@code {fa, fb}} — {@code {m, m}} when snapped, the inputs
     *            untouched otherwise
     * @return true when snapped
     */
    public static boolean snapUniformFactors(float fa, float fb, float tol, float[] out) {
        if (out == null || out.length < 2) return false;
        if (!isFinite(fa) || !isFinite(fb) || !(tol >= 0f)) {
            out[0] = fa;
            out[1] = fb;
            return false;
        }
        float denom = Math.max(Math.abs(fa), Math.abs(fb));
        if (denom < 1e-6f) {
            out[0] = fa;
            out[1] = fb;
            return false;
        }
        if (Math.abs(fa - fb) / denom <= tol) {
            float m = (fa + fb) * 0.5f;
            out[0] = m;
            out[1] = m;
            return true;
        }
        out[0] = fa;
        out[1] = fb;
        return false;
    }

    /**
     * TILT on a corner: move it, and move its horizontal neighbour the mirrored way — a symmetric
     * taper, which is what perspective on a flat plane looks like. Applied as a DELTA from where
     * the corner is now, so it composes with itself frame to frame.
     */
    public static void tiltCorner(float[] q, int corner, float tx, float ty) {
        float dx = tx - q[corner * 2], dy = ty - q[corner * 2 + 1];
        int nb = new int[]{TR, TL, BL, BR}[corner];
        q[corner * 2] += dx;
        q[corner * 2 + 1] += dy;
        q[nb * 2] -= dx;
        q[nb * 2 + 1] += dy;
    }

    /** FREE on a corner: the corner is wherever the finger is. No constraint at all. */
    public static void freeCorner(float[] q, int corner, float tx, float ty) {
        set(q, corner, tx, ty);
    }

    // ── Edge behaviours ──────────────────────────────────────────────────

    /**
     * SCALE on an edge — the same defect and the same cure as {@link #scaleCorner}.
     *
     * <p>The naive version forces both side lengths to one common distance from their opposite
     * corners, so any shape whose two sides differ (a free-moved corner, an asymmetric trapezoid)
     * is snapped symmetric the instant a scale edge is touched. Here the frame is anchored on the
     * OPPOSITE edge: {@code u} runs along that pinned edge, {@code v} runs across to this one.
     * Only the {@code v} coordinate is scaled, and it is scaled for all four corners from their
     * own values — so the opposite edge (v = 0) is pinned, the taper ratio survives, and the shape
     * simply gets longer or shorter in that one direction.</p>
     *
     * @param outFactors optional {@code float[2]}; {@code [0]} receives the factor, {@code [1]} NaN
     *                   (an edge scale has ONE factor, and the HUD prints one number for it)
     */
    public static boolean scaleEdge(float[] q, float[] q0, int e, float tx, float ty,
                                    float[] outFactors) {
        int[] pair = EDGE_CORNERS[e], opp = EDGE_OPPOSITE[e];
        float ox = q0[opp[0] * 2], oy = q0[opp[0] * 2 + 1];
        float ux = q0[opp[1] * 2] - ox, uy = q0[opp[1] * 2 + 1] - oy;
        float vx = q0[pair[0] * 2] - ox, vy = q0[pair[0] * 2 + 1] - oy;

        float[][] c = new float[4][2];
        float[] tmp = new float[2];
        for (int k = 0; k < 4; k++) {
            if (!uvCoords(q0[k * 2], q0[k * 2 + 1], ox, oy, ux, uy, vx, vy, tmp)) return false;
            c[k][0] = tmp[0];
            c[k][1] = tmp[1];
        }
        if (!uvCoords(tx, ty, ox, oy, ux, uy, vx, vy, tmp)) return false;
        // The edge's MIDPOINT across-coordinate is the denominator, not either corner's: the
        // finger is dragging the middle of the edge, so that is what should land under it.
        float m = (c[pair[0]][1] + c[pair[1]][1]) / 2f;
        if (Math.abs(m) < 1e-3f) return false;
        float f = clamp(tmp[1] / m, MIN_FACTOR, MAX_FACTOR);
        for (int k = 0; k < 4; k++) {
            q[k * 2] = ox + ux * c[k][0] + vx * c[k][1] * f;
            q[k * 2 + 1] = oy + uy * c[k][0] + vy * c[k][1] * f;
        }
        if (outFactors != null) { outFactors[0] = f; outFactors[1] = Float.NaN; }
        return true;
    }

    /**
     * TILT on an edge: a pure lean. The edge slides ALONG ITSELF — only the component of the
     * finger's offset that runs parallel to the edge is used, so the edge's length is untouched
     * and the shape shears rather than resizing.
     */
    public static void tiltEdge(float[] q, int e, float tx, float ty) {
        int a = EDGE_CORNERS[e][0], b = EDGE_CORNERS[e][1];
        float mx = (q[a * 2] + q[b * 2]) / 2f, my = (q[a * 2 + 1] + q[b * 2 + 1]) / 2f;
        float dx = q[b * 2] - q[a * 2], dy = q[b * 2 + 1] - q[a * 2 + 1];
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-4f) return;
        dx /= len;
        dy /= len;
        float proj = (tx - mx) * dx + (ty - my) * dy;
        q[a * 2] += dx * proj;
        q[a * 2 + 1] += dy * proj;
        q[b * 2] += dx * proj;
        q[b * 2 + 1] += dy * proj;
    }

    /** FREE on an edge: the whole edge follows the finger, any direction, length untouched. */
    public static void freeEdge(float[] q, int e, float tx, float ty) {
        int a = EDGE_CORNERS[e][0], b = EDGE_CORNERS[e][1];
        float mx = (q[a * 2] + q[b * 2]) / 2f, my = (q[a * 2 + 1] + q[b * 2 + 1]) / 2f;
        float dx = tx - mx, dy = ty - my;
        q[a * 2] += dx;
        q[a * 2 + 1] += dy;
        q[b * 2] += dx;
        q[b * 2 + 1] += dy;
    }

    // ── Whole-shape behaviours ───────────────────────────────────────────

    /** Translate every corner. */
    public static void translate(float[] q, float dx, float dy) {
        for (int i = 0; i < 4; i++) { q[i * 2] += dx; q[i * 2 + 1] += dy; }
    }

    /**
     * A PURE rotation about {@code (cx,cy)} — no translation, no scale, rebuilt from the
     * grab-time quad so repeated frames cannot accumulate drift.
     *
     * <p>This is the entire reason a separate rotate handle exists at all: two fingers can nudge
     * position and size without the user meaning to, and this cannot. Every corner is
     * reconstructed from {@code q0}, so the only thing that can differ frame to frame is the
     * angle.</p>
     */
    public static void rotateAbout(float[] q, float[] q0, float cx, float cy, float radians) {
        float cs = (float) Math.cos(radians), sn = (float) Math.sin(radians);
        for (int i = 0; i < 4; i++) {
            float dx = q0[i * 2] - cx, dy = q0[i * 2 + 1] - cy;
            q[i * 2] = cx + cs * dx - sn * dy;
            q[i * 2 + 1] = cy + sn * dx + cs * dy;
        }
    }

    /**
     * TWO FINGERS: the similarity that carries the old finger midpoint to the new one, scaled by
     * the span ratio and turned by the twist.
     *
     * <p>Every corner is rebuilt as {@code m1 + R(theta)*f*(q0 - m0)}. The picture point sitting
     * under the midpoint when the gesture began therefore stays under the midpoint for the whole
     * gesture — which is what "it pivots on the point between your fingers" means, and what an
     * about-the-centre pinch conspicuously fails to do.</p>
     */
    public static void pinch(float[] q, float[] q0,
                             float m0x, float m0y, float m1x, float m1y,
                             float factor, float radians) {
        float cs = (float) Math.cos(radians), sn = (float) Math.sin(radians);
        for (int i = 0; i < 4; i++) {
            float dx = q0[i * 2] - m0x, dy = q0[i * 2 + 1] - m0y;
            q[i * 2] = m1x + (cs * dx - sn * dy) * factor;
            q[i * 2 + 1] = m1y + (sn * dx + cs * dy) * factor;
        }
    }

    /**
     * FOLD: a true geometric reflection of the whole quad in the line through edge {@code e}'s two
     * corners — the shape flips over that edge like a page. The two corners on the edge are their
     * own reflections and do not move; the other two swing across.
     *
     * @return false and leaves {@code q} untouched when the edge is degenerate
     */
    public static boolean foldOverEdge(float[] q, int e) {
        int a = EDGE_CORNERS[e][0], b = EDGE_CORNERS[e][1];
        float ax = q[a * 2], ay = q[a * 2 + 1];
        float dx = q[b * 2] - ax, dy = q[b * 2 + 1] - ay;
        float l2 = dx * dx + dy * dy;
        if (l2 < 1e-6f) return false;
        for (int i = 0; i < 4; i++) {
            float px = q[i * 2], py = q[i * 2 + 1];
            float t = ((px - ax) * dx + (py - ay) * dy) / l2;
            float fx = ax + dx * t, fy = ay + dy * t;   // the foot of the perpendicular
            q[i * 2] = 2f * fx - px;
            q[i * 2 + 1] = 2f * fy - py;
        }
        return true;
    }

    // ── Direction helpers the handle glyphs are oriented by ──────────────

    /** Angle of edge {@code e} in DEGREES (the direction from its first corner to its second). */
    public static float edgeAngleDeg(float[] q, int e) {
        int a = EDGE_CORNERS[e][0], b = EDGE_CORNERS[e][1];
        return (float) Math.toDegrees(Math.atan2(q[b * 2 + 1] - q[a * 2 + 1],
                q[b * 2] - q[a * 2]));
    }

    /**
     * Angle in DEGREES of edge {@code e}'s INWARD normal — the direction a scale triangle's apex
     * points, which is what leaves its flat back parallel to the edge it sits on.
     */
    public static float edgeInwardNormalDeg(float[] q, int e) {
        int a = EDGE_CORNERS[e][0], b = EDGE_CORNERS[e][1];
        float dx = q[b * 2] - q[a * 2], dy = q[b * 2 + 1] - q[a * 2 + 1];
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-4f) return 0f;
        dx /= len;
        dy /= len;
        float nx = -dy, ny = dx;
        float mx = (q[a * 2] + q[b * 2]) / 2f, my = (q[a * 2 + 1] + q[b * 2 + 1]) / 2f;
        float[] c = new float[2];
        centroid(q, c);
        if ((c[0] - mx) * nx + (c[1] - my) * ny < 0f) { nx = -nx; ny = -ny; }
        return (float) Math.toDegrees(Math.atan2(ny, nx));
    }

    /**
     * Angle in degrees a corner's square/diamond is drawn at so it stays square TO THE SHAPE.
     * Squares and diamonds are 90°-symmetric, so the angle of one adjacent edge is enough.
     */
    public static float cornerAngleDeg(float[] q, int corner) {
        int b = (corner + 1) % 4;
        return (float) Math.toDegrees(Math.atan2(q[b * 2 + 1] - q[corner * 2 + 1],
                q[b * 2] - q[corner * 2]));
    }

    // ── The mesh/bend seam: unit-square ⇄ quad homography ────────────────

    /**
     * The 3x3 homography carrying the unit square {@code (0,0)-(1,0)-(1,1)-(0,1)} onto {@code q},
     * row-major, or null when the quad is degenerate.
     *
     * <p><b>This is the seam the bend/mesh lane plugs into.</b> A bend net stores each control
     * point as a {@code (u,v)} nudge inside this unit square and re-projects it through this
     * matrix every frame; that — and only that — is what makes a bend survive an edge scale
     * instead of being wiped by it. Nothing in phase 1 stores offsets, but the projection they
     * will need is here, tested by the body hit-test that already uses it.</p>
     */
    public static float[] unitToQuad(float[] q) {
        // Standard four-point solve: map the unit square to the quad via the "basis" matrix
        // (three corners as columns, scaled so the fourth lands correctly), which for the unit
        // square's canonical corners reduces to solving for the two perspective terms directly.
        float x0 = q[0], y0 = q[1], x1 = q[2], y1 = q[3];
        float x2 = q[4], y2 = q[5], x3 = q[6], y3 = q[7];
        // (u,v): TL=(0,0) TR=(1,0) BR=(1,1) BL=(0,1)
        float dx1 = x1 - x2, dy1 = y1 - y2;
        float dx2 = x3 - x2, dy2 = y3 - y2;
        float sx = x0 - x1 + x2 - x3, sy = y0 - y1 + y2 - y3;
        float g, h;
        float den = dx1 * dy2 - dx2 * dy1;
        if (!isFinite(den) || Math.abs(den) < 1e-9f) return null;
        if (Math.abs(sx) < 1e-6f && Math.abs(sy) < 1e-6f) {
            g = 0f;
            h = 0f;
        } else {
            g = (sx * dy2 - dx2 * sy) / den;
            h = (dx1 * sy - sx * dy1) / den;
        }
        float a = x1 - x0 + g * x1;
        float b = x3 - x0 + h * x3;
        float c = x0;
        float d = y1 - y0 + g * y1;
        float e = y3 - y0 + h * y3;
        float f = y0;
        float[] m = {a, b, c, d, e, f, g, h, 1f};
        for (float v : m) if (!isFinite(v)) return null;
        return m;
    }

    /** Push {@code (u,v)} through a row-major 3x3. {@code out} receives {x, y}. */
    public static boolean applyHomography(float[] m, float u, float v, float[] out) {
        float w = m[6] * u + m[7] * v + m[8];
        if (!isFinite(w) || Math.abs(w) < 1e-9f) return false;
        out[0] = (m[0] * u + m[1] * v + m[2]) / w;
        out[1] = (m[3] * u + m[4] * v + m[5]) / w;
        return isFinite(out[0]) && isFinite(out[1]);
    }

    /** Adjugate of a row-major 3x3 — the inverse up to a scale, which a homography does not care
     *  about. Returns null for a singular matrix. */
    public static float[] invert3x3(float[] m) {
        if (m == null) return null;
        float[] a = {
                m[4] * m[8] - m[5] * m[7], m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
                m[5] * m[6] - m[3] * m[8], m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
                m[3] * m[7] - m[4] * m[6], m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3]};
        float det = m[0] * a[0] + m[1] * a[3] + m[2] * a[6];
        if (!isFinite(det) || Math.abs(det) < 1e-12f) return null;
        for (float v : a) if (!isFinite(v)) return null;
        return a;
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Fold degrees into (-180, 180]. Deterministic at the seam: +180 stays +180. */
    public static float norm180(float deg) {
        float d = deg % 360f;
        if (d > 180f) d -= 360f;
        else if (d <= -180f) d += 360f;
        return d;
    }

    // ── SPEC G: the corner-pin budget bake ──────────────────────────────────
    //
    // Every distortion is stored as four corner offsets in units of the picture's own size
    // (capped far out at CornerPin.MAX_OFFSET, which normal work never reaches). A flip in
    // pin form writes offsets of magnitude 1 and a fold 2.0 — while the distortion the user
    // actually authored, a 5% corner nudge, costs 0.05. Affine content never spends budget:
    // it is baked out at commit (below), so mirroring is unlimited no matter the cap.
    //
    // The insight: a flip, fold, rotation or scale of a rectangle is a PARALLELOGRAM, fully
    // describable by centre, size, rotation and a mirror — no corner pin at all. Only a
    // genuine perspective distortion (a non-parallelogram) needs the pin. So at COMMIT time
    // (never mid-drag, or the picture crawls under the finger) the committed quad is
    // decomposed here: the affine part is baked into the object's existing transform fields
    // and ONLY the deviation from a parallelogram stays in the pin. For a flip, fold, rotate
    // or scale the residual is exactly zero and the pin is cleared.
    //
    // COMPOSITION ORDER, stated once because three renderers must agree on it: bitmap →
    // MIRROR → pin → rotate/scale → translate. The mirror sits OUTSIDE the pin, about the
    // UNPINNED box centre: local = R(d) . M . (B + off.s) + t. The pin offsets therefore
    // live in the unmirrored box frame however the mirror flags stand, which is what makes
    // flip-then-distort and distort-then-flip compose instead of fighting. The preview
    // (CornerPinImageView), the GL Pip (negative half-extent) and the export
    // (ImageOverlayDraw) each implement this order from TextOverlayItem.mirrorSignX/Y, the
    // one shared definition — never a second transcription.
    //
    // HYSTERESIS SNAPS, all absorbed into the residual so the recompose stays exact: a
    // genuine 5% nudge must not unlink the drawer's scale chain or spray size/rotation keys
    // over a 1% affine side-effect of the fit. Snapped-away parts live in the residual pin
    // (fractions, so 0.5% of a 2000px picture is 0.005 of budget) and the round-trip stays
    // within a pixel by construction.

    /** Below this relative scale change, there is no scale change: the box keeps its size. */
    public static final float PIN_BAKE_SCALE_SNAP = 0.02f;
    /** Below this rotation delta, there is no rotation change. */
    public static final float PIN_BAKE_ROT_SNAP_DEG = 0.02f;
    /** Below this centroid shift, there is no translation. */
    public static final float PIN_BAKE_CENTRE_SNAP_PX = 0.05f;
    /** Below this residual fraction, the pin is cleared to exact zero. */
    public static final float PIN_BAKE_CLEAR_FRAC = 1e-4f;
    /** Below this relative |a-b|, a scale stays uniform (the chain stays linked). */
    public static final float PIN_BAKE_UNIFORM_REL = 1e-4f;
    /**
     * Opposite-edge mismatch (fraction of picture size) above which a quad carries genuine
     * perspective rather than float dust. SPEC L: this NO LONGER gates the bake — it is
     * reported through {@link PinNormalize#keystone} for diagnostics only. See the SPEC L
     * note on {@link #normalizePin}.
     */
    public static final float PIN_BAKE_PARALLELOGRAM_TOL = 0.005f;

    /**
     * SPEC L — below this relative anisotropy the fitted scale is read as UNIFORM, and the
     * sliver of difference is absorbed by the residual (exactly, like every other snap here).
     *
     * <p>Without it every corner nudge would fit a scale like 0.9752 x 0.9756 — visually one
     * number, arithmetically two — and the host would unlink the drawer's aspect chain and
     * spray SCALE_X/SCALE_Y keys over a rounding difference. 5% is far below any anisotropy a
     * hand actually authors on purpose and far above the fit noise a keystone produces.</p>
     */
    public static final float PIN_BAKE_ISO_REL = 0.05f;

    /**
     * The baked reading of one committed quad. All in overlay pixels and the pin's own
     * fraction unit; the caller maps them onto model fields (and verifies the picture did
     * not move before keeping them).
     */
    public static final class PinNormalize {
        /** False = degenerate input (collapsed box or quad); leave the pin exactly alone. */
        public boolean valid;
        /**
         * True when an affine part was actually extracted. SPEC L: this is now true for
         * EVERY valid quad — parallelogram or not — because the affine part is always
         * fitted and always baked. It stays in the API because callers gate on it and
         * {@code valid && !baked} remains the honest reading of a fit that produced
         * nothing (there is no such case today; degenerate input reports {@code !valid}).
         */
        public boolean baked;
        /**
         * How far this quad is from a parallelogram, as a fraction of the picture size —
         * i.e. how much genuine keystone the residual is carrying. Diagnostics only:
         * nothing branches on it (SPEC L removed the gate that did).
         */
        public float keystone;
        /** Pose-frame translation, px: add R(oldRotation) . (tx, ty) to the pose centre. */
        public float tx, ty;
        /** Baked box size, px. Exactly (w, h) when the fit found no scale change. */
        public float newW, newH;
        /** ADD to the stored rotation (-180, 180]. Never folded into a window (SPEC A). */
        public float rotDeltaDeg;
        /** ABSOLUTE new mirror state (not a toggle): the total mirror the quad carries. */
        public boolean mirrorX, mirrorY;
        /** Pin offsets on the NEW box in the order above; exact zeros when cleared. */
        public final float[] residual = new float[8];
    }

    /**
     * Decompose the committed pose-frame quad into affine part + residual pin.
     *
     * @param w       untransformed drawn width, px
     * @param h       untransformed drawn height, px
     * @param off8    packed pin offsets (fractions of w/h), TL,TR,BR,BL — in the UNMIRRORED
     *                box frame, exactly as stored
     * @param mirrorX current mirror flag: the presented quad is mirrored, the pin is not
     * @param mirrorY current mirror flag
     * @return the bake; {@link PinNormalize#valid} false when the input is degenerate
     */
    public static PinNormalize normalizePin(float w, float h, float[] off8,
                                            boolean mirrorX, boolean mirrorY) {
        PinNormalize out = new PinNormalize();
        if (!(w > 0.5f) || !(h > 0.5f) || off8 == null || off8.length < 8) return out;
        for (int i = 0; i < 8; i++) {
            if (!isFinite(off8[i])) return out;
        }
        float smx0 = mirrorX ? -1f : 1f, smy0 = mirrorY ? -1f : 1f;
        float hw = w / 2f, hh = h / 2f;
        // The quad the user sees, relative to the box centre: the stored (unmirrored) pin
        // re-mirrored into the presented frame. Everything below reads THIS.
        float x0 = smx0 * (-hw + off8[0] * w), y0 = smy0 * (-hh + off8[1] * h);
        float x1 = smx0 * (hw + off8[2] * w), y1 = smy0 * (-hh + off8[3] * h);
        float x2 = smx0 * (hw + off8[4] * w), y2 = smy0 * (hh + off8[5] * h);
        float x3 = smx0 * (-hw + off8[6] * w), y3 = smy0 * (hh + off8[7] * h);
        // Parallelogram test: opposite edges must match. Mirror-invariant (a mirror only
        // flips signs of differences), so this reads the authored shape however it is worn.
        float m1x = (x1 - x0) - (x2 - x3), m1y = (y1 - y0) - (y2 - y3);
        float m2x = (x3 - x0) - (x2 - x1), m2y = (y3 - y0) - (y2 - y1);
        out.keystone = Math.max((float) Math.hypot(m1x / w, m1y / h),
                (float) Math.hypot(m2x / w, m2y / h));
        // ── SPEC L: THE GATE IS GONE, AND THAT IS THE WHOLE FIX ──────────────────────
        //
        // This used to read "if the quad is not a parallelogram, bake NOTHING and keep the
        // authored pin bit-for-bit". That single early return is why objects escaped the
        // canvas. A trapezoid never baked, so its translation, rotation and scale stayed
        // locked in the pin and accumulated gesture after gesture — measured on JoyRaptor's live
        // project 2026-09-07, a pin of −3.416 (three and a half picture-widths) with all four
        // corners left of the box centre: the picture was two widths away from the box that
        // supposedly held it, while the centre travel clamp — which only ever looks at
        // centreX/centreY — reported the object as barely out of frame.
        //
        // The affine fit below was ALREADY correct for a trapezoid: centroid + opposite-edge
        // averaging IS the least-squares affine fit of the four corners (for the four corners
        // of a box the normal equations reduce to exactly these means), and the residual is
        // computed against the SNAPPED fit, so the recompose is exact by construction whatever
        // the shape. The old code fitted it and threw it away.
        //
        // So: fit always, bake always, keep only the keystone in the pin. Translation goes
        // back to the centre where the travel clamp can see it, and the pin only ever carries
        // shape. Nothing about the picture changes — verifyBake still rolls the whole bake
        // back if any corner moves by a pixel.
        //
        // Centroid = the translation. Opposite-edge averaging = the least-squares affine fit
        // (exact for parallelograms, the even-handed best fit for trapezoids).
        float tx = (x0 + x1 + x2 + x3) / 4f;
        float ty = (y0 + y1 + y2 + y3) / 4f;
        float exx = ((x1 - x0) + (x2 - x3)) * 0.5f;
        float exy = ((y1 - y0) + (y2 - y3)) * 0.5f;
        float eyx = ((x3 - x0) + (x2 - x1)) * 0.5f;
        float eyy = ((y3 - y0) + (y2 - y1)) * 0.5f;
        float ew = (float) Math.hypot(exx, exy);
        float eh = (float) Math.hypot(eyx, eyy);
        if (!(ew > 0.5f) || !(eh > 0.5f)) return out;
        float cross = exx * eyy - exy * eyx;
        if (!(Math.abs(cross) / (ew * eh) > 1e-3f)) return out;   // folded flat, not a picture
        float thx = (float) Math.toDegrees(Math.atan2(exy, exx));
        if (cross > 0f) {
            if (mirrorX && mirrorY) {
                // A doubly-mirrored presentation IS a half turn (det +1 both ways), so two
                // readings are exact: clear the flags and turn 180, or keep them and turn
                // nothing. Keeping them is strictly less churn — same picture, no rotation
                // readout jump, no flag write — so the current state breaks the tie.
                out.mirrorX = true;
                out.mirrorY = true;
                out.rotDeltaDeg = norm180(thx + 180f);
            } else {
                // Unmirrored: the whole affine content is a rotation by the x-axis angle.
                out.mirrorX = false;
                out.mirrorY = false;
                out.rotDeltaDeg = norm180(thx);
            }
        } else {
            // A reflection. Two readings reproduce the same picture (mirror-X plus a half
            // turn, or mirror-Y at the x-axis angle); take the one that turns less, so a
            // horizontal flip toggles its flag with no rotation change and a diagonal fold
            // takes the shorter way round. Tie goes to Y, deterministically.
            float dy = norm180(thx);
            float dx = norm180(thx + 180f);
            if (Math.abs(dx) < Math.abs(dy)) {
                out.mirrorX = true;
                out.mirrorY = false;
                out.rotDeltaDeg = dx;
            } else {
                out.mirrorX = false;
                out.mirrorY = true;
                out.rotDeltaDeg = dy;
            }
        }
        if (Math.abs(out.rotDeltaDeg) < PIN_BAKE_ROT_SNAP_DEG) out.rotDeltaDeg = 0f;
        float a = ew / w, b = eh / h;
        // SPEC L — near-uniform reads as uniform. The bake now runs on shapes that are not
        // parallelograms, and the best-fit basis lengths of a keystone differ by a fraction of
        // a percent; without this the host would see two different scale factors, unlink the
        // drawer's aspect chain and write SCALE_X/SCALE_Y keys every time a corner was nudged.
        // The difference is absorbed by the residual below, so the picture is untouched.
        float isoDen = Math.max(Math.abs(a), Math.abs(b));
        if (isoDen > 1e-6f && Math.abs(a - b) / isoDen <= PIN_BAKE_ISO_REL) {
            float m = (a + b) * 0.5f;
            a = m;
            b = m;
        }
        if (Math.abs(a - 1f) < PIN_BAKE_SCALE_SNAP) a = 1f;
        if (Math.abs(b - 1f) < PIN_BAKE_SCALE_SNAP) b = 1f;
        out.newW = w * a;
        out.newH = h * b;
        if (Math.hypot(tx, ty) < PIN_BAKE_CENTRE_SNAP_PX) { tx = 0f; ty = 0f; }
        out.tx = tx;
        out.ty = ty;
        // Residual in composition order: X = R(d) . M . (B + off.s) + t, so
        // off = (M . R(-d) . (X - t) - B) / s. Written against the SNAPPED values, so the
        // recompose with what the caller actually stores is exact, not approximate.
        double rad = Math.toRadians(-out.rotDeltaDeg);
        float cs = (float) Math.cos(rad), sn = (float) Math.sin(rad);
        float smx = out.mirrorX ? -1f : 1f, smy = out.mirrorY ? -1f : 1f;
        float nHw = out.newW / 2f, nHh = out.newH / 2f;
        float[] bx = {-nHw, nHw, nHw, -nHw};
        float[] by = {-nHh, -nHh, nHh, nHh};
        float[] xs = {x0, x1, x2, x3}, ys = {y0, y1, y2, y3};
        float worst = 0f;
        for (int i = 0; i < 4; i++) {
            float rx = xs[i] - tx, ry = ys[i] - ty;
            float ux = cs * rx - sn * ry, uy = sn * rx + cs * ry;
            float ox = (smx * ux - bx[i]) / out.newW;
            float oy = (smy * uy - by[i]) / out.newH;
            out.residual[i * 2] = ox;
            out.residual[i * 2 + 1] = oy;
            float m = Math.max(Math.abs(ox), Math.abs(oy));
            if (m > worst) worst = m;
        }
        if (worst < PIN_BAKE_CLEAR_FRAC) {
            for (int i = 0; i < 8; i++) out.residual[i] = 0f;
        }
        out.valid = true;
        out.baked = true;
        return out;
    }

    // ── SPEC P: THE RENDER EQUATION, WRITTEN ONCE ──────────────────────────
    //
    // Every renderer draws an image overlay as:
    //
    //     bitmap -> mirror (about the box centre) -> corner pin -> rotate ABOUT THE PIVOT
    //
    // (ImageOverlayDraw's canvas stack, TextOverlayLayer's View pivot, the GL fold). Rotating
    // about the pivot instead of the centre is the same thing as rotating about the centre and
    // then shifting by (I - R(rot)) . o, where o is the pivot's mirror-signed offset from the
    // box centre. That (I - R) . o term is the whole reason SPEC P existed: the commit-time bake
    // and its self-check each carried their own transcription of it, they disagreed about which
    // CENTRE they were anchored to, and the two errors cancelled inside the self-check while the
    // picture moved half a centimetre on screen. There is now exactly one copy, here, and both
    // the bake and the check call it.

    /**
     * Where the four drawn corners of a pinned, mirrored, rotated, pivot-folded picture land,
     * in the caller's pixel space. This IS the render equation:
     *
     * <pre>  screen[i] = C + R(rot) . M . (boxCorner[i] + off[i] . (w, h)) + (I - R(rot)) . o</pre>
     *
     * @param poseCx  the POSE centre — the item's stored centre, NOT a box that has already had
     *                the pivot fold applied to it (see {@code foldRotationPivotIntoBox}). Handing
     *                in a folded box centre counts the fold twice; that was SPEC P's bug.
     * @param pivOffX the pivot's MIRROR-SIGNED offset from the box centre, px — zero at a centre
     *                pivot, exactly as every renderer gates it
     * @param out8    packed TL,TR,BR,BL
     */
    public static void renderQuad(float poseCx, float poseCy, float w, float h,
                                  float[] off8, float rotDeg,
                                  float mirrorSignX, float mirrorSignY,
                                  float pivOffX, float pivOffY, float[] out8) {
        double rad = Math.toRadians(rotDeg);
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        // The pivot fold, once: rotating about (C + o) rather than C shifts by (I - R) . o.
        float fx = pivOffX - (c * pivOffX - s * pivOffY);
        float fy = pivOffY - (s * pivOffX + c * pivOffY);
        float hw = w / 2f, hh = h / 2f;
        for (int i = 0; i < 4; i++) {
            float bx = (i == 0 || i == 3) ? -hw : hw;
            float by = (i < 2) ? -hh : hh;
            // Order: pin, then mirror — the pin lives in the unmirrored box frame.
            float lx = mirrorSignX * (bx + (off8 == null ? 0f : off8[i * 2]) * w);
            float ly = mirrorSignY * (by + (off8 == null ? 0f : off8[i * 2 + 1]) * h);
            out8[i * 2] = poseCx + c * lx - s * ly + fx;
            out8[i * 2 + 1] = poseCy + s * lx + c * ly + fy;
        }
    }

    /**
     * SPEC P — the POSE centre a commit-time bake must write so that not one pixel moves.
     *
     * <p>{@link #normalizePin} splits the drawn quad into an affine part and a residual such
     * that {@code R(rot0) . l0[i] = R(rot1) . l1[i] + R(rot0) . t}. Substituting that into
     * {@link #renderQuad} on both sides and demanding equality leaves exactly one unknown:</p>
     *
     * <pre>  C1 = C0 + R(rot0) . t + (I - R(rot0)) . o0 - (I - R(rot1)) . o1</pre>
     *
     * <p>The two fold terms do not cancel, because the bake redefines the pivot: {@code o} is
     * pin-aware and the pin, the box size and the rotation all change. That is why the
     * correction is angle-dependent — and why anchoring it at the wrong centre produced an
     * error that grew with {@code 2 sin(rot/2)} and flipped sign with the sign of the angle,
     * which is precisely what JoyRaptor measured by eye.</p>
     *
     * @param poseCx0 the POSE centre before the bake (stored centre in px), never a folded box
     * @param tx      {@link PinNormalize#tx}, pose-frame px
     * @param pivOff0X the OLD mirror-signed pivot offset, on the OLD box with the OLD pin
     * @param pivOff1X the NEW mirror-signed pivot offset, on the NEW box with the residual pin
     */
    public static void bakedPoseCentre(float poseCx0, float poseCy0,
                                       float rot0Deg, float pivOff0X, float pivOff0Y,
                                       float tx, float ty,
                                       float rot1Deg, float pivOff1X, float pivOff1Y,
                                       float[] out2) {
        double rad0 = Math.toRadians(rot0Deg), rad1 = Math.toRadians(rot1Deg);
        float c0 = (float) Math.cos(rad0), s0 = (float) Math.sin(rad0);
        float c1 = (float) Math.cos(rad1), s1 = (float) Math.sin(rad1);
        float x = poseCx0 + c0 * tx - s0 * ty;
        float y = poseCy0 + s0 * tx + c0 * ty;
        x += (pivOff0X - (c0 * pivOff0X - s0 * pivOff0Y))
                - (pivOff1X - (c1 * pivOff1X - s1 * pivOff1Y));
        y += (pivOff0Y - (s0 * pivOff0X + c0 * pivOff0Y))
                - (pivOff1Y - (s1 * pivOff1X + c1 * pivOff1Y));
        out2[0] = x;
        out2[1] = y;
    }

    // ── SPEC L: clamp the DRAWN QUAD, not the box centre ───────────────────
    //
    // The travel clamp guards centreX/centreY, and a corner pin can translate the drawn
    // picture arbitrarily far from its centre — so the only guard the system had was
    // measuring the wrong point. SPEC L Part 1 puts translation back in the centre where
    // that clamp can see it; this is the backstop for the one gesture that could still
    // fling a picture out, and it measures the thing the user can actually see.
    //
    // TRANSLATE, NEVER RESHAPE: the authored shape is not ours to change, so the fix is a
    // pure per-axis shift of the POSE. If Part 1 is right this should almost never fire.

    /** SPEC L — this fraction of the drawn quad's bounding box must stay on canvas. */
    public static final float QUAD_MIN_VISIBLE_FRAC = 0.15f;

    /**
     * The smallest translation that puts at least {@code minFrac} of {@code quad8}'s
     * bounding box back inside the rect, per axis.
     *
     * <p>Per-axis rather than by area: a picture hanging off the left edge should slide
     * right and not also drop down, which is what an area solve would do. When the quad's
     * bounding box is bigger than the rect on an axis, the requirement on that axis is the
     * rect's own extent (otherwise a picture legitimately larger than the canvas could
     * never satisfy it).</p>
     *
     * @param quad8 the drawn quad in the same pixels as the rect
     * @param out2  receives {dx, dy}; zeroed when nothing is needed
     * @return true when a nonzero translation is required
     */
    public static boolean quadEscapeFix(float[] quad8,
                                        float rectL, float rectT, float rectR, float rectB,
                                        float minFrac, float[] out2) {
        if (quad8 == null || quad8.length < 8 || out2 == null || out2.length < 2) return false;
        out2[0] = 0f;
        out2[1] = 0f;
        for (float v : quad8) if (!isFinite(v)) return false;
        if (!isFinite(rectL) || !isFinite(rectT) || !isFinite(rectR) || !isFinite(rectB)) return false;
        if (!(rectR - rectL > 0.5f) || !(rectB - rectT > 0.5f)) return false;
        if (!isFinite(minFrac) || !(minFrac > 0f) || minFrac > 1f) return false;
        float bl = quad8[0], br = quad8[0], bt = quad8[1], bb = quad8[1];
        for (int i = 1; i < 4; i++) {
            bl = Math.min(bl, quad8[i * 2]);
            br = Math.max(br, quad8[i * 2]);
            bt = Math.min(bt, quad8[i * 2 + 1]);
            bb = Math.max(bb, quad8[i * 2 + 1]);
        }
        out2[0] = axisEscapeFix(bl, br, rectL, rectR, minFrac);
        out2[1] = axisEscapeFix(bt, bb, rectT, rectB, minFrac);
        if (!isFinite(out2[0]) || !isFinite(out2[1])) { out2[0] = 0f; out2[1] = 0f; return false; }
        return out2[0] != 0f || out2[1] != 0f;
    }

    /** One axis of {@link #quadEscapeFix}: the shift that restores the required overlap. */
    private static float axisEscapeFix(float lo, float hi, float rLo, float rHi, float minFrac) {
        float ext = hi - lo;
        if (!(ext > 0f)) return 0f;
        float need = Math.min(minFrac * ext, rHi - rLo);
        float overlap = Math.min(hi, rHi) - Math.max(lo, rLo);
        // A hair of slack, so the fix is IDEMPOTENT: re-measuring a quad this function has
        // just moved re-derives the bounds in float and can land a thousandth of a pixel
        // short of the requirement, which without this would shove the picture forever.
        if (overlap >= need - 1e-3f) return 0f;
        // Off the far side (lo beyond rHi - need) → pull back by the shortfall, and the
        // other way round. Exactly one of these applies: the two cases cannot both hold
        // once `need` is capped at the rect extent.
        if (hi < rLo + need) return (rLo + need) - hi;
        return (rHi - need) - lo;
    }

    // ── SPEC K: the pose/pivot-exact pin solve ─────────────────────────────
    //
    // CornerPinTransformHost.writeQuad used to un-fold every dragged corner about the
    // pivot by −θ using the pivot offset read from the OLD (pre-gesture) pins, then
    // measure against the pose box. That is exact only when the gesture leaves the
    // pivot offset alone. The offset is a bilinear function of the pins themselves, so
    // any gesture that moves the pin mean — a fold, a scale about the opposite corner,
    // a free drag on a rotated picture with a non-neutral pivot — solved for the wrong
    // frame, by (I−R)·(δold−δnew): tens to hundreds of pixels on a rotated picture,
    // stored into the pins and re-read as a quad that no longer matches the gesture.
    // Handles follow the finger, the picture follows the stored pins, and on release
    // the handles snap to the picture. Repeated gestures accumulate the error until a
    // single corner sits a full picture-height away from the other three.
    //
    // The pose centre itself is stable inside a distort gesture (only the pins move),
    // so recovering it with the old offset stays correct. The pins are then solved
    // IMPLICITLY: with C known, Q−C = (I−R)·δ + R·M·(b+o·s) and δ = δflat + Σw·o·s
    // is linear in the eight unknown offsets, in closed form D = R·M·(δflat + Σw·A)
    // with no inverse and no singularity at any angle or mirror state. Round-trip is
    // exact up to float noise at every pivot, mirror state and winding, including
    // 365° (SPEC A raw storage). At the centre pivot the folded offset is exactly
    // zero (TextOverlayItem.isRotationPivotNeutral), so the solve reads straight
    // through with no D term at all.

    /**
     * Bilinear weights of the nine-anchor pivot at {@code (u,v)} over corners
     * TL,TR,BR,BL. Sums to 1; at flat pins the pivot offset is exactly
     * {@code ((u−0.5)·w, (v−0.5)·h)}.
     *
     * @param out4 receives the four weights in TL,TR,BR,BL order
     */
    public static void pivotWeights(float u, float v, float[] out4) {
        out4[0] = (1f - u) * (1f - v);
        out4[1] = u * (1f - v);
        out4[2] = u * v;
        out4[3] = (1f - u) * v;
    }

    /**
     * Rebase gesture-local pixels from an old canvas rect to a new one, in place.
     *
     * <p>A drag stores its truth in pixels — grab snapshot, live quad, finger origin —
     * but the canvas rect those pixels are measured against can move underneath it
     * (a drawer resize, the controls fading, anything that re-lays-out the preview
     * container mid-gesture). Without a rebase the frozen snapshot and the live finger
     * speak different frames, and the commit bakes the gap into the project as a
     * teleport on finger-up. The mapping is the rect-to-rect diagonal affine (both
     * rects are axis-aligned canvas fits, so per-axis scale plus translate is exact),
     * applied to every stored point; differences (like the grab offset) scale, which
     * the same affine does to them. No model writes — pure chrome, so it cannot cost
     * the user anything and needs no undo.
     *
     * @param pts  packed {@code {x,y}} pairs (a quad, a finger origin, ...), remapped
     *             in place
     * @return false when the rects are degenerate or identical (caller keeps going —
     *         nothing moved)
     */
    public static boolean rebasePoints(float[] pts,
                                       float oldL, float oldT, float oldW, float oldH,
                                       float newL, float newT, float newW, float newH) {
        if (pts == null) return false;
        if (!(oldW > 0.5f) || !(oldH > 0.5f) || !(newW > 0.5f) || !(newH > 0.5f)) return false;
        for (float v : pts) if (!isFinite(v)) return false;
        if (!isFinite(oldL) || !isFinite(oldT) || !isFinite(newL) || !isFinite(newT)) return false;
        float sx = newW / oldW, sy = newH / oldH;
        float tx = newL - oldL * sx, ty = newT - oldT * sy;
        if (!isFinite(sx) || !isFinite(sy) || !isFinite(tx) || !isFinite(ty)) return false;
        if (sx == 1f && sy == 1f && tx == 0f && ty == 0f) return false;
        for (int i = 0; i + 1 < pts.length; i += 2) {
            float x = pts[i] * sx + tx, y = pts[i + 1] * sy + ty;
            if (!isFinite(x) || !isFinite(y)) return false;
            pts[i] = x;
            pts[i + 1] = y;
        }
        return true;
    }

    /**
     * Solve the pose-frame pin offsets whose presented quad is {@code quad8}.
     *
     * @param quad8  presented quad (TL,TR,BR,BL) in overlay px, exactly as dragged
     * @param poseCx pose box centre x in the same px (recovered by unfolding the
     *               folded box about the pivot with the OLD pins — the centre never
     *               moves inside a distort gesture, so the old offset reads it exactly)
     * @param poseCy pose box centre y
     * @param w      untransformed drawn width, px
     * @param h      untransformed drawn height, px
     * @param thDeg  stored rotation, degrees (raw winding, e.g. 365.24 — only its
     *               sine/cosine enter, so windings behave identically)
     * @param smx    mirror sign x ({@code TextOverlayItem.mirrorSignX})
     * @param smy    mirror sign y
     * @param pivU   stored pivot x fraction (0, 0.5 or 1)
     * @param pivV   stored pivot y fraction
     * @param outPins8 receives the eight offsets in CornerPin order
     * @return false for degenerate input (refuse, do not store)
     */
    public static boolean solvePinForQuad(float[] quad8,
                                          float poseCx, float poseCy,
                                          float w, float h, float thDeg,
                                          float smx, float smy,
                                          float pivU, float pivV,
                                          float[] outPins8) {
        if (quad8 == null || outPins8 == null || quad8.length < 8 || outPins8.length < 8) return false;
        if (!(w > 0.5f) || !(h > 0.5f)) return false;
        if (!isFinite(poseCx) || !isFinite(poseCy) || !isFinite(thDeg)) return false;
        if (!isFinite(smx) || !isFinite(smy) || smx == 0f || smy == 0f) return false;
        for (int i = 0; i < 8; i++) if (!isFinite(quad8[i])) return false;
        double radI = Math.toRadians(-thDeg);
        float ci = (float) Math.cos(radI), si = (float) Math.sin(radI);
        if (!isFinite(ci) || !isFinite(si)) return false;
        // A_i = M·R(−θ)·(Q_i − C) − b_i, the pin numerator at δ = 0.
        float[] ax = new float[4], ay = new float[4];
        for (int i = 0; i < 4; i++) {
            float qx = quad8[i * 2] - poseCx, qy = quad8[i * 2 + 1] - poseCy;
            float rx = ci * qx - si * qy, ry = si * qx + ci * qy;
            float bx = (i == 0 || i == 3) ? -w / 2f : w / 2f;
            float by = (i < 2) ? -h / 2f : h / 2f;
            ax[i] = smx * rx - bx;
            ay[i] = smy * ry - by;
            if (!isFinite(ax[i]) || !isFinite(ay[i])) return false;
        }
        // SPEC K — a centre pivot never folds (TextOverlayItem.isRotationPivotNeutral),
        // so the folded offset here is exactly zero however the pins read. (The closed
        // form below would recover the same thing through δflat = 0 plus the gesture's
        // own distortion mean — which is precisely the unbounded lever this avoids.)
        float dx, dy;
        if (pivU == 0.5f && pivV == 0.5f) {
            dx = 0f;
            dy = 0f;
        } else {
            float[] ww = new float[4];
            pivotWeights(pivU, pivV, ww);
            float sAx = ww[0] * ax[0] + ww[1] * ax[1] + ww[2] * ax[2] + ww[3] * ax[3];
            float sAy = ww[0] * ay[0] + ww[1] * ay[1] + ww[2] * ay[2] + ww[3] * ay[3];
            double radR = Math.toRadians(thDeg);
            float cr = (float) Math.cos(radR), sr = (float) Math.sin(radR);
            if (!isFinite(cr) || !isFinite(sr)) return false;
            float mSx = smx * ((pivU - 0.5f) * w + sAx);
            float mSy = smy * ((pivV - 0.5f) * h + sAy);
            if (!isFinite(mSx) || !isFinite(mSy)) return false;
            dx = cr * mSx - sr * mSy;
            dy = sr * mSx + cr * mSy;
            if (!isFinite(dx) || !isFinite(dy)) return false;
        }
        // K = M.(Ri-I); o.s = A - K.D per corner.
        float k00 = smx * (ci - 1f), k01 = smx * (-si);
        float k10 = smy * si, k11 = smy * (ci - 1f);
        for (int i = 0; i < 4; i++) {
            float ox = (ax[i] - (k00 * dx + k01 * dy)) / w;
            float oy = (ay[i] - (k10 * dx + k11 * dy)) / h;
            if (!isFinite(ox) || !isFinite(oy)) return false;
            outPins8[i * 2] = ox;
            outPins8[i * 2 + 1] = oy;
        }
        return true;
    }
}
