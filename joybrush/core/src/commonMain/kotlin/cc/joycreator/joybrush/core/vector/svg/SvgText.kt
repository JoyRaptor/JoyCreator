package cc.joycreator.joybrush.core.vector.svg

import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.abs
import kotlin.math.floor

/**
 * The text-level helpers of the SVG export: numbers, path data, XML escaping and base64. All pure
 * Kotlin with no locale anywhere, so the same drawing gives the same bytes on every phone and JVM.
 */
internal object SvgText {

    /**
     * [v] rounded to 2 decimals (half up), trailing zeros dropped, never an exponent, never "-0".
     * 2 decimals is 0.01 doc px, a tenth of the 0.1 px simplification. A non-finite value is "0":
     * callers drop non-finite geometry before it gets here, and a NaN must not make an unparsable file.
     */
    fun num(v: Double, sb: StringBuilder) {
        if (!v.isFinite()) { sb.append('0'); return }
        val hundredths = floor(abs(v) * 100.0 + 0.5).toLong()
        if (hundredths == 0L) { sb.append('0'); return }
        if (v < 0) sb.append('-')
        sb.append(hundredths / 100)
        val frac = (hundredths % 100).toInt()
        if (frac != 0) {
            sb.append('.')
            sb.append(frac / 10)
            if (frac % 10 != 0) sb.append(frac % 10)
        }
    }

    fun num(v: Double): String = StringBuilder().also { num(v, it) }.toString()

    /** Opacity: 0..1 to 3 decimals, the same rules as [num]. */
    fun opacity(v: Float): String {
        val x = if (v.isNaN()) 1.0 else v.toDouble().coerceIn(0.0, 1.0)
        val thousandths = floor(x * 1000.0 + 0.5).toLong()
        if (thousandths == 1000L) return "1"
        if (thousandths == 0L) return "0"
        var s = thousandths.toString().padStart(3, '0').trimEnd('0')
        s = "0.$s"
        return s
    }

    /** Path data of closed contours, "M x y C … Z" per contour, all in ONE string for one `<path>`. */
    fun pathData(contours: List<StrokeOutline.Contour>): String {
        val sb = StringBuilder()
        for (c in contours) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append('M'); pt(c.start, sb)
            for (s in c.segments) {
                if (s.straight) {
                    sb.append(" L"); pt(s.end, sb)
                } else {
                    sb.append(" C"); pt(s.c1, sb); sb.append(','); pt(s.c2, sb); sb.append(','); pt(s.end, sb)
                }
            }
            sb.append(" Z")
        }
        return sb.toString()
    }

    /** A closed polygon as path data ("M … L … Z"); empty for fewer than 3 points. */
    fun polygonData(points: List<Pt>): String {
        if (points.size < 3) return ""
        val sb = StringBuilder()
        sb.append('M'); pt(points[0], sb)
        for (i in 1 until points.size) { sb.append(" L"); pt(points[i], sb) }
        sb.append(" Z")
        return sb.toString()
    }

    private fun pt(p: Pt, sb: StringBuilder) {
        num(p.x, sb)
        sb.append(' ')
        num(p.y, sb)
    }

    /**
     * Text for an XML attribute value in double quotes: `& < > " '` escaped, and every character XML 1.0
     * cannot hold AT ALL (C0 controls other than tab/LF/CR, lone surrogates, U+FFFE/U+FFFF) replaced
     * by U+FFFD — escaping cannot save those, a `&#1;` is itself ill-formed. Tab, LF and CR are
     * written as character references so attribute-value normalisation does not turn them into spaces.
     */
    fun attr(text: String): String {
        val sb = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\'' -> sb.append("&apos;")
                ch == '\t' -> sb.append("&#9;")
                ch == '\n' -> sb.append("&#10;")
                ch == '\r' -> sb.append("&#13;")
                ch < ' ' || ch == '￾' || ch == '￿' -> sb.append('�')
                ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    sb.append(ch).append(text[i + 1])
                    i++
                }
                ch.isSurrogate() -> sb.append('�')
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    /** `#rrggbb` of a packed ARGB colour (straight sRGB; the alpha byte is not part of it). */
    fun rgb(argb: Int): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder("#")
        for (shift in intArrayOf(20, 16, 12, 8, 4, 0)) sb.append(hex[(argb ushr shift) and 0xF])
        return sb.toString()
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** RFC 4648 base64, standard alphabet, padded. */
    fun base64(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val v = ((bytes[i].toInt() and 255) shl 16) or ((bytes[i + 1].toInt() and 255) shl 8) or (bytes[i + 2].toInt() and 255)
            sb.append(B64[v ushr 18]).append(B64[(v ushr 12) and 63]).append(B64[(v ushr 6) and 63]).append(B64[v and 63])
            i += 3
        }
        val rest = bytes.size - i
        if (rest == 1) {
            val v = (bytes[i].toInt() and 255) shl 16
            sb.append(B64[v ushr 18]).append(B64[(v ushr 12) and 63]).append("==")
        } else if (rest == 2) {
            val v = ((bytes[i].toInt() and 255) shl 16) or ((bytes[i + 1].toInt() and 255) shl 8)
            sb.append(B64[v ushr 18]).append(B64[(v ushr 12) and 63]).append(B64[(v ushr 6) and 63]).append('=')
        }
        return sb.toString()
    }
}
