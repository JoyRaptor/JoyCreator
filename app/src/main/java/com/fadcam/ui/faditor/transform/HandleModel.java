package com.fadcam.ui.faditor.transform;

/**
 * WHAT EACH HANDLE DOES, AND WHAT IT LOOKS LIKE SAYING SO.
 *
 * <p>Four corners and four edges, each carrying one {@link Role}. The role decides the shape and
 * the colour, and the shape and colour are the whole point: the user is supposed to know what a
 * handle will do <i>before</i> touching it, which is the difference between this and a modal
 * toolbar. Every handle DEFAULTS to {@link Role#SCALE} — "working in right angles for video is the
 * most common thing" — so an object nobody has configured behaves like an ordinary resize box.</p>
 *
 * <p><b>The colour language</b> (the owner's own words): BLUE is fluid · AMBER is structure ·
 * GREEN is semi-fluid, semi-structured · RED is chaotic. And the shapes: SQUARE keeps your right
 * angles, TRIANGLE points the direction it will move, CIRCLE goes anywhere.</p>
 *
 * <p>Rotate is drawn AMBER on purpose even though it is not a square: amber already means "your
 * structure survives", and a rotation is the most structure-preserving move there is — every
 * angle and every length comes through it. The arc outline, not the colour, is what says it
 * spins.</p>
 *
 * <p>Like {@link TransformQuad} this file has no Android and no FadCam imports: it is the model
 * of the handle set, and the View reads it to decide what to paint and what a touch landed on.</p>
 */
public final class HandleModel {

    /** What a handle does when dragged. Bend is deliberately NOT here — see {@link #BEND_IS_A_NET}. */
    public enum Role { SCALE, TILT, FREE }

    /** Drawn outline of a handle. One vocabulary shared by handles, the ring and any legend. */
    public enum Shape { SQUARE, TRIANGLE, DIAMOND, CIRCLE, ROTATE_ARC, NET }

    /** What kind of thing a handle is. */
    public enum Kind { CORNER, EDGE, ROTATE, BODY }

    /**
     * BEND IS NOT A ROLE AND MUST NEVER BECOME ONE.
     *
     * <p>It is a net switched on OVER the top, with the structural handles still working
     * underneath it — which is why it is absent from {@link Role} rather than being a fourth
     * value. Making it a role is exactly the design that was rejected: it turns bend into a mode
     * that steals a handle, and it is what made "scale the edge and my bend is gone" feel
     * inevitable. The mesh lane adds a net layer alongside this model, not a value inside it.
     * See {@code tasks/SPEC_20260902_MESH_WARP.md}.</p>
     */
    public static final boolean BEND_IS_A_NET = true;

    // ── Colours (ARGB) ───────────────────────────────────────────────────

    public static final int COLOR_SCALE = 0xFFFBBF24;   // amber — structure
    public static final int COLOR_TILT  = 0xFF6EE7A8;   // green — semi-fluid
    public static final int COLOR_FREE  = 0xFFFF3D7F;   // red   — chaotic
    public static final int COLOR_BEND  = 0xFF5AA9FF;   // blue  — fluid
    public static final int COLOR_ROTATE = COLOR_SCALE; // rotation preserves everything
    /** The quad outline itself. */
    public static final int COLOR_GUIDE = 0xFFA78BFA;

    public static int colorOf(Role r) {
        switch (r) {
            case TILT: return COLOR_TILT;
            case FREE: return COLOR_FREE;
            default: return COLOR_SCALE;
        }
    }

    public static String nameOf(Role r) {
        switch (r) {
            case TILT: return "Tilt";
            case FREE: return "Free";
            default: return "Scale";
        }
    }

    /**
     * The shape a CORNER wears for each role: square (your right angles survive), diamond (a lean
     * — a square knocked off true), circle (anywhere).
     */
    public static Shape cornerShape(Role r) {
        switch (r) {
            case TILT: return Shape.DIAMOND;
            case FREE: return Shape.CIRCLE;
            default: return Shape.SQUARE;
        }
    }

    /**
     * The shape an EDGE wears: an inward triangle for scale (its flat back parallel to the edge,
     * its apex pointing the way the edge will travel), a square that rotates with the edge for
     * tilt, a circle for free.
     */
    public static Shape edgeShape(Role r) {
        switch (r) {
            case TILT: return Shape.SQUARE;
            case FREE: return Shape.CIRCLE;
            default: return Shape.TRIANGLE;
        }
    }

    // ── The per-object handle set ────────────────────────────────────────

    private final Role[] corners = {Role.SCALE, Role.SCALE, Role.SCALE, Role.SCALE};
    private final Role[] edges = {Role.SCALE, Role.SCALE, Role.SCALE, Role.SCALE};

    public Role corner(int i) { return corners[clampIdx(i)]; }
    public Role edge(int i) { return edges[clampIdx(i)]; }

    public void setCorner(int i, Role r) { corners[clampIdx(i)] = r; }
    public void setEdge(int i, Role r) { edges[clampIdx(i)] = r; }

    /**
     * RESET ALL HELPERS — and nothing else, ever.
     *
     * <p>This and "reset the object" own strictly disjoint territory, which is the owner's ruling
     * and the reason there are two buttons instead of one. This one owns HANDLE ROLES. It does not
     * touch geometry, it does not touch a warp, it does not touch a flip: after pressing it not
     * one pixel of the picture has moved, only the meaning of the eight handles has gone back to
     * plain scale. The geometry reset lives in the adapter, because geometry is the thing this
     * class deliberately knows nothing about.</p>
     */
    public void resetHelpers() {
        for (int i = 0; i < 4; i++) { corners[i] = Role.SCALE; edges[i] = Role.SCALE; }
    }

    /** True when every handle is still plain scale — i.e. nothing to reset. */
    public boolean isDefault() {
        for (int i = 0; i < 4; i++) {
            if (corners[i] != Role.SCALE || edges[i] != Role.SCALE) return false;
        }
        return true;
    }

    public void copyFrom(HandleModel o) {
        if (o == null) return;
        System.arraycopy(o.corners, 0, corners, 0, 4);
        System.arraycopy(o.edges, 0, edges, 0, 4);
    }

    private static int clampIdx(int i) { return i < 0 ? 0 : (i > 3 ? 3 : i); }

    // ── One handle, resolved for this frame ──────────────────────────────

    /** A handle at a place, with everything the View needs to draw it and hit-test it. */
    public static final class Handle {
        public Kind kind;
        public int index;
        public float x, y;
        /** Degrees the glyph is drawn at, so it stays square to the shape it belongs to. */
        public float rotationDeg;
        public Shape shape;
        public int color;
        /**
         * Hit priority. Higher wins a contested touch. Rotate outranks corners outranks edges,
         * because a rotate handle floats clear on a stalk and a corner is the smaller target of
         * the two it can overlap — the one you have to aim at is the one that should win.
         */
        public int priority;
    }

    /**
     * Fill {@code out} with the eight structural handles plus the rotate handle, positioned from
     * {@code quad}.
     *
     * @param out          a list of at least 9 reusable {@link Handle}s, written in place
     * @param quad         the live quad in view pixels
     * @param rotateHandle {x, y, angleDeg} of the floating spin arc (the View computes it, since
     *                     its stand-off distance is a dp measurement)
     * @return how many entries of {@code out} were filled
     */
    public int fill(Handle[] out, float[] quad, float[] rotateHandle) {
        int n = 0;
        for (int i = 0; i < 4; i++) {
            Role r = corners[i];
            Shape s = cornerShape(r);
            Handle h = out[n++];
            h.kind = Kind.CORNER;
            h.index = i;
            h.x = TransformQuad.x(quad, i);
            h.y = TransformQuad.y(quad, i);
            h.shape = s;
            h.color = colorOf(r);
            h.priority = 8;
            h.rotationDeg = s == Shape.CIRCLE ? 0f : TransformQuad.cornerAngleDeg(quad, i);
        }
        float[] mid = new float[2];
        for (int e = 0; e < 4; e++) {
            Role r = edges[e];
            Shape s = edgeShape(r);
            TransformQuad.edgeMid(quad, e, mid);
            Handle h = out[n++];
            h.kind = Kind.EDGE;
            h.index = e;
            h.x = mid[0];
            h.y = mid[1];
            h.shape = s;
            h.color = colorOf(r);
            h.priority = 5;
            // The triangle's apex points along the INWARD normal, which is exactly what leaves
            // its flat back parallel to the edge it is mounted on. The tilt square rotates WITH
            // the edge so it stays perpendicular to it. A circle has nothing to orient.
            h.rotationDeg = s == Shape.TRIANGLE
                    ? TransformQuad.edgeInwardNormalDeg(quad, e) + 90f
                    : (s == Shape.SQUARE ? TransformQuad.edgeAngleDeg(quad, e) : 0f);
        }
        if (rotateHandle != null) {
            Handle h = out[n++];
            h.kind = Kind.ROTATE;
            h.index = 0;
            h.x = rotateHandle[0];
            h.y = rotateHandle[1];
            h.shape = Shape.ROTATE_ARC;
            h.color = COLOR_ROTATE;
            h.priority = 9;
            h.rotationDeg = rotateHandle[2];
        }
        return n;
    }

    /**
     * Which handle a touch landed on, or null.
     *
     * <p>Nearest-with-a-priority-bias, not first-within-range. A rotate handle on a short stalk and
     * a corner can both be inside the touch slop of one thumb; "first match wins" would make one
     * of them permanently unreachable, and which one would depend on iteration order. The priority
     * bias breaks a genuine overlap in favour of the smaller, more deliberate target.</p>
     *
     * @param radiusPx the base grab radius — the caller passes at least 22dp so the 44dp target
     *                 rule holds however small the drawn glyph is
     */
    public static Handle hitTest(Handle[] handles, int count, float px, float py, float radiusPx) {
        Handle best = null;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            Handle h = handles[i];
            float limit = radiusPx + h.priority * 1.5f;
            float d = (float) Math.hypot(h.x - px, h.y - py);
            if (d > limit) continue;
            float score = d - h.priority * 2.4f;
            if (score < bestScore) { bestScore = score; best = h; }
        }
        return best;
    }
}
