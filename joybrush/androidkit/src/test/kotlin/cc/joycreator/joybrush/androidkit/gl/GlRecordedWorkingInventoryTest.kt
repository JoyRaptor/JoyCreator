package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.watercolour.WatercolourPass
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.vector.RecordedCel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reflection-seeded names test CPU inventory only; it does not prove GPU allocation formats. */
class GlRecordedWorkingInventoryTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T

    private fun put(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.set(owner, value)

    private fun engine() = GlPaintEngine().also { it.initWith {}; it.addLayer("paint") }

    private fun layer(engine: GlPaintEngine): Any =
        field<LinkedHashMap<String, Any>>(engine, "localLayers").getValue("paint")

    private fun mask(owner: Any): Any = owner.javaClass.getDeclaredConstructor(
        String::class.java, java.lang.Boolean.TYPE, HashMap::class.java, Any::class.java,
    ).also { it.isAccessible = true }.newInstance("paint#mask", true, HashMap<Long, Int>(), Any())

    @Test fun inventoryDeduplicatesRealTileOwnersAndExcludesStagingAndFixedResources() {
        val engine = engine()
        val owner = layer(engine)
        val rgba8 = Tiles.SIZE.toLong() * Tiles.SIZE * 4
        val rgba16f = Tiles.SIZE.toLong() * Tiles.SIZE * MediaStores.BYTES_PER_TEXEL

        field<MutableMap<Long, Int>>(owner, "tiles")[0] = 10
        field<MutableMap<String, HashMap<Long, Int>>>(owner, "cels")["frame"] = hashMapOf(1L to 11)
        field<MutableMap<Long, Int>>(owner, "projected")[2] = 12
        field<MutableMap<String, HashMap<Long, Int>>>(owner, "floats")[MediaStores.GROUND] = hashMapOf(3L to 13)
        field<MutableMap<String, HashMap<Long, Int>>>(owner, "floats")["p0"] = hashMapOf(4L to 14)
        field<MutableMap<String, HashMap<String, HashMap<Long, Int>>>>(owner, "celFloats")["frame"] =
            hashMapOf("p1" to hashMapOf(5L to 15))

        val mask = mask(owner)
        field<MutableMap<Long, Int>>(mask, "tiles")[6] = 16
        put(owner, "mask", mask)

        engine.undo.push(UndoLog.Step(listOf(
            UndoLog.TileChange("paint", 7L, 17, null),
            UndoLog.TileChange("paint", 8L, 18, 19),
        )))

        val floats = field<MutableSet<Int>>(engine, "floatNames")
        floats.addAll(listOf(14, 15, 18, 19, 21, 51)) // 19 and 51 are registry-only and have no owner.
        field<ArrayDeque<Int>>(engine, "freeLayerTex").addLast(20)
        field<ArrayDeque<Int>>(engine, "freeFloatTex").addLast(21)
        field<ArrayDeque<Int>>(engine, "freeStrokeTex").addLast(22)
        field<ArrayDeque<Int>>(engine, "freeSmudgeTex").addLast(23)
        field<MutableMap<Long, Int>>(engine, "strokeTiles")[8] = 24

        val wc = WatercolourPass(ShaderLibrary { error("No shader access in metadata test") })
        field<MutableMap<Long, Int>>(wc, "bands")[9] = 30
        field<MutableMap<Long, Int>>(wc, "plain")[10] = 31
        put(engine, "wcPass", wc)

        // A raw slab duplicates the RGBA16F pool name. The conservative max is one RGBA16F tile.
        val raw = RecordedCel.fromPixels("paint", null, mapOf(11L to 21))
        val sourcePlanes: Map<Pair<String, String?>, RecordedCel<Int>> = mapOf(("paint" to null) to raw)

        val ledger = field<RecordedWorkingTileLedger>(engine, "recordedWorkingTiles")
        val epoch = field<Long>(engine, "physicalContextEpoch")
        ledger.register(epoch, listOf(50), rgba8)

        // Fixed/screen resources are deliberately outside the mutable tile inventory.
        put(engine, "lookTexture", 40)
        put(engine, "surfaceTexture", 41)
        put(engine, "fluidTexture", 42)
        put(engine, "clearTex", 43)
        put(engine, "whiteTex", 44)
        engine.compositor.target = 45
        engine.compositor.backdrop = 46

        val actual = engine.recordedWorkingTileBytes(sourcePlanes)
        val expected = mapOf(
            10 to rgba8, 11 to rgba8, 12 to rgba8, 13 to rgba8, 14 to rgba16f, 15 to rgba16f,
            16 to rgba8, 17 to rgba8, 18 to rgba16f, 20 to rgba8, 21 to rgba16f,
            22 to Tiles.SIZE.toLong() * Tiles.SIZE, 23 to rgba8, 24 to Tiles.SIZE.toLong() * Tiles.SIZE,
            30 to rgba16f, 31 to rgba16f,
        )
        assertEquals(expected, actual)
        assertFalse(50 in actual, "Staged ledger names are accounted separately from actual owners")
        assertFalse(51 in actual, "A float registry entry alone is not an owner")
        assertTrue((40..46).none { it in actual }, "Paper, clear/white, and screen compositor targets are excluded")
    }

    @Test fun freeStrokeAndSmudgePoolCostsFollowCurrentR16fPrecision() {
        val engine = engine()
        val area = Tiles.SIZE.toLong() * Tiles.SIZE
        put(engine, "strokeInternal", 0x822D) // GL_R16F; host tests have no android.jar runtime.
        put(engine, "smudgeInternal", 0x881A) // GL_RGBA16F.
        field<ArrayDeque<Int>>(engine, "freeStrokeTex").addLast(61)
        field<ArrayDeque<Int>>(engine, "freeSmudgeTex").addLast(62)

        assertEquals(mapOf(61 to area * 2, 62 to area * 8), engine.recordedWorkingTileBytes())
    }
}
