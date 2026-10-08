package cc.joycreator.joybrush.android.board

import android.app.Activity
import android.app.Dialog
import android.content.ContentResolver
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import cc.joycreator.joybrush.androidkit.io.*
import cc.joycreator.joybrush.core.chrome.BoardChromeIdentity
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout
import cc.joycreator.joybrush.core.chrome.BoardExportLayout as Export
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.export.AnimExport
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import java.io.File
import java.util.UUID

/** K6 host: presentation is shared; file writing remains serialized by the Activity's save queue. */
class BoardExportCoordinator(private val activity: Activity,
    private val choose: (BoardExportRequest, String, String) -> Unit,
    private val refusal: (String) -> Unit,
    private val frost: (android.graphics.Canvas,android.view.View) -> Unit = { _,_ -> },
    private val refreshBackdrop: (() -> Unit) -> Unit = {}) {
    private var dialog: Dialog? = null
    private val panels=BoardPanels(activity)

    fun dismiss() { panels.close(); dialog?.dismiss(); dialog = null }

    fun show(doc: JbDocument, boardId: String) {
        dismiss()
        val board = doc.boards.firstOrNull { it.id == boardId } ?: return refusal("That board no longer exists")
        if(board.kind !in listOf(BoardKind.CANVAS,BoardKind.ANIMATION,BoardKind.SPRITE))
            return refusal("This board's export is not connected yet")
        val animated = board.kind == BoardKind.ANIMATION
        val frames = if(animated) board.frames.map { it.id } else listOf(board.id)
        if(frames.isEmpty()) return refusal("This Animation board has no frames")
        var scope = if(animated) Export.Scope.ANIMATION else Export.Scope.BOARD
        var format = when(board.kind) { BoardKind.ANIMATION -> Export.Format.GIF; BoardKind.SPRITE -> Export.Format.SPRITE_SHEET; else -> Export.Format.PNG }
        var start = frames.first(); var end = frames.last()
        val density = activity.resources.displayMetrics.density
        val dp = { value: Int -> (value*density+.5f).toInt() }
        val sheet = ExportChoiceSheet(activity)
        val paper = CheckBox(activity).apply {
            text = "Include paper"; setTextColor(Color.WHITE)
            isChecked = doc.paper.includeInExport && !doc.paper.screenTransparent
            isEnabled = !doc.paper.screenTransparent
            contentDescription = "Include paper in exported images"
        }
        cc.joycreator.joybrush.android.chrome.ChromeKit(activity).surface(paper,12f)
        val container = FrameLayout(activity)
        container.addView(paper, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(36),Gravity.TOP).apply { leftMargin = dp(14) })
        container.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(236),Gravity.BOTTOM))
        lateinit var refresh: () -> Unit
        fun editRange() {
            panels.formValidated("Frames 1–${frames.size}",listOf("First frame" to "${frames.indexOf(start)+1}","Last frame" to "${frames.indexOf(end)+1}"),
                { values ->
                    val a=values[0].toIntOrNull(); val b=values[1].toIntOrNull()
                    if(a == null || b == null || a !in 1..frames.size || b !in a..frames.size) "Choose a range from 1 to ${frames.size}" else null
                }) { values ->
                start=frames[values[0].toInt()-1]; end=frames[values[1].toInt()-1]; refresh()
            }
        }
        sheet.host = object : ExportChoiceSheet.Host {
            override fun frost(canvas: android.graphics.Canvas,element: BoardChromeLayout.Element) = frost(canvas,sheet)
            override fun scope(board: BoardChromeIdentity, scope: Export.Scope) {
                if(board.boardId != boardId) return
                selectScope(scope)
            }
            private fun selectScope(next: Export.Scope) {
                scope = next
                format = when { next == Export.Scope.FRAME -> Export.Format.PNG
                    animated && format == Export.Format.PNG -> Export.Format.GIF
                    else -> format }
                refresh()
                if(next == Export.Scope.RANGE) editRange()
            }
            override fun format(board: BoardChromeIdentity, format: Export.Format) { if(board.boardId == boardId) selectFormat(format) }
            private fun selectFormat(next: Export.Format) { format = next; refresh() }
            override fun export(choice: Export.Choice) {
                try {
                    val request = BoardExportRequest.capture(doc,choice,paper.isChecked)
                    val base = AnimExport.safeBaseName(board.name)
                    val mime = if(request.format == Export.Format.GIF) "image/gif" else "image/png"
                    dismiss(); choose(request,base + if(request.folder) "" else if(request.format == Export.Format.GIF) ".gif" else ".png",mime)
                } catch(e: Exception) { refusal(e.message ?: "Could not prepare this export") }
            }
        }
        refresh = {
            val scopes = when(board.kind) {
                BoardKind.ANIMATION -> listOf(Export.Scope.ANIMATION,Export.Scope.RANGE,Export.Scope.FRAME)
                BoardKind.CANVAS -> listOf(Export.Scope.BOARD,Export.Scope.ALL_IMAGES)
                else -> listOf(Export.Scope.BOARD)
            }
            val formats = when {
                scope == Export.Scope.FRAME || board.kind == BoardKind.CANVAS -> listOf(Export.Format.PNG)
                board.kind == BoardKind.SPRITE -> listOf(Export.Format.SPRITE_SHEET,Export.Format.SPRITE_LAB)
                else -> Export.chipFormats
            }
            val count = when(scope) {
                Export.Scope.FRAME -> 1; Export.Scope.RANGE -> frames.indexOf(end)-frames.indexOf(start)+1; else -> frames.size
            }
            val cellPixels = board.rect.w.toLong()*board.rect.h
            val decoded = when(format) {
                Export.Format.GIF -> cellPixels*count
                Export.Format.SPRITE_SHEET, Export.Format.STUDIO, Export.Format.SPRITE_LAB -> if(animated) {
                    val cols = AnimExport.sheetCols(count)
                    cellPixels*(count + ((count.toLong()+cols-1)/cols)*cols)
                } else cellPixels*2
                else -> cellPixels
            }
            val limit = if(format == Export.Format.PNG) MAX_REGION_PX else MAX_REGION_PX*2L
            val available = formats.toSet()
            val width = container.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            sheet.show(Export.Input(BoardChromeLayout.Rect(0f,0f,width.toFloat(),dp(236).toFloat()),density,
                board.id,board.name,frames,board.currentFrameId ?: frames.first(),start,end,board.rect.w,board.rect.h,
                if(animated) board.fps.toInt().toString() else "",doc.layers.size,(decoded.toDouble()/limit).toFloat().coerceIn(0f,1f),
                scope,format,scopes.toSet(),available,decoded <= limit && cellPixels <= MAX_REGION_PX,
                rangePreviewStart = frames.indexOf(start).toFloat()/frames.size,
                rangePreviewEnd = (frames.indexOf(end)+1).toFloat()/frames.size,
                displayedScopes = scopes,displayedFormats = formats,
                cellCount=board.grid?.let { it.cols*it.rows }))
            refreshBackdrop { sheet.invalidateBackdrop() }
        }
        dialog = Dialog(activity).apply {
            setContentView(container,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(272)))
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setOnDismissListener { panels.close(); sheet.stopInteractions() }
            show()
            window?.setGravity(Gravity.BOTTOM)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        container.post { refresh() }
    }

    companion object {
        fun save(request: BoardExportRequest) = Bundle().apply {
            putString("document",request.documentId); putString("board",request.boardId)
            putString("kind",request.kind.name); putString("scope",request.scope.name); putString("format",request.format.name)
            putString("frame",request.frameId); putString("start",request.startId); putString("end",request.endId)
            putStringArrayList("frames",ArrayList(request.frameIds)); putStringArrayList("images",ArrayList(request.imageIds))
            putBoolean("paper",request.includePaper)
        }
        fun restore(bundle: Bundle?): BoardExportRequest? = try {
            bundle?.let { BoardExportRequest(it.getString("document")!!,it.getString("board")!!,
                BoardKind.valueOf(it.getString("kind")!!),Export.Scope.valueOf(it.getString("scope")!!),Export.Format.valueOf(it.getString("format")!!),
                it.getString("frame"),it.getString("start"),it.getString("end"),it.getStringArrayList("frames")!!.toList(),
                it.getStringArrayList("images")!!.toList(),it.getBoolean("paper")) }
        } catch(_: Exception) { null }

        /** A fresh subfolder avoids overwriting files from earlier exports or somebody else's work. */
        fun write(resolver: ContentResolver, uri: Uri, contents: JbContents, request: BoardExportRequest, cache: File,
            progress: (Int,Int) -> Unit = { _, _ -> }) {
            BoardExport.stage(contents,request,cache,onFrame = progress).use { staged ->
                if(!request.folder) {
                    require(staged.files.size == 1)
                    val output = resolver.openOutputStream(uri,"wt") ?: throw JbArchiveException("the export destination could not be opened")
                    output.use { out -> staged.files.single().file.inputStream().use { it.copyTo(out) } }
                } else {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri))
                    val base = AnimExport.safeBaseName(contents.doc.boards.first { it.id == request.boardId }.name)
                    val folder = DocumentsContract.createDocument(resolver,parent,DocumentsContract.Document.MIME_TYPE_DIR,
                        "$base export ${UUID.randomUUID().toString().take(8)}") ?: throw JbArchiveException("the export folder could not be created")
                    var written = 0
                    try {
                        staged.files.forEach { file ->
                            val child = DocumentsContract.createDocument(resolver,folder,file.mime,file.file.name)
                                ?: throw JbArchiveException("${file.file.name} could not be created")
                            val output = resolver.openOutputStream(child,"wt") ?: throw JbArchiveException("${file.file.name} could not be opened")
                            output.use { out -> file.file.inputStream().use { it.copyTo(out) } }
                            written++
                        }
                    } catch(e: Exception) {
                        val removed = try { DocumentsContract.deleteDocument(resolver,folder) } catch(_: Exception) { false }
                        throw JbArchiveException("Wrote $written of ${staged.files.size} files. " +
                            if(removed) "The incomplete export was removed. ${e.message}" else "An incomplete export folder remains. ${e.message}")
                    }
                }
            }
        }
    }
}
