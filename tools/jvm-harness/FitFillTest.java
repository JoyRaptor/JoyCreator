import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.Keyframe;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;
import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * Fit / Fill on an image overlay (JoyRaptor's bug 5, 2026-09-13).
 *
 * <p>The bug: both buttons wrote the STATIC sizeFraction while the renderer reads
 * {@code animatedSizeFraction()}, which prefers the SCALE keyframe track. On JoyRaptor's project
 * 62 of 80 image overlays have such a track, so the buttons did nothing on most of his images —
 * and worked perfectly on the 18 without keys, which is why it survived.
 *
 * <p>These assertions are about the DRAWN size, not the field: drawn height is
 * {@code sizeFraction * canvasH} and drawn width is {@code sizeFraction * canvasH * imgAspect}
 * (TextOverlayLayer.imageHeightPx / imageWidthPx), so the test measures what the user sees.
 *
 * <p>Run: {@code bash tools/jvm-harness/run-fitfill.sh}
 */
public class FitFillTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        stillImageFits();
        stillImageFills();
        animatedFitNeverExceedsTheFrame();
        animatedFillAlwaysCovers();
        animationShapeIsPreserved();
        aMotionPathSurvivesFit();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // Canvas: a portrait phone frame. Image: landscape. The interesting mismatch.
    static final float CW = 900f, CH = 1600f;
    static final float IW = 1920f, IH = 1080f;
    static final float ASPECT = IW / IH;

    static TextOverlayItem image() {
        // sizeFraction 2.5f is the value JoyRaptor's own overlay carried.
        TextOverlayItem o = new TextOverlayItem("", 0xFFFFFFFF, 0.5f, 0.5f, 2.5f, 0f);
        o.setImageUri("file:///fake.png");
        return o;
    }

    static float drawnH(TextOverlayItem o, long t) { return o.animatedSizeFraction(t) * CH; }
    static float drawnW(TextOverlayItem o, long t) { return o.animatedSizeFraction(t) * CH * ASPECT; }

    static void addScaleKeys(TextOverlayItem o, float... vals) {
        KeyframeTrack tr = o.getKeyframes().getOrCreate(KeyframeSet.SCALE);
        for (int i = 0; i < vals.length; i++) {
            tr.put(i * 1000L, vals[i], Easing.LINEAR);
        }
    }

    static void stillImageFits() {
        TextOverlayItem o = image();
        o.applyFit(CW, CH, IW, IH);
        // Fits means: inside on BOTH axes, and touching on at least one.
        check("a still image fits inside the frame",
                drawnW(o, 0) <= CW + 0.5f && drawnH(o, 0) <= CH + 0.5f);
        check("and it touches the binding edge (no wasted room)",
                Math.abs(drawnW(o, 0) - CW) < 0.5f || Math.abs(drawnH(o, 0) - CH) < 0.5f);
    }

    static void stillImageFills() {
        TextOverlayItem o = image();
        o.applyFill(CW, CH, IW, IH);
        check("a still image covers the frame",
                drawnW(o, 0) >= CW - 0.5f && drawnH(o, 0) >= CH - 0.5f);
    }

    static void animatedFitNeverExceedsTheFrame() {
        TextOverlayItem o = image();
        addScaleKeys(o, 0.4f, 1.2f, 0.9f);   // a zoom that peaks in the middle
        o.applyFit(CW, CH, IW, IH);
        boolean everOutside = false, everTouches = false;
        for (long t = 0; t <= 2000; t += 50) {
            if (drawnW(o, t) > CW + 0.5f || drawnH(o, t) > CH + 0.5f) everOutside = true;
            if (Math.abs(drawnW(o, t) - CW) < 1f || Math.abs(drawnH(o, t) - CH) < 1f) everTouches = true;
        }
        check("THE BUG: Fit changes an ANIMATED image at all",
                Math.abs(o.getKeyframes().get(KeyframeSet.SCALE).keyframes.get(1).value - 1.2f) > 1e-4f);
        check("an animated image never exceeds the frame after Fit", !everOutside);
        check("and its largest moment touches the frame", everTouches);
    }

    static void animatedFillAlwaysCovers() {
        TextOverlayItem o = image();
        addScaleKeys(o, 0.4f, 1.2f, 0.9f);
        o.applyFill(CW, CH, IW, IH);
        boolean everUncovered = false;
        for (long t = 0; t <= 2000; t += 50) {
            if (drawnW(o, t) < CW - 0.5f || drawnH(o, t) < CH - 0.5f) everUncovered = true;
        }
        check("an animated image always covers the frame after Fill", !everUncovered);
    }

    static void animationShapeIsPreserved() {
        TextOverlayItem o = image();
        addScaleKeys(o, 0.4f, 1.2f, 0.9f);
        KeyframeTrack tr = o.getKeyframes().get(KeyframeSet.SCALE);
        float b0 = tr.keyframes.get(0).value, b1 = tr.keyframes.get(1).value, b2 = tr.keyframes.get(2).value;
        o.applyFit(CW, CH, IW, IH);
        float a0 = tr.keyframes.get(0).value, a1 = tr.keyframes.get(1).value, a2 = tr.keyframes.get(2).value;
        // One ratio for the whole track: the animation is rescaled, not reshaped or flattened.
        check("every key moved by the SAME ratio (the animation keeps its shape)",
                Math.abs((a0 / b0) - (a1 / b1)) < 1e-4f && Math.abs((a1 / b1) - (a2 / b2)) < 1e-4f);
        check("the keys are still three, not flattened to one",
                tr.keyframes.size() == 3);
        for (Keyframe k : tr.keyframes) {
            if (k.timeMs < 0) { check("key times untouched", false); return; }
        }
        check("key TIMES are untouched",
                tr.keyframes.get(0).timeMs == 0 && tr.keyframes.get(1).timeMs == 1000
                        && tr.keyframes.get(2).timeMs == 2000);
    }

    static void aMotionPathSurvivesFit() {
        TextOverlayItem o = image();
        o.getKeyframes().getOrCreate(KeyframeSet.X).put(0L, 0.1f, Easing.LINEAR);
        o.getKeyframes().getOrCreate(KeyframeSet.X).put(1000L, 0.9f, Easing.LINEAR);
        o.applyFit(CW, CH, IW, IH);
        KeyframeTrack x = o.getKeyframes().get(KeyframeSet.X);
        check("a motion path is not re-centred away by Fit",
                x != null && x.keyframes.size() == 2
                        && Math.abs(x.keyframes.get(0).value - 0.1f) < 1e-5f
                        && Math.abs(x.keyframes.get(1).value - 0.9f) < 1e-5f);
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
