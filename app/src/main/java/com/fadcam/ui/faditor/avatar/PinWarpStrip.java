package com.fadcam.ui.faditor.avatar;

import java.util.List;

/**
 * A6 (PLAN_AVATAR_STUDIO §Pin-warp) — pure math for LOW-COUNT pin-warped limb strips.
 *
 * <p>Produces the vertex grid for {@code Canvas.drawBitmapMesh(bitmap, 1, segments, verts, ...)}:
 * the limb art (one sprite cell) is sliced into {@code segments} horizontal bands and each band
 * is swept along the POSED pin chain, preserving the strip's authored width perpendicular to
 * each posed segment. This is the sparse 3-pin skinning the plan adopted (Adobe-Ch style
 * quad-strip, ~microseconds for 9–20 vertices) — NOT dense Live2D mesh authoring, which stays
 * out of scope. Canvas {@code drawBitmapMesh} does the actual warp on every surface the app
 * composites with (Avatar Studio preview, sprite overlay, export overlay), so preview and
 * export share this one vertex authority; no GL surface is needed at puppet scale.</p>
 *
 * <p><b>Authoring convention (documented for the editor):</b> limb art is drawn roughly
 * vertical with its pin chain ordered top→bottom, MONOTONIC in the cell's normalized y
 * (shoulder above elbow above wrist). {@link #isChainMonotonic} guards the convention;
 * renderers fall back to the rigid draw when it fails — never crash, never garble.</p>
 *
 * <p>Bitmap rows above the first pin / below the last pin extrapolate along the end
 * segments, so art overhang (a shoulder cap above the shoulder pin) follows its bone.
 * Width does NOT scale with limb stretch — a stretched chain lengthens, it does not fatten
 * (the correct puppet read, matching FABRIK's rigid-bone output).</p>
 *
 * <p>Pure Java (no android.*) so the JVM harness can pin the geometry:
 * {@code tools/jvm-harness/PinWarpTest.java}.</p>
 */
public final class PinWarpStrip {

    /** Minimum pins for a warp; fewer = rigid draw. */
    public static final int MIN_PINS = 2;

    private PinWarpStrip() { }

    /** True when the rest chain follows the top→bottom authoring convention. */
    public static boolean isChainMonotonic(List<float[]> restPins) {
        if (restPins == null || restPins.size() < MIN_PINS) return false;
        for (int i = 1; i < restPins.size(); i++) {
            if (restPins.get(i)[1] <= restPins.get(i - 1)[1]) return false;
        }
        return true;
    }

    /**
     * Builds the {@code drawBitmapMesh} vertex array (meshWidth=1, meshHeight={@code segments}).
     *
     * @param restPins  K≥2 pins in CELL space ({@code [x,y]}, 0..1, y strictly increasing).
     * @param posedPins K pins in DEST space (canvas px) — the resolver's blended pins (or a
     *                  FABRIK solve) already mapped by the caller into draw coordinates.
     * @param destWidth the strip's authored width in dest px (the rigid draw's cell width);
     *                  a cell-space lateral offset of 1.0 spans this many px.
     * @param segments  horizontal band count (8–12 is plenty at puppet scale).
     * @return xy-interleaved verts, {@code (segments+1)*2} points — or {@code null} when the
     *         inputs violate the contract (caller falls back to the rigid draw).
     */
    public static float[] buildMeshVerts(List<float[]> restPins, List<float[]> posedPins,
                                         float destWidth, int segments) {
        if (restPins == null || posedPins == null) return null;
        int k = restPins.size();
        if (k < MIN_PINS || posedPins.size() != k || segments < 1) return null;
        if (!isChainMonotonic(restPins)) return null;

        float[] verts = new float[(segments + 1) * 2 * 2];
        int out = 0;
        for (int row = 0; row <= segments; row++) {
            float v = row / (float) segments;

            // Rest-chain segment for this bitmap row (ends extrapolate).
            int i = segmentIndexFor(restPins, v);
            float y0 = restPins.get(i)[1], y1 = restPins.get(i + 1)[1];
            float t = (v - y0) / Math.max(1e-6f, y1 - y0); // may be <0 / >1 at the ends

            // Rest centerline x at this row → lateral offsets of the two columns.
            float cx = lerp(restPins.get(i)[0], restPins.get(i + 1)[0], t);

            // Posed base point + per-segment unit direction/normal.
            float qx0 = posedPins.get(i)[0], qy0 = posedPins.get(i)[1];
            float qx1 = posedPins.get(i + 1)[0], qy1 = posedPins.get(i + 1)[1];
            float dx = qx1 - qx0, dy = qy1 - qy0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-6f) { dx = 0f; dy = 1f; len = 1f; } // coincident pins: point down
            float ux = dx / len, uy = dy / len;
            // Normal = direction rotated +90° in y-DOWN screen coords, so a straight-down
            // chain maps the cell's left edge to the left (identity, not a mirror) —
            // pinned by PinWarpTest's identity case.
            float nx = uy, ny = -ux;

            float bx = qx0 + dx * t;
            float by = qy0 + dy * t;

            for (int col = 0; col <= 1; col++) {
                float lat = (col - cx) * destWidth; // col ∈ {0,1} = cell-space u
                verts[out++] = bx + nx * lat;
                verts[out++] = by + ny * lat;
            }
        }
        return verts;
    }

    /** Rest-chain segment index whose y-span contains {@code v} (ends clamp). */
    private static int segmentIndexFor(List<float[]> restPins, float v) {
        int k = restPins.size();
        for (int i = 0; i < k - 1; i++) {
            if (v <= restPins.get(i + 1)[1]) return i;
        }
        return k - 2;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
