from pathlib import Path
p=Path('joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/RegionRenderer.kt'); s=p.read_text(encoding='utf-8')
s=s.replace('        paper: String?,\n    ): ByteArray {','        paper: String?,\n        paperRenderer: ((RectPx) -> ByteArray)? = null,\n    ): ByteArray {')
s=s.replace('        paper: String?,\n    ): FloatArray {','        paper: String?,\n        paperRenderer: ((RectPx) -> ByteArray)? = null,\n    ): FloatArray {')
s=s.replace('        requirePaperIfAny(paper)\n','        requirePaperIfAny(paper)\n        require(paper == null || paperRenderer == null) { "choose a flat paper or a paper renderer, not both" }\n')
s=s.replace('val p = renderPremultiplied(doc, tiles, rect, frameId, paper)','val p = renderPremultiplied(doc, tiles, rect, frameId, paper, paperRenderer)')
marker='''        // One scratch pixel for the whole image: a source, the running result, and the result of'''
s=s.replace(marker,'''        if (paperRenderer != null) fillPaperBlocks(px, rect, paperRenderer)

'''+marker)
marker='''    /**
     * A region has to be renderable,'''
new='''    /** At most32 KiB of paper bytes beyond the existing output/scratch budget. */
    const val PAPER_BLOCK_WIDTH = 256
    const val PAPER_BLOCK_HEIGHT = 32

    private fun fillPaperBlocks(pixels: FloatArray, rect: RectPx, renderer: (RectPx) -> ByteArray) {
        var top = 0
        while (top < rect.h) {
            val height = minOf(PAPER_BLOCK_HEIGHT, rect.h - top)
            var left = 0
            while (left < rect.w) {
                val width = minOf(PAPER_BLOCK_WIDTH, rect.w - left)
                val block = RectPx(rect.x + left, rect.y + top, width, height)
                val rgba = renderer(block)
                require(rgba.size == width * height * 4) { "paper renderer returned the wrong byte count for ${width}x$height" }
                for (y in 0 until height) for (x in 0 until width) {
                    val source = (y * width + x) * 4
                    require((rgba[source + 3].toInt() and 255) == 255) { "paper renderer must return opaque pixels" }
                    val dest = ((top + y) * rect.w + left + x) * 4
                    for (q in 0..2) pixels[dest + q] = (rgba[source + q].toInt() and 255) / 255f
                    pixels[dest + 3] = 1f
                }
                left += width
            }
            top += height
        }
    }

'''
assert marker in s
s=s.replace(marker,new+marker)
s=s.replace(' * `FloatArray`.',' * `FloatArray`. A textured paper callback adds at most32 KiB of temporary block pixels;\n * it never adds a second whole-region paper buffer.')
p.write_text(s,encoding='utf-8')
