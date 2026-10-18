package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

/**
 * Editable lines v1.2 H13: pixel slabs go to disk (Codex's v11 layout) and come back as the same items, with no line
 * replayed. A cel of lines keeps looks; a cel of slabs alone keeps none and is composed on open.
 */
class LineSlabSaveTest {
    private val brush = LineArchiveFixture.brush
    private fun tile(v: Int) = LineArchiveFixture.tile(v)
    private val record = StrokeRecord("a", brush.id, 1, 0f, 1f,
        listOf(PenSample(20f, 20f, 0.0, 1f), PenSample(24f, 20f, 16.0, 0.5f)), seq = 1)

    /** A drawing whose screen tiles are the live pixels, before the items are declared ([LineSaveMapping] adds them). */
    private fun plain(): JbContents {
        val doc = JbDocument(id = "d", name = "Slabs",
            boards = listOf(Board("b", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 512, 512))),
            layers = listOf(
                Layer("ink", "Ink", LayerKind.PAINT, cels = listOf(Cel("c1", tiles = listOf("0_0", "1_0")))),
                Layer("wash", "Wash", LayerKind.PAINT, cels = listOf(Cel("c2", tiles = listOf("0_0"))))))
        return JbContents(doc, mapOf(
            Triple("ink", "c1", "0_0") to tile(50), Triple("ink", "c1", "1_0") to tile(60),
            Triple("wash", "c2", "0_0") to tile(70)), emptyMap())
    }

    private fun saved(): JbContents = LineSaveMapping.apply(plain(), listOf(
        // Ink: a line over tile 0_0 (base 5) with a multiply slab at seq 2 on 0_0 and 1_0 (1_0's base is transparent).
        PlaneItems("ink", "c1", listOf(record), crossed = setOf("0_0", "1_0"), bases = mapOf("0_0" to tile(5)),
            slabs = mapOf(2L to SlabBytes("multiply", mapOf("0_0" to tile(9), "1_0" to tile(11))))),
        // Wash: slabs alone (a fill kept in order), no lines.
        PlaneItems("wash", "c2", emptyList(), crossed = setOf("0_0"), bases = mapOf("0_0" to tile(7)),
            slabs = mapOf(1L to SlabBytes("normal", mapOf("0_0" to tile(8))))),
        // A plane whose cel the drawing no longer has (a deleted frame) is history only.
        PlaneItems("ink", "gone", listOf(record), crossed = setOf("0_0"), bases = emptyMap(), slabs = emptyMap()),
    ), mapOf(brush.id to brush))

    private fun roundTrip(c: JbContents): JbContents =
        JbArchive.read(ByteArrayInputStream(ByteArrayOutputStream().also { JbArchive.write(it, c) }.toByteArray()), null)

    @Test fun theStackIsDeclaredAndTheTileFilesBecomeItsBottom() {
        val c = saved()
        val ink = c.doc.layers.first { it.id == "ink" }.cels.single()
        assertEquals(listOf(SlabRef("0_0", 0), SlabRef("0_0", 2, "multiply"), SlabRef("1_0", 0), SlabRef("1_0", 2, "multiply")), ink.slabs)
        assertEquals(listOf("0_0", "1_0"), ink.looks, "a cel of lines keeps the picture of every tile its items cross")
        assertContentEquals(tile(5), c.tiles.getValue(Triple("ink", "c1", "0_0")), "the tile file is the base")
        assertContentEquals(ByteArray(TILE_BYTES), c.tiles.getValue(Triple("ink", "c1", "1_0")), "a transparent bottom is written in full")
        assertContentEquals(tile(50), c.looks.getValue(Triple("ink", "c1", "0_0")))
        val wash = c.doc.layers.first { it.id == "wash" }.cels.single()
        assertTrue(wash.looks.isEmpty(), "slabs alone keep no look")
        assertNull(wash.strokesFile)
        assertFalse(c.strokes.keys.any { it.second == "gone" }, "a dead plane is never written")
    }

    @Test fun itReopensAsTheSameItemsWithNothingReplayed() {
        val back = roundTrip(saved())
        assertNull(LineOpenPlan.refusal(back))
        val plan = LineOpenPlan.of(back)
        assertEquals(setOf(Triple("ink", "c1", "0_0"), Triple("ink", "c1", "1_0"), Triple("wash", "c2", "0_0")), plan.itemTiles)
        assertEquals(setOf(Triple("ink", "c1", "0_0"), Triple("wash", "c2", "0_0")), plan.bases.keys, "a blank bottom is no base")
        assertEquals(setOf(Triple("wash", "c2", "0_0")), plan.composeKeys, "only slabs-alone tiles are composed on open")
        assertEquals("multiply", plan.slabs.getValue(SlabKey("ink", "c1", "1_0", 2)).first)
        assertContentEquals(tile(11), plan.slabs.getValue(SlabKey("ink", "c1", "1_0", 2)).second)
        assertEquals(listOf(record), plan.records.getValue("ink" to "c1"))
    }

    @Test fun stacksThisScreenCannotComposeAreRefusedInWords() {
        val c = saved()
        fun withSlabs(refs: List<SlabRef>) = c.copy(doc = c.doc.copy(layers = c.doc.layers.map { l ->
            if (l.id != "wash") l else l.copy(cels = l.cels.map { it.copy(slabs = refs) }) }))
        assertNotNull(LineOpenPlan.refusal(withSlabs(listOf(SlabRef("0_0", 0), SlabRef("0_0", 1, "overlay")))))
        assertNotNull(LineOpenPlan.refusal(withSlabs(listOf(SlabRef("0_0", 3), SlabRef("0_0", 4)))), "a stack starts at its tile")
        assertNull(LineOpenPlan.refusal(withSlabs(listOf(SlabRef("0_0", 0), SlabRef("0_0", 1, "behind")))))
    }
}
