package cc.joycreator.joybrush.core.symmetry

import cc.joycreator.joybrush.core.guide.SymmetryCopy
import cc.joycreator.joybrush.core.guide.SymmetryMath
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.media.MediaInput
import cc.joycreator.joybrush.core.media.MediaSpline
import cc.joycreator.joybrush.core.media.MediaZoneGate

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
 * ## The seed follows the guide's `symmetric`
 *
 * With the guide's `symmetric` on, every media lane draws with the SAME seed: the copies are the
 * same brush (the owner), and the wet stroke draws its water, its bead and its grain from that
 * seed. With it off, every lane has its own seed, exactly as the stamp lanes do, so each copy's
 * texture and jitter are its own ([SymmetryLanes.buildMedia]).
 */
class MediaLane(
    /** The copy's own map, or null for the ORIGINAL lane (lane 0). */
    val copy: SymmetryCopy?,
    /** The seed this lane's brush draws with: shared under a symmetric guide, its own otherwise. */
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
 * Feeds one media stroke's pen samples to its lanes under the safe zone.
 *
 * This is the ONE place a sample meets the lanes, and it is deliberately narrow:
 *
 * - the sample is transformed into EVERY lane's own frame first;
 * - when any lane's copy is outside the [MediaZoneGate], the sample is DROPPED with all its
 *   copies BEFORE any lane's [MediaSpline] sees it, and each spline's current run is finished at
 *   the last inside point, so the curve never bridges the excluded gap with a half-built segment;
 * - [MediaZoneGate.refusal] says the sentence ONCE per stroke;
 * - nothing latches: the next sample inside the zone paints again.
 */
class MediaLaneFeed(
    /** The lanes, the ORIGINAL first, as [SymmetryLanes.buildMedia] made them. */
    val lanes: List<MediaLane>,
    /** Each lane's own spline, in the same order as [lanes]. */
    private val splines: List<MediaSpline>,
    /** The safe zone, or null when the stroke is not pinned on a symmetry centre. */
    private val zone: MediaZoneGate?,
    /** The pen's own tilt reach, as the media input reads it. */
    private val reachDeg: Double,
) {
    init {
        require(lanes.size == splines.size) {
            "a media lane and its spline go together: ${lanes.size} lanes, ${splines.size} splines"
        }
    }

    /**
     * Feeds [s] to every lane's spline, or DROPS it (with its copies) when it is outside the zone.
     * True when the sample was painted; false when it was dropped.
     */
    fun add(s: PenSample): Boolean {
        val moved = lanes.map { it.transform(s) }
        if (zone != null && moved.any { !zone.admits(it.x.toDouble(), it.y.toDouble()) }) {
            // The run ends at the last INSIDE point: the dropped sample never reaches MediaSpline,
            // so it cannot leave a pending point that the next inside sample would curve through.
            for (spline in splines) spline.finish()
            return false
        }
        for (i in splines.indices) splines[i].add(MediaInput.sample(moved[i], reachDeg))
        return true
    }

    /** The one sentence for a dropped sample: null when none was dropped, or it was already said. */
    fun dropSentence(): String? = zone?.refusal()
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
