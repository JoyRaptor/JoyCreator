package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushValidate
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Procreate importer, on structural fixtures built with `java.util.zip`.
 *
 * **In `jvmTest`, not `commonTest`, because the fixtures need `java.util.zip.ZipOutputStream` to
 * build a real archive.** Hand-rolling a second zip writer inside a test would be a second reader to
 * get wrong and a second thing to review; the JDK's is right there and is the thing being tested
 * against.
 *
 * ## The one thing this suite cannot do, stated plainly
 *
 * The spec's test 11 asks for "the two **real** `.brush` archives". **There is no Procreate file
 * anywhere in this repository and this row had no way to obtain one**, so every fixture here is
 * hand-built from the key names R4 §B.2 documents. That is a real gap and it is not a detail:
 *
 *  - a hand-built fixture proves the reader, the zip walk, the mapping, the buckets and the budgets
 *    all agree with each other and with the open bplist and zip formats;
 *  - it does **not** prove that a file Procreate actually wrote imports correctly. If Procreate
 *    stores `plotSpacing` in a unit other than the one R4 records, or writes its `$objects` in a
 *    shape Apple has changed since, every test here stays green and the import is still wrong.
 *
 * The two places where that matters most — the bplist trailer's five unused bytes, and a `Uid`
 * indexing the flat object table rather than the `$objects` array — are derived in comments at both
 * ends of the format, and both were **found by this suite disagreeing with the inherited code**
 * rather than by this suite confirming it. A first run against one real `.brush` from
 * Catherine's Basic Procreate Brushes (R4 §B.7 lists it as a public-domain corpus) is the single
 * highest-value follow-up, and it is cheap.
 */
class ProcreateImportTest {

    // ---- 11. representative archives convert and validate clean (Decision 14) -----------------------

    @Test
    fun twoRepresentativeArchivesConvertAndValidateClean() {
        val shape = png(37, 21)
        val grain = png(64, 64)
        val a = ProcreateImport.convertBrush(withShape(ROUND, shape), "pack")
        val b = ProcreateImport.convertBrush(withGrain(SOFT, grain), "pack")

        assertEquals(1, a.brushes.size, "a .brush holds one brush")
        assertEquals(1, b.brushes.size)
        assertEquals(0, a.refused.size, a.summary())
        assertEquals(0, b.refused.size, b.summary())

        for (result in a.brushes + b.brushes) {
            val p = result.preset
            assertEquals("procreate", p.sourceFormat)
            assertEquals("stamp", p.engine)
            assertTrue(p.license != "CC0", "a .brush states no licence, so \"CC0\" is a claim nobody made")
            assertEquals("unknown", p.license)
            assertEquals(brushId("pack", p.name), p.id)
            // Decision 14: the whole point of the house rule.
            assertEquals(emptyList(), BrushValidate.validate(p), "it would not be a legal brush")
        }

        // Non-vacuity: the archives' own names are among the converted names, and the count is a
        // number rather than "not empty".
        assertEquals(listOf("Round Hard"), a.brushes.map { it.preset.name })
        assertEquals(listOf("Soft Pastel"), b.brushes.map { it.preset.name })

        // The mapped numbers are real, and this is the derivation: `maxSize` is a diameter in px, so
        // it is `size.base` unchanged; `shapeAngle` is degrees, and `tip.angle` is degrees.
        assertEquals(23f, a.brushes[0].preset.size.base)
        assertEquals(31f, b.brushes[0].preset.size.base)
        assertEquals(17f, a.brushes[0].preset.tip.angle.base)
        assertEquals(1, a.brushes[0].preset.scatter.count)
        assertEquals("Someone", a.brushes[0].preset.author)
    }

    @Test
    fun aStoredEntryIsCopiedRatherThanInflated() {
        // Decision 5: "Entry 0 is conventionally STORED (method 0), which is a real case." A zip
        // written with the archive stored and the image deflated is the mixed case, and it is what a
        // `.brush` that one tool wrote and another packed really looks like.
        // **No `maxSize`**, so the `IHDR` path is the one that decides `size.base` — 37 px is the
        // bitmap's own width, and this is the only fixture that proves the STORED case reaches it.
        val archive = PlistFixture.keyed(linkedMapOf("name" to "Round Hard"))
        val bytes = zipOf(
            "Brush.archive" to archive,
            "Shape.png" to png(37, 21),
            method = ZipOutputStream.STORED,
        )
        val result = ProcreateImport.convertBrush(bytes, "pack")
        assertEquals(1, result.brushes.size)
        assertEquals("image", result.brushes[0].preset.tip.source)
        assertEquals(37f, result.brushes[0].preset.size.base)
    }

    @Test
    fun aDeflateEntryIsReadAsRawDeflateAndAZlibHeaderIsTolerated() {
        // **The finding that cost this row a build cycle, kept as a test.**
        //
        // The spec's Decision 5 says a `.brush` entry carries a zlib two-byte header that this reader
        // must check and skip. It does not: a zip entry's method 8 is raw DEFLATE (RFC 1951) per
        // PKWARE APPNOTE 4.4.5, and the JDK's own `ZipOutputStream` — which wrote the first half of
        // this fixture — produces raw DEFLATE, so requiring the header refuses **every** real file.
        //
        // Both halves are asserted, because the fix is "tolerate, do not require":
        //  - the left half is a plain deflated entry, which is what a spec-conforming zip holds;
        //  - the right half is a hand-built entry whose payload *does* carry a zlib header, which is
        //    what the spec describes, and it must import too.
        val plain = ProcreateImport.convertBrush(withShape(ROUND, png(37, 21)), "pack")
        assertEquals(1, plain.brushes.size, "a raw DEFLATE entry must import")
        assertEquals("Round Hard", plain.brushes[0].preset.name)

        val archive = PlistFixture.keyed(linkedMapOf("name" to "Round Hard", "maxSize" to 23.0))
        val zlibWrapped = zipOfRaw(RawEntry("Brush.archive", zlib(archive), archive.size))
        val tolerated = ProcreateImport.convertBrush(zlibWrapped, "pack")
        assertEquals(1, tolerated.brushes.size, "a zlib-wrapped entry must import too")
        assertEquals("Round Hard", tolerated.brushes[0].preset.name)
    }

    // ---- 12. THE MAPPED-CURVE TEST ------------------------------------------------------------------

    @Test
    fun pressureSizeAndOpacityCurvesBecomeNonEmptyInputs() {
        // The assertion that would have caught JB-8.03's Finding 1: a curve key read and dropped.
        val bytes = withShape(
            linkedMapOf(
                "name" to "Round Hard",
                "maxSize" to 23.0,
                "dynamicsPressureSize" to 0.5,
                "dynamicsPressureSizeCurve" to listOf("{0.0, 0.25}", "{0.5, 0.75}", "{1.0, 1.0}"),
                "dynamicsPressureOpacity" to 0.4,
                "dynamicsPressureOpacityCurve" to listOf("{0.0, 0.1}", "{1.0, 1.0}"),
            ),
            png(37, 21),
        )
        val preset = ProcreateImport.convertBrush(bytes, "pack").brushes[0].preset

        val size = assertNotNull(preset.size.inputs.firstOrNull { it.input == BrushInput.pressure })
        assertEquals(3, size.curve.size, "three points in, three points out")
        // The parsed points are exactly the archive's "{x, y}" strings, in order.
        assertEquals(listOf(0.0f, 0.25f), size.curve[0].map { it })
        assertEquals(listOf(0.5f, 0.75f), size.curve[1].map { it })
        assertEquals(listOf(1.0f, 1.0f), size.curve[2].map { it })

        val opacity = assertNotNull(preset.opacity.inputs.firstOrNull { it.input == BrushInput.pressure })
        assertEquals(2, opacity.curve.size)
        assertEquals(listOf(0.0f, 0.1f), opacity.curve[0].map { it })
        assertEquals(emptyList(), BrushValidate.validate(preset))
    }

    @Test
    fun aTiltCurveBecomesATiltInput() {
        val bytes = withShape(
            linkedMapOf(
                "name" to "Round Hard",
                "maxSize" to 23.0,
                "dynamicsTiltSize" to 0.3,
                "dynamicsTiltSizeCurve" to listOf("{0.0, 1.0}", "{1.0, 0.5}"),
            ),
            png(37, 21),
        )
        val preset = ProcreateImport.convertBrush(bytes, "pack").brushes[0].preset
        val tilt = assertNotNull(preset.size.inputs.firstOrNull { it.input == BrushInput.tilt })
        assertEquals(listOf(1.0f, 0.5f), tilt.curve[1].map { it })
        assertEquals(emptyList(), BrushValidate.validate(preset))
    }

    // ---- 13. THE R40 TESTS --------------------------------------------------------------------------

    @Test
    fun theExactR40SentenceIsCarriedByValueAndNotRetyped() {
        // 13a. Asserted against the shared constant, so this test fails if the sentence drifts.
        val withShapeResult = ProcreateImport.convertBrush(withShape(ROUND, png(37, 21)), "pack")
        val withGrainResult = ProcreateImport.convertBrush(withGrain(SOFT, png(64, 64)), "pack")
        val builtIn = brushSet(
            "aaa" to linkedMapOf("name" to "Wants A Built-in", "bundledGrainPath" to "BrushStudio/Grain 1"),
        )

        assertTrue(
            withShapeResult.brushes[0].warnings.any { it.contains(TEXTURE_NOT_DRAWN) },
            "a brush that stored a tip must say it: ${withShapeResult.brushes[0].warnings}",
        )
        assertTrue(
            withGrainResult.brushes[0].warnings.any { it.contains(TEXTURE_NOT_DRAWN) },
            "a brush that stored a grain must say it",
        )
        // Decision 8: the refusal for a built-in carries the sentence too, so one grep finds both.
        val refused = ProcreateImport.convertBrushSet(builtIn, "pack").refused
        assertEquals(1, refused.size)
        assertTrue(refused[0].reason.contains(TEXTURE_NOT_DRAWN), refused[0].reason)
    }

    @Test
    fun theImageIsStoredAndThePresetSaysWhichFileItIsIn() {
        // 13b. The names say what the bytes are and are bare names in the brush folder.
        val shape = png(37, 21)
        val grain = png(64, 64)
        val result = ProcreateImport.convertBrush(withShapeAndGrain(ROUND, shape, grain), "pack")
        val p = result.preset(0)

        assertEquals("image", p.tip.source)
        assertEquals("tip.png", p.tip.image)
        assertEquals("tip.png", p.tip.image ?: "", "NOT \"shape.png\": that is a path inside the foreign .brush")
        assertEquals("grain.png", p.paperGrain.image)
        assertTrue(p.paperGrain.enabled)

        val tipBytes = assertNotNull(p.extensions["procreate.tipImage"])
        val grainBytes = assertNotNull(p.extensions["procreate.grainImage"])
        // Byte for byte, through an independently written decoder rather than the encoder's own output.
        assertContentEquals(shape, base64Decode(tipBytes), "the stored tip is not the archive's Shape.png")
        assertContentEquals(grain, base64Decode(grainBytes), "the stored grain is not the archive's Grain.png")

        // The numbers are still real: `size.base` is `maxSize` here, because the archive has one.
        assertEquals(23f, p.size.base)
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    @Test
    fun sizeBaseIsThePngsOwnWidthWhenTheArchiveStatesNoMaxSize() {
        // The other half of 13b, and the only fixture that proves the `IHDR` path runs at all.
        val bytes = withShape(linkedMapOf("name" to "Round Hard"), png(37, 21))
        val p = ProcreateImport.convertBrush(bytes, "pack").preset(0)
        // 37 is four big-endian bytes at file offsets 16..20 of the Shape.png. A header read, not a
        // decode — the bitmap is never decoded anywhere in this importer.
        assertEquals(37f, p.size.base)
        assertEquals("image", p.tip.source)
    }

    @Test
    fun overTheExtensionCapTheImageIsDroppedWholeAndTheGrainStaysOff() {
        // 13c. 200 000 bytes is 266 668 base64 characters, over the 262 144-character cap. The bytes
        // are incompressible so the entry's expansion ratio is about 1:1 and the **ratio** budget —
        // which is checked first — cannot fire instead of the cap this test is about.
        val huge = incompressible(200_000)
        assertTrue(base64Length(huge) > MAX_EXTENSION_BYTES)
        val result = ProcreateImport.convertBrush(withGrain(SOFT, huge), "pack")
        val p = result.preset(0)

        // Absent, not truncated: a prefix of an image is a file that is not the thing it says it is.
        assertNull(p.extensions["procreate.grainImage"], "a partial image was stored")
        assertEquals("cloud", p.paperGrain.source, "the source must not claim an image that is not there")
        assertEquals(false, p.paperGrain.enabled, "a grain that is on with no picture is a stronger claim")
        val warning = assertNotNull(result.brushes[0].warnings.firstOrNull { it.contains(TEXTURE_NOT_DRAWN) })
        assertTrue(warning.contains("200000"), "the byte count is not in the message: $warning")
        assertTrue(warning.contains("$MAX_EXTENSION_BYTES"), "the cap is not in the message: $warning")
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    @Test
    fun everyImageSourceHasANonBlankImageAndStillValidates() {
        // 13d. The check that catches a half-written path, and the one that would let a lying preset
        // through if 13b were got wrong. `BrushValidate` rule 23 is satisfied by a non-blank path —
        // which is exactly why the R40 warning is load-bearing.
        val library = ProcreateImport.convertBrushSet(
            zipOf(
                "aaa/Brush.archive" to archiveFor(ROUND),
                "aaa/Shape.png" to png(37, 21),
                "bbb/Brush.archive" to archiveFor(SOFT),
                "bbb/Grain.png" to png(64, 64),
                "ccc/Brush.archive" to archiveFor(linkedMapOf("name" to "Bare", "maxSize" to 12.0)),
                "brushset.plist" to PLIST,
            ),
            "pack",
        )
        assertEquals(3, library.brushes.size, library.summary())
        var seen = 0
        for (result in library.brushes) {
            val p = result.preset
            if (p.tip.source == "image") { assertTrue(!p.tip.image.isNullOrBlank()); seen++ }
            if (p.paperGrain.source == "image") { assertTrue(!p.paperGrain.image.isNullOrBlank()); seen++ }
            assertEquals(emptyList(), BrushValidate.validate(p), "${p.name} would not be a legal brush")
        }
        assertEquals(2, seen, "the two image sources in this set are not being exercised")
    }

    // ---- 14. every unverified field is a LOSSY with the raw kept -------------------------------------

    @Test
    fun everyUnverifiedFieldKeepsItsRawAndIsNamedInAWarning() {
        val fields = listOf(
            "plotSpacing" to 0.25, "plotJitter" to 0.1, "plotSmoothing" to 0.4,
            "textureScale" to 3.0, "grainBlendMode" to "Multiply", "shapeFlipXJitter" to 0.5,
        )
        val root = linkedMapOf<String, Any?>("name" to "Round Hard", "maxSize" to 23.0)
        for ((k, v) in fields) root[k] = v
        val result = ProcreateImport.convertBrush(withShape(root, png(37, 21)), "pack")
        val p = result.preset(0)

        for ((key, _) in fields) {
            assertNotNull(p.extensions["procreate.$key"], "$key did not keep its raw value")
        }
        // …and named in a warning. Any one warning that names all six will do; they are not all
        // named by the same sentence in the code, and the rule is that a person can see them.
        val said = result.brushes[0].warnings.joinToString("\n")
        for ((key, _) in fields) {
            assertTrue(said.contains(key), "$key is kept but never named in a warning")
        }
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    // ---- 15. the unverified-scale warning is ONCE per pack (Decision 10) -----------------------------

    @Test
    fun theUnverifiedScaleWarningIsExactlyOnePerPack() {
        // JB-8.03's review Finding 2 as a named test: "exactly one" is a number, so it is asserted.
        val entries = (1..5).map { i ->
            val uuid = "u$i"
            uuid to linkedMapOf(
                "name" to "Brush $i",
                "maxSize" to 20.0 + i,
                "plotSpacing" to 0.1 * i,          // ≈ — would fire once per brush if it were per brush
                "plotJitter" to 0.05,
                "dynamicsJitterLightness" to 0.5,  // unresolved — also a pack fact
            )
        }.toTypedArray()
        val library = ProcreateImport.convertBrushSet(brushSet(*entries), "pack")

        assertEquals(5, library.brushes.size)
        assertEquals(0, library.refused.size, library.summary())
        var count = 0
        for (result in library.brushes) {
            count += result.warnings.count { it.contains(SCALE_WARNING_MARKER) }
        }
        assertEquals(1, count, "one per pack, not one per brush")
        // And it is on exactly one brush, so the count and the placement agree.
        val withIt = library.brushes.filter { r -> r.warnings.any { it.contains(SCALE_WARNING_MARKER) } }
        assertEquals(1, withIt.size)
        val sentence = withIt[0].warnings.first { it.contains(SCALE_WARNING_MARKER) }
        assertTrue(sentence.contains("plotSpacing"), sentence)
        assertTrue(sentence.contains("plotJitter"), sentence)
        assertTrue(sentence.contains("dynamicsJitterLightness"), sentence)
    }

    // ---- 16. THE COLOUR-JITTER SCALE TESTS (Decision 3a) ---------------------------------------------

    @Test
    fun aJitterOfZeroMapsToZeroAndSaysNothing() {
        // Both readings agree at zero, so there is nothing to say — and saying something anyway is
        // how a warning becomes noise.
        val result = ProcreateImport.convertBrush(withShape(jitter("dynamicsJitterLightness", 0.0), png(9, 9)), "pack")
        assertEquals(0f, result.preset(0).color.value)
        assertEquals(
            0,
            result.brushes[0].warnings.count { it.contains(SCALE_WARNING_MARKER) },
            "zero must not produce a scale warning",
        )
        assertEquals(emptyList(), BrushValidate.validate(result.preset(0)))
    }

    @Test
    fun aJitterOfAHalfMapsToAHalfAndNotToFiveThousandthsOfIt() {
        // **The case JB-8.01's first draft got wrong, and it failed silently.** A blind `/100` turns a
        // real 0.5 into 0.005, which is still inside 0f..1f, so `BrushValidate` rule 15 says nothing,
        // no clamp fires, and no warning can be produced. A whole pack then loses its colour jitter
        // with nobody told. The magnitude decides, and 0 <= v <= 1 is the unresolved branch.
        val result = ProcreateImport.convertBrush(
            withShape(jitter("dynamicsJitterLightness", 0.5), png(9, 9)),
            "pack",
        )
        val p = result.preset(0)
        assertEquals(0.5f, p.color.value, "0.5 is not 0.005")
        assertTrue(p.color.value != 0.005f, "the blind /100 failure is back")
        assertEquals("0.5", p.extensions["procreate.dynamicsJitterLightness"], "the raw must survive")
        assertTrue(p.color.perStroke)
        // Named in the one per-pack sentence.
        val sentence = assertNotNull(
            result.brushes[0].warnings.firstOrNull { it.contains(SCALE_WARNING_MARKER) }
        )
        assertTrue(sentence.contains("dynamicsJitterLightness"), sentence)
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    @Test
    fun aJitterOfAHundredIsReadAsAPercentageAndClampsWithoutAWarning() {
        // 100 > 1, so the percentage branch: 100 / 100 = 1.0, which is inside range, so no per-brush
        // clamp warning. This is the boundary: one unit either side changes which branch runs.
        val result = ProcreateImport.convertBrush(
            withShape(jitter("dynamicsJitterLightness", 100.0), png(9, 9)),
            "pack",
        )
        val p = result.preset(0)
        assertEquals(1.0f, p.color.value)
        assertEquals(
            0,
            result.brushes[0].warnings.count { it.contains("clamps to 1.0") },
            "100/100 is inside 0..1, so there is nothing to clamp",
        )
        val sentence = assertNotNull(
            result.brushes[0].warnings.firstOrNull { it.contains(SCALE_WARNING_MARKER) }
        )
        assertTrue(sentence.contains("dynamicsJitterLightness"), sentence)
        assertTrue(sentence.contains("percentage"), sentence)
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    @Test
    fun aJitterOfTwoFiftyAndTenIsClampedAndBothNumbersAreNamed() {
        // 250 / 100 = 2.5, which is still out of range, so this is the one case that needs a per-brush
        // clamp warning naming the field and both numbers.
        val result = ProcreateImport.convertBrush(
            withShape(jitter("dynamicsJitterLightness", 250.0), png(9, 9)),
            "pack",
        )
        val p = result.preset(0)
        assertEquals(1.0f, p.color.value)
        val clamp = assertNotNull(
            result.brushes[0].warnings.firstOrNull { it.contains("dynamicsJitterLightness") && it.contains("clamps") }
        )
        assertTrue(clamp.contains("250"), "the raw value is not named: $clamp")
        assertTrue(clamp.contains("1.0"), "the clamped value is not named: $clamp")
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    // ---- 17. a built-in resource refuses the brush, and substitutes nothing (Decision 8) ---------------

    @Test
    fun aBuiltInShapeRefusesThatBrushAndTheOthersSurvive() {
        val bytes = brushSet(
            "aaa" to linkedMapOf("name" to "Good One", "maxSize" to 20.0),
            "bbb" to linkedMapOf("name" to "Wants A Built-in", "bundledShapePath" to "BrushStudio/SmoothRound"),
            "ccc" to linkedMapOf("name" to "Also Good", "maxSize" to 30.0),
        )
        val library = ProcreateImport.convertBrushSet(bytes, "pack")

        assertEquals(2, library.brushes.size, "the other two must survive")
        assertEquals(1, library.refused.size)
        val reason = library.refused[0].reason
        assertTrue(reason.contains("BrushStudio/SmoothRound"), "the resource is not named: $reason")
        assertTrue(reason.contains("built-in"), "the word \"built-in\" is not in the message: $reason")
        // Nothing is substituted, so no survivor points at a file the archive does not hold.
        for (result in library.brushes) {
            val p = result.preset
            if (p.tip.source == "image") {
                val stored = assertNotNull(p.extensions["procreate.tipImage"])
                assertTrue(p.tip.image == "tip.png")
                assertTrue(base64Decode(stored).isNotEmpty(), "an image tip must carry real bytes")
            } else {
                assertNull(p.tip.image, "${p.name} has a procedural tip and names an image anyway")
            }
        }
    }

    @Test
    fun aBuiltInGrainIsRefusedTheSameWayAndCarriesTheR40Sentence() {
        val bytes = brushSet(
            "aaa" to linkedMapOf("name" to "Wants A Built-in", "bundledGrainPath" to "BrushStudio/Grain 1"),
        )
        val refused = ProcreateImport.convertBrushSet(bytes, "pack").refused
        assertEquals(1, refused.size)
        assertTrue(refused[0].reason.contains("built-in"), refused[0].reason)
        assertTrue(refused[0].reason.contains("BrushStudio/Grain 1"), refused[0].reason)
        assertTrue(refused[0].reason.contains(TEXTURE_NOT_DRAWN), refused[0].reason)
    }

    // ---- 18. the taper family is LOSSY, not refused (Decision 9) --------------------------------------

    @Test
    fun theTaperFamilyConvertsAndDoesNotBecomeAShapeTaper() {
        val bytes = withShape(
            linkedMapOf(
                "name" to "Round Hard",
                "maxSize" to 23.0,
                "pencilTaperSize" to 0.4,
                "taperOpacity" to 0.6,
                "taperStartLength" to 12.0,
            ),
            png(37, 21),
        )
        val result = ProcreateImport.convertBrush(bytes, "pack")
        val p = result.preset(0)

        // It CONVERTED. R40 ruled this LOSSY, because refusing taper refuses most of Procreate.
        assertEquals(1, result.brushes.size)
        assertNotNull(p.extensions["procreate.taper"], "the raw taper pairs must survive")
        val said = result.brushes[0].warnings.joinToString("\n")
        assertTrue(said.contains("taper"), said)
        // **And it did not become a shape taper.** R4 §C: "Procreate 'taper' means stroke-end size
        // and opacity taper, a different thing. Keep both." `tip.taper` is the shape one.
        assertEquals(0f, p.tip.taper, "a Procreate stroke taper went into the shape taper")
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    // ---- 19. a flat XML archive converts (Decision 6) ------------------------------------------------

    @Test
    fun aFlatXmlArchiveConvertsToADefaultStampAndSaysSo() {
        val xml = "<plist><dict>" +
            "<key>name</key><string>Paper Dry</string>" +
            "<key>identifier</key><string>0F1E2D3C-0000-0000-0000-000000000000</string>" +
            "<key>authorName</key><string>Catherine</string>" +
            "</dict></plist>"
        val bytes = zipOf("Brush.archive" to xml.toByteArray(Charsets.UTF_8))
        val result = ProcreateImport.convertBrush(bytes, "pack")
        val p = result.preset(0)

        assertEquals("Paper Dry", p.name)
        assertEquals("Catherine", p.author)
        assertEquals("procedural", p.tip.source, "an empty brush has no bitmap to name")
        // It said so: this is the archive that converts *and* warns, and both facts are the point.
        val said = result.brushes[0].warnings.joinToString("\n")
        assertTrue(said.contains("no settings"), said)
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    // ---- 20. a bad curve refuses that brush and nothing else ----------------------------------------

    @Test
    fun aBadCurveRefusesOnlyTheBrushThatHasIt() {
        val good = linkedMapOf("name" to "Good One", "maxSize" to 20.0)
        val goodWithCurve = linkedMapOf(
            "name" to "Good With A Curve",
            "maxSize" to 21.0,
            "dynamicsPressureSizeCurve" to listOf("{0.0, 0.5}", "{1.0, 1.0}"),
        )
        val tooMany = linkedMapOf<String, Any?>(
            "name" to "Too Many Points",
            "maxSize" to 22.0,
            "dynamicsPressureSizeCurve" to (0 until 65).map { "{$it, 0.5}" },
        )
        val oneNumber = linkedMapOf(
            "name" to "One Number",
            "maxSize" to 22.0,
            "dynamicsPressureSizeCurve" to listOf("{0.5}"),
        )
        val xTooBig = linkedMapOf(
            "name" to "X Out Of Range",
            "maxSize" to 22.0,
            "dynamicsPressureSizeCurve" to listOf("{1.4, 0.5}"),
        )
        val yNotANumber = linkedMapOf(
            "name" to "Y Not A Number",
            "maxSize" to 22.0,
            "dynamicsPressureSizeCurve" to listOf("{0.5, nope}"),
        )

        val library = ProcreateImport.convertBrushSet(
            brushSet("a1" to good, "a2" to goodWithCurve, "a3" to tooMany, "a4" to oneNumber, "a5" to xTooBig, "a6" to yNotANumber),
            "pack",
        )

        assertEquals(2, library.brushes.size, "only the two good brushes: ${library.summary()}")
        assertEquals(listOf("Good One", "Good With A Curve"), library.brushes.map { it.preset.name })
        assertEquals(4, library.refused.size)

        // Four refusals with four *different* reasons, each naming the setting.
        val reasons = library.refused.map { it.reason }
        assertEquals(4, reasons.toSet().size, "the four reasons are not distinct:\n" + reasons.joinToString("\n"))
        assertTrue(reasons.any { it.contains("65 points") && it.contains("64") }, reasons.toString())
        assertTrue(reasons.any { it.contains("1 numbers, not 2") }, reasons.toString())
        assertTrue(reasons.any { it.contains("x 1.4") && it.contains("0..1") }, reasons.toString())
        assertTrue(reasons.any { it.contains("not a number") }, reasons.toString())
        for (r in reasons) assertTrue(r.contains("dynamicsPressureSize"), r)

        // Non-vacuity: the good brush is exactly what a file without the bad ones would have produced.
        val alone = ProcreateImport.convertBrush(brush(good), "pack")
        assertEquals(alone.brushes[0].preset, library.brushes[0].preset)
    }

    // ---- 21. a hostile entry name refuses (Decision 12) ----------------------------------------------

    @Test
    fun aHostileEntryNameIsRefusedAndNothingIsWrittenAnywhere() {
        val hostile = listOf("../evil.png", "/etc/passwd", "a\\b.png", "c:x.png", "with control", "a/./b", ".. ", "a/ /../b")
        for (name in hostile) {
            val e = assertFailsWith<BrushException>("\"$name\" should be refused") {
                ProcreateImport.convertBrush(zipOf("Brush.archive" to archiveFor(ROUND), name to png(4, 4)), "pack")
            }
            assertTrue(e.message!!.contains("refused"), "\"$name\": ${e.message}")
        }

        // And the stronger half of the rule: this importer writes **nothing at all**, so it cannot
        // write outside anywhere. The directory listing is asserted, not assumed.
        val dir = Files.createTempDirectory("jb802")
        try {
            val before = Files.list(dir).use { it.count() }
            for (name in hostile) {
                runCatching {
                    ProcreateImport.convertBrush(
                        zipOf("Brush.archive" to archiveFor(ROUND), name to png(4, 4)),
                        "pack",
                    )
                }
            }
            val after = Files.list(dir).use { it.count() }
            assertEquals(before, after, "the importer wrote something into a directory it was given")
            assertEquals(0L, Files.list(dir).use { it.count() }, "it should have written nothing at all")
        } finally {
            Files.list(dir).use { s -> s.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun aStoredEntryIsRefusedForItsStoredSize() {
        // Decision 5's "a STORED entry is copied, not inflated" is covered above; this is the other
        // side: a stored entry whose two sizes disagree is not a stored entry.
        val good = zipOf("Brush.archive" to archiveFor(ROUND))
        val e = assertFailsWith<BrushException> { ProcreateImport.convertBrush(good.take(40).toByteArray(), "pack") }
        assertTrue(e.message!!.contains("truncated") || e.message!!.contains("directory"), e.message!!)
    }

    // ---- 22. a zip bomb refuses before allocating (Decision 11) --------------------------------------

    @Test
    fun aZipBombIsRefusedByArithmeticAndQuickly() {
        // A 1 KiB payload whose directory **claims** 100 MiB. The ratio is over 100 000:1, and the
        // refusal is arithmetic on the directory's own numbers, so nothing is inflated and nothing is
        // allocated. The declared size is the file's claim, which is the thing the budget is about.
        val packed = deflate(ByteArray(1024) { it.toByte() }, 6)
        val bomb = zipOfRaw(RawEntry("Brush.archive", packed, 100 * 1024 * 1024))

        val start = System.nanoTime()
        val e = assertFailsWith<BrushException> { ProcreateImport.convertBrush(bomb, "pack") }
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue(e.message!!.contains("200:1"), "the ratio is not named: ${e.message}")
        assertTrue(millis < 5_000, "the bomb took ${millis}ms to refuse, which means it expanded it")
    }

    // ---- 23. every budget refuses, one at cap + 1 (Decision 11) --------------------------------------

    @Test
    fun theArchiveBytesBudgetRefuses() {
        // The size check is first, so this does not need a valid zip — it needs 256 MiB of array.
        val huge = ByteArray(ProcreateImport.MAX_ARCHIVE_BYTES.toInt() + 1)
        val e = assertFailsWith<BrushException> { ProcreateImport.convertBrush(huge, "pack") }
        assertTrue(e.message!!.contains("${ProcreateImport.MAX_ARCHIVE_BYTES}"), e.message!!)
    }

    @Test
    fun theEntryCountBudgetRefuses() {
        val all = ArrayList<Pair<String, ByteArray>>()
        all += "Brush.archive" to archiveFor(ROUND)
        for (i in 0..ProcreateImport.MAX_ENTRIES) all += "pad$i" to ByteArray(1)
        val bytes = zipOf(*all.toTypedArray())
        val e = assertFailsWith<BrushException> { ProcreateImport.convertBrush(bytes, "pack") }
        assertTrue(e.message!!.contains("${ProcreateImport.MAX_ENTRIES}"), e.message!!)
    }

    @Test
    fun theEntryBytesBudgetRefuses() {
        // A stored entry of 64 MiB + 1. The cap is on the bytes **in the file**, so a stored entry is
        // the only way to reach it with a ratio of 1 and no inflation at all.
        val big = ByteArray(ProcreateImport.MAX_ENTRY_BYTES.toInt() + 1)
        val bytes = zipOf("Brush.archive" to archiveFor(ROUND), "Shape.png" to big, method = ZipOutputStream.STORED)
        val e = assertFailsWith<BrushException> { ProcreateImport.convertBrush(bytes, "pack") }
        assertTrue(e.message!!.contains("${ProcreateImport.MAX_ENTRY_BYTES}"), e.message!!)
    }

    @Test
    fun theEntryNameLengthBudgetRefuses() {
        val long = "n".repeat(ProcreateImport.MAX_ENTRY_NAME_CHARS + 1)
        val bytes = zipOf("Brush.archive" to archiveFor(ROUND), long to ByteArray(1))
        val e = assertFailsWith<BrushException> { ProcreateImport.convertBrush(bytes, "pack") }
        assertTrue(e.message!!.contains("${ProcreateImport.MAX_ENTRY_NAME_CHARS}"), e.message!!)
    }

    @Test
    fun theInflatedBytesBudgetRefuses() {
        // The directory claims 64 MiB + 1 out of a payload whose ratio is 134:1 — comfortably inside
        // the 200:1 cap, so the **ratio** budget passes and the *inflated* budget is the one that
        // fires. Choosing the two numbers independently is the point: a fixture that only ever
        // produced a high-ratio bomb could not tell the two refusals apart.
        val payload = deflate(incompressible(500 * 1024), 6)
        val ratio = (ProcreateImport.MAX_INFLATED_BYTES + 1) / payload.size.toLong()
        assertTrue(ratio in 1..ProcreateImport.MAX_INFLATE_RATIO, "the fixture's own ratio is $ratio, too high to be useful")
        val bytes = zipOfRaw(
            RawEntry("Brush.archive", payload, (ProcreateImport.MAX_INFLATED_BYTES + 1).toInt())
        )
        val e = assertFailsWith<BrushException> { ProcreateImport.convertBrush(bytes, "pack") }
        assertTrue(e.message!!.contains("inflates to"), e.message!!)
        assertTrue(e.message!!.contains("${ProcreateImport.MAX_INFLATED_BYTES}"), e.message!!)
    }

    @Test
    fun theBrushesPerPackBudgetRefuses() {
        val entries = (0..ProcreateImport.MAX_BRUSHES).map { i ->
            "u$i" to linkedMapOf("name" to "Brush $i", "maxSize" to 20.0)
        }.toTypedArray()
        val bytes = brushSet(*entries)
        val e = assertFailsWith<BrushException> { ProcreateImport.convertBrushSet(bytes, "pack") }
        assertTrue(e.message!!.contains("${ProcreateImport.MAX_BRUSHES}"), e.message!!)
    }

    // ---- 24. malformed archives throw; they do not return empty (Decision 15) -----------------------

    @Test
    fun malformedArchivesThrowRatherThanReturningAnEmptyLibrary() {
        val notAZip = "this is not a zip at all, it is just some text".toByteArray(Charsets.UTF_8)
        val noArchive = zipOf("something.png" to ByteArray(4))
        val truncatedBplist = zipOf("Brush.archive" to "bplist00".toByteArray())
        val empty = ByteArray(0)
        val three = byteArrayOf(1, 2, 3)

        for ((what, bytes) in listOf(
            "not a zip" to notAZip,
            "a zip with no Brush.archive" to noArchive,
            "a truncated bplist" to truncatedBplist,
            "an empty file" to empty,
            "a 3-byte file" to three,
        )) {
            val e = assertFailsWith<BrushException>("$what should throw") {
                ProcreateImport.convertBrush(bytes, "pack")
            }
            assertTrue(e.message!!.isNotBlank(), "$what threw with no sentence")
        }
    }

    @Test
    fun aBrushSetWithNoFoldersIsAnEmptyLibraryWithBothListsEmpty() {
        // A pack that is a zip with nothing in it is a real outcome, and saying so honestly — an empty
        // library — is different from the fault cases above, which must throw.
        val library = ProcreateImport.convertBrushSet(zipOf("brushset.plist" to PLIST), "pack")
        assertEquals(0, library.brushes.size)
        assertEquals(0, library.refused.size)
        assertTrue(library.summary().isNotBlank())
        assertTrue(library.summary().contains("nothing"), library.summary())
    }

    // ---- 25. the set's order and name come from `brushset.plist` (Decisions 15, 16) -------------------

    @Test
    fun theSetsOrderComesFromThePlistAndTheSetsNamePrefixesEveryBrush() {
        // Folder names sort aaa < bbb < ccc. The plist says ccc, aaa, bbb. The import must follow the
        // plist, and every name must be the set's name, U+00B7, and the brush's own name.
        val bytes = brushSet(
            "aaa" to linkedMapOf("name" to "A", "maxSize" to 20.0),
            "bbb" to linkedMapOf("name" to "B", "maxSize" to 21.0),
            "ccc" to linkedMapOf("name" to "C", "maxSize" to 22.0),
            plist = setPlist("My Pack", listOf("ccc", "aaa", "bbb")),
        )
        val library = ProcreateImport.convertBrushSet(bytes, "pack")
        assertEquals(listOf("C", "A", "B"), library.brushes.map { it.preset.name.substringAfter(" · ") })
        for (p in library.brushes.map { it.preset }) {
            assertTrue(p.name.startsWith("My Pack" + ProcreateImport.SET_SEPARATOR), p.name)
            assertEquals(brushId("pack", p.name), p.id)
        }
        // Ids stay unique across packs that both contain the same brush name.
        val other = ProcreateImport.convertBrushSet(
            brushSet("aaa" to linkedMapOf("name" to "A", "maxSize" to 20.0), plist = setPlist("Other Pack", null)),
            "pack",
        )
        assertTrue(other.brushes[0].preset.id != library.brushes[0].preset.id, "two packs collided on an id")
    }

    @Test
    fun aSetWithACorruptPlistImportsInNameOrderNamesNoPrefixAndWarnsOnce() {
        val bytes = brushSet(
            "aaa" to linkedMapOf("name" to "A", "maxSize" to 20.0),
            "bbb" to linkedMapOf("name" to "B", "maxSize" to 21.0),
            "ccc" to linkedMapOf("name" to "C", "maxSize" to 22.0),
            plist = "not a plist at all".toByteArray(Charsets.UTF_8),
        )
        val library = ProcreateImport.convertBrushSet(bytes, "pack")
        assertEquals(listOf("A", "B", "C"), library.brushes.map { it.preset.name }, "name order")
        for (p in library.brushes.map { it.preset }) {
            assertTrue(!p.name.contains("·"), "a set with no name must not prefix: ${p.name}")
        }
        var said = 0
        for (result in library.brushes) {
            said += result.warnings.count { it.contains("brushset.plist") }
        }
        assertEquals(1, said, "the pack fact is said once, not once per brush")
    }

    @Test
    fun aSetWithNoPlistAtAllImportsInNameOrderAndSaysSoOnce() {
        val bytes = zipOf(
            "aaa/Brush.archive" to archiveFor(linkedMapOf("name" to "A", "maxSize" to 20.0)),
            "bbb/Brush.archive" to archiveFor(linkedMapOf("name" to "B", "maxSize" to 21.0)),
        )
        val library = ProcreateImport.convertBrushSet(bytes, "pack")
        assertEquals(listOf("A", "B"), library.brushes.map { it.preset.name })
        assertEquals(1, library.brushes.sumOf { r -> r.warnings.count { it.contains("no brushset.plist") } })
    }

    @Test
    fun aDualBrushIsKeptRawAndWarnsRatherThanBecomingTwoPresets() {
        val bytes = zipOf(
            "Brush.archive" to archiveFor(ROUND),
            "Sub01/Brush.archive" to archiveFor(linkedMapOf("name" to "Sub", "maxSize" to 9.0)),
            "Sub01/Shape.png" to png(12, 12),
        )
        val result = ProcreateImport.convertBrush(bytes, "pack")
        assertEquals(1, result.brushes.size, "a dual brush is one brush, not two")
        assertEquals("true", result.preset(0).extensions["procreate.dual"])
        assertTrue(result.brushes[0].warnings.any { it.contains("dual brush") })
    }

    // ---- the clamp paths ----------------------------------------------------------------------------

    @Test
    fun everyClampNamesTheFieldAndBothNumbers() {
        val bytes = withShape(
            linkedMapOf(
                "name" to "Round Hard",
                "maxSize" to 99_999.0,               // over MAX_SIZE_PX
                "shapeCount" to 500.0,               // over 1..16
                "plotSpacing" to 42.0,               // over 0.005..5
                "textureScale" to 0.0,               // a grain scale must be above 0
                "shapeRoundness" to 4.0,             // over -1..1
            ),
            png(37, 21),
        )
        val result = ProcreateImport.convertBrush(bytes, "pack")
        val p = result.preset(0)
        val said = result.brushes[0].warnings.joinToString("\n")

        assertEquals(BrushValidate.MAX_SIZE_PX, p.size.base)
        assertTrue(said.contains("99999.0 px") && said.contains("${BrushValidate.MAX_SIZE_PX}"), said)
        assertEquals(16, p.scatter.count)
        assertTrue(said.contains("shapeCount 500") && said.contains("clamped to 16"), said)
        assertTrue(said.contains("plotSpacing 42.0"), said)
        assertTrue(said.contains("textureScale 0.0"), said)
        assertEquals(1f, p.tip.aspect)
        assertTrue(said.contains("shapeRoundness 4.0"), said)
        // Everything clamped, so the validator is silent — which is the whole of Decision 14.
        assertEquals(emptyList(), BrushValidate.validate(p), said)
    }

    @Test
    fun aLicenceIsOnlyEverCopiedFromTheArchiveAndNeverInvented() {
        val stated = ProcreateImport.convertBrush(withShape(statedLicence(), png(9, 9)), "pack").preset(0)
        assertEquals("CC-BY 4.0", stated.license)
        val silent = ProcreateImport.convertBrush(withShape(ROUND, png(9, 9)), "pack").preset(0)
        assertEquals("unknown", silent.license)
        assertTrue(silent.license != "CC0", "\"CC0\" is BrushPreset's own default and a claim nobody made")
    }

    // ---- fixtures -----------------------------------------------------------------------------------

    private class BrushFolder(
        val uuid: String,
        val root: Map<String, Any?>,
        val shape: ByteArray?,
        val grain: ByteArray?,
    )

    private fun archiveFor(root: Map<String, Any?>): ByteArray = PlistFixture.keyed(root)

    private fun brush(root: Map<String, Any?>, shape: ByteArray? = null, grain: ByteArray? = null): ByteArray {
        val e = ArrayList<Pair<String, ByteArray>>()
        e += "Brush.archive" to archiveFor(root)
        shape?.let { e += "Shape.png" to it }
        grain?.let { e += "Grain.png" to it }
        return zipOf(*e.toTypedArray())
    }

    private fun withShape(root: Map<String, Any?>, shape: ByteArray): ByteArray = brush(root, shape = shape)
    private fun withGrain(root: Map<String, Any?>, grain: ByteArray): ByteArray = brush(root, grain = grain)
    private fun withShapeAndGrain(root: Map<String, Any?>, s: ByteArray, g: ByteArray): ByteArray =
        brush(root, shape = s, grain = g)

    /** One entry per UUID folder, each with its own `Brush.archive`, plus the set's plist. */
    private fun brushSet(vararg folders: Pair<String, Map<String, Any?>>, plist: ByteArray = PLIST): ByteArray {
        val e = ArrayList<Pair<String, ByteArray>>()
        for ((uuid, root) in folders) e += "$uuid/Brush.archive" to archiveFor(root)
        e += "brushset.plist" to plist
        return zipOf(*e.toTypedArray())
    }

    private fun setPlist(setName: String, order: List<String>?): ByteArray {
        val body = StringBuilder("<plist><dict><key>name</key><string>$setName</string>")
        if (order != null) {
            body.append("<key>brushes</key><array>")
            for (id in order) {
                body.append("<dict><key>brushID</key><string>$id</string></dict>")
            }
            body.append("</array>")
        }
        body.append("</dict></plist>")
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    private val PLIST = "<plist><dict><key>brushes</key><array/></dict></plist>".toByteArray(Charsets.UTF_8)

    private val ROUND = linkedMapOf<String, Any?>(
        "name" to "Round Hard",
        "identifier" to "0F1E2D3C-0000-0000-0000-000000000000",
        "authorName" to "Someone",
        "maxSize" to 23.0,
        "shapeAngle" to 17.0,
        "plotSpacing" to 0.12,
        "dynamicsPressureSize" to 0.5,
        "shapeCount" to 1,
    )

    private val SOFT = linkedMapOf<String, Any?>(
        "name" to "Soft Pastel",
        "authorName" to "Someone Else",
        "maxSize" to 31.0,
        "grainDepth" to 0.35,
        "textureScale" to 4.0,
        "plotJitter" to 0.2,
    )

    private fun jitter(key: String, value: Double): Map<String, Any?> = linkedMapOf(
        "name" to "Round Hard", "maxSize" to 23.0, key to value,
    )

    private fun statedLicence(): Map<String, Any?> = linkedMapOf(
        "name" to "Round Hard", "maxSize" to 23.0, "license" to "CC-BY 4.0",
    )

    /**
     * A PNG carrying only the signature and a real `IHDR` — a header read, not a decode.
     *
     * The chunk length, the type and the payload are the PNG specification's own, and the CRC is
     * computed rather than left as zeroes, so this is a structurally valid PNG even though it has no
     * pixels. `walkPngChunks` stops at the first `IHDR`, which is all `readPngHeader` needs and is
     * also why nothing in this importer ever decodes an image.
     */
    private fun png(width: Int, height: Int): ByteArray {
        val data = ByteArray(13)
        writeBe32(data, 0, width)
        writeBe32(data, 4, height)
        data[8] = 8          // bit depth
        data[9] = 6          // colour type: RGBA
        val crc = CRC32()
        crc.update("IHDR".toByteArray(Charsets.US_ASCII))
        crc.update(data)
        val out = ArrayList<Byte>()
        for (b in byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) out.add(b)
        for (b in be32(13)) out.add(b)
        "IHDR".forEach { out.add(it.code.toByte()) }
        for (b in data) out.add(b)
        for (b in be32(crc.value)) out.add(b)
        return out.toByteArray()
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

    /** A real zip, written by the JDK. The reader under test is compared against the same library. */
    private fun zipOf(
        vararg entries: Pair<String, ByteArray>,
        method: Int = ZipOutputStream.DEFLATED,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (method == ZipEntry.STORED) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /**
     * One entry of [zipOfRaw]: the bytes to store, and **the uncompressed size the directory claims**.
     *
     * The two are separate because a hostile zip is exactly a directory that lies about the second —
     * and Decision 5's "the declared uncompressed size is **not** believed" is only testable when the
     * test can set it freely. Every budget below is therefore a test of what the *directory* says,
     * which is the file's claim and not its content.
     */
    private class RawEntry(val name: String, val payload: ByteArray, val declaredUncompressed: Int)

    /**
     * A zip whose method-8 payloads are **exactly the bytes given**, and whose sizes are the ones
     * stated.
     *
     * `ZipOutputStream` cannot produce this: it deflates whatever it is handed, and it always writes
     * the true sizes. A hand-written zip is the only way to state a size the payload does not have,
     * which is what the ratio and inflated-bytes budgets are about. Every length is written
     * consistently, so the archive is self-describing; the CRC is left at zero because the reader does
     * not check it and a test that invented a wrong CRC would be testing the wrong thing.
     */
    private fun zipOfRaw(vararg entries: RawEntry): ByteArray {
        val out = ArrayList<Byte>()
        val directory = ArrayList<Byte>()
        for (e in entries) {
            val nameBytes = e.name.toByteArray(Charsets.UTF_8)
            val compressed = e.payload.size.toLong()
            val uncompressed = e.declaredUncompressed.toLong()
            val localOffset = out.size
            for (b in le32(0x04034b50L)) out.add(b)
            for (b in le16(20)) out.add(b)                        // version needed
            for (b in le16(0)) out.add(b)                         // flags
            for (b in le16(8)) out.add(b)                         // method: deflate
            for (b in le16(0)) out.add(b)                         // time
            for (b in le16(0)) out.add(b)                         // date
            for (b in le32(0)) out.add(b)                         // crc
            for (b in le32(compressed)) out.add(b)
            for (b in le32(uncompressed)) out.add(b)
            for (b in le16(nameBytes.size)) out.add(b)
            for (b in le16(0)) out.add(b)                         // extra
            for (b in nameBytes) out.add(b)
            for (b in e.payload) out.add(b)

            for (b in le32(0x02014b50L)) directory.add(b)
            for (b in le16(20)) directory.add(b)                  // version made by
            for (b in le16(20)) directory.add(b)                  // version needed
            for (b in le16(0)) directory.add(b)                   // flags
            for (b in le16(8)) directory.add(b)                   // method: deflate
            for (b in le16(0)) directory.add(b)
            for (b in le16(0)) directory.add(b)
            for (b in le32(0)) directory.add(b)                   // crc
            for (b in le32(compressed)) directory.add(b)
            for (b in le32(uncompressed)) directory.add(b)
            for (b in le16(nameBytes.size)) directory.add(b)
            for (b in le16(0)) directory.add(b)                   // extra
            for (b in le16(0)) directory.add(b)                   // comment
            for (b in le16(0)) directory.add(b)                   // disk
            for (b in le16(0)) directory.add(b)                   // internal attributes
            for (b in le32(0)) directory.add(b)                   // external attributes
            for (b in le32(localOffset.toLong())) directory.add(b)
            for (b in nameBytes) directory.add(b)
        }
        val directoryOffset = out.size
        for (b in directory) out.add(b)
        for (b in le32(0x06054b50L)) out.add(b)
        for (b in le16(0)) out.add(b)                             // this disk
        for (b in le16(0)) out.add(b)                             // disk with the directory
        for (b in le16(entries.size)) out.add(b)
        for (b in le16(entries.size)) out.add(b)
        for (b in le32(directory.size.toLong())) out.add(b)
        for (b in le32(directoryOffset.toLong())) out.add(b)
        for (b in le16(0)) out.add(b)                             // comment length
        return out.toByteArray()
    }

    /**
     * A **zlib** stream (RFC 1950): the two-byte header, the DEFLATE data, and the trailing Adler-32.
     *
     * `DeflaterOutputStream` rather than a bare `Deflater` with `nowrap = false`, because the adler-32
     * is part of the stream and `Deflater.finished()` reports the *deflate* part done before the
     * checksum is emitted — so a hand-rolled loop stops four bytes early and produces a zlib header
     * wrapped around a truncated stream. That is a good illustration of why the reader checks for a
     * zlib header at all, and of why the oracle should be the JDK's stream classes.
     */
    private fun zlib(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DeflaterOutputStream(bos).use { it.write(bytes); it.finish() }
        return bos.toByteArray()
    }

    private fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    /**
     * A **raw DEFLATE** stream (RFC 1951), which is what a zip entry's method 8 really is.
     *
     * The JDK's `Deflater` with `nowrap = true`, so the bytes are the same kind of thing `ZipOutputStream`
     * would have put in the entry — which is what makes the bombs below honest rather than convenient.
     */
    private fun deflate(input: ByteArray, level: Int): ByteArray {
        val d = Deflater(level, /* nowrap = */ true)
        d.setInput(input)
        d.finish()
        val out = ArrayList<Byte>(input.size / 2 + 16)
        val chunk = ByteArray(16 * 1024)
        while (!d.finished()) {
            val n = d.deflate(chunk)
            if (n <= 0) break
            for (i in 0 until n) out.add(chunk[i])
        }
        d.end()
        return out.toByteArray()
    }

    /**
     * Bytes DEFLATE cannot compress, so an entry built from them has a ratio of about 1:1.
     *
     * Needed whenever a test wants a *large* payload to reach a later budget: a repeating pattern
     * compresses to roughly 250:1, which trips the ratio budget first and tests the wrong refusal. A
     * linear congruential sequence is deterministic, needs no `java.util.Random` seed argument, and
     * has no runs for the matcher to find.
     */
    private fun incompressible(n: Int): ByteArray {
        var state = -0x61c8864680b583ebL
        return ByteArray(n) {
            state = state * 6364136223846793005L + 1442695040888963407L
            (state shr 33).toByte()
        }
    }

    /**
     * RFC 4648 §4, written independently of the importer's encoder so the check is an oracle and not
     * a tautology. Three bytes are 24 bits and each of four characters takes six, so the indices are
     * `(n >> 18) & 63`, `(n >> 12) & 63`, `(n >> 6) & 63`, `n & 63`; `=` ends the stream.
     */
    private fun base64Decode(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (c in text) {
            if (c == '=') break
            val v = BASE64.indexOf(c)
            check(v >= 0) { "not base64: '$c'" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.toByteArray()
    }

    /** ceil(n / 3) * 4, which is what Decision 3's cap is compared against. */
    private fun base64Length(bytes: ByteArray): Int = (bytes.size + 2) / 3 * 4

    /** RFC 4648 §4's standard alphabet, `A–Z a–z 0–9 + /`. */
    private val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun ImportLibrary.preset(i: Int) = brushes[i].preset
}
