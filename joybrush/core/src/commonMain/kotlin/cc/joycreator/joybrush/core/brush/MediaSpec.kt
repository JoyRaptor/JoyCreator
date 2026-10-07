package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.media.DOC_PX_PER_MM
import cc.joycreator.joybrush.core.media.PasteBrush
import cc.joycreator.joybrush.core.media.PasteBrushes
import cc.joycreator.joybrush.core.media.Stick
import cc.joycreator.joybrush.core.media.Sticks
import cc.joycreator.joybrush.core.media.THINNER
import cc.joycreator.joybrush.core.media.WETNESS
import cc.joycreator.joybrush.core.media.WetBrush
import cc.joycreator.joybrush.core.media.WetBrushes
import kotlinx.serialization.Serializable

/** The media engine (brush version 8, MEDIA_ENGINE_PLAN §4): pencil, watercolour and oil on a media layer. */
const val ENGINE_MEDIA = "media"

/** The brush version that introduced `engine: "media"` and the `media` section. */
const val VERSION_MEDIA = 8

/** The three media, as the file says them: a stick on the paper, water carrying pigment, paint with body. */
const val MEDIUM_DRY = "dry"
const val MEDIUM_WET = "wet"
const val MEDIUM_PASTE = "paste"
val MEDIA_KINDS: List<String> = listOf(MEDIUM_DRY, MEDIUM_WET, MEDIUM_PASTE)

/**
 * Where a hair paste brush's second colour (its belly) comes from, in the lab's words (owner, 2026-10-07: choosing a
 * second colour every stroke gets tedious). "manual" takes the colour the person picked for the belly.
 */
val BELLY_MODES: List<String> = listOf("off", "manual", "darker", "lighter", "warmer", "cooler", "last colour", "shift")

/**
 * What a media brush is (read only when `engine == "media"`). The brush CARRIES its tool's numbers, in exactly the lab's
 * table form ([Stick], [WetBrush], [PasteBrush]), so a tuned or shared brush paints the same everywhere and nothing is
 * looked up by name at paint time; [tool] is the table name it started from, kept for people and for the drawer.
 *
 * Exactly one of [stick], [wet] and [paste] is set, the one [medium] names. [wetness], [thinner] and [belly] are the
 * brush's state the canvas bar taps through (wetness for watercolour, thinner and belly for oil); each brush keeps its own.
 *
 * Size: the file's `size.base` is the tool's width in document px; the shipped brushes start at [referencePx], and
 * the engine scales every length of the tool by `size.base / referencePx` ([mediaScale]). The owner's size slider then
 * does what it does on every other brush.
 */
@Serializable data class MediaSpec(
    val medium: String,
    val tool: String,
    val stick: Stick? = null,
    val wet: WetBrush? = null,
    val paste: PasteBrush? = null,
    /** WETNESS level, 0 dry … 4 runny. A watercolour brush's water. */
    val wetness: Int = 3,
    /** THINNER level, 0 neat … 3 runny. An oil brush's thinner, which rides along as water. */
    val thinner: Int = 0,
    /** One of [BELLY_MODES]. Only a hair paste brush has a belly. */
    val belly: String = "off",
) {
    /** The tool's natural width in mm: the point at full pressure, the belly, the bristles, or the blade. */
    val referenceMm: Double get() = when {
        stick != null -> stick.tipMax * 2
        wet != null -> wet.bellyMm
        paste != null -> if (paste.blade) paste.bladeLenMm else paste.widthMm
        else -> 1.0
    }

    val referencePx: Float get() = (referenceMm * DOC_PX_PER_MM).toFloat()

    /** True for a paste brush made of hair (it has a belly); a knife or scraper does not. */
    val isHair: Boolean get() = paste != null && !paste.blade && !paste.trowel
}

/**
 * This spec's tool at [k] times its size: every length across the paper grows, and what is measured INTO the paper (paint
 * thickness, a blade's reach and taper, the tooth) does not, so a bigger brush is a bigger brush and not a deeper one. A
 * watercolour brush holds water in proportion to its area, so a big brush still carries a stroke as far.
 */
fun MediaSpec.scaled(k: Double): MediaSpec {
    if (k == 1.0) return this
    return copy(
        stick = stick?.let { it.copy(tipR = it.tipR * k, tipMax = it.tipMax * k, side1 = it.side1 * k, side2 = it.side2 * k, face = it.face * k) },
        wet = wet?.let { it.copy(bellyMm = it.bellyMm * k, tipMm = it.tipMm * k, beadMm = it.beadMm * k, liftMm = it.liftMm * k,
            capacityMm3 = it.capacityMm3 * k * k) },
        paste = paste?.let { it.copy(widthMm = it.widthMm * k, lenMm = it.lenMm * k, tipHalfMm = it.tipHalfMm * k,
            edgeMinMm = it.edgeMinMm * k, acrossMaxMm = it.acrossMaxMm * k, bladeLenMm = it.bladeLenMm * k,
            bladeHalfMm = it.bladeHalfMm * k) },
    )
}

/** How much the media engine scales the tool's lengths: the brush's size against the tool's natural width. */
fun BrushPreset.mediaScale(): Double {
    val m = media ?: return 1.0
    return size.base / m.referencePx.toDouble()
}

/**
 * The shipped media brushes, built from the lab's tables so the two can never disagree. Ids are
 * `media:<medium>:<tool>` (contract point 6): names repeat across media, so the medium is part of the id. A name that
 * appears in more than one medium says which in brackets, so the drawer's "All" shelf never shows two the same.
 */
object MediaPresets {

    private fun preset(medium: String, tool: String, spec: MediaSpec, label: String): BrushPreset = BrushPreset(
        id = "media:$medium:$tool",
        name = label,
        engine = ENGINE_MEDIA,
        size = Param(spec.referencePx),
        smoothing = 0f,
        author = "Joy Creator",
        media = spec,
    )

    val ALL: List<BrushPreset> by lazy {
        val repeated = (Sticks.ALL.keys.toList() + WetBrushes.ALL.keys + PasteBrushes.ALL.keys).groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
        fun label(tool: String, mediumName: String) = if (tool in repeated) "$tool ($mediumName)" else tool
        Sticks.ALL.map { (n, s) -> preset(MEDIUM_DRY, n, MediaSpec(MEDIUM_DRY, n, stick = s), label(n, "pencil")) } +
            WetBrushes.ALL.map { (n, w) -> preset(MEDIUM_WET, n, MediaSpec(MEDIUM_WET, n, wet = w), label(n, "watercolour")) } +
            PasteBrushes.ALL.map { (n, b) -> preset(MEDIUM_PASTE, n, MediaSpec(MEDIUM_PASTE, n, paste = b), label(n, "oil")) }
    }

    /** The WETNESS and THINNER level names, for the canvas bar and the knobs. */
    val WETNESS_NAMES: List<String> get() = WETNESS.map { it.name }
    val THINNER_NAMES: List<String> get() = THINNER.map { it.name }
}
