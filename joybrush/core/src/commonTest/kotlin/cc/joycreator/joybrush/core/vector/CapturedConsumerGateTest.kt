package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.RegionFrames
import cc.joycreator.joybrush.core.render.RegionException
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.svg.LineSvg
import cc.joycreator.joybrush.core.vector.svg.SvgWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CapturedConsumerGateTest {
    // Empty raw samples deliberately give this record no legacy/replayed footprint.
    private val captured = StrokeRecord(
        id = "capture", brushId = "brush", seed = 1L, smoothing = 0f, screenPerDoc = 1f,
        samples = emptyList(), seq = 1L,
        capturedPhysicalSource = CapturedPhysicalSource(CaptureDomain()),
    )
    private val brush = BrushPreset(id = "brush", name = "Brush", size = Param(4f))

    @Test
    fun replayAndCpuCompositionRefuseTheWholeCapturedRecordBeforeCallbacks() {
        val refusal = assertNotNull(InkReplay.refusal(captured, null))
        assertTrue(refusal.contains("physical capture"))
        assertFailsWith<InkReplayException> { InkReplay.replay(captured, null) }

        var lookupCalls = 0
        assertFailsWith<IllegalArgumentException> {
            InkTiles.prepare(listOf(captured)) { lookupCalls++; brush }
        }
        assertEquals(0, lookupCalls, "the whole source is rejected before brush lookup")
        assertFailsWith<IllegalArgumentException> {
            CelComposer.draw(CelComposer.Items(listOf(CelComposer.Line(1L, captured)), emptyMap())) {
                lookupCalls++
                brush
            }
        }
        assertEquals(0, lookupCalls)
    }

    @Test
    fun svgRefusesCapturedLineBeforeEmittingItsOrdinaryOpaqueSlab() {
        var lookupCalls = 0
        val slab = SvgWriter.Slab(0L, "opaque-base", 0.0, 0.0, 1.0, 1.0, byteArrayOf(255.toByte(), 0, 0, 255.toByte()))
        assertFailsWith<IllegalArgumentException> {
            LineSvg.export(1.0, 1.0, listOf(captured), { lookupCalls++; brush }, slabs = listOf(slab))
        }
        assertEquals(0, lookupCalls, "whole-export admission precedes line and slab serialization")
    }

    @Test
    fun legacyInkRegionPreflightsEvenWhenCapturedSamplesHaveNoCurrentFootprint() {
        val cel = Cel("c", strokesFile = "layers/l/c/strokes.jbs")
        val layer = Layer("l", "Legacy ink", LayerKind.INK, cels = listOf(cel))
        val doc = JbDocument(
            id = "d", name = "D",
            boards = listOf(Board("canvas", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 8, 8))),
            layers = listOf(layer),
        )
        var inkRefusals = 0
        var lookups = 0
        var tileReads = 0
        assertFailsWith<RegionException> {
            RegionRenderer.render(
                doc, TileSource { _, _, _, _ -> tileReads++; null }, RectPx(0, 0, 2, 2), null, null,
                brushLookup = { lookups++; brush },
                strokeSource = { _, _ -> listOf(captured) },
                onInkRefusal = { inkRefusals++ },
            )
        }
        assertEquals(0, inkRefusals, "captured input is refused in preflight, not downgraded to a warning")
        assertEquals(0, lookups)
        assertEquals(0, tileReads)
    }

    // Editable lines v1 (NATIVE_LINES_V1): an ordinary line on a regional layer needs no captured source (the plane's
    // regions clip it when it is re-rendered); a captured source still needs document version 11.
    @Test
    fun archiveSourceRulesAreVersionSpecificAndOrdinaryRegionalLinesNeedNoCapturedSource() {
        val global = Layer("g", "Global", LayerKind.PAINT, cels = listOf(Cel("gc")))
        val regional = Layer(
            "r", "Regional", LayerKind.PAINT, cels = listOf(Cel("rc")),
            regions = listOf(RegionFrames("anim", emptyMap())),
        )
        val ordinary = captured.copy(capturedPhysicalSource = null)

        assertTrue(LineRules.sourceProblems(10, global, global.cels.single(), listOf(captured)).isNotEmpty())
        assertTrue(LineRules.sourceProblems(11, regional, regional.cels.single(), listOf(ordinary)).isEmpty())
        assertTrue(LineRules.sourceProblems(10, regional, regional.cels.single(), listOf(ordinary)).isEmpty())
        assertTrue(LineRules.sourceProblems(11, global, global.cels.single(), listOf(captured)).isEmpty())
        assertTrue(LineRules.sourceProblems(10, regional, regional.cels.single(), listOf(captured)).isNotEmpty())
        assertTrue(LineRules.sourceProblems(11, regional, regional.cels.single(), listOf(captured)).isEmpty())
    }

    @Test
    fun savedLooksOfOrdinaryRecordsAreConservativeAndNotGeometricallyProven() {
        // A look on a tile no line reaches equals its base, which is harmless; proving the exact set would replay every line.
        val ordinary = captured.copy(capturedPhysicalSource = null)
        assertTrue(LineRules.lookProblems("layer", Cel("cel", looks = listOf("99_99")), listOf(ordinary)) { brush }.isEmpty())
        assertTrue(LineRules.lookProblems("layer", Cel("cel", looks = listOf("99_99")), listOf(captured)) { brush }.isNotEmpty())
    }

    @Test
    fun savedLookValidatorRefusesCapturedRecordEvenWhenLookIndexIsEmpty() {
        val problems = LineRules.lookProblems("layer", Cel("cel"), listOf(captured)) { brush }
        assertTrue(problems.any { it.contains("clipping-aware saved-look validator") })
    }

    @Test fun tweenRefusesEitherCapturedEndpointBeforeEmptyPairFastPath() {
        assertFailsWith<IllegalArgumentException> {
            cc.joycreator.joybrush.core.anim.Tween.between(listOf(captured), emptyList(), 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            cc.joycreator.joybrush.core.anim.Tween.between(emptyList(), listOf(captured), 1f)
        }
    }
}
