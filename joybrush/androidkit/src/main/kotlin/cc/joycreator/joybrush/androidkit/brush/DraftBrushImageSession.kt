package cc.joycreator.joybrush.androidkit.brush

import cc.joycreator.joybrush.androidkit.gl.PngSamplePreflight
import cc.joycreator.joybrush.androidkit.io.FrozenBrushAssets
import cc.joycreator.joybrush.core.brush.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class BrushImageDraftException(message: String) : Exception(message)

enum class BrushImageSlot { sampled, dual, tipTexture }

/** Immutable checked pair. Recipe getters detach all nested collections; PNG bytes stay defensively owned. */
class BrushImagePair internal constructor(private val json: String, val resources: FrozenBrushPngs) {
    val recipe: BrushPreset get() = BrushJson.decodeChecked(json)
}

/** Opaque request identity plus immutable source snapshot; a late reply cannot replace a newer edit. */
class BrushImagePickRequest internal constructor(internal val owner: Any, internal val revision: Long,
    val slot: BrushImageSlot, internal val base: BrushImagePair)

class PreparedBrushImageChange internal constructor(internal val owner: Any, internal val revision: Long,
    internal val request: BrushImagePickRequest?, val pair: BrushImagePair, orderedPaths: List<String>, displayNames: List<String>) {
    private val paths = orderedPaths.toList()
    private val names = displayNames.toList()
    val selectedPaths: List<String> get() = paths.toList()
    val displayNames: List<String> get() = names.toList()
}

/**
 * One current pair and at most one pending pair; no history/cache of earlier image edits.
 * Host performs preparation on an IO worker and calls acceptance synchronously on its model thread.
 */
class DraftBrushImageSession(initial: BrushPreset, resources: FrozenBrushPngs,
    private val verifier: FrozenBrushAssets.PngVerifier? = null) {
    private val identity = Any()
    private var revision = 0L
    private var current = BrushImageAdmission.admit(initial, resources, verifier)
    private var pending: PreparedBrushImageChange? = null
    private var activeRequest: BrushImagePickRequest? = null
    private var applying = false

    @Synchronized fun current(): BrushImagePair = current
    @Synchronized fun pending(): PreparedBrushImageChange? = pending

    @Synchronized fun requestPick(slot: BrushImageSlot): BrushImagePickRequest {
        mutable()
        if (slot == BrushImageSlot.tipTexture) refuse("Tip texture image freezing/import still needs its grain resource adapter")
        return BrushImagePickRequest(identity, revision, slot, current).also { activeRequest = it }
    }

    /** Ordinary model edits reuse current bytes, pruning resources no longer required by the recipe. */
    @Synchronized fun prepareEdit(recipe: BrushPreset): PreparedBrushImageChange {
        mutable()
        val admitted = BrushImageAdmission.admit(recipe, current.resources, verifier)
        return PreparedBrushImageChange(identity, revision, null, admitted, emptyList(), emptyList()).also {
            pending = it; activeRequest = null
        }
    }

    /** Does no file IO or decoding; the loader already admitted the immutable candidate pair. */
    @Synchronized fun stage(loaded: PreparedBrushImageChange): PreparedBrushImageChange {
        mutable()
        if (loaded.owner !== identity || loaded.revision != revision || loaded.request !== activeRequest || activeRequest == null)
            refuse("This image selection is stale; choose images again for the current mode")
        pending = loaded; activeRequest = null
        return loaded
    }

    /** Callback false/throw preserves current AND the sole pending candidate. Never publishes bytes first. */
    @Synchronized fun acceptPending(change: PreparedBrushImageChange, onChange: (BrushImagePair) -> Boolean): Boolean {
        mutable()
        if (pending !== change || change.owner !== identity || change.revision != revision)
            refuse("This prepared image edit is no longer pending")
        applying = true
        try {
            if (!onChange(change.pair)) return false
            current = change.pair; pending = null; activeRequest = null; revision++
            return true
        } finally { applying = false }
    }

    @Synchronized fun discardPending() { mutable(); pending = null; activeRequest = null }
    private fun mutable() { if (applying) refuse("The host must not reenter image editing while accepting a pair") }
    private fun refuse(message: String): Nothing = throw BrushImageDraftException(message)
}

internal object BrushImageAdmission {
    const val MAX_JSON_BYTES = 256 * 1024
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    /** All encoded/header/ordered pixel reservations finish before the first optional full decoder call. */
    fun admit(recipe: BrushPreset, resources: FrozenBrushPngs, verifier: FrozenBrushAssets.PngVerifier?): BrushImagePair {
        val checked = checked(recipe)
        val required = BrushAssetPaths.required(checked)
        if (resources.encodedBytes > FrozenBrushAssets.MAX_SET_BYTES)
            refuse("The source frozen image owner exceeds the complete encoded bundle budget")
        val retained = if (resources.paths == required.toSet()) resources else FrozenBrushPngs.snapshot(
            required.associateWith { resources.requirePng(it) })
        val headers = required.associateWith { PngSamplePreflight.inspect(retained.requirePng(it)) }
        var pixels = 0L
        for (path in BrushAssetPaths.ordered(checked)) {
            val header = headers.getValue(path)
            val count = header.width.toLong() * header.height
            if (count > FrozenBrushAssets.MAX_SET_PIXELS - pixels) refuse("The complete brush image closure exceeds its decoded pixel budget")
            pixels += count
        }
        val assets = if (required.isEmpty()) emptyMap() else mapOf(checked.id to retained)
        FrozenBrushAssets.prepare(mapOf(checked.id to checked), assets, retainEntries = false, verifier = verifier)
        return BrushImagePair(BrushJson.encode(checked), retained)
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun checked(recipe: BrushPreset): BrushPreset {
        if (recipe.version !in 1..BRUSH_VERSION) refuse("The source brush version is unsupported")
        if (recipe.engine != "stamp") refuse("This draft image session requires a stamp brush")
        if (recipe.tip.source != "procedural" && recipe.tip.sampled == null)
            refuse("The primary imported tip must be materialized before editing its frozen images")
        if (!PackagedBrushGrains.supported(recipe.tipTexture) || !PackagedBrushGrains.supported(recipe.paperGrain))
            refuse("External grain images still need a separate frozen resource adapter")
        val out = ByteArrayOutputStream(4096)
        var count = 0
        val sink = object : OutputStream() {
            override fun write(value: Int) { if (count >= MAX_JSON_BYTES) refuse("The brush model exceeds its JSON budget");out.write(value);count++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length < 0 || length > MAX_JSON_BYTES-count) refuse("The brush model exceeds its JSON budget")
                out.write(bytes,offset,length);count+=length
            }
        }
        json.encodeToStream(BrushPreset.serializer(), recipe, sink)
        // Detach only after the bounded stream pass, then let the standard writer raise the
        // version for newly added sampled/dual words before checked model validation.
        val detached = json.decodeFromString(BrushPreset.serializer(),out.toString(Charsets.UTF_8.name()))
        val canonical = BrushJson.encode(detached)
        if (canonical.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) refuse("The brush model exceeds its JSON budget")
        return BrushJson.decodeChecked(canonical)
    }
    private fun refuse(message: String): Nothing = throw BrushImageDraftException(message)
}
