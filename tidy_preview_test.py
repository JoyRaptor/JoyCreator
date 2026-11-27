from pathlib import Path
p=Path('joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/paper/PaperTestSetTest.kt')
s=p.read_text(encoding='utf-8').replace('private fun texture(s: SurfaceEntry): PaperTexture {\n        val img = ImageIO.read(File(dir, s.file))', 'private fun texture(s: SurfaceEntry): PaperTexture = texture(s.file)\n    private fun texture(file: String): PaperTexture {\n        val img = ImageIO.read(File(dir, file))')
s=s.replace('look.file?.let { texture(SurfaceEntry("preview", "Preview", it, 512, 1f, .099f, 180f, false)) }','look.file?.let(::texture)').replace('p.look?.file?.let { texture(SurfaceEntry("preview", "Preview", it, 512, 1f, .099f, 180f, false)) }','p.look?.file?.let(::texture)')
p.write_text(s,encoding='utf-8')
