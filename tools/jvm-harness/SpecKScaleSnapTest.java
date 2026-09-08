import com.fadcam.ui.faditor.transform.TransformQuad;

/**
 * SPEC K follow-up (JoyRaptor 2026-09-07): corner-scale snaps to uniform by default —
 * the touch-screen answer to the Shift key, and the modern convention (Photoshop
 * CC2019+, Affinity, Figma, Pixelmator all scale proportionally on corner drags).
 * Near-diagonal drags lock aspect; a deliberate off-diagonal push breaks out to
 * free aspect. Tilt/Free roles and edge scales are untouched by the snap.
 */
public class SpecKScaleSnapTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static float[] rect(float cx, float cy, float w, float h) {
        return new float[]{cx - w / 2f, cy - h / 2f, cx + w / 2f, cy - h / 2f,
                cx + w / 2f, cy + h / 2f, cx - w / 2f, cy + h / 2f};
    }

    static float edgeLen(float[] q, int a, int b) {
        return (float) Math.hypot(q[b * 2] - q[a * 2], q[b * 2 + 1] - q[a * 2 + 1]);
    }

    public static void main(String[] a) {
        // 1. Split equivalence: factors + apply == scaleCorner, bit for bit.
        {
            java.util.Random r = new java.util.Random(20260907L);
            int bad = 0;
            for (int k = 0; k < 200; k++) {
                float[] q0 = rect(500f, 500f, 200f + r.nextFloat() * 300f, 150f + r.nextFloat() * 250f);
                int c = r.nextInt(4);
                float tx = q0[c * 2] + (r.nextFloat() - 0.5f) * 300f;
                float ty = q0[c * 2 + 1] + (r.nextFloat() - 0.5f) * 300f;
                float[] q1 = q0.clone(), q2 = q0.clone();
                float[] f1 = new float[2], f2 = new float[2];
                boolean ok1 = TransformQuad.scaleCorner(q1, q0, c, tx, ty, f1);
                boolean ok2 = TransformQuad.scaleCornerFactors(q0, c, tx, ty, f2);
                if (ok1 != ok2) { bad++; continue; }
                if (!ok1) continue;
                TransformQuad.scaleCornerApply(q2, q0, c, f2[0], f2[1]);
                float worst = 0f;
                for (int i = 0; i < 8; i++) worst = Math.max(worst, Math.abs(q1[i] - q2[i]));
                if (worst > 1e-4f || f1[0] != f2[0] || f1[1] != f2[1]) bad++;
            }
            check(bad == 0, "factors+apply == scaleCorner (" + bad + "/200 differ)");
        }
        // 2. Snap rule.
        {
            float[] out = new float[2];
            check(TransformQuad.snapUniformFactors(1.5f, 1.52f, 0.08f, out)
                    && out[0] == out[1] && Math.abs(out[0] - 1.51f) < 1e-6f,
                    "near-diagonal snaps to the mean");
            check(!TransformQuad.snapUniformFactors(1.5f, 2.0f, 0.08f, out)
                    && out[0] == 1.5f && out[1] == 2.0f,
                    "far-off-diagonal stays free");
            check(!TransformQuad.snapUniformFactors(1.5f, 2.0f, -1f, out),
                    "negative tol disables");
            check(!TransformQuad.snapUniformFactors(Float.NaN, 1f, 0.08f, out)
                    && Float.isNaN(out[0]), "NaN passes through unsnapped");
            // Boundary: exactly at tol snaps, just over does not.
            check(TransformQuad.snapUniformFactors(1.0f, 1.08f, 0.08f, out), "at tol snaps");
            check(!TransformQuad.snapUniformFactors(1.0f, 1.09f, 0.08f, out), "past tol frees");
        }
        // 3. Snapped scale of a trapezoid stays similar (aspect locked, taper kept).
        {
            float[] q0 = {100f, 100f, 400f, 100f, 350f, 300f, 150f, 300f};
            float r0 = edgeLen(q0, 0, 1) / edgeLen(q0, 1, 2);
            float[] fac = new float[2];
            check(TransformQuad.scaleCornerFactors(q0, 2, 460f, 360f, fac), "factors apply");
            float[] sn = new float[2];
            check(TransformQuad.snapUniformFactors(fac[0], fac[1], 0.5f, sn), "snaps");
            float[] q = q0.clone();
            TransformQuad.scaleCornerApply(q, q0, 2, sn[0], sn[1]);
            float r1 = edgeLen(q, 0, 1) / edgeLen(q, 1, 2);
            check(Math.abs(r1 - r0) / r0 < 0.01f, "snapped scale preserves shape ratios");
            check(TransformQuad.isValid(q), "snapped result drawable");
        }
        // 4. Diagonal drag on a plain rect scales uniformly through the snap path.
        {
            float[] q0 = rect(500f, 500f, 300f, 200f);
            float[] fac = new float[2];
            // Straight down-right diagonal from TL's opposite corner grab (drag BR out).
            check(TransformQuad.scaleCornerFactors(q0, 2, 700f, 633.3333f, fac), "factors apply");
            float rel = Math.abs(fac[0] - fac[1]) / Math.max(Math.abs(fac[0]), Math.abs(fac[1]));
            float[] sn = new float[2];
            boolean snapped = TransformQuad.snapUniformFactors(fac[0], fac[1], 0.08f, sn);
            check(snapped && sn[0] == sn[1], "diagonal drag snaps (rel " + rel + ")");
            float[] q = q0.clone();
            TransformQuad.scaleCornerApply(q, q0, 2, sn[0], sn[1]);
            check(Math.abs(edgeLen(q, 0, 1) / edgeLen(q, 1, 2) - 1.5f) < 0.01f,
                    "aspect preserved end to end");
        }
        System.out.println(fails == 0 ? "ALL GREEN" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
