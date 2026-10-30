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
    class File(val preset: BrushPreset, val colors: cc.joycreator.joybrush.core.brush.StrokeColors? = null,
        val sampled: cc.joycreator.joybrush.core.brush.SampledTipSet? = null) : LaneBrush()

    /** The app's hard-coded round brush. The lane builds its own placer. */
    class Hardcoded(val tip: HardcodedTip) : LaneBrush()
}

/**
 * The cap that applied to a symmetry stroke: the brush family, and the most COPIES it may make.
 *
 * The owner counts COPIES ("cap copies per brush family"), so the number here is the copy count
 * and NOT the lane count: a guide with one mirror has ONE copy and TWO lanes, the original and
 * the mirror.
 *
 * This is a VALUE: the core reports which cap applied and the view decides whether to say so out
 * loud. Nothing in the core remembers that it has already said it.
 */
data class SymmetryCap(val family: SymmetryFamily, val copies: Int)

/**
 * The lanes of one symmetry stroke: the ORIGINAL first, then the copies.
 *
 * @property lanes the lanes, in order. Lane 0 is the original stroke.
 * @property requestedCopies how many COPIES the guide asked for, before any cap.
 * @property family the brush family this stroke is drawn with.
 * @property copyLimit the most COPIES this brush family may make.
 */
class SymmetryLaneSet(
    val lanes: List<SymmetryLane>,
    val requestedCopies: Int,
    val family: SymmetryFamily,
    val copyLimit: Int,
) {
    /** The ORIGINAL lane: the one the copies are copies of. */
    val original: SymmetryLane get() = lanes[0]

    /** The COPIES this stroke makes: the lanes less the original. */
    val copies: Int get() = lanes.size - 1

    /** How many LANES the guide asked for: the copies and the original. */
    val requestedLanes: Int get() = requestedCopies + 1

    /** The most lanes this brush family may make: the copies it may make, and the original. */
    val laneLimit: Int get() = copyLimit + 1

    /** True when the guide asked for more copies than this brush may make. */
    val capped: Boolean get() = copies < requestedCopies

    /**
     * The cap that bit, or null when none did: the family, and the COPY count it allows.
     *
     * The number in here is the one the app says out loud ("this brush keeps symmetry to N
     * copies"), so it is the copy count and never the lane count.
     */
    val cap: SymmetryCap? get() = if (capped) SymmetryCap(family, copyLimit) else null
}

/**
 * Builds the lanes of a symmetry stroke, and says when one may not be built at all.
 *
 * ## The caps, and the words that go with them
 *
 * The owner's answer (2026-10-19): *"May I cap copies per brush family on the Note 9 (ink/pen up
 * to ~30, pencil ~16, watercolour ~8, oil ~4-8), and show a small note when the cap hits?"* — the
 * caps are counted in COPIES, so the table below is in copies and the lane limit is DERIVED from
 * it: lanes = copies + 1, the original always being there. A guide with one mirror has one copy
 * and two lanes, and the number the app says is the copy count.
 *
 * The ink and pen family — the stamp family — may make thirty copies. The media families are
 * capped per medium — dry sixteen, wet eight, paste eight — and those three numbers are
 * PROVISIONAL: the media phase is the one that measures them on the phone, and they live in this
 * one table so that phase has one place to fill in and cannot leave a family behind. The families
 * the owner did not name — the eraser, smudge and push dabs — are not capped below thirty copies:
 * they take the guard.
 *
 * The hard guard is SIXTY LANES — fifty-nine copies and the original — and no family may pass it.
 * It is ONE number, [HARD_LANE_LIMIT], and [HARD_COPY_LIMIT] is that same guard counted in
 * copies, so the two cannot drift apart.
 *
 * A cap that bites is NOT silent: the stroke goes on with the capped set, and the app says so
 * once, in words - [capSentence]. A person who asks for thirty copies and gets eight deserves to
 * be told. Saying it ONCE is the view's business: the core reports WHICH cap applied
 * ([SymmetryLaneSet.cap]) and the view remembers whether it has already said it.
 *
     * ## The families this phase does NOT copy
     *
     * The tuft engine lays footprints, the legacy watercolour engine keeps its own dab array and
     * the fill pen rasterises a shape: none of them is a stroke of dabs, and none of them can be
     * copied by running a brush again. They are refused in words by [refusalFor] rather than
     * half-drawn.
     *
     * The MEDIA families — the pencil, the watercolour and the oil — ARE copied (JB-2.12a's
     * Phase E): the media engine paints them through one window pinned on the symmetry centre, so
     * that every copy's wet paint lives in one water field. Their caps above are their caps, and
     * the three numbers are PROVISIONAL until the media phase has measured them on the phone.

 */
object SymmetryLanes {

    /**
     * The most lanes any family may make, whatever the family asks for: sixty, which is what a
     * kaleidoscope of thirty makes and the hard guard on everything else.
     *
     * Sixty lanes are FIFTY-NINE COPIES and the original. [HARD_COPY_LIMIT] is that same guard
     * counted in copies, and it is derived from this one number so that the two cannot drift.
     */
    const val HARD_LANE_LIMIT = 60

    /** The same hard guard in COPIES: sixty lanes less the original. */
    const val HARD_COPY_LIMIT = HARD_LANE_LIMIT - 1

    /**
     * The most COPIES each family may make — the owner's answer, in the owner's own unit.
     *
     * The ink and pen family — the stamp family — may make thirty copies. The media families are
     * capped per medium — dry sixteen, wet eight, paste eight — and those three are PROVISIONAL
     * until the media phase measures them on the phone (the media phase now COPIES them, so the
     * numbers are live: they are the copies the pencil, the watercolour and the oil may make).
     *
     * The families the owner did not name — the eraser, smudge and push dabs, and the three that
     * are refused in this phase — take the guard: they are not capped below thirty copies.
     */
    private val CAPS: Map<SymmetryFamily, Int> = mapOf(
        SymmetryFamily.STAMP to 30,
        SymmetryFamily.MEDIA_DRY to 16,
        SymmetryFamily.MEDIA_WET to 8,
        SymmetryFamily.MEDIA_PASTE to 8,
        // The ones below are not capped below thirty copies: they take the guard. The three that
        // are refused in this phase are in the table anyway: a family with no entry would be a
        // family a later change could forget.
        SymmetryFamily.ERASER to HARD_COPY_LIMIT,
        SymmetryFamily.SMUDGE to HARD_COPY_LIMIT,
        SymmetryFamily.PUSH to HARD_COPY_LIMIT,
        SymmetryFamily.TUFT to HARD_COPY_LIMIT,
        SymmetryFamily.WATERCOLOUR to HARD_COPY_LIMIT,
        SymmetryFamily.FILL to HARD_COPY_LIMIT,
    )

    /**
     * The most COPIES [family] may make: its own number, and never more than the hard guard.
     */
    fun copyCapFor(family: SymmetryFamily): Int =
        minOf(CAPS[family] ?: HARD_COPY_LIMIT, HARD_COPY_LIMIT)

    /**
     * The most LANES [family] may make: its copies, and the original.
     *
     * The one rule that turns a cap in copies into a limit in lanes: lanes = copies + 1. It is
     * here, in ONE place, so that the lane limit and the copy cap can never drift apart.
     */
    fun laneCapFor(family: SymmetryFamily): Int = copyCapFor(family) + 1

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
     *
     * The MEDIA families are NOT refused any more (JB-2.12a's Phase E): the pencil, the
     * watercolour and the oil are copied by the media engine's lanes, and the caps above are
     * their caps. The tuft engine lays footprints, the legacy watercolour engine keeps its own
     * dab array and the fill pen rasterises a shape: none of them is a stroke of dabs, and none
     * of them can be copied by running a brush again.
     */
    fun refusalFor(family: SymmetryFamily): String? = when (family) {
        SymmetryFamily.TUFT,
        SymmetryFamily.WATERCOLOUR,
        SymmetryFamily.FILL,
        -> "Symmetry is not available for this brush."
        else -> null
    }

    /**
     * The words for a cap that bit: how many COPIES this brush keeps on this phone.
     *
     * The number is the COPY count — the one the owner asked about — and never the lane count:
     * a cap of thirty copies is thirty in this sentence, not thirty-one.
     */
    fun capSentence(copies: Int): String = "This brush keeps symmetry to $copies copies on this phone."

    /**
     * The lanes of a stroke under [guide].
     *
     * The copies come from [SymmetryMath.copies] - the ORIGINAL is lane 0 and is not among them -
     * and the family may cut the list short. The cut is REPORTED: [SymmetryLaneSet.capped] and
     * [SymmetryLaneSet.cap] say so, and the app says [capSentence].
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
        val requested = copies.size
        val limit = copyCapFor(family)
        val kept = copies.take(limit)
        val lanes = ArrayList<SymmetryLane>(kept.size + 1)
        lanes.add(lane(null, AngleFrame.IDENTITY, brush, guide.symmetric, seedFor(guide, 0, seed), smoothing, screenPerDoc))
        for (i in kept.indices) {
            val copy = kept[i]
            lanes.add(lane(copy, frameFor(copy), brush, guide.symmetric, seedFor(guide, i + 1, seed), smoothing, screenPerDoc))
        }
        return SymmetryLaneSet(lanes, requested, family, limit)
    }

    /**
     * The lanes of a MEDIA symmetry stroke: the ORIGINAL first, then the copies.
     *
     * The copies and the cap are the stamp path's ([build]): the same guide, the same table, the
     * same words when the cap bites. What is different is what a lane IS: a media lane is the
     * copy's MAP and the seed (see [MediaLane]), because the media engine paints through water
     * and paste and cannot be run twice on the same sample. The engine makes the lane's own
     * spline and its own wet, dry or paste builder from them.
     *
     * Every lane shares ONE seed: the copies are the same brush (the owner).
     */
    fun buildMedia(guide: Guide.Symmetry, family: SymmetryFamily, seed: Long): MediaLaneSet {
        val copies = SymmetryMath.copies(guide)
        val requested = copies.size
        val limit = copyCapFor(family)
        val kept = copies.take(limit)
        val lanes = ArrayList<MediaLane>(kept.size + 1)
        lanes.add(MediaLane(null, seed, guide.symmetric))
        for (copy in kept) lanes.add(MediaLane(copy, seed, guide.symmetric))
        return MediaLaneSet(lanes, requested, family, limit)
    }

    /**
     * The cap that would apply to [guide] under [family], without building the lanes: the number
     * the app says out loud, and null when no cap bites.
     *
     * It is the same rule [build] and [buildMedia] use — the copies the guide asks for against
     * the family's cap — said here so that the view can say the note without building a stroke.
     */
    fun capFor(guide: Guide.Symmetry, family: SymmetryFamily): SymmetryCap? {
        val requested = SymmetryMath.copies(guide).size
        val limit = copyCapFor(family)
        return if (requested > limit) SymmetryCap(family, limit) else null
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
            colors = brush.colors,
            sampled = brush.sampled,
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
