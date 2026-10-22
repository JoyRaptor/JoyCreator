package cc.joycreator.joybrush.android.chrome

import cc.joycreator.joybrush.androidkit.preview.*
import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.StabilizerMode
import cc.joycreator.joybrush.core.input.StabilizerSpec
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/** Uses queued IO/UI and text pictures: exercises real cache ownership and resource identity without GL. */
class BrushPreviewResourcesTest {
    private class Manual : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Maker : PreviewMaker<String> {
        class Call(val snapshot: BrushPreviewRequest, val done: (String?) -> Unit) { var cancelled = false }
        val calls = ArrayList<Call>()
        override fun make(preset: BrushPreset, width: Int, height: Int, style: BrushPreviewStyle,
                          done: (String?) -> Unit): PreviewCancel = error("The resource snapshot must reach the maker")
        override fun make(snapshot: BrushPreviewRequest, done: (String?) -> Unit): PreviewCancel {
            val call = Call(snapshot, done)
            calls += call
            return PreviewCancel { call.cancelled = true }
        }
    }
    private val codec = object : PreviewCodec<String> {
        override fun write(value: String, file: File): Boolean { file.writeText(value); return true }
        override fun read(file: File, width: Int, height: Int) = file.readText()
        override fun bytes(value: String) = value.length.toLong()
    }
    private fun brush(images: List<String> = listOf("tip.png")) = BrushPreset(id = "sample", name = "Sample", size = Param(24f),
        tip = TipSpec(sampled = SampledTipSpec(images)))
    // Encoded payload identity is tested here; PNG decode/admission belongs to the resolver's existing tests.
    private fun pngs(value: Int) = FrozenBrushPngs.snapshot(mapOf("tip.png" to byteArrayOf(1, 2, value.toByte())))
    private fun snapshot(value: Int, actual: StabilizerSpec? = null, pen: PreviewPenOverride? = null) =
        BrushPreviewRequest.snapshot(brush(), 64, 32, pngs = pngs(value), actualStabilizer = actual, penOverride = pen)

    @Test fun resourceAwareRendererRequestPreservesExactSnapshotAndLegacyConstructorStillWorks() {
        val brush = BrushPreset(id = "pen", name = "Pen", size = Param(24f))
        val snapshot = BrushPreviewRequest.snapshot(brush, 64, 32,
            actualStabilizer = StabilizerSpec(windowSize = 31), penOverride = PreviewPenOverride(.3f, .5f))
        val request = BrushPreviewRenderer.Request(snapshot, BrushPreviewTicket()) {}
        assertSame(snapshot, request.snapshot)
        assertEquals(31, request.snapshot.actualStabilizer!!.windowSize)
        val old = BrushPreviewRenderer.Request(brush, 64, 32, BrushPreviewStyle(), BrushPreviewTicket()) {}
        assertNull(old.snapshot.actualStabilizer)
        assertNull(old.snapshot.penOverride)
    }

    @Test fun samePathChangedBytesMakeAnotherPictureButEqualContentSharesFlightAndMemory() {
        val dir = java.nio.file.Files.createTempDirectory("preview_resources").toFile()
        try {
            val ui = Manual(); val io = Manual(); val maker = Maker()
            val cache = BrushPreviewCache(dir, maker, codec, ui, io)
            val got = ArrayList<String>()
            val first = snapshot(3)
            val equivalent = snapshot(3)
            assertNotSame(first.pngs, equivalent.pngs)
            assertEquals(BrushPreviewKey.of(first), BrushPreviewKey.of(equivalent))
            cache.request(first) { got += it }
            cache.request(equivalent) { got += it }
            io.drain(); ui.drain()
            assertEquals(1, maker.calls.size)
            assertEquals(3L, cache.inFlightResourceBytes)
            maker.calls.single().done("A")
            assertEquals(listOf("A", "A"), got)
            assertEquals(0L, cache.inFlightResourceBytes)
            cache.request(equivalent) { got += it }
            assertEquals(listOf("A", "A", "A"), got)
            val changed = snapshot(4)
            assertNotEquals(BrushPreviewKey.of(first), BrushPreviewKey.of(changed))
            cache.request(changed) { got += it }
            io.drain(); ui.drain()
            assertEquals(2, maker.calls.size)
            maker.calls.last().done("B")
            io.drain(); ui.drain()
            assertEquals("B", got.last())
            assertEquals(0L, cache.inFlightResourceBytes)
        } finally { dir.deleteRecursively() }
    }

    @Test fun actualStabilizerAndPenReadingsInvalidateKeysIncludingInactiveStoredFields() {
        val actual = StabilizerSpec(mode = StabilizerMode.off, strength = .2f)
        val first = snapshot(3, actual, PreviewPenOverride(.5f, .4f))
        assertNotEquals(BrushPreviewKey.of(first), BrushPreviewKey.of(snapshot(3, actual.copy(strength = .3f), first.penOverride)))
        assertNotEquals(BrushPreviewKey.of(first), BrushPreviewKey.of(snapshot(3, actual, PreviewPenOverride(.6f, .4f))))
        assertNotEquals(BrushPreviewKey.of(first), BrushPreviewKey.of(snapshot(3, actual, PreviewPenOverride(.5f, .6f))))
        assertNotEquals(BrushPreviewKey.of(snapshot(3, actual)), BrushPreviewKey.of(snapshot(3)))
        val legacy = BrushPreset(id = "pen", name = "Pen", size = Param(24f))
        assertEquals(BrushPreviewKey.of(legacy, 64, 32, BrushPreviewStyle()),
            BrushPreviewKey.of(BrushPreviewRequest.snapshot(legacy, 64, 32)))
    }

    @Test fun changedActualSettingsStartIndependentFlightsAndMakerFailureReleasesAssets() {
        val dir = java.nio.file.Files.createTempDirectory("preview_actual").toFile()
        try {
            val ui = Manual(); val io = Manual(); val maker = Maker()
            val cache = BrushPreviewCache(dir, maker, codec, ui, io)
            val actual = StabilizerSpec(windowSize = 8)
            val first = cache.request(snapshot(3, actual)) {}
            val second = cache.request(snapshot(3, actual.copy(windowSize = 9))) {}
            io.drain(); ui.drain()
            assertEquals(2, maker.calls.size)
            assertEquals(6L, cache.inFlightResourceBytes)
            first.cancel(); second.cancel()
            assertEquals(0L, cache.inFlightResourceBytes)
            val failing = object : PreviewMaker<String> {
                override fun make(p: BrushPreset, w: Int, h: Int, s: BrushPreviewStyle,
                                  done: (String?) -> Unit): PreviewCancel = error("Snapshot required")
                override fun make(snapshot: BrushPreviewRequest, done: (String?) -> Unit): PreviewCancel =
                    throw IllegalArgumentException("Decode refusal")
            }
            val refused = BrushPreviewCache(dir, failing, codec, ui, io)
            refused.request(snapshot(3)) {}
            io.drain()
            try { ui.drain(); fail("Maker refusal disappeared") }
            catch (expected: IllegalArgumentException) { assertEquals("Decode refusal", expected.message) }
            assertEquals(0, refused.inFlight)
            assertEquals(0L, refused.inFlightResourceBytes)
        } finally { dir.deleteRecursively() }
    }

    @Test fun pendingFlightKeepsTheRequestAsAdmittedAndCancelOrFailureReleasesItsOwnership() {
        val dir = java.nio.file.Files.createTempDirectory("preview_snapshot").toFile()
        try {
            val ui = Manual(); val io = Manual(); val maker = Maker()
            val cache = BrushPreviewCache(dir, maker, codec, ui, io)
            val names = arrayListOf("tip.png")
            val bytes = byteArrayOf(7, 8, 9)
            val sources = mutableMapOf("tip.png" to bytes)
            val request = BrushPreviewRequest.snapshot(brush(names), 64, 32,
                pngs = FrozenBrushPngs.snapshot(sources), actualStabilizer = StabilizerSpec(windowSize = 17))
            val cancel = cache.request(request) { fail("Cancelled request delivered") }
            names[0] = "another.png"; bytes.fill(0); sources.clear()
            io.drain(); ui.drain()
            val admitted = maker.calls.single().snapshot
            assertSame(request, admitted)
            assertEquals(listOf("tip.png"), admitted.preset.tip.sampled!!.images)
            assertArrayEquals(byteArrayOf(7, 8, 9), admitted.pngs!!.requirePng("tip.png"))
            assertEquals(17, admitted.actualStabilizer!!.windowSize)
            cancel.cancel(); cancel.cancel()
            assertTrue(maker.calls.single().cancelled)
            assertEquals(0, cache.inFlight)
            assertEquals(0L, cache.inFlightResourceBytes)
            cache.request(snapshot(4)) { fail("Null result delivered") }
            io.drain(); ui.drain(); maker.calls.last().done(null)
            assertEquals(0, cache.inFlight)
            assertEquals(0L, cache.inFlightResourceBytes)
        } finally { dir.deleteRecursively() }
    }

    @Test fun legacySamRefusesResourceSnapshotsInsteadOfDroppingThem() {
        val maker = PreviewMaker<String> { _, _, _, _, _ -> fail("Legacy maker was called"); PreviewCancel {} }
        try { maker.make(snapshot(3)) {}; fail("Expected explicit refusal") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("frozen resources")) }
        val source = PreviewSource<String> { _, _, _, _, _ -> fail("Legacy source was called"); PreviewCancel {} }
        try { source.request(snapshot(3)) {}; fail("Expected explicit refusal") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("frozen resources")) }
    }

    @Test fun synchronousMakerCannotReattachFinishedFlightOwnership() {
        val dir = java.nio.file.Files.createTempDirectory("preview_synchronous").toFile()
        try {
            val ui = Manual(); val io = Manual()
            var released = 0
            val maker = object : PreviewMaker<String> {
                override fun make(p: BrushPreset, w: Int, h: Int, s: BrushPreviewStyle,
                                  done: (String?) -> Unit): PreviewCancel = error("Resource path required")
                override fun make(snapshot: BrushPreviewRequest, done: (String?) -> Unit): PreviewCancel {
                    done("P")
                    return PreviewCancel { released++ }
                }
            }
            val cache = BrushPreviewCache(dir, maker, codec, ui, io)
            var got = ""
            val cancel = cache.request(snapshot(3)) { got = it }
            io.drain(); ui.drain()
            assertEquals("P", got)
            assertEquals(0L, cache.inFlightResourceBytes)
            assertEquals(1, released)
            cancel.cancel()
            assertEquals(1, released)
        } finally { dir.deleteRecursively() }
    }
}
