import com.fadcam.ui.faditor.transform.mesh.MeshCurves;
import com.fadcam.ui.faditor.transform.mesh.MeshEasingFit;
import com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack;

/**
 * Thinning a take must not flatten the performance.
 *
 * <p>JoyRaptor asked for this by name — <i>"we already have a lot of different types of easing
 * that it could look at and identify what it's closest to"</i> — and the reason it needs a test
 * rather than a look on a phone is that the failure is invisible: a whipped gesture that plays
 * back as a glide looks like a slightly disappointing animation, not like a bug, so nobody ever
 * reports it and nobody can point at the moment it went wrong.
 *
 * <p>The method is honest by construction: each case RECORDS a dense motion with a known curve,
 * thins it to two keys, and asks what curve the fitter names. The one that matters most is the
 * last: a genuinely straight motion must come back LINEAR, because a fitter that upgrades
 * everything to a flourish is worse than no fitter at all.
 */
public final class MeshEasingFitTest {

    private static int checks, failures;

    public static void main(String[] args) {
        recoversEaseOut();
        recoversAnOvershoot();
        leavesAStraightMotionAlone();
        leavesAGapWithNoEvidenceAlone();
        neverTouchesAPresetOwnedKey();
        survivesDegenerateInput();

        System.out.println();
        System.out.println(failures == 0
                ? "MeshEasingFitTest: " + checks + " checks OK"
                : "MeshEasingFitTest: " + failures + " FAILURES of " + checks);
        if (failures != 0) System.exit(1);
    }

    // ── the rig under test ───────────────────────────────────────────────

    /** A dense one-second recording of a single value moving 0 → 1 along {@code curve}. */
    private static MeshPoseTrack record(String curve) {
        MeshPoseTrack t = new MeshPoseTrack(2);
        t.setCurve(MeshCurves.APP_EASING);
        for (int ms = 0; ms <= 1000; ms += 33) {
            float u = ms / 1000f;
            float v = MeshCurves.APP_EASING.apply(curve, u);
            t.put(ms, new float[]{v, -v}, "LINEAR");
        }
        return t;
    }

    /** The same motion reduced to its two end keys, which is what hard thinning leaves. */
    private static MeshPoseTrack endsOnly(MeshPoseTrack dense) {
        MeshPoseTrack t = new MeshPoseTrack(dense.arity());
        t.setCurve(MeshCurves.APP_EASING);
        long[] times = dense.times();
        float[] v = new float[dense.arity()];
        for (long ms : new long[]{times[0], times[times.length - 1]}) {
            dense.valueAt(ms, v);
            t.put(ms, v.clone(), MeshPoseTrack.DEFAULT_EASING);
        }
        return t;
    }

    private static String easingAt(MeshPoseTrack t, long ms) {
        for (MeshPoseTrack.Pose p : t.poses()) if (p.timeMs == ms) return p.easing;
        return null;
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL  " + what); }
    }

    // ── cases ────────────────────────────────────────────────────────────

    private static void recoversEaseOut() {
        MeshPoseTrack dense = record("EASE_OUT");
        MeshPoseTrack thin = endsOnly(dense);
        int n = MeshEasingFit.fit(thin, dense, null, 0, 1000, MeshCurves.APP_EASING);
        String got = easingAt(thin, 0);
        check("a fast-then-settling move is not left linear (got " + got + ")",
                n == 1 && got != null && !"LINEAR".equals(got));
        // Not asserting the exact name: several of the app's curves decelerate, and demanding
        // one particular label would be testing the enum rather than the fit. What matters is
        // that the RESULT is close to what was recorded.
        check("and what it chose reproduces the recording", errorOf(thin, dense) < 0.02f);
    }

    private static void recoversAnOvershoot() {
        MeshPoseTrack dense = record("OVERSHOOT");
        MeshPoseTrack thin = endsOnly(dense);
        MeshEasingFit.fit(thin, dense, null, 0, 1000, MeshCurves.APP_EASING);
        String got = easingAt(thin, 0);
        check("an overshoot is not flattened into a glide (got " + got + ")",
                got != null && !"LINEAR".equals(got));
        check("and the fitted curve tracks the overshoot", errorOf(thin, dense) < 0.05f);
    }

    private static void leavesAStraightMotionAlone() {
        MeshPoseTrack dense = record("LINEAR");
        MeshPoseTrack thin = endsOnly(dense);
        MeshEasingFit.fit(thin, dense, null, 0, 1000, MeshCurves.APP_EASING);
        // THE ONE THAT MATTERS. A fitter that upgrades everything to a flourish would pass every
        // other case here and still be useless, because nothing the user did would survive.
        check("a straight motion is described as straight (got " + easingAt(thin, 0) + ")",
                "LINEAR".equals(easingAt(thin, 0)));
    }

    private static void leavesAGapWithNoEvidenceAlone() {
        MeshPoseTrack dense = new MeshPoseTrack(2);
        dense.setCurve(MeshCurves.APP_EASING);
        dense.put(0, new float[]{0f, 0f}, "LINEAR");
        dense.put(1000, new float[]{1f, -1f}, "LINEAR");

        MeshPoseTrack thin = endsOnly(dense);
        int n = MeshEasingFit.fit(thin, dense, null, 0, 1000, MeshCurves.APP_EASING);
        check("two hand-placed keys with nothing between them are left as the user made them",
                n == 0 && MeshPoseTrack.DEFAULT_EASING.equals(easingAt(thin, 0)));
    }

    private static void neverTouchesAPresetOwnedKey() {
        MeshPoseTrack dense = record("EASE_OUT");
        MeshPoseTrack thin = endsOnly(dense);
        for (MeshPoseTrack.Pose p : thin.poses()) p.presetOwned = true;
        int n = MeshEasingFit.fit(thin, dense, null, 0, 1000, MeshCurves.APP_EASING);
        check("a key an animation preset owns is not ours to re-describe", n == 0);
    }

    private static void survivesDegenerateInput() {
        check("null tracks", MeshEasingFit.fit(null, null, null, 0, 1, MeshCurves.APP_EASING) == 0);
        MeshPoseTrack one = new MeshPoseTrack(2);
        one.put(0, new float[]{0f, 0f}, null);
        check("a single key has no gap to fit",
                MeshEasingFit.fit(one, one, null, 0, 1000, MeshCurves.APP_EASING) == 0);
        MeshPoseTrack mismatched = new MeshPoseTrack(4);
        check("tracks of different arity are refused rather than read out of bounds",
                MeshEasingFit.fit(mismatched, record("EASE_OUT"), null, 0, 1000,
                        MeshCurves.APP_EASING) == 0);
    }

    // ── how close the thinned playback is to what was recorded ───────────

    private static float errorOf(MeshPoseTrack thin, MeshPoseTrack dense) {
        float[] a = new float[dense.arity()], b = new float[dense.arity()];
        float worst = 0f;
        for (long ms = 0; ms <= 1000; ms += 17) {
            if (!thin.valueAt(ms, a) || !dense.valueAt(ms, b)) continue;
            for (int c = 0; c < dense.arity(); c++) {
                worst = Math.max(worst, Math.abs(a[c] - b[c]));
            }
        }
        return worst;
    }
}
