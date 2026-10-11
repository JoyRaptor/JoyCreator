package cc.joycreator.joybrush.androidkit.tools

import android.view.MotionEvent
import cc.joycreator.joybrush.core.view.ViewTransform

/**
 * A canvas tool that is not a brush: Select, lasso, nudge, transform (JB-5.20e, the UI lane). While one is set on the
 * canvas ([cc.joycreator.joybrush.androidkit.JbCanvasView.tool]), a contact that would have started a stroke (a pen
 * anywhere; a finger before any pen has been seen, or a finger in a mode the tool asks for) goes to the tool instead.
 *
 * The contract, so the canvas and the tool never fight over a gesture:
 * - [down] is offered the contact first. Return true to own it: every later event of that gesture (moves, more pointers,
 *   up, cancel) then comes to [event] until the last pointer lifts or the tool returns false from [event], which hands
 *   the rest of the gesture back to navigation. Return false and the canvas does what it would have done anyway.
 * - Two fingers that land when no tool gesture is running stay navigation (pan, zoom, rotate), as with a brush.
 * - The tool reads document coordinates through [view] ([ViewTransform.screenToDoc]) and never touches GL itself; edits
 *   go through the canvas's public calls, so undo stays one history (one press, one step).
 * - Main thread only.
 */
interface CanvasTool {
    /** Offered a new contact at [pointerIndex] of [ev] (ACTION_DOWN or ACTION_POINTER_DOWN). True = the tool owns it. */
    fun down(ev: MotionEvent, pointerIndex: Int, view: ViewTransform): Boolean

    /** Every later event of a gesture the tool owns. False hands the rest of the gesture back to navigation. */
    fun event(ev: MotionEvent, view: ViewTransform): Boolean

    /** The gesture was taken away (a system cancel, the tool being swapped, the screen closing). */
    fun cancel() {}

    /** Pen hover over the canvas while the tool is set (for a hover highlight, say). */
    fun hover(ev: MotionEvent, view: ViewTransform) {}
}
