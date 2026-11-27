from pathlib import Path
p=Path('joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/paper/PaperTestSetTest.kt')
s=p.read_text(); marker='    @Test fun allFiveSurfaceFilesRebuildExactlyFromTheirPhysicalHeights() {'
s=s.replace(marker,'''    @Test fun contactSheetShowsActualRendererAtQuarterNormalAndFourTimesScale() {
        val c = catalogue
        val rows = ids + "pulp_artisan"
        val sheet = java.awt.image.BufferedImage(432, rows.size * 152, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = java.awt.Color.WHITE; g.fillRect(0, 0, sheet.width, sheet.height)
        for ((row, id) in rows.withIndex()) {
            val s = assertNotNull(PaperCatalogues.surface(c, id)); val tex = texture(s)
            val lookId = if (id == "pulp_artisan") "off_white" else id
            val look = assertNotNull(PaperCatalogues.look(c, lookId))
            for ((col, scale) in listOf(.25f, 1f, 4f).withIndex()) {
                val paper = PaperState.resolve(cc.joycreator.joybrush.core.doc.Paper(
                    lookId = lookId, textureId = id, textureScale = scale), c)
                val rgba = PaperRaster.render(paper, null, tex, RectPx(0, 0, 128, 128))
                for (y in 0 until 128) for (x in 0 until 128) {
                    val i = (y*128+x)*4
                    sheet.setRGB(col*144+x, row*152+y, ((rgba[i].toInt() and 255) shl 16) or
                        ((rgba[i+1].toInt() and 255) shl 8) or (rgba[i+2].toInt() and 255))
                }
                g.color = java.awt.Color.BLACK
                g.drawString("${look.name} ${scale}x", col*144+2, row*152+143)
            }
        }
        g.dispose()
        val output = File(dir.parentFile.parentFile, "tools/paper/out/contact.png")
        output.parentFile.mkdirs(); assertTrue(ImageIO.write(sheet, "png", output))
    }

''' + marker)
p.write_text(s)
