package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.ENGINE_TUFT
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TuftSpec
import cc.joycreator.joybrush.core.brush.TuftStroke
import cc.joycreator.joybrush.core.paint.UndoLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tuning sheet's parts (R9): every knob is wired, tuning round-trips, Test is one undo. */
class TuftTuningTest {

    /** A dead knob is worse than a missing one: every slider must move exactly its own number. */
    @Test
    fun everyTuftSettingHasAKnobAndEveryKnobMovesItsOwnSetting() {
        val names = BrushValidate.tuftSliders(TuftSpec()).map { it.first }.toSet() + "tipPx"
        assertEquals(names, TuftKnobs.all.map { it.key }.toSet())
        for (knob in TuftKnobs.all) {
            val lo = knob.set(TuftSpec(), 0f)
            val hi = knob.set(TuftSpec(), 1f)
            assertTrue(lo != hi, "${knob.key} changes nothing")
            assertEquals(0f, knob.get(lo), 1e-4f, knob.key)
            assertEquals(1f, knob.get(hi), 1e-4f, knob.key)
            // Only its own field moves.
            val changed = BrushValidate.tuftSliders(lo).zip(BrushValidate.tuftSliders(hi)).filter { it.first != it.second }
                .map { it.first.first } + (if (lo.tipPx != hi.tipPx) listOf("tipPx") else emptyList())
            assertEquals(listOf(knob.key), changed, "${knob.key} moves $changed")
            assertTrue(BrushValidate.validate(sable().copy(tuft = hi)).isEmpty(), "${knob.key} at the top is a valid brush")
            assertTrue(BrushValidate.validate(sable().copy(tuft = lo)).isEmpty(), "${knob.key} at the bottom is a valid brush")
            assertTrue(knob.hint.isNotBlank() && knob.label.isNotBlank())
        }
    }

    private fun sable() = BrushPreset(id = "joybrush.sable", name = "Sable", engine = ENGINE_TUFT, size = Param(18f))

    @Test
    fun tuningRoundTripsAndOnlyTouchesTuftBrushes() {
        val tuned = mapOf("joybrush.sable" to TuftSpec(dry = 0.9f, spatter = 0f))
        assertEquals(tuned, TuftTuning.decode(TuftTuning.encode(tuned)))
        assertEquals(0.9f, TuftTuning.apply(sable(), tuned).tuft.dry)
        val stamp = BrushPreset(id = "joybrush.sable", name = "Not a tuft", size = Param(4f))
        assertEquals(stamp, TuftTuning.apply(stamp, tuned))
        assertEquals(emptyMap(), TuftTuning.decode("not json"))
        assertEquals(emptyMap(), TuftTuning.decode(null))
    }

    @Test
    fun theTestSheetDrawsWithTheSable() {
        val strokes = TuftTestSheet.strokes(500f, 400f)
        assertTrue(strokes.size > 20)
        for (s in strokes) {
            assertTrue(s.size >= 2)
            assertTrue(s.all { it.x in -1f..501f && it.y in -1f..401f && it.pressure in 0f..1f })
            assertTrue(s.zipWithNext().all { (a, b) -> b.timeMs >= a.timeMs })
            val t = TuftStroke(sable(), 1L)
            assertTrue((t.add(s) + t.finish()).isNotEmpty())
        }
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
        val step = log.undo()!!
        val byKey = step.changes.associateBy { it.key }
        assertEquals(null, byKey[1L]!!.before)
        assertEquals("a3", byKey[1L]!!.after)
        assertEquals("b0", byKey[2L]!!.before)
        assertEquals("b1", byKey[2L]!!.after)
    }
}
