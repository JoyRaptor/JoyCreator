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
        assertTrue(
            listOf("joybrush.ink", "joybrush.pencil").all { ids.contains(it) },
            "the two example brushes must be on disk, found $ids",
        )
    }

    /** The `joybrush/` folder: walk up from wherever the test task was started, as WriteGrainAssets does. */
    private fun joybrushRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (dir.name == "joybrush" && File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "no joybrush/ folder with settings.gradle.kts above ${File("").absolutePath}",
        )
    }
}
