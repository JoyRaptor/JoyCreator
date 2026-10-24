package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.lines.LineEditResult
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.androidkit.lines.LineSnapshot
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/** Shared by the Select-host tests: a stand-in canvas and screen, and a few frozen lines. */
internal val planeP = LinePlane("L", null)
internal val planeQ = LinePlane("L", "cel-2")

internal fun testBrush(id: String, name: String = "Ink", engine: String = "stamp") = BrushPreset(
    id = id, name = name, engine = engine, size = Param(6f),
    tip = TipSpec(corner = 2f, hardness = Param(1f)), spacing = 0.04f, accumulate = "wash",
)

internal val frozenInk = testBrush("lb-1")

/** A flat line of 20 samples, 2 px apart, from ([x0], [y]). */
internal fun lineRecord(id: String, seq: Long, x0: Float = 10f, y: Float = 10f, brushId: String = "lb-1") = StrokeRecord(
    id = id, brushId = brushId, seed = 7L, smoothing = 0f, screenPerDoc = 1f,
    samples = List(20) { i -> PenSample(x = x0 + i * 2f, y = y, timeMs = 1000.0 + 8.0 * i) }, seq = seq,
)

internal fun shifted(r: StrokeRecord, dx: Float): StrokeRecord = r.copy(samples = r.samples.map { it.copy(x = it.x + dx) })

internal class FakeCanvas : HostCanvas {
    var snapshot: LineSnapshot = LineSnapshot.EMPTY
    var active: List<LinePlane> = emptyList()
    var visible: List<LinePlane> = emptyList()
    val submitted = ArrayList<Pair<LineEdit, (LineEditResult) -> Unit>>()
    val previews = ArrayList<List<cc.joycreator.joybrush.core.stroke.AddressedStroke>>()
    /** When set, the canvas answers inside submitLineEdit, as it does for "Lift the pen". */
    var answerAtOnce: LineEditResult? = null
    var redraws = 0
    var epoch = 0
    var supportsLineLift = false

    override val lineSnapshot: LineSnapshot get() = snapshot
    override val lineEditEpoch: Int get() = epoch
    override fun activeLinePlanes(): List<LinePlane> = active
    override fun visibleLinePlanes(): List<LinePlane> = visible
    override fun submitLineEdit(edit: LineEdit, done: (LineEditResult) -> Unit) {
        submitted += edit to done
        answerAtOnce?.let(done)
    }
    override fun redraw() { redraws++ }
    override fun beginLineLift(ids: Set<cc.joycreator.joybrush.core.stroke.LineAddress>): Boolean = supportsLineLift
    override fun previewLineLift(records: List<cc.joycreator.joybrush.core.stroke.AddressedStroke>) { previews += records.toList() }

    /** The engine answers the [n]th edit sent. */
    fun answer(n: Int, result: LineEditResult) = submitted[n].second(result)
}

internal class FakeUi : HostUi {
    val notes = ArrayList<String>()
    val logs = ArrayList<String>()
    val penTaps = ArrayList<Pair<Float, Float>>()
    val refusals = ArrayList<String>()
    var overlays = 0
    /** What a test wants done when the engine refuses an edit it accepted (the screen's re-read). */
    var onRefused: (String) -> Unit = {}

    override fun say(text: String, action: String?, onAction: (() -> Unit)?) { notes += text }
    override fun penButtonTap(x: Float, y: Float) { penTaps += x to y }
    override fun overlaysChanged() { overlays++ }
    override fun editRefused(message: String) { refusals += message; onRefused(message) }
    override fun log(note: String) { logs += note }
}

/** A host over a canvas holding lines a and b on plane P (seq 1, 2) and c on plane Q (seq 1); P is active, P and Q visible. */
internal class Rig(
    planes: Map<LinePlane, List<StrokeRecord>> = mapOf(
        planeP to listOf(lineRecord("a", 1, y = 10f), lineRecord("b", 2, y = 30f)),
        planeQ to listOf(lineRecord("c", 1, y = 50f)),
    ),
    brushes: Map<String, BrushPreset> = mapOf("lb-1" to frozenInk),
) {
    val canvas = FakeCanvas().also {
        it.snapshot = LineSnapshot(1L, planes, brushes)
        it.active = listOf(planeP)
        it.visible = listOf(planeP, planeQ)
    }
    val ui = FakeUi()
    val host = CanvasSelectionHost(canvas, ui) { "lb-new" }

    /** The engine published [planes] as its next snapshot. */
    fun publish(planes: Map<LinePlane, List<StrokeRecord>>, brushes: Map<String, BrushPreset> = canvas.snapshot.brushes) {
        canvas.snapshot = LineSnapshot(canvas.snapshot.revision + 1, planes, brushes)
    }
}
