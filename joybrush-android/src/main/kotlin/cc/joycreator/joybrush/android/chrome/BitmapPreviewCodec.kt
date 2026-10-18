package cc.joycreator.joybrush.android.chrome

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Brush previews as PNG files: lossless, and the transparency of an ink preview is kept. */
object BitmapPreviewCodec : PreviewCodec<Bitmap> {

    override fun write(value: Bitmap, file: File): Boolean =
        FileOutputStream(file).use { value.compress(Bitmap.CompressFormat.PNG, 100, it) }

    override fun read(file: File, width: Int, height: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = true
        }
        val bmp = BitmapFactory.decodeFile(file.path, options) ?: return null
        if (bmp.width == width && bmp.height == height) return bmp
        // Another size than its name says: damaged. Throw it away rather than stretch it.
        bmp.recycle()
        file.delete()
        return null
    }

    override fun bytes(value: Bitmap): Long = value.allocationByteCount.toLong()

    /** One background thread at the lowest priority for the folder, so reading and writing PNGs never competes with painting. */
    fun ioThread(): Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "brush-preview-io").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }

    fun uiThread(): Executor = Handler(Looper.getMainLooper()).let { h -> Executor { h.post(it) } }
}
