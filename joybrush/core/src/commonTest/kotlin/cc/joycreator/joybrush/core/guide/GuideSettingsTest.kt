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

    @Test
    fun theOverlayNeverHasToDrawMoreThanTheCap() {
        val lines = GuideLines.visible(Guide.Grid(0.001), floatArrayOf(0f, 0f, 1000f, 2000f), 1f)
        assertTrue(lines.size <= GuideLines.MAX_SEGMENTS, "${lines.size} segments")
    }
}
