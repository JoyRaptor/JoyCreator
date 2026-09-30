package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.InputCurve
import cc.joycreator.joybrush.core.brush.Param
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JB-2.01's pure half: where the strip lands, what each tool remembers, how the drawer shelves brushes, the sample stroke. */
class ChromeCoreTest {

    private fun brush(id: String, name: String = id, engine: String = "stamp", blend: String = "normal", size: Float = 12f,
                      opacity: Float = 1f, source: String = "native") =
        BrushPreset(id = id, name = name, engine = engine, blend = blend, size = Param(size), opacity = Param(opacity), sourceFormat = source)

    private val ink = brush("joybrush.ink", "Ink", size = 6f)
    private val pencil = brush("joybrush.pencil", "Pencil", size = 4f)
    private val marker = brush("joybrush.marker", "Marker", size = 24f, opacity = 0.8f)
    private val air = brush("joybrush.softair", "Soft air", size = 120f)
    private val smudge = brush("joybrush.smudge", "Smudge", engine = "smudge", size = 30f)
    private val eraser = brush("joybrush.eraser", "Eraser", blend = "erase", size = 40f)
    private val fill = brush("fill", "Fill pen", engine = "fill", size = 8f)
    private val chalk = brush("abr.chalk 2", "Chalk", source = "abr", size = 50f)
    private val library = listOf(ink, pencil, marker, air, smudge, eraser, fill, chalk)

    // ── the strip ──────────────────────────────────────────────────────────────

    @Test
    fun theStripSnapsToTheNearerEdgeAndStaysOnScreen() {
        // A 1000 × 2000 free area, a 400 px strip: 1600 px of travel.
        assertEquals(StripPlacement(StripPlacement.Edge.LEFT, 0f), StripPlacement.dropAt(100f, 0f, 1000f, 2000f, 400f))
        assertEquals(StripPlacement(StripPlacement.Edge.RIGHT, 1f), StripPlacement.dropAt(900f, 5000f, 1000f, 2000f, 400f))
        // Centre at y = 1000 → top 800 → 800 / 1600.
        assertEquals(StripPlacement(StripPlacement.Edge.RIGHT, 0.5f), StripPlacement.dropAt(501f, 1000f, 1000f, 2000f, 400f))
        // The exact middle is the default side.
        assertEquals(StripPlacement.Edge.LEFT, StripPlacement.dropAt(500f, 1000f, 1000f, 2000f, 400f).edge)
    }

    @Test
    fun aStripTallerThanTheScreenOrABrokenNumberKeepsTheDefaultHeight() {
        assertEquals(StripPlacement.DEFAULT.along, StripPlacement.dropAt(10f, 10f, 1000f, 300f, 400f).along)
        assertEquals(StripPlacement.DEFAULT.along, StripPlacement.dropAt(10f, Float.NaN, 1000f, 2000f, 400f).along)
        assertEquals(StripPlacement.Edge.LEFT, StripPlacement.dropAt(Float.NaN, 10f, 1000f, 2000f, 400f).edge)
    }

    @Test
    fun theStripPlacementSurvivesThePreferencesFileAndABrokenOneIsTheDefault() {
        val p = StripPlacement(StripPlacement.Edge.RIGHT, 0.75f)
        assertEquals(p, StripPlacement.decode(p.encode()))
        assertEquals(StripPlacement.DEFAULT, StripPlacement.decode(null))
        assertEquals(StripPlacement.DEFAULT, StripPlacement.decode("UP 0.5"))
        assertEquals(StripPlacement.DEFAULT, StripPlacement.decode("LEFT NaN"))
        assertEquals(StripPlacement(StripPlacement.Edge.LEFT, 1f), StripPlacement.decode("LEFT 7"))
        // 1600 px of travel at 0.25 → the strip's top at 400.
        assertEquals(400f, StripPlacement(StripPlacement.Edge.LEFT, 0.25f).topPx(2000f, 400f))
    }

    // ── the tools ──────────────────────────────────────────────────────────────

    @Test
    fun eachToolTakesItsFirstBrushAndThePaintingToolIsInTheHand() {
        val m = ToolMemory.defaults(library)
        assertEquals(ToolSlot.BRUSH, m.active)
        assertEquals(SlotState("joybrush.ink", 6f, 1f), m.slots[ToolSlot.BRUSH])
        assertEquals("joybrush.smudge", m.slots[ToolSlot.SMUDGE]?.brushId)
        assertEquals("joybrush.eraser", m.slots[ToolSlot.ERASER]?.brushId)
    }

    @Test
    fun aLibraryWithNoSmudgeBrushHasNoSmudgeToolAndCannotSwitchToIt() {
        val m = ToolMemory.defaults(listOf(ink, eraser))
        assertNull(m.slots[ToolSlot.SMUDGE])
        assertEquals(m, m.activate(ToolSlot.SMUDGE))
        assertEquals(ToolSlot.ERASER, m.activate(ToolSlot.ERASER).active)
    }

    @Test
    fun eachToolRemembersItsOwnSize() {
        var m = ToolMemory.defaults(library).withSize(20f)
        m = m.activate(ToolSlot.ERASER).withSize(90f).withOpacity(0.5f)
        m = m.activate(ToolSlot.BRUSH)
        assertEquals(20f, m.current?.sizePx)
        assertEquals(1f, m.current?.opacity)
        assertEquals(SlotState("joybrush.eraser", 90f, 0.5f), m.slots[ToolSlot.ERASER])
    }

    @Test
    fun sizeAndOpacityAreClampedLikeTheDragClampsThem() {
        val m = ToolMemory.defaults(library)
        assertEquals(0.5f, m.withSize(0f).current?.sizePx)
        assertEquals(4096f, m.withSize(1e9f).current?.sizePx)
        assertEquals(6f, m.withSize(Float.NaN).current?.sizePx)
        assertEquals(0.01f, m.withOpacity(0f).current?.opacity)
        assertEquals(1f, m.withOpacity(3f).current?.opacity)
    }

    @Test
    fun aBrushPickedInTheDrawerGoesToItsOwnToolAtItsOwnSize() {
        val m = ToolMemory.defaults(library).withSize(50f)
        val picked = m.pick(air)
        assertEquals(ToolSlot.BRUSH, picked.active)
        assertEquals(SlotState("joybrush.softair", 120f, 1f), picked.current)
        val smudged = m.pick(smudge.copy(id = "other.smudge"))
        assertEquals(ToolSlot.SMUDGE, smudged.active)
        // The painting tool is left as it was.
        assertEquals(50f, smudged.slots[ToolSlot.BRUSH]?.sizePx)
        assertEquals(ToolSlot.ERASER, m.pick(eraser).active)
    }

    @Test
    fun sizingMovesOnlyTheBasesSoAPressureCurveKeepsItsShape() {
        val curve = listOf(InputCurve(BrushInput.pressure, listOf(listOf(0f, 0.2f), listOf(1f, 1f))))
        val p = ink.copy(size = Param(6f, curve), opacity = Param(1f, curve))
        val s = ToolMemory.sized(p, 30f, 0.4f)
        assertEquals(Param(30f, curve), s.size)
        assertEquals(Param(0.4f, curve), s.opacity)
        assertEquals(p.id, s.id)
    }

    @Test
    fun presetFromIsTheRememberedBrushAtTheRememberedSize() {
        val m = ToolMemory.defaults(library).withSize(33f).withOpacity(0.25f)
        val p = m.presetFrom(library)!!
        assertEquals("joybrush.ink", p.id)
        assertEquals(33f, p.size.base)
        assertEquals(0.25f, p.opacity.base)
        assertNull(m.presetFrom(listOf(pencil)))
    }

    @Test
    fun theToolsSurviveThePreferencesFileEvenWithSpacesInABrushId() {
        val m = ToolMemory.defaults(library).pick(chalk).withSize(77f).activate(ToolSlot.ERASER).withOpacity(0.5f)
        val back = ToolMemory.decode(m.encode(), library)
        assertEquals(m, back)
        assertEquals("abr.chalk 2", back.slots[ToolSlot.BRUSH]?.brushId)
    }

    @Test
    fun aRememberedBrushThatIsGoneOrInTheWrongToolFallsBackToTheDefault() {
        val text = "active SMUDGE\nBRUSH 9.0 1.0 gone.brush\nERASER 5.0 1.0 joybrush.ink\nnonsense line"
        val m = ToolMemory.decode(text, library)
        assertEquals(ToolSlot.SMUDGE, m.active)
        assertEquals(ToolMemory.defaults(library).slots[ToolSlot.BRUSH], m.slots[ToolSlot.BRUSH])
        assertEquals("joybrush.eraser", m.slots[ToolSlot.ERASER]?.brushId)
        assertEquals(ToolMemory.defaults(library), ToolMemory.decode("", library))
        // A tool in the hand that the library cannot fill is not left in the hand.
        assertEquals(ToolSlot.BRUSH, ToolMemory.decode("active SMUDGE", listOf(ink)).active)
    }

    // ── the drawer ─────────────────────────────────────────────────────────────

    @Test
    fun theBuiltInBrushesLandOnTheShelvesAPainterExpects() {
        assertEquals(BrushShelf.Kind.INKS, BrushShelf.kindOf(ink))
        assertEquals(BrushShelf.Kind.PENCILS, BrushShelf.kindOf(pencil))
        assertEquals(BrushShelf.Kind.MARKERS, BrushShelf.kindOf(marker))
        assertEquals(BrushShelf.Kind.AIRBRUSH, BrushShelf.kindOf(air))
        assertEquals(BrushShelf.Kind.SMUDGE, BrushShelf.kindOf(smudge))
        assertEquals(BrushShelf.Kind.ERASERS, BrushShelf.kindOf(eraser))
        assertEquals(BrushShelf.Kind.FILL, BrushShelf.kindOf(fill))
        assertEquals(BrushShelf.Kind.IMPORTED, BrushShelf.kindOf(chalk))
        assertEquals(BrushShelf.Kind.PAINT, BrushShelf.kindOf(brush("oil", "Oil")))
        // What a brush does outranks where it came from.
        assertEquals(BrushShelf.Kind.ERASERS, BrushShelf.kindOf(eraser.copy(sourceFormat = "abr")))
        // "Hair" is not an airbrush.
        assertEquals(BrushShelf.Kind.PAINT, BrushShelf.kindOf(brush("hair", "Hair")))
    }

    @Test
    fun onlyShelvesThatHoldABrushAreShownAllFirst() {
        val s = BrushShelf.shelves(listOf(marker, ink, chalk))
        assertEquals(listOf(BrushShelf.Kind.ALL, BrushShelf.Kind.INKS, BrushShelf.Kind.MARKERS, BrushShelf.Kind.IMPORTED), s.map { it.first })
        assertEquals(listOf(marker, ink, chalk), s[0].second)
        assertTrue(BrushShelf.shelves(emptyList()).isEmpty())
    }

    // ── the sample stroke ──────────────────────────────────────────────────────

    @Test
    fun theSampleStrokeFitsItsRowAndIsTheSameEveryTime() {
        val a = SampleStroke.dabs(air, 200f, 40f, displaySize = 16f)
        val b = SampleStroke.dabs(air, 200f, 40f, displaySize = 16f)
        assertTrue(a.isNotEmpty())
        assertEquals(a, b)
        for (d in a) {
            // Drawn 16 px wide at most: every dab inside the box by its radius.
            assertTrue(d.radius <= 8f + 1e-3f, "radius ${d.radius}")
            assertTrue(d.x - d.radius >= -1e-3f && d.x + d.radius <= 200f + 1e-3f, "x ${d.x}")
            assertTrue(d.y - d.radius >= -1e-3f && d.y + d.radius <= 40f + 1e-3f, "y ${d.y}")
        }
        // A hairline is not blown up to the row's height.
        val fine = SampleStroke.dabs(brush("fine", size = 2f), 200f, 40f, displaySize = 16f)
        assertTrue(fine.all { it.radius <= 1f + 1e-3f })
    }
}
