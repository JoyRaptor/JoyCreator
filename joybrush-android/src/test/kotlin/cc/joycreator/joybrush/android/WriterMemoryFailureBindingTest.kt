package cc.joycreator.joybrush.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.core.doc.DocOps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

/** Exercises the real Activity archive-copy writer through resolver failures at destination open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WriterMemoryFailureBindingTest {
    private class FailingDestinationProvider(
        private val cacheDir: File,
        private val failure: Throwable,
    ) : ContentProvider() {
        private val original = "preexisting destination".encodeToByteArray()
        private var destinationBytes = original.copyOf()
        var opens = 0
            private set
        var validArchiveAtOpen = false
            private set

        fun originalBytes() = original.copyOf()
        fun destinationBytes() = destinationBytes.copyOf()

        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
                            selectionArgs: Array<out String>?): Int = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            val temp = File(cacheDir, "joybrush-copy.tmp")
            validArchiveAtOpen = temp.isFile && runCatching {
                temp.inputStream().use { JbArchive.read(it) }.doc.id == "writer-memory-test"
            }.getOrDefault(false)
            throw failure
        }
    }

    private fun contents(): JbContents {
        val doc = DocOps.newDocument("writer-memory-test", "Writer memory test", 1, 1) { java.util.UUID.randomUUID().toString() }
        return JbContents(doc, emptyMap(), emptyMap())
    }

    private fun invokeWriter(activity: JoyBrushActivity, uri: Uri, content: JbContents): String? {
        val activityClass = JoyBrushActivity::class.java
        val copyClass = Class.forName("cc.joycreator.joybrush.android.JoyBrushActivity\$SaveDest\$Copy")
        val destination = copyClass.getDeclaredConstructor(Uri::class.java).apply { isAccessible = true }.newInstance(uri)
        val writer = activityClass.declaredMethods.single { it.name == "writeSaveContents" && it.parameterCount == 3 }
            .apply { isAccessible = true }
        return writer.invoke(activity, destination, content, null) as String?
    }

    private fun register(activity: JoyBrushActivity, authority: String, failure: Throwable): FailingDestinationProvider {
        val provider = FailingDestinationProvider(activity.cacheDir, failure)
        provider.attachInfo(activity, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return provider
    }

    @Test fun outOfMemoryFromActualCopyDestinationOpenIsBoundedAndPreservesDestination() {
        val activity = Robolectric.buildActivity(JoyBrushActivity::class.java).get()
        val uri = Uri.parse("content://writer-oom.test/copy")
        val provider = register(activity, "writer-oom.test", OutOfMemoryError("injected writer OOM"))
        val temp = File(activity.cacheDir, "joybrush-copy.tmp")
        val destinationBefore = provider.destinationBytes()
        try {
            temp.delete()
            val problem = invokeWriter(activity, uri, contents())
            val message = requireNotNull(problem)
            assertTrue(message.isNotBlank())
            assertFalse(message.contains("injected writer OOM"))
            assertEquals(1, provider.opens)
            assertTrue("the production writer must build a valid archive before opening the chosen destination",
                provider.validArchiveAtOpen)
            assertArrayEquals(provider.originalBytes(), destinationBefore)
            assertArrayEquals(destinationBefore, provider.destinationBytes())
            assertFalse("copy temp is cleaned after destination failure", temp.exists())
        } finally {
            temp.delete()
            activity.finish()
        }
    }

    @Test fun ioFailureFromActualCopyDestinationOpenIsReturnedAndCleansTemporaryArchive() {
        val activity = Robolectric.buildActivity(JoyBrushActivity::class.java).get()
        val uri = Uri.parse("content://writer-io.test/copy")
        val provider = register(activity, "writer-io.test", FileNotFoundException("injected destination IO failure"))
        val temp = File(activity.cacheDir, "joybrush-copy.tmp")
        val destinationBefore = provider.destinationBytes()
        try {
            temp.delete()
            val problem = invokeWriter(activity, uri, contents())
            assertEquals("injected destination IO failure", problem)
            assertEquals(1, provider.opens)
            assertTrue(provider.validArchiveAtOpen)
            assertArrayEquals(provider.originalBytes(), destinationBefore)
            assertArrayEquals(destinationBefore, provider.destinationBytes())
            assertFalse(temp.exists())
        } finally {
            temp.delete()
            activity.finish()
        }
    }
}
