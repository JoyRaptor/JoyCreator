package cc.joycreator.joybrush.android.board

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout as Chrome
import cc.joycreator.joybrush.core.doc.BoardKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Actual Android View input/render tests on the owner's API level, without installing an app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w548dp-h1126dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardChromeViewTest {
    private val board = Chrome.Rect(100f, 100f, 324f, 226f)
    private fun input() = Chrome.Input(board, 1f, BoardKind.CANVAS)
    private fun view(): BoardChromeView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return BoardChromeView(activity).also {
            activity.setContentView(it)
            it.measure(View.MeasureSpec.makeMeasureSpec(548, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1126, View.MeasureSpec.EXACTLY))
            it.layout(0, 0, 548, 1126)
        }
    }
    private fun frame() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) }
    private fun touch(view: BoardChromeView, action: Int, x: Float, y: Float): Boolean {
        val event = MotionEvent.obtain(0, 10, action, x, y, 0)
        return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun penRateOffersBuildOneLatestLayoutAndUnchangedSceneDoesNothing() {
        val view = view()
        for (n in 0..500) view.show(input().copy(name = "Board $n"))
        assertEquals(0L, view.layoutBuildCount)
        frame()
        assertEquals(1L, view.layoutBuildCount)
        assertTrue(view.getChildAt(0).contentDescription.toString().startsWith("Board 500,"))
        view.show(input().copy(name = "Board 500")); frame()
        assertEquals(1L, view.layoutBuildCount)
    }

    @Test fun passiveBoardAndGapsLetPaintingReceiveDown() {
        val view = view(); view.show(input()); frame()
        assertFalse(touch(view, MotionEvent.ACTION_DOWN, 200f, 150f))
        assertFalse(touch(view, MotionEvent.ACTION_DOWN, 73f, 170f))
    }

    @Test fun penSamplesInsideSelectedBoardDoNotRebuildTheSameChrome() {
        val view = view(); val selected = input().copy(selected = true)
        view.show(selected); frame()
        val count = view.layoutBuildCount
        for (n in 0..500) view.show(selected.copy(pen = Chrome.Point(110f + n / 10f, 140f)))
        frame()
        assertEquals(count, view.layoutBuildCount)
    }

    @Test fun overlappingTouchTargetsChooseClosestAndCancelNeverClicks() {
        val view = view()
        val actions = mutableListOf<String>()
        view.host = object : BoardChromeView.Host {
            override fun action(id: String, held: Boolean) { actions += id }
        }
        val state = input().copy(selected = true)
        view.show(state); frame()
        val feature = Chrome.layout(state).controls.first { it.id == "feature" }.visual
        assertTrue(touch(view, MotionEvent.ACTION_DOWN, feature.cx, feature.cy))
        assertTrue(touch(view, MotionEvent.ACTION_UP, feature.cx, feature.cy))
        frame()
        assertEquals(listOf("feature"), actions)
        assertTrue(touch(view, MotionEvent.ACTION_DOWN, feature.cx, feature.cy))
        touch(view, MotionEvent.ACTION_CANCEL, feature.cx, feature.cy)
        assertFalse(touch(view, MotionEvent.ACTION_UP, feature.cx, feature.cy))
        frame()
        assertEquals(listOf("feature"), actions)
    }

    @Test fun hostCanReservePenOverAControlForPainting() {
        val view = view()
        view.host = object : BoardChromeView.Host {
            override fun acceptsPointer(id: String, event: MotionEvent) = false
        }
        view.show(input()); frame()
        assertFalse(touch(view, MotionEvent.ACTION_DOWN, 73f, 119f))
        assertFalse(touch(view, MotionEvent.ACTION_UP, 73f, 119f))
    }

    @Test fun stoppingDropsPendingUpdatesAndCapture() {
        val view = view(); view.show(input()); view.stopInteractions(); frame()
        assertEquals(0L, view.layoutBuildCount)
        view.show(input()); frame()
        assertEquals(1L, view.layoutBuildCount)
        assertTrue(touch(view, MotionEvent.ACTION_DOWN, 73f, 119f))
        view.stopInteractions()
        assertFalse(touch(view, MotionEvent.ACTION_UP, 73f, 119f))
    }

    @Test fun passiveShadowCacheIsSmallAndDrawingReusesRaster() {
        val view = view(); view.show(input()); frame()
        assertEquals(View.LAYER_TYPE_NONE, view.layerType)
        val image = Bitmap.createBitmap(548, 1126, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        view.draw(canvas)
        assertTrue(view.cachedShadowBytes in 1..4096)
        val rasterizations = view.shadowRasterizations
        view.draw(canvas)
        assertEquals(rasterizations, view.shadowRasterizations)
    }

    @Test fun selectedFadeUsesCachedShadowsAndWiggleDoesNotRasterizeWholeCellsAgain() {
        val view = view()
        val image = Bitmap.createBitmap(548, 1126, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val selected = input().copy(selected = true)
        view.show(selected); frame(); view.draw(canvas)
        val beforeFade = view.shadowRasterizations
        view.show(selected.copy(penDown = true)); frame(); view.draw(canvas)
        assertEquals(beforeFade, view.shadowRasterizations)
        val sprite = input().copy(kind = BoardKind.SPRITE, selected = true, armed = true)
        view.show(sprite); frame(); view.draw(canvas)
        val beforeWiggle = view.shadowRasterizations
        view.show(sprite.copy(wiggleElapsedMs = 1)); frame(); view.draw(canvas)
        assertEquals(beforeWiggle, view.shadowRasterizations)
        assertTrue(view.cachedShadowBytes < 4 * 1024 * 1024)
    }

    @Test fun wheelStepsOneFramePerNineDpAndDoesNotEmitAResizeDrop() {
        val view=view();val frames=mutableListOf<Int>();val drops=mutableListOf<String>()
        view.host=object:BoardChromeView.Host {
            override fun frame(frame:Int){frames+=frame}
            override fun drag(id:String,from:Chrome.Point,to:Chrome.Point,finished:Boolean){drops+=id}
        }
        val state=input().copy(kind=BoardKind.ANIMATION,selected=true,holds=listOf(1,1,1))
        view.show(state);frame()
        val wheel=Chrome.layout(state).controls.first {it.id=="wheel"}.visual
        touch(view,MotionEvent.ACTION_DOWN,wheel.cx,wheel.cy)
        touch(view,MotionEvent.ACTION_MOVE,wheel.cx,wheel.cy-9f)
        touch(view,MotionEvent.ACTION_UP,wheel.cx,wheel.cy-9f)
        assertEquals(listOf(2),frames);assertTrue(drops.isEmpty())
    }

    @Test fun replacingHostCancelsOldControlGesture() {
        val view=view();var oldActions=0;var newActions=0
        view.host=object:BoardChromeView.Host {override fun action(id:String,held:Boolean){oldActions++}}
        view.show(input());frame()
        touch(view,MotionEvent.ACTION_DOWN,73f,119f)
        view.host=object:BoardChromeView.Host {override fun action(id:String,held:Boolean){newActions++}}
        assertFalse(touch(view,MotionEvent.ACTION_UP,73f,119f))
        frame();assertEquals(0,oldActions);assertEquals(0,newActions)
    }
}
