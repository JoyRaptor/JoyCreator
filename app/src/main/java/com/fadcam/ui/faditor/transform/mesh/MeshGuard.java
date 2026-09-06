package com.fadcam.ui.faditor.transform.mesh;

/**
 * THE FOLD GUARD. Refuses a deformation that has turned the picture inside out, rather than
 * rendering the result.
 *
 * <h3>What it measures</h3>
 * <p>Every triangle the renderer will actually draw, compared with the same triangle at rest. A
 * triangle whose signed area has flipped sign has folded through itself; a triangle squeezed below
 * {@link #MIN_AREA_FRAC} of its rest area is the state immediately before that. Both are refused.</p>
 *
 * <h3>Why on the SOLVED triangles and not on the handles</h3>
 * <p>Two reasons, and the second is the architectural one.</p>
 * <ol>
 *   <li><b>It is exact rather than a proxy.</b> A smooth interpolant can bulge well past its own
 *       control points, so checking only the authored handles would pass a mesh whose interior has
 *       already folded. The drawn triangles ARE the picture: if none of them inverts, nothing
 *       smears. The earlier attempt at this engine probed a separate 12x12 grid finer than the
 *       lattice for the same reason; measuring the real triangles is both cheaper and stricter.</li>
 *   <li><b>It carries over to puppeteering untouched.</b> This file contains no notion of a cell, a
 *       row, a lattice or a pin — only positions and indices, which every topology produces. A
 *       puppet's ARAP solve gets the identical guard with no new code.</li>
 * </ol>
 *
 * <h3>What refusal means</h3>
 * <p>The gesture simply stops moving, which is always recoverable. Rendering through a folded cell
 * is not — the prototype found it "smears the picture across the screen", and an export would bake
 * it in. Same reasoning as {@code TransformQuad}'s convexity refusal, one level down.</p>
 *
 * <h3>Cost</h3>
 * <p>One cross product per triangle: 1,152 triangles at the finest lattice level is about 12,000
 * flops, paid once per drag frame while a thumb is down, never on the render path. Allocation-free.</p>
 *
 * <p>No Android imports.</p>
 */
public final class MeshGuard {

    private MeshGuard() {}

    /**
     * Smallest fraction of its rest area a triangle may shrink to before the deformation is
     * refused. An AREA floor rather than an angle, for the same reason {@code TransformQuad}'s
     * convexity epsilon is one: a sliver is not merely ugly, it is one pixel of drag away from
     * being inside out.
     */
    public static final float MIN_AREA_FRAC = 0.02f;

    /** Convenience at {@link #MIN_AREA_FRAC}. */
    public static boolean isValid(MeshBuffers b) {
        return isValid(b, MIN_AREA_FRAC);
    }

    /**
     * True when every drawn triangle keeps its rest orientation and at least {@code minAreaFrac} of
     * its rest area, and every coordinate is finite.
     *
     * <p>Orientation is compared against the REST triangle's own sign rather than assumed positive,
     * so a topology that emits either winding — or mixed winding, which an irregular triangulation
     * may well do — is handled without a special case.</p>
     */
    public static boolean isValid(MeshBuffers b, float minAreaFrac) {
        if (b == null || !b.hasGeometry()) return true;
        float[] pos = b.positions, rest = b.rest;
        short[] idx = b.indices;
        int n = b.indexCount();
        for (int i = 0; i < n; i += 3) {
            int a = (idx[i] & 0xFFFF) * 2;
            int c = (idx[i + 1] & 0xFFFF) * 2;
            int d = (idx[i + 2] & 0xFFFF) * 2;
            float restArea = cross(rest, a, c, d);
            float area = cross(pos, a, c, d);
            if (Float.isNaN(area) || Float.isInfinite(area)) return false;
            if (restArea == 0f) continue;                 // degenerate at rest; nothing to protect
            float ratio = area / restArea;
            if (ratio < minAreaFrac) return false;         // flipped (negative) or crushed
        }
        return true;
    }

    /**
     * Would this pose be renderable? Solves into {@code scratch} and guards the result, leaving the
     * caller's live buffers untouched — what a drag calls before committing a candidate value.
     *
     * <p>{@code scratch} must already be bound to {@code topology}. Allocation-free.</p>
     */
    public static boolean accepts(MeshTopology topology, MeshDeformer deformer,
                                  MeshBuffers scratch, float[] candidatePose) {
        if (topology == null || deformer == null || scratch == null) return false;
        if (!deformer.solve(topology, scratch, candidatePose)) return false;
        return isValid(scratch);
    }

    /** Twice the signed area of the triangle at packed offsets {@code a,b,c}. */
    private static float cross(float[] p, int a, int b, int c) {
        float abx = p[b] - p[a], aby = p[b + 1] - p[a + 1];
        float acx = p[c] - p[a], acy = p[c + 1] - p[a + 1];
        return abx * acy - aby * acx;
    }
}
