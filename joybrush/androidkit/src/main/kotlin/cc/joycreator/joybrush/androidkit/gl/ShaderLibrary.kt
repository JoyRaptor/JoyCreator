package cc.joycreator.joybrush.androidkit.gl

/**
 * Loads the SHARED shader files (joybrush/shaders, packaged into this jar under /joybrush/shaders/)
 * and expands `#include "name.glsl"` lines, so the phone runs exactly the files the PC Brush Lab runs.
 */
class ShaderLibrary(private val load: (String) -> String = ::loadResource) {

    private val cache = HashMap<String, String>()

    fun source(name: String): String = cache.getOrPut(name) { expand(name, HashSet()) }

    /**
     * [name] built with `#define`s: each of [defines] becomes a `#define` line right after the `#version` line (Media Atlas
     * v2 builds its shaders with JB_ATLAS this way). No defines = [source], the very same text.
     */
    fun source(name: String, defines: List<String>): String =
        if (defines.isEmpty()) source(name)
        else cache.getOrPut(name + "#" + defines.joinToString(",")) { withDefines(source(name), defines) }

    private fun expand(name: String, seen: MutableSet<String>): String {
        require(seen.add(name)) { "shader include cycle at $name" }
        val out = StringBuilder()
        for (line in load(name).lineSequence()) {
            val m = INCLUDE.matchEntire(line.trim())
            if (m != null) out.append(expand(m.groupValues[1], seen)).append('\n')
            else out.append(line).append('\n')
        }
        seen.remove(name)
        return out.toString()
    }

    companion object {
        private val INCLUDE = Regex("#include\\s+\"([^\"]+)\"")

        /** [src] with `#define d` lines after its `#version` line (GLSL ES: nothing may precede #version). */
        fun withDefines(src: String, defines: List<String>): String {
            for (d in defines) require(d.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "not a define name: $d" }
            val at = src.indexOf("#version")
            require(at >= 0) { "shader has no #version line to put defines after" }
            val end = src.indexOf('\n', at).let { if (it < 0) src.length else it + 1 }
            val head = if (end == src.length && !src.endsWith("\n")) src + "\n" else src.substring(0, end)
            return head + defines.joinToString("") { "#define $it\n" } + src.substring(minOf(end, src.length))
        }

        fun loadResource(name: String): String {
            val stream = ShaderLibrary::class.java.getResourceAsStream("/joybrush/shaders/$name")
                ?: error("shader not packaged: /joybrush/shaders/$name")
            return stream.bufferedReader().use { it.readText() }
        }
    }
}
