package cc.joycreator.joybrush.android.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What leaves the preview folder after a picture is written. */
class PreviewDiskTrimTest {

    private fun name(id: String, hash: String, w: Int = 900, h: Int = 90) = "${id}_${hash}_${w}x$h.png"
    private val h1 = "0123456789abcdef"
    private val h2 = "fedcba9876543210"
    private val h3 = "aaaaaaaaaaaaaaaa"
    private val h4 = "bbbbbbbbbbbbbbbb"

    private fun entry(n: String, bytes: Long = 100, at: Long = 0) = PreviewDiskTrim.Entry(n, bytes, at)
    private fun keepOf(n: String) = BrushPreviewKey.parse(n)!!

    @Test fun theOtherVersionsOfTheBrushJustWrittenGo() {
        val keep = keepOf(name("pen", h2))
        val gone = PreviewDiskTrim.victims(listOf(entry(name("pen", h1), at = 1), entry(name("pen", h2), at = 2), entry(name("pen", h3, 400, 40), at = 3)),
            keep, PreviewDiskTrim.Limits())
        assertEquals(setOf(name("pen", h1), name("pen", h3, 400, 40)), gone.toSet())
    }

    @Test fun theSameVersionAtAnotherSizeStays() {
        val keep = keepOf(name("pen", h2))
        val gone = PreviewDiskTrim.victims(listOf(entry(name("pen", h2, 400, 40)), entry(name("pen", h2))), keep, PreviewDiskTrim.Limits())
        assertTrue(gone.isEmpty())
    }

    @Test fun otherBrushesAreLeftAlone() {
        val keep = keepOf(name("pen", h2))
        val gone = PreviewDiskTrim.victims(listOf(entry(name("pen_2", h1)), entry(name("brush", h1)), entry(name("pen", h2))), keep, PreviewDiskTrim.Limits())
        assertTrue(gone.isEmpty())
    }

    @Test fun filesThatAreNotOursAreNeverTouchedEvenOverTheLimit() {
        val keep = keepOf(name("pen", h2))
        val gone = PreviewDiskTrim.victims(listOf(entry("notes.txt", 10_000, 0), entry("x.png.tmp", 10_000, 0), entry(name("pen", h2))),
            keep, PreviewDiskTrim.Limits(maxFiles = 1, maxBytes = 1))
        assertTrue(gone.isEmpty())
    }

    @Test fun overTheFileLimitTheOldestGoFirst() {
        val a = name("a", h1); val b = name("b", h1); val c = name("c", h1); val d = name("d", h1)
        val keep = keepOf(d)
        val gone = PreviewDiskTrim.victims(listOf(entry(a, at = 10), entry(b, at = 20), entry(c, at = 30), entry(d, at = 40)), keep,
            PreviewDiskTrim.Limits(maxFiles = 2, maxBytes = Long.MAX_VALUE))
        assertEquals(listOf(a, b), gone)
    }

    @Test fun overTheByteLimitTheOldestGoUntilItFits() {
        val a = name("a", h1); val b = name("b", h1); val c = name("c", h1)
        val keep = keepOf(c)
        val gone = PreviewDiskTrim.victims(listOf(entry(a, 400, 1), entry(b, 400, 2), entry(c, 400, 3)), keep,
            PreviewDiskTrim.Limits(maxFiles = 100, maxBytes = 900))
        assertEquals(listOf(a), gone)
    }

    @Test fun theOneJustWrittenIsKeptEvenIfItIsTheOldestAndTheLimitIsTiny() {
        val a = name("a", h1); val b = name("b", h1)
        val keep = keepOf(a)
        val gone = PreviewDiskTrim.victims(listOf(entry(a, 5_000, 1), entry(b, 5_000, 2)), keep, PreviewDiskTrim.Limits(maxFiles = 1, maxBytes = 10))
        assertEquals(listOf(b), gone)
        assertFalse(a in gone)
    }

    @Test fun staleVersionsAreCountedOutBeforeTheLimitIsAsked() {
        val keep = keepOf(name("pen", h4))
        val a = name("a", h1)
        val gone = PreviewDiskTrim.victims(listOf(entry(name("pen", h3), at = 1), entry(a, at = 2), entry(name("pen", h4), at = 3)), keep,
            PreviewDiskTrim.Limits(maxFiles = 2, maxBytes = Long.MAX_VALUE))
        assertEquals(listOf(name("pen", h3)), gone, "the stale one goes, and then two files fit")
    }
}
