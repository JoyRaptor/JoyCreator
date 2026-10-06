package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.chrome.BoardExportLayout as Export
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.export.AnimExport
import cc.joycreator.joybrush.core.export.SpritePacker
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource
import cc.joycreator.joybrush.core.sprite.SpriteGridMath
import java.io.File
import java.util.UUID

/** Stable identities cross the picker; neither selection nor a playback preview is an export target. */
data class BoardExportRequest(
    val documentId: String, val boardId: String, val kind: BoardKind,
    val scope: Export.Scope, val format: Export.Format, val frameId: String?,
    val startId: String?, val endId: String?, val frameIds: List<String>,
    val imageIds: List<String>, val includePaper: Boolean,
) {
    val folder: Boolean get() = scope == Export.Scope.ALL_IMAGES ||
        format == Export.Format.PNG_FRAMES || format == Export.Format.SPRITE_SHEET

    companion object {
        fun capture(doc: JbDocument, choice: Export.Choice, paper: Boolean): BoardExportRequest {
            val board = doc.boards.singleOrNull { it.id == choice.boardId }
                ?: throw JbArchiveException("that board no longer exists")
            return BoardExportRequest(doc.id, board.id, board.kind, choice.scope, choice.format,
                choice.currentFrameId.takeIf { board.kind == BoardKind.ANIMATION },
                choice.rangeStartId.takeIf { board.kind == BoardKind.ANIMATION },
                choice.rangeEndId.takeIf { board.kind == BoardKind.ANIMATION },
                board.frames.map { it.id }, doc.boards.filter { it.kind == BoardKind.CANVAS }.map { it.id }, paper)
        }
    }
}

data class StagedExportFile(val file: File, val mime: String)

/** Only this fresh private directory is owned; cleanup never touches the chosen destination. */
class StagedBoardExport internal constructor(val directory: File, val files: List<StagedExportFile>) : AutoCloseable {
    override fun close() { directory.deleteRecursively() }
}

/** Runs on the existing save writer. All outputs finish locally before a SAF destination is opened. */
object BoardExport {
    fun stage(contents: JbContents, request: BoardExportRequest, cache: File,
        onWarning: (String) -> Unit = {}, onFrame: (Int, Int) -> Unit = { _, _ -> }): StagedBoardExport {
        val doc = contents.doc
        DocOps.validate(doc).takeIf { it.isNotEmpty() }?.let { throw JbArchiveException(it.joinToString("; ")) }
        if (doc.id != request.documentId) throw JbArchiveException("the drawing changed; choose the board again")
        val board = doc.boards.singleOrNull { it.id == request.boardId }
            ?: throw JbArchiveException("that board no longer exists")
        if (board.kind != request.kind) throw JbArchiveException("the board changed; choose its export again")
        if (board.kind == BoardKind.ANIMATION && board.frames.map { it.id } != request.frameIds)
            throw JbArchiveException("the frame order changed; choose the export range again")
        val directory = File(cache, "jb-board-export-${UUID.randomUUID()}")
        if (!directory.mkdirs()) throw JbArchiveException("there is no room to prepare this export")
        val files = arrayListOf<StagedExportFile>()
        val sink = AnimFileSink { name, mime, bytes ->
            require(name == File(name).name && name !in listOf(".", "..") && files.none { it.file.name == name })
            val file = File(directory, name)
            file.outputStream().use { it.write(bytes) }
            files += StagedExportFile(file, mime)
        }
        try {
            val base = AnimExport.safeBaseName(board.name)
            when {
                request.scope == Export.Scope.ALL_IMAGES -> {
                    require(board.kind == BoardKind.CANVAS && request.format == Export.Format.PNG)
                    val images = request.imageIds.map { id -> doc.boards.singleOrNull { it.id == id && it.kind == BoardKind.CANVAS }
                        ?: throw JbArchiveException("an image board changed; choose all image boards again") }
                    require(images.isNotEmpty())
                    // Numbering avoids collisions between identically named boards, even after sanitizing names.
                    images.forEachIndexed { i, image ->
                        sink.write("${(i+1).toString().padStart(3,'0')} ${AnimExport.safeBaseName(image.name)}.png", "image/png",
                            CanvasPng.encodeBoard(contents, image.id, request.includePaper, onWarning = onWarning))
                        onFrame(i+1, images.size)
                    }
                }
                board.kind == BoardKind.ANIMATION -> {
                    require(request.scope in listOf(Export.Scope.ANIMATION, Export.Scope.RANGE, Export.Scope.FRAME))
                    val plan = when (request.scope) {
                        Export.Scope.ANIMATION -> AnimExport.plan(board, base)
                        else -> {
                            val first = board.frames.indexOfFirst { it.id == if(request.scope == Export.Scope.FRAME) request.frameId else request.startId }
                            val last = board.frames.indexOfFirst { it.id == if(request.scope == Export.Scope.FRAME) request.frameId else request.endId }
                            if (first < 0 || last < first) throw JbArchiveException("that frame range no longer exists")
                            AnimExport.planRange(board, first, last, base)
                        }
                    }
                    when (request.format) {
                        Export.Format.PNG -> {
                            require(request.scope == Export.Scope.FRAME)
                            sink.write("$base.png", "image/png", CanvasPng.encodeBoard(contents, board.id,
                                request.includePaper, request.frameId, onWarning = onWarning))
                        }
                        Export.Format.PNG_FRAMES -> AnimExportRunner.writeSequence(contents, board.id, plan,
                            request.includePaper, sink, onWarning = onWarning, onFrame = onFrame)
                        Export.Format.GIF, Export.Format.SPRITE_SHEET -> AnimExportRunner.encodeOne(
                            if(request.format == Export.Format.GIF) AnimFormat.GIF else AnimFormat.SPRITE_SHEET,
                            contents, board.id, plan, request.includePaper, onWarning = onWarning, onFrame = onFrame
                        ).forEach { sink.write(it.name, it.mime, it.bytes) }
                        else -> throw JbArchiveException("that destination is not connected yet")
                    }
                }
                board.kind == BoardKind.CANVAS && request.scope == Export.Scope.BOARD && request.format == Export.Format.PNG ->
                    sink.write("$base.png", "image/png", CanvasPng.encodeBoard(contents, board.id, request.includePaper, onWarning = onWarning))
                board.kind == BoardKind.SPRITE && request.scope == Export.Scope.BOARD && request.format == Export.Format.SPRITE_SHEET -> {
                    val grid = board.grid ?: throw JbArchiveException("this Sprite board has no grid")
                    val count = grid.cols.toLong()*grid.rows
                    val pixels = count*grid.cellW*grid.cellH
                    if (count !in 1..4096 || pixels*2 > AnimExportRunner.MAX_EXPORT_PX)
                        throw JbArchiveException("this sprite sheet is too large to export on this device")
                    val tiles = TileSource { layer, cel, x, y -> contents.tiles[Triple(layer,cel,DocOps.key(x,y))] }
                    val paper = CanvasPng.paperRendererFor(contents, request.includePaper, null, onWarning)
                    val colour = if(request.includePaper && !doc.paper.screenTransparent && paper == null) doc.paper.color else null
                    val cells = (0 until count.toInt()).map { index ->
                        RegionRenderer.render(doc, tiles, SpriteGridMath.cellRect(board,index), null, colour, paper).also { onFrame(index+1,count.toInt()) }
                    }
                    val packed = SpritePacker.pack(cells, grid.cellW, grid.cellH, grid.cols,
                        board.id, board.name, "$base.png", board.fps, emptyList())
                    sink.write("$base.png", "image/png", PngWriter.encode(packed.width,packed.height,packed.rgba))
                    sink.write("$base.sprite.json", "application/json", packed.sidecarJson.toByteArray(Charsets.UTF_8))
                }
                else -> throw JbArchiveException("that board export is not connected yet")
            }
            return StagedBoardExport(directory, files)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            if (failure is OutOfMemoryError) throw JbArchiveException("this export needs more memory; try PNG frames or a smaller range")
            throw failure
        }
    }
}
