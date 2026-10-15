package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.io.ColdRecordedLoadPlan
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.core.media.HalfFloat
import cc.joycreator.joybrush.core.paint.Tiles
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Consumes ONLY the host's disposable diagnostic engine. No production Open or owner file I/O. */
internal object RecordedColdPayloadProbe {
    fun run(engine: GlPaintEngine, input: JbContents): JSONObject {
        val report = JSONObject().put("probe","recordedColdPayloads").put("passed",false)
            .put("callerTexturesReleasedByProbe",false)
        val rows = JSONArray()
        val framebuffers = IntArray(3) // hostile draw/read, then probe readback
        val texture = IntArray(1)
        val buffer = IntArray(1)
        var context: () -> Boolean = { false }
        var contextStarted = false
        var inspection: ColdUploadedOwners? = null
        var entry: List<Int>? = null
        var retired = false
        var cleanup = false
        var failure: String? = null
        var phase = "fixture"
        var operations = 0; var errorChecks = 0; var errors = 0
        var reads = 0; var framebufferChecks = 0; var filterChecks = 0
        var comparedBytes = 0L
        val pixelNames = intArrayOf(GLES30.GL_UNPACK_ALIGNMENT,GLES30.GL_UNPACK_ROW_LENGTH,
            GLES30.GL_UNPACK_SKIP_ROWS,GLES30.GL_UNPACK_SKIP_PIXELS,GLES30.GL_UNPACK_IMAGE_HEIGHT,
            GLES30.GL_UNPACK_SKIP_IMAGES,GLES30.GL_PACK_ALIGNMENT,GLES30.GL_PACK_ROW_LENGTH,
            GLES30.GL_PACK_SKIP_ROWS,GLES30.GL_PACK_SKIP_PIXELS)
        fun current(): Boolean = !retired && try { context() && (inspection?.current?.invoke() ?: true) }
            catch (_: Exception) { retired=true; false }
        fun <T> gl(label: String, action: () -> T): T {
            check(current()) { retired=true; "$label context retired before GL" }
            operations++
            val value=action()
            check(current()) { retired=true; "$label context retired after GL" }
            return value
        }
        fun get(name: Int): Int = IntArray(1).also { v -> gl("query state") { GLES30.glGetIntegerv(name,v,0) } }[0]
        fun checked(label: String) {
            errorChecks++
            var code=gl("error query") { GLES30.glGetError() }
            while(code != GLES30.GL_NO_ERROR) {
                errors++
                code=gl("error drain") { GLES30.glGetError() }
            }
            check(errors == 0) { "$label observed GL error" }
        }
        // Pixel-store values, PBOs, independent FBO bindings, active sampler and unit0/unit3 names.
        fun state(): List<Int> {
            val values=pixelNames.map(::get).toMutableList()
            values += get(GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING)
            values += get(GLES30.GL_PIXEL_PACK_BUFFER_BINDING)
            values += get(GLES30.GL_DRAW_FRAMEBUFFER_BINDING)
            values += get(GLES30.GL_READ_FRAMEBUFFER_BINDING)
            val active=get(GLES30.GL_ACTIVE_TEXTURE); values += active
            for(slot in listOf(GLES30.GL_TEXTURE0,GLES30.GL_TEXTURE3)) {
                gl("select captured sampler") { GLES30.glActiveTexture(slot) }
                values += get(GLES30.GL_TEXTURE_BINDING_2D)
            }
            gl("restore queried sampler") { GLES30.glActiveTexture(active) }
            return values
        }
        fun restore(values: List<Int>) {
            for(i in pixelNames.indices) gl("restore pixel state") { GLES30.glPixelStorei(pixelNames[i],values[i]) }
            gl("restore unpack PBO") { GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,values[10]) }
            gl("restore pack PBO") { GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER,values[11]) }
            gl("restore draw FBO") { GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,values[12]) }
            gl("restore read FBO") { GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER,values[13]) }
            gl("restore sampler zero") { GLES30.glActiveTexture(GLES30.GL_TEXTURE0) }
            gl("restore texture zero") { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,values[15]) }
            gl("restore sampler three") { GLES30.glActiveTexture(GLES30.GL_TEXTURE3) }
            gl("restore texture three") { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,values[16]) }
            gl("restore active sampler") { GLES30.glActiveTexture(values[14]) }
        }
        fun read(name: Int, floating: Boolean): ByteArray {
            gl("unbind pack PBO") { GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER,0) }
            for((key,value) in listOf(GLES30.GL_PACK_ALIGNMENT to 1,GLES30.GL_PACK_ROW_LENGTH to 0,
                GLES30.GL_PACK_SKIP_ROWS to 0,GLES30.GL_PACK_SKIP_PIXELS to 0)) {
                gl("tight readback") { GLES30.glPixelStorei(key,value) }
            }
            gl("bind readback FBO") { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,framebuffers[2]) }
            gl("attach borrowed texture") { GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,name,0) }
            framebufferChecks++
            check(gl("readback completeness") { GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) } == GLES30.GL_FRAMEBUFFER_COMPLETE)
            gl("read attachment") { GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0) }
            val values=Tiles.SIZE*Tiles.SIZE*4
            val bytes=ByteBuffer.allocateDirect(values*(if(floating) 4 else 1)).order(ByteOrder.nativeOrder())
            reads++
            gl("read pixels") { GLES30.glReadPixels(0,0,Tiles.SIZE,Tiles.SIZE,GLES30.GL_RGBA,
                if(floating) GLES30.GL_FLOAT else GLES30.GL_UNSIGNED_BYTE,bytes) }
            checked("read pixels"); bytes.position(0)
            return if(floating) HalfFloat.encode(FloatArray(values).also { bytes.asFloatBuffer().get(it) })
                else ByteArray(values).also { bytes.get(it) }
        }
        try {
            val fixture=ColdPayloadProbeFixture.enrich(input)
            val archive=ByteArrayOutputStream().also { JbArchive.write(it,fixture.contentsCopy()) }.toByteArray()
            val digest=MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it.toInt() and 255) }
            val loaded=JbArchive.read(ByteArrayInputStream(archive))
            val plan=ColdRecordedLoadPlan.prepare(loaded)
            engine.resetDocument()
            context=engine.coldUploadProbeContext()
            contextStarted=true
            entry=state(); checked("entry")
            phase="hostile unpack setup"
            gl("generate owned FBOs") { GLES30.glGenFramebuffers(framebuffers.size,framebuffers,0) }
            gl("generate owned sampler") { GLES30.glGenTextures(1,texture,0) }
            gl("generate owned PBO") { GLES30.glGenBuffers(1,buffer,0) }
            check(framebuffers.all { it != 0 } && framebuffers.distinct().size == framebuffers.size && texture[0] != 0 && buffer[0] != 0)
            check(texture[0] !in entry!!.takeLast(2) && framebuffers.none { it == entry!![12] || it == entry!![13] })
            gl("select unit zero") { GLES30.glActiveTexture(GLES30.GL_TEXTURE0) }
            gl("bind owned sampler") { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture[0]) }
            gl("unbind unpack for sampler") { GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,0) }
            gl("allocate sampler") { GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA8,1,1,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null) }
            gl("select unit three") { GLES30.glActiveTexture(GLES30.GL_TEXTURE3) }
            gl("bind third sampler") { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture[0]) }
            gl("bind hostile unpack PBO") { GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,buffer[0]) }
            gl("allocate hostile PBO") { GLES30.glBufferData(GLES30.GL_PIXEL_UNPACK_BUFFER,64,null,GLES30.GL_STATIC_DRAW) }
            for((name,value) in listOf(GLES30.GL_UNPACK_ALIGNMENT to 8,GLES30.GL_UNPACK_ROW_LENGTH to Tiles.SIZE+7,
                GLES30.GL_UNPACK_SKIP_ROWS to 2,GLES30.GL_UNPACK_SKIP_PIXELS to 3,
                GLES30.GL_UNPACK_IMAGE_HEIGHT to Tiles.SIZE+13,GLES30.GL_UNPACK_SKIP_IMAGES to 1)) {
                gl("set hostile unpack") { GLES30.glPixelStorei(name,value) }
            }
            gl("hostile draw FBO") { GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,framebuffers[0]) }
            gl("hostile read FBO") { GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER,framebuffers[1]) }
            val hostile=state(); checked("hostile setup")
            val preparations=engine.recordedLinePreparationCalls; val compositions=engine.recordedCompositionCalls
            phase="cold upload"
            check(current())
            val receipt=engine.loadFreshRecordedArtwork(plan)
            check(current())
            check(state() == hostile) { "Cold loader changed caller unpack/PBO/FBO/sampler state" }
            checked("restored hostile state")
            val owners=engine.inspectColdUploadedOwners(); inspection=owners
            val actual=owners.entries.associateBy { it.address }
            val expected=plan.uploads.associateBy { u -> ColdUploadedOwners.Address(u.kind,u.layerId,u.address?.celId,u.key,u.store,u.seq,u.op) }
            check(actual.keys == expected.keys && actual.size == owners.entries.size)
            check(owners.entries.map { it.texture }.distinct().size == actual.size)
            check(owners.unresolvedLinePlanes == 1 && !engine.undo.canUndo && !engine.undo.canRedo)
            phase="actual owner readbacks"
            for((address,owner) in actual) {
                val floating=address.kind == ColdRecordedLoadPlan.Kind.MEDIA_RGBA16F
                check(owner.texture != 0 && !owners.isForbidden(owner.texture) && owners.floatRegistered(owner.texture) == floating)
                check(gl("actual texture existence") { GLES30.glIsTexture(owner.texture) })
                gl("select owner sampler") { GLES30.glActiveTexture(GLES30.GL_TEXTURE0) }
                gl("bind owner sampler") { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,owner.texture) }
                for(parameter in listOf(GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_TEXTURE_MAG_FILTER)) {
                    val value=IntArray(1)
                    gl("query owner filter") { GLES30.glGetTexParameteriv(GLES30.GL_TEXTURE_2D,parameter,value,0) }
                    filterChecks++; check(value[0] == if(floating) GLES30.GL_NEAREST else GLES30.GL_LINEAR)
                }
                gl("detach owner sampler") { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0) }
                val wanted=plan.copyPayload(expected.getValue(address).id)
                val observed=read(owner.texture,floating)
                check(wanted.contentEquals(observed)) { "Cold upload bytes differ at $address" }
                comparedBytes += wanted.size
                rows.put(JSONObject().put("kind",address.kind.name).put("layer",address.layerId)
                    .put("cel",address.engineCelId ?: JSONObject.NULL).put("key",address.key)
                    .put("seq",address.seq ?: JSONObject.NULL).put("store",address.store ?: JSONObject.NULL)
                    .put("bytes",wanted.size).put("differentBytes",0).put("floatingReadback",floating))
            }
            check(current() && engine.recordedLinePreparationCalls == preparations && engine.recordedCompositionCalls == compositions)
            check(!engine.undo.canUndo && !engine.undo.canRedo && receipt.linePreparationCalls == 0L && receipt.compositionCalls == 0L)
            report.put("archiveSha256",digest).put("archiveBytes",archive.size).put("exactOwnerManifest",true)
                .put("hostileUnpackStateRestored",true).put("openingLineCalls",0).put("openingCompositionCalls",0)
                .put("nativeTiles",receipt.nativeTiles).put("maskTiles",receipt.maskTiles)
                .put("mediaTiles",receipt.mediaTiles).put("sourceSlabs",receipt.sourceSlabs).put("savedLooks",receipt.savedLooks)
                .put("unresolvedLinePlanes",owners.unresolvedLinePlanes).put("ownerNamesDistinct",true).put("historyEmpty",true)
            checked("completed comparisons")
            report.put("passed",true)
        } catch(error: Exception) { failure="$phase ${error.javaClass.simpleName}: ${error.message}" }
        finally {
            if(!contextStarted) cleanup=true
            else if(current()) try {
                entry?.let(::restore)
                for(i in framebuffers.indices) if(framebuffers[i] != 0 &&
                    entry?.let { framebuffers[i] != it[12] && framebuffers[i] != it[13] } != false) {
                    gl("delete owned FBO") { GLES30.glDeleteFramebuffers(1,framebuffers,i) }
                }
                if(texture[0] != 0 && entry?.takeLast(2)?.contains(texture[0]) != true)
                    gl("delete owned sampler") { GLES30.glDeleteTextures(1,texture,0) }
                if(buffer[0] != 0 && entry?.let { buffer[0] != it[10] && buffer[0] != it[11] } != false)
                    gl("delete owned PBO") { GLES30.glDeleteBuffers(1,buffer,0) }
                checked("probe cleanup"); cleanup=true
            } catch(error: Exception) { failure=listOfNotNull(failure,"cleanup ${error.message}").joinToString("; ") }
            else { retired=true; failure=listOfNotNull(failure,"Context retired; old probe names abandoned").joinToString("; ") }
            report.put("cleanupComplete",cleanup).put("contextRetired",retired).put("failure",failure ?: JSONObject.NULL)
                .put("payloadRows",rows).put("payloadComparisons",rows.length()).put("comparedBytes",comparedBytes)
                .put("externalReadbacks",reads).put("externalFramebufferChecks",framebufferChecks)
                .put("actualFilterChecks",filterChecks).put("probeGlOperations",operations)
                .put("probeGlErrorChecks",errorChecks).put("observedGlErrors",errors)
                .put("scope","Private cold upload/readback only; no load rollback fault, live/Select/Working/recovery activation")
            if(failure != null || !cleanup) report.put("passed",false)
        }
        return report
    }
}
