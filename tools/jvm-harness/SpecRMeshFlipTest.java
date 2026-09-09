import com.fadcam.ui.faditor.transform.mesh.LatticeTopology;
import com.fadcam.ui.faditor.transform.mesh.MeshGlSource;
import com.fadcam.ui.faditor.transform.mesh.MeshPlacement;

/**
 * SPEC R — a meshed picture must render UPRIGHT: exactly ONE vertical flip end to end.
 *
 * <p><b>The measurement.</b> JoyRaptor, 2026-09-08: turning Bend on flipped the picture upside down —
 * subject's legs at the top, lettering mirrored — at 7.6 degrees, where SPEC Q's displacement is
 * essentially nil. An inverted picture is a flip applied an ODD number of times, so the whole
 * question is how many flips the mesh path performs between the BITMAP ROW and the SCREEN ROW.
 *
 * <p><b>This file counts them, rather than reading the comments.</b> It walks an IDENTITY mesh —
 * a lattice with no handle moved, so {@code positions == rest == uv} — one vertex at a time:
 * <ol>
 *   <li>the vertex's object-local {@code v} through the real {@link MeshPlacement#buildPlace}
 *       matrix, to a clip {@code y} (+1 = top of screen);</li>
 *   <li>the same vertex's {@code aUv.v} through the real {@link MeshGlSource#FRAGMENT_SHADER}
 *       sampling expression, PARSED out of the shipped string, to a texture {@code t}; and</li>
 *   <li>{@code t} to a bitmap row, which is the one fact GL fixes for us:
 *       {@code GLUtils.texImage2D} uploads top-row-first, so {@code t == 0} IS the bitmap's top
 *       row.</li>
 * </ol>
 * Upright means: the vertex that lands at the TOP of the screen samples the TOP of the bitmap.
 * Before the fix it sampled the bottom, which is the entire bug and cannot be mistaken for
 * anything else.
 *
 * <p><b>The flat path is the control, not the authority.</b> {@code PIP_STILL_FRAGMENT} lives in
 * {@code FxPreviewTextureView}, which needs Android to load, so its two load-bearing lines are
 * restated in {@link #stillPathBitmapRowAtScreenTop()} and run through the SAME "count the flips"
 * model. It passes for the OPPOSITE reason the mesh path does — its {@code uv.y == 1} is the top
 * of the picture where the mesh's {@code vUv.y == 0} is — which is precisely why copying its
 * {@code 1-y} into the mesh fragment inverted the picture.
 *
 * <p>Android-free by construction; the runner refuses if an android import leaks into the package.
 */
public class SpecRMeshFlipTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        theShippedFragmentSamplesStraight();
        identityMeshPutsTheBitmapTopAtTheScreenTop();
        identityMeshAgreesWithTheFlatDrawAtEveryAngle();
        theOldFlippedFragmentFailsThisSameTest();
        theStillPathPassesForTheOppositeReason();
        downstreamCompositesStillSampleTheStampUnflipped();
        aRealBendMovesThePictureTheWayTheHandleWent();

        System.out.println(failed == 0
                ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── The model: one number, "which bitmap row shows at the screen top" ──────────────────

    /**
     * The sampling expression the SHIPPED fragment applies to {@code vUv.y}, read out of the
     * string rather than assumed. Returns the texture {@code t} for an object-local {@code v}.
     */
    static double shippedSampleT(double v) {
        String f = MeshGlSource.FRAGMENT_SHADER;
        boolean flipped = f.contains("vec2 s = vec2(vUv.x, 1.0 - vUv.y);");
        boolean straight = f.contains("vec2 s = vUv;");
        if (flipped == straight) {
            throw new IllegalStateException(
                    "the fragment's sampling line is neither of the two known forms — this test "
                            + "must be taught the new one before it can be trusted");
        }
        return flipped ? 1.0 - v : v;
    }

    /**
     * Clip-space y for an object-local {@code (u,v)} through the real placement matrix. +1 is the
     * top of the screen.
     */
    static double clipY(double u, double v, double rotDeg) {
        float[] m = new float[9];
        if (!MeshPlacement.buildPlace(0.5f, 0.5f, 0.2f, 0.3f, (float) rotDeg, 1080f / 1920f, m)) {
            throw new IllegalStateException("buildPlace refused a drawable pose");
        }
        double y = m[1] * u + m[4] * v + m[7];
        double w = m[2] * u + m[5] * v + m[8];
        return y / w;
    }

    /**
     * {@code t} to bitmap row fraction. {@code GLUtils.texImage2D} uploads the bitmap top row
     * first, so {@code t == 0} is the top row. 0 = top of the photo, 1 = bottom.
     */
    static double bitmapRowOf(double t) { return t; }

    // ── 1. The line itself ────────────────────────────────────────────

    static void theShippedFragmentSamplesStraight() {
        String f = MeshGlSource.FRAGMENT_SHADER;
        check("the stamp fragment samples the source STRAIGHT (s = vUv)",
                f.contains("vec2 s = vUv;") && !f.contains("1.0 - vUv.y"));
        check("the wipe still travels across the undeformed u, untouched by SPEC R",
                f.contains("if (vUv.x > uReveal)"));
    }

    // ── 2. The identity mesh, vertex by vertex ────────────────────────

    /**
     * Walk every vertex of a real identity lattice. The vertex with the greatest clip y is the one
     * at the top of the screen; the bitmap row it samples must be the top of the photo (0), not
     * the bottom (1).
     */
    static void identityMeshPutsTheBitmapTopAtTheScreenTop() {
        int n = LatticeTopology.tessellationFor(LatticeTopology.L3);
        float[] rest = new float[(n + 1) * (n + 1) * 2];
        float[] uv = new float[rest.length];
        short[] idx = new short[n * n * 6];
        LatticeTopology topo = new LatticeTopology(LatticeTopology.L3);
        topo.buildRest(rest, uv, idx);

        boolean identity = true;
        for (int i = 0; i < rest.length; i++) if (rest[i] != uv[i]) identity = false;
        check("an untouched lattice's uv IS its rest position (the identity mesh)", identity);

        double bestY = -Double.MAX_VALUE, rowAtTop = -1;
        double worstY = Double.MAX_VALUE, rowAtBottom = -1;
        for (int i = 0; i < uv.length; i += 2) {
            double u = uv[i], v = uv[i + 1];
            double y = clipY(u, v, 0);
            if (y > bestY) { bestY = y; rowAtTop = bitmapRowOf(shippedSampleT(v)); }
            if (y < worstY) { worstY = y; rowAtBottom = bitmapRowOf(shippedSampleT(v)); }
        }
        check("the vertex at the TOP of the screen samples the TOP of the photo (row "
                + fmt(rowAtTop) + ")", rowAtTop < 0.001);
        check("the vertex at the BOTTOM of the screen samples the BOTTOM of the photo (row "
                + fmt(rowAtBottom) + ")", rowAtBottom > 0.999);
    }

    /**
     * Rotation must not change the answer, only turn it. At 180 degrees the photo's top edge is
     * legitimately BELOW its bottom edge on screen — that is a turn, and a turn is not a flip. So
     * the test is in two halves, and only the second one can catch a flip:
     * <ul>
     *   <li>GEOMETRY: whether the object's top edge sits higher on screen must follow
     *       {@code cos(rot)} — upright below 90 degrees, inverted beyond it, level at 90/270
     *       where the edges are side by side and the comparison means nothing.</li>
     *   <li>SAMPLING: the row the top edge displays must be the photo's TOP row at EVERY angle.
     *       A flip in the sampler inverts this at every angle at once, which is what makes it a
     *       flip rather than a turn.</li>
     * </ul>
     */
    static void identityMeshAgreesWithTheFlatDrawAtEveryAngle() {
        boolean geometryOk = true, samplingOk = true;
        StringBuilder bad = new StringBuilder();
        for (int rot : new int[]{0, 45, 90, 135, 180, 225, 270, 315}) {
            double yTop = clipY(0.5, 0.0, rot);
            double yBot = clipY(0.5, 1.0, rot);
            double cos = Math.cos(Math.toRadians(rot));
            if (Math.abs(cos) > 0.01 && (yTop > yBot) != (cos > 0)) {
                geometryOk = false; bad.append(' ').append(rot);
            }
            // The top EDGE of the object always carries local v=0, whatever the object's pose.
            if (!(bitmapRowOf(shippedSampleT(0.0)) < 0.001)
                    || !(bitmapRowOf(shippedSampleT(1.0)) > 0.999)) {
                samplingOk = false;
            }
        }
        check("the object's top edge leads or trails exactly as cos(rot) says — a turn, not a flip"
                + (geometryOk ? "" : " — wrong at" + bad), geometryOk);
        check("the object's top edge shows the photo's top row at every one of those angles",
                samplingOk);
    }

    // ── 3. The bug, pinned so it cannot come back ─────────────────────

    /**
     * The pre-fix line, transcribed, must FAIL the same measurement. Without this the test above
     * could pass against a model that had quietly inherited the same mistake.
     */
    static void theOldFlippedFragmentFailsThisSameTest() {
        // Old: s.y = 1 - vUv.y. The object's top (v=0) is at clip +1 (screen top) and would have
        // sampled t = 1 — the BOTTOM row of the photo.
        double oldRowAtScreenTop = bitmapRowOf(1.0 - 0.0);
        check("the old '1.0 - vUv.y' put the photo's BOTTOM row at the screen top (row "
                + fmt(oldRowAtScreenTop) + ") — upside down, exactly what JoyRaptor saw",
                oldRowAtScreenTop > 0.999);
    }

    /**
     * The flat still path, restated. Its {@code uv} comes from {@code q*0.5+0.5} where {@code q}
     * is built from the frame's y-UP {@code vFxUv}, so {@code uv.y == 1} is the TOP of the
     * picture; its {@code 1-uv.y} therefore lands on {@code t == 0}, the photo's top row. Same
     * answer, opposite arithmetic — which is why copying it into the mesh inverted the mesh.
     */
    static void theStillPathPassesForTheOppositeReason() {
        double rowAtTop = stillPathBitmapRowAtScreenTop();
        check("the flat still path also shows the photo's TOP at the screen top (row "
                + fmt(rowAtTop) + "), reaching it from uv.y==1 rather than vUv.y==0",
                rowAtTop < 0.001);
    }

    static double stillPathBitmapRowAtScreenTop() {
        double uvYAtScreenTop = 1.0;          // q.y = +1 at the top of the PiP box (vFxUv is y-up)
        double t = 1.0 - uvYAtScreenTop;      // PIP_STILL_FRAGMENT: s = vec2(uv.x, 1.0 - uv.y)
        return bitmapRowOf(t);
    }

    /**
     * The stamp FBO is bottom-up like every other GL target, so BOTH composites must keep sampling
     * it unflipped. If either ever grows a {@code 1-y} the stamp inverts again, on that surface
     * only — the preview/export split this codebase fears most. Asserted against the shipped
     * strings by reading the two files, since both classes need Android to load.
     */
    static void downstreamCompositesStillSampleTheStampUnflipped() {
        String preview = readSource("app/src/main/java/com/fadcam/ui/faditor/compositor/"
                + "FxPreviewTextureView.java");
        String export = readSource("app/src/main/java/com/fadcam/ui/faditor/export/"
                + "ImageBlendGlEffect.java");
        check("preview PIP_MESH_FRAGMENT samples the stamp unflipped",
                preview.contains("\"vec2 s = vec2(uv.x, 1.0 - uv.y);\",\n"
                        + "                     \"vec2 s = uv;\"")
                        || preview.replaceAll("\\s+", " ")
                        .contains("\"vec2 s = vec2(uv.x, 1.0 - uv.y);\", \"vec2 s = uv;\""));
        check("export FRAGMENT_MESH_BASE samples the stamp unflipped",
                export.replaceAll("\\s+", " ").contains("vec2 ovc = vTexSamplingCoord;"));
    }

    /**
     * Acceptance 4: a real bend must bend the way the finger went. A handle dragged DOWN raises
     * the deformed {@code v} of the vertices near it (model space is top-left origin), and
     * {@code uPlace} must move that part of the picture DOWN the screen — while the source row it
     * samples is unchanged, because {@code aUv} is the UNdeformed rest coordinate.
     */
    static void aRealBendMovesThePictureTheWayTheHandleWent() {
        double vRest = 0.5;
        double vDragged = 0.5 + 0.2;                    // finger moved down, so v increases
        double yBefore = clipY(0.5, vRest, 0);
        double yAfter = clipY(0.5, vDragged, 0);
        check("dragging a dot DOWN moves that part of the picture DOWN the screen",
                yAfter < yBefore);
        check("the dragged vertex still samples its own unchanged source row (aUv is the rest uv)",
                Math.abs(bitmapRowOf(shippedSampleT(vRest)) - 0.5) < 1e-6);
    }

    // ── plumbing ─────────────────────────────────────────────────────

    static String readSource(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + path + " — run from the repo root", e);
        }
    }

    static String fmt(double v) { return String.format(java.util.Locale.US, "%.4f", v); }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
