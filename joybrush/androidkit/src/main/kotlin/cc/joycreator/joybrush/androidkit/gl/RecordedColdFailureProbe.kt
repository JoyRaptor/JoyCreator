package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.androidkit.io.ColdRecordedLoadPlan
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.RectPx
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Exercises controlled cold-load rollback only on the supplied disposable diagnostic engine. */
internal object RecordedColdFailureProbe {
    fun run(engine: GlPaintEngine, input: JbContents): JSONObject {
        val report = JSONObject().put("probe", "recordedColdUploadFailures").put("passed", false)
        val rows = JSONArray()
        val texture = IntArray(1)
        val buffer = IntArray(1)
        val framebuffers = IntArray(2)
        var alive: () -> Boolean = { false }
        var contextStarted = false
        var entry: List<Int>? = null
        var hostile: List<Int>? = null
        var protectedTextures: Set<Int> = emptySet()
        var cleanupComplete = false
        var retired = false
        var failure: String? = null
        var archiveBytes = 0
        var archiveSha256: String? = null
        var manifestCount = 0
        var hostileStateChecks = 0
        var cleanupGlChecks = 0
        var cleanupErrors = 0
        var retirementFailureSeen = false
        var paperSentinelDifferent = false

        val unpackNames = intArrayOf(
            GLES30.GL_UNPACK_ALIGNMENT, GLES30.GL_UNPACK_ROW_LENGTH,
            GLES30.GL_UNPACK_SKIP_ROWS, GLES30.GL_UNPACK_SKIP_PIXELS,
            GLES30.GL_UNPACK_IMAGE_HEIGHT, GLES30.GL_UNPACK_SKIP_IMAGES,
            GLES30.GL_PACK_ALIGNMENT, GLES30.GL_PACK_ROW_LENGTH,
            GLES30.GL_PACK_SKIP_ROWS, GLES30.GL_PACK_SKIP_PIXELS,
        )

        fun current(): Boolean = !retired && try { alive() } catch (_: Exception) { retired = true; false }
        fun <T> gl(label: String, action: () -> T): T {
            check(current()) { "$label: diagnostic context retired" }
            val value = action()
            check(current()) { "$label: diagnostic context retired during GL operation" }
            return value
        }
        fun get(name: Int): Int = IntArray(1).also { values ->
            gl("query GL state") { GLES30.glGetIntegerv(name, values, 0) }
        }[0]

        // [unpack/pack stores, unpack/pack PBO, draw/read FBO, active unit, unit0/unit3 bindings]
        fun state(): List<Int> {
            val values = unpackNames.map(::get).toMutableList()
            values += get(GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING)
            values += get(GLES30.GL_PIXEL_PACK_BUFFER_BINDING)
            values += get(GLES30.GL_DRAW_FRAMEBUFFER_BINDING)
            values += get(GLES30.GL_READ_FRAMEBUFFER_BINDING)
            val active = get(GLES30.GL_ACTIVE_TEXTURE)
            values += active
            for (unit in intArrayOf(GLES30.GL_TEXTURE0, GLES30.GL_TEXTURE3)) {
                gl("select sampler for state query") { GLES30.glActiveTexture(unit) }
                values += get(GLES30.GL_TEXTURE_BINDING_2D)
            }
            gl("restore active unit after state query") { GLES30.glActiveTexture(active) }
            return values
        }

        fun restore(values: List<Int>) {
            for (index in unpackNames.indices) {
                gl("restore unpack store") { GLES30.glPixelStorei(unpackNames[index], values[index]) }
            }
            gl("restore unpack PBO") { GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, values[10]) }
            gl("restore pack PBO") { GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, values[11]) }
            gl("restore draw FBO") { GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, values[12]) }
            gl("restore read FBO") { GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, values[13]) }
            gl("select unit zero for restore") { GLES30.glActiveTexture(GLES30.GL_TEXTURE0) }
            gl("restore unit zero texture") { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, values[15]) }
            gl("select unit three for restore") { GLES30.glActiveTexture(GLES30.GL_TEXTURE3) }
            gl("restore unit three texture") { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, values[16]) }
            gl("restore active unit") { GLES30.glActiveTexture(values[14]) }
        }

        fun checked(label: String) {
            cleanupGlChecks++
            val error = gl("check GL error") { GLES30.glGetError() }
            if (error != GLES30.GL_NO_ERROR) cleanupErrors++
            check(error == GLES30.GL_NO_ERROR) { "$label GL error 0x${error.toString(16)}" }
        }

        try {
            val fixture = ColdPayloadProbeFixture.enrich(input)
            val archive = ByteArrayOutputStream().also { JbArchive.write(it, fixture.contentsCopy()) }.toByteArray()
            archiveBytes = archive.size
            archiveSha256 = MessageDigest.getInstance("SHA-256").digest(archive)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val loaded = JbArchive.read(ByteArrayInputStream(archive))
            val plan = ColdRecordedLoadPlan.prepare(loaded)
            val targets = ColdUploadFailureMatrix.targets(plan)
            manifestCount = plan.uploads.size
            check(targets.size == 21 && targets.toSet().size == 21)

            val targetPaperPixels = engine.renderPaper(RectPx(0,0,8,8))
            engine.resetDocument()
            // A visibly different previous paper makes rollback equality nonvacuous.
            engine.setDocumentPaper(Paper(color = "#123456"))
            if (engine.renderPaper(RectPx(0,0,8,8)).contentEquals(targetPaperPixels)) {
                engine.setDocumentPaper(Paper(color = "#ABCDEF"))
            }
            paperSentinelDifferent = !engine.renderPaper(RectPx(0,0,8,8)).contentEquals(targetPaperPixels)
            check(paperSentinelDifferent && engine.documentPaper != plan.metadataCopy().paper)
            alive = engine.coldUploadProbeContext()
            contextStarted = true
            entry = state()
            protectedTextures = engine.protectedRecordedTextureHandles()

            gl("generate owned hostile texture") { GLES30.glGenTextures(1, texture, 0) }
            check(texture[0] != 0 && texture[0] !in protectedTextures && texture[0] !in entry!!.takeLast(2)) {
                "Generated hostile texture aliases an existing owner or caller binding"
            }
            gl("select unit zero") { GLES30.glActiveTexture(GLES30.GL_TEXTURE0) }
            gl("bind hostile texture") { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0]) }
            gl("unbind unpack PBO for texture allocation") { GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0) }
            gl("allocate hostile texture") {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            }
            gl("select unit three") { GLES30.glActiveTexture(GLES30.GL_TEXTURE3) }
            gl("bind unit three hostile texture") { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0]) }

            gl("generate hostile unpack PBO") { GLES30.glGenBuffers(1, buffer, 0) }
            check(buffer[0] != 0 && buffer[0] != entry!![10] && buffer[0] != entry!![11]) {
                "Generated PBO aliases a caller binding"
            }
            gl("bind hostile unpack PBO") { GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, buffer[0]) }
            gl("allocate hostile unpack PBO") {
                GLES30.glBufferData(GLES30.GL_PIXEL_UNPACK_BUFFER, 64, null, GLES30.GL_STATIC_DRAW)
            }
            for ((index, value) in intArrayOf(8, Tiles.SIZE + 7, 2, 3, Tiles.SIZE + 13, 1).withIndex()) {
                gl("set hostile unpack layout") { GLES30.glPixelStorei(unpackNames[index], value) }
            }

            gl("generate hostile FBOs") { GLES30.glGenFramebuffers(framebuffers.size, framebuffers, 0) }
            check(framebuffers.all { it != 0 } && framebuffers.distinct().size == framebuffers.size &&
                framebuffers.none { it == entry!![12] || it == entry!![13] }) {
                "Generated FBOs alias a caller binding"
            }
            gl("bind hostile draw FBO") { GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, framebuffers[0]) }
            gl("bind hostile read FBO") { GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, framebuffers[1]) }
            hostile = state()
            checked("hostile state setup")

            for (target in targets) {
                check(current()) { "Context retired before target ${target.phase}/${target.uploadId}" }
                check(state() == hostile) { "Hostile caller state drifted before target ${target.phase}/${target.uploadId}" }
                val raw = engine.runColdUploadFailureProbe(plan, target)
                val row = JSONObject(raw)
                check(row.optBoolean("passed") && row.optBoolean("expectedFaultReached") &&
                    row.optBoolean("candidateEmpty") && row.optBoolean("callerGlStateRestored") &&
                    row.optInt("generatedTextures", 0) > 0 &&
                    row.optInt("ownedAfterRollback", -1) + row.optInt("deletedAfterRollback", -1) ==
                        row.optInt("generatedTextures", 0) &&
                    row.optInt("orphanedAfterRollback", -1) == 0 &&
                    row.optInt("danglingOwnersAfterRollback", -1) == 0 &&
                    row.optInt("unexpectedCleanupFailures", -1) == 0 &&
                    row.optBoolean("paperFieldsRestored") && row.optBoolean("cpuPaperRestored") &&
                    row.optBoolean("priorPaperTexturesValid") && row.optBoolean("contextCurrent") &&
                    row.optInt("linePreparationCalls", -1) == 0 && row.optInt("compositionCalls", -1) == 0) {
                    "Engine rejected controlled rollback target ${target.phase}/${target.uploadId}: $raw"
                }
                val shouldRetireFail = target.phase == ColdUploadFailurePhase.AFTER_ROOT_BIND_WITH_RETIRE_FAILURE
                check(row.optBoolean("retirementFailureObserved") == shouldRetireFail) {
                    "Retirement failure observation mismatch for ${target.phase}"
                }
                if (shouldRetireFail) retirementFailureSeen = row.optBoolean("retirementFailureObserved")
                check(state() == hostile) { "Attempt ${target.phase}/${target.uploadId} changed full hostile caller GL state" }
                hostileStateChecks++
                checked("after attempt ${target.phase}/${target.uploadId}")
                // Preserve the engine's complete measured row for independent proof counting.
                rows.put(row)
            }
            check(rows.length() == 21 && retirementFailureSeen)
            report.put("passed", true)
        } catch (error: Exception) {
            failure = "${error.javaClass.simpleName}: ${error.message}"
        } finally {
            if (!contextStarted) {
                cleanupComplete = true
            } else if (current()) {
                var cleanupFailed = false
                fun cleanup(action: () -> Unit) {
                    try { action() } catch (error: Exception) {
                        cleanupFailed = true
                        failure = listOfNotNull(failure, "cleanup ${error.message}").joinToString("; ")
                    }
                }
                cleanup { entry?.let(::restore) }
                // glGen writes the primitive ledgers before validation. Reclaim every fresh
                // nonzero name even after a partial generation or rejected sibling name;
                // caller bindings and existing engine textures must never be deleted.
                for (name in framebuffers.filter { it != 0 && it != entry?.get(12) && it != entry?.get(13) }.distinct()) cleanup {
                    gl("delete hostile FBO") { GLES30.glDeleteFramebuffers(1, intArrayOf(name), 0) }
                    check(!gl("verify hostile FBO release") { GLES30.glIsFramebuffer(name) })
                }
                if (buffer[0] != 0 && buffer[0] != entry?.get(10) && buffer[0] != entry?.get(11)) cleanup {
                    gl("delete hostile PBO") { GLES30.glDeleteBuffers(1, buffer, 0) }
                    check(!gl("verify hostile PBO release") { GLES30.glIsBuffer(buffer[0]) })
                }
                if (texture[0] != 0 && texture[0] !in protectedTextures && entry?.takeLast(2)?.contains(texture[0]) != true) cleanup {
                    gl("delete hostile texture") { GLES30.glDeleteTextures(1, texture, 0) }
                    check(!gl("verify hostile texture release") { GLES30.glIsTexture(texture[0]) })
                }
                cleanup { checked("probe cleanup") }
                cleanup { check(entry == state()) { "Probe cleanup changed caller GL state" } }
                cleanupComplete = !cleanupFailed && current()
            } else {
                retired = true
                failure = listOfNotNull(failure, "Context retired; diagnostic names abandoned").joinToString("; ")
            }
            report.put("passed", report.optBoolean("passed") && cleanupComplete && failure == null)
                .put("archiveSha256", archiveSha256 ?: JSONObject.NULL)
                .put("archiveBytes", archiveBytes)
                .put("matrixRows", rows)
                .put("matrixCount", rows.length())
                .put("manifestUploadCount", manifestCount)
                .put("hostileCallerStateChecks", hostileStateChecks)
                .put("retirementFailureObserved", retirementFailureSeen)
                .put("paperSentinelDifferent", paperSentinelDifferent)
                .put("diagnosticNamesReleased", cleanupComplete)
                .put("contextRetired", retired)
                .put("cleanupGlChecks", cleanupGlChecks)
                .put("cleanupGlErrors", cleanupErrors)
                .put("failure", failure ?: JSONObject.NULL)
                .put("scope", "Disposable controlled cold-upload rollback diagnostics only; no production activation")
        }
        return report
    }
}
