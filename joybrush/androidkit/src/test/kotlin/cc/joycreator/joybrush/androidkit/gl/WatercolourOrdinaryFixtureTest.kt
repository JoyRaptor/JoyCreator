package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.VERSION_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.WatercolourSpec
import cc.joycreator.joybrush.core.brush.watercolourRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class WatercolourOrdinaryFixtureTest {
    private fun source(
        id: String = "source-wc",
        engine: String = ENGINE_WATERCOLOUR,
        blend: String = "normal",
        size: Float = 450f,
        watercolour: WatercolourSpec? = WatercolourSpec(
            size = 450.0, grain = .4, paperCut = .5, window = 2,
            dabImages = mutableListOf("dabs/a.png", "dabs/b.png"),
            sampleSeeds = mutableListOf(78234L, 78235L),
        ),
    ) = BrushPreset(
        id = id, name = "Fixture watercolor", version = VERSION_WATERCOLOUR,
        engine = engine, size = Param(size), flow = Param(.89f), opacity = Param(.8f),
        watercolour = watercolour, blend = blend, spacing = .15f,
    )

    @Test fun prepareLeavesSourceJsonUnchangedAndDetachesNestedLists() {
        val dabImages = mutableListOf("dabs/a.png", "dabs/b.png")
        val seeds = mutableListOf(78234L, 78235L)
        val original = source(watercolour = WatercolourSpec(
            size = 250.0, grain = .4, paperCut = .5, window = 2,
            dabImages = dabImages, sampleSeeds = seeds,
        ))
        val before = BrushJson.encode(original)

        val prepared = WatercolourOrdinaryFixture.prepare(original, newId = { "frozen-wc" })

        assertEquals(before, BrushJson.encode(original))
        dabImages[0] = "changed.png"
        seeds[0] = 999L
        val frozen = prepared.frozen.preset.watercolour!!
        assertEquals(listOf("dabs/a.png", "dabs/b.png"), frozen.dabImages)
        assertEquals(listOf(78234L, 78235L), frozen.sampleSeeds)
        assertNotSame(dabImages, frozen.dabImages)
        assertNotSame(seeds, frozen.sampleSeeds)
    }

    @Test fun recordsOnlyActualFixtureOverridesAndPreservesGenericRuntimeKnobs() {
        val original = source(size = 250f, watercolour = WatercolourSpec(
            size = 250.0, grain = .4, paperCut = .5, spacing = .15,
            backdrop = "paper/backdrop.png", gravityPool = .57,
        ))
        val prepared = WatercolourOrdinaryFixture.prepare(original, newId = { "frozen-wc" })
        val runtime = prepared.frozen.preset.watercolourRuntime()

        assertEquals(250.0, runtime.size)
        assertEquals(0.0, runtime.grain)
        assertEquals(0.0, runtime.paperCut)
        assertEquals(.89, runtime.opacity)
        assertEquals(.8, runtime.strokeOpacity)
        assertEquals(.15, runtime.spacing)
        assertEquals("paper/backdrop.png", runtime.backdrop)
        assertEquals(.57, runtime.gravityPool)
        assertEquals(setOf("watercolour.grain", "watercolour.paperCut"),
            prepared.overrides.map { it.field }.toSet())
        assertEquals(250.0, prepared.metadata()["fixtureSize"])
        assertEquals(0.0, prepared.metadata()["fixtureGrain"])
        assertEquals(0.0, prepared.metadata()["fixturePaperCut"])
    }

    @Test fun capsEffectiveRuntimeSizeAt300AndReportsTheCap() {
        val prepared = WatercolourOrdinaryFixture.prepare(source(), newId = { "frozen-wc" })
        val runtime = prepared.frozen.preset.watercolourRuntime()

        assertEquals(300.0, runtime.size)
        assertEquals(300f, prepared.frozen.preset.size.base)
        assertEquals("450.0", prepared.overrides.single { it.field == "runtime.size" }.before)
        assertEquals("300.0", prepared.overrides.single { it.field == "runtime.size" }.after)
        assertEquals(300.0, prepared.metadata()["fixtureSize"])
    }

    @Test fun normalAndMultiplyOperatorOverridesAreFrozenAndReported() {
        for (operator in listOf("normal", "multiply")) {
            val prepared = WatercolourOrdinaryFixture.prepare(
                source(blend = if (operator == "normal") "multiply" else "normal", size = 200f,
                    watercolour = WatercolourSpec(size = 200.0, grain = 0.0, paperCut = 0.0)),
                operator = operator,
                newId = { "frozen-$operator" },
            )
            assertEquals(operator, prepared.frozen.preset.blend)
            assertEquals(operator, prepared.metadata()["fixtureOperator"])
            assertEquals(operator, prepared.overrides.single { it.field == "blend" }.after)
        }
    }

    @Test fun recordIsRepeatableAndRetainsSeedFrameAndNegativeBoundarySamples() {
        val prepared = WatercolourOrdinaryFixture.prepare(
            source(size = 200f, watercolour = WatercolourSpec(size = 200.0, grain = 0.0, paperCut = 0.0)),
            newId = { "frozen-wc" },
        )
        val first = prepared.record()
        val second = prepared.record()

        assertEquals(first, second)
        assertEquals(78234L, first.seed)
        assertEquals(prepared.frozen.id, first.brushId)
        assertEquals(1L, first.seq)
        assertTrue(first.washFrame != null)
        assertTrue(first.washFrame!!.m02 != 0.0 || first.washFrame!!.m12 != 0.0)
        assertTrue(first.samples.any { it.x < 0f && it.y < 0f })
        assertTrue(first.samples.any { it.x >= 0f && it.y >= 0f })
        assertTrue(first.samples.any { it.x < 256f && it.x + 37f >= 256f },
            "the diagnostic's +37px move must cross another positive tile edge")
        assertTrue(first.samples.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs })
        assertFalse(first.samples.any { it.predicted })
        assertEquals(24, prepared.metadata()["fixtureRawSamples"])
        assertEquals(78234L, prepared.metadata()["fixtureSeed"])
        assertTrue(prepared.overrides.isEmpty(), "an already neutral fixture needs no invented override")
    }

    @Test fun invalidSourceNonWatercolourAndUnsupportedOperatorRefuseBeforeAllocation() {
        var allocations = 0
        fun allocate(): String { allocations++; return "frozen-wc" }

        assertFailsWith<IllegalArgumentException> {
            WatercolourOrdinaryFixture.prepare(source(engine = "stamp"), newId = ::allocate)
        }
        assertFailsWith<IllegalArgumentException> {
            WatercolourOrdinaryFixture.prepare(source(watercolour = null), newId = ::allocate)
        }
        assertFailsWith<IllegalArgumentException> {
            WatercolourOrdinaryFixture.prepare(
                source(size = 0f, watercolour = WatercolourSpec(size = 0.0)), newId = ::allocate,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            // grain is an override field: validation must still reject its corrupt original.
            WatercolourOrdinaryFixture.prepare(
                source(watercolour = WatercolourSpec(grain = Double.NaN)), newId = ::allocate,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            // Valid general brush size, outside the deliberately narrower ordinary-probe fixture.
            WatercolourOrdinaryFixture.prepare(
                source(size = .5f, watercolour = WatercolourSpec(size = .5)), newId = ::allocate,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WatercolourOrdinaryFixture.prepare(source(), operator = "screen", newId = ::allocate)
        }
        assertEquals(0, allocations)
    }
}
