package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.ENGINE_TUFT
import cc.joycreator.joybrush.core.brush.GrainSpec
import cc.joycreator.joybrush.core.brush.InputCurve
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TuftSpec
import cc.joycreator.joybrush.core.brush.TuftStroke
import cc.joycreator.joybrush.core.paint.UndoLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every brush's settings sheet (owner, 2026-09-30): every knob is wired, tuning is kept, the previews draw, Test is one undo. */
class BrushTuningTest {

    private fun sable() = BrushPreset(id = "joybrush.sable", name = "Sable", engine = ENGINE_TUFT, size = Param(18f))
    private fun ink() = BrushPreset(id = "joybrush.ink", name = "Ink", size = Param(6f, listOf(InputCurve(BrushInput.pressure, listOf(listOf(0f, 0.15f), listOf(1f, 1f))))))
    private fun pencil() = ink().copy(id = "joybrush.pencil", paperGrain = GrainSpec(enabled = true, depth = Param(0.8f)))
    private val all = listOf(sable(), ink(), pencil(), ink().copy(id = "s", engine = ENGINE_SMUDGE, version = 3), ink().copy(id = "f", engine = ENGINE_FILL, version = 2))

    /** A dead knob is worse than a missing one (owner's rule): every slider must move its brush, both ways, and stay valid. */
    @Test
    fun everyKnobOfEveryBrushMovesItsBrushAndKeepsItValid() {
        for (brush in all) {
            val knobs = BrushKnobs.forBrush(brush)
            assertTrue(knobs.isNotEmpty(), "${brush.id} has no settings")
            assertEquals(knobs.size, knobs.map { it.key }.toSet().size, "${brush.id} repeats a knob")
            for (k in knobs) {
                val lo = k.set(brush, 0f)
                val hi = k.set(brush, 1f)
                assertTrue(lo != hi, "${brush.id} ${k.key} changes nothing")
                assertEquals(0f, k.get(lo), 1e-4f, "${brush.id} ${k.key}")
                assertEquals(1f, k.get(hi), 1e-4f, "${brush.id} ${k.key}")
                for (p in listOf(lo, hi)) assertTrue(BrushValidate.validate(p).isEmpty(), "${brush.id} ${k.key}: ${BrushValidate.validate(p)}")
                assertTrue(k.hint.isNotBlank() && k.label.isNotBlank())
            }
        }
    }

    @Test
    fun everyTuftSettingHasAKnob() {
        val names = BrushValidate.tuftSliders(TuftSpec()).map { "tuft.${it.first}" }.toSet() + "tuft.tipPx"
        val keys = BrushKnobs.forBrush(sable()).map { it.key }.filter { it.startsWith("tuft.") }.toSet()
        assertEquals(names, keys)
    }

    @Test
    fun grainOnlyWhereThereIsGrain() {
        assertTrue(BrushKnobs.forBrush(pencil()).any { it.key == "stamp.grain" })
        assertTrue(BrushKnobs.forBrush(ink()).none { it.key == "stamp.grain" })
    }

    @Test
    fun tuningIsKeptAsSliderPositionsAndOnlyTouchesItsBrush() {
        val tuning = mapOf("joybrush.sable" to mapOf("tuft.dry" to 0.9f, "tuft.nonsense" to 0.2f), "joybrush.ink" to mapOf("stamp.hardness" to 0.3f))
        assertEquals(tuning, BrushTuning.decode(BrushTuning.encode(tuning)))
        assertEquals(0.9f, BrushTuning.apply(sable(), tuning).tuft.dry)
        assertEquals(0.3f, BrushTuning.apply(ink(), tuning).tip.hardness.base)
        assertEquals(pencil(), BrushTuning.apply(pencil(), tuning))
        assertEquals(emptyMap(), BrushTuning.decode("not json"))
        assertEquals(emptyMap(), BrushTuning.decode(null))
    }

    @Test
    fun theFirstTuningFormatIsCarriedOver() {
        val legacy = """{"joybrush.sable":{"dry":0.9,"spatter":0.1},"gone":{"dry":0.2}}"""
        val moved = BrushTuning.fromLegacyTuft(legacy, listOf(sable()))
        assertEquals(setOf("joybrush.sable"), moved.keys)
        val tuned = BrushTuning.apply(sable(), moved)
        assertEquals(0.9f, tuned.tuft.dry, 1e-4f)
        assertEquals(0.1f, tuned.tuft.spatter, 1e-4f)
    }

    @Test
    fun thePreviewAndTheTestSheetDrawWithEveryMode() {
        for (sheet in listOf(BrushPreviewStrokes.strokes(1000f, 320f), TuftTestSheet.strokes(500f, 400f))) {
            for (s in sheet) {
                assertTrue(s.size >= 2)
                assertTrue(s.zipWithNext().all { (a, b) -> b.timeMs >= a.timeMs })
                val t = TuftStroke(sable(), 1L)
                assertTrue((t.add(s) + t.finish()).isNotEmpty())
            }
        }
        // The preview walks the whole range: a hairline, pressed flat, and laid over.
        val samples = BrushPreviewStrokes.strokes(1000f, 320f).flatten()
        assertTrue(samples.any { it.pressure < 0.1f } && samples.any { it.pressure > 0.95f })
        assertTrue(samples.any { it.tilt > 1f } && samples.any { it.tilt < 0.1f })
        assertTrue(samples.all { it.x in -1f..1001f && it.y in -1f..321f })
    }

    @Test
    fun severalStepsFoldIntoOneUndoAndTheTilesInBetweenAreReleased() {
        val released = ArrayList<String>()
        val log = UndoLog<String>(1L shl 40, sizeOf = { 1L }, release = { released.add(it) })
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("L", 1L, null, "a1"))))
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("L", 1L, "a1", "a2"), UndoLog.TileChange("L", 2L, "b0", "b1"))))
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("L", 1L, "a2", "a3"))))
        log.mergeNewest(3)
        assertEquals(1, log.undoDepth)
        assertEquals(listOf("a1", "a2"), released)
        val byKey = log.undo()!!.changes.associateBy { it.key }
        assertEquals(null, byKey[1L]!!.before)
        assertEquals("a3", byKey[1L]!!.after)
        assertEquals("b0", byKey[2L]!!.before)
        assertEquals("b1", byKey[2L]!!.after)
    }
}
