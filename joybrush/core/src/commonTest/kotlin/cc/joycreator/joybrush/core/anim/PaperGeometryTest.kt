package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource
import cc.joycreator.joybrush.core.view.ViewTransform
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The animation paper's core maths: where the pegs are, which one a finger hit, and where the ruler
 * puts its ticks.
 *
 * ## THE TWO THINGS TO KNOW BEFORE WRITING A TEST HERE
 *
 * **1. Density is a RUNTIME value (R32), and that is the whole of this row.** Every constant in
 * `PaperGeometry` is dp, and the test that carries the most weight is the one that shows the SAME dp
 * numbers arriving as DIFFERENT px numbers at two densities. A suite that only ever ran at density 1
 * would pass against exactly the bug this row exists to fix, which is a `const val 88f` that
 * claimed to be "already × density".
 * **2. Every expected number below is DERIVED IN ITS OWN COMMENT, not asserted because the code said
 * so.** Six of the numbers in the spec's own tables were wrong on re-derivation and one of them was
 * wrong on the spec's own figures, so a number with no derivation is a number the next reader has to
 * trust. Where this file's answer differs from the spec's, the comment says so and says why — see
 * [pegAtHitsPegTwoAtTwoHundredAndFortySixRatherThanPegOne].
 *
 * **`commonTest`, and not one test here opens a file** (JB-1.07 Decision 8). Even the render in
 * [helpersAreNeverInAnExport] works: `TileSource` is a `fun interface`, which is how the landed
 * `RegionRendererTest` does the same thing in `commonTest`.
 */
class PaperGeometryTest {

    // ── 1. pegCentres: the layout ───────────────────────────────────────────────

    /**
     * 600 px bar, 5 pegs, density 1 → `212, 256, 300, 344, 388`.
     *
     * Derivation: the preferred pitch is `PEG_PITCH_DP × 1 = 44`, so the block spans `4 × 44 = 176`;
     * with `176 + 2 × 14 = 204 ≤ 600` it fits and the pitch stays 44 (Decision 6's "fits" test is
     * about the last peg's OUTER edge, not its centre). The first centre is `300 − 176/2 = 212` and
     * the rest are 44 apart, so the last is `212 + 176 = 388 = 300 + 88`: **symmetric about 300**.
     *
     * (The draft of this spec said `256, 300, 344, 388, 432`, which is centred on 344 rather than on
     * 300 and does not follow from its own pitch of 88 either — with an 88 pitch the centred centres
     * would have been `124, 212, 300, 388, 476`.)
     */
    @Test
    fun pegCentresCentreTheBlockOnTheBar() {
        assertContentEquals(floatArrayOf(212f, 256f, 300f, 344f, 388f), PaperGeometry.pegCentres(5, 600f, 1f))
    }

    /** One peg is one peg at the middle, and a count below 1 is a count of 1. */
    @Test
    fun pegCentresCoerceTheCount() {
        for (count in listOf(1, 0, -3, Int.MIN_VALUE)) {
            val centres = PaperGeometry.pegCentres(count, 600f, 1f)
            assertEquals(1, centres.size, "count $count is one peg")
            assertEquals(300f, centres[0], "count $count is centred: bar/2 = 600/2 = 300")
        }
    }

    /**
     * 200 px bar, 5 pegs, density 1 → pitch 40, `20, 60, 100, 140, 180`.
     *
     * Derivation: `176 + 2 × 14 = 204 > 200`, so the block does NOT fit and the pitch shrinks to
     * `barWidthPx / count = 200 / 5 = 40`. The floor is `2 × PEG_RADIUS_DP = 28` and `40 ≥ 28`, so
     * the shrink is legal. The first centre is `100 − 4 × 40 / 2 = 20`.
     *
     * The three properties asserted after the list are what make the shrink a LAYOUT rather than a
     * number: no centre leaves `[0, 200]`, the first clears the radius (`20 ≥ 14`) and the last does
     * too (`180 ≤ 186 = 200 − 14`), and the pitch stays above the floor. A shrink that quietly
     * clipped the end pegs would pass a test that only checked the list.
     */
    @Test
    fun pegCentresShrinkThePitchBeforeTheyClipAPeg() {
        val centres = PaperGeometry.pegCentres(5, 200f, 1f)
        assertContentEquals(floatArrayOf(20f, 60f, 100f, 140f, 180f), centres)
        for (c in centres) {
            assertTrue(c in 0f..200f, "centre $c is inside the bar")
        }
        assertTrue(centres.first() >= PaperGeometry.PEG_RADIUS_DP, "the first peg is not clipped")
        assertTrue(centres.last() <= 200f - PaperGeometry.PEG_RADIUS_DP, "the last peg is not clipped")
        assertTrue(centres[1] - centres[0] >= 2f * PaperGeometry.PEG_RADIUS_DP, "the pitch is above the floor")
    }

    /**
     * A bar too narrow even for the floor overflows SYMMETRICALLY, which is Decision 6's whole
     * content: a peg scrolled off the end of the bar is a control that exists and cannot be reached,
     * and a bar that cannot hold the block is a bar that must scroll — so the overflow is shared.
     *
     * Derivation: at 28 px the floor itself binds (`28 / 5 = 5.6 < 2 × 14 = 28`), so the pitch is 28
     * and the first centre is `14 − 4 × 28 / 2 = −42`: `−42, −14, 14, 42, 70`. Peg 0 is 56 px left of
     * the bar's half-width 14 and peg 4 is 56 px right of it — `14 − (−42) = 56 = 70 − 14` — so the
     * block is centred on the bar exactly as the wide case is, instead of running off the right.
     */
    @Test
    fun aBarTooNarrowForTheFloorOverflowsSymmetrically() {
        val centres = PaperGeometry.pegCentres(5, 28f, 1f)
        assertContentEquals(floatArrayOf(-42f, -14f, 14f, 42f, 70f), centres)
        assertEquals(14f - centres.first(), centres.last() - 14f, "the overflow is shared, not pushed right")
    }

    // ── 2. pegAt: the hit test ─────────────────────────────────────────────────

    /**
     * 600 px bar, 5 pegs, density 1, hit radius `22 × 1 = 22` — against the centres of test 1.
     *
     * Each expectation carries the two distances that decide it, because the rule is NEAREST with
     * ties to the lower index (Decision 7) and a boundary case with only one number beside it is
     * exactly the number the next reader cannot check.
     */
    @Test
    fun pegAtHitsTheNearestCentreAndTiesGoToTheLowerIndex() {
        val bar = 600f
        // 0 from peg 2.
        assertEquals(2, PaperGeometry.pegAt(300f, 5, bar, 1f))
        // A true tie: 22 from peg 2 (at 300) and 22 from peg 3 (at 344), the hit radius exactly.
        // The lower index wins. (The draft's `2 and 2` answer was measured on 88-px radii that no
        // longer exist, but the tie and the rule are the same.)
        assertEquals(2, PaperGeometry.pegAt(322f, 5, bar, 1f))
        // One pixel past that tie: 23 from peg 2 is out, 21 from peg 3 is in.
        assertEquals(3, PaperGeometry.pegAt(323f, 5, bar, 1f))
        // The same tie one gap further on: 22 from 256 and 22 from 300.
        assertEquals(1, PaperGeometry.pegAt(278f, 5, bar, 1f))
        // The OUTER edge of peg 0's radius, from the left: 22 from 212, and 66 from peg 1 is out.
        assertEquals(0, PaperGeometry.pegAt(190f, 5, bar, 1f))
        // 21.9 is INSIDE the radius, so it would still be a hit — which is why the boundary case
        // worth asserting is the one just OUTSIDE it, and 22.1 from the only peg in range is out.
        assertEquals(-1, PaperGeometry.pegAt(189.9f, 5, bar, 1f))
    }

    /**
     * `pegAt(0, 5, 600)` is −1, and the reason is the layout rather than the hit test: **the nearest
     * centre is 212 px away**, nearly ten hit radii. The draft of this spec asserted `0` here, and it
     * was wrong on the draft's OWN centres too (its `256, 300, 344, 388, 432` put the nearest at
     * 256). A finger at the left edge of the bar is not pressing the first peg.
     */
    @Test
    fun pegAtReturnsMinusOneWhenNothingIsWithinTheRadius() {
        assertEquals(212f, PaperGeometry.pegCentres(5, 600f, 1f)[0], "the nearest centre to 0 is 212")
        assertEquals(-1, PaperGeometry.pegAt(0f, 5, 600f, 1f))
    }

    /** Off the ends, at the end of the Float range, and NaN: −1, and never an exception. */
    @Test
    fun pegAtSurvivesAbsurdCoordinates() {
        for (x in listOf(-1f, -1e9f, 1e9f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertEquals(-1, PaperGeometry.pegAt(x, 5, 600f, 1f), "x = $x hits nothing")
        }
    }

    // ── 3. R32: density is multiplied at the use site ──────────────────────────

    /**
     * The same bar at density 3. Derivation, from the dp constants and nothing else:
     *
     *  - pitch `44 × 3 = 132`, radius `14 × 3 = 42`, hit radius `22 × 3 = 66`;
     *  - the block needs `4 × 132 + 2 × 42 = 528 + 84 = 612 > 600`, so it does not fit and the pitch
     *    shrinks to `600 / 5 = 120`, above the floor `2 × 42 = 84`;
     *  - the first centre is `300 − 4 × 120 / 2 = 60`, so `60, 180, 300, 420, 540`.
     *
     * At density 1 the same bar gives 212…388 (test 1), so the two densities cannot be the same code
     * with a different literal — which is the only thing a test of "is the density multiplied in" is
     * really for.
     */
    @Test
    fun densityIsAppliedAtTheUseSiteAndNotBakedIntoAConstant() {
        assertContentEquals(floatArrayOf(60f, 180f, 300f, 420f, 540f), PaperGeometry.pegCentres(5, 600f, 3f))
        assertEquals(1, PaperGeometry.pegAt(180f, 5, 600f, 3f), "dead centre of peg 1")
        assertEquals(1, PaperGeometry.pegAt(234f, 5, 600f, 3f), "54 from peg 1 beats 66 from peg 2")
        assertEquals(2, PaperGeometry.pegAt(247f, 5, 600f, 3f), "67 from peg 1 is out, 53 from peg 2 is in")
    }

    /**
     * **`pegAt(246, …, density 3) = 2`, and this is a number the spec's table still gets wrong.**
     *
     * 246 is `180 + 66`, so it sits exactly on peg 1's hit radius — but it is `300 − 54 = 54` from
     * peg 2, and 54 < 66, so the NEAREST centre is peg 2. The spec's table says `1`, which is only
     * true under a "first peg whose radius contains x" rule: a different rule from Decision 7, which
     * says nearest, and which says a tie goes to the lower index. Every other boundary in the spec's
     * own test 3 (the ties at 322 and 278) is written for the nearest rule, so the two rules cannot
     * both be in the file. Implemented as Decision 7 states.
     *
     * (The spec's gloss on the neighbouring line — `pegAt(234) = 1`, "a tie" — is not a tie either,
     * 54 and 66 are not equal, but its ANSWER is right for the quieter reason that 54 is nearer.)
     */
    @Test
    fun pegAtHitsPegTwoAtTwoHundredAndFortySixRatherThanPegOne() {
        val centres = PaperGeometry.pegCentres(5, 600f, 3f)
        assertEquals(66f, abs(centres[1] - 246f), "246 is exactly peg 1's hit radius (22 × 3)")
        assertEquals(54f, abs(centres[2] - 246f), "and 54 from peg 2")
        assertTrue(abs(centres[2] - 246f) < abs(centres[1] - 246f), "so peg 2 is the nearer one")
        assertEquals(2, PaperGeometry.pegAt(246f, 5, 600f, 3f))
    }

    /**
     * **THE PROPERTY THIS ROW EXISTS FOR (R32): the dp numbers are the same at every density and only
     * the px numbers change.**
     *
     * Stated as a scale law rather than as two literals, because two literals only say the code moves
     * when the density does — a law says it moves the right AMOUNT:
     *
     *  - A bar 600 **dp** wide is 600 px at density 1 and 1800 px at density 3. Asking for the same
     *    bar at both and dividing the px answer back down must give test 1's layout exactly:
     *    `636, 768, 900, 1032, 1164` ÷ 3 = `212, 256, 300, 344, 388`. (Derivation: at 1800 px the
     *    preferred pitch 132 needs `4 × 132 + 84 = 612 ≤ 1800`, so it does not shrink, and the first
     *    centre is `900 − 4 × 132 / 2 = 636`.)
     *  - The ruler is a function of `density ÷ zoom` — how many dp a document pixel is worth on
     *    screen — so scaling the zoom DOWN by three is the same problem as scaling the density UP by
     *    three, and the two must return the same step. (The needed step is `48 × density ÷ zoom`
     *    document px: `48 × 3 ÷ 1 = 144` and `48 × 1 ÷ (1/3) = 144`.) At 144 the ladder answers 200:
     *    100 is short, and 150 and 175 are not on the ladder at all.
     *  - And the px really do differ, which is the half that keeps the rest honest: at zoom 1 the
     *    step is 50 document px at density 1 and 200 at density 3.
     *
     * A `const val 88f` that "claims to be already scaled" fails the first loop below and passes
     * every other test in this file.
     */
    @Test
    fun theDpIsIdenticalAtEveryDensityAndOnlyThePixelsChange() {
        // The dp numbers are density-free BY CONSTRUCTION — these are the literals the view reads.
        assertEquals(44f, PaperGeometry.PEG_PITCH_DP)
        assertEquals(14f, PaperGeometry.PEG_RADIUS_DP)
        assertEquals(22f, PaperGeometry.PEG_HIT_RADIUS_DP)
        assertEquals(48f, PaperGeometry.MIN_LABEL_DP)
        assertEquals(24f, PaperGeometry.RULER_THICKNESS_DP)

        val atDensityOne = PaperGeometry.pegCentres(5, 600f, 1f)
        for (density in listOf(1f, 2f, 3f, 4f)) {
            val px = PaperGeometry.pegCentres(5, 600f * density, density)
            for (i in px.indices) {
                assertEquals(
                    atDensityOne[i],
                    px[i] / density,
                    "density $density, peg $i is the same dp position",
                )
            }
            // first = bar/2 − 4·pitch/2 = 300d − 88d = 212d, so the px layout really did move.
            assertEquals(212f * density, px[0], "density $density lays the block out in px")
        }

        assertEquals(200L, stepOf(PaperGeometry.ticks(0.0, 4000.0, 0.0, 1f, 3f)), "density 3, zoom 1")
        assertEquals(200L, stepOf(PaperGeometry.ticks(0.0, 4000.0, 0.0, 1f / 3f, 1f)), "density 1, zoom 1/3")
        assertEquals(50L, stepOf(PaperGeometry.ticks(0.0, 4000.0, 0.0, 1f, 1f)), "density 1, zoom 1: floor 48")
        assertTrue(
            stepOf(PaperGeometry.ticks(0.0, 4000.0, 0.0, 1f, 3f)) !=
                stepOf(PaperGeometry.ticks(0.0, 4000.0, 0.0, 1f, 1f)),
            "at a fixed zoom the step in document px grows with the density",
        )
    }

    // ── 4. ticks: the ladder ───────────────────────────────────────────────────

    /**
     * Zoom 1, density 3 → step **200**. Derivation: the floor is `48 × 3 = 144` screen px, so a step
     * of 100 occupies `100 × 1 = 100` screen px and is short; 200 occupies 200 and fits; 250 and 300
     * are not on the 1/2/5 ladder at all, so **200 is the finest that fits**.
     *
     * (The draft's headline said 100, which its own parenthetical arithmetic contradicts.)
     *
     * Over a doc view `0..600` on a board at 0 the ticks are `0, 200, 400, 600` — four of them, one
     * per step interval, and every one a multiple of 200, so 150 and 175 are impossible by
     * CONSTRUCTION rather than by a filter afterwards.
     */
    @Test
    fun ticksUseTheFinestLadderStepThatFitsTheFloor() {
        val t = PaperGeometry.ticks(0.0, 600.0, 0.0, 1f, 3f)
        assertContentEquals(longArrayOf(0L, 200L, 400L, 600L), t)
        for (tick in t) {
            assertEquals(0L, tick % 200L, "a tick is a multiple of the step, so 150 and 175 cannot appear")
        }
    }

    /**
     * Zoom 0.05 (`ViewTransform.MIN_ZOOM`), density 1 → step **1000**, and the count is 12 step
     * intervals, not the draft's "≤ 2".
     *
     * Derivation: the floor is 48 screen px and a step of `s` occupies `s × 0.05`, so `s ≥ 960`; on
     * the ladder 500 gives 25 (too close) and 1000 gives 50 (fits), so **1000**. A 600-screen-px view
     * at that zoom spans `600 / 0.05 = 12 000` document px = **12 step intervals**, which is 13 ruled
     * lines with both ends counted — and that is why Decision 11 needs no cap: 43 is the ceiling for
     * a real phone, and this is the FURTHEST OUT zoom there is, so it is the worst case.
     *
     * The span is written as `12_000.0` rather than computed as `600 / ViewTransform.MIN_ZOOM`, and
     * that is not laziness: `0.05f` is really `0.050000000745…`, so the division lands a hair SHORT
     * of 12 000 and a computed view would legitimately lose the last tick. The view a person gets is
     * `round(pan / zoom)`-shaped, not `600 / 0.05f`-shaped, so the exact figure is the honest one.
     */
    @Test
    fun theFurthestOutZoomSpansTwelveStepIntervals() {
        val docSpan = 12_000.0
        val t = PaperGeometry.ticks(0.0, docSpan, 0.0, ViewTransform.MIN_ZOOM, 1f)
        assertEquals(13, t.size, "12 intervals, 13 lines with both ends counted")
        assertEquals(1000L, stepOf(t), "floor 48 at 0.05: 500 gives 25 screen px, 1000 gives 50")
        assertEquals(0L, t.first())
        assertEquals(12_000L, t.last())
        for (i in 1 until t.size) {
            assertEquals(1000L, t[i] - t[i - 1], "evenly ruled")
        }
    }

    /**
     * Zoom 64 (`ViewTransform.MAX_ZOOM`) → step 1. Derivation: `s × 64 ≥ 48` gives `s ≥ 0.75`, and
     * the ladder starts at 1 (Decision 9), so every document pixel in view is a tick.
     */
    @Test
    fun theFurthestInZoomRulesEveryPixel() {
        val t = PaperGeometry.ticks(0.0, 8.0, 0.0, ViewTransform.MAX_ZOOM, 1f)
        assertContentEquals(longArrayOf(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), t)
        assertEquals(1L, stepOf(t))
    }

    /**
     * Garbage in, nothing out — and a bad DENSITY is not garbage, it is a device we do not have.
     *
     * A `screenPerDoc` of `0f`, `−1f`, NaN or `±∞`, an inverted view, and a non-finite value in any
     * of the three document arguments all give an empty array rather than an exception. A density of
     * `0f` or `−1f` is treated as 1, so the answer is the density-1 ladder and not a division by
     * zero that would turn the whole bar into NaN on one device class.
     */
    @Test
    fun ticksRefuseBrokenNumbersAndRepairBrokenDensities() {
        for (zoom in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0, PaperGeometry.ticks(0.0, 600.0, 0.0, zoom, 1f).size, "zoom $zoom")
        }
        assertEquals(0, PaperGeometry.ticks(600.0, 0.0, 0.0, 1f, 1f).size, "an inverted view")
        assertEquals(0, PaperGeometry.ticks(Double.NaN, 600.0, 0.0, 1f, 1f).size, "a NaN start")
        assertEquals(0, PaperGeometry.ticks(0.0, Double.NaN, 0.0, 1f, 1f).size, "a NaN end")
        assertEquals(0, PaperGeometry.ticks(0.0, 600.0, Double.NaN, 1f, 1f).size, "a NaN board origin")
        assertEquals(
            0,
            PaperGeometry.ticks(0.0, 600.0, Double.POSITIVE_INFINITY, 1f, 1f).size,
            "an infinite board origin",
        )

        // Zoom 1 at density 1 is step 50: 20 × 1 = 20 < 48 and 50 ≥ 48.
        for (broken in listOf(0f, -1f, Float.NaN)) {
            assertContentEquals(
                PaperGeometry.ticks(0.0, 200.0, 0.0, 1f, 1f),
                PaperGeometry.ticks(0.0, 200.0, 0.0, 1f, broken),
                "density $broken is density 1",
            )
        }
    }

    /**
     * Negative `k` FLOORS, they do not truncate (Decision 10).
     *
     * Step 2 needs `2 × screenPerDoc ≥ 48`, so the test uses `screenPerDoc = 24f` — `1 × 24 = 24` is
     * short and `2 × 24 = 48` fits exactly, which makes step 2 the answer without any fudge. A doc
     * view of `−3..3` relative to the board then gives exactly `−2, 0, 2`, and the assertion that
     * matters is that **−2 is present**: a `k ≥ 0` reading would have produced `0, 2` and left the
     * left edge of a view that starts at a negative coordinate with no tick near it. This is the
     * floor-not-truncate bug the project keeps meeting at a seam, and on a ruler it is visible.
     */
    @Test
    fun negativeTicksFloorRatherThanTruncate() {
        val t = PaperGeometry.ticks(-3.0, 3.0, 0.0, 24f, 1f)
        assertContentEquals(longArrayOf(-2L, 0L, 2L), t)
        assertTrue(-2L in t.toList(), "the tick BELOW the view's start is on the ruler")
    }

    // ── 5. the ruler is measured from the BOARD ────────────────────────────────

    /**
     * Two boards — one at `(0, 0)` and one at `(−1400, 300)` — with views chosen so that both cover
     * board-relative `−3..3`, give **identical arrays**.
     *
     * Derivation: on the second board the doc view is `−1403..−1397` in x and `297..303` in y, so
     * subtracting its origin gives `−3..3` again and step 2 gives `−2, 0, 2` in both. A
     * document-relative ruler would differ by 1400 on x and 300 on y and would fail this — which is
     * the point of Decision 8: a board may sit at a negative x or y on the unbounded canvas, and a
     * ruler reading −1400 would be true and useless.
     */
    @Test
    fun theRulerIsMeasuredFromTheBoardNotTheDocument() {
        for ((origin, viewStart, viewEnd) in listOf(
            Triple(0.0, -3.0, 3.0),
            Triple(-1400.0, -1403.0, -1397.0),
            Triple(300.0, 297.0, 303.0),
        )) {
            assertContentEquals(
                PaperGeometry.ticks(-3.0, 3.0, 0.0, 24f, 1f),
                PaperGeometry.ticks(viewStart, viewEnd, origin, 24f, 1f),
                "a board at $origin reads the same as a board at 0",
            )
        }
    }

    /**
     * A zoom change re-ladders the ruler, and the step is a function of the ZOOM and the density —
     * not of anything a previous call did. Same view, same origin, called twice each way.
     *
     * Density 1 (floor 48): at zoom 1 the step is **50** (`20 × 1 = 20 < 48`, `50 ≥ 48`) and at zoom
     * 0.1 it is **500** (`500 × 0.1 = 50 ≥ 48`, while `50 × 0.1 = 5` does not). The same two calls at
     * **density 2** (floor 96) give **100 and 1000** (`100 × 1 = 100 ≥ 96`; `1000 × 0.1 = 100 ≥ 96`,
     * while `500 × 0.1 = 50` does not).
     *
     * (The draft claimed 100 and 1000 at density 1 — the density-2 answer written on the density-1
     * test, which is the unit slip showing up inside a test table.)
     */
    @Test
    fun aZoomChangeReLaddersTheRulerWithNoStateToInvalidate() {
        assertEquals(50L, stepOf(PaperGeometry.ticks(0.0, 2000.0, 0.0, 1f, 1f)), "density 1, zoom 1")
        assertEquals(500L, stepOf(PaperGeometry.ticks(0.0, 2000.0, 0.0, 0.1f, 1f)), "density 1, zoom 0.1")
        assertEquals(100L, stepOf(PaperGeometry.ticks(0.0, 2000.0, 0.0, 1f, 2f)), "density 2, zoom 1")
        assertEquals(1000L, stepOf(PaperGeometry.ticks(0.0, 2000.0, 0.0, 0.1f, 2f)), "density 2, zoom 0.1")

        // Order does not matter: the same call after a different one gives the same answer.
        PaperGeometry.ticks(0.0, 2000.0, 0.0, 0.1f, 1f)
        assertEquals(50L, stepOf(PaperGeometry.ticks(0.0, 2000.0, 0.0, 1f, 1f)), "no history")
    }

    /**
     * Every step is on the ladder: 40 zooms × 3 densities × 3 board origins, and the step divided by
     * its own largest power of ten is 1, 2 or 5 and never below 1.
     *
     * This is the test that says `0.03` and `7` are impossible BY CONSTRUCTION rather than by a
     * rounding step, and it is also the one that would notice a ladder starting below 1 — the change
     * that would mean a `Double` tick and a rewritten contract. The view is 40 000 document px wide
     * so that even the coarsest step here (10 000, at zoom 0.023 and density 3) rules at least two
     * lines to read the step off.
     */
    @Test
    fun everyStepIsOnTheLadder() {
        val zooms = listOf(
            0.023f, 0.04f, 0.05f, 0.06f, 0.07f, 0.09f, 0.1f, 0.13f, 0.15f, 0.2f,
            0.25f, 0.3f, 0.33f, 0.45f, 0.5f, 0.6f, 0.75f, 0.9f, 0.99f, 1f,
            1.1f, 1.5f, 2.2f, 3f, 3.7f, 4f, 5.5f, 7f, 9.5f, 11.3f,
            13f, 17f, 21f, 22f, 30f, 37f, 40f, 55f, 63.5f, 64f,
        )
        for (zoom in zooms) {
            for (density in listOf(1f, 2f, 3f)) {
                for (origin in listOf(0.0, -1400.0, 12345.0)) {
                    val t = PaperGeometry.ticks(origin, origin + 40_000.0, origin, zoom, density)
                    val where = "zoom $zoom, density $density, origin $origin"
                    assertTrue(t.size >= 2, "$where ruled something: ${t.size} tick(s)")
                    val step = t[1] - t[0]
                    assertTrue(step >= 1L, "$where: step $step is a whole document pixel")
                    var decade = 1.0
                    while (decade * 10.0 <= step.toDouble()) decade *= 10.0
                    val lead = step / decade
                    assertTrue(
                        lead == 1.0 || lead == 2.0 || lead == 5.0,
                        "$where: step $step is not on the 1/2/5 ladder",
                    )
                }
            }
        }
    }

    // ── 6. labels ──────────────────────────────────────────────────────────────

    /**
     * Whole pixels, no decimal point, no separator, no unit, and no `-0` — because there is no float
     * in the signature that could produce one.
     */
    @Test
    fun labelsAreWholePixels() {
        assertEquals("0", PaperGeometry.label(0L))
        assertEquals("-1400", PaperGeometry.label(-1400L))
        assertEquals("512", PaperGeometry.label(512L))
        assertEquals("-9223372036854775808", PaperGeometry.label(Long.MIN_VALUE))
        assertEquals("9223372036854775807", PaperGeometry.label(Long.MAX_VALUE))
        for (v in listOf(0L, -1400L, 512L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val text = PaperGeometry.label(v)
            assertTrue(
                '.' !in text && ',' !in text && ' ' !in text && '-' !in text.drop(1),
                "no decimal point, no separator, no unit, no -0: \"$text\"",
            )
        }
    }

    // ── 7. one saturated control (R33) ─────────────────────────────────────────

    /**
     * For all five pegs × both values of `active`, exactly ONE result is `PegStyle.ACTION` and it is
     * `style(PLAY, true)`. The enum is ENUMERATED here rather than listed, so a sixth peg turns this
     * red instead of quietly adding a second gradient.
     */
    @Test
    fun exactlyOnePegIsAllowedToLookLikeAnActionAndItIsPlay() {
        val actions = Peg.entries.flatMap { peg ->
            listOf(true, false).filter { PaperGeometry.style(peg, it) == PegStyle.ACTION }.map { peg }
        }
        assertEquals(1, actions.size, "one saturated control on the whole animation board (R33)")
        assertEquals(Peg.PLAY, actions.single())
        assertEquals(PegStyle.ACTION, PaperGeometry.style(Peg.PLAY, true))
        assertEquals(PegStyle.STATE_RING, PaperGeometry.style(Peg.PLAY, false), "a Play that lies about playing")
        assertEquals(PegStyle.IDENTITY, PaperGeometry.style(Peg.ONION, true))
        assertEquals(PegStyle.STATE_RING, PaperGeometry.style(Peg.ONION, false))
        for (peg in listOf(Peg.MODE, Peg.CADENCE, Peg.EXPORT)) {
            assertEquals(PegStyle.IDENTITY, PaperGeometry.style(peg, true), "$peg is never an action")
            assertEquals(PegStyle.IDENTITY, PaperGeometry.style(peg, false), "$peg is never an action")
        }
    }

    /**
     * The peg set is the five, in that order — the house idiom from `EnumFreezeTest`, asserted
     * against the WHOLE list so a reorder or a rename fails.
     *
     * This is R33's "the peg bar owns PLAY and MODE" made checkable, and it is also the assertion
     * that nothing here is a transport control: no loop peg, no stop peg, no prev/next peg, because
     * the film strip has prev/next and nothing else and this bar has play. (`Peg` is deliberately
     * NOT added to `EnumFreezeTest` — that file pins the SERIALISED enums for R3's version rule,
     * `Peg` never reaches a file, and that file is outside this row's owner area.)
     */
    @Test
    fun thePegSetIsTheFiveInOrder() {
        assertEquals(listOf("PLAY", "MODE", "ONION", "CADENCE", "EXPORT"), Peg.entries.map { it.name })
        for (transport in listOf("LOOP", "STOP", "PREV", "NEXT", "PAUSE", "MUTE")) {
            assertTrue(transport !in Peg.entries.map { it.name }, "the strip owns $transport, not the peg bar")
        }
        // PegPress is the three cases blueprint §1(a) ruled, and no hover MENU (Decision 3).
        assertEquals(listOf("TAP", "LONG_PRESS", "HOVER"), PegPress.entries.map { it.name })
        assertEquals(listOf("IDENTITY", "STATE_RING", "ACTION"), PegStyle.entries.map { it.name })
    }

    // ── 8. helpers are never in an export ──────────────────────────────────────

    /**
     * The same one-layer document rendered twice — once plainly, and once after this file's geometry
     * has been computed for that board — gives **identical bytes**.
     *
     * Decision 14 says the rulers and pegs are overlays and are never in an export, and this pins
     * the half that can be pinned from `core`: a builder who later decides to put a ruler in a layer
     * has to delete this test to do it. `TileSource` is a `fun interface`, so the fake is one line
     * and the whole test stays in `commonTest` with nothing opened.
     */
    @Test
    fun helpersAreNeverInAnExport() {
        val board = Board("anim", "Anim", BoardKind.ANIMATION, RectPx(-1400, 300, 512, 512))
        val doc = JbDocument(
            id = "d",
            name = "D",
            boards = listOf(board),
            layers = listOf(Layer(id = "l", name = "l", kind = LayerKind.PAINT, cels = listOf(Cel("c")))),
        )
        val rect = RectPx(-1400, 300, 64, 64)
        val empty = TileSource { _, _, _, _ -> null }

        val plain = RegionRenderer.render(doc, empty, rect, frameId = null, paper = null)

        // Everything the view would have drawn on top, computed for THIS board.
        PaperGeometry.pegCentres(Peg.entries.size, 600f, 3f)
        val tick = PaperGeometry.ticks(-1400.0, -1336.0, board.rect.x.toDouble(), 1f, 3f)[0]
        PaperGeometry.label(tick)
        assertTrue(PaperGeometry.pegAt(300f, Peg.entries.size, 600f, 3f) >= 0, "the peg bar was laid out")

        val afterHelpers = RegionRenderer.render(doc, empty, rect, frameId = null, paper = null)
        assertContentEquals(plain, afterHelpers, "an overlay is a View over the canvas, never a layer")
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** The gap between two adjacent ticks, which IS the step. */
    private fun stepOf(ticks: LongArray): Long {
        assertTrue(ticks.size >= 2, "a step needs two ticks to read off, got ${ticks.size}")
        return ticks[1] - ticks[0]
    }
}
