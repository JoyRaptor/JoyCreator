package cc.joycreator.joybrush.core.brush

/** A brush-scoped, library-independent PNG lookup using the exact schema path spelling. */
fun interface BrushPngLookup { fun png(path: String): ByteArray? }

/**
 * Immutable encoded resources. Storage admission verifies PNG/hash/dimensions; the Android sampled
 * set resolver verifies/decode budgets and makes immutable masks. This type intentionally does not
 * decode or claim that an arbitrary supplied byte array is a valid PNG. No installed-library fallback.
 */
class FrozenBrushPngs private constructor(private val images: Map<String, ByteArray>) : BrushPngLookup {
    data class Limits(val maxImages: Int = 32, val maxBytesPerImage: Int = 16 * 1024 * 1024,
        val maxTotalBytes: Long = 32L * 1024 * 1024, val maxPathChars: Int = 256)

    val paths: Set<String> get() = images.keys.toSet()
    val encodedBytes: Long get() = images.values.sumOf { it.size.toLong() }
    override fun png(path: String): ByteArray? = images[path]?.copyOf()
    fun requirePng(path: String): ByteArray = png(path)
        ?: throw IllegalArgumentException("The frozen brush is missing image $path")

    /** Compare unique storage identities; repeated entries in a sequence still keep their order in the schema. */
    fun requirePaths(orderedPaths: List<String>) {
        require(images.keys == orderedPaths.toSet()) { "The frozen brush image list is incomplete or has unreferenced payloads" }
    }

    /** Frozen identity compares actual bytes, not array identity or the installed file name. */
    fun sameBytes(other: FrozenBrushPngs): Boolean = images.keys == other.images.keys &&
        images.all { (path, bytes) -> bytes.contentEquals(other.images.getValue(path)) }

    companion object {
        fun empty(): FrozenBrushPngs = FrozenBrushPngs(emptyMap())
        fun snapshot(images: Map<String, ByteArray>, limits: Limits = Limits()): FrozenBrushPngs {
            require(images.size <= limits.maxImages) { "The frozen brush has too many images" }
            var total = 0L
            for ((path, bytes) in images) {
                require(path.length in 1..limits.maxPathChars && SampledTips.safePath(path)) {
                    "Unsafe frozen brush image path: $path"
                }
                require(bytes.isNotEmpty() && bytes.size <= limits.maxBytesPerImage) { "The frozen brush image is empty or exceeds its encoded byte limit" }
                total += bytes.size.toLong()
                require(total <= limits.maxTotalBytes) { "The frozen brush images exceed their encoded byte limit" }
            }
            // Budget ALL borrowed arrays before copying any of them.
            return FrozenBrushPngs(images.keys.sorted().associateWith { images.getValue(it).copyOf() })
        }
    }
}
