package cc.joycreator.joybrush.core.brush

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The brushes that ship with Joy Brush, read from the disk they actually live on.
 *
 * `BrushTest` (commonTest) carries byte-identical copies of these files because commonTest cannot open
 * a file and must stay platform-neutral — which means a file edited on disk drifts from its copy
 * silently. This is the test that notices: it walks every `joybrush/brushes/<folder>/brush.json`
 * the way
 * `WriteGrainAssets` finds the `joybrush/` folder, and every one of them must decode and validate
 * with zero problems. It is also the canary for a new rule in [BrushValidate]: a rule that refuses a
 * brush the app has been shipping fails here first.
 *
 * **The `joybrush/` walk lives in [joybrushRoot]**, beside [DefaultPresetsTest], because that class reads
 * the same disk and two walks of the same kind are two things to get wrong. It is also the class that
 * reads `index.txt`, which is a FILE rather than a folder: a folder nobody listed in it validates,
 * round-trips and is never shown to anybody, which is how a brush can be "shipped" for a whole phase and
 * never once seen. The three additions below close that from both directions.
 */
class ShippedBrushFilesTest {

    @Test
    fun everyShippedBrushFileOnDiskDecodesAndValidatesClean() {
        val brushes = File(joybrushRoot(), "brushes")
        val folders = (brushes.listFiles() ?: emptyArray<File>())
            .filter { it.isDirectory && File(it, "brush.json").isFile }
            .sortedBy { it.name }
        assertTrue(
            folders.isNotEmpty(),
            "no brush folders with a brush.json in ${brushes.absolutePath}",
        )

        val ids = ArrayList<String>()
        for (folder in folders) {
            val file = File(folder, "brush.json")
            val preset = BrushJson.decode(file.readText())
            val problems = BrushValidate.validate(preset)
            assertEquals(emptyList(), problems, "${file.absolutePath} must load clean, but: $problems")
            assertTrue(preset.id.isNotBlank(), "${file.absolutePath} has no id")
            ids.add(preset.id)
            // What ships is what the app writes back out, so a save cannot quietly change a brush.
            assertEquals(preset, BrushJson.decode(BrushJson.encode(preset)), "${file.absolutePath} does not round-trip")
        }

        // Two files claiming one id would make the second one unreachable in the brush shelf.
        assertEquals(ids.size, ids.toSet().size, "two brush files share an id: $ids")
        // Every shipped brush, not the two the suite started with: the file is a shipped set and the
        // message says so, because "the two example brushes" stops being true the moment there are six.
        // `fill` is the bare id, not `joybrush.fill` — a pre-existing inconsistency (R21 made the fill
        // pen a brush, and renaming a shipped id is a compatibility decision, not a tidy-up).
        assertTrue(
            listOf("joybrush.ink", "joybrush.pencil", "joybrush.marker", "joybrush.softair", "joybrush.eraser", "fill")
                .all { ids.contains(it) },
            "the shipped brushes must be on disk, found $ids",
        )
    }

    // ---- index.txt: the order IS the product (JB-1.07) -----------------------------------------------
    //
    // `BrushLibrary.folderNames()` reads this file, drops blanks and `#` comments and keeps everything
    // else IN FILE ORDER, and the pill cycles in that order — so line 1 is the brush the Joy Brush
    // screen opens with. Nothing else in the build knows that, which is why it is pinned here.

    @Test
    fun everyFolderIsInTheIndexAndEveryIndexNameIsAFolder() {
        val brushes = brushesDir()
        val listed = indexFolderNames()
        val folders = brushFolders().map { it.name }

        assertEquals(
            folders.toSet(), listed.toSet(),
            "brushes/index.txt and the folders on disk disagree. In the file but no folder: " +
                "${listed - folders}. A folder but not in the file (it would validate, round-trip and " +
                "never be shown to anybody): ${folders - listed}.",
        )
        assertEquals(
            listed.size, listed.toSet().size,
            "brushes/index.txt names a folder twice; the pill would show that brush twice: $listed",
        )
    }

    /**
     * The exact order, as a SEQUENCE rather than as a set, because the order is what a person sees.
     *
     * The expected list is the FINISHED order, and any folder that does not exist yet is removed from
     * both sides of the comparison with the removal named in the failure. Smudge and Nudge are absent
     * today — they are wave two, refused by name by this build — so wave two is two inserted lines here
     * and not a re-order, and this test is green both before and after it.
     */
    @Test
    fun theIndexOrderIsTheFinishedOrderWithWhateverHasNotLandedRemoved() {
        val finished = listOf("ink", "sable", "pencil", "marker", "softair", "smudge", "nudge", "eraser", "fill")
        val landed = brushFolders().map { it.name }.toSet()
        val notYet = finished.filterNot { landed.contains(it) }
        val expected = finished.filter { landed.contains(it) }

        assertEquals(
            expected, indexFolderNames(),
            "brushes/index.txt is not in the pill's order. Expected $expected. Not on disk yet, and " +
                "so removed from the expected list: $notYet. Folders on disk: $landed.",
        )
        assertEquals("ink", indexFolderNames().first(), "line 1 is the brush the screen opens with")
    }

    /**
     * A folder somebody made and forgot. The walk above filters on `brush.json` being a file, which is
     * right for a folder holding a `tip.png` and wrong for a folder someone created and never filled in —
     * so the folder vanishes from every test above rather than failing one. Naming the folder in a
     * COMMENT here is the escape hatch, so a future `assets/` folder needs no renaming.
     */
    @Test
    fun aFolderWithNoBrushJsonMustBeNamedInAnIndexComment() {
        val brushes = brushesDir()
        val empty = (brushes.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !File(it, "brush.json").isFile }
            .map { it.name }
        if (empty.isEmpty()) return
        val index = File(brushes, "index.txt").readText()
        val forgotten = empty.filterNot { name ->
            index.lineSequence().any { it.trimStart().startsWith("#") && it.contains(name) }
        }
        assertEquals(
            emptyList(), forgotten,
            "these folders have no brush.json and are not named in a comment in brushes/index.txt, so " +
                "they are invisible to BrushLibrary and to every test above: $forgotten. Give the folder a " +
                "brush.json, or name it in a `#` comment in index.txt if it is not a brush.",
        )
    }

    /** `index.txt`'s folder names, the way `BrushLibrary.folderNames()` reads them. */
    private fun indexFolderNames(): List<String> =
        File(brushesDir(), "index.txt").readText().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
}
