package cc.joycreator.joybrush.core.paper

import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Resolution-exact paper for export; the screen-only detail octave never changes saved pixels. */
object PaperRaster {
    const val RELIEF_GAIN = 6f
    data class LocalFrame(
        val hexBaseX: Int, val hexBaseY: Int,
        val localOriginX: Double, val localOriginY: Double,
        val baseCentreModX: Double, val baseCentreModY: Double,
    )

    /** Rebase at a lattice vertex, preserving the global hash rather than repeating the canvas. */
    fun localFrame(docX: Double, docY: Double, texelPx: Double, scale: Double, hexTexels: Double, size: Int): LocalFrame {
        require(texelPx > 0 && scale > 0 && hexTexels > 0 && size > 0)
        val x = docX / (texelPx * scale); val y = docY / (texelPx * scale)
        val lattice = HexTile.lattice(x, y, hexTexels)
        val i = lattice.vi[0]; val j = lattice.vj[0]
        val cx = HexTile.centreX(i, j, hexTexels); val cy = HexTile.centreY(i, j, hexTexels)
        fun mod(v: Double) = v - floor(v / size) * size
        return LocalFrame(i, j, x - cx, y - cy, mod(cx), mod(cy))
    }

    /** Local-coordinate twin for precision tests and callers; offsets are TEXELS from the corner. */
    fun sampleLocal(frame: LocalFrame, tex: PaperTexture, offsetX: Double, offsetY: Double,
                    hexTexels: Double, rotatable: Boolean, out: FloatArray, slopeRange: Float? = null, seed: Int = 0,
                    mean: FloatArray? = null, heightMean: Float = 0.5f) {
        require(out.size >= 4)
        val px = frame.localOriginX + offsetX; val py = frame.localOriginY + offsetY
        val lattice = HexTile.lattice(px, py, hexTexels)
        val weights = FloatArray(3) { lattice.w[it] * lattice.w[it] * lattice.w[it] }
        val total = weights.sum()
        val keep = HexTile.contrastKeep(FloatArray(3) { weights[it] / total })
        out.fill(0f)
        val s = FloatArray(4)
        var spread = 0f
        for (n in 0..2) {
            val di = lattice.vi[n]; val dj = lattice.vj[n]
            val i = frame.hexBaseX + di; val j = frame.hexBaseY + dj
            val cx = HexTile.centreX(di, dj, hexTexels); val cy = HexTile.centreY(di, dj, hexTexels)
            val theta = if (rotatable) HexTile.hash(i, j, seed + 3) * 2.0 * PI else 0.0
            val c = cos(theta); val sn = sin(theta)
            val dx = px - cx; val dy = py - cy
            tex.bilinear(c * dx - sn * dy + cx + frame.baseCentreModX + HexTile.hash(i, j, seed + 1) * tex.w,
                sn * dx + c * dy + cy + frame.baseCentreModY + HexTile.hash(i, j, seed + 2) * tex.h, s)
            if (slopeRange != null) {
                val sx = SurfaceMaps.decodeFilteredSlope(s[0], slopeRange)
                val sy = SurfaceMaps.decodeFilteredSlope(s[1], slopeRange)
                s[0] = (c * sx + sn * sy).toFloat(); s[1] = (-sn * sx + c * sy).toFloat()
            }
            val wn = weights[n] / total
            if (slopeRange != null) {
                // A surface: exactly HexTile.sampleSurface's blend.
                out[0] += wn * s[0]; out[1] += wn * s[1]; out[2] += wn * (s[2] - heightMean)
                spread += wn * wn * (s[3] - s[2] * s[2]).coerceAtLeast(0f)
            } else {
                for (q in 0..3) out[q] += (s[q] - (if (q < 3) mean?.get(q) ?: 0f else 0f)) * wn
            }
        }
        // The same variance-preserving blend as HexTile and the shaders.
        if (slopeRange != null) {
            out[0] *= keep; out[1] *= keep
            out[2] = (heightMean + out[2] * keep).coerceIn(0f, 1f)
            out[3] = out[2] * out[2] + spread * keep * keep
        } else if (mean != null) for (q in 0..2) out[q] = (mean[q] + out[q] * keep).coerceIn(0f, 1f)
    }

    /** Straight, opaque RGBA8 at document pixel centres; default lamp points upper left. */
    fun render(p: ResolvedPaper, look: PaperTexture?, surface: PaperTexture?, rect: RectPx): ByteArray {
        require(rect.w >= 0 && rect.h >= 0)
        val bytes = ByteArray(rect.w * rect.h * 4)
        val base = colour(p.baseArgb)
        val sampled = FloatArray(4); val slopes = FloatArray(4)
        val lookEntry = p.look; val surfEntry = p.surface
        val mean = lookEntry?.mean?.let { colour(0xFF000000.toInt() or it.substring(1).toInt(16)) }
        val tint = p.tintSet
        val lightLength = sqrt(0.45f * 0.45f + 0.55f * 0.55f + 0.70f * 0.70f)
        val lx = -0.45f / lightLength; val ly = -0.55f / lightLength; val lz = 0.70f / lightLength
        for (y in 0 until rect.h) for (x in 0 until rect.w) {
            val dx = rect.x.toDouble() + x + 0.5; val dy = rect.y.toDouble() + y + 0.5
            if (look != null && lookEntry != null) {
                val pitch = lookEntry.texelPx.toDouble() * p.scale
                HexTile.sampleLook(look, dx / pitch, dy / pitch, lookEntry.hexTexels.toDouble(), lookEntry.rotatable, sampled, footprint = 1.0 / pitch, mean = mean)
                if (tint && mean != null) for (q in 0..2) sampled[q] *= base[q] / mean[q].coerceAtLeast(1f / 255f)
            } else for (q in 0..2) sampled[q] = base[q]
            var shade = 1f
            if (p.light && surface != null && surfEntry != null) {
                val pitch = surfEntry.texelPx.toDouble() * p.scale
                HexTile.sampleSurface(surface, dx / pitch, dy / pitch, surfEntry.hexTexels.toDouble(), surfEntry.rotatable, surfEntry.slopeRange, slopes, footprint = 1.0 / pitch, heightMean = surfEntry.heightMean)
                val nx = -slopes[0] / pitch.toFloat() * RELIEF_GAIN * surfEntry.relief
                val ny = -slopes[1] / pitch.toFloat() * RELIEF_GAIN * surfEntry.relief
                val lit = ((nx * lx + ny * ly + lz) / sqrt(nx * nx + ny * ny + 1f) / lz).coerceIn(LIT_MIN, LIT_MAX)
                shade += (lit - 1f) * p.show
            }
            val at = (y * rect.w + x) * 4
            for (q in 0..2) bytes[at + q] = ((base[q] + (sampled[q] - base[q]) * p.show) * shade * 255f).roundToInt().coerceIn(0, 255).toByte()
            bytes[at + 3] = 255.toByte()
        }
        return bytes
    }
    /**
     * How far the relief light may brighten or darken the paper (2026-10-06). It was 0.6..1.4 — a lamp raking across an
     * embossed sheet, which is exactly the "early-90s 3D graphics" look the owner rejected. Real paper under even light
     * shows its relief only faintly; the look picture already carries what the eye sees. Matches jb_paper_bg.frag.
     */
    const val LIT_MIN = 0.9f
    const val LIT_MAX = 1.1f

    private fun colour(argb: Int) = floatArrayOf(((argb ushr 16) and 255) / 255f, ((argb ushr 8) and 255) / 255f, (argb and 255) / 255f)
}