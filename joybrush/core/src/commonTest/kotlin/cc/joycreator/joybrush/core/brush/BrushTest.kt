package cc.joycreator.joybrush.core.brush

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class BrushTest {

    // The two shipped example brushes. These strings are byte-for-byte the files in
    // joybrush/brushes/ink/brush.json and joybrush/brushes/pencil/brush.json — commonTest cannot open
    // a file (it must stay platform-neutral), so if one of these is edited, edit the file to match.

    private val inkJson: String = """
        {
          "format": "joybrush.brush",
          "version": 1,
          "id": "joybrush.ink",
          "name": "Ink",
          "engine": "stamp",
          "tip": {
            "corner": 2,
            "hardness": { "base": 0.95 }
          },
          "size": {
            "base": 6,
            "inputs": [ { "input": "pressure", "curve": [ [0, 0.15], [1, 1] ] } ]
          },
          "opacity": { "base": 1 },
          "flow": { "base": 1 },
          "spacing": 0.04,
          "accumulate": "wash",
          "blend": "normal",
          "smoothing": 0.35,
          "license": "CC0"
        }
    """.trimIndent() + "\n"

    private val pencilJson: String = """
        {
          "format": "joybrush.brush",
          "version": 1,
          "id": "joybrush.pencil",
          "name": "Pencil",
          "engine": "stamp",
          "tip": {
            "corner": 2,
            "aspect": 0,
            "followDirection": false
          },
          "size": {
            "base": 3,
            "inputs": [ { "input": "pressure", "curve": [ [0, 0.6], [1, 1] ] } ]
          },
          "opacity": {
            "base": 1,
            "inputs": [ { "input": "pressure", "curve": [ [0, 0.1], [1, 0.9] ] } ]
          },
          "paperGrain": {
            "enabled": true,
            "source": "cloud",
            "depth": { "base": 1, "inputs": [ { "input": "pressure", "curve": [ [0, 0.2], [1, 0.9] ] } ] },
            "edge": 0.25,
            "tiltGradient": 0.6
          },
          "smoothing": 0.2,
          "license": "CC0"
        }
    """.trimIndent() + "\n"

    /** pressure 0.5, upright, still, 200 px along, tilt at half lean, two fixed randoms. */
    private val dab = DabInputs(
        pressure = 0.5f, tilt = 0f, speedPxPerS = 0f, direction = 0f,
        lean = 0f, distancePx = 200f, random = 0.25f, strokeRandom = 0.75f, barrel = 0f,
    )

    private fun curve(vararg points: Pair<Float, Float>, input: BrushInput = BrushInput.pressure) =
        InputCurve(input, points.map { listOf(it.first, it.second) })

    private val identity = curve(0f to 0f, 1f to 1f)
    private val half = curve(0f to 0.5f, 1f to 1f)

    /** A well-formed curve of [n] points, all at x = 0: only the point count is what a test asks of it. */
    private fun flatCurve(n: Int): List<List<Float>> = List(n) { listOf(0f, it.toFloat()) }

    /** A well-formed input → curve pair, repeated, so a test can talk about a *count* of inputs. */
    private fun inputs(n: Int): List<InputCurve> =
        List(n) { InputCurve(BrushInput.pressure, listOf(listOf(0f, 0.5f), listOf(1f, 1f))) }

    /** The input cap, exactly on it, and one over it. */
    private val eightInputs = inputs(8)
    private val nineInputs = inputs(9)

    private fun preset(build: (BrushPreset) -> BrushPreset): BrushPreset =
        build(BrushJson.decode(inkJson))

    private fun assertSole(problems: List<String>, expected: String) {
        assertEquals(1, problems.size, "expected one message, got $problems")
        assertTrue(problems.single().contains(expected), "message was: ${problems.single()}")
    }

    // ---- 1. the shipped brushes ------------------------------------------------------------------

    @Test
    fun bothExampleBrushesLoadAndRoundTrip() {
        for (json in listOf(inkJson, pencilJson)) {
            val p = BrushJson.decode(json)
            assertEquals(emptyList(), BrushValidate.validate(p), "$p.id must be clean")
            assertEquals(BRUSH_FORMAT, p.format)
            assertEquals(BRUSH_VERSION, p.version)
            assertEquals(p, BrushJson.decode(BrushJson.encode(p)))
        }
        val ink = BrushJson.decode(inkJson)
        assertEquals(6f, ink.size.base)
        assertEquals(0.95f, ink.tip.hardness.base)
        assertEquals(0.04f, ink.spacing)
        assertEquals(0.35f, ink.smoothing)
        assertEquals("wash", ink.accumulate)
        // Ink gets thin and pale as the pen lifts: pressure 0.5 → 0.575 of 6 px.
        assertEquals(6f * 0.575f, Dynamics.eval(ink.size, dab), 1e-4f)

        val pencil = BrushJson.decode(pencilJson)
        assertEquals(3f, pencil.size.base)
        assertTrue(pencil.paperGrain.enabled)
        assertEquals(0.25f, pencil.paperGrain.edge)
        assertEquals(0.6f, pencil.paperGrain.tiltGradient)
        assertEquals(0.2f, pencil.smoothing)
        assertEquals(3f * 0.8f, Dynamics.eval(pencil.size, dab), 1e-4f)
        assertEquals(0.5f, Dynamics.eval(pencil.opacity, dab), 1e-4f)

        // The constants the validator uses are the ones a default-constructed preset carries.
        val bare = BrushPreset(id = "x", name = "x", size = Param(1f))
        assertEquals(BRUSH_FORMAT, bare.format)
        assertEquals(BRUSH_VERSION, bare.version)
        // Writing is stable, so a saved brush only shows a diff when the brush really changed.
        assertEquals(BrushJson.encode(ink), BrushJson.encode(BrushJson.decode(BrushJson.encode(ink))))
    }

    // ---- 2. reading files from the future -------------------------------------------------------

    @Test
    fun unknownKeysAreIgnoredAndANewerVersionIsRejected() {
        val text = inkJson.replace("\"version\": 1,", "\"version\": 1,\n  \"future\": 1,")
            .replace("\"corner\": 2,", "\"corner\": 2,\n    \"future\": { \"a\": 1 },")
        val p = BrushJson.decode(text)
        assertEquals(BrushJson.decode(inkJson), p)
        assertEquals(emptyList(), BrushValidate.validate(p), "an unknown key is not a problem: ${BrushValidate.validate(p)}")

        val newer = BrushJson.decode(inkJson.replace("\"version\": 1,", "\"version\": 2,"))
        assertEquals(2, newer.version)
        assertSole(BrushValidate.validate(newer), "newer Joy Brush")

        assertFailsWith<BrushException> { BrushJson.decode("{\"format\": \"joybrush.brush\",") }
        assertFailsWith<BrushException> { BrushJson.decode("{}") }
    }

    // ---- 3. evaluating a Param -------------------------------------------------------------------

    @Test
    fun evalCombinesTheBaseWithItsCurves() {
        assertEquals(10f, Dynamics.eval(Param(10f), dab))

        // One multiply curve, as the spec's own example: base 10 through [[0,0],[1,1]] at pressure 0.5.
        assertEquals(5f, Dynamics.eval(Param(10f, listOf(identity)), dab), 1e-5f)

        // Two multiply inputs multiply into each other: 10 × 0.75 (pressure) × 0.5 (upright tilt).
        val twoInputs = Param(10f, listOf(half, curve(0f to 0.5f, 1f to 1f, input = BrushInput.tilt)))
        assertEquals(3.75f, Dynamics.eval(twoInputs, dab), 1e-5f)

        // Add: the curves stack onto the base instead of scaling it.
        assertEquals(10.5f, Dynamics.eval(Param(10f, listOf(identity), combine = "add"), dab), 1e-5f)
        assertEquals(11.5f, Dynamics.eval(Param(10f, listOf(half, half), combine = "add"), dab), 1e-5f)

        // A device with no pen reports NaN: that input contributes nothing, in either mode.
        val noPen = dab.copy(pressure = Float.NaN)
        assertEquals(10f, Dynamics.eval(Param(10f, listOf(identity)), noPen))
        assertEquals(10f, Dynamics.eval(Param(10f, listOf(identity), combine = "add"), noPen))
        // …and only that input: the tilt curve still counts.
        val tiltHalf = curve(0f to 0.5f, 1f to 1f, input = BrushInput.tilt)
        assertEquals(5f, Dynamics.eval(Param(10f, listOf(identity, tiltHalf)), noPen), 1e-5f)
        assertEquals(10.5f, Dynamics.eval(Param(10f, listOf(identity, tiltHalf), combine = "add"), noPen), 1e-5f)

        // A file that slipped past validation must not throw inside a stroke: a curve with no usable
        // point is ignored and the base stands.
        assertEquals(7f, Dynamics.eval(Param(7f, listOf(InputCurve(BrushInput.pressure, emptyList()))), dab))
        assertEquals(7f, Dynamics.eval(Param(7f, listOf(InputCurve(BrushInput.pressure, listOf(listOf(1f))))), dab))
    }

    // ---- 4. normalising the pen's inputs ---------------------------------------------------------

    @Test
    fun everyInputIsNormalisedToZeroOne() {
        val n = { i: BrushInput, d: DabInputs -> Dynamics.normalise(i, d) }
        val d = DabInputs(
            pressure = 0.4f, tilt = 0.5f, speedPxPerS = 1500f, direction = 0f,
            lean = 0f, distancePx = 500f, random = 0.37f, strokeRandom = 0.62f, barrel = 0f,
        )
        assertEquals(0.4f, n(BrushInput.pressure, d))
        assertEquals(0.3183099f, n(BrushInput.tilt, d), 1e-6f) // 0.5 rad of 90°
        assertEquals(0.5f, n(BrushInput.speed, d), 1e-6f)
        assertEquals(0.5f, n(BrushInput.direction, d), 1e-6f)
        assertEquals(0.5f, n(BrushInput.lean, d), 1e-6f)
        assertEquals(0.5f, n(BrushInput.distance, d), 1e-6f)
        assertEquals(0.37f, n(BrushInput.random, d))
        assertEquals(0.62f, n(BrushInput.strokeRandom, d))
        assertEquals(0.5f, n(BrushInput.barrel, d), 1e-6f)

        // speed and distance stop at 1; nothing else is clamped (that is the caller's job).
        assertEquals(1f, n(BrushInput.speed, d.copy(speedPxPerS = 9000f)))
        assertEquals(1f, n(BrushInput.distance, d.copy(distancePx = 100_000f)))
        assertEquals(0f, n(BrushInput.speed, d.copy(speedPxPerS = 0f)))

        // Angles run 0 at −π to 1 at +π.
        assertEquals(0f, n(BrushInput.direction, d.copy(direction = -PI.toFloat())), 1e-6f)
        assertEquals(1f, n(BrushInput.direction, d.copy(direction = PI.toFloat())), 1e-6f)
        assertEquals(0.25f, n(BrushInput.barrel, d.copy(barrel = -PI.toFloat() / 2f)), 1e-6f)
        assertEquals(0.75f, n(BrushInput.lean, d.copy(lean = PI.toFloat() / 2f)), 1e-6f)

        // attack is the wrapped angle between lean and stroke direction, so the far side of the
        // circle is a small angle, not a near-full turn.
        val almostTheSame = d.copy(lean = PI.toFloat() - 0.1f, direction = -PI.toFloat() + 0.1f)
        assertEquals(0.063662f, n(BrushInput.attack, almostTheSame), 1e-5f) // 0.2 rad of 180°
        assertEquals(0f, n(BrushInput.attack, d.copy(lean = 1.2f, direction = 1.2f)), 1e-6f)
        assertEquals(1f, n(BrushInput.attack, d.copy(lean = 0f, direction = PI.toFloat())), 1e-5f)
        assertEquals(1f, n(BrushInput.attack, d.copy(lean = PI.toFloat() / 2f, direction = -PI.toFloat() / 2f)), 1e-5f)

        // NaN in, NaN out — the evaluator is what decides that such an input counts for nothing.
        assertTrue(n(BrushInput.pressure, d.copy(pressure = Float.NaN)).isNaN())
        assertTrue(n(BrushInput.tilt, d.copy(tilt = Float.NaN)).isNaN())
        assertTrue(n(BrushInput.speed, d.copy(speedPxPerS = Float.NaN)).isNaN())
        assertTrue(n(BrushInput.distance, d.copy(distancePx = Float.NaN)).isNaN())
        assertTrue(n(BrushInput.attack, d.copy(lean = Float.NaN)).isNaN())
    }

    // ---- 5. speed --------------------------------------------------------------------------------

    @Test
    fun aMillionDabEvaluationsStayFast() {
        // One curve on pressure, one on distance, and the distance really does change every call.
        val param = Param(10f, listOf(identity, curve(0f to 0.5f, 1f to 1f, input = BrushInput.distance)))
        val inputs = DabInputs(0.5f, 0.4f, 800f, 1f, 0.2f, 120f, 0.3f, 0.9f, 0.1f)
        var sink = 0f
        val started = TimeSource.Monotonic.markNow()
        for (i in 0 until 1_000_000) {
            sink += Dynamics.eval(param, inputs.copy(distancePx = i * 0.01f))
        }
        val took = started.elapsedNow().inWholeMilliseconds
        assertTrue(sink > 0f, "the loop must not be optimised away")
        assertTrue(took < 2000L, "1,000,000 evals took $took ms")
    }

    @Test
    fun curvesAreBuiltOnceAndThenReused() {
        val param = Param(10f, listOf(identity))
        Dynamics.eval(param, dab) // the first call is the one that builds
        val before = CurveCache.builds
        repeat(1_000) { Dynamics.eval(param, dab) }
        assertEquals(before, CurveCache.builds, "curves must be built once per Param, not per eval")
        // Two Params in a row, the way a dab asks for size then opacity: each is built once.
        val flow = Param(1f, listOf(half))
        Dynamics.eval(param, dab); Dynamics.eval(flow, dab)
        val afterFirst = CurveCache.builds
        repeat(10) { Dynamics.eval(param, dab); Dynamics.eval(flow, dab) }
        assertEquals(afterFirst, CurveCache.builds, "a second Param is built once too")
        // A Param with no curves never enters the cache.
        val plain = Param(1f)
        val beforePlain = CurveCache.builds
        repeat(10) { Dynamics.eval(plain, dab) }
        assertEquals(beforePlain, CurveCache.builds)
    }

    // ---- 6. one failing case per validation rule ------------------------------------------------

    @Test
    fun validationSaysOneThingPerRule() {
        // 1 — format and version.
        assertSole(BrushValidate.validate(preset { it.copy(version = 2) }), "newer Joy Brush")
        assertSole(BrushValidate.validate(preset { it.copy(format = "photoshop.abr") }), "expected \"joybrush.brush\"")
        // 2 — id.
        assertSole(BrushValidate.validate(preset { it.copy(id = "  ") }), "id is empty")
        // 3 — size, now with its cap.
        assertSole(BrushValidate.validate(preset { it.copy(size = Param(0f)) }), "size.base must be above 0 and at most 4096, is 0.0")
        // 4 — spacing.
        assertSole(BrushValidate.validate(preset { it.copy(spacing = 0.004f) }), "spacing 0.004 is outside")
        assertSole(BrushValidate.validate(preset { it.copy(spacing = 5.1f) }), "spacing 5.1 is outside")
        // 5 — tip corner.
        assertSole(BrushValidate.validate(preset { it.copy(tip = it.tip.copy(corner = 0.4f)) }), "tip.corner 0.4 is outside")
        assertSole(BrushValidate.validate(preset { it.copy(tip = it.tip.copy(corner = 65f)) }), "tip.corner 65.0 is outside")
        // 6 — taper.
        assertSole(BrushValidate.validate(preset { it.copy(tip = it.tip.copy(taper = 1.2f)) }), "tip.taper 1.2 is outside")
        // 7 — aspect.
        assertSole(BrushValidate.validate(preset { it.copy(tip = it.tip.copy(aspect = -1.5f)) }), "tip.aspect -1.5 is outside")
        assertSole(BrushValidate.validate(preset { it.copy(tip = it.tip.copy(aspect = 2f)) }), "tip.aspect 2.0 is outside")
        // 18 — curve points. The rules 8 to 17 (the numbers that gained a range, and the caps) are
        // covered by the tests further down; the numbering follows BrushValidate.
        assertSole(
            BrushValidate.validate(preset { it.copy(size = Param(6f, listOf(InputCurve(BrushInput.pressure, emptyList())))) }),
            "has no points",
        )
        assertSole(
            BrushValidate.validate(preset { it.copy(size = Param(6f, listOf(InputCurve(BrushInput.pressure, listOf(listOf(0f, 1f, 2f)))))) }),
            "has a point of 3 numbers, not 2, at point 1",
        )
        // 20 — combine. Every Param in the preset is checked, not just the obvious ones: break one
        // deep inside the tip and one inside the scatter, and each is named.
        assertSole(
            BrushValidate.validate(preset { it.copy(opacity = Param(1f, emptyList(), combine = "average")) }),
            "combine must be multiply or add, on: opacity (\"average\")",
        )
        assertSole(
            BrushValidate.validate(preset { it.copy(tip = it.tip.copy(hardness = Param(1f, emptyList(), combine = "x"))) }),
            "on: tip.hardness (\"x\")",
        )
        assertSole(
            BrushValidate.validate(preset { it.copy(scatter = ScatterSpec(amount = Param(0f, listOf(InputCurve(BrushInput.pressure, emptyList()))))) }),
            "scatter.amount input 1 (pressure) has no points",
        )
        assertSole(
            BrushValidate.validate(preset { it.copy(flow = Param(1f, listOf(InputCurve(BrushInput.tilt, listOf(listOf(0f, 1f, 0f)))))) }),
            "flow input 1 (tilt)",
        )
        // 21 — engine, accumulate, blend are one rule with three words in it.
        val wrongWords = preset { it.copy(engine = "water", accumulate = "dry", blend = "multiply") }
        val words = BrushValidate.validate(wrongWords)
        assertEquals(1, words.size, "expected one message, got $words")
        for (w in listOf("engine \"water\"", "accumulate \"dry\"", "blend \"multiply\"")) {
            assertTrue(words.single().contains(w), "message was: ${words.single()}")
        }
        // 23 — an image source needs an image.
        assertSole(BrushValidate.validate(preset { it.copy(tip = it.tip.copy(source = "image")) }), "no image path is set: tip")
        assertSole(
            BrushValidate.validate(preset { it.copy(tipTexture = GrainSpec(enabled = true, source = "image")) }),
            "no image path is set: tipTexture",
        )
        assertSole(
            BrushValidate.validate(preset { it.copy(paperGrain = GrainSpec(enabled = true, source = "image", image = " ")) }),
            "no image path is set: paperGrain",
        )
        // The edges of every range are allowed, and untouched parts stay quiet.
        assertEquals(
            emptyList(),
            BrushValidate.validate(
                preset {
                    it.copy(
                        spacing = 0.005f, tip = it.tip.copy(corner = 64f, taper = 1f, aspect = -1f),
                        tipTexture = GrainSpec(enabled = true, source = "image", image = "tip.png"),
                    )
                },
            ),
        )
        // A number that no rule used to range is still a number this build cannot write, and now it
        // is named too (JB-0.03b, ruling 3) instead of only being caught when the file is saved.
        assertSole(
            BrushValidate.validate(preset { it.copy(tip = it.tip.copy(hardness = Param(Float.NaN))) }),
            "not a finite number: tip.hardness.base = NaN",
        )
        assertFailsWith<BrushException> { BrushJson.encode(preset { it.copy(tip = it.tip.copy(hardness = Param(Float.NaN))) }) }
        assertFailsWith<BrushException> { BrushJson.encode(preset { it.copy(smoothing = Float.POSITIVE_INFINITY) }) }
    }

    @Test
    fun aNaNInARangedNumberIsAProblemNotAPass() {
        // A NaN spacing would walk the dab loop off into never-land, so the range rules catch it.
        assertSole(BrushValidate.validate(preset { it.copy(spacing = Float.NaN) }), "spacing NaN is outside 0.005..5")
        assertSole(BrushValidate.validate(preset { it.copy(tip = it.tip.copy(corner = Float.NaN)) }), "tip.corner NaN is outside")
        assertSole(BrushValidate.validate(preset { it.copy(tip = it.tip.copy(taper = Float.NaN)) }), "tip.taper NaN is outside")
        assertSole(BrushValidate.validate(preset { it.copy(tip = it.tip.copy(aspect = Float.NaN)) }), "tip.aspect NaN is outside")
        // …and so is one in size, which the size rule has always caught.
        assertSole(BrushValidate.validate(preset { it.copy(size = Param(Float.NaN)) }), "size.base must be above 0 and at most 4096, is NaN")
        // A `!in a..b` rule catches the infinities as well as the NaN, and says which one it was.
        assertSole(BrushValidate.validate(preset { it.copy(spacing = Float.POSITIVE_INFINITY) }), "spacing Infinity is outside 0.005..5")
        assertSole(BrushValidate.validate(preset { it.copy(spacing = Float.NEGATIVE_INFINITY) }), "spacing -Infinity is outside 0.005..5")
        assertSole(BrushValidate.validate(preset { it.copy(tip = it.tip.copy(taper = Float.POSITIVE_INFINITY)) }), "tip.taper Infinity is outside 0..1")
    }

    // ---- 7. the size that froze a stroke (Lead ruling R1) ----------------------------------------

    /**
     * The reviewer's own case, byte for byte — and the layer that actually stops it.
     *
     * `"base": 1e999` never becomes a [BrushPreset]: kotlinx checks every float it decodes for
     * finiteness, so `BrushJson.decode` throws [BrushException] and there is no Infinity for
     * [BrushValidate] to catch on this path. The message names the field (`$.size.base`), so the
     * person is told which number is wrong, in the parser's words rather than ours.
     *
     * That check is on the decoded *value*, not on the spelling, so an exponent that overflows a
     * Float lands on the same Infinity and is refused the same way — even though `1e40` is a
     * perfectly well-formed JSON number. Both are here so the assumption "no file can carry a
     * non-finite number" is pinned by a test instead of by one data point.
     *
     * A `Param` holding Infinity still has to be refused by validation, because one can be built in
     * Kotlin (`everyNonFiniteSizeIsRefusedByName` below), and the engine is the guard after that:
     * `DabPlacer` clamps every dab, so a bad brush cannot freeze a stroke either.
     */
    @Test
    fun anInfiniteBrushSizeIsRefusedBeforeItReachesTheEngine() {
        for (literal in listOf("1e999", "1e40", "-1e999")) {
            val hostile = inkJson.replace("\"base\": 6,", "\"base\": $literal,")
            val thrown = assertFailsWith<BrushException>("\"base\": $literal must not decode") {
                BrushJson.decode(hostile)
            }
            assertTrue(thrown.message.orEmpty().contains("size.base"), "message was: ${thrown.message}")
        }
    }

    @Test
    fun everyNonFiniteSizeIsRefusedByName() {
        // `!(size > 0f)` catches NaN and −Infinity but lets +Infinity through, so the rule is
        // `!(size > 0f) || size > 4096f`. All three are refused, and the message says which.
        assertSole(BrushValidate.validate(preset { it.copy(size = Param(Float.POSITIVE_INFINITY)) }), "at most 4096, is Infinity")
        assertSole(BrushValidate.validate(preset { it.copy(size = Param(Float.NEGATIVE_INFINITY)) }), "at most 4096, is -Infinity")
        assertSole(BrushValidate.validate(preset { it.copy(size = Param(Float.NaN)) }), "at most 4096, is NaN")
        // The cap's own edges: 4096 px is a brush, 4097 px is a mistake.
        assertEquals(emptyList(), BrushValidate.validate(preset { it.copy(size = Param(4096f)) }))
        assertSole(BrushValidate.validate(preset { it.copy(size = Param(4097f)) }), "at most 4096, is 4097.0")
        // A size that small it cannot be seen is still "above 0" as far as this rule is concerned —
        // see the Questions in JB-0.03b about the missing floor.
        assertEquals(emptyList(), BrushValidate.validate(preset { it.copy(size = Param(1e-40f)) }))
        // Why the rule has to exist at all: the evaluator is deliberately unclamped, so a Param that
        // does reach the engine hands it Infinity, and it is the dab loop's step that freezes.
        assertEquals(Float.POSITIVE_INFINITY, Dynamics.eval(Param(Float.POSITIVE_INFINITY), dab))
    }

    // ---- 8. the rulings that are not about size ---------------------------------------------------

    @Test
    fun aVersionFromNoOneIsRefused() {
        // Version 0 is read with version 1's rules, and there is no such file.
        assertSole(BrushValidate.validate(preset { it.copy(version = 0) }), "unknown brush version 0")
        assertSole(BrushValidate.validate(preset { it.copy(version = -3) }), "unknown brush version -3")
        // …and the refusal is still one message when the rest of the header is fine.
        assertSole(BrushValidate.validate(BrushJson.decode(inkJson.replace("\"version\": 1,", "\"version\": 0,"))), "unknown brush version 0")
    }

    @Test
    fun everyRangedNumberIsChecked() {
        // smoothing.
        assertSole(BrushValidate.validate(preset { it.copy(smoothing = 1.2f) }), "smoothing 1.2 is outside 0..1")
        // The tip's smallest visible size.
        assertSole(BrushValidate.validate(preset { it.copy(tip = it.tip.copy(minPx = 0.1f)) }), "tip.minPx 0.1 is outside 0.25..16")
        assertSole(BrushValidate.validate(preset { it.copy(tip = it.tip.copy(minPx = 17f)) }), "tip.minPx 17.0 is outside 0.25..16")
        // The two jitters.
        assertSole(BrushValidate.validate(preset { it.copy(sizeJitter = -0.1f) }), "sizeJitter -0.1 is outside 0..1")
        assertSole(BrushValidate.validate(preset { it.copy(angleJitter = 400f) }), "angleJitter 400.0 is outside 0..360")
        // Scatter.
        assertSole(BrushValidate.validate(preset { it.copy(scatter = ScatterSpec(count = 0)) }), "scatter.count 0 is outside 1..16")
        assertSole(BrushValidate.validate(preset { it.copy(scatter = ScatterSpec(count = 17)) }), "scatter.count 17 is outside 1..16")
        assertSole(BrushValidate.validate(preset { it.copy(scatter = ScatterSpec(countJitter = 2f)) }), "scatter.countJitter 2.0 is outside 0..1")
        // The grains: one message per rule, naming whichever grain broke it.
        assertSole(BrushValidate.validate(preset { it.copy(paperGrain = GrainSpec(enabled = true, scale = 0f)) }), "paperGrain.scale 0.0")
        assertSole(BrushValidate.validate(preset { it.copy(tipTexture = GrainSpec(enabled = true, scale = Float.POSITIVE_INFINITY)) }), "tipTexture.scale Infinity")
        assertSole(BrushValidate.validate(preset { it.copy(paperGrain = GrainSpec(enabled = true, edge = 1.4f)) }), "paperGrain.edge 1.4 is outside 0..1")
        assertSole(BrushValidate.validate(preset { it.copy(tipTexture = GrainSpec(enabled = true, tiltGradient = 9f)) }), "tipTexture.tiltGradient 9.0 is outside -4..4")
        assertSole(BrushValidate.validate(preset { it.copy(paperGrain = GrainSpec(enabled = true, radial = -1f)) }), "paperGrain.radial -1.0 is outside 0..4")
        // Colour jitter: all three in one message.
        val colours = BrushValidate.validate(preset { it.copy(color = ColorJitter(hue = 2f, value = 1.2f)) })
        assertEquals(1, colours.size, "expected one message, got $colours")
        assertTrue(colours.single().contains("hue 2.0"), "message was: ${colours.single()}")
        assertTrue(colours.single().contains("value 1.2"), "message was: ${colours.single()}")
        // Two grains breaking the same rule share one message, so the person fixes both at once.
        val bothGrains = BrushValidate.validate(
            preset { it.copy(tipTexture = GrainSpec(scale = 100f), paperGrain = GrainSpec(edge = 2f)) },
        )
        assertEquals(2, bothGrains.size, "expected two messages, got $bothGrains")
        assertTrue(bothGrains.any { it.contains("tipTexture.scale 100.0") }, "got $bothGrains")
        assertTrue(bothGrains.any { it.contains("paperGrain.edge 2.0") }, "got $bothGrains")
        // Every new bound includes its edges, and the caps are at their limits too.
        assertEquals(
            emptyList(),
            BrushValidate.validate(
                preset {
                    it.copy(
                        size = Param(4096f, listOf(InputCurve(BrushInput.pressure, flatCurve(64)))),
                        tip = it.tip.copy(minPx = 0.25f),
                        smoothing = 1f, sizeJitter = 1f, angleJitter = 360f,
                        scatter = ScatterSpec(amount = Param(0f), count = 16, countJitter = 1f),
                        tipTexture = GrainSpec(enabled = true, scale = 64f, edge = 1f, tiltGradient = -4f, radial = 4f),
                        paperGrain = GrainSpec(enabled = true, scale = 0.001f, edge = 0f, tiltGradient = 4f, radial = 0f),
                        color = ColorJitter(hue = 1f, saturation = 1f, value = 1f),
                    )
                },
            ),
            "the edges of every new range are allowed",
        )
    }

    @Test
    fun everyBaseNoRuleRangedMustStillBeANumber() {
        // These seven bases have no range yet (see the table in JB-0.03b), so all that can be asked
        // of them is that they are numbers: 1e999 decodes to +Infinity, which draws nothing and
        // which JSON cannot write back out.
        assertSole(
            BrushValidate.validate(preset { it.copy(tip = it.tip.copy(hardness = Param(Float.POSITIVE_INFINITY))) }),
            "not a finite number: tip.hardness.base = Infinity",
        )
        assertSole(
            BrushValidate.validate(preset { it.copy(tip = it.tip.copy(angle = Param(Float.NaN))) }),
            "not a finite number: tip.angle.base = NaN",
        )
        assertSole(BrushValidate.validate(preset { it.copy(opacity = Param(Float.NEGATIVE_INFINITY)) }), "opacity.base = -Infinity")
        assertSole(BrushValidate.validate(preset { it.copy(flow = Param(Float.NaN)) }), "flow.base = NaN")
        assertSole(
            BrushValidate.validate(preset { it.copy(paperGrain = it.paperGrain.copy(depth = Param(Float.POSITIVE_INFINITY))) }),
            "paperGrain.depth.base = Infinity",
        )
        assertSole(
            BrushValidate.validate(preset { it.copy(tipTexture = it.tipTexture.copy(depth = Param(Float.NaN))) }),
            "tipTexture.depth.base = NaN",
        )
        assertSole(
            BrushValidate.validate(preset { it.copy(scatter = it.scatter.copy(amount = Param(Float.NEGATIVE_INFINITY))) }),
            "scatter.amount.base = -Infinity",
        )
        // size.base is the one base a range does speak for, so the size rule names it instead.
        assertSole(
            BrushValidate.validate(preset { it.copy(size = Param(Float.POSITIVE_INFINITY)) }),
            "size.base must be above 0 and at most 4096, is Infinity",
        )
    }

    @Test
    fun aFileCannotCarryUnlimitedCurve() {
        // The caps are 64 points in one curve and 8 inputs on one Param, and both edges are allowed.
        assertEquals(emptyList(), BrushValidate.validate(preset { it.copy(size = Param(6f, listOf(InputCurve(BrushInput.pressure, flatCurve(64))))) }))
        assertEquals(emptyList(), BrushValidate.validate(preset { it.copy(size = Param(6f, eightInputs)) }))
        // One point over the point cap, named with the count that broke it. (Note 64, not 8: a curve
        // may hold far more points than a Param may hold inputs.)
        assertSole(
            BrushValidate.validate(preset { it.copy(size = Param(6f, listOf(InputCurve(BrushInput.pressure, flatCurve(65))))) }),
            "size input 1 (pressure) has 65 points, at most 64",
        )
        // A curve far over the point cap says so too, rather than being quietly truncated.
        assertSole(
            BrushValidate.validate(preset { it.copy(tip = it.tip.copy(hardness = Param(1f, listOf(InputCurve(BrushInput.pressure, flatCurve(1000)))))) }),
            "tip.hardness input 1 (pressure) has 1000 points, at most 64",
        )
        // One input over the input cap, named with the setting it is on…
        assertSole(BrushValidate.validate(preset { it.copy(size = Param(6f, nineInputs)) }), "size has 9 inputs, at most 8")
        // …and the same when it is deep inside the tip rather than on the obvious setting.
        assertSole(
            BrushValidate.validate(preset { it.copy(tip = it.tip.copy(hardness = Param(1f, nineInputs))) }),
            "tip.hardness has 9 inputs, at most 8",
        )
    }

    @Test
    fun aCurvePointIsAPairOfNumbersInZeroToOne() {
        fun withPoints(vararg pts: List<Float>) =
            preset { it.copy(size = Param(6f, listOf(InputCurve(BrushInput.pressure, pts.toList())))) }
        // x is where the input sits: outside 0..1 it is silently clamped, so it is a mistake.
        assertSole(BrushValidate.validate(withPoints(listOf(-0.5f, 1f))), "point 1: x -0.5 is not in 0..1")
        assertSole(BrushValidate.validate(withPoints(listOf(1.5f, 1f))), "point 1: x 1.5 is not in 0..1")
        assertSole(BrushValidate.validate(withPoints(listOf(Float.NaN, 0.5f))), "point 1: x NaN is not in 0..1")
        assertSole(BrushValidate.validate(withPoints(listOf(Float.POSITIVE_INFINITY, 0.5f))), "point 1: x Infinity is not in 0..1")
        // y is the setting's own value, so it is not ranged — it only has to be a number.
        assertSole(BrushValidate.validate(withPoints(listOf(0.5f, Float.POSITIVE_INFINITY))), "point 1: y Infinity is not a number")
        assertSole(BrushValidate.validate(withPoints(listOf(0.5f, Float.NEGATIVE_INFINITY))), "point 1: y -Infinity is not a number")
        assertSole(BrushValidate.validate(withPoints(listOf(0.5f, Float.NaN))), "point 1: y NaN is not a number")
        // A y outside 0..1 is legal — size curves go there on purpose.
        assertEquals(emptyList(), BrushValidate.validate(withPoints(listOf(0f, 4f), listOf(1f, 9f))))
        // A point that is not a pair is the curve rule's message, and the numbers rule leaves it be.
        assertSole(BrushValidate.validate(withPoints(listOf(0f))), "has a point of 1 numbers, not 2, at point 1")
    }

    @Test
    fun aSourceIsAWordThisBuildKnows() {
        assertSole(BrushValidate.validate(preset { it.copy(tip = it.tip.copy(source = "Photo")) }), "on: tip (\"Photo\")")
        // "cloud" is a grain word and "procedural" is a tip word; neither is the other's.
        assertSole(BrushValidate.validate(preset { it.copy(tipTexture = GrainSpec(enabled = true, source = "procedural")) }), "on: tipTexture (\"procedural\")")
        assertSole(BrushValidate.validate(preset { it.copy(paperGrain = GrainSpec(enabled = true, source = "noise")) }), "on: paperGrain (\"noise\")")
        // Both grains in one message, and the two legal words still load clean.
        val both = BrushValidate.validate(
            preset { it.copy(tipTexture = GrainSpec(source = "x"), paperGrain = GrainSpec(source = "y")) },
        )
        assertEquals(1, both.size, "expected one message, got $both")
        assertTrue(both.single().contains("tipTexture (\"x\")"), "message was: ${both.single()}")
        assertTrue(both.single().contains("paperGrain (\"y\")"), "message was: ${both.single()}")
        assertEquals(
            emptyList(),
            BrushValidate.validate(
                preset {
                    it.copy(
                        tip = it.tip.copy(source = "image", image = "tip.png"),
                        tipTexture = GrainSpec(enabled = true, source = "image", image = "t.png"),
                        paperGrain = GrainSpec(enabled = true, source = "cloud"),
                    )
                },
            ),
        )
    }
}

