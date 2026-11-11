package cc.joycreator.joybrush.core.media

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The wet sheet's arithmetic (tasks/joybrush/specs/WET_SHEET_20261110.md): pure, so its rules are tested on the JVM.
 *
 * While watercolour is wet it lives on ONE continuous grid, the sheet, shaped like the screen and laid over what the screen
 * shows: [LONG_DETAILED] cells along the screen's long side, so a cell is about a pixel and three quarters of the SCREEN at
 * whatever zoom the painter works at. Nothing in the water is divided into tiles, so nothing can leave a tile-shaped seam,
 * and the water's work is bounded by the screen, not by how much paper the strokes cross.
 */
object WetSheetMath {
    /** Cells along the sheet's long side: the owner's Water detail switch, Detailed and Fast. */
    const val LONG_DETAILED = 1280
    const val LONG_FAST = 896

    /** The finest and coarsest cell, in document px. Finer than a document pixel could not be kept when the paint dries. */
    const val MIN_CELL = 1.0
    const val MAX_CELL = 16.0

    /** The sheet's origin snaps to this many cells, so the 16-cell activity blocks line up after a move. */
    const val SNAP_CELLS = 16

    /**
     * The widest cell the water's physics is run at, in the lab's pixels (20 to the millimetre). Up to here the sheet is
     * checked against the lab's own look (tools/media_render.js "test=flood2&cell=2", 2026-11-10); wider cells slide as one
     * sheet. So when a cell is wider than this on the paper (the painter is zoomed out), the water treats the paper as
     * magnified instead ([worldScale]): it behaves on the SCREEN as it does at 100 %.
     */
    const val MAX_PHYSICS_CELL = 2.0

    /** The sheet's size in cells. */
    data class Grid(val w: Int, val h: Int) { val cells: Int get() = w * h }

    /**
     * The grid for a screen [screenW] x [screenH] px with [longCells] along its long side: the same shape as the screen,
     * each side a multiple of [SNAP_CELLS].
     */
    fun grid(screenW: Int, screenH: Int, longCells: Int = LONG_DETAILED): Grid {
        val long = max(screenW, screenH).coerceAtLeast(1)
        val short = min(screenW, screenH).coerceAtLeast(1)
        val l = longCells / SNAP_CELLS * SNAP_CELLS
        val s = (ceil(l.toDouble() * short / long / SNAP_CELLS).toInt() * SNAP_CELLS).coerceIn(SNAP_CELLS * 8, l)
        return if (screenW >= screenH) Grid(l, s) else Grid(s, l)
    }

    /**
     * Document px per cell for a view at [zoom] (screen px per document px) on a screen [screenLong] px along its long side:
     * the cell at which the grid's long side covers the screen's, in eighths of a pixel, never finer than a document pixel.
     */
    fun cellFor(zoom: Float, screenLong: Int, longCells: Int = LONG_DETAILED): Double {
        if (!(zoom > 0f) || screenLong <= 0) return 2.0
        val c = screenLong.toDouble() / longCells / zoom
        return ((c * 8).roundToInt() / 8.0).coerceIn(MIN_CELL, MAX_CELL)
    }

    /**
     * How much the water's world is magnified: 1 while a cell is no wider than [MAX_PHYSICS_CELL] lab pixels (the paper is
     * life size: a millimetre of water is a millimetre of paper), more when the painter is zoomed further out, so that a
     * cell is never wider than that to the physics. A wash laid zoomed out runs and spreads on the screen as one laid at
     * 100 % does; in the drawing its drips are that much bigger, as a big brush on a big sheet.
     */
    fun worldScale(cell: Double): Double = max(1.0, cell / MAX_PHYSICS_CELL)

    /** A cell's width to the physics, in lab pixels: [cell] over [worldScale], so 1 to [MAX_PHYSICS_CELL]. */
    fun physicsCell(cell: Double): Double = cell / worldScale(cell)

    /** The sheet's origin along one axis (document px) so that [centre] is at its middle, snapped to [SNAP_CELLS] cells. */
    fun origin(centre: Double, cells: Int, cell: Double): Float {
        val snap = SNAP_CELLS * cell
        return (floor((centre - cells * cell / 2.0) / snap) * snap).toFloat()
    }

    /** True when document ([x], [y]) lies in the sheet at ([ox], [oy]), at least [margin] document px from its edge. */
    fun covers(ox: Float, oy: Float, grid: Grid, cell: Double, x: Double, y: Double, margin: Double): Boolean =
        x >= ox + margin && y >= oy + margin && x < ox + grid.w * cell - margin && y < oy + grid.h * cell - margin

    /**
     * A sheet made for cells [had] document px wide is too coarse for a view that now calls for [wanted]: the painter has
     * zoomed in far enough that a cell would show as a block (more than about three screen pixels).
     */
    fun tooCoarse(had: Double, wanted: Double): Boolean = had > wanted * 1.75

    /**
     * The longest stable time step (s) for cells [cell] lab pixels wide. The water's spreading terms are explicit diffusion
     * (the pigment's mingling is the first to break: 12 a second into four neighbours), whose stable step grows with the
     * cell's area. Full-resolution cells allow no more than the shipped 1/120 s: on the Note 9, 1/60 s and 1/30 s broke into
     * coloured speckle (2026-11-10). Capped at 1/30 s, a frame.
     * Checked against the lab's own look at 2 px cells with 1/60 s and 4 px cells with 1/30 s (flood2, 2026-11-10).
     */
    fun maxDt(cell: Double): Double = if (cell <= 1.0) 1.0 / 120 else min(1.0 / 30, 0.8 * cell * cell / 100.0)

    /**
     * The step at which running water reaches its limit ([runCapMmPerS]): a step carries water 0.45 cells at most, so the
     * limit needs a step no longer than cell / (120 x speed) seconds. Never longer than the stable one.
     */
    fun runDt(cell: Double, speed: Double = 1.0): Double = min(maxDt(cell), cell / (120.0 * max(speed, 1e-3)))

    /**
     * The fastest running water may travel, mm/s. On full-resolution cells a step carries water 0.45 cells at most, which at
     * the shipped step is 2.7 mm/s, and the look the owner approved in the lab was tuned with it: drips break into fingers
     * because the water cannot outrun them. Wider cells would let it run two to eight times as fast and slide as one sheet
     * (lab, 2026-11-10: tools/media_render.js "test=flood2&cell=4"), so the sheet keeps the limit in millimetres.
     * [speed] is the owner's dial: 1 = as approved, 2 = twice as fast.
     *
     * A step still carries water 0.45 cells at most, so the limit is only reached with steps no longer than [runDt]:
     * [stepsWanted] asks for that many. A phone that cannot run them keeps the clock's pace for everything else (the
     * soaking, the spreading, the drying) with longer steps, and its drips run that much under the limit.
     */
    fun runCapMmPerS(speed: Double = 1.0): Double = 0.45 * 120 / DOC_PX_PER_MM * speed

    /**
     * The water steps a frame that came round in [realMs] wants on cells [cell] lab pixels wide, for running water to reach
     * its limit ([runDt]). A step is not added for the last third: on the Note 9's 1.75 px cells two steps a frame let drips
     * reach seven eighths of it, and a third step would cost half as much again for the rest.
     */
    fun stepsWanted(realMs: Float, cell: Double, speed: Double = 1.0): Int =
        if (realMs <= 0f) 1 else ceil(realMs / 1000.0 / runDt(cell, speed) - 0.3).toInt().coerceAtLeast(1)

    /**
     * The water's constants for a frame that came round in [realMs] and runs [steps] water steps on cells [cell] lab pixels
     * wide: the time step is the frame's real time shared between the steps (never under the shipped step, never over
     * [maxDt]), so the water spreads, soaks and dries at the pace of the clock whatever the phone manages; only how far
     * running water can travel is left to the number of steps (0.45 cells each).
     *
     * The levelling flow keeps a share of itself from step to step (`keep`, the viscosity). That share and the push are
     * given per shipped step, so for a longer step the kept share is the same per SECOND and the push is what leaves the
     * steady flow unchanged: f = keep' * f + dt * flow' * dh settles at the same f as the shipped constants do.
     */
    fun constants(base: WetConstants, realMs: Float, steps: Int, cell: Double): WetConstants {
        val n = max(1, steps)
        val want = if (realMs > 0f) realMs / 1000.0 / n else base.dt
        val dt = want.coerceIn(base.dt, max(base.dt, maxDt(cell)))
        if (dt == base.dt) return base.copy(substeps = n)
        val keep = base.keep.pow(dt / base.dt)
        val flow = base.flow * (1 - keep) * base.dt / ((1 - base.keep) * dt)
        return base.copy(dt = dt, substeps = n, keep = keep, flow = flow)
    }

    /**
     * How many water steps a frame may run, steered by how long frames take to come round: as many as the clock's pace
     * wants while frames are quick, one fewer after a slow one, and then no more than that for a while (a screen that ticks
     * every 16.7 ms makes frames land on 33 or 50 ms, so probing upward every frame would drop a frame each time).
     */
    class Pace(private val most: Int = 8) {
        var steps = 1
            private set
        private var ceiling = most
        private var calm = 0

        /** The steps for this frame: [want] ([stepsWanted]), no more than the phone has shown it keeps up with. */
        fun next(realMs: Float, want: Int): Int {
            if (realMs > SLOW_MS) {
                ceiling = max(1, min(ceiling, steps) - 1)
                calm = 0
            } else if (realMs > 0f) {
                calm++
                // After two seconds of good frames the ceiling lifts by one, to find out whether there is room again.
                if (calm > 60 && ceiling < most) { ceiling++; calm = 0 }
            }
            steps = want.coerceIn(1, ceiling)
            return steps
        }

        fun reset() { steps = 1; ceiling = most; calm = 0 }

        private companion object {
            /**
             * A frame that took longer than this is too slow to watch (under 18 a second): a step is given up. Up to here the
             * steps are worth more than the frames: on the Note 9 a big wash at two steps comes round every 37 ms, and backing
             * off at 42 left it at one step and six tenths of the clock (2026-11-10).
             */
            const val SLOW_MS = 55f
        }
    }

    // ---- the undo log's marks (UndoLog.Step.sheet) ----
    /** A step that only prints dried paint into the layer: undone and redone together with the step under it. */
    fun isPrintMark(mark: Long): Boolean = mark and 1L == 1L
    fun strokeMark(serial: Long): Long = serial shl 1
    fun printMark(serial: Long): Long = (serial shl 1) or 1L
}
