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
        theNewModes();
        theFullStandardSet();
        theNonSeparableTrio();
        theGrouping();
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

    /**
     * DIFFERENCE and COLOR (2026-09-04). Both are pinned on their DEFINING identities rather than
     * on numbers copied out of the implementation, which would only prove the code equals itself.
     */
    static void theNewModes() {
        int diff = BlendModes.modeCode(BlendModes.DIFFERENCE);
        int col = BlendModes.modeCode(BlendModes.COLOR);
        check("DIFFERENCE and COLOR are their own bands, not ADD's fall-through",
                diff == 5 && col == 6);

        float[] b = {0.8f, 0.3f, 0.5f, 1f};
        float[] s = {0.2f, 0.9f, 0.5f, 1f};
        float[] d = BlendModes.blend(b, s, diff);
        check("DIFFERENCE is |b - s|",
                near(d[0], 0.6f) && near(d[1], 0.6f) && near(d[2], 0f));
        float[] dBlack = BlendModes.blend(b, new float[]{0f, 0f, 0f, 1f}, diff);
        check("DIFFERENCE with black leaves the base",
                near(dBlack[0], b[0]) && near(dBlack[1], b[1]) && near(dBlack[2], b[2]));
        float[] dSelf = BlendModes.blend(b, b, diff);
        check("DIFFERENCE of a layer with itself is black",
                near(dSelf[0], 0f) && near(dSelf[1], 0f) && near(dSelf[2], 0f));

        // COLOR's whole definition: the result carries the BACKDROP's luminosity. That is the one
        // property worth pinning -- get it wrong and the mode is just a tinted mess.
        float[] cb = {0.15f, 0.15f, 0.15f, 1f};      // a dark, neutral backdrop
        float[] cs = {0.9f, 0.2f, 0.1f, 1f};         // a bright, saturated source
        float[] c = BlendModes.blend(cb, cs, col);
        check("COLOR keeps the BACKDROP's luminosity", near(lum(c), lum(cb)));
        check("...and takes the SOURCE's hue (red still dominates)",
                c[0] > c[1] && c[0] > c[2]);
        float[] grey = BlendModes.blend(cb, new float[]{0.5f, 0.5f, 0.5f, 1f}, col);
        check("a GREY source paints no colour: the backdrop comes back",
                near(grey[0], grey[1]) && near(grey[1], grey[2]) && near(lum(grey), lum(cb)));
        // A bright source over a bright backdrop is where naive setLum clips a channel past 1 and
        // silently shifts the hue; ClipColor is what stops it.
        float[] hot = BlendModes.blend(new float[]{0.9f, 0.9f, 0.9f, 1f},
                new float[]{1f, 0f, 0f, 1f}, col);
        check("COLOR stays inside 0..1 where naive setLum would clip",
                hot[0] <= 1.0001f && hot[1] >= -0.0001f && hot[2] >= -0.0001f);
    }

    static float lum(float[] c) { return 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]; }

    /**
     * The rest of the Photoshop/W3C separable set (2026-09-04). Every mode is pinned on its
     * DEFINING identity — the thing that would still be true if someone rewrote the shader — not
     * on numbers read back out of the implementation, which would only prove the code equals
     * itself.
     */
    static void theFullStandardSet() {
        float[] b = {0.8f, 0.3f, 0.5f, 1f};
        float[] s = {0.2f, 0.9f, 0.5f, 1f};
        float[] black = {0f, 0f, 0f, 1f};
        float[] white = {1f, 1f, 1f, 1f};
        float[] grey = {0.5f, 0.5f, 0.5f, 1f};

        float[] dk = bl(b, s, BlendModes.DARKEN);
        check("DARKEN keeps the darker channel",
                near(dk[0], 0.2f) && near(dk[1], 0.3f) && near(dk[2], 0.5f));
        float[] lt = bl(b, s, BlendModes.LIGHTEN);
        check("LIGHTEN keeps the lighter channel",
                near(lt[0], 0.8f) && near(lt[1], 0.9f) && near(lt[2], 0.5f));

        // Colour dodge/burn: the zero and one cases are where a naive division blows up.
        check("COLOR DODGE with a black source leaves the base",
                near(bl(b, black, BlendModes.COLOR_DODGE)[0], b[0]));
        check("COLOR DODGE of a black BASE stays black (no divide-by-zero white)",
                near(bl(black, s, BlendModes.COLOR_DODGE)[0], 0f));
        check("COLOR DODGE with a white source blows out to 1",
                near(bl(new float[]{0.4f, 0.4f, 0.4f, 1f}, white, BlendModes.COLOR_DODGE)[0], 1f));
        check("COLOR BURN with a white source leaves the base",
                near(bl(b, white, BlendModes.COLOR_BURN)[0], b[0]));
        check("COLOR BURN with a black source crushes to 0",
                near(bl(b, black, BlendModes.COLOR_BURN)[0], 0f));
        check("COLOR BURN of a white BASE stays white",
                near(bl(white, s, BlendModes.COLOR_BURN)[0], 1f));

        check("LINEAR BURN is b + s - 1, floored at 0",
                near(bl(b, s, BlendModes.LINEAR_BURN)[0], 0f)
                        && near(bl(b, s, BlendModes.LINEAR_BURN)[1], 0.2f));

        // Hard Light is Overlay with the operands swapped — the cheapest possible proof it is not
        // just a second copy of Overlay.
        float[] hl = bl(b, s, BlendModes.HARD_LIGHT);
        float[] ovSwapped = bl(s, b, BlendModes.OVERLAY);
        check("HARD LIGHT equals OVERLAY with base and source swapped",
                near(hl[0], ovSwapped[0]) && near(hl[1], ovSwapped[1]) && near(hl[2], ovSwapped[2]));

        // A 50% grey source is the fixed point of every "light" mode: it must change nothing.
        check("SOFT LIGHT with a 50% grey source leaves the base",
                near(bl(b, grey, BlendModes.SOFT_LIGHT)[0], b[0]));
        check("VIVID LIGHT with a 50% grey source leaves the base",
                near(bl(b, grey, BlendModes.VIVID_LIGHT)[0], b[0]));
        check("LINEAR LIGHT with a 50% grey source leaves the base",
                near(bl(b, grey, BlendModes.LINEAR_LIGHT)[0], b[0]));
        check("PIN LIGHT with a 50% grey source leaves the base",
                near(bl(b, grey, BlendModes.PIN_LIGHT)[0], b[0]));
        check("SOFT LIGHT darkens below 50% and lightens above it",
                bl(b, new float[]{0.2f, 0.2f, 0.2f, 1f}, BlendModes.SOFT_LIGHT)[0] < b[0]
                        && bl(b, new float[]{0.8f, 0.8f, 0.8f, 1f},
                                BlendModes.SOFT_LIGHT)[0] > b[0]);

        // Hard Mix is Vivid Light thresholded — so it may only ever emit 0 or 1.
        float[] hm = bl(b, s, BlendModes.HARD_MIX);
        check("HARD MIX emits only 0 or 1",
                (near(hm[0], 0f) || near(hm[0], 1f))
                        && (near(hm[1], 0f) || near(hm[1], 1f))
                        && (near(hm[2], 0f) || near(hm[2], 1f)));
        check("HARD MIX flips at b + s == 1",
                near(bl(new float[]{0.4f, 0f, 0f, 1f}, new float[]{0.5f, 0f, 0f, 1f},
                        BlendModes.HARD_MIX)[0], 0f)
                        && near(bl(new float[]{0.6f, 0f, 0f, 1f}, new float[]{0.5f, 0f, 0f, 1f},
                                BlendModes.HARD_MIX)[0], 1f));

        check("EXCLUSION with black leaves the base",
                near(bl(b, black, BlendModes.EXCLUSION)[0], b[0]));
        check("EXCLUSION with white inverts the base",
                near(bl(b, white, BlendModes.EXCLUSION)[0], 1f - b[0]));
        check("EXCLUSION of 0.5 with 0.5 is 0.5",
                near(bl(grey, grey, BlendModes.EXCLUSION)[0], 0.5f));

        check("SUBTRACT is b - s, floored at 0",
                near(bl(b, s, BlendModes.SUBTRACT)[0], 0.6f)
                        && near(bl(b, s, BlendModes.SUBTRACT)[1], 0f));
        check("DIVIDE with a white source leaves the base",
                near(bl(b, white, BlendModes.DIVIDE)[0], b[0]));
        check("DIVIDE by black clamps to 1 rather than exploding",
                near(bl(b, black, BlendModes.DIVIDE)[0], 1f));

        // Darker/Lighter Color are WHOLE-PIXEL, which is the only thing separating them from
        // Darken/Lighten: they must return one of the two inputs intact, never a mix.
        float[] dc = bl(b, s, BlendModes.DARKER_COLOR);
        boolean isB = near(dc[0], b[0]) && near(dc[1], b[1]) && near(dc[2], b[2]);
        boolean isS = near(dc[0], s[0]) && near(dc[1], s[1]) && near(dc[2], s[2]);
        check("DARKER COLOR returns one whole pixel, not a per-channel mix", isB || isS);
        check("...and it is the darker of the two", lum(dc) <= Math.min(lum(b), lum(s)) + 0.002f);
        float[] lc = bl(b, s, BlendModes.LIGHTER_COLOR);
        check("LIGHTER COLOR picks the brighter whole pixel",
                lum(lc) >= Math.max(lum(b), lum(s)) - 0.002f);
        check("DARKER COLOR is NOT per-channel Darken (they must differ here)",
                !(near(dc[0], 0.2f) && near(dc[1], 0.3f)));

        // ADD is Photoshop's "Linear Dodge (Add)". There must be exactly ONE wire value for it.
        check("there is no separate LINEAR_DODGE mode — it is ADD",
                BlendModes.modeCode("LINEAR_DODGE") == 0
                        && !contains(BlendModes.ALL, "LINEAR_DODGE"));
        check("DISSOLVE is deliberately absent (it sizzles frame to frame)",
                !contains(BlendModes.ALL, "DISSOLVE"));
    }

    /**
     * HUE / SATURATION / LUMINOSITY. These reuse COLOR's SetLum + ClipColor tail, so what is worth
     * pinning is which quantity each one takes from which layer.
     */
    static void theNonSeparableTrio() {
        float[] b = {0.2f, 0.5f, 0.8f, 1f};      // a muted blue backdrop
        float[] s = {0.9f, 0.2f, 0.1f, 1f};      // a hot red source

        float[] h = bl(b, s, BlendModes.HUE);
        check("HUE keeps the BACKDROP's luminosity", near(lum(h), lum(b)));
        check("...and takes the SOURCE's hue (red now leads)", h[0] > h[2]);

        float[] sat = bl(b, s, BlendModes.SATURATION);
        check("SATURATION keeps the BACKDROP's luminosity", near(lum(sat), lum(b)));
        check("...and keeps the BACKDROP's hue (still blue-led)", sat[2] > sat[0]);
        float[] flat = bl(b, new float[]{0.4f, 0.4f, 0.4f, 1f}, BlendModes.SATURATION);
        check("...a GREY source has zero saturation, so the result goes grey",
                near(flat[0], flat[1]) && near(flat[1], flat[2]));

        float[] l = bl(b, s, BlendModes.LUMINOSITY);
        check("LUMINOSITY takes the SOURCE's luminosity", near(lum(l), lum(s)));
        check("...and keeps the BACKDROP's colour (still blue-led)", l[2] > l[0]);
        // COLOR and LUMINOSITY are duals: swapping the operands of one gives the other.
        float[] dual = bl(s, b, BlendModes.COLOR);
        check("LUMINOSITY is COLOR with the layers swapped",
                near(l[0], dual[0]) && near(l[1], dual[1]) && near(l[2], dual[2]));

        boolean sane = true;
        String bad = "";
        for (String m : BlendModes.ALL) {
            float[] o = bl(new float[]{0.97f, 0.05f, 0.62f, 1f},
                    new float[]{0.02f, 0.99f, 0.41f, 1f}, m);
            for (int i = 0; i < 3; i++) {
                if (Float.isNaN(o[i]) || Float.isInfinite(o[i])
                        || o[i] < -0.001f || o[i] > 1.001f) { sane = false; bad = m; }
            }
        }
        check("every mode stays finite and inside 0..1 on an extreme pair"
                + (sane ? "" : " — " + bad + " does not"), sane);
    }

    /**
     * The picker's grouping lives in BlendModes so it can be checked HERE. A mode that ships in
     * the shader but is missing from the grouping is a mode the user cannot reach, which is
     * indistinguishable from not having built it.
     */
    static void theGrouping() {
        int n = 0;
        boolean dupes = false;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] g : BlendModes.GROUPED) {
            for (String m : g) { n++; if (!seen.add(m)) dupes = true; }
        }
        check("the picker's groups cover EVERY mode exactly once, and nothing else",
                n == BlendModes.ALL.length && !dupes && seen.size() == BlendModes.ALL.length);
        boolean allKnown = true;
        for (String m : seen) if (!contains(BlendModes.ALL, m)) allKnown = false;
        check("...and every grouped entry is a real mode", allKnown);
        check("there is one heading per group",
                BlendModes.GROUP_KEYS.length == BlendModes.GROUPED.length);
    }

    static boolean contains(String[] a, String v) {
        for (String x : a) if (x.equals(v)) return true;
        return false;
    }

    /** blend() by NAME — a test reads as the mode it is about, not as a magic integer. */
    static float[] bl(float[] b, float[] s, String mode) {
        return BlendModes.blend(b, s, BlendModes.modeCode(mode));
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
        // Every mode must have its OWN band. ADD was the fall-through once; a new mode appended
        // after a fall-through renders as that mode instead, which looks implemented and is not.
        boolean everyBand = true;
        for (int code = 1; code < BlendModes.ALL.length; code++) {
            // Each mode is selected by a "< code + 0.5" guard. A missing one means that mode falls
            // through into its neighbour: it looks implemented and renders as something else.
            if (!fn.contains("uBlendMode < " + code + ".5")) everyBand = false;
        }
        check("...and every mode code has its own guarded band", everyBand);
        check("...and the LAST band is not a fall-through: unknown codes come back as NORMAL",
                fn.contains("uBlendMode < " + (BlendModes.ALL.length - 1) + ".5")
                        && fn.trim().endsWith("return s;\n}"));
        // The COLOR branch declares locals; they must survive the mode-as-argument rewrite, which
        // keeps everything from the first brace onward.
        check("...and the COLOR branch survives into the FX-fold variant",
                param.contains("vec3 lw = vec3(0.3, 0.59, 0.11)"));
    }

    static boolean near(float a, float b) { return Math.abs(a - b) <= 0.002f; }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
