package cc.joycreator.joybrush.core.select

import kotlin.math.hypot

/**
 * JB-5.20e — who a contact belongs to while selecting, and what it means (spec D3–D8, D12, D13, §K K11–K19). Pure: no
 * view, no MotionEvent, an injected clock. The Android `SelectTool` turns MotionEvents into these calls and the [Sink]
 * into edits; the Studio box (`TransformOverlayView`) owns its handles and body through [boxTakes].
 *
 * Coordinates are SCREEN px; [Env.density] turns the spec's dp into them.
 *
 * The rules, in the owner's words where he gave them:
 * - **The pen keeps its tool; the finger and the S Pen button handle the selection** (D12). With a brush in hand the pen
 *   paints (inside a paint selection it is clipped, H4); a pen stroke ends a LINE selection, as in Concepts.
 * - **One finger after the pen has been seen pans by default** (K18, "p10 … default b"); the Select tool, or the
 *   "Selects" setting, makes it select. Before any pen, one finger draws with a brush as it always has.
 * - **A tap picks; a drag draws a loop; a finger that holds still lifts a crosshair** (D3, D7, D8; P4 B).
 * - **＋ (or a resting thumb while the pen works) adds** for every way of picking (K12).
 * - **Tap outside ends the selection** (D6); what "outside" is, the [Sink] decides from the box.
 */
class SelectRouter(private val env: Env, private val sink: Sink, private val clock: () -> Long) {

    enum class Kind { PEN, FINGER }

    enum class Selection { NONE, LINES, PIXELS }

    enum class Phase { START, MOVE, END }

    /** Where a contact lands relative to the live box, for [boxTakes]. */
    enum class BoxHit { HANDLE, BODY, TIP, OFF }

    interface Env {
        /** Screen px per dp. */
        val density: Float
        /** The Select tool is in the hand (otherwise a brush, smudge or eraser). */
        val selectToolInHand: Boolean
        val selection: Selection
        /** Nudge (D13) is on: drags bend the selected lines inside the ring. */
        val nudging: Boolean
        /** A pen has been seen on this canvas (JbCanvasView.penHasBeenSeen). */
        val penSeen: Boolean
        /** K18: "One finger, after the pen: Selects" (default false: it pans). */
        val fingerSelects: Boolean
        /** P4 B: a still finger lifts a crosshair. */
        val crosshair: Boolean
        /** The ＋ chip is on (P6 A). */
        val addArmed: Boolean
        /** A tip dot (D14, K16) is under this point. */
        fun onTip(x: Float, y: Float): Boolean
    }

    interface Sink {
        /** A tap: pick / cycle / add / end, by [SelectionState.tap]'s rules. */
        fun tap(x: Float, y: Float, adding: Boolean)
        /** The loop so far (x, y pairs), for the live preview of what it will take (D8). */
        fun lassoMoved(points: FloatArray)
        fun lassoEnded(points: FloatArray, adding: Boolean)
        /** The crosshair point (already lifted above the finger). */
        fun crosshairMoved(x: Float, y: Float)
        fun crosshairEnded(x: Float, y: Float, adding: Boolean)
        /** R42's tap action for the S Pen button (the eyedropper by default). */
        fun penButtonTap(x: Float, y: Float)
        /** A pen stroke with a brush began over a line selection, or a finger tapped while one pans. */
        fun endSelection()
        fun nudge(phase: Phase, x: Float, y: Float)
        fun tip(phase: Phase, x: Float, y: Float)
        /** The gesture was taken away: drop any half-made loop, crosshair, nudge or tip drag. */
        fun cancelled()
    }

    private enum class Mode { IDLE, PENDING, LASSO, CROSSHAIR, BUTTON, NUDGE_PENDING, NUDGE, TIP, PAN_PENDING }

    private var mode = Mode.IDLE
    private var owner = -1
    private var ownerKind = Kind.FINGER
    private var x0 = 0f
    private var y0 = 0f
    private var downAt = 0L
    private var thumbs = 0
    private val loop = ArrayList<Float>()
    private var lastX = 0f
    private var lastY = 0f

    /** True while the tool owns a gesture. */
    val busy: Boolean get() = mode != Mode.IDLE

    private val adding: Boolean get() = env.addArmed || thumbs > 0

    private fun dp(v: Float) = v * env.density

    /**
     * A new first contact. True = the Select tool owns it; false = the canvas does what it would have done (a brush
     * stroke, one-finger navigation). Never called for contacts the box took ([boxTakes]).
     */
    fun down(id: Int, x: Float, y: Float, kind: Kind, penButton: Boolean): Boolean {
        if (mode != Mode.IDLE) return false
        owner = id; ownerKind = kind; x0 = x; y0 = y; lastX = x; lastY = y; downAt = clock(); thumbs = 0; loop.clear()
        val sel = env.selection
        mode = when {
            sel != Selection.NONE && env.nudging -> Mode.NUDGE_PENDING
            sel == Selection.LINES && env.onTip(x, y) -> Mode.TIP.also { sink.tip(Phase.START, x, y) }
            kind == Kind.PEN && penButton -> Mode.BUTTON
            kind == Kind.PEN -> if (env.selectToolInHand) Mode.PENDING else {
                if (sel == Selection.LINES) sink.endSelection()
                Mode.IDLE
            }
            !env.penSeen && !env.selectToolInHand -> Mode.IDLE // before any pen, a finger draws
            env.selectToolInHand || env.fingerSelects -> Mode.PENDING
            sel != Selection.NONE -> Mode.PAN_PENDING // it pans, but a tap still ends the selection
            else -> Mode.IDLE
        }
        if (mode == Mode.IDLE) owner = -1
        return mode != Mode.IDLE
    }

    /** Another contact while the tool owns a gesture. A finger beside a working pen is a resting thumb (K12). */
    fun otherDown(id: Int, kind: Kind): Boolean {
        if (mode == Mode.IDLE || id == owner) return false
        if (ownerKind == Kind.PEN && kind == Kind.FINGER) { thumbs++; return true }
        // A second finger beside a finger: the page moves (the box takes two fingers itself while a selection lives).
        abandon()
        return false
    }

    fun otherUp(id: Int) { if (id != owner && thumbs > 0) thumbs-- }

    /** False hands the rest of the gesture back to navigation. */
    fun move(id: Int, x: Float, y: Float): Boolean {
        if (id != owner) return mode != Mode.IDLE
        lastX = x; lastY = y
        val far = hypot(x - x0, y - y0) > dp(SLOP_DP)
        when (mode) {
            Mode.PENDING -> if (far) {
                mode = Mode.LASSO; loop.add(x0); loop.add(y0); loop.add(x); loop.add(y); sink.lassoMoved(loop.toFloatArray())
            }
            Mode.BUTTON -> if (far) { mode = Mode.LASSO; loop.add(x0); loop.add(y0); loop.add(x); loop.add(y); sink.lassoMoved(loop.toFloatArray()) }
            Mode.LASSO -> {
                val n = loop.size
                if (hypot(x - loop[n - 2], y - loop[n - 1]) >= dp(2f)) { loop.add(x); loop.add(y); sink.lassoMoved(loop.toFloatArray()) }
            }
            Mode.CROSSHAIR -> sink.crosshairMoved(x, y - dp(CROSSHAIR_LIFT_DP))
            Mode.NUDGE_PENDING -> if (far) { mode = Mode.NUDGE; sink.nudge(Phase.START, x0, y0); sink.nudge(Phase.MOVE, x, y) }
            Mode.NUDGE -> sink.nudge(Phase.MOVE, x, y)
            Mode.TIP -> sink.tip(Phase.MOVE, x, y)
            Mode.PAN_PENDING -> if (far) { reset(); return false }
            Mode.IDLE -> {}
        }
        return true
    }

    /** Drives the crosshair hold. Call at least once [CROSSHAIR_HOLD_MS] after a finger's down. */
    fun tick() {
        if (mode == Mode.PENDING && ownerKind == Kind.FINGER && env.crosshair && clock() - downAt >= CROSSHAIR_HOLD_MS &&
            hypot(lastX - x0, lastY - y0) <= dp(SLOP_DP)
        ) {
            mode = Mode.CROSSHAIR
            sink.crosshairMoved(lastX, lastY - dp(CROSSHAIR_LIFT_DP))
        }
    }

    fun up(id: Int, x: Float, y: Float) {
        if (id != owner) { otherUp(id); return }
        when (mode) {
            Mode.PENDING, Mode.PAN_PENDING -> if (mode == Mode.PAN_PENDING) sink.endSelection() else sink.tap(x0, y0, adding)
            Mode.BUTTON -> sink.penButtonTap(x0, y0)
            Mode.LASSO -> {
                loop.add(x); loop.add(y)
                var length = 0f
                for (i in 2 until loop.size step 2) length += hypot(loop[i] - loop[i - 2], loop[i + 1] - loop[i - 1])
                if (length < dp(LASSO_MIN_DP)) sink.tap(x0, y0, adding) else sink.lassoEnded(loop.toFloatArray(), adding)
            }
            Mode.CROSSHAIR -> sink.crosshairEnded(x, y - dp(CROSSHAIR_LIFT_DP), adding)
            Mode.NUDGE_PENDING -> sink.tap(x0, y0, false) // inside the selection: nothing; outside: it ends (Sink decides)
            Mode.NUDGE -> sink.nudge(Phase.END, x, y)
            Mode.TIP -> sink.tip(Phase.END, x, y)
            Mode.IDLE -> {}
        }
        reset()
    }

    fun cancel() { if (mode != Mode.IDLE) abandon() }

    private fun abandon() { sink.cancelled(); reset() }

    private fun reset() { mode = Mode.IDLE; owner = -1; thumbs = 0; loop.clear() }

    companion object {
        /** Movement that turns a press into a drag (D3: a tap lifts within this). */
        const val SLOP_DP = 8f
        /** A loop shorter than this is a tap (D8). */
        const val LASSO_MIN_DP = 24f
        /** A finger still this long lifts the crosshair (D7). */
        const val CROSSHAIR_HOLD_MS = 300L
        /** How far above the fingertip the crosshair sits (D7). */
        const val CROSSHAIR_LIFT_DP = 48f

        /**
         * The box's gate (D12, §K K19): whether a contact landing on the live box belongs to the box (true) or goes on to
         * the canvas and this router (false).
         * - Handles always belong to the box.
         * - The body belongs to the box, except a plain pen with a brush in hand: the pen keeps its tool and paints.
         * - A tip dot belongs to the router (it bends the line's end).
         * - Off the box, a finger is the box's (held briefly for a two-finger move anywhere, P8 B); a pen never is, so
         *   drawing outside a selection is never delayed.
         */
        fun boxTakes(hit: BoxHit, kind: Kind, penButton: Boolean, selectToolInHand: Boolean): Boolean = when (hit) {
            BoxHit.HANDLE -> true
            BoxHit.BODY -> !(kind == Kind.PEN && !penButton && !selectToolInHand)
            BoxHit.TIP -> false
            BoxHit.OFF -> kind == Kind.FINGER
        }
    }
}
