import com.fadcam.ui.faditor.model.ChromaKey;
import com.fadcam.ui.faditor.model.CompositingSpec;

/**
 * §3a chroma key — the arithmetic the shader runs, pinned off device.
 *
 * <p>GLSL cannot run in the JVM, so {@link ChromaKey#keepFactor} is the harness's only reach
 * into the key. Every check here is written against a property the SHADER must have, not
 * against whatever the Java happens to return, so the mirror cannot be "fixed" by editing the
 * expectation to match a bug.</p>
 */
public class ChromaKeyTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static final float[] GREEN = {0f, 1f, 0f};
    static final float[] WHITE = {1f, 1f, 1f};
    static final float[] SKIN  = {0.85f, 0.68f, 0.55f};

    static float keep(float[] px, float tol, float fuzz, float off) {
        return ChromaKey.keepFactor(px, GREEN, tol, fuzz, off);
    }

    public static void main(String[] a) {

        // ── 1. The disabled path is EXACTLY identity, not approximately ──────────────────
        // Every project ever saved has keyEnabled=false. If "off" cost even a rounding error
        // the key would silently regrade every existing export.
        CompositingSpec off = new CompositingSpec();
        float[] p = ChromaKey.packParams(off);
        check(p[0] == 0f && p[1] == 0f && p[2] == 0f && p[3] == 0f,
                "a spec with the key off packs all-zero params (shader short-circuits)");
        check(!ChromaKey.isActive(off), "key off is not active");
        check(!ChromaKey.isActive(null), "a null spec is not active (no spec == no key)");

        // ── 2. The key actually keys, and keeps what it should ──────────────────────────
        check(keep(GREEN, 0.2f, 0.1f, 0f) == 0f,
                "the key colour itself is fully removed");
        check(keep(WHITE, 0.2f, 0.1f, 0f) == 1f,
                "white is fully kept against a green key");
        check(keep(SKIN, 0.2f, 0.1f, 0f) == 1f,
                "skin tone is fully kept — the whole point of a green screen");

        // ── 3. The soft band is a RAMP, not a second hard edge ──────────────────────────
        // A band that jumps 0→1 would alias every keyed edge; this is what fuzziness buys.
        float[] nearGreen = {0.18f, 0.92f, 0.18f};
        float mid = keep(nearGreen, 0.10f, 0.40f, 0f);
        check(mid > 0f && mid < 1f, "a colour inside the soft band is PARTIALLY kept");
        float prev = -1f;
        boolean monotone = true;
        for (int i = 0; i <= 20; i++) {
            float t = i / 20f;
            float[] px = {t, 1f - t * 0.5f, t};          // sweeps away from green
            float k = keep(px, 0.10f, 0.50f, 0f);
            if (k < prev - 1e-6f) monotone = false;
            prev = k;
        }
        check(monotone, "keep rises monotonically as a colour moves away from the key");

        // ── 4. Zero fuzziness is a HARD edge, and must not divide by zero ───────────────
        // smoothstep(e,e,x) is undefined in GLSL; EDGE_EPSILON is what stops it. If that
        // guard is ever removed this check goes NaN rather than merely wrong.
        float hardIn = keep(GREEN, 0.30f, 0f, 0f);
        float[] justOutside = {0f, 0.60f, 0f};           // distance 0.40 > tolerance 0.30
        float hardOut = keep(justOutside, 0.30f, 0f, 0f);
        check(hardIn == 0f && hardOut == 1f,
                "fuzziness 0 gives a hard edge, both sides, with no NaN");
        check(!Float.isNaN(hardIn) && !Float.isNaN(hardOut),
                "the degenerate smoothstep pair never produces NaN");

        // ── 5. The offset rails, both of which are reachable from the slider ────────────
        check(keep(GREEN, 0.2f, 0.1f, 1f) == 1f,
                "offset +1 keeps everything — the key becomes a no-op");
        check(keep(WHITE, 0.2f, 0.1f, -1f) == 0f,
                "offset -1 removes everything, including what the key would have kept");
        check(keep(GREEN, 0.2f, 0.1f, 0.5f) == 0.5f,
                "a partial offset lifts the removed region by exactly that much (spill spread)");
        // The two cases that must go PAST the rail, not land on it. Written this way because
        // the first draft of this block did NOT discriminate: every case chosen (keep=0 with
        // offset +1, keep=1 with offset -1) sums to exactly 0 or 1, so deleting the clamp
        // entirely still passed all three. Alpha outside 0..1 is not a rounding matter — it
        // is a black halo or an invisible subject in the composite.
        check(keep(WHITE, 0.2f, 0.1f, 0.5f) == 1f,
                "an already-kept pixel plus a positive offset CLAMPS at 1 (would be 1.5)");
        check(keep(GREEN, 0.2f, 0.1f, -0.5f) == 0f,
                "an already-removed pixel plus a negative offset CLAMPS at 0 (would be -0.5)");
        check(keep(WHITE, 0.2f, 0.1f, 1f) == 1f,
                "both rails at once still clamps (would be 2.0)");

        // ── 6. offset +1 is NOT worth lighting up the GL tier ───────────────────────────
        // isActive exists so an invisible setting cannot cost a whole render path.
        CompositingSpec railed = new CompositingSpec();
        railed.keyEnabled = true;
        railed.keyOffset = 1f;
        check(!ChromaKey.isActive(railed),
                "keyEnabled with offset at +1 is inactive — it cannot change a pixel");
        railed.keyOffset = 0.99f;
        check(ChromaKey.isActive(railed), "just below the rail it IS active");

        // ── 7. THE PREMULTIPLICATION TRAP ───────────────────────────────────────────────
        // This is the classic chroma-key bug and the reason both call sites un-premultiply.
        // A half-transparent green pixel and an opaque one are the SAME colour, so the key
        // must treat them identically. Measured on straight colour, it does. (If a caller
        // ever passes premultiplied rgb, the half-transparent one reads as dark green,
        // lands far from the key and SURVIVES — keying would eat solid green and keep the
        // translucent fringe, which is exactly the "noisy edges" failure.)
        float[] straightHalf = {0f, 1f, 0f};
        float[] premultHalf  = {0f, 0.5f, 0f};
        check(keep(straightHalf, 0.2f, 0.1f, 0f) == keep(GREEN, 0.2f, 0.1f, 0f),
                "a translucent green keys the same as an opaque green (straight colour)");
        check(keep(premultHalf, 0.2f, 0.1f, 0f) > 0f,
                "CONTROL: the same pixel premultiplied would survive — so the trap is real "
                        + "and this test can tell the two apart");

        // ── 8. Packing clamps at the boundary, so the shader never sees nonsense ────────
        CompositingSpec wild = new CompositingSpec();
        wild.keyEnabled = true;
        wild.keyTolerance = 5f;
        wild.keyFuzziness = -3f;
        wild.keyOffset = 9f;
        float[] wp = ChromaKey.packParams(wild);
        check(wp[1] == 1f && wp[2] == 0f && wp[3] == 1f,
                "out-of-range tolerance/fuzziness/offset clamp on the way to the uniform");
        wild.keyOffset = -9f;
        check(ChromaKey.packParams(wild)[3] == -1f, "offset clamps at the negative rail too");
        wild.keyTolerance = Float.NaN;
        check(ChromaKey.packParams(wild)[1] == 0f, "a NaN tolerance packs as 0, not as NaN");

        // ── 9. Colour packing — channel order, and alpha ignored by design ──────────────
        CompositingSpec col = new CompositingSpec();
        col.keyColor = 0x3366CC;
        float[] c = ChromaKey.packColor(col);
        check(Math.abs(c[0] - 0x33 / 255f) < 1e-6
                        && Math.abs(c[1] - 0x66 / 255f) < 1e-6
                        && Math.abs(c[2] - 0xCC / 255f) < 1e-6,
                "keyColor unpacks R,G,B in that order, normalised");
        col.keyColor = 0xFF00FF00;                        // alpha byte set
        float[] withAlpha = ChromaKey.packColor(col);
        check(withAlpha[0] == 0f && withAlpha[1] == 1f && withAlpha[2] == 0f,
                "a set alpha byte does not leak into the RGB the shader compares against");
        check(ChromaKey.packColor(null)[1] == 1f,
                "a null spec still yields a usable (green) colour rather than black");

        // ── 10. The default spec is a USABLE green screen out of the box ───────────────
        // The panel ships with these defaults; if they keyed nothing the first tap would
        // look broken and the user would conclude the feature does not work.
        CompositingSpec def = new CompositingSpec();
        def.keyEnabled = true;
        float[] dp = ChromaKey.packParams(def);
        float[] dc = ChromaKey.packColor(def);
        check(ChromaKey.keepFactor(GREEN, dc, dp[1], dp[2], dp[3]) == 0f,
                "DEFAULTS: pure green is removed with no slider touched");
        check(ChromaKey.keepFactor(SKIN, dc, dp[1], dp[2], dp[3]) == 1f,
                "DEFAULTS: skin survives with no slider touched");

        // ── 11. smoothstep is GLSL's, including its clamp ───────────────────────────────
        check(ChromaKey.smoothstep(0f, 1f, -5f) == 0f, "smoothstep clamps below edge0");
        check(ChromaKey.smoothstep(0f, 1f, 5f) == 1f, "smoothstep clamps above edge1");
        check(Math.abs(ChromaKey.smoothstep(0f, 1f, 0.5f) - 0.5f) < 1e-6,
                "smoothstep is symmetric about the midpoint (Hermite, not linear)");
        check(ChromaKey.smoothstep(0f, 1f, 0.25f) < 0.25f,
                "smoothstep eases IN — it is not a straight ramp");

        // ── 12. The shader really is built from this constant ──────────────────────────
        // The whole point of ChromaKey is that there is one key. If someone hard-codes the
        // epsilon back into the GLSL, or renames the function the export shader calls, this
        // is what notices — the mirror agreeing with itself would not.
        check(ChromaKey.GLSL_KEY_FN.contains(String.valueOf(ChromaKey.EDGE_EPSILON)),
                "the GLSL carries the SAME epsilon constant the Java mirror uses");
        check(ChromaKey.GLSL_KEY_FN.contains("fadKeyAlpha"),
                "the shared function keeps the name both renderers call");
        check(ChromaKey.GLSL_KEY_FN.contains("params.x < 0.5"),
                "the enabled short-circuit lives INSIDE the shared function");
        check(ChromaKey.GLSL_KEY_FN.contains("clamp("),
                "the shader clamps the offset result, matching the mirror");

        // ── 13. Purity — the same input twice is the same answer ────────────────────────
        check(keep(nearGreen, 0.1f, 0.4f, 0f) == keep(nearGreen, 0.1f, 0.4f, 0f),
                "keepFactor is pure (preview and export sample it independently)");

        System.out.println(fails == 0 ? "ALL GREEN" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
