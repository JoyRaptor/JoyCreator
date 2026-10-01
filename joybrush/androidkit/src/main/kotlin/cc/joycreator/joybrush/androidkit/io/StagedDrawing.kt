package cc.joycreator.joybrush.androidkit.io

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.UUID

/** An Open request holds compressed bytes on disk, never a second canvas worth of paint tiles. */
class StagedDrawing private constructor(private val file: File) : Closeable {
    private var closed = false

    /** Call only after the previous canvas snapshot has been written and released. */
    fun read(): JbContents {
        if (closed) throw JbArchiveException("the selected drawing is no longer available")
        return JbArchive.open(file)
    }

    override fun close() {
        closed = true
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
