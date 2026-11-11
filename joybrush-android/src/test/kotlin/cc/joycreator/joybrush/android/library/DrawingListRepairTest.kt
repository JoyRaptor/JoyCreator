package cc.joycreator.joybrush.android.library

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

/**
 * Work-loss audit 2026-11-10: the drawing list is one small file. Losing it must never cost the drawings, and a
 * change of Joy Creator folder must not leave drawings out of the new one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DrawingListRepairTest {
    @get:Rule val temp = TemporaryFolder()

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var dir: File
    private lateinit var folder: File
    private lateinit var jf: JoyFolder

    @Before fun setUp() {
        dir = DrawingLibrary.dir(app)!!
        dir.listFiles()?.forEach { it.deleteRecursively() }
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

    private val index get() = File(dir, "library.json")
    private fun names() = DrawingLibrary.list(app).map { it.name }.sorted()
    private fun entry(file: String) = JSONObject(index.readText()).getJSONObject("drawings").getJSONObject(file)

    private fun twoDrawings() {
        File(dir, "drawing-1.joybrush").writeBytes(drawing("jb-one", "one"))
        File(dir, "drawing-2.joybrush").writeBytes(drawing("jb-two", "two"))
        IndexFile.write(index, JSONObject().put("drawings", JSONObject()
            .put("drawing-1.joybrush", JSONObject().put("name", "Dino walk").put("created", 1L))
            .put("drawing-2.joybrush", JSONObject().put("name", "Castle").put("created", 2L))).toString())
    }

    @Test fun aDamagedListIsReadFromItsBackup() {
        twoDrawings()
        // One more write, so the list as it was is now the backup.
        IndexFile.write(index, index.readText())
        index.writeText("{\"drawings\":{\"drawing-1.joy")   // a write cut off part-way
        assertEquals(listOf("Castle", "Dino walk"), names())
    }

    @Test fun anEmptyFileIsNotAnEmptyList() {
        twoDrawings()
        IndexFile.write(index, index.readText())
        index.writeText("")   // the name reached the disk, the bytes did not
        assertEquals(listOf("Castle", "Dino walk"), names())
    }

    @Test fun aDamagedListNeverPushesOutAGoodBackup() {
        twoDrawings()
        IndexFile.write(index, index.readText())
        index.writeText("not a list")
        // Opening Joy Brush writes the list (last opened). The backup must still be the good one afterwards.
        DrawingLibrary.fileToOpen(app, null)
        index.writeText("damaged again")
        assertTrue(names().containsAll(listOf("Castle", "Dino walk")))
    }

    @Test fun withTheListAndItsBackupGoneEveryDrawingFileIsStillListed() {
        twoDrawings()
        index.delete()
        IndexFile.backupOf(index).delete()
        val listed = DrawingLibrary.list(app)
        assertEquals(setOf("drawing-1.joybrush", "drawing-2.joybrush"), listed.map { it.file }.toSet())
        assertEquals(listOf("Recovered drawing 1", "Recovered drawing 2"), listed.map { it.name }.sorted())
        // Each keeps the id inside its file, so the folder sync does not bring it in a second time.
        assertEquals("jb-one", entry("drawing-1.joybrush").getString("docId"))
        assertEquals("jb-two", entry("drawing-2.joybrush").getString("docId"))
    }

    @Test fun aRecoveredDrawingTakesItsNameBackFromItsFolderCopy() {
        twoDrawings()
        File(folder, "Dino walk.joybrush").writeBytes(drawing("jb-one", "the copy in the folder"))
        index.delete()
        IndexFile.backupOf(index).delete()
        DrawingLibrary.list(app)
        DrawingLibrary.backfillFolderCopies(app, jf, dir)
        assertEquals("Dino walk", entry("drawing-1.joybrush").getString("name"))
        assertEquals("Dino walk.joybrush", entry("drawing-1.joybrush").getString("folderPath"))
        assertFalse(entry("drawing-1.joybrush").has("recovered"))
    }

    @Test fun aSaveBackupOrAHalfWrittenFileIsNotADrawing() {
        twoDrawings()
        File(dir, "drawing-1.joybrush.bak").writeBytes(drawing("jb-one", "older"))
        File(dir, "drawing-3.joybrush.tmp").writeBytes(drawing("jb-three", "half"))
        File(dir, "notes.joybrush").writeText("not an archive")
        assertEquals(listOf("Castle", "Dino walk"), names())
    }

    @Test fun afterAChangeOfFolderEveryDrawingGetsACopyInTheNewOne() {
        twoDrawings()
        // Both were copied into the OLD folder, at these places.
        val old = JSONObject(index.readText())
        old.getJSONObject("drawings").getJSONObject("drawing-1.joybrush").put("folderPath", "Dino walk.joybrush").put("docId", "jb-one")
        old.getJSONObject("drawings").getJSONObject("drawing-2.joybrush").put("folderPath", "Art/Castle.joybrush").put("docId", "jb-two")
        IndexFile.write(index, old.toString())
        // The new folder is empty.
        DrawingLibrary.backfillFolderCopies(app, jf, dir)
        assertArrayEquals(File(dir, "drawing-1.joybrush").readBytes(), File(folder, "Dino walk.joybrush").readBytes())
        assertArrayEquals(File(dir, "drawing-2.joybrush").readBytes(), File(folder, "Castle.joybrush").readBytes())
        assertEquals("Castle.joybrush", entry("drawing-2.joybrush").getString("folderPath"))
    }

    @Test fun aCopyThatIsStillThereIsLeftAlone() {
        twoDrawings()
        val inFolder = drawing("jb-one", "the copy in the folder")
        File(folder, "Dino walk.joybrush").writeBytes(inFolder)
        val old = JSONObject(index.readText())
        old.getJSONObject("drawings").getJSONObject("drawing-1.joybrush").put("folderPath", "Dino walk.joybrush").put("docId", "jb-one")
        old.getJSONObject("drawings").remove("drawing-2.joybrush")
        File(dir, "drawing-2.joybrush").delete()
        IndexFile.write(index, old.toString())
        DrawingLibrary.backfillFolderCopies(app, jf, dir)
        assertArrayEquals(inFolder, File(folder, "Dino walk.joybrush").readBytes())
        assertEquals(1, folder.list()!!.count { it.endsWith(".joybrush") })
    }
}
