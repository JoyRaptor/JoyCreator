package cc.joycreator.joybrush.androidkit.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import cc.joycreator.joybrush.core.paper.PaperCatalogues
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Detached diagnostic metadata; estimates RGBA8 storage including every mip level. */
internal data class PaperTextureCacheSnapshot(
    val handles: Set<Int>,
    val handlesByAsset: Map<String, Int>,
    val estimatedMipBytes: Long,
    val collectionFailures: Long,
    val lastCollectionFailure: String?,
) {
    val count: Int get() = handles.size
}

/**
 * The grain pictures a brush points at (JB-1.05c): `cloud_256.png` and friends from the packaged
 * `/joybrush/assets/grain/` and paper surfaces from `/joybrush/assets/paper/`. GL THREAD ONLY, like [GlPaintEngine], which owns one.
 *
 * Every texture is REPEAT with a mip chain (a paper grain seen from far away must average, not shimmer)
 * Tip pictures use RED for height; paper surfaces retain RGBA slopes, height and height².
 *
 * A texture name only means something on the context that minted it, so after a context loss
 * [forget] drops the names WITHOUT deleting them (deleting a name the new context may already have
 * handed to somebody else would delete somebody else's texture). [release] is the deleting path.
 */
class GrainTextures(
    private val loadBitmap: (String, String) -> Bitmap? = ::loadPackagedBitmap,
    private val deleteTextures: (IntArray) -> Unit = { GLES30.glDeleteTextures(it.size, it, 0) },
) {
    private val byName = HashMap<String, Int>()
    private val failed = HashSet<String>()
    private val sizes = HashMap<String, Int>()
    private val heights = HashMap<String, Int>()
    private val paperPins = HashMap<Any, Set<Int>>()
    private var paperCollectionFailures = 0L
    private var lastPaperCollectionFailure: String? = null

    /** GL thread. A rollback scope may restore these borrowed names after adopting another paper.
     * Closing is idempotent and does no GL work; forget clears old-context scopes without deletion. */
    internal fun pinPaperTextures(handles: Collection<Int>): AutoCloseable {
        val token = Any()
        paperPins[token] = handles.filter { it != 0 }.toSet()
        return AutoCloseable { paperPins.remove(token); Unit }
    }

    /**
     * Retain every live borrower, reclaim only unused paper-folder pictures. This is not a hard
     * memory cap: genuinely live borrowers may exceed any soft target. Brush grains and placeholder
     * are never candidates. Snapshot ALL borrowers before deleting anything; failed enumeration
     * conservatively retains the entire cache. No cache ownership inventory belongs in [borrowed].
     */
    internal fun collectUnusedPaperTextures(borrowed: () -> Set<Int>): Int {
        val (keys, names) = try {
            val keep = borrowed().toMutableSet().also { live ->
                paperPins.values.forEach { live.addAll(it) }
                live.add(placeholder)
                byName.forEach { (key, texture) -> if (!key.startsWith("paper/")) live.add(texture) }
            }
            val retired = byName.filter { (key, texture) -> key.startsWith("paper/") && texture !in keep }
            // Prepare all allocation before GL deletion, including the later lookup-removal loop.
            retired.keys.toTypedArray() to retired.values.distinct().toIntArray()
        } catch (failure: Throwable) {
            notePaperCollectionFailure(failure)
            return 0
        }
        if (keys.isEmpty()) return 0
        val confirmed = try { deleteTextures(names); true } catch (failure: Throwable) {
            // Adoption already succeeded. A throwing driver/seam must not turn it into a partial
            // operation, or leave possibly deleted names available for future lookup. These were
            // all unborrowed: forget their lookups even if deletion was only partly performed.
            // GPU release is unconfirmed; diagnostics must fail acceptance until context renewal.
            notePaperCollectionFailure(failure)
            false
        }
        for (key in keys) { byName.remove(key); sizes.remove(key); heights.remove(key) }
        return if (confirmed) keys.size else 0
    }

    private fun notePaperCollectionFailure(failure: Throwable) {
        paperCollectionFailures++
        lastPaperCollectionFailure = failure.javaClass.simpleName
    }

    internal fun paperCacheSnapshot(): PaperTextureCacheSnapshot {
        val entries = byName.filterKeys { it.startsWith("paper/") }.entries.distinctBy { it.value }
        val bytes = entries.sumOf { (key, _) ->
            var w = (sizes[key] ?: 1).coerceAtLeast(1)
            var h = (heights[key] ?: w).coerceAtLeast(1)
            var total = 0L
            while (true) {
                total += w.toLong() * h * 4L
                if (w == 1 && h == 1) break
                w = (w / 2).coerceAtLeast(1); h = (h / 2).coerceAtLeast(1)
            }
            total
        }
        return PaperTextureCacheSnapshot(java.util.Collections.unmodifiableSet(entries.map { it.value }.toSet()),
            java.util.Collections.unmodifiableMap(byName.filterKeys { it.startsWith("paper/") }),
            bytes, paperCollectionFailures, lastPaperCollectionFailure)
    }

    fun sizeFor(assetName: String, folder: String = folderFor(assetName)): Int = sizes["$folder/$assetName"] ?: 1

    /** A 1×1 white texel. Bound to a grain sampler whose grain is OFF, so a sampler never points at
     *  something else — above all never at the framebuffer's own attachment, which would be a feedback loop. */
    var placeholder = 0
        private set

    fun create() {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        placeholder = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, placeholder)
        val white = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        white.put(byteArrayOf(-1, -1, -1, -1)).rewind()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, white)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * The texture for a packaged grain picture, loaded the first time it is asked for; or null if the
     * picture is missing or unreadable (remembered, so a broken file is not re-read on every stroke).
     * The caller then draws that stroke WITHOUT that grain — a missing picture must never turn into a
     * "white" one, which would paint everywhere.
     */
    fun textureFor(assetName: String, folder: String = folderFor(assetName)): Int? {
        val key = "$folder/$assetName"
        byName[key]?.let { return it }
        if (key in failed) return null
        val bmp = try { loadBitmap(folder, assetName) } catch (e: Exception) { null }
        if (bmp == null) { failed.add(key); return null }
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val bytes = expandSurface(assetName, rgbaBytes(pixels), bmp.width, bmp.height)
        sizes[key] = bmp.width
        heights[key] = bmp.height
        val upload = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        upload.put(bytes).rewind()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, bmp.width, bmp.height,
            0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, upload)
        bmp.recycle()
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        byName[key] = t[0]
        return t[0]
    }

    /** Context lost: the names are dead. Drop them; delete nothing. */
    fun forget() {
        byName.clear()
        failed.clear()
        sizes.clear()
        heights.clear()
        paperPins.clear()
        paperCollectionFailures = 0L; lastPaperCollectionFailure = null
        placeholder = 0
    }

    /** Deletes every texture this owns. */
    fun release() {
        val all = ArrayList<Int>(byName.values)
        if (placeholder != 0) all.add(placeholder)
        if (all.isNotEmpty()) deleteTextures(all.toIntArray())
        forget()
    }

    /** Borrowed ownership inventory; no GL calls and no asset loading. */
    internal fun heldTextureHandles(): Set<Int> = (byName.values + listOf(placeholder)).filter { it != 0 }.toSet()

    /** How many texture names are held (a test seam, like [GlPaintEngine.heldTextureNames]). */
    internal fun heldNames(): Int = byName.size + (if (placeholder != 0) 1 else 0)

    companion object {
        private val surfaceEntries: Map<String, cc.joycreator.joybrush.core.paper.SurfaceEntry> by lazy {
            val stream = GrainTextures::class.java.getResourceAsStream("/joybrush/assets/paper/catalogue.json")
            stream?.use { PaperCatalogues.parse(it.bufferedReader().readText()).surfaces.associateBy { entry -> entry.file } }
                ?: emptyMap()
        }

        private val surfaceFiles: Set<String> by lazy {
            val stream = GrainTextures::class.java.getResourceAsStream("/joybrush/assets/paper/catalogue.json")
            stream?.use { PaperCatalogues.parse(it.bufferedReader().readText()).surfaces.flatMap { entry -> listOfNotNull(entry.file, entry.fluid) }.toSet() }
                ?: emptySet()
        }

        /** The catalogue determines surface membership; arbitrary filename prefixes mean nothing. */
        internal fun folderFor(name: String): String = if (name in surfaceFiles) "paper" else "grain"

        /**
         * A surface whose file holds only its height (2026-10-06 photo papers) is packed here, once, at load — the same
         * bytes the PC tool would have stored ([cc.joycreator.joybrush.core.paper.SurfaceMaps.expand]). Every other file
         * passes through unchanged.
         */
        fun expandSurface(file: String, rgba: ByteArray, w: Int, h: Int): ByteArray {
            val entry = surfaceEntries[file] ?: return rgba
            return cc.joycreator.joybrush.core.paper.SurfaceMaps.expand(entry, rgba, w, h)
        }

        /** Straight RGBA data: alpha never multiplies the other three channels. */
        fun rgbaBytes(argb: IntArray): ByteArray = ByteArray(argb.size * 4).also { out ->
            for (i in argb.indices) {
                val p = argb[i]
                out[i * 4] = (p ushr 16).toByte()
                out[i * 4 + 1] = (p ushr 8).toByte()
                out[i * 4 + 2] = p.toByte()
                out[i * 4 + 3] = (p ushr 24).toByte()
            }
        }
        /** Reads grain or surface PNGs from their packaged resource folder. The name is a plain file name (GrainMath.assetNameFor). */
        fun loadPackagedBitmap(folder: String, name: String): Bitmap? {
            val stream = GrainTextures::class.java.getResourceAsStream("/joybrush/assets/$folder/$name") ?: return null
            return stream.use {
                val o = BitmapFactory.Options().apply {
                    inScaled = false
                    // Surface alpha is height² data: never multiply the slopes/height by it.
                    inPremultiplied = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(it, null, o)
            }
        }
    }
}
