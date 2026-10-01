package cc.joycreator.joybrush.core.paper

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `assets/paper/catalogue.json` — the ONE list of the papers that ship (JB-9.04).
 *
 * **Every paper is listed once, here.** The Paper sheet (JB-9.07), the engine (JB-9.06) and export all
 * read this one list, so a paper that is not in it does not exist, and a paper listed twice is the
 * second one unreachable in the sheet.
 *
 * **A paper has two halves, and they are separate entries.** A **look** is what you see (a colour
 * picture, re-tintable) and a **surface** is what the brushes feel (JB-9.01's height map). A look
 * names a default surface — owner P6, a chalkboard comes with its chalk — which is why
 * `defaultSurface` has to name a surface that is really in the list, and why the two lists are
 * validated together.
 *
 * **Every test makes exactly one bad field and asserts exactly one problem.** That shape is the row's
 * whole guard: a validator that reports three problems for one mistake is a validator whose messages
 * have stopped being readable, and a rule that cannot be triggered in isolation cannot be
 * mutation-tested either. The fixture is therefore BUILT FROM NAMED VALUES ([surface], [look]) rather
 * than written out as one block of text — a test that edited a 30-line literal with `replace` would
 * depend on the literal's exact indentation, and a test that silently stopped matching is a test
 * that passes for the wrong reason.
 *
 * **The JSON is text, not an object.** [PaperCatalogues] reads and validates and has no `encode`, so
 * a test that built a `PaperCatalogue` in Kotlin could only reach half the door. A typo in the file is
 * the failure this parser exists to catch, so the file is the thing under test.
 *
 * **The wording of a problem is not pinned**, only that it names the id and the field — a sentence is
 * the cheapest thing in this project to change and the most expensive to change twice.
 */
class PaperCatalogueTest {

    // ---- the fixture: one surface, one look, every field stated outright --------------------------

    /**
     * One surface, as JSON. Numbers are [String]s rather than floats so a test can write a value JSON
     * has a word for but Kotlin does not reach this code as — `"1e999"`, which decodes to +Infinity —
     * without the compiler turning it into a literal first.
     */
    private fun surface(
        id: String = "pulp_artisan",
        name: String = "Artisan pulp",
        file: String = "surface_pulp_artisan.png",
        size: Int = 512,
        texelPx: String = "2.0",
        slopeRange: String = "0.099",
        hexTexels: String = "180",
        rotatable: Boolean = true,
        relief: String = "1.0",
    ): String = """
        {
          "id": "$id",
          "name": "$name",
          "file": "$file",
          "size": $size,
          "texelPx": $texelPx,
          "slopeRange": $slopeRange,
          "hexTexels": $hexTexels,
          "rotatable": $rotatable,
          "relief": $relief
        }
    """.trimIndent()

    /** One look. `file` and `mean` are nullable because a flat look has neither. */
    private fun look(
        id: String = "off_white",
        name: String = "Off-white",
        base: String = "#F3EFE6",
        file: String? = null,
        mean: String? = null,
        texelPx: String = "2.0",
        hexTexels: String = "180",
        rotatable: Boolean = true,
        defaultSurface: String? = "pulp_artisan",
        lightByDefault: Boolean = true,
    ): String = """
        {
          "id": "$id",
          "name": "$name",
          "base": "$base",
          "file": ${file?.let { "\"$it\"" } ?: "null"},
          "mean": ${mean?.let { "\"$it\"" } ?: "null"},
          "texelPx": $texelPx,
          "hexTexels": $hexTexels,
          "rotatable": $rotatable,
          "defaultSurface": ${defaultSurface?.let { "\"$it\"" } ?: "null"},
          "lightByDefault": $lightByDefault
        }
    """.trimIndent()

    /**
     * The two entries wrapped in a file. `extraRoot` injects a key at the top level so the
     * unknown-key test needs no string surgery, and `format` is a raw JSON word for the same reason.
     */
    private fun catalogue(
        surfaces: List<String> = listOf(surface()),
        looks: List<String> = listOf(look()),
        format: String = "\"$PAPER_CATALOGUE_FORMAT\"",
        version: String = "1",
        extraRoot: String = "",
    ): String = """
        {
          "format": $format,
          "version": $version,
          $extraRoot
          "surfaces": [${surfaces.joinToString(",")}],
          "looks": [${looks.joinToString(",")}]
        }
    """.trimIndent()

    /** The control. Without it, the rule tests below could all be passing because the fixture is broken. */
    @Test
    fun aGoodTwoEntryCatalogueParsesWithZeroProblems() {
        val c = PaperCatalogues.parse(catalogue())
        assertEquals(1, c.surfaces.size, "the fixture must hold one surface")
        assertEquals(1, c.looks.size, "the fixture must hold one look")
        assertEquals(PAPER_CATALOGUE_FORMAT, c.format)
        assertEquals(PAPER_CATALOGUE_VERSION, c.version)
        assertEquals(emptyList(), PaperCatalogues.problems(c), "a good catalogue has no problems")
    }

    /**
     * **The shape every rule test below uses.** Parse, then insist the list of problems is EXACTLY one
     * entry naming the entry's id and the field. `one problem per mistake` is asserted rather than
     * assumed: a rule added to [PaperCatalogues.problems] that also fires on a good catalogue makes
     * the count two and the control test go red, which is the point.
     */
    private fun assertOneProblem(json: String, id: String, field: String) {
        val problems = PaperCatalogues.problems(PaperCatalogues.parse(json))
        assertEquals(1, problems.size, "one bad field must give one problem, got: $problems")
        val said = problems.single()
        assertTrue(said.contains(id), "the problem must name the entry id \"$id\", got: $said")
        assertTrue(said.contains(field), "the problem must name the field \"$field\", got: $said")
    }

    // ---- the file itself -------------------------------------------------------------------------

    /**
     * **An unknown key is a parse error, not a shrug.** Strict, like the brush codec (JB-0.02d): the
     * catalogue is an asset in the app, so a key this build does not know is a build and a file that
     * disagree, and reading it anyway means the sheet offers a paper whose settings are not the ones
     * on disk.
     *
     * **The throwable is the library's, and that is deliberate.** The spec's contract gives
     * [PaperCatalogues] no exception type of its own, so this row invents none — a
     * `PaperException` would be a shape the contract does not have, and a caller that needs a sentence
     * rather than a library message will wrap it. JB-9.06 is the row that has to.
     */
    @Test
    fun anUnknownKeyIsAParseError() {
        assertFailsWith<SerializationException> {
            PaperCatalogues.parse(catalogue(extraRoot = "\"fromTheFuture\": 1,"))
        }
    }

    /** The same at the entry level, where a future row would actually add a field. */
    @Test
    fun anUnknownKeyInsideAnEntryIsAParseError() {
        val withIt = surface().replaceFirst("{", "{\n  \"fromTheFuture\": 1,")
        assertFailsWith<SerializationException> { PaperCatalogues.parse(catalogue(surfaces = listOf(withIt))) }
    }

    /**
     * A newer catalogue is refused IN WORDS rather than by a library error, so a person is sent looking
     * for a build of Joy Brush that can read their papers instead of looking for a bug. The file still
     * PARSES, because refusing at the door would replace this sentence with "Encountered an unknown
     * key" — the same trade JB-0.02d ruled on, and the same answer here.
     */
    @Test
    fun aNewerVersionIsRefusedWithTheWordsAbove() {
        val c = PaperCatalogues.parse(catalogue(version = "${PAPER_CATALOGUE_VERSION + 1}"))
        val problems = PaperCatalogues.problems(c)
        assertTrue(
            problems.any { it.contains("made by a newer Joy Brush") },
            "a catalogue from a newer Joy Brush must be refused in words, got: $problems",
        )
    }

    // ---- surfaces --------------------------------------------------------------------------------

    /**
     * A surface id has a second job: the fixture's look names it as its `defaultSurface`, so breaking
     * the id would break the look's reference too and this test would report two problems for one
     * mistake. The look therefore goes smooth here — which is also the honest statement that a
     * malformed surface id is a problem on its own, not only through a reference to it.
     */
    @Test
    fun aSurfaceIdWithTheWrongShapeIsRefused() {
        val json = catalogue(
            surfaces = listOf(surface(id = "Pulp-Artisan")),
            looks = listOf(look(defaultSurface = null)),
        )
        assertOneProblem(json, "Pulp-Artisan", "id")
    }

    @Test
    fun aSurfaceIdLongerThanFortyIsRefused() {
        val long = "a".repeat(41)
        val json = catalogue(
            surfaces = listOf(surface(id = long)),
            looks = listOf(look(defaultSurface = null)),
        )
        assertOneProblem(json, long, "id")
    }

    /** Two surfaces claiming one id makes the second unreachable in the sheet. */
    @Test
    fun twoSurfacesSharingAnIdAreRefused() {
        val twice = catalogue(surfaces = listOf(surface(), surface(name = "Duplicate")))
        assertOneProblem(twice, "pulp_artisan", "id")
    }

    @Test
    fun aSurfaceNameOutsideOneToFortyIsRefused() {
        assertOneProblem(catalogue(surfaces = listOf(surface(name = ""))), "pulp_artisan", "name")
    }

    /**
     * A `file` is a plain name inside `assets/paper/`. A path in it is either a typo or a reach outside
     * the folder, and either way it is not a name the asset loader can find — so it is refused here
     * rather than becoming a `FileNotFoundException` on a phone.
     */
    @Test
    fun aSurfaceFileWithAPathSeparatorIsRefused() {
        assertOneProblem(catalogue(surfaces = listOf(surface(file = "../../secrets/surface.png"))), "pulp_artisan", "file")
    }

    @Test
    fun aSurfaceFileThatIsNotAPngIsRefused() {
        assertOneProblem(catalogue(surfaces = listOf(surface(file = "surface_pulp_artisan.jpg"))), "pulp_artisan", "file")
    }

    @Test
    fun aSurfaceSizeThatIsNotAPowerOfTwoIsRefused() {
        assertOneProblem(catalogue(surfaces = listOf(surface(size = 500))), "pulp_artisan", "size")
    }

    @Test
    fun aSurfaceSizeAboveTwoThousandAndFortyEightIsRefused() {
        assertOneProblem(catalogue(surfaces = listOf(surface(size = 4096))), "pulp_artisan", "size")
    }

    /**
     * A `size` under 64 is one bad field, and it is the only field in the whole contract whose legal
     * range is COUPLED to another: `hexTexels` is bounded by `size`, so dropping the size to 32 also
     * strands the fixture's `hexTexels` of 180. The fixture therefore moves `hexTexels` down with it —
     * to a value that is legal for the new size, so `size` stays the only field actually wrong. This is
     * worth stating because it is the one place a reader would expect two problems and get one.
     */
    @Test
    fun aSurfaceSizeBelowSixtyFourIsRefused() {
        assertOneProblem(catalogue(surfaces = listOf(surface(size = 32, hexTexels = "16"))), "pulp_artisan", "size")
    }

    @Test
    fun aSurfaceTexelPxOutsideItsRangeIsRefused() {
        assertOneProblem(catalogue(surfaces = listOf(surface(texelPx = "20.0"))), "pulp_artisan", "texelPx")
    }

    /**
     * **A non-finite number is refused, and it is refused at the PARSE as well as by the rule.**
     *
     * JSON has no word for Infinity, so `"texelPx": 1e999` cannot arrive from a file: the library
     * refuses the token before a [SurfaceEntry] exists. That is the better of the two answers and it
     * makes this row's problem list untestable for NaN from text — so the rule is checked the only way
     * a non-finite value can actually reach it, which is **built in memory** (a future row assembling a
     * catalogue, or a test doing exactly this). `BrushValidate` has the same split and the same reason.
     */
    @Test
    fun aNonFiniteSurfaceNumberIsRefused() {
        val entry = SurfaceEntry(
            id = "pulp_artisan", name = "Artisan pulp", file = "surface_pulp_artisan.png",
            size = 512, texelPx = Float.POSITIVE_INFINITY, slopeRange = 0.099f,
            hexTexels = 180f, rotatable = true,
        )
        val inMemory = PaperCatalogue(surfaces = listOf(entry), looks = emptyList())
        val problems = PaperCatalogues.problems(inMemory)
        assertEquals(1, problems.size, "one bad field must give one problem, got: $problems")
        assertTrue(problems.single().contains("texelPx"), "must name the field, got: ${problems.single()}")

        // And the file route, which is refused a step earlier, with the library's own words.
        assertFailsWith<SerializationException> {
            PaperCatalogues.parse(catalogue(surfaces = listOf(surface(texelPx = "1e999"))))
        }
    }

    @Test
    fun aSurfaceSlopeRangeOutsideItsRangeIsRefused() {
        assertOneProblem(catalogue(surfaces = listOf(surface(slopeRange = "9.0"))), "pulp_artisan", "slopeRange")
    }

    @Test
    fun aSurfaceHexTexelsUnderSixteenIsRefused() {
        assertOneProblem(catalogue(surfaces = listOf(surface(hexTexels = "8"))), "pulp_artisan", "hexTexels")
    }

    /** The hex cell must fit inside the picture it tiles, so the ceiling is the surface's own `size`. */
    @Test
    fun aSurfaceHexTexelsWiderThanItsOwnPictureIsRefused() {
        assertOneProblem(catalogue(surfaces = listOf(surface(hexTexels = "600"))), "pulp_artisan", "hexTexels")
    }

    @Test
    fun aSurfaceReliefOutsideZeroToFourIsRefused() {
        assertOneProblem(catalogue(surfaces = listOf(surface(relief = "9.0"))), "pulp_artisan", "relief")
    }

    // ---- looks -----------------------------------------------------------------------------------

    @Test
    fun aLookIdWithTheWrongShapeIsRefused() {
        assertOneProblem(catalogue(looks = listOf(look(id = "Off White"))), "Off White", "id")
    }

    @Test
    fun twoLooksSharingAnIdAreRefused() {
        assertOneProblem(catalogue(looks = listOf(look(), look(name = "Duplicate"))), "off_white", "id")
    }

    /**
     * `base` is the flat colour AND the colour a tint is measured against, so a value that is not a
     * colour cannot be a flat colour and cannot be a reference: a tint divides by it.
     */
    @Test
    fun aLookBaseThatIsNotAColourIsRefused() {
        assertOneProblem(catalogue(looks = listOf(look(base = "0xF3EFE6"))), "off_white", "base")
    }

    @Test
    fun aLookFileWithAPathSeparatorIsRefused() {
        assertOneProblem(catalogue(looks = listOf(look(file = "looks/../rice.png", mean = "#FFFFFF"))), "off_white", "file")
    }

    /**
     * A pictured look MUST carry its mean: tinting divides by it, so a picture with no mean is a tint
     * that divides by nothing. A flat look (`file` is null) needs no mean, and the good fixture is
     * exactly that case — so this test cannot pass by accident.
     */
    @Test
    fun aPicturedLookWithNoMeanIsRefused() {
        assertOneProblem(catalogue(looks = listOf(look(file = "look_rice.png"))), "off_white", "mean")
    }

    @Test
    fun aLookMeanThatIsNotAColourIsRefused() {
        assertOneProblem(catalogue(looks = listOf(look(file = "look_rice.png", mean = "white"))), "off_white", "mean")
    }

    /** A mean on a look with no picture is a number nothing reads, and the file cannot be written back with it. */
    @Test
    fun aMeanOnAFlatLookIsRefused() {
        assertOneProblem(catalogue(looks = listOf(look(mean = "#F3EFE6"))), "off_white", "mean")
    }

    /**
     * Owner P6: a look may name the surface it comes with. Naming one that is not in this catalogue
     * would give the sheet a paper whose default surface cannot be loaded — a chalkboard with no
     * chalk, which reads as "the paper did not apply" rather than as an error.
     */
    @Test
    fun aDefaultSurfaceThatIsNotInTheCatalogueIsRefused() {
        assertOneProblem(catalogue(looks = listOf(look(defaultSurface = "chalk_green"))), "off_white", "defaultSurface")
    }

    /**
     * `null` is the other half of that rule and must NOT be a problem: null means smooth, and smooth is
     * a paper like any other. Asserted because a rule written as "defaultSurface must name a surface"
     * without the null case is the obvious wrong turn, and it would refuse every flat-colour look.
     */
    @Test
    fun aLookWithNoDefaultSurfaceIsSmoothAndValid() {
        val c = PaperCatalogues.parse(catalogue(looks = listOf(look(defaultSurface = null))))
        assertEquals(emptyList(), PaperCatalogues.problems(c), "no default surface means smooth, got: ${PaperCatalogues.problems(c)}")
    }

    // ---- the file's own two fields, and the lookups -----------------------------------------------

    @Test
    fun aWrongFormatTagIsRefused() {
        val c = PaperCatalogues.parse(catalogue(format = "\"joybrush.paper\""))
        assertTrue(
            PaperCatalogues.problems(c).any { it.contains("format") },
            "a file that is not a paper catalogue must be refused, got: ${PaperCatalogues.problems(c)}",
        )
    }

    @Test
    fun aVersionBelowOneIsRefused() {
        val c = PaperCatalogues.parse(catalogue(version = "0"))
        assertTrue(
            PaperCatalogues.problems(c).any { it.contains("version") },
            "version 0 is not a version, got: ${PaperCatalogues.problems(c)}",
        )
    }

    /** A paper is found by the id a document carries (JB-9.05), and a miss is null rather than a throw. */
    @Test
    fun entriesAreFoundByIdAndAMissIsNull() {
        val c = PaperCatalogues.parse(catalogue())
        assertEquals("pulp_artisan", PaperCatalogues.surface(c, "pulp_artisan")?.id)
        assertEquals("off_white", PaperCatalogues.look(c, "off_white")?.id)
        assertNull(PaperCatalogues.surface(c, "chalk_green"), "an unknown surface id is null, not a throw")
        assertNull(PaperCatalogues.look(c, "rice"), "an unknown look id is null, not a throw")
    }

    /**
     * The defaults the contract gives [LookEntry], asserted on a look that states only its three
     * required fields. **These are the numbers the Paper sheet will show**, so they are read out of the
     * decoded entry rather than retyped into the expectation: a spec that says "the default is 2" and a
     * file that says `"texelPx": 2.0` are the same fact, and only one of them is code.
     *
     * `lightByDefault` is here because **AMOLED black is the one look that will ship it false** — a
     * black paper that is lit by default is a black paper that is not black.
     */
    @Test
    fun aLookWithOnlyItsRequiredFieldsTakesTheContractDefaults() {
        val bare = catalogue(surfaces = emptyList(), looks = listOf(look(defaultSurface = null, lightByDefault = true)))
        val c = PaperCatalogues.parse(bare)
        assertEquals(emptyList(), PaperCatalogues.problems(c), "a bare look is valid, got: ${PaperCatalogues.problems(c)}")
        val l = PaperCatalogues.look(c, "off_white")!!
        assertNull(l.file, "no file means a flat base colour")
        assertNull(l.mean, "a flat look has no picture to average")
        assertNull(l.defaultSurface, "no default surface means smooth")
        assertEquals(2f, l.texelPx)
        assertEquals(180f, l.hexTexels)
        assertTrue(l.rotatable)
        assertTrue(l.lightByDefault, "AMOLED black is the one look that ships this false")
        assertNull(PaperCatalogues.surface(c, "nothing"), "this catalogue lists no surfaces, so every id misses")
    }

    /** A surface's `relief` defaults to 1, which is "lit at ordinary strength" rather than off. */
    @Test
    fun aSurfaceWithoutAReliefReliefDefaultsToOne() {
        val noRelief = surface().replace(Regex(",\\s*\"relief\": [0-9.]+"), "")
        val c = PaperCatalogues.parse(catalogue(surfaces = listOf(noRelief)))
        assertEquals(emptyList(), PaperCatalogues.problems(c), "relief is optional, got: ${PaperCatalogues.problems(c)}")
        assertEquals(1f, PaperCatalogues.surface(c, "pulp_artisan")?.relief)
    }
}
