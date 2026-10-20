package cc.joycreator.joybrush.androidkit

import cc.joycreator.joybrush.core.symmetry.SymmetryCap
import cc.joycreator.joybrush.core.symmetry.SymmetryLanes

/**
 * The memory of the symmetry's cap note: "said it once".
 *
 * The owner asked for *"a small note when the cap hits"* — ONE note. A person painting ten
 * strokes with the same brush must hear it once and not ten times.
 *
 * The note is said once per (brush family, cap): a second stroke with the same brush and the
 * same cap does not say it again, and another family — or another cap — says it again. The cap
 * and the sentence are per family, so a second brush of the SAME family keeps the same note: what
 * changed there is the brush, and the number in the sentence would be the same either way.
 *
 * The state lives HERE, on the view's side of the line, and not in the core: the core's lane code
 * is a pure function of its inputs, and all it does is report WHICH cap applied. This decides
 * whether to speak it, and [JbCanvasView] owns one of these and asks it on every symmetry stroke.
 */
class SymmetryCapNote {

    /** The cap whose note has been said, or null when none has been said yet. */
    private var said: SymmetryCap? = null

    /**
     * The sentence to say for [cap], or null when there is nothing to say: no cap bit, or this
     * cap has already been said.
     *
     * The number in the sentence is the COPY count — the cap's, which is the number the owner
     * asked about — and never the lane count.
     */
    fun sentenceFor(cap: SymmetryCap?): String? {
        if (cap == null || cap == said) return null
        said = cap
        return SymmetryLanes.capSentence(cap.copies)
    }
}
