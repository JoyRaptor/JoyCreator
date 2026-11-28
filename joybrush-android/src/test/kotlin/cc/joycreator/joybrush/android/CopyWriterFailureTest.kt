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
import java.lang.reflect.InvocationTargetException

/**
 * "Save a copy…" when the place the artist chose fails as it is opened (JB-LIVE-SAVE, carried over from Codex's 11-04
 * branch `lead/live-goal-20261104`): the real writer has built a whole, readable archive first, the chosen file is never
 * written into, the failure comes back to the save (which tells the artist and keeps the drawing), and the temporary
 * whole drawing in the cache is gone afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CopyWriterFailureTest {
    private class FailingDestination(private val cacheDir: File, private val failure: Throwable) : ContentProvider() {
        var opens = 0; private set
        var wholeArchiveAtOpen = false; private set

        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            val temp = File(cacheDir, "joybrush-copy.tmp")
            wholeArchiveAtOpen = temp.isFile && runCatching { temp.inputStream().use { JbArchive.read(it) }.doc.id == DOC_ID }.getOrDefault(false)
            throw failure
        }
    }

    private fun contents(): JbContents =
        JbContents(DocOps.newDocument(DOC_ID, "Copy writer test", 1, 1) { java.util.UUID.randomUUID().toString() }, emptyMap(), emptyMap())

    /** The activity's own private writer, as the save's file thread calls it; what it threw, or null. */
    private fun writeCopy(activity: JoyBrushActivity, uri: Uri): Throwable? {
        val m = JoyBrushActivity::class.java.getDeclaredMethod("writeCopy", Uri::class.java, JbContents::class.java).apply { isAccessible = true }
        return try { m.invoke(activity, uri, contents()); null } catch (e: InvocationTargetException) { e.targetException }
    }

    private fun check(failure: Throwable, authority: String): Throwable? {
        val activity = Robolectric.buildActivity(JoyBrushActivity::class.java).get()
        val provider = FailingDestination(activity.cacheDir, failure)
        provider.attachInfo(activity, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val temp = File(activity.cacheDir, "joybrush-copy.tmp")
        try {
            temp.delete()
            val thrown = writeCopy(activity, Uri.parse("content://$authority/copy"))
            assertEquals("the chosen place is asked once", 1, provider.opens)
            assertTrue("a whole, readable archive is built before the chosen place is opened", provider.wholeArchiveAtOpen)
            assertFalse("the temporary whole drawing does not stay in the cache", temp.exists())
            return thrown
        } finally {
            temp.delete()
            activity.finish()
        }
    }

    @Test fun aPlaceThatCannotBeOpenedFailsTheCopyAndLeavesNoTemporary() {
        val thrown = check(FileNotFoundException("the chosen place went away"), "copy-io.test")
        assertTrue("the failure reaches the save: $thrown", thrown is FileNotFoundException)
        assertEquals("the chosen place went away", thrown?.message)
    }

    @Test fun runningOutOfMemoryAtTheChosenPlaceFailsTheCopyAndLeavesNoTemporary() {
        // The save's file thread turns this into "this device has too little memory..." (JoyBrushActivity, the save's catch).
        val thrown = check(OutOfMemoryError("injected"), "copy-oom.test")
        assertTrue("the failure reaches the save: $thrown", thrown is OutOfMemoryError)
    }

    private companion object { const val DOC_ID = "copy-writer-test" }
}
