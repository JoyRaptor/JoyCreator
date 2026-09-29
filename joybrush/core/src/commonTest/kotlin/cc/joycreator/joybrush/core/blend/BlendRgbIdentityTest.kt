package cc.joycreator.joybrush.core.blend

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The SECOND opinion on [BlendRgb], and the one that does not depend on the golden table being
 * right.
 *
 * [BlendParityTest] answers "does this transcription match what the Studio produced?". It cannot
 * answer "are these the right equations?" — if the table and the code were both wrong the same way
 * it would be green and the drawing would still be wrong. That is not hypothetical: blend modes
 * are exactly the code where a plausible-looking wrong answer passes a self-written test.
 *
 * So every expectation in this file is derived from the DEFINITION of a mode — the W3C Compositing
 * and Blending definition, or algebra on the Studio's own equations — and never from the table.
 * Four kinds of check:
 *
 *  1. ALGEBRA between modes: OVERLAY(b, s) == HARD_LIGHT(s, b); HARD_MIX is the half-threshold of
 *     VIVID_LIGHT. A transcription that swaps an operand or moves a `<` by one ulp breaks these.
 *  2. INVARIANTS of the non-separable modes: luminosity and saturation are what HUE, SATURATION,
 *     COLOR and LUMINOSITY are DEFINED to preserve. Those are the properties a colour picker shows
 *     a person, so if they do not hold the mode is not that mode.
 *  3. BRANCH REACHABILITY: SetSat's degenerate arm (a colour with no span), black and white's
 *     neutrality, and DARKER/LIGHTER_COLOR's tie behaviour. Branches that are never wrong by
 *     reading and always wrong by writing.
 *  4. STRUCTURE: the separable modes must be separable, so a channel computed on its own equals
 *     the same channel of the whole-colour call. This is what catches a separable mode that has
 *     quietly become non-separable, which is the usual way a mode gets "added".
 */
class BlendRgbIdentityTest {

    private val out = FloatArray(3)
    private val scratch = FloatArray(3)
    private val b = FloatArray(3)
    private val s = FloatArray(3)
    private val r = FloatArray(3)

    private fun code(name: String) = BlendRgb.studioCodeOf(name)

    /** Run a mode on two GREY values; every channel of the answer is the same. */
    private fun run(name: String, bv: Float, sv: Float): FloatArray {
        b[0] = bv; b[1] = bv; b[2] = bv
        s[0] = sv; s[1] = sv; s[2] = sv
        BlendRgb.blendRgb(code(name), b, s, r, scratch)
        return r.copyOf()
    }

    private fun run3(name: String, bv: FloatArray, sv: FloatArray): FloatArray {
        b[0] = bv[0]; b[1] = bv[1]; b[2] = bv[2]
        s[0] = sv[0]; s[1] = sv[1]; s[2] = sv[2]
        BlendRgb.blendRgb(code(name), b, s, r, scratch)
        return r.copyOf()
    }

    // ── 1. algebra between modes ─────────────────────────────────────────────────

    @Test
    fun overlayIsHardLightWithTheTwoRolesSwapped() {
        // Both are 2bs below 0.5 and 1 - 2(1-b)(1-s) above it. The ONLY difference is which
        // operand is compared with 0.5. Getting that backwards renders one mode as the other for
        // every pixel, and no screenshot catches it.
        forEachPair { bv, sv ->
            val o = run("OVERLAY", bv, sv)
            val h = run("HARD_LIGHT", sv, bv)
            for (ch in 0..2) assertClose(o[ch], h[ch], 1e-6f, "OVERLAY(b=$bv,s=$sv) vs HARD_LIGHT(s=$bv,b=$sv)")
        }
    }

    @Test
    fun hardMixIsTheThresholdOfTheSum() {
        forEachPair { bv, sv ->
            val want = if (bv + sv >= 1f) 1f else 0f
            for (ch in 0..2) {
                assertEquals(want, run("HARD_MIX", bv, sv)[ch], 0f, "HARD_MIX at b=$bv s=$sv")
            }
        }
    }

    /**
     * The Studio's own note ("HARD MIX is Vivid Light thresholded at 0.5") as a closed form, and it
     * holds for b, s in 0..1: for s <= 0.5 vivid is `1 - (1-b)/(2s)`, which reaches 0.5 exactly
     * when `b <= 1 - s`; for s > 0.5 vivid is `b/(2-2s)`, which reaches 0.5 exactly when
     * `b >= 1 - s`. Both arms are `b + s >= 1`.
     *
     * TWO THINGS ARE EXCLUDED, and both are about the STUDIO's arithmetic rather than about the
     * algebra being awkward:
     *
     *  - the point (b = 0, s = 1), where the two modes genuinely disagree. See
     *    [hardMixAndVividLightDisagreeAtOneDegenerateEndpoint].
     *  - any value within [BOUNDARY_BAND] of 0.5, where the threshold lands on the wrong side of a
     *    float32 rounding. At b = 0.1, s = 0.9 the true quotient is 0.5 exactly in real arithmetic,
     *    but `2 - 2*0.9f` evaluates to 0.20000005f rather than to `2 * 0.1f`, so the division gives
     *    0.49999988 — a hair under the threshold — while HARD MIX's `0.1 + 0.9 >= 1` is exactly
     *    true. The band is 8 ulp wide, which is what a half-ulp in each of the three operations
     *    involved can reach; a wrong branch puts the answer a whole 0 or 1 away, not a hair away.
     *
     * Restricted to 0..1 ON PURPOSE: with s below 0 the `max(2s, 1e-5)` floor turns vivid into
     * `1 - (1-b)/1e-5` and the two modes genuinely stop agreeing — a property of the equations, not
     * a bug, and not something this test should report as one.
     */
    @Test
    fun hardMixIsVividLightThresholdedAtAHalf() {
        forEachInRangePair { bv, sv ->
            if (bv == 0f && sv == 1f) return@forEachInRangePair
            val v = run("VIVID_LIGHT", bv, sv)
            val hard = run("HARD_MIX", bv, sv)
            for (ch in 0..2) {
                if (abs(v[ch] - 0.5f) <= BOUNDARY_BAND) continue   // float32 rounding, not a branch
                assertEquals(
                    if (v[ch] >= 0.5f) 1f else 0f, hard[ch], 0f,
                    "VIVID_LIGHT b=$bv s=$sv gives ${v[ch]} but HARD_MIX gives ${hard[ch]}",
                )
            }
        }
    }

    /** And the boundary case itself, named, so the band above is not hiding a hole. */
    @Test
    fun vividLightLandsJustUnderAHalfWhereHardMixLandsJustOverIt() {
        // b = 0.1, s = 0.9: b + s == 1 exactly, so HARD MIX is 1. Vivid's dodge divides by
        // 2 - 2*0.9f = 0.20000005f, which is NOT 2*0.1f, so the quotient is 0.49999988.
        val v = run("VIVID_LIGHT", 0.1f, 0.9f)[0]
        assertTrue(v < 0.5f && abs(v - 0.5f) <= BOUNDARY_BAND, "expected a hair under 0.5, got $v")
        assertEquals(1f, run("HARD_MIX", 0.1f, 0.9f)[0], 0f, "0.1 + 0.9 >= 1")
    }

    /**
     * A FINDING about the Studio, pinned so it cannot change by accident.
     *
     * `BlendModes`' comment says HARD MIX "is Vivid Light thresholded at 0.5, and that threshold
     * reduces algebraically to b + s >= 1 on BOTH halves of vivid's branch". The reduction is
     * correct for every s < 1, but the dodge half divides by `max(2 - 2s, 1e-5)`, and at s == 1
     * exactly that denominator is the epsilon rather than anything derived from b. So at
     * (b = 0, s = 1):
     *
     *   VIVID's dodge = min(b / 1e-5, 1) = min(0 / 1e-5, 1) = 0        -> below the threshold
     *   HARD MIX      = (b + s >= 1)     = (0 + 1 >= 1)       = 1
     *
     * A one-pixel difference at a single corner of the cube, and the two modes are both the
     * Studio's. It is recorded here rather than "corrected": correcting it would make Joy Brush
     * stop matching the Studio at exactly the point R23 says the Studio is the authority. The Lead
     * may want to fix `BlendModes.java` — that is a Studio change and out of this task's owner area.
     */
    @Test
    fun hardMixAndVividLightDisagreeAtOneDegenerateEndpoint() {
        assertEquals(0f, run("VIVID_LIGHT", 0f, 1f)[0], "the dodge half at s == 1 is b / 1e-5")
        assertEquals(1f, run("HARD_MIX", 0f, 1f)[0], "0 + 1 >= 1")
        // One to either side of the endpoint they agree again, so this really is only the corner.
        assertEquals(run("HARD_MIX", 0f, 0.999f)[0], if (run("VIVID_LIGHT", 0f, 0.999f)[0] >= 0.5f) 1f else 0f, 0f)
        assertEquals(run("HARD_MIX", 0.001f, 1f)[0], if (run("VIVID_LIGHT", 0.001f, 1f)[0] >= 0.5f) 1f else 0f, 0f)
    }

    @Test
    fun everySeparableModeMatchesItsClosedForm() {
        forEachPair { bv, sv ->
            for (ch in 0..2) {
                assertClose(run("NORMAL", bv, sv)[ch], sv, "NORMAL at $bv,$sv")
                assertClose(run("MULTIPLY", bv, sv)[ch], bv * sv, "MULTIPLY at $bv,$sv")
                assertClose(run("SCREEN", bv, sv)[ch], 1f - (1f - bv) * (1f - sv), "SCREEN at $bv,$sv")
                assertClose(run("DIFFERENCE", bv, sv)[ch], abs(bv - sv), "DIFFERENCE at $bv,$sv")
                assertClose(run("ADD", bv, sv)[ch], min(bv + sv, 1f), "ADD at $bv,$sv")
                assertClose(run("DARKEN", bv, sv)[ch], min(bv, sv), "DARKEN at $bv,$sv")
                assertClose(run("LIGHTEN", bv, sv)[ch], max(bv, sv), "LIGHTEN at $bv,$sv")
                assertClose(run("SUBTRACT", bv, sv)[ch], max(bv - sv, 0f), "SUBTRACT at $bv,$sv")
                assertClose(run("LINEAR_BURN", bv, sv)[ch], max(bv + sv - 1f, 0f), "LINEAR_BURN at $bv,$sv")
                assertClose(run("EXCLUSION", bv, sv)[ch], bv + sv - 2f * bv * sv, "EXCLUSION at $bv,$sv")
                assertClose(
                    run("COLOR_DODGE", bv, sv)[ch], min(bv / max(1f - sv, EPS), 1f),
                    "COLOR_DODGE at $bv,$sv",
                )
                assertClose(
                    run("COLOR_BURN", bv, sv)[ch], 1f - min((1f - bv) / max(sv, EPS), 1f),
                    "COLOR_BURN at $bv,$sv",
                )
                assertClose(run("DIVIDE", bv, sv)[ch], min(bv / max(sv, EPS), 1f), "DIVIDE at $bv,$sv")
                assertClose(
                    run("LINEAR_LIGHT", bv, sv)[ch], max(0f, min(1f, bv + 2f * sv - 1f)),
                    "LINEAR_LIGHT at $bv,$sv",
                )
                assertClose(
                    run("PIN_LIGHT", bv, sv)[ch],
                    if (sv <= 0.5f) min(bv, 2f * sv) else max(bv, 2f * sv - 1f),
                    "PIN_LIGHT at $bv,$sv",
                )
                assertClose(
                    run("HARD_LIGHT", bv, sv)[ch],
                    if (sv < 0.5f) 2f * bv * sv else 1f - 2f * (1f - bv) * (1f - sv),
                    "HARD_LIGHT at $bv,$sv",
                )
                val dd = if (bv <= 0.25f) ((16f * bv - 12f) * bv + 4f) * bv else sqrt(max(bv, 0f))
                assertClose(
                    run("SOFT_LIGHT", bv, sv)[ch],
                    if (sv <= 0.5f) bv - (1f - 2f * sv) * bv * (1f - bv) else bv + (2f * sv - 1f) * (dd - bv),
                    "SOFT_LIGHT at $bv,$sv",
                )
                val burn = 1f - min((1f - bv) / max(2f * sv, EPS), 1f)
                val dodge = min(bv / max(2f - 2f * sv, EPS), 1f)
                assertClose(
                    run("VIVID_LIGHT", bv, sv)[ch], if (sv <= 0.5f) burn else dodge,
                    "VIVID_LIGHT at $bv,$sv",
                )
            }
        }
    }

    // ── 2. the non-separable modes' defining invariants ──────────────────────────

    @Test
    fun hueKeepsTheBackdropsLuminosityAndSaturation() {
        // HUE = SetLum(SetSat(source, Sat(backdrop)), Lum(backdrop))
        eachUnclippedRow("HUE") { bv, sv, got ->
            assertClose(lum(bv), lum(got), 2e-5f, "HUE lost the backdrop's luminosity at $bv/$sv")
            assertClose(sat(bv), sat(got), 2e-5f, "HUE lost the backdrop's saturation at $bv/$sv")
        }
    }

    @Test
    fun saturationKeepsTheBackdropsLuminosityAndTheSourcesSaturation() {
        // SATURATION = SetLum(SetSat(backdrop, Sat(source)), Lum(backdrop))
        eachUnclippedRow("SATURATION") { bv, sv, got ->
            assertClose(lum(bv), lum(got), 2e-5f, "SATURATION lost the backdrop's luminosity at $bv/$sv")
            assertClose(sat(sv), sat(got), 2e-5f, "SATURATION lost the source's saturation at $bv/$sv")
        }
    }

    @Test
    fun colorKeepsTheBackdropsLuminosityAndTheSourcesSaturation() {
        // COLOR = SetLum(source, Lum(backdrop))
        eachUnclippedRow("COLOR") { bv, sv, got ->
            assertClose(lum(bv), lum(got), 2e-5f, "COLOR lost the backdrop's luminosity at $bv/$sv")
            assertClose(sat(sv), sat(got), 2e-5f, "COLOR lost the source's saturation at $bv/$sv")
        }
    }

    @Test
    fun luminosityKeepsTheSourcesLuminosityAndTheBackdropsSaturation() {
        // LUMINOSITY = SetLum(backdrop, Lum(source))
        eachUnclippedRow("LUMINOSITY") { bv, sv, got ->
            assertClose(lum(sv), lum(got), 2e-5f, "LUMINOSITY lost the source's luminosity at $bv/$sv")
            assertClose(sat(bv), sat(got), 2e-5f, "LUMINOSITY lost the backdrop's saturation at $bv/$sv")
        }
    }

    /**
     * Luminosity survives ClipColor too, out of range. ClipColor rescales about the colour's OWN
     * luminosity by a factor k, and scaling every channel by k scales the weighted sum by k — so
     * Lum is preserved even when the rescale is what pulled the result back into 0..1. Algebra
     * above, values from the table below.
     */
    /**
     * What is observable of ClipColor when its rescale has to fire, both derived rather than
     * observed.
     *
     * 1. The answer is always a COLOUR a pixel can hold. ClipColor's last act is `clamp(c, 0, 1)`,
     *    so however far the rescale pushed the channels, what comes back is in the unit box and
     *    finite. (DARKER_COLOR and LIGHTER_COLOR are excluded above all: they are picks, not
     *    arithmetic, so they hand an out-of-range colour back unchanged — which is correct, and is
     *    what makes them the cheapest modes there are.)
     *
     * 2. A rescale puts the offending channel ON the boundary, exactly — but only when it is the
     *    LAST rescale to run. `cn < 0` uses `k = cl / (cl - cn)`, so the channel that was `cn`
     *    becomes `cl + (cn - cl)k = 0`; `cx > 1` uses `k = (1 - cl) / (cx - cl)`, so the channel
     *    that was `cx` becomes `cl + (cx - cl)k = 1`.
     *
     *    "Last" is the whole qualification, and it is easy to miss: `cn` and `cx` are BOTH measured
     *    once at the top, before either rescale, so a colour that is both too dark and too bright
     *    gets both branches — and the second one moves the colour off the edge the first one put it
     *    on. Recomputing `cx` after the low rescale (a natural-looking "cleanup") would change
     *    which rows double-rescale, and the golden table would not notice.
     *
     * TWO STRONGER CLAIMS ARE DELIBERATELY NOT MADE HERE, and the reason is worth writing down,
     * because both look obvious and both are untestable from outside:
     *
     *  - "the rescale preserves luminosity". It does — scaling every channel by k scales the
     *    weighted sum by k, and the offset from `cl` is `k*d`. But the rescale always lands a
     *    channel exactly on 0 or 1, so the final clamp always fires afterwards, and a clamp does
     *    NOT preserve luminosity. (An earlier version of this test asserted the equality and failed
     *    at 1.134 -> 1.0. The mode was right; the claim was about the clamp, not about the mode.)
     *  - "the offset from the result's luminosity is still parallel to the backdrop's". True up to
     *    the clamp, and the clamp has already moved one channel by the time anything can see it.
     *
     * The luminosity invariant itself is asserted where it IS exact, in the four mode tests above,
     * which are gated on the rescale not firing.
     */
    @Test
    fun luminosityStaysAColourAndClipColorLandsItOnTheBoundary() {
        val c = code("LUMINOSITY")
        var lowRescale = 0
        var highRescale = 0
        for (pair in 0 until BlendGolden.PAIR_COUNT) {
            val bv = BlendGolden.BS.copyOfRange(pair * 6, pair * 6 + 3)
            val sv = BlendGolden.BS.copyOfRange(pair * 6 + 3, pair * 6 + 6)
            val got = run3("LUMINOSITY", bv, sv)
            for (ch in 0..2) {
                assertTrue(
                    got[ch] in 0f..1f && !got[ch].isNaN(),
                    "LUMINOSITY at ${bv.toList()}/${sv.toList()} gave ${got.toList()}; " +
                        "ClipColor's clamp is the last word, so this should be a colour",
                )
            }
            val pre = preClipColor(c, bv, sv)
            if (max3Of(pre) > 1f && min3Of(pre) >= 0f) {
                highRescale++
                assertTrue(
                    max3Of(got) >= 1f - BOUNDARY_BAND,
                    "the high rescale was the last to run, so it should have landed a channel on 1: " +
                        "${got.toList()} from ${bv.toList()}/${sv.toList()}",
                )
            }
            if (min3Of(pre) < 0f && max3Of(pre) <= 1f) {
                lowRescale++
                assertTrue(
                    min3Of(got) <= BOUNDARY_BAND,
                    "the low rescale was the last to run, so it should have landed a channel on 0: " +
                        "${got.toList()} from ${bv.toList()}/${sv.toList()}",
                )
            }
        }
        assertTrue(lowRescale > 10, "expected plenty of low rescales to check; saw $lowRescale")
        assertTrue(highRescale > 10, "expected plenty of high rescales to check; saw $highRescale")
    }

    /**
     * The double-rescale case, named and derived. When a colour is BOTH too dark and too bright,
     * ClipColor runs both branches, and the second one moves the colour off the edge — so an
     * implementation that assumes "a rescale lands on the boundary and stops" is wrong here, and
     * only here.
     *
     * backdrop [1.0052972, -1.2236341, -1.1721493], source [0.05759287, 1.2476826, -1.2155753]:
     *   lum(b) = -0.54929, lum(s) = 0.61970, so the pre-clip colour is b + 1.16899
     *          = [2.17429, -0.05465, -0.00316]         cl = 0.61970, cn = -0.05465, cx = 2.17429
     *   low:  k = cl/(cl - cn)        = 0.91893  ->  [2.04801, 0.00000, 0.04720]
     *   high: k = (1 - cl)/(cx - cl)  = 0.24468  ->  [0.96918, 0.46809, 0.47967]
     *
     * The maximum is 0.969, NOT 1: the high rescale is measured against the pre-low-rescale colour
     * but applied to the post-low-rescale one, so the channel it lands is not the one it aimed at.
     * The clamp then does nothing at all. The Studio's recorded value is the same, which is what
     * `BlendParityTest` checks; what is pinned HERE is the property that makes it explicable.
     */
    @Test
    fun clipColorRunsBothRescalesAndTheSecondMovesTheColourOffTheEdge() {
        val bv = floatArrayOf(1.0052972f, -1.2236341f, -1.1721493f)
        val sv = floatArrayOf(0.05759287f, 1.2476826f, -1.2155753f)
        val pre = preClipColor(code("LUMINOSITY"), bv, sv)
        assertTrue(min3Of(pre) < 0f && max3Of(pre) > 1f, "this case is only interesting if both fire")
        val got = run3("LUMINOSITY", bv, sv)
        assertTrue(
            max3Of(got) < 1f - BOUNDARY_BAND,
            "after both rescales the colour is NOT on the high boundary: ${got.toList()}",
        )
        assertTrue(
            min3Of(got) > BOUNDARY_BAND,
            "and not on the low one either: ${got.toList()}",
        )
    }

    // ── 3. the branches a plausible transcription gets wrong ─────────────────────

    @Test
    fun darkerAndLighterColorHandBackOneOfTheTwoColoursByteForByte() {
        // These are whole-colour PICKS, not arithmetic. The result must be bit-identical to the
        // backdrop or to the source; if anything has been done to it, the mode is wrong even when
        // it looks close.
        forEachTriplePair { bv, sv ->
            val darker = run3("DARKER_COLOR", bv, sv)
            val lighter = run3("LIGHTER_COLOR", bv, sv)
            val wantDark = if (lum(sv) < lum(bv)) sv else bv
            val wantLight = if (lum(sv) > lum(bv)) sv else bv
            for (ch in 0..2) {
                assertTrue(
                    darker[ch] == wantDark[ch],
                    "DARKER_COLOR at ${bv.toList()}/${sv.toList()} gave ${darker.toList()}, " +
                        "which is neither colour",
                )
                assertTrue(
                    lighter[ch] == wantLight[ch],
                    "LIGHTER_COLOR at ${bv.toList()}/${sv.toList()} gave ${lighter.toList()}, " +
                        "which is neither colour",
                )
            }
        }
    }

    @Test
    fun aLuminosityTieReturnsTheBackdropInBothColourModes() {
        // The Studio compares with `<` and `>`, so a tie keeps the BACKDROP in both. `<=` and `>=`
        // would keep the source in one of them; the difference is invisible on screen because the
        // two colours have the same luminosity, and visible only in the pixels' other channels.
        for (g in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val bv = floatArrayOf(g, g, g)
            val sv = floatArrayOf(g, g, g)
            assertEquals(bv.toList(), run3("DARKER_COLOR", bv, sv).toList(), "DARKER_COLOR tie at $g")
            assertEquals(bv.toList(), run3("LIGHTER_COLOR", bv, sv).toList(), "LIGHTER_COLOR tie at $g")
        }
    }

    @Test
    fun setSatOnAColourWithNoSpanGivesBlackAndThenTheBackdropsLuminosity() {
        // The degenerate arm of SetSat: a grey colour has no span to rescale, so it contributes no
        // hue, and HUE(grey, s), SATURATION(b, grey) and COLOR(b, grey) all land on
        // SetLum(black, Lum(backdrop)) — a neutral of the backdrop's lightness. Writing
        // `hx > hn` as `hx >= hn` divides by zero here, and nothing else in the suite notices.
        for (g in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            for (other in floatArrayOf(0.1f, 0.4f, 0.9f)) {
                assertClose(run("HUE", g, other)[0], lum(g), 1e-5f, "HUE(grey $g, $other)")
                assertClose(run("SATURATION", other, g)[0], lum(other), 1e-5f, "SATURATION($other, grey $g)")
                assertClose(run("COLOR", other, g)[0], lum(other), 1e-5f, "COLOR($other, grey $g)")
                assertClose(run("LUMINOSITY", g, other)[0], lum(other), 1e-5f, "LUMINOSITY(grey $g, $other)")
            }
        }
    }

    @Test
    fun blackAndWhiteStayNeutralThroughEveryWholeColourMode() {
        // Black and white are the two colours with no span, so they are the sharpest probe of the
        // SetSat/SetLum tail: the result must still be neutral, whatever the other colour was.
        //
        // DARKER_COLOR and LIGHTER_COLOR are NOT in this list, and deliberately: they are picks, not
        // arithmetic, so their answer is whichever of the two colours has the lower/higher
        // luminosity — which is the colourful one whenever the neutral is black and the other colour
        // is bright. They are pinned bit-for-bit by
        // [darkerAndLighterColorHandBackOneOfTheTwoColoursByteForByte] instead.
        val colourful = listOf(floatArrayOf(0.2f, 0.5f, 0.9f), floatArrayOf(0.9f, 0.1f, 0.4f))
        for (mode in listOf("HUE", "SATURATION", "COLOR", "LUMINOSITY")) {
            for (n in floatArrayOf(0f, 1f)) {
                val neutral = floatArrayOf(n, n, n)
                for (other in colourful) {
                    for (pair in listOf(neutral to other, other to neutral)) {
                        val got = run3(mode, pair.first, pair.second)
                        val spread = max(max(got[0], got[1]), got[2]) - min(min(got[0], got[1]), got[2])
                        assertTrue(
                            spread <= 1e-5f,
                            "$mode at ${pair.first.toList()} / ${pair.second.toList()} gave " +
                                "${got.toList()}, a spread of $spread — black or white lost its neutrality",
                        )
                    }
                }
            }
        }
    }

    // ── 4. structure ─────────────────────────────────────────────────────────────

    @Test
    fun theSeparableModesReallyArePerChannel() {
        val separable = listOf(
            "NORMAL", "MULTIPLY", "SCREEN", "OVERLAY", "ADD", "DIFFERENCE", "DARKEN", "LIGHTEN",
            "COLOR_DODGE", "COLOR_BURN", "LINEAR_BURN", "HARD_LIGHT", "SOFT_LIGHT", "VIVID_LIGHT",
            "LINEAR_LIGHT", "PIN_LIGHT", "HARD_MIX", "EXCLUSION", "SUBTRACT", "DIVIDE",
        )
        forEachTriplePair { bv, sv ->
            for (mode in separable) {
                val whole = run3(mode, bv, sv)
                for (ch in 0..2) {
                    assertEquals(
                        run(mode, bv[ch], sv[ch])[0], whole[ch], 0f,
                        "$mode channel $ch depends on the other channels at " +
                            "${bv.toList()} / ${sv.toList()}, so it is not separable",
                    )
                }
            }
        }
    }

    @Test
    fun blendRgbWritesThroughOutAndScratchWithoutTouchingItsInputs() {
        val bv = floatArrayOf(0.2f, 0.6f, 0.9f)
        val sv = floatArrayOf(0.7f, 0.4f, 0.1f)
        val bCopy = bv.copyOf()
        val sCopy = sv.copyOf()
        for (mode in listOf("COLOR", "HUE", "SATURATION", "LUMINOSITY", "DARKER_COLOR", "LIGHTER_COLOR")) {
            BlendRgb.blendRgb(code(mode), bv, sv, r, scratch)
            assertEquals(bCopy.toList(), bv.toList(), "$mode modified the backdrop in place")
            assertEquals(sCopy.toList(), sv.toList(), "$mode modified the source in place")
        }
    }

    @Test
    fun blendRgbRefusesToWriteOverItsOwnInputs() {
        val bv = floatArrayOf(0.2f, 0.6f, 0.9f)
        val sv = floatArrayOf(0.7f, 0.4f, 0.1f)
        assertFailsWith<IllegalArgumentException> { BlendRgb.blendRgb(code("HUE"), bv, sv, bv, scratch) }
        assertFailsWith<IllegalArgumentException> { BlendRgb.blendRgb(code("MULTIPLY"), bv, sv, out, sv) }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** The Studio's own epsilon. Named here so a change to it is a change to the contract. */
    private val EPS = 0.00001f

    /**
     * Every ordered pair over a grid holding every awkward value: 0 and 1 (where the epsilons
     * live), 0.5 (where every branch boundary here is), 0.25 (SOFT LIGHT's D(b)), a value either
     * side of each, and values outside 0..1 (the unclamped contract).
     */
    private fun forEachPair(f: (Float, Float) -> Unit) {
        for (bv in PAIR_GRID) for (sv in PAIR_GRID) f(bv, sv)
    }

    private fun forEachInRangePair(f: (Float, Float) -> Unit) {
        for (bv in IN_RANGE_GRID) for (sv in IN_RANGE_GRID) f(bv, sv)
    }

    /** Independent R, G and B, so chroma and the min/max walks are exercised too. */
    private fun forEachTriplePair(f: (FloatArray, FloatArray) -> Unit) {
        for (br in TRIPLE_GRID) for (bg in TRIPLE_GRID) for (bb in TRIPLE_GRID) {
            for (sr in TRIPLE_GRID) for (sg in TRIPLE_GRID) for (sb in TRIPLE_GRID) {
                f(floatArrayOf(br, bg, bb), floatArrayOf(sr, sg, sb))
            }
        }
    }

    /**
     * The rows where ClipColor is a no-op AND SetSat was not degenerate, so the saturation
     * invariants are EXACT rather than approximate: scaling a colour about its luminosity scales
     * its span, so a clipped result does NOT keep its saturation and pinning that would be pinning
     * a wrong expectation.
     *
     * The gate is [preClipColor] — the colour the mode hands to ClipColor, derived from the W3C
     * definitions over the table's inputs, independently of [BlendRgb] — and [saturationCanSurvive]
     * — NOT the recorded result. That distinction is load-bearing: the recorded result is
     * `clamp(c, 0, 1)`, so it is inside the unit box on EVERY row by construction, and gating on it
     * would silently run the invariants on the clipped rows too. (It did, once. The tests passed,
     * because the rows it picked up happened to be greys, whose saturation is 0 either way — a
     * green test that was asserting almost nothing.)
     */
    private inline fun eachUnclippedRow(mode: String, f: (FloatArray, FloatArray, FloatArray) -> Unit) {
        val c = code(mode)
        var used = 0
        var skipped = 0
        for (pair in 0 until BlendGolden.FIRST_OUT_OF_RANGE_PAIR) {
            val bv = BlendGolden.BS.copyOfRange(pair * 6, pair * 6 + 3)
            val sv = BlendGolden.BS.copyOfRange(pair * 6 + 3, pair * 6 + 6)
            val usable = saturationCanSurvive(c, bv, sv) && insideUnitBox(preClipColor(c, bv, sv))
            if (!usable) { skipped++; continue }
            b[0] = bv[0]; b[1] = bv[1]; b[2] = bv[2]
            s[0] = sv[0]; s[1] = sv[1]; s[2] = sv[2]
            BlendRgb.blendRgb(c, b, s, r, scratch)
            used++
            f(bv, sv, r.copyOf())
        }
        assertTrue(used > 200, "$mode: only $used usable rows to check the invariants on")
        assertTrue(
            skipped > 50,
            "$mode: $skipped rows were skipped, so the gate is probably not testing what it " +
                "claims to",
        )
    }

    /**
     * False when the colour that carries the HUE is neutral, so SetSat has no span to rescale and
     * returns black — and then no saturation can survive, whatever the definition says.
     *
     * This is the definition, not an excuse. HUE is `SetLum(SetSat(source, Sat(backdrop)),
     * Lum(backdrop))` in the Studio's spelling: with a grey source there is no hue to take from it,
     * and Photoshop's HUE over a grey layer is grey. SATURATION is the same story with the roles
     * swapped, so a grey BACKDROP makes SATURATION grey. COLOR and LUMINOSITY only shift
     * luminosity, which always preserves the span, so they are never gated on this.
     */
    private fun saturationCanSurvive(mode: Int, bv: FloatArray, sv: FloatArray): Boolean {
        if (mode == code("COLOR") || mode == code("LUMINOSITY")) return true
        val hueFrom = if (mode == code("HUE")) sv else bv
        return max(max(hueFrom[0], hueFrom[1]), hueFrom[2]) >
            min(min(hueFrom[0], hueFrom[1]), hueFrom[2])
    }

    /**
     * The colour a whole-colour mode hands to ClipColor, written from the W3C definitions and not
     * from [BlendRgb] — a third transcription, and the one that decides whether a saturation
     * invariant is supposed to hold on a given row.
     */
    private fun preClipColor(mode: Int, bv: FloatArray, sv: FloatArray): FloatArray {
        if (mode == code("COLOR")) {
            val d = lum(bv) - lum(sv)
            return floatArrayOf(sv[0] + d, sv[1] + d, sv[2] + d)
        }
        val carried: FloatArray
        var targetLum: Float
        if (mode == code("LUMINOSITY")) {
            carried = bv.copyOf()
            targetLum = lum(sv)
        } else {
            val hueFrom = if (mode == code("HUE")) sv else bv
            val satFrom = if (mode == code("HUE")) bv else sv
            val wanted = max(max(satFrom[0], satFrom[1]), satFrom[2]) -
                min(min(satFrom[0], satFrom[1]), satFrom[2])
            val lo = min(min(hueFrom[0], hueFrom[1]), hueFrom[2])
            val hi = max(max(hueFrom[0], hueFrom[1]), hueFrom[2])
            carried = if (hi > lo) {
                floatArrayOf(
                    (hueFrom[0] - lo) * wanted / (hi - lo),
                    (hueFrom[1] - lo) * wanted / (hi - lo),
                    (hueFrom[2] - lo) * wanted / (hi - lo),
                )
            } else {
                floatArrayOf(0f, 0f, 0f)
            }
            targetLum = lum(bv)
        }
        val shift = targetLum - lum(carried)
        return floatArrayOf(carried[0] + shift, carried[1] + shift, carried[2] + shift)
    }

    private fun insideUnitBox(c: FloatArray): Boolean =
        c[0] >= 0f && c[0] <= 1f && c[1] >= 0f && c[1] <= 1f && c[2] >= 0f && c[2] <= 1f

    private fun min3Of(c: FloatArray): Float = min(min(c[0], c[1]), c[2])

    private fun max3Of(c: FloatArray): Float = max(max(c[0], c[1]), c[2])

    /** A grey's luminosity: 0.3 + 0.59 + 0.11 = 1, so it is the grey itself, in float. */
    private fun lum(c: Float): Float = 0.3f * c + 0.59f * c + 0.11f * c

    private fun lum(c: FloatArray): Float = 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]

    /** Saturation is a colour's span: its largest channel minus its smallest. */
    private fun sat(c: FloatArray): Float =
        max(max(c[0], c[1]), c[2]) - min(min(c[0], c[1]), c[2])

    /** 2e-5 on a 0..1 colour is ~170 ulp: float rounding over a dozen operations, no more. */
    private fun assertClose(want: Float, got: Float, what: String) {
        assertClose(want, got, 2e-5f, what)
    }

    private fun assertClose(want: Float, got: Float, tol: Float, what: String) {
        if (abs(want - got) > tol) {
            fail("$what: expected $want, got $got (off by ${abs(want - got)}, allowed $tol)")
        }
    }

    private companion object {
        /** How far a threshold result may sit from a half and still be rounding rather than a branch. */
        const val BOUNDARY_BAND = 1e-6f

        val PAIR_GRID = floatArrayOf(
            -3f, -1f, -0.1f, 0f, 0.01f, 0.1f, 0.2f, 0.2499f, 0.25f, 0.2501f, 0.3f,
            0.4999f, 0.5f, 0.5001f, 0.7f, 0.75f, 0.9f, 0.99f, 1f, 1.01f, 3f,
        )
        val IN_RANGE_GRID = floatArrayOf(0f, 0.01f, 0.1f, 0.2f, 0.25f, 0.4f, 0.5f, 0.6f, 0.75f, 0.9f, 1f)
        /** Includes both of the branch boundaries (0.25 and 0.5) and both ends. */
        val TRIPLE_GRID = floatArrayOf(0f, 0.25f, 0.5f, 1f)
    }
}
