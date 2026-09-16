package com.fadcam.ui.faditor.transform.mesh;

/**
 * WHICH TRIANGLES ARE IN FRONT — a continuous depth across the picture, not a per-piece choice.
 *
 * <p>JoyRaptor, 2026-09-15: <i>"I often have a character 3/4 stance. Shoulder is behind body, elbow
 * is behind body, hand may reach behind their head or in front of their nose or belly. Could we use
 * the falloff heat map style thing to determine a threshold where the tris stop rendering behind
 * layer 2 and start being in front?"</i>
 *
 * <p>Yes, and the weight table this engine already builds is the right thing to do it with.
 *
 * <h3>Depth is a FIELD, blended by the same weights that bend the picture</h3>
 * <p>Each pin carries a depth. Every vertex takes the weighted average of the pins that reach it,
 * using the identical {@link PuppetWeights} row that decides how much each pin MOVES it. So a
 * shoulder at -1 and a wrist at +1 make the arm pass through the body somewhere along the forearm,
 * and it lands there because that is where the influence actually changes hands — not at a number
 * somebody had to tune.
 *
 * <p><b>There is no threshold to set.</b> The crossing is wherever the arm's depth passes the
 * body's, which falls out of the two depths and needs no third control. That is the part worth
 * keeping: a threshold knob would have to be re-tuned for every character.
 *
 * <h3>Why the seam does not show</h3>
 * <p>Splitting a limb mid-way sounds like it should leave a visible cut, and it does not, for a
 * reason worth writing down: <b>depth only matters where things overlap.</b> Wherever the forearm
 * is over the body, the cut is hidden behind the body's own pixels; wherever it is over nothing,
 * front and behind draw identically. The only way to see the seam is for the limb to overlap
 * something AND change depth in the same few pixels, which is the pose where it genuinely is
 * passing through.
 *
 * <h3>One draw call, not one per piece</h3>
 * <p>GL blends primitives in submission order, so sorting the INDEX list by depth and drawing it
 * once puts every triangle in the right place — no state changes, no batching, and it subsumes
 * per-island ordering (an island's depth is just a constant added to its vertices). This replaced
 * a loop of one draw call per island, which could only ever put a WHOLE limb in front or behind.
 *
 * <p>The honest limitation, stated rather than discovered later: sorting by a triangle's average
 * depth is the painter's algorithm, and the painter's algorithm cannot resolve two long triangles
 * that genuinely interleave. On a mesh of this density, with a field this smooth, that does not
 * arise — but it is why this is sorting and not a depth buffer.
 *
 * <p>No Android imports.
 */
public final class MeshDepth {

    private MeshDepth() {}

    /**
     * Order the triangles back to front.
     *
     * <p>A STABLE merge sort, so triangles at equal depth keep the order the triangulator emitted
     * them in and nothing flickers between two arrangements nobody chose. Merge rather than
     * insertion because a keyframed depth re-sorts every frame, and insertion sort on 600
     * triangles is a third of a millisecond that would be paid sixty times a second.
     *
     * @param indices     the triangle list, three entries per triangle
     * @param indexCount  how much of {@code indices} is live
     * @param vertexDepth one depth per vertex; higher is nearer the viewer
     * @param order       out: triangle indices, back first. At least {@code indexCount / 3} long.
     * @param scratchA    reusable, at least {@code indexCount / 3} long
     * @param scratchKey  reusable, at least {@code indexCount / 3} long
     * @return how many triangles were ordered, or -1 when the inputs do not line up
     */
    public static int sortTriangles(short[] indices, int indexCount, float[] vertexDepth,
                                    int[] order, int[] scratchA, float[] scratchKey) {
        if (indices == null || vertexDepth == null || order == null) return -1;
        int tris = indexCount / 3;
        if (tris <= 0 || order.length < tris) return -1;
        if (scratchA == null || scratchA.length < tris) return -1;
        if (scratchKey == null || scratchKey.length < tris) return -1;
        if (indexCount > indices.length) return -1;

        int verts = vertexDepth.length;
        for (int t = 0; t < tris; t++) {
            int a = indices[t * 3] & 0xFFFF;
            int b = indices[t * 3 + 1] & 0xFFFF;
            int c = indices[t * 3 + 2] & 0xFFFF;
            if (a >= verts || b >= verts || c >= verts) return -1;
            // The average of the corners. A triangle is small enough that its centre is the only
            // honest single number for "how far away is this".
            scratchKey[t] = (vertexDepth[a] + vertexDepth[b] + vertexDepth[c]) / 3f;
            order[t] = t;
        }
        mergeSort(order, scratchA, scratchKey, 0, tris);
        return tris;
    }

    /** Write the triangles out in {@code order}, ready to draw in one call. */
    public static boolean applyOrder(short[] indices, int[] order, int triCount, short[] out) {
        if (indices == null || order == null || out == null) return false;
        if (out.length < triCount * 3 || order.length < triCount) return false;
        for (int k = 0; k < triCount; k++) {
            int t = order[k];
            if (t < 0 || t * 3 + 2 >= indices.length) return false;
            out[k * 3] = indices[t * 3];
            out[k * 3 + 1] = indices[t * 3 + 1];
            out[k * 3 + 2] = indices[t * 3 + 2];
        }
        return true;
    }

    /**
     * A number that changes whenever the ordering would — so the renderer can skip re-uploading an
     * index list that has not moved.
     *
     * <p>Depth comes from the weights (fixed at bind) and the authored depths, never from the POSE,
     * so bending a character does not re-sort it. That is deliberate: a depth that shifted as the
     * limb moved would be magic, and magic in a z-order is a character that pops inside out mid
     * gesture for no reason the animator can see.
     */
    public static int stampOf(float[] handleZ, float[] groupZ) {
        int h = 17;
        if (handleZ != null) for (float f : handleZ) h = h * 31 + Float.floatToIntBits(f);
        if (groupZ != null) for (float f : groupZ) h = h * 31 + Float.floatToIntBits(f);
        return h;
    }

    // ── a stable merge sort over an int[] keyed by a float[] ────────────────────────────────

    private static void mergeSort(int[] a, int[] tmp, float[] key, int lo, int hi) {
        if (hi - lo < 2) return;
        int mid = (lo + hi) >>> 1;
        mergeSort(a, tmp, key, lo, mid);
        mergeSort(a, tmp, key, mid, hi);
        if (key[a[mid - 1]] <= key[a[mid]]) return;         // already in order: the common case
        int i = lo, j = mid, k = lo;
        while (i < mid && j < hi) {
            // <= keeps it STABLE: equal depths leave the left run first.
            tmp[k++] = (key[a[i]] <= key[a[j]]) ? a[i++] : a[j++];
        }
        while (i < mid) tmp[k++] = a[i++];
        while (j < hi) tmp[k++] = a[j++];
        System.arraycopy(tmp, lo, a, lo, hi - lo);
    }
}
