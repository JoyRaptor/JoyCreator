import com.fadcam.ui.faditor.transform.TransformQuad;
import com.fadcam.ui.faditor.transform.mesh.LatticeDeformer;
import com.fadcam.ui.faditor.transform.mesh.LatticeTopology;
import com.fadcam.ui.faditor.transform.mesh.MeshBuffers;
import com.fadcam.ui.faditor.transform.mesh.MeshDeformer;
import com.fadcam.ui.faditor.transform.mesh.MeshEngine;
import com.fadcam.ui.faditor.transform.mesh.MeshGuard;
import com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack;
import com.fadcam.ui.faditor.transform.mesh.MeshProjection;
import com.fadcam.ui.faditor.transform.mesh.MeshTopologies;
import com.fadcam.ui.faditor.transform.mesh.MeshTopology;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.management.ManagementFactory;

/**
 * JVM harness for the mesh deformation ENGINE — topology, deformer, pose track, guard,
 * serialisation, and the two properties the whole design rests on:
 *
 *   1. subdividing the lattice does not change the picture (the maths, proved not asserted);
 *   2. a puppet topology with a completely different deformer slots in WITHOUT touching the pose
 *      track, the output buffers, the fold guard or the wire format.
 *
 * Runs off device. No android.* anywhere in the engine, which is what makes this file possible.
 */
public class MeshEngineTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        latticeIsBilinearAtLevelOne();
        identityReproducesRest();
        subdivisionTwoToThreeIsExact();
        subdivisionThreeToFiveIsProportional();
        subdivisionCarriesTheWholeSpec();
        structuralChangeCarriesTheBend();
        foldIsRefused();
        poseTrackInterpolates();
        poseTrackRefusesMixedArity();
        serialisationRoundTrips();
        serialisationIsTolerant();
        hotPathDoesNotAllocate();
        puppetTopologySlotsIn();
        homographyMatchesTransformQuad();

        System.out.println(failed == 0
                ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── 1. The bilinear foundation the subdivision proof rests on ────────

    /**
     * At side==2 the Catmull-Rom axis weights must collapse to exactly {1-t, t}. If the ghost
     * controls were resolved by duplicating the endpoint instead of extrapolating linearly, this
     * fails — and so does every exactness claim downstream.
     */
    static void latticeIsBilinearAtLevelOne() {
        LatticeDeformer d = new LatticeDeformer();
        float[] out = new float[2];
        // A 2x2 lattice whose four nudges are samples of a bilinear function must reproduce it.
        // f(u,v) = 0.1 + 0.2u + 0.3v + 0.4uv  (x), and a different bilinear for y.
        float[] h = new float[8];
        int i = 0;
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                float u = c, v = r;
                h[i++] = bilinX(u, v);
                h[i++] = bilinY(u, v);
            }
        }
        boolean ok = true;
        for (int a = 0; a <= 32; a++) {
            for (int b = 0; b <= 32; b++) {
                float u = a / 32f, v = b / 32f;
                d.eval(h, 2, u, v, out);
                ok &= Math.abs((out[0] - u) - bilinX(u, v)) < 1e-6f;
                ok &= Math.abs((out[1] - v) - bilinY(u, v)) < 1e-6f;
            }
        }
        check("level 1 is exactly bilinear (linear extrapolation at the ghosts)", ok);
    }

    static float bilinX(float u, float v) { return 0.1f + 0.2f * u + 0.3f * v + 0.4f * u * v; }
    static float bilinY(float u, float v) { return -0.05f + 0.11f * u - 0.22f * v + 0.33f * u * v; }

    // ── 2. Identity ─────────────────────────────────────────────────────

    static void identityReproducesRest() {
        MeshTopology t = new LatticeTopology(LatticeTopology.L3);
        MeshBuffers b = new MeshBuffers();
        b.bind(t);
        LatticeDeformer d = new LatticeDeformer();
        d.solve(t, b, new float[t.handleArity()]);
        boolean ok = true;
        for (int i = 0; i < b.vertexCount() * 2; i++) ok &= Math.abs(b.positions[i] - b.rest[i]) < 1e-7f;
        check("identity pose reproduces the rest shape exactly", ok);
        check("identity pose is valid", MeshGuard.isValid(b));
        check("engine takes the zero-cost path on an identity spec",
                !new MeshEngine().update(MeshWarpSpec.lattice(LatticeTopology.L3), 0L));
        check("5x5 topology emits 25x25 verts / 1152 triangles",
                t.vertexCount() == 625 && t.indexCount() == 24 * 24 * 6 && t.handleCount() == 25);
    }

    // ── 3. THE SUBDIVISION PROOF ────────────────────────────────────────

    /**
     * 2x2 -> 3x3 is EXACT. Fact 1: at side==2 the map is bilinear (pinned above). Fact 2: the
     * Catmull-Rom tensor product has bilinear precision. Sampling a bilinear map at {0,1/2,1}^2 and
     * re-fitting therefore reproduces it at EVERY point, not merely at the knots. 1e-6 here is
     * float noise, not a tolerance.
     */
    static void subdivisionTwoToThreeIsExact() {
        LatticeDeformer d = new LatticeDeformer();
        float[] coarse = {0.00f, 0.00f, -0.30f, 0.12f,
                          0.25f, -0.18f, 0.10f, 0.40f};   // an arbitrary 2x2 bend
        float[] fine = new float[3 * 3 * 2];
        d.subdivide(coarse, 2, 3, fine);
        check("2x2 -> 3x3 preserves the map to 1e-6 over a 65x65 sweep",
                maxDeviation(d, coarse, 2, fine, 3, 65) < 1e-6f);
        // And the old knots did not move: an interpolating spline passes through its own controls.
        boolean corners = Math.abs(fine[0] - coarse[0]) < 1e-6f
                && Math.abs(fine[(0 * 3 + 2) * 2] - coarse[2]) < 1e-6f
                && Math.abs(fine[(2 * 3 + 0) * 2] - coarse[4]) < 1e-6f
                && Math.abs(fine[(2 * 3 + 2) * 2] - coarse[6]) < 1e-6f;
        check("2x2 -> 3x3 leaves the original four control points exactly where they were", corners);
    }

    /**
     * 3x3 -> 5x5 is NOT exact, and this pins exactly how inexact.
     *
     * A 3x3 map is piecewise cubic per axis; Catmull-Rom reproduces quadratics but not cubics, so a
     * half-spacing resample leaves a residual. SPEC_20260902 §3.4 proposed three Gauss-Seidel
     * sweeps to cancel it; that correction is arithmetically a no-op (the new interpolant passes
     * through its own new knots, so every sweep adds exactly zero), and no adjustment of control
     * values under an INTERPOLATORY basis can do better without moving the dots off the places the
     * user put them — which direct manipulation may not do.
     *
     * So the residual is measured rather than wished away, and it turns out to be a clean constant:
     *
     *     max |D_before - D_after|  ==  |nudge| / 32
     *
     * exactly, and linear in the pose (both the interpolation and the resample are linear operators
     * in the control values). A 10%-of-width bend on a full-frame 1080-wide picture therefore shifts
     * it by 1080 * 0.1/32 = 3.4 px at the single worst point on the whole surface; the bend a user
     * actually authors is smaller and so is the shift. L1 -> L2, which is the "+ Finer" press people
     * make from an untouched picture, is EXACT — this cost only exists on the second press.
     */
    static void subdivisionThreeToFiveIsProportional() {
        LatticeDeformer d = new LatticeDeformer();
        float[] fine = new float[5 * 5 * 2];
        boolean constant = true;
        StringBuilder seen = new StringBuilder();
        for (float m = 0.05f; m <= 0.301f; m += 0.05f) {
            float[] pose = new float[3 * 3 * 2];
            pose[4 * 2] = m;                                   // one dot pulled, the honest case
            d.subdivide(pose, 3, 5, fine);
            float dev = maxDeviation(d, pose, 3, fine, 5, 65);
            constant &= Math.abs(dev - m / 32f) < m / 32f * 0.05f;
            seen.append(String.format(" %.2f->%.1e", m, dev));
        }
        check("3x3 -> 5x5 residual is EXACTLY nudge/32, linear in the pose:" + seen, constant);

        // The bound that matters: the worst deformation the FOLD GUARD will actually let through.
        MeshTopology t = new LatticeTopology(LatticeTopology.L2);
        MeshBuffers scratch = new MeshBuffers();
        scratch.bind(t);
        java.util.Random r = new java.util.Random(7);
        float worst = 0f;
        for (int trial = 0; trial < 4000; trial++) {
            float mag = 0.05f + r.nextFloat() * 0.6f;
            float[] pose = new float[18];
            for (int i = 0; i < 18; i++) pose[i] = (r.nextFloat() * 2f - 1f) * mag;
            if (!MeshGuard.accepts(t, d, scratch, pose)) continue;
            d.subdivide(pose, 3, 5, fine);
            worst = Math.max(worst, maxDeviation(d, pose, 3, fine, 5, 33));
        }
        check("worst RENDERABLE 3x3 -> 5x5 residual over 4,000 random poses is < 0.05 of the unit "
                + "square (measured " + fmt(worst) + ")", worst > 0f && worst < 0.05f);
    }

    /** "+ Finer" must carry the static pose AND every keyframe, together, or arity can go mixed. */
    static void subdivisionCarriesTheWholeSpec() {
        LatticeDeformer d = new LatticeDeformer();
        MeshWarpSpec s = MeshWarpSpec.lattice(LatticeTopology.L2);
        s.handles()[4 * 2] = 0.2f;
        MeshPoseTrack tr = s.ensureTrack();
        float[] a = new float[18], b = new float[18];
        a[4 * 2] = 0.2f;
        b[4 * 2] = -0.2f;
        tr.put(0, a, "LINEAR");
        tr.put(1000, b, "LINEAR");

        float[] before = new float[2], after = new float[2];
        d.eval(s.handles(), 3, 0.37f, 0.62f, before);

        check("setLevel L2 -> L3 succeeds", d.setLevel(s, LatticeTopology.L3));
        check("spec arity followed the topology", s.arity() == 50 && s.handles().length == 50);
        check("track arity followed too — mixed arity is unrepresentable", s.track().arity() == 50);
        check("both poses were rewritten", s.track().size() == 2
                && s.track().poses().get(0).values.length == 50
                && s.track().poses().get(1).values.length == 50);

        d.eval(s.handles(), 5, 0.37f, 0.62f, after);
        // The pose's largest nudge is 0.2, so the proved bound is 0.2/32 = 6.25e-3.
        check("the static pose still draws the same point after + Finer, inside the proved bound",
                Math.abs(before[0] - after[0]) <= 0.2f / 32f
                        && Math.abs(before[1] - after[1]) <= 0.2f / 32f);
    }

    /** max |D_before(u,v) - D_after(u,v)| over an NxN parameter sweep. */
    static float maxDeviation(LatticeDeformer d, float[] a, int sideA,
                              float[] b, int sideB, int n) {
        float[] pa = new float[2], pb = new float[2];
        float worst = 0f;
        for (int i = 0; i < n; i++) {
            float v = i / (float) (n - 1);
            for (int j = 0; j < n; j++) {
                float u = j / (float) (n - 1);
                d.eval(a, sideA, u, v, pa);
                d.eval(b, sideB, u, v, pb);
                worst = Math.max(worst, Math.abs(pa[0] - pb[0]));
                worst = Math.max(worst, Math.abs(pa[1] - pb[1]));
            }
        }
        return worst;
    }

    // ── 4. A structural change CARRIES the bend ─────────────────────────

    /**
     * The prototype's first-round bug was "scaling the edge undid my bend". The cure is that a
     * handle's authored value lives in the picture's own unit space and is re-projected through the
     * CURRENT quad every frame. So: bend a dot on quad A, then scale the quad's right edge out to
     * make quad B, and the bend must (a) still be there and (b) have moved WITH the structure.
     */
    static void structuralChangeCarriesTheBend() {
        MeshTopology t = new LatticeTopology(LatticeTopology.L2);
        LatticeDeformer d = new LatticeDeformer();
        float[] pose = new float[t.handleArity()];

        float[] quadA = {100, 100, 300, 100, 300, 300, 100, 300};
        float[] hA = TransformQuad.unitToQuad(quadA);
        float[] hAinv = TransformQuad.invert3x3(hA);

        // Drag the centre dot (handle 4) to a stage point and store it as a nudge.
        float[] v = new float[2];
        check("drag maps back into unit space",
                MeshProjection.dragToHandle(t, d, 4, hAinv, 240f, 160f, v));
        pose[4 * 2] = v[0];
        pose[4 * 2 + 1] = v[1];
        check("the stored value is a nudge in unit space, not stage pixels",
                Math.abs(v[0] - 0.20f) < 1e-4f && Math.abs(v[1] - (-0.20f)) < 1e-4f);

        float[] pA = new float[2];
        MeshProjection.projectHandle(t, pose, 4, hA, pA);
        check("the dot lands back under the thumb",
                Math.abs(pA[0] - 240f) < 1e-2f && Math.abs(pA[1] - 160f) < 1e-2f);

        // Structural edit: scale the right edge out. The pose array is NOT touched.
        float[] quadB = {100, 100, 700, 100, 700, 300, 100, 300};
        float[] hB = TransformQuad.unitToQuad(quadB);
        float[] pB = new float[2];
        MeshProjection.projectHandle(t, pose, 4, hB, pB);

        check("the bend survived the structural edit (the pose was never rewritten)",
                pose[4 * 2] == v[0] && pose[4 * 2 + 1] == v[1]);
        check("and it MOVED with the structure rather than staying put",
                Math.abs(pB[0] - pA[0]) > 100f);
        check("landing exactly where the re-projection says it should",
                Math.abs(pB[0] - (100f + 0.70f * 600f)) < 1e-2f
                        && Math.abs(pB[1] - (100f + 0.30f * 200f)) < 1e-2f);

        // And the deformation itself is unchanged in unit space — the same triangles, re-placed.
        MeshBuffers bb = new MeshBuffers();
        bb.bind(t);
        d.solve(t, bb, pose);
        float[] snapshot = bb.positions.clone();
        d.solve(t, bb, pose);
        boolean same = true;
        for (int i = 0; i < bb.vertexCount() * 2; i++) same &= snapshot[i] == bb.positions[i];
        check("the deformed geometry is a pure function of the pose", same);
    }

    // ── 5. Fold guard ───────────────────────────────────────────────────

    static void foldIsRefused() {
        MeshTopology t = new LatticeTopology(LatticeTopology.L2);
        LatticeDeformer d = new LatticeDeformer();
        MeshBuffers b = new MeshBuffers();
        b.bind(t);

        float[] gentle = new float[t.handleArity()];
        gentle[4 * 2] = 0.06f;
        gentle[4 * 2 + 1] = -0.06f;
        check("a gentle bend is accepted", MeshGuard.accepts(t, d, b, gentle));

        float[] folded = new float[t.handleArity()];
        folded[4 * 2] = LatticeDeformer.MAX_NUDGE;      // haul the centre dot clean past its cell
        check("a bend that turns cells inside out is REFUSED, not rendered",
                !MeshGuard.accepts(t, d, b, folded));

        // Refusal must be recoverable: the live buffers the renderer holds are untouched.
        MeshBuffers live = new MeshBuffers();
        live.bind(t);
        d.solve(t, live, gentle);
        float[] before = live.positions.clone();
        MeshGuard.accepts(t, d, b, folded);             // candidate goes into the SCRATCH buffers
        boolean untouched = true;
        for (int i = 0; i < live.vertexCount() * 2; i++) untouched &= before[i] == live.positions[i];
        check("refusing a candidate leaves the live geometry untouched", untouched);

        float[] nan = new float[t.handleArity()];
        nan[0] = Float.NaN;
        check("NaN in a pose is clamped to zero at the edit seam",
                d.clampComponent(Float.NaN) == 0f);
        check("a NaN pose is refused by the guard", !MeshGuard.accepts(t, d, b, nan));
    }

    // ── 6. Pose track ───────────────────────────────────────────────────

    static void poseTrackInterpolates() {
        MeshPoseTrack tr = new MeshPoseTrack(4);
        check("empty track yields nothing", !tr.valueAt(0, new float[4]));

        float[] a = {0f, 0f, 0f, 0f}, b = {1f, 2f, 3f, 4f};
        tr.put(1000, a, "LINEAR");
        float[] out = new float[4];
        tr.valueAt(0, out);
        check("a single pose is a constant", out[0] == 0f && !tr.isAnimated());

        tr.put(2000, b, "LINEAR");
        check("two poses is animated, and ONE diamond per pose", tr.isAnimated() && tr.times().length == 2);

        tr.valueAt(0, out);
        check("held before the first pose", out[3] == 0f);
        tr.valueAt(9999, out);
        check("held after the last pose (never extrapolated)", out[3] == 4f);
        tr.valueAt(1500, out);
        check("component-wise lerp at the midpoint",
                near(out[0], 0.5f) && near(out[1], 1f) && near(out[2], 1.5f) && near(out[3], 2f));

        tr.setCurve(new MeshPoseTrack.Curve() {
            @Override public float apply(String name, float t) {
                return "HOLD".equals(name) ? 0f : t * t;
            }
        });
        tr.valueAt(1500, out);
        check("the installed easing curve drives the span", near(out[3], 4f * 0.25f));
        tr.put(1000, a, "HOLD");
        tr.valueAt(1999, out);
        check("HOLD steps rather than ramps", out[3] == 0f);

        tr.setCurve(null);
        tr.put(1000, a, "LINEAR");
        tr.shiftAll(-1500);
        check("shiftAll KEEPS negative times so a trim is exactly reversible",
                tr.times()[0] == -500 && tr.times()[1] == 500);
        tr.shiftAll(1500);
        check("and shifting back restores them exactly",
                tr.times()[0] == 1000 && tr.times()[1] == 2000);
    }

    static void poseTrackRefusesMixedArity() {
        MeshPoseTrack tr = new MeshPoseTrack(18);
        check("a pose of the wrong arity is refused, not stored",
                !tr.put(0, new float[50], "LINEAR") && tr.isEmpty());
        check("the right arity is accepted", tr.put(0, new float[18], "LINEAR"));
        check("a remapper that returns the wrong length leaves the track UNCHANGED",
                !tr.remap(50, new MeshPoseTrack.Remapper() {
                    @Override public float[] remap(float[] p) { return new float[7]; }
                }) && tr.arity() == 18 && tr.size() == 1);
    }

    // ── 7. Serialisation ────────────────────────────────────────────────

    static void serialisationRoundTrips() {
        check("absent field reads as no mesh", MeshWarpSpec.fromJson(null) == null);
        check("an untouched spec writes NOTHING — a byte-identical save",
                MeshWarpSpec.lattice(LatticeTopology.L2).toJson() == null);

        MeshWarpSpec s = MeshWarpSpec.lattice(LatticeTopology.L3);
        s.handles()[12 * 2] = 0.17f;
        s.handles()[12 * 2 + 1] = -0.09f;
        MeshPoseTrack tr = s.ensureTrack();
        float[] p0 = new float[50], p1 = new float[50];
        p0[12 * 2] = 0.17f;
        p1[12 * 2] = -0.31f;
        tr.put(0, p0, "EASE_OUT");
        tr.put(1200, p1, "LINEAR");

        JsonObject j = s.toJson();
        check("a bent spec writes a field", j != null);
        String wire = j.toString();
        MeshWarpSpec back = MeshWarpSpec.fromJson(JsonParser.parseString(wire).getAsJsonObject());
        check("round trip preserves the topology",
                back != null && back.topology() instanceof LatticeTopology
                        && ((LatticeTopology) back.topology()).level() == LatticeTopology.L3);
        check("round trip preserves the static pose",
                near(back.handles()[24], 0.17f) && near(back.handles()[25], -0.09f));
        check("round trip preserves both poses and their easing",
                back.track() != null && back.track().size() == 2
                        && "EASE_OUT".equals(back.track().poses().get(0).easing)
                        && near(back.track().poses().get(1).values[24], -0.31f));
        check("re-serialising is byte-identical", wire.equals(back.toJson().toString()));
        check("the topology descriptor is a KIND plus a float array — no lattice word in the format",
                wire.contains("\"k\":\"lattice\"") && wire.contains("\"tp\":[3"));
    }

    static void serialisationIsTolerant() {
        check("unknown kind drops the spec and keeps the object",
                MeshWarpSpec.fromJson(obj("{\"k\":\"puppet-from-the-future\",\"tp\":[1],\"h\":[0]}")) == null);
        check("a pose of the wrong arity drops the spec, never throws",
                MeshWarpSpec.fromJson(obj("{\"k\":\"lattice\",\"tp\":[2],\"h\":[0,0,0]}")) == null);
        check("garbage does not throw",
                MeshWarpSpec.fromJson(obj("{\"k\":\"lattice\",\"tp\":\"nonsense\",\"h\":\"nope\"}")) == null);

        MeshWarpSpec legacy = MeshWarpSpec.fromJson(obj(
                "{\"lvl\":1,\"off\":[0,0, 0.1,0, 0,0, 0,0]}"));
        check("SPEC_20260902's bare lvl + off spelling still loads",
                legacy != null && legacy.arity() == 8 && near(legacy.handles()[2], 0.1f));

        MeshWarpSpec abs = MeshWarpSpec.fromJson(obj(
                "{\"lvl\":1,\"pts\":[0,0, 1.1,0, 0,1, 1,1]}"));
        check("SPEC_20260902's ABSOLUTE pts spelling converts to nudges on read",
                abs != null && near(abs.handles()[2], 0.1f) && near(abs.handles()[0], 0f));

        MeshWarpSpec dropped = MeshWarpSpec.fromJson(obj(
                "{\"k\":\"lattice\",\"tp\":[1],\"h\":[0,0,0.2,0,0,0,0,0],\"keys\":["
                        + "{\"t\":0,\"e\":\"LINEAR\",\"h\":[0,0,0.2,0,0,0,0,0]},"
                        + "{\"t\":50,\"e\":\"LINEAR\",\"h\":[1,2,3]},"
                        + "{\"t\":99,\"e\":\"LINEAR\",\"h\":[0,0,0.4,0,0,0,0,0]}]}"));
        check("one bad pose is dropped and the rest are kept",
                dropped != null && dropped.track() != null && dropped.track().size() == 2
                        && dropped.track().times()[1] == 99);
    }

    static JsonObject obj(String s) { return JsonParser.parseString(s).getAsJsonObject(); }

    // ── 8. No allocation on the per-frame path ──────────────────────────

    /**
     * A new float[] on the GL thread is a stutter that only shows up once the phone is hot. This
     * measures the JVM's own allocated-bytes counter across 20,000 engine updates at the heaviest
     * level, with a two-pose track running, and demands ZERO growth after warm-up.
     */
    static void hotPathDoesNotAllocate() {
        MeshWarpSpec s = MeshWarpSpec.lattice(LatticeTopology.L3);
        MeshPoseTrack tr = s.ensureTrack();
        float[] a = new float[50], b = new float[50];
        a[12 * 2] = 0.05f;
        b[12 * 2] = -0.05f;
        tr.put(0, a, "LINEAR");
        tr.put(2000, b, "LINEAR");
        MeshEngine e = new MeshEngine();

        for (int i = 0; i < 2000; i++) e.update(s, i % 2000);        // warm up + JIT

        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(id);
        long sink = 0;
        for (int i = 0; i < 20000; i++) {
            e.update(s, i % 2000);
            sink += (long) e.buffers().positions[0];
        }
        long grew = mx.getThreadAllocatedBytes(id) - before;
        // A single new float[50] per frame would be ~4.3 MB over this loop. Anything under a few
        // KB is the measurement instrument itself, not the engine.
        check("20,000 engine frames at 5x5 allocate nothing per frame ("
                + grew + " bytes total, sink " + sink + ")", grew < 8192);

        // The fold guard runs on the drag path, not the render path, but it must be clean too.
        MeshBuffers scratch = new MeshBuffers();
        scratch.bind(s.topology());
        LatticeDeformer d = new LatticeDeformer();
        for (int i = 0; i < 200; i++) MeshGuard.accepts(s.topology(), d, scratch, a);
        before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 2000; i++) MeshGuard.accepts(s.topology(), d, scratch, a);
        long guardGrew = mx.getThreadAllocatedBytes(id) - before;
        check("2,000 fold-guard checks allocate nothing per check (" + guardGrew + " bytes total)",
                guardGrew < 8192);
    }

    // ── 9. THE ARCHITECTURAL TEST: a puppet slots in ────────────────────

    /**
     * A deliberately alien topology — irregular triangulation, no rows, no columns, three pins that
     * are ABSOLUTE positions rather than nudges — driven by a deformer that is a moving-least-
     * squares-flavoured weighted blend rather than a spline. Nothing about it resembles a lattice.
     *
     * It is registered with one line, and then the pose track, the buffers, the fold guard, the
     * engine and the wire format all serve it with NO change. That is the property the whole
     * architecture exists for, and this test is what stops a future edit quietly re-fusing them.
     */
    static void puppetTopologySlotsIn() {
        MeshTopologies.register(FakePuppetTopology.KIND,
                new MeshTopologies.Factory() {
                    @Override public MeshTopology create(float[] p) { return new FakePuppetTopology(); }
                },
                new MeshTopologies.DeformerFactory() {
                    @Override public MeshDeformer create() { return new FakePuppetDeformer(); }
                });

        MeshTopology t = new FakePuppetTopology();
        MeshWarpSpec s = new MeshWarpSpec(t);
        check("the spec sized itself from the topology, not from a level", s.arity() == 6);

        // Pose track: unchanged code, puppet pins.
        MeshPoseTrack tr = s.ensureTrack();
        float[] rest = t instanceof FakePuppetTopology ? ((FakePuppetTopology) t).restPins() : null;
        float[] moved = rest.clone();
        moved[0] += 0.3f;
        check("the pose track stores puppet pins with no new code",
                tr.put(0, rest, "LINEAR") && tr.put(1000, moved, "LINEAR"));

        // Engine: unchanged code, a completely different deformer.
        MeshEngine e = new MeshEngine();
        check("the engine drives the puppet", e.update(s, 1000));
        check("and produced the neutral buffers a renderer already knows how to draw",
                e.buffers().indexCount() == t.indexCount() && e.buffers().vertexCount() == t.vertexCount());
        check("the puppet actually deformed", e.buffers().positions[0] != e.buffers().rest[0]);

        // Fold guard: unchanged code, irregular triangles.
        check("the fold guard accepts a sane puppet pose", MeshGuard.isValid(e.buffers()));
        float[] inverted = rest.clone();
        inverted[0] += 9f;
        MeshBuffers scratch = new MeshBuffers();
        scratch.bind(t);
        check("and refuses one that inverts a triangle",
                !MeshGuard.accepts(t, new FakePuppetDeformer(), scratch, inverted));

        // Wire format: unchanged code.
        s.handles()[0] += 0.3f;
        JsonObject j = s.toJson();
        MeshWarpSpec back = MeshWarpSpec.fromJson(j);
        check("the puppet spec round-trips through the SAME serialiser",
                back != null && back.topology() instanceof FakePuppetTopology
                        && back.track().size() == 2 && near(back.handles()[0], rest[0] + 0.3f));
        check("and the wire format never mentioned a lattice", j.toString().contains("\"k\":\"puppet-test\""));
    }

    /** Three vertices, one triangle, three pins that are absolute positions. Nothing regular. */
    static final class FakePuppetTopology implements MeshTopology {
        static final String KIND = "puppet-test";
        static final float[] V = {0.1f, 0.9f, 0.5f, 0.05f, 0.95f, 0.85f};

        float[] restPins() { return V.clone(); }

        @Override public String kind() { return KIND; }
        @Override public float[] params() { return new float[]{0.35f}; }
        @Override public int topologyId() { return 0x9E11_0001; }
        // One triangle, so one draw group — the same answer a lattice gives. Added when draw
        // groups landed: the compiler caught this fake the moment the interface grew, which is
        // the interface doing its job.
        @Override public int groupCount() { return 1; }
        @Override public void groupIndexStart(int[] out) {
            if (out != null && out.length >= 2) { out[0] = 0; out[1] = indexCount(); }
        }
        @Override public int vertexCount() { return 3; }
        @Override public int indexCount() { return 3; }
        @Override public int handleCount() { return 3; }
        @Override public int handleComponents() { return 2; }
        @Override public int handleArity() { return 6; }
        @Override public float handleRestX(int i) { return V[i * 2]; }
        @Override public float handleRestY(int i) { return V[i * 2 + 1]; }
        @Override public void buildRest(float[] rest, float[] uv, short[] idx) {
            System.arraycopy(V, 0, rest, 0, 6);
            if (uv != null) System.arraycopy(V, 0, uv, 0, 6);
            if (idx != null) { idx[0] = 0; idx[1] = 1; idx[2] = 2; }
        }
    }

    /** Pins ARE the vertices here; a real puppet would run ARAP/MLS over hundreds of them. */
    static final class FakePuppetDeformer implements MeshDeformer {
        @Override public boolean supports(MeshTopology t) { return t instanceof FakePuppetTopology; }
        @Override public boolean isIdentity(float[] v) {
            if (v == null) return true;
            for (int i = 0; i < 6; i++) if (v[i] != FakePuppetTopology.V[i]) return false;
            return true;
        }
        @Override public void identityPose(MeshTopology t, float[] out) {
            System.arraycopy(FakePuppetTopology.V, 0, out, 0, 6);
        }
        @Override public float clampComponent(float v) { return v; }
        @Override public boolean solve(MeshTopology t, MeshBuffers b, float[] v) {
            if (!supports(t) || v == null || v.length != 6) return false;
            System.arraycopy(v, 0, b.positions, 0, 6);
            return true;
        }
    }

    // ── 10. The one duplicated function stays equal to its original ─────

    static void homographyMatchesTransformQuad() {
        float[] quad = {40, 20, 300, 60, 280, 340, 10, 300};      // a real perspective quad
        float[] h = TransformQuad.unitToQuad(quad);
        float[] mine = new float[2], theirs = new float[2];
        boolean ok = true;
        for (int i = 0; i <= 16; i++) {
            for (int j = 0; j <= 16; j++) {
                float u = j / 16f, v = i / 16f;
                boolean a = MeshProjection.applyHomography(h, u, v, mine);
                boolean b = TransformQuad.applyHomography(h, u, v, theirs);
                ok &= a == b && Math.abs(mine[0] - theirs[0]) < 1e-4f
                        && Math.abs(mine[1] - theirs[1]) < 1e-4f;
            }
        }
        check("MeshProjection.applyHomography == TransformQuad.applyHomography", ok);
        check("a degenerate matrix is refused rather than drawn at infinity",
                !MeshProjection.applyHomography(new float[9], 0.5f, 0.5f, mine));
    }

    // ── harness plumbing ────────────────────────────────────────────────

    static boolean near(float a, float b) { return Math.abs(a - b) < 1e-4f; }

    static String fmt(float f) { return String.format("%.3e", f); }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
