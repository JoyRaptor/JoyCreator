package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushValidate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Krita importer: every bucket, and the R40 rules in particular.
 *
 * **What this test cannot do, said out loud.** The spec asks for real `.kpp` files pasted as base64 —
 * David Revoy's `deevad-krita-brushpresets` (CC-BY 4.0) is the pack to ship. This build has no `.kpp` to
 * paste: there is none in the repository and the sandbox cannot fetch a binary. So every fixture here is
 * **hand-built** — a PNG written chunk by chunk, a zip written entry by entry, an XML document written
 * element by element — in the shapes R4 §B.3 and Krita's `KoPaintOpSettings::savePreset` describe. That
 * proves the reader against the *format*; it does not prove it against a file Krita wrote, and that gap is
 * in the report rather than hidden here.
 *
 * Every expected number carries its arithmetic in the comment that asserts it: `rx − ry = 1.0 − 0.6` for an
 * aspect, the point with the greatest `x` for a `size.base`, `ceil(200 000 / 3) × 4 = 266 668` for a base64
 * length, `<paintop>` = depth 1 so 64 nested `<a>` = depth 65 for the XML depth cap.
 *
 * **No `java.*` anywhere**, which is why the zip and the CRC-32 are written out longhand.
 */
class KritaImportTest {

    // ---- 8. a whole bundle, end to end -----------------------------------------------------------------

    /**
     * A three-brush bundle in one `convertBundle` call.
     *
     * Non-vacuity in two places: the count is **3** and not "more than zero", and the three **Krita names** —
     * which are the entry file names, because a `.kpp` carries none of its own — appear in the converted
     * names, in entry-name order.
     */
    @Test
    fun aWholeBundleConvertsAndEveryPresetIsALegalBrush() {
        val library = KritaImport.convertBundle(
            bundleOf(
                ZipEntry("brushes/Ink Default.kpp", ZIP_STORED, kpp(pixel(ink()))),
                ZipEntry("brushes/Ink Splatter.kpp", ZIP_STORED, kpp(pixel(scatter()))),
                ZipEntry("paintoppresets/Flat Blitter.kpp", ZIP_STORED, kpp(pixel(blitter()))),
            ),
            "Revoy Extras",
        )
        assertEquals(0, library.refused.size, "${library.refused}")
        // The order is by entry name: "brushes/ink default.kpp" < "brushes/ink splatter.kpp" <
        // "paintoppresets/flat blitter.kpp", so the brushes come before the paint-op presets.
        assertEquals(
            listOf("Ink Default", "Ink Splatter", "Flat Blitter"),
            library.brushes.map { it.preset.name },
        )
        for (result in library.brushes) {
            val p = result.preset
            assertEquals("kpp", p.sourceFormat, p.name)
            // Decision 14: a bundle with no `meta.xml` states no licence, and **"CC0"** — `BrushPreset`'s
            // own default — would be a claim about somebody else's pack that nobody in this file made.
            assertEquals("unknown", p.license, p.name)
            assertEquals("", p.author, p.name)
            assertEquals("stamp", p.engine, p.name)
            assertEquals(brushId("Revoy Extras", p.name), p.id, p.name)
            assertEquals(emptyList(), BrushValidate.validate(p), "${p.name}: ${result.warnings}")
        }
        assertTrue(library.summary().isNotBlank())
    }

    /**
     * Decision 14, R40's third point, with the two strings this row is told to carry: the licence and the
     * author come from the bundle's own `meta.xml` and are **never assumed**. A bare `.kpp` has no
     * `meta.xml` at all, so both fall to the honest values.
     */
    @Test
    fun licenceAndAuthorAreKeptFromTheBundleAndNeverAssumed() {
        val meta = """
            <?xml version="1.0" encoding="UTF-8"?>
            <office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0">
              <office:meta>
                <dc:title>Revoy Extras</dc:title>
                <dc:creator>David Revoy</dc:creator>
                <dc:rights>CC-BY 4.0</dc:rights>
              </office:meta>
            </office:document-meta>
        """.trimIndent()
        val stated = KritaImport.convertBundle(
            bundleOf(
                ZipEntry("meta.xml", ZIP_STORED, meta.toByteArray()),
                ZipEntry("brushes/Ink Default.kpp", ZIP_STORED, kpp(pixel(ink()))),
            ),
            "Revoy Extras",
        ).brushes.single().preset
        assertEquals("CC-BY 4.0", stated.license)
        assertEquals("David Revoy", stated.author)

        // The same brush with no `meta.xml` says "unknown" and "", which is what a sheet can filter on.
        val silent = KritaImport.convertKpp(kpp(pixel(ink()), title = "Ink Default"), "kpp")
            .brushes.single().preset
        assertEquals("unknown", silent.license)
        assertEquals("", silent.author)
        assertFalse(silent.license == "CC0", "never CC0 by default (Decision 14)")
    }

    // ---- 9. THE ENGINE GATE, one case per refused id ---------------------------------------------------

    /**
     * **This is the row's headline assertion** (Decision 1). Twelve Krita engines that are not `Pixel` and
     * not `ColorSmudge`, each refused with **that literal `paintopid` in the sentence** — because the id is
     * what a person sees in Krita's own brush editor, and a message that says only "unsupported" is not a
     * sentence anybody can act on.
     *
     * **Non-vacuity in the same test:** `Pixel` and `ColorSmudge` out of the *same fixture shape* are **not**
     * refused. Without that half, an importer that refused everything would pass.
     */
    @Test
    fun everyEngineThatIsNotPixelOrColorSmudgeIsRefusedWithItsOwnPaintopid() {
        val refused = listOf(
            "Hairy", "Bristle", "Sketch", "Deform", "Particle", "Curve", "Hatching",
            "Grid", "Shape", "Filter", "TangentNormal", "Quick", "Spray",
        )
        for (engine in refused) {
            val library = KritaImport.convertKpp(kpp(pixel(ink(), engine = engine)), "kpp")
            assertEquals(0, library.brushes.size, "$engine converted: ${library.brushes}")
            assertEquals(1, library.refused.size, "$engine: ${library.refused}")
            val why = library.refused.single().reason
            assertTrue(why.contains(engine), "$engine was refused without naming itself: $why")
            assertTrue(why.contains("paintopid"), "$engine: $why")
        }
        for (engine in listOf("Pixel", "ColorSmudge")) {
            val library = KritaImport.convertKpp(kpp(pixel(ink(), engine = engine)), "kpp")
            assertEquals(0, library.refused.size, "$engine: ${library.refused}")
            assertEquals(1, library.brushes.size, "$engine was refused: ${library.refused}")
        }
    }

    // ---- 9b. THE BOTH-TILTS WARNING (Decision 2b, R40) -------------------------------------------------

    /**
     * **The assertion is on *absence* as well as presence**, and the absence is the load-bearing half: a
     * warning that fires whenever any tilt exists says nothing at all, and R40 is about telling a person
     * something true.
     */
    @Test
    fun aBrushWithBothTiltsSaysSoAndASingleTiltDoesNot() {
        val both = single(pixel(ink(), curves = tilts("xtilt", "declination")))
        assertTrue(
            both.warnings.any { it.contains(KritaImport.BOTH_TILTS) },
            "no both-tilts warning: ${both.warnings}",
        )
        assertTrue(
            both.warnings.any { it.contains("two tilt sensors") },
            "the warning must name the condition: ${both.warnings}",
        )
        // Decision 2b: **both** raw values survive, whichever one is applied.
        assertNotNull(both.preset.extensions["krita.xtilt"], "${both.preset.extensions.keys}")
        assertNotNull(both.preset.extensions["krita.declination"], "${both.preset.extensions.keys}")
        // …and the second is the one that is not drawn: exactly one tilt input on the size param, and it is
        // xtilt's — "0,0.5;1,20", whose last point is (1, 20).
        val tiltsOnSize = both.preset.size.inputs.filter { it.input == BrushInput.tilt }
        assertEquals(1, tiltsOnSize.size, "${both.preset.size.inputs}")
        assertContentEquals(
            listOf(1f, 20f),
            tiltsOnSize.single().curve.last(),
            "the xtilt curve is the one that is applied",
        )

        for (only in listOf("xtilt", "declination")) {
            val one = single(pixel(ink(), curves = tilts(only)))
            assertFalse(
                one.warnings.any { it.contains("two tilt sensors") },
                "a brush with only $only must not carry the both-tilts warning: ${one.warnings}",
            )
        }
    }

    // ---- 10. a missing engine refuses, and is never defaulted -----------------------------------------

    @Test
    fun aMissingEngineRefusesAndIsNeverDefaultedToPixel() {
        val library = KritaImport.convertKpp(
            kpp("""<paintop><brush_definition type="auto_mask" name="start"/></paintop>"""),
            "kpp",
        )
        assertEquals(0, library.brushes.size, "${library.brushes}")
        assertEquals(1, library.refused.size)
        val why = library.refused.single().reason
        assertTrue(why.contains("paintop"), why)
        assertTrue(why.contains("id"), why)
    }

    // ---- 11. sensors map, and unmapped ones are loud --------------------------------------------------

    /**
     * Decision 2, with the numbers derived in the fixture's own comments.
     *
     * `ytilt` is the important half: it warns **and does not become a second tilt**, so the opacity param
     * has exactly one input.
     */
    @Test
    fun mappedSensorsBecomeInputsAndUnmappedOnesAreKeptRawAndNamed() {
        val p = single(
            pixel(
                ink(),
                curves = """
                    <curve sensor="pressure" name="sizes" commonCurve="0,0.2;1,40"/>
                    <curve sensor="pressure" name="opacity" commonCurve="0,0;1,1"/>
                    <curve sensor="xtilt" name="sizes" commonCurve="0,0.5;1,20"/>
                    <curve sensor="rotation" name="angle" commonCurve="0,-10;1,10"/>
                    <curve sensor="fuzzy" name="sizes" commonCurve="0,0;1,1"/>
                    <curve sensor="ytilt" name="opacity" commonCurve="0,0;1,1"/>
                """.trimIndent(),
            ),
        )
        assertEquals(
            listOf(BrushInput.pressure, BrushInput.tilt),
            p.preset.size.inputs.map { it.input },
            "size inputs",
        )
        // `size.base` is the **first** size sensor's curve top: "0,0.2;1,40" tops out at x = 1, y = 40.
        assertEquals(40f, p.preset.size.base)
        assertEquals(
            listOf(BrushInput.pressure),
            p.preset.opacity.inputs.map { it.input },
            "ytilt must not become a second tilt, and must not become an input at all",
        )
        assertEquals(listOf(BrushInput.barrel), p.preset.tip.angle.inputs.map { it.input })
        // The auto mask's cell is `1.0;0.6` and `tip.aspect` is `rx − ry` in −1..1: 1.0 − 0.6, computed the
        // same way the importer computes it, because 1.0f − 0.6f is 0.39999998f and not 0.4f.
        assertEquals(1.0f - 0.6f, p.preset.tip.aspect)
        assertEquals(2f, p.preset.tip.corner)
        assertEquals(emptyList(), BrushValidate.validate(p.preset), "${p.warnings}")

        assertNotNull(p.preset.extensions["krita.fuzzy"], "${p.preset.extensions.keys}")
        assertNotNull(p.preset.extensions["krita.ytilt"], "${p.preset.extensions.keys}")
        val said = p.warnings.joinToString(" | ")
        assertTrue(said.contains("fuzzy"), said)
        assertTrue(said.contains("ytilt"), said)
    }

    // ---- 12. THE MAPPED-CURVE TEST (Decision 3) -------------------------------------------------------

    /**
     * `commonCurve "0,0;0.5,0.8;1,1"` becomes `[[0,0],[0.5,0.8],[1,1]]` **exactly** — no tolerance is used,
     * and saying so is the point. And a **second** pressure curve on a different `Param` is also present,
     * which is the assertion that a brush with two sensors cannot silently lose one: JB-8.03's whole finding
     * was that it could.
     */
    @Test
    fun aMappedCurveBecomesThePointsKritaWroteAndASecondSensorIsNotLost() {
        val p = single(
            pixel(
                ink(),
                curves = """
                    <curve sensor="pressure" name="sizes" commonCurve="0,0;0.5,0.8;1,1"/>
                    <curve sensor="pressure" name="opacity" commonCurve="0,0.25;1,0.75"/>
                """.trimIndent(),
            ),
        )
        val size = p.preset.size.inputs.single().curve
        assertEquals(3, size.size, "$size")
        for ((i, expected) in listOf(0f to 0f, 0.5f to 0.8f, 1f to 1f).withIndex()) {
            assertEquals(expected.first, size[i][0], "point ${i + 1} x, exactly")
            assertEquals(expected.second, size[i][1], "point ${i + 1} y, exactly")
        }
        val opacity = p.preset.opacity.inputs.single().curve
        assertEquals(2, opacity.size, "$opacity")
        assertEquals(0.25f, opacity[0][1], "the second pressure curve is present, not dropped")
    }

    // ---- 13. a bad curve refuses the brush, and only that brush ---------------------------------------

    /**
     * Four different faults, four different sentences, because "your curve is wrong" is not a sentence
     * anybody can fix. And **the good brushes in the same bundle are `==` what a bundle without them would
     * have produced** — which is what a *local* refusal means.
     */
    @Test
    fun aBadCurveRefusesThatBrushAndOnlyThatBrush() {
        val good = pixel(ink())
        // The same two good brushes, and nothing else: this is the library a bundle without the faults
        // produces, and the good half of the other library has to be `==` to it.
        val alone = KritaImport.convertBundle(
            bundleOf(
                ZipEntry("brushes/a.kpp", ZIP_STORED, kpp(good)),
                ZipEntry("brushes/f.kpp", ZIP_STORED, kpp(good)),
            ),
            "p",
        )
        val withFaults = KritaImport.convertBundle(
            bundleOf(
                ZipEntry("brushes/a.kpp", ZIP_STORED, kpp(good)),
                ZipEntry("brushes/b.kpp", ZIP_STORED, kpp(pixel(ink(), curves = curve("sizes", "pressure", longCurve(65))))),
                ZipEntry("brushes/c.kpp", ZIP_STORED, kpp(pixel(ink(), curves = curve("sizes", "pressure", "0,0;x,1")))),
                ZipEntry("brushes/d.kpp", ZIP_STORED, kpp(pixel(ink(), curves = curve("sizes", "pressure", "0,0;1.4,1")))),
                ZipEntry("brushes/e.kpp", ZIP_STORED, kpp(pixel(ink(), curves = curve("sizes", "pressure", "0,0;1")))),
                ZipEntry("brushes/f.kpp", ZIP_STORED, kpp(good)),
            ),
            "p",
        )
        assertEquals(4, withFaults.refused.size, "${withFaults.refused}")
        assertEquals(listOf("b", "c", "d", "e"), withFaults.refused.map { it.name })
        val reasons = withFaults.refused.map { it.reason }
        // 65 points against a cap of 64 — the cap is the shared `BrushValidate.MAX_CURVE_POINTS`, not a
        // number this file invented, and the message quotes it.
        assertTrue(reasons[0].contains("${KritaImport.CURVE_POINT_CAP}"), reasons[0])
        // "0,0;x,1": two numbers, and the first is not one, so the sentence names the number and says so.
        assertTrue(reasons[1].contains("not a number"), reasons[1])
        assertTrue(reasons[2].contains("0..1"), reasons[2])
        // "0,0;1": one number, not two.
        assertTrue(reasons[3].contains("numbers, not 2"), reasons[3])
        // All four are different sentences, which is the point of four fixtures.
        assertEquals(4, reasons.toSet().size, "$reasons")
        // The good brushes came through **identically**, which is what "that brush" means.
        assertEquals(alone.brushes, withFaults.brushes)
    }

    // ---- 14. THE R40 TESTS (Decision 4) ---------------------------------------------------------------

    /**
     * **14a. The exact sentence, by value.** Three different shapes carry a texture — an embedded PNG tip,
     * a predefined tip by name, and a named texture — and all three warnings contain
     * [TEXTURE_NOT_DRAWN] **itself**, not a retyped literal, so one grep finds one string in
     * all three importers.
     *
     * The fourth assertion is the load-bearing one: a brush with **no** texture says nothing about textures.
     * The warning is per brush that carried one, and a warning that fires everywhere is a warning nobody
     * reads.
     */
    @Test
    fun everyShapeThatCarriesATextureCarriesTheSharedSentenceByValue() {
        val embedded = single(pixel(ink(), definition = embeddedPng(64, 64)))
        val predefined = single(pixel(ink(), definition = """<brush_definition type="mask" name="my_tip.png"/>"""))
        val textured = single(pixel(ink(), texture = texture("paper.png", "Subtract", "0.5")))

        for ((what, result) in listOf("embedded" to embedded, "predefined" to predefined, "texture" to textured)) {
            assertTrue(
                result.warnings.any { it.contains(TEXTURE_NOT_DRAWN) },
                "$what does not carry the R40 sentence: ${result.warnings}",
            )
        }
        val plain = single(pixel(ink()))
        assertFalse(
            plain.warnings.any { it.contains(TEXTURE_NOT_DRAWN) },
            "a brush with no texture must be quiet: ${plain.warnings}",
        )
    }

    /**
     * **14b. The tip is stored, and the round trip is over `extensions`** — not over a result object,
     * because `ImportResult` has no field for bytes and this row did not widen one.
     *
     * `tip.image` is `"tip.png"` and is **asserted not to be the `<brush_definition>` attribute's own name**,
     * because that attribute is a Krita resource path and `TipSpec.image` is "path inside the brush folder".
     * `size.base` is the tip's own `IHDR` width — R40's "the stored image never replaces the numbers".
     */
    @Test
    fun anEmbeddedPngTipIsStoredInExtensionsAndRoundTripsByteForByte() {
        val pngBytes = tipPng(64, 32)
        val result = single(
            pixel(
                ink(),
                definition = """
                    <brush_definition type="mask" name="/usr/share/krita/brushes/inks/hairy.gbr" embedded="true">
                      <param type="base64" name="preset_image" value="${encodeBase64(pngBytes)}"/>
                    </brush_definition>
                """.trimIndent(),
            ),
        )
        val p = result.preset
        assertEquals("image", p.tip.source)
        assertEquals("tip.png", p.tip.image)
        assertFalse(
            p.tip.image == "/usr/share/krita/brushes/inks/hairy.gbr",
            "tip.image must name the encoding, not the foreign path",
        )
        // Rule 23 is satisfied by a non-blank path, which is exactly why the warning is not optional.
        assertEquals(emptyList(), BrushValidate.validate(p), "${result.warnings}")
        assertEquals(64f, p.size.base, "size.base is the tip's own IHDR width")
        val stored = assertNotNull(p.extensions[KritaImport.TIP_IMAGE_KEY], "${p.extensions.keys}")
        assertContentEquals(pngBytes, decodeBase64(stored), "the bytes come back exactly")
    }

    /**
     * **14c. Rule 23 holds and nothing is half-written.** Across the fixtures here that convert, a
     * `source == "image"` always has a non-blank `image`. The second half is the check that catches a
     * half-written path, and it is the reason R40's warning exists at all.
     */
    @Test
    fun everyImageSourceInThisFileNamesANonBlankImage() {
        val library = KritaImport.convertBundle(
            bundleOf(
                ZipEntry("brushes/embedded.kpp", ZIP_STORED, kpp(pixel(ink(), definition = embeddedPng(48, 48)))),
                ZipEntry("brushes/predefined.kpp", ZIP_STORED, kpp(pixel(ink()))),
                ZipEntry("brushes/textured.kpp", ZIP_STORED, kpp(pixel(ink(), texture = texture("paper.png", "Overlay")))),
                ZipEntry("brushes/grain.kpp", ZIP_STORED, kpp(pixel(ink(), texture = texture("grain.png", "Subtract")))),
                ZipEntry("patterns/grain.png", ZIP_STORED, tipPng(32, 32)),
            ),
            "p",
        )
        assertEquals(0, library.refused.size, "${library.refused}")
        var imageSources = 0
        for (result in library.brushes) {
            val p = result.preset
            if (p.tip.source == "image") {
                imageSources++
                assertTrue(!p.tip.image.isNullOrBlank(), "${p.name}: tip.source is image with a blank path")
            }
            if (p.paperGrain.source == "image") {
                imageSources++
                assertTrue(
                    !p.paperGrain.image.isNullOrBlank(),
                    "${p.name}: paperGrain.source is image with a blank path",
                )
            }
        }
        // Non-vacuity: the embedded tip and the bundle's STORED pattern really did produce image sources.
        assertEquals(
            2,
            imageSources,
            "${library.brushes.map { it.preset.tip.source to it.preset.paperGrain.source }}",
        )
    }

    /**
     * **14d. Over the cap, not truncated** (Decision 4). The image is dropped **whole** — the key is
     * *absent*, not empty — `tip.source` stays `"procedural"`, `size.base` is untouched, and the warning
     * still carries [TEXTURE_NOT_DRAWN] plus the byte count and the cap.
     *
     * **The two caps are kept apart on purpose and this fixture is where that is proved.** The tip is
     * `200 000` bytes, so its base64 is `ceil(200 000 / 3) × 4 = 66 667 × 4 = 266 668` characters: over
     * `MAX_EXTENSION_BYTES = 256 × 1024 = 262 144`, and far under this row's own
     * `MAX_BASE64_CHARS = 8 388 608`. The brush is over one cap and nowhere near the other, so the two are
     * demonstrably not the same number.
     */
    @Test
    fun anEmbeddedTipOverTheExtensionsCapIsDroppedWholeAndSaysWhy() {
        val pngBytes = tipPng(300, 300, totalBytes = 200_000)
        assertEquals(200_000, pngBytes.size)
        val encoded = encodeBase64(pngBytes)
        assertEquals(266_668, encoded.length, "ceil(200 000 / 3) * 4")
        assertTrue(encoded.length > MAX_EXTENSION_BYTES, "the fixture must be over the cap")
        assertTrue(encoded.length < KritaImport.MAX_BASE64_CHARS, "and under this row's own base64 cap")

        val result = single(
            pixel(
                ink(),
                extra = """<param type="range" name="PixelSize" value="12"/>""",
                definition = """
                    <brush_definition type="mask" name="big.png" embedded="true">
                      <param type="base64" name="preset_image" value="$encoded"/>
                    </brush_definition>
                """.trimIndent(),
            ),
        )
        val p = result.preset
        assertFalse(KritaImport.TIP_IMAGE_KEY in p.extensions, "the key must be absent, not empty")
        assertEquals("procedural", p.tip.source)
        assertTrue(p.tip.image == null, "${p.tip.image}")
        // 12 is the file's own `PixelSize`, and the tip's 300 px width must **not** have replaced it.
        assertEquals(12f, p.size.base)
        val warning = result.warnings.first { it.contains(TEXTURE_NOT_DRAWN) }
        assertTrue(warning.contains("200000"), warning)
        assertTrue(warning.contains(MAX_EXTENSION_BYTES.toString()), warning)
        assertTrue(warning.contains("none of it is stored"), warning)
        assertEquals(emptyList(), BrushValidate.validate(p), "${result.warnings}")
    }

    /** **14e. GBR and GIH are still refused, by name**, and a predefined tip by name converts. */
    @Test
    fun aBase64GbrOrGihIsRefusedByNameAndAPredefinedTipConverts() {
        for ((extension, magic) in listOf("gbr" to "GIMP", "gih" to "gimp")) {
            val library = KritaImport.convertKpp(
                kpp(
                    pixel(
                        ink(),
                        definition = """
                            <brush_definition type="gimagebrush" name="hair.$extension" embedded="true">
                              <param type="base64" name="preset_image" value="${encodeBase64(magic.toByteArray())}"/>
                            </brush_definition>
                        """.trimIndent(),
                    ),
                ),
                "kpp",
            )
            assertEquals(0, library.brushes.size, "$extension converted")
            assertEquals(1, library.refused.size)
            val why = library.refused.single().reason
            assertTrue(why.contains(extension.uppercase()), "$extension was refused without its name: $why")
        }
        // A tip named but **not carried** is LOSSY, not refused: there is nothing in the file to refuse on.
        val named = single(pixel(ink(), definition = """<brush_definition type="mask" name="brushes/ink.png"/>"""))
        assertEquals("procedural", named.preset.tip.source)
        assertEquals("brushes/ink.png", named.preset.extensions[KritaImport.TIP_PREDEFINED_KEY])
        assertTrue(
            named.warnings.any { it.contains("brushes/ink.png") },
            "the warning must name the missing image: ${named.warnings}",
        )
        assertEquals(emptyList(), BrushValidate.validate(named.preset))
    }

    // ---- 15. texture: both branches, both decided -----------------------------------------------------

    /**
     * **Branch one** — a `.kpp` that names a pattern resource. `Subtract` is one of the four
     * Photoshop-compatible modes (R4 §A.5, from Krita's own source), so the grain turns on and takes a
     * depth; `Overlay` has no field, so it is kept raw in `extensions` and named in a warning. Both carry
     * the R40 sentence and the pattern name, and both validate clean.
     */
    @Test
    fun aNamedTextureMapsToPaperGrainAndSaysTheModeAndThatItIsNotDrawn() {
        val subtract = single(pixel(ink(), texture = texture("paper_dots.png", "Subtract", "0.4")))
        assertTrue(subtract.preset.paperGrain.enabled)
        assertEquals("cloud", subtract.preset.paperGrain.source, "a named pattern is not an image")
        assertEquals(0.4f, subtract.preset.paperGrain.depth.base)
        assertEquals("paper_dots.png", subtract.preset.extensions[KritaImport.TEXTURE_PATTERN_KEY])
        assertTrue(subtract.warnings.any { it.contains("Subtract") }, "${subtract.warnings}")
        assertTrue(
            subtract.warnings.any { it.contains(TEXTURE_NOT_DRAWN) },
            "${subtract.warnings}",
        )
        // A Photoshop-compatible mode has no `GrainSpec` field, so it is not written to one either.
        assertFalse(
            KritaImport.TEXTURE_MODE_KEY in subtract.preset.extensions,
            "${subtract.preset.extensions.keys}",
        )
        assertEquals(emptyList(), BrushValidate.validate(subtract.preset))

        val overlay = single(pixel(ink(), texture = texture("paper_lines.png", "Overlay", "0.7")))
        assertFalse(overlay.preset.paperGrain.enabled, "a mode Joy Brush has no field for does not turn it on")
        assertEquals("Overlay", overlay.preset.extensions[KritaImport.TEXTURE_MODE_KEY])
        assertTrue(overlay.warnings.any { it.contains("Overlay") }, "${overlay.warnings}")
        assertTrue(
            overlay.warnings.any { it.contains(TEXTURE_NOT_DRAWN) },
            "${overlay.warnings}",
        )
        assertEquals("paper_lines.png", overlay.preset.extensions[KritaImport.TEXTURE_PATTERN_KEY])
        assertEquals(emptyList(), BrushValidate.validate(overlay.preset))
    }

    /**
     * **Branch two** — the name resolves to a **STORED** entry of the same bundle, so the bytes are read and
     * R40 applies to the *grain*: `source = "image"`, `image = "grain.png"`, the bytes in
     * `extensions["krita.grainImage"]` round-tripping exactly, and the R40 sentence.
     *
     * The second half is a **DEFLATE** entry, which this row cannot read (Decision 5), and it must fall
     * back to the named case with a sentence saying so rather than to silence.
     */
    @Test
    fun aBundleStoredPatternBecomesTheGrainAndADeflatedOneFallsBackToItsName() {
        val stored = tipPng(32, 32)
        val withStored = KritaImport.convertBundle(
            bundleOf(
                ZipEntry("brushes/grain.kpp", ZIP_STORED, kpp(pixel(ink(), texture = texture("grain.png", "Subtract")))),
                ZipEntry("patterns/grain.png", ZIP_STORED, stored),
            ),
            "p",
        )
        assertEquals(0, withStored.refused.size, "${withStored.refused}")
        val p = withStored.brushes.single().preset
        assertEquals("image", p.paperGrain.source)
        assertEquals("grain.png", p.paperGrain.image)
        assertTrue(p.paperGrain.enabled)
        assertContentEquals(
            stored,
            decodeBase64(assertNotNull(p.extensions[KritaImport.GRAIN_IMAGE_KEY])),
            "the pattern's bytes come back exactly",
        )
        assertTrue(
            withStored.brushes.single().warnings.any { it.contains(TEXTURE_NOT_DRAWN) },
            "${withStored.brushes.single().warnings}",
        )
        assertEquals(emptyList(), BrushValidate.validate(p))

        val withDeflated = KritaImport.convertBundle(
            bundleOf(
                ZipEntry("brushes/grain.kpp", ZIP_STORED, kpp(pixel(ink(), texture = texture("grain.png", "Subtract")))),
                ZipEntry("patterns/grain.png", ZIP_DEFLATED, stored),
            ),
            "p",
        )
        val fallback = withDeflated.brushes.single().preset
        assertEquals("cloud", fallback.paperGrain.source, "an unread pattern is never invented")
        assertEquals("grain.png", fallback.extensions[KritaImport.TEXTURE_PATTERN_KEY])
        assertFalse(KritaImport.GRAIN_IMAGE_KEY in fallback.extensions, "${fallback.extensions.keys}")
        val said = withDeflated.brushes.single().warnings.joinToString(" | ")
        assertTrue(said.contains("could not be read out of the bundle"), said)
        assertTrue(said.contains(TEXTURE_NOT_DRAWN), said)
    }

    // ---- 16. ColorSmudge is legal and says so ---------------------------------------------------------

    /**
     * Decision 11 and R40. `engine == "smudge"` — a word `BrushValidate.ENGINES` **already** accepts, which
     * is why this row needs no version bump and no validator edit, and this test is what proves it.
     *
     * "Exactly one" is part of the assertion: the engine really is a per-brush fact, and a warning repeated
     * per sensor would be a different sentence entirely.
     */
    @Test
    fun colorSmudgeImportsAsSmudgeAndSaysTheEngineIsNotBuiltYetExactlyOnce() {
        val result = single(pixel(ink(), engine = "ColorSmudge"))
        assertEquals("smudge", result.preset.engine)
        assertEquals(emptyList(), BrushValidate.validate(result.preset), "${result.warnings}")
        val matches = result.warnings.filter { it.contains("smudge engine is not built yet") }
        assertEquals(1, matches.size, "${result.warnings}")
        assertTrue(matches.single().contains(KritaImport.SMUDGE_NOT_BUILT), matches.single())
    }

    // ---- 17. a bundle walks, names are not trusted, content decides ------------------------------------

    @Test
    fun aBundleWalksInNameOrderAndAnEscapingEntryNameIsRefusedNotHonoured() {
        val library = KritaImport.convertBundle(
            bundleOf(
                ZipEntry("brushes/b.kpp", ZIP_STORED, kpp(pixel(ink()))),
                ZipEntry("brushes/a.kpp", ZIP_STORED, kpp(pixel(ink()))),
                ZipEntry("paintoppresets/c.kpp", ZIP_STORED, kpp(pixel(ink()))),
                // The three shapes a substring test for ".." misses and a segment test does not:
                // ".. " (a segment of dots and a space), "a/./b" (a segment that *is* a dot), and
                // "brushes/a/./evil3.kpp", which reaches `evil3.kpp` after a `.` segment.
                ZipEntry("brushes/../evil.kpp", ZIP_STORED, kpp(pixel(ink()))),
                ZipEntry("brushes/.. /evil2.kpp", ZIP_STORED, kpp(pixel(ink()))),
                ZipEntry("brushes/a/./evil3.kpp", ZIP_STORED, kpp(pixel(ink()))),
            ),
            "p",
        )
        assertEquals(listOf("a", "b", "c"), library.brushes.map { it.preset.name })
        assertEquals(3, library.refused.size, "${library.refused.map { it.name }}")
        for (r in library.refused) {
            assertTrue(
                r.reason.contains("climbs out of the archive") || r.reason.contains("empty path segment"),
                "${r.name}: ${r.reason}",
            )
        }
        // And a bundle with neither directory REFUSES, in words, rather than importing nothing quietly.
        val empty = assertFailsWith<BrushException> {
            KritaImport.convertBundle(bundleOf(ZipEntry("preview.png", ZIP_STORED, tipPng(4, 4))), "p")
        }
        assertTrue((empty.message ?: "").contains("holds no brushes"), empty.message ?: "")
    }

    // ---- 18. every budget refuses, one at cap + 1 ------------------------------------------------------

    @Test
    fun aFileOverTheFileCapRefusesAndNamesItsCap() {
        val e = assertFailsWith<BrushException> {
            KritaImport.convertKpp(ByteArray(KritaImport.MAX_FILE_BYTES.toInt() + 1), "kpp")
        }
        assertTrue((e.message ?: "").contains(KritaImport.MAX_FILE_BYTES.toString()), e.message ?: "")
    }

    @Test
    fun aChunkOverTheChunkByteCapRefusesAndNamesItsCap() {
        val bytes = SIGNATURE + be32(KritaImport.MAX_CHUNK_BYTES + 1) + "tEXt".toByteArray()
        val e = assertFailsWith<BrushException> { KritaImport.convertKpp(bytes, "kpp") }
        assertTrue((e.message ?: "").contains(KritaImport.MAX_CHUNK_BYTES.toString()), e.message ?: "")
    }

    @Test
    fun aFileOverTheChunkCountCapRefusesAndNamesItsCap() {
        val chunks = ArrayList<ByteArray>()
        repeat(KritaImport.MAX_CHUNKS + 1) { chunks += chunkBytes("zzZz", ByteArray(0)) }
        val e = assertFailsWith<BrushException> { KritaImport.convertKpp(SIGNATURE + concat(chunks), "kpp") }
        assertTrue((e.message ?: "").contains(KritaImport.MAX_CHUNKS.toString()), e.message ?: "")
    }

    @Test
    fun aTextChunkOverTheStringCapRefusesAndNamesItsCap() {
        val bytes = SIGNATURE + concat(
            listOf(ihdr(8, 8), textChunk("preset", "x".repeat(KritaImport.MAX_STRING_BYTES + 1))),
        )
        val e = assertFailsWith<BrushException> { KritaImport.convertKpp(bytes, "kpp") }
        assertTrue((e.message ?: "").contains(KritaImport.MAX_STRING_BYTES.toString()), e.message ?: "")
    }

    @Test
    fun anXmlNestOverTheDepthCapRefusesAndNamesItsCap() {
        // `<paintop>` is depth 1 and each `<a>` is one deeper, so 64 of them reaches depth 65 and a cap of
        // 64 refuses. The derivation is in the number, not in the assertion.
        val body = "<a>".repeat(KritaImport.MAX_XML_DEPTH) + "</a>".repeat(KritaImport.MAX_XML_DEPTH)
        val why = KritaImport.convertKpp(kpp("""<paintop id="Pixel">$body</paintop>"""), "kpp")
            .refused.single().reason
        assertTrue(why.contains(KritaImport.MAX_XML_DEPTH.toString()), why)
        assertTrue(why.contains("nests elements"), why)
    }

    @Test
    fun anElementOverTheNodeCapRefusesAndNamesItsCap() {
        val body = "<a/>".repeat(KritaImport.MAX_XML_NODES + 1)
        val why = KritaImport.convertKpp(kpp("""<paintop id="Pixel">$body</paintop>"""), "kpp")
            .refused.single().reason
        assertTrue(why.contains(KritaImport.MAX_XML_NODES.toString()), why)
        assertTrue(why.contains("XML nodes"), why)
    }

    @Test
    fun anElementOverTheAttributesCapRefusesAndNamesItsCap() {
        val extra = (1..KritaImport.MAX_XML_ATTRS_PER_NODE).joinToString("") { " a$it=\"$it\"" }
        // The root already spends one of the 256 on `id`, so this element carries 257 attributes in all.
        val why = KritaImport.convertKpp(kpp("""<paintop id="Pixel"$extra/>"""), "kpp").refused.single().reason
        assertTrue(why.contains(KritaImport.MAX_XML_ATTRS_PER_NODE.toString()), why)
        assertTrue(why.contains("attributes"), why)
    }

    /**
     * The two base64 caps, and **the finding this test produces rather than a green tick.**
     *
     * `MAX_BASE64_CHARS` is 8 388 608 and `MAX_PNG_STRING_BYTES` is 8 × 1024 × 1024 = 8 388 608: **the same
     * number.** The base64 payload lives *inside* the `preset` text chunk, so a payload longer than the
     * base64 cap always makes the chunk longer than the string cap, and the string cap is what refuses.
     * The two are therefore not independent as this spec intended, and `MAX_BASE64_CHARS` is **unreachable**
     * through a `.kpp`.
     *
     * The fixture below really does carry a `MAX_BASE64_CHARS + 1`-character base64 value, and the assertion
     * is that the **string** cap is the one that fires — which is a derived fact about two numbers being
     * equal, not a green tick standing in for a path that cannot be walked. In the report as a Question.
     */
    @Test
    fun theBase64CapIsUnreachableBecauseItIsTheSameNumberAsTheStringCap() {
        assertEquals(KritaImport.MAX_BASE64_CHARS, KritaImport.MAX_STRING_BYTES)
        val xml = pixel(
            ink(),
            definition = """<brush_definition type="mask" name="tip.png" embedded="true">""" +
                """<param type="base64" name="preset_image" value="${"A".repeat(KritaImport.MAX_BASE64_CHARS + 1)}"/>""" +
                """</brush_definition>""",
        )
        val e = assertFailsWith<BrushException> { KritaImport.convertKpp(kpp(xml), "kpp") }
        val why = e.message ?: ""
        // The string cap, not the base64 cap, and the chunk it refused is named.
        assertTrue(why.contains(KritaImport.MAX_STRING_BYTES.toString()), why)
        assertTrue(why.contains("tEXt"), why)
    }

    @Test
    fun aBundleOverTheBrushCapRefusesAndNamesItsCap() {
        val entries = ArrayList<ZipEntry>()
        repeat(KritaImport.MAX_BRUSHES + 1) { entries += ZipEntry("brushes/b$it.kpp", ZIP_STORED, ByteArray(0)) }
        val e = assertFailsWith<BrushException> { KritaImport.convertBundle(bundleOf(*entries.toTypedArray()), "p") }
        assertTrue((e.message ?: "").contains(KritaImport.MAX_BRUSHES.toString()), e.message ?: "")
    }

    // ---- 19. hostile XML is refused, not honoured -----------------------------------------------------

    /**
     * Decision 9. A `.kpp` is a stranger's file and XXE is the first thing that comes to mind, so a
     * `<!DOCTYPE` with an external entity is refused **before a character of it is read**. A mismatched close
     * tag and a 200-deep nest are refused too.
     *
     * **One case does not refuse, and Decision 9 itself says why: an undefined entity is left as text.**
     * The rule is "entities are limited to `&amp; &lt; &gt; &quot; &apos; &#NNN;`; anything else is left
     * as text", and that is the better rule — a file that writes `&nbsp;` in a brush name should not cost
     * the person their brush. The test asserts the *literal* comes through, which is the anti-XXE property
     * that matters: nothing is resolved, so nothing can be fetched. The spec's own test 19 expected a
     * refusal here; this follows Decision 9 and the difference is in the report.
     */
    @Test
    fun hostileXmlIsRefusedAndAnUndefinedEntityIsLeftAsTextAndNeverResolved() {
        val hostile = listOf(
            "holds a DOCTYPE or an ENTITY declaration" to
                "<?xml version=\"1.0\"?><!DOCTYPE paintop [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
                    "<paintop id=\"Pixel\"/>",
            "closes" to """<paintop id="Pixel"><curve sensor="pressure" name="sizes"></pressure></paintop>""",
        )
        for ((needle, xml) in hostile) {
            val library = KritaImport.convertKpp(kpp(xml), "kpp")
            assertEquals(1, library.refused.size, "$needle converted: ${library.brushes}")
            assertTrue(library.refused.single().reason.contains(needle), library.refused.single().reason)
        }

        // 200 deep against a cap of 64 — refused with a message and without a stack overflow.
        val deep = "<a>".repeat(200) + "</a>".repeat(200)
        val tooDeep = KritaImport.convertKpp(kpp("""<paintop id="Pixel">$deep</paintop>"""), "kpp")
        assertEquals(1, tooDeep.refused.size)
        assertTrue(tooDeep.refused.single().reason.contains("nests elements"), tooDeep.refused.single().reason)

        // The entity case: `&amp;` decoded, everything else literal, and nothing resolved.
        val literal = KritaImport.convertKpp(
            kpp("""<paintop id="Pixel" name="a&amp;b &nbsp; c &xxe; d"/>"""),
            "kpp",
        )
        val kept = literal.brushes.single().preset.extensions
        assertEquals("a&b &nbsp; c &xxe; d", kept["krita.paintop.name"], "$kept")
    }

    // ---- 20. a malformed `.kpp` throws ----------------------------------------------------------------

    @Test
    fun aMalformedKppThrowsRatherThanImportingSomething() {
        val cases = listOf<Pair<String, ByteArray>>(
            "not a PNG" to byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
            "an empty file" to ByteArray(0),
            "a 5-byte file" to ByteArray(5),
            // A PNG with an IHDR and no `preset` chunk at all.
            "a PNG with no preset chunk" to (SIGNATURE + ihdr(8, 8)),
            // A PNG whose `preset` chunk holds prose.
            "a preset chunk that is not XML" to (SIGNATURE + textChunk("preset", "this is not a preset")),
        )
        for ((what, bytes) in cases) {
            val e = assertFailsWith<BrushException>("$what did not throw") { KritaImport.convertKpp(bytes, "kpp") }
            assertTrue((e.message ?: "").isNotBlank(), what)
        }
    }

    /**
     * A compressed `preset` chunk is refused **by the caller**, in words, with "compressed" in the sentence
     * and the reason for it in the second half — so a person knows it is this build's choice and not a
     * corrupt file.
     */
    @Test
    fun aCompressedPresetChunkIsRefusedByTheCallerInWords() {
        val bytes = SIGNATURE + concat(
            listOf(
                ihdr(8, 8),
                // keyword, NUL, compression method 0, then bytes that are deliberately not a DEFLATE stream.
                chunkBytes("zTXt", "preset".toByteArray() + byteArrayOf(0, 0, 1, 2, 3, 4)),
            ),
        )
        val why = assertFailsWith<BrushException> { KritaImport.convertKpp(bytes, "kpp") }.message ?: ""
        assertTrue(why.contains("compressed"), why)
        assertTrue(why.contains("does not inflate"), why)
    }

    // ---- the fixture builders ------------------------------------------------------------------------

    private class ZipEntry(val name: String, val method: Int, val data: ByteArray)

    /** The auto mask every fixture starts from: an ellipse of `rx = 1.0`, `ry = 0.6`, so aspect 0.4. */
    private val autoMask = """<brush_definition type="auto_mask" name="start" active="true">""" +
        """<cell row="0" col="0" value="1.0;0.6"/></brush_definition>"""

    private fun pixel(
        body: String,
        engine: String = "Pixel",
        curves: String = "",
        definition: String = autoMask,
        texture: String? = null,
        extra: String = "",
    ) = """<paintop id="$engine">$definition$extra$curves${texture ?: ""}$body</paintop>"""

    /** One brush: the auto mask, a 0.3 spacing, and nothing else. */
    private fun ink() = """<spacing mode="space" value="0.3"/>"""

    private fun scatter() =
        """<spacing mode="space" value="0.3"/><curve sensor="pressure" name="scattering" commonCurve="0,0;1,0.3"/>"""

    private fun blitter() = """<smoothing mode="none" value="0.6"/>"""

    private fun curve(name: String, sensor: String, points: String) =
        """<curve sensor="$sensor" name="$name" commonCurve="$points"/>"""

    private fun tilts(vararg sensors: String) = sensors.joinToString("") {
        """<curve sensor="$it" name="sizes" commonCurve="0,0.5;1,20"/>"""
    }

    private fun texture(pattern: String, mode: String, depth: String = "0.5") =
        """<texture enabled="true" pattern="$pattern" blendmode="$mode" depth="$depth"/>"""

    private fun embeddedPng(width: Int, height: Int): String {
        val png = tipPng(width, height)
        return """<brush_definition type="mask" name="tip.png" embedded="true">""" +
            """<param type="base64" name="preset_image" value="${encodeBase64(png)}"/></brush_definition>"""
    }

    private fun longCurve(count: Int) = (0 until count).joinToString(";") { "$it,0.5" }

    private fun single(xml: String) = KritaImport.convertKpp(kpp(xml), "kpp").let { library ->
        assertEquals(0, library.refused.size, "${library.refused}")
        assertEquals(1, library.brushes.size, "${library.refused}")
        library.brushes.single()
    }

    // ---- PNG bytes -----------------------------------------------------------------------------------

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    /** A whole `.kpp`: signature, `IHDR`, a `version` chunk, the `preset` chunk, and `IEND`. */
    private fun kpp(xml: String, title: String? = null): ByteArray {
        val chunks = ArrayList<ByteArray>()
        chunks += ihdr(8, 8)
        chunks += textChunk("version", "0.5")
        chunks += textChunk("preset", xml)
        if (title != null) chunks += textChunk("Title", title)
        chunks += chunkBytes("IEND", ByteArray(0))
        return concat(listOf(SIGNATURE) + chunks)
    }

    /**
     * A real PNG carrying an `IHDR` of [width] × [height] and no pixels — a header read, not a decode.
     *
     * [totalBytes] pads it with one unknown-type chunk, which the walk steps over by its declared length
     * and which no string budget can catch, so the **byte count** can be driven exactly. That is what the
     * over-the-cap fixture needs: a 300 px wide PNG whose base64 is over 256 KiB.
     */
    private fun tipPng(width: Int, height: Int, totalBytes: Int? = null): ByteArray {
        val head = concat(listOf(SIGNATURE, ihdr(width, height)))
        if (totalBytes == null) return concat(listOf(head, chunkBytes("IEND", ByteArray(0))))
        val padding = totalBytes - head.size - 12 - 12
        require(padding > 0) { "totalBytes $totalBytes is below the ${head.size + 24} a PNG needs" }
        return concat(listOf(head, chunkBytes("padX", ByteArray(padding)), chunkBytes("IEND", ByteArray(0))))
    }

    private fun ihdr(width: Int, height: Int): ByteArray {
        val data = ByteArray(13)
        writeBe32(data, 0, width)
        writeBe32(data, 4, height)
        data[8] = 8
        data[9] = 6
        return chunkBytes("IHDR", data)
    }

    private fun textChunk(keyword: String, body: String): ByteArray {
        val data = ByteArray(keyword.length + 1 + body.length)
        for (i in keyword.indices) data[i] = keyword[i].code.toByte()
        data[keyword.length] = 0
        for (i in body.indices) data[keyword.length + 1 + i] = body[i].code.toByte()
        return chunkBytes("tEXt", data)
    }

    private fun chunkBytes(type: String, data: ByteArray): ByteArray {
        val out = ByteArray(12 + data.size)
        be32(data.size.toLong()).copyInto(out, 0)
        for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
        data.copyInto(out, 8)
        be32(crc32(type.toByteArray(), data)).copyInto(out, 8 + data.size)
        return out
    }

    private fun writeBe32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v shr 24).toByte()
        b[at + 1] = (v shr 16).toByte()
        b[at + 2] = (v shr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun be32(v: Long): ByteArray = byteArrayOf(
        (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte(),
    )

    private fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    /** CRC-32 as PNG and zip both state it: the reflected IEEE polynomial `0xEDB88320`. */
    private fun crc32(vararg parts: ByteArray): Long {
        var c = 0xFFFFFFFFL
        for (part in parts) {
            for (b in part) {
                c = c xor (b.toLong() and 0xFF)
                repeat(8) {
                    c = if (c and 1L != 0L) (c ushr 1) xor 0xEDB88320L else c ushr 1
                }
            }
        }
        return (c xor 0xFFFFFFFFL) and 0xFFFFFFFFL
    }

    // ---- zip bytes, written by hand --------------------------------------------------------------------

    /**
     * A real zip, written entry by entry. **`commonTest` may not use `java.util.zip`** — the spec's own rule,
     * and the reason every bundle fixture here is hand-built; a `ZipOutputStream` would also be a second
     * implementation of the thing under test.
     *
     * `ZIP_STORED` copies the bytes and is the only method this build reads. `ZIP_DEFLATED` writes a genuine
     * **stored DEFLATE block** (RFC 1951 §3.2.4: `BFINAL = 1`, `BTYPE = 00`, `LEN`, `~LEN`, then the raw
     * bytes), so a method-8 entry in a fixture is a real one and the reader's refusal is a refusal of
     * something genuine rather than of a made-up header.
     */
    private fun bundleOf(vararg entries: ZipEntry): ByteArray {
        val pieces = ArrayList<ByteArray>()
        val offsets = ArrayList<Int>()
        val payloads = ArrayList<ByteArray>()
        var at = 0
        for (e in entries) {
            offsets += at
            val body = if (e.method == ZIP_DEFLATED) storedDeflateBlock(e.data) else e.data
            payloads += body
            val name = e.name.toByteArray()
            val head = ByteArray(30)
            le32(0x04034b50L).copyInto(head, 0)          // local file header signature
            le16(20).copyInto(head, 4)                   // version needed
            le16(0).copyInto(head, 6)                    // flags
            le16(e.method).copyInto(head, 8)
            le16(0).copyInto(head, 10)                   // mod time
            le16(0).copyInto(head, 12)                   // mod date
            le32(crc32(e.data)).copyInto(head, 14)
            le32(e.data.size.toLong()).copyInto(head, 18)
            le32(e.data.size.toLong()).copyInto(head, 22)
            le16(name.size).copyInto(head, 26)
            le16(0).copyInto(head, 28)                   // extra length
            pieces += head
            pieces += name
            pieces += body
            at += 30 + name.size + body.size
        }
        val directoryAt = at
        for ((i, e) in entries.withIndex()) {
            val name = e.name.toByteArray()
            val head = ByteArray(46)
            le32(0x02014b50L).copyInto(head, 0)          // central directory signature
            le16(20).copyInto(head, 4)                   // version made by
            le16(20).copyInto(head, 6)                   // version needed
            le16(0).copyInto(head, 8)                    // flags
            le16(e.method).copyInto(head, 10)
            le16(0).copyInto(head, 12)                   // mod time
            le16(0).copyInto(head, 14)                   // mod date
            le32(crc32(e.data)).copyInto(head, 16)
            le32(payloads[i].size.toLong()).copyInto(head, 20)
            le32(e.data.size.toLong()).copyInto(head, 24)
            le16(name.size).copyInto(head, 28)
            le16(0).copyInto(head, 30)                   // extra length
            le16(0).copyInto(head, 32)                   // comment length
            le16(0).copyInto(head, 34)                   // disk number start
            le16(0).copyInto(head, 36)                   // internal attributes
            le32(0L).copyInto(head, 38)                  // external attributes
            le32(offsets[i].toLong()).copyInto(head, 42) // local header offset
            pieces += head
            pieces += name
            at += 46 + name.size
        }
        val directorySize = at - directoryAt
        val end = ByteArray(22)
        le32(0x06054b50L).copyInto(end, 0)               // end-of-directory signature
        le16(0).copyInto(end, 4)                        // this disk
        le16(0).copyInto(end, 6)                        // disk with the directory
        le16(entries.size).copyInto(end, 8)             // entries on this disk
        le16(entries.size).copyInto(end, 10)            // entries in total
        le32(directorySize.toLong()).copyInto(end, 12)
        le32(directoryAt.toLong()).copyInto(end, 16)
        le16(0).copyInto(end, 20)                       // comment length
        pieces += end
        return concat(pieces)
    }

    /** RFC 1951 §3.2.4's stored block: one header byte, `LEN`, `~LEN`, then the bytes themselves. */
    private fun storedDeflateBlock(data: ByteArray): ByteArray {
        require(data.size <= 0xFFFF) { "a stored DEFLATE block holds at most 65 535 bytes" }
        val out = ByteArray(5 + data.size)
        out[0] = 0x01 // BFINAL = 1, BTYPE = 00
        le16(data.size).copyInto(out, 1)
        le16(data.size.inv() and 0xFFFF).copyInto(out, 3)
        data.copyInto(out, 5)
        return out
    }

    // ---- base64, written independently ----------------------------------------------------------------

    /**
     * RFC 4648 §4, written here rather than imported, so the round trips in 14b and 15 are checked against
     * an **independently written** codec rather than against the importer's own encoder. The derivation is
     * `AbrImport.base64Of`'s: three bytes are 24 bits and four characters take six each. The alphabet is
     * [KRITA_TEST_BASE64] at the bottom of this file.
     */

    private fun encodeBase64(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
            sb.append(KRITA_TEST_BASE64[(n ushr 18) and 63])
            sb.append(KRITA_TEST_BASE64[(n ushr 12) and 63])
            sb.append(KRITA_TEST_BASE64[(n ushr 6) and 63])
            sb.append(KRITA_TEST_BASE64[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(KRITA_TEST_BASE64[(n ushr 18) and 63]).append(KRITA_TEST_BASE64[(n ushr 12) and 63]).append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(KRITA_TEST_BASE64[(n ushr 18) and 63])
                sb.append(KRITA_TEST_BASE64[(n ushr 12) and 63])
                sb.append(KRITA_TEST_BASE64[(n ushr 6) and 63]).append('=')
            }
        }
        return sb.toString()
    }

    private fun decodeBase64(text: String): ByteArray {
        var end = text.length
        while (end > 0 && text[end - 1] == '=') end--
        val out = ByteArray(end / 4 * 3 + when (end % 4) { 1 -> 0; 2 -> 1; 3 -> 2; else -> 0 })
        var written = 0
        var acc = 0
        var bits = 0
        for (i in 0 until end) {
            val v = KRITA_TEST_BASE64.indexOf(text[i])
            if (v < 0) throw IllegalArgumentException("not base64 at $i")
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (written < out.size) out[written++] = ((acc shr bits) and 0xFF).toByte()
            }
        }
        return if (written == out.size) out else out.copyOf(written)
    }
}

private const val ZIP_STORED = 0
private const val ZIP_DEFLATED = 8

/** RFC 4648 §4's standard alphabet, written out again so the codec above is a second implementation. */
private const val KRITA_TEST_BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
