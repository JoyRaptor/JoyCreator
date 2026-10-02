package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.chrome.BoardChromeLayout.Element
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout.Input
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout.Point
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout.Rect
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout.Shape
import cc.joycreator.joybrush.core.doc.BoardKind
import kotlin.test.*

class BoardChromeRenderPlanTest {
    private val viewport = Rect(0f, 0f, 548f, 1126f)

    @Test fun passiveIconNeedsSmallShadowPatchNotWindowBitmap() {
        val e = BoardChromeLayout.layout(Input(Rect(100f, 100f, 324f, 226f), 1f, BoardKind.CANVAS)).elements.single()
        val patches = BoardChromeRenderPlan.patches(e, viewport)
        assertTrue(patches.isNotEmpty())
        assertTrue(patches.sumOf { it.argbBytes } < 4096)
        assertTrue(patches.all { it.width <= 256 && it.height <= 256 })
    }

    @Test fun selectedPageKeylineRastersOnlyPerimeter() {
        val e = Element("keyline", Shape.ROUND_RECT, Rect(20f, 20f, 528f, 1106f),
            strokePx = 1f, haloPx = 1f, shadowPx = 1f)
        val patches = BoardChromeRenderPlan.patches(e, viewport)
        val bytes = patches.sumOf { it.argbBytes }
        assertTrue(bytes < 256 * 1024, "Perimeter cache was $bytes bytes")
        assertTrue(patches.none { it.left <= 274 && it.right > 274 && it.top <= 563 && it.bottom > 563 })
        assertTrue(bytes < 548L * 1126 * 4 / 10)
    }

    @Test fun fractionalPerimeterPatchesNeverOverlap() {
        val e = Element("line", Shape.ROUND_RECT, Rect(20.2f, 20.7f, 530.8f, 900.1f),
            strokePx = 1.5f, radiusPx = 3.2f, haloPx = 1f)
        val patches = BoardChromeRenderPlan.patches(e, viewport, 73)
        for (i in patches.indices) for (j in 0 until i) {
            val a = patches[i]; val b = patches[j]
            assertFalse(a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top)
        }
    }

    @Test fun offScreenBoardConsumesNoShadowMemory() {
        val e = Element("keyline", Shape.ROUND_RECT, Rect(-500f, -500f, 900f, 1600f),
            strokePx = 1f, haloPx = 1f)
        assertTrue(BoardChromeRenderPlan.patches(e, viewport).isEmpty())
    }

    @Test fun rotatedLiftAndSharedNumberPivotStayInsideBoundedPatches() {
        val e = Element("number", Shape.TEXT, Rect(120f, 130f, 170f, 142f),
            rotationDeg = -2f, scale = 1.1f, haloPx = 2f, transformOrigin = Point(140f, 150f))
        val patches = BoardChromeRenderPlan.patches(e, viewport, 32)
        assertTrue(patches.all { it.width <= 32 && it.height <= 32 })
        assertTrue(patches.minOf { it.left } < 120)
        assertTrue(patches.maxOf { it.right } > 170)
    }

    @Test fun stripClipLimitsRastersAndZeroViewportAllocatesNothing() {
        val e = Element("cell", Shape.ROUND_RECT, Rect(-10f, 200f, 1000f, 226f),
            strokePx = 1f, haloPx = 1f, clip = Rect(0f, 197f, 548f, 237f))
        val patches = BoardChromeRenderPlan.patches(e, viewport)
        assertTrue(patches.all { it.left >= 0 && it.right <= 548 && it.top >= 197 && it.bottom <= 237 })
        assertTrue(BoardChromeRenderPlan.patches(e, Rect(0f, 0f, 0f, 0f)).isEmpty())
        assertFailsWith<IllegalArgumentException> { BoardChromeRenderPlan.patches(e, viewport, 0) }
    }

    @Test fun inverseRasterViewportIncludesRotatedVisiblePortionAndStableSmallWiggle() {
        val e = Element("sprite-cell", Shape.ROUND_RECT, Rect(100f, 100f, 150f, 180f),
            rotationDeg = -2.2f, strokePx = 1f)
        val first = BoardChromeRenderPlan.rasterViewport(e, viewport)
        val next = BoardChromeRenderPlan.rasterViewport(e.copy(rotationDeg = -2.18f), viewport)
        assertEquals(first, next)
        assertTrue(first.left <= 0 && first.top <= 0 && first.right >= viewport.right)
        assertTrue(first.bottom >= viewport.bottom)
        val layout = BoardChromeLayout.layout(Input(Rect(100f, 100f, 324f, 226f), 1f,
            BoardKind.SPRITE, selected = true, armed = true))
        assertEquals(1f, layout.elements.first { it.id == "sprite-cell-0" }.shadowPx)
    }
}
