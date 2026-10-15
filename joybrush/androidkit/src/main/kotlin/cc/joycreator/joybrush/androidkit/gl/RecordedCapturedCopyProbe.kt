package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.CaptureRect
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only GPU comparison for a disposable captured physical copy. All texture handles are
 * borrowed from the caller; this probe owns only its read FBO and never publishes or releases
 * engine textures.
 */
internal object RecordedCapturedCopyProbe {
    private data class Diff(val channels: Int, val pixels: Int, val max: Int, val nonzeroAlpha: Int,
                            val fractionalAlpha: Int, val zeroAlpha: Int)

    internal fun compare(canonical: Map<Long, Int>, source: CapturedPhysicalSource,
                         contextCurrent: () -> Boolean,
                         preparePlaced: () -> Map<Long, Int>): JSONObject {
        val report = JSONObject().put("probe", "recordedCapturedCopyPixels")
            .put("passed", false).put("textureReleaseByProbe", false)
            .put("comparison", "Independent Long arithmetic, half-open window/exclusion membership, exact RGBA8")
        val rows = JSONArray()
        var placed: Map<Long, Int> = emptyMap()
        var canonicalBytes: Map<Long, ByteArray> = emptyMap()
        val n = Tiles.SIZE
        val length = n * n * 4
        report.put("tileSize",n).put("tileBytes",length)
        var fbo = 0
        var readFboDeleted = false
        var readFboAbandoned = false
        var fboChecks = 0
        var incompleteFbos = 0
        var glChecks = 0
        var glErrors = 0
        var feedbackChecks = 0
        var feedbackFailures = 0
        var sourceReads = 0
        var placedReads = 0
        var preparePlacedCalls = 0
        var cpuTexels = 0L
        var outputTexels = 0L
        var expectedPlacedKeys: Set<Long> = emptySet()
        var missingPlacedKeys: Set<Long> = emptySet()
        var extraPlacedKeys: Set<Long> = emptySet()
        var expectedKeysEqual = false
        var expectedTransparentTiles = 0
        var missingTransparentKeyControl = JSONObject().put("present", false)
        var sourceAfterDifferentChannels = 0
        var sourceAfterDifferentPixels = 0
        var failure: String? = null
        var cleanupComplete = false
        var contextRetired = false

        fun contextIsCurrent(): Boolean {
            if (contextRetired) return false
            return try { contextCurrent() } catch (_: Exception) {
                contextRetired = true
                false
            }
        }

        fun ensureContext(label: String) {
            if (!contextIsCurrent()) {
                contextRetired = true
                throw IllegalStateException("$label graphics context is no longer current")
            }
        }

        fun <T> gl(label: String, call: () -> T): T {
            ensureContext("before $label")
            val result = call()
            ensureContext("after $label")
            return result
        }

        fun checkGl(label: String) {
            glChecks++
            var first = GLES30.GL_NO_ERROR
            var code = gl("$label glGetError") { GLES30.glGetError() }
            while (code != GLES30.GL_NO_ERROR) {
                if (first == GLES30.GL_NO_ERROR) first = code
                glErrors++
                code = gl("$label glGetError") { GLES30.glGetError() }
            }
            check(first == GLES30.GL_NO_ERROR) { "$label GL error 0x${first.toString(16)}" }
        }

        fun detach() {
            gl("detach FBO") { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0) }
            for (slot in 0..1) {
                gl("select sampler $slot") { GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + slot) }
                gl("detach sampler $slot") { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0) }
            }
        }

        fun read(texture: Int, label: String): ByteArray {
            feedbackChecks++
            if (texture == 0) feedbackFailures++
            check(texture != 0) { "$label has texture 0" }
            detach()
            gl("unbind pixel pack buffer") { GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0) }
            gl("set pack alignment") { GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1) }
            gl("set pack row length") { GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0) }
            gl("set pack skip rows") { GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0) }
            gl("set pack skip pixels") { GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0) }
            gl("bind read FBO") { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo) }
            gl("attach borrowed texture") { GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0) }
            fboChecks++
            val status = gl("check read FBO") { GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) }
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) incompleteFbos++
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "$label incomplete framebuffer 0x${status.toString(16)}"
            }
            gl("select read attachment") { GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0) }
            val buffer = ByteBuffer.allocateDirect(length)
            gl("read RGBA8 pixels") { GLES30.glReadPixels(0, 0, n, n, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer) }
            checkGl(label)
            buffer.position(0)
            return ByteArray(length).also { buffer.get(it) }
        }

        fun contains(rect: CaptureRect, x: Long, y: Long): Boolean =
            x >= rect.x.toLong() && x < rect.x.toLong() + rect.w.toLong() &&
                y >= rect.y.toLong() && y < rect.y.toLong() + rect.h.toLong()

        fun included(sx: Long, sy: Long, allDomain: Boolean = false): Boolean {
            if (sx !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
                sy !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return false
            if (allDomain) return true
            val window = source.domain.window
            if (window != null && !contains(window, sx, sy)) return false
            return source.domain.excluded.none { contains(it, sx, sy) }
        }

        fun keyForPixel(x: Long, y: Long): Long? {
            val tx = Math.floorDiv(x, n.toLong())
            val ty = Math.floorDiv(y, n.toLong())
            if (tx !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
                ty !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
            return Tiles.key(tx.toInt(), ty.toInt())
        }

        fun sourceByte(raw: Map<Long, ByteArray>, x: Long, y: Long, c: Int): Int {
            if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
                y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return 0
            val key = keyForPixel(x, y) ?: return 0
            val tile = raw[key] ?: return 0 // Missing sparse tile means transparent source.
            val lx = Math.floorMod(x, n.toLong()).toInt()
            val ly = Math.floorMod(y, n.toLong()).toInt()
            return tile[(ly * n + lx) * 4 + c].toInt() and 255
        }

        fun outputKeys(dx: Long, dy: Long, allDomain: Boolean = false): Set<Long> {
            val result = linkedSetOf<Long>()
            for (key in canonical.keys) {
                val ox = Tiles.tx(key).toLong() * n
                val oy = Tiles.ty(key).toLong() * n
                for (ly in 0 until n) for (lx in 0 until n) {
                    val sx = ox + lx
                    val sy = oy + ly
                    if (!included(sx, sy, allDomain)) continue
                    val x = sx + dx
                    val y = sy + dy
                    if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
                        y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) continue
                    result += checkNotNull(keyForPixel(x, y)) { "Placed destination tile is unaddressable" }
                }
            }
            return result
        }

        fun expectedTile(key: Long, raw: Map<Long, ByteArray>, dx: Long, dy: Long,
                         allDomain: Boolean = false): ByteArray {
            val result = ByteArray(length)
            val ox = Tiles.tx(key).toLong() * n
            val oy = Tiles.ty(key).toLong() * n
            for (ly in 0 until n) for (lx in 0 until n) {
                outputTexels++
                val dxp = ox + lx
                val dyp = oy + ly
                val sx = dxp - dx
                val sy = dyp - dy
                if (sx !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
                    sy !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || !included(sx, sy, allDomain)) continue
                val p = (ly * n + lx) * 4
                for (c in 0..3) result[p + c] = sourceByte(raw, sx, sy, c).toByte()
                cpuTexels++
            }
            return result
        }

        fun diff(expected: ByteArray, actual: ByteArray): Diff {
            var channels = 0
            var pixels = 0
            var max = 0
            var nonzero = 0
            var fractional = 0
            var zero = 0
            for (p in expected.indices step 4) {
                val alpha = expected[p + 3].toInt() and 255
                if (alpha == 0) zero++ else {
                    nonzero++
                    if (alpha < 255) fractional++
                }
                var changed = false
                for (c in 0..3) {
                    val delta = kotlin.math.abs((expected[p + c].toInt() and 255) -
                        (actual[p + c].toInt() and 255))
                    if (delta != 0) { channels++; changed = true }
                    max = maxOf(max, delta)
                }
                if (changed) pixels++
            }
            return Diff(channels, pixels, max, nonzero, fractional, zero)
        }

        try {
            check(canonical.size in 1..64) { "Canonical map exceeds the 64-tile probe bound" }
            check(canonical.values.all { it != 0 }) { "Canonical map contains texture 0" }
            feedbackChecks++
            if (canonical.values.size != canonical.values.toSet().size) feedbackFailures++
            check(canonical.values.size == canonical.values.toSet().size) { "Canonical texture handles alias each other" }
            checkGl("entry")

            val names = IntArray(1)
            gl("allocate read FBO") { GLES30.glGenFramebuffers(1, names, 0) }
            fbo = names[0]
            check(fbo != 0) { "Could not allocate read FBO" }
            checkGl("FBO allocation")

            canonicalBytes = canonical.mapValues { (key, texture) ->
                sourceReads++
                read(texture, "canonical source $key")
            }

            // The kernel runs only after the independent canonical baseline is frozen and the
            // probe FBO/sampler bindings are detached. It is called exactly once and owns rollback
            // of any output allocations if it throws.
            detach()
            checkGl("before copy kernel")
            ensureContext("before copy kernel callback")
            preparePlacedCalls++
            placed = preparePlaced()
            ensureContext("after copy kernel callback")
            check(preparePlacedCalls == 1) { "Copy kernel callback was invoked more than once" }
            check(placed.size <= 64) { "Placed map exceeds the 64-tile probe bound" }
            check(placed.values.all { it != 0 }) { "Placed map contains texture 0" }
            val allHandles = canonical.values + placed.values
            feedbackChecks++
            if (allHandles.size != allHandles.toSet().size) feedbackFailures++
            check(allHandles.size == allHandles.toSet().size) { "Canonical/placed texture ownership aliases" }
            val dx = source.placement.dx.toLong()
            val dy = source.placement.dy.toLong()
            expectedPlacedKeys = outputKeys(dx, dy)
            missingPlacedKeys = expectedPlacedKeys - placed.keys
            extraPlacedKeys = placed.keys - expectedPlacedKeys
            expectedKeysEqual = missingPlacedKeys.isEmpty() && extraPlacedKeys.isEmpty()
            val transparentExpectedKeys = expectedPlacedKeys.filter { key ->
                expectedTile(key, canonicalBytes, dx, dy).all { it == 0.toByte() }
            }
            expectedTransparentTiles = transparentExpectedKeys.size
            if (transparentExpectedKeys.isNotEmpty()) {
                val simulatedMissing = expectedPlacedKeys - transparentExpectedKeys.first()
                missingTransparentKeyControl = JSONObject().put("present", true)
                    .put("missingTileKeyDetected", simulatedMissing != expectedPlacedKeys)
                    .put("pixelDiffIfAbsent", 0)
            }
            report.put("expectedPlacedTiles", expectedPlacedKeys.size)
                .put("missingPlacedKeys", JSONArray(missingPlacedKeys.sorted()))
                .put("extraPlacedKeys", JSONArray(extraPlacedKeys.sorted()))
                .put("expectedKeysEqual", expectedKeysEqual)
                .put("expectedWhollyTransparentTiles", expectedTransparentTiles)
                .put("missingTransparentKeyControl", missingTransparentKeyControl)
            if (!expectedKeysEqual) {
                // Even a key-set refusal must verify the source against the pre-copy baseline.
                for ((key, texture) in canonical) {
                    sourceReads++
                    val after = read(texture, "canonical source unchanged after key mismatch $key")
                    val d = diff(canonicalBytes.getValue(key), after)
                    sourceAfterDifferentChannels += d.channels
                    sourceAfterDifferentPixels += d.pixels
                }
                check(sourceAfterDifferentChannels == 0 && sourceAfterDifferentPixels == 0) {
                    "Canonical source changed while copy returned an incorrect tile-key set"
                }
                error("Placed tile keys do not match independently expected output keys")
            }
            val placedBytes = placed.mapValues { (key, texture) ->
                placedReads++
                read(texture, "placed output $key")
            }
            check(canonicalBytes.values.all { it.size == length } && placedBytes.values.all { it.size == length })

            val correctKeys = expectedPlacedKeys + placed.keys
            val offsetKeys = outputKeys(dx + 1L, dy) + correctKeys
            val domainKeys = outputKeys(dx, dy, allDomain = true) + correctKeys
            val unionKeys = correctKeys + offsetKeys + domainKeys
            check(unionKeys.size <= 64) { "Expected union exceeds the 64-tile probe bound" }
            var comparedChannels = 0
            var comparedPixels = 0
            var maxDelta = 0
            var expectedNonzero = 0
            var expectedFractional = 0
            var expectedZero = 0
            var wrongOffsetChannels = 0
            var wrongOffsetPixels = 0
            var wrongDomainChannels = 0
            var wrongDomainPixels = 0
            for (key in unionKeys.sorted()) {
                val expected = expectedTile(key, canonicalBytes, dx, dy)
                val actual = placedBytes[key] ?: ByteArray(length)
                val d = diff(expected, actual)
                val wrongOffset = diff(expectedTile(key, canonicalBytes, dx + 1L, dy), expected)
                val wrongDomain = diff(expectedTile(key, canonicalBytes, dx, dy, allDomain = true), expected)
                comparedChannels += d.channels
                comparedPixels += d.pixels
                maxDelta = maxOf(maxDelta, d.max)
                expectedNonzero += d.nonzeroAlpha
                expectedFractional += d.fractionalAlpha
                expectedZero += d.zeroAlpha
                wrongOffsetChannels += wrongOffset.channels
                wrongOffsetPixels += wrongOffset.pixels
                wrongDomainChannels += wrongDomain.channels
                wrongDomainPixels += wrongDomain.pixels
                rows.put(JSONObject().put("kind", "placed-vs-independent-expected").put("tileKey", key)
                    .put("differentChannels", d.channels).put("differentPixels", d.pixels).put("maxByteDelta", d.max))
            }
            // Re-read source after all comparisons to prove this read-only probe did not alter it.
            for ((key, texture) in canonical) {
                sourceReads++
                val after = read(texture, "canonical source unchanged $key")
                val d = diff(canonicalBytes.getValue(key), after)
                sourceAfterDifferentChannels += d.channels
                sourceAfterDifferentPixels += d.pixels
            }
            check(sourceAfterDifferentChannels == 0 && sourceAfterDifferentPixels == 0) {
                "Canonical source bytes changed during read-only comparison"
            }
            check(wrongOffsetChannels > 0 && wrongOffsetPixels > 0) { "Wrong-offset CPU control was not distinguishable" }
            check(wrongDomainChannels > 0 && wrongDomainPixels > 0) { "All-domain CPU control was not distinguishable" }
            check(expectedNonzero > 0 && expectedFractional > 0 && expectedZero > 0) {
                "Fixture needs copied nonzero, fractional-alpha, and zero-alpha texels"
            }
            if (expectedTransparentTiles > 0) {
                check(missingTransparentKeyControl.optBoolean("missingTileKeyDetected") &&
                    missingTransparentKeyControl.optInt("pixelDiffIfAbsent", -1) == 0) {
                    "Missing-transparent-key CPU control did not distinguish ownership from pixel bytes"
                }
            }
            check(comparedChannels == 0 && comparedPixels == 0) { "Placed output differs from independent expected texels" }
            report.put("canonicalTiles", canonical.size).put("placedTiles", placed.size)
                .put("expectedWhollyTransparentTiles", expectedTransparentTiles)
                .put("comparedTileRows", rows.length()).put("comparisonRows", rows)
                .put("sourceReadCalls", sourceReads).put("placedReadCalls", placedReads)
                .put("preparePlacedCalls", preparePlacedCalls)
                .put("sourceBytesRead", sourceReads.toLong() * length)
                .put("placedBytesRead", placedReads.toLong() * length)
                .put("cpuTexelsVisited", cpuTexels).put("outputTexelsVisited", outputTexels)
                .put("differentChannels", comparedChannels).put("differentPixels", comparedPixels)
                .put("maxByteDelta", maxDelta).put("expectedNonzeroAlphaPixels", expectedNonzero)
                .put("expectedFractionalAlphaPixels", expectedFractional).put("expectedZeroAlphaPixels", expectedZero)
                .put("sourceAfterDifferentChannels", sourceAfterDifferentChannels)
                .put("sourceAfterDifferentPixels", sourceAfterDifferentPixels)
                .put("controls", JSONObject()
                    .put("wrongOffsetDifferentChannels", wrongOffsetChannels)
                    .put("wrongOffsetDifferentPixels", wrongOffsetPixels)
                    .put("allDomainDifferentChannels", wrongDomainChannels)
                    .put("allDomainDifferentPixels", wrongDomainPixels)
                    .put("missingTransparentKey", missingTransparentKeyControl))
            checkGl("comparison complete")
            report.put("passed", true)
        } catch (e: Exception) {
            failure = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            if (!contextIsCurrent()) {
                contextRetired = true
                readFboAbandoned = fbo != 0
                cleanupComplete = false
                failure = listOfNotNull(failure, "Graphics context retired; probe FBO abandoned without GL cleanup")
                    .joinToString("; ")
            } else try {
                detach()
                if (fbo != 0) {
                    gl("delete read FBO") { GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0) }
                    readFboDeleted = true
                    fbo = 0
                }
                checkGl("cleanup")
                cleanupComplete = true
            } catch (e: Exception) {
                if (contextRetired) {
                    readFboAbandoned = fbo != 0
                    cleanupComplete = false
                }
                failure = listOfNotNull(failure, "Cleanup ${e.javaClass.simpleName}: ${e.message}").joinToString("; ")
            }
            report.put("framebufferChecks", fboChecks).put("incompleteFramebuffers", incompleteFbos)
                .put("glErrorChecks", glChecks).put("observedGlErrors", glErrors)
                .put("feedbackChecks", feedbackChecks).put("feedbackFailures", feedbackFailures)
                .put("canonicalTiles", canonical.size).put("placedTiles", placed.size)
                .put("expectedPlacedTiles", expectedPlacedKeys.size)
                .put("missingPlacedKeys", JSONArray(missingPlacedKeys.sorted()))
                .put("extraPlacedKeys", JSONArray(extraPlacedKeys.sorted()))
                .put("expectedKeysEqual", expectedKeysEqual)
                .put("expectedWhollyTransparentTiles", expectedTransparentTiles)
                .put("missingTransparentKeyControl", missingTransparentKeyControl)
                .put("comparedTileRows", rows.length()).put("comparisonRows", rows)
                .put("sourceReadCalls", sourceReads).put("placedReadCalls", placedReads)
                .put("preparePlacedCalls", preparePlacedCalls)
                .put("sourceBytesRead", sourceReads.toLong() * length)
                .put("placedBytesRead", placedReads.toLong() * length)
                .put("cpuTexelsVisited", cpuTexels).put("outputTexelsVisited", outputTexels)
                .put("cleanupComplete", cleanupComplete).put("readFboDeleted", readFboDeleted)
                .put("readFboAbandoned", readFboAbandoned).put("contextRetired", contextRetired)
                .put("failure", failure ?: JSONObject.NULL)
            if (!cleanupComplete || failure != null) report.put("passed", false)
        }
        return report
    }

}
