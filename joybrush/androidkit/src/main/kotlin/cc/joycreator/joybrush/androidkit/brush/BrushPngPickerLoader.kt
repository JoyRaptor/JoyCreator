package cc.joycreator.joybrush.androidkit.brush

import cc.joycreator.joybrush.androidkit.gl.PngSamplePreflight
import cc.joycreator.joybrush.androidkit.io.FrozenBrushAssets
import cc.joycreator.joybrush.core.brush.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

/** Host supplies URI resolution as a stream opener. Display names and reported sizes are untrusted metadata. */
class BrushPickedPng(val displayName: String?, val reportedBytes: Long? = null, val open: () -> InputStream)

/** No URI, filesystem path, permission persistence or Activity launch is inferred here. */
class BrushPngPickerLoader(private val verifier: FrozenBrushAssets.PngVerifier? = null,
    private val limits: Limits = Limits(), private val uuid: () -> UUID = UUID::randomUUID) {
    data class Limits(val maxImageBytes: Int = FrozenBrushAssets.MAX_PNG_BYTES,
        val maxSetBytes: Long = FrozenBrushAssets.MAX_SET_BYTES,
        val maxPixels: Long = FrozenBrushAssets.MAX_SET_PIXELS) {
        init {
            require(maxImageBytes in 33..FrozenBrushAssets.MAX_PNG_BYTES)
            require(maxSetBytes in 33L..FrozenBrushAssets.MAX_SET_BYTES)
            require(maxPixels in 1L..FrozenBrushAssets.MAX_SET_PIXELS)
        }
    }

    /** All streams close before any full decode; failure returns no pair and changes no session owner. */
    fun prepare(request: BrushImagePickRequest, sources: List<BrushPickedPng>,
        isCancelled: () -> Boolean = { false }): PreparedBrushImageChange {
        if (request.slot == BrushImageSlot.tipTexture) refuse("Tip texture image freezing/import is not yet implemented")
        val maxCount = if (request.slot == BrushImageSlot.sampled) SampledTips.MAX_IMAGES else 1
        if (sources.size !in 1..maxCount) refuse("Choose 1 to $maxCount PNG images for this image slot")
        val picked = sources.toList()
        checkCancelled(isCancelled)
        val prefix = "picked/${uuid()}"
        val ordered = picked.indices.map { "$prefix/${it+1}.png" }
        if (ordered.any { it in request.base.resources.paths }) refuse("The generated image identities already exist")
        val original = request.base.recipe
        val tip = when (request.slot) {
            BrushImageSlot.sampled -> original.tip.copy(source = "image", image = ordered.first(),
                sampled = (original.tip.sampled ?: SampledTipSpec(ordered)).copy(images = ordered))
            BrushImageSlot.dual -> original.tip.copy(dual = (original.tip.dual ?: DualTipSpec()).let { dual ->
                dual.copy(shape = null, image = (dual.image ?: DualImageSpec(ordered.single())).copy(image = ordered.single()))
            })
            BrushImageSlot.tipTexture -> refuse("Tip texture image freezing/import is not yet implemented")
        }
        val recipe = BrushImageAdmission.checked(original.copy(tip = tip))
        val required = BrushAssetPaths.required(recipe)
        val kept = required.filter { it !in ordered }
        val encoded = linkedMapOf<String, ByteArray>()
        if (request.base.resources.encodedBytes > FrozenBrushAssets.MAX_SET_BYTES)
            refuse("The original resource owner exceeds its encoded byte budget")
        var total = 0L
        for (path in kept) {
            val bytes = request.base.resources.requirePng(path)
            reserve(bytes.size, total);total += bytes.size;encoded[path] = bytes
        }
        // Do not trust metadata as allocation size; still reject impossible declarations before opening anything.
        var declared = total
        for (source in picked) source.reportedBytes?.let { size ->
            if (size !in 1..limits.maxImageBytes.toLong() || size > limits.maxSetBytes-declared)
                refuse("The selected PNG metadata exceeds the encoded image/set budget")
            declared += size
        }
        for ((index, source) in picked.withIndex()) {
            checkCancelled(isCancelled)
            val remaining = limits.maxSetBytes-total
            val limit = minOf(limits.maxImageBytes.toLong(),remaining).toInt()
            if (limit < 33) refuse("The complete selected image bundle exceeds its encoded byte budget")
            val bytes = source.open().use { readBounded(it,limit,isCancelled) }
            reserve(bytes.size,total);total += bytes.size;encoded[ordered[index]] = bytes
        }
        // Inspect and reserve the complete current+new source closure before calling any decoder.
        val headers = encoded.mapValues { PngSamplePreflight.inspect(it.value) }
        var pixels = 0L
        for (path in BrushAssetPaths.ordered(recipe)) {
            val header = headers.getValue(path)
            val count = header.width.toLong()*header.height
            if (count > limits.maxPixels-pixels) refuse("The complete selected image bundle exceeds its decoded pixel budget")
            pixels += count
        }
        checkCancelled(isCancelled)
        val owner = FrozenBrushPngs.snapshot(encoded)
        val pair = BrushImageAdmission.admit(recipe,owner,verifier)
        checkCancelled(isCancelled)
        return PreparedBrushImageChange(request.owner,request.revision,request,pair,ordered,
            picked.map { label(it.displayName) })
    }

    private fun reserve(size: Int, total: Long) {
        if (size !in 1..limits.maxImageBytes || size > limits.maxSetBytes-total)
            refuse("The complete selected image bundle exceeds its encoded byte budget")
    }
    private fun readBounded(stream: InputStream, limit: Int, cancelled: () -> Boolean): ByteArray {
        val out = ByteArrayOutputStream(minOf(8192,limit));val buffer = ByteArray(8192)
        var count = 0
        while (true) {
            checkCancelled(cancelled)
            val ask = minOf(buffer.size, limit-count+1)
            val read = stream.read(buffer,0,ask)
            if (read < 0) break
            if (read == 0) {
                val single = stream.read()
                if (single < 0) break
                if (count >= limit) refuse("A selected PNG exceeds its encoded byte budget")
                out.write(single);count++;continue
            }
            if (read > limit-count) refuse("A selected PNG exceeds its encoded byte budget")
            out.write(buffer,0,read);count+=read
        }
        return out.toByteArray()
    }
    private fun label(name: String?): String = name.orEmpty().take(256).asSequence().filterNot { Character.isISOControl(it) }
        .take(80).joinToString("").trim().ifEmpty { "PNG image" }
    private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) refuse("Image selection was cancelled") }
    private fun refuse(message: String): Nothing = throw BrushImageDraftException(message)
}
