package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.ENGINE_PUSH
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.ENGINE_TUFT
import cc.joycreator.joybrush.core.brush.GrainSpec
import cc.joycreator.joybrush.core.brush.InputCurve
import cc.joycreator.joybrush.core.brush.PaperResponse
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TuftSpec
import cc.joycreator.joybrush.core.brush.TuftStroke
import cc.joycreator.joybrush.core.brush.VERSION_PAPER
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
        val names = BrushValidate.tuftSliders(TuftSpec()).map { "tuft.${it.first}" }.toSet() + "tuft.tipPx" + "tuft.grazeAtPoint"
        val keys = BrushKnobs.forBrush(sable()).map { it.key }.filter { it.startsWith("tuft.") }.toSet()
        assertEquals(names, keys)
    }

    @Test
    fun grainOnlyWhereThereIsGrain() {
        assertTrue(BrushKnobs.forBrush(pencil()).any { it.key == "stamp.grain" })
        assertTrue(BrushKnobs.forBrush(ink()).none { it.key == "stamp.grain" })
    }

    // ---- the paper response (JB-9.09) ---------------------------------------------------------

    /**
     * The three paper sliders, in the position Decision 2 fixes: after the engine's own knobs and
     * before Smoothing. The list is read as a whole because the position IS the product — a person
     * meets the knobs in the order they come back.
     */
    @Test
    fun thePaperSlidersSitAfterTheBrushsOwnKnobsAndBeforeSmoothing() {
        val keys = BrushKnobs.forBrush(pencil()).map { it.key }
        val at = keys.indexOf("paper.influence")
        assertEquals(
            listOf("paper.influence", "paper.directional", "paper.wet"),
            keys.subList(at, at + 3),
            "the three paper keys are consecutive and in the stated order. Pencil's whole list: $keys",
        )
        assertEquals("smoothing", keys[at + 3], "and Smoothing comes straight after them: $keys")
        // "after the engine's own knobs": the engine's own come first.
        assertTrue(keys.lastIndexOf("stamp.angleJitter") < at, "the engine's own knobs come first: $keys")
    }

    /**
     * Every engine that lays paint has the three; the three that do not lay paint do not.
     *
     * Smudge drags paint that is already there, push moves pixels, fill fills a shape — none of them
     * deposits anything, so there is nothing for the paper to hold or catch. Offering a slider that
     * cannot do anything is the dead control the owner's rule is against.
     */
    @Test
    fun thePaperSlidersAreOnEveryBrushThatLaysPaintAndOnNoOther() {
        val paperKeys = setOf("paper.influence", "paper.directional", "paper.wet")
        for (brush in listOf(ink(), pencil(), sable())) {
            val keys = BrushKnobs.forBrush(brush).map { it.key }
            assertEquals(paperKeys, keys.filter { it in paperKeys }.toSet(), "${brush.id} (${brush.engine}) has them: $keys")
        }
        for (brush in listOf(
            ink().copy(id = "s", engine = ENGINE_SMUDGE, version = 3),
            ink().copy(id = "p", engine = ENGINE_PUSH, version = 3),
            ink().copy(id = "f", engine = ENGINE_FILL, version = 2),
        )) {
            assertTrue(
                BrushKnobs.forBrush(brush).none { it.key in paperKeys },
                "${brush.id} (${brush.engine}) deposits nothing, so it has no paper sliders",
            )
        }
    }

    /**
     * Each slider's get/set round-trips, at every position a person can leave a slider in.
     *
     * 0.5 is in the list because the endpoints are the easy half: a get that forgot to invert, or a set
     * that clamped, shows up at an edge and not in the middle on some sliders.
     */
    @Test
    fun everyPaperSliderRoundTripsEveryPositionItCanBeLeftIn() {
        val read = mapOf(
            "paper.influence" to { p: BrushPreset -> p.paper.influence },
            "paper.directional" to { p: BrushPreset -> p.paper.directional },
            "paper.wet" to { p: BrushPreset -> p.paper.wet },
        )
        for (key in read.keys) {
            val knob = BrushKnobs.forBrush(pencil()).first { it.key == key }
            for (v in listOf(0f, 0.5f, 1f)) {
                val moved = knob.set(pencil(), v)
                assertEquals(v, knob.get(moved), 1e-4f, "$key at $v")
                assertEquals(v, read.getValue(key)(moved), 1e-4f, "$key moved its own field to $v")
                // A slider moves ONE field, and the file's own version; nothing else about the brush moves.
                assertEquals(
                    pencil().copy(paper = PaperResponse()),
                    moved.copy(paper = PaperResponse(), version = pencil().version),
                    "$key moved something that is not `paper`",
                )
            }
        }
    }

    /**
     * A slider moved on a brush whose file says version 1 must claim version 6, exactly as a bent
     * response curve does — otherwise the tuned brush cannot be written back out without lying about
     * the words it uses.
     */
    @Test
    fun aPaperSliderMovedOnAVersionOneBrushRestampsTheFile() {
        val old = ink().copy(version = 1)
        val moved = BrushKnobs.forBrush(old).first { it.key == "paper.influence" }.set(old, 0.5f)
        assertEquals(VERSION_PAPER, moved.version, "the tuned brush says `paper`, so it is a version-6 file")
        assertTrue(BrushJson.encode(moved).contains("\"version\": $VERSION_PAPER"))
        assertEquals(emptyList(), BrushValidate.validate(moved), "and the tuned brush loads clean")

        // Putting the slider back does NOT take the version back, and that is the house rule rather than
        // an oversight: a version is only ever stamped UPWARD (`BrushJson.versionFor` says the same), so a
        // file somebody has already re-saved as version 6 stays version 6. What a slider put back restores
        // is the default, so the section costs nothing again.
        val back = BrushKnobs.forBrush(moved).first { it.key == "paper.influence" }.set(moved, 0f)
        assertTrue(back.paper.isDefault, "back to 0/0/0")
        assertEquals(VERSION_PAPER, back.version, "a version is never taken back")
        // A brush whose paper is already the default and stays there costs no version: that is the case
        // the shipped eraser, smudge and fill are in, and it is why they are still version 1, 3 and 2.
        val untouched = ink().copy(version = 1)
        assertEquals(untouched, BrushKnobs.forBrush(untouched).first { it.key == "paper.influence" }.set(untouched, 0f))
    }

    /** The hint says what the slider will do, and says plainly that it does not do it yet (Decision 5). */
    @Test
    fun everyPaperSliderSaysWhenItStartsWorking() {
        for (knob in BrushKnobs.forBrush(pencil()).filter { it.key.startsWith("paper.") }) {
            assertTrue(knob.hint.isNotBlank(), "${knob.key} has no hint")
            assertTrue(
                knob.hint.trimEnd().endsWith("(takes effect when the paper engine lands)"),
                "${knob.key} must say the paper engine has not landed yet: ${knob.hint}",
            )
        }
        val labels = BrushKnobs.forBrush(pencil()).filter { it.key.startsWith("paper.") }.map { it.label }
        assertEquals(listOf("Paper", "Direction", "Wet"), labels, "the labels the owner chose: $labels")
    }

    /**
     * The validator's field names and the sliders' keys are the SAME three names, so a person who is
     * told `paper.directional 1.4 is outside 0..1` can find the slider that set it. The same bargain
     * [BrushValidate.tuftSliders] makes with `everyTuftSettingHasAKnob` above, for the paper section.
     */
    @Test
    fun theValidatorNamesTheSameThreeFieldsTheSlidersDo() {
        assertEquals(
            BrushValidate.paperSliders(PaperResponse()).map { "paper.${it.first}" }.toSet(),
            setOf("paper.influence", "paper.directional", "paper.wet"),
        )
        val keys = BrushKnobs.forBrush(pencil()).map { it.key }.filter { it.startsWith("paper.") }.toSet()
        assertEquals(
            BrushValidate.paperSliders(PaperResponse()).map { "paper.${it.first}" }.toSet(),
            keys,
            "BrushValidate.paperSliders and BrushKnobs' paper knobs have drifted apart: $keys",
        )
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
