package cc.joycreator.joybrush.android

import android.app.Activity
import android.os.Handler
import android.os.Looper
import cc.joycreator.joybrush.android.library.DrawingLibrary
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.chrome.ToolMemory
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.NewFrame
import cc.joycreator.joybrush.core.doc.RegionDocumentOps
import cc.joycreator.joybrush.core.layers.LayerState
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** What the open JoyBrush screen lends Joybot's hands. The screen implements it; every member runs on the UI thread. */
interface JoybotEnv {
    val canvas: JbCanvasView
    fun ready(): Boolean
    fun brushes(): List<BrushPreset>
    fun tools(): ToolMemory
    fun setTools(next: ToolMemory)
    fun colourChanged()
    fun selectedBoardId(): String?
    fun drawingName(): String?
}

/** Joybot said something the person's screen could not do; [message] is the sentence to say back. */
class HandsException(message: String) : Exception(message)

/**
 * Joybot's hands in JoyBrush (the app-wide action family "joybrush" calls these). Everything goes through the same canvas calls the
 * buttons use, so a layer made here is a layer made by hand: refusals are the screen's own sentences, undo is the screen's undo.
 * One request is one Undo press ([JbCanvasView.beginUndoGroup]). The commands are plain words, parsed here so the model writes
 * "hide Sketch" rather than a structure. Callable from any thread.
 */
object JoybotHands {
    @Volatile private var env: JoybotEnv? = null
    private val ui = Handler(Looper.getMainLooper())

    @JvmStatic fun attach(e: JoybotEnv) { env = e }
    @JvmStatic fun detach(e: JoybotEnv) { if (env === e) env = null }
    @JvmStatic fun isOpen(): Boolean = env != null

    /** The drawings in the library (any time; no screen needed). */
    @JvmStatic
    fun drawings(app: android.content.Context): String {
        val all = DrawingLibrary.list(app)
        if (all.isEmpty()) return "No drawings yet."
        val open = env?.drawingName()
        val sb = StringBuilder("${all.size} drawing${if (all.size == 1) "" else "s"}, newest first:\n")
        for (d in all) sb.append(if (d.name == open) "★ " else "• ").append(d.name).append('\n')
        sb.append(if (open != null) "★ = open now." else "None is open (open_room joybrush).")
        return sb.toString()
    }

    @JvmStatic @Throws(HandsException::class) fun describe(): String = onUi { e -> describe(e) }

    /** Layer commands, one per line. [token] is the request: its changes are one Undo. */
    @JvmStatic @Throws(HandsException::class) fun layers(lines: List<String>, token: String?): String = onUi(token) { e -> layerLines(e, lines) }

    @JvmStatic @Throws(HandsException::class) fun frames(lines: List<String>, token: String?): String = onUi(token) { e -> frameLines(e, lines) }

    @JvmStatic @Throws(HandsException::class)
    fun brush(brush: String?, argb: Int?, size: Double?, opacityPct: Double?, token: String?): String =
        onUi(token, group = false) { e -> setBrush(e, brush, argb, size, opacityPct) }

    /** Shapes, one per line: "line 10,10 90,90", "rect x,y w,h", "ellipse cx,cy rx,ry", "polyline x,y x,y …", "polygon …". Percent of the page. */
    @JvmStatic @Throws(HandsException::class)
    fun draw(shapes: List<String>, layer: String?, token: String?): String = onUi(token) { e -> drawShapes(e, shapes, layer) }

    /** True when a line of [lines] deletes something (the person's yes is owed first). */
    @JvmStatic
    fun deletes(lines: List<String>): List<String> = lines.map { it.trim() }.filter { it.lowercase(Locale.US).startsWith("delete") }

    // ------------------------------------------------------------------ running on the screen

    /** What the screen refused (its own sentences) while this request ran; UI thread. */
    private val refusals = ArrayList<String>()

    private fun <T> onUi(token: String? = null, group: Boolean = true, block: (JoybotEnv) -> T): T {
        val result = AtomicReference<Any?>()
        val latch = CountDownLatch(1)
        var canvas: JbCanvasView? = null
        var oldRefused: ((String) -> Unit)? = null
        val run = Runnable {
            try {
                val e = env ?: throw HandsException("JoyBrush isn't open. Open a drawing first (open_room joybrush).")
                if (!e.ready()) throw HandsException("The drawing is still opening; try again in a moment.")
                val c = e.canvas
                canvas = c; oldRefused = c.onRefused
                refusals.clear()
                c.onRefused = { why -> refusals.add(why) }
                if (group) c.beginUndoGroup()
                try { result.set(Ok(block(e))) } finally { if (group) c.endUndoGroup(token) }
            } catch (t: Throwable) {
                result.set(Failed(t))
            } finally {
                latch.countDown()
            }
        }
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        if (onMain) run.run() else ui.post(run)
        if (!latch.await(20, TimeUnit.SECONDS)) throw HandsException("JoyBrush didn't answer; try again.")
        // Work the screen queued for the drawing's own thread has run once this answers; its refusals arrive by then.
        val c = canvas
        if (c != null && !onMain && result.get() is Ok) {
            val settled = CountDownLatch(1)
            ui.post { c.ensureBoardDocument { settled.countDown() } }
            settled.await(5, TimeUnit.SECONDS)
        }
        val late = CountDownLatch(1)
        val finish = Runnable {
            if (c != null) c.onRefused = oldRefused
            if (result.get() is Ok && refusals.isNotEmpty()) result.set(Failed(HandsException(refusals.joinToString(" "))))
            late.countDown()
        }
        if (onMain) finish.run() else { ui.post(finish); late.await(5, TimeUnit.SECONDS) }
        return when (val r = result.get()) {
            is Ok -> @Suppress("UNCHECKED_CAST") (r.value as T)
            is Failed -> throw (r.error as? HandsException ?: HandsException("JoyBrush couldn't do that: ${r.error.message}"))
            else -> throw HandsException("JoyBrush didn't answer; try again.")
        }
    }

    private class Ok(val value: Any?)
    private class Failed(val error: Throwable)

    // ------------------------------------------------------------------ reading

    private fun describe(e: JoybotEnv): String {
        val c = e.canvas
        val sb = StringBuilder()
        sb.append("Drawing “").append(e.drawingName() ?: "untitled").append("”, ").append(c.pageWidth).append("×").append(c.pageHeight).append(" px.\n")
        val stack = c.layers
        sb.append("Layers, top to bottom (number 1 = bottom):\n")
        for ((i, l) in stack.layers.withIndex().reversed()) {
            sb.append(if (l.id == stack.activeId) "★ " else "• ").append(i + 1).append(". ").append(l.name)
            sb.append(" — ").append(if (l.visible) "shown" else "hidden").append(", ").append(Math.round(l.opacity * 100)).append("%")
            if (l.locked) sb.append(", locked")
            if (l.hasMask) sb.append(", has a mask")
            sb.append('\n')
        }
        sb.append("★ = the layer being painted on.\n")
        val doc = c.boardDocument
        val boards = doc?.boards.orEmpty()
        if (boards.isEmpty()) sb.append("No boards (animation frames need an animation board).\n")
        for (b in boards) {
            sb.append("Board “").append(b.name).append("” (").append(b.kind.name.lowercase(Locale.US)).append(")")
            if (b.kind == BoardKind.ANIMATION) {
                val at = b.frames.indexOfFirst { it.id == b.currentFrameId } + 1
                sb.append(": ").append(b.frames.size).append(" frame").append(if (b.frames.size == 1) "" else "s")
                sb.append(", on frame ").append(at).append(", ").append(b.fps.toInt()).append(" fps")
            }
            if (b.id == e.selectedBoardId()) sb.append(" ★")
            sb.append('\n')
        }
        val t = e.tools()
        val cur = t.current
        val brush = e.brushes().firstOrNull { it.id == cur?.brushId }
        sb.append("In hand: ").append(brush?.name ?: "no brush").append(", size ").append(cur?.sizePx?.let { Math.round(it) } ?: 0).append(" px, ")
            .append(Math.round((cur?.opacity ?: 1f) * 100)).append("% opacity, colour ").append(hex(c.strokeColor)).append('.')
        return sb.toString()
    }

    private fun hex(argb: Int) = String.format(Locale.US, "#%06X", 0xFFFFFF and argb)

    // ------------------------------------------------------------------ layers

    private fun resolve(c: JbCanvasView, ref: String): LayerState {
        val stack = c.layers
        val r = ref.trim().trim('"', '“', '”').lowercase(Locale.US)
        if (r == "active" || r == "current" || r == "this") return stack.active
        if (r == "top") return stack.layers.last()
        if (r == "bottom" || r == "back") return stack.layers.first()
        r.toIntOrNull()?.let { n ->
            return stack.layers.getOrNull(n - 1) ?: throw HandsException("There is no layer $n; there are ${stack.layers.size}.")
        }
        stack.layers.firstOrNull { it.name.lowercase(Locale.US) == r || it.id.lowercase(Locale.US) == r }?.let { return it }
        val hits = stack.layers.filter { it.name.lowercase(Locale.US).contains(r) }
        if (hits.size == 1) return hits[0]
        if (hits.size > 1) throw HandsException("Several layers match “$ref”: ${hits.joinToString { it.name }}. Use the number.")
        throw HandsException("No layer is called “$ref”. Layers: ${stack.layers.joinToString { it.name }}.")
    }

    private fun layerLines(e: JoybotEnv, lines: List<String>): String {
        val c = e.canvas
        val done = ArrayList<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            done.add(layerLine(e, c, line))
            if (refusals.isNotEmpty()) throw HandsException(refusals.last())
        }
        return done.joinToString("\n")
    }

    private fun layerLine(e: JoybotEnv, c: JbCanvasView, line: String): String {
        val low = line.lowercase(Locale.US)
        val verb = low.substringBefore(' ')
        val rest = line.substring(verb.length).trim()
        when (verb) {
            "add", "new" -> {
                if (!c.addLayer()) throw HandsException("No room for another layer.")
                val name = rest.trim('"', '“', '”')
                if (name.isNotEmpty()) c.renameLayer(c.activeLayerId, name)
                return "Added layer “${c.layers.active.name}”."
            }
            "rename" -> {
                val m = Regex("(?i)^(.+?)\\s+(?:to|as)\\s+(.+)$").find(rest) ?: throw HandsException("Say it as: rename <layer> to <new name>.")
                val l = resolve(c, m.groupValues[1])
                val name = m.groupValues[2].trim().trim('"', '“', '”')
                c.renameLayer(l.id, name)
                return "Renamed “${l.name}” to “$name”."
            }
            "hide", "show" -> {
                val l = resolve(c, rest)
                c.setLayerVisible(l.id, verb == "show")
                return "${if (verb == "show") "Showing" else "Hid"} “${l.name}”."
            }
            "lock", "unlock" -> {
                val l = resolve(c, rest)
                c.setLayerLocked(l.id, verb == "lock")
                return "${if (verb == "lock") "Locked" else "Unlocked"} “${l.name}”."
            }
            "select", "paint" -> {
                val l = resolve(c, rest.removePrefix("on ").trim())
                c.selectLayer(l.id)
                return "Painting on “${l.name}”."
            }
            "opacity" -> {
                val m = Regex("(?i)^(.+?)\\s+(?:to\\s+)?(\\d+(?:\\.\\d+)?)\\s*%?$").find(rest) ?: throw HandsException("Say it as: opacity <layer> <0–100>.")
                val l = resolve(c, m.groupValues[1])
                val pct = m.groupValues[2].toDouble().coerceIn(0.0, 100.0)
                c.commitLayerOpacity(c.layers, l.id, (pct / 100.0).toFloat())
                return "“${l.name}” at ${Math.round(pct)}%."
            }
            "move" -> {
                val m = Regex("(?i)^(.+?)\\s+to\\s+(\\S+)$").find(rest) ?: throw HandsException("Say it as: move <layer> to <number|top|bottom>.")
                val l = resolve(c, m.groupValues[1])
                val n = c.layers.layers.size
                val at = when (val w = m.groupValues[2].lowercase(Locale.US)) {
                    "top", "front" -> n - 1
                    "bottom", "back" -> 0
                    else -> (w.toIntOrNull() ?: throw HandsException("“$w” isn't a position.")) - 1
                }.coerceIn(0, n - 1)
                c.moveLayer(l.id, at)
                return "Moved “${l.name}” to position ${at + 1}."
            }
            "duplicate", "copy" -> {
                val l = resolve(c, rest)
                if (!c.duplicateLayer(l.id)) throw HandsException("“${l.name}” couldn't be duplicated.")
                return "Duplicated “${l.name}”."
            }
            "delete", "remove" -> {
                val l = resolve(c, rest)
                if (!c.deleteLayer(l.id)) throw HandsException("“${l.name}” couldn't be deleted.")
                return "Deleted “${l.name}”. Undo brings it back."
            }
        }
        throw HandsException("I don't know “$line”. Layer commands: add, rename, hide, show, lock, unlock, select, opacity, move, duplicate, delete.")
    }

    /** Names of the layers a "delete" line would remove, for the question to the person. */
    @JvmStatic
    fun deleteNames(lines: List<String>): String = try {
        onUi(group = false) { e ->
            deletes(lines).joinToString(", ") { resolve(e.canvas, it.substringAfter(' ')).name.let { n -> "“$n”" } }
        }
    } catch (x: HandsException) { "that layer" }

    // ------------------------------------------------------------------ frames

    private fun animationBoard(e: JoybotEnv) = e.canvas.boardDocument?.boards.orEmpty().let { boards ->
        boards.firstOrNull { it.id == e.selectedBoardId() && it.kind == BoardKind.ANIMATION }
            ?: boards.firstOrNull { it.kind == BoardKind.ANIMATION }
    }

    private fun frameLines(e: JoybotEnv, lines: List<String>): String {
        val c = e.canvas
        val done = ArrayList<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            done.add(frameLine(e, c, line))
            if (refusals.isNotEmpty()) throw HandsException(refusals.last())
        }
        return done.joinToString("\n")
    }

    private fun frameLine(e: JoybotEnv, c: JbCanvasView, line: String): String {
        val low = line.lowercase(Locale.US)
        val verb = low.substringBefore(' ')
        val rest = low.substringAfter(' ', "").trim()
        if (verb == "new" && rest.startsWith("board")) return newBoard(e, c, line.substringAfter("board").trim())
        val board = animationBoard(e)
            ?: throw HandsException("This drawing has no animation board yet. Make one first with: new board <name>.")
        val b = board
        when (verb) {
            "add" -> {
                val mode = when (rest.removePrefix("a ").removePrefix("frame").trim().split(' ').firstOrNull().orEmpty()) {
                    "", "blank", "empty", "new" -> NewFrame.BLANK
                    "copy", "duplicate" -> NewFrame.DUPLICATE
                    "link", "linked" -> NewFrame.LINK
                    else -> throw HandsException("add blank, add copy (duplicate of this frame) or add link.")
                }
                c.editBoards { RegionDocumentOps.addFrame(it, b.id, mode) { UUID.randomUUID().toString() } }
                return "Added a ${mode.name.lowercase(Locale.US)} frame after the current one to “${b.name}”."
            }
            "duplicate", "copy" -> {
                c.editBoards { RegionDocumentOps.addFrame(it, b.id, NewFrame.DUPLICATE) { UUID.randomUUID().toString() } }
                return "Duplicated the current frame of “${b.name}”."
            }
            "select", "go" -> {
                val n = rest.removePrefix("to").removePrefix("frame").trim().toIntOrNull() ?: throw HandsException("Which frame number?")
                val f = b.frames.getOrNull(n - 1) ?: throw HandsException("“${b.name}” has ${b.frames.size} frames.")
                c.selectBoardFrame(b.id, f.id)
                return "On frame $n of “${b.name}”."
            }
            "delete", "remove" -> {
                val n = rest.removePrefix("frame").trim().toIntOrNull()
                val f = if (n != null) b.frames.getOrNull(n - 1) ?: throw HandsException("“${b.name}” has ${b.frames.size} frames.")
                else b.frames.firstOrNull { it.id == b.currentFrameId } ?: throw HandsException("No frame is selected.")
                c.editBoards { RegionDocumentOps.deleteFrame(it, b.id, f.id) }
                return "Deleted frame ${b.frames.indexOf(f) + 1} of “${b.name}”. Undo brings it back."
            }
        }
        throw HandsException("I don't know “$line”. Frame commands: add [blank|copy|link], select <n>, delete [n], new board <name>.")
    }

    private fun newBoard(e: JoybotEnv, c: JbCanvasView, name: String): String {
        val title = name.trim('"', '“', '”').ifEmpty { "Animation" }
        val w = c.pageWidth; val h = c.pageHeight
        val side = max(64, minOf(w, h) / 2)
        val rect = cc.joycreator.joybrush.core.doc.RectPx((w - side) / 2, (h - side) / 2, side, side)
        c.editBoards { d -> RegionDocumentOps.create(d, title, rect) { UUID.randomUUID().toString() } }
        return "Made the animation board “$title” (${side}×$side px, in the middle of the page) with one frame."
    }

    /** Names of the frame a "delete" line would remove, for the question to the person. */
    @JvmStatic
    fun deleteFrameName(lines: List<String>): String = try {
        onUi(group = false) { e ->
            val b = animationBoard(e) ?: throw HandsException("no board")
            val n = deletes(lines).firstOrNull()?.substringAfter(' ', "")?.removePrefix("frame")?.trim()?.toIntOrNull()
            val at = if (n != null) n else b.frames.indexOfFirst { it.id == b.currentFrameId } + 1
            "frame $at of “${b.name}”"
        }
    } catch (x: HandsException) { "that frame" }

    // ------------------------------------------------------------------ brush and colour

    private fun setBrush(e: JoybotEnv, brush: String?, argb: Int?, size: Double?, opacityPct: Double?): String {
        val said = ArrayList<String>()
        var tools = e.tools()
        if (!brush.isNullOrBlank()) {
            val r = brush.trim().lowercase(Locale.US)
            val all = e.brushes()
            val hit = all.firstOrNull { it.id.lowercase(Locale.US) == r || it.name.lowercase(Locale.US) == r }
                ?: all.filter { it.name.lowercase(Locale.US).contains(r) }.let { m ->
                    if (m.size > 1) throw HandsException("Several brushes match “$brush”: ${m.joinToString { it.name }}.")
                    m.firstOrNull()
                }
                ?: throw HandsException("No brush is called “$brush”. Brushes: ${all.joinToString { it.name }}.")
            tools = tools.pick(hit)
            said.add("brush ${hit.name}")
        }
        if (size != null) { tools = tools.withSize(size.toFloat()); said.add("size ${Math.round(tools.current?.sizePx ?: size.toFloat())} px") }
        if (opacityPct != null) {
            tools = tools.withOpacity((opacityPct / 100.0).coerceIn(0.01, 1.0).toFloat()); said.add("opacity ${Math.round(opacityPct)}%")
        }
        if (said.isNotEmpty()) e.setTools(tools)
        if (argb != null) { e.canvas.colorArgb = argb or (0xFF shl 24); e.colourChanged(); said.add("colour ${hex(argb)}") }
        if (said.isEmpty()) throw HandsException("Nothing to change: give a brush, colour, size or opacity.")
        return "Now using " + said.joinToString(", ") + "."
    }

    // ------------------------------------------------------------------ drawing

    private fun drawShapes(e: JoybotEnv, shapes: List<String>, layer: String?): String {
        val c = e.canvas
        if (c.strokeInProgress) throw HandsException("A stroke is in progress; lift the pen first.")
        if (!layer.isNullOrBlank()) c.selectLayer(resolve(c, layer).id)
        val done = ArrayList<String>()
        val w = c.pageWidth.toFloat(); val h = c.pageHeight.toFloat()
        for (raw in shapes) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val pts = shapePoints(line, w, h)
            if (!c.drawStrokeAsPen(pts)) throw HandsException(refusals.lastOrNull() ?: "The stroke didn't start (is the layer locked or hidden?).")
            if (refusals.isNotEmpty()) throw HandsException(refusals.last())
            done.add(line.substringBefore(' '))
        }
        return "Drew ${done.size} stroke${if (done.size == 1) "" else "s"} (${done.joinToString(", ")}) on “${c.layers.active.name}”."
    }

    private fun numbers(s: String): List<Float> =
        Regex("-?\\d+(?:\\.\\d+)?").findAll(s).map { it.value.toFloat() }.toList()

    /** One shape as document-pixel points, ~every 5 px along it. Coordinates are percent of the page (0–100). */
    private fun shapePoints(line: String, w: Float, h: Float): List<Pair<Float, Float>> {
        val kind = line.substringBefore(' ').lowercase(Locale.US)
        val n = numbers(line.substringAfter(' ', ""))
        fun px(x: Float) = x / 100f * w
        fun py(y: Float) = y / 100f * h
        val corners: List<Pair<Float, Float>> = when (kind) {
            "line" -> { need(n, 4, kind); listOf(px(n[0]) to py(n[1]), px(n[2]) to py(n[3])) }
            "rect", "rectangle", "box" -> {
                need(n, 4, kind)
                val x = px(n[0]); val y = py(n[1]); val rw = px(n[2]); val rh = py(n[3])
                listOf(x to y, x + rw to y, x + rw to y + rh, x to y + rh, x to y)
            }
            "ellipse", "circle" -> {
                need(n, 3, kind)
                val cx = px(n[0]); val cy = py(n[1])
                val rx = px(n[2]); val ry = if (n.size > 3) py(n[3]) else rx
                val steps = max(24, (PI * (rx + ry) / 5f).toInt())
                return (0..steps).map { i -> val a = 2 * PI * i / steps; (cx + rx * cos(a)).toFloat() to (cy + ry * sin(a)).toFloat() }
            }
            "polyline", "path", "polygon" -> {
                if (n.size < 4 || n.size % 2 != 0) throw HandsException("“$kind” needs pairs of x,y numbers.")
                val p = (0 until n.size / 2).map { px(n[2 * it]) to py(n[2 * it + 1]) }
                if (kind == "polygon") p + p[0] else p
            }
            else -> throw HandsException("I can draw: line, rect, ellipse (circle), polyline, polygon. Not “$kind”.")
        }
        return densify(corners)
    }

    private fun need(n: List<Float>, count: Int, kind: String) {
        if (n.size < count) throw HandsException("“$kind” needs $count numbers (percent of the page).")
    }

    private fun densify(corners: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        out.add(corners[0])
        for (i in 1 until corners.size) {
            val (ax, ay) = corners[i - 1]; val (bx, by) = corners[i]
            val d = kotlin.math.hypot((bx - ax).toDouble(), (by - ay).toDouble()).toFloat()
            val steps = max(1, (d / 5f).toInt())
            for (s in 1..steps) out.add((ax + (bx - ax) * s / steps) to (ay + (by - ay) * s / steps))
        }
        return out
    }
}
