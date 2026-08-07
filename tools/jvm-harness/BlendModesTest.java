import com.fadcam.ui.faditor.model.BlendModes;

/**
 * JVM harness for the extracted blend authority.
 *
 * <p>{@code blendPix} used to live inside {@code BlendModeGlEffect}. The FX stack's per-card fold
 * needs the same arithmetic, and a second copy of it is precisely how two renderers come to
 * disagree about what "Screen" means — so it was extracted rather than duplicated. This file
 * pins the Java mirror against the values the GLSL computes, the {@code ChromaKey.keepFactor}
 * pattern.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-fx.sh}</p>
 */
public class BlendModesTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        codesRoundTrip();
        normalIsPassthrough();
        theArithmetic();
        edgeValues();
        theGlslIsSelfContained();

        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    static void codesRoundTrip() {
        boolean all = true;
        for (String name : BlendModes.ALL) {
            if (!name.equals(BlendModes.modeName(BlendModes.modeCode(name)))) all = false;
        }
        check("every mode name survives name→code→name", all);
        check("an unknown name falls back to NORMAL",
                BlendModes.modeCode("NOT_A_MODE") == BlendModes.modeCode(BlendModes.NORMAL));
        check("an out-of-range code falls back to NORMAL",
                BlendModes.NORMAL.equals(BlendModes.modeName(99)));
    }

    static void normalIsPassthrough() {
        float[] b = {0.2f, 0.4f, 0.6f, 1f};
        float[] s = {0.7f, 0.3f, 0.9f, 1f};
        float[] out = BlendModes.blend(b, s, BlendModes.modeCode(BlendModes.NORMAL));
        check("NORMAL returns the source untouched",
                near(out[0], s[0]) && near(out[1], s[1]) && near(out[2], s[2]));
    }

    static void theArithmetic() {
        float[] b = {0.5f, 0.5f, 0.5f, 1f};
        float[] s = {0.4f, 0.4f, 0.4f, 1f};

        float[] mul = BlendModes.blend(b, s, BlendModes.modeCode(BlendModes.MULTIPLY));
        check("MULTIPLY is b*s", near(mul[0], 0.2f));

        float[] scr = BlendModes.blend(b, s, BlendModes.modeCode(BlendModes.SCREEN));
        check("SCREEN is 1-(1-b)(1-s)", near(scr[0], 0.7f));

        float[] add = BlendModes.blend(b, s, BlendModes.modeCode(BlendModes.ADD));
        check("ADD is b+s", near(add[0], 0.9f));

        // Overlay is the one with a branch, so it is checked on BOTH sides of it.
        float[] dark = BlendModes.blend(new float[]{0.25f, 0.25f, 0.25f, 1f}, s,
                BlendModes.modeCode(BlendModes.OVERLAY));
        check("OVERLAY multiplies on the dark side", dark[0] < 0.25f);
        float[] light = BlendModes.blend(new float[]{0.75f, 0.75f, 0.75f, 1f}, s,
                BlendModes.modeCode(BlendModes.OVERLAY));
        check("OVERLAY screens on the light side", light[0] > 0.5f);
    }

    static void edgeValues() {
        float[] black = {0f, 0f, 0f, 1f};
        float[] white = {1f, 1f, 1f, 1f};
        check("MULTIPLY by white leaves the base",
                near(BlendModes.blend(new float[]{0.3f, 0.3f, 0.3f, 1f}, white,
                        BlendModes.modeCode(BlendModes.MULTIPLY))[0], 0.3f));
        check("SCREEN with black leaves the base",
                near(BlendModes.blend(new float[]{0.3f, 0.3f, 0.3f, 1f}, black,
                        BlendModes.modeCode(BlendModes.SCREEN))[0], 0.3f));
        float[] over = BlendModes.blend(white, white, BlendModes.modeCode(BlendModes.ADD));
        check("ADD stays in range at the top", over[0] <= 1.0001f);
    }

    static void theGlslIsSelfContained() {
        String fn = BlendModes.GLSL_BLEND_FN;
        check("the GLSL constant is non-empty", fn.length() > 32);
        check("...declares blendPix", fn.contains("vec3 blendPix(vec3 b, vec3 s)"));
        // The FX compiler needs the mode as an ARGUMENT: one fused pass folds several cards with
        // different blend modes, so a single uBlendMode uniform cannot serve them.
        String param = BlendModes.glslBlendFnWithModeParam();
        check("...and a mode-as-argument variant exists for the FX fold",
                param.contains("vec3 blendPix(vec3 b, vec3 s, float uBlendMode)"));
        check("...which is DERIVED, not a second copy of the equations",
                param.contains("1.0 - (1.0 - b) * (1.0 - s)")
                        && param.substring(param.indexOf('{')).equals(
                                fn.substring(fn.indexOf('{'))));
        check("...and declares no uniform of its own — it is CONCATENATED, not compiled alone",
                !fn.contains("uniform "));
        check("...and carries no preprocessor directive, so AGSL can take it too",
                !fn.contains("#"));
    }

    static boolean near(float a, float b) { return Math.abs(a - b) <= 0.002f; }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
