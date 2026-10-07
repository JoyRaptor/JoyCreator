package cc.joycreator.joybrush.core.doc

import cc.joycreator.joybrush.core.brush.BRUSH_VERSION
import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.VERSION_MEDIA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The serialised enums, pinned. JB-0.02b, for Lead ruling R3.
 *
 * R3 is the decision that an unknown enum VALUE is not a bug to be smoothed over: adding a constant
 * to any of these enums requires bumping that file's version, so an older app refuses the newer file
 * in words ("from a newer Joy Brush") instead of quietly turning a kind it does not have into one
 * it does. These tests pin both halves of that promise:
 *
 *  - the exact name lists, in order, because the NAME is the on-disk token and a rename or a
 *    delete breaks every file somebody has already saved;
 *  - the versions those lists were written for, so adding a constant has to be a deliberate act;
 *  - and that an unknown constant is REFUSED, never coerced, which is the behaviour R3 promises and
 *    the one a well-meaning `coerceInputValues = true` would quietly break.
 *
 * What this file cannot do: check that a human bumped the version, or check the KDoc that tells
 * them to. A comment is not testable from a common source set (there is no file system in common
 * code), so the rule is pinned by making the change conspicuous, not by making it impossible. See
 * the Questions section of tasks/joybrush/specs/JB-0.02b_enum_version_rule.md.
 */
class EnumFreezeTest {

    // ---------- the on-disk token lists, in order ----------

    @Test
    fun boardKindNamesAreFrozenInOrder() {
        assertEquals(
            listOf("CANVAS", "ANIMATION", "SPRITE", "PUPPET", "CHARACTER"),
            BoardKind.entries.map { it.name },
            "document.json stores these names, so a rename or a deletion breaks saved files. R3: append only, with a version bump.",
        )
    }

    @Test
    fun layerKindNamesAreFrozenInOrder() {
        assertEquals(
            listOf("PAINT", "INK", "MEDIA"),
            LayerKind.entries.map { it.name },
            "the on-disk layer kinds; MEDIA (v8) was appended with a version bump. Append only.",
        )
    }

    @Test
    fun blendModeNamesAreFrozenInOrder() {
        assertEquals(
            listOf(
                // Joy Brush's own eight.
                "NORMAL", "MULTIPLY", "SCREEN", "OVERLAY", "ADD", "DARKEN", "LIGHTEN", "ERASE_BELOW",
                // The Studio's, in `BlendModes.ALL` order (R23, JB-2.20a). NOT "so ordinal ==
                // modeCode" — ERASE_BELOW is Joy Brush's own insertion and shifts everything after
                // it. The parity test walks by Studio code and looks modes up by NAME, so it does not
                // depend on this order; what the order buys is that a human comparing the two lists
                // finds the same sequence. (The false ordinal claim here was filed as a MAJOR.)
                "DIFFERENCE", "COLOR", "COLOR_DODGE", "COLOR_BURN", "LINEAR_BURN",
                "HARD_LIGHT", "SOFT_LIGHT", "VIVID_LIGHT", "LINEAR_LIGHT", "PIN_LIGHT", "HARD_MIX",
                "EXCLUSION", "SUBTRACT", "DIVIDE", "DARKER_COLOR", "LIGHTER_COLOR",
                "HUE", "SATURATION", "LUMINOSITY",
            ),
            BlendMode.entries.map { it.name },
            "a name here is one a saved file may already hold, and the renderer is expected to honour. " +
                "27 entries where the Studio has 26, because ERASE_BELOW is Joy Brush's own. The " +
                "generator in tools/blend-golden asserts the Studio's 26 land on their own modeCode.",
        )
    }

    @Test
    fun brushInputNamesAreFrozenInOrder() {
        assertEquals(
            listOf("pressure", "tilt", "speed", "direction", "lean", "attack", "distance", "random", "strokeRandom", "barrel"),
            BrushInput.entries.map { it.name },
            "brush.json stores these names. Substituting one for another changes how every stroke draws.",
        )
    }

    /**
     * The versions the lists above belong to. A bump here is a legal, deliberate act — it is the
     * bump R3 asks for — so this test does not forbid it. It makes it a choice somebody makes on
     * purpose, next to a red test that names the four enums.
     */
    @Test
    fun theVersionsTheNamesWereWrittenFor() {
        // 4 is the paper look/surface (JB-9.05): `Paper` gained lookId, tint, show, bite and light.
        // The bump is R31's — any new serialised field bumps — and the Lead accepted its cost (an old
        // build refuses a v4 file) for one owner and one phone (R38).
        // 8 is the media layer (MEDIA_ENGINE_PLAN §4): LayerKind.MEDIA and Cel.floatTiles.
        assertEquals(8, DOC_VERSION)
        // 6 is `paper` (JB-9.09): the three numbers each brush carries about how it feels the document
        // paper. Read as BRUSH_VERSION rather than typed, so the bump is one edit here and one there.
        // 8 is `engine: "media"` and its `media` section (MEDIA_ENGINE_PLAN §4).
        assertEquals(8, BRUSH_VERSION)
        assertEquals(VERSION_MEDIA, BRUSH_VERSION, "the newest version is the one this row added")
        // BrushPreset.version is a second literal beside BRUSH_VERSION, and the file writes the
        // default — if they drift, every new brush is stamped with a version nobody validates.
        assertEquals(BRUSH_VERSION, BrushPreset(id = "b", name = "B", size = Param(1f)).version)
    }

    // ---------- the NAME is the token, or the KDoc on each enum is a lie ----------

    @Test
    fun theNamesAreWhatActuallyGoIntoTheFile() {
        val doc = documentUsingEveryConstant()
        val text = DocJson.encode(doc)
        for (name in BoardKind.entries.map { it.name }) {
            assertTrue(text.contains("\"kind\": \"$name\""), "no \"$name\" token in the file:\n$text")
        }
        for (name in LayerKind.entries.map { it.name }) {
            assertTrue(text.contains("\"kind\": \"$name\""), "no \"$name\" token in the file:\n$text")
        }
        for (name in BlendMode.entries.map { it.name }) {
            assertTrue(text.contains("\"blend\": \"$name\""), "no \"$name\" token in the file:\n$text")
        }
        assertEquals(doc, DocJson.decode(text), "and every one of them must read back as itself")
    }

    /** One board per board kind, and one layer per (layer kind x blend) pair. */
    private fun documentUsingEveryConstant(): JbDocument {
        val boards = BoardKind.entries.map { kind ->
            Board(
                id = "b-${kind.name}", name = kind.name, kind = kind,
                rect = RectPx(0, 0, 8, 8),
                grid = if (kind == BoardKind.SPRITE) SpriteGrid(1, 1, 8, 8) else null,
            )
        }
        val layers = LayerKind.entries.flatMap { kind ->
            BlendMode.entries.map { blend ->
                val id = "l-${kind.name}-${blend.name}"
                Layer(id = id, name = id, kind = kind, blend = blend, cels = listOf(Cel("c-$id")))
            }
        }
        return JbDocument(
            id = "d", name = "Every constant",
            boards = boards, layers = layers,
        )
    }

    // ---------- an unknown constant is refused, never coerced ----------

    /**
     * A document.json whose only free variable is the enum tokens, so the test below can be sure it
     * is the constant that fails and not the shape of the file.
     */
    private fun documentWith(
        boardKind: String = "CANVAS",
        layerKind: String = "PAINT",
        blend: String = "NORMAL",
    ): String = """
        {
          "format": "joybrush.document",
          "version": 1,
          "id": "d",
          "name": "D",
          "boards": [
            {
              "id": "b",
              "name": "B",
              "kind": "$boardKind",
              "rect": { "x": 0, "y": 0, "w": 8, "h": 8 }
            }
          ],
          "layers": [
            {
              "id": "l",
              "name": "L",
              "kind": "$layerKind",
              "blend": "$blend",
              "cels": [ { "id": "c" } ]
            }
          ]
        }
    """.trimIndent()

    /**
     * The three throw tests below all use a token Joy Brush will never actually ship a constant
     * called. That is deliberate: if one of them ever became a real constant the "unknown" case
     * would stop being unknown and the test would fail for a reason that has nothing to do with
     * freezing, so the token would have to be repointed at that moment.
     */

    @Test
    fun anUnknownBoardKindIsRefusedRatherThanGuessed() {
        // The control: this exact file with a kind this build has must decode. Without it, the test
        // below could pass because the JSON was broken all along.
        assertEquals(BoardKind.CANVAS, DocJson.decode(documentWith()).boards.single().kind)

        assertFailsWith<DocException> { DocJson.decode(documentWith(boardKind = "HOLOGRAM")) }
    }

    @Test
    fun anUnknownLayerKindIsRefusedRatherThanGuessed() {
        assertEquals(LayerKind.PAINT, DocJson.decode(documentWith()).layers.single().kind)

        // Guessing here would be the worst case of the three: the wrong kind means the wrong
        // storage for the pixels, and a re-save writes the wrong kind back.
        assertFailsWith<DocException> { DocJson.decode(documentWith(layerKind = "TELEPATHY")) }
    }

    @Test
    fun anUnknownBlendModeIsRefusedRatherThanGuessed() {
        assertEquals(BlendMode.NORMAL, DocJson.decode(documentWith()).layers.single().blend)

        // NORMAL is the fallback a coerce would pick, and a layer quietly composited as NORMAL is a
        // painting that has changed without anybody being told.
        assertFailsWith<DocException> { DocJson.decode(documentWith(blend = "HOLOGRAM")) }
    }

    /** brush.json, with the one input token as the only free variable. */
    private fun brushWithInput(input: String): String = """
        {
          "format": "joybrush.brush",
          "version": 1,
          "id": "b",
          "name": "B",
          "size": { "base": 10.0 },
          "tip": {
            "angle": {
              "base": 0.0,
              "inputs": [ { "input": "$input", "curve": [ [ 0.0, 1.0 ] ] } ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun anUnknownBrushInputIsRefusedRatherThanSwappedForAnother() {
        val known = BrushJson.decode(brushWithInput("pressure"))
        assertEquals(BrushInput.pressure, known.tip.angle.inputs.single().input, "the control case")

        assertFailsWith<BrushException> { BrushJson.decode(brushWithInput("telepathy")) }
    }
}
