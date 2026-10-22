package cc.joycreator.joybrush.androidkit.preview

import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import java.security.MessageDigest
import java.util.WeakHashMap

/** Stable across process restarts; retained previews hold only this digest, never every PNG bundle. */
object PreviewResourceFingerprint {
    private val cache = WeakHashMap<FrozenBrushPngs,String>()
    @Synchronized fun of(pngs: FrozenBrushPngs?): String {
        if (pngs == null) return "none"
        cache[pngs]?.let { return it }
        val hash = MessageDigest.getInstance("SHA-256")
        fun count(value: Int) { for (shift in intArrayOf(24,16,8,0)) hash.update((value ushr shift).toByte()) }
        count(pngs.paths.size)
        for (path in pngs.paths.sorted()) {
            val name = path.toByteArray(Charsets.UTF_8)
            val bytes = pngs.requirePng(path)
            count(name.size); hash.update(name); count(bytes.size); hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            .also { cache[pngs] = it }
    }
}
