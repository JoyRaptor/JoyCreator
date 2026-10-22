package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.export.*
import cc.joycreator.joybrush.core.render.*
import kotlin.test.*
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import org.w3c.dom.Node

class SceneAnimExportTest {
    private var n=0
    private fun id()="export-${++n}"
    private fun sceneGif(board: Board, fullLoop: Boolean = false, warnings: MutableList<String> = arrayListOf()): ByteArray {
        val doc=JbDocument(id="d",name="Scene",boards=listOf(board),layers=emptyList())
        var result: ByteArray?=null
        SceneAnimExportRunner.write(JbContents(doc,emptyMap(),emptyMap()),SceneAnimExportPlan.build(doc,fullLoop),true,false,
            AnimFileSink { _,_,bytes -> result=bytes },onWarning={ warnings.add(it) })
        return result ?: error("No GIF was written")
    }
    private fun delaysCs(bytes: ByteArray): List<Int> = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val reader=ImageIO.getImageReadersByFormatName("gif").next()
        try {
            reader.input=input
            (0 until reader.getNumImages(true)).map { i ->
                val tree=reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0")
                fun control(node: Node): Node? {
                    if(node.nodeName == "GraphicControlExtension") return node
                    for(j in 0 until node.childNodes.length) control(node.childNodes.item(j))?.let { return it }
                    return null
                }
                control(tree)!!.attributes.getNamedItem("delayTime").nodeValue.toInt()
            }
        } finally { reader.dispose() }
    }
    @Test fun staticOneSecondAt60FpsIsOneGifFrameWithOneSecondDelay() {
        val board=Board("clip","Clip",BoardKind.ANIMATION,RectPx(0,0,1,1),fps=60f,
            frames=listOf(Frame("f",holdFrames=60)),currentFrameId="f")
        val warnings=arrayListOf<String>()
        val bytes=sceneGif(board,warnings=warnings)
        assertEquals(listOf(100),delaysCs(bytes))
        assertTrue(warnings.any { "50 frames per second" in it })
    }
    @Test fun gifUsesCumulativeCentisecondBoundariesInsteadOfRoundingEveryDrawing() {
        val board=Board("clip","Clip",BoardKind.ANIMATION,RectPx(0,0,1,1),fps=24f,
            frames=List(24) { Frame("f-$it") },currentFrameId="f-0")
        val delays=delaysCs(sceneGif(board))
        assertEquals(List(24) { i -> ((i+1)*100.0/24).roundToInt()-(i*100.0/24).roundToInt() },delays)
        assertEquals(100,delays.sum())
    }
    @Test fun gifSplitsLongHoldsWithoutLosingAOneCentisecondTail() {
        val board=Board("clip","Clip",BoardKind.ANIMATION,RectPx(0,0,1,1),fps=999000f/655360f,
            frames=listOf(Frame("f",holdFrames=999)),currentFrameId="f")
        assertEquals(listOf(65_534,2),delaysCs(sceneGif(board)))
    }
    @Test fun fullLoopDoesNotLoopGifWhenEveryClipEndsWithHold() {
        val board=Board("clip","Clip",BoardKind.ANIMATION,RectPx(0,0,1,1),end="hold",
            frames=listOf(Frame("f")),currentFrameId="f")
        assertFalse(sceneGif(board,true).toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
    }
    @Test fun independentClipsAndBlankRenderCorrectPixelsAndKeepSavedFrames() {
        var d=DocOps.newDocument("doc","Scene",4,4,::id)
        val bottom=d.layers.single().copy(id="bg",name="Sky")
        val top=Layer("ch","Character",LayerKind.PAINT,cels=listOf(Cel("ch-shared")))
        d=BoardDocumentOps.createAnimation(d.copy(layers=listOf(bottom,top),activeLayerId="ch"),"Walk",RectPx(0,0,2,2),::id).doc
        val walk=d.boards.last()
        d=BoardDocumentOps.createAnimation(d.copy(activeLayerId="bg"),"Sky",RectPx(1,1,2,2),::id).doc
        val sky=d.boards.last()
        d=d.copy(boards=d.boards.map { when(it.id) { walk.id -> it.copy(fps=12f,end="blank"); sky.id -> it.copy(fps=24f,frames=it.frames.map { f -> f.copy(holdFrames=3) }); else -> it } })
        fun colour(r:Int,g:Int,b:Int)=ByteArray(256*256*4).also { out -> for(i in out.indices step 4) { out[i]=r.toByte();out[i+1]=g.toByte();out[i+2]=b.toByte();out[i+3]=255.toByte() } }
        val wc=d.layers.first { it.id=="ch" }.regions.first { it.boardId==walk.id }.frameCel.values.first()
        val sc=d.layers.first { it.id=="bg" }.regions.first { it.boardId==sky.id }.frameCel.values.first()
        val contents=JbContents(d,mapOf(Triple("ch",wc,"0_0") to colour(255,0,0),Triple("bg",sc,"0_0") to colour(0,255,0)),emptyMap())
        val plan=SceneAnimExportPlan.build(d)
        val files=ArrayList<AnimFile>()
        SceneAnimExportRunner.write(contents,plan,false,false,AnimFileSink { name,mime,bytes -> files += AnimFile(name,mime,bytes) })
        val first=ImageIO.read(ByteArrayInputStream(files.first().bytes))
        val last=ImageIO.read(ByteArrayInputStream(files[2].bytes))
        assertEquals(0xffff0000.toInt(),first.getRGB(1,1)); assertEquals(0xff00ff00.toInt(),last.getRGB(1,1))
        assertEquals(0,last.getRGB(0,0),"blank suppresses the character instead of showing its saved frame")
        assertEquals(walk.currentFrameId,d.boards.first { it.id==walk.id }.currentFrameId)
        assertEquals("timing.csv",files.last().name)
    }
    @Test fun gifLoopFlagFollowsClipEnd() {
        val b=Board("clip","Clip",BoardKind.ANIMATION,RectPx(0,0,1,1),frames=listOf(Frame("f")),currentFrameId="f")
        val layer=Layer("l","Layer",LayerKind.PAINT,cels=listOf(Cel("c")),animatedIn=b.id,frameCel=mapOf("f" to "c"))
        for(end in BoardEnd.ALL) {
            val board=b.copy(end=end); val d=JbDocument(id="d",name="D",boards=listOf(board),layers=listOf(layer))
            val bytes=AnimExportRunner.encodeOne(AnimFormat.GIF,JbContents(d,emptyMap(),emptyMap()),b.id,AnimExport.plan(board,"Clip"),false).single().bytes
            assertEquals(end in listOf("loop","pingpong"),bytes.toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0"),end)
        }
    }
}
