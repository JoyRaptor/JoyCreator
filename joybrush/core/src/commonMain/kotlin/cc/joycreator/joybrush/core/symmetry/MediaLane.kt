package cc.joycreator.joybrush.core.symmetry

import cc.joycreator.joybrush.core.guide.SymmetryCopy
import cc.joycreator.joybrush.core.guide.SymmetryMath
import cc.joycreator.joybrush.core.input.PenSample

/**
 * ONE lane of a MEDIA symmetry stroke: the copy's own map, and the seed its brush draws with.
 *
 * A media lane is NOT a brush (the stamp lanes are, in [SymmetryLane]): the media engine paints
 * through water and paste, and it cannot be run twice on the same sample. So a media lane is the
 * MAP — the copy's own transform — and the seed: the media engine makes the lane's own spline and
 * its own wet, dry or paste builder, and every lane shares the ONE engine, the ONE window and the
 * ONE undo step.
 *
 * ## The transform is the stamp path's transform
 *
 * The same [SymmetryMath.apply] the stamp lanes use: the position, and the lean and the twist when
 * the guide is symmetric. It is applied to the PEN SAMPLE, before the media input reads the azimuth
 * ([cc.joycreator.joybrush.core.media.MediaInput.sample]), so the copy's lean is the copy's own and
 * not the original's turned twice.
 *
 * ## The seed is SHARED
 *
 * Every media lane draws with the SAME seed: the copies are the same brush (the owner), and the
 * media engine's wet stroke draws its water, its bead and its grain from that seed. This is the
 * one place where a media lane differs from a stamp lane, whose seed is the lane's own when the
 * guide is not symmetric.
 */
class MediaLane(
    /** The copy's own map, or null for the ORIGINAL lane (lane 0). */
    val copy: SymmetryCopy?,
    /** The seed this lane's brush draws with: the same seed for every lane of the stroke. */
    val seed: Long,
    /** True when the guide is symmetric: the lean and the twist are copied too. */
    private val symmetric: Boolean,
) {
    /** True when this lane is the ORIGINAL stroke: the one the copies are copies of. */
    val isOriginal: Boolean get() = copy == null

    /** The pen's sample as THIS lane hears it: the copy's own position, lean and twist. */
    fun transform(s: PenSample): PenSample =
        if (copy == null) s else SymmetryMath.apply(s, copy, symmetric)
}

/**
 * The lanes of one MEDIA symmetry stroke: the ORIGINAL first, then the copies.
 *
 * @property lanes the lanes, in order. Lane 0 is the original stroke.
 * @property requestedCopies how many COPIES the guide asked for, before any cap.
 * @property family the brush family this stroke is drawn with.
 * @property copyLimit the most COPIES this brush family may make.
 */
class MediaLaneSet(
    val lanes: List<MediaLane>,
    val requestedCopies: Int,
    val family: SymmetryFamily,
    val copyLimit: Int,
) {
    /** The ORIGINAL lane: the one the copies are copies of. */
    val original: MediaLane get() = lanes[0]

    /** The COPIES this stroke makes: the lanes less the original. */
    val copies: Int get() = lanes.size - 1

    /** How many LANES the guide asked for: the copies and the original. */
    val requestedLanes: Int get() = requestedCopies + 1

    /** True when the guide asked for more copies than this brush may make. */
    val capped: Boolean get() = copies < requestedCopies

    /** The cap that bit, or null when none did. */
    val cap: SymmetryCap? get() = if (capped) SymmetryCap(family, copyLimit) else null
}
