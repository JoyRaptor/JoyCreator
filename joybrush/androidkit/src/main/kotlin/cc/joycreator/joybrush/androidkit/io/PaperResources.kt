package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.androidkit.gl.GrainTextures
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paper.*

/** CPU export resources: the packaged catalogue is also the screen's source of truth. */
object PaperResources {
    val catalogue: PaperCatalogue by lazy {
        PaperResources::class.java.getResourceAsStream("/joybrush/assets/paper/catalogue.json")
            ?.use { PaperCatalogues.parse(it.bufferedReader().readText()) }
            ?: PaperCatalogue(surfaces = emptyList(), looks = emptyList())
    }

    class Loaded(val paper: ResolvedPaper, val look: PaperTexture?, val surface: PaperTexture?, val warnings: List<String>) {
        fun render(rect: RectPx): ByteArray = PaperRaster.render(paper, look, surface, rect)
    }

    fun load(p: Paper, readTexture: (String) -> PaperTexture? = ::decode): Loaded {
        val loaded = load(PaperState.resolve(p, catalogue), readTexture)
        return Loaded(loaded.paper, loaded.look, loaded.surface, PaperState.problems(p, catalogue) + loaded.warnings)
    }

    fun load(resolved: ResolvedPaper, readTexture: (String) -> PaperTexture? = ::decode): Loaded {
        val warnings = mutableListOf<String>()
        fun texture(file: String?): PaperTexture? {
            if (file == null) return null
            val texture = try { readTexture(file) } catch (_: Exception) { null }
            if (texture == null) warnings += "Paper $file could not be loaded; exported the flat base colour."
            return texture
        }
        val look = texture(resolved.look?.file)
        val surface = texture(resolved.surface?.file)
        // A partially loaded material must not change into a different lit paper.
        return if (warnings.isNotEmpty()) Loaded(resolved.copy(look = null, surface = null, light = false), null, null, warnings)
            else Loaded(resolved, look, surface, warnings)
    }

    private fun decode(file: String): PaperTexture? {
        val bitmap = GrainTextures.loadPackagedBitmap("paper", file) ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            PaperTexture(bitmap.width, bitmap.height, GrainTextures.rgbaBytes(pixels))
        } finally { bitmap.recycle() }
    }
}
