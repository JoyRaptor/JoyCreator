import com.fadcam.ui.faditor.avatar.DangleSim;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM harness for A6 DangleSim (pure math, no Android). Same runner recipe as
 * PinWarpTest — app classes on the classpath, no jars.
 */
public class DangleTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        settlesStraightDownUnderGravity();
        bonesStayRigidThroughViolentAnchorMotion();
        swingExcitesThenDampsBack();
        deterministicReplay();
        degenerateChainsAreSafe();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    static List<float[]> chain(float... xy) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) out.add(new float[]{xy[i], xy[i + 1]});
        return out;
    }

    /** Static anchor: the chain must hang plumb below it at rest lengths. */
    static void settlesStraightDownUnderGravity() {
        DangleSim s = new DangleSim(chain(100, 0, 100, 60, 100, 140));
        for (int f = 0; f < 300; f++) s.step(200, 50, 1 / 60f);
        boolean plumb = Math.abs(s.nodeX(1) - 200) < 0.5f && Math.abs(s.nodeX(2) - 200) < 0.5f;
        boolean lengths = Math.abs((s.nodeY(1) - s.nodeY(0)) - 60) < 0.5f
                && Math.abs((s.nodeY(2) - s.nodeY(1)) - 80) < 0.5f;
        check("settle: hangs plumb below the anchor", plumb);
        check("settle: bone lengths preserved at rest", lengths);
    }

    /** Whip the anchor around — bones must never stretch beyond tolerance. */
    static void bonesStayRigidThroughViolentAnchorMotion() {
        DangleSim s = new DangleSim(chain(0, 0, 0, 50, 0, 100));
        boolean rigid = true;
        for (int f = 0; f < 240; f++) {
            float ax = (float) (300 * Math.sin(f * 0.4)); // fast whip
            s.step(ax, 100, 1 / 60f);
            float b0 = (float) Math.hypot(s.nodeX(1) - s.nodeX(0), s.nodeY(1) - s.nodeY(0));
            float b1 = (float) Math.hypot(s.nodeX(2) - s.nodeX(1), s.nodeY(2) - s.nodeY(1));
            rigid &= Math.abs(b0 - 50) < 5f && Math.abs(b1 - 50) < 5f;
        }
        check("rigid: bones within 10% under violent whip", rigid);
    }

    /** A sharp anchor jerk must swing the tail sideways, then damp back to plumb. */
    static void swingExcitesThenDampsBack() {
        DangleSim s = new DangleSim(chain(0, 0, 0, 50, 0, 100));
        for (int f = 0; f < 200; f++) s.step(100, 0, 1 / 60f); // settle
        // Jerk the anchor 80px right over 4 frames.
        for (int f = 0; f < 4; f++) s.step(100 + 20 * (f + 1), 0, 1 / 60f);
        float maxLag = 0;
        for (int f = 0; f < 30; f++) {
            s.step(180, 0, 1 / 60f);
            maxLag = Math.max(maxLag, Math.abs(s.nodeX(2) - 180));
        }
        check("swing: tail lags visibly after a jerk (inertia)", maxLag > 15f);
        for (int f = 0; f < 500; f++) s.step(180, 0, 1 / 60f);
        check("swing: damps back to plumb under the new anchor",
                Math.abs(s.nodeX(2) - 180) < 1f);
    }

    /** Identical input sequences → bit-identical trajectories (bake-replay). */
    static void deterministicReplay() {
        DangleSim a = new DangleSim(chain(0, 0, 0, 40, 0, 90));
        DangleSim b = new DangleSim(chain(0, 0, 0, 40, 0, 90));
        boolean same = true;
        for (int f = 0; f < 200; f++) {
            float ax = (float) (50 * Math.sin(f * 0.13));
            float ay = (float) (20 * Math.cos(f * 0.07));
            a.step(ax, ay, 1 / 60f);
            b.step(ax, ay, 1 / 60f);
            for (int i = 0; i < a.nodeCount(); i++) {
                same &= a.nodeX(i) == b.nodeX(i) && a.nodeY(i) == b.nodeY(i);
            }
        }
        check("determinism: two sims, same inputs, bit-identical", same);
    }

    static void degenerateChainsAreSafe() {
        DangleSim empty = new DangleSim(chain());
        empty.step(0, 0, 1 / 60f); // must not throw
        check("degenerate: empty chain steps safely", true);
        DangleSim one = new DangleSim(chain(5, 5));
        one.step(10, 10, 1 / 60f);
        check("degenerate: single node pins to the anchor",
                one.nodeX(0) == 10 && one.nodeY(0) == 10);
        DangleSim zeroLen = new DangleSim(chain(0, 0, 0, 0)); // coincident pins
        boolean finite = true;
        for (int f = 0; f < 60; f++) {
            zeroLen.step((float) Math.sin(f), 0, 1 / 60f);
            finite &= !Float.isNaN(zeroLen.nodeX(1)) && !Float.isNaN(zeroLen.nodeY(1));
        }
        check("degenerate: zero-length bone stays finite", finite);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (ok) passed++; else failed++;
    }
}
