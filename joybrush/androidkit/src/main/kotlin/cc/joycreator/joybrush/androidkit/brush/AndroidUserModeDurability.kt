package cc.joycreator.joybrush.androidkit.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import cc.joycreator.joybrush.androidkit.io.FrozenBrushAssets
import java.io.FileDescriptor
import java.nio.file.Path

/** Small typed seam for JVM failure tests; real directory descriptors stay inside the Android adapter. */
interface UserModeDirectoryCalls {
    fun openDirectory(directory: Path): FileDescriptor
    fun fsync(descriptor: FileDescriptor)
    fun close(descriptor: FileDescriptor)
}

private object AndroidDirectoryCalls : UserModeDirectoryCalls {
    override fun openDirectory(directory: Path): FileDescriptor {
        // O_DIRECTORY is not exposed by the public Android SDK. Verify the opened descriptor
        // itself, rather than checking the path before open (which would admit a replacement race).
        val descriptor = Os.open(directory.toString(),
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try {
            require(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "The mode store path is not a directory" }
            return descriptor
        } catch (error: Throwable) {
            try { Os.close(descriptor) } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    }
    override fun fsync(descriptor: FileDescriptor) = Os.fsync(descriptor)
    override fun close(descriptor: FileDescriptor) = Os.close(descriptor)
}

/** Real Android directory durability. An fsync OR close failure refuses success; neither is swallowed. */
class AndroidUserModeDurability(private val calls: UserModeDirectoryCalls = AndroidDirectoryCalls) : UserModePersistence {
    override fun syncDirectory(directory: Path) {
        val descriptor = calls.openDirectory(directory)
        var primary: Throwable? = null
        try { calls.fsync(descriptor) }
        catch (error: Throwable) { primary = error; throw error }
        finally {
            try { calls.close(descriptor) }
            catch (cleanup: Throwable) {
                val first = primary
                if (first == null) throw cleanup else if (first !== cleanup) first.addSuppressed(cleanup)
            }
        }
    }

    override fun publishNew(staging: Path, destination: Path) = UserModePersistence.Nio.publishNew(staging, destination)
}

/** Construct on the app's private storage; call listing/loading/saving on an IO worker. */
fun createAndroidUserModeStore(context: Context): UserModeStore = UserModeStore(
    root = context.filesDir.toPath().resolve("unibrush-user-modes"),
    persistence = AndroidUserModeDurability(),
    verifier = FrozenBrushAssets.PngVerifier { bytes, width, height ->
        // FrozenBrushAssets reserves every encoded size/header/set pixel budget before this callback.
        // It calls verifiers sequentially; no collection of decoded bitmaps is retained here.
        val options = BitmapFactory.Options().apply {
            inScaled = false; inPremultiplied = false; inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw java.io.IOException("The user mode contains an undecodable PNG")
        try {
            if (bitmap.width != width || bitmap.height != height)
                throw java.io.IOException("The user-mode PNG dimensions changed during decoding")
        } finally { bitmap.recycle() }
    },
)
