package cc.joycreator.joybrush.androidkit.gl

/**
 * Loads the SHARED shader files (joybrush/shaders, packaged into this jar under /joybrush/shaders/)
 * and expands `#include "name.glsl"` lines, so the phone runs exactly the files the PC Brush Lab runs.
 */
class ShaderLibrary(private val load: (String) -> String = ::loadResource) {

    private val cache = HashMap<String, String>()

    fun source(name: String): String = cache.getOrPut(name) { expand(name, HashSet()) }

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

        fun loadResource(name: String): String {
            val stream = ShaderLibrary::class.java.getResourceAsStream("/joybrush/shaders/$name")
                ?: error("shader not packaged: /joybrush/shaders/$name")
            return stream.bufferedReader().use { it.readText() }
        }
    }
}
