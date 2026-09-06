package com.fadcam.ui.faditor.transform.mesh;

/**
 * THE PROJECTION SEAM between the engine's unit space and the stage the user's thumb is on.
 *
 * <p>A handle's authored value lives in the picture's OWN unit space and is re-projected through
 * the CURRENT quad every frame. That is the property the interaction design asked for by name: a
 * structural edit — scale an edge, tilt a corner, pinch, fold — carries the bend along instead of
 * wiping it, because the bend was never stored in stage pixels. "Scaling the edge undid my bend"
 * was the prototype's first-round bug and this is its cure.</p>
 *
 * <p>Forward and inverse must be exact mirrors or a dot will not land under the thumb, which is why
 * both live in one file rather than one in the overlay and one in the engine.</p>
 *
 * <h3>Composition order: deformation first (local), homography second</h3>
 * <pre>  (u,v)  -&gt;  D(u,v)      the mesh, in object-local space   (this package)
 *         -&gt;  H . [D,1]    the corner-pin homography         (TransformQuad.unitToQuad)
 *         -&gt;  P . [ . ]    placement: size, aspect, scale, rotation, centre
 *         -&gt;  gl_Position with w = the homography's z, UNDIVIDED</pre>
 * <p>Three reasons, in order of force. It is what the user means ("bend the picture, then tilt the
 * bent picture away from me" — the reverse makes the bend handles move non-uniformly as the
 * perspective changes, which is unusable). Perspective correctness comes free, because doing H last
 * leaves one honest {@code w} per vertex. And the handles stay unambiguous: bend dots are local
 * points projected forward, corner handles are H's own outputs.</p>
 *
 * <p>Note that a 4-point {@code setPolyToPoly} is a projective homography while a 2x2 mesh is
 * bilinear — <b>the mesh is NOT the degenerate case of corner-pin.</b> They keep the same outline
 * and disagree everywhere inside it. Anyone who fuses them ships a corner-pin that stops looking
 * like perspective the moment the bend net is switched on.</p>
 *
 * <p>{@link #applyHomography} is duplicated from {@code TransformQuad} rather than called, and that
 * is deliberate: this package's whole value is that it has no imports at all, so it loads on the
 * JVM harness and lifts into another app whole. Twelve lines of arithmetic is a cheaper price than
 * a dependency, and the two are pinned equal by a harness test.</p>
 *
 * <p>No Android imports.</p>
 */
public final class MeshProjection {

    private MeshProjection() {}

    /**
     * Where handle {@code i}'s dot is DRAWN: its rest position plus its authored nudge, pushed
     * through the quad's homography.
     *
     * <p>Topology-agnostic — it asks {@link MeshTopology} for the rest position rather than
     * computing a row and a column, so a puppet pin projects through the identical call.</p>
     *
     * @param h    row-major 3x3 from {@code TransformQuad.unitToQuad}
     * @param out2 receives the stage-space {@code {x, y}}
     * @return false when the homography is degenerate — the caller then draws nothing rather than
     *         drawing at infinity
     */
    public static boolean projectHandle(MeshTopology topo, float[] handles, int i,
                                        float[] h, float[] out2) {
        if (topo == null || i < 0 || i >= topo.handleCount()) return false;
        float u = topo.handleRestX(i), v = topo.handleRestY(i);
        if (handles != null && topo.handleComponents() == 2 && handles.length >= (i + 1) * 2) {
            u += handles[i * 2];
            v += handles[i * 2 + 1];
        }
        return applyHomography(h, u, v, out2);
    }

    /**
     * The inverse half: a stage point back into the object's unit space, so a drag becomes a nudge.
     * {@code hInv} is {@code TransformQuad.invert3x3(TransformQuad.unitToQuad(quad))}.
     */
    public static boolean stageToUnit(float[] hInv, float x, float y, float[] out2) {
        return applyHomography(hInv, x, y, out2);
    }

    /**
     * Turn a finished drag into an authored value for handle {@code i}, clamped by the deformer.
     *
     * @param out2 receives the new {@code (du, dv)}; the caller writes it into the pose and runs
     *             {@link MeshGuard} before committing
     * @return false when the inverse homography is degenerate
     */
    public static boolean dragToHandle(MeshTopology topo, MeshDeformer deformer, int i,
                                       float[] hInv, float stageX, float stageY, float[] out2) {
        if (topo == null || deformer == null || i < 0 || i >= topo.handleCount()) return false;
        if (!applyHomography(hInv, stageX, stageY, out2)) return false;
        out2[0] = deformer.clampComponent(out2[0] - topo.handleRestX(i));
        out2[1] = deformer.clampComponent(out2[1] - topo.handleRestY(i));
        return true;
    }

    /** Push {@code (u,v)} through a row-major 3x3. */
    public static boolean applyHomography(float[] m, float u, float v, float[] out2) {
        if (m == null || m.length < 9 || out2 == null) return false;
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
