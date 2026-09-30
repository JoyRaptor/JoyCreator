import com.fadcam.ui.faditor.transform.TransformQuad;

/**
 * D.02a — the two pure helpers behind the transform tool's rotation unwrap (T4) and its centre-anchored corner scale (T5).
 * Plain Java, no Android: TransformQuad is android-free and the script refuses to run if that changes.
 *
 * <p>Every expected number is derived in the check that uses it (R9).
 */
public class GestureAngleTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        wrapIsTheShortWayRound();
        twentyStepsOfTenDegreesFrom170AccumulateToPlus200();
        theOldDifferenceAgainstTheStartAngleLosesTheTurn();
        centreFactorPutsTheCornerUnderTheFinger();
        centreFactorClamps();
        System.out.println(failed == 0
                ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    static float rad(double deg) { return (float) Math.toRadians(deg); }

    static void wrapIsTheShortWayRound() {
        // +190 degrees is -170 the short way; -190 is +170; 30 stays 30; 0 stays 0; a full turn is 0.
        check("wrap 190 -> -170", Math.abs(Math.toDegrees(TransformQuad.wrapRad(rad(190))) - (-170)) < 1e-3);
        check("wrap -190 -> 170", Math.abs(Math.toDegrees(TransformQuad.wrapRad(rad(-190))) - 170) < 1e-3);
        check("wrap 30 -> 30", Math.abs(Math.toDegrees(TransformQuad.wrapRad(rad(30))) - 30) < 1e-4);
        check("wrap 360 -> 0", Math.abs(TransformQuad.wrapRad(rad(360))) < 1e-4);
        // The seam itself: exactly +180 stays +180 (the range is (-PI, PI]), so a step of exactly half a turn is not sign-flipped.
        check("wrap 180 stays 180", Math.abs(Math.toDegrees(TransformQuad.wrapRad((float) Math.PI)) - 180) < 1e-3);
        check("wrap NaN -> 0 (a bad sample must not poison the total)", TransformQuad.wrapRad(Float.NaN) == 0f);
        check("wrap Infinity -> 0", TransformQuad.wrapRad(Float.POSITIVE_INFINITY) == 0f);
    }

    static void twentyStepsOfTenDegreesFrom170AccumulateToPlus200() {
        // A finger pair (or the rotate handle) starts at 170 degrees and turns +10 per move for 20 moves. The angles it reports are
        // 180, 190, ... 370, which atan2 folds into (-180, 180]: 180, -170, -160, ... 10. Summing wrap(step) gives 20 x 10 = +200.
        double last = rad(170), accum = 0;
        for (int k = 1; k <= 20; k++) {
            double now = Math.atan2(Math.sin(rad(170 + 10 * k)), Math.cos(rad(170 + 10 * k)));
            accum += TransformQuad.wrapRad((float) (now - last));
            last = now;
        }
        check("20 x +10 degrees from 170 accumulates to +200, not -160", Math.abs(Math.toDegrees(accum) - 200) < 0.05);
    }

    static void theOldDifferenceAgainstTheStartAngleLosesTheTurn() {
        // The behaviour being replaced: delta = wrap(now - start). At +200 it reports -160 — the object would jump 360 degrees.
        double start = rad(170), now = Math.atan2(Math.sin(rad(370)), Math.cos(rad(370)));
        float old = TransformQuad.wrapRad((float) (now - start));
        check("differencing against the start reports -160 at +200 (the bug)", Math.abs(Math.toDegrees(old) - (-160)) < 0.05);
    }

    static void centreFactorPutsTheCornerUnderTheFinger() {
        // Box 0..100 wide, corner dragged from 100 to 150: the factor measured from the opposite corner is 1.5. Applied about the
        // centre (50) the corner lands at 50 + (100 - 50) * f'. For it to land at 150, f' = (150 - 50) / 50 = 2 = 2 x 1.5 - 1.
        float f = TransformQuad.centreFactor(1.5f);
        check("centreFactor(1.5) = 2", Math.abs(f - 2f) < 1e-6);
        check("the corner lands at 150", Math.abs((50f + (100f - 50f) * f) - 150f) < 1e-4);
        check("centreFactor(1) = 1 (no drag, no change)", Math.abs(TransformQuad.centreFactor(1f) - 1f) < 1e-6);
        // Pulling the corner IN: 100 -> 70, f = 0.7; landing at 70 needs f' = (70 - 50)/50 = 0.4 = 2 x 0.7 - 1.
        check("centreFactor(0.7) = 0.4", Math.abs(TransformQuad.centreFactor(0.7f) - 0.4f) < 1e-6);
    }

    static void centreFactorClamps() {
        check("centreFactor(0.2) clamps to MIN_FACTOR (2 x 0.2 - 1 is negative)",
                TransformQuad.centreFactor(0.2f) == TransformQuad.MIN_FACTOR);
        check("centreFactor(3.4) clamps to MAX_FACTOR (2 x 3.4 - 1 = 5.8)",
                TransformQuad.centreFactor(3.4f) == TransformQuad.MAX_FACTOR);
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; } else { failed++; System.out.println("FAIL: " + what); }
    }
}
