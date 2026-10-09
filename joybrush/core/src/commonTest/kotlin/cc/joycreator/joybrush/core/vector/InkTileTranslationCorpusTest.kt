package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.GrainSpec
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer.Items
import cc.joycreator.joybrush.core.vector.CelComposer.Line
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JB-NOW-27 — real ink-tile footprint/translation regression (repro JB-NOW-27-20261009).
 *
 * The CPU ink path promises (InkReplay KDoc: "the same recording draws the same marks, every
 * time"; InkRaster.stampInto KDoc: "the origin is a SUBTRACTION and never a modulo") that a
 * recording shifted by whole tiles draws the same bytes on the shifted tiles. This file pins
 * that promise with real production APIs and no tolerance: translated corresponding tile bytes
 * are `assertContentEquals`, not "close".
 *
 * Fixtures use INTEGER document coordinates with smoothing 0 and pressure 1, so a whole-tile
 * shift stays exact in Float below 2^24 (integer + 256 is exact; the shipped Ink's placer's
 * 0.5-doc-px floor puts every dab on a .0/.5 grid that survives +256 exactly). Byte equality is
 * therefore exact with no rounding excuse, and no tolerance is loosened anywhere.
 *
 * What this is NOT: no all-brush optical equivalence (only the shipped Ink stamp + the shipped
 * fill pen), no GPU claim (CPU `InkTiles`/`InkRaster` only), no paper/tip-grain rendering.
 * Coordinate-dependent textures are an EXEMPTION reported from the real spec (see the grain
 * test): `InkTiles.prepare` refuses tip grain by name, and `GrainMath.paperGrainUv` addresses
 * the paper grain by document position, so a textured brush cannot promise translation
 * equivalence on any backend. The test asserts the refusal rather than comparing pixels.
 *
 * Bounded: every raster fixture is one 256x256 tile, every line has <= 43 samples, every
 * footprint touches <= 4 tiles. Seeds are fixed (73L/74L); no timing thresholds.
 */
class InkTileTranslationCorpusTest {

    // ── real supported preset lookup ─────────────────────────────────────────────
    //
    // commonTest cannot open a file, so these are copies of joybrush/brushes/ink/brush.json
    // (version 6) and joybrush/brushes/fill/brush.json (version 2), kept in step by hand — the
    // same bargain BrushTest strikes. They are decoded through the REAL door
    // (BrushJson.decodeChecked, which runs BrushValidate), so the lookup below is a supported
    // preset the app ships, not a hand-built synthetic.

    private val inkJson: String = """
        {
          "format": "joybrush.brush",
          "version": 6,
          "id": "joybrush.ink",
          "name": "Ink",
          "engine": "stamp",
          "tip": {
            "corner": 2,
            "hardness": { "base": 0.95 }
          },
          "size": {
            "base": 6,
            "inputs": [ { "input": "pressure", "curve": [ [0, 0.15], [1, 1] ] } ]
          },
          "opacity": { "base": 1 },
          "flow": { "base": 1 },
          "spacing": 0.04,
          "paper": { "influence": 0.15, "directional": 0, "wet": 0 },
          "accumulate": "wash",
          "blend": "normal",
          "smoothing": 0.35,
          "license": "CC0"
        }
    """.trimIndent() + "\n"

    private val fillJson: String = """
        {
          "format": "joybrush.brush",
          "version": 2,
          "id": "fill",
          "name": "Fill pen",
          "engine": "fill",
          "size": { "base": 8 },
          "opacity": { "base": 1 },
          "blend": "normal",
          "smoothing": 0.3,
          "license": "CC0"
        }
    """.trimIndent() + "\n"

    private val ink: BrushPreset = BrushJson.decodeChecked(inkJson)
    private val fill: BrushPreset = BrushJson.decodeChecked(fillJson)

    private fun lookup(id: String): BrushPreset? = when (id) {
        "joybrush.ink" -> ink
        "fill" -> fill
        else -> null
    }

    // ── deterministic fixtures (integer doc px, smoothing 0, pressure 1) ──────────

    /** Level stamp line x = 40..460 at y = 128: crosses the +256 seam from tile (0,0) to (1,0). */
    private fun stampPositive(
        id: String = "pos",
        seed: Long = 73L,
    ) = StrokeRecord(
        id = id,
        brushId = "joybrush.ink",
        seed = seed,
        smoothing = 0f,
        screenPerDoc = 1f,
        samples = (0..42).map { PenSample(40f + it * 10f, 128f, it * 8.0, pressure = 1f) },
        colorArgb = 0xFF1B1B22.toInt(),
    )

    /** Level stamp line x = -300..100 at y = -10: crosses negative seams, tiles (-2,-1),(-1,-1),(0,-1). */
    private fun stampNegative(
        id: String = "neg",
        seed: Long = 73L,
    ) = StrokeRecord(
        id = id,
        brushId = "joybrush.ink",
        seed = seed,
        smoothing = 0f,
        screenPerDoc = 1f,
        samples = (0..40).map { PenSample(-300f + it * 10f, -10f, it * 8.0, pressure = 1f) },
        colorArgb = 0xFF1B1B22.toInt(),
    )

    /** Fill square (200,200)-(300,200)-(300,300)-(200,300): straddles the +256 seam both ways. */
    private fun fillSquare(
        id: String = "sq",
    ) = StrokeRecord(
        id = id,
        brushId = "fill",
        seed = 74L,
        smoothing = 0f,
        screenPerDoc = 1f,
        samples = listOf(
            PenSample(200f, 200f, 0.0),
            PenSample(300f, 200f, 8.0),
            PenSample(300f, 300f, 16.0),
            PenSample(200f, 300f, 24.0),
        ),
        colorArgb = 0xFF336699.toInt(),
    )

    /** Whole-tile shift of [record] by ([dxTiles], [dyTiles]) tiles; seed and all else preserved. */
    private fun shifted(record: StrokeRecord, dxTiles: Int, dyTiles: Int, id: String = record.id + "-t"): StrokeRecord {
        val dx = dxTiles * TILE_SIZE.toFloat()
        val dy = dyTiles * TILE_SIZE.toFloat()
        return record.copy(
            id = id,
            samples = record.samples.map { it.copy(x = it.x + dx, y = it.y + dy) },
        )
    }

    private fun drawn(records: List<StrokeRecord>): CelComposer.Drawn {
        val items = Items(records.mapIndexed { i, r -> Line(i + 1L, r) }, emptyMap())
        return CelComposer.draw(items) { lookup(it) }
    }

    private fun alphaSum(bytes: ByteArray?): Int {
        if (bytes == null) return 0
        var n = 0
        var i = 3
        while (i < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0) n++
            i += 4
        }
        return n
    }

    // ── 1. the lookup is real and supported ───────────────────────────────────────

    /**
     * The shipped Ink + fill decodeChecked clean (zero validation problems by construction —
     * decodeChecked would throw otherwise), draw through the real replay with no refusals, and
     * put non-blank bytes on the tile the line crosses. Non-vacuity: the tile is asserted
     * non-blank first, so a renderer that returned null everywhere would fail here, not pass.
     */
    @Test
    fun shippedLookupIsRealAndSupported() {
        assertEquals("joybrush.ink", ink.id, "the stamp preset is the shipped Ink")
        assertEquals("fill", fill.id, "the fill preset is the shipped fill pen")
        assertEquals(null, InkReplay.refusal(stampPositive(), ink), "shipped Ink draws an ink line")
        assertEquals(null, InkReplay.refusal(fillSquare(), fill), "the fill pen draws a shape")

        val prepared = InkTiles.prepare(listOf(stampPositive(), fillSquare())) { lookup(it) }
        assertTrue(prepared.refusals.isEmpty(), "real lookup refuses nothing: ${prepared.refusals}")

        val tile = InkTiles.render(prepared, 0, 0)
        assertTrue(tile.refusals.isEmpty())
        assertNotNull(tile.pixels, "the positive line + square both reach tile (0,0)")
        assertTrue(alphaSum(tile.pixels) > 0, "NON-VACUITY: tile (0,0) is not blank")
    }

    // ── 2. footprint across positive and negative seams ──────────────────────────

    /**
     * Hand-computed tile keys (floor(x / 256), floor(y / 256)):
     * - positive line y=128 in [0,256) -> ty 0; x 40..460 spans [0,256) and [256,512) -> tx 0,1.
     * - negative line y=-10 in [-256,0) -> ty -1; x -300..100 spans [-512,-256),[-256,0),[0,256)
     *   -> tx -2,-1,0.
     * Both verified through CelComposer.tiles (the real reachability rule) and through
     * InkTiles.render (non-null where reached, null two tiles away). Bounded: 2 and 3 tiles.
     */
    @Test
    fun stampFootprintAcrossPositiveAndNegativeSeamsHasExactTileKeys() {
        assertEquals(256, TILE_SIZE, "the tile grid the keys are hand-computed against")

        val posKeys = drawn(listOf(stampPositive())).let { CelComposer.tiles(it) }
        val expectPos = setOf(Tiles.key(0, 0), Tiles.key(1, 0))
        assertEquals(expectPos, posKeys, "positive line touches exactly tiles (0,0) and (1,0)")
        assertEquals(listOf(0, 1), posKeys.map { Tiles.tx(it) }.sorted().distinct(), "tx by hand")
        assertEquals(listOf(0), posKeys.map { Tiles.ty(it) }.sorted().distinct(), "ty by hand")

        val negKeys = drawn(listOf(stampNegative())).let { CelComposer.tiles(it) }
        val expectNeg = setOf(Tiles.key(-2, -1), Tiles.key(-1, -1), Tiles.key(0, -1))
        assertEquals(expectNeg, negKeys, "negative line touches exactly tiles (-2,-1),(-1,-1),(0,-1)")

        // The bytes agree with the keys: reached tiles are non-blank, far tiles are null.
        for ((tx, ty) in listOf(0 to 0, 1 to 0)) {
            val r = InkTiles.render(listOf(stampPositive()), tx, ty) { lookup(it) }
            assertNotNull(r.pixels, "tile ($tx,$ty) holds the positive line")
            assertTrue(alphaSum(r.pixels) > 0, "NON-VACUITY: tile ($tx,$ty) is not blank")
        }
        for ((tx, ty) in listOf(-2 to -1, -1 to -1, 0 to -1)) {
            val r = InkTiles.render(listOf(stampNegative()), tx, ty) { lookup(it) }
            assertNotNull(r.pixels, "tile ($tx,$ty) holds the negative line")
        }
        assertNull(
            InkTiles.render(listOf(stampPositive()), 5, -5) { lookup(it) }.pixels,
            "a tile two tiles away from a 420-doc-px line is transparent (null)",
        )
        assertNull(
            InkTiles.render(listOf(stampNegative()), 5, 5) { lookup(it) }.pixels,
            "same bound on the negative side",
        )
    }

    // ── 3. integer-tile translation is byte-identical (stamp) ────────────────────

    /**
     * THE TRANSLATION PROMISE for the stamp path. Base line L on tiles (0,0),(1,0); L' = L +
     * (512,-256) on tiles (2,-1),(3,-1). Corresponding tile bytes are EXACTLY equal — the
     * subtraction origin (InkRaster.stampInto KDoc) plus the seed-preserving replay (InkReplay
     * KDoc) leave no room for a tolerance, and none is taken.
     */
    @Test
    fun integerTileShiftGivesByteIdenticalTilesForStamp() {
        val base = stampPositive()
        val moved = shifted(base, 2, -1)
        assertEquals(base.seed, moved.seed, "the shift preserves the seed: same stream, moved leaves")

        val pairs = listOf((0 to 0) to (2 to -1), (1 to 0) to (3 to -1))
        for ((a, b) in pairs) {
            val left = assertNotNull(
                InkTiles.render(listOf(base), a.first, a.second) { lookup(it) }.pixels,
                "base tile ${a.first},${a.second} is non-blank",
            )
            val right = assertNotNull(
                InkTiles.render(listOf(moved), b.first, b.second) { lookup(it) }.pixels,
                "shifted tile ${b.first},${b.second} is non-blank",
            )
            assertContentEquals(left, right, "tile ${a.first},${a.second} + (2,-1) tiles is byte-identical")
        }

        // Same promise through the composer look (slabs empty, one line): identical bytes.
        val dBase = drawn(listOf(base))
        val dMoved = drawn(listOf(moved))
        for ((a, b) in pairs) {
            val left = assertNotNull(CelComposer.look(dBase, Tiles.key(a.first, a.second)))
            val right = assertNotNull(CelComposer.look(dMoved, Tiles.key(b.first, b.second)))
            assertContentEquals(left, right, "composer look agrees: translation is byte-identical")
        }
    }

    // ── 4. the shift preserves the recording ─────────────────────────────────────

    /**
     * The input records are the truth (blueprint §2): prepare/render must not mutate them, and
     * the shifted copy keeps seed/smoothing/screenPerDoc/colour/widthScale with every sample
     * moved by exactly (dx,dy). Sample positions are integers + whole tiles, so `x + dx` is
     * exact in Float below 2^24 and is asserted with `==`, not a tolerance.
     */
    @Test
    fun translationPreservesSeedAndLeavesInputsUntouched() {
        val base = stampPositive()
        val snapshot = base.samples.map { it.copy() }
        InkTiles.prepare(listOf(base)) { lookup(it) }
        InkTiles.render(listOf(base), 0, 0) { lookup(it) }
        InkTiles.render(listOf(base), 1, 0) { lookup(it) }
        assertEquals(snapshot, base.samples, "prepare/render leave the recording untouched")

        val moved = shifted(base, 2, -1)
        assertEquals(base.seed, moved.seed, "seed preserved")
        assertEquals(base.smoothing, moved.smoothing, "smoothing preserved")
        assertEquals(base.screenPerDoc, moved.screenPerDoc, "screenPerDoc preserved")
        assertEquals(base.colorArgb, moved.colorArgb, "colour preserved")
        assertEquals(base.widthScale, moved.widthScale, "widthScale preserved")
        assertEquals(base.samples.size, moved.samples.size, "same sample count")
        val dx = 2 * TILE_SIZE.toFloat()
        val dy = -1 * TILE_SIZE.toFloat()
        for (i in base.samples.indices) {
            assertTrue(moved.samples[i].x == base.samples[i].x + dx, "sample $i x shifted exactly by $dx")
            assertTrue(moved.samples[i].y == base.samples[i].y + dy, "sample $i y shifted exactly by $dy")
            assertEquals(base.samples[i].timeMs, moved.samples[i].timeMs, "sample $i time kept")
            assertEquals(base.samples[i].pressure, moved.samples[i].pressure, "sample $i pressure kept")
        }
        // The replay draws the same COUNT from the same seed: the stream is preserved, moved only.
        assertEquals(
            InkReplay.dabs(base, ink).size,
            InkReplay.dabs(moved, ink).size,
            "same seed + same pressures = same dab count",
        )
    }

    // ── 5. fill footprint + translation ──────────────────────────────────────────

    /**
     * Fill-pen half of the promise. The square (200..300, 200..300) straddles x=256 and y=256,
     * so its hand-computed footprint is the four tiles (0,0),(1,0),(0,1),(1,1); shifted by
     * (+1,-2) tiles its footprint is (1,-2),(2,-2),(1,-1),(2,-1) with byte-identical
     * corresponding tiles (InkRaster.fill addresses docX/docY by subtraction, same as stamps).
     */
    @Test
    fun fillFootprintAndTranslationAreByteIdentical() {
        val square = fillSquare()
        val keys = drawn(listOf(square)).let { CelComposer.tiles(it) }
        val expect = setOf(Tiles.key(0, 0), Tiles.key(1, 0), Tiles.key(0, 1), Tiles.key(1, 1))
        assertEquals(expect, keys, "square straddling 256/256 touches exactly four tiles")
        for (key in expect) {
            val bytes = InkTiles.render(listOf(square), Tiles.tx(key), Tiles.ty(key)) { lookup(it) }.pixels
            assertNotNull(bytes, "tile ${Tiles.tx(key)},${Tiles.ty(key)} holds the square")
            assertTrue(alphaSum(bytes) > 0, "NON-VACUITY: tile ${Tiles.tx(key)},${Tiles.ty(key)} not blank")
        }

        val moved = shifted(square, 1, -2, id = "sq-t")
        val movedKeys = drawn(listOf(moved)).let { CelComposer.tiles(it) }
        val expectMoved = setOf(Tiles.key(1, -2), Tiles.key(2, -2), Tiles.key(1, -1), Tiles.key(2, -1))
        assertEquals(expectMoved, movedKeys, "shifted square touches the shifted four tiles")

        for (key in expect) {
            val a = assertNotNull(InkTiles.render(listOf(square), Tiles.tx(key), Tiles.ty(key)) { lookup(it) }.pixels)
            val b = assertNotNull(
                InkTiles.render(listOf(moved), Tiles.tx(key) + 1, Tiles.ty(key) - 2) { lookup(it) }.pixels,
            )
            assertContentEquals(a, b, "fill tile ${Tiles.tx(key)},${Tiles.ty(key)} shifted by (1,-2) is byte-identical")
        }
    }

    // ── 6. coordinate-dependent texture exemption (refusal, not tolerance) ───────

    /**
     * EXEMPTION, reported from the real spec rather than papered over with a tolerance.
     * `InkTiles.prepare` (InkTiles.kt) refuses `tipTexture.enabled` ("uses tip grain; CPU ink
     * rendering cannot reproduce it") and image tips ("uses an image tip; CPU ink rendering has
     * no image source"). The reason is coordinate dependence: `GrainMath.tipGrainUv` turns with
     * the dab and `GrainMath.paperGrainUv` addresses `doc / pitch` (the same document point is
     * the same texel whatever the brush does), so a textured stroke's look depends on absolute
     * document position and translation equivalence cannot be promised for it on any backend.
     * This test pins the refusal sentences and asserts such strokes render null (transparent),
     * with no pixel comparison and no loosened tolerance. CPU grain-free translation above is
     * unaffected; GPU texturing is explicitly out of scope.
     */
    @Test
    fun texturedBrushesAreRefusedWithCoordinateExemption() {
        val grained = ink.copy(id = "grained", tipTexture = GrainSpec(enabled = true))
        val record = stampPositive(id = "g1")
        val prepared = InkTiles.prepare(listOf(record)) { if (it == record.brushId) grained else null }
        assertEquals(1, prepared.refusals.size, "grain is one refusal, not a drawing")
        assertEquals(
            "stroke \"g1\" uses tip grain; CPU ink rendering cannot reproduce it",
            prepared.refusals[0].reason,
            "InkTiles' own tip-grain sentence, hand-quoted from InkTiles.prepare (no self-comparison)",
        )
        assertNull(
            InkTiles.render(listOf(record), 0, 0) { grained }.pixels,
            "a refused-only tile is transparent (null)",
        )

        val imaged = ink.copy(id = "imaged", tip = ink.tip.copy(source = "image"))
        val imageRefused = InkTiles.render(listOf(record), 0, 0) { imaged }
        assertEquals(1, imageRefused.refusals.size)
        assertEquals(
            "stroke \"g1\" uses an image tip; CPU ink rendering has no image source",
            imageRefused.refusals[0].reason,
            "InkTiles' own image-tip sentence, hand-quoted (no self-comparison)",
        )
        assertNull(imageRefused.pixels)
    }

    // ── 7. empty, missing lookup, and a narrow edge-touching stroke ─────────────

    /**
     * Boundaries: an empty cel is transparent with no refusals; a brush id with no preset is
     * refused IN WORDS (InkReplay.refusal's exact sentence, naming stroke and brush) and draws
     * nothing; a one-sample dot exactly ON the seam x=256 (radius ~3 doc px) reaches both tiles
     * (0,0) and (1,0) with non-blank bytes while a far tile stays null. Narrow = one dab, no
     * sweep across tiles to hide behind.
     */
    @Test
    fun emptyMissingAndEdgeTouchingStroke() {
        val empty = InkTiles.render(emptyList(), 0, 0) { lookup(it) }
        assertNull(empty.pixels, "nothing drawn is transparent (null)")
        assertTrue(empty.refusals.isEmpty(), "and there is nothing to refuse")

        val missing = StrokeRecord(
            id = "m1", brushId = "no-such-brush", seed = 73L, smoothing = 0f, screenPerDoc = 1f,
            samples = (0..10).map { PenSample(40f + it * 10f, 128f, it * 8.0) },
        )
        val refused = InkTiles.render(listOf(missing), 0, 0) { lookup(it) }
        assertNull(refused.pixels, "a missing lookup draws nothing")
        assertEquals(1, refused.refusals.size)
        assertEquals(0, refused.refusals[0].index, "refusal position is the record index")
        assertEquals("m1", refused.refusals[0].strokeId)
        assertEquals(
            InkReplay.refusal(missing, null), refused.refusals[0].reason,
            "the refusal is InkReplay's own sentence (names stroke and brush), quoted exactly",
        )
        assertTrue(refused.refusals[0].reason.contains("m1"), "names the stroke: ${refused.refusals[0].reason}")
        assertTrue(refused.refusals[0].reason.contains("no-such-brush"), "names the brush: ${refused.refusals[0].reason}")

        // Narrow edge-touching dot: one sample exactly on the seam.
        val dot = StrokeRecord(
            id = "dot", brushId = "joybrush.ink", seed = 73L, smoothing = 0f, screenPerDoc = 1f,
            samples = listOf(PenSample(TILE_SIZE.toFloat(), 128f, 0.0, pressure = 1f)),
        )
        assertTrue(InkReplay.dabs(dot, ink).isNotEmpty(), "NON-VACUITY: one sample draws one dot")
        val left = InkTiles.render(listOf(dot), 0, 0) { lookup(it) }
        val right = InkTiles.render(listOf(dot), 1, 0) { lookup(it) }
        assertNotNull(left.pixels, "the seam dot reaches tile (0,0)")
        assertNotNull(right.pixels, "and tile (1,0)")
        assertTrue(alphaSum(left.pixels) > 0 && alphaSum(right.pixels) > 0, "both sides hold real bytes")
        assertEquals(
            setOf(Tiles.key(0, 0), Tiles.key(1, 0)),
            drawn(listOf(dot)).let { CelComposer.tiles(it) },
            "the dot's footprint is exactly the two seam neighbours",
        )
        assertNull(InkTiles.render(listOf(dot), 0, 1) { lookup(it) }.pixels, "the tile above the dot stays transparent")
    }
}
