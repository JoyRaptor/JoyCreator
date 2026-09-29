package cc.joycreator.joybrush.core.view

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JB-2.02 — the view transform. Every case here is a claim the gesture code and the GL draw both
 * rest on: that screen and document are inverse, that a pinch keeps what is between the fingers
 * between the fingers, that the page squares up only when it is nearly square already, and that
 * the clip matrix still points the right way up.
 */
class ViewTransformTest {

    private val tol = 1e-3f

    private fun deg(d: Double): Float = (d * PI / 180.0).toFloat()

    /** Document point through a COLUMN-MAJOR 3×3: returns clip x, clip y. */
    private fun clip(m: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val cx = m[0] * x + m[3] * y + m[6]
        val cy = m[1] * x + m[4] * y + m[7]
        val cw = m[2] * x + m[5] * y + m[8]
        return Pair(cx / cw, cy / cw)
    }

    // ---- screen ↔ document ------------------------------------------------------------------------

    @Test
    fun screenToDocUndoesDocToScreen() {
        val v = ViewTransform()
        val points = listOf(0f to 0f, 12.5f to -7.25f, -40f to 33f, 240f to 130f)
        val cases = listOf(
            floatArrayOf(0.05f, 0f, 0f, 0f),              // zoomed right out
            floatArrayOf(0.5f, deg(0.4), -13f, 7f),
            floatArrayOf(1f, deg(1.1), -140.5f, 88.25f),  // a scrolled page, zoom 1
            floatArrayOf(2.5f, 0f, 0f, 0f),
            floatArrayOf(4f, deg(2.9), -1000f, -2000f),   // a big pan at zoom 4
            floatArrayOf(64f, deg(-3.0), 0f, 0f),         // zoomed right in
        )
        for (c in cases) {
            v.zoom = c[0]
            v.rotation = c[1]
            v.panX = c[2]
            v.panY = c[3]
            for (p in points) {
                val s = v.docToScreen(p.first, p.second)
                val back = v.screenToDoc(s.first, s.second)
                assertEquals(p.first, back.first, tol, "x at zoom ${c[0]} rot ${c[1]}")
                assertEquals(p.second, back.second, tol, "y at zoom ${c[0]} rot ${c[1]}")
            }
        }
    }

    @Test
    fun aFreshViewPutsTheDocumentOriginAtTheScreenOrigin() {
        val v = ViewTransform()
        assertEquals(1f, v.zoom, 0f)
        assertEquals(0f, v.rotation, 0f)
        val s = v.docToScreen(0f, 0f)
        assertEquals(0f, s.first, 0f)
        assertEquals(0f, s.second, 0f)
    }

    // ---- the pinch -------------------------------------------------------------------------------

    @Test
    fun applyPinchKeepsTheDocumentPointUnderTheCentroid() {
        val v = ViewTransform()
        v.zoom = 1.7f
        v.rotation = deg(0.35)
        v.panX = 40f
        v.panY = -25f
        val oldC = 300f to 500f
        val newC = 250f to 640f
        val held = v.screenToDoc(oldC.first, oldC.second)

        v.applyPinch(oldC.first, oldC.second, newC.first, newC.second, 2.5f, deg(12.0))

        val now = v.screenToDoc(newC.first, newC.second)
        assertEquals(held.first, now.first, tol, "the held document point moved in x")
        assertEquals(held.second, now.second, tol, "the held document point moved in y")
        assertEquals(1.7f * 2.5f, v.zoom, tol, "the step did not scale")
        assertEquals(deg(0.35) + deg(12.0), v.rotation, tol, "the step did not turn")
    }

    @Test
    fun aPinchWithNoScaleOrTurnIsJustAPan() {
        val v = ViewTransform()
        v.zoom = 3f
        v.rotation = deg(20.0)
        v.panX = 10f
        v.panY = 10f
        val held = v.screenToDoc(200f, 300f)

        v.applyPinch(200f, 300f, 260f, 340f, 1f, 0f)

        assertEquals(held.first, v.screenToDoc(260f, 340f).first, tol)
        assertEquals(held.second, v.screenToDoc(260f, 340f).second, tol)
        assertEquals(3f, v.zoom, 0f)
        assertEquals(deg(20.0), v.rotation, 0f)
    }

    @Test
    fun aNonsenseScaleIsIgnored() {
        val v = ViewTransform()
        v.zoom = 2f
        v.panX = 5f
        v.panY = 5f
        v.applyPinch(100f, 100f, 200f, 200f, Float.NaN, 0f)
        v.applyPinch(100f, 100f, 200f, 200f, 0f, 0f)
        v.applyPinch(100f, 100f, 200f, 200f, -2f, 0f)
        assertEquals(2f, v.zoom, 0f)
        assertEquals(5f, v.panX, 0f)
        assertEquals(5f, v.panY, 0f)
    }

    // ---- zoom limits -----------------------------------------------------------------------------

    @Test
    fun zoomIsClampedBothWays() {
        val v = ViewTransform()
        v.zoom = 1000f
        assertEquals(ViewTransform.MAX_ZOOM, v.zoom, 0f)
        v.zoom = 0.0001f
        assertEquals(ViewTransform.MIN_ZOOM, v.zoom, 0f)
        v.zoom = 64f
        assertEquals(64f, v.zoom, 0f)
        // A gesture that hands over a broken number must not be able to unzoom the canvas for good.
        v.zoom = Float.NaN
        assertEquals(64f, v.zoom, 0f)
    }

    @Test
    fun aPinchCannotEscapeTheZoomLimits() {
        val v = ViewTransform()
        v.applyPinch(0f, 0f, 0f, 0f, 1e9f, 0f)
        assertEquals(ViewTransform.MAX_ZOOM, v.zoom, 0f)
        v.applyPinch(0f, 0f, 0f, 0f, 1e-9f, 0f)
        assertEquals(ViewTransform.MIN_ZOOM, v.zoom, 0f)
    }

    // ---- squaring up ----------------------------------------------------------------------------

    @Test
    fun snapRotationSquaresUpOnlyWhenItIsNearlyThere() {
        val v = ViewTransform()
        v.rotation = deg(85.0)
        v.snapRotation(400f, 300f)
        assertEquals(deg(90.0), v.rotation, 1e-4f)

        v.rotation = deg(80.0)
        v.snapRotation(400f, 300f)
        assertEquals(deg(80.0), v.rotation, 1e-4f, "80° is 10° off: it must stay where it is")

        v.rotation = deg(-85.0)
        v.snapRotation(400f, 300f)
        assertEquals(deg(-90.0), v.rotation, 1e-4f)

        v.rotation = deg(20.0)
        v.snapRotation(400f, 300f)
        assertEquals(deg(20.0), v.rotation, 1e-4f)
    }

    @Test
    fun snapRotationKeepsTheDocumentPointUnderTheCentroid() {
        val v = ViewTransform()
        v.zoom = 3f
        v.rotation = deg(85.0)
        v.panX = 20f
        v.panY = -70f
        val c = 512f to 640f
        val held = v.screenToDoc(c.first, c.second)

        v.snapRotation(c.first, c.second)

        val now = v.screenToDoc(c.first, c.second)
        assertEquals(held.first, now.first, tol, "the page jumped while squaring up (x)")
        assertEquals(held.second, now.second, tol, "the page jumped while squaring up (y)")
    }

    @Test
    fun rotationIsWrappedSoItNeverDrifts() {
        val v = ViewTransform()
        v.rotation = deg(370.0)
        assertEquals(deg(10.0), v.rotation, 1e-4f)
        v.rotation = Float.NaN
        assertEquals(deg(10.0), v.rotation, 1e-4f, "a broken angle must not wipe the page's turn")
    }

    // ---- fitting --------------------------------------------------------------------------------

    @Test
    fun fitCentresTheRectWithAMargin() {
        val v = ViewTransform()
        v.zoom = 3f
        v.rotation = deg(37.0)
        v.panX = 500f
        v.panY = -500f

        v.fit(0f, 0f, 1000f, 500f, 1000, 500) // the same shape as the view

        val topLeft = v.docToScreen(0f, 0f)
        val bottomRight = v.docToScreen(1000f, 500f)
        assertEquals(50f, topLeft.first, tol, "5% of 1000 on the left")
        assertEquals(25f, topLeft.second, tol, "5% of 500 at the top")
        assertEquals(950f, bottomRight.first, tol)
        assertEquals(475f, bottomRight.second, tol)
        assertEquals(0f, v.rotation, tol, "fit turns the page upright")
        val mid = v.docToScreen(500f, 250f)
        assertEquals(500f, mid.first, tol, "not centred in x")
        assertEquals(250f, mid.second, tol, "not centred in y")
    }

    @Test
    fun fitSplitsTheSpareRoomOnTheAxisThatHasIt() {
        val v = ViewTransform()
        v.fit(0f, 0f, 1000f, 250f, 1000, 500) // wide and short: only the width binds
        assertEquals(50f, v.docToScreen(0f, 0f).first, tol)
        val mid = v.docToScreen(500f, 125f)
        assertEquals(500f, mid.first, tol)
        assertEquals(250f, mid.second, tol)
    }

    @Test
    fun fitSurvivesAnEmptyRectangle() {
        val v = ViewTransform()
        v.zoom = 7f
        v.panX = 300f
        v.fit(10f, 10f, 10f, 10f, 800, 600) // no width, no height: neither axis binds
        assertTrue(v.zoom.isFinite() && v.zoom > 0f, "a degenerate rect must not break the zoom")
        val mid = v.docToScreen(10f, 10f)
        assertEquals(400f, mid.first, tol)
        assertEquals(300f, mid.second, tol)
    }

    // ---- the clip matrix -------------------------------------------------------------------------

    @Test
    fun docToClipAtIdentityIsExactlyThePlainViewMatrix() {
        val m = ViewTransform().docToClip(800, 600)
        assertEquals(9, m.size)
        // Column-major: 0..2 is the first COLUMN, then 3..5, then 6..8.
        assertEquals(2f / 800f, m[0], 0f)
        assertEquals(0f, m[1], 0f)
        assertEquals(0f, m[2], 0f)
        assertEquals(0f, m[3], 0f)
        assertEquals(-2f / 600f, m[4], 0f)
        assertEquals(0f, m[5], 0f)
        assertEquals(-1f, m[6], 0f)
        assertEquals(1f, m[7], 0f)
        assertEquals(1f, m[8], 0f)
    }

    @Test
    fun docToClipSurvivesAViewportThatHasNoSizeYet() {
        val m = ViewTransform().docToClip(0, 0)
        assertEquals(9, m.size)
        for (v in m) assertTrue(v.isFinite(), "a zero-sized viewport must not fill the matrix with infinities")
    }

    @Test
    fun docToClipPutsTheViewCornersAtPlusAndMinusOne() {
        val v = ViewTransform()
        v.zoom = 2.5f
        v.rotation = deg(20.0)
        v.panX = 33f
        v.panY = -12f
        val w = 1000
        val h = 2000
        val m = v.docToClip(w, h)
        val corners = listOf(
            0f to 0f,
            w.toFloat() to 0f,
            0f to h.toFloat(),
            w.toFloat() to h.toFloat(),
        )
        for (c in corners) {
            val d = v.screenToDoc(c.first, c.second)
            val out = clip(m, d.first, d.second)
            // x right is +1; y DOWN the screen is -1, because clip space is y up.
            assertEquals(c.first * 2f / w - 1f, out.first, 1e-4f, "clip x for ${c.first},${c.second}")
            assertEquals(1f - c.second * 2f / h, out.second, 1e-4f, "clip y for ${c.first},${c.second}")
        }
    }
}
