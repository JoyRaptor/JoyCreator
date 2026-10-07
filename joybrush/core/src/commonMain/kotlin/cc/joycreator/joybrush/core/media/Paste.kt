package cc.joycreator.joybrush.core.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Paste media (oil, palette knives, scraper): brushes, opaque paint, and the stroke → step builder. Port of
// lab/media/js/paste.js; the paint trade runs on the GPU (shaders/media/jb_paste_dab.frag on the canvas,
// jb_paste_brush.frag on the brush's 32×8 cells) from the same rules on both sides.

const val PASTE_LANES = 32
const val PASTE_DEPTH = 8

/** How a blade's edge lies (the owner's Edge toggle): along the pen's lean, across the stroke, along the stroke. */
enum class EdgeMode(val id: String, val label: String) {
    PEN("pen", "pen angle"), ACROSS("across", "across stroke"), ALONG("along", "along stroke");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

/** What pressure means for a loaded blade: more paint comes off (LOAD), or it digs deeper (DEPTH). */
enum class PressMode(val id: String) {
    LOAD("load"), DEPTH("depth");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

/** What part of a loaded knife touches: its thin edge, or its flat face. */
enum class FaceMode(val id: String) {
    EDGE("edge"), FLAT("flat");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

/**
 * A paste brush. shape: 0 round, 1 flat, 2 knife, 3 scraper. Lengths in mm. thickMm = layer a full brush leaves;
 * loadLenMm = how far a full load lays a full layer. Nullable fields are "not set" in the lab table (the engine's
 * defaults apply). A [trowel] is the v6/v7 knife (Palette knife 1); a [blade] is the rigid-edge model.
 */
data class PasteBrush(
    val shape: Int,
    val widthMm: Double = 0.0, val lenMm: Double = 0.0, val thickMm: Double = 0.0,
    val scrape: Double = 0.0, val hairDepth: Double = 0.0, val ridge: Double = 0.0, val rate: Double = 1.0,
    val mix: Double = 0.0, val swap: Double = 0.6, val loadLenMm: Double = 1.0, val wick: Double = 0.0,
    val bow: Double = 0.0, val lump: Double = 0.0, val rigid: Double = 0.0,
    val allround: Boolean = false, val tipHalfMm: Double = 0.0, val thinMm: Double = 0.0,
    val fingers: Double? = null, val rag: Double? = null, val arc: Double? = null, val sparse: Double? = null,
    val trowel: Boolean = false, val fillDips: Double? = null,
    val blade: Boolean = false, val orient: EdgeMode = EdgeMode.PEN, val press: PressMode? = null,
    val face: FaceMode? = null, val edgeHalfMm: Double? = null, val edgeMinMm: Double = 0.0,
    val acrossMaxMm: Double = 0.0, val riseMaxMm: Double? = null, val bladeLenMm: Double = 0.0,
    val bladeHalfMm: Double = 0.0, val bladeHmax: Double = 0.0, val bead: Double = 0.0, val bladeCap: Double = 0.0,
    val film: Double? = null, val clean: Boolean = false, val pressIn: Double? = null,
)

object PasteBrushes {
    val ALL: Map<String, PasteBrush> = linkedMapOf(
        // The all-rounder: from a hairline tip to a full belly on pressure and tilt, like the pencil.
        "All-round" to PasteBrush(shape = 0, allround = true, tipHalfMm = 0.08, widthMm = 6.0, lenMm = 8.0, thickMm = 0.45,
            thinMm = 0.04, scrape = 0.4, hairDepth = 0.2, ridge = 0.25, rate = 2.4, mix = 0.12, swap = 0.5, loadLenMm = 160.0,
            wick = 0.003, bow = 0.35, lump = 0.08, rigid = 0.0, fingers = 0.0, rag = 0.35),
        "Oil flat" to PasteBrush(shape = 1, widthMm = 7.0, lenMm = 6.0, thickMm = 0.6, scrape = 0.5, hairDepth = 0.4, ridge = 0.5,
            rate = 2.5, mix = 0.12, swap = 0.7, loadLenMm = 140.0, wick = 0.003, bow = 0.6, lump = 0.2, rigid = 0.0, fingers = 1.0),
        "Oil round" to PasteBrush(shape = 0, widthMm = 4.5, lenMm = 7.0, thickMm = 0.5, scrape = 0.5, hairDepth = 0.35, ridge = 0.35,
            rate = 2.2, mix = 0.12, swap = 0.6, loadLenMm = 120.0, wick = 0.003, bow = 0.5, lump = 0.12, rigid = 0.0, fingers = 0.4),
        // A fan: hair tips on an arc, spread apart; it feathers and blends rather than lays paint.
        "Fan blender" to PasteBrush(shape = 1, widthMm = 14.0, lenMm = 2.5, thickMm = 0.1, scrape = 0.3, hairDepth = 0.95, ridge = 0.0,
            rate = 1.2, mix = 0.45, swap = 0.3, loadLenMm = 60.0, wick = 0.002, bow = 0.1, lump = 0.0, rigid = 0.0, fingers = 0.5,
            arc = 0.55, sparse = 0.6),
        // The trowel knife of v6/v7, back by the owner's request (2026-10-07: "the old one had excellent texture").
        "Palette knife 1" to PasteBrush(shape = 2, trowel = true, widthMm = 9.0, lenMm = 13.0, thickMm = 1.1, scrape = 0.99,
            hairDepth = 0.0, ridge = 1.0, rate = 4.0, mix = 0.2, swap = 0.5, loadLenMm = 70.0, wick = 0.0, bow = 0.9, lump = 0.25,
            rigid = 1.0, fillDips = 1.0),
        // Knife v3 (owner's spec, 2026-10-07): lean sets the edge, tilt the size and taper, pressure the paint load.
        "Palette knife 2" to PasteBrush(shape = 2, blade = true, orient = EdgeMode.PEN, press = PressMode.LOAD, face = FaceMode.EDGE,
            edgeHalfMm = 0.6, edgeMinMm = 3.0, acrossMaxMm = 16.0, riseMaxMm = 0.9, bladeLenMm = 22.0, bladeHalfMm = 4.5,
            bladeHmax = 0.9, bead = 0.35, bladeCap = 20.0, thickMm = 1.1, rate = 4.0, loadLenMm = 70.0, mix = 0.2, swap = 0.0,
            scrape = 0.0, hairDepth = 0.0, ridge = 0.0, wick = 0.0, bow = 0.0, lump = 0.25, rigid = 1.0),
        // An empty knife with a thin edge: Palette knife 2's rules, but it scrapes paint OFF; pressure is how deep.
        "Scraper" to PasteBrush(shape = 3, blade = true, orient = EdgeMode.PEN, press = PressMode.DEPTH, edgeMinMm = 2.0,
            acrossMaxMm = 10.0, riseMaxMm = 0.6, bladeLenMm = 14.0, bladeHalfMm = 0.35, bladeHmax = 0.6, bead = 0.5, bladeCap = 3.0,
            film = 0.002, thickMm = 0.0, rate = 5.0, loadLenMm = 40.0, mix = 0.0, swap = 0.0, scrape = 0.0, hairDepth = 0.0,
            ridge = 0.0, wick = 0.0, bow = 0.0, lump = 0.0, rigid = 1.0, clean = true),
    )
}

/** Opaque paint: the colour it covers with when thick. Strong scattering; K from the KM masstone. */
class OpaquePaint(val k: DoubleArray, val s: Double)

fun opaquePaint(rgb: DoubleArray): OpaquePaint {
    val s = 30.0   // per mm: 0.1 mm already hides most of what is under it
    val k = DoubleArray(3) {
        val c = rgb[it]
        val lin = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        val r = min(0.95, max(0.06, lin))   // real paints never reflect under ~6 % in any channel
        s * (1 - r) * (1 - r) / (2 * r)
    }
    return OpaquePaint(k, s)
}

fun cellCap(brush: PasteBrush): Double =
    if (brush.blade) brush.bladeCap else brush.thickMm * brush.rate * brush.loadLenMm / brush.lenMm

/** v8's blade angle from the tilt fraction (an S-curve; kept for the record and the lab's tests). */
fun bladeTan(t: Double): Double = tan(75 * PI / 180 * (1 - min(1.0, max(0.0, t))).pow(3))

/** One step of a paste brush, in the units jb_paste*.frag read (doc px for x/y, mm for sizes). */
data class PasteStep(
    val x: Double, val y: Double,
    val wDirX: Double, val wDirY: Double, val lDirX: Double, val lDirY: Double,
    val halfW: Double, val len: Double, val lenMax: Double, val pressure: Double, val slideMm: Double,
    val fingers: Double? = null, val thick: Double? = null,
    // Blades only:
    val bladeDirX: Double = 1.0, val bladeDirY: Double = 0.0, val bladeLen: Double? = null, val bladeHalf: Double? = null,
    val travelX: Double = 1.0, val travelY: Double = 0.0, val bladeH0: Double? = null, val bladeTan: Double? = null,
    val pressK: Double? = null, val loadMode: Boolean = false,
)

/**
 * A paste stroke: samples in, steps out. [layerScale] = layer px per doc px of the layer painted (a zoomed vector
 * view is > 1); steps stay finer than one LAYER pixel so no step shows as an edge at any zoom.
 */
class PasteStroke(
    private val brush: PasteBrush,
    private val pxPerMm: Double,
    private val layerScale: Double = 1.0,
    edge: EdgeMode? = null,
    press: PressMode? = null,
    face: FaceMode? = null,
    body: Double = 1.0,
) {
    val edge: EdgeMode = edge ?: brush.orient
    val press: PressMode = if (brush.clean) PressMode.DEPTH else press ?: brush.press ?: PressMode.LOAD
    val face: FaceMode = if (brush.edgeHalfMm != null) face ?: brush.face ?: FaceMode.EDGE else FaceMode.FLAT
    /** Thinned paint lays a thinner body (THINNER in Wet.kt). */
    var body: Double = body
    private val half: Double = if (this.face == FaceMode.EDGE && brush.edgeHalfMm != null) brush.edgeHalfMm else brush.bladeHalfMm
    private val steps = ArrayList<PasteStep>()
    private var last: MediaSample? = null
    private var dirX = 0.0
    private var dirY = 0.0
    private var hasDir = false
    private var carry = 0.0
    private var travelled = 0.0   // mm since touchdown: the hairs can only trail over the path already drawn
    private var lastStepT = 0.0

    fun add(s: MediaSample) {
        val prev = last
        last = s
        val b = brush
        if (prev == null) { lastStepT = s.t; return }
        val dx = s.x - prev.x
        val dy = s.y - prev.y
        val len = hypot(dx, dy)
        if (b.trowel) {
            // The trowel knife steers as it did in v6/v7: it turns with each report and only lays paint as it moves.
            if (len < 1e-6) return
            val tx = dx / len
            val ty = dy / len
            if (hasDir) { val n = norm2(dirX * 0.6 + tx * 0.4, dirY * 0.6 + ty * 0.4); dirX = n[0]; dirY = n[1] }
            else { dirX = tx; dirY = ty; hasDir = true }
        } else if (len > 1e-6) {
            // The hairs swing round over about 1.5 mm of travel, not per pen report.
            val tx = dx / len
            val ty = dy / len
            if (hasDir) {
                val k = 1 - exp(-(len / pxPerMm) / 1.5)
                val n = norm2(dirX * (1 - k) + tx * k, dirY * (1 - k) + ty * k)
                dirX = n[0]; dirY = n[1]
            } else { dirX = tx; dirY = ty; hasDir = true }
        }
        // Small hops: a thin blade edge needs hops finer than its width (else a zip of ribs).
        val stepMm = if (b.blade) min(0.3, 0.6 * half * 0.35) else 0.3
        val stepPx = max(1 / layerScale, stepMm * pxPerMm / layerScale)
        fun interp(w: Double) = MediaSample(
            prev.x + dx * w, prev.y + dy * w, prev.p + (s.p - prev.p) * w, prev.tilt + (s.tilt - prev.tilt) * w,
            prev.az + angleDelta(prev.az, s.az) * w, prev.t + (s.t - prev.t) * w,
        )
        var pos = stepPx - carry
        var stepped = false
        while (pos <= len) {
            travelled += stepPx / pxPerMm
            push(interp(pos / len), stepPx / pxPerMm)
            stepped = true
            pos += stepPx
        }
        carry = len - (pos - stepPx)
        // A brush held still (a dab, or the start of a stroke) keeps laying paint where it is.
        val now = s.t
        if (!stepped && !b.trowel && now - lastStepT >= 16) push(s, 0.04 * min(4.0, (now - lastStepT) / 16))
        if (stepped || now - lastStepT >= 16) lastStepT = now
    }

    private fun push(s: MediaSample, slideMm: Double) {
        val b = brush
        val p = max(0.0, min(1.0, s.p))
        val tiltFrac = min(1.0, s.tilt / (60 * PI / 180))
        // Before the brush has moved it has no direction: lean it along the pen's lean until the drag takes over.
        val dX = if (hasDir) dirX else -cos(s.az)
        val dY = if (hasDir) dirY else -sin(s.az)
        val lX = -dX
        val lY = -dY                  // the tips trail the handle
        val wX = -lY
        val wY = lX
        val tr = travelled
        if (b.trowel) {
            // v6/v7 trowel geometry: width barely changes with pressure, length grows with it; tilt does nothing.
            steps.add(PasteStep(s.x, s.y, wX, wY, lX, lY, b.widthMm / 2 * (0.9 + 0.1 * p), b.lenMm * (0.6 + 0.4 * p),
                b.lenMm * 1.4, p, slideMm, fingers = 0.0))
            return
        }
        if (b.blade) {
            // All linear: tilt sets how much edge is in play and the gouge taper; pressure raises (load) or lowers
            // (depth) the underside.
            val t = min(1.0, max(0.0, s.tilt / (PI / 2)))
            val load = press == PressMode.LOAD
            val h0 = b.bladeHmax * (if (load) p else 1 - p)
            val pressK = if (load) 0.0 else p
            if (edge == EdgeMode.ACROSS) {
                // Squeegee: the edge lies across the travel, centred on the pen; lean lays more of it down.
                val l = b.edgeMinMm + (b.acrossMaxMm - b.edgeMinMm) * t
                val aX = -dY
                val aY = dX
                val k = 0.5 * l * pxPerMm
                steps.add(PasteStep(s.x - aX * k, s.y - aY * k, wX, wY, lX, lY, half, l, l, p, slideMm,
                    bladeDirX = aX, bladeDirY = aY, bladeLen = l, bladeHalf = half, travelX = dX, travelY = dY,
                    bladeH0 = h0, bladeTan = 0.0, pressK = pressK, loadMode = load))
                return
            }
            // From the pen point: along the pen's lean ('pen') or trailing along the path ('along').
            val l = b.edgeMinMm + (b.bladeLenMm - b.edgeMinMm) * t
            // The gouge tapers over the WHOLE edge in play: deepest at the point, rising riseMax·(1 − t) at the far end.
            val rise = (b.riseMaxMm ?: 0.8) * (1 - t) / max(l, 0.5)
            val bX = if (edge == EdgeMode.ALONG) -dX else cos(s.az)
            val bY = if (edge == EdgeMode.ALONG) -dY else sin(s.az)
            steps.add(PasteStep(s.x, s.y, wX, wY, lX, lY, half, l, l, p, slideMm,
                bladeDirX = bX, bladeDirY = bY, bladeLen = l, bladeHalf = half, travelX = dX, travelY = dY,
                bladeH0 = h0, bladeTan = rise, pressK = pressK, loadMode = load))
            return
        }
        // Splayed hairs come in only after the brush has travelled a little, never on touchdown.
        val fingers = (b.fingers ?: 1.0) * smoothstep(2.0, 6.0, tr)
        val halfW: Double
        val lenFull: Double
        var thick: Double? = null
        if (b.allround) {
            // From a hairline tip to the full belly: pressure gathers the hairs down, tilt lays the belly over.
            val k = p.pow(1.6)
            halfW = (b.tipHalfMm + (b.widthMm / 2 - b.tipHalfMm) * k) * (1 + 0.35 * tiltFrac)
            lenFull = (0.35 + (b.lenMm - 0.35) * k) * (1 + 0.4 * tiltFrac)
            thick = b.thinMm + (b.thickMm - b.thinMm) * k
        } else {
            halfW = b.widthMm / 2 * (if (b.shape == 0) 0.55 + 0.45 * sqrt(p) else 0.9 + 0.1 * p) * (1 + 0.2 * tiltFrac)
            lenFull = b.lenMm * (0.25 + 0.75 * p.pow(0.8)) * (1 + 0.4 * tiltFrac)
        }
        // On touchdown a round is a dot and a flat its pressed chisel; the hairs trail out as far as it travelled.
        val minLen = if (b.shape == 0) 2 * halfW else 0.5 * halfW
        val lenMax = max(minLen, b.lenMm * 1.4)
        val len = min(lenMax, max(minLen, min(lenFull, minLen + 0.8 * tr)))
        val th = (thick ?: b.thickMm) * body
        steps.add(PasteStep(s.x, s.y, wX, wY, lX, lY, halfW, len, lenMax, p, slideMm, fingers = fingers,
            thick = if (thick != null || body != 1.0) th else null))
    }

    fun take(): List<PasteStep> = ArrayList(steps).also { steps.clear() }
}
