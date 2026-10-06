package cc.joycreator.joybrush.android.chrome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerColumnAnimationTest {
    private class Host : LayerColumnView.Host {
        val toggles = mutableListOf<Triple<String,String,Boolean>>()
        var layerActions = 0
        var maskActions = 0
        override fun addLayer() {}
        override fun selectLayer(id: String) { layerActions++ }
        override fun openLayer(id: String, anchor: View) { layerActions++ }
        override fun moveLayer(id: String, toIndex: Int) { layerActions++ }
        override fun maskTapped(id: String) { maskActions++ }
        override fun setLayerHeld(boardId: String, layerId: String, held: Boolean) { toggles += Triple(boardId,layerId,held) }
    }
    private fun document(): JbDocument {
        val base = JbDocument(id="drawing",name="Drawing",
            boards=listOf(Board("page","Page",BoardKind.CANVAS,RectPx(0,0,400,400))),
            layers=listOf(Layer("paint","Paint",LayerKind.PAINT,cels=listOf(Cel("shared-paint")),mask=Cel("mask")),
                Layer("ink","Ink",LayerKind.INK,cels=listOf(Cel("shared-ink")))))
        var serial = 0
        return RegionDocumentOps.create(base,"Animation",RectPx(20,20,100,100)) { "id-${++serial}" }.doc
    }
    private fun column(host: Host, doc: JbDocument): LayerColumnView =
        LayerColumnView(ChromeKit(RuntimeEnvironment.getApplication()),host).also { column ->
            column.layoutParams = FrameLayout.LayoutParams(column.widthPx,400)
            column.show(LayerStack(doc.layers.map { LayerState(it.id,it.name,hasMask=it.mask!=null) },"paint"),16)
        }
    private fun layout(column: LayerColumnView) {
        column.measure(View.MeasureSpec.makeMeasureSpec(column.widthPx,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.AT_MOST))
        column.layout(0,0,column.measuredWidth,column.measuredHeight)
    }
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0,10,action,x,y,0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    @Test fun markersToggleEligibleLayerInSelectedBoardWithoutChangingPaintOrMaskTarget() {
        val doc = document(); val board = doc.boards.last().id; val host = Host(); val column = column(host,doc)
        column.setAnimationBoard(doc,board)
        assertTrue(column.animationMarkerFor("paint")!!.performClick())
        assertTrue(column.animationMarkerFor("ink")!!.performClick())
        assertEquals(listOf(Triple(board,"paint",true),Triple(board,"ink",true)),host.toggles)
        assertEquals(0,host.layerActions)
        assertEquals(0,host.maskActions)
        assertNull(column.animationMarkerFor("mask"))
        val cell = column.cellFor("paint")
        val held = RegionDocumentOps.setHeld(doc,board,"paint",true).doc
        column.setAnimationBoard(held,board)
        assertSame(cell,column.cellFor("paint"))
        assertTrue(column.animationMarkerFor("paint")!!.contentDescription.toString().contains("Same on every frame"))
        column.animationMarkerFor("paint")!!.performClick()
        assertEquals(Triple(board,"paint",false),host.toggles.last())
    }
    @Test fun markerColumnPreservesThumbnailGeometryAndDisappearsForPassiveBoards() {
        val doc = document(); val host = Host(); val column = column(host,doc)
        column.pageAspect = 1.25f
        layout(column)
        val size = column.thumbSize(); val cellWidth = column.cellFor("paint")!!.width
        column.setAnimationBoard(doc,doc.boards.last().id)
        layout(column)
        assertEquals(76,column.widthPx)
        assertEquals(76,column.layoutParams.width)
        assertEquals(16,column.animationMarkerFor("paint")!!.width)
        assertEquals(size,column.thumbSize())
        assertEquals(cellWidth,column.cellFor("paint")!!.width)
        column.setAnimationBoard(doc,"page")
        layout(column)
        assertEquals(60,column.widthPx)
        assertNull(column.animationMarkerFor("paint"))
        assertEquals(size,column.thumbSize())
        column.setAnimationBoard(doc,doc.boards.last().id)
        column.setAnimationBoard(null,null)
        assertNull(column.animationMarkerFor("ink"))
    }
    @Test fun missingRegionAndMaskRowsCannotToggleAndOldBoardTouchCannotToggleNewBoard() {
        val doc = document(); val host = Host(); val column = column(host,doc)
        column.show(LayerStack(listOf(LayerState("paint","Paint"),LayerState("mask","Mask"),LayerState("unknown","Unknown")),"paint"),16)
        column.setAnimationBoard(doc,doc.boards.last().id)
        assertNull(column.animationMarkerFor("mask"))
        assertNull(column.animationMarkerFor("unknown"))
        val marker = column.animationMarkerFor("paint")!!
        touch(marker,MotionEvent.ACTION_DOWN,5f,5f)
        var serial = 0
        val second = RegionDocumentOps.create(doc,"Other",RectPx(200,200,100,100)) { "other-${++serial}" }.doc
        column.setAnimationBoard(second,second.boards.last().id)
        assertFalse(marker.performClick())
        assertTrue(host.toggles.isEmpty())
        column.show(LayerStack(listOf(LayerState("unknown","Unknown")),"unknown"),16)
        assertFalse(marker.performClick())
    }
    @Test fun maskThumbnailStillDispatchesMaskActionBesideAnimationMarker() {
        val doc = document(); val host = Host(); val column = column(host,doc)
        column.pageAspect = 1f
        column.setAnimationBoard(doc,doc.boards.last().id)
        layout(column)
        val cell = column.cellFor("paint")!!
        cell.draw(Canvas(Bitmap.createBitmap(cell.width,cell.height,Bitmap.Config.ARGB_8888)))
        touch(cell,MotionEvent.ACTION_DOWN,cell.width-3f,cell.height-5f)
        touch(cell,MotionEvent.ACTION_UP,cell.width-3f,cell.height-5f)
        assertEquals(1,host.maskActions)
        assertTrue(host.toggles.isEmpty())
    }
    @Test fun runnerAndMountainUseThirteenDpGlyphBoundsAndSpecifiedColours() {
        val doc = document(); val board = doc.boards.last().id; val column = column(Host(),doc)
        column.setAnimationBoard(doc,board)
        fun pixels(): List<Int> {
            layout(column)
            val marker = column.animationMarkerFor("paint")!!
            val image = Bitmap.createBitmap(marker.width,marker.height,Bitmap.Config.ARGB_8888)
            marker.draw(Canvas(image))
            val colors = mutableListOf<Int>()
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val color = image.getPixel(x,y)
                if (color ushr 24 > 200) {
                    assertTrue(x in 1..14)
                    assertTrue(kotlin.math.abs(y-image.height/2f) <= 7f)
                    colors += color
                }
            }
            assertTrue(colors.isNotEmpty())
            return colors
        }
        assertTrue(pixels().all { ((it ushr 8) and 255) > ((it ushr 16) and 255) })
        column.setAnimationBoard(RegionDocumentOps.setHeld(doc,board,"paint",true).doc,board)
        assertTrue(pixels().all { kotlin.math.abs(((it ushr 16) and 255)-161) <= 1 && kotlin.math.abs((it and 255)-170) <= 1 })
    }
}
