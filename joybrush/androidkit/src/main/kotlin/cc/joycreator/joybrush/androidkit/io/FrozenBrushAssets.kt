package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.BrushAssetPaths
import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import cc.joycreator.joybrush.core.brush.FrozenBrushRegistry
import cc.joycreator.joybrush.core.brush.SampledTips
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlinx.serialization.json.*

/** Archive bytes only. Resolved sampled sets still fully decode their PNGs before drawing. */
object FrozenBrushAssets {
    const val MANIFEST_ENTRY = "brush-assets/index.json"
    const val MAX_MANIFEST_BYTES = 1024 * 1024
    const val MAX_PNG_BYTES = 16 * 1024 * 1024
    const val MAX_SET_BYTES = 32L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    const val MAX_DIMENSION = 2048
    const val MAX_SET_PIXELS = 4L * 1024 * 1024
    private val blobPath = Regex("brush-assets/sha256/[0-9a-f]{64}\\.png")
    private val shaPattern = Regex("[0-9a-f]{64}")
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private const val HEX = "0123456789abcdef"

    /** Optional archive-time full decode, after all encoded/header budgets; no Android dependency. */
    fun interface PngVerifier { fun verify(bytes: ByteArray, width: Int, height: Int) }
    private data class Ref(val path: String, val hash: String, val width: Int, val height: Int)

    fun isBlobEntry(name: String): Boolean = blobPath.matches(name)
    fun isAssetEntry(name: String): Boolean = name.startsWith("brush-assets/")
    private fun entry(hash: String) = "brush-assets/sha256/$hash.png"

    /** Complete pre-write validation. Legacy empty bundles produce no new entries. */
    fun prepare(brushes: Map<String, BrushPreset>, assets: Map<String, FrozenBrushPngs>,
                retainEntries: Boolean = true, verifier: PngVerifier? = null): List<Pair<String, ByteArray>> {
        try { FrozenBrushRegistry.validateAssets(brushes, assets) }
        catch (e: BrushException) { refuse(e.message ?: "Frozen brush images disagree") }
        if (assets.isEmpty()) return emptyList()
        need(assets.size <= 256, "Too many frozen image brushes")
        val payloads = linkedMapOf<String, ByteArray>()
        val sizes = HashMap<String, Pair<Int, Int>>()
        val seen = HashSet<String>()
        val manifest = linkedMapOf<String, List<Ref>>()
        var total = 0L
        for (id in assets.keys.sorted()) {
            val pngs = assets.getValue(id)
            need(pngs.paths.size in 1..32 && pngs.encodedBytes <= MAX_SET_BYTES,
                "Frozen sampled set exceeds its image count or encoded byte budget")
            val refs = ArrayList<Ref>()
            for (path in pngs.paths.sorted()) {
                val bytes = pngs.requirePng(path)
                total += bytes.size
                need(total <= MAX_ARCHIVE_BYTES, "Frozen brush images exceed the 64 MiB archive budget")
                val size = pngStructure(bytes)
                val hash = sha(bytes)
                if (seen.add(hash)) {
                    sizes[hash] = size
                    if (retainEntries || verifier != null) payloads[hash] = bytes
                }
                refs += Ref(path, hash, size.first, size.second)
            }
            checkSetPixels(brushes.getValue(id), refs)
            manifest[id] = refs
        }
        val index = encodeManifest(manifest)
        need(index.size <= MAX_MANIFEST_BYTES, "Frozen image manifest exceeds its byte budget")
        need(total + index.size <= MAX_ARCHIVE_BYTES, "Frozen images and manifest exceed the 64 MiB archive budget")
        for ((hash, bytes) in payloads) verify(verifier, bytes, sizes.getValue(hash))
        if (!retainEntries) return emptyList()
        return listOf(MANIFEST_ENTRY to index) + payloads.keys.sorted().map { entry(it) to payloads.getValue(it) }
    }

    /** Exact document-owned references; never consult an installed library. */
    fun reopen(manifest: ByteArray?, payloads: Map<String, ByteArray>, brushes: Map<String, BrushPreset>,
               verifier: PngVerifier? = null): Map<String, FrozenBrushPngs> {
        val required = brushes.mapValues { BrushAssetPaths.required(it.value).toSet() }.filterValues { it.isNotEmpty() }
        if (manifest == null) {
            need(required.isEmpty() && payloads.isEmpty(), "Frozen sampled images have no archive manifest")
            return emptyMap()
        }
        need(manifest.size <= MAX_MANIFEST_BYTES, "Frozen image manifest exceeds its byte budget")
        val refs = decodeManifest(manifest)
        need(refs.keys == required.keys, "Frozen image brush identities are missing or unreferenced")
        val hashes = refs.values.flatten().map { it.hash }.toSet()
        need(payloads.keys == hashes.map(::entry).toSet(), "Frozen image payloads are missing or unreferenced")
        var total = 0L
        for ((id, list) in refs) {
            need(list.map { it.path }.toSet() == required.getValue(id), "Frozen image paths disagree with brush $id")
            need(list.size <= 32, "Too many frozen images in brush $id")
            val setBytes = list.sumOf { payloads.getValue(entry(it.hash)).size.toLong() }
            need(setBytes <= MAX_SET_BYTES, "Frozen sampled set exceeds its encoded byte budget")
            total += setBytes
            need(total <= MAX_ARCHIVE_BYTES, "Frozen brush images exceed the 64 MiB archive budget")
            checkSetPixels(brushes.getValue(id), list)
        }
        need(total + manifest.size <= MAX_ARCHIVE_BYTES, "Frozen images and manifest exceed the 64 MiB archive budget")
        need(payloads.values.all { it.size <= MAX_PNG_BYTES }, "Frozen PNG exceeds its encoded byte budget")
        for (hash in hashes) {
            val bytes = payloads.getValue(entry(hash))
            need(bytes.size <= MAX_PNG_BYTES && sha(bytes) == hash, "Frozen PNG exceeds its byte budget or content hash disagrees")
            val size = pngStructure(bytes)
            for (ref in refs.values.flatten().filter { it.hash == hash })
                need(ref.width == size.first && ref.height == size.second, "Frozen PNG dimensions disagree with its manifest")
            verify(verifier, bytes, size)
        }
        val out = refs.mapValues { (_, list) ->
            try { FrozenBrushPngs.snapshot(list.associate { it.path to payloads.getValue(entry(it.hash)) }) }
            catch (e: IllegalArgumentException) { refuse(e.message ?: "Frozen images cannot be retained") }
        }
        try { FrozenBrushRegistry.validateAssets(brushes, out) }
        catch (e: BrushException) { refuse(e.message ?: "Frozen brush images disagree") }
        return out
    }

    /** Complete chunk/CRC framing without inflating pixels or allocating from a PNG declaration. */
    private fun pngStructure(bytes: ByteArray): Pair<Int, Int> {
        need(bytes.size in 33..MAX_PNG_BYTES && bytes.copyOfRange(0, 8).contentEquals(signature), "Frozen image is not a bounded PNG")
        fun u32(at: Int): Long = (0..3).fold(0L) { n, i -> (n shl 8) or (bytes[at + i].toLong() and 255) }
        need(u32(8) == 13L && bytes.copyOfRange(12, 16).decodeToString() == "IHDR", "Frozen PNG has no canonical IHDR")
        val w = u32(16); val h = u32(20)
        need(w in 1..MAX_DIMENSION.toLong() && h in 1..MAX_DIMENSION.toLong() && w * h <= MAX_SET_PIXELS,
            "Frozen PNG dimensions exceed the sampled-set budget")
        var cursor = 8
        var ihdr = false; var idat = false; var iend = false
        while (cursor < bytes.size) {
            need(bytes.size - cursor >= 12, "Truncated frozen PNG chunk")
            val length = u32(cursor)
            val end = cursor.toLong() + 12 + length
            need(end <= bytes.size, "Truncated frozen PNG payload")
            val kind = bytes.copyOfRange(cursor + 4, cursor + 8).decodeToString()
            need(kind.length == 4 && kind.all { it in 'A'..'Z' || it in 'a'..'z' }, "Invalid frozen PNG chunk")
            val crc = CRC32().apply { update(bytes, cursor + 4, length.toInt() + 4) }.value
            need(crc == u32(cursor + 8 + length.toInt()), "Frozen PNG checksum mismatch")
            when (kind) {
                "IHDR" -> { need(!ihdr && cursor == 8 && length == 13L, "Duplicate or misplaced PNG header"); ihdr = true }
                "IDAT" -> { need(ihdr && !iend, "Misplaced PNG image data"); idat = true }
                "IEND" -> { need(ihdr && idat && length == 0L && end == bytes.size.toLong(), "Invalid PNG ending"); iend = true }
            }
            cursor = end.toInt()
        }
        need(iend, "Frozen PNG has no complete image ending")
        return w.toInt() to h.toInt()
    }

    private fun encodeManifest(refs: Map<String, List<Ref>>): ByteArray = buildJsonObject {
        put("version", 1)
        put("brushes", buildJsonArray {
            for ((id, images) in refs) add(buildJsonObject {
                put("id", id)
                put("images", buildJsonArray { for (r in images) add(buildJsonObject {
                    put("path", r.path); put("sha256", r.hash); put("width", r.width); put("height", r.height)
                }) })
            })
        })
    }.toString().encodeToByteArray()

    private fun decodeManifest(bytes: ByteArray): Map<String, List<Ref>> = try {
        val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
            ?: refuse("Frozen image manifest is not an object")
        need(root.keys == setOf("version", "brushes") && integer(root["version"]) == 1, "Unsupported frozen image manifest")
        val brushes = root["brushes"] as? JsonArray ?: refuse("Frozen image brush list is missing")
        need(brushes.size <= 256, "Too many frozen image brushes")
        val out = linkedMapOf<String, List<Ref>>()
        for (value in brushes) {
            val brush = value as? JsonObject ?: refuse("Invalid frozen image brush")
            need(brush.keys == setOf("id", "images"), "Unknown frozen image brush fields")
            val id = text(brush["id"])
            need(id !in out, "Duplicate frozen image brush")
            val images = brush["images"] as? JsonArray ?: refuse("Frozen image list is missing")
            need(images.size in 1..32, "Invalid frozen image count")
            val list = images.map { v ->
                val image = v as? JsonObject ?: refuse("Invalid frozen image reference")
                need(image.keys == setOf("path", "sha256", "width", "height"), "Unknown frozen image fields")
                val ref = Ref(text(image["path"]), text(image["sha256"]), integer(image["width"]), integer(image["height"]))
                need(SampledTips.safePath(ref.path) && shaPattern.matches(ref.hash) && ref.width in 1..MAX_DIMENSION && ref.height in 1..MAX_DIMENSION,
                    "Invalid frozen image hash or dimensions")
                ref
            }
            need(list.map { it.path }.distinct().size == list.size, "Duplicate frozen image path")
            out[id] = list
        }
        out
    } catch (e: JbArchiveException) { throw e }
      catch (e: Exception) { refuse("Frozen image manifest cannot be read: ${e.message}") }

    private fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: refuse("Frozen image manifest needs text")
    private fun integer(value: JsonElement?): Int = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        ?: refuse("Frozen image manifest needs an integer")
    private fun checkSetPixels(brush: BrushPreset, refs: List<Ref>) {
        val byPath = refs.associateBy { it.path }
        val ordered = BrushAssetPaths.ordered(brush)
        need(ordered.sumOf { path -> byPath.getValue(path).let { it.width.toLong() * it.height } } <= MAX_SET_PIXELS,
            "Frozen sampled set exceeds its ordered image pixel budget")
    }
    private fun verify(verifier: PngVerifier?, bytes: ByteArray, size: Pair<Int, Int>) {
        try { verifier?.verify(bytes.copyOf(), size.first, size.second) }
        catch (e: Exception) { refuse("Frozen PNG cannot be decoded: ${e.message}") }
    }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).let { hash ->
        CharArray(hash.size * 2).also { out -> for (i in hash.indices) {
            val v = hash[i].toInt() and 255; out[i * 2] = HEX[v ushr 4]; out[i * 2 + 1] = HEX[v and 15]
        } }.concatToString()
    }
    private fun need(value: Boolean, message: String) { if (!value) refuse(message) }
    private fun refuse(message: String): Nothing = throw JbArchiveException(message)
}
