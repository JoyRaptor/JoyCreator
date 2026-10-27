package cc.joycreator.joybrush.core.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tight paste rectangle must (1) never be larger than the old square, and (2) contain every point where the dab
 * shader can change the canvas. (2) is checked against a CPU port of the shader's SUPPORT predicate only (where
 * `cover > 0` in jb_paste.glsl `jb_pasteCover`, or `cov > 0` in `jb_bladeCover`), not of the paint exchange: outside
 * the support the shader hands back its input, so nothing else matters for bit-exactness.
 *
 * The shader's noise terms (`jb_pasteRag`, the flat brush's lead noise) are not reproduced; the predicate is evaluated
 * at their extremes (and middle) instead, and a point counts as supported if ANY of those combinations covers it. That
 * is a superset of the real support, so containing it is stricter than containing the real thing. `u_sparse` and
 * `u_fingers` only multiply the coverage by a factor <= 1 and are left out for the same reason.
 * The GPU parity of the real shaders is in tools/media_paste_rect_parity_check.js.
 */
class PasteFootprintTest {
    private val ppm = DOC_PX_PER_MM

    // ---- CPU port of the support predicate ----
    // A loaded brush's landing is more ragged (jb_paste.glsl JB_LAND_RAG x u_land); its other ends only shrink the support
    // (they move the front and the tips inward), so the clean-cut predicate below stays a superset for them.
    private fun rawRagMax(st: PasteStep, b: PasteBrush) =
        if (b.shape > 1) 0.14 * 0.5 + 0.05 * 0.5
        else (if (b.loadedEnds) 1 + PasteFootprint.LAND_RAG * st.land else 1.0) * abs(b.rag ?: 1.0) * (0.10 * 0.5 + 0.06 * 0.5)

    /** `jb_pasteCover(a, b, px / halfW, px) > 0` for the hair/trowel/edge path, with `rag` and the flat lead noise given. */
    private fun hairCovers(st: PasteStep, br: PasteBrush, px: Double, wMm: Double, lMm: Double, rag: Double, leadNoise: Double): Boolean {
        // A loaded brush planted hard splays its hairs wider (jb_paste.glsl jb_pasteHalfW; at most LAND_SPLAY x u_land).
        val hw = max(st.halfW * (if (br.loadedEnds && br.shape <= 1) 1 + PasteFootprint.LAND_SPLAY * st.land else 1.0), 1e-3)
        val a = wMm / hw * (1 - rag)            // dab.frag 77-78
        var b = lMm                             // dab.frag 79
        val aaA = px / hw; val aaB = px          // dab.frag 82
        val len = st.len
        if (b < -aaB || b > len + aaB) return false   // glsl 73
        var t = (b / max(len, 1e-3)).coerceIn(0.0, 1.0)
        var half: Double
        if (br.shape < 1) {
            val r = min(st.halfW, 0.5 * len)
            if (b < r) {
                val k = (r - b) / max(r, 1e-3)
                half = sqrt(max(0.0, 1 - k * k)) * (r / max(st.halfW, 1e-3))
                if (r >= st.halfW * 0.999) half = sqrt(max(0.0, 1 - k * k))
            } else {
                val tt = ((b - r) / max(len - r, 1e-3)).coerceIn(0.0, 1.0)
                half = sqrt(max(0.0, 1 - tt * tt * tt))
            }
        } else if (br.shape < 2) {
            val lead = ((br.arc ?: 0.18) * a * a + 0.06 * (leadNoise - 0.5)) * st.halfW
            b -= lead
            if (b < -aaB) return false
            t = (b / max(len, 1e-3)).coerceIn(0.0, 1.0)
            half = 1 - 0.45 * smoothstep(0.6, 1.0, t) * smoothstep(0.6, 1.0, t)
        } else {
            half = if (br.shape > 2) sqrt(max(0.0, 1 - t * t)) else 1 - 0.45 * smoothstep(0.6, 1.0, t) * smoothstep(0.6, 1.0, t)
        }
        val side = smoothstep(half + aaA, half - aaA, abs(a))
        if (side <= 0.0) return false
        val aaE = max(aaB, 1.2 * st.slideMm)
        val ends = smoothstep(-aaE, aaE, b) * smoothstep(len + aaE, len - aaE, b)
        return side * ends > 0.0
    }

    /** `jb_bladeCover(...) > 0` for a point at blade-frame (xb, yb) mm. */
    private fun bladeCovers(st: PasteStep, br: PasteBrush, px: Double, xb: Double, yb: Double): Boolean {
        val hw = st.bladeHalf ?: br.bladeHalfMm.takeIf { it != 0.0 } ?: 1.0
        val bl = st.bladeLen ?: br.bladeLenMm.takeIf { it != 0.0 } ?: 1.0
        val half = hw * (0.35 + 0.65 * smoothstep(0.0, 2.5 * hw + 1.0, xb))
        val aaS = max(px, 1.2 * st.slideMm)
        val along = smoothstep(-0.3 * half - aaS, -0.3 * half + aaS, xb) * smoothstep(bl + aaS, bl - aaS, xb)
        return along * smoothstep(half + aaS, half - aaS, abs(yb)) > 0.0
    }

    class Result(val inside: Int, val outsideBox: Int, val outsideLegacy: Int, val supported: Int, val worstSlackPx: Double)

    /** Samples the support on a fine grid in the step's own frame, maps each hit to doc px and tests it against the rects. */
    private fun check(st: PasteStep, br: PasteBrush, layerScale: Double, what: String, du: Double = 0.04, dv: Double = 0.08): Result {
        val px = 1.0 / (ppm * layerScale)
        val sup = PasteFootprint.supportBounds(st, br, layerScale, ppm) ?: error("$what: no support bounds")
        val rect = PasteFootprint.docBounds(st, br, layerScale, ppm)
        val old = PasteFootprint.legacyBounds(st, br, ppm)
        // (2a) never larger than the old square, on every side and in area.
        assertTrue(rect[0] >= old[0] - 1e-9 && rect[1] >= old[1] - 1e-9 && rect[2] <= old[2] + 1e-9 && rect[3] <= old[3] + 1e-9, "$what: rect leaves the old square")
        assertTrue((rect[2] - rect[0]) * (rect[3] - rect[1]) <= (old[2] - old[0]) * (old[3] - old[1]) + 1e-6, "$what: area grew")
        val blade = br.blade
        // The loop below walks (u = across, v = along); a blade's "across" is the perpendicular of its edge direction.
        val ux = if (blade) -st.bladeDirY else st.wDirX; val uy = if (blade) st.bladeDirX else st.wDirY
        val vx = if (blade) st.bladeDirX else st.lDirX; val vy = if (blade) st.bladeDirY else st.lDirY
        val ragMax = rawRagMax(st, br)
        val rags = doubleArrayOf(-ragMax, 0.0, ragMax)
        val leads = doubleArrayOf(0.0, 0.5, 1.0)
        // Grid wide enough to include the box and well beyond it.
        val lo = -(max(st.halfW, st.bladeHalf ?: br.bladeHalfMm) * 1.6 + 1.5)
        val hiV = max(st.len, st.bladeLen ?: br.bladeLenMm) + 1.5
        var inside = 0; var outBox = 0; var outLegacy = 0; var n = 0
        var worst = Double.POSITIVE_INFINITY
        var u = lo
        while (u <= -lo) {
            var v = -1.5
            while (v <= hiV) {
                val hit = if (blade) bladeCovers(st, br, px, v, u) else rags.any { r -> leads.any { l -> hairCovers(st, br, px, u, v, r, l) } }
                if (hit) {
                    n++
                    val x = st.x + ppm * (u * ux + v * vx); val y = st.y + ppm * (u * uy + v * vy)
                    // slack (doc px) to the unclamped support box, per side; negative = outside
                    val slack = min(min(x - sup[0], sup[2] - x), min(y - sup[1], sup[3] - y))
                    worst = min(worst, slack)
                    if (slack < 0) outBox++
                    val inOld = x >= old[0] && x <= old[2] && y >= old[1] && y <= old[3]
                    if (!inOld) outLegacy++
                    else if (!(x >= rect[0] && x <= rect[2] && y >= rect[1] && y <= rect[3])) fail("$what: supported point ($x, $y) in old square but outside the new rect ${rect.toList()}")
                    else inside++
                }
                v += dv
            }
            u += du
        }
        return Result(inside, outBox, outLegacy, n, worst)
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)

    // ---- steps ----
    private fun stroke(br: PasteBrush, angle: Double, p: Double, tilt: Double, az: Double, layerScale: Double = 1.0, edge: EdgeMode? = null): List<PasteStep> {
        val s = PasteStroke(br, ppm, layerScale, edge = edge)
        val dx = cos(angle); val dy = sin(angle)
        var t = 0.0
        for (i in 0..40) s.add(MediaSample(400 + dx * i * ppm, 400 + dy * i * ppm, p, tilt, az, t.also { t += 16.0 }))
        val all = s.take()
        // First steps (touchdown: short, round dot), a mid-stroke step, and the last.
        return listOfNotNull(all.firstOrNull(), all.getOrNull(all.size / 3), all.getOrNull(all.size / 2), all.lastOrNull())
    }

    @Test fun everyBrushEveryDirectionAndPressureContainsItsSupport() {
        var steps = 0; var points = 0; var beyondLegacy = 0
        for ((name, br) in PasteBrushes.ALL) {
            val edges: List<EdgeMode?> = if (br.blade) EdgeMode.entries else listOf(null)
            for (edge in edges) for (angle in listOf(0.0, 0.4, 2.2, -2.6)) {
                for (p in listOf(0.0, 0.55, 1.0)) for ((tilt, az) in listOf(0.0 to 0.0, 1.1 to -2.0, PI / 2 to 3.0)) {
                    for (scale in listOf(1.0, 2.0)) for (s in stroke(br, angle, p, tilt, az, scale, edge)) {
                        val r = check(s, br, scale, "$name edge=$edge angle=$angle p=$p tilt=$tilt scale=$scale", du = 0.1, dv = 0.12)
                        assertEquals(0, r.outsideBox, "$name: ${r.outsideBox} supported points outside the unclamped box (worst slack ${r.worstSlackPx})")
                        // The old square is not a perfect bound either: at the extreme of the edge noise (a fan: rag 1 => ~9% of a
                        // 7 mm half-width, more than the square's 0.3 mm margin) a few samples fall outside it. The new rect is
                        // clipped to the square, so those pixels were never drawn before and still are not (bit-exact); counted here.
                        beyondLegacy += r.outsideLegacy
                        steps++; points += r.supported
                    }
                }
            }
        }
        assertTrue(steps > 1000 && points > 100_000, "the sweep must be non-vacuous ($steps steps, $points supported samples)")
        assertTrue(beyondLegacy < points / 1000, "the old square should hold (nearly) all the support: $beyondLegacy of $points samples lie beyond it")
        println("PasteFootprintTest sweep: $steps steps, $points supported samples, $beyondLegacy beyond the old square")
    }

    @Test fun syntheticFootprintsIncludingExtremeRagArcAndSlide() {
        val base = PasteStep(500.0, 500.0, 0.0, 1.0, -1.0, 0.0, 2.0, 5.0, 9.0, 0.6, 0.3)
        var n = 0
        for (shape in 0..3) for (rag in listOf(0.0, 0.35, 1.0, 1.5)) for (arc in listOf(0.0, 0.18, 0.55, 1.2)) for (theta in listOf(0.0, 0.7, 2.0, -1.4)) {
            val c = cos(theta); val s = sin(theta)
            for ((hw, len, slide) in listOf(Triple(0.05, 0.3, 0.04), Triple(1.0, 9.0, 0.3), Triple(7.0, 2.5, 1.0), Triple(3.0, 3.0, 0.3), Triple(0.001, 1.0, 2.0))) {
                val br = PasteBrush(shape = shape, widthMm = 2 * hw, lenMm = len, rag = rag, arc = arc, sparse = if (arc > 1.0) 0.6 else 0.0)
                val st = base.copy(wDirX = -s, wDirY = c, lDirX = c, lDirY = s, halfW = hw, len = len, lenMax = max(len, 1.4 * len), slideMm = slide)
                val r = check(st, br, 1.0, "shape=$shape rag=$rag arc=$arc theta=$theta hw=$hw len=$len slide=$slide")
                assertEquals(0, r.outsideBox, "synthetic shape=$shape rag=$rag arc=$arc hw=$hw len=$len slide=$slide: ${r.outsideBox} points outside (worst slack ${r.worstSlackPx})")
                n++
            }
        }
        assertEquals(4 * 4 * 4 * 4 * 5, n)
    }

    @Test fun syntheticBlades() {
        for (brName in listOf("Palette knife 2", "Scraper")) {
            val br = PasteBrushes.ALL.getValue(brName)
            for (theta in listOf(0.0, 0.9, 2.5, -2.0)) for (len in listOf(0.5, 3.0, 14.0, 22.0)) for (half in listOf(0.2, 0.35, 0.6, 4.5)) for (slide in listOf(0.01, 0.3, 1.5)) for (scale in listOf(0.5, 1.0, 2.0)) {
                val st = PasteStep(300.0, 700.0, 0.0, 1.0, 1.0, 0.0, half, len, len, 0.5, slide,
                    bladeDirX = cos(theta), bladeDirY = sin(theta), bladeLen = len, bladeHalf = half, travelX = 1.0, travelY = 0.0, bladeH0 = 0.1, bladeTan = 0.0)
                val r = check(st, br, scale, "$brName theta=$theta len=$len half=$half slide=$slide scale=$scale")
                assertEquals(0, r.outsideBox, "$brName len=$len half=$half slide=$slide scale=$scale: ${r.outsideBox} points outside (worst slack ${r.worstSlackPx})")
                // (This synthetic set can ask for a blade longer than the brush's own bladeLenMm, which the old square
                // never covered: only the unclamped box is asserted here, the clamped one is never larger.)
            }
        }
    }

    @Test fun controlAShrunkBoxIsDetected() {
        // The test must be able to fail: shrink the oil round's box by 30 px and the sampled support leaves it.
        val br = PasteBrushes.ALL.getValue("Oil round")
        val st = stroke(br, 0.4, 0.6, 0.3, 1.0).last()
        val good = PasteFootprint.supportBounds(st, br, 1.0, ppm)!!
        val bad = doubleArrayOf(good[0] + 30, good[1] + 30, good[2] - 30, good[3] - 30)
        val px = 1.0 / ppm
        var outside = 0
        var u = -4.0
        while (u <= 4.0) {
            var v = -0.5
            while (v <= st.len + 0.5) {
                if (listOf(-0.08, 0.0, 0.08).any { r -> hairCovers(st, br, px, u, v, r, 0.5) }) {
                    val x = st.x + ppm * (u * st.wDirX + v * st.lDirX); val y = st.y + ppm * (u * st.wDirY + v * st.lDirY)
                    if (x < bad[0] || x > bad[2] || y < bad[1] || y > bad[3]) outside++
                }
                v += 0.08
            }
            u += 0.04
        }
        assertTrue(outside > 100, "a 30 px shrink must lose support samples (lost $outside)")
    }

    @Test fun oilRoundRectIsMuchSmallerThanTheSquare() {
        val br = PasteBrushes.ALL.getValue("Oil round")
        var oldSum = 0.0; var newSum = 0.0
        for (angle in listOf(0.0, 0.4, PI / 4, PI / 2, 2.2)) for (s in stroke(br, angle, 0.5, 0.0, 0.0).takeLast(2)) {
            val o = PasteFootprint.legacyBounds(s, br, ppm); val r = PasteFootprint.docBounds(s, br, 1.0, ppm)
            oldSum += (o[2] - o[0]) * (o[3] - o[1]); newSum += (r[2] - r[0]) * (r[3] - r[1])
        }
        assertTrue(newSum < oldSum / 2, "the oil round rect should be less than half of the old square (old $oldSum new $newSum)")
    }

    /**
     * Pins tools/media_paste_rect_parity_check.js (which re-implements PasteFootprint in JS so it can run without a JVM) to
     * this code: the same literal steps, the bounds the checker's `--anchors` prints. If either side changes, this fails.
     */
    @Test fun anchorsMatchTheGpuCheckerPort() {
        fun hair(x: Double, y: Double, wx: Double, wy: Double, lx: Double, ly: Double, hw: Double, len: Double, lenMax: Double) =
            PasteStep(x, y, wx, wy, lx, ly, hw, len, lenMax, 0.5, 0.3)
        fun blade(x: Double, y: Double, half: Double, len: Double, slide: Double, bx: Double, by: Double) =
            PasteStep(x, y, 0.0, 1.0, -1.0, 0.0, half, len, len, 0.5, slide, bladeDirX = bx, bladeDirY = by, bladeLen = len, bladeHalf = half)
        val cases = listOf(
            Triple("Oil round", 1.0, hair(500.0, 500.0, 0.6, 0.8, -0.8, 0.6, 1.95, 4.0, 9.8)) to doubleArrayOf(407.113, 462.6174, 528.887, 585.3826),
            Triple("Oil flat", 1.0, hair(400.0, 300.0, 0.0, 1.0, -1.0, 0.0, 3.4, 4.2, 8.4)) to doubleArrayOf(313.0, 223.0, 403.0, 377.0),
            Triple("Fan blender", 2.0, hair(250.0, 260.0, -0.28, 0.96, -0.96, -0.28, 6.8, 1.8, 3.5)) to doubleArrayOf(172.4165, 106.3452, 293.0235, 403.5748),
            Triple("Palette knife 1", 1.0, hair(120.0, 880.0, 0.6, -0.8, 0.8, 0.6, 4.4, 10.0, 18.2)) to doubleArrayOf(58.1945, 798.726, 341.8055, 1081.274),
            Triple("Palette knife 2", 1.0, blade(700.0, 640.0, 0.6, 12.0, 0.126, 0.8, 0.6)) to doubleArrayOf(685.5584, 623.4104, 905.4336, 799.8336),
            Triple("Scraper", 0.5, blade(333.0, 444.0, 0.35, 9.0, 0.0735, -0.6, 0.8)) to doubleArrayOf(212.6, 432.412, 345.841, 599.0),
        )
        for ((c, want) in cases) {
            val (name, scale, st) = c
            val got = PasteFootprint.docBounds(st, PasteBrushes.ALL.getValue(name), scale, ppm)
            for (i in 0..3) assertEquals(want[i], got[i], 5e-4, "$name bound $i: got ${got.toList()}")
        }
    }

    @Test fun degenerateFrameFallsBackToTheOldSquare() {
        val br = PasteBrushes.ALL.getValue("Oil round")
        val st = PasteStep(100.0, 100.0, 1.0, 0.0, 1.0, 0.0, 2.0, 5.0, 9.0, 0.5, 0.3)   // wDir parallel to lDir: not a frame
        assertEquals(PasteFootprint.legacyBounds(st, br, ppm).toList(), PasteFootprint.docBounds(st, br, 1.0, ppm).toList())
        assertEquals(null, PasteFootprint.supportBounds(st, br, 1.0, ppm))
    }
}
