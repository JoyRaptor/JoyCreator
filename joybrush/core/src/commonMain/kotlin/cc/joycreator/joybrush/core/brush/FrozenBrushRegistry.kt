package cc.joycreator.joybrush.core.brush

/**
 * Document-owned brush definitions. A library id is never an instruction to replace a saved copy.
 * All operations validate the whole supplied map before returning anything usable by replay.
 * JSON round trips detach every nested curve, list and extension map from the caller.
 */
object FrozenBrushRegistry {
    private const val COMPARISON_ID = "frozen-definition"
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    /** Validate loaded copies, including their map identities, and return detached definitions. */
    fun validate(entries: Map<String, BrushPreset>): Map<String, BrushPreset> =
        snapshots(entries).mapValues { (_, json) -> BrushJson.decodeChecked(json) }

    /** Resolve exclusively from document copies; a missing id has no library fallback. */
    fun resolve(entries: Map<String, BrushPreset>, id: String): BrushPreset? {
        val saved = snapshots(entries)
        return saved[id]?.let { BrushJson.decodeChecked(it) }
    }

    /**
     * Reuse the first identical definition in sorted id order, comparing every field except id.
     * Otherwise request one fresh id. A collision refuses the operation rather than overwriting
     * artwork or repeatedly calling an allocator that may be broken. No input map is mutated.
     */
    fun freeze(
        entries: Map<String, BrushPreset>,
        preset: BrushPreset,
        newId: () -> String,
    ): FrozenBrushResult = freezeWithAssets(entries, emptyMap(), preset, FrozenBrushPngs.empty(), newId)

    /** New sampled definitions and their exact images enter the drawing as one immutable result. */
    fun freezeWithAssets(
        entries: Map<String, BrushPreset>,
        knownAssets: Map<String, FrozenBrushPngs>,
        preset: BrushPreset,
        candidatePngs: FrozenBrushPngs,
        newId: () -> String,
    ): FrozenBrushResult {
        val saved = snapshots(entries)
        validateAssets(entries, knownAssets)
        val problems = BrushValidate.validate(preset)
        if (problems.isNotEmpty()) {
            throw BrushException("brush cannot be frozen: " + problems.joinToString("; "))
        }
        val candidate = BrushJson.decodeChecked(BrushJson.encode(preset))
        try { candidatePngs.requirePaths(BrushAssetPaths.required(candidate)) }
        catch (e: IllegalArgumentException) { throw BrushException(e.message ?: "Frozen candidate images are incomplete") }
        val definition = comparison(candidate)
        for (id in saved.keys.sorted()) {
            if (comparison(BrushJson.decodeChecked(saved.getValue(id))) == definition &&
                (knownAssets[id] ?: FrozenBrushPngs.empty()).sameBytes(candidatePngs)) {
                return FrozenBrushResult(id, saved.getValue(id), saved, emptyMap(), knownAssets)
            }
        }
        val id = newId()
        if (!SAFE_ID.matches(id) || ".." in id) {
            throw BrushException("frozen brush id must be 1..128 safe letters, digits, dots, underscores or hyphens")
        }
        if (id in saved) throw BrushException("frozen brush id already exists: $id")
        val json = BrushJson.encode(candidate.copy(id = id))
        val added = mapOf(id to json)
        val images = if (candidatePngs.paths.isEmpty()) knownAssets else knownAssets + (id to candidatePngs)
        return FrozenBrushResult(id, json, saved + added, added, images)
    }

    /** Structural resource completeness; PNG decoding remains the resolved-set boundary's job. */
    fun validateAssets(entries: Map<String, BrushPreset>, assets: Map<String, FrozenBrushPngs>) {
        val required = entries.mapValues { BrushAssetPaths.required(it.value) }.filterValues { it.isNotEmpty() }
        if (assets.keys != required.keys) throw BrushException("Frozen brush images are missing or unreferenced")
        for ((id, paths) in required) try { assets.getValue(id).requirePaths(paths) }
        catch (e: IllegalArgumentException) { throw BrushException("Frozen brush $id: ${e.message}") }
    }

    private fun comparison(preset: BrushPreset): String =
        BrushJson.encode(preset.copy(id = COMPARISON_ID))

    private fun snapshots(entries: Map<String, BrushPreset>): Map<String, String> {
        val saved = linkedMapOf<String, String>()
        for ((id, preset) in entries) {
            if (id.isBlank() || id != preset.id) {
                throw BrushException("frozen brush map key does not match its definition: $id")
            }
            // Validate before encoding: writing may upgrade a version that was invalid on load.
            val problems = BrushValidate.validate(preset)
            if (problems.isNotEmpty()) {
                throw BrushException("frozen brush $id cannot be used: " + problems.joinToString("; "))
            }
            val json = BrushJson.encode(preset)
            BrushJson.decodeChecked(json)
            saved[id] = json
        }
        return saved
    }
}

/**
 * An immutable freeze outcome. [entries] is the complete replacement map; [newEntries] is only
 * the additions (empty on reuse). Each getter returns detached objects, including byte arrays,
 * so even a caller casting a read-only collection to a mutable one cannot modify this outcome.
 */
class FrozenBrushResult internal constructor(
    val id: String,
    private val json: String,
    entries: Map<String, String>,
    newEntries: Map<String, String>,
    assets: Map<String, FrozenBrushPngs> = emptyMap(),
) {
    private val saved = entries.toMap()
    private val added = newEntries.toMap()
    private val imageCopies = assets.toMap()

    val preset: BrushPreset get() = BrushJson.decodeChecked(json)
    val bytes: ByteArray get() = json.encodeToByteArray()
    val entries: Map<String, BrushPreset>
        get() = saved.mapValues { (_, value) -> BrushJson.decodeChecked(value) }
    val newEntries: Map<String, BrushPreset>
        get() = added.mapValues { (_, value) -> BrushJson.decodeChecked(value) }
    /** Values are immutable and copy their bytes outward; the map is detached on every access. */
    val assets: Map<String, FrozenBrushPngs> get() = imageCopies.toMap()
}
