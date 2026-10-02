import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.transform.TransformQuad;

/**
 * D.02a — the two pure helpers behind the transform tool's rotation unwrap (T4) and its centre-anchored corner scale (T5),
 * plus the fold that decides what a rotation gesture actually STORES.
 * Plain Java, no Android: TransformQuad and the keyframe package are android-free (marker
 * annotations only) and the script refuses to run if that changes.
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
        theStoredAngleIsTheFoldedOneAndTheHudAgreesWithIt();
        theKeyframesTurnTheShortWayAndNotTheLongWayRound();
        aFullTurnStoresZeroAndNot360();
        theDetentPinsTheFoldedNumberNotAnUnwrappedOne();
        aStaleAngleFromAnOlderBuildHealsOnRead();
        thePinchTwistStoresTheSameWayTheArcDoes();
        everyStoredAngleIsCanonicalOverASweep();
        theFoldIsIdempotentAndDeterministicAtTheSeam();
        itIsAFoldNotAClamp();
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

    // ── What a rotation gesture STORES (D.02a T4 follow-up) ──────────────────────────────────
    //
    // The gestures above prove the accumulator unwraps the DELTA. That was only half of T4: the
    // number the model was handed was never folded, so at 175 + 30 it stored 205 while the HUD
    // showed -155, and a keyframe made from it interpolated the long way round. These check the
    // second half — and they check it through the REAL interpolator, not through the helper's own
    // arithmetic, which is what made the original suite unable to see the bug at all.

    /** TransformOverlayView.ROT_DETENT_ENTER_DEG / ROT_DETENT_EXIT_DEG (TransformOverlayView.java:317-318). */
    private static final float ENTER = 3.5f, EXIT = 1.5f;

    /** TransformOverlayView.PINCH_ROT_DEADZONE_DEG (TransformOverlayView.java:325). */
    private static final float PINCH_DEADZONE = 7f;

    /** One MOVE through the ROTATE branch: what the UNWRAPPED pipeline chose, and what gets stored. */
    static final class Move {
        /** What the detent (+ the global snap hook, the identity here) chose, on the turn count. */
        float unwrappedDeg;
        /** What the model stores and the live quad turns by. */
        TransformQuad.StoredRotation stored;
        /** The hysteresis flag the next MOVE is handed. */
        boolean detentBroken;
    }

    /**
     * One MOVE of {@code TransformOverlayView.applyDrag}'s ROTATE branch, line for line, with the
     * ONE Context-coupled piece — {@code snapRotation}, the global snap hook, which reads
     * {@code SnapSettings} off a Context and is the identity until the lead wires it — replaced by
     * that identity. Everything else is the same call with the same argument in the same order,
     * including the order: the detent runs on the UNWRAPPED number (that is what the spec's "Do
     * not" asks for), and the fold happens once, after it.
     */
    static Move oneMove(float startDeg, float deltaDeg, boolean brokenIn) {
        float rawAbs = startDeg + deltaDeg;
        // Update the hysteresis flag FIRST, then snap through it, so one frame can never both
        // escape and re-stick.
        float offDeg = Math.abs(rawAbs - Math.round(rawAbs / 90f) * 90f);
        boolean broken = brokenIn;
        if (broken) {
            if (offDeg <= EXIT) broken = false;
        } else if (offDeg > ENTER) {
            broken = true;
        }
        Move m = new Move();
        // snapRotation() is the identity in this harness (see above).
        m.unwrappedDeg = TransformQuad.detentCardinalDeg(rawAbs, ENTER, EXIT, broken);
        m.stored = TransformQuad.storedRotationDeg(startDeg, m.unwrappedDeg);
        m.detentBroken = broken;
        return m;
    }

    /**
     * A whole ROTATE gesture: {@code steps} MOVEs of {@code stepDeg} each, every handle angle
     * reported through {@code atan2} exactly as the view reads it (so it folds into (-180, 180] and
     * the ACCUMULATOR is the only thing that can recover the turn), the detent re-run on every
     * MOVE, and the LAST MOVE returned.
     *
     * @param grabDeg where the handle was grabbed; irrelevant to the answer (only the steps are
     *                differences) but it decides which atan2 branch each step crosses.
     */
    static Move rotateGesture(float startDeg, double grabDeg, double stepDeg, int steps) {
        double last = Math.atan2(Math.sin(Math.toRadians(grabDeg)), Math.cos(Math.toRadians(grabDeg)));
        double accumRad = 0;
        Move m = null;
        for (int k = 1; k <= steps; k++) {
            double want = grabDeg + stepDeg * k;
            double now = Math.atan2(Math.sin(Math.toRadians(want)), Math.cos(Math.toRadians(want)));
            accumRad += TransformQuad.wrapRad((float) (now - last));
            last = now;
            m = oneMove(startDeg, (float) Math.toDegrees(accumRad), m == null ? false : m.detentBroken);
        }
        return m;
    }

    static void theStoredAngleIsTheFoldedOneAndTheHudAgreesWithIt() {
        // The audit's own case: an object sitting at 175, one gesture, the spin arc dragged +30.
        // 20 MOVEs of +1.5 is how a finger actually arrives, and the handle is grabbed at 170 so
        // that the steps cross atan2's fold at the 7th — the accumulator is the only thing that can
        // recover a turn across it.
        Move m = rotateGesture(175f, 170f, 1.5, 20);
        // The unwrapped pipeline's answer is 175 + 30 = 205, which is a POSE the model can spell
        // two ways. 25 degrees from the 180 cardinal, so no detent width can reach it.
        check("175 + 30 is not snapped by the detent (25 degrees from 180)",
                Math.abs(m.unwrappedDeg - 205f) < 0.01f);
        // The one spelling the model may keep: 205 - 360 = -155. NOT 205 — that is the defect.
        check("the model stores -155, not 205", Math.abs(m.stored.deg - (-155f)) < 1e-3f);
        // The turn the gesture actually made. The drag went +30, so the delta must read +30 and
        // not -330: a fold, not a negation.
        check("the turn reads +30, not -330", Math.abs(m.stored.deltaDeg - 30f) < 1e-3f);
        // The defect was that ONE gesture reported TWO angles — 205 into the model, -155 on the
        // HUD. The HUD line is `Math.round(stored.deg) + " (" + signedDeg(stored.deltaDeg) + ")"`,
        // built from the same StoredRotation the model is handed, so it must read exactly "-155
        // (+30)". Before the fix that line read the same thing and the MODEL said 205; asserting
        // the string pins the half that must not change.
        String hud = Math.round(m.stored.deg) + " (" + (m.stored.deltaDeg >= 0 ? "+" : "")
                + Math.round(m.stored.deltaDeg) + ")";
        check("the HUD reads -155 (+30), unchanged by the fix", hud.equals("-155 (+30)"));
    }

    static void theKeyframesTurnTheShortWayAndNotTheLongWayRound() {
        // Through the REAL interpolator — KeyframeSet.valueAt -> KeyframeTrack.valueAt:80, which is
        // a plain `a.value + (b.value - a.value) * eased` with no wrap anywhere. That is the whole
        // of the harm: the turn a track makes between two keys IS the difference of their numbers.
        Move m = rotateGesture(175f, 170f, 1.5, 20);
        float stored = m.stored.deg;

        KeyframeSet fixed = new KeyframeSet();
        fixed.getOrCreate(KeyframeSet.ROTATION).put(0L, 0f, Easing.LINEAR);
        fixed.getOrCreate(KeyframeSet.ROTATION).put(1000L, stored, Easing.LINEAR);
        // At the halfway time: a=0, b=-155, span=1000, t=0.5, LINEAR.apply(0.5)=0.5, so
        // 0 + (-155 - 0) * 0.5 = -77.5.
        check("keys 0 -> -155 read -77.5 halfway: the short way",
                Math.abs(fixed.valueAt(KeyframeSet.ROTATION, 500L, 0f) - (-77.5f)) < 1e-3f);
        check("the whole 1s span turned -155, not 205",
                Math.abs(fixed.valueAt(KeyframeSet.ROTATION, 1000L, 0f) - (-155f)) < 1e-3f);

        // What the same two keys made from the UNFOLDED number did, which is the defect: the track
        // reports 0 + 205 * 0.5 = 102.5 at the midpoint and turns +205 across the second.
        KeyframeSet broken = new KeyframeSet();
        broken.getOrCreate(KeyframeSet.ROTATION).put(0L, 0f, Easing.LINEAR);
        broken.getOrCreate(KeyframeSet.ROTATION).put(1000L, 205f, Easing.LINEAR);
        check("the unfolded spelling would have read +102.5 halfway (the bug)",
                Math.abs(broken.valueAt(KeyframeSet.ROTATION, 500L, 0f) - 102.5f) < 1e-3f);
    }

    static void aFullTurnStoresZeroAndNot360() {
        // 40 MOVEs of +9 from 0: the accumulator sums to +360, so the unwrapped pipeline says 360.
        Move m = rotateGesture(0f, 90f, 9, 40);
        check("a full turn is +360 unwrapped", Math.abs(m.unwrappedDeg - 360f) < 1e-3f);
        // 360 is 0. Storing 360 makes a whole revolution not compose to identity, with no keyframe
        // anywhere in sight to explain it.
        check("a full turn stores 0, not 360", Math.abs(m.stored.deg) < 1e-3f);
        check("a full turn turns the quad by 0", Math.abs(m.stored.deltaDeg) < 1e-3f);
    }

    static void theDetentPinsTheFoldedNumberNotAnUnwrappedOne() {
        // The detent's own case from the audit: at 360 the distance to the nearest cardinal
        // (round(360/90)*90 = 360) is 0, so the detent HOLDS, whatever its width. 357 lands in
        // tolerance (3 <= 3.5) and 360 lands on it.
        Move m = rotateGesture(0f, 90f, 9.5, 38);   // 38 x 9.5 = 361; the 38th step is at 361
        check("the detent held the near-cardinal number", Math.abs(m.unwrappedDeg - 360f) < 1e-2f);
        check("so what it pins is 0", Math.abs(m.stored.deg) < 1e-3f);

        // And the pin is on the FOLDED spelling: had the detent been fed a pre-folded -3, its
        // distance to the -0 cardinal would be 3 as well and it would snap to 0 — same answer, but
        // the pin has to happen on the number the gesture is really on, which is the 360th.
        Move pre = rotateGesture(0f, 90f, 9.5, 37);  // 37 x 9.5 = 351.5, 8.5 short of a cardinal
        check("8.5 degrees short of a cardinal is NOT snapped", Math.abs(pre.unwrappedDeg - 351.5f) < 0.05f);
        check("and it stores 351.5 as -8.5", Math.abs(pre.stored.deg - (-8.5f)) < 0.05f);
    }

    static void aStaleAngleFromAnOlderBuildHealsOnRead() {
        // A project saved by the build that shipped this defect holds 205 where the model wanted
        // -155. The gesture must be measured from the folded number or its very first move writes
        // back something a whole turn out of step with the HUD. The read is folded inside
        // storedRotationDeg, so this holds even if a caller forgets to fold it.
        Move m = rotateGesture(205f, 170f, 1.5, 20);
        check("a stale 205 start folds to -155 before the arithmetic", Math.abs(m.unwrappedDeg - 235f) < 0.01f);
        check("dragging +30 from a stale 205 stores -125", Math.abs(m.stored.deg - (-125f)) < 1e-3f);
        check("and the turn still reads +30", Math.abs(m.stored.deltaDeg - 30f) < 1e-3f);

        // The pose never moved: -125 is 235 turned round, so the picture is where it was.
        check("the stored angle is the same pose as the unwrapped 235",
                Math.abs(TransformQuad.norm180(m.stored.deg - m.unwrappedDeg)) < 1e-3f);
    }

    static void thePinchTwistStoresTheSameWayTheArcDoes() {
        // The two-finger twin, TransformOverlayView.applyPinch: rawAbs = start + twist - signum *
        // DEADZONE, then the same detent, then the same fold, through the same helper. Starting at
        // 175 with a +30 twist: 175 + 30 - 7 = 198, which is 18 degrees from the 180 cardinal so
        // no detent reaches it; the stored angle is 198 - 360 = -162 and the twist is 198 - 175 =
        // 23, which is the +30 the owner made less the 7 degrees the dead-zone absorbs.
        float rawAbs = 175f + 30f - (float) Math.signum(30f) * PINCH_DEADZONE;
        Move m = oneMove(175f, rawAbs - 175f, false);
        check("the pinch's unwrapped answer is 198", Math.abs(m.unwrappedDeg - 198f) < 1e-3f);
        check("the pinch stores -162, not 198", Math.abs(m.stored.deg - (-162f)) < 1e-3f);
        check("the pinch twist reads +23 (30 less the 7 degree dead-zone)",
                Math.abs(m.stored.deltaDeg - 23f) < 1e-3f);

        // A full turn of twist, pinch edition. The dead-zone is SUBTRACTED, so a twist of exactly +360
        // leaves a genuine -7 pose — that is by design, not a defect, and it is not the 360 case.
        Move shy = oneMove(0f, 360f - PINCH_DEADZONE, true);
        check("a +360 twist less the dead-zone is a real -7 pose, stored as -7",
                Math.abs(shy.stored.deg - (-7f)) < 1e-3f);
        // The twist that actually lands on 0 is 367: the dead-zone takes 7 of it and the remaining
        // 360 is the detent's 0. Before the fix that stored 360.
        Move full = oneMove(0f, 367f - PINCH_DEADZONE, true);
        check("the twist's full turn reaches 0 and the detent pins it there",
                Math.abs(full.unwrappedDeg - 360f) < 1e-3f);
        check("so a full pinch twist stores 0, not 360", Math.abs(full.stored.deg) < 1e-3f);
    }

    static void everyStoredAngleIsCanonicalOverASweep() {
        // The property that makes the fix safe rather than merely different: the fold changes the
        // SPELLING and never the pose. Over starts across and beyond the whole range and turns up
        // to two and a quarter revolutions each way, every case must satisfy
        //   (1) deg is canonical — in (-180, 180] and a fixed point of norm180
        //   (2) norm180(deg - unwrapped) == 0, i.e. the SAME POSE as the unwrapped pipeline chose
        //       <- this is the anti-CLAMP guard: clamping 205 to 180 gives norm180(180-205) = -25
        //   (3) norm180(deg - base - delta) == 0, i.e. the angle the model stores and the turn the
        //       quad is given land on one pose, so they can never disagree  <- with a + sign this
        //       is 180 off at the first case, which is how the sign was found
        int cases = 0;
        boolean inRange = true, canonical = true;
        float worstSamePose = 0f, worstAgree = 0f;
        float[] starts = {-540f, -205f, -180f, -179.5f, -90f, -30f, -0.5f, 0f, 0.5f,
                45f, 90f, 175f, 179.5f, 180f, 205f, 360f, 540f};
        for (float start : starts) {
            for (float delta = -810f; delta <= 810f; delta += 7.5f) {
                Move m = oneMove(start, delta, true);   // broken=true: the sweep may still snap out of it
                float d = m.stored.deg;
                cases++;
                if (!(d > -180f && d <= 180f)) inRange = false;
                if (Math.abs(TransformQuad.norm180(d) - d) > 1e-3f) canonical = false;
                worstSamePose = Math.max(worstSamePose,
                        Math.abs(TransformQuad.norm180(d - m.unwrappedDeg)));
                worstAgree = Math.max(worstAgree,
                        Math.abs(TransformQuad.norm180(d - TransformQuad.norm180(start)
                                - m.stored.deltaDeg)));
            }
        }
        check("every stored angle over " + cases + " start/turn pairs is in (-180, 180]", inRange);
        check("every stored angle is a fixed point of norm180", canonical);
        check("every stored angle is the same POSE as the unwrapped number (worst "
                + round1(worstSamePose) + " deg off, must be 0)", worstSamePose < 1e-2f);
check("stored angle and quad turn land on one pose, every case (worst "
                + round1(worstAgree) + " deg off, must be 0)", worstAgree < 1e-2f);
    }

    static void theFoldIsIdempotentAndDeterministicAtTheSeam() {
        // (-180, 180] is half-open, so -180 must come back as +180 — otherwise the seam is a fixed
        // point that reads -180 and the range is really [-180, 180] with two spellings of 180.
        check("norm180(180) = 180", TransformQuad.norm180(180f) == 180f);
        check("norm180(-180) = 180", TransformQuad.norm180(-180f) == 180f);
        check("norm180(540) = 180", TransformQuad.norm180(540f) == 180f);
        check("norm180(-540) = 180", TransformQuad.norm180(-540f) == 180f);
        check("norm180(360) = 0", TransformQuad.norm180(360f) == 0f);
        // Idempotent: feeding the fold its own output must be a no-op, or a caller that folds twice
        // and a caller that folds once could disagree. And deg - base - delta must be a whole
        // number of turns: startDeg 30, one MOVE that snaps to 45, deg = 45, delta = 15, and
        // 45 - 30 - 15 = 0.
        float[] probes = {0f, 45f, -45f, 179.9f, -179.9f, 180f, -180f, 205f, -205f, 359.5f};
        boolean idem = true, pairAgrees = true;
        for (float p : probes) {
            float once = TransformQuad.norm180(p);
            if (once != TransformQuad.norm180(once)) idem = false;
            TransformQuad.StoredRotation sr = TransformQuad.storedRotationDeg(30f, p);
            if (Math.abs(TransformQuad.norm180(sr.deg - 30f - sr.deltaDeg)) > 1e-3f) pairAgrees = false;
        }
        check("the fold is a fixed point of itself", idem);
        check("deg - startDeg - deltaDeg is a whole number of turns, every probe", pairAgrees);
    }

    static void itIsAFoldNotAClamp() {
        // A clamp would be the tempting wrong fix and it would CHANGE THE PICTURE: 205 clamped to
        // 180 is 25 degrees short of where the finger left the object, and nothing about that is
        // visible until the next gesture jumps.
        check("205 folds to -155 (a clamp would say 180)",
                Math.abs(TransformQuad.storedRotationDeg(0f, 205f).deg - (-155f)) < 1e-4f);
        check("-205 folds to 155 (a clamp would say -180)",
                Math.abs(TransformQuad.storedRotationDeg(0f, -205f).deg - 155f) < 1e-4f);
        // The anti-clamp statement, measured: folding must never move the pose by a measurable amount.
        // 205 and -155 differ by exactly 360, so cos and sin agree to float precision.
        float folded = TransformQuad.storedRotationDeg(0f, 205f).deg;
        float cosFolded = (float) Math.cos(Math.toRadians(folded));
        float cosRaw = (float) Math.cos(Math.toRadians(205f));
        float sinFolded = (float) Math.sin(Math.toRadians(folded));
        float sinRaw = (float) Math.sin(Math.toRadians(205f));
        check("the folded angle renders identically to the raw one (cos and sin agree to 1e-6)",
                Math.abs(cosFolded - cosRaw) < 1e-6f && Math.abs(sinFolded - sinRaw) < 1e-6f);
        // And the clamp would NOT: cos(180) = -1 while cos(205) = -cos(25) = -0.9063, so clamping
        // to 180 shifts the drawn picture by 1 - 0.9063 = 0.0937 of the unit circle — 25 degrees,
        // visible the moment the next gesture starts from where the finger actually left it.
        float cosClamped = (float) Math.cos(Math.toRadians(180f));
        check("a clamp to 180 would have moved the pose (cos differs by 0.0937)",
                Math.abs(cosClamped - cosRaw) > 0.05f);
    }

    static float round1(float v) { return Math.round(v * 10f) / 10f; }
}
