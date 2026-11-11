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
import cc.joycreator.joybrush.core.paint.Tiles
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The wet sheet (tasks/joybrush/specs/WET_SHEET_20261110.md): watercolour while it is wet.
 *
 * The owner's Note 9, 2026-11-10: eight or nine watercolour marks painted zoomed out left water on 85 to 92 tiles, the
 * atlas holds 62, and everything the atlas does when water does not fit (pools paused, tiles set aside, running tiles
 * parked through their stores, water run block by block) showed as frozen water and tile-shaped squares, at 4 water steps a
 * second. Water in tiles cannot be made to feel like paper there.
 *
 * So wet watercolour lives on ONE continuous grid shaped like the screen and laid over what the screen shows
 * ([WetSheetMath.grid], [WetSheetMath.cellFor]). It is the media engine itself, run as a plain window at a reduced scale
 * (the same shaders, the same physics, no atlas, no page table): nothing in the water knows about tiles, so no seam, no
 * square and no capacity to overflow. Its look, the paint alone lit over the layer's own pixels, is drawn over its layer
 * every frame ([GlPaintEngine.layerOverlays]). When all of it has dried it is PRINTED into the layer's tiles with the very
 * quad the screen showed ([GlPaintEngine.printOverlay]), so nothing moves at that moment, and the sheet is empty again,
 * free to be laid over wherever the painter looks next.
 *
 * Undo: every stroke on the sheet is a step that changes no tile; the sheet keeps what it held before the stroke
 * ([History]). A print is a step of its own that goes back and forth together with the step under it. While there is
 * something to redo the water stands still, so redo finds what undo left.
 *
 * GL thread only.
 */
class WetSheet(private val paint: GlPaintEngine, private val shaders: ShaderLibrary) {
    /** What the painter sees: the zoom (screen px per document px), the document point at the screen's middle, the screen. */
    class View(val zoom: Float, val centreX: Double, val centreY: Double, val screenW: Int, val screenH: Int, val rotation: Float = 0f)

    private var engine: MediaLayerEngine? = null
    private var grid = WetSheetMath.Grid(0, 0)

    /** The layer whose wet watercolour this holds, or null. */
    var layerId: String? = null
        private set

    /** Document px per cell, and the sheet's origin (document px). */
    var cell = 2.0
        private set
    var originX = 0f
        private set
    var originY = 0f
        private set

    /** Holds water or paint that is not printed yet. */
    var holding = false
        private set

    /** Water is on the sheet (it runs unless the caller holds it still). */
    val wet: Boolean get() = holding && engine?.wetActive == true

    /** Everything held lies inside this rectangle (cells): every wet rectangle since the sheet was last empty. */
    private var content: FloatArray? = null
    /** Where the look no longer shows the state (cells): drawn again at the next frame. */
    private var lookStale: FloatArray? = null
    private var lastWet: FloatArray? = null
    private var groundStale = true
    private var idleSince = 0L

    private val pace = WetSheetMath.Pace()
    private val history = History()

    // The engine's look and ground textures, and framebuffers of this sheet's own on them (made again when the engine wakes).
    private var lookFbo = 0; private var lookFboTex = 0
    private var groundFbo = 0; private var groundFboTex = 0

    private val docW: Float get() = (grid.w * cell).toFloat()
    private val docH: Float get() = (grid.h * cell).toFloat()

    private fun covers(docX: Double, docY: Double, margin: Double = EDGE_MARGIN_CELLS * cell): Boolean =
        WetSheetMath.covers(originX, originY, grid, cell, docX, docY, margin)

    // ---- strokes ----

    /**
     * A wet stroke starts on [layer] at document ([docX], [docY]), seen through [view]. An empty sheet is laid over the
     * view. One that still holds another layer's paint, does not reach the pen, or was laid for a view so much further out
     * that its cells would show as blocks, prints what it holds first (as it lies: [settle]) and is laid again.
     * Returns the sentence to show when the phone has no memory for the sheet.
     */
    fun begin(layer: String, docX: Double, docY: Double, view: View, paper: MediaPaperGl, look: MediaLook,
              availBytes: Long, thresholdBytes: Long): String? {
        val turned = abs(sin(view.rotation.toDouble())) > 0.7071
        val wantGrid = if (turned) WetSheetMath.grid(view.screenH, view.screenW, longCells) else WetSheetMath.grid(view.screenW, view.screenH, longCells)
        val wantCell = forcedCell?.toDouble() ?: WetSheetMath.cellFor(view.zoom, max(view.screenW, view.screenH), longCells)
        if (holding && (layer != layerId || !covers(docX, docY) || WetSheetMath.tooCoarse(cell, wantCell))) {
            android.util.Log.i(TAG, "the wash sets: the next stroke is on ${if (layer != layerId) "another layer" else if (!covers(docX, docY)) "paper the sheet does not reach" else "a closer view"}")
            settle(paper, look)
        }
        val before = history.now()
        if (!holding) {
            if (engine == null || grid != wantGrid) {
                if (!LayerBudget.windowFits(availBytes, thresholdBytes, wet = true)) return LayerBudget.windowRefusal(true)
                releaseEngine()
                val made = MediaLayerEngine(shaders, atlasMode = false, coarseWater = false)
                try { made.init(wantGrid.w, wantGrid.h, 0f, 0f, 1f) } catch (x: GraphicsMemoryException) {
                    android.util.Log.e(TAG, "no graphics memory for the wet sheet: ${x.message}")
                    runCatching { made.release() }
                    return LayerBudget.windowRefusal(true)
                }
                made.lookPaintOnly = true
                engine = made; grid = wantGrid
                android.util.Log.i(TAG, "sheet made: ${grid.w} x ${grid.h} cells")
            }
            val e = engine!!
            if (e.asleep) try { e.wake() } catch (x: GraphicsMemoryException) {
                android.util.Log.e(TAG, "no graphics memory to wake the wet sheet: ${x.message}")
                return LayerBudget.windowRefusal(true)
            }
            cell = wantCell
            // Over the middle of the view; over the pen when that would not reach it (a view turned part way, the bench's cell).
            originX = WetSheetMath.origin(view.centreX, grid.w, cell); originY = WetSheetMath.origin(view.centreY, grid.h, cell)
            if (!covers(docX, docY)) { originX = WetSheetMath.origin(docX, grid.w, cell); originY = WetSheetMath.origin(docY, grid.h, cell) }
            e.moveTo(originX, originY, (1.0 / cell).toFloat())
            e.worldScale = WetSheetMath.worldScale(cell)
            pace.reset()
            content = null; lookStale = null; lastWet = null; groundStale = true
            android.util.Log.i(TAG, "sheet laid: cells of $cell px at ($originX, $originY), zoom ${view.zoom}, world x${e.worldScale}")
        }
        layerId = layer
        holding = true
        history.strokeBegan(before)
        return null
    }

    /** The stroke [begin] started is over: its one undo step. */
    fun strokeEnded() = history.strokeEnded()

    /** The stroke [begin] started is given up: the sheet is as it was before it, and no step is made. */
    fun strokeCancelled() = history.strokeCancelled()

    /**
     * One drawn frame: this frame's dabs ([batch], document px, or null), then as many water steps as the clock's pace wants
     * and the phone keeps up with ([WetSheetMath.stepsWanted], [WetSheetMath.Pace]), then the look of what changed.
     * [realMs]: how long the frame took to come round. [still]: the water stands (there is something to redo).
     * Returns true while water runs.
     */
    fun frame(batch: DabBatch?, paper: MediaPaperGl, base: WetConstants, slope: FloatArray, look: MediaLook, realMs: Float, still: Boolean): Boolean {
        val e = engine ?: return false
        if (!holding || e.asleep) return false
        val input = batch?.takeIf { it.count > 0 }
        val run = input != null || (e.wetActive && !still)
        if (run) {
            val pc = WetSheetMath.physicsCell(cell)
            e.slope = slope
            e.frameRealMs = realMs
            e.worldScale = WetSheetMath.worldScale(cell)
            e.runCapMmPerS = WetSheetMath.runCapMmPerS(flowSpeed)
            val steps = pace.next(realMs, WetSheetMath.stepsWanted(realMs, pc, flowSpeed))
            e.wetFrame(input, paper, DOC_PX_PER_MM, WetSheetMath.constants(base, realMs, steps, pc), substeps = steps)
            e.takeDirty(); e.takeDirtyStores()
            history.changed()
            if (!memoryLogged && e.hasWaterTextures) {
                memoryLogged = true
                val bytes = runCatching { org.json.JSONObject(e.memoryReport()).optLong("totalBytes") }.getOrDefault(0L)
                android.util.Log.i(TAG, "sheet memory: ${bytes shr 20} MB for ${grid.w} x ${grid.h} cells")
            }
        }
        val wetNow = e.wetRect
        if (wetNow != null) content = union(content, wetNow)
        // Where the water was a frame ago is drawn once more after it has gone: the dry look.
        if (run) lookStale = union(lookStale, union(wetNow, lastWet))
        lastWet = wetNow
        refresh(paper, look)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return e.wetActive && !still
    }

    /** The ground and the look brought up to date (cheap when nothing is stale). */
    private fun refresh(paper: MediaPaperGl, look: MediaLook) {
        val e = engine ?: return
        if (groundStale) {
            groundStale = false
            val id = layerId
            if (id != null) {
                paint.layerLookInto(id, fboOn(e.groundTexture(), ground = true), grid.w, grid.h, originX, originY, docW, docH)
                lookStale = union(lookStale, content)
            }
        }
        val stale = lookStale?.let(::clamp) ?: return
        lookStale = null
        if (stale[2] <= stale[0] || stale[3] <= stale[1]) return
        linearLook(e)
        e.bakePaper(paper)
        // No relief on the sheet: watercolour has next to no body, and the shadow march is most of a look's cost.
        val flat = MediaLook(look.paperColor, look.lamp, impasto = 0f, sheen = look.sheen, capMm = look.capMm)
        e.renderLook(stale, paper, flat, DOC_PX_PER_MM)
    }

    /** The newest wet brush's granulation (WetBrush.gran). */
    fun setGranulation(gran: Double) { engine?.wetGran = gran }

    /** The layer's own pixels changed under the sheet (another step undone, a stroke on it): the paint is lit over them anew. */
    fun groundChanged(layer: String) { if (holding && layer == layerId) groundStale = true }

    /** What the canvas draws over the layer: the sheet's look, or null when it holds nothing. */
    fun overlay(): GlPaintEngine.LayerOverlay? {
        val e = engine ?: return null
        val id = layerId ?: return null
        if (!holding || e.asleep) return null
        return GlPaintEngine.LayerOverlay(id, e.lookTexture(), originX, originY, docW, docH)
    }

    // ---- printing ----

    /** Everything on the sheet is dry and can go into the layer ([print]). */
    val dry: Boolean get() = holding && engine?.wetActive == false

    /**
     * Prints what the sheet holds into its layer's tiles, as the screen shows it, as one undo step, and empties the sheet.
     * Only when all of it is dry ([dry]); [settle] dries first. Returns the tiles written (0 when it held nothing to see).
     */
    fun print(paper: MediaPaperGl, look: MediaLook): Int {
        val e = engine ?: return 0
        if (!holding || e.wetActive || !paint.localHistory) return 0
        lookStale = union(lookStale, content)   // the dry look, whatever was drawn last
        refresh(paper, look)
        val o = overlay()
        val keys = if (o == null) emptyList() else coveredTiles(e)
        // Printed straight after the stroke that laid it: undoing the print leaves the sheet for that stroke's undo to fill.
        val before = if (history.newestIsStroke()) Was.AsIs else history.now()
        val mark = history.printBegan(before)
        val n = if (o != null) paint.printOverlay(o, keys, mark) else { paint.pushSheetStep(mark); 0 }
        empty()
        history.prune()
        android.util.Log.i(TAG, "printed: $n tiles of ${keys.size} under the sheet")
        return n
    }

    /**
     * Whatever the sheet holds goes into the layer NOW, wet or dry: the water dries where it lies first. Before anything
     * else writes the layer, before a save, when the sheet is needed elsewhere. True when it held something.
     */
    fun settle(paper: MediaPaperGl, look: MediaLook): Boolean {
        val e = engine ?: return false
        if (!holding || settling) return false
        if (!paint.localHistory || paint.strokeInProgress || paint.mediaStrokeInProgress) {
            android.util.Log.w(TAG, "the sheet cannot be printed now (a stroke is open, or the history is not this engine's): it stays")
            return false
        }
        settling = true
        try {
            if (e.asleep) e.wake()
            if (e.wetActive) {
                e.dryNow(paper, DOC_PX_PER_MM)
                e.takeDirty(); e.takeDirtyStores()
                history.changed()
            }
            print(paper, look)
        } finally { settling = false }
        return true
    }
    private var settling = false

    /** Bench only: the water dries where it lies and NOTHING is printed, so a screenshot of the dry sheet can be compared with its print. */
    fun dryOnly(paper: MediaPaperGl) {
        val e = engine ?: return
        if (!holding || !e.wetActive) return
        e.dryNow(paper, DOC_PX_PER_MM)
        e.takeDirty(); e.takeDirtyStores()
        lookStale = union(lookStale, content)
        history.changed()
    }
    private var memoryLogged = false

    /** The sheet holds nothing: its engine is cleared where it lies. */
    private fun empty() {
        engine?.let { e -> if (!e.asleep) { e.stopWater(); e.moveTo(originX, originY, (1.0 / cell).toFloat()) } }
        holding = false; content = null; lookStale = null; lastWet = null
        history.changed()
    }

    /**
     * The layer tiles the look has any paint over: the look's alpha read back once (it is small: the sheet is a megapixel)
     * and scanned tile by tile, a cell further than each tile's own, because the look is drawn smoothed between cells.
     */
    private fun coveredTiles(e: MediaLayerEngine): List<Long> {
        val c = content?.let(::clamp) ?: return emptyList()
        val x0 = floor(c[0]).toInt(); val y0 = floor(c[1]).toInt(); val x1 = ceil(c[2]).toInt(); val y1 = ceil(c[3]).toInt()
        val w = x1 - x0; val h = y1 - y0
        if (w <= 0 || h <= 0) return emptyList()
        val px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboOn(e.lookTexture(), ground = false))
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        GLES30.glReadPixels(x0, y0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val t = Tiles.SIZE
        val out = ArrayList<Long>()
        val tx0 = floor((originX + (x0 - 1) * cell) / t).toInt(); val tx1 = floor((originX + (x1 + 1) * cell) / t).toInt()
        val ty0 = floor((originY + (y0 - 1) * cell) / t).toInt(); val ty1 = floor((originY + (y1 + 1) * cell) / t).toInt()
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val cx0 = max(x0, floor((tx * t - originX) / cell).toInt() - 1); val cx1 = min(x1, ceil(((tx + 1) * t - originX) / cell).toInt() + 1)
            val cy0 = max(y0, floor((ty * t - originY) / cell).toInt() - 1); val cy1 = min(y1, ceil(((ty + 1) * t - originY) / cell).toInt() + 1)
            var hit = false
            var y = cy0
            while (y < cy1 && !hit) {
                var i = ((y - y0) * w + (cx0 - x0)) * 4 + 3
                val end = ((y - y0) * w + (cx1 - x0)) * 4 + 3
                while (i < end) { if (px.get(i).toInt() != 0) { hit = true; break }; i += 4 }
                y++
            }
            if (hit) out.add(Tiles.key(tx, ty))
        }
        return out
    }

    // ---- history: what the sheet held, by the undo log's marks ----

    /** The sheet's state at some moment. */
    private sealed class Was {
        /** It held nothing. */
        object Empty : Was()
        /** Leave it as it is (a print straight after its stroke: that stroke's own undo fills the sheet). */
        object AsIs : Was()
        /** What it held is no longer kept (the memory cap, or the graphics restarted): the history ends at this step. */
        object Lost : Was()
        class Held(val snap: Snap) : Was()
    }

    /** A copy of the sheet's state: its paint (and water, while wet) over [rect] (cells), as half floats, with where it lay. */
    private class Snap(val layer: String, val originX: Float, val originY: Float, val cell: Double, val grid: WetSheetMath.Grid,
                       val rect: IntArray, val tex: Map<String, Int>, val wetRect: FloatArray?, val bytes: Long) { var refs = 0 }

    private class Rec(var before: Was, var after: Was? = null)

    private inner class History {
        private val recs = java.util.TreeMap<Long, Rec>()
        private var serial = 0L
        /** The snapshot the sheet's state equals right now, when one was taken or put back and nothing has changed since. */
        private var live: Snap? = null
        private var pending: Was? = null

        fun changed() { live?.let(::release); live = null }

        /** The sheet's state now, kept (one copy however many steps point at it). */
        fun now(): Was {
            if (!holding) return Was.Empty
            live?.let { return Was.Held(retain(it)) }
            val s = try { capture() } catch (x: GraphicsMemoryException) {
                android.util.Log.e(TAG, "no graphics memory to keep the sheet for undo: ${x.message}"); null
            } ?: return Was.Lost
            live = retain(s)
            return Was.Held(retain(s))
        }

        fun strokeBegan(before: Was) { pending?.let(::drop); pending = before }

        fun strokeEnded() {
            val before = pending ?: return
            pending = null
            if (!paint.localHistory) { drop(before); return }
            val mark = WetSheetMath.strokeMark(++serial)
            recs[mark] = Rec(before)
            paint.pushSheetStep(mark)
            prune()
        }

        fun strokeCancelled() {
            val before = pending ?: return
            pending = null
            put(before)
            drop(before)
        }

        fun newestIsStroke(): Boolean = paint.newestSheetMark()?.let { !WetSheetMath.isPrintMark(it) && recs[it] != null } == true

        fun printBegan(before: Was): Long {
            val mark = WetSheetMath.printMark(++serial)
            recs[mark] = Rec(before)
            return mark
        }

        /** The undo log undid ([forward] false) or redid the step carrying [mark]. */
        fun moved(mark: Long, forward: Boolean) {
            val rec = recs[mark]
            if (rec == null) { android.util.Log.w(TAG, "a sheet step with nothing kept for it ($mark) was ${if (forward) "redone" else "undone"}"); return }
            if (forward) { rec.after?.let(::put); return }
            rec.after?.let(::drop)
            rec.after = now()
            put(rec.before)
        }

        fun blocked(mark: Long): Boolean = recs[mark].let { it == null || it.before === Was.Lost }

        private fun put(was: Was) {
            when (was) {
                Was.Empty, Was.Lost -> if (holding) empty()
                Was.AsIs -> Unit
                is Was.Held -> { restore(was.snap); changed(); live = retain(was.snap) }
            }
        }

        /** Lets go of what the undo log no longer holds, and of the oldest copies past the memory cap. */
        fun prune() {
            val alive = paint.sheetMarks()
            val it = recs.entries.iterator()
            while (it.hasNext()) { val (mark, rec) = it.next(); if (mark !in alive) { drop(rec.before); rec.after?.let(::drop); it.remove() } }
            var held = bytes()
            for (rec in recs.values) {
                if (held <= SNAPSHOT_CAP_BYTES) break
                val b = rec.before
                if (b is Was.Held) { drop(b); rec.before = Was.Lost; held = bytes() }
            }
        }

        private fun bytes(): Long {
            val seen = HashSet<Snap>()
            for (rec in recs.values) { (rec.before as? Was.Held)?.let { seen.add(it.snap) }; (rec.after as? Was.Held)?.let { seen.add(it.snap) } }
            live?.let { seen.add(it) }
            return seen.sumOf { it.bytes }
        }

        private fun retain(s: Snap): Snap { s.refs++; return s }
        private fun release(s: Snap) { if (--s.refs <= 0) MediaTex.deleteTex(*s.tex.values.toIntArray()) }
        private fun drop(was: Was) { if (was is Was.Held) release(was.snap) }

        /** Everything goes ([deleteTextures] false: the context they lived in is gone already). */
        fun clear(deleteTextures: Boolean) {
            if (deleteTextures) {
                val seen = HashSet<Snap>()
                for (rec in recs.values) { (rec.before as? Was.Held)?.let { seen.add(it.snap) }; (rec.after as? Was.Held)?.let { seen.add(it.snap) } }
                (pending as? Was.Held)?.let { seen.add(it.snap) }
                live?.let { seen.add(it) }
                for (s in seen) MediaTex.deleteTex(*s.tex.values.toIntArray())
            }
            recs.clear(); live = null; pending = null
        }
    }

    /** A copy of what the sheet holds now. */
    private fun capture(): Snap? {
        val e = engine ?: return null
        val id = layerId ?: return null
        e.finishWaterJob()
        if (e.inputPending) android.util.Log.w(TAG, "the sheet is copied with brush input the water has not taken in yet")
        val c = content?.let(::clamp)
        val r = if (c == null) intArrayOf(0, 0, 1, 1) else intArrayOf(floor(c[0]).toInt(), floor(c[1]).toInt(), ceil(c[2]).toInt(), ceil(c[3]).toInt())
        val w = max(1, r[2] - r[0]); val h = max(1, r[3] - r[1])
        val stores = if (e.wetActive) listOf("p0", "p1", "w0", "w1") else listOf("p0", "p1")
        val tex = LinkedHashMap<String, Int>()
        try {
            for (s in stores) {
                val src = e.current(s) ?: continue
                val t = MediaTex.make(w, h, filter = GLES30.GL_NEAREST)
                tex[s] = t
                e.blit(src, r[0], r[1], r[0] + w, r[1] + h, t, 0, 0, w, h)
            }
        } catch (x: GraphicsMemoryException) {
            MediaTex.deleteTex(*tex.values.toIntArray())
            throw x
        }
        return Snap(id, originX, originY, cell, grid, intArrayOf(r[0], r[1], r[0] + w, r[1] + h), tex, e.wetRect?.copyOf(),
            w.toLong() * h * 8 * tex.size)
    }

    /** The sheet holds [s] again, where it lay; water that was running may run on. */
    private fun restore(s: Snap) {
        if (engine == null || grid != s.grid) {
            releaseEngine()
            val made = MediaLayerEngine(shaders, atlasMode = false, coarseWater = false)
            try { made.init(s.grid.w, s.grid.h, 0f, 0f, 1f) } catch (x: GraphicsMemoryException) {
                android.util.Log.e(TAG, "no graphics memory to bring the sheet back: ${x.message}")
                runCatching { made.release() }
                holding = false
                return
            }
            made.lookPaintOnly = true
            engine = made; grid = s.grid
        }
        val e = engine!!
        if (e.asleep) e.wake()
        e.stopWater()
        layerId = s.layer; cell = s.cell; originX = s.originX; originY = s.originY
        e.moveTo(originX, originY, (1.0 / cell).toFloat())
        e.worldScale = WetSheetMath.worldScale(cell)
        val w = s.rect[2] - s.rect[0]; val h = s.rect[3] - s.rect[1]
        for ((store, t) in s.tex) for (dst in e.loadTargets(store)) e.blit(t, 0, 0, w, h, dst, s.rect[0], s.rect[1], s.rect[2], s.rect[3])
        s.wetRect?.let { e.resumeWater(it) }
        e.takeDirty(); e.takeDirtyStores()
        content = floatArrayOf(s.rect[0].toFloat(), s.rect[1].toFloat(), s.rect[2].toFloat(), s.rect[3].toFloat())
        lookStale = content; lastWet = e.wetRect; groundStale = true
        holding = true
        pace.reset()
    }

    /** The undo log undid or redid a step carrying a sheet mark ([GlPaintEngine.onSheetHistory]). */
    fun historyMoved(mark: Long, forward: Boolean) = history.moved(mark, forward)

    /** [GlPaintEngine.sheetUndoBlocked]. */
    fun undoBlocked(mark: Long): Boolean = history.blocked(mark)

    // ---- housekeeping ----

    /** Empty and unused for a while: the sheet's textures go (its programs stay; the next stroke wakes it). */
    fun releaseIfIdle(nowMs: Long) {
        val e = engine ?: return
        if (holding || e.asleep) { idleSince = 0L; return }
        if (idleSince == 0L) { idleSince = nowMs; return }
        if (nowMs - idleSince > IDLE_RELEASE_MS) { dropFbos(); e.sleep(); idleSince = 0L }
    }

    /** The paper textures the sheet's engine reads: not to be collected while it does (GlPaintEngine.registerPaperTextureBorrower). */
    fun borrowedPaper(): Set<Int> = engine?.borrowedPaperTextureHandles().orEmpty()

    /** Bytes of graphics memory the sheet's engine holds now, by its own count (bench). */
    fun memoryReport(): String? = engine?.takeIf { !it.asleep }?.memoryReport()

    private fun fboOn(tex: Int, ground: Boolean): Int {
        if (ground) {
            if (groundFboTex != tex) { if (groundFbo != 0) MediaTex.deleteFbo(groundFbo); groundFbo = MediaTex.fbo(tex); groundFboTex = tex }
            return groundFbo
        }
        if (lookFboTex != tex) { if (lookFbo != 0) MediaTex.deleteFbo(lookFbo); lookFbo = MediaTex.fbo(tex); lookFboTex = tex }
        return lookFbo
    }

    private var linearTex = 0
    /** The look is magnified onto the screen and into the tiles: smoothly. */
    private fun linearLook(e: MediaLayerEngine) {
        val t = e.lookTexture()
        if (t == linearTex) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        linearTex = t
    }

    private fun dropFbos() {
        if (lookFbo != 0) MediaTex.deleteFbo(lookFbo)
        if (groundFbo != 0) MediaTex.deleteFbo(groundFbo)
        lookFbo = 0; lookFboTex = 0; groundFbo = 0; groundFboTex = 0; linearTex = 0
    }

    private fun releaseEngine() {
        dropFbos()
        engine?.release()
        engine = null
    }

    /** The canvas closing or another drawing opening: everything goes, unprinted paint too (the caller settled first if it wanted it). */
    fun release() {
        history.clear(deleteTextures = true)
        releaseEngine()
        layerId = null; holding = false; content = null; lookStale = null; lastWet = null
    }

    /** The context these GL names came from is gone: forget them without deleting. */
    fun forget() {
        history.clear(deleteTextures = false)
        engine = null
        lookFbo = 0; lookFboTex = 0; groundFbo = 0; groundFboTex = 0; linearTex = 0
        layerId = null; holding = false; content = null; lookStale = null; lastWet = null
    }

    private fun clamp(r: FloatArray) = floatArrayOf(max(r[0], 0f), max(r[1], 0f), min(r[2], grid.w.toFloat()), min(r[3], grid.h.toFloat()))

    private fun union(a: FloatArray?, b: FloatArray?): FloatArray? = when {
        a == null -> b?.copyOf()
        b == null -> a
        else -> floatArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
    }

    companion object {
        private const val TAG = "JoyBrushSheet"
        /** A stroke that starts closer than this many cells to the sheet's edge is off it. */
        const val EDGE_MARGIN_CELLS = 24
        /** The most graphics memory the copies kept for undo may take; past it the oldest go and undo ends there. */
        const val SNAPSHOT_CAP_BYTES = 128L * 1024 * 1024
        private const val IDLE_RELEASE_MS = 20_000L

        /** The owner's switch (the ⋯ menu) and the bench's `jb_wet_sheet`: watercolour on the wet sheet. */
        @Volatile @JvmStatic var enabled = false

        /** Cells along the sheet's long side: the owner's Water detail switch (Detailed or Fast). */
        @Volatile @JvmStatic var longCells = WetSheetMath.LONG_DETAILED

        /** The owner's dial: how fast running water may travel, 1 = as approved in the lab (WetSheetMath.runCapMmPerS). */
        @Volatile @JvmStatic var flowSpeed = 1.0

        /** Debug bench only (`jb_sheet_print_hold_ms`): a dry sheet waits this long before it is printed (0 in the app). */
        @Volatile @JvmStatic var printHoldMs = 0L

        /** Debug bench only (`jb_sheet_cell`): the cell in document px whatever the zoom. */
        @Volatile @JvmStatic var forcedCell: Int? = null
    }
}
