package cc.joycreator.joybrush.core.brush

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JB-1.06 Decision 8 / R47 — the file format for smudge and push: brush version 3, engine words, two small sections.
 * The version rule under test is "a word needs the version that introduced it": a fill pen stays a version-2 file,
 * so a build that predates smudge can still open it.
 */
class SmudgeFormatTest {

    private fun preset(edit: (BrushPreset) -> BrushPreset): BrushPreset =
        edit(BrushPreset(id = "t", name = "T", size = Param(20f), version = 1))

    @Test
    fun theNewestVersionIsThreeAndItsWordsAreTheTwoNewEngines() {
        assertEquals(3, BRUSH_VERSION)
        assertEquals(2, VERSION_FILL)
        assertEquals(3, VERSION_SMUDGE)
        assertEquals("smudge", ENGINE_SMUDGE)
        assertEquals("push", ENGINE_PUSH)
        assertEquals(BRUSH_VERSION, BrushPreset(id = "b", name = "B", size = Param(1f)).version)
    }

    @Test
    fun aSmudgeOrPushBrushIsWrittenAsVersionThreeWithItsSection() {
        val smudge = preset { it.copy(engine = ENGINE_SMUDGE, smudge = SmudgeSpec(pickup = 0.6f, load = 0.25f)) }
        val text = BrushJson.encode(smudge)
        assertTrue(text.contains("\"version\": 3"), text)
        assertTrue(text.contains("\"engine\": \"smudge\""))
        assertTrue(text.contains("\"pickup\": 0.6") && text.contains("\"load\": 0.25"), text)
        assertEquals(smudge.copy(version = 3), BrushJson.decode(text))

        val push = preset { it.copy(engine = ENGINE_PUSH, push = PushSpec(amount = 0.4f)) }
        val pushText = BrushJson.encode(push)
        assertTrue(pushText.contains("\"version\": 3") && pushText.contains("\"amount\": 0.4"), pushText)
        assertEquals(push.copy(version = 3), BrushJson.decode(pushText))
    }

    @Test
    fun aFillPenStaysVersionTwoAndAnOrdinaryBrushStaysVersionOne() {
        assertTrue(BrushJson.encode(preset { it.copy(engine = ENGINE_FILL) }).contains("\"version\": 2"))
        assertTrue(BrushJson.encode(preset { it.copy(blend = BLEND_BEHIND) }).contains("\"version\": 2"))
        assertTrue(BrushJson.encode(preset { it }).contains("\"version\": 1"))
        // A brush from a later build is never downgraded by a round trip.
        assertTrue(BrushJson.encode(preset { it.copy(engine = ENGINE_SMUDGE, version = 9) }).contains("\"version\": 9"))
    }

    @Test
    fun aVersionTwoFileCannotSaySmudgeOrPushAndTheSentenceNamesVersionThree() {
        for (engine in listOf(ENGINE_SMUDGE, ENGINE_PUSH)) {
            val v2 = BrushJson.encode(preset { it.copy(engine = engine) }).replace("\"version\": 3", "\"version\": 2")
            val thrown = assertFailsWith<BrushException>("a v2 file saying $engine must not decode") { BrushJson.decode(v2) }
            assertEquals("engine \"$engine\" needs brush version 3", thrown.message)
        }
        // The same sentence, from validation, for a preset that never came from a file.
        assertEquals(
            listOf("engine \"smudge\" needs brush version 3"),
            BrushValidate.validate(preset { it.copy(version = 2, engine = ENGINE_SMUDGE) }),
        )
        // A fill pen at version 2 is fine on a version-3 build.
        assertEquals(emptyList(), BrushValidate.validate(preset { it.copy(version = 2, engine = ENGINE_FILL) }))
    }

    @Test
    fun theRatesAreRangedAndNaNIsRefused() {
        val ok = preset { it.copy(version = 3, engine = ENGINE_SMUDGE, smudge = SmudgeSpec(0.5f, 0.15f)) }
        assertEquals(emptyList(), BrushValidate.validate(ok))
        val bad = BrushValidate.validate(ok.copy(smudge = SmudgeSpec(pickup = 1.5f, load = Float.NaN)))
        assertEquals(1, bad.size, bad.toString())
        assertTrue(bad[0].contains("smudge.pickup 1.5") && bad[0].contains("smudge.load NaN"), bad[0])

        for (amount in listOf(0f, -0.2f, 1.5f, Float.NaN)) {
            val problems = BrushValidate.validate(ok.copy(engine = ENGINE_PUSH, push = PushSpec(amount)))
            assertEquals(1, problems.size, "amount $amount: $problems")
            assertTrue(problems[0].startsWith("push.amount"), problems[0])
        }
        assertEquals(emptyList(), BrushValidate.validate(ok.copy(engine = ENGINE_PUSH, push = PushSpec(1f))))
    }

    @Test
    fun theTwoEngineWordsAreKnownWordsNotTypos() {
        val p = preset { it.copy(version = 3, engine = ENGINE_PUSH) }
        assertTrue(BrushValidate.validate(p).none { it.startsWith("unknown:") })
        assertTrue(BrushValidate.validate(p.copy(engine = "smuge")).any { it.startsWith("unknown:") && it.contains("smuge") })
    }
}
