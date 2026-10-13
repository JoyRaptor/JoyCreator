package cc.joycreator.joybrush.core.brush

import kotlinx.serialization.Serializable

const val ENGINE_WATERCOLOUR = "watercolour"
const val VERSION_WATERCOLOUR = 9 + 1

/** Which response curves modulate a control. Both selected multiply, neither means constant. */
@Serializable data class WatercolourInputs(
    val pressure: Boolean = false,
    val tilt: Boolean = false,
    val invertPressure: Boolean = false,
    val invertTilt: Boolean = false,
)

/**
 * Replayable line-watercolour settings (brush v10), independent of the raster media engine.
 * All shares/percentages are fractions 0..1; size, minimum, travel, variationLength and pooling
 * widths are DOCUMENT pixels. tipAngle and previewTilt are degrees. Curves are [x1,y1,x2,y2]
 * cubic-Bezier handles, evaluated through the POC's 257-entry float lookup table.
 *
 * Preview pressure/tilt, renderScale, worldPixels, seedLock and sampleSeeds preserve the approved
 * test-bench setup only; device readings and document coordinates drive real strokes. Actual
 * paper height always comes from the document, never the POC's procedural placeholder.
 */
@Serializable data class WatercolourSpec(
    val dabImages: List<String> = (0 until 25).map { "watercolour/poc25/dab_${it.toString().padStart(2, '0')}.png" },
    val backdrop: String = "watercolour/poc25/backdrop.png",
    val window: Int = 8,
    val spacing: Double = .15,
    val size: Double = 300.0,
    val taper: Double = 1.0,
    val tiltMinimum: Double = .55,
    val scatter: Double = .36,
    val opacity: Double = .89,
    val texture: Double = 1.0,
    val tipAngle: Double = 40.0,
    val tipWidth: Double = .08,
    val edgeAlpha: Double = .72,
    val gravityPool: Double = .57,
    val gravityWidth: Double = 36.0,
    val backdropOffset: Double = 1.0,
    val paperCut: Double = .5,
    val dryness: Double = 0.0,
    val dryBuildup: Double = .32,
    val strokeOpacity: Double = .8,
    val edgePool: Double = .65,
    val edgeWidth: Double = 3.0,
    val grain: Double = .4,
    val depletion: Double = .2,
    val travel: Double = 2569.0,
    val variation: Double = .47,
    val variationLength: Double = 227.0,
    val textureScale: Double = 1.0,
    val textureContrast: Double = 1.5,
    val minimum: Double = 1.0,
    val rotation: Double = 1.0,
    val follow: Double = 0.0,
    /** Distance-based filtering; normalized percentages, same POC distance constant. */
    val smoothPressure: Double = .15,
    val smoothTilt: Double = .2,
    val smoothPath: Double = 0.0,
    val pressureBezier: List<Double> = listOf(.33, .33, .67, .67),
    val tiltBezier: List<Double> = listOf(.33, .33, .67, .67),
    val scatterInputs: WatercolourInputs = WatercolourInputs(pressure = true, tilt = true),
    val spacingInputs: WatercolourInputs = WatercolourInputs(pressure = true),
    val spacingCompensation: Boolean = true,
    val backdropInverted: Boolean = false,
    val poolingFadeMilliseconds: Int = 1000,
    val previewPressure: Double = .71,
    val previewTilt: Double = 25.0,
    val renderScale: Double = .65,
    val worldPixels: List<Int> = listOf(1400, 900),
    val seedLock: Boolean = false,
    val sampleSeeds: List<Long> = listOf(78234, 78235, 78236),
    val approvedTint: String = "#94f92f",
)
