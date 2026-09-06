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
        float fa = clamp(tmp[0] / c[corner][0], MIN_FACTOR, MAX_FACTOR);
        float fb = clamp(tmp[1] / c[corner][1], MIN_FACTOR, MAX_FACTOR);
        for (int k = 0; k < 4; k++) {
            q[k * 2] = ox + ux * c[k][0] * fa + vx * c[k][1] * fb;
            q[k * 2 + 1] = oy + uy * c[k][0] * fa + vy * c[k][1] * fb;
        }
        if (outFactors != null) { outFactors[0] = fa; outFactors[1] = fb; }
        return true;
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
}
