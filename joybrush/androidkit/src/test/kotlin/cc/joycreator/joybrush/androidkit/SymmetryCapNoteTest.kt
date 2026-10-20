package cc.joycreator.joybrush.androidkit

import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.symmetry.HardcodedTip
import cc.joycreator.joybrush.core.symmetry.LaneBrush
import cc.joycreator.joybrush.core.symmetry.SymmetryCap
import cc.joycreator.joybrush.core.symmetry.SymmetryFamily
import cc.joycreator.joybrush.core.symmetry.SymmetryLanes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The symmetry's cap note is said ONCE.
 *
 * The owner asked for *"a small note when the cap hits"* — ONE note. A person painting ten strokes
 * with the same brush must hear it once and not ten times; the note is said again when the brush
 * family or the cap changes.
 *
 * The memory is the VIEW's and not the core's: the core's lane code is a pure function of its
 * inputs and only reports WHICH cap applied, so the "have I already said this" flag is the
 * view's business. This is where that rule is proved.
 */
class SymmetryCapNoteTest {

    /** The ink and pen family at the owner's thirty copies. */
    private val ink = SymmetryCap(SymmetryFamily.STAMP, 30)

    /** The wet media family at its provisional eight copies. */
    private val wet = SymmetryCap(SymmetryFamily.MEDIA_WET, 8)

    @Test
    fun theNoteIsSaidOnceForTheSameFamilyAndCap() {
        val note = SymmetryCapNote()
        assertEquals(
            "This brush keeps symmetry to 30 copies on this phone.",
            note.sentenceFor(ink),
            "the note says the COPY count - the number the owner asked about",
        )
        assertNull(note.sentenceFor(ink), "the second stroke with the same brush says it again")
        assertNull(note.sentenceFor(ink), "and the third does not either")
    }

    @Test
    fun theNoteIsSaidAgainWhenTheFamilyOrTheCapChanges() {
        val note = SymmetryCapNote()
        assertEquals("This brush keeps symmetry to 30 copies on this phone.", note.sentenceFor(ink))
        // Another family: said again, and with its own number.
        assertEquals("This brush keeps symmetry to 8 copies on this phone.", note.sentenceFor(wet))
        // The same family with another cap: said again.
        assertEquals(
            "This brush keeps symmetry to 16 copies on this phone.",
            note.sentenceFor(SymmetryCap(SymmetryFamily.STAMP, 16)),
        )
        // And back to the ink family at thirty: said again, because the note is per (family, cap).
        assertEquals("This brush keeps symmetry to 30 copies on this phone.", note.sentenceFor(ink))
    }

    @Test
    fun aCapThatDidNotBitSaysNothing() {
        val note = SymmetryCapNote()
        assertNull(note.sentenceFor(null), "no cap, no note")
        assertNull(note.sentenceFor(null), "and still no note")
        // A stroke that was not capped does not spend the note either.
        assertEquals("This brush keeps symmetry to 30 copies on this phone.", note.sentenceFor(ink))
        assertNull(note.sentenceFor(null), "an uncapped stroke says nothing")
        assertNull(note.sentenceFor(ink), "and the capped one is still said once")
    }

    /**
     * The whole round trip: the CORE says which cap applied, and the VIEW turns that into the
     * sentence a person hears - once.
     *
     * A kaleidoscope of thirty asks for fifty-nine copies; the ink family may make thirty, so the
     * cap bites and the core reports it. The number the sentence carries is the copy count.
     */
    @Test
    fun theCoreSaysWhichCapAppliedAndTheNoteSaysItOnce() {
        val guide = Guide.Symmetry(Pt(0.0, 0.0), Pt(100.0, 0.0), count = 30, kaleido = true)
        val set = SymmetryLanes.build(
            guide,
            LaneBrush.Hardcoded(
                HardcodedTip(
                    sizePx = 8f,
                    minSizeFraction = 0.5f,
                    flow = 1f,
                    spacing = 0.25f,
                    cap = 1f,
                    followDirection = true,
                ),
            ),
            SymmetryFamily.STAMP,
            20261019L,
            0.35f,
            1f,
        )
        val cap = assertNotNull(set.cap, "the cap bit and was not reported")
        assertEquals(SymmetryFamily.STAMP, cap.family, "the core names the family")
        assertEquals(30, cap.copies, "the core reports the COPY count")

        val note = SymmetryCapNote()
        assertEquals(
            "This brush keeps symmetry to 30 copies on this phone.",
            note.sentenceFor(cap),
        )
        assertNull(note.sentenceFor(cap), "the second stroke with the same brush does not say it again")
    }
}
