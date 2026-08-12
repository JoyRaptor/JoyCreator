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
