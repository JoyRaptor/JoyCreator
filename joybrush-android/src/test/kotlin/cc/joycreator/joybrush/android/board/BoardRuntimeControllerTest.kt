package cc.joycreator.joybrush.android.board

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.view.ViewTransform
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout as Chrome
import cc.joycreator.joybrush.core.sprite.CellRoll
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w548dp-h1126dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardRuntimeControllerTest {
    private fun spriteHost(): Host {
        var serial = 0
        val base = document()
        return Host(BoardDocumentOps.createSprite(base,"Sprites",RectPx(100,300,120,100),SpriteGrid(4,2,30,50)) { "sprite-${serial++}" }.doc)
    }
    private fun spriteView(controller: BoardRuntimeController, parent: FrameLayout, host: Host): BoardChromeView {
        val id = host.document.boards.last().id
        controller.select(id)
        return (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<BoardChromeView>().single { it.sceneIdentity?.boardId == id }
    }
    @Test fun spriteCountAndPixelSteppersFitWholeCellsWithoutContentCopies() {
        val host = spriteHost(); val (controller,parent) = setup(host); val view = spriteView(controller,parent,host)
        val layers = host.document.layers
        view.host.action("cols-plus",false)
        assertEquals(SpriteGrid(5,2,24,50),host.document.boards.last().grid)
        controller.documentChanged(host.document)
        view.host.action("grid-px",false); view.host.action("cols-plus",false)
        assertEquals(SpriteGrid(5,2,25,50),host.document.boards.last().grid)
        assertEquals(RectPx(100,300,125,100),host.document.boards.last().rect)
        assertEquals(layers,host.document.layers)
    }
    @Test fun rapidQueuedGridStepsUseLatestGridAndOldDrawingCannotReceiveThem() {
        val host = spriteHost(); val (controller,parent) = setup(host); val view = spriteView(controller,parent,host)
        host.deferEdits = true
        view.host.action("cols-plus",false); view.host.action("cols-plus",false)
        val first = host.queued[0](host.document)
        val second = host.queued[1](first.doc)
        assertEquals(6,second.doc.boards.last().grid!!.cols)
        assertTrue(first.copies.isEmpty()); assertTrue(second.copies.isEmpty())
        assertFailsDoc { host.queued.first()(host.document.copy(id="other-drawing")) }
    }
    private fun assertFailsDoc(block: () -> Unit) {
        try { block(); fail("Expected a board refusal") } catch(_: DocException) { }
    }
    @Test fun typedGridRefusesChangedBoardRatherThanResizingFromAnOldDialog() {
        val host = spriteHost(); val (controller,parent) = setup(host); val view = spriteView(controller,parent,host)
        view.host.action("cols",false)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val field = dialog.findViewById<android.widget.EditText>(android.R.id.edit) ?: findEdit(dialog.window!!.decorView)
        field.setText("3")
        host.deferEdits = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        val changed = BoardDocumentOps.rename(host.document,host.document.boards.last().id,"Changed")
        assertFailsDoc { host.queued.single()(changed) }
    }
    private fun findEdit(view: View): android.widget.EditText {
        if(view is android.widget.EditText) return view
        if(view is android.view.ViewGroup) for(n in 0 until view.childCount) {
            try { return findEdit(view.getChildAt(n)) } catch(_: NoSuchElementException) { }
        }
        throw NoSuchElementException()
    }
    private fun shownInput(view: BoardChromeView): Chrome.Input {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        return BoardChromeView::class.java.getDeclaredField("input").apply { isAccessible=true }.get(view) as Chrome.Input
    }
    @Test fun spriteHandlePreviewsCellAndBoardSizesWithoutHistoryAndCancelRestores() {
        val host=spriteHost(); val (controller,parent)=setup(host); val view=spriteView(controller,parent,host)
        val original=host.document
        view.host.drag("handle-4",Chrome.Point(0f,0f),Chrome.Point(9f,7f),false)
        val preview=shownInput(view)
        assertEquals(128,preview.pixelWidth); assertEquals(108,preview.pixelHeight)
        assertEquals(32,preview.cellWidth); assertEquals(54,preview.cellHeight)
        assertEquals(original,host.document); assertTrue(host.queued.isEmpty())
        view.host.cancel("handle-4")
        assertEquals(120,shownInput(view).pixelWidth); assertEquals(original.boards.last().rect,host.scope)
    }
    @Test fun spriteCornerCommitsOneAtomicEditAndQueuedEditRejectsChangedGrid() {
        val host=spriteHost(); val (controller,parent)=setup(host); val view=spriteView(controller,parent,host)
        host.deferEdits=true
        repeat(4) { view.host.drag("handle-4",Chrome.Point(0f,0f),Chrome.Point(9f,7f),false) }
        assertTrue(host.queued.isEmpty())
        view.host.drag("handle-4",Chrome.Point(0f,0f),Chrome.Point(9f,7f),true)
        assertEquals(1,host.queued.size)
        val result=host.queued.single()(host.document)
        assertEquals(SpriteGrid(4,2,32,54),result.doc.boards.last().grid)
        assertEquals(host.document.layers,result.doc.layers)
        assertFailsDoc { host.queued.single()(BoardDocumentOps.rename(host.document,host.document.boards.last().id,"Other")) }
    }
    @Test fun typedCellDimensionsRetainCountsAndGrowTheBoard() {
        val host=spriteHost(); val (controller,parent)=setup(host); val view=spriteView(controller,parent,host)
        view.host.action("grid-px",false); view.host.action("cols",false)
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        findEdit(dialog.window!!.decorView).setText("41")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        assertEquals(SpriteGrid(4,2,41,50),host.document.boards.last().grid)
        assertEquals(164,host.document.boards.last().rect.w)
    }
    @Test fun lockedSpriteSequenceIsTransientAndPenStillPassesThrough() {
        val host=spriteHost(); host.document=BoardDocumentOps.setLocked(host.document,host.document.boards.last().id,true)
        val (controller,parent)=setup(host); val view=spriteView(controller,parent,host); val original=host.document
        fun pointer(tool:Int)=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,1,arrayOf(MotionEvent.PointerProperties().apply { id=0;toolType=tool }),
            arrayOf(MotionEvent.PointerCoords().apply { x=0f;y=0f;pressure=1f;size=1f }),0,0,1f,1f,0,0,0,0)
        for(tool in listOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER)) {
            val event=pointer(tool); try { assertEquals(tool == MotionEvent.TOOL_TYPE_FINGER,view.host.acceptsPointer("sprite-cell-0",event)) } finally { event.recycle() }
        }
        view.host.action("sprite-cell-2",false); view.host.action("sprite-cell-0",false); view.host.action("sprite-cell-2",false)
        assertEquals(listOf(2,0,2),shownInput(view).spriteOrder)
        view.host.action("preview-play",false)
        assertTrue(shownInput(view).playing)
        assertTrue(host.previews.isEmpty()); assertEquals(original,host.document)
        view.host.action("preview-clear",false)
        assertFalse(shownInput(view).playing); assertTrue(shownInput(view).spriteOrder.isEmpty())
        assertEquals(original,host.document); controller.stop()
    }
    @Test fun lockedArmedSpriteDragCommitsOneSwapAndCancelCommitsNothing() {
        val host=spriteHost(); host.document=BoardDocumentOps.setLocked(host.document,host.document.boards.last().id,true)
        val (controller,parent)=setup(host); val view=spriteView(controller,parent,host)
        view.host.action("feature",false)
        val input=shownInput(view); val from=Chrome.Point(input.board.left+15,input.board.top+25); val to=Chrome.Point(input.board.left+75,input.board.top+25)
        host.deferEdits=true
        view.host.drag("sprite-cell-0",from,to,false); view.host.cancel("sprite-cell-0")
        assertTrue(host.queued.isEmpty())
        view.host.drag("sprite-cell-0",from,to,false); view.host.drag("sprite-cell-0",from,to,true)
        assertEquals(1,host.queued.size)
        val change=host.queued.single()(host.document)
        assertEquals(host.document,change.doc); assertTrue(change.transfers.isNotEmpty())
        assertEquals(RectPx(100,300,30,50),change.transfers.first().sourceRect)
        assertEquals(RectPx(160,300,30,50),change.transfers.first().destinationRect)
    }
    @Test fun neighboringAnimationCursorInvalidatesSpritePicturesAndRejectsOldReply() {
        val host = spriteHost()
        val spriteId = host.document.boards.last().id
        val animationId = host.document.boards.first { it.kind == BoardKind.ANIMATION }.id
        host.document = RegionDocumentOps.addFrame(host.document, animationId, NewFrame.BLANK) {
            UUID.randomUUID().toString()
        }.doc
        host.document = BoardDocumentOps.move(host.document, spriteId, 100, 100)
        host.document = BoardDocumentOps.setLocked(host.document, spriteId, true)
        val (controller, parent) = setup(host)
        val view = spriteView(controller, parent, host)
        view.host.action("sprite-cell-0", false)
        val old = host.spriteThumbnails.single().second
        val animation = host.document.boards.first { it.id == animationId }
        val next = animation.frames.first { it.id != animation.currentFrameId }.id
        host.document = RegionDocumentOps.selectFrame(host.document, animationId, next)
        controller.documentChanged(host.document)
        assertEquals("Saved frame navigation must refetch overlapping Sprite art", 2, host.spriteThumbnails.size)
        assertEquals(0L, host.revision)
        val fresh = host.spriteThumbnails.last()
        assertEquals(listOf(0), fresh.first)
        fresh.second(mapOf(0 to IntArray(6400) { 0xff00ff00.toInt() }))
        old(mapOf(0 to IntArray(6400) { 0xffff0000.toInt() }))
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        view.host.art(Canvas(bitmap), Chrome.Element("preview-art", Chrome.Shape.ROUND_RECT,
            Chrome.Rect(0f, 0f, 80f, 80f), artSlot = true))
        assertEquals(0xff00ff00.toInt(), bitmap.getPixel(40, 40))
        bitmap.recycle(); controller.stop()
    }

    @Test fun pausedSpriteThumbnailReplyDoesNotPreventRefetchOnResume() {
        val host = spriteHost()
        host.document = BoardDocumentOps.setLocked(host.document, host.document.boards.last().id, true)
        val (controller, parent) = setup(host)
        val view = spriteView(controller, parent, host)
        view.host.action("sprite-cell-0", false)
        val old = host.spriteThumbnails.single().second
        controller.stop()
        old(emptyMap())
        controller.resume()
        assertEquals("Paused requests must not leave a cell permanently pending", 2, host.spriteThumbnails.size)
        val fresh = host.spriteThumbnails.last()
        fresh.second(mapOf(0 to IntArray(6400) { 0xff00ff00.toInt() }))
        old(mapOf(0 to IntArray(6400) { 0xffff0000.toInt() }))
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        view.host.art(Canvas(bitmap), Chrome.Element("preview-art", Chrome.Shape.ROUND_RECT,
            Chrome.Rect(0f, 0f, 80f, 80f), artSlot = true))
        assertEquals(0xff00ff00.toInt(), bitmap.getPixel(40, 40))
        repeat(3) { controller.refreshTransform() }
        assertEquals(2, host.spriteThumbnails.size)
        bitmap.recycle(); controller.stop()
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun longIdleSpriteSequenceFetchesOnlyCurrentWindowAndSettlesAfterSuccess() {
        val host = spriteHost(); val spriteId = host.document.boards.last().id
        host.document = BoardDocumentOps.setSpriteGrid(host.document, spriteId, SpriteGrid(20, 20, 6, 5))
        host.document = BoardDocumentOps.setLocked(host.document, spriteId, true)
        val (controller, parent) = setup(host)
        // Seed a legitimate session roll without hundreds of native tap dispatches.
        val rolls = BoardRuntimeController::class.java.getDeclaredField("spriteRolls").apply { isAccessible = true }
            .get(controller) as MutableMap<String, CellRoll>
        rolls[spriteId] = CellRoll(List(400) { CellRoll.Entry(it) }, cursor = 350)
        val original = host.document
        val view = spriteView(controller, parent, host)
        val request = host.spriteThumbnails.single()
        assertTrue(request.first.size <= 16)
        assertTrue("The current preview cell must be fetched first", 350 in request.first)
        assertTrue("Idle prefetch must stay within a bounded look-ahead", request.first.all { it in 350..365 })
        request.second(request.first.associateWith { IntArray(6400) { 0xff00ff00.toInt() } })
        repeat(40) { controller.refreshTransform() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertEquals("Completed idle previews must settle without cache eviction/refetch churn", 1, host.spriteThumbnails.size)
        assertEquals(original, host.document); assertTrue(host.queued.isEmpty())
        assertEquals(350, shownInput(view).playingCell ?: -1)
        controller.stop()
    }

    @Test fun spriteGuideAndGridModeAreSessionOnlyAndResetForAnotherDrawing() {
        val host = spriteHost(); val (controller,parent) = setup(host); val view = spriteView(controller,parent,host)
        val original = host.document
        view.host.action("grid-px",false)
        repeat(6) { view.host.action("subgrid",false) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        fun input(v: BoardChromeView) = BoardChromeView::class.java.getDeclaredField("input").apply { isAccessible=true }.get(v) as Chrome.Input
        assertTrue(input(view).gridByPixels); assertEquals(8,input(view).subGrid)
        view.host.action("subgrid",false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        assertEquals(2,input(view).subGrid)
        assertEquals(original,host.document); assertTrue(host.queued.isEmpty())
        val replacement = original.copy(id="another-drawing")
        host.document = replacement; controller.documentChanged(replacement)
        val newView = spriteView(controller,parent,host)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        assertFalse(input(newView).gridByPixels); assertEquals(2,input(newView).subGrid)
    }
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
        val spriteThumbnails = mutableListOf<Pair<List<Int>,(Map<Int,IntArray>)->Unit>>()
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
        override fun spriteThumbnails(boardId:String,cells:List<Int>,width:Int,height:Int,ready:(Map<Int,IntArray>)->Unit) { spriteThumbnails += cells to ready }
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
    @Test fun addingAndReorderingFramesSurvivesSynchronousCancellationRefresh() {
        val host = Host(document()); val (controller,parent) = setup(host)
        val id = host.document.boards.last().id
        controller.select(id)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        val view = (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<BoardChromeView>().first { it.sceneIdentity?.boardId == id }
        // This is the production adapter: roll cancellation calls stripGap, which immediately
        // refreshes sceneIdentity again. Add must publish its new identity before that callback.
        view.host.action("add",false)
        controller.documentChanged(host.document)
        assertEquals(2,host.document.boards.last().frames.size)
        assertEquals(host.document.boards.last().frames.map { it.id },view.sceneIdentity?.frameIds)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        val reversed = host.document.boards.last().frames.map { it.id }.reversed()
        host.document = RegionDocumentOps.reorderFrames(host.document,id,reversed)
        controller.documentChanged(host.document)
        assertEquals(reversed,view.sceneIdentity?.frameIds)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        // Selection changes also cancel interactions from applyInput, with the same refreshing host.
        controller.select(null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        controller.select(id)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        val builds = view.layoutBuildCount
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        assertEquals(builds,view.layoutBuildCount)
        assertEquals(id,controller.selectedBoardId)
        assertEquals(reversed,view.sceneIdentity?.frameIds)
        controller.stop()
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
    private fun penTouch(view:View,action:Int,x:Float,y:Float,tool:Int=MotionEvent.TOOL_TYPE_STYLUS): Boolean {
        val property = MotionEvent.PointerProperties().apply { id=0; toolType=tool }
        val coords = MotionEvent.PointerCoords().apply { this.x=x; this.y=y; pressure=1f }
        val event = MotionEvent.obtain(0,20,action,1,arrayOf(property),arrayOf(coords),0,0,1f,1f,0,0,0,0)
        return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun beginPlacementThroughMenu(controller: BoardRuntimeController, parent: FrameLayout): View {
        controller.showMenu()
        val menu = ShadowAlertDialog.getLatestAlertDialog()
        menu.listView.performItemClick(null,0,0L) // New Image board
        parent.measure(View.MeasureSpec.makeMeasureSpec(548,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1126,View.MeasureSpec.EXACTLY))
        parent.layout(0,0,548,1126)
        return parent.getChildAt(parent.childCount-1)
    }
    @Test fun parentDispatchedPlacementUpDetachesAfterDispatchAndCreatesOnlyOnce() {
        for (tool in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER)) {
            val host = Host(document()); val (controller,parent) = setup(host)
            val before = parent.childCount
            val overlay = beginPlacementThroughMenu(controller,parent)
            assertTrue(penTouch(parent,MotionEvent.ACTION_DOWN,320f,320f,tool))
            assertTrue(penTouch(parent,MotionEvent.ACTION_MOVE,400f,420f,tool))
            assertTrue(penTouch(parent,MotionEvent.ACTION_UP,400f,420f,tool))
            assertSame(parent,overlay.parent) // No ViewGroup mutation inside its touch dispatch.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            assertNull(overlay.parent)
            assertEquals(before,parent.childCount)
            val form = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("New board",shadowOf(form).title.toString())
            // A reentrant or repeated terminal event must not schedule another form.
            penTouch(overlay,MotionEvent.ACTION_CANCEL,400f,420f,tool)
            penTouch(overlay,MotionEvent.ACTION_UP,400f,420f,tool)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            assertSame(form,ShadowAlertDialog.getLatestAlertDialog())
            val count = host.document.boards.size
            form.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            // AlertDialog delivers its positive-button listener through the main Handler.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            assertEquals(count+1,host.document.boards.size)
            assertEquals(RectPx(320,320,80,100),host.document.boards.last().rect)
            controller.stop()
        }
    }
    @Test fun parentDispatchedPlacementCancelNeverShowsCreationForm() {
        val host = Host(document()); val (controller,parent) = setup(host)
        val before = parent.childCount
        val overlay = beginPlacementThroughMenu(controller,parent)
        val menu = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(penTouch(parent,MotionEvent.ACTION_DOWN,320f,320f))
        assertTrue(penTouch(parent,MotionEvent.ACTION_CANCEL,400f,420f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        assertNull(overlay.parent)
        assertEquals(before,parent.childCount)
        assertSame(menu,ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(document(),host.document)
        controller.stop()
    }
    @Test fun stoppingBeforeDeferredPlacementCompletionSuppressesItsForm() {
        val host = Host(document()); val (controller,parent) = setup(host)
        val overlay = beginPlacementThroughMenu(controller,parent)
        val menu = ShadowAlertDialog.getLatestAlertDialog()
        penTouch(parent,MotionEvent.ACTION_DOWN,320f,320f)
        penTouch(parent,MotionEvent.ACTION_UP,400f,420f)
        controller.stop()
        controller.resume()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        assertNull(overlay.parent)
        assertSame(menu,ShadowAlertDialog.getLatestAlertDialog())
        controller.stop()
    }
    @Test fun secondPlacementInvalidatesFirstPendingCreationForm() {
        val host = Host(document()); val (controller,parent) = setup(host)
        val first = beginPlacementThroughMenu(controller,parent)
        penTouch(parent,MotionEvent.ACTION_DOWN,320f,320f)
        penTouch(parent,MotionEvent.ACTION_UP,400f,420f)
        val second = beginPlacementThroughMenu(controller,parent)
        val menu = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        assertNull(first.parent)
        assertSame(parent,second.parent)
        assertSame(menu,ShadowAlertDialog.getLatestAlertDialog())
        penTouch(parent,MotionEvent.ACTION_DOWN,330f,330f)
        penTouch(parent,MotionEvent.ACTION_UP,410f,430f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        assertNull(second.parent)
        assertEquals("New board",shadowOf(ShadowAlertDialog.getLatestAlertDialog()).title.toString())
        ShadowAlertDialog.getLatestAlertDialog().dismiss()
        controller.stop()
    }
    @Test fun boardButtonRemainsInteractiveWhileSpriteCellsPassAllPaintingPointersThrough() {
        val host = Host(document())
        host.document = BoardDocumentOps.createSprite(host.document,"Sprite",RectPx(300,300,100,100),SpriteGrid(1,1,100,100)) { "sprite" }.doc
        val (controller,parent) = setup(host)
        val painted = mutableListOf<Int>()
        val canvas = object : View(parent.context) {
            override fun onTouchEvent(event: MotionEvent): Boolean { painted += event.actionMasked; return true }
        }
        parent.addView(canvas,0,FrameLayout.LayoutParams(-1,-1))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        parent.measure(View.MeasureSpec.makeMeasureSpec(548,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1126,View.MeasureSpec.EXACTLY))
        parent.layout(0,0,548,1126)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        val sprite = host.document.boards.last()
        val view = (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<BoardChromeView>().first { it.sceneIdentity?.boardId == sprite.id }
        fun point(target: View): FloatArray = floatArrayOf(target.left + target.width/2f,target.top + target.height/2f).also {
            view.matrix.mapPoints(it); it[0] += view.left; it[1] += view.top
        }
        // Use the actual layout's kind button and Sprite cell, not synthetic control IDs.
        val kind = point(view.getChildAt(0))
        assertTrue(penTouch(parent,MotionEvent.ACTION_DOWN,kind[0],kind[1]))
        assertTrue(penTouch(parent,MotionEvent.ACTION_UP,kind[0],kind[1]))
        assertEquals(sprite.id,controller.selectedBoardId)
        assertTrue(painted.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        val cell = point((0 until view.childCount).map { view.getChildAt(it) }.single {
            it.contentDescription?.toString()?.startsWith("Cell 1.") == true
        })
        for (tool in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER,MotionEvent.TOOL_TYPE_FINGER)) {
            painted.clear()
            assertTrue(penTouch(parent,MotionEvent.ACTION_DOWN,cell[0],cell[1],tool))
            assertTrue(penTouch(parent,MotionEvent.ACTION_MOVE,cell[0]+1,cell[1]+1,tool))
            assertTrue(penTouch(parent,MotionEvent.ACTION_UP,cell[0]+1,cell[1]+1,tool))
            assertEquals(listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP),painted)
        }
        controller.stop()
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

    @Test fun emptyThumbnailReplyWaitsForNewContentBeforeRetrying() {
        val host = Host(document()); val (controller,_) = setup(host)
        controller.select(host.document.boards.last().id)
        val count = host.thumbnails.size
        host.thumbnails.last().second(emptyMap())
        repeat(3) { controller.refreshTransform() }
        controller.documentChanged(host.document)
        assertEquals(count, host.thumbnails.size)
        host.revision++
        controller.documentChanged(host.document)
        assertEquals(count + 1, host.thumbnails.size)
        host.thumbnails.last().second(emptyMap())
        controller.refreshTransform()
        assertEquals(count + 1, host.thumbnails.size)
    }

    @Test fun partialThumbnailReplyCachesSuccessAndDefersMissingOrInvalidFrames() {
        val source = document(); val id = source.boards.last().id
        var next = source
        repeat(2) { next = RegionDocumentOps.addFrame(next,id,NewFrame.BLANK) { UUID.randomUUID().toString() }.doc }
        val host = Host(next); val (controller,parent) = setup(host)
        controller.select(id)
        val request = host.thumbnails.single()
        assertEquals(3,request.first.size)
        val first = request.first[0]
        request.second(mapOf(first to IntArray(6400) { 0xff00ff00.toInt() },request.first[1] to IntArray(1)))
        repeat(3) { controller.refreshTransform() }
        assertEquals(1,host.thumbnails.size)
        val view = (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<BoardChromeView>().first { it.sceneIdentity?.boardId == id }
        val bitmap = Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888)
        view.host.art(Canvas(bitmap),Chrome.Element("cell-0",Chrome.Shape.ROUND_RECT,Chrome.Rect(0f,0f,80f,80f),artSlot=true))
        assertEquals(0xff00ff00.toInt(),bitmap.getPixel(40,40))
        host.revision++
        controller.documentChanged(host.document)
        assertEquals(2,host.thumbnails.size)
        assertEquals(request.first,host.thumbnails.last().first)
        // A late old reply cannot clear the newer epoch's pending requests.
        request.second(emptyMap())
        controller.refreshTransform()
        assertEquals(2,host.thumbnails.size)
        host.thumbnails.last().second(request.first.associateWith { IntArray(6400) })
        controller.refreshTransform()
        assertEquals(2,host.thumbnails.size)
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
