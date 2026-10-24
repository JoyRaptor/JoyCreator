package cc.joycreator.joybrush.android.board

import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.JbCanvasView.DiagnosticStrokeCaptureResult
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeStabilizer
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.vector.InkReplay
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Real start/feed/finish dispatcher, with GL work left queued on unattached surfaces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrokeCaptureDispatchTest {
    private val surfaces = ArrayList<JbCanvasView>()
    @After fun pauseUnattachedSurfaces() { surfaces.forEach { it.onPause() } }

    private fun surface(): JbCanvasView = JbCanvasView(RuntimeEnvironment.getApplication()).also {
        surfaces.add(it)
        it.preset = BrushPreset(id="stamp", name="Stamp", size=Param(12f), smoothing=.6f,
            sizeJitter=.25f, scatter=ScatterSpec(amount=Param(.4f)))
        it.colorArgb = Color.RED
        it.longPressEyedropper = false
    }
    private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .also { it.isAccessible=true }.get(target)
    private fun set(view: JbCanvasView, name: String, value: Any?) {
        JbCanvasView::class.java.getDeclaredField(name).also { it.isAccessible=true }.set(view,value)
    }
    private fun call(view: JbCanvasView, name: String, vararg args: Any?) {
        JbCanvasView::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .also { it.isAccessible=true }.invoke(view,*args)
    }
    private fun begin(view: JbCanvasView, eraser: Boolean=false, seed: Long?=null) = call(view,"startStroke",eraser,seed)
    private fun finish(view: JbCanvasView) = call(view,"finishStroke")
    private fun feedRaw(view: JbCanvasView, points: List<PenSample>) {
        val sm = get(view,"smoother") as StrokeStabilizer
        val released = ArrayList<PenSample>()
        points.forEach { call(view,"feedOne",sm,it,released) }
        call(view,"paint",released)
    }
    private fun observe(view: JbCanvasView): ArrayList<DiagnosticStrokeCaptureResult> =
        ArrayList<DiagnosticStrokeCaptureResult>().also { out -> view.onDiagnosticStrokeCapture={out.add(it)} }
    private fun captured(out: List<DiagnosticStrokeCaptureResult>) = out.single() as DiagnosticStrokeCaptureResult.Captured
    private fun point(time: Double=0.0) = PenSample(10f,2f,time,pressure=.25f,tilt=.4f,azimuth=.2f,barrel=.7f)
    private fun document(view: JbCanvasView, floatTiles: List<String> = emptyList()) {
        val layer=Layer(view.activeLayerId,"Paint",LayerKind.PAINT,cels=listOf(Cel("physical-shared",floatTiles=floatTiles)))
        set(view,"retainedContents",JbContents(JbDocument(id="test",name="Test",boards=emptyList(),
            layers=listOf(layer),activeLayerId=layer.id),emptyMap(),emptyMap()))
    }

    @Test fun actualMotionEventHistoryIsCapturedAfterGuidesBeforeResponseAndSmoothing() {
        val view=surface(); val out=observe(view)
        view.view.zoom=2f; view.view.panX=10f; view.view.panY=20f
        view.snapTo=listOf(Guide.Ruler(Pt(0.0,0.0),Pt(100.0,0.0)))
        view.preset=view.preset!!.copy(response=ResponseSpec(pressure=listOf(.1f,.8f,.2f,1f)))
        begin(view); set(view,"pointerId",0)
        val properties=arrayOf(MotionEvent.PointerProperties().apply { id=0; toolType=MotionEvent.TOOL_TYPE_STYLUS })
        fun coords(xValue: Float, yValue: Float, pressureValue: Float) = MotionEvent.PointerCoords().apply {
            x=xValue; y=yValue; pressure=pressureValue; size=1f
            setAxisValue(MotionEvent.AXIS_TILT,.4f); setAxisValue(MotionEvent.AXIS_ORIENTATION,.2f)
        }
        val event=MotionEvent.obtain(0,10,MotionEvent.ACTION_MOVE,1,properties,arrayOf(coords(30f,24f,.25f)),
            0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
        try {
            event.addBatch(20,arrayOf(coords(50f,26f,.75f)),0)
            call(view,"feed",event)
        } finally { event.recycle() }
        assertTrue(out.isEmpty()); finish(view)
        val result=captured(out); val samples=result.record.samples
        assertEquals(2,samples.size)
        assertEquals(listOf(10f,20f),samples.map { it.x })
        assertEquals(listOf(0f,0f),samples.map { it.y })
        assertEquals(listOf(10.0,20.0),samples.map { it.timeMs })
        assertEquals(listOf(.25f,.75f),samples.map { it.pressure })
        assertEquals(.4f,samples.first().tilt,0f)
        assertNotEquals(samples.first().pressure,result.brush.preset.response.apply(samples.first()).pressure)
        assertEquals(.6f,result.record.smoothing,0f); assertEquals(2f,result.record.screenPerDoc,0f)
        assertNull(result.physicalCelId); assertEquals(0L,result.record.seq)
        assertNull(get(view,"diagnosticContact")); assertFalse(view.strokeInProgress)
    }

    @Test fun frozenDefinitionIsDetachedAtContactAndIsTheDefinitionUsedByTheLiveDabber() {
        val view=surface(); val out=observe(view)
        val pressure=mutableListOf(.1f,.8f,.2f,1f); val extensions=mutableMapOf("source" to "original")
        val p=view.preset!!.copy(response=ResponseSpec(pressure=pressure),extensions=extensions)
        view.preset=p; begin(view)
        val live=get(view,"strokePreset") as BrushPreset
        assertNotSame(p,live); assertSame(live,(get(view,"strokeDabber") as BrushDabber).preset)
        pressure[1]=0f; extensions["source"]="changed"
        view.preset=p.copy(size=Param(80f))
        feedRaw(view,listOf(point(),point(10.0).copy(x=30f))); finish(view)
        val result=captured(out)
        assertEquals(listOf(.1f,.8f,.2f,1f),result.brush.preset.response.pressure)
        assertEquals("original",result.brush.preset.extensions["source"])
        assertEquals(12f,result.brush.preset.size.base,0f)
        assertEquals(result.brush.id,result.record.brushId); assertEquals(live,result.brush.preset)
        val consumerCopy=result.brush.preset
        (consumerCopy.response.pressure as MutableList<Float>)[1]=0f
        assertEquals(.8f,result.brush.preset.response.pressure[1],0f)
    }

    @Test fun stampCaptureUsesTheActualDabberAndScatterSeedInsteadOfAStaleStrokeSeed() {
        val view=surface(); val out=observe(view)
        set(view,"strokeSeed",-774411L); ShadowSystemClock.advanceBy(Duration.ofMillis(41))
        val expected=SystemClock.uptimeMillis(); begin(view)
        assertEquals(expected,get(view,"strokeSeed"))
        val dabber=get(view,"strokeDabber") as BrushDabber
        assertEquals(SplitMix(expected).nextFloat(),get(dabber,"strokeRandom") as Float,0f)
        assertEquals(expected xor Scatter.SALT,get(get(view,"scatterRng")!!,"state"))
        feedRaw(view,listOf(point(),point(10.0).copy(x=30f))); finish(view)
        assertEquals(expected,captured(out).record.seed)
    }

    @Test fun operatorLayerAndKnownPhysicalCelStayAtTheirContactValues() {
        for ((presetBlend,eraser,expected) in listOf(
            Triple("normal",false,StrokeBlend.NORMAL), Triple("normal",true,StrokeBlend.ERASE),
            Triple("erase",false,StrokeBlend.ERASE), Triple("multiply",false,StrokeBlend.MULTIPLY),
            Triple("screen",false,StrokeBlend.SCREEN))) {
            val view=surface(); document(view); val first=view.activeLayerId
            set(view,"stackUi",LayerStack(listOf(LayerState(first,"First"),LayerState("second","Second")),first))
            view.preset=view.preset!!.copy(blend=presetBlend)
            val out=observe(view); begin(view,eraser); view.selectLayer("second")
            feedRaw(view,listOf(point())); finish(view)
            val result=captured(out)
            assertEquals(first,result.layerId); assertEquals("physical-shared",result.physicalCelId)
            assertEquals(expected,result.blend); assertEquals(Color.RED,result.record.colorArgb)
            assertEquals(when (expected) {
                StrokeBlend.ERASE -> "erase"
                StrokeBlend.MULTIPLY -> "multiply"
                StrokeBlend.SCREEN -> "screen"
                else -> "normal"
            },result.brush.preset.blend)
            assertEquals(0L,result.record.seq)
        }
    }

    @Test fun predictedAndBrokenSamplesAreExcludedAndRealSensorChannelsStayRaw() {
        val view=surface(); val out=observe(view); begin(view)
        val raw=point()
        feedRaw(view,listOf(raw,point(1.0).copy(predicted=true),point(2.0).copy(x=Float.NaN),point(3.0)))
        finish(view)
        assertEquals(listOf(raw,point(3.0)),captured(out).record.samples)
    }

    @Test fun cancellationDiscardsContactAndNextStrokeHasOnlyItsOwnSamples() {
        val view=surface(); val out=observe(view); var ended=0
        view.onStrokeEnded={ended++; assertNull(get(view,"diagnosticContact"))}
        begin(view); feedRaw(view,listOf(point())); view.cancelInterruptedStroke(); view.cancelInterruptedStroke()
        assertTrue(out.isEmpty()); assertEquals(1,ended); assertNull(get(view,"diagnosticContact"))
        begin(view); feedRaw(view,listOf(point(30.0))); finish(view)
        assertEquals(listOf(point(30.0)),captured(out).record.samples); assertEquals(2,ended)
    }

    @Test fun nullObserverKeepsTheOriginalPresetAndAllocatesNoDiagnosticSnapshotOrBuffer() {
        val view=surface(); val original=view.preset
        assertNull(view.onDiagnosticStrokeCapture); begin(view)
        assertSame(original,get(view,"strokePreset")); assertNull(get(view,"diagnosticContact"))
        assertEquals(0L,get(view,"diagnosticSerial"))
        // Enabling partway through a contact cannot produce a partial recording.
        val out=observe(view); feedRaw(view,listOf(point())); finish(view)
        assertTrue(out.isEmpty()); assertEquals(0L,get(view,"diagnosticSerial"))
        assertFalse(view.strokeInProgress)
    }

    @Test fun unsupportedDiagnosticToolsRefuseCaptureWhileTheirLiveDispatchStillWorks() {
        for (engine in listOf("fill","smudge","tuft","media")) {
            val view=surface(); view.preset=view.preset!!.copy(engine=engine)
            val out=observe(view); begin(view)
            assertTrue((out.single() as DiagnosticStrokeCaptureResult.Refused).reason.contains(engine))
            assertNull(get(view,"diagnosticContact")); assertEquals(0L,get(view,"diagnosticSerial"))
            if (engine != "media") { assertTrue(view.strokeInProgress); view.cancelInterruptedStroke() }
        }
        val view=surface(); view.preset=view.preset!!.copy(line=LineStyleSpec(taperOut=1f))
        val out=observe(view); begin(view)
        assertTrue(view.strokeInProgress)
        assertTrue((out.single() as DiagnosticStrokeCaptureResult.Refused).reason.contains("line shaping"))
        feedRaw(view,listOf(point())); finish(view); assertEquals(1,out.size)
        val behind=surface(); behind.preset=behind.preset!!.copy(blend="behind")
        val refused=observe(behind); begin(behind); assertTrue(behind.strokeInProgress)
        feedRaw(behind,listOf(point())); finish(behind)
        assertTrue((refused.single() as DiagnosticStrokeCaptureResult.Refused).reason.contains("behind"))
    }

    @Test fun masksMediaPayloadAndTilingNeverEmitAPretendReplayRecord() {
        for (unsupported in listOf("masks","media","tiling")) {
            val view=surface()
            when (unsupported) {
                "masks" -> { set(view,"stackUi",view.layers.withMask(view.activeLayerId,true)); set(view,"editingMask",true) }
                "media" -> document(view,floatTiles=listOf("0_0"))
                "tiling" -> set(view,"tileBoardId","tile")
            }
            val out=observe(view); begin(view)
            assertTrue(view.strokeInProgress)
            assertTrue((out.single() as DiagnosticStrokeCaptureResult.Refused).reason.contains(unsupported))
            feedRaw(view,listOf(point())); finish(view); assertEquals(1,out.size)
        }
    }

    @Test fun watercolorCaptureUsesItsActualPreviewSeedAndRejectsItsEraserFallback() {
        val view=surface(); view.preset=view.preset!!.copy(engine=ENGINE_WATERCOLOUR,watercolour=WatercolourSpec())
        val out=observe(view); begin(view,seed=1234567L)
        assertEquals(1234567L,get(view,"strokeSeed")); feedRaw(view,listOf(point(),point(10.0).copy(x=30f)))
        finish(view); assertEquals(1234567L,captured(out).record.seed)
        val other=surface(); other.preset=view.preset; val refused=observe(other); begin(other,eraser=true)
        assertTrue(other.strokeInProgress)
        assertTrue((refused.single() as DiagnosticStrokeCaptureResult.Refused).reason.contains("erasers"))
        other.cancelInterruptedStroke()
    }

    @Test fun budgetRefusesTheWholeDiagnosticContactAndLeavesDrawingActive() {
        val view=surface(); val out=observe(view); begin(view)
        val sm=get(view,"smoother") as StrokeStabilizer; val released=ArrayList<PenSample>()
        repeat(32769) { call(view,"feedOne",sm,point(it.toDouble()),released) }
        assertTrue(view.strokeInProgress); assertNull(get(view,"diagnosticContact"))
        assertTrue((out.single() as DiagnosticStrokeCaptureResult.Refused).reason.contains("32768"))
        call(view,"paint",released); finish(view); assertEquals(1,out.size)
    }

    @Test fun firstDabCapturesActualLiveColorAndDynamicOpacityThenKeepsThem() {
        val view=surface(); view.preset=view.preset!!.copy(smoothing=0f)
        val out=observe(view); begin(view); view.colorArgb=Color.BLUE
        feedRaw(view,listOf(point(),point(10.0).copy(x=30f)))
        assertTrue("The blue first dab must begin before the later color change",get(view,"glBegan") as Boolean)
        view.colorArgb=Color.GREEN; finish(view)
        val result=captured(out)
        assertEquals(Color.BLUE,result.start.colorArgb); assertEquals(Color.BLUE,result.record.colorArgb)
        assertEquals(Color.GREEN,view.colorArgb)

        val dynamic=surface()
        dynamic.preset=dynamic.preset!!.copy(accumulate="buildup",smoothing=0f,
            opacity=Param(.8f,inputs=listOf(InputCurve(BrushInput.pressure,listOf(listOf(0f,0f),listOf(1f,1f))))),
            tip=TipSpec(hardness=Param(.9f,inputs=listOf(InputCurve(BrushInput.pressure,listOf(listOf(0f,0f),listOf(1f,1f)))))))
        val dynamicOut=observe(dynamic); begin(dynamic); feedRaw(dynamic,listOf(point(),point(10.0).copy(x=30f)))
        val dabber=get(dynamic,"strokeDabber") as BrushDabber
        val liveOpacity=dabber.strokeOpacity; val liveHardness=dabber.strokeHardness
        finish(dynamic)
        val captured=captured(dynamicOut); val replay=InkReplay.replay(captured.record,captured.brush.preset)
        assertTrue(liveOpacity < captured.brush.preset.opacity.base)
        assertEquals(liveOpacity,captured.start.opacity,0f); assertEquals(liveHardness,captured.start.hardness,0f)
        assertEquals(replay.strokeOpacity,captured.start.opacity,0f)
        assertEquals(replay.strokeHardness,captured.start.hardness,0f)
    }
    @Test fun familyContactFreezesActualPolicyBeforeLaterPreferenceAndSuggestionChanges() {
        val view=surface(); val out=observe(view)
        val actual=cc.joycreator.joybrush.core.input.StabilizerSpec(
            mode=cc.joycreator.joybrush.core.input.StabilizerMode.catchUpOnLift,radiusPx=12f)
        view.preset=view.preset!!.copy(extensions=mapOf("unibrush.family" to "builtin"))
        view.stabilizerPreference=cc.joycreator.joybrush.core.input.StabilizerPreference(
            cc.joycreator.joybrush.core.input.StabilizerPolicy.alwaysMine,actual)
        begin(view,seed=321L)
        view.stabilizerPreference=cc.joycreator.joybrush.core.input.StabilizerPreference()
        view.preset=view.preset!!.copy(stabilizer=cc.joycreator.joybrush.core.input.StabilizerSpec(
            mode=cc.joycreator.joybrush.core.input.StabilizerMode.off))
        val raw=listOf(point(),point(10.0).copy(x=35f),point(20.0).copy(x=70f))
        feedRaw(view,raw);finish(view)
        val result=captured(out)
        assertEquals(actual,result.record.stabilizer)
        assertEquals(raw,result.record.samples)
        assertEquals(InkReplay.replay(result.record,result.brush.preset).dabs,InkReplay.replay(result.record,
            result.brush.preset.copy(stabilizer=view.preset!!.stabilizer)).dabs)
    }

    @Test fun globalActualPolicyLeavesHistoricalContactsOnTheLegacyFilterContract() {
        val view=surface();val out=observe(view)
        view.stabilizerPreference=cc.joycreator.joybrush.core.input.StabilizerPreference(
            cc.joycreator.joybrush.core.input.StabilizerPolicy.alwaysMine,
            cc.joycreator.joybrush.core.input.StabilizerSpec(mode=cc.joycreator.joybrush.core.input.StabilizerMode.off))
        begin(view);feedRaw(view,listOf(point(),point(10.0).copy(x=30f)));finish(view)
        assertNull(captured(out).record.stabilizer)
    }

    @Test fun strokeEndsDrawPlainlyAndAreShapedFromTheirOwnRecordingAtTheLift() {
        // Unibrush U6: Taper and dashes are no longer refused; the stroke draws and is redrawn shaped at the lift.
        for(family in listOf("builtin","user")) for(style in listOf(
            LineStyleSpec(taperOut=1f),LineStyleSpec(dashOn=1f,dashOff=.5f))) {
            val view=surface();view.preset=view.preset!!.copy(line=style,extensions=mapOf("unibrush.family" to family))
            val refused=ArrayList<String>();view.onRefused={refused.add(it)}
            begin(view)
            assertTrue(view.strokeInProgress);assertTrue(refused.isEmpty())
            assertTrue(get(view,"shapeAtLift") as Boolean)
            assertTrue(get(view,"shapeCapture") != null)   // no line is being made, so the stroke records itself for the lift
            view.cancelInterruptedStroke()
        }
    }

    @Test fun aScreenWidthWireIsStillRefusedBeforeAnyNativeWork() {
        val view=surface();view.preset=view.preset!!.copy(line=LineStyleSpec(taperOut=1f,screenWidthPx=1.5f),
            extensions=mapOf("unibrush.family" to "builtin"))
        val refused=ArrayList<String>();view.onRefused={refused.add(it)}
        val out=observe(view);begin(view)
        assertFalse(view.strokeInProgress);assertTrue(refused.single().contains("wire"))
        assertTrue(out.isEmpty());assertFalse(get(view,"glBegan") as Boolean);assertNull(get(view,"smoother"))
    }

    @Test fun unmarkedModernTipFieldsAndActiveColourWithEndsAreShapedAtTheLiftNotRefused() {
        val base=surface().preset!!
        val modern=listOf(
            base.copy(tip=TipSpec(angleSource=TipAngleSource.fixed)),
            base.copy(tip=TipSpec(profile=TipProfileSpec())),
            base.copy(tip=TipSpec(fade=TipFadeSpec())),
            base.copy(tip=TipSpec(quad=TipQuadSpec())),
            base.copy(color=ColorJitter(dynamics=ColorDynamicsSpec(hueRangeDegrees=1f))),
            base.copy(tip=TipSpec(dual=DualTipSpec())))
        for(feature in modern) for(style in listOf(LineStyleSpec(taperOut=1f),LineStyleSpec(dashOn=1f,dashOff=.5f))) {
            val view=surface();view.preset=view.preset!!.copy(tip=feature.tip,color=feature.color,line=style)
            val refused=ArrayList<String>();view.onRefused={refused.add(it)}
            begin(view)
            assertTrue(refused.none { it.contains("recorded drawing connection") })
            assertTrue(view.strokeInProgress);assertTrue(get(view,"shapeAtLift") as Boolean)
            view.cancelInterruptedStroke()
        }
    }

    @Test fun unmarkedOldScalarTaperLegacyColourAndDormantModernFieldsKeepNativeEndDispatch() {
        val base=surface().preset!!
        val cases=listOf(
            base.copy(tip=base.tip.copy(taper=.6f,aspect=.3f,followDirection=true),
                color=ColorJitter(hue=.2f,saturation=.1f,value=.15f,perStroke=true)),
            base.copy(color=ColorJitter(dynamics=ColorDynamicsSpec())),
            base.copy(stabilizer=cc.joycreator.joybrush.core.input.StabilizerSpec(
                mode=cc.joycreator.joybrush.core.input.StabilizerMode.off)),
            base.copy(version=BRUSH_VERSION))
        for(recipe in cases) for(style in listOf(LineStyleSpec(taperOut=1f),LineStyleSpec(dashOn=1f,dashOff=.5f))) {
            val view=surface();view.preset=recipe.copy(line=style)
            val refused=ArrayList<String>();view.onRefused={refused.add(it)}
            val out=observe(view);begin(view)
            assertTrue(view.strokeInProgress);assertTrue(refused.isEmpty())
            assertTrue((out.single() as DiagnosticStrokeCaptureResult.Refused).reason.contains("line shaping"))
            feedRaw(view,listOf(point()));finish(view);assertEquals(1,out.size)
        }
    }

    @Test fun inactiveEndsDoNotTriggerPublisherGateEvenForFamilyContacts() {
        for(style in listOf(LineStyleSpec(),LineStyleSpec(dashOn=1f,dashOff=0f))) {
            val view=surface();view.preset=view.preset!!.copy(line=style,extensions=mapOf("unibrush.family" to "builtin"))
            val refused=ArrayList<String>();view.onRefused={refused.add(it)}
            val out=observe(view);begin(view)
            assertTrue(view.strokeInProgress);assertTrue(refused.isEmpty())
            assertTrue((out.single() as DiagnosticStrokeCaptureResult.Refused).reason.contains("line shaping"))
            view.cancelInterruptedStroke()
        }
    }

    @Test fun explicitUnmarkedStabilizerSuggestionStillUsesHistoricalContactPolicy() {
        val view=surface();view.preset=view.preset!!.copy(stabilizer=cc.joycreator.joybrush.core.input.StabilizerSpec(
            mode=cc.joycreator.joybrush.core.input.StabilizerMode.off))
        val out=observe(view);begin(view);feedRaw(view,listOf(point(),point(10.0).copy(x=30f)));finish(view)
        assertNull(captured(out).record.stabilizer)
    }

    @Test fun activeColourDynamicsKeepBothContactColoursWhenUiColoursChangeBeforeTheFirstDab() {
        val view=surface();view.preset=view.preset!!.copy(smoothing=0f,
            color=ColorJitter(dynamics=ColorDynamicsSpec(secondaryMix=.5f)))
        view.secondaryColorArgb=Color.GREEN
        val out=observe(view);begin(view,seed=987L)
        view.colorArgb=Color.BLUE;view.secondaryColorArgb=Color.WHITE
        feedRaw(view,listOf(point(),point(10.0).copy(x=30f)));finish(view)
        val result=captured(out)
        assertEquals(Color.RED,result.start.colorArgb)
        assertEquals(Color.RED,result.record.colorArgb)
        assertEquals(Color.GREEN,result.record.secondaryColorArgb)
    }

}
