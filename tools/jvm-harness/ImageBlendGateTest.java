import com.fadcam.ui.faditor.model.BlendModes;
import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * The gate that decides whether an image overlay leaves the export's Canvas for a shader.
 *
 * <p>Two consumers read it — {@code ImageBlendGlEffect} decides whether to exist, and
 * {@code CompositeExportOverlay.filterTextOverlays} decides whether to drop the item — and they
 * must be EXACTLY complementary: either side drifting means the image is composited twice
 * (blended in the shader, then plain on top of itself) or vanishes from the export entirely.
 * The predicate lives in the model so that is structural; this pins its meaning.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-textstyle.sh}</p>
 */
public class ImageBlendGateTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        normalIsNotABlend();
        everyRealModeIsABlend();
        textIsNeverRouted();
        unknownModesDegradeToNormal();
        effectsAloneAlsoRouteToTheShader();
        aKeyRoutesButAMaskDoesNot();

        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    private static TextOverlayItem image(String mode) {
        TextOverlayItem o = new TextOverlayItem("", 0xFFFFFFFF, 0.5f, 0.5f, 0.5f, 0f);
        o.setImageUri("file:///dev/null/test.png");
        o.setOverlayBlendMode(mode);
        return o;
    }

    /**
     * An image carrying EFFECTS must leave the canvas even with NORMAL blend — a Canvas cannot run
     * a fragment shader, so leaving it behind is what made the Effects tab inert.
     *
     * <p>Both consumers ask {@code wantsGlExport}, never the two halves separately: if the emitter
     * checked blend and the canvas skip checked blend-or-fx (or vice versa) an image with effects
     * would be drawn twice or not at all. That is what these assertions protect.</p>
     */
    static void effectsAloneAlsoRouteToTheShader() {
        TextOverlayItem plain = image(BlendModes.NORMAL);
        check("no blend, no fx → canvas", !plain.wantsGlExport());

        TextOverlayItem fx = image(BlendModes.NORMAL);
        fx.getOrCreateFx().add("invert");
        check("effects alone route an image to the shader", fx.wantsGlExport());
        check("…and that is FX, not blend", fx.hasExportFx() && !fx.wantsExportBlend());

        TextOverlayItem both = image(BlendModes.MULTIPLY);
        both.getOrCreateFx().add("invert");
        check("blend AND effects still route exactly once", both.wantsGlExport());

        TextOverlayItem t = new TextOverlayItem("hi", 0xFFFFFFFF, 0.5f, 0.5f, 0.5f, 0f);
        t.getOrCreateFx().add("invert");
        check("a TEXT overlay with effects is not routed here (TextFxGlEffect owns it)",
                !t.wantsGlExport());

        TextOverlayItem emptied = image(BlendModes.NORMAL);
        emptied.getOrCreateFx();          // stack exists but carries no cards
        check("an empty stack is not effects", !emptied.wantsGlExport());
    }

    /**
     * A CHROMA KEY needs a shader too, so it routes; a MASK does not, so it must NOT.
     *
     * <p>A mask is a Canvas clip and {@code ImageOverlayDraw} applies it on either export path.
     * Routing a merely-masked image into GL would move it in z (chain position is paint order)
     * for no benefit at all — the caveat in {@code ImageBlendGlEffect}'s class doc, applied.</p>
     */
    static void aKeyRoutesButAMaskDoesNot() {
        TextOverlayItem keyed = image(BlendModes.NORMAL);
        com.fadcam.ui.faditor.model.CompositingSpec ks = keyed.getOrCreateCompositing();
        ks.keyEnabled = true;
        ks.keyColor = 0xFF00FF00;
        check("an active chroma key routes an image to the shader", keyed.wantsGlExport());
        check("…and that is the KEY, not blend or fx",
                keyed.hasExportKey() && !keyed.wantsExportBlend() && !keyed.hasExportFx());

        TextOverlayItem keyOff = image(BlendModes.NORMAL);
        keyOff.getOrCreateCompositing().keyColor = 0xFF00FF00;   // colour set, switch off
        check("a key that is switched OFF does not route", !keyOff.wantsGlExport());

        TextOverlayItem masked = image(BlendModes.NORMAL);
        masked.getOrCreateCompositing().masks.add(
                new com.fadcam.ui.faditor.model.CompositingSpec.MaskShape());
        check("a MASK alone stays on the canvas path — no z shift for nothing",
                !masked.wantsGlExport());
    }

    static void normalIsNotABlend() {
        // The whole inertness guarantee for existing projects rests on this one: a project that
        // never picked a mode must build the chain it always built.
        check("NORMAL stays on the canvas path", !image(BlendModes.NORMAL).wantsExportBlend());
        TextOverlayItem fresh = new TextOverlayItem("", 0xFFFFFFFF, 0.5f, 0.5f, 0.5f, 0f);
        fresh.setImageUri("file:///dev/null/test.png");
        check("a freshly added image defaults to the canvas path", !fresh.wantsExportBlend());
    }

    static void everyRealModeIsABlend() {
        boolean all = true;
        int seen = 0;
        for (String mode : BlendModes.ALL) {
            if (BlendModes.NORMAL.equals(mode)) continue;
            seen++;
            if (!image(mode).wantsExportBlend()) all = false;
        }
        check("every non-NORMAL mode routes to the shader", all);
        // A new mode added to BlendModes.ALL must not silently skip the export path.
        check("there is more than one real mode to route", seen >= 4);
    }

    static void textIsNeverRouted() {
        // A text overlay has no export blend path. Claiming one here would drop it from the
        // canvas and draw nothing in its place — a blank where the words were.
        TextOverlayItem t = new TextOverlayItem("hello", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        t.setOverlayBlendMode(BlendModes.MULTIPLY);
        check("a text overlay with a blend mode stays on the canvas", !t.wantsExportBlend());
    }

    static void unknownModesDegradeToNormal() {
        // Forward compatibility: a project written by a newer build names a mode this one has
        // never heard of. modeCode falls back to 0, so it must stay on the canvas rather than
        // reach a shader that would render it with whatever mode 0 means.
        check("an unknown mode stays on the canvas", !image("NOT_A_MODE").wantsExportBlend());
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (ok) passed++; else failed++;
    }
}
