import com.fadcam.ui.faditor.fx.FxInstance;
import com.fadcam.ui.faditor.fx.GradientCurve;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The Curve gradient's legacy-param migration.
 *
 * <p>The shape used to be ONE quadratic control point (a vec2 param named {@code curve}) before
 * the editable bezier path replaced it. A project saved with a bent curve would otherwise load
 * with that param unrecognised and silently straighten — no crash, no message, the user's shape
 * simply gone. The claim this file pins is stronger than "something was recovered": a quadratic
 * IS a cubic, so the migrated path must trace the old curve POINT FOR POINT.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-fx.sh}</p>
 */
public class CurveMigrationTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        theBendSurvives();
        theMigratedPathIsTheOldQuadratic();
        anUnbentCurveStaysStraight();
        aNewProjectIsUntouched();
        junkDegradesToTheDefault();

        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    /** A gradient_fill card as an OLD build wrote it: a "curve" vec2, no "path". */
    private static FxInstance legacy(float cx, float cy) {
        JsonObject v = new JsonObject();
        JsonArray cp = new JsonArray();
        cp.add(cx);
        cp.add(cy);
        v.add("curve", cp);
        v.addProperty("shape", 5f);          // the Curve chip
        JsonObject card = new JsonObject();
        card.addProperty("id", "gradient_fill");
        card.addProperty("slot", 0);
        card.add("v", v);
        return FxInstance.fromJson(card);
    }

    private static GradientCurve pathOf(FxInstance fx) {
        float[] packed = fx.get(
                com.fadcam.ui.faditor.fx.FxRegistry.get("gradient_fill").param("path"));
        return GradientCurve.fromFloatArray(packed);
    }

    static void theBendSurvives() {
        FxInstance fx = legacy(0.5f, 0.1f);   // pulled well above the midpoint
        check("a legacy card still loads", fx != null);
        if (fx == null) return;
        GradientCurve gc = pathOf(fx);
        boolean bent = Math.abs(gc.start.hy) > 1e-4f || Math.abs(gc.end.hy) > 1e-4f;
        check("the bend is recovered, not dropped", bent);
        check("no vertex was invented for it", gc.vertices.isEmpty());
    }

    static void theMigratedPathIsTheOldQuadratic() {
        // The old shader's path, in the space the default centre and angle put it in: p0 and p2
        // are the frame-spanning line the new default already uses, p1 is the stored point.
        final float p1x = 0.35f, p1y = 0.15f;
        FxInstance fx = legacy(p1x, p1y);
        if (fx == null) { check("legacy card loads for the point comparison", false); return; }
        GradientCurve gc = pathOf(fx);

        float p0x = 0f, p0y = 0.5f, p2x = 1f, p2y = 0.5f;
        // The migrated cubic, evaluated the way GradientCurve does: c1 = a + h, c2 = b - h.
        float c1x = gc.start.x + gc.start.hx, c1y = gc.start.y + gc.start.hy;
        float c2x = gc.end.x - gc.end.hx,     c2y = gc.end.y - gc.end.hy;

        boolean same = true;
        float worst = 0f;
        for (int i = 0; i <= 20; i++) {
            float u = i / 20f, m = 1f - u;
            float qx = m * m * p0x + 2f * m * u * p1x + u * u * p2x;
            float qy = m * m * p0y + 2f * m * u * p1y + u * u * p2y;
            float bx = m * m * m * p0x + 3f * m * m * u * c1x
                    + 3f * m * u * u * c2x + u * u * u * p2x;
            float by = m * m * m * p0y + 3f * m * m * u * c1y
                    + 3f * m * u * u * c2y + u * u * u * p2y;
            worst = Math.max(worst, Math.max(Math.abs(qx - bx), Math.abs(qy - by)));
            if (Math.abs(qx - bx) > 1e-5f || Math.abs(qy - by) > 1e-5f) same = false;
        }
        check("the migrated cubic traces the old quadratic exactly (worst "
                + String.format("%.2e", worst) + ")", same);
    }

    static void anUnbentCurveStaysStraight() {
        // The old default control point WAS the linear midpoint, so a card the user never bent
        // must migrate to a path that still renders identically to Linear.
        FxInstance fx = legacy(0.5f, 0.5f);
        if (fx == null) { check("legacy default card loads", false); return; }
        GradientCurve gc = pathOf(fx);
        float[] pts = gc.samplePoints(GradientCurve.SAMPLE_POINTS);
        boolean flat = true;
        for (int i = 0; i < GradientCurve.SAMPLE_POINTS; i++) {
            if (Math.abs(pts[i * 2 + 1] - 0.5f) > 1e-4f) flat = false;
        }
        check("an untouched legacy curve is still a straight line", flat);
    }

    static void aNewProjectIsUntouched() {
        // A card written by THIS build has "path" and no "curve". The migration must not fire and
        // overwrite it — that would silently replace an edited three-vertex path with a bend.
        GradientCurve authored = GradientCurve.defaultCurve();
        authored.addVertex(0.5f, 0.2f);
        JsonObject v = new JsonObject();
        JsonArray arr = new JsonArray();
        for (float f : authored.toFloatArray()) arr.add(f);
        v.add("path", arr);
        JsonObject card = new JsonObject();
        card.addProperty("id", "gradient_fill");
        card.addProperty("slot", 0);
        card.add("v", v);
        FxInstance fx = FxInstance.fromJson(card);
        check("a modern card loads", fx != null);
        if (fx == null) return;
        check("its authored vertex survives", pathOf(fx).vertices.size() == 1);
    }

    static void junkDegradesToTheDefault() {
        // Hand-edited or AI-authored JSON: a one-element "curve" array. The tolerance rule says
        // degrade, never take the project load down.
        JsonObject v = new JsonObject();
        JsonArray cp = new JsonArray();
        cp.add(0.5f);
        v.add("curve", cp);
        JsonObject card = new JsonObject();
        card.addProperty("id", "gradient_fill");
        card.addProperty("slot", 0);
        card.add("v", v);
        FxInstance fx = null;
        boolean threw = false;
        try { fx = FxInstance.fromJson(card); } catch (RuntimeException e) { threw = true; }
        check("a malformed legacy point does not throw", !threw && fx != null);
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (ok) passed++; else failed++;
    }
}
