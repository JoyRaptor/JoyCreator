package cc.joycreator.joybrush.androidkit.gl

import java.util.Collections
import java.util.LinkedHashMap

/**
 * CPU-only accounting for staged tile textures owned by one physical GL context epoch.
 * This ledger records metadata; it does not own, reserve, or delete GL resources.
 */
internal class RecordedWorkingTileLedger(initialPhysicalEpoch: Long) {
    private var physicalEpoch: Long = initialPhysicalEpoch
    private var stagedBytes = LinkedHashMap<Int, Long>()

    /** Starts a new accounting epoch without making any GL calls. */
    fun reset(physicalEpoch: Long) {
        this.physicalEpoch = physicalEpoch
        stagedBytes.clear()
    }

    /** Registers a whole batch or leaves the ledger untouched. */
    fun register(epoch: Long, textures: Collection<Int>, bytesPerTexture: Long) {
        requireCurrent(epoch)
        require(bytesPerTexture > 0L) { "Texture bytes must be positive" }

        val additions = LinkedHashMap<Int, Long>()
        for (texture in textures) {
            require(texture != 0) { "Texture handles must be nonzero GLuint bit patterns" }
            require(texture !in stagedBytes && texture !in additions) {
                "Texture handle $texture is already registered"
            }
            additions[texture] = bytesPerTexture
        }
        // Publish once after all potentially allocating map work succeeds.
        val next = LinkedHashMap(stagedBytes)
        next.putAll(additions)
        stagedBytes = next
    }

    /** A stale epoch cannot remove a name that may have been reused in the current context. */
    fun forget(epoch: Long, texture: Int): Boolean {
        if (epoch != physicalEpoch) return false
        require(texture != 0) { "Texture handles must be nonzero GLuint bit patterns" }
        return stagedBytes.remove(texture) != null
    }

    /** Returns a detached read-only view of the current epoch's staged byte metadata. */
    fun snapshot(epoch: Long): Map<Int, Long> {
        requireCurrent(epoch)
        return Collections.unmodifiableMap(LinkedHashMap(stagedBytes))
    }

    /**
     * Computes conservative current usage from actual owners and staged metadata.
     * Shared names are charged once at the larger reported size; additional bytes are then added.
     */
    fun admit(
        epoch: Long,
        actualOwners: Map<Int, Long>,
        additionalBytes: Long,
        budgetBytes: Long,
    ): Long {
        requireCurrent(epoch)
        require(additionalBytes >= 0L) { "Additional bytes must be nonnegative" }
        require(budgetBytes >= 0L) { "Budget bytes must be nonnegative" }

        val merged = LinkedHashMap<Int, Long>()
        for ((texture, bytes) in actualOwners) {
            require(texture != 0) { "Actual owner handles must be nonzero GLuint bit patterns" }
            require(bytes > 0L) { "Actual owner bytes must be positive" }
            merged[texture] = maxOf(merged[texture] ?: 0L, bytes)
        }
        for ((texture, bytes) in stagedBytes) {
            merged[texture] = maxOf(merged[texture] ?: 0L, bytes)
        }

        var total = 0L
        for (bytes in merged.values) total = checkedAdd(total, bytes)
        total = checkedAdd(total, additionalBytes)
        require(total <= budgetBytes) { "Recorded working tile usage $total exceeds budget $budgetBytes" }
        return total
    }

    private fun requireCurrent(epoch: Long) {
        check(epoch == physicalEpoch) { "Recorded working tile ledger epoch is stale" }
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        check(right >= 0L && left <= Long.MAX_VALUE - right) { "Recorded working tile byte count overflow" }
        return left + right
    }
}
