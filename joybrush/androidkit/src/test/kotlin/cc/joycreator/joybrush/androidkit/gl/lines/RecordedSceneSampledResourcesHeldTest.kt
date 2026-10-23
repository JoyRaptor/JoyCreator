package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.io.PngWriter
import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.*
import cc.joycreator.joybrush.core.vector.*
import kotlin.test.*

/** Proposed JVM tests for the held adapters; not executed or copied into the repository. */
class RecordedSceneSampledResourcesHeldTest {
    private val at: Pair<String, String?> = "paint" to null
    private val address = LineAddress("paint", null, "line")
    private val ordinary = BrushPreset(id="ordinary", name="Pen", size=Param(8f))
    private val sampled = ordinary.copy(id="sampled", tip=TipSpec(
        sampled=SampledTipSpec(images=listOf("tips/a.png"))))
    private fun png(alpha: Int) = FrozenBrushPngs.snapshot(mapOf("tips/a.png" to
        PngWriter.encode(1, 1, byteArrayOf(-1, -1, -1, alpha.toByte()))))
    private fun record(id: String) = StrokeRecord("line",id,19,0f,1f,listOf(PenSample(0f,0f,0.0)),seq=1)
    private class Driver : RecordedScene.Renderer<Int> {
        var next=100
        val seen=mutableListOf<Pair<String,FrozenBrushPngs?>>()
        override fun line(address:LineAddress,record:StrokeRecord,preset:BrushPreset):RecordedScene.Unit<Int> =
            error("The scene must use the resource-bearing callback")
        override fun line(address:LineAddress,record:StrokeRecord,preset:BrushPreset,pngs:FrozenBrushPngs?):RecordedScene.Unit<Int> {
            seen += preset.id to pngs
            if(preset.tip.sampled!=null) assertNotNull(pngs)
            return RecordedScene.Unit(preset.blend,mapOf(0L to next++))
        }
        override fun look(plane:Pair<String,String?>,key:Long,sources:List<RecordedScene.Source<Int>>) = next++
        override fun release(texture:Int) {}
    }
    private class Fixture(val driver:Driver,val history:UndoLog<Int>,val scene:RecordedScene<Int>)
    private fun fixture():Fixture {
        val driver=Driver()
        val history=UndoLog<Int>(1_000_000,{4},{})
        val scene=RecordedScene.create(mapOf(at to RecordedCel<Int>("paint",null,listOf(record(ordinary.id)))),
            mapOf(ordinary.id to ordinary),history,driver)
        driver.seen.clear()
        return Fixture(driver,history,scene)
    }
    private fun rebrush(f:Fixture) = RecordedEdits.replace(f.scene.planes,
        listOf(AddressedStroke(address,record(sampled.id))),setOf(ordinary.id,sampled.id))

    @Test fun canceledCandidateNeverPublishesAssetsAndRendererSeesCandidateBundle() {
        val f=fixture(); val images=png(255); val before=f.scene.snapshotState()
        f.scene.stage(rebrush(f),frozen=mapOf(ordinary.id to ordinary,sampled.id to sampled),
            frozenAssets=mapOf(sampled.id to images)).use {
            assertSame(before,f.scene.snapshotState())
            assertTrue(f.scene.brushAssets.isEmpty())
            assertSame(images,f.driver.seen.single().second)
        }
        assertSame(before,f.scene.snapshotState())
        assertTrue(f.scene.brushAssets.isEmpty()); assertFalse(f.history.canUndo)
    }

    @Test fun successfulRebrushKeepsBundleAcrossUndoRedo() {
        val f=fixture(); val images=png(255)
        f.scene.stage(rebrush(f),frozen=mapOf(ordinary.id to ordinary,sampled.id to sampled),
            frozenAssets=mapOf(sampled.id to images)).use { assertTrue(it.commit()) }
        assertSame(images,f.scene.brushAssets[sampled.id])
        val step=assertNotNull(f.history.peekUndo())
        f.scene.stage(RecordedEdits.prepare(f.scene.planes,step,false),RecordedScene.Direction.UNDO).use { assertTrue(it.commit()) }
        assertSame(images,f.scene.brushAssets[sampled.id])
        assertEquals(ordinary.id,f.scene.planes.getValue(at).records.single().brushId)
        f.scene.stage(RecordedEdits.prepare(f.scene.planes,step,true),RecordedScene.Direction.REDO).use { assertTrue(it.commit()) }
        assertSame(images,f.scene.brushAssets[sampled.id])
        assertEquals(sampled.id,f.scene.planes.getValue(at).records.single().brushId)
    }

    @Test fun missingOrReplacedResourcesRefuseBeforeRendererAndPreserveRoot() {
        val f=fixture(); val images=png(255)
        val missing = assertFailsWith<BrushException> {
            f.scene.stage(rebrush(f),frozen=mapOf(ordinary.id to ordinary,sampled.id to sampled))
        }
        assertEquals("Frozen brush images are missing or unreferenced", missing.message)
        assertTrue(f.driver.seen.isEmpty())
        f.scene.stage(rebrush(f),frozen=mapOf(ordinary.id to ordinary,sampled.id to sampled),
            frozenAssets=mapOf(sampled.id to images)).use { assertTrue(it.commit()) }
        f.driver.seen.clear(); val before=f.scene.snapshotState()
        val moved=RecordedEdits.replace(f.scene.planes,listOf(AddressedStroke(address,
            record(sampled.id).copy(colorArgb=0xff00ff00.toInt()))),f.scene.brushes.keys)
        assertFailsWith<IllegalArgumentException> {
            f.scene.stage(moved,frozenAssets=mapOf(sampled.id to png(128)))
        }
        assertSame(before,f.scene.snapshotState()); assertTrue(f.driver.seen.isEmpty())
    }
}
