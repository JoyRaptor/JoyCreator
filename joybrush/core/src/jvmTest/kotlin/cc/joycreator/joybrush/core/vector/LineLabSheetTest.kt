package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.joybrushRoot
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.paint.DabTravel
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paper.PaperCatalogue
import cc.joycreator.joybrush.core.paper.PaperCatalogues
import cc.joycreator.joybrush.core.paper.PaperState
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import cc.joycreator.joybrush.core.paper.SurfaceMaps
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The LINE LAB's sheet writer (joybrush/lab/lines). It makes NO pictures: it turns a brush file into the exact numbers the
 * phone's GPU path would be handed, so `joybrush/lab/lines/render_sheet.js` can draw them with the real shaders.
 *
 * For each brush (the shipped Pencil and Ink, plus every json file in `joybrush/lab/lines/presets`) it lays one test sheet of
 * strokes (see [layout]), replays each with [InkReplay.dabs], and writes `joybrush/core/build/linelab/<name>.json`:
 * per stroke the packed instance floats (17 per dab, exactly `GlPaintEngine.fillInstances` + `putContact`) and every
 * uniform `GlPaintEngine.beginStroke` / `setGrainUniforms` / `setPaperSurfaceUniforms` / `setCommitUniforms` would set.
 *
 * Paper: the app's default document has NO surface (`Paper()` = flat white, smooth), and then `documentPaperGrain` turns the
 * paper grain OFF and the shipped Pencil draws with no tooth at all. The lab instead uses the paper the Pencil is designed
 * for, `pulp_artisan` (`GrainMath.DEFAULT_SURFACE`, the `off_white` look's default surface). `LINELAB_PAPER=none` gives the
 * smooth default document; `LINELAB_PAPER=<surface id>` any catalogue surface.
 */
class LineLabSheetTest {

    // ---- the sheet -------------------------------------------------------------------------------------------------

    private val sheetW = 2048
    private val sheetH = 1280
    private val tile = 2048

    /** 240 Hz pen, hand at 2000 px/s: one sample every 8.33 px. */
    private val dtMs = 1000.0 / 240.0
    private val stepPx = 2000.0 / 240.0

    /** The pen leans toward the LEFT of the page: azimuth pi, for which GrainMath gives lean = (-1, ~0). */
    private val azimuth = PI.toFloat()

    private val deg = (PI / 180.0).toFloat()

    private class Spec(val group: String, val name: String, val label: String, val seed: Long, val samples: List<PenSample>)
    private class Cell(val group: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    private fun walk(pts: List<Pair<Float, Float>>, tilt: Float, pressureAt: (Float) -> Float): List<PenSample> {
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + hypot((pts[i].first - pts[i - 1].first).toDouble(), (pts[i].second - pts[i - 1].second).toDouble())
        val total = cum.last()
        require(total > 0.0)
        val n = ceil(total / stepPx).toInt()
        val out = ArrayList<PenSample>(n + 1)
        var seg = 0
        for (i in 0..n) {
            val s = min(i * stepPx, total)
            while (seg < pts.size - 2 && cum[seg + 1] < s) seg++
            val len = cum[seg + 1] - cum[seg]
            val t = if (len > 0.0) ((s - cum[seg]) / len).toFloat() else 0f
            val x = pts[seg].first + (pts[seg + 1].first - pts[seg].first) * t
            val y = pts[seg].second + (pts[seg + 1].second - pts[seg].second) * t
            out.add(PenSample(x, y, timeMs = i * dtMs, pressure = pressureAt((s / total).toFloat()), tilt = tilt, azimuth = azimuth))
        }
        return out
    }

    private fun line(a: Pair<Float, Float>, b: Pair<Float, Float>, tilt: Float, p: (Float) -> Float) = walk(listOf(a, b), tilt, p)

    private fun layout(): Pair<List<Spec>, List<Cell>> {
        val specs = ArrayList<Spec>()
        var seed = 0x5EED0000L
        fun add(group: String, name: String, label: String, samples: List<PenSample>) { specs.add(Spec(group, name, label, seed++, samples)) }

        // A. point-mode pressure ladder: 10 parallel diagonals (30 degrees below horizontal), 600 px, tilt 0.15 rad.
        for (i in 0..9) {
            val p = (i + 1) / 10f
            val a = Pair(60f + 55f * i, 40f)
            val b = Pair(a.first + 600f * cos(30f * deg), a.second + 600f * sin(30f * deg))
            add("A", "A_pressure_%.1f".format(java.util.Locale.ROOT, p), "A pressure ladder: constant pressure $p, tilt 0.15 rad", line(a, b, 0.15f) { p })
        }
        // B. tilt ladder: 6 vertical strokes, 500 px, pressure 0.5, tilt 0..70 degrees.
        for ((i, t) in listOf(0, 15, 30, 45, 60, 70).withIndex()) {
            val x = 1230f + 110f * i
            add("B", "B_tilt_$t", "B tilt ladder: tilt $t deg, pressure 0.5", line(Pair(x, 110f), Pair(x, 610f), t * deg) { 0.5f })
        }
        // C. pressure swell 0 -> 1 -> 0, once nearly upright and once at 55 degrees.
        val swell = { u: Float -> sin(PI * u).toFloat() }
        add("C1", "C_swell_tilt0.15", "C pressure swell 0-1-0, tilt 0.15 rad", line(Pair(100f, 410f), Pair(1000f, 410f), 0.15f, swell))
        add("C2", "C_swell_tilt55", "C pressure swell 0-1-0, tilt 55 deg", line(Pair(100f, 600f), Pair(1000f, 600f), 55f * deg, swell))
        // D. zigzag: 8 legs of 300 px, each turning 150 degrees, tilt 55 degrees, pressure 0.6. One stroke.
        run {
            val pts = ArrayList<Pair<Float, Float>>()
            var cur = Pair(1230f, 1120f)
            pts.add(cur)
            for (k in 0..7) {
                val a = (if (k % 2 == 0) -75f else 75f) * deg
                cur = Pair(cur.first + 300f * cos(a), cur.second + 300f * sin(a))
                pts.add(cur)
            }
            add("D", "D_zigzag_tilt55", "D zigzag, 8 legs of 300 px turning 150 deg, tilt 55 deg, pressure 0.6", walk(pts, 55f * deg) { 0.6f })
        }
        // E. three sine waves, 3 periods of 180 px, amplitude 35 px, pressure 0.2 / 0.5 / 0.9, tilt 0.15 rad.
        for ((i, p) in listOf(0.2f, 0.5f, 0.9f).withIndex()) {
            val cy = 820f + 130f * i
            val pts = List(541) { k -> Pair(60f + k, cy + 35f * sin(2.0 * PI * k / 180.0).toFloat()) }
            add("E", "E_wave_p$p", "E wavy line, pressure $p, tilt 0.15 rad", walk(pts, 0.15f) { p })
        }
        // F. hatching: 12 parallel strokes of 250 px, 10 px apart, alternating direction, pressure 0.5, tilt 30 degrees.
        for (i in 0..11) {
            val d = Pair(cos(30f * deg), -sin(30f * deg))
            val n = Pair(sin(30f * deg), cos(30f * deg))
            val s = Pair(730f + n.first * 10f * i, 1040f + n.second * 10f * i)
            val e = Pair(s.first + d.first * 250f, s.second + d.second * 250f)
            val (a, b) = if (i % 2 == 0) Pair(s, e) else Pair(e, s)
            add("F", "F_hatch_$i", "F hatching stroke $i (${if (i % 2 == 0) "up-right" else "down-left"}), tilt 30 deg, pressure 0.5", line(a, b, 30f * deg) { 0.5f })
        }
        val cells = listOf(
            Cell("A", 20f, 20f, 1110f, 360f), Cell("B", 1120f, 0f, 1890f, 720f), Cell("C1", 20f, 385f, 1110f, 440f),
            Cell("C2", 20f, 445f, 1110f, 735f), Cell("E", 20f, 745f, 650f, 1275f), Cell("D", 1120f, 722f, 1960f, 1275f),
            Cell("F", 660f, 745f, 1110f, 1275f),
        )
        return specs to cells
    }

    // ---- paper -----------------------------------------------------------------------------------------------------

    private class PaperPick(val resolved: ResolvedPaper, val id: String, val why: String, val surfaceAsset: String?, val fluidAsset: String?, val fluidSize: Int)

    private fun pickPaper(root: File, catalogue: PaperCatalogue, outDir: File): PaperPick {
        val wanted = System.getenv("LINELAB_PAPER")?.takeIf { it.isNotBlank() } ?: "pulp_artisan"
        if (wanted == "none") {
            val resolved = PaperState.resolve(Paper(), catalogue)
            return PaperPick(resolved, "none", "LINELAB_PAPER=none: the app's default document (Paper() is flat white, smooth); paper grain is OFF", null, null, 1)
        }
        val surface = PaperCatalogues.surface(catalogue, wanted) ?: error("LINELAB_PAPER=$wanted is not a surface id in assets/paper/catalogue.json")
        val look = catalogue.looks.firstOrNull { it.defaultSurface == wanted }
        val resolved = PaperState.resolve(Paper(lookId = look?.id, textureId = wanted), catalogue)
        // A surface whose file holds only the height is packed at load by the app (GrainTextures.expandSurface): do the same.
        val asset: String = if (surface.packed) "assets/paper/${surface.file}" else {
            val src = ImageIO.read(File(root, "assets/paper/${surface.file}")) ?: error("cannot read ${surface.file}")
            val w = src.width
            val h = src.height
            val heights = ByteArray(w * h) { src.raster.getSample(it % w, it / w, 0).toByte() }
            val rgba = SurfaceMaps.pack(heights, w, h, surface.slopeRange)
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            for (i in 0 until w * h) {
                val r = rgba[i * 4].toInt() and 255; val g = rgba[i * 4 + 1].toInt() and 255
                val b = rgba[i * 4 + 2].toInt() and 255; val a = rgba[i * 4 + 3].toInt() and 255
                img.setRGB(i % w, i / w, (a shl 24) or (r shl 16) or (g shl 8) or b)
            }
            val out = File(outDir, "assets/${surface.file}")
            out.parentFile.mkdirs()
            ImageIO.write(img, "png", out)
            "core/build/linelab/assets/${surface.file}"
        }
        val fluid = surface.fluid
        val fluidSize = if (fluid != null) ImageIO.read(File(root, "assets/paper/$fluid")).width else 1
        val why = if (wanted == "pulp_artisan") "pulp_artisan: the paper the Pencil is designed for (GrainMath.DEFAULT_SURFACE, the off_white look's defaultSurface); the app's own default document has no surface"
        else "LINELAB_PAPER=$wanted"
        return PaperPick(resolved, wanted, why, asset, fluid?.let { "assets/paper/$it" }, fluidSize)
    }

    /** `documentPaperGrain` (androidkit/gl/DocumentPaperGrain.kt), transcribed: the androidkit class is not visible from core. */
    private fun documentPaperGrain(brush: GrainMath.GrainUniforms, paper: ResolvedPaper, influence: Float): GrainMath.GrainUniforms {
        val surface = paper.surface ?: return GrainMath.GrainUniforms.OFF
        if (influence <= 0f || paper.bite <= 0f) return GrainMath.GrainUniforms.OFF
        val chosen = if (brush.enabled) brush else GrainMath.GrainUniforms(surface.texelPx, GrainMath.UNIVERSAL_DEPTH, GrainMath.UNIVERSAL_EDGE, 0f, 0f, surface.file)
        return chosen.copy(asset = surface.file, pitchPx = surface.texelPx * paper.scale)
    }

    // ---- one stroke, as the live path hands it to the GPU -----------------------------------------------------------

    private class StrokeOut(val spec: Spec, val json: String, val bbox: FloatArray, val dabCount: Int)

    private fun strokeJson(spec: Spec, preset: BrushPreset, paper: PaperPick, catalogue: PaperCatalogue, root: File, notes: MutableList<String>): StrokeOut {
        val record = StrokeRecord(spec.name, preset.id, spec.seed, preset.smoothing, 1f, spec.samples)
        val dabs: List<Dab> = InkReplay.dabs(record, preset)
        assertTrue(dabs.isNotEmpty(), "${spec.name}: the replay made no dabs")

        // The stroke's whole-stroke numbers come from a SECOND dabber fed exactly the way InkReplay.placedDabs feeds its own
        // (response curves -> smoother -> placer asking dabber.look once per dab): its first-dab captures are what
        // JbCanvasView.beginStrokeNow reads. (InkReplay's dabber is private, and its dab list also has taper/scatter applied.)
        val smoother = StrokeSmoother(record.smoothing, record.screenPerDoc)
        val released = ArrayList<PenSample>()
        for (s in record.samples) released.addAll(smoother.add(preset.response.apply(s)))
        released.addAll(smoother.finish())
        val dabber = BrushDabber(preset, record.seed)
        DabPlacer(spacing = dabber.spacing, look = dabber::look).add(released)

        // JbCanvasView.beginStrokeNow
        val buildUp = preset.accumulate == "buildup"
        val opacity = dabber.strokeOpacity.coerceIn(0f, 1f)              // GlPaintEngine.beginStroke: opacity.coerceIn(0, 1)
        val tip = TipShape(aspect = preset.tip.aspect, corner = preset.tip.corner, taper = preset.tip.taper,
            hardness = dabber.strokeHardness, minPx = preset.tip.minPx)
        val brushGrain = dabber.strokeGrain
        // GlPaintEngine.beginStroke
        val response = brushGrain.paperResponse
        val influence = response.influence * paper.resolved.bite
        val tipGrainFolder = { name: String -> if (catalogue.surfaces.any { it.file == name || it.fluid == name }) "paper" else "grain" }
        val tipAsset = if (brushGrain.tip.enabled) "assets/${tipGrainFolder(brushGrain.tip.asset)}/${brushGrain.tip.asset}" else null
        val tipOn = tipAsset != null && File(root, tipAsset).isFile
        if (tipAsset != null && !tipOn) notes.add("${spec.name}: tip grain $tipAsset is missing, so the app would draw it OFF")
        val documentGrain = documentPaperGrain(brushGrain.paper, paper.resolved, response.influence)
        val paperOn = documentGrain.enabled && paper.surfaceAsset != null
        val tipGrain = if (tipOn) brushGrain.tip else GrainMath.GrainUniforms.OFF
        val paperGrain = if (paperOn) documentGrain else GrainMath.GrainUniforms.OFF

        // TipShape/Dab -> instance floats: fillInstances + putContact
        val travel = DabTravel()
        val sb = StringBuilder(dabs.size * 150)
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        fun f(sb: StringBuilder, v: Float) { require(v.isFinite()) { "${spec.name}: non-finite float in a dab" }; sb.append(v.toString()) }
        for ((k, d) in dabs.withIndex()) {
            travel.update(d.x, d.y)                                  // addDabs: strokeTravel.update for EVERY dab
            val tx = if (d.travelKnown) d.travelX else travel.x
            val ty = if (d.travelKnown) d.travelY else travel.y
            fun resolved(v: Float, fallback: Float) = if (v.isFinite()) v else fallback
            val live = d.aspect.isFinite() || d.hardness.isFinite() || d.tipDepth.isFinite() || d.paperDepth.isFinite() || d.anchor.isFinite()
            val anchor = resolved(d.anchor, 0f)
            val v = floatArrayOf(d.x, d.y, d.radius, d.angle, d.flow, d.cap, tx, ty,
                resolved(d.aspect, tip.aspect), resolved(d.hardness, tip.hardness), resolved(d.tipDepth, tipGrain.depth), resolved(d.paperDepth, paperGrain.depth),
                anchor, GrainMath.tiltAmount(d.tilt), GrainMath.leanX(d.azimuth), GrainMath.leanY(d.azimuth), if (live) 1f else 0f)
            // jb_dab.vert: ext = radius * (1.4143 + (live ? |anchor| : 0)) + 2
            val ext = d.radius * (1.4143f + (if (live) abs(anchor) else 0f)) + 2f
            x0 = min(x0, d.x - ext); y0 = min(y0, d.y - ext); x1 = max(x1, d.x + ext); y1 = max(y1, d.y + ext)
            if (k > 0) sb.append(",\n")
            for (i in v.indices) { if (i > 0) sb.append(','); f(sb, v[i]) }
        }
        val last = dabs.last()   // setGrainUniforms(newest dab of the batch): the lab draws a stroke as one batch

        val s = StringBuilder(sb.length + 2048)
        fun grain(g: GrainMath.GrainUniforms, asset: String?) =
            """{"pitchPx":${g.pitchPx},"depth":${g.depth},"edge":${g.edge},"tiltGradient":${g.tiltGradient},"radial":${g.radial},"asset":${asset?.let { "\"$it\"" } ?: "null"}}"""
        s.append("{\"name\":\"${spec.name}\",\"group\":\"${spec.group}\",\"label\":\"${spec.label}\",\"seed\":${spec.seed},\"samples\":${spec.samples.size},")
        s.append("\"bbox\":[$x0,$y0,$x1,$y1],\"dabCount\":${dabs.size},")
        s.append("\"tip\":{\"aspect\":${tip.aspect},\"corner\":${tip.corner},\"taper\":${tip.taper},\"hardness\":${tip.hardness},\"minPx\":${tip.minPx}},")
        s.append("\"tipGrain\":${grain(tipGrain, if (tipOn) tipAsset else null)},")
        s.append("\"paperGrain\":${grain(paperGrain, if (paperOn) paper.surfaceAsset else null)},")
        s.append("\"legacy\":{\"tiltAmount\":${GrainMath.tiltAmount(last.tilt)},\"leanDir\":[${GrainMath.leanX(last.azimuth)},${GrainMath.leanY(last.azimuth)}]},")
        s.append("\"paperResponse\":{\"influence\":$influence,\"directional\":${response.directional},\"wet\":${response.wet},\"brushInfluence\":${response.influence},\"bite\":${paper.resolved.bite}},")
        s.append("\"accumulate\":\"${if (buildUp) "buildup" else "wash"}\",\"opacity\":$opacity,\"strokeScale\":${if (buildUp) opacity else 1f},")
        s.append("\"color\":[0.0,0.0,0.0],\"blend\":${when (preset.blend) { "multiply" -> 1; "screen" -> 2; else -> 0 }},")
        // A preset that says extensions.deposit = "tooth" asks the shader for the capacity (tooth-limited) deposit mode.
        s.append("\"grainCapacity\":${if (preset.extensions["deposit"] == "tooth") 1 else 0},")
        s.append("\"dabs\":[\n").append(sb).append("\n]}")
        return StrokeOut(spec, s.toString(), floatArrayOf(x0, y0, x1, y1), dabs.size)
    }

    // ---- the test --------------------------------------------------------------------------------------------------

    private class Named(val name: String, val preset: BrushPreset, val shipped: Boolean)

    @Test
    fun writesLineLabSheets() {
        val root = joybrushRoot()
        val outDir = File(root, "core/build/linelab").also { it.mkdirs() }
        val catalogue = PaperCatalogues.parse(File(root, "assets/paper/catalogue.json").readText())
        val paper = pickPaper(root, catalogue, outDir)

        // The azimuth must be the one that leans the pen to the page's left.
        assertTrue(GrainMath.leanX(azimuth) < -0.999f && abs(GrainMath.leanY(azimuth)) < 1e-3f, "azimuth $azimuth does not lean left")

        val presets = ArrayList<Named>()
        for (n in listOf("pencil", "ink")) presets.add(Named(n, BrushJson.decodeChecked(File(root, "brushes/$n/brush.json").readText()), true))
        File(root, "lab/lines/presets").listFiles { f -> f.isFile && f.extension == "json" }?.sortedBy { it.name }?.forEach {
            presets.add(Named(it.nameWithoutExtension, BrushJson.decodeChecked(it.readText()), false))
        }
        assertTrue(presets.map { it.name }.containsAll(listOf("pencil", "ink", "pencil_p1", "pencil_p2")),
            "the four reference sheets must be available")

        val (specs, cells) = layout()
        // The cells (one per group) must not overlap each other, and sit inside the sheet.
        for (c in cells) assertTrue(c.x0 >= 0 && c.y0 >= 0 && c.x1 <= sheetW && c.y1 <= sheetH && c.x0 < c.x1 && c.y0 < c.y1, "cell ${c.group} leaves the sheet")
        for (i in cells.indices) for (j in i + 1 until cells.size) {
            val a = cells[i]; val b = cells[j]
            assertTrue(a.x1 <= b.x0 || b.x1 <= a.x0 || a.y1 <= b.y0 || b.y1 <= a.y0, "cells ${a.group} and ${b.group} overlap")
        }

        val summary = StringBuilder()
        summary.append("azimuth = $azimuth rad -> lean = (${GrainMath.leanX(azimuth)}, ${GrainMath.leanY(azimuth)})\n")
        summary.append("paper = ${paper.id}: ${paper.why}\n")
        val failures = ArrayList<String>()
        for (named in presets) {
            val notes = ArrayList<String>()
            val refusal = InkReplay.refusal(StrokeRecord("x", named.preset.id, 1L, named.preset.smoothing, 1f, emptyList()), named.preset)
            if (refusal != null) { failures.add("${named.name}: $refusal"); continue }
            val strokes = specs.map { strokeJson(it, named.preset, paper, catalogue, root, notes) }
            for (st in strokes) {
                val cell = cells.first { it.group == st.spec.group }
                val inside = st.bbox[0] >= cell.x0 && st.bbox[1] >= cell.y0 && st.bbox[2] <= cell.x1 && st.bbox[3] <= cell.y1
                if (!inside) {
                    val msg = "${st.spec.name}: dab extent ${st.bbox.toList()} leaves its cell ${cell.group} (${cell.x0},${cell.y0},${cell.x1},${cell.y1})"
                    if (named.shipped) failures.add("${named.name}: $msg") else notes.add(msg)
                }
                if (st.bbox[0] < 0 || st.bbox[1] < 0 || st.bbox[2] > sheetW || st.bbox[3] > sheetH) notes.add("${st.spec.name}: dab extent leaves the sheet")
            }
            val p = named.preset
            val sb = StringBuilder(strokes.sumOf { it.json.length } + 8192)
            sb.append("{\"format\":\"joybrush.linelab.sheet\",\"version\":1,\"name\":\"${named.name}\",")
            sb.append("\"preset\":{\"id\":\"${p.id}\",\"name\":\"${p.name}\",\"version\":${p.version},\"engine\":\"${p.engine}\",\"smoothing\":${p.smoothing},\"accumulate\":\"${p.accumulate}\",\"blend\":\"${p.blend}\"},")
            sb.append("\"sheet\":{\"width\":$sheetW,\"height\":$sheetH,\"tileSize\":$tile,\"background\":[1.0,1.0,1.0],\"docPxPerMm\":20,\"penHz\":240,\"speedPxPerS\":2000},")
            sb.append("\"pen\":{\"azimuthRad\":$azimuth,\"lean\":[${GrainMath.leanX(azimuth)},${GrainMath.leanY(azimuth)}]},")
            val s = paper.resolved.surface
            sb.append("\"paper\":{\"choice\":\"${paper.id}\",\"why\":\"${paper.why.replace("\"", "'")}\",\"surfaceAsset\":${paper.surfaceAsset?.let { "\"$it\"" } ?: "null"},")
            sb.append("\"texelPx\":${(s?.texelPx ?: GrainMath.SURFACE_TEXEL_PX) * paper.resolved.scale},\"scale\":${paper.resolved.scale},\"bite\":${paper.resolved.bite},")
            sb.append("\"size\":${s?.size?.toFloat() ?: GrainMath.SURFACE_SIZE},\"hexTexels\":${s?.hexTexels ?: GrainMath.SURFACE_HEX_TEXELS},\"slopeRange\":${s?.slopeRange ?: GrainMath.SURFACE_SLOPE_RANGE},")
            sb.append("\"heightMean\":${s?.heightMean ?: 0.5f},\"rotatable\":${s?.rotatable ?: GrainMath.SURFACE_ROTATABLE},")
            sb.append("\"fluid\":${if (s?.fluid != null && paper.fluidAsset != null) "{\"asset\":\"${paper.fluidAsset}\",\"texelPx\":${s.fluidTexelPx * paper.resolved.scale},\"size\":${paper.fluidSize},\"hexTexels\":${s.fluidHexTexels}}" else "null"}},")
            sb.append("\"cells\":[${cells.joinToString(",") { "{\"group\":\"${it.group}\",\"rect\":[${it.x0},${it.y0},${it.x1},${it.y1}]}" }}],")
            sb.append("\"notes\":[${notes.joinToString(",") { "\"" + it.replace("\"", "'") + "\"" }}],")
            sb.append("\"strokes\":[\n").append(strokes.joinToString(",\n") { it.json }).append("\n]}")
            val sheet = Json.parseToJsonElement(sb.toString()).jsonObject
            assertEquals(strokes.size, sheet.getValue("strokes").jsonArray.size)
            for (stroke in sheet.getValue("strokes").jsonArray) {
                val values = stroke.jsonObject
                assertEquals(values.getValue("dabCount").jsonPrimitive.int * 17,
                    values.getValue("dabs").jsonArray.size, "${named.name}: packed instance count")
            }
            File(outDir, "${named.name}.json").writeText(sb.toString())
            summary.append("${named.name}: ${strokes.size} strokes, ${strokes.sumOf { it.dabCount }} dabs, hardness/depth at first dab -> ${strokes[0].json.substringAfter("\"tip\":").substringBefore("\"tipGrain\"")}\n")
            notes.forEach { summary.append("  note: $it\n") }
        }
        File(outDir, "summary.txt").writeText(summary.toString())
        println(summary)
        assertEquals(emptyList<String>(), failures, "line lab sheets could not be written")
    }
}
