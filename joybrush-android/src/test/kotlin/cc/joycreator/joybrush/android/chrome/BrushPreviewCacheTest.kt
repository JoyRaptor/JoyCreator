package cc.joycreator.joybrush.android.chrome

import cc.joycreator.joybrush.androidkit.preview.BrushPreviewStyle
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/**
 * The drawer's cache of engine pictures, with pictures made of text so no GPU or bitmap is needed: what is answered from
 * memory, from the folder, or made; what is shared between two askers; what a call-off does; what the folder keeps.
 */
class BrushPreviewCacheTest {

    /** Runs nothing until told: the test decides when the UI thread and the IO thread get their turn. */
    private class Manual : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
        fun runOne() { queue.removeFirst().run() }
    }

    private class TextCodec : PreviewCodec<String> {
        var failWrites = false
        val reads = ArrayList<String>()
        override fun write(value: String, file: File): Boolean { if (failWrites) return false; file.writeText(value); return true }
        override fun read(file: File, width: Int, height: Int): String? { reads += file.name; return file.readText().takeIf { it.isNotEmpty() } }
        override fun bytes(value: String): Long = value.length.toLong()
    }

    /** A maker that waits: [finish] answers the oldest call. */
    private class Maker : PreviewMaker<String> {
        class Call(val preset: BrushPreset, val w: Int, val h: Int, val done: (String?) -> Unit) { var cancelled = false }
        val calls = ArrayList<Call>()
        override fun make(preset: BrushPreset, width: Int, height: Int, style: BrushPreviewStyle, done: (String?) -> Unit): PreviewCancel {
            val call = Call(preset, width, height, done)
            calls += call
            return PreviewCancel { call.cancelled = true }
        }
        fun finish(text: String?, index: Int = 0) = calls[index].done(text)
    }

    private lateinit var dir: File
    private val ui = Manual()
    private val io = Manual()
    private val maker = Maker()
    private val codec = TextCodec()
    /** A clock that moves two seconds per look, so file times differ even on a folder that keeps whole seconds. */
    private var now = 1_000_000L
    private val style = BrushPreviewStyle()

    @Before fun makeDir() {
        dir = java.nio.file.Files.createTempDirectory("brush_previews_test").toFile()
        File(dir, "x").mkdirs()
        File(dir, "x").delete()
    }

    @After fun removeDir() { dir.deleteRecursively() }

    private fun cache(limits: PreviewDiskTrim.Limits = PreviewDiskTrim.Limits(), memory: Long = 1_000L) =
        BrushPreviewCache(dir, maker, codec, ui, io, memory, limits) { now += 2_000L; now }

    private fun brush(id: String = "pen", size: Float = 12f) = BrushPreset(id = id, name = id, size = Param(size))
    private fun settle() { repeat(10) { io.runAll(); ui.runAll() } }
    private fun stored() = dir.listFiles()?.map { it.name }?.sorted().orEmpty()

    @Test fun aMissIsMadeOnceThenKeptInMemoryAndOnDisk() {
        val c = cache()
        val got = ArrayList<String>()
        c.request(brush(), 900, 90, style) { got += it }
        settle()
        assertEquals(1, maker.calls.size)
        assertTrue(got.isEmpty())
        maker.finish("PICTURE")
        assertEquals(listOf("PICTURE"), got)
        settle()
        assertEquals(1, stored().size)
        assertEquals("PICTURE", File(dir, stored().single()).readText())
        // Asked again: answered at once, from memory, no second making.
        c.request(brush(), 900, 90, style) { got += it }
        assertEquals(listOf("PICTURE", "PICTURE"), got)
        assertEquals(1, maker.calls.size)
    }

    @Test fun aStoredPictureIsFoundWithoutMakingItAgain() {
        val first = cache()
        first.request(brush(), 900, 90, style) { }
        settle(); maker.finish("PICTURE"); settle()
        // A fresh cache (a new run of the app) has nothing in memory but finds the folder.
        val second = cache()
        val got = ArrayList<String>()
        second.request(brush(), 900, 90, style) { got += it }
        assertTrue(got.isEmpty())
        settle()
        assertEquals(listOf("PICTURE"), got)
        assertEquals(1, maker.calls.size)
        assertEquals(1, second.inMemory)
    }

    @Test fun twoAskersForOnePictureShareOneMaking() {
        val c = cache()
        val a = ArrayList<String>(); val b = ArrayList<String>()
        c.request(brush(), 900, 90, style) { a += it }
        c.request(brush(), 900, 90, style) { b += it }
        settle()
        assertEquals(1, maker.calls.size)
        maker.finish("P")
        assertEquals(listOf("P"), a)
        assertEquals(listOf("P"), b)
        assertEquals(0, c.inFlight)
    }

    @Test fun callingOffOneOfTwoAskersLeavesTheOtherItsPicture() {
        val c = cache()
        val a = ArrayList<String>(); val b = ArrayList<String>()
        val offA = c.request(brush(), 900, 90, style) { a += it }
        c.request(brush(), 900, 90, style) { b += it }
        settle()
        offA.cancel()
        assertFalse(maker.calls[0].cancelled)
        maker.finish("P")
        assertTrue(a.isEmpty())
        assertEquals(listOf("P"), b)
    }

    @Test fun callingOffTheLastAskerCallsOffTheMakingAndTheNextAskStartsAfresh() {
        val c = cache()
        val off = c.request(brush(), 900, 90, style) { }
        settle()
        off.cancel()
        off.cancel()
        assertTrue(maker.calls[0].cancelled)
        assertEquals(0, c.inFlight)
        val got = ArrayList<String>()
        c.request(brush(), 900, 90, style) { got += it }
        settle()
        assertEquals(2, maker.calls.size)
        maker.finish("P", index = 1)
        assertEquals(listOf("P"), got)
    }

    @Test fun aPictureFinishedAfterEveryoneLeftIsStillKeptForNextTime() {
        val c = cache()
        c.request(brush(), 900, 90, style) { }.also { settle(); it.cancel() }
        maker.finish("LATE")
        settle()
        val got = ArrayList<String>()
        c.request(brush(), 900, 90, style) { got += it }
        assertEquals(listOf("LATE"), got)
        assertEquals(1, maker.calls.size)
        assertEquals(1, stored().size)
    }

    @Test fun callingOffWhileTheFolderIsBeingReadMakesNothing() {
        val c = cache()
        val off = c.request(brush(), 900, 90, style) { }
        off.cancel()
        settle()
        assertTrue(maker.calls.isEmpty())
        assertEquals(0, c.inFlight)
    }

    @Test fun aMakerThatCouldNotMakeOneLeavesNothingBehindAndTheNextAskTriesAgain() {
        val c = cache()
        val got = ArrayList<String>()
        c.request(brush(), 900, 90, style) { got += it }
        settle()
        maker.finish(null)
        settle()
        assertTrue(got.isEmpty())
        assertEquals(0, c.inFlight)
        assertTrue(stored().isEmpty())
        c.request(brush(), 900, 90, style) { got += it }
        settle()
        assertEquals(2, maker.calls.size)
    }

    @Test fun aDamagedFileIsMadeAgainNotShown() {
        val key = BrushPreviewKey.of(brush(), 900, 90, style)
        File(dir, key.fileName).writeText("")   // the codec reads an empty file as damaged
        val c = cache()
        c.request(brush(), 900, 90, style) { }
        settle()
        assertEquals(1, maker.calls.size)
    }

    @Test fun aTunedBrushIsAnotherPictureAndItsOldOneIsDeleted() {
        val c = cache()
        c.request(brush(size = 12f), 900, 90, style) { }
        settle(); maker.finish("OLD"); settle()
        val oldName = stored().single()
        c.request(brush(size = 20f), 900, 90, style) { }
        settle(); maker.finish("NEW", index = 1); settle()
        assertEquals(2, maker.calls.size)
        assertEquals(1, stored().size)
        assertFalse(stored().contains(oldName))
        assertEquals("NEW", File(dir, stored().single()).readText())
    }

    @Test fun theFolderIsHeldToItsFileLimitOldestFirst() {
        val c = cache(PreviewDiskTrim.Limits(maxFiles = 2, maxBytes = Long.MAX_VALUE))
        for ((i, id) in listOf("a", "b", "c").withIndex()) {
            c.request(brush(id), 900, 90, style) { }
            settle(); maker.finish("P$id", index = i); settle()
        }
        assertEquals(2, stored().size)
        assertTrue("the oldest went: ${stored()}", stored().none { it.startsWith("a_") })
    }

    @Test fun aReadFromTheFolderMakesThatPictureTheNewestThere() {
        val c = cache(PreviewDiskTrim.Limits(maxFiles = 2, maxBytes = Long.MAX_VALUE))
        for ((i, id) in listOf("a", "b").withIndex()) {
            c.request(brush(id), 900, 90, style) { }
            settle(); maker.finish("P$id", index = i); settle()
        }
        c.trimMemory()
        c.request(brush("a"), 900, 90, style) { }   // read "a" back: now "b" is the older
        settle()
        c.request(brush("c"), 900, 90, style) { }
        settle(); maker.finish("Pc", index = 2); settle()
        assertTrue(stored().any { it.startsWith("a_") })
        assertTrue(stored().none { it.startsWith("b_") })
    }

    @Test fun aFolderThatCannotBeWrittenCostsOnlyTheNextOpenAFreshMaking() {
        codec.failWrites = true
        val c = cache()
        val got = ArrayList<String>()
        c.request(brush(), 900, 90, style) { got += it }
        settle(); maker.finish("P"); settle()
        assertEquals(listOf("P"), got)
        assertTrue(stored().none { it.endsWith(".png") })
        assertTrue("no half-written file is left behind: ${stored()}", stored().isEmpty())
    }

    @Test fun differentSizesOfOneBrushAreSeparatePictures() {
        val c = cache()
        c.request(brush(), 900, 90, style) { }
        c.request(brush(), 600, 90, style) { }
        settle()
        assertEquals(2, maker.calls.size)
    }

    @Test fun trimmingMemoryMakesTheNextAskReadTheFolder() {
        val c = cache()
        c.request(brush(), 900, 90, style) { }
        settle(); maker.finish("P"); settle()
        c.trimMemory()
        assertEquals(0, c.inMemory)
        val got = ArrayList<String>()
        c.request(brush(), 900, 90, style) { got += it }
        assertTrue(got.isEmpty())
        settle()
        assertEquals(listOf("P"), got)
        assertEquals(1, maker.calls.size)
    }
}
