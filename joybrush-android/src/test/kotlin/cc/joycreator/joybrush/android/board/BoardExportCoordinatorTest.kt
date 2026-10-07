package cc.joycreator.joybrush.android.board

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import cc.joycreator.joybrush.androidkit.io.BoardExportRequest
import cc.joycreator.joybrush.core.chrome.BoardExportLayout as Export
import cc.joycreator.joybrush.core.doc.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w411dp-h846dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardExportCoordinatorTest {
    private var id = 0
    private fun ids() = "test-${id++}"
    private fun base() = DocOps.newDocument("art","Art",2,1,::ids)
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
    private fun find(view: View): ExportChoiceSheet? {
        if(view is ExportChoiceSheet) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun input(sheet: ExportChoiceSheet): Export.Input = ExportChoiceSheet::class.java.getDeclaredField("shown").apply { isAccessible=true }.get(sheet) as Export.Input
    private fun tap(sheet: ExportChoiceSheet, id: String) {
        val target = Export.layout(input(sheet)).controls.single { it.id == id }.visual
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0,10,action,target.cx,target.cy,0)
            try { sheet.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        settle()
    }
    private fun open(doc: JbDocument, selected: String, ready: (BoardExportRequest,String,String) -> Unit): Pair<BoardExportCoordinator,ExportChoiceSheet> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val coordinator = BoardExportCoordinator(activity,ready,{ fail(it) })
        coordinator.show(doc,selected); settle()
        val dialog: Dialog = ShadowDialog.getLatestDialog()
        val sheet = requireNotNull(find(requireNotNull(dialog.window).decorView))
        return coordinator to sheet
    }
    @Test fun animationFrameExportsCapturedCurrentFrameAsPng() {
        val first = BoardDocumentOps.createAnimation(base(),"Walk",RectPx(0,0,1,1),::ids).doc
        val doc = RegionDocumentOps.addFrame(first,first.boards.last().id,NewFrame.BLANK,::ids).doc
        var chosen: BoardExportRequest? = null
        val (coordinator,sheet) = open(doc,doc.boards.last().id) { request,name,mime ->
            chosen = request; assertEquals("Walk.png",name); assertEquals("image/png",mime)
        }
        try {
            tap(sheet,"export-scope-frame"); tap(sheet,"export-go")
            assertEquals(Export.Scope.FRAME,chosen?.scope); assertEquals(doc.boards.last().currentFrameId,chosen?.frameId)
            assertEquals(Export.Format.PNG,chosen?.format); assertFalse(requireNotNull(chosen).folder)
        } finally { coordinator.dismiss() }
    }
    @Test fun allImagesUseFolderAndSpriteLabHandsOffTheCapturedGrid() {
        val doc = base(); var chosen: BoardExportRequest? = null
        val (images,imageSheet) = open(doc,doc.boards.single().id) { request,_,_ -> chosen=request }
        try { tap(imageSheet,"export-scope-all_images"); tap(imageSheet,"export-go"); assertTrue(requireNotNull(chosen).folder) }
        finally { images.dismiss() }
        val spriteDoc = BoardDocumentOps.createSprite(doc,"Sprite",RectPx(0,0,2,1),SpriteGrid(2,1,1,1),::ids).doc
        chosen = null
        val (sprites,spriteSheet) = open(spriteDoc,spriteDoc.boards.last().id) { request,_,_ -> chosen=request }
        try {
            assertTrue(Export.layout(input(spriteSheet)).controls.single { it.id == "export-format-sprite_lab" }.enabled)
            tap(spriteSheet,"export-format-sprite_lab")
            assertNull(chosen); assertEquals(Export.Format.SPRITE_LAB,input(spriteSheet).format)
            tap(spriteSheet,"export-go"); assertFalse(requireNotNull(chosen).folder)
            assertEquals(BoardKind.SPRITE,chosen?.kind)
            assertEquals(Export.Format.SPRITE_LAB,chosen?.format)
        } finally { sprites.dismiss() }
    }
    @Test fun pickerRecreationPreservesExactFrameRangeAndBoardTarget() {
        val first = BoardDocumentOps.createAnimation(base(),"Walk",RectPx(0,0,1,1),::ids).doc
        val doc = RegionDocumentOps.addFrame(first,first.boards.last().id,NewFrame.BLANK,::ids).doc
        val board = doc.boards.last()
        val request = BoardExportRequest.capture(doc,Export.Choice(board.id,Export.Scope.RANGE,Export.Format.PNG_FRAMES,
            board.currentFrameId!!,board.frames.last().id,board.frames.last().id),false)
        assertEquals(request,BoardExportCoordinator.restore(BoardExportCoordinator.save(request)))
        assertNull(BoardExportCoordinator.restore(android.os.Bundle()))
    }
}
