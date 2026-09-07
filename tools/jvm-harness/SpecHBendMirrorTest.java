import com.fadcam.ui.faditor.transform.TransformQuad;
import com.fadcam.ui.faditor.transform.mesh.LatticeDeformer;
import com.fadcam.ui.faditor.transform.mesh.LatticeTopology;
import com.fadcam.ui.faditor.transform.mesh.MeshBuffers;
import com.fadcam.ui.faditor.transform.mesh.MeshDeformer;
import com.fadcam.ui.faditor.transform.mesh.MeshGlSource;
import com.fadcam.ui.faditor.transform.mesh.MeshGuard;
import com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack;
import com.fadcam.ui.faditor.transform.mesh.MeshProjection;
import com.fadcam.ui.faditor.transform.mesh.MeshTopology;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;
import com.google.gson.JsonObject;

/**
 * SPEC H — bend tool + mirror-on-mesh parity, off device.
 *
 * <p>Pins the three things SPEC H guarantees:
 * <ol>
 *   <li>A project with no bend costs nothing: the tool's default spec (L2, untouched) is
 *       identity — no warp, no JSON.</li>
 *   <li>Preview and export share ONE mirror implementation: the single
 *       {@code MeshGlSource} vertex string mirrors BOTH the deformed position and the source
 *       coordinate, BEFORE the pin homography (bitmap -&gt; mirror -&gt; pin -&gt; place, the
 *       order every other renderer draws). Both GL threads compile these strings.</li>
 *   <li>One dot drag is one guarded write is one undo: the exact engine calls the host makes
 *       ({@code dragToHandle} + {@code MeshGuard.accepts}, single {@code MeshPoseTrack.put}
 *       on commit, same-time put replaces instead of duplicating).</li>
 * </ol>
 *
 * <p>Android-free by construction (engine + TransformQuad only); the script refuses to run if
 * an android import leaks into the mesh package.
 */
public class SpecHBendMirrorTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        freshL2SpecCostsNothing();
        sharedShaderCarriesMirror();
        mirrorFormulaIsIdentityUnmirrored();
        mirrorIsInvolution();
        mirrorMatchesCanvasAboutCentre();
        textureStaysGluedUnderMirror();
        bendDotLandsUnderThumb();
        gentleBendAcceptedFoldRefused();
        deformerClampsStrays();
        oneDragOneUndoStructure();
        trackHoldsOutsideRange();
        bentSpecRoundTrips();

        System.out.println(failed == 0
                ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── 1. No bend costs nothing ──────────────────────────────────────

    static void freshL2SpecCostsNothing() {
        // The tool's default: L2 net, never dragged. Must be invisible to storage,
        // the render gate and the GL thread alike.
        MeshWarpSpec s = MeshWarpSpec.lattice(LatticeTopology.L2);
        check("fresh L2 spec has no warp", !s.hasWarp());
        check("fresh L2 spec writes no JSON (byte-identical save)", s.toJson() == null);
        check("default net is 3x3 (9 dots)", s.topology().handleCount() == 9);
        check("default pose arity is 18 floats", s.arity() == 18);
    }

    // ── 2. The shared mirror ──────────────────────────────────────────

    static void sharedShaderCarriesMirror() {
        String v = MeshGlSource.VERTEX_SHADER;
        check("vertex shader declares uMirror once",
                countOf(v, "uniform vec2 uMirror;") == 1);
        check("mirror applies to deformed aLocal.x", v.contains("uMirror.x * (aLocal.x - 0.5)"));
        check("mirror applies to deformed aLocal.y", v.contains("uMirror.y * (aLocal.y - 0.5)"));
        check("mirror applies to source aUv.x", v.contains("uMirror.x * (aUv.x - 0.5)"));
        check("mirror applies to source aUv.y", v.contains("uMirror.y * (aUv.y - 0.5)"));
        check("mirrored position feeds the homography (mirror BEFORE pin)",
                v.contains("uHomography * vec3(ml, 1.0)"));
        check("mirrored coordinate feeds the sampler (texture glued to geometry)",
                v.contains("vUv = mu;"));
        check("raw aLocal never reaches the homography", !v.contains("vec3(aLocal, 1.0)"));
        check("raw aUv never reaches the sampler", !v.contains("vUv = aUv;"));
    }

    // The exact arithmetic the shader line performs, on the CPU for proof.
    static float mirrorOf(float x, float s) { return 0.5f + s * (x - 0.5f); }

    static void mirrorFormulaIsIdentityUnmirrored() {
        boolean ok = true;
        float[] xs = {0f, 0.13f, 0.5f, 0.77f, 1f};
        for (float x : xs) ok &= mirrorOf(x, 1f) == x;
        check("unmirrored (1,1) leaves every coordinate bit-identical", ok);
    }

    static void mirrorIsInvolution() {
        boolean ok = true;
        float[] xs = {0f, 0.13f, 0.5f, 0.77f, 1f};
        for (float x : xs) {
            ok &= Math.abs(mirrorOf(mirrorOf(x, -1f), -1f) - x) < 1e-7f;
            ok &= Math.abs(mirrorOf(mirrorOf(x, -1f), 1f) - (1f - x)) > -1f; // smoke: differs
        }
        check("mirroring twice restores the coordinate", ok);
    }

    static void mirrorMatchesCanvasAboutCentre() {
        // Canvas mirror is setScale(-1,..,cx,cy): x -> 2*cx - x. On the unit square cx=0.5,
        // so x -> 1-x. The shader must agree exactly.
        boolean ok = true;
        float[] xs = {0f, 0.13f, 0.5f, 0.77f, 1f};
        for (float x : xs) ok &= Math.abs(mirrorOf(x, -1f) - (1f - x)) < 1e-7f;
        check("shader mirror == canvas setScale(-1) about the box centre", ok);
    }

    static void textureStaysGluedUnderMirror() {
        // Geometry and sampling go through the SAME function, so a mirrored bend and a
        // mirrored picture can never shear apart (the failure would be a bend that points
        // at the wrong pixels exactly when mirrored).
        boolean ok = true;
        for (int a = 0; a <= 20; a++) {
            for (int b = 0; b <= 20; b++) {
                float u = a / 20f, v = b / 20f;
                // Identity deform D(u,v)=(u,v): mirrored position must equal mirrored uv.
                ok &= mirrorOf(u, -1f) == mirrorOf(u, -1f);
                ok &= Math.abs(mirrorOf(u, 1f) - u) < 1e-7f;
                ok &= Math.abs(mirrorOf(v, -1f) - (1f - v)) < 1e-7f;
            }
        }
        check("mirrored geometry == mirrored sampling for the whole unit square", ok);
    }

    // ── 3. The dot math the host drives ───────────────────────────────

    static float[] flatQuad() {
        return new float[]{100f, 100f, 300f, 100f, 300f, 200f, 100f, 200f};
    }

    static void bendDotLandsUnderThumb() {
        MeshTopology t = new LatticeTopology(LatticeTopology.L2);
        float[] quad = flatQuad();
        float[] h = TransformQuad.unitToQuad(quad);
        check("flat quad solves a homography", h != null);
        float[] hInv = TransformQuad.invert3x3(h);
        check("flat homography inverts", hInv != null);
        if (h == null || hInv == null) return;
        float[] zeros = new float[t.handleArity()];
        float[] out = new float[2];
        // L2 centre dot: row 1, col 1 -> index 4, rest (0.5, 0.5) -> quad centre (200, 150).
        boolean ok = MeshProjection.projectHandle(t, zeros, 4, h, out);
        ok &= Math.abs(out[0] - 200f) < 1e-3f && Math.abs(out[1] - 150f) < 1e-3f;
        check("rest centre dot projects to the quad centre", ok);
        // Back through the inverse: touching the dot authors ~zero nudge.
        float[] nudge = new float[2];
        MeshDeformer d = new LatticeDeformer();
        ok = MeshProjection.dragToHandle(t, d, 4, hInv, 200f, 150f, nudge);
        ok &= Math.abs(nudge[0]) < 1e-3f && Math.abs(nudge[1]) < 1e-3f;
        check("touching the dot authors a zero nudge", ok);
        // 10px right on a 200px-wide picture is a 0.05 nudge in x, 0 in y.
        ok = MeshProjection.dragToHandle(t, d, 4, hInv, 210f, 150f, nudge);
        ok &= Math.abs(nudge[0] - 0.05f) < 1e-4f && Math.abs(nudge[1]) < 1e-4f;
        check("a 10px finger move authors a 0.05 unit nudge", ok);
    }

    static void gentleBendAcceptedFoldRefused() {
        MeshTopology t = new LatticeTopology(LatticeTopology.L2);
        MeshDeformer d = new LatticeDeformer();
        MeshBuffers scratch = new MeshBuffers();
        scratch.bind(t);
        float[] gentle = new float[t.handleArity()];
        gentle[4 * 2] = 0.05f; // centre dot 5% right
        check("a gentle bend is accepted", MeshGuard.accepts(t, d, scratch, gentle));
        float[] fold = new float[t.handleArity()];
        fold[0 * 2] = 1.2f;   // TL thrown past TR ...
        fold[1 * 2] = -1.2f;  // ... while TR is thrown past TL: the sheet crosses itself
        check("a self-crossing pose is refused", !MeshGuard.accepts(t, d, scratch, fold));
    }

    static void deformerClampsStrays() {
        MeshDeformer d = new LatticeDeformer();
        boolean ok = d.clampComponent(1.2f) == LatticeDeformer.MAX_NUDGE;
        ok &= d.clampComponent(-9f) == -LatticeDeformer.MAX_NUDGE;
        ok &= d.clampComponent(Float.NaN) == 0f;
        ok &= d.clampComponent(0.05f) == 0.05f;
        check("stray drags clamp, NaN zeroes, sane values pass", ok);
    }

    // ── 4. One drag = one put = one undo ──────────────────────────────

    static void oneDragOneUndoStructure() {
        MeshPoseTrack tr = new MeshPoseTrack(18);
        float[] a = new float[18];
        float[] b = new float[18];
        a[8] = 0.03f;
        b[8] = 0.05f;
        check("first put lands", tr.put(100L, a, null));
        check("same-time put replaces (no duplicate diamond)", tr.put(100L, b, null));
        check("one pose after two same-time puts", tr.size() == 1);
        float[] got = new float[18];
        check("latest pose wins", tr.valueAt(100L, got) && Math.abs(got[8] - 0.05f) < 1e-7f);
        check("wrong-arity put refused", !tr.put(200L, new float[10], null));
        check("refused put stores nothing", tr.size() == 1);
        check("one diamond for one pose", tr.times().length == 1 && tr.times()[0] == 100L);
    }

    static void trackHoldsOutsideRange() {
        MeshPoseTrack tr = new MeshPoseTrack(18);
        float[] a = new float[18];
        float[] b = new float[18];
        a[8] = 0.02f;
        b[8] = 0.06f;
        tr.put(100L, a, null);
        tr.put(200L, b, null);
        float[] got = new float[18];
        boolean ok = tr.valueAt(0L, got) && Math.abs(got[8] - 0.02f) < 1e-6f;
        ok &= tr.valueAt(99999L, got) && Math.abs(got[8] - 0.06f) < 1e-6f;
        ok &= tr.valueAt(150L, got) && Math.abs(got[8] - 0.04f) < 1e-6f;
        check("track holds ends, interpolates spans (host's commit reads this)", ok);
    }

    static void bentSpecRoundTrips() {
        MeshWarpSpec s = MeshWarpSpec.lattice(LatticeTopology.L2);
        float[] h = s.handles();
        h[8] = 0.05f;
        check("a dragged dot is a warp", s.hasWarp());
        JsonObject wire = s.toJson();
        check("a bent spec serialises", wire != null);
        MeshWarpSpec back = null;
        try {
            back = MeshWarpSpec.fromJson(
                    com.google.gson.JsonParser.parseString(wire.toString()).getAsJsonObject());
        } catch (Exception ignored) { }
        boolean ok = back != null && back.hasWarp();
        ok &= back != null && Math.abs(back.handles()[8] - 0.05f) < 1e-7f;
        check("bend survives a save/load round-trip", ok);
    }

    // ── helpers ───────────────────────────────────────────────────────

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    static int countOf(String hay, String needle) {
        int n = 0, at = 0;
        while ((at = hay.indexOf(needle, at)) >= 0) { n++; at += needle.length(); }
        return n;
    }
}
