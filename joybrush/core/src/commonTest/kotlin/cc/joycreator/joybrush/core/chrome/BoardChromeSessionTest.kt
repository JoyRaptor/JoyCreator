package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.doc.*
import kotlin.test.*

class BoardChromeSessionTest {
    private val page = RectPx(0, 0, 1920, 1080)
    private val image = Board("image", "Image", BoardKind.CANVAS, RectPx(17, 29, 320, 180))
    private val animation = image.copy(id = "animation", kind = BoardKind.ANIMATION,
        frames = listOf(Frame("first", 1), Frame("second", 3)))

    @Test fun selectedBoardScopesEveryLayerToBoardBounds() {
        val scope = BoardLayerPreview.scope(page, image, true)
        assertEquals(image.rect, scope.bounds)
        assertEquals(image.id, scope.boardId)
        assertNull(scope.frameId)
        assertEquals(scope, scope.key("bottom", 1).scope)
        assertEquals(scope, scope.key("top", 1).scope)
        assertEquals(RectPx(17, 29, 320, 180), image.rect) // Preview never edits the board.
    }

    @Test fun makingBoardPassiveRestoresNormalPreviews() {
        val normal = BoardLayerPreview(null, page, null)
        assertEquals(normal, BoardLayerPreview.scope(page, image, false))
        assertEquals(normal, BoardLayerPreview.scope(page, null, true))
        assertEquals(normal, BoardLayerPreview.scope(page, animation, false, "second"))
    }

    @Test fun animationPreviewFollowsCurrentFrameAndOtherBoardUsesItsOwnBounds() {
        val first = BoardLayerPreview.scope(page, animation, true, "first")
        val second = BoardLayerPreview.scope(page, animation, true, "second")
        assertEquals(first.bounds, second.bounds)
        assertNotEquals(first.key("layer", 7), second.key("layer", 7))
        assertNotEquals(second, BoardLayerPreview.scope(page, image, true))
        assertEquals("second", second.frameId)
        assertFailsWith<IllegalArgumentException> { BoardLayerPreview.scope(page, animation, true) }
        assertFailsWith<IllegalArgumentException> { BoardLayerPreview.scope(page, animation, true, "deleted") }
    }

    @Test fun previewKeyChangesOnlyForScopeLayerOrContent() {
        val scope = BoardLayerPreview.scope(page, image, true)
        assertEquals(scope.key("layer", 2), scope.key("layer", 2))
        assertNotEquals(scope.key("layer", 2), scope.key("layer", 3))
        assertNotEquals(scope.key("layer", 2), scope.key("other", 2))
        assertNotEquals(scope.key("layer", 2), scope.copy(bounds = image.rect.copy(w = 321)).key("layer", 2))
    }

    @Test fun frameGestureUsesItsFrozenIdsAcrossReorderAndDeletion() {
        val mutableIds = mutableListOf("first", "second")
        val snapshot = BoardChromeIdentity(animation.id, mutableIds)
        mutableIds.reverse()
        assertEquals("first", snapshot.frame(1))
        assertEquals("second", snapshot.liftedFrame(1))
        assertEquals("second", snapshot.insertBefore(1))
        assertNull(snapshot.insertBefore(2))
        assertTrue(snapshot.stillCurrent(animation))
        assertFalse(snapshot.stillCurrent(animation.copy(frames = animation.frames.reversed())))
        assertFalse(snapshot.stillCurrent(animation.copy(frames = listOf(animation.frames.last()))))
        assertFalse(snapshot.stillCurrent(animation.copy(id = "other")))
        assertNull(snapshot.frame(0)); assertNull(snapshot.frame(3))
        assertFailsWith<IllegalArgumentException> { snapshot.insertBefore(3) }
        assertFailsWith<IllegalArgumentException> { BoardChromeIdentity("board", listOf("same", "same")) }
    }

    @Test fun manyChromeUpdatesScheduleOneFrameAndUseLatestState() {
        val queue = BoardChromeFrameQueue<Int>()
        assertTrue(queue.offer(0))
        for (n in 1..500) assertFalse(queue.offer(n))
        assertEquals(500, queue.take())
        assertNull(queue.take())
        assertFalse(queue.offer(500))
        assertTrue(queue.offer(501))
        assertEquals(501, queue.take())
    }

    @Test fun cancellationDropsPendingChromeTimersAndCanRestart() {
        val queue = BoardChromeFrameQueue<Int>()
        queue.offer(1); queue.clear()
        assertNull(queue.take())
        assertTrue(queue.offer(1))
        assertEquals(1, queue.take())
    }

    @Test fun penCoordinatesDoNotDirtySelectedChromeAndRestUsesApproachBoundary() {
        val b = BoardChromeLayout.Rect(100f, 100f, 324f, 226f)
        val selected = BoardChromeLayout.Input(b, 1f, BoardKind.CANVAS, selected = true)
        assertEquals(BoardChromeLayout.visualInput(selected.copy(pen = BoardChromeLayout.Point(120f, 150f))),
            BoardChromeLayout.visualInput(selected.copy(pen = BoardChromeLayout.Point(200f, 190f))))
        val rest = selected.copy(selected = false)
        val near = BoardChromeLayout.visualInput(rest.copy(pen = BoardChromeLayout.Point(52f, 100f)))
        val far = BoardChromeLayout.visualInput(rest.copy(pen = BoardChromeLayout.Point(51.9f, 100f)))
        assertNotEquals(near, far)
        assertEquals(BoardChromeLayout.layout(rest.copy(pen = BoardChromeLayout.Point(52f, 100f))),
            BoardChromeLayout.layout(near))
        assertEquals(BoardChromeLayout.visualInput(selected.copy(penDownElapsedMs = 500)),
            BoardChromeLayout.visualInput(selected.copy(penDownElapsedMs = 120)))
    }

    @Test fun passiveBoardAndTouchTransparentGapsNeverCapturePainting() {
        val b = BoardChromeLayout.Rect(100f, 100f, 324f, 226f)
        val layout = BoardChromeLayout.layout(BoardChromeLayout.Input(b, 1f, BoardKind.CANVAS))
        val capture = BoardChromePointerCapture()
        assertNull(capture.down(layout, BoardChromeLayout.Point(200f, 150f)))
        assertNull(capture.down(layout, BoardChromeLayout.Point(73f, 170f)))
        assertEquals("kind", capture.down(layout, BoardChromeLayout.Point(73f, 119f)))
        capture.cancel()
        assertNull(capture.release())
        assertNull(capture.active)
    }

    @Test fun overlappingTargetsChooseNearestAndCaptureSurvivesMotionUntilRelease() {
        val layout = BoardChromeLayout.layout(BoardChromeLayout.Input(
            BoardChromeLayout.Rect(100f, 100f, 324f, 226f), 1f, BoardKind.CANVAS, selected = true))
        val feature = layout.controls.first { it.id == "feature" }
        val capture = BoardChromePointerCapture()
        assertEquals("feature", capture.down(layout, BoardChromeLayout.Point(feature.visual.cx, feature.visual.cy)))
        assertTrue(capture.retain(layout))
        assertEquals("feature", capture.release())
        assertNull(capture.release())
        capture.down(layout, BoardChromeLayout.Point(feature.visual.cx, feature.visual.cy))
        assertFalse(capture.retain(layout.copy(controls = emptyList())))
        assertNull(capture.release())
    }
}
