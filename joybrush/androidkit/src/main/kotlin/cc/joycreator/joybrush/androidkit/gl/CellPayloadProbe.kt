package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.Tiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pixels a board edit MOVES take their realistic-paint state with them (R2-JB-03, second half): a sprite cell swap and an
 * animation board dragged onto part of itself. Before the fix the moved pixels kept their look and lost the paint under
 * them (pigment, water, paper, ground).
 *
 * On a throwaway engine. The oracle is one rule, checked for EVERY pixel of every tile the edit could touch, in every
 * store: the paint state is a field over the plane that moves exactly as the pixels move, where a tile with no paint
 * state has nothing in its stores and its look as its ground. So after the edit, a pixel's stores hold what its source
 * pixel's stores held, its ground is its source pixel's ground (or look), a pixel whose content was moved away and not
 * replaced holds nothing, and a tile that ends with no paint state must be one whose pixels all had none.
 * Then: one undo step, nothing dropped as stale, Undo gives the state before back in every store, Redo the state after.
 *
 * Not covered: a save and reopen, painting on with a real brush, memory running out part way, a mask, linked frames,
 * a paint-state tile with no picture tile under it.
 */
internal object CellPayloadProbe {
    private const val S = Tiles.SIZE
    private val STORES = listOf("p0", "w0")   // one paint plane and the water, plus the ground

    /** What the plane holds, by tile: the look, and each store that is there. */
    private class State(val look: Map<Long, ByteArray>, val media: Map<Pair<String, Long>, ByteArray>) {
        fun hasPayload(key: Long) = media.keys.any { it.second == key }
    }

    fun run(make: () -> GlPaintEngine, layerId: String): JSONObject {
        val report = JSONObject().put("probe", "cellPayload")
        val problems = JSONArray()
        fun problem(s: String) { if (problems.length() < 24) problems.put(s) }
        for ((name, scenario) in listOf<Pair<String, (GlPaintEngine, JSONObject, (String) -> Unit) -> Unit>>(
            "spriteSwap" to { e, r, p -> spriteSwap(e, layerId, r, p) },
            "boardDragOverItself" to { e, r, p -> boardDrag(e, layerId, r, p) })) {
            val part = JSONObject()
            val engine = try { make() } catch (x: Throwable) { problem("$name: no engine: $x"); continue }
            try {
                scenario(engine, part) { problem("$name: $it") }
                val gl = GLES30.glGetError()
                if (gl != GLES30.GL_NO_ERROR) problem("$name: GL error $gl")
            } catch (x: Throwable) {
                problem("$name: $x")
                while (GLES30.glGetError() != GLES30.GL_NO_ERROR) Unit
            } finally {
                try { engine.release() } catch (x: Throwable) { problem("$name: release: $x") }
            }
            report.put(name, part)
        }
        return report.put("problems", problems).put("passed", problems.length() == 0)
    }

    // ---- scenario 1: two sprite cells swapped; the cells straddle a tile edge, and only the left tile holds paint state ----

    private fun spriteSwap(engine: GlPaintEngine, layerId: String, report: JSONObject, problem: (String) -> Unit) {
        var serial = 0
        fun fresh() = "cell-probe-${++serial}"
        val shared = "cell-probe-shared"
        val base = JbDocument(id = "cell-probe-doc", name = "Disposable cell payload probe",
            boards = listOf(Board("cell-probe-root", "Root", BoardKind.CANVAS, RectPx(-512, -512, 1536, 1536))),
            layers = listOf(Layer(layerId, "Probe", LayerKind.PAINT, cels = listOf(Cel(shared)), sharedCelId = shared)), activeLayerId = layerId)
        engine.setBoardDocument(base)
        val keys = listOf(Tiles.key(0, 0), Tiles.key(1, 0))
        for (key in keys) engine.writeCelTile(layerId, shared, key, lookBytes(key))
        // Paint state on the left tile only.
        writePayload(engine, layerId, keys[0], null)
        engine.agreeAllPayloads()
        // Cell 0 is x 64..224 (left tile); cell 1 is x 224..384 (32 px of the left tile, then the right one).
        val rect = RectPx(64, 32, 320, 160)
        engine.applyBoardChange(BoardDocumentOps.createSprite(checkNotNull(engine.boardDocument), "Cells", rect, SpriteGrid(2, 1, 160, 160), ::fresh))
        var doc = checkNotNull(engine.boardDocument)
        val board = doc.boards.single { it.kind == BoardKind.SPRITE }
        doc = BoardDocumentOps.setLocked(doc, board.id, true)
        engine.setBoardDocument(doc)
        val cel = doc.layers.single().sharedCelId ?: shared
        val before = read(engine, layerId, cel, null, keys)
        report.put("payloadTilesBefore", before.media.size)
        if (!before.hasPayload(keys[0]) || before.hasPayload(keys[1])) problem("setup: paint state is not on the left tile alone")
        val a = RectPx(64, 32, 160, 160); val b = RectPx(224, 32, 160, 160)
        // Where each pixel's content comes from: the two cells trade places, everything else stays.
        val source: (Int, Int) -> Pair<Int, Int>? = { x, y ->
            when {
                inside(a, x, y) -> (x + 160) to y
                inside(b, x, y) -> (x - 160) to y
                else -> x to y
            }
        }
        edit(engine, layerId, cel, null, keys, before, source, report, problem) {
            engine.applyBoardChange(SpriteCellOps.swap(checkNotNull(engine.boardDocument), board.id, 0, 1))
        }
    }

    // ---- scenario 2: an animation board dragged right and down onto part of itself; its frame holds paint state ----

    private fun boardDrag(engine: GlPaintEngine, layerId: String, report: JSONObject, problem: (String) -> Unit) {
        var serial = 0
        fun fresh() = "drag-probe-${++serial}"
        val base = JbDocument(id = "drag-probe-doc", name = "Disposable board drag probe",
            boards = listOf(Board("drag-probe-root", "Root", BoardKind.CANVAS, RectPx(-512, -512, 1536, 1536))),
            layers = listOf(Layer(layerId, "Probe", LayerKind.PAINT, cels = listOf(Cel("drag-probe-shared")))), activeLayerId = layerId)
        engine.setBoardDocument(base)
        val old = RectPx(40, 24, 200, 168)
        engine.applyBoardChange(RegionDocumentOps.create(base, "Drag", old, ::fresh))
        val doc = checkNotNull(engine.boardDocument)
        val board = doc.boards.single { it.kind == BoardKind.ANIMATION }
        val cel = doc.layers.single().regions.single().frameCel.getValue(board.currentFrameId!!)
        val keys = listOf(Tiles.key(0, 0), Tiles.key(1, 0), Tiles.key(0, 1), Tiles.key(1, 1))
        // The frame's picture and paint state: only the first tile (the board lies inside it).
        engine.writeCelTile(layerId, cel, keys[0], lookBytes(keys[0], only = old))
        writePayload(engine, layerId, keys[0], cel)
        engine.agreeAllPayloads()
        val before = read(engine, layerId, cel, cel, keys)
        report.put("payloadTilesBefore", before.media.size)
        // 120 right and 140 down: the new place overlaps the old one and crosses into all four tiles.
        val dx = 120; val dy = 140
        val new = RectPx(old.x + dx, old.y + dy, old.w, old.h)
        val source: (Int, Int) -> Pair<Int, Int>? = { x, y ->
            when {
                inside(new, x, y) -> (x - dx) to (y - dy)
                inside(old, x, y) -> null            // moved away and not replaced
                else -> x to y
            }
        }
        edit(engine, layerId, cel, cel, keys, before, source, report, problem) {
            engine.applyBoardChange(AnimationBoardOps.moveAll(checkNotNull(engine.boardDocument), board.id, new.x, new.y))
        }
    }

    // ---- the edit, its oracle, undo and redo ----

    private fun edit(engine: GlPaintEngine, layerId: String, cel: String, plane: String?, keys: List<Long>, before: State,
                     source: (Int, Int) -> Pair<Int, Int>?, report: JSONObject, problem: (String) -> Unit, change: () -> Unit) {
        val depth = engine.undo.undoDepth
        change()
        report.put("undoSteps", engine.undo.undoDepth - depth)
        if (engine.undo.undoDepth != depth + 1) problem("the edit was ${engine.undo.undoDepth - depth} undo steps")
        val after = read(engine, layerId, cel, plane, keys)
        report.put("payloadTilesAfter", after.media.size)
        var wrong = 0L; var checked = 0L
        for (key in keys) {
            val ox = Tiles.tx(key) * S; val oy = Tiles.ty(key) * S
            val has = after.hasPayload(key)
            for (y in 0 until S) for (x in 0 until S) {
                val from = source(ox + x, oy + y)
                val at = y * S + x
                // The picture moved as the oracle says (the engine's own tests cover this; it anchors the rest).
                if (!same(after.look[key], at * 4, 4, from?.let { lookAt(before, it.first, it.second) })) wrong++
                for (store in STORES) {
                    val want = from?.let { storeAt(before, store, it.first, it.second) }
                    if (!same(after.media[store to key], at * MediaStores.BYTES_PER_TEXEL, MediaStores.BYTES_PER_TEXEL, want)) wrong++
                }
                // The ground: the source's ground, or its look where it had no paint state. A tile left with no paint
                // state has its own look as its ground, which must then be exactly that.
                val wantGround = from?.let { groundAt(before, it.first, it.second) }
                val gotGround = if (has) after.media[MediaStores.GROUND to key] else after.look[key]
                if (!same(gotGround, at * 4, 4, wantGround)) wrong++
                checked++
            }
        }
        report.put("pixelsChecked", checked).put("wrong", wrong)
        if (wrong != 0L) problem("$wrong of ${checked * (2 + STORES.size)} values are not where the pixels went")
        val moved = after.media.keys.any { (store, key) -> store in STORES && after.media.getValue(store to key).any { it != 0.toByte() } &&
            before.media[store to key]?.contentEquals(after.media.getValue(store to key)) != true }
        if (!moved) problem("no paint state moved at all")
        val stale = engine.dropStalePayload(layerId, keys, plane)
        report.put("droppedAsStale", stale.size)
        if (stale.isNotEmpty()) problem("${stale.size} tiles were dropped as not agreeing with their look")

        check(engine.undoStep()) { "undo refused" }
        val undone = read(engine, layerId, cel, plane, keys)
        if (!sameState(before, undone)) problem("undo is not the state before (${undone.media.size} payload tiles, were ${before.media.size})")
        check(engine.redoStep()) { "redo refused" }
        val redone = read(engine, layerId, cel, plane, keys)
        if (!sameState(after, redone)) problem("redo is not the state after (${redone.media.size} payload tiles, were ${after.media.size})")
        report.put("undoRedo", sameState(before, undone) && sameState(after, redone))
    }

    private fun read(engine: GlPaintEngine, layerId: String, cel: String, plane: String?, keys: List<Long>): State {
        val look = LinkedHashMap<Long, ByteArray>(); val media = LinkedHashMap<Pair<String, Long>, ByteArray>()
        for (key in keys) {
            engine.readCelTile(layerId, cel, key)?.let { look[key] = it.copyOf() }
            for (store in STORES + MediaStores.GROUND) engine.readMediaTile(layerId, store, key, plane)?.let { media[store to key] = it.copyOf() }
        }
        return State(look, media)
    }

    private fun sameState(a: State, b: State): Boolean {
        fun <K> eq(x: Map<K, ByteArray>, y: Map<K, ByteArray>, blankIsAbsent: Boolean): Boolean = (x.keys + y.keys).all { k ->
            val p = x[k]; val q = y[k]
            if (p != null && q != null) p.contentEquals(q)
            else blankIsAbsent && (p ?: q)!!.all { it == 0.toByte() }
        }
        // A picture tile that is all clear is the same picture as no tile; a paint-state tile must be there or not.
        return eq(a.look, b.look, true) && eq(a.media, b.media, false)
    }

    private fun tileOf(x: Int, y: Int) = Tiles.key(Math.floorDiv(x, S), Math.floorDiv(y, S))
    private fun at(x: Int, y: Int) = Math.floorMod(y, S) * S + Math.floorMod(x, S)
    private fun lookAt(s: State, x: Int, y: Int): ByteArray? = s.look[tileOf(x, y)]?.copyOfRange(at(x, y) * 4, at(x, y) * 4 + 4)
    private fun storeAt(s: State, store: String, x: Int, y: Int): ByteArray? {
        val n = MediaStores.BYTES_PER_TEXEL
        return s.media[store to tileOf(x, y)]?.copyOfRange(at(x, y) * n, at(x, y) * n + n)
    }
    private fun groundAt(s: State, x: Int, y: Int): ByteArray? =
        if (s.hasPayload(tileOf(x, y))) s.media[MediaStores.GROUND to tileOf(x, y)]?.copyOfRange(at(x, y) * 4, at(x, y) * 4 + 4) else lookAt(s, x, y)

    /** [n] bytes of [tile] at [offset] are [want]; a tile or a want that is not there is all zero. */
    private fun same(tile: ByteArray?, offset: Int, n: Int, want: ByteArray?): Boolean {
        for (i in 0 until n) if ((tile?.get(offset + i) ?: 0.toByte()) != (want?.get(i) ?: 0.toByte())) return false
        return true
    }

    private fun inside(r: RectPx, x: Int, y: Int) = x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h

    /** An opaque picture in which every pixel says where it is; with [only], clear outside that rectangle. */
    private fun lookBytes(key: Long, only: RectPx? = null): ByteArray {
        val ox = Tiles.tx(key) * S; val oy = Tiles.ty(key) * S
        val out = ByteArray(S * S * 4)
        for (y in 0 until S) for (x in 0 until S) {
            if (only != null && !inside(only, ox + x, oy + y)) continue
            val i = (y * S + x) * 4
            out[i] = ((ox + x) and 0xFF).toByte(); out[i + 1] = ((oy + y) and 0xFF).toByte()
            out[i + 2] = (((ox + x) shr 8) * 64 + ((oy + y) shr 8) * 16 + 7).toByte(); out[i + 3] = 0xFF.toByte()
        }
        return out
    }

    /** Stores in which every texel says where it is (half floats between 0.5 and 1), and a ground that is not the look. */
    private fun writePayload(engine: GlPaintEngine, layerId: String, key: Long, plane: String?) {
        for ((s, store) in STORES.withIndex()) {
            val bytes = ByteArray(MediaStores.tileBytes(S, store))
            for (y in 0 until S) for (x in 0 until S) for (c in 0 until 4) {
                val i = ((y * S + x) * 4 + c) * 2
                bytes[i] = ((x * 3 + y * 5 + c * 17 + s * 29) and 0xFF).toByte()
                bytes[i + 1] = (0x38 + ((x + y + c + s) and 3)).toByte()
            }
            engine.writeMediaTile(layerId, store, key, bytes, plane = plane)
        }
        val ground = ByteArray(S * S * 4)
        for (y in 0 until S) for (x in 0 until S) {
            val i = (y * S + x) * 4
            ground[i] = ((x * 7 + 3) and 0xFF).toByte(); ground[i + 1] = ((y * 11 + 5) and 0xFF).toByte(); ground[i + 2] = ((x + y) and 0xFF).toByte(); ground[i + 3] = 0xFF.toByte()
        }
        engine.writeMediaTile(layerId, MediaStores.GROUND, key, ground, plane = plane)
    }
}
