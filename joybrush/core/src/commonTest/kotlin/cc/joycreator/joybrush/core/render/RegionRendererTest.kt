package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The renderer, checked against values worked out by hand.
 *
 * The blend table is the reason this file is mostly what it is: every exporter is downstream of
 * these eight numbers, and a blend that is silently wrong (rather than obviously wrong) is what
 * this suite exists to prevent.
 */
class RegionRendererTest {

    // ── the blend table ────────────────────────────────────────────────────────

    /**
     * One source, one backdrop, all eight modes, every expected value worked out from the W3C
     * formula by hand.
     *
     * The pair is 50% red over 50% blue, both PREMULTIPLIED, so Cs = (0.8, 0.2, 0.1) and
     * Cb = (0.3, 0.6, 0.9). With sa = da = 0.5 the formula collapses to
     * `co = 0.25(Cs + B + Cb)` and `a = 0.75`, which is what makes eight values checkable by hand.
     */
    @Test
    fun allEightBlendModesOnOneHandCheckedPair() {
        val s = floatArrayOf(0.4f, 0.1f, 0.05f, 0.5f)   // premultiplied 50% red
        val d = floatArrayOf(0.15f, 0.3f, 0.45f, 0.5f)  // premultiplied 50% blue
        val table = listOf(
            //  B(0.8,0.3)=0.8  B(0.2,0.6)=0.2  B(0.1,0.9)=0.1
            Triple(BlendMode.NORMAL, floatArrayOf(0.475f, 0.25f, 0.275f, 0.75f), "B = Cs"),
            //  0.24, 0.12, 0.09
            Triple(BlendMode.MULTIPLY, floatArrayOf(0.335f, 0.23f, 0.2725f, 0.75f), "B = Cs*Cb"),
            //  1.1-0.24, 0.8-0.12, 1.0-0.09  =  0.86, 0.68, 0.91
            Triple(BlendMode.SCREEN, floatArrayOf(0.49f, 0.37f, 0.4775f, 0.75f), "B = Cs+Cb-Cs*Cb"),
            //  cb_r 0.3 <= 0.5 so 2*0.8*0.3; cb_g 0.6 > 0.5 so 1-2*0.8*0.4; cb_b 0.9 so 1-2*0.9*0.1
            Triple(BlendMode.OVERLAY, floatArrayOf(0.395f, 0.29f, 0.455f, 0.75f), "overlay tests the backdrop"),
            //  min(1, 1.1)=1, min(1,0.8)=0.8, min(1,1.0)=1.0 — the red channel is the one that would
            //  have overflowed to 1.1 without the clamp.
            Triple(BlendMode.ADD, floatArrayOf(0.525f, 0.4f, 0.5f, 0.75f), "B = min(1, Cs+Cb)"),
            Triple(BlendMode.DARKEN, floatArrayOf(0.35f, 0.25f, 0.275f, 0.75f), "B = min"),
            Triple(BlendMode.LIGHTEN, floatArrayOf(0.475f, 0.35f, 0.475f, 0.75f), "B = max"),
            //  d * (1 - sa) = d * 0.5, and a = da * 0.5 — NOT the 0.75 every other mode gives.
            Triple(BlendMode.ERASE_BELOW, floatArrayOf(0.075f, 0.15f, 0.225f, 0.25f), "destination-out"),
        )
        val out = FloatArray(4)
        for ((mode, want, note) in table) {
            Blend.apply(mode, s, d, out)
            for (c in 0..3) {
                assertEquals(want[c], out[c], 1e-4f, "$mode ($note) channel $c")
            }
            assertTrue(out[3] > 0f, "$mode keeps a positive alpha")
            for (c in 0..2) {
                assertTrue(out[c] <= out[3] + 1e-4f, "$mode channel $c (${out[c]}) is not above its own alpha")
            }
        }
    }

    /** The named doors must be the same functions as the enum, not eight separate opinions. */
    @Test
    fun theEightNamedFunctionsMatchTheEnum() {
        val s = floatArrayOf(0.4f, 0.1f, 0.05f, 0.5f)
        val d = floatArrayOf(0.15f, 0.3f, 0.45f, 0.5f)
        val byEnum = FloatArray(4)
        val byName = FloatArray(4)
        for (mode in BlendMode.entries) {
            Blend.apply(mode, s, d, byEnum)
            when (mode) {
                BlendMode.NORMAL -> Blend.normal(s, d, byName)
                BlendMode.MULTIPLY -> Blend.multiply(s, d, byName)
                BlendMode.SCREEN -> Blend.screen(s, d, byName)
                BlendMode.OVERLAY -> Blend.overlay(s, d, byName)
                BlendMode.ADD -> Blend.add(s, d, byName)
                BlendMode.DARKEN -> Blend.darken(s, d, byName)
                BlendMode.LIGHTEN -> Blend.lighten(s, d, byName)
                BlendMode.ERASE_BELOW -> Blend.eraseBelow(s, d, byName)
            }
            for (c in 0..3) assertEquals(byEnum[c], byName[c], 0f, "$mode through its named function")
        }
    }

    /** A tie must give the one value both modes share, not "whichever won the comparison". */
    @Test
    fun darkenAndLightenAgreeOnExactlyEqualChannels() {
        val grey = floatArrayOf(0.5019608f, 0.5019608f, 0.5019608f, 1f)
        val out = FloatArray(4)
        val results = listOf(BlendMode.NORMAL, BlendMode.DARKEN, BlendMode.LIGHTEN).map { m ->
            Blend.apply(m, grey, grey, out)
            out.copyOf()
        }
        for (r in results) {
            for (c in 0..3) assertEquals(results[0][c], r[c], 0f, "a tie must not depend on the mode")
        }
        // And compositing a pixel with itself is that pixel, not a brighter one.
        assertEquals(0.5019608f, results[0][0], 1e-6f)
        assertEquals(1f, results[0][3], 1e-6f)
    }

    /** Two near-whites must land on white, not on 1.2 that somebody else has to clamp. */
    @Test
    fun addOnTwoNearWhitePixelsStaysInRange() {
        val s = floatArrayOf(0.98f, 0.98f, 0.98f, 0.98f)
        val d = floatArrayOf(0.98f, 0.98f, 0.98f, 0.98f)
        val out = FloatArray(4)
        for (m in listOf(BlendMode.ADD, BlendMode.NORMAL, BlendMode.SCREEN, BlendMode.MULTIPLY)) {
            Blend.apply(m, s, d, out)
            for (c in 0..2) assertTrue(out[c] <= 1f, "$m channel $c is ${out[c]}")
            for (c in 0..2) assertTrue(out[c] <= out[3] + 1e-5f, "$m channel $c is above its alpha")
            assertTrue(out[3] <= 1f, "$m alpha is ${out[3]}")
        }
        // ADD of two opaques is exactly white.
        val w = floatArrayOf(1f, 1f, 1f, 1f)
        Blend.apply(BlendMode.ADD, w, w, out)
        for (c in 0..3) assertEquals(1f, out[c], 1e-6f, "ADD channel $c")
    }

    /**
     * A blend over a destination that is not there at all. `Cb` is `0/0` here, and NaN would turn
     * every one of these into a garbage pixel instead of a correct one.
     */
    @Test
    fun everyModeOverNothingIsTheSourceItself() {
        val s = floatArrayOf(0.4f, 0.1f, 0.05f, 0.5f)
        val d = FloatArray(4)
        val out = FloatArray(4)
        for (m in BlendMode.entries) {
            Blend.apply(m, s, d, out)
            if (m == BlendMode.ERASE_BELOW) {
                for (c in 0..3) assertEquals(0f, out[c], 0f, "$m over nothing erases nothing")
            } else {
                for (c in 0..3) assertEquals(s[c], out[c], 1e-6f, "$m over nothing, channel $c")
            }
        }
    }

    /** A blend source of nothing must not touch the destination, in any mode. */
    @Test
    fun everyModeWithNoSourceLeavesTheDestinationAlone() {
        val s = floatArrayOf(0f, 0f, 0f, 0f)
        val d = floatArrayOf(0.4f, 0.1f, 0.05f, 0.5f)
        val out = FloatArray(4)
        for (m in BlendMode.entries) {
            Blend.apply(m, s, d, out)
            for (c in 0..3) assertEquals(d[c], out[c], 1e-6f, "$m with no source, channel $c")
        }
    }

    // ── regions ────────────────────────────────────────────────────────────────

    @Test
    fun oneOpaqueRedLayerInsideItsTile() {
        val d = doc(listOf(layer("L1")))
        val tiles = tilesOf(TKey("L1", "L1-cel", 0, 0) to solid(255, 0, 0, 255))
        val rect = RectPx(10, 10, 4, 3)
        val out = RegionRenderer.render(d, tiles, rect, null, null)
        assertEquals(4 * 3 * 4, out.size)
        assertEquals(listOf(255, 0, 0, 255), pixelAt(out, rect, 10, 10))
        assertEquals(listOf(255, 0, 0, 255), pixelAt(out, rect, 13, 12))
        // A hole in the layer is transparent, NOT black: paper is a setting, not a layer.
        val hole = RectPx(600, 600, 2, 2)
        val out2 = RegionRenderer.render(d, tiles, hole, null, null)
        assertEquals(listOf(0, 0, 0, 0), pixelAt(out2, hole, 600, 600))
        assertEquals(listOf(0, 0, 0, 0), pixelAt(out2, hole, 601, 601))
    }

    /**
     * A region crossing a tile seam on both axes, at negative coordinates. 256 is the seam, so
     * x from -260 to -253 straddles tiles -2 and -1.
     */
    @Test
    fun regionStraddlingFourNegativeTiles() {
        val d = doc(listOf(layer("L1")))
        val tiles = tilesOf(
            TKey("L1", "L1-cel", -2, -2) to solid(255, 0, 0, 255),
            TKey("L1", "L1-cel", -1, -2) to solid(0, 255, 0, 255),
            TKey("L1", "L1-cel", -2, -1) to solid(0, 0, 255, 255),
            TKey("L1", "L1-cel", -1, -1) to solid(255, 255, 255, 255),
        )
        val rect = RectPx(-260, -260, 8, 8)
        val out = RegionRenderer.render(d, tiles, rect, null, null)
        assertEquals(listOf(255, 0, 0, 255), pixelAt(out, rect, -260, -260), "top left")
        assertEquals(listOf(255, 0, 0, 255), pixelAt(out, rect, -257, -260), "last column of tile -2")
        assertEquals(listOf(0, 255, 0, 255), pixelAt(out, rect, -256, -260), "first column of tile -1")
        assertEquals(listOf(0, 255, 0, 255), pixelAt(out, rect, -253, -257))
        assertEquals(listOf(0, 0, 255, 255), pixelAt(out, rect, -260, -256), "first row of tile -1")
        assertEquals(listOf(255, 255, 255, 255), pixelAt(out, rect, -256, -256), "the far corner")
        assertEquals(listOf(255, 255, 255, 255), pixelAt(out, rect, -253, -253))
    }

    /** The same shape, but only one of the four tiles exists. */
    @Test
    fun missingTilesAreTransparent() {
        val d = doc(listOf(layer("L1")))
        val tiles = tilesOf(TKey("L1", "L1-cel", -1, -1) to solid(255, 255, 255, 255))
        val rect = RectPx(-260, -260, 8, 8)
        val out = RegionRenderer.render(d, tiles, rect, null, null)
        // Only the 4x4 that falls in tile (-1,-1) has anything in it.
        var painted = 0
        for (y in -260..-253) for (x in -260..-253) {
            val p = pixelAt(out, rect, x, y)
            if (p[3] == 255) painted++
            if (x >= -256 && y >= -256) {
                assertEquals(listOf(255, 255, 255, 255), p, "$x,$y is inside the one tile")
            } else {
                assertEquals(listOf(0, 0, 0, 0), p, "$x,$y is in no tile at all")
            }
        }
        assertEquals(16, painted)
    }

    @Test
    fun paperUnderAHalfAlphaBlackLayer() {
        val d = doc(listOf(layer("L1")))
        val tiles = tilesOf(TKey("L1", "L1-cel", 0, 0) to solid(0, 0, 0, 128))
        val rect = RectPx(0, 0, 2, 2)

        // sa = 128/255, so what shows through is 127/255 — mid grey, and opaque, because paper is
        // opaque underneath it. NOT 128: 1 - 128/255 is 127/255, and rounding the complement is not
        // the same as rounding the alpha.
        val onPaper = RegionRenderer.render(d, tiles, rect, null, "#FFFFFF")
        assertEquals(listOf(127, 127, 127, 255), pixelAt(onPaper, rect, 0, 0))
        assertEquals(listOf(127, 127, 127, 255), pixelAt(onPaper, rect, 1, 1))

        // No paper: the same layer on nothing is black at its own alpha.
        val noPaper = RegionRenderer.render(d, tiles, rect, null, null)
        assertEquals(listOf(0, 0, 0, 128), pixelAt(noPaper, rect, 0, 0))
        assertEquals(listOf(0, 0, 0, 128), pixelAt(noPaper, rect, 1, 1))
    }

    /** A paper colour the renderer cannot read is a caller bug, not a black rectangle. */
    @Test
    fun aPaperColourItCannotReadIsRefused() {
        val d = doc(listOf(layer("L1")))
        val tiles = tilesOf(TKey("L1", "L1-cel", 0, 0) to solid(255, 0, 0, 255))
        for (bad in listOf("FFFFFF", "#FFF", "#GGGGGG", "red", "#FFFFFF00", "")) {
            assertFailsWith<IllegalArgumentException>("paper \"$bad\"") {
                RegionRenderer.render(d, tiles, RectPx(0, 0, 1, 1), null, bad)
            }
        }
        // Lower case is still a colour, and an empty document shows it exactly.
        val rect = RectPx(0, 0, 1, 1)
        val ok = RegionRenderer.render(doc(emptyList()), tiles, rect, null, "#ff8000")
        assertEquals(listOf(255, 128, 0, 255), pixelAt(ok, rect, 0, 0))
    }

    /**
     * Opacity belongs to the WHOLE premultiplied pixel, not just to the alpha.
     *
     * MULTIPLY by white is a no-op at any opacity: the result is the backdrop. Fold the opacity
     * into the alpha alone and the source un-premultiplies to 2.0 — not a colour at all — so this
     * darkens mid grey to 192 instead of leaving it at 128. NORMAL over black or over paper cannot
     * see the difference, which is why this is worth a test of its own.
     */
    @Test
    fun aWhiteLayerAtHalfOpacityMultipliesToTheBackdropUnchanged() {
        val d = doc(listOf(layer("bg"), layer("gloss", blend = BlendMode.MULTIPLY, opacity = 0.5f)))
        val tiles = tilesOf(
            TKey("bg", "bg-cel", 0, 0) to solid(128, 128, 128, 255),
            TKey("gloss", "gloss-cel", 0, 0) to solid(255, 255, 255, 255),
        )
        val rect = RectPx(0, 0, 1, 1)
        val out = RegionRenderer.render(d, tiles, rect, null, null)
        assertEquals(listOf(128, 128, 128, 255), pixelAt(out, rect, 0, 0))
    }

    /** The same layer on nothing: half-transparent white, still perfectly white. */
    @Test
    fun aWhiteLayerAtHalfOpacityOnNothingIsStillWhite() {
        val d = doc(listOf(layer("gloss", blend = BlendMode.MULTIPLY, opacity = 0.5f)))
        val tiles = tilesOf(TKey("gloss", "gloss-cel", 0, 0) to solid(255, 255, 255, 255))
        val rect = RectPx(0, 0, 1, 1)
        val out = RegionRenderer.render(d, tiles, rect, null, null)
        assertEquals(listOf(255, 255, 255, 128), pixelAt(out, rect, 0, 0))
    }

    // ── opacity, visibility, locking ────────────────────────────────────────────

    @Test
    fun layerOpacityHalvesAlphaAndLeavesTheColourAlone() {
        val d = doc(listOf(layer("L1", opacity = 0.5f)))
        val tiles = tilesOf(TKey("L1", "L1-cel", 0, 0) to solid(255, 0, 0, 255))
        val rect = RectPx(0, 0, 1, 1)
        val out = RegionRenderer.render(d, tiles, rect, null, null)
        // Premultiplied 0.5 red at alpha 0.5 un-premultiplies back to full red.
        assertEquals(listOf(255, 0, 0, 128), pixelAt(out, rect, 0, 0))
    }

    @Test
    fun opacityZeroAndOne() {
        val red = tilesOf(TKey("L1", "L1-cel", 0, 0) to solid(255, 0, 0, 255))
        val rect = RectPx(0, 0, 1, 1)
        val none = RegionRenderer.render(doc(listOf(layer("L1", opacity = 0f))), red, rect, null, null)
        assertEquals(listOf(0, 0, 0, 0), pixelAt(none, rect, 0, 0))
        val full = RegionRenderer.render(doc(listOf(layer("L1", opacity = 1f))), red, rect, null, null)
        assertEquals(listOf(255, 0, 0, 255), pixelAt(full, rect, 0, 0))
    }

    /**
     * `coerceIn` lets a NaN straight through, and one NaN would make the whole region NaN rather
     * than invisible. A document that means nothing must render nothing.
     */
    @Test
    fun aMeaninglessOpacityIsClampedRatherThanFollowedIntoNaN() {
        val tiles = tilesOf(TKey("L1", "L1-cel", 0, 0) to solid(255, 0, 0, 255))
        val rect = RectPx(0, 0, 2, 2)
        for (bad in listOf(Float.NaN, -1f)) {
            val out = RegionRenderer.render(doc(listOf(layer("L1", opacity = bad))), tiles, rect, null, null)
            for (b in out) assertTrue((b.toInt() and 0xFF) == 0, "opacity $bad left byte $b")
        }
        // A document that says 200% opaque means 100%, and stays fully opaque.
        val loud = RegionRenderer.render(doc(listOf(layer("L1", opacity = 2f))), tiles, rect, null, null)
        assertEquals(listOf(255, 0, 0, 255), pixelAt(loud, rect, 0, 0))
    }

    @Test
    fun anInvisibleLayerIsIgnored() {
        val d = doc(listOf(layer("L1"), layer("L2", visible = false)))
        val tiles = tilesOf(
            TKey("L1", "L1-cel", 0, 0) to solid(255, 0, 0, 255),
            TKey("L2", "L2-cel", 0, 0) to solid(0, 0, 255, 255),
        )
        val rect = RectPx(0, 0, 1, 1)
        assertEquals(listOf(255, 0, 0, 255), pixelAt(RegionRenderer.render(d, tiles, rect, null, null), rect, 0, 0))
    }

    /** Locking is about editing, not about being visible. A locked layer still exports. */
    @Test
    fun aLockedLayerStillRenders() {
        val d = doc(listOf(layer("L1", locked = true)))
        val tiles = tilesOf(TKey("L1", "L1-cel", 0, 0) to solid(255, 0, 0, 255))
        val rect = RectPx(0, 0, 1, 1)
        assertEquals(listOf(255, 0, 0, 255), pixelAt(RegionRenderer.render(d, tiles, rect, null, null), rect, 0, 0))
    }

    @Test
    fun anEmptyDocumentIsTransparentNotBlack() {
        val rect = RectPx(0, 0, 3, 2)
        val out = RegionRenderer.render(doc(emptyList()), tilesOf(), rect, null, null)
        assertEquals(3 * 2 * 4, out.size)
        for (b in out) assertTrue((b.toInt() and 0xFF) == 0, "an empty document left byte $b")
        // And the same empty document on white paper is white, not transparent.
        val onPaper = RegionRenderer.render(doc(emptyList()), tilesOf(), rect, null, "#FFFFFF")
        assertEquals(listOf(255, 255, 255, 255), pixelAt(onPaper, rect, 2, 1))
    }

    /**
     * Two layers, one cel id, and BOTH layers' pixels present.
     *
     * The cel id is namespaced BY LAYER, not globally: `DocOps.validate` only forbids a repeated
     * cel id within one layer, and `DocModelTest` already uses two layers that both hold a cel
     * called "c". So "the same cel" in two layers is two different pixel stores, which is why
     * `TileSource` is addressed by (layerId, celId) and why this fixture has to supply BOTH keys.
     * Supplying only one is the mistake that made this test vacuous once already.
     */
    @Test
    fun twoLayersShowingTheSameCelBothContribute() {
        val d = doc(listOf(layer("A", cels = listOf("shared")), layer("B", cels = listOf("shared"))))
        val tiles = tilesOf(
            TKey("A", "shared", 0, 0) to solid(128, 0, 0, 128),
            TKey("B", "shared", 0, 0) to solid(128, 0, 0, 128),
        )
        val rect = RectPx(0, 0, 1, 1)
        val out = RegionRenderer.render(d, tiles, rect, null, null)
        // 0.50196 + 0.50196 * (1 - 0.50196) = 0.75196 — a second 50% layer, not a second nothing.
        assertEquals(listOf(255, 0, 0, 192), pixelAt(out, rect, 0, 0))
    }

    /**
     * The guard: two layers sharing a cel id must keep their OWN pixels, and both must be read.
     *
     * The colours differ on purpose. A had (255, 0, 0, 128) only, and B only, are both single-layer
     * answers — so a fixture that quietly collapsed the two onto one key, or a renderer that
     * memoised tiles by cel id and asked only once, would still produce *a* colour here. The only
     * value that proves both were read, in order, is the blend of the two.
     *
     * 50% red then 50% blue: co = (0.25, 0, 0.50196) at a = 0.75196, so the two colour channels
     * come out UNEQUAL (85 and 170). Equal channels would be a weaker witness.
     */
    @Test
    fun twoLayersSharingACelIdEachKeepTheirOwnPixels() {
        val d = doc(listOf(layer("A", cels = listOf("shared")), layer("B", cels = listOf("shared"))))
        val tiles = RecordingTiles(
            mapOf(
                TKey("A", "shared", 0, 0) to solid(128, 0, 0, 128),
                TKey("B", "shared", 0, 0) to solid(0, 0, 128, 128),
            )
        )
        val rect = RectPx(0, 0, 1, 1)
        val out = RegionRenderer.render(d, tiles, rect, null, null)

        // Asked for BY NAME, bottom layer first. A cache keyed on the cel id alone would show one.
        assertEquals(
            listOf(TKey("A", "shared", 0, 0), TKey("B", "shared", 0, 0)),
            tiles.asked,
            "every layer must be asked for its own tile, even under a cel id it shares",
        )
        val p = pixelAt(out, rect, 0, 0)
        assertEquals(listOf(85, 0, 170, 192), p, "50% red under 50% blue")
        assertTrue(p[0] > 0, "layer A's red is missing")
        assertTrue(p[2] > 0, "layer B's blue is missing")
        assertEquals(0, p[1], "green was in neither layer")
    }

    /**
     * A cel id shared by two layers where only ONE has pixels: the other is transparent, and must
     * not borrow its neighbour's tiles.
     */
    @Test
    fun aSharedCelIdDoesNotLetOneLayerBorrowAnothersPixels() {
        val d = doc(listOf(layer("A", cels = listOf("shared")), layer("B", cels = listOf("shared"))))
        val tiles = tilesOf(TKey("A", "shared", 0, 0) to solid(255, 0, 0, 255))
        val rect = RectPx(0, 0, 1, 1)
        // B is empty, so the answer is A's opaque red — NOT red twice, and not green.
        assertEquals(listOf(255, 0, 0, 255), pixelAt(RegionRenderer.render(d, tiles, rect, null, null), rect, 0, 0))
    }

    // ── animation ───────────────────────────────────────────────────────────────

    @Test
    fun anAnimatedLayerShowsTheCelItsFrameMapsTo() {
        val board = animBoard()
        val d = doc(
            listOf(layer("A", cels = listOf("c1", "c2"), animatedIn = "anim", frameCel = mapOf("f1" to "c1", "f2" to "c2"))),
            boards = listOf(board),
        )
        val tiles = tilesOf(
            TKey("A", "c1", 0, 0) to solid(255, 0, 0, 255),
            TKey("A", "c2", 0, 0) to solid(0, 255, 0, 255),
        )
        val rect = RectPx(0, 0, 1, 1)
        val f1 = RegionRenderer.render(d, tiles, rect, "f1", null)
        val f2 = RegionRenderer.render(d, tiles, rect, "f2", null)
        assertEquals(listOf(255, 0, 0, 255), pixelAt(f1, rect, 0, 0), "frame 1")
        assertEquals(listOf(0, 255, 0, 255), pixelAt(f2, rect, 0, 0), "frame 2")
        // A static layer is the held background, and is there on both frames.
        val withStatic = doc(
            listOf(layer("bg"), layer("A", cels = listOf("c1", "c2"), animatedIn = "anim", frameCel = mapOf("f1" to "c1", "f2" to "c2"))),
            boards = listOf(board),
        )
        val both = tilesOf(TKey("bg", "bg-cel", 0, 0) to solid(0, 0, 255, 255))
        val out = RegionRenderer.render(withStatic, both, rect, "f2", null)
        assertEquals(listOf(0, 0, 255, 255), pixelAt(out, rect, 0, 0), "frame 2 keeps its background")
    }

    /** An animated layer asked for a frame it has no mapping for shows nothing, not frame 1. */
    @Test
    fun anAnimatedLayerWithNoCelForTheFrameShowsNothing() {
        val d = doc(
            listOf(layer("A", cels = listOf("c1", "c2"), animatedIn = "anim", frameCel = mapOf("f1" to "c1", "f2" to "c2"))),
            boards = listOf(animBoard()),
        )
        val tiles = tilesOf(TKey("A", "c1", 0, 0) to solid(255, 0, 0, 255))
        val rect = RectPx(0, 0, 1, 1)
        assertEquals(listOf(0, 0, 0, 0), pixelAt(RegionRenderer.render(d, tiles, rect, "f9", null), rect, 0, 0))
        assertEquals(listOf(0, 0, 0, 0), pixelAt(RegionRenderer.render(d, tiles, rect, null, null), rect, 0, 0))
    }

    // ── erase below, through the whole renderer ─────────────────────────────────

    /**
     * The bug this whole file is partly about. An erase takes the destination's ALPHA with it: a
     * 50% eraser over opaque white leaves white at alpha 127, NOT grey at alpha 255. The GPU
     * (`jb_commit.frag`) multiplies the whole vec4 by `1 - a`, so the two must agree.
     */
    @Test
    fun eraseBelowTakesTheDestinationAlphaWithIt() {
        val d = doc(listOf(layer("bg"), layer("erase", blend = BlendMode.ERASE_BELOW)))
        val tiles = tilesOf(
            TKey("bg", "bg-cel", 0, 0) to solid(255, 255, 255, 255),
            TKey("erase", "erase-cel", 0, 0) to solid(255, 0, 0, 128),
        )
        val rect = RectPx(0, 0, 1, 1)
        val out = RegionRenderer.render(d, tiles, rect, null, null)
        assertEquals(listOf(255, 255, 255, 127), pixelAt(out, rect, 0, 0))
    }

    @Test
    fun anOpaqueEraseRemovesTheDestinationEntirely() {
        val d = doc(listOf(layer("bg"), layer("erase", blend = BlendMode.ERASE_BELOW)))
        val tiles = tilesOf(
            TKey("bg", "bg-cel", 0, 0) to solid(255, 255, 255, 255),
            TKey("erase", "erase-cel", 0, 0) to solid(255, 0, 0, 255),
        )
        val rect = RectPx(0, 0, 1, 1)
        assertEquals(listOf(0, 0, 0, 0), pixelAt(RegionRenderer.render(d, tiles, rect, null, null), rect, 0, 0))
    }

    /** Layer opacity counts towards the erase, the same as it counts towards every other blend. */
    @Test
    fun eraseBelowHonoursLayerOpacity() {
        val d = doc(listOf(layer("bg"), layer("erase", blend = BlendMode.ERASE_BELOW, opacity = 0.5f)))
        val tiles = tilesOf(
            TKey("bg", "bg-cel", 0, 0) to solid(255, 255, 255, 255),
            TKey("erase", "erase-cel", 0, 0) to solid(255, 0, 0, 255),
        )
        val rect = RectPx(0, 0, 1, 1)
        // sa = 1 * 0.5, so the destination keeps half of itself: alpha 0.5 -> 128.
        val out = RegionRenderer.render(d, tiles, rect, null, null)
        assertEquals(listOf(255, 255, 255, 128), pixelAt(out, rect, 0, 0))
    }

    /** An eraser on an empty canvas erases nothing — there is nothing under it. */
    @Test
    fun eraseBelowAnEmptyCanvasStaysEmpty() {
        val d = doc(listOf(layer("erase", blend = BlendMode.ERASE_BELOW)))
        val tiles = tilesOf(TKey("erase", "erase-cel", 0, 0) to solid(255, 0, 0, 255))
        val rect = RectPx(0, 0, 1, 1)
        assertEquals(listOf(0, 0, 0, 0), pixelAt(RegionRenderer.render(d, tiles, rect, null, null), rect, 0, 0))
    }

    // ── shapes of region, and refusing nonsense ─────────────────────────────────

    @Test
    fun aRegionOfOnePixelStillWorks() {
        val d = doc(listOf(layer("L1")))
        val tiles = tilesOf(TKey("L1", "L1-cel", -1, -1) to solid(255, 0, 0, 255))
        val rect = RectPx(-256, -256, 1, 1)
        val out = RegionRenderer.render(d, tiles, rect, null, null)
        assertEquals(4, out.size)
        assertEquals(listOf(255, 0, 0, 255), pixelAt(out, rect, -256, -256))
        // The very first pixel of the canvas.
        val origin = RectPx(0, 0, 1, 1)
        val o2 = RegionRenderer.render(d, tilesOf(TKey("L1", "L1-cel", 0, 0) to solid(0, 255, 0, 255)), origin, null, null)
        assertEquals(listOf(0, 255, 0, 255), pixelAt(o2, origin, 0, 0))
    }

    @Test
    fun anEmptyRegionIsAnEmptyImage() {
        val d = doc(listOf(layer("L1")))
        val tiles = tilesOf(TKey("L1", "L1-cel", 0, 0) to solid(255, 0, 0, 255))
        assertEquals(0, RegionRenderer.render(d, tiles, RectPx(0, 0, 0, 5), null, null).size)
        assertEquals(0, RegionRenderer.render(d, tiles, RectPx(0, 0, 5, 0), null, null).size)
        assertEquals(0, RegionRenderer.renderPremultiplied(d, tiles, RectPx(0, 0, 0, 0), null, null).size)
    }

    @Test
    fun aRegionOfNoSizeInTheOtherDirectionIsRefused() {
        val d = doc(listOf(layer("L1")))
        val tiles = tilesOf()
        for (r in listOf(RectPx(0, 0, -1, 5), RectPx(0, 0, 5, -1))) {
            assertFailsWith<IllegalArgumentException>("$r") { RegionRenderer.render(d, tiles, r, null, null) }
        }
    }

    /** A TileSource that hands back the wrong number of bytes is a storage bug, not a short read. */
    @Test
    fun aTileOfTheWrongSizeIsRefused() {
        val d = doc(listOf(layer("L1")))
        val short = tilesOf(TKey("L1", "L1-cel", 0, 0) to ByteArray(16))
        assertFailsWith<IllegalArgumentException> {
            RegionRenderer.render(d, short, RectPx(0, 0, 1, 1), null, null)
        }
    }

    /**
     * Premultiplied in, premultiplied out — and the two forms are NOT the same numbers.
     *
     * A 50% red pixel is 0.50196 premultiplied, and 1.0 straight. This is the whole of bug 2: a
     * renderer that blended correctly and then handed the PREMULTIPLIED bytes to an exporter would
     * ship a red of 128 where the GPU shows 255, and it would look like the colours were slightly
     * off rather than like a bug.
     */
    @Test
    fun thePremultipliedFormIsTheSamePictureInDifferentNumbers() {
        val d = doc(listOf(layer("L1")))
        val tiles = tilesOf(TKey("L1", "L1-cel", 0, 0) to solid(128, 0, 0, 128))
        val rect = RectPx(0, 0, 1, 1)

        val p = RegionRenderer.renderPremultiplied(d, tiles, rect, null, null)
        assertEquals(4, p.size)
        assertEquals(0.5019608f, p[0], 1e-5f, "premultiplied red is 128/255, not 1")
        assertEquals(0.5019608f, p[3], 1e-5f, "alpha is 128/255, not 1")
        assertEquals(0f, p[1], 0f)
        assertEquals(0f, p[2], 0f)

        // The byte form un-premultiplies it back to full red at half alpha.
        assertEquals(listOf(255, 0, 0, 128), pixelAt(RegionRenderer.render(d, tiles, rect, null, null), rect, 0, 0))
    }

    /** Over paper, the alpha is 1 and the two forms happen to agree — a useful second opinion. */
    @Test
    fun overPaperTheTwoFormsAgree() {
        val d = doc(listOf(layer("L1")))
        val tiles = tilesOf(TKey("L1", "L1-cel", 0, 0) to solid(128, 0, 0, 128))
        val rect = RectPx(0, 0, 1, 1)
        val p = RegionRenderer.renderPremultiplied(d, tiles, rect, null, "#0000FF")
        // co = sa(1-da)Cs + sa*da*Cs + (1-sa)da*Cb, with Cs = red and Cb = blue.
        assertEquals(0.5019608f, p[0], 1e-5f)
        assertEquals(0f, p[1], 1e-6f)
        assertEquals(0.4980392f, p[2], 1e-5f)
        assertEquals(1f, p[3], 1e-6f)
        assertEquals(
            listOf(128, 0, 127, 255),
            pixelAt(RegionRenderer.render(d, tiles, rect, null, "#0000FF"), rect, 0, 0),
        )
    }

    // ── the size budget: a declared size is a wish ─────────────────────────────

    /*
     * Every export goes through RegionRenderer, so a rect it cannot allocate is a rect no exporter
     * can draw. The budget is MAX_REGION_PX; these tests pin it from both sides, pin the two ways it
     * used to fail (the Int wrap and the plain out-of-memory), and pin the mismatch that made this a
     * bug at all: `DocOps.validate` passes a document this renderer then refuses.
     */

    /**
     * THE BOUNDARY from the allowed side: exactly [MAX_REGION_PX] pixels must render.
     *
     * This is the test that says the guard is not off by one, and it is the only expensive test in
     * this file. 4096 x 2048 IS the budget, so `render` really does allocate 160 MiB — 32 MiB of
     * result and 128 MiB of float scratch — and then walk 8.4 million pixels. That is deliberate:
     * the constant's claim is "160 MiB is affordable", and the only honest way to check a claim
     * about an allocation is to make the allocation. The document is empty, so allocating it is the
     * only work done.
     */
    @Test
    fun aRegionExactlyOnTheBudgetIsRendered() {
        val rect = RectPx(0, 0, 4096, 2048)
        assertEquals(MAX_REGION_PX, rect.w.toLong() * rect.h.toLong(), "the fixture IS the budget")
        val out = RegionRenderer.render(doc(emptyList()), tilesOf(), rect, null, null)
        assertEquals(MAX_REGION_PX * 4, out.size.toLong())
        assertEquals(listOf(0, 0, 0, 0), pixelAt(out, rect, 0, 0), "first pixel")
        assertEquals(listOf(0, 0, 0, 0), pixelAt(out, rect, 4095, 2047), "last pixel")
    }

    /**
     * THE BOUNDARY from the refused side: one row over must be refused, and the message must say how
     * many pixels it wanted. A refusal nobody can act on is one they report as "export just failed",
     * which is how a 160 MiB budget becomes a bug report instead of a sentence.
     *
     * Both doors are checked. `renderPremultiplied` allocates from the same rect, so a guard on one
     * door and not the other would be a guard that can be walked around.
     */
    @Test
    fun aRegionOneRowOverTheBudgetIsRefusedAndSaysWhatItWanted() {
        val rect = RectPx(0, 0, 4096, 2049)
        val wanted = 4096L * 2049
        assertEquals(MAX_REGION_PX + 4096, wanted, "one row of 4096 px past the fixture above")

        val fromRender = assertFailsWith<RegionException>("render") {
            RegionRenderer.render(doc(emptyList()), tilesOf(), rect, null, null)
        }
        val fromScratch = assertFailsWith<RegionException>("renderPremultiplied") {
            RegionRenderer.renderPremultiplied(doc(emptyList()), tilesOf(), rect, null, null)
        }
        val m = fromRender.message ?: ""
        assertTrue(m.contains("$wanted"), "the message names the pixel count it wanted: $m")
        assertTrue(m.contains("$MAX_REGION_PX"), "the message names the budget: $m")
        assertTrue(m.contains("4096") && m.contains("2049"), "the message names the rect: $m")
        assertEquals(m, fromScratch.message, "both doors refuse in the same words")
    }

    /**
     * THE CRASH — the Int wrap, with the multiplication written out so this is a claim about
     * arithmetic rather than about taste.
     *
     * 30,000 x 30,000 is 900,000,000 px, which is fine. x 4 is 3,600,000,000, which is not: an Int
     * holds to 2,147,483,647, and two's complement puts 3,600,000,000 at -694,967,296, so the
     * unguarded `ByteArray` answered `NegativeArraySizeException`. The thing being asserted is
     * therefore not the message but the TYPE: a different exception has to come out. This test
     * cannot pass by accident, because the crash it replaces was not a [RegionException].
     */
    @Test
    fun aRegionWhoseByteCountWrapsAnIntIsRefusedRatherThanCrashing() {
        val real = 30000L * 30000L * 4L
        assertEquals(900_000_000L, 30000L * 30000L, "the pixel count itself is not the problem")
        assertEquals(3_600_000_000L, real, "the byte count the renderer used to ask for")
        assertTrue(real > Int.MAX_VALUE, "3.6 GB does not fit an Int")
        assertEquals(-694967296L, real - 4294967296L, "and an Int wraps it to exactly this")

        val e = assertFailsWith<RegionException> {
            RegionRenderer.render(doc(emptyList()), tilesOf(), RectPx(0, 0, 30000, 30000), null, null)
        }
        assertTrue((e.message ?: "").contains("900000000"), "names the pixel count: ${e.message}")
    }

    /**
     * THE OUT OF MEMORY — the case the wrap test cannot reach, and the one that would have taken the
     * phone down rather than the build.
     *
     * Nothing here overflows: 400,000,000 px is a legal pixel count, and 1,600,000,000 bytes is
     * still a legal Int array length. The renderer simply asks for 6.4 GB of floats and the device
     * says no. So `assertFailsWith<RegionException>` IS the assertion — an [OutOfMemoryError] is not
     * a [RegionException], and before the budget this test took the test process with it instead of
     * failing tidily.
     */
    @Test
    fun aRegionThatOverflowsNothingAndStillCannotBeAllocatedIsAlsoRefused() {
        val px = 20000L * 20000L
        assertEquals(400_000_000L, px, "no wrap anywhere in here — that is the whole point")
        assertTrue(px * 4 <= Int.MAX_VALUE, "a 1.6 GB result is still a legal Int length")
        assertTrue(px * 16 > Int.MAX_VALUE, "and 6.4 GB of floats is not")

        val e = assertFailsWith<RegionException> {
            RegionRenderer.render(doc(emptyList()), tilesOf(), RectPx(0, 0, 20000, 20000), null, null)
        }
        assertTrue((e.message ?: "").contains("400000000"), "names the pixel count: ${e.message}")
    }

    /**
     * THE MISMATCH, which is the actual bug rather than a symptom of it: the document validator says
     * this drawing is fine and the renderer says it cannot be drawn. Both are right, and the point of
     * the test is that they are NOT the same check — a budget that merely repeated
     * `DocOps.validate` would be useless here, because validate passes this document.
     *
     * The rect is the BOARD's, which is how an exporter asks for a whole board. So a hostile file
     * does not have to be corrupt at all: it only has to declare a large board.
     */
    @Test
    fun aRegionDocOpsValidatesIsStillRefusedHere() {
        for (side in listOf(30000, 20000, 4096)) {
            val board = Board("big", "Big", BoardKind.CANVAS, RectPx(0, 0, side, side))
            val d = doc(listOf(layer("L1")), boards = listOf(board))
            assertEquals(emptyList<String>(), DocOps.validate(d), "validate accepts a $side-square board")
            assertFailsWith<RegionException>("$side-square board") {
                RegionRenderer.render(d, tilesOf(), board.rect, null, null)
            }
        }
    }

    /**
     * The budget's arithmetic, checked without allocating 160 MiB: the result, the scratch, the peak,
     * and — the one that is easy to forget — that every one of them still fits an [Int], because
     * every one is a length AND an index. Raise the constant past that and this goes red, which is
     * the point: a memory problem would become an index wrap, and a wrap is a crash.
     */
    @Test
    fun theBudgetBoundsEveryBufferDerivedFromTheRect() {
        assertEquals(8_388_608L, MAX_REGION_PX, "the cap is 2^23")
        assertEquals(33_554_432L, MAX_REGION_PX * 4, "the result ByteArray: px x 4")
        assertEquals(134_217_728L, MAX_REGION_PX * 16, "the scratch FloatArray: px x 4ch x 4B")
        assertEquals(167_772_160L, MAX_REGION_PX * 20, "the live peak: px x 20B")
        assertEquals(0L, MAX_REGION_PX * 20 % (1024 * 1024), "which is 160 MiB exactly, as the kdoc says")
        assertTrue(MAX_REGION_PX * 4 <= Int.MAX_VALUE, "the result is indexed with an Int")
        assertTrue(MAX_REGION_PX * 16 <= Int.MAX_VALUE, "and so is the scratch")
        assertTrue(MAX_REGION_PX * 20 <= 256L * 1024 * 1024, "and the peak fits a Note 9's app heap")
    }

    /**
     * The other half of the constant's promise: it must not refuse an export anyone actually wants.
     *
     * 3840 x 2160 is 8,294,400 px, 94,208 under the cap, so a 4K board — the largest thing this app
     * is realistically asked to export — goes through untouched. If this test ever fails, the budget
     * has been lowered into a regression. The 4096-square on the other side is 2^24, the first round
     * number over the line, and it is refused.
     */
    @Test
    fun theBudgetLeavesA4KExportAloneAndRefusesTheFirstSizeOverIt() {
        val d = doc(emptyList())
        assertEquals(8_294_400L, 3840L * 2160L, "a 4K frame")
        assertTrue(3840L * 2160L <= MAX_REGION_PX, "4K is inside the budget")
        assertEquals(16_777_216L, 4096L * 4096L, "2^24 is the first power of two over it")
        assertFailsWith<RegionException>("4096 square") {
            RegionRenderer.render(d, tilesOf(), RectPx(0, 0, 4096, 4096), null, null)
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private data class TKey(val layer: String, val cel: String, val tx: Int, val ty: Int)

    private class FakeTiles(private val map: Map<TKey, ByteArray>) : TileSource {
        override fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? =
            map[TKey(layerId, celId, tx, ty)]
    }

    /**
     * A [TileSource] that remembers every key it was asked for, so a test can prove the renderer
     * looked in BOTH layers rather than deduplicating two layers that share a cel id.
     */
    private class RecordingTiles(private val map: Map<TKey, ByteArray>) : TileSource {
        val asked = ArrayList<TKey>()
        override fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? {
            val key = TKey(layerId, celId, tx, ty)
            asked += key
            return map[key]
        }
    }

    private fun tilesOf(vararg pairs: Pair<TKey, ByteArray>): TileSource = FakeTiles(mapOf(*pairs))

    /** A whole tile of one premultiplied colour. */
    private fun solid(r: Int, g: Int, b: Int, a: Int): ByteArray {
        val t = ByteArray(RegionRenderer.TILE_BYTES)
        var i = 0
        while (i < t.size) {
            t[i] = r.toByte()
            t[i + 1] = g.toByte()
            t[i + 2] = b.toByte()
            t[i + 3] = a.toByte()
            i += 4
        }
        return t
    }

    private fun layer(
        id: String,
        cels: List<String> = listOf("$id-cel"),
        visible: Boolean = true,
        locked: Boolean = false,
        opacity: Float = 1f,
        blend: BlendMode = BlendMode.NORMAL,
        animatedIn: String? = null,
        frameCel: Map<String, String> = emptyMap(),
    ) = Layer(
        id = id,
        name = id,
        kind = LayerKind.PAINT,
        visible = visible,
        locked = locked,
        opacity = opacity,
        blend = blend,
        animatedIn = animatedIn,
        cels = cels.map { Cel(it) },
        frameCel = frameCel,
    )

    private fun canvasBoard() = Board("canvas", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 64, 64))

    private fun animBoard() = Board(
        id = "anim",
        name = "Anim",
        kind = BoardKind.ANIMATION,
        rect = RectPx(0, 0, 64, 64),
        frames = listOf(Frame("f1"), Frame("f2")),
    )

    private fun doc(layers: List<Layer>, boards: List<Board> = listOf(canvasBoard())) =
        JbDocument(id = "d", name = "D", boards = boards, layers = layers)

    /** One pixel of the rendered region as r, g, b, a. */
    private fun pixelAt(out: ByteArray, rect: RectPx, x: Int, y: Int): List<Int> {
        val i = ((y - rect.y) * rect.w + (x - rect.x)) * 4
        return listOf(
            out[i].toInt() and 0xFF,
            out[i + 1].toInt() and 0xFF,
            out[i + 2].toInt() and 0xFF,
            out[i + 3].toInt() and 0xFF,
        )
    }
}
