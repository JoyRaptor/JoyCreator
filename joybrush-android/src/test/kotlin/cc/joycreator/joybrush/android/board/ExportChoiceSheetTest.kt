package cc.joycreator.joybrush.android.board

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import cc.joycreator.joybrush.core.chrome.BoardChromeIdentity
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout as Chrome
import cc.joycreator.joybrush.core.chrome.BoardExportLayout as Export
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], qualifiers="w548dp-h1126dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportChoiceSheetTest {
    private fun input()=Export.Input(Chrome.Rect(0f,600f,352f,836f),1f,"walk","Walk cycle",
        (1..6).map {"f$it"},"f3","f2","f5",1920,1080,"12",3,.22f)
    private fun sheet():ExportChoiceSheet {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        return ExportChoiceSheet(activity).also {
            activity.setContentView(it)
            it.measure(View.MeasureSpec.makeMeasureSpec(548,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1126,View.MeasureSpec.EXACTLY))
            it.layout(0,0,548,1126)
        }
    }
    private fun touch(view:ExportChoiceSheet,action:Int,x:Float,y:Float):Boolean {
        val event=MotionEvent.obtain(0,10,action,x,y,0)
        return try {view.dispatchTouchEvent(event)} finally {event.recycle()}
    }
    private fun settle(){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))}

    private fun control(view:ExportChoiceSheet, descriptionPrefix:String):View {
        val chrome=view.getChildAt(0) as ViewGroup
        return (0 until chrome.childCount).map { chrome.getChildAt(it) }
            .first { it.contentDescription?.toString()?.startsWith(descriptionPrefix)==true }
    }

    @Test fun accessibleChoicesExposeCheckedStateAndUpdateAfterSelection() {
        val view=sheet()
        val state=input().copy(availableScopes=Export.Scope.entries.toSet(),
            availableFormats=Export.chipFormats.toSet(),exportEnabled=true)
        view.show(state)
        val animation=control(view,"Animation")
        val range=control(view,"A range")
        assertEquals("android.widget.RadioButton",animation.createAccessibilityNodeInfo().className)
        assertTrue(animation.createAccessibilityNodeInfo().isChecked)
        assertFalse(range.createAccessibilityNodeInfo().isChecked)
        view.show(state.copy(scope=Export.Scope.RANGE,format=Export.Format.PNG_FRAMES))
        assertFalse(animation.createAccessibilityNodeInfo().isChecked)
        assertTrue(range.createAccessibilityNodeInfo().isChecked)
        assertTrue(control(view,"PNG frames").createAccessibilityNodeInfo().isChecked)
        assertFalse(control(view,"GIF").createAccessibilityNodeInfo().isChecked)
    }

    @Test fun exportAccessibilityIdentifiesExactScopeAndUnavailableChoices() {
        val view=sheet()
        view.show(input().copy(scope=Export.Scope.FRAME,format=Export.Format.PNG,
            availableScopes=setOf(Export.Scope.FRAME),availableFormats=setOf(Export.Format.PNG),
            displayedFormats=listOf(Export.Format.PNG),exportEnabled=true))
        val export=control(view,"Export Walk cycle")
        assertEquals("android.widget.Button",export.createAccessibilityNodeInfo().className)
        assertTrue(export.contentDescription.toString().contains("This frame, 3"))
        assertTrue(export.contentDescription.toString().contains("1920 by 1080 pixels"))
        assertFalse(control(view,"Animation").createAccessibilityNodeInfo().isEnabled)
        assertEquals("Export Walk cycle",view.accessibilityPaneTitle)
    }

    @Test fun spriteBudgetIsDescribedAsCellsRatherThanAnimationFrames() {
        val view=sheet()
        view.show(input().copy(cellCount=24))
        val budget=control(view,"cells 24")
        assertEquals("cells 24 × layers 3, 22% of memory budget",budget.contentDescription)
        assertFalse(budget.createAccessibilityNodeInfo().isEnabled)
    }

    @Test fun glassIsTranslucentAndFrostIsClippedPresentationOnly() {
        val view=sheet();var frosts=0
        view.host=object:ExportChoiceSheet.Host {
            override fun frost(canvas:Canvas,element:Chrome.Element){frosts++}
        }
        view.show(input());settle()
        val bitmap=Bitmap.createBitmap(548,1126,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        assertEquals(140,Color.alpha(bitmap.getPixel(2,750)))
        assertEquals(0,Color.alpha(bitmap.getPixel(2,500)))
        assertEquals(1,frosts)
    }

    @Test fun disabledSheetDoesNotExportOrLetTapPaintBehindIt() {
        val view=sheet();var exports=0
        view.host=object:ExportChoiceSheet.Host {override fun export(choice:Export.Choice){exports++}}
        view.show(input());settle()
        assertTrue(touch(view,MotionEvent.ACTION_DOWN,176f,808f))
        assertTrue(touch(view,MotionEvent.ACTION_UP,176f,808f))
        assertFalse(touch(view,MotionEvent.ACTION_DOWN,176f,500f))
        assertEquals(0,exports)
    }

    @Test fun enabledExportReturnsTypedSnapshotAndCancelReturnsNothing() {
        val view=sheet();val choices=mutableListOf<Export.Choice>();val scopes=mutableListOf<Export.Scope>()
        view.host=object:ExportChoiceSheet.Host {
            override fun export(choice:Export.Choice){choices+=choice}
            override fun scope(board:BoardChromeIdentity,scope:Export.Scope){assertEquals("walk",board.boardId);scopes+=scope}
        }
        val state=input().copy(availableScopes=Export.Scope.entries.toSet(),availableFormats=Export.chipFormats.toSet(),exportEnabled=true)
        view.show(state);settle()
        touch(view,MotionEvent.ACTION_DOWN,176f,808f);touch(view,MotionEvent.ACTION_UP,176f,808f);settle()
        assertEquals(listOf(state.choice()),choices)
        touch(view,MotionEvent.ACTION_DOWN,176f,808f);touch(view,MotionEvent.ACTION_CANCEL,176f,808f)
        touch(view,MotionEvent.ACTION_UP,176f,808f);settle()
        assertEquals(1,choices.size)
        touch(view,MotionEvent.ACTION_DOWN,176f,687f);touch(view,MotionEvent.ACTION_UP,176f,687f);settle()
        assertEquals(listOf(Export.Scope.RANGE),scopes)
    }

    @Test fun frameChoiceUsesPointerDownSnapshotEvenIfPreviewChangesBeforeLift() {
        val view=sheet();val choices=mutableListOf<Export.Choice>()
        view.host=object:ExportChoiceSheet.Host {override fun export(choice:Export.Choice){choices+=choice}}
        val state=input().copy(scope=Export.Scope.FRAME,format=Export.Format.PNG,
            availableScopes=setOf(Export.Scope.FRAME),availableFormats=setOf(Export.Format.PNG),exportEnabled=true)
        view.show(state);settle()
        touch(view,MotionEvent.ACTION_DOWN,176f,808f)
        view.show(state.copy(currentFrameId="f4"))
        touch(view,MotionEvent.ACTION_UP,176f,808f);settle()
        assertEquals(listOf(state.choice()),choices)
    }
}
