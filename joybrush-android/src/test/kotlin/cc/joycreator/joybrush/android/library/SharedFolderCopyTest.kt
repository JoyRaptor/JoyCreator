package cc.joycreator.joybrush.android.library

import cc.joycreator.joybrush.androidkit.JbCanvasView.Companion.LEGACY_SHARED_DOC_ID
import com.fadcam.joyfolder.FileJoyFs
import com.fadcam.joyfolder.JoyFolder
import org.json.JSONObject
import org.junit.Assert.*
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
 * The Note 9, 2026-10-31: "Drawing 2 test" and "Drawing 3" had one folder path, so saving Drawing 3 replaced the Drawing 2
 * test copy in the folder. Real files, a real folder and the real list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedFolderCopyTest {
    @get:Rule val temp = TemporaryFolder()

    private val app get() = RuntimeEnvironment.getApplication()
    private val two = "drawing-1792100806803-1.joybrush"
    private val three = "drawing-1792348378213.joybrush"
    private val sharedName = "Drawing 2 test.joybrush"
    private val twoBytes = drawing("jb-two", "the real Drawing 2 test")
    private val threeBytes = drawing("jb-three", "Drawing 3")

    private lateinit var dir: File
    private lateinit var folder: File
    private lateinit var jf: JoyFolder

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

    /** The phone as found: both listed at one path, the folder file holding Drawing 3. */
    private fun phoneAsFound(inFolder: ByteArray = threeBytes) {
        dir = temp.newFolder("joybrush")
        folder = temp.newFolder("Joy Creator")
        File(dir, two).writeBytes(twoBytes)
        File(dir, three).writeBytes(threeBytes)
        File(folder, sharedName).writeBytes(inFolder)
        File(dir, "library.json").writeText(
            JSONObject().put("drawings", JSONObject()
                .put(two, JSONObject().put("name", "Drawing 2 test").put("docId", "jb-two").put("folderPath", sharedName))
                .put(three, JSONObject().put("name", "Drawing 3").put("docId", "jb-three").put("folderPath", sharedName))
            ).toString(),
        )
        jf = JoyFolder.open(FileJoyFs(folder))
    }

    private fun pathOf(file: String) =
        JSONObject(File(dir, "library.json").readText()).getJSONObject("drawings").getJSONObject(file).getString("folderPath")

    private fun inFolder(rel: String) = File(folder, rel).readBytes()

    @Test fun savingOneOfTwoSharingDrawingsLeavesEachWithItsOwnCopy() {
        phoneAsFound()
        val rel = DrawingLibrary.mirrorFile(app, jf, dir, three, "jb-three")
        assertEquals("Drawing 3.joybrush", rel)
        assertEquals(sharedName, pathOf(two))
        assertEquals("Drawing 3.joybrush", pathOf(three))
        assertArrayEquals(twoBytes, inFolder(sharedName))
        assertArrayEquals(threeBytes, inFolder("Drawing 3.joybrush"))
    }

    @Test fun laterSavesStayInTheirOwnFiles() {
        phoneAsFound()
        DrawingLibrary.mirrorFile(app, jf, dir, three, "jb-three")
        val newer = drawing("jb-three", "Drawing 3, more paint")
        File(dir, three).writeBytes(newer)
        DrawingLibrary.mirrorFile(app, jf, dir, three, "jb-three")
        assertArrayEquals(newer, inFolder("Drawing 3.joybrush"))
        assertArrayEquals(twoBytes, inFolder(sharedName))
    }

    @Test fun aSweepWithNoSaveRepairsItToo() {
        phoneAsFound()
        DrawingLibrary.separateSharedCopies(app, jf, dir)
        assertNotEquals(pathOf(two), pathOf(three))
        assertArrayEquals(twoBytes, inFolder(pathOf(two)))
        assertArrayEquals(threeBytes, inFolder(pathOf(three)))
    }

    @Test fun whenTheFileIsTheOtherDrawingNothingMoves() {
        phoneAsFound(inFolder = twoBytes)
        DrawingLibrary.separateSharedCopies(app, jf, dir)
        assertEquals(sharedName, pathOf(two))
        assertEquals("Drawing 3.joybrush", pathOf(three))
        assertArrayEquals(twoBytes, inFolder(sharedName))
        assertArrayEquals(threeBytes, inFolder("Drawing 3.joybrush"))
    }

    @Test fun aFileThatIsNeitherDrawingIsLeftUntouched() {
        val stranger = drawing("jb-stranger", "something the person put here")
        phoneAsFound(inFolder = stranger)
        DrawingLibrary.separateSharedCopies(app, jf, dir)
        assertArrayEquals(stranger, inFolder(sharedName))
        assertEquals(setOf("Drawing 2 test 2.joybrush", "Drawing 3.joybrush"), setOf(pathOf(two), pathOf(three)))
        assertArrayEquals(twoBytes, inFolder(pathOf(two)))
        assertArrayEquals(threeBytes, inFolder(pathOf(three)))
    }

    @Test fun nothingSharedMeansNothingChanges() {
        phoneAsFound()
        DrawingLibrary.separateSharedCopies(app, jf, dir)
        val list = File(dir, "library.json").readText()
        DrawingLibrary.separateSharedCopies(app, jf, dir)
        assertEquals(list, File(dir, "library.json").readText())
    }

    @Test fun theOnceSharedIdOwnsNothing() {
        assertNull(FolderAdoption.owner(listOf("a"), LEGACY_SHARED_DOC_ID) { LEGACY_SHARED_DOC_ID })
        assertNull(FolderAdoption.owner(listOf("a"), null) { "jb-a" })
        assertEquals("b", FolderAdoption.owner(listOf("a", "b"), "jb-b") { "jb-$it" })
    }

    @Test fun onlyPathsWithTwoDrawingsCountAsShared() {
        assertEquals(mapOf("x" to listOf("a", "c")), FolderAdoption.shared(mapOf("c" to "x", "a" to "x", "b" to "y")))
    }
}
