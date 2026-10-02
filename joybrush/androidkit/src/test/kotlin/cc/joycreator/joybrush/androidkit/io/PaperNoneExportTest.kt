package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class PaperNoneExportTest {
    private fun contents(transparent: Boolean) = JbContents(
        doc=JbDocument(id="d",name="Paper",paper=Paper(color="#306090",screenTransparent=transparent),
            boards=listOf(Board("b","Canvas",BoardKind.CANVAS,RectPx(0,0,2,2))),
            layers=listOf(Layer("l","Paint",LayerKind.PAINT,cels=listOf(Cel("c"))))),
        tiles=emptyMap(),strokes=emptyMap(),thumbnailPng=null)

    @Test fun noneCannotAccidentallyExportTheCheckerOrAnOpaquePaper() {
        val d=contents(true)
        val bytes=CanvasPng.encode(d,true,paperRenderer={error("None must not read paper")})
        val image=ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(0,image.getRGB(0,0))
        assertNull(CanvasPng.paperRendererFor(d,true,{error("None must not render")},{}))
    }
    @Test fun normalPaperCanStillBeIncludedOrExcludedExplicitly() {
        val d=contents(false)
        val included=ImageIO.read(ByteArrayInputStream(CanvasPng.encode(d,true)))
        assertEquals(0xFF306090.toInt(),included.getRGB(0,0))
        val excluded=ImageIO.read(ByteArrayInputStream(CanvasPng.encode(d,false)))
        assertEquals(0,excluded.getRGB(0,0))
    }
}
