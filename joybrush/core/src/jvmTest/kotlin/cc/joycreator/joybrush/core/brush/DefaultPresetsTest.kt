package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The default presets, read from the disk they actually live on (JB-1.07, wave one).
 *
 * **Every test here is a `jvmTest` test** because every one of them opens a file. `commonTest` cannot,
 * which is why `BrushTest` and `BrushDabberTest` carry byte-identical inline copies of `ink` and
 * `pencil` — and that is the reason this file adds NO copies: reading the file is the point, because a
 * file edited on disk cannot drift from a copy of itself unnoticed.
 *
 * **A preset is asserted by its PROPERTY, not by its number.** "The Marker cannot go darker than 0.9"
 * survives a tuning pass; "the Marker's flow is 0.7" only gets in the way of one. Where the property IS
 * a number, the number is read out of the DECODED preset rather than retyped, and the failure message
 * names both the brush and the number.
 *
 * **Every number in these three files is a PROVISIONAL T1 tuning value the owner judges on the Note 9.**
 * A preset is not finished because it validates; it is finished because a person drew with it. The
 * numbers most likely to move are listed in Q5 of `tasks/joybrush/specs/JB-1.07_default_presets.md`, and
 * the tests here are written so that moving one of them is a one-line change and not a test rewrite.
 *
 * The two wave-two folders (Smudge, Nudge) are named below only as an ABSENCE and in one check that
 * exists to fail if a stub ever lands. This build has no `"push"` engine word, so those presets cannot be
 * written at all today; they wait on JB-1.06, which owns the three files that add the word.
 *
 * The `index.txt` two-way set equality and the exact pill order are asserted in [ShippedBrushFilesTest]
 * instead of here — that class already walks this disk, and a second root-finder is a second thing to
 * get wrong.
 */
class DefaultPresetsTest {

    // ---- the fixture, so no test has to invent a board -------------------------------------------

    /** Fixed, so a run is a run. Arbitrary, and not a golden value. */
    private val seed = 20260929L

    /**
     * A straight stroke of 100 samples: 2 px per step, 8 ms per step (125 samples a second, a real pen
     * rate), upright, at a middling 0.5 pressure. `tilt` 0 is a READING ("upright"); azimuth and barrel
     * are left at their `PenSample` default, which is NaN — "this device cannot report it".
     */
    private fun straightStroke(n: Int = 100): List<PenSample> = (0 until n).map {
        PenSample(x = 2f * it, y = 0f, timeMs = 8.0 * it, pressure = 0.5f, tilt = 0f)
    }

    /** One preset drawn through the real `BrushDabber` and the real `DabPlacer`. */
    private class Laid(val preset: BrushPreset, val dabber: BrushDabber, val dabs: List<Dab>) {
        val count: Int get() = dabs.size
        val caps: List<Float> get() = dabs.map { it.cap }
        val flows: List<Float> get() = dabs.map { it.flow }
    }

    private fun draw(preset: BrushPreset, seed: Long = this.seed): Laid {
        val dabber = BrushDabber(preset, seed)
        val placer = DabPlacer(spacing = dabber.spacing, look = dabber::look)
        return Laid(preset, dabber, placer.add(straightStroke()))
    }

    /**
     * `[folder, preset]` for every shipped brush, read from disk and in folder order. A file that will
     * not load is re-thrown with its PATH in the message, because `decodeChecked`'s own text is
     * `brush.json cannot be used: …` and every test in this class would then fail saying the same
     * sentence about a file nobody could name.
     */
    private val shipped: List<Pair<String, BrushPreset>> by lazy {
        brushFolders().map { folder ->
            val file = File(folder, "brush.json")
            val preset = try {
                BrushJson.decodeChecked(file.readText())
            } catch (e: BrushException) {
                throw AssertionError("${file.path} must load clean, but: ${e.message}", e)
            }
            folder.name to preset
        }
    }

    private fun preset(id: String): BrushPreset =
        shipped.firstOrNull { it.second.id == id }?.second
            ?: error("no shipped preset with id $id; on disk: ${shipped.map { it.second.id }}")

    // ---- 1. the set, exactly -----------------------------------------------------------------------

    /**
     * The ids on disk are exactly the six that ship, plus Smudge and Nudge **if and only if** those two
     * folders exist. Conditional rather than a fixed list, so the suite is green both before and after
     * wave two and turns red when a brush is added to one place and not the other.
     *
     * `fill` is the bare id, not `joybrush.fill` — a pre-existing inconsistency with the other five
     * (R21 made the fill pen a brush, and renaming a shipped id is a compatibility decision, not a
     * tidy-up, and not this row's). Do not "correct" it here.
     */
    @Test
    fun theIdsOnDiskAreExactlyTheOnesThisRowShips() {
        val folders = brushFolders().map { it.name }.toSet()
        val ids = shipped.map { it.second.id }.toSet()

        val waveTwo = listOf("smudge", "nudge")
        val present = waveTwo.filter { folders.contains(it) }
        val absent = waveTwo.filterNot { folders.contains(it) }

        val expected = setOf(
            "joybrush.ink", "joybrush.pencil", "joybrush.marker",
            "joybrush.softair", "joybrush.eraser", "fill",
        ) + present.map { "joybrush.$it" }

        assertEquals(
            expected, ids,
            "the shipped ids do not match. ${if (present.isEmpty()) "" else "Wave-two folders are on disk: $present. "}" +
                "Wave-two folders not on disk (expected today — they wait on JB-1.06, which owns the " +
                "engine word they need): $absent. Folders on disk: $folders.",
        )
    }

    /**
     * **A stub must not be able to land in wave one's place.** The absence of Smudge and Nudge is a
     * property of this BUILD, not a promise: `BrushValidate.ENGINES` has no `"push"` word, so a Nudge
     * file written today is refused by name, and the cheapest way to pin that is to ask for the refusal.
     *
     * This is deliberately conditional on the folders being absent, because the day JB-1.06 lands and
     * `"push"` becomes a legal word, the assertion stops being true and must be deleted rather than
     * retyped. Nothing here creates those folders or a placeholder for them.
     */
    @Test
    fun waveTwoPresetsCannotBeWrittenByThisBuild() {
        val folders = brushFolders().map { it.name }.toSet()
        if (folders.contains("smudge") || folders.contains("nudge")) {
            return // JB-1.06 has landed; the premise of this test is gone and it is not the one to fix it.
        }
        val wouldBe = preset("joybrush.eraser").copy(id = "joybrush.nudge", name = "Nudge", engine = "push")
        val problems = BrushValidate.validate(wouldBe)
        assertTrue(
            problems.any { it.contains("engine \"push\"") },
            "this build accepted engine \"push\", so a Nudge preset can be written today and the " +
                "wave-two rows in the spec are out of date. Problems said: $problems",
        )
    }

    // ---- 4. the defining property of each brush, through the engine --------------------------------

    /**
     * **Ink does not darken on itself.** `wash` is the whole of that: `BrushDabber.look` returns
     * `cap = opacity` for every dab of a wash brush, so a stroke's contribution is capped at its own
     * ceiling and crossing the stroke a second time cannot make it blacker. Read through the placer, so
     * the first and the hundredth dab are compared as DABS rather than as fields.
     */
    @Test
    fun inkIsCappedAtItsOwnOpacityOnEveryDabSoItNeverDarkensOnItself() {
        val p = preset("joybrush.ink")
        assertEquals("wash", p.accumulate, "ink/brush.json must be a wash brush")
        val laid = draw(p)
        val ceiling = p.opacity.base
        val over = laid.caps.filter { it > ceiling + 1e-6f }
        assertTrue(over.isEmpty(), "ink/brush.json: ${over.size} of ${laid.count} dabs exceeded its own opacity ceiling $ceiling")
        assertEquals(
            laid.caps.first(), laid.caps.last(),
            "ink/brush.json: dab 1 carried cap ${laid.caps.first()} and dab ${laid.count} carried ${laid.caps.last()}",
        )
    }

    /**
     * **The Marker arrives at once, and cannot go past its ceiling.** Two halves, both read out of the
     * decoded file rather than retyped:
     *
     *  - every dab's cap EQUALS the file's own `opacity.base` (the property: a wash stroke is capped at
     *    its own ceiling), and that ceiling is at most 0.9 — the Marker's own number, and the tuning
     *    limit. Raise the file's `opacity` above 0.9 and this goes red, which is the point: the number
     *    is a decision somebody signed off, not a value a later edit should be free to change.
     *  - every dab's flow EQUALS the file's own `flow.base`, and that is at least 0.5. The threshold is
     *    half of full on purpose: Ink, the pen that is known to work, runs at 1, so a Marker's flow below
     *    0.5 is a brush that fades in over the first centimetre. "A Marker with a soft flow is a pale
     *    pen" — the owner judges that number on the Note 9, this only holds the floor.
     */
    @Test
    fun theMarkerArrivesAtOnceAndIsCappedAtItsOwnCeiling() {
        val p = preset("joybrush.marker")
        assertEquals("wash", p.accumulate, "marker/brush.json must be a wash brush")
        val laid = draw(p)
        val ceiling = p.opacity.base
        val flowBase = p.flow.base
        val said = "marker/brush.json: opacity.base $ceiling, flow.base $flowBase, ${laid.count} dabs"

        val worstCap = laid.caps.map { abs(it - ceiling) }.max()
        assertTrue(worstCap <= 1e-6f, "$said, and the worst dab's cap was off by $worstCap")
        val worstFlow = laid.flows.map { abs(it - flowBase) }.max()
        assertTrue(worstFlow <= 1e-6f, "$said, and the worst dab's flow was off by $worstFlow")

        assertTrue(ceiling <= 0.9f, "$said — a Marker's stroke may not exceed 0.9 opacity")
        assertTrue(flowBase >= 0.5f, "$said — a Marker's flow is under 0.5, so the colour fades in")
    }

    /**
     * **One Soft air dab is invisible and the whole stroke is not.** This is the airbrush arithmetic
     * written out rather than a number typed in: with `f = flow.base`, a pixel under `n` overlapping
     * dabs reaches `1 − (1 − f)ⁿ`.
     *
     * The dab count `n` is COMPUTED from the dabs the placer actually produced and is never written down
     * as a literal — a test that states a dab count is asserting the placer's internals, so a change to
     * `spacing` would then fail a test about the brush. The threshold the test derives for itself is
     * `n > ln(0.1) / ln(1 − f)`; at `f = 0.05` that is 45 dabs. The failure message names the count, so
     * a red test says "the stroke was only N dabs" and not "the airbrush is too weak".
     *
     * If somebody raises `flow` to make the airbrush "stronger", it is the FIRST half that goes red —
     * one dab stops being invisible — and the message says which half broke.
     */
    @Test
    fun oneSoftAirDabIsInvisibleAndTheWholeStrokeIsSolid() {
        val p = preset("joybrush.softair")
        assertEquals("buildup", p.accumulate, "softair/brush.json must be a build-up brush")
        val laid = draw(p)
        val n = laid.count
        val f = p.flow.base.toDouble()
        val said = "softair/brush.json: flow.base ${p.flow.base}, $n dabs in the fixture stroke"

        // Build-up is the other half of "airbrush": every dab is uncapped and the stroke's opacity is
        // applied once, on commit. This file's `opacity` has no inputs, so the value taken at the FIRST
        // dab IS the base — and `strokeOpacity` is what the commit will multiply the stroke by.
        assertTrue(laid.caps.all { it == 1f }, "$said, but not every dab was uncapped: ${laid.caps.toSet()}")
        assertEquals(p.opacity.base, laid.dabber.strokeOpacity, 1e-6f, "$said, and the commit opacity differs")

        val one = 1.0 - Math.pow(1.0 - f, 1.0)
        assertTrue(one < 0.1, "$said — a single dab reaches $one, which is a visible dot, not spray (f = $f)")
        val whole = 1.0 - Math.pow(1.0 - f, n.toDouble())
        val least = Math.floor(ln(0.1) / ln(1.0 - f)) + 1
        assertTrue(
            n >= least && whole > 0.9,
            "$said — the stroke reaches only $whole, under 0.9. The arithmetic needs " +
                "n > ln(0.1)/ln(1-f) = ${ln(0.1) / ln(1.0 - f)}, so at least $least dabs.",
        )
    }

    /**
     * **The Eraser clears in one pass.** `flow 1` with `opacity 1` is the whole of it: under `wash` that
     * is `cap = 1` on every dab, so a dab's own coverage is what reaches the layer, once, whole. It is
     * `engine "stamp"`, which is one of the two engines R20 allows on an INK layer.
     */
    @Test
    fun theEraserClearsInOnePass() {
        val p = preset("joybrush.eraser")
        assertEquals("erase", p.blend, "eraser/brush.json must say blend \"erase\"")
        assertEquals("stamp", p.engine, "eraser/brush.json must stay stamp, so it works on an INK layer")
        assertEquals(1f, p.flow.base, "eraser/brush.json: flow must be 1 so one pass clears")
        assertEquals(1f, p.opacity.base, "eraser/brush.json: opacity must be 1 so one pass clears")
        val laid = draw(p)
        val said = "eraser/brush.json: ${laid.count} dabs"
        assertTrue(laid.flows.all { it == 1f }, "$said, but a dab was not at full flow: ${laid.flows.toSet()}")
        assertTrue(laid.caps.all { it == 1f }, "$said, but a dab was capped: ${laid.caps.toSet()}")
    }

    /**
     * **Pencil is the brush the grain exists for** — the three fields that are the file's reason for
     * being: canvas-space paper grain that answers to tilt, and no dab-space tip texture. `BrushTest`
     * asserts the first two against an inline copy; this reads the file, so an edit on disk cannot pass
     * unnoticed. The tilt gradient is the owner's own idea and the reason JB-1.02 exists, so it is
     * asserted as a direction (> 0) and not as a number.
     */
    @Test
    fun pencilIsTheOneShippedBrushWithPaperGrainAndNoTipTexture() {
        val p = preset("joybrush.pencil")
        assertTrue(p.paperGrain.enabled, "pencil/brush.json: paperGrain must be on")
        assertTrue(!p.tipTexture.enabled, "pencil/brush.json: tipTexture must be off — the grain is paper, not nib")
        assertTrue(
            p.paperGrain.tiltGradient > 0f,
            "pencil/brush.json: paperGrain.tiltGradient is ${p.paperGrain.tiltGradient}; the tilt response is the owner's own idea",
        )
    }

    // ---- 5. the ranges every preset must sit in ----------------------------------------------------

    /**
     * `size.base` is inside the range `BrushValidate` enforces, tested against the constant R19 made
     * public for exactly this rather than a retyped 4096. `flow.base` and `opacity.base` are in 0..1
     * because **`BrushValidate` does not range those two today** — only `size` and `tip.hardness` are in
     * its `RANGED_BASES` — so this assertion is this suite's own and is currently the only thing standing
     * there. If a later row ranges them, this test goes red and says which sentence changed; it is left
     * in place on purpose rather than deleted.
     *
     * `size.base` is a document-px number at zoom 1. Nothing on screen scales it yet (JB-1.05b Q5), so
     * this is the one number in every file that is certain to move.
     */
    @Test
    fun everyShippedSizeIsUsableAndEveryFlowAndOpacityIsAFraction() {
        for ((folder, p) in shipped) {
            val said = "$folder/brush.json"
            assertTrue(
                p.size.base > 0f && p.size.base <= BrushValidate.MAX_SIZE_PX,
                "$said: size.base ${p.size.base} is outside 0..${BrushValidate.MAX_SIZE_PX}",
            )
            for ((name, v) in listOf("flow.base" to p.flow.base, "opacity.base" to p.opacity.base)) {
                assertTrue(v in 0f..1f, "$said: $name is $v, outside 0..1")
            }
        }
    }

    // ---- 6. nothing shares an id -------------------------------------------------------------------

    /**
     * Two files claiming one id would make the second unreachable in the pill, and the failure is a
     * MISSING BRUSH rather than an error. `ShippedBrushFilesTest` says this from the file walk; this says
     * it from the decoded ids, so the message can name the two FOLDERS involved.
     */
    @Test
    fun nothingSharesAnId() {
        val byId = shipped.groupBy { it.second.id }.filterValues { it.size > 1 }
        assertTrue(
            byId.isEmpty(),
            "these ids are claimed by more than one folder: ${byId.mapValues { (id, pairs) -> pairs.map { it.first } }}",
        )
    }

    // ---- 7. no preset is another preset with a different name --------------------------------------

    /**
     * For every pair, at least one of {engine, accumulate, blend, corner, hardness, flow, size} differs.
     * This catches "the Marker is the Soft air with the size changed", which is the exact shape a rushed
     * preset takes and which no validator would ever speak to. On this row's set it also catches the one
     * mistake available here: shipping the Eraser as a copy of the Ink with `"blend"` changed.
     */
    @Test
    fun noPresetIsACopyOfAnotherWithADifferentName() {
        val all = shipped
        for (i in all.indices) {
            for (j in i + 1 until all.size) {
                val (aFolder, a) = all[i]
                val (bFolder, b) = all[j]
                assertTrue(
                    shape(a) != shape(b),
                    "$aFolder and $bFolder are the same brush with two names — engine " +
                        "${a.engine}/${b.engine}, accumulate ${a.accumulate}/${b.accumulate}, " +
                        "blend ${a.blend}/${b.blend}, corner ${a.tip.corner}/${b.tip.corner}, " +
                        "hardness ${a.tip.hardness.base}/${b.tip.hardness.base}, " +
                        "flow ${a.flow.base}/${b.flow.base}, size ${a.size.base}/${b.size.base}",
                )
            }
        }
    }

    private fun shape(p: BrushPreset) = listOf(
        p.engine, p.accumulate, p.blend, p.tip.corner, p.tip.hardness.base, p.flow.base, p.size.base,
    )

    // ---- 8. every preset validates clean, and the message names the file ---------------------------

    /**
     * [shipped] already refuses a file that will not load, and it re-throws with the path in the
     * message. This is the same walk stated as a test of its own, so the row has a test whose failure
     * text is `marker/brush.json` and not a bare list of rule messages — which is the only thing a person
     * fixing a shipped preset can act on.
     */
    @Test
    fun everyShippedPresetValidatesCleanAndSaysWhichFile() {
        assertTrue(shipped.isNotEmpty(), "no brush folders with a brush.json under ${brushesDir().absolutePath}")
        for ((folder, p) in shipped) {
            assertEquals(emptyList(), BrushValidate.validate(p), "$folder/brush.json must load clean, but: ${BrushValidate.validate(p)}")
            assertTrue(p.id.isNotBlank(), "$folder/brush.json has no id")
            assertTrue(p.name.isNotBlank(), "$folder/brush.json has no name — the pill shows it by name")
        }
    }

    // ---- 9. grain is only on where it is meant to be ------------------------------------------------

    /**
     * Exactly one shipped preset has grain on: Pencil. A Marker with grain is a different brush, and a
     * grain number copied out of Pencil is the likeliest mistake in this row — so the allowed set is
     * named in the failure message rather than being a bare count.
     */
    @Test
    fun grainIsOnlyOnTheBrushItIsFor() {
        val grained = shipped.filter { it.second.tipTexture.enabled || it.second.paperGrain.enabled }
        val smudgeHasLanded = brushFolders().any { it.name == "smudge" }
        val allowed = setOf("pencil") + if (smudgeHasLanded) setOf("smudge") else emptySet()
        assertEquals(
            allowed, grained.map { it.first }.toSet(),
            "only Pencil may carry grain, and these folders do: " +
                grained.joinToString { "${it.first} (${it.second.id})" },
        )
    }

    // ---- 10. the version on disk is the lowest that can express the file ----------------------------

    /**
     * Three things, and not one version literal anywhere in this file (R30 item 3: a spec says "bump to
     * the next `BRUSH_VERSION`" and the builder reads the current number AT LANDING).
     *
     *  1. **Round trip.** Re-encoding a decoded preset writes back the version the FILE had. That is
     *     `BrushJson.versionFor`'s rule — a file needing no newer word encodes as the version it already
     *     had — observed through the public API rather than restated.
     *  2. **Only the files that need the newest words carry the newest number.** At most one shipped file
     *     claims `BRUSH_VERSION`, and the one that does uses a word that needs it. This is the assertion
     *     that a version bump does not sweep every shipped file along with it, and it is read from the
     *     code, so when wave two bumps `BRUSH_VERSION` it needs no edit here.
     *  3. **Every other shipped file is below `BRUSH_VERSION` and needs nothing from it**, which is what
     *     makes "the lowest version that can express it" true of them rather than merely claimed.
     */
    @Test
    fun theVersionOnDiskIsTheLowestThatCanExpressTheFile() {
        val all = shipped
        assertTrue(all.isNotEmpty(), "no shipped presets found")

        for ((folder, p) in all) {
            val again = BrushJson.decode(BrushJson.encode(p))
            assertEquals(
                p.version, again.version,
                "$folder/brush.json says version ${p.version} and re-encodes as ${again.version}. The rule " +
                    "is `BrushJson.versionFor`: a file that needs no newer word keeps the version it had.",
            )
            assertTrue(
                p.version <= BRUSH_VERSION,
                "$folder/brush.json says version ${p.version}; this build reads $BRUSH_VERSION, so " +
                    "`BrushValidate` refuses it as being from a newer Joy Brush.",
            )
        }

        val claimsCurrent = all.filter { it.second.version == BRUSH_VERSION }
        assertTrue(
            claimsCurrent.size <= 1,
            "more than one shipped file claims version $BRUSH_VERSION: ${claimsCurrent.map { it.first }}. " +
                "Only a file that uses a version-$BRUSH_VERSION word needs that number, and a bump is not " +
                "a reason to restamp the others.",
        )
        for ((folder, p) in claimsCurrent) {
            assertTrue(
                p.engine == ENGINE_FILL || p.blend == BLEND_BEHIND,
                "$folder/brush.json claims version $BRUSH_VERSION but uses no word that needs it " +
                    "(engine \"${p.engine}\", blend \"${p.blend}\") — it should be the LOWEST version.",
            )
        }
        for ((folder, p) in all) {
            if (claimsCurrent.any { it.first == folder }) continue
            assertTrue(
                p.engine != ENGINE_FILL && p.blend != BLEND_BEHIND,
                "$folder/brush.json uses a version-$BRUSH_VERSION word but claims version ${p.version}; it " +
                    "would be refused as a file older than the word it uses.",
            )
        }
    }
}

/**
 * Every directory under `brushes/` that holds a `brush.json`, sorted by name. The `brush.json` filter is
 * the right one for a folder holding a `tip.png`; the companion check for a folder somebody made and
 * forgot is in [ShippedBrushFilesTest], beside the walk it belongs to.
 */
internal fun brushFolders(): List<File> =
    (brushesDir().listFiles() ?: emptyArray())
        .filter { it.isDirectory && File(it, "brush.json").isFile }
        .sortedBy { it.name }

/** `joybrush/brushes`, found by the same walk [joybrushRoot] uses. */
internal fun brushesDir(): File = File(joybrushRoot(), "brushes")

/**
 * The `joybrush/` folder: walk up from wherever the test task was started, as `WriteGrainAssets` does.
 *
 * ONE root-finder for the whole source set, and it is here rather than duplicated: two walks of the same
 * kind are two things to get wrong, and the second one is the one nobody reads.
 */
internal fun joybrushRoot(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        if (dir.name == "joybrush" && File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
    }
    throw IllegalStateException(
        "no joybrush/ folder with settings.gradle.kts above ${File("").absolutePath}",
    )
}
