package cc.joycreator.joybrush.core.paper

import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.paper.PaperCatalogues.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PaperState] — a document's paper resolved against the catalogue (JB-9.05).
 *
 * **This is the join between two files.** `document.json` stores ids and numbers, and
 * `assets/paper/catalogue.json` stores what those ids mean. Neither is useful alone: the document says
 * `"lookId": "off_white"` and the catalogue says what off-white looks like, and only the two together
 * produce the numbers the renderer needs. This is the one place they meet.
 *
 * **`resolve` NEVER FAILS.** An id this build's catalogue does not have resolves to null — smooth, or
 * a flat colour — and is REPORTED through [PaperState.problems] rather than thrown. That is the whole
 * design: a drawing saved with a paper a later build added, or one whose catalogue entry was renamed,
 * must still OPEN on a build that has never heard of it, because refusing to open a person's drawing
 * over a background is the one outcome nobody would accept. Smooth is a safe answer; a crash is not.
 *
 * **The three colours are one precedence chain, and the tests below are the chain read end to end:**
 * `tint` beats the look's own `base`, which beats the document's flat `color`. A tint is the owner's
 * explicit "make this paper this colour", so it has to win; the look's base is what the look IS; and
 * `color` is the pre-catalogue flat colour that only matters when there is no look at all.
 */
class PaperStateTest {

    /** One surface and one look, so both halves of a resolved paper can be non-null. */
    private val catalogue = parse(
        """
        {
          "format": "joybrush-papers",
          "version": 1,
          "surfaces": [
            { "id": "pulp_artisan", "name": "Artisan pulp", "file": "s.png", "size": 512,
              "texelPx": 2.0, "slopeRange": 0.099, "hexTexels": 180, "rotatable": true, "relief": 1.0 }
          ],
          "looks": [
            { "id": "off_white", "name": "Off-white", "base": "#F3EFE6", "file": null,
              "texelPx": 2.0, "hexTexels": 180, "rotatable": true, "defaultSurface": "pulp_artisan" },
            { "id": "amoled_black", "name": "AMOLED black", "base": "#000000", "file": null,
              "texelPx": 2.0, "hexTexels": 180, "rotatable": true, "lightByDefault": false }
          ]
        }
        """.trimIndent(),
    )

    // ---- the colour chain ------------------------------------------------------------------------

    /**
     * **The whole chain in one test, because it is one rule.** Read as a table rather than three tests
     * so the precedence is visible: what you set wins over what it is set on, and the flat colour is the
     * floor under both.
     */
    @Test
    fun theColourIsTheTintOrTheLooksBaseOrTheDocumentsColourInThatOrder() {
        val asColour = PaperState.resolve(Paper(), catalogue).baseArgb
        assertEquals(0xFFFFFFFF.toInt(), asColour, "no tint and no look: the document's own flat colour")

        val fromLook = PaperState.resolve(Paper(color = "#112233", lookId = "off_white"), catalogue).baseArgb
        assertEquals(0xFFF3EFE6.toInt(), fromLook, "a look's base beats the document colour")

        val fromTint = PaperState.resolve(Paper(color = "#112233", lookId = "off_white", tint = "#445566"), catalogue).baseArgb
        assertEquals(0xFF445566.toInt(), fromTint, "a tint beats the look's own base")
    }

    /**
     * **The resolved colour is always OPAQUE.** Every one of the three sources is `#RRGGBB` with no
     * alpha channel, and a paper pass that drew at partial alpha would let the canvas through the
     * paper — which is a different product. `0xFF` in the high byte is asserted on every rung of the
     * chain above, which is why the expectations are written as full ARGB literals rather than the
     * `0xF3EFE6` the file holds.
     */
    @Test
    fun theResolvedColourIsAlwaysFullyOpaque() {
        for (p in listOf(
            Paper(),
            Paper(color = "#000000"),
            Paper(lookId = "off_white"),
            Paper(lookId = "off_white", tint = "#FFFFFF"),
        )) {
            val argb = PaperState.resolve(p, catalogue).baseArgb
            assertEquals(0xFF, argb ushr 24 and 0xFF, "paper colour must be opaque, but ${p} gave $argb")
        }
    }

    /** Lower-case hex is legal and is not a different colour: `#f3efe6` is the same paper as `#F3EFE6`. */
    @Test
    fun aLowerCaseHexIsTheSameColour() {
        val p = Paper(lookId = "off_white", tint = "#aabbcc")
        assertEquals(0xFFAABBCC.toInt(), PaperState.resolve(p, catalogue).baseArgb)
    }

    // ---- the halves ------------------------------------------------------------------------------

    @Test
    fun aLookAndItsSurfaceBothResolve() {
        val r = PaperState.resolve(Paper(lookId = "off_white", textureId = "pulp_artisan"), catalogue)
        assertEquals("off_white", r.look?.id)
        assertEquals("pulp_artisan", r.surface?.id, "`textureId` IS the surface id, so the same id resolves both ways")
    }

    /** A flat document paper is smooth: no surface means brushes feel nothing and nothing is lit. */
    @Test
    fun aPaperWithNoSurfaceIsSmooth() {
        val r = PaperState.resolve(Paper(), catalogue)
        assertNull(r.surface, "no textureId means smooth")
        assertNull(r.look, "no lookId means a flat colour")
    }

    // ---- the unknown id: resolve null, report it, never throw ---------------------------------------

    /**
     * **The case the whole design is for.** A document naming a paper this build's catalogue does not
     * have — because the drawing was saved by a later Joy Brush, or because the catalogue lost the
     * entry — must still open, on flat white paper, and SAY SO. A throw here would make one missing
     * background cost a person their drawing.
     */
    @Test
    fun anUnknownSurfaceIdResolvesToSmoothAndIsReported() {
        val p = Paper(textureId = "chalk_green_from_the_future")
        val r = PaperState.resolve(p, catalogue)
        assertNull(r.surface, "an id this build does not have is smooth, not a throw")
        val problems = PaperState.problems(p, catalogue)
        assertEquals(1, problems.size, "one unknown id is one problem, got: $problems")
        assertTrue(
            problems.single().contains("chalk_green_from_the_future"),
            "the problem must name the id so a person can find the line, got: ${problems.single()}",
        )
    }

    @Test
    fun anUnknownLookIdResolvesToFlatAndIsReported() {
        val p = Paper(color = "#ABCDEF", lookId = "rice_paper")
        val r = PaperState.resolve(p, catalogue)
        assertNull(r.look, "an id this build does not have is a flat colour, not a throw")
        assertEquals(0xFFABCDEF.toInt(), r.baseArgb, "with no look, the document's own colour is what shows")
        assertEquals(1, PaperState.problems(p, catalogue).size)
    }

    /** Both unknown at once is TWO problems — one per id — and still no throw. */
    @Test
    fun twoUnknownIdsAreTwoProblems() {
        val problems = PaperState.problems(Paper(lookId = "rice", textureId = "chalk"), catalogue)
        assertEquals(2, problems.size, "got: $problems")
    }

    /** The control: a paper naming only ids that exist reports nothing, so the tests above are not vacuous. */
    @Test
    fun aPaperWithKnownIdsHasNoProblems() {
        val p = Paper(lookId = "off_white", textureId = "pulp_artisan")
        assertEquals(emptyList(), PaperState.problems(p, catalogue), "got: ${PaperState.problems(p, catalogue)}")
        assertEquals(emptyList(), PaperState.problems(Paper(), catalogue), "a brand new document is clean")
    }

    // ---- light ------------------------------------------------------------------------------------

    /**
     * **`light` is a THREE-state field and null is not false.** `null` means "whatever the look says",
     * which is how AMOLED black ships: its `lightByDefault` is false, because lighting a relief on a
     * true black greys it into a dark grey that is not black. A field of plain `Boolean` could not say
     * this — it would have to be false on every look that happens to want lighting on, or the paper
     * would carry a switch that the catalogue also sets and the two would fight.
     */
    @Test
    fun lightNullTakesTheLooksOwnDefault() {
        assertTrue(
            PaperState.resolve(Paper(lookId = "off_white"), catalogue).light,
            "off_white ships lightByDefault true, so null means lit",
        )
        assertTrue(
            !PaperState.resolve(Paper(lookId = "amoled_black"), catalogue).light,
            "AMOLED black ships lightByDefault false, so null means NOT lit — that is the whole reason the field is nullable",
        )
    }

    /** An explicit choice beats the look's default in both directions. */
    @Test
    fun anExplicitLightBeatsTheLooksDefault() {
        assertTrue(
            PaperState.resolve(Paper(lookId = "amoled_black", light = true), catalogue).light,
            "the owner turned relief lighting on for a black paper, so it is on",
        )
        assertTrue(
            !PaperState.resolve(Paper(lookId = "off_white", light = false), catalogue).light,
            "the owner turned it off for a paper that would otherwise be lit, so it is off",
        )
    }

    /** With no look at all there is nothing to take a default from, so a flat paper is lit. */
    @Test
    fun aFlatPaperWithNoLookIsLit() {
        assertTrue(PaperState.resolve(Paper(), catalogue).light, "nothing to inherit from, so light is on")
    }

    // ---- the numbers ------------------------------------------------------------------------------

    /** `show` and `bite` pass straight through: they are the owner's two sliders and the engine's business. */
    @Test
    fun showAndBitePassThroughUntouched() {
        val r = PaperState.resolve(Paper(show = 0.35f, bite = 0.6f), catalogue)
        assertEquals(0.35f, r.show)
        assertEquals(0.6f, r.bite)
    }

    /**
     * **`scale` is CLAMPED here, not validated in `DocOps`.** The specialist's answer on JB-9.05
     * question 3 moved the 0.25..4 window out of validation and into this clamp, so a file written by
     * an older Joy Brush (whose rule was "over 0 and at most 64") still opens and still gets a sane
     * paper. The two ends are asserted separately because a clamp that only worked at one end would
     * pass a test that only checked the other.
     *
     * NaN is the third case and the one a `coerceIn` gets for free: `coerceIn` on a NaN returns the NaN,
     * so it is handled by hand rather than left to the arithmetic.
     */
    @Test
    fun theScaleIsClampedToAQuarterThroughFour() {
        assertEquals(1f, PaperState.resolve(Paper(textureScale = 1f), catalogue).scale, "1 needs no clamping")
        assertEquals(0.25f, PaperState.resolve(Paper(textureScale = 0.0001f), catalogue).scale, "a tiny scale is clamped up to a quarter")
        assertEquals(0.25f, PaperState.resolve(Paper(textureScale = 0f), catalogue).scale, "zero is clamped, not refused")
        assertEquals(4f, PaperState.resolve(Paper(textureScale = 64f), catalogue).scale, "an old file's 64 clamps down to 4")
        assertEquals(4f, PaperState.resolve(Paper(textureScale = 1e9f), catalogue).scale, "a huge scale clamps to 4")
        assertEquals(
            1f,
            PaperState.resolve(Paper(textureScale = Float.NaN), catalogue).scale,
            "NaN must not survive into the renderer; a NaN scale is a paper that draws nothing",
        )
    }

    /**
     * A paper whose every id is unknown still resolves to something drawable: flat white, smooth, lit,
     * show 1. **This is the shape a rescue needs**, and it is asserted as a whole rather than field by
     * field because the guarantee is "a drawing opens", not "these six numbers are right".
     */
    @Test
    fun aPaperWithNoRecognisedIdStillResolvesToSomethingDrawable() {
        val r = PaperState.resolve(Paper(lookId = "gone", textureId = "also_gone"), catalogue)
        assertNull(r.look)
        assertNull(r.surface)
        assertEquals(0xFFFFFFFF.toInt(), r.baseArgb)
        assertEquals(1f, r.scale)
        assertEquals(1f, r.show)
        assertEquals(1f, r.bite)
        assertTrue(r.light)
        assertNotNull(r, "resolve always returns a paper, even a paper that is only a colour")
    }
}
