package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import android.opengl.GLES31
import cc.joycreator.joybrush.androidkit.io.PaperResources
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.paper.PaperState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Source-only until invoked by an admitted debug dispatch. GL thread, quiescent host, disposable
 * engine only: no owner canvas, files, history, preferences or device lifecycle actions. Visits
 * every full-evaluation look twice and checks actual GL identities/mips, not just cache metadata.
 * This idle traversal does not prove frozen-stroke, sliced-water, selected-line or cold rollback
 * GPU parity; those remain separate acceptance gates. Live borrowers may exceed an idle bound.
 */
internal object PaperTextureRetentionProbe {
    private val comparisons = setOf("eval_expanded_firm_p1k", "eval_expanded_firm_p512")
    private const val MASTER_COUNT = 45
    private const val EVAL_COUNT = MASTER_COUNT + 2
    private const val FULL_2048_MIP_BYTES = 22_369_620L

    fun probe(): String {
        val report = JSONObject().put("probe", "paperTextureRetention")
            .put("renderer", GLES30.glGetString(GLES30.GL_RENDERER))
        val rows = JSONArray()
        val engine = GlPaintEngine()
        var started = false
        var visits = 0
        var liveChecks = 0
        var retiredChecks = 0
        var maxCount = 0
        var maxBytes = 0L
        var failureCount = 0
        try {
            val catalogue = PaperResources.catalogue
            val looks = catalogue.looks.filter { it.id.startsWith("eval_") }.sortedBy { it.id }
            val masters = looks.filter { it.id !in comparisons }
            report.put("selectedIds", JSONArray(looks.map { it.id }))
                .put("masterIds", JSONArray(masters.map { it.id }))
                .put("evalCount", looks.size).put("masterCount", masters.size)
            check(looks.size == EVAL_COUNT && masters.size == MASTER_COUNT &&
                looks.map { it.id }.toSet().containsAll(comparisons)) {
                "Need all 45 masters and exactly the two installed comparisons; refuse a partial dataset"
            }
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Probe entered with a GL error" }
            engine.init(); started = true
            var previous = emptySet<Int>()
            repeat(2) { cycle ->
                for (look in looks) {
                    val paper = Paper(lookId = look.id, textureId = look.defaultSurface)
                    val resolved = PaperState.resolve(paper, catalogue)
                    val lookFile = checkNotNull(look.file) { "${look.id}: missing look file" }
                    val surface = checkNotNull(resolved.surface) { "${look.id}: missing default surface" }
                    engine.setDocumentPaper(paper)
                    check(engine.paperWarnings.isEmpty()) { "${look.id}: ${engine.paperWarnings}" }
                    val snapshot = engine.paperTextureCacheSnapshot()
                    val live = engine.borrowedPaperTextureHandles()
                    val files = listOfNotNull(lookFile, surface.file, surface.fluid).toSet()
                    check(snapshot.handlesByAsset.keys == files.map { "paper/$it" }.toSet()) {
                        "${look.id}: idle cache retained unrelated paper"
                    }
                    check(snapshot.count <= 3 && snapshot.estimatedMipBytes <= 3 * FULL_2048_MIP_BYTES &&
                        snapshot.collectionFailures == 0L) { "${look.id}: unbounded cache or failed collection" }
                    for (texture in snapshot.handles) {
                        liveChecks++
                        check(texture in live && GLES30.glIsTexture(texture)) { "${look.id}: current texture is dead" }
                    }
                    for (texture in previous - live) {
                        retiredChecks++
                        check(!GLES30.glIsTexture(texture)) { "${look.id}: retired unborrowed texture remains live" }
                    }
                    var actualMipBytes = 0L
                    for ((asset, texture) in snapshot.handlesByAsset) {
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                        val baseDimensions = dimensions(0)
                        if (look.id !in comparisons && (asset == "paper/$lookFile" || asset == "paper/${surface.file}"))
                            check(baseDimensions == (2048 to 2048)) { "${look.id}: master $asset is $baseDimensions" }
                        var width = baseDimensions.first; var height = baseDimensions.second; var level = 0
                        check(width > 0 && height > 0) { "$asset: no GPU storage" }
                        while (true) {
                            check(dimensions(level) == (width to height)) { "$asset: missing/wrong mip $level" }
                            actualMipBytes += width.toLong() * height * 4L
                            if (width == 1 && height == 1) break
                            width = (width / 2).coerceAtLeast(1); height = (height / 2).coerceAtLeast(1); level++
                        }
                    }
                    check(actualMipBytes == snapshot.estimatedMipBytes) { "${look.id}: mip-byte estimate differs from GPU storage" }
                    check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "${look.id}: GL error" }
                    visits++
                    maxCount = maxOf(maxCount, snapshot.count); maxBytes = maxOf(maxBytes, snapshot.estimatedMipBytes)
                    rows.put(JSONObject().put("cycle", cycle + 1).put("id", look.id)
                        .put("paperCount", snapshot.count).put("paperMipBytes", snapshot.estimatedMipBytes))
                    previous = snapshot.handles
                }
            }
            check(retiredChecks > 0) { "Traversal did not exercise retirement" }
        } catch (failure: Throwable) {
            failureCount++
            report.put("error", failure.toString()).put("at", failure.stackTrace.take(8)
                .joinToString(" < ") { "${it.fileName}:${it.lineNumber}" })
        } finally {
            if (started) try { engine.release() } catch (failure: Throwable) {
                failureCount++; report.put("releaseError", failure.toString())
            }
        }
        val glError = GLES30.glGetError()
        if (glError != GLES30.GL_NO_ERROR) failureCount++
        return report.put("visits", visits).put("liveGlChecks", liveChecks).put("retiredGlChecks", retiredChecks)
            .put("maxPaperCount", maxCount).put("maxPaperMipBytes", maxBytes).put("failureCount", failureCount)
            .put("glError", glError).put("rows", rows)
            .put("pass", failureCount == 0 && visits == EVAL_COUNT * 2).toString()
    }

    private fun dimensions(level: Int): Pair<Int, Int> {
        val values = IntArray(2)
        GLES31.glGetTexLevelParameteriv(GLES30.GL_TEXTURE_2D, level, GLES31.GL_TEXTURE_WIDTH, values, 0)
        GLES31.glGetTexLevelParameteriv(GLES30.GL_TEXTURE_2D, level, GLES31.GL_TEXTURE_HEIGHT, values, 1)
        return values[0] to values[1]
    }
}
