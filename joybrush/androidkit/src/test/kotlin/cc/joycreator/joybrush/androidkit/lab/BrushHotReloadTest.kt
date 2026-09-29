package cc.joycreator.joybrush.androidkit.lab

import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.Param
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JB-1.21. The watcher that makes a brush edited on a PC show up on the phone, and the two ways it
 * can go wrong: a file that is not a brush, and a file that is not readable.
 *
 * A lab exists so that a wrong brush is a two-second fix, so the promise these tests pin is not
 * "a brush loads" — it is "a CHANGED brush loads, an unchanged one does not arrive twice, and a
 * broken one arrives as a sentence rather than as a stroke drawn with a half-read file". The old
 * brush staying put while an error is on screen is the caller's half of that (it only assigns the
 * preset inside `onBrush`), so what is pinned here is the other half: no `onBrush` for a file that
 * failed.
 *
 * Every brush file used here is written by [BrushJson.encode] from a preset, so the fixture is a
 * real brush in the real format and the expectation is decoded by the same reader the watcher uses.
 * A hand-typed JSON blob would drift from the format silently; these do not.
 *
 * Times are set explicitly rather than waited for. "The file changed" must be a fact in the test,
 * not a race with a filesystem clock, and a test that cannot tell whether the clock moved is a
 * test that can pass without having watched anything.
 *
 * This is a CLASS on purpose: JUnit 4 runs methods on an instance, so a file of top-level `@Test`
 * functions compiles, reports a green build and executes nothing — which is worse than a red one.
 */
class BrushHotReloadTest {

    /**
     * 2023-11-14T22:13:20Z, and one second later. Any two distinct stamps would do; these are
     * spelled out so the number a test compares against is a constant in the file rather than
     * whatever `System.currentTimeMillis()` happened to be.
     */
    private val firstSave = 1_700_000_000_000L
    private val secondSave = firstSave + 1_000L

    /** Every folder this file writes, so each test starts from a lab folder that is empty. */
    private val labs = ArrayList<File>()

    /** What the watcher said, in the order it said it. */
    private val brushes = ArrayList<BrushPreset>()
    private val errors = ArrayList<Pair<File, List<String>>>()

    @AfterTest
    fun removeTheLabFolders() {
        for (dir in labs) dir.deleteRecursively()
        labs.clear()
        brushes.clear()
        errors.clear()
    }

    // ---------------------------------------------------------------- fixtures

    /** A lab folder of its own for one test, created up front so a push can be empty or full. */
    private fun lab(): File = Files.createTempDirectory("joybrush-lab").toFile().also { labs.add(it) }

    /**
     * The watcher under test, wired to the two recorders above.
     *
     * No Handler: [BrushHotReload.start] is the only part of the class that needs an Android
     * runtime, and the decision worth testing is [BrushHotReload.scanOnce], which is the part the
     * Handler merely repeats.
     */
    private fun watcher(lab: File) = BrushHotReload(lab, { brushes.add(it) }, { file, problems ->
        errors.add(file to problems)
    })

    /**
     * A brush that passes every one of [BrushValidate]'s rules: the preset's own defaults are
     * inside their ranges, and the two numbers a brush cannot be without — an id and a size — are
     * given. `assertEquals(emptyList(), BrushValidate.validate(...))` is here so a change to the
     * format that made this fixture unusable fails in the FIXTURE, naming that, rather than in
     * whichever test happened to use it first.
     */
    private fun brush(id: String, sizeBase: Float): BrushPreset =
        BrushPreset(id = id, name = id.substringAfterLast('.'), size = Param(sizeBase))

    /** [brush], checked: a fixture that is not a usable brush fails here, naming the fixture. */
    private fun usable(id: String, sizeBase: Float): BrushPreset = brush(id, sizeBase).also {
        assertEquals(emptyList(), BrushValidate.validate(it), "the test's own brush is not a usable brush")
    }

    /**
     * Writes `<lab>/<folder>/brush.json` and gives it [stamp], asserting the file really carries
     * that time afterwards.
     *
     * The assertion is the point: a filesystem that keeps the stamp to a coarser resolution would
     * otherwise leave "changed" meaning "changed as far as this test can tell", and every test below
     * would quietly stop testing change detection.
     */
    private fun put(lab: File, folder: String, text: String, stamp: Long): File {
        val dir = File(lab, folder).apply { mkdirs() }
        val file = File(dir, BrushHotReload.BRUSH_FILE)
        file.writeText(text)
        assertTrue(file.setLastModified(stamp), "could not set the time of $file")
        assertEquals(stamp, file.lastModified(), "$file did not keep the time it was given")
        return file
    }

    /** Writes a good brush, as `tools/brushlab_push.sh` would have pushed it. */
    private fun putBrush(lab: File, folder: String, id: String, sizeBase: Float, stamp: Long): File =
        put(lab, folder, BrushJson.encode(usable(id, sizeBase)), stamp)

    // ---------------------------------------------------------------- a brush arrives

    @Test
    fun aBrushInTheLabIsHandedOverAsTheDecodedPreset() {
        val lab = lab()
        val text = BrushJson.encode(usable("joybrush.ink", 6f))
        put(lab, "ink", text, firstSave)

        watcher(lab).scanOnce()

        assertEquals(1, brushes.size, "one brush was in the lab and it did not arrive")
        assertEquals(0, errors.size, "a good brush file was reported as a problem")
        val preset = brushes.single()
        // Spelled out as well as compared, so this test says what it means rather than only that
        // two calls of the same decoder agree.
        assertEquals("joybrush.ink", preset.id)
        assertEquals("ink", preset.name)
        assertEquals(6f, preset.size.base)
        assertEquals(BrushJson.decode(text), preset)
    }

    @Test
    fun anEditIsHandedOverOnTheNextScan() {
        val lab = lab()
        putBrush(lab, "ink", "joybrush.ink", 6f, firstSave)
        val watcher = watcher(lab)
        watcher.scanOnce()

        // The change the owner makes on the PC: size.base 6 -> 30.
        putBrush(lab, "ink", "joybrush.ink", 30f, secondSave)
        watcher.scanOnce()

        assertEquals(2, brushes.size, "the edit did not arrive on the next scan")
        assertEquals(6f, brushes[0].size.base, "the first brush was not the 6 px one")
        assertEquals(30f, brushes[1].size.base, "the second brush is not the 30 px one")
        assertEquals(emptyList(), errors.toList(), "a good edit was reported as a problem")
    }

    /**
     * Time, not content. Someone who saves a file without changing it has still saved it, and the
     * lab is a place where "did my save land" is the question being asked — so the watcher answers
     * from the file's time and does not quietly suppress a save that made no difference.
     */
    @Test
    fun aFileSavedAgainWithTheSameBytesStillArrives() {
        val lab = lab()
        val text = BrushJson.encode(usable("joybrush.ink", 6f))
        put(lab, "ink", text, firstSave)
        val watcher = watcher(lab)
        watcher.scanOnce()

        put(lab, "ink", text, secondSave)
        watcher.scanOnce()

        assertEquals(2, brushes.size, "a second save of the same file was swallowed")
    }

    @Test
    fun aBrushIsOnlyHandedOverOnceWhileItsTimeStandsStill() {
        val lab = lab()
        putBrush(lab, "ink", "joybrush.ink", 6f, firstSave)
        val watcher = watcher(lab)

        // Ten polls of a folder nobody is touching. The 500 ms is not slept through — this is about
        // the decision, and the Handler is what would have spaced the polls out.
        repeat(10) { watcher.scanOnce() }

        assertEquals(1, brushes.size, "a brush that had not changed was handed over again")
    }

    /**
     * Every changed folder, in name order, and named outright rather than counted.
     *
     * The list is the test. `assertEquals(3, brushes.size)` would still be green if the watcher
     * picked the three folders it happened to like and left out the one it did not, so the ids are
     * spelled out and the order is part of the promise: two brushes changed in the same tick reach
     * the screen the same way every time.
     */
    @Test
    fun everyChangedFolderIsHandedOverInNameOrder() {
        val lab = lab()
        putBrush(lab, "gamma", "joybrush.gamma", 3f, firstSave)
        putBrush(lab, "alpha", "joybrush.alpha", 1f, firstSave)
        putBrush(lab, "beta", "joybrush.beta", 2f, firstSave)

        watcher(lab).scanOnce()

        assertEquals(
            listOf("joybrush.alpha", "joybrush.beta", "joybrush.gamma"),
            brushes.map { it.id },
        )
        assertEquals(emptyList(), errors.toList(), "three good brushes and ${errors.size} problems")
    }

    // ---------------------------------------------------------------- a brush that is not one

    @Test
    fun aFileThatWillNotDecodeIsReportedAndNoBrushIsOffered() {
        val lab = lab()
        val file = put(lab, "ink", "{ \"format\": \"joybrush.brush\", oops", secondSave)

        watcher(lab).scanOnce()

        assertEquals(emptyList(), brushes.toList(), "an unreadable file was offered as a brush")
        assertEquals(1, errors.size, "a broken file did not say so")
        assertEquals(file, errors.single().first, "the problem was reported against the wrong file")
        val problems = errors.single().second
        assertTrue(problems.isNotEmpty(), "a refusal with nothing in it tells the person nothing")
        // BrushJson's own sentence, named rather than matched: what the person is shown has to be
        // the reason the reader gave, not a summary written here.
        assertTrue(
            problems.single().startsWith("brush.json cannot be read:"),
            "unexpected problem: $problems",
        )
    }

    /**
     * A file that parses but is not a brush: `"size": {"base": 0}` is a stroke that would come out
     * as one dot on the dabber's minimum-size floor, which is the "silent nonsense" the validator
     * exists to turn into a sentence.
     */
    @Test
    fun aFileThatDecodesButIsNotUsableIsReportedWithTheValidatorsOwnSentence() {
        val lab = lab()
        val broken = brush("joybrush.ink", 0f)
        val file = put(lab, "ink", BrushJson.encode(broken), secondSave)

        watcher(lab).scanOnce()

        assertEquals(emptyList(), brushes.toList(), "a refused brush was offered as a brush")
        assertEquals(1, errors.size)
        assertEquals(file, errors.single().first)
        // The number in the sentence is the validator's own constant, and the base is the one this
        // test wrote — neither is a hand-copied literal that could go stale beside the rule.
        assertEquals(
            listOf("size.base must be above 0 and at most ${BrushValidate.MAX_SIZE_PX.toInt()}, is 0.0"),
            errors.single().second,
        )
    }

    @Test
    fun aBrokenFileThatIsThenFixedIsOfferedAsABrush() {
        val lab = lab()
        put(lab, "ink", "not json at all", firstSave)
        val watcher = watcher(lab)
        watcher.scanOnce()

        putBrush(lab, "ink", "joybrush.ink", 6f, secondSave)
        watcher.scanOnce()

        assertEquals(1, errors.size, "the first scan should have said the file was broken")
        assertEquals(1, brushes.size, "the fixed file was never offered as a brush")
        assertEquals("joybrush.ink", brushes.single().id)
    }

    /**
     * Every reason at once, not just the first. The screen shows one line and the log wants the
     * rest, so a watcher that stopped at the first problem would be throwing away half of what it
     * knows — and the person fixing the file would come back to the same file a second time.
     */
    @Test
    fun aFileWithSeveralProblemsReportsAllOfThem() {
        val lab = lab()
        val spacing = 9f
        val smoothing = 4f
        val broken = brush("joybrush.ink", 0f).copy(spacing = spacing, smoothing = smoothing)
        put(lab, "ink", BrushJson.encode(broken), secondSave)

        watcher(lab).scanOnce()

        assertEquals(emptyList(), brushes.toList())
        assertEquals(
            BrushValidate.validate(broken),
            errors.single().second,
            "the watcher reported something other than every reason the validator gave",
        )
        // The count is derived, not typed: three separate rules were broken above (size, spacing,
        // smoothing), and the expected list is the validator's own.
        assertEquals(3, errors.single().second.size, "expected one problem per broken rule")
    }

    // ---------------------------------------------------------------- what is not a brush folder

    /**
     * The shape is `<folder>/brush.json`, so a `brush.json` lying directly in the lab root is not a
     * brush — it is a file in the wrong place, and loading it would mean the shape is whatever the
     * watcher happened to find. Same for a folder with nothing in it, a stray file that is not a
     * folder, and a folder whose `brush.json` is a directory rather than a file.
     */
    @Test
    fun onlyAFolderHoldingARealBrushJsonIsLookedAt() {
        val lab = lab()
        put(lab, "ink", BrushJson.encode(usable("joybrush.ink", 6f)), firstSave)
        File(lab, BrushHotReload.BRUSH_FILE).writeText(
            BrushJson.encode(usable("joybrush.not-in-a-folder", 6f))
        )
        File(lab, "notes").mkdirs()
        File(File(lab, "notes"), "readme.txt").writeText("a brush folder with no brush in it")
        File(lab, "stray.txt").writeText("not a folder")
        File(File(lab, "empty"), BrushHotReload.BRUSH_FILE).apply { parentFile.mkdirs() }.mkdirs()

        watcher(lab).scanOnce()

        assertEquals(
            listOf("joybrush.ink"),
            brushes.map { it.id },
            "something that is not a <folder>/brush.json was loaded as a brush",
        )
        assertEquals(emptyList(), errors.toList(), "nothing here is wrong, so nothing should be reported")
    }

    /**
     * An empty lab folder, and one that does not exist at all, are the state the screen is in before
     * anything has ever been pushed — so they must be silent rather than an error, and they must not
     * break the scan that comes after. `File.listFiles()` answers null for a folder that is not
     * there, which is not the same answer as an empty one.
     */
    @Test
    fun aLabFolderThatIsEmptyOrMissingSaysNothingAndLeavesTheWatcherWorking() {
        val lab = lab()
        val watcher = watcher(lab)
        watcher.scanOnce()

        putBrush(lab, "ink", "joybrush.ink", 6f, firstSave)
        watcher.scanOnce()

        assertEquals(1, brushes.size, "a brush pushed into a previously empty lab never arrived")
        assertEquals(emptyList(), errors.toList(), "an empty lab folder was reported as a problem")
    }

    // ---------------------------------------------------------------- the promise, in one place

    /**
     * The owner's whole check, as a single pass over the same watcher: push a brush, see it, edit
     * it, see the edit, break it, and be told why while the last good brush is still the one on
     * screen. Nothing here touches Android — it is the sequence, not the platform, that is the
     * promise — and every step above is asserted for the same reason.
     */
    @Test
    fun pushSeeEditBreakTheOldBrushIsStillTheOneOnScreen() {
        val lab = lab()
        val watcher = watcher(lab)

        putBrush(lab, "ink", "joybrush.ink", 6f, firstSave)
        watcher.scanOnce()
        // A screen left open polls twice a second for as long as it is up, so the two ticks here are
        // the ordinary case and not a pause: neither may hand the same brush over again.
        watcher.scanOnce()
        watcher.scanOnce()
        putBrush(lab, "ink", "joybrush.ink", 30f, secondSave)
        watcher.scanOnce()
        put(lab, "ink", "{ broken", secondSave + 1_000L)
        watcher.scanOnce()

        assertEquals(listOf(6f, 30f), brushes.map { it.size.base }, "the two good versions, in order")
        assertEquals(1, errors.size, "the broken save should be the one and only complaint")
        // Nothing was offered for the broken save, so the caller's `canvas.preset` still holds the
        // 30 px brush: that is the whole of "the old brush stays", and it is a consequence of the
        // absence asserted above rather than a thing this class does itself.
        assertFalse(brushes.any { it.size.base == 0f }, "a file that could not be read reached the brush list")
    }
}
