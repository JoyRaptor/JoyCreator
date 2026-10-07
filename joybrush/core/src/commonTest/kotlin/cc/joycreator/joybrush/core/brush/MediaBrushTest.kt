package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.chrome.BrushKnobs
import cc.joycreator.joybrush.core.chrome.BrushShelf
import cc.joycreator.joybrush.core.chrome.BrushTuning
import cc.joycreator.joybrush.core.media.EdgeMode
import cc.joycreator.joybrush.core.media.PasteBrushes
import cc.joycreator.joybrush.core.media.Sticks
import cc.joycreator.joybrush.core.media.WetBrushes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Media brushes as brush files (brush version 8, MEDIA_ENGINE_PLAN §4): the shipped set is the lab's tables, each one
 * round-trips and validates, every wrong file is refused in words, they land on Pencils / Watercolour / Oils, and every
 * knob is wired and stays inside a usable brush.
 */
class MediaBrushTest {

    private val all = MediaPresets.ALL
    private fun byId(id: String) = all.single { it.id == id }

    @Test fun theShippedSetIsTheLabTables() {
        assertEquals(Sticks.ALL.size + WetBrushes.ALL.size + PasteBrushes.ALL.size, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size, "ids are unique")
        assertEquals(all.size, all.map { it.name }.toSet().size, "the All shelf never shows two the same")
        assertEquals("All-round (watercolour)", byId("media:wet:All-round").name)
        assertEquals("All-round (oil)", byId("media:paste:All-round").name)
        assertEquals(PasteBrushes.ALL["Palette knife 2"], byId("media:paste:Palette knife 2").media?.paste)
        for (p in all) assertEquals(ENGINE_MEDIA, p.engine, p.id)
    }

    @Test fun everyShippedBrushValidatesAndRoundTrips() {
        for (p in all) {
            assertEquals(emptyList(), BrushValidate.validate(p), p.id)
            val json = BrushJson.encode(p)
            assertTrue("\"version\": $VERSION_MEDIA" in json, p.id)
            assertEquals(p.copy(version = VERSION_MEDIA), BrushJson.decodeChecked(json), p.id)
        }
    }

    @Test fun anOlderVersionCannotSayMedia() {
        val json = BrushJson.encode(byId("media:dry:HB")).replace("\"version\": 8", "\"version\": 7")
        val thrown = assertFailsWith<BrushException> { BrushJson.decode(json) }
        assertEquals("engine \"media\" needs brush version 8", thrown.message)
    }

    @Test fun aBrushWithoutMediaSaysNothingNew() {
        val ink = BrushPreset(id = "ink", name = "Ink", size = Param(6f), version = 1)
        assertTrue("\"version\": 1" in BrushJson.encode(ink), "an ordinary pen stays an old file")
    }

    private fun refusals(p: BrushPreset) = BrushValidate.validate(p).joinToString(" | ")

    @Test fun wrongMediaFilesAreRefusedInWords() {
        val hb = byId("media:dry:HB")
        val m = hb.media!!
        assertTrue("needs a media section" in refusals(hb.copy(media = null)))
        assertTrue("only read by engine \"media\"" in refusals(hb.copy(engine = "stamp")))
        assertTrue("media.medium \"chalk\" must be one of" in refusals(hb.copy(media = m.copy(medium = "chalk"))))
        assertTrue("media.tool is empty" in refusals(hb.copy(media = m.copy(tool = " "))))
        val swapped = refusals(hb.copy(media = m.copy(medium = MEDIUM_WET)))
        assertTrue("media.medium is \"wet\" but media.wet is missing" in swapped, swapped)
        assertTrue("media.stick is set but media.medium is \"wet\"" in swapped, swapped)
        assertTrue("media.wetness 5 must be 0..4" in refusals(hb.copy(media = m.copy(wetness = 5))))
        assertTrue("media.thinner -1 must be 0..3" in refusals(hb.copy(media = m.copy(thinner = -1))))
        assertTrue("media.belly \"plaid\"" in refusals(hb.copy(media = m.copy(belly = "plaid"))))
        val bad = refusals(hb.copy(media = m.copy(stick = m.stick!!.copy(tipR = -0.1, side2 = Double.NaN))))
        assertTrue("media.stick.tipR -0.1" in bad && "media.stick.side2 NaN" in bad, bad)
        val knife = byId("media:paste:Palette knife 2")
        val k = knife.media!!
        assertTrue("media.paste.riseMaxMm Infinity" in refusals(knife.copy(media = k.copy(paste = k.paste!!.copy(riseMaxMm = Double.POSITIVE_INFINITY)))))
    }

    @Test fun mediaBrushesLandOnPencilsWatercolourAndOils() {
        assertEquals(BrushShelf.Kind.PENCILS, BrushShelf.kindOf(byId("media:dry:Proto")))
        assertEquals(BrushShelf.Kind.WATERCOLOUR, BrushShelf.kindOf(byId("media:wet:Wash")))
        assertEquals(BrushShelf.Kind.OILS, BrushShelf.kindOf(byId("media:paste:Scraper")))
        val kinds = BrushShelf.shelves(all).map { it.first }
        assertEquals(listOf(BrushShelf.Kind.ALL, BrushShelf.Kind.PENCILS, BrushShelf.Kind.WATERCOLOUR, BrushShelf.Kind.OILS), kinds)
    }

    @Test fun sizeScalesTheTool() {
        for (p in all) assertEquals(1.0, p.mediaScale(), 1e-6, p.id)
        val hb = byId("media:dry:HB")
        assertEquals(0.5, hb.copy(size = Param(hb.size.base / 2)).mediaScale(), 1e-6)
        assertEquals(90f, byId("media:wet:All-round").media!!.referencePx, 1e-4f, "the belly's width: 4.5 mm at 20 px/mm")
    }

    @Test fun everyMediaKnobIsWiredAndKeepsTheBrushUsable() {
        for (p in all) {
            val knobs = BrushKnobs.forBrush(p)
            assertEquals(knobs.size, knobs.map { it.key }.toSet().size, "${p.id}: knob keys are unique")
            assertTrue(knobs.none { it.key.startsWith("paper.") }, "${p.id}: the media engine has its own paper")
            for (k in knobs) {
                if (!k.slider) continue
                // At either end the brush is still one the validator accepts: no slider can write a broken file.
                for (end in listOf(0f, 1f)) {
                    val moved = k.set(p, end)
                    assertEquals(emptyList(), BrushValidate.validate(moved), "${p.id} ${k.key} at $end")
                    assertTrue(abs(k.get(moved) - end) < 1e-3f, "${p.id} ${k.key}: set $end, reads ${k.get(moved)}")
                }
                // And the shipped value sits inside the slider's range, so opening the sheet changes nothing it shows.
                assertEquals(k.show(p), k.show(k.set(p, k.get(p))), "${p.id} ${k.key}: the shipped value is outside the range")
            }
        }
    }

    @Test fun eachMediumShowsItsOwnControls() {
        fun keys(id: String) = BrushKnobs.forBrush(byId(id)).map { it.key }
        assertTrue("media.wetness" in keys("media:wet:Round"))
        assertTrue("media.stick.rInf" in keys("media:dry:6B"))
        val oil = keys("media:paste:Oil flat")
        assertTrue("media.thinner" in oil && "media.belly" in oil && "media.paste.orient" !in oil, oil.toString())
        val knife = keys("media:paste:Palette knife 2")
        assertTrue("media.paste.orient" in knife && "media.paste.press" in knife && "media.paste.face" in knife && "media.belly" !in knife, knife.toString())
        val scraper = keys("media:paste:Scraper")
        assertTrue("media.thinner" !in scraper && "media.paste.press" !in scraper && "media.paste.thickMm" !in scraper, scraper.toString())
        val trowel = keys("media:paste:Palette knife 1")
        assertTrue("media.belly" !in trowel && "media.paste.hairDepth" !in trowel, trowel.toString())
    }

    @Test fun steppedKnobsSnapToTheirNamedLevels() {
        val round = byId("media:wet:Round")
        val water = BrushKnobs.forBrush(round).single { it.key == "media.wetness" }
        val dry = water.set(round, 0.1f)
        assertEquals(0, dry.media!!.wetness)
        assertEquals("dry", water.show(dry))
        assertEquals("runny", water.show(water.set(round, 1f)))
        val knife = byId("media:paste:Palette knife 2")
        val edge = BrushKnobs.forBrush(knife).single { it.key == "media.paste.orient" }
        assertEquals(EdgeMode.ACROSS, edge.set(knife, 0.5f).media!!.paste!!.orient)
    }

    @Test fun aSavedTuningIsLaidOverTheShippedBrush() {
        val hb = byId("media:dry:HB")
        val tuned = BrushTuning.apply(hb, mapOf(hb.id to mapOf("media.stick.tipR" to 1f, "media.wetness" to 0f)))
        assertEquals(1.0, tuned.media!!.stick!!.tipR, 1e-9)
        assertEquals(hb.media!!.wetness, tuned.media!!.wetness, "a knob this brush does not have is ignored")
    }
}
