package cc.joycreator.joybrush.android.board

import cc.joycreator.joybrush.androidkit.io.JbArchiveException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** EXPORT-FORMATS audit 11-24: an export that did not arrive whole is never left looking like a good file. */
class ExportWriteTest {

    private val picture = ByteArray(1000) { it.toByte() }

    @Test
    fun aWholeWriteIsLeftAlone() {
        val place = ByteArrayOutputStream()
        var removed = false
        ExportWrite.write(picture, { place }, { place.size().toLong() }, { removed = true; true })
        assertArrayEquals(picture, place.toByteArray())
        assertTrue("nothing removed after a good write", !removed)
    }

    @Test
    fun aWriteThatDiesPartWayIsRemovedAndSaidSo() {
        var removed = false
        val dying = object : OutputStream() {
            var n = 0
            override fun write(b: Int) { if (++n > 400) throw IOException("No space left on device") }
        }
        try {
            ExportWrite.write(picture, { dying }, { 400L }, { removed = true; true })
            fail("a write that died must not report success")
        } catch (e: JbArchiveException) {
            assertTrue(removed)
            assertTrue(e.message!!, e.message!!.contains("did not arrive whole") && e.message!!.contains("removed"))
            assertTrue(e.message!!, e.message!!.contains("No space left on device"))
        }
    }

    @Test
    fun aShortFileFoundAfterTheWriteIsRemovedAndSaidSo() {
        var removed = false
        try {
            ExportWrite.write(picture, { ByteArrayOutputStream() }, { 512L }, { removed = true; true })
            fail("a short file must not report success")
        } catch (e: JbArchiveException) {
            assertTrue(removed)
            assertTrue(e.message!!, e.message!!.contains("512 of 1000 bytes"))
        }
    }

    @Test
    fun whenTheFolderCannotRemoveItThePersonIsToldToDeleteIt() {
        try {
            ExportWrite.write(picture, { ByteArrayOutputStream() }, { 10L }, { false })
            fail()
        } catch (e: JbArchiveException) {
            assertTrue(e.message!!, e.message!!.contains("could not be removed; delete it and export again"))
        }
    }

    @Test
    fun aFolderThatCannotSayItsSizeIsTrustedAfterAGoodWrite() {
        val place = ByteArrayOutputStream()
        ExportWrite.write(picture, { place }, { null }, { fail("nothing to remove"); true })
        assertEquals(1000, place.size())
    }

    @Test
    fun aPlaceThatCannotBeOpenedIsRefusedInWords() {
        try {
            ExportWrite.write(picture, { null }, { null }, { true })
            fail()
        } catch (e: JbArchiveException) {
            assertEquals("the export destination could not be opened", e.message)
        }
    }
}
