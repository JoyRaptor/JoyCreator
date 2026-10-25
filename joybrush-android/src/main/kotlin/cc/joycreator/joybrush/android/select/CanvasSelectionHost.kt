package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.core.select.SelectionColour
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.lines.LineEditResult
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.androidkit.lines.LineSnapshot
import cc.joycreator.joybrush.androidkit.select.LiftedLines
import cc.joycreator.joybrush.androidkit.select.LiftedPixels
import cc.joycreator.joybrush.androidkit.select.SelectionHost
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushRegistry
import cc.joycreator.joybrush.core.doc.RegionDocumentOps
import cc.joycreator.joybrush.core.doc.RegionPaintPlan
import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.select.SelectionMask
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkEditSession
import cc.joycreator.joybrush.core.vector.InkLine
import cc.joycreator.joybrush.core.vector.InkReplay

/**
 * The few canvas calls the Select host makes, so a test can stand in for the GL canvas. Everything here is the UI thread.
 * [JbHostCanvas] is the real one.
 */
interface HostCanvas {
    /** The engine's lines as of its last change. A new object whenever they changed. */
    val lineSnapshot: LineSnapshot
    fun activeLinePlanes(): List<LinePlane>
    fun linePlaneAt(docX: Float, docY: Float): LinePlane? = activeLinePlanes().firstOrNull()
    fun visibleLinePlanes(): List<LinePlane>
    /** Runs [edit] as ONE undo step on the GL thread; [done] comes back on the UI thread (it may also come back at once). */
    fun submitLineEdit(edit: LineEdit, done: (LineEditResult) -> Unit)
    fun redraw()
    /** Live drag preview (v1.1): lift, move through a homography, re-draw, put back. Defaults = no preview. */
    fun beginLineLift(ids: Set<LineAddress>): Boolean = false
    fun setLineLiftTransform(rowMajor: DoubleArray?) {}
    fun previewLineLift(records: List<AddressedStroke>) {}
    fun endLineLift() {}
    /** Moves when another drawing is opened: edits still in flight were about the old one (v1.2 H10). */
    val lineEditEpoch: Int get() = 0
}

class JbHostCanvas(private val canvas: JbCanvasView) : HostCanvas {
    override val lineSnapshot: LineSnapshot get() = canvas.lineSnapshot
    override val lineEditEpoch: Int get() = canvas.lineEditEpoch

    // Not memoised here on purpose: the canvas owns what "the current planes" are (frames, layers, visibility), and a copy
    // kept on this side would go stale the day it changes how it works them out.
    override fun activeLinePlanes(): List<LinePlane> = canvas.activeLinePlanes()
    override fun linePlaneAt(docX: Float, docY: Float): LinePlane? {
        if (!docX.isFinite() || !docY.isFinite()) return null
        val allowed = activeLinePlanes()
        val doc = canvas.boardDocument ?: return allowed.firstOrNull()
        val plan = try { RegionDocumentOps.paintPlans(doc, emptyMap())[canvas.activeLayerId] }
            catch (_: IllegalArgumentException) { return null }
        return pastePlaneAt(plan ?: return null, allowed, docX, docY)
    }
    override fun visibleLinePlanes(): List<LinePlane> = canvas.visibleLinePlanes()
    override fun submitLineEdit(edit: LineEdit, done: (LineEditResult) -> Unit) = canvas.submitLineEdit(edit, done)
    override fun redraw() = canvas.invalidate()
    override fun beginLineLift(ids: Set<LineAddress>): Boolean = canvas.beginLineLift(ids)
    override fun setLineLiftTransform(rowMajor: DoubleArray?) = canvas.setLineLiftTransform(rowMajor)
    override fun previewLineLift(records: List<AddressedStroke>) = canvas.previewLineLift(records)
    override fun endLineLift() = canvas.endLineLift()
}

/** Resolve against the canvas's allowed planes as well as the region plan, so a stale/locked cel is never a target. */
internal fun pastePlaneAt(plan: RegionPaintPlan, allowed: List<LinePlane>, x: Float, y: Float): LinePlane? {
    if (!x.isFinite() || !y.isFinite()) return null
    val cel = plan.planeAt(kotlin.math.floor(x.toDouble()).toLong(), kotlin.math.floor(y.toDouble()).toLong()).celId
    return allowed.firstOrNull { it.celId == cel }
}

/** What the host needs of the screen around it. */
interface HostUi {
    /** A note, with an optional one-shot action ("Undo"). */
    fun say(text: String, action: String? = null, onAction: (() -> Unit)? = null)
    /** The S Pen button was tapped at a screen point: R42's tap action (the eyedropper by default). */
    fun penButtonTap(x: Float, y: Float)
    /** The Select overlays (box, glow, ants) redraw. */
    fun overlaysChanged()
    /**
     * An edit the host accepted did not happen. The host has already said why in words ([message]); the Select tool is
     * still showing the move, so the screen re-reads it (see [SelectionSync.afterRefusedEdit]).
     */
    fun editRefused(message: String)
    fun log(note: String)
}

/**
 * JB-5.20e / NATIVE_LINES_V1 — the Select tool's host, backed by the canvas's editable lines.
 *
 * READING. [activeLines] and [visibleLines] are the canvas's [LineSnapshot] turned into [SelectionHost.Line]s: the record,
 * and the picker's view of its geometry, which is `InkEditSession.lines` run on that one record with the drawing's frozen
 * brush (the same replay the picker has always used; not copied). A record the replay refuses is skipped and logged once.
 * Both lists are memoised on (snapshot, edits in flight, planes) and each geometry on (record value, brush), so the tool's
 * per-hover and per-touch calls are cheap.
 *
 * WRITING. [SelectionHost] answers synchronously and the engine edits on the GL thread, so an edit is checked here first
 * (every address, every brush, every line must replay), submitted, and then SHOWN AT ONCE: until the engine's answer
 * arrives the lists carry the edit on top of the snapshot. The Select tool re-reads the lines right after each edit (to
 * fit its box round a bent line, to select a duplicate), so without this it would read the old geometry and a second
 * move would start from the line as it was before the first. When the answer comes the edit drops off, and the snapshot
 * already holds it. If the engine refused, [HostUi.editRefused] hears the reason and the screen puts the box back.
 *
 * LIVE DRAG (v1.1): [liftLines] lifts the lines out of their plane's tiles and the engine draws them through the box's
 * homography until [LiftedLines.commit] (one undo step, then lifted again at identity) or [LiftedLines.release].
 *
 * Paint lift and dimming are not backed here; [paintClip] is kept but nothing paints through it. Line colour adjustment
 * previews through the active lift and commits one replacement edit on release.
 */
class CanvasSelectionHost(
    private val canvas: HostCanvas,
    private val ui: HostUi,
    private val newBrushId: () -> String = { randomId("lb") },
) : SelectionHost {
    override val selectionEpoch: Int get() = canvas.lineEditEpoch

    // ── reading ──

    override fun activeLines(): List<SelectionHost.Line> { dropPendingIfReloaded(); return active.get(canvas.activeLinePlanes()) }

    override fun visibleLines(): List<SelectionHost.Line> { dropPendingIfReloaded(); return visible.get(canvas.visibleLinePlanes()) }

    private inner class Memo {
        private var snapshot: LineSnapshot? = null
        private var version = -1
        private var planes: List<LinePlane> = emptyList()
        private var lines: List<SelectionHost.Line> = emptyList()
        var keys: Set<GeometryKey> = emptySet()
            private set

        fun get(wanted: List<LinePlane>): List<SelectionHost.Line> {
            val now = canvas.lineSnapshot
            if (now === snapshot && version == editsVersion && wanted == planes) return lines
            val out = ArrayList<SelectionHost.Line>()
            val used = HashSet<GeometryKey>()
            for (plane in wanted.distinct()) for (record in effective(plane)) {
                val key = GeometryKey(record, brushFor(record.brushId))
                used += key   // a refusal is kept too, so it is neither replayed nor logged again
                val ink = geometryFor(key) ?: continue
                out += SelectionHost.Line(plane.address(record.id), ink, record, record.locked, record.groupId)
            }
            snapshot = now; version = editsVersion; planes = wanted.toList(); lines = out; keys = used
            prune()
            return out
        }
    }

    private val active = Memo()
    private val visible = Memo()

    /** A record and the brush it replays with: what a line's geometry is a function of. */
    private class GeometryKey(val record: StrokeRecord, val brush: BrushPreset?) {
        override fun equals(other: Any?): Boolean = other is GeometryKey && other.record == record && other.brush == brush
        override fun hashCode(): Int = 31 * record.hashCode() + (brush?.hashCode() ?: 0)
    }

    /** The replayed line, or null when the replay refuses (cached, so a refusal is logged once). */
    private class Geometry(val line: InkLine?)

    private val geometry = HashMap<GeometryKey, Geometry>()
    private var activeLift: HostLift? = null
    private var colourBase: List<AddressedStroke>? = null

    private fun geometryFor(key: GeometryKey): InkLine? =
        (geometry[key] ?: Geometry(replay(key.record, key.brush)).also { geometry[key] = it }).line

    private fun prune() {
        val keep = active.keys + visible.keys
        geometry.keys.retainAll(keep)
    }

    /** `InkEditSession.lines` on this one record: the picker's own geometry. Null = it is not a line (logged). */
    private fun replay(record: StrokeRecord, brush: BrushPreset?): InkLine? {
        val refusal = InkReplay.refusal(record, brush)
        if (refusal != null) {
            ui.log("Select skips line ${record.id}: $refusal")
            return null
        }
        return try {
            InkEditSession(listOf(record), record.id).lines { brush }.singleOrNull()
                ?: run { ui.log("Select skips line ${record.id}: it draws no dabs"); null }
        } catch (e: Exception) {
            ui.log("Select skips line ${record.id}: ${e.message}")
            null
        }
    }

    // ── what is there now: the snapshot with the edits still in flight on top ──

    private class Pending(val edit: LineEdit, val epoch: Int)

    private val pending = ArrayList<Pending>()
    private var editsVersion = 0

    private fun planeOf(a: LineAddress) = LinePlane(a.layerId, a.celId)

    override fun activeLinePlane(): LinePlane? = canvas.activeLinePlanes().firstOrNull()
    override fun linePlaneAt(docX: Float, docY: Float): LinePlane? = canvas.linePlaneAt(docX, docY)

    /** The drawing the in-flight edits were made against; a new one (an open) drops them (v1.2 H10). */
    private var pendingEpoch = canvas.lineEditEpoch

    private fun dropPendingIfReloaded() {
        if (pendingEpoch == canvas.lineEditEpoch) return
        pendingEpoch = canvas.lineEditEpoch
        if (pending.isNotEmpty()) { pending.clear(); editsVersion++ }
    }

    private fun effective(plane: LinePlane): List<StrokeRecord> {
        dropPendingIfReloaded()
        val base = canvas.lineSnapshot.lines(plane)
        if (pending.isEmpty()) return base
        val list = base.toMutableList()
        for (p in pending) when (val edit = p.edit) {
            is LineEdit.Replace -> for (a in edit.after) if (planeOf(a.address) == plane) {
                val i = list.indexOfFirst { it.id == a.address.recordId }
                if (i >= 0) list[i] = a.record.copy(seq = list[i].seq)
            }
            // The engine puts a new line on top: one past the highest seq in its plane.
            is LineEdit.Add -> for (a in edit.records) if (planeOf(a.address) == plane && list.none { it.id == a.address.recordId }) {
                list += a.record.copy(seq = (list.maxOfOrNull { it.seq } ?: 0L) + 1L)
            }
            // A baked line leaves the lines (it stays as pixels), exactly like a delete as far as Select can see.
            is LineEdit.Delete, is LineEdit.Bake -> {
                val ids = if (edit is LineEdit.Delete) edit.ids else (edit as LineEdit.Bake).ids
                val gone = ids.filter { planeOf(it) == plane }.map { it.recordId }.toSet()
                if (gone.isNotEmpty()) list.removeAll { it.id in gone }
            }
        }
        return list
    }

    private fun find(a: LineAddress): StrokeRecord? = effective(planeOf(a)).firstOrNull { it.id == a.recordId }

    private fun brushFor(id: String): BrushPreset? = canvas.lineSnapshot.brush(id) ?: staged[id]

    override fun brushPreset(brushId: String): BrushPreset? = brushFor(brushId)

    override fun importBrush(preset: BrushPreset): String? = try {
        freezeBrush(preset)
    } catch (e: Exception) {
        ui.say("That line's brush can't be brought into this drawing.")
        null
    }

    // ── brushes a re-brush introduces ──

    /** Frozen copies made for a re-brush that the engine's snapshot does not hold yet, by id. */
    private val staged = LinkedHashMap<String, BrushPreset>()

    /**
     * Freezes the library brush [preset] into the drawing (an identical copy already there is reused) and returns the id a
     * record names it by. The copy rides along on whichever edit first uses it. Throws `BrushException` for a brush that
     * cannot be frozen.
     */
    fun freezeBrush(preset: BrushPreset): String {
        pruneStaged()
        val known = canvas.lineSnapshot.brushes + staged
        val result = FrozenBrushRegistry.freeze(known, preset, newBrushId)
        for ((id, p) in result.newEntries) staged[id] = p
        return result.id
    }

    /** The brush a record names, for the row's brush icon. */
    fun brushOf(brushId: String): BrushPreset? = brushFor(brushId)

    private fun pruneStaged() { staged.keys.removeAll(canvas.lineSnapshot.brushes.keys) }

    /** The staged copies these records name that the snapshot lacks: they travel with the edit. */
    private fun brushesFor(records: List<StrokeRecord>): Map<String, BrushPreset> {
        val held = canvas.lineSnapshot.brushes
        val out = LinkedHashMap<String, BrushPreset>()
        for (r in records) if (r.brushId !in held) staged[r.brushId]?.let { out[r.brushId] = it }
        return out
    }

    // ── writing ──

    /** A fresh line name (Duplicate). */
    fun newLineId(): String = randomId("ln")

    override fun replaceLines(after: List<AddressedStroke>): Boolean {
        if (after.isEmpty()) return false
        if (after.map { it.address }.toSet().size != after.size) return refuse("Those lines can't be changed together.")
        if (after.any { find(it.address) == null }) return refuse(GONE)
        checkDrawable(after)?.let { return refuse(it) }
        return submit(LineEdit.Replace(after, brushesFor(after.map { it.record })))
    }

    override fun addLines(records: List<AddressedStroke>): Boolean {
        if (records.isEmpty()) return false
        if (records.map { it.address }.toSet().size != records.size) return refuse("Those lines can't be added together.")
        val planes = canvas.activeLinePlanes() + canvas.visibleLinePlanes()
        if (records.any { planeOf(it.address) !in planes }) return refuse("That layer can't take lines right now.")
        if (records.any { find(it.address) != null }) return refuse("A line with that name is already there.")
        checkDrawable(records)?.let { return refuse(it) }
        return submit(LineEdit.Add(records, brushesFor(records.map { it.record })))
    }

    override fun deleteLines(ids: Set<LineAddress>): Boolean {
        if (ids.isEmpty()) return false
        if (ids.any { find(it) == null }) return refuse(GONE)
        return submit(LineEdit.Delete(ids.toSet()))
    }

    /** A sentence when one of [records] would not draw (no brush, or a replay that refuses); null = all do. */
    private fun checkDrawable(records: List<AddressedStroke>): String? {
        for (a in records) {
            val record = a.record
            if (record.capturedPhysicalSource != null) return "Lines placed from a capture can't be edited yet."
            val brush = brushFor(record.brushId) ?: return "This line's brush is missing from the drawing."
            if (geometryFor(GeometryKey(record, brush)) == null) return "That change would leave a line its brush can't draw."
        }
        return null
    }

    private fun refuse(why: String): Boolean {
        ui.say(why)
        return false
    }

    /** Sends [edit] and shows it at once. False only when the canvas answered before returning, with no. */
    private fun submit(edit: LineEdit): Boolean {
        dropPendingIfReloaded()
        val entry = Pending(edit, canvas.lineEditEpoch)
        pending += entry
        editsVersion++
        var returned = false
        var early: LineEditResult? = null
        canvas.submitLineEdit(edit) { result ->
            if (returned) {
                answered(entry, result)
            } else {
                early = result
            }
        }
        returned = true
        val result = early ?: return true
        if (entry.epoch != canvas.lineEditEpoch) { dropPendingIfReloaded(); return false }
        pending.remove(entry)
        editsVersion++
        if (result.ok) return true
        return refuse(result.message ?: DID_NOT_HAPPEN)
    }

    /** The engine's answer, on the UI thread: the edit drops off the lists (the snapshot holds it now, if it happened). */
    private fun answered(entry: Pending, result: LineEditResult) {
        dropPendingIfReloaded()
        if (entry.epoch != canvas.lineEditEpoch) return
        pending.remove(entry)
        editsVersion++
        pruneStaged()
        if (!result.ok) {
            val why = result.message ?: DID_NOT_HAPPEN
            ui.say(why)
            ui.editRefused(why)
        }
        canvas.redraw()
        ui.overlaysChanged()
    }

    /** True while an edit is on its way to the engine. */
    val editing: Boolean get() { dropPendingIfReloaded(); return pending.isNotEmpty() }

    // ── live drag (v1.1) ──

    override fun liftLines(ids: Set<LineAddress>): LiftedLines? {
        if (ids.isEmpty() || ids.any { find(it) == null }) return null
        if (!canvas.beginLineLift(ids)) return null
        return HostLift(ids.toSet()).also { activeLift = it; colourBase = null }
    }

    private inner class HostLift(var ids: Set<LineAddress>) : LiftedLines {
        private var live = true
        override fun setTransform(h: Homography) { if (live) canvas.setLineLiftTransform(h.m) }
        override fun preview(records: List<AddressedStroke>) {
            if (!live) return
            if (records.any { it.address !in ids }) { ui.say("Only the chosen lines can be previewed."); return }
            canvas.previewLineLift(records)
        }
        override fun commit(after: List<AddressedStroke>): Boolean {
            if (!live) return false
            // Put the lines back, apply the edit as ONE step, then lift the result at identity (all queued in order on GL).
            canvas.endLineLift()
            val ok = replaceLines(after)
            ids = after.map { it.address }.toSet()
            if (!canvas.beginLineLift(ids)) { live = false; activeLift = null }
            return ok
        }
        override fun release() {
            if (!live) return
            live = false
            canvas.endLineLift()
            if (activeLift === this) activeLift = null
            colourBase = null
        }
    }

    override fun bakeLines(ids: Set<LineAddress>): Boolean {
        if (ids.isEmpty()) return false
        if (ids.any { find(it) == null }) return refuse(GONE)
        return submit(LineEdit.Bake(ids.toSet()))
    }

    override fun liftPixels(mask: SelectionMask, keepSource: Boolean): LiftedPixels? = null

    /** Kept for the tool's sake; no brush stroke paints through it in v1. */
    override var paintClip: SelectionMask? = null

    override fun dimRest(alpha: Float) {}

    override val canAdjustColour: Boolean get() = activeLift != null

    override fun adjustColour(hueDeg: Float, saturation: Float, brightness: Float, done: Boolean) {
        val lift = activeLift ?: return
        val before = colourBase ?: lift.ids.mapNotNull { address ->
            find(address)?.let { AddressedStroke(address, it) }
        }.also { colourBase = it }
        if (before.isEmpty()) { colourBase = null; return }
        val after = before.map { entry ->
            entry.copy(record = entry.record.copy(colorArgb = SelectionColour.shift(entry.record.colorArgb, hueDeg, saturation, brightness)))
        }
        lift.preview(after)
        canvas.redraw()
        if (done) {
            colourBase = null
            if (after != before) lift.commit(after) else lift.preview(before)
        }
    }


    override fun penButtonTap(screenX: Float, screenY: Float) = ui.penButtonTap(screenX, screenY)

    override fun toast(text: String, action: String?, onAction: (() -> Unit)?) = ui.say(text, action, onAction)

    override fun invalidate() {
        canvas.redraw()
        ui.overlaysChanged()
    }

    companion object {
        const val GONE = "Those lines changed. Tap again to choose."
        const val DID_NOT_HAPPEN = "Those lines could not be changed."

        fun randomId(prefix: String): String =
            prefix + "-" + java.lang.Long.toHexString(java.util.UUID.randomUUID().mostSignificantBits and 0xFFFFFFFFFFFFL)
    }
}
