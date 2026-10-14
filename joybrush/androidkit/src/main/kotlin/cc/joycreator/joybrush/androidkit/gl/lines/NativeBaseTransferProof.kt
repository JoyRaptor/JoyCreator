package cc.joycreator.joybrush.androidkit.gl.lines

/**
 * Pure evidence for ONE engine-private transfer. No rendering, publication,
 * history mutation or release occurs here. Engine constructs occurrences from its real current
 * inventory and proves plain-RGBA8 allocation provenance; this is not an upload format validator.
 */
internal class NativeBaseTransferProof private constructor(
    private val context: Context,
    private var cursor: Any?,
    private var old: Map<Pair<String, String?>, Physical>?,
    private val next: Map<Pair<String, String?>, Physical>,
    private var contents: Map<Pair<String, String?>, Map<Long, Int>>?,
) {
    class Context(val document: Any?, val paper: Any, val resolvedPaper: Any?,
                  val logicalEpoch: Long, val physicalEpoch: Long) {
        fun same(other: Context): Boolean = document === other.document && paper == other.paper &&
            resolvedPaper == other.resolvedPaper && logicalEpoch == other.logicalEpoch &&
            physicalEpoch == other.physicalEpoch
    }
    class Physical(val owner: Any, val token: Any, val plan: Any?, val store: Map<Long, Int>,
                   val sharedAlias: Map<Long, Int>? = null)
    data class Slot(val plane: Pair<String, String?>, val key: Long)
    /** null physical means every non-base owner: pool/global/cache/mask/media/history/current unit. */
    class Occurrence(val texture: Int, val physical: Slot?)

    private var plannedRoot: Any? = null
    private var installed = false

    fun prepareRoot(root: Any) {
        check(plannedRoot == null && !installed)
        plannedRoot = root
    }

    /** Called before/after providers and last before assignment, using a FRESH full inventory. */
    fun canPublish(root: Any?, now: Context, currentCursor: Any,
                   physical: Map<Pair<String, String?>, Physical>, occurrences: List<Occurrence>,
                   rgba8Bases: Set<Int>, heldHistory: Set<Int>): Boolean =
        !installed && plannedRoot != null && root == null && context.same(now) && cursor === currentCursor &&
            old?.let { samePhysical(it, physical, local = true) } == true && samePhysical(next, next, local = false) &&
            exclusive(occurrences, rgba8Bases, heldHistory)

    /** Primitive after-assignment switch. Engine preflights it; there is no provider or allocation. */
    fun acknowledgeInstalled(root: Any) {
        check(!installed && root === plannedRoot)
        installed = true
        old = null; contents = null; cursor = null // Drop borrowed metadata; no recycling/release.
        plannedRoot = null // Do not pin the initial artwork root after later publications.
    }

    /**
     * Renderer check: local old tuples ONLY before transfer, planned new tuples ONLY afterwards.
     * Subsequent real root replacements are allowed; RecordedScene guards exact expected root
     * around each callback. The promoted native placeholders must remain empty in every root.
     */
    fun contextCurrent(root: Any?, now: Context, physical: Map<Pair<String, String?>, Physical>): Boolean =
        context.same(now) && if (installed) root != null && samePhysical(next, physical, local = false)
        else root == null && old?.let { samePhysical(it, physical, local = true) } == true

    private fun samePhysical(expected: Map<Pair<String, String?>, Physical>,
                             actual: Map<Pair<String, String?>, Physical>, local: Boolean): Boolean =
        actual.keys == expected.keys && expected.all { (plane, captured) ->
            val current = actual.getValue(plane)
            (!local || current.owner === captured.owner) && current.token === captured.token &&
                current.plan === captured.plan && current.store === captured.store &&
                current.sharedAlias === captured.sharedAlias &&
                current.store == (if (local) contents?.getValue(plane) else emptyMap<Long, Int>())
        }

    private fun exclusive(occurrences: List<Occurrence>, rgba8Bases: Set<Int>, heldHistory: Set<Int>): Boolean {
        val snapshot = contents ?: return false
        val expected = snapshot.flatMap { (plane, tiles) -> tiles.map { (key, texture) -> texture to Slot(plane, key) } }
        return expected.all { (texture, slot) ->
            texture != 0 && texture in rgba8Bases && texture !in heldHistory &&
                occurrences.count { it.texture == texture } == 1 &&
                occurrences.singleOrNull { it.texture == texture }?.physical == slot
        }
    }

    companion object {
        fun capture(context: Context, cursor: Any,
                    old: Map<Pair<String, String?>, Physical>, next: Map<Pair<String, String?>, Physical>,
                    occurrences: List<Occurrence>, rgba8Bases: Set<Int>, heldHistory: Set<Int>): NativeBaseTransferProof {
            require(old.isNotEmpty() && next.keys == old.keys)
            // These maps detach metadata only. Their referenced physical stores are never cleared.
            val oldCopy = old.toMap(); val nextCopy = next.toMap()
            val contents = oldCopy.mapValues { it.value.store.toMap() }
            require(contents.values.flatMap { it.values }.let { it.distinct().size == it.size }) {
                "Two canonical native base slots own one texture"
            }
            require(oldCopy.values.map { it.store }.let { stores ->
                stores.indices.all { i -> (0 until i).none { j -> stores[i] === stores[j] } }
            }) { "Physical slots must be distinct, including empty frame stores" }
            require(nextCopy.values.map { it.store }.let { stores ->
                stores.indices.all { i -> (0 until i).none { j -> stores[i] === stores[j] } }
            }) { "Planned physical slots must have distinct stores" }
            for ((plane, before) in oldCopy) {
                val after = nextCopy.getValue(plane)
                require(after.token === before.token && after.plan === before.plan && after.store.isEmpty())
                require(oldCopy.values.none { it.store === after.store }) { "A transfer needs a NEW empty map" }
                require(if (plane.second == null) {
                    before.sharedAlias == null || before.sharedAlias === before.store
                } else before.sharedAlias == null)
                require(if (before.sharedAlias == null) after.sharedAlias == null else after.sharedAlias === after.store)
            }
            // Installed-phase matching needs only token/plan/store, not a Layer object whose
            // disposable projection maps could otherwise stay retained across later edits.
            val planned = nextCopy.mapValues { (_, p) -> Physical(p.token, p.token, p.plan, p.store, p.sharedAlias) }
            return NativeBaseTransferProof(context, cursor, oldCopy, planned, contents).also {
                require(it.exclusive(occurrences, rgba8Bases, heldHistory)) {
                    "Base transfer needs exact sole physical ownership and current RGBA8 provenance"
                }
            }
        }
    }
}
