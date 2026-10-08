package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.DabInputs
import cc.joycreator.joybrush.core.brush.FillPen
import cc.joycreator.joybrush.core.brush.InputCurve
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.ResponseSpec
import cc.joycreator.joybrush.core.brush.Scatter
import cc.joycreator.joybrush.core.brush.ScatterSpec
import cc.joycreator.joybrush.core.brush.SplitMix
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A recording replayed must draw the marks it drew, every time, on every platform, for ever.
 *
 * That is one promise, and it is the promise an ink layer is made of: the recording is the truth,
 * so a line that comes back looking different is a line that has been lost. Everything else here is
 * a way of being able to say that with a straight face — the seed is really the record's, the
 * scatter really gets NaN for what it cannot know, the tessellation really is the live path's
 * arithmetic, and a brush that cannot draw an ink line says so rather than drawing one anyway.
 *
 * The non-vacuity argument in every test is written inside the test. A test that cannot be shown
 * failing is an assumption, and the three this file exists to kill are: that the determinism test
 * is sensitive to the seed, that the parity test is driving a real stroke, and that "the two lists
 * differ" means the seed rather than a constant offset.
 */
class InkReplayTest {

    // ── 1. the salt ────────────────────────────────────────────────────────────────────────

    /**
     * THE SALT IS THE VIEW'S SALT. `0x5CA7L` is the literal `JbCanvasView.SCATTER_SALT`
     * (`JbCanvasView.kt:651`) has always held, MOVED into core and not retyped.
     *
     * This is a tripwire BY DESIGN: it is the drift detector for the window in which the two are
     * still two literals. The structural fix — one literal instead of two — is the Lead's two-line
     * edit in JB-5.01b, because LEAD_RULINGS R30 item 2 reserves `JbCanvasView.kt`, and this
     * test is what makes deferring it safe rather than a drift nobody would notice.
     *
     * AND THE SECOND HALF, which the tripwire alone is not: the replay really USES the salt, not
     * merely carries it. Two scatter runs over the same dab list, one seeded `seed xor SALT` and
     * one seeded `seed`, are different pictures — and the replay must be the salted one. A `dabs`
     * that dropped the xor would be self-consistent, would satisfy every determinism test in this
     * file (a run is a run whatever it is seeded with), and would scatter every line somewhere the
     * pen never put it. Pinning the literal is not the same claim as pinning the use.
     */
    @Test
    fun theSaltIsTheOneTheViewUses() {
        assertEquals(
            0x5CA7L, Scatter.SALT,
            "core's salt must equal JbCanvasView.SCATTER_SALT (JbCanvasView.kt:651) — if you " +
                "changed one, change the other",
        )

        val record = line(40, 4f, 0f, seed = 7L)
        val brush = scatterBrush()
        val placed = placedDabsOf(record, brush)
        val salted = scatterOf(record, brush, placed)
        val unsalted = Scatter.expand(placed, brush.scatter, SplitMix(record.seed), ::viewInputsOf)
        assertNotEquals(unsalted, salted, "the salt really does move the leaves, so its absence shows")
        assertEquals(salted, InkReplay.dabs(record, brush), "and the replay is the SALTED run")
    }

    // ── 2. determinism, and that it is SENSITIVE to the seed ───────────────────────────────

    /**
     * DETERMINISM, hard, and then the harder half: this test must be SENSITIVE TO THE SEED.
     *
     * `assertEquals(list, list)` on two separate `InkReplay` invocations IS the property:
     * `Dab` is a data class, so the comparison is field-wise and nothing hides behind an identity
     * check. Three calls rather than two, and the third is on an equal record rebuilt from the
     * same values, so a cache keyed on nothing, a shared random stream left advanced by the first
     * call, and a `toInt()` that lost a bit would all be caught.
     *
     * BUT a determinism test that passes with the wrong seed is worse than no test, so the seed is
     * perturbed and the answer is REQUIRED to change. That half is what makes the first half mean
     * something: it says the equality is a property of the seed and not a property of a stroke
     * with no randomness in it. A `scatterBrush()` is used for exactly that reason — with
     * `scatter.amount = 0f` (which test 3 checks) the dab list is the same whatever the seed, and
     * a determinism test run on that fixture would pass against an implementation that ignored the
     * record's seed entirely.
     */
    @Test
    fun replayingTwiceDrawsTheSameMarksAndChangingTheSeedChangesThem() {
        val brush = scatterBrush()
        val record = line(40, 4f, 0f, seed = 7L)

        val first = InkReplay.dabs(record, brush)
        val second = InkReplay.dabs(record, brush)
        val third = InkReplay.dabs(record.copy(), brush)
        assertTrue(first.isNotEmpty(), "the fixture is a real stroke: ${first.size} dabs")
        assertEquals(first, second, "two replays of one recording are the same marks")
        assertEquals(first, third, "and so is a replay of an equal record built independently")
        assertEquals(first.hashCode(), second.hashCode(), "field-wise equality, hash and all")

        // …and the seed is the whole of it. One step along the stream is enough; the point is that
        // the replay READS record.seed and has no other source of randomness at all.
        val other = InkReplay.dabs(record.copy(seed = record.seed + 1L), brush)
        assertEquals(first.size, other.size, "the dab COUNT is the seed's too: the stream advances alike")
        assertNotEquals(first, other, "and the leaves land somewhere else: the seed is really read")
    }

    // ── 3. the seed is the record's and only the record's ─────────────────────────────────

    /**
     * The seed decides the scatter, and NOTHING ELSE does — proved from both sides.
     *
     * BOTH HALVES MATTER, and either alone would be a test that could pass wrongly:
     *
     *  - With `scatter.amount = 0f` the two seeds give the SAME list, because a zero offset hands
     *    back the source dab (see `Scatter.shifted`). So any difference seen with a real scatter is
     *    a SEED effect and not the brush, and the length really is the seed's doing.
     *  - With a real scatter the difference must not be a CONSTANT TRANSLATION. A thousand leaves
     *    all moved by the same vector would satisfy "the lists differ" while being a bug, so the
     *    per-dab deltas are required to take more than one value, and at least one leaf to be more
     *    than half a document pixel off the centreline it would otherwise have had.
     */
    @Test
    fun theSeedIsTheRecordsAndOnlyTheRecords() {
        val flat = inkBrush(scatter = ScatterSpec(amount = Param(0f), count = 4))
        val noScatterA = InkReplay.dabs(line(40, 4f, 0f, seed = 7L), flat)
        val noScatterB = InkReplay.dabs(line(40, 4f, 0f, seed = 8L), flat)
        assertEquals(
            noScatterA, noScatterB,
            "no scatter amount means the seed cannot move a dab: the difference below is the seed's",
        )

        val a = InkReplay.dabs(line(40, 4f, 0f, seed = 7L), scatterBrush())
        val b = InkReplay.dabs(line(40, 4f, 0f, seed = 8L), scatterBrush())
        assertEquals(a.size, b.size, "the count is the same: one stream, walked the same way")
        assertNotEquals(a, b, "the positions are not")

        val deltas = a.zip(b) { p, q -> hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble()) }
        assertTrue(deltas.toSet().size > 1, "the difference is not one constant translation")
        // Derivation: `Scatter` scales its offsets by `amount x 2 x radius` = 0.5 x 2 x 3 = 3 doc
        // px, and a leaf's across-offset is `2u - 1` for a uniform u, so the leaves are spread right
        // across +-3. A half-doc-px floor is therefore met by a large fraction of them, and a
        // scatter that moved nothing at all would meet it by none.
        assertTrue(deltas.any { it > 0.5 }, "at least one leaf is more than 0.5 doc px off: $deltas")
    }

    // ── 4. the replay IS the live path's arithmetic ───────────────────────────────────────

    /**
     * The replay walks `JbCanvasView`'s steps in `JbCanvasView`'s order, and this is the test that
     * says so.
     *
     * The expected list is built BY HAND from the same four objects in the same order, and the two
     * must be field-identical. A replay that smoothed with `StrokeSmoother.smoothAll`, or expanded
     * the scatter batch by batch, or asked the dabber twice per dab (which advances the random
     * stream twice as fast — the bug `BrushDabber`'s own KDoc is about), or seeded the scatter
     * with `record.seed` instead of `record.seed xor Scatter.SALT`, all produce a DIFFERENT list
     * here, and all produce a line that is subtly not the one that was drawn.
     *
     * A pressure curve on `size` is carried so the dabber's per-dab state is real: with a flat size
     * the three draws per dab are still taken but every radius agrees, and half of what can go
     * wrong here would be invisible.
     */
    @Test
    fun theReplayIsTheLivePathsArithmetic() {
        val brush = inkBrush(size = Param(6f, listOf(curve(BrushInput.pressure))))
        val record = line(200, 3f, 10f, seed = 4242L)
        assertEquals(0f, record.smoothing, "the fixture smooths nothing, so the smoother is transparent")

        val expected = livePathDabs(record, brush)
        assertTrue(expected.size > 100, "the fixture is a real stroke: ${expected.size} dabs")
        assertContentEquals(expected, InkReplay.dabs(record, brush), "the live path's own arithmetic")
    }

    // ── 5. the scatter's DabInputs is the view's ───────────────────────────────────────────

    /**
     * A replay may only tell [Scatter] what a [Dab] actually carries, and that is `pressure`.
     *
     * A [Dab] carries only `pressure`; every other input is unknowable from a recording, so all of
     * them are NaN and every curve keyed on one reads its BASE. A builder who filled `tilt` — or
     * `speedPxPerS`, or `direction` — with a plausible number would draw a replayed line that
     * scatters differently from the original, which is the one thing this file must never do.
     *
     * Two fixtures, and the SECOND is the one that bites, because the first cannot. `Param.combine`
     * is "multiply", so a curve sits on top of a base: with a base of `0f` the curve is multiplied
     * by zero and is invisible whatever the input reads, and a fixture of that shape cannot tell
     * NaN from a real number. The tilt curve is `[[0, 0.5], [1, 1]]` rather than the obvious
     * `[[0, 0], [1, 1]]` for the same reason one layer down: a curve through the origin reads 0 at
     * tilt 0, and 0 is the number a builder reaches for first.
     */
    @Test
    fun theScattersDabInputsIsTheViews() {
        // (a) The amount's BASE is 0, so the scatter adds nothing at all, whatever the pen was
        // doing, and the two presses must give the same marks apart from the radius.
        val flat = inkBrush(
            size = Param(6f, listOf(curve(BrushInput.pressure))),
            scatter = ScatterSpec(amount = Param(0f, listOf(tiltCurve())), count = 4),
        )
        val soft = InkReplay.dabs(line(30, 4f, 0f, pressure = 0.2f), flat)
        val hard = InkReplay.dabs(line(30, 4f, 0f, pressure = 1.0f), flat)

        assertEquals(soft.size, hard.size, "the same samples are the same length of stroke")
        assertNotEquals(
            soft.map { it.radius }, hard.map { it.radius },
            "pressure really does reach the size, so the two radii differ",
        )
        assertContentEquals(soft.map { it.x }, hard.map { it.x }, "and NOT ONE OFFSET in x")
        assertContentEquals(soft.map { it.y }, hard.map { it.y }, "same for y")
        // And the claim as an EQUALITY rather than a difference: with the amount at its base the
        // scatter hands its input list back untouched, so the replay IS the placer's output.
        assertContentEquals(soft, livePathDabs(line(30, 4f, 0f, pressure = 0.2f), flat), "no offset at all")

        // (b) THE ONE THAT ACTUALLY CATCHES AN INVENTED TILT, because (a) cannot.
        //
        // `Param.combine` is "multiply", so a curve sits ON TOP of a base: with a base of 0f the
        // curve is multiplied by zero and is invisible no matter what the input reads. A fixture
        // of that shape cannot tell a NaN input from a real one, which is why the spec's own
        // version of this test passes against an implementation that fills `tilt` in with 0.
        //
        // So the base is 0.5 here, and the two candidates are: the BASE, 0.5, because the tilt
        // curve is skipped; and 0.5 x 0.5 = 0.25, because a replay that invented "the pen was
        // upright" would read 0 on `[[0, 0.5], [1, 1]]` — a curve that reads 0.5 at tilt 0 rather
        // than the 0 a curve through the origin would read, because those two are
        // indistinguishable at tilt 0 and only one of them can see a bug.
        //
        // With the amount 0.5 and radius 3 the leaves are offset by 0.5 x 2 x 3 = 3 doc px, so the
        // two candidates differ by document pixels, not by a last bit.
        val loud = inkBrush(scatter = ScatterSpec(amount = Param(0.5f, listOf(tiltCurve())), count = 4))
        val record = line(30, 4f, 0f)
        val atBase = livePathDabs(record, loud)
        val atInventedTilt = scatteredWith(record, loud, 0f)
        assertNotEquals(atInventedTilt, atBase, "NON-VACUITY: the curve is visible, so the two differ")
        assertEquals(atBase, InkReplay.dabs(record, loud), "and the replay is the BASE, not an invented tilt")
    }

    // ── 6. a fill recording draws no dabs ──────────────────────────────────────────────────

    /**
     * A fill is a SHAPE, so it has no dabs — and the reason is not an accident of the fixture.
     *
     * Non-vacuity: the same record's samples through `FillPen.outline` give at least three points,
     * so the recording really does enclose something. An empty dab list here is the ENGINE rule and
     * not a stroke that happened to fail, and a `dabs` that asked the dabber anyway would draw the
     * fill pen as a row of beads — the outline of the shape, which is what re-brushing a fill to a
     * pencil is FOR and must not be what drawing a fill does.
     */
    @Test
    fun aFillRecordingDrawsNoDabsAndDoesNotThrow() {
        val record = loop(40)
        val brush = inkBrush(engine = "fill")
        assertTrue(
            FillPen.outline(record.samples, record.smoothing, record.screenPerDoc).size >= 3,
            "the fixture is a real shape",
        )
        assertEquals(emptyList(), InkReplay.dabs(record, brush), "a fill is a shape, not a row of dabs")
        assertEquals(null, InkReplay.refusal(record, brush), "and it is not a refusal either")
    }

    // ── 7. smudge and wet are refused by name ─────────────────────────────────────────────

    /**
     * `smudge` and `wet` read the pixels UNDERNEATH. An ink layer has none to read — it has
     * recordings — so they may not draw an ink line (LEAD_RULINGS R20).
     *
     * The sentence must NAME the engine and the stroke, because it is the whole error report a
     * person sees: "this stroke cannot be drawn" is a bug report, while "stroke s9 was drawn with a
     * \"smudge\" brush…" is a sentence they can act on.
     *
     * `refusal()` is the same words without the throw, and must be the SAME words: two files
     * growing two sentences about one fact is how this project has been bitten before. A legal
     * stamp brush answers `null` from `refusal` — which is where the original spec's test said
     * `dabs` returns `null`, and `dabs` returns `List<Dab>`.
     */
    @Test
    fun smudgeAndWetAreRefusedByName() {
        for (engine in listOf("smudge", "wet")) {
            val brush = inkBrush(engine = engine)
            val record = line(6, 4f, 0f, id = "s9")
            val thrown = assertFailsWith<InkReplayException>("engine \"$engine\"") { InkReplay.dabs(record, brush) }
            val m = thrown.message ?: ""
            assertTrue(m.contains(engine), "the message names the engine ($engine): $m")
            assertTrue(m.contains("s9"), "and the stroke: $m")
            assertEquals(m, InkReplay.refusal(record, brush), "refusal() is the same sentence, unthrown")
        }
        assertEquals(null, InkReplay.refusal(line(6, 4f, 0f), inkBrush()), "a legal brush is not a refusal")
    }

    // ── 8. a missing brush is refused, naming both ────────────────────────────────────────

    /**
     * A recording whose brush is not in the library is refused IN WORDS, naming the stroke and the
     * brush, and never drawn as a default nib: a line that suddenly appears in the wrong brush is
     * worse than a line that is visibly missing and says why.
     */
    @Test
    fun aMissingBrushIsRefusedNamingBoth() {
        val record = line(6, 4f, 0f, id = "s3").copy(brushId = "ink-pen")
        val thrown = assertFailsWith<InkReplayException> { InkReplay.dabs(record, null) }
        val m = thrown.message ?: ""
        assertTrue(m.contains("s3"), "the message names the stroke: $m")
        assertTrue(m.contains("ink-pen"), "and the brush it cannot find: $m")
        assertEquals(m, InkReplay.refusal(record, null), "refusal() agrees")
    }

    // ── 9. widthScale is applied ──────────────────────────────────────────────────────────

    /**
     * `widthScale` is a multiplier on the brush's size FOR THIS LINE, and nothing else applies it:
     * `BrushDabber` computes a radius from the preset alone. A replay that ignored it would make
     * JB-5.03's `reweight` change a number and not the drawing, which is the one way "you can
     * thicken a line afterwards" could be a lie.
     *
     * The same dab COUNT and every radius exactly twice: the count is unchanged because the step
     * `2r x spacing` is computed inside `DabPlacer.emit` from the UNSCALED radius — `InkReplay`
     * multiplies afterwards — so a re-weighted line's dabs are further apart as well as fatter,
     * which is what a bigger brush would have done.
     *
     * AND THE BROKEN NUMBERS. A `StrokeRecord` is a plain value and does not check `widthScale`,
     * so this is the point that reads it, and LEAD_RULINGS R1/R10 says a broken number is read as
     * a safe one. `NaN` and 0 are safe-zero; `1e30f` is not, and the derivation is below.
     */
    @Test
    fun widthScaleThickensTheReplay() {
        val thin = InkReplay.dabs(line(40, 4f, 0f, widthScale = 1f), inkBrush())
        val thick = InkReplay.dabs(line(40, 4f, 0f, widthScale = 2f), inkBrush())
        assertTrue(thin.isNotEmpty(), "the fixture is a real stroke")
        assertEquals(thin.size, thick.size, "re-weighting does not re-space the stroke")
        for (i in thin.indices) {
            val expected = thin[i].radius * 2f
            assertTrue(
                abs(thick[i].radius - expected) <= 1e-6f * maxOf(1f, abs(expected)),
                "dab $i: ${thin[i].radius} x 2 is $expected, not ${thick[i].radius}",
            )
        }

        // Zero and a NaN are the two answers that lay the stroke down and draw no radius at all.
        for (bad in floatArrayOf(0f, Float.NaN)) {
            val dabs = InkReplay.dabs(line(40, 4f, 0f, widthScale = bad), inkBrush())
            assertEquals(thin.size, dabs.size, "widthScale=$bad still lays the stroke down")
            assertTrue(dabs.all { it.radius == 0f }, "widthScale=$bad draws no radius: ${dabs.map { it.radius }}")
        }

        // 1e30f is FINITE, and that is the whole of it. Decision 9 clamps the RADIUS to
        // 0..DabPlacer.MAX_RADIUS_PX: radius 3 x 1e30 = 3e30, which is a finite float, so it is
        // clamped DOWN to MAX_RADIUS_PX rather than read as 0 — which is what Decision 9's own
        // reason asks for ("a file that was never validated must not make a radius that cannot be
        // allocated"). The spec's test text asked for "0f" here; the DECISION and the guard it
        // names agree with each other and disagree with it, and the decision is what is built.
        val huge = InkReplay.dabs(line(40, 4f, 0f, widthScale = 1e30f), inkBrush())
        assertEquals(thin.size, huge.size, "still the same stroke")
        assertEquals(
            listOf(DabPlacer.MAX_RADIUS_PX), huge.map { it.radius }.distinct(),
            "3 x 1e30 is finite, so the placer's own clamp is what answers, not the safe-zero rule",
        )
    }

    // ── 10. the dab count is bounded by the placer, not by a cap ───────────────────────────

    /**
     * NO DAB CAP (Decision 14): capping dabs means refusing a stroke somebody drew, and that
     * belongs on `spacing` if it belongs anywhere. What bounds the count is `DabPlacer`, and it is
     * bounded in ARITHMETIC rather than by luck:
     *
     *     step   = max(2r x spacing, minSpacingPx) >= minSpacingPx = 0.5 doc px
     *     dabs   <= travel / step + 1 = 39 900 / 0.5 + 1 = 79 801
     *
     * The fixture drives that: 400 samples 100 doc px apart is 39 900 doc px of travel, at
     * `spacing = 0.0001f`, where the brush's own step would be 2 x 3 x 0.0001 = 0.0006 doc px —
     * 833x below the floor, so the floor is unambiguously the binding term. A BOUND rather than
     * an exact count, because the count is the placer's business and this test is about the RULE
     * that keeps it finite; and the lower half asserts the fixture really did place a great many
     * dabs, so the bound is not being met by an empty list.
     *
     * ## THE FLOOR ALSO BINDS FOR INK ITSELF, WHICH CORRECTS DECISION 3's FIGURE
     *
     * Decision 3 derives the scallop at Ink's NOMINAL step of `spacing x diameter` = 0.24 doc px
     * and gets 0.0024 doc px, 0.038 screen px at 16x. But `DabPlacer.emit` returns
     * `max(2r x spacing, minSpacingPx)` and `minSpacingPx` is 0.5 doc px, so for a 6 px brush the
     * FLOOR binds and the real step is 0.5, not 0.24. Redoing the same arithmetic at the real step:
     *
     *     scallop = r - sqrt(r^2 - (d/2)^2) = 3 - sqrt(9 - 0.0625) = 3 - 2.98956 = 0.01044 doc px
     *             = 0.00174 x diameter          (not 0.0004)
     *             = 0.167 screen px at 16x      (not 0.038)
     *
     * The RULING is untouched and its conclusion is if anything unchanged in character — 0.167
     * screen px is a sixth of a pixel at the zoom R37 names, and it is the scallop of a HARD edge
     * that the antialiasing and the 1 000-fold overlap of neighbouring beads both soften — but the
     * figure in the spec is 4.4x optimistic and the Lead should have the real one. The assertion
     * below is what makes the floor a fact rather than a note: a 6 px brush's dabs are 0.5 doc px
     * apart, and 0.24 is what the arithmetic above wrongly predicts.
     *
     * ## AND THE OTHER SIDE, WITH A FIXTURE BIG ENOUGH FOR IT TO BIND
     *
     * A bound alone is half a claim: `max(step, 0.5)` would pass a replay that multiplied the
     * spacing by 1.5 and still clamped to the floor. So the step is checked on a 20 px brush,
     * where `2 x 10 x 0.04` = 0.8 is above the floor and the brush's own term is what binds. The
     * widest gap between consecutive dabs is then 0.8 doc px to within 0.001 — loose enough for the
     * float carry of 2 000 adds, and 400x tighter than the 1.2 an implementation scaling the
     * spacing by 1.5 would produce. Decision 3 says the density IS the recording's, so it is
     * checked from both sides or not at all.
     */
    @Test
    fun theDabCountIsBoundedByThePlacerNotByACap() {
        val spread = line(400, 100f, 0f)
        val floorBound = InkReplay.dabs(spread, inkBrush(spacingOverride = 0.0001f))
        val travelDocPx = 399.0 * 100.0
        val bound = (travelDocPx / MIN_SPACING_PX).toInt() + 1
        assertEquals(79_801, bound, "39 900 doc px / 0.5 doc px + the first dab, as derived")
        assertTrue(
            floorBound.size <= bound,
            "${floorBound.size} dabs for 39 900 doc px is inside the placer's own bound",
        )
        assertTrue(
            floorBound.size > 1_000,
            "and it really did place ${floorBound.size}, so the bound is not vacuous",
        )
        val line400 = line(400, 4f, 0f)
        // The shipped 6 px brush: 2 x 3 x 0.04 = 0.24, and the placer's floor takes it to 0.5.
        assertStep(maxGapDocPx(InkReplay.dabs(line400, inkBrush())), 0.5f, "the 6 px brush is on the placer's 0.5 floor")
        // A 20 px brush: 2 x 10 x 0.04 = 0.8, above the floor, so the SPACING is what binds.
        assertStep(maxGapDocPx(InkReplay.dabs(line400, inkBrush(size = Param(20f)))), 0.8f, "2 x 10 x 0.04")
    }

    /** The measured step is the derived one to within a thousandth of a document pixel. */
    private fun assertStep(measured: Float, derived: Float, what: String) {
        assertTrue(
            abs(measured - derived) <= 0.001f,
            "$what: the widest gap between consecutive dabs is $measured and the derived step is $derived",
        )
    }

    /** The widest distance between consecutive dabs of a straight line: the placer's real step. */
    private fun maxGapDocPx(dabs: List<Dab>): Float {
        var max = 0f
        for (i in 0 until dabs.size - 1) {
            val gap = hypot(
                (dabs[i + 1].x - dabs[i].x).toDouble(),
                (dabs[i + 1].y - dabs[i].y).toDouble(),
            ).toFloat()
            if (gap > max) max = gap
        }
        return max
    }

    // ── 11. empty, all-dropped, and a dot ─────────────────────────────────────────────────

    /**
     * Three recordings that are almost nothing, and the difference between "a dot draws a dot" and
     * "a dot is silently nothing".
     *
     *  - ZERO samples: nothing was drawn, so nothing is drawn. Not an exception.
     *  - ONE sample with a non-finite `x`: a corrupt or hostile recording, which `StrokeCodec`
     *    hands back bit for bit because bit-exactness is that codec's whole contract.
     *    `StrokeSmoother` drops it and counts it in `droppedSamples` — "throwing here instead
     *    would throw away the other 999 good samples over one bad float" — so the replay draws the
     *    nothing that was recorded. Not an exception.
     *  - ONE valid sample: `DabPlacer` emits a dab for the first sample unconditionally, so a tap
     *    draws a dot. A pen that landed once and reported two identical positions is ordinary.
     */
    @Test
    fun emptyAndAllDroppedRecordingsAndADot() {
        val brush = inkBrush()
        assertEquals(emptyList(), InkReplay.dabs(line(0, 4f, 0f), brush), "no samples, no dabs, no throw")

        val broken = line(1, 4f, 0f).copy(samples = listOf(PenSample(Float.NaN, 0f, 0.0)))
        assertEquals(emptyList(), InkReplay.dabs(broken, brush), "a sample with no place is dropped, and the rest is kept")

        val dot = InkReplay.dabs(line(1, 4f, 0f), brush)
        assertEquals(1, dot.size, "a recording of one sample is not empty: a dot draws a dot")
        assertEquals(3f, dot[0].radius, "at the brush's own size")
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────

    /**
     * The shipped Ink's numbers — `joybrush/brushes/ink/brush.json` — with `hardness` raised to 1
     * so a test's edge is predictable, and `engine`, `spacing` and the scatter are the only things
     * a test varies.
     */
    // ── the brush's own curves ─────────────────────────────────────────────────────────────

    /**
     * THE BRUSH'S RESPONSE CURVES ARE PART OF THE REPLAY. `JbCanvasView.feedOne` runs every pen sample through
     * `preset.response` before the smoother, so a replay that skipped it would draw a brush with a pressure curve at
     * another weight than the pen drew it (found by the Lead, 2026-10-07, while planning record capture for JB-5.20).
     *
     * Non-vacuity: the same record replayed through a brush WITHOUT the curve must give different radii, so the equality
     * half cannot pass because the curve did nothing. The fixture presses at 0.5 and the curve bends 0.5 well below
     * itself, and the size follows pressure.
     */
    @Test
    fun theBrushsPressureCurveShapesTheReplayAsItShapedTheLiveStroke() {
        val curved = inkBrush(size = Param(6f, listOf(curve(BrushInput.pressure))))
            .copy(response = ResponseSpec(pressure = listOf(0.9f, 0f, 1f, 0.1f)))
        val record = line(40, 4f, 0f, pressure = 0.5f)

        val replayed = InkReplay.dabs(record, curved)
        assertTrue(replayed.isNotEmpty(), "a real stroke: ${replayed.size} dabs")
        assertEquals(livePathDabs(record, curved), replayed, "the replay is the live path, curve included")

        val straight = InkReplay.dabs(record, curved.copy(response = ResponseSpec()))
        val mid = replayed.size / 2
        assertTrue(replayed[mid].radius < straight[mid].radius - 0.5f,
            "the curve really bends pressure: ${replayed[mid].radius} vs ${straight[mid].radius} without it")
    }

    private fun inkBrush(
        engine: String = "stamp",
        scatter: ScatterSpec = ScatterSpec(),
        size: Param = Param(6f),
        spacingOverride: Float? = null,
    ) = BrushPreset(
        id = "joybrush.ink",
        name = "Ink",
        engine = engine,
        size = size,
        tip = TipSpec(corner = 2f, hardness = Param(1f)),
        spacing = spacingOverride ?: 0.04f,
        scatter = scatter,
        accumulate = "wash",
    )

    private fun scatterBrush() = inkBrush(scatter = ScatterSpec(amount = Param(0.5f), count = 4))

    /** `n` samples along `x = 0, step, 2 * step, …` at height `y`, 8 ms apart so the dabber's speed filter moves. */
    private fun line(
        n: Int,
        step: Float,
        y: Float,
        seed: Long = 7L,
        id: String = "s1",
        widthScale: Float = 1f,
        pressure: Float = 1f,
    ) = StrokeRecord(
        id = id,
        brushId = "joybrush.ink",
        seed = seed,
        smoothing = 0f,
        screenPerDoc = 1f,
        samples = (0 until n).map {
            PenSample(x = it * step, y = y, timeMs = it * 8.0, pressure = pressure)
        },
        widthScale = widthScale,
    )

    /** A closed square [n] points to a side, which is a shape and not a line. */
    private fun loop(n: Int): StrokeRecord {
        val side = (n - 1).coerceAtLeast(1).toFloat()
        val pts = ArrayList<PenSample>()
        var k = 0
        for (edge in 0 until 4) {
            val fromX = if (edge % 2 == 0) 0f else side
            val fromY = if (edge < 2) 0f else side
            val toX = if (edge % 2 == 0) side else 0f
            val toY = if (edge < 2) side else 0f
            for (i in 0..side.toInt()) {
                pts.add(
                    PenSample(
                        x = fromX + (toX - fromX) * i / side,
                        y = fromY + (toY - fromY) * i / side,
                        timeMs = k++ * 8.0,
                    ),
                )
            }
        }
        return StrokeRecord(
            id = "lf", brushId = "joybrush.ink", seed = 3L, smoothing = 0f, screenPerDoc = 1f, samples = pts,
        )
    }

    /**
     * The live path, written out: `JbCanvasView.startStroke` + `feed` + `paint` with a preset
     * instead of a view. The class forms, in the same order, with the same values — and
     * [cc.joycreator.joybrush.core.brush.Scatter] fed the whole stroke at once, which the view's
     * own note says gives the same leaves as feeding it batch by batch.
     */
    private fun livePathDabs(record: StrokeRecord, brush: BrushPreset): List<Dab> =
        scatterOf(record, brush, placedDabsOf(record, brush))

    /** Steps 1-3 of the live path: smooth, dab, place. */
    private fun placedDabsOf(record: StrokeRecord, brush: BrushPreset): List<Dab> {
        val smoother = StrokeSmoother(record.smoothing, record.screenPerDoc)
        val points = ArrayList<PenSample>()
        for (s in record.samples) points.addAll(smoother.add(brush.response.apply(s)))
        points.addAll(smoother.finish())
        val dabber = BrushDabber(brush, record.seed)
        return DabPlacer(spacing = dabber.spacing, look = dabber::look).add(points)
    }

    /** Step 4: one `Scatter.expand` over the whole dab list, from `record.seed xor Scatter.SALT`. */
    private fun scatterOf(record: StrokeRecord, brush: BrushPreset, placed: List<Dab>): List<Dab> =
        Scatter.expand(placed, brush.scatter, SplitMix(record.seed xor Scatter.SALT), ::viewInputsOf)

    /**
     * The same, with a REAL number in one of the inputs a replay cannot know. Only a test may build
     * this: it is how a fixture shows what an invented input WOULD have produced, so it can require
     * that the replay did not produce it.
     */
    private fun scatteredWith(record: StrokeRecord, brush: BrushPreset, tilt: Float): List<Dab> =
        Scatter.expand(placedDabsOf(record, brush), brush.scatter, SplitMix(record.seed xor Scatter.SALT)) { dab ->
            viewInputsOf(dab).copy(tilt = tilt)
        }

    /**
     * `JbCanvasView.dabInputsOf`, transcribed. A [Dab] carries only `pressure`; every other input
     * is unknowable from a replay and is NaN, which the curves read as "not available" and skip.
     */
    private fun viewInputsOf(dab: Dab) = DabInputs(
        pressure = dab.pressure, tilt = Float.NaN, speedPxPerS = Float.NaN, direction = Float.NaN,
        lean = Float.NaN, distancePx = Float.NaN, random = Float.NaN, strokeRandom = Float.NaN,
        barrel = Float.NaN,
    )

    /** `[[0, 0], [1, 1]]`: a curve that takes an input at its own scale, 0 at the bottom. */
    private fun curve(input: BrushInput) = InputCurve(input, listOf(listOf(0f, 0f), listOf(1f, 1f)))

    /**
     * `[[0, 0.5], [1, 1]]` on TILT. A curve through the origin cannot tell "skipped because the
     * input is NaN" from "read as 0", which is exactly the bug this fixture exists to catch.
     */
    private fun tiltCurve() = InputCurve(BrushInput.tilt, listOf(listOf(0f, 0.5f), listOf(1f, 1f)))

    private companion object {
        /**
         * `DabPlacer`'s own default `minSpacingPx`, in document px. Written out rather than read
         * (it is a private constructor default) because the bound in test 10 is arithmetic on it.
         */
        const val MIN_SPACING_PX = 0.5
    }
}
