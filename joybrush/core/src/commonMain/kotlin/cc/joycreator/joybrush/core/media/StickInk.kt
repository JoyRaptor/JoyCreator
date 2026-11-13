package cc.joycreator.joybrush.core.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// The colour a dry stick lays (the owner, 2026-11-13: "I was not able to change it to be any other color than the
// black that it had. It should be able to be selected to any color, just like any other brush").
//
// Graphite is opaque grey FLAKES: the paper store keeps one number for their brightness, so it has nowhere to hold a
// hue. A coloured stick therefore lays its colour as a pigment film in the paint planes (K·X, S·X, where dried
// watercolour and oil live), in the same grain and the same amounts the graphite would have gone down in, and marks
// that share of the deposit as tinted so the display does not also draw it as grey flakes. A grey, white or black
// stick stays flakes, lighter or darker. Black is the stick's own graphite, exactly as before.
// Twin of lab/media/js/stick.js stickInk; the GPU half is jb_media_apply.frag (u_inkK, u_inkS, u_inkTint).

/**
 * What one stroke of a dry stick lays. [flakeR]: the flakes' brightness (linear). [k], [s]: the pigment film a full
 * unit of deposit adds, per mm of deposit (zero for graphite). [tint]: 1 when the deposit is a colour, 0 when flakes.
 */
class StickInk(val flakeR: Double, val k: DoubleArray, val s: DoubleArray, val tint: Double) {
    val tinted: Boolean get() = tint > 0.0

    companion object {
        /** The stick's own graphite: what every pencil stroke was before a pencil took a colour. */
        fun graphite(flakeR: Double) = StickInk(flakeR, DoubleArray(3), DoubleArray(3), 0.0)
    }
}

/** A colour whose three channels are this close (sRGB, 0..1) is a grey: it stays graphite flakes. */
const val STICK_GREY_CHROMA = 0.02

/**
 * The film's scattering per mm of deposit. The display covers paper with flakes by area as 1 − exp(−V / (0.18 · cap))
 * (jb_media_render.frag), cap being [STICK_LOOK_CAP_MM]; this makes a coloured deposit hide the paper about as fast.
 */
const val STICK_LOOK_CAP_MM = 0.004
const val STICK_INK_S_PER_MM = 1.2 / (0.18 * STICK_LOOK_CAP_MM)

private fun linear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

/**
 * The ink for a stick whose own flakes reflect [stickFlakeR], laying the colour [rgb] (sRGB, 0..1 each).
 * Black gives the stick's graphite to the bit; a grey lifts the flakes toward that grey (white is a white pencil);
 * a colour is a film whose thick layer is that colour (Kubelka–Munk: K / S = (1 − R)² / 2R).
 */
fun stickInk(rgb: DoubleArray, stickFlakeR: Double): StickInk {
    val hi = max(rgb[0], max(rgb[1], rgb[2]))
    val lo = min(rgb[0], min(rgb[1], rgb[2]))
    if (hi - lo < STICK_GREY_CHROMA) {
        val grey = linear((rgb[0] + rgb[1] + rgb[2]) / 3)
        return StickInk.graphite(stickFlakeR + (1 - stickFlakeR) * grey)
    }
    val s = STICK_INK_S_PER_MM
    val k = DoubleArray(3) {
        val r = min(0.95, max(0.02, linear(rgb[it])))
        s * (1 - r) * (1 - r) / (2 * r)
    }
    return StickInk(stickFlakeR, k, DoubleArray(3) { s }, 1.0)
}
