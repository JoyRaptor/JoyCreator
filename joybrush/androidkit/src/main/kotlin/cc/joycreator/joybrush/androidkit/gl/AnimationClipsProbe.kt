package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.io.*
import cc.joycreator.joybrush.androidkit.layers.MergeDownPlan
import cc.joycreator.joybrush.core.anim.PlaybackClock
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.export.AnimExport
import cc.joycreator.joybrush.core.export.SceneAnimExportPlan
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Debug proof on a disposable engine. Call only on a GL thread with its context current.
 * All files belong to a fresh, empty caller-supplied directory; no owner document is opened or changed.
 * The returned rows describe checks actually executed on that device, including failures and cleanup. */
object AnimationClipsProbe {
    fun run(directory: File): String {
        val rows=JSONArray()
        val report=JSONObject().put("probe","animationClips").put("passed",false).put("rows",rows)
            .put("ownerFilesTouched",false).put("comparison","Exact bounded RGBA8 GPU projections versus original CPU scene")
        val engine=GlPaintEngine()
        var initialized=false
        var cleanup=false
        var failed=false
        var serial=0
        val fresh={ "probe-generated-${++serial}" }
        val original=fixture()
        fun checkGl() {
            val errors=arrayListOf<Int>()
            repeat(32) { val error=GLES30.glGetError(); if(error != GLES30.GL_NO_ERROR) errors.add(error) }
            check(errors.isEmpty()) { "GL errors: ${errors.joinToString { "0x${it.toString(16)}" }}" }
        }
        fun load(contents: JbContents = original) {
            engine.resetDocument()
            contents.doc.layers.forEach { engine.addLayer(it.id); engine.setLayerName(it.id,it.name) }
            engine.setBoardDocument(contents.doc)
            contents.tiles.forEach { (address,bytes) -> engine.writeCelTile(address.first,address.second,0L,bytes) }
            check(engine.undo.undoDepth == 0)
        }
        fun row(name: String, block: (JSONObject) -> Unit) {
            val result=JSONObject().put("name",name).put("passed",false)
            rows.put(result)
            try { load(); block(result); checkGl(); result.put("passed",true) }
            catch(failure: Throwable) { failed=true; result.put("error","${failure.javaClass.simpleName}: ${failure.message}") }
        }
        fun compareLayers(expected: JbContents, previews: Map<String,String> = emptyMap(), bounds: RectPx = crop) {
            for(layer in expected.doc.layers) {
                val one=expected.copy(doc=expected.doc.copy(layers=listOf(layer),activeLayerId=layer.id))
                equalPixels(render(one,previews,bounds),visible(engine,layer.id,bounds),"layer ${layer.name}")
            }
        }
        fun assertRestored() {
            check(engine.undoStep()) { "Undo refused" }
            check(engine.boardDocument == original.doc) { "Undo did not restore both original clips and mappings" }
            check(engine.undo.undoDepth == 0) { "Operation used more than one Undo step" }
            comparePhysical(original,snapshot(engine))
            compareLayers(original)
        }
        fun archive(name: String, captured: JbContents): JbContents {
            val file=File(directory,name)
            check(!file.exists()) { "Artifact already exists" }
            file.outputStream().use { JbArchive.write(it,captured) }
            return file.inputStream().use { JbArchive.read(it) }.also { back ->
                check(back.doc == captured.doc) { "Archive changed end modes, mappings or cel metadata" }
                comparePhysical(captured,back)
            }
        }
        fun gif(name: String, bytes: ByteArray) {
            check(bytes.size>14 && bytes.copyOfRange(0,6).toString(Charsets.US_ASCII)=="GIF89a" && bytes.last()==0x3b.toByte()) { "Incomplete GIF" }
            val file=File(directory,name)
            check(!file.exists())
            file.writeBytes(bytes)
        }
        try {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create probe directory" }
            check(directory.listFiles()?.isEmpty() == true) { "Probe directory must be empty" }
            check(DocOps.validate(original.doc).isEmpty()) { DocOps.validate(original.doc).joinToString() }
            engine.init(); initialized=true
            row("fixtureGpuReadback") { result ->
                comparePhysical(original,snapshot(engine)); compareLayers(original)
                archive("animation-clips-original.joybrush",snapshot(engine))
                result.put("layers",2).put("clips",2).put("frames",5).put("crop","16x12")
            }
            row("combinePixelsAndSingleUndo") { result ->
                engine.applyBoardChange(AnimationBoardOps.combine(original.doc,"background","character",fresh))
                val combined=checkNotNull(engine.boardDocument)
                val board=combined.boards.single { it.kind == BoardKind.ANIMATION }
                check(board.frames.size==6 && board.fps==24f)
                board.frames.forEachIndexed { tick,frame ->
                    engine.setFramePreviews(mapOf(board.id to frame.id))
                    compareLayers(original,previews(original.doc,tick,board.fps))
                }
                engine.setFramePreviews(emptyMap())
                val captured=snapshot(engine)
                val back=archive("animation-clips-combined.joybrush",captured)
                board.frames.forEachIndexed { tick,frame ->
                    equalPixels(render(original,previews(original.doc,tick,board.fps)),render(back,mapOf(board.id to frame.id)),"combined archive tick $tick")
                }
                assertRestored()
                result.put("framesCompared",board.frames.size).put("layerComparisons",board.frames.size*2).put("undoSteps",1)
            }
            row("directMovePreviewCancelAndCommitUndo") { result ->
                val cursor=engine.undo.snapshotCursor()
                val before=snapshot(engine)
                val moved=translated(original,3,2)
                engine.setClipMovePreview("background",RectPx(3,2,8,8))
                compareLayers(moved)
                check(engine.boardDocument==original.doc) { "Preview wrote saved geometry" }
                check(engine.undo.snapshotCursor() === cursor) { "Preview changed history" }
                comparePhysical(before,snapshot(engine))
                engine.setClipMovePreview(null,null)
                compareLayers(original)
                check(engine.undo.snapshotCursor() === cursor) { "Cancel changed history" }
                engine.applyBoardChange(AnimationBoardOps.moveAll(original.doc,"background",3,2))
                compareLayers(moved)
                comparePhysical(moved,snapshot(engine))
                assertRestored()
                result.put("previewMetadataUnchanged",true).put("previewHistoryUnchanged",true)
                    .put("previewPhysicalPixelsUnchanged",true).put("cancelRestoredPixels",true).put("undoSteps",1)
            }
            row("flattenFramewiseCompositeAndSingleUndo") { result ->
                val before=engine.stack("bottom")
                val after=LayerStack(listOf(before.layers.first()),"bottom")
                engine.flattenStep("bottom",listOf("bottom","top"),emptyList(),before,after)
                val flattened=checkNotNull(engine.boardDocument)
                check(flattened.layers.size==1)
                val board=flattened.boards.single { it.kind==BoardKind.ANIMATION }
                check(board.frames.size==6)
                board.frames.forEachIndexed { tick,frame ->
                    engine.setFramePreviews(mapOf(board.id to frame.id))
                    equalPixels(render(original,previews(original.doc,tick,board.fps)),visible(engine,"bottom"),"flatten tick $tick")
                }
                engine.setFramePreviews(emptyMap())
                val back=archive("animation-clips-flattened.joybrush",snapshot(engine))
                board.frames.forEachIndexed { tick,frame ->
                    equalPixels(render(original,previews(original.doc,tick,board.fps)),render(back,mapOf(board.id to frame.id)),"flatten archive tick $tick")
                }
                assertRestored()
                result.put("framesCompared",board.frames.size).put("undoSteps",1)
            }
            row("singleGifSavedEndFlagsAndSceneGif") { result ->
                val captured=snapshot(engine)
                val board=captured.doc.boards.first { it.id=="background" }
                val flags=JSONObject()
                for(end in BoardEnd.ALL) {
                    val variant=board.copy(end=end)
                    val contents=captured.copy(doc=captured.doc.copy(boards=captured.doc.boards.map { if(it.id==board.id) variant else it }))
                    val file=AnimExportRunner.encodeOne(AnimFormat.GIF,contents,board.id,AnimExport.plan(variant,"clip-$end"),false).single()
                    val loops=file.bytes.toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0")
                    check(loops == (end==BoardEnd.LOOP || end==BoardEnd.PING_PONG)) { "$end GIF has incorrect loop flag" }
                    gif("clip-$end.gif",file.bytes); flags.put(end,loops)
                }
                val scene=SceneAnimExportPlan.build(captured.doc)
                SceneAnimExportRunner.write(captured,scene,true,false,AnimFileSink { _,_,bytes -> gif("animation-clips-scene.gif",bytes) })
                check(engine.boardDocument==original.doc && engine.undo.undoDepth==0)
                result.put("loopFlags",flags).put("sceneFrames",scene.previews.size)
            }
            row("componentLinksEditAndMoveAcrossTiles") { result ->
                val source=componentFixture(false)
                load(source)
                engine.applyBoardChange(AnimationBoardOps.combine(source.doc,"part-a","part-b",fresh))
                val combined=checkNotNull(engine.boardDocument)
                val board=combined.boards.single { it.kind==BoardKind.ANIMATION }
                check(board.frames.size==4)
                board.frames.forEachIndexed { tick,frame ->
                    engine.setFramePreviews(mapOf(board.id to frame.id))
                    compareLayers(source,previews(source.doc,tick,board.fps))
                }
                engine.setFramePreviews(emptyMap())
                val beforeEdit=snapshot(engine)
                val first=RegionDocumentOps.paintPlan(combined,"bottom",mapOf(board.id to board.frames[0].id))
                val third=RegionDocumentOps.paintPlan(combined,"bottom",mapOf(board.id to board.frames[2].id))
                val drawing=first.planeAt(2,2).celId
                check(drawing==third.planeAt(2,2).celId) { "The repeated component lost its physical identity" }
                // Deliberate low-level diagnostic write: verify the routing itself without adding a history step.
                val editedTile=checkNotNull(engine.readCelTile("bottom",drawing,0L)).copyOf()
                val pixel=(2*TILE_SIZE+2)*4
                editedTile[pixel]=247.toByte(); editedTile[pixel+1]=1; editedTile[pixel+2]=149.toByte(); editedTile[pixel+3]=-1
                val history=engine.undo.snapshotCursor()
                engine.writeCelTile("bottom",drawing,0L,editedTile)
                check(engine.undo.snapshotCursor() === history)
                val edited=beforeEdit.copy(tiles=beforeEdit.tiles+(Triple("bottom",drawing,"0_0") to editedTile))
                board.frames.forEach { frame ->
                    val preview=mapOf(board.id to frame.id)
                    engine.setFramePreviews(preview); compareLayers(edited,preview)
                }
                engine.setFramePreviews(emptyMap())
                for((x,bounds) in listOf(3 to crop,253 to RectPx(248,0,24,12))) {
                    val beforeMove=snapshot(engine)
                    val expected=moveContents(edited,board.id,x,2)
                    val cursor=engine.undo.snapshotCursor(); val depth=engine.undo.undoDepth
                    engine.setClipMovePreview(board.id,board.rect.copy(x=x,y=2))
                    compareLayers(expected,bounds=bounds)
                    check(engine.boardDocument==combined && engine.undo.snapshotCursor() === cursor)
                    comparePhysical(beforeMove,snapshot(engine))
                    engine.setClipMovePreview(null,null); compareLayers(edited)
                    engine.applyBoardChange(AnimationBoardOps.moveAll(combined,board.id,x,2))
                    check(engine.undo.undoDepth==depth+1)
                    board.frames.forEach { frame ->
                        val preview=mapOf(board.id to frame.id)
                        engine.setFramePreviews(preview); compareLayers(expected,preview,bounds)
                    }
                    engine.setFramePreviews(emptyMap())
                    comparePhysical(expected,snapshot(engine))
                    check(engine.undoStep()); check(engine.boardDocument==combined && engine.undo.undoDepth==depth)
                    comparePhysical(beforeMove,snapshot(engine)); compareLayers(edited)
                }
                val back=archive("animation-clips-component-links.joybrush",snapshot(engine))
                board.frames.forEach { frame -> equalPixels(render(edited,mapOf(board.id to frame.id)),render(back,mapOf(board.id to frame.id)),"component archive ${frame.id}") }
                result.put("framesCompared",4).put("repeatedDrawingEdited",true).put("moveDestinations",JSONArray(listOf(3,253)))
                    .put("movesEachUsedOneUndo",true).put("previewPhysicalPixelsUnchanged",true)
            }
            row("componentCurrentFrameMergeAndUndo") { result ->
                val source=componentFixture(true)
                load(source)
                engine.applyBoardChange(AnimationBoardOps.combine(source.doc,"part-a","part-b",fresh))
                val savedMetadata=checkNotNull(engine.boardDocument)
                val combined=snapshot(engine)
                val board=combined.doc.boards.single { it.kind==BoardKind.ANIMATION }
                val current=checkNotNull(board.currentFrameId)
                val lowerOnly=combined.copy(doc=combined.doc.copy(layers=combined.doc.layers.filter { it.id=="bottom" },activeLayerId="bottom"))
                val upperOnly=combined.copy(doc=combined.doc.copy(layers=combined.doc.layers.filter { it.id=="top" },activeLayerId="top"))
                val expectedLower=render(lowerOnly); val expectedUpper=render(upperOnly); val scene=render(combined)
                for(y in board.rect.y until board.rect.y+board.rect.h) for(x in board.rect.x until board.rect.x+board.rect.w) {
                    val pixel=(y*crop.w+x)*4
                    scene.copyInto(expectedLower,pixel,pixel,pixel+4)
                    for(c in 0..3) expectedUpper[pixel+c]=0
                }
                val before=engine.stack("top"); val depth=engine.undo.undoDepth
                engine.mergeDownStep("top","bottom",MergeDownPlan.Way.PIXELS,"normal",false,before,before,board.id)
                check(engine.undo.undoDepth==depth+1)
                equalPixels(expectedLower,visible(engine,"bottom"),"component merged lower current frame")
                equalPixels(expectedUpper,visible(engine,"top"),"component emptied upper current frame")
                for(frame in board.frames.filter { it.id!=current }) {
                    val preview=mapOf(board.id to frame.id)
                    engine.setFramePreviews(preview); compareLayers(combined,preview)
                }
                engine.setFramePreviews(emptyMap())
                archive("animation-clips-component-merged.joybrush",snapshot(engine))
                check(engine.undoStep()); check(engine.undo.undoDepth==depth && engine.boardDocument==savedMetadata)
                comparePhysical(combined,snapshot(engine)); compareLayers(combined)
                check(engine.redoStep())
                equalPixels(expectedLower,visible(engine,"bottom"),"component merge redo lower")
                equalPixels(expectedUpper,visible(engine,"top"),"component merge redo upper")
                check(engine.undoStep()); comparePhysical(combined,snapshot(engine))
                result.put("otherFramesUnchanged",board.frames.size-1).put("undoSteps",1).put("redoPixelsVerified",true)
            }
            row("diskArchivesReloadIntoGpu") { result ->
                val files=directory.listFiles().orEmpty().filter { it.extension=="joybrush" }.sortedBy { it.name }
                check(files.size==5) { "Expected five saved drawing archives, got ${files.size}" }
                var frames=0
                for(file in files) {
                    val reopened=file.inputStream().use { JbArchive.read(it) }
                    load(reopened)
                    comparePhysical(reopened,snapshot(engine)); compareLayers(reopened)
                    for(board in reopened.doc.boards.filter { it.kind==BoardKind.ANIMATION }) {
                        for(frame in board.frames) {
                            val preview=mapOf(board.id to frame.id)
                            engine.setFramePreviews(preview); compareLayers(reopened,preview); frames++
                        }
                    }
                    engine.setFramePreviews(emptyMap())
                }
                result.put("archivesReopened",files.size).put("gpuFramesCompared",frames)
            }
        } catch(failure: Throwable) {
            failed=true; report.put("error","${failure.javaClass.simpleName}: ${failure.message}")
        } finally {
            if(initialized) try { engine.release(); cleanup=true } catch(failure: Throwable) {
                failed=true; report.put("cleanupError","${failure.javaClass.simpleName}: ${failure.message}")
            }
        }
        val artifacts=JSONArray()
        directory.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach {
            artifacts.put(JSONObject().put("name",it.name).put("bytes",it.length()))
        }
        return report.put("cleanupComplete",cleanup).put("artifacts",artifacts)
            .put("passed",!failed && cleanup && rows.length()==8).toString()
    }

    private val crop=RectPx(0,0,16,12)
    private fun fixture(): JbContents {
        val a=Board("background","Background",BoardKind.ANIMATION,RectPx(0,0,8,8),fps=12f,
            frames=List(3) { Frame("a$it") },currentFrameId="a0",end=BoardEnd.HOLD)
        val b=Board("character","Character",BoardKind.ANIMATION,RectPx(4,2,6,6),fps=24f,
            frames=List(2) { Frame("b$it") },currentFrameId="b0",end=BoardEnd.BLANK)
        val lower=Layer("bottom","Background layer",LayerKind.PAINT,sharedCelId="bottom-shared",
            cels=listOf(Cel("bottom-shared"))+a.frames.map { Cel("bottom-${it.id}") },
            regions=listOf(RegionFrames(a.id,a.frames.associate { it.id to "bottom-${it.id}" })))
        val upper=Layer("top","Character layer",LayerKind.PAINT,sharedCelId="top-shared",
            cels=listOf(Cel("top-shared"))+b.frames.map { Cel("top-${it.id}") },
            regions=listOf(RegionFrames(b.id,b.frames.associate { it.id to "top-${it.id}" })))
        val tiles=linkedMapOf<Triple<String,String,String>,ByteArray>()
        fun tile(layer: String,cel: String,rect: RectPx,r: Int,g: Int,b: Int) {
            val bytes=ByteArray(TILE_BYTES)
            for(y in rect.y until rect.y+rect.h) for(x in rect.x until rect.x+rect.w) {
                val p=(y*TILE_SIZE+x)*4; bytes[p]=r.toByte(); bytes[p+1]=g.toByte(); bytes[p+2]=b.toByte(); bytes[p+3]=-1
            }
            tiles[Triple(layer,cel,"0_0")]=bytes
        }
        tile("bottom","bottom-shared",RectPx(12,1,2,2),255,255,0)
        tile("top","top-shared",RectPx(1,1,2,2),0,255,255)
        a.frames.forEachIndexed { i,f -> tile("bottom","bottom-${f.id}",a.rect,40+i*70,20,40) }
        b.frames.forEachIndexed { i,f -> tile("top","top-${f.id}",b.rect,10,100+i*100,30) }
        val doc=JbDocument(id="animation-clips-probe",name="Animation clips device proof",boards=listOf(a,b),
            layers=listOf(lower,upper).map { l -> l.copy(cels=l.cels.map { it.copy(tiles=listOf("0_0")) }) },
            activeLayerId="top",activeBoardId=b.id)
        return JbContents(doc,tiles,emptyMap())
    }
    private fun componentFixture(twoLayers: Boolean): JbContents {
        val a=Board("part-a","Character",BoardKind.ANIMATION,RectPx(0,0,6,8),fps=24f,
            frames=List(2) { Frame("part-a-$it") },currentFrameId="part-a-0")
        val b=Board("part-b","Background",BoardKind.ANIMATION,RectPx(6,0,6,8),fps=24f,
            frames=List(4) { Frame("part-b-$it") },currentFrameId="part-b-0")
        val tiles=linkedMapOf<Triple<String,String,String>,ByteArray>()
        val layers=(if(twoLayers) listOf("bottom","top") else listOf("bottom")).mapIndexed { layerIndex,id ->
            val shared="$id-shared"
            val cels=listOf(Cel(shared))+listOf(a,b).flatMap { board -> board.frames.map { Cel("$id-${it.id}") } }
            for(cel in cels) {
                val rect=if(cel.id==shared) RectPx(14,1+layerIndex*8,1,1) else if(cel.id.contains("part-a")) a.rect else b.rect
                val frameIndex=if(cel.id==shared) 0 else cel.id.substringAfterLast('-').toInt()
                val rgba=ByteArray(TILE_BYTES)
                for(y in rect.y until rect.y+rect.h) for(x in rect.x until rect.x+rect.w) {
                    val p=(y*TILE_SIZE+x)*4
                    rgba[p]=(30+frameIndex*40+layerIndex*15).toByte()
                    rgba[p+1]=(if(cel.id.contains("part-a")) 50 else 200).toByte()
                    rgba[p+2]=(90+layerIndex*120).toByte(); rgba[p+3]=-1
                }
                tiles[Triple(id,cel.id,"0_0")]=rgba
            }
            Layer(id,id,LayerKind.PAINT,cels=cels.map { it.copy(tiles=listOf("0_0")) },sharedCelId=shared,
                regions=listOf(a,b).map { board -> RegionFrames(board.id,board.frames.associate { it.id to "$id-${it.id}" }) })
        }
        return JbContents(JbDocument(id="component-clips-probe",name="Linked component proof",boards=listOf(a,b),layers=layers,
            activeLayerId=layers.last().id,activeBoardId=a.id),tiles,emptyMap())
    }
    /** Small independent CPU executor for the move plan's snapshot/clear/transfer pixel contract. */
    private fun moveContents(source: JbContents,boardId: String,x: Int,y: Int): JbContents {
        val change=AnimationBoardOps.moveAll(source.doc,boardId,x,y)
        val tiles=source.tiles.mapValues { it.value.copyOf() }.toMutableMap()
        fun address(layer: String,cel: String,x: Int,y: Int)=Triple(layer,cel,DocOps.key(Math.floorDiv(x,TILE_SIZE),Math.floorDiv(y,TILE_SIZE)))
        fun offset(x: Int,y: Int)=(Math.floorMod(y,TILE_SIZE)*TILE_SIZE+Math.floorMod(x,TILE_SIZE))*4
        for(clear in change.clears) for(py in clear.rect.y until clear.rect.y+clear.rect.h) for(px in clear.rect.x until clear.rect.x+clear.rect.w) {
            tiles[address(clear.layerId,clear.celId,px,py)]?.let { bytes -> val p=offset(px,py); for(c in 0..3) bytes[p+c]=0 }
        }
        for(transfer in change.transfers) for(dy in 0 until transfer.sourceRect.h) for(dx in 0 until transfer.sourceRect.w) {
            val sx=transfer.sourceRect.x+dx; val sy=transfer.sourceRect.y+dy
            val tx=transfer.destinationRect.x+dx; val ty=transfer.destinationRect.y+dy
            val from=source.tiles[address(transfer.layerId,transfer.fromCelId,sx,sy)]
            val key=address(transfer.layerId,transfer.toCelId,tx,ty)
            if(from==null && key !in tiles) continue
            val target=tiles.getOrPut(key) { ByteArray(TILE_BYTES) }; val out=offset(tx,ty)
            if(from==null) for(c in 0..3) target[out+c]=0 else from.copyInto(target,out,offset(sx,sy),offset(sx,sy)+4)
        }
        return source.copy(doc=change.doc,tiles=tiles)
    }
    private fun previews(doc: JbDocument,tick: Int,fps: Float): Map<String,String> = doc.boards.associate { b ->
        val clock=PlaybackClock(b); val time=tick*1000.0/fps
        b.id to if(clock.isBlank(time)) RegionDocumentOps.BLANK_PREVIEW else b.frames[clock.frameIndexAt(time)].id
    }
    private fun render(contents: JbContents,frames: Map<String,String> = emptyMap(),bounds: RectPx = crop): ByteArray =
        RegionRenderer.renderWithPreviews(contents.doc,TileSource { l,c,x,y -> contents.tiles[Triple(l,c,DocOps.key(x,y))] },
            bounds,null,null,brushLookup=null,frameOverrides=frames)
    private fun visible(engine: GlPaintEngine,layer: String,bounds: RectPx = crop): ByteArray {
        check(engine.tileKeys(layer).size<=4) { "Probe visible tile bound exceeded" }
        val cached=HashMap<Long,ByteArray>()
        return ByteArray(bounds.w*bounds.h*4).also { out ->
            for(y in 0 until bounds.h) for(x in 0 until bounds.w) {
                val wx=bounds.x+x; val wy=bounds.y+y
                val key=Tiles.key(Math.floorDiv(wx,TILE_SIZE),Math.floorDiv(wy,TILE_SIZE))
                val tile=cached.getOrPut(key) { engine.readTile(layer,key) ?: ByteArray(TILE_BYTES) }
                val from=(Math.floorMod(wy,TILE_SIZE)*TILE_SIZE+Math.floorMod(wx,TILE_SIZE))*4
                tile.copyInto(out,(y*bounds.w+x)*4,from,from+4)
            }
        }
    }
    private fun equalPixels(expected: ByteArray,actual: ByteArray,label: String) {
        check(expected.contentEquals(actual)) {
            val first=expected.indices.firstOrNull { expected[it] != actual[it] }
            "$label differs at RGBA byte $first (${first?.let { expected[it].toInt() and 255 }} vs ${first?.let { actual[it].toInt() and 255 }})"
        }
    }
    private fun snapshot(engine: GlPaintEngine): JbContents {
        val tiles=linkedMapOf<Triple<String,String,String>,ByteArray>()
        val doc=checkNotNull(engine.boardDocument)
        val layers=doc.layers.map { layer -> layer.copy(cels=layer.cels.map { cel ->
            val keys=engine.celTileKeys(layer.id,cel.id)
            check(keys.size<=4) { "Probe physical tile bound exceeded" }
            val names=keys.map { key ->
                val name=DocOps.key(Tiles.tx(key),Tiles.ty(key))
                tiles[Triple(layer.id,cel.id,name)]=checkNotNull(engine.readCelTile(layer.id,cel.id,key))
                name
            }.sorted()
            cel.copy(tiles=names)
        }) }
        return JbContents(doc.copy(layers=layers),tiles,emptyMap())
    }
    private fun comparePhysical(expected: JbContents,actual: JbContents) {
        check(expected.tiles.keys==actual.tiles.keys) { "Physical tile addresses changed" }
        expected.tiles.forEach { (key,bytes) -> equalPixels(bytes,actual.tiles.getValue(key),"physical $key") }
    }
    private fun translated(source: JbContents,dx: Int,dy: Int): JbContents {
        val change=AnimationBoardOps.moveAll(source.doc,"background",dx,dy)
        val board=source.doc.boards.first { it.id=="background" }
        val cels=source.doc.layers.first { it.id=="bottom" }.regions.single().frameCel.values.toSet()
        val tiles=source.tiles.mapValues { (key,bytes) ->
            if(key.first!="bottom" || key.second !in cels) bytes else bytes.copyOf().also { out ->
                for(y in board.rect.y until board.rect.y+board.rect.h) for(x in board.rect.x until board.rect.x+board.rect.w)
                    for(c in 0..3) out[(y*TILE_SIZE+x)*4+c]=0
                for(y in board.rect.y until board.rect.y+board.rect.h) for(x in board.rect.x until board.rect.x+board.rect.w)
                    bytes.copyInto(out,((y+dy)*TILE_SIZE+x+dx)*4,(y*TILE_SIZE+x)*4,(y*TILE_SIZE+x)*4+4)
            }
        }
        return source.copy(doc=change.doc,tiles=tiles)
    }
}
