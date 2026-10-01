package cc.joycreator.joybrush.core.paper

import cc.joycreator.joybrush.core.brush.joybrushRoot
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The paper catalogue **as it ships on disk**, read from `joybrush/assets/paper/catalogue.json`.
 *
 * `PaperCatalogueTest` (commonTest) carries a catalogue written out inline, because commonTest cannot
 * open a file and must stay platform-neutral — which means a catalogue edited on disk drifts from its
 * copy silently. This is the test that notices. It is also the canary for a new rule in
 * [PaperCatalogues.problems]: a rule that refuses a paper the app has been shipping fails here first.
 *
 * **It checks the pictures too, because a catalogue entry is a promise about a file.** A `file` that
 * does not exist, is not a PNG, or is not the shape the entry claims is a paper the Paper sheet
 * (JB-9.07) will offer and the engine (JB-9.06) will then fail to load — and the failure lands on a
 * phone, in someone else's hands, with no way to name the paper. A **surface** declares its own
 * `size`, so its picture must be exactly that many texels across; a **look** declares no size, so its
 * picture must merely be square and a power of two, which is what the hex tiling needs.
 *
 * **[joybrushRoot] is the one root-finder for this source set** (it lives in the brush tests), and the
 * `assets` folder is already declared as a `jvmTest` input in `core/build.gradle.kts`, so editing the
 * catalogue or a picture re-runs this rather than reporting UP-TO-DATE.
 */
class ShippedCatalogueTest {

    private val paperDir: File = File(joybrushRoot(), "assets/paper")

    private val file = File(paperDir, "catalogue.json")

    private fun shipped(): PaperCatalogue = PaperCatalogues.parse(file.readText())

    @Test
    fun theShippedCatalogueLoadsClean() {
        assertTrue(file.isFile, "no paper catalogue at ${file.absolutePath}")
        val c = shipped()
        assertEquals(emptyList(), PaperCatalogues.problems(c), "${file.absolutePath} must load clean, but: ${PaperCatalogues.problems(c)}")
        assertTrue(
            c.surfaces.isNotEmpty() && c.looks.isNotEmpty(),
            "a catalogue with no surfaces or no looks is an empty shelf: ${c.surfaces.size} surfaces, ${c.looks.size} looks",
        )
    }

    /**
     * Every picture a shipped entry names, loaded and measured. **One failure names the paper**, not
     * just the file, because a person fixing this is looking for a paper in a list and not for a
     * `.png` in a folder.
     */
    @Test
    fun everySurfacePictureExistsAndIsTheSizeTheEntryClaims() {
        for (s in shipped().surfaces) {
            val png = File(paperDir, s.file)
            assertTrue(png.isFile, "surface \"${s.id}\" names ${s.file}, which is not in ${paperDir.absolutePath}")
            val img = assertNotNull(ImageIO.read(png), "surface \"${s.id}\": ${s.file} does not decode as an image")
            assertEquals(s.size, img.width, "surface \"${s.id}\": ${s.file} is ${img.width} wide, not the ${s.size} the entry claims")
            assertEquals(s.size, img.height, "surface \"${s.id}\": ${s.file} is ${img.height} tall, not the ${s.size} the entry claims")
        }
    }

    /**
     * A look's picture carries no declared size, so the rule is the one the sampler needs: **square and
     * a power of two**, 64..2048, the same bounds a surface's `size` is held to. A look with no `file`
     * is a flat `base` colour and has no picture to check, which is why this walks nulls.
     */
    @Test
    fun everyLookPictureIsSquareAndAPowerOfTwo() {
        for (l in shipped().looks) {
            val name = l.file ?: continue
            val png = File(paperDir, name)
            assertTrue(png.isFile, "look \"${l.id}\" names $name, which is not in ${paperDir.absolutePath}")
            val img = assertNotNull(ImageIO.read(png), "look \"${l.id}\": $name does not decode as an image")
            assertEquals(img.width, img.height, "look \"${l.id}\": $name is ${img.width}x${img.height}, and a tiled look must be square")
            assertTrue(
                img.width in 64..2048 && (img.width and (img.width - 1)) == 0,
                "look \"${l.id}\": $name is ${img.width} across; a look picture must be a power of two in 64..2048",
            )
        }
    }

    /**
     * **A mean on a flat look is dead weight, and a missing mean on a pictured one is already a
     * problem** — [PaperCatalogues.problems] refuses the second. What this adds is the reason to
     * refuse it said out loud on the shipped file: a picture with no `mean` is a tint that divides by
     * nothing. The assertion is that every shipped pictured look really does carry one, which is what
     * makes the shipped catalogue the example the good fixture in `PaperCatalogueTest` copies.
     */
    @Test
    fun everyPicturedLookCarriesTheMeanItsTintDividesBy() {
        for (l in shipped().looks) {
            if (l.file == null) {
                assertNull(l.mean, "look \"${l.id}\" has no picture, so a mean on it is a number nobody reads")
            } else {
                assertNotNull(l.mean, "look \"${l.id}\" is a picture and must carry the mean colour a tint divides by")
            }
        }
    }
}
