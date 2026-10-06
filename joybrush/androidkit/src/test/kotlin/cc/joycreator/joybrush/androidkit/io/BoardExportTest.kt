package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.chrome.BoardExportLayout as Export
import cc.joycreator.joybrush.core.doc.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class BoardExportTest {
    private var serial = 0
    private fun ids() = "export-test-${serial++}"
    private fun base(): JbDocument = DocOps.newDocument("drawing","Test",2,1,::ids)
    private fun contents(doc: JbDocument): JbContents {
        val layer = doc.layers.single()
        val shared = layer.sharedCelId ?: layer.cels.single().id
        val bytes = ByteArray(TILE_BYTES).apply { this[0]=255.toByte(); this[3]=255.toByte(); this[5]=255.toByte(); this[7]=255.toByte() }
        val manifests = doc.copy(layers = listOf(layer.copy(cels=layer.cels.map { it.copy(tiles=listOf("0_0")) })))
        val data = layer.cels.associate { cel -> Triple(layer.id,cel.id,"0_0") to
            if(cel.id == shared) bytes else ByteArray(TILE_BYTES).apply {
                this[if(cel.id == layer.cels.last().id) 1 else 2]=255.toByte(); this[3]=255.toByte()
            } }
        return JbContents(manifests,data,emptyMap())
    }
    private fun request(doc: JbDocument, board: Board, scope: Export.Scope, format: Export.Format,
        first: String = board.frames.firstOrNull()?.id ?: board.id, last: String = board.frames.lastOrNull()?.id ?: board.id) =
        BoardExportRequest.capture(doc,Export.Choice(board.id,scope,format,first,first,last),false)
    private fun animation(): JbDocument {
        val first = BoardDocumentOps.createAnimation(base(),"Walk",RectPx(0,0,1,1),::ids).doc
        val board = first.boards.last()
        val second = RegionDocumentOps.addFrame(first,board.id,NewFrame.BLANK,::ids).doc
        return RegionDocumentOps.setHold(second,board.id,second.boards.last().frames.last().id,3)
    }
    private fun inCache(block: (java.io.File) -> Unit) {
        val cache = Files.createTempDirectory("jb-board-export-test").toFile()
        try { block(cache) } finally { cache.deleteRecursively() }
    }
    @Test fun imageExportDecodesCorrectCropAndCleansOnlyItsPrivateDirectory() = inCache { cache ->
        val doc = base(); val sentinel = java.io.File(cache,"keep").apply { writeText("not export-owned") }
        val art = contents(doc)
        val staged = BoardExport.stage(art,request(doc,doc.boards.single(),Export.Scope.BOARD,Export.Format.PNG),cache)
        assertEquals(0xFFFF0000.toInt(),ImageIO.read(staged.files.single().file).getRGB(0,0))
        assertEquals(0xFF00FF00.toInt(),ImageIO.read(staged.files.single().file).getRGB(1,0))
        staged.close(); assertTrue(sentinel.exists()); assertFalse(staged.directory.exists()); assertEquals(doc.id,art.doc.id)
    }
    @Test fun duplicateImageNamesHaveDistinctFiles() = inCache { cache ->
        val original = base(); val doc = BoardDocumentOps.createImage(original,original.boards.single().name,RectPx(1,0,1,1),::ids).doc
        BoardExport.stage(contents(doc),request(doc,doc.boards.first(),Export.Scope.ALL_IMAGES,Export.Format.PNG),cache).use { out ->
            assertEquals(2,out.files.size); assertEquals(2,out.files.map { it.file.name }.toSet().size)
            assertEquals(0xFF00FF00.toInt(),ImageIO.read(out.files.last().file).getRGB(0,0))
        }
    }
    @Test fun rangeSequenceCarriesHeldTimingAndStableFrameDespiteNavigation() = inCache { cache ->
        val doc = animation(); val board = doc.boards.last(); val last = board.frames.last().id
        val target = request(doc,board,Export.Scope.RANGE,Export.Format.PNG_FRAMES,last,last)
        val moved = RegionDocumentOps.selectFrame(doc,board.id,board.frames.first().id)
        BoardExport.stage(contents(moved),target,cache).use { out ->
            assertEquals(listOf("0001.png","Walk.timing.txt"),out.files.map { it.file.name })
            assertTrue(out.files.last().file.readText().contains("0001.png\t250"))
            assertEquals(0xFF00FF00.toInt(),ImageIO.read(out.files.first().file).getRGB(0,0))
        }
        assertEquals(board.frames.first().id,moved.boards.last().currentFrameId)
    }
    @Test fun gifIsActuallyDecodableAndSheetHasItsRelativeSidecar() = inCache { cache ->
        val doc = animation(); val board = doc.boards.last()
        BoardExport.stage(contents(doc),request(doc,board,Export.Scope.ANIMATION,Export.Format.GIF),cache).use {
            assertEquals("image/gif",it.files.single().mime); assertNotNull(ImageIO.read(it.files.single().file))
        }
        BoardExport.stage(contents(doc),request(doc,board,Export.Scope.ANIMATION,Export.Format.SPRITE_SHEET),cache).use {
            val sidecar = Json.parseToJsonElement(it.files.last().file.readText()).jsonObject
            assertEquals(it.files.first().file.name,sidecar.getValue("sheetUri").jsonPrimitive.content)
            assertEquals(2,ImageIO.read(it.files.first().file).width)
        }
    }
    @Test fun spriteSheetKeepsActualGridAndCellPixels() = inCache { cache ->
        val doc = BoardDocumentOps.createSprite(base(),"Sprites",RectPx(0,0,2,1),SpriteGrid(2,1,1,1),::ids).doc
        BoardExport.stage(contents(doc),request(doc,doc.boards.last(),Export.Scope.BOARD,Export.Format.SPRITE_SHEET),cache).use {
            val image = ImageIO.read(it.files.first().file)
            assertEquals(2,image.width); assertEquals(1,image.height)
            assertEquals(0xFFFF0000.toInt(),image.getRGB(0,0)); assertEquals(0xFF00FF00.toInt(),image.getRGB(1,0))
            val json = Json.parseToJsonElement(it.files.last().file.readText()).jsonObject
            assertEquals(2,json.getValue("cols").jsonPrimitive.int); assertEquals(1,json.getValue("rows").jsonPrimitive.int)
        }
    }
    @Test fun changedFrameOrderAndWrongDrawingRefuseBeforeStaging() = inCache { cache ->
        val doc = animation(); val board = doc.boards.last()
        val target = request(doc,board,Export.Scope.ANIMATION,Export.Format.PNG_FRAMES)
        val reversed = doc.copy(boards = doc.boards.map { if(it.id == board.id) it.copy(frames=it.frames.reversed()) else it })
        assertFailsWith<JbArchiveException> { BoardExport.stage(contents(reversed),target,cache) }
        assertFailsWith<JbArchiveException> { BoardExport.stage(contents(doc.copy(id="other")),target,cache) }
        assertTrue(cache.listFiles()!!.isEmpty())
    }
    @Test fun unconnectedHandoffRefusesAndCleansStaging() = inCache { cache ->
        val doc = animation(); val board = doc.boards.last()
        assertFailsWith<JbArchiveException> { BoardExport.stage(contents(doc),request(doc,board,Export.Scope.ANIMATION,Export.Format.STUDIO),cache) }
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
