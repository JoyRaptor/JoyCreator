package cc.joycreator.joybrush.android.library

import cc.joycreator.joybrush.androidkit.JbCanvasView.Companion.LEGACY_SHARED_DOC_ID
import com.fadcam.joyfolder.FileJoyFs
import com.fadcam.joyfolder.JoyFolder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The Note 9, 2026-10-31: "My drawing" was listed with no copy in the folder, so an uninstall would lose it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FolderBackfillTest {
    @get:Rule val temp = TemporaryFolder()

    private val app get() = RuntimeEnvironment.getApplication()
    private val mine = "current.joybrush"
    private lateinit var dir: File
    private lateinit var folder: File
    private lateinit var jf: JoyFolder

    @Before fun setUp() {
        dir = temp.newFolder("joybrush")
        folder = temp.newFolder("Joy Creator")
        jf = JoyFolder.open(FileJoyFs(folder))
    }

    private fun drawing(id: String, paint: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("document.json"))
            zip.write("""{"id":"$id","name":"Joy Brush"}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("paint"))
            zip.write(paint.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun list(entries: JSONObject) = File(dir, "library.json").writeText(JSONObject().put("drawings", entries).toString())
    private fun myDrawing() = JSONObject().put("name", "My drawing").put("created", 1L)
    private fun entry(file: String) = JSONObject(File(dir, "library.json").readText()).getJSONObject("drawings").getJSONObject(file)
    private fun pathOf(file: String) = entry(file).optString("folderPath", "")
    private fun backfill() = DrawingLibrary.backfillFolderCopies(app, jf, dir)

    @Test fun aListedDrawingWithNoFolderCopyGetsOne() {
        val bytes = drawing("jb-mine", "paint")
        File(dir, mine).writeBytes(bytes)
        list(JSONObject().put(mine, myDrawing()))
        backfill()
        assertEquals("My drawing.joybrush", pathOf(mine))
        assertArrayEquals(bytes, File(folder, "My drawing.joybrush").readBytes())
        assertArrayEquals(bytes, File(dir, mine).readBytes())
    }

    @Test fun aFileAlreadyUsingTheNameIsNeverOverwritten() {
        val stranger = drawing("jb-stranger", "the person's own file")
        File(folder, "My drawing.joybrush").writeBytes(stranger)
        val bytes = drawing("jb-mine", "paint")
        File(dir, mine).writeBytes(bytes)
        list(JSONObject().put(mine, myDrawing()))
        backfill()
        assertEquals("My drawing 2.joybrush", pathOf(mine))
        assertArrayEquals(stranger, File(folder, "My drawing.joybrush").readBytes())
        assertArrayEquals(bytes, File(folder, "My drawing 2.joybrush").readBytes())
    }

    @Test fun itsOwnFileMovedByThePersonIsRememberedNotWrittenOver() {
        val inFolder = drawing("jb-mine", "the copy in the folder")
        File(folder, "Renamed.joybrush").writeBytes(inFolder)
        File(dir, mine).writeBytes(drawing("jb-mine", "paint"))
        list(JSONObject().put(mine, myDrawing()))
        backfill()
        assertEquals("Renamed.joybrush", pathOf(mine))
        assertArrayEquals(inFolder, File(folder, "Renamed.joybrush").readBytes())
        assertEquals(1, folder.list()!!.count { it.endsWith(".joybrush") })
    }

    @Test fun anotherDrawingsFileIsNeverTakenEvenWithTheSameId() {
        val theirs = drawing("jb-mine", "another drawing's copy")
        File(folder, "Other.joybrush").writeBytes(theirs)
        val bytes = drawing("jb-mine", "paint")
        File(dir, mine).writeBytes(bytes)
        list(JSONObject().put(mine, myDrawing()).put("other.joybrush", JSONObject().put("name", "Other").put("folderPath", "Other.joybrush")))
        backfill()
        assertEquals("My drawing.joybrush", pathOf(mine))
        assertArrayEquals(theirs, File(folder, "Other.joybrush").readBytes())
        assertArrayEquals(bytes, File(folder, "My drawing.joybrush").readBytes())
    }

    @Test fun theOnceSharedIdDoesNotCountAsItsOwnFile() {
        val old = drawing(LEGACY_SHARED_DOC_ID, "some older drawing")
        File(folder, "Old.joybrush").writeBytes(old)
        File(dir, mine).writeBytes(drawing(LEGACY_SHARED_DOC_ID, "paint"))
        list(JSONObject().put(mine, myDrawing()))
        backfill()
        assertEquals("My drawing.joybrush", pathOf(mine))
        assertArrayEquals(old, File(folder, "Old.joybrush").readBytes())
    }

    @Test fun aDrawingNeverSavedHasNothingToCopy() {
        list(JSONObject().put("drawing-1.joybrush", JSONObject().put("name", "Drawing 2")))
        backfill()
        assertEquals("", pathOf("drawing-1.joybrush"))
        assertEquals(0, folder.list()!!.count { it.endsWith(".joybrush") })
    }

    @Test fun aSecondRunChangesNothing() {
        File(dir, mine).writeBytes(drawing("jb-mine", "paint"))
        list(JSONObject().put(mine, myDrawing()))
        backfill()
        val written = File(folder, "My drawing.joybrush").lastModified()
        val before = File(dir, "library.json").readText()
        backfill()
        assertEquals(before, File(dir, "library.json").readText())
        assertEquals(written, File(folder, "My drawing.joybrush").lastModified())
        assertEquals(1, folder.list()!!.count { it.endsWith(".joybrush") })
    }
}
