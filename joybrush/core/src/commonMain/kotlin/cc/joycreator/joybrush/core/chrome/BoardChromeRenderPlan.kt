package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.chrome.BoardChromeLayout.Element
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout.Rect
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout.Shape
import kotlin.math.*

/** Bounds for software shadow patches, not another board layout. No window-sized software layer. */
object BoardChromeRenderPlan {
    data class Patch(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val argbBytes get() = width.toLong() * height * 4
    }

    /** Cache the unrotated shadow, then let the GPU apply the core pose. Quantized inverse
     * viewport bounds keep a small wiggle from rerasterizing the same outline every frame. */
    fun rasterViewport(element: Element, viewport: Rect, quantum: Int = 256): Rect {
        require(quantum > 0 && element.scale > 0 && element.scale.isFinite())
        val pivot = element.transformOrigin ?: BoardChromeLayout.Point(element.rect.cx, element.rect.cy)
        val local = transformedBounds(element.copy(rect = viewport, transformOrigin = pivot,
            rotationDeg = -element.rotationDeg, scale = 1f / element.scale))
        val q = quantum.toFloat()
        return Rect(floor(local.left / q) * q, floor(local.top / q) * q,
            ceil(local.right / q) * q, ceil(local.bottom / q) * q)
    }

    fun patches(element: Element, viewport: Rect, maxEdge: Int = 256): List<Patch> {
        require(maxEdge > 0)
        if (viewport.width <= 0 || viewport.height <= 0) return emptyList()
        val e = element
        val pad = ceil(e.strokePx / 2 + 3 * max(e.haloPx, e.shadowPx) + 2).toInt()
        val shapeBounds = transformedBounds(e)
        val clip = intersect(viewport, e.clip ?: viewport) ?: return emptyList()
        val outer = Rect(floor(shapeBounds.left - pad), floor(shapeBounds.top - pad),
            ceil(shapeBounds.right + pad), ceil(shapeBounds.bottom + pad))
        val bands = if (e.shape == Shape.ROUND_RECT && e.strokePx > 0 &&
            e.rotationDeg == 0f && e.scale == 1f) {
            // Rounded stroked rectangles only need their perimeter. The empty middle of a large
            // selected board must never become a software bitmap just to draw its keyline.
            val edge = pad + max(e.radiusPx, e.cornerRadiiPx?.maxOrNull() ?: 0f)
            val topEnd = min(outer.bottom, ceil(e.rect.top + edge))
            val bottomStart = max(topEnd, floor(e.rect.bottom - edge))
            val leftEnd = min(outer.right, ceil(e.rect.left + edge))
            val rightStart = max(leftEnd, floor(e.rect.right - edge))
            listOf(Rect(outer.left, outer.top, outer.right, topEnd),
                Rect(outer.left, bottomStart, outer.right, outer.bottom),
                Rect(outer.left, topEnd, leftEnd, bottomStart),
                Rect(rightStart, topEnd, outer.right, bottomStart))
        } else listOf(outer)
        val result = ArrayList<Patch>()
        for (band in bands) {
            val r = intersect(band, clip) ?: continue
            // Use half-open integer bands so adjacent shadow patches never double their alpha.
            val left = floor(r.left).toInt(); val top = floor(r.top).toInt()
            val right = ceil(r.right).toInt(); val bottom = ceil(r.bottom).toInt()
            var y = top
            while (y < bottom) {
                var x = left
                while (x < right) {
                    val x2 = min(right.toLong(), x.toLong() + maxEdge).toInt()
                    val y2 = min(bottom.toLong(), y.toLong() + maxEdge).toInt()
                    result += Patch(x, y, x2, y2)
                    x = x2
                }
                y = min(bottom.toLong(), y.toLong() + maxEdge).toInt()
            }
        }
        return result
    }

    private fun intersect(a: Rect, b: Rect): Rect? {
        val r = Rect(max(a.left, b.left), max(a.top, b.top), min(a.right, b.right), min(a.bottom, b.bottom))
        return r.takeIf { it.width > 0 && it.height > 0 }
    }

    private fun transformedBounds(e: Element): Rect {
        val p = e.transformOrigin ?: BoardChromeLayout.Point(e.rect.cx, e.rect.cy)
        val angle = e.rotationDeg * PI.toFloat() / 180f
        val c = cos(angle); val s = sin(angle)
        val points = listOf(e.rect.left to e.rect.top, e.rect.right to e.rect.top,
            e.rect.left to e.rect.bottom, e.rect.right to e.rect.bottom).map { (x, y) ->
            val dx = (x - p.x) * e.scale; val dy = (y - p.y) * e.scale
            (p.x + c * dx - s * dy) to (p.y + s * dx + c * dy)
        }
        return Rect(points.minOf { it.first }, points.minOf { it.second },
            points.maxOf { it.first }, points.maxOf { it.second })
    }
}
