package com.fadcam.ui.faditor.transform.mesh;

/**
 * Stage 2 of puppeteering: turn the traced outline into TRIANGLES.
 *
 * <p>JoyRaptor, 2026-09-04: <i>"puts TRIANGLES AROUND THE SHAPE in a mesh."</i> The output is a
 * plain vertex list plus a triangle-index list, which is exactly what {@link MeshBuffers} holds and
 * what the GL stamp already draws — so a puppet costs no new rendering, no new export path and no
 * new parity story. That was the entire point of separating topology from deformer.
 *
 * <h3>Ear clipping, plus interior points</h3>
 * <p>The spec names "constrained Delaunay triangulation with interior points seeded by density".
 * This is the honest first half of that: ear clipping gives a correct, hole-free triangulation of
 * any simple polygon, and interior points are added on a grid and stitched in. What it does NOT do
 * is optimise triangle QUALITY the way Delaunay does — a long thin sliver deforms worse than a fat
 * triangle, so a later pass should flip edges toward the Delaunay condition.
 *
 * <p>Said plainly rather than implied: <b>this produces a usable mesh, not an optimal one.</b> It is
 * enough to prove the pipeline end to end and to measure the solver's cost against a realistic
 * vertex count, which is what the spec says the biggest unknown is. Swapping in edge-flipping later
 * changes only this file — nothing downstream knows how the triangles were chosen.
 *
 * <p>No Android imports; runs on the desktop harness, like the rest of this package.
 */
public final class PuppetTriangulator {

    /** The triangulated result. Plain arrays, because that is what the buffers and GL want. */
    public static final class Mesh {
        /** Interleaved x,y in unit space. Contour points first, then interior points. */
        public final float[] verts;
        /** Triangle list, three indices per triangle, referring to {@link #verts}. */
        public final short[] indices;
        /** How many of {@link #verts} came from a contour (the rest are interior). */
        public final int contourCount;

        /**
         * Where each ISLAND's vertices start, plus a final entry holding the total — so island
         * {@code i} owns vertices {@code [islandStart[i], islandStart[i+1])}.
         *
         * <p>A character drawn as detached limbs is several islands, and they are contiguous
         * blocks rather than interleaved because each is triangulated on its own and appended.
         * That contiguity is what lets {@link PuppetWeights} keep a pin's influence inside its own
         * piece without storing an island number per vertex.
         *
         * <p>Always at least two entries: a single-island mesh is {@code {0, vertexCount}}.
         */
        public final int[] islandStart;

        /**
         * Where each island's TRIANGLES start in {@link #indices}, plus a final total — so island
         * {@code i} owns indices {@code [indexStart[i], indexStart[i+1])}.
         *
         * <p>Vertices alone are not enough to draw the pieces separately, which is what they have
         * to be: one draw call for the whole mesh composites every island against the same flat
         * sample, and an overlapping limb then punches its own transparent border through the limb
         * behind it.
         */
        public final int[] indexStart;

        Mesh(float[] verts, short[] indices, int contourCount) {
            this(verts, indices, contourCount, new int[]{0, verts.length / 2},
                    new int[]{0, indices.length});
        }

        Mesh(float[] verts, short[] indices, int contourCount, int[] islandStart,
             int[] indexStart) {
            this.verts = verts;
            this.indices = indices;
            this.contourCount = contourCount;
            this.islandStart = islandStart;
            this.indexStart = indexStart;
        }

        public int vertexCount() { return verts.length / 2; }

        public int triangleCount() { return indices.length / 3; }

        /** How many separate pieces of artwork this mesh covers. */
        public int islandCount() { return islandStart.length - 1; }

        /** Which island a vertex belongs to, or -1. Linear over a handful of islands. */
        public int islandOf(int vertex) {
            for (int i = 0; i + 1 < islandStart.length; i++) {
                if (vertex >= islandStart[i] && vertex < islandStart[i + 1]) return i;
            }
            return -1;
        }
    }

    private PuppetTriangulator() { }

    /**
     * The most vertices a mesh may carry, and the matching index count.
     *
     * <p>Computed from {@link LatticeTopology} rather than copied as a literal, because these are
     * the same two numbers {@code MeshStampGl.MAX_VERTS} and {@code MAX_INDICES} are computed
     * from — and a builder that disagrees with its renderer about the budget produces meshes that
     * are silently dropped at draw time. This package cannot import the stamp (it would drag GL
     * in and the harness would stop running), so it derives the numbers the same way instead.
     */
    public static final int VERT_BUDGET =
            (LatticeTopology.tessellationFor(LatticeTopology.L3) + 1)
                    * (LatticeTopology.tessellationFor(LatticeTopology.L3) + 1);

    /** @see #VERT_BUDGET */
    public static final int INDEX_BUDGET = LatticeTopology.tessellationFor(LatticeTopology.L3)
            * LatticeTopology.tessellationFor(LatticeTopology.L3) * 6;

    /**
     * Triangulate SEVERAL rings into one mesh — the detached-limbs case.
     *
     * <p>JoyRaptor's artwork is a character drawn as separate pieces, so this is the ordinary path
     * and not an exotic one. Each ring is triangulated on its own and the results are appended:
     * vertices, UVs and indices all concatenate cleanly, the indices of the second island simply
     * starting where the first island's vertices ended. There is no stitching and no shared
     * vertex between islands, which is the point — two pieces of art that do not touch must not
     * end up joined by a triangle.
     *
     * <h3>Density is scaled per island, not copied</h3>
     * <p>{@code interior} is points along one axis of an island's own bounding box, so handing a
     * hand-sized island the body's number would pack it far more densely than the body — more
     * vertices to solve, and, worse, geodesic distances measured on two different scales. Each
     * island's density is scaled by the square root of its share of the largest island's area,
     * which keeps triangles roughly the same SIZE everywhere. Every island keeps at least one
     * interior point when any were asked for, or it cannot bend at all.
     *
     * @param rings    one closed ring per island, largest first as {@link AlphaContour#traceAll}
     *                 returns them
     * @param interior interior density for the LARGEST island; see above
     * @return the concatenated mesh, or null when no ring triangulated
     */
    public static Mesh triangulate(float[][] rings, int interior) {
        if (rings == null || rings.length == 0) return null;
        if (rings.length == 1) return triangulate(rings[0], interior);

        float biggest = 0f;
        float[] areas = new float[rings.length];
        for (int i = 0; i < rings.length; i++) {
            areas[i] = Math.abs(AlphaContour.signedArea2(rings[i])) * 0.5f;
            biggest = Math.max(biggest, areas[i]);
        }

        java.util.List<Mesh> parts = new java.util.ArrayList<>(rings.length);
        java.util.List<Integer> starts = new java.util.ArrayList<>(rings.length + 1);
        java.util.List<Integer> idxStarts = new java.util.ArrayList<>(rings.length + 1);
        int verts = 0, indices = 0, contour = 0;
        for (int i = 0; i < rings.length; i++) {
            int dens = interior;
            if (interior > 0 && biggest > 0f && areas[i] < biggest) {
                dens = Math.max(1, Math.round(interior * (float) Math.sqrt(areas[i] / biggest)));
            }
            Mesh m = triangulate(rings[i], dens);
            if (m == null || m.vertexCount() == 0) continue;   // a degenerate piece is skipped
            // THE RENDERER'S BUDGET, not a number invented here. A mesh over it is not an error
            // anywhere — the topology accepts it, the item stores it, and the GL stamp then
            // drops it in silence and the picture never bends. Stopping at the budget costs the
            // smallest pieces; going past it costs the whole character.
            if (verts + m.vertexCount() > VERT_BUDGET
                    || indices + m.indices.length > INDEX_BUDGET) {
                break;
            }
            starts.add(verts);
            idxStarts.add(indices);
            parts.add(m);
            verts += m.vertexCount();
            indices += m.indices.length;
            contour += m.contourCount;
        }
        if (parts.isEmpty()) return null;
        starts.add(verts);
        idxStarts.add(indices);

        float[] outV = new float[verts * 2];
        short[] outI = new short[indices];
        int vo = 0, io = 0;
        for (int i = 0; i < parts.size(); i++) {
            Mesh m = parts.get(i);
            System.arraycopy(m.verts, 0, outV, vo * 2, m.verts.length);
            int base = vo;
            for (int k = 0; k < m.indices.length; k++) {
                outI[io + k] = (short) (base + (m.indices[k] & 0xFFFF));
            }
            vo += m.vertexCount();
            io += m.indices.length;
        }
        int[] islandStart = new int[starts.size()];
        for (int i = 0; i < islandStart.length; i++) islandStart[i] = starts.get(i);
        int[] indexStart = new int[idxStarts.size()];
        for (int i = 0; i < indexStart.length; i++) indexStart[i] = idxStarts.get(i);
        return new Mesh(outV, outI, contour, islandStart, indexStart);
    }

    /**
     * Triangulate a simple polygon, optionally seeding interior points.
     *
     * @param ring     interleaved x,y, unit space, closed implicitly, no self-intersections
     * @param interior approximate number of interior points ALONG ONE AXIS. 0 = contour only.
     *                 A deformable mesh needs interior vertices or the inside of the shape is one
     *                 enormous triangle fan that cannot bend; 4-8 is a sensible range.
     * @return the mesh, or null when the ring is not a polygon
     */
    public static Mesh triangulate(float[] ring, int interior) {
        if (ring == null || ring.length < 6) return null;

        // ONE WINDING, always. Ear clipping needs to know which side is "inside", and a contour's
        // winding depends on which way the tracer happened to walk. Normalising here means the
        // rest of this file has one case instead of two, and two cases is where a triangulator
        // silently inverts and emits back-facing triangles.
        // DEDUPE FIRST. A traced ring closes on itself, and simplify keeps both endpoints by
        // construction — so the first and last point are usually the SAME point. Ear clipping
        // turns a duplicate into a zero-area triangle, which is exactly what a deformer later
        // divides by. Caught by the harness before it shipped ("worst=0.0"), and fixed HERE
        // rather than in simplify because any caller may hand us a closed ring.
        float[] poly = dedupe(ring);
        if (poly == null || poly.length < 6) return null;
        if (AlphaContour.signedArea2(poly) < 0f) AlphaContour.reverse(poly);

        int nc = poly.length / 2;
        java.util.List<float[]> pts = new java.util.ArrayList<>(nc + 64);
        for (int i = 0; i < nc; i++) pts.add(new float[]{poly[i * 2], poly[i * 2 + 1]});

        // ── Interior points on a grid, kept only where they are genuinely inside ────────────
        if (interior > 0) {
            float minX = 1f, maxX = 0f, minY = 1f, maxY = 0f;
            for (int i = 0; i < nc; i++) {
                minX = Math.min(minX, poly[i * 2]); maxX = Math.max(maxX, poly[i * 2]);
                minY = Math.min(minY, poly[i * 2 + 1]); maxY = Math.max(maxY, poly[i * 2 + 1]);
            }
            float stepX = (maxX - minX) / (interior + 1f);
            float stepY = (maxY - minY) / (interior + 1f);
            // Keep interior points a margin away from the boundary. A point landing ON an edge
            // produces a zero-area triangle, which a deformer divides by.
            float margin = 0.35f * Math.min(stepX, stepY);
            for (int gy = 1; gy <= interior; gy++) {
                for (int gx = 1; gx <= interior; gx++) {
                    // STAGGERED, not a square grid. Two reasons, and the first is a real bug the
                    // harness caught: a regular grid on a square puts points EXACTLY on the
                    // ear-clipping diagonal — (0.283,0.283), (0.5,0.5), (0.717,0.717) all sit on
                    // y=x — and splitting a triangle at a point on its own edge makes a zero-area
                    // sliver, which is what a deformer divides by. The second is quality:
                    // staggered rows triangulate into fatter triangles than a square lattice, and
                    // a fat triangle deforms better than a sliver.
                    float x = minX + (gx + ((gy & 1) == 1 ? 0.5f : 0f)) * stepX;
                    float y = minY + gy * stepY;
                    if (x >= maxX) continue;
                    if (!contains(poly, x, y)) continue;
                    if (distanceToBoundary(poly, x, y) < margin) continue;
                    pts.add(new float[]{x, y});
                }
            }
        }

        // ── Ear clipping over the contour ──────────────────────────────────────────────────
        java.util.List<Integer> idx = new java.util.ArrayList<>(nc);
        for (int i = 0; i < nc; i++) idx.add(i);
        java.util.List<Integer> tris = new java.util.ArrayList<>(nc * 3);

        int guard = nc * nc + 16;   // bounded: a malformed ring must not spin forever
        while (idx.size() > 3 && guard-- > 0) {
            boolean clipped = false;
            for (int k = 0; k < idx.size(); k++) {
                int i0 = idx.get((k + idx.size() - 1) % idx.size());
                int i1 = idx.get(k);
                int i2 = idx.get((k + 1) % idx.size());
                if (!isEar(pts, idx, i0, i1, i2)) continue;
                tris.add(i0); tris.add(i1); tris.add(i2);
                idx.remove(k);
                clipped = true;
                break;
            }
            if (!clipped) break;   // no ear found: degenerate ring, keep what we have
        }
        if (idx.size() == 3) {
            tris.add(idx.get(0)); tris.add(idx.get(1)); tris.add(idx.get(2));
        }
        if (tris.isEmpty()) return null;

        // ── Stitch interior points in by splitting the triangle that contains each ─────────
        for (int p = nc; p < pts.size(); p++) {
            float[] q = pts.get(p);
            int hit = -1;
            for (int t = 0; t < tris.size(); t += 3) {
                if (pointInTriangle(pts, tris.get(t), tris.get(t + 1), tris.get(t + 2),
                        q[0], q[1])) {
                    hit = t;
                    break;
                }
            }
            if (hit < 0) continue;   // outside every triangle (a concavity) — drop it silently
            int a = tris.get(hit), b = tris.get(hit + 1), c = tris.get(hit + 2);
            // BELT, after the staggering braces. A point sitting ON an edge of the triangle it
            // splits produces a zero-area sliver AND a T-junction: the triangle on the far side
            // of that edge does not know about the point, so the mesh cracks open under
            // deformation. Skipping the point loses one interior vertex and keeps the mesh
            // conforming, which is the right trade — and it must essentially never fire now.
            float[] pa = pts.get(a), pb = pts.get(b), pc = pts.get(c);
            float edgeEps = 1e-4f;
            if (pointSegDist(q[0], q[1], pa[0], pa[1], pb[0], pb[1]) < edgeEps
                    || pointSegDist(q[0], q[1], pb[0], pb[1], pc[0], pc[1]) < edgeEps
                    || pointSegDist(q[0], q[1], pc[0], pc[1], pa[0], pa[1]) < edgeEps) {
                continue;
            }
            // Replace the containing triangle with three, fanning from the new point.
            tris.set(hit, a); tris.set(hit + 1, b); tris.set(hit + 2, p);
            tris.add(b); tris.add(c); tris.add(p);
            tris.add(c); tris.add(a); tris.add(p);
        }

        // ── Drop vertices no triangle uses ────────────────────────────────────────────────
        // Both `continue`s above leave a point in the list that nothing references: one for an
        // interior point that landed in a concavity, one for a point sitting on an edge. An
        // unreferenced vertex is invisible — nothing draws it — but it is NOT harmless:
        //   * it is solved every frame for a pixel that does not exist;
        //   * PuppetWeights sees a vertex the mesh graph cannot reach, so it looks exactly like
        //     a detached island and gets the ride-along fallback meant for real ones.
        // The second one cost an afternoon: a three-piece character reported its far arm moving
        // when the only things moving were two phantom vertices behind it.
        boolean[] used = new boolean[pts.size()];
        for (int i : tris) used[i] = true;
        int[] remap = new int[pts.size()];
        int kept = 0, keptContour = 0;
        for (int i = 0; i < pts.size(); i++) {
            if (!used[i]) { remap[i] = -1; continue; }
            remap[i] = kept++;
            if (i < nc) keptContour++;
        }

        float[] verts = new float[kept * 2];
        for (int i = 0; i < pts.size(); i++) {
            if (remap[i] < 0) continue;
            verts[remap[i] * 2] = pts.get(i)[0];
            verts[remap[i] * 2 + 1] = pts.get(i)[1];
        }
        short[] indices = new short[tris.size()];
        for (int i = 0; i < tris.size(); i++) indices[i] = (short) remap[tris.get(i)];
        return new Mesh(verts, indices, keptContour);
    }

    /**
     * Drop consecutive coincident points, INCLUDING a last point that repeats the first.
     *
     * <p>Epsilon rather than exact equality: a simplified contour can leave two points a
     * hair apart, and a triangle that thin is degenerate for every purpose that matters even
     * though its area is not literally zero.
     */
    private static float[] dedupe(float[] ring) {
        final float eps = 1e-6f;
        int n = ring.length / 2;
        float[] tmp = new float[ring.length];
        int m = 0;
        for (int i = 0; i < n; i++) {
            float x = ring[i * 2], y = ring[i * 2 + 1];
            if (m > 0 && Math.abs(tmp[m - 2] - x) < eps && Math.abs(tmp[m - 1] - y) < eps) {
                continue;
            }
            tmp[m++] = x;
            tmp[m++] = y;
        }
        // ...and the wrap-around pair, which is the one that actually bit.
        if (m >= 4 && Math.abs(tmp[0] - tmp[m - 2]) < eps && Math.abs(tmp[1] - tmp[m - 1]) < eps) {
            m -= 2;
        }
        if (m < 6) return null;
        float[] out = new float[m];
        System.arraycopy(tmp, 0, out, 0, m);
        return out;
    }

    /** True when (i0,i1,i2) is an ear: convex, and containing no other vertex of the ring. */
    private static boolean isEar(java.util.List<float[]> pts, java.util.List<Integer> idx,
                                 int i0, int i1, int i2) {
        float[] a = pts.get(i0), b = pts.get(i1), c = pts.get(i2);
        // Convex in a counter-clockwise ring means a positive cross product. A reflex vertex is
        // not an ear no matter how empty it looks.
        float cross = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        if (cross <= 0f) return false;
        for (int j : idx) {
            if (j == i0 || j == i1 || j == i2) continue;
            float[] p = pts.get(j);
            if (pointInTriangle(a, b, c, p[0], p[1])) return false;
        }
        return true;
    }

    private static boolean pointInTriangle(java.util.List<float[]> pts, int ia, int ib, int ic,
                                           float px, float py) {
        return pointInTriangle(pts.get(ia), pts.get(ib), pts.get(ic), px, py);
    }

    private static boolean pointInTriangle(float[] a, float[] b, float[] c, float px, float py) {
        float d1 = sign(px, py, a[0], a[1], b[0], b[1]);
        float d2 = sign(px, py, b[0], b[1], c[0], c[1]);
        float d3 = sign(px, py, c[0], c[1], a[0], a[1]);
        boolean neg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean pos = (d1 > 0) || (d2 > 0) || (d3 > 0);
        return !(neg && pos);
    }

    private static float sign(float px, float py, float ax, float ay, float bx, float by) {
        return (px - bx) * (ay - by) - (ax - bx) * (py - by);
    }

    /** Even-odd ray cast. Used to reject grid points that fall outside a concave shape. */
    public static boolean contains(float[] ring, float x, float y) {
        int n = ring.length / 2;
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = ring[i * 2], yi = ring[i * 2 + 1];
            float xj = ring[j * 2], yj = ring[j * 2 + 1];
            if (((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi + 1e-20f) + xi)) {
                in = !in;
            }
        }
        return in;
    }

    /** Shortest distance from a point to any edge of the ring. */
    public static float distanceToBoundary(float[] ring, float x, float y) {
        int n = ring.length / 2;
        float best = Float.MAX_VALUE;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            best = Math.min(best, pointSegDist(x, y,
                    ring[j * 2], ring[j * 2 + 1], ring[i * 2], ring[i * 2 + 1]));
        }
        return best;
    }

    private static float pointSegDist(float px, float py, float ax, float ay,
                                      float bx, float by) {
        float ex = bx - ax, ey = by - ay;
        float len2 = ex * ex + ey * ey;
        float t = len2 < 1e-20f ? 0f : ((px - ax) * ex + (py - ay) * ey) / len2;
        t = Math.max(0f, Math.min(1f, t));
        float dx = px - (ax + t * ex), dy = py - (ay + t * ey);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
