package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.AnimOps
import cc.joycreator.joybrush.core.doc.AnimResult
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.NewFrame
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The film strip's geometry, its gesture rules and its five document operations, as pure maths.
 * No Android, no view, no clock, no document mutation: every operation that changes the document
 * returns a NEW document, exactly like `AnimOps` does.
 *
 * THE STRIP'S X AXIS IS TICKS, NOT MILLISECONDS. A cell is `holdFrames × tickPx` wide and that is
 * the whole geometry; no `Double` in milliseconds appears in this file, on purpose — see the
 * spec's seam section. A caller with an elapsed time converts it to an index first, through
 * `FrameStepper.frameOnScreen`, and never multiplies a duration by a pixel rate.
 *
 * The strip's state that ISN'T the document is a PLAYHEAD, which is a frame ID and never an index:
 * `playheadAfterDelete` re-anchors it by ID, and every gesture that can remove a frame says which
 * frame the playhead is on. See Decision 1.
 *
 * WHAT THIS CLASS DELIBERATELY DOES NOT DO, all of it on purpose and all of it checkable:
 *
 *  - it holds **no copy of the model's hold clamp.** `AnimOps.MAX_HOLD_FRAMES` and
 *    `MIN_HOLD_FRAMES` are private in `AnimOps.kt` (lines 100 and 117), so every clamp below is
 *    `AnimOps.setHold` answering and this class reading `holdFrames` back out of the document that
 *    comes back. A local copy could drift from the model's and nothing would catch it — the trap
 *    R19 already fixed for `SizeOpacityDrag.MAX_SIZE_PX`. See Decision 11.
 *  - it never reads a clock. `PlaybackClock.nextChangeMs` hands back the boundary itself as an
 *    *infimum* at a backward boundary of a PING_PONG leg, so a caller that sleeps until it and then
 *    re-asks without comparing frame indices spins forever. That is correct behaviour of the clock
 *    and a bug in the caller, and the strip is the caller most likely to have one. J1 is the
 *    mechanical form of this sentence.
 *  - it does not draw, scroll, decide a play/pause, or know what a density READ is. `density` is a
 *    number the caller was given (R32); the view half, the thumbnails (JB-3.03b) and the sprockets
 *    are not this row.
 *
 * IT IS BUILT FROM A `Board` IT WAS GIVEN AND NEVER RE-READS ANYTHING, which is the reading rule
 * the whole file obeys: an operation that changes a hold returns a NEW document, and the geometry
 * of that hold is a property of a strip built on THAT document. Asking the old strip for a new
 * width returns the old width, and it looks right.
 *
 * An ANIMATION board with no frames is already a document `DocOps.validate` rule 4 refuses, so this
 * class does not add a second refusal in a paint loop: every answer below is TOTAL over an empty
 * board (nothing to draw, nothing to hit, a playhead that cannot move).
 */
class FilmStrip(val board: Board, val density: Float = 1f) {

    /** Screen px per tick of hold. 44 dp × density (R32). */
    val tickPx: Float get() = TICK_PX_DP * density

    /** Screen px a finger-down may be from a frame's right edge and still grab it. 24 dp × density. */
    val edgeGrabPx: Float get() = EDGE_GRAB_PX_DP * density

    /**
     * Cell [frameIndex]'s left edge in STRIP coordinates, strip px from the strip's own left end.
     * Cell 0's left edge is 0. The sum of the widths before it — ticks, never ms.
     *
     * Counted in WHOLE TICKS and multiplied once, which is the same number as summing the widths and
     * is the better way to get it: a long board of 44 px cells would accumulate a `Float` error
     * that a drag then has to be measured against, and the error would be invisible until a frame
     * at the far end of a 999-tick strip landed on the wrong side of its own boundary.
     *
     * Total over the whole `Int` range on purpose: an index before the first cell is the strip's
     * left end (0) and an index past the last is the strip's right end ([stripWidth]), which is
     * what makes `stripWidth()` the identity it is written as.
     */
    fun cellLeft(frameIndex: Int): Float {
        val frames = board.frames
        var ticks = 0
        for (i in 0 until frameIndex.coerceIn(0, frames.size)) ticks += frames[i].holdFrames
        return ticks * tickPx
    }

    /**
     * Cell width: `holdFrames × tickPx`, which is never less than one tick.
     *
     * "Never less than one tick" is the MODEL's promise, not a floor invented here: a hold below 1
     * is a document `DocOps.validate` rule 4 already calls broken, and a
     * second floor here would be a copy of `AnimOps.MIN_HOLD_FRAMES` in a file that must not hold
     * one (Decision 11). A hand-edited zero draws as nothing and is fixed in the document, not
     * hidden by the strip.
     *
     * An index this board does not have is 0 px wide: there is no cell there to measure.
     */
    fun cellWidth(frameIndex: Int): Float {
        val frames = board.frames
        if (frameIndex !in frames.indices) return 0f
        return frames[frameIndex].holdFrames * tickPx
    }

    /** Total strip width: the last cell's right edge, i.e. the sum of every cell's width. */
    fun stripWidth(): Float = cellLeft(board.frames.size)

    /**
     * Which frame's cell contains strip coordinate [xPx]. HALF-OPEN: `x` in
     * `[cellLeft(i), cellLeft(i) + cellWidth(i))` is frame i, so the exact pixel on a boundary
     * belongs to the frame that STARTS there. Before the first cell → 0; past the last cell's
     * right edge → the last frame. A non-finite x → the last frame.
     *
     * The walk is the same one `AnimOps.frameAt` and `PlaybackClock.slotAt` make — "the last start
     * at or before x", breaking at the first start past it — because three implementations of
     * "which frame is showing" that disagree by one frame at a boundary is a failure this project
     * has paid for twice (Decision 6).
     *
     * [Float.NaN] is answered with the last frame and needs saying: it is the one x the walk
     * cannot answer, because every `cellLeft(i) <= NaN` is false and the loop would otherwise
     * report frame 0 for a pointer that has not reported a position. An x of `-Inf` is left to the
     * walk, which answers frame 0 — it is before the strip, and that is the honest answer for a
     * position that far off. `+Inf` is left to it too, and answers the last frame.
     */
    fun frameAt(xPx: Float): Int {
        val frames = board.frames
        if (frames.isEmpty()) return 0
        if (xPx.isNaN()) return frames.size - 1
        var chosen = 0
        for (i in frames.indices) {
            if (cellLeft(i) <= xPx) chosen = i else break
        }
        return chosen
    }

    /**
     * Which frame's RIGHT EDGE a finger-down at [xPx] has hold of, or -1.
     *
     * The grab zone is `[edgeLeft - edgeGrabPx, edgeLeft]` — to the LEFT of the edge only, and
     * INCLUDING the edge itself. Frame 0's right edge is a real edge; the strip's own left end
     * (x = 0) is not, because there is no frame before it to lengthen. There is no "left edge"
     * zone at all, so the zones of two frames can never overlap and no tie-break exists.
     *
     * The one-sided shape is the reason there is no tie to break: two sides would put frame *i*'s
     * left-edge zone on top of frame *i−1*'s right-edge zone at every interior boundary, and the
     * winner would have to be decided by an arbitrary convention (Decision 5). The depth, 24 dp, is
     * the Lead's number and not R32's 12: 24 dp is the only depth under which all four of the
     * cross-review's pinned values are simultaneously true — Q1 in the spec, and the arithmetic is
     * in the test.
     *
     * The loop below returns the FIRST zone that contains x, which is only safe because the zones
     * are provably disjoint, and they are: a cell is at least one tick wide and
     * `EDGE_GRAB_PX_DP < TICK_PX_DP`, so consecutive right edges are more than one grab-zone depth
     * apart. Both constants are the companion's only two fields and J2 pins them, so raising the
     * zone to 88 dp — which would make every interior boundary ambiguous — is a test failure and
     * not a mystery.
     *
     * A non-finite x is in no zone at all, which is why [Float.NaN], `-Inf` and `+Inf` all answer
     * -1: the answer is the range test's, not a special case (Decision 7).
     */
    fun edgeAt(xPx: Float): Int {
        if (!xPx.isFinite()) return -1
        val grab = edgeGrabPx
        for (i in board.frames.indices) {
            val edge = cellLeft(i) + cellWidth(i)
            if (xPx >= edge - grab && xPx <= edge) return i
        }
        return -1
    }

    /**
     * The playhead after the frame [deletedFrameId] is deleted, given the board that results.
     * Anchored by ID: the frame that now sits where the deleted one was, or the new last frame if
     * the deleted one was the last. See Decision 1.
     *
     * The OLD index comes from the board THIS strip was built on and the NEW frame from
     * [boardAfter], which is the whole point of taking two boards: the caller has the document the
     * delete returned, and this strip has not been rebuilt yet.
     *
     * A playhead on any other frame is returned exactly as it was given, by ID and whatever the
     * indices did — and that is the case the decision is about, because an index playhead lands one
     * frame past the end of a board whenever the frame before it is deleted, which is the exact
     * off-by-one this project keeps shipping.
     *
     * Total on the awkward inputs rather than a new refusal: a null playhead has nothing to
     * re-anchor, and an id this board does not have is not a frame of it, so the frame list's
     * answer (the new last frame, or null if there are none) is returned.
     */
    fun playheadAfterDelete(boardAfter: Board, deletedFrameId: String, playheadFrameId: String?): String? {
        if (playheadFrameId == null) return null
        // Any other frame's playhead is left exactly as it is. The whole decision is these two lines:
        // a playhead is a frame, and the indices have just been renumbered under it.
        if (playheadFrameId != deletedFrameId) return playheadFrameId
        val was = board.frames.indexOfFirst { it.id == deletedFrameId }
        if (was < 0) return playheadFrameId // nothing was deleted from THIS board; the id stands
        val now = boardAfter.frames
        if (now.isEmpty()) return null
        return now.getOrNull(was)?.id ?: now.last().id
    }

    /**
     * Whether delete is available. `false` exactly when [AnimOps.deleteFrame] will refuse, which is
     * a board with one frame — so a view can disable the control instead of offering something the
     * model will throw at.
     */
    fun canDelete(): Boolean = board.frames.size > 1

    // ---- the five document operations. Each returns a NEW document; none of them mutates. ----

    /**
     * The `+` TAP, which is **DUPLICATE** (R33: what animators do most; FlipaClip is the named
     * reference). A new cel holding a copy of the pixels: one `CelWork.CopyCel` per animated layer.
     * The long-press menu's BLANK and LINK are the same call with the other two `NewFrame` values.
     *
     * Verbatim [AnimOps.addFrame] and nothing else: the landed signature takes a `JbDocument` and a
     * `boardId` (the old spec's `addBlank(board: Board, …)` would have needed a `Board` → document
     * bridge that does not exist in this row), and the strip is not the place to re-derive an id
     * policy the model already owns. If the owner finds DUPLICATE surprising, it is ONE call site
     * and one constant.
     */
    fun addAfter(
        doc: JbDocument,
        boardId: String,
        afterFrameId: String?,
        mode: NewFrame,
        ids: () -> String,
    ): AnimResult = AnimOps.addFrame(doc, boardId, afterFrameId, mode, ids)

    /** The `−`: `AnimOps.deleteFrame` verbatim, including its refusal in words on a one-frame board. */
    fun delete(doc: JbDocument, boardId: String, frameId: String): AnimResult =
        AnimOps.deleteFrame(doc, boardId, frameId)

    /**
     * The edge drag, COMMITTED: [dxPx] is the TOTAL screen offset since the finger went down,
     * positive right. The step is `round(dxPx / tickPx)` with ties away from zero and the new hold is
     * `holdAtDown + step`; the clamp is `AnimOps.setHold`'s and this class reads `holdFrames` back out
     * of the document that comes back. **This class holds no copy of 1 and no copy of 999.**
     *
     * `dxPx` is measured from the finger-DOWN, not from the last move, and that is the decision
     * (Decision 10): the deltas telescope to the offset from the down, so measuring from the down
     * is also the only one of the three candidate answers that is not a function of how many MOVE
     * events the platform happened to deliver.
     */
    fun setHoldByDrag(
        doc: JbDocument,
        boardId: String,
        frameId: String,
        holdAtDown: Int,
        dxPx: Float,
    ): JbDocument = withHoldStep(doc, boardId, frameId, holdAtDown, dragStep(tickPx, dxPx))

    /**
     * The long-press menu's HOLD ±1 on one entry: `setHold(hold + delta)`, read back the same way.
     * One code path with [setHoldByDrag] (Decision 13).
     */
    fun setHoldByBump(
        doc: JbDocument,
        boardId: String,
        frameId: String,
        holdAtDown: Int,
        delta: Int,
    ): JbDocument = withHoldStep(doc, boardId, frameId, holdAtDown, delta)

    /**
     * The ONE place a hold is written, and the whole of Decision 11: the requested number is handed
     * to [AnimOps.setHold] and the document THAT RETURNS is what this class gives back, so the drawn
     * width is always the width the model kept — including when the model clamped.
     *
     * The sum is done in `Long` and clamped into `Int` on the way in, because a drag of a hundred
     * thousand px against a 44 px tick is 2273 ticks and a caller with a degenerate tick (a zero
     * density) can ask for anything at all; `holdAtDown + step` on two `Int`s that are each close
     * to the ceiling wraps negative, and a negative hold is silently clamped up to 1 — so an
     * enormous right drag would have quietly set every frame to the shortest possible hold. The same
     * long arithmetic, for the same reason, is in `SpriteGridMath.grownCell`.
     */
    private fun withHoldStep(
        doc: JbDocument,
        boardId: String,
        frameId: String,
        holdAtDown: Int,
        step: Int,
    ): JbDocument {
        val asked = (holdAtDown.toLong() + step.toLong())
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
        return AnimOps.setHold(doc, boardId, frameId, asked)
    }

    /** The hold of [frameId] in [doc], which is how "read it back" is spelled. */
    fun holdOf(doc: JbDocument, boardId: String, frameId: String): Int {
        val frames = boardOf(doc, boardId).frames
        val frame = frames.firstOrNull { it.id == frameId }
            ?: throw DocException("board \"$boardId\" has no frame \"$frameId\"")
        return frame.holdFrames
    }

    /**
     * The playhead one step along, for the strip's own prev/next. Clamped at the ends and **never
     * wrapped** — wrapping is playback's business and the strip is not playback (Decision 12).
     *
     * Three inputs this answers without inventing a frame: a null playhead stepping forward lands on
     * the FIRST frame (there is a next frame and nothing was chosen yet) and a null stepping back
     * stays null (nothing is before the first frame); an id this board does not have is returned
     * exactly as it was given, because the strip cannot know which board the caller meant and a
     * delete that took the frame away is [playheadAfterDelete]'s job, not this method's.
     *
     * Long arithmetic and a clamp, for the same overflow reason as [withHoldStep]: `delta` is a
     * signed `Int` from a stepper button and a wrapped index would be a silent wrong frame.
     */
    fun stepPlayhead(frameId: String?, delta: Int): String? {
        val frames = board.frames
        if (frames.isEmpty()) return frameId
        if (frameId == null) return if (delta > 0) frames.first().id else null
        val from = frames.indexOfFirst { it.id == frameId }
        if (from < 0) return frameId
        val to = (from.toLong() + delta.toLong()).coerceIn(0L, frames.size - 1L).toInt()
        return frames[to].id
    }

    companion object {
        /** Screen px per tick, in DP. Density is applied at the use site (R32). */
        const val TICK_PX_DP: Float = 44f

        /**
         * How deep the edge grab zone is, in DP, to the LEFT of a frame's right edge. Density is
         * applied at the use site (R32). 24, not 12: the zone is ONE-SIDED (Decision 6), so 24 dp of
         * depth is what the old "12 dp either side of the edge" constant became — see Decision 5.
         *
         * **It must stay smaller than [TICK_PX_DP]**, and that is a structural rule rather than a
         * taste: a cell is at least one tick wide, so a zone shallower than a cell is what makes two
         * adjacent zones disjoint. 24 < 44. A zone as deep as a cell, or deeper, would put frame
         * *i*'s zone and frame *i+1*'s zone on top of each other at every interior boundary and
         * [edgeAt]'s first-match loop would be answering out of order.
         */
        const val EDGE_GRAB_PX_DP: Float = 24f
    }
}

/**
 * Ticks of hold for a drag of [dxPx] screen px at [tickPx] px per tick, ties AWAY FROM ZERO.
 *
 * `floor(v + 0.5f)` above zero and `ceil(v - 0.5f)` below it, spelled out here rather than called:
 * `SpriteGridMath.roundedPx` is the same two lines and the same ruling, and it is `private`, so this
 * is a precedent and not something to call (Decision 12). Deliberately **not** `kotlin.math.round`,
 * which is ties-to-EVEN: one rounding idiom in the project, not two, and a person dragging under a
 * finger expects the value it looks nearest. Half a tick is 22 px at density 1, so the tie is a real
 * one and not a hypothetical.
 *
 * A `NaN` offset and a `+Inf` one both answer 0, and so does a strip whose tick is not a number
 * (a zero or negative density): a drag measured in nothing is a drag that has not reported a
 * position, and the cell under the finger should stay the length the model gave it.
 */
private fun dragStep(tickPx: Float, dxPx: Float): Int {
    if (dxPx.isNaN() || !tickPx.isFinite() || tickPx <= 0f) return 0
    val ticks = dxPx / tickPx
    if (!ticks.isFinite()) return 0
    val rounded = if (ticks >= 0f) floor(ticks + 0.5f) else ceil(ticks - 0.5f)
    return rounded.toInt() // saturating: a Float outside Int's range clamps, it does not wrap
}

/**
 * [doc]'s board called [boardId], refused in `AnimOps`'s own words.
 *
 * A private copy of `AnimOps.board`, in the same wording, because that one is private and the
 * strip needs the same answer twice (a hold read back, a gesture's own strip) — one rule, written
 * once, and the message a person sees is the message `AnimOps` would have given them.
 */
private fun boardOf(doc: JbDocument, boardId: String): Board =
    doc.boards.firstOrNull { it.id == boardId }
        ?: throw DocException("this document has no board \"$boardId\"")

/** What a finger-down has hold of, decided ONCE. See Decision 7. */
sealed class Grab {
    /** An edge: the frame whose right edge is being dragged, and its hold at finger-down. */
    data class Edge(val frameIndex: Int, val holdAtDown: Int) : Grab()

    /** Not an edge — the finger is scrubbing to whichever frame is under it. */
    object Scrub : Grab()
}

/** What a lift, or a second finger, produces. Nothing here is a view type. */
sealed class StripStep {
    /** The playhead moves. Session state, not a document: no document is produced. */
    data class PlayheadTo(val frameId: String) : StripStep()

    /**
     * The hold changed. [doc] is a NEW document; the one the gesture was given is untouched and is
     * still what the undo stack holds. There is exactly ONE of these per gesture.
     */
    data class HoldChanged(val doc: JbDocument, val frameId: String, val holdFrames: Int) : StripStep()

    /** A second finger, or a lift that changed nothing. The document is not touched at all. */
    object Nothing : StripStep()
}

/**
 * One finger's gesture on the strip. Pure: it holds the document it was GIVEN and the x it went down
 * at, and every answer is a function of those. It never mutates, never asks a clock and never
 * decides anything twice.
 *
 * It is here, and not in the view, because three of the strip's rules are only checkable if
 * something other than a `View` owns the gesture: what a finger-down has hold of is decided ONCE
 * (Decision 8), a second finger cancels the whole thing (Decision 9), and a document is written
 * ONCE, on the lift (Decision 10). A `View` would have all three and none of them testable.
 *
 * The three pieces of state below are the gesture, not the model: what was grabbed, where the finger
 * went down, and the frame the scrub last published. Nothing here is a copy of anything the
 * document says — [doc] is the caller's document, held, never edited, and every write goes through
 * [FilmStrip] to a NEW document.
 */
class FilmStripGesture(
    val doc: JbDocument,
    val boardId: String,
    val density: Float = 1f,
) {

    /** The strip this gesture hit-tests against: the board of the document it was given, once. */
    private val strip = FilmStrip(boardOf(doc, boardId), density)

    private var grab: Grab? = null

    /** Strip x of the finger-down. Only ever read for an [Grab.Edge], and an edge needs a finite x. */
    private var downX = 0f

    /** The frame a scrub last published — the answer a non-finite coordinate repeats (Decision 7). */
    private var lastPublished = 0

    /** Set by [cancel] and by the first [up]; a second [up] is a no-op, not a second write. */
    private var finished = false

    /**
     * A finger-down at strip coordinate [xPx]. Decides edge-grab vs scrub ONCE, for the whole gesture.
     *
     * The hold is read from the document HERE and not at the lift, so a gesture that is abandoned —
     * a second finger, a scroll, the view being detached — has nothing left to commit and no way to
     * commit something the person changed underneath it.
     *
     * A [down] after a [cancel] or a previous [up] starts a NEW gesture rather than being refused:
     * a view that reuses one object for every touch on the strip is the normal way to write it, and
     * a refusal here would push the state machine into the view, which is the thing this class
     * exists to keep out of it.
     */
    fun down(xPx: Float): Grab {
        val edge = strip.edgeAt(xPx)
        val taken = if (edge < 0) {
            Grab.Scrub
        } else {
            Grab.Edge(edge, strip.holdOf(doc, boardId, strip.board.frames[edge].id))
        }
        grab = taken
        downX = xPx
        lastPublished = strip.frameAt(xPx)
        finished = false
        return taken
    }

    /**
     * The frame a move to [xPx] would playhead to, for a SCRUB. A non-finite [xPx] returns the frame
     * last published, so a pointer that has not reported a position moves nothing (Decision 6).
     *
     * NOT [FilmStrip.frameAt]'s answer for a non-finite x, and that difference is the whole test:
     * the lookup would report the LAST frame for a `NaN`, which is a confident, wrong picture drawn
     * from a coordinate that is not one. The finite case is the same function, so a real position
     * really does move the preview.
     *
     * Answered after [cancel] as well as before it: a cancelled gesture has stopped PLAYING, not
     * stopped understanding where the finger is, and a view that keeps drawing a preview during the
     * tail of a touch should be told the truth.
     */
    fun previewFrameAt(xPx: Float): Int {
        if (!xPx.isFinite()) return lastPublished
        val frame = strip.frameAt(xPx)
        lastPublished = frame
        return frame
    }

    /**
     * The hold a lift at [xPx] would commit, for an EDGE GRAB, WITHOUT committing it: the live
     * preview the cell width is drawn from. The document is not touched.
     *
     * The number is the raw request and is NOT clamped, because a clamp here would mean asking the
     * model for a document in order to throw it away — and the visible clamp the house wants is the
     * model clamping on the lift, after which the drawn width comes from the document that came
     * back (Decision 13). A preview beyond the limit is therefore a cell drawn a little too wide for
     * as long as the finger is still down, and exactly the right width the moment it lifts.
     *
     * A gesture that is not an edge drag has no edge to preview, so the answer is the model's own
     * hold of the frame under the finger: a real number rather than an exception in a paint loop.
     * With no frame there, the answer is 0 px, which means "no cell here" and is NOT a hold of 0.
     */
    fun previewHoldAt(xPx: Float): Int {
        val taken = grab
        if (taken !is Grab.Edge) {
            val id = strip.board.frames.getOrNull(lastPublished)?.id ?: return 0
            return strip.holdOf(doc, boardId, id)
        }
        return taken.holdAtDown + dragStep(strip.tickPx, xPx - downX)
    }

    /**
     * The lift. The ONE place a gesture writes, and only for an edge grab.
     *
     * A scrub publishes a playhead — session state, no document. An edge drag asks [FilmStrip] for
     * the hold and hands back the document that CAME BACK, with the hold read out of it, so the
     * number reported is the model's and not the one that was asked for. The document the gesture
     * was given is untouched and is still what the undo stack holds.
     *
     * One write per gesture, enforced rather than promised: a second [up] is
     * [StripStep.Nothing].
     */
    fun up(xPx: Float): StripStep {
        val taken = grab
        if (finished || taken == null) return StripStep.Nothing
        finished = true
        return when (taken) {
            is Grab.Edge -> {
                val id = strip.board.frames[taken.frameIndex].id
                val after = strip.setHoldByDrag(doc, boardId, id, taken.holdAtDown, xPx - downX)
                StripStep.HoldChanged(after, id, strip.holdOf(after, boardId, id))
            }
            Grab.Scrub -> {
                val id = strip.board.frames.getOrNull(previewFrameAt(xPx))?.id
                    ?: return StripStep.Nothing
                StripStep.PlayheadTo(id)
            }
        }
    }

    /** A second finger. [StripStep.Nothing], always — the gesture is over and nothing was written. */
    fun cancel(): StripStep {
        finished = true
        return StripStep.Nothing
    }
}
