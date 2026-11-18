package cc.joycreator.joybrush.androidkit.io

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.UUID

/** An Open request holds compressed bytes on disk, never a second canvas worth of paint tiles. */
class StagedDrawing private constructor(private val file: File) : Closeable {
    private var closed = false
    /** What [read] last handed out: its tiles may still be read from [file], which is held open for them. */
    private var handed: JbContents? = null

    /** Call only after the previous canvas snapshot has been written and released. */
    fun read(): JbContents {
        if (closed) throw JbArchiveException("the selected drawing is no longer available")
        // The paint state stays in this file until the canvas asks for it tile by tile (it is most of a painted drawing).
        // A file that is open stays readable after [close] deletes it.
        return JbArchive.openDeferringMedia(file).also { handed = it; CanvasSnapshot.requireSupportedPayload(it) }
    }

    override fun close() {
        closed = true
        // Let go, then delete: the canvas has taken the tiles by now (the screen closes this after its load), and a file
        // that is still held open cannot be deleted everywhere.
        handed?.let(DeferredMedia::release); handed = null
        file.delete()
    }

    companion object {
        /** The caller owns [input]. A private copy survives Recent pruning and provider changes. */
        fun stage(directory: File, input: InputStream): StagedDrawing {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw JbArchiveException("there is nowhere to prepare the selected drawing")
            }
            val file = File(directory, "open-${UUID.randomUUID()}.joybrush")
            try {
                file.outputStream().use { input.copyTo(it) }
                return StagedDrawing(file)
            } catch (e: Exception) {
                file.delete()
                throw JbArchiveException("the selected drawing could not be prepared: ${e.message}")
            }
        }
    }
}
