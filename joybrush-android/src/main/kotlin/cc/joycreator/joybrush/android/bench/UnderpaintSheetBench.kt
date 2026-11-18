package cc.joycreator.joybrush.android.bench

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.ViewGroup
import android.widget.FrameLayout
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.preview.UnderpaintPatterns
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_PUSH
import cc.joycreator.joybrush.core.brush.PushSpec
import cc.joycreator.joybrush.core.chrome.BrushPreviewStrokes
import java.io.File

/**
 * DEBUG ONLY (`jb_underpaint_sheet`): the owner's pick of what a smudge works into on the brush settings stage (11-18:
 * "the checkers are so fine they turn to grey too soon ... give me 14 options on a variety of smudge brushes"). For each
 * brush, every [UnderpaintPatterns] option is laid on a canvas the size of the stage, exactly as the stage does it
 * ([JbCanvasView.replaceWithStrokes] with the pattern as the layer's own paint), the test stroke drawn over it with that
 * brush, and the screen copied ([PixelCopy]) once the paint has settled. One labelled sheet per brush, two columns:
 * `files/underpaint/<n>_<brush>.png`. Pull with adb. Never saves a drawing.
 *
 *     adb shell am start -n com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity --ez jb_underpaint_sheet true
 *
 * The brushes: the two shipped smudge-engine brushes, three made here to show what the engine can do that no shipped
 * brush does (texture pick-up with paper grain, push), and the media brushes that move paint (clear water, the oil
 * blenders). Results: `adb logcat -s JoyBrushUnderpaint`.
 */
class UnderpaintSheetBench(
    private val activity: Activity,
    private val brushes: List<BrushPreset>,
    private val onEnd: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val out = File(activity.getExternalFilesDir(null), "underpaint").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    private lateinit var stage: JbCanvasView
    private val jobs = ArrayDeque<Pair<BrushPreset, String>>()
    private var cells = ArrayList<Pair<String, Bitmap>>()
    private var current: BrushPreset? = null
    private var sheetNo = 0

    fun start() {
        val byId = brushes.associateBy { it.id }
        val smudge = byId["joybrush.smudge"]
        val flat = byId["joybrush.flatpaint"]
        val list = ArrayList<Pair<BrushPreset, String>>()
        smudge?.let { list += it to "Smudge" }
        flat?.let { list += it to "Flat Paint" }
        if (smudge != null && flat != null) {
            // What the engine can do that no shipped smudge does: lift the paint's texture and ride the paper's grain.
            list += smudge.copy(id = "bench.smudge-texture", name = "Smudge with texture and grain",
                smudge = smudge.smudge.copy(texturePickup = 0.6f), paperGrain = flat.paperGrain, paper = flat.paper) to "Smudge, textured"
            list += smudge.copy(id = "bench.smudge-strong", name = "Strong smudge",
                smudge = smudge.smudge.copy(pickup = 0.02f), flow = smudge.flow.copy(base = 0.9f), size = smudge.size.copy(base = 44f)) to "Smudge, long pull"
            list += smudge.copy(id = "bench.push", name = "Push", engine = ENGINE_PUSH, push = PushSpec(0.5f),
                size = smudge.size.copy(base = 40f)) to "Push"
        }
        for ((id, label) in listOf("media:wet:Water" to "Water (watercolour)", "media:paste:Fan blender" to "Fan blender (oil)",
                "media:paste:Scraper" to "Scraper (oil)", "media:paste:Palette knife 1" to "Palette knife 1 (oil)")) {
            byId[id]?.let { list += it to label } ?: android.util.Log.w(TAG, "no brush $id: left out")
        }
        for (b in list) jobs.addLast(b)
        // A stage-sized canvas over the drawing, as the settings sheet lays it.
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val w = (STAGE_W_DP * activity.resources.displayMetrics.density).toInt()
        val h = (STAGE_H_DP * activity.resources.displayMetrics.density).toInt()
        stage = JbCanvasView(activity).apply {
            setZOrderMediaOverlay(true)
            longPressEyedropper = false
            colorArgb = INK
            smallMediaPreview = true
        }
        root.addView(FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(stage, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        android.util.Log.i(TAG, "${jobs.size} brushes x ${UnderpaintPatterns.ALL.size} patterns on a $w x $h stage")
        stage.onReady = { main.postDelayed({ nextBrush() }, 500) }
    }

    private var patterns = ArrayDeque<UnderpaintPatterns.Pattern>()
    private var label = ""

    private fun nextBrush() {
        current?.let { writeSheet() }
        val next = jobs.removeFirstOrNull() ?: return finish()
        current = next.first
        label = next.second
        cells = ArrayList()
        patterns = ArrayDeque(UnderpaintPatterns.ALL)
        nextPattern()
    }

    private fun nextPattern() {
        val p = patterns.removeFirstOrNull() ?: return nextBrush()
        val brush = current!!
        stage.preset = brush
        val sheet = BrushPreviewStrokes.sheet(stage.width.toFloat(), stage.height.toFloat())
        stage.replaceWithStrokes(sheet.map { it.samples }, sheet.map { null }, ground = p)
        waitSettled(p, 0)
    }

    /** Waits until no stroke, media stroke or running water is left (at most [MAX_WAITS] looks), then copies the stage. */
    private fun waitSettled(p: UnderpaintPatterns.Pattern, tries: Int) {
        main.postDelayed({
            stage.benchState(readPixels = false) { st, _ ->
                if (st?.idle != true && tries < MAX_WAITS) waitSettled(p, tries + 1) else shoot(p)
            }
        }, WAIT_MS)
    }

    private fun shoot(p: UnderpaintPatterns.Pattern) {
        val bmp = Bitmap.createBitmap(stage.width, stage.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(stage, bmp, { status ->
            if (status == PixelCopy.SUCCESS) cells += p.name to bmp
            else android.util.Log.w(TAG, "copy failed ($status) for $label / ${p.name}")
            nextPattern()
        }, main)
    }

    private fun writeSheet() {
        if (cells.isEmpty()) return
        val cw = cells[0].second.width / 2
        val ch = cells[0].second.height / 2
        val pad = 8
        val cap = 30
        val title = 56
        val cols = 2
        val rows = (cells.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(cols * cw + (cols + 1) * pad, title + rows * (ch + cap + pad) + pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(40, 40, 44))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 220, 220); textSize = 22f }
        c.drawText(label, pad.toFloat(), 42f, text)
        for ((i, cell) in cells.withIndex()) {
            val x = pad + (i % cols) * (cw + pad)
            val y = title + (i / cols) * (ch + cap + pad)
            c.drawText("${i + 1}. ${cell.first}", x.toFloat(), (y + 22).toFloat(), small)
            c.drawBitmap(Bitmap.createScaledBitmap(cell.second, cw, ch, true), x.toFloat(), (y + cap).toFloat(), null)
        }
        sheetNo++
        val f = File(out, "%02d_%s.png".format(sheetNo, label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')))
        f.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i(TAG, "sheet: ${f.absolutePath} (${cells.size} patterns)")
    }

    private fun finish() {
        android.util.Log.i(TAG, "done: $sheetNo sheets in ${out.absolutePath}")
        onEnd()
    }

    companion object {
        const val TAG = "JoyBrushUnderpaint"
        /** The settings stage on the Note 9: 1008 x 354 px at 2.625 px a dp (dp values stay as drawn). */
        private const val STAGE_W_DP = 384f
        private const val STAGE_H_DP = 135f
        private const val INK = 0xFF202020.toInt()
        private const val WAIT_MS = 250L
        private const val MAX_WAITS = 16

        fun wanted(activity: Activity): Boolean =
            activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                activity.intent?.getBooleanExtra("jb_underpaint_sheet", false) == true
    }
}
