package cc.joycreator.joybrush.androidkit.brush

import cc.joycreator.joybrush.androidkit.io.FrozenBrushAssets
import cc.joycreator.joybrush.core.brush.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** A new mode can be visible after rename even if its final directory fsync fails. Never retry automatically. */
class UserModeStoreException(message: String, val publishedId: String? = null, cause: Throwable? = null) :
    Exception(message, cause)

/** Platform durability boundary. Android may supply directory fsync through its Os adapter. */
interface UserModePersistence {
    fun syncDirectory(directory: Path)
    /** Called under the store's root lock, with a checked-absent sibling destination in app-private storage. */
    fun publishNew(staging: Path, destination: Path)

    object Nio : UserModePersistence {
        override fun syncDirectory(directory: Path) {
            FileChannel.open(directory, READ).use { it.force(true) }
        }
        override fun publishNew(staging: Path, destination: Path) {
            // ATOMIC_MOVE itself may replace a target on some providers. The root lock excludes
            // every cooperating writer while this absence check and move execute. This is not
            // a publication primitive for a directory with uncooperative external writers.
            if (Files.exists(destination, NOFOLLOW_LINKS)) throw FileAlreadyExistsException(destination.toString())
            Files.move(staging, destination, ATOMIC_MOVE) // Root-lock-protected absence; no non-atomic fallback.
        }
    }
}

/** Bounded metadata handle. Every preset getter is detached; no mutable collections escape. */
class UserModeDefinition internal constructor(val id: String, val name: String, val folder: String,
                                               internal val json: String) {
    val preset: BrushPreset get() = BrushJson.decodeChecked(json)
}

class UserModeCatalog internal constructor(definitions: List<UserModeDefinition>, refused: List<Refused>,
                                           val modeFolders: Int, val metadataBytes: Long) {
    data class Refused(val folder: String, val reason: String)
    val definitions: List<UserModeDefinition> = definitions.toList()
    val refused: List<Refused> = refused.toList()
}

class UserModeSelection internal constructor(val definition: UserModeDefinition, val images: FrozenBrushPngs?) {
    val preset: BrushPreset get() = definition.preset
}

/**
 * Explicit Save As only: one UUID folder is the atomic publication unit; there is no mutable index,
 * overwrite, delete, install or UI operation. UI loads metadata, then selects one immutable PNG owner.
 * The supplied root is app-private/exclusively managed storage. Every store writer uses its root lock.
 */
class UserModeStore(
    root: Path,
    private val limits: Limits = Limits(),
    private val uuid: () -> UUID = UUID::randomUUID,
    private val persistence: UserModePersistence = UserModePersistence.Nio,
    private val verifier: FrozenBrushAssets.PngVerifier? = null,
    private val onRead: (Path) -> Unit = {},
    private val checkpoint: (Phase, Path) -> Unit = { _, _ -> },
) {
    data class Limits(val maxModes: Int = MAX_MODES, val maxModeJsonBytes: Int = MAX_MODE_JSON_BYTES,
                      val maxCatalogJsonBytes: Long = MAX_CATALOG_JSON_BYTES,
                      val maxRootEntries: Int = MAX_ROOT_ENTRIES) {
        init {
            require(maxModes in 1..MAX_MODES && maxModeJsonBytes in 1..MAX_MODE_JSON_BYTES)
            require(maxCatalogJsonBytes in 1..MAX_CATALOG_JSON_BYTES && maxRootEntries in 1..MAX_ROOT_ENTRIES)
        }
    }
    enum class Phase { AFTER_METADATA_SYNC, AFTER_ASSET_SYNC, BEFORE_PUBLISH, AFTER_PUBLISH, AFTER_ROOT_SYNC }
    private val requestedRoot = root.toAbsolutePath().normalize()
    private var registered: Map<String, UserModeDefinition> = emptyMap()
    private class Selected(val definition: UserModeDefinition, val images: FrozenBrushPngs)
    private class OwnedPath(val path: Path, val fileKey: Any?)
    private var selected: Selected? = null

    /** A malformed individual mode is refused while the other bounded metadata remains available. */
    fun loadDefinitions(): UserModeCatalog = synchronized(PROCESS_LOCK) {
        try {
            val catalog = if (!Files.exists(requestedRoot, NOFOLLOW_LINKS))
                UserModeCatalog(emptyList(), emptyList(), 0, 0) else scan(existingRoot())
            val next = catalog.definitions.associateBy { it.id }
            selected?.let { if (next[it.definition.id]?.json != it.definition.json) selected = null }
            registered = next
            catalog
        } catch (error: UserModeStoreException) { throw error }
        catch (error: Exception) { throw UserModeStoreException("User modes could not be listed", cause = error) }
    }

    /** The map is registered from validated folder/JSON pairs; caller IDs never become filesystem paths. */
    fun select(id: String): UserModeSelection = synchronized(PROCESS_LOCK) {
        val definition = registered[id] ?: refuse("Load the mode list before selecting this mode")
        val preset = definition.preset
        if (BrushAssetPaths.required(preset).isEmpty()) {
            selected = null // Release a formerly selected 32MiB bundle immediately.
            val folder = checkedModeFolder(existingRoot(), definition)
            requireChildren(folder, setOf(BRUSH_JSON))
            requireUnchangedMetadata(folder, definition)
            UserModeSelection(definition, null)
        } else UserModeSelection(definition, pngs(preset))
    }

    /** Install this as the View provider for registered user-mode IDs, including editable copies of a mode. */
    fun pngs(preset: BrushPreset): FrozenBrushPngs? = synchronized(PROCESS_LOCK) {
        val required = BrushAssetPaths.required(preset)
        if (required.isEmpty()) { selected = null; return@synchronized null }
        val definition = registered[preset.id] ?: refuse("This user mode is not registered")
        selected?.takeIf { it.definition.id == definition.id && it.definition.json == definition.json }?.let {
            if (it.images.paths != required.toSet()) refuse("The edited mode needs a different frozen image bundle")
            return@synchronized it.images
        }
        selected = null // No previous bundle remains retained while another one is loaded.
        try {
            val folder = checkedModeFolder(existingRoot(), definition)
            requireUnchangedMetadata(folder, definition)
            requireChildren(folder, setOf(BRUSH_JSON, "brush-assets"))
            val assets = folder.resolve("brush-assets"); requireDirectory(assets)
            requireChildren(assets, setOf("index.json", "sha256"))
            val manifest = readBounded(assets.resolve("index.json"), FrozenBrushAssets.MAX_MANIFEST_BYTES)
            val blobFolder = assets.resolve("sha256"); requireDirectory(blobFolder)
            val plans = ArrayList<Triple<String, Path, Long>>()
            var total = 0L
            Files.newDirectoryStream(blobFolder).use { entries -> for (file in entries) {
                if (plans.size >= 32) refuse("The user mode has too many PNG payloads")
                val name = file.fileName.toString()
                if (!BLOB_NAME.matches(name)) refuse("The user mode has an unrecognized PNG payload")
                val size = regularSize(file)
                if (size !in 1..FrozenBrushAssets.MAX_PNG_BYTES.toLong() || size > FrozenBrushAssets.MAX_SET_BYTES - total)
                    refuse("The user mode exceeds its encoded PNG budget")
                total += size; plans += Triple("brush-assets/sha256/$name", file, size)
            } }
            val payloads = plans.sortedBy { it.first }.associate { (name, file, size) ->
                name to readBounded(file, FrozenBrushAssets.MAX_PNG_BYTES, size)
            }
            val images = FrozenBrushAssets.reopen(manifest, payloads, mapOf(definition.id to definition.preset), verifier)
                .getValue(definition.id)
            images.requirePaths(required)
            selected = Selected(definition, images)
            images
        } catch (error: UserModeStoreException) { throw error }
        catch (error: Exception) { throw UserModeStoreException("The user mode's frozen images could not be loaded", cause = error) }
    }

    fun clearSelected() = synchronized(PROCESS_LOCK) { selected = null }
    fun heldPngBytes(): Long = synchronized(PROCESS_LOCK) { selected?.images?.encodedBytes ?: 0L }

    /** All JSON, exact image closure and image budgets are admitted before the first filesystem write. */
    fun saveAs(name: String, source: BrushPreset, images: FrozenBrushPngs = FrozenBrushPngs.empty()): UserModeDefinition =
        synchronized(PROCESS_LOCK) {
            try { saveAsChecked(name, source, images) }
            catch (error: UserModeStoreException) { throw error }
            catch (error: Exception) { throw UserModeStoreException("The new user mode could not be saved", cause = error) }
        }

    private fun saveAsChecked(name: String, source: BrushPreset, images: FrozenBrushPngs): UserModeDefinition {
        val words = name.trim()
        if (words.isBlank() || Character.codePointCount(words, 0, words.length) > 80 || words.any { Character.isISOControl(it) })
            refuse("Give the new mode a name of 1 to 80 characters")
        if (source.engine != "stamp") refuse("A Unibrush user mode needs the stamp engine")
        if (source.version !in 1..BRUSH_VERSION) refuse("The source brush version is unsupported")
        if (source.tip.source != "procedural" && source.tip.sampled == null)
            refuse("Materialize the imported image tip before saving a user mode")
        if (!PackagedBrushGrains.supported(source.tipTexture) || !PackagedBrushGrains.supported(source.paperGrain))
            refuse("An external grain image needs a persistent resource adapter before saving this mode")
        val folderName = uuid().toString()
        val id = ID_PREFIX + folderName
        // Attribution is preserved: only identity, the requested mode name and its family marker change.
        val candidate = BrushRecipeEdits.withRequiredVersion(source.copy(id = id, name = words,
            extensions = source.extensions + (FAMILY_KEY to FAMILY_USER)))
        val bytes = encodeBounded(candidate)
        if (bytes.size.toLong() > limits.maxCatalogJsonBytes) refuse("The new mode exceeds the metadata byte budget")
        val definition = definition(folderName, bytes)
        val frozen = definition.preset
        val bundles = if (BrushAssetPaths.required(frozen).isEmpty()) {
            images.requirePaths(emptyList()); emptyMap()
        } else mapOf(id to images)
        val entries = FrozenBrushAssets.prepare(mapOf(id to frozen), bundles, retainEntries = true, verifier = verifier)

        val root = createRoot()
        val lockPath = root.resolve(LOCK_FILE)
        if (Files.exists(lockPath, NOFOLLOW_LINKS) && regularSize(lockPath) != 0L) refuse("The mode store lock is not empty")
        var publishedId: String? = null
        try { FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> channel.lock().use {
            val catalog = scan(root)
            if (catalog.modeFolders >= limits.maxModes) refuse("The user-mode library is full")
            if (bytes.size.toLong() > limits.maxCatalogJsonBytes - catalog.metadataBytes)
                refuse("The user-mode metadata byte budget is full")
            val destination = root.resolve(folderName)
            if (Files.exists(destination, NOFOLLOW_LINKS)) refuse("The new user-mode UUID already exists")
            persistence.syncDirectory(root) // Unsupported directory durability refuses before staging writes.
            val staging = root.resolve(".staging-$folderName-${UUID.randomUUID()}")
            val created = ArrayList<OwnedPath>()
            var published = false
            try {
                Files.createDirectory(staging); created.add(owned(staging))
                writeNew(staging.resolve(BRUSH_JSON), bytes, created)
                checkpoint(Phase.AFTER_METADATA_SYNC, staging.resolve(BRUSH_JSON))
                if (entries.isNotEmpty()) {
                    val assets = staging.resolve("brush-assets"); Files.createDirectory(assets); created.add(owned(assets))
                    val blobs = assets.resolve("sha256"); Files.createDirectory(blobs); created.add(owned(blobs))
                    for ((relative, payload) in entries) {
                        if (relative != FrozenBrushAssets.MANIFEST_ENTRY && !FrozenBrushAssets.isBlobEntry(relative))
                            refuse("The prepared user-mode resource path is unsupported")
                        val file = staging.resolve(relative).normalize()
                        if (!file.startsWith(staging)) refuse("The prepared resource escaped its staging directory")
                        writeNew(file, payload, created)
                        checkpoint(Phase.AFTER_ASSET_SYNC, file)
                    }
                    persistence.syncDirectory(blobs); persistence.syncDirectory(assets)
                }
                persistence.syncDirectory(staging)
                checkpoint(Phase.BEFORE_PUBLISH, staging)
                if (Files.exists(destination, NOFOLLOW_LINKS)) refuse("The new user-mode UUID already exists")
                try { persistence.publishNew(staging, destination) }
                catch (error: Exception) {
                    // An injected/platform publisher can throw after its atomic rename completed.
                    // Within the locked cooperative root, only this transaction can create this
                    // checked-absent destination. Report the possible publication; never retry it.
                    if (!Files.exists(staging, NOFOLLOW_LINKS) && Files.exists(destination, NOFOLLOW_LINKS)) {
                        published = true; publishedId = id
                    }
                    throw error
                }
                published = true // Published directories are never deleted as rollback.
                publishedId = id
                checkpoint(Phase.AFTER_PUBLISH, destination)
                persistence.syncDirectory(root)
                checkpoint(Phase.AFTER_ROOT_SYNC, destination)
                registered = catalog.definitions.associateBy { it.id } + (id to definition)
                return definition
            } catch (error: Exception) {
                if (!published) cleanupCreated(staging, created, error)
                if (published) throw UserModeStoreException(
                    "The mode was published; its final durability could not be confirmed. Refresh the mode list.", id, error)
                throw error
            }
        } } } catch (error: Exception) {
            // FileLock/FileChannel close can fail after the inner transaction returned successfully.
            // Keep the publication identity even for that final failure; a caller must refresh, not retry.
            val visible = publishedId
            if (visible != null && (error !is UserModeStoreException || error.publishedId == null))
                throw UserModeStoreException("The mode was published; its final close or durability could not be confirmed. Refresh the mode list.",
                    visible, error)
            throw error
        }
    }

    /** Preflight every candidate size before reading even the first JSON. PNG contents are untouched. */
    private fun scan(root: Path): UserModeCatalog {
        val refused = ArrayList<UserModeCatalog.Refused>()
        val pending = ArrayList<Triple<String, Path, Long>>()
        var count = 0; var folders = 0; var bytes = 0L
        Files.newDirectoryStream(root).use { entries -> for (entry in entries) {
            if (++count > limits.maxRootEntries) refuse("The mode store has too many directory entries")
            val folder = entry.fileName.toString()
            if (folder == LOCK_FILE || STAGING_NAME.matches(folder)) continue
            if (!UUID_NAME.matches(folder)) { refused += UserModeCatalog.Refused(folder, "Unrecognized mode folder"); continue }
            if (++folders > limits.maxModes) refuse("The user-mode count exceeds its budget")
            try {
                requireDirectory(entry)
                val file = entry.resolve(BRUSH_JSON)
                val size = regularSize(file)
                if (size !in 1..limits.maxModeJsonBytes.toLong()) refuse("A user mode exceeds its JSON byte budget")
                if (size > limits.maxCatalogJsonBytes - bytes) refuse("The user-mode metadata exceeds its byte budget")
                bytes += size; pending += Triple(folder, file, size)
            } catch (error: UserModeStoreException) {
                // Size budgets are global admission errors, not permission to skip uncounted metadata.
                throw error
            } catch (error: Exception) { refused += UserModeCatalog.Refused(folder, error.message ?: "Mode metadata is unavailable") }
        } }
        val definitions = ArrayList<UserModeDefinition>()
        for ((folder, file, size) in pending.sortedBy { it.first }) {
            try { definitions += definition(folder, readBounded(file, limits.maxModeJsonBytes, size)) }
            catch (error: Exception) { refused += UserModeCatalog.Refused(folder, error.message ?: "Mode metadata is invalid") }
        }
        return UserModeCatalog(definitions, refused, folders, bytes)
    }

    private fun definition(folder: String, bytes: ByteArray): UserModeDefinition {
        if (!UUID_NAME.matches(folder)) refuse("A user mode needs a canonical UUID folder")
        val json = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val preset = BrushJson.decodeChecked(json)
        if (preset.id != ID_PREFIX + folder || preset.engine != "stamp" || preset.extensions[FAMILY_KEY] != FAMILY_USER)
            refuse("The user-mode folder, identity and family do not agree")
        if (preset.name != preset.name.trim() || preset.name.isBlank() || Character.codePointCount(preset.name, 0, preset.name.length) > 80 ||
            preset.name.any { Character.isISOControl(it) }) refuse("The stored user-mode name is invalid")
        if (preset.tip.source != "procedural" && preset.tip.sampled == null)
            refuse("The stored image tip is not materialized")
        if (!PackagedBrushGrains.supported(preset.tipTexture) || !PackagedBrushGrains.supported(preset.paperGrain))
            refuse("The stored external grain has no persistent resource adapter")
        return UserModeDefinition(preset.id, preset.name, folder, json)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun encodeBounded(preset: BrushPreset): ByteArray {
        val buffer = ByteArrayOutputStream(minOf(4096, limits.maxModeJsonBytes))
        var count = 0
        val sink = object : OutputStream() {
            override fun write(value: Int) {
                if (count >= limits.maxModeJsonBytes) refuse("The new user mode exceeds its JSON byte budget")
                buffer.write(value); count++
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length < 0 || length > limits.maxModeJsonBytes - count) refuse("The new user mode exceeds its JSON byte budget")
                buffer.write(bytes, offset, length); count += length
            }
        }
        // Stream first to prevent an oversized extension/curve from allocating an unbounded JSON string.
        FORMAT.encodeToStream(BrushPreset.serializer(), preset, sink)
        val detached = BrushJson.decodeChecked(buffer.toString(UTF_8.name()))
        val canonical = BrushJson.encode(detached).toByteArray(UTF_8)
        if (canonical.size > limits.maxModeJsonBytes) refuse("The new user mode exceeds its JSON byte budget")
        return canonical
    }

    private fun checkedModeFolder(root: Path, definition: UserModeDefinition): Path {
        val current = registered[definition.id]
        if (current !== definition || !UUID_NAME.matches(definition.folder)) refuse("This mode handle is no longer registered")
        return root.resolve(definition.folder).also(::requireDirectory)
    }
    private fun requireUnchangedMetadata(folder: Path, definition: UserModeDefinition) {
        val bytes = readBounded(folder.resolve(BRUSH_JSON), limits.maxModeJsonBytes)
        if (!bytes.contentEquals(definition.json.toByteArray(UTF_8))) refuse("The stored mode changed; refresh the mode list")
    }
    private fun requireChildren(folder: Path, expected: Set<String>) {
        val actual = linkedSetOf<String>()
        Files.newDirectoryStream(folder).use { entries -> for (entry in entries) {
            if (actual.size >= expected.size) refuse("The mode has unreferenced files")
            actual += entry.fileName.toString()
        } }
        if (actual != expected) refuse("The mode's files do not match its frozen definition")
    }
    private fun readBounded(file: Path, max: Int, expectedSize: Long? = null): ByteArray {
        val size = regularSize(file)
        if (expectedSize != null && size != expectedSize) refuse("The mode file changed after its budget reservation")
        if (size < 0 || size > max) refuse("The mode file exceeds its byte budget")
        onRead(file)
        FileChannel.open(file, READ, NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != size) refuse("The mode file changed during admission")
            val result = ByteArray(size.toInt()); val buffer = ByteBuffer.wrap(result)
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) refuse("The mode file was truncated")
            if (channel.read(ByteBuffer.allocate(1)) >= 0) refuse("The mode file grew during admission")
            return result
        }
    }
    private fun writeNew(file: Path, bytes: ByteArray, created: MutableList<OwnedPath>) {
        FileChannel.open(file, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            created.add(owned(file))
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
    private fun owned(path: Path) = OwnedPath(path, Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
    private fun cleanupCreated(staging: Path, created: List<OwnedPath>, primary: Exception) {
        for (item in created.asReversed()) {
            val file = item.path
            if (!file.normalize().startsWith(staging)) continue
            try {
                // Never follow a replaced staging ancestor while cleaning exact tracked paths.
                var parent: Path? = if (file == staging) staging else file.parent
                while (parent != null && parent.startsWith(staging)) {
                    if (Files.exists(parent, NOFOLLOW_LINKS) && !Files.isDirectory(parent, NOFOLLOW_LINKS))
                        throw java.io.IOException("Staging cleanup refused a replaced ancestor")
                    parent = parent.parent
                }
                if (!Files.exists(file, NOFOLLOW_LINKS)) continue
                val currentKey = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                if (item.fileKey == null || currentKey != item.fileKey)
                    throw java.io.IOException("Staging cleanup could not confirm ownership of a path")
                Files.delete(file)
            } catch (cleanup: Exception) { primary.addSuppressed(cleanup) }
        }
    }
    private fun regularSize(file: Path): Long {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw java.io.IOException("A mode file is missing or is not a regular file")
        return Files.size(file)
    }
    private fun requireDirectory(directory: Path) {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw java.io.IOException("A mode folder is missing or is not a regular directory")
    }
    private fun existingRoot(): Path { requireDirectory(requestedRoot); return requestedRoot.toRealPath() }
    private fun createRoot(): Path {
        if (!Files.exists(requestedRoot, NOFOLLOW_LINKS)) {
            val parent = requestedRoot.parent ?: refuse("The mode store needs an explicit parent directory")
            requireDirectory(parent)
            try { Files.createDirectory(requestedRoot) } catch (_: FileAlreadyExistsException) { }
            persistence.syncDirectory(parent)
        }
        return existingRoot()
    }
    private fun refuse(message: String): Nothing = throw UserModeStoreException(message)

    companion object {
        const val ID_PREFIX = "unibrush.user."
        const val FAMILY_KEY = "unibrush.family"
        const val FAMILY_USER = "user"
        const val MAX_MODES = 256
        const val MAX_MODE_JSON_BYTES = 256 * 1024
        const val MAX_CATALOG_JSON_BYTES = 16L * 1024 * 1024
        const val MAX_ROOT_ENTRIES = 2 * MAX_MODES + 16
        private const val BRUSH_JSON = "brush.json"
        private const val LOCK_FILE = ".user-modes.lock"
        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val UUID_NAME = Regex(UUID_PATTERN)
        private val STAGING_NAME = Regex("\\.staging-$UUID_PATTERN-$UUID_PATTERN")
        private val BLOB_NAME = Regex("[0-9a-f]{64}\\.png")
        private val PROCESS_LOCK = Any()
        private val FORMAT = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
