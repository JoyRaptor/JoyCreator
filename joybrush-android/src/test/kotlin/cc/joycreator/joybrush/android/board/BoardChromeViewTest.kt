package cc.joycreator.joybrush.android.board

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout as Chrome
import cc.joycreator.joybrush.core.chrome.BoardChromeIdentity
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
import kotlin.math.roundToInt

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
        assertTrue((0 until view.childCount).any { view.getChildAt(it).contentDescription?.toString()?.startsWith("Board 500,") == true })
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

    @Test fun glassFrostRefreshesBehindCachedShadowOnEveryDraw() {
        val view=view()
        val element=Chrome.Element("glass",Chrome.Shape.ROUND_RECT,Chrome.Rect(20f,20f,140f,100f),
            Chrome.Colour.GLASS,alpha=.55f,radiusPx=12f,shadowPx=3f)
        view.submit(Chrome.Layout(listOf(element),emptyList(),false,false,false))
        var frostCalls=0
        var colour=android.graphics.Color.RED
        view.host=object:BoardChromeView.Host {
            override fun frost(canvas:Canvas,element:Chrome.Element) { frostCalls++; canvas.drawColor(colour) }
        }
        val image=Bitmap.createBitmap(548,1126,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image)
        view.draw(canvas)
        assertEquals(1,frostCalls)
        val first=image.getPixel(70,60)
        val rasterizations=view.shadowRasterizations
        image.eraseColor(android.graphics.Color.TRANSPARENT)
        colour=android.graphics.Color.BLUE
        view.invalidate(); view.draw(canvas)
        assertEquals(2,frostCalls)
        assertEquals(rasterizations,view.shadowRasterizations)
        assertTrue(android.graphics.Color.red(first) > android.graphics.Color.blue(first))
        val second=image.getPixel(70,60)
        assertTrue(android.graphics.Color.blue(second) > android.graphics.Color.red(second))
        assertEquals(0,android.graphics.Color.alpha(image.getPixel(2,2)))
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

    @Test fun slidingAwayFromAButtonDoesNotActivateItOnRelease() {
        val view=view();var actions=0
        view.host=object:BoardChromeView.Host {override fun action(id:String,held:Boolean){actions++}}
        view.show(input());frame()
        touch(view,MotionEvent.ACTION_DOWN,73f,119f)
        touch(view,MotionEvent.ACTION_MOVE,400f,500f)
        touch(view,MotionEvent.ACTION_UP,73f,119f)
        frame();assertEquals(0,actions)
    }

    @Test fun holdingPlayRequestsFpsWithoutTogglingPlaybackOnRelease() {
        val view=view();val actions=mutableListOf<Pair<String,Boolean>>()
        view.host=object:BoardChromeView.Host {override fun action(id:String,held:Boolean){actions+=id to held}}
        val state=input().copy(kind=BoardKind.ANIMATION,selected=true)
        view.show(state);frame()
        val play=Chrome.layout(state).controls.first {it.id=="play"}.visual
        touch(view,MotionEvent.ACTION_DOWN,play.cx,play.cy)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        touch(view,MotionEvent.ACTION_UP,play.cx,play.cy)
        frame();assertEquals(listOf("play" to true),actions)
    }

    @Test fun mouseHoverStartsOnceRestoresPriorFrameAndStopsOnLifecycleExit() {
        val view=view();val events=mutableListOf<Pair<Boolean,Int?>>()
        view.host=object:BoardChromeView.Host {override fun hoverLoop(playing:Boolean,restoreFrame:Int?){events+=playing to restoreFrame}}
        val state=input().copy(kind=BoardKind.ANIMATION,currentFrame=2,holds=listOf(1,1,1))
        view.show(state);frame()
        fun hover(action:Int,tool:Int) {
            val properties=MotionEvent.PointerProperties().also {it.id=0;it.toolType=tool}
            val coords=MotionEvent.PointerCoords().also {it.x=73f;it.y=119f}
            val event=MotionEvent.obtain(0,10,action,1,arrayOf(properties),arrayOf(coords),0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_MOUSE,0)
            try {view.dispatchGenericMotionEvent(event)} finally {event.recycle()}
        }
        hover(MotionEvent.ACTION_HOVER_ENTER,MotionEvent.TOOL_TYPE_FINGER)
        assertTrue(events.isEmpty())
        hover(MotionEvent.ACTION_HOVER_MOVE,MotionEvent.TOOL_TYPE_MOUSE)
        hover(MotionEvent.ACTION_HOVER_MOVE,MotionEvent.TOOL_TYPE_MOUSE)
        assertEquals(listOf(true to null),events)
        view.stopInteractions()
        assertEquals(listOf(true to null,false to 2),events)
        view.stopInteractions();assertEquals(2,events.size)
    }

    private fun animation() = input().copy(kind = BoardKind.ANIMATION, selected = true, holds = listOf(1, 2, 1))
    private fun attachStrip(view: BoardChromeView, state: Chrome.Input = animation()) {
        view.sceneIdentity = BoardChromeIdentity("walk", listOf("left", "middle", "right"))
        view.show(state); frame()
    }
    @Test fun animationUsesSharedOutlineStockAndWeightedRendererWithNestedTargets() {
        val view = view(); attachStrip(view)
        val strip = (0 until view.childCount).map { view.getChildAt(it) }
            .filterIsInstance<com.fadcam.ui.faditor.sprite.FilmStrip>().single()
        assertEquals(1, strip.childCount) // OUTLINE has no filled stock or solid perforation bars.
        val row = strip.frames()
        assertEquals(0, row.paddingLeft); assertEquals(0, row.paddingTop)
        assertEquals(1, (0 until row.childCount).count {
            row.getChildAt(it) is com.fadcam.ui.faditor.sprite.WeightedScrubBar
        })
        for (control in Chrome.layout(animation()).controls.filter { it.id.startsWith("cell-") }) {
            val target = (0 until row.childCount).map { row.getChildAt(it) }
                .single { it.contentDescription == control.tooltip }
            assertSame(row, target.parent)
            assertEquals(control.hit.left.roundToInt(), target.left)
            assertEquals(control.hit.top.roundToInt(), target.top)
            assertEquals(control.hit.right.roundToInt(), target.right)
        }
    }

    @Test fun nestedFrameTargetsKeepBoardCoordinatesAtDensityAndScroll() {
        for (density in listOf(1f, 2f)) {
            val view = view(); val frames = mutableListOf<String>()
            view.host = object : BoardChromeView.Host {
                override fun stripScrub(boardId: String, frameId: String) { frames += frameId }
            }
            val state = animation().copy(density = density, stripScrollPx = 44f * density,
                board = Chrome.Rect(100f, 100f, 524f, 226f))
            attachStrip(view, state)
            val middle = Chrome.layout(state).controls.first { it.id == "cell-1" }.visual
            assertTrue(touch(view, MotionEvent.ACTION_DOWN, middle.cx, middle.cy))
            assertTrue(touch(view, MotionEvent.ACTION_UP, middle.cx, middle.cy))
            assertEquals(listOf("middle"), frames)
        }
    }

    @Test fun foldingAndChangingKindReleaseNestedFrameTargets() {
        val view = view(); attachStrip(view)
        view.show(animation().copy(penDown = true)); frame()
        val strip = (0 until view.childCount).map { view.getChildAt(it) }
            .filterIsInstance<com.fadcam.ui.faditor.sprite.FilmStrip>().single()
        val bar = (0 until strip.frames().childCount).map { strip.frames().getChildAt(it) }
            .filterIsInstance<com.fadcam.ui.faditor.sprite.WeightedScrubBar>().single()
        assertTrue(Chrome.layout(animation().copy(penDown = true)).folded)
        assertEquals(0, bar.frameAt(0f, 0f, 14f))
        assertEquals(1, bar.frameAt(14f, 0f, 14f))
        assertEquals(2, bar.frameAt(42f, 0f, 14f))
        view.show(input().copy(kind = BoardKind.SPRITE, selected = true)); frame()
        assertEquals(1, strip.frames().childCount) // Only the shared bar; no stale animation targets.
        assertEquals(-1, bar.frameAt(0f, 0f, 14f))
    }

    @Test fun spriteCellSizeTextAndPreviewSurviveAnimationRoundTrip() {
        val state = input().copy(kind = BoardKind.SPRITE, selected = true,
            spriteOrder = listOf(0, 1), board = Chrome.Rect(100f, 160f, 324f, 286f))
        fun render(view: BoardChromeView): Bitmap = Bitmap.createBitmap(548, 1126, Bitmap.Config.ARGB_8888)
            .also { view.draw(Canvas(it)) }
        fun artHost() = object : BoardChromeView.Host {
            override fun art(canvas: Canvas, element: Chrome.Element) {
                if (element.id == "preview-art") canvas.drawColor(android.graphics.Color.GREEN)
            }
        }
        val fresh = view(); fresh.host = artHost(); fresh.show(state); frame()
        val expected = render(fresh)
        val label = Chrome.layout(state).elements.first { it.id == "cell-size" }.rect
        var labelPixels = 0
        for (y in label.top.toInt() until label.bottom.toInt())
            for (x in label.left.toInt().coerceAtLeast(0) until label.right.toInt().coerceAtMost(expected.width))
                if (android.graphics.Color.alpha(expected.getPixel(x, y)) > 0) labelPixels++
        assertTrue("Sprite's real cell-size text must be drawn, not swallowed as an animation frame", labelPixels > 0)
        val preview = Chrome.layout(state).elements.first { it.id == "preview-art" }.rect
        assertEquals(android.graphics.Color.GREEN, expected.getPixel(preview.cx.toInt(), preview.cy.toInt()))
        val transitioned = view(); transitioned.host = artHost(); attachStrip(transitioned)
        transitioned.show(state); frame()
        val strip = (0 until transitioned.childCount).map { transitioned.getChildAt(it) }
            .filterIsInstance<com.fadcam.ui.faditor.sprite.FilmStrip>().single()
        assertEquals(View.GONE, strip.visibility)
        val sizeTarget = (0 until transitioned.childCount).map { transitioned.getChildAt(it) }
            .single { it.contentDescription == Chrome.layout(state).controls.first { c -> c.id == "cell-size" }.tooltip }
        assertSame(transitioned, sizeTarget.parent)
        val actual = render(transitioned)
        val before = IntArray(548 * 1126); val after = IntArray(before.size)
        expected.getPixels(before, 0, 548, 0, 0, 548, 1126)
        actual.getPixels(after, 0, 548, 0, 0, 548, 1126)
        assertArrayEquals("Sprite rendering must agree before and after an Animation scene", before, after)
        attachStrip(transitioned)
        assertEquals(View.VISIBLE, strip.visibility)
        val frameTargets = (0 until strip.frames().childCount).map { strip.frames().getChildAt(it) }
            .filter { it.contentDescription?.toString()?.startsWith("Frame ") == true }
        assertEquals(3, frameTargets.size)
        assertTrue(frameTargets.all { it.parent === strip.frames() })
    }

    private data class Hold(val board: String, val frame: String, val before: Int,
                            val dx: Float, val density: Float, val finished: Boolean)

    @Test fun edgeDragPreviewsThenCommitsOnceAgainstOriginalHoldAndId() {
        val view = view(); val updates = mutableListOf<Hold>(); val actions = mutableListOf<String>()
        view.host = object : BoardChromeView.Host {
            override fun stripHold(boardId: String, frameId: String, holdAtDown: Int, dragPx: Float, density: Float, finished: Boolean) {
                updates += Hold(boardId, frameId, holdAtDown, dragPx, density, finished)
                if (!finished) view.show(animation().copy(holds = listOf(1, 3, 1)))
            }
            override fun action(id: String, held: Boolean) { actions += id }
        }
        attachStrip(view)
        // Middle cell: starts at 152, width 88, visible right edge 236; the 4 dp visual gap is not duration.
        touch(view, MotionEvent.ACTION_DOWN, 235f, 275f)
        touch(view, MotionEvent.ACTION_MOVE, 279f, 275f)
        frame() // A transient preview changes displayed holds while the original gesture stays anchored.
        touch(view, MotionEvent.ACTION_MOVE, 301f, 275f)
        touch(view, MotionEvent.ACTION_UP, 301f, 275f)
        assertFalse(touch(view, MotionEvent.ACTION_UP, 301f, 275f))
        assertEquals(listOf(Hold("walk", "middle", 2, 44f, 1f, false),
            Hold("walk", "middle", 2, 66f, 1f, false), Hold("walk", "middle", 2, 66f, 1f, true)), updates)
        assertTrue(actions.isEmpty())
    }

    @Test fun bodyDragScrubsAcrossHeldCellsWithStableIdsAndNoDuplicateRelease() {
        val view = view(); val frames = mutableListOf<String>(); var scrolls = 0; var holds = 0
        view.host = object : BoardChromeView.Host {
            override fun stripScrub(boardId: String, frameId: String) { assertEquals("walk", boardId); frames += frameId }
            override fun stripScrollBy(pixels: Int) { scrolls++ }
            override fun stripHold(boardId: String, frameId: String, holdAtDown: Int, dragPx: Float, density: Float, finished: Boolean) { holds++ }
        }
        attachStrip(view)
        touch(view, MotionEvent.ACTION_DOWN, 116f, 275f)
        touch(view, MotionEvent.ACTION_MOVE, 161f, 275f)
        touch(view, MotionEvent.ACTION_MOVE, 211f, 275f) // Still the same two-tick middle frame.
        touch(view, MotionEvent.ACTION_MOVE, 251f, 275f)
        touch(view, MotionEvent.ACTION_MOVE, 110f, 275f)
        touch(view, MotionEvent.ACTION_UP, 110f, 275f)
        assertEquals(listOf("middle", "right", "left"), frames)
        assertEquals(0, scrolls); assertEquals(0, holds)
    }

    @Test fun stripTapUsesStableIdAndScrollOffsetRatherThanCellNumberAction() {
        val view = view(); val frames = mutableListOf<String>(); var actions = 0
        view.host = object : BoardChromeView.Host {
            override fun stripScrub(boardId: String, frameId: String) { frames += frameId }
            override fun action(id: String, held: Boolean) { actions++ }
        }
        attachStrip(view, animation().copy(stripScrollPx = 44f))
        touch(view, MotionEvent.ACTION_DOWN, 116f, 275f)
        touch(view, MotionEvent.ACTION_UP, 116f, 275f)
        assertEquals(listOf("middle"), frames); assertEquals(0, actions)
    }

    @Test fun tappingOneTickCellCentersAndEdgesSelectsWithoutChangingDuration() {
        for (density in listOf(1f, 2f)) {
            val view = view(); val selected = mutableListOf<String>(); var holds = 0; var cancellations = 0
            view.host = object : BoardChromeView.Host {
                override fun stripScrub(boardId: String, frameId: String) { selected += frameId }
                override fun stripHold(boardId: String, frameId: String, holdAtDown: Int, dragPx: Float, density: Float, finished: Boolean) { holds++ }
                override fun stripHoldCancelled(boardId: String, frameId: String) { cancellations++ }
            }
            val state = animation().copy(holds = listOf(1, 1, 1), density = density,
                board = Chrome.Rect(100f, 100f, 524f, 226f))
            attachStrip(view, state)
            for (index in 0..2) {
                val cell = Chrome.layout(state).elements.first { it.id == "cell-$index" }.rect
                for (x in listOf(cell.cx, cell.right - 2f * density)) {
                    assertTrue(touch(view, MotionEvent.ACTION_DOWN, x, cell.cy))
                    assertTrue(touch(view, MotionEvent.ACTION_UP, x, cell.cy))
                }
            }
            assertEquals(listOf("left", "left", "middle", "middle", "right", "right"), selected)
            assertEquals(0, holds); assertEquals(0, cancellations)
        }
    }

    @Test fun stationaryLongPressOnOneTickCenterLiftsEvenInsideDurationEdgeArea() {
        val view = view(); val lifted = mutableListOf<Int>(); var holds = 0; var scrubs = 0
        view.host = object : BoardChromeView.Host {
            override fun stripLift(index: Int, dx: Float, dy: Float, removing: Boolean, active: Boolean) { if (active) lifted += index }
            override fun stripHold(boardId: String, frameId: String, holdAtDown: Int, dragPx: Float, density: Float, finished: Boolean) { holds++ }
            override fun stripScrub(boardId: String, frameId: String) { scrubs++ }
        }
        val state = animation().copy(holds = listOf(1, 1, 1))
        attachStrip(view, state)
        val cell = Chrome.layout(state).elements.first { it.id == "cell-1" }.rect
        touch(view, MotionEvent.ACTION_DOWN, cell.cx, cell.cy)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        touch(view, MotionEvent.ACTION_MOVE, cell.cx + 44f, cell.cy - 25f)
        touch(view, MotionEvent.ACTION_CANCEL, cell.cx + 44f, cell.cy - 25f)
        assertTrue(lifted.isNotEmpty()); assertEquals(1, lifted.first())
        assertEquals(0, holds); assertEquals(0, scrubs)
    }

    @Test fun cancelSecondFingerAndSceneReorderNeverCommitDuration() {
        for (end in listOf("cancel", "second-finger", "reorder", "host", "stop")) {
            val view = view(); val updates = mutableListOf<Hold>(); val cancelled = mutableListOf<String>()
            view.host = object : BoardChromeView.Host {
                override fun stripHold(boardId: String, frameId: String, holdAtDown: Int, dragPx: Float, density: Float, finished: Boolean) {
                    updates += Hold(boardId, frameId, holdAtDown, dragPx, density, finished)
                }
                override fun stripHoldCancelled(boardId: String, frameId: String) { cancelled += "$boardId/$frameId" }
            }
            attachStrip(view)
            touch(view, MotionEvent.ACTION_DOWN, 235f, 275f)
            touch(view, MotionEvent.ACTION_MOVE, 279f, 275f)
            when (end) {
                "cancel" -> touch(view, MotionEvent.ACTION_CANCEL, 279f, 275f)
                "second-finger" -> touch(view, MotionEvent.ACTION_POINTER_DOWN, 279f, 275f)
                "reorder" -> view.sceneIdentity = BoardChromeIdentity("walk", listOf("right", "middle", "left"))
                "host" -> view.host = object : BoardChromeView.Host {}
                else -> view.stopInteractions()
            }
            touch(view, MotionEvent.ACTION_UP, 279f, 275f)
            assertEquals(listOf("walk/middle"), cancelled)
            assertEquals(1, updates.size); assertFalse(updates.single().finished)
        }
    }

    @Test fun durationDragKeepsScreenDeltaAndDensityForSharedRounding() {
        val view = view(); val updates = mutableListOf<Hold>()
        view.host = object : BoardChromeView.Host {
            override fun stripHold(boardId: String, frameId: String, holdAtDown: Int, dragPx: Float, density: Float, finished: Boolean) {
                updates += Hold(boardId, frameId, holdAtDown, dragPx, density, finished)
            }
        }
        val state = animation().copy(density = 2f, board = Chrome.Rect(100f, 100f, 524f, 226f))
        attachStrip(view, state)
        val edge = Chrome.layout(state).elements.first { it.id == "cell-0" }.rect
        touch(view, MotionEvent.ACTION_DOWN, edge.right - 2f, edge.cy)
        touch(view, MotionEvent.ACTION_MOVE, edge.right - 90f, edge.cy)
        touch(view, MotionEvent.ACTION_UP, edge.right - 90f, edge.cy)
        assertEquals(Hold("walk", "left", 1, -88f, 2f, true), updates.last())
    }

    @Test fun holdingCellBodyStillLiftsForReorderingInsteadOfScrubbing() {
        val view = view(); val lifted = mutableListOf<Int>(); val scrubbed = mutableListOf<String>()
        view.host = object : BoardChromeView.Host {
            override fun stripLift(index: Int, dx: Float, dy: Float, removing: Boolean, active: Boolean) { if (active) lifted += index }
            override fun stripScrub(boardId: String, frameId: String) { scrubbed += frameId }
        }
        attachStrip(view)
        touch(view, MotionEvent.ACTION_DOWN, 116f, 275f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        touch(view, MotionEvent.ACTION_MOVE, 161f, 250f)
        touch(view, MotionEvent.ACTION_CANCEL, 161f, 250f)
        assertTrue(lifted.isNotEmpty()); assertEquals(0, lifted.first()); assertTrue(scrubbed.isEmpty())
    }

    @Test fun replacementIdentityCannotUsePreviousScenesGeometryBeforeNextUiFrame() {
        val view = view(); val frames = mutableListOf<String>()
        view.host = object : BoardChromeView.Host {
            override fun stripScrub(boardId: String, frameId: String) { frames += "$boardId/$frameId" }
        }
        attachStrip(view)
        view.sceneIdentity = BoardChromeIdentity("jump", listOf("up", "middle", "down"))
        assertFalse(touch(view, MotionEvent.ACTION_DOWN, 116f, 275f))
        view.show(animation()); frame()
        touch(view, MotionEvent.ACTION_DOWN, 116f, 275f)
        touch(view, MotionEvent.ACTION_UP, 116f, 275f)
        assertEquals(listOf("jump/up"), frames)
    }
}
