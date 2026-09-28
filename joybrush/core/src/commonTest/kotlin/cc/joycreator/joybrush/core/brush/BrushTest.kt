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
        // 3 — size.
        assertSole(BrushValidate.validate(preset { it.copy(size = Param(0f)) }), "size.base must be above 0")
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
        // 8 — curve points.
        assertSole(
            BrushValidate.validate(preset { it.copy(size = Param(6f, listOf(InputCurve(BrushInput.pressure, emptyList())))) }),
            "has no points",
        )
        assertSole(
            BrushValidate.validate(preset { it.copy(size = Param(6f, listOf(InputCurve(BrushInput.pressure, listOf(listOf(0f, 1f, 2f)))))) }),
            "has a point of 3 numbers, not 2, at point 1",
        )
        // 9 — combine. Every Param in the preset is checked, not just the obvious ones: break one
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
        // 10 — engine, accumulate, blend are one rule with three words in it.
        val wrongWords = preset { it.copy(engine = "water", accumulate = "dry", blend = "multiply") }
        val words = BrushValidate.validate(wrongWords)
        assertEquals(1, words.size, "expected one message, got $words")
        for (w in listOf("engine \"water\"", "accumulate \"dry\"", "blend \"multiply\"")) {
            assertTrue(words.single().contains(w), "message was: ${words.single()}")
        }
        // 11 — an image source needs an image.
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
        // A NaN in a number no rule ranges is still a number this build cannot write, and the person
        // saving the brush is told so instead of the JSON library throwing at them.
        assertEquals(
            emptyList(),
            BrushValidate.validate(preset { it.copy(tip = it.tip.copy(hardness = Param(Float.NaN))) }),
            "no rule ranges hardness today — see the Questions in JB-0.03",
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
        assertSole(BrushValidate.validate(preset { it.copy(size = Param(Float.NaN)) }), "size.base must be above 0, is NaN")
    }
}
