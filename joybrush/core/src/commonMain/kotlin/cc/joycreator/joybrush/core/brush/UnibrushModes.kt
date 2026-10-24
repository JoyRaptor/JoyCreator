package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.StabilizerMode
import cc.joycreator.joybrush.core.input.StabilizerSpec
import kotlin.math.PI
import kotlin.math.cos
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
            paper = PaperResponse(influence = .4f), spacing = .12f), PressureBehavior.both)

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
            scatter = .5f, mode = DualMode.multiply)), flow = Param(.65f), spacing = .12f),
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

        return listOf(soft, hard, calligraphy, taper, fade, texture, round, chisel, living, dry, sparkle)
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
        else -> null
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
