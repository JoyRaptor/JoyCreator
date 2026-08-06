import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.CompositingSpec;
import com.fadcam.ui.faditor.model.MaskAnimator;

/**
 * JVM harness for MaskAnimator — animated mask parameters and the object LINK.
 *
 * <p>Two groups matter most:</p>
 * <ul>
 *   <li><b>Identity when nothing animates.</b> Every project that has never keyed a mask must come
 *       back byte-for-byte the same object, not a copy — that is what makes the feature free for
 *       everyone who does not use it, and a copy would also silently drop the aliasing the
 *       renderers rely on.</li>
 *   <li><b>The link rotates in PIXELS.</b> MaskAnimator's own comment claims that rotating the
 *       offset in normalised coordinates would SHEAR the mask on a non-square frame, with the
 *       error zero at 0°/180° and largest at 45°. That is a falsifiable claim, so it is tested:
 *       a mask offset purely horizontally from its object, rotated 90° on a 2:1 frame, must land
 *       purely vertically at the SAME pixel distance.</li>
 * </ul>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-key.sh}</p>
 */
public class MaskAnimatorTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        nullAndEmptyPassThrough();
        unanimatedUnlinkedIsTheSameObject();
        animatesTheSevenParameters();
        sizeNeverReachesZero();
        clampsIntoRange();
        linkTranslatesWithTheObject();
        linkScalesWithTheObject();
        linkRotationIsAPixelRotationNotAShear();
        linkWithNoObjectKeyframesIsInert();
        linkBaseScaleZeroCannotProduceNaN();

        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    static CompositingSpec specWithMask() {
        CompositingSpec s = new CompositingSpec();
        CompositingSpec.MaskShape m = new CompositingSpec.MaskShape();
        m.cx = 0.5f; m.cy = 0.5f; m.w = 0.4f; m.h = 0.4f;
        s.masks.add(m);
        return s;
    }

    static KeyframeSet objectAt(float x, float y, float scale, float rot) {
        KeyframeSet k = new KeyframeSet();
        k.getOrCreate(KeyframeSet.X).put(0, x, Easing.LINEAR);
        k.getOrCreate(KeyframeSet.Y).put(0, y, Easing.LINEAR);
        k.getOrCreate(KeyframeSet.SCALE).put(0, scale, Easing.LINEAR);
        k.getOrCreate(KeyframeSet.ROTATION).put(0, rot, Easing.LINEAR);
        return k;
    }

    // ── Identity / pass-through ─────────────────────────────────────────────

    static void nullAndEmptyPassThrough() {
        check("null spec passes through", MaskAnimator.resolve(null, null, 0, 100, 100) == null);
        CompositingSpec empty = new CompositingSpec();
        check("a spec with no masks is returned as-is",
                MaskAnimator.resolve(empty, null, 500, 100, 100) == empty);
    }

    static void unanimatedUnlinkedIsTheSameObject() {
        CompositingSpec s = specWithMask();
        // IDENTITY, not equality: an untouched project must not even allocate here.
        check("unanimated + unlinked returns the SAME instance",
                MaskAnimator.resolve(s, null, 1234, 1920, 1080) == s);
    }

    // ── Animation ───────────────────────────────────────────────────────────

    static void animatesTheSevenParameters() {
        CompositingSpec s = specWithMask();
        s.maskKeys = new KeyframeSet();
        s.maskKeys.getOrCreate(MaskAnimator.CX).put(0, 0.2f, Easing.LINEAR);
        s.maskKeys.getOrCreate(MaskAnimator.CX).put(1000, 0.8f, Easing.LINEAR);
        s.maskKeys.getOrCreate(MaskAnimator.W).put(0, 0.2f, Easing.LINEAR);
        s.maskKeys.getOrCreate(MaskAnimator.W).put(1000, 0.6f, Easing.LINEAR);
        s.maskKeys.getOrCreate(MaskAnimator.FEATHER).put(0, 0f, Easing.LINEAR);
        s.maskKeys.getOrCreate(MaskAnimator.FEATHER).put(1000, 1f, Easing.LINEAR);

        CompositingSpec at0 = MaskAnimator.resolve(s, null, 0, 1920, 1080);
        CompositingSpec mid = MaskAnimator.resolve(s, null, 500, 1920, 1080);
        CompositingSpec end = MaskAnimator.resolve(s, null, 1000, 1920, 1080);
        check("animating returns a COPY, not the original", at0 != s);
        check("cx at t=0", near(at0.masks.get(0).cx, 0.2f));
        check("cx halfway", near(mid.masks.get(0).cx, 0.5f));
        check("cx at the end", near(end.masks.get(0).cx, 0.8f));
        check("w halfway", near(mid.masks.get(0).w, 0.4f));
        check("feather animates too", near(mid.maskFeather, 0.5f));
        check("the ORIGINAL is never mutated", near(s.masks.get(0).cx, 0.5f));
    }

    static void sizeNeverReachesZero() {
        CompositingSpec s = specWithMask();
        s.maskKeys = new KeyframeSet();
        s.maskKeys.getOrCreate(MaskAnimator.W).put(0, 0f, Easing.LINEAR);
        s.maskKeys.getOrCreate(MaskAnimator.H).put(0, 0f, Easing.LINEAR);
        CompositingSpec r = MaskAnimator.resolve(s, null, 0, 1920, 1080);
        // A zero-size shape makes an EMPTY path, and an empty mask reads as "no mask at all"
        // rather than "a mask you cannot see" — two very different pictures.
        check("width floors at MIN_SIZE", r.masks.get(0).w >= MaskAnimator.MIN_SIZE);
        check("height floors at MIN_SIZE", r.masks.get(0).h >= MaskAnimator.MIN_SIZE);
    }

    static void clampsIntoRange() {
        CompositingSpec s = specWithMask();
        s.maskKeys = new KeyframeSet();
        s.maskKeys.getOrCreate(MaskAnimator.CX).put(0, 5f, Easing.LINEAR);
        s.maskKeys.getOrCreate(MaskAnimator.CY).put(0, -5f, Easing.LINEAR);
        CompositingSpec r = MaskAnimator.resolve(s, null, 0, 1920, 1080);
        check("cx clamps to 1", near(r.masks.get(0).cx, 1f));
        check("cy clamps to 0", near(r.masks.get(0).cy, 0f));
    }

    // ── The link ────────────────────────────────────────────────────────────

    static void linkTranslatesWithTheObject() {
        CompositingSpec s = specWithMask();
        CompositingSpec.MaskShape m = s.masks.get(0);
        m.linkedToObject = true;
        m.linkBaseX = 0.5f; m.linkBaseY = 0.5f; m.linkBaseScale = 1f; m.linkBaseRotDeg = 0f;
        m.cx = 0.6f; m.cy = 0.5f;             // 0.1 to the right of the object
        CompositingSpec r = MaskAnimator.resolve(s, objectAt(0.2f, 0.5f, 1f, 0f), 0, 1000, 1000);
        check("a linked mask keeps its offset when the object moves",
                near(r.masks.get(0).cx, 0.3f) && near(r.masks.get(0).cy, 0.5f));
    }

    static void linkScalesWithTheObject() {
        CompositingSpec s = specWithMask();
        CompositingSpec.MaskShape m = s.masks.get(0);
        m.linkedToObject = true;
        m.linkBaseX = 0.5f; m.linkBaseY = 0.5f; m.linkBaseScale = 1f;
        m.cx = 0.6f; m.cy = 0.5f; m.w = 0.2f; m.h = 0.2f;
        CompositingSpec r = MaskAnimator.resolve(s, objectAt(0.5f, 0.5f, 2f, 0f), 0, 1000, 1000);
        check("the offset doubles with the object", near(r.masks.get(0).cx, 0.7f));
        check("the mask SIZE doubles too", near(r.masks.get(0).w, 0.4f));
    }

    /**
     * THE claim in MaskAnimator's doc comment, made falsifiable.
     *
     * <p>Frame is 2000x1000 — deliberately non-square, which is where a normalised-space rotation
     * shears. The mask sits 0.1 to the RIGHT of the object: 0.1 x 2000 = 200 PIXELS. Rotate the
     * object 90° and the mask must end up 200 pixels BELOW it — i.e. 200/1000 = 0.2 in normalised
     * y. A naive rotation in normalised units would put it at 0.1, half the correct distance.</p>
     */
    static void linkRotationIsAPixelRotationNotAShear() {
        CompositingSpec s = specWithMask();
        CompositingSpec.MaskShape m = s.masks.get(0);
        m.linkedToObject = true;
        m.linkBaseX = 0.5f; m.linkBaseY = 0.5f; m.linkBaseScale = 1f; m.linkBaseRotDeg = 0f;
        m.cx = 0.6f; m.cy = 0.5f;
        CompositingSpec r = MaskAnimator.resolve(s, objectAt(0.5f, 0.5f, 1f, 90f), 0, 2000, 1000);
        float cx = r.masks.get(0).cx, cy = r.masks.get(0).cy;
        check("rotated 90°, x returns to the object's x: " + cx, near(cx, 0.5f, 0.005f));
        check("...and y is 200px away on a 1000px-tall frame (0.7), NOT 0.6: " + cy,
                near(cy, 0.7f, 0.005f));
        check("the shape's own rotation follows the object",
                near(r.masks.get(0).rotationDeg, 90f));
    }

    static void linkWithNoObjectKeyframesIsInert() {
        CompositingSpec s = specWithMask();
        CompositingSpec.MaskShape m = s.masks.get(0);
        m.linkedToObject = true;
        m.linkBaseX = 0.5f; m.linkBaseY = 0.5f; m.linkBaseScale = 1f;
        m.cx = 0.6f; m.cy = 0.4f;
        // A PiP that never animates has no transform track at all; the mask must stay put.
        CompositingSpec r = MaskAnimator.resolve(s, null, 999, 1920, 1080);
        check("null object transform leaves a linked mask where it was",
                near(r.masks.get(0).cx, 0.6f) && near(r.masks.get(0).cy, 0.4f));
    }

    static void linkBaseScaleZeroCannotProduceNaN() {
        CompositingSpec s = specWithMask();
        CompositingSpec.MaskShape m = s.masks.get(0);
        m.linkedToObject = true;
        m.linkBaseScale = 0f;   // as a hand-edited JSON could supply
        m.cx = 0.6f;
        CompositingSpec r = MaskAnimator.resolve(s, objectAt(0.5f, 0.5f, 1f, 0f), 0, 1000, 1000);
        check("a zero base scale does not make NaN geometry",
                !Float.isNaN(r.masks.get(0).cx) && !Float.isNaN(r.masks.get(0).w));
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    static boolean near(float a, float b) { return near(a, b, 0.001f); }
    static boolean near(float a, float b, float tol) { return Math.abs(a - b) <= tol; }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
