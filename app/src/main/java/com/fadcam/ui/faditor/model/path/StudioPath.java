package com.fadcam.ui.faditor.model.path;

import java.util.ArrayList;
import java.util.List;

/**
 * STU-5 primitives, step 1: the PATH itself (STUDIO_PLAN P3.1), the shape every later piece points
 * at: text on a path, offset along a path, paths as masks and motion paths. No screen yet; the
 * object on the timeline, its pen tool and the look are a design step for the owner.
 *
 * <p>A path is a run of nodes joined by cubic Bezier curves; each node has an in-handle and an
 * out-handle (relative to the node), so a sharp corner is a node whose handles are zero. It can be
 * open (a line, an arc, a freehand stroke) or closed (a circle, a rectangle). Coordinates are the
 * canvas fractions every Studio object already uses (0..1 across and down).</p>
 *
 * <p><b>Measured by length, not by curve parameter.</b> "Halfway along" means half the distance,
 * so text spaced along a circle is evenly spaced and an object moving along a path moves at a
 * steady speed. {@link #pointAt} walks a table of equal-distance samples built once per shape.</p>
 *
 * <p>Freehand ({@link #fromStroke}) is simplified on release with the same reducer that turns a
 * traced picture outline into a puppet mesh ({@code AlphaContour.simplify}), then smoothed through
 * the kept points, as the plan asks.</p>
 *
 * <p>Pure Java: {@code tools/jvm-harness/StudioPathTest.java}.</p>
 */
public final class StudioPath {

    /** One node: where it is, and its two handles RELATIVE to it. */
    public static final class Node {
        public float x, y;
        public float inX, inY, outX, outY;

        public Node(float x, float y) { this.x = x; this.y = y; }

        public Node(float x, float y, float inX, float inY, float outX, float outY) {
            this.x = x; this.y = y; this.inX = inX; this.inY = inY; this.outX = outX; this.outY = outY;
        }

        Node copy() { return new Node(x, y, inX, inY, outX, outY); }
    }

    /** Where a point along the path is, and which way the path runs there (degrees, 0 = along +x). */
    public static final class Point {
        public final float x, y, angleDeg;
        Point(float x, float y, float angleDeg) { this.x = x; this.y = y; this.angleDeg = angleDeg; }
    }

    private final List<Node> nodes = new ArrayList<>();
    private boolean closed;

    /** Steps per curve when the shape is measured. Plenty for a phone screen; cheap to build. */
    static final int STEPS = 48;
    /** The circle's handle length for a quarter turn (4/3 * tan(pi/8)). */
    static final float KAPPA = 0.5522847498f;

    private float[] table;      // x0,y0,x1,y1,... equal-parameter samples along the whole path
    private float[] cumulative; // distance travelled at each sample

    public StudioPath() {}

    public StudioPath(List<Node> nodes, boolean closed) {
        for (Node n : nodes) this.nodes.add(n.copy());
        this.closed = closed;
    }

    public List<Node> nodes() { return nodes; }
    public boolean isClosed() { return closed; }

    public void setClosed(boolean c) { closed = c; changed(); }

    /** Call after moving a node or a handle: the length table is rebuilt on the next question. */
    public void changed() { table = null; cumulative = null; }

    public StudioPath copy() { return new StudioPath(nodes, closed); }

    // ── Presets ─────────────────────────────────────────────────────────────

    public static StudioPath line(float x0, float y0, float x1, float y1) {
        List<Node> n = new ArrayList<>();
        n.add(new Node(x0, y0));
        n.add(new Node(x1, y1));
        return new StudioPath(n, false);
    }

    /** A rectangle, corners clockwise from the top left. */
    public static StudioPath rect(float left, float top, float right, float bottom) {
        List<Node> n = new ArrayList<>();
        n.add(new Node(left, top));
        n.add(new Node(right, top));
        n.add(new Node(right, bottom));
        n.add(new Node(left, bottom));
        return new StudioPath(n, true);
    }

    /** A circle (or an ellipse when rx and ry differ), starting at the top and going clockwise. */
    public static StudioPath circle(float cx, float cy, float rx, float ry) {
        float kx = rx * KAPPA, ky = ry * KAPPA;
        List<Node> n = new ArrayList<>();
        n.add(new Node(cx, cy - ry, -kx, 0f, kx, 0f));
        n.add(new Node(cx + rx, cy, 0f, -ky, 0f, ky));
        n.add(new Node(cx, cy + ry, kx, 0f, -kx, 0f));
        n.add(new Node(cx - rx, cy, 0f, ky, 0f, -ky));
        return new StudioPath(n, true);
    }

    /**
     * An arc of a circle: from {@code startDeg} (0 = right, 90 = down, as the screen's y runs down)
     * turning {@code sweepDeg} (positive = clockwise on screen). Built from pieces of at most a
     * quarter turn, each a cubic with the standard handle length, so it is a true slice of
     * {@link #circle} and text on an arc lines up with text on the circle.
     */
    public static StudioPath arc(float cx, float cy, float r, float startDeg, float sweepDeg) {
        int pieces = Math.max(1, (int) Math.ceil(Math.abs(sweepDeg) / 90f - 1e-4f));
        double step = Math.toRadians(sweepDeg) / pieces;
        double k = 4.0 / 3.0 * Math.tan(step / 4.0) * r;
        List<Node> n = new ArrayList<>();
        double a = Math.toRadians(startDeg);
        for (int i = 0; i <= pieces; i++) {
            double ang = a + i * step;
            float x = (float) (cx + r * Math.cos(ang)), y = (float) (cy + r * Math.sin(ang));
            float tx = (float) (-Math.sin(ang) * k), ty = (float) (Math.cos(ang) * k);   // tangent * k
            Node node = new Node(x, y);
            if (i > 0) { node.inX = -tx; node.inY = -ty; }
            if (i < pieces) { node.outX = tx; node.outY = ty; }
            n.add(node);
        }
        return new StudioPath(n, false);
    }

    /**
     * A freehand stroke (x,y interleaved, as drawn), simplified on release: points within
     * {@code tolerance} of the line between their neighbours go ({@code AlphaContour.simplify}),
     * then the kept points are joined smoothly (Catmull-Rom tangents, a third of the way to each
     * neighbour), so a hand-drawn swoop becomes a few clean nodes that still pass through the
     * places the pen went. Fewer than two points gives an empty path.
     */
    public static StudioPath fromStroke(float[] xy, float tolerance) {
        List<Node> out = new ArrayList<>();
        if (xy == null || xy.length < 4) return new StudioPath(out, false);
        float[] kept = xy.length >= 8 && tolerance > 0f
                ? com.fadcam.ui.faditor.transform.mesh.AlphaContour.simplify(xy, tolerance) : xy;
        // The contour reducer never goes below three points (a polygon must stay a polygon), so a
        // stroke that is a straight line within the tolerance comes back whole: keep its two ends.
        if (kept == xy && xy.length >= 8 && tolerance > 0f && withinChord(xy, tolerance)) {
            kept = new float[]{xy[0], xy[1], xy[xy.length - 2], xy[xy.length - 1]};
        }
        int n = kept.length / 2;
        for (int i = 0; i < n; i++) out.add(new Node(kept[i * 2], kept[i * 2 + 1]));
        for (int i = 0; i < n; i++) {
            Node p = out.get(i);
            Node prev = out.get(Math.max(0, i - 1)), next = out.get(Math.min(n - 1, i + 1));
            float tx = (next.x - prev.x) / 6f, ty = (next.y - prev.y) / 6f;
            if (i > 0) { p.inX = -tx; p.inY = -ty; }
            if (i < n - 1) { p.outX = tx; p.outY = ty; }
        }
        return new StudioPath(out, false);
    }

    /** True when every point is within {@code tol} of the line from the first point to the last. */
    private static boolean withinChord(float[] xy, float tol) {
        float ax = xy[0], ay = xy[1], ex = xy[xy.length - 2] - ax, ey = xy[xy.length - 1] - ay;
        float len = (float) Math.sqrt(ex * ex + ey * ey);
        if (len < 1e-9f) return false;
        for (int i = 2; i < xy.length - 2; i += 2) {
            float px = xy[i] - ax, py = xy[i + 1] - ay;
            if (Math.abs(px * ey - py * ex) / len > tol) return false;
        }
        return true;
    }

    // ── Measuring ───────────────────────────────────────────────────────────

    private int segmentCount() {
        int n = nodes.size();
        if (n < 2) return 0;
        return closed ? n : n - 1;
    }

    private void build() {
        if (table != null) return;
        int segs = segmentCount();
        if (segs == 0) {
            table = nodes.isEmpty() ? new float[0] : new float[]{nodes.get(0).x, nodes.get(0).y};
            cumulative = new float[table.length / 2];
            return;
        }
        int samples = segs * STEPS + 1;
        table = new float[samples * 2];
        cumulative = new float[samples];
        int k = 0;
        for (int s = 0; s < segs; s++) {
            Node a = nodes.get(s), b = nodes.get((s + 1) % nodes.size());
            float p0x = a.x, p0y = a.y, p1x = a.x + a.outX, p1y = a.y + a.outY;
            float p2x = b.x + b.inX, p2y = b.y + b.inY, p3x = b.x, p3y = b.y;
            for (int i = (s == 0 ? 0 : 1); i <= STEPS; i++) {
                float t = i / (float) STEPS, u = 1f - t;
                float w0 = u * u * u, w1 = 3f * u * u * t, w2 = 3f * u * t * t, w3 = t * t * t;
                table[k * 2] = w0 * p0x + w1 * p1x + w2 * p2x + w3 * p3x;
                table[k * 2 + 1] = w0 * p0y + w1 * p1y + w2 * p2y + w3 * p3y;
                if (k > 0) {
                    float dx = table[k * 2] - table[k * 2 - 2], dy = table[k * 2 + 1] - table[k * 2 - 1];
                    cumulative[k] = cumulative[k - 1] + (float) Math.sqrt(dx * dx + dy * dy);
                }
                k++;
            }
        }
    }

    /**
     * The path as a dense polyline (x,y interleaved, {@value #STEPS} points per curve), the one
     * outline every renderer draws, so the preview and the export cannot draw two different curves.
     */
    public float[] polyline() {
        build();
        return table.clone();
    }

    /** Total length, in the same units as the nodes. */
    public float length() {
        build();
        return cumulative.length == 0 ? 0f : cumulative[cumulative.length - 1];
    }

    /**
     * The point {@code fraction} of the way along the path by DISTANCE (0 = the first node, 1 = the
     * end; a closed path comes back to its start at 1), and which way the path runs there. Outside
     * 0..1 a closed path wraps round and an open one stops at its ends. Null for an empty path.
     */
    public Point pointAt(float fraction) {
        build();
        int n = cumulative.length;
        if (n == 0) return null;
        if (n == 1) return new Point(table[0], table[1], 0f);
        float total = cumulative[n - 1];
        float f = closed ? fraction - (float) Math.floor(fraction) : Math.max(0f, Math.min(1f, fraction));
        if (closed && fraction != 0f && f == 0f && fraction == Math.floor(fraction)) f = 1f;
        float d = f * total;
        int lo = 0, hi = n - 1;
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] <= d) lo = mid; else hi = mid;
        }
        float span = cumulative[hi] - cumulative[lo];
        float t = span > 0f ? (d - cumulative[lo]) / span : 0f;
        float ax = table[lo * 2], ay = table[lo * 2 + 1], bx = table[hi * 2], by = table[hi * 2 + 1];
        float ang = (float) Math.toDegrees(Math.atan2(by - ay, bx - ax));
        return new Point(ax + (bx - ax) * t, ay + (by - ay) * t, ang);
    }

    // ── Saving ──────────────────────────────────────────────────────────────

    /** {closed, then six numbers a node}: readable, and stable for the project file. */
    public float[] toArray() {
        float[] a = new float[1 + nodes.size() * 6];
        a[0] = closed ? 1f : 0f;
        int k = 1;
        for (Node n : nodes) {
            a[k++] = n.x; a[k++] = n.y; a[k++] = n.inX; a[k++] = n.inY; a[k++] = n.outX; a[k++] = n.outY;
        }
        return a;
    }

    /** The inverse of {@link #toArray}; a short or broken array gives what it can, never throws. */
    public static StudioPath fromArray(float[] a) {
        List<Node> n = new ArrayList<>();
        if (a == null || a.length < 1) return new StudioPath(n, false);
        for (int k = 1; k + 5 < a.length; k += 6) n.add(new Node(a[k], a[k + 1], a[k + 2], a[k + 3], a[k + 4], a[k + 5]));
        return new StudioPath(n, a[0] > 0.5f);
    }
}
