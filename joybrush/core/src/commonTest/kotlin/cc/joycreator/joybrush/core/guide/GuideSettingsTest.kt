package cc.joycreator.joybrush.core.guide

import cc.joycreator.joybrush.core.doc.DocJson
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JB-2.12's pure half: the settings around the guides, which are never part of the drawing (Lead ruling R49). */
class GuideSettingsTest {

    private val ruler = Guide.Ruler(Pt(0.0, 0.0), Pt(100.0, 50.0))
    private val ellipse = Guide.EllipseTracer(Pt(10.0, 10.0), 40.0, 20.0, 0.5)
    private val persp2 = Guide.Perspective(listOf(Pt(-500.0, 300.0), Pt(1500.0, 300.0)))

    @Test
    fun tracersComeFirstThenTheDirectionGuidesAndNothingTwice() {
        val s = GuideSettings(grid = GuideSettings.DEFAULT_GRID, perspective = persp2, tracers = listOf(ruler, ruler, ellipse))
        assertEquals(listOf<Guide>(ruler, ellipse, GuideSettings.DEFAULT_GRID, persp2), s.all())
        assertTrue(GuideSettings.NONE.all().isEmpty())
        assertTrue(GuideSettings.NONE.isEmpty)
    }

    @Test
    fun nonsenseIsRefusedInWordsNamingTheGuide() {
        assertNull(GuideSettings(grid = GuideSettings.DEFAULT_GRID, perspective = persp2, tracers = listOf(ruler)).refusalFor())
        assertNotNull(GuideSettings(grid = Guide.Grid(0.0)).refusalFor()).let { assertTrue(it.contains("grid")) }
        assertNotNull(GuideSettings(perspective = Guide.Perspective(emptyList())).refusalFor()).let { assertTrue(it.contains("0")) }
        val four = Guide.Perspective(List(4) { Pt(it.toDouble(), 0.0) })
        assertNotNull(GuideSettings(perspective = four).refusalFor()).let { assertTrue(it.contains("4")) }
        assertNotNull(GuideSettings(grid = Guide.Grid(10.0, Pt(Double.NaN, 0.0))).refusalFor())
        assertNotNull(GuideSettings(tracers = listOf(Guide.EllipseTracer(Pt(0.0, 0.0), 0.0, 5.0, 0.0))).refusalFor())
    }

    @Test
    fun theGridDoublesUntilItsLinesAreEightScreenPixelsApart() {
        // 100 doc px at zoom 0.2 is 20 screen px: fine as it is. At zoom 0.02 it is 2 px: doubled twice to 400 (8 px).
        assertEquals(100.0, GuideSettings.withMinScreenSpacing(Guide.Grid(100.0), 0.2f).spacing)
        assertEquals(400.0, GuideSettings.withMinScreenSpacing(Guide.Grid(100.0), 0.02f).spacing)
        assertEquals(100.0, GuideSettings.withMinScreenSpacing(Guide.Grid(100.0), 4f).spacing)
        // Exactly 8 screen px stops (the loop is `<`, not `<=`): 80 doc px at zoom 0.1.
        assertEquals(80.0, GuideSettings.withMinScreenSpacing(Guide.Grid(80.0), 0.1f).spacing)
    }

    @Test
    fun aZoomThatIsNotAPositiveNumberIsReadAsOne() {
        for (z in listOf(0f, -2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(8.0, GuideSettings.withMinScreenSpacing(Guide.Grid(1.0), z).spacing, "zoom $z")
        }
    }

    @Test
    fun theSettingsSurviveTheAppsPreferencesAndABrokenOneIsNothing() {
        val s = GuideSettings(grid = Guide.Grid(64.0, Pt(3.0, -4.0), 0.25), isometric = Guide.Isometric(30.0),
            perspective = persp2, tracers = listOf(ruler, ellipse), snapEnabled = false)
        assertEquals(s, GuideSettings.decode(s.encode()))
        assertEquals(GuideSettings.NONE, GuideSettings.decode(null))
        assertEquals(GuideSettings.NONE, GuideSettings.decode("grid 0 0 0 0"), "a grid of spacing 0 is refused, so nothing")
        assertEquals(GuideSettings(grid = Guide.Grid(10.0)), GuideSettings.decode("grid 10 0 0 0\nnonsense here\nruler 1 2 x 4"))
    }

    @Test
    fun aGuideIsNeverPartOfTheDocument() {
        // The document made with guides on and the one made with them off are the same bytes: a guide has nowhere to go.
        var n = 0
        val doc = DocOps.newDocument("d", "D", 100, 100) { "id-${n++}" }
        val withGuides = GuideSettings(grid = GuideSettings.DEFAULT_GRID, tracers = listOf(ruler))
        assertTrue(!withGuides.isEmpty)
        assertEquals(DocJson.encode(doc), DocJson.encode(doc.copy()))
    }

    // ── the symmetry ───────────────────────────────────────────────────────────

    @Test
    fun aSymmetrySurvivesTheAppsPreferencesAndABadOneIsSkippedWithoutTakingTheRest() {
        val sym = Guide.Symmetry(
            Pt(10.0, 20.0), Pt(110.0, 20.0), count = 4, kaleido = true, overlap = true, symmetric = false,
        )
        val s = GuideSettings(
            grid = Guide.Grid(64.0, Pt(3.0, -4.0), 0.25), isometric = Guide.Isometric(30.0),
            perspective = persp2, tracers = listOf(ruler, ellipse), symmetry = sym, snapEnabled = false,
        )
        assertEquals(s, GuideSettings.decode(s.encode()))
        // The symmetry is not one of the guides the snapper is given: it copies the brush, it does
        // not catch the stroke.
        assertTrue(s.all().none { it is Guide.Symmetry }, "the snapper must never see the symmetry")

        // A symmetry with a count outside 1..30 is skipped, and everything else survives it: a
        // refused setting takes the whole set with it, so the check has to be local.
        for (bad in listOf(0, 31)) {
            val text = "grid 10 0 0 0\npersp -500 300 1500 300\nruler 1 2 3 4\nellipse 5 6 7 8 0.5\nsym 0 0 100 0 $bad 0 0 1"
            val out = GuideSettings.decode(text)
            assertNull(out.symmetry, "a count of $bad was taken")
            assertEquals(Guide.Grid(10.0), out.grid, "the grid did not survive a count of $bad")
            assertEquals(persp2, out.perspective, "the perspective did not survive a count of $bad")
            assertEquals(
                listOf(
                    Guide.Ruler(Pt(1.0, 2.0), Pt(3.0, 4.0)),
                    Guide.EllipseTracer(Pt(5.0, 6.0), 7.0, 8.0, 0.5),
                ),
                out.tracers,
                "the tracers did not survive a count of $bad",
            )
        }
        // A symmetry whose ends are the same point, or not numbers at all, is skipped as well.
        val dot = GuideSettings.decode("grid 10 0 0 0\nsym 5 5 5 5 2 0 0 1")
        assertNull(dot.symmetry, "a dot is not a line")
        assertEquals(Guide.Grid(10.0), dot.grid, "the grid did not survive a dot")
        val rubbish = GuideSettings.decode("grid 10 0 0 0\nsym NaN 0 100 0 2 0 0 1")
        assertNull(rubbish.symmetry, "a point that is not a number is not a point")
        assertEquals(Guide.Grid(10.0), rubbish.grid, "the grid did not survive rubbish")
    }

    @Test
    fun aSymmetryIsRefusedInWordsNamingIt() {
        assertNotNull(GuideSettings(symmetry = Guide.Symmetry(Pt(5.0, 5.0), Pt(5.0, 5.0), count = 2)).refusalFor())
            .let { assertTrue(it.contains("symmetry"), "the refusal does not name the symmetry: $it") }
        assertNotNull(GuideSettings(symmetry = Guide.Symmetry(Pt(0.0, 0.0), Pt(10.0, 0.0), count = 31)).refusalFor())
            .let { assertTrue(it.contains("1 and 30"), "the refusal does not give the range: $it") }
        assertNotNull(GuideSettings(symmetry = Guide.Symmetry(Pt(Double.NaN, 0.0), Pt(10.0, 0.0), count = 2)).refusalFor())
        // A good symmetry is not refused.
        assertNull(GuideSettings(symmetry = Guide.Symmetry(Pt(0.0, 0.0), Pt(10.0, 0.0), count = 2)).refusalFor())
    }

    @Test
    fun aBrokenSymmetryLineIsSkippedAndTheRestOfTheSettingsSurviveIt() {
        // The reader drops any line with a token that is not a number: "true" is not a number, so
        // the whole symmetry line is skipped and the settings around it are untouched.
        val words = GuideSettings.decode("grid 10 0 0 0\npersp -500 300 1500 300\nsym 0 0 100 0 2 true false true")
        assertNull(words.symmetry, "a line with words in it was taken")
        assertEquals(Guide.Grid(10.0), words.grid, "the grid did not survive")
        assertEquals(persp2, words.perspective, "the perspective did not survive")
        // And a symmetry line with too few numbers in it is skipped as well.
        val short = GuideSettings.decode("grid 10 0 0 0\npersp -500 300 1500 300\nsym 0 0 100 0 2")
        assertNull(short.symmetry, "a symmetry line with too few numbers was taken")
        assertEquals(Guide.Grid(10.0), short.grid, "the grid did not survive")
        assertEquals(persp2, short.perspective, "the perspective did not survive")
    }

    @Test
    fun theCapOnTheCountIsOneRuleAndBothEndsOfItKnowIt() {
        // The cap is [SymmetryMath.MAX_COUNT], and it is the SAME number for the writer and the
        // reader: the settings refuse a count over it, and the reader skips a line that asks for
        // one, so the two can never drift apart.
        for (bad in listOf(0, 31, 1_000_000_000)) {
            val refusal = GuideSettings(symmetry = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = bad)).refusalFor()
            assertNotNull(refusal, "a count of $bad was accepted")
            assertTrue(refusal.contains("symmetry"), "the refusal does not name the symmetry: $refusal")
            assertTrue(
                refusal.contains("1 and ${SymmetryMath.MAX_COUNT}"),
                "the refusal does not give the range: $refusal",
            )
            val out = GuideSettings.decode("grid 10 0 0 0\nsym 0 0 100 0 $bad 0 0 1")
            assertNull(out.symmetry, "a count of $bad was taken by the reader")
            assertEquals(Guide.Grid(10.0), out.grid, "the grid did not survive a count of $bad")
        }
        // A count inside the cap is taken by both ends.
        val good = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = SymmetryMath.MAX_COUNT)
        assertNull(GuideSettings(symmetry = good).refusalFor())
        assertEquals(good, GuideSettings.decode("sym 0 0 100 0 ${SymmetryMath.MAX_COUNT} 0 0 1").symmetry)
        // And a count that is not a whole number of copies is not a count at all.
        val half = GuideSettings.decode("grid 10 0 0 0\nsym 0 0 100 0 2.5 0 0 1")
        assertNull(half.symmetry, "a count of 2.5 was taken")
        assertEquals(Guide.Grid(10.0), half.grid, "the grid did not survive a count of 2.5")
    }

    @Test
    fun aSymmetryNeverReachesTheDocument() {
        var n = 0
        val doc = DocOps.newDocument("d", "D", 100, 100) { "id-${n++}" }
        val withGuides = GuideSettings(
            grid = GuideSettings.DEFAULT_GRID, tracers = listOf(ruler),
            symmetry = Guide.Symmetry(Pt(0.0, 0.0), Pt(10.0, 10.0), count = 4),
        )
        assertTrue(!withGuides.isEmpty)
        val json = DocJson.encode(doc)
        assertTrue(!json.contains("sym"), "the symmetry reached the document")
        assertEquals(json, DocJson.encode(doc.copy()))
    }

    @Test
    fun theOverlayNeverHasToDrawMoreThanTheCap() {
        val lines = GuideLines.visible(Guide.Grid(0.001), floatArrayOf(0f, 0f, 1000f, 2000f), 1f)
        assertTrue(lines.size <= GuideLines.MAX_SEGMENTS, "${lines.size} segments")
    }
}
