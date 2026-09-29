package cc.joycreator.joybrush.androidkit.bench

import cc.joycreator.joybrush.androidkit.io.ARCHIVE_DEFLATE_LEVEL
import cc.joycreator.joybrush.androidkit.io.JbArchive
import cc.joycreator.joybrush.androidkit.io.TILE_BYTES
import cc.joycreator.joybrush.core.bench.Bench
import cc.joycreator.joybrush.core.bench.BenchOutcome
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import cc.joycreator.joybrush.core.fill.MAX_FILL_PX
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import cc.joycreator.joybrush.core.render.RegionRenderer
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JB-0.10, the jobs half. These tests are a smoke test of the HARNESS, not a measurement: nothing
 * here asserts a millisecond, because a wall-clock assertion is flaky by construction (JB-5.10's was,
 * and R28 removed it for exactly that reason) and here the timing IS the output rather than the claim.
 *
 * WHAT IS ASSERTED INSTEAD is everything that does not vary with machine load:
 *
 *  - the inputs are byte-identical between two builds, so a faster number cannot be a smaller input;
 *  - every case does real work, and does DIFFERENT work from its neighbours;
 *  - the archive's bytes are built ONCE, outside the timed closure -- proven by a deterministic work
 *    counter, not by a timing figure (see [noFixtureIsBuiltInsideATimedRegion]);
 *  - the budget table has an entry for every case that exists and none for anything else;
 *  - the fold behaves as a fold and is honest about being a sample;
 *  - the report runs end to end and counts the three kinds of outcome separately.
 *
 * This is a CLASS on purpose: JUnit runs methods on an instance, so a file of top-level `@Test`
 * functions compiles, reports green and executes nothing.
 */
class BenchCasesTest {

    private fun allCases() =
        BenchCases.floodFill() + BenchCases.tileDeflate() + BenchCases.regionRender() + BenchCases.archiveRead()

    /** `BenchOutcome` is a sealed class whose two kinds each carry a name; this reads either. */
    private fun nameOf(outcome: BenchOutcome): String = when (outcome) {
        is BenchOutcome.Measured -> outcome.name
        is BenchOutcome.Unavailable -> outcome.name
    }

    // ---------------------------------------------------------------- 12. the inputs are fixed

    @Test
    fun theFillAndTileInputsAreTheSameBytesEveryTimeTheyAreBuilt() {
        // A benchmark whose input could differ between runs is measuring the input. Two builds of each
        // fixture must be identical, or the number is a report on a random picture.
        val fillA = BenchCases.fillReference()
        val fillB = BenchCases.fillReference()
        assertContentEquals(fillA, fillB, "two builds of the fill reference differ")
        assertEquals(
            BenchCases.FILL_SIDE * BenchCases.FILL_SIDE * 4,
            fillA.size,
            "a w x h premultiplied RGBA8 reference is w * h * 4 bytes",
        )
        // The size is derived from the engine's own cap, not from a number copied into this test:
        // MAX_FILL_PX is 2^23 and FILL_SIDE^2 = 4 194 304 is exactly half of it.
        assertTrue(
            BenchCases.FILL_SIDE.toLong() * BenchCases.FILL_SIDE.toLong() <= MAX_FILL_PX,
            "the fill case must fit inside fill.MAX_FILL_PX ($MAX_FILL_PX)",
        )

        val tileA = BenchCases.tileImage(0, 0)
        val tileB = BenchCases.tileImage(0, 0)
        assertContentEquals(tileA, tileB, "two builds of tile 0,0 differ")
        assertEquals(TILE_BYTES, tileA.size, "a paint tile is exactly TILE_BYTES")

        // And two ADDRESSES differ, which is the other half of the claim: a TileSource that answered
        // every address with the same tile would make the render fast and wrong.
        assertFalse(
            tileA.contentEquals(BenchCases.tileImage(1, 0)),
            "tile 0,0 and tile 1,0 are the same bytes, so the source cannot be answering by address",
        )

        // THE SEED MUST LAND ON PAPER. This is the guard for the mistake that was made once while
        // building these cases: a seed of FILL_SIDE / 8 is 256 on a 2048 board whose ink lines sit at
        // 0, 256, 512... -- so the tap lands ON a line, the flood leaves the paper and fills the
        // connected ink instead, and BOTH fill cases return the same count because neither of them is
        // filling what its name says. Three byte assertions, and that whole failure cannot come back
        // quietly.
        val seed = (BenchCases.FILL_SEED_Y * BenchCases.FILL_SIDE + BenchCases.FILL_SEED_X) * 4
        assertEquals(255, fillA[seed + 3].toInt() and 0xFF, "the seed must be opaque")
        assertEquals(255, fillA[seed].toInt() and 0xFF, "the seed must be on white paper, not on a line of ink")
        assertTrue(
            BenchCases.FILL_SEED_X in 0 until BenchCases.FILL_SIDE &&
                BenchCases.FILL_SEED_Y in 0 until BenchCases.FILL_SIDE,
            "a seed outside the board is a tap that missed, which FloodFill answers with an empty mask",
        )
    }

    // ---------------------------------------------------------------- 13. the work is real

    @Test
    fun theFillCasesFillRealPixelsAndTheTwoFillDifferentAmounts() {
        val cases = BenchCases.floodFill()
        assertEquals(2, cases.size)
        val byName = cases.associateBy { it.name }
        val gap0 = byName.getValue("flood-fill-${BenchCases.FILL_SIDE}x${BenchCases.FILL_SIDE}-gap0").run()
        val gap3 = byName.getValue("flood-fill-${BenchCases.FILL_SIDE}x${BenchCases.FILL_SIDE}-gap3").run()
        assertTrue(gap0 > 0L, "the gap0 fill filled no pixels at all")
        assertTrue(gap3 > 0L, "the gap3 fill filled no pixels at all")
        // THE TWO CASES MUST NOT BE THE SAME JOB, and the direction is NOT the obvious one. The
        // reference is a closed grid with ONE six-pixel break in the wall of the cell the tap lands in.
        // `gap0` leaks through the break into the next cell, so it fills TWICE as many pixels.
        // `gap3` dilates the ink until the break is sealed, so the fill cannot leave its own cell and
        // fills LESS -- while costing several times as much, because its extra work is four
        // full-image passes rather than anything proportional to what it filled. If these were equal,
        // one of the two settings would have quietly stopped doing anything.
        assertTrue(
            gap0 > gap3,
            "the gap0 case filled $gap0 px and the gap3 case filled $gap3 px: the six-pixel break was " +
                "not sealed by gapClosePx 3, so the two settings did the same thing",
        )
    }

    @Test
    fun theDeflateCaseCompressesAndDoesNotMerelyCopy() {
        val image = BenchCases.tileImage(0, 0)
        val deflated = BenchCases.deflated(image)
        assertTrue(
            deflated.size < image.size,
            "a ${image.size}-byte tile deflated to ${deflated.size}: the case has become a memcpy " +
                "benchmark, and its timing would say nothing about compression",
        )
        assertTrue(BenchCases.tileDeflate().single().run() != 0L, "the deflate case folded to zero")
    }

    @Test
    fun theRenderCasesProduceAWholePictureAtTheSizesTheirNamesState() {
        val cases = BenchCases.regionRender()
        assertEquals(
            listOf(
                "region-render-${BenchCases.RENDER_SMALL_W}x${BenchCases.RENDER_SMALL_H}",
                "region-render-${BenchCases.RENDER_4K_W}x${BenchCases.RENDER_4K_H}",
            ),
            cases.map { it.name },
        )
        // Both sizes are inside the engine's own cap, re-derived from the constants rather than
        // restated: 3840 * 2160 = 8 294 400 against MAX_REGION_PX = 8 388 608, so the 4K case is
        // 94 208 px under the cap, exactly as RegionRenderer's KDoc says.
        assertTrue(
            BenchCases.RENDER_4K_W.toLong() * BenchCases.RENDER_4K_H.toLong() <= MAX_REGION_PX,
            "the 4K case must fit inside render.MAX_REGION_PX ($MAX_REGION_PX)",
        )
        assertTrue(
            BenchCases.RENDER_SMALL_W.toLong() * BenchCases.RENDER_SMALL_H.toLong() <= MAX_REGION_PX,
        )

        // The 4K render is really 33 177 600 bytes of straight RGBA8.
        val w = BenchCases.RENDER_4K_W
        val h = BenchCases.RENDER_4K_H
        val doc = BenchCases.renderDoc(w, h)
        val source = BenchCases.renderSource(w, h)
        val out = RegionRenderer.render(doc, source, RectPx(0, 0, w, h), null, BenchCases.BENCH_PAPER)
        assertEquals(w * h * 4, out.size, "a w x h RGBA8 render is w * h * 4 bytes")
        assertTrue(out.any { it.toInt() != 0 }, "the 4K render is entirely zero bytes")

        // It is safe in the DEFAULT test heap, and this is the arithmetic rather than a hope:
        // MAX_REGION_PX's own KDoc costs one render at 20 bytes a pixel, and
        //   8 294 400 px x 20 B = 165 888 000 B = 158 MiB   against a 512 MB default Gradle heap.
        // If this test ever OOMs the answer is the case's size, not the heap.
        assertTrue(w.toLong() * h.toLong() * 20L < 512L * 1024L * 1024L)

        // Different sizes must give different checksums, or a source that answered every rect with the
        // same buffer would go unnoticed -- and no timing would show it.
        assertNotEquals(cases[0].run(), cases[1].run(), "the two render cases folded to the same number")
    }

    @Test
    fun theRenderSourceServesEveryTileTheDocumentDeclaresAndNullOutsideIt() {
        val w = BenchCases.RENDER_SMALL_W
        val h = BenchCases.RENDER_SMALL_H
        val doc = BenchCases.renderDoc(w, h)
        val layer = doc.layers.single()
        val cel = layer.cels.single()
        // 1024 / 256 = 4 per side, so 4 x 4 = 16 tiles -- derived from the engine's own tile size
        // rather than written down, so a tile-size change is a wrong expectation and not a silent pass.
        assertEquals(
            (BenchCases.RENDER_SMALL_W / TILE_SIZE) * (BenchCases.RENDER_SMALL_H / TILE_SIZE),
            cel.tiles.size,
            "the cel must declare every tile of the board",
        )
        val source = BenchCases.renderSource(w, h)

        for (key in cel.tiles) {
            val tx = key.substringBefore('_').toInt()
            val ty = key.substringAfter('_').toInt()
            val tile = source.tile(layer.id, cel.id, tx, ty)
            assertNotNull(tile, "the source refused $key, which the cel declares")
            assertEquals(TILE_BYTES, tile.size, "tile $key is not a whole paint tile")
        }
        assertEquals(
            cel.tiles.size,
            source.asked.size,
            "the source must have been asked once per declared tile",
        )
        assertNull(source.tile(layer.id, cel.id, 99, 99), "a tile off the board is null, not a tile")
    }

    @Test
    fun theTwoTileBytesConstantsInThisTreeAgree() {
        // There are TWO TILE_BYTES and both are 262 144: the top-level androidkit.io one the archive
        // writes with, and RegionRenderer.TILE_BYTES inside the core object. `tileDeflate` imports
        // the androidkit one, because importing the core one from androidkit would be a bet that two
        // files that agree today keep agreeing -- in the one place where being wrong measures the
        // wrong thing. This assertion is that bet checked, so it cannot rot unnoticed.
        assertEquals(RegionRenderer.TILE_BYTES, TILE_BYTES)
    }

    // ---------------------------------------------------------------- 14 / 15. the table and the order

    @Test
    fun theBudgetTableHasAnEntryForEveryCaseThatExistsAndNoneForAnythingElse() {
        val names = allCases().map { it.name }
        assertEquals(6, names.size, "two fills, one deflate, two renders, one archive read")
        for (name in names) {
            val budget = BenchCases.budgetMsFor(name)
            assertTrue(budget.isFinite() && budget > 0.0, "\"$name\" has budget $budget, which is not a budget")
        }
        // A case with no entry is NO_BUDGET, never a silent pass and never a crash.
        assertTrue(BenchCases.budgetMsFor("psd-write").isNaN(), "the PSD writer has no implementation to budget")
        assertTrue(BenchCases.budgetMsFor("no-such-case").isNaN())
        assertTrue(BenchCases.budgetMsFor("").isNaN())
    }

    @Test
    fun allReportsSevenOutcomesInOrderWithTheUnavailableOneLast() {
        // SEVEN, not six: two fills, one deflate, two renders and one archive read are SIX MEASURED
        // CASES, and `all()` adds the psd-write `Unavailable` on top, so the list has seven entries.
        // (The JB-0.10 spec says "six entries" and then enumerates seven things; the enumeration is
        // what is built, and the report's summary counts 6 measured + 1 not measured. Reported to the
        // Lead rather than resolved here.)
        val outcomes = BenchCases.all()
        assertEquals(7, outcomes.size)
        assertEquals(
            listOf(
                "flood-fill-${BenchCases.FILL_SIDE}x${BenchCases.FILL_SIDE}-gap0",
                "flood-fill-${BenchCases.FILL_SIDE}x${BenchCases.FILL_SIDE}-gap3",
                "tile-deflate-$ARCHIVE_DEFLATE_LEVEL",
                "region-render-${BenchCases.RENDER_SMALL_W}x${BenchCases.RENDER_SMALL_H}",
                "region-render-${BenchCases.RENDER_4K_W}x${BenchCases.RENDER_4K_H}",
                "archive-read-${BenchCases.ARCHIVE_TILES}",
                "psd-write",
            ),
            outcomes.map { nameOf(it) },
        )
        val last = assertIs<BenchOutcome.Unavailable>(outcomes.last(), "the report must end on the thing that is missing")
        assertTrue(last.reason.contains("JB-2.14c"), "the reason names the row that is not built: ${last.reason}")
        // Every measured outcome carries its table budget; a NaN here would mean the case names and
        // the table keys have drifted apart.
        for (o in outcomes.filterIsInstance<BenchOutcome.Measured>()) {
            assertEquals(BenchCases.budgetMsFor(o.name), o.budgetMs, "${o.name} did not get its budget")
        }
    }

    // ---------------------------------------------------------------- 16. the fold

    @Test
    fun foldIsAFunctionOfItsBytesAndIsHonestAboutBeingASample() {
        val bytes = ByteArray(9000) { (it % 251).toByte() }
        assertEquals(BenchCases.fold(bytes), BenchCases.fold(bytes), "the same bytes fold the same way")
        assertEquals(0L, BenchCases.fold(ByteArray(0)), "no bytes is 0")

        // THE OBSERVED POSITIONS ARE 0, stride, 2 * stride, ... -- NOT "the first 4 KiB". A 9 000-byte
        // array is observed at exactly three bytes: indices 0, 4 096 and 8 192. A byte that is not a
        // multiple of the stride is invisible to the fold, and saying otherwise here would be a
        // guarantee written down stronger than the code.
        val sampled = bytes.copyOf()
        sampled[0] = (sampled[0] + 1).toByte()
        assertNotEquals(BenchCases.fold(bytes), BenchCases.fold(sampled), "byte 0 is sampled and must matter")
        val alsoSampled = bytes.copyOf()
        alsoSampled[4096] = (alsoSampled[4096] + 1).toByte()
        assertNotEquals(BenchCases.fold(bytes), BenchCases.fold(alsoSampled), "byte 4096 is sampled too")

        val notSampled = bytes.copyOf()
        notSampled[5000] = (notSampled[5000] + 1).toByte()
        assertEquals(BenchCases.fold(bytes), BenchCases.fold(notSampled), "byte 5 000 is not a stride multiple")
        // And the one this test exists for: a case that returned NOTHING folds to a different number
        // from one that returned a whole picture of the same length, because byte 0 is sampled.
        assertNotEquals(
            BenchCases.fold(bytes),
            BenchCases.fold(ByteArray(9000)),
            "a run that produced nothing must not fold like a run that produced a picture",
        )

        // THE LENGTH IS MIXED IN LAST, and this is the sharp version of that claim. 4 097 bytes and 4 098
        // bytes, both filled with the same byte. The sampled positions are the multiples of 4 096 --
        // 0 and 4 096 for BOTH -- and the byte at index 4 097, which is the only one that differs
        // between the two arrays, is NOT a multiple of 4 096, so it is sampled by NEITHER. The two
        // arrays therefore differ ONLY in length. Drop the length from the fold and they become
        // equal, which is exactly the case where a truncated output folds like a full one and the
        // checksum says "the work happened" about a render that stopped early.
        val shorter = ByteArray(4097) { 9 }
        val longer = ByteArray(4098) { 9 }
        assertNotEquals(
            BenchCases.fold(shorter),
            BenchCases.fold(longer),
            "a one-byte-longer output with identical sampled bytes must fold differently",
        )
        // The coarse version too, because the sharp one alone would pass with a stride of 4 097.
        assertNotEquals(
            BenchCases.fold(ByteArray(4096) { 7 }),
            BenchCases.fold(ByteArray(8192) { 7 }),
            "two outputs of different lengths must not fold alike",
        )
    }

    // ---------------------------------------------------------------- 17. the level is read

    @Test
    fun theDeflateCaseIsNamedAfterTheArchiveOwnsLevelAndNotACopyOfIt() {
        // There is NO literal 6 in this test: the case name is built from ARCHIVE_DEFLATE_LEVEL, which
        // is what makes the test able to survive the level being changed. R39's whole point about the
        // duplication was that the number is READ, and a test that retyped it would restore the
        // duplication it exists to prevent.
        val case = BenchCases.tileDeflate().single()
        assertEquals("tile-deflate-" + ARCHIVE_DEFLATE_LEVEL, case.name)
        assertTrue(
            BenchCases.budgetMsFor(case.name) > 0.0,
            "the table's key must be built from the same constant, or the row would go NO_BUDGET",
        )
    }

    // ---------------------------------------------------------------- 20. the archive round trip

    @Test
    fun theArchiveCaseIsARoundTripAndItsBytesAreNotRebuiltPerCall() {
        val fixture = BenchCases.archiveFixture()
        val read = JbArchive.read(ByteArrayInputStream(fixture.bytes))
        assertEquals(fixture.doc, read.doc, "the document must come back as it went in")
        assertEquals(emptyList(), DocOps.validate(read.doc), "and it must still be a valid document")
        assertEquals(BenchCases.ARCHIVE_TILES, read.tiles.size)
        for ((key, tile) in read.tiles) assertEquals(TILE_BYTES, tile.size, "tile $key is not a whole paint tile")
        assertEquals(fixture.keys.sorted(), read.tiles.keys.map { it.third }.sorted())

        // NOT ASSERTED, DELIBERATELY: that two writes are byte-identical. `ZipOutputStream` stamps
        // every entry with the current time, so two writes of the same drawing differ in their headers
        // while their CONTENTS do not. A determinism test on the file bytes would be red for the right
        // reason and wrong in its conclusion.

        // What "the fixture is outside the closure" looks like from outside: running the case twice
        // gives the same checksum, because nothing about the case rebuilds or mutates its input.
        val case = BenchCases.archiveRead().single()
        assertEquals(case.run(), case.run(), "the archive case is not deterministic across its own runs")
    }

    // ---------------------------------------------------------------- THE ONE THAT MATTERS MOST

    @Test
    fun noFixtureIsBuiltInsideATimedRegion() {
        // THE ASSERTION THIS ROW EXISTS TO SUPPORT. Every other test here checks that a case does
        // work; this one checks that a case's reported milliseconds are the WORK and not the building
        // of the input it works on. A fixture constructed inside `run()` produces a plausible, stable,
        // completely meaningless number, and no amount of reading the report line shows it -- the
        // number is simply the cost of something nobody asked about.
        //
        // It cannot be checked with a clock (R28: a wall-clock assertion is flaky by construction) and
        // it cannot be checked by reading the code (a reader has to be in the room). So it is checked
        // with a DETERMINISTIC WORK COUNTER, R28's own idiom, counting fixture builds: build every
        // case once, note the counter, run every case through the harness (3 warm-ups + 9 repeats each,
        // so 60 timed regions), and require the counter not to have moved by a single build.
        val cases = allCases()
        val built = BenchCases.FixtureBuilds.total
        // Non-vacuity first: a dead counter would make the assertion below pass for no reason.
        assertTrue(built > 0, "the work counter never moved, so asserting it stays still proves nothing")
        assertEquals(6, cases.size)

        for (case in cases) {
            val measured = Bench.measure(case, nowMs = { 0L })
            assertEquals(Bench.DEFAULT_REPEATS, measured.samplesMs.size)
            assertTrue(measured.checksum != 0L, "${case.name} returned a constant zero and did nothing")
        }
        assertEquals(
            built,
            BenchCases.FixtureBuilds.total,
            "a case rebuilt its fixture inside the timed region, so its milliseconds are the cost of " +
                "constructing its own input",
        )
    }

    // ---------------------------------------------------------------- 21. the report, end to end

    @Test
    fun theReportOfEverythingRunsEndToEnd() {
        // The exact FORMAT is asserted in BenchTest, where the samples are numbers that test chose.
        // Here the samples are measurements, and a test that asserted them would fail on every machine
        // but this one -- so what is asserted is the SHAPE: six outcome lines, one of them saying the
        // PSD writer was not measured, and a summary that says so in words.
        val text = Bench.report(BenchCases.all(), "SM-N960F Android 10")
        val lines = text.lines()
        // A header, seven outcomes, a summary and the not-measured line.
        assertEquals(1 + 7 + 2, lines.size, "a header, seven outcomes, a summary and the not-measured line: $text")
        assertEquals("device: SM-N960F Android 10", lines[0])
        assertEquals(1, lines.count { it.contains("NOT MEASURED") }, "exactly one job is not measured: $text")
        // Names are padded to the longest name in the report, so this line cannot be matched by
        // substring with a fixed gap after "psd-write"; match the two facts separately.
        val notMeasured = lines.single { it.contains("NOT MEASURED") }.trim()
        assertTrue(notMeasured.startsWith("psd-write"), notMeasured)
        assertTrue(notMeasured.contains("JB-2.14c"), notMeasured)
        assertTrue(text.contains("6 measured,"), text)
        assertTrue(text.contains("1 not measured"), text)
        assertTrue(text.contains("not measured: psd-write"), text)
        for (line in lines.drop(1).take(7)) {
            if (line.contains("NOT MEASURED")) continue
            assertTrue(line.contains("median ") && line.contains(" p95 ") && line.contains(" checksum "), line)
            assertTrue(line.contains("budget "), "every measured line prints its budget: $line")
        }
    }
}
