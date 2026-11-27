package cc.joycreator.joybrush.core.fill

import cc.joycreator.joybrush.core.brush.FillPen
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.select.MAX_SELECT_SPAN
import cc.joycreator.joybrush.core.select.SelectionMask
import cc.joycreator.joybrush.core.shape.Pt

/**
 * The fill pen's LIVE PREVIEW: what the person is being shown while the pen is still down.
 *
 * ## Why this exists
 *
 * The owner asked, verbatim, for the fill pen to be *"light drawing like a lasso selection except
 * it's just live updating. with a shape that closes itself from where you started your stroke. and
 * the idea is it can easily fill in shapes quickly."* As shipped, `engine: "fill"` has no branch in
 * `JbCanvasView.startStroke` at all, so it fell through to the stamp engine and drew a row of 8 px
 * beads — the OUTLINE of the shape, not the shape.
 *
 * Every piece this needs already exists and is tested, but in three places that do not know about
 * each other, and there is no live path anywhere in the tree:
 *
 *  - [FillPen.outline] turns the pen samples into the polygon, closing an open path with one straight
 *    chord (`JB-1.08a`);
 *  - [SelectionMask.polygon] rasterises it, non-zero winding, 4x4 antialiased (`JB-2.05a`);
 *  - [MaskPaint.apply] turns the mask into changed tiles, returning only what moved.
 *
 * So this object is deliberately **arithmetic-free**: it calls those three and adds the two things a
 * per-frame preview needs and neither of them offers.
 *
 * ## The two things this adds
 *
 * **1. [Shape.bounds] — the dirty rectangle.** A preview re-evaluates every frame while the pen
 * moves, and the cost of a preview is the cost of the tiles it touches. [SelectionMask.bounds] is
 * already public; exposing it here means a caller never re-derives a bounding box from the outline
 * and never accidentally walks the whole mask.
 *
 * **2. [Shape.closedPath] — the trap in [FillPen.outline].** `outline` returns the ring WITHOUT the
 * closing point, and that is deliberate (`FillPen.kt:27-29`): the closing segment is implicit and
 * `Lasso.rasterise` wraps the edge list modulo `n`, so materialising it would close the ring twice.
 * That is right for rasterising and **wrong for drawing** — an overlay that strokes `outline` draws
 * a shape with a visible gap on one side. A caller that wants to draw the preview must append
 * `outline[0]` itself, and must NOT append it before asking for a mask.
 *
 * [Shape.closedPath] is that one correct answer, named so the mistake is hard to make.
 *
 * ## No GL, no clock, no allocation beyond the mask
 *
 * `HoldToShape` and `FilmStrip.previewFrameAt` already establish this project's pattern for a live
 * preview: a pure `(samples) -> what the person is being shown right now`. This follows it, so the
 * same samples always give the same shape and a preview cannot drift from what a commit would paint.
 */
object FillPreview {

    /**
     * One evaluable shape. [outline] is empty exactly when there is nothing to show, and then
     * [mask] is [SelectionMask.EMPTY] and [bounds] is null — so `isDrawable` is the single honest
     * question and the other two answers cannot disagree with it.
     */
    class Shape internal constructor(
        /** The ring, without the closing point. Feed this to [SelectionMask.polygon], never [closedPath]. */
        val outline: List<Pt>,
        /** The rasterised region, non-zero winding. [SelectionMask.EMPTY] when there is nothing to fill. */
        val mask: SelectionMask,
        /** The dirty rectangle in document pixels, or null when there is nothing to fill. */
        val bounds: RectPx?,
    ) {
        /** True when there is a shape to show and a region to paint. */
        val isDrawable: Boolean get() = outline.isNotEmpty() && !mask.isEmpty

        /**
         * The outline WITH its closing point, for **drawing** a preview outline only.
         *
         * Never pass this to [SelectionMask.polygon]: the rasteriser closes the ring itself, so a
         * repeated first point closes it twice.
         */
        val closedPath: List<Pt>
            get() = if (outline.isEmpty()) outline else ArrayList<Pt>(outline.size + 1).also {
                it.addAll(outline); it.add(outline[0])
            }

        override fun toString(): String =
            "FillPreview.Shape(points=${outline.size} bounds=$bounds fillable=${mask.isEmpty.not()})"
    }

    /** Nothing to show: too few points, or a shape the lasso refuses. */
    val NOTHING = Shape(emptyList(), SelectionMask.EMPTY, null)

    /**
     * The shape for the pen samples so far. Total, pure, and safe to call every frame.
     *
     * [smoothing] and [screenPerDoc] are the values the stroke was recorded with — the same two
     * [FillPen.outline] takes — so a preview and a committed fill are the same polygon by
     * construction rather than by agreement between two code paths.
     */
    fun of(samples: List<PenSample>, smoothing: Float, screenPerDoc: Float): Shape {
        val outline = FillPen.outline(samples, smoothing, screenPerDoc)
        if (outline.isEmpty()) return NOTHING
        val mask = SelectionMask.polygon(outline)
        // `Lasso.rasterise` answers a refused shape with EMPTY rather than by throwing, so an
        // over-large lasso arrives here looking like a valid shape with no pixels. Distinguish it in
        // words instead of letting a caller paint nothing and wonder why.
        if (mask.isEmpty) return NOTHING
        return Shape(outline, mask, mask.bounds())
    }

    /**
     * True when a shape this big should be refused before anyone spends a mask on it.
     *
     * [MAX_SELECT_SPAN] is `internal`, so a caller in `androidkit` cannot ask whether a lasso is over
     * the limit and would otherwise discover it the expensive way — by watching [of] return
     * [NOTHING]. This is the public door, and it exists so the refusal can be SAID.
     */
    fun tooLargeForTheLasso(bounds: RectPx?): Boolean {
        if (bounds == null) return false
        return bounds.w > MAX_SELECT_SPAN || bounds.h > MAX_SELECT_SPAN
    }
}