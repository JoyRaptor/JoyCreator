package cc.joycreator.joybrush.androidkit.lines

import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushRegistry
import cc.joycreator.joybrush.core.brush.FrozenBrushResult
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import cc.joycreator.joybrush.androidkit.io.FrozenBrushAssets

/**
 * Editable lines v1.2 H8: freezing a brush against the drawing's kept brushes encodes and compares EVERY kept brush, which
 * is far too much work at pen-down. The answer only depends on the drawing's brush set (its snapshot revision) and the
 * brush asked for, so it is remembered per (revision, encoded effective brush). UI thread only.
 *
 * A remembered answer for a brush the drawing does not hold yet keeps its fresh id; that is safe, because the id is only
 * ever recorded once a line using it is committed, and that grows the revision, which retires every older answer.
 */
class FrozenBrushCache(
    private val capacity: Int = 16,
    private val freeze: (Map<String, BrushPreset>, BrushPreset, () -> String) -> FrozenBrushResult = FrozenBrushRegistry::freeze,
) {
    private data class Key(val revision: Long, val known: Int, val encoded: String)

    private val results = object : LinkedHashMap<Key, FrozenBrushResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, FrozenBrushResult>?): Boolean = size > capacity
    }

    /** How many times the registry really ran (for tests and diagnostics). */
    var computed = 0
        private set

    fun freeze(revision: Long, known: Map<String, BrushPreset>, preset: BrushPreset, newId: () -> String): FrozenBrushResult {
        val key = Key(revision, known.size, BrushJson.encode(preset))
        results[key]?.let { return it }
        val result = freeze(known, preset, newId)
        computed++
        results[key] = result
        return result
    }

    // One modern candidate only. Complete results share immutable document bundles, while
    // canceled contacts cannot accumulate sixteen independent 32MiB image sets in this cache.
    private class Modern(val key: Key, val candidate: FrozenBrushPngs,
                         val known: Map<String, FrozenBrushPngs>, val result: FrozenBrushResult)
    private var modern: Modern? = null

    fun freeze(revision: Long, known: Map<String, BrushPreset>,
               knownAssets: Map<String, FrozenBrushPngs>, preset: BrushPreset,
               candidate: FrozenBrushPngs, newId: () -> String): FrozenBrushResult {
        val key = Key(revision, known.size, BrushJson.encode(preset))
        modern?.takeIf { it.key == key && it.candidate.sameBytes(candidate) &&
            it.known.keys == knownAssets.keys && it.known.all { (id, old) -> knownAssets[id]?.sameBytes(old) == true }
        }?.let { return it.result }
        // Drop the old candidate before allocating/validating the next complete answer.
        modern = null
        val result = FrozenBrushRegistry.freezeWithAssets(known, knownAssets, preset, candidate, newId)
        computed++
        FrozenBrushAssets.prepare(result.entries, result.assets, retainEntries = false)
        modern = Modern(key, candidate, knownAssets.toMap(), result)
        return result
    }

    fun clear() { results.clear(); modern = null }
}
