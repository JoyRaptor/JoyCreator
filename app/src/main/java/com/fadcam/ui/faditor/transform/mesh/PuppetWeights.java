package com.fadcam.ui.faditor.transform.mesh;

/**
 * WHICH PINS MOVE WHICH VERTICES — measured ACROSS THE BODY, not through the air.
 *
 * <p>SPEC_20260915_PUPPET_UI §4 is the ruling this file implements: keep the {@code 1/d^2} falloff,
 * keep the normalisation across handles, <b>change only the distance function.</b>
 *
 * <h3>The bug this fixes</h3>
 * <p>With straight-line distance, a character standing with both hands together has a left-hand pin
 * a couple of pixels from the right hand — through the air. Drag the left hand and the right hand
 * comes with it, because nothing in the maths knows the two are joined only by way of the
 * shoulders. Measuring along the mesh instead, the same two points are an arm, a chest and an arm
 * apart, and the influence dies long before it gets there.
 *
 * <p>It is not a corner case: it is what happens the moment bones exist, because a bone is exactly
 * the thing that puts two far-apart-along-the-body points near each other in space.
 *
 * <h3>Why Dijkstra and not heat or biharmonic weights</h3>
 * <p>All three are BIND-TIME precomputes producing one weight per vertex per handle; the per-frame
 * blend is byte-for-byte the same either way, so <b>none of them buys a single frame of playback
 * speed.</b> They differ only in the wait when a pin is dropped and in how much code carries the
 * idea. This is ~1-3 ms and lives inside maths that already worked. The ladder, and the reasons for
 * not climbing it yet, are in section 4 of the spec.
 *
 * <h3>Normalising is free, and it is what makes smoothing legal</h3>
 * <p>Rigid MLS is invariant to a global scaling of the weights — the centroids divide by the sum,
 * and the accumulated vector is renormalised to {@code |u|} — so normalising to a partition of
 * unity changes no pose. What it buys is a BOUNDED field, which is the only kind you can safely
 * average with its neighbours. Smoothing unbounded {@code 1/d^2} would be dominated by whichever
 * vertex happened to sit nearest a pin.
 *
 * <p>The exception is the vertex that sits ON a pin. There, {@code 1/d^2} is infinite and that
 * infinity is doing real work: it is what makes MLS pass exactly through its control points. Those
 * vertices are given the one-hot row and then <b>held out of smoothing</b>, so the pin still lands
 * exactly under the finger.
 *
 * <h3>Detached limbs get this for free</h3>
 * <p>JoyRaptor draws characters as SEPARATE PIECES, and the pieces share no mesh edge — so the
 * distance from a pin on one arm to a vertex on the other is not merely large, it is INFINITE.
 * That is the right answer and it needs no special case: an unreachable pin gets weight zero
 * exactly, so a pin can never move a piece of artwork it is not attached to. The one rule this
 * file does add is what happens to a piece with NO pin on it at all — see {@link #build}.
 *
 * <p>Immutable once built, so it can be shared across GL threads. No Android imports.
 */
public final class PuppetWeights {

    /**
     * Laplacian passes over the normalised field. Two is the spec's lower bound and is enough to
     * take the faceting off a coarse traced mesh; more costs nothing at playback and only blurs the
     * boundary between neighbouring pins further. First number to turn if posing looks angular.
     */
    private static final int SMOOTHING_PASSES = 2;

    /** How much of a smoothed vertex comes from its neighbours rather than itself. */
    private static final float SMOOTHING_LAMBDA = 0.5f;

    /** Closer than this (in unit space) and a vertex IS the pin. */
    private static final float SNAP = 1e-6f;

    private final int vertexCount;
    private final int pinCount;
    /** Row-major {@code [vertex * pinCount + pin]}, normalised so each row sums to 1. */
    private final float[] w;

    private PuppetWeights(int vertexCount, int pinCount, float[] w) {
        this.vertexCount = vertexCount;
        this.pinCount = pinCount;
        this.w = w;
    }

    public int vertexCount() { return vertexCount; }

    public int pinCount() { return pinCount; }

    /** Normalised influence of {@code pin} over {@code vertex}. Out of range gives 0. */
    public float weight(int vertex, int pin) {
        if (vertex < 0 || vertex >= vertexCount || pin < 0 || pin >= pinCount) return 0f;
        return w[vertex * pinCount + pin];
    }

    /** The whole row for one vertex, copied into {@code out}. */
    public void row(int vertex, float[] out) {
        if (out == null || out.length < pinCount || vertex < 0 || vertex >= vertexCount) return;
        System.arraycopy(w, vertex * pinCount, out, 0, pinCount);
    }

    /**
     * Build the table.
     *
     * @param verts   interleaved x,y — the REST mesh, exactly as {@link MeshTopology#buildRest} laid
     *                it out, because the returned rows are indexed by that same vertex order
     * @param indices the triangle list; supplies the graph the distance is measured along
     * @param pins    pin rest positions, interleaved x,y
     * @return null when the inputs do not describe a mesh with at least one pin — the caller then
     *         falls back to straight-line distance rather than refusing to draw
     */
    public static PuppetWeights build(float[] verts, short[] indices, float[] pins) {
        return build(verts, indices, pins, null);
    }

    /**
     * Build the table for a mesh that may be several disconnected pieces.
     *
     * @param islandStart where each island's vertices begin plus a final total, as
     *                    {@code PuppetTriangulator.Mesh.islandStart} gives it. Null means one
     *                    island, which is what ordinary artwork is.
     *
     * <h3>The one rule islands need: a piece with no pin on it</h3>
     * <p>Every vertex of such a piece is infinitely far from every pin, so the honest weighting is
     * "no influence" — and a piece with no influence never moves, which reads as a head that stays
     * behind when the body walks away. So a piece nobody pinned falls back to STRAIGHT-LINE
     * weights and rides along with whatever is nearest. It is the only place in this file that
     * measures through the air, it applies to a whole piece rather than a vertex, and it stops the
     * moment someone puts one pin on it.
     */
    public static PuppetWeights build(float[] verts, short[] indices, float[] pins,
                                      int[] islandStart) {
        if (verts == null || indices == null || pins == null) return null;
        int n = verts.length / 2, p = pins.length / 2;
        if (n <= 0 || p <= 0 || indices.length < 3) return null;

        int[] island = islandOfVertex(n, islandStart);
        int[][] adj = adjacency(n, indices);
        float[] table = new float[n * p];
        float[] dist = new float[n];
        boolean[] locked = new boolean[n];

        for (int i = 0; i < p; i++) {
            geodesic(verts, adj, island, pins[i * 2], pins[i * 2 + 1], dist);
            for (int v = 0; v < n; v++) {
                float d = dist[v];
                if (Float.isInfinite(d)) {
                    // A pin on another piece of the artwork. Zero, exactly — this is the whole
                    // point of measuring along the mesh, and it costs nothing to get right.
                    table[v * p + i] = 0f;
                } else if (d < SNAP) {
                    locked[v] = true;
                    table[v * p + i] = Float.POSITIVE_INFINITY;   // resolved in normalise()
                } else {
                    table[v * p + i] = 1f / (d * d);
                }
            }
        }

        orphanIslandsRideAlong(table, verts, pins, n, p);
        normalise(table, n, p);
        for (int pass = 0; pass < SMOOTHING_PASSES; pass++) smooth(table, n, p, adj, locked);
        return new PuppetWeights(n, p, table);
    }

    /** Expand {@code islandStart} into one island number per vertex. Null means a single island. */
    private static int[] islandOfVertex(int n, int[] islandStart) {
        int[] island = new int[n];
        if (islandStart == null || islandStart.length < 2) return island;
        for (int i = 0; i + 1 < islandStart.length; i++) {
            int lo = Math.max(0, islandStart[i]), hi = Math.min(n, islandStart[i + 1]);
            for (int v = lo; v < hi; v++) island[v] = i;
        }
        return island;
    }

    /**
     * Vertices no pin could reach get straight-line weights, so an unpinned piece of artwork rides
     * with the rest instead of being left behind. See {@link #build}'s note.
     */
    private static void orphanIslandsRideAlong(float[] table, float[] verts, float[] pins,
                                               int n, int p) {
        for (int v = 0; v < n; v++) {
            int base = v * p;
            boolean reached = false;
            for (int i = 0; i < p && !reached; i++) reached = table[base + i] != 0f;
            if (reached) continue;
            for (int i = 0; i < p; i++) {
                float dx = verts[v * 2] - pins[i * 2], dy = verts[v * 2 + 1] - pins[i * 2 + 1];
                float d2 = dx * dx + dy * dy;
                table[base + i] = d2 < SNAP * SNAP ? Float.POSITIVE_INFINITY : 1f / d2;
            }
        }
    }

    /**
     * The measurement itself: shortest path ALONG THE MESH from an arbitrary point to every vertex.
     *
     * <p>Exposed so the claim this file is built on can be tested directly, rather than inferred
     * from its consequences. Normalising the weights compresses the difference — a vertex an inch
     * from one pin gives every other pin a small share no matter how far away they are — so the
     * ratio between two DISTANCES is the honest statement of "across the body, not through the
     * air", and the weight and the pose are downstream of it.
     *
     * @param outDist one entry per vertex; infinite where the mesh does not connect
     * @return false when the inputs do not describe a mesh
     */
    public static boolean distancesFrom(float[] verts, short[] indices, float x, float y,
                                        float[] outDist) {
        if (verts == null || indices == null || outDist == null) return false;
        int n = verts.length / 2;
        if (n <= 0 || outDist.length < n || indices.length < 3) return false;
        geodesic(verts, adjacency(n, indices), new int[n], x, y, outDist);
        return true;
    }

    // -- the graph ---------------------------------------------------------------------------

    private static int[][] adjacency(int n, short[] indices) {
        // Two passes so the result is exact int[] rather than a boxed list per vertex — this runs
        // on the drop of a pin, on the UI thread.
        int[] deg = new int[n];
        int tris = indices.length / 3;
        for (int t = 0; t < tris; t++) {
            for (int e = 0; e < 3; e++) {
                int a = indices[t * 3 + e] & 0xFFFF, b = indices[t * 3 + (e + 1) % 3] & 0xFFFF;
                if (a < n && b < n) { deg[a]++; deg[b]++; }
            }
        }
        int[][] adj = new int[n][];
        int[] fill = new int[n];
        for (int v = 0; v < n; v++) adj[v] = new int[deg[v]];
        for (int t = 0; t < tris; t++) {
            for (int e = 0; e < 3; e++) {
                int a = indices[t * 3 + e] & 0xFFFF, b = indices[t * 3 + (e + 1) % 3] & 0xFFFF;
                if (a < n && b < n) { adj[a][fill[a]++] = b; adj[b][fill[b]++] = a; }
            }
        }
        // Duplicates (an interior edge is shared by two triangles) are left in. They cost one extra
        // relaxation each in a graph this size and removing them would cost a sort per vertex.
        return adj;
    }

    /**
     * Shortest path along mesh edges from an arbitrary POINT to every vertex.
     *
     * <p>A pin is not a vertex — it is wherever the finger landed. So the search is seeded with the
     * three vertices nearest the pin, each at its straight-line distance from it; whenever the pin
     * is inside the mesh those three are its own triangle or its immediate neighbours, and within
     * one triangle straight-line and along-the-surface are the same thing. A pin dropped outside
     * the shape still gets a sane field instead of no reach at all.
     */
    private static void geodesic(float[] verts, int[][] adj, int[] island,
                                 float pinX, float pinY, float[] dist) {
        int n = dist.length;
        java.util.Arrays.fill(dist, Float.POSITIVE_INFINITY);

        int a = -1, b = -1, c = -1;
        float da = Float.MAX_VALUE, db = Float.MAX_VALUE, dc = Float.MAX_VALUE;
        for (int v = 0; v < n; v++) {
            float dx = verts[v * 2] - pinX, dy = verts[v * 2 + 1] - pinY;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < da) { c = b; dc = db; b = a; db = da; a = v; da = d; }
            else if (d < db) { c = b; dc = db; b = v; db = d; }
            else if (d < dc) { c = v; dc = d; }
        }
        if (a < 0) return;
        // A PIN BELONGS TO ONE PIECE. Dropped in the gap between two limbs, its three nearest
        // vertices can straddle both — and seeding both would bridge them, which is the exact bug
        // measuring along the mesh exists to prevent. The nearest vertex decides, and the other
        // seeds join only if they agree.
        int home = island[a];
        dist[a] = da;
        if (b >= 0 && island[b] == home) dist[b] = db;
        if (c >= 0 && island[c] == home) dist[c] = dc;

        // Dijkstra with a binary heap. Meshes here are 50-200 vertices, so the heap is a formality
        // — but an O(n^2) scan would become the thing that made a denser mesh feel slow, and this
        // is twenty lines.
        int[] heapV = new int[Math.max(8, n * 4)];
        float[] heapK = new float[heapV.length];
        int size = 0;
        for (int v = 0; v < n; v++) {
            if (!Float.isInfinite(dist[v])) { heapV[size] = v; heapK[size] = dist[v]; size++; }
        }
        for (int i = size / 2 - 1; i >= 0; i--) siftDown(heapV, heapK, size, i);

        boolean[] done = new boolean[n];
        while (size > 0) {
            int v = heapV[0];
            float k = heapK[0];
            size--;
            heapV[0] = heapV[size];
            heapK[0] = heapK[size];
            siftDown(heapV, heapK, size, 0);
            if (done[v] || k > dist[v]) continue;
            done[v] = true;
            for (int u : adj[v]) {
                if (done[u]) continue;
                float ex = verts[u * 2] - verts[v * 2], ey = verts[u * 2 + 1] - verts[v * 2 + 1];
                float nd = dist[v] + (float) Math.sqrt(ex * ex + ey * ey);
                if (nd < dist[u]) {
                    dist[u] = nd;
                    if (size >= heapV.length) {   // lazy deletion means the heap can outgrow n
                        heapV = java.util.Arrays.copyOf(heapV, heapV.length * 2);
                        heapK = java.util.Arrays.copyOf(heapK, heapK.length * 2);
                    }
                    heapV[size] = u;
                    heapK[size] = nd;
                    size++;
                    siftUp(heapV, heapK, size - 1);
                }
            }
        }
    }

    private static void siftUp(int[] hv, float[] hk, int i) {
        while (i > 0) {
            int par = (i - 1) / 2;
            if (hk[par] <= hk[i]) break;
            swap(hv, hk, i, par);
            i = par;
        }
    }

    private static void siftDown(int[] hv, float[] hk, int size, int i) {
        while (true) {
            int l = i * 2 + 1, r = l + 1, m = i;
            if (l < size && hk[l] < hk[m]) m = l;
            if (r < size && hk[r] < hk[m]) m = r;
            if (m == i) return;
            swap(hv, hk, i, m);
            i = m;
        }
    }

    private static void swap(int[] hv, float[] hk, int i, int j) {
        int tv = hv[i]; hv[i] = hv[j]; hv[j] = tv;
        float tk = hk[i]; hk[i] = hk[j]; hk[j] = tk;
    }

    // -- the field ---------------------------------------------------------------------------

    private static void normalise(float[] table, int n, int p) {
        for (int v = 0; v < n; v++) {
            int base = v * p;
            // An infinity in the row means the vertex sits on that pin, and the row is one-hot.
            int hot = -1;
            for (int i = 0; i < p; i++) {
                if (Float.isInfinite(table[base + i])) { hot = i; break; }
            }
            if (hot >= 0) {
                for (int i = 0; i < p; i++) table[base + i] = (i == hot) ? 1f : 0f;
                continue;
            }
            float sum = 0f;
            for (int i = 0; i < p; i++) sum += table[base + i];
            if (sum > 0f && !Float.isInfinite(sum)) {
                for (int i = 0; i < p; i++) table[base + i] /= sum;
            } else {
                // Nothing reached this vertex at all. Spread it evenly rather than freeze it.
                float even = 1f / p;
                for (int i = 0; i < p; i++) table[base + i] = even;
            }
        }
    }

    /**
     * One Laplacian pass, then renormalise. Locked vertices (the ones sitting on a pin) are read by
     * their neighbours but never written, which is what keeps MLS passing through its pins.
     */
    private static void smooth(float[] table, int n, int p, int[][] adj, boolean[] locked) {
        float[] next = table.clone();
        for (int v = 0; v < n; v++) {
            if (locked[v] || adj[v].length == 0) continue;
            int base = v * p;
            for (int i = 0; i < p; i++) {
                float acc = 0f;
                for (int u : adj[v]) acc += table[u * p + i];
                float avg = acc / adj[v].length;
                next[base + i] = table[base + i] * (1f - SMOOTHING_LAMBDA) + avg * SMOOTHING_LAMBDA;
            }
            float sum = 0f;
            for (int i = 0; i < p; i++) sum += next[base + i];
            if (sum > 0f) for (int i = 0; i < p; i++) next[base + i] /= sum;
        }
        System.arraycopy(next, 0, table, 0, table.length);
    }
}
