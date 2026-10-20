package cc.joycreator.joybrush.core.symmetry

import cc.joycreator.joybrush.core.brush.AngleFrame
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.ENGINE_PUSH
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.ENGINE_TUFT
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.MEDIUM_DRY
import cc.joycreator.joybrush.core.brush.MEDIUM_PASTE
import cc.joycreator.joybrush.core.brush.MEDIUM_WET
import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.guide.SymmetryCopy
import cc.joycreator.joybrush.core.guide.SymmetryMath

/**
 * Which brush a copy belongs to, for the symmetry's sake.
 *
 * The families are the ones the owner's answer about the caps names: the stamp family (and the
 * vector and eraser brushes, which are dabs too), the smudge family, and the media families by
 * their medium. The rest are here so that the cap table has an entry for EVERY family and a new
 * one cannot be forgotten.
 */
enum class SymmetryFamily {
    /** Plain GL dabs: the stamp family, and the vector and eraser brushes with it. */
    STAMP,
    /** The eraser: a stamp brush that takes paint away. */
    ERASER,
    /** Smudge: dabs that read the paint under them. */
    SMUDGE,
    /** Push: dabs that move the paint under them. */
    PUSH,
    /** The media families, by their medium: dry, wet and paste. */
    MEDIA_DRY,
    MEDIA_WET,
    MEDIA_PASTE,
    /** The tuft engine: footprints, not dabs. */
    TUFT,
    /** The legacy watercolour engine: its own dab array. */
    WATERCOLOUR,
    /** The fill pen: a rasterised shape, not a stroke of dabs. */
    FILL,
}

/**
 * The brush a lane draws with: a brush FILE, or the app's hard-coded round brush.
 */
sealed class LaneBrush {

    /** A brush FILE. The lane builds its own dabber and its own placer. */
    class File(val preset: BrushPreset) : LaneBrush()

    /** The app's hard-coded round brush. The lane builds its own placer. */
    class Hardcoded(val tip: HardcodedTip) : LaneBrush()
}

/**
 * The lanes of one symmetry stroke: the ORIGINAL first, then the copies.
 *
 * @property lanes the lanes, in order. Lane 0 is the original stroke.
 * @property requestedLanes how many lanes the guide asked for, before any cap.
 * @property limit the most lanes this brush may make.
 */
class SymmetryLaneSet(
    val lanes: List<SymmetryLane>,
    val requestedLanes: Int,
    val limit: Int,
) {
    /** The ORIGINAL lane: the one the copies are copies of. */
    val original: SymmetryLane get() = lanes[0]

    /** True when the guide asked for more lanes than this brush may make. */
    val capped: Boolean get() = lanes.size < requestedLanes
}

/**
 * Builds the lanes of a symmetry stroke, and says when one may not be built at all.
 *
 * ## The caps, and the words that go with them
 *
 * The owner's answer: the stamp, vector, eraser and smudge families are NOT capped below thirty
 * copies, and the hard guard is SIXTY lanes - which is exactly what a kaleidoscope of thirty
 * makes. The media families are capped per medium (dry sixteen, wet eight, paste eight), and
 * those numbers are the media phase's to measure; they live in this one table so that phase has
 * one place to fill in and cannot leave a family behind.
 *
 * A cap that bites is NOT silent: the stroke goes on with the capped set, and the app says so
 * once, in words - [capSentence]. A person who asks for thirty copies and gets eight deserves to
 * be told.
 *
 * ## The families this phase does NOT copy
 *
 * The tuft engine lays footprints, the legacy watercolour engine keeps its own dab array, the
 * fill pen rasterises a shape, and the media brushes paint through the media engine: none of them
 * is a stroke of dabs, and none of them can be copied by running a brush again. They are refused
 * in words by [refusalFor] rather than half-drawn.
 */
object SymmetryLanes {

    /**
     * The most lanes any family may make, whatever the family asks for: sixty, which is what a
     * kaleidoscope of thirty makes and the hard guard on everything else.
     */
    const val HARD_LANE_LIMIT = 60

    /**
     * The most lanes each family may make.
     *
     * The media numbers (dry sixteen, wet eight, paste eight) are the media phase's to measure;
     * they are here so that the table has ONE place for them, and a family added later has
     * exactly one number to fill in.
     */
    private val CAPS: Map<SymmetryFamily, Int> = mapOf(
        SymmetryFamily.STAMP to HARD_LANE_LIMIT,
        SymmetryFamily.ERASER to HARD_LANE_LIMIT,
        SymmetryFamily.SMUDGE to HARD_LANE_LIMIT,
        SymmetryFamily.PUSH to HARD_LANE_LIMIT,
        SymmetryFamily.MEDIA_DRY to 16,
        SymmetryFamily.MEDIA_WET to 8,
        SymmetryFamily.MEDIA_PASTE to 8,
        // The three below are refused in this phase, so their cap never bites. They are in the
        // table anyway: a family with no entry would be a family a later change could forget.
        SymmetryFamily.TUFT to HARD_LANE_LIMIT,
        SymmetryFamily.WATERCOLOUR to HARD_LANE_LIMIT,
        SymmetryFamily.FILL to HARD_LANE_LIMIT,
    )

    /**
     * The most lanes [family] may make: its own number, and never more than the hard guard.
     */
    fun laneCapFor(family: SymmetryFamily): Int =
        minOf(CAPS[family] ?: HARD_LANE_LIMIT, HARD_LANE_LIMIT)

    /**
     * The family a brush belongs to, from its engine and (for the media brushes) its medium.
     *
     * A null engine is the app's hard-coded round brush, which is a stamp brush. An eraser is an
     * eraser whatever brush it is: the eraser is a stroke of dabs that takes paint away, and the
     * media brushes' own engine is not what the eraser runs on.
     */
    fun familyFor(engine: String?, medium: String?, erase: Boolean = false): SymmetryFamily =
        if (erase) SymmetryFamily.ERASER else when (engine) {
            null -> SymmetryFamily.STAMP
            ENGINE_TUFT -> SymmetryFamily.TUFT
            ENGINE_WATERCOLOUR -> SymmetryFamily.WATERCOLOUR
            ENGINE_FILL -> SymmetryFamily.FILL
            ENGINE_MEDIA -> when (medium) {
                MEDIUM_DRY -> SymmetryFamily.MEDIA_DRY
                MEDIUM_PASTE -> SymmetryFamily.MEDIA_PASTE
                MEDIUM_WET -> SymmetryFamily.MEDIA_WET
                else -> SymmetryFamily.MEDIA_WET
            }
            ENGINE_SMUDGE -> SymmetryFamily.SMUDGE
            ENGINE_PUSH -> SymmetryFamily.PUSH
            else -> SymmetryFamily.STAMP
        }

    /**
     * The sentence for a family this phase cannot copy, or null when it can.
     *
     * A refusal in words, never a silent single lane: a person who asks for symmetry and gets
     * one stroke with no word has been told a lie by omission.
     */
    fun refusalFor(family: SymmetryFamily): String? = when (family) {
        SymmetryFamily.TUFT,
        SymmetryFamily.WATERCOLOUR,
        SymmetryFamily.FILL,
        SymmetryFamily.MEDIA_DRY,
        SymmetryFamily.MEDIA_WET,
        SymmetryFamily.MEDIA_PASTE,
        -> "Symmetry is not available for this brush."
        else -> null
    }

    /**
     * The words for a cap that bit: how many copies this brush keeps on this phone.
     */
    fun capSentence(lanes: Int): String = "This brush keeps symmetry to $lanes copies on this phone."

    /**
     * The lanes of a stroke under [guide].
     *
     * The copies come from [SymmetryMath.copies] - the ORIGINAL is lane 0 and is not among them -
     * and the family may cut the list short. The cut is REPORTED: [SymmetryLaneSet.capped] and
     * [SymmetryLaneSet.limit] say so, and the app says [capSentence].
     *
     * The refusal is NOT here: it is [refusalFor], and it is the app's gate. This builder will
     * build any family's lanes, because the media phase will need it and because the cap has to
     * be provable now, in a phase where no media brush may be drawn.
     */
    fun build(
        guide: Guide.Symmetry,
        brush: LaneBrush,
        family: SymmetryFamily,
        seed: Long,
        smoothing: Float,
        screenPerDoc: Float,
    ): SymmetryLaneSet {
        val copies = SymmetryMath.copies(guide)
        val requested = copies.size + 1
        val limit = laneCapFor(family)
        val kept = copies.take((limit - 1).coerceAtLeast(0))
        val lanes = ArrayList<SymmetryLane>(kept.size + 1)
        lanes.add(lane(null, AngleFrame.IDENTITY, brush, guide.symmetric, seedFor(guide, 0, seed), smoothing, screenPerDoc))
        for (i in kept.indices) {
            val copy = kept[i]
            lanes.add(lane(copy, frameFor(copy), brush, guide.symmetric, seedFor(guide, i + 1, seed), smoothing, screenPerDoc))
        }
        return SymmetryLaneSet(lanes, requested, limit)
    }

    /**
     * The frame of a copy: its OWN geometry, and never the guide's line.
     *
     * A reflection's own axis already carries its turn - a kaleidoscope copy's axis is the
     * guide's line turned by HALF the copy's rotation (see [SymmetryMath]) - so the copy's own
     * axis is what a reflected frame is given. A copy that is only turned is turned by its own
     * rotation.
     */
    fun frameFor(copy: SymmetryCopy): AngleFrame =
        if (copy.mirrored) AngleFrame.reflected(copy.mirrorAxis ?: 0.0)
        else AngleFrame.rotated(copy.rotation)

    /**
     * The seed of lane [index].
     *
     * With the guide's `symmetric` on, every lane has the SAME seed: the copies share one
     * brush's randomness, and a copy's texture is the original's texture seen in the copy's
     * frame. With it off, every lane has its own, one golden-ratio step apart, so each copy's
     * texture and jitter are its own.
     */
    fun seedFor(guide: Guide.Symmetry, index: Int, seed: Long): Long =
        if (guide.symmetric) seed else seed + index * LANE_STRIDE

    /** 0x9E3779B97F4A7C15 - the golden-ratio step, as a signed Long. */
    private const val LANE_STRIDE = -0x61c8864680b583ebL

    private fun lane(
        copy: SymmetryCopy?,
        frame: AngleFrame,
        brush: LaneBrush,
        symmetric: Boolean,
        seed: Long,
        smoothing: Float,
        screenPerDoc: Float,
    ): SymmetryLane = when (brush) {
        is LaneBrush.File -> SymmetryLane.forFile(
            copy = copy,
            frame = frame,
            preset = brush.preset,
            seed = seed,
            symmetric = symmetric,
            smoothing = smoothing,
            screenPerDoc = screenPerDoc,
        )
        is LaneBrush.Hardcoded -> SymmetryLane.forHardcoded(
            copy = copy,
            frame = frame,
            tip = brush.tip,
            seed = seed,
            symmetric = symmetric,
            smoothing = smoothing,
            screenPerDoc = screenPerDoc,
        )
    }
}
