package cc.joycreator.joybrush.core.media

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The wet sheet's arithmetic (tasks/joybrush/specs/WET_SHEET_20261110.md): pure, so its rules are tested on the JVM.
 *
 * While watercolour is wet it lives on ONE continuous grid, the sheet, [CELLS] cells a side, each cell [cellFor] document
 * pixels wide: about one to two pixels of the SCREEN at the zoom the painter works at. Nothing in the water is divided into
 * tiles, so nothing can leave a tile-shaped seam, and the water's work is bounded by the sheet, not by how much paper the
 * strokes cross.
 */
object WetSheetMath {
    /** Cells along each side of the sheet. */
    const val CELLS = 1024

    /** The coarsest and finest cell, in document px. */
    const val MAX_CELL = 8
    const val MIN_CELL = 1

    /** The sheet's origin snaps to this many cells, so the paper bakes and the 16-cell activity blocks line up after a move. */
    const val SNAP_CELLS = 16

    /**
     * Document px per cell for a view at [zoom] (screen px per document px): the largest power of two no more than two screen
     * px wide. At 100 % a cell is 2 px; zoomed out to 31 % it is 4 px (1.2 px on screen); zoomed in past 200 % it is 1 px.
     */
    fun cellFor(zoom: Float): Int {
        if (!(zoom > 0f)) return 2
        var c = MIN_CELL
        while (c * 2 <= MAX_CELL && c * 2 * zoom <= 2.0001f) c *= 2
        return c
    }

    /** The sheet's origin along one axis (document px) so that [pen] is at its middle, snapped to [SNAP_CELLS] cells. */
    fun origin(pen: Double, cell: Int): Float {
        val snap = (SNAP_CELLS * cell).toDouble()
        return (floor((pen - CELLS * cell / 2.0) / snap) * snap).toFloat()
    }

    /** True when document ([x], [y]) lies in the sheet at ([ox], [oy]), at least [margin] document px from its edge. */
    fun covers(ox: Float, oy: Float, cell: Int, x: Double, y: Double, margin: Double): Boolean {
        val side = CELLS * cell
        return x >= ox + margin && y >= oy + margin && x < ox + side - margin && y < oy + side - margin
    }

    /**
     * The longest stable time step (s) for cells [cell] document px wide. The water's spreading terms are explicit diffusion
     * (the pigment's mingling is the first to break: 12 a second into four neighbours), whose stable step grows with the
     * cell's area. Full-resolution cells allow no more than the shipped 1/120 s: on the Note 9, 1/60 s and 1/30 s broke into
     * coloured speckle (2026-11-10). Capped at 1/30 s, a frame.
     */
    fun maxDt(cell: Int): Double = if (cell <= 1) 1.0 / 120 else min(1.0 / 30, 0.8 * cell * cell / 100.0)

    /**
     * The water's constants for a frame that came round in [realMs] and runs [steps] water steps: the time step is the frame's
     * real time shared between the steps (never under the shipped step, never over [maxDt]), so the water spreads, soaks and
     * dries at the pace of the clock whatever the phone manages; only how far running water can travel is left to the number
     * of steps (0.45 cells each).
     *
     * The levelling flow keeps a share of itself from step to step (`keep`, the viscosity). That share and the push are
     * given per shipped step, so for a longer step the kept share is the same per SECOND and the push is what leaves the
     * steady flow unchanged: f = keep' * f + dt * flow' * dh settles at the same f as the shipped constants do.
     */
    fun constants(base: WetConstants, realMs: Float, steps: Int, cell: Int): WetConstants {
        val n = max(1, steps)
        val want = if (realMs > 0f) realMs / 1000.0 / n else base.dt
        val dt = want.coerceIn(base.dt, max(base.dt, maxDt(cell)))
        if (dt == base.dt) return base.copy(substeps = n)
        val keep = base.keep.pow(dt / base.dt)
        val flow = base.flow * (1 - keep) * base.dt / ((1 - base.keep) * dt)
        return base.copy(dt = dt, substeps = n, keep = keep, flow = flow)
    }

    /**
     * How many water steps a frame runs, steered by how long frames take to come round: more while frames are quick, one
     * fewer after a slow one, and then no higher than that for a while (a screen that ticks every 16.7 ms makes frames land on
     * 33 or 50 ms, so probing upward every frame would drop a frame each time).
     */
    class Pace(private val most: Int = 8) {
        var steps = 2
            private set
        private var ceiling = most
        private var calm = 0

        fun next(realMs: Float): Int {
            if (realMs <= 0f) return steps
            if (realMs > SLOW_MS) {
                steps = max(1, steps - 1)
                ceiling = steps
                calm = 0
            } else {
                calm++
                // After two seconds of good frames the ceiling lifts by one, to find out whether there is room again.
                if (calm > 60 && ceiling < most) { ceiling++; calm = 0 }
                if (realMs < QUICK_MS && steps < ceiling) steps++
            }
            return steps
        }

        fun reset() { steps = 2; ceiling = most; calm = 0 }

        private companion object {
            /** A frame that took longer than this missed the 30-a-second beat on a 60 Hz screen. */
            const val SLOW_MS = 42f
            const val QUICK_MS = 36f
        }
    }
}
