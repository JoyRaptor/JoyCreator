import com.fadcam.ui.faditor.avatar.PinWarpStrip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JVM harness for A6 PinWarpStrip (pure math — no Android). Run like the other
 * harnesses (see Opencode-work.md TASK 2 recipe): compile against the app's
 * built classes, no jars needed beyond the class dir.
 */
public class PinWarpTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        identityReproducesRect();
        translationIsRigid();
        ninetyDegreeBendKeepsWidthAndLength();
        monotonicGuardRejectsBadChains();
        degenerateInputsReturnNull();
        coincidentPinsDoNotExplode();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    /** Straight vertical chain posed as a plain scale = the untouched rect grid. */
    static void identityReproducesRect() {
        List<float[]> rest = pins(0.5f, 0.1f, 0.5f, 0.9f);
        // Dest rect: x 100..200 (width 100), chain at cx=150 spanning y 10..90 of 0..100.
        List<float[]> posed = pins(150f, 10f, 150f, 90f);
        float[] v = PinWarpStrip.buildMeshVerts(rest, posed, 100f, 4);
        check("identity: non-null", v != null);
        if (v == null) return;
        // 5 rows × 2 cols; row r at y = r*25 exactly (extrapolated ends), x = 100/200.
        boolean ok = true;
        for (int r = 0; r <= 4; r++) {
            float ex0 = 100f, ex1 = 200f, ey = r * 25f;
            ok &= near(v[r * 4], ex0) && near(v[r * 4 + 1], ey)
                    && near(v[r * 4 + 2], ex1) && near(v[r * 4 + 3], ey);
        }
        check("identity: grid == plain rect (incl. extrapolated overhang rows)", ok);
    }

    /** Translating every posed pin translates every vertex — no shape change. */
    static void translationIsRigid() {
        List<float[]> rest = pins(0.5f, 0.1f, 0.5f, 0.5f, 0.5f, 0.9f);
        List<float[]> a = pins(150f, 10f, 150f, 50f, 150f, 90f);
        List<float[]> b = pins(180f, 40f, 180f, 80f, 180f, 120f);
        float[] va = PinWarpStrip.buildMeshVerts(rest, a, 100f, 6);
        float[] vb = PinWarpStrip.buildMeshVerts(rest, b, 100f, 6);
        check("translate: non-null", va != null && vb != null);
        if (va == null || vb == null) return;
        boolean ok = true;
        for (int i = 0; i < va.length; i += 2) {
            ok &= near(vb[i] - va[i], 30f) && near(vb[i + 1] - va[i + 1], 30f);
        }
        check("translate: every vertex shifted by exactly (30,30)", ok);
    }

    /** Elbow bent 90°: rows keep the authored width; the two bones stay their length. */
    static void ninetyDegreeBendKeepsWidthAndLength() {
        List<float[]> rest = pins(0.5f, 0f, 0.5f, 0.5f, 0.5f, 1f);
        // Upper bone straight down 80px, lower bone 90° to the right 80px.
        List<float[]> posed = pins(100f, 0f, 100f, 80f, 180f, 80f);
        int segs = 8;
        float[] v = PinWarpStrip.buildMeshVerts(rest, posed, 40f, segs);
        check("bend: non-null", v != null);
        if (v == null) return;
        boolean widthOk = true;
        for (int r = 0; r <= segs; r++) {
            float dx = v[r * 4 + 2] - v[r * 4], dy = v[r * 4 + 3] - v[r * 4 + 1];
            widthOk &= near((float) Math.sqrt(dx * dx + dy * dy), 40f);
        }
        check("bend: every row spans exactly the authored 40px width", widthOk);
        // Centerline endpoints land on the posed pins (rows 0, mid, last).
        float mx0 = (v[0] + v[2]) / 2f, my0 = (v[1] + v[3]) / 2f;
        int mid = segs / 2, last = segs;
        float mxm = (v[mid * 4] + v[mid * 4 + 2]) / 2f, mym = (v[mid * 4 + 1] + v[mid * 4 + 3]) / 2f;
        float mxl = (v[last * 4] + v[last * 4 + 2]) / 2f, myl = (v[last * 4 + 1] + v[last * 4 + 3]) / 2f;
        check("bend: centerline hits shoulder/elbow/wrist pins",
                near(mx0, 100f) && near(my0, 0f)
                        && near(mxm, 100f) && near(mym, 80f)
                        && near(mxl, 180f) && near(myl, 80f));
    }

    static void monotonicGuardRejectsBadChains() {
        List<float[]> sideways = pins(0.1f, 0.5f, 0.9f, 0.5f); // equal y = not monotonic
        check("guard: sideways chain rejected", !PinWarpStrip.isChainMonotonic(sideways));
        List<float[]> reversed = pins(0.5f, 0.9f, 0.5f, 0.1f);
        check("guard: reversed chain rejected", !PinWarpStrip.isChainMonotonic(reversed));
        check("guard: buildMeshVerts returns null on rejected chain",
                PinWarpStrip.buildMeshVerts(reversed, pins(0f, 0f, 0f, 1f), 10f, 4) == null);
    }

    static void degenerateInputsReturnNull() {
        check("degenerate: null lists", PinWarpStrip.buildMeshVerts(null, null, 10f, 4) == null);
        check("degenerate: single pin",
                PinWarpStrip.buildMeshVerts(pins(0.5f, 0.5f), pins(1f, 1f), 10f, 4) == null);
        check("degenerate: pin-count mismatch",
                PinWarpStrip.buildMeshVerts(pins(0.5f, 0.1f, 0.5f, 0.9f), pins(1f, 1f), 10f, 4) == null);
        check("degenerate: zero segments",
                PinWarpStrip.buildMeshVerts(pins(0.5f, 0.1f, 0.5f, 0.9f), pins(0f, 0f, 0f, 1f), 10f, 0) == null);
    }

    /** Posed pins collapsed onto one point must not produce NaN/Inf. */
    static void coincidentPinsDoNotExplode() {
        float[] v = PinWarpStrip.buildMeshVerts(
                pins(0.5f, 0.1f, 0.5f, 0.9f), pins(50f, 50f, 50f, 50f), 20f, 4);
        check("coincident: non-null", v != null);
        if (v == null) return;
        boolean finite = true;
        for (float f : v) finite &= !Float.isNaN(f) && !Float.isInfinite(f);
        check("coincident: all verts finite", finite);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static List<float[]> pins(float... xy) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) out.add(new float[]{xy[i], xy[i + 1]});
        return out;
    }

    static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (ok) passed++; else failed++;
    }
}
