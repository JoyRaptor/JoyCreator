package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import kotlin.test.*

/** Exercises the real reflection inventory without a driver; device proof remains separate. */
class RecordedPromotionExternalTextureInventoryTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T

    @Test fun localInventoryReadsActualAgreementAndEveryInitializedHolderWithoutGl() {
        val engine = GlPaintEngine().also { it.initWith {} }
        field<MutableMap<Triple<String, String?, Long>, Int>>(engine, "localAgreedLook")[Triple("paint", null, 0L)] = 71
        field<ArrayDeque<Int>>(engine, "freeLayerTex").addLast(72)
        val check = RecordedPromotionExternalTextureInventory.check(engine, listOf(71), listOf(72), listOf(73))
        assertEquals(setOf(71), check.sourceConflicts)
        assertEquals(setOf(72), check.cacheConflicts)
        assertTrue(check.independentLineConflicts.isEmpty())
        assertFalse(check.passed)
        assertEquals(setOf(71), check.groups.getValue("agreedLook"))
    }

    @Test fun boundInventoryReadsPublishedAgreementsRatherThanStaleLocalBacking() {
        val engine = GlPaintEngine().also { it.initWith {}; it.addLayer("paint") }
        val plane: Pair<String, String?> = "paint" to null
        val renderer = object : RecordedScene.Renderer<Int> {
            override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> = error("No replay")
            override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>): Int = error("No composition")
            override fun release(texture: Int) = error("No release")
        }
        engine.registerRecordedScene(RecordedScene.create(mapOf(plane to RecordedCel<Int>("paint", null)),
            emptyMap(), engine.undo, renderer))
        engine.bindRecordedPaintPublication()
        val publication = field<Any>(engine, "publishedPaint")
        field<MutableMap<Triple<String, String?, Long>, Int>>(publication, "agreements")[Triple("paint", null, 0L)] = 81
        field<MutableMap<Triple<String, String?, Long>, Int>>(engine, "localAgreedLook")[Triple("paint", null, 0L)] = 82
        val check = RecordedPromotionExternalTextureInventory.check(engine, listOf(81), emptyList(), listOf(82))
        assertEquals(setOf(81), check.sourceConflicts)
        assertTrue(check.independentLineConflicts.isEmpty())
        assertEquals(setOf(81), check.groups.getValue("agreedLook"))
    }
}
