package cc.joycreator.joybrush.android.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.tools.FillPenPreviewState
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the real UI stroke dispatcher; unattached surfaces never execute deferred GL work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FillPenDispatchTest {
    private val surfaces = ArrayList<JbCanvasView>()
    @After fun pauseUnattachedSurfaces() { surfaces.forEach { it.onPause() } }

    private fun surface(): JbCanvasView = JbCanvasView(RuntimeEnvironment.getApplication()).also {
        surfaces.add(it)
        it.preset = BrushPreset(id="fill", name="Fill", engine="fill", size=Param(12f), smoothing=0f)
        it.colorArgb = Color.RED
        it.longPressEyedropper = false
    }

    private fun get(view: JbCanvasView, name: String): Any? =
        JbCanvasView::class.java.getDeclaredField(name).also { it.isAccessible=true }.get(view)
    private fun set(view: JbCanvasView, name: String, value: Any?) {
        JbCanvasView::class.java.getDeclaredField(name).also { it.isAccessible=true }.set(view,value)
    }
    private fun call(view: JbCanvasView, name: String, vararg args: Any) {
        JbCanvasView::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .also { it.isAccessible=true }.invoke(view,*args)
    }
    private fun begin(view: JbCanvasView, eraser: Boolean=false) { call(view,"startStroke",eraser) }
    private fun feed(view: JbCanvasView, points: List<PenSample>) {
        val smoother = get(view,"smoother") as StrokeSmoother
        val released = ArrayList<PenSample>()
        for (point in points) call(view,"feedOne",smoother,point,released)
        call(view,"paint",released)
        call(view,"paint",smoother.finish())
    }
    private fun triangle() = listOf(
        PenSample(10f,10f,0.0), PenSample(90f,10f,10.0), PenSample(90f,90f,20.0),
    )
    private fun document(view: JbCanvasView, kind: LayerKind=LayerKind.PAINT, locked: Boolean=false) {
        val id = view.activeLayerId
        val doc = JbDocument(id="test", name="Test", boards=emptyList(),
            layers=listOf(Layer(id,"Paint",kind,locked=locked,cels=listOf(Cel("shared")))), activeLayerId=id)
        set(view,"retainedContents",JbContents(doc,emptyMap(),emptyMap()))
    }

    @Test fun fillDispatchMakesAClosedTransientShapeWithoutStartingStampPaint() {
        val view=surface(); val previews=ArrayList<FillPenPreviewState?>()
        view.onFillPreview={previews.add(it)}
        begin(view); feed(view,triangle())
        assertTrue(view.strokeInProgress)
        assertNull(get(view,"placer")); assertNull(get(view,"strokeDabber"))
        assertEquals(false,get(view,"glBegan"))
        val state=previews.last()!!
        assertEquals(Color.RED,state.argb)
        assertTrue(state.points.size >= 3)
        // Render the actual screen overlay: pen-up need not repeat the first corner to close it.
        val overlay=FillPenPreview(RuntimeEnvironment.getApplication(),view.view)
        overlay.state=state; overlay.layout(0,0,110,110)
        val bitmap=Bitmap.createBitmap(110,110,Bitmap.Config.ARGB_8888)
        try {
            overlay.draw(Canvas(bitmap))
            assertTrue(Color.alpha(bitmap.getPixel(75,25)) > 0)
            assertEquals(0,Color.alpha(bitmap.getPixel(25,75)))
        } finally { bitmap.recycle() }
    }

    @Test fun strokeKeepsItsStartingLayerColourOpacityAndPreset() {
        val view=surface(); val first=view.activeLayerId
        set(view,"stackUi",LayerStack(listOf(LayerState(first,"First"),LayerState("second","Second")),first))
        view.preset=view.preset!!.copy(opacity=Param(.4f))
        var preview: FillPenPreviewState?=null; view.onFillPreview={preview=it}
        begin(view)
        view.selectLayer("second"); view.colorArgb=Color.GREEN
        view.preset=view.preset!!.copy(engine="stamp",opacity=Param(.9f))
        feed(view,triangle())
        assertEquals(first,get(view,"strokeLayerId"))
        assertEquals("second",view.activeLayerId)
        assertEquals(Color.RED,preview!!.argb)
        assertEquals(.4f,preview!!.opacity,0f)
        assertNull(get(view,"placer"))
    }

    @Test fun cancellationClearsPreviewAndRecordedShapeAndReportsStrokeEndOnce() {
        val view=surface(); var preview: FillPenPreviewState?=null; var ended=0
        view.onFillPreview={preview=it}; view.onStrokeEnded={ended++}
        begin(view); feed(view,triangle()); assertNotNull(preview)
        view.cancelInterruptedStroke()
        assertFalse(view.strokeInProgress); assertNull(preview)
        assertTrue((get(view,"fillSamples") as List<*>).isEmpty())
        assertTrue((get(view,"fillPoints") as List<*>).isEmpty())
        assertNull(get(view,"strokePreset")); assertEquals(1,ended)
        view.cancelInterruptedStroke(); assertEquals(1,ended)
    }

    @Test fun aSecondFingerHandsOverToNavigationAndCancelsTheFillPreview() {
        val view=surface(); var preview: FillPenPreviewState?=null
        view.onFillPreview={preview=it}
        begin(view); feed(view,triangle()); assertNotNull(preview)
        val properties=Array(2) { index -> MotionEvent.PointerProperties().apply { id=index; toolType=MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates=Array(2) { index -> MotionEvent.PointerCoords().apply { x=20f+index*20; y=20f; pressure=1f; size=1f } }
        val event=MotionEvent.obtain(0,30,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,properties,coordinates,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
        try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
        assertFalse(view.strokeInProgress); assertNull(preview)
        assertTrue((get(view,"fillSamples") as List<*>).isEmpty())
    }

    @Test fun hugeFinitePathIsRefusedBeforeTheSmootherExpandsItsSecondSample() {
        val view=surface(); val reasons=ArrayList<String>(); var ended=0
        view.onRefused={reasons.add(it)}; view.onStrokeEnded={ended++}
        begin(view)
        val smoother=get(view,"smoother") as StrokeSmoother
        val released=ArrayList<PenSample>()
        call(view,"feedOne",smoother,PenSample(0f,0f,0.0),released)
        val before=released.toList()
        call(view,"feedOne",smoother,PenSample(1e20f,0f,10.0),released)
        assertFalse(view.strokeInProgress)
        assertEquals(before,released)
        assertTrue(reasons.single().contains("too long"))
        assertEquals(1,ended)
        assertTrue((get(view,"fillSamples") as List<*>).isEmpty())
    }

    @Test fun hiddenLayerRefusesFillBeforeAnyPreviewOrStrokeStarts() {
        val view=surface(); val stack=view.layers
        set(view,"stackUi",stack.withVisible(stack.activeId,false))
        var reason=""; view.onRefused={reason=it}
        begin(view)
        assertFalse(view.strokeInProgress); assertTrue(reason.contains("hidden"))
        assertNull(get(view,"strokePreset")); assertTrue((get(view,"fillPoints") as List<*>).isEmpty())
    }

    @Test fun lockedLayerRefusesFillBeforeStarting() {
        val view=surface(); document(view,locked=true)
        var reason=""; view.onRefused={reason=it}
        begin(view)
        assertFalse(view.strokeInProgress); assertTrue(reason.contains("locked"))
        assertNull(get(view,"strokePreset"))
    }

    @Test fun aMaskRefusesFillWithAnActionableExplanation() {
        val view=surface(); val stack=view.layers
        set(view,"stackUi",stack.withMask(stack.activeId,true)); set(view,"editingMask",true)
        var reason=""; view.onRefused={reason=it}
        begin(view)
        assertFalse(view.strokeInProgress); assertTrue(reason.contains("paint layer"))
        assertTrue(reason.contains("masks")); assertNull(get(view,"strokePreset"))
    }

    @Test fun armedTilingRefusesFillBeforeTheStrokeBegins() {
        val view=surface(); set(view,"tileBoardId","tile")
        var reason=""; view.onRefused={reason=it}
        begin(view)
        assertFalse(view.strokeInProgress); assertTrue(reason.contains("tiling"))
        assertNull(get(view,"strokePreset"))
    }

    @Test fun fillOnMediaIsRefusedInsteadOfOverwritingItsBakedLook() {
        val view=surface(); document(view,LayerKind.MEDIA)
        var reason=""; view.onRefused={reason=it}
        begin(view)
        assertFalse(view.strokeInProgress); assertTrue(reason.isNotBlank())
        assertEquals(false,get(view,"mediaErase")); assertNull(get(view,"strokePreset"))
    }

    @Test fun penEraserOnMediaKeepsTheExistingStateErasePathEvenWithFillSelected() {
        val view=surface(); document(view,LayerKind.MEDIA)
        val previews=ArrayList<FillPenPreviewState?>(); val reasons=ArrayList<String>()
        view.onFillPreview={previews.add(it)}; view.onRefused={reasons.add(it)}
        begin(view,eraser=true)
        assertTrue(view.strokeInProgress); assertEquals(true,get(view,"mediaErase"))
        assertNotNull(get(view,"placer"))
        feed(view,triangle())
        assertTrue(previews.isEmpty()); assertTrue(reasons.isEmpty())
        assertTrue((get(view,"fillSamples") as List<*>).isEmpty())
        assertEquals(true,get(view,"mediaBegun"))
    }
}
