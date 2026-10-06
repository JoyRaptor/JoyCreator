package cc.joycreator.joybrush.android.board

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.view.ViewTransform
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout as Chrome
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w548dp-h1126dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardRuntimeControllerTest {
    private fun document(): JbDocument {
        val base = JbDocument(id="drawing",name="Drawing",boards=listOf(Board("page","Page",BoardKind.CANVAS,RectPx(0,0,548,1126))),
            layers=listOf(Layer("paint","Paint",LayerKind.PAINT,cels=listOf(Cel("shared")))))
        var n=0
        return RegionDocumentOps.create(base,"Animation",RectPx(100,100,200,100)) { "id-${++n}" }.doc
    }
    private class Host(var document: JbDocument): BoardRuntimeController.Host {
        var revision = 0L
        val transform = ViewTransform()
        var scope: RectPx? = null
        val previews = mutableListOf<Map<String,String>>()
        val thumbnails = mutableListOf<Pair<List<String>,(Map<String,IntArray>)->Unit>>()
        var deferEdits = false
        val queued = mutableListOf<(JbDocument) -> RegionChange>()
        override fun ensureDocument(ready:(JbDocument)->Unit) = ready(document)
        override fun edit(change:(JbDocument)->RegionChange) {
            if (deferEdits) queued += change else document = change(document).doc
        }
        override fun selectFrame(boardId:String,frameId:String) { document = RegionDocumentOps.selectFrame(document,boardId,frameId) }
        override fun preview(frames:Map<String,String>) { previews += frames }
        override fun transform() = transform
        override fun contentRevision() = revision
        override fun refusal(message:String) {}
        override fun selectionChanged(bounds:RectPx?) { scope = bounds }
        override fun thumbnails(boardId:String,frames:List<String>,width:Int,height:Int,ready:(Map<String,IntArray>)->Unit) { thumbnails += frames to ready }
    }
    private fun setup(host:Host): Pair<BoardRuntimeController,FrameLayout> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity)
        activity.setContentView(parent)
        parent.measure(View.MeasureSpec.makeMeasureSpec(548,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1126,View.MeasureSpec.EXACTLY))
        parent.layout(0,0,548,1126)
        val controller = BoardRuntimeController(activity,parent,host)
        controller.documentChanged(host.document)
        return controller to parent
    }
    @Test fun passiveSelectionCropsAnyKindWithoutChangingSavedActiveBoard() {
        val host = Host(document()); val saved = host.document
        val (controller,_) = setup(host)
        controller.select("page")
        assertEquals(saved.boards.first().rect,host.scope)
        assertEquals(saved,host.document)
        controller.select(null)
        assertNull(host.scope)
        assertEquals(saved,host.document)
    }
    @Test fun nativePlaybackUsesPreviewAndStopsWithoutChangingCursor() {
        val host = Host(document()); val saved = host.document
        val (controller,parent) = setup(host)
        val animation = saved.boards.last()
        controller.select(animation.id)
        val view = (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<BoardChromeView>().first { it.sceneIdentity?.boardId == animation.id }
        view.host.action("play",false)
        assertEquals(mapOf(animation.id to animation.currentFrameId),host.previews.last())
        assertEquals(saved,host.document)
        controller.stopPreview()
        assertEquals(emptyMap<String,String>(),host.previews.last())
        assertEquals(saved,host.document)
    }
    @Test fun oldThumbnailCallbackCannotReplaceNewSceneArt() {
        val host = Host(document()); val (controller,parent) = setup(host)
        val b = host.document.boards.last()
        controller.select(b.id)
        val old = host.thumbnails.last().second
        host.revision++
        controller.documentChanged(host.document)
        val fresh = host.thumbnails.last().second
        val frame = b.frames.first().id
        fresh(mapOf(frame to IntArray(6400) { 0xff00ff00.toInt() }))
        old(mapOf(frame to IntArray(6400) { 0xffff0000.toInt() }))
        val view = (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<BoardChromeView>().first { it.sceneIdentity?.boardId == b.id }
        val bitmap = Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888)
        view.host.art(Canvas(bitmap),Chrome.Element("cell-0",Chrome.Shape.ROUND_RECT,Chrome.Rect(0f,0f,80f,80f),artSlot=true))
        assertEquals(0xff00ff00.toInt(),bitmap.getPixel(40,40))
    }
    @Test fun cursorOnlyNavigationRetainsCapturedFrameArt() {
        val source = document(); val id = source.boards.last().id
        val host = Host(RegionDocumentOps.addFrame(source,id,NewFrame.LINK) { UUID.randomUUID().toString() }.doc)
        val (controller,_) = setup(host)
        controller.select(id)
        val count = host.thumbnails.size
        val callback = host.thumbnails.last().second
        host.document = RegionDocumentOps.selectFrame(host.document,id,host.document.boards.last().frames.first().id)
        controller.documentChanged(host.document)
        assertEquals(count,host.thumbnails.size)
        callback(host.document.boards.last().frames.associate { it.id to IntArray(6400) { 0xff00ff00.toInt() } })
        controller.refreshTransform()
        assertEquals(count,host.thumbnails.size)
    }
    @Test fun replacingDocumentClearsPassiveSelectionEvenWithReusedBoardIds() {
        val host = Host(document()); val (controller,_) = setup(host)
        controller.select("page")
        host.document = host.document.copy(id="replacement")
        controller.documentChanged(host.document)
        assertNull(controller.selectedBoardId)
        assertNull(host.scope)
    }
    private fun penTouch(view:View,action:Int,x:Float,y:Float): Boolean {
        val property = MotionEvent.PointerProperties().apply { id=0; toolType=MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply { this.x=x; this.y=y; pressure=1f }
        val event = MotionEvent.obtain(0,20,action,1,arrayOf(property),arrayOf(coords),0,0,1f,1f,0,0,0,0)
        return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    @Test fun penCanSelectBoardButtonWhileSpriteCellsPassThroughToPainting() {
        val host = Host(document())
        host.document = BoardDocumentOps.createSprite(host.document,"Sprite",RectPx(300,300,100,100),SpriteGrid(1,1,100,100)) { "sprite" }.doc
        val (controller,parent) = setup(host)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        val animation = host.document.boards.last()
        val view = (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<BoardChromeView>().first { it.sceneIdentity?.boardId == animation.id }
        // Test the real native pointer dispatcher with bounded targets, independent of rotation.
        val kind = Chrome.Rect(0f,0f,40f,40f); val cell = Chrome.Rect(60f,0f,100f,40f)
        view.submit(Chrome.Layout(emptyList(),listOf(Chrome.Control("kind",kind,kind,"Select"),Chrome.Control("cell-0",cell,cell,"Frame")),false,false,false))
        assertTrue(penTouch(view,MotionEvent.ACTION_DOWN,20f,20f))
        assertTrue(penTouch(view,MotionEvent.ACTION_UP,20f,20f))
        assertEquals(animation.id,controller.selectedBoardId)
        assertFalse(penTouch(view,MotionEvent.ACTION_DOWN,80f,20f))
        assertFalse(penTouch(view,MotionEvent.ACTION_UP,80f,20f))
    }
    @Test fun rotatedHitSurfaceCoversViewportAndParentDoesNotClip() {
        val host = Host(document()); host.transform.rotation = (Math.PI / 4).toFloat()
        val (_,parent) = setup(host)
        assertFalse(parent.clipChildren)
        val child = parent.getChildAt(0)
        val params = child.layoutParams as FrameLayout.LayoutParams
        assertTrue(params.width > parent.width + parent.height)
        assertTrue(params.leftMargin < 0)
        assertEquals(45f,child.rotation,.001f)
    }

    @Test fun rotatedParentDispatchSelectsTheBoardAndPreservesPaintingGaps() {
        for (degrees in listOf(-45f, 45f, 90f)) {
            val host = Host(document())
            host.transform.apply { rotation = Math.toRadians(degrees.toDouble()).toFloat(); panX = 275f; panY = 300f; zoom = .8f }
            val (controller, parent) = setup(host)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            parent.measure(View.MeasureSpec.makeMeasureSpec(548,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1126,View.MeasureSpec.EXACTLY))
            parent.layout(0,0,548,1126)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
            val board = host.document.boards.last()
            val view = (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<BoardChromeView>().first { it.sceneIdentity?.boardId == board.id }
            // Dispatch at the settled native target, after Activity decor/layout has its actual size.
            // A passive board has one target: its kind button. Android transforms this local point.
            val target = view.getChildAt(0)
            val kind = floatArrayOf(target.left + target.width / 2f, target.top + target.height / 2f)
            view.matrix.mapPoints(kind); kind[0] += view.left; kind[1] += view.top
            assertTrue("down at $degrees", penTouch(parent,MotionEvent.ACTION_DOWN,kind[0],kind[1]))
            assertTrue("up at $degrees", penTouch(parent,MotionEvent.ACTION_UP,kind[0],kind[1]))
            assertEquals(board.id,controller.selectedBoardId)
            val center = host.transform.docToScreen((board.rect.x+board.rect.w/2).toFloat(), (board.rect.y+board.rect.h/2).toFloat())
            assertFalse("paint gap at $degrees", penTouch(parent,MotionEvent.ACTION_DOWN,center.first,center.second))
            controller.stop()
        }
    }

    @Test fun deferredFrameDropRefusesAChangedOrderInsteadOfMovingAnotherFrame() {
        val host = Host(document())
        val id = host.document.boards.last().id
        host.document = RegionDocumentOps.addFrame(host.document,id,NewFrame.BLANK) { UUID.randomUUID().toString() }.doc
        host.deferEdits = true
        val (_,parent) = setup(host)
        val view = (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<BoardChromeView>().first { it.sceneIdentity?.boardId == id }
        view.host.stripLift(0,0f,0f,false,true)
        view.host.stripLift(0,0f,0f,false,false)
        view.host.stripDrop(0,2,false)
        assertEquals(1,host.queued.size)
        val changed = RegionDocumentOps.reorderFrames(host.document,id,host.document.boards.last().frames.map { it.id }.reversed())
        assertThrows(DocException::class.java) { host.queued.single()(changed) }
    }

    @Test fun refusedThumbnailCompletesAndAllowsANewRequest() {
        val host = Host(document()); val (controller,_) = setup(host)
        controller.select(host.document.boards.last().id)
        val count = host.thumbnails.size
        host.thumbnails.last().second(emptyMap())
        controller.refreshTransform()
        assertEquals(count + 1, host.thumbnails.size)
    }

    @Test fun hoverAlwaysLoopsAndPauseStopsAllPreviewScheduling() {
        val host = Host(document())
        val id = host.document.boards.last().id
        host.document = RegionDocumentOps.addFrame(host.document,id,NewFrame.BLANK) { UUID.randomUUID().toString() }.doc
        val (controller,parent) = setup(host)
        val view = (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<BoardChromeView>().first { it.sceneIdentity?.boardId == id }
        view.host.action("loop",false); view.host.action("loop",false) // manual mode ONCE
        view.host.hoverLoop(true,null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(host.previews.last().isNotEmpty())
        controller.stop()
        val count = host.previews.size
        controller.documentChanged(host.document)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(count,host.previews.size)
        assertTrue(host.previews.last().isEmpty())
    }
    @Test fun stableFrameDropAdjustsOriginalInsertionGap() {
        assertEquals(listOf("b","c","a"),BoardRuntimeController.reordered(listOf("a","b","c"),"a",3))
        assertEquals(listOf("c","a","b"),BoardRuntimeController.reordered(listOf("a","b","c"),"c",0))
        assertEquals(listOf("a","b","c"),BoardRuntimeController.reordered(listOf("a","b","c"),"b",2))
    }
    @Test fun resizeHandlesKeepOppositeEdgeAndNeverInvert() {
        assertEquals(RectPx(5,7,25,33),BoardRuntimeController.resized(RectPx(10,20,20,20),0,-5,-13))
        assertEquals(RectPx(29,39,1,1),BoardRuntimeController.resized(RectPx(10,20,20,20),0,100,100))
        assertEquals(RectPx(10,20,24,20),BoardRuntimeController.resized(RectPx(10,20,20,20),3,4,90))
    }
}
