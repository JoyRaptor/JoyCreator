package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.StabilizerMode
import cc.joycreator.joybrush.core.input.StabilizerSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * DRAFT — not compiled/tested. Requires the D8 BrushPreset.stabilizer suggestion field.
 * One stamp family. Names are mode words because its shelf supplies "Unibrush" once.
 * New modes are independent recipes; no historical preset is migrated or overwritten.
 */
object UnibrushModes {
    const val FAMILY_KEY = "unibrush.family"
    const val BUILTIN_FAMILY = "builtin"
    const val TEXTURE_PATH = "unibrush/tip-texture.png"
    const val DRY_PATH = "unibrush/dual-dry.png"
    const val SPARKLE_PATH = "unibrush/tip-sparkle.png"
    /** Foliage's four leaves, in the order the sampled tip lists them. */
    val FOLIAGE_PATHS: List<String> = listOf("unibrush/tip-leaf-1.png", "unibrush/tip-leaf-2.png",
        "unibrush/tip-leaf-3.png", "unibrush/tip-leaf-4.png")
    /** Hatch's three sheets, lightest pressure first: open lines, close lines, crossed lines. */
    val HATCH_PATHS: List<String> = listOf("unibrush/tip-hatch-open.png", "unibrush/tip-hatch-close.png",
        "unibrush/tip-hatch-cross.png")

    /** Fresh model graph on every call. A sheet edits/saves a copy instead of the built-in entry. */
    fun presets(): List<BrushPreset> {
        val soft = pressure(base("soft", "Soft", 256f, hardness = 0f,
            profile = TipProfile.gaussian), PressureBehavior.ink).copy(
            flow = Param(.08f, listOf(pressureCurve(0f, 1f))), spacing = .15f,
            accumulate = "buildup", smoothing = 0f,
            stabilizer = StabilizerSpec(mode = StabilizerMode.off, strength = 0f))

        val hard = pressure(base("hard", "Hard", 6f, profile = TipProfile.hard),
            PressureBehavior.width).copy(stabilizer = inkingStabilizer())

        // The table's 0.15 width/height ratio becomes 0.85 thinness in the current
        // aspect model (remaining width = 1-aspect). Corners are the modern quad.
        val calligraphyBase = base("calligraphy", "Calligraphy", 28f,
            roundness = 0f, profile = TipProfile.hard, hardness = 1f)
        val calligraphy = BrushBehaviors.tilt(pressure(calligraphyBase.copy(
            tip = calligraphyBase.tip.copy(aspect = .85f, angle = Param(-45f))),
            PressureBehavior.width), TiltBehavior.flatten).let {
            BrushBehaviors.rotation(it, TipAngleSource.penAzimuth)
        }.copy(spacing = .06f)

        val taper = pressure(base("taper", "Taper", 14f, hardness = .85f),
            PressureBehavior.width).let { pen ->
            pen.copy(size = pen.size.copy(inputs = pen.size.inputs.map {
                if (it.input == BrushInput.pressure) InputCurve(BrushInput.pressure,
                    listOf(listOf(0f,.06f), listOf(.15f,.12f), listOf(.5f,.45f), listOf(1f,1f))) else it
            }), line = LineStyleSpec(taperIn = 1.1f, taperOut = 2.8f,
                taperTip = .04f, taperShape = 1.6f), stabilizer = inkingStabilizer())
        }

        val fadeBase = base("fade", "Fade", 32f, hardness = .45f)
        val fade = pressure(fadeBase.copy(tip = fadeBase.tip.copy(fade = TipFadeSpec(
            amount = Param(.8f), angle = 0f, relativeTo = FadeFrame.canvas))), PressureBehavior.ink)

        val textureBase = base("texture", "Texture", 48f, hardness = .85f,
            roundness = .3f, profile = TipProfile.hard)
        val texture = pressure(textureBase.copy(tip = textureBase.tip.copy(
            sampled = SampledTipSpec(listOf(TEXTURE_PATH), spacingJitter = .12f)),
            // Tip grain rotates with the nib; paper grain reads the document's fixed canvas surface.
            tipTexture = GrainSpec(enabled = true, scale = 1.5f, depth = Param(.45f)),
            paperGrain = GrainSpec(enabled = true, depth = Param(.35f)),
            // Pigment catches the side of each bump of the paper that faces the stroke (owner, 2026-10-23: on by default).
            paper = PaperResponse(influence = .4f, directional = .6f), spacing = .12f), PressureBehavior.both)

        val round = pressure(base("round", "Round", 16f, hardness = .85f), PressureBehavior.both)

        val chiselBase = base("chisel", "Chisel", 32f, roundness = .08f,
            hardness = .9f, profile = TipProfile.smooth)
        val chisel = pressure(chiselBase.copy(tip = chiselBase.tip.copy(
            aspect = .55f, taper = .4f, angle = Param(30f), fade = TipFadeSpec(
                amount = Param(.65f), relativeTo = FadeFrame.penAzimuth)),
            opacity = Param(.7f), flow = Param(.8f), accumulate = "wash", spacing = .08f),
            PressureBehavior.ink)

        val living = pressure(base("living", "Living", 7f, profile = TipProfile.hard),
            PressureBehavior.width).copy(color = ColorJitter(dynamics = ColorDynamicsSpec(
                mode = ColorMode.perStrokeDrift, hueRangeDegrees = 18f, saturationRange = .1f,
                driftDistancePx = 64f)), stabilizer = inkingStabilizer())

        val dryBase = base("dry", "Dry", 40f, hardness = .65f)
        val dry = pressure(dryBase.copy(tip = dryBase.tip.copy(dual = DualTipSpec(
            shape = null, image = DualImageSpec(DRY_PATH), scale = .28f, spacing = .55f,
            scatter = .5f, mode = DualMode.multiply)), flow = Param(.65f), spacing = .12f,
            // A dry brush skips over the paper's tooth and catches the bumps facing its travel, as the bristle brush does.
            paperGrain = GrainSpec(enabled = true, depth = Param(.35f)),
            paper = PaperResponse(influence = .6f, directional = .8f)),
            PressureBehavior.ink)

        val sparkleBase = base("sparkle", "Sparkle", 20f, roundness = 0f,
            hardness = 1f, profile = TipProfile.hard)
        val sparkle = pressure(sparkleBase.copy(tip = sparkleBase.tip.copy(
            sampled = SampledTipSpec(listOf(SPARKLE_PATH), spacingJitter = .4f)),
            spacing = 1f, sizeJitter = .45f, angleJitter = 180f,
            scatter = ScatterSpec(amount = Param(1.2f), count = 2, countJitter = .5f),
            color = ColorJitter(dynamics = ColorDynamicsSpec(mode = ColorMode.perDab,
                hueRangeDegrees = 45f, saturationRange = .2f, valueRange = .1f,
                secondaryMix = .2f)), smoothing = .1f,
            stabilizer = StabilizerSpec(mode = StabilizerMode.off, strength = 0f)), PressureBehavior.width)

        // ---- The 2026-11-13 brush set. Appended only: the eleven modes above keep their order, ids and output. ----

        // A square stick held flat: pressure is how much chalk is left behind, tilt lays more of the stick down.
        val chalkBase = base("chalk", "Chalk", 36f, roundness = .2f, profile = TipProfile.hard)
        val chalk = BrushBehaviors.tilt(pressure(chalkBase.copy(
            tip = chalkBase.tip.copy(aspect = .25f),
            tipTexture = GrainSpec(enabled = true, scale = 2f, depth = Param(.3f)),
            // A light touch only reaches the top of the paper's tooth; pressing fills it in.
            paperGrain = GrainSpec(enabled = true, depth = Param(.9f, listOf(pressureCurve(1f, .6f)))),
            paper = PaperResponse(influence = .8f, directional = .5f),
            opacity = Param(.95f), flow = Param(.85f), spacing = .08f), PressureBehavior.ink),
            TiltBehavior.size)

        // Softer and darker than chalk, with a smeared rim; leaned over it widens and shades off to one side.
        val charcoalBase = base("charcoal", "Charcoal", 44f, roundness = .6f, hardness = .35f)
        val charcoal = charcoalBase.copy(
            size = Param(44f, listOf(tiltCurve(1f, 2.4f))),
            tip = charcoalBase.tip.copy(fade = TipFadeSpec(
                amount = Param(0f, listOf(tiltCurve(0f, .7f)), "add"), relativeTo = FadeFrame.penAzimuth)),
            flow = Param(.9f, listOf(InputCurve(BrushInput.pressure,
                listOf(listOf(0f, .15f), listOf(.5f, .7f), listOf(1f, 1f))))),
            paperGrain = GrainSpec(enabled = true, depth = Param(.8f, listOf(pressureCurve(1f, .4f)))),
            paper = PaperResponse(influence = .7f, directional = .7f), spacing = .07f)

        // One width whatever the hand does, and a long string to pull the line straight.
        val technical = pressure(base("technical", "Technical", 4f, profile = TipProfile.hard),
            PressureBehavior.none).copy(spacing = .03f, smoothing = .5f,
            stabilizer = StabilizerSpec(mode = StabilizerMode.pulledString, strength = .8f, radiusPx = 24f))

        // The small airbrush for modelling form; Soft above is the big one for backgrounds.
        val airbrush = pressure(base("airbrush", "Airbrush", 40f, hardness = 0f,
            profile = TipProfile.gaussian), PressureBehavior.ink).copy(
            flow = Param(.06f, listOf(pressureCurve(0f, 1f))), spacing = .04f, accumulate = "buildup")

        // A stick dragged on its side: the broad face stays across the direction of travel.
        val pastelBase = base("pastel", "Pastel", 56f, roundness = .35f, hardness = .92f)
        val pastel = BrushBehaviors.rotation(pastelBase.copy(
            size = Param(56f, listOf(pressureCurve(.7f, 1f))),
            tip = pastelBase.tip.copy(aspect = .45f),
            tipTexture = GrainSpec(enabled = true, scale = 2.5f, depth = Param(.35f)),
            paperGrain = GrainSpec(enabled = true, depth = Param(.3f)),
            paper = PaperResponse(influence = .35f, directional = .3f),
            flow = Param(.95f), spacing = .05f), TipAngleSource.stroke)

        // A flat band that turns with the pen. Pressure leans its ends the other way and opens it up, and one
        // edge is always in shade, so a curving stroke reads as silk twisting over.
        val ribbonBase = base("ribbon", "Ribbon", 44f, roundness = 0f, hardness = .9f)
        val ribbon = BrushBehaviors.rotation(ribbonBase.copy(tip = ribbonBase.tip.copy(
            aspect = .9f,
            quad = TipQuadSpec(
                a = QuadShapeSpec(points = listOf(QuadPoint(-1f, -1f), QuadPoint(1f, -.4f),
                    QuadPoint(1f, 1f), QuadPoint(-1f, .4f)), roundness = .15f, aspect = .9f),
                b = QuadShapeSpec(points = listOf(QuadPoint(-1f, -.4f), QuadPoint(1f, -1f),
                    QuadPoint(1f, .4f), QuadPoint(-1f, 1f)), roundness = .5f, aspect = .6f),
                morph = Param(1f, listOf(pressureCurve(0f, 1f)))),
            fade = TipFadeSpec(amount = Param(.7f), angle = 90f, relativeTo = FadeFrame.penAzimuth)),
            flow = Param(.85f), spacing = .03f), TipAngleSource.penAzimuth)

        // The chosen colour is only the start: pressing turns the hue and it wanders along the stroke.
        val rainbow = pressure(base("rainbow", "Rainbow", 18f, hardness = .8f),
            PressureBehavior.width).copy(color = ColorJitter(dynamics = ColorDynamicsSpec(
                mode = ColorMode.perStrokeDrift, hueRangeDegrees = 120f, pressureHueDegrees = 150f,
                driftDistancePx = 160f)))

        // Four leaf shapes thrown along the branch being drawn, each a slightly different green.
        val foliageBase = base("foliage", "Foliage", 56f, roundness = 0f, profile = TipProfile.hard)
        val foliage = BrushBehaviors.rotation(foliageBase.copy(
            size = Param(56f, listOf(pressureCurve(.35f, 1f))),
            tip = foliageBase.tip.copy(sampled = SampledTipSpec(FOLIAGE_PATHS, pick = SamplePick.random,
                rotateJitterDegrees = 35f, spacingJitter = .3f)),
            spacing = .4f, sizeJitter = .4f, angleJitter = 50f,
            scatter = ScatterSpec(amount = Param(.9f), count = 2, countJitter = .5f),
            color = ColorJitter(dynamics = ColorDynamicsSpec(mode = ColorMode.perDab,
                hueRangeDegrees = 14f, saturationRange = .12f, valueRange = .18f, secondaryMix = .25f))),
            TipAngleSource.stroke)

        // Separate hard dots, bigger as the pen presses: shading by stippling.
        val stipple = pressure(base("stipple", "Stipple", 14f, profile = TipProfile.hard),
            PressureBehavior.width).copy(spacing = 1.6f, sizeJitter = .25f,
            scatter = ScatterSpec(amount = Param(.8f), count = 2, countJitter = .5f), smoothing = .1f)

        // Dash and gap are in brush widths, so the rhythm is the same at every size. Shaped at the lift.
        val dashes = pressure(base("dashes", "Dashes", 6f, profile = TipProfile.hard),
            PressureBehavior.none).copy(line = LineStyleSpec(dashOn = 4f, dashOff = 2.5f),
            spacing = .05f, stabilizer = inkingStabilizer())

        // Ruled lines that turn with the stroke. Pressure picks the sheet: open lines, close lines, crossed lines.
        // Spacing is half a tip, which is a whole number of line gaps, so neighbouring dabs join into long lines.
        val hatchBase = base("hatch", "Hatch", 64f, roundness = 0f, profile = TipProfile.hard)
        val hatch = BrushBehaviors.rotation(hatchBase.copy(
            tip = hatchBase.tip.copy(sampled = SampledTipSpec(HATCH_PATHS, pick = SamplePick.byPressure)),
            spacing = .5f), TipAngleSource.stroke)

        return listOf(soft, hard, calligraphy, taper, fade, texture, round, chisel, living, dry, sparkle,
            chalk, charcoal, technical, airbrush, pastel, ribbon, rainbow, foliage, stipple, dashes, hatch)
    }

    /** Exact logical resource closure; the platform encodes R8 as WHITE RGB + R8 alpha PNG. */
    fun imageMasks(preset: BrushPreset): Map<String, ImageMask> =
        BrushAssetPaths.required(preset).associateWith { path -> imageMask(path)
            ?: throw BrushException("Unibrush built-in image is unavailable: $path") }

    /** ImageMask owns its bytes; sharing this object never shares a writable array. */
    fun imageMask(path: String): ImageMask? = when (path) {
        TEXTURE_PATH -> textureMask
        DRY_PATH -> dryMask
        SPARKLE_PATH -> sparkleMask
        else -> FOLIAGE_PATHS.indexOf(path).takeIf { it >= 0 }?.let { leafMasks[it] }
            ?: HATCH_PATHS.indexOf(path).takeIf { it >= 0 }?.let { hatchMasks[it] }
    }

    private fun base(slug: String, name: String, size: Float, roundness: Float = 1f,
        profile: TipProfile = TipProfile.smooth, hardness: Float = 1f): BrushPreset = BrushPreset(
        id = "unibrush.$slug", name = name, engine = "stamp", size = Param(size),
        tip = TipSpec(quad = TipQuadSpec(a = QuadShapeSpec(roundness = roundness)),
            profile = TipProfileSpec(profile), hardness = Param(hardness), minPx = .75f,
            angleSource = TipAngleSource.fixed),
        spacing = .06f, smoothing = .2f,
        stabilizer = StabilizerSpec(mode = StabilizerMode.smooth, strength = .2f),
        extensions = mapOf(FAMILY_KEY to BUILTIN_FAMILY),
    )

    private fun pressure(preset: BrushPreset, behavior: PressureBehavior) =
        BrushBehaviors.pressure(preset, behavior)

    private fun pressureCurve(low: Float, high: Float) = InputCurve(BrushInput.pressure,
        listOf(listOf(0f, low), listOf(1f, high)))

    private fun tiltCurve(low: Float, high: Float) = InputCurve(BrushInput.tilt,
        listOf(listOf(0f, low), listOf(1f, high)))

    private fun inkingStabilizer() = StabilizerSpec(mode = StabilizerMode.pulledString,
        strength = .45f, radiusPx = 12f)

    // Code-native, deterministic source artwork. Does not consume any stroke RNG stream.
    private const val SIDE = 64
    private val textureMask by lazy {
        makeMask { x, y ->
            val qx = (x + .5f) / SIDE * 2f - 1f
            val qy = (y + .5f) / SIDE * 2f - 1f
            if (qx*qx + qy*qy > .94f*.94f) 0 else {
                val pore = noise(x, y)
                val fiber = noise(x / 4, y / 2)
                if (pore < .22f || fiber < .12f) 0 else (85f + 170f*fiber).toInt()
            }
        }
    }
    private val dryMask by lazy {
        makeMask { x, y ->
            val qx = (x + .5f) / SIDE * 2f - 1f
            val qy = (y + .5f) / SIDE * 2f - 1f
            val inside = qx*qx + qy*qy < .9f*.9f
            val strand = (y % 7 <= 1) && noise(x / 3, y / 7) > .34f
            if (inside && (strand || noise(x,y) > .91f)) 155 + (100f*noise(x+41,y+73)).toInt() else 0
        }
    }
    private val sparkleMask by lazy {
        // A real five-point star, outer radius0.9 and inner/outer ratio0.45.
        val points = List(10) { i ->
            val angle = -PI*.5 + i*PI/5.0
            val radius = if (i % 2 == 0) .9 else .9*.45
            QuadPoint((cos(angle)*radius).toFloat(), (sin(angle)*radius).toFloat())
        }
        makeMask { x,y ->
            var covered = 0
            for (sy in 0 until 4) for (sx in 0 until 4) {
                val qx = (x + (sx+.5f)/4f) / SIDE * 2f - 1f
                val qy = (y + (sy+.5f)/4f) / SIDE * 2f - 1f
                if (insidePolygon(qx,qy,points)) covered++
            }
            (covered*255 + 8)/16
        }
    }

    /** One leaf: its stalk end, the way it points (degrees, +y down), and its outline. All in tip units (-1..1). */
    private class Leaf(val baseX: Float, val baseY: Float, val degrees: Float, val length: Float,
        val width: Float, val skew: Float, val bend: Float)

    private val leafMasks by lazy {
        listOf(
            // Slender, a little curved.
            leafMask(Leaf(-.92f, 0f, 0f, 1.84f, .24f, .8f, .08f)),
            // Broad, widest near the stalk.
            leafMask(Leaf(-.92f, 0f, 0f, 1.84f, .46f, .7f, 0f)),
            // Bowed over, widest near the point.
            leafMask(Leaf(-.92f, -.1f, 0f, 1.84f, .32f, 1.1f, .22f)),
            // A sprig of two from one stalk.
            leafMask(Leaf(-.9f, 0f, 28f, 1.7f, .22f, .8f, .05f), Leaf(-.9f, 0f, -32f, 1.5f, .2f, .9f, -.05f)),
        )
    }

    private fun leafMask(vararg leaves: Leaf): ImageMask = makeMask { x, y ->
        var covered = 0
        for (sy in 0 until 4) for (sx in 0 until 4) {
            val qx = (x + (sx+.5f)/4f) / SIDE * 2f - 1f
            val qy = (y + (sy+.5f)/4f) / SIDE * 2f - 1f
            if (leaves.any { insideLeaf(qx, qy, it) }) covered++
        }
        (covered*255 + 8)/16
    }

    private fun insideLeaf(qx: Float, qy: Float, leaf: Leaf): Boolean {
        val angle = leaf.degrees * PI.toFloat() / 180f
        val c = cos(angle)
        val s = sin(angle)
        val dx = qx - leaf.baseX
        val dy = qy - leaf.baseY
        val t = (dx*c + dy*s) / leaf.length
        if (t <= 0f || t >= 1f) return false
        val across = (dy*c - dx*s) - leaf.bend * sin(PI.toFloat() * t)
        val half = leaf.width * sin(PI.toFloat() * t.pow(leaf.skew))
        // The midrib is an open slit from the stalk most of the way up, so a large leaf still reads as a leaf.
        return abs(across) < half && (abs(across) > .03f || t > .8f)
    }

    private const val HATCH_HALF_WIDTH = .035f
    private val hatchMasks by lazy {
        listOf(
            hatchMask { qx, qy -> hatchLine(qx, qy, false, 1f) },
            hatchMask { qx, qy -> hatchLine(qx, qy, false, .5f) },
            hatchMask { qx, qy -> hatchLine(qx, qy, false, .5f) || hatchLine(qx, qy, true, .5f) },
        )
    }

    private fun hatchMask(inside: (Float, Float) -> Boolean): ImageMask = makeMask { x, y ->
        var covered = 0
        for (sy in 0 until 4) for (sx in 0 until 4) {
            val qx = (x + (sx+.5f)/4f) / SIDE * 2f - 1f
            val qy = (y + (sy+.5f)/4f) / SIDE * 2f - 1f
            if (inside(qx, qy)) covered++
        }
        (covered*255 + 8)/16
    }

    /**
     * Diagonal ruled lines, [period] apart measured along the stroke (tip x). The tip is two units wide, so any
     * period that divides one unit repeats exactly from a dab to the next at the mode's half-tip spacing.
     * Each line stops at its own distance from the band's edge, as a hand's hatching does.
     */
    private fun hatchLine(qx: Float, qy: Float, fall: Boolean, period: Float): Boolean {
        val along = (if (fall) qx - qy else qx + qy) / period
        val line = floor(along + .5f)
        val reach = .7f + .28f * noise(line.toInt() + 97, if (fall) 5 else 3)
        return abs(along - line) * period < HATCH_HALF_WIDTH * 1.4142f && abs(qy) < reach
    }

    private fun makeMask(pixel: (Int,Int)->Int): ImageMask {
        val bytes = ByteArray(SIDE*SIDE) { i -> pixel(i%SIDE,i/SIDE).coerceIn(0,255).toByte() }
        return ImageMask.fromR8(SIDE,SIDE,bytes)
    }

    private fun noise(x: Int,y: Int): Float {
        var z = x*0x1f123bb5 xor y*0x5f356495 xor 0x31a73d25
        z = (z xor (z ushr 16))*0x45d9f3b
        z = (z xor (z ushr 16))*0x45d9f3b
        z = z xor (z ushr 16)
        return (z ushr 8).toFloat()/16777215f
    }

    private fun insidePolygon(x: Float,y: Float,points: List<QuadPoint>): Boolean {
        var inside = false
        var previous = points.last()
        for (point in points) {
            if ((point.y > y) != (previous.y > y) &&
                x < (previous.x-point.x)*(y-point.y)/(previous.y-point.y)+point.x) inside = !inside
            previous = point
        }
        return inside
    }
}
