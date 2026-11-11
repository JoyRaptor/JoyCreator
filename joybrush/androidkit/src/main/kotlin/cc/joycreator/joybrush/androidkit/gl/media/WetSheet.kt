package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.GraphicsMemoryException
import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import cc.joycreator.joybrush.core.layers.LayerBudget
import cc.joycreator.joybrush.core.media.DOC_PX_PER_MM
import cc.joycreator.joybrush.core.media.DabBatch
import cc.joycreator.joybrush.core.media.WetConstants
import cc.joycreator.joybrush.core.media.WetSheetMath

/**
 * The wet sheet (tasks/joybrush/specs/WET_SHEET_20261110.md): watercolour while it is wet.
 *
 * The owner's Note 9, 2026-11-10: eight or nine watercolour marks painted zoomed out left water on 85 to 92 tiles, the
 * atlas holds 62, and everything the atlas does when water does not fit (pools paused, tiles set aside, running tiles
 * parked through their stores, water run block by block) showed as frozen water and tile-shaped squares, at 4 water steps a
 * second. Water in tiles cannot be made to feel like paper there.
 *
 * So wet watercolour lives on ONE continuous grid, [WetSheetMath.CELLS] cells a side, each cell about one to two SCREEN
 * pixels at the zoom it is painted at ([WetSheetMath.cellFor]). It is the media engine itself, run as a plain window at a
 * reduced scale (the same shaders, the same physics, no atlas, no page table): nothing in the water knows about tiles, so
 * no seam, no square and no capacity to overflow. Its look is drawn over its layer every frame as that layer's overlay
 * ([GlPaintEngine.layerOverlays]), so the whole wash shows one moment. As paint dries it is printed into the layer's own
 * tiles at full resolution.
 *
 * GL thread only.
 */
class WetSheet(private val paint: GlPaintEngine, private val shaders: ShaderLibrary) {
    private var engine: MediaLayerEngine? = null

    /** The layer whose wet watercolour this holds, or null. */
    var layerId: String? = null
        private set

    /** Document px per cell, and the sheet's origin (document px). */
    var cell = 2
        private set
    var originX = 0f
        private set
    var originY = 0f
        private set

    /** Holds water or paint that is not printed yet. */
    var holding = false
        private set

    /** Water is running: keep drawing frames. */
    val running: Boolean get() = engine?.wetActive == true

    private val pace = WetSheetMath.Pace()

    /** The side of the sheet in document px. */
    val side: Int get() = WetSheetMath.CELLS * cell

    fun covers(docX: Double, docY: Double, margin: Double = EDGE_MARGIN_PX * cell.toDouble()): Boolean =
        WetSheetMath.covers(originX, originY, cell, docX, docY, margin)

    /**
     * A wet stroke starts on [layer] at document ([docX], [docY]), seen at [zoom]. An empty sheet is placed round the pen at
     * the cell that zoom calls for. Returns the sentence to show when the phone has no memory for the sheet.
     */
    fun begin(layer: String, docX: Double, docY: Double, zoom: Float, availBytes: Long, thresholdBytes: Long): String? {
        val e = engine
        if (e == null) {
            if (!LayerBudget.windowFits(availBytes, thresholdBytes, wet = true)) return LayerBudget.windowRefusal(true)
            place(docX, docY, zoom)
            val made = MediaLayerEngine(shaders, atlasMode = false, coarseWater = false)
            try { made.init(WetSheetMath.CELLS, WetSheetMath.CELLS, originX, originY, 1f / cell) } catch (x: GraphicsMemoryException) {
                android.util.Log.e(TAG, "no graphics memory for the wet sheet: ${x.message}")
                runCatching { made.release() }
                return LayerBudget.windowRefusal(true)
            }
            // The look is magnified onto the screen: smoothly.
            val look = made.lookTexture()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, look)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            engine = made
            android.util.Log.i(TAG, "sheet made: ${WetSheetMath.CELLS} cells of $cell px at ($originX, $originY), zoom $zoom")
        } else if (!holding || layer != layerId) {
            // Nothing of its own left on it (or another layer's turn): free to move to the pen and take this zoom's cell.
            place(docX, docY, zoom)
            e.moveTo(originX, originY, 1f / cell)
            pace.reset()
            android.util.Log.i(TAG, "sheet moved: cells of $cell px at ($originX, $originY), zoom $zoom")
        } else if (!covers(docX, docY)) {
            android.util.Log.w(TAG, "a wet stroke starts off the sheet (at $docX, $docY; sheet $originX, $originY, $side px): what falls outside is not painted")
        }
        layerId = layer
        holding = true
        return null
    }

    private fun place(docX: Double, docY: Double, zoom: Float) {
        cell = forcedCell ?: WetSheetMath.cellFor(zoom)
        originX = WetSheetMath.origin(docX, cell)
        originY = WetSheetMath.origin(docY, cell)
    }

    /**
     * One drawn frame: this frame's dabs ([batch], document px, or null), then as many water steps as the phone keeps up with
     * ([WetSheetMath.Pace]) at a time step that keeps the water at the clock's pace ([WetSheetMath.constants]), then the look
     * of everything wet. [realMs]: how long the frame took to come round. Returns true while water runs.
     */
    fun frame(batch: DabBatch?, paper: MediaPaperGl, base: WetConstants, slope: FloatArray, look: MediaLook, realMs: Float): Boolean {
        val e = engine ?: return false
        val input = batch?.takeIf { it.count > 0 }
        if (input == null && !e.wetActive) return false
        e.slope = slope
        e.frameRealMs = realMs
        e.runCapMmPerS = WetSheetMath.runCapMmPerS(flowSpeed)
        val steps = pace.next(realMs)
        val wc = WetSheetMath.constants(base, realMs, steps, cell, flowSpeed)
        e.wetFrame(input, paper, DOC_PX_PER_MM, wc, substeps = steps)
        e.takeDirty(); e.takeDirtyStores()
        val wet = e.wetRect
        if (wet != null) {
            // No relief on the sheet: watercolour has next to no body, and the shadow march is most of a look's cost.
            val flat = MediaLook(look.paperColor, look.lamp, impasto = 0f, sheen = look.sheen, capMm = look.capMm)
            e.renderLook(wet, paper, flat, DOC_PX_PER_MM)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return e.wetActive
    }

    /** The newest wet brush's granulation (WetBrush.gran). */
    fun setGranulation(gran: Double) { engine?.wetGran = gran }

    /** What the canvas draws over the layer: the sheet's look, or null when it holds nothing. */
    fun overlay(): GlPaintEngine.LayerOverlay? {
        val e = engine ?: return null
        val id = layerId ?: return null
        if (!holding) return null
        return GlPaintEngine.LayerOverlay(id, e.lookTexture(), originX, originY, side.toFloat())
    }

    /** GL context lost or the canvas closing. */
    fun release() {
        engine?.release()
        engine = null; layerId = null; holding = false
    }

    /** The context these GL names came from is gone: forget them without deleting. */
    fun forget() { engine = null; layerId = null; holding = false }

    companion object {
        private const val TAG = "JoyBrushSheet"
        /** A stroke that starts closer than this many cells to the sheet's edge is off it. */
        const val EDGE_MARGIN_PX = 48

        /** The owner's switch (the ⋯ menu) and the bench's `jb_wet_sheet`: watercolour on the wet sheet. */
        @Volatile @JvmStatic var enabled = false

        /** The owner's dial: how fast running water may travel, 1 = as approved in the lab (WetSheetMath.runCapMmPerS). */
        @Volatile @JvmStatic var flowSpeed = 1.0

        /** Debug bench only (`jb_sheet_cell`): the cell in document px whatever the zoom (the bench's view is always 100 %). */
        @Volatile @JvmStatic var forcedCell: Int? = null
    }
}
