package cc.joycreator.joybrush.androidkit

import cc.joycreator.joybrush.androidkit.io.FrozenBrushAssets
import cc.joycreator.joybrush.androidkit.io.PngWriter
import cc.joycreator.joybrush.core.brush.*

/** Code-native built-in artwork only. Imported/user resources have their own explicit store. */
class UnibrushResources {
    private val registered = UnibrushModes.presets().map { it.id }.toSet()
    private var cachedPaths: Set<String>? = null
    private var cached: FrozenBrushPngs? = null

    /** One selected immutable PNG owner, stable while the exact logical closure stays selected. */
    @Synchronized fun pngs(preset: BrushPreset): FrozenBrushPngs? {
        if (preset.id !in registered || preset.extensions[UnibrushModes.FAMILY_KEY] != UnibrushModes.BUILTIN_FAMILY)
            return null
        val paths = BrushAssetPaths.required(preset).toSet()
        if (paths.isEmpty()) { cachedPaths = null; cached = null; return null }
        if (paths == cachedPaths) return cached
        // Complete admission before replacing the previous selected owner.
        val encoded = paths.associateWith { path ->
            val mask = UnibrushModes.imageMask(path)
                ?: throw BrushException("This mode needs its imported image resources")
            val alpha = mask.bytes()
            val rgba = ByteArray(alpha.size*4) { i -> if (i%4==3) alpha[i/4] else (-1).toByte() }
            PngWriter.encode(mask.width,mask.height,rgba)
        }
        val made = FrozenBrushPngs.snapshot(encoded)
        FrozenBrushAssets.prepare(mapOf(preset.id to preset),mapOf(preset.id to made),retainEntries=false)
        cachedPaths = paths; cached = made
        return made
    }
}
