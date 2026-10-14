package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.RecordedSnapshot
import cc.joycreator.joybrush.androidkit.io.TILE_BYTES
import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

/** Coordinator/ownership contracts with fake units: no GPU, grain or watercolor appearance proof. */
class RecordedSceneSavedTest {
    private val shared: Pair<String, String?> = "paint" to null
    private val frame: Pair<String, String?> = "paint" to "frame"
    private val other: Pair<String, String?> = "other" to null
    private val pen = BrushPreset(id="pen",name="Pen",size=Param(8f),opacity=Param(.5f))
    private val brushes = listOf(pen,pen.copy(id="erase",blend="erase"),pen.copy(id="behind",blend="behind"),
        pen.copy(id="grain",tipTexture=GrainSpec(enabled=true)),
        pen.copy(id="water",engine=ENGINE_WATERCOLOUR,watercolour=WatercolourSpec())).associateBy { it.id }
    private fun record(id: String="same", seq: Long=1, key: Long=0, brush: String="pen", locked: Boolean=false) =
        StrokeRecord(id,brush,19,0f,1f,listOf(PenSample(key.toFloat(),0f,0.0)),seq=seq,locked=locked)
    private fun cel(plane: Pair<String, String?> = shared, records: List<StrokeRecord> = listOf(record()),
                    slabs: Map<Long,List<RecordedCel.PixelSlab<Int>>> = emptyMap()) =
        RecordedCel(plane.first,plane.second,records,slabs)
    private fun slab(seq: Long, texture: Int, op: String="normal") = RecordedCel.PixelSlab(seq,UndoLog.SlabValue(texture,op))
    private fun address(plane: Pair<String, String?> = shared, id: String="same") = LineAddress(plane.first,plane.second,id)

    private class Driver : RecordedScene.Renderer<Int> {
        data class Call(val address: LineAddress, val record: StrokeRecord)
        data class Composition(val plane: Pair<String,String?>, val key: Long, val sources: List<RecordedScene.Source<Int>>)
        var next=100
        val minted=mutableListOf<Int>()
        val released=mutableListOf<Int>()
        val lines=mutableListOf<Call>()
        val looks=mutableListOf<Composition>()
        val copies=mutableListOf<LineAddress>()
        val copyInputs=mutableListOf<RecordedScene.Unit<Int>>()
        val copied=mutableListOf<RecordedScene.Unit<Int>>()
        var context=true
        var lineFailure: ((LineAddress,StrokeRecord,BrushPreset) -> String?)? = null
        var lineResult: ((RecordedScene.Unit<Int>) -> RecordedScene.Unit<Int>)? = null
        var copyResult: ((RecordedScene.Unit<Int>) -> RecordedScene.Unit<Int>)? = null
        var failLookAt: Int? = null
        var failCopyAt: Int? = null
        var lookAlias: Int? = null
        var afterLine: (() -> Unit)? = null
        var afterCopy: (() -> Unit)? = null
        var afterLook: (() -> Unit)? = null
        var contextChecks=0
        var failContextCheckAt: Int? = null
        var afterContext: (() -> Unit)? = null
        override fun contextCurrent(): Boolean {
            contextChecks++
            afterContext?.invoke()
            return context && contextChecks != failContextCheckAt
        }
        private fun mint(): Int {
            val texture=next++
            minted += texture
            return texture
        }
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> {
            lines += Call(address,record)
            lineFailure?.invoke(address,record,preset)?.let { error(it) }
            val unit=RecordedScene.Unit(preset.blend,if(record.samples.isEmpty()) emptyMap()
                else mapOf(record.samples.first().x.toLong() to mint()))
            afterLine?.invoke()
            return lineResult?.invoke(unit) ?: unit
        }
        override fun copyForBake(address: LineAddress, unit: RecordedScene.Unit<Int>): RecordedScene.Unit<Int> {
            copies += address; copyInputs += unit
            if(copies.size == failCopyAt) error("copy failed")
            val copy=RecordedScene.Unit(unit.op,unit.tiles.mapValues { mint() })
            afterCopy?.invoke()
            val result=copyResult?.invoke(copy) ?: copy
            copied += result
            return result
        }
        override fun look(plane: Pair<String,String?>, key: Long, sources: List<RecordedScene.Source<Int>>): Int? {
            looks += Composition(plane,key,sources.toList())
            assertTrue(sources.zipWithNext().all { (a,b) -> a.seq < b.seq })
            if(looks.size == failLookAt) error("look failed")
            val output=lookAlias ?: mint()
            afterLook?.invoke()
            return output
        }
        override fun release(texture: Int) { released += texture }
        fun clearCalls() { lines.clear(); looks.clear(); copies.clear(); copyInputs.clear(); copied.clear() }
    }
    private class Fixture(val renderer: Driver, val history: UndoLog<Int>, val scene: RecordedScene<Int>,
                          val uploaded: Map<Pair<String,String?>,Map<Long,Int>>) {
        val uploadedTextures get() = uploaded.values.flatMap { it.values }.toSet()
    }
    private fun fixture(planes: Map<Pair<String,String?>,RecordedCel<Int>> = mapOf(shared to cel()),
                        keys: Map<Pair<String,String?>,Set<Long>> = planes.mapValues { (_,cel) ->
                            cel.records.flatMap { it.samples.take(1).map { s -> s.x.toLong() } }.toSet() },
                        driver: Driver = Driver(), sizeOf: (Int) -> Long = { 4 }): Fixture {
        var handle=10
        val uploads=keys.mapValues { (_,keys) -> keys.associateWith { handle++ } }
        val history=UndoLog<Int>(1_000_000,sizeOf,driver.released::add)
        return Fixture(driver,history,RecordedScene.createSaved(planes,brushes,history,driver,keys,uploads),uploads)
    }
    private fun replacement(f: Fixture, plane: Pair<String,String?> = shared, id: String="same", key: Long=2) =
        RecordedEdits.replace(f.scene.planes,listOf(AddressedStroke(address(plane,id),
            f.scene.planes.getValue(plane).records.first { it.id == id }.copy(samples=listOf(PenSample(key.toFloat(),0f,0.0))))),
            f.scene.brushes.keys)
    private fun assertOnce(driver: Driver, textures: Collection<Int>) {
        textures.toSet().forEach { assertEquals(1,driver.released.count { released -> released == it },"texture $it") }
    }
    private fun assertColdUnchanged(f: Fixture, before: Map<Pair<String,String?>,RecordedCel<Int>>) {
        assertSame(before,f.scene.planes)
        before.keys.forEach { plane ->
            assertFalse(f.scene.isMaterialized(plane))
            assertEquals(f.uploaded.getValue(plane).keys,f.scene.savedLookKeys(plane))
            f.uploaded.getValue(plane).forEach { (key,texture) -> assertEquals(texture,f.scene.look(plane,key)) }
        }
        assertTrue(f.uploadedTextures.none { it in f.renderer.released })
    }

    @Test fun savedFactoryMakesZeroReplayOrCompositionCallsAndRetainsExactPhysicalAddressesAndSources() {
        val f=fixture(mapOf(shared to cel(slabs=mapOf(8L to listOf(slab(0,1),slab(4,2,"behind")))),
            frame to cel(frame,listOf(record(seq=3,key=4))),other to cel(other,listOf(record(seq=7,key=6)))))
        assertTrue(f.renderer.lines.isEmpty()); assertTrue(f.renderer.looks.isEmpty())
        assertEquals(setOf(shared,frame,other),f.scene.planes.keys)
        assertEquals(listOf(0L,4L),f.scene.planes.getValue(shared).slabs.getValue(8).map { it.seq })
        assertEquals(listOf("normal","behind"),f.scene.planes.getValue(shared).slabs.getValue(8).map { it.value.op })
        assertEquals(setOf(4L),f.scene.savedLookKeys(frame)); assertFalse(f.history.canUndo)
        assertEquals(f.uploadedTextures,f.scene.heldCacheTextures())
        f.scene.close(); assertOnce(f.renderer,f.uploadedTextures)
        assertTrue(listOf(1,2).none { it in f.renderer.released })
    }

    @Test fun savedFactoryCopiesCallerCollectionsAndFrozenDefinitionWithoutTakingSourceOwnership() {
        val curves=mutableListOf(.1f,.8f,.2f,1f)
        val brush=pen.copy(response=ResponseSpec(pressure=curves))
        val driver=Driver(); val history=UndoLog<Int>(1_000_000,{ 4 },driver.released::add)
        val keys=mutableSetOf(0L); val uploads=mutableMapOf(0L to 10)
        val planes=mutableMapOf(shared to cel())
        val scene=RecordedScene.createSaved(planes,mapOf("pen" to brush),history,driver,
            mapOf(shared to keys),mapOf(shared to uploads))
        keys.clear(); uploads.clear(); planes.clear(); curves[1]=0f
        assertEquals(setOf(0L),scene.savedLookKeys(shared)); assertEquals(10,scene.look(shared,0))
        assertEquals(.8f,scene.brushes.getValue("pen").response.pressure[1]); assertTrue(driver.lines.isEmpty())
        scene.close(); assertEquals(listOf(10),driver.released)
    }

    @Test fun slabOnlyColdPlaneRetainsSourcesWithoutInventingAnOwnedDisplayOrSavedLook() {
        val f=fixture(mapOf(shared to cel(records=emptyList(),slabs=mapOf(
            0L to listOf(slab(0,1)),8L to listOf(slab(0,2),slab(3,3,"behind"))))))
        assertTrue(f.renderer.lines.isEmpty()); assertTrue(f.renderer.looks.isEmpty())
        assertTrue(f.scene.savedLookKeys(shared).isEmpty()); assertTrue(f.scene.lookKeys(shared).isEmpty())
        assertNull(f.scene.look(shared,0)); assertNull(f.scene.look(shared,8)); assertTrue(f.scene.heldCacheTextures().isEmpty())
        assertEquals(setOf(0L,8L),f.scene.planes.getValue(shared).slabs.keys)
        assertEquals(listOf(0L,3L),f.scene.planes.getValue(shared).slabs.getValue(8).map { it.seq })
        f.scene.close(); assertTrue(f.renderer.released.isEmpty())
    }

    @Test fun acceptedFactoryRollbackReleasesOnlyTransferredLookUploadsExactlyOnce() {
        val f=fixture(mapOf(shared to cel(slabs=mapOf(8L to listOf(slab(0,1)))),frame to cel(frame,listOf(record(key=2)))))
        // A loader's later physical-source/upload step fails after factory acceptance.
        // Rollback closes the accepted scene; the loader still owns its borrowed slab upload.
        f.scene.close(); f.scene.close()
        assertOnce(f.renderer,f.uploadedTextures); assertFalse(1 in f.renderer.released)
        f.renderer.release(1); assertOnce(f.renderer,listOf(1))
        assertTrue(f.renderer.lines.isEmpty()); assertTrue(f.renderer.looks.isEmpty()); assertFalse(f.history.canUndo)
    }

    @Test fun invalidFactoryLeavesAllUploadsCallerOwnedForRollbackAndCallsNoRenderer() {
        val driver=Driver(); val history=UndoLog<Int>(1_000_000,{ 4 },driver.released::add)
        val planes=mapOf(shared to cel(slabs=mapOf(8L to listOf(slab(0,1)))))
        fun rejects(keys: Map<Pair<String,String?>,Set<Long>>, uploads: Map<Pair<String,String?>,Map<Long,Int>>,
                    definitions: Map<String,BrushPreset> = brushes) {
            assertFailsWith<IllegalArgumentException> { RecordedScene.createSaved(planes,definitions,history,driver,keys,uploads) }
            assertTrue(driver.released.isEmpty()); assertTrue(driver.lines.isEmpty()); assertTrue(driver.looks.isEmpty())
            assertFalse(history.canUndo)
        }
        rejects(emptyMap(),mapOf(shared to mapOf(0L to 10)))
        rejects(mapOf(shared to setOf(0L)),mapOf(shared to mapOf(1L to 10)))
        rejects(mapOf(shared to setOf(0L)),mapOf(shared to mapOf(0L to 10,1L to 11)))
        rejects(mapOf(shared to setOf(0L,1L)),mapOf(shared to mapOf(0L to 10,1L to 10)))
        rejects(mapOf(shared to setOf(0L)),mapOf(shared to mapOf(0L to 1)))
        rejects(mapOf(shared to setOf(0L)),mapOf(shared to mapOf(0L to 10)),emptyMap())
        // The caller, not a rejected scene, rolls back every upload it allocated.
        driver.release(10); driver.release(11); assertEquals(listOf(10,11),driver.released)
    }

    @Test fun factoryRejectsCrossCelAndHistoryTextureAliasesWithoutDeletingAnyInput() {
        val driver=Driver(); val history=UndoLog<Int>(1_000_000,{ 4 },driver.released::add)
        history.push(UndoLog.Step(listOf(UndoLog.TileChange("legacy",0,70,71))))
        assertTrue(70 in history.heldTextures())
        for (uploads in listOf(mapOf(shared to mapOf(0L to 10),frame to mapOf(2L to 10)),
            mapOf(shared to mapOf(0L to 70),frame to mapOf(2L to 11)))) {
            assertFailsWith<IllegalArgumentException> { RecordedScene.createSaved(
                mapOf(shared to cel(),frame to cel(frame,listOf(record(key=2)))),brushes,history,driver,
                mapOf(shared to setOf(0L),frame to setOf(2L)),uploads) }
        }
        assertFailsWith<IllegalArgumentException> { RecordedScene.createSaved(
            mapOf(shared to cel(slabs=mapOf(8L to listOf(slab(0,1)))),
                frame to cel(frame,listOf(record(key=2)),mapOf(9L to listOf(slab(0,1))))),
            brushes,history,driver,mapOf(shared to setOf(0L),frame to setOf(2L)),
            mapOf(shared to mapOf(0L to 10),frame to mapOf(2L to 11))) }
        assertTrue(driver.released.isEmpty()); assertTrue(driver.lines.isEmpty())
    }

    @Test fun factoryRejectsOriginalLiveSceneLookAliasesUsingCallersProtectedHandles() {
        val previous=fixture()
        val oldLook=assertNotNull(previous.scene.look(shared,0))
        val driver=Driver(); val history=UndoLog<Int>(1_000_000,{ 4 },driver.released::add)
        assertFailsWith<IllegalArgumentException> { RecordedScene.createSaved(mapOf(shared to cel()),brushes,
            history,driver,mapOf(shared to setOf(0L)),mapOf(shared to mapOf(0L to oldLook)),
            protectedTextures=previous.scene.heldCacheTextures()) }
        assertTrue(driver.lines.isEmpty()); assertTrue(driver.looks.isEmpty()); assertTrue(driver.released.isEmpty())
        assertEquals(oldLook,previous.scene.look(shared,0)); assertTrue(previous.renderer.released.isEmpty())
        previous.scene.close(); assertOnce(previous.renderer,listOf(oldLook))
    }

    @Test fun factoryRejectsUnknownPlanesUnresolvedBrushAndLooksOnARecordFreeCel() {
        val driver=Driver(); val history=UndoLog<Int>(1_000_000,{ 4 },driver.released::add)
        for ((planes,keys,uploads) in listOf(
            Triple(mapOf(frame to cel()),mapOf(frame to setOf(0L)),mapOf(frame to mapOf(0L to 10))),
            Triple(mapOf(shared to cel(records=listOf(record(id="")))),mapOf(shared to setOf(0L)),mapOf(shared to mapOf(0L to 10))),
            Triple(mapOf(shared to cel(records=listOf(record(brush="missing")))),mapOf(shared to setOf(0L)),mapOf(shared to mapOf(0L to 10))),
            Triple(mapOf(shared to cel(records=emptyList())),mapOf(shared to setOf(0L)),mapOf(shared to mapOf(0L to 10))))) {
            assertFailsWith<IllegalArgumentException> { RecordedScene.createSaved(planes,brushes,history,driver,keys,uploads) }
        }
        assertTrue(driver.released.isEmpty()); assertTrue(driver.lines.isEmpty()); assertTrue(driver.looks.isEmpty())
    }

    @Test fun firstEditWarmsAllOldLinesInOnlyItsPhysicalCelAndPreservesUntouchedColdSavedPixels() {
        val f=fixture(mapOf(shared to cel(records=listOf(record(),record("second",2,4))),
            frame to cel(frame,listOf(record(key=8))),other to cel(other,listOf(record(key=9)))))
        val coldFrame=f.scene.planes.getValue(frame); val frameLook=f.scene.look(frame,8)
        val previous=f.scene.planes
        val proposal=f.scene.stage(replacement(f))
        assertEquals(listOf(address(),address(id="second"),address()),f.renderer.lines.map { it.address })
        assertEquals(listOf(0f,4f,2f),f.renderer.lines.map { it.record.samples.first().x })
        assertEquals(setOf(0L,2L),proposal.affected.getValue(shared)); assertEquals(setOf(shared),proposal.affected.keys)
        assertSame(previous,f.scene.planes); assertFalse(f.scene.isMaterialized(shared)); assertFalse(f.history.canUndo)
        assertTrue(proposal.commit()); proposal.close()
        assertTrue(f.scene.isMaterialized(shared)); assertFalse(f.scene.isMaterialized(frame)); assertFalse(f.scene.isMaterialized(other))
        assertSame(coldFrame,f.scene.planes.getValue(frame)); assertEquals(frameLook,f.scene.look(frame,8))
        assertEquals(setOf(2L,4L),f.scene.savedLookKeys(shared)); assertEquals(setOf(8L),f.scene.savedLookKeys(frame))
        assertEquals(1,f.history.undoDepth)
        // The superseded warm source for the edited OLD record has no owner after commit.
        assertOnce(f.renderer,listOf(f.renderer.minted.first()))
        assertTrue(f.uploadedTextures.filter { it != f.uploaded.getValue(shared).getValue(0) }.none { it in f.renderer.released })
    }

    @Test fun secondEditReusesMeasuredUnchangedUnitsAndDoesNotReplayColdNeighbors() {
        val f=fixture(mapOf(shared to cel(records=listOf(record(),record("second",2,4))),frame to cel(frame,listOf(record(key=8)))))
        f.scene.stage(replacement(f)).use { assertTrue(it.commit()) }; f.renderer.clearCalls()
        f.scene.stage(replacement(f,key=3)).use { assertTrue(it.commit()) }
        assertEquals(listOf(address()),f.renderer.lines.map { it.address })
        assertEquals(setOf(3L,4L),f.scene.savedLookKeys(shared)); assertFalse(f.scene.isMaterialized(frame))
    }

    @Test fun oneEditCanWarmTwoTouchedColdPhysicalCelsWithoutMaterializingAThird() {
        val f=fixture(mapOf(shared to cel(),frame to cel(frame,listOf(record(key=8))),other to cel(other,listOf(record(key=9)))))
        val entries=listOf(shared to 2L,frame to 4L).map { (plane,key) ->
            AddressedStroke(address(plane),f.scene.planes.getValue(plane).records.single()
                .copy(samples=listOf(PenSample(key.toFloat(),0f,0.0))))
        }
        val edit=RecordedEdits.replace(f.scene.planes,entries,f.scene.brushes.keys)
        f.scene.stage(edit).use { proposal ->
            assertEquals(setOf(shared,frame),proposal.affected.keys)
            assertEquals(setOf(0L,2L),proposal.affected.getValue(shared))
            assertEquals(setOf(8L,4L),proposal.affected.getValue(frame))
            assertTrue(proposal.commit())
        }
        assertEquals(2,f.renderer.lines.count { it.address == address(shared) })
        assertEquals(2,f.renderer.lines.count { it.address == address(frame) })
        assertFalse(f.scene.isMaterialized(other)); assertEquals(f.uploaded.getValue(other).getValue(9),f.scene.look(other,9))
        assertEquals(1,f.history.undoDepth)
    }

    @Test fun failureWarmingASecondTouchedCelReclaimsEarlierWarmUnitsWithoutPublishingEitherCel() {
        val driver=Driver().also { it.lineFailure={ address,_,_ -> if(address.celId == "frame") "later cel failure" else null } }
        val f=fixture(mapOf(shared to cel(),frame to cel(frame,listOf(record(key=8)))),driver=driver)
        val before=f.scene.planes
        val entries=listOf(shared,frame).map { plane -> AddressedStroke(address(plane),
            before.getValue(plane).records.single().copy(samples=listOf(PenSample(2f,0f,0.0)))) }
        val edit=RecordedEdits.replace(before,entries,f.scene.brushes.keys)
        assertFailsWith<IllegalStateException> { f.scene.stage(edit) }
        assertEquals(listOf(address(shared),address(frame)),driver.lines.map { it.address })
        assertColdUnchanged(f,before); assertOnce(driver,driver.minted); assertTrue(driver.looks.isEmpty())
        assertFalse(f.history.canUndo)
    }

    @Test fun overlappingUnchangedLineAndSlabsComposeWithOriginalSequenceAndOperator() {
        val f=fixture(planes=mapOf(shared to cel(records=listOf(record(seq=2,brush="erase"),record("second",4,0,"behind")),
            slabs=mapOf(0L to listOf(slab(0,1),slab(3,2,"normal"))))))
        f.scene.stage(replacement(f)).use { assertTrue(it.commit()) }
        val oldTile=f.renderer.looks.single { it.key == 0L }
        assertEquals(listOf(0L,3L,4L),oldTile.sources.map { it.seq })
        assertEquals(listOf("normal","normal","behind"),oldTile.sources.map { it.op })
        val newTile=f.renderer.looks.single { it.key == 2L }
        assertEquals(listOf(2L),newTile.sources.map { it.seq }); assertEquals(listOf("erase"),newTile.sources.map { it.op })
    }

    @Test fun missingOrExtraDeclaredCoverageRefusesBeforeEditingAndKeepsTheWholeColdScene() {
        for (keys in listOf(emptySet(),setOf(0L,7L))) {
            val f=fixture(keys=mapOf(shared to keys)); val before=f.scene.planes
            val failure=assertFailsWith<IllegalArgumentException> { f.scene.stage(replacement(f)) }
            assertTrue(failure.message!!.contains("union"))
            assertEquals(1,f.renderer.lines.size); assertEquals(0f,f.renderer.lines.single().record.samples.first().x)
            assertTrue(f.renderer.looks.isEmpty()); assertColdUnchanged(f,before)
            assertOnce(f.renderer,f.renderer.minted); assertFalse(f.history.canUndo)
        }
    }

    @Test fun explicitEmptyNoDabIndexCanWarmAndBakeWithoutInventingSlabsOrLooks() {
        val empty=record().copy(samples=emptyList())
        val f=fixture(mapOf(shared to cel(records=listOf(empty))))
        assertTrue(f.scene.savedLookKeys(shared).isEmpty()); assertFalse(f.scene.isMaterialized(shared))
        f.scene.stageBake(setOf(address())).use { assertTrue(it.affected.isEmpty()); assertTrue(it.commit()) }
        assertEquals(1,f.renderer.lines.size); assertTrue(f.renderer.looks.isEmpty())
        assertTrue(f.scene.planes.getValue(shared).records.isEmpty()); assertTrue(f.scene.planes.getValue(shared).slabs.isEmpty())
        val step=assertNotNull(f.history.peekUndo()); assertTrue(step.slabs.isEmpty()); assertEquals(1,step.lines.size)
    }

    @Test fun assetAndWatercolorCapRefusalsAfterEarlierUnitsPreserveSavedPixelsRecordsAndHistory() {
        for ((brushId,reason) in listOf("grain" to "Missing frozen grain picture", "water" to "More than 6000 watercolor dabs")) {
            val driver=Driver().also { it.lineFailure={ _,_,preset -> if(preset.id == brushId) reason else null } }
            val f=fixture(mapOf(shared to cel(records=listOf(record(),record("unsupported",2,4,brushId)))),driver=driver)
            val before=f.scene.planes
            assertFailsWith<IllegalStateException> { f.scene.stage(replacement(f)) }
            assertEquals(2,driver.lines.size); assertTrue(driver.looks.isEmpty()); assertColdUnchanged(f,before)
            assertOnce(driver,driver.minted); assertFalse(f.history.canUndo)
        }
    }

    @Test fun lateNewRecordReplayFailureAndCompositionFailureCleanWarmUnitsWithoutPublishing() {
        for (failReplay in listOf(true,false)) {
            val driver=Driver()
            if(failReplay) driver.lineFailure={ _,record,_ -> if(record.samples.firstOrNull()?.x == 2f) "edit replay failed" else null }
            else driver.failLookAt=1
            val f=fixture(driver=driver); val before=f.scene.planes
            assertFailsWith<IllegalStateException> { f.scene.stage(replacement(f)) }
            assertColdUnchanged(f,before); assertOnce(driver,driver.minted); assertFalse(f.history.canUndo)
        }
    }

    @Test fun laterCompositionFailureReclaimsAnEarlierFreshLookAndEveryWarmSource() {
        val driver=Driver().also { it.failLookAt=2 }
        val f=fixture(mapOf(shared to cel(records=listOf(record(),record("overlap",2)))),driver=driver)
        val before=f.scene.planes
        assertFailsWith<IllegalStateException> { f.scene.stage(replacement(f)) }
        assertEquals(2,driver.looks.size); assertColdUnchanged(f,before)
        assertOnce(driver,driver.minted); assertFalse(f.history.canUndo)
    }

    @Test fun warmRendererAliasesSavedSourceOrHistoryWithoutReleasingThoseBorrowedHandles() {
        for (alias in listOf(10,1,70)) {
            val driver=Driver(); val f=fixture(planes=mapOf(shared to cel(slabs=mapOf(8L to listOf(slab(0,1))))),driver=driver)
            f.history.push(UndoLog.Step(listOf(UndoLog.TileChange("legacy",0,70,71))))
            driver.lineResult={ unit -> RecordedScene.Unit(unit.op,mapOf(0L to alias,1L to unit.tiles.values.single())) }
            val before=f.scene.planes
            assertFailsWith<IllegalArgumentException> { f.scene.stage(replacement(f)) }
            assertColdUnchanged(f,before); assertFalse(alias in driver.released); assertOnce(driver,driver.minted)
        }
    }

    @Test fun compositionAliasingAnUntouchedColdLookReclaimsOnlyFreshWarmAndEditUnits() {
        val f=fixture(mapOf(shared to cel(),frame to cel(frame,listOf(record(key=8)))))
        f.renderer.lookAlias=f.uploaded.getValue(frame).getValue(8); val before=f.scene.planes
        assertFailsWith<IllegalArgumentException> { f.scene.stage(replacement(f)) }
        assertColdUnchanged(f,before); assertOnce(f.renderer,f.renderer.minted)
    }

    @Test fun discardedProposalKeepsColdStateAndReclaimsWarmAndEditResourcesExactlyOnce() {
        val f=fixture(); val before=f.scene.planes; val staged=f.scene.stage(replacement(f))
        val fresh=f.renderer.minted.toList(); staged.close(); staged.close()
        assertColdUnchanged(f,before); assertOnce(f.renderer,fresh); assertFalse(f.history.canUndo)
    }

    @Test fun staleHistoryAndChangedContextCannotPublishAWarmedProposal() {
        for (contextChanged in listOf(false,true)) {
            val f=fixture(); val before=f.scene.planes; val staged=f.scene.stage(replacement(f))
            if(contextChanged) f.renderer.context=false
            else f.history.push(UndoLog.Step(listOf(UndoLog.TileChange("legacy",0,70,71))))
            assertFalse(staged.commit()); staged.close()
            assertColdUnchanged(f,before); assertOnce(f.renderer,f.renderer.minted)
            assertEquals(if(contextChanged) 0 else 1,f.history.undoDepth)
        }
    }

    @Test fun competingColdProposalCannotOverwriteTheAlreadyCommittedWarmScene() {
        val f=fixture(); val first=f.scene.stage(replacement(f,key=2))
        val nextMint=f.renderer.minted.size; val second=f.scene.stage(replacement(f,key=3))
        val secondFresh=f.renderer.minted.drop(nextMint)
        assertTrue(first.commit()); first.close()
        val live=f.scene.heldCacheTextures()
        assertFalse(second.commit()); second.close()
        assertEquals(2f,f.scene.planes.getValue(shared).records.single().samples.first().x)
        assertEquals(setOf(2L),f.scene.savedLookKeys(shared)); assertEquals(1,f.history.undoDepth)
        assertOnce(f.renderer,secondFresh); assertTrue(live.none { it in f.renderer.released })
    }

    @Test fun changedContextDuringOldCelMaterializationRefusesAndKeepsColdState() {
        val driver=Driver(); val f=fixture(driver=driver); val before=f.scene.planes
        driver.afterLine={ driver.context=false }
        assertFailsWith<IllegalStateException> { f.scene.stage(replacement(f)) }
        assertColdUnchanged(f,before); assertOnce(driver,driver.minted); assertTrue(driver.looks.isEmpty())
    }

    @Test fun forgottenContextDoesNotDeleteDeadUploadOrProposalNamesInReplacementContext() {
        val f=fixture(); val proposal=f.scene.stage(replacement(f))
        f.scene.forget(); proposal.close(); f.scene.close()
        assertTrue(f.scene.planes.isEmpty()); assertTrue(f.scene.heldCacheTextures().isEmpty())
        assertTrue(f.renderer.released.isEmpty())
    }

    @Test fun reentrantForgetAfterFirstWarmMintStopsAllFurtherReplayCompositionAndBakeWork() {
        for(baking in listOf(false,true)) {
            val f=fixture(mapOf(shared to cel(records=listOf(record(),record("second",2,4)))))
            f.renderer.afterLine={ f.scene.forget() }
            assertFailsWith<IllegalStateException> {
                if(baking) f.scene.stageBake(setOf(address(),address(id="second")))
                else f.scene.stage(replacement(f))
            }
            assertEquals(listOf(address()),f.renderer.lines.map { it.address })
            assertEquals(1,f.renderer.minted.size)
            assertTrue(f.renderer.looks.isEmpty()); assertTrue(f.renderer.copies.isEmpty())
            assertTrue(f.renderer.released.isEmpty()); assertTrue(f.scene.planes.isEmpty()); assertFalse(f.history.canUndo)
        }
    }

    @Test fun reentrantForgetDuringWarmSceneEditStopsBeforeAnotherLineOrLookCallback() {
        val f=fixture(mapOf(shared to cel(records=listOf(record(),record("second",2,4)))))
        f.scene.stage(replacement(f)).use { assertTrue(it.commit()) }
        f.renderer.clearCalls(); val oldDeletes=f.renderer.released.toList(); val oldMintCount=f.renderer.minted.size
        val entries=listOf("same" to 6L,"second" to 8L).map { (id,key) -> AddressedStroke(address(id=id),
            f.scene.planes.getValue(shared).records.first { it.id == id }.copy(samples=listOf(PenSample(key.toFloat(),0f,0.0)))) }
        val edit=RecordedEdits.replace(f.scene.planes,entries,f.scene.brushes.keys)
        f.renderer.afterLine={ f.scene.forget() }
        assertFailsWith<IllegalStateException> { f.scene.stage(edit) }
        assertEquals(1,f.renderer.lines.size); assertEquals(oldMintCount+1,f.renderer.minted.size)
        assertTrue(f.renderer.looks.isEmpty()); assertTrue(f.renderer.copies.isEmpty())
        assertEquals(oldDeletes,f.renderer.released); assertEquals(1,f.history.undoDepth)
    }

    @Test fun reentrantForgetDuringFirstBakeCopyStopsBeforeAnotherCopyOrComposition() {
        val f=fixture(mapOf(shared to cel(records=listOf(record(),record("second",2,4)))))
        f.renderer.afterCopy={ f.scene.forget() }
        assertFailsWith<IllegalStateException> { f.scene.stageBake(setOf(address(),address(id="second"))) }
        assertEquals(2,f.renderer.lines.size); assertEquals(1,f.renderer.copies.size)
        assertTrue(f.renderer.looks.isEmpty()); assertTrue(f.renderer.released.isEmpty())
        assertTrue(f.scene.planes.isEmpty()); assertFalse(f.history.canUndo)
    }

    @Test fun reentrantForgetDuringFirstLookStopsBeforeAnotherCompositionAndDeletesNoOldNames() {
        val f=fixture(mapOf(shared to cel(records=listOf(record(),record("overlap",2)))))
        f.renderer.afterLook={ f.scene.forget() }
        assertFailsWith<IllegalStateException> { f.scene.stage(replacement(f)) }
        assertEquals(3,f.renderer.lines.size); assertEquals(1,f.renderer.looks.size)
        assertTrue(f.renderer.copies.isEmpty()); assertTrue(f.renderer.released.isEmpty())
        assertTrue(f.scene.planes.isEmpty()); assertFalse(f.history.canUndo)
    }

    @Test fun coldBakeWarmsOnlyRequestedCelsAndCopiesCanonicalUnitsAtOriginalSequenceAndOperator() {
        val f=fixture(mapOf(shared to cel(records=listOf(record(seq=2,brush="erase"),record("second",4,4))),
            frame to cel(frame,listOf(record(key=8)))))
        val before=f.scene.planes
        val proposal=f.scene.stageBake(setOf(address()))
        assertEquals(listOf(address(),address(id="second")),f.renderer.lines.map { it.address })
        assertSame(before,f.scene.planes); assertFalse(f.scene.isMaterialized(shared))
        assertEquals(listOf(address()),f.renderer.copies); assertEquals("erase",f.renderer.copyInputs.single().op)
        assertTrue(proposal.commit()); proposal.close()
        val baked=f.scene.planes.getValue(shared).slabs.getValue(0).single()
        assertEquals(2L,baked.seq); assertEquals("erase",baked.value.op)
        assertEquals(f.renderer.copied.single().tiles.getValue(0),baked.value.texture)
        assertEquals(setOf(4L),f.scene.savedLookKeys(shared)); assertFalse(f.scene.isMaterialized(frame))
        assertEquals(8f,f.scene.planes.getValue(frame).records.single().samples.first().x)
    }

    @Test fun bakeUndoRedoRetainsPhysicalSourcesAndWarmsNoUnrelatedColdCel() {
        val f=fixture(mapOf(shared to cel(),frame to cel(frame,listOf(record(key=8)))))
        val original=f.scene.planes.getValue(shared).records.single()
        f.scene.stageBake(setOf(address())).use { assertTrue(it.commit()) }
        val step=assertNotNull(f.history.peekUndo()); val baked=assertNotNull(step.slabs.single().after).texture
        f.renderer.clearCalls()
        f.scene.stage(RecordedEdits.prepare(f.scene.planes,step,false),RecordedScene.Direction.UNDO).use { assertTrue(it.commit()) }
        assertEquals(listOf(original),f.scene.planes.getValue(shared).records); assertEquals(setOf(0L),f.scene.savedLookKeys(shared))
        assertEquals(listOf(address()),f.renderer.lines.map { it.address }); assertFalse(f.scene.isMaterialized(frame))
        f.renderer.clearCalls()
        f.scene.stage(RecordedEdits.prepare(f.scene.planes,step,true),RecordedScene.Direction.REDO).use { assertTrue(it.commit()) }
        assertTrue(f.renderer.lines.isEmpty()); assertEquals(baked,f.scene.planes.getValue(shared).slabs.getValue(0).single().value.texture)
        f.scene.close(); assertFalse(baked in f.renderer.released)
    }

    @Test fun coldBakeScopeValidationPrecedesAnyReplayAndAnyCopy() {
        val f=fixture(mapOf(shared to cel(records=listOf(record(),record("locked",2,4,locked=true)))))
        for(scope in listOf(emptySet(),setOf(address(),address(id="locked")))) {
            assertFailsWith<IllegalArgumentException> { f.scene.stageBake(scope) }
            assertTrue(f.renderer.lines.isEmpty()); assertTrue(f.renderer.copies.isEmpty()); assertFalse(f.history.canUndo)
        }
        assertFailsWith<IllegalStateException> { f.scene.stageBake(setOf(address(),address(id="missing"))) }
        assertTrue(f.renderer.lines.isEmpty()); assertTrue(f.renderer.copies.isEmpty())
    }

    @Test fun coldBakeCopyFailureOrWrongOperatorCleansWarmUnitsAndCopiesOnly() {
        for (wrongOperator in listOf(false,true)) {
            val driver=Driver()
            if(wrongOperator) driver.copyResult={ RecordedScene.Unit("erase",it.tiles) } else driver.failCopyAt=2
            val f=fixture(mapOf(shared to cel(records=listOf(record(),record("second",2,4)))),driver=driver)
            val before=f.scene.planes
            if(wrongOperator) assertFailsWith<IllegalArgumentException> { f.scene.stageBake(setOf(address())) }
            else assertFailsWith<IllegalStateException> { f.scene.stageBake(setOf(address(),address(id="second"))) }
            assertColdUnchanged(f,before); assertOnce(driver,driver.minted); assertFalse(f.history.canUndo)
        }
    }

    @Test fun coldBakeCopyCannotAliasWarmUnitOrUploadedLookOrHistoryTexture() {
        for(aliasKind in listOf("unit","look","history")) {
            val f=fixture(); f.history.push(UndoLog.Step(listOf(UndoLog.TileChange("legacy",0,70,71))))
            f.renderer.copyResult={ copy -> RecordedScene.Unit(copy.op,mapOf(
                0L to when(aliasKind) { "unit" -> f.renderer.copyInputs.single().tiles.getValue(0); "look" -> 10; else -> 70 },
                1L to copy.tiles.values.single())) }
            val before=f.scene.planes
            assertFailsWith<IllegalArgumentException> { f.scene.stageBake(setOf(address())) }
            assertColdUnchanged(f,before); assertOnce(f.renderer,f.renderer.minted)
            assertFalse(70 in f.renderer.released); assertSame(before,f.scene.planes)
        }
    }

    @Test fun publicationBudgetFailureClosesAllWarmBakeAndLookResourcesWithoutChangingColdState() {
        var fail=false
        val f=fixture(sizeOf={ if(fail) error("budget failed") else 4 })
        val previous=UndoLog.Step(listOf(UndoLog.TileChange("legacy",0,70,71))); f.history.push(previous)
        val before=f.scene.planes; val proposal=f.scene.stageBake(setOf(address())); fail=true
        assertFailsWith<IllegalStateException> { proposal.commit() }; proposal.close()
        assertColdUnchanged(f,before); assertSame(previous,f.history.peekUndo()); assertOnce(f.renderer,f.renderer.minted)
        assertFalse(70 in f.renderer.released)
    }

    @Test fun frozenReplacementRefusesBeforeWarmingAndMetadataEditMayWarmItsOwnCel() {
        val f=fixture(); val changedBrushes=brushes + ("pen" to pen.copy(size=Param(12f)))
        assertFailsWith<IllegalArgumentException> { f.scene.stage(replacement(f),frozen=changedBrushes) }
        assertTrue(f.renderer.lines.isEmpty())
        val old=f.scene.planes.getValue(shared).records.single()
        val lock=RecordedEdits.replace(f.scene.planes,listOf(AddressedStroke(address(),old.copy(locked=true))),f.scene.brushes.keys)
        f.scene.stage(lock).use { assertTrue(it.commit()) }
        assertEquals(1,f.renderer.lines.size); assertTrue(f.scene.isMaterialized(shared))
        assertTrue(f.scene.planes.getValue(shared).records.single().locked)
    }

    @Test fun coldSnapshotAndArchiveKeepRawSlabsFrozenBrushesAndDeclaredLooksWithZeroRendererCalls() {
        var serial=0
        val doc=DocOps.newDocument("doc","Art",128,128) { "id-${++serial}" }
        val layer=doc.layers.single(); val plane=layer.id to null
        val f=fixture(mapOf(plane to cel(plane,listOf(record()),mapOf(0L to listOf(slab(0,1),slab(2,2,"behind"))))))
        val sourceBytes=mapOf(1 to ByteArray(TILE_BYTES) { 1 },2 to ByteArray(TILE_BYTES) { 2 })
        val lookBytes=ByteArray(TILE_BYTES) { 7 }
        val snapshot=RecordedSnapshot.capture(doc,f.scene.planes,f.scene.brushes,
            readTexture={ sourceBytes[it] },keys={ _,_ -> emptyList() },read={ _,_,_ -> error("Recorded source only") },
            expectedLookKeys=mapOf(plane to f.scene.savedLookKeys(plane).toList()),
            lookRead={ _,_,key -> assertEquals(0L,key); lookBytes })
        val encoded=ByteArrayOutputStream().also { JbArchive.write(it,snapshot) }.toByteArray()
        val opened=JbArchive.read(ByteArrayInputStream(encoded)); val stored=opened.doc.layers.single().cels.single()
        assertEquals(listOf(record()),opened.strokes.getValue(layer.id to stored.id))
        assertEquals(listOf("0_0"),stored.looks); assertEquals(listOf(0L,2L),stored.slabs.map { it.seq })
        assertEquals(listOf("normal","behind"),stored.slabs.map { it.op })
        assertContentEquals(lookBytes,opened.looks.getValue(Triple(layer.id,stored.id,"0_0")))
        assertEquals(f.scene.brushes,opened.brushes); assertFalse(f.scene.isMaterialized(plane))
        assertTrue(f.renderer.lines.isEmpty()); assertTrue(f.renderer.looks.isEmpty()); assertFalse(f.history.canUndo)
    }

    @Test fun finalContextCallbackCannotPublishOverAResetScene() {
        val f=fixture(); val proposal=f.scene.stage(replacement(f))
        f.renderer.afterContext={ f.scene.forget() }
        assertFalse(proposal.commit()); proposal.close()
        assertTrue(f.scene.planes.isEmpty()); assertFalse(f.history.canUndo)
        assertTrue(f.renderer.released.isEmpty())
    }

    @Test fun finalContextCallbackCannotOverwriteAConcurrentHistoryHead() {
        val f=fixture(); val before=f.scene.planes; val proposal=f.scene.stage(replacement(f))
        val external=UndoLog.Step(listOf(UndoLog.TileChange("legacy",0,70,71)))
        f.renderer.afterContext={ f.history.push(external) }
        assertFalse(proposal.commit()); proposal.close()
        assertColdUnchanged(f,before); assertSame(external,f.history.peekUndo())
        assertOnce(f.renderer,f.renderer.minted); assertFalse(70 in f.renderer.released)
    }

    @Test fun factoryContextRefusalNeverAdoptsOrDeletesUploadHandles() {
        for (late in listOf(false,true)) {
            val driver=Driver().also { if(late) it.failContextCheckAt=2 else it.context=false }
            val history=UndoLog<Int>(1_000_000,{ 4 },driver.released::add)
            assertFailsWith<IllegalStateException> { RecordedScene.createSaved(mapOf(shared to cel()),brushes,history,driver,
                mapOf(shared to setOf(0L)),mapOf(shared to mapOf(0L to 10))) }
            assertTrue(driver.released.isEmpty()); assertTrue(driver.lines.isEmpty()); assertTrue(driver.looks.isEmpty())
        }
    }
}
