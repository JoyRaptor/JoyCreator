package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.androidkit.io.ColdRecordedLoadPlan
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.render.Blend
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedEdits
import java.nio.ByteBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Consumes ONLY a fresh unsaved diagnostic engine. No live View, save/recovery or quality claim. */
internal object RecordedPromotionProbe {
    fun run(engine: GlPaintEngine, owner: String, preset: BrushPreset,
            input: StrokeRecord, regional: Boolean, coldLoad: Boolean = false): JSONObject {
        val n = Tiles.SIZE; val length = n * n * 4; val empty = ByteArray(length)
        val at: Pair<String, String?> = owner to null
        val old = input.copy(samples = input.samples.toList(), seq = 1)
        val moved = old.copy(samples = old.samples.map { it.copy(x = it.x + n * 2, y = it.y + n) })
        val nativeId = "promotion-native-sibling"; val nativeKey = Tiles.key(8, 8)
        var readFbo = 0; var phase = "entry"; var failure: String? = null; var cleanup = false
        val rows = JSONArray(); val units = ArrayList<GlPaintEngine.RecordedLineUnit>()
        val raw = ArrayList<Map<Long, ByteArray>>()
        var reads = 0; var glChecks = 0; var errors = 0; var fbos = 0; var badFbos = 0
        var feedbacks = 0; var aliases = 0; var comparisons = 0; var changedChannels = 0L
        var sourceChecks = 0; var rootChecks = 0; var historyChecks = 0; var openingLines = -1L; var openingLooks = -1L
        var lineAlphaPixels = 0L; var planeCount = 0; var emptyPlanes = 0
        var wrongOrderChannels = 0L; var badPremultiplied = 0L
        var archiveRoundtrips = 0; var archiveComparisons = 0; val archives = JSONArray()
        val coldProof = JSONObject().put("requested", coldLoad).put("completed", false)
        fun gl(label: String) {
            glChecks++; var code = GLES30.glGetError(); var first = 0
            while (code != GLES30.GL_NO_ERROR) { errors++; if (first == 0) first = code; code = GLES30.glGetError() }
            check(first == 0) { "$label GL $first" }
        }
        fun own(ok: Boolean, label: String) { feedbacks++; if (!ok) aliases++; check(ok) { "$label ownership" } }
        fun read(texture: Int): ByteArray {
            own(texture != 0 && GLES30.glIsTexture(texture), "read live texture")
            for (slot in 0..1) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + slot); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0) }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
            fbos++; val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) badFbos++
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "read framebuffer $status" }
            GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0); GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1); GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0); GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0)
            val b = ByteBuffer.allocateDirect(length); reads++
            GLES30.glReadPixels(0, 0, n, n, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b); gl("external read")
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, 0, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); b.position(0)
            return ByteArray(length).also { b.get(it) }
        }
        fun compare(expected: ByteArray, actual: ByteArray?, label: String, key: Long) {
            val value = actual ?: empty; var different = 0; var pixels = 0; var delta = 0
            require(value.size == length)
            for (p in 0 until length step 4) {
                var changed = false
                for (c in 0..3) {
                    val d = kotlin.math.abs((expected[p+c].toInt() and 255) - (value[p+c].toInt() and 255))
                    if (d != 0) { different++; changed = true }; delta = maxOf(delta, d)
                }
                if (changed) pixels++
            }
            comparisons++; rows.put(JSONObject().put("phase", label).put("key", key.toString())
                .put("differentChannels", different).put("differentPixels", pixels).put("maxByteDelta", delta))
            check(different == 0) { "$label/$key differs $different channels" }
        }
        fun swatch(r: Int, g: Int, b: Int, a: Int) = ByteArray(length).also {
            for (p in it.indices step 4) { it[p]=r.toByte(); it[p+1]=g.toByte(); it[p+2]=b.toByte(); it[p+3]=a.toByte() }
        }
        fun over(source: ByteArray, base: ByteArray): ByteArray {
            val out = ByteArray(length); val s = FloatArray(4); val d = FloatArray(4); val o = FloatArray(4)
            for (p in out.indices step 4) {
                for (c in 0..3) { s[c]=(source[p+c].toInt() and 255)/255f; d[c]=(base[p+c].toInt() and 255)/255f }
                Blend.apply(BlendMode.NORMAL, s, d, o)
                for (c in 0..3) out[p+c]=(o[c].coerceIn(0f,1f)*255f).roundToInt().toByte()
            }
            return out
        }
        fun field(obj: Any, name: String): Any? = obj.javaClass.getDeclaredField(name).also { it.isAccessible=true }.get(obj)
        fun root(): Any = checkNotNull(field(engine, "publishedPaint"))
        @Suppress("UNCHECKED_CAST")
        fun scene(): RecordedScene<Int> = field(root(), "coordinator") as RecordedScene<Int>
        val before = swatch(17,35,49,97); val after = swatch(43,97,113,167); val sibling = swatch(7,13,19,31)
        try {
            gl("entry"); check(engine.ready && preset.blend == "normal" && input.brushId == preset.id)
            require(!coldLoad || !regional) { "Regional cold lines need persisted captured physical clipping" }
            check(engine.tileKeys(owner).isEmpty() && !engine.undo.canUndo && !engine.undo.canRedo)
            var ids = 0
            var doc = DocOps.newDocument("promotion-doc", "Throwaway", n*8, n*8) {
                when (++ids) { 1 -> "promotion-board"; 2 -> owner; else -> "promotion-shared" }
            }
            if (regional) {
                var serial = 0
                doc = RegionDocumentOps.create(doc,"Frame",RectPx(0,0,n,n)) { "region-${++serial}" }.doc
                val board = doc.boards.single { it.kind == BoardKind.ANIMATION }
                doc = RegionDocumentOps.addFrame(doc,board.id,NewFrame.BLANK) { "region-${++serial}" }.doc
            }
            doc=doc.copy(layers=doc.layers+Layer(nativeId,"Native sibling",LayerKind.PAINT,
                cels=listOf(Cel("promotion-native-cel"))))
            engine.addLayer(nativeId); engine.setBoardDocument(doc); engine.writeTile(nativeId,nativeKey,sibling)
            val names=IntArray(1); GLES30.glGenFramebuffers(1,names,0); readFbo=names[0]; check(readFbo!=0)
            phase="independent raw preparation"
            for (record in listOf(old,moved)) {
                val unit=engine.prepareRecordedLine(owner,record,preset); units.add(unit)
                check(unit.op=="normal" && unit.tiles.isNotEmpty() && unit.tiles.size<=16)
                raw.add(unit.tiles.mapValues { read(it.value) })
            }
            val allKeys=raw.flatMap { it.keys }.toSet(); check(allKeys.size<=16 && raw[0].keys!=raw[1].keys)
            lineAlphaPixels=raw.sumOf { it.values.sumOf { bytes -> (3 until length step 4).count { p -> (bytes[p].toInt() and 255)>0 }.toLong() } }
            badPremultiplied=raw.sumOf { it.values.sumOf { bytes -> (0 until length step 4).count { p ->
                (0..2).any { c -> (bytes[p+c].toInt() and 255)>(bytes[p+3].toInt() and 255) } }.toLong() } }
            check(lineAlphaPixels>0 && badPremultiplied==0L)
            phase="ordinary native history setup"
            allKeys.forEach { engine.writeTile(owner,it,before) }
            val originalDisplay=allKeys.associateWith { engine.readTile(owner,it) ?: empty }
            engine.replaceTiles(owner,allKeys.associateWith { after })
            val legacy=checkNotNull(engine.undo.peekUndo()); val cursor=engine.undo.snapshotCursor()
            check(legacy.lines.isEmpty() && legacy.slabs.isEmpty() && legacy.changes.isNotEmpty())
            val currentDisplay=allKeys.associateWith { engine.readTile(owner,it) ?: empty }
            val inventory=engine.inspectPlainPromotionInventory(owner)
            val sourceBytes=inventory.physical.mapValues { (_,p) -> p.store.mapValues { read(it.value) } }
            planeCount=inventory.physical.size; emptyPlanes=inventory.physical.values.count { it.store.isEmpty() }
            if (regional) check(planeCount>=3 && emptyPlanes>=1)
            val starts=engine.recordedLinePreparationCalls to engine.recordedCompositionCalls
            phase="native base transfer"
            val ticket=engine.preparePlainRecordingPromotion(owner); check(ticket.canPublish()); check(ticket.publish())
            openingLines=engine.recordedLinePreparationCalls-starts.first; openingLooks=engine.recordedCompositionCalls-starts.second
            check(openingLines==0L && openingLooks==0L && engine.undo.snapshotCursor()===cursor)
            val current=scene(); check(current.planes.keys==inventory.physical.keys)
            check(current.heldCacheTextures().isEmpty() && current.planes.values.all { it.records.isEmpty() })
            val legacyPlanes=legacy.changes.map { it.layerId to it.celId }.toSet()
            val fixedFrameCels=current.planes.filterKeys { it!=at && it !in legacyPlanes }
            fun checkRoot() { rootChecks++; val r=root(); check(field(r,"recorded")===current.snapshotState())
                check(field(r,"cursor")===engine.undo.snapshotCursor()); check(field(r,"coordinator")===current) }
            fun retainedSources(label:String) {
                for ((plane,tiles) in sourceBytes) for ((key,bytes) in tiles) {
                    sourceChecks++; val tex=current.planes.getValue(plane).slabs.getValue(key).single { it.seq==0L }.value.texture
                    check(tex==inventory.contents.getValue(plane).getValue(key)); compare(bytes,read(tex),"$label-source",key)
                }
                units.forEachIndexed { i,unit -> unit.tiles.forEach { (key,texture) -> compare(raw[i].getValue(key),read(texture),"$label-independent-$i",key) } }
                compare(sibling,engine.readTile(nativeId,nativeKey),"$label-native",nativeKey)
            }
            fun archive(label:String, expected:Map<Long,ByteArray>, record:StrokeRecord?): JbContents {
                phase="$label source snapshot/archive"
                val beforeRoot=root(); val beforeCursor=engine.undo.snapshotCursor()
                val snapshot=engine.snapshotJointRecordedArtwork()
                val expectedTiles=linkedSetOf<Triple<String,String,String>>()
                for((plane,tiles) in sourceBytes) {
                    val meta=doc.layers.single { it.id==plane.first }
                    val cel=plane.second ?: meta.sharedCelId ?: meta.cels.single().id
                    for((key,bytes) in tiles) {
                        val address=Triple(plane.first,cel,DocOps.key(Tiles.tx(key),Tiles.ty(key))); expectedTiles.add(address)
                        compare(bytes,snapshot.tiles.getValue(address),"$label-snapshot-source",key)
                    }
                }
                val nativeAddress=Triple(nativeId,"promotion-native-cel",DocOps.key(Tiles.tx(nativeKey),Tiles.ty(nativeKey)))
                expectedTiles.add(nativeAddress); compare(sibling,snapshot.tiles.getValue(nativeAddress),"$label-snapshot-native",nativeKey)
                check(snapshot.tiles.keys==expectedTiles && snapshot.slabs.isEmpty() && snapshot.mediaTiles.isEmpty())
                val shared=doc.layers.single { it.id==owner }.sharedCelId ?: doc.layers.single { it.id==owner }.cels.single().id
                check(snapshot.strokes==if(record==null) emptyMap() else mapOf((owner to shared) to listOf(record)))
                check(snapshot.looks.keys==current.savedLookKeys(at).map { Triple(owner,shared,DocOps.key(Tiles.tx(it),Tiles.ty(it))) }.toSet())
                for(key in current.savedLookKeys(at)) compare(expected.getValue(key),
                    snapshot.looks.getValue(Triple(owner,shared,DocOps.key(Tiles.tx(key),Tiles.ty(key)))),"$label-snapshot-look",key)
                check(snapshot.brushes==if(record==null) emptyMap() else mapOf(preset.id to preset))
                val output=ByteArrayOutputStream(); JbArchive.write(output,snapshot)
                val loaded=JbArchive.read(ByteArrayInputStream(output.toByteArray()))
                check(loaded.doc==snapshot.doc && loaded.strokes==snapshot.strokes && loaded.brushes==snapshot.brushes)
                fun <K> bytes(expected:Map<K,ByteArray>,actual:Map<K,ByteArray>) {
                    check(expected.keys==actual.keys)
                    expected.forEach { (key,value) -> archiveComparisons++; check(value.contentEquals(actual.getValue(key))) }
                }
                bytes(snapshot.tiles,loaded.tiles); bytes(snapshot.slabs,loaded.slabs)
                bytes(snapshot.looks,loaded.looks); bytes(snapshot.mediaTiles,loaded.mediaTiles)
                check(root()===beforeRoot && engine.undo.snapshotCursor()===beforeCursor)
                archiveRoundtrips++; archives.put(JSONObject().put("phase",label).put("passed",true)
                    .put("archiveBytes",output.size()).put("bottomPixelTiles",loaded.tiles.size)
                    .put("savedLookTiles",loaded.looks.size).put("rawRecords",loaded.strokes.values.sumOf { it.size }))
                gl("$label source snapshot/archive")
                return loaded
            }
            fun checkpoint(label:String, pixels:Map<Long,ByteArray>, record:StrokeRecord?) {
                phase=label; checkRoot(); historyChecks++
                check(current.planes.getValue(at).records==listOfNotNull(record))
                fixedFrameCels.forEach { (plane,cel) -> check(current.planes[plane]===cel) }
                val sourceIndex=when(record) { old -> 0; moved -> 1; else -> -1 }
                val lineKeys=if(sourceIndex<0) emptySet() else raw[sourceIndex].keys.filter { !regional || it!=Tiles.key(0,0) }.toSet()
                check(current.savedLookKeys(at)==lineKeys)
                check(engine.tileKeys(owner).toSet()==pixels.filterValues { it.any { b -> b.toInt()!=0 } }.keys)
                for (key in allKeys) { compare(pixels.getValue(key),engine.readTile(owner,key),"$label-public",key); gl("$label public read") }
                for (i in units.indices) units[i].tiles.forEach { (key,tex) -> compare(raw[i].getValue(key),read(tex),"$label-raw-$i",key) }
                compare(sibling,engine.readTile(nativeId,nativeKey),"$label-native",nativeKey)
                val state=current.snapshotState()
                val sources=state.planes.values.flatMap { cel -> cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } } }
                val caches=state.units.values.flatMap { it.unit.tiles.values }+state.looks.values.flatMap { it.values }
                val native=linkedSetOf<Int>()
                fun nativeOwner(value:Any) {
                    @Suppress("UNCHECKED_CAST")
                    val method=value.javaClass.getDeclaredMethod("ownedTextures").also { it.isAccessible=true }
                    @Suppress("UNCHECKED_CAST")
                    native.addAll(method.invoke(value) as Set<Int>)
                    field(value,"mask")?.let(::nativeOwner)
                }
                @Suppress("UNCHECKED_CAST")
                val owners=field(root(),"nativeLayers") as Map<String,Any>
                owners.values.forEach(::nativeOwner)
                val history=engine.undo.heldTextures(); val independentNames=units.flatMap { it.tiles.values }
                val independent=independentNames.toSet()
                own(sources.distinct().size==sources.size && sources.none { it in native || it in history || it in independent },"$label source families")
                own(caches.distinct().size==caches.size && caches.none { it in sources || it in native || it in history || it in independent },"$label cache families")
                own(RecordedPromotionExternalTextureInventory.check(engine,sources,caches,independentNames).passed,
                    "$label pool/transient/global families")
            }
            checkpoint("promoted",currentDisplay,null); retainedSources("promoted")
            archive("promoted",currentDisplay,null)
            val frameKey=Tiles.key(0,0)
            fun expected(i:Int):Map<Long,ByteArray> = allKeys.associateWith { key ->
                val bytes=raw[i][key]
                if (bytes==null || (regional && key==frameKey)) currentDisplay.getValue(key)
                else over(bytes,currentDisplay.getValue(key))
            }
            val expectedOld=expected(0); val expectedMoved=expected(1)
            wrongOrderChannels=raw[0].entries.filter { !regional || it.key!=frameKey }.sumOf { (key,bytes) ->
                val backwards=over(currentDisplay.getValue(key),bytes)
                backwards.indices.count { backwards[it]!=expectedOld.getValue(key)[it] }.toLong()
            }
            changedChannels=allKeys.sumOf { key -> expectedOld.getValue(key).indices.count {
                expectedOld.getValue(key)[it]!=expectedMoved.getValue(key)[it] }.toLong() }
            check(changedChannels>0 && wrongOrderChannels>0)
            phase="actual first raw add"
            val add=RecordedEdits.add(current.planes,listOf(AddressedStroke(LineAddress(owner,null,old.id),old)),setOf(preset.id)).step
            check(engine.publishJointPaintStep(add,frozen=mapOf(preset.id to preset)))
            check(engine.undo.peekUndo()===add && engine.undo.undoDepth==2)
            checkpoint("raw-add",expectedOld,old); retainedSources("raw-add")
            if(!regional) archive("raw-add",expectedOld,old)
            val edit=RecordedEdits.replace(current.planes,listOf(AddressedStroke(LineAddress(owner,null,old.id),moved)),setOf(preset.id)).step
            check(engine.publishJointPaintStep(edit)); check(engine.undo.peekUndo()===edit)
            checkpoint("raw-move",expectedMoved,moved)
            check(engine.undoStep()); check(engine.undo.peekRedo()===edit); checkpoint("undo-move",expectedOld,old)
            check(engine.undoStep()); check(engine.undo.peekRedo()===add); checkpoint("undo-add",currentDisplay,null)
            check(engine.undoStep()); check(engine.undo.peekRedo()===legacy); checkpoint("undo-legacy",originalDisplay,null)
            check(engine.redoStep()); check(engine.undo.peekUndo()===legacy); checkpoint("redo-legacy",currentDisplay,null)
            check(engine.redoStep()); check(engine.undo.peekUndo()===add); checkpoint("redo-add",expectedOld,old)
            check(engine.redoStep()); check(engine.undo.peekUndo()===edit); checkpoint("redo-move",expectedMoved,moved)
            retainedSources("final"); gl("final")
            val finalArchive = if(!regional) archive("final",expectedMoved,moved) else null
            check(archiveRoundtrips==if(regional) 1 else 3)
            if(coldLoad) {
                phase="owned cold source plan"
                val loaded = checkNotNull(finalArchive)
                val plan = ColdRecordedLoadPlan.prepare(loaded)
                check(plan.summary.recordCount == 1L && !plan.summary.dynamicReplayValidated)
                engine.resetDocument()
                check(!engine.hasJointPaintPublication && engine.layerIds().isEmpty())
                val counts = engine.recordedLinePreparationCalls to engine.recordedCompositionCalls
                phase="fresh cold root load"
                val receipt = engine.loadFreshRecordedArtwork(plan)
                val cold = scene()
                check(engine.recordedLinePreparationCalls == counts.first && engine.recordedCompositionCalls == counts.second)
                check(receipt.linePreparationCalls == 0L && receipt.compositionCalls == 0L)
                check(receipt.recordedPlanes == 1 && receipt.unresolvedLinePlanes == 1)
                check(!cold.isMaterialized(at) && !engine.undo.canUndo && !engine.undo.canRedo)
                check(cold.planes.getValue(at).records == listOf(moved))
                var filterChecks = 0
                fun coldCheckpoint(label: String, pixels: Map<Long,ByteArray>) {
                    phase=label
                    val state=cold.snapshotState()
                    check(field(root(),"recorded") === state && field(root(),"cursor") === engine.undo.snapshotCursor())
                    for(key in allKeys) compare(pixels.getValue(key),engine.readTile(owner,key),"$label-public",key)
                    compare(sibling,engine.readTile(nativeId,nativeKey),"$label-native",nativeKey)
                    val sources=state.planes.values.flatMap { cel -> cel.slabs.values.flatMap { stack -> stack.map { it.value.texture } } }
                    val caches=state.units.values.flatMap { it.unit.tiles.values } + state.looks.values.flatMap { it.values }
                    if(label == "cold-open") {
                        // Actual allocation parameters, since identical tile bytes alone miss zoom/pool regressions.
                        val nativeTexture=checkNotNull(engine.mediaTile(nativeId,MEDIA_LOOK,nativeKey))
                        for(texture in sources + caches + nativeTexture) {
                            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture)
                            for(parameter in listOf(GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_TEXTURE_MAG_FILTER)) {
                                val actual=IntArray(1); GLES30.glGetTexParameteriv(GLES30.GL_TEXTURE_2D,parameter,actual,0)
                                filterChecks++; check(actual[0] == GLES30.GL_LINEAR)
                            }
                        }
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0)
                    }
                    own(RecordedPromotionExternalTextureInventory.check(engine,sources,caches,units.flatMap { it.tiles.values }).passed,
                        "$label cold source/cache/native/pool/global families")
                    val physical=cold.planes.getValue(at)
                    for(upload in plan.recordedPlanes.single().slabs) {
                        val texture=physical.slabs.getValue(upload.key).single { it.seq == upload.seq }.value.texture
                        compare(plan.copyPayload(upload.id),read(texture),"$label-source",upload.key)
                    }
                    gl(label)
                }
                coldCheckpoint("cold-open",expectedMoved)
                check(!cold.isMaterialized(at)) // Display reads must not secretly warm replay.
                val coldEdit=RecordedEdits.replace(cold.planes,
                    listOf(AddressedStroke(LineAddress(owner,null,old.id),old)),setOf(preset.id)).step
                check(engine.publishJointPaintStep(coldEdit))
                val firstEditLines=engine.recordedLinePreparationCalls-counts.first
                val firstEditLooks=engine.recordedCompositionCalls-counts.second
                check(firstEditLines > 0L && firstEditLooks > 0L)
                check(cold.isMaterialized(at) && engine.undo.peekUndo() === coldEdit && engine.undo.undoDepth == 1)
                coldCheckpoint("cold-first-edit",expectedOld)
                check(engine.undoStep() && !engine.undo.canUndo && engine.undo.peekRedo() === coldEdit)
                coldCheckpoint("cold-undo",expectedMoved)
                check(engine.redoStep() && engine.undo.peekUndo() === coldEdit)
                coldCheckpoint("cold-redo",expectedOld)
                phase="cold edit source snapshot/archive"
                val snapshot=engine.snapshotJointRecordedArtwork()
                val output=ByteArrayOutputStream(); JbArchive.write(output,snapshot)
                val back=JbArchive.read(ByteArrayInputStream(output.toByteArray()))
                check(snapshot.doc == back.doc && snapshot.strokes == back.strokes && snapshot.brushes == back.brushes)
                fun <K> exact(a:Map<K,ByteArray>,b:Map<K,ByteArray>) {
                    check(a.keys == b.keys)
                    a.forEach { (key,bytes) -> archiveComparisons++; check(bytes.contentEquals(b.getValue(key))) }
                }
                exact(snapshot.tiles,back.tiles); exact(snapshot.slabs,back.slabs)
                exact(snapshot.looks,back.looks); exact(snapshot.mediaTiles,back.mediaTiles)
                archiveRoundtrips++
                coldProof.put("completed",true).put("openingLinePreparationCalls",receipt.linePreparationCalls)
                    .put("openingCompositionCalls",receipt.compositionCalls).put("sourceSlabs",receipt.sourceSlabs)
                    .put("savedLooks",receipt.savedLooks).put("nativeTiles",receipt.nativeTiles)
                    .put("unresolvedLinePlanesAtOpen",receipt.unresolvedLinePlanes)
                    .put("firstEditLinePreparationCalls",firstEditLines)
                    .put("firstEditCompositionCalls",firstEditLooks).put("actualFilterChecks",filterChecks)
                    .put("coldHistoryUndoRedoPassed",true).put("postEditArchivePassed",true)
            }
        } catch (e:Exception) { failure="$phase ${e.javaClass.simpleName}: ${e.message}" }
        finally {
            try {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
                for (slot in 0..1) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0+slot); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0) }
                if(readFbo!=0) GLES30.glDeleteFramebuffers(1,intArrayOf(readFbo),0)
                units.forEach(engine::discardRecordedLine)
                engine.resetDocument(); check(engine.layerIds().isEmpty() && !engine.undo.canUndo && !engine.undo.canRedo)
                gl("reset cleanup"); cleanup=true
            } catch(e:Exception) { failure=listOfNotNull(failure,"cleanup ${e.message}").joinToString("; ") }
        }
        return JSONObject().put("probe","recordedPromotion").put("schemaVersion",1).put("regional",regional)
            .put("coldLoad",coldProof)
            .put("proofScope","Actual native base transfer, physical display, raw/legacy history and root-source in-memory archive roundtrip; no live capture, Select, Working/recovery, performance or quality claim")
            .put("passed",failure==null && cleanup && errors==0 && badFbos==0 && aliases==0)
            .put("failure",failure?:JSONObject.NULL).put("phase",phase).put("cleanupComplete",cleanup)
            .put("comparisonCount",comparisons).put("comparisons",rows).put("maxComparisonRows",1024)
            .put("openingLineCalls",openingLines).put("openingCompositionCalls",openingLooks)
            .put("sourceNonzeroAlphaPixels",lineAlphaPixels).put("changedReferenceChannels",changedChannels)
            .put("sourceInvalidPremultipliedPixels",badPremultiplied).put("wrongOrderDifferentChannels",wrongOrderChannels)
            .put("archiveRoundtripsCompleted",archiveRoundtrips).put("archiveByteComparisons",archiveComparisons)
            .put("archives",archives).put("regionalLineArchiveSkipped",regional)
            .put("physicalPlanes",planeCount).put("emptyPhysicalPlanes",emptyPlanes).put("physicalSourceChecks",sourceChecks)
            .put("rootChecks",rootChecks).put("historyChecks",historyChecks).put("readbackCalls",reads)
            .put("glErrorChecks",glChecks).put("observedGlErrors",errors).put("framebufferChecks",fbos)
            .put("incompleteFramebuffers",badFbos).put("feedbackChecks",feedbacks).put("feedbackFailures",aliases)
    }
}
